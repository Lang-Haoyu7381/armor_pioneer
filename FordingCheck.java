import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ywzjvehicle.armor.ArmorConfig;
import com.ywzjvehicle.armor.Fording;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * Exercises the fording kit's decision offline, without a game and without a world.
 *
 * <p>What it can reach is the part of the feature that is wrong without saying so. {@link
 * Fording#verdict} is a pure function and is the whole of the judgement - whether the engine runs in
 * water, and whether the water is too deep - and every way it can be wrong is quiet:
 *
 * <ul>
 *   <li>A depth comparison that is inverted or off by one gives a tank that either wades through an
 *       ocean or stalls in a puddle, and both look exactly like a mixin that failed to apply.</li>
 *   <li>Water in the wrong order against the other tests - a lava check after the depth check, say -
 *       lets a kit protect an engine from lava, which is not a thing a snorkel can do.</li>
 *   <li>A message whose translation key is missing or misspelled puts {@code
 *       message.ywzj_vehicle_armor.fording_deep} on the screen instead of a sentence, and nothing
 *       anywhere reports it.</li>
 *   <li>A mixin class that is written, compiled, shipped - and not listed in the mixins json - is
 *       never applied at all. The feature simply does nothing, on every vehicle, for ever.</li>
 * </ul>
 *
 * <p>What it cannot reach is whether the mixin actually injects: that needs a real game, and is
 * guarded there instead, by {@code required: true} and {@code defaultRequire: 1} turning a failed
 * injection into a crash at load.
 *
 * <p>Usage: FordingCheck
 */
public class FordingCheck {

    private static int failures;

    /** Named in the mixins json, or the whole feature is a class nobody calls. */
    private static final String MIXIN = "AbstractVehicleFordingMixin";

    /** What {@code VehicleArmorMod} calls itself, which is where the message keys are built from. */
    private static final String MOD_ID = "ywzj_vehicle_armor";

    public static void main(String[] args) throws Exception {
        table();
        sweep();
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
                Fording.verdict(null, true, water(1.0F)) == Fording.Verdict.NO_KIT
                        && Fording.verdict(null, false, Fording.Probe.DRY) == Fording.Verdict.NO_KIT);
        check("a stowed kit protects nothing, even standing in water",
                Fording.verdict(kit, false, water(1.0F)) == Fording.Verdict.RETRACTED);
        check("a deployed kit on dry land has nothing to do",
                Fording.verdict(kit, true, Fording.Probe.DRY) == Fording.Verdict.DRY);
        check("water exactly as deep as the snorkel is water it crosses",
                Fording.verdict(kit, true, water(kit.max_depth)) == Fording.Verdict.WADING);
        check("a hundredth of a block more is too deep",
                Fording.verdict(kit, true, water(kit.max_depth + 0.01F))
                        == Fording.Verdict.TOO_DEEP);
        check("a puddle is not a fording",
                Fording.verdict(kit, true, water(0.0F)) == Fording.Verdict.WADING);
        // The one that would be easy to get wrong by ordering the tests badly: a kit must never
        // help in anything that is not water, however shallow it is.
        check("lava is not water, at any depth",
                Fording.verdict(kit, true, new Fording.Probe(true, false, 0.0F))
                        == Fording.Verdict.NOT_WATER
                        && Fording.verdict(kit, true, new Fording.Probe(true, false, 99.0F))
                                == Fording.Verdict.NOT_WATER);
        check("the limit is the vehicle's own, not a shared figure",
                Fording.verdict(shortKit, true, water(3.0F)) == Fording.Verdict.TOO_DEEP
                        && Fording.verdict(kit, true, water(3.0F)) == Fording.Verdict.WADING);
    }

    /**
     * The same question asked across the whole range rather than at two points.
     *
     * <p>Boundary cases catch an off-by-one; they do not catch a comparison written against the
     * wrong side entirely, or a limit read from the wrong place. Walking the range asserts the
     * relationship rather than two of its values.
     */
    private static void sweep() {
        System.out.println();
        System.out.println("=== the whole range of depths ===");
        Fording.Kit kit = new Fording.Kit();
        int mismatches = 0;
        float worst = -1.0F;
        for (int step = 0; step <= 120; step++) {
            float depth = step / 10.0F;
            boolean crosses = depth <= kit.max_depth;
            Fording.Verdict verdict = Fording.verdict(kit, true, water(depth));
            boolean runs = Fording.mayRun(verdict);
            if (runs != crosses) {
                mismatches++;
                worst = depth;
            }
        }
        check("0.0 .. 12.0 blocks: the engine runs exactly up to the limit", mismatches == 0);
        if (mismatches > 0) {
            System.out.println("       first depth that disagrees: " + worst);
        }
        System.out.println("  limit " + kit.max_depth + ", so 0.0-" + kit.max_depth
                + " wades and " + (kit.max_depth + 0.1F) + "+ does not");
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

    /** The id crosses a network, so the ends have to be answered rather than trusted. */
    private static void idsOffTheWire() {
        System.out.println();
        System.out.println("=== verdict ids off the wire ===");
        boolean roundTrips = true;
        for (Fording.Verdict verdict : Fording.Verdict.values()) {
            roundTrips &= Fording.Verdict.byId(verdict.ordinal()) == verdict;
        }
        check("every verdict round trips through its id", roundTrips);
        check("an id past the end falls back rather than throwing",
                Fording.Verdict.byId(Fording.Verdict.values().length) == Fording.Verdict.RETRACTED);
        check("a negative id falls back", Fording.Verdict.byId(-1) == Fording.Verdict.RETRACTED);
        check("a wild id falls back", Fording.Verdict.byId(9999) == Fording.Verdict.RETRACTED);
    }

    /** The default the T-64 is configured with, and the one the messages measure against. */
    private static void defaults() {
        System.out.println();
        System.out.println("=== the configured default ===");
        Fording.Kit kit = new Fording.Kit();
        check("a kit with nothing said about it reaches " + Fording.DEFAULT_MAX_DEPTH + " blocks",
                kit.max_depth == Fording.DEFAULT_MAX_DEPTH);
        check("which is the published five metres of an OPVT", kit.max_depth == 5.0F);
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
        for (Fording.Verdict verdict : Fording.Verdict.values()) {
            // Both ways round: the line for a vehicle whose model has a snorkel, and the one for a
            // pack that was never patched, which carries a second sentence after the first.
            keysIn(Fording.describe(verdict, 6.2F, 5.0F, true), keys);
            keysIn(Fording.describe(verdict, 6.2F, 5.0F, false), keys);
        }
        keysIn(Fording.shallowWarning(4.1F, 5.0F), keys);
        check("no message was built without a translation key", !keys.contains(null));
        check("the unpatched-pack note is one of them", keys.contains(
                "message." + MOD_ID + ".fording_no_model"));

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
        System.out.println("  " + keys.size() + " key(s): " + keys);
    }

    /** The mixin has to be listed, or the class ships and is never applied to anything. */
    private static void mixinListed() throws Exception {
        System.out.println();
        System.out.println("=== the mixin is registered ===");
        String json = resource(MOD_ID + ".mixins.json");
        check("the mixins config was found", json != null);
        if (json == null) {
            return;
        }
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Set<String> listed = new LinkedHashSet<>();
        root.getAsJsonArray("mixins").forEach(element -> listed.add(element.getAsString()));
        check(MIXIN + " is listed in " + MOD_ID + ".mixins.json", listed.contains(MIXIN));
        check("and the config is required, so a failed injection crashes instead of doing nothing",
                root.get("required").getAsBoolean()
                        && root.getAsJsonObject("injectors").get("defaultRequire").getAsInt() >= 1);
        System.out.println("  mixins: " + listed);
    }

    /**
     * The other half of the kit: the part, the bone, the animation and the pixels.
     *
     * <p>The addon's half is a boolean and a key. Whether anything is *seen* to happen is decided by
     * four things in the vehicle pack that have to agree with each other and with
     * {@link Fording#KIT_PART_ID}, and every way they can fail is silent:
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
        for (Path p : packs) {
            System.out.println("    " + p.getFileName());
        }

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
        System.out.println("  " + configured + " vehicle(s) carry a kit, on part id '"
                + Fording.KIT_PART_ID + "'");
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
            check("  the bone holds exactly one cube (the tube)",
                    bone.has("cubes") && bone.getAsJsonArray("cubes").size() == 1);

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
            for (var face : bone.getAsJsonArray("cubes").get(0).getAsJsonObject()
                    .getAsJsonObject("uv").entrySet()) {
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
            check("  every face of the tube is on the atlas", offAtlas == 0);
            check("  and every pixel of every face is opaque (otherwise it rises invisibly)",
                    transparent == 0);
        }
    }

    /** @return the packs to read: the ones named on the command line, or every pack installed. */
    private static List<Path> packs() throws Exception {
        List<Path> packs = new ArrayList<>();
        String root = System.getenv().getOrDefault("MC_ROOT", "E:\\.minecraft");
        Path dir = Path.of(root,
                "/versions/1.20.1-Forge_47.4.2/limitless_vehicle");
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".zip"))
                        .sorted()
                        .forEach(packs::add);
            }
        }
        return packs;
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
