package com.ywzjvehicle.armor.mixin;

import com.ywzjvehicle.armor.ArmorSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.item.RepairToolItem;

/**
 * Re-arms a repaired vehicle's reactive panels when the repair tool is used on it.
 *
 * <p>This is the half of the repair hook that {@code PartUnitRepairMixin} cannot see: a tank
 * whose panels stopped their rounds may have taken no damage at all, so every part is already
 * at full health and the tool's per-part {@code setHealth} writes the same value.  The tool's
 * own heal of the vehicle is the unambiguous signal that a repair is happening, and it fires
 * even at full vehicle health because the tool always calls {@code setHealth}.
 */
@Mixin(RepairToolItem.class)
public abstract class RepairToolMixin {

    @Redirect(method = "m_5929_(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;I)V",
            at = @At(value = "INVOKE",
                    target = "Lorg/ywzj/vehicle/entity/vehicle/AbstractVehicle;setHealth(F)V"))
    private void ywzjarmor$rearmOnRepair(AbstractVehicle vehicle, float health) {
        ArmorSystem.rearmAll(vehicle);
        vehicle.setHealth(health);
    }
}
