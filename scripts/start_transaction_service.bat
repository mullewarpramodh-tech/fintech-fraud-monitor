@echo off
title FinTech Transaction Service [Port 8081 + Kafka 9092]
cd /d "%~dp0..\transaction-service"
echo =====================================================================
echo  FinTech Fraud Monitor - Transaction Ingestion Microservice
echo =====================================================================
echo [INFO] Service Port: http://localhost:8081
echo [INFO] Embedded Kafka Broker: localhost:9092
echo [INFO] Database: Neon Cloud Serverless PostgreSQL
echo =====================================================================
mvn spring-boot:run
pause
