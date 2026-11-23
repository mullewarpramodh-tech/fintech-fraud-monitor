"""
FinTech Fraud Detection — ML Scoring FastAPI Service
---------------------------------------------------
Provides low-latency ML fraud scoring via POST /predict and health telemetry via GET /health.
Java fraud-service calls this service to obtain fraud probability scores while maintaining
sole responsibility for final transaction status decisions (APPROVED, FRAUD_DETECTED, MANUAL_REVIEW).
"""

import json
import logging
import os
from contextlib import asynccontextmanager
from pathlib import Path
import joblib
import pandas as pd
from fastapi import FastAPI, HTTPException, status
from fastapi.responses import JSONResponse

from app.schemas import (
    TransactionFeaturesRequest,
    PredictionResponse,
    HealthResponse
)

logger = logging.getLogger("ml_service")
logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")


@asynccontextmanager
async def lifespan(app: FastAPI):
    """
    Loads the trained model pipeline and metadata ONCE into application state
    during server startup. Avoids per-request disk reads or pipeline re-instantiation.
    """
    model_path_env = os.getenv("MODEL_PATH")
    meta_path_env = os.getenv("METADATA_PATH")

    model_path = Path(model_path_env) if model_path_env else Path(__file__).parent / "models" / "fraud_pipeline.joblib"
    meta_path = Path(meta_path_env) if meta_path_env else Path(__file__).parent / "models" / "model_metadata.json"

    app.state.model = None
    app.state.metadata = None

    try:
        if model_path.exists() and meta_path.exists():
            app.state.model = joblib.load(model_path)
            with open(meta_path, "r", encoding="utf-8") as f:
                app.state.metadata = json.load(f)
            logger.info("Successfully loaded model version %s from %s", 
                        app.state.metadata.get("model_version"), model_path.name)
        else:
            logger.error("Model artifacts missing: model_path=%s, meta_path=%s", model_path, meta_path)
    except Exception as e:
        logger.error("Failed to initialize ML model during startup: %s", str(e), exc_info=True)

    yield

    app.state.model = None
    app.state.metadata = None
    logger.info("ML Service shutdown complete.")


app = FastAPI(
    title="FinTech Fraud Detection ML Inference Service",
    description="Scores transaction fraud probability using a calibrated ML model pipeline.",
    version="1.0.0",
    lifespan=lifespan
)


@app.get("/", tags=["Root"])
def root():
    return {
        "service": "FinTech Fraud Detection ML Service",
        "status": "operational",
        "documentation": "/docs"
    }


@app.get("/health", response_model=HealthResponse, tags=["Telemetry"])
def health_check():
    """
    Reports service and model readiness. Returns 200 UP if model is ready for inference,
    or 503 DEGRADED if the model pipeline failed to load.
    """
    is_ready = getattr(app.state, "model", None) is not None and getattr(app.state, "metadata", None) is not None

    if not is_ready:
        return JSONResponse(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            content=HealthResponse(
                status="DEGRADED",
                model_loaded=False,
                model_version=None,
                model_type=None,
                features_expected=None
            ).model_dump()
        )

    return HealthResponse(
        status="UP",
        model_loaded=True,
        model_version=app.state.metadata.get("model_version"),
        model_type=app.state.metadata.get("model_type"),
        features_expected=app.state.metadata.get("features")
    )


@app.post("/predict", response_model=PredictionResponse, tags=["Inference"])
def predict_fraud_score(request: TransactionFeaturesRequest):
    """
    Computes calibrated fraud probability for the provided engineered transaction features.
    Guarantees feature ordering matches model metadata before scoring.
    """
    model = getattr(app.state, "model", None)
    metadata = getattr(app.state, "metadata", None)

    if model is None or metadata is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="ML inference engine is not ready or failed to initialize."
        )

    try:
        expected_features = metadata.get("features", [])
        raw_values = request.model_dump()

        # Build single-row DataFrame with strict feature order verification
        ordered_values = [raw_values[feature_name] for feature_name in expected_features]
        feature_df = pd.DataFrame([ordered_values], columns=expected_features)

        # Execute predict_proba on the calibrated pipeline
        probabilities = model.predict_proba(feature_df)
        fraud_prob = float(probabilities[0][1])

        return PredictionResponse(
            fraud_probability=round(fraud_prob, 4),
            model_version=metadata.get("model_version", "unknown")
        )

    except Exception as e:
        logger.error("Unexpected error during fraud prediction inference: %s", str(e), exc_info=True)
        # Security rule: Do not expose file paths, stack traces, or internal error details
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="An error occurred while evaluating transaction fraud probability."
        )
