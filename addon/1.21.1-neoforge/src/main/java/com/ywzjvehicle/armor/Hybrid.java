package com.ywzjvehicle.armor;

import com.ywzjvehicle.armor.network.HybridActionMessage;
import net.minecraft.network.chat.Component;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * The ZTZ-100's hybrid-electric drive: a battery the diesel engine charges, and an electric mode
 * the battery alone can run.
 *
 * <p><b>What is real and what the game can express.</b> The ZTZ-100 is reported to have a
 * diesel-electric hybrid powertrain - the engine generates, electric motors drive, and a battery
 * carries the vehicle for a while without burning fuel. The base mod has no separate electric motor:
 * {@code AbstractVehicle.tickPower} ramps one power figure from the engine state, and
 * {@code TrackedVehicle.tickMove} multiplies acceleration and turn rate by
 * {@code getSuspensionPowerScale}. So the hybrid is layered onto that model rather than replacing
 * it: while electric, the ignition is off, {@code AbstractVehicleHybridMixin} keeps that one power
 * figure up from the battery, fuel is not burned, the client silences the engine and exhaust, and
 * the diesel comes back the moment the battery runs out. The base power figure is not a second
 * solver; it is the same number the motor would produce.
 *
 * <p><b>The battery is the whole of the state.</b> There is no per-part "electric drive" flag and no
 * second fuel tank. The battery is a float on the vehicle, the engine is the base mod's own flag, and
 * the decisions are the pure functions below so the offline tools can assert the truth table without
 * a game. Everything that decides anything runs on the server thread; the client copy only draws and
 * silences sounds.
 *
 * <p><b>Not saved, like the fording kit and the suspension pose.</b> A vehicle reloaded from disk
 * comes back with a full battery and the diesel mode selected - a deliberate gap, and the one that
 * keeps the battery from becoming a second opinion about a world that has been reloaded. See
 * {@code HybridState}.
 */
public final class Hybrid {

    private Hybrid() {
    }

    /**
     * One vehicle's hybrid drive, as configured. The block being <b>present</b> is the switch, the
     * same rule {@code fording} and {@code part_armor} follow.
     *
     * <p>The battery is in <b>percent of capacity</b>. That unit is what the crew reads on the HUD and
     * what the action-bar lines quote, so a config edited to a different capacity keeps its meaning:
     * a 100-unit battery and a 200-unit battery both read 100% when full. The two rates are in
     * percentage points per tick, which makes the arithmetic on screen the arithmetic in the config.
     */
    public static final class Settings {
        /** Battery capacity, in percent. The display is always out of this. */
        public float battery_capacity = 100.0F;

        /** Charge a freshly spawned vehicle starts with, in percent. Full. */
        public float initial_charge = 100.0F;

        /**
         * Percentage points the diesel engine puts back per tick while it is running in diesel
         * mode. At the default 0.06 a flat battery reaches the 10% engage threshold in about 8.5
         * seconds, and a full one in about 83 seconds - a charging run rather than a wait.
         */
        public float charge_per_tick = 0.06F;

        /** Percentage points the electric drive uses per tick while the tank is standing still. */
        public float idle_drain_per_tick = 0.01F;

        /**
         * Percentage points the electric drive uses per tick with a drive key held. At the default
         * 0.15 a full battery gives about 33 seconds of full-throttle electric driving - a burst
         * for a silent approach or a short retreat, not a second diesel.
         */
        public float drive_drain_per_tick = 0.15F;

        /**
         * Percentage points recovered per tick while rolling with no drive key held - the
         * regenerative half of the hybrid, and the reason a tank can recover some of what a climb
         * spent on the way back down.
         */
        public float regen_per_tick = 0.03F;

        /**
         * Battery needed to engage electric mode. Below this the key is refused, so a crew cannot
         * flick into electric for a second and get the engine restarted by the empty-battery
         * cut-out on the next tick.
         */
        public float min_start_percent = 10.0F;

        /**
         * Acceleration and turn multiplier while electric. It composes with the engine's own
         * hp/t factor rather than replacing it: the ZTZ-100 is 1.5x the reference on its diesel
         * alone, and 0.8 of that is still 1.2x. Electric is quieter and slower, not gutted.
         */
        public float electric_power_scale = 0.8F;

        /**
         * Fraction of the gearbox's maximum speed the electric drive can reach. The base mod
         * clamps top speed separately from acceleration, so this is the one figure that makes the
         * electric mode a low-speed mode rather than only a weaker one.
         */
        public float electric_speed_fraction = 0.55F;

        /** The capacity, guarded against a hand-edited zero or negative. */
        public float capacity() {
            return battery_capacity > 0.0F ? battery_capacity : 100.0F;
        }
    }

    // --- the action-bar codes -------------------------------------------------------------------

    /** A state sync, with nothing to announce. */
    public static final int SYNC = 0;
    /** Electric mode engaged. */
    public static final int ON = 1;
    /** Electric mode disengaged by the driver. */
    public static final int OFF = 2;
    /** The battery ran out and the diesel engine took over. */
    public static final int EMPTY = 3;
    /** There was not enough charge to engage. */
    public static final int LOW = 4;
    /** This vehicle has no hybrid drive configured. */
    public static final int NONE = 5;
    /** Only the driver may change the powertrain mode. */
    public static final int DRIVER = 6;

    /** Below this horizontal speed a tank counts as stationary for regen. */
    public static final float MOVING_SPEED = 0.05F;

    // --- the decisions, as pure functions -------------------------------------------------------

    /**
     * One tick of battery accounting.
     *
     * <p>Pure on purpose: the interesting failures are arithmetic - a sign inverted so that
     * electric mode charges instead of draining, an engage threshold compared backwards, a drain
     * that ignores the throttle - and none of them throws or logs. {@code HybridCheck} asserts this
     * table offline.
     *
     * <p>While electric, the diesel is off and the numbers here decide the battery. While not
     * electric, the engine charges the battery only if it is running and has fuel - an empty tank
     * is exactly the case the battery exists for, and a charging rate that ignored it would make a
     * dry tank recover its battery from nothing.
     */
    public static Battery batteryTick(Settings settings, float battery, boolean active,
                                      boolean engineOn, boolean hasFuel, boolean driving,
                                      float horizontalSpeed) {
        float capacity = settings.capacity();
        float amount = clamp(battery, 0.0F, capacity);
        boolean coasting = !driving && horizontalSpeed > MOVING_SPEED;
        if (active) {
            // Three states, not two: under throttle the motor is working and the battery falls;
            // stationary it only supplies the electronics; rolling with the throttle released it
            // regenerates. The last case has to be a charge rather than "idle drain minus regen",
            // or a regen stronger than the idle load would be clamped to no change.
            float delta;
            if (driving) {
                delta = -Math.max(0.0F, settings.drive_drain_per_tick);
            } else if (coasting) {
                delta = Math.max(0.0F, settings.regen_per_tick);
            } else {
                delta = -Math.max(0.0F, settings.idle_drain_per_tick);
            }
            amount = clamp(amount + delta, 0.0F, capacity);
            if (amount <= 0.0F) {
                return new Battery(0.0F, false, true);
            }
            return new Battery(amount, true, false);
        }
        float charge = 0.0F;
        if (engineOn && hasFuel) {
            charge += Math.max(0.0F, settings.charge_per_tick);
        }
        if (coasting) {
            charge += Math.max(0.0F, settings.regen_per_tick);
        }
        return new Battery(clamp(amount + charge, 0.0F, capacity), false, false);
    }

    /** @return whether there is enough charge to engage electric mode. */
    public static boolean canEngage(Settings settings, float battery) {
        return battery >= Math.max(0.0F, settings.min_start_percent);
    }

    /** One tick's result: the new charge, whether electric is still on, and whether it just ran out. */
    public record Battery(float amount, boolean active, boolean depleted) {
    }

    // --- the vehicle's copy ---------------------------------------------------------------------

    /** @return the hybrid settings for this vehicle, or {@code null} when it has none. */
    public static Settings settingsFor(AbstractVehicle vehicle) {
        return vehicle == null ? null : ArmorSystem.hybridFor(vehicle);
    }

    /** @return whether this vehicle has a hybrid drive configured at all. */
    public static boolean fitted(AbstractVehicle vehicle) {
        return settingsFor(vehicle) != null;
    }

    /** @return the battery as the vehicle holds it, with the configured starting charge filled in. */
    public static float batteryOf(HybridState state, Settings settings) {
        float raw = state.ywzjarmor$hybridBattery();
        if (Float.isNaN(raw)) {
            return clamp(settings.initial_charge, 0.0F, settings.capacity());
        }
        return clamp(raw, 0.0F, settings.capacity());
    }

    /** @return whether electric mode is on and the vehicle is a configured hybrid. */
    public static boolean isActive(AbstractVehicle vehicle) {
        Settings settings = settingsFor(vehicle);
        return settings != null && vehicle instanceof HybridState state
                && state.ywzjarmor$hybridActive();
    }

    /** @return the acceleration multiplier the electric drivetrain contributes. */
    public static float powerScale(AbstractVehicle vehicle) {
        Settings settings = settingsFor(vehicle);
        return settings != null && isActive(vehicle)
                ? Math.max(0.0F, settings.electric_power_scale) : 1.0F;
    }

    /** @return the fraction of the vehicle's top speed the electric mode is held to. */
    public static float speedFraction(AbstractVehicle vehicle) {
        Settings settings = settingsFor(vehicle);
        if (settings == null || !isActive(vehicle)) {
            return 1.0F;
        }
        return clamp(settings.electric_speed_fraction, 0.0F, 1.0F);
    }

    /**
     * The server's once-per-tick hybrid update.
     *
     * <p>Called from {@code AbstractVehicleHybridMixin}'s injection at the head of
     * {@code tickPower}, which is inside the base mod's server-only branch and runs before
     * {@code tickMove}. Two jobs: account for the battery, and make sure the state the movement
     * solver is about to read is coherent - the electric mode has a driver, is not on a wreck, and
     * has its diesel off. The mixin keeps the one power figure the solver reads up from the battery
     * while that flag is off.
     *
     * <p>The battery update runs even when the mode is off, because that is what charging is. The
     * engine is forced off only while electric, and only after the driver/wreck checks: a mode that
     * started or stopped an engine on an abandoned or destroyed tank would be a state nobody asked
     * for.
     */
    public static void tickServer(AbstractVehicle vehicle) {
        Settings settings = settingsFor(vehicle);
        if (settings == null || !(vehicle instanceof HybridState state)) {
            return;
        }
        float battery = batteryOf(state, settings);
        boolean active = state.ywzjarmor$hybridActive();
        if (active && (vehicle.isDestroyed() || vehicle.getDriver() == null)) {
            // The crew left, or the tank is a wreck. Neither can benefit from a drivetrain mode -
            // and the tracking broadcast matters here even though there may be nobody aboard: the
            // clients that were hearing a silent tank have to be told the diesel is back. A live
            // tank whose driver walked away gets its engine restarted, because that is the state
            // the base mod leaves an abandoned vehicle in.
            state.ywzjarmor$setHybridActive(false);
            state.ywzjarmor$setHybridBattery(battery);
            if (!vehicle.isDestroyed() && !vehicle.isEngineOn()) {
                vehicle.toggleEngine(true);
            }
            HybridActionMessage.broadcastState(vehicle, OFF, null);
            return;
        }
        if (active && vehicle.isEngineOn()) {
            // Electric is a real engine-off mode. The base power figure is the only one the
            // movement solver reads, so AbstractVehicleHybridMixin keeps that figure up from the
            // battery while this flag is off; leaving the diesel "on" would only be a label.
            vehicle.toggleEngine(false);
        }

        boolean driving = vehicle.controlUnit.forward || vehicle.controlUnit.backward
                || vehicle.controlUnit.left || vehicle.controlUnit.right;
        float speed = horizontalSpeed(vehicle);
        Battery next = batteryTick(settings, battery, active, vehicle.isEngineOn(),
                vehicle.getEnergy() > 0.0F, driving, speed);
        state.ywzjarmor$setHybridBattery(next.amount());

        if (active && !next.active()) {
            state.ywzjarmor$setHybridActive(false);
            vehicle.toggleEngine(true);
            // A tank that has just run dry is answered to the crew, because from the driver's
            // seat the only visible change is the engine coming back and the HUD changing.
            HybridActionMessage.broadcastState(vehicle, EMPTY, vehicle.getDriver() instanceof
                    net.minecraft.server.level.ServerPlayer driver ? driver : null);
        } else if (next.active() != active) {
            state.ywzjarmor$setHybridActive(next.active());
        }

        // Periodic state so the HUD and the sound suppression follow the battery. Ten ticks is
        // twice a second, which is enough for a percentage that moves by hundredths, and far less
        // traffic than per-tick. No riders means no dashboard and no client near enough to gate
        // its own engine sound on this, so it is skipped entirely.
        if (!vehicle.getPassengers().isEmpty() && vehicle.tickCount % 10 == 0) {
            HybridActionMessage.broadcastState(vehicle, SYNC, null);
        }
    }

    /** Horizontal speed, ignoring the vertical component. */
    public static float horizontalSpeed(AbstractVehicle vehicle) {
        net.minecraft.world.phys.Vec3 delta = vehicle.getDeltaMovement();
        return (float) Math.sqrt(delta.x * delta.x
                + delta.z * delta.z);
    }

    /** @return 0..100 for the HUD and the action bar. */
    public static float percentOf(Settings settings, float battery) {
        float capacity = settings.capacity();
        return clamp(battery / capacity, 0.0F, 1.0F) * 100.0F;
    }

    /**
     * The sentence a press gets, built here so the client never keeps a second copy of which code
     * means what. {@code SYNC} has no line, which is what makes the periodic update silent.
     */
    public static Component describe(int code, float percent, float minStartPercent) {
        String id = "message." + VehicleArmorMod.MOD_ID + ".";
        return switch (code) {
            case ON -> Component.translatable(id + "hybrid_on", Math.round(percent));
            case OFF -> Component.translatable(id + "hybrid_off", Math.round(percent));
            case EMPTY -> Component.translatable(id + "hybrid_empty");
            case LOW -> Component.translatable(id + "hybrid_low", Math.round(minStartPercent));
            case NONE -> Component.translatable(id + "hybrid_none");
            case DRIVER -> Component.translatable(id + "hybrid_driver");
            default -> null;
        };
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
