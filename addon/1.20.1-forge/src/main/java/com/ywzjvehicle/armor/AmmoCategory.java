package com.ywzjvehicle.armor;

import java.util.Collection;

/**
 * Where a round sits in the ammunition compendium: a {@link Warhead} and a {@link WeaponClass}
 * together.
 *
 * <p>Six of these rather than nine, because only six combinations are rounds the pack ships. The
 * two axes are the model; this enum is the set of pairs that exist, and adding a seventh - a shaped
 * charge for an autocannon, say - means adding a constant here and nothing else.
 *
 * <p>The id is what a config file writes, and the ids have not changed with the split: a round filed
 * under {@code machine_gun} is now understood as "a kinetic round, fired by a machine gun" rather
 * than as a shelf of its own, and no config needs rewriting to say so.
 *
 * <p>Written into the config rather than worked out from the weapon pack, because the pack can
 * answer "what is this called" but not "which shelf does the player expect it on" - and because the
 * weapon data reaches the client over the network, so a book that had to ask would show an empty
 * index until it arrived. {@link #derivedFrom} is what a hand-added round with no category gets.
 */
public enum AmmoCategory {

    CANNON_AP("cannon_ap", Warhead.AP, WeaponClass.CANNON),
    CANNON_HEAT("cannon_heat", Warhead.HEAT, WeaponClass.CANNON),
    CANNON_HE("cannon_he", Warhead.HE, WeaponClass.CANNON),
    AUTOCANNON_AP("autocannon_ap", Warhead.AP, WeaponClass.AUTOCANNON),
    AUTOCANNON_HE("autocannon_he", Warhead.HE, WeaponClass.AUTOCANNON),

    /**
     * Machine-gun ammunition, filed as kinetic.
     *
     * <p>It used to be a shelf of its own, on the argument that a 7.62mm round has no business being
     * called armour-piercing when the difference between that and high-explosive is a few
     * millimetres. That argument is about <em>splitting</em> a machine-gun round further, which this
     * still does not do - it is one shelf, filed under the warhead it actually is rather than
     * pretending to be a third kind of thing alongside the warheads.
     */
    MACHINE_GUN("machine_gun", Warhead.AP, WeaponClass.MACHINE_GUN),

    /**
     * The gun mod's cartridges, on the same two axes as everything else.
     *
     * <p>Three of these rather than one, because a TACZ cartridge really is one of the three
     * warheads: a rifle round is kinetic, the 40mm grenade and the RPG are shaped charges. Filing
     * them all as kinetic would compare a shaped charge against the wrong table and print a
     * plausible number that means nothing.
     */
    SMALL_ARMS_AP("small_arms_ap", Warhead.AP, WeaponClass.SMALL_ARMS),
    SMALL_ARMS_HEAT("small_arms_heat", Warhead.HEAT, WeaponClass.SMALL_ARMS),
    SMALL_ARMS_HE("small_arms_he", Warhead.HE, WeaponClass.SMALL_ARMS);

    private final String id;
    private final Warhead warhead;
    private final WeaponClass weapon;

    AmmoCategory(String id, Warhead warhead, WeaponClass weapon) {
        this.id = id;
        this.warhead = warhead;
        this.weapon = weapon;
    }

    /** @return the name this category is written as in the config. */
    public String id() {
        return id;
    }

    /** @return what the round does when it arrives. */
    public Warhead warhead() {
        return warhead;
    }

    /** @return what fires it. */
    public WeaponClass weapon() {
        return weapon;
    }

    /**
     * @return the category with this config name, or {@code null} when there is no such category -
     *         an unknown name is treated as no name at all rather than being forced onto a shelf
     *         the player did not choose.
     */
    public static AmmoCategory byId(String id) {
        if (id == null) {
            return null;
        }
        for (AmmoCategory category : values()) {
            if (category.id.equals(id)) {
                return category;
            }
        }
        return null;
    }

    /** @return whether any round can be filed under this pairing. */
    public static boolean exists(Warhead warhead, WeaponClass weapon) {
        for (AmmoCategory category : values()) {
            if (category.warhead == warhead && category.weapon == weapon) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return whether the given shelves put more than one kind of gun behind the same warhead -
     *         which is the condition for printing a weapon heading over a warhead's rounds.
     *
     * <p>A pure function over categories rather than a question asked of {@link AmmoIndex}, because
     * the book and the offline tool both need the answer and neither should get it by its own copy
     * of the rule. {@code AmmoIndex} would have been the obvious home, but listing its rounds asks
     * the vehicle pack for display names, and that drags the game's own classes in with it - which
     * is exactly what a tool that runs without a game cannot have.
     */
    public static boolean groupsByWeapon(Warhead warhead, Collection<AmmoCategory> shelves) {
        WeaponClass seen = null;
        for (AmmoCategory shelf : shelves) {
            if (shelf.warhead != warhead) {
                continue;
            }
            if (seen == null) {
                seen = shelf.weapon;
            } else if (seen != shelf.weapon) {
                return true;
            }
        }
        return false;
    }

    /**
     * The shelf a round lands on when its config does not say.
     *
     * <p>The warhead is not in doubt - a round that names the shaped-charge table is a shaped
     * charge - so only the weapon is guessed at, and it is guessed as a cannon. That is right for
     * every round the addon ships except the autocannon and machine-gun ones, which say so.
     */
    public static AmmoCategory derivedFrom(String profile) {
        if ("heat".equals(profile)) {
            return CANNON_HEAT;
        }
        if ("he".equals(profile)) {
            return CANNON_HE;
        }
        return CANNON_AP;
    }
}
