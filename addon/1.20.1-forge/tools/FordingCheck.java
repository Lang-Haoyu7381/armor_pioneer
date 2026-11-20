import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ywzjvehicle.armor.ArmorConfig;
import com.ywzjvehicle.armor.Fording;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * Exercises the fording kit's decisions offline, without a game and without a world.
 *
 * <p>The feature has two halves that fail differently. The decision half is pure - {@link
 * Fording#verdict}, {@link Fording#step}, {@link Fording#percent} - and every way it can be wrong
 * is quiet: an inverted depth comparison wades through an ocean or stalls in a puddle, and both
 * look like a mixin that failed to apply. The model half lives in the vehicle pack, where a part, a
 * bone, an animation and a controller binding have to agree on one id; any one of them missing
 * leaves a kit that works and is never seen to move.
 *
 * <p>What it cannot reach is whether the mixin actually injects or whether the client renders the
 * kit: those need a real game, and are guarded there by {@code required: true} and {@code
 * defaultRequire: 1} turning a failed injection into a crash at load.
 *
 * <p>Usage: FordingCheck
 */
public class FordingCheck {

    private static int failures;

    /** Named in the mixins json, or the whole feature is a class nobody calls. */
    private static final String MIXIN = "AbstractVehicleFordingMixin";
    private static final String TURRET_MIXIN = "RotatableUnitFordingMixin";

    /** What {@code VehicleArmorMod} calls itself, which is where the message keys are built from. */
    private static final String MOD_ID = "ywzj_vehicle_armor";

    /** The seven Armor Pioneer vehicles the pack patch gives a snorkel to. */
    private static final String[] ARMOR_PIONEER = {
            "armor_pioneer:vt5", "armor_pioneer:vt4", "armor_pioneer:k2",
            "armor_pioneer:type10", "armor_pioneer:tianma20", "armor_pioneer:ztz100",
            "armor_pioneer:leopard2a4"
    };

    public static void main(String[] args) throws Exception {
        table();
        phases();
        timing();
        percentages();
        mayRun();
        idsOffTheWire();
        defaults();
        messages();
        mixinListed();
        pack();

        System.out.println();
        if (failures == 0) {
            System.out.println("RESULT: the fording kit decides what it says it decides");
        } else {
            System.out.println("RESULT: " + failures + " check(s) failed - see above");
            System.exit(1);
        }
    }

    /** The truth table, case by case, with the two boundaries on either side of the limit. */
    private static void table() {
        System.out.println("=== what the kit decides ===");
        Fording.Kit kit = new Fording.Kit();
        Fording.Kit shortKit = new Fording.Kit();
        shortKit.max_depth = 2.0F;

        check("no kit on the vehicle is no kit, whatever the water",
                Fording.verdict(null, Fording.Phase.READY, water(1.0F)) == Fording.Verdict.NO_KIT
                        && Fording.verdict(null, Fording.Phase.STOWED, Fording.Probe.DRY)
                                == Fording.Verdict.NO_KIT);
        check("a stowed kit protects nothing, even standing in water",
                Fording.verdict(kit, Fording.Phase.STOWED, water(1.0F))
                        == Fording.Verdict.RETRACTED);
        check("a kit still being fitted protects nothing either",
                Fording.verdict(kit, Fording.Phase.PREPARING, water(1.0F))
                        == Fording.Verdict.RETRACTED
                        && Fording.verdict(kit, Fording.Phase.STOWING, water(1.0F))
                                == Fording.Verdict.RETRACTED);
        check("a ready kit on dry land has nothing to do",
                Fording.verdict(kit, Fording.Phase.READY, Fording.Probe.DRY) == Fording.Verdict.DRY);
        check("water exactly as deep as the snorkel is water it crosses",
                Fording.verdict(kit, Fording.Phase.READY, water(kit.max_depth))
                        == Fording.Verdict.WADING);
        check("a hundredth of a block more is too deep",
                Fording.verdict(kit, Fording.Phase.READY, water(kit.max_depth + 0.01F))
                        == Fording.Verdict.TOO_DEEP);
        check("a puddle is not a fording",
                Fording.verdict(kit, Fording.Phase.READY, water(0.0F)) == Fording.Verdict.WADING);
        // The one that would be easy to get wrong by ordering the tests badly: a kit must never
        // help in anything that is not water, however deep.
        check("lava is not water, at any depth",
                Fording.verdict(kit, Fording.Phase.READY, new Fording.Probe(true, false, 0.0F))
                        == Fording.Verdict.NOT_WATER
                        && Fording.verdict(kit, Fording.Phase.READY,
                                new Fording.Probe(true, false, 99.0F)) == Fording.Verdict.NOT_WATER);
        check("the limit is the vehicle's own, not a shared figure",
                Fording.verdict(shortKit, Fording.Phase.READY, water(3.0F))
                        == Fording.Verdict.TOO_DEEP
                        && Fording.verdict(kit, Fording.Phase.READY, water(3.0F))
                                == Fording.Verdict.WADING);
    }

    /** The state machine: a phase is either ready or it is not, and only ready protects. */
    private static void phases() {
        System.out.println();
        System.out.println("=== the four phases ===");
        Fording.Kit kit = new Fording.Kit();
        Set<Fording.Phase> protecting = new LinkedHashSet<>();
        for (Fording.Phase phase : Fording.Phase.values()) {
            if (Fording.mayRun(Fording.verdict(kit, phase, water(1.0F)))) {
                protecting.add(phase);
            }
        }
        check("only READY protects the engine, and it does: " + protecting,
                protecting.equals(Set.of(Fording.Phase.READY)));
        boolean roundTrips = true;
        for (Fording.Phase phase : Fording.Phase.values()) {
            roundTrips &= Fording.Phase.byId(phase.ordinal()) == phase;
        }
        check("every phase round trips through its id", roundTrips);
        check("a wild phase id falls back to stowed",
                Fording.Phase.byId(-1) == Fording.Phase.STOWED
                        && Fording.Phase.byId(999) == Fording.Phase.STOWED);
    }

    /** The two times, their fallbacks, and where the three steps divide. */
    private static void timing() {
        System.out.println();
        System.out.println("=== preparation and stowing ===");
        Fording.Kit kit = new Fording.Kit();
        check("a fresh kit takes " + Fording.DEFAULT_PREP_TICKS + " ticks ("
                + Fording.DEFAULT_PREP_TICKS / 20 + " s) to fit",
                kit.prep_ticks == Fording.DEFAULT_PREP_TICKS
                        && kit.prep_ticks == 600);
        check("and " + Fording.DEFAULT_STOW_TICKS / 20 + " s to take down",
                kit.stow_ticks == Fording.DEFAULT_STOW_TICKS && kit.stow_ticks == 200);

        Fording.Kit zero = new Fording.Kit();
        zero.prep_ticks = 0;
        zero.stow_ticks = 0;
        check("a config with no times falls back rather than finishing instantly",
                Fording.prepTicks(zero) == Fording.DEFAULT_PREP_TICKS
                        && Fording.stowTicks(zero) == Fording.DEFAULT_STOW_TICKS);
        Fording.Kit tiny = new Fording.Kit();
        tiny.prep_ticks = 3;
        tiny.stow_ticks = 3;
        check("and so does a time too short to be a procedure",
                Fording.prepTicks(tiny) == Fording.DEFAULT_PREP_TICKS
                        && Fording.stowTicks(tiny) == Fording.DEFAULT_STOW_TICKS);
        check("a hand-set time is kept",
                Fording.prepTicks(withPrep(90)) == 90 && Fording.stowTicks(withStow(30)) == 30);

        check("the seal is 40% of the fitting", Fording.SEAL_PERCENT == 40);
        check("the tube and checks split the rest at 80%", Fording.SNORKEL_PERCENT == 80);
        check("sealing ends exactly at the seal boundary",
                !Fording.sealComplete(39) && Fording.sealComplete(40)
                        && Fording.sealComplete(100));
    }

    private static void percentages() {
        System.out.println();
        System.out.println("=== progress and steps ===");
        Fording.Kit kit = new Fording.Kit();
        check("stowed is 0% and ready is 100%",
                Fording.percent(Fording.Phase.STOWED, 0, kit) == 0
                        && Fording.percent(Fording.Phase.READY, 0, kit) == 100);
        int mismatches = 0;
        for (int step = 0; step <= 100; step++) {
            int percent = Fording.percent(Fording.Phase.PREPARING,
                    step * Fording.prepTicks(kit) / 100, kit);
            if (percent != step) {
                mismatches++;
            }
        }
        check("the percentage is the elapsed share of the configured time", mismatches == 0);
        check("0 and 39 are sealing",
                Fording.step(Fording.Phase.PREPARING, 0) == Fording.Step.SEAL
                        && Fording.step(Fording.Phase.PREPARING, 39) == Fording.Step.SEAL);
        check("40 to 79 is the snorkel",
                Fording.step(Fording.Phase.PREPARING, 40) == Fording.Step.SNORKEL
                        && Fording.step(Fording.Phase.PREPARING, 79) == Fording.Step.SNORKEL);
        check("80 to 100 is the check",
                Fording.step(Fording.Phase.PREPARING, 80) == Fording.Step.CHECK
                        && Fording.step(Fording.Phase.PREPARING, 100) == Fording.Step.CHECK);
        check("stowing has one step and the other phases have none",
                Fording.step(Fording.Phase.STOWING, 50) == Fording.Step.STOW
                        && Fording.step(Fording.Phase.READY, 50) == Fording.Step.NONE
                        && Fording.step(Fording.Phase.STOWED, 50) == Fording.Step.NONE);
    }

    /** Every verdict's answer to "may the engine run", including the two that must never be yes. */
    private static void mayRun() {
        System.out.println();
        System.out.println("=== which verdicts let the engine run ===");
        Set<Fording.Verdict> runs = new LinkedHashSet<>();
        for (Fording.Verdict verdict : Fording.Verdict.values()) {
            if (Fording.mayRun(verdict)) {
                runs.add(verdict);
            }
        }
        check("only wading and dry run, and both do: " + runs,
                runs.equals(Set.of(Fording.Verdict.WADING, Fording.Verdict.DRY)));
    }

    /** The ids cross a network, so the ends have to be answered rather than trusted. */
    private static void idsOffTheWire() {
        System.out.println();
        System.out.println("=== ids off the wire ===");
        boolean roundTrips = true;
        for (Fording.Verdict verdict : Fording.Verdict.values()) {
            roundTrips &= Fording.Verdict.byId(verdict.ordinal()) == verdict;
        }
        for (Fording.Note note : Fording.Note.values()) {
            roundTrips &= Fording.Note.byId(note.ordinal()) == note;
        }
        check("every verdict and note round trips through its id", roundTrips);
        check("an id past the end falls back rather than throwing",
                Fording.Verdict.byId(Fording.Verdict.values().length)
                        == Fording.Verdict.RETRACTED
                        && Fording.Note.byId(Fording.Note.values().length) == Fording.Note.NONE);
        check("a negative id falls back",
                Fording.Verdict.byId(-1) == Fording.Verdict.RETRACTED
                        && Fording.Note.byId(-1) == Fording.Note.NONE);
    }

    /** The defaults the T-64 and the seven Armor Pioneer vehicles are configured with. */
    private static void defaults() {
        System.out.println();
        System.out.println("=== the configured default ===");
        Fording.Kit kit = new Fording.Kit();
        check("a kit with nothing said about it reaches " + Fording.DEFAULT_MAX_DEPTH + " blocks",
                kit.max_depth == Fording.DEFAULT_MAX_DEPTH);
        check("which is the published five metres of an OPVT", kit.max_depth == 5.0F);
        check("the fitting time is the compressed 30 seconds", kit.prep_ticks == 600);
        check("and taking it down is quicker", kit.stow_ticks < kit.prep_ticks);
    }

    /**
     * Every line the player can be shown has to exist in both languages.
     *
     * <p>The keys are taken off the components the mod itself builds - the same objects the game
     * looks up - rather than from a list written out here, because a list here is a second copy of
     * the mapping and would go stale the first time a message was renamed.
     */
    private static void messages() {
        System.out.println();
        System.out.println("=== every line has words in both languages ===");
        Set<String> keys = new LinkedHashSet<>();
        Fording.Kit kit = new Fording.Kit();

        // every verdict, in both the patched-model and unpatched-model forms
        for (Fording.Verdict verdict : Fording.Verdict.values()) {
            for (boolean hasSnorkel : new boolean[]{true, false}) {
                keysIn(Fording.describe(report(verdict, Fording.Note.NONE,
                        Fording.Phase.READY, 0, 6.2F, 5.0F, true, hasSnorkel)), keys);
            }
        }
        // every note
        for (Fording.Note note : Fording.Note.values()) {
            if (note == Fording.Note.STAGE) {
                continue;
            }
            keysIn(Fording.describe(report(Fording.Verdict.RETRACTED, note,
                    Fording.Phase.STOWED, 0, 0.0F, 5.0F, false, true)), keys);
        }
        // every step, including the lock line the two middle steps carry
        keysIn(Fording.describe(report(Fording.Verdict.RETRACTED, Fording.Note.STAGE,
                Fording.Phase.PREPARING, 10, 0.0F, 5.0F, false, true)), keys);
        keysIn(Fording.describe(report(Fording.Verdict.RETRACTED, Fording.Note.STAGE,
                Fording.Phase.PREPARING, 50, 0.0F, 5.0F, false, true)), keys);
        keysIn(Fording.describe(report(Fording.Verdict.RETRACTED, Fording.Note.STAGE,
                Fording.Phase.PREPARING, 90, 0.0F, 5.0F, false, true)), keys);
        keysIn(Fording.describe(report(Fording.Verdict.RETRACTED, Fording.Note.STAGE,
                Fording.Phase.STOWING, 50, 0.0F, 5.0F, false, true)), keys);
        keysIn(Fording.shallowWarning(4.1F, 5.0F), keys);

        check("no message was built without a translation key", !keys.contains(null));
        check("the unpatched-pack note is one of them", keys.contains(
                "message." + MOD_ID + ".fording_no_model"));
        check("the turret lock line is one of them", keys.contains(
                "message." + MOD_ID + ".fording_turret_locked"));

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
        System.out.println("  " + keys.size() + " key(s)");
    }

    /** The mixins have to be listed, or the classes ship and are never applied to anything. */
    private static void mixinListed() throws Exception {
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
        check(MIXIN + " is listed in " + MOD_ID + ".mixins.json", listed.contains(MIXIN));
        check(TURRET_MIXIN + " is listed too", listed.contains(TURRET_MIXIN));
        check("and the config is required, so a failed injection crashes instead of doing nothing",
                root.get("required").getAsBoolean()
                        && root.getAsJsonObject("injectors").get("defaultRequire").getAsInt() >= 1);
        System.out.println("  mixins: " + listed);
    }

    /**
     * The other half of the kit: the part, the bone, the animation and the pixels, in the pack.
     *
     * <p>Every way this can fail is silent:
     *
     * <ul>
     *   <li>no part on the vehicle - the kit works and nothing moves, on every press;</li>
     *   <li>a part but no bone in the render model - the part toggles and there is nothing to move;</li>
     *   <li>a bone but no animation naming it - the baker welds the bone into its parent's static
     *       mesh, so it is there and can never move;</li>
     *   <li>cube faces pointing at transparent pixels - the snorkel rises, invisibly.</li>
     * </ul>
     *
     * <p>Read from the pack the way the base mod reads it: the display file names the model, the
     * animation and the controller, so everything else follows from the vehicle id.
     */
    private static void pack() throws Exception {
        System.out.println();
        System.out.println("=== the snorkel in the vehicle pack ===");
        List<Path> packs = packs();
        System.out.println("  packs: " + packs.size());

        Method defaultProfile = ArmorConfig.class.getDeclaredMethod("defaultProfile");
        defaultProfile.setAccessible(true);
        ArmorConfig.Profile profile = (ArmorConfig.Profile) defaultProfile.invoke(null);

        int configured = 0;
        for (var entry : profile.vehicles.entrySet()) {
            if (entry.getValue().fording == null) {
                continue;
            }
            configured++;
            checkVehicle(entry.getKey(), packs);
        }
        check("some vehicle is configured with a kit at all", configured > 0);
        check("and it is at least the T-64 plus the seven Armor Pioneer vehicles",
                configured >= 1 + ARMOR_PIONEER.length);
        for (String id : ARMOR_PIONEER) {
            ArmorConfig.VehicleArmor armor = profile.vehicles.get(id);
            check("  " + id + " has a kit configured", armor != null && armor.fording != null);
        }
        System.out.println("  " + configured + " vehicle(s) carry a kit, on part id '"
                + Fording.KIT_PART_ID + "'");
        packLang(packs);
    }

    /** Everything the pack has to say about one vehicle's snorkel, checked together. */
    private static void checkVehicle(String vehicleId, List<Path> packs) throws Exception {
        String namespace = vehicleId.substring(0, vehicleId.indexOf(':'));
        String name = vehicleId.substring(vehicleId.indexOf(':') + 1);
        System.out.println("  " + vehicleId + ":");

        try (ZipFile pack = open(packs, "assets/" + namespace + "/display/vehicle/" + name + ".json")) {
            if (pack == null) {
                check("  its vehicle pack is installed", false);
                return;
            }
            JsonObject display = JsonParser.parseString(read(pack, "assets/" + namespace
                    + "/display/vehicle/" + name + ".json")).getAsJsonObject();

            // The part: a switchable with no structure bone, which is what makes it something the
            // base mod syncs and animates rather than something that can be shot.
            JsonObject vehicle = JsonParser.parseString(read(pack, "data/" + namespace + "/vehicles/"
                    + name + ".json")).getAsJsonObject();
            JsonObject part = null;
            for (var element : vehicle.getAsJsonArray("parts")) {
                JsonObject candidate = element.getAsJsonObject();
                if (Fording.KIT_PART_ID.equals(candidate.get("id").getAsString())) {
                    part = candidate;
                }
            }
            check("  the vehicle has a '" + Fording.KIT_PART_ID + "' part", part != null);
            if (part == null) {
                return;
            }
            check("  it is a switchable, so the base mod syncs and animates it",
                    "ywzj_vehicle:switchable".equals(part.get("type").getAsString()));
            check("  it is not a seat", part.has("is_seat") && !part.get("is_seat").getAsBoolean());
            check("  it has no structure_bone, so it adds no hitbox",
                    !part.has("structure_bone"));

            // The bone, in the model the display names.
            String modelPath = resourcePath(display.get("model").getAsString(), "models/bedrock");
            JsonObject model = JsonParser.parseString(read(pack, modelPath)).getAsJsonObject();
            JsonObject bone = null;
            for (var element : model.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject()
                    .getAsJsonArray("bones")) {
                JsonObject candidate = element.getAsJsonObject();
                if (Fording.KIT_PART_ID.equals(candidate.get("name").getAsString())) {
                    bone = candidate;
                }
            }
            check("  the render model has a '" + Fording.KIT_PART_ID + "' bone", bone != null);
            if (bone == null) {
                return;
            }
            check("  the part points at that bone",
                    part.has("render_bone")
                            && Fording.KIT_PART_ID.equals(part.get("render_bone").getAsString()));
            check("  the bone is built from at least one cube",
                    bone.has("cubes") && bone.getAsJsonArray("cubes").size() >= 1);

            // The animation: named by the controller, and it has to name the bone, or the baker
            // folds the bone into its parent and no keypress can ever move it.
            JsonObject controller = JsonParser.parseString(read(pack,
                    resourcePath(display.get("animation_controller").getAsString(),
                            "animation_controllers"))).getAsJsonObject();
            JsonObject switchable = controller.has("switchable_animations")
                    ? controller.getAsJsonObject("switchable_animations")
                            .getAsJsonObject(Fording.KIT_PART_ID)
                    : null;
            check("  the controller binds the part to an animation", switchable != null);
            if (switchable == null) {
                return;
            }
            String animation = switchable.get("animation").getAsString();
            check("  and that binding names this part as its part_id",
                    Fording.KIT_PART_ID.equals(switchable.get("part_id").getAsString()));
            check("  the pose graph merges it",
                    controller.getAsJsonObject("graph").toString().contains("\"" + Fording.KIT_PART_ID + "\""));

            JsonObject animations = JsonParser.parseString(read(pack,
                    resourcePath(display.get("animations").getAsString(), "animations/bedrock")))
                    .getAsJsonObject().getAsJsonObject("animations");
            check("  '" + animation + "' exists in the animation file",
                    animations.has(animation));
            if (!animations.has(animation)) {
                return;
            }
            JsonObject bones = animations.getAsJsonObject(animation).getAsJsonObject("bones");
            check("  it animates the bone (without this the bone can never move)",
                    bones.has(Fording.KIT_PART_ID));

            // The pixels: the faces have to land on atlas that is actually there and actually
            // painted, or the snorkel rises without being seen.
            // A texture id is a whole path, extension and all - unlike a model or an animation, which
            // name a file under a directory the base mod decides on.
            String textureId = display.get("texture").getAsString();
            String texturePath = "assets/" + textureId.substring(0, textureId.indexOf(':')) + "/"
                    + textureId.substring(textureId.indexOf(':') + 1);
            BufferedImage texture = ImageIO.read(pack.getInputStream(pack.getEntry(texturePath)));
            check("  the texture loads", texture != null);
            if (texture == null) {
                return;
            }
            int transparent = 0;
            int offAtlas = 0;
            for (var cube : bone.getAsJsonArray("cubes")) {
                for (var face : cube.getAsJsonObject().getAsJsonObject("uv").entrySet()) {
                    JsonObject box = face.getValue().getAsJsonObject();
                    int u = box.getAsJsonArray("uv").get(0).getAsInt();
                    int v = box.getAsJsonArray("uv").get(1).getAsInt();
                    int w = Math.abs(box.getAsJsonArray("uv_size").get(0).getAsInt());
                    int h = Math.abs(box.getAsJsonArray("uv_size").get(1).getAsInt());
                    for (int y = v; y < v + h; y++) {
                        for (int x = u; x < u + w; x++) {
                            if (x < 0 || y < 0 || x >= texture.getWidth() || y >= texture.getHeight()) {
                                offAtlas++;
                            } else if ((texture.getRGB(x, y) >>> 24) == 0) {
                                transparent++;
                            }
                        }
                    }
                }
            }
            check("  every face of the tube is on the atlas", offAtlas == 0);
            check("  and every pixel of every face is opaque (otherwise it rises invisibly)",
                    transparent == 0);
        }
    }

    /** The pack's own lang entry, so the part has a name rather than an id in the part list. */
    private static void packLang(List<Path> packs) throws Exception {
        try (ZipFile pack = open(packs, "assets/armor_pioneer/lang/zh_cn.json")) {
            if (pack == null) {
                return;
            }
            for (String lang : new String[]{"zh_cn", "en_us"}) {
                JsonObject file = JsonParser.parseString(read(pack,
                        "assets/armor_pioneer/lang/" + lang + ".json")).getAsJsonObject();
                check("  pack " + lang + " names part.armor_pioneer.snorkel",
                        file.has("part.armor_pioneer.snorkel"));
            }
        }
    }

    /** @return the packs to read: every pack installed, in either the zip or the folder shape. */
    private static List<Path> packs() throws Exception {
        return PackLocator.allPackZips();
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

    /** Reads a pack entry as text. */
    private static String read(ZipFile pack, String entry) throws Exception {
        try (InputStream in = pack.getInputStream(pack.getEntry(entry))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Turns a resource id the display file names into a path in the pack.
     *
     * <p>The base mod looks models, animations and controllers up under fixed directories, so this is
     * the same mapping it does: namespace to a folder, and the id's own path appended under it.
     */
    private static String resourcePath(String id, String directory) {
        String namespace = id.substring(0, id.indexOf(':'));
        String path = id.substring(id.indexOf(':') + 1);
        return "assets/" + namespace + "/" + directory + "/" + path + ".json";
    }

    private static Fording.Report report(Fording.Verdict verdict, Fording.Note note,
                                         Fording.Phase phase, int percent, float depth,
                                         float maxDepth, boolean seconds, boolean hasSnorkel) {
        return new Fording.Report(verdict, note, phase, percent, depth, maxDepth,
                seconds ? Fording.DEFAULT_PREP_TICKS / 20 : 0, hasSnorkel);
    }

    private static Fording.Kit withPrep(int ticks) {
        Fording.Kit kit = new Fording.Kit();
        kit.prep_ticks = ticks;
        return kit;
    }

    private static Fording.Kit withStow(int ticks) {
        Fording.Kit kit = new Fording.Kit();
        kit.stow_ticks = ticks;
        return kit;
    }

    /** @return a probe standing in water of this depth. */
    private static Fording.Probe water(float depth) {
        return new Fording.Probe(true, true, depth);
    }

    /**
     * Collects the translation keys in a component the mod built, and in everything appended to it.
     *
     * <p>The whole tree rather than the root, because a message can be several sentences: the reply
     * that says the kit is deployed and that this pack has no snorkel to deploy is one component with
     * a sibling, and reading only the root would check half of it.
     */
    private static void keysIn(net.minecraft.network.chat.Component component, Set<String> into) {
        if (component == null) {
            return;
        }
        String own = keyOf(component);
        if (own != null) {
            into.add(own);
        }
        for (net.minecraft.network.chat.Component sibling : component.m_7360_()) {
            keysIn(sibling, into);
        }
    }

    /**
     * @return the translation key of a component the mod built, or {@code null} if it is not a
     *         translatable one - which for these messages would itself be the bug.
     */
    private static String keyOf(net.minecraft.network.chat.Component component) {
        if (component == null) {
            return null;
        }
        return component.m_214077_() instanceof net.minecraft.network.chat.contents.TranslatableContents
                translated ? translated.m_237508_() : null;
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
        try (InputStream in = FordingCheck.class.getClassLoader().getResourceAsStream(path)) {
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
