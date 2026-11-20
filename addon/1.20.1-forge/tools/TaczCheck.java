import com.ywzjvehicle.armor.AmmoIndex;
import com.ywzjvehicle.armor.ArmorConfig;
import com.ywzjvehicle.armor.TaczExplosions;
import com.ywzjvehicle.armor.WeaponClass;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Checks the TACZ side of the armour table offline, without a game and without the gun mod loaded.
 *
 * <p>Three things can go wrong here and none of them has a symptom you would notice while playing:
 *
 * <ul>
 *   <li><b>A class outside the bridge naming TACZ.</b> That is a crash before the main menu for every
 *       player who does not have the gun mod installed - and it cannot be reproduced on a machine
 *       that does, which is every machine this addon is developed on. So it is checked by walking the
 *       compiled classes, which is the only place the answer actually is.</li>
 *   <li><b>A cartridge id that does not exist.</b> The entry sits in the config file looking perfectly
 *       reasonable and is never looked up, so that round quietly keeps TACZ's own damage instead of
 *       meeting the armour table. The same failure the vehicle pack's part ids have, and the same
 *       reason {@code VehicleScaffold} compares those against the pack.</li>
 *   <li><b>A damage type id that has moved.</b> The whole of the TACZ interception is two
 *       {@code ResourceLocation}s - {@code tacz:bullet} and {@code tacz:bullet_ignore_armor} - built
 *       by hand rather than read out of TACZ. If either is renamed nothing throws: the two ids simply
 *       stop matching, and pistol rounds go back to denting tank armour.</li>
 * </ul>
 *
 * <p><b>What it cannot check</b>, stated rather than faked: whether a bullet actually hits a vehicle,
 * whether the two halves of {@code tacAttackEntity}'s hit arrive in the order this addon assumes, and
 * whether the bullet's position at the moment of the hit really is the start of its ray. All three
 * need a live game with a gun and a tank in it. The order in particular is the assumption the whole
 * double-count guard rests on, and it is written down in {@code ArmorSystem} rather than tested here.
 *
 * <p>Usage: TaczCheck
 */
public class TaczCheck {

    private static int failures;

    /** The one class allowed to name TACZ, and the prefix its nested classes share. */
    private static final String BRIDGE = "TaczBridge";

    /** What a class's constant pool is searched for. */
    private static final String TACZ_PACKAGE = "com/tacz/";

    /** The two damage types the interception is keyed on. */
    private static final String[] BULLET_TYPES = {"bullet", "bullet_ignore_armor"};

    /** Nothing in the table should be anywhere near this; a round that is, is a typo. */
    private static final float ABSURD = 1500.0F;

    public static void main(String[] args) throws Exception {
        try {
            references();
            bulletTypes();
            cartridges();
            figures();
            compendium();
            explosions();
            migration();
        } catch (Throwable thrown) {
            System.out.println("  FAIL the check itself could not run: " + thrown);
            thrown.printStackTrace(System.out);
            failures++;
        }

        System.out.println();
        if (failures == 0) {
            System.out.println("RESULT: TACZ is named in one place, and every cartridge it is "
                    + "configured against exists");
        } else {
            System.out.println("RESULT: " + failures + " check(s) failed - see above");
            System.exit(1);
        }
    }

    /**
     * Every compiled class, against the rule that only the bridge may name TACZ.
     *
     * <p>Read out of the compiled classes rather than the sources because that is where the answer
     * is: an {@code import} nothing uses leaves no trace in the class file, and a reference appearing
     * only in a method descriptor leaves no {@code Class} entry. Searching every pool's UTF-8 entries
     * for the package name catches all of them.
     */
    private static void references() throws Exception {
        System.out.println("=== which classes name TACZ ===");
        Path classes = classesDir();
        check("the compiled classes were found", classes != null);
        if (classes == null) {
            return;
        }

        List<Path> files;
        try (Stream<Path> walk = Files.walk(classes)) {
            files = walk.filter(path -> path.toString().endsWith(".class")).toList();
        }
        check("there are compiled classes to read (" + files.size() + ")", files.size() > 0);

        List<String> strays = new ArrayList<>();
        boolean bridgeNamesIt = false;
        boolean hookNamesIt = false;
        for (Path file : files) {
            String name = file.getFileName().toString().replace(".class", "");
            boolean mentions = mentionsTacz(file);
            if (name.startsWith(BRIDGE)) {
                if (name.equals(BRIDGE)) {
                    bridgeNamesIt = mentions;
                } else {
                    hookNamesIt |= mentions;
                }
                continue;
            }
            if (mentions) {
                strays.add(name);
            }
        }

        // The split between the two is the entire reason the bridge is shaped the way it is. The
        // outer class is loaded on every machine, so one reference in it would crash the game for
        // players who have never heard of the gun mod - which is the failure this file exists for.
        check(BRIDGE + " itself names no TACZ type, so loading it is safe without the mod",
                !bridgeNamesIt);
        check(BRIDGE + "'s nested class does name them, so the work still happens when it is there",
                hookNamesIt);
        check("and no other class does (" + strays.size() + " stray)", strays.isEmpty());
        if (!strays.isEmpty()) {
            System.out.println("       " + strays);
            System.out.println("       Every com.tacz reference outside " + BRIDGE + " is a"
                    + " NoClassDefFoundError on a machine without the gun mod - before the main menu,"
                    + " with nothing in the message to say why. Move it into the bridge.");
        }
        System.out.println("  " + files.size() + " class(es) walked, " + BRIDGE
                + " and its nested class excepted");
    }

    /** @return whether this class file names {@code com.tacz} anywhere in its constant pool. */
    private static boolean mentionsTacz(Path classFile) throws IOException {
        return MixinCheck.Pool.read(Files.readAllBytes(classFile)).mentions(TACZ_PACKAGE);
    }

    /**
     * The two damage types, against the gun mod's own data files.
     *
     * <p>They are written into {@code ArmorSystem} as literals rather than read from TACZ's
     * {@code ModDamageTypes}, which is what lets a TACZ hit be recognised without naming a TACZ
     * class - and it means nothing at all would notice if TACZ renamed them.
     */
    private static void bulletTypes() throws Exception {
        System.out.println();
        System.out.println("=== the damage types a TACZ bullet arrives as ===");
        Path jar = taczJar();
        if (jar == null) {
            check("the TACZ jar was found", false);
            return;
        }
        Set<String> declared = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String path = entries.nextElement().getName();
                // `.*data/` and not `.*/data/`: the damage types sit at the top of the jar's own
                // namespace, with nothing in front of them, while the cartridges are nested inside
                // an assets/.../custom pack and do have a segment before. One pattern that demands a
                // leading slash therefore matches the cartridges and silently matches no damage
                // types at all - which reads exactly like "the gun mod has none".
                if (path.matches(".*data/tacz/damage_type/[^/]+\\.json")) {
                    declared.add(path.substring(path.lastIndexOf('/') + 1).replace(".json", ""));
                }
            }
        }
        check("the gun mod declares its damage types (" + declared.size() + ")", !declared.isEmpty());
        for (String type : BULLET_TYPES) {
            check("tacz:" + type + " is still one of them", declared.contains(type));
        }

        // And that the compiled addon really is keyed on those two ids. They are string literals, so
        // they are in ArmorSystem's pool whether or not the wiring around them survived a refactor.
        Path armor = classesDir().resolve("com/ywzjvehicle/armor/ArmorSystem.class");
        String pool = String.join("\u0000", MixinCheck.Pool.read(Files.readAllBytes(armor)).utf8Values());
        for (String type : BULLET_TYPES) {
            check("ArmorSystem looks for tacz:" + type, pool.contains(type));
        }
    }

    /**
     * Every configured cartridge, against the cartridges the installed gun mod actually has.
     *
     * <p>A cartridge id is the name of its index file - {@code data/tacz/index/ammo/762x39.json} is
     * {@code tacz:762x39} - which is why this reads the jar rather than trusting the list.
     */
    private static void cartridges() throws Exception {
        System.out.println();
        System.out.println("=== the cartridges the config rates ===");
        Path jar = taczJar();
        if (jar == null) {
            check("the TACZ jar was found", false);
            return;
        }
        Set<String> present = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String path = entries.nextElement().getName();
                if (path.matches(".*data/tacz/index/ammo/[^/]+\\.json")) {
                    present.add("tacz:" + path.substring(path.lastIndexOf('/') + 1)
                            .replace(".json", ""));
                }
            }
        }
        Set<String> configured = new TreeSet<>(defaultProfile().tacz_rounds.keySet());

        check("the gun mod has cartridges to rate (" + present.size() + ")", !present.isEmpty());
        check("and the config rates some (" + configured.size() + ")", !configured.isEmpty());

        Set<String> invented = new TreeSet<>(configured);
        invented.removeAll(present);
        check("no configured cartridge is one the gun mod does not have", invented.isEmpty());
        if (!invented.isEmpty()) {
            System.out.println("       " + invented);
            System.out.println("       An id that does not exist is never looked up, so the entry"
                    + " prints a plausible number in every table while the round itself keeps TACZ's"
                    + " own damage. Nothing else would ever say so.");
        }

        // Not a failure: a new gun mod version may add cartridges, and an unrated round falling back
        // to TACZ's damage is a defensible answer. Printed because it is a decision somebody should
        // get to make, rather than a thing to find out in game.
        Set<String> unrated = new TreeSet<>(present);
        unrated.removeAll(configured);
        if (!unrated.isEmpty()) {
            System.out.println("  note  " + unrated.size() + " cartridge(s) the gun mod has and this"
                    + " addon does not rate:");
            System.out.println("        " + unrated);
            System.out.println("        those keep TACZ's own damage - add them to tacz_rounds to put"
                    + " them through the armour table");
        }
    }

    /** The figures themselves, and that they survive the trip through the config file. */
    private static void figures() throws Exception {
        System.out.println();
        System.out.println("=== the figures ===");
        ArmorConfig.Profile profile = defaultProfile();

        List<String> silent = new ArrayList<>();
        List<String> absurd = new ArrayList<>();
        for (var entry : profile.tacz_rounds.entrySet()) {
            float penetration = entry.getValue().penetration;
            if (penetration <= 0.0F) {
                silent.add(entry.getKey());
            }
            // The heaviest thing here is an RPG at 330mm. Past 1500 is a typo, and a typo in this
            // direction is the one that makes a round go through everything in the game.
            if (penetration > ABSURD) {
                absurd.add(entry.getKey() + "=" + penetration);
            }
        }
        check("every rated cartridge says how far it goes through steel", silent.isEmpty());
        check("and none of them is a typo that would open any tank in the game", absurd.isEmpty());

        // The two warheads must be measured against the chemical table, not the kinetic one. Getting
        // this wrong is invisible in every number - the round would simply be compared against the
        // wrong plate - and it is the one thing about a shaped charge that is not a figure.
        check("the two shaped charges carry the heat profile",
                "heat".equals(profile.tacz_rounds.get("tacz:40mm").profile)
                        && "heat".equals(profile.tacz_rounds.get("tacz:rpg_rocket").profile));

        ArmorConfig.Profile back = read(write(profile));
        check("all " + profile.tacz_rounds.size() + " of them come back from the file",
                back.tacz_rounds != null && back.tacz_rounds.size() == profile.tacz_rounds.size());
        check("with their figures intact",
                back.tacz_rounds.get("tacz:50bmg").penetration == 20.0F
                        && back.tacz_rounds.get("tacz:rpg_rocket").penetration == 330.0F);
        check("the .300 Blackout cartridge TACZ 1.2.0 added is rated",
                back.tacz_rounds.containsKey("tacz:300blk"));
    }

    /**
     * The cartridges as the ammunition book shows them.
     *
     * <p>The failure this is for is a round that is rated and never appears: a config key with no
     * row is a number in a file that nobody can read without opening the file. The shelf matters
     * for the same reason - a shaped charge filed as kinetic is compared against the wrong table
     * and prints a number that looks fine and means nothing.
     */
    private static void compendium() throws Exception {
        System.out.println();
        System.out.println("=== TACZ in the ammunition compendium ===");
        List<AmmoIndex.Entry> rows = AmmoIndex.taczEntries();
        int rated = defaultProfile().tacz_rounds.size();
        check("every rated cartridge is a row (" + rows.size() + "/" + rated + ")",
                rows.size() == rated);
        boolean allSmallArms = true;
        boolean allNamed = true;
        for (AmmoIndex.Entry row : rows) {
            allSmallArms &= row.category().weapon() == WeaponClass.SMALL_ARMS;
            // A row that falls back to its id is a designation nobody wrote down.
            allNamed &= row.name() != null && !row.name().isBlank()
                    && !row.name().equals(row.id().substring("tacz:".length()));
        }
        check("they are all filed under the small-arms heading", allSmallArms);
        check("and every one has a designation rather than a bare id", allNamed);
        check("the 40mm grenade and the RPG are shaped charges",
                "small_arms_heat".equals(categoryOf(rows, "tacz:40mm"))
                        && "small_arms_heat".equals(categoryOf(rows, "tacz:rpg_rocket")));
        check("a rifle cartridge is kinetic",
                "small_arms_ap".equals(categoryOf(rows, "tacz:762x39")));
        check("the .50 BMG is written as its designation",
                "12.7×99 mm (.50 BMG)".equals(nameOf(rows, "tacz:50bmg")));
    }

    /**
     * The blast of an explosive cartridge against a vehicle it has already hit.
     *
     * <p>What cannot be checked here is the hit itself - that needs a game, a gun and a tank. What
     * can be checked is the decision the handler makes before it needs any of that: which rounds it
     * claims, and that the handler is wired to the event at all. An event method that loses its
     * annotation is the quiet kind of broken.
     */
    private static void explosions() throws Exception {
        System.out.println();
        System.out.println("=== a TACZ explosion against a vehicle ===");
        check("a rated warhead's blast is replaced by the armour hit",
                TaczExplosions.rated("tacz:40mm") && TaczExplosions.rated("tacz:rpg_rocket"));
        check("an unrated cartridge keeps TACZ's blast",
                !TaczExplosions.rated("tacz:not_a_cartridge"));
        check("and so does a null id", !TaczExplosions.rated(null));
        Method handler = TaczExplosions.class.getDeclaredMethod("onDetonate",
                net.minecraftforge.event.level.ExplosionEvent.Detonate.class);
        check("the detonate handler is subscribed",
                handler.isAnnotationPresent(net.minecraftforge.eventbus.api.SubscribeEvent.class));
    }

    /** @return the shelf id of the named row, or {@code null} when it is not in the list. */
    private static String categoryOf(List<AmmoIndex.Entry> rows, String id) {
        for (AmmoIndex.Entry row : rows) {
            if (row.id().equals(id)) {
                return row.category().id();
            }
        }
        return null;
    }

    /** @return the displayed name of the named row, or {@code null}. */
    private static String nameOf(List<AmmoIndex.Entry> rows, String id) {
        for (AmmoIndex.Entry row : rows) {
            if (row.id().equals(id)) {
                return row.name();
            }
        }
        return null;
    }

    /**
     * v44 -> v45 on a file that predates the table, carrying a figure of the player's own.
     *
     * <p>Two failures live here and only one of them is obvious: a file with no {@code tacz_rounds}
     * key at all must end up with the whole table, and an entry a player has already rated must come
     * back with <em>their</em> number rather than the shipped one.
     */
    private static void migration() throws Exception {
        System.out.println();
        System.out.println("=== a file written before the table existed ===");
        int shipped = defaultProfile().tacz_rounds.size();

        ArmorConfig.Profile older = read(write(defaultProfile()));
        older.tacz_rounds = new java.util.LinkedHashMap<>();
        older.tacz_rounds.put("tacz:50bmg", new ArmorConfig.Ammo(99.0F));
        upgrade(older);
        check("the rest of the table is filled in", older.tacz_rounds.size() == shipped);
        check("and the figure the player had already set is left exactly as it was",
                older.tacz_rounds.get("tacz:50bmg").penetration == 99.0F);

        ArmorConfig.Profile empty = read(write(defaultProfile()));
        empty.tacz_rounds = null;
        upgrade(empty);
        check("a file with no key at all gets the map as well as its contents",
                empty.tacz_rounds != null && empty.tacz_rounds.size() == shipped);
    }

    // --- locating things ---------------------------------------------------------------------

    /**
     * @return the directory the addon's own classes were compiled into, found on the classpath
     *         rather than assumed - the tools are run from wherever the caller happens to be.
     */
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

    /**
     * @return the gun mod's jar, found the same way {@code build.ps1} finds it and confirmed the same
     *         way too - by opening it and looking for a class only the gun mod has, because a second
     *         jar in mods/ shares the prefix and a name filter alone would pick whichever was written
     *         last.
     */
    private static Path taczJar() throws IOException {
        String root = System.getenv().getOrDefault("MC_ROOT", "E:\\.minecraft");
        Path mods = Path.of(root, "/versions/1.20.1-Forge_47.4.2/mods");
        if (!Files.isDirectory(mods)) {
            return null;
        }
        try (Stream<Path> list = Files.list(mods)) {
            for (Path candidate : list.filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .toList()) {
                String name = candidate.getFileName().toString().toLowerCase();
                if (!name.contains("tacz") || name.contains("mesh-loader")) {
                    continue;
                }
                try (ZipFile zip = new ZipFile(candidate.toFile())) {
                    if (zip.getEntry("com/tacz/guns/GunMod.class") != null) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private static void upgrade(ArmorConfig.Profile profile) throws Exception {
        Method method = ArmorConfig.class.getDeclaredMethod("upgrade", ArmorConfig.Profile.class);
        method.setAccessible(true);
        method.invoke(null, profile);
    }

    private static ArmorConfig.Profile defaultProfile() throws Exception {
        Method method = ArmorConfig.class.getDeclaredMethod("defaultProfile");
        method.setAccessible(true);
        return (ArmorConfig.Profile) method.invoke(null);
    }

    private static String write(ArmorConfig.Profile profile) {
        return ArmorConfig.configWriter().toJson(profile);
    }

    private static ArmorConfig.Profile read(String json) {
        return ArmorConfig.configReader().fromJson(json, ArmorConfig.Profile.class);
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
