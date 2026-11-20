package com.ywzjvehicle.armor.mixin;

import com.ywzjvehicle.armor.ArmorSystem;
import com.ywzjvehicle.armor.Suspension;
import com.ywzjvehicle.armor.SuspensionState;
import com.ywzjvehicle.armor.VehicleArmorMod;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.SuspensionUnit;

/**
 * The whole of the hydropneumatic suspension, in a handful of field writes per wheel per tick.
 *
 * <p>A wheel is a spring whose force is {@code stiffness x compression}, and compression is
 * {@code restLength - contactLength} - how far the wheel is pushed up from where it hangs free. So
 * the height the hull settles at is the rest length, and asking for a different height is asking for
 * a different rest length. Writing it here, before anything reads it, is what makes the pack's own
 * springs do the work: the base mod's solver then finds a new equilibrium, with pitch and roll
 * solved along with the height, and applies it to the entity. Nothing else in this addon touches the
 * physics.
 *
 * <p><b>One rest length, as of 0.6.3.</b> Up to 0.6.2 a wheel was a contact patch and the solver
 * read a per-support-point array, which is why this class used to shadow and write
 * {@code supportRestLengths} beside the summary field. 0.6.3 replaced the patch with a single
 * support centre per suspension ({@code supportCenter}); {@code findContactLength} now measures
 * against {@code restLength} directly and {@code calibrateRestLengths} writes it directly. The
 * array is gone, so writing the field is the whole job - and shadowing a field that no longer
 * exists would fail at class transform time and take the entire mod down.
 *
 * <p><b>Injected at the head of {@code tick} rather than where the length is used.</b> The unit's
 * own {@code tick} calls {@code simulate} and then {@code updateTransform}, and the engine calls
 * {@code simulate} again for its own pass - so one write at the head covers both, and writing
 * anywhere else would leave the two disagreeing about the spring's length.
 *
 * <p><b>The bases are captured lazily, on the first tick, and never at construction.</b>
 * {@code calibrateRestLengths} runs once while the vehicle is being set up and <em>overwrites</em>
 * both the array and the field with the values that hold this vehicle at its static height, weight
 * and all - so the pack's {@code rest_length: 0.4} is not what the vehicle is actually riding on,
 * and remembering that instead would re-spring the whole tank. The first tick is after calibration
 * has run and before anything of ours has written either one.
 *
 * <p>Both sides run this. The client's suspension units compute their own compression for the
 * wheels' visual travel from the same rest length, so the pose has to be known on both sides - it is
 * synced, and on a client that has not heard yet the wheels simply render level.
 *
 * <p>Nothing here clamps the bias to the suspension's travel. It does not have to: the solver
 * already clamps compression to the unit's own limits, so an absurd bias bottoms the springs out
 * instead of throwing the vehicle somewhere. What stops an absurd bias from being shipped is
 * {@code SuspensionCheck}, which compares the config's largest step against the travel the pack
 * gives the wheels - because a wheel hanging in the air is not a number that looks wrong, it is a
 * vehicle that drives strangely.
 */
@Mixin(SuspensionUnit.class)
public abstract class SuspensionUnitMixin {

    /** The spring's rest length - what 0.6.3's contact probe measures against. Ours is this + bias. */
    @Shadow
    private float restLength;

    /**
     * The suspension's own idea of where the track bottom is. {@code TrackUnit}'s floating path
     * starts here and is pulled toward the suspension by the compression, so the visible link
     * centres need this point to be the link centre, not the bottom of the support cube.
     */
    @Shadow
    private Vec3 trackBottomCenter;

    /** The rest length the pack's springs were calibrated to. */
    @Unique
    private float ywzjarmor$baseRest;

    /** Whether the base above has been captured yet. */
    @Unique
    private boolean ywzjarmor$baseKnown;

    /** The hull's stiffness-weighted fore-and-aft balance point, worked out once - see the method. */
    @Unique
    private float ywzjarmor$balanceZ;

    /** The same across the hull, which is the roll axis's pivot. */
    @Unique
    private float ywzjarmor$balanceX;

    @Unique
    private boolean ywzjarmor$balanceKnown;

    /** The bias last announced, so the lines below are written once per pose rather than per tick. */
    @Unique
    private float ywzjarmor$announcedBias;

    @Unique
    private boolean ywzjarmor$announced;

    @Unique
    private boolean ywzjarmor$alive;

    /**
     * Where this wheel's actuator is, in blocks of bias. Distinct from the pose: the pose is what
     * the crew asked for, this is how far the pump has got.
     */
    @Unique
    private float ywzjarmor$appliedBias;

    /** Whether the actuator position above means anything yet. False until the first tick. */
    @Unique
    private boolean ywzjarmor$appliedKnown;

    /**
     * The droop the base mod calibrates in at rest - see {@code calibrateRestLengths}, which lowers
     * the ground by a centimetre before solving the static heights. The probe's reach is
     * {@code max_droop}, so the most a rest length may be lengthened without hanging the wheel is
     * {@code max_droop - this}.
     */
    @Unique
    private static final float ywzjarmor$REST_DROOP = 0.01F;

    @Inject(method = "tick", at = @At("HEAD"))
    private void ywzjarmor$applySuspension(CallbackInfo ci) {
        SuspensionUnit<?> self = (SuspensionUnit<?>) (Object) this;
        AbstractVehicle vehicle = self.getVehicle();
        if (!(vehicle instanceof SuspensionState state)) {
            return;
        }

        ywzjarmor$remember();

        Suspension.Settings settings = ArmorSystem.suspensionFor(vehicle);
        ywzjarmor$announceAlive(self, vehicle, settings);
        if (settings == null) {
            // No suspension configured. Put the lengths back, in case a config edit took one away
            // from a vehicle that was riding high when it happened.
            ywzjarmor$appliedKnown = false;
            ywzjarmor$appliedBias = 0.0F;
            ywzjarmor$write(0.0F);
            return;
        }

        Suspension.Pose pose = state.ywzjarmor$suspensionPose();
        ywzjarmor$measureBalance(vehicle);
        float target = Suspension.bias(settings, pose,
                (float) self.getPivotOffset().f_82479_,
                (float) self.getPivotOffset().f_82481_,
                ywzjarmor$balanceX, ywzjarmor$balanceZ);

        // A rest length longer than the probe can reach hangs the wheel, and the solver will not
        // report it - it will happily settle with that wheel off the ground. The config is checked
        // against the pack offline (SuspensionCheck), but a player may edit it afterwards, so the
        // runtime clamp is the last line of defence rather than the specification.
        float droop = self.getData().getMaxDroop();
        if (droop > ywzjarmor$REST_DROOP) {
            target = Math.min(target, droop - ywzjarmor$REST_DROOP);
        }

        // The actuator. On the first tick it starts where the pose asks, so a vehicle loaded at a
        // pose does not visibly pump itself into position; afterwards every tick moves the rest
        // length one rate's worth toward the target. Both sides run this against the synced pose,
        // so the server's hull and the client's wheels move together to within a tick.
        if (!ywzjarmor$appliedKnown) {
            ywzjarmor$appliedBias = target;
            ywzjarmor$appliedKnown = true;
        } else {
            ywzjarmor$appliedBias = Suspension.approach(ywzjarmor$appliedBias, target,
                    settings.actuationRate);
        }
        if (droop > ywzjarmor$REST_DROOP) {
            ywzjarmor$appliedBias = Math.min(ywzjarmor$appliedBias, droop - ywzjarmor$REST_DROOP);
        }

        ywzjarmor$write(ywzjarmor$appliedBias);
        ywzjarmor$announce(self, vehicle, settings, pose, target);
    }

    /**
     * Lifts the point the track floating path is built from onto the visible link centres.
     *
     * <p>{@code calculateTrackBounds} takes the bottom of the part's structure cubes, which on a
     * model whose suspension cubes span the wheel is the wheel bottom - below the link centres the
     * display model was drawn at. The 0.6.2 {@code TrackUnit} deforms the link path through this
     * point, so without the offset the lower run is that far into the ground. The amount is a
     * property of the model, so it lives in the vehicle's suspension settings and defaults to 0;
     * only the K2 (0.0654) and the Type 10 (0.0543) set it.
     *
     * <p>At RETURN, so it sees the value the method just computed. Private target method: Mixin can
     * inject into it, and the field is declared in {@code SuspensionUnit} itself, not inherited.
     */
    @Inject(method = "calculateTrackBounds", at = @At("RETURN"))
    private void ywzjarmor$raiseTrackFloatingCentre(CallbackInfo ci) {
        SuspensionUnit<?> self = (SuspensionUnit<?>) (Object) this;
        AbstractVehicle vehicle = self.getVehicle();
        if (vehicle == null || this.trackBottomCenter == null) {
            return;
        }
        Suspension.Settings settings = ArmorSystem.suspensionFor(vehicle);
        if (settings == null || settings.trackCenterOffset == 0.0F) {
            return;
        }
        this.trackBottomCenter = new Vec3(this.trackBottomCenter.f_82479_,
                this.trackBottomCenter.f_82480_ + settings.trackCenterOffset,
                this.trackBottomCenter.f_82481_);
    }

    /**
     * Remembers what the springs were calibrated to, once.
     *
     * <p>0.6.3's calibration writes the single {@code restLength} that the contact probe reads, so
     * there is one number to remember and no array whose shape could change. The first tick is
     * still the right moment: it is after {@code calibrateRestLengths} has solved this vehicle's
     * static height and before anything of ours has written the field.
     */
    @Unique
    private void ywzjarmor$remember() {
        if (ywzjarmor$baseKnown) {
            return;
        }
        ywzjarmor$baseRest = this.restLength;
        ywzjarmor$baseKnown = true;
    }

    /**
     * Writes the bias into the rest length the 0.6.3 solver reads.
     *
     * <p>Written from the remembered base rather than accumulated, so repeated key presses cannot
     * drift and a pose that returns to the shipped one restores the calibrated length exactly.
     */
    @Unique
    private void ywzjarmor$write(float bias) {
        this.restLength = ywzjarmor$baseRest + bias;
    }

    /**
     * Says, once per vehicle, that this class is running at all and what it found.
     *
     * <p>Written because the alternative is a silence that means any of three things: the injection
     * never applied, the vehicle resolved to no suspension block, or the wheels are simply not
     * touching anything. Those are three unrelated repairs, and without this line the only symptom
     * of all three is the same - a key that answers and a hull that does not move.
     *
     * <p>The wheel asked to speak is the vehicle's <b>first suspension unit</b>, compared by
     * identity. Not the part whose index is zero: part indices count every unit on the vehicle, and
     * a vehicle's wheels are never first - on the M1296 they are parts 5 through 12, so a guard on
     * index zero is a log line that never runs, and a diagnostic that silently proves nothing.
     */
    @Unique
    private void ywzjarmor$announceAlive(SuspensionUnit<?> self, AbstractVehicle vehicle,
                                         Suspension.Settings settings) {
        if (ywzjarmor$alive || vehicle.getSuspensionUnits().isEmpty()
                || vehicle.getSuspensionUnits().get(0) != self) {
            return;
        }
        ywzjarmor$alive = true;
        VehicleArmorMod.LOGGER.info(
                "[Vehicle Armor] suspension mixin running on {}: {} suspension unit(s), suspension block {}",
                vehicle.getVehicleId(), vehicle.getSuspensionUnits().size(),
                settings == null ? "NOT found" : "found");
    }

    /**
     * Says what the wheels were asked for, once per pose, on the vehicle's first wheel.
     *
     * <p>A wrong bias has no symptom of its own: it is a length the solver settles at quite happily,
     * so "the key answers and the hull does not move" looks identical whether the wheels were asked
     * for nothing, asked for something the springs cannot reach, or never asked at all because the
     * mixin did not run. What separates those is the state the solver reads rather than the number
     * written: whether the wheel is on the ground at all, what the contact point's own compression
     * and limits are, and where the bias put the lengths. The spread across the wheels is the other
     * half - it is the pitch gradient as the model actually laid the wheels out, which is the one
     * input no offline check can confirm.
     *
     * <p>The hull's own Y is in there on purpose too. It is read at the moment of the press, so it is
     * the height the <em>previous</em> pose settled at: two presses in a row whose Y does not move
     * say the write is not reaching the solver, and that is a different repair from one where the
     * hull moves and the model does not follow it.
     */
    @Unique
    private void ywzjarmor$announce(SuspensionUnit<?> self, AbstractVehicle vehicle,
                                    Suspension.Settings settings, Suspension.Pose pose, float bias) {
        if (vehicle.getSuspensionUnits().isEmpty() || vehicle.getSuspensionUnits().get(0) != self
                || (ywzjarmor$announced && ywzjarmor$announcedBias == bias)) {
            return;
        }
        ywzjarmor$announced = true;
        ywzjarmor$announcedBias = bias;
        ywzjarmor$measureBalance(vehicle);

        int grounded = 0;
        float lowest = Float.MAX_VALUE;
        float highest = -Float.MAX_VALUE;
        for (SuspensionUnit<?> unit : vehicle.getSuspensionUnits()) {
            if (unit.isGrounded()) {
                grounded++;
            }
            float each = Suspension.bias(settings, pose,
                    (float) unit.getPivotOffset().f_82479_,
                    (float) unit.getPivotOffset().f_82481_,
                    ywzjarmor$balanceX, ywzjarmor$balanceZ);
            lowest = Math.min(lowest, each);
            highest = Math.max(highest, each);
        }
        VehicleArmorMod.LOGGER.info(
                "[Vehicle Armor] suspension on {}: {} blocks / {} degrees / {} degrees of lean, "
                        + "{} of {} wheels on the ground, bias spread {} to {}, hull y {}",
                vehicle.getVehicleId(),
                Suspension.height(settings, pose.height()),
                Suspension.pitch(settings, pose.pitch()),
                Suspension.roll(settings, pose.roll()),
                grounded, vehicle.getSuspensionUnits().size(), lowest, highest,
                vehicle.m_20186_());
        VehicleArmorMod.LOGGER.info(
                "[Vehicle Armor]   {} rest length {} -> {}, compression {} within "
                        + "[{}, {}], grounded {}, {}",
                self.getId(), ywzjarmor$baseRest, this.restLength,
                self.getCompression(), self.getMinCompression(), self.getMaxCompression(),
                self.isGrounded(), ywzjarmor$contacts(self));
    }

    /** What the solver was handed for this wheel: the contact points, or that there were none. */
    @Unique
    private static String ywzjarmor$contacts(SuspensionUnit<?> self) {
        var points = self.getSupportContacts();
        if (points.isEmpty()) {
            return "no support point in contact";
        }
        var first = points.get(0);
        return points.size() + " support point(s), first at compression " + first.compression()
                + " within [" + first.minCompression() + ", " + first.maxCompression() + "]";
    }

    /**
     * The hull's balance points: the mean of the wheels' positions weighted by how hard each spring
     * pushes, once fore and aft and once across.
     *
     * <p>Both biases are measured from these rather than from the middle of the hull, and the
     * difference matters as soon as the springs are not all alike: angling about any point that is
     * not the balance point also raises or lowers the hull, so a "nose down" or "lean right" key
     * would quietly lift the tank. About these points the bias sums to zero and each command is pure
     * rotation.
     *
     * <p>Worked out once per unit and remembered. It is a property of the model and the part data,
     * neither of which changes while the vehicle exists - and with twelve wheels, twelve units each
     * working it out every tick would be a hundred and forty-four sums to answer a question whose
     * answer never moves.
     */
    @Unique
    private void ywzjarmor$measureBalance(AbstractVehicle vehicle) {
        if (ywzjarmor$balanceKnown) {
            return;
        }
        double weightedZ = 0.0;
        double weightedX = 0.0;
        double total = 0.0;
        for (SuspensionUnit<?> unit : vehicle.getSuspensionUnits()) {
            double stiffness = unit.getData().getSpringStiffness();
            weightedZ += stiffness * unit.getPivotOffset().f_82481_;
            weightedX += stiffness * unit.getPivotOffset().f_82479_;
            total += stiffness;
        }
        ywzjarmor$balanceZ = total > 0.0 ? (float) (weightedZ / total) : 0.0F;
        ywzjarmor$balanceX = total > 0.0 ? (float) (weightedX / total) : 0.0F;
        ywzjarmor$balanceKnown = true;
    }
}
