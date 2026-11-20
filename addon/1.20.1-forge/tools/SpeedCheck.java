import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The pack's maximum road speeds, held against the figures the vehicles are published with.
 *
 * <p><b>The unit is the thing worth writing down.</b> {@code max_speed_forward} is a delta movement
 * in <b>blocks per tick</b> - {@code TrackedVehicle.tickMove} adds it to the entity's velocity and
 * the physics engine integrates that - and the base mod's speedometer multiplies the velocity by
 * <b>72</b> to print km/h. So one block per tick is 20 blocks a second, which is 20 m/s or
 * <b>72 km/h</b>, and the conversion is one line: {@code blocks/tick = km/h / 72}. A value of 0.4
 * is 28.8 km/h, which is not a tank; 70 km/h is 0.972222.
 *
 * <p><b>The table is the policy, not the check.</b> The user's rule is that every vehicle in this
 * pack carries its published maximum road speed, and that future vehicles do too. So every vehicle
 * JSON in the pack is read, and a file whose name is not in {@link #PUBLISHED} or {@link #REVERSE}
 * fails - a new vehicle cannot be added with the engine's default 0.5 or a copied 0.4 and pass
 * silently. Adding one means adding its published figures here and to the README's table.
 *
 * <p><b>Reverse too, from a source rather than a ratio.</b> The forward figure is the published
 * maximum road speed; the reverse is recorded only where a source gives one. The VT-4's 17 km/h is
 * in the leaked Thai manual, the VT-5's 33 km/h is the War Thunder stat card's realistic figure
 * (the same card gives the VT-4 70/17, which the leak confirms), and the K2's 32 km/h is the
 * Multicrew Tank Combat database's figure - Hyundai Rotem publishes only the forward speeds. A
 * vehicle may carry a {@code null} reverse entry when no figure exists; that is a recorded "not
 * published", not a silent default.
 *
 * <p>Usage: SpeedCheck [pack.zip]
 *        Defaults to the newest armor_pioneer zip under the 1.20.1 instance's pack folder.
 */
public class SpeedCheck {

    /** A block per tick, in km/h: 20 ticks a second, a block is a metre. */
    private static final double KMH_PER_BLOCK_PER_TICK = 72.0;

    /** The pack is written from whole-kilometre figures, so a rounding of a tenth is not a defect. */
    private static final double TOLERANCE_KMH = 0.5;

    /**
     * Published maximum road speed, in km/h, per vehicle file name.
     *
     * <p>A row for every vehicle in the pack, and every future one. The source is named beside the
     * figure so the next person can check it rather than guess:
     *
     * <ul>
     *   <li>VT-5 / Type 15 - 70 km/h (Wikipedia, Type 15 tank: maximum speed 70 km/h).</li>
     *   <li>VT-4 - 70 km/h (Army Technology, VT4 (MBT-3000): maximum road speed 70 km/h).</li>
     *   <li>K2 Black Panther - 70 km/h on a paved road, 50 km/h off-road (Hyundai Rotem). The base
     *       mod has one maximum-speed field and no terrain term, so the paved figure is the one the
     *       game can express; the off-road figure is recorded in the README and not modelled.</li>
     *   <li>Type 10 - 70 km/h (the JGSDF's published maximum road speed). No reverse figure is
     *       published, so its reverse row is a recorded null rather than a guess.</li>
     *   <li>Tianma-20 / Cheonma-2 - 65 km/h, the figure in the public analysis of the vehicle
     *       (Army Recognition, 2024); no official Korean figure exists. The reverse is likewise
     *       unpublished and recorded as null.</li>
     *   <li>ZTZ-100 / 100式 - 85 km/h, the figure reported with its 1,500 hp hybrid drive
     *       (VPK.name, 2025); other reports quote the same 65-85 km/h band. The reverse is
     *       unpublished and recorded as null.</li>
     * </ul>
     */
    private static final Map<String, Double> PUBLISHED = new LinkedHashMap<>();

    /**
     * Published maximum reverse speed, in km/h, per vehicle file name.
     *
     * <p>A {@code null} value records "no published figure" and is printed as a note rather than
     * checked - but the row still has to exist, so a new vehicle cannot be added without somebody
     * deciding what its reverse is.
     */
    private static final Map<String, Double> REVERSE = new LinkedHashMap<>();

    static {
        PUBLISHED.put("vt5", 70.0);
        PUBLISHED.put("vt4", 70.0);
        PUBLISHED.put("k2", 70.0);
        PUBLISHED.put("type10", 70.0);
        PUBLISHED.put("tianma20", 65.0);
        // 85 km/h: VPK.name's report on the 1,500 hp hybrid ZTZ-100. The pack's 1.18 blocks/tick
        // is 84.96, which is the same figure rounded.
        PUBLISHED.put("ztz100", 85.0);
        // 70 km/h: the Leopard 2's published maximum road speed (Wikipedia's infobox figure,
        // shared by the A4 and the later marks).
        PUBLISHED.put("leopard2a4", 70.0);

        // 33 km/h: War Thunder's VT5 stat card, backward 33 (stock) / 36 (upgraded).
        REVERSE.put("vt5", 33.0);
        // 17 km/h: the leaked Thai VT-4 manual, and the MTC database's VT4 page.
        REVERSE.put("vt4", 17.0);
        // 32 km/h: the MTC database's K2 page; Hyundai Rotem publishes no reverse figure.
        REVERSE.put("k2", 32.0);
        // 31 km/h: the Leopard 2A4's published maximum reverse speed.
        REVERSE.put("leopard2a4", 31.0);
        // Neither the newer tanks nor the ZTZ-100 has a published reverse speed.
        REVERSE.put("type10", null);
        REVERSE.put("tianma20", null);
        REVERSE.put("ztz100", null);
    }

    private static int failures;

    public static void main(String[] args) throws Exception {
        Path pack = pack(args);
        System.out.println("pack: " + pack);
        System.out.println();
        System.out.println("=== published maximum road speed vs the pack ===");
        System.out.println("  1 block/tick = 72 km/h (20 ticks a second, 1 block = 1 m)");

        int checked = 0;
        Set<String> seen = new TreeSet<>();
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith("data/armor_pioneer/vehicles/") || !name.endsWith(".json")) {
                    continue;
                }
                String model = name.substring(name.lastIndexOf('/') + 1,
                        name.length() - ".json".length());
                seen.add(model);
                Double published = PUBLISHED.get(model);
                if (published == null) {
                    check(model + ": a published maximum road speed is recorded", false);
                    System.out.println("       add it to SpeedCheck.PUBLISHED and the README's "
                            + "speed table");
                    continue;
                }
                JsonObject vehicle = JsonParser.parseString(new String(
                                zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8))
                        .getAsJsonObject();
                if (!vehicle.has("attributes")
                        || !vehicle.getAsJsonObject("attributes").has("max_speed_forward")) {
                    check(model + ": has attributes.max_speed_forward", false);
                    continue;
                }
                double blocksPerTick = vehicle.getAsJsonObject("attributes")
                        .get("max_speed_forward").getAsDouble();
                double kmh = blocksPerTick * KMH_PER_BLOCK_PER_TICK;
                checked++;
                check(String.format(java.util.Locale.ROOT,
                                "%s: %.6f b/t = %.1f km/h (published %.0f km/h)",
                                model, blocksPerTick, kmh, published),
                        Math.abs(kmh - published) <= TOLERANCE_KMH);

                if (!REVERSE.containsKey(model)) {
                    check(model + ": a published reverse speed is recorded", false);
                    System.out.println("       add it to SpeedCheck.REVERSE (null with a note if "
                            + "unpublished)");
                    continue;
                }
                Double reverse = REVERSE.get(model);
                if (reverse == null) {
                    System.out.println("  note  " + model + ": no published reverse speed - "
                            + "not checked");
                    continue;
                }
                if (!vehicle.getAsJsonObject("attributes").has("max_speed_backward")) {
                    check(model + ": has attributes.max_speed_backward", false);
                    continue;
                }
                double backBlocksPerTick = vehicle.getAsJsonObject("attributes")
                        .get("max_speed_backward").getAsDouble();
                double backKmh = backBlocksPerTick * KMH_PER_BLOCK_PER_TICK;
                check(String.format(java.util.Locale.ROOT,
                                "%s reverse: %.6f b/t = %.1f km/h (published %.0f km/h)",
                                model, backBlocksPerTick, backKmh, reverse),
                        Math.abs(backKmh - reverse) <= TOLERANCE_KMH);
            }
        }

        // A row whose vehicle has gone is the other half of the table being a record: it would
        // otherwise keep claiming a speed for a file the pack no longer has.
        for (String model : PUBLISHED.keySet()) {
            if (!seen.contains(model)) {
                check("the table's " + model + " exists in the pack", false);
            }
        }
        for (String model : REVERSE.keySet()) {
            if (!seen.contains(model)) {
                check("the reverse table's " + model + " exists in the pack", false);
            }
        }

        System.out.println();
        System.out.println("  " + checked + " vehicle(s) checked; " + PUBLISHED.size()
                + " forward and " + REVERSE.size() + " reverse figures in the table");
        if (failures == 0) {
            System.out.println("RESULT: every pack vehicle's forward and reverse speeds are their "
                    + "published figures");
        } else {
            System.out.println("RESULT: " + failures + " speed check(s) failed - see above");
            System.exit(1);
        }
    }

    /**
     * @return the pack named on the command line, or the installed {@code armor_pioneer} pack -
     *         zip or folder, whichever shape it is in.
     */
    private static Path pack(String[] args) throws Exception {
        return PackLocator.of(args, "armor_pioneer");
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
