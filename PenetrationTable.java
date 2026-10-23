import com.ywzjvehicle.armor.ArmorConfig;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.ywzj.vehicle.vehicle.structure.OBB;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exercises the real plate-selection code in ArmorSystem against the real shipped defaults, and
 * prints the damage table for every configured round.
 *
 * Everything below is driven from {@code ArmorConfig.defaultProfile()} rather than from numbers
 * typed in here, so the printed tables are what a fresh install actually uses. If a default in
 * ArmorConfig is wrong, it is wrong here too - which is the point.
 *
 * Face codes returned by OBB.embeddingFace: +-1 = X (side), +-2 = Y (roof/floor), +-3 = Z (front/rear).
 */
public class PenetrationTable {

    private static Method resolve;
    private static Method resolutionZone;
    private static Method resolutionLayer;
    private static Method resolutionSpent;
    private static Method resolutionUsed;
    private static Method isHullWeakSpot;
    private static Method isReactivePanel;
    private static Method hitsFrontDisc;
    private static Method isTrackEnd;
    private static Method isRoadWheel;

    /** The vehicle whose tables are being printed; every helper below resolves against it. */
    private static ArmorConfig.VehicleArmor current;

    /** Vehicle health the shot counts are measured against; set per model. */
    private static float VEHICLE_HEALTH;

    /**
     * The hull box's half-extents in blocks, derived from the model's cube.
     *
     * <p>The zone code needs it to turn a hit - which it is handed as a fraction of those extents -
     * back into blocks, which only the machine-gun port's circle asks for.
     */
    private static double[] hullHalf;

    private static ArmorConfig.Profile loaded;

    /**
     * Measurents taken from a vehicle's structure json, in 1/16-block units.
     *
     * <p>Kept here rather than read from the pack so the arithmetic in the checks below is visible:
     * these are the numbers the mod's derived defaults - track inner edge, gun mount offset - were
     * worked out from, and every one of them is an assumption the design did not state.
     *
     * @param hull      the {@code main_structure} cube: origin then size
     * @param turret    the {@code turret} cube: origin then size
     * @param gearInner |X| where the running gear begins - the track's locator cubes for a tracked
     *                  vehicle, the wheel boxes for a wheeled one
     * @param maxHealth the vehicle's own {@code max_health} from the pack, which the shot counts are
     *                  measured against; the BTR-3 has two thirds of a tank's
     */
    private record Model(String vehicleId, double[] hull, double[] turret, double gearInner,
                         float maxHealth) {
    }

    private static final Model T64 = new Model("vvp_ywzj:t64",
            new double[]{-35.17, 0.00, -61.13551, 70.34, 30.89389, 131.58912},
            new double[]{-29.0902, 26.0602, -25.8672, 54.4233, 17.7714, 57.0047},
            1.63 * 16, 3000.0F);

    private static final double[] ABRAMS_HULL = {-35.40, 0.00, -75.46, 70.80, 30.62, 153.78};
    private static final double[] ABRAMS_TURRET = {-33.66, 30.67, -44.93, 70.70, 17.30, 90.75};

    /**
     * The M1 family. All four are the same tank as far as this tool is concerned - VehicleScaffold
     * measures their hull and turret boxes to the same figures - so they share one set of numbers
     * and are told apart by what the config bolts onto them, which is the same way the T-90Ms work.
     */
    private static final Model[] ABRAMS = {
            new Model("vvp_ywzj:m1a1", ABRAMS_HULL, ABRAMS_TURRET, 1.75 * 16, 3000.0F),
            new Model("vvp_ywzj:m1a2", ABRAMS_HULL, ABRAMS_TURRET, 1.75 * 16, 3000.0F),
            new Model("vvp_ywzj:m1a1_ukr", ABRAMS_HULL, ABRAMS_TURRET, 1.75 * 16, 3000.0F),
            new Model("vvp_ywzj:m1a2_sep", ABRAMS_HULL, ABRAMS_TURRET, 1.75 * 16, 3000.0F),
    };

    /** The BTR-3 rides on wheels; 1.0613 blocks is where its wheel boxes begin, measured. */
    private static final Model BTR3 = new Model("vvp_ywzj:btr_3",
            new double[]{-23.10, 0.00, -62.5758, 46.25, 33.5996, 109.6766},
            new double[]{-14.10, 31.58629, -0.07164, 31.5, 12.31371, 16.67362},
            1.0613 * 16, 2000.0F);

    /**
     * The Panther G, from the tank pack rather than the Vintage Vehicle Pack.
     *
     * <p>Its running gear is one box per side rather than the rows of locator cubes the other
     * tracked vehicles carry, so {@code gearInner} is the track box's real inner face, at 1.0149
     * blocks against a hull half-width of 1.7806 - which is where the configured 0.589 comes from.
     */
    /**
     * The T-90Ms. All three variants are the same tank as far as this tool is concerned - the same
     * hull cube and the same turret cube, byte for byte in the pack - so they share one set of
     * numbers and differ only in what the config bolts onto the turret.
     *
     * <p>{@code gearInner} is the track box's inner face at 1.68 blocks against a hull half-width of
     * 1.98, which is where the configured 0.847 comes from.
     */
    private static final double[] T90_HULL = {-31.675, 0.0, -69.025, 63.425, 28.575, 128.45};
    private static final double[] T90_TURRET = {-21.3287, 26.3528, -36.2826, 43.9909, 13.6578, 55.6098};
    private static final Model[] T90M = {
            new Model("vvp_ywzj:t90_m", T90_HULL, T90_TURRET, 1.680 * 16, 3000.0F),
            new Model("vvp_ywzj:t90_pror_2022", T90_HULL, T90_TURRET, 1.680 * 16, 3000.0F),
            new Model("vvp_ywzj:t90_pror_2024", T90_HULL, T90_TURRET, 1.680 * 16, 3000.0F),
    };

    /**
     * The T-72B3M. Its own hull and turret, both shallower than the T-90M's, with the running gear's
     * inner face at 1.73 blocks against a hull half-width of 2.17 - where the configured 0.70 comes
     * from, as an upper bound the config has to stay at or below.
     */
    // Derived the way the mod derives an OBB: origin is the box's own corner and size its full
    // extent, both in 1/16-block units, off the centre and half-extents VehicleScaffold measured -
    // hull centre (+0.06, +0.87, -0.08) half (2.17, 0.87, 4.31), turret (+0.05, +2.02, +0.30)
    // half (1.84, 0.44, 1.81).
    private static final Model T72B3M = new Model("vvp_ywzj:t72_b3m",
            new double[]{-33.76, 0.00, -70.24, 69.44, 27.84, 137.92},
            new double[]{-28.64, 25.28, -24.16, 58.88, 14.08, 57.92},
            1.731 * 16, 3000.0F);

    /**
     * The M1296 Stryker Dragoon: the second wheeled vehicle here, and the first with an adjustable
     * suspension.
     *
     * <p>{@code gearInner} is the wheel boxes' inner face at 0.956 blocks - |X| = 15.29647 in the
     * model's units, against a hull half-width of 1.51 - which is the same figure the other wheeled
     * vehicle's entry carries, and the {@code road_wheels.top} of 0.017 the config uses is a height
     * rather than a width: it is the wheel boxes' top edge at y +0.017 of the hull's half-height.
     */
    private static final Model STRYKER = new Model("vvp_ywzj:stryker_m1296",
            new double[]{-24.325, 1.47363, -60.675, 48.175, 39.52637, 126.4},
            new double[]{-20.56192, 43.91207, -39.31847, 41.64884, 13.38469, 39.28375},
            15.29647, 2000.0F);

    private static final Model PANTHER = new Model("tank_assault:panther",
            new double[]{-28.175, -0.118384, -55.55, 56.97971, 33.868384, 114.15},
            new double[]{-19.8, 33.85, -21.725, 40.425, 12.525, 45.075},
            1.0149 * 16, 1150.0F);

    /**
     * The Tiger I, early hull with drum magazine. VehicleScaffold's measurement: hull centre
     * (+0.00, +0.96, -0.09) half (1.86, 0.96, 3.12) and turret (+0.03, +2.31, +0.05) half
     * (1.26, 0.46, 1.41). The early Tiger uses 100mm plate throughout the turret and nose
     * rather than the F's later 100/80 doubled plate, so the {@code max_health} is the same 100mm
     * shaped area as the Panther but with smaller faces.
     */
    private static final Model TIGER_E = new Model("tank_assault:tiger",
            new double[]{-29.76, 0.0, -49.92, 59.52, 30.72, 99.84},
            new double[]{-19.68, 29.60, -21.76, 40.32, 14.72, 45.12},
            1.10112 * 16, 1250.0F);

    /**
     * The VT-5, from the Armor Pioneer pack. VehicleScaffold's measurement of the current
     * structure file: hull centre (+0.00, +1.36, +0.60) half (1.55, 0.66, 3.95) and turret
     * (+0.00, +2.40, +0.44) half (1.38, 0.56, 2.32). The track boxes start at |X| = 1.855
     * blocks, which is 1.197 of the hull's 1.55-block half-width - the tracks sit outside the hull
     * box entirely. Health is the pack's own 2500.
     */
    private static final Model VT5 = new Model("armor_pioneer:vt5",
            new double[]{-24.80, 11.20, -53.60, 49.60, 21.12, 126.40},
            new double[]{-22.08, 29.44, -30.08, 44.16, 17.92, 74.24},
            1.855 * 16, 2500.0F);

    public static void main(String[] args) throws Exception {
        Class<?> system = Class.forName("com.ywzjvehicle.armor.ArmorSystem");
        // The real decision function, not a re-implementation of it. Rebuilding this logic here is
        // how a turret/hull mix-up in plateFor once went unnoticed: the copy agreed with itself.
        //
        // `resolve` rather than `resolveZone`: it is what the damage system calls, so the tandem
        // pass - the one rule that is not a single zone lookup - is exercised in the same shape the
        // game runs it. With tandem false it returns the plain zone, so this replaced no rows.
        Class<?> hitClass = Class.forName("com.ywzjvehicle.armor.ArmorSystem$Hit");
        resolve = system.getDeclaredMethod("resolve", ArmorConfig.VehicleArmor.class, String.class,
                hitClass, boolean.class, java.util.Set.class);
        resolve.setAccessible(true);
        Class<?> resolutionClass =
                Class.forName("com.ywzjvehicle.armor.ArmorSystem$Resolution");
        resolutionZone = resolutionClass.getDeclaredMethod("zone");
        resolutionLayer = resolutionClass.getDeclaredMethod("panelName");
        resolutionSpent = resolutionClass.getDeclaredMethod("scale");
        resolutionUsed = resolutionClass.getDeclaredMethod("panelUsed");
        for (Method m : new Method[]{resolutionZone, resolutionLayer, resolutionSpent, resolutionUsed}) {
            m.setAccessible(true);
        }
        isHullWeakSpot = system.getDeclaredMethod("isHullWeakSpot",
                ArmorConfig.WeakSpot.class, double[].class, double[].class);
        isHullWeakSpot.setAccessible(true);
        isReactivePanel = system.getDeclaredMethod("isReactivePanel", float.class, double.class);
        isReactivePanel.setAccessible(true);
        hitsFrontDisc = system.getDeclaredMethod("hitsFrontDisc", OBB.class, Vec3.class, ArmorConfig.WeakSpot.class);
        hitsFrontDisc.setAccessible(true);
        isTrackEnd = system.getDeclaredMethod("isTrackEnd",
                ArmorConfig.TrackEnds.class, double.class, double.class);
        isTrackEnd.setAccessible(true);
        isRoadWheel = system.getDeclaredMethod("isRoadWheel",
                ArmorConfig.RoadWheels.class, double.class);
        isRoadWheel.setAccessible(true);

        ArmorConfig.Profile profile = defaultProfile();
        loaded = profile;
        if (profile.vehicles.isEmpty()) {
            throw new IllegalStateException("defaultProfile() configures no vehicles at all");
        }

        List<Model> models = new ArrayList<>(List.of(T64, BTR3, STRYKER, PANTHER, TIGER_E, T72B3M, VT5));
        models.addAll(List.of(ABRAMS));
        models.addAll(List.of(T90M));
        for (Model model : models) {
            current = profile.vehicles.get(model.vehicleId());
            VEHICLE_HEALTH = model.maxHealth();
            // Size in 1/16 units, halved and converted: the same arithmetic the mod does when it
            // builds the OBB out of the model cube.
            hullHalf = new double[]{model.hull()[3] / 32.0, model.hull()[4] / 32.0,
                    model.hull()[5] / 32.0};
            if (current == null) {
                throw new IllegalStateException("defaultProfile() has no " + model.vehicleId() + " entry");
            }
            System.out.println("=========================================================");
            System.out.println("vehicle " + model.vehicleId());
            System.out.println();
            tables();
            splitBoundary();
            if (hasSideScreen()) {
                reactiveBoundaryCheck(model);
            }
            gunMountCheck(model);
            trackZoneCheck(model);
            wheelCheck(model);
            taczGrid();
        }
        ammoTableCheck(profile);
        screenCheck();
        tandemCheck();

        if (failures > 0) {
            System.out.println();
            System.out.println("RESULT: " + failures + " check(s) failed - see above");
            System.exit(1);
        }
        System.out.println();
        System.out.println("RESULT: every table and screen check passed");
    }

    /**
     * Exercises the two things a screen can do that a plate cannot: take penetration off the round,
     * and only be there at all when the round arrives obliquely.
     *
     * <p>On a vehicle of its own rather than on a shipped one, because no shipped vehicle has either
     * yet - and a mechanism nothing uses is a mechanism nothing has tested. The synthetic vehicle is
     * two numbers that are obviously made up, which is the point: what is being checked is the rule,
     * not anybody's armour.
     *
     * <p>Called through the same {@code resolveZone} the game calls, so a check that passes here and
     * a hit that behaves differently in play would mean the two are reaching this code by different
     * routes - which is what the tool is for.
     */
    private static void screenCheck() throws Exception {
        System.out.println();
        System.out.println("=== screens: penetration loss and arrival angle ===");

        ArmorConfig.VehicleArmor vehicle = new ArmorConfig.VehicleArmor();
        vehicle.hull = new ArmorConfig.ArmorSet(100.0F, 100.0F, 100.0F, 100.0F, 100.0F);
        vehicle.hull_side_era = new ArmorConfig.Plate(20.0F, 2.0F);
        vehicle.hull_side_era.penetration_loss = 200.0F;
        vehicle.hull_side_era_front_fraction = 0.5F;
        vehicle.hull_side_era_min_angle = 45.0F;
        vehicle.turret_part_ids = new String[]{"turret"};

        ArmorConfig.VehicleArmor saved = current;
        current = vehicle;
        try {
            // Square on: the screen is not there, and the shot meets the flank behind it.
            angleNow = 0.0;
            Object[] squareOn = zoneFor(null, false, 1, hull(0.0, 0.0, 1.0), false);
            check("square on, the screen is skipped", "hull".equals(squareOn[0]),
                    "zone was " + squareOn[0]);
            check("  and nothing is taken off the round", (Float) squareOn[3] == 0.0F, "");

            // Oblique: the screen is what the shot meets, and it takes 200mm with it.
            angleNow = 60.0;
            Object[] oblique = zoneFor(null, false, 1, hull(0.0, 0.0, 1.0), false);
            check("at 60 degrees the screen counts", "hull side (reactive)".equals(oblique[0]),
                    "zone was " + oblique[0]);
            check("  and takes 200mm off the round", (Float) oblique[3] == 200.0F, "");

            // The threshold itself belongs to the screen, so the boundary is where it says.
            angleNow = 45.0;
            check("exactly at the threshold it still counts",
                    "hull side (reactive)".equals(zoneFor(null, false, 1,
                            hull(0.0, 0.0, 1.0), false)[0]), "");
            angleNow = 44.9;
            check("a tenth below it, it does not",
                    "hull".equals(zoneFor(null, false, 1, hull(0.0, 0.0, 1.0), false)[0]), "");

            // And the loss has to reach the sum rather than merely be reported: 400mm of penetration
            // through the 20mm screen arrives at the flank as 180mm, and the flank is 100mm.
            float left = 400.0F - (Float) oblique[3] - (Float) oblique[1];
            check("the loss is subtracted before the plate is compared", left == 180.0F,
                    "400 - 200 screen loss - 20 screen = " + left + "mm against a 100mm flank");

            // A box bolted to the outside beats the face it is bolted over. The hull is 100mm from
            // every direction; the box is 700, so a hit on the box must read 700 and not 100.
            angleNow = 0.0;
            vehicle.part_armor.put("era_3", new ArmorConfig.Plate(700.0F, 10.0F));
            partNow = "era_3";
            Object[] onBox = zoneFor(null, false, 1, hull(0.0, 0.0, 1.0), false);
            check("a hit on a bolted-on box reads the box", "era_3".equals(onBox[0])
                    && (Float) onBox[1] == 700.0F, "zone was " + onBox[0] + " at " + onBox[1] + "mm");

            // And the box is not the flank: a hit beside it still reads the flank.
            partNow = "era_4";
            Object[] besideBox = zoneFor(null, false, 1, hull(0.0, 0.0, 1.0), false);
            check("a box with no value of its own falls through to the face",
                    "hull".equals(besideBox[0]) && (Float) besideBox[1] == 100.0F,
                    "zone was " + besideBox[0] + " at " + besideBox[1] + "mm");

            // The round's own value for the box wins, which is the whole reason a shell that does
            // not care about slat screens can say so by saying nothing.
            ArmorConfig.ArmorOverride forRound = new ArmorConfig.ArmorOverride();
            forRound.part_armor = new java.util.LinkedHashMap<>();
            forRound.part_armor.put("era_3", new ArmorConfig.Plate(30.0F, 2.0F));
            vehicle.overrides.put("thin", forRound);
            partNow = "era_3";
            Object[] byRound = zoneFor("thin", false, 1, hull(0.0, 0.0, 1.0), false);
            check("the round's value for the box wins", (Float) byRound[1] == 30.0F,
                    "was " + byRound[1] + "mm");
        } finally {
            current = saved;
            angleNow = 0.0;
            partNow = null;
        }
    }

    /**
     * Exercises what a tandem warhead does about each kind of reactive armour.
     *
     * <p>On a made-up vehicle, for the same reason the screen check is - what is being checked is
     * the rule, not anybody's armour. It carries a heavy panel on the turret and a light one on the
     * flank because the two are reached by different branches of the zone decision, and a rule that
     * works for one of them is not evidence about the other.
     *
     * <p>The heavy panel is also the only thing in this addon with a memory, so the second hit on it
     * is the case a stateless implementation would get wrong while every other row still looked
     * right - and the case the game would only reveal in the middle of a fight.
     */
    private static void tandemCheck() throws Exception {
        System.out.println();
        System.out.println("=== tandem warheads: a light panel costs a third, a heavy one costs one hit ===");

        ArmorConfig.VehicleArmor vehicle = new ArmorConfig.VehicleArmor();
        vehicle.turret = new ArmorConfig.ArmorSet(100.0F, 100.0F, 100.0F, 100.0F, 100.0F);
        vehicle.hull = new ArmorConfig.ArmorSet(100.0F, 100.0F, 100.0F, 100.0F, 100.0F);
        vehicle.turret_part_ids = new String[]{"turret"};
        // A heavy panel worth far more than the round can penetrate, so "it got through" can only
        // mean the panel was already gone. No loss of its own: this kind stops the round with
        // thickness, and what it costs is itself.
        vehicle.part_armor.put("box", new ArmorConfig.Plate(900.0F, 10.0F)
                .reactive(com.ywzjvehicle.armor.EraKind.HEAVY));
        // A light panel on the flank, reached by a different branch entirely.
        vehicle.hull_side_era = new ArmorConfig.Plate(20.0F, 2.0F)
                .reactive(com.ywzjvehicle.armor.EraKind.LIGHT);
        vehicle.hull_side_era.penetration_loss = 200.0F;
        vehicle.hull_side_era_front_fraction = 1.0F;
        vehicle.hull_side_era_min_angle = 45.0F;

        ArmorConfig.VehicleArmor saved = current;
        current = vehicle;
        try {
            float penetration = 500.0F;

            // A heavy panel, first hit: it stops the tandem round and does not survive it.
            partNow = "box";
            Object[] fresh = zoneFor(null, true, 3, null, false, true);
            check("a heavy panel stops a tandem round",
                    "box".equals(fresh[0]) && (Float) fresh[1] == 900.0F, "was " + fresh[0]);
            check("  and is used up doing it", "box".equals(fresh[7]),
                    "panel used was " + fresh[7]);
            check("  so that round gets nowhere", penetration - (Float) fresh[1] < 0.0F,
                    "500 - 900 = " + (penetration - (Float) fresh[1]));

            // The same panel, second hit: gone, and the round meets the turret that was behind it.
            Object[] spent = zoneFor(null, true, 3, null, false, true, java.util.Set.of("box"));
            check("the next tandem round finds it gone",
                    "turret".equals(spent[0]) && (Float) spent[1] == 100.0F,
                    "was " + spent[0] + " at " + spent[1] + "mm");
            check("  and arrives at full penetration", (Float) spent[6] == 1.0F,
                    "scale was " + spent[6]);
            check("  naming the panel it went through", "box".equals(spent[5]),
                    "panel was " + spent[5]);
            check("  which that hit does not use again", spent[7] == null, "used " + spent[7]);

            // The light panel: through, a third of the penetration lighter, and never consumed -
            // what it is worth to a tandem round is the third, every time.
            angleNow = 60.0;
            partNow = null;
            Object[] onLight = zoneFor(null, false, 1, hull(0.0, 0.0, 1.0), false, true);
            check("a tandem round goes through a light panel",
                    "hull".equals(onLight[0]) && (Float) onLight[1] == 100.0F,
                    "was " + onLight[0] + " at " + onLight[1] + "mm");
            check("  at two thirds of its penetration",
                    Math.abs((Float) onLight[6] - 2.0F / 3.0F) < 1.0E-5F, "scale " + onLight[6]);
            check("  which is a third of 500mm gone",
                    Math.abs((Float) onLight[6] * penetration - 333.3333F) < 0.01F,
                    "left " + ((Float) onLight[6] * penetration) + "mm");
            check("  and the panel is not used up", onLight[7] == null, "used " + onLight[7]);

            // Against bare armour there is no panel at all, so a tandem round is an ordinary one -
            // the flag says what it can do, not that it always does it.
            Object[] bare = zoneFor(null, true, 3, null, false, true, java.util.Set.of("box"));
            check("against bare armour it resolves exactly as before",
                    "turret".equals(bare[0]) && (Float) bare[6] == 1.0F && bare[5] == null,
                    "was " + bare[0] + ", scale " + bare[6]);

            // And none of this touches an ordinary round. It meets each panel and is measured
            // against that panel itself, which is what every round did before any of this existed.
            angleNow = 0.0;
            partNow = "box";
            Object[] plainBox = zoneFor(null, true, 3, null, false);
            check("an ordinary round still just meets the heavy panel",
                    "box".equals(plainBox[0]) && (Float) plainBox[1] == 900.0F
                            && plainBox[5] == null && plainBox[7] == null,
                    "was " + plainBox[0] + ", panel " + plainBox[5] + ", used " + plainBox[7]);
            angleNow = 60.0;
            partNow = null;
            Object[] plainLight = zoneFor(null, false, 1, hull(0.0, 0.0, 1.0), false);
            check("  and the light panel, and is not scaled",
                    "hull side (reactive)".equals(plainLight[0])
                            && (Float) plainLight[6] == 1.0F && plainLight[5] == null,
                    "was " + plainLight[0] + " at scale " + plainLight[6]);
            angleNow = 0.0;

            // The kinds have to be readable off the zone at all, or every rule above is dead code
            // that happens to be unreachable rather than one that fails.
            check("both panels carry their kind into the zone",
                    com.ywzjvehicle.armor.EraKind.HEAVY.equals(plainBox[4])
                            && com.ywzjvehicle.armor.EraKind.LIGHT.equals(plainLight[4]),
                    "box " + plainBox[4] + ", flank " + plainLight[4]);
            check("  and an unarmoured plate carries none", bare[4] == null, "was " + bare[4]);
        } finally {
            current = saved;
            angleNow = 0.0;
            partNow = null;
        }
    }

    private static void check(String what, boolean ok, String detail) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what
                + (ok || detail.isEmpty() ? "" : "   (" + detail + ")"));
        if (!ok) {
            failures++;
        }
    }

    private static int failures;

    /**
     * One table per armour table on the current vehicle, its own values first.
     *
     * <p>Rounds are grouped by everything that changes what the table says, so rounds that really
     * do produce the same numbers share a table and any other pairing gets one of its own. Grouping
     * on the profile alone would be wrong twice over: the tables are per vehicle while the profile
     * names are not, so the T-64 and the Abrams both have a "heat"; and two rounds can share a
     * profile and a penetration while differing in what they do on impact, as the two HE rounds do.
     *
     * <p>Rounds carrying a flat multiplier are left out - a machine gun's damage does not depend on
     * which plate it struck, so there is nothing to tabulate; they appear in the ammunition summary.
     */
    /**
     * What every TACZ cartridge does to this vehicle, one row apiece.
     *
     * <p>Twenty-four full tables for each vehicle would answer the same question and be unreadable,
     * so this is the summary instead: the residual against each face, or a marker where the plate
     * held. It exists because the figures in {@code tacz_rounds} are real-world millimetres while the
     * armour they meet is this addon's own, and the two only meet on a page like this one - there is
     * no round in the game that will tell you a .50 BMG opens an M1296's roof by seven millimetres
     * and stops dead on its glacis.
     *
     * <p>It goes through {@link #zoneFor}, the same call {@link #row} makes, so it cannot drift from
     * the tables above it or from the game. What it does not do is model angles: every cell is a
     * square-on hit, and the flank of anything with a slat screen is worth more than the number here.
     */
    private static void taczGrid() throws Exception {
        if (loaded.tacz_rounds.isEmpty()) {
            return;
        }
        System.out.println("TACZ cartridges against this vehicle  (residual in mm, '===' = held)");
        System.out.printf("%-14s %5s %7s %7s %7s %7s %7s %7s%n",
                "cartridge", "mm", "turret", "hull", "hull", "hull", "hull", "hull");
        System.out.printf("%-14s %5s %7s %7s %7s %7s %7s %7s%n",
                "", "", "front", "front", "side", "rear", "roof", "floor");
        System.out.println("-----------------------------------------------------------------------");
        ArmorConfig.Ammo wasRound = roundNow;
        float wasPenetration = penetrationNow;
        for (Map.Entry<String, ArmorConfig.Ammo> entry : loaded.tacz_rounds.entrySet()) {
            ArmorConfig.Ammo round = entry.getValue();
            roundNow = round;
            penetrationNow = round.penetration;
            System.out.printf("%-14s %5.0f %7s %7s %7s %7s %7s %7s%n",
                    entry.getKey().replace("tacz:", ""), round.penetration,
                    cell("turret", 3, null),
                    cell("hull", 3, hull(0.77, -0.50, 0.0)),
                    cell("hull", 1, hull(0.0, 0.0, 0.50)),
                    cell("hull", -3, hull(0.77, -0.50, 0.0)),
                    cell("hull", 2, null),
                    cell("hull", -2, null));
        }
        System.out.println();
        roundNow = wasRound;
        penetrationNow = wasPenetration;
    }

    /** @return one cell of {@link #taczGrid}: the residual, or a marker where the plate held. */
    private static String cell(String part, int face, double[] hullLocal) throws Exception {
        // The round's own profile decides which of the vehicle's tables it is measured against, the
        // same way `table(...)` passes it down. Reading the vehicle's base table for every round is
        // how the two shaped charges in this list came out being stopped by armour their chemical
        // table goes straight through - the numbers a shaped charge is rated on are not on the
        // kinetic table at all, so ignoring the profile made them all look like duds.
        ArmorConfig.ArmorOverride ov =
                roundNow == null || roundNow.profile == null
                        ? null : current.overrides.get(roundNow.profile);
        Object[] resolved = zoneFor(profileOf(ov), "turret".equals(part), face, hullLocal,
                false, roundNow != null && roundNow.tandem(), null);
        float thickness = (Float) resolved[1];
        float loss = (Float) resolved[3];
        float scale = (Float) resolved[6];
        float residual = penetrationNow * scale - loss - thickness;
        return residual >= 0.0F ? String.format("%.0f", residual) : "===";
    }

    private static void tables() throws Exception {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (Map.Entry<String, ArmorConfig.Ammo> e : loaded.ammo_penetration.entrySet()) {
            ArmorConfig.Ammo a = e.getValue();
            if (a.multiplier == null) {
                groups.computeIfAbsent(signature(a), k -> new ArrayList<>()).add(e.getKey());
            }
        }

        for (List<String> names : groups.values()) {
            ArmorConfig.Ammo model = loaded.ammo_penetration.get(names.get(0));
            if (model.proximity != null) {
                // A multi-purpose round has two tables, and the second one is the whole reason it
                // exists. Printed first so it stands beside the impact table rather than looking
                // like a footnote to it.
                ArmorConfig.Ammo near = model.proximity;
                table("proximity / delay fuze of " + labelled(names),
                        near, near.profile == null ? null : current.overrides.get(near.profile));
            }
            String profile = model.profile;
            table((profile == null ? "base table" : "profile '" + profile + "'")
                            + " at " + model.penetration + "mm  (" + labelled(names) + ")"
                            // Worth marking, because it is the one property of a round that changes
                            // how a row is read: the layer in front of the plate is spent, not met.
                            + (model.tandem() ? "  [tandem warhead]" : ""),
                    model, profile == null ? null : current.overrides.get(profile));
        }
    }

    /**
     * The ids of a group, each marked with the loads it offers if it offers any.
     *
     * <p>Marked per id and not once for the group, because a group is whatever shares a signature
     * and the loads are not part of one: the T-64's plain 125mm high-explosive shell and the
     * T-72B3M's otherwise identical one are grouped together, and the second of the two is the only
     * one that can be swapped for an airburst shell. One marker taken from the group's first id
     * would sit on the wrong round - or, as it did, on none of them.
     *
     * <p>What the loads <em>are</em> is the compendium's job and is not repeated here; this says
     * only that the row below is the round the pack names the slot for, which a gunner who has
     * swapped the load is not firing.
     */
    private static String labelled(List<String> names) {
        List<String> out = new ArrayList<>();
        for (String id : names) {
            ArmorConfig.Ammo ammo = loaded.ammo_penetration.get(id);
            out.add(ammo != null && ammo.selectable()
                    ? id + " [+" + (ammo.variantCount() - 1) + " load(s)]" : id);
        }
        return String.join(", ", out);
    }

    /**
     * Everything about a round that changes the table: the armour table it claims, how hard it
     * hits, and what it does on impact - a bursting charge, a blast that scales, or both.
     */
    private static String signature(ArmorConfig.Ammo a) {
        return a.profile + "|" + a.penetration + "|" + a.penetration_damage + "|"
                // The roll's three numbers, for the same reason as everything else here: two rounds
                // that differ only in the band they roll are two different rounds, and grouping them
                // would print one of the bands and hide the other.
                + a.penetration_bonus_min + "|" + a.penetration_bonus_max + "|"
                + a.penetration_bonus_max_at + "|"
                + a.spallOnPenetration() + "|" + a.spall_damage_min + "|" + a.spall_damage_max
                + "|" + a.spall_falloff_to + "|" + a.replacesBaseExplosion()
                // A tandem warhead is a property of the round and not of its numbers, but it is
                // very much a property of the table: against a reactive panel the outer layer is
                // spent rather than met, so every one of those rows comes out different. Without
                // this the BTR-3's missile would be tabulated with an ordinary shaped charge of the
                // same rating and one of the two tables - the interesting one - would never print.
                + "|tandem:" + a.tandem()
                // A multi-purpose round's impact numbers are a plain shaped charge's exactly, so
                // without this it would be grouped with one and its second table would never be
                // printed - the one table that says what makes it different.
                + (a.proximity == null ? ""
                        : "|proximity:" + a.proximity.profile + ":" + a.proximity.penetration);
    }

    /** Penetration of the round whose table is being printed; set by {@link #table}. */
    private static float penetrationNow;

    /** The round itself, so the printed damage uses the same burst and blast rules the mod does. */
    private static ArmorConfig.Ammo roundNow;

    private static void table(String title, ArmorConfig.Ammo round, ArmorConfig.ArmorOverride ov)
            throws Exception {
        penetrationNow = round.penetration;
        roundNow = round;
        System.out.println(title);
        System.out.printf("penetration %.0fmm, vehicle health %.0f%n", round.penetration, VEHICLE_HEALTH);
        // The face column carries an angle tag on the rows that need one - "side@30" is seven
        // characters - so it is wider than the longest bare name to keep every row in line.
        System.out.printf("%-7s %-23s %7s %7s %9s %6s %9s %8s%n",
                "face", "zone", "armor", "screen", "residual", "mult", "damage", "shots");
        System.out.println("---------------------------------------------------------------------------------");
        row(ov, "turret", 3, null, false);
        row(ov, "turret", 3, null, true);
        row(ov, "hull", 3, hull(0.77, -0.50, 0.0), false);
        row(ov, "hull", -3, hull(0.77, -0.50, 0.0), false);
        row(ov, "hull", 3, hull(0.0, 0.50, 0.0), false);
        row(ov, "hull", 3, hull(0.0, -0.50, 0.0), false);
        // The boxes bolted to this vehicle, each measured as itself. Without these rows the one
        // thing a modern tank is configured for would never appear in a table at all: the boxes beat
        // every face, so no face row can ever print their numbers.
        for (String part : current.part_armor.keySet()) {
            boolean onTurret = java.util.Arrays.asList(current.turret_part_ids).contains(part);
            partNow = part;
            row(ov, onTurret ? "turret" : "hull", onTurret ? 3 : 1,
                    onTurret ? null : hull(0.0, 0.0, 0.0), false);
            // A heavy panel is one-shot, so a table that only ever shows it in place tells half the
            // story: the tandem round it stops is stopped once, and the row underneath is what the
            // second one meets. Printed here rather than left to the reader because the two rows
            // differ by a factor of five and neither says so on its own.
            if (roundNow != null && roundNow.tandem() && heavyPanel(ov, part)) {
                spentNow = java.util.Set.of(part);
                row(ov, onTurret ? "turret" : "hull", onTurret ? 3 : 1,
                        onTurret ? null : hull(0.0, 0.0, 0.0), false);
                spentNow = null;
            }
            partNow = null;
        }
        // And the flank a second time, obliquely. A screen that only counts past an angle has
        // numbers that a square-on row can never show, and those are the numbers it exists for.
        if (sideScreen(ov) != null && current.hull_side_era_min_angle > 0.0F) {
            angleNow = current.hull_side_era_min_angle;
            row(ov, "hull", 1, hull(0.0, 0.0, 0.50), false);
            angleNow = 0.0;
        }
        if (current.hull_weak_spot != null && current.hull_weak_spot.enabled
                && current.hull_weak_spot.radius > 0.0F) {
            // Dead centre of the port, worked out from the port's own offsets so the row cannot be
            // aimed at where the circle used to be after the offsets are edited.
            row(ov, "hull", 3, hull(current.hull_weak_spot.offset_x / hullHalf[0],
                    current.hull_weak_spot.offset_y / hullHalf[1], 0.0), false);
        }
        row(ov, "turret", 1, null, false);
        row(ov, "hull", 1, hull(0.0, 0.0, 0.50), false);
        row(ov, "hull", 1, hull(0.0, 0.0, -0.50), false);
        row(ov, "turret", -3, null, false);
        row(ov, "hull", -3, null, false);
        // Roofs and floors are the plates the HE round was given its own values for, so they are
        // listed separately rather than lumped together.
        row(ov, "turret", 2, null, false);
        row(ov, "hull", 2, null, false);
        row(ov, "turret", -2, null, false);
        row(ov, "hull", -2, null, false);
        System.out.println();
    }

    /**
     * The flank screen actually in force for a table, wherever its numbers were written down.
     *
     * <p>A screen can be stated by the vehicle or by the ammunition's own override, and only the
     * plate travels that way: the coverage and the angle stay on the vehicle, because they describe
     * where the panels are bolted rather than what they do. Reading the vehicle's copy alone is how
     * the T-90M's screen - which is a CE-only override - went unprinted in every table it exists
     * for, and its boundary check was skipped outright.
     */
    private static ArmorConfig.Plate sideScreen(ArmorConfig.ArmorOverride ov) {
        if (ov != null && ov.hull_side_era != null) {
            return ov.hull_side_era;
        }
        return current.hull_side_era;
    }

    /**
     * Whether the current vehicle has a flank screen anywhere at all, so the boundary block is
     * printed whenever there is one to describe rather than only when the hull block holds it.
     */
    private static boolean hasSideScreen() {
        if (current.hull_side_era != null) {
            return true;
        }
        for (ArmorConfig.ArmorOverride ov : current.overrides.values()) {
            if (ov != null && ov.hull_side_era != null) {
                return true;
            }
        }
        return false;
    }

    /** A hull-local hit position: index 0 across the hull, 1 up it, 2 along it. */
    private static double[] hull(double x, double y, double z) {
        return new double[]{x, y, z};
    }

    /**
     * One row of the table.
     *
     * @param part      "turret" or "hull"
     * @param face      OBB.embeddingFace code
     * @param hullLocal hull-local hit position, or {@code null} for a turret hit
     * @param gunMount  whether the hit is inside the gun mount circle
     */
    private static void row(ArmorConfig.ArmorOverride ov, String part, int face,
                            double[] hullLocal, boolean gunMount) throws Exception {
        boolean turret = "turret".equals(part);
        ArmorConfig.Ammo round = roundNow;
        // The round's own tandem flag decides whether the layer it met is spent or met, so it has to
        // be asked here rather than assumed: printing a tandem round as stopped by a box it goes
        // straight through is the table lying about the one round whose whole point is that box.
        Object[] resolved = zoneFor(profileOf(ov), turret, face, hullLocal, gunMount,
                round != null && round.tandem(), spentNow);
        String name = (String) resolved[0];
        float thickness = (Float) resolved[1];
        float multiplier = (Float) resolved[2];
        float loss = (Float) resolved[3];
        String panelName = (String) resolved[5];
        float scale = (Float) resolved[6];
        // What the panel took off the round, whichever way it did it: a number of millimetres for an
        // ordinary round measured against a screen, or a share of its penetration for a tandem round
        // through a light panel. Both are the same thing to the reader - what the round no longer has.
        float spentOnLayers = penetrationNow * (1.0F - scale) + loss;
        if (panelName != null) {
            // Named, because the zone that decides the damage is the one behind it. When the panel
            // is itself the answer, the round was stopped by it - and on a heavy panel that can only
            // be the first hit, since the first is the one it gets.
            name = panelName.equals(name) ? panelName + " (1st hit)" : panelName + " > " + name;
        }

        // Same sum the damage system uses, the screen included: what is left of the round after the
        // screen is what meets the plate.
        float residual = penetrationNow * scale - loss - thickness;
        // Same rule the damage system uses: equal counts as through.
        boolean penetrated = residual >= 0.0F;
        System.out.printf("%-6s %-23s %7.0f %7.0f %9.1f %6.1f ",
                faceLabel(face), name, thickness, spentOnLayers, residual, multiplier);

        if (!penetrated) {
            System.out.printf("%9s %8s", "no pen", "-");
            appendBlast(round, thickness);
            return;
        }
        // The round's own formula, called rather than copied. A round that rolls a bonus on top of
        // its penetration damage gets the whole band printed instead of one draw from it - a single
        // sample would be a number the round never promises, and reading two tables side by side
        // would be comparing two dice rolls.
        float base = round != null && round.penetration_damage != null
                ? round.penetration_damage
                : residual * multiplier;
        float low = base;
        float high = base;
        if (round != null && round.hasPenetrationBonus()) {
            low = base + round.penetrationBonusFloor();
            high = base + round.penetrationBonusCeiling(thickness);
            System.out.printf("%9s", String.format("%.0f-%.0f", low, high));
        } else {
            System.out.printf("%9.1f", base);
        }
        // A round that carries no bursting charge deals its blast whether or not it got through, so
        // the spall has to be shown on this side of the branch too - the damage figure alone would
        // read as zero and hide the round's whole effect.
        if (round != null && round.hasSpall() && round.spallOnPenetration()) {
            System.out.printf("   blast %.0f%n", spallDamage(round, thickness));
        } else {
            System.out.printf(" %8s%n", shots(low, high));
        }
    }

    /**
     * @return how many hits it takes to kill, or the span of that count when the damage rolls.
     *
     * <p>Fewest first, the same way the damage band is written: the least damage a hit can do is the
     * most hits it can take, so the two ends come out in opposite orders and printing them the wrong
     * way round reads as a round that gets stronger the more of them you land.
     */
    private static String shots(float low, float high) {
        if (low <= 0.0F || high <= 0.0F) {
            return "0";
        }
        String fewest = String.format("%.0f", Math.ceil(VEHICLE_HEALTH / high));
        if (low >= high) {
            return fewest;
        }
        return fewest + "-" + String.format("%.0f", Math.ceil(VEHICLE_HEALTH / low));
    }

    /** Appends the blast column for a round that scatters spall. */
    private static void appendBlast(ArmorConfig.Ammo round, float thickness) throws Exception {
        if (round != null && round.hasSpall()) {
            System.out.printf("   blast %.0f%n", spallDamage(round, thickness));
        } else {
            System.out.println();
        }
    }

    /**
     * The face the hit came through, as fed to the mod - the zone name alone does not say.
     *
     * <p>The arrival angle is tagged on when it is not square on, because two rows of the same face
     * are otherwise indistinguishable on the page while measuring different things - which for a
     * screen is the entire point of the second row.
     */
    private static String faceLabel(int face) {
        if (partNow != null) {
            // A box beats every face, so which face the ray happened to enter it through is not part
            // of the answer - printing one of the five would be inventing a fact.
            return "box";
        }
        String base;
        switch (Math.abs(face)) {
            case 1: base = "side"; break;
            case 3: base = face > 0 ? "front" : "rear"; break;
            default: base = face > 0 ? "roof" : "floor";
        }
        return angleNow > 0.0 ? base + "@" + Math.round(angleNow) : base;
    }

    /** Calls the mod's own spall damage curve so the printed blast values cannot drift from it. */
    private static float spallDamage(ArmorConfig.Ammo round, float thickness) throws Exception {
        Method m = Class.forName("com.ywzjvehicle.armor.ArmorSystem")
                .getDeclaredMethod("spallDamage", ArmorConfig.Ammo.class, float.class);
        m.setAccessible(true);
        return (Float) m.invoke(null, round, thickness);
    }

    /** The first configured round claiming the given profile, or {@code null} for the base table. */
    private static ArmorConfig.Ammo roundUsingProfile(String profileName) {
        return profileName == null ? null : roundUsing(loaded, profileName);
    }

    /** How obliquely the synthetic hits arrive. Square on unless a check asks for otherwise. */
    private static double angleNow;

    /** Which part the synthetic hit landed on, or {@code null} for hull structure. */
    private static String partNow;

    /** Reactive panels already used up on the vehicle being tabulated, or {@code null} for none. */
    private static java.util.Set<String> spentNow;

    /**
     * @return whether a box is heavy reactive armour for the table's profile - the kind that stops
     *         one tandem warhead and is gone, so that it needs a second row.
     */
    private static boolean heavyPanel(ArmorConfig.ArmorOverride ov, String part) {
        ArmorConfig.Plate byRound = ov == null || ov.part_armor == null ? null : ov.part_armor.get(part);
        ArmorConfig.Plate plate = byRound != null ? byRound : current.part_armor.get(part);
        return plate != null && plate.eraKind() == com.ywzjvehicle.armor.EraKind.HEAVY;
    }

    /**
     * Calls the mod's real ArmorSystem.resolveZone and returns
     * {@code {name, thickness, multiplier, loss}}.
     *
     * <p>The hit is packed into the mod's own record rather than passed as a row of arguments, so
     * that a check which forgets one of them cannot quietly be measuring a different impact - which
     * is the failure the record exists to prevent, and which would look like a wrong number rather
     * than a wrong call.
     */
    private static Object[] zoneFor(String profile, boolean turret, int face, double[] hullLocal,
                                    boolean gunMount) throws Exception {
        return zoneFor(profile, turret, face, hullLocal, gunMount, false);
    }

    /**
     * The plate a hit is finally measured against, through the mod's own resolution.
     *
     * <p>Every row goes through {@code ArmorSystem.resolve} - the method the game calls - rather
     * than through {@code resolveZone} directly, so the tandem rule is not implemented a second time
     * here. A copy would agree with itself and disagree with the game, which is the failure this
     * tool has already been rebuilt once to avoid. With {@code tandem} false the two are the same
     * call, so the ordinary rows measure exactly what they always did.
     *
     * @return {@code {name, thickness, multiplier, loss, layer, layerSpent, spentOnLayers}}
     */
    private static Object[] zoneFor(String profile, boolean turret, int face, double[] hullLocal,
                                    boolean gunMount, boolean tandem) throws Exception {
        return zoneFor(profile, turret, face, hullLocal, gunMount, tandem, null);
    }

    /**
     * The same, against a vehicle whose reactive panels have (or have not) already been used up.
     *
     * @param spent the panels already used on this vehicle, or {@code null} for none - which is what
     *              the first hit of a fight meets, and therefore the default for every table above
     */
    private static Object[] zoneFor(String profile, boolean turret, int face, double[] hullLocal,
                                    boolean gunMount, boolean tandem, java.util.Set<String> spent)
            throws Exception {
        Class<?> hitClass = Class.forName("com.ywzjvehicle.armor.ArmorSystem$Hit");
        Object hit = hitClass.getDeclaredConstructors()[0].newInstance(
                turret, face, hullLocal, hullHalf, gunMount, angleNow, partNow, false);
        Object resolution = resolve.invoke(null, current, profile, hit, tandem, spent);
        Object zone = resolutionZone.invoke(resolution);
        return new Object[]{field(zone, "name"), field(zone, "thickness"), field(zone, "multiplier"),
                field(zone, "loss"), field(zone, "era"),
                resolutionLayer.invoke(resolution), resolutionSpent.invoke(resolution),
                resolutionUsed.invoke(resolution)};
    }

    /** Reads one field of a zone by reflection; the fields are final and the zone is not ours. */
    private static Object field(Object zone, String name) throws Exception {
        java.lang.reflect.Field f = zone.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(zone);
    }

    /** Reverse-looks-up which profile name an override object is registered under. */
    private static String profileOf(ArmorConfig.ArmorOverride ov) {
        if (ov == null) {
            return null;
        }
        for (Map.Entry<String, ArmorConfig.ArmorOverride> e : current.overrides.entrySet()) {
            if (e.getValue() == ov) {
                return e.getKey();
            }
        }
        throw new IllegalArgumentException("override is not registered on the vehicle");
    }

    private static void splitBoundary() throws Exception {
        if (current.hull_front_split == null) {
            System.out.println("no hull front split on this vehicle");
            System.out.println();
            return;
        }
        double split = current.hull_front_split.split;
        System.out.println("hull front split boundary (split = " + split + ")");
        // The split is a normalised height: -1 is the bottom of the hull box, +1 the top. Saying
        // what that works out to in coverage is the whole point of the number.
        double upperShare = (1.0 - split) / 2.0;
        System.out.printf("  glacis = upper %.0f%% of the front face, lower plate = lower %.0f%%%n",
                upperShare * 100, (1.0 - upperShare) * 100);
        System.out.printf("%9s  %-34s %s%n", "height", "base table", "'heat' profile");
        for (double ratio : new double[] {-1.0, -0.5, -0.25, 0.0, 0.25, 0.5, 1.0}) {
            boolean upper = ratio >= current.hull_front_split.split;
            ArmorConfig.Plate base = upper ? current.hull_front_split.upper : current.hull_front_split.lower;
            ArmorConfig.Plate heat = plateAt("heat", false, 3, ratio);
            System.out.printf("%9.2f  %-34s %s%n", ratio,
                    base.thickness + "mm x" + base.multiplier + (upper ? "  UPPER" : "  LOWER"),
                    heat.thickness + "mm x" + heat.multiplier);
        }
        System.out.println();
    }

    /** The glacis/lower plate a hull front hit at the given normalised height meets. */
    private static ArmorConfig.Plate plateAt(String profile, boolean turret, int face, double hullLocalY)
            throws Exception {
        Object[] resolved = zoneFor(profile, turret, face, hull(0.0, hullLocalY, 0.0), false);
        return new ArmorConfig.Plate((Float) resolved[1], (Float) resolved[2]);
    }

    /**
     * Checks where the reactive side panels begin, against the vehicle's real hull length.
     *
     * <p>There is no separate "ERA zone" in the model to copy from - it is a geometric assumption,
     * so this prints the boundary it produces in blocks.
     */
    private static void reactiveBoundaryCheck(Model model) throws Exception {
        float fraction = current.hull_side_era_front_fraction;
        double hullLength = model.hull()[5] / 16.0;
        System.out.println("hull side reactive panels (assumed boundary, not modelled)");
        System.out.printf("  front %.0f%% of the hull -> hits at localZ >= %.2f count as reactive%n",
                fraction * 100, 1.0 - 2.0 * fraction);
        System.out.printf("%12s %16s   %s%n", "localZ", "blocks from nose", "result");
        for (double z : new double[]{1.0, 0.5, 0.1, 0.0, -0.1, -0.5, -1.0}) {
            boolean era = (Boolean) isReactivePanel.invoke(null, fraction, z);
            System.out.printf("%12.2f %16.2f   %s%n", z, (1.0 - z) / 2.0 * hullLength,
                    era ? "reactive" : "bare");
        }
        System.out.println();
    }

    /**
     * Checks the gun mount weak spot against the vehicle's real turret box, and reports how far the
     * barrel's axis sits from the centre of the front plate - which is what the configured offsets
     * are, and which had to be measured off the model because the design did not state them.
     */
    private static void gunMountCheck(Model model) throws Exception {
        if (current.turret_weak_spot == null) {
            return;
        }
        ArmorConfig.WeakSpot spot = current.turret_weak_spot;
        double[] origin = {model.turret()[0], model.turret()[1], model.turret()[2]};
        double[] size = {model.turret()[3], model.turret()[4], model.turret()[5]};

        Vector3f center = new Vector3f(
                (float) ((origin[0] + size[0] / 2) / 16),
                (float) ((origin[1] + size[1] / 2) / 16),
                (float) ((origin[2] + size[2] / 2) / 16));
        Vector3f extents = new Vector3f(
                (float) (size[0] / 32), (float) (size[1] / 32), (float) (size[2] / 32));
        OBB box = new OBB(center, extents, new Quaternionf());

        System.out.println("gun mount weak spot on the turret front plate");
        System.out.printf("  front plate : %.2f wide x %.2f tall blocks%n", extents.x * 2, extents.y * 2);
        System.out.printf("  weak spot   : radius %.2f blocks -> %.0f%% of width, %.0f%% of height, %.0f%% of the face%n",
                spot.radius,
                100 * 2 * spot.radius / (extents.x * 2),
                100 * 2 * spot.radius / (extents.y * 2),
                100 * (Math.PI * spot.radius * spot.radius) / (extents.x * 2 * extents.y * 2));
        System.out.printf("  offsets     : (%.2f, %.2f) blocks from the plate centre%n",
                spot.offset_x, spot.offset_y);

        // The hits are placed relative to the configured circle's own centre, which is the point a
        // round aimed at the gun actually lands on, so the report says whether that circle is where
        // it is meant to be rather than whether the plate's middle is covered.
        double front = extents.z;
        double[][] cases = {
                {0.0, 0.0, 0.0},
                {0.25, 0.0, 0.0},
                {0.30, 0.30, 0.0},
                {0.45, 0.0, 0.0},
                {0.0, 0.50, 0.0},
                {0.0, 0.0, 1.0},
        };
        double ux = spot.offset_x;
        double uy = spot.offset_y;
        System.out.printf("%10s %10s %10s   %s%n", "dX", "dY", "dZ", "result");
        for (double[] c : cases) {
            Vec3 hit = new Vec3(center.x + ux + c[0], center.y + uy + c[1], center.z + front + c[2]);
            boolean in = (Boolean) hitsFrontDisc.invoke(null, box, hit, spot);
            System.out.printf("%10.2f %10.2f %10.2f   %s%n", c[0], c[1], c[2],
                    in ? "gun mount" : "turret front armour");
        }
        System.out.println();
    }

    /**
     * Checks the track strip against the T-64's real hull box.
     *
     * <p>main_structure cube: origin (-35.17, 0, -61.13551), size (70.34, 30.89389, 131.58912)
     * in 1/16-block units, so the hull half-width is 70.34/32 = 2.20 blocks. The track's centre
     * line sits at |X| = 1.69 blocks, i.e. 0.77 of the half-width.
     */
    private static void trackZoneCheck(Model model) throws Exception {
        if (current.track_ends == null) {
            return;
        }
        ArmorConfig.TrackEnds ends = current.track_ends;
        double halfWidth = model.hull()[3] / 32.0;
        double trackBlock = model.gearInner() / 16.0;
        System.out.println("track strips on the hull's front / rear face");
        System.out.printf("  hull half-width %.2f blocks; the running gear starts at %.2f blocks%n",
                halfWidth, trackBlock);
        System.out.printf("  inner_edge %.2f -> %.2f blocks  (%s by %.2f blocks)%n",
                ends.inner_edge, ends.inner_edge * halfWidth,
                ends.inner_edge * halfWidth <= trackBlock ? "inboard of the gear" : "OUTBOARD OF THE GEAR",
                Math.abs(trackBlock - ends.inner_edge * halfWidth));
        System.out.printf("  top at %.2f of the hull box%n", ends.top);
        System.out.printf("%10s %10s %10s   %s%n", "|X| frac", "X block", "Y frac", "result");
        double outer = trackBlock / halfWidth;
        double[][] cases = {
                {outer, -0.50},
                {outer, 0.50},
                {ends.inner_edge, -0.01},
                {ends.inner_edge - 0.01, -0.50},
                {0.30, -0.50},
                {0.95, -0.20},
        };
        for (double[] c : cases) {
            boolean track = (Boolean) isTrackEnd.invoke(null, ends, c[0], c[1]);
            System.out.printf("%10.2f %10.2f %10.2f   %s%n", c[0], c[0] * halfWidth, c[1],
                    track ? "track" : "hull armour");
        }
        System.out.println();
    }

    /**
     * Checks the road-wheel strip against the vehicle's real hull box, and reports what a shot at
     * each height resolves to.
     *
     * <p>This is the zone with the least model behind it: unlike the tracks, the wheels do have real
     * collision boxes, but the hull's own box encloses them, so nothing about a hit can say "wheel"
     * except its height. `WheelProbe` establishes that; this says where the line ends up in blocks.
     */
    private static void wheelCheck(Model model) throws Exception {
        if (current.road_wheels == null) {
            return;
        }
        ArmorConfig.RoadWheels wheels = current.road_wheels;
        double halfHeight = model.hull()[4] / 32.0;
        double gearEdge = model.gearInner() / 16.0;
        System.out.println("road wheels on the hull's flanks");
        System.out.printf("  hull flank %.2f blocks tall; the wheel boxes start at |X| = %.2f blocks%n",
                halfHeight * 2, gearEdge);
        System.out.printf("  strip top %.3f -> %.2f blocks up the flank (%.0f%% of it)%n",
                wheels.top, (1.0 + wheels.top) * halfHeight,
                100 * (1.0 + wheels.top) / 2.0);
        System.out.printf("%11s %11s   %-19s %s%n", "Y (frac)", "Y (blocks)", "result", "zone");
        for (double y : new double[]{-1.0, -0.5, 0.0, 0.04, 0.041, 0.2, 0.5, 1.0}) {
            boolean wheel = (Boolean) isRoadWheel.invoke(null, wheels, y);
            Object[] resolved = zoneFor(null, false, 1, hull(0.0, y, 0.50), false);
            System.out.printf("%11.3f %11.2f   %-19s %s %s%n", y, (1.0 + y) * halfHeight,
                    wheel ? "road wheels" : "hull flank armour",
                    resolved[0], String.format("%.0fmm x%.1f", resolved[1], resolved[2]));
        }
        System.out.println();
    }

    /** What each configured round does, and what its blast is made of. */
    private static void ammoTableCheck(ArmorConfig.Profile profile) {
        System.out.println("configured ammunition");
        System.out.printf("%-34s %7s %11s %-9s %-15s %-6s %-8s %-9s %-6s %s%n",
                "weapon id", "pen", "multiplier", "profile", "spall", "blind", "base blast",
                "on pen", "burst", "fuze");
        for (Map.Entry<String, ArmorConfig.Ammo> e : profile.ammo_penetration.entrySet()) {
            ArmorConfig.Ammo a = e.getValue();
            String mult = a.multiplier == null ? "per-plate" : "x" + a.multiplier;
            String spall = !a.hasSpall() ? "-"
                    : a.spallFallsOff()
                            ? a.spall_damage_max + "->" + a.spall_damage_min + " of " + a.spall_falloff_to
                            : a.spall_damage_min + "-" + a.spall_damage_max;
            String blind = a.spall_blindness_ticks > 0
                    ? fmt(a.spall_blindness_ticks / 20.0) + "s"
                    : "-";
            String base = a.replacesBaseExplosion() ? "dropped" : "kept";
            String onPen = a.hasSpall() && a.spallOnPenetration() ? "yes" : "no";
            String burst = a.penetration_damage == null ? "-" : String.valueOf(a.penetration_damage);
            // What the second model is, for the one round that has one: what the fuze switches to,
            // and how far the proximity fuze reaches.
            String fuze = a.proximity == null ? "-"
                    : "yes -> " + (a.proximity.profile == null ? "default" : a.proximity.profile)
                            + " " + fmt(a.proximity.penetration) + "mm r" + fmt(a.proximityRadius());
            System.out.printf("%-34s %7.1f %11s %-9s %-15s %-6s %-8s %-9s %-6s %s%n",
                    e.getKey(), a.penetration, mult,
                    a.profile == null ? "default" : a.profile, spall, blind, base, onPen, burst, fuze);
        }
        System.out.println();
        System.out.println("note: a warhead round also makes the base mod fire its own VehicleExplosion on");
        System.out.println("      every entity hit (2a46m1_125mm_heat declares damage 1500, radius 4), which");
        System.out.println("      knows nothing about armour. 'base blast: dropped' means that hit is cancelled");
        System.out.println("      for armoured vehicles and the spall numbers stand in its place.");
    }

    private static ArmorConfig.Ammo roundUsing(ArmorConfig.Profile profile, String profileName) {
        for (ArmorConfig.Ammo a : profile.ammo_penetration.values()) {
            if (profileName.equals(a.profile)) {
                return a;
            }
        }
        return null;
    }

    private static String roundName(ArmorConfig.Profile profile, String profileName) {
        for (Map.Entry<String, ArmorConfig.Ammo> e : profile.ammo_penetration.entrySet()) {
            if (profileName.equals(e.getValue().profile)) {
                return e.getKey();
            }
        }
        return "?";
    }

    private static ArmorConfig.Profile defaultProfile() throws Exception {
        Method defaultProfile = ArmorConfig.class.getDeclaredMethod("defaultProfile");
        defaultProfile.setAccessible(true);
        return (ArmorConfig.Profile) defaultProfile.invoke(null);
    }

    private static String fmt(double value) {
        return String.format("%.2f", value);
    }
}
