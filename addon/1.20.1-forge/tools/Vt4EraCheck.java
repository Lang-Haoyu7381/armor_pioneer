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
 * The VT-4's four reactive panels: the pack parts, the config boxes, the arithmetic and the
 * v74 -> v75 migration.
 *
 * <p>Four things can go wrong with a panel and only the first is a compile error:
 *
 * <ul>
 *   <li>the pack has no part or no structure box, so a `part_armor` key names a part that can
 *       never be hit - the config still prints a plausible number;</li>
 *   <li>the box sits inside the coarse hull/turret box, so the base mod's closest-OBB raycast
 *       never sees it;</li>
 *   <li>the chemical table was not lowered, so the panel stacks on top of an ERA-inclusive
 *       figure and the tank is stronger than the numbers say;</li>
 *   <li>the old file never hears about the boxes, which is invisible because every existing
 *       number is still there.</li>
 * </ul>
 *
 * <p>Run against the installed pack and config, and against the mod's own
 * {@code ArmorSystem.resolve} - the same method the damage system calls - rather than a copy of
 * the arithmetic.
 *
 * <p>Usage: Vt4EraCheck [pack-root] [config-file]
 */
public class Vt4EraCheck {

    private static final String NAMESPACE = "armor_pioneer";
    private static final String VEHICLE = "armor_pioneer:vt4";
    private static final String[] PARTS = {"hull_era", "hull_era2", "turret_era", "turret_era2"};
    private static final String DEFAULT_CONFIG =
            "/versions/1.20.1-Forge_47.4.2/config/ywzj_vehicle_armor.json";

    private static int failures;

    public static void main(String[] args) throws Exception {
        Path pack = PackLocator.of(args, NAMESPACE);
        Path config = args.length > 1 ? Path.of(args[1]) : defaultConfig();
        System.out.println("pack  : " + pack);
        System.out.println("config: " + config);

        System.out.println();
        System.out.println("=== the pack carries the four parts and their hitboxes ===");
        JsonObject structure = entry(pack, "data/" + NAMESPACE + "/models/bedrock/vehicle/vt4.structure.json");
        JsonObject vehicle = entry(pack, "data/" + NAMESPACE + "/vehicles/vt4.json");
        JsonObject display = entry(pack, "assets/" + NAMESPACE + "/models/bedrock/entity/vt4.json");
        packParts(pack, structure, vehicle, display);

        System.out.println();
        System.out.println("=== the shipped config splits the chemical table ===");
        ArmorConfig.Profile defaults = defaults();
        ArmorConfig.VehicleArmor vt4 = defaults.vehicles.get(VEHICLE);
        check("the VT-4 profile exists", vt4 != null);
        if (vt4 == null) {
            finish();
            return;
        }
        shippedConfig(vt4);

        System.out.println();
        System.out.println("=== the bands survive a round trip ===");
        roundTrip();

        System.out.println();
        System.out.println("=== the panel arithmetic keeps the old totals ===");
        arithmetic(vt4);

        System.out.println();
        System.out.println("=== v74 -> v75 on the installed config ===");
        if (Files.isRegularFile(config)) {
            migration(config, defaults);
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
     * be the last N parts: the snorkel is appended after them, and "among the last N" was a proxy
     * for this rule rather than the rule itself.
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

    private static void packParts(Path pack, JsonObject structure, JsonObject vehicle,
                                  JsonObject display) throws Exception {
        JsonArray bones = structure.getAsJsonArray("minecraft:geometry").get(0)
                .getAsJsonObject().getAsJsonArray("bones");
        Map<String, JsonObject> byName = new LinkedHashMap<>();
        for (JsonElement element : bones) {
            JsonObject bone = element.getAsJsonObject();
            byName.put(bone.get("name").getAsString(), bone);
        }
        JsonObject bodyBone = byName.get("vehicle_body");
        JsonObject turretBone = byName.get("turret");
        check("the structure model still has vehicle_body and turret",
                bodyBone != null && turretBone != null);

        for (String part : PARTS) {
            JsonObject bone = byName.get(part);
            check("the structure model has " + part, bone != null);
            if (bone == null) {
                continue;
            }
            boolean childOfTurret = part.startsWith("turret_");
            check(part + " is " + (childOfTurret ? "a child of turret" : "a hull-level part"),
                    childOfTurret == "turret".equals(string(bone, "parent")));
            Box all = box(bone);
            check(part + " has a non-empty box", all != null);
            if (all == null || bodyBone == null || turretBone == null) {
                continue;
            }
            // The hull and turret hitboxes are split around the panels, so a panel is no
            // longer "outside the coarse box" -- it sits in a cavity between split cubes.
            // A panel whose centre falls inside a hull/turret cube can never be the closest
            // OBB, which is the real defect this check exists to catch.
            for (JsonElement element : bone.getAsJsonArray("cubes")) {
                Box cube = boxOf(element.getAsJsonObject());
                String hit = coveredBy(cube, bodyBone, turretBone);
                check(part + " panel sits in a split hull/turret cavity", hit == null,
                        hit == null ? null : "panel centre inside " + hit);
            }
        }

        JsonArray parts = vehicle.getAsJsonArray("parts");
        List<String> ids = new ArrayList<>();
        for (JsonElement element : parts) {
            ids.add(string(element.getAsJsonObject(), "id"));
        }
        for (String part : PARTS) {
            JsonObject entry = part(vehicle, part);
            check("the vehicle definition has " + part, entry != null);
            if (entry == null) {
                continue;
            }
            check(part + " names structure bone " + part, part.equals(string(entry, "structure_bone")));
            check(part + " names its display bone " + part,
                    part.equals(string(entry, "render_bone")));
            check(part + " is generic", "ywzj_vehicle:generic".equals(string(entry, "type")));
            check(part + " is not a seat", entry.has("is_seat") && !entry.get("is_seat").getAsBoolean());
            check(part + " is NOT in the seat prefix", ids.indexOf(part) >= seatPrefix(parts),
                    "position " + ids.indexOf(part) + " of " + ids.size());
        }

        JsonArray displayBones = display.getAsJsonArray("minecraft:geometry").get(0)
                .getAsJsonObject().getAsJsonArray("bones");
        Set<String> displayNames = new TreeSet<>();
        for (JsonElement element : displayBones) {
            displayNames.add(string(element.getAsJsonObject(), "name"));
        }
        for (String part : PARTS) {
            check("the display model marks " + part, displayNames.contains(part));
        }

        for (String lang : new String[]{"zh_cn", "en_us"}) {
            JsonObject json = entry(pack, "assets/" + NAMESPACE + "/lang/" + lang + ".json");
            boolean ok = true;
            for (String part : new String[]{"hull_era", "hull_era2", "turret_era", "turret_era2"}) {
                ok &= json.has("part." + NAMESPACE + "." + part);
            }
            check(lang + " has the four VT-4 part names", ok);
        }
    }

    // ---------------------------------------------------------------- config

    private static void shippedConfig(ArmorConfig.VehicleArmor vt4) {
        ArmorConfig.ArmorOverride heat = vt4.overrides == null ? null : vt4.overrides.get("heat");
        ArmorConfig.ArmorOverride he = vt4.overrides == null ? null : vt4.overrides.get("he");
        check("the heat and he overrides exist", heat != null && he != null);
        if (heat == null || he == null) {
            return;
        }
        check("turret front is 850 behind the panels", heat.turret != null && heat.turret.front == 850.0F);
        check("turret side is 100 behind the panels", heat.turret != null && heat.turret.side == 100.0F);
        check("hull front is 850 behind the panels", heat.hull != null && heat.hull.front == 850.0F);
        check("the upper glacis is 850 behind the panels", heat.hull_front_split != null
                && heat.hull_front_split.upper != null
                && heat.hull_front_split.upper.thickness == 850.0F);
        check("the he table follows the heat table", he.turret != null && he.turret.front == 850.0F
                && he.turret.side == 100.0F && he.hull.front == 850.0F
                && he.hull_front_split != null && he.hull_front_split.upper != null
                && he.hull_front_split.upper.thickness == 850.0F);

        checkPanel(vt4.part_armor, "hull_era", 200.0F, 1.0F);
        checkPanel(vt4.part_armor, "hull_era2", 200.0F, 1.0F);
        checkPanel(vt4.part_armor, "turret_era", 200.0F, 1.0F);
        checkPanel(vt4.part_armor, "turret_era2", 200.0F, 1.0F);
        check("the panels are not duplicated in the heat override",
                heat.part_armor == null || !heat.part_armor.containsKey("hull_era"));
        check("the panels are not duplicated in the he override",
                he.part_armor == null || !he.part_armor.containsKey("hull_era"));
        check("the kinetic table is untouched", vt4.turret != null && vt4.turret.front == 600.0F
                && vt4.turret.side == 180.0F && vt4.hull != null && vt4.hull.front == 600.0F);
        check("the turret panel ids are in turret_part_ids",
                contains(vt4.turret_part_ids, "turret_era") && contains(vt4.turret_part_ids, "turret_era2"));

        // v77: the user drew four bands on the front of the display model. The glacis' top strip
        // is the near-horizontal ricochet face, the rest of the glacis keeps 600, the belt's
        // extension below the nose is 480, and the rest of the lower plate keeps 180. The two
        // edges are normalised hull-box heights, so a sign slip would put the thick strip at the
        // bottom of the tank - and the plate would still print a plausible number.
        //
        // v94: the top strip is stated as the steel it is - 125mm at the 81 degrees measured off
        // the display model's `hull_era2` tile rows - rather than as the flat 800 equivalent the
        // vehicle-wide direction factor used to scale against the box's vertical face.
        ArmorConfig.FrontSplit split = vt4.hull_front_split;
        check("the split itself is still -0.2", split != null && split.split == -0.2F);
        check("the glacis' top strip is 125mm at 81 deg, edge +0.33",
                band(split, true, 125.0F, 0.33F) && split.upper_band_slope == 81.0F);
        check("the lower plate's belt strip is 480 at -0.93",
                band(split, false, 480.0F, -0.93F));
        check("the bands leave the two plates they cut alone",
                split != null && split.upper != null && split.upper.thickness == 600.0F
                        && split.lower != null && split.lower.thickness == 180.0F);
        // A chemical round's table is a complete statement about the face, and the user's figures
        // are kinetic. The arithmetic below proves the bands do not leak into it; what is checked
        // here is that the plates the chemical table does state are the ones v76 shipped.
        check("the chemical plates are still 850 / 300",
                heat.hull_front_split.upper.thickness == 850.0F
                        && heat.hull_front_split.lower.thickness == 300.0F
                        && he.hull_front_split.upper.thickness == 850.0F
                        && he.hull_front_split.lower.thickness == 300.0F);
    }

    /** @return whether the split's top or bottom strip is exactly {@code mm} with edge {@code at}. */
    private static boolean band(ArmorConfig.FrontSplit split, boolean upper, float mm, float at) {
        if (split == null) {
            return false;
        }
        ArmorConfig.Plate plate = upper ? split.upper_band : split.lower_band;
        float edge = upper ? split.upper_band_split : split.lower_band_split;
        return plate != null && plate.thickness == mm && plate.era_hp == 0.0F && edge == at;
    }

    private static boolean checkPanel(Map<String, ArmorConfig.Plate> map, String id, float hp, float mult) {
        ArmorConfig.Plate plate = map == null ? null : map.get(id);
        boolean ok = plate != null && plate.thickness == hp && plate.multiplier == mult
                && plate.era_hp == hp
                && plate.era_ce != null && plate.era_ce == 300.0F
                && plate.era_ke != null && plate.era_ke == 150.0F
                && plate.era_ce_falloff != null && plate.era_ce_falloff == 0.35F
                && plate.era_ke_falloff != null && plate.era_ke_falloff == 0.50F;
        check("  " + id + " is a " + (int) hp + "mm panel, 300 CE / 150 KE", ok);
        return ok;
    }

    /**
     * The writer has to emit the new plates and their edges, and an absent band has to stay
     * absent: a writer that always wrote a band would give every vehicle a strip at height zero
     * the moment it loaded a file this build had saved.
     */
    private static void roundTrip() throws Exception {
        ArmorConfig.Profile parsed = ArmorConfig.configReader().fromJson(
                ArmorConfig.configWriter().toJson(defaults()), ArmorConfig.Profile.class);
        ArmorConfig.VehicleArmor vt4 = parsed.vehicles.get(VEHICLE);
        check("the VT-4 came back with both bands",
                vt4 != null && band(vt4.hull_front_split, true, 125.0F, 0.33F)
                        && vt4.hull_front_split.upper_band_slope == 81.0F
                        && band(vt4.hull_front_split, false, 480.0F, -0.93F));
        ArmorConfig.VehicleArmor vt5 = parsed.vehicles.get("armor_pioneer:vt5");
        check("a vehicle with no bands came back without them",
                vt5 != null && vt5.hull_front_split.upper_band == null
                        && vt5.hull_front_split.lower_band == null);
    }

    // ------------------------------------------------------------- arithmetic

    private static void arithmetic(ArmorConfig.VehicleArmor vt4) throws Exception {
        Class<?> system = Class.forName("com.ywzjvehicle.armor.ArmorSystem");
        Class<?> hitClass = Class.forName("com.ywzjvehicle.armor.ArmorSystem$Hit");
        Class<?> resolutionClass = Class.forName("com.ywzjvehicle.armor.ArmorSystem$Resolution");
        Method resolve = system.getDeclaredMethod("resolve", ArmorConfig.VehicleArmor.class,
                String.class, hitClass, float.class, Set.class);
        resolve.setAccessible(true);
        Method resolveTandem = system.getDeclaredMethod("resolve", ArmorConfig.VehicleArmor.class,
                String.class, hitClass, float.class, Set.class, boolean.class);
        resolveTandem.setAccessible(true);
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
        java.lang.reflect.Field zoneName = zoneClass.getDeclaredField("name");
        thickness.setAccessible(true);
        eraHp.setAccessible(true);
        zoneName.setAccessible(true);

        // Hull upper front: 200 FY-4E panel + 850 behind = the old 1050. A 1100mm jet leaves 50.
        Object hullHit = ctor.newInstance(false, 3, null, null, false, 0.0, "hull_era", false);
        Object r = resolve.invoke(null, vt4, "heat", hullHit, 1100.0F, new java.util.HashSet<String>());
        check("hull panel (HEAT): 1100 -> 300 spent -> 850 behind -> 50",
                (float) thickness.get(zoneMethod.invoke(r)) == 850.0F
                        && (float) lossMethod.invoke(r) == 300.0F
                        && "hull_era".equals(usedMethod.invoke(r)));
        Set<String> spent = new java.util.HashSet<>();
        spent.add("hull_era");
        Object spentHit = ctor.newInstance(false, 3, null, null, false, 0.0, "hull_era", true);
        Object rs = resolve.invoke(null, vt4, "heat", spentHit, 1100.0F, spent);
        check("spent hull panel (HEAT): 1100 -> 850 behind -> 250",
                (float) thickness.get(zoneMethod.invoke(rs)) == 850.0F
                        && (float) lossMethod.invoke(rs) == 0.0F && usedMethod.invoke(rs) == null);

        // The 200 is the anti-kinetic figure, so the base table must be the one a dart meets.
        Object kinetic = ctor.newInstance(false, 3, null, null, false, 0.0, "hull_era", false);
        Object rk = resolve.invoke(null, vt4, null, kinetic, 1100.0F, new java.util.HashSet<String>());
        check("hull panel (KE): 1100 -> 150 spent -> 600 behind -> 350",
                (float) thickness.get(zoneMethod.invoke(rk)) == 600.0F
                        && (float) lossMethod.invoke(rk) == 150.0F
                        && "hull_era".equals(usedMethod.invoke(rk)));
        Object weak = ctor.newInstance(false, 3, null, null, false, 0.0, "hull_era", false);
        Object rw = resolve.invoke(null, vt4, null, weak, 150.0F, new java.util.HashSet<String>());
        check("a 150mm dart is stopped by the FY-4E panel's kinetic rating",
                (float) thickness.get(zoneMethod.invoke(rw)) == 200.0F
                        && (float) eraHp.get(zoneMethod.invoke(rw)) == 200.0F
                        && (float) lossMethod.invoke(rw) == 150.0F);

        // v77: the four bands the user drew, through the same resolve() the game calls. The
        // heights are normalised hull-box positions; the two edges are the shipped +0.33 and
        // -0.93, and both are checked on either side so an inverted comparison cannot pass.
        // Heights sit a hundredth either side of each edge rather than exactly on it: the config
        // stores floats and the hit height is a double, so a literal `0.33` is a hair BELOW the
        // shipped `0.33F`. The game never sees a difference that small; the test would.
        Object[][] bands = {
                {0.80, 125.0F, "hull upper front (ricochet)"},
                {0.34, 125.0F, "hull upper front (ricochet)"},
                {0.32, 600.0F, "hull upper front"},
                {0.00, 600.0F, "hull upper front"},
                {-0.19, 600.0F, "hull upper front"},
                {-0.21, 480.0F, "hull lower front (belt)"},
                {-0.50, 480.0F, "hull lower front (belt)"},
                {-0.92, 480.0F, "hull lower front (belt)"},
                {-0.94, 180.0F, "hull lower front"},
                {-0.99, 180.0F, "hull lower front"},
        };
        int bandFails = 0;
        for (Object[] row : bands) {
            Object hit = ctor.newInstance(false, 3,
                    new double[]{0.0, (Double) row[0], 0.0}, null, false, 0.0, null, false);
            Object res = resolve.invoke(null, vt4, null, hit, 1200.0F,
                    new java.util.HashSet<String>());
            Object zone = zoneMethod.invoke(res);
            boolean ok = (float) thickness.get(zone) == (Float) row[1]
                    && row[2].equals(zoneName.get(zone));
            if (!ok) {
                System.out.println("  FAIL height " + row[0] + ": " + zoneName.get(zone) + " "
                        + thickness.get(zone) + "mm, expected " + row[2] + " " + row[1] + "mm");
                bandFails++;
            }
        }
        check("the four kinetic bands resolve at their heights (" + bands.length + " heights)",
                bandFails == 0);
        // The user's figures are kinetic. Every chemical face keeps the value v76 shipped, and
        // the band name is still the geometric one - the scanner is pointing at that strip.
        Object[][] chemical = {
                {0.80, 850.0F, "hull upper front (ricochet)"},
                {0.00, 850.0F, "hull upper front"},
                {-0.50, 300.0F, "hull lower front (belt)"},
                {-0.99, 300.0F, "hull lower front"},
        };
        int chemicalFails = 0;
        for (Object[] row : chemical) {
            Object hit = ctor.newInstance(false, 3,
                    new double[]{0.0, (Double) row[0], 0.0}, null, false, 0.0, null, false);
            Object res = resolve.invoke(null, vt4, "heat", hit, 1200.0F,
                    new java.util.HashSet<String>());
            Object zone = zoneMethod.invoke(res);
            if ((float) thickness.get(zone) != (Float) row[1] || !row[2].equals(zoneName.get(zone))) {
                chemicalFails++;
            }
        }
        check("the chemical table still measures the same plate at every height",
                chemicalFails == 0);
        // A panel is a layer in front of the band, not a replacement for it: intact it takes its
        // 200 off and the steel behind is 125mm at 81 degrees, spent the band alone is 125mm.
        Object intact = ctor.newInstance(false, 3, new double[]{0.0, 0.80, 0.0}, null,
                false, 0.0, "hull_era", false);
        Object ri = resolve.invoke(null, vt4, null, intact, 1100.0F,
                new java.util.HashSet<String>());
        check("an intact panel over the ricochet strip: 150 spent, 125mm band behind",
                (float) thickness.get(zoneMethod.invoke(ri)) == 125.0F
                        && (float) lossMethod.invoke(ri) == 150.0F);
        Set<String> spentUpper = new java.util.HashSet<>();
        spentUpper.add("hull_era");
        Object spentStrip = ctor.newInstance(false, 3, new double[]{0.0, 0.80, 0.0}, null,
                false, 0.0, "hull_era", false);
        Object rss = resolve.invoke(null, vt4, null, spentStrip, 1100.0F, spentUpper);
        check("a spent panel on the ricochet strip leaves the 125mm band behind",
                (float) thickness.get(zoneMethod.invoke(rss)) == 125.0F
                        && (float) lossMethod.invoke(rss) == 0.0F);

        // Turret front: 200 panel + 850 behind = 1050. Side: 200 + 100 = 300.
        Object turretFront = ctor.newInstance(true, 3, null, null, false, 0.0, "turret_era", false);
        Object rf = resolve.invoke(null, vt4, "heat", turretFront, 1100.0F, new java.util.HashSet<String>());
        check("turret front (HEAT): 1100 -> 300 -> 850 -> 50",
                (float) thickness.get(zoneMethod.invoke(rf)) == 850.0F
                        && (float) lossMethod.invoke(rf) == 300.0F);
        Object turretSide = ctor.newInstance(true, 1, null, null, false, 0.0, "turret_era2", false);
        Object rsd = resolve.invoke(null, vt4, "heat", turretSide, 400.0F, new java.util.HashSet<String>());
        check("turret side (HEAT): 400 -> 300 -> 100 -> 100",
                (float) thickness.get(zoneMethod.invoke(rsd)) == 100.0F
                        && (float) lossMethod.invoke(rsd) == 300.0F);
        Object sideK = ctor.newInstance(true, 1, null, null, false, 0.0, "turret_era2", false);
        Object rsk = resolve.invoke(null, vt4, null, sideK, 400.0F, new java.util.HashSet<String>());
        check("turret side (KE): 400 -> 150 -> 180 -> 70",
                (float) thickness.get(zoneMethod.invoke(rsk)) == 180.0F
                        && (float) lossMethod.invoke(rsk) == 150.0F);

        // The split is only worth having if both halves move. At 60 degrees the falloffs take
        // 300 -> 222.9 and 150 -> 94.9; an implementation that ignored either the threat kind or
        // the angle would land on one of the other three numbers.
        Object angledHeat = ctor.newInstance(false, 3, null, null, false, 60.0, "hull_era", false);
        Object ra = resolve.invoke(null, vt4, "heat", angledHeat, 1100.0F,
                new java.util.HashSet<String>());
        check("a 60-degree HEAT hit: 300 -> 222.9mm off",
                Math.abs((float) lossMethod.invoke(ra) - 222.86F) <= 0.05F);
        Object angledDart = ctor.newInstance(false, 3, null, null, false, 60.0, "hull_era", false);
        Object rak = resolve.invoke(null, vt4, null, angledDart, 1100.0F,
                new java.util.HashSet<String>());
        check("a 60-degree dart: 150 -> 94.9mm off",
                Math.abs((float) lossMethod.invoke(rak) - 94.90F) <= 0.05F);

        // A tandem precursor spends the panel; the main jet pays only a token share and reaches
        // the armour behind. This is what the round's `tandem` flag has always claimed to mean.
        Object tandemHit = ctor.newInstance(false, 3, null, null, false, 0.0, "hull_era", false);
        Object rt = resolveTandem.invoke(null, vt4, "heat", tandemHit, 1100.0F,
                new java.util.HashSet<String>(), true);
        check("a tandem jet spends the panel and pays only 30mm",
                (float) lossMethod.invoke(rt) == 30.0F
                        && (float) thickness.get(zoneMethod.invoke(rt)) == 850.0F
                        && "hull_era".equals(usedMethod.invoke(rt)));

        ArmorConfig.Plate legacy = new ArmorConfig.Plate(200.0F, 1.0F).reactiveHp(200.0F);
        check("a legacy panel keeps 200 against both threats and no falloff",
                legacy.eraCe() == 200.0F && legacy.eraKe() == 200.0F
                        && legacy.eraCeFalloff() == 0.0F && legacy.eraKeFalloff() == 0.0F);
    }

    // -------------------------------------------------------------- migration

    private static void migration(Path config, ArmorConfig.Profile defaults) throws Exception {
        ArmorConfig.Profile loaded = ArmorConfig.configReader().fromJson(
                Files.readString(config, StandardCharsets.UTF_8), ArmorConfig.Profile.class);
        ArmorConfig.VehicleArmor mine = loaded.vehicles.get(VEHICLE);
        check("the installed file has the VT-4", mine != null);
        if (mine == null) {
            return;
        }
        ArmorConfig.ArmorOverride heat = mine.overrides == null ? null : mine.overrides.get("heat");
        check("the installed file has the VT-4 heat table", heat != null);
        if (heat == null) {
            return;
        }
        float wasFront = heat.turret == null ? -1.0F : heat.turret.front;
        float wasSide = heat.turret == null ? -1.0F : heat.turret.side;
        float wasHull = heat.hull == null ? -1.0F : heat.hull.front;
        float wasUpper = heat.hull_front_split == null || heat.hull_front_split.upper == null
                ? -1.0F : heat.hull_front_split.upper.thickness;
        boolean hadPanels = heat.part_armor != null && heat.part_armor.containsKey("hull_era");
        boolean hadIds = contains(mine.turret_part_ids, "turret_era");
        System.out.printf("  before: version %d, front %.0f side %.0f hull %.0f glacis %.0f, panels %s, ids %s%n",
                loaded.config_version, wasFront, wasSide, wasHull, wasUpper, hadPanels, hadIds);

        Method upgrade = ArmorConfig.class.getDeclaredMethod("upgrade", ArmorConfig.Profile.class);
        upgrade.setAccessible(true);
        upgrade.invoke(null, loaded);

        // v74 shipped 1050/300/1050/1050; v75 shipped 900/150/600/600. Both are ours.
        check("the shipped turret front moved to 850",
                (wasFront != 1050.0F && wasFront != 900.0F)
                        || (heat.turret != null && heat.turret.front == 850.0F));
        check("the shipped turret side moved to 100",
                (wasSide != 300.0F && wasSide != 150.0F)
                        || (heat.turret != null && heat.turret.side == 100.0F));
        check("the shipped hull front moved to 850",
                (wasHull != 1050.0F && wasHull != 600.0F)
                        || (heat.hull != null && heat.hull.front == 850.0F));
        check("the shipped glacis moved to 850",
                (wasUpper != 1050.0F && wasUpper != 600.0F)
                        || (heat.hull_front_split != null
                        && heat.hull_front_split.upper != null
                        && heat.hull_front_split.upper.thickness == 850.0F));
        for (String id : PARTS) {
            check("the installed file gained the 200mm " + id + " base panel",
                    checkPanel(mine.part_armor, id, 200.0F, 1.0F));
        }
        check("the interim override panels are gone from heat",
                heat.part_armor == null || !heat.part_armor.containsKey("hull_era"));
        check("the installed file's turret list gained both ids",
                contains(mine.turret_part_ids, "turret_era")
                        && contains(mine.turret_part_ids, "turret_era2"));

        // A v75 file carries the interim 450/150 panels in both overrides. The upgrade has to
        // remove them, or they would keep overriding the 200 for their rounds.
        ArmorConfig.Profile v75 = ArmorConfig.configReader().fromJson(
                Files.readString(config, StandardCharsets.UTF_8), ArmorConfig.Profile.class);
        simulateV75(v75, 900.0F, 150.0F, 600.0F, 600.0F);
        upgrade.invoke(null, v75);
        ArmorConfig.VehicleArmor v75v = v75.vehicles.get(VEHICLE);
        check("a v75 file's 900/150/600 moves to 850/100/850",
                v75v.overrides.get("heat").turret.front == 850.0F
                        && v75v.overrides.get("heat").turret.side == 100.0F
                        && v75v.overrides.get("heat").hull.front == 850.0F
                        && v75v.overrides.get("heat").hull_front_split.upper.thickness == 850.0F);
        check("a v75 file gains the 200mm base panels",
                checkPanel(v75v.part_armor, "hull_era", 200.0F, 1.0F)
                        && checkPanel(v75v.part_armor, "turret_era", 200.0F, 1.0F));
        check("a v75 file's interim override panels are removed",
                !v75v.overrides.get("heat").part_armor.containsKey("hull_era")
                        && !v75v.overrides.get("heat").part_armor.containsKey("turret_era")
                        && !v75v.overrides.get("he").part_armor.containsKey("hull_era"));

        // A hand-edited table keeps every figure; the boxes and ids still arrive. A hand-valued
        // override panel is a statement about that round and must survive too.
        ArmorConfig.Profile hand = ArmorConfig.configReader().fromJson(
                Files.readString(config, StandardCharsets.UTF_8), ArmorConfig.Profile.class);
        ArmorConfig.VehicleArmor hv = hand.vehicles.get(VEHICLE);
        ArmorConfig.ArmorOverride hh = hv.overrides.get("heat");
        hh.turret.front = 1049.0F;
        hh.turret.side = 299.0F;
        hh.hull.front = 1048.0F;
        hh.hull_front_split.upper.thickness = 1047.0F;
        hv.hull_front_split.upper_band = new ArmorConfig.Plate(1234.0F, 10.0F);
        hh.part_armor.put("hull_era", new ArmorConfig.Plate(999.0F, 1.0F).reactiveHp(999.0F));
        hv.turret_part_ids = new String[]{"turret", "commander_machine_gun"};
        upgrade.invoke(null, hand);
        check("a hand-edited table is left exactly as typed",
                hh.turret.front == 1049.0F && hh.turret.side == 299.0F
                        && hh.hull.front == 1048.0F && hh.hull_front_split.upper.thickness == 1047.0F);
        // v76 -> v77: the bands are new optional plates on the split, so they are copied in where
        // the file has none - and a band a player has valued themselves is kept whole. v94 makes
        // the top band steel at 81 degrees, so a file that gains it now gains that pair.
        check("the installed file gained the two glacis bands",
                band(mine.hull_front_split, true, 125.0F, 0.33F)
                        && mine.hull_front_split.upper_band_slope == 81.0F
                        && band(mine.hull_front_split, false, 480.0F, -0.93F));
        check("a hand-set band is left exactly as typed",
                hv.hull_front_split.upper_band != null
                        && hv.hull_front_split.upper_band.thickness == 1234.0F
                        && hv.hull_front_split.upper_band_slope == 81.0F);
        check("a hand-valued override panel is kept",
                hh.part_armor.get("hull_era") != null
                        && hh.part_armor.get("hull_era").era_hp == 999.0F);
        check("a hand-edited file still gains the base boxes and ids",
                checkPanel(hv.part_armor, "hull_era", 200.0F, 1.0F)
                        && checkPanel(hv.part_armor, "turret_era", 200.0F, 1.0F)
                        && contains(hv.turret_part_ids, "turret_era")
                        && contains(hv.turret_part_ids, "turret_era2"));
    }

    /** Puts the v75 interim figures and panels on a parsed file, to exercise the v75 -> v76 step. */
    private static void simulateV75(ArmorConfig.Profile p, float front, float side,
                                    float hull, float upper) {
        ArmorConfig.VehicleArmor v = p.vehicles.get(VEHICLE);
        if (v.part_armor != null) {
            for (String id : PARTS) {
                v.part_armor.remove(id);
            }
        }
        for (String name : new String[]{"heat", "he"}) {
            ArmorConfig.ArmorOverride o = v.overrides.get(name);
            o.turret.front = front;
            o.turret.side = side;
            o.hull.front = hull;
            o.hull_front_split.upper.thickness = upper;
            o.part_armor.put("hull_era", new ArmorConfig.Plate(450.0F, 1.0F).reactiveHp(450.0F));
            o.part_armor.put("hull_era2", new ArmorConfig.Plate(450.0F, 1.0F).reactiveHp(450.0F));
            o.part_armor.put("turret_era", new ArmorConfig.Plate(150.0F, 4.0F).reactiveHp(150.0F));
            o.part_armor.put("turret_era2", new ArmorConfig.Plate(150.0F, 4.0F).reactiveHp(150.0F));
        }
        v.turret_part_ids = new String[]{"turret", "commander_machine_gun"};
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
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null;
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

    /** Name of the first hull/turret cube whose AABB contains the panel's centre. */
    private static String coveredBy(Box panel, JsonObject body, JsonObject turret) {
        double[] centre = new double[3];
        for (int i = 0; i < 3; i++) {
            centre[i] = (panel.lo[i] + panel.hi[i]) / 2.0;
        }
        for (String name : new String[]{"vehicle_body", "turret"}) {
            JsonObject bone = "vehicle_body".equals(name) ? body : turret;
            for (JsonElement element : bone.getAsJsonArray("cubes")) {
                Box cube = boxOf(element.getAsJsonObject());
                boolean inside = true;
                for (int i = 0; i < 3; i++) {
                    if (centre[i] < cube.lo[i] - 0.01 || centre[i] > cube.hi[i] + 0.01) {
                        inside = false;
                    }
                }
                if (inside) {
                    return name;
                }
            }
        }
        return null;
    }

    private static Box box(JsonObject bone) {
        if (bone == null || !bone.has("cubes")) {
            return null;
        }
        Box out = null;
        for (JsonElement element : bone.getAsJsonArray("cubes")) {
            Box one = boxOf(element.getAsJsonObject());
            if (out == null) {
                out = one;
            } else {
                for (int i = 0; i < 3; i++) {
                    out.lo[i] = Math.min(out.lo[i], one.lo[i]);
                    out.hi[i] = Math.max(out.hi[i], one.hi[i]);
                }
            }
        }
        return out;
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
        // The loader mirrors x: world = [-(origin.x + size.x), -origin.x].
        return new Box(new double[]{-(ox + sx), oy, oz}, new double[]{-ox, oy + sy, oz + sz});
    }

    private static void check(String what, boolean ok) {
        check(what, ok, null);
    }

    private static void check(String what, boolean ok, String detail) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what + (detail == null ? "" : "  (" + detail + ")"));
        if (!ok) {
            failures++;
        }
    }

    private static void finish() {
        System.out.println();
        if (failures > 0) {
            System.out.println("RESULT: " + failures + " VT-4 ERA check(s) failed");
            System.exit(1);
        }
        System.out.println("RESULT: all VT-4 ERA checks passed");
    }

    private static final class Box {
        final double[] lo;
        final double[] hi;

        Box(double[] lo, double[] hi) {
            this.lo = lo;
            this.hi = hi;
        }
    }
}
