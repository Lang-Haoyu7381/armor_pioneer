package com.ywzjvehicle.armor.network;

import com.ywzjvehicle.armor.Fording;
import com.ywzjvehicle.armor.GunnerSettings;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server to client: this is what the fording kit did.
 *
 * <p>Carries the numbers as well as the verdict, because the line the player reads names the water
 * they are standing in ("6.2 of 5"). Leaving them out would mean the client measuring the water
 * itself, which is a second opinion about a fact the server has already decided - and the two would
 * disagree exactly when it mattered.
 *
 * <p>{@code hasSnorkel} is the one thing a server and a client can honestly disagree about, because
 * the model belongs to the vehicle pack each of them loaded. Sent from the server's side so that the
 * reply is about the vehicle the key was pressed in.
 *
 * <p>Its other job is the one {@code GunnerStateMessage}'s arrival does: it proves the server is
 * running this addon. A keypress that gets no reply at all is reported as "this server has no
 * addon", and this is what makes that report true rather than a guess.
 */
public record FordingStateMessage(int verdict, boolean deployed, float depth, float maxDepth,
                                  boolean hasSnorkel) {

    public static final int ID = 3;

    /**
     * Written as plain bytes and floats rather than var-ints on purpose: {@code writeByte},
     * {@code writeFloat} and their readers come from netty and so keep their names in the SRG jar
     * the addon compiles against, while the var-int pair are Minecraft's own.
     */
    public static void encode(FordingStateMessage message, FriendlyByteBuf buffer) {
        buffer.writeByte(message.verdict);
        buffer.writeByte(message.deployed ? 1 : 0);
        buffer.writeFloat(message.depth);
        buffer.writeFloat(message.maxDepth);
        buffer.writeByte(message.hasSnorkel ? 1 : 0);
    }

    public static FordingStateMessage decode(FriendlyByteBuf buffer) {
        return new FordingStateMessage(buffer.readByte(), buffer.readByte() != 0,
                buffer.readFloat(), buffer.readFloat(), buffer.readByte() != 0);
    }

    public static void handle(FordingStateMessage message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        // Only ever sent towards a client: acting on it server-side would let a client decide what
        // its own kit is doing.
        if (!context.getDirection().getReceptionSide().isClient()) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            // Parked rather than shown: this runs on the network thread, and the tick handler is
            // what owns the screen. Same road the fuze readout was built to take.
            GunnerSettings.queueFeedback(Fording.describe(Fording.Verdict.byId(message.verdict),
                    message.depth, message.maxDepth, message.hasSnorkel));
            GunnerSettings.markServerPresent();
        });
        context.setPacketHandled(true);
    }
}
