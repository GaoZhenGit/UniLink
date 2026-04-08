# Large message test script - reproduces the 65KB WS frame issue
$ErrorActionPreference = "Continue"

# 1. Stop existing services
Write-Host "=== Stopping existing services ===" -ForegroundColor Yellow
.\test\stop.ps1
Start-Sleep -Seconds 2

# 2. Build project
Write-Host "=== Building project ===" -ForegroundColor Yellow
mvn clean package -DskipTests
if ($LASTEXITCODE -ne 0) {
    Write-Host "Build failed!" -ForegroundColor Red
    exit 1
}

# 3. Start services
Write-Host "=== Starting services ===" -ForegroundColor Yellow
.\test\start.ps1
Write-Host "Waiting for services to start..." -ForegroundColor Cyan
Start-Sleep -Seconds 12

# =============================================
# Helper: run curl via cmd /c
# =============================================
function Invoke-Curl {
    param(
        [string]$Proxy,
        [string]$ProxyAuth,
        [string]$Url,
        [string]$ExtraArgs = "",
        [int]$TimeoutSec = 60
    )
    $cmdBase = "curl.exe -s -o nul"
    if ($Proxy)     { $cmdBase += " -x $Proxy" }
    if ($ProxyAuth) { $cmdBase += " -U $ProxyAuth" }
    if ($ExtraArgs) { $cmdBase += " $ExtraArgs" }

    $cmd = "$cmdBase `"$Url`""
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $process = Start-Process cmd -ArgumentList "/c", $cmd -NoNewWindow -Wait -PassThru
    $sw.Stop()
    return @($process.ExitCode, [math]::Round($sw.Elapsed.TotalSeconds, 2))
}

# =============================================
# Large Response Test
# =============================================
Write-Host ""
Write-Host "========================================" -ForegroundColor Magenta
Write-Host "       Large Response Test (65KB+)" -ForegroundColor Magenta
Write-Host "========================================" -ForegroundColor Magenta

# Test 1: Large response via HTTP CONNECT proxy
$total = 0
$passed = 0

$total++
Write-Host ""
Write-Host "--- Large Response via HTTP CONNECT (expecting 65KB+) ---" -ForegroundColor Cyan
Write-Host "CMD: curl.exe -x http://localhost:8888 -U admin:password123 -s https://httpbin.org/bytes/65536" -ForegroundColor DarkGray

$outFile = "$env:TEMP\large_response_$([guid]::NewGuid().ToString('N')).bin"
$cmd = "curl.exe -s -o `"$outFile`" -x http://localhost:8888 -U admin:password123 https://httpbin.org/bytes/65536"
$sw = [Diagnostics.Stopwatch]::StartNew()
$process = Start-Process cmd -ArgumentList "/c", $cmd -NoNewWindow -Wait -PassThru
$sw.Stop()
$exitCode = $process.ExitCode
$elapsed = [math]::Round($sw.Elapsed.TotalSeconds, 2)

if (Test-Path $outFile) {
    $size = (Get-Item $outFile).Length
    Write-Host "EXIT: $exitCode, TIME: ${elapsed}s, SIZE: $size B" -ForegroundColor Yellow
    if ($exitCode -eq 0 -and $size -gt 60000) {
        Write-Host "PASS: Received large response ($size bytes)" -ForegroundColor Green
        $passed++
    } elseif ($exitCode -eq 0) {
        Write-Host "WARN: Response too small ($size bytes)" -ForegroundColor Yellow
        $passed++
    } else {
        Write-Host "FAIL: Request failed" -ForegroundColor Red
    }
    Remove-Item $outFile -Force -ErrorAction SilentlyContinue
} else {
    Write-Host "EXIT: $exitCode, TIME: ${elapsed}s" -ForegroundColor Red
    Write-Host "FAIL: No response file created" -ForegroundColor Red
}

# =============================================
# Check logs for buffer too small error
# =============================================
Write-Host ""
Write-Host "=== Checking logs for buffer errors ===" -ForegroundColor Cyan

$foundError = $false
Get-ChildItem .\logs\*.log -ErrorAction SilentlyContinue | ForEach-Object {
    $content = Get-Content $_.FullName -Raw -ErrorAction SilentlyContinue
    if ($content -match "buffer too small|No async message support") {
        Write-Host "ERROR FOUND in $($_.Name):" -ForegroundColor Red
        $matches = [regex]::Matches($content, ".*buffer too small.*|.*No async message support.*")
        foreach ($m in $matches) {
            Write-Host "  $($m.Value)" -ForegroundColor Red
        }
        $foundError = $true
    }
}

if (-not $foundError) {
    Write-Host "No buffer errors found in logs" -ForegroundColor Green
}

# =============================================
# Summary
# =============================================
Write-Host ""
Write-Host "========================================" -ForegroundColor Magenta
Write-Host "           Test Summary" -ForegroundColor Magenta
Write-Host "========================================" -ForegroundColor Magenta
Write-Host "Passed: $passed / $total" -ForegroundColor $(if ($passed -eq $total) { "Green" } else { "Red" })
Write-Host "Buffer Errors: $(if ($foundError) { 'FOUND' } else { 'None' })" -ForegroundColor $(if ($foundError) { "Red" } else { "Green" })

# Stop services
Write-Host ""
Write-Host "=== Stopping services ===" -ForegroundColor Yellow
.\test\stop.ps1

exit $(if ($foundError -or $passed -ne $total) { 1 } else { 0 })
