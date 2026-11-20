import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ywzjvehicle.armor.ArmorConfig;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The engine exhaust, held to the pack and to the one vehicle that must be able to turn it off.
 *
 * <p>The base mod's exhaust is not a part and has no name of its own: {@code
 * TrackedVehicle.tickParticle} calls {@code ParticleUtil.spawnEngineSmoke} with {@code
 * energy_info.engine_particle_offsets} whenever {@code hasPower()} and the engine particle timer
 * comes round. A definition without that key is silent, and nothing anywhere reports it - the
 * vehicle simply never smokes. So every Armor Pioneer vehicle is required to carry the key, and
 * the offsets are required to look like a rear deck point rather than a front bumper or the
 * middle of the hull.
 *
 * <p>The exception is the ZTZ-100. Its diesel-electric hybrid ({@code ArmorConfig#hybridFor}) runs
 * the same power figure from the battery with the ignition off, so {@code hasPower()} stays true
 * on battery and the base mod would smoke like a diesel it is not. The addon's answer is the
 * client mixin {@code TrackedVehicleElectricSmokeMixin}: it redirects the one call that produces
 * exhaust, so the track dust and the suspension dust keep working. That mixin has to name the call
 * rather than the method, and it has to gate on the hybrid state - both are asserted from the
 * source text here, because nothing on this classpath loads a client mixin.
 */
public class ExhaustCheck {

    private static final String NAMESPACE = "armor_pioneer";

    /** Every Armor Pioneer vehicle. A new one has to be added here and configured in the pack. */
    private static final List<String> VEHICLES =
            List.of("vt5", "vt4", "k2", "type10", "tianma20", "ztz100", "leopard2a4");

    private static int failures;

    public static void main(String[] args) throws Exception {
        Path pack = PackLocator.of(args, NAMESPACE);
        System.out.println("pack: " + pack);
        System.out.println();
        System.out.println("=== every vehicle's rear deck exhaust ===");
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            TreeSet<String> shipped = new TreeSet<>();
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith("data/" + NAMESPACE + "/vehicles/")
                        && name.endsWith(".json")) {
                    shipped.add(name.substring(name.lastIndexOf('/') + 1,
                            name.length() - ".json".length()));
                }
            }
            check("the pack ships exactly the configured vehicles - " + shipped,
                    shipped.equals(new TreeSet<>(VEHICLES)));

            for (String vehicle : VEHICLES) {
                JsonObject definition = read(zip, "data/" + NAMESPACE + "/vehicles/"
                        + vehicle + ".json");
                if (definition == null) {
                    check(vehicle + ": vehicle definition exists", false);
                    continue;
                }
                JsonArray offsets = offsets(definition);
                check(vehicle + ": energy_info carries engine_particle_offsets",
                        offsets != null && !offsets.isEmpty());
                if (offsets == null || offsets.isEmpty()) {
                    continue;
                }
                check(vehicle + ": the offsets are three-number points",
                        pointsAreNumbers(offsets));
                check(vehicle + ": the plume starts at the rear (z < 0)",
                        allBehindOrigin(offsets));
                check(vehicle + ": the plume starts on the hull deck (0.5 < y < 4)",
                        deckHeight(offsets));
                check(vehicle + ": the plume stays inside the hull width (|x| < 3)",
                        insideWidth(offsets));
                check(vehicle + ": the offsets are a mirrored pair",
                        mirroredPair(offsets));
                System.out.println("    " + offsets);
            }

            System.out.println();
            System.out.println("=== the hybrid exception comes from the addon, not the pack ===");
            check("the ZTZ-100 has a hybrid drive",
                    ArmorConfig.hybridFor(new ResourceLocation(NAMESPACE, "ztz100")) != null);
            check("no other Armor Pioneer vehicle has one",
                    VEHICLES.stream().filter(id -> !"ztz100".equals(id))
                            .noneMatch(id -> ArmorConfig.hybridFor(
                                    new ResourceLocation(NAMESPACE, id)) != null));
            // The diesel has to keep smoking: electric mode is a mode, not the only mode.
            JsonObject ztz = read(zip, "data/" + NAMESPACE + "/vehicles/ztz100.json");
            check("the ZTZ-100 keeps its exhaust for diesel mode",
                    ztz != null && offsets(ztz) != null && !offsets(ztz).isEmpty());

            String mixin = text("src/main/java/com/ywzjvehicle/armor/mixin/"
                    + "TrackedVehicleElectricSmokeMixin.java");
            check("the smoke mixin redirects spawnEngineSmoke, not tickParticle",
                    mixin != null && mixin.contains("spawnEngineSmoke")
                            && !mixin.contains("method = \"tickParticle\", at = @At(\"HEAD\")"));
            check("the smoke mixin returns before the call while electric",
                    mixin != null && mixin.contains("HybridClientState.isElectric")
                            && mixin.indexOf("isElectric") < mixin.indexOf(
                                    "ParticleUtil.spawnEngineSmoke(level"));
        }
        System.out.println();
        if (failures > 0) {
            System.out.println("RESULT: " + failures + " exhaust check(s) failed");
            System.exit(1);
        }
        System.out.println("RESULT: every Armor Pioneer vehicle smokes from its rear deck, and the "
                + "ZTZ-100's client mixin silences it on battery power only");
    }

    // --- reading -----------------------------------------------------------------------------

    private static JsonArray offsets(JsonObject definition) {
        if (!definition.has("energy_info") || !definition.get("energy_info").isJsonObject()) {
            return null;
        }
        JsonObject energy = definition.getAsJsonObject("energy_info");
        return energy.has("engine_particle_offsets")
                && energy.get("engine_particle_offsets").isJsonArray()
                ? energy.getAsJsonArray("engine_particle_offsets") : null;
    }

    private static boolean pointsAreNumbers(JsonArray offsets) {
        for (JsonElement element : offsets) {
            if (!element.isJsonArray() || element.getAsJsonArray().size() != 3) {
                return false;
            }
            for (JsonElement coordinate : element.getAsJsonArray()) {
                if (!coordinate.isJsonPrimitive()
                        || !coordinate.getAsJsonPrimitive().isNumber()) {
                    return false;
                }
            }
        }
        return true;
    }

    /** +z is the nose, so an exhaust point has a negative z. */
    private static boolean allBehindOrigin(JsonArray offsets) {
        for (JsonElement element : offsets) {
            if (at(element, 2) >= 0.0) {
                return false;
            }
        }
        return true;
    }

    private static boolean deckHeight(JsonArray offsets) {
        for (JsonElement element : offsets) {
            double y = at(element, 1);
            if (y <= 0.5 || y >= 4.0) {
                return false;
            }
        }
        return true;
    }

    private static boolean insideWidth(JsonArray offsets) {
        for (JsonElement element : offsets) {
            if (Math.abs(at(element, 0)) >= 3.0) {
                return false;
            }
        }
        return true;
    }

    /** Two points with x and -x, at the same height and the same distance back. */
    private static boolean mirroredPair(JsonArray offsets) {
        if (offsets.size() != 2) {
            return false;
        }
        double x0 = at(offsets.get(0), 0);
        double x1 = at(offsets.get(1), 0);
        return Math.abs(x0 + x1) < 1.0E-6
                && Math.abs(at(offsets.get(0), 1) - at(offsets.get(1), 1)) < 1.0E-6
                && Math.abs(at(offsets.get(0), 2) - at(offsets.get(1), 2)) < 1.0E-6;
    }

    private static double at(JsonElement point, int index) {
        return point.getAsJsonArray().get(index).getAsDouble();
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

    private static String text(String relative) {
        Path path = Path.of(relative);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
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
