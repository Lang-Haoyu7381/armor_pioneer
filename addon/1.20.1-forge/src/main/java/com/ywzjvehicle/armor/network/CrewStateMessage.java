package com.ywzjvehicle.armor.network;

import com.ywzjvehicle.armor.CrewClientState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server to client: the commander is (or is no longer) laying this vehicle's main gun, and whether
 * the vehicle is in the commander's solo-control mode.
 *
 * <p>The aim angles themselves already travel in the base mod's own sync data; all that is missing
 * is the one fact the base mod cannot express - that the owner of the weapon should follow them
 * too. Sending the target id alongside is for the readout and costs four bytes; the client does not
 * aim with it, because the server's angles are the authority.
 *
 * <p>The solo flag rides in the same message because it is the same question: the crew's HUD says
 * who is steering the gun and the wheel, and a mode that survived the commander leaving the seat
 * has to be visible to whoever sits down next.
 */
public record CrewStateMessage(int vehicleEntityId, boolean slaved, int targetEntityId,
                               boolean incapacitated, boolean solo) {

    public static final int ID = 7;

    public static void encode(CrewStateMessage message, FriendlyByteBuf buffer) {
        buffer.writeInt(message.vehicleEntityId);
        buffer.writeByte(message.slaved ? 1 : 0);
        buffer.writeInt(message.targetEntityId);
        buffer.writeByte(message.incapacitated ? 1 : 0);
        buffer.writeByte(message.solo ? 1 : 0);
    }

    public static CrewStateMessage decode(FriendlyByteBuf buffer) {
        return new CrewStateMessage(buffer.readInt(), buffer.readByte() != 0,
                buffer.readInt(), buffer.readByte() != 0, buffer.readByte() != 0);
    }

    public static void handle(CrewStateMessage message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        if (!context.getDirection().getReceptionSide().isClient()) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            CrewClientState.set(message.vehicleEntityId, message.slaved);
            CrewClientState.setDesignated(message.targetEntityId);
            CrewClientState.setSolo(message.vehicleEntityId, message.solo);
            // The wound is the recipient's own: the server sends this to each rider with their
            // own flag, and the client's fire handler reads it so the trigger is blocked on the
            // same side the effects are played.
            CrewClientState.setIncapacitated(message.incapacitated);
        });
        context.setPacketHandled(true);
    }
}
