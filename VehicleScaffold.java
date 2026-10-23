import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ywzjvehicle.armor.ArmorConfig;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * Works out, from the vehicle pack alone, everything a new vehicle needs in the armour config.
 *
 * <p>Adapting a vehicle is otherwise a measuring job: four of the numbers in a {@code vehicles} entry
 * are geometry rather than armour - which part units count as the turret, where the road wheels stop,
 * where the track's inner edge is, and how far the gun is off the turret box's centre - and until now
 * they were read out of the structure file by eye and typed in. They are all derivable from the
 * pack's own two files, so this derives them.
 *
 * <p>Nothing here re-implements the armour system. It reads the pack, does the same block arithmetic
 * the mod does when it builds its OBBs, and prints a config block to paste. What it cannot decide -
 * how thick the armour is - it leaves at zero and says so, because a plausible-looking guess is
 * worse than an obvious blank.
 *
 * <p>Three modes:
 * <ul>
 *   <li>{@code check} (the default) - derives the geometry of every vehicle already in the shipped
 *       config and compares it against what that config says. This is the mode that makes the other
 *       two trustworthy: if the tool cannot reproduce numbers a human measured by hand for the T-64,
 *       the Abrams and the BTR-3, its output for a fourth vehicle is not worth pasting.</li>
 *   <li>{@code list} - every vehicle the pack ships, and which of them the addon already configures.</li>
 *   <li>{@code &lt;vehicle-id&gt;} - the scaffold itself.</li>
 * </ul>
 *
 * <p>Usage: VehicleScaffold [check|list|&lt;vehicle-id&gt;] [--pack &lt;path-to-the-pack.zip&gt;]
 */
public class VehicleScaffold {

    private static final String DEFAULT_PACK =
            "E:/.minecraft/versions/1.20.1-Forge_47.4.2/limitless_vehicle/vvp_0.9.9_0.6.0+(1)(1).zip";

    /** Where the base mod keeps its packs, under {@code MC_ROOT}. */
    private static final String PACK_DIR = "/versions/1.20.1-Forge_47.4.2/limitless_vehicle";

    /**
     * The pack's namespace, read from its own metadata rather than assumed.
     *
     * <p>There is more than one vehicle pack, and they do not share a namespace - the tank pack's
     * ids are {@code tank_assault:panther}, not {@code vvp_ywzj:...}. Everything below is built from
     * this, including the config key, so a second pack needs no second copy of this tool.
     */
    private static String namespace = "vvp_ywzj";

    private static String vehicleDir() {
        return "data/" + namespace + "/vehicles/";
    }

    /**
     * @return where a weapon's file lives, taking the namespace from the id itself.
     *
     * <p>Not from the pack in hand. A weapon id carries its own namespace, and by the time there are
     * two packs the config holds weapons belonging to both - so an id from the other one must be
     * looked for where it says it is and not found, which is the honest answer, rather than looked
     * for under this pack's namespace and not found for the wrong reason.
     */
    private static String weaponPath(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? "data/" + id + ".json"
                : "data/" + id.substring(0, colon) + "/weapons/" + id.substring(colon + 1) + ".json";
    }

    private static String structureDir() {
        return "data/" + namespace + "/models/bedrock/vehicle/";
    }

    /** Model units are 1/16 of a block, which is the whole of the unit conversion. */
    private static final double UNITS = 16.0;

    /**
     * How far a derived number may sit from the configured one and still count as agreeing.
     *
     * <p>Not zero, on purpose. The configured values were read off the same model but rounded and
     * picked with margin - the T-64's track edge is a threshold deliberately set below where the
     * track actually starts, so that the whole track is caught. The comparison is here to catch a
     * derivation that is wrong, not to demand the file match to the last decimal.
     */
    private static final double NORMALISED_TOLERANCE = 0.02;

    private static int failures;

    public static void main(String[] args) throws Exception {
        List<String> plain = new ArrayList<>();
        Path named = null;
        String forced = null;
        for (int i = 0; i < args.length; i++) {
            if ("--pack".equals(args[i]) && i + 1 < args.length) {
                named = Path.of(args[++i]);
            } else if ("--namespace".equals(args[i]) && i + 1 < args.length) {
                forced = args[++i];
            } else {
                plain.add(args[i]);
            }
        }
        // "form" is a modifier rather than a mode of its own, so that both orders people reach for
        // work: "<id> form" and "form <id>".
        boolean worksheet = plain.remove("form");
        if (worksheet && plain.isEmpty()) {
            throw new IllegalStateException(
                    "the form mode needs a vehicle id, e.g. VehicleScaffold t72_b3m form");
        }
        String mode = plain.isEmpty() ? "check" : plain.get(0);

        List<Path> packs = resolvePacks(named);

        // Check mode runs against every pack installed, because the config covers every pack
        // installed. Pointing it at one pack would check three vehicles and quietly ignore the rest,
        // and a green result that covers half the file is worse than no result.
        if ("check".equals(mode)) {
            for (Path one : packs) {
                try (ZipFile zip = new ZipFile(one.toFile())) {
                    namespace = forced != null ? forced : detectNamespace(zip);
                    System.out.println();
                    System.out.println("##### " + one.getFileName() + "  [" + namespace + "]");
                    check(zip, defaultProfile());
                }
            }
            if (failures > 0) {
                System.out.println();
                System.out.println("RESULT: " + failures + " check(s) failed - see above");
                System.exit(1);
            }
            return;
        }

        // The list is a question about the installation, not about one pack, so it answers for all
        // of them - "what can I adapt" is not a question with a per-pack answer.
        if ("list".equals(mode)) {
            ArmorConfig.Profile profile = defaultProfile();
            for (Path one : packs) {
                try (ZipFile zip = new ZipFile(one.toFile())) {
                    namespace = forced != null ? forced : detectNamespace(zip);
                    System.out.println();
                    System.out.println("##### " + one.getFileName());
                    list(zip, profile);
                }
            }
            return;
        }

        // The remaining modes are about one vehicle, so they need the pack that has it.
        Path pack = findPack(packs, mode);
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            namespace = forced != null ? forced : detectNamespace(zip);
            // Held back in form mode, where the output is a Markdown document people keep: a stray
            // line above the title ends up inside whatever it is pasted into. The worksheet names
            // the pack in its own header, so nothing is lost.
            if (!worksheet) {
                System.out.println("pack: " + pack);
                System.out.println("namespace: " + namespace);
            }
            if (worksheet) {
                form(zip, defaultProfile(), mode, pack);
            } else {
                scaffold(zip, defaultProfile(), mode);
            }
        }
    }

    /**
     * The packs to read: the one named with {@code --pack}, or every pack the install has.
     */
    private static List<Path> resolvePacks(Path named) {
        List<Path> packs = new ArrayList<>();
        if (named != null) {
            if (!named.toFile().isFile()) {
                throw new IllegalStateException("no pack at " + named);
            }
            packs.add(named);
            return packs;
        }
        Path dir = Path.of(System.getenv().getOrDefault("MC_ROOT", "E:\\.minecraft") + PACK_DIR);
        if (java.nio.file.Files.isDirectory(dir)) {
            try (var stream = java.nio.file.Files.list(dir)) {
                stream.filter(path -> path.getFileName().toString().endsWith(".zip"))
                        .filter(path -> !path.getFileName().toString().endsWith(".bak"))
                        .sorted()
                        .forEach(packs::add);
            } catch (Exception e) {
                throw new IllegalStateException("could not list the packs in " + dir, e);
            }
        }
        if (packs.isEmpty()) {
            Path fallback = Path.of(System.getenv().getOrDefault("VVP_PACK", DEFAULT_PACK));
            if (!fallback.toFile().isFile()) {
                throw new IllegalStateException("no pack at " + fallback + " - pass --pack");
            }
            packs.add(fallback);
        }
        return packs;
    }

    /**
     * @return the pack that carries the given vehicle, or the first pack when none is named.
     *
     * <p>Found by looking rather than by being told, because with two packs installed "which pack is
     * the Panther in" is a question the packs answer and a path constant does not.
     */
    private static Path findPack(List<Path> packs, String vehicleId) {
        if (vehicleId == null) {
            return packs.get(0);
        }
        for (Path pack : packs) {
            try (ZipFile zip = new ZipFile(pack.toFile())) {
                String candidate = detectNamespace(zip);
                if (zip.getEntry("data/" + candidate + "/vehicles/" + vehicleId + ".json") != null) {
                    return pack;
                }
            } catch (Exception e) {
                // A pack that cannot be read is not a reason to stop looking in the others.
            }
        }
        throw new IllegalStateException("no installed pack has a vehicle called " + vehicleId);
    }

    // ---------------------------------------------------------------- check

    /**
     * Derives each configured vehicle from the pack and compares with the shipped config.
     *
     * <p>This is the tool's own proof. The three shipped vehicles had their geometry measured by hand
     * and their numbers are known to work in play, so they are a reference the derivation can be held
     * against - and holding it against them is the only way to find out that, say, the barrel is not
     * where the tool thinks it is, without first adapting a vehicle and noticing the gun mount is in
     * the wrong place.
     */
    private static void check(ZipFile zip, ArmorConfig.Profile profile) throws Exception {
        System.out.println();
        System.out.println("=== derivation vs the shipped config ===");
        int checked = 0;
        for (Map.Entry<String, ArmorConfig.VehicleArmor> entry : profile.vehicles.entrySet()) {
            String full = entry.getKey();
            String id = full.substring(full.indexOf(':') + 1);
            // Only the vehicles of the pack in hand. The config covers several packs at once, and a
            // vehicle from another one is not a derivation that failed - it is simply not here to be
            // measured, and reporting it as a failure would drown the answer in noise.
            if (!full.startsWith(namespace + ":")) {
                continue;
            }
            checked++;
            ArmorConfig.VehicleArmor armor = entry.getValue();
            Measured measured = measure(zip, id);
            System.out.println();
            System.out.println(full);
            if (measured == null) {
                fail("  no vehicle or structure file in the pack");
                continue;
            }

            // The turret's part units: derived from the bone tree, so a part counts as the turret
            // when the model says it hangs off the turret - not when its name happens to say so.
            Set<String> want = new LinkedHashSet<>(List.of(armor.turret_part_ids));
            Set<String> got = new LinkedHashSet<>(measured.turretParts);
            System.out.println("  turret part ids  : " + got);
            boolean partsOk = got.equals(want);
            report(partsOk, "matches the config" + (partsOk ? "" : " - config has " + want));
            if (!partsOk) {
                // Not fatal, and reported rather than asserted: a vehicle whose model does not parent
                // its turret parts to the turret bone is exactly the case a human has to look at.
                System.out.println("      (check the bone parenting above before trusting either list)");
            }

            // Every `part_armor` box has to name a part the model actually has, because the lookup
            // is by the id of whatever part a round hit: a box keyed to a name the pack does not
            // use - a typo, or a part that was renamed - is never looked up by anything, and reads
            // as an armour value in every table while armour nothing. The set of keys is compared,
            // not counted, and every override's table is in it: the round-specific boxes are the
            // ones most easily left out of a check like this and the ones a jet meets.
            Set<String> boxes = new LinkedHashSet<>();
            if (armor.part_armor != null) {
                boxes.addAll(armor.part_armor.keySet());
            }
            if (armor.overrides != null) {
                for (ArmorConfig.ArmorOverride override : armor.overrides.values()) {
                    if (override != null && override.part_armor != null) {
                        boxes.addAll(override.part_armor.keySet());
                    }
                }
            }
            Set<String> missing = new LinkedHashSet<>(boxes);
            missing.removeAll(measured.parts);
            System.out.println("  armour boxes     : " + boxes);
            report(missing.isEmpty(), missing.isEmpty()
                    ? "every box is a part the model has"
                    : "boxes on parts this model does not have: " + missing);

            if (armor.road_wheels != null) {
                if (measured.wheelTop == null) {
                    fail("  road_wheels.top  : config has " + armor.road_wheels.top
                            + " but no wheel bones were found");
                } else {
                    double gap = Math.abs(measured.wheelTop - armor.road_wheels.top);
                    report(gap <= NORMALISED_TOLERANCE, String.format(Locale.ROOT,
                            "road_wheels.top  : derived %.3f, config %.3f", measured.wheelTop,
                            armor.road_wheels.top));
                }
                System.out.println("      wheel bones: " + measured.wheelBones);
            }

            if (armor.track_ends != null) {
                // A threshold, not a measurement: the derivation says where the track starts and the
                // config must sit at or below it, but how far below is a choice about how much of the
                // flank counts as running gear. Reported as both numbers rather than as agreement.
                System.out.printf(Locale.ROOT,
                        "  track_ends.inner : derived inner face %.3f, config %.3f %s%n",
                        measured.trackInner, armor.track_ends.inner_edge,
                        measured.trackInner >= armor.track_ends.inner_edge
                                ? "(config is at or below it: the whole track is caught)"
                                : "(CONFIG IS ABOVE IT: part of the track is missed)");
                report(measured.trackInner >= armor.track_ends.inner_edge, "track edge outside the track");
                System.out.println("      track bones: " + measured.trackBones);
            }

            if (armor.turret_weak_spot != null && armor.turret_weak_spot.radius > 0.0F) {
                if (measured.gunOffsetX == null) {
                    fail("  gun mount        : config models one but no gun was found on the turret");
                } else {
                    // Reported, not asserted: the config's offsets are this pair rounded, and the T-64
                    // ships (0, 0) for a barrel that measures (+0.14, -0.06) because at a 0.4-block
                    // radius the difference cannot move a hit in or out of the circle.
                    System.out.printf(Locale.ROOT,
                            "  gun mount offset : derived (%+.3f, %+.3f) from %s, config (%+.3f, %+.3f)%n",
                            measured.gunOffsetX, measured.gunOffsetY, measured.gunBone,
                            armor.turret_weak_spot.offset_x, armor.turret_weak_spot.offset_y);
                    boolean close = Math.abs(measured.gunOffsetX - armor.turret_weak_spot.offset_x) <= 0.2
                            && Math.abs(measured.gunOffsetY - armor.turret_weak_spot.offset_y) <= 0.2;
                    report(close, "within a fifth of a block");
                }
            } else {
                System.out.println("  gun mount        : config models none");
            }

            if (armor.hull_weak_spot != null && armor.hull_weak_spot.radius > 0.0F) {
                if (measured.portX == null) {
                    fail("  machine-gun port : config models one but the model has no bow MG");
                } else {
                    System.out.printf(Locale.ROOT,
                            "  machine-gun port : derived (%+.3f, %+.3f) from %s, config (%+.3f, %+.3f)%n",
                            measured.portX, measured.portY, measured.portBone,
                            armor.hull_weak_spot.offset_x, armor.hull_weak_spot.offset_y);
                    boolean close = Math.abs(measured.portX - armor.hull_weak_spot.offset_x) <= 0.2
                            && Math.abs(measured.portY - armor.hull_weak_spot.offset_y) <= 0.2;
                    report(close, "within a fifth of a block");
                }
            } else {
                System.out.println("  machine-gun port : config models none");
            }
            System.out.println("  hull box (blocks): " + measured.hull);
        }
        belts(zip, profile);
        categories(zip, profile);

        System.out.println();
        // The count is printed rather than implied: a namespace with nothing configured yet passes
        // this check without checking anything, and "0 of 0" is the difference between a green
        // result and a vacuous one.
        System.out.println("  " + checked + " vehicle(s) of namespace '" + namespace + "' checked");
        System.out.println(failures == 0
                ? "RESULT: the derivation reproduces every shipped vehicle's geometry"
                : "RESULT: " + failures + " derivation(s) disagree with the shipped config");
    }

    /**
     * The dual-feed cannons: two belts of two rounds, with each round a weapon in both.
     *
     * <p>Nothing in the addon configures this and nothing in the addon can catch it going wrong. A
     * belt is an {@code ids} group in the vehicle's own file, and its failure mode is the quiet one
     * this project keeps meeting: the pack was never repatched, the vehicle still carries the two
     * rounds as two ordinary weapons, every table in the config still reads correctly - and the key
     * that switches between them does nothing at all.
     *
     * <p>Which guns are dual-feed is derived rather than listed, so the next cannon adapted this way
     * is covered without being named here: a vehicle is one when a belt of its carries a round this
     * config rates as an armour-piercing autocannon shell. The darts are then checked from the other
     * side as well - every such round the config knows has to be in someone's belt - which is what
     * catches the pack that was left alone rather than patched.
     */
    private static void belts(ZipFile zip, ArmorConfig.Profile profile) {
        System.out.println();
        System.out.println("=== the dual-feed belts ===");
        Set<String> belted = new LinkedHashSet<>();
        int checked = 0;
        for (Map.Entry<String, ArmorConfig.VehicleArmor> entry : profile.vehicles.entrySet()) {
            String full = entry.getKey();
            if (!full.startsWith(namespace + ":")) {
                continue;
            }
            String id = full.substring(full.indexOf(':') + 1);
            JsonObject vehicle = read(zip, vehicleDir() + id + ".json");
            if (vehicle == null) {
                continue;
            }
            List<JsonObject> groups = groups(vehicle);
            List<String> darts = new ArrayList<>();
            for (JsonObject group : groups) {
                for (String member : members(group)) {
                    ArmorConfig.Ammo ammo = profile.ammo_penetration.get(member);
                    if (ammo != null && ammo.shelf() == com.ywzjvehicle.armor.AmmoCategory.AUTOCANNON_AP) {
                        darts.add(member);
                    }
                }
            }
            if (darts.isEmpty()) {
                continue;
            }
            checked++;
            belted.addAll(darts);
            System.out.println(full);

            // Two of them, and that is the definition of a dual feed: one belt is a gun with two
            // rounds in a fixed order, which is what the gunner switches between with the primary
            // key, and a third would be a weapon nobody asked for.
            report(groups.size() == 2, "two belts in the weapons list"
                    + (groups.size() == 2 ? "" : " - found " + groups.size()));

            // Each belt holds the dart and one other round, in opposite orders - so the two belts are
            // the same two rounds and differ only in which one a gunner arrives on. The other member
            // is asserted to be high explosive: a belt of two darts would pass a check that only
            // counted members, and would be a vehicle with no shell for anything unarmoured.
            List<String> first = groups.size() == 2 ? members(groups.get(0)) : List.of();
            List<String> second = groups.size() == 2 ? members(groups.get(1)) : List.of();
            String dart = darts.get(0);
            boolean paired = first.size() == 2 && second.size() == 2
                    && first.get(0).equals(dart) && first.get(1).equals(second.get(0))
                    && dart.equals(second.get(1))
                    && isAutocannonHe(profile, first.get(1));
            report(paired, paired
                    ? "belt 1 opens on " + dart + " and belt 2 on " + second.get(0)
                    : "the belts are not two orders of the same dart and shell - belt 1 " + first
                            + ", belt 2 " + second);

            // A save id each, and different ones. Two groups written as bare arrays are both saved
            // under the fixed name "multi_weapons", which puts whichever belt the gunner left into
            // whichever one he comes back to - the sort of wrongness that reads as nothing at all.
            Set<String> saves = new LinkedHashSet<>();
            for (JsonObject group : groups) {
                if (group.has("save_id") && !group.get("save_id").getAsString().isBlank()) {
                    saves.add(group.get("save_id").getAsString());
                }
            }
            report(saves.size() == groups.size(), "each belt is saved under its own id" + " " + saves);
        }

        // And the other direction: a dart this config rates that no belt carries is a round the
        // gunner of that gun cannot select, whatever the config says it is worth.
        Set<String> orphans = new LinkedHashSet<>();
        for (Map.Entry<String, ArmorConfig.Ammo> entry : profile.ammo_penetration.entrySet()) {
            ArmorConfig.Ammo ammo = entry.getValue();
            if (ammo != null && ammo.shelf() == com.ywzjvehicle.armor.AmmoCategory.AUTOCANNON_AP
                    && entry.getKey().startsWith(namespace + ":") && !belted.contains(entry.getKey())) {
                orphans.add(entry.getKey());
            }
        }
        report(orphans.isEmpty(), orphans.isEmpty()
                ? "every armour-piercing autocannon round is in a belt"
                : "rated here but in no belt: " + orphans);
        System.out.println("  " + checked + " dual-feed cannon(s) checked");
    }

    private static boolean isAutocannonHe(ArmorConfig.Profile profile, String id) {
        ArmorConfig.Ammo ammo = profile.ammo_penetration.get(id);
        return ammo != null && ammo.shelf() == com.ywzjvehicle.armor.AmmoCategory.AUTOCANNON_HE;
    }

    /** The {@code {"ids": [...]}} groups in a vehicle's weapons arrays, in the order they are read. */
    private static List<JsonObject> groups(JsonObject vehicle) {
        List<JsonObject> found = new ArrayList<>();
        if (vehicle == null || !vehicle.has("parts")) {
            return found;
        }
        for (JsonElement element : vehicle.getAsJsonArray("parts")) {
            JsonObject part = element.getAsJsonObject();
            if (!part.has("weapons")) {
                continue;
            }
            for (JsonElement weapon : part.getAsJsonArray("weapons")) {
                if (weapon.isJsonObject() && weapon.getAsJsonObject().has("ids")) {
                    found.add(weapon.getAsJsonObject());
                }
            }
        }
        return found;
    }

    private static List<String> members(JsonObject group) {
        List<String> ids = new ArrayList<>();
        for (JsonElement member : group.getAsJsonArray("ids")) {
            ids.add(member.getAsString());
        }
        return ids;
    }

    /**
     * Holds the shelf rule against every round the config already files.
     *
     * <p>A scaffold is only as useful as the shelf it puts a round on, and a shelf guess is the one
     * part of this tool that is inference rather than measurement. The fourteen shipped rounds are
     * the reference: each one has a shelf a human chose, and the rule has to reproduce all of them
     * before it is allowed to file a fifteenth.
     */
    private static void categories(ZipFile zip, ArmorConfig.Profile profile) {
        System.out.println();
        System.out.println("=== shelf rule vs the shipped ammunition ===");
        int agreed = 0;
        int counted = 0;
        List<String> wrong = new ArrayList<>();
        List<String> unpacked = new ArrayList<>();
        for (Map.Entry<String, ArmorConfig.Ammo> entry : profile.ammo_penetration.entrySet()) {
            String id = entry.getKey();
            Map<String, Weapon> single = new LinkedHashMap<>();
            add(zip, id, single);
            Weapon weapon = single.get(id);
            if (!weapon.packed()) {
                unpacked.add(id);
                continue;
            }
            counted++;
            String want = entry.getValue().shelf().id();
            if (want.equals(weapon.category())) {
                agreed++;
            } else {
                wrong.add(String.format("  %-34s config says %-14s the rule says %s",
                        id, want, weapon.category()));
            }
        }
        System.out.println("  " + agreed + "/" + counted + " filed where the config files them");
        wrong.forEach(line -> System.out.println(line));
        if (counted == 0) {
            // Nothing of this pack's is configured yet, which is the ordinary state of a pack the
            // addon has not been adapted to. Listed by count rather than by name: the alternative is
            // the whole config echoed once per pack, five times over, to say nothing.
            System.out.println("  no configured round belongs to this pack");
        } else if (!unpacked.isEmpty()) {
            // Legitimate for the base mod's own rounds, which have no pack file to read a calibre or
            // a designation from - the rule cannot be applied to them and does not pretend to be.
            System.out.println("  " + unpacked.size() + " configured round(s) belong to other packs");
        }
        report(wrong.isEmpty(), "every packed round is filed on the shelf the config gives it");
    }

    // ---------------------------------------------------------------- list

    /** Every vehicle the pack ships, so it is visible which ones are still unadapted. */
    private static void list(ZipFile zip, ArmorConfig.Profile profile) {
        System.out.println();
        System.out.println("=== vehicles in the pack ===");
        System.out.printf("  %-22s %-18s %-8s %s%n", "id", "type", "parts", "configured");
        System.out.println("  " + "-".repeat(72));
        int configured = 0;
        int total = 0;
        for (String id : vehicleIds(zip)) {
            total++;
            JsonObject vehicle = read(zip, vehicleDir() + id + ".json");
            String type = vehicle == null || !vehicle.has("type")
                    ? "?" : shortName(vehicle.get("type").getAsString());
            int parts = vehicle == null || !vehicle.has("parts")
                    ? 0 : vehicle.getAsJsonArray("parts").size();
            boolean known = profile.vehicles.containsKey(namespace + ":" + id);
            if (known) {
                configured++;
            }
            System.out.printf("  %-22s %-18s %-8d %s%n", id, type, parts,
                    known ? "yes" : "");
        }
        System.out.println();
        System.out.println("  " + configured + " of " + total + " configured; the rest fall through "
                + "to the vanilla pipeline");
        System.out.println("RESULT: packed vehicle list read");
    }

    // ---------------------------------------------------------------- form

    /**
     * Prints the adaptation worksheet: what has to be true before starting, what the addon requires
     * of the numbers, and a table to fill in - with the half that can be measured already filled.
     *
     * <p>Markdown, because a worksheet is a thing people paste into an issue, a note or a chat, and
     * because the measured half has to stay legible next to the blank half. The blank half is not
     * decoration: the fields left empty here are exactly the ones that are a judgement about a
     * vehicle rather than a reading of its model, and writing them down as blanks is the point.
     */
    private static void form(ZipFile zip, ArmorConfig.Profile profile, String id, Path pack)
            throws Exception {
        Measured measured = measure(zip, id);
        if (measured == null) {
            throw new IllegalStateException("the pack has no vehicle or structure file for " + id);
        }
        String full = namespace + ":" + id;
        List<Weapon> weapons = weapons(zip, id, measured);
        String packName = pack.getFileName().toString();
        boolean known = profile.vehicles.containsKey(full);

        System.out.println("# 载具适配数据收集表");
        System.out.println();
        System.out.println("- 载具 id：`" + full + "`");
        System.out.println("- 载具包：`" + packName + "`");
        System.out.println("- 写入位置：`config/ywzj_vehicle_armor.json`");
        System.out.println("- 已配过：**" + (known ? "是 —— 再写一遍会整个替换掉它" : "否") + "**");
        System.out.println();
        System.out.println("---");
        System.out.println();
        System.out.println("## 一、要求");
        System.out.println();
        System.out.println("### 1.1 开始之前");
        System.out.println();
        System.out.println("- [ ] 载具包里有 `" + vehicleDir() + id + ".json`");
        System.out.println("- [ ] 载具包里有 `" + structureDir() + id + ".structure.json`");
        System.out.println("- [ ] 上面「已配过」为否，或者你确定要替换掉现有的那一份");
        System.out.println("- [ ] 知道这辆车的**实车**装甲厚度 —— 这一步没有捷径，也不该有");
        System.out.println();
        System.out.println("### 1.2 硬性要求");
        System.out.println();
        System.out.println("| # | 要求 | 不满足会怎样 |");
        System.out.println("|---|---|---|");
        System.out.println("| R1 | `turret` 和 `hull` 的五个面都要给值 | 缺的面取默认 90 —— "
                + "不报错，只是数字是错的 |");
        System.out.println("| R2 | 弹药的 `profile` 必须是本车 `overrides` 里存在的名字 | "
                + "**静默**退回载具自己的装甲表，日志里什么都没有 |");
        System.out.println("| R3 | `ammo_penetration` 的键必须是载具包里真实存在的武器 id | "
                + "配了也永远不生效，同样没有日志 |");
        System.out.println("| R4 | 「已量出」那一节的数字一律不要改 | 它们是量出来的，改了就是错的 |");
        System.out.println("| R5 | `penetration_damage` 一旦填了，就**取代** "
                + "`剩余穿深 × 倍率` | 高爆弹那种「不看得穿多少」的弹才该填 |");
        System.out.println("| R6 | `overrides.he` 只写和 `overrides.heat` **不同**的面 | "
                + "写全了容易和 heat 漂移，漏写才是正常用法 |");
        System.out.println();
        System.out.println("---");
        System.out.println();
        System.out.println("## 二、数据收集表");
        System.out.println();
        System.out.println("### 2.1 载具身份（工具已填）");
        System.out.println();
        System.out.println("| 字段 | 值 |");
        System.out.println("|---|---|");
        System.out.println("| `vehicles` 的键 | `" + full + "` |");
        System.out.println("| 载具类型 | " + typeOf(zip, id) + " |");
        System.out.println("| 部件数 | " + measured.parts.size() + " |");
        System.out.println();
        System.out.println("### 2.2 几何（工具已量出，照抄，不要手改）");
        System.out.println();
        System.out.println("| 字段 | 值 | 依据 |");
        System.out.println("|---|---|---|");
        System.out.println("| `turret_part_ids` | `" + measured.turretParts + "` | "
                + "骨骼树：挂在炮塔骨骼下面、且不是座位的部件 | ");
        if (measured.gunOffsetX != null) {
            System.out.printf(Locale.ROOT,
                    "| `turret_weak_spot.offset_x` | `%s` | 炮管立方体（`%s`）中心相对炮塔盒中心 |%n",
                    round(measured.gunOffsetX), measured.gunBone);
            System.out.printf(Locale.ROOT,
                    "| `turret_weak_spot.offset_y` | `%s` | 同上 |%n", round(measured.gunOffsetY));
        } else {
            System.out.println("| `turret_weak_spot` | 未量到炮管 —— 这辆车不配炮根弱点 | |");
        }
        if (measured.wheelTop != null) {
            System.out.printf(Locale.ROOT,
                    "| `road_wheels.top` | `%s` | 轮子立方体顶面，换算成车体归一化高度 |%n",
                    round(measured.wheelTop));
        }
        if (!measured.trackBones.isEmpty()) {
            System.out.printf(Locale.ROOT,
                    "| `track_ends.inner_edge` | `%s` | 履带内侧面，占车体半宽的比例（**上限**，"
                            + "只能填得比它小） |%n", round(measured.trackInner));
        }
        if (measured.portX != null) {
            System.out.printf(Locale.ROOT,
                    "| `hull_weak_spot.offset_x` | `%s` | 车体机枪（`%s`）相对车体盒中心，单位是格 |%n",
                    round(measured.portX), measured.portBone);
            System.out.printf(Locale.ROOT, "| `hull_weak_spot.offset_y` | `%s` | 同上 |%n",
                    round(measured.portY));
        }
        System.out.println("| 车体盒（块） | " + measured.hull + " | `main_structure` 骨骼最大立方体 |");
        System.out.println("| 炮塔盒（块） | " + measured.turret + " | 炮塔骨骼最大立方体 |");
        System.out.println();
        System.out.println("> `hull_front_split.split` **量不出**，所以不在上表里。它是首上和首下的分界"
                + "（`+1` 顶 / `0` 中 / `-1` 底），模组碰撞体是一整块平盒子、视觉模型也没有斜面几何，"
                + "只能看车定。T-64 用 `0.0`，Abrams 用 `0.4`。");
        System.out.println();
        System.out.println("### 2.3 装甲数值（**待填**）");
        System.out.println();
        System.out.println("单位 mm；等效厚度指「这发弹要打穿它需要多少穿深」。五个面一律必填。");
        System.out.println();
        System.out.println("| 字段 | 含义 | 必填 | 填写 |");
        System.out.println("|---|---|---|---|");
        for (String part : new String[]{"turret", "hull"}) {
            for (String face : new String[]{"front", "side", "rear", "roof", "floor"}) {
                System.out.println("| `" + part + "." + face + "` | " + faceName(face)
                        + (part.equals("turret") ? "（炮塔）" : "（车体）") + " | 是 | |");
            }
        }
        System.out.println();
        System.out.println("### 2.4 伤害倍率（选填）");
        System.out.println();
        System.out.println("每个面的「每毫米剩余穿深 = 多少伤害」。**不填就用全局默认**"
                + "（正面 ×50、其余 ×5）。薄板倍率必须压低，否则穿深减薄板剩余很大、一打就秒。");
        System.out.println();
        System.out.println("| 字段 | 参考值 | 填写 |");
        System.out.println("|---|---|---|");
        for (String face : new String[]{"front", "side", "rear", "roof", "floor"}) {
            System.out.println("| `damage_multiplier." + face + "` | 正面 50、侧面 5、"
                    + "履带/轮子 1.5~2 | |");
        }
        System.out.println();
        System.out.println("### 2.5 可选区域（选填；不填就整块不生效）");
        System.out.println();
        System.out.println("| 区域 | 子字段 | 说明 | 本车 |");
        System.out.println("|---|---|---|---|");
        System.out.println("| `turret_weak_spot` | `enabled` `thickness` `multiplier` `radius` "
                + "`offset_x` `offset_y` | 炮根弱点圆盘。`radius <= 0` 等于关闭。偏移已量出 | "
                + (measured.gunOffsetX != null ? "可配" : "无炮管") + " |");
        System.out.println("| `hull_front_split` | `enabled` `split` `upper.{thickness,multiplier}` "
                + "`lower.{...}` | 首上 / 首下按命中高度切分 | "
                + (measured.wheelTop == null ? "可配" : "轮式，通常不需要") + " |");
        System.out.println("| `track_ends` | `enabled` `thickness` `multiplier` `inner_edge` `top` | "
                + "外侧两条履带区。`inner_edge` 已量出；`top` 是条带高度上限 | "
                + (measured.trackBones.isEmpty() ? "无履带" : "可配") + " |");
        System.out.println("| `road_wheels` | `enabled` `thickness` `multiplier` `top` | "
                + "轮式车辆的整个下车身侧面。`top` 已量出 | "
                + (measured.wheelTop != null ? "可配" : "无轮") + " |");
        System.out.println("| `hull_side_era` | `thickness` `multiplier` `penetration_loss` `era` + "
                + "`hull_side_era_front_fraction` `hull_side_era_min_angle` | 车体侧面**前段**的"
                + "裙板/反应装甲。`fraction` 是覆盖比例，`loss` 见下，`min_angle` 见下，"
                + "`era` 见下下 | 自行判断 |");
        System.out.println("| `part_armor` | `\"<部件 id>\": { thickness multiplier penetration_loss era }` | "
                + "**按部件单位记值**：打在模型上那个部件上就按这个数算。键是部件 id，"
                + "载具层和 `overrides.<弹种>` 里都能写，同名的部件按弹种的那份优先。"
                + "炮塔上的反应装甲块 / 附加装甲板走这个 | 看模型有没有独立部件 |");
        System.out.println();
        System.out.println("> **`era`（爆反类型）**：只写在上面两处才有意义。`\"light\"` = 串联战斗部"
                + "**穿得过去**但穿深**削掉三分之一**，板不消耗；`\"heavy\"` = 串联战斗部**被整个挡下**"
                + "且**板就此用掉**，第二发打的是它后面的主装甲。**普通弹两种都不受影响**，"
                + "撞上板就按板算。不写 / 写错 = 普通装甲。详见 README「串联战斗部与两种爆反」。");
        System.out.println();
        System.out.println("> **`part_armor` 的脾气**：它**排在所有其它判定之前**，而且**无条件** —— "
                + "打中这个部件就按它算，不看面、不看角度。所以「这个盒子只有斜着打才算数」这种话"
                + "在这里写不出来；那种要在几何版的 `hull_side_era` 上做。"
                + "好处是它按部件走，盒子的位置、大小、缝隙全都不用你操心。");
        System.out.println();
        System.out.println("> **`penetration_loss`（穿深削减）**：这块板让穿过去的弹损失这么多毫米穿深。"
                + "加厚度只有两种结局 —— 挡住，或者放过去而且毫发无伤；而裙板、间隙装甲、反应装甲"
                + "真正干的是「把弹打残」。典型写法是 `thickness` 给几毫米（钢板的厚度），"
                + "`penetration_loss` 给一个远大于它的数。默认 0，就是普通装甲。"
                + "**按弹种分开**：写在 `overrides.heat` 里就是只对破甲弹。");
        System.out.println();
        System.out.println("> ⚠️ **但现在它和「加厚」算出来是同一个数**：一发弹打到第一个区域就停下，"
                + "不会带着削减继续去撞后面的主装甲，所以 `loss` 和把板写成 `thickness + loss` 等效。"
                + "要分开只有打开 `angle_normalization`。详见 README「裙板能做的两件装甲板做不到的事」。");
        System.out.println();
        System.out.println("> **`hull_side_era_min_angle`（入射角门槛，单位是度）**：裙板/"
                + "格栅**只在这个角度以上才算数**。它靠的是把破甲弹的射流横着掐断，所以斜着擦进来很有用、"
                + "正对着打等于没有。写在车体上而不是弹药上 —— 板子的角度是车怎么造的。"
                + "`0`（默认）＝ 永远算数，也就是现有那几辆车的行为。");
        System.out.println();
        System.out.println("### 2.6 破甲弹装甲表 `overrides.heat`（强烈建议填）");
        System.out.println();
        System.out.println("同一块板对动能弹和对破甲弹完全不同：钢针靠动能硬顶，倾斜厚板对它最有效；"
                + "破甲弹靠射流烧穿，倾斜影响小得多，反而被反应装甲克制。**不填这一块，破甲弹就会用"
                + "上面那套动能数值**。");
        System.out.println();
        System.out.println("字段名和 2.3 / 2.4 **完全一样**，另加 `turret_weak_spot`、`hull_front_split`、"
                + "`track_ends`、`road_wheels` 这几个 `{thickness, multiplier}` 覆盖项。");
        System.out.println();
        System.out.println("| 从哪里抄 | 抄什么 |");
        System.out.println("|---|---|");
        System.out.println("| `overrides.heat.turret.*` / `.hull.*` | 同 2.3，五个面 |");
        System.out.println("| `overrides.heat.damage_multiplier.*` | 同 2.4，破甲弹倍率通常更低 |");
        System.out.println("| `overrides.he` | 只写和 `heat` **不同**的面（高爆弹一般只差顶部/底部） |");
        System.out.println();
        System.out.println("### 2.7 弹药（**待填**）");
        System.out.println();
        System.out.println("这辆车带的武器：");
        System.out.println();
        System.out.println("| 武器 id | 包里叫什么 | 口径 | 建议货架 | 状态 |");
        System.out.println("|---|---|---|---|---|");
        for (Weapon weapon : weapons) {
            String state = !weapon.packed() ? "**不配** —— 载具包没有它，配了也不生效"
                    : profile.ammo_penetration.containsKey(weapon.id)
                            ? "已配过，共用一条，不要重复写" : "**待填**";
            System.out.printf("| `%s` | %s | %s | %s | %s |%n", weapon.id, weapon.name,
                    weapon.caliber > 0 ? weapon.caliber + "mm" : "-",
                    weapon.packed() ? "`" + weapon.category + "`" : "-", state);
        }
        System.out.println();
        System.out.println("每个**待填**的武器要写的字段：");
        System.out.println();
        System.out.println("| 字段 | 含义 | 必填 | 取值要求 | 填写 |");
        System.out.println("|---|---|---|---|---|");
        System.out.println("| `penetration` | 等效穿深 | 是 | ≥ 0，见下 | |");
        System.out.println("| `profile` | 用哪套装甲表 | 否 | 必须是本车 `overrides` 里的名字 | |");
        System.out.println("| `category` | 弹药合集里的货架 | 是 | 六个之一，见表下 | |");
        System.out.println("| `multiplier` | 无视部位倍率的固定倍率 | 否 | 机枪才用；填了就不看命中部位 | |");
        System.out.println("| `penetration_damage` | 固定伤害，不乘倍率 | 否 | ≥ 0；高爆弹用 | |");
        System.out.println("| `replaces_base_explosion` | 吞掉基础模组的爆炸伤害 | 否 | 只有自带爆炸的弹才写 `true` | |");
        System.out.println("| `spall_damage_min/max` | 溅射伤害区间 | 否 | ≥ 0；破甲弹才有 | |");
        System.out.println("| `spall_falloff_to` | 板厚到此溅射降到最低 | 否 | 配合 `spall_damage_min` | |");
        System.out.println("| `spall_on_penetration` | 击穿时也溅射 | 否 | 只有「没穿也要溅射」才写 `false` | |");
        System.out.println("| `spall_blindness_ticks` | 溅射致盲时长 | 否 | tick，20 = 1 秒 | |");
        System.out.println("| `tandem` | 串联战斗部 | 否 | 只有反坦克导弹这类**前驱装药**才写 `true` | |");
        System.out.println();
        System.out.println("> **`tandem` 是什么**：这发弹的前驱装药专门对付反应装甲，所以它和爆反的结算"
                + "与普通弹不同 —— 对**轻型**爆反穿过去但**削掉三分之一穿深**，对**重型**爆反**被挡下**"
                + "并把那块板用掉。打在裸装甲上时它和普通弹完全一样。"
                + "**爆反类型写在车上**（见 2.5 的 `era`）。详见 README「串联战斗部与两种爆反」。");
        System.out.println();
        System.out.println("分类 id 是**弹种 × 武器类别**两个维度拼起来的，只有这六种组合：");
        System.out.println();
        System.out.println("| 分类 id | 弹种 | 武器类别 |");
        System.out.println("|---|---|---|");
        for (com.ywzjvehicle.armor.AmmoCategory shelf : com.ywzjvehicle.armor.AmmoCategory.values()) {
            System.out.printf("| `%s` | %s | %s |%n", shelf.id(), shelf.warhead().id(),
                    shelf.weapon().id());
        }
        System.out.println();
        System.out.println("> `penetration` 是**等效**穿深，不是现实穿深：它要比对的另一头是上面那些"
                + "等效厚度，两端同一把尺子才成立。");
        System.out.println();
        System.out.println("---");
        System.out.println();
        System.out.println("## 三、填完之后");
        System.out.println();
        System.out.println("- [ ] 数值已粘进 `config/ywzj_vehicle_armor.json`");
        System.out.println("- [ ] `powershell -File verify.ps1` 无 FAIL");
        System.out.println("- [ ] `PenetrationTable` 里这辆车的伤害表**逐行读过**："
                + "每个部位打几发击毁，是不是你要的手感");
        System.out.println("- [ ] 重启游戏（配置在启动时读）。**不需要重新构建**");
        System.out.println();
        System.out.println("> 要拿粘贴用的 JSON：`add_vehicle.ps1 " + id + "`");
        System.out.println();
        System.out.println("RESULT: worksheet printed for " + full);
    }

    private static String typeOf(ZipFile zip, String id) {
        JsonObject vehicle = read(zip, vehicleDir() + id + ".json");
        return vehicle != null && vehicle.has("type")
                ? shortName(vehicle.get("type").getAsString()) : "?";
    }

    private static String faceName(String face) {
        switch (face) {
            case "front": return "正面";
            case "side": return "侧面";
            case "rear": return "后面";
            case "roof": return "顶部";
            default: return "底部";
        }
    }

    // ---------------------------------------------------------------- scaffold

    /** Prints the config a vehicle needs, with the geometry measured and the armour left blank. */
    private static void scaffold(ZipFile zip, ArmorConfig.Profile profile, String id) throws Exception {
        Measured measured = measure(zip, id);
        if (measured == null) {
            throw new IllegalStateException("the pack has no vehicle or structure file for " + id);
        }
        String full = namespace + ":" + id;

        System.out.println();
        System.out.println("=== " + full + " ===");
        System.out.println("  hull box (blocks)  : " + measured.hull);
        System.out.println("  turret box (blocks): " + measured.turret);
        System.out.println("  turret part ids    : " + measured.turretParts);
        System.out.println("  wheel bones        : " + measured.wheelBones);
        System.out.println("  track bones        : " + measured.trackBones);
        if (measured.wheelTop != null) {
            System.out.printf(Locale.ROOT, "  wheel top (local Y): %.3f%n", measured.wheelTop);
        }
        if (measured.trackInner != 0.0) {
            System.out.printf(Locale.ROOT, "  track inner (local): %.3f%n", measured.trackInner);
        }
        if (measured.gunOffsetX != null) {
            System.out.printf(Locale.ROOT, "  gun vs turret box  : (%+.3f, %+.3f)  [bone %s]%n",
                    measured.gunOffsetX, measured.gunOffsetY, measured.gunBone);
        }
        System.out.println("  part units         : " + measured.parts);
        if (profile.vehicles.containsKey(full)) {
            System.out.println();
            System.out.println("  NOTE: already in the shipped config - adding it again would replace it.");
        }

        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        // The geometry is real, the armour is blank. Zeroes rather than plausible round numbers on
        // purpose: an entry that looks finished is one nobody finishes.
        JsonObject turret = armourSet();
        JsonObject hull = armourSet();
        JsonObject vehicle = new JsonObject();
        vehicle.add("turret", turret);
        vehicle.add("hull", hull);
        vehicle.add("damage_multiplier", multiplier());
        if (measured.gunOffsetX != null) {
            JsonObject spot = new JsonObject();
            spot.addProperty("enabled", true);
            spot.addProperty("thickness", 0.0);
            spot.addProperty("multiplier", 0.0);
            // The default radius: how wide the hole the barrel comes through is, is a judgement about
            // the model, and the two shipped vehicles with one both use the default.
            spot.addProperty("radius", 0.4);
            spot.addProperty("offset_x", round(measured.gunOffsetX));
            spot.addProperty("offset_y", round(measured.gunOffsetY));
            vehicle.add("turret_weak_spot", spot);
        }
        if (measured.portX != null) {
            JsonObject port = new JsonObject();
            port.addProperty("enabled", true);
            port.addProperty("thickness", 0.0);
            port.addProperty("multiplier", 0.0);
            // The same default the gun mount uses: how wide the mounting is, is a judgement about
            // the model, and 0.6 blocks across is about right for a bow MG ball.
            port.addProperty("radius", 0.3);
            port.addProperty("offset_x", round(measured.portX));
            port.addProperty("offset_y", round(measured.portY));
            vehicle.add("hull_weak_spot", port);
        }
        if (!measured.wheelBones.isEmpty()) {
            JsonObject wheels = new JsonObject();
            wheels.addProperty("enabled", true);
            wheels.addProperty("thickness", 0.0);
            wheels.addProperty("multiplier", 0.0);
            wheels.addProperty("top", round(measured.wheelTop));
            vehicle.add("road_wheels", wheels);
        } else {
            JsonObject split = new JsonObject();
            split.addProperty("enabled", true);
            // The one number here that cannot be derived from anything. It is where the glacis meets
            // the lower plate, and it is a reading of the drawing, not of the collision box.
            split.addProperty("split", 0.0);
            split.add("upper", plate());
            split.add("lower", plate());
            vehicle.add("hull_front_split", split);
        }
        if (!measured.trackBones.isEmpty()) {
            JsonObject track = new JsonObject();
            track.addProperty("enabled", true);
            track.addProperty("thickness", 0.0);
            track.addProperty("multiplier", 0.0);
            track.addProperty("inner_edge", round(measured.trackInner));
            track.addProperty("top", 0.0);
            vehicle.add("track_ends", track);
        }
        JsonArray partIds = new JsonArray();
        measured.turretParts.forEach(partIds::add);
        vehicle.add("turret_part_ids", partIds);
        JsonObject overrides = new JsonObject();
        overrides.add("heat", override());
        vehicle.add("overrides", overrides);

        JsonObject entry = new JsonObject();
        entry.add(full, vehicle);
        System.out.println();
        System.out.println("---- paste into \"vehicles\" ----");
        System.out.println(gson.toJson(entry));

        // Stubs only for rounds the config does not already carry: a shared machine-gun round is one
        // entry however many vehicles mount it, and a second copy under the same key would be a
        // duplicate that silently wins or loses depending on map order.
        JsonObject stubs = new JsonObject();
        List<String> shared = new ArrayList<>();
        List<String> unmeasurable = new ArrayList<>();
        for (Weapon weapon : weapons(zip, id, measured)) {
            if (!weapon.packed()) {
                unmeasurable.add(weapon.id + " (" + weapon.name + ")");
                continue;
            }
            if (profile.ammo_penetration.containsKey(weapon.id)) {
                shared.add(weapon.id + " (" + weapon.name + ")");
                continue;
            }
            JsonObject ammo = new JsonObject();
            ammo.addProperty("penetration", 0.0);
            ammo.addProperty("category", weapon.category);
            stubs.add(weapon.id, ammo);
        }
        System.out.println();
        if (stubs.size() == 0) {
            System.out.println("---- no new ammunition: every weapon this vehicle carries is "
                    + "already configured ----");
        } else {
            System.out.println("---- paste into \"ammo_penetration\" ----");
            System.out.println(gson.toJson(stubs));
        }
        if (!shared.isEmpty()) {
            System.out.println();
            System.out.println("already configured, shared with another vehicle (leave alone):");
            shared.forEach(one -> System.out.println("  " + one));
        }
        if (!unmeasurable.isEmpty()) {
            System.out.println();
            System.out.println("not the pack's rounds, so not this addon's to measure or configure:");
            unmeasurable.forEach(one -> System.out.println("  " + one));
        }

        System.out.println();
        System.out.println("weapons this vehicle carries:");
        for (Weapon weapon : weapons(zip, id, measured)) {
            System.out.printf("  %-40s %-16s %s%n", weapon.id,
                    weapon.packed() ? weapon.category : "-",
                    weapon.name + (weapon.caliber > 0 ? "  " + weapon.caliber + "mm" : "")
                            + (weapon.packed() ? "" : "   [base mod, no pack file]"));
        }

        // The block is parsed back through the mod's own reader before it is offered. A scaffold that
        // prints JSON the config cannot load would be worse than no scaffold: it fails at the next
        // game start, in a log, long after the tool that produced it has been forgotten.
        ArmorConfig.VehicleArmor parsed = ArmorConfig.configReader()
                .fromJson(gson.toJson(vehicle), ArmorConfig.VehicleArmor.class);
        System.out.println();
        if (parsed == null || parsed.turret_part_ids == null
                || parsed.turret_part_ids.length != measured.turretParts.size()) {
            fail("  the block above did not survive a round trip through the config reader");
        } else {
            System.out.println("  the block above parses back as this addon's own config format");
        }

        System.out.println();
        System.out.println("still to fill in (the tool cannot know these):");
        System.out.println("  turret.front / side / rear / roof / floor   effective thickness in mm");
        System.out.println("  hull.front / side / rear / roof / floor     effective thickness in mm");
        System.out.println("  damage_multiplier.*                         residual mm -> damage, per face");
        System.out.println("  every thickness and multiplier inside overrides.heat");
        System.out.println("  turret_weak_spot.thickness / multiplier     if the gun mount is a weak spot");
        System.out.println("  road_wheels.thickness / multiplier          wheeled vehicles only");
        System.out.println("  track_ends.thickness / multiplier / top     tracked vehicles only");
        System.out.println("  hull_front_split.split                      see the note below");
        System.out.println();
        System.out.println("  hull_front_split.split is the one number no model can supply. It is where the");
        System.out.println("  glacis ends and the lower plate begins, as a fraction of hull height (+1 top,");
        System.out.println("  0 middle, -1 bottom). The T-64 uses 0.0 and the Abrams 0.4; set it by looking");
        System.out.println("  at the vehicle, then check both halves with PenetrationTable.");
        System.out.println();
        System.out.println("  This is a config-only change. Nothing needs compiling or rebuilding.");
        System.out.println("RESULT: scaffold printed for " + full);
    }

    /** The ammunition block template: the destructive half of a HEAT round, ready to be filled in. */
    private static JsonObject override() {
        JsonObject override = new JsonObject();
        override.add("turret", armourSet());
        override.add("hull", armourSet());
        override.add("damage_multiplier", multiplier());
        JsonObject spot = plate();
        override.add("turret_weak_spot", spot);
        JsonObject split = new JsonObject();
        split.add("upper", plate());
        split.add("lower", plate());
        override.add("hull_front_split", split);
        override.add("track_ends", plate());
        return override;
    }

    private static JsonObject armourSet() {
        JsonObject set = new JsonObject();
        for (String face : new String[]{"front", "side", "rear", "roof", "floor"}) {
            set.addProperty(face, 0.0);
        }
        return set;
    }

    private static JsonObject multiplier() {
        return armourSet();
    }

    private static JsonObject plate() {
        JsonObject plate = new JsonObject();
        plate.addProperty("thickness", 0.0);
        plate.addProperty("multiplier", 0.0);
        return plate;
    }

    // ---------------------------------------------------------------- measuring

    /** Everything the pack can say about a vehicle's shape. */
    private static final class Measured {
        String hull = "?";
        String turret = "?";
        List<String> turretParts = new ArrayList<>();
        List<String> parts = new ArrayList<>();
        List<String> wheelBones = new ArrayList<>();
        List<String> trackBones = new ArrayList<>();
        Double wheelTop;
        double trackInner;
        Double gunOffsetX;
        Double gunOffsetY;
        String gunBone = "?";

        /** The bow machine gun, if the model has one: where its port sits on the hull front plate. */
        Double portX;
        Double portY;
        String portBone = "?";
    }

    /**
     * True when a part's {@code structure_bone} puts it on the turret.
     *
     * <p>The bone a part names is often not a bone at all. The model keeps the joint and the geometry
     * apart, so {@code turret_mg} exists only as {@code turret_mg_barrel}; the part names the joint,
     * the cubes live one level down. Both the exact name and the underscore-suffixed children are
     * therefore tried - with the underscore, because a bare prefix would let {@code turret_mg} also
     * match a sibling {@code turret_mg2} and quietly fold two separate mountings into one.
     */
    private static boolean onTurret(Set<String> names, Map<String, String> parents,
                                    String bone, String turretBone) {
        if (bone.equals(turretBone)) {
            return true;
        }
        if (names.contains(bone)) {
            return descendsFrom(parents, bone, turretBone);
        }
        for (String candidate : names) {
            if (candidate.startsWith(bone + "_") && descendsFrom(parents, candidate, turretBone)) {
                return true;
            }
        }
        return false;
    }

    private static Measured measure(ZipFile zip, String id) {
        JsonObject vehicle = read(zip, vehicleDir() + id + ".json");
        JsonObject structure = read(zip, structureDir() + id + ".structure.json");
        if (vehicle == null || structure == null) {
            return null;
        }
        Measured m = new Measured();

        Map<String, JsonObject> bones = bones(structure);
        Map<String, String> parents = new TreeMap<>();
        for (Map.Entry<String, JsonObject> bone : bones.entrySet()) {
            if (bone.getValue().has("parent")) {
                parents.put(bone.getKey(), bone.getValue().get("parent").getAsString());
            }
        }

        // The hull is the main_structure bone - the box the addon itself measures everything against,
        // via getMainCubeOBB. Its largest cube, because a bone may hold several.
        JsonObject hullBone = bones.get("main_structure");
        Cube hullCube = hullBone == null ? null : largestCube(hullBone);
        if (hullCube == null) {
            return null;
        }
        m.hull = describe(hullCube);

        // Parts, their bones, and which of them belong to the turret.
        String turretBone = null;
        List<JsonObject> partList = new ArrayList<>();
        if (vehicle.has("parts")) {
            for (JsonElement element : vehicle.getAsJsonArray("parts")) {
                JsonObject part = element.getAsJsonObject();
                partList.add(part);
                String partId = part.has("id") ? part.get("id").getAsString() : "?";
                m.parts.add(partId);
                if ("turret".equals(partId) && part.has("structure_bone")) {
                    turretBone = part.get("structure_bone").getAsString();
                }
            }
        }
        for (JsonObject part : partList) {
            String partId = part.has("id") ? part.get("id").getAsString() : "?";
            String bone = part.has("structure_bone") ? part.get("structure_bone").getAsString() : null;
            if (partId.toLowerCase(Locale.ROOT).contains("seat") || "seat".equalsIgnoreCase(bone)) {
                // A crew position, not armour. Worth stating because it is not obvious from the model:
                // the Abrams parents its driver seat to the turret bone, so taking the bone tree at
                // face value would measure a hit through the driver against the turret front plate.
                continue;
            }
            // A part belongs to the turret when the model says it hangs off the turret bone, which is
            // what the base mod's own part hierarchy means. Falling back to the name only for a part
            // the model gives no bone at all.
            boolean isTurret = bone != null && turretBone != null
                    ? onTurret(bones.keySet(), parents, bone, turretBone)
                    : partId.startsWith("turret");
            if (isTurret) {
                m.turretParts.add(partId);
            }
        }

        Cube turretCube = turretBone == null ? null : largestCube(bones.get(turretBone));
        if (turretCube != null) {
            m.turret = describe(turretCube);
        }

        // The gun is the longest cube anywhere below the turret. Length rather than volume, and no
        // reliance on the bone being called "barrel": on the Abrams the turret's biggest cube by
        // volume is a hatch at three times the gun's, while the gun is longer than everything else on
        // the turret by a factor of four. The bone it came from is printed below, so a wrong pick is
        // visible rather than merely wrong.
        Cube gun = null;
        if (turretBone != null) {
            for (Map.Entry<String, JsonObject> bone : bones.entrySet()) {
                if (!descendsFrom(parents, bone.getKey(), turretBone)) {
                    continue;
                }
                Cube cube = largestCube(bone.getValue());
                if (cube != null && (gun == null || cube.longest() > gun.longest())) {
                    gun = cube;
                    m.gunBone = bone.getKey();
                }
            }
        }
        if (gun != null && turretCube != null) {
            // The same local frame hitsFrontDisc uses: measured from the turret box's centre along the
            // box's own axes, which for an unrotated hull is just X and Y.
            m.gunOffsetX = (gun.cx - turretCube.cx);
            m.gunOffsetY = (gun.cy - turretCube.cy);
        }

        // The bow machine gun, which is a hole through the glacis wherever a model has one. Found by
        // name and then required not to be on the turret, because that is the same word: the Panther
        // calls its hull gun `mg` and its coaxial `turret_machine_gun`, and only one of them is in
        // the front plate.
        Cube port = null;
        if (turretBone != null) {
            for (Map.Entry<String, JsonObject> bone : bones.entrySet()) {
                String name = bone.getKey().toLowerCase(Locale.ROOT);
                boolean looksLikeMg = name.equals("mg") || name.startsWith("mg_")
                        || name.contains("machine_gun");
                if (!looksLikeMg || descendsFrom(parents, bone.getKey(), turretBone)) {
                    continue;
                }
                Cube cube = largestCube(bone.getValue());
                if (cube != null && (port == null || cube.volume() > port.volume())) {
                    port = cube;
                    m.portBone = bone.getKey();
                }
            }
        }
        if (port != null) {
            // In blocks from the hull box's centre, which is what the config's offsets are.
            m.portX = port.cx - hullCube.cx;
            m.portY = port.cy - hullCube.cy;
        }

        // Running gear, by bone name. Wheels and tracks are told apart because a wheeled vehicle has
        // no tracks and a tracked one has no wheels, and the pack names them in plain English.
        for (Map.Entry<String, JsonObject> bone : bones.entrySet()) {
            String name = bone.getKey().toLowerCase(Locale.ROOT);
            Cube cube = largestCube(bone.getValue());
            if (cube == null) {
                continue;
            }
            if (name.contains("wheel")) {
                m.wheelBones.add(bone.getKey());
                double top = (cube.top() - hullCube.cy) / hullCube.hy;
                m.wheelTop = m.wheelTop == null ? top : Math.max(m.wheelTop, top);
            } else if (name.contains("track")) {
                m.trackBones.add(bone.getKey());
                // The track's innermost face across the hull, as a fraction of the half-width: the
                // threshold a track_ends entry is compared against.
                //
                // Measured from the hull box's own centre, which is what the armour system does when
                // it turns a hit into a hull-local coordinate. A hull that is not centred on x would
                // otherwise have both its tracks reported a little too far out - and the three
                // vehicles this was written against are all centred, so nothing would have said so.
                double inner = (Math.abs(cube.cx - hullCube.cx) - cube.hx) / hullCube.hx;
                m.trackInner = Math.max(m.trackInner, inner);
            }
        }
        return m;
    }

    /** Every weapon id the vehicle's parts refer to, with what the weapon file says about it. */
    private static List<Weapon> weapons(ZipFile zip, String id, Measured measured) {
        JsonObject vehicle = read(zip, vehicleDir() + id + ".json");
        Map<String, Weapon> found = new LinkedHashMap<>();
        if (vehicle != null && vehicle.has("parts")) {
            for (JsonElement element : vehicle.getAsJsonArray("parts")) {
                JsonObject part = element.getAsJsonObject();
                if (!part.has("weapons")) {
                    continue;
                }
                collect(zip, part.getAsJsonArray("weapons"), found);
            }
        }
        return new ArrayList<>(found.values());
    }

    /**
     * Walks one part's {@code weapons} array.
     *
     * <p>Four shapes live in there, and all four are in the stock pack or in the patched one: a bare
     * id, a bare array of ids, an object carrying {@code ids} (a belt - two rounds the gunner
     * switches between with the multi-weapon key), and an object naming a different part unit, which
     * is how a coaxial machine gun is attached to the turret that carries it.
     */
    private static void collect(ZipFile zip, JsonArray array, Map<String, Weapon> found) {
        for (JsonElement element : array) {
            if (element.isJsonArray()) {
                for (JsonElement inner : element.getAsJsonArray()) {
                    add(zip, inner.getAsString(), found);
                }
            } else if (element.isJsonPrimitive()) {
                add(zip, element.getAsString(), found);
            } else if (element.isJsonObject() && element.getAsJsonObject().has("ids")) {
                for (JsonElement inner : element.getAsJsonObject().getAsJsonArray("ids")) {
                    add(zip, inner.getAsString(), found);
                }
            } else if (element.isJsonObject() && element.getAsJsonObject().has("id")) {
                add(zip, element.getAsJsonObject().get("id").getAsString(), found);
            }
        }
    }

    private static void add(ZipFile zip, String id, Map<String, Weapon> found) {
        if (found.containsKey(id)) {
            return;
        }
        JsonObject file = read(zip, weaponPath(id));
        String name = id;
        int caliber = 0;
        if (file != null) {
            if (file.has("name")) {
                name = file.get("name").getAsString();
            }
            if (file.has("caliber")) {
                caliber = file.get("caliber").getAsInt();
            }
        }
        // A weapon with no file of its own is the base mod's, not the pack's - smoke launchers and
        // the like. It is still shown, because it is on the vehicle, but it is never offered as an
        // ammo_penetration entry: a round this addon cannot measure is a setting that can never do
        // anything, which is the same thing the compendium tool reports from the other direction.
        found.put(id, new Weapon(id, name, caliber, category(id, name, caliber), file != null));
    }

    /**
     * Which shelf the compendium should file a round on.
     *
     * <p>Read off the calibre and the designation, because there is nothing else to read: the pack
     * types every weapon it ships as {@code ywzj_vehicle:cannon}, from a 125mm tank gun down to a
     * 7.62mm coaxial, so the type field carries no information at all about what a round is.
     *
     * <p>The order of the tests is the whole of the logic. Calibre first, because a 7.62mm is a
     * machine-gun round no matter what its name says. Then "heat" before "he", because every HEAT
     * round contains the letters of an HE round and would otherwise be filed as one.
     *
     * <p>Check mode holds this against the fourteen rounds the config already files, so a rule that
     * drifted away from the shipped answers is reported rather than believed.
     */
    private static String category(String id, String name, int caliber) {
        // Tokenised rather than searched as one string: a substring test for "he" would claim
        // "heat", "the" and half the German vocabulary, and one for "br" would claim "breda". Split
        // on anything that is not alphanumeric and each of these is a word or a mark number.
        Set<String> words = new LinkedHashSet<>();
        for (String word : (id + " " + name).toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        // The designations a round can carry, in the languages the packs use them in. German:
        // Panzergranate is armour-piercing and Sprenggranate is high-explosive - the Panther's two
        // rounds are named that way and say nothing in English at all. Russian: UBR is
        // armour-piercing, UOF is high-explosive.
        boolean kinetic = named(words, "apfsds", "apds", "apcbc", "apcr", "aphe", "pzgr", "ubr", "sabot");
        // An anti-tank guided missile is a shaped charge in every pack here and in nearly every
        // real one - the RK-2S on the BTR-3 is one, and its name says nothing else about it. Without
        // this it falls through to the default below and lands on the armour-piercing shelf, which
        // is where a HEAT warhead least belongs.
        boolean chemical = named(words, "heat", "mpat", "hollow", "atgm");
        boolean explosive = named(words, "he", "sprgr", "uof", "uo", "hesh");

        if (caliber > 0 && caliber <= 15) {
            return "machine_gun";
        }
        if (caliber > 0 && caliber <= 40) {
            return kinetic ? "autocannon_ap" : "autocannon_he";
        }
        if (chemical) {
            return "cannon_heat";
        }
        if (kinetic) {
            return "cannon_ap";
        }
        if (explosive) {
            return "cannon_he";
        }
        return "cannon_ap";
    }

    /**
     * @return whether any word carries one of the given designation stems.
     *
     * <p>Long stems match a prefix, because a designation usually arrives with a mark number stuck
     * to it - {@code sprgr42} is a Sprgr 42, and an exact comparison would miss every one of them.
     * Short stems are compared whole, because as prefixes they claim far too much: {@code he} would
     * take "heat" and {@code br} would take "breda".
     */
    private static boolean named(Set<String> words, String... stems) {
        for (String word : words) {
            for (String stem : stems) {
                if (stem.length() >= 4 ? word.startsWith(stem) : word.equals(stem)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- pack plumbing

    private static JsonObject read(ZipFile zip, String path) {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) {
            return null;
        }
        try (var stream = zip.getInputStream(entry)) {
            return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    /** The pack's vehicle ids, taken from the filenames rather than from an index that may not exist. */
    private static List<String> vehicleIds(ZipFile zip) {
        List<String> ids = new ArrayList<>();
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.startsWith(vehicleDir()) && name.endsWith(".json")
                    && name.indexOf('/', vehicleDir().length()) < 0) {
                ids.add(name.substring(vehicleDir().length(), name.length() - ".json".length()));
            }
        }
        ids.sort(String::compareTo);
        return ids;
    }

    /**
     * The pack's namespace, from its own {@code vehicle_pack.meta.json}.
     *
     * <p>Asked of the pack rather than taken from the file name or from a constant, because the two
     * packs in play disagree: the Vintage Vehicle Pack is {@code vvp_ywzj} and the tank pack is
     * {@code tank_assault}, and a vehicle's config key is built from this. Read from the metadata
     * first; if a pack predates that file, the directory listing answers the same question.
     */
    private static String detectNamespace(ZipFile zip) {
        JsonObject meta = read(zip, "vehicle_pack.meta.json");
        if (meta != null && meta.has("namespace")) {
            return meta.get("namespace").getAsString();
        }
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            // "data/<namespace>/vehicles/..." - the only place a namespace is unambiguous.
            if (name.startsWith("data/") && name.contains("/vehicles/")) {
                return name.substring("data/".length(), name.indexOf("/vehicles/"));
            }
        }
        throw new IllegalStateException("the pack names no namespace and has no vehicles to infer one");
    }

    /** The bones of the first geometry in a bedrock structure file, by name. */
    private static Map<String, JsonObject> bones(JsonObject structure) {
        Map<String, JsonObject> bones = new LinkedHashMap<>();
        if (!structure.has("minecraft:geometry")) {
            return bones;
        }
        for (JsonElement geometry : structure.getAsJsonArray("minecraft:geometry")) {
            JsonObject object = geometry.getAsJsonObject();
            if (!object.has("bones")) {
                continue;
            }
            for (JsonElement element : object.getAsJsonArray("bones")) {
                JsonObject bone = element.getAsJsonObject();
                if (bone.has("name")) {
                    bones.put(bone.get("name").getAsString(), bone);
                }
            }
        }
        return bones;
    }

    /** True when {@code bone} is somewhere below {@code ancestor} in the model's bone tree. */
    private static boolean descendsFrom(Map<String, String> parents, String bone, String ancestor) {
        String at = parents.get(bone);
        // Bounded rather than trusting the file: a model with a cycle in its parenting would
        // otherwise hang the tool, and a hang tells you less than a wrong answer.
        for (int hops = 0; at != null && hops < 64; hops++) {
            if (at.equals(ancestor)) {
                return true;
            }
            at = parents.get(at);
        }
        return false;
    }

    /** A cube in blocks: centre and half-extents, which is the form the addon's OBBs use. */
    private record Cube(double cx, double cy, double cz, double hx, double hy, double hz) {
        double top() {
            return cy + hy;
        }

        double volume() {
            return 8.0 * hx * hy * hz;
        }

        /** The single longest side, in blocks - what tells a gun barrel from any other box. */
        double longest() {
            return 2.0 * Math.max(hx, Math.max(hy, hz));
        }
    }

    private static Cube largestCube(JsonObject bone) {
        if (bone == null || !bone.has("cubes")) {
            return null;
        }
        Cube largest = null;
        for (JsonElement element : bone.getAsJsonArray("cubes")) {
            JsonObject cube = element.getAsJsonObject();
            if (!cube.has("origin") || !cube.has("size")) {
                continue;
            }
            JsonArray origin = cube.getAsJsonArray("origin");
            JsonArray size = cube.getAsJsonArray("size");
            Cube made = new Cube(
                    (origin.get(0).getAsDouble() + size.get(0).getAsDouble() / 2.0) / UNITS,
                    (origin.get(1).getAsDouble() + size.get(1).getAsDouble() / 2.0) / UNITS,
                    (origin.get(2).getAsDouble() + size.get(2).getAsDouble() / 2.0) / UNITS,
                    size.get(0).getAsDouble() / (2.0 * UNITS),
                    size.get(1).getAsDouble() / (2.0 * UNITS),
                    size.get(2).getAsDouble() / (2.0 * UNITS));
            if (largest == null || made.volume() > largest.volume()) {
                largest = made;
            }
        }
        return largest;
    }

    private static String describe(Cube cube) {
        return String.format(Locale.ROOT, "centre (%+.2f, %+.2f, %+.2f) half (%.2f, %.2f, %.2f)",
                cube.cx, cube.cy, cube.cz, cube.hx, cube.hy, cube.hz);
    }

    private static String shortName(String id) {
        return id.substring(id.indexOf(':') + 1);
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static ArmorConfig.Profile defaultProfile() throws Exception {
        Method method = ArmorConfig.class.getDeclaredMethod("defaultProfile");
        method.setAccessible(true);
        return (ArmorConfig.Profile) method.invoke(null);
    }

    private record Weapon(String id, String name, int caliber, String category, boolean packed) {
    }

    private static void report(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }

    private static void fail(String what) {
        report(false, what);
    }
}