import com.ywzjvehicle.armor.ArmorConfig;
import com.ywzjvehicle.armor.VehicleCollision;
import com.ywzjvehicle.armor.VehicleRamming;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks the collision and high-speed-ram side of the armour table offline, without a game.
 *
 * <p>Four things can go wrong and none of them has a symptom you would notice before a tank has
 * already driven through a wall:
 *
 * <ul>
 *   <li><b>The base mod's collision damage type moved.</b> The whole interception is one
 *       {@code ResourceLocation}; a rename makes every crash quietly take the base mod's
 *       armour-blind damage again.</li>
 *   <li><b>The conversion or the scope drifted.</b> A sign error in the damage-to-penetration
 *       figure, or a vehicle list that quietly gained or lost an id, changes which crashes hurt
 *       and which vehicles are covered. Both are checked against the shipped block.</li>
 *   <li><b>The ram is not registered.</b> {@code VehicleRamming} is a static event handler; if
 *       {@code VehicleArmorMod} stops registering it, nothing throws and the high-speed path just
 *       never runs. The class and its registration are checked in the compiled classes.</li>
 *   <li><b>The blocks not surviving an upgrade.</b> A v99 file has neither block. Both have to be
 *       added whole, and a hand-edited block has to keep every figure.</li>
 * </ul>
 *
 * <p><b>What it cannot check</b>, stated rather than faked: whether the direction inferred from
 * the pre-impact velocity is the face a given collision actually met, whether the contact points
 * in {@code VehicleCollectCollisionEvent} line up with the blocks a tank sees, whether the
 * terrain tags match a given block in a loaded world (vanilla populates them from the datapack
 * at world load, which an offline tool has no world for), and whether the shock feels right in
 * play. Those need a live crash.
 *
 * <p>Usage: CollisionCheck
 */
public class CollisionCheck {

    private static int failures;

    /** The base mod's collision damage type. */
    private static final String COLLISION_ID = "vehicle_collision";

    /** The shipped collision conversion. */
    private static final float PER_DAMAGE = 0.1F;
    private static final float MAX_PENETRATION = 500.0F;

    /** The shipped crew shock. */
    private static final float SHOCK_MIN = 20.0F;
    private static final float SHOCK_FULL = 200.0F;
    private static final int SHOCK_NAUSEA = 40;
    private static final int SHOCK_SLOWNESS = 20;

    /** The shipped ramming curve. */
    private static final float RAM_SPEED = 0.15F;
    private static final float RAM_BASE_HARDNESS = 0.4F;
    private static final float RAM_HARDNESS_PER_SPEED = 10.0F;
    private static final float RAM_MASS_REFERENCE = 40.0F;
    private static final int RAM_MAX_BLOCKS = 12;

    /** The seven vehicles this first version is scoped to. */
    private static final String[] ARMOR_PIONEER = {
            "armor_pioneer:vt5", "armor_pioneer:vt4", "armor_pioneer:k2",
            "armor_pioneer:type10", "armor_pioneer:tianma20", "armor_pioneer:ztz100",
            "armor_pioneer:leopard2a4"
    };

    public static void main(String[] args) throws Exception {
        try {
            references();
            conversion();
            defaults();
            rammingDefaults();
            migration();
            liveConfig();
        } catch (Throwable thrown) {
            System.out.println("  FAIL the check itself could not run: " + thrown);
            thrown.printStackTrace(System.out);
            failures++;
        }

        System.out.println();
        if (failures == 0) {
            System.out.println("RESULT: collision damage is measured against the armour table, "
                    + "the crew feels a short shock, the Armor Pioneer vehicles can ram, and the "
                    + "world's own ground is never crushed");
        } else {
            System.out.println("RESULT: " + failures + " check(s) failed - see above");
            System.exit(1);
        }
    }

    /** The compiled classes, against the rules a source scan cannot prove. */
    private static void references() throws Exception {
        System.out.println("=== which classes take collisions ===");
        Path classes = classesDir();
        check("the compiled classes were found", classes != null);
        if (classes == null) {
            return;
        }
        Path collision = classes.resolve("com/ywzjvehicle/armor/VehicleCollision.class");
        Path ramming = classes.resolve("com/ywzjvehicle/armor/VehicleRamming.class");
        Path system = classes.resolve("com/ywzjvehicle/armor/ArmorSystem.class");
        Path mod = classes.resolve("com/ywzjvehicle/armor/VehicleArmorMod.class");
        check("VehicleCollision.class exists", Files.exists(collision));
        check("VehicleRamming.class exists", Files.exists(ramming));
        check("ArmorSystem.class exists", Files.exists(system));
        check("VehicleArmorMod.class exists", Files.exists(mod));
        if (!Files.exists(collision) || !Files.exists(ramming) || !Files.exists(system)
                || !Files.exists(mod)) {
            return;
        }
        // Exact id, not a substring scan: "vehicle_collision_typo" contains the right word.
        check("exactly the base mod's collision damage type is taken over",
                VehicleCollision.damageType().toString().equals("ywzj_vehicle:" + COLLISION_ID));
        check("ArmorSystem routes collisions through VehicleCollision",
                mentions(system, "VehicleCollision"));
        check("VehicleRamming reads the base collision event",
                mentions(ramming, "VehicleCollectCollisionEvent"));
        // The ground guard is a tag lookup; the tag path has to be in the compiled class or the
        // filter silently protects nothing and natural terrain is dug again. The tags live in
        // the lazy holder, so this is a class of its own.
        Path natural = classes.resolve(
                "com/ywzjvehicle/armor/VehicleRamming$NaturalGround.class");
        check("the natural-ground holder exists", Files.exists(natural));
        if (Files.exists(natural)) {
            check("the natural-ground holder reads the terrain tags",
                    mentions(natural, "base_stone_overworld")
                            && mentions(natural, "base_stone_nether")
                            && mentions(natural, "terracotta")
                            && mentions(natural, "dirt"));
            check("the natural-ground holder reads the configurable extra list",
                    mentions(natural, "natural_ground_blocks")
                            && mentions(natural, "protect_natural_ground"));
        }
        check("VehicleArmorMod registers VehicleRamming", mentions(mod, "VehicleRamming"));
    }

    /** The collision conversion, and the zero case that must still be a round. */
    private static void conversion() {
        System.out.println();
        System.out.println("=== the collision damage-to-penetration conversion ===");
        check("a 40t tank at 36 km/h (200 raw) is 20mm",
                close(VehicleCollision.penetrationFor(200.0F, PER_DAMAGE, MAX_PENETRATION), 20.0F));
        check("a 40t tank at 72 km/h (800 raw) is 80mm",
                close(VehicleCollision.penetrationFor(800.0F, PER_DAMAGE, MAX_PENETRATION), 80.0F));
        check("the cap holds at 500mm however large the crash is",
                close(VehicleCollision.penetrationFor(10000.0F, PER_DAMAGE, MAX_PENETRATION),
                        MAX_PENETRATION));
        check("a zero factor penetrates nothing",
                VehicleCollision.penetrationFor(200.0F, 0.0F, MAX_PENETRATION) == 0.0F);
        check("a zero cap penetrates nothing",
                VehicleCollision.penetrationFor(200.0F, PER_DAMAGE, 0.0F) == 0.0F);
        check("a non-positive crash penetrates nothing",
                VehicleCollision.penetrationFor(0.0F, PER_DAMAGE, MAX_PENETRATION) == 0.0F
                        && VehicleCollision.penetrationFor(-4.0F, PER_DAMAGE,
                        MAX_PENETRATION) == 0.0F);
        ArmorConfig.Ammo zero = VehicleCollision.roundFor(0.0F);
        check("a zero-damage collision still returns a round", zero != null);
        check("that round is a zero-penetration ricochet",
                zero != null && zero.penetration == 0.0F);
    }

    /** The shipped collision block and its scope. */
    private static void defaults() throws Exception {
        System.out.println();
        System.out.println("=== the shipped collision block ===");
        ArmorConfig.Profile defaults = defaultProfile();
        ArmorConfig.VehicleCollisions collisions = defaults.vehicle_collisions;
        check("defaultProfile carries vehicle_collisions", collisions != null);
        if (collisions == null) {
            return;
        }
        check("it ships enabled", collisions.active());
        check("penetration_per_damage is " + PER_DAMAGE,
                close(collisions.penetration_per_damage, PER_DAMAGE));
        check("max_penetration is " + MAX_PENETRATION,
                close(collisions.max_penetration, MAX_PENETRATION));
        check("damage_multiplier is absent, so the plate's own multiplier is used",
                collisions.damage_multiplier == null);
        check("the scope is exactly the seven Armor Pioneer vehicles",
                sameSet(collisions.vehicles, ARMOR_PIONEER));
        check("an unknown vehicle is out of scope",
                !collisions.appliesTo("vvp_ywzj:t64"));
        ArmorConfig.VehicleCollisions.Shock shock = collisions.shock;
        check("the crew shock ships enabled", shock != null && shock.active());
        if (shock != null) {
            check("shock min_damage is " + SHOCK_MIN, close(shock.min_damage, SHOCK_MIN));
            check("shock full_damage is " + SHOCK_FULL, close(shock.full_damage, SHOCK_FULL));
            check("shock max_nausea_ticks is " + SHOCK_NAUSEA,
                    shock.max_nausea_ticks == SHOCK_NAUSEA);
            check("shock max_slowness_ticks is " + SHOCK_SLOWNESS,
                    shock.max_slowness_ticks == SHOCK_SLOWNESS);
        }
    }

    /** The shipped ramming block and its curve. */
    private static void rammingDefaults() throws Exception {
        System.out.println();
        System.out.println("=== the shipped ramming block ===");
        ArmorConfig.Profile defaults = defaultProfile();
        ArmorConfig.VehicleRamming ramming = defaults.vehicle_ramming;
        check("defaultProfile carries vehicle_ramming", ramming != null);
        if (ramming == null) {
            return;
        }
        check("it ships enabled", ramming.active());
        check("the scope is exactly the seven Armor Pioneer vehicles",
                sameSet(ramming.vehicles, ARMOR_PIONEER));
        check("an unknown vehicle is out of scope",
                !ramming.appliesTo("vvp_ywzj:t64"));
        check("speed_threshold is " + RAM_SPEED,
                close(ramming.speed_threshold, RAM_SPEED));
        check("base_hardness is " + RAM_BASE_HARDNESS,
                close(ramming.base_hardness, RAM_BASE_HARDNESS));
        check("hardness_per_speed is " + RAM_HARDNESS_PER_SPEED,
                close(ramming.hardness_per_speed, RAM_HARDNESS_PER_SPEED));
        check("mass_reference_tons is " + RAM_MASS_REFERENCE,
                close(ramming.mass_reference_tons, RAM_MASS_REFERENCE));
        check("max_blocks_per_tick is " + RAM_MAX_BLOCKS,
                ramming.max_blocks_per_tick == RAM_MAX_BLOCKS);
        check("block entities are skipped", ramming.skip_block_entities);
        check("natural ground is protected by default",
                ramming.protect_natural_ground == null || ramming.protect_natural_ground);
        check("the extra natural-ground list ships empty",
                ramming.natural_ground_blocks != null && ramming.natural_ground_blocks.isEmpty());

        float tankSlow = VehicleRamming.hardnessLimit(ramming, 0.5D, 40.0D);
        float tankFast = VehicleRamming.hardnessLimit(ramming, 1.0D, 40.0D);
        float car = VehicleRamming.hardnessLimit(ramming, 0.5D, 2.0D);
        check("40t at 0.5 blocks/tick reaches " + tankSlow + " hardness",
                close(tankSlow, 5.4F));
        check("40t at 1.0 blocks/tick reaches " + tankFast + " hardness",
                close(tankFast, 10.4F));
        check("2t at 0.5 blocks/tick reaches only " + car + " hardness",
                close(car, 1.518F));
        check("obsidian's hardness 50 is above every shipped limit",
                tankFast < 50.0F);
        check("faster is always at least as strong", tankFast >= tankSlow);
    }

    /** Older files must gain both blocks; hand-edited blocks must keep every figure. */
    private static void migration() throws Exception {
        System.out.println();
        System.out.println("=== the v99 -> v101 upgrade ===");
        Method upgrade = ArmorConfig.class.getDeclaredMethod("upgrade", ArmorConfig.Profile.class);
        upgrade.setAccessible(true);
        Method sanitize = ArmorConfig.class.getDeclaredMethod("sanitize", ArmorConfig.Profile.class);
        sanitize.setAccessible(true);

        ArmorConfig.Profile old = defaultProfile();
        old.config_version = 99;
        old.vehicle_collisions = null;
        old.vehicle_ramming = null;
        upgrade.invoke(null, old);
        check("a v99 config gains vehicle_collisions", old.vehicle_collisions != null);
        check("a v99 config gains vehicle_ramming", old.vehicle_ramming != null);
        check("the added collision block is the shipped one",
                old.vehicle_collisions != null && old.vehicle_collisions.active()
                        && sameSet(old.vehicle_collisions.vehicles, ARMOR_PIONEER)
                        && close(old.vehicle_collisions.penetration_per_damage, PER_DAMAGE));
        check("the added ramming block is the shipped one",
                old.vehicle_ramming != null && old.vehicle_ramming.active()
                        && sameSet(old.vehicle_ramming.vehicles, ARMOR_PIONEER)
                        && close(old.vehicle_ramming.speed_threshold, RAM_SPEED));
        check("the added ramming block protects natural ground",
                old.vehicle_ramming != null
                        && (old.vehicle_ramming.protect_natural_ground == null
                        || old.vehicle_ramming.protect_natural_ground)
                        && old.vehicle_ramming.natural_ground_blocks != null
                        && old.vehicle_ramming.natural_ground_blocks.isEmpty());

        // A file written at v100 already carries the block but neither new key. Gson's field
        // initialisers hide that in most cases; this puts the hole in front of the migration.
        ArmorConfig.VehicleRamming v100Ram = new ArmorConfig.VehicleRamming();
        v100Ram.speed_threshold = 0.5F;
        v100Ram.protect_natural_ground = null;
        v100Ram.natural_ground_blocks = null;
        ArmorConfig.Profile v100Profile = defaultProfile();
        v100Profile.config_version = 100;
        v100Profile.vehicle_ramming = v100Ram;
        upgrade.invoke(null, v100Profile);
        check("a v100 ramming block gains the ground guard as on",
                v100Ram.protect_natural_ground != null && v100Ram.protect_natural_ground);
        check("a v100 ramming block gains an empty extra list",
                v100Ram.natural_ground_blocks != null && v100Ram.natural_ground_blocks.isEmpty());
        check("the v100 curve is not rewritten by the ground guard",
                close(v100Ram.speed_threshold, 0.5F));

        ArmorConfig.VehicleRamming handGuard = new ArmorConfig.VehicleRamming();
        handGuard.protect_natural_ground = Boolean.FALSE;
        handGuard.natural_ground_blocks = new java.util.ArrayList<>(List.of("custom:rock"));
        ArmorConfig.Profile handGuardProfile = defaultProfile();
        handGuardProfile.config_version = 100;
        handGuardProfile.vehicle_ramming = handGuard;
        upgrade.invoke(null, handGuardProfile);
        check("a hand-disabled ground guard survives the upgrade",
                Boolean.FALSE.equals(handGuard.protect_natural_ground));
        check("a hand-written extra list survives the upgrade",
                handGuard.natural_ground_blocks.equals(List.of("custom:rock")));

        ArmorConfig.VehicleCollisions custom = new ArmorConfig.VehicleCollisions();
        custom.enabled = Boolean.FALSE;
        custom.penetration_per_damage = 0.3F;
        custom.max_penetration = 900.0F;
        custom.damage_multiplier = 2.0F;
        custom.vehicles = new java.util.ArrayList<>(List.of("custom:one"));
        custom.shock = new ArmorConfig.VehicleCollisions.Shock();
        custom.shock.enabled = Boolean.FALSE;
        custom.shock.max_nausea_ticks = 5;
        ArmorConfig.Profile hand = defaultProfile();
        hand.config_version = 99;
        hand.vehicle_collisions = custom;
        hand.vehicle_ramming = null;
        upgrade.invoke(null, hand);
        check("a hand-edited collision block survives the upgrade",
                hand.vehicle_collisions == custom && !custom.active()
                        && close(custom.penetration_per_damage, 0.3F)
                        && close(custom.max_penetration, 900.0F)
                        && custom.vehicles.equals(List.of("custom:one"))
                        && custom.shock != null && !custom.shock.active()
                        && custom.shock.max_nausea_ticks == 5);

        ArmorConfig.VehicleRamming customRam = new ArmorConfig.VehicleRamming();
        customRam.enabled = Boolean.FALSE;
        customRam.vehicles = new java.util.ArrayList<>(List.of("custom:two"));
        customRam.speed_threshold = 0.5F;
        ArmorConfig.Profile handRam = defaultProfile();
        handRam.config_version = 99;
        handRam.vehicle_ramming = customRam;
        handRam.vehicle_collisions = null;
        upgrade.invoke(null, handRam);
        check("a hand-edited ramming block survives the upgrade",
                handRam.vehicle_ramming == customRam && !customRam.active()
                        && customRam.vehicles.equals(List.of("custom:two"))
                        && close(customRam.speed_threshold, 0.5F));

        ArmorConfig.Profile missing = defaultProfile();
        missing.vehicle_collisions = null;
        missing.vehicle_ramming = null;
        sanitize.invoke(null, missing);
        check("sanitize creates a missing collision block", missing.vehicle_collisions != null);
        check("sanitize creates a missing ramming block", missing.vehicle_ramming != null);

        ArmorConfig.VehicleCollisions dirty = new ArmorConfig.VehicleCollisions();
        dirty.penetration_per_damage = -1.0F;
        dirty.max_penetration = -2.0F;
        dirty.damage_multiplier = -3.0F;
        dirty.vehicles = null;
        dirty.shock = new ArmorConfig.VehicleCollisions.Shock();
        dirty.shock.min_damage = 10.0F;
        dirty.shock.full_damage = 5.0F;
        dirty.shock.max_nausea_ticks = -1;
        dirty.shock.max_slowness_ticks = -1;
        ArmorConfig.Profile dirtyProfile = defaultProfile();
        dirtyProfile.vehicle_collisions = dirty;
        sanitize.invoke(null, dirtyProfile);
        check("sanitize clamps negative collision figures to zero",
                dirty.penetration_per_damage == 0.0F && dirty.max_penetration == 0.0F
                        && dirty.damage_multiplier != null && dirty.damage_multiplier == 0.0F);
        check("sanitize fills a missing scope list", dirty.vehicles != null);
        check("sanitize repairs an inverted shock band",
                dirty.shock.full_damage > dirty.shock.min_damage);
        check("sanitize clamps negative shock lengths",
                dirty.shock.max_nausea_ticks == 0 && dirty.shock.max_slowness_ticks == 0);

        ArmorConfig.VehicleRamming dirtyRam = new ArmorConfig.VehicleRamming();
        dirtyRam.vehicles = null;
        dirtyRam.speed_threshold = -1.0F;
        dirtyRam.base_hardness = -1.0F;
        dirtyRam.hardness_per_speed = -1.0F;
        dirtyRam.mass_reference_tons = 0.0F;
        dirtyRam.max_blocks_per_tick = -1;
        dirtyRam.protect_natural_ground = null;
        dirtyRam.natural_ground_blocks = null;
        ArmorConfig.Profile dirtyRamProfile = defaultProfile();
        dirtyRamProfile.vehicle_ramming = dirtyRam;
        sanitize.invoke(null, dirtyRamProfile);
        check("sanitize fills a missing ram scope list", dirtyRam.vehicles != null);
        check("sanitize clamps negative ram figures to zero",
                dirtyRam.speed_threshold == 0.0F && dirtyRam.base_hardness == 0.0F
                        && dirtyRam.hardness_per_speed == 0.0F);
        check("sanitize restores a usable mass reference",
                close(dirtyRam.mass_reference_tons, RAM_MASS_REFERENCE));
        check("sanitize clamps a negative block cap", dirtyRam.max_blocks_per_tick == 0);
        check("sanitize turns a missing ground guard back on",
                dirtyRam.protect_natural_ground != null && dirtyRam.protect_natural_ground);
        check("sanitize fills a missing natural-ground list",
                dirtyRam.natural_ground_blocks != null);
    }

    /** The install this machine actually runs, upgraded in memory only. */
    private static void liveConfig() throws Exception {
        System.out.println();
        System.out.println("=== the live config ===");
        Path config = liveConfigPath();
        if (config == null || !Files.exists(config)) {
            System.out.println("  skip no config file at " + config);
            return;
        }
        ArmorConfig.Profile live = ArmorConfig.configReader().fromJson(
                Files.readString(config, StandardCharsets.UTF_8), ArmorConfig.Profile.class);
        Method upgrade = ArmorConfig.class.getDeclaredMethod("upgrade", ArmorConfig.Profile.class);
        upgrade.setAccessible(true);
        upgrade.invoke(null, live);
        check("the live config gains vehicle_collisions", live.vehicle_collisions != null);
        check("the live config gains vehicle_ramming", live.vehicle_ramming != null);
        if (live.vehicle_collisions == null || live.vehicle_ramming == null) {
            return;
        }
        System.out.printf(java.util.Locale.ROOT,
                "  live collisions: %.2f mm/damage, cap %.1f, %d vehicle(s), shock %s%n",
                live.vehicle_collisions.penetration_per_damage,
                live.vehicle_collisions.max_penetration,
                live.vehicle_collisions.vehicles == null
                        ? 0 : live.vehicle_collisions.vehicles.size(),
                live.vehicle_collisions.shock != null && live.vehicle_collisions.shock.active()
                        ? "on" : "off");
        System.out.printf(java.util.Locale.ROOT,
                "  live ramming  : %.2f blocks/tick gate, %d vehicle(s), %d blocks/tick cap%n",
                live.vehicle_ramming.speed_threshold,
                live.vehicle_ramming.vehicles == null ? 0 : live.vehicle_ramming.vehicles.size(),
                live.vehicle_ramming.max_blocks_per_tick);
        check("the live collision block has a usable conversion",
                live.vehicle_collisions.penetration_per_damage >= 0.0F
                        && live.vehicle_collisions.max_penetration >= 0.0F);
        check("the live ramming block has a usable curve",
                live.vehicle_ramming.speed_threshold >= 0.0F
                        && live.vehicle_ramming.max_blocks_per_tick >= 0);
    }

    private static Path liveConfigPath() {
        String root = System.getenv().getOrDefault("MC_ROOT", "E:\\.minecraft");
        return Path.of(root, "/versions/1.20.1-Forge_47.4.2/config/ywzj_vehicle_armor.json");
    }

    private static ArmorConfig.Profile defaultProfile() throws Exception {
        Method defaults = ArmorConfig.class.getDeclaredMethod("defaultProfile");
        defaults.setAccessible(true);
        return (ArmorConfig.Profile) defaults.invoke(null);
    }

    private static Path classesDir() {
        for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            Path path = Path.of(entry);
            if (Files.isDirectory(path)
                    && Files.exists(path.resolve("com/ywzjvehicle/armor/ArmorSystem.class"))) {
                return path;
            }
        }
        return null;
    }

    private static boolean sameSet(List<String> actual, String[] expected) {
        if (actual == null || actual.size() != expected.length) {
            return false;
        }
        Set<String> got = new LinkedHashSet<>(actual);
        Set<String> want = new LinkedHashSet<>(List.of(expected));
        return got.equals(want);
    }

    /** @return whether the class file's constant pool holds the given text. */
    private static boolean mentions(Path classFile, String needle) throws Exception {
        String text = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
        return text.contains(needle);
    }

    private static boolean close(float actual, float expected) {
        return Math.abs(actual - expected) < 1.0E-3F;
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
