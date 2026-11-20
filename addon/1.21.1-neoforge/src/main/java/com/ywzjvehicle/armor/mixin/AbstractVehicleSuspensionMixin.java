package com.ywzjvehicle.armor.mixin;

import com.ywzjvehicle.armor.ArmorSystem;
import com.ywzjvehicle.armor.Suspension;
import com.ywzjvehicle.armor.SuspensionState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * Holds which position the crew has put the suspension in, and delivers it to the wheels.
 *
 * <p>The two step indices live here rather than on the wheels because they are one decision for the
 * whole vehicle: what a wheel does with it depends only on where that wheel is, which
 * {@link SuspensionUnitMixin} works out from the model. The pose is not saved, so a vehicle reloaded
 * from disk comes back level - the same gap the fording flag and the spent reactive panels have, and
 * for the same reason: a suspension nobody is sitting in does not hold a pose.
 *
 * <p>The steps start <b>unset</b> rather than at zero, because zero is the lowest position and the
 * vehicle's resting pose is the middle of the range. The middle is a property of the config - a
 * vehicle with five positions sits somewhere else than one with three - so it is resolved on the way
 * out, where the config is at hand, instead of being frozen in at construction.
 */
@Mixin(AbstractVehicle.class)
public abstract class AbstractVehicleSuspensionMixin implements SuspensionState {

    /** Not set: nobody has touched this vehicle's suspension. */
    @Unique
    private static final int ywzjarmor$UNSET = -1;

    @Unique
    private int ywzjarmor$height = ywzjarmor$UNSET;

    @Unique
    private int ywzjarmor$pitch = ywzjarmor$UNSET;

    @Unique
    private int ywzjarmor$roll = ywzjarmor$UNSET;

    @Override
    public Suspension.Pose ywzjarmor$suspensionPose() {
        Suspension.Settings settings = ywzjarmor$settings();
        if (settings == null) {
            // The vehicle has no suspension, so it has no pose. See Pose#NONE for why this is not
            // "level" and must not be used as one.
            return Suspension.Pose.NONE;
        }
        return new Suspension.Pose(
                ywzjarmor$height < 0 ? Suspension.heightRest(settings) : ywzjarmor$height,
                ywzjarmor$pitch < 0 ? Suspension.pitchRest(settings) : ywzjarmor$pitch,
                ywzjarmor$roll < 0 ? Suspension.rollRest(settings) : ywzjarmor$roll);
    }

    @Override
    public void ywzjarmor$setSuspensionPose(Suspension.Pose pose) {
        if (pose == null) {
            return;
        }
        ywzjarmor$height = pose.height();
        ywzjarmor$pitch = pose.pitch();
        ywzjarmor$roll = pose.roll();
    }

    /**
     * @return this vehicle's suspension, or {@code null} when it has none configured.
     *
     * <p>Resolved through {@code ArmorSystem} rather than off the vehicle's armour, because the two
     * stopped being the same lookup at v44: a suspension is no longer a block on a vehicle's armour
     * block, and the six tracked vehicles that have one have no armour entry at all.
     *
     * <p><b>This line is why nothing was level.</b> Left reading the old field, it answered
     * {@code null} for every vehicle, the pose below fell through to {@link Suspension.Pose#NONE},
     * and that constant is step zero on both axes - the <em>bottom</em> of the height range and the
     * <em>nose-down end</em> of the pitch one, not the resting pose. So every vehicle sat 0.2 blocks
     * low and 2 degrees nose-down whenever nobody had pressed anything, and pressing a key could not
     * fix it: the reply reported the position the press asked for, while this method went on
     * returning the same placeholder.
     */
    @Unique
    private Suspension.Settings ywzjarmor$settings() {
        return ArmorSystem.suspensionFor((AbstractVehicle) (Object) this);
    }
}
