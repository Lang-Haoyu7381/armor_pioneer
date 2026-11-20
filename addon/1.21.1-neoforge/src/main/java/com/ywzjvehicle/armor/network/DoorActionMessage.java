package com.ywzjvehicle.armor.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import com.ywzjvehicle.armor.VehicleArmorMod;

import com.ywzjvehicle.armor.OperatedRound;

import net.minecraft.server.level.ServerPlayer;

import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.DoorUnit;

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
public record DoorActionMessage() implements CustomPacketPayload {

    public static final int ID = 8;

    public static final CustomPacketPayload.Type<DoorActionMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    VehicleArmorMod.MOD_ID, "door_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DoorActionMessage> CODEC =
            CustomPacketPayload.codec(DoorActionMessage::encode, DoorActionMessage::decode);

    @Override
    public CustomPacketPayload.Type<DoorActionMessage> type() {
        return TYPE;
    }

    public static void encode(DoorActionMessage message, RegistryFriendlyByteBuf buffer) {
    }

    public static DoorActionMessage decode(RegistryFriendlyByteBuf buffer) {
        return new DoorActionMessage();
    }

    public static void handle(DoorActionMessage message, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }
        if (sender == null) {
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
    }
}
