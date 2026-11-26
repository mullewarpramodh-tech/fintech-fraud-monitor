<#
.SYNOPSIS
    Orchestrates live Java-to-FastAPI integration test without Docker.

.DESCRIPTION
    1. Launches FastAPI via Uvicorn locally on loopback.
    2. Polls /health until model readiness is confirmed.
    3. Executes the Maven live integration test suite against the running server.
    4. Cleanly shuts down the Python server process upon completion or error.
#>

[CmdletBinding()]
param(
    [int]$Port = 8000,
    [int]$MaxHealthWaitSeconds = 20
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path "$PSScriptRoot\.."
Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "   LIVE JAVA-TO-FASTAPI HTTP INTEGRATION VERIFICATION" -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "Repository Root: $repoRoot"

$pythonExe = "$repoRoot\ml-service\venv\Scripts\python.exe"
if (-not (Test-Path $pythonExe)) {
    Write-Error "Python virtual environment executable not found at $pythonExe"
}

$mlServiceDir = "$repoRoot\ml-service"
$healthUrl = "http://127.0.0.1:$Port/health"
$predictUrl = "http://127.0.0.1:$Port/predict"

Write-Host "`n[1/4] Starting FastAPI Uvicorn Server on 127.0.0.1:$Port..." -ForegroundColor Yellow

$serverProcess = Start-Process `
    -FilePath $pythonExe `
    -ArgumentList "-m uvicorn app.main:app --host 127.0.0.1 --port $Port" `
    -WorkingDirectory $mlServiceDir `
    -PassThru

try {
    Write-Host "Uvicorn process started (PID: $($serverProcess.Id))."
    Write-Host "`n[2/4] Waiting for /health model readiness..." -ForegroundColor Yellow

    $deadline = (Get-Date).AddSeconds($MaxHealthWaitSeconds)
    $ready = $false
    $healthData = $null

    while ((Get-Date) -lt $deadline) {
        if ($serverProcess.HasExited) {
            throw "Uvicorn process exited prematurely with exit code $($serverProcess.ExitCode)"
        }

        try {
            $healthData = Invoke-RestMethod -Uri $healthUrl -Method Get -TimeoutSec 2 -ErrorAction Stop
            if ($healthData.status -eq "UP" -and $healthData.model_loaded -eq $true) {
                $ready = $true
                break
            }
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }

    if (-not $ready) {
        throw "FastAPI service did not reach UP status within $MaxHealthWaitSeconds seconds. Last response: $($healthData | ConvertTo-Json -Compress)"
    }

    Write-Host "? FastAPI is UP and Healthy!" -ForegroundColor Green
    Write-Host "   Model Version:     $($healthData.model_version)"
    Write-Host "   Model Type:        $($healthData.model_type)"
    Write-Host "   Features Expected: $($healthData.features_expected -join ', ')"

    Write-Host "`n[3/4] Executing Java Live HTTP Integration Tests (No Mocks)..." -ForegroundColor Yellow

    $mvnCmd = "mvn.cmd"
    $mvnArgs = @(
        "-f", "$repoRoot\fraud-service\pom.xml",
        "test",
        "-Dtest=MlFraudScoringLiveIntegrationTest",
        "-Plive-http",
        "-Dml.service.url=http://127.0.0.1:$Port",
        "-Dnet.bytebuddy.experimental=true"
    )

    Write-Host "Command: $mvnCmd $($mvnArgs -join ' ')"
    & $mvnCmd @mvnArgs
    $mvnExitCode = $LASTEXITCODE

    if ($mvnExitCode -ne 0) {
        throw "Live integration test failed with exit code $mvnExitCode"
    }

    Write-Host "`n[4/4] ? Live Java-to-FastAPI HTTP Integration Tests PASSED successfully!" -ForegroundColor Green
} finally {
    Write-Host "`n[Cleanup] Terminating Uvicorn process (PID: $($serverProcess.Id))..." -ForegroundColor Cyan
    if ($serverProcess -and -not $serverProcess.HasExited) {
        Stop-Process -Id $serverProcess.Id -Force -ErrorAction SilentlyContinue
        Start-Sleep -Milliseconds 500
        Write-Host "Server process terminated cleanly." -ForegroundColor Green
    } else {
        Write-Host "Server process had already exited."
    }
}

Write-Host "`n=================================================================" -ForegroundColor Cyan
Write-Host "   LIVE INTEGRATION COMPLETE: VERIFIED TRUE TCP HTTP INTERACTION" -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan
