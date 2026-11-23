"""
Unit and API Integration Tests for ML Fraud Detection Service
-------------------------------------------------------------
Tests valid inference, boundary validations, missing fields,
service health, model-loading failures, and prediction failure guards.
"""

import sys
from pathlib import Path
from unittest.mock import MagicMock
import pytest
from fastapi.testclient import TestClient

# Ensure ml-service root and app package are in sys.path
ml_service_dir = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ml_service_dir))

from app.main import app


@pytest.fixture
def client():
    """
    TestClient that enters and exits FastAPI lifespan to load the model.
    """
    with TestClient(app) as test_client:
        yield test_client


@pytest.fixture
def valid_features():
    return {
        "amount": 250.00,
        "tx_count_last_1h": 1,
        "avg_amount_last_24h": 120.00,
        "amount_to_avg_ratio": 2.0833,
        "velocity_60s": 0,
        "sin_hour": -0.7071,
        "cos_hour": 0.7071
    }


# =====================================================================
# 1. HEALTH ENDPOINT TESTS
# =====================================================================

def test_health_endpoint_healthy(client):
    """Should return 200 UP with model metadata when model is loaded."""
    response = client.get("/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "UP"
    assert data["model_loaded"] is True
    assert data["model_version"] == "v1.0.0"
    assert "amount" in data["features_expected"]
    assert len(data["features_expected"]) == 7


def test_health_endpoint_degraded():
    """Should return 503 DEGRADED when model fails to load or is None."""
    with TestClient(app) as test_client:
        # Simulate uninitialized model state
        original_model = app.state.model
        app.state.model = None
        try:
            response = test_client.get("/health")
            assert response.status_code == 503
            data = response.json()
            assert data["status"] == "DEGRADED"
            assert data["model_loaded"] is False
        finally:
            app.state.model = original_model


# =====================================================================
# 2. VALID INFERENCE PREDICTION TESTS
# =====================================================================

def test_predict_valid_clean_transaction(client, valid_features):
    """Normal transaction should return valid probability and version."""
    response = client.post("/predict", json=valid_features)
    assert response.status_code == 200
    data = response.json()
    assert "fraud_probability" in data
    assert "model_version" in data
    assert isinstance(data["fraud_probability"], float)
    assert 0.0 <= data["fraud_probability"] <= 1.0
    assert data["model_version"] == "v1.0.0"
    # Ensure no decision field is present in response
    assert "status" not in data
    assert "decision" not in data


def test_predict_high_risk_burst_transaction(client):
    """High-risk velocity burst transaction should return higher probability."""
    high_risk_features = {
        "amount": 9500.00,
        "tx_count_last_1h": 8,
        "avg_amount_last_24h": 50.00,
        "amount_to_avg_ratio": 190.0,
        "velocity_60s": 4,
        "sin_hour": 0.866,
        "cos_hour": 0.500
    }
    response = client.post("/predict", json=high_risk_features)
    assert response.status_code == 200
    data = response.json()
    assert data["fraud_probability"] > 0.14  # Exceeds tuned threshold


# =====================================================================
# 3. INPUT VALIDATION TESTS (INVALID VALUES)
# =====================================================================

def test_predict_negative_amount_fails(client, valid_features):
    """Negative amount must fail with 422 Unprocessable Entity."""
    payload = valid_features.copy()
    payload["amount"] = -50.0
    response = client.post("/predict", json=payload)
    assert response.status_code == 422


def test_predict_zero_amount_fails(client, valid_features):
    """Zero amount must fail with 422."""
    payload = valid_features.copy()
    payload["amount"] = 0.0
    response = client.post("/predict", json=payload)
    assert response.status_code == 422


def test_predict_negative_velocity_fails(client, valid_features):
    """Negative velocity count must fail with 422."""
    payload = valid_features.copy()
    payload["velocity_60s"] = -1
    response = client.post("/predict", json=payload)
    assert response.status_code == 422


def test_predict_invalid_cyclic_sine_fails(client, valid_features):
    """Out-of-range cyclic sine (> 1.0) must fail with 422."""
    payload = valid_features.copy()
    payload["sin_hour"] = 1.25
    response = client.post("/predict", json=payload)
    assert response.status_code == 422


def test_predict_invalid_cyclic_cosine_fails(client, valid_features):
    """Out-of-range cyclic cosine (< -1.0) must fail with 422."""
    payload = valid_features.copy()
    payload["cos_hour"] = -1.5
    response = client.post("/predict", json=payload)
    assert response.status_code == 422


# =====================================================================
# 4. MISSING FIELD TESTS
# =====================================================================

def test_predict_missing_amount_fails(client, valid_features):
    """Missing required feature must fail with 422."""
    payload = valid_features.copy()
    del payload["amount"]
    response = client.post("/predict", json=payload)
    assert response.status_code == 422


def test_predict_missing_velocity_fails(client, valid_features):
    """Missing velocity count must fail with 422."""
    payload = valid_features.copy()
    del payload["velocity_60s"]
    response = client.post("/predict", json=payload)
    assert response.status_code == 422


def test_predict_empty_body_fails(client):
    """Empty body must fail with 422."""
    response = client.post("/predict", json={})
    assert response.status_code == 422


# =====================================================================
# 5. RESILIENCE, ERROR HANDLING & INFORMATION LEAKAGE GUARDS
# =====================================================================

def test_predict_when_model_unready_returns_503(client, valid_features):
    """Service should return 503 if model is unready."""
    original_model = app.state.model
    app.state.model = None
    try:
        response = client.post("/predict", json=valid_features)
        assert response.status_code == 503
        assert "not ready" in response.json()["detail"].lower()
    finally:
        app.state.model = original_model


def test_predict_exception_does_not_leak_internals(client, valid_features):
    """Internal model inference failure should return clean 500 without stack trace."""
    original_model = app.state.model
    mock_model = MagicMock()
    mock_model.predict_proba.side_effect = RuntimeError("Sensitive internal path: /var/secrets/key.pem")
    app.state.model = mock_model
    try:
        response = client.post("/predict", json=valid_features)
        assert response.status_code == 500
        data = response.json()
        assert "detail" in data
        # Verify no traceback, secrets or internal paths leaked in detail
        assert "Sensitive internal path" not in data["detail"]
        assert "key.pem" not in data["detail"]
        assert "Traceback" not in data["detail"]
    finally:
        app.state.model = original_model
