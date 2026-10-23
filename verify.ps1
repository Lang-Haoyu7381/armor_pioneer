# Compiles and runs the verification tools in tools/ against the freshly built classes.
#
# Usage:  powershell -ExecutionPolicy Bypass -File verify.ps1

$ErrorActionPreference = 'Stop'

$root     = $PSScriptRoot
$mcRoot   = if ($env:MC_ROOT) { $env:MC_ROOT } else { 'E:\.minecraft' }
$libsRoot = Join-Path $mcRoot 'libraries'
$classDir = Join-Path $root 'build\classes'
$toolDir  = Join-Path $root 'build\tools'
# Same resolution as build.ps1, and it has to agree with it: the tools run against these classes and
# the pack files, and a tool that passed against a different build of the base mod than the game
# loads would be verifying a game nobody plays.
$instance = Join-Path $mcRoot 'versions\1.20.1-Forge_47.4.2'
$modJar   = if ($env:YWZJ_JAR) { $env:YWZJ_JAR } else {
    $found = Get-ChildItem (Join-Path $instance 'mods') -Filter 'ywzj_vehicle-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike 'ywzj_vehicle_armor*' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($found) { $found.FullName } else { 'E:\ywzj_vehicle-1.20.1-0.6.0.jar' }
}
# TACZ again, resolved the same way build.ps1 resolves it and for the same reason: the tools read the
# TACZ jar to check that every configured ammo id really exists in it, and a tool that passed against
# a different gun pack than the game loads would be verifying a game nobody plays.
$taczJar = if ($env:TACZ_JAR) { $env:TACZ_JAR } else {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $candidates = Get-ChildItem (Join-Path $instance 'mods') -Filter '*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match 'tacz' -and $_.Name -notlike '*mesh-loader*' } |
        Sort-Object LastWriteTime -Descending
    $picked = $null
    foreach ($candidate in $candidates) {
        $isTacz = $false
        try {
            $zip = [System.IO.Compression.ZipFile]::OpenRead($candidate.FullName)
            $isTacz = $null -ne $zip.GetEntry('com/tacz/guns/GunMod.class')
            $zip.Dispose()
        } catch {
            $isTacz = $false
        }
        if ($isTacz) { $picked = $candidate.FullName; break }
    }
    $picked
}
# -LiteralPath for the reason spelled out in build.ps1: the jar's name starts with [ ], which
# Test-Path reads as a wildcard character class.
if (-not $taczJar -or -not (Test-Path -LiteralPath $taczJar)) {
    throw ("No TACZ jar found under $(Join-Path $instance 'mods'). Set TACZ_JAR to point at one.")
}
Write-Host "TACZ      : $taczJar"

function Find-Jar([string]$leaf) {
    $hit = Get-ChildItem -Path $libsRoot -Recurse -Filter $leaf -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1
    if (-not $hit) { throw "Could not find $leaf under $libsRoot" }
    return $hit.FullName
}

$srgJar = (Get-ChildItem (Join-Path $libsRoot 'net\minecraft\client') -Recurse -Filter '*-srg.jar' |
                Where-Object { $_.FullName -match '1\.20\.1' } | Select-Object -First 1).FullName
$forgeJar = (Get-ChildItem (Join-Path $libsRoot 'net\minecraftforge\forge') -Recurse -Filter '*-universal.jar' |
                Where-Object { $_.FullName -match '1\.20\.1' } | Sort-Object FullName -Descending | Select-Object -First 1).FullName

$classpath = @(
    $classDir, $srgJar, $forgeJar, $modJar, $taczJar,
    (Find-Jar 'joml-1.10.5.jar'), (Find-Jar 'gson-2.10.1.jar'),
    (Find-Jar 'mixin-0.8.5.jar'), (Find-Jar 'eventbus-6.2.33.jar'),
    (Find-Jar 'slf4j-api-2.0.1.jar'), (Find-Jar 'brigadier-1.1.8.jar'),
    (Find-Jar 'forgespi-3.2.0.jar'),
    # Vec3's static initialiser needs Codec, which in turn needs fastutil; both have to be on
    # the *runtime* path even though nothing here is compiled against them directly.
    (Find-Jar 'datafixerupper-*.jar'), (Find-Jar 'fastutil-*.jar'),
    # And building a chat Component - which FordingCheck does, to read the translation keys off the
    # messages the mod itself builds - initialises ExtraCodecs, which names authlib's Property. Same
    # rule again: nothing is compiled against it, but the class has to be there to initialise.
    # Pinned, because the machine carries authlib for several Minecraft versions and the newest is
    # not the one 1.20.1 runs on.
    (Find-Jar 'authlib-4.0.43.jar'),
    # GunnerCheck builds a real FriendlyByteBuf, whose read/write methods come from netty's ByteBuf
    # and whose internals name netty-codec's exception types and guava's collections.
    (Find-Jar 'netty-buffer-4.1.82.Final.jar'), (Find-Jar 'netty-common-4.1.82.Final.jar'),
    (Find-Jar 'netty-codec-4.1.82.Final.jar'), (Find-Jar 'guava-31.1-jre.jar')
) -join ';'

New-Item -ItemType Directory -Force $toolDir | Out-Null

$tools = Get-ChildItem (Join-Path $root 'tools') -Filter *.java | Select-Object -ExpandProperty FullName
Write-Host "Compiling $($tools.Count) tool(s)..."
# As in build.ps1: javac's notes go to stderr, and at the Stop preference PowerShell 5.1 treats a
# note as a terminating error. Only the exit code means anything.
$ErrorActionPreference = 'Continue'
& javac -encoding UTF-8 --release 17 -nowarn -proc:none -classpath $classpath -d $toolDir $tools
$javacCode = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($javacCode -ne 0) { throw "javac failed with exit code $javacCode" }

# VehicleScaffold runs in its default mode, which re-derives the geometry of the three shipped
# vehicles from the pack and compares it with the config. It is here because it is the one tool that
# reads the vehicle pack's structure files, and a pack update that moved a bone would otherwise show
# up only as a gun mount in the wrong place on a vehicle adapted months later.
$failed = @()
foreach ($tool in @('MixinCheck', 'TaczCheck', 'PenetrationTable', 'FaceProbe', 'WheelProbe', 'UpgradeCheck', 'GunnerCheck', 'CrewCheck', 'FordingCheck', 'SuspensionCheck', 'AmmoCompendium', 'VehicleScaffold', 'ConfigDump')) {
    Write-Host ""
    Write-Host "=== $tool ===" -ForegroundColor Cyan
    # Same reason again: a tool that writes a line to stderr and still exits zero has passed, and
    # the JVM itself is given to doing so.
    $ErrorActionPreference = 'Continue'
    & java -classpath "$toolDir;$classpath" $tool
    $code = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    # Recorded rather than thrown on the spot: a tool that dies before printing anything is the one
    # worth reading least of all, and stopping here hid every other tool's verdict behind it. All of
    # them run, and the run still fails if any did.
    if ($code -ne 0) { $failed += $tool }
}
if ($failed.Count -gt 0) { throw "these tool(s) failed: $($failed -join ', ')" }
Write-Host ""
Write-Host "all $($tools.Count) tool(s) passed" -ForegroundColor Green
