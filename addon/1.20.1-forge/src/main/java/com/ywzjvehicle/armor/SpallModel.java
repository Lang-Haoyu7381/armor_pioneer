package com.ywzjvehicle.armor;

/**
 * Behind-armour debris damage for a kinetic round that states its mass and muzzle velocity.
 *
 * <p>The old damage rule is {@code residual * face multiplier}: a round that gets through a plate
 * is worth a fixed number per millimetre of spare penetration, whatever the plate was made of or
 * how fast the round was still going. This model replaces that for the rounds that opt in.
 *
 * <p>Three facts drive it:
 *
 * <ol>
 *   <li><b>The plate is the source of the fragments.</b> A larger hole and a thicker plate eject
 *       more material, so the plate's own thickness appears in the fragment mass. Very thick plates
 *       confine that material instead of throwing it, hence the {@code c/(c+t)} factor.</li>
 *   <li><b>The residual sets the speed.</b> A round that only just got through has almost no
 *       velocity left to give the fragments, so {@code R = (P - t)/P} scales the fragment
 *       velocity. The round's own remaining energy is counted separately and at a lower weight,
 *       because it travels down one narrow channel rather than through the compartment.</li>
 *   <li><b>A thin plate over-penetrates.</b> When the plate is less than half the projectile's
 *       diameter the round does not slow or tumble much; it exits the far side with most of its
 *       energy. The damage falls toward {@code overmatch_damage_floor} as the plate gets thinner.</li>
 * </ol>
 *
 * <p>Nothing here reads a world, a vehicle or a config file directly: every constant comes in as
 * an {@link ArmorConfig.Spall}, and the geometry comes in as numbers. That is what lets the
 * offline check assert the same arithmetic the hit path runs.
 */
public final class SpallModel {

    private SpallModel() {
    }

    /**
     * @return the round's certified penetration at {@code rangeMetres}, or its muzzle figure when
     *         it states no curve. The mass term is anchored to the round's own {@code mass_kg}, so
     *         in play this is the shipped / hand-edited curve; the mass field changes it only when
     *         the round's own figure is edited.
     */
    public static float penetration(ArmorConfig.Ammo round, double rangeMetres) {
        if (round == null) {
            return 0.0F;
        }
        if (rangeMetres > 0.0 && round.hasRangeFalloff()) {
            return round.penetrationAt((float) rangeMetres);
        }
        return round.penetration;
    }

    /**
     * @return the impact velocity at {@code rangeMetres}, derived by inverting the round's own
     *         penetration curve through the velocity exponent. A round with no curve keeps its
     *         muzzle velocity.
     *
     * <p>Using the curve rather than a drag coefficient means the shipped penetration and the
     * velocity used for the debris energy can never disagree: the same curve produces both.
     */
    public static float impactVelocity(ArmorConfig.Ammo round, double rangeMetres) {
        if (round == null || !round.usesKineticModel()) {
            return 0.0F;
        }
        float v0 = round.muzzleVelocity();
        if (rangeMetres > 0.0 && round.hasRangeFalloff() && round.penetration > 0.0F) {
            float share = round.penetrationAt((float) rangeMetres) / round.penetration;
            share = Math.max(0.01F, Math.min(1.0F, share));
            double beta = Math.max(0.01F, round.velocityExponent());
            return (float) (v0 * Math.pow(share, 1.0 / beta));
        }
        return v0;
    }

    /**
     * @return the damage a penetrating round does, from the debris it throws.
     *
     * @param penetration       what the round still has after the outer layers, in mm
     * @param effectiveThickness the plate along the round's path, in mm (already divided by cos)
     * @param nominalThickness  the plate's own thickness, in mm, for the overmatch test
     * @param caliberMm         the projectile diameter, in mm
     * @param velocity          the impact velocity, in m/s
     * @param massKg            the projectile mass, in kg
     */
    public static float damage(ArmorConfig.Spall config, float penetration,
                               float effectiveThickness, float nominalThickness,
                               float caliberMm, float velocity, float massKg) {
        if (config == null || penetration <= 0.0F || effectiveThickness < 0.0F) {
            return 0.0F;
        }
        double residual = Math.max(0.0, (penetration - effectiveThickness) / penetration);
        if (residual <= 0.0) {
            return 0.0F;
        }
        double caliber = Math.max(1.0, caliberMm);
        double hole = Math.max(0.0, config.hole_diameter_factor) * caliber / 1000.0;
        double area = Math.PI / 4.0 * hole * hole;
        double thicknessMm = Math.max(0.0, effectiveThickness);
        double confine = config.confinement_mm <= 0.0F ? 1.0
                : config.confinement_mm / (config.confinement_mm + thicknessMm);
        double plateMass = Math.max(0.0, config.density_kg_m3) * area
                * (thicknessMm / 1000.0) * confine;
        double fragmentSpeed = Math.max(0.0, config.fragment_velocity_factor)
                * Math.max(0.0, velocity) * Math.sqrt(residual);
        double exitSpeed = Math.max(0.0, velocity) * Math.sqrt(residual);
        double plateEnergy = 0.5 * plateMass * fragmentSpeed * fragmentSpeed;
        double rodEnergy = Math.max(0.0, config.rod_energy_factor)
                * 0.5 * Math.max(0.0, massKg) * exitSpeed * exitSpeed;
        double energy = plateEnergy + rodEnergy;
        double perDamage = Math.max(1.0F, config.energy_per_damage);
        double damage = energy / perDamage * overmatch(nominalThickness, caliberMm, config);
        if (!Double.isFinite(damage)) {
            return 0.0F;
        }
        return (float) Math.max(0.0, Math.min(1.0E6, damage));
    }

    /**
     * @return the share of the debris energy that stays in the vehicle. One for a plate thick
     *         enough relative to the projectile, falling to {@code overmatch_damage_floor} for one
     *         thin enough that the round simply passes through.
     */
    public static float overmatch(float nominalThickness, float caliberMm, ArmorConfig.Spall config) {
        if (config == null || caliberMm <= 0.0F) {
            return 1.0F;
        }
        double start = config.overmatch_start;
        double full = config.overmatch_full;
        if (start <= full) {
            return 1.0F;
        }
        double ratio = Math.max(0.0, nominalThickness) / caliberMm;
        double over = (start - ratio) / (start - full);
        over = Math.max(0.0, Math.min(1.0, over));
        double floor = Math.max(0.0, Math.min(1.0, config.overmatch_damage_floor));
        return (float) (1.0 - over * (1.0 - floor));
    }
}
