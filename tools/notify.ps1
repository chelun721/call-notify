<#
    在 Windows 上下发一条来电通知。

    用法：
        .\notify.ps1 -Key cn_xxx -Text "构建失败，请看日志"
        .\notify.ps1 -Key cn_xxx -Name 张经理 -Number 13800138000 -Text "服务器 CPU 超过 90%" -Times 2
        .\notify.ps1 -Key cn_xxx -Text "训练跑完了" -Server http://192.168.1.10:8787 -Wait

    为什么不用 curl：
      Windows PowerShell 里 curl 是 Invoke-WebRequest 的别名，`curl -X POST` 直接报错；
      而且中文 body 必须显式转成 UTF-8 字节，否则会乱码。这个脚本两件事都处理了。
#>

param(
    [Parameter(Mandatory = $true)][string]$Key,
    [string]$Text = '',
    [string]$Name = '系统通知',
    [string]$Number = '10086',
    [int]$Delay = 0,
    [int]$Ring = 60,
    [int]$Times = 1,
    [string]$Server = 'http://127.0.0.1:8787',
    [switch]$Wait
)

$ErrorActionPreference = 'Stop'
$Server = $Server.TrimEnd('/')

$body = @{
    callerName   = $Name
    callerNumber = $Number
    text         = $Text
    delaySeconds = $Delay
    ringSeconds  = $Ring
    speakTimes   = $Times
} | ConvertTo-Json -Compress

$bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
$headers = @{ 'X-API-Key' = $Key }

try {
    $resp = Invoke-WebRequest -Uri "$Server/api/v1/notify" -Method Post `
        -Headers $headers -ContentType 'application/json; charset=utf-8' `
        -Body $bytes -UseBasicParsing -TimeoutSec 15
} catch {
    $code = $null
    if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
    switch ($code) {
        401 { throw 'HTTP 401：密钥不正确（App 里重新注册一次，或确认这串 key 属于当前服务器）' }
        429 { throw 'HTTP 429：下发太频繁（默认 30 次/分钟），稍后再试' }
        400 { throw 'HTTP 400：参数有问题（callerName 和 text 至少要有一个）' }
        default { throw "请求失败：$($_.Exception.Message)" }
    }
}

$data = $resp.Content | ConvertFrom-Json
Write-Host ("已下发  id={0}  status={1}  设备{2}" -f $data.id, $data.status, $(if ($data.deviceOnline) { '在线' } else { '离线（已排队，重连后补发）' }))

if (-not $Wait) { return }

# 轮询状态直到终态，能直接看出卡在哪一步
$final = @('completed', 'rejected', 'missed', 'failed', 'expired')
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Milliseconds 1000
    try {
        $s = Invoke-RestMethod -Uri "$Server/api/v1/notify/$($data.id)" -Headers $headers -TimeoutSec 10
    } catch { continue }
    $line = "  [{0,2}s] {1}" -f ($i + 1), $s.status
    if ($s.note) { $line += "  ($($s.note))" }
    Write-Host $line
    if ($final -contains $s.status) { break }
}
