package com.ywzjvehicle.armor;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The crew's own state: who is doing what, and who is too hurt to do it for a while.
 *
 * <p>Three things live here because they are all questions about a person rather than about a
 * vehicle:
 * <ul>
 *   <li>the role they are filling, announced when they sit somewhere new;</li>
 *   <li>the wounds a penetration left, which are what stop a gunner from firing or a driver from
 *       steering for a few seconds;</li>
 *   <li>the driver's control lock, implemented by taking the control unit's operator away and
 *       putting it back. The base mod already refuses movement packets from anyone who is not that
 *       operator, so this needs no mixin and cannot half-work.</li>
 * </ul>
 *
 * <p>A player's wounds are tied to the vehicle they were in. Being ejected - by dying, by leaving,
 * or by the vehicle being wrecked - clears them, because "wounded" is not a debuff to carry across
 * a respawn; the point is that this crew is having a bad time in this tank.
 *
 * <p>Nothing here decides <em>whether</em> a hit penetrates; {@link ArmorSystem} does that and calls
 * {@link #onPenetration}. This class only answers what it costs the people inside.
 */
public final class CrewState {

    private CrewState() {
    }

    /** How long a severe wound keeps a crew member out of their job, in ticks. */
    private static final int MIN_INCAPACITATED_TICKS = 80;

    private static final int MAX_INCAPACITATED_TICKS = 200;

    /** One crew member's active wound. */
    private static final class Wound {
        long until;
        UUID vehicle;
        boolean driverLock;
    }

    private static final Map<UUID, Wound> WOUNDS = new HashMap<>();

    /** Last role announced per player, so a seat change is reported once rather than every tick. */
    private static final Map<UUID, CrewRole> LAST_ROLE = new HashMap<>();

    /** The vehicle they were in, so climbing into a second tank re-sends the commander's state. */
    private static final Map<UUID, UUID> LAST_VEHICLE = new HashMap<>();

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        long now = now(server);

        expireWounds(server, now);
        announceRoles(server);
        CrewControl.tick(server);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() != null) {
            UUID id = event.getEntity().m_20148_();
            WOUNDS.remove(id);
            LAST_ROLE.remove(id);
            LAST_VEHICLE.remove(id);
            CrewControl.forgetCommander(id);
        }
    }

    // --- roles -------------------------------------------------------------------------------

    /**
     * Announces the role whenever it changes.
     *
     * <p>Announcing on entry is the whole point: a player who sits in the commander's seat should
     * not have to guess whether they are the gunner or the commander. Comparing against the last
     * announced role also makes a seat switch - rather than a re-login - the thing that produces
     * the line.
     */
    private static void announceRoles(MinecraftServer server) {
        for (ServerPlayer player : server.m_6846_().m_11314_()) {
            if (!(player.m_20202_() instanceof AbstractVehicle vehicle)) {
                LAST_ROLE.remove(player.m_20148_());
                LAST_VEHICLE.remove(player.m_20148_());
                continue;
            }
            UUID id = player.m_20148_();
            UUID previousVehicle = LAST_VEHICLE.put(id, vehicle.m_20148_());
            CrewRole role = CrewRoles.roleOf(vehicle, player);
            CrewRole previous = LAST_ROLE.put(id, role);
            if (previous != role) {
                player.m_5661_(Component.m_237110_("message.ywzj_vehicle_armor.role",
                        Component.m_237110_(role.key())), true);
            }
            // A new vehicle needs the commander's state even if the role is the same: the gunner
            // may have climbed into a tank whose gun is already slaved, and their scope has to
            // know before the next shot.
            if (previous != role || !vehicle.m_20148_().equals(previousVehicle)) {
                CrewControl.sendState(player, vehicle);
            }
        }
    }

    // --- wounds ------------------------------------------------------------------------------

    /**
     * Turns one penetrating hit into damage to the people in the compartment it went through.
     *
     * <p><b>Who is hit.</b> If the round struck a part unit whose owner is riding, that person is
     * hit: a shot into the commander's mount should not be resolved against a random crewman. For
     * hull structure and parts with no owner, the compartment's crew is used, and the vehicle's id
     * picks one of them so the same seat is not always the unlucky one - and so two servers with the
     * same hit see the same result.
     *
     * <p><b>How much.</b> A main gun's penetration hurts; an autocannon's or a rifle bullet's
     * usually does not, because the scale follows the bore. The damage figure is included as well
     * as the residual: a high-explosive round that got through a roof has almost no residual but a
     * great deal of burst inside, and the crew should feel that.
     */
    public static void onPenetration(AbstractVehicle vehicle, ArmorConfig.VehicleArmor armor,
                                     boolean turret, PartUnit<?> struck, float residual,
                                     float damage, float caliber) {
        if (vehicle == null || vehicle.m_9236_().m_5776_()) {
            return;
        }
        float amount = crewDamage(residual, damage, caliber);
        if (amount <= 0.0F) {
            return;
        }
        LivingEntity victim = victimOf(vehicle, armor, turret, struck, residual);
        if (victim == null) {
            return;
        }

        victim.m_6469_(victim.m_269291_().m_269354_(), amount);
        if (!(victim instanceof ServerPlayer player)) {
            return;
        }
        long now = vehicle.m_9236_().m_46467_();
        boolean severe = shouldIncapacitate(caliber, amount);
        if (severe) {
            Wound wound = WOUNDS.computeIfAbsent(player.m_20148_(), key -> new Wound());
            wound.until = Math.max(wound.until, now + incapacitationTicks(amount));
            wound.vehicle = vehicle.m_20148_();
            if (CrewRoles.roleOf(vehicle, player) == CrewRole.DRIVER
                    && vehicle.getDriver() == player) {
                vehicle.controlUnit.setOperator(null);
                vehicle.controlUnit.reset();
                wound.driverLock = true;
            }
            player.m_5661_(Component.m_237115_(
                    "message.ywzj_vehicle_armor.crew_incapacitated"), true);
            // The client-side trigger check needs to know before the next shot; the server-side
            // mixin is the authority, but the client should not play the effects of a shot that
            // will be refused.
            CrewControl.sendState(player, vehicle);
        } else if (caliber >= 50.0F) {
            player.m_5661_(Component.m_237115_(
                    "message.ywzj_vehicle_armor.crew_hit"), true);
        }
        if (caliber >= 50.0F || severe) {
            // Autocannon and machine-gun hits are left out of the log: a roof raked by a 30mm
            // would otherwise write a line per round, and the point of the line is the tank shell.
            VehicleArmorMod.LOGGER.info(
                    "[Vehicle Armor] {} hit the {} crew: {} took {} damage{}",
                    vehicle.getVehicleId(), turret ? "turret" : "hull",
                    player.m_7755_().getString(),
                    String.format(java.util.Locale.ROOT, "%.1f", amount),
                    severe ? " and is out of action" : "");
        }
    }

    /** @return the amount of player damage a penetration does, or zero for a graze. */
    public static float crewDamage(float residual, float damage, float caliber) {
        // A round that neither out-penetrated the plate nor burst inside is not a crew casualty.
        if (residual < 5.0F && damage < 100.0F) {
            return 0.0F;
        }
        float severity = Math.max(residual, damage / 10.0F);
        float scale = caliber >= 50.0F ? 0.05F : 0.01F;
        return Math.min(12.0F, Math.max(0.5F, severity * scale));
    }

    /**
     * @return whether a wound takes the crew member out of their job.
     *
     * <p>Only a main gun does that. A 30mm round that gets through a roof hurts, but the crew
     * member can still work; a tank shell that gets through takes the station with it. The amount
     * threshold is what keeps a graze from doing it too.
     */
    public static boolean shouldIncapacitate(float caliber, float amount) {
        return caliber >= 50.0F && amount >= 6.0F;
    }

    /** @return how long a wound of this severity keeps the crew member out, in ticks. */
    public static int incapacitationTicks(float amount) {
        int ticks = MIN_INCAPACITATED_TICKS + (int) (amount * 10.0F);
        return Math.min(MAX_INCAPACITATED_TICKS, ticks);
    }

    private static LivingEntity victimOf(AbstractVehicle vehicle, ArmorConfig.VehicleArmor armor,
                                         boolean turret, PartUnit<?> struck, float residual) {
        LivingEntity owner = struck == null ? null : struck.getOwner();
        if (owner != null && owner.m_20202_() == vehicle) {
            return owner;
        }
        List<LivingEntity> crew = CrewRoles.crewIn(vehicle, armor, turret);
        if (crew.isEmpty()) {
            return null;
        }
        // The hit's own residual spreads the choice around; the vehicle id keeps two hits on the
        // same tank from walking through the seats in lockstep.
        int index = Math.floorMod(vehicle.m_19879_() * 31 + (int) residual, crew.size());
        return crew.get(index);
    }

    private static long now(Entity entity) {
        Entity vehicle = entity.m_20202_();
        return (vehicle != null ? vehicle.m_9236_() : entity.m_9236_()).m_46467_();
    }

    private static long now(MinecraftServer server) {
        // The overworld's game time is the server's clock; call it by its SRG name because this
        // addon compiles against the SRG-named Minecraft jar.
        return server.m_129783_().m_46467_();
    }

    /**
     * Clears expired wounds and restores a driver whose lock has run out.
     *
     * <p>A wound whose vehicle the player is no longer in is dropped without restoring anything:
     * they were ejected or killed, and the seat is somebody else's problem now.
     */
    private static void expireWounds(MinecraftServer server, long now) {
        Iterator<Map.Entry<UUID, Wound>> iterator = WOUNDS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Wound> entry = iterator.next();
            Wound wound = entry.getValue();
            ServerPlayer player = server.m_6846_().m_11259_(entry.getKey());
            if (player == null) {
                iterator.remove();
                continue;
            }
            if (!(player.m_20202_() instanceof AbstractVehicle vehicle)
                    || !vehicle.m_20148_().equals(wound.vehicle)) {
                iterator.remove();
                continue;
            }
            if (now < wound.until) {
                continue;
            }
            if (wound.driverLock) {
                restoreDriver(player, wound.vehicle);
            }
            iterator.remove();
            player.m_5661_(Component.m_237115_(
                    "message.ywzj_vehicle_armor.crew_recovered"), true);
            CrewControl.sendState(player, vehicle);
        }
    }

    private static void restoreDriver(ServerPlayer player, UUID vehicleId) {
        if (!(player.m_20202_() instanceof AbstractVehicle vehicle)
                || !vehicle.m_20148_().equals(vehicleId) || vehicle.getDriver() != null) {
            return;
        }
        PartUnit<?> unit = vehicle.getOwnOperatorUnit(player);
        if (unit == null) {
            return;
        }
        for (AbstractVehicle.Seat seat : vehicle.seats) {
            if (seat.seatIndex == 0) {
                if (seat.partUnit == unit) {
                    vehicle.controlUnit.setOperator(player);
                }
                return;
            }
        }
    }

    // --- what the action handlers ask ---------------------------------------------------------

    /** @return whether this entity is currently too hurt to work their station. */
    public static boolean isIncapacitated(Entity entity) {
        if (entity == null) {
            return false;
        }
        Wound wound = WOUNDS.get(entity.m_20148_());
        return wound != null && wound.until > now(entity);
    }

    /** @return whether the player may work the suspension or fording kit, with the reason spoken. */
    public static boolean requireDriver(ServerPlayer player, AbstractVehicle vehicle) {
        if (CrewRoles.roleOf(vehicle, player) != CrewRole.DRIVER) {
            player.m_5661_(Component.m_237115_(
                    "message.ywzj_vehicle_armor.driver_only"), true);
            return false;
        }
        return requireOperational(player);
    }

    /** @return whether the player may designate targets, with the reason spoken. */
    public static boolean requireCommander(ServerPlayer player, AbstractVehicle vehicle) {
        if (CrewRoles.roleOf(vehicle, player) != CrewRole.COMMANDER) {
            player.m_5661_(Component.m_237115_(
                    "message.ywzj_vehicle_armor.commander_only"), true);
            return false;
        }
        return requireOperational(player);
    }

    /** @return whether the player is well enough to act at all, with the reason spoken. */
    public static boolean requireOperational(ServerPlayer player) {
        if (isIncapacitated(player)) {
            player.m_5661_(Component.m_237115_(
                    "message.ywzj_vehicle_armor.incapacitated"), true);
            return false;
        }
        return true;
    }
}
