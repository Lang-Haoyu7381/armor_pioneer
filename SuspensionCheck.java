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
    private static final String SUSPENSION_PART = "ywzj_vehicle:suspension";

    /** Which way a keypress asked to go, as the two ends of the axis see it. */
    private static final int UP = 1;
    private static final int DOWN = -1;

    public static void main(String[] args) throws Exception {
        stepping();
        bias();
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
        System.out.println("  " + heights + " height positions, " + pitches + " pitch positions");

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
        check("no range at all is a single position on each axis",
                Suspension.heightSteps(still) == 1 && Suspension.pitchSteps(still) == 1);
        check("and asks for no movement at all, however it is pressed",
                Suspension.height(still, 0) == 0.0F && Suspension.pitch(still, 0) == 0.0F
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
     * wheel at the balance point gets none, and height and pitch add.
     */
    private static void bias() {
        System.out.println();
        System.out.println("=== how the bias is shared out along the hull ===");
        Suspension.Settings settings = new Suspension.Settings();
        // A synthetic axle layout, in blocks fore and aft of the hull's middle, and a balance point
        // that is deliberately not the middle: with unequal springs the balance point moves, and the
        // whole reason it is weighted is so that a pitch command does not also change the height.
        float front = 2.5F;
        float back = -2.0F;
        float balance = 0.3F;

        Suspension.Pose rest = new Suspension.Pose(Suspension.heightRest(settings),
                Suspension.pitchRest(settings));
        Suspension.Pose high = new Suspension.Pose(Suspension.heightSteps(settings) - 1,
                Suspension.pitchRest(settings));
        Suspension.Pose low = new Suspension.Pose(0, Suspension.pitchRest(settings));
        Suspension.Pose noseUp = new Suspension.Pose(Suspension.heightRest(settings),
                Suspension.pitchSteps(settings) - 1);
        Suspension.Pose noseDown = new Suspension.Pose(Suspension.heightRest(settings), 0);

        check("the resting pose asks for nothing, at every wheel",
                Suspension.bias(settings, rest, front, balance) == 0.0F
                        && Suspension.bias(settings, rest, 0.0F, balance) == 0.0F
                        && Suspension.bias(settings, rest, back, balance) == 0.0F);
        check("height is the same at the nose, the middle and the tail",
                Suspension.bias(settings, high, front, balance)
                        == Suspension.bias(settings, high, 0.0F, balance)
                        && Suspension.bias(settings, high, 0.0F, balance)
                                == Suspension.bias(settings, high, back, balance));
        check("and it is the full rise above the resting pose, not a fraction of it",
                Math.abs(Suspension.bias(settings, high, 0.0F, balance) - settings.max_rise)
                        < 1.0E-5F);
        check("a vehicle whose range is all rise never asks for a drop",
                Suspension.bias(settings, low, front, balance) == 0.0F);
        check("a wheel at the balance point is not moved by pitch at all",
                Suspension.bias(settings, noseUp, balance, balance) == 0.0F
                        && Suspension.bias(settings, noseDown, balance, balance) == 0.0F);
        // The sign convention is stated as "the two ends differ" rather than "the front is
        // positive": which end of the model is the nose is a property of the pack, and the constant
        // that reads it lives in the mixin. What is asserted here is the part that must hold
        // whatever that constant says.
        check("pitch moves the two ends in opposite directions",
                Suspension.bias(settings, noseUp, front, balance)
                        != Suspension.bias(settings, noseUp, back, balance)
                        && Math.signum(Suspension.bias(settings, noseUp, front, balance))
                                != Math.signum(Suspension.bias(settings, noseUp, back, balance)));
        check("and the two pitch directions are mirror images",
                Math.abs(Suspension.bias(settings, noseUp, front, balance)
                        + Suspension.bias(settings, noseDown, front, balance)) < 1.0E-6F
                        && Math.abs(Suspension.bias(settings, noseUp, back, balance)
                                + Suspension.bias(settings, noseDown, back, balance)) < 1.0E-6F);
        check("the tilt is the tangent of the angle over the distance from the balance point",
                Math.abs(Suspension.bias(settings, noseUp, front, balance)
                        - (float) Math.tan(Math.toRadians(settings.max_pitch)) * (front - balance))
                        < 1.0E-5F);
        check("height and pitch add rather than override",
                Math.abs(Suspension.bias(settings,
                                new Suspension.Pose(Suspension.heightSteps(settings) - 1,
                                        Suspension.pitchSteps(settings) - 1), front, balance)
                        - (Suspension.height(settings, Suspension.heightSteps(settings) - 1)
                                + Suspension.bias(settings, noseUp, front, balance))) < 1.0E-5F);
        check("no vehicle configured means no bias, whatever the pose",
                Suspension.bias(null, high, front, balance) == 0.0F
                        && Suspension.bias(settings, null, front, balance) == 0.0F);
        // The placeholder a vehicle with no settings resolves to is not a resting pose: it reads
        // (0, 0), and zero is the bottom of the height range and the nose-down end of the pitch one.
        // That distinction is the whole reason a missing settings object is visible rather than
        // silent - a vehicle sitting level by accident is a bug nobody would ever report - so it is
        // asserted here rather than left to the name of the constant.
        check("and the no-pose placeholder is not what a configured vehicle rests at",
                Suspension.bias(settings, Suspension.Pose.NONE, front, balance) != 0.0F);
        check("and the largest lift is the larger half of the range",
                Math.abs(Suspension.maxLift(settings)
                        - Math.max(settings.max_rise, settings.max_drop)) < 1.0E-6F);
    }

    /** Every line the mechanism can put on the screen has words in both languages. */
    private static void messages() {
        System.out.println();
        System.out.println("=== every line has words in both languages ===");
        Set<String> keys = new LinkedHashSet<>();
        keysIn(Suspension.describe(true, 0.1F, -1.5F), keys);
        keysIn(Suspension.describe(false, 0.0F, 0.0F), keys);
        check("no message was built without a translation key", !keys.contains(null));
        check("the no-suspension line is one of them",
                keys.contains("message." + MOD_ID + ".suspension_none"));

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

        // The point of the move, stated as the thing that would be false if it had stayed: the six
        // tracked vehicles have a suspension and no armour block to hang one on.
        check("all seven vehicles the addon gives a suspension to are in the defaults",
                profile.suspensions.size() == 7
                        && profile.suspensions.containsKey("vvp_ywzj:bmp_3")
                        && profile.suspensions.containsKey("vvp_ywzj:ajax"));
        check("and the six tracked ones need no armour entry to have one",
                !profile.vehicles.containsKey("vvp_ywzj:bmp_3")
                        && !profile.vehicles.containsKey("vvp_ywzj:puma"));

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
        check("while the six vehicles that file never had are added",
                older.suspensions != null && older.suspensions.containsKey("vvp_ywzj:challenger"));
        check("and a vehicle the addon does not give one to still has none",
                older.suspensions != null && older.suspensions.get("vvp_ywzj:t64") == null);

        // The other shape: a map that is there but empty. The fill keys on what the map does not
        // contain rather than on the map being absent, so both arrive at the same place.
        ArmorConfig.Profile emptied = ArmorConfig.configReader().fromJson(json,
                ArmorConfig.Profile.class);
        emptied.suspensions = new java.util.LinkedHashMap<>();
        upgrade.invoke(null, emptied);
        check("an empty map is filled rather than left empty",
                emptied.suspensions != null && emptied.suspensions.size() == 7);
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
     *       the tangent of the angle times how far that wheel is from the hull's balance point.</li>
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
                double weighted = 0.0;
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
                    units.add(new double[]{stiffness, z});
                    weighted += stiffness * z;
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

                double balance = total > 0.0 ? weighted / total : 0.0;
                double furthest = 0.0;
                for (double[] unit : units) {
                    furthest = Math.max(furthest, Math.abs(unit[1] - balance));
                }
                // The two directions are bounded by different things and are checked apart. Raising
                // the hull lengthens the springs until their probes run out of reach: that is the
                // droop budget, and the pitch spends it too, at the wheel furthest from the balance
                // point. Lowering it compresses them, and is stopped by the hull meeting the ground
                // rather than by any spring - so it is measured against the vehicle's own daylight.
                double rise = Math.abs(settings.max_rise)
                        + Suspension.maxPitchTangent(settings) * furthest;
                check(id + ": its largest rise (" + round(rise) + " blocks, " + springs
                                + " wheels) fits the droop the springs have left ("
                                + round(droopLeft) + ")",
                        rise <= droopLeft);

                Double hullBottom = lowestY(model, "main_structure");
                if (hullBottom != null && gearBottom != null) {
                    double clearance = (hullBottom - gearBottom) / 16.0;
                    check(id + ": its drop (" + round(settings.max_drop) + " blocks) clears the "
                                    + round(clearance) + " blocks the hull stands above the running gear",
                            settings.max_drop <= clearance);
                    System.out.printf("  %s: %d springs, balance z %.3f, furthest %.3f, droop left "
                                    + "%.3f, rise %.3f, drop %.3f of %.3f%n",
                            id, springs, balance, furthest, droopLeft, rise, settings.max_drop,
                            clearance);
                } else {
                    System.out.printf("  %s: %d springs, balance z %.3f, furthest %.3f, droop left "
                                    + "%.3f, rise %.3f (no hull clearance in the model)%n",
                            id, springs, balance, furthest, droopLeft, rise);
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

    private static String round(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    /** @return the packs to read: every pack installed under the game's pack folder. */
    private static List<Path> packs() throws Exception {
        List<Path> packs = new java.util.ArrayList<>();
        String root = System.getenv().getOrDefault("MC_ROOT", "E:\\.minecraft");
        Path dir = Path.of(root, "/versions/1.20.1-Forge_47.4.2/limitless_vehicle");
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".zip"))
                        .sorted()
                        .forEach(packs::add);
            }
        }
        return packs;
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
