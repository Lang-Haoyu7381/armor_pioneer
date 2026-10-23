package com.ywzjvehicle.armor;

/**
 * What fires a round - the second of the two axes the compendium is filed along.
 *
 * <p>Ordered as the book reads them: the tank gun first, then the autocannon, then the machine gun.
 * That is also roughly the order of how much the distinction matters, which is why a warhead with
 * only one class of weapon behind it does not print the heading at all.
 *
 * <p>Deliberately coarse. It separates the three sizes of gun the pack actually ships and says
 * nothing about calibre, because a 30mm and a 25mm autocannon round belong on the same shelf and a
 * list that subdivided them would be a list of weapons, not of ammunition.
 */
public enum WeaponClass {

    CANNON("cannon"),
    AUTOCANNON("autocannon"),
    MACHINE_GUN("machine_gun");

    private final String id;

    WeaponClass(String id) {
        this.id = id;
    }

    /** @return the name this class is written as in the config, as part of a category id. */
    public String id() {
        return id;
    }

    public String translationKey() {
        return "gui.ywzj_vehicle_armor.weapon_class." + id;
    }
}
