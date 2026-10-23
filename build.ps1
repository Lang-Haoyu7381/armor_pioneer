# Builds the T-64 armour addon without Gradle.
#
# It compiles straight against the SRG-named Minecraft jar and the Forge universal jar
# that a normal Forge 1.20.1 installation already has in .minecraft/libraries, so no
# ForgeGradle setup or Minecraft decompile step is needed.
#
# Usage:  powershell -ExecutionPolicy Bypass -File build.ps1

$ErrorActionPreference = 'Stop'

$root     = $PSScriptRoot
$mcRoot   = if ($env:MC_ROOT) { $env:MC_ROOT } else { 'E:\.minecraft' }
$libsRoot = Join-Path $mcRoot 'libraries'
$buildDir = Join-Path $root 'build'
$classDir = Join-Path $buildDir 'classes'
$libsOut  = Join-Path $buildDir 'libs'
# The base mod jar to compile against - and it has to be the one the game loads, not a copy of it.
#
# javac checks every method call in this addon against this classpath, and that is the only thing
# checking them: a mixin's @Shadow fields and @Inject targets are resolved by Mixin at load time, but
# the calls inside the injected method are resolved HERE. Compile against a different build of the
# same version and the build is clean, the game starts, and the first tick throws NoSuchMethodError.
# That is exactly what two jars both calling themselves 0.6.0 did — so the default is now "the newest
# ywzj_vehicle jar in the instance's mods folder", which is what the launcher hands to Forge.
$instance = Join-Path $mcRoot 'versions\1.20.1-Forge_47.4.2'
$modJar   = if ($env:YWZJ_JAR) { $env:YWZJ_JAR } else {
    $found = Get-ChildItem (Join-Path $instance 'mods') -Filter 'ywzj_vehicle-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike 'ywzj_vehicle_armor*' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($found) { $found.FullName } else { 'E:\ywzj_vehicle-1.20.1-0.6.0.jar' }
}

# TACZ (Timeless and Classics Zero), the gun mod whose bullets this addon now rates in millimetres.
#
# Compile-time only. It is NOT a runtime dependency: the addon loads and plays without it, because
# every reference to com.tacz lives in one class that checks ModList first - see TaczBridge - and
# the tool suite holds that to a constant-pool check that fails if any other class names the mod.
# So this jar is here for javac and for nothing else.
#
# Two jars in mods/ carry the prefix: the gun mod itself and tacz-mesh-loader, an unrelated project
# that happens to start with the same four letters. A name filter alone would pick whichever was
# written last, so the choice is confirmed by opening the jar and looking for the one class the
# compile actually needs. That is the same mistake as compiling against a ywzj_vehicle jar the game
# does not load, one directory over - and it costs the same afternoon.
$taczJar = if ($env:TACZ_JAR) { $env:TACZ_JAR } else {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $candidates = Get-ChildItem (Join-Path $instance 'mods') -Filter '*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match 'tacz' -and $_.Name -notlike '*mesh-loader*' } |
        Sort-Object LastWriteTime -Descending
    # The whole point of this loop: tacz-mesh-loader is a different project that happens to share the
    # prefix, so the candidate list is filtered by name and then each survivor is opened and asked
    # whether it is the gun mod.
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
# -LiteralPath, and it is load-bearing rather than tidy: this jar's name starts with a bracketed
# Chinese mod title, and Test-Path reads [ ] as a wildcard character class, so the plain form
# answers "no such file" for a file that is sitting right there. The build then reports the jar as
# missing and blames the wrong thing.
if (-not $taczJar -or -not (Test-Path -LiteralPath $taczJar)) {
    throw ("No TACZ jar found under $(Join-Path $instance 'mods'). This addon compiles against it; " +
        "set TACZ_JAR to point at one.")
}

function Find-Jar([string]$pattern) {
    $hit = Get-ChildItem -Path $libsRoot -Recurse -Filter (Split-Path $pattern -Leaf) -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1
    if (-not $hit) { throw "Could not find $pattern under $libsRoot" }
    return $hit.FullName
}

# --- locate the compile classpath -------------------------------------------------
$srgJar     = (Get-ChildItem (Join-Path $libsRoot 'net\minecraft\client') -Recurse -Filter '*-srg.jar' |
                Where-Object { $_.FullName -match '1\.20\.1' } | Select-Object -First 1).FullName
$forgeJar   = (Get-ChildItem (Join-Path $libsRoot 'net\minecraftforge\forge') -Recurse -Filter '*-universal.jar' |
                Where-Object { $_.FullName -match '1\.20\.1' } | Sort-Object FullName -Descending | Select-Object -First 1).FullName
$jomlJar    = Find-Jar 'org\joml\joml\1.10.5\joml-1.10.5.jar'
$mixinJar   = Find-Jar 'org\spongepowered\mixin\0.8.5\mixin-0.8.5.jar'
$gsonJar    = Find-Jar 'com\google\code\gson\gson\2.10.1\gson-2.10.1.jar'
$eventbusJar= Find-Jar 'net\minecraftforge\eventbus\6.2.33\eventbus-6.2.33.jar'
$slf4jJar   = Find-Jar 'org\slf4j\slf4j-api\2.0.1\slf4j-api-2.0.1.jar'
$brigadierJar = Find-Jar 'com\mojang\brigadier\1.1.8\brigadier-1.1.8.jar'
# FriendlyByteBuf's read/write methods are inherited from netty's ByteBuf, so a network message
# does not resolve without netty on the classpath.
$nettyBufferJar = Find-Jar 'io\netty\netty-buffer\4.1.82.Final\netty-buffer-4.1.82.Final.jar'
$nettyCommonJar = Find-Jar 'io\netty\netty-common\4.1.82.Final\netty-common-4.1.82.Final.jar'
# net.minecraftforge.api.distmarker.Dist (and so FMLEnvironment.dist) only ships in the older
# forgespi; without it a reference to Dist fails to resolve and javac crashes during desugaring,
# hiding the real error.
$forgespiJar = Find-Jar 'net\minecraftforge\forgespi\3.2.0\forgespi-3.2.0.jar'
# @Mod lives in javafmllanguage, FMLPaths in fmlloader, ModLoadingContext in fmlcore.
$fmlLangJar = (Get-ChildItem (Join-Path $libsRoot 'net\minecraftforge\javafmllanguage') -Recurse -Filter '*.jar' |
                Where-Object { $_.FullName -match '1\.20\.1' } | Sort-Object FullName -Descending | Select-Object -First 1).FullName
$fmlCoreJar = (Get-ChildItem (Join-Path $libsRoot 'net\minecraftforge\fmlcore') -Recurse -Filter '*.jar' |
                Where-Object { $_.FullName -match '1\.20\.1' } | Sort-Object FullName -Descending | Select-Object -First 1).FullName
$fmlLoaderJar = (Get-ChildItem (Join-Path $libsRoot 'net\minecraftforge\fmlloader') -Recurse -Filter '*.jar' |
                Where-Object { $_.FullName -match '1\.20\.1' } | Sort-Object FullName -Descending | Select-Object -First 1).FullName

foreach ($required in @($srgJar, $forgeJar, $modJar, $fmlLangJar, $fmlCoreJar, $fmlLoaderJar, $forgespiJar)) {
    if (-not $required -or -not (Test-Path $required)) { throw "Missing required jar: $required" }
}

Write-Host "Minecraft : $srgJar"
Write-Host "Forge     : $forgeJar"
Write-Host "Vehicle   : $modJar"
Write-Host "TACZ      : $taczJar  (compile only - see TaczBridge)"

$classpath = @(
    $srgJar, $forgeJar, $modJar, $taczJar, $jomlJar, $mixinJar,
    $gsonJar, $eventbusJar, $slf4jJar, $brigadierJar, $forgespiJar, $fmlLangJar, $fmlCoreJar, $fmlLoaderJar,
    $nettyBufferJar, $nettyCommonJar
) -join ';'

# --- compile ----------------------------------------------------------------------
Remove-Item $classDir, $libsOut -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $classDir, $libsOut | Out-Null

$sources = Get-ChildItem (Join-Path $root 'src\main\java') -Recurse -Filter *.java |
    Select-Object -ExpandProperty FullName
Write-Host "Compiling $($sources.Count) source file(s)..."

# -proc:none: the Mixin annotation processor is not needed because every name this addon
# references is already in its runtime (SRG) form, so there is no refmap to generate.
#
# javac writes even its -nowarn lints to stderr, and PowerShell 5.1 with the preference at Stop
# turns any native stderr line into a terminating error - so a note that a Forge method is marked
# for removal aborts the build *after* the compile has already succeeded, and the jar is never
# packaged. The exit code is the verdict; the notes are not.
$ErrorActionPreference = 'Continue'
& javac -encoding UTF-8 --release 17 -nowarn -proc:none -classpath $classpath -d $classDir $sources
$javacCode = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($javacCode -ne 0) { throw "javac failed with exit code $javacCode" }

Copy-Item (Join-Path $root 'src\main\resources\*') $classDir -Recurse -Force

# --- package ----------------------------------------------------------------------
$manifest = Join-Path $buildDir 'MANIFEST.MF'
@"
Manifest-Version: 1.0
MixinConfigs: ywzj_vehicle_armor.mixins.json

"@ | Set-Content -Path $manifest -Encoding ascii

$outJar = Join-Path $libsOut 'ywzj_vehicle_armor-1.0.0.jar'
& jar --create --file $outJar --manifest $manifest -C $classDir .
if ($LASTEXITCODE -ne 0) { throw "jar failed with exit code $LASTEXITCODE" }

Write-Host ""
Write-Host "Built $outJar" -ForegroundColor Green
