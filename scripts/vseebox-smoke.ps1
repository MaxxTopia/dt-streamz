param(
    [string]$Serial = "",
    [string]$ApkPath = "",
    [string]$PackageName = "com.dt.streamz.debug",
    [string]$OutputDir = ""
)

$ErrorActionPreference = "Stop"

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb was not found on PATH. Start the Android SDK environment first."
}

if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    $ApkPath = Join-Path $PSScriptRoot "..\app\build\outputs\apk\debug\app-debug.apk"
}
$ApkPath = (Resolve-Path $ApkPath).Path

if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $OutputDir = Join-Path $PSScriptRoot "..\build\smoke"
}
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$adbArgs = @()
if (-not [string]::IsNullOrWhiteSpace($Serial)) {
    $adbArgs += @("-s", $Serial)
}

& adb @adbArgs get-state | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "No ready Android device or emulator was found."
}

Write-Host "Installing $ApkPath"
& adb @adbArgs install -r $ApkPath
if ($LASTEXITCODE -ne 0) {
    throw "adb install failed."
}

& adb @adbArgs shell am force-stop $PackageName
& adb @adbArgs shell monkey -p $PackageName -c android.intent.category.LAUNCHER 1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Could not launch $PackageName."
}

Start-Sleep -Seconds 4
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$logPath = Join-Path $OutputDir "smoke-$stamp.log"
& adb @adbArgs logcat -d -t 500 -v time | Out-File -FilePath $logPath -Encoding ascii

Write-Host "Launched $PackageName"
Write-Host "Log: $logPath"
