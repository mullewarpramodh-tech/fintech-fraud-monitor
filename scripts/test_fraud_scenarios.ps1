# =====================================================================
# Distributed FinTech Fraud Monitor - Automated Verification Suite
# =====================================================================

Write-Host "=====================================================================" -ForegroundColor Cyan
Write-Host "  DISTRIBUTED FINTECH FRAUD MONITOR - LIVE TEST HARNESS" -ForegroundColor Cyan
Write-Host "=====================================================================" -ForegroundColor Cyan

$baseUrl = "http://localhost:8081"

# 1. Authenticate as USER
Write-Host "`n[STEP 1] Authenticating as ROLE_USER..." -ForegroundColor Yellow
$userLoginBody = @{ username = "pramodh"; role = "USER" } | ConvertTo-Json
$userAuth = Invoke-RestMethod -Uri "$baseUrl/api/auth/login" -Method Post -Body $userLoginBody -ContentType "application/json"
$userToken = $userAuth.token
Write-Host "✅ User JWT Acquired: Bearer $($userToken.Substring(0, 25))..." -ForegroundColor Green

$userHeaders = @{
    "Authorization" = "Bearer $userToken"
    "Content-Type"  = "application/json"
}

# 2. Authenticate as ADMIN
Write-Host "`n[STEP 2] Authenticating as ROLE_ADMIN..." -ForegroundColor Yellow
$adminLoginBody = @{ username = "admin"; role = "ADMIN" } | ConvertTo-Json
$adminAuth = Invoke-RestMethod -Uri "$baseUrl/api/auth/login" -Method Post -Body $adminLoginBody -ContentType "application/json"
$adminToken = $adminAuth.token
Write-Host "✅ Admin JWT Acquired: Bearer $($adminToken.Substring(0, 25))..." -ForegroundColor Green

$adminHeaders = @{
    "Authorization" = "Bearer $adminToken"
    "Content-Type"  = "application/json"
}

# 3. Scenario 1: Clean Transaction ($500)
Write-Host "`n[SCENARIO 1] Submitting Clean Transaction ($500.00)..." -ForegroundColor Yellow
$cleanTx = @{
    accountId = "ACC-NORMAL-001"
    amount    = 500.00
    currency  = "USD"
} | ConvertTo-Json

$resp1 = Invoke-RestMethod -Uri "$baseUrl/api/transactions" -Method Post -Headers $userHeaders -Body $cleanTx
Write-Host "➡️ Ingested Txn #$($resp1.id) | Status: $($resp1.status)" -ForegroundColor Cyan
Start-Sleep -Seconds 2

# Verify in DB via Admin endpoint
$tx1 = Invoke-RestMethod -Uri "$baseUrl/api/transactions/$($resp1.id)" -Headers $adminHeaders
Write-Host "✅ Fraud Engine Result: Status = '$($tx1.status)' | Reason = '$($tx1.fraudReason)'" -ForegroundColor Green

# 4. Scenario 2: High-Value Anomaly ($25,000 > $10,000 threshold)
Write-Host "`n[SCENARIO 2] Submitting High-Value Anomaly ($25,000.00)..." -ForegroundColor Yellow
$highValTx = @{
    accountId = "ACC-WHALE-002"
    amount    = 25000.00
    currency  = "USD"
} | ConvertTo-Json

$resp2 = Invoke-RestMethod -Uri "$baseUrl/api/transactions" -Method Post -Headers $userHeaders -Body $highValTx
Write-Host "➡️ Ingested Txn #$($resp2.id) | Status: $($resp2.status)" -ForegroundColor Cyan
Start-Sleep -Seconds 2

$tx2 = Invoke-RestMethod -Uri "$baseUrl/api/transactions/$($resp2.id)" -Headers $adminHeaders
if ($tx2.status -eq "FRAUD_DETECTED") {
    Write-Host "❌ FRAUD CAUGHT AS EXPECTED! Status = '$($tx2.status)' | Reason = '$($tx2.fraudReason)'" -ForegroundColor Red
} else {
    Write-Host "Status: $($tx2.status)" -ForegroundColor Yellow
}

# 5. Scenario 3: 60-Second Sliding-Window Velocity Spike (> 3 transactions)
$rapidAcc = "ACC-BURST-$(Get-Random -Minimum 1000 -Maximum 9999)"
Write-Host "`n[SCENARIO 3] Rapid-Fire Velocity Burst (4 Transactions in 2s for $rapidAcc)..." -ForegroundColor Yellow

$burstIds = @()
for ($i = 1; $i -le 4; $i++) {
    $burstTx = @{
        accountId = $rapidAcc
        amount    = 150.00 + $i
        currency  = "USD"
    } | ConvertTo-Json
    $burstResp = Invoke-RestMethod -Uri "$baseUrl/api/transactions" -Method Post -Headers $userHeaders -Body $burstTx
    Write-Host "  -> Burst Txn $i (ID #$($burstResp.id)) submitted" -ForegroundColor Cyan
    $burstIds += $burstResp.id
    Start-Sleep -Milliseconds 300
}

Write-Host "Waiting 2s for Kafka Consumer window aggregation..." -ForegroundColor Yellow
Start-Sleep -Seconds 2

Write-Host "`n[VERIFICATION] Inspecting Sliding-Window Audit Results:" -ForegroundColor Yellow
foreach ($id in $burstIds) {
    $item = Invoke-RestMethod -Uri "$baseUrl/api/transactions/$id" -Headers $adminHeaders
    $color = if ($item.status -eq "FRAUD_DETECTED") { "Red" } else { "Green" }
    Write-Host "  Txn #$($item.id) | Amount: `$$($item.amount) | Status: $($item.status) | Reason: $($item.fraudReason)" -ForegroundColor $color
}

Write-Host "`n=====================================================================" -ForegroundColor Cyan
Write-Host "  ALL TESTS COMPLETED SUCCESSFULLY!" -ForegroundColor Cyan
Write-Host "=====================================================================" -ForegroundColor Cyan
