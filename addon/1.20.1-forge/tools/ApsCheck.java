import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ywzjvehicle.armor.ApsIntercept;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The APS sweep's geometry, checked without a game.
 *
 * <p>The failure this is for is the one measured in the user's world: the Tianma-20's two APS
 * launchers fired five rounds at 120mm HEAT shells and intercepted none. The base's check looks
 * inside an 8-block box around the interceptor once a tick; a shell and an interceptor both moving
 * at about 15 blocks a tick can pass each other between two samples. The base's own catch is
 * therefore a matter of the firing range, and at close range it is always a miss.
 *
 * <p>What is checked here is the arithmetic that makes the sweep worth writing: a point that is
 * outside the base's 8-block box around the interceptor's new position is still within 10 blocks of
 * the segment it flew - which is the shell the user's test lost - while a shell that passed nowhere
 * near it is not caught. The mixin itself is validated by {@code MixinCheck}; this tool covers the
 * decision the mixin delegates here.
 *
 * <p>Usage: ApsCheck
 */
public class ApsCheck {

    private static final String MOD_ID = "ywzj_vehicle_armor";
    private static final String MIXIN = "ActiveProtectionGrenadeMixin";

    private static int failures;

    public static void main(String[] args) throws Exception {
        geometry();
        mixinListed();

        System.out.println();
        if (failures == 0) {
            System.out.println("RESULT: the APS sweep catches a shell the base's point check misses, "
                    + "and nothing beyond its own path");
        } else {
            System.out.println("RESULT: " + failures + " APS check(s) failed - see above");
            System.exit(1);
        }
    }

    private static void geometry() {
        System.out.println("=== the swept segment vs the base's point box ===");
        // One tick of the interceptor: fired from the launcher at the origin, 15 blocks along +z.
        Vec3 from = new Vec3(0.0, 3.0, 0.0);
        Vec3 to = new Vec3(0.0, 3.0, 15.0);
        // The shell the user's test lost: at d = 20 it is at z = 5 after the same tick. The base's
        // 8-block box sits on `to`, ten blocks away from it; the sweep contains it.
        Vec3 incoming = new Vec3(0.0, 3.0, 5.0);
        check("a shell crossing the interceptor's path is inside the sweep",
                ApsIntercept.withinPath(from, to, incoming));
        check("  and outside the base's 8-block box around the interceptor's new position",
                to.m_82546_(incoming).m_82553_() > 8.0);
        check("  so this is the case the base check cannot catch",
                10.0 > 8.0 && ApsIntercept.pathDistance(from, to, incoming) < 1.0E-9);

        // Perpendicular distances: 9 is inside, 11 is outside - the radius is 10 and the test is
        // about the distance to the segment, not to either end.
        check("a shell 9 blocks to the side is caught",
                ApsIntercept.withinPath(from, to, new Vec3(9.0, 3.0, 7.5)));
        check("a shell 11 blocks to the side is not",
                !ApsIntercept.withinPath(from, to, new Vec3(11.0, 3.0, 7.5)));
        check("the radius is the base's 8 with room for one sample",
                ApsIntercept.RADIUS == 10.0);

        // The segment, not the infinite line: a shell behind the launcher is not caught just
        // because it lies on the same line.
        check("a shell 20 blocks behind the interceptor is not caught",
                !ApsIntercept.withinPath(from, to, new Vec3(0.0, 3.0, -20.0)));
        check("a shell 20 blocks past the interceptor is not caught",
                !ApsIntercept.withinPath(from, to, new Vec3(0.0, 3.0, 35.0)));
        check("but one 3 blocks past the far end is",
                ApsIntercept.withinPath(from, to, new Vec3(0.0, 3.0, 18.0)));

        // The search box has to contain both ends with the radius, or the candidate query misses a
        // shell the distance test would have accepted.
        AABB box = ApsIntercept.searchBox(from, to, ApsIntercept.RADIUS);
        check("the search box contains the segment and its margin",
                box.m_82390_(new Vec3(0.0, 3.0, 0.0))
                        && box.m_82390_(new Vec3(0.0, 3.0, 15.0))
                        && box.m_82390_(new Vec3(9.0, 3.0, 7.5))
                        && !box.m_82390_(new Vec3(12.0, 3.0, 7.5)));

        // A degenerate segment (a stationary interceptor) is the point check, not a division by
        // zero; the base's own 8-block rule would otherwise stop working on a very slow grenade.
        check("a zero-length segment measures from the point",
                Math.abs(ApsIntercept.pathDistance(to, to, new Vec3(0.0, 3.0, 15.0))) < 1.0E-9
                        && ApsIntercept.withinPath(to, to, new Vec3(3.0, 3.0, 15.0))
                        && !ApsIntercept.withinPath(to, to, new Vec3(12.0, 3.0, 15.0)));
    }

    private static void mixinListed() {
        System.out.println();
        System.out.println("=== the mixin is registered ===");
        String json = resource(MOD_ID + ".mixins.json");
        check("the mixins config was found", json != null);
        if (json == null) {
            return;
        }
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Set<String> listed = new LinkedHashSet<>();
        root.getAsJsonArray("mixins").forEach(element -> listed.add(element.getAsString()));
        check(MIXIN + " is listed under \"mixins\"", listed.contains(MIXIN));
        System.out.println("  mixins: " + listed);

        // The two annotations resolve their targets at class-load; a renamed method or a wrong
        // descriptor is a mod that fails to start rather than a feature that does nothing. javac
        // cannot see either. The base class cannot be loaded here - its own method signatures name
        // the animation library, which the tools do not carry - so the class file is read as bytes
        // and the two names are looked for in its constant pool. MixinCheck states the same limit
        // for every other mixin; this is the one place where the names are also pinned.
        byte[] aps = resourceBytes(
                "org/ywzj/vehicle/entity/weapon/ActiveProtectionGrenadeEntity.class");
        check("the @Inject target ActiveProtectionGrenadeEntity.m_8119_() exists in the base class",
                aps != null && contains(aps, "m_8119_"));
        check("the @Redirect target Level.m_6249_(Entity, AABB, Predicate)List is in the base class",
                aps != null && contains(aps, "m_6249_")
                        && contains(aps, "(Lnet/minecraft/world/entity/Entity;"
                                + "Lnet/minecraft/world/phys/AABB;"
                                + "Ljava/util/function/Predicate;)Ljava/util/List;"));
    }

    private static byte[] resourceBytes(String path) {
        try (InputStream in = ApsCheck.class.getClassLoader().getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean contains(byte[] haystack, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i + n.length <= haystack.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (haystack[i + j] != n[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static String resource(String path) {
        try (InputStream in = ApsCheck.class.getClassLoader().getResourceAsStream(path)) {
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
