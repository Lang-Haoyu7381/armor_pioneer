import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Checks that every key this addon reads is a registered {@code KeyMapping} with words in both
 * languages.
 *
 * <p>The failure this prevents is silent by construction: a key that is still compared against a
 * literal code works, so nothing looks wrong - it simply never appears in Options &gt; Controls and
 * cannot be rebound. The counterpart is just as quiet: a mapping registered but not read, or one
 * whose translation key is missing, shows up in the controls screen as a raw key name.
 */
public class KeyBindCheck {

    private static int failures;

    private static final String[][] MAPPINGS = {
            // field, lang path, default source line
            {"CREW_LEFT", "crew_left", "CREW_LEFT = key(\"crew_left\", LEFT)"},
            {"CREW_RIGHT", "crew_right", "CREW_RIGHT = key(\"crew_right\", RIGHT)"},
            {"CREW_UP", "crew_up", "CREW_UP = key(\"crew_up\", UP)"},
            {"CREW_DOWN", "crew_down", "CREW_DOWN = key(\"crew_down\", DOWN)"},
            {"LOAD_CHOOSER", "load_chooser", "LOAD_CHOOSER = key(\"load_chooser\", LEFT_ALT)"},
            {"FORDING", "fording", "FORDING = key(\"fording\", K)"},
            {"DOORS", "doors", "DOORS = key(\"doors\", P)"},
            {"HYBRID", "hybrid", "HYBRID = key(\"hybrid\", G)"},
    };

    private static final String[] DEFAULT_CODES = {
            "LEFT = 263", "RIGHT = 262", "UP = 265", "DOWN = 264",
            "LEFT_ALT = 342", "K = 75", "P = 80", "G = 71",
    };

    public static void main(String[] args) {
        String bindings = text("src/main/java/com/ywzjvehicle/armor/client/KeyBindings.java");
        String client = text("src/main/java/com/ywzjvehicle/armor/ClientSetup.java");
        String keys = text("src/main/java/com/ywzjvehicle/armor/WeaponKeyHandler.java");
        String scanner = text("src/main/java/com/ywzjvehicle/armor/ArmorScannerOverlay.java");

        System.out.println("=== every key is a KeyMapping ===");
        check("the bindings class exists", bindings != null);
        for (String[] mapping : MAPPINGS) {
            String field = mapping[0];
            String path = mapping[1];
            check(field + " is declared", bindings != null && bindings.contains(mapping[2]));
            check(field + " is registered",
                    bindings != null && bindings.contains("event.register(" + field + ")"));
            check(field + " is read by the handler",
                    keys != null && keys.contains("KeyBindings." + field));
            check("the controls screen uses the addon category",
                    bindings != null && bindings.contains("\"key.ywzj_vehicle_armor.\" + path")
                            && bindings.contains(", CATEGORY)"));
        }
        check("ClientSetup registers the bindings",
                client != null && client.contains("KeyBindings::register"));

        System.out.println();
        System.out.println("=== the old literal codes are gone ===");
        // The two screens keep their own navigation keys; only the world handlers are checked.
        check("WeaponKeyHandler has no key.keyboard literals",
                keys != null && !keys.contains("key.keyboard"));
        check("ArmorScannerOverlay has no key.keyboard literals",
                scanner != null && !scanner.contains("key.keyboard"));
        check("the scanner pages with the shared mappings",
                scanner != null && scanner.contains("KeyBindings.CREW_UP")
                        && scanner.contains("KeyBindings.CREW_DOWN"));
        check("the pitch chord uses the base mod's own drive mappings",
                keys != null && keys.contains("AllKeys.FORWARD.m_90832_")
                        && keys.contains("AllKeys.BACKWARD.m_90832_"));
        for (String code : DEFAULT_CODES) {
            check("the default is still " + code, bindings != null && bindings.contains(code));
        }

        System.out.println();
        System.out.println("=== both languages name every key ===");
        JsonObject zh = lang("zh_cn");
        JsonObject en = lang("en_us");
        check("key.category.ywzj_vehicle_armor", named(zh, en, "key.category.ywzj_vehicle_armor"));
        for (String[] mapping : MAPPINGS) {
            String key = "key.ywzj_vehicle_armor." + mapping[1];
            check(key, named(zh, en, key));
        }
        check("the night sight is in the same category", named(zh, en,
                "key.ywzj_vehicle_armor.night_vision"));
        check("the periscope is in the same category", named(zh, en,
                "key.ywzj_vehicle_armor.periscope"));

        System.out.println();
        if (failures == 0) {
            System.out.println("RESULT: every addon key is a rebindable, translated KeyMapping");
        } else {
            System.out.println("RESULT: " + failures + " key-binding check(s) failed");
            System.exit(1);
        }
    }

    private static boolean named(JsonObject zh, JsonObject en, String key) {
        return zh != null && en != null && zh.has(key) && en.has(key)
                && !zh.get(key).getAsString().isBlank()
                && !en.get(key).getAsString().isBlank();
    }

    private static String text(String path) {
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (Exception e) {
            check("could not read " + path + " - run this from the project root", false);
            return null;
        }
    }

    private static JsonObject lang(String locale) {
        String path = "/assets/ywzj_vehicle_armor/lang/" + locale + ".json";
        try (InputStream in = KeyBindCheck.class.getResourceAsStream(path)) {
            if (in == null) {
                check("could not load " + path + " from the compiled classes", false);
                return null;
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (Exception e) {
            check("could not parse " + path + ": " + e.getMessage(), false);
            return null;
        }
    }

    private static void check(String what, boolean ok) {
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
    }
}
