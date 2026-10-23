package com.ywzjvehicle.armor.mixin;

import com.ywzjvehicle.armor.CrewClientState;
import com.ywzjvehicle.armor.CrewRoles;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RotatableUnit;

import java.util.Objects;

/**
 * Makes the weapon's owner follow the server's aim while the commander is laying the gun.
 *
 * <p>{@code RotatableUnit.tickRemoteRot} deliberately skips the owner: normally the owner's mouse
 * is the aim, and overwriting it would fight the player. A commander's order is the one case where
 * that is backwards - the server's aim is the order, and the owner is the person who should see it.
 * This mixin applies the remote aim only when the server has said an order is active, so ordinary
 * aiming is untouched.
 *
 * <p>Client-only, and listed under {@code client} in the mixin config: it names
 * {@link LocalVehiclePlayer}, which does not belong on a dedicated server.
 */
@Mixin(RotatableUnit.class)
public abstract class RotatableUnitClientMixin {

    @Shadow
    protected float xAimRot;

    @Shadow
    protected float yAimRot;

    @Shadow
    protected float xRemoteAimRot;

    @Shadow
    protected float yRemoteAimRot;

    @Inject(method = "tickRemoteRot", at = @At("HEAD"), cancellable = true)
    private void ywzjarmor$followCommander(CallbackInfo ci) {
        // vehicle and getOwner live on PartUnit, the superclass. Mixin will not @Shadow an
        // inherited method (the first build of this class crashed exactly there), so the mixin
        // casts itself instead. Every RotatableUnit is a PartUnit by construction.
        PartUnit<?> self = (PartUnit<?>) (Object) this;
        AbstractVehicle vehicle = self.getVehicle();
        if (vehicle != null && vehicle.m_9236_().m_5776_()
                && CrewClientState.isSlaved(vehicle)
                // Only the main gun. A rotatable that is not the vehicle's largest weapon - a
                // truck bed, a secondary mount - has no business following the commander's order.
                // Objects.equals because this mixin class does not extend RotatableUnit at compile
                // time; Mixin makes it one at runtime.
                && Objects.equals(CrewRoles.primaryWeaponUnit(vehicle), this)
                && self.getOwner() == LocalVehiclePlayer.instance.getPlayer()) {
            // Same two assignments the base method makes for everyone who is not the owner.
            this.xAimRot = this.xRemoteAimRot;
            this.yAimRot = this.yRemoteAimRot;
            ci.cancel();
        }
    }
}
