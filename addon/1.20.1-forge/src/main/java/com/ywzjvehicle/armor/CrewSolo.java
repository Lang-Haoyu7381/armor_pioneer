package com.ywzjvehicle.armor;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.network.message.ClientVehicleAction;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleMultiWeapons;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The commander's single-seat control of the whole vehicle.
 *
 * <p>The base mod gives one crew member the wheel (seat zero's {@code ControlUnit.operator}) and
 * gives every weapon to the seat that mounts it; a lone player controls a tank by switching seats.
 * This mode gives the commander the same practical result without leaving their own seat: the
 * wheel is forced into their hands, their sight lays the selected weapon, and their primary
 * trigger fires that weapon. The commander's own station is not in the selection: the point of
 * the mode is the weapons that seat otherwise cannot reach, and the base mod keeps owning the
 * commander's own gun when the mode is off.
 *
 * <p><b>Selection.</b> {@link #units(AbstractVehicle, PartUnit)} lists every weapon unit that
 * actually carries a weapon except the commander's own station (and its sub-weapons), in the
 * pack's part order. The default is the main gun; the T key cycles the list. The selected unit is
 * aimed once per server tick after the crew's own aim packets, so the commander wins that
 * argument, and the gunner's client follows the server aim for the main gun (the existing slave
 * flag).
 *
 * <p><b>The trigger.</b> The commander's trigger packet is the base mod's packet for their own
 * station. {@link #redirectShot} rewrites it to the selected unit and rebuilds the aim contexts
 * from the server's own pose, exactly as the old single-gun redirect did. The selected weapon's
 * ammunition, reload and cooldown all apply; if it cannot fire, the click stays with the
 * commander's own weapon rather than becoming a silent nothing.
 *
 * <p><b>It survives the seat.</b> The mode is a property of the vehicle, so getting out does not
 * quietly restore the crew split. The wheel is released while nobody is in the commander seat -
 * the vehicle must remain drivable by whoever is aboard - and is taken again when a commander sits
 * down. The mode ends only with the same key or the vehicle's destruction.
 */
public final class CrewSolo {

    private CrewSolo() {
    }

    private static final double AIM_RANGE = 256.0;

    private static final class Mode {
        final ServerLevel level;
        final int vehicleEntityId;
        int selected;

        Mode(ServerLevel level, AbstractVehicle vehicle) {
            this.level = level;
            this.vehicleEntityId = vehicle.m_19879_();
        }
    }

    /** One solo-control state per vehicle. The mode outlives the commander's seat by design. */
    private static final Map<UUID, Mode> MODES = new HashMap<>();

    /** @return whether this vehicle is in the commander's solo-control mode. */
    public static boolean isSolo(AbstractVehicle vehicle) {
        return vehicle != null && MODES.containsKey(vehicle.m_20148_());
    }

    /** @return whether the gunner's client should follow the server aim for the main gun. */
    public static boolean slaved(AbstractVehicle vehicle) {
        Mode mode = vehicle == null ? null : MODES.get(vehicle.m_20148_());
        if (mode == null) {
            return false;
        }
        ServerPlayer commander = commanderAboard(vehicle);
        if (commander == null) {
            return false;
        }
        List<WeaponUnit> units = units(vehicle, vehicle.getOwnOperatorUnit(commander));
        if (mode.selected < 0 || mode.selected >= units.size()) {
            return false;
        }
        return units.get(mode.selected) == CrewRoles.primaryWeaponUnit(vehicle);
    }

    /**
     * Every weapon unit that actually carries a weapon except the commander's own station, in the
     * pack's part order. The commander already has their own weapon; solo control is about the
     * weapons that seat otherwise cannot reach.
     */
    public static List<WeaponUnit> units(AbstractVehicle vehicle, PartUnit<?> commanderStation) {
        List<WeaponUnit> units = new ArrayList<>();
        if (vehicle == null) {
            return units;
        }
        WeaponUnit ownRoot = commanderStation instanceof WeaponUnit own
                ? rootOrSelf(own) : null;
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (!(part instanceof WeaponUnit unit) || unit.getCurrentWeapon().isEmpty()) {
                continue;
            }
            if (ownRoot != null && rootOrSelf(unit) == ownRoot) {
                continue;
            }
            units.add(unit);
        }
        return units;
    }

    private static WeaponUnit rootOrSelf(WeaponUnit unit) {
        WeaponUnit root = unit.getRootParentWeaponUnit();
        return root == null ? unit : root;
    }

    /** The pure selection step, exported for the offline check. */
    public static int nextSelection(int current, int count) {
        return count <= 0 ? 0 : (current + 1) % count;
    }

    /**
     * Turns the mode on or off. On means: the wheel is the commander's and the selected weapon is
     * the one the commander lays and fires. Off returns the vehicle to the ordinary crew split.
     */
    public static boolean toggle(ServerPlayer commander, AbstractVehicle vehicle) {
        if (commander == null || vehicle == null) {
            return false;
        }
        if (!(vehicle.m_9236_() instanceof ServerLevel level)) {
            return false;
        }
        Mode mode = MODES.get(vehicle.m_20148_());
        if (mode != null) {
            MODES.remove(vehicle.m_20148_());
            CrewControl.clearOrder(vehicle);
            CrewDrive.release(vehicle);
            CrewControl.broadcast(vehicle);
            commander.m_5661_(Component.m_237115_(
                    "message.ywzj_vehicle_armor.solo_off"), true);
            VehicleArmorMod.LOGGER.info("[Vehicle Armor] {} left solo control of {}",
                    commander.m_7755_().getString(), vehicle.getVehicleId());
            return true;
        }

        List<WeaponUnit> units = units(vehicle, vehicle.getOwnOperatorUnit(commander));
        if (units.isEmpty()) {
            commander.m_5661_(Component.m_237115_(
                    "message.ywzj_vehicle_armor.solo_none"), true);
            return false;
        }
        mode = new Mode(level, vehicle);
        WeaponUnit main = CrewRoles.primaryWeaponUnit(vehicle);
        mode.selected = Math.max(0, units.indexOf(main));
        MODES.put(vehicle.m_20148_(), mode);
        // The old mark and the legacy override would only argue with the solo aim.
        CrewControl.clearOrder(vehicle);
        CrewDrive.force(vehicle, commander);
        CrewControl.broadcast(vehicle);
        commander.m_5661_(Component.m_237110_("message.ywzj_vehicle_armor.solo_on",
                name(units.get(mode.selected))), true);
        VehicleArmorMod.LOGGER.info("[Vehicle Armor] {} took solo control of {} ({})",
                commander.m_7755_().getString(), vehicle.getVehicleId(),
                units.get(mode.selected).getId());
        return true;
    }

    /** Advances the selected weapon and tells the commander what the trigger now fires. */
    public static boolean cycle(ServerPlayer commander, AbstractVehicle vehicle) {
        Mode mode = vehicle == null ? null : MODES.get(vehicle.m_20148_());
        if (mode == null) {
            commander.m_5661_(Component.m_237115_(
                    "message.ywzj_vehicle_armor.solo_only"), true);
            return false;
        }
        List<WeaponUnit> units = units(vehicle, vehicle.getOwnOperatorUnit(commander));
        if (units.isEmpty()) {
            return false;
        }
        mode.selected = nextSelection(mode.selected, units.size());
        commander.m_5661_(Component.m_237110_("message.ywzj_vehicle_armor.solo_weapon",
                name(units.get(mode.selected))), true);
        return true;
    }

    /**
     * Advances the selected weapon's own multi-weapon slot - the VT-5's five 105 mm rounds, for
     * example. F normally does this for the player's own station; in solo control the commander's
     * own station is the RWS, so the selected unit's slot needs this route.
     */
    public static boolean cycleVariant(ServerPlayer commander, AbstractVehicle vehicle) {
        Mode mode = vehicle == null ? null : MODES.get(vehicle.m_20148_());
        if (mode == null) {
            commander.m_5661_(Component.m_237115_(
                    "message.ywzj_vehicle_armor.solo_only"), true);
            return false;
        }
        List<WeaponUnit> units = units(vehicle, vehicle.getOwnOperatorUnit(commander));
        if (units.isEmpty()) {
            return false;
        }
        if (mode.selected < 0 || mode.selected >= units.size()) {
            mode.selected = 0;
        }
        WeaponUnit selected = units.get(mode.selected);
        AbstractVehicleWeapon<?> current = selected.getCurrentWeapon().orElse(null);
        if (!(current instanceof VehicleMultiWeapons multi) || multi.getSubWeapons().size() <= 1) {
            commander.m_5661_(Component.m_237115_(
                    "message.ywzj_vehicle_armor.solo_single"), true);
            return false;
        }
        multi.cycleSubWeapon(true);
        commander.m_5661_(Component.m_237110_("message.ywzj_vehicle_armor.solo_weapon",
                name(selected)), true);
        return true;
    }

    /** Re-asserts the wheel and lays the selected weapon after the crew's own aim, once a tick. */
    public static void tick(MinecraftServer server) {
        Iterator<Map.Entry<UUID, Mode>> iterator = MODES.entrySet().iterator();
        while (iterator.hasNext()) {
            Mode mode = iterator.next().getValue();
            Entity entity = mode.level.m_6815_(mode.vehicleEntityId);
            if (!(entity instanceof AbstractVehicle vehicle)) {
                // Unloaded, not destroyed: keep the mode for when the chunk comes back.
                continue;
            }
            if (vehicle.isDestroyed()) {
                iterator.remove();
                continue;
            }
            ServerPlayer commander = commanderAboard(vehicle);
            if (commander == null) {
                // Nobody is commanding: the vehicle must stay drivable by the crew that is aboard.
                CrewDrive.release(vehicle);
                continue;
            }
            PartUnit<?> own = vehicle.getOwnOperatorUnit(commander);
            List<WeaponUnit> units = units(vehicle, own);
            if (units.isEmpty()) {
                continue;
            }
            if (mode.selected < 0 || mode.selected >= units.size()) {
                mode.selected = 0;
            }
            WeaponUnit selected = units.get(mode.selected);
            CrewDrive.force(vehicle, commander);
            if (selected == own) {
                // The commander's own station is aimed and fired by the base mod.
                continue;
            }
            Vec3 point = aimPoint(commander, own);
            if (point != null) {
                CrewControl.aim(selected, point);
            }
        }
    }

    /**
     * Rewrites the commander's own trigger to the selected weapon.
     *
     * @return whether the packet was rewritten. False leaves it alone: the commander's own station
     *         fires normally, and so does a selected weapon that cannot shoot right now.
     */
    public static boolean redirectShot(AbstractVehicle vehicle, int partIndex, Player player,
                                       ClientVehicleAction message) {
        Mode mode = vehicle == null || player == null || message == null || !message.shoot
                ? null : MODES.get(vehicle.m_20148_());
        if (mode == null) {
            return false;
        }
        if (partIndex < 0 || partIndex >= vehicle.getPartUnits().size()) {
            return false;
        }
        PartUnit<?> firingPart = vehicle.getPartUnits().get(partIndex);
        if (!(firingPart instanceof WeaponUnit firing)) {
            return false;
        }
        if (vehicle.getOwnOperatorUnit(player) != firingPart
                || CrewRoles.roleOf(vehicle, player) != CrewRole.COMMANDER) {
            return false;
        }
        List<WeaponUnit> units = units(vehicle, vehicle.getOwnOperatorUnit(player));
        if (units.isEmpty()) {
            return false;
        }
        if (mode.selected < 0 || mode.selected >= units.size()) {
            mode.selected = 0;
        }
        WeaponUnit selected = units.get(mode.selected);
        if (selected == firingPart || selected.isDestroyed()) {
            return false;
        }
        AbstractVehicleWeapon<?> weapon = selected.getCurrentWeapon().orElse(null);
        if (weapon == null || !weapon.hasAmmo() || weapon.isCoolingDown()
                || weapon.isReloading()) {
            // The click stays with the commander's own weapon rather than becoming a silent
            // nothing while the selected one is between rounds.
            return false;
        }
        int weaponIndex = selected.indexedWeapons.indexOf(weapon);
        if (weaponIndex < 0) {
            return false;
        }
        List<AimContext> fresh = contexts(selected);
        if (fresh.isEmpty()) {
            return false;
        }
        Vec3 velocity = vehicle.m_20184_();
        Vec3 aimPoint = selected.aimHitPosition();
        List<AimContext> contexts = new ArrayList<>(fresh.size());
        for (AimContext source : fresh) {
            AimContext context = new AimContext();
            context.from = source.from.m_82549_(velocity);
            context.direction = source.direction;
            context.position = aimPoint;
            contexts.add(context);
        }
        message.partUnitIndex = selected.getIndex();
        message.weaponIndex = weaponIndex;
        message.aimContexts = contexts;
        // The base client advances the bolt cursor for ripple weapons; the redirected shot is
        // built on the server, so it has to do the same or every smoke round would leave one tube.
        selected.countFire(fresh.size());
        VehicleArmorMod.LOGGER.debug("[Vehicle Armor] commander fired {} from {}",
                weapon.getData() == null ? "?" : weapon.getData().getWeaponId(),
                vehicle.getVehicleId());
        return true;
    }

    /**
     * Rewrites another crew member's shot from the selected unit to the server's aim.
     *
     * <p>The commander moves the gun; the gun's owner may still be the one pulling the trigger.
     * Their client can be a tick behind, so the server's own contexts replace what they sent, the
     * same rule the old commander order used.
     *
     * @return whether the contexts were rewritten.
     */
    public static boolean rewriteShot(AbstractVehicle vehicle, int partIndex,
                                      List<AimContext> contexts) {
        Mode mode = vehicle == null ? null : MODES.get(vehicle.m_20148_());
        if (mode == null || contexts == null || contexts.isEmpty()) {
            return false;
        }
        ServerPlayer commander = commanderAboard(vehicle);
        if (commander == null) {
            return false;
        }
        List<WeaponUnit> units = units(vehicle, vehicle.getOwnOperatorUnit(commander));
        if (units.isEmpty() || mode.selected < 0 || mode.selected >= units.size()) {
            return false;
        }
        WeaponUnit selected = units.get(mode.selected);
        PartUnit<?> part = vehicle.getPartUnit(partIndex).orElse(null);
        if (part != selected) {
            return false;
        }
        List<AimContext> fresh = contexts(selected);
        if (fresh.isEmpty()) {
            return false;
        }
        Vec3 velocity = vehicle.m_20184_();
        for (int i = 0; i < contexts.size(); i++) {
            AimContext source = fresh.get(Math.min(i, fresh.size() - 1));
            AimContext target = contexts.get(i);
            target.from = source.from.m_82549_(velocity);
            target.direction = source.direction;
        }
        return true;
    }

    private static List<AimContext> contexts(WeaponUnit unit) {
        WeaponUnitData.FiringMode mode = unit.getFiringMode();
        if (mode == WeaponUnitData.FiringMode.RIPPLE) {
            return List.of(unit.aimContext());
        }
        if (mode == WeaponUnitData.FiringMode.SALVO
                || mode == WeaponUnitData.FiringMode.FULL_SALVO) {
            return unit.aimContexts();
        }
        return List.of();
    }

    private static ServerPlayer commanderAboard(AbstractVehicle vehicle) {
        for (Entity passenger : vehicle.m_20197_()) {
            if (passenger instanceof ServerPlayer player
                    && player.m_6084_()
                    && !CrewState.isIncapacitated(player)
                    && CrewRoles.roleOf(vehicle, player) == CrewRole.COMMANDER) {
                return player;
            }
        }
        return null;
    }

    private static Vec3 aimPoint(ServerPlayer commander, PartUnit<?> own) {
        if (own instanceof WeaponUnit weapon) {
            Vec3 hit = weapon.aimHitPosition();
            if (hit != null) {
                return hit;
            }
        }
        return commander.m_146892_()
                .m_82549_(commander.m_20252_(1.0F).m_82490_(AIM_RANGE));
    }

    private static String name(WeaponUnit unit) {
        if (unit == null) {
            return "?";
        }
        return unit.getCurrentWeapon()
                .map(weapon -> weapon.getDisplayName().getString())
                .filter(text -> !text.isBlank())
                .orElse(unit.getId());
    }
}
