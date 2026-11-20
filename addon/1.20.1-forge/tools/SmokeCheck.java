import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ywzjvehicle.armor.ArmorConfig;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Holds every Armor Pioneer smoke bank against the base mod's own launch path and the display
 * models the user marked.
 *
 * <p><b>The pack no longer owns the firing.</b> The banks use the base mod's
 * {@code ywzj_vehicle:launcher_smoke_grenade} in the turret's weapons list again: that weapon is
 * independent, so the base H key walks the turret's {@code independentWeapons} and fires it. The
 * part is in {@code ripple} mode, which is the base mod's own smoke behaviour - one grenade per
 * press, from the current bolt, with {@code countFire(1)} advancing to the next tube each time.
 * {@code salvo} would fire the whole bank at once, which is not what the base vehicles do. The
 * addon's old depth-row key, its network message and its HUD block are gone, and this tool fails if
 * any of them comes back. What remains the pack's job is the geometry: the user's marked `smoke`
 * bone, one structure hitbox and one explicit bolt per tube, and the same transform the renderer
 * uses.
 *
 * <p>The K2's display model now carries 21 tubes (it gained a forward cluster); its part,
 * structure boxes, bolts and magazine are all sized to that, not to the old twelve.
 *
 * <p>Every half of this fails silently in game: a missing display bone is invisible, a tube with no
 * structure cube is not hittable, a bolt offset in the wrong frame puts the grenade at the vehicle
 * origin, and a part left out of {@code turret_part_ids} rates the launcher as hull. The maths here
 * is the same as the display path's (see {@code game_transform.py} in the earlier VT-5 tools).
 *
 * <p>Usage: SmokeCheck [pack.zip]
 *        Defaults to the newest armor_pioneer zip under the 1.20.1 instance's pack folder.
 */
public class SmokeCheck {

    private static final String NAMESPACE = "armor_pioneer";
    /** The base mod's own launcher: independent, and its H key fires one round per press. */
    private static final String BASE_LAUNCHER = "ywzj_vehicle:launcher_smoke_grenade";
    private static final String PART = "turret_smoke_grenade";
    private static final String BONE = "turret_smoke_grenade";
    private static final String BARREL = "turret_smoke_grenade_barrel";
    private static final String DISPLAY_BONE = "smoke";
    private static final String NAME_KEY = "part.armor_pioneer.turret_smoke_grenade";

    /**
     * The vehicles the pack gives a smoke bank, and how many marked tubes each has.
     *
     * <p>The VT-5, VT-4 and Tianma-20 carry twelve; the Type 10 eight; the Leopard 2A4 sixteen;
     * the K2 twenty-one after the user added a forward cluster to its `smoke` bone. A vehicle in
     * this map is required to pass every check, so deleting a bank cannot pass silently; a vehicle
     * not in it is skipped with a note.
     */
    private static final Map<String, Integer> TUBES_BY_VEHICLE = new LinkedHashMap<>();

    static {
        TUBES_BY_VEHICLE.put("vt5", 12);
        TUBES_BY_VEHICLE.put("vt4", 12);
        TUBES_BY_VEHICLE.put("k2", 21);
        TUBES_BY_VEHICLE.put("tianma20", 12);
        TUBES_BY_VEHICLE.put("type10", 8);
        TUBES_BY_VEHICLE.put("leopard2a4", 16);
    }

    private static final Set<String> SMOKE_VEHICLES = TUBES_BY_VEHICLE.keySet();

    private static int tubes(String vehicle) {
        return TUBES_BY_VEHICLE.getOrDefault(vehicle, 0);
    }

    /** The structure hitboxes follow the display geometry to within a hundredth of a model unit. */
    private static final double POSITION_TOLERANCE = 0.05;

    /** The explicit bolt rotations are quoted to a tenth of a degree in the generated table. */
    private static final double ANGLE_TOLERANCE = 0.5;

    private static int failures;

    public static void main(String[] args) throws Exception {
        Path pack = pack(args);
        System.out.println("pack: " + pack);

        Method defaultProfile = ArmorConfig.class.getDeclaredMethod("defaultProfile");
        defaultProfile.setAccessible(true);
        ArmorConfig.Profile defaults = (ArmorConfig.Profile) defaultProfile.invoke(null);

        Map<String, JsonObject> languages = languages(pack);

        try (ZipFile zip = new ZipFile(pack.toFile())) {
            List<String> vehicles = vehicles(zip);
            if (vehicles.isEmpty()) {
                check("the pack has at least one vehicle", false);
            }
            for (String vehicle : vehicles) {
                System.out.println();
                System.out.println("=== " + vehicle + " ===");
                if (!SMOKE_VEHICLES.contains(vehicle)) {
                    System.out.println("  note  the pack gives this tank no smoke bank - not checked");
                    continue;
                }
                JsonObject definition = read(zip, vehiclesDir() + vehicle + ".json");
                JsonObject structure = read(zip, structureDir() + vehicle + ".structure.json");
                // The model a display config names is not always `<vehicle>.json`: the Leopard
                // 2A4's current model is `leopard2a4_user.json`. Resolve the display config's
                // model id rather than assuming the stem.
                JsonObject displayConfig = read(zip, "assets/" + NAMESPACE
                        + "/display/vehicle/" + vehicle + ".json");
                String modelName = vehicle;
                if (displayConfig != null) {
                    String model = string(displayConfig, "model");
                    String prefix = NAMESPACE + ":entity/";
                    if (model.startsWith(prefix)) {
                        modelName = model.substring(prefix.length());
                    }
                }
                JsonObject display = read(zip, displayDir() + modelName + ".json");
                if (definition == null || structure == null || display == null) {
                    check(vehicle + ": vehicle, structure and display files all present", false);
                    continue;
                }
                Display displayTools = new Display(display, tubes(vehicle));
                check(vehicle + ": the display model has a marked " + DISPLAY_BONE + " bone",
                        displayTools.hasSmoke());
                check(vehicle + ": the marked smoke bone carries " + tubes(vehicle) + " tubes ("
                        + displayTools.tubes.size() + ")",
                        displayTools.tubes.size() == tubes(vehicle));
                checkPart(vehicle, definition, languages);
                checkTurretEntry(vehicle, definition);
                checkStructure(vehicle, structure, displayTools);
                checkBolts(vehicle, definition, displayTools);
                checkConfig(vehicle, defaults);
            }
        }

        checkBaseMethod(pack);

        System.out.println();
        if (failures == 0) {
            System.out.println("RESULT: every Armor Pioneer smoke bank uses the base mod's "
                    + "independent launcher in ripple mode - one grenade per press, cycling "
                    + "through the tubes - with one hitbox and one bolt per marked display tube "
                    + "(K2 twenty-one), and the addon's old depth-row key, message and HUD are gone");
        } else {
            System.out.println("RESULT: " + failures + " smoke check(s) failed - see above");
            System.exit(1);
        }
    }

    private static void checkPart(String vehicle, JsonObject definition,
                                  Map<String, JsonObject> languages) {
        JsonObject part = part(definition, PART);
        if (part == null) {
            check(vehicle + ": has a " + PART + " part", false);
            return;
        }
        check(vehicle + ": the smoke launcher is a weapon part",
                "ywzj_vehicle:weapon".equals(string(part, "type")));
        check(vehicle + ": the launch bank hangs off " + BONE,
                BONE.equals(string(part, "structure_bone")));
        check(vehicle + ": the launcher renders the marked display bone " + DISPLAY_BONE,
                DISPLAY_BONE.equals(string(part, "render_bone")));
        check(vehicle + ": the smoke launcher is not a seat",
                part.has("is_seat") && !part.get("is_seat").getAsBoolean());
        int capacity = integer(part, "ammo_capacity");
        check(vehicle + ": carries one round per tube (" + capacity + ")",
                capacity == tubes(vehicle));
        // The base H key fires an independent weapon through doClientShoot. Ripple is the base
        // mod's own smoke behaviour: one aim context from the current bolt per press, and
        // countFire(1) moves to the next tube. Salvo would empty the whole bank in one press.
        check(vehicle + ": fires one grenade per press in ripple, the base H method",
                "ripple".equalsIgnoreCase(string(part, "firing_mode")));
        check(vehicle + ": its display name has a translation key (" + NAME_KEY + ")",
                NAME_KEY.equals(string(part, "name")));
        for (Map.Entry<String, JsonObject> language : languages.entrySet()) {
            check(vehicle + ": " + language.getKey() + " translates " + NAME_KEY,
                    language.getValue().has(NAME_KEY));
        }
    }

    private static void checkTurretEntry(String vehicle, JsonObject definition) {
        JsonObject turret = part(definition, "turret");
        if (turret == null || !turret.has("weapons")) {
            check(vehicle + ": the turret has a weapons list", false);
            return;
        }
        List<JsonObject> entries = new ArrayList<>();
        collectObjects(turret.getAsJsonArray("weapons"), entries);
        JsonObject entry = entries.stream()
                .filter(object -> BASE_LAUNCHER.equals(string(object, "id")))
                .findFirst().orElse(null);
        check(vehicle + ": the turret names the base mod's launcher (" + BASE_LAUNCHER + ")",
                entry != null);
        if (entry != null) {
            check(vehicle + ": the launcher is created on the " + PART + " sub unit",
                    PART.equals(string(entry, "part_unit_id")));
            // It must not be secondary: the base H key only fires the turret's independent list.
            // The base weapon data carries independent:true, and combineAndInit files it there;
            // a stray secondary flag would be a lie about how the weapon is reached.
            check(vehicle + ": the launcher is not filed as a secondary weapon",
                    !bool(entry, "secondary"));
        }
        boolean packLauncherNamed = entries.stream()
                .anyMatch(object -> string(object, "id").startsWith(NAMESPACE + ":launcher_smoke"));
        check(vehicle + ": no pack-local smoke launcher is named", !packLauncherNamed);
    }

    private static void checkStructure(String vehicle, JsonObject structure, Display display) {
        JsonObject geometry = structure.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
        Map<String, JsonObject> byName = new LinkedHashMap<>();
        for (JsonElement element : geometry.getAsJsonArray("bones")) {
            JsonObject bone = element.getAsJsonObject();
            byName.put(string(bone, "name"), bone);
        }
        JsonObject root = byName.get(BONE);
        JsonObject barrel = byName.get(BARREL);
        check(vehicle + ": the structure model has a " + BONE + " bone", root != null);
        check(vehicle + ": the structure model has a " + BARREL + " bone", barrel != null);
        if (root == null || barrel == null) {
            return;
        }
        check(vehicle + ": " + BONE + " descends from the turret",
                descendsFrom(byName, BONE, "turret"));
        check(vehicle + ": " + BARREL + " is a child of " + BONE,
                BONE.equals(string(barrel, "parent")));
        check(vehicle + ": the smoke root is a small bracket, not a duplicate of the tubes",
                root.has("cubes") && root.getAsJsonArray("cubes").size() == 1);
        JsonArray cubes = barrel.has("cubes") ? barrel.getAsJsonArray("cubes") : new JsonArray();
        int expected = tubes(vehicle);
        check(vehicle + ": the barrel carries one hitbox per marked tube (" + cubes.size() + ")",
                cubes.size() == expected);

        // The structure pivots and hitboxes have to be the same geometry the display mark
        // describes, in the same game frame. A structure cube's file x is mirrored by the loader.
        double[] pivotFile = numbers(root.getAsJsonArray("pivot"));
        double[] pivotGame = {-pivotFile[0], pivotFile[1], pivotFile[2]};
        check(vehicle + ": the root pivot is the bank centre",
                near(pivotGame, display.bankCentre, POSITION_TOLERANCE));
        double[] barrelPivotFile = numbers(barrel.getAsJsonArray("pivot"));
        double[] barrelPivotGame = {-barrelPivotFile[0], barrelPivotFile[1], barrelPivotFile[2]};
        check(vehicle + ": the barrel pivot is the bank centre",
                near(barrelPivotGame, display.bankCentre, POSITION_TOLERANCE));

        boolean boxesMatch = cubes.size() == expected;
        if (boxesMatch) {
            for (int i = 0; i < expected; i++) {
                JsonObject cube = cubes.get(i).getAsJsonObject();
                double[] origin = numbers(cube.getAsJsonArray("origin"));
                double[] size = numbers(cube.getAsJsonArray("size"));
                double[] gameMin = {-(origin[0] + size[0]), origin[1], origin[2]};
                double[] gameMax = {-origin[0], origin[1] + size[1], origin[2] + size[2]};
                if (!near(gameMin, display.tubes.get(i).min, POSITION_TOLERANCE)
                        || !near(gameMax, display.tubes.get(i).max, POSITION_TOLERANCE)) {
                    boxesMatch = false;
                    break;
                }
            }
        }
        check(vehicle + ": every hitbox is the marked tube's display AABB", boxesMatch);

        JsonObject turret = byName.get("turret");
        if (turret == null || !turret.has("cubes") || turret.getAsJsonArray("cubes").size() == 0) {
            check(vehicle + ": the turret bone has a box to sit the bank on", false);
            return;
        }
        // The turret hitbox may be split into several cubes (the VT-4's is split around
        // its ERA panels), so a tube must be inside *some* turret cube, not the largest.
        List<double[]> turretBoxes = new ArrayList<>();
        for (JsonElement element : turret.getAsJsonArray("cubes")) {
            JsonObject cube = element.getAsJsonObject();
            double[] origin = numbers(cube.getAsJsonArray("origin"));
            double[] size = numbers(cube.getAsJsonArray("size"));
            turretBoxes.add(new double[]{-(origin[0] + size[0]), origin[1], origin[2]});
            turretBoxes.add(new double[]{-origin[0], origin[1] + size[1], origin[2] + size[2]});
        }
        boolean inside = true;
        for (DisplayTube tube : display.tubes) {
            boolean onOne = false;
            for (int box = 0; box < turretBoxes.size(); box += 2) {
                double[] min = turretBoxes.get(box);
                double[] max = turretBoxes.get(box + 1);
                boolean inThis = true;
                for (int axis = 0; axis < 3; axis++) {
                    if (tube.centre[axis] < min[axis] - 0.01
                            || tube.centre[axis] > max[axis] + 0.01) {
                        inThis = false;
                    }
                }
                if (inThis) {
                    onOne = true;
                    break;
                }
            }
            if (!onOne) {
                inside = false;
            }
        }
        check(vehicle + ": every marked tube is on the turret box", inside);
    }

    private static void checkBolts(String vehicle, JsonObject definition, Display display) {
        JsonObject part = part(definition, PART);
        if (part == null || !part.has("bolts")) {
            check(vehicle + ": the smoke part carries explicit bolts", false);
            return;
        }
        JsonArray bolts = part.getAsJsonArray("bolts");
        int expected = tubes(vehicle);
        check(vehicle + ": one bolt per marked tube (" + bolts.size() + ")",
                bolts.size() == expected);
        if (bolts.size() != expected) {
            return;
        }
        boolean offsets = true;
        boolean angles = true;
        for (int i = 0; i < expected; i++) {
            JsonObject bolt = bolts.get(i).getAsJsonObject();
            if (!bolt.has("offset") || !bolt.get("offset").isJsonArray()
                    || bolt.getAsJsonArray("offset").size() != 3) {
                offsets = false;
                continue;
            }
            double[] offset = numbers(bolt.getAsJsonArray("offset"));
            double[] wanted = new double[3];
            for (int axis = 0; axis < 3; axis++) {
                wanted[axis] = (display.tubes.get(i).muzzle[axis]
                        - display.bankCentre[axis]) / 16.0;
            }
            if (!near(offset, wanted, 0.01)) {
                offsets = false;
            }
            double xRot = number(bolt, "x_rot", 0.0);
            double yRot = number(bolt, "y_rot", 0.0);
            if (Math.abs(xRot - display.tubes.get(i).xRot) > ANGLE_TOLERANCE
                    || Math.abs(yRot - display.tubes.get(i).yRot) > ANGLE_TOLERANCE) {
                angles = false;
            }
            if (Math.abs(number(bolt, "barrel_length", 99.0)) > 1.0E-4) {
                check(vehicle + ": bolt " + i + " has a zero barrel length", false);
            }
        }
        check(vehicle + ": every bolt offset is its tube's muzzle relative to the bank centre",
                offsets);
        check(vehicle + ": every bolt points along its marked tube", angles);
    }

    private static void checkConfig(String vehicle, ArmorConfig.Profile defaults) {
        ArmorConfig.VehicleArmor armor = defaults.vehicles.get(NAMESPACE + ":" + vehicle);
        if (armor == null) {
            check(vehicle + ": the addon has an armour profile for it", false);
            return;
        }
        boolean listed = armor.turret_part_ids != null
                && java.util.Arrays.asList(armor.turret_part_ids).contains(PART);
        check(vehicle + ": the addon files " + PART + " under the turret", listed);
    }

    /**
     * The base method's other half: nothing of the pack's own launcher, and nothing of the addon's
     * old depth-row key, may be left behind to fight it.
     *
     * <p>A pack-local launcher file would be dead weight, and worse, an invitation to point the
     * turret at it again without restoring the addon message that used to fire it: then the turret
     * would carry a weapon nothing can shoot. The source checks are the same argument one level up
     * - the H key must belong to the base mod, and the message id that used to be registered must
     * be gone, or a client and server on protocol 14 would disagree about the channel.
     */
    private static void checkBaseMethod(Path pack) throws Exception {
        System.out.println();
        System.out.println("=== the base mod owns the launch ===");
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            check("the pack no longer ships launcher_smoke_grenade.json",
                    read(zip, "data/" + NAMESPACE + "/weapons/launcher_smoke_grenade.json") == null);
            check("the pack no longer ships launcher_smoke_grenade_16.json",
                    read(zip, "data/" + NAMESPACE
                            + "/weapons/launcher_smoke_grenade_16.json") == null);
            check("the pack no longer ships its launcher display files",
                    read(zip, "assets/" + NAMESPACE
                            + "/display/weapon/launcher_smoke_grenade.json") == null
                            && read(zip, "assets/" + NAMESPACE
                            + "/display/weapon/launcher_smoke_grenade_16.json") == null);
        }

        String keys = text("src/main/java/com/ywzjvehicle/armor/WeaponKeyHandler.java");
        check("the addon no longer reads H - the base smoke key is untouched",
                keys != null && !keys.contains("key.keyboard.h"));
        check("the addon no longer names the smoke group message",
                keys != null && !keys.contains("SmokeActionMessage"));
        String network = text("src/main/java/com/ywzjvehicle/armor/network/"
                + "VehicleArmorNetwork.java");
        check("the smoke group message is no longer registered",
                network != null && !network.contains("SmokeActionMessage"));
        check("the channel version is 16",
                network != null && network.contains("VERSION = \"16\""));
        check("the smoke group message source is retired",
                !Files.exists(Path.of("src/main/java/com/ywzjvehicle/armor/network/"
                        + "SmokeActionMessage.java")));
        for (String locale : new String[]{"zh_cn", "en_us"}) {
            String lang = text("src/main/resources/assets/ywzj_vehicle_armor/lang/"
                    + locale + ".json");
            check(locale + ": the smoke group and operator lines are gone",
                    lang != null && !lang.contains("smoke_group")
                            && !lang.contains("smoke_operator")
                            && !lang.contains("hud.ywzj_vehicle_armor.smoke"));
        }
    }

    private static String text(String path) {
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    // --- the display path's transform ---------------------------------------------------------

    /**
     * The user's marked smoke bone, transformed the way the renderer transforms it.
     *
     * <p>This is the `game_transform.py` port, in Java: the file rotation is
     * {@code Rz(rz) . Ry(-ry) . Rx(-rx)}, pivots and cube origins have x mirrored, and a bone's
     * chain maps a point by {@code M . p + t}. The same transform is what the structure model's
     * loader applies, so the two frames are directly comparable.
     */
    private static final class Display {
        private final Map<String, JsonObject> bones = new LinkedHashMap<>();
        private final Map<String, double[][]> matrices = new LinkedHashMap<>();
        private final Map<String, double[]> translations = new LinkedHashMap<>();
        final List<DisplayTube> tubes = new ArrayList<>();
        double[] bankCentre = {0, 0, 0};

        private final int expectedTubes;

        Display(JsonObject display, int expectedTubes) {
            this.expectedTubes = expectedTubes;
            JsonObject geometry = display.getAsJsonArray("minecraft:geometry")
                    .get(0).getAsJsonObject();
            for (JsonElement element : geometry.getAsJsonArray("bones")) {
                JsonObject bone = element.getAsJsonObject();
                bones.put(string(bone, "name"), bone);
            }
            if (!bones.containsKey(DISPLAY_BONE)) {
                return;
            }
            chain(DISPLAY_BONE);
            JsonObject smoke = bones.get(DISPLAY_BONE);
            double[][] m = matrices.get(DISPLAY_BONE);
            double[] t = translations.get(DISPLAY_BONE);
            if (!smoke.has("cubes")) {
                return;
            }
            List<JsonElement> cubeElements = new ArrayList<>();
            for (JsonElement element : smoke.getAsJsonArray("cubes")) {
                cubeElements.add(element);
            }
            // The Leopard 2A4's smoke bone carries 32 cubes: 16 tube bodies and 16 caps.
            // The other banks carry one cube per tube. When the bone has exactly twice the
            // expected count, the tube bodies are the long cubes; the short caps are their bases
            // and are not separate tubes.
            if (expectedTubes > 0 && cubeElements.size() == expectedTubes * 2) {
                List<JsonElement> bodies = new ArrayList<>();
                for (JsonElement element : cubeElements) {
                    JsonArray size = element.getAsJsonObject().getAsJsonArray("size");
                    if (size != null && size.get(2).getAsDouble() >= 3.0) {
                        bodies.add(element);
                    }
                }
                if (bodies.size() == expectedTubes) {
                    cubeElements = bodies;
                }
            }
            for (JsonElement element : cubeElements) {
                JsonObject cube = element.getAsJsonObject();
                double[] origin = numbers(cube.getAsJsonArray("origin"));
                double[] size = numbers(cube.getAsJsonArray("size"));
                double[] pivot = cube.has("pivot") ? numbers(cube.getAsJsonArray("pivot"))
                        : new double[]{0, 0, 0};
                double[] rot = cube.has("rotation") ? numbers(cube.getAsJsonArray("rotation"))
                        : new double[]{0, 0, 0};
                double[] og = {-(origin[0] + size[0]), origin[1], origin[2]};
                double[] pg = {-pivot[0], pivot[1], pivot[2]};
                double[][] rc = rotation(rot);
                double[] min = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
                double[] max = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
                for (double dx : new double[]{0, size[0]}) {
                    for (double dy : new double[]{0, size[1]}) {
                        for (double dz : new double[]{0, size[2]}) {
                            double[] off = {og[0] + dx - pg[0], og[1] + dy - pg[1],
                                    og[2] + dz - pg[2]};
                            double[] rp = add(apply(m, add(apply(rc, off), pg)), t);
                            for (int axis = 0; axis < 3; axis++) {
                                min[axis] = Math.min(min[axis], rp[axis]);
                                max[axis] = Math.max(max[axis], rp[axis]);
                            }
                        }
                    }
                }
                double[] centre = new double[3];
                for (int axis = 0; axis < 3; axis++) {
                    centre[axis] = (min[axis] + max[axis]) / 2.0;
                }
                double[] axis = normalise(apply(m, apply(rc, new double[]{0, 0, 1})));
                double sign = (axis[1] + axis[2]) >= 0 ? 1.0 : -1.0;
                double[] direction = {sign * axis[0], sign * axis[1], sign * axis[2]};
                double[] muzzle = {centre[0] + sign * size[2] / 2.0 * axis[0],
                        centre[1] + sign * size[2] / 2.0 * axis[1],
                        centre[2] + sign * size[2] / 2.0 * axis[2]};
                double xRot = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1,
                        -direction[1]))));
                double yRot = Math.toDegrees(Math.atan2(direction[0], direction[2]));
                tubes.add(new DisplayTube(min, max, centre, muzzle, xRot, yRot));
            }
            if (!tubes.isEmpty()) {
                for (int axis = 0; axis < 3; axis++) {
                    double sum = 0;
                    for (DisplayTube tube : tubes) {
                        sum += tube.centre[axis];
                    }
                    bankCentre[axis] = sum / tubes.size();
                }
            }
        }

        boolean hasSmoke() {
            return bones.containsKey(DISPLAY_BONE);
        }

        private double[][] chain(String name) {
            if (matrices.containsKey(name)) {
                return matrices.get(name);
            }
            JsonObject bone = bones.get(name);
            double[][] r = rotation(bone.has("rotation")
                    ? numbers(bone.getAsJsonArray("rotation")) : new double[]{0, 0, 0});
            double[] pivot = bone.has("pivot") ? numbers(bone.getAsJsonArray("pivot"))
                    : new double[]{0, 0, 0};
            double[] p = {-pivot[0], pivot[1], pivot[2]};
            String parent = string(bone, "parent");
            double[][] m;
            double[] t;
            if (!parent.isEmpty() && bones.containsKey(parent)) {
                double[][] mp = chain(parent);
                double[] tp = translations.get(parent);
                m = multiply(mp, r);
                t = add(apply(mp, subtract(p, apply(r, p))), tp);
            } else {
                m = r;
                t = subtract(p, apply(r, p));
            }
            matrices.put(name, m);
            translations.put(name, t);
            return m;
        }

        private static double[][] rotation(double[] values) {
            double rx = Math.toRadians(values[0]);
            double ry = Math.toRadians(values[1]);
            double rz = Math.toRadians(values[2]);
            return multiply(rotZ(rz), multiply(rotY(-ry), rotX(-rx)));
        }

        private static double[][] rotX(double a) {
            double c = Math.cos(a);
            double s = Math.sin(a);
            return new double[][]{{1, 0, 0}, {0, c, -s}, {0, s, c}};
        }

        private static double[][] rotY(double a) {
            double c = Math.cos(a);
            double s = Math.sin(a);
            return new double[][]{{c, 0, s}, {0, 1, 0}, {-s, 0, c}};
        }

        private static double[][] rotZ(double a) {
            double c = Math.cos(a);
            double s = Math.sin(a);
            return new double[][]{{c, -s, 0}, {s, c, 0}, {0, 0, 1}};
        }

        private static double[][] multiply(double[][] a, double[][] b) {
            double[][] r = new double[3][3];
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    for (int k = 0; k < 3; k++) {
                        r[i][j] += a[i][k] * b[k][j];
                    }
                }
            }
            return r;
        }

        private static double[] apply(double[][] m, double[] v) {
            return new double[]{m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
                    m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
                    m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]};
        }

        private static double[] add(double[] a, double[] b) {
            return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
        }

        private static double[] subtract(double[] a, double[] b) {
            return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
        }

        private static double[] normalise(double[] v) {
            double n = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
            return n == 0 ? new double[]{0, 0, 1} : new double[]{v[0] / n, v[1] / n, v[2] / n};
        }
    }

    private record DisplayTube(double[] min, double[] max, double[] centre, double[] muzzle,
                               double xRot, double yRot) {
    }

    // --- pack access ---------------------------------------------------------------------------

    private static Map<String, JsonObject> languages(Path pack) throws Exception {
        Map<String, JsonObject> languages = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            for (String language : new String[]{"zh_cn", "en_us"}) {
                JsonObject data = read(zip, "assets/" + NAMESPACE + "/lang/" + language + ".json");
                if (data != null) {
                    languages.put(language, data);
                }
            }
        }
        check("the pack ships both zh_cn and en_us", languages.size() == 2);
        return languages;
    }

    private static List<String> vehicles(ZipFile zip) {
        Set<String> names = new TreeSet<>();
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.startsWith(vehiclesDir()) && name.endsWith(".json")) {
                names.add(name.substring(vehiclesDir().length(),
                        name.length() - ".json".length()));
            }
        }
        return new ArrayList<>(names);
    }

    private static JsonObject part(JsonObject definition, String id) {
        JsonArray parts = definition.getAsJsonArray("parts");
        for (JsonElement element : parts) {
            JsonObject part = element.getAsJsonObject();
            if (id.equals(string(part, "id"))) {
                return part;
            }
        }
        return null;
    }

    private static void collectObjects(JsonArray array, List<JsonObject> found) {
        for (JsonElement element : array) {
            if (element.isJsonArray()) {
                collectObjects(element.getAsJsonArray(), found);
            } else if (element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                found.add(object);
                if (object.has("ids")) {
                    collectObjects(object.getAsJsonArray("ids"), found);
                }
            }
        }
    }

    private static boolean descendsFrom(Map<String, JsonObject> bones, String bone, String ancestor) {
        Set<String> seen = new TreeSet<>();
        String current = bone;
        while (current != null && seen.add(current)) {
            if (current.equals(ancestor)) {
                return true;
            }
            JsonObject object = bones.get(current);
            if (object == null || !object.has("parent")) {
                return false;
            }
            current = string(object, "parent");
        }
        return false;
    }

    private static JsonObject largestCube(JsonObject bone) {
        if (!bone.has("cubes")) {
            return null;
        }
        JsonObject best = null;
        double bestVolume = -1.0;
        for (JsonElement element : bone.getAsJsonArray("cubes")) {
            JsonObject cube = element.getAsJsonObject();
            double[] size = numbers(cube.getAsJsonArray("size"));
            double volume = size[0] * size[1] * size[2];
            if (volume > bestVolume) {
                bestVolume = volume;
                best = cube;
            }
        }
        return best;
    }

    private static double[] numbers(JsonArray array) {
        double[] values = new double[array.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = array.get(i).getAsDouble();
        }
        return values;
    }

    private static boolean near(double[] a, double[] b, double tolerance) {
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > tolerance) {
                return false;
            }
        }
        return true;
    }

    private static JsonObject read(ZipFile zip, String name) {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        try {
            return JsonParser.parseString(new String(zip.getInputStream(entry).readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsString() : "";
    }

    private static int integer(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsInt() : 0;
    }

    private static double number(JsonObject object, String key, double fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsDouble() : fallback;
    }

    private static boolean bool(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                && object.get(key).getAsBoolean();
    }

    private static String vehiclesDir() {
        return "data/" + NAMESPACE + "/vehicles/";
    }

    private static String structureDir() {
        return "data/" + NAMESPACE + "/models/bedrock/vehicle/";
    }

    private static String displayDir() {
        return "assets/" + NAMESPACE + "/models/bedrock/entity/";
    }

    private static Path pack(String[] args) throws Exception {
        return PackLocator.of(args, NAMESPACE);
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
