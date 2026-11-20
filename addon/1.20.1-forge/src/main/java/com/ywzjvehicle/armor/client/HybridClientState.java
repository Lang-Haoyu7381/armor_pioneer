package com.ywzjvehicle.armor.client;

import com.ywzjvehicle.armor.ArmorSystem;
import com.ywzjvehicle.armor.GunnerSettings;
import com.ywzjvehicle.armor.Hybrid;
import com.ywzjvehicle.armor.network.HybridStateMessage;
import net.minecraft.network.chat.Component;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.HashMap;
import java.util.Map;

/**
 * The last hybrid state the server sent, for the client that draws the HUD and plays the sounds.
 *
 * <p>Client-only and named from exactly one place, the network handler's is-client branch. It is a
 * cache, not an opinion: the server owns the battery and the mode, and this class only remembers
 * what it was last told so the HUD row and the engine-sound gate can answer between packets. A
 * vehicle with no entry is simply not electric, which is the right default for a client that has
 * only just started tracking the tank.
 *
 * <p>The key is the entity id, not the vehicle object, because the message carries the id and the
 * renderer holds the entity. A vehicle that unloads leaves its entry behind until the next logout;
 * the map is small, the ids are ints, and clearing on every level change would be a second place to
 * forget to clear.
 */
public final class HybridClientState {

    private HybridClientState() {
    }

    /** What the last message said. */
    public record View(boolean fitted, boolean active, float batteryFraction,
                       float minStartPercent) {
    }

    private static final Map<Integer, View> STATES = new HashMap<>();

    /** Applies a state message and, when it is the sender's answer, parks the action-bar line. */
    public static void apply(HybridStateMessage message) {
        if (message.vehicleEntityId() >= 0) {
            STATES.put(message.vehicleEntityId(), new View(message.fitted(), message.active(),
                    message.batteryFraction(), message.minStartPercent()));
        }
        GunnerSettings.markServerPresent();
        if (!message.announce()) {
            return;
        }
        // Parked rather than shown: this runs on the network thread's work queue, and the tick
        // handler owns the action bar. SYNC has no line, which is what makes the periodic packet
        // silent.
        Component line = Hybrid.describe(message.code(),
                message.batteryFraction() * 100.0F, message.minStartPercent());
        if (line != null) {
            GunnerSettings.queueFeedback(line);
        }
    }

    /** @return the last state for this vehicle, or {@code null} when none has arrived yet. */
    public static View view(AbstractVehicle vehicle) {
        return vehicle == null ? null : STATES.get(vehicle.m_19879_());
    }

    /** @return whether this vehicle is currently on battery power, as far as the client knows. */
    public static boolean isElectric(AbstractVehicle vehicle) {
        View view = view(vehicle);
        return view != null && view.active();
    }

    /**
     * @return the electric drivetrain's acceleration multiplier, for the HUD's "actual power" row.
     *
     * <p>Read from the local config, so the number belongs to the same settings the row beside it
     * was built from. A client and a server with different configs can disagree about the exact
     * factor; the HUD is the client's view, while the movement solver's number is the server's.
     */
    public static float powerFactor(AbstractVehicle vehicle) {
        Hybrid.Settings settings = ArmorSystem.hybridFor(vehicle);
        return settings != null && isElectric(vehicle)
                ? Math.max(0.0F, settings.electric_power_scale) : 1.0F;
    }

    /** Forgets every vehicle's state, for a logout or a level change. */
    public static void clear() {
        STATES.clear();
    }
}
