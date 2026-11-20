package com.ywzjvehicle.armor.mixin;

import com.ywzjvehicle.armor.TopAttack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.vehicle.entity.weapon.AmmoEntity;
import org.ywzj.vehicle.entity.weapon.MissileEntity;

/**
 * Gives a configured top-attack missile a second way to end: going off above the target instead of
 * flying into it.
 *
 * <p>Targeted at {@link AmmoEntity} rather than {@link MissileEntity} because that is where
 * {@code tickHit} is declared. Mixin injects into the class that owns the method, not into a
 * subclass that inherits it - the same rule that makes an inherited {@code @Shadow} fail - so a
 * mixin on {@code MissileEntity} would never find this method. The first line narrows the mixin
 * back to missiles.
 *
 * <p>The header injection runs before the ordinary entity/block hit test for the tick. When it
 * detonates, the missile is already gone and the impact test must not also run, which is what
 * cancelling the method does.
 */
@Mixin(AmmoEntity.class)
public abstract class MissileTopAttackMixin {

    @Inject(method = "tickHit", at = @At("HEAD"), cancellable = true)
    private void ywzjarmor$topAttack(CallbackInfo ci) {
        if ((Object) this instanceof MissileEntity missile && TopAttack.tick(missile)) {
            ci.cancel();
        }
    }
}
