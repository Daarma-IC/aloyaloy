param([string]$Compiler)
$ErrorActionPreference = 'Stop'
if (!$Compiler) {
    $zigCandidate = Join-Path $PSScriptRoot '../build/tools/zig-x86_64-windows-0.15.2/zig.exe'
    if (Test-Path $zigCandidate) { $Compiler = (Resolve-Path $zigCandidate).Path }
    elseif (Get-Command g++ -ErrorAction SilentlyContinue) { $Compiler = (Get-Command g++).Source }
    else { throw 'Specify -Compiler with g++.exe or zig.exe. Compiler is not installed automatically.' }
}
$testOutput = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../build/host-tests'))
New-Item -ItemType Directory -Force -Path $testOutput | Out-Null
$env:ZIG_GLOBAL_CACHE_DIR = Join-Path $testOutput 'zig-global-cache'
$env:ZIG_LOCAL_CACHE_DIR = Join-Path $testOutput 'zig-local-cache'
$compilerPrefix = @()
if ([IO.Path]::GetFileName($Compiler) -eq 'zig.exe') { $compilerPrefix = @('c++') }
$sharedFlags = @('-std=c++17', '-pthread', '-Wall', '-Wextra', '-DLORA_QUASI_TAKEOVER=0',
    ('-I' + (Join-Path $PSScriptRoot 'stubs')),
    ('-I' + (Join-Path $PSScriptRoot '../NusaNode')))
$radio = Join-Path $PSScriptRoot '../NusaNode/src/lora/nusa_radio.cpp'
$receiver = Join-Path $PSScriptRoot '../NusaNode/src/lora/nusa_receiver.cpp'
$cases = @(
    @{Name='duplex-wire'; Defines=@(); Sources=@('duplex_wire_test.cpp')},
    @{Name='duplex-uart'; Defines=@('-DLORA_FULL_DUPLEX=1','-DLORA_RX_BACKEND=1'); Sources=@('duplex_radio_test.cpp',$radio,$receiver)},
    @{Name='duplex-spi'; Defines=@('-DLORA_FULL_DUPLEX=1','-DLORA_RX_BACKEND=2'); Sources=@('duplex_spi_test.cpp',$radio,$receiver)},
    @{Name='quasi-uart'; Defines=@('-DLORA_FULL_DUPLEX=0','-DLORA_QUASI_DUPLEX=1','-DLORA_RX_BACKEND=1'); Sources=@('quasi_radio_test.cpp',$radio,$receiver)},
    @{Name='quasi-spi'; Defines=@('-DLORA_FULL_DUPLEX=0','-DLORA_QUASI_DUPLEX=1','-DLORA_RX_BACKEND=2'); Sources=@('duplex_spi_test.cpp',$radio,$receiver)},
    @{Name='takeover-uart'; Defines=@('-ULORA_QUASI_TAKEOVER','-DLORA_QUASI_TAKEOVER=1'); Sources=@('takeover_radio_test.cpp',$radio,$receiver)},
    @{Name='takeover-spi'; Defines=@('-ULORA_QUASI_TAKEOVER','-DLORA_QUASI_TAKEOVER=1','-DLORA_RX_BACKEND=2'); Sources=@('takeover_spi_test.cpp',$radio,$receiver)},
    @{Name='admission'; Defines=@('-DLORA_QUASI_DUPLEX=0'); Sources=@('admission_test.cpp',(Join-Path $PSScriptRoot '../NusaNode/src/protocol/nusa_admission.cpp'),$radio)},
    @{Name='audio-half'; Defines=@('-DLORA_QUASI_DUPLEX=0'); Sources=@('audio_transport_test.cpp',(Join-Path $PSScriptRoot '../NusaNode/src/lora/nusa_fragment.cpp'),$radio)},
    @{Name='scheduler'; Defines=@('-DLORA_QUASI_DUPLEX=0'); Sources=@('scheduler_test.cpp',$radio)},
    @{Name='mobility'; Defines=@(); Sources=@('mobility_test.cpp',(Join-Path $PSScriptRoot '../NusaNode/src/routing/nusa_mobility.cpp'))}
)
foreach ($case in $cases) {
    $sources = @($case.Sources | ForEach-Object { if ([IO.Path]::IsPathRooted($_)) { $_ } else { Join-Path $PSScriptRoot $_ } })
    $exe = Join-Path $testOutput ($case.Name + '.exe')
    & $Compiler @compilerPrefix @sharedFlags @($case.Defines) @sources -o $exe
    if ($LASTEXITCODE -ne 0) { throw ('Compile failed: ' + $case.Name) }
    & $exe
    if ($LASTEXITCODE -ne 0) { throw ('Test failed: ' + $case.Name) }
}
Write-Output 'PASS: all software test suites. Hardware RF behavior remains unverified.'
