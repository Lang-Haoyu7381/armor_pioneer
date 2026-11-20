package com.ywzjvehicle.armor.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import com.ywzjvehicle.armor.VehicleArmorMod;

/**
 * A legacy commander-action packet, kept only so an older client's message is consumed harmlessly.
 *
 * <p>The addon no longer overrides the base mod's control: seat zero's occupant drives and every
 * seat operates its own weapon, with the base mod's own scroll-wheel weapon switch. The packet id
 * and layout are unchanged so a mixed old/new client does not fail at the channel; the server
 * simply ignores the intent.
 */
public record CrewActionMessage(int action, int targetEntityId) implements CustomPacketPayload {

    public static final int ID = 6;

    public static final CustomPacketPayload.Type<CrewActionMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    VehicleArmorMod.MOD_ID, "crew_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CrewActionMessage> CODEC =
            CustomPacketPayload.codec(CrewActionMessage::encode, CrewActionMessage::decode);

    @Override
    public CustomPacketPayload.Type<CrewActionMessage> type() {
        return TYPE;
    }

    public static void encode(CrewActionMessage message, RegistryFriendlyByteBuf buffer) {
        buffer.writeByte(message.action);
        buffer.writeInt(message.targetEntityId);
    }

    public static CrewActionMessage decode(RegistryFriendlyByteBuf buffer) {
        return new CrewActionMessage(buffer.readByte(), buffer.readInt());
    }

    public static void handle(CrewActionMessage message, IPayloadContext context) {
    }
}
