package com.ywzjvehicle.armor.network;

import com.ywzjvehicle.armor.OperatedRound;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.DoorUnit;

import java.util.function.Supplier;

/**
 * Client to server: the crew pressed the door key.
 *
 * <p>The server picks the door from its own seat table, through the base mod's
 * {@code getNearestDoorUnit}: the door linked to the sender's seat if there is
 * one, otherwise the nearest door. That is the same lookup the base mod uses
 * when dismounting, so a hatch on top of a tank - which a right-click ray
 * cannot reach through the turret hitbox - can still be opened by its crew.
 *
 * <p>The door state itself is a base-mod {@code SwitchableUnit} sync field;
 * this message only flips it. No reply is needed.
 */
public record DoorActionMessage() {

    public static final int ID = 8;

    public static void encode(DoorActionMessage message, FriendlyByteBuf buffer) {
    }

    public static DoorActionMessage decode(FriendlyByteBuf buffer) {
        return new DoorActionMessage();
    }

    public static void handle(DoorActionMessage message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer sender = context.getSender();
        if (sender == null) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            AbstractVehicle vehicle = OperatedRound.vehicleOf(sender);
            if (vehicle == null) {
                return;
            }
            DoorUnit door = vehicle.getNearestDoorUnit(sender);
            if (door != null) {
                door.setOn(!door.isOn());
            }
        });
        context.setPacketHandled(true);
    }
}
