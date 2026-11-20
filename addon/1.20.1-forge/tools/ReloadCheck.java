import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The pack's reload times, held against the autoloader cycle each vehicle is published with.
 *
 * <p><b>The unit is ticks, and that is the first thing to get wrong.</b>
 * {@code AbstractVehicleWeapon.tickReload} decrements {@code reloadTime} once per server tick, so
 * {@code reload.time} is in <b>ticks</b> (20 = 1 s), not milliseconds. The neighbouring
 * {@code shoot_interval} <i>is</i> milliseconds - it is compared against
 * {@code System.currentTimeMillis()} - and the two sitting side by side in the same file is exactly
 * how a value gets written in the wrong unit.
 *
 * <p><b>Every main gun loads one round per reload.</b> {@code reload()} sets {@code remainAmmo =
 * maxCapacity} in one go after {@code reloadTime} ticks, so a magazine above one is a burst. Since
 * 2026-11-16 every main-gun file in this pack has {@code max_capacity: 1}, which makes the figure
 * the autoloader cycle itself; a main gun whose capacity is not 1 fails below.
 *
 * <p><b>The table is the policy, not the check.</b> Every weapon file in the pack is read, and a
 * file whose stem is in neither {@link #MAIN_GUN} nor {@link #ACCESSORY} fails, so a weapon added
 * later cannot keep the engine's default 60-tick reload and pass silently. A row whose weapon file
 * has gone fails too, so the table cannot keep claiming a figure for something the pack no longer
 * ships. Adding a vehicle means adding its cycle to {@link #CYCLE}, its ammunition to
 * {@link #MAIN_GUN}, and a row to the README's reload table.
 *
 * <p><b>Accessories are recorded, not rated.</b> Machine guns, automatic grenade launchers and the
 * smoke discharger are belt- or box-fed and their "magazine" is a game abstraction, not an
 * autoloader. Their 100-tick reload is kept as it was - the base mod's own value - and recorded
 * here so that "unchanged" is a decision written down rather than an omission.
 *
 * <p>Usage: ReloadCheck [pack.zip]
 *        Defaults to the installed armor_pioneer pack - zip or folder, whichever shape it is in.
 */
public class ReloadCheck {

    /** One tick is 1/20 s, so ticks = seconds x 20. */
    private static final int TICKS_PER_SECOND = 20;

    /** One tick is 50 ms; {@code shoot_interval} is milliseconds, so this converts it back. */
    private static final int MS_PER_TICK = 50;

    /**
     * Published autoloader cycle, <b>seconds per round</b>, per vehicle model name. The source is
     * named beside the figure so that the next person can check it rather than guess:
     *
     * <ul>
     *   <li>VT-5 / Type 15 - 4 s. Its 105 mm rounds are one-piece, so unlike every other Chinese
     *       tank it uses a <b>bustle</b> autoloader; the War Thunder suggestion thread for the
     *       Bangladeshi VT-5 records the autoloader's reload rate as 4 s with 38 rounds stowed.</li>
     *   <li>VT-4 - <b>nine rounds per minute</b>, i.e. 6.67 s per round, from the leaked Thai
     *       VT-4 crew manual - the same document that supplies the 17 km/h reverse in
     *       {@code SpeedCheck}. The round figure is quantised to <b>134 ticks</b> (6.70 s, 8.96
     *       rounds/min): 133 ticks would be 6.65 s, and the engine spends one more tick before
     *       {@code startReload}, so 133 would edge over the manual's ceiling. Military Periscope's
     *       125-mm ZPT-98 entry quotes eight rounds per minute for the same gun; the manual is the
     *       more specific source and the one taken.</li>
     *   <li>K2 Black Panther - 4 s, i.e. <b>15 rounds per minute</b>: MilitaryFactory, ROTEM K2,
     *       "a 120 mm Rheinmetall smoothbore gun with an autoloader provided a rate-of-fire of up to
     *       15 rounds per minute". Some references give 10 rounds per minute (6 s); 15 is the
     *       published ceiling and is the figure this pack carries.</li>
     *   <li>Type 10 - 3.5 s. GlobalMilitary's Type 10 entry: the 120 mm JSW gun "is equipped with
     *       an autoloader that enables a firing interval of 3.5 seconds".</li>
     *   <li>Tianma-20 / Cheonma-2 - 7.5 s, the same <b>estimate</b> as the VT-4. The vehicle
     *       carries a 125 mm gun "derived from the Russian 2A46" (Wikipedia, Cheonma-2), i.e. the
     *       same carousel class, and North Korea publishes no rate of fire. Recorded as an
     *       estimate, not a quote.</li>
     *   <li>ZTZ-100 / 100式 - 4 s, the same figure as the VT-5's one-piece 105 mm autoloader.
     *       No rate of fire for the ZTZ-100 has been published; the two guns are the same calibre
     *       and the same class of bustle loader, so the VT-5's figure is the honest reference rather
     *       than the engine's 60-tick default.</li>
     * </ul>
     */
    private static final Map<String, Double> CYCLE = new LinkedHashMap<>();

    /** Pack weapon id (without the {@code armor_pioneer:} prefix) -> the vehicle whose cycle it uses. */
    private static final Map<String, String> MAIN_GUN = new LinkedHashMap<>();

    /**
     * Weapons that are not autoloaded, with the tick figure the pack keeps and why. Their value is
     * the base mod's own 100 ticks; these rows exist so the decision is explicit.
     */
    private static final Map<String, Integer> ACCESSORY = new LinkedHashMap<>();

    static {
        CYCLE.put("vt5", 4.0);
        // 9 rounds/min from the Thai manual; 6.7 s rounds to 134 ticks without exceeding it.
        CYCLE.put("vt4", 6.7);
        CYCLE.put("k2", 4.0);
        CYCLE.put("type10", 3.5);
        CYCLE.put("tianma20", 7.5);
        // Same one-piece 105 mm autoloader as the VT-5; the pack keeps the 80 ticks that go with it.
        CYCLE.put("ztz100", 4.0);
        // Manually loaded 120 mm L/44: about six seconds for a trained loader (10 rounds/min).
        CYCLE.put("leopard2a4", 6.0);

        // VT-5: one 105 mm gun, five rounds on the multi-weapon key plus the QLZ-04 on the
        // commander's station.
        MAIN_GUN.put("cannon_105mm", "vt5");
        MAIN_GUN.put("cannon_105mm_he", "vt5");
        MAIN_GUN.put("cannon_105mm_bunker", "vt5");
        MAIN_GUN.put("cannon_105mm_heat", "vt5");
        MAIN_GUN.put("cannon_105mm_atgm", "vt5");

        MAIN_GUN.put("cannon_125mm", "vt4");
        MAIN_GUN.put("cannon_125mm_he", "vt4");
        MAIN_GUN.put("cannon_125mm_heat", "vt4");
        MAIN_GUN.put("cannon_125mm_pele", "vt4");
        MAIN_GUN.put("cannon_125mm_atgm", "vt4");

        MAIN_GUN.put("cannon_120mm", "k2");
        MAIN_GUN.put("cannon_120mm_he", "k2");
        MAIN_GUN.put("cannon_120mm_heat", "k2");
        MAIN_GUN.put("cannon_120mm_kstam", "k2");

        MAIN_GUN.put("cannon_120mm_type10", "type10");
        MAIN_GUN.put("cannon_120mm_jm12", "type10");

        // The Leopard 2A4's Rh-120 L/44 rounds.
        MAIN_GUN.put("cannon_120mm_dm33", "leopard2a4");
        MAIN_GUN.put("cannon_120mm_dm12", "leopard2a4");
        MAIN_GUN.put("cannon_120mm_dm11", "leopard2a4");

        MAIN_GUN.put("cannon_125mm_dprk", "tianma20");
        MAIN_GUN.put("cannon_125mm_dprk_he", "tianma20");
        MAIN_GUN.put("cannon_125mm_dprk_heat", "tianma20");

        // The ZTZ-100's own 105 mm dart; the rest of its load list is the VT-5's 105 mm family,
        // already in the table above and checked against the VT-5 cycle.
        MAIN_GUN.put("cannon_105mm_ztz100", "ztz100");

        ACCESSORY.put("gun_7_62mm", 100);              // 120-round belt, a game abstraction
        ACCESSORY.put("type74_7_62mm", 100);           // the Type 10's 7.62mm coax
        ACCESSORY.put("m60e2_7_62mm", 100);            // the K2's 7.62mm coax
        ACCESSORY.put("pkt_7_62mm", 100);              // the Cheonma-20's 7.62mm coax
        ACCESSORY.put("gun_12_7mm", 100);              // 120-round belt
        ACCESSORY.put("gun_12_7mm_m2hb", 100);         // the Type 10's Type 90
        ACCESSORY.put("k6_12_7mm", 100);               // the K2's commander K6
        ACCESSORY.put("qlz_04_35mm", 100);             // 30-round drum
        ACCESSORY.put("ags_30", 100);                  // 30-round belt
        // The smoke banks use the base mod's launcher again, not pack weapons; SmokeCheck owns
        // the pack's side of that wiring.
        ACCESSORY.put("mg3_7_62mm", 100);                 // 120-round MG3 belt
    }

    private static final Pattern TIME = Pattern.compile("\"time\"\\s*:\\s*(\\d+)");
    private static final Pattern CAPACITY = Pattern.compile("\"max_capacity\"\\s*:\\s*(\\d+)");
    private static final Pattern INTERVAL = Pattern.compile("\"shoot_interval\"\\s*:\\s*(\\d+)");
    private static final Pattern PACK_ID = Pattern.compile("armor_pioneer:[a-z0-9_]+");

    private static int failures;

    public static void main(String[] args) throws Exception {
        Path pack = PackLocator.of(args, "armor_pioneer");
        System.out.println("pack: " + pack);
        System.out.println();
        System.out.println("=== published autoloader cycle vs the pack's reload.time ===");
        System.out.println("  reload.time is TICKS (20 = 1 s); every main gun loads one round");

        Set<String> seen = new TreeSet<>();
        int checked = 0;
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            checked = checkWeapons(zip, seen);
            checkVehicles(zip);
        }

        for (String id : MAIN_GUN.keySet()) {
            if (!seen.contains(id)) {
                check("the table's " + id + " exists in the pack", false);
            }
        }
        for (String id : ACCESSORY.keySet()) {
            if (!seen.contains(id)) {
                check("the table's " + id + " exists in the pack", false);
            }
        }
        System.out.println();
        System.out.println("  " + checked + " weapon(s) checked; " + MAIN_GUN.size()
                + " autoloaded and " + ACCESSORY.size() + " belt-fed rows in the table");
        if (failures == 0) {
            System.out.println("RESULT: every pack gun reloads at its published autoloader cycle");
        } else {
            System.out.println("RESULT: " + failures + " reload check(s) failed - see above");
            System.exit(1);
        }
    }

    private static int checkWeapons(ZipFile zip, Set<String> seen) {
        int checked = 0;
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (!name.startsWith("data/armor_pioneer/weapons/") || !name.endsWith(".json")) {
                continue;
            }
            String id = name.substring(name.lastIndexOf('/') + 1, name.length() - ".json".length());
            seen.add(id);
            String text = read(zip, entry);
            Matcher time = TIME.matcher(text);
            if (!time.find()) {
                check(id + ": has reload.time", false);
                continue;
            }
            int ticks = Integer.parseInt(time.group(1));
            Integer capacity = capacity(text);
            checked++;

            if (MAIN_GUN.containsKey(id)) {
                String vehicle = MAIN_GUN.get(id);
                Double seconds = CYCLE.get(vehicle);
                if (seconds == null) {
                    check(id + ": " + vehicle + " has a published cycle", false);
                    continue;
                }
                int expected = (int) Math.round(seconds * TICKS_PER_SECOND);
                boolean ok = ticks == expected;
                check(String.format(Locale.ROOT, "%s (%s): %d ticks = %.2f s = %s%s",
                                id, vehicle, ticks, ticks / (double) TICKS_PER_SECOND,
                                rate(ticks),
                                ok ? "" : "  EXPECTED " + expected + " ticks ("
                                        + fmt(seconds) + " s/round)"),
                        ok);
                if (capacity != null && capacity != 1) {
                    check(id + ": max_capacity " + capacity + " is 1 (one round per reload)",
                            false);
                }
                long intervalTicks = shootIntervalTicks(text);
                if (intervalTicks > ticks) {
                    System.out.println("  note  " + id + ": shoot_interval " + intervalTicks
                            + " ticks is longer than the reload - the rate is shot-limited");
                }
            } else if (ACCESSORY.containsKey(id)) {
                int expected = ACCESSORY.get(id);
                check(String.format(Locale.ROOT, "%s: %d ticks = %.2f s (belt/box change, kept)",
                        id, ticks, ticks / (double) TICKS_PER_SECOND), ticks == expected);
            } else {
                check(id + ": is recorded in ReloadCheck (add a MAIN_GUN or ACCESSORY row)", false);
            }
        }
        return checked;
    }

    /**
     * Every weapon id a vehicle references has to be a row in this table, or a new gun could be
     * mounted with no published figure behind it.
     */
    private static void checkVehicles(ZipFile zip) {
        System.out.println();
        System.out.println("=== every weapon a vehicle mounts is in the table ===");
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (!name.startsWith("data/armor_pioneer/vehicles/") || !name.endsWith(".json")) {
                continue;
            }
            String model = name.substring(name.lastIndexOf('/') + 1,
                    name.length() - ".json".length());
            Matcher id = PACK_ID.matcher(read(zip, entry));
            Set<String> ids = new TreeSet<>();
            while (id.find()) {
                ids.add(id.group().substring("armor_pioneer:".length()));
            }
            ids.remove("vehicle");               // the vehicle's own item id, not a weapon
            for (String weapon : ids) {
                check(model + " -> " + weapon, MAIN_GUN.containsKey(weapon)
                        || ACCESSORY.containsKey(weapon));
            }
            if (MAIN_GUN.containsValue(model)) {
                System.out.println("  note  " + model + ": cycle " + fmt(CYCLE.get(model))
                        + " s/round");
            }
        }
    }

    private static String read(ZipFile zip, ZipEntry entry) {
        try {
            return new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception exc) {
            throw new IllegalStateException("cannot read " + entry.getName(), exc);
        }
    }

    private static Integer capacity(String text) {
        Matcher m = CAPACITY.matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    private static long shootIntervalTicks(String text) {
        Matcher m = INTERVAL.matcher(text);
        return m.find() ? Long.parseLong(m.group(1)) / MS_PER_TICK : 0;
    }

    /** The sustained rate a one-round reload gives, in rounds per minute. */
    private static String rate(int ticks) {
        return String.format(Locale.ROOT, "%.1f rounds/min", 60.0 * TICKS_PER_SECOND / ticks);
    }

    private static String fmt(Double seconds) {
        return seconds == null ? "?"
                : java.math.BigDecimal.valueOf(seconds).stripTrailingZeros().toPlainString();
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
