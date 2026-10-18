param([Parameter(Mandatory=$true)][ValidateSet('osrm','gdal','grass','earthengine','worldcover')][string]$Name)
$ErrorActionPreference = 'Stop'
$catalog = @{
  osrm = @{ repo='Project-OSRM/osrm-backend'; files=@('README.md','LICENSE.TXT','AGENTS.md','profiles/foot.lua','profiles/lib/way_handlers.lua','docs/profiles.md','docs/areas.md','docs/http.md','include/engine/api/route_parameters.hpp','src/engine/plugins/viaroute.cpp') }
  gdal = @{ repo='OSGeo/gdal'; files=@('README.md','LICENSE.TXT','AGENTS.md','apps/gdaldem_lib.cpp','apps/gdalwarp_lib.cpp','apps/gdal_rasterize_lib.cpp','alg/gdalwarper.cpp','alg/gdalrasterize.cpp') }
  grass = @{ repo='OSGeo/grass'; files=@('README.md','COPYING','AGENTS.md','raster/r.walk/main.c','raster/r.walk/r.walk.md','raster/r.walk/heap.c','raster/r.path/main.c') }
  earthengine = @{ repo='google/earthengine-api'; files=@('README.md','LICENSE','AGENTS.md','python/ee/batch.py','python/ee/image.py','python/ee/data.py','python/ee/tests/batch_test.py') }
  worldcover = @{ repo='ESA-WorldCover/esa-worldcover-datasets'; files=@('README.md','LICENSE','scripts/download.py','notebooks/utils/labels.py','notebooks/utils/stac.py') }
}
$entry = $catalog[$Name]
$commit = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot "metadata/$Name-commit.json") | ConvertFrom-Json
if ($commit.sha -notmatch '^[a-f0-9]{40}$') { throw 'Missing valid upstream commit' }
$destination = Join-Path $PSScriptRoot "source/$Name"
$results = @()
foreach ($relative in $entry.files) {
  $target = Join-Path $destination $relative
  New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null
  $url = "https://raw.githubusercontent.com/$($entry.repo)/$($commit.sha)/$relative"
  & curl.exe -sS -L --fail --connect-timeout 10 --max-time 25 $url -o $target
  $success = $LASTEXITCODE -eq 0
  $checksum = if ($success) { (Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash.ToLowerInvariant() } else { $null }
  $results += [PSCustomObject]@{ path=$relative; url=$url; downloaded=$success; sha256=$checksum }
  Write-Output "$Name/$relative : $success"
}
[PSCustomObject]@{ repository=$entry.repo; commit=$commit.sha; importedAt=[DateTime]::UtcNow.ToString('o'); scope='selected upstream source, not full clone or installed runtime'; files=$results } | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $destination 'manifest.json')
