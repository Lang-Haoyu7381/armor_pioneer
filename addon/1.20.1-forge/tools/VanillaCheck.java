import com.ywzjvehicle.armor.ArmorConfig;
import com.ywzjvehicle.armor.VanillaExplosion;
import com.ywzjvehicle.armor.VanillaWeapon;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Checks the vanilla side of the armour table offline, without a game.
 *
 * <p>Five things can go wrong here and none of them has a symptom you would notice before a
 * skeleton is already shooting at a tank:
 *
 * <ul>
 *   <li><b>A damage type id that has moved.</b> The whole interception is a handful of
 *       {@code ResourceLocation}s built by hand. If one is renamed, nothing throws - the branch
 *       simply stops matching and that projectile goes back to taking health off the tank while
 *       ignoring every plate in the config.</li>
 *   <li><b>The conversion drifting.</b> The penetration is damage times a factor, capped. A sign
 *       error or a missing clamp is invisible in play until a modded sword opens a main battle
 *       tank; both conversions are exercised here at both ends and at every clamp.</li>
 *   <li><b>A block not surviving an upgrade.</b> A config written at v78 has no
 *       {@code vanilla_weapons} key, and one at v98 has no {@code vanilla_explosions} key. The
 *       upgrade has to add both, and a hand-edited block has to be left exactly as it is.</li>
 *   <li><b>The explosion centre never arriving.</b> The blast branch needs
 *       {@code Explosion.explode()} to push a centre. If {@code ExplosionMixin} is missing from the
 *       mixin config, nothing throws - the branch falls back to the direct entity's position, and
 *       a bed's or respawn anchor's blast (which has no direct entity) quietly returns to the base
 *       mod. The class and its registration are both checked.</li>
 *   <li><b>An accidental dependency.</b> These classes must name no gun mod and no optional mod's
 *       class; vanilla's own damage types are the only thing needed to recognise a sword or a
 *       TNT block.</li>
 * </ul>
 *
 * <p><b>What it cannot check</b>, stated rather than faked: whether an arrow or a blast actually
 * reaches {@code DamageSystem.hurt} with the damage this conversion expects, whether the rays
 * aimed at the hull centre hit the right part on every vehicle, and whether player melee is enabled
 * on a given server - the base mod gates it behind {@code allowMeleeDamageVehicle}. All three need
 * a live game.
 *
 * <p>Usage: VanillaCheck
 */
public class VanillaCheck {

    private static int failures;

    /** The ten weapon damage types the interception is keyed on. */
    private static final String[] TYPES = {
            "player_attack", "mob_attack", "mob_attack_no_aggro", "arrow", "trident",
            "fireball", "unattributed_fireball", "wither_skull", "mob_projectile", "thrown"
    };

    /** The three vanilla explosion damage types. */
    private static final String[] EXPLOSION_TYPES = {
            "explosion", "player_explosion", "bad_respawn_point"
    };

    /** The shipped weapon conversion. Held here rather than read from the class. */
    private static final float PER_DAMAGE = 0.4F;
    private static final float MAX_PENETRATION = 10.0F;
    private static final float MULTIPLIER = 0.5F;

    /** The shipped blast conversion. */
    private static final float BLAST_PER_DAMAGE = 0.5F;
    private static final float BLAST_MAX_PENETRATION = 80.0F;

    public static void main(String[] args) throws Exception {
        try {
            references();
            conversion();
            blastConversion();
            defaults();
            blastDefaults();
            migration();
            liveConfig();
        } catch (Throwable thrown) {
            System.out.println("  FAIL the check itself could not run: " + thrown);
            thrown.printStackTrace(System.out);
            failures++;
        }

        System.out.println();
        if (failures == 0) {
            System.out.println("RESULT: vanilla weapons and explosions are recognised by damage "
                    + "type, rated from what they carry, and reach the armour table from an older "
                    + "config");
        } else {
            System.out.println("RESULT: " + failures + " check(s) failed - see above");
            System.exit(1);
        }
    }

    /**
     * The compiled classes, against the rules a source scan cannot prove: every id is in the
     * constant pool, both classes are routed from {@code ArmorSystem}, and the explosion mixin is
     * registered.
     */
    private static void references() throws Exception {
        System.out.println("=== which classes take vanilla hits ===");
        Path classes = classesDir();
        check("the compiled classes were found", classes != null);
        if (classes == null) {
            return;
        }
        Path weapon = classes.resolve("com/ywzjvehicle/armor/VanillaWeapon.class");
        Path explosion = classes.resolve("com/ywzjvehicle/armor/VanillaExplosion.class");
        Path mixin = classes.resolve("com/ywzjvehicle/armor/mixin/ExplosionMixin.class");
        Path system = classes.resolve("com/ywzjvehicle/armor/ArmorSystem.class");
        check("VanillaWeapon.class exists", Files.exists(weapon));
        check("VanillaExplosion.class exists", Files.exists(explosion));
        check("ExplosionMixin.class exists", Files.exists(mixin));
        check("ArmorSystem.class exists", Files.exists(system));
        if (!Files.exists(weapon) || !Files.exists(explosion) || !Files.exists(mixin)
                || !Files.exists(system)) {
            return;
        }

        // Exact ids, not a substring scan: "arrow_typo" contains "arrow", and a byte check that
        // accepted it would be red only when somebody remembered to look. This is the check that
        // was made red by planting exactly that typo.
        java.util.Set<String> expected = new java.util.LinkedHashSet<>();
        for (String type : TYPES) {
            expected.add("minecraft:" + type);
        }
        java.util.Set<String> actual = new java.util.LinkedHashSet<>();
        for (net.minecraft.resources.ResourceLocation id : VanillaWeapon.damageTypes()) {
            actual.add(id.toString());
        }
        check("exactly the ten expected weapon damage types are taken over",
                actual.equals(expected));
        if (!actual.equals(expected)) {
            System.out.println("       expected " + expected);
            System.out.println("       got      " + actual);
        }
        check("no weapon damage type is listed twice",
                VanillaWeapon.damageTypes().length == actual.size());

        java.util.Set<String> expectedBlasts = new java.util.LinkedHashSet<>();
        for (String type : EXPLOSION_TYPES) {
            expectedBlasts.add("minecraft:" + type);
        }
        java.util.Set<String> actualBlasts = new java.util.LinkedHashSet<>();
        for (net.minecraft.resources.ResourceLocation id : VanillaExplosion.damageTypes()) {
            actualBlasts.add(id.toString());
        }
        check("exactly the three expected explosion damage types are taken over",
                actualBlasts.equals(expectedBlasts));
        if (!actualBlasts.equals(expectedBlasts)) {
            System.out.println("       expected " + expectedBlasts);
            System.out.println("       got      " + actualBlasts);
        }
        check("no explosion damage type is listed twice",
                VanillaExplosion.damageTypes().length == actualBlasts.size());

        check("VanillaWeapon names no gun mod",
                !mentions(weapon, "com/tacz/") && !mentions(weapon, "tacz"));
        check("VanillaExplosion names no gun mod",
                !mentions(explosion, "com/tacz/") && !mentions(explosion, "tacz"));
        check("ArmorSystem routes hits through VanillaWeapon", mentions(system, "VanillaWeapon"));
        check("ArmorSystem routes blasts through VanillaExplosion",
                mentions(system, "VanillaExplosion"));
        check("ExplosionMixin pushes and pops a blast centre",
                mentions(mixin, "pushExplosion") && mentions(mixin, "popExplosion"));

        String config = mixinConfig(classes);
        check("the mixins config was found", config != null);
        if (config != null) {
            check("ExplosionMixin is registered in the mixins config",
                    config.contains("\"ExplosionMixin\""));
        }
    }

    /** The weapon conversion itself, at both ends and at every clamp. */
    private static void conversion() {
        System.out.println();
        System.out.println("=== the weapon damage-to-penetration conversion ===");
        check("a 6-damage hit penetrates 2.4mm",
                close(VanillaWeapon.penetrationFor(6.0F, PER_DAMAGE, MAX_PENETRATION), 2.4F));
        check("a 10-damage hit penetrates 4.0mm",
                close(VanillaWeapon.penetrationFor(10.0F, PER_DAMAGE, MAX_PENETRATION), 4.0F));
        check("a Sharpened 13-damage hit penetrates 5.2mm",
                close(VanillaWeapon.penetrationFor(13.0F, PER_DAMAGE, MAX_PENETRATION), 5.2F));
        check("the cap holds at 10mm however large the hit is",
                close(VanillaWeapon.penetrationFor(1000.0F, PER_DAMAGE, MAX_PENETRATION),
                        MAX_PENETRATION));
        check("a zero factor penetrates nothing",
                VanillaWeapon.penetrationFor(6.0F, 0.0F, MAX_PENETRATION) == 0.0F);
        check("a zero cap penetrates nothing",
                VanillaWeapon.penetrationFor(6.0F, PER_DAMAGE, 0.0F) == 0.0F);
        check("a non-positive hit penetrates nothing",
                VanillaWeapon.penetrationFor(0.0F, PER_DAMAGE, MAX_PENETRATION) == 0.0F
                        && VanillaWeapon.penetrationFor(-4.0F, PER_DAMAGE, MAX_PENETRATION) == 0.0F);
        check("the shipped figures leave a 6-damage arrow far below every plate",
                VanillaWeapon.penetrationFor(6.0F, PER_DAMAGE, MAX_PENETRATION) < 8.0F);

        ArmorConfig.Ammo snowball = VanillaWeapon.roundForAmount(0.0F);
        check("a zero-damage thrown hit still returns a round", snowball != null);
        check("that round is a zero-penetration ricochet",
                snowball != null && snowball.penetration == 0.0F);
    }

    /** The blast conversion itself, at both ends and at every clamp. */
    private static void blastConversion() {
        System.out.println();
        System.out.println("=== the blast damage-to-penetration conversion ===");
        check("a TNT blast (29) penetrates 14.5mm",
                close(VanillaExplosion.penetrationFor(29.0F, BLAST_PER_DAMAGE,
                        BLAST_MAX_PENETRATION), 14.5F));
        check("a charged creeper / end crystal (43) penetrates 21.5mm",
                close(VanillaExplosion.penetrationFor(43.0F, BLAST_PER_DAMAGE,
                        BLAST_MAX_PENETRATION), 21.5F));
        check("a ghast fireball blast (8) penetrates 4.0mm",
                close(VanillaExplosion.penetrationFor(8.0F, BLAST_PER_DAMAGE,
                        BLAST_MAX_PENETRATION), 4.0F));
        check("the cap holds at 80mm however large the blast is",
                close(VanillaExplosion.penetrationFor(1000.0F, BLAST_PER_DAMAGE,
                        BLAST_MAX_PENETRATION), BLAST_MAX_PENETRATION));
        check("a zero factor penetrates nothing",
                VanillaExplosion.penetrationFor(29.0F, 0.0F, BLAST_MAX_PENETRATION) == 0.0F);
        check("a zero cap penetrates nothing",
                VanillaExplosion.penetrationFor(29.0F, BLAST_PER_DAMAGE, 0.0F) == 0.0F);
        check("a non-positive blast penetrates nothing",
                VanillaExplosion.penetrationFor(0.0F, BLAST_PER_DAMAGE,
                        BLAST_MAX_PENETRATION) == 0.0F
                        && VanillaExplosion.penetrationFor(-4.0F, BLAST_PER_DAMAGE,
                        BLAST_MAX_PENETRATION) == 0.0F);

        ArmorConfig.Ammo empty = VanillaExplosion.roundForAmount(0.0F);
        check("a zero-damage blast still returns a round", empty != null);
        check("that round is a zero-penetration ricochet",
                empty != null && empty.penetration == 0.0F);
    }

    /** What a freshly generated config says for weapons. */
    private static void defaults() throws Exception {
        System.out.println();
        System.out.println("=== the shipped weapon block ===");
        ArmorConfig.Profile defaults = defaultProfile();
        ArmorConfig.VanillaWeapons vanilla = defaults.vanilla_weapons;
        check("defaultProfile carries vanilla_weapons", vanilla != null);
        if (vanilla == null) {
            return;
        }
        check("it ships enabled", vanilla.active());
        check("penetration_per_damage is " + PER_DAMAGE,
                close(vanilla.penetration_per_damage, PER_DAMAGE));
        check("max_penetration is " + MAX_PENETRATION,
                close(vanilla.max_penetration, MAX_PENETRATION));
        check("damage_multiplier is " + MULTIPLIER,
                vanilla.damage_multiplier != null && close(vanilla.damage_multiplier, MULTIPLIER));
        check("overrides starts empty",
                vanilla.overrides != null && vanilla.overrides.isEmpty());
        check("a missing enabled key reads as on", new ArmorConfig.VanillaWeapons().active());
        ArmorConfig.VanillaWeapons off = new ArmorConfig.VanillaWeapons();
        off.enabled = Boolean.FALSE;
        check("an explicit false reads as off", !off.active());
        ArmorConfig.VanillaWeapons blank = new ArmorConfig.VanillaWeapons();
        blank.enabled = null;
        check("an explicit null reads as on", blank.active());
    }

    /** What a freshly generated config says for explosions. */
    private static void blastDefaults() throws Exception {
        System.out.println();
        System.out.println("=== the shipped explosion block ===");
        ArmorConfig.Profile defaults = defaultProfile();
        ArmorConfig.VanillaExplosions explosions = defaults.vanilla_explosions;
        check("defaultProfile carries vanilla_explosions", explosions != null);
        if (explosions == null) {
            return;
        }
        check("it ships enabled", explosions.active());
        check("penetration_per_damage is " + BLAST_PER_DAMAGE,
                close(explosions.penetration_per_damage, BLAST_PER_DAMAGE));
        check("max_penetration is " + BLAST_MAX_PENETRATION,
                close(explosions.max_penetration, BLAST_MAX_PENETRATION));
        check("damage_multiplier is absent, so the plate's own multiplier is used",
                explosions.damage_multiplier == null);
        check("overrides starts empty",
                explosions.overrides != null && explosions.overrides.isEmpty());
        check("a missing enabled key reads as on",
                new ArmorConfig.VanillaExplosions().active());
        ArmorConfig.VanillaExplosions off = new ArmorConfig.VanillaExplosions();
        off.enabled = Boolean.FALSE;
        check("an explicit false reads as off", !off.active());
    }

    /** Older files must gain both blocks; blocks that already exist must keep every figure. */
    private static void migration() throws Exception {
        System.out.println();
        System.out.println("=== the upgrades into both blocks ===");
        Method upgrade = ArmorConfig.class.getDeclaredMethod("upgrade", ArmorConfig.Profile.class);
        upgrade.setAccessible(true);
        Method sanitize = ArmorConfig.class.getDeclaredMethod("sanitize", ArmorConfig.Profile.class);
        sanitize.setAccessible(true);

        // v78 -> v79: the weapon block.
        ArmorConfig.Profile shipped = defaultProfile();
        shipped.config_version = 78;
        shipped.vanilla_weapons = null;
        shipped.vanilla_explosions = null;
        upgrade.invoke(null, shipped);
        check("a v78 config gains vanilla_weapons", shipped.vanilla_weapons != null);
        check("the added weapon block is the shipped one",
                shipped.vanilla_weapons != null
                        && shipped.vanilla_weapons.active()
                        && close(shipped.vanilla_weapons.penetration_per_damage, PER_DAMAGE)
                        && close(shipped.vanilla_weapons.max_penetration, MAX_PENETRATION));

        ArmorConfig.VanillaWeapons custom = new ArmorConfig.VanillaWeapons();
        custom.enabled = Boolean.FALSE;
        custom.penetration_per_damage = 1.7F;
        custom.max_penetration = 30.0F;
        custom.damage_multiplier = null;
        custom.overrides.put("minecraft:arrow", new ArmorConfig.Ammo(12.0F));
        ArmorConfig.Profile hand = defaultProfile();
        hand.config_version = 78;
        hand.vanilla_weapons = custom;
        upgrade.invoke(null, hand);
        check("a hand-edited weapon block survives the upgrade",
                hand.vanilla_weapons == custom
                        && !custom.active()
                        && close(custom.penetration_per_damage, 1.7F)
                        && close(custom.max_penetration, 30.0F)
                        && custom.overrides.size() == 1);

        // v98 -> v99: the explosion block.
        ArmorConfig.Profile oldBlast = defaultProfile();
        oldBlast.config_version = 98;
        oldBlast.vanilla_explosions = null;
        upgrade.invoke(null, oldBlast);
        check("a v98 config gains vanilla_explosions", oldBlast.vanilla_explosions != null);
        check("the added explosion block is the shipped one",
                oldBlast.vanilla_explosions != null
                        && oldBlast.vanilla_explosions.active()
                        && close(oldBlast.vanilla_explosions.penetration_per_damage,
                        BLAST_PER_DAMAGE)
                        && close(oldBlast.vanilla_explosions.max_penetration,
                        BLAST_MAX_PENETRATION));

        ArmorConfig.VanillaExplosions customBlast = new ArmorConfig.VanillaExplosions();
        customBlast.enabled = Boolean.FALSE;
        customBlast.penetration_per_damage = 1.7F;
        customBlast.max_penetration = 200.0F;
        customBlast.damage_multiplier = 2.0F;
        customBlast.overrides.put("minecraft:tnt", new ArmorConfig.Ammo(25.0F));
        ArmorConfig.Profile handBlast = defaultProfile();
        handBlast.config_version = 98;
        handBlast.vanilla_explosions = customBlast;
        upgrade.invoke(null, handBlast);
        check("a hand-edited explosion block survives the upgrade",
                handBlast.vanilla_explosions == customBlast
                        && !customBlast.active()
                        && close(customBlast.penetration_per_damage, 1.7F)
                        && close(customBlast.max_penetration, 200.0F)
                        && customBlast.damage_multiplier != null
                        && close(customBlast.damage_multiplier, 2.0F)
                        && customBlast.overrides.size() == 1);

        ArmorConfig.Profile missing = defaultProfile();
        missing.vanilla_weapons = null;
        missing.vanilla_explosions = null;
        sanitize.invoke(null, missing);
        check("sanitize creates a weapon block a hand edit removed",
                missing.vanilla_weapons != null);
        check("sanitize creates an explosion block a hand edit removed",
                missing.vanilla_explosions != null);

        ArmorConfig.VanillaExplosions dirty = new ArmorConfig.VanillaExplosions();
        dirty.penetration_per_damage = -1.0F;
        dirty.max_penetration = -2.0F;
        dirty.damage_multiplier = -3.0F;
        dirty.overrides = null;
        ArmorConfig.Profile dirtyProfile = defaultProfile();
        dirtyProfile.vanilla_explosions = dirty;
        sanitize.invoke(null, dirtyProfile);
        check("sanitize clamps negative blast figures to zero",
                dirty.penetration_per_damage == 0.0F && dirty.max_penetration == 0.0F
                        && dirty.damage_multiplier != null && dirty.damage_multiplier == 0.0F);
        check("sanitize fills a missing blast override map", dirty.overrides != null);
    }

    /**
     * The install this machine actually runs. Read and upgraded in memory only - nothing is
     * written, because the game owns that file and the next launch will do the same upgrade.
     */
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
        check("the live config parses and gains vanilla_weapons", live.vanilla_weapons != null);
        check("the live config parses and gains vanilla_explosions",
                live.vanilla_explosions != null);
        if (live.vanilla_weapons == null || live.vanilla_explosions == null) {
            return;
        }
        System.out.printf(java.util.Locale.ROOT,
                "  live weapons: %.2f mm/damage, cap %.1f mm, multiplier %s, %d override(s)%n",
                live.vanilla_weapons.penetration_per_damage,
                live.vanilla_weapons.max_penetration,
                live.vanilla_weapons.damage_multiplier == null
                        ? "(plate)" : String.format(java.util.Locale.ROOT, "%.2f",
                        live.vanilla_weapons.damage_multiplier),
                live.vanilla_weapons.overrides == null ? 0 : live.vanilla_weapons.overrides.size());
        System.out.printf(java.util.Locale.ROOT,
                "  live blasts : %.2f mm/damage, cap %.1f mm, multiplier %s, %d override(s)%n",
                live.vanilla_explosions.penetration_per_damage,
                live.vanilla_explosions.max_penetration,
                live.vanilla_explosions.damage_multiplier == null
                        ? "(plate)" : String.format(java.util.Locale.ROOT, "%.2f",
                        live.vanilla_explosions.damage_multiplier),
                live.vanilla_explosions.overrides == null
                        ? 0 : live.vanilla_explosions.overrides.size());
        check("the live weapon block has a usable conversion",
                live.vanilla_weapons.penetration_per_damage >= 0.0F
                        && live.vanilla_weapons.max_penetration >= 0.0F);
        check("the live explosion block has a usable conversion",
                live.vanilla_explosions.penetration_per_damage >= 0.0F
                        && live.vanilla_explosions.max_penetration >= 0.0F);
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

    /** @return the built mixin config, or the source copy when the build directory lacks it. */
    private static String mixinConfig(Path classes) throws Exception {
        Path built = classes.resolve("ywzj_vehicle_armor.mixins.json");
        if (Files.exists(built)) {
            return Files.readString(built, StandardCharsets.UTF_8);
        }
        Path source = Path.of("src/main/resources/ywzj_vehicle_armor.mixins.json");
        return Files.exists(source) ? Files.readString(source, StandardCharsets.UTF_8) : null;
    }

    /** @return whether the class file's constant pool holds the given text. */
    private static boolean mentions(Path classFile, String needle) throws Exception {
        String text = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
        return text.contains(needle);
    }

    private static boolean close(float actual, float expected) {
        return Math.abs(actual - expected) < 1.0E-4F;
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
