package com.ywzjvehicle.armor;

/**
 * A vehicle whose crew can work its suspension.
 *
 * <p>The setting lives on the vehicle entity rather than on the wheels, because it is one decision
 * for the whole tank: the wheels only differ by where they are, which the mixin works out from the
 * model. It goes away with the tank instead of having to be kept in a registry keyed by something
 * that can be unloaded - the same reason the fording flag and the spent reactive panels are keyed by
 * the vehicle. It is not saved, so a vehicle reloaded from disk comes back level; that is the same
 * deliberate gap those two have.
 *
 * <p>Reached by {@code instanceof} from the server-side message handler, so that handler never has
 * to name a mixin class - the pattern {@link ShellState} and {@link FordingState} established.
 */
public interface SuspensionState {

    /** @return which step each axis is on, as the wire carries it. */
    Suspension.Pose ywzjarmor$suspensionPose();

    /** @param pose the new pose. The caller has already put each axis through {@link Suspension#step}. */
    void ywzjarmor$setSuspensionPose(Suspension.Pose pose);
}
