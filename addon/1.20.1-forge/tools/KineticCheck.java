import com.ywzjvehicle.armor.ArmorConfig;
import com.ywzjvehicle.armor.SpallModel;

import java.lang.reflect.Method;

/**
 * The four darts that use the debris model: their certified inputs, the penetration and velocity
 * the model derives from the shipped range curve, the damage at the K2's and VT-4's own plates, and
 * the migration that opts an older file in.
 *
 * <p>The arithmetic is {@link SpallModel}'s own, not a copy: a check that rebuilt the fragment
 * formula here would agree with itself and not with the game.
 */
public class KineticCheck {

    private static int failures;

    public static void main(String[] args) throws Exception {
        ArmorConfig.Profile defaults = defaults();

        System.out.println("== the four darts state their inputs ==");
        ArmorConfig.Ammo k279 = defaults.ammo_penetration.get("armor_pioneer:cannon_120mm");
        ArmorConfig.Ammo vt4 = defaults.ammo_penetration.get("armor_pioneer:cannon_125mm");
        check("the K2 slot exists", k279 != null);
        check("the VT-4 slot exists", vt4 != null);
        if (k279 == null || vt4 == null) {
            finish();
            return;
        }
        check("K279: 8.3kg, 1690m/s, 120mm",
                k279.usesKineticModel() && near(k279.massKg(), 8.3F)
                        && near(k279.muzzleVelocity(), 1690.0F) && near(k279.caliberMm(), 120.0F));
        check("BTA4: 7.5kg, 1700m/s, 125mm",
                vt4.usesKineticModel() && near(vt4.massKg(), 7.5F)
                        && near(vt4.muzzleVelocity(), 1700.0F) && near(vt4.caliberMm(), 125.0F));
        ArmorConfig.Ammo dm53 = firstVariant(k279);
        ArmorConfig.Ammo dtc10e = firstVariant(vt4);
        check("DM53: 8.5kg, 1750m/s, 120mm",
                dm53 != null && dm53.usesKineticModel() && near(dm53.massKg(), 8.5F)
                        && near(dm53.muzzleVelocity(), 1750.0F) && near(dm53.caliberMm(), 120.0F));
        check("DTC10E: 7.6kg, 1740m/s, 125mm",
                dtc10e != null && dtc10e.usesKineticModel() && near(dtc10e.massKg(), 7.6F)
                        && near(dtc10e.muzzleVelocity(), 1740.0F) && near(dtc10e.caliberMm(), 125.0F));

        System.out.println();
        System.out.println("== the range curve still supplies the penetration ==");
        expect("K279 @0m", 600.0, SpallModel.penetration(k279, 0.0), 0.5);
        expect("K279 @1km", 588.0, SpallModel.penetration(k279, 1000.0), 0.5);
        expect("DM53 @1km", 686.0, SpallModel.penetration(dm53, 1000.0), 0.5);
        expect("BTA4 @1km", 588.0, SpallModel.penetration(vt4, 1000.0), 0.5);
        expect("DTC10E @1km", 666.4, SpallModel.penetration(dtc10e, 1000.0), 0.5);

        System.out.println();
        System.out.println("== velocity is recovered through the exponent ==");
        // beta = 0.5, so v/v0 = share^2: 0.98 at 1km becomes 0.9604 of the muzzle velocity.
        expect("K279 @1km", 1623.0, SpallModel.impactVelocity(k279, 1000.0), 0.5);
        expect("DM53 @1km", 1680.7, SpallModel.impactVelocity(dm53, 1000.0), 0.5);
        expect("DTC10E @1km", 1671.1, SpallModel.impactVelocity(dtc10e, 1000.0), 0.5);
        expect("K279 @0m keeps its muzzle velocity", 1690.0,
                SpallModel.impactVelocity(k279, 0.0), 0.01);

        System.out.println();
        System.out.println("== the debris damage at 80mm ==");
        ArmorConfig.Spall spall = ArmorConfig.spall();
        expect("K279", 2779.0, damage(k279, spall, 80.0, 0.0), 2.0);
        expect("DM53", 3099.0, damage(dm53, spall, 80.0, 0.0), 2.0);
        expect("BTA4", 2674.0, damage(vt4, spall, 80.0, 0.0), 2.0);
        expect("DTC10E", 2878.0, damage(dtc10e, spall, 80.0, 0.0), 2.0);
        expect("DM53 @1km", 2848.0, damage(dm53, spall, 80.0, 1000.0), 3.0);

        System.out.println();
        System.out.println("== thin plates over-penetrate ==");
        expect("DM53 through 40mm", 1927.0, damage(dm53, spall, 40.0, 0.0), 3.0);
        expect("DM53 through 20mm", 816.0, damage(dm53, spall, 20.0, 0.0), 3.0);
        expect("overmatch at 80mm is none", 1.0, SpallModel.overmatch(80.0F, 120.0F, spall), 0.001);
        expect("overmatch at 40mm", 0.6429, SpallModel.overmatch(40.0F, 120.0F, spall), 0.002);
        expect("overmatch at 20mm", 0.2857, SpallModel.overmatch(20.0F, 120.0F, spall), 0.002);

        System.out.println();
        System.out.println("== the chemical rounds are untouched ==");
        ArmorConfig.Ammo heat = defaults.ammo_penetration.get("armor_pioneer:cannon_120mm_heat");
        ArmorConfig.Ammo he = defaults.ammo_penetration.get("armor_pioneer:cannon_120mm_he");
        check("the K2's HEAT is not on the model", heat != null && !heat.usesKineticModel());
        check("the K2's HE is not on the model", he != null && !he.usesKineticModel());

        System.out.println();
        System.out.println("== the new fields survive a round trip ==");
        ArmorConfig.Profile trip = ArmorConfig.configReader().fromJson(
                ArmorConfig.configWriter().toJson(defaults), ArmorConfig.Profile.class);
        ArmorConfig.Ammo tripK279 = trip.ammo_penetration.get("armor_pioneer:cannon_120mm");
        ArmorConfig.Ammo tripVt4 = trip.ammo_penetration.get("armor_pioneer:cannon_125mm");
        check("the K2 slot came back with its inputs",
                tripK279 != null && tripK279.usesKineticModel()
                        && near(tripK279.massKg(), 8.3F) && near(tripK279.caliberMm(), 120.0F));
        check("the VT-4 slot came back with its inputs",
                tripVt4 != null && tripVt4.usesKineticModel()
                        && near(tripVt4.massKg(), 7.5F) && near(tripVt4.caliberMm(), 125.0F));
        check("the spall block came back", trip.spall != null
                && near(trip.spall.energy_per_damage, 1000.0F));

        System.out.println();
        System.out.println("== v104 -> v105 ==");
        migration(defaults);

        finish();
    }

    private static double damage(ArmorConfig.Ammo round, ArmorConfig.Spall spall,
                                 double thickness, double range) {
        return SpallModel.damage(spall, SpallModel.penetration(round, range), (float) thickness,
                (float) thickness, round.caliberMm(), SpallModel.impactVelocity(round, range),
                round.massKg());
    }

    private static void migration(ArmorConfig.Profile defaults) throws Exception {
        Method upgrade = ArmorConfig.class.getDeclaredMethod("upgrade", ArmorConfig.Profile.class);
        upgrade.setAccessible(true);

        ArmorConfig.Profile old = ArmorConfig.configReader().fromJson(
                ArmorConfig.configWriter().toJson(defaults), ArmorConfig.Profile.class);
        old.config_version = 104;
        old.spall = null;
        ArmorConfig.Ammo oldK2 = old.ammo_penetration.get("armor_pioneer:cannon_120mm");
        ArmorConfig.Ammo oldVt4 = old.ammo_penetration.get("armor_pioneer:cannon_125mm");
        clear(oldK2);
        clear(oldVt4);
        clear(firstVariant(oldK2));
        clear(firstVariant(oldVt4));
        upgrade.invoke(null, old);
        check("a v104 file gains the spall block", old.spall != null);
        check("a v104 file's four darts gain their inputs",
                oldK2.usesKineticModel() && oldVt4.usesKineticModel()
                        && firstVariant(oldK2).usesKineticModel()
                        && firstVariant(oldVt4).usesKineticModel());

        ArmorConfig.Profile hand = ArmorConfig.configReader().fromJson(
                ArmorConfig.configWriter().toJson(defaults), ArmorConfig.Profile.class);
        hand.config_version = 104;
        hand.spall = null;
        ArmorConfig.Ammo handK2 = hand.ammo_penetration.get("armor_pioneer:cannon_120mm");
        handK2.penetration = 650.0F;
        clear(handK2);
        upgrade.invoke(null, hand);
        check("a hand-rated round keeps its old formula", handK2.mass_kg == null
                && handK2.penetration == 650.0F);
    }

    private static void clear(ArmorConfig.Ammo round) {
        if (round == null) {
            return;
        }
        round.mass_kg = null;
        round.muzzle_velocity = null;
        round.mass_exponent = null;
        round.velocity_exponent = null;
        round.caliber_mm = null;
    }

    private static ArmorConfig.Ammo firstVariant(ArmorConfig.Ammo round) {
        return round == null || round.variants == null || round.variants.isEmpty()
                ? null : round.variants.get(0);
    }

    private static ArmorConfig.Profile defaults() throws Exception {
        Method method = ArmorConfig.class.getDeclaredMethod("defaultProfile");
        method.setAccessible(true);
        return (ArmorConfig.Profile) method.invoke(null);
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) <= 0.01F;
    }

    private static void expect(String what, double want, double got, double tolerance) {
        boolean ok = Math.abs(want - got) <= tolerance;
        System.out.printf("  %-44s %9.2f  (want %9.2f)  %s%n", what, got, want,
                ok ? "ok" : "FAIL");
        if (!ok) {
            failures++;
        }
    }

    private static void check(String what, boolean ok) {
        System.out.printf("  %-44s %s%n", what, ok ? "ok" : "FAIL");
        if (!ok) {
            failures++;
        }
    }

    private static void finish() {
        if (failures > 0) {
            System.out.println("RESULT: " + failures + " kinetic-model check(s) failed");
            System.exit(1);
        }
        System.out.println("RESULT: the debris model is configured, wired and migrated");
    }
}
