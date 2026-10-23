import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.structure.OBB;

import java.util.ArrayList;
import java.util.List;

/**
 * Which face does a shot at the glacis actually resolve to?
 *
 * <p>The T-64's hull is covered by two boxes whose front faces are coplanar and whose top faces
 * are coplanar - main_structure (the whole hull) and vehicle_body (the upper hull, slightly wider
 * and stopping at the same height). This casts real rays at that shared front plane from a range
 * of elevations and reports the OBB and face each one resolves to, using the mod's own hitOBB and
 * embeddingFace rather than a re-implementation.
 */
public class FaceProbe {

    /** From t64.structure.json, in 1/16-block units: origin then size. */
    private static final double[] MAIN_STRUCTURE = {-35.17, 0.00, -61.13551, 70.34, 30.89389, 131.58912};
    private static final double[] VEHICLE_BODY = {-40.17, 9.40, -61.14, 80.47, 21.49, 131.59};

    public static void main(String[] args) throws Exception {
        OBB hull = box(MAIN_STRUCTURE);
        OBB upper = box(VEHICLE_BODY);
        List<OBB> both = new ArrayList<>();
        both.add(hull);
        both.add(upper);

        System.out.println("box                     y range        z range        x range");
        describe("main_structure", hull);
        describe("vehicle_body  ", upper);
        System.out.println();

        System.out.println("one box at a time: which face does a shot at the glacis resolve to?");
        System.out.printf("%9s %10s   %-28s %-28s%n", "elev", "aim y", "main_structure", "vehicle_body");
        for (double elev : new double[]{30, 20, 10, 5, 2, 0, -2, -5, -10, -20}) {
            double aimY = 1.60;
            System.out.printf("%8.0f° %10.2f   %-28s %-28s%n", elev, aimY,
                    resolveOne(hull, elev, aimY), resolveOne(upper, elev, aimY));
        }
        System.out.println();

        System.out.println("both boxes present (what the game actually has)");
        System.out.printf("%8s %10s   %s%n", "elev", "aim y", "resolved to");
        for (double elev : new double[]{30, 20, 10, 5, 2, 0, -2, -5, -10, -20}) {
            System.out.printf("%7.0f° %10.2f   %s%n", elev, 1.60, resolveBoth(both, elev, 1.60));
        }
        System.out.println();

        // The front face of the hull is 1.93 blocks tall; the upper box only covers the top 1.34.
        System.out.println("where the two front faces overlap");
        System.out.printf("  main_structure front face: y 0.00 .. 1.93%n");
        System.out.printf("  vehicle_body   front face: y 0.59 .. 1.93%n");
        System.out.printf("  overlap                  : y 0.59 .. 1.93   (the glacis, essentially)%n");
    }

    private static String resolveOne(OBB box, double elevDeg, double aimY) {
        List<OBB> one = new ArrayList<>();
        one.add(box);
        return resolveBoth(one, elevDeg, aimY);
    }

    private static String resolveBoth(List<OBB> boxes, double elevDeg, double aimY) {
        double rad = Math.toRadians(elevDeg);
        // Fire from well in front of the tank, descending at the given elevation.
        Vector3f dir = new Vector3f(0.0F, (float) -Math.sin(rad), (float) -Math.cos(rad));
        double back = 20.0;
        Vector3f start = new Vector3f(0.0F, (float) (aimY + Math.sin(rad) * back), (float) (4.40 + Math.cos(rad) * back));
        Vector3f end = new Vector3f(start).add(new Vector3f(dir).mul(40.0F));

        VectorUtil.HitOBB hit = VectorUtil.hitOBB(boxes, new net.minecraft.world.phys.Vec3(start.x, start.y, start.z),
                new net.minecraft.world.phys.Vec3(end.x, end.y, end.z));
        if (hit == null) {
            return "miss";
        }
        int face = hit.obb().embeddingFace(hit.hitPos());
        boolean isUpper = hit.obb() == boxes.get(boxes.size() - 1) && boxes.size() > 1;
        String who = boxes.size() > 1 ? (isUpper ? "vehicle_body" : "main_structure") : "";
        return String.format("face %+d %-11s y=%.2f", face, faceName(face), hit.hitPos().f_82480_);
    }

    private static String faceName(int face) {
        switch (Math.abs(face)) {
            case 1: return "(side)";
            case 3: return face > 0 ? "(FRONT)" : "(rear)";
            default: return face > 0 ? "(ROOF)" : "(FLOOR)";
        }
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
        System.out.printf("%-22s y %.2f..%.2f   z %.2f..%.2f   x %.2f..%.2f%n", name,
                c.y - e.y, c.y + e.y, c.z - e.z, c.z + e.z, c.x - e.x, c.x + e.x);
    }
}
