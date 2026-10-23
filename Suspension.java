package com.ywzjvehicle.armor;

import net.minecraft.network.chat.Component;

/**
 * Hydropneumatic suspension: the crew's control over how high the hull rides and how far it leans
 * fore and aft.
 *
 * <p>The vehicle already has the springs. Every wheel is a {@code ywzj_vehicle:suspension} part
 * with its own stiffness, damping and rest length, and {@code PhysicsEngine.motionBySuspension}
 * solves vertical, pitch and roll from them every tick and writes the answer straight onto the
 * entity - position, pitch, roll. So nothing here builds a spring: what is missing is a way for the
 * crew to say "hold it higher" or "put the nose down", and that is one number per wheel.
 *
 * <p><b>This is not a wheeled-vehicle mechanism, and until base mod 0.6.1 it looked like one.</b>
 * Until then only wheeled vehicles had suspension units at all, so only they had anything for the
 * bias to lengthen - and {@code hasSuspension()} is nothing more than "this vehicle has at least
 * one such part". 0.6.1 gives every tracked vehicle in the pack ten to fourteen of them, which is
 * what makes a tracked hydropneumatic suspension possible here at all: the solver, the pose, the
 * keys and the armour all follow the hull, and none of them care whether the hull is carried on
 * wheels or on tracks.
 *
 * <p>What a tracked vehicle does <em>not</em> get for free is travel. A wheel's contact probe
 * reaches exactly {@code max_droop} below its mount, and the pack ships 0.1 for every tracked
 * vehicle in it - a tenth of a block, which is invisible on a tank. {@code add_suspension.ps1}
 * raises that on the vehicles that have one of these in the config, and the figures below are then
 * bounded by it.
 *
 * <p>That number is the rest length. A spring pushes with {@code stiffness x compression} and
 * {@code compression} is {@code restLength - contactLength}, so lengthening the rest length by a
 * centimetre raises the height the hull settles at by about a centimetre, and lengthening the front
 * wheels while shortening the rear ones pitches the hull. Everything downstream follows from the
 * hull moving: the hitboxes are rebuilt from the entity's position and rotation every tick
 * ({@code VehicleCubeOBB.update}), the renderer transforms the whole model the same way, and the
 * armour reads its hit positions off the same body - so a kneeling tank really does expose its roof
 * and really does gain gun depression.
 *
 * <p>This class is the part that decides, and it is pure: what a step is worth, how many steps an
 * axis has, and how a step is shared out between the wheels. The mixin applies it. Keeping the two
 * apart is what lets {@code SuspensionCheck} assert the whole of it without a game - and an error
 * here has no symptom at all, since a wrong bias is still a number the physics will happily settle
 * at.
 *
 * <h2>The three numbers, and why there are three</h2>
 *
 * <p>{@code max_rise} and {@code max_drop} are not each other's negatives, because a vehicle's
 * suspension does not travel equally both ways and, more importantly, because the hull does not
 * always have room to go down. The Stryker's collision box has its belly drawn level with the
 * bottoms of its wheels: at rest it already sits a centimetre off the ground, so it has
 * <em>nothing</em> to lower into and its {@code max_drop} is zero. A symmetric range would have
 * pushed the hull into the terrain on the first press of the key.
 *
 * <p>{@code max_pitch} is in degrees rather than in blocks, because that is what the feature is for
 * - a hull pitched two and a half degrees down is two and a half degrees more gun depression - and
 * because it makes the per-wheel figure arithmetic rather than a guess: a wheel {@code z} blocks
 * from the hull's pitch axis needs {@code tan(angle) * z} of travel.
 */
public final class Suspension {

    /**
     * The suspension, as configured on a vehicle. Its **presence** in the config's
     * {@code suspensions} map is what says the vehicle has one - the same presence-as-switch rule
     * {@code fording}, {@code part_armor} and the rest of that config follow. A separate "enabled"
     * boolean would be the same switch written twice.
     *
     * <p>It hangs off the config root rather than off the vehicle's armour block, because the two
     * are answers to different questions: armour is what a vehicle is worth to a round that hits it,
     * and a suspension is a piece of equipment the crew can work. The vehicles here that really have
     * one - the BMP-3, the BRM-3K, the Khrizantema, the Challenger 2, the Puma, the Ajax - are not
     * in the armour table at all.
     */
    public static final class Settings {
        /**
         * How far above its resting height the hull can be held, in blocks.
         *
         * <p>Bounded by something outside this file: the wheels have to stay on the ground. A wheel's
         * rest length can only be lengthened while its contact probe can still reach the terrain,
         * and that window is the pack's {@code max_droop} minus however far it is already drooping.
         * On the Stryker that comes to 0.25 blocks, and the two axes spend it together - the worst
         * wheel is the one at the front corner, asked for the rise plus its share of the pitch.
         * {@code SuspensionCheck} works that sum out from the pack and fails if it does not fit,
         * because a wheel hanging in the air is not a number that looks wrong, it is a vehicle that
         * drives strangely.
         *
         * <p>The shipped pair - 0.15 of rise and 2 degrees of pitch - comes to 0.23 of the 0.25
         * available, which is why the pitch is two degrees rather than two and a half: the first
         * draft of these two numbers did not fit, and the tool said so.
         *
         * <p>The tracked vehicles ship 0.20 against a budget of 0.31, which is only available
         * because the pack patch raised their {@code max_droop} - out of the box this number would
         * have to be under 0.09, and the whole feature would be a hull that twitches.
         */
        public float max_rise = 0.15F;

        /**
         * How far below its resting height the hull can be held, in blocks.
         *
         * <p>Zero on a vehicle whose belly already sits on the ground, which the Stryker's does: its
         * collision box is drawn level with the bottoms of its wheels, so at rest it is already a
         * centimetre off the ground and there is nothing to lower into. Raise this only after
         * checking that there is clearance - a symmetric range would drive the hull into the terrain
         * on the first press.
         *
         * <p>On a tracked vehicle it is bounded by the one thing that can measure it: the height of
         * the hull's collision box above the lowest point of the running gear, straight off the
         * structure model. The six that ship with one have 0.598 to 0.756 of it, so their 0.20 still
         * leaves better than half a block of daylight under the belly. The failure a wrong value
         * here produces is not a crash: the hull reaches the ground, its own collision box holds it
         * up instead of its springs, and the tank drives like something with a flat tyre.
         */
        public float max_drop = 0.0F;

        /**
         * How far the hull can be pitched either way, in degrees. Nose down is negative.
         *
         * <p>Degrees rather than blocks because this is what the feature is for: a hull pitched two
         * degrees down is two degrees more gun depression, and that is a number a gunner can judge.
         */
        public float max_pitch = 2.0F;
    }

    /**
     * How far one press moves the height axis, in blocks, and the pitch axis, in degrees.
     *
     * <p>Constants rather than config, because they are a resolution and not a property of the
     * vehicle: what a vehicle has is a range, and how finely it is divided into presses is the same
     * question everywhere. Three presses to cross the Stryker's 0.15 of rise, and four to go from
     * level to either end of its 2 degrees.
     */
    public static final float HEIGHT_STEP = 0.05F;
    public static final float PITCH_STEP = 0.5F;

    /** Which positions the two axes are in. Step indices, not blocks - see {@link #bias}. */
    public record Pose(int height, int pitch) {

        /**
         * A vehicle with no suspension, and therefore no pose. <b>Not a resting pose, and not
         * level.</b>
         *
         * <p>It reads {@code (0, 0)}, and zero is a real position on both axes: the bottom of the
         * height range and the nose-down end of the pitch one, because {@link #height} counts up
         * from {@code -max_drop}. On the seven vehicles that have a suspension that is 0.2 blocks
         * low and 2 degrees nose-down.
         *
         * <p>That is not a hypothetical. This constant used to be called {@code LEVEL}, which reads
         * as "level" and is why it was never questioned, and it was reached by any vehicle whose
         * suspension lookup returned null - which, for a while, was all of them. Every one of them
         * sat low and nose-down while the keys appeared to work, because the reply reports what the
         * press <em>asked for</em> rather than what the vehicle is doing.
         *
         * <p>The resting pose is a property of the settings ({@link #heightRest} / {@link #pitchRest})
         * and there is no sensible default for it, so the name says what this is: no pose at all.
         * It survives only because {@code bias} with null settings returns zero, which makes the one
         * caller that can meet it harmless.
         */
        public static final Pose NONE = new Pose(0, 0);
    }

    private Suspension() {
    }

    /**
     * How many positions the height axis has.
     *
     * <p>At least one, always: a vehicle configured with no travel at all gets a single position
     * rather than a division by zero, and one position is "this axis does nothing", which is a
     * truthful answer for a vehicle with no room to move.
     */
    public static int heightSteps(Settings settings) {
        if (settings == null) {
            return 1;
        }
        float range = Math.abs(settings.max_rise) + Math.abs(settings.max_drop);
        return 1 + Math.round(range / HEIGHT_STEP);
    }

    /** How many positions the pitch axis has. Odd, so that exactly one of them is level. */
    public static int pitchSteps(Settings settings) {
        if (settings == null) {
            return 1;
        }
        return 1 + 2 * Math.round(Math.abs(settings.max_pitch) / PITCH_STEP);
    }

    /**
     * @return how far the hull is held above its resting height at this step, in blocks. Negative is
     *         below it, and step zero is the lowest position - which on a vehicle with no drop is
     *         the height it rests at.
     */
    public static float height(Settings settings, int step) {
        if (settings == null) {
            return 0.0F;
        }
        return -Math.abs(settings.max_drop) + clamp(step, heightSteps(settings)) * HEIGHT_STEP;
    }

    /**
     * @return how far the hull is pitched at this step, in degrees. Positive is nose up; the middle
     *         step is level.
     */
    public static float pitch(Settings settings, int step) {
        if (settings == null) {
            return 0.0F;
        }
        int steps = pitchSteps(settings);
        return (clamp(step, steps) - centre(steps)) * PITCH_STEP;
    }

    /**
     * The step the vehicle sits at untouched, which is the one that asks for nothing.
     *
     * <p>Not "the middle of the range": on a vehicle that can only go up, the resting pose is the
     * bottom of it. Resolved rather than assumed, because a file that says {@code max_drop} is 0.1
     * has a resting position one step up from the bottom, and a vehicle that started anywhere else
     * would visibly rise or sink the moment the world loaded.
     */
    public static int heightRest(Settings settings) {
        if (settings == null || settings.max_drop <= 0.0F) {
            return 0;
        }
        return Math.round(Math.abs(settings.max_drop) / HEIGHT_STEP);
    }

    /** The level step of the pitch axis, which is halfway along it. */
    public static int pitchRest(Settings settings) {
        return centre(pitchSteps(settings));
    }

    /** @return the step this one is at the middle of, for an axis that has one. */
    public static int centre(int steps) {
        return steps <= 1 ? 0 : (steps - 1) / 2;
    }

    /**
     * The step a press moves to, given which way the key asked for.
     *
     * <p><b>It stops at the ends rather than wrapping</b>, because every key that drives an axis now
     * says which way it means: holding "raise" at the top of the range and being sent back to the
     * bottom is the one thing a direction key must not do. That is the whole difference from the
     * single cycling key this started as, and it is a difference in kind rather than in taste - a
     * key whose meaning is "the next one along" has to wrap or it can never come back down.
     *
     * <p>Guarded rather than trusted, in the same spirit as {@code Fuze.byId}: the step and the
     * direction arrive from a client and the counts come from a config a player may have edited, and
     * a count of zero would otherwise index a position that does not exist.
     *
     * @param direction positive to raise, negative to lower; anything non-negative counts as up
     */
    public static int step(int step, int steps, int direction) {
        if (steps <= 1) {
            return 0;
        }
        int last = steps - 1;
        int next = clamp(step, steps) + (direction < 0 ? -1 : 1);
        return next < 0 ? 0 : Math.min(next, last);
    }

    /** Brings a step inside an axis, so a config edited underneath a running vehicle cannot index off it. */
    private static int clamp(int step, int steps) {
        int last = Math.max(0, steps - 1);
        return step < 0 ? 0 : Math.min(step, last);
    }

    /**
     * One wheel's share of the bias, in blocks, added to the rest length its springs were calibrated
     * to.
     *
     * <p>The whole mechanism is this line. Height is the same for every wheel, which is what makes
     * the hull rise without tilting. Pitch is proportional to how far the wheel is from the hull's
     * <b>stiffness-weighted</b> pitch axis rather than from its middle, and that weighting is the one
     * subtle thing here: with eight springs that do not all push equally hard, angling the hull about
     * a point that is not the balance point would also change its height, so a "nose down" key would
     * quietly raise the tank. About the weighted centre the sum of the bias is zero and the command
     * is pure rotation.
     *
     * @param z        this wheel's fore-and-aft position in the vehicle's own frame, in blocks
     * @param zBalance the stiffness-weighted mean of every wheel's, which the mixin works out
     */
    public static float bias(Settings settings, Pose pose, float z, float zBalance) {
        if (settings == null || pose == null) {
            return 0.0F;
        }
        return height(settings, pose.height())
                + (float) Math.tan(Math.toRadians(pitch(settings, pose.pitch()))) * (z - zBalance);
    }

    /**
     * @return the most any one wheel can be asked for by the height axis alone, in blocks. The pitch
     *         axis adds to this by an amount that depends on where the wheels are - which is in the
     *         pack rather than here, so {@code SuspensionCheck} reads it there and adds the two.
     */
    public static float maxLift(Settings settings) {
        if (settings == null) {
            return 0.0F;
        }
        return Math.max(Math.abs(settings.max_rise), Math.abs(settings.max_drop));
    }

    /** @return the tangent of the largest pitch, which multiplies a wheel's distance from the axis. */
    public static float maxPitchTangent(Settings settings) {
        return settings == null ? 0.0F : (float) Math.tan(Math.toRadians(Math.abs(settings.max_pitch)));
    }

    /**
     * What to say when a key is pressed.
     *
     * <p>Both axes are always reported together, because they are one setting: someone who has just
     * put the nose down wants to see that the height they chose earlier is still there, and a line
     * naming only the axis they touched would leave them wondering whether the other had been reset.
     *
     * <p>The position is reported as the numbers it is worth rather than as a name - "0.1 blocks,
     * -1.5 degrees" says what the tank is doing, works the same for three positions and for eleven,
     * and is the same sentence in every language. It also means the line can be built from what came
     * off the wire without the client needing the settings: the server measured the pose, so the
     * server sends the measurement.
     */
    public static Component describe(boolean hasSuspension, float rise, float pitchDegrees) {
        String prefix = "message." + VehicleArmorMod.MOD_ID + ".";
        if (!hasSuspension) {
            return Component.m_237115_(prefix + "suspension_none");
        }
        return Component.m_237110_(prefix + "suspension_set",
                round(rise), round(pitchDegrees));
    }

    /** One decimal place, or a bare integer - "0" reads better than "0.0" in a one-line message. */
    private static String round(float value) {
        return value == Math.round(value) ? String.valueOf(Math.round(value))
                : String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
