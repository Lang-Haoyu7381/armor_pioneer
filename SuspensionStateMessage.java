package com.ywzjvehicle.armor.network;

import com.ywzjvehicle.armor.GunnerSettings;
import com.ywzjvehicle.armor.Suspension;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server to client: this is where the suspension now is.
 *
 * <p>Both axes are reported on every press, not just the one that moved, because they are one
 * setting: someone who has just put the nose down wants to see that the height they chose earlier is
 * still there, and a line that named only the axis they touched would leave them wondering whether
 * the other one had been reset.
 *
 * <p>What is sent is the measurement rather than the step index - how many blocks up the hull is
 * held and how many degrees it is pitched - for the same reason the fording reply carries the depth
 * it measured: the server's file is what decided what the keypress meant, and a client whose file
 * said something else would otherwise label the position it heard about with its own range.
 *
 * <p>Its other job is the one every reply from this channel has: proving the server is running this
 * addon, so that a keypress which gets no answer at all can be reported as "this server has no
 * addon" rather than left as silence.
 */
public record SuspensionStateMessage(boolean hasSuspension, float rise, float pitchDegrees) {

    public static final int ID = 5;

    /** Plain bytes and floats, no var-ints: netty's methods keep their names in the SRG jar. */
    public static void encode(SuspensionStateMessage message, FriendlyByteBuf buffer) {
        buffer.writeByte(message.hasSuspension ? 1 : 0);
        buffer.writeFloat(message.rise);
        buffer.writeFloat(message.pitchDegrees);
    }

    public static SuspensionStateMessage decode(FriendlyByteBuf buffer) {
        return new SuspensionStateMessage(buffer.readByte() != 0, buffer.readFloat(),
                buffer.readFloat());
    }

    public static void handle(SuspensionStateMessage message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        if (!context.getDirection().getReceptionSide().isClient()) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            // Parked rather than shown: this runs on the network thread, and the tick handler owns
            // the screen.
            GunnerSettings.queueFeedback(Suspension.describe(message.hasSuspension(),
                    message.rise(), message.pitchDegrees()));
            GunnerSettings.markServerPresent();
        });
        context.setPacketHandled(true);
    }
}
