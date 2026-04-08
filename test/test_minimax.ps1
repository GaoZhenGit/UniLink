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
# Test helper: 流式请求测试（实时进度）
# =============================================
function Test-StreamingRequest {
    param(
        [string]$Name,
        [string]$RequestFile,
        [string]$Proxy,
        [string]$ProxyAuth,
        [int]$TimeoutSec = 300
    )

    Write-Host ""
    Write-Host "--- $Name ---" -ForegroundColor Cyan

    # 请求文件位于 $PSScriptRoot 下，路径相对于 WorkingDirectory
    $requestPath = $RequestFile  # 如 "claude_request_short.json"
    $requestFullPath = Join-Path $PSScriptRoot $RequestFile
    $requestSize = (Get-Item $requestFullPath).Length
    Write-Host "  request: $requestFullPath ($requestSize bytes)" -ForegroundColor DarkGray

    $cmd = "curl.exe -s --max-time $TimeoutSec"
    if ($Proxy)     { $cmd += " -x $Proxy" }
    if ($ProxyAuth) { $cmd += " -U $ProxyAuth" }
    $cmd += " --data-binary `"@$RequestFile`""
    $cmd += " -H `"Content-Type: application/json`""
    $cmd += " -H `"Authorization: Bearer $($env:MINIMAX_API_KEY)`""
    $cmd += " `"https://api.minimaxi.com/anthropic/v1/messages`""

    $outFile = "$env:TEMP\minimax_stream_$([guid]::NewGuid().ToString('N')).txt"
    $fullCmd = "$cmd -o `"$outFile`""
    Write-Host "CMD: $cmd" -ForegroundColor DarkGray

    # Snapshot network stats before curl starts (baseline)
    $prevNet = @{}
    Get-NetAdapterStatistics -ErrorAction SilentlyContinue | ForEach-Object {
        $prevNet[$_.Name] = @{
            SentBytes     = $_.SentBytes
            ReceivedBytes = $_.ReceivedBytes
            SentPkts      = $_.SentUnicastPackets
            RcvPkts       = $_.ReceivedUnicastPackets
        }
    }

    # Start curl process in background
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = "cmd.exe"
    $psi.Arguments = "/c $fullCmd"
    $psi.RedirectStandardOutput = $false
    $psi.RedirectStandardError = $false
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.WorkingDirectory = $PSScriptRoot
    $process = [System.Diagnostics.Process]::Start($psi)

    $sw = [Diagnostics.Stopwatch]::StartNew()
    $lastSize = 0

    # Progress loop: poll every 200ms while process is running
    while (-not $process.HasExited) {
        Start-Sleep -Milliseconds 200
        $sw.Stop()
        $elapsed = [math]::Round($sw.Elapsed.TotalSeconds, 1)
        $sw.Start()

        # --- Network packet stats: snapshot all adapters and diff against baseline ---
        $totalSentBytes = 0
        $totalRcvBytes  = 0
        $totalSentPkts  = 0
        $totalRcvPkts   = 0
        Get-NetAdapterStatistics -ErrorAction SilentlyContinue | ForEach-Object {
            if ($prevNet.ContainsKey($_.Name)) {
                $totalSentBytes += $_.SentBytes - $prevNet[$_.Name].SentBytes
                $totalRcvBytes  += $_.ReceivedBytes - $prevNet[$_.Name].ReceivedBytes
                $totalSentPkts  += $_.SentUnicastPackets - $prevNet[$_.Name].SentPkts
                $totalRcvPkts   += $_.ReceivedUnicastPackets - $prevNet[$_.Name].RcvPkts
            }
        }

        if (Test-Path $outFile) {
            $size = (Get-Item $outFile).Length
            if ($size -gt 0) {
                $delta = $size - $lastSize
                $rate  = [math]::Round($delta / 0.2)
                $lastSize = $size

                $content   = Get-Content $outFile -Raw -ErrorAction SilentlyContinue
                $dataCount = ([regex]::Matches($content, "(?m)^data:")).Count

                $barLen  = 20
                $filled  = [math]::Min([math]::Floor($size / 500), $barLen)
                $bar     = ("#" * $filled).PadRight($barLen)

                Write-Host "`r  [$bar] $size B | $dataCount chunks | ${elapsed}s | ${rate} B/s | TX:$totalSentPkts RX:$totalRcvPkts   " -NoNewline -ForegroundColor Yellow
            } else {
                Write-Host "`r  waiting... ${elapsed}s | TX:$totalSentPkts RX:$totalRcvPkts   " -NoNewline -ForegroundColor DarkGray
            }
        } else {
            Write-Host "`r  connecting... ${elapsed}s | TX:$totalSentPkts RX:$totalRcvPkts   " -NoNewline -ForegroundColor DarkGray
        }
    }

    # Final network stats
    $totalSentPkts = 0; $totalRcvPkts = 0; $totalSentBytes = 0; $totalRcvBytes = 0
    Get-NetAdapterStatistics -ErrorAction SilentlyContinue | ForEach-Object {
        if ($prevNet.ContainsKey($_.Name)) {
            $totalSentPkts  += $_.SentUnicastPackets - $prevNet[$_.Name].SentPkts
            $totalRcvPkts   += $_.ReceivedUnicastPackets - $prevNet[$_.Name].RcvPkts
            $totalSentBytes += $_.SentBytes - $prevNet[$_.Name].SentBytes
            $totalRcvBytes  += $_.ReceivedBytes - $prevNet[$_.Name].ReceivedBytes
        }
    }

    # Final status
    $sw.Stop()
    $elapsed = [math]::Round($sw.Elapsed.TotalSeconds, 2)
    $exitCode = $process.ExitCode

    Write-Host ""  # newline after progress bar

    $ok = $false
    $reason = ""

    if ($exitCode -ne 0) {
        $reason = "curl exit $exitCode"
    } elseif (-not (Test-Path $outFile)) {
        $reason = "no output file"
    } else {
        $content = Get-Content $outFile -Raw -ErrorAction SilentlyContinue
        $size = if ($content) { $content.Length } else { 0 }

        $eventCount = ([regex]::Matches($content, "(?m)^event:")).Count
        $dataCount  = ([regex]::Matches($content, "(?m)^data:")).Count
        $hasMessageStart = $content -match "message_start"
        $hasContentBlock = $content -match "content_block_start"

        Write-Host "  RESULT: size=$size B, event=$eventCount, data=$dataCount, msg_start=$hasMessageStart, content=$hasContentBlock" -ForegroundColor Yellow
        Write-Host "  NETWORK: TX=$totalSentPkts pkts ($totalSentBytes B) | RX=$totalRcvPkts pkts ($totalRcvBytes B)" -ForegroundColor Cyan

        if ($eventCount -gt 0 -and $dataCount -gt 0) {
            $ok = $true
        } elseif ($size -gt 100) {
            $ok = $true
            $reason = "(non-SSE, size=$size)"
        } else {
            $reason = "response too small (size=$size)"
        }

        Remove-Item $outFile -Force -ErrorAction SilentlyContinue
    }

    Write-Host "  EXIT: $exitCode, TIME: ${elapsed}s $reason" -ForegroundColor $(if ($ok) { "Green" } else { "Red" })
    return $ok
}

# =============================================
# Test cases
# =============================================
# Test 1: Direct API (short data)
# Test 2: Via SOCKS5 proxy (short data)
# Test 3: Via HTTP proxy (short data)
# Test 4: Via SOCKS5 proxy (long data)
# Test 5: Via HTTP proxy (long data)

$passed = 0
$total  = 3

# 1. Direct API - short
if (Test-StreamingRequest -Name "Direct API (short)" -RequestFile "claude_request_short.json" -TimeoutSec 120) { $passed++ }

# 2. SOCKS5 proxy - short
if (Test-StreamingRequest -Name "SOCKS5 proxy (short)" -RequestFile "claude_request_short.json" -Proxy "socks5h://127.0.0.1:1080" -ProxyAuth "socks5:password" -TimeoutSec 120) { $passed++ }

# 3. HTTP proxy - short
if (Test-StreamingRequest -Name "HTTP proxy (short)" -RequestFile "claude_request_short.json" -Proxy "http://localhost:8888" -ProxyAuth "admin:password123" -TimeoutSec 120) { $passed++ }

# 4. SOCKS5 proxy - long
if (Test-StreamingRequest -Name "SOCKS5 proxy (long)" -RequestFile "claude_request_long.json" -Proxy "socks5h://127.0.0.1:1080" -ProxyAuth "socks5:password" -TimeoutSec 300) { $passed++ }

# 5. HTTP proxy - long
if (Test-StreamingRequest -Name "HTTP proxy (long)" -RequestFile "claude_request_long.json" -Proxy "http://localhost:8888" -ProxyAuth "admin:password123" -TimeoutSec 300) { $passed++ }

Write-Host ""
Write-Host "========================================" -ForegroundColor Magenta
Write-Host "           Result: $passed / $total" -ForegroundColor $(if ($passed -eq $total) { "Green" } else { "Red" })
Write-Host "========================================" -ForegroundColor Magenta
