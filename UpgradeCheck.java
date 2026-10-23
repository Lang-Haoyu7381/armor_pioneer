import com.google.gson.Gson;
import com.ywzjvehicle.armor.ArmorConfig;
import com.ywzjvehicle.armor.Fording;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs ArmorConfig's upgrade path against a real config file, to prove an existing
 * install picks up newly introduced keys instead of silently ignoring them.
 *
 * Usage: UpgradeCheck [path-to-ywzj_vehicle_armor.json]
 *        Defaults to the 1.20.1-Forge_47.4.2 instance under MC_ROOT (default E:\.minecraft).
 */
public class UpgradeCheck {

    private static final String DEFAULT_CONFIG_DIR = "/versions/1.20.1-Forge_47.4.2/config/";

    /** What the mod itself calls its file, and what it called it before it was renamed. */
    private static final String CONFIG_NAME = "ywzj_vehicle_armor.json";
    private static final String PREVIOUS_CONFIG_NAME = "t64_armor.json";

    public static void main(String[] args) throws Exception {
        Path config = args.length > 0 ? Path.of(args[0]) : defaultConfig();
        System.out.println("file: " + config);

        // Use the mod's own reader so this parses exactly what the game would parse.
        ArmorConfig.Profile profile = ArmorConfig.configReader().fromJson(
                Files.readString(config, StandardCharsets.UTF_8), ArmorConfig.Profile.class);
        System.out.println("parsed OK, config_version=" + profile.config_version
                + " (current is " + currentVersion() + ")");

        System.out.println("vehicles before        : " + profile.vehicles.keySet());
        ArmorConfig.VehicleArmor t64 = profile.vehicles.get("vvp_ywzj:t64");
        System.out.println("t64 profile found      : " + (t64 != null));
        System.out.println("fire block before      : " + profile.destroyed_parts_cannot_fire);
        System.out.println("front multiplier before: " + profile.damage_multiplier.front);
        System.out.println("hull_front_split before: " + describe(t64));
        System.out.println("turret_weak_spot before: " + describeWeakSpot(t64));
        System.out.println("track_ends before      : " + describeTrackEnds(t64));
        System.out.println("overrides before       : " + describeOverrides(t64));
        System.out.println("ammo before            : " + describeAmmo(profile));

        Method upgrade = ArmorConfig.class.getDeclaredMethod("upgrade", ArmorConfig.Profile.class);
        upgrade.setAccessible(true);
        upgrade.invoke(null, profile);

        System.out.println("vehicles after         : " + profile.vehicles.keySet());
        System.out.println("fire block after       : " + profile.destroyed_parts_cannot_fire);
        System.out.println("front multiplier after : " + profile.damage_multiplier.front);
        System.out.println("hull_front_split after : " + describe(t64));
        System.out.println("turret_weak_spot after : " + describeWeakSpot(t64));
        System.out.println("track_ends after       : " + describeTrackEnds(t64));
        System.out.println("overrides after        : " + describeOverrides(t64));
        System.out.println("ammo after             : " + describeAmmo(profile));

        boolean splitOk = t64 != null && t64.hull_front_split != null
                && t64.hull_front_split.upper.thickness == 440.0F
                && t64.hull_front_split.upper.multiplier == 50.0F
                && t64.hull_front_split.lower.thickness == 120.0F
                && t64.hull_front_split.lower.multiplier == 5.0F;
        // The v9 -> v10 upgrade has to leave a usable 'heat' profile behind, or the HEAT round
        // silently falls back to the kinetic table and half the new feature does nothing.
        ArmorConfig.ArmorOverride heat = t64 == null ? null : t64.overrides.get("heat");
        boolean heatOk = heat != null && heat.hull_front_split != null
                && heat.hull_front_split.upper.thickness == 840.0F
                && heat.hull_front_split.upper.multiplier == 45.0F
                && heat.hull_side_era != null && heat.hull_side_era.thickness == 500.0F
                && heat.damage_multiplier.front == 45.0F;
        // The HE profile has to be the chemical table with three thin plates, not a partial block
        // that silently fell back to the vehicle's kinetic values: turret front 880 (not 480) is
        // what proves the difference, and the roofs are the whole point of the profile.
        ArmorConfig.ArmorOverride he = t64 == null ? null : t64.overrides.get("he");
        boolean heOk = he != null && he.turret != null && he.hull != null
                && he.turret.front == 880.0F && he.hull.front == 840.0F
                && he.turret.roof == 60.0F && he.hull.roof == 60.0F && he.hull.floor == 60.0F
                && he.turret.floor == 200.0F
                && he.hull_side_era != null && he.hull_side_era.thickness == 500.0F;
        ArmorConfig.Ammo heRoundPresent = profile.ammo_penetration.get("vvp_ywzj:2a46m1_125mm_he");
        boolean heRoundOk = heRoundPresent != null && heRoundPresent.penetration == 60.0F
                && "he".equals(heRoundPresent.profile)
                && heRoundPresent.penetration_damage != null
                && heRoundPresent.penetration_damage == 2000.0F
                && heRoundPresent.spallFallsOff()
                && heRoundPresent.spall_damage_max == 1000.0F
                && heRoundPresent.spall_damage_min == 300.0F
                && !heRoundPresent.spallOnPenetration();

        // v16 -> v17: the proximity-fuzed shell. It has to keep the pack's explosion rather than
        // replace it, carry no bursting charge, and scatter its blast whether or not it got
        // through - all three of which distinguish it from the plain HE round beside it.
        ArmorConfig.Ammo vtRound = profile.ammo_penetration.get("vvp_ywzj:2a46m1_125mm_he_vt");
        boolean vtOk = vtRound != null && vtRound.penetration == 60.0F
                && "he".equals(vtRound.profile)
                && vtRound.penetration_damage != null && vtRound.penetration_damage == 0.0F
                && !vtRound.replacesBaseExplosion()
                && vtRound.spallOnPenetration() && vtRound.hasSpall();

        // v19 -> v20: the BTR-3, a third vehicle and a third kind of running gear. Its glacis and
        // lower plate are the same thickness so there is no split, its gun mount is the turret front
        // so there is no weak spot, and what it does have is wheels.
        ArmorConfig.VehicleArmor btr = profile.vehicles.get("vvp_ywzj:btr_3");
        boolean btrOk = btr != null
                && btr.turret.front == 32.0F && btr.turret.side == 16.0F
                && btr.turret.roof == 8.0F && btr.turret.floor == 8.0F
                && btr.hull.front == 32.0F && btr.hull.rear == 16.0F
                && btr.hull.roof == 8.0F && btr.hull.floor == 8.0F
                // Unlike the two tanks this vehicle states its own multipliers, because its plates
                // are all equally lethal to get through and the global x50 front belongs to a tank.
                && btr.damage_multiplier != null && btr.damage_multiplier.front == 5.0F
                && btr.damage_multiplier.side == 5.0F && btr.damage_multiplier.floor == 5.0F
                && btr.road_wheels != null && btr.road_wheels.thickness == 20.0F
                // -0.283 and not the 0.04 that shipped until v43: the 0.6.1 pack raised the hull
                // box's floor to y +0.72 blocks, so the wheels - which did not move - sit below its
                // centre line and the fraction that describes them is negative. See roadWheelRegrade.
                && btr.road_wheels.multiplier == 1.5F && btr.road_wheels.top == -0.283F
                // Absent on purpose, not forgotten: the front face is one plate and the gun mount
                // is given the turret front's own numbers, so neither zone has anything to say.
                && btr.hull_front_split == null && btr.turret_weak_spot == null
                && btr.track_ends == null && btr.hull_side_era == null
                && btr.overrides.keySet().equals(java.util.Set.of("heat", "he"));
        ArmorConfig.ArmorOverride btrHeat = btr == null ? null : btr.overrides.get("heat");
        ArmorConfig.ArmorOverride btrHe = btr == null ? null : btr.overrides.get("he");
        boolean btrChemicalOk = btrHeat != null && btrHe != null
                && btrHeat.turret.front == 32.0F && btrHeat.hull.front == 32.0F
                && btrHeat.turret.roof == 8.0F && btrHeat.damage_multiplier.front == 4.0F
                && btrHeat.road_wheels != null && btrHeat.road_wheels.thickness == 30.0F
                && btrHeat.road_wheels.multiplier == 2.0F
                // The HE table is the HEAT one exactly - the design gave every plate the same
                // numbers - but it must still be a separate object, or editing one edits both.
                && btrHe != btrHeat
                && btrHe.turret.front == 32.0F && btrHe.hull.roof == 8.0F
                && btrHe.damage_multiplier.front == 4.0F
                && btrHe.road_wheels != null && btrHe.road_wheels.thickness == 30.0F;
        ArmorConfig.Ammo btrDart = profile.ammo_penetration.get("vvp_ywzj:ztm1_30mm_apfsds");
        ArmorConfig.Ammo btrHe30 = profile.ammo_penetration.get("vvp_ywzj:ztm1_30mm");
        ArmorConfig.Ammo btrMg = profile.ammo_penetration.get("vvp_ywzj:kt_7_62_7_62mm");
        boolean btrAmmoOk = btrDart != null && btrDart.penetration == 90.0F
                // No profile and no flat multiplier: the dart is measured against the vehicle's own
                // table with the vehicle's own multipliers, which is what keeps the wheels at x1.5
                // while every plate around them is x5. A flat multiplier here would flatten that.
                && btrDart.profile == null && btrDart.multiplier == null
                && btrHe30 != null && btrHe30.penetration == 6.0F
                && "he".equals(btrHe30.profile)
                // The 30mm HE keeps the pack's own small blast rather than replacing it: 10 damage
                // against 3000 health cannot drown out an armour table, which is the only reason
                // this addon ever drops one.
                && !btrHe30.replacesBaseExplosion() && !btrHe30.hasSpall()
                && btrHe30.penetration_damage == null
                && btrMg != null && btrMg.penetration == 15.0F && btrMg.multiplier == 3.0F
                && matches(profile, "vvp_ywzj:pkt_7_62mm", btrMg);
        System.out.println("BTR-3 profile present   : " + btrOk);
        System.out.println("BTR-3 chemical tables   : " + btrChemicalOk
                + "  (wheels " + (btrHeat == null || btrHeat.road_wheels == null
                        ? "-" : btrHeat.road_wheels.thickness + "mm x"
                                + btrHeat.road_wheels.multiplier) + ")");
        // v21 -> v22: the M829 family. The dart slot already exists in every config, so what arrives
        // is not a new entry but four new loads for the one that is there - and without the upgrade
        // step the four would exist only in this file while the Abrams kept firing the same dart.
        ArmorConfig.Ammo dart = profile.ammo_penetration.get("vvp_ywzj:m256_120mm_apfsds");
        boolean loadsOk = dart != null && dart.variantCount() == 5 && dart.selectable()
                && dart.variant(0) == dart && dart.variant(0).penetration == 570.0F
                && "M829A1".equals(dart.variant(1).name) && dart.variant(1).penetration == 600.0F
                && "M829A2".equals(dart.variant(2).name) && dart.variant(2).penetration == 630.0F
                && "M829A3".equals(dart.variant(3).name) && dart.variant(3).penetration == 700.0F
                && "M829A4".equals(dart.variant(4).name) && dart.variant(4).penetration == 780.0F
                // Every other slot has to be untouched, or the chooser would open over a round that
                // has nothing to choose.
                && !profile.ammo_penetration.get("vvp_ywzj:m256_120mm_heat").selectable()
                && !profile.ammo_penetration.get("vvp_ywzj:2a46m1_125mm_apfsds").selectable();
        System.out.println("M829 family loads       : " + loadsOk + "  ("
                + (dart == null ? "-" : String.join(" / ", dart.variantNames("M829"))) + ")");

        // v20 -> v21: every round is filed on a compendium shelf. The fallback would put the
        // machine guns and the autocannon rounds on the cannon shelves, so a file written before
        // the categories existed has to be told where they go - and told only where it says
        // nothing, so a round the player has moved stays moved.
        java.util.Map<String, com.ywzjvehicle.armor.AmmoCategory> expected = java.util.Map.ofEntries(
                java.util.Map.entry("vvp_ywzj:2a46m1_125mm_apfsds", com.ywzjvehicle.armor.AmmoCategory.CANNON_AP),
                java.util.Map.entry("vvp_ywzj:m256_120mm_apfsds", com.ywzjvehicle.armor.AmmoCategory.CANNON_AP),
                java.util.Map.entry("vvp_ywzj:2a46m1_125mm_heat", com.ywzjvehicle.armor.AmmoCategory.CANNON_HEAT),
                java.util.Map.entry("vvp_ywzj:m256_120mm_heat", com.ywzjvehicle.armor.AmmoCategory.CANNON_HEAT),
                java.util.Map.entry("vvp_ywzj:m256_120mm_heat_mp", com.ywzjvehicle.armor.AmmoCategory.CANNON_HEAT),
                java.util.Map.entry("vvp_ywzj:2a46m1_125mm_he", com.ywzjvehicle.armor.AmmoCategory.CANNON_HE),
                java.util.Map.entry("vvp_ywzj:2a46m1_125mm_he_vt", com.ywzjvehicle.armor.AmmoCategory.CANNON_HE),
                java.util.Map.entry("vvp_ywzj:ztm1_30mm_apfsds", com.ywzjvehicle.armor.AmmoCategory.AUTOCANNON_AP),
                java.util.Map.entry("vvp_ywzj:ztm1_30mm", com.ywzjvehicle.armor.AmmoCategory.AUTOCANNON_HE),
                java.util.Map.entry("vvp_ywzj:pkt_7_62mm", com.ywzjvehicle.armor.AmmoCategory.MACHINE_GUN),
                java.util.Map.entry("vvp_ywzj:nsvt_12_7mm", com.ywzjvehicle.armor.AmmoCategory.MACHINE_GUN),
                java.util.Map.entry("vvp_ywzj:m240_7_62mm", com.ywzjvehicle.armor.AmmoCategory.MACHINE_GUN),
                java.util.Map.entry("vvp_ywzj:m2_50cal", com.ywzjvehicle.armor.AmmoCategory.MACHINE_GUN),
                java.util.Map.entry("vvp_ywzj:kt_7_62_7_62mm", com.ywzjvehicle.armor.AmmoCategory.MACHINE_GUN));
        int misfiled = 0;
        int unfiled = 0;
        for (java.util.Map.Entry<String, ArmorConfig.Ammo> entry : profile.ammo_penetration.entrySet()) {
            com.ywzjvehicle.armor.AmmoCategory want = expected.get(entry.getKey());
            if (want == null) {
                continue;
            }
            if (entry.getValue().category == null) {
                unfiled++;
            } else if (entry.getValue().shelf() != want) {
                misfiled++;
                System.out.println("  MISFILED " + entry.getKey()
                        + " is under " + entry.getValue().shelf().id() + ", expected " + want.id());
            }
        }
        boolean categoriesOk = misfiled == 0 && unfiled == 0;
        System.out.println("compendium categories   : " + categoriesOk
                + "  (" + expected.size() + " checked, " + unfiled + " without a category, "
                + misfiled + " on the wrong shelf)");

        System.out.println("BTR-3 ammunition        : " + btrAmmoOk
                + "  (dart " + (btrDart == null ? "-" : btrDart.penetration + "mm")
                + ", HE " + (btrHe30 == null ? "-" : btrHe30.penetration + "mm") + ")");

        ArmorConfig.Ammo heatRound = profile.ammo_penetration.get("vvp_ywzj:2a46m1_125mm_heat");
        boolean heatRoundOk = heatRound != null && heatRound.penetration == 480.0F
                && "heat".equals(heatRound.profile) && heatRound.hasSpall()
                && heatRound.spall_blindness_ticks == 20
                // Without this the pack's 1500-damage explosion still lands on every hit and the
                // whole armour table is drowned out - the exact bug the v10 -> v11 step fixes.
                && heatRound.replacesBaseExplosion();

        // A whole vehicle arriving is the other half of v13 -> v14, and the upgrade used to skip
        // anything the file did not already have, so it needs checking in its own right.
        ArmorConfig.VehicleArmor abrams = profile.vehicles.get("vvp_ywzj:m1a1");
        boolean abramsOk = abrams != null
                && abrams.turret.front == 600.0F && abrams.turret.side == 250.0F
                && abrams.turret.roof == 100.0F && abrams.hull.side == 100.0F
                && abrams.hull.rear == 90.0F
                && abrams.hull_front_split != null
                && abrams.hull_front_split.upper.thickness == 800.0F
                && abrams.hull_front_split.upper.multiplier == 50.0F
                && abrams.hull_front_split.lower.thickness == 400.0F
                && abrams.hull_front_split.lower.multiplier == 10.0F
                // The split shipped wrong at v14 and again at v15, so a file at either version has
                // to be carried forward - it would otherwise keep a boundary nobody wants.
                && abrams.hull_front_split.split == 0.4F
                && abrams.turret_weak_spot != null && abrams.turret_weak_spot.thickness == 350.0F
                && abrams.track_ends != null && abrams.track_ends.thickness == 200.0F
                && abrams.overrides.keySet().equals(java.util.Set.of("heat", "he"))
                && abrams.hull_side_era == null;
        // v18 -> v19: the Abrams' machine guns. Same flat-multiplier shape as the T-64's, and the
        // same numbers, because they are the same calibres - a file without them would leave the
        // M240 and the M2HB hitting for the pack's raw 10 and 20 through the vanilla pipeline, which
        // is precisely what this addon exists to replace on the vehicles it governs.
        ArmorConfig.Ammo m240 = profile.ammo_penetration.get("vvp_ywzj:m240_7_62mm");
        ArmorConfig.Ammo m2 = profile.ammo_penetration.get("vvp_ywzj:m2_50cal");
        boolean abramsMgOk = m240 != null && m240.penetration == 15.0F && m240.multiplier != null
                && m240.multiplier == 3.0F && m240.profile == null
                && m2 != null && m2.penetration == 30.0F && m2.multiplier != null
                && m2.multiplier == 5.0F && m2.profile == null
                // Deliberately identical to the T-64's pair: the design asked for the same effect,
                // and the calibres line up one for one, so any difference here is a typo.
                && matches(profile, "vvp_ywzj:pkt_7_62mm", m240)
                && matches(profile, "vvp_ywzj:nsvt_12_7mm", m2);
        System.out.println("Abrams machine guns     : " + abramsMgOk
                + "  (M240 " + describe(m240) + "; M2HB " + describe(m2) + ")");

        ArmorConfig.Ammo abramsDart = profile.ammo_penetration.get("vvp_ywzj:m256_120mm_apfsds");
        boolean abramsAmmoOk = abramsDart != null && abramsDart.penetration == 570.0F
                && abramsDart.profile == null
                && profile.ammo_penetration.containsKey("vvp_ywzj:m256_120mm_heat")
                // No HE entry: the Abrams carries no such round, and an entry pointing at a weapon
                // that does not exist is a setting that can never do anything.
                && !profile.ammo_penetration.containsKey("vvp_ywzj:m256_120mm_he");

        // v17 -> v18: the multi-purpose round. Its impact half is a plain shaped charge, so the
        // entry can be present and look perfectly healthy while the second model - the entire
        // feature - is missing. Both halves are checked, and so is the polarity that separates
        // them: the impact fuze has to drop the pack's explosion and the proximity one has to
        // keep it, or the fuze would switch between two identical outcomes.
        ArmorConfig.Ammo mp = profile.ammo_penetration.get("vvp_ywzj:m256_120mm_heat_mp");
        boolean mpOk = mp != null && mp.multiPurpose() && mp.penetration == 480.0F
                && "heat".equals(mp.profile) && mp.replacesBaseExplosion()
                && mp.proximity_radius != null && mp.proximity_radius == 3.0F
                && mp.proximity != null
                && mp.proximity.penetration == 480.0F
                && "heat".equals(mp.proximity.profile)
                && !mp.proximity.replacesBaseExplosion()
                && mp.proximity.penetration_damage != null && mp.proximity.penetration_damage == 0.0F
                && mp.proximity.hasSpall() && mp.proximity.spallOnPenetration()
                // The two models must not be the same object: a copy would make the proximity
                // fuze read the impact numbers and every check above would still pass.
                && mp.proximity != mp;

        // The nested block is the one thing here that a serializer can silently drop, and a dropped
        // block degrades the round back to a plain HEAT without any error. So the config is written
        // out and read back in, and the round has to come out of that round trip whole.
        ArmorConfig.Profile roundTripped = ArmorConfig.configReader().fromJson(
                ArmorConfig.configWriter().toJson(profile), ArmorConfig.Profile.class);
        ArmorConfig.Ammo mpAgain = roundTripped.ammo_penetration.get("vvp_ywzj:m256_120mm_heat_mp");
        boolean mpRoundTripOk = mpAgain != null && mpAgain.multiPurpose()
                && mpAgain.proximity != null
                && mpAgain.proximity.penetration == 480.0F
                && "heat".equals(mpAgain.proximity.profile)
                && mpAgain.proximity_radius != null && mpAgain.proximity_radius == 3.0F
                && mpAgain.replacesBaseExplosion() && !mpAgain.proximity.replacesBaseExplosion()
                && mpAgain.proximity.spallFallsOff();
        System.out.println("multi-purpose round     : " + mp);
        System.out.println("  impact   : " + (mp == null ? "-" : describe(mp)));
        System.out.println("  proximity: " + (mp == null || mp.proximity == null
                ? "-" : describe(mp.proximity)));

        // The Panther, from the second vehicle pack. Its armour-piercing round is the first to state
        // its damage as a roll against the plate rather than a share of the residual, and three new
        // keys is three chances for a serializer to drop one - so the round is written out and read
        // back, and the band it comes back with has to be the band that went in.
        ArmorConfig.VehicleArmor panther = profile.vehicles.get("tank_assault:panther");
        ArmorConfig.Ammo pantherAp = profile.ammo_penetration.get("tank_assault:cannon_75mm_pzgr_39_42");
        boolean pantherOk = panther != null && panther.hull_front_split != null
                // Half and half, and the lower plate thinner than the glacis. A config written
                // before either was corrected still says -0.3 and 200, so these two numbers are
                // what tells a migration that ran from one that did not.
                && panther.hull_front_split.split == 0.0F
                && panther.hull_front_split.lower != null
                && panther.hull_front_split.lower.thickness == 120.0F
                && panther.hull_front_split.upper != null
                && panther.hull_front_split.upper.thickness == 200.0F
                // The machine-gun port. It is a block added to a vehicle the file already had, so
                // it arrives through the backfill list and nowhere else - and if it does not arrive
                // there is no error, only a port that cannot be hit.
                && panther.hull_weak_spot != null && panther.hull_weak_spot.enabled
                && panther.hull_weak_spot.thickness == 100.0F
                && panther.hull_weak_spot.radius == 0.3F
                && panther.hull_weak_spot.offset_x == 0.604F
                && panther.hull_weak_spot.offset_y == 0.700F
                && panther.track_ends != null && panther.track_ends.inner_edge == 0.589F
                && panther.turret_weak_spot != null && panther.turret_weak_spot.thickness == 180.0F
                && panther.turret_part_ids.length == 3
                // The HE table is the HEAT one plate for plate; if it were ever pointed at the same
                // object this would still pass, so it is the numbers that are compared, not identity.
                && samePlates(panther.overrides.get("he"), panther.overrides.get("heat"))
                && pantherAp != null && pantherAp.hasPenetrationBonus()
                && pantherAp.penetration == 192.0F
                && pantherAp.penetration_bonus_min == 20.0F
                && pantherAp.penetration_bonus_max == 200.0F
                && pantherAp.penetration_bonus_max_at == 200.0F;

        ArmorConfig.Profile pantherTripped = ArmorConfig.configReader().fromJson(
                ArmorConfig.configWriter().toJson(profile), ArmorConfig.Profile.class);
        ArmorConfig.Ammo apAgain =
                pantherTripped.ammo_penetration.get("tank_assault:cannon_75mm_pzgr_39_42");
        boolean pantherRoundTripOk = apAgain != null && apAgain.hasPenetrationBonus()
                && apAgain.penetration_bonus_min == 20.0F
                && apAgain.penetration_bonus_max == 200.0F
                && apAgain.penetration_bonus_max_at == 200.0F
                // Cheap at the ends, expensive in the middle: the band has to widen with the plate,
                // which is the whole mechanic and is not something a stored number can be checked
                // for by looking at it.
                && apAgain.penetrationBonusCeiling(0.0F) == 20.0F
                && apAgain.penetrationBonusCeiling(200.0F) == 200.0F
                && apAgain.penetrationBonusCeiling(100.0F) == 110.0F
                && pantherTripped.vehicles.containsKey("tank_assault:panther");

        System.out.println("Panther profile present : " + pantherOk);
        System.out.println("  AP roll survives trip : " + pantherRoundTripOk);
        System.out.println("  AP bonus at 0/100/200mm: "
                + (apAgain == null ? "-" : apAgain.penetrationBonusCeiling(0.0F) + " / "
                        + apAgain.penetrationBonusCeiling(100.0F) + " / "
                        + apAgain.penetrationBonusCeiling(200.0F)));
        // The bonus is an addition, not a replacement: through a 50mm flank with the plate's own x5,
        // a hit has to come out between 142x5+20 and 142x5+65 - and the two ends are checked apart so
        // that a round which quietly went back to one or the other is caught at the boundary.
        float flank = pantherAp == null ? 0.0F
                : pantherAp.penetrationDamage(142.0F, 5.0F, 50.0F);
        System.out.println("  AP through 50mm flank  : " + flank
                + "  (residual 142 x5, plus 20-65)");
        pantherRoundTripOk &= flank >= 730.0F && flank <= 775.0F;

        // The Tiger E, the early-hull Tiger I with the drum. New profile + three new rounds. Armour
        // numbers come from the same source - 100mm turret front, 60mm glacis / 100mm nose lower -
        // and are checked by themselves, because the kit being shipped is the proof.
        ArmorConfig.VehicleArmor tiger = profile.vehicles.get("tank_assault:tiger");
        ArmorConfig.Ammo pzgr39 = profile.ammo_penetration.get("tank_assault:cannon_88mm_pzgr_39");
        ArmorConfig.Ammo pzgr40 = profile.ammo_penetration.get("tank_assault:cannon_88mm_pzgr_40");
        ArmorConfig.Ammo sprgr43 = profile.ammo_penetration.get("tank_assault:cannon_88mm_sprgr_43");
        boolean tigerOk = tiger != null
                && tiger.turret != null && tiger.turret.front == 100.0F
                && tiger.hull != null && tiger.hull.front == 60.0F
                && tiger.hull_front_split != null
                && tiger.hull_front_split.split == 0.0F
                && tiger.hull_front_split.upper != null
                && tiger.hull_front_split.upper.thickness == 60.0F
                && tiger.hull_front_split.lower != null
                && tiger.hull_front_split.lower.thickness == 100.0F
                && tiger.turret_weak_spot != null
                && tiger.turret_weak_spot.offset_x == -0.08F
                && tiger.turret_weak_spot.offset_y == 0.066F
                && tiger.track_ends != null && tiger.track_ends.inner_edge == 0.591F
                && tiger.turret_part_ids.length == 3
                && pzgr39 != null && pzgr39.penetration == 165.0F
                && pzgr39.hasPenetrationBonus()
                && pzgr39.replacesBaseExplosion()
                && pzgr40 != null && pzgr40.penetration == 238.0F
                && pzgr40.replacesBaseExplosion()
                && sprgr43 != null && sprgr43.penetration == 60.0F
                && "he".equals(sprgr43.profile)
                && sprgr43.replacesBaseExplosion();
        System.out.println("Tiger E profile present : " + tigerOk + "  (turret 100, glacis 60 / lower 100, PzGr39 165)");

        // v42 -> v43: the two road-wheel strips, which the 0.6.1 pack moved by moving the box they
        // are a fraction of. The shipped figures have to be gone afterwards, and a figure a player
        // has edited has to survive - so this is checked on a scratch copy carrying the old number
        // for one vehicle and a hand-set number for the other.
        ArmorConfig.Profile wheelsTrip = ArmorConfig.configReader().fromJson(
                ArmorConfig.configWriter().toJson(profile), ArmorConfig.Profile.class);
        ArmorConfig.VehicleArmor btrWheels = wheelsTrip.vehicles.get("vvp_ywzj:btr_3");
        ArmorConfig.VehicleArmor strykerWheels = wheelsTrip.vehicles.get("vvp_ywzj:stryker_m1296");
        btrWheels.road_wheels = new ArmorConfig.RoadWheels();
        btrWheels.road_wheels.top = 0.040F;          // the figure that shipped, to be moved
        strykerWheels.road_wheels = new ArmorConfig.RoadWheels();
        strykerWheels.road_wheels.top = 0.111F;      // a player's own number, to be left alone
        upgrade.invoke(null, wheelsTrip);
        boolean wheelMigrationOk = wheelsTrip.vehicles.get("vvp_ywzj:btr_3").road_wheels.top == -0.283F
                && wheelsTrip.vehicles.get("vvp_ywzj:stryker_m1296").road_wheels.top == 0.111F;
        System.out.println("road-wheel regrade      : " + wheelMigrationOk
                + "  (shipped 0.040 -> " + wheelsTrip.vehicles.get("vvp_ywzj:btr_3").road_wheels.top
                + ", hand-set 0.111 -> " + wheelsTrip.vehicles.get("vvp_ywzj:stryker_m1296").road_wheels.top + ")");

        // v43 -> v44: the suspension moved off each vehicle's armour block into a map of its own,
        // and six tracked vehicles whose real counterpart has a hydropneumatic suspension were
        // given one. The seven are named rather than counted, because the count is not the point:
        // the six tracked ones are vehicles this addon has never had an armour profile for, which
        // is the whole reason the block had to move.
        com.ywzjvehicle.armor.Suspension.Settings strykerSuspension =
                profile.suspensions.get("vvp_ywzj:stryker_m1296");
        boolean suspensionsOk = profile.suspensions.size() == 7
                // The Stryker is the wheeled one, and its range is the one that has shipped since
                // v38 - no drop at all, because its belly is drawn level with its wheels.
                && strykerSuspension != null
                && strykerSuspension.max_rise == 0.15F && strykerSuspension.max_drop == 0.0F
                && strykerSuspension.max_pitch == 2.0F
                // The six tracked ones: one chassis three times over (BMP-3, BRM-3K, Khrizantema),
                // then the Challenger 2 with its Horstman Hydrostrut, the Puma, and the Ajax.
                && java.util.Arrays.stream(new String[]{"vvp_ywzj:bmp_3", "vvp_ywzj:brm",
                                "vvp_ywzj:chryzantema", "vvp_ywzj:challenger", "vvp_ywzj:puma",
                                "vvp_ywzj:ajax"})
                        .map(profile.suspensions::get)
                        .allMatch(s -> s != null && s.max_rise == 0.20F && s.max_drop == 0.20F
                                && s.max_pitch == 2.0F)
                // And the ones that must not have one. Every tracked vehicle in the pack has springs
                // the mechanism could drive - 0.6.1 gave all of them ten to fourteen - so what
                // separates a tank with a ride-height control from one without is a fact about the
                // real vehicle, not about its model. These all have torsion bars, and the failure
                // this guards is a later edit quietly handing one of them a feature it never had.
                && java.util.Arrays.stream(new String[]{"vvp_ywzj:t64", "vvp_ywzj:t72_b3m",
                                "vvp_ywzj:t90_m", "vvp_ywzj:m1a1", "vvp_ywzj:btr_3",
                                "vvp_ywzj:leopard_2a4", "tank_assault:panther"})
                        .allMatch(id -> profile.suspensions.get(id) == null);
        System.out.println("adjustable suspensions : " + suspensionsOk
                + "  (7 named, " + profile.suspensions.size() + " in the map)");

        // The move itself, on a scratch copy in the shape a v43 file is in: the block inside the
        // vehicle, and no map at all. Two different failures live here - the player's own range
        // being dropped on the way across, and the six new vehicles never arriving.
        ArmorConfig.Profile suspensionsTrip = ArmorConfig.configReader().fromJson(
                ArmorConfig.configWriter().toJson(profile), ArmorConfig.Profile.class);
        suspensionsTrip.suspensions = null;
        com.ywzjvehicle.armor.Suspension.Settings theirs =
                new com.ywzjvehicle.armor.Suspension.Settings();
        theirs.max_rise = 0.07F;
        suspensionsTrip.vehicles.get("vvp_ywzj:stryker_m1296").suspension = theirs;
        upgrade.invoke(null, suspensionsTrip);
        boolean suspensionMigrationOk = suspensionsTrip.suspensions != null
                && suspensionsTrip.suspensions.size() == 7
                && suspensionsTrip.suspensions.get("vvp_ywzj:stryker_m1296") != null
                && suspensionsTrip.suspensions.get("vvp_ywzj:stryker_m1296").max_rise == 0.07F
                && suspensionsTrip.suspensions.get("vvp_ywzj:challenger") != null
                && suspensionsTrip.vehicles.get("vvp_ywzj:stryker_m1296").suspension == null;
        System.out.println("suspension migration    : " + suspensionMigrationOk
                + "  (a v43 block carried across at 0.07, the six new vehicles added)");

        // v44 -> v45: TACZ's cartridges. A whole new map, so a file written before it has no key at
        // all - the fill has to create it as well as populate it, which is the one shape this table
        // differs from every other ammunition addition in.
        //
        // The gun mod is not needed for any of this: the table is config, and the migration only
        // moves numbers. What does need TACZ present is TaczCheck, which holds the ids in this table
        // against the cartridges the installed gun mod actually has.
        boolean taczOk = profile.tacz_rounds != null && !profile.tacz_rounds.isEmpty()
                && profile.tacz_rounds.get("tacz:50bmg") != null
                && profile.tacz_rounds.get("tacz:50bmg").penetration == 20.0F
                && "heat".equals(profile.tacz_rounds.get("tacz:rpg_rocket").profile);
        ArmorConfig.Profile taczTrip = ArmorConfig.configReader().fromJson(
                ArmorConfig.configWriter().toJson(profile), ArmorConfig.Profile.class);
        taczTrip.tacz_rounds = null;
        upgrade.invoke(null, taczTrip);
        boolean taczMigrationOk = taczTrip.tacz_rounds != null
                && taczTrip.tacz_rounds.size() == profile.tacz_rounds.size()
                && taczTrip.tacz_rounds.get("tacz:50bmg").penetration == 20.0F;
        System.out.println("TACZ cartridges         : " + (taczOk && taczMigrationOk)
                + "  (" + profile.tacz_rounds.size() + " rated, migration " + taczMigrationOk + ")");

        // What a screen can do is two fields on classes Gson writes by reflection - there is no
        // hand-written serializer for a Plate or a VehicleArmor, so nothing would notice if one
        // stopped being written. A round trip on a copy of the profile, with the T-64's flank
        // panels given values to lose, is what says they survive.
        ArmorConfig.Profile screenTrip = ArmorConfig.configReader().fromJson(
                ArmorConfig.configWriter().toJson(profile), ArmorConfig.Profile.class);
        ArmorConfig.VehicleArmor screenVehicle = screenTrip.vehicles.get("vvp_ywzj:t64");
        screenVehicle.hull_side_era = new ArmorConfig.Plate(20.0F, 2.0F);
        screenVehicle.hull_side_era.penetration_loss = 150.0F;
        screenVehicle.hull_side_era_min_angle = 40.0F;
        ArmorConfig.Profile screenBack = ArmorConfig.configReader().fromJson(
                ArmorConfig.configWriter().toJson(screenTrip), ArmorConfig.Profile.class);
        ArmorConfig.VehicleArmor backVehicle = screenBack.vehicles.get("vvp_ywzj:t64");
        boolean screenOk = backVehicle != null && backVehicle.hull_side_era != null
                && backVehicle.hull_side_era.thickness == 20.0F
                && backVehicle.hull_side_era.penetration_loss == 150.0F
                && backVehicle.hull_side_era_min_angle == 40.0F;
        System.out.println("screen loss + angle     : " + screenOk
                + (backVehicle == null || backVehicle.hull_side_era == null ? ""
                        : "  (loss " + backVehicle.hull_side_era.penetration_loss
                                + "mm, from " + backVehicle.hull_side_era_min_angle + " degrees)"));

        // v26 -> v27: the three T-90Ms, the first vehicles to be given anything their own model
        // cannot state - a value per add-on box, and a flank screen that is worth nothing to a dart
        // and a great deal to a jet. The glacis boundary shipped with its sign the wrong way round,
        // so the corrected figure is checked here and the migration below moves only the shipped one.
        boolean t90mOk = true;
        for (String id : new String[]{"vvp_ywzj:t90_m", "vvp_ywzj:t90_pror_2022",
                "vvp_ywzj:t90_pror_2024"}) {
            ArmorConfig.VehicleArmor t90m = profile.vehicles.get(id);
            ArmorConfig.ArmorOverride chemical = t90m == null ? null : t90m.overrides.get("heat");
            t90mOk &= t90m != null && t90m.hull_front_split != null
                    && t90m.hull_front_split.upper != null && t90m.hull_front_split.lower != null
                    // Half and half. The boundary is a normalised height with +1 at the top of the
                    // hull box, so a value of 0 is the middle - and note that the same "upper two
                    // thirds" would be a NEGATIVE number, which is what the T-72B3M still writes and
                    // what the migration below is about.
                    && t90m.hull_front_split.split == 0.0F
                    && t90m.hull_front_split.upper.thickness == 700.0F
                    && t90m.hull_front_split.lower.thickness == 150.0F
                    // And the hull's own front face is kept level with the glacis, so turning the
                    // split off falls back to the tank's real front plate and not a weaker one.
                    && t90m.hull.front == 700.0F
                    // The screen is chemical-only. A slat screen does not slow a dart down, and a
                    // base table carrying one would say that it does.
                    && t90m.hull_side_era == null
                    && t90m.hull_side_era_front_fraction == 1.0F
                    && t90m.hull_side_era_min_angle == 30.0F
                    && t90m.overrides.keySet().equals(java.util.Set.of("heat", "he"))
                    && chemical != null && chemical.hull_side_era != null
                    && chemical.hull_side_era.thickness == 30.0F
                    && chemical.hull_side_era.multiplier == 4.0F
                    && chemical.hull_side_era.penetration_loss == 400.0F
                    // 700 on the chemical table too, which is where the kinetic figure came from -
                    // so the glacis is the same plate to both kinds of round and only the multiplier
                    // tells them apart. The lower plate is not: it stays 150 against 200.
                    && chemical.hull_front_split != null
                    && chemical.hull_front_split.upper.thickness == 700.0F
                    && chemical.hull_front_split.lower.thickness == 200.0F;
        }
        // The bolt-ons, keyed by the part unit each one is on the model. The keys come from the
        // variant's own part list rather than from a flag beside it, so what is checked is that the
        // list and the map agree - a block valued on a variant that has no such part would be a
        // setting that can never do anything, and nothing else would report it.
        ArmorConfig.VehicleArmor t90m2024 = profile.vehicles.get("vvp_ywzj:t90_pror_2024");
        ArmorConfig.ArmorOverride t90m2024Heat =
                t90m2024 == null ? null : t90m2024.overrides.get("heat");
        boolean t90mPartsOk = partArmorIs(profile, "vvp_ywzj:t90_m",
                java.util.Set.of("additional_armor"))
                && partArmorIs(profile, "vvp_ywzj:t90_pror_2022",
                        java.util.Set.of("additional_armor", "additional_armor_rear",
                                "era_1", "era_2", "era_3", "era_4"))
                && partArmorIs(profile, "vvp_ywzj:t90_pror_2024",
                        java.util.Set.of("additional_armor", "additional_armor_rear",
                                "additional_armor_top", "era_1", "era_2", "era_3", "era_4", "era_5"))
                // And the boxes are worth different amounts to the two kinds of round, which is the
                // whole reason they are separate entries rather than a raised turret face.
                && t90m2024 != null && t90m2024Heat != null
                // The turret's blocks carry the hull's figures, because they are the same Relikt
                // over plates of the same order - and because the 700/1100 they shipped with were
                // calibrated against the turret's 650mm FRONT, which is not the face they sit on.
                && samePlate(t90m2024.part_armor.get("era_3"), new ArmorConfig.Plate(300.0F, 8.0F))
                && samePlate(t90m2024Heat.part_armor.get("era_3"),
                        new ArmorConfig.Plate(600.0F, 6.0F))
                && samePlate(t90m2024.part_armor.get("additional_armor_top"),
                        new ArmorConfig.Plate(40.0F, 5.0F))
                && samePlate(t90m2024Heat.part_armor.get("additional_armor_top"),
                        new ArmorConfig.Plate(300.0F, 4.0F))
                // Every reactive block on the tank, hull and turret alike, carries one pair of
                // figures. What distinguishes them is where the model draws them, which is measured
                // and not stated here - so what this asserts is the one thing the config is saying.
                && t90mBlocksMatch(profile, "vvp_ywzj:t90_pror_2022")
                && t90mBlocksMatch(profile, "vvp_ywzj:t90_pror_2024")
                // And the plate across the back of the turret, which is a part of its own only
                // because the pack was patched to make it one. A config that values a part the pack
                // does not have is armour nothing can hit, and VehicleScaffold fails on it as well -
                // this is the half of the pair that catches a value the patch made reachable and the
                // config got wrong.
                && t90mRearPlate(profile, "vvp_ywzj:t90_pror_2022")
                && t90mRearPlate(profile, "vvp_ywzj:t90_pror_2024")
                // And the HE table carries the same boxes. The two are built by copying the chemical
                // table, so a field the copy forgets falls back to the vehicle's kinetic value: the
                // boxes would then stop a jet and let a high-explosive round through, which is
                // backwards and is not visible in either table on its own.
                && samePlates(t90m2024Heat, t90m2024.overrides.get("he"));
        // Printed as coverage rather than as the raw boundary, because the number is a normalised
        // height and "glacis 67%" is the statement anyone can check against the tank.
        String glacis = t90m2024 == null || t90m2024.hull_front_split == null ? "-"
                : String.format("%.2f, glacis %.0f%% of the front face",
                        t90m2024.hull_front_split.split,
                        (1.0F - t90m2024.hull_front_split.split) * 50.0F);
        System.out.println("T-90M profiles present  : " + t90mOk + "  (" + glacis + ")");
        System.out.println("T-90M bolt-on parts     : " + t90mPartsOk + "  (2024: "
                + (t90m2024 == null ? "-" : t90m2024.part_armor.keySet()) + ")");
        // The blocks one by one, thickness and multiplier, because "the turret's carry the hull's
        // figures" is the whole of what this release changed about them and a mismatched pair looks
        // exactly like a matched one in a list of names.
        System.out.println("T-90M reactive blocks   : "
                + plates(t90m2024 == null ? null : t90m2024.part_armor, "kinetic") + " / "
                + plates(t90m2024Heat == null ? null : t90m2024Heat.part_armor, "chemical"));
        boolean t90mMigrationOk = t90mGlacisMigration(upgrade);

        // v27 -> v28: the BTR-3's ATGM, the first tandem warhead here - a round whose precursor charge is
        // aimed at the reactive armour the main charge would otherwise be wasted on. The flag is one
        // boolean, and a serializer that dropped it would leave a perfectly ordinary shaped charge
        // that stops at the first reactive block: no error, no log, just a missile that no longer
        // does the one thing it is carried for.
        ArmorConfig.Ammo rk2s = profile.ammo_penetration.get("vvp_ywzj:rk2s_barrier");
        boolean tandemOk = rk2s != null && rk2s.tandem() && rk2s.penetration == 800.0F
                && "heat".equals(rk2s.profile) && rk2s.hasSpall()
                && rk2s.shelf() == com.ywzjvehicle.armor.AmmoCategory.CANNON_HEAT;
        ArmorConfig.Ammo rk2sAgain = roundTripped.ammo_penetration.get("vvp_ywzj:rk2s_barrier");
        boolean tandemRoundTripOk = rk2sAgain != null && rk2sAgain.tandem()
                && rk2sAgain.penetration == 800.0F && "heat".equals(rk2sAgain.profile)
                // And nothing else acquired the flag on the way through: it is written only when
                // true, so a round that came back tandem would mean the writer defaults it on.
                && !roundTripped.ammo_penetration.get("vvp_ywzj:2a46m1_125mm_heat").tandem()
                && !roundTripped.ammo_penetration.get("vvp_ywzj:m256_120mm_heat_mp").tandem();
        System.out.println();
        System.out.println("RK-2S tandem warhead     : " + tandemOk
                + "  (survives a round trip: " + tandemRoundTripOk + ")");

        // v28 -> v29: which panels are reactive armour, and which kind. Nothing about the tandem
        // round changed - what changed is what the panels do about it - so the two kinds have to be
        // on the right panels or the round behaves as though the armour were plain steel.
        ArmorConfig.VehicleArmor t64Armor = profile.vehicles.get("vvp_ywzj:t64");
        ArmorConfig.ArmorOverride t64Heat = t64Armor == null ? null : t64Armor.overrides.get("heat");
        boolean t64EraOk = t64Heat != null && t64Heat.hull_side_era != null
                && "light".equals(t64Heat.hull_side_era.era)
                // The HE table is a copy of the chemical one, so it carries the kind too - a panel
                // that is reactive against one kind of round and not the other is not a thing.
                && t64Armor.overrides.get("he") != null
                && "light".equals(t64Armor.overrides.get("he").hull_side_era.era);
        boolean t90mEraOk = true;
        for (String id : new String[]{"vvp_ywzj:t90_m", "vvp_ywzj:t90_pror_2022",
                "vvp_ywzj:t90_pror_2024"}) {
            ArmorConfig.VehicleArmor t90m = profile.vehicles.get(id);
            ArmorConfig.ArmorOverride chemical = t90m == null ? null : t90m.overrides.get("heat");
            if (t90m == null || chemical == null) {
                t90mEraOk = false;
                continue;
            }
            for (String block : new String[]{"era_1", "era_2", "era_3", "era_4", "era_5"}) {
                ArmorConfig.Plate hard = t90m.part_armor.get(block);
                ArmorConfig.Plate soft = chemical.part_armor.get(block);
                // Only the variants that carry the block at all - the plain T-90M has none, and a
                // box valued on a variant with no such part is the mistake the part list prevents.
                boolean bothAbsent = hard == null && soft == null;
                t90mEraOk &= bothAbsent || (hard != null && soft != null
                        && "heavy".equals(hard.era) && "heavy".equals(soft.era));
            }
        }
        ArmorConfig.Profile eraTrip = ArmorConfig.configReader().fromJson(
                ArmorConfig.configWriter().toJson(profile), ArmorConfig.Profile.class);
        ArmorConfig.ArmorOverride t64Trip = eraTrip.vehicles.get("vvp_ywzj:t64").overrides.get("heat");
        boolean eraRoundTripOk = "light".equals(t64Trip.hull_side_era.era)
                && "heavy".equals(eraTrip.vehicles.get("vvp_ywzj:t90_pror_2024")
                        .part_armor.get("era_3").era)
                // The hull boxes are new keys, so they are new ground for the writer too: a kind
                // that survives on the turret's blocks and not on the hull's would be a difference
                // in how they are spelled, not in what they are.
                && "heavy".equals(eraTrip.vehicles.get("vvp_ywzj:t90_pror_2022")
                        .part_armor.get("era_1").era)
                // And nothing else grew a kind. Every plate that is not a reactive panel has to come
                // back without one: a writer that defaulted the key on would quietly turn ordinary
                // steel - a gun mount, a track strip - into reactive armour.
                && t64Trip.track_ends.era == null && t64Trip.turret_weak_spot.era == null;
        boolean eraMigrationOk = eraMigration(upgrade);
        boolean boxMigrationOk = boxMigration(upgrade);
        boolean dartMigrationOk = dartMigration(upgrade);

        // The rest of the M1 family: the A2, the Ukrainian A1 and the SEP. All four are the same
        // tank as far as this addon is concerned, so what is checked is that they came out identical
        // where they should be and differ only by the tiles their models actually carry - a variant
        // given tiles it does not have would be a box valued on a part that is not there.
        String[] family = {"vvp_ywzj:m1a1", "vvp_ywzj:m1a2", "vvp_ywzj:m1a1_ukr", "vvp_ywzj:m1a2_sep"};
        boolean familyOk = true;
        for (String id : family) {
            ArmorConfig.VehicleArmor mine = profile.vehicles.get(id);
            familyOk &= mine != null
                    // Everything but the frontal armour is the same on all four: same sides, rear,
                    // roof and floor, same multipliers, same glacis boundary, same track strips.
                    && mine.turret.side == abrams.turret.side
                    && mine.turret.rear == abrams.turret.rear
                    && mine.turret.roof == abrams.turret.roof
                    && mine.turret.floor == abrams.turret.floor
                    && mine.hull.side == abrams.hull.side
                    && mine.hull.rear == abrams.hull.rear
                    && mine.hull.roof == abrams.hull.roof
                    && mine.hull.floor == abrams.hull.floor
                    // hull.front is deliberately not compared: it is dead while the split is on,
                    // and each generation keeps it equal to its own glacis so that turning the split
                    // off falls back to something right for that tank rather than to the M1A1's.
                    && sameMultiplier(mine.damage_multiplier, abrams.damage_multiplier)
                    && mine.hull_front_split != null
                    && mine.hull_front_split.split == abrams.hull_front_split.split
                    && mine.track_ends != null && mine.track_ends.inner_edge == 0.69F
                    && mine.overrides.keySet().equals(java.util.Set.of("heat", "he"))
                    // No flank screen on any of them: whatever they wear is a part, not a band.
                    && mine.hull_side_era == null;
        }
        // And the frontal armour is where they part company, by generation. The three numbers below
        // are the researched ladder - the M1A1 as the design gives it, the A2 with the intermediate
        // depleted-uranium array, and the SEP at the published 940mm figure for the frontal arc.
        // Asserted rather than left to the config, because a copy-paste slip here is invisible:
        // every one of these numbers is a plausible thickness for an Abrams.
        boolean duOk = frontalIs(profile, "vvp_ywzj:m1a1", 600.0F, 800.0F, 400.0F, 1000.0F)
                // The Ukrainian tank is an M1A1: the SA rebuild added sights, not an array.
                && frontalIs(profile, "vvp_ywzj:m1a1_ukr", 600.0F, 800.0F, 400.0F, 1000.0F)
                && frontalIs(profile, "vvp_ywzj:m1a2", 800.0F, 1000.0F, 500.0F, 1250.0F)
                && frontalIs(profile, "vvp_ywzj:m1a2_sep", 940.0F, 1150.0F, 600.0F, 1500.0F)
                // And the HE table follows the chemical one, or a high-explosive round would be
                // measured against a different turret front than the shaped charge beside it.
                && chemicalFront(profile, "vvp_ywzj:m1a2_sep") == 1500.0F;
        boolean duMigrationOk = duMigration(upgrade);
        boolean tilesOk = partArmorIs(profile, "vvp_ywzj:m1a1", java.util.Set.of())
                && partArmorIs(profile, "vvp_ywzj:m1a2", java.util.Set.of())
                && partArmorIs(profile, "vvp_ywzj:m1a1_ukr",
                        java.util.Set.of("era_1", "era_2"))
                && partArmorIs(profile, "vvp_ywzj:m1a2_sep",
                        java.util.Set.of("era_1", "era_2", "era_3", "era_4"));
        ArmorConfig.VehicleArmor sep = profile.vehicles.get("vvp_ywzj:m1a2_sep");
        ArmorConfig.ArmorOverride sepHeat = sep == null ? null : sep.overrides.get("heat");
        ArmorConfig.VehicleArmor ukr = profile.vehicles.get("vvp_ywzj:m1a1_ukr");
        ArmorConfig.ArmorOverride ukrHeat = ukr == null ? null : ukr.overrides.get("heat");
        boolean tilesValued = sep != null && sepHeat != null && ukr != null && ukrHeat != null
                // Each figure is the tile over the armour it sits on - hull side 100 + 40, turret
                // side 250 + 40, and 400 of tile over 200 or 400. Written as the tile's own worth
                // the round would simply go through it, since one zone decides a hit.
                && samePlate(ukr.part_armor.get("era_1"), new ArmorConfig.Plate(140.0F, 5.0F))
                && samePlate(ukrHeat.part_armor.get("era_1"), new ArmorConfig.Plate(600.0F, 4.0F))
                && samePlate(sep.part_armor.get("era_3"), new ArmorConfig.Plate(290.0F, 5.0F))
                && samePlate(sepHeat.part_armor.get("era_3"), new ArmorConfig.Plate(800.0F, 4.0F))
                // ARAT is a light tile: a tandem warhead gets through it, a plain shaped charge
                // does not. A heavy one here would stop the tandem round instead.
                && "light".equals(ukrHeat.part_armor.get("era_1").era)
                && "light".equals(sepHeat.part_armor.get("era_3").era)
                // The SEP's part list is the only one of the four that is not the M1A1's.
                && java.util.Arrays.asList(sep.turret_part_ids).contains("turret_mg4")
                && java.util.Arrays.asList(sep.turret_part_ids).contains("era_3")
                && !java.util.Arrays.asList(ukr.turret_part_ids).contains("era_1");
        System.out.println();
        boolean m1Ok = familyOk && tilesOk && tilesValued && duOk && duMigrationOk;
        System.out.println("M1 family               : " + m1Ok
                + "  (four vehicles; tiles on "
                + (tilesOk ? "the two whose models carry them" : "WRONG SET")
                + ", DU armour " + duOk + ", migration " + duMigrationOk + ")");

        // The T-72B3M: the fourth tank, and the first one whose reactive armour is a band with no
        // part behind it. It shares the T-90M's gun and its glacis shape, so the numbers that matter
        // here are the ones that would look right if they had been copied from the wrong tank - the
        // older hull's thinner sides, and the absence of an add-on box this model has no part for.
        ArmorConfig.VehicleArmor t72 = profile.vehicles.get("vvp_ywzj:t72_b3m");
        ArmorConfig.ArmorOverride t72Heat = t72 == null ? null : t72.overrides.get("heat");
        boolean t72Ok = t72 != null && t72.turret != null && t72.hull != null
                && t72.turret.front == 480.0F && t72.turret.side == 160.0F
                && t72.turret.rear == 80.0F && t72.turret.roof == 40.0F && t72.turret.floor == 40.0F
                && t72.hull.front == 480.0F && t72.hull.side == 80.0F
                && t72.hull.rear == 60.0F && t72.hull.roof == 40.0F && t72.hull.floor == 40.0F
                // The glacis boundary is the T-90M's, sign and all: the same hull shape drawn by the
                // same hand, so the same half-and-half line. At +0.33 the thin plate covers the
                // bottom half and the thick one the top, and no row of the table says so.
                && t72.hull_front_split != null && t72.hull_front_split.split == 0.0F
                && t72.hull_front_split.upper.thickness == 480.0F
                && t72.hull_front_split.lower.thickness == 140.0F
                && t72.turret_weak_spot != null && t72.turret_weak_spot.thickness == 380.0F
                && t72.turret_weak_spot.multiplier == 8.0F
                // Nothing bolted on: this model's only extra parts are its machine gun and its
                // smoke launchers. A box here would be armour on a part that is not there, and the
                // part list is what would catch it - so both are checked against the pack's parts.
                && t72.part_armor.isEmpty()
                && java.util.Set.of(t72.turret_part_ids).equals(java.util.Set.of(
                        "turret", "turret_machine_gun", "turret_smoke_grenade",
                        "commander_machine_gun"))
                // The flank screen is chemical-only, as the T-90M's is, and for the same reason: a
                // slat screen does not slow a dart down. On the kinetic table there is none.
                && t72.hull_side_era == null
                && t72.hull_side_era_front_fraction == 1.0F
                && t72.hull_side_era_min_angle == 30.0F
                && t72.overrides.keySet().equals(java.util.Set.of("heat", "he"))
                && t72Heat != null && t72Heat.hull_side_era != null
                && t72Heat.hull_side_era.thickness == 30.0F
                && t72Heat.hull_side_era.multiplier == 4.0F
                && t72Heat.hull_side_era.penetration_loss == 400.0F
                && t72Heat.hull_front_split != null
                && t72Heat.hull_front_split.upper.thickness == 620.0F
                && t72Heat.hull_front_split.lower.thickness == 180.0F
                // The HE table is a copy of the chemical one and must not be the same object, or
                // editing one plate edits the round that was meant to be measured against the other.
                && samePlates(t72Heat, t72.overrides.get("he"))
                && t72.overrides.get("he") != t72Heat;
        System.out.println();
        System.out.println("T-72B3M profile         : " + t72Ok
                + "  (parts " + (t72 == null ? "-" : java.util.Arrays.toString(t72.turret_part_ids))
                + ", bolt-ons " + (t72 == null ? "-" : t72.part_armor.keySet()) + ")");

        // The M1296 Stryker, and the two things about it that a copy-paste would get wrong: its
        // wheel line is its own (-0.318, measured off its own wheel boxes), and its suspension is
        // the one wheeled entry among the seven - the other six are tracked.
        ArmorConfig.VehicleArmor stryker = profile.vehicles.get("vvp_ywzj:stryker_m1296");
        boolean strykerOk = stryker != null
                && stryker.turret != null && stryker.hull != null
                && stryker.turret.front == 45.0F && stryker.turret.side == 35.0F
                && stryker.turret.roof == 15.0F && stryker.hull.front == 45.0F
                && stryker.hull.floor == 20.0F
                && stryker.damage_multiplier != null && stryker.damage_multiplier.front == 5.0F
                // The same box the wheels are measured against moved, so this figure is negative for
                // the same reason the BTR-3's is - see roadWheelRegrade.
                && stryker.road_wheels != null && stryker.road_wheels.top == -0.318F
                // A wheeled vehicle: no track strips, and no glacis split - the whole front face is
                // one plate, which is what the BTR-3 does and for the same reason.
                && stryker.track_ends == null && stryker.hull_front_split == null
                && java.util.Set.of(stryker.turret_part_ids).equals(java.util.Set.of(
                        "turret", "turret_mg", "turret_smoke_grenade"));
        // The gun, which now has two rounds rather than one. The high-explosive shell is the pack's
        // own: an explosion on it and nothing in its name to say it is a dart, so it is filed where
        // the shelf rule puts it, exactly as the BTR-3's 30mm high-explosive round is.
        ArmorConfig.Ammo xm813 = profile.ammo_penetration.get("vvp_ywzj:xm813_30mm");
        boolean xm813Ok = xm813 != null && xm813.penetration == 6.0F && "he".equals(xm813.profile)
                && xm813.shelf() == com.ywzjvehicle.armor.AmmoCategory.AUTOCANNON_HE
                && xm813.penetration_damage == null;
        // And the dart, which is the reason this round of changes exists: 116mm for the MK258, on the
        // armour-piercing shelf, and **no profile** - a dart carries no chemical charge and no blast,
        // so the kinetic table is the whole of it. A profile left on it would measure it against the
        // HEAT table and quietly turn a slug of tungsten into a shaped charge.
        //
        // The other half of this check is not here but in the `ammo after` line printed above: this
        // file is the **user's** config, written before the round existed, so the entry being in the
        // profile at this point is the v38 -> v39 migration having run.
        ArmorConfig.Ammo xm813Dart = profile.ammo_penetration.get("vvp_ywzj:xm813_30mm_apfsds");
        boolean xm813DartOk = xm813Dart != null && xm813Dart.penetration == 116.0F
                && xm813Dart.shelf() == com.ywzjvehicle.armor.AmmoCategory.AUTOCANNON_AP
                && xm813Dart.profile == null;
        System.out.println("M1296 Stryker profile   : " + strykerOk
                + "  (wheels from " + (stryker == null || stryker.road_wheels == null ? "-"
                        : stryker.road_wheels.top)
                + ", suspension " + (stryker != null && stryker.suspension != null ? "yes" : "no")
                + ", 30mm " + (xm813Ok ? "he" : "WRONG")
                + " + dart " + (xm813DartOk ? xm813Dart.penetration + "mm" : "WRONG") + ")");

        // v40 -> v41: the two dual-feed 30mm cannons stopped carrying their other round as a load.
        // The belts are groups in the **pack** now - two real weapons apiece, switched with the
        // multi-weapon key - and what the config has to say about them is that it has nothing to say:
        // a load list left on any of the four slots is the v40 arrangement, and a load is fired as
        // the weapon it is loaded into, so the dart's slot left holding the shell's entry would
        // measure every dart it fired against six millimetres.
        //
        // This is the half of the check that the pack-side one in VehicleScaffold cannot make: that
        // tool reads the belts, this reads the four slots they draw their rounds from.
        ArmorConfig.Ammo ztmDart = profile.ammo_penetration.get("vvp_ywzj:ztm1_30mm_apfsds");
        ArmorConfig.Ammo ztmHe = profile.ammo_penetration.get("vvp_ywzj:ztm1_30mm");
        ArmorConfig.Ammo xmHe = profile.ammo_penetration.get("vvp_ywzj:xm813_30mm");
        boolean beltsOk = ztmDart != null && !ztmDart.selectable() && ztmDart.penetration == 90.0F
                && ztmDart.profile == null
                && ztmHe != null && !ztmHe.selectable() && ztmHe.penetration == 6.0F
                && "he".equals(ztmHe.profile)
                && xmHe != null && !xmHe.selectable() && xmHe.penetration == 6.0F
                && "he".equals(xmHe.profile)
                && xm813Dart != null && !xm813Dart.selectable()
                && xm813Dart.penetration == 116.0F && xm813Dart.profile == null;
        // And the two rounds are real weapons in the pack, which is what makes each of them keep its
        // own projectile and its own explosion. That half is asserted against the pack itself, in
        // VehicleScaffold's "dual-feed belts" section - the answer is in the vehicle file, and the
        // vehicle file is not this tool's to read.
        System.out.println("30mm belts, no loads    : " + beltsOk + "  ("
                + (ztmDart == null ? "-" : ztmDart.penetration + "mm / "
                        + (ztmHe == null ? "-" : ztmHe.penetration + "mm, both as weapons in a belt"))
                + ")");

        // The 125mm high-explosive shell, and the reason it needed a config version of its own: it is
        // the first slot whose loads are not marks of a kinetic round but a different shell, and the
        // load list is nested one level deeper than anything the serializer had carried before. A
        // dropped list leaves a perfectly ordinary HE shell that the chooser still offers - the
        // second load simply is not there any more, and nothing on either side says so.
        ArmorConfig.Ammo he125 = profile.ammo_penetration.get("vvp_ywzj:2a46m5_125mm_he");
        ArmorConfig.Ammo telnik = he125 == null ? null : he125.variant(1);
        boolean he125Ok = he125 != null
                && he125.penetration == 60.0F && "he".equals(he125.profile)
                && he125.penetration_damage != null && he125.penetration_damage == 2000.0F
                // The same numbers as the T-64's HE shell, deliberately: it is the same shell, and a
                // 125mm high-explosive round that meant one thing under one tank and another under
                // the next would be the sort of difference nobody could see.
                && he125.spall_damage_min == 300.0F && he125.spall_damage_max == 1000.0F
                && he125.spallFallsOff() && !he125.spallOnPenetration()
                && he125.shelf() == com.ywzjvehicle.armor.AmmoCategory.CANNON_HE
                // Two loads: the pack's own shell, and the airburst one. Same charge, same
                // penetration, heavier fragments - so the round is the same and the shell is not.
                && he125.variantCount() == 2
                && telnik != null && "3OF82 Telnik".equals(telnik.name)
                && telnik.penetration == he125.penetration
                && telnik.penetration_damage != null
                // Compared as floats, not as Floats: the field is a boxed Float, so == would ask
                // whether the two numbers were the same object and answer no for anything outside
                // the integer cache - which is a passing check that fails.
                && telnik.penetration_damage.floatValue() == he125.penetration_damage.floatValue()
                && telnik.spall_damage_max > he125.spall_damage_max
                && telnik.hasSpall()
                && !telnik.spallOnPenetration();
        ArmorConfig.Ammo he125Again = roundTripped.ammo_penetration.get("vvp_ywzj:2a46m5_125mm_he");
        boolean he125RoundTripOk = he125Again != null && he125Again.variantCount() == 2
                && he125Again.variant(1) != null && "3OF82 Telnik".equals(he125Again.variant(1).name)
                && he125Again.variant(1).spall_damage_max == telnik.spall_damage_max
                // And the loads of a kinetic slot came back as kinetic loads, with nothing dragged
                // onto the high-explosive shelf on the way through.
                && roundTripped.ammo_penetration.get("vvp_ywzj:2a46m5_125mm_apfsds")
                        .shelf() == com.ywzjvehicle.armor.AmmoCategory.CANNON_AP;
        System.out.println("125mm HE + airburst     : " + he125Ok
                + "  (loads " + (he125 == null ? "-" : he125.variantCount())
                + ", blast " + spall(he125) + " and " + spall(telnik)
                + ", round trip " + he125RoundTripOk + ")");

        // v33 -> v34: the T-64's fording kit. One optional block on one vehicle, and the failure it
        // can have is the quietest kind: a file that never receives it reads as "no kit", the tank
        // drowns exactly as it did before the feature existed, and nothing anywhere says the feature
        // is missing rather than absent by design.
        ArmorConfig.VehicleArmor t64Kit = profile.vehicles.get("vvp_ywzj:t64");
        boolean fordingOk = t64Kit != null && t64Kit.fording != null
                && t64Kit.fording.max_depth == Fording.DEFAULT_MAX_DEPTH
                // The set, not the count: a count passes with the kit on the wrong vehicle.
                && withKit(profile).equals(java.util.Set.of("vvp_ywzj:t64"));
        ArmorConfig.Profile kitTrip = ArmorConfig.configReader().fromJson(
                ArmorConfig.configWriter().toJson(profile), ArmorConfig.Profile.class);
        boolean fordingRoundTripOk = withKit(kitTrip).equals(java.util.Set.of("vvp_ywzj:t64"))
                && kitTrip.vehicles.get("vvp_ywzj:t64").fording != null
                && kitTrip.vehicles.get("vvp_ywzj:t64").fording.max_depth
                        == Fording.DEFAULT_MAX_DEPTH;
        boolean fordingMigrationOk = fordingMigration(upgrade);
        System.out.println("T-64 fording kit        : " + fordingOk
                + "  (round trip " + fordingRoundTripOk
                + ", migration " + fordingMigrationOk + ")");

        boolean migrationOk = migration();

        System.out.println();
        System.out.println("hull_front_split intact : " + splitOk);
        System.out.println("'heat' override present : " + heatOk);
        System.out.println("HEAT round present      : " + heatRoundOk);
        System.out.println("'he'   override present : " + heOk);
        System.out.println("HE   round present      : " + heRoundOk);
        System.out.println("Abrams profile present  : " + abramsOk);
        System.out.println("Abrams ammunition       : " + abramsAmmoOk);
        System.out.println("Abrams machine guns     : " + abramsMgOk);
        System.out.println("HE-VT round present     : " + vtOk);
        System.out.println("multi-purpose round     : " + mpOk);
        System.out.println("  survives a round trip : " + mpRoundTripOk);
        System.out.println("T-90M glacis migration  : " + t90mMigrationOk);
        System.out.println("T-90M dart migration    : " + dartMigrationOk);
        System.out.println("reactive panel kinds    : " + (t64EraOk && t90mEraOk)
                + "  (round trip " + eraRoundTripOk + ", migration " + eraMigrationOk + ")");
        System.out.println("new part-armour boxes   : " + boxMigrationOk);
        System.out.println("adjustable suspension   : " + (suspensionsOk && suspensionMigrationOk)
                + "  (migration " + suspensionMigrationOk + ")");
        if (splitOk && heatOk && heatRoundOk && heOk && heRoundOk && abramsOk && abramsAmmoOk && vtOk
                && suspensionsOk && suspensionMigrationOk && taczOk && taczMigrationOk
                && mpOk && mpRoundTripOk && abramsMgOk
                && btrOk && btrChemicalOk && btrAmmoOk && categoriesOk && loadsOk && migrationOk
                && pantherOk && pantherRoundTripOk && screenOk && tigerOk && wheelMigrationOk
                && t90mOk && t90mPartsOk && t90mMigrationOk
                && tandemOk && tandemRoundTripOk
                && t64EraOk && t90mEraOk && eraRoundTripOk && eraMigrationOk && boxMigrationOk
                && dartMigrationOk && m1Ok && t72Ok && strykerOk && xm813Ok && xm813DartOk
                && beltsOk
                && he125Ok && he125RoundTripOk
                && fordingOk && fordingRoundTripOk && fordingMigrationOk) {
            System.out.println("RESULT: upgrade applied correctly");
        } else {
            System.out.println("RESULT: upgrade incomplete - see the flags above");
        }
    }

    /**
     * Exercises the v28 -> v29 backfill of the reactive-panel kinds on a scratch profile.
     *
     * <p>Two behaviours, and the second is the one that matters: a panel that says nothing has to be
     * given the kind its default states, and a panel a player has given a kind of their own has to be
     * left alone. The first is invisible when it fails - a panel with no kind is ordinary armour, so
     * the tandem round it was configured to answer sails through it and every number still prints.
     *
     * <p>Run on {@code defaultProfile()} rather than on the real file, so the assertion does not
     * depend on what this machine's config happens to hold.
     */
    private static boolean eraMigration(Method upgrade) throws Exception {
        Method defaults = ArmorConfig.class.getDeclaredMethod("defaultProfile");
        defaults.setAccessible(true);
        ArmorConfig.Profile scratch = (ArmorConfig.Profile) defaults.invoke(null);

        // As a file written before the key existed: the panels are there, the kinds are not.
        scratch.vehicles.get("vvp_ywzj:t64").overrides.get("heat").hull_side_era.era = null;
        scratch.vehicles.get("vvp_ywzj:t90_pror_2024").part_armor.get("era_3").era = null;
        // One the player has set themselves, to a kind the defaults do not give this panel.
        scratch.vehicles.get("vvp_ywzj:t90_m").part_armor.get("additional_armor").era = "light";
        // And one that is not reactive armour in the defaults either, so it must stay that way.
        scratch.vehicles.get("vvp_ywzj:t64").overrides.get("heat").track_ends.era = null;

        upgrade.invoke(null, scratch);

        String gotFlank = scratch.vehicles.get("vvp_ywzj:t64").overrides.get("heat")
                .hull_side_era.era;
        String gotBox = scratch.vehicles.get("vvp_ywzj:t90_pror_2024").part_armor.get("era_3").era;
        String gotHand = scratch.vehicles.get("vvp_ywzj:t90_m").part_armor.get("additional_armor").era;
        String gotPlain = scratch.vehicles.get("vvp_ywzj:t64").overrides.get("heat").track_ends.era;
        System.out.println();
        System.out.println("reactive kind migration  : T-64 flank -> " + gotFlank
                + ", T-90M box -> " + gotBox + ", hand-set -> " + gotHand
                + ", plain plate -> " + gotPlain);
        return "light".equals(gotFlank) && "heavy".equals(gotBox)
                && "light".equals(gotHand) && gotPlain == null;
    }

    /** @return whether a vehicle's kinetic front plates and chemical turret front are these. */
    private static boolean frontalIs(ArmorConfig.Profile p, String id, float turretFront,
                                     float glacis, float lowerPlate, float turretFrontHeat) {
        ArmorConfig.VehicleArmor v = p.vehicles.get(id);
        ArmorConfig.ArmorOverride heat = v == null ? null : v.overrides.get("heat");
        return v != null && v.turret != null && v.turret.front == turretFront
                && v.hull_front_split != null
                && v.hull_front_split.upper.thickness == glacis
                && v.hull_front_split.lower.thickness == lowerPlate
                && heat != null && heat.turret != null && heat.turret.front == turretFrontHeat;
    }

    /**
     * Exercises the v33 -> v34 backfill of the T-64's fording kit, on a scratch profile.
     *
     * <p>Two behaviours, and the second is the one that matters: a vehicle with no kit has to be
     * given the one its default states, and a kit the player has put their own depth on has to be
     * left alone. The first is invisible when it fails - a vehicle with no kit simply behaves as
     * every vehicle did before the feature existed.
     *
     * <p>Run on {@code defaultProfile()} rather than on the real file, so the assertion does not
     * depend on what this machine's config happens to hold.
     */
    private static boolean fordingMigration(Method upgrade) throws Exception {
        Method defaults = ArmorConfig.class.getDeclaredMethod("defaultProfile");
        defaults.setAccessible(true);
        ArmorConfig.Profile scratch = (ArmorConfig.Profile) defaults.invoke(null);

        // As a file written at v33: the block is not there at all.
        scratch.vehicles.get("vvp_ywzj:t64").fording = null;
        // And one the player has set themselves - a deeper river than the published figure.
        ArmorConfig.VehicleArmor t90m = scratch.vehicles.get("vvp_ywzj:t90_m");
        t90m.fording = new Fording.Kit();
        t90m.fording.max_depth = 8.0F;

        upgrade.invoke(null, scratch);

        ArmorConfig.VehicleArmor t64 = scratch.vehicles.get("vvp_ywzj:t64");
        float t64Depth = t64.fording == null ? Float.NaN : t64.fording.max_depth;
        System.out.println();
        System.out.println("fording kit migration   : T-64 -> " + t64Depth
                + ", hand-set T-90M -> " + t90m.fording.max_depth + " (want 8.0)");
        return t64Depth == Fording.DEFAULT_MAX_DEPTH && t90m.fording.max_depth == 8.0F;
    }

    /** @return the ids of every vehicle in a profile that carries a fording kit. */
    private static java.util.Set<String> withKit(ArmorConfig.Profile p) {
        java.util.Set<String> ids = new java.util.TreeSet<>();
        for (java.util.Map.Entry<String, ArmorConfig.VehicleArmor> entry : p.vehicles.entrySet()) {
            if (entry.getValue().fording != null) {
                ids.add(entry.getKey());
            }
        }
        return ids;
    }

    /** @return a round's blast band, for printing beside a flag that says it is wrong. */
    private static String spall(ArmorConfig.Ammo ammo) {
        return ammo == null ? "-" : (int) ammo.spall_damage_min + "-" + (int) ammo.spall_damage_max;
    }

    /** @return the chemical turret front of a vehicle, or NaN when it has none. */
    private static float chemicalFront(ArmorConfig.Profile p, String id) {
        ArmorConfig.VehicleArmor v = p.vehicles.get(id);
        ArmorConfig.ArmorOverride heat = v == null ? null : v.overrides.get("heat");
        return heat == null || heat.turret == null ? Float.NaN : heat.turret.front;
    }

    /**
     * Exercises the v30 -> v31 move onto the depleted-uranium armour, on a scratch profile.
     *
     * <p>Two behaviours, and the second is the one that matters: a tank still carrying the M1A1's
     * frontal plates is the shipped copy and has to be given its own, and one the player has edited
     * has to be left alone. The first is invisible when it fails - a copied 800mm glacis is as
     * plausible a number as the 1000mm it should have been, and nothing else in the file says which
     * tank is which.
     */
    private static boolean duMigration(Method upgrade) throws Exception {
        Method defaults = ArmorConfig.class.getDeclaredMethod("defaultProfile");
        defaults.setAccessible(true);
        ArmorConfig.Profile scratch = (ArmorConfig.Profile) defaults.invoke(null);

        ArmorConfig.VehicleArmor reference = scratch.vehicles.get("vvp_ywzj:m1a1");
        // As a v30 file: the A2 carrying the M1A1's plates, front for front - all six of them, both
        // tables, because that is what the copy that shipped was. Setting three of them would be
        // testing a file that never existed and would take the "has the player edited this?" branch.
        ArmorConfig.VehicleArmor a2 = scratch.vehicles.get("vvp_ywzj:m1a2");
        ArmorConfig.ArmorOverride a2Heat = a2.overrides.get("heat");
        ArmorConfig.ArmorOverride refHeat = reference.overrides.get("heat");
        a2.turret.front = reference.turret.front;
        a2Heat.turret.front = refHeat.turret.front;
        a2.hull_front_split.upper.thickness = reference.hull_front_split.upper.thickness;
        a2.hull_front_split.lower.thickness = reference.hull_front_split.lower.thickness;
        a2Heat.hull_front_split.upper.thickness = refHeat.hull_front_split.upper.thickness;
        a2Heat.hull_front_split.lower.thickness = refHeat.hull_front_split.lower.thickness;
        // And the SEP hand-edited to a figure of the player's own, which must survive untouched.
        ArmorConfig.VehicleArmor sep = scratch.vehicles.get("vvp_ywzj:m1a2_sep");
        sep.turret.front = 700.0F;

        upgrade.invoke(null, scratch);

        float a2Front = scratch.vehicles.get("vvp_ywzj:m1a2").turret.front;
        float a2Glacis = scratch.vehicles.get("vvp_ywzj:m1a2").hull_front_split.upper.thickness;
        float sepFront = scratch.vehicles.get("vvp_ywzj:m1a2_sep").turret.front;
        System.out.println();
        System.out.println("depleted uranium migration: A2 " + a2Front + "/" + a2Glacis
                + " (want 800/1000), hand-set SEP " + sepFront + " (want 700)");
        return a2Front == 800.0F && a2Glacis == 1000.0F && sepFront == 700.0F;
    }

    /**
     * Exercises the v31 -> v32 change to the T-90M's dart on a scratch profile.
     *
     * <p>Two things arrive by different routes and both have to: the rating is a value on an entry
     * the file already has, so it needs the migration, and the four loads are a list the entry does
     * not have, so they come through the generic fill. Either one missing leaves a T-90M firing a
     * round that is not the one it should be - and in both cases the config still parses, still has
     * the entry, and still looks right.
     */
    private static boolean dartMigration(Method upgrade) throws Exception {
        Method defaults = ArmorConfig.class.getDeclaredMethod("defaultProfile");
        defaults.setAccessible(true);
        ArmorConfig.Profile scratch = (ArmorConfig.Profile) defaults.invoke(null);

        ArmorConfig.Ammo dart = scratch.ammo_penetration.get("vvp_ywzj:2a46m5_125mm_t90m_apfsds");
        // As a v31 file: the shipped 600mm rating, and no loads at all.
        dart.penetration = 600.0F;
        dart.variants = null;
        // And a second copy with a rating of the player's own, which must survive.
        ArmorConfig.Ammo handSet = new ArmorConfig.Ammo(650.0F);

        upgrade.invoke(null, scratch);

        float rated = dart.penetration;
        int loads = dart.variantCount();
        boolean handKept = handSet.penetration == 650.0F;
        System.out.println();
        System.out.println("T-90M dart migration     : " + rated + "mm with " + (loads - 1)
                + " load(s), hand-set kept " + handKept);
        return rated == 700.0F && loads == 4 && handKept;
    }

    /** @return whether a vehicle values exactly the parts named, and no others. */
    private static boolean partArmorIs(ArmorConfig.Profile p, String id, java.util.Set<String> parts) {
        ArmorConfig.VehicleArmor v = p.vehicles.get(id);
        return v != null && v.part_armor != null && v.part_armor.keySet().equals(parts);
    }

    /**
     * Every reactive block of one table as {@code name ThicknessxMultiplier}, or {@code -}.
     *
     * <p>Keyed off the {@code era_} prefix rather than off a list of names, so the line reports what
     * the table actually holds - a block that is there and should not be, or one that is missing,
     * shows up as a difference in the text rather than as a silent pass.
     */
    private static String plates(java.util.Map<String, ArmorConfig.Plate> boxes, String what) {
        if (boxes == null) {
            return what + ": -";
        }
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (String key : new java.util.TreeSet<>(boxes.keySet())) {
            if (!key.startsWith("era_")) {
                continue;
            }
            ArmorConfig.Plate plate = boxes.get(key);
            if (plate == null) {
                continue;
            }
            parts.add(key + " " + trim(plate.thickness) + "x" + trim(plate.multiplier)
                    + (plate.era == null ? " (!)" : ""));
        }
        return what + ": " + String.join(", ", parts);
    }

    /** One decimal, or a bare integer when the value has none - "300" reads better than "300.0". */
    private static String trim(float value) {
        return value == Math.round(value) ? String.valueOf(Math.round(value))
                : String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    /**
     * @return whether every reactive block on a T-90M variant carries the figures the design gives
     *         it, on both kinds of round and with the kind that makes it reactive at all.
     *
     * <p>All of them, turret and hull together: `era_1` and `era_2` along the hull's flanks,
     * `era_3`/`era_4` on the turret's and `era_5` across its rear. What used to tell them apart was
     * a number, and that was the mistake - a block's figure is what a round has to beat **at that
     * spot**, and where the spot is decides what is underneath it, not what the block is worth. The
     * keys are gathered into a set and checked one by one rather than counted, so a block nobody
     * valued fails here instead of passing on the strength of its neighbours.
     */
    private static boolean t90mBlocksMatch(ArmorConfig.Profile p, String id) {
        ArmorConfig.VehicleArmor v = p.vehicles.get(id);
        if (v == null || v.part_armor == null) {
            return false;
        }
        java.util.Set<String> blocks = new java.util.TreeSet<>();
        for (String key : v.part_armor.keySet()) {
            if (key.startsWith("era_")) {
                blocks.add(key);
            }
        }
        if (blocks.size() < 4) {
            // Two on the hull and two on the turret, whatever else a variant bolts on.
            return false;
        }
        for (String block : blocks) {
            if (!blockIs(v.part_armor.get(block), 300.0F, 8.0F)
                    // Heavy: the Relikt class, which has to stop a tandem warhead once. Left without
                    // a kind it would be plain steel, and the one round these blocks exist to answer
                    // would go straight through them.
                    || !"heavy".equals(era(v.part_armor.get(block)))) {
                return false;
            }
        }
        // And every profile the vehicle has, not only the chemical one: a figure that moves in one
        // table and not another is the mistake this pair of assertions exists for - the HE round
        // would find 1100 where the shaped charge found 600, and both tables print a plausible
        // number on their own.
        for (ArmorConfig.ArmorOverride override : v.overrides.values()) {
            for (String block : blocks) {
                ArmorConfig.Plate plate = override.part_armor == null ? null
                        : override.part_armor.get(block);
                if (!blockIs(plate, 600.0F, 6.0F) || !"heavy".equals(era(plate))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * @return whether a variant's turret-rear plate is valued, on both kinds of round.
     *
     * <p>Kept apart from {@link #t90mBlocksMatch} because the name does not start with {@code era_}:
     * that check gathers blocks by prefix, and this part is named for where it is rather than for
     * what it is. It carries the same pair of figures and the same kind, which is the whole of the
     * request that produced it.
     */
    private static boolean t90mRearPlate(ArmorConfig.Profile p, String id) {
        ArmorConfig.VehicleArmor v = p.vehicles.get(id);
        if (v == null || v.overrides == null) {
            return false;
        }
        ArmorConfig.Plate hard = v.part_armor == null ? null
                : v.part_armor.get("additional_armor_rear");
        for (ArmorConfig.ArmorOverride override : v.overrides.values()) {
            ArmorConfig.Plate soft = override.part_armor == null ? null
                    : override.part_armor.get("additional_armor_rear");
            if (!blockIs(soft, 600.0F, 6.0F) || !"heavy".equals(era(soft))) {
                return false;
            }
        }
        return blockIs(hard, 300.0F, 8.0F) && "heavy".equals(era(hard));
    }

    /** @return whether a box is this thickness at this multiplier. */
    private static boolean blockIs(ArmorConfig.Plate plate, float thickness, float multiplier) {
        return plate != null && plate.thickness == thickness && plate.multiplier == multiplier;
    }

    /** @return a plate's reactive kind, or {@code null} when it has none. */
    private static String era(ArmorConfig.Plate plate) {
        return plate == null ? null : plate.era;
    }

    /**
     * Exercises the v34 -> v35 backfill of the T-90M's hull blocks, on a scratch profile.
     *
     * <p>Two behaviours, and the second is the one that matters: a file that has never had the boxes
     * has to be given them, and a box the player has given values of their own has to be left exactly
     * as it is. The first is invisible when it fails in the worst way of anything in this file - the
     * vehicle keeps a full table of plausible numbers, and the only thing that says the blocks were
     * never valued is the model, which the config reader does not look at.
     *
     * <p>Run on {@code defaultProfile()} rather than on the real file, so the assertion does not
     * depend on what this machine's config happens to hold.
     */
    private static boolean boxMigration(Method upgrade) throws Exception {
        Method defaults = ArmorConfig.class.getDeclaredMethod("defaultProfile");
        defaults.setAccessible(true);
        ArmorConfig.Profile scratch = (ArmorConfig.Profile) defaults.invoke(null);

        // As a file written at v34: the turret's blocks are there, the hull's are not - which is
        // exactly the shape `inherit` cannot see, because the map itself is neither absent nor empty.
        ArmorConfig.VehicleArmor worn = scratch.vehicles.get("vvp_ywzj:t90_pror_2024");
        worn.part_armor.remove("era_1");
        worn.part_armor.remove("era_2");
        worn.overrides.get("heat").part_armor.remove("era_1");
        worn.overrides.get("heat").part_armor.remove("era_2");
        // And the same again one release later for the plate the pack patch made a part: a file
        // written before that patch has never heard of it, and no key is added by any other route.
        worn.part_armor.remove("additional_armor_rear");
        worn.overrides.get("heat").part_armor.remove("additional_armor_rear");
        // And on the other variant a box the player has retuned to their own figure.
        ArmorConfig.VehicleArmor retuned = scratch.vehicles.get("vvp_ywzj:t90_pror_2022");
        retuned.part_armor.get("era_1").thickness = 1000.0F;

        upgrade.invoke(null, scratch);

        ArmorConfig.Plate added = worn.part_armor.get("era_1");
        ArmorConfig.Plate addedHeat = worn.overrides.get("heat").part_armor.get("era_2");
        ArmorConfig.Plate addedRear = worn.part_armor.get("additional_armor_rear");
        float kept = retuned.part_armor.get("era_1").thickness;
        System.out.println();
        System.out.println("new-key migration       : 2024 -> "
                + (added == null ? "-" : added.thickness) + "/"
                + (addedHeat == null ? "-" : addedHeat.thickness) + "/"
                + (addedRear == null ? "-" : addedRear.thickness)
                + " (want 300/600/300), hand-set 2022 -> " + kept + " (want 1000)");
        return added != null && added.thickness == 300.0F && "heavy".equals(added.era)
                && addedHeat != null && addedHeat.thickness == 600.0F
                && "heavy".equals(addedHeat.era)
                && addedRear != null && addedRear.thickness == 300.0F
                && "heavy".equals(addedRear.era) && kept == 1000.0F;
    }

    /**
     * Exercises the T-90M's two rounds of corrections on a scratch profile: the glacis boundary
     * (v26 -> v27, and again at v35 -> v36) and then the front plates and turret blocks (v35 -> v36).
     *
     * <p>Each figure moves only where the file still holds what shipped, and that second half is the
     * one worth testing: the moving half is wrong loudly the first time anyone reads a table, while
     * the not-moving half fails silently - it would overrule an edit the player has made, and there
     * is nothing on screen to say it happened.
     *
     * <p>Run on {@code defaultProfile()} rather than on the real file, so the assertion does not
     * depend on what this machine's config happens to hold.
     */
    private static boolean t90mGlacisMigration(Method upgrade) throws Exception {
        Method defaults = ArmorConfig.class.getDeclaredMethod("defaultProfile");
        defaults.setAccessible(true);
        ArmorConfig.Profile scratch = (ArmorConfig.Profile) defaults.invoke(null);

        // A file as the first of these shipped: the boundary with its sign the wrong way round, the
        // glacis at 520, and the turret's blocks at the pair that was calibrated against the wrong
        // face. Every one of them has to be put back by hand, since the defaults are what they are
        // being moved to.
        ArmorConfig.VehicleArmor old = scratch.vehicles.get("vvp_ywzj:t90_pror_2024");
        old.hull_front_split.split = 0.3F;
        old.hull_front_split.upper.thickness = 520.0F;
        old.hull.front = 520.0F;
        old.part_armor.get("era_3").thickness = 700.0F;
        old.part_armor.get("era_3").multiplier = 12.0F;
        // Both profiles, because the figures are stored in each table separately and the first
        // version of this migration moved only the chemical one: the HE table would have gone on
        // rating the turret's blocks at 1100 while the shaped charge beside it read 600.
        old.overrides.get("heat").part_armor.get("era_4").thickness = 1100.0F;
        old.overrides.get("heat").part_armor.get("era_4").multiplier = 10.0F;
        old.overrides.get("he").part_armor.get("era_4").thickness = 1100.0F;
        old.overrides.get("he").part_armor.get("era_4").multiplier = 10.0F;
        // The second generation, whose boundary is the negative one - a file that has never seen
        // either gets a third, and all three end up in the same place. The T-72B3M is on that same
        // negative figure and has to move with them.
        scratch.vehicles.get("vvp_ywzj:t90_pror_2022").hull_front_split.split = -0.33F;
        scratch.vehicles.get("vvp_ywzj:t72_b3m").hull_front_split.split = -0.33F;
        // And one a player has been tuning: every figure here is their own, down to the hull front
        // that is meant to track the glacis.
        ArmorConfig.VehicleArmor hand = scratch.vehicles.get("vvp_ywzj:t90_m");
        hand.hull_front_split.split = 0.1F;
        hand.hull_front_split.upper.thickness = 600.0F;
        hand.hull.front = 600.0F;

        upgrade.invoke(null, scratch);

        float fromSign = old.hull_front_split.split;
        float fromThirds = scratch.vehicles.get("vvp_ywzj:t90_pror_2022").hull_front_split.split;
        float fromB3m = scratch.vehicles.get("vvp_ywzj:t72_b3m").hull_front_split.split;
        float kept = hand.hull_front_split.split;
        float glacis = old.hull_front_split.upper.thickness;
        float hullFront = old.hull.front;
        float block = old.part_armor.get("era_3").thickness;
        float blockHeat = old.overrides.get("heat").part_armor.get("era_4").thickness;
        float blockHe = old.overrides.get("he").part_armor.get("era_4").thickness;
        float keptGlacis = hand.hull_front_split.upper.thickness;
        System.out.println();
        System.out.println("glacis split migration  : shipped 0.3 / -0.33 / T-72B3M -0.33 -> "
                + fromSign + " / " + fromThirds + " / " + fromB3m
                + ", hand-edited 0.1 -> " + kept);
        System.out.println("T-90M regrade migration : glacis 520 -> " + glacis
                + ", hull front -> " + hullFront
                + ", blocks 700/1100 -> " + block + "/" + blockHeat + "/" + blockHe
                + ", hand-set 600 -> " + keptGlacis);
        return fromSign == 0.0F && fromThirds == 0.0F && fromB3m == 0.0F && kept == 0.1F
                && glacis == 700.0F && hullFront == 700.0F
                && block == 300.0F && blockHeat == 600.0F && blockHe == 600.0F
                && keptGlacis == 600.0F;
    }

    /**
     * @return whether two overrides would produce the same numbers, or whether both are absent.
     *
     * <p>Compared field by field rather than by identity: a table that is a copy of another is the
     * point, and a table that <em>is</em> the other object is the mistake - editing one would edit
     * both, and {@code ==} would call that a pass.
     */
    private static boolean samePlates(ArmorConfig.ArmorOverride a, ArmorConfig.ArmorOverride b) {
        if (a == null || b == null) {
            return a == b;
        }
        return sameSet(a.turret, b.turret) && sameSet(a.hull, b.hull)
                && sameMultiplier(a.damage_multiplier, b.damage_multiplier)
                && samePlate(a.turret_weak_spot, b.turret_weak_spot)
                && samePlate(a.track_ends, b.track_ends)
                && samePlate(a.road_wheels, b.road_wheels)
                && samePlate(a.hull_side_era, b.hull_side_era)
                // The per-box values too. They are the newest member of this family and the easiest
                // to leave out, because a missing one does not read as missing - the box simply gets
                // the vehicle's own number, which looks like a table either way.
                && sameBoxes(a.part_armor, b.part_armor)
                && samePlate(a.hull_front_split == null ? null : a.hull_front_split.upper,
                        b.hull_front_split == null ? null : b.hull_front_split.upper)
                && samePlate(a.hull_front_split == null ? null : a.hull_front_split.lower,
                        b.hull_front_split == null ? null : b.hull_front_split.lower);
    }

    private static boolean sameSet(ArmorConfig.ArmorSet a, ArmorConfig.ArmorSet b) {
        return a == null || b == null ? a == b
                : a.front == b.front && a.side == b.side && a.rear == b.rear
                        && a.roof == b.roof && a.floor == b.floor;
    }

    private static boolean sameMultiplier(ArmorConfig.DamageMultiplier a,
                                          ArmorConfig.DamageMultiplier b) {
        return a == null || b == null ? a == b
                : a.front == b.front && a.side == b.side && a.rear == b.rear
                        && a.roof == b.roof && a.floor == b.floor;
    }

    private static boolean samePlate(ArmorConfig.Plate a, ArmorConfig.Plate b) {
        return a == null || b == null ? a == b
                : a.thickness == b.thickness && a.multiplier == b.multiplier;
    }

    /** @return whether two per-part maps hold the same parts, each with the same numbers. */
    private static boolean sameBoxes(java.util.Map<String, ArmorConfig.Plate> a,
                                     java.util.Map<String, ArmorConfig.Plate> b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (!a.keySet().equals(b.keySet())) {
            return false;
        }
        for (String part : a.keySet()) {
            if (!samePlate(a.get(part), b.get(part))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Where the mod's config would be on this machine, preferring whichever of the two names is
     * actually present.
     *
     * <p>The fallback is not a convenience. Until the game has been launched once with the renamed
     * build, the only config on disk is the old one - the mod moves it at load time, and nothing
     * else does. Reading the new name alone would make this tool report "file not found" for a
     * player whose settings are perfectly fine.
     */
    private static Path defaultConfig() {
        Path dir = Path.of(System.getenv().getOrDefault("MC_ROOT", "E:\\.minecraft")
                + DEFAULT_CONFIG_DIR);
        Path current = dir.resolve(CONFIG_NAME);
        return Files.exists(current) ? current : dir.resolve(PREVIOUS_CONFIG_NAME);
    }

    /**
     * Exercises the rename-time handover of the config file on scratch directories.
     *
     * <p>Two behaviours, and the second is the one that matters: the old file must be taken over
     * when the new one is absent, and must be left strictly alone when it is present - the latter
     * silently reverting every edit made since the rename. Neither shows up in a log unless it
     * goes wrong, which is why both are run here rather than reasoned about.
     *
     * <p>Reached reflectively because the method is private, and takes the directory as an
     * argument rather than reading {@code FMLPaths} itself - which is the whole reason it can be
     * run without a game.
     */
    private static boolean migration() throws Exception {
        Method adopt = ArmorConfig.class.getDeclaredMethod("adoptPreviousConfig", Path.class);
        adopt.setAccessible(true);

        Path dir = Files.createTempDirectory("ywzj_vehicle_armor-rename");

        // 1. only the old file: it is taken over, contents intact.
        Files.writeString(dir.resolve(PREVIOUS_CONFIG_NAME), "{\"config_version\":22}",
                StandardCharsets.UTF_8);
        adopt.invoke(null, dir);
        boolean tookOver = Files.exists(dir.resolve(CONFIG_NAME))
                && !Files.exists(dir.resolve(PREVIOUS_CONFIG_NAME))
                && Files.readString(dir.resolve(CONFIG_NAME), StandardCharsets.UTF_8)
                        .contains("22");

        // 2. both files: the new one is the live one, and must survive untouched.
        Files.writeString(dir.resolve(PREVIOUS_CONFIG_NAME), "{\"config_version\":0}",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(CONFIG_NAME), "{\"config_version\":22,\"edited\":true}",
                StandardCharsets.UTF_8);
        adopt.invoke(null, dir);
        boolean leftAlone = Files.readString(dir.resolve(CONFIG_NAME), StandardCharsets.UTF_8)
                .contains("edited");

        // 3. neither: a first install, where nothing may be created and nothing thrown.
        Path fresh = Files.createTempDirectory("ywzj_vehicle_armor-fresh");
        adopt.invoke(null, fresh);
        boolean quiet = !Files.exists(fresh.resolve(CONFIG_NAME))
                && !Files.exists(fresh.resolve(PREVIOUS_CONFIG_NAME));

        Files.deleteIfExists(dir.resolve(CONFIG_NAME));
        Files.deleteIfExists(dir.resolve(PREVIOUS_CONFIG_NAME));
        Files.deleteIfExists(dir);
        Files.deleteIfExists(fresh);

        System.out.println();
        System.out.println("rename migration        : " + tookOver + "   (old file taken over)");
        System.out.println("  new file left alone   : " + leftAlone);
        System.out.println("  clean install quiet   : " + quiet);
        return tookOver && leftAlone && quiet;
    }

    private static String describeOverrides(ArmorConfig.VehicleArmor t64) {
        if (t64 == null || t64.overrides == null || t64.overrides.isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, ArmorConfig.ArmorOverride> e : t64.overrides.entrySet()) {
            ArmorConfig.ArmorOverride o = e.getValue();
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(e.getKey()).append(": turret front ")
                    .append(o.turret == null ? "inherited" : o.turret.front + "mm")
                    .append(", glacis ")
                    .append(o.hull_front_split == null || o.hull_front_split.upper == null
                            ? "inherited" : o.hull_front_split.upper.thickness + "mm")
                    .append(", ERA side ")
                    .append(o.hull_side_era == null ? "inherited" : o.hull_side_era.thickness + "mm");
        }
        return sb.toString();
    }

    private static int currentVersion() throws Exception {
        java.lang.reflect.Field f = ArmorConfig.class.getDeclaredField("CONFIG_VERSION");
        f.setAccessible(true);
        return (Integer) f.get(null);
    }

    private static String describeAmmo(ArmorConfig.Profile p) {
        if (p.ammo_penetration == null || p.ammo_penetration.isEmpty()) {
            return "(empty)";
        }
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, ArmorConfig.Ammo> e : p.ammo_penetration.entrySet()) {
            ArmorConfig.Ammo a = e.getValue();
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(e.getKey()).append(' ')
                    .append(a.penetration).append("mm/")
                    .append(a.multiplier == null ? "per-plate" : "x" + a.multiplier)
                    .append(a.replacesBaseExplosion() ? "/blast-dropped" : "");
        }
        return sb.toString();
    }

    /** @return whether two rounds are configured identically, which is the whole point of a pair. */
    private static boolean matches(ArmorConfig.Profile profile, String other, ArmorConfig.Ammo ammo) {
        ArmorConfig.Ammo theirs = profile.ammo_penetration.get(other);
        return ammo != null && theirs != null
                && ammo.penetration == theirs.penetration
                && java.util.Objects.equals(ammo.multiplier, theirs.multiplier)
                && java.util.Objects.equals(ammo.profile, theirs.profile);
    }

    /** One round's numbers, so a failure says which half of a multi-purpose round is wrong. */
    private static String describe(ArmorConfig.Ammo a) {
        return (a.profile == null ? "default" : a.profile) + " " + a.penetration + "mm, burst "
                + (a.penetration_damage == null ? "residual" : a.penetration_damage)
                + ", blast " + a.spall_damage_min + "-" + a.spall_damage_max
                + (a.spallFallsOff() ? " of " + a.spall_falloff_to : "")
                + (a.replacesBaseExplosion() ? ", drops the pack blast" : ", keeps the pack blast");
    }

    private static String describeTrackEnds(ArmorConfig.VehicleArmor t64) {
        if (t64 == null || t64.track_ends == null) {
            return "null";
        }
        ArmorConfig.TrackEnds t = t64.track_ends;
        return String.format("%.0fmm x%.1f, inner edge %.2f, top %.2f",
                t.thickness, t.multiplier, t.inner_edge, t.top);
    }

    private static String describeWeakSpot(ArmorConfig.VehicleArmor t64) {
        if (t64 == null || t64.turret_weak_spot == null) {
            return "null";
        }
        ArmorConfig.WeakSpot w = t64.turret_weak_spot;
        return String.format("%.0fmm x%.0f, radius %.2f, offset (%.2f, %.2f)",
                w.thickness, w.multiplier, w.radius, w.offset_x, w.offset_y);
    }

    private static String describe(ArmorConfig.VehicleArmor t64) {
        if (t64 == null) {
            return "(no t64 in config)";
        }
        ArmorConfig.FrontSplit s = t64.hull_front_split;
        if (s == null) {
            return "null";
        }
        return String.format("upper %.0fmm x%.0f, lower %.0fmm x%.0f, split %.2f",
                s.upper.thickness, s.upper.multiplier,
                s.lower.thickness, s.lower.multiplier, s.split);
    }
}
