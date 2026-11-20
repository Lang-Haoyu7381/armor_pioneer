package com.ywzjvehicle.armor.mixin;

import com.ywzjvehicle.armor.client.PeriscopeView;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.PartUnit;

/**
 * Moves the operator camera onto the hatch periscope while {@code PeriscopeView} is active.
 *
 * <p>This is a client mixin and is listed under {@code "client"} in the mixin JSON: both the target
 * and the helper name client-only types, and a common mixin that names one is the crash this
 * project has already paid for once.
 *
 * <p>The injection sits at the head of {@code cameraPosition} and cancels only when the helper
 * returns a point. That keeps the base's own method the single owner of every other view - the
 * periscope is one extra branch, not a replacement for the operator view. {@code switchViewType}
 * is observed rather than changed, so the base's own V and X keys still end the periscope without
 * this addon having to duplicate their logic.
 */
@Mixin(LocalVehiclePlayer.class)
public abstract class PeriscopeCameraMixin {

    @Inject(method = "cameraPosition", at = @At("HEAD"), cancellable = true)
    private void armor$periscopeCamera(PartUnit<?> operatorUnit, float partialTick,
                                       CallbackInfoReturnable<Vec3> cir) {
        Vec3 position = PeriscopeView.cameraPosition(operatorUnit, partialTick);
        if (position != null) {
            cir.setReturnValue(position);
        }
    }

    @Inject(method = "switchViewType", at = @At("HEAD"))
    private void armor$leavePeriscope(LocalVehiclePlayer.ViewType toViewType, CallbackInfo ci) {
        PeriscopeView.beforeViewSwitch(toViewType);
    }
}
