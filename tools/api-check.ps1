<#
    在线 API 自检：一次性验证「服务端可达 → 密钥有效 → 设备在线 → 通知能下发」

    用法（PowerShell，任意目录）：
        .\api-check.ps1 -Server http://192.168.1.10:8787 -Key cn_你的密钥
        .\api-check.ps1 -Server http://192.168.1.10:8787 -Key cn_xxx -SkipNotify
#>

param(
    [Parameter(Mandatory = $true)][string]$Key,
    [string]$Server = 'http://127.0.0.1:8787',
    [switch]$SkipNotify
)

$ErrorActionPreference = 'Stop'
$Server = $Server.TrimEnd('/')
$results = @()

function Step($name, [scriptblock]$action) {
    try {
        $value = & $action
        $script:results += [pscustomobject]@{ 检查项 = $name; 结果 = '✓'; 详情 = $value }
        return $value
    } catch {
        $script:results += [pscustomobject]@{ 检查项 = $name; 结果 = '✗'; 详情 = $_.Exception.Message }
        return $null
    }
}

Write-Host "目标服务端：$Server`n"

# ---- 1. 服务端可达
$health = Step '服务端 /health' {
    (Invoke-RestMethod -Uri "$Server/health" -TimeoutSec 8) | ConvertTo-Json -Compress
}
if (-not $health) {
    $results | Format-Table -AutoSize | Out-String | Write-Host
    Write-Host '服务端不可达：检查地址、端口、防火墙/安全组，以及服务端是否在跑。'
    exit 1
}

# ---- 2. 密钥有效
$me = Step '密钥 /api/v1/me' {
    (Invoke-RestMethod -Uri "$Server/api/v1/me" -Headers @{ 'X-API-Key' = $Key } -TimeoutSec 8) |
        ConvertTo-Json -Compress
}
if (-not $me) {
    $results | Format-Table -AutoSize | Out-String | Write-Host
    Write-Host '密钥被拒：换一台服务器后必须重新注册，到 App 里点「注册设备」拿新 key。'
    exit 1
}
$meObj = $me | ConvertFrom-Json
Write-Host ("设备：{0}（{1}）在线={2}" -f $meObj.deviceId, $meObj.name, $meObj.online)

# ---- 3. 自检面板（给排障用，不属于正式 API）
Step '诊断 /api/v1/diag' {
    $d = Invoke-RestMethod -Uri "$Server/api/v1/diag" -TimeoutSec 8
    $hint = if ($d.hints) { $d.hints -join '；' } else { '无告警' }
    "在线 $($d.online) 台；$hint"
} | Out-Null

if ($meObj.online -ne $true) {
    Write-Host "`n注意：设备当前显示离线。通知仍会入队（最多 20 条、10 分钟内有效），" -ForegroundColor Yellow
    Write-Host "      手机重连后会补发；但要立刻响铃，请先让 App 处于运行状态。" -ForegroundColor Yellow
}

# ---- 4. 真实下发 + 状态跟踪
if (-not $SkipNotify) {
    $sent = Step '下发 /api/v1/notify' {
        $body = @{ callerName = 'API 自检'; callerNumber = '10086'; text = '这是一条 API 自检通知，收到即可关闭'; speakTimes = 1 } |
            ConvertTo-Json -Compress
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
        $resp = Invoke-WebRequest -Uri "$Server/api/v1/notify" -Method Post `
            -Headers @{ 'X-API-Key' = $Key } -ContentType 'application/json; charset=utf-8' `
            -Body $bytes -UseBasicParsing -TimeoutSec 15
        $resp.Content
    }

    if ($sent) {
        $id = ($sent | ConvertFrom-Json).id
        $final = @('completed', 'rejected', 'missed', 'failed', 'expired')
        $status = ''
        for ($i = 0; $i -lt 25; $i++) {
            Start-Sleep -Milliseconds 1000
            try {
                $s = Invoke-RestMethod -Uri "$Server/api/v1/notify/$id" -Headers @{ 'X-API-Key' = $Key } -TimeoutSec 8
                $status = $s.status
                if ($final -contains $status) {
                    $note = if ($s.note) { "（$($s.note)）" } else { '' }
                    $script:results += [pscustomobject]@{ 检查项 = '状态流转'; 结果 = $(if ($status -in 'completed','answered') { '✓' } else { '✗' }); 详情 = "$status$note" }
                    break
                }
            } catch { }
        }
        if (-not $status) {
            $script:results += [pscustomobject]@{ 检查项 = '状态流转'; 结果 = '?'; 详情 = '25 秒内没到终态' }
        }
    }
}

Write-Host ''
$results | Format-Table -AutoSize -Wrap | Out-String -Width 160 | Write-Host

Write-Host @'
判定：
  ✓ 全部通过            → API 可用，可以接业务系统
  ✗ 设备离线 / queued   → 手机端 App 没在跑，或后台被杀；打开 App 再发
  ✗ failed（not enabled）→ 手机端通话账户未启用，去系统设置里允许「来电通知」
  ✗ failed（语音引擎）  → 手机端 TTS 不可用，装一个中文语音引擎
'@
