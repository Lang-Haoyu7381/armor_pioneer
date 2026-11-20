package com.ywzjvehicle.armor;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.bus.api.SubscribeEvent;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;

import java.util.List;

/**
 * Keeps a TACZ explosive round's own blast from putting a second hole in a vehicle.
 *
 * <p>TACZ delivers an explosive round in two pieces: {@code tacAttackEntity} calls
 * {@code Entity.hurt} for the direct hit, and then {@code ExplodeUtil.createExplosion} runs its own
 * {@code ProjectileExplosion}. The direct hit is already resolved by {@link ArmorSystem} - a rated
 * cartridge is measured against the plate, and a penetrating shaped charge carries its spall - so
 * letting the blast land as well would damage the same vehicle twice, once through the armour table
 * and once through vanilla explosion damage that ignores the plate entirely. This handler removes
 * the directly struck vehicle from the blast's affected list, and only that vehicle: a nearby
 * vehicle that was not hit keeps TACZ's own splash.
 *
 * <p>No mixin and no TACZ type. The explosion event carries the bullet as the explosion's direct
 * source entity, and {@link TaczBridge} turns that into a cartridge id; everything after that is
 * the same armour config every other path reads. A round this addon has not rated is left alone,
 * blast and all - the fallback is deliberate.
 */
public final class TaczExplosions {

    private TaczExplosions() {
    }

    /** @return whether the addon has rated this cartridge, so its blast is replaced by the hit. */
    public static boolean rated(String ammoId) {
        if (ammoId == null) {
            return false;
        }
        try {
            return ArmorConfig.taczRoundFor(ResourceLocation.parse(ammoId)) != null;
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    /**
     * Runs after TACZ has chosen what the blast affects and before it damages anything.
     *
     * <p>{@code ProjectileExplosion.explode()} fires the Forge detonate event itself, between the
     * block and entity scans, so removing an entry here is what keeps it out of the damage loop.
     */
    @SubscribeEvent
    public static void onDetonate(ExplosionEvent.Detonate event) {
        Entity blast = event.getExplosion().getDirectSourceEntity();
        ResourceLocation ammo = TaczBridge.ammoIdOf(blast);
        ArmorConfig.Ammo round = ArmorConfig.taczRoundFor(ammo);
        if (round == null) {
            return;
        }
        List<Entity> affected = event.getAffectedEntities();
        Entity struck = directlyStruck(blast, affected);
        if (struck == null) {
            return;
        }
        try {
            if (affected.remove(struck)) {
                VehicleArmorMod.LOGGER.info(
                        "[Vehicle Armor] {} blast dropped on {}; the direct hit was already "
                                + "resolved against the armour table", ammo, struck.getType());
            }
        } catch (UnsupportedOperationException frozen) {
            // A provider that hands out an immutable list would otherwise turn a blast into a
            // crash. The round then lands twice, which is wrong but survivable - and visible in
            // the log line that never appeared.
            VehicleArmorMod.LOGGER.warn("[Vehicle Armor] {} blast could not drop {}: the affected "
                    + "list is not modifiable", ammo, struck.getType());
        }
    }

    /**
     * @return the vehicle the bullet was on its way into, or {@code null}.
     *
     * <p>At the moment the explosion runs the bullet still sits at the start of the step whose end
     * the hit was found on, so the step itself - position to position plus velocity - is the same
     * ray the base mod used to find the hit. Checking it against the affected list rather than
     * guessing from distance keeps a near miss a near miss.
     */
    private static Entity directlyStruck(Entity blast, List<Entity> affected) {
        Vec3 from = blast.position();
        Vec3 to = from.add(blast.getDeltaMovement());
        Entity struck = null;
        double nearest = Double.MAX_VALUE;
        for (Entity entity : affected) {
            if (!(entity instanceof AbstractVehicle vehicle) || ArmorSystem.armorFor(vehicle) == null) {
                continue;
            }
            Vec3 hit = VectorUtil.closestHitObbPosition(vehicle, from, to);
            if (hit == null) {
                continue;
            }
            double distance = hit.distanceTo(from);
            if (distance < nearest) {
                nearest = distance;
                struck = entity;
            }
        }
        return struck;
    }
}
