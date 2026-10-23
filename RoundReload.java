package com.ywzjvehicle.armor;

/**
 * A weapon that can be re-fed, so a different load goes into it.
 *
 * <p>Choosing a load is not the same as loading one. The gunner picks from the list, and the round
 * in the breech is still the old one until the gun has been reloaded with the new one - which is
 * what this interface lets the server ask for, and what {@code GunnerSettings.commitLoad} finishes
 * when the reload ends.
 *
 * <p>An interface rather than the mixin class by name, the same rule the fording kit and the
 * suspension follow: the packet handler runs on both sides of a connection and must never name a
 * class that only exists where Mixin ran. A weapon that is not one of ours simply does not implement
 * it, and the handler says so.
 */
public interface RoundReload {

    /**
     * Starts the reload that will fit whatever the gunner has asked for.
     *
     * <p>Only ever called on the server, because a reload is the base mod's own state and it is the
     * server's copy of it that decides whether the gun can fire.
     */
    void ywzjarmor$startRoundReload();
}
