import com.ywzjvehicle.armor.ArmorConfig;
import com.ywzjvehicle.armor.ChemicalModel;

import java.lang.reflect.Method;

/**
 * The chemical rounds on the new model: a shaped charge's surviving jet and a high-explosive
 * shell's inside burst or outside scab.
 *
 * <p>The arithmetic is {@link ChemicalModel}'s own. The figures asserted here are the old
 * residual-times-face answers at the points the shipped rounds were calibrated on, so the new
 * curve cannot quietly rebalance the tanks while its shape changes.
 */
public class ChemCheck {

    private static int failures;

    public static void main(String[] args) throws Exception {
        ArmorConfig.Profile defaults = defaults();
        ArmorConfig.Chem chem = defaults.chem;
        check("the chem block exists", chem != null);
        if (chem == null) {
            finish();
            return;
        }
        ArmorConfig.Ammo k2Heat = defaults.ammo_penetration.get("armor_pioneer:cannon_120mm_heat");
        ArmorConfig.Ammo k2He = defaults.ammo_penetration.get("armor_pioneer:cannon_120mm_he");
        ArmorConfig.Ammo kstam = defaults.ammo_penetration.get("armor_pioneer:cannon_120mm_kstam");
        ArmorConfig.Ammo vt4Heat = defaults.ammo_penetration.get("armor_pioneer:cannon_125mm_heat");
        ArmorConfig.Ammo vt4He = defaults.ammo_penetration.get("armor_pioneer:cannon_125mm_he");

        System.out.println("== the five rounds state their inputs ==");
        check("the K2 HEAT is on the jet model", k2Heat != null && k2Heat.usesHeatModel());
        check("the K2 HE is on the blast model", k2He != null && k2He.usesHeModel());
        check("the KSTAM EFP is on the jet model", kstam != null && kstam.usesHeatModel());
        check("the VT-4 HEAT is on the jet model", vt4Heat != null && vt4Heat.usesHeatModel());
        check("the VT-4 HE is on the blast model", vt4He != null && vt4He.usesHeModel());

        System.out.println();
        System.out.println("== shaped charges ==");
        // 480mm jet against 120mm: the old 360 residual x 4 face multiplier.
        expect("K2 HEAT vs 120mm", 1440.0, heat(k2Heat, 480.0, 120.0, chem), 1.0);
        expect("K2 HEAT vs 60mm", 1591.7, heat(k2Heat, 480.0, 60.0, chem), 1.0);
        // 550mm jet against 120mm behind a spent panel: the old 430 x 4.
        expect("VT-4 HEAT vs 120mm", 1729.8, heat(vt4Heat, 550.0, 120.0, chem), 1.0);
        // 200mm EFP against a 40mm roof: the old 160 x 5.
        expect("KSTAM vs 40mm roof", 798.5, heat(kstam, 200.0, 40.0, chem), 1.0);
        expect("a jet that does not get through does nothing", 0.0,
                heat(k2Heat, 480.0, 480.0, chem), 0.01);

        System.out.println();
        System.out.println("== high explosive ==");
        // 3.5kg TNT = 14.644 MJ; 7322 J per point of damage -> 2000 inside.
        expect("K2 HE inside a 40mm roof", 2000.0, he(k2He, 60.0, 40.0, chem), 1.0);
        expect("VT-4 HE inside a 40mm roof", 2285.7, he(vt4He, 60.0, 40.0, chem), 1.0);
        // Outside: the scabbable thickness for 3.5kg is 109.3mm.
        expect("K2 HE scabs a 90mm plate", 282.8, he(k2He, 60.0, 90.0, chem), 1.0);
        expect("... and a 120mm plate confines it", 0.0, he(k2He, 60.0, 120.0, chem), 0.01);

        System.out.println();
        System.out.println("== the new fields survive a round trip ==");
        ArmorConfig.Profile trip = ArmorConfig.configReader().fromJson(
                ArmorConfig.configWriter().toJson(defaults), ArmorConfig.Profile.class);
        ArmorConfig.Ammo tripHeat = trip.ammo_penetration.get("armor_pioneer:cannon_120mm_heat");
        ArmorConfig.Ammo tripHe = trip.ammo_penetration.get("armor_pioneer:cannon_120mm_he");
        check("the K2 HEAT came back rated",
                tripHeat != null && tripHeat.usesHeatModel() && near(tripHeat.heat_damage, 1736.0F));
        check("the K2 HE came back with its charge",
                tripHe != null && tripHe.usesHeModel() && near(tripHe.charge_kg, 3.5F));
        check("the chem block came back", trip.chem != null
                && near(trip.chem.he_energy_per_damage, 7322.0F));

        System.out.println();
        System.out.println("== v105 -> v106 ==");
        migration(defaults);

        finish();
    }

    private static float heat(ArmorConfig.Ammo round, double penetration, double thickness,
                              ArmorConfig.Chem chem) {
        return ChemicalModel.heatDamage(round, (float) penetration, (float) thickness, chem);
    }

    private static float he(ArmorConfig.Ammo round, double penetration, double thickness,
                            ArmorConfig.Chem chem) {
        return ChemicalModel.heDamage(round, (float) penetration, (float) thickness, chem);
    }

    private static void migration(ArmorConfig.Profile defaults) throws Exception {
        Method upgrade = ArmorConfig.class.getDeclaredMethod("upgrade", ArmorConfig.Profile.class);
        upgrade.setAccessible(true);

        ArmorConfig.Profile old = ArmorConfig.configReader().fromJson(
                ArmorConfig.configWriter().toJson(defaults), ArmorConfig.Profile.class);
        old.config_version = 105;
        old.chem = null;
        ArmorConfig.Ammo oldHeat = old.ammo_penetration.get("armor_pioneer:cannon_120mm_heat");
        ArmorConfig.Ammo oldHe = old.ammo_penetration.get("armor_pioneer:cannon_120mm_he");
        ArmorConfig.Ammo oldKstam = old.ammo_penetration.get("armor_pioneer:cannon_120mm_kstam");
        ArmorConfig.Ammo oldVtHeat = old.ammo_penetration.get("armor_pioneer:cannon_125mm_heat");
        ArmorConfig.Ammo oldVtHe = old.ammo_penetration.get("armor_pioneer:cannon_125mm_he");
        for (ArmorConfig.Ammo round : new ArmorConfig.Ammo[]{oldHeat, oldHe, oldKstam,
                oldVtHeat, oldVtHe}) {
            round.heat_damage = null;
            round.charge_kg = null;
        }
        upgrade.invoke(null, old);
        check("a v105 file gains the chem block", old.chem != null);
        check("a v105 file's five rounds gain their inputs", oldHeat.usesHeatModel()
                && oldHe.usesHeModel() && oldKstam.usesHeatModel()
                && oldVtHeat.usesHeatModel() && oldVtHe.usesHeModel());

        ArmorConfig.Profile hand = ArmorConfig.configReader().fromJson(
                ArmorConfig.configWriter().toJson(defaults), ArmorConfig.Profile.class);
        hand.config_version = 105;
        hand.chem = null;
        ArmorConfig.Ammo handHeat = hand.ammo_penetration.get("armor_pioneer:cannon_120mm_heat");
        handHeat.penetration = 500.0F;
        handHeat.heat_damage = null;
        upgrade.invoke(null, hand);
        check("a hand-rated jet keeps the old formula", handHeat.heat_damage == null
                && handHeat.penetration == 500.0F);
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
            System.out.println("RESULT: " + failures + " chemical-model check(s) failed");
            System.exit(1);
        }
        System.out.println("RESULT: the chemical models are configured, wired and migrated");
    }
}
