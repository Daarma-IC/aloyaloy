param([string]$Compiler)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (!$Compiler) { $Compiler = [IO.Path]::GetFullPath((Join-Path $root '../../firmware/build/tools/zig-x86_64-windows-0.15.2/zig.exe')) }
if (!(Test-Path -LiteralPath $Compiler)) { throw 'Provide -Compiler with zig.exe; no compiler is downloaded automatically.' }
$src = Join-Path $root 'third_party/codec2/src'
$generated = Join-Path $root 'composeApp/src/nativeInterop/codec2-generated'
$bridge = Join-Path $root 'composeApp/src/nativeInterop/codec2'
$output = Join-Path $root 'composeApp/build/codec2-host-tests'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$sources = @('codec2','codec2_fft','dump','interp','kiss_fft','kiss_fftr','lpc','lsp','mbest','nlp','newamp1','pack','phase','postfilter','quantise','sine') | ForEach-Object { Join-Path $src ($_+'.c') }
$sources += @('codebook','codebookd','codebookjmv','codebookge','codebooknewamp1','codebooknewamp1_energy') | ForEach-Object { Join-Path $generated ($_+'.c') }
$sources += (Join-Path $bridge 'codec2_bridge.c'), (Join-Path $PSScriptRoot 'codec2_bridge_test.c')
$exe = Join-Path $output 'codec2-test.exe'
& $Compiler cc -O1 -DCODEC2_MODE_EN_DEFAULT=0 -DCODEC2_MODE_3200_EN=1 -DCODEC2_MODE_1200_EN=1 ('-I'+$src) ('-I'+$bridge) ('-I'+(Join-Path $root 'composeApp/src/androidMain/cpp/include')) @sources -o $exe
if ($LASTEXITCODE -ne 0) { throw 'Codec2 host compilation failed' }
& $exe
if ($LASTEXITCODE -ne 0) { throw 'Codec2 host tests failed' }
