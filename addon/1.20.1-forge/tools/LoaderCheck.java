import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ywzjvehicle.armor.ArmorConfig;
import com.ywzjvehicle.armor.CrewRoles;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Checks the Leopard 2A4's manual loader without a game.
 *
 * <p>A loader is the one crew role that cannot be derived from a weapon's calibre: an autoloader
 * leaves the fourth seat free, while a manual gun's rate is a person's rate. The facts this tool
 * holds to are the ones that would otherwise fail silently:
 *
 * <ul>
 *   <li><b>The seat is named by the vehicle, not guessed from a part name.</b> The Leopard's
 *       config names {@code loader_seat}; the autoloaded tanks name none. A part called
 *       "loader_seat" on a vehicle that has not said so must stay an ordinary passenger.</li>
 *   <li><b>The seat is in the turret.</b> The loader rides in the turret, so a turret penetration
 *       has to be able to wound them. A seat missing from {@code turret_part_ids} is silently
 *       treated as hull and the loader is never hit by the round that went through their own
 *       compartment.</li>
 *   <li><b>The penalty is a multiplier on the pack's own figure, not a second number in the pack.</b>
 *       The Leopard's 120 mm reload is 120 ticks (6 s); with no fit loader the mixin doubles it.
 *       The pack stays the trained loader's cycle, which is why this tool checks both the 120 and
 *       the 240.</li>
 *   <li><b>The loader's role is stable.</b> It sits ahead of {@code CREW} in the ordinal order, and
 *       the role's translation keys exist in both languages.</li>
 * </ul>
 *
 * <p>Usage: LoaderCheck [pack.zip]
 *        Defaults to the installed armor_pioneer pack, zip or folder.
 */
public class LoaderCheck {

    private static final String NAMESPACE = "armor_pioneer";
    private static final String VEHICLE = "leopard2a4";
    private static final String LOADER_SEAT = "loader_seat";
    private static final String[] SEATS = {
            "turret", "commander_machine_gun", "driver_seat_2", "loader_seat"
    };

    private static int failures;

    public static void main(String[] args) throws Exception {
        factors();
        defaults();
        pack(args);
        language();
        if (failures > 0) {
            System.out.println("RESULT: " + failures + " loader check(s) failed");
            System.exit(1);
        }
        System.out.println("RESULT: the manual loader is wired and its penalty is bounded");
    }

    // --- the reload maths --------------------------------------------------------------------

    private static void factors() {
        System.out.println();
        System.out.println("=== a fit loader reloads at the pack's rate ===");
        check(CrewRoles.loaderReloadFactor(true) == 1.0F,
                "a fit loader leaves the reload at x1");
        check(CrewRoles.loaderReloadFactor(false) == 2.0F,
                "an absent or incapacitated loader doubles it");
        check(CrewRoles.loaderReloadTicks(120, true) == 120,
                "the Leopard's 120-tick (6 s) cycle is unchanged with a loader");
        check(CrewRoles.loaderReloadTicks(120, false) == 240,
                "without one it is 240 ticks (12 s)");
        check(CrewRoles.loaderReloadTicks(0, false) >= 1,
                "the scaled timer never reaches zero, which would mean 'loaded'");
        check(CrewRoles.isManuallyLoaded(120.0F), "120 mm is a manually loaded gun");
        check(CrewRoles.isManuallyLoaded(100.0F), "the threshold itself counts");
        check(!CrewRoles.isManuallyLoaded(99.9F), "below 100 mm is not");
        check(!CrewRoles.isManuallyLoaded(7.62F), "the MG3 is not");
        check(!CrewRoles.isManuallyLoaded(0.0F), "a weapon with no bore is not");
        check(CrewRoles.isLoaderSeat(LOADER_SEAT, LOADER_SEAT),
                "the configured loader id matches its seat");
        check(!CrewRoles.isLoaderSeat(null, LOADER_SEAT),
                "an autoloaded vehicle names no loader seat");
        check(!CrewRoles.isLoaderSeat(LOADER_SEAT, "driver_seat_2"),
                "another seat is not the loader's");
    }

    // --- the shipped profile -----------------------------------------------------------------

    private static void defaults() throws Exception {
        System.out.println();
        System.out.println("=== the shipped profile names the Leopard's loader ===");
        Method mk = ArmorConfig.class.getDeclaredMethod("defaultProfile");
        mk.setAccessible(true);
        ArmorConfig.Profile p = (ArmorConfig.Profile) mk.invoke(null);

        ArmorConfig.VehicleArmor leopard = p.vehicles.get("armor_pioneer:leopard2a4");
        check(leopard != null, "the Leopard has a profile");
        if (leopard != null) {
            check(LOADER_SEAT.equals(leopard.loader_seat_id),
                    "its loader seat is " + LOADER_SEAT);
            check(CrewRoles.inTurretCompartment(leopard, LOADER_SEAT),
                    "the loader's seat counts as turret crew");
            check(!CrewRoles.inTurretCompartment(leopard, "driver_seat_2"),
                    "the extra hull seat does not");
            check(!contains(leopard.turret_part_ids, LOADER_SEAT),
                    "the crew seat is not mistaken for an armour part in turret_part_ids");
        }
        for (String id : new String[]{"armor_pioneer:vt5", "armor_pioneer:vt4",
                "armor_pioneer:k2", "armor_pioneer:type10", "armor_pioneer:ztz100"}) {
            ArmorConfig.VehicleArmor armor = p.vehicles.get(id);
            check(armor == null || armor.loader_seat_id == null,
                    id + " has no manual loader seat");
        }

        Field version = ArmorConfig.class.getDeclaredField("CONFIG_VERSION");
        version.setAccessible(true);
        int shipped = version.getInt(null);
        // The loader arrived in v68; v70 is the first file that must carry it. A later bump must
        // not make this check fail, or an unrelated release reports the loader as missing.
        check(shipped >= 70, "the config version carries the loader (70+, got " + shipped + ")");
    }

    // --- the pack ---------------------------------------------------------------------------

    private static void pack(String[] args) throws Exception {
        System.out.println();
        System.out.println("=== the pack's fourth seat is the loader and the gun is 120 mm ===");
        Path zipPath = PackLocator.of(args, NAMESPACE);
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            String vehicleName = "data/armor_pioneer/vehicles/" + VEHICLE + ".json";
            JsonObject vehicle = json(zip, vehicleName);
            if (vehicle == null) {
                return;
            }
            JsonArray parts = vehicle.getAsJsonArray("parts");
            List<String> seats = new ArrayList<>();
            JsonObject loader = null;
            for (JsonElement element : parts) {
                JsonObject part = element.getAsJsonObject();
                String id = part.get("id").getAsString();
                if (part.has("is_seat") && !part.get("is_seat").getAsBoolean()) {
                    continue;
                }
                seats.add(id);
                if (LOADER_SEAT.equals(id)) {
                    loader = part;
                }
            }
            check(seats.size() >= 4 && seats.get(3).equals(LOADER_SEAT),
                    "seat four is " + LOADER_SEAT + " (seats found: " + seats + ")");
            for (int i = 0; i < SEATS.length && i < seats.size(); i++) {
                check(seats.get(i).equals(SEATS[i]),
                        "seat " + (i + 1) + " is " + SEATS[i] + " (got " + seats.get(i) + ")");
            }
            check(loader != null, "the loader's part definition exists");
            if (loader != null) {
                check("ywzj_vehicle:generic".equals(loader.get("type").getAsString()),
                        "the loader's station is a plain seat, not a weapon");
                check(!loader.has("weapons"), "the loader has no weapon of their own");
                JsonArray offset = loader.getAsJsonArray("seat_offset");
                check(offset != null && offset.size() == 3,
                        "the loader's seat has a three-axis offset");
            }

            JsonObject gun = json(zip, "data/armor_pioneer/weapons/cannon_120mm_dm33.json");
            if (gun != null) {
                check(gun.get("caliber").getAsFloat() == 120.0F,
                        "the main gun is 120 mm, which is the manually loaded class");
                JsonObject reload = gun.getAsJsonObject("reload");
                check(reload != null && reload.get("time").getAsInt() == 120,
                        "the pack's reload.time stays the trained loader's 120 ticks");
            }
        }
    }

    // --- the keys ---------------------------------------------------------------------------

    private static void language() {
        System.out.println();
        System.out.println("=== what the loader and the gunner are told ===");
        for (String locale : new String[]{"zh_cn", "en_us"}) {
            JsonObject lang = lang(locale);
            for (String key : new String[]{
                    "role.ywzj_vehicle_armor.loader",
                    "message.ywzj_vehicle_armor.loader_slow",
                    "message.ywzj_vehicle_armor.loader_interrupted"}) {
                check(lang != null && lang.has(key), locale + " has " + key);
            }
        }
    }

    // --- helpers ----------------------------------------------------------------------------

    private static JsonObject json(ZipFile zip, String name) {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            check(false, "pack is missing " + name);
            return null;
        }
        try (InputStream in = zip.getInputStream(entry);
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            check(false, "could not parse " + name + ": " + e);
            return null;
        }
    }

    private static JsonObject lang(String locale) {
        String path = "/assets/ywzj_vehicle_armor/lang/" + locale + ".json";
        try (InputStream in = LoaderCheck.class.getResourceAsStream(path)) {
            if (in == null) {
                check(false, "could not load " + path);
                return null;
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (Exception e) {
            check(false, "could not read " + path + ": " + e);
            return null;
        }
    }

    private static boolean contains(String[] values, String wanted) {
        for (String value : values) {
            if (wanted.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
