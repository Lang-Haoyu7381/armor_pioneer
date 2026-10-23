package com.ywzjvehicle.armor.mixin;

import com.ywzjvehicle.armor.CrewState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;

import java.util.List;

/**
 * Refuses to shoot for an operator who is out of action, on the server.
 *
 * <p>The client's {@code VehicleFireEvent.Pre} is the polite half of this: it stops the effect
 * before it is played. It is also only a client, and a client can be wrong, modified, or simply a
 * tick behind the wound. The server's own shoot method is where the round is actually created, so
 * the refusal that counts belongs here.
 *
 * <p>Only a wounded <em>operator</em> is refused; a vehicle whose gun is fired by its AI driver
 * still fires, because there is no crew member to be hurt.
 */
@Mixin(WeaponUnit.class)
public abstract class CrewFireMixin {

    @Inject(method = "shoot(ILjava/util/List;Lnet/minecraft/world/entity/LivingEntity;)V",
            at = @At("HEAD"), cancellable = true)
    private void ywzjarmor$refuseWoundedOperator(int weaponIndex, List<AimContext> contexts,
                                                 LivingEntity operator, CallbackInfo ci) {
        if (operator != null && CrewState.isIncapacitated(operator)) {
            ci.cancel();
        }
    }
}
