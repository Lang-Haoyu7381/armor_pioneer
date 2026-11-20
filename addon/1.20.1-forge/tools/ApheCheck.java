import com.ywzjvehicle.armor.ArmorConfig;
import com.ywzjvehicle.armor.ChemicalModel;
import com.ywzjvehicle.armor.SpallModel;

import java.lang.reflect.Method;

/**
 * The Tiger's three rounds on the new models, and the APHE effect itself.
 *
 * <p>An armour-piercing shell is two events: the hole the projectile makes, measured by the debris
 * model, and the base charge which can only light once the plate is behind it. This check pins both
 * halves and the fuze rule between them: the burst is worth what its charge says, and it only
 * exists on a round that got in.
 */
public class ApheCheck {

    private static int failures;

    public static void main(String[] args) throws Exception {
        ArmorConfig.Profile defaults = defaults();
        ArmorConfig.Spall spall = defaults.spall;
        ArmorConfig.Chem chem = defaults.chem;
        ArmorConfig.Ammo ap = defaults.ammo_penetration.get("tank_assault:cannon_88mm_pzgr_39");
        ArmorConfig.Ammo apcr = defaults.ammo_penetration.get("tank_assault:cannon_88mm_pzgr_40");
        ArmorConfig.Ammo he = defaults.ammo_penetration.get("tank_assault:cannon_88mm_sprgr_43");

        System.out.println("== the three rounds state their projectiles ==");
        check("the PzGr 39 is on the debris model", ap != null && ap.usesKineticModel());
        check("... and carries a base charge", ap != null && ap.usesApheModel());
        check("... of the captured-document 60 g of cyclonite", ap != null
                && Math.abs(ap.charge_kg - 0.10F) < 0.001F);
        check("... and no longer rolls a bonus band",
                ap != null && !ap.hasPenetrationBonus());
        check("the PzGr 40 is a kinetic round with no charge", apcr != null
                && apcr.usesKineticModel() && !apcr.usesApheModel());
        check("the Sprgr 43 is the 0.70 kg blast charge", he != null
                && he.usesHeModel() && Math.abs(he.charge_kg - 0.70F) < 0.001F);
        check("... and its old flat damage is gone", he != null && he.penetration_damage == null);

        System.out.println();
        System.out.println("== the hole ==");
        expect("PzGr 39 through 80mm", 333.2, spallDamage(ap, 152.0F, 80.0F, spall), 0.5);
        expect("PzGr 39 through 100mm", 244.6, spallDamage(ap, 152.0F, 100.0F, spall), 0.5);
        expect("PzGr 40 through 80mm", 373.9, spallDamage(apcr, 156.0F, 80.0F, spall), 0.5);
        expect("PzGr 40 through 100mm", 281.6, spallDamage(apcr, 156.0F, 100.0F, spall), 0.5);

        System.out.println();
        System.out.println("== the burst ==");
        // 0.10 kg of TNT equivalent inside a closed compartment: 0.10 x 4.184 MJ / 7322 J, times
        // the confinement factor of three. It does not depend on the plate, only on getting in.
        expect("the PzGr 39 base charge", 171.4,
                ChemicalModel.burstDamage(ap, chem), 0.5);
        expect("the APCR has none", 0.0,
                ChemicalModel.burstDamage(apcr, chem), 0.001);
        check("an APHE round is not a high-explosive round",
                ap != null && !ap.usesHeModel());

        System.out.println();
        System.out.println("== the HE shell ==");
        // 60mm of rated penetration means a 40mm roof is perforated and bursts inside; above that
        // the shell is held out and scabs, and the scabbable thickness for 0.70 kg is 63.9mm.
        expect("0.70 kg bursts inside a 40mm roof", 400.0,
                ChemicalModel.heDamage(he, 60.0F, 40.0F, chem), 0.5);
        expect("... scabs a 61mm plate it is held out of", 14.7,
                ChemicalModel.heDamage(he, 60.0F, 61.0F, chem), 0.2);
        expect("... and a 120mm plate confines it", 0.0,
                ChemicalModel.heDamage(he, 60.0F, 120.0F, chem), 0.01);

        System.out.println();
        System.out.println("== the velocity still comes off the round's own curve ==");
        float v = SpallModel.impactVelocity(ap, 1000.0);
        expect("PzGr 39 at 1km", 679.3, v, 1.0);
        expect("PzGr 39 penetration at 1km", 122.8, SpallModel.penetration(ap, 1000.0), 0.2);
        expect("PzGr 40 at 1km", 826.6, SpallModel.impactVelocity(apcr, 1000.0), 1.0);

        System.out.println();
        System.out.println("== v107 -> v108 ==");
        migration(defaults);

        finish();
    }

    private static float spallDamage(ArmorConfig.Ammo round, float penetration, float thickness,
                                     ArmorConfig.Spall spall) {
        return SpallModel.damage(spall, penetration, thickness, thickness, round.caliberMm(),
                round.muzzleVelocity(), round.massKg());
    }

    private static void migration(ArmorConfig.Profile defaults) throws Exception {
        Method upgrade = ArmorConfig.class.getDeclaredMethod("upgrade", ArmorConfig.Profile.class);
        upgrade.setAccessible(true);

        ArmorConfig.Profile old = ArmorConfig.configReader().fromJson(
                ArmorConfig.configWriter().toJson(defaults), ArmorConfig.Profile.class);
        old.config_version = 107;
        ArmorConfig.Ammo ap = old.ammo_penetration.get("tank_assault:cannon_88mm_pzgr_39");
        ArmorConfig.Ammo apcr = old.ammo_penetration.get("tank_assault:cannon_88mm_pzgr_40");
        ArmorConfig.Ammo he = old.ammo_penetration.get("tank_assault:cannon_88mm_sprgr_43");
        for (ArmorConfig.Ammo round : new ArmorConfig.Ammo[]{ap, apcr}) {
            round.mass_kg = null;
            round.muzzle_velocity = null;
            round.caliber_mm = null;
        }
        ap.charge_kg = null;
        ap.penetration_bonus_min = 20.0F;
        ap.penetration_bonus_max = 152.0F;
        ap.penetration_bonus_max_at = 152.0F;
        he.charge_kg = null;
        he.penetration_damage = 300.0F;
        he.spall_damage_min = 50.0F;
        he.spall_damage_max = 150.0F;
        upgrade.invoke(null, old);
        check("the PzGr 39 moves to the model", ap.usesKineticModel() && ap.usesApheModel());
        check("... and its band is retired", !ap.hasPenetrationBonus());
        check("the PzGr 40 moves to the model", apcr.usesKineticModel() && !apcr.usesApheModel());
        check("the Sprgr 43 gains its charge", he.usesHeModel());
        check("... and loses the flat damage", he.penetration_damage == null);

        ArmorConfig.Profile hand = ArmorConfig.configReader().fromJson(
                ArmorConfig.configWriter().toJson(defaults), ArmorConfig.Profile.class);
        hand.config_version = 107;
        ArmorConfig.Ammo handAp = hand.ammo_penetration.get("tank_assault:cannon_88mm_pzgr_39");
        handAp.mass_kg = null;
        handAp.muzzle_velocity = null;
        handAp.caliber_mm = null;
        handAp.charge_kg = null;
        handAp.penetration_bonus_min = 20.0F;
        handAp.penetration_bonus_max = 200.0F;
        handAp.penetration_bonus_max_at = 152.0F;
        upgrade.invoke(null, hand);
        check("a hand-tuned band keeps the old behaviour",
                !handAp.usesKineticModel() && handAp.penetration_bonus_max == 200.0F);
    }

    private static ArmorConfig.Profile defaults() throws Exception {
        Method method = ArmorConfig.class.getDeclaredMethod("defaultProfile");
        method.setAccessible(true);
        return (ArmorConfig.Profile) method.invoke(null);
    }

    private static void expect(String what, double want, double got, double tolerance) {
        boolean ok = Math.abs(want - got) <= tolerance;
        System.out.printf("  %-46s %9.1f  (want %9.1f)  %s%n", what, got, want,
                ok ? "ok" : "FAIL");
        if (!ok) {
            failures++;
        }
    }

    private static void check(String what, boolean ok) {
        System.out.printf("  %-46s %s%n", what, ok ? "ok" : "FAIL");
        if (!ok) {
            failures++;
        }
    }

    private static void finish() {
        if (failures > 0) {
            System.out.println("RESULT: " + failures + " Tiger / APHE check(s) failed");
            System.exit(1);
        }
        System.out.println("RESULT: the Tiger's rounds state their projectiles, and APHE is "
                + "hole plus burst");
    }
}
