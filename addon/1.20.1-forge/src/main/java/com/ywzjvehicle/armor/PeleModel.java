package com.ywzjvehicle.armor;

/**
 * The lateral effect of a PELE round, for the rounds that state what is flying.
 *
 * <p>A PELE projectile is a thin-walled tube with an inert filling behind a hard nose. The nose
 * opens the plate, the plate slows it, and the rear of the body - still doing most of its speed -
 * compresses against it until the wall ruptures and the material sprays sideways behind the plate.
 * Three consequences follow, and they are the three terms of the model:
 *
 * <ul>
 *   <li><b>The fragments are the projectile's own.</b> The energy that throws them is the energy
 *       the plate took out of the projectile - {@code ½m(v_in² − v_out²)} - not plate material and
 *       not a fixed roll. A heavier or faster round therefore sprays more.</li>
 *   <li><b>A thin plate barely engages it.</b> If the plate is much thinner than the nose, the
 *       projectile passes through with a clean hole and the body never compresses; the effect
 *       scales with plate thickness in calibres up to {@code activation_calibers}.</li>
 *   <li><b>It is added to the hole.</b> The residual-times-multiplier damage of the perforation is
 *       still there - that is what makes the round usable against a thin-skinned target - and the
 *       lateral spray is what it does on top.</li>
 * </ul>
 *
 * <p>Like the other two models this class reads no world: every figure comes in, so the offline
 * check asserts the same arithmetic the hit path runs.
 */
public final class PeleModel {

    private PeleModel() {
    }

    /**
     * @return the extra damage the round's lateral fragments do after it has perforated a plate,
     *         or zero if it did not get through.
     *
     * @param round               the flying round, for its mass
     * @param config              the lateral-effect constants
     * @param effectivePenetration the round's penetration after outer layers, in mm
     * @param effectiveThickness  the plate along the round's path, in mm
     * @param caliberMm           the projectile diameter, in mm
     * @param velocity            the impact velocity, in m/s
     */
    public static float lateral(ArmorConfig.Ammo round, ArmorConfig.Pele config,
                                float effectivePenetration, float effectiveThickness,
                                float caliberMm, float velocity) {
        if (round == null || config == null || effectivePenetration <= 0.0F
                || velocity <= 0.0F) {
            return 0.0F;
        }
        double penetration = effectivePenetration;
        double thickness = Math.max(0.0, effectiveThickness);
        if (thickness >= penetration) {
            return 0.0F;
        }
        // The plate takes this much of the projectile's kinetic energy; the rest leaves with it.
        double residual = (penetration - thickness) / penetration;
        double exitSpeed = velocity * Math.sqrt(residual);
        double lost = 0.5 * Math.max(0.0, round.massKg())
                * (velocity * velocity - exitSpeed * exitSpeed);
        double engage = 1.0;
        double calibers = Math.max(0.01F, config.activation_calibers);
        if (caliberMm > 0.0F) {
            engage = Math.min(1.0, thickness / (calibers * caliberMm));
        }
        double perDamage = Math.max(1.0F, config.energy_per_damage);
        double damage = engage * Math.max(0.0F, config.lateral_efficiency) * lost / perDamage;
        if (!Double.isFinite(damage)) {
            return 0.0F;
        }
        return (float) Math.max(0.0, Math.min(1.0E6, damage));
    }
}
