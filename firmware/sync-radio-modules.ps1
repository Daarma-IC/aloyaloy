# Sync canonical shared UART protocol and main modules into self-contained sketches.
# Existing SF configs and all .ino files are deliberately preserved.
$ErrorActionPreference = 'Stop'
$radioFirmwareRoot = $PSScriptRoot
$canonicalWire = Join-Path $radioFirmwareRoot 'NusaNode/src/protocol/nusa_duplex_wire.h'
$companionProtocol = Join-Path $radioFirmwareRoot 'NusaRxCompanion/src/protocol'
New-Item -ItemType Directory -Force -Path $companionProtocol | Out-Null
Copy-Item -LiteralPath $canonicalWire -Destination (Join-Path $companionProtocol 'nusa_duplex_wire.h') -Force
foreach ($variant in (Get-ChildItem (Join-Path $radioFirmwareRoot 'sf-variants') -Directory)) {
    Copy-Item -LiteralPath (Join-Path $radioFirmwareRoot 'NusaNode/src') -Destination $variant.FullName -Recurse -Force
}
Write-Output 'Synced protocol and SF modules; INO/config files preserved.'
