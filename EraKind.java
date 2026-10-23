package com.ywzjvehicle.armor;

/**
 * Explosive reactive armour, and how much of a tandem warhead it can take.
 *
 * <p>A shaped-charge jet sets the panel off, and the plate it throws wrecks the jet - which is why
 * reactive armour exists at all. A tandem warhead is the answer to that: a small precursor charge
 * goes off first and spends the panel, and the main charge arrives at armour that is no longer
 * covered. How well that works is entirely a question of which panel it is:
 *
 * <ul>
 *   <li>{@link #LIGHT} - a thin panel, and the precursor is enough. The main charge goes through,
 *       but the going is not free: a third of its penetration is spent on the panel. A tandem round
 *       still does what it was built for here, just not at full strength.</li>
 *   <li>{@link #HEAVY} - a thick one, and the precursor is <em>not</em> enough. The panel stops the
 *       round outright, exactly as it would stop an ordinary shaped charge, and is used up doing it.
 *       The next round - or the next missile - meets bare armour where the panel was.</li>
 * </ul>
 *
 * <p>The two differ in kind rather than in degree, which is why this is a named kind and not a
 * number: one scales the round and lets it past, the other annihilates it and is consumed. No single
 * value expresses both.
 *
 * <p>Ordinary rounds are not affected either way. They meet the panel and are measured against it,
 * which is what they did before this existed.
 */
public enum EraKind {

    /** Thin reactive armour: a tandem warhead gets through it, a third of its penetration lighter. */
    LIGHT("light"),

    /** Thick reactive armour: it stops one tandem warhead outright, and is spent doing so. */
    HEAVY("heavy");

    /** How much of a tandem round's penetration survives a light panel, in one place. */
    public static final float TANDEM_FRACTION_THROUGH_LIGHT = 2.0F / 3.0F;

    private final String id;

    EraKind(String id) {
        this.id = id;
    }

    /** @return the name this kind is written as in the config. */
    public String id() {
        return id;
    }

    /**
     * @return the kind this id names, or {@code null} for anything else - including null.
     *
     * <p>An unrecognised name reads as "not reactive armour", which is the safe direction: the panel
     * then behaves as it always did rather than acquiring a rule from a typo. It is logged where the
     * config is read, since a panel silently behaving as ordinary armour is exactly the kind of
     * failure this addon has been bitten by.
     */
    public static EraKind byId(String id) {
        if (id == null) {
            return null;
        }
        for (EraKind kind : values()) {
            if (kind.id.equals(id)) {
                return kind;
            }
        }
        return null;
    }
}
