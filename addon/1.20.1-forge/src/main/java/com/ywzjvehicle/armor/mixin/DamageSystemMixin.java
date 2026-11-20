package com.ywzjvehicle.armor.mixin;

import com.ywzjvehicle.armor.ArmorSystem;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.DamageSystem;

/**
 * Takes over damage resolution for vehicles and ammunition covered by the armour config.
 *
 * <p>The injection sits at the very top of {@code DamageSystem.hurt} and cancels it once the
 * armour calculation has been applied by us. Cancelling here - rather than at
 * {@code AbstractVehicle.hurt} - is deliberate: the "vehicle destroyed" check and the
 * resulting explosion live in the caller, so the tank still blows up normally when a
 * penetrating hit takes its health to zero.
 */
@Mixin(DamageSystem.class)
public abstract class DamageSystemMixin {

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private static void ywzjarmor$applyArmorEquivalence(DamageSource damageSource, float amount,
                                                       AbstractVehicle vehicle, CallbackInfo ci) {
        if (ArmorSystem.tryHandle(damageSource, amount, vehicle)) {
            ci.cancel();
        }
    }
}
