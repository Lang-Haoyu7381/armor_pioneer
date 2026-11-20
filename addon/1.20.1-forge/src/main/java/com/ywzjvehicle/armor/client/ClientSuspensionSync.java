package com.ywzjvehicle.armor.client;

import com.ywzjvehicle.armor.GunnerSettings;
import com.ywzjvehicle.armor.Suspension;
import com.ywzjvehicle.armor.SuspensionState;
import com.ywzjvehicle.armor.network.SuspensionStateMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * Applies the server's suspension pose on the client that draws the vehicle.
 *
 * <p>Client-only and named from exactly one place, the network handler's is-client branch, so a
 * dedicated server never loads this class. The pose is set on the vehicle through the same
 * {@link SuspensionState} interface the server-side mixin implements; from there
 * {@code SuspensionUnitMixin} writes the same rest lengths on this side as on the other, and the
 * wheel's drawn travel agrees with the hull's server-authoritative height.
 *
 * <p>The vehicle is looked up by entity id rather than carried through the packet as anything
 * richer: the id is what the server used to find the riders in the first place, and a client that
 * has not received the entity yet simply does nothing - the next state message, or entering the
 * seat, sends another.
 */
public final class ClientSuspensionSync {

    private ClientSuspensionSync() {
    }

    public static void apply(SuspensionStateMessage message) {
        Minecraft minecraft = Minecraft.m_91087_();
        GunnerSettings.setClientSuspension(message.hasSuspension(), message.rise(),
                message.pitchDegrees(), message.rollDegrees());
        if (minecraft.f_91073_ != null && message.hasSuspension()) {
            Entity entity = minecraft.f_91073_.m_6815_(message.vehicleEntityId());
            if (entity instanceof AbstractVehicle vehicle
                    && vehicle instanceof SuspensionState state) {
                state.ywzjarmor$setSuspensionPose(new Suspension.Pose(
                        message.heightStep(), message.pitchStep(), message.rollStep()));
            }
        }
        if (message.announce()) {
            // Parked rather than shown: this runs on the network thread's work queue, which is not
            // the screen's, and the tick handler owns the action bar.
            GunnerSettings.queueFeedback(Suspension.describe(message.hasSuspension(),
                    message.hasRoll(), message.rise(), message.pitchDegrees(),
                    message.rollDegrees()));
        }
        GunnerSettings.markServerPresent();
    }
}
