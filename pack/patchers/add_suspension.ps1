# Raises the suspension travel on the vehicles whose real counterpart has a hydropneumatic
# suspension, so the addon's ride-height keys have something to move.
#
# Usage:  powershell -ExecutionPolicy Bypass -File add_suspension.ps1
#
# Why this is needed at all, because "the pack already has springs" is true and not enough:
#
# A wheel's contact probe reaches `min(max_droop, rest_length + max_extension)` below its mount.
# That is not a rule of thumb - `SuspensionUnit.getMinCompression` is `max(-max_extension,
# rest_length - max_droop)`, and `findContacts` builds a probe whose far end is `rest_length -
# min_compression` away. The whole of the ride-height mechanism is lengthening that rest length, so
# that distance is the entire travel budget.
#
# `rest_length` is the problem in that expression. It is not the pack's `rest_length` field:
# `SuspensionUnit.calibrateRestLengths` overwrites it at init with whatever holds the vehicle's own
# weight, which neither this script nor the Java tools can compute. So the guarantee this script
# buys is the second branch never winning - `max_extension >= max_droop` - after which the travel is
# exactly `max_droop`, whatever the calibration came out at. `SuspensionCheck` asserts exactly that,
# which is what makes it the check for "the pack was never repatched".
#
# On the tracked vehicles there is a second thing to fix, and it is the reason a tracked vehicle
# could not have this feature before: the pack ships **max_droop 0.1 on every one of them** - all
# 28, to two decimal places - while the wheeled M1296 gets 0.26. A tenth of a block is about 7cm of
# usable hull movement after the resting centimetre the base mod calibrates in, which on a
# three-block-tall tank is invisible. So those six go to 0.32.
#
# The M1296 keeps its 0.26. It already has twice a tracked vehicle's travel, its behaviour is
# shipped, and nothing about the feature needs it to change - it only lacks the guarantee, because
# its max_extension is 0.22, below its own max_droop. Bringing that up to 0.26 is the whole change
# on it, and where the calibration already reached 0.26 it changes nothing at all.
#
# What this does NOT change is where a vehicle sits. The resting height falls out of
# `calibrateRestLengths`, which solves for the rest lengths that hold the vehicle's weight and never
# reads either of these two figures; raising them changes what the crew can ask for, not what they
# are given.
#
# Insert-only, in the sense the other three patchers mean it: this rewrites numeric values and no
# other byte. Nothing is re-serialised, because a round trip through a JSON formatter would re-spell
# every number in the file.
#
# Idempotent, and the read-back at the end proves what it claims: the same parts with the same ids,
# every suspension part at the new travel, and - the strong one - the file with the two edited keys
# struck out is byte-for-byte what it was.
#
# Order matters if rebuilding the pack from scratch: the pristine pack is the .bak next to it, so
# restore that, then run add_shells.ps1, add_snorkel.ps1, split_rear_armor.ps1, and this last. All
# four are idempotent and each verifies its own writes.

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

# --- what we are changing ---------------------------------------------------------------------
#
# Every vehicle here is here because the real one has a hydropneumatic suspension a crew could work
# - not because its model happens to have the parts. The T-64, the T-72B3M, the T-90Ms and the M1s
# have suspension parts too, and all of them have torsion bars in reality.
#
# `from` is the max_droop the pack ships, checked rather than assumed: a pack that has already been
# retuned - by this script on an earlier run, or by hand - does not match, and the script stops
# rather than flattening whoever's work that was. `to` is what both figures become.

$vehicles = @(
    # One chassis, three vehicles: the BMP-3 and its two derivatives all ride on the suspension
    # whose ground clearance the driver selects.
    @{ id = 'bmp_3';         from = '0.1';  to = '0.32' },
    @{ id = 'brm';           from = '0.1';  to = '0.32' },
    @{ id = 'chryzantema';   from = '0.1';  to = '0.32' },
    # Horstman Hydrostrut, and Horstman build a ride-height control system for the same family.
    @{ id = 'challenger';    from = '0.1';  to = '0.32' },
    # Hydropneumatic.
    @{ id = 'puma';          from = '0.1';  to = '0.32' },
    # Hydrogas, on the ASCOD platform.
    @{ id = 'ajax';          from = '0.1';  to = '0.32' },
    # The wheeled one: travel unchanged, guarantee added.
    @{ id = 'stryker_m1296'; from = '0.26'; to = '0.26' }
)

$partType = 'ywzj_vehicle:suspension'
$droop = 'max_droop'
$extension = 'max_extension'

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

# Matches the key, its colon and any spacing, and captures all of it, so the replacement puts the
# number back exactly where the file had it. The number itself is `[0-9.]+` rather than a parsed
# double: what comes out is what the regex saw plus a new literal.
function Set-Value($text, $key, $value) {
    return [regex]::Replace($text, '("' + $key + '"\s*:\s*)[0-9.]+', ('${1}' + $value))
}

# The same two keys with their numbers struck out, for the "nothing else moved" comparison.
function Without-Values($text) {
    $stripped = [regex]::Replace($text, '("' + $droop + '"\s*:\s*)[0-9.]+', '${1}')
    return [regex]::Replace($stripped, '("' + $extension + '"\s*:\s*)[0-9.]+', '${1}')
}

function Count-Key($text, $key) {
    return ([regex]::Matches($text, '"' + $key + '"')).Count
}

# --- patch ------------------------------------------------------------------------------------

$zip = [System.IO.Compression.ZipFile]::Open($pack, [System.IO.Compression.ZipArchiveMode]::Update)

$before = @{}
$after = @{}

foreach ($v in $vehicles) {
    $id = $v.id
    $to = $v.to
    $path = "data/vvp_ywzj/vehicles/$id.json"
    $text = Get-Text $zip $path
    $parsed = $text | ConvertFrom-Json

    $parts = @($parsed.parts)
    $springs = @($parts | Where-Object { $_.type -eq $partType })
    $ids = @($parts | ForEach-Object { $_.id })
    $before[$id] = @{ ids = $ids; count = $parts.Count }

    Write-Host ""
    Write-Host "${id}: $($parts.Count) parts, $($springs.Count) of them $partType"

    if ($springs.Count -eq 0) { throw "$id has no suspension parts - it should not be in this list" }

    # The count is the whole safety argument for editing text rather than re-serialising. If the two
    # keys appear exactly once per suspension part, and nowhere else, then replacing every occurrence
    # of each key can only touch suspension parts. If a future pack puts `max_droop` on something
    # else, this stops rather than quietly retuning it.
    $droopCount = Count-Key $text $droop
    $extensionCount = Count-Key $text $extension
    if ($droopCount -ne $springs.Count -or $extensionCount -ne $springs.Count) {
        throw ("$id has $($springs.Count) suspension parts but $droopCount '$droop' and " +
                "$extensionCount '$extension' keys - the pack layout changed")
    }

    # Either the figure the pack shipped, or the one this script already wrote. Anything else is
    # somebody's tuning, and the answer to that is to stop.
    $current = @($springs | ForEach-Object { "$($_.max_droop)" } | Sort-Object -Unique)
    if ($current.Count -ne 1 -or ($current[0] -ne $v.from -and $current[0] -ne $to)) {
        throw "$id's $droop is $($current -join ', ') - expected the shipped $($v.from) or $to"
    }

    $patched = Set-Value (Set-Value $text $droop $to) $extension $to
    if ($patched -eq $text) {
        Write-Host "  already at $to - nothing to do"
    } else {
        Set-Text $zip $path $patched
        Write-Host "  $droop and $extension -> $to on all $($springs.Count)"
    }
    # Recorded either way, so a second run is held to exactly the same check as the first.
    $after[$id] = $patched
}

$zip.Dispose()

# --- read the pack back -----------------------------------------------------------------------

Write-Host ""
Write-Host "verifying the pack as written:"
$zip = [System.IO.Compression.ZipFile]::OpenRead($pack)
$problems = 0

foreach ($v in $vehicles) {
    $id = $v.id
    $to = $v.to
    $path = "data/vvp_ywzj/vehicles/$id.json"
    $text = Get-Text $zip $path
    Write-Host ""
    Write-Host "  $id"

    $parsed = $text | ConvertFrom-Json
    $parts = @($parsed.parts)
    $ids = @($parts | ForEach-Object { $_.id })

    # A name list, not a count: this script changes no structure at all, so anything missing here is
    # something it has done to the file that it has no business having done.
    $lost = @($before[$id].ids | Where-Object { $ids -notcontains $_ })
    if ($lost.Count -gt 0) {
        Write-Host "    FAIL parts lost in the patch: $($lost -join ', ')"; $problems++
    } elseif ($parts.Count -ne $before[$id].count) {
        Write-Host "    FAIL the file has $($parts.Count) parts, it had $($before[$id].count)"; $problems++
    } else {
        Write-Host "    OK   all $($before[$id].count) parts are still there, by id"
    }

    $springs = @($parts | Where-Object { $_.type -eq $partType })
    $wrongDroop = @($springs | Where-Object { "$($_.max_droop)" -ne $to })
    $wrongExtension = @($springs | Where-Object { "$($_.max_extension)" -ne $to })
    if ($wrongDroop.Count -gt 0 -or $wrongExtension.Count -gt 0) {
        Write-Host ("    FAIL $($wrongDroop.Count) part(s) with the wrong $droop, " +
                "$($wrongExtension.Count) with the wrong $extension"); $problems++
    } else {
        Write-Host "    OK   $droop and $extension are $to on all $($springs.Count) springs"
    }

    # The strong one. Reading the two keys' values back out of the file leaves what the file was
    # before this script touched it - so if the two texts differ, something other than those two
    # numbers changed, whoever changed it.
    if ((Without-Values $text) -ne (Without-Values $after[$id])) {
        Write-Host "    FAIL something other than the two travel figures changed in this file"
        $problems++
    } else {
        Write-Host "    OK   nothing but those two numbers differs from what was written"
    }
}

$zip.Dispose()

Write-Host ""
if ($problems -gt 0) {
    Write-Host "$problems problem(s) - the pack is NOT in the state this script describes." -ForegroundColor Red
    exit 1
}
Write-Host "Done. Six tracked vehicles have 0.32 of wheel travel instead of 0.1, and the M1296's" -ForegroundColor Green
Write-Host "springs can reach the 0.26 it was always meant to have."
Write-Host "The ranges the crew can ask for live in the config, under 'suspensions'; SuspensionCheck"
Write-Host "re-derives the budget from these parts and fails if a range does not fit inside it."
