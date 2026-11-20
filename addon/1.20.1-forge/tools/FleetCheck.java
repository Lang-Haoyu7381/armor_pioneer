import com.ywzjvehicle.armor.ArmorConfig;
import com.ywzjvehicle.armor.ChemicalModel;
import com.ywzjvehicle.armor.SpallModel;

import java.lang.reflect.Method;

/**
 * The rest of the Armor Pioneer fleet on the damage models.
 *
 * <p>Three things are checked. First, that every tank's cannon rounds are actually measured by one
 * of the models rather than quietly left on the old formula - the failure mode this whole suite
 * exists for is a round that looks configured and is not. Second, that the chemical ratings still
 * reproduce the old residual-times-face answers at the flank they were calibrated on, which is what
 * keeps the rework from rebalancing the tanks. Third, that the v108 -> v109 migration moves the
 * shipped entries and leaves a hand-rated round alone.
 */
public class FleetCheck {

    private static int failures;

    public static void main(String[] args) throws Exception {
        ArmorConfig.Profile defaults = defaults();
        ArmorConfig.Spall spall = defaults.spall;
        ArmorConfig.Chem chem = defaults.chem;

        System.out.println("== every Armor Pioneer cannon round is on a model ==");
        ArmorConfig.Ammo[] darts = {
                ammo(defaults, "armor_pioneer:cannon_105mm"),
                ammo(defaults, "armor_pioneer:cannon_105mm_ztz100"),
                ammo(defaults, "armor_pioneer:cannon_120mm_type10"),
                ammo(defaults, "armor_pioneer:cannon_125mm_dprk"),
                ammo(defaults, "armor_pioneer:cannon_120mm_dm33")
        };
        for (ArmorConfig.Ammo dart : darts) {
            check("a dart states its projectile",
                    dart != null && dart.usesKineticModel());
            check("every load of it does too",
                    dart != null
                            && (dart.variantCount() < 2 || dart.variant(1).usesKineticModel()));
        }
        String[] heatIds = {
                "armor_pioneer:cannon_105mm_heat",
                "armor_pioneer:cannon_105mm_bunker",
                "armor_pioneer:cannon_105mm_atgm",
                "armor_pioneer:cannon_120mm_jm12",
                "armor_pioneer:cannon_125mm_dprk_heat",
                "armor_pioneer:cannon_120mm_dm12"
        };
        for (String id : heatIds) {
            ArmorConfig.Ammo round = defaults.ammo_penetration.get(id);
            check(id.substring(id.lastIndexOf(':') + 1) + " is on the jet model",
                    round != null && round.usesHeatModel());
        }
        String[] heIds = {
                "armor_pioneer:cannon_105mm_he",
                "armor_pioneer:cannon_125mm_dprk_he",
                "armor_pioneer:cannon_120mm_dm11"
        };
        for (String id : heIds) {
            ArmorConfig.Ammo round = defaults.ammo_penetration.get(id);
            check(id.substring(id.lastIndexOf(':') + 1) + " is on the blast model",
                    round != null && round.usesHeModel() && round.penetration_damage == null);
        }

        System.out.println();
        System.out.println("== the jets still deal what they used to on a 120mm flank ==");
        // The calibration: each rating was placed so the new curve reproduces the old
        // (penetration - 120) x 4 at the flank figure the old table was rated on.
        for (String id : heatIds) {
            ArmorConfig.Ammo round = defaults.ammo_penetration.get(id);
            float old = (round.penetration - 120.0F) * 4.0F;
            float now = ChemicalModel.heatDamage(round, round.penetration, 120.0F, chem);
            boolean ok = Math.abs(now - old) <= old * 0.01F;
            System.out.printf("  %-46s old %7.1f  now %7.1f  %s%n", id, old, now,
                    ok ? "ok" : "FAIL");
            if (!ok) {
                failures++;
            }
        }

        System.out.println();
        System.out.println("== the HE shells burst with their charge ==");
        expect("VT-5 105mm HE inside", 1200.0,
                ChemicalModel.heDamage(ammo(defaults, "armor_pioneer:cannon_105mm_he"),
                        60.0F, 40.0F, chem), 0.5);
        expect("Tianma 125mm HE inside", 2000.0,
                ChemicalModel.heDamage(ammo(defaults, "armor_pioneer:cannon_125mm_dprk_he"),
                        60.0F, 40.0F, chem), 0.5);
        expect("Leopard DM11 inside", 2000.0,
                ChemicalModel.heDamage(ammo(defaults, "armor_pioneer:cannon_120mm_dm11"),
                        60.0F, 40.0F, chem), 0.5);

        System.out.println();
        System.out.println("== the debris figures, for the record (80mm / 100mm plate) ==");
        for (ArmorConfig.Ammo dart : darts) {
            if (dart == null) {
                continue;
            }
            float at80 = SpallModel.damage(spall, dart.penetration, 80.0F, 80.0F,
                    dart.caliberMm(), dart.muzzleVelocity(), dart.massKg());
            float at100 = SpallModel.damage(spall, dart.penetration, 100.0F, 100.0F,
                    dart.caliberMm(), dart.muzzleVelocity(), dart.massKg());
            System.out.printf("  %-46s %7.1f  %7.1f%n", dart.penetration + "mm " + dart.massKg()
                    + "kg@" + Math.round(dart.muzzleVelocity()), at80, at100);
            // Not monotone in thickness: the plate's own fragment mass grows with it and the
            // residual shrinks, and on a dart with a lot of spare penetration the first term wins
            // over the first 100mm. What must hold is that both are finite and positive.
            check("both figures are a real answer", at80 > 0.0F && at100 > 0.0F);
        }

        System.out.println();
        System.out.println("== v108 -> v109 ==");
        migration(defaults);

        finish();
    }

    private static void migration(ArmorConfig.Profile defaults) throws Exception {
        Method upgrade = ArmorConfig.class.getDeclaredMethod("upgrade", ArmorConfig.Profile.class);
        upgrade.setAccessible(true);

        ArmorConfig.Profile old = ArmorConfig.configReader().fromJson(
                ArmorConfig.configWriter().toJson(defaults), ArmorConfig.Profile.class);
        old.config_version = 108;
        ArmorConfig.Ammo dart = old.ammo_penetration.get("armor_pioneer:cannon_105mm");
        dart.mass_kg = null;
        dart.muzzle_velocity = null;
        dart.caliber_mm = null;
        dart.variant(1).mass_kg = null;
        dart.variant(1).muzzle_velocity = null;
        dart.variant(1).caliber_mm = null;
        ArmorConfig.Ammo heat = old.ammo_penetration.get("armor_pioneer:cannon_105mm_heat");
        heat.heat_damage = null;
        ArmorConfig.Ammo he = old.ammo_penetration.get("armor_pioneer:cannon_105mm_he");
        he.charge_kg = null;
        he.penetration_damage = 1200.0F;
        he.spall_damage_min = 300.0F;
        he.spall_damage_max = 1000.0F;
        he.spall_falloff_to = 900.0F;
        ArmorConfig.Ammo dm12 = old.ammo_penetration.get("armor_pioneer:cannon_120mm_dm12");
        dm12.heat_damage = null;
        ArmorConfig.Ammo jm12 = old.ammo_penetration.get("armor_pioneer:cannon_120mm_jm12");
        jm12.heat_damage = null;
        jm12.proximity.heat_damage = null;
        upgrade.invoke(null, old);
        check("the VT-5's dart and its load move to the model",
                dart.usesKineticModel() && dart.variant(1).usesKineticModel());
        check("its HEAT moves to the jet model", heat.usesHeatModel());
        check("its HE moves to the charge, flat figures cleared",
                he.usesHeModel() && he.penetration_damage == null);
        check("the Leopard's HEAT moves to the jet model", dm12.usesHeatModel());
        check("the Type 10's proximity mode moves too",
                jm12.usesHeatModel() && jm12.proximity.usesHeatModel());

        ArmorConfig.Profile hand = ArmorConfig.configReader().fromJson(
                ArmorConfig.configWriter().toJson(defaults), ArmorConfig.Profile.class);
        hand.config_version = 108;
        ArmorConfig.Ammo handDart = hand.ammo_penetration.get("armor_pioneer:cannon_105mm");
        handDart.penetration = 600.0F;
        handDart.mass_kg = null;
        handDart.muzzle_velocity = null;
        handDart.caliber_mm = null;
        upgrade.invoke(null, hand);
        check("a hand-rated dart keeps the old formula",
                handDart.mass_kg == null && !handDart.usesKineticModel());

        // v109 -> v110: the direction model. The switch is filled where the file has none, and a
        // file that has answered the question - even with "no" - keeps its answer.
        String[] fleet = {"armor_pioneer:vt5", "armor_pioneer:type10",
                "armor_pioneer:tianma20", "armor_pioneer:ztz100", "armor_pioneer:leopard2a4"};
        ArmorConfig.Profile scaled = ArmorConfig.configReader().fromJson(
                ArmorConfig.configWriter().toJson(defaults), ArmorConfig.Profile.class);
        scaled.config_version = 109;
        for (String id : fleet) {
            scaled.vehicles.get(id).kinetic_angle_scaling = null;
        }
        scaled.vehicles.get("armor_pioneer:ztz100").kinetic_angle_scaling = Boolean.FALSE;
        upgrade.invoke(null, scaled);
        check("the five composite tanks gain the direction factor",
                scaled.vehicles.get("armor_pioneer:vt5").kineticAngleScaling()
                        && scaled.vehicles.get("armor_pioneer:type10").kineticAngleScaling()
                        && scaled.vehicles.get("armor_pioneer:tianma20").kineticAngleScaling()
                        && scaled.vehicles.get("armor_pioneer:leopard2a4").kineticAngleScaling());
        check("a file that already answered 'no' keeps it",
                !scaled.vehicles.get("armor_pioneer:ztz100").kineticAngleScaling());
    }

    private static ArmorConfig.Ammo ammo(ArmorConfig.Profile profile, String id) {
        return profile.ammo_penetration.get(id);
    }

    private static ArmorConfig.Profile defaults() throws Exception {
        Method method = ArmorConfig.class.getDeclaredMethod("defaultProfile");
        method.setAccessible(true);
        return (ArmorConfig.Profile) method.invoke(null);
    }

    private static void expect(String what, double want, double got, double tolerance) {
        boolean ok = Math.abs(want - got) <= tolerance;
        System.out.printf("  %-46s %8.1f  (want %8.1f)  %s%n", what, got, want,
                ok ? "ok" : "FAIL");
        if (!ok) {
            failures++;
        }
    }

    private static void check(String what, boolean ok) {
        System.out.printf("  %-62s %s%n", what, ok ? "ok" : "FAIL");
        if (!ok) {
            failures++;
        }
    }

    private static void finish() {
        if (failures > 0) {
            System.out.println("RESULT: " + failures + " fleet check(s) failed");
            System.exit(1);
        }
        System.out.println("RESULT: every Armor Pioneer cannon round is on a model, the jets "
                + "reproduce their old flank figures, and the fleet migrates");
    }
}
