param(
    [string]$AndroidHome = "$env:LOCALAPPDATA\Android\Sdk"
)

$ErrorActionPreference = 'Stop'
$toolsUrl = 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip'
$toolsSha256 = '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a'

function Find-JavaHome {
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) { return $env:JAVA_HOME }
    $javaCommand = Get-Command java -ErrorAction SilentlyContinue
    if ($javaCommand) { return (Split-Path -Parent (Split-Path -Parent $javaCommand.Source)) }
    $roots = @('C:\Program Files\Microsoft', 'C:\Program Files\Java', 'C:\Program Files\Eclipse Adoptium')
    foreach ($root in $roots) {
        if (Test-Path $root) {
            $candidate = Get-ChildItem $root -Directory -Filter '*17*' -ErrorAction SilentlyContinue |
                Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } |
                Sort-Object Name -Descending | Select-Object -First 1
            if ($candidate) { return $candidate.FullName }
        }
    }
    return $null
}

$javaHome = Find-JavaHome
if (-not $javaHome) {
    if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
        throw 'Java 17 belum ada. Install OpenJDK 17 lalu jalankan script ini lagi.'
    }
    winget install --id Microsoft.OpenJDK.17 -e --accept-package-agreements --accept-source-agreements
    $javaHome = Find-JavaHome
    if (-not $javaHome) { throw 'OpenJDK selesai diinstall tetapi folder Java belum ditemukan. Buka PowerShell baru.' }
}
[Environment]::SetEnvironmentVariable('JAVA_HOME', $javaHome, 'User')
$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$env:Path"

$archive = Join-Path $env:TEMP 'nusamesh-android-commandlinetools.zip'
$extract = Join-Path $env:TEMP 'nusamesh-android-commandlinetools'
New-Item -ItemType Directory -Force -Path $AndroidHome | Out-Null

if (-not (Test-Path "$AndroidHome\cmdline-tools\latest\bin\sdkmanager.bat")) {
    Invoke-WebRequest -UseBasicParsing $toolsUrl -OutFile $archive
    $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash.ToLowerInvariant()
    if ($actualHash -ne $toolsSha256) { throw "Checksum command-line tools tidak cocok: $actualHash" }
    if (Test-Path $extract) { Remove-Item -LiteralPath $extract -Recurse -Force }
    Expand-Archive -LiteralPath $archive -DestinationPath $extract
    New-Item -ItemType Directory -Force -Path "$AndroidHome\cmdline-tools\latest" | Out-Null
    Copy-Item -Path "$extract\cmdline-tools\*" -Destination "$AndroidHome\cmdline-tools\latest" -Recurse
}

[Environment]::SetEnvironmentVariable('ANDROID_HOME', $AndroidHome, 'User')
$env:ANDROID_HOME = $AndroidHome
$sdkManager = "$AndroidHome\cmdline-tools\latest\bin\sdkmanager.bat"

Write-Host 'Baca dan setujui lisensi Android SDK pada prompt berikut.'
& $sdkManager --licenses
& $sdkManager 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0'

Write-Host "Android CLI siap di $AndroidHome"
Write-Host 'Tutup dan buka PowerShell, lalu jalankan scripts\deploy-android.ps1'
