package com.ywzjvehicle.armor.mixin;

import com.ywzjvehicle.armor.ArmorSystem;
import com.ywzjvehicle.armor.Hybrid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * Turns a vehicle's horsepower per tonne into an acceleration multiplier.
 *
 * <p>The base mod's {@code TrackedVehicle.tickMove} and {@code WheeledVehicle} movement read one
 * {@code powerScale} from {@code getSuspensionPowerScale()} and apply it to forward acceleration,
 * backward acceleration, the turn rate and the turn acceleration - but not to the maximum speed,
 * which comes from the gearbox/track figure. That is exactly the shape the addon wants: hp/t is a
 * power-to-weight ratio, so it should decide how quickly the vehicle gets to its speed and how
 * hard it pulls uphill, while the published top speeds stay untouched.
 *
 * <p><b>The server only.</b> {@code tickMove} runs on the server; the client renders the synced
 * motion and does not run this solver. Applying the factor only where the solver runs keeps the
 * client's prediction out of a config that the server owns.
 *
 * <p>The method is the one the base mod already treats as "fraction of the drivetrain still
 * intact": a vehicle on two of four springs loses half its power. Multiplying that by the engine
 * ratio is the honest composition - a damaged, underpowered vehicle has both problems - rather
 * than replacing one with the other.
 */
@Mixin(AbstractVehicle.class)
public abstract class AbstractVehiclePowerMixin {

    @Inject(method = "getSuspensionPowerScale", at = @At("RETURN"), cancellable = true)
    private void ywzjarmor$enginePower(CallbackInfoReturnable<Float> cir) {
        AbstractVehicle self = (AbstractVehicle) (Object) this;
        if (self.m_9236_().m_5776_()) {
            return;
        }
        // Two independent multipliers, composed: the engine's hp/t factor, and the electric
        // drivetrain's own while the battery is running the tank. Electric is a power source, not a
        // replacement for the hp/t law - the ZTZ-100's diesel factor is 1.5, and 0.8 of that is
        // still 1.2, which is what a hybrid that is slower than its diesel should feel like.
        float scale = ArmorSystem.enginePowerScale(self) * Hybrid.powerScale(self);
        if (Math.abs(scale - 1.0F) > 1.0E-4F) {
            cir.setReturnValue(cir.getReturnValue() * scale);
        }
    }
}
