package com.ywzjvehicle.armor;

/**
 * A hatch periscope: a crew station's own observation position, on the vehicle's structure.
 *
 * <p>The base mod has three views - third person, the operator's head, and a weapon's optical
 * sight - and the operator's head sits where the weapon unit says it does. A hatch periscope is
 * neither: it is a fixed point on the vehicle that the commander looks out of while buttoned up,
 * and it is measured off the structure model in the same frame as the pack's
 * {@code operator_view_offset}. This block supplies that point and nothing else.
 *
 * <p><b>The block's presence is the switch.</b> A vehicle with no entry has no periscope, so the
 * key says so instead of moving the camera to the origin. That is the same rule {@code fording},
 * {@code hybrids} and {@code suspensions} follow.
 *
 * <p><b>The offset is from the seat part's own pivot, not from the vehicle's origin.</b>
 * {@code seat_part_id} names the part the crew member must be sitting in, and the camera is
 * {@code part.pivotOffset + offset}. The base's {@code operator_view_offset} is measured the same
 * way, so a pack's existing sight figure and this one can be compared without converting frames.
 * {@code PeriscopeCheck} reads the pack and holds the shipped figures to the geometry.
 */
public final class Periscope {

    private Periscope() {
    }

    /** One vehicle's hatch periscope, as configured. */
    public static final class Settings {
        /**
         * The part unit whose seat the periscope belongs to. The player must occupy that seat; a
         * gunner in the same vehicle gets a sentence rather than the commander's view.
         */
        public String seat_part_id = "commander_machine_gun";

        /**
         * The part whose yaw the periscope turns with, or blank for a point fixed to the hull.
         *
         * <p>A hatch on a turret has to follow the turret's <b>yaw</b> and not its gun's pitch -
         * the base's {@code worldPositionWithBaseRot} would apply the whole parent rotation, which
         * on the VT-5 would swing the camera off the hatch and into the roof as the main gun
         * depresses. Naming the turret here makes this class apply the yaw alone. A periscope on a
         * hull roof leaves it blank and stays with the hull.
         */
        public String yaw_part_id = "turret";

        /**
         * Camera position in blocks from that part's pivot, in the structure model's frame -
         * the same frame and the same addition as a pack's {@code operator_view_offset}.
         */
        public float offset_x = 0.0F;
        public float offset_y = 0.0F;
        public float offset_z = 0.0F;
    }
}
