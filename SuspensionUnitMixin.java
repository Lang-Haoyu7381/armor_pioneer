package com.ywzjvehicle.armor.mixin;

import com.ywzjvehicle.armor.ArmorSystem;
import com.ywzjvehicle.armor.Suspension;
import com.ywzjvehicle.armor.SuspensionState;
import com.ywzjvehicle.armor.VehicleArmorMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.SuspensionUnit;

import java.util.Arrays;

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
 * <p><b>There are two rest lengths, and the one the name suggests is the one that does nothing.</b>
 * As of 0.6.0 a wheel is not a point but a small contact patch: {@code buildSupportOffsets} spreads
 * support points across the bone's cubes, {@code simulate} builds one {@code SupportContact} per
 * point out of {@code supportRestLengths[i]}, and {@code PhysicsEngine.motionBySuspension} reads
 * those contacts and nothing else. The {@code restLength} field is their <em>average</em>, and it is
 * what the compression clamps and the wheel's drawn offset come from. Writing only the field is
 * therefore a change nothing in the solver can feel: the key answers, the wheel's picture twitches,
 * and the hull does not move. Both are written here, together, from bases remembered together.
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

    /** The springs' own summary of themselves: the average of the array below. Ours is this + bias. */
    @Shadow
    private float restLength;

    /** One rest length per support point - {@code supportRestLengths[i]}. What the solver reads. */
    @Shadow
    private double[] supportRestLengths;

    /** The average the pack's springs were calibrated to. */
    @Unique
    private float ywzjarmor$baseRest;

    /** Every support point's calibrated length, in the order {@code buildSupportOffsets} made them. */
    @Unique
    private double[] ywzjarmor$baseSupport;

    /** The hull's stiffness-weighted balance point, worked out once - see the method. */
    @Unique
    private float ywzjarmor$balance;

    @Unique
    private boolean ywzjarmor$balanceKnown;

    /** The bias last announced, so the lines below are written once per pose rather than per tick. */
    @Unique
    private float ywzjarmor$announcedBias;

    @Unique
    private boolean ywzjarmor$announced;

    @Unique
    private boolean ywzjarmor$alive;

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
            ywzjarmor$write(0.0F);
            return;
        }

        Suspension.Pose pose = state.ywzjarmor$suspensionPose();
        float bias = Suspension.bias(settings, pose, (float) self.getPivotOffset().f_82481_,
                ywzjarmor$balance(vehicle));
        ywzjarmor$write(bias);
        ywzjarmor$announce(self, vehicle, settings, pose, bias);
    }

    /**
     * Remembers what the springs were calibrated to, once, and again if the array is ever rebuilt.
     *
     * <p>The array is re-taken only when its length changes, which is {@code buildStructure} handing
     * out a new set of support points rather than the calibration being re-run. Remembering the
     * wrong thing here would re-spring the vehicle rather than bias it, so the condition is on the
     * shape of the array rather than on a flag: a flag cannot notice being handed a different array.
     *
     * <p>The copy is made with {@code Arrays.copyOf} rather than by asking the array to copy itself,
     * and that is not a style choice: Mixin rewrites every method call in an injected body, and a
     * call whose owner is an array type ({@code [D}) sends it looking for a class by that name, which
     * does not exist - it throws while transforming the class and the whole mod fails to load with
     * "broken mod state" before the game reaches the main menu. {@code System.arraycopy} would do as
     * well; what it must not be is a method invoked <em>on</em> the array.
     */
    @Unique
    private void ywzjarmor$remember() {
        if (ywzjarmor$baseSupport != null && ywzjarmor$baseSupport.length == this.supportRestLengths.length) {
            return;
        }
        ywzjarmor$baseRest = this.restLength;
        ywzjarmor$baseSupport = Arrays.copyOf(this.supportRestLengths, this.supportRestLengths.length);
    }

    /**
     * Writes the bias into both rest lengths: every support point, and the field that summarises
     * them.
     *
     * <p>The field is not re-derived from the array. It is the average of it, every point moves by
     * the same bias, so the average moves by the same bias - and writing it that way keeps the two
     * agreeing to the last bit instead of to whatever an average of eight doubles rounds to.
     */
    @Unique
    private void ywzjarmor$write(float bias) {
        for (int i = 0; i < this.supportRestLengths.length; i++) {
            this.supportRestLengths[i] = ywzjarmor$baseSupport[i] + bias;
        }
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

        int grounded = 0;
        float lowest = Float.MAX_VALUE;
        float highest = -Float.MAX_VALUE;
        for (SuspensionUnit<?> unit : vehicle.getSuspensionUnits()) {
            if (unit.isGrounded()) {
                grounded++;
            }
            float each = Suspension.bias(settings, pose, (float) unit.getPivotOffset().f_82481_,
                    ywzjarmor$balance(vehicle));
            lowest = Math.min(lowest, each);
            highest = Math.max(highest, each);
        }
        VehicleArmorMod.LOGGER.info(
                "[Vehicle Armor] suspension on {}: {} blocks / {} degrees, {} of {} wheels on the ground, "
                        + "bias spread {} to {}, hull y {}",
                vehicle.getVehicleId(),
                Suspension.height(settings, pose.height()),
                Suspension.pitch(settings, pose.pitch()),
                grounded, vehicle.getSuspensionUnits().size(), lowest, highest,
                vehicle.m_20186_());
        VehicleArmorMod.LOGGER.info(
                "[Vehicle Armor]   {} rest length {} -> {} (spring {} -> {}), compression {} within "
                        + "[{}, {}], grounded {}, {}",
                self.getId(), ywzjarmor$baseRest, this.restLength,
                ywzjarmor$baseSupport.length == 0 ? "-" : ywzjarmor$baseSupport[0],
                this.supportRestLengths.length == 0 ? "-" : this.supportRestLengths[0],
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
     * The hull's fore-and-aft balance point: the mean of the wheels' positions weighted by how hard
     * each spring pushes.
     *
     * <p>The pitch bias is measured from here rather than from the middle of the hull, and the
     * difference matters as soon as the springs are not all alike: angling about any point that is
     * not the balance point also raises or lowers the hull, so a "nose down" key would quietly lift
     * the tank. About this point the bias sums to zero and a pitch command is pure rotation.
     *
     * <p>Worked out once per unit and remembered. It is a property of the model and the part data,
     * neither of which changes while the vehicle exists - and with eight wheels, eight units each
     * working it out every tick would be sixty-four sums to answer a question whose answer never
     * moves.
     */
    @Unique
    private float ywzjarmor$balance(AbstractVehicle vehicle) {
        if (!ywzjarmor$balanceKnown) {
            double weighted = 0.0;
            double total = 0.0;
            for (SuspensionUnit<?> unit : vehicle.getSuspensionUnits()) {
                double stiffness = unit.getData().getSpringStiffness();
                weighted += stiffness * unit.getPivotOffset().f_82481_;
                total += stiffness;
            }
            ywzjarmor$balance = total > 0.0 ? (float) (weighted / total) : 0.0F;
            ywzjarmor$balanceKnown = true;
        }
        return ywzjarmor$balance;
    }
}
