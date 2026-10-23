package com.ywzjvehicle.armor;

/**
 * What a round does when it arrives - the first of the two axes the compendium is filed along.
 *
 * <p>Three of them, and they are the whole of the difference that matters: a round either drives
 * through armour with kinetic energy, burns through it with a jet, or goes off against it. The
 * vehicle's armour table has a set of numbers for each, which is why the question is worth asking
 * at all - the same plate is 480mm against one of these and 880mm against another.
 *
 * <p>The second axis is {@link WeaponClass}: what fires the round. The two are separate because they
 * are genuinely separate questions - a 30mm autocannon and a 125mm tank gun can fire the same kind
 * of warhead, and the book needs to say both "this is a kinetic round" and "this is not for your
 * machine gun". See {@link AmmoCategory} for the pairs that actually occur.
 */
public enum Warhead {

    /** Kinetic: a dart or a solid shot, measured by how far it drives in. */
    AP("ap"),

    /** Chemical: a shaped charge, measured by how far its jet burns in. */
    HEAT("heat"),

    /** A bursting charge, measured by how little it needs to get through to do its work. */
    HE("he");

    private final String id;

    Warhead(String id) {
        this.id = id;
    }

    /** @return the name this warhead is written as in the config, as part of a category id. */
    public String id() {
        return id;
    }

    public String translationKey() {
        return "gui.ywzj_vehicle_armor.warhead." + id;
    }

    /**
     * @return the warhead this id names, or {@code null} for anything else.
     *
     * <p>Used to read back the ammunition profiles that are named after a warhead - a vehicle's
     * armour tables are selected by profile name, so calling one of them "heat" in the config and
     * 破甲弹 on screen means going through the name rather than keeping a second list of which
     * profile is which. A profile that names no warhead gets {@code null} and is left alone.
     */
    public static Warhead byId(String id) {
        if (id == null) {
            return null;
        }
        for (Warhead warhead : values()) {
            if (warhead.id.equals(id)) {
                return warhead;
            }
        }
        return null;
    }
}
