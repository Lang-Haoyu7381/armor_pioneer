# Adapts a vehicle from the pack to the armour system.
#
# The measuring that used to be done by eye - which part units count as the turret, where the road
# wheels stop, where the track's inner edge is, how far the gun sits off the turret box's centre -
# is read out of the pack's own structure file, and the armour config to paste is printed with those
# numbers already in it.
#
# Nothing here writes to the config. It prints a block, and the block is parsed back through the
# addon's own reader before it is shown, so what comes out is known to be loadable. What the tool
# cannot know - how thick the armour is - is left at zero, deliberately: an entry that looks
# finished is one nobody finishes.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File add_vehicle.ps1                    # list every vehicle
#   powershell -ExecutionPolicy Bypass -File add_vehicle.ps1 t72_b3m            # scaffold one
#   powershell -ExecutionPolicy Bypass -File add_vehicle.ps1 check              # verify the tool
#
# The pack is found from VVP_PACK, or from the default path below.

$ErrorActionPreference = 'Stop'

$root     = $PSScriptRoot
$mcRoot   = if ($env:MC_ROOT) { $env:MC_ROOT } else { 'E:\.minecraft' }
$libsRoot = Join-Path $mcRoot 'libraries'
$classDir = Join-Path $root 'build\classes'
$toolDir  = Join-Path $root 'build\tools'
$modJar   = if ($env:YWZJ_JAR) { $env:YWZJ_JAR } else { 'E:\ywzj_vehicle-1.20.1-0.6.0.jar' }

function Find-Jar([string]$leaf) {
    $hit = Get-ChildItem -Path $libsRoot -Recurse -Filter $leaf -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1
    if (-not $hit) { throw "Could not find $leaf under $libsRoot" }
    return $hit.FullName
}

if (-not (Test-Path $classDir)) {
    throw "$classDir is missing - run build.ps1 first"
}

$srgJar = (Get-ChildItem (Join-Path $libsRoot 'net\minecraft\client') -Recurse -Filter '*-srg.jar' |
                Where-Object { $_.FullName -match '1\.20\.1' } | Select-Object -First 1).FullName
$forgeJar = (Get-ChildItem (Join-Path $libsRoot 'net\minecraftforge\forge') -Recurse -Filter '*-universal.jar' |
                Where-Object { $_.FullName -match '1\.20\.1' } | Sort-Object FullName -Descending | Select-Object -First 1).FullName

$classpath = @(
    $classDir, $srgJar, $forgeJar, $modJar,
    (Find-Jar 'joml-1.10.5.jar'), (Find-Jar 'gson-2.10.1.jar'),
    (Find-Jar 'mixin-0.8.5.jar'), (Find-Jar 'eventbus-6.2.33.jar'),
    (Find-Jar 'slf4j-api-2.0.1.jar'), (Find-Jar 'brigadier-1.1.8.jar'),
    (Find-Jar 'forgespi-3.2.0.jar'),
    (Find-Jar 'datafixerupper-*.jar'), (Find-Jar 'fastutil-*.jar')
) -join ';'

# The tool is compiled into build\tools by verify.ps1. Compiled here too, so that adapting a vehicle
# does not first require remembering to run the whole verification suite.
New-Item -ItemType Directory -Force $toolDir | Out-Null
$tool = Join-Path $root 'tools\VehicleScaffold.java'
& javac -encoding UTF-8 --release 17 -nowarn -proc:none -classpath $classpath -d $toolDir $tool
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

# Cast to [string[]] rather than assigned loosely. A one-element array collapses to a bare string on
# assignment, and splatting a string hands PowerShell the string's *characters* - so a single
# argument would arrive at Java as one argument per letter, and the tool would be asked to scaffold
# a vehicle called "t".
[string[]]$arguments = if ($args.Count -gt 0) { $args } else { @('list') }

# The worksheet is Chinese, and a Windows console defaults to a code page that cannot represent it:
# Java would write UTF-8 and the terminal would show mojibake, which for a page of tables is the same
# as printing nothing. Both ends are pinned - the console switched to UTF-8 for the duration, and
# Java told explicitly what to write - and the console is put back afterwards, because the encoding
# is process-wide and would otherwise leak into whatever the user runs next in the same window.
# Save-and-restore is guarded because setting the encoding throws when stdout is redirected, and
# redirecting the worksheet into a file is the ordinary way to keep one. A failure to switch the
# console is not a reason to refuse to print the page.
$consoleEncoding = $null
try {
    $consoleEncoding = [Console]::OutputEncoding
    [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
} catch {
    $consoleEncoding = $null
}
try {
    # Quoted: PowerShell re-quotes arguments to native commands itself, and an unquoted
    # -Dname=value arrives at Java split at the dot, so the JVM is handed a main class of
    # ".encoding=UTF-8" instead of a system property.
    & java "-Dstdout.encoding=UTF-8" "-Dfile.encoding=UTF-8" -classpath "$toolDir;$classpath" VehicleScaffold @arguments
    $code = $LASTEXITCODE
} finally {
    if ($consoleEncoding) {
        try { [Console]::OutputEncoding = $consoleEncoding } catch { }
    }
}
exit $code
