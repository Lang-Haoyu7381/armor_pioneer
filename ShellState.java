package com.ywzjvehicle.armor;

/**
 * A shell that knows which fuze it is carrying.
 *
 * <p>Implemented by the {@code AmmoEntity} mixin; the damage system reads it off the projectile
 * rather than out of a side table, so a shell cannot be separated from its own fuze by an entity
 * unloading and reloading. Everything that is not a shell the mixin touched simply does not
 * implement this, which is what keeps ordinary rounds on their configured numbers.
 */
public interface ShellState {

    /** @return the fuze this shell adopted when it was fired. */
    Fuze ywzjarmor$fuze();

    /** @return ticks a delay fuze waits after passing through cover. */
    int ywzjarmor$delayTicks();

    /**
     * @return which mark of the round the gun was loaded with when this shell was fired.
     *
     * <p>Frozen at the same moment as the fuze, and for the same reason: swapping the load after
     * the shot has left the barrel must not change what is already in the air.
     */
    int ywzjarmor$load();
}
