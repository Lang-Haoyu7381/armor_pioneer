import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ywzjvehicle.armor.ArmorConfig;
import com.ywzjvehicle.armor.Suspension;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * Exercises the adjustable suspension offline, without a game and without a world.
 *
 * <p>What the mechanism really is: every wheel is a spring whose force is {@code stiffness x
 * compression}, compression is {@code restLength - contactLength}, so the height the hull settles at
 * <em>is</em> the rest length. The addon's whole part in it is one field write per wheel per tick.
 * That makes most of the feature reachable from here:
 *
 * <ul>
 *   <li>{@link Suspension#step}, {@link Suspension#centre} and {@link Suspension#bias} are pure and
 *       are the entire decision - which position a key moves to, and how much each wheel is asked
 *       for.</li>
 *   <li>{@link Suspension#approach} is the actuator that keeps a press from being a teleport: it
 *       moves the rest-length bias a bounded amount each tick, lands exactly on the target, and
 *       treats a zero rate as instant rather than frozen.</li>
 *   <li>The one thing the settings must stay inside is in the pack rather than here: the wheels'
 *       travel. A step larger than that either bottoms the springs out or hangs the wheels in the
 *       air, and in game neither is a number that looks wrong - it is a vehicle that drives
 *       strangely, which is exactly the kind of failure this project writes tools for.</li>
 *   <li>A message whose translation key is missing or misspelled puts {@code
 *       message.ywzj_vehicle_armor.suspension_set} on the screen instead of a sentence.</li>
 *   <li>A mixin class that is written, compiled, shipped and not listed in the mixins json is never
 *       applied at all - the keys would answer and nothing would move, on every vehicle, for
 *       ever.</li>
 * </ul>
 *
 * <p>What it cannot reach is whether the hull actually moves, and which way it leans: that is the
 * base mod's solver, and the sign of the fore-and-aft lever, and both need a real game. They are
 * guarded there rather than here - {@code required: true} turns a failed injection into a crash, and
 * the lever's sign is one constant in {@code SuspensionUnitMixin}.
 *
 * <p>Usage: SuspensionCheck
 */
public class SuspensionCheck {

    private static int failures;

    /** Named in the mixins json, or the whole feature is a class nobody calls. */
    private static final String[] MIXINS = {"AbstractVehicleSuspensionMixin", "SuspensionUnitMixin"};

    /** What {@code VehicleArmorMod} calls itself, which is where the message keys are built from. */
    private static final String MOD_ID = "ywzj_vehicle_armor";

    /** The one vehicle configured with a suspension, and the pack part type the mechanism drives. */
    private static final String VEHICLE = "vvp_ywzj:stryker_m1296";
    /** The tank added at v53: twelve in-arm units, and the longest lever in the set. */
    private static final String K2 = "armor_pioneer:k2";
    /** The tank added at v60: ten hydraulic units and the same 0.60 of pack travel. */
    private static final String TYPE10 = "armor_pioneer:type10";
    /**
     * The five tanks whose baked treads gave way to a link model on 2026-11-01. They differ from
     * the K2/Type 10 in one respect only: they keep the legacy {@code left_track}/{@code
     * right_track} names and the controller's {@code track_animation} node, because their wheel
     * spin still comes from that animation instance. The part that must match is the visible track.
     */
    private static final java.util.Set<String> BELT_TANKS = java.util.Set.of(
            "armor_pioneer:vt5", "armor_pioneer:vt4", "armor_pioneer:tianma20",
            "armor_pioneer:leopard2a4", "armor_pioneer:ztz100");
    private static final String SUSPENSION_PART = "ywzj_vehicle:suspension";

    /** Which way a keypress asked to go, as the two ends of the axis see it. */
    private static final int UP = 1;
    private static final int DOWN = -1;

    public static void main(String[] args) throws Exception {
        stepping();
        bias();
        actuation();
        messages();
        roundTrip();
        mixinsListed();
        travel();

        System.out.println();
        if (failures == 0) {
            System.out.println("RESULT: the suspension moves what it says it moves");
        } else {
            System.out.println("RESULT: " + failures + " check(s) failed - see above");
            System.exit(1);
        }
    }

    /**
     * Which position a press moves to, at both ends of the range and off the ends.
     *
     * <p>The ends are the part that matters. Every key that drives an axis says which way it means,
     * so a press has to <b>stop</b> at the end of the travel - a "raise" that wrapped to the lowest
     * position would be the one visible defect this mechanism can have that no solver would ever
     * report, because the vehicle would still move, just the wrong way.
     */
    private static void stepping() {
        System.out.println("=== a press moves one step, this way, and stops at the end ===");
        Suspension.Settings settings = new Suspension.Settings();
        int heights = Suspension.heightSteps(settings);
        int pitches = Suspension.pitchSteps(settings);
        int rolls = Suspension.rollSteps(settings);
        System.out.println("  " + heights + " height positions, " + pitches + " pitch positions, "
                + rolls + " roll position(s)");

        check("a range of 0.15 in steps of 0.05 is four positions",
                heights == 1 + Math.round(settings.max_rise / Suspension.HEIGHT_STEP));
        check("a range of 2 degrees in steps of half a degree is nine, and odd",
                pitches % 2 == 1 && pitches == 1 + 2 * Math.round(settings.max_pitch
                        / Suspension.PITCH_STEP));
        check("from the bottom, up", Suspension.step(0, heights, UP) == 1
                && Suspension.step(1, heights, UP) == 2);
        check("and from the top, down", Suspension.step(heights - 1, heights, DOWN) == heights - 2);
        check("but up at the top stays at the top, rather than wrapping to the bottom",
                Suspension.step(heights - 1, heights, UP) == heights - 1);
        check("and down at the bottom stays at the bottom",
                Suspension.step(0, heights, DOWN) == 0);
        check("the two axes are separate: the same press moves each of them by its own step count",
                Suspension.step(pitches - 1, pitches, DOWN) == pitches - 2
                        && Suspension.step(heights - 1, heights, DOWN) != pitches - 2);
        check("the ends of the range are the range: the lowest is the resting height on a vehicle "
                        + "that cannot drop, and the highest is the full rise",
                Suspension.height(settings, 0) == 0.0F
                        && Math.abs(Suspension.height(settings, heights - 1) - settings.max_rise)
                                < 1.0E-5F);
        check("and the middle of the pitch axis is level, with as much either side of it",
                Suspension.pitch(settings, Suspension.pitchRest(settings)) == 0.0F
                        && Math.abs(Suspension.pitch(settings, pitches - 1) - settings.max_pitch)
                                < 1.0E-5F
                        && Math.abs(Suspension.pitch(settings, 0) + settings.max_pitch) < 1.0E-5F);

        // The lateral axis is the one a vehicle may not have, so its zero is asserted first: a
        // single position is "this vehicle cannot lean", not a broken division.
        check("a vehicle with no lateral axis has one roll position and asks for no lean",
                rolls == 1 && Suspension.roll(settings, 0) == 0.0F
                        && Suspension.step(0, rolls, UP) == 0);
        Suspension.Settings leaner = new Suspension.Settings();
        leaner.max_roll = 4.0F;
        int leanSteps = Suspension.rollSteps(leaner);
        check("a range of 4 degrees in steps of half a degree is seventeen, and odd",
                leanSteps == 17 && leanSteps % 2 == 1);
        check("its ends are the full lean either way, and the middle is level",
                Math.abs(Suspension.roll(leaner, leanSteps - 1) - leaner.max_roll) < 1.0E-5F
                        && Math.abs(Suspension.roll(leaner, 0) + leaner.max_roll) < 1.0E-5F
                        && Suspension.roll(leaner, Suspension.rollRest(leaner)) == 0.0F);
        check("and it stops at its ends like the other axes",
                Suspension.step(leanSteps - 1, leanSteps, UP) == leanSteps - 1
                        && Suspension.step(0, leanSteps, DOWN) == 0);

        Suspension.Settings dropper = new Suspension.Settings();
        dropper.max_drop = 0.10F;
        check("a vehicle that can drop rests one step up from the bottom, so it does not sink on load",
                Suspension.heightRest(dropper) == 2
                        && Math.abs(Suspension.height(dropper,
                                Suspension.heightRest(dropper))) < 1.0E-5F);

        // The counts come out of a file a player may have edited, so the degenerate ones are the
        // ones that matter: a range of nothing must not divide by anything or index off the end.
        Suspension.Settings still = new Suspension.Settings();
        still.max_rise = 0.0F;
        still.max_drop = 0.0F;
        still.max_pitch = 0.0F;
        still.max_roll = 0.0F;
        check("no range at all is a single position on each axis",
                Suspension.heightSteps(still) == 1 && Suspension.pitchSteps(still) == 1
                        && Suspension.rollSteps(still) == 1);
        check("and asks for no movement at all, however it is pressed",
                Suspension.height(still, 0) == 0.0F && Suspension.pitch(still, 0) == 0.0F
                        && Suspension.roll(still, 0) == 0.0F
                        && Suspension.step(0, 1, UP) == 0 && Suspension.step(0, 1, DOWN) == 0);
        check("a step off the end of the range is brought back rather than trusted",
                Suspension.step(9, 5, UP) == 4 && Suspension.step(-4, 5, DOWN) == 0
                        && Suspension.height(still, 99) == 0.0F);
        check("and a direction that arrived mangled cannot lower the tank by accident",
                Suspension.step(2, 5, 0) == 3 && Suspension.step(2, 5, 99) == 3);
    }

    /**
     * The one formula: how much each wheel is asked for.
     *
     * <p>Written as relationships rather than as expected values, because the amounts are a choice
     * and the shape is not: every wheel gets the same height, the two ends get opposite pitch, the
     * two sides get opposite lean, the wheel at either balance point gets none, and the three axes
     * add.
     */
    private static void bias() {
        System.out.println();
        System.out.println("=== how the bias is shared out along the hull ===");
        Suspension.Settings settings = new Suspension.Settings();
        // A synthetic axle layout, in blocks fore and aft of the hull's middle and side to side,
        // and balance points that are deliberately not the middle: with unequal springs they move,
        // and the whole reason they are weighted is so a pitch or lean command does not also change
        // the height.
        float front = 2.5F;
        float back = -2.0F;
        float right = 1.5F;
        float left = -1.5F;
        float balanceX = 0.1F;
        float balanceZ = 0.3F;

        Suspension.Pose rest = new Suspension.Pose(Suspension.heightRest(settings),
                Suspension.pitchRest(settings), Suspension.rollRest(settings));
        Suspension.Pose high = new Suspension.Pose(Suspension.heightSteps(settings) - 1,
                Suspension.pitchRest(settings), Suspension.rollRest(settings));
        Suspension.Pose low = new Suspension.Pose(0, Suspension.pitchRest(settings),
                Suspension.rollRest(settings));
        Suspension.Pose noseUp = new Suspension.Pose(Suspension.heightRest(settings),
                Suspension.pitchSteps(settings) - 1, Suspension.rollRest(settings));
        Suspension.Pose noseDown = new Suspension.Pose(Suspension.heightRest(settings), 0,
                Suspension.rollRest(settings));

        // Every pose above is level in roll, so any x is the same wheel for the height and pitch
        // checks; 0 on the centre line is the simplest one to read.
        check("the resting pose asks for nothing, at every wheel",
                Suspension.bias(settings, rest, 0.0F, front, balanceX, balanceZ) == 0.0F
                        && Suspension.bias(settings, rest, 0.0F, 0.0F, balanceX, balanceZ) == 0.0F
                        && Suspension.bias(settings, rest, 0.0F, back, balanceX, balanceZ) == 0.0F);
        check("height is the same at the nose, the middle and the tail",
                Suspension.bias(settings, high, 0.0F, front, balanceX, balanceZ)
                        == Suspension.bias(settings, high, 0.0F, 0.0F, balanceX, balanceZ)
                        && Suspension.bias(settings, high, 0.0F, 0.0F, balanceX, balanceZ)
                                == Suspension.bias(settings, high, 0.0F, back, balanceX, balanceZ));
        check("and it is the full rise above the resting pose, not a fraction of it",
                Math.abs(Suspension.bias(settings, high, 0.0F, 0.0F, balanceX, balanceZ)
                        - settings.max_rise) < 1.0E-5F);
        check("a vehicle whose range is all rise never asks for a drop",
                Suspension.bias(settings, low, 0.0F, front, balanceX, balanceZ) == 0.0F);
        check("a wheel at the fore-and-aft balance point is not moved by pitch at all",
                Suspension.bias(settings, noseUp, 0.0F, balanceZ, balanceX, balanceZ) == 0.0F
                        && Suspension.bias(settings, noseDown, 0.0F, balanceZ, balanceX, balanceZ)
                                == 0.0F);
        // The sign convention is stated as "the two ends differ" rather than "the front is
        // positive": which end of the model is the nose is a property of the pack, and the constant
        // that reads it lives in the mixin. What is asserted here is the part that must hold
        // whatever that constant says.
        check("pitch moves the two ends in opposite directions",
                Suspension.bias(settings, noseUp, 0.0F, front, balanceX, balanceZ)
                        != Suspension.bias(settings, noseUp, 0.0F, back, balanceX, balanceZ)
                        && Math.signum(Suspension.bias(settings, noseUp, 0.0F, front,
                                        balanceX, balanceZ))
                                != Math.signum(Suspension.bias(settings, noseUp, 0.0F, back,
                                        balanceX, balanceZ)));
        check("and the two pitch directions are mirror images",
                Math.abs(Suspension.bias(settings, noseUp, 0.0F, front, balanceX, balanceZ)
                        + Suspension.bias(settings, noseDown, 0.0F, front, balanceX, balanceZ))
                        < 1.0E-6F
                        && Math.abs(Suspension.bias(settings, noseUp, 0.0F, back, balanceX, balanceZ)
                                + Suspension.bias(settings, noseDown, 0.0F, back, balanceX,
                                        balanceZ)) < 1.0E-6F);
        check("the tilt is the tangent of the angle over the distance from the balance point",
                Math.abs(Suspension.bias(settings, noseUp, 0.0F, front, balanceX, balanceZ)
                        - (float) Math.tan(Math.toRadians(settings.max_pitch))
                                * (front - balanceZ)) < 1.0E-5F);
        check("height and pitch add rather than override",
                Math.abs(Suspension.bias(settings,
                                new Suspension.Pose(Suspension.heightSteps(settings) - 1,
                                        Suspension.pitchSteps(settings) - 1,
                                        Suspension.rollRest(settings)),
                                0.0F, front, balanceX, balanceZ)
                        - (Suspension.height(settings, Suspension.heightSteps(settings) - 1)
                                + Suspension.bias(settings, noseUp, 0.0F, front, balanceX,
                                        balanceZ))) < 1.0E-5F);
        check("a vehicle with no lateral axis asks for no lean whatever side the wheel is on",
                Suspension.bias(settings, high, right, front, balanceX, balanceZ)
                        == Suspension.bias(settings, high, left, front, balanceX, balanceZ));
        check("no vehicle configured means no bias, whatever the pose",
                Suspension.bias(null, high, 0.0F, front, balanceX, balanceZ) == 0.0F
                        && Suspension.bias(settings, null, 0.0F, front, balanceX, balanceZ) == 0.0F);
        // The placeholder a vehicle with no settings resolves to is not a resting pose: it reads
        // (0, 0, 0), and zero is the bottom of the height range, the nose-down end of the pitch one
        // and the left-down end of the roll one. That distinction is the whole reason a missing
        // settings object is visible rather than silent - a vehicle sitting level by accident is a
        // bug nobody would ever report - so it is asserted here rather than left to the name of
        // the constant.
        check("and the no-pose placeholder is not what a configured vehicle rests at",
                Suspension.bias(settings, Suspension.Pose.NONE, 0.0F, front, balanceX, balanceZ)
                        != 0.0F);
        check("and the largest lift is the larger half of the range",
                Math.abs(Suspension.maxLift(settings)
                        - Math.max(settings.max_rise, settings.max_drop)) < 1.0E-6F);

        // --- the lateral axis -------------------------------------------------------------------
        Suspension.Settings leaner = new Suspension.Settings();
        leaner.max_roll = 4.0F;
        Suspension.Pose level = new Suspension.Pose(Suspension.heightRest(leaner),
                Suspension.pitchRest(leaner), Suspension.rollRest(leaner));
        Suspension.Pose leanRight = new Suspension.Pose(Suspension.heightRest(leaner),
                Suspension.pitchRest(leaner), Suspension.rollSteps(leaner) - 1);
        Suspension.Pose leanLeft = new Suspension.Pose(Suspension.heightRest(leaner),
                Suspension.pitchRest(leaner), 0);
        Suspension.Pose noseUpLeaner = new Suspension.Pose(Suspension.heightRest(leaner),
                Suspension.pitchSteps(leaner) - 1, Suspension.rollRest(leaner));

        check("a level lean asks for nothing, at either side",
                Suspension.bias(leaner, level, right, 0.0F, balanceX, 0.0F) == 0.0F
                        && Suspension.bias(leaner, level, left, 0.0F, balanceX, 0.0F) == 0.0F);
        check("a wheel at the lateral balance point is not moved by lean",
                Suspension.bias(leaner, leanRight, balanceX, 0.0F, balanceX, 0.0F) == 0.0F);
        // Positive roll is the right side down - see Suspension.Settings#max_roll - so the two
        // sides must take opposite signs, and the right one must be the negative.
        check("lean moves the two sides in opposite directions, the right side down first",
                Suspension.bias(leaner, leanRight, right, 0.0F, balanceX, 0.0F) < 0.0F
                        && Suspension.bias(leaner, leanRight, left, 0.0F, balanceX, 0.0F) > 0.0F);
        check("and the two lean directions are mirror images",
                Math.abs(Suspension.bias(leaner, leanRight, right, 0.0F, balanceX, 0.0F)
                        + Suspension.bias(leaner, leanLeft, right, 0.0F, balanceX, 0.0F))
                        < 1.0E-6F);
        check("the lean is the tangent of the angle over the distance from the cross balance point",
                Math.abs(Suspension.bias(leaner, leanRight, right, 0.0F, balanceX, 0.0F)
                        + (float) Math.tan(Math.toRadians(leaner.max_roll))
                                * (right - balanceX)) < 1.0E-5F);
        check("height, pitch and lean add rather than override",
                Math.abs(Suspension.bias(leaner,
                                new Suspension.Pose(Suspension.heightSteps(leaner) - 1,
                                        Suspension.pitchSteps(leaner) - 1,
                                        Suspension.rollSteps(leaner) - 1),
                                right, front, balanceX, balanceZ)
                        - (Suspension.height(leaner, Suspension.heightSteps(leaner) - 1)
                                + Suspension.bias(leaner, noseUpLeaner, right, front, balanceX,
                                        balanceZ)
                                + Suspension.bias(leaner, leanRight, right, back, balanceX,
                                        balanceZ))) < 1.0E-5F);
    }

    /**
     * The actuator: a press no longer jumps the springs to their new length in one tick.
     *
     * <p>What has to hold is small and entirely offline. The command must move toward the target
     * at the rate, stop exactly on it rather than overshoot, work in both directions, and never
     * take so long that the keys feel dead - the point of the feature is a hull that arrives, not
     * one that is still thinking about it. A zero rate is read as instant, which is the one
     * degenerate case a config edit can produce.
     *
     * <p>The rate is deliberately not "per tick per degree": both axes are rest-length bias, and
     * the pitch is a smaller bias at the wheels near the balance point. The same rate therefore
     * gives the same physical speed to both, which is what one pump does.
     */
    private static void actuation() {
        System.out.println();
        System.out.println("=== the actuator ramps rather than jumping ===");
        Suspension.Settings settings = new Suspension.Settings();
        float rate = settings.actuationRate;
        check("a configured rate is positive", rate > 0.0F);

        float at = 0.0F;
        int ticks = 0;
        boolean monotonic = true;
        boolean overshot = false;
        while (at < settings.max_rise && ticks < 200) {
            float next = Suspension.approach(at, settings.max_rise, rate);
            monotonic &= next >= at;
            overshot |= next > settings.max_rise + 1.0E-7F;
            at = next;
            ticks++;
        }
        check("the full rise is reached, without overshooting", at == settings.max_rise && !overshot);
        check("and it takes more than one tick but under a second and a half (" + ticks + " ticks)",
                ticks > 1 && ticks < 30);
        check("the ramp is monotonic", monotonic);

        float down = Suspension.approach(settings.max_rise, 0.0F, rate);
        check("it comes back down by the same step", down == settings.max_rise - rate);
        check("and a target already reached does not move",
                Suspension.approach(0.075F, 0.075F, rate) == 0.075F);
        check("a short final step lands exactly on the target, rather than a fraction past it",
                Suspension.approach(0.195F, 0.20F, rate) == 0.20F);
        check("a zero or negative rate is instant, not frozen - a hull must always be able to level",
                Suspension.approach(0.0F, 0.20F, 0.0F) == 0.20F
                        && Suspension.approach(0.20F, 0.0F, -1.0F) == 0.0F);
        check("and a NaN from nowhere cannot poison the rest length",
                Suspension.approach(Float.NaN, 0.1F, rate) == 0.1F);
    }

    /** Every line the mechanism can put on the screen has words in both languages. */
    private static void messages() {
        System.out.println();
        System.out.println("=== every line has words in both languages ===");
        Set<String> keys = new LinkedHashSet<>();
        // All three shapes, because the third number only appears on a vehicle that can lean and
        // the lean line is a key of its own: a missing translation would put the raw key on screen
        // exactly on the one vehicle whose new feature the line is describing.
        keysIn(Suspension.describe(true, true, 0.1F, -1.5F, 2.5F), keys);
        keysIn(Suspension.describe(true, false, 0.1F, -1.5F, 0.0F), keys);
        keysIn(Suspension.describe(false, false, 0.0F, 0.0F, 0.0F), keys);
        check("no message was built without a translation key", !keys.contains(null));
        check("the no-suspension line is one of them",
                keys.contains("message." + MOD_ID + ".suspension_none"));
        check("and the lean line, which only a vehicle with a lateral axis ever shows",
                keys.contains("message." + MOD_ID + ".suspension_set_roll"));

        Set<String> english = langKeys("en_us");
        Set<String> chinese = langKeys("zh_cn");
        check("both language files were found and parsed",
                !english.isEmpty() && !chinese.isEmpty());
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            check("en_us has " + key, english.contains(key));
            check("zh_cn has " + key, chinese.contains(key));
        }
        System.out.println("  " + keys.size() + " key(s): " + keys);
    }

    /**
     * The block survives a trip through the file, and arrives on a vehicle that never had it.
     *
     * <p>The first is what the game reads: a field the writer forgets comes back as its default, and
     * a tuned suspension would silently reset every time the config was rewritten. The second is the
     * migration: a file written before this block existed has no key for it, and {@code inherit} is
     * the only thing that adds one.
     */
    private static void roundTrip() throws Exception {
        System.out.println();
        System.out.println("=== the block survives the config file ===");
        Method defaultProfile = ArmorConfig.class.getDeclaredMethod("defaultProfile");
        defaultProfile.setAccessible(true);
        ArmorConfig.Profile profile = (ArmorConfig.Profile) defaultProfile.invoke(null);

        Suspension.Settings configured = profile.suspensions.get(VEHICLE);
        check(VEHICLE + " has a suspension configured", configured != null);
        if (configured == null) {
            return;
        }
        configured.max_rise = 0.123F;
        configured.max_pitch = 4.5F;

        String json = ArmorConfig.configWriter().toJson(profile);
        ArmorConfig.Profile back = ArmorConfig.configReader().fromJson(json,
                ArmorConfig.Profile.class);
        Suspension.Settings read = back.suspensions == null ? null : back.suspensions.get(VEHICLE);
        check("the tuned range comes back",
                read != null && read.max_rise == 0.123F && read.max_pitch == 4.5F);
        check("and a vehicle that has none comes back without one",
                back.suspensions != null && back.suspensions.get("vvp_ywzj:t64") == null);

        // The point of the move, stated as the thing that would be false if it had stayed: the
        // tracked vehicles have a suspension and no armour block to hang one on.
        check("all nine vehicles the addon gives a suspension to are in the defaults",
                profile.suspensions.size() == 9
                        && profile.suspensions.containsKey("vvp_ywzj:bmp_3")
                        && profile.suspensions.containsKey("vvp_ywzj:ajax")
                        && profile.suspensions.containsKey(K2)
                        && profile.suspensions.containsKey(TYPE10));
        check("and the tracked ones without armour need no armour entry to have one",
                !profile.vehicles.containsKey("vvp_ywzj:bmp_3")
                        && !profile.vehicles.containsKey("vvp_ywzj:puma"));
        Suspension.Settings k2 = profile.suspensions.get(K2);
        check("the K2 is configured with the ranged hydropneumatic block",
                k2 != null && Math.abs(k2.max_rise - 0.20F) < 1.0E-6F
                        && Math.abs(k2.max_drop - 0.20F) < 1.0E-6F
                        && Math.abs(k2.max_pitch - 5.0F) < 1.0E-6F
                        // The lateral axis its in-arm units give it, shared with the Type 10 since
                        // v63 - see the check below.
                        && Math.abs(k2.max_roll - 4.0F) < 1.0E-6F
                        && k2.actuationRate > 0.0F
                        // Its support cubes end at the wheel bottom, 0.0654 below the link centres;
                        // without this the link-model track's lower run is in the ground.
                        && Math.abs(k2.trackCenterOffset - 0.0654F) < 1.0E-4F);
        // The Type 10 joined at v60. Same height bracket and pitch as the K2, the same four-degree
        // lateral axis since v63, and its own measured track offset (0.8686 model units = 0.0543
        // blocks).
        Suspension.Settings type10 = profile.suspensions.get(TYPE10);
        check("the Type 10 is configured with the ranged hydropneumatic block",
                type10 != null && Math.abs(type10.max_rise - 0.20F) < 1.0E-6F
                        && Math.abs(type10.max_drop - 0.20F) < 1.0E-6F
                        && Math.abs(type10.max_pitch - 5.0F) < 1.0E-6F
                        && Math.abs(type10.max_roll - 4.0F) < 1.0E-6F
                        && Math.abs(type10.trackCenterOffset - 0.0543F) < 1.0E-4F);
        // The request was "the same unit as the K2", so the shared axis is asserted as a shared
        // figure rather than as two independent fours: a later edit that opens one of them alone
        // has moved them apart even if both still look reasonable.
        check("and the Type 10 carries the K2's lateral axis exactly",
                type10.max_roll == k2.max_roll);

        Method upgrade = ArmorConfig.class.getDeclaredMethod("upgrade", ArmorConfig.Profile.class);
        upgrade.setAccessible(true);

        // v43 -> v44, the shape a file written before the move is in: the block is inside the
        // vehicle and there is no map at all. Two different things have to happen, and a migration
        // that did only the second would leave the Dragoon as the only vehicle in the game whose
        // keys did anything.
        ArmorConfig.Profile older = ArmorConfig.configReader().fromJson(json,
                ArmorConfig.Profile.class);
        older.suspensions = null;
        Suspension.Settings theirs = new Suspension.Settings();
        theirs.max_rise = 0.07F;
        older.vehicles.get(VEHICLE).suspension = theirs;
        upgrade.invoke(null, older);
        Suspension.Settings moved = older.suspensions == null ? null : older.suspensions.get(VEHICLE);
        check("a v43 file's per-vehicle block is carried into the map, not dropped",
                moved != null && moved.max_rise == 0.07F);
        check("and the field it came out of is cleared, so the writer stops emitting it",
                older.vehicles.get(VEHICLE).suspension == null);
        check("while the eight vehicles that file never had are added",
                older.suspensions != null && older.suspensions.containsKey("vvp_ywzj:challenger")
                        && older.suspensions.containsKey(K2)
                        && older.suspensions.containsKey(TYPE10));
        check("and a vehicle the addon does not give one to still has none",
                older.suspensions != null && older.suspensions.get("vvp_ywzj:t64") == null);

        // The other shape: a map that is there but empty. The fill keys on what the map does not
        // contain rather than on the map being absent, so both arrive at the same place.
        ArmorConfig.Profile emptied = ArmorConfig.configReader().fromJson(json,
                ArmorConfig.Profile.class);
        emptied.suspensions = new java.util.LinkedHashMap<>();
        upgrade.invoke(null, emptied);
        check("an empty map is filled rather than left empty",
                emptied.suspensions != null && emptied.suspensions.size() == 9);

        // v54 -> v55: a file written before the offset and the five-degree pitch has the K2
        // block at 2.0 with no trackCenterOffset. This is the exact shape of the shipped v54
        // config, and the reason the keys stopped at +-2 while the defaults said five.
        ArmorConfig.Profile v54 = ArmorConfig.configReader().fromJson(json,
                ArmorConfig.Profile.class);
        v54.config_version = 54;
        v54.suspensions.get(K2).max_pitch = 2.0F;
        v54.suspensions.get(K2).trackCenterOffset = 0.0F;
        v54.suspensions.get(K2).max_roll = 0.0F;
        upgrade.invoke(null, v54);
        Suspension.Settings opened = v54.suspensions.get(K2);
        check("a v54 K2 block opens to 5 degrees and gains its 0.0654 track offset",
                opened.max_pitch == 5.0F
                        && Math.abs(opened.trackCenterOffset - 0.0654F) < 1.0E-4F);
        check("and the same upgrade gives it the four-degree lateral axis",
                opened.max_roll == 4.0F);

        // v55 -> v56 is the other shape: the pitch and the offset are already there, and the only
        // thing missing is the field Gson had never seen. It must arrive at four degrees, and a
        // range the player has set themselves must survive.
        ArmorConfig.Profile v55 = ArmorConfig.configReader().fromJson(json,
                ArmorConfig.Profile.class);
        v55.config_version = 55;
        v55.suspensions.get(K2).max_roll = 0.0F;
        upgrade.invoke(null, v55);
        check("a v55 K2 block gains its lateral axis without touching the other figures",
                v55.suspensions.get(K2).max_roll == 4.0F
                        && v55.suspensions.get(K2).max_pitch == 5.0F
                        && Math.abs(v55.suspensions.get(K2).trackCenterOffset - 0.0654F) < 1.0E-4F);

        ArmorConfig.Profile handSet = ArmorConfig.configReader().fromJson(json,
                ArmorConfig.Profile.class);
        handSet.config_version = 55;
        handSet.suspensions.get(K2).max_roll = 2.5F;
        upgrade.invoke(null, handSet);
        check("while a lateral range the player typed is left alone",
                handSet.suspensions.get(K2).max_roll == 2.5F);
    }

    /** Both mixins have to be listed, or the classes ship and are never applied to anything. */
    private static void mixinsListed() {
        System.out.println();
        System.out.println("=== the mixins are registered ===");
        String json = resource(MOD_ID + ".mixins.json");
        check("the mixins config was found", json != null);
        if (json == null) {
            return;
        }
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Set<String> listed = new LinkedHashSet<>();
        root.getAsJsonArray("mixins").forEach(element -> listed.add(element.getAsString()));
        for (String mixin : MIXINS) {
            check(mixin + " is listed in " + MOD_ID + ".mixins.json", listed.contains(mixin));
        }
        check("and the config is required, so a failed injection crashes instead of doing nothing",
                root.get("required").getAsBoolean()
                        && root.getAsJsonObject("injectors").get("defaultRequire").getAsInt() >= 1);
        System.out.println("  mixins: " + listed);
    }

    /**
     * The settings against the vehicle's own springs and the geometry of its hull.
     *
     * <p>This is the check that has no other symptom. The addon lengthens each wheel's rest length,
     * and a spring can only be lengthened while its contact probe still reaches the terrain - past
     * that the wheel hangs in the air. In game that is not a number that looks wrong; it is a
     * vehicle that drives strangely, or one whose suspension "feels floaty", which is why the figure
     * is re-derived here from the pack rather than trusted to a comment.
     *
     * <p>Two numbers have to fit together, and both come from the pack:
     *
     * <ul>
     *   <li>The largest bias any one wheel is asked for: the rise, plus the pitch's share, which is
     *       the tangent of the angle times how far that wheel is from the hull's fore-and-aft
     *       balance point, plus the lean's share across the hull's lateral one.</li>
     *   <li>The droop the springs have left: the pack's {@code max_droop}, less the centimetre the
     *       base mod calibrates in as the resting droop.</li>
     * </ul>
     *
     * <p>It also answers what a config edit can get wrong in the other direction: a vehicle given a
     * suspension block whose model has no suspension parts at all would answer both keys and move
     * nothing, because there is nothing on the model for the mechanism to lengthen.
     */
    private static void travel() throws Exception {
        System.out.println();
        System.out.println("=== the steps against the springs the pack ships ===");
        Method defaultProfile = ArmorConfig.class.getDeclaredMethod("defaultProfile");
        defaultProfile.setAccessible(true);
        ArmorConfig.Profile profile = (ArmorConfig.Profile) defaultProfile.invoke(null);
        List<Path> packs = packs();

        // The five tanks converted to the link belt on 2026-11 are not in the suspension map (they
        // have torsion bars, not a ride-height control), so the loop below never reaches them and
        // their visible-track check is its own pass.
        for (String beltId : BELT_TANKS) {
            String beltNs = ns(beltId);
            String beltShort = beltId.substring(beltId.indexOf(':') + 1);
            try (ZipFile beltPack = open(packs,
                    "data/" + beltNs + "/vehicles/" + beltShort + ".json")) {
                if (beltPack == null) {
                    check(beltId + ": the pack is installed", false);
                    continue;
                }
                check(beltId + ": the visible track is its link model",
                        beltReady(beltPack, beltId));
                if ("armor_pioneer:leopard2a4".equals(beltId)) {
                    check(beltId + ": the wheels spin about their axles",
                            leopardWheelsSpin(beltPack, beltId));
                }
            }
        }

        int configured = 0;
        for (var entry : profile.suspensions.entrySet()) {
            configured++;
            Suspension.Settings settings = entry.getValue();
            String id = entry.getKey();
            String namespace = ns(id);
            String short_ = id.substring(id.indexOf(':') + 1);
            String vehiclePath = "data/" + namespace + "/vehicles/" + short_ + ".json";
            String modelPath = "data/" + namespace + "/models/bedrock/vehicle/" + short_
                    + ".structure.json";
            try (ZipFile pack = open(packs, vehiclePath)) {
                if (pack == null) {
                    check(id + ": its vehicle json was found in an installed pack", false);
                    continue;
                }
                JsonObject vehicle = JsonParser.parseString(read(pack, vehiclePath))
                        .getAsJsonObject();
                JsonObject model = pack.getEntry(modelPath) == null ? null
                        : JsonParser.parseString(read(pack, modelPath)).getAsJsonObject();

                int springs = 0;
                double droopLeft = Double.POSITIVE_INFINITY;
                double weightedZ = 0.0;
                double weightedX = 0.0;
                double total = 0.0;
                boolean reachGuaranteed = true;
                Double gearBottom = null;
                List<double[]> units = new java.util.ArrayList<>();
                for (var element : vehicle.getAsJsonArray("parts")) {
                    JsonObject part = element.getAsJsonObject();
                    if (!part.has("type") || !SUSPENSION_PART.equals(part.get("type").getAsString())) {
                        continue;
                    }
                    springs++;
                    // What is left of this unit's droop once the base mod has calibrated in the
                    // resting centimetre - see calibrateRestLengths: `ground -= 0.01`, and the
                    // lowest wheel ends up drooping exactly that much at rest.
                    double droop = part.has("max_droop") ? part.get("max_droop").getAsDouble() : 0.0;
                    droopLeft = Math.min(droopLeft, droop - REST_DROOP);
                    // Whether that 0.1, or whatever the pack now says, is really the travel. The
                    // probe's far end is `min(max_droop, rest_length + max_extension)`, and
                    // `rest_length` is recomputed at init by calibrateRestLengths to whatever holds
                    // the vehicle's weight - a figure this tool cannot derive. With
                    // `max_extension >= max_droop` the first branch always wins and the travel is
                    // exactly max_droop, which is what the budget below assumes; otherwise the
                    // budget depends on a number neither side can read, and being wrong about it
                    // hangs the wheels in the air rather than reporting anything.
                    double extension = part.has("max_extension")
                            ? part.get("max_extension").getAsDouble() : 0.0;
                    if (extension < droop) {
                        reachGuaranteed = false;
                    }
                    double stiffness = part.has("spring_stiffness")
                            ? part.get("spring_stiffness").getAsDouble() : 0.0;
                    String bone = part.has("structure_bone")
                            ? part.get("structure_bone").getAsString() : null;
                    double z = zOf(model, bone);
                    double x = xOf(model, bone);
                    units.add(new double[]{stiffness, z, x});
                    weightedZ += stiffness * z;
                    weightedX += stiffness * x;
                    total += stiffness;
                    Double bottom = lowestY(model, bone);
                    if (bottom != null && (gearBottom == null || bottom < gearBottom)) {
                        gearBottom = bottom;
                    }
                }
                check(id + " has springs for the mechanism to work on (" + springs + " units)",
                        springs > 0);
                if (springs == 0) {
                    continue;
                }
                check(id + ": the pack's max_extension is at least its max_droop on every unit, "
                                + "so the travel really is max_droop - run add_suspension.ps1",
                        reachGuaranteed);
                // A link-model track follows the springs its suspensions table names, so a track
                // bone on the other side of the hull from those springs is not a cosmetic mix-up:
                // with pitch and height every wheel moves together and it is invisible, but with a
                // lean one track would follow the wrong side. The K2's structure is the model where
                // this comparison is decidable from the file; see the method.
                if (K2.equals(id)) {
                    check(id + ": each track path is on the side of the springs it is bound to",
                            k2TrackSidesMatch(vehicle, model));
                }
                // The springs can be perfect and still be invisible. Both link-model tanks had
                // this defect at one point: the floating path was computed, the hull and wheels
                // moved, and the visible track stayed on the display model's fixed loop.
                if (K2.equals(id) || TYPE10.equals(id)) {
                    check(id + ": the visible track is the link model, not the display loop",
                            linkTrackReady(pack, id));
                }

                double balanceZ = total > 0.0 ? weightedZ / total : 0.0;
                double balanceX = total > 0.0 ? weightedX / total : 0.0;
                double furthestZ = 0.0;
                double furthestX = 0.0;
                for (double[] unit : units) {
                    furthestZ = Math.max(furthestZ, Math.abs(unit[1] - balanceZ));
                    furthestX = Math.max(furthestX, Math.abs(unit[2] - balanceX));
                }
                // The two directions are bounded by different things and are checked apart. Raising
                // the hull lengthens the springs until their probes run out of reach: that is the
                // droop budget, and both the pitch and the lean spend it too - at the corner
                // furthest from the balance point on each axis, which is the same wheel on a
                // symmetric layout. Lowering it compresses them, and is stopped by the hull meeting
                // the ground rather than by any spring - so it is measured against the vehicle's
                // own daylight.
                double rise = Math.abs(settings.max_rise)
                        + Suspension.maxPitchTangent(settings) * furthestZ
                        + Suspension.maxRollTangent(settings) * furthestX;
                check(id + ": its largest rise (" + round(rise) + " blocks, " + springs
                                + " wheels, pitch over " + round(furthestZ) + " and lean over "
                                + round(furthestX) + ") fits the droop the springs have left ("
                                + round(droopLeft) + ")",
                        rise <= droopLeft);

                Double hullBottom = lowestY(model, "main_structure");
                if (hullBottom != null && gearBottom != null) {
                    double clearance = (hullBottom - gearBottom) / 16.0;
                    check(id + ": its drop (" + round(settings.max_drop) + " blocks) clears the "
                                    + round(clearance) + " blocks the hull stands above the running gear",
                            settings.max_drop <= clearance);
                    System.out.printf("  %s: %d springs, balance z %.3f / x %.3f, furthest %.3f / "
                                    + "%.3f, droop left %.3f, rise %.3f, drop %.3f of %.3f%n",
                            id, springs, balanceZ, balanceX, furthestZ, furthestX, droopLeft,
                            rise, settings.max_drop, clearance);
                } else {
                    System.out.printf("  %s: %d springs, balance z %.3f / x %.3f, furthest %.3f / "
                                    + "%.3f, droop left %.3f, rise %.3f (no hull clearance in the "
                                    + "model)%n",
                            id, springs, balanceZ, balanceX, furthestZ, furthestX, droopLeft, rise);
                }
            }
        }
        check("at least one vehicle is configured with a suspension", configured > 0);
    }

    /**
     * @return the lowest point of a bone's cubes in the structure model, in sixteenths of a block,
     *         or {@code null} when the model, the bone or its cubes are not there.
     *
     * <p>Model units, not blocks: the model's own grid is sixteenths, which is the conversion
     * {@code VehicleScaffold} makes everywhere it measures anything. The caller divides.
     */
    private static Double lowestY(JsonObject model, String bone) {
        if (model == null || bone == null || !model.has("minecraft:geometry")) {
            return null;
        }
        for (var element : model.getAsJsonArray("minecraft:geometry")) {
            JsonObject geometry = element.getAsJsonObject();
            if (!geometry.has("bones")) {
                continue;
            }
            for (var boneElement : geometry.getAsJsonArray("bones")) {
                JsonObject candidate = boneElement.getAsJsonObject();
                if (!candidate.has("name") || !bone.equals(candidate.get("name").getAsString())) {
                    continue;
                }
                if (!candidate.has("cubes")) {
                    return null;
                }
                Double lowest = null;
                for (var cubeElement : candidate.getAsJsonArray("cubes")) {
                    double y = cubeElement.getAsJsonObject().getAsJsonArray("origin").get(1)
                            .getAsDouble();
                    lowest = lowest == null ? y : Math.min(lowest, y);
                }
                return lowest;
            }
        }
        return null;
    }

    /**
     * The droop a wheel has left at rest, in blocks.
     *
     * <p>The base mod calibrates each spring so the vehicle sits at its static height, and it does
     * that by lowering the ground it measures against by a centimetre first - so the lowest wheel is
     * already drooping this much and cannot droop it again. Nothing in the addon can read that, and
     * it is small enough that being wrong by it costs a hundredth of a block.
     */
    private static final double REST_DROOP = 0.01;

    /**
     * @return the fore-aft position of a bone in the structure model, in blocks, or 0 when it is not
     *         there.
     *
     * <p>The model's units are sixteenths of a block - the same conversion {@code VehicleScaffold}
     * makes everywhere it measures anything, and the reason the first version of this check compared
     * a figure in blocks against one in model units and reported a wheel two and a half blocks out
     * on a hull four blocks long.
     */
    private static double zOf(JsonObject model, String bone) {
        if (model == null || bone == null) {
            return 0.0;
        }
        for (var element : model.getAsJsonArray("minecraft:geometry")) {
            JsonObject geometry = element.getAsJsonObject();
            if (!geometry.has("bones")) {
                continue;
            }
            for (var boneElement : geometry.getAsJsonArray("bones")) {
                JsonObject candidate = boneElement.getAsJsonObject();
                if (candidate.has("name") && bone.equals(candidate.get("name").getAsString())
                        && candidate.has("pivot")) {
                    return candidate.getAsJsonArray("pivot").get(2).getAsDouble() / 16.0;
                }
            }
        }
        return 0.0;
    }

    /**
     * @return the left-and-right position of a bone in the structure model, in blocks, or 0 when it
     *         is not there.
     *
     * <p>Only the <b>absolute</b> distance from the lateral balance point is used by the budget, so
     * the model's x mirror does not matter here: every wheel's x is negated together, and
     * {@code |(-x) - (-balance)|} is the same number as {@code |x - balance|}. The sign itself is the
     * game's own convention - see {@code Suspension.Settings#max_roll} - and is not this tool's to
     * assert.
     */
    private static double xOf(JsonObject model, String bone) {
        if (model == null || bone == null) {
            return 0.0;
        }
        for (var element : model.getAsJsonArray("minecraft:geometry")) {
            JsonObject geometry = element.getAsJsonObject();
            if (!geometry.has("bones")) {
                continue;
            }
            for (var boneElement : geometry.getAsJsonArray("bones")) {
                JsonObject candidate = boneElement.getAsJsonObject();
                if (candidate.has("name") && bone.equals(candidate.get("name").getAsString())
                        && candidate.has("pivot")) {
                    return candidate.getAsJsonArray("pivot").get(0).getAsDouble() / 16.0;
                }
            }
        }
        return 0.0;
    }

    /**
     * @return whether the pack's display side is set up for 0.6.2's link-model track.
     *
     * <p>Three files have to agree, and any one of them left behind makes the suspension
     * invisible below the hull:
     *
     * <ul>
     *   <li>{@code track_config.model} points at a link model that exists. The template value
     *       {@code effect/none} resolves to no model, so {@code TrackUnit} computes the floating
     *       path and renders nothing.</li>
     *   <li>The display model no longer carries the old {@code vt5_tread_*} cubes. That loop is
     *       baked into the display model and knows nothing about the springs; drawn over the link
     *       model it leaves the visible links on the fixed loop.</li>
     *   <li>The controller no longer runs a {@code track_animation} node, which is what drives
     *       those display cubes, and the two legacy {@code left_track}/{@code right_track} names are
     *       gone so {@code TrackConfig.isValid()} is false.</li>
     * </ul>
     *
     * <p>This was the Type 10's reported defect: the hull and wheels moved with the suspension, the
     * path was recomputed, and the track visually stayed where it was. The K2 already passes; both
     * are checked because both were converted the same way.
     */
    private static boolean linkTrackReady(ZipFile pack, String id) throws Exception {
        String namespace = ns(id);
        String short_ = id.substring(id.indexOf(':') + 1);
        String displayPath = "assets/" + namespace + "/display/vehicle/" + short_ + ".json";
        if (pack.getEntry(displayPath) == null) {
            return false;
        }
        JsonObject display = JsonParser.parseString(read(pack, displayPath)).getAsJsonObject();
        if (!display.has("track_config")) {
            return false;
        }
        JsonObject track = display.getAsJsonObject("track_config");
        if (!track.has("model") || track.has("left_track") || track.has("right_track")) {
            return false;
        }
        String model = track.get("model").getAsString();
        String linkPath = "assets/" + model.replace(":", "/models/bedrock/") + ".json";
        if (pack.getEntry(linkPath) == null) {
            return false;
        }
        JsonObject link = JsonParser.parseString(read(pack, linkPath)).getAsJsonObject();
        boolean hasLinkCube = false;
        for (var geometry : link.getAsJsonArray("minecraft:geometry")) {
            for (var bone : geometry.getAsJsonObject().getAsJsonArray("bones")) {
                JsonObject candidate = bone.getAsJsonObject();
                if (candidate.has("name")
                        && "track_link_0".equals(candidate.get("name").getAsString())
                        && candidate.has("cubes")
                        && !candidate.getAsJsonArray("cubes").isEmpty()) {
                    hasLinkCube = true;
                }
            }
        }
        if (!hasLinkCube) {
            return false;
        }

        String entityPath = "assets/" + namespace + "/models/bedrock/entity/" + short_ + ".json";
        if (pack.getEntry(entityPath) == null) {
            return false;
        }
        JsonObject entity = JsonParser.parseString(read(pack, entityPath)).getAsJsonObject();
        for (var geometry : entity.getAsJsonArray("minecraft:geometry")) {
            for (var bone : geometry.getAsJsonObject().getAsJsonArray("bones")) {
                JsonObject candidate = bone.getAsJsonObject();
                if (candidate.has("name")
                        && candidate.get("name").getAsString().startsWith("vt5_tread_")
                        && candidate.has("cubes")
                        && !candidate.getAsJsonArray("cubes").isEmpty()) {
                    return false;
                }
            }
        }

        String controllerPath = "assets/" + namespace + "/animation_controllers/" + short_
                + "_controller.json";
        if (pack.getEntry(controllerPath) == null) {
            return false;
        }
        return !read(pack, controllerPath).contains("\"track_animation\"");
    }

    /**
     * The same guard for the five tanks converted in 2026-11, which keep the legacy animation.
     *
     * <p>{@link #linkTrackReady} also demands that the controller drop its {@code track_animation}
     * node and that {@code track_config} drop {@code left_track}/{@code right_track}. These five
     * keep both on purpose: the wheel-spin bindings still read their degrees from that animation
     * instance, and removing it would silently change which displacement the wheels integrate.
     * What is checked here is the defect that made the K2 and Type 10 look broken - a link belt
     * drawn over a second, static tread loop - plus the obvious first one: no {@code effect/none},
     * whose link model resolves to nothing and renders no track at all.
     *
     * <p>The display model is read from the display file's own {@code model} key rather than from
     * the vehicle id: the Leopard 2A4's is {@code entity/leopard2a4_user}.
     */
    private static boolean beltReady(ZipFile pack, String id) throws Exception {
        String namespace = ns(id);
        String shortName = id.substring(id.indexOf(':') + 1);
        String displayPath = "assets/" + namespace + "/display/vehicle/" + shortName + ".json";
        if (pack.getEntry(displayPath) == null) {
            return false;
        }
        JsonObject display = JsonParser.parseString(read(pack, displayPath)).getAsJsonObject();
        if (!display.has("track_config")) {
            return false;
        }
        JsonObject track = display.getAsJsonObject("track_config");
        if (!track.has("model")) {
            return false;
        }
        String model = track.get("model").getAsString();
        if (model.endsWith("/none")) {
            return false;
        }
        String linkPath = "assets/" + model.replace(":", "/models/bedrock/") + ".json";
        if (pack.getEntry(linkPath) == null) {
            return false;
        }
        JsonObject link = JsonParser.parseString(read(pack, linkPath)).getAsJsonObject();
        boolean hasLinkCube = false;
        for (var geometry : link.getAsJsonArray("minecraft:geometry")) {
            for (var bone : geometry.getAsJsonObject().getAsJsonArray("bones")) {
                JsonObject candidate = bone.getAsJsonObject();
                if (candidate.has("name")
                        && "track_link_0".equals(candidate.get("name").getAsString())
                        && candidate.has("cubes")
                        && !candidate.getAsJsonArray("cubes").isEmpty()) {
                    hasLinkCube = true;
                }
            }
        }
        if (!hasLinkCube || !display.has("model")) {
            return false;
        }
        String entityPath = "assets/" + display.get("model").getAsString()
                .replace(":", "/models/bedrock/") + ".json";
        if (pack.getEntry(entityPath) == null) {
            return false;
        }
        JsonObject entity = JsonParser.parseString(read(pack, entityPath)).getAsJsonObject();
        for (var geometry : entity.getAsJsonArray("minecraft:geometry")) {
            for (var bone : geometry.getAsJsonObject().getAsJsonArray("bones")) {
                JsonObject candidate = bone.getAsJsonObject();
                if (candidate.has("name")
                        && candidate.get("name").getAsString().startsWith("vt5_tread_")
                        && candidate.has("cubes")
                        && !candidate.getAsJsonArray("cubes").isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The Leopard's wheels: the spin bindings are back, and every bound bone's pivot is its axle.
     *
     * <p>That model shipped with 14 road wheels and the two 4th return rollers whose bone pivots
     * were mirrored in x and z against their own cubes; spinning them about those pivots threw the
     * wheel ~6 blocks away, so the bindings were removed when the model was first integrated. The
     * pivots are now at the axles and the bindings are back. Both halves fail silently in game in
     * different ways - no bindings means wheels that never turn, bad pivots means wheels that fly -
     * so both are asserted: a {@code left_wheel_rotation}/{@code right_wheel_rotation} pair exists,
     * every wheel and roller bone is bound, and each pivot is within 2.5 model units of its cube
     * centre. The tolerance is not a fudge around a good measurement: the idler's and the
     * sprocket's boxes carry a protruding part, so their pivots sit 1.32 and 1.93 units off the
     * box centre even in the K2 - which spins correctly. A mirrored pivot is 50 to 120 units out.
     */
    private static boolean leopardWheelsSpin(ZipFile pack, String id) throws Exception {
        String namespace = ns(id);
        String shortName = id.substring(id.indexOf(':') + 1);
        String displayPath = "assets/" + namespace + "/display/vehicle/" + shortName + ".json";
        String controllerPath = "assets/" + namespace + "/animation_controllers/" + shortName
                + "_controller.json";
        if (pack.getEntry(displayPath) == null || pack.getEntry(controllerPath) == null) {
            return false;
        }
        JsonObject controller = JsonParser.parseString(read(pack, controllerPath)).getAsJsonObject();
        JsonObject binding = controller.getAsJsonObject("graph")
                .getAsJsonArray("inputs").get(0).getAsJsonObject()
                .getAsJsonObject("base").getAsJsonArray("inputs").get(0).getAsJsonObject();
        if (!binding.has("special_bindings")) {
            return false;
        }
        java.util.Set<String> bound = new java.util.HashSet<>();
        boolean left = false;
        boolean right = false;
        for (var group : binding.getAsJsonArray("special_bindings")) {
            JsonObject entry = group.getAsJsonObject();
            String source = entry.get("source").getAsString();
            left |= "left_wheel_rotation".equals(source);
            right |= "right_wheel_rotation".equals(source);
            for (var bone : entry.getAsJsonArray("bones")) {
                bound.add(bone.getAsString());
            }
        }
        if (!left || !right) {
            return false;
        }

        JsonObject display = JsonParser.parseString(read(pack, displayPath)).getAsJsonObject();
        String entityPath = "assets/" + display.get("model").getAsString()
                .replace(":", "/models/bedrock/") + ".json";
        if (pack.getEntry(entityPath) == null) {
            return false;
        }
        JsonObject entity = JsonParser.parseString(read(pack, entityPath)).getAsJsonObject();
        for (var geometry : entity.getAsJsonArray("minecraft:geometry")) {
            for (var bone : geometry.getAsJsonObject().getAsJsonArray("bones")) {
                JsonObject candidate = bone.getAsJsonObject();
                String name = candidate.get("name").getAsString();
                if (!(name.startsWith("vt5_wheel") || name.startsWith("vt5_roller"))
                        || !candidate.has("cubes")
                        || candidate.getAsJsonArray("cubes").isEmpty()) {
                    continue;
                }
                if (!bound.contains(name)) {
                    return false;
                }
                double[] pivot = {
                        candidate.getAsJsonArray("pivot").get(0).getAsDouble(),
                        candidate.getAsJsonArray("pivot").get(1).getAsDouble(),
                        candidate.getAsJsonArray("pivot").get(2).getAsDouble()};
                double[] lo = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
                double[] hi = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
                for (var cube : candidate.getAsJsonArray("cubes")) {
                    JsonObject c = cube.getAsJsonObject();
                    for (int axis = 0; axis < 3; axis++) {
                        double o = c.getAsJsonArray("origin").get(axis).getAsDouble();
                        double s = c.getAsJsonArray("size").get(axis).getAsDouble();
                        lo[axis] = Math.min(lo[axis], o);
                        hi[axis] = Math.max(hi[axis], o + s);
                    }
                }
                double sum = 0.0;
                for (int axis = 0; axis < 3; axis++) {
                    sum += Math.pow(pivot[axis] - (lo[axis] + hi[axis]) / 2.0, 2);
                }
                if (Math.sqrt(sum) > 2.5) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * @return whether the K2's two track parts sit on the same side as the springs their
     *         {@code suspensions} table binds them to.
     *
     * <p>Pack-specific on purpose. This is a sign comparison in the <b>structure file's</b> own
     * frame, and it is only meaningful where the track bone and the spring bones are file-level
     * roots carrying no rotation - which is the K2's model. The vvp models put their springs on the
     * other raw side and hang both track paths off one {@code tracks} bone, so the same comparison
     * would report a defect that is not one.
     *
     * <p>The failure it guards was real: the K2's track path was first written from the
     * <b>display</b> model's loop, and the display x is mirrored relative to the structure's. Both
     * track parts therefore ended up on the opposite side of the hull from the springs they were
     * bound to. Height and pitch move every wheel together and hid it; a lean would not have - the
     * left-hand track would have followed the right-hand springs.
     */
    private static boolean k2TrackSidesMatch(JsonObject vehicle, JsonObject model) {
        if (model == null) {
            return false;
        }
        java.util.Map<String, String> boneOf = new java.util.HashMap<>();
        for (var element : vehicle.getAsJsonArray("parts")) {
            JsonObject part = element.getAsJsonObject();
            if (part.has("id") && part.has("structure_bone")) {
                boneOf.put(part.get("id").getAsString(), part.get("structure_bone").getAsString());
            }
        }
        for (var element : vehicle.getAsJsonArray("parts")) {
            JsonObject part = element.getAsJsonObject();
            if (!part.has("type")
                    || !"ywzj_vehicle:track".equals(part.get("type").getAsString())) {
                continue;
            }
            String trackBone = part.has("structure_bone")
                    ? part.get("structure_bone").getAsString() : null;
            if (trackBone == null || !part.has("suspensions")) {
                continue;
            }
            for (var entry : part.getAsJsonObject("suspensions").entrySet()) {
                for (var spring : entry.getValue().getAsJsonArray()) {
                    String springBone = boneOf.get(spring.getAsString());
                    Double trackX = rawPivotX(model, trackBone);
                    Double springX = rawPivotX(model, springBone);
                    if (trackX == null || springX == null || (trackX > 0) != (springX > 0)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** @return a bone's raw pivot x in the structure file, or {@code null} when it is not there. */
    private static Double rawPivotX(JsonObject model, String bone) {
        if (model == null || bone == null) {
            return null;
        }
        for (var element : model.getAsJsonArray("minecraft:geometry")) {
            JsonObject geometry = element.getAsJsonObject();
            if (!geometry.has("bones")) {
                continue;
            }
            for (var boneElement : geometry.getAsJsonArray("bones")) {
                JsonObject candidate = boneElement.getAsJsonObject();
                if (candidate.has("name") && bone.equals(candidate.get("name").getAsString())
                        && candidate.has("pivot")) {
                    return candidate.getAsJsonArray("pivot").get(0).getAsDouble();
                }
            }
        }
        return null;
    }

    private static String round(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    /** @return the packs to read: every pack installed, in either the zip or the folder shape. */
    private static List<Path> packs() throws Exception {
        return PackLocator.allPackZips();
    }

    /** @return the namespace an id names - the config key's own, not a constant. */
    private static String ns(String id) {
        return id.substring(0, id.indexOf(':'));
    }

    /** @return the first pack holding this entry, or {@code null} if none does. */
    private static ZipFile open(List<Path> packs, String entry) throws Exception {
        for (Path path : packs) {
            ZipFile zip = new ZipFile(path.toFile());
            if (zip.getEntry(entry) != null) {
                return zip;
            }
            zip.close();
        }
        return null;
    }

    private static String read(ZipFile pack, String entry) throws Exception {
        try (InputStream in = pack.getInputStream(pack.getEntry(entry))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Collects the translation keys in a component the mod built, and in everything appended. */
    private static void keysIn(net.minecraft.network.chat.Component component, Set<String> into) {
        if (component == null) {
            return;
        }
        if (component.m_214077_()
                instanceof net.minecraft.network.chat.contents.TranslatableContents translated) {
            into.add(translated.m_237508_());
        }
        for (net.minecraft.network.chat.Component sibling : component.m_7360_()) {
            keysIn(sibling, into);
        }
    }

    /** @return the keys of a language file, read from the classes as they will ship. */
    private static Set<String> langKeys(String locale) {
        String json = resource("assets/" + MOD_ID + "/lang/" + locale + ".json");
        Set<String> keys = new LinkedHashSet<>();
        if (json != null) {
            keys.addAll(JsonParser.parseString(json).getAsJsonObject().keySet());
        }
        return keys;
    }

    /** Reads a resource off the addon's own classpath - the compiled copies, not the sources. */
    private static String resource(String path) {
        try (InputStream in = SuspensionCheck.class.getClassLoader().getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
