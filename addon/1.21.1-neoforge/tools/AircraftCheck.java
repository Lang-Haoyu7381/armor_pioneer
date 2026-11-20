import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ywzjvehicle.armor.ArmorConfig;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Holds the aircraft model in the shipped config against the pack's own structure files.
 *
 * <p>An aircraft is the one vehicle kind whose armour is mostly <em>not</em> a number on a face:
 * the skin is thin, and the answer is what the round finds behind it. That makes the geometry of
 * the components the part worth checking - a pilot box that does not contain the pilot, an engine
 * in the tail, a seat back in front of the pilot would all read as a perfectly plausible config
 * and would all fire the wrong numbers in play.
 *
 * <p>Everything measured here comes from the pack: the main cube, the fuselage's half-width, and
 * the cubes of the parts named by a component. Nothing is copied from the config, so a pack update
 * that moves a bone shows up here instead of in a dogfight.
 *
 * <p>Usage: AircraftCheck [--pack &lt;path&gt;]
 */
public class AircraftCheck {

    private static final String NAMESPACE = "tank_assault";

    /** The three aircraft, and the animal each one is for the log. */
    private static final Map<String, String> AIRCRAFT = new LinkedHashMap<>();

    static {
        AIRCRAFT.put("bf109e4", "Bf 109E-4");
        AIRCRAFT.put("fw190a5", "Fw 190A-5");
        AIRCRAFT.put("p51d20", "P-51D-20");
    }

    /** The rounds a fighter meets most often: two rifle-calibre machine guns and two 20mm. */
    private static final String[] GUNS = {
            "tank_assault:gun_mg17",
            "tank_assault:gun_m2_browning",
            "tank_assault:auto_cannon_mg151",
            "tank_assault:auto_cannon_mgffm"
    };

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path pack = args.length > 0 ? PackLocator.asZip(Path.of(args[0]))
                : PackLocator.find(NAMESPACE);
        System.out.println("pack: " + pack);

        ArmorConfig.Profile profile = defaultProfile();
        System.out.println("config_version: " + profile.config_version);

        int checked = 0;
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            for (Map.Entry<String, String> aircraft : AIRCRAFT.entrySet()) {
                String id = aircraft.getKey();
                JsonObject vehicle = read(zip, "data/" + NAMESPACE + "/vehicles/" + id + ".json");
                JsonObject structure = read(zip,
                        "data/" + NAMESPACE + "/models/bedrock/vehicle/" + id + ".structure.json");
                if (vehicle == null || structure == null) {
                    fail(id, "vehicle or structure file is missing from the pack");
                    continue;
                }
                ArmorConfig.VehicleArmor armor = profile.vehicles.get(NAMESPACE + ":" + id);
                if (armor == null) {
                    fail(id, "no vehicle profile in the defaults");
                    continue;
                }
                System.out.println();
                System.out.println("=== " + NAMESPACE + ":" + id + "  (" + aircraft.getValue() + ") ===");
                checked++;
                checkOne(id, vehicle, structure, armor, profile);
            }
        }

        // A tank in the same pack must not have the block: the branch is opt-in, and a vehicle that
        // silently acquired a skin would stop being measured against its plates.
        ArmorConfig.VehicleArmor panther = profile.vehicles.get(NAMESPACE + ":panther");
        check(NAMESPACE + ":panther", "a tank in the same pack has no aircraft block",
                panther != null && panther.aircraft == null,
                panther == null ? "no profile" : "aircraft present");

        // The block has to survive the writer and reader, or the values in memory and the values in
        // the file are two different things - the setting-that-does-nothing shape this project
        // keeps meeting.
        ArmorConfig.Profile back = ArmorConfig.configReader().fromJson(
                ArmorConfig.configWriter().toJson(profile), ArmorConfig.Profile.class);
        for (String id : AIRCRAFT.keySet()) {
            ArmorConfig.VehicleArmor armor = back.vehicles.get(NAMESPACE + ":" + id);
            check(id, "the aircraft block survives a config round trip",
                    armor != null && armor.aircraft != null
                            && armor.aircraft.components != null
                            && armor.aircraft.components.size() >= 5
                            && armor.aircraft.skin != null && armor.aircraft.wing != null,
                    "missing after write/read");
        }

        System.out.println();
        System.out.println("  " + checked + " aircraft checked");
        if (failures > 0) {
            System.out.println("RESULT: " + failures + " aircraft check(s) failed");
            System.exit(1);
        }
        System.out.println("RESULT: every aircraft box agrees with the pack and with the damage rule");
    }

    private static void checkOne(String id, JsonObject vehicle, JsonObject structure,
                                 ArmorConfig.VehicleArmor armor, ArmorConfig.Profile profile) {
        String type = vehicle.has("type") ? vehicle.get("type").getAsString() : "?";
        check(id, "the pack calls it a fixed-wing aircraft",
                "ywzj_vehicle:fixed_wing_vehicle".equals(type), "type is " + type);

        ArmorConfig.AircraftArmor aircraft = armor.aircraft;
        check(id, "the profile carries an aircraft block", aircraft != null,
                "aircraft is null");
        if (aircraft == null) {
            return;
        }

        // The old table is the fallback if the block is deleted: it must not become a battleship.
        check(id, "hull and turret fall back to the skin",
                same(armor.hull, aircraft.skin) && same(armor.turret, aircraft.skin),
                "hull " + describe(armor.hull) + ", turret " + describe(armor.turret)
                        + ", skin " + describe(aircraft.skin));
        check(id, "turret_part_ids is empty", armor.turret_part_ids != null
                && armor.turret_part_ids.length == 0,
                "ids " + java.util.Arrays.toString(armor.turret_part_ids));

        // Geometry, all of it from the pack.
        Geometry geometry = geometry(structure);
        check(id, "the model has a main_structure cube", geometry.main() != null, "no main cube");
        if (geometry.main() == null) {
            return;
        }
        System.out.println("  hull box       : " + box(geometry.main()));
        System.out.println("  fuselage half  : " + fmt(geometry.fuselageHalf())
                + " blocks, wing_from " + fmt(aircraft.wing_from));
        // The threshold sits between the fuselage and the wing: above the fuselage's half-width, or
        // a hit on its skin would count as a wing; below the wing cube's centre, or a wing hit
        // would count as fuselage. The wing centres on these three are around 2.7-3.0 blocks, so
        // 2.0 is a safe upper bound and the printout carries the half-width that was measured.
        check(id, "wing_from sits between the fuselage and the wings",
                aircraft.wing_from > geometry.fuselageHalf() && aircraft.wing_from < 2.0,
                "half " + fmt(geometry.fuselageHalf()) + ", wing_from " + fmt(aircraft.wing_from));

        check(id, "the skin is a positive thickness on every face",
                aircraft.skin.front > 0 && aircraft.skin.side > 0 && aircraft.skin.rear > 0
                        && aircraft.skin.roof > 0 && aircraft.skin.floor > 0,
                describe(aircraft.skin));
        check(id, "the wing is a positive plate",
                aircraft.wing != null && aircraft.wing.thickness > 0 && aircraft.wing.multiplier > 0,
                aircraft.wing == null ? "null" : aircraft.wing.thickness + "mm x"
                        + aircraft.wing.multiplier);
        check(id, "the frame multiplier is positive on every face",
                aircraft.frame_multiplier.front > 0 && aircraft.frame_multiplier.side > 0
                        && aircraft.frame_multiplier.rear > 0
                        && aircraft.frame_multiplier.roof > 0
                        && aircraft.frame_multiplier.floor > 0,
                "frame " + aircraft.frame_multiplier.front);

        Map<String, ArmorConfig.AircraftComponent> components = new LinkedHashMap<>();
        if (aircraft.components != null) {
            for (ArmorConfig.AircraftComponent component : aircraft.components) {
                if (component != null && component.name != null) {
                    components.put(component.name, component);
                }
            }
        }
        System.out.println("  components     : " + components.keySet());
        for (String wanted : List.of("pilot", "engine", "fuel tank", "seat armour", "windscreen")) {
            check(id, "it has a " + wanted + " component", components.containsKey(wanted), "missing");
        }

        Set<String> partIds = partIds(vehicle);
        for (ArmorConfig.AircraftComponent component : components.values()) {
            if (component.center == null || component.center.length != 3
                    || component.half == null || component.half.length != 3) {
                fail(id, component.name + " has no usable box");
                continue;
            }
            check(id, component.name + " is inside the airframe",
                    inside(component, geometry.union()), component.name + " box " + box(component)
                            + " vs airframe " + box(geometry.union()));

            if (component.part_id == null) {
                continue;
            }
            check(id, component.name + " names a part the pack has",
                    partIds.contains(component.part_id),
                    "part_id " + component.part_id + ", parts " + partIds);
            Box partBox = partBox(structure, component.part_id, geometry.centre());
            if (partBox == null) {
                System.out.println("      " + component.part_id
                        + ": no structure bone on this model - box measured from the fuselage");
                continue;
            }
            check(id, component.name + " contains the " + component.part_id + " cube",
                    contains(component, partBox),
                    "box " + box(component) + " vs part " + partBox);
        }

        ArmorConfig.AircraftComponent pilot = components.get("pilot");
        ArmorConfig.AircraftComponent seat = components.get("seat armour");
        ArmorConfig.AircraftComponent screen = components.get("windscreen");
        ArmorConfig.AircraftComponent engine = components.get("engine");
        if (pilot != null && seat != null) {
            check(id, "the seat back is behind the pilot",
                    seat.center[2] + seat.half[2] <= pilot.center[2] - pilot.half[2] + 0.001,
                    "seat z max " + fmt(seat.center[2] + seat.half[2])
                            + ", pilot z min " + fmt(pilot.center[2] - pilot.half[2]));
        }
        if (pilot != null && screen != null) {
            check(id, "the windscreen is in front of the pilot",
                    screen.center[2] - screen.half[2] >= pilot.center[2] + pilot.half[2] - 0.001,
                    "screen z min " + fmt(screen.center[2] - screen.half[2])
                            + ", pilot z max " + fmt(pilot.center[2] + pilot.half[2]));
        }
        if (engine != null) {
            check(id, "the engine is in the nose", engine.center[2] > 0.0F,
                    "engine z " + fmt(engine.center[2]));
        }

        checkArithmetic(id, vehicle, aircraft, components, profile);
        printHits(id, vehicle, aircraft, components, profile);
    }

    /**
     * The seat back has to do its job. A rifle-calibre round from dead astern must be stopped by
     * it and a .50 must get through: those are the two facts the numbers were chosen for, and
     * checking them here is what keeps a later edit to either number from turning the model into
     * decoration.
     */
    private static void checkArithmetic(String id, JsonObject vehicle,
                                        ArmorConfig.AircraftArmor aircraft,
                                        Map<String, ArmorConfig.AircraftComponent> components,
                                        ArmorConfig.Profile profile) {
        ArmorConfig.Ammo rifle = profile.ammo_penetration.get("tank_assault:gun_mg17");
        ArmorConfig.Ammo heavy = profile.ammo_penetration.get("tank_assault:gun_m2_browning");
        ArmorConfig.AircraftComponent seat = components.get("seat armour");
        ArmorConfig.AircraftComponent pilot = components.get("pilot");
        check(id, "both aircraft machine guns are rated", rifle != null && heavy != null,
                "gun_mg17 " + (rifle != null) + ", gun_m2_browning " + (heavy != null));
        if (rifle == null || heavy == null || seat == null || pilot == null) {
            return;
        }
        float afterSkin = rifle.penetration - aircraft.skin.rear;
        // The E-4 and the P-51 carry 8mm seat backs (Gustin; the later G/K is 11mm), so the AP
        // belt the MG 17 is rated with (11mm) gets through - and that is the researched figure,
        // not a seat thickened until this check passes. What the plate does stop is the standard
        // rifle ball (about 5mm), so that is the assertion; the AP case is printed beside it.
        float ballAfterSkin = 5.0F - aircraft.skin.rear;
        check(id, "the seat back stops a standard rifle ball from dead astern",
                ballAfterSkin < seat.armor,
                "ball 5 - skin " + fmt(aircraft.skin.rear) + " = " + fmt(ballAfterSkin)
                        + ", seat " + fmt(seat.armor));
        System.out.printf(Locale.ROOT, "  note: the AP belt (%.0f mm) %s the seat back%n",
                rifle.penetration, afterSkin < seat.armor ? "is stopped by" : "gets through");
        float heavyAfterSkin = heavy.penetration - aircraft.skin.rear;
        check(id, "but not a .50",
                heavyAfterSkin >= seat.armor && pilot.hasEffect(),
                ".50 " + fmt(heavy.penetration) + " - skin " + fmt(aircraft.skin.rear)
                        + " = " + fmt(heavyAfterSkin) + ", seat " + fmt(seat.armor));
        ArmorConfig.Ammo cannon = profile.ammo_penetration.get("tank_assault:auto_cannon_mgffm");
        check(id, "and not a 20mm mine shell",
                cannon != null && cannon.penetration - aircraft.skin.rear >= seat.armor,
                cannon == null ? "mgffm not rated"
                        : "20mm " + fmt(cannon.penetration) + " - skin "
                        + fmt(aircraft.skin.rear) + ", seat " + fmt(seat.armor));
    }

    /** What the numbers add up to, so a balance edit can be read without opening the game. */
    private static void printHits(String id, JsonObject vehicle,
                                  ArmorConfig.AircraftArmor aircraft,
                                  Map<String, ArmorConfig.AircraftComponent> components,
                                  ArmorConfig.Profile profile) {
        float health = vehicle.has("max_health") ? vehicle.get("max_health").getAsFloat() : 0.0F;
        ArmorConfig.AircraftComponent seat = components.get("seat armour");
        ArmorConfig.AircraftComponent pilot = components.get("pilot");
        System.out.println("  health         : " + fmt(health));
        for (String gun : GUNS) {
            ArmorConfig.Ammo round = profile.ammo_penetration.get(gun);
            if (round == null) {
                System.out.println("  " + gun + ": NOT RATED");
                continue;
            }
            float frameResidual = round.penetration - aircraft.skin.side;
            String frame = frameResidual > 0.0F
                    ? hits(health, round.penetrationDamage(frameResidual,
                    aircraft.frame_multiplier.side, aircraft.skin.side)) + " frame"
                    : "skin holds";
            String pilotHits;
            float pilotResidual = round.penetration - aircraft.skin.rear;
            boolean reaches = pilot != null && pilotResidual >= 0.0F;
            if (seat != null && reaches) {
                if (pilotResidual < seat.armor) {
                    reaches = false;
                } else {
                    pilotResidual -= seat.armor;
                }
            }
            if (reaches) {
                float damage = round.penetrationDamage(pilotResidual,
                        pilot.damage_multiplier, aircraft.skin.rear) + pilot.damage;
                pilotHits = hits(health, damage) + " pilot";
            } else {
                pilotHits = "seat stops it";
            }
            System.out.printf(Locale.ROOT, "  %-34s %-18s %s%n", gun, frame, pilotHits);
        }
    }

    private static String hits(float health, float damage) {
        if (damage <= 0.0F) {
            return "no damage";
        }
        return String.valueOf((int) Math.ceil(health / damage));
    }

    // ------------------------------------------------------------------ geometry

    /**
     * The main cube (absolute, for the printout), everything a component may occupy (relative to
     * the main cube's centre, which is the frame the config's boxes are stated in), and the
     * fuselage's half-width.
     */
    private record Geometry(double[] centre, Box main, Box union, double fuselageHalf) {
    }

    private static Geometry geometry(JsonObject structure) {
        Map<String, JsonObject> bones = bones(structure);
        JsonObject mainBone = bones.get("main_structure");
        JsonObject bodyBone = bones.get("vehicle_body");
        Box main = mainBone == null ? null : largestCube(mainBone);
        if (main == null) {
            return new Geometry(new double[]{0, 0, 0}, null, null, 0.0);
        }
        double[] centre = new double[]{
                (main.min[0] + main.max[0]) / 2.0,
                (main.min[1] + main.max[1]) / 2.0,
                (main.min[2] + main.max[2]) / 2.0};
        Box union = main;
        if (bodyBone != null) {
            union = union.union(bonesBox(bodyBone));
        }

        // The fuselage cube is the one containing the main cube's z centre, among the narrow ones.
        // The wing cubes overlap that z on two of the three models, which is why a wing's 2-plus
        // block half-width must not be allowed to win the vote.
        double mainZ = (main.min[2] + main.max[2]) / 2.0;
        double half = 0.0;
        if (bodyBone != null) {
            for (JsonElement element : bodyBone.getAsJsonArray("cubes")) {
                Box cube = cube(element.getAsJsonObject());
                if (cube == null) {
                    continue;
                }
                double cubeHalf = (cube.max[0] - cube.min[0]) / 2.0;
                if (cubeHalf > 1.0) {
                    continue;
                }
                if (cube.min[2] <= mainZ && mainZ <= cube.max[2] && cubeHalf > half) {
                    half = cubeHalf;
                }
            }
        }
        if (half <= 0.0) {
            half = (main.max[0] - main.min[0]) / 2.0;
        }
        return new Geometry(centre, main, union.offset(centre), half);
    }

    /** A part's own cube (not its children), relative to the main cube's centre; null if no bone. */
    private static Box partBox(JsonObject structure, String partId, double[] centre) {
        JsonObject bone = bones(structure).get(partId);
        return bone == null ? null : largestCube(bone).offset(centre);
    }

    private static Map<String, JsonObject> bones(JsonObject structure) {
        Map<String, JsonObject> bones = new TreeMap<>();
        JsonArray list = structure.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject()
                .getAsJsonArray("bones");
        for (JsonElement element : list) {
            JsonObject bone = element.getAsJsonObject();
            if (bone.has("name")) {
                bones.put(bone.get("name").getAsString(), bone);
            }
        }
        return bones;
    }

    private static Box largestCube(JsonObject bone) {
        Box best = null;
        double volume = -1.0;
        if (!bone.has("cubes")) {
            return null;
        }
        for (JsonElement element : bone.getAsJsonArray("cubes")) {
            Box cube = cube(element.getAsJsonObject());
            if (cube == null) {
                continue;
            }
            double v = (cube.max[0] - cube.min[0]) * (cube.max[1] - cube.min[1])
                    * (cube.max[2] - cube.min[2]);
            if (v > volume) {
                volume = v;
                best = cube;
            }
        }
        return best;
    }

    /** The bounds of every cube of a bone, children included, in structure blocks. */
    private static Box bonesBox(JsonObject bone) {
        Box box = null;
        if (bone.has("cubes")) {
            for (JsonElement element : bone.getAsJsonArray("cubes")) {
                Box cube = cube(element.getAsJsonObject());
                if (cube != null) {
                    box = box == null ? cube : box.union(cube);
                }
            }
        }
        return box == null ? new Box(new double[]{0, 0, 0}, new double[]{0, 0, 0}) : box;
    }

    /**
     * One cube in the mod's structure frame: x mirrored, everything divided by 16. Cube pivots and
     * rotations are ignored, which is right for the fuselage, the pilot and the fuel tank and is a
     * bound rather than a measurement for the wings - the union only has to contain the boxes.
     */
    private static Box cube(JsonObject cube) {
        if (!cube.has("origin") || !cube.has("size")) {
            return null;
        }
        double[] origin = array(cube.getAsJsonArray("origin"));
        double[] size = array(cube.getAsJsonArray("size"));
        return new Box(new double[]{
                -(origin[0] + size[0]) / 16.0, origin[1] / 16.0, origin[2] / 16.0},
                new double[]{-origin[0] / 16.0, (origin[1] + size[1]) / 16.0,
                        (origin[2] + size[2]) / 16.0});
    }

    private static double[] array(JsonArray array) {
        double[] values = new double[array.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = array.get(i).getAsDouble();
        }
        return values;
    }

    /** A box in blocks; the config's component boxes are this, relative to the main cube's centre. */
    private record Box(double[] min, double[] max) {
        Box union(Box other) {
            return new Box(new double[]{
                    Math.min(min[0], other.min[0]), Math.min(min[1], other.min[1]),
                    Math.min(min[2], other.min[2])},
                    new double[]{Math.max(max[0], other.max[0]), Math.max(max[1], other.max[1]),
                            Math.max(max[2], other.max[2])});
        }

        /** The same box moved so that {@code origin} becomes the origin. */
        Box offset(double[] origin) {
            return new Box(new double[]{min[0] - origin[0], min[1] - origin[1],
                    min[2] - origin[2]},
                    new double[]{max[0] - origin[0], max[1] - origin[1], max[2] - origin[2]});
        }

        @Override
        public String toString() {
            return "[" + fmt(min[0]) + ".." + fmt(max[0]) + ", "
                    + fmt(min[1]) + ".." + fmt(max[1]) + ", "
                    + fmt(min[2]) + ".." + fmt(max[2]) + "]";
        }
    }

    private static boolean inside(ArmorConfig.AircraftComponent component, Box bounds) {
        if (bounds == null) {
            return false;
        }
        double[] min = new double[]{
                component.center[0] - component.half[0],
                component.center[1] - component.half[1],
                component.center[2] - component.half[2]};
        double[] max = new double[]{
                component.center[0] + component.half[0],
                component.center[1] + component.half[1],
                component.center[2] + component.half[2]};
        // A tenth of a block of slack: the fuselage's own skin is the boundary and some of these
        // boxes are deliberately the compartment rather than a single cube.
        for (int i = 0; i < 3; i++) {
            if (min[i] < bounds.min[i] - 0.1 || max[i] > bounds.max[i] + 0.1) {
                return false;
            }
        }
        return true;
    }

    private static boolean contains(ArmorConfig.AircraftComponent component, Box part) {
        double[] min = new double[]{
                component.center[0] - component.half[0],
                component.center[1] - component.half[1],
                component.center[2] - component.half[2]};
        double[] max = new double[]{
                component.center[0] + component.half[0],
                component.center[1] + component.half[1],
                component.center[2] + component.half[2]};
        for (int i = 0; i < 3; i++) {
            if (min[i] > part.min[i] + 0.001 || max[i] < part.max[i] - 0.001) {
                return false;
            }
        }
        return true;
    }

    private static String box(ArmorConfig.AircraftComponent component) {
        return "[" + fmt(component.center[0] - component.half[0]) + ".."
                + fmt(component.center[0] + component.half[0]) + ", "
                + fmt(component.center[1] - component.half[1]) + ".."
                + fmt(component.center[1] + component.half[1]) + ", "
                + fmt(component.center[2] - component.half[2]) + ".."
                + fmt(component.center[2] + component.half[2]) + "]";
    }

    private static String box(Box box) {
        return "[" + fmt(box.min[0]) + ".." + fmt(box.max[0]) + ", "
                + fmt(box.min[1]) + ".." + fmt(box.max[1]) + ", "
                + fmt(box.min[2]) + ".." + fmt(box.max[2]) + "]";
    }

    // ------------------------------------------------------------------ plumbing

    private static Set<String> partIds(JsonObject vehicle) {
        Set<String> ids = new LinkedHashSet<>();
        if (vehicle.has("parts")) {
            for (JsonElement element : vehicle.getAsJsonArray("parts")) {
                JsonObject part = element.getAsJsonObject();
                if (part.has("id")) {
                    ids.add(part.get("id").getAsString());
                }
            }
        }
        return ids;
    }

    private static boolean same(ArmorConfig.ArmorSet one, ArmorConfig.ArmorSet other) {
        return one != null && other != null
                && one.front == other.front && one.side == other.side && one.rear == other.rear
                && one.roof == other.roof && one.floor == other.floor;
    }

    private static String describe(ArmorConfig.ArmorSet set) {
        return set == null ? "null" : fmt(set.front) + "/" + fmt(set.side) + "/" + fmt(set.rear)
                + "/" + fmt(set.roof) + "/" + fmt(set.floor);
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static JsonObject read(ZipFile zip, String name) throws Exception {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return JsonParser.parseString(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static ArmorConfig.Profile defaultProfile() throws Exception {
        Method method = ArmorConfig.class.getDeclaredMethod("defaultProfile");
        method.setAccessible(true);
        return (ArmorConfig.Profile) method.invoke(null);
    }

    private static void check(String id, String what, boolean ok, String detail) {
        if (!ok) {
            fail(id, what + " - " + detail);
        }
    }

    private static void fail(String id, String what) {
        failures++;
        System.out.println("  FAIL " + id + ": " + what);
    }
}
