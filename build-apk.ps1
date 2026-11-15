<#
    一键编译 APK（Windows）

    为什么要这个脚本：
      工程路径含中文（X:\dsh开发\电话通知）时，
        - AGP 会直接报 "Your project path contains non-ASCII characters" 并中断；
        - Kotlin 编译器会报 source file or directory not found: X:\dshu5F00u53D1\...
      所以脚本先把工程映射到一个纯英文路径（目录联接），在那边编译，再把 APK 拷回 dist\。

    用法：
      powershell -ExecutionPolicy Bypass -File .\build-apk.ps1              # 编 release
      powershell -ExecutionPolicy Bypass -File .\build-apk.ps1 -Target both # release + debug
#>

param(
    [ValidateSet('release', 'debug', 'both')]
    [string]$Target = 'release',
    [string]$LinkPath = 'C:\cn\phone'
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
if (-not $root) { $root = Split-Path -Parent $MyInvocation.MyCommand.Path }
if (-not (Test-Path (Join-Path $root 'android\settings.gradle.kts'))) {
    throw "请在工程根目录（含 android\settings.gradle.kts）执行本脚本：$root"
}

# ---------------------------------------------------------------- 1. 英文路径映射
function New-AsciiLink([string]$target, [string]$link) {
    if (Test-Path $link) {
        $item = Get-Item $link -Force
        if ($item.LinkType -eq 'Junction' -and $item.Target -eq $target) { return $link }
        cmd /c rmdir "`"$link`"" | Out-Null
    }
    $parent = Split-Path -Parent $link
    if (-not (Test-Path $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    cmd /c mklink /J "`"$link`"" "`"$target`"" | Out-Null
    if (-not (Test-Path (Join-Path $link 'android\settings.gradle.kts'))) {
        throw "创建目录联接失败：$link -> $target（需要管理员权限时可换一个盘符或手改 -LinkPath）"
    }
    return $link
}

$ascii = New-AsciiLink $root $LinkPath
Write-Host "[1/4] 英文路径映射：$LinkPath -> $root"

# ---------------------------------------------------------------- 2. 环境探测
function Resolve-JavaHome {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) { return $env:JAVA_HOME }
    $candidates = @(
        'C:\Tools\jdk-21',
        'C:\Program Files\Android\Android Studio\jbr',
        'C:\Program Files\Java\jdk-17',
        'C:\Program Files\Eclipse Adoptium\jdk-17*'
    )
    foreach ($c in $candidates) {
        $hit = Get-Item $c -ErrorAction SilentlyContinue
        if ($hit -and (Test-Path (Join-Path $hit.FullName 'bin\javac.exe'))) { return $hit.FullName }
    }
    throw '找不到 JDK 17+，请设置 JAVA_HOME。'
}

function Resolve-AndroidSdk {
    $candidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, 'C:\Android\Sdk', "$env:LOCALAPPDATA\Android\Sdk")
    foreach ($c in $candidates) {
        if ($c -and (Test-Path (Join-Path $c 'platforms\android-34\android.jar'))) { return (Resolve-Path $c).Path }
    }
    throw '找不到 Android SDK（需要 platforms;android-34 与 build-tools;34.0.0）。'
}

function Resolve-Gradle {
    if (Test-Path 'C:\Tools\gradle-8.7\bin\gradle.bat') { return 'C:\Tools\gradle-8.7\bin\gradle.bat' }
    if ($env:GRADLE_HOME -and (Test-Path (Join-Path $env:GRADLE_HOME 'bin\gradle.bat'))) { return (Join-Path $env:GRADLE_HOME 'bin\gradle.bat') }
    $wrapper = Join-Path $ascii 'android\gradlew.bat'
    if (Test-Path $wrapper) { return $wrapper }   # 首次会自己下载 Gradle 发行包
    throw '找不到 Gradle，也找不到 gradlew.bat。'
}

$env:JAVA_HOME = Resolve-JavaHome
$env:ANDROID_HOME = Resolve-AndroidSdk
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$gradle = Resolve-Gradle
Write-Host "[2/4] JDK：$env:JAVA_HOME"
Write-Host "      SDK：$env:ANDROID_HOME"
Write-Host "      Gradle：$gradle"

# local.properties 每次按当前 SDK 位置修正
$sdkEscaped = $env:ANDROID_HOME.Replace('\', '\\').Replace(':', '\:')
Set-Content -LiteralPath (Join-Path $ascii 'android\local.properties') -Value "sdk.dir=$sdkEscaped" -Encoding ASCII

# ---------------------------------------------------------------- 3. 编译
$tasks = switch ($Target) {
    'release' { @(':app:assembleRelease') }
    'debug' { @(':app:assembleDebug') }
    'both' { @(':app:assembleRelease', ':app:assembleDebug') }
}
Write-Host "[3/4] 编译：$($tasks -join ' ')"
Push-Location (Join-Path $ascii 'android')
try {
    & $gradle @tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle 编译失败（退出码 $LASTEXITCODE）" }
} finally {
    Pop-Location
}

# ---------------------------------------------------------------- 4. 产物归档
$version = '1.0.0'
$versionMatch = Select-String -Path (Join-Path $ascii 'android\app\build.gradle.kts') -Pattern 'versionName\s*=\s*"([^"]+)"' | Select-Object -First 1
if ($versionMatch) { $version = $versionMatch.Matches[0].Groups[1].Value }
$dist = Join-Path $root 'dist'
New-Item -ItemType Directory -Force -Path $dist | Out-Null

$map = @{
    'release' = @{ src = 'android\app\build\outputs\apk\release\app-release.apk'; name = "CallNotify-$version-release.apk" }
    'debug'   = @{ src = 'android\app\build\outputs\apk\debug\app-debug.apk'; name = "CallNotify-$version-debug.apk" }
}
foreach ($key in $map.Keys) {
    if ($Target -ne 'both' -and $Target -ne $key) { continue }
    $src = Join-Path $ascii $map[$key].src
    if (Test-Path $src) {
        $dst = Join-Path $dist $map[$key].name
        Copy-Item $src $dst -Force
        $hash = (Get-FileHash $dst -Algorithm SHA256).Hash
        Write-Host ("[4/4] {0}  {1:N1} MB  sha256={2}" -f $map[$key].name, ((Get-Item $dst).Length / 1MB), $hash)
    }
}
Write-Host "完成，产物在：$dist"
