package com.ywzjvehicle.armor;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleMultiWeapons;

/**
 * Works out which configured round the player has in the gun they are operating.
 *
 * <p>Deliberately answers a narrower question than it once did. It returns <em>any</em> round the
 * addon configures, and says nothing about whether that round has a fuze to switch or loads to
 * choose - those are the caller's business, and one round supports one and not the other. It used
 * to filter down to the multi-purpose round, which was right while the fuze was the only thing a
 * key could change; the moment a second kind of key existed, that filter turned every press on a
 * plain dart into a silent no-op - and the rejection path was hidden behind the same filter, so
 * nothing was logged either. The load chooser simply did not work, and said nothing about it.
 *
 * <p>Deliberately lives outside the client-only classes: the answer decides what the server will
 * act on, and that decision belongs on the side whose view of who is sitting in which seat cannot
 * be out of date.
 */
public final class OperatedRound {

    private OperatedRound() {
    }

    /**
     * @return the configured round in the gun the given player is operating, or {@code null} when
     *         the weapon in front of them is one the addon has no entry for - which includes every
     *         weapon on a vehicle it does not govern, and every player on foot.
     */
    public static ArmorConfig.Ammo operatingRound(LivingEntity operator) {
        if (operator == null) {
            return null;
        }
        Entity ridden = operator.m_20202_();
        if (!(ridden instanceof AbstractVehicle vehicle)) {
            return null;
        }
        return roundOf(vehicle.getOwnOperatorUnit(operator));
    }

    /**
     * @return any configured round carried anywhere on the vehicle, or {@code null} if it carries
     *         none. Used only to decide whether a rejected key press is worth logging: a helicopter
     *         whose arrow keys the addon happens to see is not a problem to report, while a tank
     *         with a configured gun and a gunner whose press did nothing certainly is.
     */
    public static ArmorConfig.Ammo anyRound(AbstractVehicle vehicle) {
        if (vehicle == null) {
            return null;
        }
        for (PartUnit<?> unit : vehicle.getPartUnits()) {
            ArmorConfig.Ammo round = roundOf(unit);
            if (round != null) {
                return round;
            }
        }
        return null;
    }

    /**
     * @return the weapon in front of the given player, or {@code null} when they are on foot or the
     *         vehicle's own operator unit is not a weapon at all.
     *
     * <p>Needed by itself, and not only for the round it carries: changing what a gun is loaded with
     * means re-feeding that gun, so the caller has to be able to reach it.
     */
    public static AbstractVehicleWeapon<?> weaponOf(LivingEntity operator) {
        if (operator == null) {
            return null;
        }
        Entity ridden = operator.m_20202_();
        if (!(ridden instanceof AbstractVehicle vehicle)) {
            return null;
        }
        return weaponIn(vehicle.getOwnOperatorUnit(operator));
    }

    /**
     * @return the round the given unit has loaded, or {@code null} when the addon has no entry for
     *         it - which is what keeps every weapon on a vehicle it does not govern out of this.
     *
     * <p>No filter on what the round can do. Returning only the rounds that supported the one
     * action that existed at the time is what broke the second action, and a round's capabilities
     * are for the caller to check.
     */
    private static ArmorConfig.Ammo roundOf(PartUnit<?> unit) {
        AbstractVehicleWeapon<?> weapon = weaponIn(unit);
        return weapon == null ? null : ArmorConfig.ammoFor(weapon.getData().getWeaponId());
    }

    /**
     * The weapon a unit is pointing at, with the multi-round mount's own data stepped over.
     *
     * <p>One place rather than two: the round and the weapon have to be the same weapon, and a copy
     * of this resolution that drifted from the other would have the addon measuring one gun's round
     * while re-feeding a different one.
     */
    private static AbstractVehicleWeapon<?> weaponIn(PartUnit<?> unit) {
        if (!(unit instanceof WeaponUnit weaponUnit)) {
            return null;
        }
        AbstractVehicleWeapon<?> weapon = weaponUnit.getCurrentWeapon().orElse(null);
        // A multi-round mount holds the sub-weapons; the mount's own data is not the round in the
        // breech, and only the sub-weapon knows which shell the gunner has selected.
        if (weapon instanceof VehicleMultiWeapons multi) {
            weapon = multi.getSelectedWeapon();
        }
        return weapon == null || weapon.getData() == null ? null : weapon;
    }

    /** @return the vehicle the player is riding, or {@code null} if they are on foot. */
    public static AbstractVehicle vehicleOf(LivingEntity operator) {
        if (operator == null) {
            return null;
        }
        Entity ridden = operator.m_20202_();
        return ridden instanceof AbstractVehicle vehicle ? vehicle : null;
    }
}
