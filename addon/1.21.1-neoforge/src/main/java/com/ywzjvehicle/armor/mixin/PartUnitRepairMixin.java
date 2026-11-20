package com.ywzjvehicle.armor.mixin;

import com.ywzjvehicle.armor.ArmorSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.vehicle.vehicle.part.PartUnit;

/**
 * Puts a repaired reactive panel back in place.
 *
 * <p>A panel is single-use, and the addon remembers which ones a vehicle has used.  The repair
 * tool heals every part unit, including the part a reactive panel is carried by, but the base
 * knows nothing about the addon's spent set - so without this a repaired tank kept its panels
 * spent forever.  A health set that <em>raises</em> the part is a repair; a hit writes a lower
 * figure through {@code hurt}.  The equal-health case (the tool topping up an undamaged part)
 * is handled by {@code RepairToolMixin}, which sees the tool itself.
 *
 * <p>Runs on the server (the repair tool heals there); a client-side sync write also reaches
 * it, but the spent set lives on the server, so the call is a no-op there.
 */
@Mixin(PartUnit.class)
public abstract class PartUnitRepairMixin {

    @Inject(method = "setHealth", at = @At("HEAD"))
    private void ywzjarmor$rearmRepairedPanel(float health, CallbackInfo ci) {
        PartUnit<?> self = (PartUnit<?>) (Object) this;
        if (health > self.getHealth()) {
            ArmorSystem.rearm(self.getVehicle(), self.getId());
        }
    }
}
