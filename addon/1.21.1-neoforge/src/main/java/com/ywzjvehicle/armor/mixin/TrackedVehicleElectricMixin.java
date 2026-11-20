package com.ywzjvehicle.armor.mixin;

import com.ywzjvehicle.armor.Hybrid;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.vehicle.entity.vehicle.TrackedVehicle;

/**
 * Holds the ZTZ-100 to a lower top speed while it is on the battery.
 *
 * <p>{@code TrackedVehicle.tickMove} clamps forward speed to {@code maxSpeedForward} and nothing
 * else scales that clamp: {@code getSuspensionPowerScale} reaches acceleration and turn rate only.
 * So the electric mode's reduced power would make the tank reach its full diesel speed eventually,
 * which is not what a hybrid's battery cruise is. The two fields are capped for the duration of one
 * {@code tickMove} call and restored on the way out, so the clamp sees the electric figure while the
 * rest of the tick - the sound pitch, the track particles, the HUD - keeps reading the pack's own
 * maximum.
 *
 * <p>Server only, like the movement solver itself. The fields are public and the method is not
 * re-entrant, which is what makes the save-and-restore safe rather than a leak between ticks.
 */
@Mixin(TrackedVehicle.class)
public abstract class TrackedVehicleElectricMixin {

    @Unique
    private float ywzjarmor$savedMaxForward;

    @Unique
    private float ywzjarmor$savedMaxBackward;

    @Unique
    private boolean ywzjarmor$speedCapped;

    @Inject(method = "tickMove", at = @At("HEAD"))
    private void ywzjarmor$capElectricSpeed(CallbackInfoReturnable<Vec3> cir) {
        TrackedVehicle self = (TrackedVehicle) (Object) this;
        if (self.level().isClientSide()) {
            return;
        }
        float fraction = Hybrid.speedFraction(self);
        if (fraction >= 0.999F) {
            return;
        }
        ywzjarmor$savedMaxForward = self.maxSpeedForward;
        ywzjarmor$savedMaxBackward = self.maxSpeedBackward;
        ywzjarmor$speedCapped = true;
        self.maxSpeedForward = ywzjarmor$savedMaxForward * fraction;
        self.maxSpeedBackward = ywzjarmor$savedMaxBackward * fraction;
    }

    @Inject(method = "tickMove", at = @At("RETURN"))
    private void ywzjarmor$restoreMaxSpeed(CallbackInfoReturnable<Vec3> cir) {
        if (!ywzjarmor$speedCapped) {
            return;
        }
        TrackedVehicle self = (TrackedVehicle) (Object) this;
        self.maxSpeedForward = ywzjarmor$savedMaxForward;
        self.maxSpeedBackward = ywzjarmor$savedMaxBackward;
        ywzjarmor$speedCapped = false;
    }
}
