import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.structure.OBB;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Can a shot ever land on a BTR-3 wheel?
 *
 * <p>The wheels have real collision boxes - unlike the T-64's track, which is nine 0.125-block
 * locator cubes a side - so the obvious way to give them their own armour would be to name their
 * part units, the way turret parts are named. That only works if the wheel boxes are reachable:
 * every cube in the model goes into the one list the damage system ray-tests, and a hit resolves to
 * whichever box the ray enters first. The wheels sit at |X| = 23.01953 units while the bare hull
 * runs out to 23.10, so on paper the hull is always in front of them.
 *
 * <p>This builds the boxes exactly as the mod does - origin and size in 1/16-block units, extents
 * halved - and asks the mod's own {@code hitOBB} which one a shot resolves to. It also reports where
 * the wheels sit in the hull's normalised frame, which is the frame any geometric fallback would
 * have to be written in.
 */
public class WheelProbe {

    /** From btr_3.structure.json, in 1/16-block units: origin then size. */
    private static final double[] MAIN_STRUCTURE = {-23.1, 0.0, -62.5758, 46.25, 33.5996, 109.6766};

    private static final double[] VEHICLE_BODY = {-23.0, 6.25, -62.7258, 46.0, 27.9996, 90.725};

    /** Every wheel bone in the model, in the order the structure file lists them. */
    private static final Map<String, double[]> WHEEL_CUBES = new LinkedHashMap<>();

    static {
        WHEEL_CUBES.put("wheelR0Turn", new double[]{-23.01953, -0.5, 24.0, 6.03906, 18.0, 18.0});
        WHEEL_CUBES.put("wheelL0Turn", new double[]{16.98047, -0.5, 24.0, 6.03906, 18.0, 18.0});
        WHEEL_CUBES.put("wheelR0", new double[]{-23.01953, -0.5, 0.0, 6.03906, 18.0, 18.0});
        WHEEL_CUBES.put("wheelL0", new double[]{16.98047, -0.5, 0.0, 6.03906, 18.0, 18.0});
        WHEEL_CUBES.put("wheelR1", new double[]{-23.01953, -0.5, -25.0, 6.03906, 18.0, 18.0});
        WHEEL_CUBES.put("wheelL1", new double[]{16.98047, -0.5, -25.0, 6.03906, 18.0, 18.0});
        WHEEL_CUBES.put("wheelR2", new double[]{-23.01953, -0.5, -49.0, 6.03906, 18.0, 18.0});
        WHEEL_CUBES.put("wheelL2", new double[]{16.98047, -0.5, -49.0, 6.03906, 18.0, 18.0});
    }

    private static final OBB HULL = box(MAIN_STRUCTURE);
    private static final OBB UPPER = box(VEHICLE_BODY);
    private static final Map<String, OBB> WHEELS = new LinkedHashMap<>();
    private static final Map<OBB, String> NAMES = new IdentityHashMap<>();

    static {
        NAMES.put(HULL, "main_structure (HULL)");
        NAMES.put(UPPER, "vehicle_body");
        for (Map.Entry<String, double[]> e : WHEEL_CUBES.entrySet()) {
            OBB wheel = box(e.getValue());
            WHEELS.put(e.getKey(), wheel);
            NAMES.put(wheel, "WHEEL " + e.getKey());
        }
    }

    public static void main(String[] args) {
        System.out.println("box                      x range        y range        z range");
        describe("main_structure", HULL);
        describe("vehicle_body  ", UPPER);
        for (Map.Entry<String, OBB> e : WHEELS.entrySet()) {
            describe(e.getKey(), e.getValue());
        }
        System.out.println();

        // Exactly what getOBBs() builds: the structure cubes first, then each part unit's, which is
        // where the wheels land - after the hull that encloses them.
        List<OBB> body = new ArrayList<>();
        body.add(HULL);
        body.add(UPPER);
        body.addAll(WHEELS.values());

        System.out.println("side shot from |X| = 20 blocks, straight along +X");
        System.out.printf("%9s %9s   %s%n", "aim y", "aim z", "resolved to");
        for (double aimY : new double[]{0.20, 0.55, 1.00, 1.20, 1.60}) {
            // 0.55 is the middle of wheelR0/L0 (z 0..1.125); 2.06 the front pair; -1.0 and -2.5 the
            // two rear pairs; 0.0 is aimed between the front pair and the first rear pair.
            for (double aimZ : new double[]{0.55, 2.06, -1.00, -2.50, 0.00}) {
                System.out.printf("%9.2f %9.2f   %s%n", aimY, aimZ, name(resolve(body, aimY, aimZ)));
            }
        }
        System.out.println();

        System.out.println("the same rays with the structure boxes taken out (wheels alone)");
        List<OBB> wheelsOnly = new ArrayList<>(WHEELS.values());
        System.out.printf("%9s %9s   %s%n", "aim y", "aim z", "resolved to");
        for (double aimY : new double[]{0.20, 0.55, 1.00}) {
            for (double aimZ : new double[]{0.55, 2.06, -1.00, -2.50, 0.00}) {
                System.out.printf("%9.2f %9.2f   %s%n", aimY, aimZ, name(resolve(wheelsOnly, aimY, aimZ)));
            }
        }
        System.out.println();

        geometry();
    }

    private static OBB resolve(List<OBB> boxes, double aimY, double aimZ) {
        VectorUtil.HitOBB hit = VectorUtil.hitOBB(boxes,
                new net.minecraft.world.phys.Vec3(-20.0, aimY, aimZ),
                new net.minecraft.world.phys.Vec3(20.0, aimY, aimZ));
        return hit == null ? null : hit.obb();
    }

    private static String name(OBB hit) {
        return hit == null ? "miss" : NAMES.getOrDefault(hit, "?");
    }

    /**
     * Where the wheels sit in the hull's own normalised frame - the frame the existing zones are
     * written in, and so the frame any geometric fallback would have to use.
     */
    private static void geometry() {
        Vector3f centre = HULL.center();
        Vector3f extents = HULL.extents();
        System.out.println("wheels in the hull's normalised frame (hull surface is +-1)");
        System.out.printf("  hull centre (%.3f, %.3f, %.3f) blocks, half-sizes (%.3f, %.3f, %.3f)%n",
                centre.x, centre.y, centre.z, extents.x, extents.y, extents.z);
        System.out.printf("%-14s %10s %10s %10s %10s%n",
                "wheel", "y bottom", "y top", "z rear", "z front");
        double lowest = Double.MAX_VALUE;
        double highest = -Double.MAX_VALUE;
        double rear = Double.MAX_VALUE;
        double front = -Double.MAX_VALUE;
        for (Map.Entry<String, double[]> e : WHEEL_CUBES.entrySet()) {
            double[] o = e.getValue();
            double yBottom = normalise(o[1], centre.y, extents.y);
            double yTop = normalise(o[1] + o[4], centre.y, extents.y);
            double zRear = normalise(o[2], centre.z, extents.z);
            double zFront = normalise(o[2] + o[5], centre.z, extents.z);
            System.out.printf("%-14s %10.3f %10.3f %10.3f %10.3f%n",
                    e.getKey(), yBottom, yTop, zRear, zFront);
            lowest = Math.min(lowest, yBottom);
            highest = Math.max(highest, yTop);
            rear = Math.min(rear, zRear);
            front = Math.max(front, zFront);
        }
        System.out.printf("  all wheels: y %.3f .. %.3f, z %.3f .. %.3f%n", lowest, highest, rear, front);
        System.out.printf("  hull side face is 2.10 blocks tall, so the wheels reach up to %.2f blocks%n",
                (highest * extents.y) + centre.y);
    }

    /** Model units to the hull's -1..+1 frame, the same arithmetic ArmorSystem.hullLocalPosition does. */
    private static double normalise(double units, double centreBlocks, double halfBlocks) {
        return (units / 16.0 - centreBlocks) / halfBlocks;
    }

    private static OBB box(double[] o) {
        Vector3f centre = new Vector3f(
                (float) ((o[0] + o[3] / 2) / 16),
                (float) ((o[1] + o[4] / 2) / 16),
                (float) ((o[2] + o[5] / 2) / 16));
        Vector3f extents = new Vector3f((float) (o[3] / 32), (float) (o[4] / 32), (float) (o[5] / 32));
        return new OBB(centre, extents, new Quaternionf());
    }

    private static void describe(String name, OBB box) {
        Vector3f c = box.center();
        Vector3f e = box.extents();
        System.out.printf("%-20s x %6.2f..%6.2f   y %6.2f..%6.2f   z %6.2f..%6.2f%n", name,
                c.x - e.x, c.x + e.x, c.y - e.y, c.y + e.y, c.z - e.z, c.z + e.z);
    }
}
