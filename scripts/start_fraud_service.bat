@echo off
title FinTech Fraud Detection Engine [Port 8082 + Kafka Consumer]
cd /d "%~dp0..\fraud-service"
echo =====================================================================
echo  FinTech Fraud Monitor - Fraud Detection Consumer Microservice
echo =====================================================================
echo [INFO] Service Port: http://localhost:8082
echo [INFO] Kafka Topic Subscribed: transaction-events
echo [INFO] Consumer Group: fraud-detection-group
echo [INFO] Fraud Rules: Single Txn > $10k OR Velocity > 3 txns / 60s
echo =====================================================================
mvn spring-boot:run
pause
