import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ywzjvehicle.armor.BlastScale;
import com.ywzjvehicle.armor.MuzzleSelect;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.ywzj.vehicle.vehicle.pojo.AimContext;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Checks the cannon effects as data, without starting a game.
 *
 * <p>An effect fails quietly by nature. A missing translation shows the key instead of the sound
 * name; a missing Ogg is a shot with no report; a particle JSON pointing at a texture that was
 * never written is a flash with nothing to draw; and a redirect whose descriptor no longer matches
 * the base mod does not fail until the class is transformed, which is at launch. None of these is
 * visible in the source. So this tool holds the pieces to each other:
 *
 * <ul>
 *   <li>every registered sound event has a {@code sounds.json} entry, and that entry's Ogg exists,
 *       is an Ogg, and is not empty;</li>
 *   <li>every particle type has a definition and every texture it names exists and is a square
 *       PNG;</li>
 *   <li>every subtitle key is translated in both languages;</li>
 *   <li>the client wiring names the effect class and all three particle types;</li>
 *   <li>the redirect's exact descriptor is in the compiled mixin <em>and</em> in the base mod class
 *       it will be applied to.</li>
 * </ul>
 *
 * <p>The last one is the only check here that can stop a crash rather than a silence. The target
 * is a string in an annotation; {@code javac} never resolves it, so a typo survives the build and
 * Mixin reports it at class-load time. Comparing the string against the base mod's bytecode is the
 * closest this can get without a running Mixin.
 *
 * <p>Finally it plants two defects in memory and requires the same validators to reject them. A
 * checker that can only ever print "ok" is worse than none.
 *
 * <p>Usage: EffectsCheck
 */
public class EffectsCheck {

    private static final String MOD = "ywzj_vehicle_armor";

    private static final List<String> SOUND_EVENTS = List.of(
            "cannon_light", "cannon_medium", "cannon_heavy", "cannon_artillery",
            "autocannon", "projectile_crack", "cannon_echo", "breech_clank");

    private static final List<String> PARTICLE_TYPES = List.of(
            "muzzle_flash_small", "muzzle_flash", "muzzle_flash_large",
            "muzzle_flash_jet", "muzzle_flash_spark");

    /** The exact sprite set each particle type is allowed to name. */
    private static List<String> expectedTextures(String type) {
        return switch (type) {
            case "muzzle_flash_small", "muzzle_flash", "muzzle_flash_large" ->
                    List.of("muzzle_flash_0", "muzzle_flash_1", "muzzle_flash_2", "muzzle_flash_3");
            case "muzzle_flash_jet" -> List.of("muzzle_jet_0", "muzzle_jet_1", "muzzle_jet_2");
            case "muzzle_flash_spark" -> List.of("muzzle_spark_0");
            default -> List.of();
        };
    }

    /**
     * The exact invocation the client mixin redirects - the one call to playSound, which 0.6.2.1
     * moved out of {@code onClientFire} into {@code playFireSounds}.
     */
    private static final String PLAY_SOUND_DESCRIPTOR =
            "Lnet/minecraft/world/level/Level;m_6263_(Lnet/minecraft/world/entity/player/Player;DDD"
                    + "Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V";

    private static int failures;
    private static int checks;

    public static void main(String[] args) {
        JsonObject sounds = sounds();
        particles();
        language(sounds);
        wiring();
        mixinTarget();
        muzzleSelection();
        blastScale();
        negativeTest(sounds);

        System.out.println();
        if (failures > 0) {
            System.out.println("RESULT: " + failures + " effect check(s) failed");
            System.exit(1);
        }
        System.out.println("RESULT: all " + checks + " effect check(s) passed");
    }

    // --- sounds ----------------------------------------------------------------------------

    private static JsonObject sounds() {
        System.out.println();
        System.out.println("=== the reports and their files ===");
        JsonObject json = resourceJson("assets/" + MOD + "/sounds.json");
        if (json == null) {
            return new JsonObject();
        }

        List<String> problems = validateSounds(json);
        for (String problem : problems) {
            check(false, problem);
        }
        if (problems.isEmpty()) {
            check(true, "all " + SOUND_EVENTS.size() + " sound events have a definition and a playable Ogg");
        }

        // Extra events are not harmful, but they are dead weight and usually mean a rename was
        // half done. The set is small and fixed, so exact is the right comparison.
        Set<String> extra = new LinkedHashSet<>(json.keySet());
        extra.removeAll(SOUND_EVENTS);
        check(extra.isEmpty(), "no sound event is defined that the code never plays" + (extra.isEmpty() ? "" : " - " + extra));
        return json;
    }

    private static List<String> validateSounds(JsonObject json) {
        List<String> problems = new ArrayList<>();
        for (String name : SOUND_EVENTS) {
            JsonObject entry = json.has(name) && json.get(name).isJsonObject()
                    ? json.getAsJsonObject(name) : null;
            if (entry == null) {
                problems.add(name + " has no sounds.json entry");
                continue;
            }
            if (!entry.has("subtitle") || entry.get("subtitle").getAsString().isBlank()) {
                problems.add(name + " has no subtitle key");
            }
            JsonArray sounds = entry.has("sounds") && entry.get("sounds").isJsonArray()
                    ? entry.getAsJsonArray("sounds") : null;
            if (sounds == null || sounds.isEmpty()) {
                problems.add(name + " has no sound file");
                continue;
            }
            // The reports have two takes so a burst does not repeat one waveform; the echo and the
            // clank are single layers, where a repeat would be more obvious than the variety buys.
            int wanted = name.equals("cannon_echo") || name.equals("breech_clank") ? 1 : 2;
            if (sounds.size() < wanted) {
                problems.add(name + " has " + sounds.size() + " take(s), wanted " + wanted);
            }
            for (JsonElement element : sounds) {
                String reference = element.isJsonObject()
                        ? element.getAsJsonObject().get("name").getAsString()
                        : element.getAsString();
                int colon = reference.indexOf(':');
                if (colon <= 0 || !reference.substring(0, colon).equals(MOD)) {
                    problems.add(name + " points outside the addon: " + reference);
                    continue;
                }
                String path = "assets/" + MOD + "/sounds/" + reference.substring(colon + 1) + ".ogg";
                byte[] bytes = resourceBytes(path);
                if (bytes == null) {
                    problems.add(name + " names " + path + ", which is not in the jar");
                } else if (!isOgg(bytes)) {
                    problems.add(path + " is not an Ogg file");
                } else if (bytes.length < 4096) {
                    problems.add(path + " is " + bytes.length + " bytes - almost certainly truncated");
                }
            }
        }
        return problems;
    }

    // --- particles -------------------------------------------------------------------------

    private static void particles() {
        System.out.println();
        System.out.println("=== the flash and its textures ===");

        for (String type : PARTICLE_TYPES) {
            JsonObject definition = resourceJson("assets/" + MOD + "/particles/" + type + ".json");
            check(definition != null, type + " has a particle definition");
            if (definition == null) {
                continue;
            }
            List<String> expected = expectedTextures(type);
            JsonArray textures = definition.has("textures")
                    ? definition.getAsJsonArray("textures") : new JsonArray();
            check(textures.size() == expected.size(),
                    type + " names " + expected.size() + " frames"
                            + (textures.size() == expected.size() ? "" : " - found " + textures.size()));

            Set<String> names = new LinkedHashSet<>();
            for (JsonElement element : textures) {
                String reference = element.getAsString();
                int colon = reference.indexOf(':');
                String local = colon >= 0 ? reference.substring(colon + 1) : reference;
                names.add(local);
                String path = "assets/" + MOD + "/textures/particle/" + local + ".png";
                byte[] bytes = resourceBytes(path);
                if (bytes == null) {
                    check(false, type + " names " + path + ", which is not in the jar");
                } else if (!isPng(bytes)) {
                    check(false, path + " is not a PNG file");
                } else {
                    int width = pngInt(bytes, 16);
                    int height = pngInt(bytes, 20);
                    check(width == height && width >= 16,
                            path + " is a square sprite (" + width + "x" + height + ")");
                }
            }
            check(names.equals(new LinkedHashSet<>(expected)),
                    type + " names exactly its own sprite set");
        }
    }

    // --- language --------------------------------------------------------------------------

    private static void language(JsonObject sounds) {
        System.out.println();
        System.out.println("=== what the player is told ===");
        JsonObject zh = resourceJson("assets/" + MOD + "/lang/zh_cn.json");
        JsonObject en = resourceJson("assets/" + MOD + "/lang/en_us.json");
        if (zh == null || en == null) {
            return;
        }
        for (String name : SOUND_EVENTS) {
            String key = "subtitles." + MOD + "." + name;
            check(translated(zh, key), key + " is translated in zh_cn");
            check(translated(en, key), key + " is translated in en_us");
        }
    }

    private static boolean translated(JsonObject language, String key) {
        return language.has(key) && !language.get(key).getAsString().isBlank()
                && !language.get(key).getAsString().equals(key);
    }

    // --- wiring ----------------------------------------------------------------------------

    private static void wiring() {
        System.out.println();
        System.out.println("=== the code that puts it together ===");
        String javaRoot = "src/main/java/com/ywzjvehicle/armor/";
        String soundSource = readSource(javaRoot + "ArmorSounds.java");
        String particleSource = readSource(javaRoot + "ArmorParticles.java");
        String clientSource = readSource(javaRoot + "ClientSetup.java");
        String weaponSource = readSource(javaRoot + "WeaponEffects.java");
        String fireMixinSource = readSource(javaRoot + "mixin/AbstractVehicleWeaponFireMixin.java");
        String mixinJson = readSource("src/main/resources/" + MOD + ".mixins.json");
        if (soundSource == null || particleSource == null || clientSource == null || mixinJson == null) {
            return;
        }

        for (String name : SOUND_EVENTS) {
            check(soundSource.contains("register(\"" + name + "\")"),
                    "ArmorSounds registers " + name);
        }
        for (String type : PARTICLE_TYPES) {
            check(particleSource.contains("\"" + type + "\""),
                    "ArmorParticles registers " + type);
        }
        check(clientSource.contains("MinecraftForge.EVENT_BUS.register(WeaponEffects.class)"),
                "ClientSetup subscribes WeaponEffects");
        check(clientSource.contains("MinecraftForge.EVENT_BUS.register(ProjectileCrack.class)"),
                "ClientSetup subscribes ProjectileCrack");
        // The report, the flash, the smoke and the echo are the addon's only on the pack they
        // were built for. Every other pack ships its own per-gun recordings, and this gate is one
        // constant and one comparison - exactly the shape that can be deleted with no symptom in
        // any test that only counts sounds.
        check(weaponSource != null && weaponSource.contains("PACK_NAMESPACE")
                        && weaponSource.contains("\"armor_pioneer\""),
                "WeaponEffects takes a shot over only on the Armor Pioneer pack");
        check(fireMixinSource != null && fireMixinSource.contains("WeaponEffects.handles(self)")
                        && fireMixinSource.contains("ywzjarmor$baseFiringContexts"),
                "the fire mixin hands a foreign pack's shot back to the base mod");
        for (String type : PARTICLE_TYPES) {
            check(clientSource.contains("ArmorParticles." + type.toUpperCase() + ".get()"),
                    "ClientSetup has a provider for " + type);
        }
        try {
            JsonObject config = JsonParser.parseString(mixinJson).getAsJsonObject();
            Set<String> common = names(config, "mixins");
            Set<String> client = names(config, "client");
            check(client.contains("AbstractVehicleWeaponFireMixin"),
                    "the fire-sound mixin is listed under client");
            check(!common.contains("AbstractVehicleWeaponFireMixin"),
                    "the fire-sound mixin is not also listed as common");
            check(client.contains("BulletEntitySonicBoomMixin"),
                    "the projectile-crack mixin is listed under client");
            check(!common.contains("BulletEntitySonicBoomMixin"),
                    "the projectile-crack mixin is not also listed as common");
            check(client.contains("ParticleEngineAtlasAccessor"),
                    "the particle-atlas accessor is listed under client");
            check(!common.contains("ParticleEngineAtlasAccessor"),
                    "the particle-atlas accessor is not also listed as common");
        } catch (Exception e) {
            check(false, "the mixin configuration did not parse: " + e);
        }

        check(resourceBytes("com/ywzjvehicle/armor/WeaponEffects.class") != null,
                "WeaponEffects is in the compiled output");
        check(resourceBytes("com/ywzjvehicle/armor/ArmorSounds.class") != null,
                "ArmorSounds is in the compiled output");
        check(resourceBytes("com/ywzjvehicle/armor/client/MuzzleFlashParticle.class") != null,
                "MuzzleFlashParticle is in the compiled output");
        check(resourceBytes("com/ywzjvehicle/armor/ProjectileCrack.class") != null,
                "ProjectileCrack is in the compiled output");
    }

    // --- the one check that can prevent a launch failure ------------------------------------

    private static void mixinTarget() {
        System.out.println();
        System.out.println("=== the mixin targets ===");
        byte[] mixin = resourceBytes("com/ywzjvehicle/armor/mixin/AbstractVehicleWeaponFireMixin.class");
        if (mixin == null) {
            check(false, "the fire-sound mixin is in the compiled output");
            return;
        }
        check(contains(mixin, "onClientFire"), "the mixin names onClientFire");
        check(contains(mixin, "playFireSounds"),
                "the mixin redirects the sound call inside playFireSounds");
        check(contains(mixin, PLAY_SOUND_DESCRIPTOR), "the mixin carries the SRG playSound descriptor");
        // 0.6.2.1 moved the two aimContext calls into getFiringAimContexts(). The mixin wraps the
        // one call onClientFire makes to that method, rather than redirecting inside it - the same
        // method also builds the shot contexts doClientShoot sends to the server.
        check(contains(mixin, "getFiringAimContexts"),
                "the mixin wraps getFiringAimContexts in onClientFire");

        Path baseJar = baseModJar();
        if (baseJar == null) {
            check(false, "the base vehicle mod jar could not be located on the classpath");
            return;
        }
        byte[] target = zipEntry(baseJar, "org/ywzj/vehicle/vehicle/weapon/AbstractVehicleWeapon.class");
        check(target != null, "the target class is in " + baseJar.getFileName());
        if (target != null) {
            // A class file stores the owner, the method name and its descriptor as three separate
            // constant-pool entries, so the annotation's single concatenated string is not there
            // as such. The three parts are, and together they name the same call.
            check(contains(target, "net/minecraft/world/level/Level")
                            && contains(target, "m_6263_")
                            && contains(target, "(Lnet/minecraft/world/entity/player/Player;DDD"
                            + "Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V"),
                    "the base weapon class really contains the owner, name and descriptor of that call");
            check(contains(target, "playFireSounds"),
                    "the base weapon class really has playFireSounds");
            check(contains(target, "getFiringAimContexts")
                            && contains(target, "()Ljava/util/List;"),
                    "the base weapon class really has getFiringAimContexts");
        }

        byte[] sonicMixin = resourceBytes(
                "com/ywzjvehicle/armor/mixin/BulletEntitySonicBoomMixin.class");
        check(sonicMixin != null && contains(sonicMixin, "tickMove"),
                "the projectile-crack mixin names tickMove and is in the compiled output");
        byte[] bullet = zipEntry(baseJar, "org/ywzj/vehicle/entity/weapon/BulletEntity.class");
        check(bullet != null, "the shell class is in " + baseJar.getFileName());
        if (bullet != null) {
            check(contains(bullet, "tickMove"),
                    "the base shell class really has tickMove");
            check(contains(bullet, "getCaliber"),
                    "the base shell class really exposes getCaliber");
        }

        byte[] unitClass = zipEntry(baseJar, "org/ywzj/vehicle/vehicle/part/WeaponUnit.class");
        check(unitClass != null && contains(unitClass, "aimContexts"),
                "the base WeaponUnit really exposes aimContexts");
        check(unitClass != null && contains(unitClass, "aimContext"),
                "the base WeaponUnit really exposes aimContext");

        // The particle atlas is reached through an accessor, so its field name is a string that
        // javac never resolves. If the field moves, the flash vanishes again with only a render
        // thread exception to show for it - the same failure this check was added after.
        byte[] accessor = resourceBytes(
                "com/ywzjvehicle/armor/mixin/ParticleEngineAtlasAccessor.class");
        check(accessor != null && contains(accessor, "f_107296_"),
                "the particle-atlas accessor is compiled and names f_107296_");
        // These three are Minecraft classes, so they live in the SRG jar, not in the base mod jar.
        Path srgJar = srgJar();
        if (srgJar == null) {
            check(false, "the Minecraft SRG jar could not be located on the classpath");
            return;
        }
        byte[] engine = zipEntry(srgJar, "net/minecraft/client/particle/ParticleEngine.class");
        check(engine != null && contains(engine, "f_107296_"),
                "the Minecraft ParticleEngine really has the atlas field f_107296_");
        byte[] minecraft = zipEntry(srgJar, "net/minecraft/client/Minecraft.class");
        check(minecraft != null && contains(minecraft, "f_91061_"),
                "Minecraft really exposes the ParticleEngine field f_91061_");
        byte[] atlasClass = zipEntry(srgJar, "net/minecraft/client/renderer/texture/TextureAtlas.class");
        check(atlasClass != null && contains(atlasClass, "m_118316_"),
                "TextureAtlas really exposes the sprite getter m_118316_");
    }

    private static Path srgJar() {
        String classpath = System.getProperty("java.class.path", "");
        for (String entry : classpath.split(";")) {
            String name = Path.of(entry).getFileName().toString();
            if (name.endsWith("-srg.jar")) {
                return Path.of(entry);
            }
        }
        return null;
    }

    // --- which bolt is the muzzle ------------------------------------------------------------

    /**
     * A barrel modelled as two cubes gives the unit two bolts, one at a rear sleeve and one at
     * the real muzzle. These are the VT-4's measured positions; the addon must always pick the
     * front, and the base's same-length remap must not change how many puffs it spawns.
     */
    private static void muzzleSelection() {
        System.out.println();
        System.out.println("=== which bolt is the muzzle ===");

        AimContext rear = context(0.0, 0.0, 3.44);
        AimContext front = context(0.0, 0.0, 11.27);
        List<AimContext> all = List.of(rear, front);
        Vec3 origin = new Vec3(0.0, 0.0, 0.0);

        List<AimContext> chosen = MuzzleSelect.frontMost(all, origin, MuzzleSelect.TOLERANCE);
        check(chosen.size() == 1 && chosen.get(0) == front,
                "the rear barrel cube is not treated as a muzzle");
        check(!MuzzleSelect.isFrontMost(rear, all, origin, MuzzleSelect.TOLERANCE),
                "the rear bolt is not front-most");
        check(MuzzleSelect.isFrontMost(front, all, origin, MuzzleSelect.TOLERANCE),
                "the front bolt is front-most");

        List<AimContext> remapped = MuzzleSelect.remapRear(all, origin, MuzzleSelect.TOLERANCE);
        check(remapped.size() == 2,
                "the base's remap keeps the context count, so countFire is unchanged");
        check(Math.abs(remapped.get(0).from.f_82481_ - 11.27) < 1.0E-6,
                "the rear context's smoke moves to the front muzzle");
        check(remapped.get(1) == front,
                "a context that was already front-most is kept as it is");

        AimContext left = context(-0.5, 0.0, 11.27);
        AimContext right = context(0.5, 0.0, 11.27);
        check(MuzzleSelect.frontMost(List.of(left, right, rear), origin, MuzzleSelect.TOLERANCE).size() == 2,
                "a twin mount keeps both real muzzles");
        check(MuzzleSelect.frontMost(List.of(), origin, MuzzleSelect.TOLERANCE).isEmpty(),
                "no bolts means no muzzles, not a crash");
    }

    private static AimContext context(double x, double y, double z) {
        AimContext context = new AimContext();
        context.from = new Vec3(x, y, z);
        context.direction = new Vec2(0.0F, 0.0F);
        context.position = context.from;
        return context;
    }

    // --- how big the cloud is ---------------------------------------------------------------

    /**
     * The smoke and dust ladder is the difference between a puff and the cloud in the reference
     * footage. These are the four steps the effect actually uses; they must stay ordered, and an
     * autocannon must not start kicking up a ground ring.
     */
    private static void blastScale() {
        System.out.println();
        System.out.println("=== how big the cloud is ===");

        check(BlastScale.smokePuffs(30.0F) == 4, "an autocannon makes a puff, not a cloud");
        check(BlastScale.smokePuffs(75.0F) == 18, "a 75/88 mm gun makes a small cloud");
        check(BlastScale.smokePuffs(105.0F) == 26, "a 105 mm gun makes a medium cloud");
        check(BlastScale.smokePuffs(125.0F) == 38, "a 120/125 mm gun makes the full cloud");
        check(BlastScale.smokePuffs(152.0F) == 48, "artillery is capped at 48 puffs");
        check(BlastScale.smokePuffs(125.0F) > BlastScale.smokePuffs(105.0F)
                        && BlastScale.smokePuffs(105.0F) > BlastScale.smokePuffs(75.0F),
                "the smoke ladder is ordered");

        check(BlastScale.dustPuffs(30.0F) == 0, "an autocannon raises no dust ring");
        check(BlastScale.dustPuffs(75.0F) == 8, "a light tank gun raises a small dust ring");
        check(BlastScale.dustPuffs(125.0F) == 24, "a modern tank gun raises the full ring");
        check(BlastScale.dustPuffs(125.0F) > BlastScale.dustPuffs(105.0F),
                "the dust ladder is ordered");

        check(BlastScale.smokeLifeTicks(125.0F) >= 120,
                "the long-lived puffs last several seconds");
        check(BlastScale.longLivedPuffs(38) > 0 && BlastScale.longLivedPuffs(38) < 38,
                "some but not all of the cloud drifts");
        check(BlastScale.longLivedPuffs(4) >= 1, "a small cloud still has a drifting puff");
    }

    // --- the negative test -----------------------------------------------------------------

    private static void negativeTest(JsonObject sounds) {
        System.out.println();
        System.out.println("=== planted defects (these must be rejected) ===");
        JsonObject planted = sounds.deepCopy();
        if (planted.has("cannon_heavy")) {
            planted.remove("cannon_heavy");
        }
        check(!validateSounds(planted).isEmpty(), "a missing sound event is reported");

        check(!isOgg(new byte[]{0x00, 0x01, 0x02, 0x03}), "a four-byte stub is not an Ogg");
        check(!isOgg(new byte[]{'O', 'g', 'g'}), "a three-byte prefix is not an Ogg");
        check(!isPng(new byte[]{(byte) 0x89, 'P', 'N', 'G'}), "a four-byte stub is not a PNG");
    }

    // --- helpers ---------------------------------------------------------------------------

    private static boolean isOgg(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == 'O' && bytes[1] == 'g' && bytes[2] == 'g' && bytes[3] == 'S';
    }

    private static boolean isPng(byte[] bytes) {
        return bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                && bytes[4] == 0x0D && bytes[5] == 0x0A && bytes[6] == 0x1A && bytes[7] == 0x0A;
    }

    private static int pngInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24) | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
    }

    private static boolean contains(byte[] haystack, String needle) {
        byte[] pattern = needle.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i + pattern.length <= haystack.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (haystack[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static Set<String> names(JsonObject config, String key) {
        Set<String> names = new LinkedHashSet<>();
        if (config.has(key) && config.get(key).isJsonArray()) {
            for (JsonElement element : config.getAsJsonArray(key)) {
                names.add(element.getAsString());
            }
        }
        return names;
    }

    private static Path baseModJar() {
        String classpath = System.getProperty("java.class.path", "");
        for (String entry : classpath.split(";")) {
            String name = Path.of(entry).getFileName().toString();
            if (name.startsWith("ywzj_vehicle-") && name.endsWith(".jar")) {
                return Path.of(entry);
            }
        }
        return null;
    }

    private static byte[] zipEntry(Path jar, String name) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String readSource(String relative) {
        try {
            Path path = Path.of("").toAbsolutePath().resolve(relative);
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            check(false, "could not read " + relative + " - run this from the project root");
            return null;
        }
    }

    private static JsonObject resourceJson(String path) {
        byte[] bytes = resourceBytes(path);
        if (bytes == null) {
            check(false, path + " is not in the compiled output");
            return null;
        }
        try (InputStreamReader reader = new InputStreamReader(new java.io.ByteArrayInputStream(bytes),
                StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            check(false, "could not parse " + path + ": " + e);
            return null;
        }
    }

    private static byte[] resourceBytes(String path) {
        try (InputStream in = EffectsCheck.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static void check(boolean ok, String what) {
        checks++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
