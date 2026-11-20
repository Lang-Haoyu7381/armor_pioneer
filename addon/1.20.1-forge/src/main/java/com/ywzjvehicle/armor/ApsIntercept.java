package com.ywzjvehicle.armor;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.weapon.AmmoEntity;

/**
 * The geometry behind the Armor Pioneer active-protection sweep.
 *
 * <p><b>Why the base check misses a cannon shell.</b> {@code ActiveProtectionGrenadeEntity} looks
 * for projectiles inside its own bounding box expanded by 8 blocks, once per tick, and fires the
 * interceptor at {@code target.pos + target.velocity} - a lead of exactly one tick. Against a
 * missile that is more than enough. Against a 120/125mm shell it is not: both the grenade and the
 * shell move at about 15 blocks a tick, so after one tick the grenade is at the aim point while the
 * shell is at the aim point plus (shell speed x flight time), and the miss grows with the firing
 * range. The tank's own test showed exactly that: five rounds left the two launchers and nothing
 * was intercepted.
 *
 * <p>The fix is not a bigger sphere around a point - it is checking the volume the interceptor
 * actually swept between two ticks. The grenade's path is a segment from where it was to where it
 * is; a projectile within {@link #RADIUS} blocks of that segment is caught. A shell crossing the
 * interceptor's path is then caught wherever along its flight it happens to be, which is what the
 * proximity fuse of a real hard-kill system does. The radius stays the base's own 8-block
 * engagement distance plus a little for the segment's endpoints; what changes is that the volume
 * is swept rather than sampled.
 *
 * <p>Friendly fire is excluded, which the base does not do: the same tank's own shells, and any
 * projectile owned by its crew, are not intercepted. Without this the sweep would eat the tank's
 * own main-gun round as it left the muzzle.
 *
 * <p>Only vehicles in the {@code armor_pioneer} namespace are affected - the addon's own pack. The
 * base APS on every other vehicle keeps its original behaviour, so this is not a global balance
 * change.
 */
public final class ApsIntercept {

    /** The pack whose APS gets the sweep. */
    public static final String NAMESPACE = "armor_pioneer";

    /**
     * How close to the interceptor's swept path a projectile has to pass, in blocks.
     *
     * <p>The base's engagement radius is 8. Ten adds two blocks of margin for the fact that the
     * shell's position is also sampled once a tick: at 15 blocks a tick a shell can be 7.5 blocks
     * from the closest point of its own path at either sample. Twelve would catch more, but it
     * starts to reach projectiles that passed nowhere near the interceptor; ten is the base radius
     * with room for one sample, and {@code ApsCheck} pins the geometry.
     */
    public static final double RADIUS = 10.0;

    private ApsIntercept() {
    }

    /** @return whether this vehicle's APS uses the sweep. */
    public static boolean isOurs(AbstractVehicle vehicle) {
        return vehicle != null && vehicle.getVehicleId() != null
                && NAMESPACE.equals(vehicle.getVehicleId().m_135827_());
    }

    /**
     * @return whether the entity is a live projectile this vehicle should not intercept - its own
     *         rounds, the rounds of its crew, or another interceptor launched by the same tank.
     */
    public static boolean isFriendly(AbstractVehicle vehicle, Entity entity) {
        if (!(entity instanceof Projectile projectile) || vehicle == null) {
            return false;
        }
        if (entity instanceof AmmoEntity ammo && ammo.vehicle == vehicle) {
            return true;
        }
        Entity owner = projectile.m_19749_();
        return owner != null
                && (owner == vehicle || vehicle.m_20197_().contains(owner));
    }

    /** @return whether the entity is a projectile worth intercepting. */
    public static boolean isCandidate(AbstractVehicle vehicle, Entity entity) {
        return entity != null && entity.m_6084_() && entity instanceof Projectile
                && !isFriendly(vehicle, entity);
    }

    /** @return the search box that contains the whole swept segment with {@code radius} margin. */
    public static AABB searchBox(Vec3 from, Vec3 to, double radius) {
        return new AABB(
                Math.min(from.f_82479_, to.f_82479_) - radius,
                Math.min(from.f_82480_, to.f_82480_) - radius,
                Math.min(from.f_82481_, to.f_82481_) - radius,
                Math.max(from.f_82479_, to.f_82479_) + radius,
                Math.max(from.f_82480_, to.f_82480_) + radius,
                Math.max(from.f_82481_, to.f_82481_) + radius);
    }

    /** @return the distance from {@code p} to the segment {@code from -> to}, in blocks. */
    public static double pathDistance(Vec3 from, Vec3 to, Vec3 p) {
        double bx = to.f_82479_ - from.f_82479_;
        double by = to.f_82480_ - from.f_82480_;
        double bz = to.f_82481_ - from.f_82481_;
        double px = p.f_82479_ - from.f_82479_;
        double py = p.f_82480_ - from.f_82480_;
        double pz = p.f_82481_ - from.f_82481_;
        double lengthSq = bx * bx + by * by + bz * bz;
        double t = lengthSq < 1.0E-12 ? 0.0
                : Math.max(0.0, Math.min(1.0, (px * bx + py * by + pz * bz) / lengthSq));
        double dx = px - bx * t;
        double dy = py - by * t;
        double dz = pz - bz * t;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** @return whether {@code p} is within {@link #RADIUS} of the swept segment. */
    public static boolean withinPath(Vec3 from, Vec3 to, Vec3 p) {
        return pathDistance(from, to, p) <= RADIUS;
    }
}
