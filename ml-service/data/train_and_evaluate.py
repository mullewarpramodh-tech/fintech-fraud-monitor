"""
FinTech Fraud Detection — ML Training, Benchmark, and Calibration Pipeline
---------------------------------------------------------------------------
- Models: Logistic Regression Baseline vs. Calibrated Random Forest Benchmark
- Preprocessing: Zero-leakage StandardScaler fitted strictly on Train split
- Splitting: Chronological separation (70% Train, 15% Validation, 15% Held-Out Test)
- Metrics: Precision, Recall, F1, PR-AUC, ROC-AUC, Brier Score, Reliability Curve
"""

import json
import os
import joblib
import numpy as np
import pandas as pd
from sklearn.linear_model import LogisticRegression
from sklearn.ensemble import RandomForestClassifier
from sklearn.preprocessing import StandardScaler
from sklearn.pipeline import Pipeline
from sklearn.calibration import CalibratedClassifierCV, calibration_curve
from sklearn.metrics import (
    precision_score, recall_score, f1_score,
    roc_auc_score, average_precision_score,
    brier_score_loss, confusion_matrix
)


def run_training_pipeline():
    csv_path = "ml-service/data/synthetic_fraud_transactions.csv"
    if not os.path.exists(csv_path):
        raise FileNotFoundError(f"Dataset not found at {csv_path}")

    df = pd.read_csv(csv_path)
    df["timestamp"] = pd.to_datetime(df["timestamp"], format="ISO8601")
    df = df.sort_values(by="timestamp").reset_index(drop=True)

    features = [
        "amount",
        "tx_count_last_1h",
        "avg_amount_last_24h",
        "amount_to_avg_ratio",
        "velocity_60s",
        "sin_hour",
        "cos_hour"
    ]
    target = "is_fraud"

    # 1. Chronological Split (70% Train, 15% Val, 15% Test)
    n_total = len(df)
    n_train = int(n_total * 0.70)
    n_val = int(n_total * 0.15)

    train_df = df.iloc[:n_train].copy()
    val_df = df.iloc[n_train:n_train + n_val].copy()
    test_df = df.iloc[n_train + n_val:].copy()

    X_train, y_train = train_df[features], train_df[target]
    X_val, y_val = val_df[features], val_df[target]
    X_test, y_test = test_df[features], test_df[target]

    print("=" * 80)
    print("FINTECH FRAUD MONITOR — ML PIPELINE & BENCHMARKING REPORT")
    print("=" * 80)
    print(f"Total Transactions: {len(df):,} | Features: {len(features)}")
    print(f"Train Split: {len(train_df):,} ({train_df[target].mean()*100:.2f}% fraud)")
    print(f"Val Split:   {len(val_df):,} ({val_df[target].mean()*100:.2f}% fraud)")
    print(f"Test Split:  {len(test_df):,} ({test_df[target].mean()*100:.2f}% fraud)")
    print()

    # 2. Baseline Model: Logistic Regression
    lr_pipeline = Pipeline([
        ("scaler", StandardScaler()),
        ("classifier", LogisticRegression(class_weight="balanced", max_iter=1000, random_state=42))
    ])
    lr_pipeline.fit(X_train, y_train)
    lr_test_probs = lr_pipeline.predict_proba(X_test)[:, 1]

    # 3. Benchmark Model: Random Forest with Calibrated Probabilities
    rf_base_pipeline = Pipeline([
        ("scaler", StandardScaler()),
        ("classifier", RandomForestClassifier(
            n_estimators=100,
            max_depth=12,
            min_samples_split=10,
            random_state=42,
            n_jobs=-1
        ))
    ])
    # 3-fold cross-validation calibration preserves true posterior probabilities
    rf_calibrated = CalibratedClassifierCV(rf_base_pipeline, method="sigmoid", cv=3)
    rf_calibrated.fit(X_train, y_train)

    rf_val_probs = rf_calibrated.predict_proba(X_val)[:, 1]
    rf_test_probs = rf_calibrated.predict_proba(X_test)[:, 1]

    # 4. Threshold Tuning on Validation Set
    thresholds = np.linspace(0.05, 0.70, 66)
    best_th = 0.50
    best_f1 = -1.0
    best_p = 0.0
    best_r = 0.0

    for th in thresholds:
        preds = (rf_val_probs >= th).astype(int)
        p = precision_score(y_val, preds, zero_division=0)
        r = recall_score(y_val, preds, zero_division=0)
        f1 = f1_score(y_val, preds, zero_division=0)
        if f1 > best_f1:
            best_f1 = f1
            best_th = th
            best_p = p
            best_r = r

    # 5. Held-Out Test Evaluation
    models_dir = "ml-service/app/models"
    os.makedirs(models_dir, exist_ok=True)
    model_path = os.path.join(models_dir, "fraud_pipeline.joblib")
    meta_path = os.path.join(models_dir, "model_metadata.json")

    # Serialize trained calibrated model
    joblib.dump(rf_calibrated, model_path)

    rf_test_preds = (rf_test_probs >= best_th).astype(int)
    tn, fp, fn, tp = confusion_matrix(y_test, rf_test_preds).ravel()

    # Compare models
    print(f"{'Evaluation Metric':30s} | {'Logistic Regression':20s} | {'Calibrated Random Forest':25s}")
    print("-" * 80)
    print(f"{'Test ROC-AUC':30s} | {roc_auc_score(y_test, lr_test_probs):20.4f} | {roc_auc_score(y_test, rf_test_probs):25.4f}")
    print(f"{'Test PR-AUC':30s} | {average_precision_score(y_test, lr_test_probs):20.4f} | {average_precision_score(y_test, rf_test_probs):25.4f}")
    print(f"{'Test Brier Score (Calibration)':30s} | {brier_score_loss(y_test, lr_test_probs):20.4f} | {brier_score_loss(y_test, rf_test_probs):25.4f}")
    print(f"{'Optimal Threshold (from Val)':30s} | {'0.50 (default)':20s} | {f'{best_th:.2f} (tuned)':25s}")
    print(f"{'Test Precision @ Threshold':30s} | {precision_score(y_test, (lr_test_probs>=0.5).astype(int)):20.4f} | {precision_score(y_test, rf_test_preds):25.4f}")
    print(f"{'Test Recall @ Threshold':30s} | {recall_score(y_test, (lr_test_probs>=0.5).astype(int)):20.4f} | {recall_score(y_test, rf_test_preds):25.4f}")
    print(f"{'Test F1-Score @ Threshold':30s} | {f1_score(y_test, (lr_test_probs>=0.5).astype(int)):20.4f} | {f1_score(y_test, rf_test_preds):25.4f}")
    print()

    print(f"Confusion Matrix (Calibrated RF @ th={best_th:.2f}):")
    print(f"  True Positives (Frauds Caught):   {tp}")
    print(f"  False Positives (False Alarms):   {fp}")
    print(f"  True Negatives (Legit Approved):  {tn}")
    print(f"  False Negatives (Missed Frauds):  {fn}")
    print()

    # Calibration Reliability Curve
    frac_pos, mean_pred = calibration_curve(y_test, rf_test_probs, n_bins=8)
    print("Probability Calibration Curve (Calibrated RF on Test):")
    print(f"{'Predicted Risk Bin':25s} | {'Observed Fraud Rate':25s}")
    print("-" * 55)
    for p_pred, p_obs in zip(mean_pred, frac_pos):
        print(f"{p_pred:25.4f} | {p_obs:25.4f}")
    print()

    metadata = {
        "model_version": "v1.0.0",
        "model_type": "CalibratedClassifierCV(RandomForestClassifier)",
        "features": features,
        "optimal_threshold": round(float(best_th), 2),
        "validation_metrics": {
            "f1": round(float(best_f1), 4),
            "precision": round(float(best_p), 4),
            "recall": round(float(best_r), 4)
        },
        "test_metrics": {
            "roc_auc": round(float(roc_auc_score(y_test, rf_test_probs)), 4),
            "pr_auc": round(float(average_precision_score(y_test, rf_test_probs)), 4),
            "brier_score": round(float(brier_score_loss(y_test, rf_test_probs)), 4),
            "precision": round(float(precision_score(y_test, rf_test_preds)), 4),
            "recall": round(float(recall_score(y_test, rf_test_preds)), 4),
            "f1": round(float(f1_score(y_test, rf_test_preds)), 4),
            "confusion_matrix": {"TP": int(tp), "FP": int(fp), "TN": int(tn), "FN": int(fn)}
        },
        "disclaimer": "Domain-engineered synthetic dataset. Not for production financial settlement without manual review."
    }

    with open(meta_path, "w") as f:
        json.dump(metadata, f, indent=2)

    print(f"[+] Model pipeline saved to: {model_path}")
    print(f"[+] Model metadata saved to: {meta_path}")
    print("=" * 80)


if __name__ == "__main__":
    run_training_pipeline()
