$ErrorActionPreference = "Continue"

# MiniMax API Key (set from config file if not in environment)
$script:MinimaxApiKey = $env:MINIMAX_API_KEY
if (-not $script:MinimaxApiKey -and (Test-Path "$PSScriptRoot\config.ps1")) {
    . "$PSScriptRoot\config.ps1"
}

# 1. Stop existing services
Write-Host "=== Stopping existing services ===" -ForegroundColor Yellow
.\test\stop.ps1
Start-Sleep -Seconds 2

# 2. Clean logs and old jars
Write-Host "=== Cleaning logs and old jars ===" -ForegroundColor Yellow
Remove-Item -Path .\logs\* -Force -ErrorAction SilentlyContinue
Remove-Item 'unilink-proxy/target/unilink-proxy-1.1.0.jar', 'unilink-worker/target/unilink-worker-1.1.0.jar', 'unilink-access/target/unilink-access-1.1.0.jar' -Force -ErrorAction SilentlyContinue

# 3. Build project
Write-Host "=== Building project ===" -ForegroundColor Yellow
mvn clean package -DskipTests
if ($LASTEXITCODE -ne 0) {
    Write-Host "Build failed!" -ForegroundColor Red
    exit 1
}

# 4. Start services
Write-Host "=== Starting services ===" -ForegroundColor Yellow
.\test\start.ps1
Write-Host "Waiting for services to start..." -ForegroundColor Cyan
Start-Sleep -Seconds 12

# =============================================
# Helper: run curl via cmd /c to get clean exit code
# =============================================
function Invoke-Curl {
    param(
        [string]$Proxy,
        [string]$ProxyAuth,
        [string]$Url,
        [string]$ExtraArgs = "",
        [int]$TimeoutSec = 30,
        [int]$Retry = 1
    )
    $cmdBase = "curl.exe -s -o nul"
    if ($Proxy)     { $cmdBase += " -x $Proxy" }
    if ($ProxyAuth) { $cmdBase += " -U $ProxyAuth" }
    if ($ExtraArgs) { $cmdBase += " $ExtraArgs" }

    $attempts = $Retry + 1
    for ($i = 0; $i -lt $attempts; $i++) {
        $cmd = "$cmdBase `"$Url`""
        $sw = [Diagnostics.Stopwatch]::StartNew()
        $process = Start-Process cmd -ArgumentList "/c", $cmd -NoNewWindow -Wait -PassThru
        $sw.Stop()
        $exitCode = $process.ExitCode
        if ($exitCode -eq 0 -or $i -ge $Retry) {
            return @($exitCode, [math]::Round($sw.Elapsed.TotalSeconds, 2))
        }
        Start-Sleep -Milliseconds 500
    }
    return @(1, 999)
}

# =============================================
# Helper: streaming request test (for SSE responses)
# =============================================
function Test-StreamingRequest {
    param(
        [string]$Name,
        [string]$Proxy,
        [string]$ProxyAuth,
        [int]$TimeoutSec = 300
    )

    Write-Host ""
    Write-Host "--- $Name (streaming) ---" -ForegroundColor Cyan

    $cmd = "curl.exe -s --max-time $TimeoutSec"
    if ($Proxy)     { $cmd += " -x $Proxy" }
    if ($ProxyAuth) { $cmd += " -U $ProxyAuth" }
    $cmd += " --data-binary `"@test\claude_request_short.json`""
    $cmd += " -H `"Content-Type: application/json`""
    $cmd += " -H `"Authorization: Bearer $($env:MINIMAX_API_KEY)`""
    $cmd += " `"https://api.minimaxi.com/anthropic/v1/messages`""

    $outFile = "$env:TEMP\minimax_stream_$([guid]::NewGuid().ToString('N')).txt"
    Write-Host "CMD: $cmd" -ForegroundColor DarkGray

    $sw = [Diagnostics.Stopwatch]::StartNew()
    $process = Start-Process cmd -ArgumentList "/c", "$cmd -o `"$outFile`"" -NoNewWindow -Wait -PassThru
    $sw.Stop()
    $exitCode = $process.ExitCode
    $elapsed = [math]::Round($sw.Elapsed.TotalSeconds, 2)

    $ok = $false
    if ($exitCode -eq 0 -and (Test-Path $outFile)) {
        $content = Get-Content $outFile -Raw -ErrorAction SilentlyContinue
        $eventCount = ([regex]::Matches($content, "(?m)^event:")).Count
        $dataCount  = ([regex]::Matches($content, "(?m)^data:")).Count
        $hasStreaming = $eventCount -gt 0 -and $dataCount -gt 0
        Write-Host "  size=$($content.Length) bytes, event=$eventCount, data=$dataCount, time=${elapsed}s" -ForegroundColor Yellow
        $ok = $hasStreaming
        Remove-Item $outFile -Force -ErrorAction SilentlyContinue
    } else {
        Write-Host "  EXIT: $exitCode, TIME: ${elapsed}s" -ForegroundColor Red
    }

    Write-Host "  RESULT: $(if ($ok) { 'PASS' } else { 'FAIL' })" -ForegroundColor $(if ($ok) { 'Green' } else { 'Red' })
    return $ok
}

# =============================================
# Test cases
# =============================================
Write-Host ""
Write-Host "========================================" -ForegroundColor Magenta
Write-Host "           Running Test Cases" -ForegroundColor Magenta
Write-Host "========================================" -ForegroundColor Magenta

$total = 0
$passed = 0

# --- HTTP CONNECT normal request ---
$total++
Write-Host ""
Write-Host "--- HTTP CONNECT normal request ---" -ForegroundColor Cyan
Write-Host "CMD: curl.exe -x http://localhost:8888 -U admin:password123 -s -o nul https://httpbin.org/get" -ForegroundColor DarkGray
$r = Invoke-Curl -Proxy "http://localhost:8888" -ProxyAuth "admin:password123" -Url "https://httpbin.org/get" -Retry 1
$ok = ($r[0] -eq 0)
Write-Host "EXIT: $($r[0]) (expected: 0), TIME: $($r[1])s" -ForegroundColor $(if ($ok) { "Green" } else { "Red" })
if ($ok) { $passed++ }

# --- SOCKS5 normal request ---
$total++
Write-Host ""
Write-Host "--- SOCKS5 normal request (remote DNS) ---" -ForegroundColor Cyan
Write-Host "CMD: curl.exe -x socks5h://127.0.0.1:1080 -U socks5:password -s -o nul https://www.baidu.com" -ForegroundColor DarkGray
$r = Invoke-Curl -Proxy "socks5h://127.0.0.1:1080" -ProxyAuth "socks5:password" -Url "https://www.baidu.com"
$ok = ($r[0] -eq 0)
Write-Host "EXIT: $($r[0]) (expected: 0), TIME: $($r[1])s" -ForegroundColor $(if ($ok) { "Green" } else { "Red" })
if ($ok) { $passed++ }

# --- SOCKS5 no auth (should be rejected) ---
$total++
Write-Host ""
Write-Host "--- SOCKS5 no auth (should reject) ---" -ForegroundColor Cyan
Write-Host "CMD: curl.exe -x socks5://127.0.0.1:1080 -s -o nul https://www.baidu.com" -ForegroundColor DarkGray
$r = Invoke-Curl -Proxy "socks5://127.0.0.1:1080" -Url "https://www.baidu.com"
$ok = ($r[0] -ne 0)
Write-Host "EXIT: $($r[0]) (expected: non-0), TIME: $($r[1])s" -ForegroundColor $(if ($ok) { "Green" } else { "Red" })
if ($ok) { $passed++ }

# --- SOCKS5 wrong password (should be rejected) ---
$total++
Write-Host ""
Write-Host "--- SOCKS5 wrong password (should reject) ---" -ForegroundColor Cyan
Write-Host "CMD: curl.exe -x socks5h://127.0.0.1:1080 -U wrong:pass -s -o nul https://www.baidu.com" -ForegroundColor DarkGray
$r = Invoke-Curl -Proxy "socks5h://127.0.0.1:1080" -ProxyAuth "wrong:pass" -Url "https://www.baidu.com"
$ok = ($r[0] -ne 0)
Write-Host "EXIT: $($r[0]) (expected: non-0), TIME: $($r[1])s" -ForegroundColor $(if ($ok) { "Green" } else { "Red" })
if ($ok) { $passed++ }

# --- SOCKS5 DNS failure (should fail fast) ---
$total++
Write-Host ""
Write-Host "--- SOCKS5 DNS failure (should fail fast) ---" -ForegroundColor Cyan
Write-Host "CMD: curl.exe -x socks5h://127.0.0.1:1080 -U socks5:password -s -o nul https://baidu.co" -ForegroundColor DarkGray
$r = Invoke-Curl -Proxy "socks5h://127.0.0.1:1080" -ProxyAuth "socks5:password" -Url "https://baidu.co"
$ok = ($r[1] -lt 5)
Write-Host "EXIT: $($r[0]), TIME: $($r[1])s (expected: < 5s)" -ForegroundColor $(if ($ok) { "Green" } else { "Red" })
if ($ok) { $passed++ }

# --- HTTP CONNECT DNS failure (should fail fast) ---
$total++
Write-Host ""
Write-Host "--- HTTP CONNECT DNS failure (should fail fast) ---" -ForegroundColor Cyan
Write-Host "CMD: curl.exe -x http://localhost:8888 -U admin:password123 -s -o nul https://thisdoesnotexist12345.com" -ForegroundColor DarkGray
$r = Invoke-Curl -Proxy "http://localhost:8888" -ProxyAuth "admin:password123" -Url "https://thisdoesnotexist12345.com"
$ok = ($r[1] -lt 5)
Write-Host "EXIT: $($r[0]), TIME: $($r[1])s (expected: < 5s)" -ForegroundColor $(if ($ok) { "Green" } else { "Red" })
if ($ok) { $passed++ }

# --- MiniMax API streaming tests ---
if (-not $env:MINIMAX_API_KEY) {
    Write-Host ""
    Write-Host "--- MiniMax streaming (SOCKS5 + HTTP) ---" -ForegroundColor Cyan
    Write-Host "SKIP: MINIMAX_API_KEY not set" -ForegroundColor DarkGray
} else {
    # SOCKS5 proxy
    $total++
    if (Test-StreamingRequest -Name "MiniMax via SOCKS5" -Proxy "socks5h://127.0.0.1:1080" -ProxyAuth "socks5:password") { $passed++ }

    # HTTP proxy
    $total++
    if (Test-StreamingRequest -Name "MiniMax via HTTP proxy" -Proxy "http://localhost:8888" -ProxyAuth "admin:password123") { $passed++ }
}

# --- Access history query ---
Write-Host ""
Write-Host "--- Access history query ---" -ForegroundColor Cyan
$total++
$historyIds = curl.exe -s http://localhost:8082/unilink/access/with-history
Write-Host "Access IDs: $historyIds" -ForegroundColor Yellow
if ($historyIds -match '"ac"') {
    Write-Host "Query: curl.exe http://localhost:8082/unilink/access/ac/history?limit=10" -ForegroundColor DarkGray
    $historyJson = curl.exe -s "http://localhost:8082/unilink/access/ac/history?limit=10"
    Write-Host $historyJson -ForegroundColor Yellow
    if ($historyJson -match '"protocol"' -and $historyJson -match '"url"' -and $historyJson -match '"success"') {
        Write-Host "PASS: history contains protocol/url/success fields" -ForegroundColor Green
        $passed++
    } else {
        Write-Host "FAIL: history missing expected fields" -ForegroundColor Red
    }
} else {
    Write-Host "FAIL: no access history found" -ForegroundColor Red
}

# =============================================
# Summary
# =============================================
Write-Host ""
Write-Host "========================================" -ForegroundColor Magenta
Write-Host "           Test Summary" -ForegroundColor Magenta
Write-Host "========================================" -ForegroundColor Magenta
Write-Host "Passed: $passed / $total" -ForegroundColor $(if ($passed -eq $total) { "Green" } else { "Red" })

# Stop services
Write-Host ""
Write-Host "=== Stopping services ===" -ForegroundColor Yellow
.\test\stop.ps1
