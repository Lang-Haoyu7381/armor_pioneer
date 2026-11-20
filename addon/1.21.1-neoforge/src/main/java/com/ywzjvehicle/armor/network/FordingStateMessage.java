package com.ywzjvehicle.armor.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import com.ywzjvehicle.armor.VehicleArmorMod;

import com.ywzjvehicle.armor.Fording;
import com.ywzjvehicle.armor.GunnerSettings;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * Server to client: this is what the fording kit is doing.
 *
 * <p>Carries the numbers as well as the verdict, because the line the player reads names the water
 * they are standing in ("6.2 of 5") and, during the preparation, the percentage of the job the crew
 * has finished. Leaving them out would mean the client measuring the water and guessing the progress
 * itself, which is a second opinion about facts the server has already decided - and the two would
 * disagree exactly when it mattered.
 *
 * <p>{@code hasSnorkel} is the one thing a server and a client can honestly disagree about, because
 * the model belongs to the vehicle pack each of them loaded. Sent from the server's side so that the
 * reply is about the vehicle the key was pressed in.
 *
 * <p>{@code note} is what distinguishes the messages: a refusal to start while moving and a line
 * about the water are both "status", but they are not the same sentence. The note is the reason the
 * server spoke; the verdict is the state of the water.
 *
 * <p>Its other job is the one {@code GunnerStateMessage}'s arrival does: it proves the server is
 * running this addon. A keypress that gets no reply at all is reported as "this server has no
 * addon", and this is what makes that report true rather than a guess.
 */
public record FordingStateMessage(int verdict, int note, int phase, int percent, float depth,
                                  float maxDepth, int seconds, boolean hasSnorkel)
        implements CustomPacketPayload {

    public static final int ID = 3;

    public static final CustomPacketPayload.Type<FordingStateMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    VehicleArmorMod.MOD_ID, "fording_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FordingStateMessage> CODEC =
            CustomPacketPayload.codec(FordingStateMessage::encode, FordingStateMessage::decode);

    @Override
    public CustomPacketPayload.Type<FordingStateMessage> type() {
        return TYPE;
    }

    /**
     * Written as plain bytes and floats rather than var-ints on purpose: {@code writeByte},
     * {@code writeFloat} and their readers come from netty, while the var-int pair are Minecraft's
     * own.
     */
    public static void encode(FordingStateMessage message, RegistryFriendlyByteBuf buffer) {
        buffer.writeByte(message.verdict);
        buffer.writeByte(message.note);
        buffer.writeByte(message.phase);
        buffer.writeByte(message.percent);
        buffer.writeFloat(message.depth);
        buffer.writeFloat(message.maxDepth);
        buffer.writeByte(message.seconds);
        buffer.writeByte(message.hasSnorkel ? 1 : 0);
    }

    public static FordingStateMessage decode(RegistryFriendlyByteBuf buffer) {
        return new FordingStateMessage(buffer.readByte(), buffer.readByte(), buffer.readByte(),
                buffer.readByte(), buffer.readFloat(), buffer.readFloat(),
                buffer.readByte(), buffer.readByte() != 0);
    }

    public static void handle(FordingStateMessage message, IPayloadContext context) {
        // Only ever sent towards a client: acting on it server-side would let a client decide what
        // its own kit is doing.
        if (context.flow() != PacketFlow.CLIENTBOUND) {
            return;
        }
        context.enqueueWork(() -> {
            // Parked rather than shown: this runs on the network thread, and the tick handler is
            // what owns the screen. Same road the fuze readout was built to take.
            GunnerSettings.queueFeedback(Fording.describe(new Fording.Report(
                    Fording.Verdict.byId(message.verdict),
                    Fording.Note.byId(message.note),
                    Fording.Phase.byId(message.phase),
                    message.percent,
                    message.depth,
                    message.maxDepth,
                    message.seconds,
                    message.hasSnorkel)));
            GunnerSettings.markServerPresent();
        });
    }

    /**
     * Sends one status line to everyone riding, which is the crew that is doing the work.
     *
     * <p>Everyone rather than only the driver: the commander watches the turret lock and the
     * passengers are the ones who have to know the hull is sealed. The action bar is a personal
     * channel, so each of them gets their own copy in their own language.
     */
    public static void sendToVehicle(AbstractVehicle vehicle, Fording.Report report) {
        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof ServerPlayer player) {
                sendTo(player, report);
            }
        }
    }

    /** Sends one status line to one player - used for a refusal only that player needs to read. */
    public static void sendTo(ServerPlayer player, Fording.Report report) {
        PacketDistributor.sendToPlayer(player,
                new FordingStateMessage(report.verdict().ordinal(), report.note().ordinal(),
                        report.phase().ordinal(), report.percent(), report.depth(),
                        report.maxDepth(), report.seconds(), report.hasSnorkel()));
    }
}
