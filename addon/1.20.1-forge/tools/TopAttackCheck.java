import com.ywzjvehicle.armor.ArmorConfig;
import com.ywzjvehicle.armor.TopAttack;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The KSTAM-II's top-attack carriage: the configuration that enables it, the geometry that decides
 * when it goes off, and the registration that makes the mixin run at all.
 *
 * <p>What is checked here is the arithmetic that can be checked without a world: the standoff
 * window and its boundaries, the descending-only condition, and the fact that exactly one round in
 * the shipped table asks for a top attack. The rest - which vehicle is underneath, and the roof's
 * height - needs a live level and is exercised in game.
 */
public class TopAttackCheck {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Method defaults = ArmorConfig.class.getDeclaredMethod("defaultProfile");
        defaults.setAccessible(true);
        ArmorConfig.Profile profile = (ArmorConfig.Profile) defaults.invoke(null);

        System.out.println("== the KSTAM-II's configuration ==");
        ArmorConfig.Ammo kstam = profile.ammo_penetration.get(
                "armor_pioneer:cannon_120mm_kstam");
        expectTrue("the KSTAM-II is in the table", kstam != null);
        if (kstam == null) {
            finish();
            return;
        }
        expectTrue("it asks for a top attack", kstam.isTopAttack());
        expect("goes off 1 block above the roof", 1.0, kstam.topAttackStandoff(), 0.001);
        expect("looks 3 blocks to either side", 3.0, kstam.topAttackRadius(), 0.001);
        expect("never below 0.25 blocks", 0.25, kstam.topAttackMinHeight(), 0.001);
        expectTrue("it is still the 200mm EFP", kstam.penetration == 200.0F
                && "heat".equals(kstam.profile));

        System.out.println();
        System.out.println("== the detonation window ==");
        expectTrue("1.0 above, descending", TopAttack.shouldDetonate(1.0, 1.5, -0.5, kstam));
        expectTrue("0.25 above, descending (the floor)",
                TopAttack.shouldDetonate(0.25, 0.75, -0.5, kstam));
        expectTrue("... just below the floor is not",
                !TopAttack.shouldDetonate(0.2, 0.7, -0.5, kstam));
        expectTrue("... just above the standoff is not",
                !TopAttack.shouldDetonate(1.01, 1.51, -0.5, kstam));
        expectTrue("a level missile is not attacking",
                !TopAttack.shouldDetonate(0.5, 0.5, 0.0, kstam));
        expectTrue("a climbing missile is not either",
                !TopAttack.shouldDetonate(0.5, 0.0, 0.5, kstam));
        expectTrue("a fast dive that jumps the window still fires",
                TopAttack.shouldDetonate(0.2, 9.0, -8.8, kstam));
        expectTrue("... but one already inside the vehicle does not",
                !TopAttack.shouldDetonate(-0.5, 9.0, -9.5, kstam));
        expectTrue("an ordinary round has no window",
                !TopAttack.shouldDetonate(0.5, 1.0, -0.5, profile.ammo_penetration.get(
                        "armor_pioneer:cannon_120mm_heat")));

        System.out.println();
        System.out.println("== exactly one round asks for it ==");
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (Map.Entry<String, ArmorConfig.Ammo> entry : profile.ammo_penetration.entrySet()) {
            if (entry.getValue() != null && entry.getValue().isTopAttack()) {
                ids.add(entry.getKey());
            }
        }
        expectTrue("the KSTAM-II is the only top-attack round (" + ids + ")",
                ids.size() == 1 && ids.contains("armor_pioneer:cannon_120mm_kstam"));

        System.out.println();
        System.out.println("== the mixin is registered ==");
        String json = mixinConfig();
        expectTrue("the mixin config is on the classpath", json != null);
        if (json != null) {
            int commonStart = json.indexOf("\"mixins\"");
            int clientStart = json.indexOf("\"client\"");
            int at = json.indexOf("\"MissileTopAttackMixin\"");
            expectTrue("MissileTopAttackMixin is in the common list",
                    at >= 0 && commonStart >= 0 && clientStart > commonStart
                            && at > commonStart && at < clientStart);
        }
        boolean mixinClass = true;
        try {
            Class.forName("com.ywzjvehicle.armor.mixin.MissileTopAttackMixin");
        } catch (ClassNotFoundException missing) {
            mixinClass = false;
        }
        expectTrue("the mixin class is in the build", mixinClass);

        finish();
    }

    private static String mixinConfig() {
        try (InputStream in = ArmorConfig.class.getResourceAsStream(
                "/ywzj_vehicle_armor.mixins.json")) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception failed) {
            return null;
        }
    }

    private static void expect(String what, double want, double got, double tolerance) {
        boolean ok = Math.abs(want - got) <= tolerance;
        System.out.printf("  %-48s %8.2f  (want %8.2f)  %s%n", what, got, want,
                ok ? "ok" : "FAIL");
        if (!ok) {
            failures++;
        }
    }

    private static void expectTrue(String what, boolean ok) {
        System.out.printf("  %-48s %s%n", what, ok ? "ok" : "FAIL");
        if (!ok) {
            failures++;
        }
    }

    private static void finish() {
        if (failures > 0) {
            System.out.println("RESULT: " + failures + " top-attack check(s) failed");
            System.exit(1);
        }
        System.out.println("RESULT: the KSTAM-II top attack is configured and wired");
    }
}
