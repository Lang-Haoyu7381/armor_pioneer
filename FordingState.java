package com.ywzjvehicle.armor;

/**
 * A vehicle whose fording kit is deployed.
 *
 * <p>The flag lives on the vehicle entity, so it goes away with the tank instead of having to be
 * kept in a registry keyed by something that can be unloaded - the same reason the spent reactive
 * panels are keyed by the vehicle. It is not saved: a vehicle reloaded from disk comes back with the
 * snorkel stowed, which is the same deliberate gap the spent panels have.
 *
 * <p>The flag is the whole of the state - the engine reads it and the key writes it. When the
 * vehicle's model carries a snorkel part, the mixin also pushes the flag onto that part each tick,
 * which is what puts the snorkel up on screen; the part is a display of the flag and never consulted
 * as a second opinion about it. That direction matters: the part <em>is</em> saved to NBT, so a state
 * kept in the part would survive a reload on a patched pack and not on an unpatched one, and the same
 * keypress would quietly mean two different things depending on which pack was installed.
 *
 * <p>Reached by {@code instanceof} from the server-side message handler, so that handler never has
 * to name a mixin class - the pattern {@link ShellState} already established.
 */
public interface FordingState {

    /** @return whether the kit is deployed (the snorkel is up). */
    boolean ywzjarmor$fording();

    /** Deploys or stows the kit. */
    void ywzjarmor$setFording(boolean deployed);
}
