param(
    [switch]$Unsigned,
    [switch]$CreateSigningKey,
    [string]$KeyStore = $env:NUSAMESH_KEYSTORE,
    [string]$KeyAlias = $env:NUSAMESH_KEY_ALIAS,
    [switch]$Offline,
    [switch]$LowMemory
)
$ErrorActionPreference = 'Stop'
if (-not $KeyAlias) { $KeyAlias = 'nusamesh' }
$projectRoot = Split-Path -Parent $PSScriptRoot
$version = ConvertFrom-StringData (Get-Content -LiteralPath (Join-Path $projectRoot 'version.properties') -Raw)
$output = Join-Path $projectRoot ('releases/v' + $version.versionName)
if ($Unsigned) { $output = Join-Path $output 'validation' }
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 17.' }
$keytool = Join-Path $env:JAVA_HOME 'bin/keytool.exe'
$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
$buildTools = Get-ChildItem (Join-Path $sdkRoot 'build-tools') -Directory | Sort-Object Name -Descending | Select-Object -First 1
if (-not $buildTools) { throw 'Android build-tools not found.' }
$envNames = @('NUSAMESH_KEYSTORE','NUSAMESH_KEY_ALIAS','NUSAMESH_STORE_PASSWORD','NUSAMESH_KEY_PASSWORD')
$previous = @{}
foreach ($name in $envNames) { $previous[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
Push-Location $projectRoot
try {
    if ($Unsigned -and $CreateSigningKey) { throw 'Choose unsigned build or create signing key, not both.' }
    if ($Unsigned) {
        foreach ($name in $envNames) { [Environment]::SetEnvironmentVariable($name,$null,'Process') }
    } else {
        if (-not $KeyStore) { $KeyStore = Join-Path $env:USERPROFILE '.android/nusamesh/nusamesh-release.p12' }
        $KeyStore = [IO.Path]::GetFullPath($KeyStore)
        $credentialPath = $KeyStore + '.credentials.clixml'
        if ($CreateSigningKey) {
            if (Test-Path -LiteralPath $KeyStore) { throw 'Keystore already exists; it will not be replaced.' }
            New-Item -ItemType Directory -Path (Split-Path -Parent $KeyStore) -Force | Out-Null
            $randomBytes = New-Object byte[] 32
            $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
            try { $rng.GetBytes($randomBytes) } finally { $rng.Dispose() }
            $env:NUSAMESH_STORE_PASSWORD = [Convert]::ToBase64String($randomBytes)
            $env:NUSAMESH_KEY_PASSWORD = $env:NUSAMESH_STORE_PASSWORD
            & $keytool -genkeypair -keystore $KeyStore -storetype PKCS12 -alias $KeyAlias -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=NusaMesh Release,O=NusaMesh,C=ID' -storepass:env NUSAMESH_STORE_PASSWORD -keypass:env NUSAMESH_KEY_PASSWORD
            if ($LASTEXITCODE -ne 0) { throw 'Release key generation failed.' }
            [pscredential]::new($KeyAlias,(ConvertTo-SecureString $env:NUSAMESH_STORE_PASSWORD -AsPlainText -Force)) | Export-Clixml -LiteralPath $credentialPath
            Write-Host ('Release key saved outside repository: ' + $KeyStore)
            Write-Host 'Password is encrypted with Windows DPAPI for this Windows user. Back up the key and password securely.'
        } elseif (-not (Test-Path -LiteralPath $KeyStore)) {
            throw 'Release key missing. Supply -KeyStore or explicitly use -CreateSigningKey. Use -Unsigned for build validation.'
        }
        if (-not $env:NUSAMESH_STORE_PASSWORD) {
            if (Test-Path -LiteralPath $credentialPath) {
                $credential = Import-Clixml -LiteralPath $credentialPath
                $KeyAlias = $credential.UserName
                $env:NUSAMESH_STORE_PASSWORD = $credential.GetNetworkCredential().Password
                $env:NUSAMESH_KEY_PASSWORD = $env:NUSAMESH_STORE_PASSWORD
            } else {
                $secure = Read-Host 'Keystore password (not saved)' -AsSecureString
                $env:NUSAMESH_STORE_PASSWORD = [Net.NetworkCredential]::new('', $secure).Password
            }
        }
        if (-not $env:NUSAMESH_KEY_PASSWORD) {
            $secure = Read-Host 'Signing key password (not saved)' -AsSecureString
            $env:NUSAMESH_KEY_PASSWORD = [Net.NetworkCredential]::new('', $secure).Password
        }
        $env:NUSAMESH_KEYSTORE = $KeyStore
        $env:NUSAMESH_KEY_ALIAS = $KeyAlias
    }
    $gradleArgs = @(':composeApp:assembleRelease',':composeApp:bundleRelease',':composeApp:testReleaseUnitTest','--console=plain')
    if ($Offline) { $gradleArgs += '--offline' }
    if ($LowMemory) {
        $gradleArgs += @('--no-daemon','--no-parallel','--max-workers=1',
            '-Dorg.gradle.jvmargs=-Xmx1536m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8',
            '-Pkotlin.compiler.execution.strategy=in-process')
    }
    & .\gradlew.bat @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw 'Release build or tests failed.' }
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    $suffix = if ($Unsigned) { '-unsigned' } else { '' }
    $apkName = 'NusaMesh-v' + $version.versionName + '-release' + $suffix + '.apk'
    $aabName = 'NusaMesh-v' + $version.versionName + '-release' + $suffix + '.aab'
    $apkSource = if ($Unsigned) { 'composeApp/build/outputs/apk/release/composeApp-release-unsigned.apk' } else { 'composeApp/build/outputs/apk/release/composeApp-release.apk' }
    $apk = Join-Path $output $apkName
    $aab = Join-Path $output $aabName
    Copy-Item -LiteralPath $apkSource -Destination $apk
    Copy-Item -LiteralPath 'composeApp/build/outputs/bundle/release/composeApp-release.aab' -Destination $aab
    $badging = & (Join-Path $buildTools.FullName 'aapt.exe') dump badging $apk
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect release APK.' }
    if ($badging -match 'application-debuggable') { throw 'Release APK is debuggable.' }
    if (-not ($badging -match ("versionCode='" + $version.versionCode + "'"))) { throw 'Wrong APK versionCode.' }
    if (-not $Unsigned) {
        $certificate = & (Join-Path $buildTools.FullName 'apksigner.bat') verify --verbose --print-certs $apk
        if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
        if ($certificate -match 'CN=Android Debug') { throw 'Debug certificate is not allowed for distribution release.' }
        $certificate | Set-Content -LiteralPath (Join-Path $output 'android-certificate.txt')
        $bundleSignature = & (Join-Path $env:JAVA_HOME 'bin/jarsigner.exe') '-J-Duser.language=en' '-J-Duser.country=US' -verify $aab
        if ($LASTEXITCODE -ne 0 -or -not ($bundleSignature -match '^jar verified\.')) { throw 'AAB signature verification failed or bundle is unsigned.' }
        $bundleSignature | Set-Content -LiteralPath (Join-Path $output 'android-bundle-signature.txt')
    }
    $manifest = [ordered]@{
        version = $version.versionName; versionCode = [int]$version.versionCode
        platform = 'android'; buildType = 'release'; debuggable = $false; signed = (-not $Unsigned)
        apk = $apkName; apkSha256 = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
        aab = $aabName; aabSha256 = (Get-FileHash -LiteralPath $aab -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    $manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output ('android-manifest' + $suffix + '.json')) -Encoding UTF8
    Write-Host ('Artifacts: ' + $output)
    if ($Unsigned) { Write-Host 'UNSIGNED: not installable or ready for distribution until signed with the chosen release key.' }
}
finally {
    foreach ($name in $envNames) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
    Pop-Location
}
