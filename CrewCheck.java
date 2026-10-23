import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ywzjvehicle.armor.CrewRole;
import com.ywzjvehicle.armor.CrewRoles;
import com.ywzjvehicle.armor.CrewState;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

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
        role(true, true, true, false, CrewRole.DRIVER,
                "seat zero is the driver even when it also carries a weapon");
        role(true, false, false, false, CrewRole.DRIVER,
                "seat zero with no weapon is still the driver");
        role(false, true, true, false, CrewRole.GUNNER, "the largest gun's operator is the gunner");
        role(false, true, false, true, CrewRole.COMMANDER,
                "the largest secondary is the commander's station");
        role(false, true, false, false, CrewRole.CREW,
                "a loader's machine gun is a weapon seat but not the commander's");
        role(false, false, false, false, CrewRole.CREW, "a plain seat is a passenger");
        check(CrewRole.byOrdinal(99) == CrewRole.CREW, "an ordinal off the end falls back to crew");
        check(CrewRole.COMMANDER.key().equals("role.ywzj_vehicle_armor.commander"),
                "the commander's translation key is stable");
    }

    private static void role(boolean driver, boolean weapon, boolean primary, boolean commander,
                             CrewRole want, String what) {
        CrewRole got = CrewRoles.classify(driver, weapon, primary, commander);
        check(got == want, what + (got == want ? "" : " - got " + got));
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
        check(!CrewRoles.isTurretPartId("driver_seat_2"), "the driver's seat is not in the turret");
        check(!CrewRoles.isTurretPartId("hull_machine_gun"),
                "a hull machine-gun port is not in the turret");
        check(!CrewRoles.isTurretPartId("track_left"), "the running gear is not in the turret");
        check(!CrewRoles.isTurretPartId(null), "no id is not a turret");
    }

    // --- wounds ----------------------------------------------------------------------------

    private static void wounds() {
        System.out.println();
        System.out.println("=== what a penetration costs the crew ===");

        float apfsds = CrewState.crewDamage(260.0F, 2600.0F, 125.0F);
        close(apfsds, 12.0F, "a 125mm dart through a light tank is a killing wound");
        check(CrewState.shouldIncapacitate(125.0F, apfsds),
                "and it takes the crew member out of the fight");
        check(CrewState.incapacitationTicks(apfsds) == 200,
                "the longest wound lasts ten seconds");

        float he = CrewState.crewDamage(0.0F, 2000.0F, 125.0F);
        close(he, 10.0F, "a burst inside the crew compartment is felt even with no residual");
        check(CrewState.shouldIncapacitate(125.0F, he),
                "a high-explosive round that got in takes the station out");

        float autocannon = CrewState.crewDamage(86.0F, 430.0F, 30.0F);
        close(autocannon, 0.86F, "a 30mm round through a thin roof hurts but does not maim");
        check(!CrewState.shouldIncapacitate(30.0F, autocannon),
                "and it does not take a station out");

        float rifle = CrewState.crewDamage(1.0F, 2.0F, 7.62F);
        close(rifle, 0.0F, "a rifle bullet that barely got in costs the crew nothing");

        float graze = CrewState.crewDamage(40.0F, 0.0F, 12.7F);
        close(graze, 0.5F, "even a heavy-machine-gun graze has a floor");

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
            "role.ywzj_vehicle_armor.crew",
            "message.ywzj_vehicle_armor.role",
            "message.ywzj_vehicle_armor.driver_only",
            "message.ywzj_vehicle_armor.commander_only",
            "message.ywzj_vehicle_armor.no_target",
            "message.ywzj_vehicle_armor.designated",
            "message.ywzj_vehicle_armor.designation_cleared",
            "message.ywzj_vehicle_armor.override_on",
            "message.ywzj_vehicle_armor.override_off",
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

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
