param(
    [string]$AndroidHome = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" })
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $AndroidHome 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) { throw 'adb belum ada. Jalankan scripts\setup-android-cli.ps1 lebih dulu.' }
if (-not $env:JAVA_HOME) {
    $jdk = Get-ChildItem 'C:\Program Files\Microsoft' -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    throw 'JAVA_HOME belum menunjuk ke JDK 17. Jalankan scripts\setup-android-cli.ps1.'
}
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

Push-Location $projectRoot
try {
    & .\gradlew.bat :composeApp:assembleDebug
    if ($LASTEXITCODE -ne 0) { throw 'Build APK gagal.' }

    & $adb start-server
    $devices = & $adb devices
    if (-not ($devices -match "`tdevice$")) {
        throw 'Ponsel belum terdeteksi. Aktifkan USB debugging dan setujui fingerprint.'
    }

    $apk = Join-Path $projectRoot 'composeApp\build\outputs\apk\debug\composeApp-debug.apk'
    & $adb install -r $apk
    if ($LASTEXITCODE -ne 0) { throw 'Instalasi APK gagal.' }
    & $adb shell am start -n 'id.nusamesh.app/.MainActivity'
    Write-Host 'NusaMesh berhasil dibuild, diinstal, dan dibuka di ponsel.'
}
finally {
    Pop-Location
}
