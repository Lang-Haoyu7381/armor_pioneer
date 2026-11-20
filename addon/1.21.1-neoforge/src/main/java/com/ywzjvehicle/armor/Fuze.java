package com.ywzjvehicle.armor;

/**
 * How a multi-purpose round decides when to go off.
 *
 * <p>Only rounds marked {@code multi_purpose} in the config pay any attention to this; every other
 * shell in the game behaves as {@link #IMPACT}, which is what it has always done.
 */
public enum Fuze {

    /** Goes off against whatever it strikes - the behaviour of an ordinary round. */
    IMPACT,

    /**
     * Goes off on the base mod's proximity fuze, detecting entities in a radius around its path
     * rather than waiting to touch one.
     */
    PROXIMITY,

    /**
     * Passes through one soft layer of cover and then goes off on the same blast as
     * {@link #PROXIMITY}, {@link FuzeSettings#delayTicks} later.
     */
    DELAY;

    private static final Fuze[] VALUES = values();

    /** @return the next fuze in the cycle the left arrow key walks. */
    public Fuze next() {
        return VALUES[(ordinal() + 1) % VALUES.length];
    }

    /**
     * @param id an ordinal that may have come off the wire, and so may be anything at all.
     * @return the matching fuze, or {@link #IMPACT} - the safe default - for anything else.
     */
    public static Fuze byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : IMPACT;
    }

    /** @return the translation key naming this fuze in the action bar. */
    public String translationKey() {
        return "fuze." + VehicleArmorMod.MOD_ID + "." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
