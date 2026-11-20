import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Checks the one thing about a vehicle's part list the crew roles cannot infer.
 *
 * <p>The base mod assigns seat indices in the order the seat parts appear in the JSON, and
 * {@code AbstractVehicle.onEnterVehicle} gives the vehicle's control unit - the driver - to seat
 * zero. The roles themselves are derived from capability, not from names, so the addon does not
 * care what a part is called; what it cannot know is which station the base will hand the wheel
 * to. The default pack's 99A lists its turret first, so the turret's occupant drives and works the
 * main gun: one crew member controls the whole vehicle. The Armor Pioneer vehicles now ship the
 * same order - {@code turret}, {@code commander_machine_gun}, {@code driver_seat_2} - which makes
 * the extra hull seat a passenger station, not a second driving position.
 *
 * <p>This is checked in the pack rather than in the addon because it is a pack decision. A future
 * pack release that reorders the parts puts a different person at the wheel silently - the vehicle
 * still drives and the turret still fires; the only symptom is that the wrong seat owns the
 * control unit - so the order is pinned here.
 *
 * <p>Part state is serialized by id and every pack cross-reference is an id, so reordering the
 * list does not touch saved health or ammunition; seat indices are runtime state and a player
 * re-boards after the reload.
 */
public class CrewOrderCheck {

    private static final String NAMESPACE = "armor_pioneer";

    /** Every Armor Pioneer vehicle; the Leopard adds a loader seat at the end. */
    private static final List<String> VEHICLES =
            List.of("vt5", "vt4", "k2", "type10", "tianma20", "ztz100", "leopard2a4");

    /**
     * The 99A's order: the main-gun turret is seat zero, the commander's station is second, and
     * the extra hull seat is a passenger. The Leopard's loader rides behind them.
     */
    private static final List<String> SEAT_ORDER =
            List.of("turret", "commander_machine_gun", "driver_seat_2");

    private static final List<String> LOADER_SEAT_ORDER =
            List.of("turret", "commander_machine_gun", "driver_seat_2", "loader_seat");

    private static int failures;

    public static void main(String[] args) throws Exception {
        Path pack = pack(args);
        System.out.println("pack: " + pack);
        System.out.println();
        System.out.println("=== seat order (the 99A gives the wheel to the first seat part) ===");
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            for (String vehicle : VEHICLES) {
                JsonObject definition = read(zip, "data/" + NAMESPACE + "/vehicles/"
                        + vehicle + ".json");
                if (definition == null) {
                    check(vehicle + ": vehicle definition exists", false);
                    continue;
                }
                boolean loader = "leopard2a4".equals(vehicle);
                List<String> want = loader ? LOADER_SEAT_ORDER : SEAT_ORDER;
                List<String> seats = seatOrder(definition);
                check(vehicle + ": turret, commander, passenger - " + seats,
                        seats.equals(want));
                check(vehicle + ": the turret is seat zero (the 99A's control)",
                        !seats.isEmpty() && "turret".equals(seats.get(0)));
                check(vehicle + ": the turret carries the main gun",
                        "ywzj_vehicle:weapon".equals(type(definition, "turret"))
                                && !weapons(definition, "turret").isEmpty());
                check(vehicle + ": the commander's station is a weapon unit",
                        "ywzj_vehicle:weapon".equals(type(definition,
                                "commander_machine_gun")));
                check(vehicle + ": the extra hull seat is a generic part",
                        "ywzj_vehicle:generic".equals(type(definition, "driver_seat_2")));
                check(vehicle + ": the extra hull seat carries no weapon",
                        weapons(definition, "driver_seat_2").isEmpty());
                if (loader) {
                    check(vehicle + ": the loader's station is a generic part",
                            "ywzj_vehicle:generic".equals(type(definition, "loader_seat")));
                    check(vehicle + ": the loader's station carries no weapon",
                            weapons(definition, "loader_seat").isEmpty());
                }
            }

            // The base mod cancels a TACZ trigger in any seat whose part answers false here, so
            // this is the pack's half of "the crew can use their own gun". The weapon stations
            // are deliberately excluded: their left click is the vehicle gun's trigger, and
            // enabling item use there would fire both on one click. The turret drives now, but
            // its left click is still the main gun.
            System.out.println();
            System.out.println("=== TACZ item use, by seat ===");
            for (String vehicle : VEHICLES) {
                JsonObject definition = read(zip, "data/" + NAMESPACE + "/vehicles/"
                        + vehicle + ".json");
                check(vehicle + ": the extra hull seat can use a TACZ gun",
                        canUseItem(definition, "driver_seat_2"));
                check(vehicle + ": the turret cannot (left click is the main gun)",
                        !canUseItem(definition, "turret"));
                check(vehicle + ": the commander cannot (left click is the gun)",
                        !canUseItem(definition, "commander_machine_gun"));
                if ("leopard2a4".equals(vehicle)) {
                    check(vehicle + ": the loader can use a TACZ gun",
                            canUseItem(definition, "loader_seat"));
                }
            }
        }
        System.out.println();
        if (failures > 0) {
            System.out.println("RESULT: " + failures + " crew order check(s) failed");
            System.exit(1);
        }
        System.out.println("RESULT: every Armor Pioneer tank puts the main-gun turret first, so "
                + "the turret occupant drives - the 99A's control - and only the unarmed seats "
                + "can use a TACZ gun");
    }

    // --- reading -----------------------------------------------------------------------------

    private static List<String> seatOrder(JsonObject definition) {
        List<String> seats = new ArrayList<>();
        for (JsonElement element : parts(definition)) {
            JsonObject part = element.getAsJsonObject();
            // is_seat defaults to true, so only an explicit false takes a part out of the chair.
            if (part.has("is_seat") && !part.get("is_seat").getAsBoolean()) {
                continue;
            }
            seats.add(part.get("id").getAsString());
        }
        return seats;
    }

    private static JsonObject part(JsonObject definition, String id) {
        for (JsonElement element : parts(definition)) {
            JsonObject part = element.getAsJsonObject();
            if (id.equals(part.get("id").getAsString())) {
                return part;
            }
        }
        return null;
    }

    private static String type(JsonObject definition, String id) {
        JsonObject part = part(definition, id);
        return part == null || !part.has("type") ? "" : part.get("type").getAsString();
    }

    /** @return the seat's {@code passenger_can_use_item}, which the base mod defaults to false. */
    private static boolean canUseItem(JsonObject definition, String id) {
        if (definition == null) {
            return false;
        }
        JsonObject part = part(definition, id);
        return part != null && part.has("passenger_can_use_item")
                && part.get("passenger_can_use_item").getAsBoolean();
    }

    private static JsonArray weapons(JsonObject definition, String id) {
        JsonObject part = part(definition, id);
        if (part == null || !part.has("weapons") || !part.get("weapons").isJsonArray()) {
            return new JsonArray();
        }
        return part.getAsJsonArray("weapons");
    }

    private static JsonArray parts(JsonObject definition) {
        return definition.has("parts") && definition.get("parts").isJsonArray()
                ? definition.getAsJsonArray("parts") : new JsonArray();
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

    private static Path pack(String[] args) throws Exception {
        return PackLocator.of(args, NAMESPACE);
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
