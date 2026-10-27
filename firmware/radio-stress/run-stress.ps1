param(
    [Parameter(Mandatory=$true)][string]$TxPort,
    [Parameter(Mandatory=$true)][string]$RxPort,
    [ValidateRange(1,4096)][int]$Count = 100,
    [ValidateRange(0,60000)][int]$IntervalMs = 400,
    [ValidateRange(16,240)][int]$PacketBytes = 200,
    [ValidateRange(0,300000)][int]$TimeoutSeconds = 0
)
$ErrorActionPreference = 'Stop'
if ($TxPort -eq $RxPort) { throw 'TX and RX must use different serial ports.' }
if (!$TimeoutSeconds) {
    $TimeoutSeconds = [int][Math]::Ceiling(($Count * [Math]::Max($IntervalMs, 500)) / 1000.0) + 30
}
$resultDir = Join-Path $PSScriptRoot ('results/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Force -Path $resultDir | Out-Null
$utf8 = New-Object System.Text.UTF8Encoding($false)
$txSerial = New-Object System.IO.Ports.SerialPort($TxPort,115200,'None',8,'One')
$rxSerial = New-Object System.IO.Ports.SerialPort($RxPort,115200,'None',8,'One')
$streams = @(
    [pscustomobject]@{ Name='TX'; Port=$txSerial; File=(Join-Path $resultDir 'tx.log'); Text='' },
    [pscustomobject]@{ Name='RX'; Port=$rxSerial; File=(Join-Path $resultDir 'rx.log'); Text='' }
)
function Pump-Serial {
    foreach ($stream in $streams) {
        $chunk = $stream.Port.ReadExisting()
        if ($chunk) {
            $stream.Text += $chunk
            [System.IO.File]::AppendAllText($stream.File, $chunk, $utf8)
            Write-Host ($stream.Name + ': ' + $chunk.TrimEnd())
        }
    }
}
$metadata = [ordered]@{
    startedAt=(Get-Date -Format o); txPort=$TxPort; rxPort=$RxPort;
    count=$Count; intervalMs=$IntervalMs; packetBytes=$PacketBytes;
    timeoutSeconds=$TimeoutSeconds; runId=$null; completed=$false
}
try {
    foreach ($stream in $streams) {
        $stream.Port.NewLine = "`n"
        $stream.Port.DtrEnable = $false
        $stream.Port.RtsEnable = $false
        $stream.Port.Open()
    }
    # Ask firmware to identify roles; do not start RF with unknown firmware.
    $identify = [Diagnostics.Stopwatch]::StartNew()
    while ($identify.Elapsed.TotalSeconds -lt 8) {
        $txSerial.WriteLine('status'); $rxSerial.WriteLine('status')
        Start-Sleep -Milliseconds 200
        Pump-Serial
        if ($streams[0].Text -match '\[STATUS\] TX run=' -and
            $streams[1].Text -match '\[STATUS\] RX run=') { break }
    }
    if ($streams[0].Text -notmatch '\[STATUS\] TX run=' -or
        $streams[1].Text -notmatch '\[STATUS\] RX run=') {
        throw 'Roles not confirmed. Check flashed sketch/role and close Arduino Serial Monitor.'
    }
    $txSerial.WriteLine('stop')
    Start-Sleep -Milliseconds 2200 # Let any previous in-flight SF7 packet finish.
    Pump-Serial
    $rxSerial.WriteLine('reset')
    Start-Sleep -Milliseconds 200
    Pump-Serial
    $streams[0].Text = ''; $streams[1].Text = ''
    $txSerial.WriteLine("run $Count $IntervalMs $PacketBytes")
    $timer = [Diagnostics.Stopwatch]::StartNew()
    while ($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        Pump-Serial
        $runMatch = [regex]::Match($streams[0].Text, '\[RUN\] run=(\d+)')
        if ($runMatch.Success) {
            $metadata.runId = $runMatch.Groups[1].Value
            $donePattern = '\[DONE\] TX run=' + $metadata.runId + '\s'
            if ($streams[0].Text -match $donePattern) { $metadata.completed = $true; break }
        }
        Start-Sleep -Milliseconds 20
    }
    if (!$metadata.completed) { throw 'Run timed out. Partial logs preserved; TX will be stopped.' }
    Start-Sleep -Milliseconds 1200
    $txSerial.WriteLine('status'); $rxSerial.WriteLine('status')
    Start-Sleep -Milliseconds 300
    Pump-Serial
    Write-Host "Run completed. Compare matching run IDs in $resultDir"
} finally {
    if ($txSerial.IsOpen) {
        # Best effort stop on timeout, Ctrl+C or an exception.
        try { $txSerial.WriteLine('stop') } catch { }
    }
    foreach ($stream in $streams) {
        if ($stream.Port.IsOpen) { $stream.Port.Close() }
        $stream.Port.Dispose()
    }
    $metadata['finishedAt'] = Get-Date -Format o
    [System.IO.File]::WriteAllText((Join-Path $resultDir 'run.json'),
        ($metadata | ConvertTo-Json), $utf8)
}
