<#
    把服务端注册成 Windows 计划任务：开机自启 + 崩溃自动重启 + 后台常驻。

    用法（**用管理员身份**打开 PowerShell）：

        powershell -ExecutionPolicy Bypass -File .\install-service.ps1
        powershell -ExecutionPolicy Bypass -File .\install-service.ps1 -Port 9000
        powershell -ExecutionPolicy Bypass -File .\install-service.ps1 -Uninstall

    两个已知坑（脚本里已经处理）：
      1. Go 编出来的普通 exe 不实现 ServiceMain，sc create 会报 1053 —— 所以用计划任务。
      2. 计划任务以 SYSTEM 身份运行，**看不到映射盘 / 网络盘 / subst 盘**
         （会报 LastTaskResult=2147942402 / 0x80070002 文件找不到）。
         所以 exe 不在本地盘时，脚本会自动复制到 C:\callnotify\ 再注册。
#>

param(
    [string]$ExePath = (Join-Path $PSScriptRoot 'dist\callnotify-server-windows-amd64.exe'),
    [int]$Port = 8787,
    [string]$DataFile = '',
    [string]$TaskName = 'CallNotifyServer',
    [string]$LocalDir = 'C:\callnotify',
    [switch]$NoCopy,
    [switch]$SkipFirewall,
    [switch]$Uninstall
)

$ErrorActionPreference = 'Stop'

function Assert-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($id)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw '需要管理员权限。请右键「以管理员身份运行」PowerShell 后重试。'
    }
}

# 映射盘 / subst 盘 / 网络盘对 SYSTEM 不可见；DisplayRoot 非空即视为非本地盘
function Test-LocalDiskPath([string]$path) {
    try {
        $qualifier = (Split-Path -Qualifier $path).TrimEnd(':')
        $drive = Get-PSDrive -Name $qualifier -ErrorAction SilentlyContinue
        if (-not $drive) { return $false }
        return [string]::IsNullOrEmpty($drive.DisplayRoot)
    } catch {
        return $false
    }
}

Assert-Admin

if ($Uninstall) {
    $task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    if ($task) {
        Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
        Write-Host "已删除计划任务：$TaskName"
    } else {
        Write-Host "计划任务不存在：$TaskName"
    }
    $rule = Get-NetFirewallRule -DisplayName 'CallNotify' -ErrorAction SilentlyContinue
    if ($rule) {
        Remove-NetFirewallRule -DisplayName 'CallNotify'
        Write-Host '已删除防火墙规则 CallNotify'
    }
    return
}

if (-not (Test-Path $ExePath)) {
    throw "找不到服务端 exe：$ExePath（可用 -ExePath 指定）"
}
$ExePath = (Resolve-Path $ExePath).Path

# ---- 1. exe 部署到本地固定目录
# 计划任务以 SYSTEM 身份运行，看不到映射盘 / 网络盘 / subst 盘 / 网盘同步目录，
# 放在那些位置会直接 0x80070002。所以默认一律复制到 C:\callnotify\ 再跑。
$originalExe = $ExePath
$originalData = if ([string]::IsNullOrWhiteSpace($DataFile)) { Join-Path (Split-Path $ExePath) 'data.json' } else { $DataFile }

if ($NoCopy) {
    Write-Host "已指定 -NoCopy，直接在原位注册：$ExePath"
} else {
    New-Item -ItemType Directory -Force -Path $LocalDir | Out-Null
    $targetExe = Join-Path $LocalDir (Split-Path $originalExe -Leaf)
    if ((Resolve-Path $originalExe).Path -ne (Join-Path (Resolve-Path $LocalDir).Path (Split-Path $originalExe -Leaf))) {
        Copy-Item $originalExe $targetExe -Force
        Write-Host "已复制 exe 到本地目录：$targetExe"
        if (Test-Path $originalData) {
            $targetData = Join-Path $LocalDir (Split-Path $originalData -Leaf)
            Copy-Item $originalData $targetData -Force
            Write-Host "已复制设备数据：$targetData（手机不用重新注册）"
            $DataFile = $targetData
        }
        $ExePath = $targetExe
    } else {
        Write-Host "exe 已在本地目录：$ExePath"
    }
}

if ([string]::IsNullOrWhiteSpace($DataFile)) {
    $DataFile = Join-Path (Split-Path $ExePath) 'data.json'
}

# ---- 2. 注册计划任务
$action = New-ScheduledTaskAction `
    -Execute $ExePath `
    -Argument "-port $Port -data `"$DataFile`"" `
    -WorkingDirectory (Split-Path $ExePath)

$trigger = New-ScheduledTaskTrigger -AtStartup

$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -StartWhenAvailable `
    -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -RestartCount 999 `
    -RestartInterval (New-TimeSpan -Minutes 1) `
    -MultipleInstances IgnoreNew

$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest

Register-ScheduledTask `
    -TaskName $TaskName `
    -Action $action `
    -Trigger $trigger `
    -Settings $settings `
    -Principal $principal `
    -Description '远程来电通知服务端（HTTP API + WebSocket）' `
    -Force | Out-Null

Write-Host "`n已注册计划任务：$TaskName"
Write-Host "  exe    : $ExePath"
Write-Host "  端口   : $Port"
Write-Host "  数据   : $DataFile"

# ---- 3. 防火墙
if (-not $SkipFirewall) {
    $rule = Get-NetFirewallRule -DisplayName 'CallNotify' -ErrorAction SilentlyContinue
    if (-not $rule) {
        New-NetFirewallRule -DisplayName 'CallNotify' -Direction Inbound -Action Allow `
            -Protocol TCP -LocalPort $Port -Profile Any | Out-Null
        Write-Host "  防火墙 : 已放行 TCP $Port"
    } elseif ($rule) {
        $existing = ($rule | Get-NetFirewallPortFilter).LocalPort
        if ($existing -notcontains "$Port") {
            Set-NetFirewallRule -DisplayName 'CallNotify' -Enabled True -ErrorAction SilentlyContinue
            Write-Host "  防火墙 : 规则 CallNotify 已存在但端口是 $existing，请手动改成 $Port"
        } else {
            Write-Host "  防火墙 : 已放行 TCP $Port"
        }
    }
}

# ---- 4. 启动并自检
Start-ScheduledTask -TaskName $TaskName
$healthy = $false
for ($i = 0; $i -lt 10; $i++) {
    Start-Sleep -Milliseconds 700
    try {
        $health = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/health" -UseBasicParsing -TimeoutSec 3
        Write-Host "`n已启动，/health 返回：$($health.Content)"
        $healthy = $true
        break
    } catch { }
}

if (-not $healthy) {
    $info = Get-ScheduledTask -TaskName $TaskName | Get-ScheduledTaskInfo
    $code = $info.LastTaskResult
    $hint = switch ($code) {
        2147942402 { '0x80070002 文件找不到 —— exe 路径对 SYSTEM 不可访问（映射盘/网络盘），或有杀软隔离了它' }
        2147942405 { '0x80070005 拒绝访问 —— 目录权限问题，把 exe 放到 C:\callnotify\' }
        2147943645 { '0x8007041D 服务启动超时 —— 检查是否有安全软件拦截' }
        default { "错误码 $code（0x{0:X8}）" -f $code }
    }
    Write-Host "`n任务已注册但 /health 不通：$hint"
    Write-Host "手工排查：Get-ScheduledTask $TaskName | Get-ScheduledTaskInfo"
}

Write-Host "`n常用命令："
Write-Host "  Get-ScheduledTask $TaskName | Get-ScheduledTaskInfo     # 运行状态"
Write-Host "  Start-ScheduledTask $TaskName                            # 启动"
Write-Host "  Stop-ScheduledTask  $TaskName                            # 停止"
Write-Host "  powershell -ExecutionPolicy Bypass -File .\install-service.ps1 -Uninstall   # 卸载"
Write-Host "`n手机端填的地址：http://<这台机器的IP>:$Port   （自检面板 /api/v1/diag）"
