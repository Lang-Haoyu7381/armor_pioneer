package com.ywzjvehicle.armor.mixin;

import com.ywzjvehicle.armor.client.HybridClientState;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.ywzj.vehicle.entity.vehicle.TrackedVehicle;
import org.ywzj.vehicle.util.ParticleUtil;

import java.util.function.Function;

/**
 * Stops the exhaust plume while the ZTZ-100 is on its battery.
 *
 * <p>{@code TrackedVehicle.tickParticle} emits engine smoke whenever {@code hasPower()}, which is
 * still true in electric mode because the base power figure is what the movement solver reads. The
 * redirect is on the one call that produces exhaust, not on {@code tickParticle} itself, so the
 * track dust and the suspension dust keep working - a silent tank still throws dust.
 *
 * <p>Client-only, like the method it sits in, and listed under "client" in the mixin JSON.
 */
@Mixin(TrackedVehicle.class)
public abstract class TrackedVehicleElectricSmokeMixin {

    @Redirect(method = "tickParticle",
            at = @At(value = "INVOKE",
                    target = "Lorg/ywzj/vehicle/util/ParticleUtil;spawnEngineSmoke("
                            + "Lnet/minecraft/world/level/Level;Ljava/lang/Iterable;"
                            + "Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;"
                            + "Ljava/util/function/Function;Lnet/minecraft/world/phys/Vec3;IIFF)V"))
    private void ywzjarmor$noExhaustWhileElectric(Level level, Iterable<Vec3> offsets,
                                                  Vec3 position, Vec3 oldPosition,
                                                  Function<Vec3, Vec3> transform,
                                                  Vec3 direction, int count, int lifetime,
                                                  float width, float height) {
        if (HybridClientState.isElectric((TrackedVehicle) (Object) this)) {
            return;
        }
        ParticleUtil.spawnEngineSmoke(level, offsets, position, oldPosition, transform, direction,
                count, lifetime, width, height);
    }
}
