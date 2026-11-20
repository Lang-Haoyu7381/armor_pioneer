package com.ywzjvehicle.armor;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Vector3f;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * The base mod's collision damage, measured against the plate the impact actually met.
 *
 * <p>{@code PhysicsEngine.motionByImpact} compares the velocity before and after a contact and
 * calls {@code DamageSystem.impactHurt} when the loss exceeds half a block per tick. That method
 * computes {@code 0.5 * tonnes * (dv * 20)^2 * impact_multiplier} and hands it to
 * {@code AbstractVehicle.hurt} with the damage type {@code ywzj_vehicle:vehicle_collision}. Until
 * this class existed, that was the largest path into a vehicle that never looked at a plate: a 40t
 * tank at 36 km/h was worth about 40 health after the base mod's own scale, and every face took it
 * the same way.
 *
 * <p><b>Identity is the damage type.</b> The first argument of the source is the collision
 * partner - the vehicle itself when it hit a block, the ramming vehicle when it hit another one -
 * and the causing entity is the driver. The source carries no impact position, so the side is
 * inferred: for a block impact, from the vehicle's own {@code deltaMovementO} (the velocity at the
 * start of the tick, before the contact was resolved); for a vehicle-on-vehicle hit, from the line
 * between the two vehicles. The ray starts outside that side and runs to the hull centre, so
 * {@code resolveHit} sees the face the obstacle was actually on - a frontal crash meets the
 * glacis, a rear-end meets the rear plate, a hard landing meets the floor.
 *
 * <p><b>The conversion.</b> The raw collision damage becomes an equivalent penetration by
 * {@link #penetrationFor}: {@code damage * 0.1}, capped at 500mm. With the shipped figures a 40t
 * tank at 36 km/h (200 raw) is 20mm and at 72 km/h (800 raw) is 80mm; a main battle tank's
 * 100mm+ side holds both, a BTR-3's 16mm side does not, and a light vehicle's 8mm roof takes real
 * damage at speed. The residual is multiplied by the plate's own multiplier unless the config
 * states a flat one.
 *
 * <p><b>Crew shock.</b> A crash the plate stops still throws the crew around. {@link #shock}
 * applies a short nausea and slowness effect, scaled by the raw damage and capped at two seconds
 * and one second respectively. It is deliberately small; a penetrating crash also wounds the crew
 * through {@code CrewState}, and the shock is on top of that.
 *
 * <p><b>Scope.</b> The caller checks the vehicle id against {@code vehicle_collisions.vehicles};
 * shipped as the seven Armor Pioneer vehicles. Aircraft are excluded by the caller, like every
 * other crash/blast rule. The base mod's own threshold and 0.2 scale are bypassed for a vehicle
 * this class handles; the armour table decides instead.
 */
public final class VehicleCollision {

    /** The base mod's collision damage type. */
    private static final ResourceLocation COLLISION =
            new ResourceLocation("ywzj_vehicle", "vehicle_collision");

    private VehicleCollision() {
    }

    /**
     * The collision damage type this class handles, exposed as the id itself so the offline check
     * can compare it exactly - a byte scan cannot tell {@code vehicle_collision} from
     * {@code vehicle_collision_typo}.
     */
    public static ResourceLocation damageType() {
        return COLLISION;
    }

    /**
     * @return whether this hit is the base mod's collision damage and the feature is on. The
     *         caller still has to check the vehicle's id and armour.
     */
    public static boolean handles(DamageSource source) {
        if (source == null || !ArmorConfig.vehicleCollisions().active()) {
            return false;
        }
        Holder<DamageType> type = source.m_269150_();
        return type.m_203373_(COLLISION);
    }

    /**
     * @return whether this vehicle is in the configured collision scope.
     */
    public static boolean appliesTo(AbstractVehicle vehicle) {
        return vehicle != null
                && ArmorConfig.vehicleCollisions().appliesTo(vehicle.getVehicleId().toString());
    }

    /**
     * @return the round a collision worth {@code amount} raw damage is measured as; never null.
     *
     * <p>A zero-penetration round is a ricochet, not a fall-through to the base mod's own
     * threshold and 0.2 scale.
     */
    public static ArmorConfig.Ammo roundFor(float amount) {
        ArmorConfig.VehicleCollisions settings = ArmorConfig.vehicleCollisions();
        ArmorConfig.Ammo impact = new ArmorConfig.Ammo(penetrationFor(amount,
                settings.penetration_per_damage, settings.max_penetration));
        impact.multiplier = settings.damage_multiplier;
        return impact;
    }

    /**
     * Millimetres of equivalent penetration for a collision that carried {@code amount} raw
     * damage. Linear and capped; public so the offline check can exercise it.
     */
    public static float penetrationFor(float amount, float perDamage, float max) {
        if (!(amount > 0.0F) || !(perDamage > 0.0F) || !(max > 0.0F)) {
            return 0.0F;
        }
        return Math.min(max, amount * perDamage);
    }

    /**
     * The ray this collision is resolved along: from outside the side that met the obstacle,
     * through the hull centre.
     *
     * @return {@code null} when there is no sensible direction - a vehicle at rest hit by
     *         something with no position, or a zero-length ray.
     */
    public static VanillaWeapon.Ray ray(AbstractVehicle vehicle, DamageSource source) {
        Vec3 direction = direction(vehicle, source);
        if (direction == null) {
            return null;
        }
        Vec3 centre = centre(vehicle);
        double radius = radius(vehicle);
        Vec3 start = centre.m_82549_(direction.m_82490_(radius + 1.0D));
        return VanillaWeapon.fromPoint(start, vehicle);
    }

    /**
     * Which way the obstacle was, from the vehicle's point of view.
     *
     * <p>For a vehicle-on-vehicle hit the source's direct entity is the other vehicle, so the line
     * between them is exact. For a world impact the direct entity is the vehicle itself; the
     * obstacle is wherever it was moving, which is {@code deltaMovementO} - the velocity captured
     * at the start of the tick, before the contact resolution took it away. A hard landing has a
     * downward pre-velocity, so the ray meets the floor; a head-on crash meets the front.
     */
    private static Vec3 direction(AbstractVehicle vehicle, DamageSource source) {
        Entity direct = source.m_7640_();
        if (direct instanceof AbstractVehicle other && other != vehicle) {
            Vec3 toward = other.m_20182_().m_82546_(vehicle.m_20182_());
            if (toward.m_82553_() > 1.0E-4) {
                return toward.m_82541_();
            }
        }
        Vec3 previous = vehicle.deltaMovementO;
        if (previous != null && previous.m_82553_() > 1.0E-4) {
            return previous.m_82541_();
        }
        Vec3 current = vehicle.m_20184_();
        return current.m_82553_() > 1.0E-4 ? current.m_82541_() : null;
    }

    private static Vec3 centre(AbstractVehicle vehicle) {
        if (vehicle.getMainCubeOBB() != null) {
            Vector3f centre = vehicle.getMainCubeOBB().obb().center();
            return new Vec3(centre.x, centre.y, centre.z);
        }
        return vehicle.m_20191_().m_82399_();
    }

    private static double radius(AbstractVehicle vehicle) {
        if (vehicle.getMainCubeOBB() != null) {
            Vector3f extents = vehicle.getMainCubeOBB().obb().extents();
            return Math.max(extents.x, Math.max(extents.y, extents.z));
        }
        return 2.0D;
    }

    /**
     * The short shell-shock a crash leaves on the crew, scaled by the raw collision damage.
     *
     * <p>Server-side only, and deliberately mild: two seconds of nausea and one of slowness at the
     * top end. The effect is re-applied rather than stacked; a stronger existing effect wins,
     * which is the vanilla behaviour and the right one when a penetrating hit has already
     * incapacitated someone.
     */
    public static void shock(AbstractVehicle vehicle, float amount) {
        ArmorConfig.VehicleCollisions settings = ArmorConfig.vehicleCollisions();
        ArmorConfig.VehicleCollisions.Shock shock = settings.shock;
        if (shock == null || !shock.active() || amount < shock.min_damage) {
            return;
        }
        float span = Math.max(1.0F, shock.full_damage - shock.min_damage);
        float strength = Math.min(1.0F, (amount - shock.min_damage) / span);
        int nauseaTicks = Math.round(shock.max_nausea_ticks * strength);
        int slownessTicks = Math.round(shock.max_slowness_ticks * strength);
        if (nauseaTicks <= 0 && slownessTicks <= 0) {
            return;
        }
        // Resolved by registry name for the same reason applySpall resolves blindness: the SRG
        // field numbering of MobEffects does not follow declaration order.
        MobEffect nausea = ForgeRegistries.MOB_EFFECTS.getValue(new ResourceLocation("nausea"));
        MobEffect slowness = ForgeRegistries.MOB_EFFECTS.getValue(new ResourceLocation("slowness"));
        for (Entity passenger : vehicle.m_20197_()) {
            if (passenger instanceof LivingEntity living) {
                apply(living, nausea, nauseaTicks, slowness, slownessTicks);
            }
        }
        LivingEntity driver = vehicle.getDriver();
        if (driver != null) {
            apply(driver, nausea, nauseaTicks, slowness, slownessTicks);
        }
    }

    private static void apply(LivingEntity living, MobEffect nausea, int nauseaTicks,
                              MobEffect slowness, int slownessTicks) {
        if (nausea != null && nauseaTicks > 0) {
            living.m_7292_(new MobEffectInstance(nausea, nauseaTicks, 0));
        }
        if (slowness != null && slownessTicks > 0) {
            living.m_7292_(new MobEffectInstance(slowness, slownessTicks, 0));
        }
    }
}
