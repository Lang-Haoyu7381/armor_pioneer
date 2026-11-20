import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ywzjvehicle.armor.ArmorConfig;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The Leopard 2A4's hull-side add-on armour, which is two part units now.
 *
 * <p>It used to be one three-hit pool on the hull's flank zone ({@code hull_side_addon}); v82 moved
 * it onto the pack's own boxes - one part per flank, the T-90M's {@code era_1} / {@code era_2}
 * shape - so a round lands on a plate, spends it, and the next round at that spot meets the hull
 * side behind it.
 *
 * <p>What is checked is the shape of the rule rather than that a number was typed. A box that is
 * read on the wrong part, one that is shared by both flanks, and one that keeps stopping rounds
 * after it is gone all print a plausible thickness and only show themselves against the same
 * flank twice. The pack half is checked too: a {@code part_armor} key with no part behind it is a
 * box nothing can ever hit, and a part whose structure box sits somewhere else is a box the
 * visible kit does not cover.
 *
 * <p>Every arithmetic row goes through {@code ArmorSystem.resolve} - the method the damage system
 * calls - rather than a copy of the sum.
 *
 * <p>The pack is read in either shape the base mod loads - a zip, or the folder the player edits -
 * and the installation is chosen by {@code MC_INSTANCE} (default {@code 1.20.1-Forge_47.4.2}), so
 * the 1.21.1 port can run the same source against its own instance.
 *
 * <p>Usage: LeopardAddonCheck [config-file] [pack]
 */
public class LeopardAddonCheck {

    private static final String VEHICLE = "armor_pioneer:leopard2a4";
    private static final String NAMESPACE = "armor_pioneer";
    private static final String LEFT = "hull_addition_armor";
    private static final String RIGHT = "hull_addition_armor2";
    private static final List<String> PARTS = List.of(LEFT, RIGHT);

    private static final float EPS = 1.0E-4F;

    private static int failures;

    public static void main(String[] args) throws Exception {
        Path config = args.length > 0 ? Path.of(args[0]) : defaultConfig();
        Pack pack = args.length > 1 ? new Pack(Path.of(args[1])) : findPack();
        System.out.println("pack: " + pack.path);

        System.out.println();
        System.out.println("=== the shipped boxes ===");
        ArmorConfig.Profile defaults = defaults();
        ArmorConfig.VehicleArmor leopard = defaults.vehicles.get(VEHICLE);
        check("the Leopard 2A4 profile exists", leopard != null);
        if (leopard == null) {
            finish();
            return;
        }
        check("the old three-hit flank layer is gone", leopard.hull_side_addon == null);
        for (String part : PARTS) {
            ArmorConfig.Plate p = leopard.part_armor.get(part);
            check(part + ": the kinetic box", p != null);
            if (p == null) {
                continue;
            }
            check(part + ": 100mm off a dart", near(p.thickness, 100.0F));
            check(part + ": spent by one hit", near(p.era_hp, 100.0F));
            check(part + ": the table's 5x multiplier (not in this sum)",
                    near(p.multiplier, 5.0F));
            check(part + ": not a screen", p.penetration_loss == 0.0F);
        }
        ArmorConfig.ArmorOverride heat = leopard.overrides.get("heat");
        check("the chemical table exists", heat != null);
        if (heat != null) {
            for (String part : PARTS) {
                ArmorConfig.Plate p = heat.part_armor.get(part);
                check(part + " (chemical): 300mm off a jet",
                        p != null && near(p.thickness, 300.0F) && near(p.era_hp, 300.0F));
                check(part + " (chemical): the table's 4x multiplier",
                        p != null && near(p.multiplier, 4.0F));
            }
        }
        ArmorConfig.ArmorOverride he = leopard.overrides.get("he");
        check("the HE table carries the same boxes", he != null
                && he.part_armor.get(LEFT) == (heat == null ? null : heat.part_armor.get(LEFT)));

        System.out.println();
        System.out.println("=== the pack carries the boxes ===");
        pack(pack);

        System.out.println();
        System.out.println("=== the arithmetic ===");
        arithmetic(leopard);

        System.out.println();
        System.out.println("=== v81 -> v82 on the installed config ===");
        if (Files.isRegularFile(config)) {
            migration(config);
        } else {
            System.out.println("  no config file at " + config + " - skipped");
        }

        finish();
    }

    // ------------------------------------------------------------- the pack

    private static void pack(Pack pack) throws Exception {
        JsonObject def = pack.json("data/" + NAMESPACE + "/vehicles/leopard2a4.json");
        check("the vehicle definition exists", def != null);
        if (def == null) {
            return;
        }
        for (String part : PARTS) {
            JsonObject unit = part(def, part);
            check(part + ": a part unit in the pack", unit != null);
            if (unit == null) {
                continue;
            }
            check(part + ": a generic part", "ywzj_vehicle:generic".equals(type(unit)));
            check(part + ": not a seat (the seat order is untouched)", !seat(unit));
            check(part + ": keyed by the box of its own name",
                    part.equals(string(unit, "structure_bone")));
        }
        List<String> seats = seats(def);
        check("the seat order is still turret, commander, passenger, loader",
                seats.equals(List.of("turret", "commander_machine_gun", "driver_seat_2",
                        "loader_seat")));

        JsonObject struct = pack.geometry(
                "data/" + NAMESPACE + "/models/bedrock/vehicle/leopard2a4.structure.json");
        check("the structure model exists", struct != null);
        if (struct != null) {
            for (String part : PARTS) {
                JsonObject bone = bone(struct, part);
                check(part + ": a structure bone", bone != null);
                if (bone == null) {
                    continue;
                }
                check(part + ": a file-level root (the OBB group starts there)", !bone.has("parent"));
                JsonArray cubes = bone.getAsJsonArray("cubes");
                check(part + ": one box", cubes != null && cubes.size() == 1);
                if (cubes == null || cubes.size() != 1) {
                    continue;
                }
                JsonObject cube = cubes.get(0).getAsJsonObject();
                double[] origin = numbers(cube.getAsJsonArray("origin"));
                double[] size = numbers(cube.getAsJsonArray("size"));
                // A root bone with no rotation: x is mirrored and folded with the size, y and z
                // are as written, and the chain is the identity.
                double x0 = -(origin[0] + size[0]) / 16.0;
                double x1 = -origin[0] / 16.0;
                double y0 = origin[1] / 16.0;
                double y1 = (origin[1] + size[1]) / 16.0;
                double z0 = origin[2] / 16.0;
                double z1 = (origin[2] + size[2]) / 16.0;
                System.out.printf("    %-24s x %+.3f..%+.3f  y %+.3f..%+.3f  z %+.3f..%+.3f%n",
                        part, x0, x1, y0, y1, z0, z1);
                boolean flank = Math.abs(x0) > 1.8 && Math.abs(x1) < 2.6
                        && y0 > 0.9 && y1 < 2.1 && z0 > 1.8 && z1 < 5.1;
                check(part + ": sits over the visible kit on the flank", flank);
                check(part + (LEFT.equals(part) ? ": on the left flank" : ": on the right flank"),
                        LEFT.equals(part) ? x1 < 0.0 : x0 > 0.0);
            }
        }

        JsonObject display = pack.geometry(
                "assets/" + NAMESPACE + "/models/bedrock/entity/leopard2a4.json");
        check("the display model exists", display != null);
        if (display != null) {
            for (String part : PARTS) {
                JsonObject bone = bone(display, part);
                check(part + ": the plates are visible (a display bone of the same name)",
                        bone != null && bone.getAsJsonArray("cubes") != null
                                && bone.getAsJsonArray("cubes").size() > 0);
            }
        }
    }

    // ---------------------------------------------------------- the arithmetic

    private static void arithmetic(ArmorConfig.VehicleArmor leopard) throws Exception {
        Class<?> system = Class.forName("com.ywzjvehicle.armor.ArmorSystem");
        Class<?> hitClass = Class.forName("com.ywzjvehicle.armor.ArmorSystem$Hit");
        Class<?> resolutionClass = Class.forName("com.ywzjvehicle.armor.ArmorSystem$Resolution");
        Class<?> zoneClass = Class.forName("com.ywzjvehicle.armor.ArmorSystem$Zone");
        Method resolve = system.getDeclaredMethod("resolve", ArmorConfig.VehicleArmor.class,
                String.class, hitClass, float.class, Set.class);
        resolve.setAccessible(true);
        Constructor<?> ctor = hitClass.getDeclaredConstructor(boolean.class, int.class,
                double[].class, double[].class, boolean.class, double.class, String.class,
                boolean.class);
        ctor.setAccessible(true);
        Method zoneM = resolutionClass.getDeclaredMethod("zone");
        Method panelName = resolutionClass.getDeclaredMethod("panelName");
        Method panelUsed = resolutionClass.getDeclaredMethod("panelUsed");
        Method panelLoss = resolutionClass.getDeclaredMethod("panelLoss");
        for (Method m : new Method[]{zoneM, panelName, panelUsed, panelLoss}) {
            m.setAccessible(true);
        }
        Field thickness = zoneClass.getDeclaredField("thickness");
        Field eraHp = zoneClass.getDeclaredField("eraHp");
        thickness.setAccessible(true);
        eraHp.setAccessible(true);

        Object left = ctor.newInstance(false, 1, null, null, false, 0.0, LEFT, false);
        Object right = ctor.newInstance(false, -1, null, null, false, 0.0, RIGHT, false);
        Object flank = ctor.newInstance(false, 1, null, null, false, 0.0, null, false);

        Set<String> spent = new HashSet<>();

        Object held = resolve.invoke(null, leopard, null, left, 100.0F, spent);
        check("a 100mm dart is stopped by the box itself",
                near((float) panelLoss.invoke(held), 100.0F)
                        && LEFT.equals(panelName.invoke(held))
                        && LEFT.equals(panelUsed.invoke(held))
                        && near((float) thickness.get(zoneM.invoke(held)), 100.0F)
                        && near((float) eraHp.get(zoneM.invoke(held)), 100.0F));
        spent.add((String) panelUsed.invoke(held));

        Object again = resolve.invoke(null, leopard, null, left, 100.0F, spent);
        check("the next hit at that spot meets the bare 60mm flank",
                near((float) panelLoss.invoke(again), 0.0F) && panelUsed.invoke(again) == null
                        && near((float) thickness.get(zoneM.invoke(again)), 60.0F));

        Object through = resolve.invoke(null, leopard, null, right, 250.0F, spent);
        check("a 250mm dart spends the right box (the left one's hit does not)",
                RIGHT.equals(panelUsed.invoke(through))
                        && near((float) panelLoss.invoke(through), 100.0F)
                        && near((float) thickness.get(zoneM.invoke(through)), 60.0F));

        Object hullSide = resolve.invoke(null, leopard, null, flank, 250.0F,
                new HashSet<String>());
        check("a flank hit between the plates meets no layer at all",
                panelName.invoke(hullSide) == null
                        && near((float) panelLoss.invoke(hullSide), 0.0F)
                        && near((float) thickness.get(zoneM.invoke(hullSide)), 60.0F));

        Object heatHeld = resolve.invoke(null, leopard, "heat", left, 300.0F,
                new HashSet<String>());
        check("a 300mm jet is stopped by the chemical box",
                near((float) panelLoss.invoke(heatHeld), 300.0F)
                        && LEFT.equals(panelUsed.invoke(heatHeld)));
        Object heatThrough = resolve.invoke(null, leopard, "heat", left, 400.0F,
                new HashSet<String>());
        check("a 400mm jet loses 300 and meets the 150mm chemical flank",
                near((float) panelLoss.invoke(heatThrough), 300.0F)
                        && near((float) thickness.get(zoneM.invoke(heatThrough)), 150.0F));
    }

    // ------------------------------------------------------------- migration

    private static void migration(Path config) throws Exception {
        Method upgrade = ArmorConfig.class.getDeclaredMethod("upgrade", ArmorConfig.Profile.class);
        upgrade.setAccessible(true);

        ArmorConfig.Profile loaded = ArmorConfig.configReader().fromJson(
                Files.readString(config, StandardCharsets.UTF_8), ArmorConfig.Profile.class);
        ArmorConfig.VehicleArmor mine = loaded.vehicles.get(VEHICLE);
        check("the installed file has the Leopard 2A4", mine != null);
        if (mine == null) {
            return;
        }
        System.out.printf("  before: version %d, layer %s%n", loaded.config_version,
                mine.hull_side_addon != null);

        // The v81 state: the three-hit layer, and neither box.
        mine.hull_side_addon = shippedLayer();
        for (String part : PARTS) {
            mine.part_armor.remove(part);
            for (String profile : new String[]{"heat", "he"}) {
                ArmorConfig.ArmorOverride o = mine.overrides.get(profile);
                if (o != null) {
                    o.part_armor.remove(part);
                }
            }
        }
        upgrade.invoke(null, loaded);

        check("the shipped three-hit layer is removed", mine.hull_side_addon == null);
        for (String part : PARTS) {
            ArmorConfig.Plate p = mine.part_armor.get(part);
            check(part + ": the file gained the box (100mm, one hit)",
                    p != null && near(p.thickness, 100.0F) && near(p.era_hp, 100.0F));
        }
        for (String profile : new String[]{"heat", "he"}) {
            ArmorConfig.ArmorOverride o = mine.overrides.get(profile);
            check("the " + profile + " table gained the boxes too",
                    o != null && o.part_armor.get(LEFT) != null
                            && near(o.part_armor.get(LEFT).thickness, 300.0F)
                            && o.part_armor.get(RIGHT) != null);
        }

        // A hand-written layer is kept: its figures are what the player said the kit is worth.
        ArmorConfig.Profile hand = ArmorConfig.configReader().fromJson(
                Files.readString(config, StandardCharsets.UTF_8), ArmorConfig.Profile.class);
        ArmorConfig.VehicleArmor hv = hand.vehicles.get(VEHICLE);
        ArmorConfig.Plate typed = new ArmorConfig.Plate(123.0F, 2.0F);
        typed.addon_hp = 999.0F;
        typed.addon_fractions = new float[]{1.0F};
        hv.hull_side_addon = typed;
        upgrade.invoke(null, hand);
        check("a hand-written layer is left exactly as typed",
                hv.hull_side_addon == typed && hv.hull_side_addon.addon_hp == 999.0F
                        && hv.hull_side_addon.addon_fractions.length == 1);

        upgrade.invoke(null, hand);
        check("a second pass keeps it", hv.hull_side_addon == typed);
    }

    private static ArmorConfig.Plate shippedLayer() {
        ArmorConfig.Plate plate = new ArmorConfig.Plate(90.0F, 5.0F);
        plate.addon_hp = 150.0F;
        plate.addon_fractions = new float[]{0.6F, 0.4F, 0.2F};
        return plate;
    }

    // ---------------------------------------------------------------- json

    private static JsonObject part(JsonObject definition, String id) {
        for (JsonElement element : parts(definition)) {
            JsonObject part = element.getAsJsonObject();
            if (id.equals(part.get("id").getAsString())) {
                return part;
            }
        }
        return null;
    }

    private static JsonArray parts(JsonObject definition) {
        return definition.has("parts") && definition.get("parts").isJsonArray()
                ? definition.getAsJsonArray("parts") : new JsonArray();
    }

    private static String type(JsonObject part) {
        return part.has("type") ? part.get("type").getAsString() : "";
    }

    private static String string(JsonObject part, String key) {
        return part.has(key) ? part.get(key).getAsString() : null;
    }

    /** {@code is_seat} defaults to true, so only an explicit false takes a part out of the chair. */
    private static boolean seat(JsonObject part) {
        return !part.has("is_seat") || part.get("is_seat").getAsBoolean();
    }

    private static List<String> seats(JsonObject definition) {
        List<String> seats = new ArrayList<>();
        for (JsonElement element : parts(definition)) {
            JsonObject part = element.getAsJsonObject();
            if (seat(part)) {
                seats.add(part.get("id").getAsString());
            }
        }
        return seats;
    }

    private static JsonObject bone(JsonObject geometry, String name) {
        JsonObject root = geometry.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
        JsonArray bones = root.getAsJsonArray("bones");
        if (bones == null) {
            return null;
        }
        for (JsonElement element : bones) {
            JsonObject bone = element.getAsJsonObject();
            if (name.equals(bone.get("name").getAsString())) {
                return bone;
            }
        }
        return null;
    }

    private static double[] numbers(JsonArray array) {
        double[] out = new double[array.size()];
        for (int i = 0; i < array.size(); i++) {
            out[i] = array.get(i).getAsDouble();
        }
        return out;
    }

    // ---------------------------------------------------------------- pack

    /** One installed pack, in either shape the base mod loads. */
    private static final class Pack {
        final Path path;

        Pack(Path path) {
            this.path = path;
        }

        String read(String rel) throws IOException {
            if (Files.isDirectory(path)) {
                Path file = path.resolve(rel);
                return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8)
                        : null;
            }
            try (ZipFile zip = new ZipFile(path.toFile())) {
                ZipEntry entry = zip.getEntry(rel);
                if (entry == null) {
                    return null;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }

        JsonObject json(String rel) throws IOException {
            String text = read(rel);
            return text == null ? null : JsonParser.parseString(text).getAsJsonObject();
        }

        JsonObject geometry(String rel) throws IOException {
            JsonObject doc = json(rel);
            return doc == null ? null : doc;
        }
    }

    /**
     * The installed Armor Pioneer pack, zip or folder.
     *
     * <p>The namespace comes from the pack's own metadata rather than its file name, because a
     * folder pack is free to be called anything.
     */
    private static Pack findPack() throws IOException {
        Path dir = Path.of(mcRoot() + "\\versions\\" + instance() + "\\limitless_vehicle");
        List<Path> candidates = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> stream = Files.list(dir)) {
                for (Path path : (Iterable<Path>) stream.sorted()::iterator) {
                    String name = path.getFileName().toString();
                    if (Files.isDirectory(path)
                            && Files.isRegularFile(path.resolve("vehicle_pack.meta.json"))) {
                        candidates.add(path);
                    } else if (name.endsWith(".zip")) {
                        candidates.add(path);
                    }
                }
            }
        }
        for (Path candidate : candidates) {
            Pack pack = new Pack(candidate);
            String meta = pack.read("vehicle_pack.meta.json");
            if (meta == null) {
                continue;
            }
            JsonObject object = JsonParser.parseString(meta).getAsJsonObject();
            if (object.has("namespace") && NAMESPACE.equals(object.get("namespace").getAsString())) {
                return pack;
            }
        }
        throw new IllegalStateException("no installed pack with namespace " + NAMESPACE
                + " under " + dir);
    }

    // ---------------------------------------------------------------- util

    private static ArmorConfig.Profile defaults() throws Exception {
        Method m = ArmorConfig.class.getDeclaredMethod("defaultProfile");
        m.setAccessible(true);
        return (ArmorConfig.Profile) m.invoke(null);
    }

    private static String mcRoot() {
        return System.getenv().getOrDefault("MC_ROOT", "E:\\.minecraft");
    }

    private static String instance() {
        return System.getenv().getOrDefault("MC_INSTANCE", "1.20.1-Forge_47.4.2");
    }

    private static Path defaultConfig() {
        return Path.of(mcRoot() + "\\versions\\" + instance() + "\\config\\ywzj_vehicle_armor.json");
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) <= EPS;
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            System.out.println("  ok    " + what);
        } else {
            failures++;
            System.out.println("  FAIL  " + what);
        }
    }

    private static void finish() {
        System.out.println();
        if (failures == 0) {
            System.out.println("LEOPARD ADD-ON CHECK: PASS");
        } else {
            System.out.println("LEOPARD ADD-ON CHECK: " + failures + " failure(s)");
            System.exit(1);
        }
    }
}
