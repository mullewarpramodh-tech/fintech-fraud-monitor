"""
Pydantic Schemas for FinTech Fraud Detection ML Service
-------------------------------------------------------
Enforces strict type and range validations on incoming transaction features
and guarantees predictable response structures.
"""

from typing import List, Optional
from pydantic import BaseModel, Field, field_validator


class TransactionFeaturesRequest(BaseModel):
    """
    Input schema validating the 7 engineered features required for fraud scoring.
    Definitions are strictly aligned with training pipeline data engineering.
    """
    amount: float = Field(
        ...,
        gt=0.0,
        description="Transaction amount in USD. Must be strictly positive."
    )
    tx_count_last_1h: int = Field(
        ...,
        ge=0,
        description="Transaction frequency for this account in the rolling 1-hour window."
    )
    avg_amount_last_24h: float = Field(
        ...,
        gt=0.0,
        description="Historical baseline average transaction amount in the rolling 24-hour window."
    )
    amount_to_avg_ratio: float = Field(
        ...,
        ge=0.0,
        description="Ratio of current transaction amount to 24-hour historical average."
    )
    velocity_60s: int = Field(
        ...,
        ge=0,
        description="Sliding-window transaction count in the last 60 seconds."
    )
    sin_hour: float = Field(
        ...,
        ge=-1.0,
        le=1.0,
        description="Sine of cyclic hour of day [-1.0, 1.0]."
    )
    cos_hour: float = Field(
        ...,
        ge=-1.0,
        le=1.0,
        description="Cosine of cyclic hour of day [-1.0, 1.0]."
    )

    model_config = {
        "json_schema_extra": {
            "example": {
                "amount": 250.00,
                "tx_count_last_1h": 1,
                "avg_amount_last_24h": 120.00,
                "amount_to_avg_ratio": 2.0833,
                "velocity_60s": 0,
                "sin_hour": -0.7071,
                "cos_hour": -0.7071
            }
        }
    }


class PredictionResponse(BaseModel):
    """
    Prediction output schema.
    Returns calibrated fraud probability and model version.
    Java backend remains solely responsible for business rules and final decision.
    """
    fraud_probability: float = Field(
        ...,
        ge=0.0,
        le=1.0,
        description="Calibrated posterior probability of transaction fraud."
    )
    model_version: str = Field(
        ...,
        description="Semantic version of the active inference model."
    )


class HealthResponse(BaseModel):
    """
    Service and model readiness health check schema.
    """
    status: str = Field(..., description="Service status ('UP' or 'DEGRADED')")
    model_loaded: bool = Field(..., description="Whether the ML pipeline is loaded in memory")
    model_version: Optional[str] = Field(None, description="Active model version")
    model_type: Optional[str] = Field(None, description="Model architecture/wrapper type")
    features_expected: Optional[List[str]] = Field(None, description="Ordered list of model input features")
