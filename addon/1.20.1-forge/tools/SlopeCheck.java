import com.ywzjvehicle.armor.ArmorConfig;
import com.ywzjvehicle.armor.ArmorSystem;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.ywzj.vehicle.vehicle.structure.OBB;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * The Panther's real plates, and the armour a round meets when it arrives from a given direction.
 *
 * <p>Three questions, in order. Does the trigonometry produce the figures a reference states for
 * an 80mm plate at 55 degrees - 139mm from dead ahead and 197mm from 45 degrees off the nose? Does
 * the shipped Panther actually carry those plates and those inclinations, with every other vehicle
 * still a plain box? And does the resolution path route the right inclination into the right zone -
 * the glacis and the lower plate at 55, the flanks at 30, the turret front at 11, and zero for the
 * mantlet, the machine-gun port and every bolted-on shape?
 *
 * <p>The arithmetic is the engine's own {@code ArmorSystem.effectiveThickness}, not a copy of the
 * formula. The zone wiring goes through the engine's real {@code resolveZone}, reached by
 * reflection the same way {@code PenetrationTable} reaches it: the method is package-private, and
 * a check that rebuilt the zone selection here would agree with itself rather than with the game.
 *
 * <p>The last section is the point of the exercise stated as a shot: the Panther's own 168mm
 * armour-piercing round goes through its glacis from dead ahead and fails from 45 degrees off,
 * which is exactly what one 80mm plate at 55 degrees should do.
 */
public class SlopeCheck {

    private static int failures = 0;

    /** A one-by-one box on the world axes: only the direction of the shot matters here. */
    private static final OBB BOX = new OBB(new Vector3f(0.0F, 0.0F, 0.0F),
            new Vector3f(1.0F, 1.0F, 1.0F), new Quaternionf());

    /** The Panther's hull half-extents in blocks, measured by VehicleScaffold. */
    private static final double[] PANTHER_HALF = {1.7806, 1.0584, 3.5672};

    private static Class<?> hitClass;
    private static Constructor<?> hitCtor;
    private static Method resolveZone;

    public static void main(String[] args) throws Exception {
        Method defaults = ArmorConfig.class.getDeclaredMethod("defaultProfile");
        defaults.setAccessible(true);
        ArmorConfig.Profile profile = (ArmorConfig.Profile) defaults.invoke(null);
        ArmorConfig.VehicleArmor panther = profile.vehicles.get("tank_assault:panther");
        ArmorConfig.VehicleArmor tiger = profile.vehicles.get("tank_assault:tiger");

        System.out.println("== the trigonometry ==");
        // 80 / cos 55 = 139.48, the line-of-sight thickness of the glacis from dead ahead.
        expect("80mm @55, dead ahead", 139.48, eff(80.0, 55.0, 3, 0.0, 0.0, -1.0), 0.1);
        // ... and 80 / (cos 45 x cos 55) = 197.25 from 45 degrees off the nose, the 197 a
        // reference gives for a shell 45 degrees to the Panther's front.
        double s = Math.sqrt(0.5);
        expect("80mm @55, 45 deg off the nose", 197.25, eff(80.0, 55.0, 3, -s, 0.0, -s), 0.2);
        expect("60mm @55, dead ahead (lower plate)", 104.61, eff(60.0, 55.0, 3, 0.0, 0.0, -1.0), 0.1);
        expect("50mm @30, square on the flank", 57.74, eff(50.0, 30.0, 1, -1.0, 0.0, 0.0), 0.1);
        expect("40mm @30, square on the rear", 46.19, eff(40.0, 30.0, -3, 0.0, 0.0, 1.0), 0.1);
        expect("110mm @11, dead ahead (turret)", 112.05, eff(110.0, 11.0, 3, 0.0, 0.0, -1.0), 0.1);
        expect("45mm @25, turret flank", 49.66, eff(45.0, 25.0, 1, -1.0, 0.0, 0.0), 0.1);
        expect("16mm @0, roof", 16.0, eff(16.0, 0.0, 2, 0.0, -1.0, 0.0), 0.01);
        // The same plate attacked from 10 degrees above: the tilted normal is met more squarely,
        // so the equivalent drops. This is the reason the sum is one dot product and not a slope
        // correction stacked on an angle correction.
        expect("80mm @55, 10 deg from above", 113.14,
                eff(80.0, 55.0, 3, 0.0, -Math.sin(Math.toRadians(10.0)),
                        -Math.cos(Math.toRadians(10.0))), 0.1);
        // No slope: the plain box-face angle, which is what every other vehicle still uses.
        expect("80mm @0, 60 deg from above (box)", 160.0,
                eff(80.0, 0.0, 3, 0.0, -Math.sin(Math.toRadians(60.0)),
                        -Math.cos(Math.toRadians(60.0))), 0.1);

        System.out.println();
        System.out.println("== the shipped Panther ==");
        require(panther != null, "the Panther is configured");
        if (panther == null) {
            finish();
            return;
        }
        expectTrue("kinetic hull is the steel, 80/50/40/16/16",
                panther.hull.front == 80.0F && panther.hull.side == 50.0F
                        && panther.hull.rear == 40.0F && panther.hull.roof == 16.0F
                        && panther.hull.floor == 16.0F);
        expectTrue("kinetic turret is 110/45/45/16/16",
                panther.turret.front == 110.0F && panther.turret.side == 45.0F
                        && panther.turret.rear == 45.0F);
        expectTrue("hull slopes 55/30/30", slopesAre(panther.hull_slopes, 55.0F, 30.0F, 30.0F));
        expectTrue("turret slopes 11/25/25",
                slopesAre(panther.turret_slopes, 11.0F, 25.0F, 25.0F));
        expectTrue("front split is 80 above / 60 below",
                panther.hull_front_split != null
                        && panther.hull_front_split.upper.thickness == 80.0F
                        && panther.hull_front_split.lower.thickness == 60.0F);

        ArmorConfig.ArmorOverride heat = panther.overrides.get("heat");
        expectTrue("the heat table is still the bare plates",
                heat != null && heat.hull.front == 80.0F && heat.hull.side == 50.0F
                        && heat.hull.rear == 40.0F && heat.turret.front == 110.0F
                        && heat.turret.side == 45.0F && heat.turret.rear == 45.0F);
        expectTrue("the heat split is 80/60 - not the line-of-sight figures",
                heat != null && heat.hull_front_split != null
                        && heat.hull_front_split.upper != null
                        && heat.hull_front_split.upper.thickness == 80.0F
                        && heat.hull_front_split.lower != null
                        && heat.hull_front_split.lower.thickness == 60.0F);

        // Only the steel tanks. The migration is per vehicle, and a slope that quietly appeared
        // on another tank would change its armour without anyone editing it.
        java.util.Set<String> slopedIds = new java.util.TreeSet<>();
        for (Map.Entry<String, ArmorConfig.VehicleArmor> entry : profile.vehicles.entrySet()) {
            ArmorConfig.VehicleArmor armor = entry.getValue();
            if (armor != null && (armor.hull_slopes != null || armor.turret_slopes != null)) {
                slopedIds.add(entry.getKey());
            }
        }
        java.util.Set<String> expectedSloped = new java.util.TreeSet<>(
                java.util.List.of("tank_assault:panther", "tank_assault:tiger"));
        expectTrue("only the steel tanks state slopes (" + slopedIds + ")",
                slopedIds.equals(expectedSloped));

        System.out.println();
        System.out.println("== the VT-4: equivalents, not steel ==");
        ArmorConfig.VehicleArmor vt4 = profile.vehicles.get("armor_pioneer:vt4");
        expectTrue("the VT-4 is configured", vt4 != null);
        if (vt4 != null) {
            expectTrue("it opts into the direction factor", vt4.kineticAngleScaling());
            // The other kind of entry. A composite array is published as an equivalent that
            // already includes the plate's obliquity, so a slope here would count it twice.
            expectTrue("and states no plate slopes",
                    vt4.hull_slopes == null && vt4.turret_slopes == null);
            expectTrue("its main array and belt are unchanged, 600/480",
                    vt4.hull_front_split.upper.thickness == 600.0F
                            && vt4.hull_front_split.lower_band.thickness == 480.0F);
            // The ricochet band is the one place the vehicle states steel: the near-horizontal
            // strip above the glacis, 125mm at the 81 degrees measured off the display model.
            expectTrue("its ricochet band is 125mm of steel at 81 degrees",
                    vt4.hull_front_split.upper_band.thickness == 125.0F
                            && vt4.hull_front_split.upper_band_slope == 81.0F);
            expect("ricochet band, dead ahead", 799.05,
                    eff(125.0, 81.0, 3, 0.0, 0.0, -1.0), 0.1);
            expect("ricochet band, 45 deg off the nose", 1130.13,
                    eff(125.0, 81.0, 3, -Math.sqrt(0.5), 0.0, -Math.sqrt(0.5)), 0.2);
            expect("ricochet band, 10 deg from above", 383.93,
                    eff(125.0, 81.0, 3, 0.0, -Math.sin(Math.toRadians(10.0)),
                            -Math.cos(Math.toRadians(10.0))), 0.2);
            expectTrue("its four FY-4E panels are still single-use layers",
                    vt4.part_armor.get("hull_era") != null
                            && vt4.part_armor.get("hull_era").era_hp == 200.0F
                            && vt4.part_armor.get("hull_era2").era_hp == 200.0F
                            && vt4.part_armor.get("turret_era").era_hp == 200.0F
                            && vt4.part_armor.get("turret_era2").era_hp == 200.0F);
            expect("600mm table, 30 deg off the nose", 692.82,
                    eff(600.0, 0.0, 3, -0.5, 0.0, -Math.sqrt(0.75)), 0.1);
        }
        // Only the composite tanks ask for the factor. A switch that arrived on another vehicle
        // would change its armour with no figure edited, exactly like a stray slope.
        java.util.Set<String> scaledIds = new java.util.TreeSet<>();
        for (Map.Entry<String, ArmorConfig.VehicleArmor> entry : profile.vehicles.entrySet()) {
            ArmorConfig.VehicleArmor armor = entry.getValue();
            if (armor != null && armor.kineticAngleScaling()) {
                scaledIds.add(entry.getKey());
            }
        }
        java.util.Set<String> expectedScaled = new java.util.TreeSet<>(
                java.util.List.of("armor_pioneer:k2", "armor_pioneer:vt4",
                        "armor_pioneer:vt5", "armor_pioneer:type10", "armor_pioneer:tianma20",
                        "armor_pioneer:ztz100", "armor_pioneer:leopard2a4"));
        expectTrue("only the composite tanks opt in (" + scaledIds + ")",
                scaledIds.equals(expectedScaled));

        System.out.println();
        System.out.println("== the K2 ==");
        ArmorConfig.VehicleArmor k2 = profile.vehicles.get("armor_pioneer:k2");
        expectTrue("the K2 is configured", k2 != null);
        if (k2 != null) {
            expectTrue("it opts into the direction factor", k2.kineticAngleScaling());
            expectTrue("and states no plate slopes",
                    k2.hull_slopes == null && k2.turret_slopes == null);
            expectTrue("its kinetic tables are the draft equivalents",
                    k2.turret.front == 700.0F && k2.hull.front == 650.0F
                            && k2.hull_front_split.upper.thickness == 650.0F
                            && k2.hull_front_split.lower.thickness == 200.0F);
            expect("700mm turret, 30 deg off the nose", 808.29,
                    eff(700.0, 0.0, 3, -0.5, 0.0, -Math.sqrt(0.75)), 0.1);
            expect("650mm glacis, 45 deg off the nose", 919.24,
                    eff(650.0, 0.0, 3, -s, 0.0, -s), 0.2);
        }

        System.out.println();
        System.out.println("== the Tiger E ==");
        expectTrue("the Tiger is configured", tiger != null);
        if (tiger != null) {
            expectTrue("its tables are the plates, 100/80/80/25",
                    tiger.turret.front == 100.0F && tiger.turret.side == 80.0F
                            && tiger.hull.front == 100.0F && tiger.hull.side == 80.0F);
            expectTrue("the driver's plate is 100mm at 9 degrees",
                    tiger.hull_front_split.upper.thickness == 100.0F
                            && tiger.hull_front_split.upper_slope == 9.0F);
            expectTrue("the lower nose is 100mm at 25 degrees",
                    tiger.hull_front_split.lower.thickness == 100.0F
                            && tiger.hull_front_split.lower_slope == 25.0F);
            expectTrue("the rear plate leans 9",
                    tiger.hull_slopes != null && tiger.hull_slopes.rear == 9.0F);
            expectTrue("the mantlet is the 120mm casting",
                    tiger.turret_weak_spot.thickness == 120.0F);
            expect("upper plate, dead ahead", 101.24, eff(100.0, 9.0, 3, 0.0, 0.0, -1.0), 0.1);
            expect("lower nose, dead ahead", 110.34, eff(100.0, 25.0, 3, 0.0, 0.0, -1.0), 0.1);
            expect("rear plate, square on", 81.00, eff(80.0, 9.0, -3, 0.0, 0.0, 1.0), 0.1);
        }

        System.out.println();
        System.out.println("== every face, once the vehicle states geometry ==");
        // A slope of zero does not mean "no equivalence" - it means the plate is square to its
        // box face (a vertical flank, a flat roof). Once a vehicle states any geometry at all, a
        // round arriving obliquely crosses more of that plate too, exactly as it does a glacis.
        expect("Tiger flank (80, vertical), 45 deg off the normal", 113.14,
                eff(80.0, 0.0, 1, -s, 0.0, -s), 0.1);
        expect("Tiger roof (25, flat), 60 deg from the normal", 50.0,
                eff(25.0, 0.0, 2, 0.0, -Math.sin(Math.toRadians(30.0)),
                        -Math.cos(Math.toRadians(30.0))), 0.1);
        expect("Tiger floor (25, flat), 60 deg from the normal", 50.0,
                eff(25.0, 0.0, -2, 0.0, Math.sin(Math.toRadians(30.0)),
                        -Math.cos(Math.toRadians(30.0))), 0.1);
        expect("Panther roof (16, flat), 60 deg from the normal", 32.0,
                eff(16.0, 0.0, 2, 0.0, -Math.sin(Math.toRadians(30.0)),
                        -Math.cos(Math.toRadians(30.0))), 0.1);
        expect("... square on it is still 16", 16.0,
                eff(16.0, 0.0, 2, 0.0, -1.0, 0.0), 0.01);
        expectTrue("the Tiger and the Panther state geometry; the K2 and the VT-5 do not",
                tiger != null && tiger.hasStatedGeometry()
                        && panther.hasStatedGeometry()
                        && profile.vehicles.get("armor_pioneer:k2") != null
                        && !profile.vehicles.get("armor_pioneer:k2").hasStatedGeometry()
                        && profile.vehicles.get("armor_pioneer:vt5") != null
                        && !profile.vehicles.get("armor_pioneer:vt5").hasStatedGeometry());

        System.out.println();
        System.out.println("== the zone the engine picks ==");
        initReflection();

        ArmorSystem.Zone glacis = zone(panther, false, 3, hull(0.0, 0.5, 0.0), false);
        expectTrue("glacis: 80mm at 55 deg (" + glacis.name + ")",
                glacis.thickness == 80.0F && glacis.slope == 55.0F);
        ArmorSystem.Zone lower = zone(panther, false, 3, hull(0.0, -0.5, 0.0), false);
        expectTrue("lower plate: 60mm at 55 deg (" + lower.name + ")",
                lower.thickness == 60.0F && lower.slope == 55.0F);
        ArmorSystem.Zone flank = zone(panther, false, 1, hull(0.0, 0.0, 0.0), false);
        expectTrue("hull flank: 50mm at 30 deg", flank.thickness == 50.0F && flank.slope == 30.0F);
        ArmorSystem.Zone rear = zone(panther, false, -3, hull(0.0, 0.0, 0.0), false);
        expectTrue("hull rear: 40mm at 30 deg", rear.thickness == 40.0F && rear.slope == 30.0F);
        ArmorSystem.Zone roof = zone(panther, false, 2, hull(0.0, 0.0, 0.0), false);
        expectTrue("hull roof: 16mm, no slope", roof.thickness == 16.0F && roof.slope == 0.0F);

        // The turret roof is in the same rule, and the reason it rarely ricochets is geometric:
        // a horizontal roof is met square-on by a plunging shell. A horizontal *line* would be
        // 90 degrees from its normal - but it cannot reach the roof top at all, it meets the
        // side; a 10-degree descent is 80 and is turned away; a 45-degree dive is 45 and
        // penetrates the 16mm. The steeper the dive, the squarer the hit.
        ArmorSystem.Zone turretRoof = zone(panther, true, 2, null, false);
        expectTrue("turret roof: 16mm, a face plate", turretRoof.thickness == 16.0F
                && turretRoof.facePlate && turretRoof.slope == 0.0F);
        double roofHorizontal = ArmorSystem.impactAngle(BOX, 2, new Vec3(0.0, 0.0, -1.0), 0.0F);
        double roofShallow = ArmorSystem.impactAngle(BOX, 2,
                new Vec3(0.0, -Math.sin(Math.toRadians(10.0)), -Math.cos(Math.toRadians(10.0))),
                0.0F);
        double roofSteep = ArmorSystem.impactAngle(BOX, 2,
                new Vec3(0.0, -Math.sin(Math.toRadians(45.0)), -Math.cos(Math.toRadians(45.0))),
                0.0F);
        expect("turret roof, a horizontal line", 90.0, roofHorizontal, 0.1);
        expect("turret roof, 10 deg descent", 80.0, roofShallow, 0.1);
        expect("turret roof, 45 deg descent", 45.0, roofSteep, 0.1);
        expectTrue("... a shallow descent is turned away, a dive is not",
                roofShallow >= 70.0 && roofSteep < 70.0);
        ArmorSystem.Zone face = zone(panther, true, 3, null, false);
        expectTrue("turret front: 110mm at 11 deg", face.thickness == 110.0F && face.slope == 11.0F);
        ArmorSystem.Zone mount = zone(panther, true, 3, null, true);
        expectTrue("mantlet: 100mm, no slope (" + mount.name + ")",
                mount.thickness == 100.0F && mount.slope == 0.0F);
        double[] port = {0.604 / PANTHER_HALF[0], 0.700 / PANTHER_HALF[1], 0.0};
        ArmorSystem.Zone mgPort = zone(panther, false, 3, port, false);
        expectTrue("machine-gun port: 80mm, no slope (" + mgPort.name + ")",
                mgPort.thickness == 80.0F && mgPort.slope == 0.0F);
        // A plain box of a vehicle, built here rather than borrowed from the config: every Armor
        // Pioneer tank now takes the direction factor, so the control has to be a table that asks
        // for nothing at all.
        ArmorConfig.VehicleArmor plain = new ArmorConfig.VehicleArmor();
        plain.turret = new ArmorConfig.ArmorSet(100.0F, 80.0F, 80.0F, 25.0F, 25.0F);
        plain.hull = new ArmorConfig.ArmorSet(100.0F, 80.0F, 80.0F, 25.0F, 25.0F);
        ArmorSystem.Zone plainFlank = zone(plain, false, 1, hull(0.0, 0.0, 0.0), false);
        expectTrue("a vehicle with no slopes still reads 0, and stays flat",
                plainFlank.slope == 0.0F
                        && !ArmorSystem.usesImpactAngle(plain, "ap", plainFlank));
        ArmorSystem.Zone pantherRoofZone = zone(panther, false, 2, hull(0.0, 0.0, 0.0), false);
        expectTrue("Panther roof: 16mm, no slope, measured along the arrival",
                ArmorSystem.usesImpactAngle(panther, "ap", pantherRoofZone));
        if (tiger != null) {
            ArmorSystem.Zone tigerUpper = zone(tiger, false, 3, hull(0.0, 0.5, 0.0), false);
            expectTrue("Tiger upper plate: 100mm at 9 deg",
                    tigerUpper.thickness == 100.0F && tigerUpper.slope == 9.0F
                            && tigerUpper.facePlate);
            ArmorSystem.Zone tigerLower = zone(tiger, false, 3, hull(0.0, -0.5, 0.0), false);
            expectTrue("Tiger lower nose: 100mm at 25 deg",
                    tigerLower.thickness == 100.0F && tigerLower.slope == 25.0F
                            && tigerLower.facePlate);
            ArmorSystem.Zone tigerRear = zone(tiger, false, -3, hull(0.0, 0.0, 0.0), false);
            expectTrue("Tiger rear: 80mm at 9 deg",
                    tigerRear.thickness == 80.0F && tigerRear.slope == 9.0F);
            ArmorSystem.Zone tigerTurret = zone(tiger, true, 3, null, false);
            expectTrue("Tiger turret front: 100mm, no slope",
                    tigerTurret.thickness == 100.0F && tigerTurret.slope == 0.0F);
            // The gate the damage path actually consults: a zero-slope face on a geometry-
            // stating vehicle is still measured along the arrival.
            ArmorSystem.Zone tigerRoof = zone(tiger, false, 2, hull(0.0, 0.0, 0.0), false);
            expectTrue("Tiger roof: 25mm, no slope, but measured along the arrival",
                    tigerRoof.thickness == 25.0F && tigerRoof.slope == 0.0F
                            && ArmorSystem.usesImpactAngle(tiger, "ap", tigerRoof));
            ArmorSystem.Zone tigerFlankZone = zone(tiger, false, 1, hull(0.0, 0.0, 0.0), false);
            expectTrue("Tiger vertical flank is measured along the arrival too",
                    ArmorSystem.usesImpactAngle(tiger, "ap", tigerFlankZone));
            expectTrue("... but a shaped charge is not",
                    !ArmorSystem.usesImpactAngle(tiger, "heat", tigerFlankZone));
        }
        if (vt4 != null) {
            ArmorSystem.Zone vt4Glacis = zone(vt4, false, 3, hull(0.0, 0.0, 0.0), false);
            expectTrue("VT-4 glacis: 600, a face plate, no slope",
                    vt4Glacis.thickness == 600.0F && vt4Glacis.facePlate
                            && vt4Glacis.slope == 0.0F);
            ArmorSystem.Zone band = zone(vt4, false, 3, hull(0.0, 0.5, 0.0), false);
            expectTrue("VT-4 ricochet band: 125mm at 81 deg, a face plate",
                    band.thickness == 125.0F && band.slope == 81.0F && band.facePlate);
            ArmorSystem.Zone lowerBand = zone(vt4, false, 3, hull(0.0, -0.5, 0.0), false);
            expectTrue("VT-4 lower band: 480, a face plate",
                    lowerBand.thickness == 480.0F && lowerBand.facePlate);
            ArmorSystem.Zone vt4Lower = zone(vt4, false, 3, hull(0.0, -0.96, 0.0), false);
            expectTrue("VT-4 lower plate: 180, a face plate",
                    vt4Lower.thickness == 180.0F && vt4Lower.facePlate);
            ArmorSystem.Zone hullEra = zone(vt4, false, 3, hull(0.0, 0.0, 0.0), false, "hull_era");
            expectTrue("VT-4 hull ERA: 200 single-use, NOT a face plate",
                    hullEra.thickness == 200.0F && hullEra.eraHp == 200.0F
                            && !hullEra.facePlate && hullEra.slope == 0.0F);
            // The ricochet angle is judged against the plate BEHIND a bolted-on layer, not the
            // layer's own cuboid hitbox - otherwise a horizontal shot at the shallow band would
            // read as square-on. Past the layers, the split names the 81-degree band again.
            ArmorSystem.Zone behindPanel = zonePast(vt4, false, 3, hull(0.0, 0.80, 0.0), false,
                    "hull_era2");
            expectTrue("... and the plate behind it is the 81-deg band",
                    behindPanel.thickness == 125.0F && behindPanel.slope == 81.0F
                            && behindPanel.facePlate);
            ArmorSystem.Zone turretEra = zone(vt4, true, 3, null, false, "turret_era");
            expectTrue("VT-4 turret ERA: 200 single-use, NOT a face plate",
                    turretEra.thickness == 200.0F && turretEra.eraHp == 200.0F
                            && !turretEra.facePlate);
            ArmorSystem.Zone turretFront = zone(vt4, true, 3, null, false);
            expectTrue("VT-4 turret front: 600, a face plate",
                    turretFront.thickness == 600.0F && turretFront.facePlate);
            ArmorSystem.Zone turretSide = zone(vt4, true, 1, null, false);
            expectTrue("VT-4 turret flank: 180, a face plate",
                    turretSide.thickness == 180.0F && turretSide.facePlate);
            ArmorSystem.Zone vt4Roof = zone(vt4, true, 2, null, false);
            expectTrue("VT-4 turret roof is measured along the arrival as well",
                    ArmorSystem.usesImpactAngle(vt4, "ap", vt4Roof));
            expectTrue("... the reactive panel itself is not",
                    !ArmorSystem.usesImpactAngle(vt4, "ap", hullEra));
        }
        if (k2 != null) {
            ArmorSystem.Zone k2Glacis = zone(k2, false, 3, hull(0.0, 0.0, 0.0), false);
            expectTrue("K2 glacis: 650, a face plate",
                    k2Glacis.thickness == 650.0F && k2Glacis.facePlate);
            ArmorSystem.Zone k2Turret = zone(k2, true, 3, null, false);
            expectTrue("K2 turret front: 700, a face plate",
                    k2Turret.thickness == 700.0F && k2Turret.facePlate);
            ArmorSystem.Zone k2Rws = zone(k2, true, 3, null, false, "commander_machine_gun");
            expectTrue("K2 commander RWS: 30mm box, NOT a face plate",
                    k2Rws.thickness == 30.0F && !k2Rws.facePlate);
        }

        System.out.println();
        System.out.println("== the rest of the composite fleet ==");
        // The other five Armor Pioneer tanks have the same kind of table: equivalents published
        // along the nose, no steel thickness to state a slope for. Each must take the direction
        // factor, leave its slope fields null, and present its stated front at 30 degrees.
        scaledFleet(profile, "armor_pioneer:vt5", 240.0F);
        scaledFleet(profile, "armor_pioneer:type10", 650.0F);
        scaledFleet(profile, "armor_pioneer:tianma20", 540.0F);
        scaledFleet(profile, "armor_pioneer:ztz100", 650.0F);
        scaledFleet(profile, "armor_pioneer:leopard2a4", 420.0F);

        System.out.println();
        System.out.println("== the round that states the point ==");
        expectTrue("kinetic profiles", ArmorSystem.isKineticProfile(null)
                && ArmorSystem.isKineticProfile("ap") && !ArmorSystem.isKineticProfile("heat")
                && !ArmorSystem.isKineticProfile("he"));
        double effectiveFront = eff(80.0, 55.0, 3, 0.0, 0.0, -1.0);
        double effectiveOblique = eff(80.0, 55.0, 3, -s, 0.0, -s);
        expectTrue(String.format("the Panther's 168mm AP pens 139mm head-on (residual %.1f)",
                168.0 - effectiveFront), 168.0 - effectiveFront > 0.0);
        expectTrue(String.format("... and fails against 197mm at 45 deg (residual %.1f)",
                168.0 - effectiveOblique), 168.0 - effectiveOblique < 0.0);

        System.out.println();
        System.out.println("== range and ricochet ==");
        ArmorConfig.Ammo ap = profile.ammo_penetration.get("tank_assault:cannon_75mm_pzgr_39_42");
        expectTrue("the Panther's AP has a range curve", ap != null && ap.hasRangeFalloff());
        if (ap != null) {
            expect("AP at 0m", 168.0, ap.penetrationAt(0.0F), 0.01);
            expect("AP at 500m", 147.2, ap.penetrationAt(500.0F), 0.1);
            expect("AP at 1000m", 131.7, ap.penetrationAt(1000.0F), 0.2);
            expect("AP at 2000m", 105.7, ap.penetrationAt(2000.0F), 0.2);
            expect("AP beyond 2000m holds", 105.7, ap.penetrationAt(5000.0F), 0.2);
        }
        ArmorConfig.Ammo dart = profile.ammo_penetration.get("armor_pioneer:cannon_125mm");
        expectTrue("the VT-4's dart has a range curve", dart != null && dart.hasRangeFalloff());
        if (dart != null) {
            expect("dart at 0m", 600.0, dart.penetrationAt(0.0F), 0.01);
            expect("dart at 1000m", 588.0, dart.penetrationAt(1000.0F), 0.1);
            expect("dart at 2000m", 576.0, dart.penetrationAt(2000.0F), 0.1);
            boolean variantCurve = dart.variants != null && !dart.variants.isEmpty()
                    && dart.variants.get(0).hasRangeFalloff();
            expectTrue("the second dart has its own curve", variantCurve);
            if (variantCurve) {
                expect("DTC10E at 1000m", 666.4, dart.variants.get(0).penetrationAt(1000.0F), 0.2);
            }
        }
        ArmorConfig.Ammo k2Dart = profile.ammo_penetration.get("armor_pioneer:cannon_120mm");
        expectTrue("the K2's dart has a range curve",
                k2Dart != null && k2Dart.hasRangeFalloff());
        if (k2Dart != null) {
            expect("K2 dart at 1000m", 588.0, k2Dart.penetrationAt(1000.0F), 0.1);
            boolean dm53Curve = k2Dart.variants != null && !k2Dart.variants.isEmpty()
                    && k2Dart.variants.get(0).hasRangeFalloff();
            expectTrue("the DM53 load has its own curve", dm53Curve);
            if (dm53Curve) {
                expect("DM53 at 1000m", 686.0, k2Dart.variants.get(0).penetrationAt(1000.0F), 0.1);
            }
        }
        ArmorConfig.Ammo tigerAp = profile.ammo_penetration.get(
                "tank_assault:cannon_88mm_pzgr_39");
        expectTrue("the Tiger's AP has a range curve",
                tigerAp != null && tigerAp.hasRangeFalloff());
        if (tigerAp != null) {
            expect("Tiger AP at 500m", 136.50, tigerAp.penetrationAt(500.0F), 0.2);
            expect("Tiger AP at 1000m", 122.82, tigerAp.penetrationAt(1000.0F), 0.2);
            expect("Tiger AP at 2000m", 103.06, tigerAp.penetrationAt(2000.0F), 0.2);
        }
        ArmorConfig.Ammo tigerApcr = profile.ammo_penetration.get(
                "tank_assault:cannon_88mm_pzgr_40");
        expectTrue("the Tiger's APCR has its own curve",
                tigerApcr != null && tigerApcr.hasRangeFalloff());
        if (tigerApcr != null) {
            expect("Tiger APCR at 1000m", 123.24, tigerApcr.penetrationAt(1000.0F), 0.2);
        }
        ArmorConfig.Ammo chemicalRound =
                profile.ammo_penetration.get("armor_pioneer:cannon_125mm_heat");
        expectTrue("a chemical round states no curve",
                chemicalRound != null && !chemicalRound.hasRangeFalloff());
        expectTrue("the ricochet angle is 70 degrees", profile.ricochet_angle == 70.0F);

        // The geometry the limit is measured on. The Panther's glacis is 66.1 degrees from its
        // normal 45 degrees off the nose - under the limit - and 73.3 at 60 degrees off, over it.
        double glacisAt45 = ArmorSystem.impactAngle(BOX, 3, new Vec3(-s, 0.0, -s), 55.0F);
        double glacisAt60 = ArmorSystem.impactAngle(BOX, 3,
                new Vec3(-Math.sqrt(0.75), 0.0, -0.5), 55.0F);
        expect("Panther glacis, 45 deg off the nose", 66.07, glacisAt45, 0.1);
        expect("Panther glacis, 60 deg off the nose", 73.33, glacisAt60, 0.1);
        expectTrue("... one is inside the 70 degree limit and the other is not",
                glacisAt45 < 70.0 && glacisAt60 >= 70.0);

        // The VT-4's ricochet band is 81 degrees from vertical - nine degrees above horizontal -
        // so the HORIZONTAL frontal shot is the one that grazes it: 81 degrees from the normal,
        // turned away. A ten-degree dive still arrives at 71, also turned away; a 45-degree dive
        // meets it 36 degrees off the normal and gets to measure the steel. The flatter the
        // trajectory, the more likely the ricochet, which is the whole point of the face.
        double bandFrontal = ArmorSystem.impactAngle(BOX, 3, new Vec3(0.0, 0.0, -1.0), 81.0F);
        double bandLowDive = ArmorSystem.impactAngle(BOX, 3,
                new Vec3(0.0, -Math.sin(Math.toRadians(10.0)), -Math.cos(Math.toRadians(10.0))),
                81.0F);
        double bandSteepDive = ArmorSystem.impactAngle(BOX, 3,
                new Vec3(0.0, -Math.sin(Math.toRadians(45.0)), -Math.cos(Math.toRadians(45.0))),
                81.0F);
        expect("VT-4 ricochet band, horizontal frontal", 81.0, bandFrontal, 0.1);
        expect("VT-4 ricochet band, 10 deg dive", 71.0, bandLowDive, 0.1);
        expect("VT-4 ricochet band, 45 deg dive", 36.0, bandSteepDive, 0.1);
        expectTrue("... horizontal fire is turned away, a steep dive is not",
                bandFrontal >= 70.0 && bandLowDive >= 70.0 && bandSteepDive < 70.0);

        finish();
    }

    /**
     * One composite-vehicle entry: the switch is on, no steel slopes are stated, the front is a
     * face plate at the stated equivalent, and a 30-degree shot measures 1/cos(30) of it.
     */
    private static void scaledFleet(ArmorConfig.Profile profile, String id, float turretFront)
            throws Exception {
        ArmorConfig.VehicleArmor armor = profile.vehicles.get(id);
        expectTrue(id + " opts into the direction factor",
                armor != null && armor.kineticAngleScaling());
        expectTrue(id + " states no steel slopes",
                armor != null && armor.hull_slopes == null && armor.turret_slopes == null);
        if (armor == null) {
            return;
        }
        ArmorSystem.Zone turret = zone(armor, true, 3, null, false);
        expectTrue(id + " turret front is a face plate at " + turretFront,
                turret.thickness == turretFront && turret.facePlate);
        expect(id + " turret, 30 deg off the nose",
                turretFront / Math.cos(Math.toRadians(30.0)),
                eff(turretFront, 0.0, 3, -0.5, 0.0, -Math.sqrt(0.75)), 0.2);
        expectTrue(id + " roof is a face plate too",
                zone(armor, true, 2, null, false).facePlate);
        String box = boxId(armor);
        if (box != null) {
            expectTrue(id + " a reactive or add-on box does not scale (" + box + ")",
                    !ArmorSystem.usesImpactAngle(armor, "ap",
                            zone(armor, false, 3, hull(0.0, 0.0, 0.0), false, box)));
        }
    }

    /** The first bolted-on box this vehicle carries, or null when it has none. */
    private static String boxId(ArmorConfig.VehicleArmor armor) {
        for (Map.Entry<String, ArmorConfig.Plate> entry : armor.part_armor.entrySet()) {
            return entry.getKey();
        }
        return null;
    }

    /** The engine's own arithmetic, for a shot travelling in the given direction. */
    private static double eff(double thickness, double slope, int face,
                              double x, double y, double z) {
        return ArmorSystem.effectiveThickness((float) thickness, (float) slope, BOX, face,
                new Vec3(x, y, z));
    }

    private static boolean slopesAre(ArmorConfig.PlateSlopes slopes, float front, float side,
                                     float rear) {
        return slopes != null && slopes.front == front && slopes.side == side
                && slopes.rear == rear;
    }

    private static void initReflection() throws Exception {
        hitClass = Class.forName("com.ywzjvehicle.armor.ArmorSystem$Hit");
        hitCtor = hitClass.getDeclaredConstructors()[0];
        hitCtor.setAccessible(true);
        resolveZone = ArmorSystem.class.getDeclaredMethod("resolveZone",
                ArmorConfig.VehicleArmor.class, String.class, hitClass);
        resolveZone.setAccessible(true);
    }

    /**
     * The zone the engine resolves for one impact, through its real {@code resolveZone}.
     *
     * <p>{@code throughLayers} is false and the profile is null - the first hit of a fight against
     * the vehicle's own table, which is the case the slopes are stated for.
     */
    private static ArmorSystem.Zone zone(ArmorConfig.VehicleArmor armor, boolean turret, int face,
                                         double[] hullLocal, boolean gunMount) throws Exception {
        return zone(armor, turret, face, hullLocal, gunMount, null);
    }

    private static ArmorSystem.Zone zone(ArmorConfig.VehicleArmor armor, boolean turret, int face,
                                         double[] hullLocal, boolean gunMount, String partId)
            throws Exception {
        Object hit = hitCtor.newInstance(turret, face, hullLocal, PANTHER_HALF, gunMount, 0.0,
                partId, false);
        return (ArmorSystem.Zone) resolveZone.invoke(null, armor, null, hit);
    }

    /** The same, but asked what lies behind the layers bolted to the outside. */
    private static ArmorSystem.Zone zonePast(ArmorConfig.VehicleArmor armor, boolean turret,
                                             int face, double[] hullLocal, boolean gunMount,
                                             String partId) throws Exception {
        Object hit = hitCtor.newInstance(turret, face, hullLocal, PANTHER_HALF, gunMount, 0.0,
                partId, true);
        return (ArmorSystem.Zone) resolveZone.invoke(null, armor, null, hit);
    }

    private static double[] hull(double x, double y, double z) {
        return new double[]{x, y, z};
    }

    private static void expect(String what, double want, double got, double tolerance) {
        boolean ok = Math.abs(want - got) <= tolerance;
        System.out.printf("  %-44s %8.2f  (want %8.2f)  %s%n", what, got, want,
                ok ? "ok" : "FAIL");
        if (!ok) {
            failures++;
        }
    }

    private static void expectTrue(String what, boolean ok) {
        System.out.printf("  %-44s %s%n", what, ok ? "ok" : "FAIL");
        if (!ok) {
            failures++;
        }
    }

    private static void require(boolean ok, String what) {
        expectTrue(what, ok);
    }

    private static void finish() {
        if (failures > 0) {
            System.out.println("RESULT: " + failures + " slope check(s) failed");
            System.exit(1);
        }
        System.out.println("RESULT: plate slopes consistent");
    }
}
