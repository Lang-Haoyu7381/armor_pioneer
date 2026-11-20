package com.ywzjvehicle.armor;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * The commander's driving takeover: a toggle that puts the vehicle's controls in the commander's
 * hands, so a solo commander can drive and fight from the same seat.
 *
 * <p>The base mod's movement input has exactly one owner: {@code ControlUnit.getOperator()}.
 * Every driving packet is checked against that player on the server, so the takeover is not a
 * second input channel - it moves the operator to the commander. The commander's own client then
 * sends the same WASD/space input it would send from the driver's seat, through the base mod's own
 * input path, with the base mod's own key bindings and camera handling. Nothing is re-implemented
 * here, and a modified client cannot drive with a packet from a seat the server did not hand it.
 *
 * <p><b>It is a command takeover, not a request.</b> The commander can take the wheel while a
 * driver is sitting in seat zero; the driver's packets are refused from that moment, because the
 * server has moved the single control owner. Everyone aboard is told that the wheel changed hands.
 * Releasing the takeover gives it back to whoever owns seat zero, if that player is still there and
 * able to use it.
 *
 * <p>Taking the driver's place also starts the engine if it was off, exactly as sitting in seat
 * zero does. Releasing the takeover never stops it; the engine belongs to the vehicle, not to the
 * seat.
 */
public final class CrewDrive {

    private CrewDrive() {
    }

    private static final class Order {
        final ServerLevel level;
        final UUID vehicle;
        final int vehicleEntityId;
        final UUID commander;

        Order(ServerLevel level, AbstractVehicle vehicle, ServerPlayer commander) {
            this.level = level;
            this.vehicle = vehicle.getUUID();
            this.vehicleEntityId = vehicle.getId();
            this.commander = commander.getUUID();
        }
    }

    /** One driving takeover per vehicle. Two commanders arguing over the wheel is not a feature. */
    private static final Map<UUID, Order> DRIVES = new HashMap<>();

    /**
     * Flips the driving takeover for this commander's vehicle.
     *
     * <p>Answered on the server: the client cannot know whether the seat it saw was still
     * occupied when the packet arrived, and a local latch would eventually send the opposite of
     * what the player expects.
     */
    public static boolean toggle(ServerPlayer commander, AbstractVehicle vehicle) {
        if (commander == null || vehicle == null || vehicle.isDestroyed()) {
            return false;
        }
        if (!(vehicle.level() instanceof ServerLevel level)) {
            return false;
        }
        Order existing = DRIVES.get(vehicle.getUUID());
        if (existing != null && existing.commander.equals(commander.getUUID())) {
            stop(vehicle, "message.ywzj_vehicle_armor.drive_off");
            return true;
        }
        if (existing != null) {
            // Another commander's takeover: hand the wheel back before taking it.
            restore(vehicle);
            DRIVES.remove(vehicle.getUUID());
        }
        vehicle.controlUnit.reset();
        vehicle.controlUnit.setOperator(commander);
        if (!vehicle.isEngineOn()) {
            vehicle.toggleEngine(true);
        }
        DRIVES.put(vehicle.getUUID(), new Order(level, vehicle, commander));
        announce(vehicle, "message.ywzj_vehicle_armor.drive_on");
        VehicleArmorMod.LOGGER.info("[Vehicle Armor] {} took the wheel of {}",
                commander.getName().getString(), vehicle.getVehicleId());
        return true;
    }

    /**
     * Puts the wheel in this commander's hands without announcing it, for the solo-control mode.
     *
     * <p>The mode is toggled once and then re-asserted every server tick; a line per tick would be
     * noise. The ordinary takeover bookkeeping is reused so the existing tick still cleans it up
     * when the commander dismounts or stops being the commander.
     */
    public static void force(AbstractVehicle vehicle, ServerPlayer commander) {
        if (vehicle == null || commander == null || vehicle.isDestroyed()) {
            return;
        }
        if (!(vehicle.level() instanceof ServerLevel level)) {
            return;
        }
        Order existing = DRIVES.get(vehicle.getUUID());
        if (existing != null && existing.commander.equals(commander.getUUID())) {
            if (vehicle.controlUnit.getOperator() != commander) {
                vehicle.controlUnit.reset();
                vehicle.controlUnit.setOperator(commander);
            }
            return;
        }
        if (existing != null) {
            restore(vehicle);
            DRIVES.remove(vehicle.getUUID());
        }
        vehicle.controlUnit.reset();
        vehicle.controlUnit.setOperator(commander);
        if (!vehicle.isEngineOn()) {
            vehicle.toggleEngine(true);
        }
        DRIVES.put(vehicle.getUUID(), new Order(level, vehicle, commander));
    }

    /** @return whether this commander is the one holding this vehicle's wheel. */
    public static boolean holds(AbstractVehicle vehicle, UUID commander) {
        if (vehicle == null) {
            return false;
        }
        Order order = DRIVES.get(vehicle.getUUID());
        return order != null && order.commander.equals(commander);
    }

    /**
     * Ends any driving takeover on a vehicle and gives the wheel back.
     *
     * <p>The commander's vanilla-control switch uses this: switching the addon off has to return
     * the wheel in the same press, not on the next packet or after the commander is out of the
     * seat. Announcing it keeps the crew from having to guess who is driving.
     *
     * @return whether a takeover was actually held.
     */
    public static boolean release(AbstractVehicle vehicle) {
        if (vehicle == null || DRIVES.remove(vehicle.getUUID()) == null) {
            return false;
        }
        restore(vehicle);
        announce(vehicle, "message.ywzj_vehicle_armor.drive_off");
        return true;
    }

    /** Re-asserts every live takeover, and ends it when the reason for it has gone away. */
    public static void tick(MinecraftServer server) {
        Iterator<Map.Entry<UUID, Order>> iterator = DRIVES.entrySet().iterator();
        while (iterator.hasNext()) {
            Order order = iterator.next().getValue();
            ServerPlayer commander = server.getPlayerList().getPlayer(order.commander);
            Entity entity = order.level.getEntity(order.vehicleEntityId);
            if (!(entity instanceof AbstractVehicle vehicle)) {
                iterator.remove();
                continue;
            }
            boolean operational = commander != null
                    && commander.getVehicle() == vehicle
                    && CrewRoles.roleOf(vehicle, commander) == CrewRole.COMMANDER
                    && commander.isAlive()
                    && !CrewState.isIncapacitated(commander);
            if (!operational || vehicle.isDestroyed()) {
                iterator.remove();
                restore(vehicle);
                announce(vehicle, "message.ywzj_vehicle_armor.drive_off");
                continue;
            }
            if (vehicle.controlUnit.getOperator() != commander) {
                // Seat zero changing hands, a driver entering, or the base re-assigning the
                // operator must not leave the commander's controls running on another player's
                // last packet.
                vehicle.controlUnit.reset();
                vehicle.controlUnit.setOperator(commander);
            }
        }
    }

    /** Hands back every takeover held by a player, on logout or when they stop being commander. */
    public static void forgetCommander(UUID commanderId) {
        Iterator<Map.Entry<UUID, Order>> iterator = DRIVES.entrySet().iterator();
        while (iterator.hasNext()) {
            Order order = iterator.next().getValue();
            if (!order.commander.equals(commanderId)) {
                continue;
            }
            iterator.remove();
            Entity entity = order.level.getEntity(order.vehicleEntityId);
            if (entity instanceof AbstractVehicle vehicle) {
                restore(vehicle);
                announce(vehicle, "message.ywzj_vehicle_armor.drive_off");
            }
        }
    }

    // --- seats and control -------------------------------------------------------------------

    private static LivingEntity seatZeroOwner(AbstractVehicle vehicle) {
        for (AbstractVehicle.Seat seat : vehicle.seats) {
            if (seat.seatIndex == 0) {
                return seat.partUnit.getOwner();
            }
        }
        return null;
    }

    /** Stops the vehicle's inputs before changing hands so a stale throttle cannot run on. */
    private static void restore(AbstractVehicle vehicle) {
        if (vehicle == null) {
            return;
        }
        vehicle.controlUnit.reset();
        LivingEntity driver = seatZeroOwner(vehicle);
        vehicle.controlUnit.setOperator(driver != null && driver.isAlive()
                && !CrewState.isIncapacitated(driver) ? driver : null);
    }

    private static void stop(AbstractVehicle vehicle, String key) {
        DRIVES.remove(vehicle.getUUID());
        restore(vehicle);
        announce(vehicle, key);
    }

    /** Everyone aboard hears the wheel change hands, not just the commander. */
    private static void announce(AbstractVehicle vehicle, String key) {
        if (vehicle == null) {
            return;
        }
        Component message = Component.translatable(key);
        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof ServerPlayer player) {
                player.displayClientMessage(message, true);
            }
        }
    }
}
