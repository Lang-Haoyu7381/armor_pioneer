package com.ywzjvehicle.armor.network;

import com.ywzjvehicle.armor.CrewControl;
import com.ywzjvehicle.armor.CrewState;
import com.ywzjvehicle.armor.OperatedRound;
import com.ywzjvehicle.armor.VehicleArmorMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Client to server: a crew member pressed a commander's key.
 *
 * <p>Only the intent travels. Which vehicle, which seat and whether the sender is the commander are
 * all answered on the server, from its own seat table - the client is allowed to be a tick behind
 * about any of them, and a client-side gate that fails closed is the exact failure this addon has
 * already paid for twice on the gunner's keys.
 *
 * <p>The target is an entity id rather than a position: a tank moves, and an order that chased the
 * coordinates where it used to be would be a different feature with a different name.
 */
public record CrewActionMessage(int action, int targetEntityId) {

    public static final int ID = 6;

    /** Mark the entity under the crosshair; a target id of zero clears the mark instead. */
    public static final int DESIGNATE = 0;

    /** Clear the mark. */
    public static final int CLEAR = 1;

    /** Hold: the main gun follows the commander's sight until the key is released. */
    public static final int OVERRIDE_START = 2;

    public static final int OVERRIDE_STOP = 3;

    /** Minimum gap between "that key did nothing" log lines, per player. */
    private static final long REJECT_INTERVAL_MILLIS = 5000L;

    private static final Map<UUID, Long> lastReject = new ConcurrentHashMap<>();

    public static void encode(CrewActionMessage message, FriendlyByteBuf buffer) {
        buffer.writeByte(message.action);
        // An entity id is an int and can exceed a byte; writeInt comes from netty and keeps its
        // name in the SRG jar this addon compiles against.
        buffer.writeInt(message.targetEntityId);
    }

    public static CrewActionMessage decode(FriendlyByteBuf buffer) {
        return new CrewActionMessage(buffer.readByte(), buffer.readInt());
    }

    public static void handle(CrewActionMessage message, Supplier<NetworkEvent.Context> supplier) {
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
            // Both checks speak to the player: a passenger pressing the commander's key should be
            // told why nothing happened, and a wounded commander should be told that too.
            if (!CrewState.requireCommander(sender, vehicle)) {
                reject(sender, "not the commander, or out of action");
                return;
            }
            switch (message.action) {
                case DESIGNATE -> CrewControl.designate(sender, vehicle, message.targetEntityId);
                case CLEAR -> CrewControl.clear(sender, vehicle);
                case OVERRIDE_START -> CrewControl.setOverride(sender, vehicle, true);
                case OVERRIDE_STOP -> CrewControl.setOverride(sender, vehicle, false);
                default -> reject(sender, "unknown crew action " + message.action);
            }
        });
        context.setPacketHandled(true);
    }

    private static void reject(ServerPlayer sender, String why) {
        long now = System.currentTimeMillis();
        Long last = lastReject.get(sender.m_20148_());
        if (last != null && now - last < REJECT_INTERVAL_MILLIS) {
            return;
        }
        lastReject.put(sender.m_20148_(), now);
        VehicleArmorMod.LOGGER.info("[Vehicle Armor] crew action from {} ignored: {}",
                sender.m_7755_().getString(), why);
    }
}
