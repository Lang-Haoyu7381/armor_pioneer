package com.ywzjvehicle.armor.mixin;

import com.ywzjvehicle.armor.ArmorSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.vehicle.vehicle.part.PartUnit;

/**
 * Hides a destroyed reactive panel's display bone, while leaving the part attached.
 *
 * <p>The base renderer's {@code applyDetachedPart} sets a part's display bone invisible when
 * the part is detached or when {@code rendersBone()} is true.  A reactive panel is not
 * detached when it is used - its OBB must stay in the hit list so the next round meets the
 * armour behind an empty box - so this reports "renders its own bone" for a destroyed panel,
 * which hides the display model but leaves the hitbox.  Repairing the part restores its
 * health, {@code rendersBone()} goes back to false, and the model reappears.
 *
 * <p>Client-only: {@code rendersBone()} is read by the entity renderer.  The vehicle JSON
 * gives the four panels a {@code render_bone} naming the display bone, which is what makes
 * the base renderer look at them in the first place.
 */
@Mixin(PartUnit.class)
public abstract class PartUnitRenderMixin {

    @Inject(method = "rendersBone", at = @At("HEAD"), cancellable = true)
    private void ywzjarmor$hideDestroyedPanel(CallbackInfoReturnable<Boolean> cir) {
        PartUnit<?> self = (PartUnit<?>) (Object) this;
        if (self.isDestroyed()
                && ArmorSystem.isReactivePanel(self.getVehicle(), self.getId())) {
            cir.setReturnValue(true);
        }
    }
}
