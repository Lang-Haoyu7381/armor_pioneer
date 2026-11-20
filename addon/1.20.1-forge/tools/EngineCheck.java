import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ywzjvehicle.armor.ArmorConfig;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The packs' engine ratings and power-to-weight ratios, held against the published figures.
 *
 * <p><b>The two are separate numbers on purpose.</b> A power rating belongs to the engine; a
 * power-to-weight ratio belongs to a particular combat weight. Publishing both means a config can
 * say what the brochure says without pretending the pack's mass is the brochure's mass - the VT-5
 * is 1,000 hp at 33 tonnes (30.3 hp/t) and 1,000 hp at the pack's 42.9 tonnes (23.3 hp/t), and the
 * difference is the weight the pack models, not the engine. The table therefore carries the mass the
 * published ratio was quoted at, and checks the pair against it, so a typo in either figure is
 * caught even when the other one is right.
 *
 * <p><b>The table is the policy, not the check.</b> Every ground vehicle in a pack has to have a row
 * here and a non-zero pair in the addon's defaults, or - for a towed gun - an explicit row saying it
 * has no engine. A new vehicle cannot arrive with no engine and pass silently: it fails until
 * somebody writes down what it is. The HUD reads the same fields, so a missing row is a row the crew
 * never sees.
 *
 * <p>Two packs are checked: {@code armor_pioneer} (the seven modern vehicles) and
 * {@code tank_assault} (its twenty-one ground vehicles; the three aircraft are skipped because a
 * fighter's engine is not what this factor drives). The first pack may be named on the command line;
 * the second is found by namespace.
 *
 * <p>Usage: EngineCheck [armor_pioneer.zip]
 */
public class EngineCheck {

    /** A published pair is quoted to a tenth of a horsepower per tonne, so 0.15 is rounding, not error. */
    private static final float RATIO_TOLERANCE = 0.15F;

    /** The engine rating itself is a round published figure; a tenth of a horsepower is noise. */
    private static final float HORSEPOWER_TOLERANCE = 0.05F;

    /**
     * Published engine figures, in horsepower and horsepower per tonne, with the combat weight the
     * ratio is quoted at and the source:
     *
     * <ul>
     *   <li>VT-5 / Type 15 - 1,000 hp; 30.3 hp/t at the 33-tonne basic weight (Wikipedia, Type 15
     *       tank). The pack's description says 33.22 t, which is the same vehicle.</li>
     *   <li>VT-4 / MBT-3000 - 1,300 hp; 25.0 hp/t at 52 t (Army Technology, VT4).</li>
     *   <li>K2 Black Panther - 1,500 hp; 27.3 hp/t at 55 t (Hyundai Rotem).</li>
     *   <li>Type 10 - 1,200 hp; 25.0 hp/t at 48 t full loadout (Wikipedia, Type 10). The pack's
     *       mass is 44 t, the standard loadout, which is the same engine at a lighter weight.</li>
     *   <li>Tianma-20 / Cheonma-2 - 1,200 hp; 21.8 hp/t at 55 t (Wikipedia, Cheonma-2). No source
     *       publishes a list price; the power figures are the infobox pair.</li>
     *   <li>ZTZ-100 / 100式 - a hybrid-electric powertrain of about 1,500 hp, quoted at the
     *       40-tonne upper end of the public weight range (VPK.name, 2025; Wikipedia, ZTZ-100).
     *       The 1,500 hp figure is the one every report agrees on; the weight is not, so the ratio
     *       is the one implied by the pack's 40 t mass.</li>
     * </ul>
     */
    private record Published(float horsepower, float perTonne, float massTonnes, String source) {
    }

    private static final Map<String, Published> ARMOR_PIONEER = new LinkedHashMap<>();

    static {
        ARMOR_PIONEER.put("vt5", new Published(1000.0F, 30.3F, 33.0F,
                "Wikipedia, Type 15 tank"));
        ARMOR_PIONEER.put("vt4", new Published(1300.0F, 25.0F, 52.0F,
                "Army Technology, VT4 (MBT-3000)"));
        ARMOR_PIONEER.put("k2", new Published(1500.0F, 27.3F, 55.0F,
                "Hyundai Rotem, K2 Black Panther"));
        ARMOR_PIONEER.put("type10", new Published(1200.0F, 25.0F, 48.0F,
                "Wikipedia, Type 10 (48 t full loadout)"));
        ARMOR_PIONEER.put("tianma20", new Published(1200.0F, 21.8F, 55.0F,
                "Wikipedia, Cheonma-2 (1,200 hp diesel, 55 t)"));
        ARMOR_PIONEER.put("ztz100", new Published(1500.0F, 37.5F, 40.0F,
                "VPK.name / Wikipedia, ZTZ-100 (hybrid ~1,500 hp, 40 t)"));
        ARMOR_PIONEER.put("leopard2a4", new Published(1500.0F, 27.2F, 55.2F,
                "MTU MB 873, Leopard 2A4 (1,500 hp; 55.2 t)"));
    }

    /**
     * The Tank Assault ground vehicles. The pair is the engine's rating and the published ratio at
     * the quoted combat weight; the packs' own masses are printed beside it because they are not
     * always the quoted weight. The two towed guns are rows of their own with no engine at all.
     *
     * <p>Sources are the type's Wikipedia infobox except where noted; these are 1940s vehicles whose
     * engine and weight figures are not in dispute.
     */
    private static final Map<String, Published> TANK_ASSAULT = new LinkedHashMap<>();

    static {
        TANK_ASSAULT.put("a22f", new Published(350.0F, 8.6F, 40.7F,
                "Wikipedia, Churchill tank (Bedford Twin-Six, 350 hp; 40.7 t)"));
        TANK_ASSAULT.put("matilda_mk1", new Published(70.0F, 6.3F, 11.2F,
                "Wikipedia, Matilda I (Ford V8, 70 hp; 11.2 t)"));
        TANK_ASSAULT.put("valentine_mk2", new Published(131.0F, 8.2F, 16.0F,
                "Wikipedia, Valentine tank (AEC A190 diesel, 131 hp; 16 t)"));
        TANK_ASSAULT.put("panzer4h", new Published(300.0F, 12.0F, 25.0F,
                "Wikipedia, Panzer IV (Maybach HL 120 TRM, 300 PS; 25 t)"));
        TANK_ASSAULT.put("panther", new Published(700.0F, 15.6F, 44.8F,
                "Wikipedia, Panther tank (Maybach HL 230 P30, 700 PS; 44.8 t)"));
        TANK_ASSAULT.put("tiger", new Published(650.0F, 11.4F, 57.0F,
                "Wikipedia, Tiger I (Maybach HL 210 P45, 650 PS; 57 t)"));
        TANK_ASSAULT.put("ferdinand", new Published(600.0F, 9.2F, 65.0F,
                "Wikipedia, Elefant (2 x Maybach HL 120 TRM, 600 PS; 65 t)"));
        TANK_ASSAULT.put("sturmtiger", new Published(700.0F, 10.8F, 65.0F,
                "Wikipedia, Sturmtiger (Maybach HL 230 P45, 700 PS; 65 t)"));
        TANK_ASSAULT.put("sdkfz251", new Published(100.0F, 11.8F, 8.5F,
                "Wikipedia, Sd.Kfz. 251 (Maybach HL 42, 100 PS; 8.5 t)"));
        TANK_ASSAULT.put("sdkfz2", new Published(36.0F, 30.0F, 1.2F,
                "Wikipedia, Sd.Kfz. 2 (Opel 1.5 L, 36 PS; 1.2 t)"));
        TANK_ASSAULT.put("bmw_r75", new Published(26.0F, 37.1F, 0.7F,
                "Wikipedia, BMW R75 (26 hp; 0.7 t with sidecar)"));
        TANK_ASSAULT.put("type82", new Published(25.0F, 21.6F, 1.16F,
                "Wikipedia, Volkswagen Kübelwagen (25 PS; 1.16 t)"));
        TANK_ASSAULT.put("bt7v", new Published(400.0F, 28.8F, 13.9F,
                "Wikipedia, BT-7 (M-17T, 400 hp; 13.9 t)"));
        TANK_ASSAULT.put("t26b", new Published(90.0F, 9.5F, 9.5F,
                "Wikipedia, T-26 (90 hp; 9.5 t)"));
        TANK_ASSAULT.put("kv1mod1942", new Published(600.0F, 12.8F, 47.0F,
                "Wikipedia, Kliment Voroshilov tank (V-2K, 600 hp; 47 t)"));
        TANK_ASSAULT.put("t3485", new Published(500.0F, 15.6F, 32.0F,
                "Wikipedia, T-34 (V-2-34, 500 hp; 32 t)"));
        TANK_ASSAULT.put("t44a", new Published(500.0F, 15.7F, 31.8F,
                "Wikipedia, T-44 (V-2-44, 500 hp; 31.8 t)"));
        TANK_ASSAULT.put("su122", new Published(500.0F, 16.2F, 30.9F,
                "Wikipedia, SU-122 (V-2, 500 hp; 30.9 t)"));
        TANK_ASSAULT.put("p40", new Published(330.0F, 12.7F, 26.0F,
                "Wikipedia, P26/40 tank (Fiat SPA 15TB, 330 hp; 26 t)"));
        // Towed: no engine, and the addon must agree rather than show a made-up figure.
        TANK_ASSAULT.put("qf17pdr", new Published(0.0F, 0.0F, 0.0F,
                "towed anti-tank gun - no engine"));
        TANK_ASSAULT.put("qf25", new Published(0.0F, 0.0F, 0.0F,
                "towed field gun - no engine"));
    }

    private static int failures;
    private static int checked;

    public static void main(String[] args) throws Exception {
        Path armorPack = PackLocator.of(args, "armor_pioneer");
        System.out.println("pack: " + armorPack);

        Method defaultProfile = ArmorConfig.class.getDeclaredMethod("defaultProfile");
        defaultProfile.setAccessible(true);
        ArmorConfig.Profile defaults = (ArmorConfig.Profile) defaultProfile.invoke(null);

        System.out.println();
        System.out.println("=== armor_pioneer: published engine vs the addon's defaults ===");
        System.out.println("  vehicle      hp      hp/t   quoted at   source");
        checkPack("armor_pioneer", armorPack, ARMOR_PIONEER, defaults, false);

        Path groundPack = PackLocator.find("tank_assault");
        System.out.println();
        System.out.println("=== tank_assault: published engine vs the addon's defaults ===");
        System.out.println("  vehicle      hp      hp/t   quoted at   source");
        checkPack("tank_assault", groundPack, TANK_ASSAULT, defaults, true);

        System.out.println();
        System.out.println("=== the physical effect ===");
        float reference = ArmorConfig.engineReferenceHpPerTon();
        float exponent = ArmorConfig.enginePowerExponent();
        check(String.format(java.util.Locale.ROOT,
                        "the reference is %.1f hp/t", reference), reference > 0.0F);
        check(String.format(java.util.Locale.ROOT,
                        "the exponent is %.2f (1 is the physical law, 0 disables)", exponent),
                exponent >= 0.0F);
        check("a vehicle at the reference gets x1.00",
                Math.abs(ArmorConfig.enginePowerScale(1000.0F, reference, 1000.0F) - 1.0F)
                        <= 1.0E-4F);
        check("a vehicle with no engine figure gets x1.00 (no effect outside the config)",
                Math.abs(ArmorConfig.enginePowerScale(0.0F, 0.0F, 50000.0F) - 1.0F)
                        <= 1.0E-4F);
        check("a very high ratio is clamped at x" + ArmorConfig.MAX_ENGINE_POWER_SCALE,
                Math.abs(ArmorConfig.enginePowerScale(1000.0F, reference * 100.0F, 1000.0F)
                        - ArmorConfig.MAX_ENGINE_POWER_SCALE) <= 1.0E-4F);
        check("a very low ratio is clamped at x" + ArmorConfig.MIN_ENGINE_POWER_SCALE,
                Math.abs(ArmorConfig.enginePowerScale(1000.0F, reference / 100.0F, 1000.0F)
                        - ArmorConfig.MIN_ENGINE_POWER_SCALE) <= 1.0E-4F);
        check("feeding horsepower without hp/t derives the ratio from the mass",
                Math.abs(ArmorConfig.enginePowerScale(1000.0F, 0.0F, 33000.0F)
                        - ArmorConfig.enginePowerScale(1000.0F, 1000.0F / 33.0F, 33000.0F))
                        <= 1.0E-4F);

        System.out.println();
        System.out.println("  " + checked + " vehicle(s) checked across "
                + (ARMOR_PIONEER.size() + TANK_ASSAULT.size()) + " engine figures");
        if (failures == 0) {
            System.out.println("RESULT: every pack vehicle carries its published engine and "
                    + "power-to-weight ratio, the pair agrees with the weight it was quoted at, "
                    + "and hp/t yields a bounded acceleration factor");
        } else {
            System.out.println("RESULT: " + failures + " engine check(s) failed - see above");
            System.exit(1);
        }
    }

    /**
     * Holds one pack's vehicles against its table.
     *
     * @param skipAircraft when true, a fixed- or rotary-wing vehicle is left out: the engine factor
     *                     drives {@code getSuspensionPowerScale}, which aircraft do not use, and a
     *                     fighter's engine rating is a different question from a tank's.
     */
    private static void checkPack(String namespace, Path pack, Map<String, Published> table,
                                  ArmorConfig.Profile defaults, boolean skipAircraft)
            throws Exception {
        Set<String> seen = new TreeSet<>();
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith("data/" + namespace + "/vehicles/") || !name.endsWith(".json")) {
                    continue;
                }
                String model = name.substring(name.lastIndexOf('/') + 1,
                        name.length() - ".json".length());

                JsonObject vehicle = JsonParser.parseString(new String(
                                zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8))
                        .getAsJsonObject();
                String type = vehicle.has("type") ? vehicle.get("type").getAsString() : "";
                if (skipAircraft && type.contains("wing")) {
                    continue;
                }
                seen.add(model);

                Published published = table.get(model);
                if (published == null) {
                    check(model + ": an engine rating is recorded", false);
                    System.out.println("       add it to EngineCheck." + namespace.toUpperCase()
                            + " and the README's engine table");
                    continue;
                }
                ArmorConfig.VehicleArmor armor = defaults.vehicles.get(namespace + ":" + model);
                if (armor == null) {
                    check(model + ": an armour profile exists to carry the engine", false);
                    continue;
                }
                checked++;

                float mass = vehicle.has("physics_info")
                        ? vehicle.getAsJsonObject("physics_info").get("mass").getAsFloat() / 1000.0F
                        : Float.NaN;

                if (published.horsepower() <= 0.0F) {
                    // No engine, and the addon must not invent one. Both fields at zero is what
                    // keeps the HUD's power row off and the acceleration factor at 1.0.
                    System.out.printf(java.util.Locale.ROOT,
                            "  %-14s %6s  %6s   %5s t   %s%n",
                            model, "-", "-", "-", published.source());
                    check(model + ": no engine, and the addon has none either",
                            armor.engine_horsepower == 0.0F && armor.horsepower_per_ton == 0.0F);
                    continue;
                }

                // The pack's own mass is printed beside the quoted one because the two are not
                // expected to agree, and a reader should be able to see by how much they differ
                // rather than wondering why the ratio does not match the pack.
                boolean horsepowerOk = Math.abs(armor.engine_horsepower
                        - published.horsepower()) <= HORSEPOWER_TOLERANCE;
                boolean ratioOk = Math.abs(armor.horsepower_per_ton
                        - published.perTonne()) <= RATIO_TOLERANCE;
                System.out.printf(java.util.Locale.ROOT,
                        "  %-14s %6.0f  %6.1f   %5.1f t   %s%n",
                        model, armor.engine_horsepower, armor.horsepower_per_ton,
                        published.massTonnes(), published.source());
                check(String.format(java.util.Locale.ROOT,
                                "%s: engine %.0f hp (published %.0f)",
                                model, armor.engine_horsepower, published.horsepower()),
                        horsepowerOk);
                check(String.format(java.util.Locale.ROOT,
                                "%s: %.1f hp/t (published %.1f; pack mass %.1f t, which would give "
                                        + "%.1f)",
                                model, armor.horsepower_per_ton, published.perTonne(), mass,
                                Float.isNaN(mass) ? 0.0F
                                        : armor.engine_horsepower / Math.max(0.001F, mass)),
                        ratioOk);
                // The pair has to agree with the weight it was quoted at, or one of the two numbers
                // is a typo that the check above would not see: 1,000 hp and 25.0 hp/t are each
                // plausible on their own and cannot both be right at 33 tonnes.
                float implied = published.horsepower() / published.massTonnes();
                check(String.format(java.util.Locale.ROOT,
                                "%s: %.0f hp at %.1f t is %.1f hp/t (table says %.1f)",
                                model, published.horsepower(), published.massTonnes(), implied,
                                published.perTonne()),
                        Math.abs(implied - published.perTonne()) <= 0.15F);

                float reference = ArmorConfig.engineReferenceHpPerTon();
                float exponent = ArmorConfig.enginePowerExponent();
                float scale = ArmorConfig.enginePowerScale(armor.engine_horsepower,
                        armor.horsepower_per_ton, mass * 1000.0F);
                float expected = (float) Math.pow(armor.horsepower_per_ton / reference, exponent);
                expected = Math.max(ArmorConfig.MIN_ENGINE_POWER_SCALE,
                        Math.min(ArmorConfig.MAX_ENGINE_POWER_SCALE, expected));
                System.out.printf(java.util.Locale.ROOT,
                        "           acceleration x%.3f (reference %.1f hp/t, exponent %.2f)%n",
                        scale, reference, exponent);
                check(String.format(java.util.Locale.ROOT,
                                "%s: hp/t applies its physical factor (x%.2f)", model, scale),
                        Math.abs(scale - expected) <= 1.0E-4F);
            }
        }

        for (String model : table.keySet()) {
            if (!seen.contains(model)) {
                check(namespace + ": the engine table's " + model + " exists in the pack", false);
            }
        }
        System.out.println("  " + namespace + ": "
                + (seen.isEmpty() ? 0 : seen.size()) + " vehicle(s) in the pack");
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
