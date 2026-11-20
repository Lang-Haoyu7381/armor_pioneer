package com.ywzjvehicle.armor;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleMultiWeapons;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Works out a crew member's role from the seat the game has already put them in.
 *
 * <p><b>Why calibre rather than part ids.</b> The base mod tells us who drives (seat zero holds the
 * control unit) and which seat operates which weapon. It does not tell us which weapon is the main
 * gun. Naming the gunners' parts would need a table per vehicle, and that table would be wrong the
 * first time a pack renamed a part - silently, because a role that cannot be resolved falls back to
 * "passenger" and nothing about that looks broken. The largest bore on the vehicle is a property of
 * the vehicle itself, so it survives renames and new packs.
 *
 * <p><b>Why roles are derived and not stored.</b> A player can change seats, and the base mod moves
 * their operator unit with them. Recomputing is cheap and cannot go stale; the only state the addon
 * keeps about a role is the last one it announced, so a seat change can be reported once.
 *
 * <p><b>The loader is the one role a name has to be given.</b> Driver, gunner and commander are
 * all properties of the vehicle's weapon layout; a manual loader is not. The config names the
 * loader's seat ({@link ArmorConfig.VehicleArmor#loader_seat_id}) on the vehicles that have one,
 * and the seat keeps its {@link CrewRole#LOADER} role even while its occupant is wounded. That is
 * what lets a turret hit take the loader out and slow the gun without silently changing what the
 * seat is.
 */
public final class CrewRoles {

    /**
     * The bore at or above which a reload is slow enough for a human loader to matter. The 120 mm
     * Rh-120 is the case this was written for; smaller automatic weapons are excluded.
     */
    public static final float MANUAL_LOADER_MIN_CALIBER = 100.0F;

    /**
     * How much longer a manual gun takes when no loader is available: the gunner or commander has
     * to cross-load, which halves the rate. The pack's {@code reload.time} is the trained loader's
     * figure, so this multiplier is only ever applied on top of it.
     */
    public static final float NO_LOADER_RELOAD_PENALTY = 2.0F;

    private CrewRoles() {
    }

    /**
     * @return what the given player is doing on this vehicle right now.
     *
     * <p>Driver wins over everything else: a vehicle can put a machine gun in seat zero, and the
     * player in it is still the only one who can drive. A weapon seat that is not the largest gun
     * is a commander's station; anything else is a passenger.
     */
    public static CrewRole roleOf(AbstractVehicle vehicle, LivingEntity player) {
        if (vehicle == null || player == null) {
            return CrewRole.CREW;
        }
        boolean driver = isDriverSeat(vehicle, player);
        PartUnit<?> unit = vehicle.getOwnOperatorUnit(player);
        boolean loaderSeat = isLoaderSeat(ArmorSystem.armorFor(vehicle), unit);
        boolean weaponSeat = unit instanceof WeaponUnit;
        WeaponUnit primary = primaryWeaponUnit(vehicle);
        WeaponUnit commander = commanderWeaponUnit(vehicle, primary);
        boolean primarySeat = weaponSeat && root((WeaponUnit) unit) == root(primary);
        boolean commanderSeat = weaponSeat && root((WeaponUnit) unit) == root(commander);
        return classify(driver, loaderSeat, weaponSeat, primarySeat, commanderSeat);
    }

    /**
     * @return whether the player occupies seat zero.
     *
     * <p>Read off the seat rather than off the control unit's current operator. The two normally
     * agree, but a wounded driver's control unit operator is deliberately taken away while the
     * role - and the seat - stay his. Asking the control unit would relabel him a passenger for
     * the duration of his own wound, which is exactly the kind of flicker that makes a HUD lie.
     */
    public static boolean isDriverSeat(AbstractVehicle vehicle, LivingEntity player) {
        if (vehicle == null || player == null) {
            return false;
        }
        PartUnit<?> unit = vehicle.getOwnOperatorUnit(player);
        if (unit == null) {
            return false;
        }
        for (AbstractVehicle.Seat seat : vehicle.seats) {
            if (seat.seatIndex == 0) {
                return seat.partUnit == unit;
            }
        }
        return false;
    }

    /**
     * The pure decision behind {@link #roleOf}, kept separate so the offline check can exercise it
     * without building a vehicle.
     */
    public static CrewRole classify(boolean driver, boolean loaderSeat, boolean weaponSeat,
                                    boolean primary, boolean commander) {
        if (driver) {
            return CrewRole.DRIVER;
        }
        if (loaderSeat) {
            return CrewRole.LOADER;
        }
        if (!weaponSeat) {
            return CrewRole.CREW;
        }
        if (primary) {
            return CrewRole.GUNNER;
        }
        // A non-main gun that is not the commander's largest secondary is still a passenger's
        // station. Naming every non-main gun "commander" would have made a loader the only person
        // able to mark targets, which is a role the vehicle does not have.
        return commander ? CrewRole.COMMANDER : CrewRole.CREW;
    }

    /**
     * @return whether this unit is the manual loader's seat on the given vehicle.
     *
     * <p>No name fallback: a part that happens to contain "loader" is not a loader's seat unless
     * the vehicle's own config says so. The config is what carries the promise that the vehicle
     * actually has a manual gun for that seat to feed.
     */
    public static boolean isLoaderSeat(ArmorConfig.VehicleArmor armor, PartUnit<?> unit) {
        return armor != null && unit != null
                && isLoaderSeat(armor.loader_seat_id, unit.getId());
    }

    /** The pure name comparison behind {@link #isLoaderSeat}, exported for the offline check. */
    public static boolean isLoaderSeat(String loaderSeatId, String partId) {
        return loaderSeatId != null && loaderSeatId.equals(partId);
    }

    /** @return the configured loader seat's part unit, or {@code null} on an autoloaded vehicle. */
    public static PartUnit<?> loaderPart(AbstractVehicle vehicle) {
        if (vehicle == null) {
            return null;
        }
        ArmorConfig.VehicleArmor armor = ArmorSystem.armorFor(vehicle);
        if (armor == null || armor.loader_seat_id == null) {
            return null;
        }
        for (PartUnit<?> unit : vehicle.getPartUnits()) {
            if (armor.loader_seat_id.equals(unit.getId())) {
                return unit;
            }
        }
        return null;
    }

    /**
     * @return the living entity in the loader's seat, or {@code null} when the seat is empty.
     *
     * <p>Read from the seat table rather than from a stored id, for the same reason the driver's
     * seat is: a player can change seats, leave, or die without the addon being told.
     */
    public static LivingEntity loader(AbstractVehicle vehicle) {
        PartUnit<?> seat = loaderPart(vehicle);
        if (seat == null) {
            return null;
        }
        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof LivingEntity living
                    && vehicle.getOwnOperatorUnit(living) == seat) {
                return living;
            }
        }
        return null;
    }

    /**
     * @return whether a healthy loader is in the loader's seat right now.
     *
     * <p>A wound does not vacate the seat - the {@link CrewRole#LOADER} role stays with the seat -
     * but an incapacitated loader cannot lift a 120 mm round, so the reload suffers the same
     * penalty as an empty seat. Server state; the reload mixin that reads it runs on the server.
     */
    public static boolean loaderOperational(AbstractVehicle vehicle) {
        LivingEntity loader = loader(vehicle);
        return loader != null && loader.isAlive() && !CrewState.isIncapacitated(loader);
    }

    /**
     * @return the multiplier to apply to a weapon's reload time for the loader's state.
     *
     * <p>One on every vehicle whose config names no loader seat and on every weapon below the
     * manual-loader bore, so autoloaded tanks and machine guns are untouched. On a manually loaded
     * main gun it is {@link #NO_LOADER_RELOAD_PENALTY} when no operational loader is aboard.
     */
    public static float loaderReloadFactor(AbstractVehicle vehicle,
                                           AbstractVehicleWeapon<?> weapon) {
        if (!isManuallyLoaded(weapon) || vehicle == null) {
            return 1.0F;
        }
        ArmorConfig.VehicleArmor armor = ArmorSystem.armorFor(vehicle);
        if (armor == null || armor.loader_seat_id == null) {
            return 1.0F;
        }
        return loaderReloadFactor(loaderOperational(vehicle));
    }

    /** @return the factor for a known loader state - one when they are fit, else the penalty. */
    public static float loaderReloadFactor(boolean loaderOperational) {
        return loaderOperational ? 1.0F : NO_LOADER_RELOAD_PENALTY;
    }

    /**
     * @return the reload time to use from the pack's figure, in ticks.
     *
     * <p>The pack's {@code reload.time} is the trained loader's cycle - 120 ticks (6 s) for the
     * Leopard's Rh-120 L/44 - so this multiplies that figure and never replaces it.
     */
    public static int loaderReloadTicks(int baseTicks, boolean loaderOperational) {
        return Math.max(1, Math.round(baseTicks * loaderReloadFactor(loaderOperational)));
    }

    /** @return whether a weapon is a gun large enough to need a human loader. */
    public static boolean isManuallyLoaded(AbstractVehicleWeapon<?> weapon) {
        if (weapon == null || weapon.getData() == null) {
            return false;
        }
        Float caliber = weapon.getData().getCaliber();
        return caliber != null && isManuallyLoaded(caliber);
    }

    /** The pure calibre test behind {@link #isManuallyLoaded}, exported for the offline check. */
    public static boolean isManuallyLoaded(float caliber) {
        return caliber >= MANUAL_LOADER_MIN_CALIBER;
    }

    /**
     * @return the root weapon unit with the largest bore, or {@code null} when the vehicle carries
     *         no weapon unit at all.
     *
     * <p>Root units only: a multi-weapon mount exposes its sub-weapons as weapon units of their
     * own, and counting those would let the same gun win twice. Ties keep the first unit, which is
     * the pack's declaration order and therefore stable.
     */
    public static WeaponUnit primaryWeaponUnit(AbstractVehicle vehicle) {
        if (vehicle == null) {
            return null;
        }
        WeaponUnit best = null;
        float bestCaliber = -1.0F;
        for (PartUnit<?> unit : vehicle.getPartUnits()) {
            if (!(unit instanceof WeaponUnit weapon)) {
                continue;
            }
            if (weapon.getParentWeaponUnit() != null) {
                continue;
            }
            float caliber = caliberOf(weapon);
            if (caliber > bestCaliber) {
                bestCaliber = caliber;
                best = weapon;
            }
        }
        return best;
    }

    /**
     * @return the largest root weapon unit below the main gun's bore - the commander's station.
     *
     * <p>Capability again rather than a part name, but one step narrower: on an Abrams the loader's
     * 7.62mm and the commander's 12.7mm are both "not the main gun", and only one of them is the
     * commander's. The larger of the secondaries is the one with the better view and the fire
     * control that goes with it on every vehicle this addon configures.
     */
    public static WeaponUnit commanderWeaponUnit(AbstractVehicle vehicle, WeaponUnit primary) {
        if (vehicle == null || primary == null) {
            return null;
        }
        float primaryCaliber = caliberOf(primary);
        WeaponUnit best = null;
        float bestCaliber = -1.0F;
        for (PartUnit<?> unit : vehicle.getPartUnits()) {
            if (!(unit instanceof WeaponUnit weapon) || weapon == primary
                    || weapon.getParentWeaponUnit() != null) {
                continue;
            }
            float caliber = caliberOf(weapon);
            if (caliber < primaryCaliber && caliber > bestCaliber) {
                bestCaliber = caliber;
                best = weapon;
            }
        }
        return best;
    }

    /** @return the bore of the round the unit has selected, or zero when the pack did not say. */
    public static float caliberOf(WeaponUnit unit) {
        if (unit == null) {
            return 0.0F;
        }
        AbstractVehicleWeapon<?> weapon = unit.getCurrentWeapon().orElse(null);
        // A multi-round mount holds the real weapons; the mount itself carries no calibre.
        if (weapon instanceof VehicleMultiWeapons multi) {
            weapon = multi.getSelectedWeapon();
        }
        if (weapon == null || weapon.getData() == null) {
            return 0.0F;
        }
        Float caliber = weapon.getData().getCaliber();
        return caliber == null ? 0.0F : caliber;
    }

    /** @return the unit with no parent above it; the same mount can be reached by two paths. */
    private static WeaponUnit root(WeaponUnit unit) {
        return unit == null ? null : unit.getRootParentWeaponUnit();
    }

    /**
     * @return whether this part unit sits in the turret compartment, for crew casualties.
     *
     * <p>The armour config's {@code turret_part_ids} is the authority where it says something. The
     * name fallback is what catches the commander's station on vehicles whose model parents it to a
     * separate root bone - the VT-5's {@code commander_machine_gun} is on {@code turret_st}, a
     * sibling of the turret bone, so the derived list cannot see it even though the station is on
     * the roof. A hull machine-gun port has no "turret" or "commander" in its id and stays on the
     * hull side, which is where a hit through the glacis should injure people.
     *
     * <p>The loader's seat is the fourth case: it is not an armour part and so is not in
     * {@code turret_part_ids}, but the loader rides in the turret and a turret penetration has to
     * be able to wound them. {@link ArmorConfig.VehicleArmor#loader_seat_id} names the seat for
     * exactly that purpose.
     */
    public static boolean inTurretCompartment(ArmorConfig.VehicleArmor armor, PartUnit<?> unit) {
        return unit != null && inTurretCompartment(armor, unit.getId());
    }

    /** The id form of {@link #inTurretCompartment(ArmorConfig.VehicleArmor, PartUnit)}, for checks. */
    public static boolean inTurretCompartment(ArmorConfig.VehicleArmor armor, String id) {
        if (id == null) {
            return false;
        }
        if (armor != null && isLoaderSeat(armor.loader_seat_id, id)) {
            return true;
        }
        if (armor != null && armor.turret_part_ids != null) {
            for (String turretId : armor.turret_part_ids) {
                if (id.equals(turretId)) {
                    return true;
                }
            }
        }
        return isTurretPartId(id);
    }

    /** The name part of {@link #inTurretCompartment}, exported for the offline check. */
    public static boolean isTurretPartId(String id) {
        if (id == null) {
            return false;
        }
        String lower = id.toLowerCase(Locale.ROOT);
        return lower.contains("turret") || lower.contains("commander");
    }

    /**
     * @return the living crew in one compartment, in the order the game lists the passengers.
     *
     * <p>Compartment membership comes from the seat's part unit rather than from the player's
     * position: a player's rendered position while riding is their seat, and a seat that is not in
     * the turret should not be hit by a round that went through the turret.
     */
    public static List<LivingEntity> crewIn(AbstractVehicle vehicle, ArmorConfig.VehicleArmor armor,
                                            boolean turret) {
        List<LivingEntity> crew = new ArrayList<>();
        if (vehicle == null) {
            return crew;
        }
        for (Entity passenger : vehicle.getPassengers()) {
            if (!(passenger instanceof LivingEntity living)) {
                continue;
            }
            PartUnit<?> unit = vehicle.getOwnOperatorUnit(living);
            if (unit != null && inTurretCompartment(armor, unit) == turret) {
                crew.add(living);
            }
        }
        return crew;
    }
}
