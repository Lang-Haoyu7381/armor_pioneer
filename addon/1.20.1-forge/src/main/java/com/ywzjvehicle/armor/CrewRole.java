package com.ywzjvehicle.armor;

import java.util.Locale;

/**
 * What a crew member is responsible for, derived from the seat they are in rather than from a
 * vehicle-specific list of part ids.
 *
 * <p>The base mod already builds the roles: seat zero drives, and every other seat that carries a
 * weapon unit operates that weapon. What it does not name is which weapon seat is the main gun and
 * which one is the commander's station. That is the only distinction added here, and it is made by
 * calibre - the largest gun on the vehicle is the gunner's, the rest are the commander's.
 *
 * <p>Capability, not part names: a new pack does not need a config entry to be recognised, and a
 * commander's machine gun called {@code turret_mg2} on one tank and {@code commander_machine_gun}
 * on another is the same role on both.
 */
public enum CrewRole {

    /** Seat zero: the base mod's control unit operator, the only seat that can move the vehicle. */
    DRIVER,

    /** The operator of the vehicle's largest-calibre weapon unit. */
    GUNNER,

    /** A weapon station other than the main gun: the commander's machine gun or remote mount. */
    COMMANDER,

    /**
     * The manual loader's seat. Only a vehicle whose config names a loader seat gets this role;
     * autoloaded vehicles leave the seat as {@link #CREW}.
     */
    LOADER,

    /** Any other seat - passengers, riders. */
    CREW;

    /** Translation key for the role's display name. */
    public String key() {
        return "role.ywzj_vehicle_armor." + name().toLowerCase(Locale.ROOT);
    }

    /** @return the role with this ordinal, or {@link #CREW} for a value off the end. */
    public static CrewRole byOrdinal(int ordinal) {
        CrewRole[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : CREW;
    }
}
