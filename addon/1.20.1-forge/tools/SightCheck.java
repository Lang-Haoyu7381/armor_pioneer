import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The Armor Pioneer sight check: thermal is a flag in the pack, night vision is the addon's shader.
 *
 * <p>Thermal is the base mod's own feature, but only a weapon unit whose optic is the CRT style and
 * whose {@code with_thermal_imager} is true ever shows it; the default pack's tanks all set the
 * flag, while every Armor Pioneer vehicle shipped it false, so key 4 did nothing in this pack.
 * The turret is the gunner's CRT sight; the commander's station is an OPERATOR sight, which the
 * base mod's thermal path does not draw through, so it stays false on purpose.
 *
 * <p>Night vision has no base-mod data flag at all. It is the addon's post chain, so the second
 * half of this check is a resource check: the post description must name the addon's program, and
 * the program and both shaders must be packaged beside it. A resource missing from the jar fails
 * at runtime with nothing on screen but a log line, which is exactly the class of defect a build
 * can catch.
 *
 * <p>Every one of the seven vehicles is listed rather than discovered, so a vehicle quietly losing
 * its turret from the pack cannot silently reduce the set this check agrees with.
 */
public class SightCheck {

    private static final String NAMESPACE = "armor_pioneer";
    private static final String PROGRAM = "ywzj_vehicle_armor:night_vision";

    private static final List<String> VEHICLES =
            List.of("vt5", "vt4", "k2", "type10", "tianma20", "ztz100", "leopard2a4");

    private static final String[] RESOURCES = {
            "/assets/ywzj_vehicle_armor/shaders/post/night_vision.json",
            "/assets/ywzj_vehicle_armor/shaders/program/night_vision.json",
            "/assets/ywzj_vehicle_armor/shaders/program/night_vision.vsh",
            "/assets/ywzj_vehicle_armor/shaders/program/night_vision.fsh"
    };

    private static int failures;

    public static void main(String[] args) throws Exception {
        Path pack = PackLocator.of(args, NAMESPACE);
        System.out.println("pack: " + pack);
        System.out.println();
        System.out.println("=== thermal: the gunner's CRT sight, and only that sight ===");
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            for (String vehicle : VEHICLES) {
                JsonObject definition = read(zip, "data/" + NAMESPACE + "/vehicles/"
                        + vehicle + ".json");
                if (definition == null) {
                    check(vehicle + ": vehicle definition exists", false);
                    continue;
                }
                JsonObject turret = part(definition, "turret");
                JsonObject commander = part(definition, "commander_machine_gun");
                check(vehicle + ": has a turret weapon unit",
                        turret != null && "ywzj_vehicle:weapon".equals(string(turret, "type")));
                check(vehicle + ": the turret is the CRT gunner sight",
                        turret != null && "crt".equals(string(turret, "optical_sight_type")));
                check(vehicle + ": the turret has thermal imaging",
                        flag(turret, "with_thermal_imager"));
                check(vehicle + ": the turret has a main-gun list",
                        turret != null && turret.has("weapons")
                                && turret.get("weapons").isJsonArray()
                                && turret.getAsJsonArray("weapons").size() > 0);
                check(vehicle + ": the commander's station stays an OPERATOR sight",
                        commander != null
                                && "operator".equals(string(commander, "optical_sight_type")));
                check(vehicle + ": the commander's station does not claim thermal",
                        commander != null && !flag(commander, "with_thermal_imager"));
            }
        }

        System.out.println();
        System.out.println("=== night vision: the addon's green post chain is in the jar ===");
        check("the handler class is in the compiled classes",
                SightCheck.class.getResource(
                        "/com/ywzjvehicle/armor/client/NightVisionHandler.class") != null);
        check("the thermal-smoke GUI puff is packaged",
                SightCheck.class.getResource(
                        "/assets/ywzj_vehicle_armor/textures/gui/thermal_smoke.png") != null);
        check("the thermal-smoke overlay class is in the compiled classes",
                SightCheck.class.getResource(
                        "/com/ywzjvehicle/armor/client/ThermalSmokeOverlay.class") != null);
        for (String resource : RESOURCES) {
            check(resource + " exists", resource(resource) != null);
        }
        String postText = resource(RESOURCES[0]);
        String programText = resource(RESOURCES[1]);
        String vertexText = resource(RESOURCES[2]);
        String fragmentText = resource(RESOURCES[3]);

        if (postText != null) {
            JsonObject post = JsonParser.parseString(postText).getAsJsonObject();
            JsonArray passes = post.getAsJsonArray("passes");
            check("post chain has a program pass and a blit",
                    passes != null && passes.size() == 2);
            if (passes != null && passes.size() == 2) {
                JsonObject first = passes.get(0).getAsJsonObject();
                JsonObject last = passes.get(1).getAsJsonObject();
                check("post chain draws the addon's night-vision program",
                        PROGRAM.equals(string(first, "name")));
                check("post chain reads the main target", "minecraft:main".equals(
                        string(first, "intarget")));
                check("post chain writes the swap target", "swap".equals(
                        string(first, "outtarget")));
                check("post chain blits the result back",
                        "blit".equals(string(last, "name"))
                                && "swap".equals(string(last, "intarget"))
                                && "minecraft:main".equals(string(last, "outtarget")));
            }
        }
        if (programText != null) {
            JsonObject program = JsonParser.parseString(programText).getAsJsonObject();
            check("program names the addon's vertex shader",
                    PROGRAM.equals(string(program, "vertex")));
            check("program names the addon's fragment shader",
                    PROGRAM.equals(string(program, "fragment")));
            check("program declares the full-screen Position attribute",
                    arrayContains(program, "attributes", "Position"));
            check("program samples the scene",
                    hasSampler(program, "DiffuseSampler"));
        }
        if (vertexText != null) {
            check("vertex shader writes gl_Position", vertexText.contains("gl_Position"));
        }
        if (fragmentText != null) {
            check("fragment shader reads DiffuseSampler",
                    fragmentText.contains("DiffuseSampler"));
            check("fragment shader writes fragColor", fragmentText.contains("fragColor"));
        }

        System.out.println();
        if (failures > 0) {
            System.out.println("RESULT: " + failures + " sight check(s) failed");
            System.exit(1);
        }
        System.out.println("RESULT: all seven Armor Pioneer tanks have a thermal gunner sight; "
                + "the night-vision post chain is packaged");
    }

    // --- pack reading ------------------------------------------------------------------------

    private static JsonObject part(JsonObject definition, String id) {
        JsonArray parts = definition.has("parts") && definition.get("parts").isJsonArray()
                ? definition.getAsJsonArray("parts") : new JsonArray();
        for (JsonElement element : parts) {
            JsonObject part = element.getAsJsonObject();
            if (id.equals(string(part, "id"))) {
                return part;
            }
        }
        return null;
    }

    private static String string(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsString() : "";
    }

    private static boolean flag(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive()
                && object.get(key).getAsBoolean();
    }

    private static boolean arrayContains(JsonObject object, String key, String wanted) {
        if (object == null || !object.has(key) || !object.get(key).isJsonArray()) {
            return false;
        }
        for (JsonElement element : object.getAsJsonArray(key)) {
            if (element.isJsonPrimitive() && wanted.equals(element.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSampler(JsonObject program, String name) {
        if (program == null || !program.has("samplers") || !program.get("samplers").isJsonArray()) {
            return false;
        }
        for (JsonElement element : program.getAsJsonArray("samplers")) {
            if (element.isJsonObject() && name.equals(string(element.getAsJsonObject(), "name"))) {
                return true;
            }
        }
        return false;
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

    private static String resource(String path) {
        try (InputStream in = SightCheck.class.getResourceAsStream(path)) {
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
