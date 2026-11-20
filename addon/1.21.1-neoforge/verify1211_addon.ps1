# Compiles and runs one tool (tools/LeopardAddonCheck.java) against the 1.21.1 build.
# Same classpath construction as build1211.ps1, so the tool runs against the exact classes the
# game loads.
#
# Usage: powershell -ExecutionPolicy Bypass -File verify1211_addon.ps1

$ErrorActionPreference = 'Stop'

$root     = $PSScriptRoot
$mcRoot   = if ($env:MC_ROOT) { $env:MC_ROOT } else { 'E:\.minecraft' }
$libsRoot = Join-Path $mcRoot 'libraries'
$instance = Join-Path $mcRoot 'versions\1.21.1-NeoForge_21.1.230'
$classDir = Join-Path $root 'build1211\classes'
$toolDir  = Join-Path $root 'build1211\tools'

$modJar = if ($env:YWZJ_JAR) { $env:YWZJ_JAR } else {
    $found = Get-ChildItem (Join-Path $instance 'mods') -Filter 'ywzj_vehicle-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike 'ywzj_vehicle_armor*' } |
        Where-Object { $_.Name -like '*-1.21*' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($found) { $found.FullName } else { throw "No ywzj_vehicle jar for 1.21.1 under $instance\mods" }
}
$srgJar = (Get-ChildItem (Join-Path $libsRoot 'net\minecraft\client') -Recurse -Filter '*-srg.jar' |
            Where-Object { $_.FullName -match '1\.21\.1' } | Select-Object -First 1).FullName
$neoDir = Join-Path $libsRoot 'net\neoforged\neoforge\21.1.230'
$neoJars = Get-ChildItem $neoDir -Filter *.jar | Select-Object -ExpandProperty FullName
$mixinJar = (Get-ChildItem (Join-Path $libsRoot 'net\fabricmc\sponge-mixin') -Recurse -Filter *.jar | Select-Object -First 1).FullName

$versionJson = Get-Content (Join-Path $instance '1.21.1-NeoForge_21.1.230.json') -Raw | ConvertFrom-Json
$jsonJars = @()
foreach ($lib in $versionJson.libraries) {
    $rel = $lib.downloads.artifact.path
    if (-not $rel) { continue }
    $full = Join-Path $libsRoot ($rel -replace '/', '\')
    if (Test-Path -LiteralPath $full) { $jsonJars += $full }
}
$cp = (@($classDir) + @($srgJar, $modJar, $mixinJar) + $neoJars + $jsonJars |
        Select-Object -Unique) -join ';'

New-Item -ItemType Directory -Force $toolDir | Out-Null
$source = Join-Path $root 'tools\LeopardAddonCheck.java'
# AircraftCheck reads the same pack and does not name a Minecraft class; PackLocator is its
# pack finder and is shared with the 1.20.1 tree, with the instance taken from MC_INSTANCE.
$aircraftSource = Join-Path $root 'tools\AircraftCheck.java'
$locatorSource = Join-Path $root 'tools\PackLocator.java'
# The engine table covers both armour_pioneer and tank_assault, and reads its two packs through
# PackLocator; platform-independent apart from the ArmorConfig fields.
$engineSource = Join-Path $root 'tools\EngineCheck.java'

$ErrorActionPreference = 'Continue'
& javac "-J-Duser.language=en" "-J-Duser.country=US" -encoding UTF-8 --release 21 -nowarn -proc:none `
    -classpath $cp -d $toolDir $source, $aircraftSource, $locatorSource, $engineSource
$javacCode = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($javacCode -ne 0) { throw "javac failed with exit code $javacCode" }

# The tool reads its config and its pack from this instance; without it, it looks in the
# 1.20.1 install.
$env:MC_INSTANCE = '1.21.1-NeoForge_21.1.230'

$ErrorActionPreference = 'Continue'
& java -classpath "$toolDir;$cp" LeopardAddonCheck
$code = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($code -ne 0) { throw "LeopardAddonCheck failed with exit code $code" }

# The aircraft model is a box table read off the pack; the same check the 1.20.1 tree runs.
$ErrorActionPreference = 'Continue'
& java -classpath "$toolDir;$cp" AircraftCheck
$code = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($code -ne 0) { throw "AircraftCheck failed with exit code $code" }

# The engine ratings and hp/t, held against the published pairs for both packs.
$ErrorActionPreference = 'Continue'
& java -classpath "$toolDir;$cp" EngineCheck
$code = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($code -ne 0) { throw "EngineCheck failed with exit code $code" }
