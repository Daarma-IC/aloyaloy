param(
    [ValidateRange(1,2147483647)][long]$DataBytes = 1512,
    [ValidateRange(7,12)][int]$SF = 7,
    [ValidateSet(125,250,500)][int]$BandwidthKhz = 125,
    [ValidateRange(5,8)][int]$CodingRate = 5,
    [ValidateRange(0.001,1.0)][double]$DutyRatio = 0.01,
    [ValidateRange(0,60000)][int]$RxWindowMs = 500
)
# Mathematical model, not a measured ETA. NusaFrame=12 B; PHY explicit header,
# CRC enabled; preamble=8. Excludes app/encryption/transfer headers and retries.
$ErrorActionPreference = 'Stop'
function Get-FrameAirMs([int]$PayloadBytes) {
    $symbolMs = [Math]::Pow(2,$SF) / $BandwidthKhz
    $de = if ($symbolMs -gt 16) { 1 } else { 0 }
    $payloadSymbols = 8 + [Math]::Max(0,
        [Math]::Ceiling((8*$PayloadBytes - 4*$SF + 28 + 16) / (4*($SF - 2*$de)))) * $CodingRate
    return (8 + 4.25 + $payloadSymbols) * $symbolMs
}
$fullAir = Get-FrameAirMs 255
$fullFrames = [Math]::Floor($DataBytes / 243.0)
$tail = $DataBytes % 243
$frames = $fullFrames + $(if ($tail) { 1 } else { 0 })
$air = $fullFrames * $fullAir
if ($tail) { $air += Get-FrameAirMs ($tail + 12) }
# Match current quasi implementation's minimum full-frame receive opportunity.
$receiveWindow = [Math]::Max($RxWindowMs,[Math]::Floor($fullAir + 20))
$quasiFloor = $air + $frames*$receiveWindow
$dutyUpper = 243000/$fullAir*$DutyRatio
$quasiUpper = 243000/($fullAir+$receiveWindow)
[pscustomobject][ordered]@{
    DataBytes = $DataBytes
    SpreadingFactor = $SF
    BandwidthKhz = $BandwidthKhz
    CodingRate = "4/$CodingRate"
    Frames = [long]$frames
    FullFrameAirMs = [Math]::Round($fullAir,3)
    PureTxSeconds = [Math]::Round($air/1000,6)
    QuasiLowerBoundSeconds = [Math]::Round($quasiFloor/1000,6)
    ReceiveWindowPerFrameMs = $receiveWindow
    DutyRatio = $DutyRatio
    AirtimeBudgetEquivalentSeconds = [Math]::Round($air/$DutyRatio/1000,6)
    SustainedPayloadUpperBytesPerSecond = [Math]::Round([Math]::Min($dutyUpper,$quasiUpper),3)
    Notes = 'Lower bound, not end-to-end ETA. Excludes app headers/encryption, BLE pacing, jitter/CAD, UART, retry, hops, playback; initial burst credit can affect completion time. DutyRatio is a calculation input, not a legal permission.'
}
