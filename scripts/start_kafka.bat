@echo off
title Apache Kafka Server [Port 9092]
cd /d "C:\kafka"
echo =====================================================================
echo  Apache Kafka 4.4.0 (KRaft Standalone) - Port 9092
echo =====================================================================
call .\bin\windows\kafka-server-start.bat .\config\server.properties
pause
