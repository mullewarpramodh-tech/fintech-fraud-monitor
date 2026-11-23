"""
Synthetic FinTech Fraud Dataset Generator
----------------------------------------
Generates a realistic, domain-engineered transaction dataset for fraud detection.

DISCLAIMER / DATA CARD:
This dataset is entirely SYNTHETIC, generated for testing, algorithmic prototyping,
and microservices integration. It reflects simulated transactional behavior, statistical
distributions, and synthetic fraud patterns. Performance metrics achieved on this
dataset do NOT constitute or guarantee real-world fraud detection efficacy in production.
"""

import math
import random
from datetime import datetime, timedelta
from typing import List, Dict
import numpy as np
import pandas as pd


def generate_synthetic_transactions(
    num_accounts: int = 1000,
    num_days: int = 30,
    target_total_transactions: int = 40000,
    random_seed: int = 42
) -> pd.DataFrame:
    np.random.seed(random_seed)
    random.seed(random_seed)

    start_time = datetime(2026, 1, 1, 0, 0, 0)
    total_seconds = num_days * 24 * 3600

    # 1. Define account profiles
    # Each account has a baseline spending tier and daily transaction rate
    account_ids = [f"ACC-{i:05d}" for i in range(1, num_accounts + 1)]
    profiles = {}
    
    for acc in account_ids:
        tier_p = np.random.rand()
        if tier_p < 0.60:
            # Everyday retail spender
            mean_amt = np.random.uniform(25.0, 120.0)
            daily_tx_rate = np.random.uniform(0.8, 2.5)
        elif tier_p < 0.85:
            # Active / affluent consumer
            mean_amt = np.random.uniform(150.0, 450.0)
            daily_tx_rate = np.random.uniform(1.5, 4.0)
        elif tier_p < 0.95:
            # High net worth / corporate card
            mean_amt = np.random.uniform(600.0, 2200.0)
            daily_tx_rate = np.random.uniform(0.5, 2.0)
        else:
            # Micro-transaction user (e.g. transit, subscriptions)
            mean_amt = np.random.uniform(5.0, 25.0)
            daily_tx_rate = np.random.uniform(2.0, 6.0)

        profiles[acc] = {
            "mean_amt": mean_amt,
            "daily_tx_rate": daily_tx_rate,
            "std_amt": mean_amt * 0.45
        }

    # 2. Simulate raw transactions over the timeline
    raw_tx_list = []
    
    # Calculate estimated transactions per account
    for acc_id, prof in profiles.items():
        expected_tx = int(prof["daily_tx_rate"] * num_days)
        # Poisson or negative binomial count of transactions
        actual_tx_count = max(1, np.random.poisson(expected_tx))
        
        for _ in range(actual_tx_count):
            # Sample time with circadian weighting (peak in afternoon, low in early morning)
            day = np.random.uniform(0, num_days)
            # Sample hour using beta distribution shifted to daytime
            hour_sample = (np.random.beta(5, 2) * 24) % 24
            second_in_day = hour_sample * 3600
            tx_second = day * 86400 + second_in_day
            tx_time = start_time + timedelta(seconds=min(tx_second, total_seconds - 1))
            
            # Normal amount from lognormal-like distribution centered on profile mean
            amt = float(np.random.gamma(shape=4.0, scale=prof["mean_amt"] / 4.0))
            amt = max(1.0, round(amt, 2))
            
            raw_tx_list.append({
                "accountId": acc_id,
                "timestamp": tx_time,
                "amount": amt,
                "currency": "USD",
                "is_fraud_candidate": False,
                "attack_type": "NONE"
            })

    # 3. Inject synthetic fraud attack scenarios (approx 2% of total transactions)
    # We introduce four distinct fraud patterns:
    # A) Account Takeover (ATO): Sudden spike far exceeding account historical mean
    # B) Card Testing / Micro-probing: rapid sequence of tiny transactions
    # C) Burst Velocity: clustered high frequency within 60s
    # D) High-Value Drain: large uncharacteristic withdrawals/transfers
    num_attacks = int(len(raw_tx_list) * 0.022)
    selected_victim_accounts = np.random.choice(account_ids, size=num_attacks, replace=True)
    
    for victim in selected_victim_accounts:
        attack_kind = np.random.choice(["ATO", "CARD_TESTING", "VELOCITY_BURST", "HIGH_VALUE_DRAIN"], 
                                      p=[0.35, 0.25, 0.20, 0.20])
        victim_prof = profiles[victim]
        rand_day = np.random.uniform(2, num_days - 1)
        
        if attack_kind == "ATO":
            # Occurs at irregular hours (night), 5x to 25x historical average
            night_hour = np.random.choice([1, 2, 3, 4, 23]) + np.random.uniform(0, 0.9)
            t_sec = rand_day * 86400 + night_hour * 3600
            attack_time = start_time + timedelta(seconds=t_sec)
            attack_amt = round(float(victim_prof["mean_amt"] * np.random.uniform(6.0, 20.0)), 2)
            raw_tx_list.append({
                "accountId": victim,
                "timestamp": attack_time,
                "amount": max(150.0, attack_amt),
                "currency": "USD",
                "is_fraud_candidate": True,
                "attack_type": "ATO"
            })
            
        elif attack_kind == "CARD_TESTING":
            # 2 to 4 micro transactions in quick succession (10 to 45 seconds apart)
            base_hour = np.random.uniform(0, 24)
            t_base = rand_day * 86400 + base_hour * 3600
            for step in range(np.random.randint(2, 5)):
                step_time = start_time + timedelta(seconds=t_base + step * np.random.uniform(8, 35))
                step_amt = round(float(np.random.uniform(2.0, 18.0)), 2)
                raw_tx_list.append({
                    "accountId": victim,
                    "timestamp": step_time,
                    "amount": step_amt,
                    "currency": "USD",
                    "is_fraud_candidate": True,
                    "attack_type": "CARD_TESTING"
                })
                
        elif attack_kind == "VELOCITY_BURST":
            # 3 to 5 transactions in under 60 seconds
            base_hour = np.random.uniform(0, 24)
            t_base = rand_day * 86400 + base_hour * 3600
            burst_amt = round(float(victim_prof["mean_amt"] * np.random.uniform(1.2, 3.5)), 2)
            for step in range(np.random.randint(3, 6)):
                step_time = start_time + timedelta(seconds=t_base + step * np.random.uniform(4, 12))
                raw_tx_list.append({
                    "accountId": victim,
                    "timestamp": step_time,
                    "amount": burst_amt,
                    "currency": "USD",
                    "is_fraud_candidate": True,
                    "attack_type": "VELOCITY_BURST"
                })
                
        elif attack_kind == "HIGH_VALUE_DRAIN":
            # Large transaction, but some legitimate transactions are also high!
            base_hour = np.random.uniform(0, 24)
            t_base = rand_day * 86400 + base_hour * 3600
            attack_time = start_time + timedelta(seconds=t_base)
            drain_amt = round(float(np.random.uniform(8500.0, 28000.0)), 2)
            raw_tx_list.append({
                "accountId": victim,
                "timestamp": attack_time,
                "amount": drain_amt,
                "currency": "USD",
                "is_fraud_candidate": True,
                "attack_type": "HIGH_VALUE_DRAIN"
            })

    # Sort strictly by timestamp to ensure chronological simulation
    raw_df = pd.DataFrame(raw_tx_list)
    raw_df = raw_df.sort_values(by="timestamp").reset_index(drop=True)

    # 4. Compute Historical Features sequentially without lookahead leakage
    # Definition rules:
    # - ONLY consider strictly prior transactions: t_prior < t_curr
    # - velocity_60s: count in [t_curr - 60s, t_curr)
    # - tx_count_last_1h: count in [t_curr - 3600s, t_curr)
    # - avg_amount_last_24h: mean(amount) in [t_curr - 86400s, t_curr)
    # - Cold-start handling: if no prior transactions exist in 24h, default avg_amount_last_24h = 100.0 (global prior)
    # - amount_to_avg_ratio = amount / (avg_amount_last_24h + 1e-5)
    # - cyclic hour: sin_hour = sin(2*pi*hour/24), cos_hour = cos(2*pi*hour/24)

    GLOBAL_PRIOR_AVG_AMOUNT = 100.0
    account_history: Dict[str, List[tuple]] = {}  # accountId -> list of (timestamp, amount)

    feature_records = []
    
    for idx, row in raw_df.iterrows():
        acc = row["accountId"]
        t_curr = row["timestamp"]
        amt = row["amount"]
        is_candidate = row["is_fraud_candidate"]
        attack_type = row["attack_type"]

        history = account_history.setdefault(acc, [])
        
        # 1. Purge entries older than 24 hours to keep history bounded
        cutoff_24h = t_curr - timedelta(seconds=86400)
        history = [entry for entry in history if entry[0] >= cutoff_24h]
        account_history[acc] = history

        # 2. Compute sliding window counts strictly prior to t_curr
        cutoff_60s = t_curr - timedelta(seconds=60)
        cutoff_1h = t_curr - timedelta(seconds=3600)

        velocity_60s = sum(1 for entry in history if entry[0] >= cutoff_60s)
        tx_count_last_1h = sum(1 for entry in history if entry[0] >= cutoff_1h)

        # 3. Compute 24-hour historical average (strictly prior transactions)
        if len(history) > 0:
            avg_amount_last_24h = float(np.mean([entry[1] for entry in history]))
        else:
            # Cold start: no prior transactions in last 24h
            avg_amount_last_24h = GLOBAL_PRIOR_AVG_AMOUNT

        amount_to_avg_ratio = float(amt / (avg_amount_last_24h + 1e-5))

        # 4. Cyclic hour of day encoding
        hour_float = t_curr.hour + t_curr.minute / 60.0 + t_curr.second / 3600.0
        sin_hour = float(math.sin(2.0 * math.pi * hour_float / 24.0))
        cos_hour = float(math.cos(2.0 * math.pi * hour_float / 24.0))

        # 5. Avoid Direct Label Leakage:
        # Ground truth label 'is_fraud' is generated using a latent multi-factor risk score
        # plus stochastic noise, so it is NOT a deterministic copy of any single rule.
        # Even with high amount or high velocity, there's non-zero probability of legitimacy,
        # and some normal-looking amounts have non-zero probability of fraud.
        base_log_odds = -5.2  # background low probability (~0.5%)
        
        # Multi-factor influence
        ratio_term = 1.1 * math.log1p(max(0.0, amount_to_avg_ratio - 1.0))
        velocity_term = 0.85 * velocity_60s
        freq_term = 0.35 * tx_count_last_1h
        amount_term = 0.55 * math.log1p(amt / 100.0)
        
        # Unusual hour penalty (late night / early morning 1am - 5am)
        hour_risk = 0.6 if (t_curr.hour in [1, 2, 3, 4]) else 0.0
        
        # If explicitly injected as candidate attack, strong positive driver
        candidate_term = 3.6 if is_candidate else 0.0
        
        # Random Gaussian noise (prevents deterministic separation)
        noise = np.random.normal(0.0, 0.65)
        
        total_logit = base_log_odds + ratio_term + velocity_term + freq_term + amount_term + hour_risk + candidate_term + noise
        
        # Logistic sigmoid probability
        prob_fraud = 1.0 / (1.0 + math.exp(-total_logit))
        
        # Sample ground truth label
        is_fraud = int(np.random.rand() < prob_fraud)

        feature_records.append({
            "transaction_id": idx + 1,
            "accountId": acc,
            "timestamp": t_curr.isoformat(),
            "amount": amt,
            "tx_count_last_1h": tx_count_last_1h,
            "avg_amount_last_24h": round(avg_amount_last_24h, 2),
            "amount_to_avg_ratio": round(amount_to_avg_ratio, 4),
            "velocity_60s": velocity_60s,
            "sin_hour": round(sin_hour, 6),
            "cos_hour": round(cos_hour, 6),
            "is_fraud": is_fraud,
            "attack_type": attack_type
        })

        # AFTER computing features, append current transaction to history for future events
        history.append((t_curr, amt))

    df = pd.DataFrame(feature_records)
    return df


if __name__ == "__main__":
    print("[*] Generating domain-engineered synthetic FinTech transaction dataset...")
    df = generate_synthetic_transactions(
        num_accounts=1000,
        num_days=30,
        random_seed=42
    )
    
    csv_path = "ml-service/data/synthetic_fraud_transactions.csv"
    df.to_csv(csv_path, index=False)
    print(f"[+] Dataset generated successfully with {len(df)} records.")
    print(f"[+] Saved to: {csv_path}")
    print(f"[+] Fraud prevalence: {df['is_fraud'].mean() * 100:.2f}% ({df['is_fraud'].sum()} fraud cases)")
