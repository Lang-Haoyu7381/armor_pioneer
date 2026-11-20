package com.ywzjvehicle.armor;

/**
 * A vehicle whose fording kit is somewhere in its preparation or deployment.
 *
 * <p>The phase lives on the vehicle entity, so it goes away with the tank instead of having to be
 * kept in a registry keyed by something that can be unloaded - the same reason the spent reactive
 * panels are keyed by the vehicle. It is not saved: a vehicle reloaded from disk comes back with the
 * kit stowed, which is what a crew that has just got out and back in would find.
 *
 * <p>The phase is the whole of the state - the engine reads it and the key writes it. When the
 * vehicle's model carries a snorkel part, the mixin also pushes the sealed/not-sealed boolean onto
 * that part, which is what puts the snorkel up on screen and what the client uses to lock the
 * turret. The part is a mirror of the phase and never consulted as a second opinion about it. That
 * direction matters: the part <em>is</em> saved to NBT, so a state kept in the part would survive a
 * reload on a patched pack and not on an unpatched one, and the same keypress would quietly mean two
 * different things depending on which pack was installed.
 *
 * <p>Reached by {@code instanceof} from the server-side message handler, so that handler never has
 * to name a mixin class - the pattern {@link ShellState} already established.
 */
public interface FordingState {

    /** @return how far through the fitting or removal the crew is. */
    Fording.Phase ywzjarmor$fordingPhase();

    /** @return the percentage of the current procedure completed, 0..100. */
    int ywzjarmor$fordingPercent();

    /**
     * Moves the kit to a phase and restarts its progress.
     *
     * <p>Only the two transitions a keypress can start are meaningful: {@code PREPARING} from
     * {@code STOWED}, and {@code STOWING} from {@code READY}. The tick decides when each is finished
     * and refuses to start one the tank is not entitled to; the caller has already checked the
     * driver and the ground.
     */
    void ywzjarmor$beginFording(Fording.Phase phase);

    /** Puts the kit back to stowed without a message. Used when the vehicle is broken or unconfigured. */
    void ywzjarmor$resetFording();
}
