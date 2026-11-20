package com.ywzjvehicle.armor.mixin;

import com.ywzjvehicle.armor.CrewRoles;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

/**
 * Makes a manually loaded main gun depend on its loader, without touching the pack.
 *
 * <p>The base mod's {@code reload.time} is a single figure and its {@code startReload} copies it
 * straight into the timer. That is correct for an autoloader and only half correct for a tank with
 * a human loader: the figure is the <em>trained loader's</em> cycle, and a tank whose fourth seat
 * is empty or whose loader has just been hit reloads more slowly. This class applies that
 * difference at the moment the reload starts, and pauses a reload that is already under way when
 * the loader stops being able to work.
 *
 * <p><b>Only the server's copy is changed.</b> {@code AbstractVehicleWeapon.tick} returns on the
 * client, so the only caller of {@code startReload} and {@code tickReload} is the server. The timer
 * is synced back, which is what the HUD draws; the client is never asked to guess whether a loader
 * is fit, which it could not answer - a wound is server state. The guard is here anyway because a
 * client-side call would use a client's empty wound table and put a 12-second bar on a 6-second
 * reload.
 *
 * <p><b>The penalty is on the reload, not on the seat.</b> The loader keeps their seat and regains
 * the rate the moment they recover or a replacement sits down; nothing is permanently taken away
 * from the player. The one-shot messages are on the action bar and go only to the gun's owner, who
 * is the person whose reload just got longer.
 */
@Mixin(AbstractVehicleWeapon.class)
public abstract class LoaderReloadMixin {

    /** The base's reload timer, in ticks. The same field {@code startReload} has just written. */
    @Shadow
    protected int reloadTime;

    /** Whether this reload started with a loader who could work - the only case a loss pauses. */
    @Unique
    private boolean ywzjarmor$loaderWasPresent;

    /** Set once per interruption so the action bar is not rewritten every tick. */
    @Unique
    private boolean ywzjarmor$loaderInterrupted;

    @Inject(method = "startReload", at = @At("RETURN"))
    private void ywzjarmor$loaderReloadTime(CallbackInfo ci) {
        AbstractVehicleWeapon<?> self = self();
        AbstractVehicle vehicle = self.getVehicle();
        if (vehicle == null || vehicle.m_9236_().m_5776_()) {
            return;
        }
        ywzjarmor$loaderInterrupted = false;
        boolean manual = CrewRoles.isManuallyLoaded(self)
                && CrewRoles.loaderPart(vehicle) != null;
        ywzjarmor$loaderWasPresent = manual && CrewRoles.loaderOperational(vehicle);
        if (!manual) {
            return;
        }
        float factor = CrewRoles.loaderReloadFactor(vehicle, self);
        if (factor <= 1.0F) {
            return;
        }
        this.reloadTime = CrewRoles.loaderReloadTicks(this.reloadTime, false);
        ywzjarmor$notify("message.ywzj_vehicle_armor.loader_slow");
    }

    /**
     * Pauses a reload whose loader has just been wounded or has left the seat.
     *
     * <p>Cancelling {@code tickReload} leaves {@code reloadTime} where it is rather than extending
     * it: when the loader returns the same reload continues, which is closer to a dropped round
     * being picked up again than a cycle restarted from zero. A reload that began with no loader
     * at all is not paused - the gunner is already cross-loading at the slow rate.
     */
    @Inject(method = "tickReload", at = @At("HEAD"), cancellable = true)
    private void ywzjarmor$pauseReloadWithoutLoader(CallbackInfo ci) {
        if (!ywzjarmor$loaderWasPresent) {
            return;
        }
        AbstractVehicleWeapon<?> self = self();
        AbstractVehicle vehicle = self.getVehicle();
        if (vehicle == null || vehicle.m_9236_().m_5776_()) {
            return;
        }
        if (CrewRoles.loaderOperational(vehicle)) {
            ywzjarmor$loaderInterrupted = false;
            return;
        }
        ci.cancel();
        if (!ywzjarmor$loaderInterrupted) {
            ywzjarmor$loaderInterrupted = true;
            ywzjarmor$notify("message.ywzj_vehicle_armor.loader_interrupted");
        }
    }

    @Unique
    private AbstractVehicleWeapon<?> self() {
        return (AbstractVehicleWeapon<?>) (Object) this;
    }

    /** The action bar, sent to the gun's owner - the person whose reload this is. */
    @Unique
    private void ywzjarmor$notify(String key) {
        LivingEntity owner = self().getWeaponUnit().getOwner();
        if (owner instanceof ServerPlayer player) {
            player.m_5661_(Component.m_237115_(key), true);
        }
    }
}
