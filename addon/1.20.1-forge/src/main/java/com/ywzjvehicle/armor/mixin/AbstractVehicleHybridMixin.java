package com.ywzjvehicle.armor.mixin;

import com.ywzjvehicle.armor.Hybrid;
import com.ywzjvehicle.armor.HybridState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * The ZTZ-100's hybrid drive, as the base mod's engine loop sees it.
 *
 * <p><b>The engine is really off while electric.</b> {@code tickPower} is the only writer of the
 * power figure {@code hasPower()} and {@code TrackedVehicle.tickMove} read, and it ramps that
 * figure only while the engine flag is on and there is fuel. So the electric mode cannot simply
 * leave the base method alone: the battery has to be the source of that one number. This mixin runs
 * the same ramp, the same water check and the same destroyed-tank decay as the base, but on the
 * battery's authority, and then cancels the base. The ignition flag is left off, which is what makes
 * the mode genuinely silent and fuel-free rather than a label over a running diesel.
 *
 * <p><b>{@code tickEnergy} is cancelled too.</b> No fuel is burned while the battery drives, and the
 * tank can leave on a full battery with an empty tank - the case a hybrid exists for.
 *
 * <p>The two fields are the whole of the state. They are not saved; see {@link HybridState}.
 */
@Mixin(AbstractVehicle.class)
public abstract class AbstractVehicleHybridMixin implements HybridState {

    /** The pack's own answer, read by {@code tickPower} before it clears power in water. */
    @Shadow
    protected boolean canWade;

    @Unique
    private boolean ywzjarmor$hybridActive;

    /** NaN is "this vehicle has not been ticked yet": the configured starting charge is applied. */
    @Unique
    private float ywzjarmor$hybridBattery = Float.NaN;

    @Override
    public boolean ywzjarmor$hybridActive() {
        return ywzjarmor$hybridActive;
    }

    @Override
    public void ywzjarmor$setHybridActive(boolean active) {
        ywzjarmor$hybridActive = active;
    }

    @Override
    public float ywzjarmor$hybridBattery() {
        return ywzjarmor$hybridBattery;
    }

    @Override
    public void ywzjarmor$setHybridBattery(float battery) {
        ywzjarmor$hybridBattery = battery;
    }

    /**
     * No fuel is burned while the battery is driving.
     *
     * <p>Cancelled at the head, before the capability is read: the base's own {@code tickEnergy}
     * is a read-modify-write of the fuel capability, and there is nothing to modify.
     */
    @Inject(method = "tickEnergy", at = @At("HEAD"), cancellable = true)
    private void ywzjarmor$noFuelWhileElectric(CallbackInfo ci) {
        AbstractVehicle self = (AbstractVehicle) (Object) this;
        if (!self.m_9236_().m_5776_() && Hybrid.isActive(self)) {
            ci.cancel();
        }
    }

    /**
     * Accounts for the battery every server tick, and drives the power figure from it while
     * electric.
     *
     * <p>Runs before the base method, because the battery tick is what decides whether the mode is
     * still on when the base power logic runs. {@code tickPower} is inside
     * {@code m_8119_}'s server-only branch, but the client guard is kept for the same reason every
     * other tick-side mixin has one: the method is reachable from code that does not care about
     * sides.
     *
     * <p>While electric the base method is cancelled and its logic is reproduced here with the
     * battery as the fuel: water still stops a vehicle that cannot wade, a wreck still winds down,
     * and a live tank still ramps. That is the whole of what the base method does, which is why
     * replacing it is a small, auditable block rather than a second solver.
     */
    @Inject(method = "tickPower", at = @At("HEAD"), cancellable = true)
    private void ywzjarmor$hybridPower(CallbackInfo ci) {
        AbstractVehicle self = (AbstractVehicle) (Object) this;
        if (self.m_9236_().m_5776_()) {
            return;
        }
        // This also applies the configured starting charge on the first tick of a vehicle's life.
        Hybrid.tickServer(self);

        if (!Hybrid.isActive(self)) {
            // Diesel mode, or the battery just ran out: the base's own engine/fuel logic is the
            // right answer, including the empty-tank stop.
            return;
        }
        if (!this.canWade) {
            FluidState fluid = self.m_9236_().m_6425_(BlockPos.m_274446_(
                    new Vec3(self.getMainCubeOBB().obb().center())));
            if (!fluid.m_76178_()) {
                self.setPower(0.0F);
                ci.cancel();
                return;
            }
        }
        if (self.isDestroyed()) {
            self.setPower(Math.max(self.getPower() - 10.0F, 0.0F));
            ci.cancel();
            return;
        }
        self.setPower(Mth.m_14036_(self.getPower() + 1.0F, 0.0F, 100.0F));
        ci.cancel();
    }
}
