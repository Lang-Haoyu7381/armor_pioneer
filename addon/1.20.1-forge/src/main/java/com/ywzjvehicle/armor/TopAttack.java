package com.ywzjvehicle.armor;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.weapon.MissileEntity;
import org.ywzj.vehicle.util.VehicleExplosion;
import org.ywzj.vehicle.vehicle.structure.OBB;

import java.util.List;

/**
 * A top-attack missile going off above a vehicle instead of flying into it.
 *
 * <p>The base mod already flies the profile: a missile whose data says {@code PRESET} climbs to a
 * cruise altitude, runs to a point short of the aim point, then dives. What it does at the end of
 * that dive is fly into whatever the gunner aimed at. This class changes only the ending: when the
 * warhead is over a vehicle and has come down to its standoff height, it goes off there and the
 * warhead is resolved as a shot straight down onto the roof. That is the whole point of a
 * top-attack round - the face it meets is the thin one, not the one the launcher was looking at.
 *
 * <p>Only rounds whose configuration says so take part. Everything else - every other missile,
 * every shell, every TACZ bullet - is untouched and keeps the base mod's impact behaviour.
 */
public final class TopAttack {

    /** A missile younger than this is still clearing its launcher. */
    private static final int MIN_TICKS = 10;

    /** Only a missile actually descending counts; a level overflight is not an attack. */
    private static final double MIN_DESCENT = -0.01;

    /** How far from the aim point a vehicle may sit and still be the intended target. */
    private static final double TARGET_MATCH = 24.0;

    private TopAttack() {
    }

    /**
     * One server tick of a missile that might be a top-attack round.
     *
     * @return {@code true} when this call detonated the missile, which tells the caller to stop the
     *         ordinary impact check for this tick
     */
    public static boolean tick(MissileEntity missile) {
        if (missile == null || missile.m_9236_().m_5776_()) {
            return false;
        }
        if (missile.f_19797_ < MIN_TICKS) {
            return false;
        }
        ArmorConfig.Ammo round = ArmorConfig.ammoFor(missile.getWeaponId());
        if (round == null || !round.isTopAttack()) {
            return false;
        }
        Vec3 velocity = missile.m_20184_();
        if (velocity.f_82480_ > MIN_DESCENT) {
            return false;
        }
        AbstractVehicle target = findTarget(missile, round.topAttackRadius());
        if (target == null) {
            return false;
        }
        double roof = roofY(target);
        double height = missile.m_20186_() - roof;
        // Where the missile was a tick ago. The delta movement is this tick's travel, so
        // subtracting it undoes the move that has already happened at this point in the tick.
        double previousHeight = height - velocity.f_82480_;
        if (!shouldDetonate(height, previousHeight, velocity.f_82480_, round)) {
            return false;
        }
        // The warhead goes off above the roof and its EFP travels straight down. The entry point
        // is inside the vehicle's outline by construction - the target search only admits a
        // vehicle whose footprint the missile is over.
        ArmorSystem.resolveTopAttack(target, missile,
                new Vec3(missile.m_20185_(),
                        roof + Math.max(0.5F, round.topAttackStandoff()), missile.m_20189_()));
        blast(missile, new Vec3(missile.m_20185_(), roof + round.topAttackStandoff(),
                missile.m_20189_()));
        missile.m_146870_();
        return true;
    }

    /**
     * Whether a warhead at this height should go off.
     *
     * <p>Two ways in. The ordinary one is the missile ending a tick inside the window - open at the
     * standoff height, closed at the minimum height. The second is the sweep: a fast missile can
     * cross the whole window between two ticks, so the segment from {@code previousHeight} to
     * {@code height} is checked for a crossing of the standoff plane as well. Without it a missile
     * moving more than the window's width per tick would fly straight through the point it was
     * supposed to fire at and only ever be caught by the impact path.
     *
     * <p>Below the roof there is nothing to attack; the ordinary impact path owns that case.
     * Separated from the world so the offline check can assert the geometry without a level.
     */
    public static boolean shouldDetonate(double height, double previousHeight,
                                         double verticalSpeed, ArmorConfig.Ammo round) {
        if (round == null || !round.isTopAttack() || verticalSpeed >= MIN_DESCENT
                || height <= 0.0) {
            return false;
        }
        double standoff = round.topAttackStandoff();
        boolean inside = height >= round.topAttackMinHeight() && height <= standoff;
        boolean crossed = previousHeight > standoff && height <= standoff;
        return inside || crossed;
    }

    /**
     * The nearest vehicle the missile is over, or {@code null}.
     *
     * <p>"Over" is horizontal: the missile's column must fall inside the vehicle's own bounding
     * box grown by the configured radius. The launcher and the launcher's own passengers are
     * excluded for the same reason the base mod excludes them.
     */
    static AbstractVehicle findTarget(MissileEntity missile, double radius) {
        Level level = missile.m_9236_();
        double reach = Math.max(radius, 6.0) + 4.0;
        AABB scan = missile.m_20191_().m_82400_(reach);
        List<AbstractVehicle> vehicles = level.m_45976_(AbstractVehicle.class, scan);
        AbstractVehicle best = null;
        double bestDistance = Double.MAX_VALUE;
        for (AbstractVehicle vehicle : vehicles) {
            if (vehicle == missile.vehicle || !vehicle.m_6084_() || vehicle.m_213877_()) {
                continue;
            }
            // The gunner aimed somewhere. A missile crossing the battlefield does not get to
            // attack the first vehicle it happens to pass over; the target has to be near the
            // aim point the launch carried.
            if (missile.targetPos != null) {
                double ax = vehicle.m_20185_() - missile.targetPos.f_82479_;
                double az = vehicle.m_20189_() - missile.targetPos.f_82481_;
                if (ax * ax + az * az > TARGET_MATCH * TARGET_MATCH) {
                    continue;
                }
            }
            AABB box = vehicle.m_20191_();
            Vec3 centre = box.m_82399_();
            double dx = missile.m_20185_() - centre.f_82479_;
            double dz = missile.m_20189_() - centre.f_82481_;
            double distance = Math.sqrt(dx * dx + dz * dz);
            double half = Math.max(box.m_82362_(), box.m_82385_()) / 2.0;
            if (distance > radius + half) {
                continue;
            }
            if (distance < bestDistance) {
                bestDistance = distance;
                best = vehicle;
            }
        }
        return best;
    }

    /** @return the highest point of the vehicle's structure, including the turret. */
    static double roofY(AbstractVehicle vehicle) {
        double top = vehicle.m_20191_().f_82292_;
        for (OBB obb : vehicle.getOBBs()) {
            for (Vector3f vertex : obb.getVertices()) {
                if (vertex.y > top) {
                    top = vertex.y;
                }
            }
        }
        return top;
    }

    /**
     * The weapon's own explosion, fired where the warhead went off.
     *
     * <p>Its damage to this vehicle is dropped by the armour system - the round already carries
     * its own answer - but the blast still reaches everything else and still draws the hit.
     */
    private static void blast(MissileEntity missile, Vec3 position) {
        if (missile.explosion == null || !missile.explosion.explode) {
            return;
        }
        new VehicleExplosion(missile.m_9236_(), missile.m_19749_(), missile, position,
                missile.explosion.radius, missile.explosion.damage,
                missile.explosion.destroyBlock).explode();
    }
}
