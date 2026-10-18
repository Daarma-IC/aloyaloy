#!/bin/sh
set -eu
if [ "$(uname -s)" != "Darwin" ]; then
  echo "iOS archives and IPA exports require macOS with Xcode. No IPA was built." >&2
  exit 2
fi
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TEAM_ID=""
EXPORT_OPTIONS=""
UNSIGNED=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --team-id) TEAM_ID="$2"; shift 2 ;;
    --export-options) EXPORT_OPTIONS="$2"; shift 2 ;;
    --unsigned) UNSIGNED=1; shift ;;
    *) echo "Usage: sh scripts/release-ios.sh [--team-id TEAM] [--export-options FILE] [--unsigned]" >&2; exit 2 ;;
  esac
done
for tool in xcodebuild xcrun xcodegen python3 java; do
  command -v "$tool" >/dev/null 2>&1 || { echo "Missing required tool: $tool" >&2; exit 2; }
done
xcrun --sdk iphoneos --show-sdk-path >/dev/null
VERSION="$(sed -n 's/^versionName=//p' "$ROOT/version.properties" | tr -d '\r')"
BUILD="$(sed -n 's/^versionCode=//p' "$ROOT/version.properties" | tr -d '\r')"
OUT="$ROOT/releases/v$VERSION/ios"
ARCHIVE="$OUT/NusaMesh-v$VERSION.xcarchive"
if [ "$UNSIGNED" -eq 0 ] && [ -z "$TEAM_ID" ]; then
  echo "Supply --team-id from your Apple Developer account, or --unsigned for compile/archive validation." >&2
  exit 2
fi
if [ "$UNSIGNED" -eq 1 ] && [ -n "$EXPORT_OPTIONS" ]; then
  echo "An unsigned archive cannot be exported as a distribution IPA." >&2
  exit 2
fi
if [ -n "$EXPORT_OPTIONS" ]; then
  EXPORT_OPTIONS="$(cd "$(dirname "$EXPORT_OPTIONS")" && pwd)/$(basename "$EXPORT_OPTIONS")"
  python3 - "$EXPORT_OPTIONS" <<'PY'
import plistlib, sys
with open(sys.argv[1], "rb") as f:
    options = plistlib.load(f)
if options.get("destination", "export") != "export":
    raise SystemExit("Use destination=export. This script does not upload to App Store Connect.")
PY
fi
# XcodeGen rewrites Info.plist; version values are always provided as build settings.
cd "$ROOT/iosApp"
xcodegen generate --spec project.yml
set -- -project "$ROOT/iosApp/NusaMesh.xcodeproj" -scheme NusaMesh -configuration Release -destination "generic/platform=iOS" -archivePath "$ARCHIVE" "MARKETING_VERSION=$VERSION" "CURRENT_PROJECT_VERSION=$BUILD"
if [ "$UNSIGNED" -eq 1 ]; then
  set -- "$@" CODE_SIGNING_ALLOWED=NO
else
  set -- "$@" "DEVELOPMENT_TEAM=$TEAM_ID" CODE_SIGN_STYLE=Automatic
fi
xcodebuild "$@" archive
APP="$ARCHIVE/Products/Applications/NusaMesh.app"
[ -d "$APP" ] || { echo "Archive does not contain NusaMesh.app" >&2; exit 1; }
if [ "$UNSIGNED" -eq 0 ]; then
  codesign --verify --deep --strict "$APP"
fi
if [ -n "$EXPORT_OPTIONS" ]; then
  xcodebuild -exportArchive -archivePath "$ARCHIVE" -exportOptionsPlist "$EXPORT_OPTIONS" -exportPath "$OUT/export"
  find "$OUT/export" -name '*.ipa' -type f -exec shasum -a 256 {} \; > "$OUT/SHA256SUMS.txt"
  [ -s "$OUT/SHA256SUMS.txt" ] || { echo "No IPA was exported." >&2; exit 1; }
  echo "IPA exported locally: $OUT/export"
else
  echo "Archive created: $ARCHIVE"
  echo "No IPA exported. Use Xcode Organizer or rerun with --export-options."
fi
