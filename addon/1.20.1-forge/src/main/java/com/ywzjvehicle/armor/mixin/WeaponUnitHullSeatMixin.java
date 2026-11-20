package com.ywzjvehicle.armor.mixin;

import com.ywzjvehicle.armor.ArmorConfig;
import com.ywzjvehicle.armor.ArmorSystem;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * Lets an unmanned turret's gunner and commander sit in the hull.
 *
 * <p>The base mod computes a weapon station's rider position from the station's own structure
 * group - {@code worldSeatPosition} uses {@code worldPositionWithSelfRot} for an
 * {@code operator_on_weapon_unit} station and {@code worldPositionWithBaseRot} otherwise - so a
 * crew member on a traversing turret turns with it. That is correct for a manned turret. The
 * ZTZ-100 has a remote mount and three crew in the hull, so its gunner's and commander's bodies
 * must not turn; {@code hull_seat_ids} names the stations where the vehicle frame is wanted
 * instead.
 *
 * <p><b>Only the rider position is changed.</b> The guns, bolts, sights, cameras and hitboxes
 * still come from the station's groups, so the turret behaves exactly as before - the difference
 * is that the body stays put while the mount traverses. The base's eye-height subtraction is kept,
 * because the pack's {@code seat_offset} is an eye position.
 *
 * <p>No packet and no sync field: both sides read the same config, exactly as the armour lookup
 * does, and the result is a pure function of the vehicle's state.
 */
@Mixin(WeaponUnit.class)
public abstract class WeaponUnitHullSeatMixin {

    @Inject(method = "worldSeatPosition", at = @At("HEAD"), cancellable = true)
    private void ywzjarmor$hullSeatPosition(CallbackInfoReturnable<Vec3> cir) {
        WeaponUnit self = (WeaponUnit) (Object) this;
        AbstractVehicle vehicle = self.getVehicle();
        if (vehicle == null) {
            return;
        }
        ArmorConfig.VehicleArmor armor = ArmorSystem.armorFor(vehicle);
        if (armor == null || !armor.isHullSeat(self.getId())) {
            return;
        }
        LivingEntity owner = self.getOwner();
        float eyeHeight = owner == null ? 2.0F : owner.m_20192_();
        Vec3 seat = self.getSeatOffset();
        Vec3 local = new Vec3(seat.f_82479_, seat.f_82480_ - (double) eyeHeight, seat.f_82481_);
        // The vehicle frame only: this is PartUnit#worldPositionWithVehicleRot, which is private
        // in the base class and would otherwise be the call to make.
        Vector3f offset = vehicle.rotYXZ(1.0F)
                .transform(local.m_82546_(vehicle.centerOffset).m_252839_());
        cir.setReturnValue(vehicle.position(1.0F).m_82549_(vehicle.centerOffset)
                .m_82520_(offset.x, offset.y, offset.z));
    }
}
