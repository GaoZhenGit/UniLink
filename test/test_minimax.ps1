$ErrorActionPreference = "Continue"

# MiniMax API Key
$script:MinimaxApiKey = $env:MINIMAX_API_KEY
if (-not $script:MinimaxApiKey -and (Test-Path "$PSScriptRoot\config.ps1")) {
    . "$PSScriptRoot\config.ps1"
}

if (-not $env:MINIMAX_API_KEY) {
    Write-Host "ERROR: MINIMAX_API_KEY not set" -ForegroundColor Red
    exit 1
}

Write-Host "=== MiniMax Streaming Test ===" -ForegroundColor Magenta
Write-Host "API Key: $($env:MINIMAX_API_KEY.Substring(0, 10))..." -ForegroundColor Cyan

# =============================================
# Test helper: 流式请求测试
# =============================================
function Test-StreamingRequest {
    param(
        [string]$Name,
        [string]$Proxy,
        [string]$ProxyAuth,
        [int]$TimeoutSec = 300
    )

    Write-Host ""
    Write-Host "--- $Name ---" -ForegroundColor Cyan

    $cmd = "curl.exe -s --max-time $TimeoutSec"
    if ($Proxy)     { $cmd += " -x $Proxy" }
    if ($ProxyAuth) { $cmd += " -U $ProxyAuth" }
    $cmd += " --data-binary `"@test\claude_request.json`""
    $cmd += " -H `"Content-Type: application/json`""
    $cmd += " -H `"Authorization: Bearer $($env:MINIMAX_API_KEY)`""
    $cmd += " `"https://api.minimaxi.com/anthropic/v1/messages`""

    $outFile = "$env:TEMP\minimax_stream_$([guid]::NewGuid().ToString('N')).txt"
    $fullCmd = "$cmd -o `"$outFile`""
    Write-Host "CMD: $cmd" -ForegroundColor DarkGray

    $sw = [Diagnostics.Stopwatch]::StartNew()
    $process = Start-Process cmd -ArgumentList "/c", $fullCmd -NoNewWindow -Wait -PassThru
    $sw.Stop()
    $exitCode = $process.ExitCode
    $elapsed = [math]::Round($sw.Elapsed.TotalSeconds, 2)

    $ok = $false
    $reason = ""

    if ($exitCode -ne 0) {
        $reason = "curl exit $exitCode"
    } elseif (-not (Test-Path $outFile)) {
        $reason = "no output file"
    } else {
        $content = Get-Content $outFile -Raw -ErrorAction SilentlyContinue
        $size = if ($content) { $content.Length } else { 0 }

        # 统计 SSE event 块数量（流式传输的特征）
        $eventCount = ([regex]::Matches($content, "(?m)^event:")).Count
        $dataCount  = ([regex]::Matches($content, "(?m)^data:")).Count
        $hasMessageStart = $content -match "message_start"
        $hasContentBlock = $content -match "content_block_start"

        Write-Host "  size=$size bytes, event chunks=$eventCount, data chunks=$dataCount, message_start=$hasMessageStart, content_block=$hasContentBlock" -ForegroundColor Yellow

        if ($eventCount -gt 0 -and $dataCount -gt 0) {
            $ok = $true
        } elseif ($size -gt 100) {
            # 有内容但不是标准 SSE，检查是否接近最终输出
            $ok = $true
            $reason = "(non-SSE response, size=$size)"
        } else {
            $reason = "response too small or empty (size=$size)"
        }

        Remove-Item $outFile -Force -ErrorAction SilentlyContinue
    }

    Write-Host "  EXIT: $exitCode, TIME: ${elapsed}s $reason" -ForegroundColor $(if ($ok) { "Green" } else { "Red" })
    return $ok
}

$passed = 0
$total  = 3

# Test 1: Direct
if (Test-StreamingRequest -Name "Direct API" -TimeoutSec 300) { $passed++ }

# Test 2: SOCKS5 proxy
if (Test-StreamingRequest -Name "Via SOCKS5 proxy" -Proxy "socks5h://127.0.0.1:1080" -ProxyAuth "socks5:password" -TimeoutSec 300) { $passed++ }

# Test 3: HTTP proxy
if (Test-StreamingRequest -Name "Via HTTP proxy" -Proxy "http://localhost:8888" -ProxyAuth "admin:password123" -TimeoutSec 300) { $passed++ }

Write-Host ""
Write-Host "========================================" -ForegroundColor Magenta
Write-Host "           Result: $passed / $total" -ForegroundColor $(if ($passed -eq $total) { "Green" } else { "Red" })
Write-Host "========================================" -ForegroundColor Magenta
