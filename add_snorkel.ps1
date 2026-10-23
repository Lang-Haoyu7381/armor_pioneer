# Adds the fording kit's snorkel to the VVP T-64: a bone, an animation, a controller entry, a part,
# and a small patch of texture for it.
#
# Usage:  powershell -ExecutionPolicy Bypass -File add_snorkel.ps1
#
# This is the visual half of the fording kit the addon already has. The addon keeps the engine
# running in water; this makes the snorkel that does it visible, so that "the kit is deployed" and
# "the engine is protected" are the same fact rather than two.
#
# The mechanism is entirely the base mod's own, copied from the shapes its own pack already uses:
#
#   * the snorkel is a vehicle PART of type ywzj_vehicle:switchable, so its on/off boolean is synced
#     to clients (PartUnitSyncData) and saved to NBT (by part id) by the base mod;
#   * a switchable part plays the animation named in the controller's switchable_animations map -
#     forwards when it turns on, backwards when it turns off (SwitchableRunner);
#   * the animation moves one named bone, and the baker only keeps a bone movable if the animation
#     file names it (BaseDisplay.collectAnimatedBones) - hence the animation is not decoration, it is
#     what stops the bone being welded into its parent.
#
# Compare the base mod's own pack: default_vehicle's dump_truck (doors) and htf5980 (a missile tube
# that erects out of the vehicle) are the same three pieces.
#
# Order matters if rebuilding from scratch: the pristine pack is the .bak next to it, so restore that
# and run add_shells.ps1 first, then this. Both scripts are idempotent and verify their own writes.

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

# --- what we are adding ----------------------------------------------------------------------
#
# Everything here is a name or a number that a human chose; the geometry (how long the tube is) is
# measured from the model below, because a hand-typed length would silently poke out of the hull.

$partId   = 'snorkel'
$boneName = 'snorkel'
$animName = 'snorkel_switch'

# The turret roof, just behind the two hatches: measured, and the one spot on the roof where the
# hull and turret below it are solid, so a tube lowered into it cannot be seen from outside.
$mountX = -4.0
$mountZ = 20.0

# Cross-section of the tube in model units (16 to the block, so 3 units is about 19 cm).
$tubeWidth = 3.0
$tubeDepth = 3.0

# Animation length in seconds. A real OPVT is fitted over several minutes; this is the time it takes
# to read as "it went up".
$raiseSeconds = 1.2

# Where the tube's texture lives in the atlas. X 466 and beyond is outside every UV rectangle the
# model uses (the furthest right edge is 465), and the script refuses to draw there unless it is
# still blank - painting over somebody's track texture would be a silent mess.
$texSideX = 466
$texSideY = 428
$texCapX  = 466
$texCapY  = 466

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

function Get-Bytes($archive, $name) {
    $entry = $archive.GetEntry($name)
    $stream = $entry.Open()
    $memory = New-Object System.IO.MemoryStream
    $stream.CopyTo($memory)
    $stream.Dispose()
    return $memory.ToArray()
}

function Set-Entry($archive, $name, $bytes) {
    $existing = $archive.GetEntry($name)
    if ($existing) {
        # Rewriting an entry means removing it first; the archive is rewritten in place.
        $existing.Delete()
        Write-Host "  replaced $name"
    } else {
        Write-Host "  added    $name"
    }
    $entry = $archive.CreateEntry($name)
    $stream = $entry.Open()
    $stream.Write($bytes, 0, $bytes.Length)
    $stream.Dispose()
}

function Set-Text($archive, $name, $text) {
    Set-Entry $archive $name $utf8.GetBytes($text)
}

# --- finding where to insert, without reparsing the file -------------------------------------
#
# A 204 KB bedrock model is rewritten here by inserting text and leaving every other byte alone.
# Reading it into objects and writing it back out would be shorter to write and would risk changing
# things this script has no business touching - every number in the file is passed through the
# serialiser's idea of how a number looks. So: find the bracket, count brackets to its partner, and
# splice. Blank space, comments and string contents are all handled, because a JSON string may
# contain a brace.

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

function Insert-After($text, $closeIndex, $snippet) {
    # The comma goes on the closing brace and the snippet starts on the next line, which is where the
    # comma belongs whether the brace is followed by another member (whose own comma is already
    # there) or by the end of the enclosing array or object.
    return $text.Substring(0, $closeIndex + 1) + ",`n" + $snippet + $text.Substring($closeIndex + 1)
}

function Insert-Before($text, $closeIndex, $snippet) {
    # Same thing for a closing bracket: the member before it ended without a comma.
    return $text.Substring(0, $closeIndex) + ",`n" + $snippet + $text.Substring($closeIndex)
}

# @return the index of the '{' that opens the object containing $marker, searching backwards.
function Find-Object-Start($text, $markerIndex) {
    $i = $markerIndex
    while ($i -ge 0) {
        if ($text[$i] -eq '{') { return $i }
        if ($text[$i] -eq '}') { throw "marker is not inside an object" }
        $i--
    }
    throw "no object before the marker"
}

# @return the index of the first $char at or after $from.
function Find-Next($text, $char, $from) {
    $i = $text.IndexOf($char, $from)
    if ($i -lt 0) { throw "could not find '$char' after $from" }
    return $i
}

# Appends a member INSIDE the object whose key is $key - the object itself is the value, and the new
# member joins it. (Inserting after the object instead would make a sibling of it, which parses
# perfectly well and means something else entirely: this script did that once, and the read-back
# below is what caught it.)
function Add-Member-To($text, $key, $snippet) {
    $at = $text.IndexOf('"' + $key + '"')
    if ($at -lt 0) { throw "no key '$key' in the file" }
    $open = Find-Next $text '{' ($at + $key.Length)
    return Insert-Before $text (Find-Matching $text $open) $snippet
}

# Appends an item INSIDE the array whose key is $key.
function Add-Item-To($text, $key, $snippet) {
    $at = $text.IndexOf('"' + $key + '"')
    if ($at -lt 0) { throw "no key '$key' in the file" }
    $open = Find-Next $text '[' ($at + $key.Length)
    return Insert-Before $text (Find-Matching $text $open) $snippet
}

# Appends a member to the file's root object, wherever its last member happens to end.
function Add-Root-Member($text, $snippet) {
    $open = Find-Next $text '{' 0
    return Insert-Before $text (Find-Matching $text $open) $snippet
}

# Inserts a bone object directly after the bone called $name.
function Insert-After-Bone($text, $name, $snippet) {
    $at = $text.IndexOf('"name": "' + $name + '"')
    if ($at -lt 0) { throw "no bone named '$name' in the model" }
    $close = Find-Matching $text (Find-Object-Start $text $at)
    return Insert-After $text $close $snippet
}

# --- read the pack ---------------------------------------------------------------------------

$zip = [System.IO.Compression.ZipFile]::Open($pack, [System.IO.Compression.ZipArchiveMode]::Update)
$modelPath  = 'assets/vvp_ywzj/models/bedrock/entity/t64.json'
$animPath   = 'assets/vvp_ywzj/animations/bedrock/entity/t64.animation.json'
$ctrlPath   = 'assets/vvp_ywzj/animation_controllers/t64_controller.json'
$vehiclePath = 'data/vvp_ywzj/vehicles/t64.json'
$texturePath = 'assets/vvp_ywzj/textures/entity/t64.png'

$model   = Get-Text $zip $modelPath
$anim    = Get-Text $zip $animPath
$ctrl    = Get-Text $zip $ctrlPath
$vehicle = Get-Text $zip $vehiclePath

# --- measure the tube -------------------------------------------------------------------------
#
# How long the tube is, is decided by the model: it has to be short enough to disappear entirely
# inside the turret and hull, so that a stowed kit is invisible, and long enough to be worth looking
# at when it is up.

function Get-Cubes($parsed, $bone) {
    $found = $parsed.'minecraft:geometry'[0].bones | Where-Object { $_.name -eq $bone }
    if (-not $found) { throw "no bone '$bone' in the model" }
    return @($found.cubes)
}

function Get-RoofAt($parsed, $x, $z) {
    $top = $null
    foreach ($c in (Get-Cubes $parsed 'cannon')) {
        $x0 = $c.origin[0]; $z0 = $c.origin[2]
        if ($x -ge $x0 -and $x -le ($x0 + $c.size[0]) -and $z -ge $z0 -and $z -le ($z0 + $c.size[2])) {
            $y = $c.origin[1] + $c.size[1]
            if ($null -eq $top -or $y -gt $top) { $top = $y }
        }
    }
    if ($null -eq $top) { throw "nothing of the turret is over ($x, $z)" }
    return $top
}

function Get-FloorAt($parsed, $x, $z) {
    $bottom = $null
    foreach ($c in (Get-Cubes $parsed 'base')) {
        $x0 = $c.origin[0]; $z0 = $c.origin[2]
        if ($x -ge $x0 -and $x -le ($x0 + $c.size[0]) -and $z -ge $z0 -and $z -le ($z0 + $c.size[2])) {
            $y = $c.origin[1]
            if ($null -eq $bottom -or $y -lt $bottom) { $bottom = $y }
        }
    }
    if ($null -eq $bottom) { throw "nothing of the hull is under ($x, $z)" }
    return $bottom
}

$parsedModel = $model | ConvertFrom-Json
$roof  = Get-RoofAt $parsedModel $mountX $mountZ
$floor = Get-FloorAt $parsedModel $mountX $mountZ
$tubeLength = [Math]::Round($roof - $floor, 4)

# Every bone the model has before the patch, so the read-back can prove they all survive it. A count
# would not: inserting two bones and losing one leaves the count right.
$bonesBefore = @($parsedModel.'minecraft:geometry'[0].bones | ForEach-Object { $_.name })

# The numbers this script was written against. If a future pack moves the turret roof, the tube
# would either float above the roof or stick out of the bottom of the hull, and both look like a
# modelling mistake rather than a stale constant - so say so and stop.
$expectRoof  = 46.3
$expectFloor = 9.9
if ([Math]::Abs($roof - $expectRoof) -gt 0.5 -or [Math]::Abs($floor - $expectFloor) -gt 0.5) {
    throw ("the turret roof or hull floor has moved: roof $roof (expected about $expectRoof), " +
           "floor $floor (expected about $expectFloor). Re-measure and update this script.")
}
Write-Host "tube: ${tubeWidth}x${tubeDepth}x$tubeLength units, from y=$floor up to y=$roof"

$fmt = [System.Globalization.CultureInfo]::InvariantCulture

function F($value) { return ([double]$value).ToString($fmt) }

# The cube is authored in its STOWED place - down inside the turret and hull - so that a snorkel with
# no animation playing is invisible rather than permanently up. Deploying it is a pure translation.
$tubeX = $mountX - $tubeWidth / 2.0
$tubeZ = $mountZ - $tubeDepth / 2.0

# The atlas is one pixel to one model unit (the hull's 42x63 face uses a 42x63 uv_size), so the
# tube's four sides are three pixels wide and as tall as the tube, rounded up so that the texture
# sampled is never narrower than the geometry drawn.
$sideLength = [int][Math]::Ceiling($tubeLength) + 1

# Laid out the way the pack lays every other cube out: one entry per face, north/east/south/west side
# by side so that the four can be shaded differently, then the two caps.
function Uv() {
    $t = "`n" + ("`t" * 7)
    $row = $t + "`t"
    return $t + '"uv": {' +
        $row + '"north": {"uv": [' + ($texSideX + 0) + ', ' + $texSideY + '], "uv_size": [3, ' + $sideLength + ']},' +
        $row + '"east": {"uv": [' + ($texSideX + 3) + ', ' + $texSideY + '], "uv_size": [3, ' + $sideLength + ']},' +
        $row + '"south": {"uv": [' + ($texSideX + 6) + ', ' + $texSideY + '], "uv_size": [3, ' + $sideLength + ']},' +
        $row + '"west": {"uv": [' + ($texSideX + 9) + ', ' + $texSideY + '], "uv_size": [3, ' + $sideLength + ']},' +
        $row + '"up": {"uv": [' + $texCapX + ', ' + $texCapY + '], "uv_size": [3, 3]},' +
        $row + '"down": {"uv": [' + ($texCapX + 3) + ', ' + $texCapY + '], "uv_size": [3, -3]}' +
        $t + '}'
}

$boneSnippet = @"
				{
					"name": "$boneName",
					"parent": "cannon",
					"pivot": [$mountX, $roof, $mountZ],
					"cubes": [
						{
							"origin": [$(F $tubeX), $(F $floor), $(F $tubeZ)],
							"size": [$(F $tubeWidth), $(F $tubeLength), $(F $tubeDepth)],
$(Uv)
						}
					]
				}
"@

if ($model.Contains('"name": "' + $boneName + '"')) {
    Write-Host "model already has the '$boneName' bone"
} else {
    $model = Insert-After-Bone $model 'cannon' $boneSnippet
    $model = $model.TrimEnd("`r", "`n") + "`n"
    Set-Text $zip $modelPath $model
}

# --- the animation ----------------------------------------------------------------------------
#
# Written like the base mod's own door animation: position keyframes, no loop mode. The switchable
# runner plays it forwards to deploy and backwards to stow, and holds the last frame at either end.

$animSnippet = @"
		"$animName": {
			"animation_length": $raiseSeconds,
			"bones": {
				"$boneName": {
					"position": {
						"0.0": [0, 0, 0],
						"$(F $raiseSeconds)": [0, $(F $tubeLength), 0]
					}
				}
			}
		}
"@

if ($anim.Contains('"' + $animName + '"')) {
    Write-Host "animation file already has '$animName'"
} else {
    $anim = Add-Member-To $anim 'animations' $animSnippet
    $anim = $anim.TrimEnd("`r", "`n") + "`n"
    Set-Text $zip $animPath $anim
}

# --- the controller ---------------------------------------------------------------------------
#
# Two additions, both additive: a map saying which part drives which animation, and that animation
# merged into the pose graph. The graph's existing inputs - the bone bindings that turn the turret
# and elevate the gun, and the event animation that recoils it - are untouched, and the read-back
# below compares them so that "untouched" is checked rather than intended.

$mapSnippet = @"
	"switchable_animations": {
		"$partId": {
			"part_id": "$partId",
			"animation": "$animName"
		}
	}
"@

$graphSnippet = @"
			{
				"type": "switchable_animation",
				"ref": "$partId"
			}
"@

if ($ctrl.Contains('"' + $partId + '"')) {
    Write-Host "controller already knows about '$partId'"
} else {
    $ctrl = Add-Root-Member $ctrl $mapSnippet
    $ctrl = Add-Item-To $ctrl 'inputs' $graphSnippet
    $ctrl = $ctrl.TrimEnd("`r", "`n") + "`n"
    Set-Text $zip $ctrlPath $ctrl
}

# --- the part ---------------------------------------------------------------------------------
#
# switchable, and deliberately without a structure_bone: a part whose bone is not in the structure
# model gets no OBB (PartUnitData.initStructureModel returns early), so this adds no hitbox, no
# collision and nothing for the armour system to find. The T-64's own driver_seat_2 is the same
# shape. render_bone is the thing that gets drawn and, if the part is ever shot off, hidden.

$partSnippet = @"
		{
			"id": "$partId",
			"name": "$partId",
			"type": "ywzj_vehicle:switchable",
			"render_bone": "$boneName",
			"is_seat": false
		}
"@

if ($vehicle.Contains('"id": "' + $partId + '"')) {
    Write-Host "vehicle already has the '$partId' part"
} else {
    $vehicle = Add-Item-To $vehicle 'parts' $partSnippet
    $vehicle = $vehicle.TrimEnd("`r", "`n") + "`n"
    Set-Text $zip $vehiclePath $vehicle
}

# --- the texture ------------------------------------------------------------------------------
#
# Four columns, one per side of the tube, so that the faces can differ slightly and the tube reads as
# round rather than flat; a dark band every few pixels for the clamps; and two caps. Painted into
# atlas space nothing else uses.
#
# The patch sits against transparent atlas on three sides. That is only safe because entity models are
# drawn with nearest-neighbour filtering and no mipmaps - on a mipmapped atlas the lower levels would
# blend the neighbouring transparency in and the tube would go see-through at a distance. The 12x38
# patch is also as wide as the strip allows: the last used pixel column of this atlas is 465.

Add-Type -AssemblyName System.Drawing

$bytes = Get-Bytes $zip $texturePath
$stream = New-Object System.IO.MemoryStream(, $bytes)
$original = New-Object System.Drawing.Bitmap([System.Drawing.Image]::FromStream($stream))
$painted = New-Object System.Drawing.Bitmap($original)

# Dark olive, with the four sides shaded differently and a clamp ring every eight pixels.
$sides = @(
    [System.Drawing.Color]::FromArgb(255, 74, 80, 66),
    [System.Drawing.Color]::FromArgb(255, 62, 68, 56),
    [System.Drawing.Color]::FromArgb(255, 52, 57, 47),
    [System.Drawing.Color]::FromArgb(255, 66, 72, 59)
)
$band  = [System.Drawing.Color]::FromArgb(255, 38, 42, 35)
$cap   = [System.Drawing.Color]::FromArgb(255, 44, 48, 41)
$mouth = [System.Drawing.Color]::FromArgb(255, 20, 22, 19)

$ours = New-Object 'System.Collections.Generic.HashSet[int]'
foreach ($c in $sides) { [void]$ours.Add($c.ToArgb()) }
[void]$ours.Add($band.ToArgb())
[void]$ours.Add($cap.ToArgb())
[void]$ours.Add($mouth.ToArgb())

# Every pixel this script touches must be either blank or already exactly what it paints - which
# makes re-running it safe, and makes painting over somebody else's texture impossible rather than
# merely unlikely. The corner was chosen because no UV rectangle reaches it; this is the check that
# that is still true.
function Assert-Paintable($x0, $y0, $w, $h, $what) {
    for ($y = 0; $y -lt $h; $y++) {
        for ($x = 0; $x -lt $w; $x++) {
            $pixel = $painted.GetPixel($x0 + $x, $y0 + $y)
            if ($pixel.A -eq 0) { continue }
            if ($ours.Contains($pixel.ToArgb())) { continue }
            throw ("the texture at ($($x0 + $x),$($y0 + $y)) is used by something else (argb " +
                   "$($pixel.ToArgb())) - $what cannot be painted there")
        }
    }
}

Assert-Paintable $texSideX $texSideY 12 $sideLength "the tube's sides"
Assert-Paintable $texCapX $texCapY 6 3 "the tube's caps"

for ($side = 0; $side -lt 4; $side++) {
    for ($y = 0; $y -lt $sideLength; $y++) {
        $colour = $sides[$side]
        if (($y % 8) -eq 3 -or ($y % 8) -eq 4) { $colour = $band }
        for ($x = 0; $x -lt 3; $x++) {
            $painted.SetPixel($texSideX + $side * 3 + $x, $texSideY + $y, $colour)
        }
    }
}
for ($y = 0; $y -lt 3; $y++) {
    for ($x = 0; $x -lt 3; $x++) {
        $painted.SetPixel($texCapX + $x, $texCapY + $y, $mouth)
        $painted.SetPixel($texCapX + 3 + $x, $texCapY + $y, $cap)
    }
}

$out = New-Object System.IO.MemoryStream
$painted.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
Set-Entry $zip $texturePath $out.ToArray()
$out.Dispose()

$zip.Dispose()
$painted.Dispose()
# $original is kept until the end: the read-back compares the released texture against it.

# --- read the pack back -----------------------------------------------------------------------

Write-Host ""
Write-Host "verifying the pack as written:"
$zip = [System.IO.Compression.ZipFile]::OpenRead($pack)
$problems = 0

$model2   = Get-Text $zip $modelPath
$anim2    = Get-Text $zip $animPath
$ctrl2    = Get-Text $zip $ctrlPath
$vehicle2 = Get-Text $zip $vehiclePath

$parsedModel2 = $model2 | ConvertFrom-Json
$bones2 = @($parsedModel2.'minecraft:geometry'[0].bones)
$cubeCount = 0
foreach ($b in $bones2) { if ($b.cubes) { $cubeCount += @($b.cubes).Count } }

if ($bones2.Count -ne 47) { Write-Host "  FAIL the model has $($bones2.Count) bones, expected 47"; $problems++ }
else { Write-Host "  OK   47 bones (was 46)" }

$names2 = @($bones2 | ForEach-Object { $_.name })
$lost = @($bonesBefore | Where-Object { $names2 -notcontains $_ })
if ($lost.Count -gt 0) {
    Write-Host "  FAIL $($lost.Count) bone(s) were lost in the patch: $($lost -join ', ')"; $problems++
} else { Write-Host "  OK   all 46 original bones are still there" }
if ($cubeCount -ne 375) { Write-Host "  FAIL the model has $cubeCount cubes, expected 375"; $problems++ }
else { Write-Host "  OK   375 cubes (was 374)" }

$cannon = $bones2 | Where-Object { $_.name -eq 'cannon' }
if (-not $cannon -or @($cannon.cubes).Count -ne 51) {
    Write-Host "  FAIL the cannon bone no longer has 51 cubes"; $problems++
} else { Write-Host "  OK   the cannon bone is untouched (51 cubes)" }

$snorkel = $bones2 | Where-Object { $_.name -eq $boneName }
if (-not $snorkel) { Write-Host "  FAIL no '$boneName' bone"; $problems++ }
elseif (@($snorkel.cubes).Count -ne 1) { Write-Host "  FAIL '$boneName' has $((@($snorkel.cubes)).Count) cubes, expected 1"; $problems++ }
elseif ($snorkel.parent -ne 'cannon') { Write-Host "  FAIL '$boneName' is parented to '$($snorkel.parent)'"; $problems++ }
else { Write-Host "  OK   the '$boneName' bone hangs off the turret with one cube" }

$parsedAnim = $anim2 | ConvertFrom-Json
$names = @($parsedAnim.animations.PSObject.Properties.Name)
if ($names -notcontains $animName) { Write-Host "  FAIL no '$animName' animation"; $problems++ }
elseif ($names -notcontains 'turret_shoot') { Write-Host "  FAIL the recoil animations are gone: $names"; $problems++ }
else { Write-Host "  OK   animation '$animName' added, recoil kept" }

# The bone has to be in the animation or the baker welds it into its parent and it can never move.
$snorkelTrack = $parsedAnim.animations.$animName.bones.PSObject.Properties.Name
if ($snorkelTrack -notcontains $boneName) {
    Write-Host "  FAIL '$animName' does not animate '$boneName' - the bone would be welded in place"
    $problems++
} else { Write-Host "  OK   '$animName' animates '$boneName' (so the bone stays movable)" }

$parsedCtrl = $ctrl2 | ConvertFrom-Json
$bindings = @($parsedCtrl.graph.inputs[0].base.inputs[0].part_bindings)
if ($bindings.Count -ne 4) {
    Write-Host "  FAIL the turret bindings are not what they were: $($bindings.Count) entries"; $problems++
} else {
    $want = 'cannon/turret/y', 'barrel/turret/x', 'gun/commander_machine_gun/y', 'gun_barrel/commander_machine_gun/x'
    $got = @()
    foreach ($b in $bindings) { $got += "$($b.bone)/$($b.part)/$($b.rotation_type)" }
    if (($got -join ',') -ne ($want -join ',')) {
        Write-Host "  FAIL the turret bindings changed: $($got -join ', ')"; $problems++
    } else { Write-Host "  OK   the turret bindings are unchanged ($($got -join ', '))" }
}
$events = @($parsedCtrl.event_animations.PSObject.Properties.Name)
if ($events.Count -ne 2) { Write-Host "  FAIL event_animations changed: $events"; $problems++ }
else { Write-Host "  OK   event_animations unchanged ($($events -join ', '))" }

# The rest of the graph: if a controller fails to compile the base mod swallows the error, the
# animation instance comes back null, and the tank loses ALL of its animation - traverse, wheels and
# recoil together - with nothing in any log to say why. So the nodes that drive those are checked by
# name, not left to "the file still parses".
$nodeTypes = @()
function Collect-Nodes($node) {
    if ($null -eq $node) { return }
    if ($node.type) { $script:nodeTypes += $node.type }
    foreach ($key in @('inputs', 'base', 'add')) {
        $child = $node.$key
        if ($null -eq $child) { continue }
        if ($child -is [System.Array]) { foreach ($c in $child) { Collect-Nodes $c } }
        else { Collect-Nodes $child }
    }
}
Collect-Nodes $parsedCtrl.graph
$required = @('bone_binding', 'track_animation', 'event_animation', 'additive')
$missing = @($required | Where-Object { $nodeTypes -notcontains $_ })
if ($missing.Count -gt 0) {
    Write-Host "  FAIL the pose graph lost node type(s): $($missing -join ', ') - the whole tank would stop animating"
    $problems++
} else { Write-Host "  OK   the pose graph still drives the turret, the wheels and the recoil" }

$sw = $parsedCtrl.switchable_animations.$partId
if (-not $sw) { Write-Host "  FAIL no switchable_animations entry for '$partId'"; $problems++ }
elseif ($sw.animation -ne $animName -or $sw.part_id -ne $partId) {
    Write-Host "  FAIL the switchable entry points at '$($sw.part_id)' / '$($sw.animation)'"; $problems++
} else { Write-Host "  OK   '$partId' drives '$animName'" }

$graphRefs = @()
foreach ($input in $parsedCtrl.graph.inputs) { if ($input.ref) { $graphRefs += $input.ref } }
if ($graphRefs -notcontains $partId) {
    Write-Host "  FAIL the graph does not merge the '$partId' animation: $graphRefs"; $problems++
} else { Write-Host "  OK   the graph merges it (inputs: $($graphRefs -join ', '))" }

$parsedVehicle = $vehicle2 | ConvertFrom-Json
$parts = @($parsedVehicle.parts)
$ids = @()
foreach ($p in $parts) { $ids += $p.id }

# Compared against the pristine pack rather than against a number written here.
#
# The first version asserted "8 parts" with the seven ids listed out, which was true when this was
# written and became false the moment the pack itself grew parts - the 0.6.1 pack gives the T-64
# twelve suspension units, so the count went to 19 and this reported a failure that was not one.
# The question actually worth asking is the one that survives a pack update: are the parts that were
# there still there, in the same order, with exactly one thing added?
$pristineParts = @()
if (Test-Path "$pack.bak") {
    try {
        $zBackup = [System.IO.Compression.ZipFile]::OpenRead("$pack.bak")
        $backupText = Get-Text $zBackup $vehiclePath
        $zBackup.Dispose()
        foreach ($p in @(($backupText | ConvertFrom-Json).parts)) { $pristineParts += $p.id }
    } catch {
        Write-Host "  (could not read the pristine pack: $($_.Exception.Message))"
    }
}
$added = @($ids | Where-Object { $pristineParts -notcontains $_ })
$missing = @($pristineParts | Where-Object { $ids -notcontains $_ })
if ($pristineParts.Count -eq 0) {
    Write-Host "  OK   $($parts.Count) part(s); no pristine pack to compare against"
} elseif ($missing.Count -gt 0) {
    Write-Host "  FAIL the pack's own parts are gone: $($missing -join ', ')"; $problems++
} elseif ($added.Count -ne 1 -or $added[0] -ne $partId) {
    Write-Host "  FAIL expected only '$partId' to be added, got: $($added -join ', ')"; $problems++
} else {
    Write-Host "  OK   $($pristineParts.Count) original parts all still there, '$partId' added"
}

$mine = $parts | Where-Object { $_.id -eq $partId }
if (-not $mine) { Write-Host "  FAIL no '$partId' part"; $problems++ }
elseif ($mine.type -ne 'ywzj_vehicle:switchable') { Write-Host "  FAIL '$partId' has type '$($mine.type)'"; $problems++ }
elseif ($mine.structure_bone) { Write-Host "  FAIL '$partId' has a structure_bone: it would become a hitbox"; $problems++ }
else { Write-Host "  OK   the '$partId' part is a switchable with no hitbox" }

$png2 = Get-Bytes $zip $texturePath
$stream2 = New-Object System.IO.MemoryStream(, $png2)
$check = New-Object System.Drawing.Bitmap([System.Drawing.Image]::FromStream($stream2))
if ($check.Width -ne 480 -or $check.Height -ne 480) {
    Write-Host "  FAIL the texture is $($check.Width)x$($check.Height)"; $problems++
} else {
    # The new patch has to be there, and nothing else may have moved.
    $missing = 0
    for ($y = 0; $y -lt $sideLength; $y++) {
        if ($check.GetPixel($texSideX, $texSideY + $y).A -eq 0) { $missing++ }
    }
    if ($missing -gt 0) { Write-Host "  FAIL $missing row(s) of the tube texture are still blank"; $problems++ }
    else { Write-Host "  OK   the tube texture is painted ($sideLength rows)" }

    $changed = 0
    for ($y = 0; $y -lt 480; $y += 3) {
        for ($x = 0; $x -lt 480; $x += 3) {
            $inSides = ($x -ge $texSideX) -and ($x -lt ($texSideX + 12)) -and
                       ($y -ge $texSideY) -and ($y -lt ($texSideY + $sideLength))
            $inCaps = ($x -ge $texCapX) -and ($x -lt ($texCapX + 6)) -and
                      ($y -ge $texCapY) -and ($y -lt ($texCapY + 3))
            if ($inSides -or $inCaps) { continue }
            if ($check.GetPixel($x, $y).ToArgb() -ne $original.GetPixel($x, $y).ToArgb()) { $changed++ }
        }
    }
    if ($changed -gt 0) { Write-Host "  FAIL $changed sampled pixel(s) outside the new patch changed"; $problems++ }
    else { Write-Host "  OK   no pixel outside the new patch changed" }
}
$check.Dispose()
$zip.Dispose()
$original.Dispose()

Write-Host ""
if ($problems -gt 0) {
    Write-Host "$problems problem(s) - the pack is NOT in the state this script describes." -ForegroundColor Red
    exit 1
}
Write-Host "Done. The T-64 now has a snorkel: it rises out of the turret roof when the kit is deployed." -ForegroundColor Green
