package com.ywzjvehicle.armor.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.PacketFlow;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import com.ywzjvehicle.armor.VehicleArmorMod;

/**
 * Server to client: the hybrid drive's state on a named vehicle.
 *
 * <p>Carries the charge as a <b>fraction</b> rather than a percentage of a capacity named on the
 * wire, because the two sides may have read different config files: a server whose battery is half
 * full must draw half full on a client whose capacity says 200. The minimum engage threshold travels
 * for the same reason - it is the server's number that decides a refusal, so it is the server's
 * number the reply line should quote.
 *
 * <p>{@code code} is what the action bar says, and {@link #SYNC} is deliberately silent: the
 * periodic update exists so the HUD and the sound suppression follow the battery, not so the crew
 * is told the same thing twice a second.
 */
public record HybridStateMessage(int vehicleEntityId, boolean fitted, boolean active,
                                 float batteryFraction, float minStartPercent,
                                 int code, boolean announce) implements CustomPacketPayload {

    public static final int ID = 11;

    public static final CustomPacketPayload.Type<HybridStateMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    VehicleArmorMod.MOD_ID, "hybrid_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HybridStateMessage> CODEC =
            CustomPacketPayload.codec(HybridStateMessage::encode, HybridStateMessage::decode);

    @Override
    public CustomPacketPayload.Type<HybridStateMessage> type() {
        return TYPE;
    }

    /** Plain bytes and floats: netty's methods keep their names in the SRG jar. */
    public static void encode(HybridStateMessage message, RegistryFriendlyByteBuf buffer) {
        buffer.writeInt(message.vehicleEntityId);
        buffer.writeByte(message.fitted ? 1 : 0);
        buffer.writeByte(message.active ? 1 : 0);
        buffer.writeFloat(message.batteryFraction);
        buffer.writeFloat(message.minStartPercent);
        buffer.writeByte(message.code);
        buffer.writeByte(message.announce ? 1 : 0);
    }

    public static HybridStateMessage decode(RegistryFriendlyByteBuf buffer) {
        return new HybridStateMessage(buffer.readInt(), buffer.readByte() != 0,
                buffer.readByte() != 0, buffer.readFloat(), buffer.readFloat(),
                buffer.readByte(), buffer.readByte() != 0);
    }

    public static void handle(HybridStateMessage message, IPayloadContext context) {
        if (context.flow() != PacketFlow.CLIENTBOUND) {
            return;
        }
        // DistExecutor, for the reason every client-only branch here uses it: this message is
        // registered on both sides, and a class reference in a branch that is not taken is still a
        // reference in the constant pool.
        context.enqueueWork(() -> com.ywzjvehicle.armor.client.HybridClientState.apply(message));
    }
}
