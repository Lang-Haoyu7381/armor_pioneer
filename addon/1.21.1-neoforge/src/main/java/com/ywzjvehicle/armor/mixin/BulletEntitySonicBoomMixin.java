package com.ywzjvehicle.armor.mixin;

import com.ywzjvehicle.armor.ProjectileCrack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.vehicle.entity.weapon.BulletEntity;

/**
 * Hands a cannon shell to {@link ProjectileCrack} on its first client tick.
 *
 * <p>The shell is the only thing that knows where it is going and how fast, so the sonic boom is
 * measured from its own state rather than inferred from the shot that created it. The flag makes
 * this run once per shell: {@code tickMove} runs every tick, and a crack scheduled on each of them
 * would be a continuous roar instead of one pass.
 *
 * <p>Client-only, and listed under {@code client} in the mixin configuration: it reaches a
 * client-only class and there is nothing for a server to do here. The level check is a second
 * guard for the same rule, so a common-side loading mistake cannot play a sound in a headless
 * server tick.
 */
@Mixin(BulletEntity.class)
public abstract class BulletEntitySonicBoomMixin {

    @Unique
    private boolean ywzjarmor$crackTracked;

    @Inject(method = "tickMove", at = @At("HEAD"))
    private void ywzjarmor$trackSonicBoom(CallbackInfo ci) {
        if (ywzjarmor$crackTracked) {
            return;
        }
        BulletEntity self = (BulletEntity) (Object) this;
        if (!self.level().isClientSide()) {
            return;
        }
        ywzjarmor$crackTracked = true;
        ProjectileCrack.track(self);
    }
}
