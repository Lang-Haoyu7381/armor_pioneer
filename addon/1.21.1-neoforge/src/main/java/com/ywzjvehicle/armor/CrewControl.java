package com.ywzjvehicle.armor;

import net.neoforged.neoforge.network.PacketDistributor;

import com.ywzjvehicle.armor.network.CrewStateMessage;
import com.ywzjvehicle.armor.network.VehicleArmorNetwork;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.network.message.ClientVehicleAction;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The commander's two directions of command over the gunner's weapon: a marked target and a
 * temporary aim override.
 *
 * <p><b>The gunner keeps the trigger while there is a gunner.</b> The base mod decides who may
 * shoot by who owns the weapon seat, and that is still the gunner; what the commander can do here
 * is move the gun - a designated target is re-aimed at every tick, and while the override is held
 * the commander's sight feeds the main gun its line. That is the part of "车长指示、炮手接战" that
 * is a division of labour rather than one player doing both jobs.
 *
 * <p><b>When the gunner's station is empty or its occupant is out of action, the commander can
 * fire too.</b> A tank whose only remaining crew member is the commander otherwise has a loaded
 * main gun nobody may pull the trigger on. In that state the commander's own primary trigger is
 * redirected from their weapon station to the main gun (see {@link #redirectCommanderShot}): the
 * main gun's ammunition, reload and cooldown all still apply, and it fires along the server's own
 * gun line rather than the client's. As soon as an operational gunner is back in the seat, the
 * commander's trigger returns to their own weapon - the gunner never loses the trigger to the
 * commander, only to an empty station.
 *
 * <p><b>Server-side aim, not client-side prediction.</b> The base mod's {@code WeaponUnit.aim}
 * writes the aim angle on the side that calls it; on the server that angle is then carried to the
 * clients by the entity's own sync data. That makes the commander's order survive the gunner moving
 * their mouse, because the order is re-applied at the end of every server tick, after the gunner's
 * aim packet. It also means a client cannot talk itself into an authorised order.
 *
 * <p>The state is keyed by vehicle, not by commander: a tank has one main gun, and two commanders
 * arguing over it would need a tie-break rule nobody asked for. The last order from the seat wins.
 *
 * <p><b>The commander's Left Ctrl is the solo-control switch.</b> {@link CrewSolo} owns the mode:
 * the commander works the wheel and the selected weapon from their own seat, and the mode is a
 * property of the vehicle so it survives them leaving the seat. This class keeps the older marked
 * target and aim-override order (M and the legacy override actions); the solo mode takes
 * precedence over them while it is on.
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
        /**
         * Whether a gunner was able to work the main gun at the last check.
         *
         * <p>Null until the override is first held. Kept so the commander is told once when the
         * gunner's station changes from manned to empty (or back), rather than every tick.
         */
        Boolean gunnerAvailable;

        Order(ServerLevel level, AbstractVehicle vehicle, UUID commander) {
            this.level = level;
            this.vehicle = vehicle.getUUID();
            this.vehicleEntityId = vehicle.getId();
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
        if (!(vehicle.level() instanceof ServerLevel level)) {
            return false;
        }
        Entity target = level.getEntity(targetId);
        if (!(target instanceof AbstractVehicle) || target == vehicle || !target.isAlive()
                || target.distanceToSqr(vehicle) > MAX_DESIGNATION_RANGE * MAX_DESIGNATION_RANGE) {
            commander.displayClientMessage(Component.translatable(
                    "message.ywzj_vehicle_armor.no_target"), true);
            return false;
        }
        // A mark through a hill is not a mark: the gun would be ordered onto a target neither
        // crew member can see, and the only feedback would be a turret that turns away. The
        // client's ray is an entity query and does not know about blocks, so the check that
        // counts is here.
        ClipContext sight = new ClipContext(commander.getEyePosition(),
                target.getBoundingBox().getCenter(), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, commander);
        if (level.clip(sight).getType() != HitResult.Type.MISS) {
            commander.displayClientMessage(Component.translatable(
                    "message.ywzj_vehicle_armor.no_target"), true);
            return false;
        }
        Order order = ORDERS.computeIfAbsent(vehicle.getUUID(),
                key -> new Order(level, vehicle, commander.getUUID()));
        order.targetId = targetId;
        broadcast(vehicle);
        commander.displayClientMessage(Component.translatable(
                "message.ywzj_vehicle_armor.designated", target.getDisplayName()), true);
        VehicleArmorMod.LOGGER.info("[Vehicle Armor] {} designated {} on {}",
                commander.getName().getString(), target.getName().getString(),
                vehicle.getVehicleId());
        return true;
    }

    /** Clears the mark. The override, if one is held, is left alone. */
    public static void clear(ServerPlayer commander, AbstractVehicle vehicle) {
        Order order = ORDERS.get(vehicle.getUUID());
        boolean hadTarget = order != null && order.targetId != 0;
        if (order != null) {
            order.targetId = 0;
            if (!order.override) {
                ORDERS.remove(vehicle.getUUID());
            }
        }
        broadcast(vehicle);
        if (hadTarget) {
            // Pressing M at empty ground is how a mark is cleared; doing it when there was no mark
            // should not claim to have cleared one.
            commander.displayClientMessage(Component.translatable(
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
        WeaponUnit main = CrewRoles.primaryWeaponUnit(vehicle);
        if (on && main == null) {
            commander.displayClientMessage(Component.translatable(
                    "message.ywzj_vehicle_armor.no_main_gun"), true);
            return false;
        }
        if (!(vehicle.level() instanceof ServerLevel)) {
            return false;
        }
        ServerLevel level = (ServerLevel) vehicle.level();
        Order order = ORDERS.get(vehicle.getUUID());
        if (order != null && !order.commander.equals(commander.getUUID())) {
            // A new commander in the seat inherits the vehicle, not the old order: the previous
            // commander's mark and override die with their seat.
            ORDERS.remove(vehicle.getUUID());
            broadcast(vehicle);
            order = null;
        }
        if (order == null) {
            order = new Order(level, vehicle, commander.getUUID());
            ORDERS.put(vehicle.getUUID(), order);
        }
        order.override = on;
        boolean solo = on && !operationalGunner(main, commander);
        if (on) {
            order.gunnerAvailable = !solo;
        }
        if (!on && order.targetId == 0) {
            ORDERS.remove(vehicle.getUUID());
        }
        broadcast(vehicle);
        commander.displayClientMessage(Component.translatable(on
                ? (solo ? "message.ywzj_vehicle_armor.override_solo"
                        : "message.ywzj_vehicle_armor.override_on")
                : "message.ywzj_vehicle_armor.override_off"), true);
        VehicleArmorMod.LOGGER.info("[Vehicle Armor] {} {} the main gun on {}{}",
                commander.getName().getString(), on ? "took over" : "released",
                vehicle.getVehicleId(), solo ? " (no operative gunner - firing too)" : "");
        return true;
    }

    /**
     * Drops this vehicle's marked target / legacy aim order without touching the wheel.
     *
     * <p>The solo-control mode takes the gun over itself, so anything the old order map was doing
     * to that vehicle would only argue with it.
     */
    static void clearOrder(AbstractVehicle vehicle) {
        if (ORDERS.remove(vehicle.getUUID()) != null) {
            broadcast(vehicle);
        }
    }

    /** Drops every order held by a player, on logout or when they stop being the commander. */
    public static void forgetCommander(UUID commanderId) {
        Iterator<Map.Entry<UUID, Order>> iterator = ORDERS.entrySet().iterator();
        while (iterator.hasNext()) {
            Order order = iterator.next().getValue();
            if (order.commander.equals(commanderId)) {
                iterator.remove();
                Entity vehicle = order.level.getEntity(order.vehicleEntityId);
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
        Order order = ORDERS.get(vehicle.getUUID());
        boolean solo = CrewSolo.isSolo(vehicle);
        boolean slaved = CrewSolo.slaved(vehicle)
                || (!solo && order != null && (order.override || order.targetId != 0));
        int target = order == null ? 0 : order.targetId;
        PacketDistributor.sendToPlayer(player,
                new CrewStateMessage(vehicle.getId(), slaved, target,
                        CrewState.isIncapacitated(player), solo));
    }

    static void broadcast(AbstractVehicle vehicle) {
        for (Entity passenger : vehicle.getPassengers()) {
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
            ServerPlayer commander = server.getPlayerList().getPlayer(order.commander);
            Entity ridden = commander == null ? null : commander.getVehicle();
            AbstractVehicle vehicle = ridden instanceof AbstractVehicle
                    ? (AbstractVehicle) ridden : vehicleOf(order);
            if (vehicle == null || !vehicle.getUUID().equals(order.vehicle)
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
            if (order.override) {
                boolean gunnerAvailable = operationalGunner(main, commander);
                if (order.gunnerAvailable != null
                        && order.gunnerAvailable.booleanValue() != gunnerAvailable) {
                    commander.displayClientMessage(Component.translatable(gunnerAvailable
                            ? "message.ywzj_vehicle_armor.override_on"
                            : "message.ywzj_vehicle_armor.override_solo"), true);
                }
                order.gunnerAvailable = gunnerAvailable;
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
                    point = commander.getEyePosition()
                            .add(commander.getViewVector(1.0F).scale(MAX_DESIGNATION_RANGE));
                }
            } else if (order.targetId != 0) {
                Entity target = order.level.getEntity(order.targetId);
                if (target != null && target.isAlive()
                        && target.distanceToSqr(vehicle)
                        <= MAX_DESIGNATION_RANGE * MAX_DESIGNATION_RANGE) {
                    point = target.getBoundingBox().getCenter();
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
        CrewSolo.tick(server);
    }

    private static AbstractVehicle vehicleOf(Order order) {
        Entity entity = order.level.getEntity(order.vehicleEntityId);
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
        if (CrewSolo.rewriteShot(vehicle, partIndex, contexts)) {
            return;
        }
        Order order = ORDERS.get(vehicle.getUUID());
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
        Vec3 velocity = vehicle.getDeltaMovement();
        for (int i = 0; i < contexts.size(); i++) {
            AimContext source = fresh.get(Math.min(i, fresh.size() - 1));
            AimContext target = contexts.get(i);
            // The client adds the vehicle's velocity to the muzzle position; the server's
            // context is in the vehicle's own frame, so it has to be added back here.
            target.from = source.from.add(velocity);
            target.direction = source.direction;
        }
    }

    /**
     * Turns the commander's own trigger into the main gun's when nobody else can work it.
     *
     * <p>The base mod builds a shot from the weapon the player's seat owns and sends it with that
     * part's index. The commander is in their own weapon station, so the packet normally means "fire
     * this machine gun". While the override is held and the primary weapon's station is empty, its
     * occupant is dead, or its occupant is incapacitated, there is nobody left to pull the main
     * gun's trigger; this rewrites the same packet to the main gun instead:
     *
     * <ul>
     *   <li>the part index becomes the main weapon unit's;</li>
     *   <li>the weapon index becomes the main unit's currently selected weapon;</li>
     *   <li>the contexts are rebuilt from the server's own gun pose, so a client cannot aim the
     *       round somewhere the server is not pointing;</li>
     *   <li>the primary trigger only - the commander's secondary weapon keeps firing the secondary
     *       weapon, not the main gun.</li>
     * </ul>
     *
     * <p><b>Only when the main gun can actually shoot.</b> If it is reloading, cooling down, or has
     * no rounds, the packet is left alone and the commander's own weapon fires. That keeps a click
     * from becoming a silent nothing while the main gun is between rounds, and it means the round's
     * ammunition and reload are the main gun's, not the machine gun's.
     *
     * <p>The gunner never loses the trigger to the commander: an operational gunner in the main
     * station always wins, and the commander's trigger stays their own.
     *
     * @return whether the message was rewritten to the main gun.
     */
    public static boolean redirectCommanderShot(AbstractVehicle vehicle, int partIndex,
                                                Player player, ClientVehicleAction message) {
        if (vehicle == null || player == null || message == null || !message.shoot) {
            return false;
        }
        if (CrewSolo.redirectShot(vehicle, partIndex, player, message)) {
            return true;
        }
        if (partIndex < 0 || partIndex >= vehicle.getPartUnits().size()) {
            return false;
        }
        PartUnit<?> firingPart = vehicle.getPartUnits().get(partIndex);
        if (!(firingPart instanceof WeaponUnit firing)) {
            return false;
        }
        // The player must actually be the commander in this station, not a passenger borrowing a
        // seat, and the order must be this commander's own.
        if (vehicle.getOwnOperatorUnit(player) != firingPart
                || CrewRoles.roleOf(vehicle, player) != CrewRole.COMMANDER) {
            return false;
        }
        Order order = ORDERS.get(vehicle.getUUID());
        if (order == null || !order.override
                || !order.commander.equals(player.getUUID())) {
            return false;
        }
        WeaponUnit main = CrewRoles.primaryWeaponUnit(vehicle);
        if (main == null || main == firing || main.isDestroyed()) {
            return false;
        }

        // Primary trigger only: the secondary trigger must keep firing the station's own secondary
        // weapon. The base sends the index of the weapon it fired; it resolves in this unit's list.
        if (message.weaponIndex < 0 || message.weaponIndex >= firing.indexedWeapons.size()) {
            return false;
        }
        AbstractVehicleWeapon<?> trigger = firing.indexedWeapons.get(message.weaponIndex);
        if (!firing.weapons.contains(trigger)) {
            return false;
        }

        AbstractVehicleWeapon<?> selected = main.getCurrentWeapon().orElse(null);
        if (selected == null) {
            return false;
        }
        boolean ready = selected.hasAmmo() && !selected.isCoolingDown()
                && !selected.isReloading();
        boolean gunnerAvailable = operationalGunner(main, player);
        if (!shouldCommanderFire(true, true, gunnerAvailable, !main.isDestroyed(), ready)) {
            return false;
        }

        int weaponIndex = main.indexedWeapons.indexOf(selected);
        if (weaponIndex < 0) {
            return false;
        }
        WeaponUnit fireUnit = selected.getWeaponUnit();
        if (fireUnit == null) {
            return false;
        }
        List<AimContext> fresh = fireUnit.aimContexts();
        if (fresh.isEmpty()) {
            return false;
        }
        Vec3 velocity = vehicle.getDeltaMovement();
        Vec3 aimPoint = fireUnit.aimHitPosition();
        if (aimPoint == null && message.aimContexts != null && !message.aimContexts.isEmpty()) {
            aimPoint = message.aimContexts.get(0).position;
        }
        List<AimContext> contexts = new ArrayList<>(fresh.size());
        for (AimContext source : fresh) {
            AimContext context = new AimContext();
            context.from = source.from.add(velocity);
            context.direction = source.direction;
            context.position = aimPoint;
            contexts.add(context);
        }
        message.partUnitIndex = main.getIndex();
        message.weaponIndex = weaponIndex;
        message.aimContexts = contexts;
        VehicleArmorMod.LOGGER.debug(
                "[Vehicle Armor] commander firing the main gun on {}",
                vehicle.getVehicleId());
        return true;
    }

    /**
     * The one decision the redirect hinges on, kept pure so the offline check can enumerate it.
     *
     * @param overriding whether the commander is holding the aim override
     * @param commanderSeat whether the player is the commander's station
     * @param gunnerOperational whether the main weapon's station has an able occupant
     * @param mainOperational whether the main weapon unit exists and is not destroyed
     * @param mainReady whether the selected main weapon has a round and is not reloading/cooling
     * @return whether the commander's trigger may fire the main gun
     */
    public static boolean shouldCommanderFire(boolean overriding, boolean commanderSeat,
                                              boolean gunnerOperational, boolean mainOperational,
                                              boolean mainReady) {
        return overriding && commanderSeat && !gunnerOperational && mainOperational && mainReady;
    }

    /**
     * @return whether a living, conscious player is operating the primary weapon unit. The
     *         commander is excluded defensively: a seat cannot be both stations, but if a pack ever
     *         puts both on one part the commander must not count as their own gunner.
     */
    private static boolean operationalGunner(WeaponUnit main, LivingEntity commander) {
        if (main == null) {
            return false;
        }
        LivingEntity owner = main.getOwner();
        return owner != null && owner != commander && owner.isAlive()
                && !CrewState.isIncapacitated(owner);
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
    static void aim(WeaponUnit unit, Vec3 point) {
        Vec2 rot = unit.aimRot(point);
        unit.setXAimRot(rot.x - unit.xBarrelSelfRot);
        unit.setYAimRot(rot.y - unit.yBarrelSelfRot);
        unit.updateWorldAimVec();
    }
}
