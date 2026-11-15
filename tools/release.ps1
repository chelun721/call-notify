<#
    一条命令发布新版本：编译客户端与服务端 → 生成校验文件 → 建 GitHub Release → 上传全部资产。

    前置：
        1. 改版本号（android/app/build.gradle.kts 里的 versionCode / versionName）
        2. 设置令牌（不要写进仓库）：$env:GITHUB_TOKEN = "github_pat_xxx"

    用法：
        $env:GITHUB_TOKEN = "github_pat_xxx"
        .\tools\release.ps1                       # 用 build.gradle.kts 里的版本号
        .\tools\release.ps1 -Version 1.10.0       # 显式指定
        .\tools\release.ps1 -SkipBuild            # 只用 dist/ 里已有的产物

    需要一个有 repo 权限的令牌；发布完成后建议立即在 GitHub 上撤销并重建。
#>

param(
    [string]$Version = '',
    [string]$Repo = 'chelun721/call-notify',
    [switch]$SkipBuild,
    [switch]$Draft
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$dist = Join-Path $root 'dist'
$token = $env:GITHUB_TOKEN
if ([string]::IsNullOrWhiteSpace($token)) { throw '请先设置 $env:GITHUB_TOKEN' }

# ---- 版本号
if ([string]::IsNullOrWhiteSpace($Version)) {
    $m = Select-String -Path (Join-Path $root 'android\app\build.gradle.kts') -Pattern 'versionName\s*=\s*"([^"]+)"' | Select-Object -First 1
    if (-not $m) { throw '读不到 versionName' }
    $Version = $m.Matches[0].Groups[1].Value
}
$tag = "v$Version"
Write-Host "发布版本：$tag"

# ---- 编译
if (-not $SkipBuild) {
    Write-Host "`n[1/4] 编译 Android"
    Push-Location (Join-Path $root 'android')
    & .\gradlew.bat assembleRelease assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Android 编译失败' }
    Pop-Location

    Write-Host "`n[2/4] 编译服务端（三平台）"
    Push-Location (Join-Path $root 'server-go')
    $env:CGO_ENABLED = '0'
    foreach ($t in @(
            @{ os = 'windows'; arch = 'amd64'; out = "..\dist\callnotify-server-windows-amd64.exe" },
            @{ os = 'linux'; arch = 'amd64'; out = "..\dist\callnotify-server-linux-amd64" },
            @{ os = 'linux'; arch = 'arm64'; out = "..\dist\callnotify-server-linux-arm64" })) {
        $env:GOOS = $t.os; $env:GOARCH = $t.arch
        & go build -trimpath -ldflags '-s -w' -o $t.out .
        if ($LASTEXITCODE -ne 0) { throw "编译失败 $($t.os)/$($t.arch)" }
    }
    Pop-Location
}

# ---- 归档产物
Write-Host "`n[3/4] 归档产物"
New-Item -ItemType Directory -Force -Path $dist | Out-Null
Copy-Item (Join-Path $root "android\app\build\outputs\apk\release\app-release.apk") (Join-Path $dist "CallNotify-$Version-release.apk") -Force
Copy-Item (Join-Path $root "android\app\build\outputs\apk\debug\app-debug.apk") (Join-Path $dist "CallNotify-$Version-debug.apk") -Force

$assets = @(
    "CallNotify-$Version-release.apk",
    "CallNotify-$Version-debug.apk",
    'callnotify-server-windows-amd64.exe',
    'callnotify-server-linux-amd64',
    'callnotify-server-linux-arm64'
)
$lines = foreach ($a in $assets) {
    $p = Join-Path $dist $a
    if (-not (Test-Path $p)) { throw "缺少产物：$a" }
    "$((Get-FileHash $p -Algorithm SHA256).Hash.ToLower())  $a"
}
$sums = Join-Path $dist 'SHA256SUMS.txt'
[System.IO.File]::WriteAllText($sums, ($lines -join "`n") + "`n", (New-Object System.Text.UTF8Encoding($false)))
$assets += 'SHA256SUMS.txt'

# ---- 建 Release 并上传
Write-Host "`n[4/4] 创建 Release 并上传"
$headers = @{ Authorization = "Bearer $token"; 'User-Agent' = 'callnotify-release'; Accept = 'application/vnd.github+json' }
$notes = Get-Content (Join-Path $root 'RELEASE_NOTES.md') -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
$payload = @{ tag_name = $tag; name = "$tag · 来电通知 / CallNotify"; body = $notes; draft = [bool]$Draft; prerelease = $false } | ConvertTo-Json
$bytes = [System.Text.Encoding]::UTF8.GetBytes($payload)
$rel = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases" -Method Post -Headers $headers -Body $bytes -ContentType 'application/json; charset=utf-8'
Write-Host "Release: $($rel.html_url)"

$uploadBase = $rel.upload_url -replace '\{.*$', ''
foreach ($a in $assets) {
    $p = Join-Path $dist $a
    $r = Invoke-RestMethod -Uri "$uploadBase`?name=$a" -Method Post -Headers $headers -InFile $p -ContentType 'application/octet-stream'
    Write-Host ("  ✓ {0}  {1:N2} MB" -f $a, ($r.size / 1MB))
}

Write-Host "`n完成：$($rel.html_url)"
Write-Host '提醒：发布用的令牌已在本次会话中使用过，建议立刻在 GitHub 撤销并重建。'
