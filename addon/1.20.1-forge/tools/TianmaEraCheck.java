import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ywzjvehicle.armor.ArmorConfig;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The Tianma-20's three reactive panels: the pack parts, the hitbox layout, the config
 * boxes, the arithmetic and the v79 -> v80 migration.
 *
 * <p>The four failure shapes a panel can take, only the first of which is a compile error:
 *
 * <ul>
 *   <li>the pack has no part or no structure box, so a {@code part_armor} key names a part
 *       that can never be hit - and the config still prints a plausible number;</li>
 *   <li>the turret's coarse box still covers the roof panel, so the base mod's closest-OBB
 *       raycast picks the turret and the panel is never the struck part;</li>
 *   <li>a hull panel sits deep inside the hull box, so the 0.1-block part probe never reaches
 *       it;</li>
 *   <li>the old file never hears about the boxes or the turret id, which is invisible because
 *       every existing number is still there.</li>
 * </ul>
 *
 * <p>Run against the installed pack and config, and against the mod's own
 * {@code ArmorSystem.resolve} - the same method the damage system calls - rather than a copy
 * of the arithmetic.
 *
 * <p>Usage: TianmaEraCheck [pack-root] [config-file]
 */
public class TianmaEraCheck {

    private static final String NAMESPACE = "armor_pioneer";
    private static final String VEHICLE = "armor_pioneer:tianma20";
    private static final String[] PARTS = {"hull_era_l", "hull_era_r", "turret_era_roof"};
    private static final String[] RENDER = {"hull_era", "hull_era2", "turret_era"};
    private static final String DEFAULT_CONFIG =
            "/versions/1.20.1-Forge_47.4.2/config/ywzj_vehicle_armor.json";

    private static int failures;

    public static void main(String[] args) throws Exception {
        Path pack = PackLocator.of(args, NAMESPACE);
        Path config = args.length > 1 ? Path.of(args[1]) : defaultConfig();
        System.out.println("pack  : " + pack);
        System.out.println("config: " + config);

        System.out.println();
        System.out.println("=== the pack carries the three parts and their hitboxes ===");
        JsonObject structure = entry(pack,
                "data/" + NAMESPACE + "/models/bedrock/vehicle/tianma20.structure.json");
        JsonObject vehicle = entry(pack, "data/" + NAMESPACE + "/vehicles/tianma20.json");
        JsonObject display = entry(pack,
                "assets/" + NAMESPACE + "/models/bedrock/entity/tianma20.json");
        packParts(structure, vehicle, display);
        hitboxes(structure);

        System.out.println();
        System.out.println("=== the shipped config carries the three 200mm panels ===");
        ArmorConfig.Profile defaults = defaults();
        ArmorConfig.VehicleArmor tianma = defaults.vehicles.get(VEHICLE);
        check("the Tianma-20 profile exists", tianma != null);
        if (tianma == null) {
            finish();
            return;
        }
        shippedConfig(tianma);

        System.out.println();
        System.out.println("=== the panel arithmetic ===");
        arithmetic(tianma);

        System.out.println();
        System.out.println("=== v79 -> v80 on the installed config ===");
        if (Files.isRegularFile(config)) {
            migration(config);
        } else {
            System.out.println("  no config file at " + config + " - skipped");
        }

        finish();
    }

    // ------------------------------------------------------------------ pack

    /**
     * @return how many leading parts are seats. {@code is_seat} absent means a seat - that is the
     *         base mod's own default, and the seat parts in these packs rely on it.
     *
     * <p>The panels have to sit outside that prefix or a seat changes index. They no longer have to
     * be the last N parts: the snorkel is appended after them, and "at the end" was a proxy for
     * this rule rather than the rule itself.
     */
    private static int seatPrefix(JsonArray parts) {
        int count = 0;
        for (JsonElement element : parts) {
            JsonObject p = element.getAsJsonObject();
            boolean isSeat = !p.has("is_seat") || p.get("is_seat").getAsBoolean();
            if (!isSeat) {
                break;
            }
            count++;
        }
        return count;
    }

    private static void packParts(JsonObject structure, JsonObject vehicle, JsonObject display) {
        JsonArray parts = vehicle.getAsJsonArray("parts");
        List<String> ids = new ArrayList<>();
        for (JsonElement element : parts) {
            ids.add(string(element.getAsJsonObject(), "id"));
        }
        for (int i = 0; i < PARTS.length; i++) {
            String part = PARTS[i];
            JsonObject entry = part(vehicle, part);
            check("the vehicle definition has " + part, entry != null);
            if (entry == null) {
                continue;
            }
            check(part + " names structure bone " + part,
                    part.equals(string(entry, "structure_bone")));
            check(part + " names its display bone " + RENDER[i],
                    RENDER[i].equals(string(entry, "render_bone")));
            check(part + " is generic", "ywzj_vehicle:generic".equals(string(entry, "type")));
            check(part + " is not a seat",
                    entry.has("is_seat") && !entry.get("is_seat").getAsBoolean());
            check(part + " is not in the seat prefix",
                    ids.indexOf(part) >= seatPrefix(parts),
                    "position " + ids.indexOf(part) + " of " + ids.size());
        }

        JsonArray displayBones = display.getAsJsonArray("minecraft:geometry").get(0)
                .getAsJsonObject().getAsJsonArray("bones");
        Set<String> displayNames = new TreeSet<>();
        for (JsonElement element : displayBones) {
            displayNames.add(string(element.getAsJsonObject(), "name"));
        }
        for (String render : RENDER) {
            check("the display model marks " + render, displayNames.contains(render));
        }
    }

    /**
     * The hitbox layout. The hull panels must be at least flush with the hull's own box, and the
     * turret must not cover the roof panel from above - either one leaves the panel unreachable
     * while the config still looks right.
     */
    private static void hitboxes(JsonObject structure) {
        JsonArray bones = structure.getAsJsonArray("minecraft:geometry").get(0)
                .getAsJsonObject().getAsJsonArray("bones");
        Map<String, JsonObject> byName = new LinkedHashMap<>();
        for (JsonElement element : bones) {
            JsonObject bone = element.getAsJsonObject();
            byName.put(bone.get("name").getAsString(), bone);
        }
        JsonObject body = byName.get("vehicle_body");
        JsonObject turret = byName.get("turret");
        check("the structure model still has vehicle_body and turret",
                body != null && turret != null);
        if (body == null || turret == null) {
            return;
        }
        Box bodyBox = box(body);
        List<Box> turretCubes = new ArrayList<>();
        for (JsonElement cube : turret.getAsJsonArray("cubes")) {
            turretCubes.add(boxOf(cube.getAsJsonObject()));
        }

        Box left = box(byName.get("hull_era_l"));
        Box right = box(byName.get("hull_era_r"));
        Box roof = box(byName.get("turret_era_roof"));
        check("hull_era_l has a box", left != null);
        check("hull_era_r has a box", right != null);
        check("turret_era_roof has a box", roof != null);
        if (left == null || right == null || roof == null) {
            return;
        }
        check("hull_era_l is a child of nothing", !byName.get("hull_era_l").has("parent"));
        check("hull_era_r is a child of nothing", !byName.get("hull_era_r").has("parent"));
        check("turret_era_roof is a child of turret",
                "turret".equals(string(byName.get("turret_era_roof"), "parent")));

        // Flush or proud on the outer flank, and not deeper than the 0.1-block probe can reach.
        check("hull_era_l is flush with the hull's left face",
                left.minX / 16.0 <= bodyBox.minX / 16.0 + 0.02,
                String.format("panel %.3f, hull %.3f", left.minX / 16.0, bodyBox.minX / 16.0));
        check("hull_era_r is flush with the hull's right face",
                right.maxX / 16.0 >= bodyBox.maxX / 16.0 - 0.02,
                String.format("panel %.3f, hull %.3f", right.maxX / 16.0, bodyBox.maxX / 16.0));
        check("the two hull panels are the two flanks",
                left.maxX < 0 && right.minX > 0 && left.minY == right.minY
                        && left.maxY == right.maxY,
                "left " + left + ", right " + right);

        // The roof panel needs a cavity: no turret cube may be above it over its footprint.
        // The margin keeps a cube that merely shares the cavity's boundary out of the test -
        // a wall that touches the panel footprint is beside it, not over it.
        final double eps = 0.05;
        boolean covered = false;
        List<Box> blockers = new ArrayList<>();
        for (Box cube : turretCubes) {
            boolean overlaps = cube.minX < roof.maxX - eps && roof.minX + eps < cube.maxX
                    && cube.minZ < roof.maxZ - eps && roof.minZ + eps < cube.maxZ;
            if (overlaps && cube.maxY > roof.maxY + 0.001) {
                covered = true;
                blockers.add(cube);
            }
        }
        check("the turret box is split open over the roof panel", !covered,
                covered ? blockers.toString() : null);
        check("the turret roof under the panel is still a turret cube",
                turretCubes.stream().anyMatch(c -> c.minX <= roof.minX + 0.001
                        && c.maxX >= roof.maxX - 0.001
                        && c.minZ <= roof.minZ + 0.001 && c.maxZ >= roof.maxZ - 0.001
                        && c.maxY <= roof.minY + 0.001),
                "no cube under the panel");
        Box turretAll = null;
        for (Box cube : turretCubes) {
            turretAll = turretAll == null ? cube : turretAll.union(cube);
        }
        check("the roof panel sits between the hull roof and the turret top",
                turretAll != null && roof.minY > bodyBox.maxY && roof.maxY < turretAll.maxY,
                "panel " + roof + ", hull " + bodyBox);
    }

    // ---------------------------------------------------------------- config

    private static void shippedConfig(ArmorConfig.VehicleArmor tianma) {
        check("hull_era_l is a 200mm panel", panel(tianma, "hull_era_l"));
        check("hull_era_r is a 200mm panel", panel(tianma, "hull_era_r"));
        check("turret_era_roof is a 200mm panel", panel(tianma, "turret_era_roof"));
        check("the roof panel is in turret_part_ids",
                contains(tianma.turret_part_ids, "turret_era_roof"));
        check("the existing kinetic flank and roof figures are untouched",
                tianma.hull != null && tianma.hull.side == 80.0F
                        && tianma.turret != null && tianma.turret.roof == 30.0F);

        ArmorConfig.ArmorOverride heat = tianma.overrides == null ? null
                : tianma.overrides.get("heat");
        check("the chemical override exists", heat != null);
        if (heat == null) {
            return;
        }
        check("the existing chemical flank and roof figures are untouched",
                heat.hull != null && heat.hull.side == 150.0F
                        && heat.turret != null && heat.turret.roof == 55.0F);
        check("the panels are not duplicated in the heat override",
                heat.part_armor == null || !heat.part_armor.containsKey("hull_era_l"));
        ArmorConfig.ArmorOverride he = tianma.overrides.get("he");
        check("the panels are not duplicated in the he override",
                he == null || he.part_armor == null || !he.part_armor.containsKey("hull_era_l"));
    }

    private static boolean panel(ArmorConfig.VehicleArmor vehicle, String id) {
        ArmorConfig.Plate plate = vehicle.part_armor == null ? null
                : vehicle.part_armor.get(id);
        return plate != null && plate.thickness == 200.0F && plate.multiplier == 1.0F
                && plate.era_hp == 200.0F;
    }

    // ------------------------------------------------------------- arithmetic

    private static void arithmetic(ArmorConfig.VehicleArmor tianma) throws Exception {
        Class<?> system = Class.forName("com.ywzjvehicle.armor.ArmorSystem");
        Class<?> hitClass = Class.forName("com.ywzjvehicle.armor.ArmorSystem$Hit");
        Class<?> resolutionClass = Class.forName("com.ywzjvehicle.armor.ArmorSystem$Resolution");
        Method resolve = system.getDeclaredMethod("resolve", ArmorConfig.VehicleArmor.class,
                String.class, hitClass, float.class, Set.class);
        resolve.setAccessible(true);
        var ctor = hitClass.getDeclaredConstructor(boolean.class, int.class, double[].class,
                double[].class, boolean.class, double.class, String.class, boolean.class);
        ctor.setAccessible(true);
        Method zoneMethod = resolutionClass.getDeclaredMethod("zone");
        Method usedMethod = resolutionClass.getDeclaredMethod("panelUsed");
        Method lossMethod = resolutionClass.getDeclaredMethod("panelLoss");
        for (Method m : new Method[]{zoneMethod, usedMethod, lossMethod}) {
            m.setAccessible(true);
        }
        Class<?> zoneClass = Class.forName("com.ywzjvehicle.armor.ArmorSystem$Zone");
        java.lang.reflect.Field thickness = zoneClass.getDeclaredField("thickness");
        java.lang.reflect.Field eraHp = zoneClass.getDeclaredField("eraHp");
        thickness.setAccessible(true);
        eraHp.setAccessible(true);

        // Hull flank: 200 panel + 80 kinetic / 150 chemical behind.
        Object flank = ctor.newInstance(false, 1, null, null, false, 0.0, "hull_era_l", false);
        Object rk = resolve.invoke(null, tianma, null, flank, 400.0F,
                new java.util.HashSet<String>());
        check("hull flank (KE): 400 -> 200 spent -> 80 behind -> 120",
                (float) thickness.get(zoneMethod.invoke(rk)) == 80.0F
                        && (float) lossMethod.invoke(rk) == 200.0F
                        && "hull_era_l".equals(usedMethod.invoke(rk)));
        Object flankHeat = ctor.newInstance(false, 1, null, null, false, 0.0, "hull_era_r", false);
        Object rc = resolve.invoke(null, tianma, "heat", flankHeat, 400.0F,
                new java.util.HashSet<String>());
        check("hull flank (HEAT): 400 -> 200 spent -> 150 behind -> 50",
                (float) thickness.get(zoneMethod.invoke(rc)) == 150.0F
                        && (float) lossMethod.invoke(rc) == 200.0F);

        // Turret roof: 200 panel + 25 kinetic / 45 chemical behind.
        Object roof = ctor.newInstance(true, 2, null, null, false, 0.0, "turret_era_roof", false);
        Object rr = resolve.invoke(null, tianma, null, roof, 400.0F,
                new java.util.HashSet<String>());
        check("turret roof (KE): 400 -> 200 spent -> 30 behind -> 170",
                (float) thickness.get(zoneMethod.invoke(rr)) == 30.0F
                        && (float) lossMethod.invoke(rr) == 200.0F
                        && "turret_era_roof".equals(usedMethod.invoke(rr)));
        Object roofHeat = ctor.newInstance(true, 2, null, null, false, 0.0,
                "turret_era_roof", false);
        Object rh = resolve.invoke(null, tianma, "heat", roofHeat, 400.0F,
                new java.util.HashSet<String>());
        check("turret roof (HEAT): 400 -> 200 spent -> 55 behind -> 145",
                (float) thickness.get(zoneMethod.invoke(rh)) == 55.0F
                        && (float) lossMethod.invoke(rh) == 200.0F);

        // A spent panel leaves the armour behind untouched.
        Set<String> spent = new java.util.HashSet<>();
        spent.add("hull_era_l");
        Object spentHit = ctor.newInstance(false, 1, null, null, false, 0.0, "hull_era_l", true);
        Object rs = resolve.invoke(null, tianma, null, spentHit, 400.0F, spent);
        check("a spent hull panel: 400 against the 80 behind",
                (float) thickness.get(zoneMethod.invoke(rs)) == 80.0F
                        && (float) lossMethod.invoke(rs) == 0.0F
                        && usedMethod.invoke(rs) == null);

        // A round smaller than the panel is stopped outright.
        Object weak = ctor.newInstance(false, 1, null, null, false, 0.0, "hull_era_l", false);
        Object rw = resolve.invoke(null, tianma, null, weak, 150.0F,
                new java.util.HashSet<String>());
        check("a 150mm dart is stopped by the 200mm panel",
                (float) thickness.get(zoneMethod.invoke(rw)) == 200.0F
                        && (float) eraHp.get(zoneMethod.invoke(rw)) == 200.0F
                        && (float) lossMethod.invoke(rw) == 150.0F);
    }

    // -------------------------------------------------------------- migration

    private static void migration(Path config) throws Exception {
        ArmorConfig.Profile loaded = ArmorConfig.configReader().fromJson(
                Files.readString(config, StandardCharsets.UTF_8), ArmorConfig.Profile.class);
        ArmorConfig.VehicleArmor mine = loaded.vehicles.get(VEHICLE);
        check("the installed file has the Tianma-20", mine != null);
        if (mine == null) {
            return;
        }
        boolean hadLeft = mine.part_armor != null && mine.part_armor.containsKey("hull_era_l");
        boolean hadRoof = contains(mine.turret_part_ids, "turret_era_roof");
        System.out.printf("  before: version %d, panels %s, roof id %s%n",
                loaded.config_version, hadLeft, hadRoof);

        Method upgrade = ArmorConfig.class.getDeclaredMethod("upgrade", ArmorConfig.Profile.class);
        upgrade.setAccessible(true);
        upgrade.invoke(null, loaded);

        check("the installed file gained hull_era_l", panel(mine, "hull_era_l"));
        check("the installed file gained hull_era_r", panel(mine, "hull_era_r"));
        check("the installed file gained turret_era_roof", panel(mine, "turret_era_roof"));
        check("the installed file's turret list gained the roof panel",
                contains(mine.turret_part_ids, "turret_era_roof"));

        // A hand-edited file keeps everything it says; the boxes and the id still arrive.
        ArmorConfig.Profile hand = ArmorConfig.configReader().fromJson(
                Files.readString(config, StandardCharsets.UTF_8), ArmorConfig.Profile.class);
        ArmorConfig.VehicleArmor hv = hand.vehicles.get(VEHICLE);
        hv.hull.side = 123.0F;
        hv.part_armor.put("hull_era_l", new ArmorConfig.Plate(999.0F, 1.0F).reactiveHp(999.0F));
        hv.turret_part_ids = new String[]{"turret", "commander_machine_gun"};
        upgrade.invoke(null, hand);
        check("a hand-edited flank figure is left exactly as typed", hv.hull.side == 123.0F);
        check("a hand-valued panel is kept",
                hv.part_armor.get("hull_era_l") != null
                        && hv.part_armor.get("hull_era_l").era_hp == 999.0F);
        check("a hand-written turret list gains the roof panel",
                contains(hv.turret_part_ids, "turret_era_roof"));

        // A file that already has the roof id must not grow a duplicate.
        int before = hv.turret_part_ids.length;
        upgrade.invoke(null, hand);
        check("the roof id is not appended twice", hv.turret_part_ids.length == before);
    }

    // ------------------------------------------------------------------ util

    private static ArmorConfig.Profile defaults() throws Exception {
        Method m = ArmorConfig.class.getDeclaredMethod("defaultProfile");
        m.setAccessible(true);
        return (ArmorConfig.Profile) m.invoke(null);
    }

    private static Path defaultConfig() {
        return Path.of(System.getenv().getOrDefault("MC_ROOT", "E:\\.minecraft") + DEFAULT_CONFIG);
    }

    private static JsonObject entry(Path pack, String name) throws Exception {
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) {
                throw new IllegalStateException("pack has no " + name);
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                        .getAsJsonObject();
            }
        }
    }

    private static JsonObject part(JsonObject definition, String id) {
        for (JsonElement element : definition.getAsJsonArray("parts")) {
            JsonObject part = element.getAsJsonObject();
            if (id.equals(string(part, "id"))) {
                return part;
            }
        }
        return null;
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString() : null;
    }

    private static boolean contains(String[] list, String value) {
        if (list == null) {
            return false;
        }
        for (String item : list) {
            if (value.equals(item)) {
                return true;
            }
        }
        return false;
    }

    /** The union of a bone's cubes, in structure units, x mirrored like the loader. */
    private static Box box(JsonObject bone) {
        if (bone == null) {
            return null;
        }
        Box all = null;
        for (JsonElement element : bone.getAsJsonArray("cubes")) {
            Box cube = boxOf(element.getAsJsonObject());
            all = all == null ? cube : all.union(cube);
        }
        return all;
    }

    private static Box boxOf(JsonObject cube) {
        JsonArray origin = cube.getAsJsonArray("origin");
        JsonArray size = cube.getAsJsonArray("size");
        double ox = origin.get(0).getAsDouble();
        double oy = origin.get(1).getAsDouble();
        double oz = origin.get(2).getAsDouble();
        double sx = size.get(0).getAsDouble();
        double sy = size.get(1).getAsDouble();
        double sz = size.get(2).getAsDouble();
        return new Box(-(ox + sx), -ox, oy, oy + sy, oz, oz + sz);
    }

    private static void check(String what, boolean ok) {
        check(what, ok, null);
    }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            System.out.println("  ok    " + what);
        } else {
            failures++;
            System.out.println("  FAIL  " + what + (detail == null ? "" : " (" + detail + ")"));
        }
    }

    private static void finish() {
        System.out.println();
        if (failures == 0) {
            System.out.println("TIANMA ERA CHECK: PASS");
        } else {
            System.out.println("TIANMA ERA CHECK: " + failures + " failure(s)");
            System.exit(1);
        }
    }

    private static final class Box {
        final double minX, maxX, minY, maxY, minZ, maxZ;

        Box(double minX, double maxX, double minY, double maxY, double minZ, double maxZ) {
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
            this.minZ = minZ;
            this.maxZ = maxZ;
        }

        Box union(Box other) {
            return new Box(Math.min(minX, other.minX), Math.max(maxX, other.maxX),
                    Math.min(minY, other.minY), Math.max(maxY, other.maxY),
                    Math.min(minZ, other.minZ), Math.max(maxZ, other.maxZ));
        }

        @Override
        public String toString() {
            return String.format("x %.3f..%.3f y %.3f..%.3f z %.3f..%.3f (blocks)",
                    minX / 16, maxX / 16, minY / 16, maxY / 16, minZ / 16, maxZ / 16);
        }
    }
}
