package com.ywzjvehicle.armor;

/**
 * Damage for shaped charges and high-explosive shells, for the rounds that state the inputs.
 *
 * <p>A jet and a bursting charge are not the same thing, and neither is a projectile:
 *
 * <ul>
 *   <li><b>Shaped charge.</b> The jet is consumed by the plate, so there is no over-penetration.
 *       Its damage falls as {@code (r/P)^0.65} - a jet that only just got through does very
 *       little, one with most of its length through is devastating, and the exponent keeps the
 *       top end from running away.</li>
 *   <li><b>High explosive, perforating.</b> The whole charge is inside, so the damage is the
 *       charge energy divided by the calibration, independent of how far through the plate it
 *       was.</li>
 *   <li><b>High explosive, held out.</b> The blast scabs the plate's inner face. The scabbable
 *       thickness grows with the cube root of the charge; above it the plate confines the blast
 *       and nothing reaches the compartment.</li>
 * </ul>
 *
 * <p>Like {@link SpallModel}, this class reads no world: every figure comes in, which is what lets
 * the offline check assert the same arithmetic the hit path runs.
 */
public final class ChemicalModel {

    private ChemicalModel() {
    }

    /** Joules in a kilogram of TNT. */
    private static final double TNT_J_PER_KG = 4.184E6;

    /**
     * @return the damage a shaped charge does after its jet has paid for the plate.
     *
     * @param effectivePenetration what the jet still has after the outer layers, in mm
     * @param effectiveThickness   the plate along the path, in mm
     */
    public static float heatDamage(ArmorConfig.Ammo round, float effectivePenetration,
                                   float effectiveThickness, ArmorConfig.Chem config) {
        if (round == null || !round.usesHeatModel() || config == null) {
            return 0.0F;
        }
        double rated = round.heat_damage;
        double jet = Math.max(1.0, round.penetration);
        double residual = Math.max(0.0, effectivePenetration - effectiveThickness);
        double share = Math.min(1.0, residual / jet);
        double exponent = Math.max(0.01F, config.heat_damage_exponent);
        double damage = rated * Math.pow(share, exponent);
        return Double.isFinite(damage) ? (float) Math.max(0.0, damage) : 0.0F;
    }

    /**
     * @return the damage a high-explosive shell does - inside if it perforated, as plate scab if
     *         it did not.
     *
     * @param effectivePenetration what is left of the shell's own penetration after outer layers
     * @param effectiveThickness   the plate along the path, in mm
     */
    /**
     * @return the burst an armour-piercing shell's base charge puts into the compartment it just
     *         penetrated. Unlike {@link #heDamage} there is no scab and no inside/outside split:
     *         the fuze is behind the nose and can only light after the plate is through, so a
     *         round that did not get in contributes nothing.
     */
    public static float burstDamage(ArmorConfig.Ammo round, ArmorConfig.Chem config) {
        if (round == null || config == null || !round.usesApheModel()) {
            return 0.0F;
        }
        double energy = round.charge_kg * TNT_J_PER_KG;
        double perDamage = Math.max(1.0F, config.he_energy_per_damage);
        double confinement = Math.max(0.0F, config.aphe_confinement);
        double damage = confinement * energy / perDamage;
        return Double.isFinite(damage) ? (float) Math.max(0.0, damage) : 0.0F;
    }

    public static float heDamage(ArmorConfig.Ammo round, float effectivePenetration,
                                 float effectiveThickness, ArmorConfig.Chem config) {
        if (round == null || !round.usesHeModel() || config == null) {
            return 0.0F;
        }
        double charge = round.charge_kg;
        double energy = charge * TNT_J_PER_KG;
        double perDamage = Math.max(1.0F, config.he_energy_per_damage);
        if (effectivePenetration >= effectiveThickness) {
            double inside = Math.max(0.0F, config.he_eta_inside) * energy / perDamage;
            return Double.isFinite(inside) ? (float) Math.max(0.0, inside) : 0.0F;
        }
        double scabbable = Math.max(0.0F, config.he_scab_thickness_per_kg13)
                * Math.cbrt(Math.max(0.0, charge));
        if (scabbable <= 0.0) {
            return 0.0F;
        }
        double share = 1.0 - Math.max(0.0, effectiveThickness) / scabbable;
        share = Math.max(0.0, Math.min(1.0, share));
        double scab = Math.max(0.0F, config.he_eta_scab) * share * energy / perDamage;
        return Double.isFinite(scab) ? (float) Math.max(0.0, scab) : 0.0F;
    }
}
