import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ywzjvehicle.armor.ArmorConfig;
import com.ywzjvehicle.armor.Periscope;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The VT-5's hatch periscope: the config point, the pack part it hangs off, and the view it gives.
 *
 * <p>Three things can go wrong with a camera point and none of them is a compile error. The
 * configured seat part can stop existing in the pack. The structure bone can lose its parent, at
 * which point the base's {@code worldPositionWithBaseRot} stops applying the turret's rotation and
 * the periscope stays with the hull. And the point itself can be inside the vehicle, which shows
 * the player the inside of a wall with nothing in the log - the same failure the RWS sight had, and
 * the reason that check casts a ray at the display model rather than trusting the offset.
 *
 * <p>So this reads the defaults from the mod's own {@code defaultProfile()}, reads the vehicle and
 * both models from the installed pack, and:
 *
 * <ul>
 *   <li>holds the shipped offsets to the part's structure pivot;</li>
 *   <li>requires the structure bone to be a child of {@code turret}, so the view turns with the
 *       turret rather than with the hull;</li>
 *   <li>requires the camera point to be above the hatch and outside every structure cube;</li>
 *   <li>casts the centre ray and a +-10 degree nine-by-nine cone at every display cube, and
 *       requires all of them clear - the same measurement that rejected the RWS camera.</li>
 * </ul>
 *
 * <p>The geometry math is the port of {@code render_model.py}'s bone chain and
 * {@code hitbox_compare.world_corners}: x mirrored on both a bone's pivot and a cube's origin,
 * bone rotations as {@code Rz * Ry * Rx} with x and y negated, and the chain composed down the
 * parent tree. It is deliberately the same instrument the VT-5 rebuild used, so this check and the
 * scripts that measured the vehicle cannot disagree about where a cube is.
 *
 * <p>Usage: PeriscopeCheck [pack-root]
 */
public class PeriscopeCheck {

    private static final String NAMESPACE = "armor_pioneer";
    private static final String VEHICLE = "vt5";
    private static final String VEHICLE_ID = NAMESPACE + ":" + VEHICLE;

    private static final String SEAT_PART = "commander_machine_gun";
    private static final String GUNNER_PART = "turret";
    private static final String TURRET_BONE = "turret";
    private static final String HATCH_BONE = "turret_door";

    /**
     * What the defaults say, so a silent config edit cannot move the view off the hatch.
     *
     * <p>The point is the outer-left, forward corner of the commander's left hatch, not its
     * centre: the remote weapon station is above the centre-left of the hatch. These figures were
     * re-measured on 2026-11-04 when the VT-5's seats were moved to commander-left / gunner-right.
     */
    private static final double EXPECTED_X = -0.4745;
    private static final double EXPECTED_Y = 0.3036;
    private static final double EXPECTED_Z = 1.0307;
    private static final double OFFSET_TOLERANCE = 1.0E-3;

    private static int failures;

    public static void main(String[] args) throws Exception {
        Path pack = PackLocator.of(args, NAMESPACE);
        System.out.println("pack: " + pack);
        System.out.println();

        Periscope.Settings settings = defaultSettings();
        System.out.println("=== the shipped point ===");
        check("the VT-5 has a periscope entry in the defaults", settings != null);
        if (settings == null) {
            finish();
            return;
        }
        check("it belongs to " + SEAT_PART, SEAT_PART.equals(settings.seat_part_id));
        check("offset x is the measured " + EXPECTED_X,
                Math.abs(settings.offset_x - EXPECTED_X) <= OFFSET_TOLERANCE);
        check("offset y is the measured " + EXPECTED_Y,
                Math.abs(settings.offset_y - EXPECTED_Y) <= OFFSET_TOLERANCE);
        check("offset z is the measured " + EXPECTED_Z,
                Math.abs(settings.offset_z - EXPECTED_Z) <= OFFSET_TOLERANCE);
        check("it follows the turret's yaw rather than the gun's pitch",
                TURRET_BONE.equals(settings.yaw_part_id));

        JsonObject vehicle = null;
        JsonObject structure = null;
        JsonObject display = null;
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            vehicle = read(zip, "data/" + NAMESPACE + "/vehicles/" + VEHICLE + ".json");
            structure = read(zip, "data/" + NAMESPACE + "/models/bedrock/vehicle/"
                    + VEHICLE + ".structure.json");
            display = read(zip, "assets/" + NAMESPACE + "/models/bedrock/entity/"
                    + VEHICLE + ".json");
        }
        System.out.println();
        System.out.println("=== the pack ===");
        check("the vehicle definition exists", vehicle != null);
        check("the structure model exists", structure != null);
        check("the display model exists", display != null);
        if (vehicle == null || structure == null || display == null) {
            finish();
            return;
        }

        JsonObject part = part(vehicle, SEAT_PART);
        check("the seat part " + SEAT_PART + " exists", part != null);
        if (part == null) {
            finish();
            return;
        }
        check("it is a seat", flag(part, "is_seat"));
        check("it is a weapon station", "ywzj_vehicle:weapon".equals(string(part, "type")));
        JsonObject gunner = part(vehicle, GUNNER_PART);
        check("the gunner part " + GUNNER_PART + " exists", gunner != null);
        if (gunner != null) {
            double[] commanderSeat = numbers(part, "seat_offset");
            double[] gunnerSeat = numbers(gunner, "seat_offset");
            double[] gunnerSight = numbers(gunner, "optical_sight_offset");
            check("the commander sits on the left",
                    commanderSeat != null && commanderSeat[0] < 0.0);
            check("the gunner sits on the right",
                    gunnerSeat != null && gunnerSeat[0] > 0.0);
            check("the gunner's CRT sight is on the gunner's side",
                    gunnerSight != null && gunnerSight[0] > 0.0);
        }
        JsonObject gunnerDoor = part(vehicle, "door_gunner");
        JsonObject commanderDoor = part(vehicle, "door_commander");
        check("the gunner's hatch is the right one (turret_door2)",
                gunnerDoor != null && "turret_door2".equals(string(gunnerDoor, "structure_bone")));
        check("the commander's hatch is the left one (turret_door)",
                commanderDoor != null && "turret_door".equals(string(commanderDoor, "structure_bone")));
        JsonObject yawPart = part(vehicle, settings.yaw_part_id);
        check("the yaw part " + settings.yaw_part_id + " exists", yawPart != null);
        check("the yaw part is a rotating weapon station", yawPart != null
                && "ywzj_vehicle:weapon".equals(string(yawPart, "type")));
        String structureBone = string(part, "structure_bone");
        check("it names a structure bone", !structureBone.isBlank());
        if (structureBone.isBlank()) {
            finish();
            return;
        }

        JsonObject structureGeometry = geometry(structure);
        JsonObject displayGeometry = geometry(display);
        Map<String, JsonObject> structureBones = bones(structureGeometry);
        JsonObject seatBone = structureBones.get(structureBone);
        check("the structure bone " + structureBone + " exists", seatBone != null);
        if (seatBone == null) {
            finish();
            return;
        }
        check("it is parented to " + TURRET_BONE + " (so the view turns with the turret)",
                TURRET_BONE.equals(seatBone.get("parent") == null
                        ? null : seatBone.get("parent").getAsString()));

        double[] pivot = bonePosition(structureGeometry, structureBone);
        double[] camera = {pivot[0] + settings.offset_x,
                pivot[1] + settings.offset_y,
                pivot[2] + settings.offset_z};
        System.out.printf("  part pivot  : (%.4f, %.4f, %.4f)%n", pivot[0], pivot[1], pivot[2]);
        System.out.printf("  camera      : (%.4f, %.4f, %.4f)%n", camera[0], camera[1], camera[2]);

        System.out.println();
        System.out.println("=== the point is on the hatch, not in the roof ===");
        List<Box> structureBoxes = boxes(structureGeometry);
        String inside = inside(camera, structureBoxes);
        check("the camera is outside every structure cube", inside == null);
        if (inside != null) {
            System.out.println("         inside " + inside);
        }
        Box turret = tallestBoxOf(structureGeometry, structureBoxes, TURRET_BONE);
        check("the " + TURRET_BONE + " has a box", turret != null);
        if (turret != null) {
            double clearance = camera[1] - turret.hi[1];
            System.out.printf("  turret top  : %.4f, camera clears it by %.4f%n",
                    turret.hi[1], clearance);
            check("the camera is above the turret box", clearance > 0.05);
        }

        System.out.println();
        System.out.println("=== the view is not looking into the vehicle ===");
        List<Box> displayBoxes = boxes(displayGeometry);
        Box hatch = tallestBoxOf(displayGeometry, displayBoxes, HATCH_BONE);
        check("the display model still has the " + HATCH_BONE + " hatch", hatch != null);
        if (hatch != null) {
            double clearance = camera[1] - hatch.hi[1];
            System.out.printf("  hatch top   : %.4f, camera clears it by %.4f%n",
                    hatch.hi[1], clearance);
            check("the camera is above the hatch lid", clearance > 0.05);
        }
        check("the camera is outside every display cube", inside(camera, displayBoxes) == null);

        Box centre = cast(camera, new double[]{0.0, 0.0, 1.0}, displayBoxes, 8.0);
        check("the centre ray is clear", centre == null);
        if (centre != null) {
            System.out.printf("         hits %s at %.3f blocks%n", centre.name, centre.entry);
        }
        int blocked10 = blocked(camera, displayBoxes, 10.0);
        System.out.println("  +-10 deg cone: " + blocked10 + "/81 blocked");
        check("the whole +-10 degree cone is clear", blocked10 == 0);

        System.out.println();
        System.out.println("=== the client wiring ===");
        check("the view class is in the compiled classes",
                PeriscopeCheck.class.getResource(
                        "/com/ywzjvehicle/armor/client/PeriscopeView.class") != null);
        check("the camera mixin is in the compiled classes",
                PeriscopeCheck.class.getResource(
                        "/com/ywzjvehicle/armor/mixin/PeriscopeCameraMixin.class") != null);
        String mixins = resource("/ywzj_vehicle_armor.mixins.json");
        check("the mixin is registered as a client mixin",
                mixins != null && clientMixins(mixins).contains("PeriscopeCameraMixin"));
        JsonObject zh = lang("zh_cn");
        JsonObject en = lang("en_us");
        for (String key : new String[]{
                "key.ywzj_vehicle_armor.periscope",
                "message.ywzj_vehicle_armor.periscope_on",
                "message.ywzj_vehicle_armor.periscope_off",
                "message.ywzj_vehicle_armor.periscope_none",
                "message.ywzj_vehicle_armor.periscope_seat",
                "hud.ywzj_vehicle_armor.view",
                "hud.ywzj_vehicle_armor.periscope"}) {
            check("both languages have " + key, has(zh, key) && has(en, key));
        }

        finish();
    }

    // --- the default profile ---------------------------------------------------------------

    private static Periscope.Settings defaultSettings() throws Exception {
        Method defaultProfile = ArmorConfig.class.getDeclaredMethod("defaultProfile");
        defaultProfile.setAccessible(true);
        ArmorConfig.Profile profile = (ArmorConfig.Profile) defaultProfile.invoke(null);
        if (profile.periscopes == null) {
            return null;
        }
        return profile.periscopes.get(VEHICLE_ID);
    }

    // --- pack reading -----------------------------------------------------------------------

    private static JsonObject part(JsonObject definition, String id) {
        JsonArray parts = definition.has("parts") && definition.get("parts").isJsonArray()
                ? definition.getAsJsonArray("parts") : new JsonArray();
        for (JsonElement element : parts) {
            JsonObject part = element.getAsJsonObject();
            if (id.equals(string(part, "id"))) {
                return part;
            }
        }
        return null;
    }

    private static JsonObject geometry(JsonObject document) {
        JsonElement geometry = document.get("minecraft:geometry");
        if (geometry == null) {
            return null;
        }
        if (geometry.isJsonArray()) {
            return geometry.getAsJsonArray().get(0).getAsJsonObject();
        }
        return geometry.getAsJsonObject();
    }

    private static Map<String, JsonObject> bones(JsonObject geometry) {
        Map<String, JsonObject> out = new HashMap<>();
        if (geometry == null || !geometry.has("bones")) {
            return out;
        }
        for (JsonElement element : geometry.getAsJsonArray("bones")) {
            JsonObject bone = element.getAsJsonObject();
            out.put(string(bone, "name"), bone);
        }
        return out;
    }

    // --- geometry ---------------------------------------------------------------------------

    /** One cube's axis-aligned box in the vehicle frame, in blocks. */
    private static final class Box {
        final String name;
        final double[] lo;
        final double[] hi;
        double entry;

        Box(String name, double[] lo, double[] hi) {
            this.name = name;
            this.lo = lo;
            this.hi = hi;
        }
    }

    private static List<Box> boxes(JsonObject geometry) {
        List<Box> out = new ArrayList<>();
        if (geometry == null || !geometry.has("bones")) {
            return out;
        }
        Map<String, JsonObject> byName = bones(geometry);
        Map<String, Chain> chains = new HashMap<>();
        for (JsonElement element : geometry.getAsJsonArray("bones")) {
            JsonObject bone = element.getAsJsonObject();
            String name = string(bone, "name");
            if (!bone.has("cubes") || !bone.get("cubes").isJsonArray()) {
                continue;
            }
            Chain transform = chain(name, byName, chains);
            for (JsonElement cubeElement : bone.getAsJsonArray("cubes")) {
                JsonObject cube = cubeElement.getAsJsonObject();
                double[] lo = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
                double[] hi = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
                for (double[] point : corners(cube, transform)) {
                    for (int axis = 0; axis < 3; axis++) {
                        lo[axis] = Math.min(lo[axis], point[axis]);
                        hi[axis] = Math.max(hi[axis], point[axis]);
                    }
                }
                out.add(new Box(name, lo, hi));
            }
        }
        return out;
    }

    /** A bone's global transform: the rotation matrix and the translation it carries. */
    private static final class Chain {
        final double[][] m;
        final double[] t;

        Chain(double[][] m, double[] t) {
            this.m = m;
            this.t = t;
        }
    }

    /** The bone's global transform, composed down its parents. */
    private static Chain chain(String name, Map<String, JsonObject> bones,
                               Map<String, Chain> cache) {
        Chain done = cache.get(name);
        if (done != null) {
            return done;
        }
        JsonObject bone = bones.get(name);
        double[][] m = identity();
        double[] t = {0.0, 0.0, 0.0};
        if (bone != null) {
            double[] rotation = numbers(bone, "rotation");
            double[] pivot = numbers(bone, "pivot");
            if (rotation != null && (rotation[0] != 0.0 || rotation[1] != 0.0
                    || rotation[2] != 0.0)) {
                double[][] r = rotation(rotation[0], rotation[1], rotation[2]);
                double[] p = {-pivot[0], pivot[1], pivot[2]};
                double[] rotated = apply(r, p);
                m = r;
                t = new double[]{p[0] - rotated[0], p[1] - rotated[1], p[2] - rotated[2]};
            }
            String parent = bone.get("parent") == null ? null : bone.get("parent").getAsString();
            if (parent != null && bones.containsKey(parent)) {
                Chain parentChain = chain(parent, bones, cache);
                double[] composed = new double[3];
                for (int i = 0; i < 3; i++) {
                    composed[i] = parentChain.m[i][0] * t[0]
                            + parentChain.m[i][1] * t[1]
                            + parentChain.m[i][2] * t[2] + parentChain.t[i];
                }
                m = multiply(parentChain.m, m);
                t = composed;
            }
        }
        Chain result = new Chain(m, t);
        cache.put(name, result);
        return result;
    }

    /** The bone's global pivot in blocks, in the vehicle frame. */
    private static double[] bonePosition(JsonObject geometry, String name) {
        Map<String, JsonObject> byName = bones(geometry);
        Chain transform = chain(name, byName, new HashMap<>());
        JsonObject bone = byName.get(name);
        double[] pivot = numbers(bone, "pivot");
        double[] p = {-pivot[0], pivot[1], pivot[2]};
        double[] rotated = apply(transform.m, p);
        return new double[]{(rotated[0] + transform.t[0]) / 16.0,
                (rotated[1] + transform.t[1]) / 16.0,
                (rotated[2] + transform.t[2]) / 16.0};
    }

    private static List<double[]> corners(JsonObject cube, Chain chain) {
        double[] origin = numbers(cube, "origin");
        double[] size = numbers(cube, "size");
        double[] o = {-(origin[0] + size[0]), origin[1], origin[2]};
        List<double[]> out = new ArrayList<>(8);
        for (int x = 0; x <= 1; x++) {
            for (int y = 0; y <= 1; y++) {
                for (int z = 0; z <= 1; z++) {
                    double[] point = {o[0] + x * size[0], o[1] + y * size[1], o[2] + z * size[2]};
                    double[] rotation = numbers(cube, "rotation");
                    double[] pivot = numbers(cube, "pivot");
                    if (rotation != null && (rotation[0] != 0.0 || rotation[1] != 0.0
                            || rotation[2] != 0.0) && pivot != null) {
                        double[][] r = rotation(rotation[0], rotation[1], rotation[2]);
                        double[] p = {-pivot[0], pivot[1], pivot[2]};
                        point = about(p, r, point);
                    }
                    double[] moved = apply(chain.m, point);
                    for (int axis = 0; axis < 3; axis++) {
                        moved[axis] = (moved[axis] + chain.t[axis]) / 16.0;
                    }
                    out.add(moved);
                }
            }
        }
        return out;
    }

    /** The tallest box owned by a named bone, which is the one a camera has to clear. */
    private static Box tallestBoxOf(JsonObject geometry, List<Box> boxes, String name) {
        Box best = null;
        for (Box box : boxes) {
            if (name.equals(box.name) && (best == null || box.hi[1] > best.hi[1])) {
                best = box;
            }
        }
        return best;
    }

    private static String inside(double[] point, List<Box> boxes) {
        for (Box box : boxes) {
            boolean within = true;
            for (int axis = 0; axis < 3; axis++) {
                if (point[axis] < box.lo[axis] - 1.0E-9
                        || point[axis] > box.hi[axis] + 1.0E-9) {
                    within = false;
                    break;
                }
            }
            if (within) {
                return box.name;
            }
        }
        return null;
    }

    private static Box cast(double[] origin, double[] direction, List<Box> boxes, double max) {
        Box best = null;
        double bestEntry = Double.MAX_VALUE;
        for (Box box : boxes) {
            double entry = -Double.MAX_VALUE;
            double exit = Double.MAX_VALUE;
            boolean hit = true;
            for (int axis = 0; axis < 3; axis++) {
                if (Math.abs(direction[axis]) < 1.0E-9) {
                    if (origin[axis] < box.lo[axis] || origin[axis] > box.hi[axis]) {
                        hit = false;
                        break;
                    }
                } else {
                    double a = (box.lo[axis] - origin[axis]) / direction[axis];
                    double b = (box.hi[axis] - origin[axis]) / direction[axis];
                    entry = Math.max(entry, Math.min(a, b));
                    exit = Math.min(exit, Math.max(a, b));
                }
            }
            if (!hit || exit < Math.max(entry, 0.0) || entry < 0.0 || entry > max) {
                continue;
            }
            if (entry < bestEntry) {
                box.entry = entry;
                bestEntry = entry;
                best = box;
            }
        }
        return best;
    }

    private static int blocked(double[] origin, List<Box> boxes, double halfDegrees) {
        int hits = 0;
        for (int ix = -4; ix <= 4; ix++) {
            for (int iy = -4; iy <= 4; iy++) {
                double yaw = Math.toRadians(halfDegrees * ix / 4.0);
                double pitch = Math.toRadians(halfDegrees * iy / 4.0);
                double[] direction = {Math.sin(yaw) * Math.cos(pitch), Math.sin(pitch),
                        Math.cos(yaw) * Math.cos(pitch)};
                if (cast(origin, direction, boxes, 8.0) != null) {
                    hits++;
                }
            }
        }
        return hits;
    }

    // --- the small linear algebra the model format needs ------------------------------------

    private static double[][] identity() {
        return new double[][]{{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
    }

    private static double[][] multiply(double[][] a, double[][] b) {
        double[][] out = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                out[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
            }
        }
        return out;
    }

    /** {@code Rz * Ry * Rx} with the model's x and y angles negated. */
    private static double[][] rotation(double rxDegrees, double ryDegrees, double rzDegrees) {
        double rx = Math.toRadians(-rxDegrees);
        double ry = Math.toRadians(-ryDegrees);
        double rz = Math.toRadians(rzDegrees);
        double cx = Math.cos(rx);
        double sx = Math.sin(rx);
        double cy = Math.cos(ry);
        double sy = Math.sin(ry);
        double cz = Math.cos(rz);
        double sz = Math.sin(rz);
        return new double[][]{
                {cz * cy, cz * sy * sx - sz * cx, cz * sy * cx + sz * sx},
                {sz * cy, sz * sy * sx + cz * cx, sz * sy * cx - cz * sx},
                {-sy, cy * sx, cy * cx}
        };
    }

    private static double[] apply(double[][] m, double[] v) {
        return new double[]{m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
                m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
                m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]};
    }

    private static double[] about(double[] pivot, double[][] m, double[] v) {
        double[] p = {v[0] - pivot[0], v[1] - pivot[1], v[2] - pivot[2]};
        double[] r = apply(m, p);
        return new double[]{r[0] + pivot[0], r[1] + pivot[1], r[2] + pivot[2]};
    }

    // --- JSON helpers -----------------------------------------------------------------------

    private static double[] numbers(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonArray()) {
            return null;
        }
        JsonArray array = object.getAsJsonArray(key);
        double[] out = new double[array.size()];
        for (int i = 0; i < array.size(); i++) {
            out[i] = array.get(i).getAsDouble();
        }
        return out;
    }

    private static String string(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsString() : "";
    }

    private static boolean flag(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive()
                && object.get(key).getAsBoolean();
    }

    private static JsonObject read(ZipFile zip, String name) throws Exception {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    private static String resource(String path) {
        try (InputStream in = PeriscopeCheck.class.getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonObject lang(String locale) {
        String text = resource("/assets/ywzj_vehicle_armor/lang/" + locale + ".json");
        return text == null ? null : JsonParser.parseString(text).getAsJsonObject();
    }

    private static boolean has(JsonObject object, String key) {
        return object != null && object.has(key);
    }

    private static List<String> clientMixins(String text) {
        List<String> out = new ArrayList<>();
        JsonArray list = JsonParser.parseString(text).getAsJsonObject().getAsJsonArray("client");
        if (list != null) {
            for (JsonElement element : list) {
                out.add(element.getAsString());
            }
        }
        return out;
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }

    private static void finish() {
        System.out.println();
        if (failures > 0) {
            System.out.println("RESULT: " + failures + " periscope check(s) failed");
            System.exit(1);
        }
        System.out.println("RESULT: the VT-5's hatch periscope is configured, above the hatch, "
                + "and looking out of the vehicle");
    }
}
