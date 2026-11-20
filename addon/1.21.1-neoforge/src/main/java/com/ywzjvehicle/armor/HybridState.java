package com.ywzjvehicle.armor;

/**
 * The per-vehicle half of the hybrid drive: whether electric mode is on, and how much charge is
 * left.
 *
 * <p>The two live on the vehicle entity, next to the base mod's own engine flag, so they go away
 * with the tank instead of having to be kept in a registry keyed by something that can be unloaded.
 * They are not saved: a vehicle reloaded from disk comes back with a full battery and the diesel
 * mode selected, which is the same deliberate gap the fording flag and the suspension pose have,
 * and for the same reason - a drivetrain mode nobody is sitting in does not survive the crew
 * leaving.
 *
 * <p><b>Reached by {@code instanceof} from the server-side message handler and the mixin</b>, so
 * neither has to name the other. The battery is allowed to be {@link Float#NaN} in the first tick
 * of a vehicle's life: that is "the configured starting charge has not been applied yet", and
 * {@link Hybrid#batteryOf} is the only reader that knows it.
 */
public interface HybridState {

    /** @return whether electric drive is engaged. */
    boolean ywzjarmor$hybridActive();

    /** Engages or disengages electric drive. */
    void ywzjarmor$setHybridActive(boolean active);

    /** @return charge in percent of capacity, or {@code NaN} before the first tick. */
    float ywzjarmor$hybridBattery();

    /** Sets the charge in percent of capacity. */
    void ywzjarmor$setHybridBattery(float battery);
}
