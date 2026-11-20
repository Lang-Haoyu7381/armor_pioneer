package com.ywzjvehicle.armor.mixin;

import com.ywzjvehicle.armor.CrewRoles;
import com.ywzjvehicle.armor.Fording;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.RotatableUnit;
import org.ywzj.vehicle.vehicle.part.SwitchableUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * Locks the main turret's traverse while the hull is sealed for deep fording.
 *
 * <p>The real turret ring is part of the seal: an inflatable gasket fills the gap, and the turret
 * cannot rotate until it is deflated. That is why the lock is tied to the seal rather than to the
 * whole preparation - and why the gun can still elevate and fire through the rubber cap over the
 * muzzle, which is exactly what the cap is for.
 *
 * <p><b>Yaw only.</b> {@code RotatableUnit.tickRot} moves both axes under one gate, so reusing that
 * gate would freeze the gun's elevation too and turn "sealed" into "combat ineffective". This
 * instead holds {@code yRot} at the value it had when the seal was made, one tick at a time, and
 * leaves {@code xRot} alone. The turret snaps back the moment the seal is broken, which is also
 * what a real crew sees when the gasket deflates.
 *
 * <p><b>Both sides.</b> The client is where a shot's {@code AimContext} is built - from the
 * structure group's rotation, which this method re-applies - so rooting the turret only on the
 * server would leave the client firing at its crosshair while the server showed a frozen barrel.
 * The seal boolean is the snorkel part, which the base mod already syncs; that is why no new packet
 * is needed for the lock.
 *
 * <p>Applied after the part's own tick rather than at {@code tickRot}: for a {@code WeaponUnit},
 * {@code tickRot} keeps running after {@code super.tickRot()} returns - the stabiliser branch
 * writes {@code yRot} again - so a reset there would be undone on the next line.
 */
@Mixin(RotatableUnit.class)
public abstract class RotatableUnitFordingMixin {

    @Shadow
    protected float yRot;

    @Shadow
    public float yRotO;

    /** Whether this part is currently being held; the yaw captured on the edge into held. */
    @Unique
    private boolean ywzjarmor$held;

    @Unique
    private float ywzjarmor$heldYaw;

    @Inject(method = "tick", at = @At("TAIL"))
    private void ywzjarmor$holdSealedTraverse(CallbackInfo ci) {
        RotatableUnit<?> self = (RotatableUnit<?>) (Object) this;
        if (!(self instanceof WeaponUnit)) {
            ywzjarmor$held = false;
            return;
        }
        AbstractVehicle vehicle = self.getVehicle();
        if (vehicle == null) {
            ywzjarmor$held = false;
            return;
        }
        // Only the main gun's mount is sealed by the turret ring. The commander's RWS and the coax
        // sit outside it and keep their own power of movement.
        if (CrewRoles.primaryWeaponUnit(vehicle) != self) {
            ywzjarmor$held = false;
            return;
        }
        SwitchableUnit<?> snorkel = Fording.snorkelOf(vehicle);
        if (snorkel == null || !snorkel.isOn()) {
            ywzjarmor$held = false;
            return;
        }
        if (!ywzjarmor$held) {
            // The edge into sealed: whatever the turret was pointing at is the firing direction for
            // the whole crossing. Captured per side, because the client's copy is the one that
            // builds the shot.
            ywzjarmor$heldYaw = this.yRot;
            ywzjarmor$held = true;
        }
        this.yRot = ywzjarmor$heldYaw;
        this.yRotO = this.yRot;
        // Re-apply now, so the pose the renderer and the client's AimContext read is already the
        // frozen one on this tick rather than the next.
        self.updateRot();
    }
}
