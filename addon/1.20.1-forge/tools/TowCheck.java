import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Checks the Leopard 2A4's tow hooks against the base mod's hook rules.
 *
 * <p><b>The failure this exists for is a parenting one.</b>
 * {@code BaseVehicleData.buildOBBs} builds a part's {@code VehicleCubeGroup} only for a bone that
 * sits one level below the file's synthetic root. A file-level root qualifies; a child of a
 * file-level root such as {@code vehicle_body} does not. A hook whose bone is a grandchild has a
 * null {@code structureGroup} and an empty {@code partCubeOBBs}, and
 * {@code HookUnit.isUsable()} then answers false - no tips, no ray hit, no connection, and nothing
 * in the log. The Leopard's two hook bones were exactly that: children of {@code vehicle_body}.
 *
 * <p>The second assertion is the hitbox. The base's own working hooks use an 8x8x6-unit box (half
 * a block square and 0.375 deep); the Leopard's had a 30x6x2-unit bar, 0.125 of a block deep. A
 * 0.125-deep invisible plate is not a practical thing to aim at from a moving tank. The check
 * requires every axis of the hook hitbox to be at least a quarter of a block, and prints the
 * resulting AABB so the next person can see what the player has to hit.
 *
 * <p>Usage: TowCheck [pack.zip]
 *        Defaults to the installed armor_pioneer pack, zip or folder.
 */
public class TowCheck {

    private static final String NAMESPACE = "armor_pioneer";
    private static final String VEHICLE = "leopard2a4";
    /** A hook hitbox thinner than this on any axis is too fine to aim at deliberately. */
    private static final float MIN_AXIS = 0.25F;
    private static final float UNIT = 16.0F;

    private static int failures;

    public static void main(String[] args) throws Exception {
        System.out.println("=== the Leopard's front and rear tow hooks ===");
        Path pack = PackLocator.of(args, NAMESPACE);
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            JsonObject vehicle = json(zip, "data/armor_pioneer/vehicles/"
                    + VEHICLE + ".json");
            JsonObject structure = json(zip, "data/armor_pioneer/models/bedrock/vehicle/"
                    + VEHICLE + ".structure.json");
            if (vehicle == null || structure == null) {
                finish();
                return;
            }
            Map<String, JsonObject> parts = byId(vehicle.getAsJsonArray("parts"));
            Map<String, JsonObject> bones = bones(structure);
            for (String id : new String[]{"hook_front", "hook_rear"}) {
                JsonObject part = parts.get(id);
                check(part != null, id + " is a part of the vehicle");
                if (part == null) {
                    continue;
                }
                check("ywzj_vehicle:hook".equals(string(part, "type")),
                        id + " is a ywzj_vehicle:hook part (got " + string(part, "type") + ")");
                check(id.equals(string(part, "structure_bone")),
                        id + " points at its own structure bone");
                check(!part.has("is_seat") || !part.get("is_seat").getAsBoolean(),
                        id + " is not a seat");

                JsonObject bone = bones.get(id);
                check(bone != null, id + " has a structure bone");
                if (bone == null) {
                    continue;
                }
                // This is the whole point. A parent means buildOBBs never reaches the bone.
                JsonElement parent = bone.get("parent");
                boolean root = parent == null || parent.isJsonNull();
                check(root, id + " is a file-level root, not a child of another bone"
                        + (root ? "" : " (parent " + parent.getAsString()
                        + " would leave structureGroup null and make HookUnit unusable)"));
                if (!root) {
                    System.out.println("        that is why the hook has no tips and no ray hit");
                }

                float[] pivot = vec(bone.getAsJsonArray("pivot"), 3);
                check(pivot != null, id + " has a pivot");
                if (pivot == null) {
                    continue;
                }
                float ax = pivot[0] / UNIT;
                float ay = pivot[1] / UNIT;
                float az = pivot[2] / UNIT;
                check(ay > 0.2F && ay < 2.5F,
                        String.format(java.util.Locale.ROOT,
                                "%s anchor height %.3f is a low tow point", id, ay));
                if ("hook_front".equals(id)) {
                    check(az > 0.0F, id + " is at the nose (+z)");
                } else {
                    check(az < 0.0F, id + " is at the tail (-z)");
                }

                JsonArray cubes = bone.getAsJsonArray("cubes");
                check(cubes != null && cubes.size() > 0, id + " has hitbox cubes");
                if (cubes == null || cubes.size() == 0) {
                    continue;
                }
                float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
                float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
                for (JsonElement element : cubes) {
                    JsonObject cube = element.getAsJsonObject();
                    float[] origin = vec(cube.getAsJsonArray("origin"), 3);
                    float[] size = vec(cube.getAsJsonArray("size"), 3);
                    if (origin == null || size == null) {
                        check(false, id + " cube has origin and size");
                        continue;
                    }
                    for (int i = 0; i < 3; i++) {
                        lo[i] = Math.min(lo[i], origin[i] / UNIT);
                        hi[i] = Math.max(hi[i], (origin[i] + size[i]) / UNIT);
                    }
                }
                float dx = hi[0] - lo[0];
                float dy = hi[1] - lo[1];
                float dz = hi[2] - lo[2];
                check(dx >= MIN_AXIS && dy >= MIN_AXIS && dz >= MIN_AXIS,
                        String.format(java.util.Locale.ROOT,
                                "%s hitbox %.3f x %.3f x %.3f blocks is aimable (min %.2f)",
                                id, dx, dy, dz, MIN_AXIS));
                System.out.printf(java.util.Locale.ROOT,
                        "        anchor (%.3f, %.3f, %.3f), hitbox x %.3f..%.3f "
                                + "y %.3f..%.3f z %.3f..%.3f%n",
                        ax, ay, az, lo[0], hi[0], lo[1], hi[1], lo[2], hi[2]);
            }

            for (String locale : new String[]{"zh_cn", "en_us"}) {
                JsonObject lang = json(zip, "assets/armor_pioneer/lang/" + locale + ".json");
                for (String key : new String[]{"part.armor_pioneer.hook_front",
                        "part.armor_pioneer.hook_rear"}) {
                    check(lang != null && lang.has(key), locale + " has " + key);
                }
            }
        }
        finish();
    }

    private static Map<String, JsonObject> byId(JsonArray parts) {
        Map<String, JsonObject> map = new LinkedHashMap<>();
        for (JsonElement element : parts) {
            JsonObject part = element.getAsJsonObject();
            map.put(string(part, "id"), part);
        }
        return map;
    }

    private static Map<String, JsonObject> bones(JsonObject structure) {
        Map<String, JsonObject> map = new LinkedHashMap<>();
        JsonObject geometry = structure.getAsJsonArray("minecraft:geometry")
                .get(0).getAsJsonObject();
        for (JsonElement element : geometry.getAsJsonArray("bones")) {
            JsonObject bone = element.getAsJsonObject();
            map.put(string(bone, "name"), bone);
        }
        return map;
    }

    private static float[] vec(JsonArray array, int n) {
        if (array == null || array.size() < n) {
            return null;
        }
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = array.get(i).getAsFloat();
        }
        return out;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static JsonObject json(ZipFile zip, String name) {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            check(false, "pack is missing " + name);
            return null;
        }
        try (InputStream in = zip.getInputStream(entry);
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            check(false, "could not parse " + name + ": " + e);
            return null;
        }
    }

    private static void finish() {
        if (failures > 0) {
            System.out.println("RESULT: " + failures + " tow check(s) failed");
            System.exit(1);
        }
        System.out.println("RESULT: both tow hooks are file-level roots with aimable hitboxes");
    }

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
