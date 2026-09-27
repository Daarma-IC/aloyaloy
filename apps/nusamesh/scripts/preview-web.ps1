$ErrorActionPreference = 'Stop'
Set-Location (Resolve-Path (Join-Path $PSScriptRoot '..'))

if (-not $env:JAVA_HOME) {
    $env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
}
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.gradle-user-home'

Write-Host 'Menjalankan preview NusaMesh:'
Write-Host '  Home : http://localhost:8080/'
Write-Host '  Chat : http://localhost:8080/#global'
Write-Host '  Map  : http://localhost:8080/#map'
& .\gradlew.bat :composeApp:wasmJsBrowserDevelopmentRun

