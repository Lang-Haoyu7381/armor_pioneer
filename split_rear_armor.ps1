# Splits the T-90M's turret-rear armour plate out of `additional_armor` into a part of its own, on
# both Proryv variants.
#
# Usage:  powershell -ExecutionPolicy Bypass -File split_rear_armor.ps1
#
# Why: `additional_armor` is one part unit covering FIVE cubes - four angled plates around the
# turret's cheeks and one plate lying across the very back of the turret (measured: z -62.3 .. -60.1,
# centred in x, the rearmost armour on the tank). The addon values armour per part unit, so all five
# cubes share one number, and the rear plate cannot be told apart from the cheeks. Splitting it out
# gives it a part id of its own (`armor_rear`), and the config then gives it its own figures.
#
# This is the same shape of change the T-90M's other add-on armour already has: `era_1`..`era_5` are
# separate part units on the model, which is exactly why they can carry separate values.
#
# Nothing is rendered from this file. A vehicle has two models - the structure model (this one) is
# OBBs and hitboxes and is never drawn, and the display model is what you see. So moving a cube from
# one bone to another changes what a hit resolves to and changes nothing on screen.
#
# The patch is textual: the cube's text is cut out of one bone and pasted into a new one, and every
# other byte of the file is left alone. Re-serialising would pass every number in the file through a
# formatter's idea of how a number looks.
#
# Idempotent, and the read-back at the end proves what it claims: the new bone exists with exactly
# that cube, `additional_armor` has one cube fewer, and every other bone and part is untouched.
#
# Order matters if rebuilding the pack from scratch: the pristine pack is the .bak next to it, so
# restore that, then run add_shells.ps1, then add_snorkel.ps1, then this. All three are idempotent and
# each verifies its own writes.

$ErrorActionPreference = 'Stop'

# The newest vvp_*.zip rather than a file name, for the same reason build.ps1 picks the newest
# base-mod jar: the pack is re-released under a new name, and a script naming the old one patches a
# pack the game no longer loads - silently, since every write succeeds and the read-back checks the
# file it just wrote.
$mcRoot = if ($env:MC_ROOT) { $env:MC_ROOT } else { 'E:\.minecraft' }
$packDir = Join-Path $mcRoot 'versions\1.20.1-Forge_47.4.2\limitless_vehicle'
$pack = if ($env:VVP_PACK) { $env:VVP_PACK } else {
    $found = Get-ChildItem $packDir -Filter 'vvp_*.zip' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike '*.bak' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $found) { throw "no vvp_*.zip in $packDir" }
    $found.FullName
}
Write-Host "pack: $pack"

# --- what we are adding -----------------------------------------------------------------------
#
# The part id the config will key on, the bone that carries the OBB, and the cube to move. The cube
# is identified by its origin - the one number in this file that is a measurement rather than a
# choice - and the script refuses to run if it is not there or is not where it was.

$partId    = 'additional_armor_rear'
$boneName  = 'armor_rear'
$sourcePart = 'additional_armor'
$cubeMarker = '"origin": [-16.6037, 28.8778, -62.2576]'

# The two variants that have the plate. The plain T-90M has no add-on armour package at all.
$vehicles = @('t90_pror_2022', 't90_pror_2024')

if (-not (Test-Path $pack)) { throw "vehicle pack not found: $pack" }
if (-not (Test-Path "$pack.bak")) {
    Copy-Item $pack "$pack.bak"
    Write-Host "backed up to $pack.bak"
} else {
    Write-Host "backup already exists, leaving it alone: $pack.bak"
}

$utf8 = New-Object System.Text.UTF8Encoding($false)
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Get-Text($archive, $name) {
    $entry = $archive.GetEntry($name)
    if (-not $entry) { throw "missing from the pack: $name" }
    $reader = New-Object System.IO.StreamReader($entry.Open(), [System.Text.Encoding]::UTF8)
    $text = $reader.ReadToEnd()
    $reader.Dispose()
    return $text
}

function Set-Text($archive, $name, $text) {
    $existing = $archive.GetEntry($name)
    if ($existing) { $existing.Delete(); Write-Host "  replaced $name" }
    else { Write-Host "  added    $name" }
    $entry = $archive.CreateEntry($name)
    $stream = $entry.Open()
    $bytes = $utf8.GetBytes($text)
    $stream.Write($bytes, 0, $bytes.Length)
    $stream.Dispose()
}

# --- bracket surgery, as in add_snorkel.ps1 ---------------------------------------------------
#
# Counting brackets rather than reparsing, because a JSON string may contain a brace and a regex
# cannot tell the difference.

function Find-Matching($text, $openIndex) {
    $open = $text[$openIndex]
    if ($open -eq '{') { $close = '}' } elseif ($open -eq '[') { $close = ']' } else { throw "not a bracket at $openIndex" }
    $depth = 0
    $inString = $false
    for ($i = $openIndex; $i -lt $text.Length; $i++) {
        $c = $text[$i]
        if ($inString) {
            if ($c -eq '\') { $i++ ; continue }
            if ($c -eq '"') { $inString = $false }
            continue
        }
        if ($c -eq '"') { $inString = $true; continue }
        if ($c -eq $open) { $depth++ }
        elseif ($c -eq $close) {
            $depth--
            if ($depth -eq 0) { return $i }
        }
    }
    throw "unbalanced $open ... $close starting at $openIndex"
}

function Find-Object-Start($text, $markerIndex) {
    $i = $markerIndex
    while ($i -ge 0) {
        if ($text[$i] -eq '{') { return $i }
        if ($text[$i] -eq '}') { throw "marker is not inside an object" }
        $i--
    }
    throw "no object before the marker"
}

function Insert-After($text, $closeIndex, $snippet) {
    return $text.Substring(0, $closeIndex + 1) + ",`n" + $snippet + $text.Substring($closeIndex + 1)
}

function Insert-Before($text, $closeIndex, $snippet) {
    return $text.Substring(0, $closeIndex) + ",`n" + $snippet + $text.Substring($closeIndex)
}

function Find-Next($text, $char, $from) {
    $i = $text.IndexOf($char, $from)
    if ($i -lt 0) { throw "could not find '$char' after $from" }
    return $i
}

# Appends an item INSIDE the array whose key is $key.
function Add-Item-To($text, $key, $snippet) {
    $at = $text.IndexOf('"' + $key + '"')
    if ($at -lt 0) { throw "no key '$key' in the file" }
    $open = Find-Next $text '[' ($at + $key.Length)
    return Insert-Before $text (Find-Matching $text $open) $snippet
}

# Inserts a bone object directly after the bone called $name.
function Insert-After-Bone($text, $name, $snippet) {
    $at = $text.IndexOf('"name": "' + $name + '"')
    if ($at -lt 0) { throw "no bone named '$name' in the model" }
    $close = Find-Matching $text (Find-Object-Start $text $at)
    return Insert-After $text $close $snippet
}

$fmt = [System.Globalization.CultureInfo]::InvariantCulture

function F($value) { return ([double]$value).ToString($fmt) }

# @return the text of the cube containing $marker, and the file with that cube removed from its bone.
function Cut-Cube($text, $marker) {
    $at = $text.IndexOf($marker)
    if ($at -lt 0) { throw "the cube to move is not in this model: $marker" }
    $open = Find-Object-Start $text $at
    $close = Find-Matching $text $open
    $cube = $text.Substring($open, $close - $open + 1)
    # The comma that joins it to the cube before it goes too; a trailing member with a comma after it
    # and nothing following is what would make the file unparseable.
    $comma = $text.LastIndexOf(',', $open)
    if ($comma -lt 0) { throw "the cube to move is not last in its array - this script assumed it was" }
    $script:cutCube = $cube
    return $text.Substring(0, $comma) + $text.Substring($close + 1)
}

# --- patch ------------------------------------------------------------------------------------

$zip = [System.IO.Compression.ZipFile]::Open($pack, [System.IO.Compression.ZipArchiveMode]::Update)

$before = @{}
foreach ($v in $vehicles) {
    $modelPath   = "data/vvp_ywzj/models/bedrock/vehicle/$v.structure.json"
    $vehiclePath = "data/vvp_ywzj/vehicles/$v.json"
    $model   = Get-Text $zip $modelPath
    $vehicle = Get-Text $zip $vehiclePath

    # Everything the read-back will hold this to.
    $parsed = $model | ConvertFrom-Json
    $bonesBefore = @($parsed.'minecraft:geometry'[0].bones | ForEach-Object { $_.name })
    $parsedVehicle = $vehicle | ConvertFrom-Json
    $idsBefore = @($parsedVehicle.parts | ForEach-Object { $_.id })
    $cubesBefore = 0
    foreach ($b in $parsed.'minecraft:geometry'[0].bones) {
        if ($b.cubes) { $cubesBefore += @($b.cubes).Count }
    }
    $before[$v] = @{ bones = $bonesBefore; ids = $idsBefore; cubes = $cubesBefore }

    $source = $parsed.'minecraft:geometry'[0].bones | Where-Object { $_.name -eq $sourcePart }
    if (-not $source) { throw "$v has no '$sourcePart' bone" }
    $cubeCountBefore = @($source.cubes).Count

    Write-Host ""
    Write-Host "${v}: '$sourcePart' has $cubeCountBefore cubes, moving the rearmost one to '$boneName'"

    if ($model.Contains('"name": "' + $boneName + '"')) {
        Write-Host "  model already has the '$boneName' bone - nothing to do"
    } else {
        $model = Cut-Cube $model $cubeMarker
        $cube = $script:cutCube
        Write-Host "  cut the cube ($($cube.Length) characters) out of '$sourcePart'"

        $pivot = '[' + (($source.pivot | ForEach-Object { F $_ }) -join ', ') + ']'
        $boneSnippet = @"
					{
						"name": "$boneName",
						"parent": "$($source.parent)",
						"pivot": $pivot,
						"cubes": [
$cube
						]
					}
"@
        $model = Insert-After-Bone $model $sourcePart $boneSnippet
        $model = $model.TrimEnd("`r", "`n") + "`n"
        Set-Text $zip $modelPath $model
    }

    if ($vehicle.Contains('"id": "' + $partId + '"')) {
        Write-Host "  vehicle already has the '$partId' part - nothing to do"
    } else {
        # Copied from the part it comes out of, apart from the ids: same type, same health, same
        # damage-transfer coefficient, so the only thing that changes is which armour value applies.
        $partSnippet = @"
			{
				"id": "$partId",
				"name": "$partId",
				"type": "$($parsedVehicle.parts | Where-Object { $_.id -eq $sourcePart } | ForEach-Object { $_.type })",
				"structure_bone": "$boneName",
				"is_seat": false,
				"max_health": $($parsedVehicle.parts | Where-Object { $_.id -eq $sourcePart } | ForEach-Object { $_.max_health }),
				"defense_stats": {
					"damage_transfer_coefficient": $($parsedVehicle.parts | Where-Object { $_.id -eq $sourcePart } | ForEach-Object { $_.defense_stats.damage_transfer_coefficient })
				}
			}
"@
        $vehicle = Add-Item-To $vehicle 'parts' $partSnippet
        $vehicle = $vehicle.TrimEnd("`r", "`n") + "`n"
        Set-Text $zip $vehiclePath $vehicle
    }
}

$zip.Dispose()

# --- read the pack back -----------------------------------------------------------------------

Write-Host ""
Write-Host "verifying the pack as written:"
$zip = [System.IO.Compression.ZipFile]::OpenRead($pack)
$problems = 0

foreach ($v in $vehicles) {
    $modelPath   = "data/vvp_ywzj/models/bedrock/vehicle/$v.structure.json"
    $vehiclePath = "data/vvp_ywzj/vehicles/$v.json"
    $model   = Get-Text $zip $modelPath
    $vehicle = Get-Text $zip $vehiclePath
    Write-Host ""
    Write-Host "  $v"

    $parsed = $model | ConvertFrom-Json
    $bones = @($parsed.'minecraft:geometry'[0].bones)

    # A name list, not a count: adding two bones and losing one leaves a count right.
    $names = @($bones | ForEach-Object { $_.name })
    $lost = @($before[$v].bones | Where-Object { $names -notcontains $_ })
    if ($lost.Count -gt 0) {
        Write-Host "    FAIL bones lost in the patch: $($lost -join ', ')"; $problems++
    } else {
        Write-Host "    OK   all $($before[$v].bones.Count) original bones are still there"
    }

    $source = $bones | Where-Object { $_.name -eq $sourcePart }
    if (-not $source) { Write-Host "    FAIL no '$sourcePart' bone"; $problems++ }
    elseif (@($source.cubes).Count -ne 4) {
        Write-Host "    FAIL '$sourcePart' has $(@($source.cubes).Count) cubes, expected 4"; $problems++
    } else { Write-Host "    OK   '$sourcePart' is down to the four angled plates" }

    $new = $bones | Where-Object { $_.name -eq $boneName }
    if (-not $new) { Write-Host "    FAIL no '$boneName' bone"; $problems++ }
    elseif (@($new.cubes).Count -ne 1) {
        Write-Host "    FAIL '$boneName' has $(@($new.cubes).Count) cubes, expected 1"; $problems++
    } elseif ($new.parent -ne 'turret') {
        Write-Host "    FAIL '$boneName' is parented to '$($new.parent)'"; $problems++
    } elseif ($new.cubes[0].origin[2] -ne -62.2576) {
        Write-Host "    FAIL '$boneName' holds the wrong cube (origin z $($new.cubes[0].origin[2]))"; $problems++
    } elseif ($new.cubes[0].rotation) {
        Write-Host "    FAIL '$boneName' holds a rotated cube - the rear plate has no rotation"; $problems++
    } else {
        Write-Host "    OK   '$boneName' is a turret part with the one unrotated cube at z -62.2576"
    }

    # The model's cube total is the one number that says nothing was duplicated or dropped on the way
    # through: a move leaves it alone, and both of those failures are still a model that loads.
    $cubeTotal = 0
    foreach ($b in $bones) { if ($b.cubes) { $cubeTotal += @($b.cubes).Count } }
    if ($cubeTotal -ne $before[$v].cubes) {
        Write-Host "    FAIL the model has $cubeTotal cubes, it had $($before[$v].cubes)"; $problems++
    } else { Write-Host "    OK   $cubeTotal cubes, exactly as before the patch" }

    $parsedVehicle = $vehicle | ConvertFrom-Json
    $ids = @($parsedVehicle.parts | ForEach-Object { $_.id })
    $missing = @($before[$v].ids | Where-Object { $ids -notcontains $_ })
    if ($missing.Count -gt 0) {
        Write-Host "    FAIL parts lost in the patch: $($missing -join ', ')"; $problems++
    } else {
        Write-Host "    OK   all $($before[$v].ids.Count) original parts are still there"
    }

    $mine = $parsedVehicle.parts | Where-Object { $_.id -eq $partId }
    if (-not $mine) { Write-Host "    FAIL no '$partId' part"; $problems++ }
    elseif ($mine.structure_bone -ne $boneName) {
        Write-Host "    FAIL '$partId' names bone '$($mine.structure_bone)'"; $problems++
    } elseif ($mine.is_seat -ne $false) {
        Write-Host "    FAIL '$partId' is a seat"; $problems++
    } else {
        Write-Host "    OK   the '$partId' part is generic, on '$boneName', and is not a seat"
    }
}

$zip.Dispose()

Write-Host ""
if ($problems -gt 0) {
    Write-Host "$problems problem(s) - the pack is NOT in the state this script describes." -ForegroundColor Red
    exit 1
}
Write-Host "Done. The turret's rear plate is its own part on both Proryv variants." -ForegroundColor Green
Write-Host "Now give it a value: it is a key in the config's part_armor block, and UpgradeCheck"
Write-Host "will fail until the config and the pack agree."
