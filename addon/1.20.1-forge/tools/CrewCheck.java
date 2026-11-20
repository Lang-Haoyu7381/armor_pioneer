import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ywzjvehicle.armor.ArmorConfig;
import com.ywzjvehicle.armor.CrewRole;
import com.ywzjvehicle.armor.CrewRoles;
import com.ywzjvehicle.armor.CrewState;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Exercises the crew rules without a game.
 *
 * <p>Three things are checked, and each is checked because it decides something the player will
 * feel rather than something a screen will show:
 *
 * <ul>
 *   <li><b>Who is what.</b> The role truth table is small enough to enumerate, and the turret name
 *       fallback is what catches the commander's station on models that parent it to a separate
 *       root bone. A misclassification here is silent: the key simply goes to the wrong person.</li>
 *   <li><b>What a hit costs.</b> The wound arithmetic is what makes a penetrating shell take a
 *       station out and a 30mm round not. Both ends of that are asserted, because a scale that
 *       only ever grows makes every hit lethal and a scale that never reaches the threshold makes
 *       the feature invisible.</li>
 *   <li><b>What the player is told.</b> Every message key the new code asks for must exist in both
 *       languages. A missing key renders as its own name, which reads like a bug in the addon
 *       rather than a missing translation.</li>
 * </ul>
 */
public class CrewCheck {

    private static int failures;

    public static void main(String[] args) {
        roles();
        baseControl();
        compartments();
        wounds();
        language();
        if (failures > 0) {
            System.out.println("RESULT: " + failures + " crew check(s) failed");
            System.exit(1);
        }
        System.out.println("RESULT: every crew check passed");
    }

    // --- roles -----------------------------------------------------------------------------

    private static void roles() {
        System.out.println();
        System.out.println("=== who is doing what ===");
        role(true, false, true, true, false, CrewRole.DRIVER,
                "seat zero is the driver even when it carries the main gun (the 99A's turret seat)");
        role(true, false, false, false, false, CrewRole.DRIVER,
                "seat zero with no weapon is still the driver");
        role(false, false, true, true, false, CrewRole.GUNNER,
                "the largest gun's operator is the gunner");
        role(false, false, true, false, true, CrewRole.COMMANDER,
                "the largest secondary is the commander's station");
        role(false, false, true, false, false, CrewRole.CREW,
                "a non-main weapon that is not the commander's is a crew station");
        role(false, true, false, false, false, CrewRole.LOADER,
                "the configured loader's seat is the loader");
        role(false, true, true, true, false, CrewRole.LOADER,
                "the loader is still the loader even if the seat also carries a weapon");
        role(false, false, false, false, false, CrewRole.CREW, "a plain seat is a passenger");
        check(CrewRoles.isLoaderSeat("loader_seat", "loader_seat"),
                "the configured loader part id matches its seat");
        check(!CrewRoles.isLoaderSeat("loader_seat", "driver_seat_2"),
                "a different seat is not the loader's");
        check(!CrewRoles.isLoaderSeat(null, "loader_seat"),
                "an autoloaded vehicle has no loader seat");
        check(CrewRole.byOrdinal(3) == CrewRole.LOADER,
                "the loader's ordinal is stable for anything that stored it");
        check(CrewRole.byOrdinal(99) == CrewRole.CREW, "an ordinal off the end falls back to crew");
        check(CrewRole.COMMANDER.key().equals("role.ywzj_vehicle_armor.commander"),
                "the commander's translation key is stable");
        check(CrewRole.LOADER.key().equals("role.ywzj_vehicle_armor.loader"),
                "the loader's translation key is stable");
    }

    private static void role(boolean driver, boolean loader, boolean weapon, boolean primary,
                             boolean commander, CrewRole want, String what) {
        CrewRole got = CrewRoles.classify(driver, loader, weapon, primary, commander);
        check(got == want, what + (got == want ? "" : " - got " + got));
    }

    // --- the base mod's control --------------------------------------------------------------

    private static void baseControl() {
        System.out.println();
        System.out.println("=== the base mod keeps the wheel and the gun ===");
        String keys = text("src/main/java/com/ywzjvehicle/armor/WeaponKeyHandler.java");
        check(keys != null && !keys.contains("CrewActionMessage"),
                "the client sends no commander-action packets");
        check(keys != null && !keys.contains("TOGGLE_OVERRIDE"),
                "Left Ctrl is not an addon control");
        check(keys != null && !keys.contains("CYCLE_SOLO_WEAPON"),
                "T does not cycle an addon weapon");
        check(keys != null && !keys.contains("CYCLE_SOLO_VARIANT"),
                "F is left to the base mod");
        check(keys != null && !keys.contains("TOGGLE_DRIVE"),
                "Left Shift is left to the base mod");
        check(keys != null && !keys.contains("key.keyboard.m"),
                "M is not an addon control");
        String actions = text("src/main/java/com/ywzjvehicle/armor/network/CrewActionMessage.java");
        check(actions != null && actions.contains("setPacketHandled"),
                "an old client's commander-action packet is consumed");
        check(actions != null && !actions.contains("CrewSolo")
                        && !actions.contains("CrewDrive") && !actions.contains("CrewControl"),
                "an old client cannot move the wheel or the gun");
    }

    // --- compartments ----------------------------------------------------------------------

    private static void compartments() {
        System.out.println();
        System.out.println("=== which seats a turret hit reaches ===");
        check(CrewRoles.isTurretPartId("turret"), "a part called turret is in the turret");
        check(CrewRoles.isTurretPartId("turret_machine_gun"),
                "the coaxial machine gun is in the turret");
        check(CrewRoles.isTurretPartId("commander_machine_gun"),
                "the commander's mount is in the turret even when its bone is a root");
        check(!CrewRoles.isTurretPartId("driver_seat_2"), "the extra hull seat is not in the turret");
        check(!CrewRoles.isTurretPartId("hull_machine_gun"),
                "a hull machine-gun port is not in the turret");
        check(!CrewRoles.isTurretPartId("track_left"), "the running gear is not in the turret");
        check(!CrewRoles.isTurretPartId(null), "no id is not a turret");

        ArmorConfig.VehicleArmor manual = new ArmorConfig.VehicleArmor();
        manual.loader_seat_id = "loader_seat";
        check(CrewRoles.inTurretCompartment(manual, "loader_seat"),
                "a configured loader seat is turret crew without being an armour part");
        check(!CrewRoles.inTurretCompartment(manual, "driver_seat_2"),
                "and the extra hull seat is still hull crew");
        check(!CrewRoles.inTurretCompartment(manual, "loader_seat_2"),
                "a seat whose id merely begins like the loader's is not it");
    }

    // --- wounds ----------------------------------------------------------------------------

    private static void wounds() {
        System.out.println();
        System.out.println("=== what a penetration costs the crew ===");

        // The dose is damage / 3000: one main-gun penetration of a light tank is a 0.87 dose and
        // therefore a 10.47 wound, not the top of the old arbitrary scale.
        float apfsds = CrewState.crewSeverity(2600.0F);
        close(apfsds, 10.4667F, "a 125mm dart through a light tank is a killing wound");
        check(CrewState.shouldIncapacitate(125.0F, apfsds),
                "and it takes the crew member out of the fight");
        check(CrewState.incapacitationTicks(apfsds) == 184,
                "the wound lasts 9.2 seconds");
        close(CrewState.crewHitProbability(2600.0F), 0.52F,
                "and one crew member is hit a little over half the time");

        float he = CrewState.crewSeverity(2000.0F);
        close(he, 8.1667F, "a burst inside the crew compartment is felt even with no residual");
        check(CrewState.shouldIncapacitate(125.0F, he),
                "a high-explosive round that got in takes the station out");
        close(CrewState.crewHitProbability(2000.0F), 0.4F,
                "and the burst hits one crew member 40% of the time");

        float autocannon = CrewState.crewSeverity(430.0F);
        close(autocannon, 2.1483F, "a 30mm round through a thin roof hurts but does not maim");
        check(!CrewState.shouldIncapacitate(30.0F, autocannon),
                "and it does not take a station out");
        close(CrewState.crewHitProbability(430.0F), 0.086F,
                "a 30mm hit rarely reaches a crew member");

        close(CrewState.crewHitProbability(2.0F), 0.0004F,
                "a rifle bullet that barely got in almost never hits the crew");

        close(CrewState.crewHitProbability(0.0F), 0.0F,
                "a hit with no inside damage cannot wound anyone");

        check(CrewState.incapacitationTicks(6.0F) == 140,
                "the shortest incapacitating wound is seven seconds");
    }

    private static void close(float got, float want, String what) {
        check(Math.abs(got - want) < 0.001F,
                what + String.format(java.util.Locale.ROOT, " (want %.2f, got %.2f)", want, got));
    }

    // --- language --------------------------------------------------------------------------

    private static final String[] KEYS = {
            "role.ywzj_vehicle_armor.driver",
            "role.ywzj_vehicle_armor.gunner",
            "role.ywzj_vehicle_armor.commander",
            "role.ywzj_vehicle_armor.loader",
            "role.ywzj_vehicle_armor.crew",
            "message.ywzj_vehicle_armor.role",
            "message.ywzj_vehicle_armor.loader_slow",
            "message.ywzj_vehicle_armor.loader_interrupted",
            "message.ywzj_vehicle_armor.driver_only",
            "message.ywzj_vehicle_armor.commander_only",
            "message.ywzj_vehicle_armor.no_target",
            "message.ywzj_vehicle_armor.designated",
            "message.ywzj_vehicle_armor.designation_cleared",
            "message.ywzj_vehicle_armor.override_on",
            "message.ywzj_vehicle_armor.override_solo",
            "message.ywzj_vehicle_armor.override_off",
            "message.ywzj_vehicle_armor.drive_on",
            "message.ywzj_vehicle_armor.drive_off",
            "message.ywzj_vehicle_armor.solo_on",
            "message.ywzj_vehicle_armor.solo_off",
            "message.ywzj_vehicle_armor.solo_weapon",
            "message.ywzj_vehicle_armor.solo_locked",
            "message.ywzj_vehicle_armor.solo_only",
            "message.ywzj_vehicle_armor.solo_none",
            "message.ywzj_vehicle_armor.solo_single",
            "message.ywzj_vehicle_armor.no_main_gun",
            "message.ywzj_vehicle_armor.incapacitated",
            "message.ywzj_vehicle_armor.weapon_incapacitated",
            "message.ywzj_vehicle_armor.crew_hit",
            "message.ywzj_vehicle_armor.crew_incapacitated",
            "message.ywzj_vehicle_armor.crew_recovered"
    };

    private static void language() {
        System.out.println();
        System.out.println("=== what the crew is told ===");
        JsonObject zh = lang("zh_cn");
        JsonObject en = lang("en_us");
        for (String key : KEYS) {
            check(zh != null && zh.has(key), "zh_cn has " + key);
            check(en != null && en.has(key), "en_us has " + key);
        }
        // M is the commander's key now; the old suspension hint still advertised it. A stale hint
        // is worse than no hint: it teaches the player a key that does something else.
        String zhHint = hint(zh);
        String enHint = hint(en);
        check(!zhHint.contains("M / P"),
                "the suspension hint no longer advertises M, which is the commander's key");
        check(!enHint.contains("M / P"),
                "the English suspension hint no longer advertises M either");
    }

    private static String hint(JsonObject lang) {
        if (lang == null || lang.get("message.ywzj_vehicle_armor.suspension_set") == null) {
            return "";
        }
        return lang.get("message.ywzj_vehicle_armor.suspension_set").getAsString();
    }

    private static JsonObject lang(String locale) {
        String path = "/assets/ywzj_vehicle_armor/lang/" + locale + ".json";
        try (InputStream in = CrewCheck.class.getResourceAsStream(path)) {
            if (in == null) {
                check(false, "could not load " + path + " from the compiled classes");
                return null;
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (Exception e) {
            check(false, "could not read " + path + ": " + e);
            return null;
        }
    }

    private static String text(String path) {
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
