package com.ywzjvehicle.armor.mixin;

import com.ywzjvehicle.armor.client.HybridClientState;
import net.minecraft.sounds.SoundEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * Silences the diesel while the ZTZ-100 is on its battery.
 *
 * <p>{@code TrackedVehicle.tickSound} plays its idle and run loops off {@code hasPower()}, not off
 * the ignition flag, so an electric tank - whose power figure is kept up from the battery while the
 * diesel is off - would otherwise go on sounding like a diesel. These three getters are the only
 * place those sounds come from, and returning null here leaves the track roar, the start/stop
 * effects and every other vehicle untouched. The engine start sound is included because it is played
 * by a level check on {@code power == 5}, which a battery-only tank can also pass through.
 *
 * <p>Client-only and listed under "client" in the mixin JSON: the methods are called by
 * {@code tickSound}, which is {@code @OnlyIn(Dist.CLIENT)}.
 */
@Mixin(AbstractVehicle.class)
public abstract class AbstractVehicleHybridSoundMixin {

    @Inject(method = "getEngineIdleSound", at = @At("HEAD"), cancellable = true)
    private void ywzjarmor$silenceElectricIdle(CallbackInfoReturnable<SoundEvent> cir) {
        if (HybridClientState.isElectric((AbstractVehicle) (Object) this)) {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "getEngineRunSound", at = @At("HEAD"), cancellable = true)
    private void ywzjarmor$silenceElectricRun(CallbackInfoReturnable<SoundEvent> cir) {
        if (HybridClientState.isElectric((AbstractVehicle) (Object) this)) {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "getEngineStartSound", at = @At("HEAD"), cancellable = true)
    private void ywzjarmor$silenceElectricStart(CallbackInfoReturnable<SoundEvent> cir) {
        if (HybridClientState.isElectric((AbstractVehicle) (Object) this)) {
            cir.setReturnValue(null);
        }
    }
}
