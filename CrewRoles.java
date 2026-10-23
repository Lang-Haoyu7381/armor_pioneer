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
 */
public final class CrewRoles {

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
        boolean weaponSeat = unit instanceof WeaponUnit;
        WeaponUnit primary = primaryWeaponUnit(vehicle);
        WeaponUnit commander = commanderWeaponUnit(vehicle, primary);
        boolean primarySeat = weaponSeat && root((WeaponUnit) unit) == root(primary);
        boolean commanderSeat = weaponSeat && root((WeaponUnit) unit) == root(commander);
        return classify(driver, weaponSeat, primarySeat, commanderSeat);
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
    public static CrewRole classify(boolean driver, boolean weaponSeat, boolean primary,
                                    boolean commander) {
        if (driver) {
            return CrewRole.DRIVER;
        }
        if (!weaponSeat) {
            return CrewRole.CREW;
        }
        if (primary) {
            return CrewRole.GUNNER;
        }
        // A loader's machine gun is a weapon station too, but it is not the commander's. Naming
        // every non-main gun "commander" would have made a loader the only person able to mark
        // targets, which is a role the vehicle does not have.
        return commander ? CrewRole.COMMANDER : CrewRole.CREW;
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
     */
    public static boolean inTurretCompartment(ArmorConfig.VehicleArmor armor, PartUnit<?> unit) {
        if (unit == null || unit.getId() == null) {
            return false;
        }
        String id = unit.getId();
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
        for (Entity passenger : vehicle.m_20197_()) {
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
