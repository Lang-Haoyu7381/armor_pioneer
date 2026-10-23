package com.ywzjvehicle.armor;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.ywzj.vehicle.api.event.VehicleFireEvent;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * Bars a weapon from firing once its own part unit has been shot to pieces.
 *
 * <p>The base mod decides to fire on the client: {@code AbstractVehicleWeapon#doClientShoot}
 * and {@code VehicleMultiWeapons#doClientShoot} post {@link VehicleFireEvent.Pre} and only send
 * the shoot packet to the server if nothing cancels it. Cancelling the event here therefore
 * stops the shot before it is even sent, with no mixin needed.
 *
 * <p>The base mod does already react to a wrecked part - {@code WeaponUnit#tickRot} drops the
 * gun stabiliser, and the scope overlay tints the part - but it never blocks the trigger.
 */
public final class FireControlHandler {

    /** Minimum gap between warnings, so holding the trigger down does not spam the action bar. */
    private static final long WARNING_INTERVAL_MILLIS = 1500L;

    private static long lastWarningMillis;

    private FireControlHandler() {
    }

    @SubscribeEvent
    public static void onVehicleFire(VehicleFireEvent.Pre event) {
        // A wounded operator cannot pull the trigger, whether or not the weapon itself is intact.
        // This is the gunner's half of "each crew member does their job": the vehicle may be fine
        // and the gun may be fine, and the person in front of it may not be.
        LivingEntity operator = event.getOperator();
        // Two answers for the same question: the server's map is authoritative and is what the
        // firing mixin enforces; the client's flag is what lets this side refuse before it sends
        // anything or plays a shot that will not happen. Either being true is enough.
        boolean wounded = operator != null && (CrewState.isIncapacitated(operator)
                || (operator instanceof Player && CrewClientState.isIncapacitated()));
        if (wounded) {
            event.setCanceled(true);
            warn(operator, "message.ywzj_vehicle_armor.weapon_incapacitated");
            return;
        }
        if (!ArmorConfig.destroyedPartsCannotFire()) {
            return;
        }
        WeaponUnit weaponUnit = event.getWeapon().getWeaponUnit();
        if (weaponUnit == null || !weaponUnit.isDestroyed()) {
            return;
        }
        event.setCanceled(true);
        warn(operator, "message.ywzj_vehicle_armor.weapon_destroyed");
    }

    private static void warn(LivingEntity operator, String messageKey) {
        if (!(operator instanceof Player player)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastWarningMillis < WARNING_INTERVAL_MILLIS) {
            return;
        }
        lastWarningMillis = now;
        player.m_5661_(Component.m_237115_(messageKey), true);
    }
}
