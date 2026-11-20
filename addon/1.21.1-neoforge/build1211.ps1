# Builds the armour addon for Minecraft 1.21.1 / NeoForge 21.1.x without Gradle.
#
# Same idea as the 1.20.1 build.ps1: compile straight against the jars a normal NeoForge
# installation already has. Two differences matter:
#
#   * Java 21, not 17. NeoForge 21.1 is Java 21 only, and --release 17 cannot see records and
#     sealed types the platform's own APIs now use.
#   * The 1.21.1 runtime jar is Mojang-named throughout. The "srg" in its filename is a leftover
#     of the toolchain, not a description: javap on it prints Vec3.x, not f_82479_.
#
# The base mod is the jar the launcher hands NeoForge, resolved the same way as in 1.20.1 -
# ywzj_vehicle-1.21.1-*.jar, newest first, never the armour addon's own jar.
#
# Usage:  powershell -ExecutionPolicy Bypass -File build1211.ps1

$ErrorActionPreference = 'Stop'

$root     = $PSScriptRoot
$mcRoot   = if ($env:MC_ROOT) { $env:MC_ROOT } else { 'E:\.minecraft' }
$libsRoot = Join-Path $mcRoot 'libraries'
$instance = Join-Path $mcRoot 'versions\1.21.1-NeoForge_21.1.230'
$buildDir = Join-Path $root 'build1211'
$classDir = Join-Path $buildDir 'classes'
$libsOut  = Join-Path $buildDir 'libs'

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

# Everything else comes from the version json, so the classpath is the launcher's own list
# rather than a hand-copied one that drifts when NeoForge is updated.
$versionJson = Get-Content (Join-Path $instance '1.21.1-NeoForge_21.1.230.json') -Raw | ConvertFrom-Json
$jsonJars = @()
foreach ($lib in $versionJson.libraries) {
    $rel = $lib.downloads.artifact.path
    if (-not $rel) { continue }
    $full = Join-Path $libsRoot ($rel -replace '/', '\')
    if (Test-Path -LiteralPath $full) { $jsonJars += $full }
}

foreach ($required in @($srgJar, $modJar, $mixinJar)) {
    if (-not $required -or -not (Test-Path -LiteralPath $required)) { throw "Missing required jar: $required" }
}

Write-Host "Minecraft : $srgJar"
Write-Host "NeoForge  : $($neoJars -join ', ')"
Write-Host "Vehicle   : $modJar"
# Deliberately not `java -version`: a native command's stderr becomes a terminating error under
# $ErrorActionPreference='Stop' in PowerShell 5.1, and the build would die before compiling.

$classpath = (@($srgJar, $modJar, $mixinJar) + $neoJars + $jsonJars | Select-Object -Unique) -join ';'

Remove-Item $classDir, $libsOut -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $classDir, $libsOut | Out-Null

$sources = Get-ChildItem (Join-Path $root 'src\main\java') -Recurse -Filter *.java |
    Select-Object -ExpandProperty FullName
Write-Host "Compiling $($sources.Count) source file(s)..."

# Native stderr becomes a terminating error under Stop in PowerShell 5.1, so the exit code is
# the verdict and the notes are not - same reason as build.ps1.
$ErrorActionPreference = 'Continue'
# English diagnostics: this console is a Chinese-locale GBK one, so javac's native output arrives
# mojibaked and unparseable. The locale flags change the message text, not what is compiled.
& javac "-J-Duser.language=en" "-J-Duser.country=US" -encoding UTF-8 --release 21 -nowarn -proc:none -classpath $classpath -d $classDir $sources
$javacCode = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($javacCode -ne 0) { throw "javac failed with exit code $javacCode" }

# Copied file by file rather than with a wildcard, so the editor backups that live beside the real
# resources (*.json.before_*) can be left out. They are not load errors - the game ignores files it
# has no use for - but a jar should not carry three copies of its own lang file.
$resources = Join-Path $root 'src\main\resources'
Get-ChildItem $resources -Recurse -File | Where-Object { $_.Name -notlike '*.bak' } | ForEach-Object {
    $relative = $_.FullName.Substring($resources.Length + 1)
    $destination = Join-Path $classDir $relative
    New-Item -ItemType Directory -Force (Split-Path $destination) | Out-Null
    Copy-Item $_.FullName $destination -Force
}

$outJar = Join-Path $libsOut 'ywzj_vehicle_armor-2.0.2-1.21.1.jar'
& jar --create --file $outJar -C $classDir .
if ($LASTEXITCODE -ne 0) { throw "jar failed with exit code $LASTEXITCODE" }

Write-Host ""
Write-Host "Built $outJar" -ForegroundColor Green
