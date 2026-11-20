package com.ywzjvehicle.armor.mixin;

import com.ywzjvehicle.armor.ArmorSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;

/**
 * Turns an aircraft's horsepower per tonne into a thrust multiplier.
 *
 * <p>The ground vehicles get their power-to-weight factor from
 * {@code AbstractVehicle#getSuspensionPowerScale}; an aircraft has no drivetrain to scale, and its
 * whole performance comes from one term in {@code FixedWingVehicle.tickMove}:
 *
 * <pre>{@code
 * float thrust = this.thrust * power;
 * double a = PhysicsHelper.accelerationPerTick(thrust, mass);
 * }</pre>
 *
 * <p>So the redirect sits on that field read and nothing else. The pack's thrust curve, drag, lift
 * and the aerodynamic turn-rate limits are untouched; the factor changes how much thrust the engine
 * is worth, and speed, climb and energy follow through the same solver. That is the aircraft
 * analogue of the ground rule - a correction on top of the pack's own performance, not a
 * replacement for it.
 *
 * <p><b>The server only.</b> The solver runs on the server; the client renders the synced motion.
 * The handler returns the raw field on the client so no prediction is made against a config that
 * belongs to the server.
 *
 * <p>The field is read once in {@code tickMove} (verified with javap against 0.6.3: one
 * {@code getfield FixedWingVehicle.thrust:F}); the {@code thrustK} beside it is a different field
 * and is not matched.
 */
@Mixin(FixedWingVehicle.class)
public abstract class FixedWingVehiclePowerMixin {

    @Redirect(method = "tickMove",
            at = @At(value = "FIELD",
                    target = "Lorg/ywzj/vehicle/entity/vehicle/FixedWingVehicle;thrust:F"))
    private float ywzjarmor$powerScaledThrust(FixedWingVehicle vehicle) {
        float thrust = vehicle.thrust;
        if (vehicle.m_9236_().m_5776_()) {
            return thrust;
        }
        return thrust * ArmorSystem.enginePowerScale(vehicle);
    }
}
