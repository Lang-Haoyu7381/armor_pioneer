import com.ywzjvehicle.armor.ArmorConfig;
import com.ywzjvehicle.armor.PeleModel;

import java.lang.reflect.Method;

/**
 * The VT-4's lateral-effect round on its own model.
 *
 * <p>The arithmetic is {@link PeleModel}'s. What is asserted is the shape the model is supposed to
 * have, not just its numbers: nothing behind a plate it did not get through, almost nothing behind
 * a plate much thinner than the body, and a spray that grows with the plate the projectile paid for.
 * The migration is checked at both ends - a shipped entry moves, a hand-tuned one stays.
 */
public class PeleCheck {

    private static int failures;

    public static void main(String[] args) throws Exception {
        ArmorConfig.Profile defaults = defaults();
        ArmorConfig.Pele pele = defaults.pele;
        check("the pele block exists", pele != null);
        if (pele == null) {
            finish();
            return;
        }
        ArmorConfig.Ammo bts8 = defaults.ammo_penetration.get("armor_pioneer:cannon_125mm_pele");

        System.out.println("== the round states what is flying ==");
        check("the BTS8 is on the lateral-effect model", bts8 != null && bts8.usesPeleModel());
        check("... with a mass", bts8 != null && near(bts8.mass_kg, 7.0F));
        check("... a muzzle velocity", bts8 != null && near(bts8.muzzle_velocity, 1700.0F));
        check("... and a caliber", bts8 != null && near(bts8.caliberMm(), 125.0F));
        check("the old bonus band is gone from the fresh entry",
                bts8 != null && !bts8.hasPenetrationBonus());

        System.out.println();
        System.out.println("== the spray is the energy the plate took out of the body ==");
        // 7 kg at 1700 m/s: 10.1 MJ in flight. A 20mm plate (0.16 calibre) barely engages the
        // body, so the effect is a sixth of what the same round does to an 80mm plate.
        expect("20mm plate", 50.6, lateral(bts8, 320.0F, 20.0F, pele), 0.2);
        expect("80mm plate", 632.2, lateral(bts8, 320.0F, 80.0F, pele), 0.2);
        expect("150mm plate", 1185.4, lateral(bts8, 320.0F, 150.0F, pele), 0.2);
        check("the spray grows with the plate",
                lateral(bts8, 320.0F, 40.0F, pele) < lateral(bts8, 320.0F, 80.0F, pele)
                        && lateral(bts8, 320.0F, 80.0F, pele)
                        < lateral(bts8, 320.0F, 150.0F, pele));
        check("a plate it did not get through sprays nothing",
                near(lateral(bts8, 320.0F, 320.0F, pele), 0.0F));
        check("... and neither does one thicker than its penetration",
                near(lateral(bts8, 320.0F, 400.0F, pele), 0.0F));
        check("no plate at all is no lateral effect",
                near(lateral(bts8, 320.0F, 0.0F, pele), 0.0F));

        System.out.println();
        System.out.println("== the new fields survive a round trip ==");
        ArmorConfig.Profile trip = ArmorConfig.configReader().fromJson(
                ArmorConfig.configWriter().toJson(defaults), ArmorConfig.Profile.class);
        ArmorConfig.Ammo tripRound = trip.ammo_penetration.get("armor_pioneer:cannon_125mm_pele");
        check("the BTS8 came back on the model", tripRound != null && tripRound.usesPeleModel());
        check("the pele block came back", trip.pele != null
                && near(trip.pele.lateral_efficiency, 0.25F));

        System.out.println();
        System.out.println("== v106 -> v107 ==");
        migration(defaults);

        finish();
    }

    private static float lateral(ArmorConfig.Ammo round, float penetration, float thickness,
                                 ArmorConfig.Pele pele) {
        float velocity = com.ywzjvehicle.armor.SpallModel.impactVelocity(round, 0.0);
        return PeleModel.lateral(round, pele, penetration, thickness, round.caliberMm(), velocity);
    }

    private static void migration(ArmorConfig.Profile defaults) throws Exception {
        Method upgrade = ArmorConfig.class.getDeclaredMethod("upgrade", ArmorConfig.Profile.class);
        upgrade.setAccessible(true);

        ArmorConfig.Profile old = ArmorConfig.configReader().fromJson(
                ArmorConfig.configWriter().toJson(defaults), ArmorConfig.Profile.class);
        old.config_version = 106;
        old.pele = null;
        ArmorConfig.Ammo oldRound = old.ammo_penetration.get("armor_pioneer:cannon_125mm_pele");
        oldRound.pele = null;
        oldRound.mass_kg = null;
        oldRound.muzzle_velocity = null;
        oldRound.caliber_mm = null;
        oldRound.penetration_bonus_min = 20.0F;
        oldRound.penetration_bonus_max = 300.0F;
        oldRound.penetration_bonus_max_at = 150.0F;
        upgrade.invoke(null, old);
        check("a v106 file gains the pele block", old.pele != null);
        check("a shipped BTS8 moves to the model", oldRound.usesPeleModel());
        check("... and its bonus band is retired", !oldRound.hasPenetrationBonus());

        ArmorConfig.Profile hand = ArmorConfig.configReader().fromJson(
                ArmorConfig.configWriter().toJson(defaults), ArmorConfig.Profile.class);
        hand.config_version = 106;
        hand.pele = null;
        ArmorConfig.Ammo handRound = hand.ammo_penetration.get("armor_pioneer:cannon_125mm_pele");
        handRound.pele = null;
        handRound.mass_kg = null;
        handRound.muzzle_velocity = null;
        handRound.caliber_mm = null;
        handRound.penetration_bonus_max = 400.0F;
        handRound.penetration_bonus_min = 20.0F;
        handRound.penetration_bonus_max_at = 150.0F;
        upgrade.invoke(null, hand);
        check("a hand-tuned band keeps the old behaviour",
                handRound.pele == null && handRound.penetration_bonus_max == 400.0F);
    }

    private static ArmorConfig.Profile defaults() throws Exception {
        Method method = ArmorConfig.class.getDeclaredMethod("defaultProfile");
        method.setAccessible(true);
        return (ArmorConfig.Profile) method.invoke(null);
    }

    private static boolean near(Float a, float b) {
        return a != null && Math.abs(a - b) <= 0.01F;
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) <= 0.01F;
    }

    private static void expect(String what, double want, double got, double tolerance) {
        boolean ok = Math.abs(want - got) <= tolerance;
        System.out.printf("  %-40s %9.1f  (want %9.1f)  %s%n", what, got, want,
                ok ? "ok" : "FAIL");
        if (!ok) {
            failures++;
        }
    }

    private static void check(String what, boolean ok) {
        System.out.printf("  %-40s %s%n", what, ok ? "ok" : "FAIL");
        if (!ok) {
            failures++;
        }
    }

    private static void finish() {
        if (failures > 0) {
            System.out.println("RESULT: " + failures + " lateral-effect check(s) failed");
            System.exit(1);
        }
        System.out.println("RESULT: the lateral-effect round is configured, wired and migrated");
    }
}
