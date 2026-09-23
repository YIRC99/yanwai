# 搭建 Android 编译环境（只在本机跑一次）
#
# 为什么需要这个：写 Xposed 模块必须能编译出 APK 才能验证。
# 本机原本只有 JDK 21 和 platform-tools，没有 Android SDK、没有 Gradle。
#
# 用法：
#   powershell -File tools/setup-android-sdk.ps1     # 任意 PowerShell 版本都行
#
# 装完会写好 local.properties，之后用 tools/gradle.ps1 编译。
#
# 注意：新版 cmdline-tools 换了 CLI —— `sdkmanager --licenses` 与 `--sdk_root`
# 都已废弃，改用 `android sdk install`，许可自动接受。
# 网上老教程里的命令在这套工具上会直接失败。

$ErrorActionPreference = "Stop"

$SdkRoot    = "D:\Android\Sdk"
$ToolsTmp   = "$env:TEMP\android-sdk-setup"
$CmdlineUrl = "https://dl.google.com/android/repository/commandlinetools-win-16111833_latest.zip"
$JdkHome    = "D:\DevEnv\Java\jdk21"

Write-Host "==> SDK 根目录: $SdkRoot"
New-Item -ItemType Directory -Force -Path $SdkRoot, $ToolsTmp | Out-Null

# ---------- 1. 下载并解压 cmdline-tools ----------
$latestDir  = Join-Path $SdkRoot "cmdline-tools\latest"
$androidExe = Join-Path $latestDir "bin\android.exe"

if (-not (Test-Path $androidExe)) {
    $zip = Join-Path $ToolsTmp "cmdline-tools.zip"
    if (-not (Test-Path $zip) -or (Get-Item $zip).Length -lt 100MB) {
        Write-Host "==> 下载 cmdline-tools (~148 MB，慢的话要几分钟)…"
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        Invoke-WebRequest -Uri $CmdlineUrl -OutFile $zip -UseBasicParsing
        $sw.Stop()
        Write-Host ("==> 下载完成 {0:N1} MB，用时 {1:N0}s" -f ((Get-Item $zip).Length/1MB), $sw.Elapsed.TotalSeconds)
    } else {
        Write-Host "==> 复用已下载的 zip"
    }

    Write-Host "==> 解压…"
    $stage = Join-Path $ToolsTmp "stage"
    if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
    Expand-Archive -Path $zip -DestinationPath $stage -Force

    New-Item -ItemType Directory -Force -Path (Join-Path $SdkRoot "cmdline-tools") | Out-Null
    if (Test-Path $latestDir) { Remove-Item $latestDir -Recurse -Force }
    # zip 里是 cmdline-tools/ 一层，最终路径必须是 cmdline-tools/latest/
    Move-Item (Join-Path $stage "cmdline-tools") $latestDir
    Remove-Item $stage -Recurse -Force
    Write-Host "==> 解压完成"
} else {
    Write-Host "==> cmdline-tools 已就绪，跳过下载"
}

if (-not (Test-Path $androidExe)) { throw "没找到 android.exe: $androidExe" }

# ---------- 2. 安装组件 ----------
$env:JAVA_HOME = $JdkHome
$env:ANDROID_HOME = $SdkRoot

# 逐个装：一起装时某一个失败会让整批中断，逐个装能看清是哪个出的问题
foreach ($pkg in @("platform-tools", "platforms;android-35", "build-tools;35.0.0")) {
    Write-Host "==> 安装 $pkg …"
    & $androidExe sdk install --sdk="$SdkRoot" $pkg
    if ($LASTEXITCODE -ne 0) { Write-Warning "$pkg 安装返回码 $LASTEXITCODE" }
}

# ---------- 3. 写 local.properties ----------
$projRoot   = Split-Path $PSScriptRoot -Parent
$localProps = Join-Path $projRoot "local.properties"
# Gradle 的 properties 文件里反斜杠是转义符，路径要写成双反斜杠
$sdkForProps = $SdkRoot -replace '\\', '\\'
Set-Content -Path $localProps -Value "sdk.dir=$sdkForProps" -Encoding ASCII
Write-Host "==> 已写 $localProps"

# ---------- 4. 校验 ----------
Write-Host ""
Write-Host "==> 校验"
$allOk = $true
foreach ($c in @(
    "cmdline-tools\latest\bin\android.exe",
    "platform-tools\adb.exe",
    "platforms\android-35\android.jar",
    "build-tools\35.0.0\aapt2.exe"
)) {
    $full = Join-Path $SdkRoot $c
    if (Test-Path $full) { Write-Host "    OK   $c" } else { Write-Host "    缺失 $c"; $allOk = $false }
}

Write-Host ""
if ($allOk) {
    Write-Host "==> 完成。接下来：powershell -File tools/gradle.ps1 :app:assembleDebug"
} else {
    Write-Host "==> 有组件没装上，检查上面的输出。"
    exit 1
}
