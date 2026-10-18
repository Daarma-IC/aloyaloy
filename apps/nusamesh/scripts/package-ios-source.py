"""Package the current app source for a Mac without caches, keys or build artifacts."""
from pathlib import Path
import hashlib
import json
import zipfile

root = Path(__file__).resolve().parents[1]
version = dict(line.split("=", 1) for line in (root / "version.properties").read_text().splitlines() if "=" in line)
output = root / "releases" / ("v" + version["versionName"])
output.mkdir(parents=True, exist_ok=True)
archive = output / ("NusaMesh-v" + version["versionName"] + "-ios-source.zip")
folders = ["composeApp/src", "iosApp/iosApp", "iosApp/Configuration", "gradle", "scripts", "third_party/codec2/src"]
files = [".gitignore", ".gitattributes", "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "version.properties",
         "gradlew", "gradlew.bat", "composeApp/build.gradle.kts", "iosApp/project.yml", "docs/RELEASE.md"]
for name in ("COPYING", "LICENSE", "README.md"):
    if (root / "third_party/codec2" / name).is_file():
        files.append("third_party/codec2/" + name)
selected = {root / name for name in files}
for folder in folders:
    selected.update(p for p in (root / folder).rglob("*") if p.is_file())
excluded = {".git", "build", ".gradle", "DerivedData", "__pycache__"}
with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as package:
    package.writestr("SOURCE-PACKAGE-README.txt",
                    "NusaMesh iOS SOURCE PACKAGE - this is NOT an IPA.\n"
                    "Extract on a Mac and follow docs/RELEASE.md.\n"
                    "Native Codec2 source is included; no private signing credentials are included.\n"
                    "iOS compilation, signing and device verification have not run on the Windows packaging host.\n")
    for path in sorted(selected):
        relative = path.relative_to(root)
        if any(part in excluded for part in relative.parts):
            continue
        if path.suffix.lower() in {".jks", ".keystore", ".p12", ".mobileprovision", ".clixml"}:
            continue
        if path.name in {"Signing.xcconfig", "local.properties", "keystore.properties"}:
            continue
        if path.name == "gradlew" or path.suffix == ".sh":
            info = zipfile.ZipInfo(relative.as_posix())
            info.create_system = 3
            info.external_attr = 0o100755 << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            package.writestr(info, path.read_bytes().replace(b"\r\n", b"\n"))
        else:
            package.write(path, relative.as_posix())
manifest = {
    "version": version["versionName"], "versionCode": int(version["versionCode"]),
    "platform": "ios", "artifactType": "source-package", "ipaBuilt": False,
    "file": archive.name, "sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
    "requires": ["macOS", "Xcode", "XcodeGen", "Apple signing identity for distribution"],
}
(output / "ios-source-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
print(json.dumps(manifest, indent=2))
