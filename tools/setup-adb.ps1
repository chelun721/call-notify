<#
    零点击装机脚本（PC + USB 数据线）

    做法：用 adb 把「用户需要手点的部分」全部代劳 —— 运行时权限、电池白名单、
    以及最关键的：直接把无障碍服务写进系统设置（adb shell 有 WRITE_SECURE_SETTINGS，
    这一步普通 App 做不到）。

    跑完之后打开 App，它会自动完成剩下的开关（通话账户 / 自启动）。

    用法：
        .\setup-adb.ps1                       # 自动选唯一在线设备
        .\setup-adb.ps1 -Serial <序列号>      # 多设备时指定
        .\setup-adb.ps1 -Package com.callnotify.app.debug   # 装的是调试包时
#>

param(
    [string]$Serial = '',
    [string]$Package = 'com.callnotify.app',
    [string]$Apk = ''
)

$ErrorActionPreference = 'Stop'

function Find-Adb {
    $candidates = @(
        "$env:ANDROID_HOME\platform-tools\adb.exe",
        "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe",
        'C:\Android\Sdk\platform-tools\adb.exe',
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
    )
    foreach ($c in $candidates) { if ($c -and (Test-Path $c)) { return $c } }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw '找不到 adb，请安装 Android platform-tools 或设置 ANDROID_HOME'
}

$adb = Find-Adb
if ([string]::IsNullOrWhiteSpace($Serial)) {
    $devices = (& $adb devices) | Select-String 'device$' | ForEach-Object { ($_ -split '\s+')[0] }
    if ($devices.Count -eq 0) { throw '没有检测到设备，请确认已开 USB 调试并授权' }
    $Serial = $devices[0]
    Write-Host "使用设备：$Serial"
}

function Sh([string]$cmd) {
    $out = & $adb -s $Serial shell $cmd 2>&1
    Write-Host "  adb shell $cmd" -ForegroundColor DarkGray
    if ($out) { $out | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray } }
}

if ([string]::IsNullOrWhiteSpace($Apk)) {
    $dist = Join-Path (Split-Path $PSScriptRoot -Parent) 'dist'
    $Apk = Get-ChildItem $dist -Filter 'CallNotify-*-release.apk' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
}
if ($Apk -and (Test-Path $Apk)) {
    Write-Host "安装 $Apk"
    & $adb -s $Serial install -r $Apk | Write-Host
} else {
    Write-Host '跳过安装（没找到 APK，假定已安装）' -ForegroundColor Yellow
}

Write-Host "`n[1/4] 授予运行时权限"
foreach ($p in @(
        'android.permission.POST_NOTIFICATIONS',
        'android.permission.READ_PHONE_STATE',
        'android.permission.READ_PHONE_NUMBERS'
    )) {
    Sh "pm grant $Package $p"
}

Write-Host "`n[2/4] 加入电池优化白名单"
Sh "dumpsys deviceidle whitelist +$Package"

Write-Host "`n[3/5] 打开无障碍自动配置服务（App 自己做不到这一步）"
$service = "$Package/com.callnotify.app.autoconfig.AutoConfigService"
if ($Package -ne 'com.callnotify.app') {
    $service = "$Package/$Package.autoconfig.AutoConfigService"
}
Sh "settings put secure enabled_accessibility_services $service"
Sh "settings put secure accessibility_enabled 1"
Start-Sleep -Seconds 1

Write-Host "`n[4/5] 拉起被藏起来的「通话账户」页（一加/OPPO 设置里没有入口，只能显式组件）"
Sh "am start -a android.intent.action.MAIN -n com.android.server.telecom/.settings.EnableAccountPreferenceActivity"
Start-Sleep -Seconds 3
Write-Host "  无障碍服务应当已自动把「$Package」的开关打开并返回；没生效就手动点一下那个开关。" -ForegroundColor DarkGray

Write-Host "`n[5/5] 启动 App"
Sh "am start -n $Package/.MainActivity"

Write-Host @"

完成。手机打开 App 后会自动完成剩余的开关（通话账户 / 自启动），全程不用手点。

如果无障碍没生效（部分 ROM 会忽略 secure settings 写入）：
  设置 → 无障碍 → 「来电通知」→ 手动打开一次即可，之后同上。

验证：
  adb shell dumpsys deviceidle whitelist | Select-String $Package
  adb shell settings get secure enabled_accessibility_services
"@
