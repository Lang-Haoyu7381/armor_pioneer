import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Checks the three Armor Pioneer vehicle recipes against the tanks' public unit prices.
 *
 * <p>A recipe is the only place a pack says what a vehicle is worth, and three identical recipes -
 * which is what the pack shipped - teach the player that a 33-tonne light tank and a K2 cost the
 * same. The ratios here are the published list prices: VT-4 at $4.9M (Wikipedia's infobox unit
 * cost), K2 at $8.5M (the 2009 constant-USD figure for the first production batch), and VT-5 at
 * $3.5M. The VT-5 figure is an estimate, not a quote: no export price has been published, so it is
 * read from its low-cost positioning and its 33-36 tonne class, and the tool says so rather than
 * pretending otherwise.
 *
 * <p>VT-4 is deliberately the same recipe as the base mod's own MBT: that keeps the new prices on
 * the economy the rest of the game already uses instead of inventing a second scale. Every other
 * material and the printing time is {@code base * price / 4.9}, rounded to the nearest item.
 *
 * <p>Both pack shapes are checked. The {@code recipes/} folder is the 1.20.1 form; the
 * {@code recipe/} folder is the 1.21 form, which uses {@code c:} tags, an {@code ingredient}
 * wrapper and item components. They must agree, or one Minecraft version silently sells a
 * different tank.
 */
public class RecipeCheck {

    private static final String NAMESPACE = "armor_pioneer";

    private record Tank(String vehicle, double priceMillion, boolean priceIsEstimate) {
    }

    private static final List<Tank> TANKS = List.of(
            new Tank("vt5", 3.5, true),
            new Tank("vt4", 4.9, false),
            new Tank("k2", 8.5, false));

    /** The default pack's MBT recipe, which a $4.9M tank reproduces exactly. */
    private static final double BASE_PRICE = 4.9;
    private static final int[] BASE_MATERIALS = {160, 80, 64, 16, 8, 12, 24, 16};
    private static final int BASE_TIME = 1500;

    /** Only the first two are tags; writing an item id under "tag" is an empty tag. */
    private static final boolean[] IS_TAG = {true, true, false, false, false, false, false, false};
    private static final String[] ITEM_IDS = {
            "minecraft:redstone", "minecraft:glass_pane", "minecraft:dispenser",
            "minecraft:diamond", "minecraft:gold_ingot", "minecraft:lapis_lazuli"};
    private static final String[] LEGACY_TAGS = {"forge:ingots/iron", "forge:ingots/copper"};
    private static final String[] MODERN_TAGS = {"c:ingots/iron", "c:ingots/copper"};

    private static int failures;

    public static void main(String[] args) throws Exception {
        Path pack = PackLocator.of(args, NAMESPACE);
        System.out.println("pack: " + pack);
        System.out.println();
        System.out.println("=== unit price -> printing recipe ===");

        List<Integer> totals = new ArrayList<>();
        int[] vt4Legacy = null;
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            for (Tank tank : TANKS) {
                JsonObject legacy = read(zip, "data/" + NAMESPACE + "/recipes/"
                        + tank.vehicle() + ".json");
                JsonObject modern = read(zip, "data/" + NAMESPACE + "/recipe/"
                        + tank.vehicle() + ".json");
                if (legacy == null || modern == null) {
                    check(tank.vehicle() + ": both recipe forms exist", false);
                    totals.add(0);
                    continue;
                }

                double ratio = tank.priceMillion() / BASE_PRICE;
                int[] want = new int[BASE_MATERIALS.length];
                for (int i = 0; i < want.length; i++) {
                    want[i] = Math.max(1, (int) Math.round(BASE_MATERIALS[i] * ratio));
                }
                int wantTime = (int) Math.round(BASE_TIME * ratio);

                int[] got = counts(legacy);
                int[] gotModern = counts(modern);
                int time = legacy.get("printingTime").getAsInt();
                int timeModern = modern.get("printingTime").getAsInt();
                totals.add(sum(got));
                if ("vt4".equals(tank.vehicle())) {
                    vt4Legacy = got;
                }

                String price = tank.priceIsEstimate()
                        ? String.format(Locale.ROOT, "$%.1fM (est.)", tank.priceMillion())
                        : String.format(Locale.ROOT, "$%.1fM", tank.priceMillion());
                System.out.printf("  %-4s %-13s x%.3f  %s  %s%n",
                        tank.vehicle(), price, ratio, Arrays.toString(got),
                        String.format(Locale.ROOT, "%.1fs", time / 20.0));

                check(tank.vehicle() + ": prints " + NAMESPACE + ":" + tank.vehicle(),
                        resultId(legacy).equals(NAMESPACE + ":" + tank.vehicle())
                                && resultId(modern).equals(NAMESPACE + ":" + tank.vehicle()));
                check(tank.vehicle() + ": eight materials", got.length == 8);
                check(tank.vehicle() + ": each material is a count plus an ingredient",
                        shapeOk(legacy, false) && shapeOk(modern, true));
                check(tank.vehicle() + ": tags are tags and items are items",
                        kindsOk(legacy, false) && kindsOk(modern, true));
                check(tank.vehicle() + ": counts follow the price ratio", within(got, want));
                check(tank.vehicle() + ": printing time follows the price ratio",
                        Math.abs(time - wantTime) <= 2);
                check(tank.vehicle() + ": the 1.21 recipe uses the same counts",
                        Arrays.equals(got, gotModern));
                check(tank.vehicle() + ": the 1.21 printing time matches", time == timeModern);
                check(tank.vehicle() + ": the 1.21 result carries the vehicle id",
                        resultId(modern).equals(NAMESPACE + ":" + tank.vehicle()));
            }

            JsonObject vt4 = read(zip, "data/" + NAMESPACE + "/recipes/vt4.json");
            JsonObject vt4Modern = read(zip, "data/" + NAMESPACE + "/recipe/vt4.json");
            check("the 1.20.1 form still uses forge: tags",
                    firstIngredient(vt4, false).equals("forge:ingots/iron"));
            check("the 1.21 form uses c: tags",
                    firstIngredient(vt4Modern, true).equals("c:ingots/iron"));
        }

        System.out.println();
        check("the K2 costs more than the VT-4", totals.get(2) > totals.get(1));
        check("the VT-4 costs more than the VT-5", totals.get(1) > totals.get(0));
        check("the VT-4 recipe is the base pack's MBT recipe",
                vt4Legacy != null && Arrays.equals(vt4Legacy, BASE_MATERIALS));

        System.out.println();
        if (failures > 0) {
            System.out.println("RESULT: " + failures + " recipe check(s) failed");
            System.exit(1);
        }
        System.out.println("RESULT: the three tanks are priced by their published unit costs "
                + "(VT-5 estimated), and both recipe forms agree");
    }

    private static int[] counts(JsonObject recipe) {
        JsonArray materials = recipe.getAsJsonArray("materials");
        int[] counts = new int[materials.size()];
        for (int i = 0; i < counts.length; i++) {
            counts[i] = materials.get(i).getAsJsonObject().get("count").getAsInt();
        }
        return counts;
    }

    /** @return whether every material has a count and exactly one ingredient spelling. */
    private static boolean shapeOk(JsonObject recipe, boolean modern) {
        for (var element : recipe.getAsJsonArray("materials")) {
            JsonObject material = element.getAsJsonObject();
            if (!material.has("count")) {
                return false;
            }
            if (modern) {
                if (!material.has("ingredient") || !material.get("ingredient").isJsonObject()) {
                    return false;
                }
                JsonObject ingredient = material.getAsJsonObject("ingredient");
                if (!ingredient.has("tag") && !ingredient.has("item")) {
                    return false;
                }
            } else if (!material.has("tag") && !material.has("item")) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return whether each material names a tag where a tag is wanted and an item where an item is.
     *
     * <p>This is the check the first version of the priced recipes needed: it wrote
     * {@code "tag":"minecraft:redstone"}, which parses as an empty tag rather than failing, and
     * the machine showed "Empty Tag: m..." with no icon. JSON validity cannot catch that; the
     * kind and the id have to be checked together.
     */
    private static boolean kindsOk(JsonObject recipe, boolean modern) {
        JsonArray materials = recipe.getAsJsonArray("materials");
        if (materials.size() != IS_TAG.length) {
            return false;
        }
        int itemIndex = 0;
        int tagIndex = 0;
        for (int i = 0; i < materials.size(); i++) {
            JsonObject material = materials.get(i).getAsJsonObject();
            JsonObject ingredient = modern ? material.getAsJsonObject("ingredient") : material;
            if (IS_TAG[i]) {
                if (!ingredient.has("tag") || ingredient.has("item")) {
                    return false;
                }
                String want = modern ? MODERN_TAGS[tagIndex] : LEGACY_TAGS[tagIndex];
                if (!want.equals(ingredient.get("tag").getAsString())) {
                    return false;
                }
                tagIndex++;
            } else {
                if (!ingredient.has("item") || ingredient.has("tag")) {
                    return false;
                }
                if (!ITEM_IDS[itemIndex].equals(ingredient.get("item").getAsString())) {
                    return false;
                }
                itemIndex++;
            }
        }
        return true;
    }

    private static String firstIngredient(JsonObject recipe, boolean modern) {
        JsonObject first = recipe.getAsJsonArray("materials").get(0).getAsJsonObject();
        JsonObject ingredient = modern ? first.getAsJsonObject("ingredient") : first;
        return ingredient.get("tag").getAsString();
    }

    private static boolean within(int[] got, int[] want) {
        if (got.length != want.length) {
            return false;
        }
        for (int i = 0; i < got.length; i++) {
            if (Math.abs(got[i] - want[i]) > 1) {
                return false;
            }
        }
        return true;
    }

    private static int sum(int[] values) {
        int total = 0;
        for (int value : values) {
            total += value;
        }
        return total;
    }

    private static String resultId(JsonObject recipe) {
        JsonObject result = recipe.getAsJsonObject("result");
        if (result.has("nbt")) {
            return result.getAsJsonObject("nbt").get("YwzjVehicleId").getAsString();
        }
        return result.getAsJsonObject("components").getAsJsonObject("minecraft:custom_data")
                .get("YwzjVehicleId").getAsString();
    }

    private static JsonObject read(ZipFile zip, String name) throws Exception {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
