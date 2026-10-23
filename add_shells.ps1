# Adds the shells the addon needs to the VVP vehicle pack.
#
# The pack is a zip the base mod reads as a datapack, so the rounds have to live there rather than
# in the addon jar: a mod jar's data/ is scanned, but nothing attaches a weapon to an existing
# vehicle except that vehicle's own "weapons" list.
#
# For each enabled round, three changes, after backing up the whole archive once:
#   data/vvp_ywzj/weapons/<id>.json            the round itself
#   assets/vvp_ywzj/display/weapon/<id>.json   fire/reload sounds
#   data/vvp_ywzj/vehicles/<vehicle>.json      one id appended to the main gun's ammo list
#
# A round can be given to several vehicles at once with 'Vehicles' instead of 'Vehicle' - the same
# round on four tanks is one round, and writing it four times under four ids would be four rounds
# that have to be kept in step. The weapon file is written once either way.
#
# Declarative rather than additive: a round that is switched off is *removed* if the pack has it,
# so flipping a switch and re-running puts the pack in the state the switches describe, either way.
# Re-running is safe. The script reads the pack back at the end and reports what it found.
#
# Usage:  powershell -ExecutionPolicy Bypass -File add_shells.ps1

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

# The Abrams is left out for now, so this round's files are not written and the id is stripped from
# the M1A1's ammo list. Its armour table against HE already exists in the addon - a 125mm HE shell
# fired at an Abrams is measured against it - this switch is only about the Abrams carrying one.
# Turning it on also needs the matching entry in config/ywzj_vehicle_armor.json, which is not the pack's to
# write: see the README.
$addAbramsHe = $false

# The pack to patch: the newest vvp_*.zip in the instance's pack folder, or VVP_PACK if set.
#
# Chosen rather than written down, for the same reason build.ps1 picks the newest base-mod jar: the
# pack is re-released under a new file name, and a script naming the old one patches a pack the game
# no longer loads - silently, because every write succeeds and the read-back checks the file it just
# wrote. That is exactly what happened when 0.6.1 arrived: all three patchers still named the 0.6.0
# zip, the new pack shipped unpatched, and the only symptom was in game (no 30mm dart, no snorkel).
$mcRoot = if ($env:MC_ROOT) { $env:MC_ROOT } else { 'E:\.minecraft' }
$packDir = Join-Path $mcRoot 'versions\1.20.1-Forge_47.4.2\limitless_vehicle'
$pack = if ($env:VVP_PACK) { $env:VVP_PACK } else {
    $found = Get-ChildItem $packDir -Filter 'vvp_*.zip' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike '*.bak' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $found) { throw "no vvp_*.zip in $packDir" }
    $found.FullName
}
if (-not (Test-Path $pack)) { throw "pack not found: $pack" }
Write-Host "pack: $pack"

# Every cannon round in this pack differs only in its name, its tracer colour, how fast it flies,
# and whether it carries a proximity fuze.
#
# The explosion block is assembled a line at a time rather than by interpolating a fragment into a
# template: a fragment would have to carry escaped quotes, and PowerShell escapes with backticks
# rather than backslashes, so the obvious `\"` silently produces a file that will not parse.
#
# 'life' is deliberately left out of every round here. The base mod's own default is 1200 ticks
# (see BaseVehicleWeaponData's constructor), which is what these rounds have always flown on, and
# it comfortably outlasts the addon's longest delay fuze at 100 ticks. Setting it here would only
# be a second place for the same number to be wrong.
function New-Cannon($name, $caliber, $tracerG, $velocity, $proximityFuze) {
    # Built before the array literal: folding the conditional into the literal splits the comma off
    # into an array element of its own, which parses but reads as a line of punctuation.
    $destroyBlock = "`t`t`"destroy_block`": true"
    if ($proximityFuze) { $destroyBlock += "," }

    $lines = @(
        "{",
        "`t`"type`": `"ywzj_vehicle:cannon`",",
        "`t`"name`": `"$name`",",
        "`t`"caliber`": $caliber,",
        "`t`"tracer_r`": 1,",
        "`t`"tracer_g`": $tracerG,",
        "`t`"tracer_b`": 0.2,",
        "`t`"damage`": 500,",
        "`t`"velocity`": $velocity,",
        "`t`"headshot_multiplier`": 1,",
        "`t`"recoil`": 1,",
        "`t`"inaccuracy`": 0.05,",
        "`t`"shoot_interval`": 400,",
        "`t`"max_capacity`": 1,",
        "`t`"reload`": {",
        "`t`t`"time`": 100,",
        "`t`t`"ammo`": `"ywzj_vehicle:ammo_artillery`"",
        "`t},",
        "`t`"explosion`": {",
        "`t`t`"explode`": true,",
        "`t`t`"damage`": 1500,",
        "`t`t`"radius`": 4,",
        $destroyBlock
    )
    if ($proximityFuze) {
        $lines += "`t`t`"proximity_fuze`": true,"
        $lines += "`t`t`"proximity_radius`": 4"
    }
    $lines += "`t}"
    $lines += "}"
    return $lines -join "`n"
}

# The 30mm autocannons are not tank guns: a faster rate of fire, a bigger magazine, a different
# feed, and rounds that are nothing like a 125mm shell. None of New-Cannon's numbers apply, so they
# get their own builder rather than a pile of switches on that one.
#
# Every number a round does *not* decide is a parameter, because the pack has two 30mm guns and they
# are not the same gun: the BTR-3's 2A72 is a fast light cannon with a 300-round feed, and the
# Stryker's XM813 is a slower one with 120. The rounds a gun fires are a property of the round; its
# cadence and its magazine are a property of the gun, and a dart that changed them would mean the
# gunner's magazine changed size when he switched rounds.
#
# The explosive flag is the whole difference between the two rounds each cannon fires: the HE shell
# carries the pack's own small blast, and the dart carries none at all - it is a slug of metal, and
# what it does to a vehicle is decided entirely by whether it gets through.
function New-AutoCannon($name, $velocity, $tracerG, $explosive, $damage, $recoil, $interval, $capacity, $reloadTime) {
    # Decided before the array literal, for the same reason as in New-Cannon: a conditional folded
    # into the literal splits the comma off into an array element of its own.
    $reloadTail = "`t}"
    if ($explosive) { $reloadTail += "," }

    $lines = @(
        "{",
        "`t`"type`": `"ywzj_vehicle:cannon`",",
        "`t`"name`": `"$name`",",
        "`t`"caliber`": 30,",
        "`t`"tracer_r`": 1,",
        "`t`"tracer_g`": $tracerG,",
        "`t`"tracer_b`": 0.2,",
        "`t`"damage`": $damage,",
        "`t`"velocity`": $velocity,",
        "`t`"headshot_multiplier`": 1.5,",
        "`t`"recoil`": $recoil,",
        "`t`"shoot_interval`": $interval,",
        "`t`"max_capacity`": $capacity,",
        "`t`"reload`": {",
        "`t`t`"time`": $reloadTime,",
        "`t`t`"ammo`": `"ywzj_vehicle:ammo_auto_cannon`"",
        $reloadTail
    )
    if ($explosive) {
        $lines += "`t`"explosion`": {"
        $lines += "`t`t`"explode`": true,"
        $lines += "`t`t`"damage`": 10,"
        $lines += "`t`t`"radius`": 1,"
        $lines += "`t`t`"destroy_block`": true"
        $lines += "`t}"
    }
    $lines += "}"
    return $lines -join "`n"
}

# The reload sound defaults to the one every tank-gun round in this pack uses; the autocannon takes
# the other one, the sound its own existing shell already carries.
# Replaces the value of a weapon file's "name" field.
#
# Located by finding the key and then the two quotes around its value, rather than with a regex:
# a regex broad enough to be readable would also match a nested "name", and there would be no way
# from the outside to tell that it had renamed the wrong one.
function Set-WeaponName($text, $newName) {
    $key = $text.IndexOf('"name"')
    if ($key -lt 0) { throw "this weapon file has no name field" }
    $colon = $text.IndexOf(':', $key)
    $open = $text.IndexOf('"', $colon + 1)
    $close = $text.IndexOf('"', $open + 1)
    if ($colon -lt 0 -or $open -lt 0 -or $close -lt 0) { throw "this weapon file's name is not a string" }
    return $text.Substring(0, $open + 1) + $newName + $text.Substring($close)
}

function New-Display($fireSound, $reloadSound = 'ywzj_vehicle:artillery_reload') {
    return @(
        "{",
        "`t`"type`": `"ywzj_vehicle:weapon`",",
        "`t`"sounds`": {",
        "`t`t`"fire`": `"$fireSound`",",
        "`t`t`"reload`": `"$reloadSound`"",
        "`t}",
        "}"
    ) -join "`n"
}

# What each round the addon *adds* to the pack is, as parameters rather than as a finished file.
#
# The name lives here and nowhere else: the JSON is built from it further down, inside the loop, so
# there is no second copy of it to drift out of step - which is what would happen if the builder
# were called in this literal and the name also written here for the read-back to check against.
#
# Kind picks the builder, because a 30mm autocannon round and a 125mm tank round share nothing but
# the file format.
$rounds = @(
    @{
        # The 125mm high-explosive shell, and the round every Soviet-derived tank in the pack was
        # built to also throw. One round on four tanks: the T-72B3M and the three T-90Ms are the
        # same gun (2A46M-5) and the same ammunition.
        Id         = 'vvp_ywzj:2a46m5_125mm_he'
        Name       = '3OF26-125mm HE'
        # Each with its own anchor, because the four do not open their lists the same way: the
        # T-90Ms carry the T-90M dart and the T-72B3M the generic one, though it is the same gun.
        Vehicles   = @(
            @{ Path = 'data/vvp_ywzj/vehicles/t72_b3m.json'
               ArrayHead = '["vvp_ywzj:2a46m5_125mm_apfsds"' },
            @{ Path = 'data/vvp_ywzj/vehicles/t90_m.json'
               ArrayHead = '["vvp_ywzj:2a46m5_125mm_t90m_apfsds"' },
            @{ Path = 'data/vvp_ywzj/vehicles/t90_pror_2022.json'
               ArrayHead = '["vvp_ywzj:2a46m5_125mm_t90m_apfsds"' },
            @{ Path = 'data/vvp_ywzj/vehicles/t90_pror_2024.json'
               ArrayHead = '["vvp_ywzj:2a46m5_125mm_t90m_apfsds"' }
        )
        Kind       = 'cannon'
        Caliber    = 125
        TracerG    = 0.5
        Velocity   = 12
        Display    = New-Display 'vvp_ywzj:125mm_2a46_shot'
    },
    @{
        Id         = 'vvp_ywzj:kew_120mm_apfsds'
        Name       = 'KE-W-120mm APFSDS'
        Vehicle    = 'data/vvp_ywzj/vehicles/m1a1_ukr.json'
        # The list this one goes into is the one the swap below has just rewritten, so this head is
        # the *post-swap* first element. Order matters: see where the swaps run.
        ArrayHead  = '["vvp_ywzj:kew_120mm_apfsds"'
        Kind       = 'cannon'
        Caliber    = 120
        TracerG    = 0.6
        Velocity   = 12
        Display    = New-Display 'ywzj_vehicle:cannon_125mm_shot'
    },
    @{
        Id         = 'vvp_ywzj:2a46m1_125mm_he'
        Name       = '3OF26-125mm HE'
        Vehicle    = 'data/vvp_ywzj/vehicles/t64.json'
        # The ammo list is found by its first entry and the new id inserted before its closing
        # bracket, rather than by matching the list's current last entry - which changes every time
        # a round is added.
        ArrayHead  = '["vvp_ywzj:2a46m1_125mm_apfsds"'
        Kind       = 'cannon'
        Caliber    = 125
        TracerG    = 0.5
        Velocity   = 12
        Display    = New-Display 'vvp_ywzj:125mm_2a46_shot'
    },
    @{
        Id         = 'vvp_ywzj:2a46m1_125mm_he_vt'
        Name       = '3OF26-VT-125mm HE-VT'
        Vehicle    = 'data/vvp_ywzj/vehicles/t64.json'
        ArrayHead  = '["vvp_ywzj:2a46m1_125mm_apfsds"'
        Kind       = 'cannon'
        Caliber    = 125
        TracerG    = 0.5
        Velocity   = 12
        # The fuze itself is the base mod's: AmmoEntity looks for entities in a box around the
        # shell expanded along its flight path and detonates on its own. Nothing in the addon
        # implements the detection; this file is what switches it on.
        ProximityFuze = $true
        Display    = New-Display 'vvp_ywzj:125mm_2a46_shot'
    },
    @{
        Id         = 'vvp_ywzj:m256_120mm_he'
        Name       = 'M908-120mm HE'
        Enabled    = $addAbramsHe
        Vehicle    = 'data/vvp_ywzj/vehicles/m1a1.json'
        ArrayHead  = '["vvp_ywzj:m256_120mm_apfsds"'
        Kind       = 'cannon'
        Caliber    = 120
        TracerG    = 0.5
        Velocity   = 12
        Display    = New-Display 'ywzj_vehicle:cannon_125mm_shot'
    },
    @{
        Id         = 'vvp_ywzj:m256_120mm_heat_mp'
        Name       = 'M830A1-120mm MPAT'
        Vehicle    = 'data/vvp_ywzj/vehicles/m1a1.json'
        ArrayHead  = '["vvp_ywzj:m256_120mm_apfsds"'
        Kind       = 'cannon'
        Caliber    = 120
        TracerG    = 0.7
        Velocity   = 15
        # Identical to the plain HEAT round, because its impact fuze is meant to be: same shell,
        # same velocity, same explosion, and a nudged tracer colour so the two can be told apart
        # when loaded. The proximity and delay fuzes are the addon's - it arms them on a private
        # copy of this explosion when the round is fired - so nothing here switches one on. In
        # particular the round must NOT carry "proximity_fuze": that would make the base mod sweep
        # for targets on every shot, whatever the gunner had selected.
        Display    = New-Display 'ywzj_vehicle:cannon_125mm_shot'
    },
    @{
        Id         = 'vvp_ywzj:ztm1_30mm_apfsds'
        # The round's own designation, because the round is what a gunner reads: the two rounds of a
        # belt are two separate weapons in a group and the game prints the name of the one he is on.
        # See $belts for how the two are tied together.
        Name       = '3UBR8-30mm APFSDS'
        Vehicle    = 'data/vvp_ywzj/vehicles/btr_3.json'
        # The BTR-3's cannon currently lists its one shell as a bare string, not as a one-element
        # array, so there is no list to append to until one is made. Wrap is that step; see the loop.
        Wrap       = '"vvp_ywzj:ztm1_30mm"'
        ArrayHead  = '["vvp_ywzj:ztm1_30mm"'
        Kind       = 'auto'
        TracerG    = 1.0
        # Faster than the high-explosive shell's 20: it is a dart, not a shell.
        Velocity   = 25
        Explosive  = $false
        # The 2A72's own cadence and feed, which the pack's HE round for this gun already carries.
        Damage     = 45
        Recoil     = 0.2
        Interval   = 182
        Capacity   = 300
        ReloadTime = 80
        Display    = New-Display 'ywzj_vehicle:auto_cannon_shot' 'ywzj_vehicle:gun_reload'
    },
    @{
        # "给 M1296 发放钢针" - the Stryker's XM813 has only ever had the high-explosive shell the pack
        # gives it, so the gunner of a Dragoon had nothing to shoot armour with.
        #
        # The round is the American one for this gun and not the BTR's: the XM813 fires 30x173, and
        # 3UBR8 is a 30x165 round for the 2A72. Same calibre, different cartridge - the same mistake
        # as calling a 125mm shell a 120mm one because both are "tank rounds".
        Id         = 'vvp_ywzj:xm813_30mm_apfsds'
        # The round, for the same reason as the BTR's dart above: MK258 is what this gun fires.
        Name       = 'MK258-30mm APFSDS'
        Vehicle    = 'data/vvp_ywzj/vehicles/stryker_m1296.json'
        # This turret already lists its main gun inside a weapons array - the machine gun and the
        # smoke launcher follow it there - so there is nothing to wrap, only a list to append to.
        #
        # The anchor is the quoted id alone, with no opening bracket, because this vehicle's file is
        # written out one field per line: the bracket sits on the line above the id. The rounds above
        # can name the bracket because their vehicles are minified, which is the only reason their
        # anchors read that way - and the loop only ever looks for the first ']' *after* the anchor,
        # so naming the list by its first element works in both layouts.
        ArrayHead  = '"vvp_ywzj:xm813_30mm"'
        Kind       = 'auto'
        TracerG    = 1.0
        # Faster than this gun's high-explosive shell's 16, and the same as the BTR's dart.
        Velocity   = 25
        Explosive  = $false
        # The XM813's own numbers, from the pack's own file for it: a slower cannon than the 2A72
        # with less in the feed, and neither is the round's business to change.
        Damage     = 40
        Recoil     = 0.3
        Interval   = 300
        Capacity   = 120
        ReloadTime = 100
        Display    = New-Display 'ywzj_vehicle:auto_cannon_shot' 'ywzj_vehicle:gun_reload'
    }
)

# Rounds a vehicle carries but should be carrying a different one of: a straight substitution inside
# that vehicle's ammo list.
#
# Not the same edit as adding a round, and not expressible as one. The four Abrams share a single
# dart id, so giving the Ukrainian one its own round means taking the shared one *out* - appending
# would leave the tank holding both, and the gunner choosing between a round it fires and a round
# the rest of the family fires.
#
# Run before $rounds, and that order is load-bearing: the round added below goes into the list this
# rewrites, so it has to find the list already in its post-swap form.
$swaps = @(
    @{
        # Ukraine's M1A1 SA fires the export tungsten round - General Dynamics' KE-W - rather than
        # the DU M829 the American tanks carry.
        Vehicle = 'data/vvp_ywzj/vehicles/m1a1_ukr.json'
        From    = 'vvp_ywzj:m256_120mm_apfsds'
        To      = 'vvp_ywzj:kew_120mm_apfsds'
    }
)

# Rounds the pack already had, which only need renaming - their files are not ours to rewrite, so
# their ids stay exactly as they are and nothing that already refers to one is disturbed.
#
# Designations are the ones these rounds actually carry:
#   3BM42 "Mango"   the 125mm dart the 2A46M-1 fires
#   3BK18M          its shaped charge
#   M829 / M830     the Abrams' dart and its HEAT-MP-T
#   3BM60 "Svinets-2"  the 2A46M-5's dart, which the T-90M fires by default. The pack ships it
#                   called "2A46M-5 125mm (APFSDS) (T-90M)" - the gun, not the round - which is
#                   the same thing the T-64's dart was called before it was renamed.
#
# The two 30mm entries at the end are the exception: they are belt names, not round names, for the
# reason given there. Everything else here is named after the shell it fires.
#
# The T-72B3M's dart is the same round under a second id, because the pack files it under its own
# weapon. It gets the same name: the two entries sit side by side in the compendium as two rows for
# one shell, and a reader who is told the T-90M's dart is 3BM60 and the T-72B3M's is "2A46M-5 125mm
# (APFSDS)" has been told there are two rounds. There are not - it is one gun and one round.
#
# A rename applies to every vehicle that carries the round, which is the point: m256_120mm_apfsds
# is on all four Abrams variants and 2a46m1_* is the T-64's, so one edit names them all.
$renames = @(
    @{ Id = 'vvp_ywzj:2a46m1_125mm_apfsds'; Name = '3BM42-125mm APFSDS' },
    @{ Id = 'vvp_ywzj:2a46m1_125mm_heat';   Name = '3BK18M-125mm HEAT' },
    @{ Id = 'vvp_ywzj:m256_120mm_apfsds';   Name = 'M829-120mm APFSDS' },
    @{ Id = 'vvp_ywzj:m256_120mm_heat';     Name = 'M830-120mm HEAT' },
    @{ Id = 'vvp_ywzj:2a46m5_125mm_t90m_apfsds'; Name = '3BM60-125mm APFSDS' },
    @{ Id = 'vvp_ywzj:2a46m5_125mm_apfsds';      Name = '3BM60-125mm APFSDS' },

    # The two 30mm high-explosive rounds: these are shells, and they are named after the shell.
    #
    # Their vehicles' belts are named after nothing, because a belt no longer exists as a weapon the
    # player can see - see $belts. What the gunner reads in the selector is the round he is on: the
    # dart or the shell, by its real designation, whichever of the two he has switched to.
    @{ Id = 'vvp_ywzj:ztm1_30mm';           Name = '3UOF8-30mm HE' },
    @{ Id = 'vvp_ywzj:xm813_30mm';          Name = 'XM813-30mm HE' }
)

# The two rounds of a dual-feed cannon, tied into the belt that holds them.
#
# A belt is not a weapon of its own: it is an "ids" group in the vehicle's weapons list - the form
# the pack already uses for the BMP-3's turret missile - and the two rounds inside it stay the two
# separate weapons they are, each with its own projectile, its own explosion and its own tracer. The
# gunner cycles *within* a belt with the multi-weapon key (F) and changes belt with the primary
# weapon switch, which is the mouse wheel.
#
# That grouping is the whole point. A round carried as a *load* of another round can only change
# numbers: a load is the same weapon fired with different figures, so it inherits the weapon's
# projectile - and the pack's high-explosive shell carries the only explosion either of these guns
# has, which left a dart loaded into that belt with no blast to gain and a shell loaded into the
# dart's belt with one to lose. Two weapons in a group have no such limit. The price is that a group
# has no name of its own: what a gunner reads in the selector is the round he is on, by its real
# designation, and not the belt it came out of.
#
# Both belts hold both rounds and differ only in which one they arrive on - the same two ids in the
# opposite order - which is exactly what a dual feed is. 'save_id' is spelled out rather than left to
# default: a group written as a bare array is saved under the fixed name "multi_weapons", so two of
# them on one vehicle would share a slot and the belt a gunner left would be the belt he comes back
# to.
$belts = @(
    @{
        Vehicle   = 'data/vvp_ywzj/vehicles/btr_3.json'
        Gun       = 'ZTM-1'
        # Belt 1 arrives with the dart in it - a gunner who has just switched to the cannon wants the
        # round that can do something to armour - and belt 2 with the high-explosive shell.
        Dart      = 'vvp_ywzj:ztm1_30mm_apfsds'
        Shell     = 'vvp_ywzj:ztm1_30mm'
    },
    @{
        Vehicle   = 'data/vvp_ywzj/vehicles/stryker_m1296.json'
        Gun       = 'XM813'
        Dart      = 'vvp_ywzj:xm813_30mm_apfsds'
        Shell     = 'vvp_ywzj:xm813_30mm'
    }
)

if (-not (Test-Path "$pack.bak")) {
    Copy-Item $pack "$pack.bak"
    Write-Host "backed up to $pack.bak"
} else {
    Write-Host "backup already exists, leaving it alone: $pack.bak"
}

$utf8 = New-Object System.Text.UTF8Encoding($false)
$zip = [System.IO.Compression.ZipFile]::Open($pack, [System.IO.Compression.ZipArchiveMode]::Update)

function Set-Entry($archive, $name, $text) {
    $existing = $archive.GetEntry($name)
    if ($existing) {
        # Rewriting an entry means removing it first; the archive is rewritten in place.
        $existing.Delete()
        Write-Host "  replaced $name"
    } else {
        Write-Host "  added    $name"
    }
    $entry = $archive.CreateEntry($name)
    $writer = New-Object System.IO.StreamWriter($entry.Open(), $utf8)
    $writer.Write($text)
    $writer.Dispose()
}

function Remove-Entry($archive, $name) {
    $existing = $archive.GetEntry($name)
    if ($existing) {
        $existing.Delete()
        Write-Host "  removed  $name"
    }
}

function Get-Text($archive, $name) {
    $entry = $archive.GetEntry($name)
    if (-not $entry) { throw "$name not found in the pack" }
    $reader = New-Object System.IO.StreamReader($entry.Open(), [System.Text.Encoding]::UTF8)
    $text = $reader.ReadToEnd()
    $reader.Dispose()
    return $text
}

# Takes one weapon id out of a vehicle's weapons list, and whichever comma joined it to the rest
# with it. Which comma that is depends on where in the list the id sits - the first element is
# joined by the one after it, every other by the one before - so this looks both ways rather than
# assuming a layout: half this pack's vehicle files are minified and half are one field per line.
#
# The id is matched quoted, so an id that merely starts with another one cannot be hit by mistake.
function Remove-WeaponId($text, $quoted) {
    $at = $text.IndexOf($quoted)
    if ($at -lt 0) { throw "$quoted is not in this file - the pack layout changed" }

    $before = $at - 1
    while ($before -ge 0 -and [char]::IsWhiteSpace($text[$before])) { $before-- }
    if ($before -ge 0 -and $text[$before] -eq ',') {
        return $text.Remove($before, $at + $quoted.Length - $before)
    }

    $after = $at + $quoted.Length
    while ($after -lt $text.Length -and [char]::IsWhiteSpace($text[$after])) { $after++ }
    if ($after -ge $text.Length -or $text[$after] -ne ',') {
        throw "$quoted is neither first nor a middle element of a list - the pack layout changed"
    }
    return $text.Remove($at, $after + 1 - $at)
}

# Before the rounds: the round added below goes into the list one of these rewrites.
foreach ($swap in $swaps) {
    $text = Get-Text $zip $swap.Vehicle
    $from = '"' + $swap.From + '"'
    $to = '"' + $swap.To + '"'
    Write-Host "swap $($swap.From) -> $($swap.To) in $($swap.Vehicle)"
    if ($text.Contains($to) -and -not $text.Contains($from)) {
        Write-Host "  already swapped"
        continue
    }
    if (-not $text.Contains($from)) {
        throw "$($swap.Vehicle) does not carry $($swap.From) - the pack layout changed"
    }
    Set-Entry $zip $swap.Vehicle $text.Replace($from, $to)
}

# One shape for the loop to work with, whichever way the round was written: a round carries either
# a single Vehicle with an ArrayHead beside it, or a Vehicles list of {Path, ArrayHead} pairs.
function Get-Targets($round) {
    if ($round.ContainsKey('Vehicles')) {
        return $round.Vehicles
    }
    return @(@{ Path = $round.Vehicle; ArrayHead = $round.ArrayHead })
}

foreach ($round in $rounds) {
    $name = $round.Id.Split(':')[1]
    # Quoted, so the check cannot be satisfied by a longer id that merely starts the same way -
    # "..._125mm_heat" contains "..._125mm_he", which would silently skip the edit.
    $quoted = '"' + $round.Id + '"'
    # A round with no switch of its own is always on.
    $on = -not $round.ContainsKey('Enabled') -or $round.Enabled

    Write-Host "round $($round.Id)  [$(if ($on) { 'on' } else { 'off' })]"

    # The files are the round's, not the vehicle's, so they are written once however many vehicles
    # carry it - and removed once when it is switched off.
    if ($on) {
        $weapon = if ($round.Kind -eq 'auto') {
            New-AutoCannon $round.Name $round.Velocity $round.TracerG $round.Explosive `
                $round.Damage $round.Recoil $round.Interval $round.Capacity $round.ReloadTime
        } else {
            New-Cannon $round.Name $round.Caliber $round.TracerG $round.Velocity $round.ProximityFuze
        }
        Set-Entry $zip "data/vvp_ywzj/weapons/$name.json" ($weapon + "`n")
        Set-Entry $zip "assets/vvp_ywzj/display/weapon/$name.json" ($round.Display + "`n")
    } else {
        Remove-Entry $zip "data/vvp_ywzj/weapons/$name.json"
        Remove-Entry $zip "assets/vvp_ywzj/display/weapon/$name.json"
    }

    foreach ($target in (Get-Targets $round)) {
        $text = Get-Text $zip $target.Path

        # A weapon that is the only one in its slot is written as a bare string, so giving it a
        # second shell means turning that string into an array first. Its own step rather than part
        # of the append below, because the append only knows how to look *inside* brackets - and it
        # is the append that then finds the newly-made list and adds to it.
        #
        # Guarded on the bracket so re-running is a no-op. Turning a wrapped round off again leaves
        # the one-element array behind, which the base mod reads exactly as it read the bare string.
        if ($on -and $round.ContainsKey('Wrap') -and -not $text.Contains('[' + $round.Wrap)) {
            $text = $text.Replace($round.Wrap, '[' + $round.Wrap + ']')
            Write-Host "  wrapped $($round.Wrap) into a shell list"
        }

        $listed = $text.Contains($quoted)
        if ($on) {
            if ($listed) {
                Write-Host "  $($target.Path) already lists it"
                continue
            }
            $head = $text.IndexOf($target.ArrayHead)
            if ($head -lt 0) {
                throw "could not find $($target.ArrayHead) in $($target.Path) - the pack layout changed"
            }
            $close = $text.IndexOf(']', $head)
            $text = $text.Substring(0, $close) + ', ' + $quoted + $text.Substring($close)
            Set-Entry $zip $target.Path $text
        } else {
            if ($listed) {
                Set-Entry $zip $target.Path $text.Replace(', ' + $quoted, '')
            } else {
                Write-Host "  $($target.Path) does not list it"
            }
        }
    }
}

# After the rounds: both of a belt's rounds have to be in the vehicle's list before they can be put
# into a group together. Before the renames, which do not care either way.
foreach ($belt in $belts) {
    $text = Get-Text $zip $belt.Vehicle
    $dart = '"' + $belt.Dart + '"'
    $shell = '"' + $belt.Shell + '"'
    $belt1 = '{"ids": [' + $dart + ', ' + $shell + '], "save_id": "' + $belt.Gun + '_1"}'
    $belt2 = '{"ids": [' + $shell + ', ' + $dart + '], "save_id": "' + $belt.Gun + '_2"}'
    Write-Host "belts $($belt.Gun) in $($belt.Vehicle)"

    if ($text.Contains($belt1) -and $text.Contains($belt2)) {
        Write-Host "  already grouped"
        continue
    }

    # The two rounds sat either as two entries of the weapons list or, on the BTR-3, inside an array
    # of their own - the shape the Wrap step above leaves behind, which is one entry. Emptying that
    # array gives the other case; the pair is rewritten in a known order while it happens, so the
    # rest of this does not have to care which way round it was.
    foreach ($pair in @(('[' + $shell + ', ' + $dart + ']'), ('[' + $dart + ', ' + $shell + ']'))) {
        if ($text.Contains($pair)) {
            $text = $text.Replace($pair, $dart + ', ' + $shell)
            Write-Host "  un-nested a pair that was an array of its own"
        }
    }

    # Exactly once each, or the replace below reaches a copy of the id held somewhere else in the
    # file - a part's own weapon reference, say - and quietly rewrites that instead.
    foreach ($id in @($dart, $shell)) {
        $count = ($text.Split([string[]]@($id), [StringSplitOptions]::None).Length - 1)
        if ($count -ne 1) { throw "$($belt.Vehicle) mentions $id $count times, expected once" }
    }

    # The belts take the place of whichever round came first in the list, not the place of the dart:
    # the order of a turret's weapons is the order the primary switch walks them, and the first is
    # what a gunner is holding when he climbs in. Appending the pair would hand him the machine gun.
    if ($text.IndexOf($dart) -lt $text.IndexOf($shell)) { $early = $dart; $late = $shell }
    else { $early = $shell; $late = $dart }

    # A placeholder rather than a delete-and-insert, so the position survives the removal of the
    # other id - which may be on either side of it.
    $marker = '@@BELTS@@'
    $text = $text.Replace($early, $marker)
    $text = Remove-WeaponId $text $late
    Set-Entry $zip $belt.Vehicle $text.Replace($marker, $belt1 + ', ' + $belt2)
}

foreach ($rename in $renames) {
    $name = $rename.Id.Split(':')[1]
    $path = "data/vvp_ywzj/weapons/$name.json"
    $text = Get-Text $zip $path
    if ($text.Contains('"' + $rename.Name + '"')) {
        Write-Host "  $($rename.Id) is already called $($rename.Name)"
        continue
    }
    Set-Entry $zip $path (Set-WeaponName $text $rename.Name)
}

$zip.Dispose()

# Read the pack back and say what is actually in it now, rather than trusting the writes above.
Write-Host ""
Write-Host "verifying the pack as written:"
$zip = [System.IO.Compression.ZipFile]::OpenRead($pack)
$problems = 0
foreach ($swap in $swaps) {
    $entry = $zip.Entries | Where-Object { $_.FullName -eq $swap.Vehicle } | Select-Object -First 1
    $reader = New-Object System.IO.StreamReader($entry.Open(), [System.Text.Encoding]::UTF8)
    $text = $reader.ReadToEnd(); $reader.Dispose()
    # Both directions checked: the new round present *and* the old one gone. Only the first would
    # pass a swap that left the tank carrying both, which is the whole failure this guards.
    $hasNew = $text.Contains('"' + $swap.To + '"')
    $hasOld = $text.Contains('"' + $swap.From + '"')
    if (-not $hasNew -or $hasOld) {
        Write-Host "  FAIL $($swap.Vehicle): new round $hasNew, old round still there $hasOld"
        $problems++
    } else {
        Write-Host "  OK   $($swap.Vehicle) now carries $($swap.To) and no longer $($swap.From)"
    }
    try { $null = $text | ConvertFrom-Json } catch {
        Write-Host "  FAIL $($swap.Vehicle) does not parse"; $problems++
    }
}
foreach ($round in $rounds) {
    $on = -not $round.ContainsKey('Enabled') -or $round.Enabled
    $name = $round.Id.Split(':')[1]
    $quoted = '"' + $round.Id + '"'
    # Every vehicle, not just the first: a round on four tanks is only really on all four if all
    # four are read back, and the one that got missed is the one nobody looks at.
    #
    # Which way the answer is read depends on the switch, and this is the trap the first version of
    # this loop fell into: for a round that is switched *off*, "the vehicle does not list it" is the
    # correct state, not a failure. So a switched-off round is only checked for absence.
    $allListed = $true
    $anyListed = $false
    foreach ($target in (Get-Targets $round)) {
        $vehicle = $zip.Entries | Where-Object { $_.FullName -eq $target.Path } | Select-Object -First 1
        $reader = New-Object System.IO.StreamReader($vehicle.Open(), [System.Text.Encoding]::UTF8)
        $text = $reader.ReadToEnd(); $reader.Dispose()
        try { $null = $text | ConvertFrom-Json } catch {
            Write-Host "  FAIL $($target.Path) does not parse"; $problems++
        }
        $here = $text.Contains($quoted)
        $anyListed = $anyListed -or $here
        if (-not $on) {
            $allListed = $allListed -and (-not $here)
            continue
        }
        if ($here) {
            Write-Host "  OK   $($target.Path) lists $($round.Id)"
        } else {
            $allListed = $false
            Write-Host "  FAIL $($target.Path) does not list $($round.Id)"
            $problems++
        }
    }
    $listed = $allListed

    $weapon = $zip.Entries | Where-Object { $_.FullName -eq "data/vvp_ywzj/weapons/$name.json" } | Select-Object -First 1
    if ($on) {
        if (-not $weapon) { Write-Host "  FAIL $name.json missing"; $problems++ }
        if ($weapon -and $listed) {
            $r2 = New-Object System.IO.StreamReader($weapon.Open(), [System.Text.Encoding]::UTF8)
            $wtext = $r2.ReadToEnd(); $r2.Dispose()
            $json = $null
            try { $json = $wtext | ConvertFrom-Json } catch { Write-Host "  FAIL $name.json does not parse"; $problems++ }
            $keys = if ($json) { $json.PSObject.Properties.Name } else { @() }
            $fuze = $keys -contains 'explosion' -and $json.explosion.proximity_fuze
            # Declared per round rather than sniffed, because the two answers mean opposite things:
            # the HE-VT shell is meant to go off beside its target every time, while a round whose
            # fuze the gunner switches must leave the base mod's own fuze off or it would sweep for
            # targets on every shot whatever was selected.
            $wantFuze = $round.ContainsKey('ProximityFuze') -and $round.ProximityFuze
            if ($fuze -ne $wantFuze) {
                Write-Host "  FAIL $($round.Id) ships with the base proximity fuze = $fuze"
                $problems++
            }
            # The base removes a shell once 'life' runs out, and the addon's longest delay fuze is
            # 100 ticks, so a round that pins 'life' has to pin it well clear of that. Leaving the
            # key out is the norm here: the base default is 1200.
            $life = if ($keys -contains 'life') { [int]$json.life } else { 1200 }
            if ($life -le 105) { Write-Host "  FAIL $name.json sets life=$life, too short for a delay fuze"; $problems++ }
            # Read back rather than assumed: the name is what the player reads in the weapon
            # selector and, now, in the compendium, and a file that quietly keeps its old one looks
            # exactly like a rename that worked.
            if (-not $json -or $json.name -ne $round.Name) {
                Write-Host "  FAIL $name.json is called '$($json.name)', expected '$($round.Name)'"
                $problems++
            }
            $fuzeText = if ($fuze) { 'proximity fuze' } else { 'impact' }
            Write-Host "  OK   $($round.Id)  [$fuzeText, life $life]  '$($round.Name)'"
        }
    } else {
        if ($weapon) { Write-Host "  FAIL $name.json still present while switched off"; $problems++ }
        if ($anyListed) { Write-Host "  FAIL a vehicle still lists $($round.Id)"; $problems++ }
        if (-not $weapon -and -not $anyListed) { Write-Host "  OK   $($round.Id)  [off]  absent" }
    }
}

foreach ($belt in $belts) {
    $entry = $zip.Entries | Where-Object { $_.FullName -eq $belt.Vehicle } | Select-Object -First 1
    $reader = New-Object System.IO.StreamReader($entry.Open(), [System.Text.Encoding]::UTF8)
    $text = $reader.ReadToEnd(); $reader.Dispose()
    try { $null = $text | ConvertFrom-Json } catch {
        Write-Host "  FAIL $($belt.Vehicle) does not parse"; $problems++
    }
    $dart = '"' + $belt.Dart + '"'
    $shell = '"' + $belt.Shell + '"'
    $belt1 = '{"ids": [' + $dart + ', ' + $shell + '], "save_id": "' + $belt.Gun + '_1"}'
    $belt2 = '{"ids": [' + $shell + ', ' + $dart + '], "save_id": "' + $belt.Gun + '_2"}'
    # Both belts, in both orders: a single group would leave the gunner with one feed and the F key
    # as his only control, which is a different vehicle from the one this is meant to build.
    if (-not $text.Contains($belt1) -or -not $text.Contains($belt2)) {
        Write-Host "  FAIL $($belt.Gun) does not carry both belts in $($belt.Vehicle)"
        $problems++
        continue
    }
    # Each round present twice and no more: once per belt. A round left loose in the list as well
    # would be a third weapon the primary switch walks past.
    $bad = $false
    foreach ($id in @($dart, $shell)) {
        $count = ($text.Split([string[]]@($id), [StringSplitOptions]::None).Length - 1)
        if ($count -ne 2) {
            Write-Host "  FAIL $($belt.Gun): $id appears $count times, expected twice (once per belt)"
            $problems++
            $bad = $true
        }
    }
    if (-not $bad) {
        Write-Host "  OK   $($belt.Gun) is two belts of two rounds, belt 1 on the dart"
    }
}

foreach ($rename in $renames) {
    $name = $rename.Id.Split(':')[1]
    $path = "data/vvp_ywzj/weapons/$name.json"
    $weapon = $zip.Entries | Where-Object { $_.FullName -eq $path } | Select-Object -First 1
    if (-not $weapon) { Write-Host "  FAIL $path missing"; $problems++; continue }
    $r2 = New-Object System.IO.StreamReader($weapon.Open(), [System.Text.Encoding]::UTF8)
    $wtext = $r2.ReadToEnd(); $r2.Dispose()
    $json = $null
    try { $json = $wtext | ConvertFrom-Json } catch { Write-Host "  FAIL $path does not parse"; $problems++ }
    if ($json -and $json.name -ne $rename.Name) {
        Write-Host "  FAIL $($rename.Id) is called '$($json.name)', expected '$($rename.Name)'"
        $problems++
        continue
    }
    if ($json) { Write-Host "  OK   $($rename.Id)  renamed to '$($rename.Name)'" }
}
$zip.Dispose()

Write-Host ""
if ($problems -gt 0) {
    Write-Host "$problems problem(s) - the pack is NOT in the state the switches describe." -ForegroundColor Red
    exit 1
}
Write-Host "Done." -ForegroundColor Green
