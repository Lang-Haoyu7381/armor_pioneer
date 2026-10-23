package com.ywzjvehicle.armor;

import com.ywzjvehicle.armor.network.CrewStateMessage;
import com.ywzjvehicle.armor.network.VehicleArmorNetwork;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The commander's two directions of command over the gunner's weapon: a marked target and a
 * temporary aim override.
 *
 * <p><b>Neither of them fires.</b> The base mod decides who may shoot by who owns the weapon seat,
 * and that is still the gunner. What the commander can do here is move the gun: a designated target
 * is re-aimed at every tick, and while the override is held the commander's sight feeds the main
 * gun its line. The gunner keeps the trigger, which is the part of "车长指示、炮手接战" that is a
 * division of labour rather than one player doing both jobs.
 *
 * <p><b>Server-side aim, not client-side prediction.</b> The base mod's {@code WeaponUnit.aim}
 * writes the aim angle on the side that calls it; on the server that angle is then carried to the
 * clients by the entity's own sync data. That makes the commander's order survive the gunner moving
 * their mouse, because the order is re-applied at the end of every server tick, after the gunner's
 * aim packet. It also means a client cannot talk itself into an authorised order.
 *
 * <p>The state is keyed by vehicle, not by commander: a tank has one main gun, and two commanders
 * arguing over it would need a tie-break rule nobody asked for. The last order from the seat wins.
 */
public final class CrewControl {

    private CrewControl() {
    }

    private static final double MAX_DESIGNATION_RANGE = 256.0;

    private static final class Order {
        final ServerLevel level;
        final UUID vehicle;
        final int vehicleEntityId;
        final UUID commander;
        int targetId;
        boolean override;

        Order(ServerLevel level, AbstractVehicle vehicle, UUID commander) {
            this.level = level;
            this.vehicle = vehicle.m_20148_();
            this.vehicleEntityId = vehicle.m_19879_();
            this.commander = commander;
        }
    }

    /** One order per vehicle. */
    private static final Map<UUID, Order> ORDERS = new HashMap<>();

    /**
     * Marks the entity under the commander's crosshair, or clears the mark when they designate
     * nothing.
     *
     * @return whether the order was accepted.
     */
    public static boolean designate(ServerPlayer commander, AbstractVehicle vehicle, int targetId) {
        if (targetId == 0) {
            clear(commander, vehicle);
            return true;
        }
        if (!(vehicle.m_9236_() instanceof ServerLevel level)) {
            return false;
        }
        Entity target = level.m_6815_(targetId);
        if (!(target instanceof AbstractVehicle) || target == vehicle || !target.m_6084_()
                || target.m_20280_(vehicle) > MAX_DESIGNATION_RANGE * MAX_DESIGNATION_RANGE) {
            commander.m_5661_(Component.m_237115_(
                    "message.ywzj_vehicle_armor.no_target"), true);
            return false;
        }
        // A mark through a hill is not a mark: the gun would be ordered onto a target neither
        // crew member can see, and the only feedback would be a turret that turns away. The
        // client's ray is an entity query and does not know about blocks, so the check that
        // counts is here.
        ClipContext sight = new ClipContext(commander.m_146892_(),
                target.m_20191_().m_82399_(), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, commander);
        if (level.m_45547_(sight).m_6662_() != HitResult.Type.MISS) {
            commander.m_5661_(Component.m_237115_(
                    "message.ywzj_vehicle_armor.no_target"), true);
            return false;
        }
        Order order = ORDERS.computeIfAbsent(vehicle.m_20148_(),
                key -> new Order(level, vehicle, commander.m_20148_()));
        order.targetId = targetId;
        broadcast(vehicle);
        commander.m_5661_(Component.m_237110_(
                "message.ywzj_vehicle_armor.designated", target.m_5446_()), true);
        VehicleArmorMod.LOGGER.info("[Vehicle Armor] {} designated {} on {}",
                commander.m_7755_().getString(), target.m_7755_().getString(),
                vehicle.getVehicleId());
        return true;
    }

    /** Clears the mark. The override, if one is held, is left alone. */
    public static void clear(ServerPlayer commander, AbstractVehicle vehicle) {
        Order order = ORDERS.get(vehicle.m_20148_());
        boolean hadTarget = order != null && order.targetId != 0;
        if (order != null) {
            order.targetId = 0;
            if (!order.override) {
                ORDERS.remove(vehicle.m_20148_());
            }
        }
        broadcast(vehicle);
        if (hadTarget) {
            // Pressing M at empty ground is how a mark is cleared; doing it when there was no mark
            // should not claim to have cleared one.
            commander.m_5661_(Component.m_237115_(
                    "message.ywzj_vehicle_armor.designation_cleared"), true);
        }
    }

    /**
     * Starts or stops feeding the commander's line of sight into the main gun.
     *
     * <p>Starting is refused when the vehicle has no main gun at all - the alternative is a key
     * that says it worked and a turret that has nowhere to turn.
     */
    public static boolean setOverride(ServerPlayer commander, AbstractVehicle vehicle, boolean on) {
        if (on && CrewRoles.primaryWeaponUnit(vehicle) == null) {
            commander.m_5661_(Component.m_237115_(
                    "message.ywzj_vehicle_armor.no_main_gun"), true);
            return false;
        }
        if (!(vehicle.m_9236_() instanceof ServerLevel)) {
            return false;
        }
        ServerLevel level = (ServerLevel) vehicle.m_9236_();
        Order order = ORDERS.get(vehicle.m_20148_());
        if (order == null) {
            order = new Order(level, vehicle, commander.m_20148_());
            ORDERS.put(vehicle.m_20148_(), order);
        }
        order.override = on;
        if (!on && order.targetId == 0) {
            ORDERS.remove(vehicle.m_20148_());
        }
        broadcast(vehicle);
        commander.m_5661_(Component.m_237115_(on
                ? "message.ywzj_vehicle_armor.override_on"
                : "message.ywzj_vehicle_armor.override_off"), true);
        VehicleArmorMod.LOGGER.info("[Vehicle Armor] {} {} the main gun on {}",
                commander.m_7755_().getString(), on ? "took over" : "released",
                vehicle.getVehicleId());
        return true;
    }

    /** Drops every order held by a player, on logout or when they stop being the commander. */
    public static void forgetCommander(UUID commanderId) {
        Iterator<Map.Entry<UUID, Order>> iterator = ORDERS.entrySet().iterator();
        while (iterator.hasNext()) {
            Order order = iterator.next().getValue();
            if (order.commander.equals(commanderId)) {
                iterator.remove();
                Entity vehicle = order.level.m_6815_(order.vehicleEntityId);
                if (vehicle instanceof AbstractVehicle) {
                    broadcast((AbstractVehicle) vehicle);
                }
            }
        }
    }

    /**
     * Sends one player this vehicle's current state.
     *
     * <p>Called when a role changes as well as when an order does: a gunner who climbs into the
     * seat while the commander is already laying the gun has to learn that, or their scope would
     * be the one thing on the vehicle that disagrees with where the gun is pointing.
     */
    public static void sendState(ServerPlayer player, AbstractVehicle vehicle) {
        if (player == null || vehicle == null) {
            return;
        }
        Order order = ORDERS.get(vehicle.m_20148_());
        boolean slaved = order != null && (order.override || order.targetId != 0);
        int target = order == null ? 0 : order.targetId;
        VehicleArmorNetwork.channel().send(PacketDistributor.PLAYER.with(() -> player),
                new CrewStateMessage(vehicle.m_19879_(), slaved, target,
                        CrewState.isIncapacitated(player)));
    }

    private static void broadcast(AbstractVehicle vehicle) {
        for (Entity passenger : vehicle.m_20197_()) {
            if (passenger instanceof ServerPlayer player) {
                sendState(player, vehicle);
            }
        }
    }

    /** Applies every live order, once per server tick, after the base mod has taken the crew's aim. */
    public static void tick(MinecraftServer server) {
        Iterator<Map.Entry<UUID, Order>> iterator = ORDERS.entrySet().iterator();
        while (iterator.hasNext()) {
            Order order = iterator.next().getValue();
            ServerPlayer commander = server.m_6846_().m_11259_(order.commander);
            Entity ridden = commander == null ? null : commander.m_20202_();
            AbstractVehicle vehicle = ridden instanceof AbstractVehicle
                    ? (AbstractVehicle) ridden : vehicleOf(order);
            if (vehicle == null || !vehicle.m_20148_().equals(order.vehicle)
                    || commander == null
                    || CrewRoles.roleOf(vehicle, commander) != CrewRole.COMMANDER
                    || CrewState.isIncapacitated(commander)) {
                iterator.remove();
                if (vehicle != null) {
                    broadcast(vehicle);
                }
                continue;
            }
            WeaponUnit main = CrewRoles.primaryWeaponUnit(vehicle);
            if (main == null || main.isDestroyed()) {
                iterator.remove();
                broadcast(vehicle);
                continue;
            }

            Vec3 point = null;
            if (order.override) {
                PartUnit<?> seat = vehicle.getOwnOperatorUnit(commander);
                if (seat instanceof WeaponUnit && seat != main) {
                    // The commander's own sight already knows where it is looking, barrel offset
                    // and all; there is no reason to reconstruct that from mouse angles.
                    point = ((WeaponUnit) seat).aimHitPosition();
                } else {
                    // No weapon in the commander's seat: use the player's look vector instead.
                    point = commander.m_146892_()
                            .m_82549_(commander.m_20252_(1.0F).m_82490_(MAX_DESIGNATION_RANGE));
                }
            } else if (order.targetId != 0) {
                Entity target = order.level.m_6815_(order.targetId);
                if (target != null && target.m_6084_()
                        && target.m_20280_(vehicle)
                        <= MAX_DESIGNATION_RANGE * MAX_DESIGNATION_RANGE) {
                    point = target.m_20191_().m_82399_();
                } else {
                    order.targetId = 0;
                }
            }

            if (point != null) {
                aim(main, point);
            }
            if (!order.override && order.targetId == 0) {
                iterator.remove();
                broadcast(vehicle);
            }
        }
    }

    private static AbstractVehicle vehicleOf(Order order) {
        Entity entity = order.level.m_6815_(order.vehicleEntityId);
        return entity instanceof AbstractVehicle ? (AbstractVehicle) entity : null;
    }

    /**
     * Rewrites the shot's aim contexts to the server's own line while an order is active.
     *
     * <p>The client normally sends where its muzzle is and where it points, and the server trusts
     * both. While the commander is laying the gun the server has moved the gun, and the client's
     * copy follows through the remote aim - but a client that is a tick behind, or one that moved
     * its mouse in the same tick, must not get to send the round somewhere else. The server's own
     * {@code aimContexts()} is the gun's actual pose, so that is what goes out.
     *
     * <p>Only the root main gun's shots are touched. A machine-gun burst from the commander's own
     * mount, or any other weapon, keeps the base mod's contexts untouched.
     */
    public static void rewriteShot(AbstractVehicle vehicle, int partIndex,
                                   List<AimContext> contexts) {
        if (vehicle == null || contexts == null || contexts.isEmpty()) {
            return;
        }
        Order order = ORDERS.get(vehicle.m_20148_());
        if (order == null || (!order.override && order.targetId == 0)) {
            return;
        }
        WeaponUnit main = CrewRoles.primaryWeaponUnit(vehicle);
        if (main == null) {
            return;
        }
        PartUnit<?> part = vehicle.getPartUnit(partIndex).orElse(null);
        if (!(part instanceof WeaponUnit) || ((WeaponUnit) part).getRootParentWeaponUnit() != main) {
            return;
        }
        List<AimContext> fresh = ((WeaponUnit) part).aimContexts();
        if (fresh.isEmpty()) {
            return;
        }
        Vec3 velocity = vehicle.m_20184_();
        for (int i = 0; i < contexts.size(); i++) {
            AimContext source = fresh.get(Math.min(i, fresh.size() - 1));
            AimContext target = contexts.get(i);
            // The client adds the vehicle's velocity to the muzzle position; the server's
            // context is in the vehicle's own frame, so it has to be added back here.
            target.from = source.from.m_82549_(velocity);
            target.direction = source.direction;
        }
    }

    /**
     * Points a weapon unit at a world position.
     *
     * <p>This is the arithmetic of {@code WeaponUnit.aim}, spelled out rather than called because
     * {@code aim} is allowed to do nothing when the unit is not aim-driven, and a main gun that
     * silently declines to follow the commander is exactly the kind of "the key did nothing" this
     * addon keeps a log line to avoid. The barrel's own fixed elevation is removed here, because
     * the aim angles are relative to the mount rather than to the bore.
     */
    private static void aim(WeaponUnit unit, Vec3 point) {
        Vec2 rot = unit.aimRot(point);
        unit.setXAimRot(rot.f_82470_ - unit.xBarrelSelfRot);
        unit.setYAimRot(rot.f_82471_ - unit.yBarrelSelfRot);
        unit.updateWorldAimVec();
    }
}
