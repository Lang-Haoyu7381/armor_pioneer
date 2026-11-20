package com.ywzjvehicle.armor.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * A legacy commander-action packet, kept only so an older client's message is consumed harmlessly.
 *
 * <p>The addon no longer overrides the base mod's control: seat zero's occupant drives and every
 * seat operates its own weapon, with the base mod's own scroll-wheel weapon switch. The packet id
 * and layout are unchanged so a mixed old/new client does not fail at the channel; the server
 * simply ignores the intent.
 */
public record CrewActionMessage(int action, int targetEntityId) {

    public static final int ID = 6;

    public static void encode(CrewActionMessage message, FriendlyByteBuf buffer) {
        buffer.writeByte(message.action);
        buffer.writeInt(message.targetEntityId);
    }

    public static CrewActionMessage decode(FriendlyByteBuf buffer) {
        return new CrewActionMessage(buffer.readByte(), buffer.readInt());
    }

    public static void handle(CrewActionMessage message, Supplier<NetworkEvent.Context> supplier) {
        supplier.get().setPacketHandled(true);
    }
}
