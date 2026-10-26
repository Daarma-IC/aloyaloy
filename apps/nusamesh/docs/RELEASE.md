# Release NusaMesh 0.3.1 (17)

Android produces APK/AAB; iOS produces an Xcode archive and, after Apple signing, an IPA. This workflow builds local artifacts only. It does not push Git, publish a GitHub release, upload to Play Console, or upload to App Store Connect.

## Android on Windows

Use JDK 17 and the Android SDK already used for debug builds:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
# Compile and test release without choosing a signing identity:
.\scripts\release-android.ps1 -Unsigned -Offline
# Existing distribution key (passwords are prompted without echo):
.\scripts\release-android.ps1 -KeyStore 'C:\private\nusamesh-release.p12' -KeyAlias 'nusamesh' -Offline
# Memory-constrained Windows host: one worker, sequential tasks, 1536 MB Gradle heap:
.\scripts\release-android.ps1 -Offline -LowMemory
# Only for a new distribution identity:
.\scripts\release-android.ps1 -CreateSigningKey -Offline
```

The new-key option never overwrites a key. It stores the PKCS12 key outside the repository at the current user's .android/nusamesh directory. Its password is saved in a Windows DPAPI-encrypted credentials file for the current Windows user. Back up the key and retain its password securely; the DPAPI credentials file alone is not portable to another Windows installation. No password, key, or provisioning profile belongs in Git or release downloads.

Automation can instead provide NUSAMESH_KEYSTORE, NUSAMESH_KEY_ALIAS, NUSAMESH_STORE_PASSWORD and NUSAMESH_KEY_PASSWORD as environment secrets. Signing configuration has no fallback to Android Debug. The helper verifies the APK signature, rejects the debug certificate, checks that the APK is not debuggable, and verifies the AAB signature. Versioned artifacts and SHA-256 hashes are placed in releases/v0.3.1. Published workspace artifacts are copied to the root release/v0.3.1 folder.

An APK signed with a new release identity cannot update an installed APK signed with Android Debug or another key. Keep the existing key if update compatibility is required. Do not uninstall an existing app without preserving its local data. An unsigned APK is a compile artifact and cannot be installed/distributed as a signed release.

The AAB is for a store upload workflow; the APK is for direct Android distribution. Unsigned validation artifacts are separated into the validation subfolder. Store acceptance and physical device behavior are separate from local compilation and signature checks.

## iOS on a Mac

Requirements: macOS with full Xcode and iPhoneOS SDK, XcodeGen, JDK 17, python3, initialized Codec2 source, plus the Apple certificate/provisioning required for your distribution method. Xcode signing must already be configured for the team.

```sh
# Compile/archive validation without a distribution signature:
sh scripts/release-ios.sh --unsigned
# Signed device archive:
sh scripts/release-ios.sh --team-id YOUR_APPLE_TEAM_ID
# Export locally using ExportOptions.plist from the intended Xcode distribution method:
sh scripts/release-ios.sh --team-id YOUR_APPLE_TEAM_ID --export-options /private/ExportOptions.plist
```

The script synchronizes Android/iOS version settings from version.properties, generates the Xcode project, archives Release for an iPhone device, verifies a signed app, and optionally exports an IPA. ExportOptions.plist must use destination=export; automatic upload is rejected. The Gradle iOS cinterop task builds and embeds the native Codec2 static library. The native build enables both legacy Codec2 3200 decoding and current 1200 mode.

Windows cannot execute the Xcode archive/export pipeline. A source archive or an unsigned .app is not an installable IPA. iOS compilation and signing must succeed on a Mac before the iOS build can be described as ready. iOS-specific media/location/BLE behavior has not been validated by Android tests. The current iOS recorder uses AAC; Codec2 interoperability must also be verified on iPhone.

Build references: [Kotlin/Native static library embedding](https://kotlinlang.org/docs/native-definition-file.html#include-a-static-library), [Apple command-line archive/export](https://developer.apple.com/library/archive/technotes/tn2339/_index.html).
