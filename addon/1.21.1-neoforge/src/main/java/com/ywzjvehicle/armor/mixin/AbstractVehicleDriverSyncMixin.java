package com.ywzjvehicle.armor.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * Keeps the client's control-unit operator in step with the periodic driver id - including when
 * there is no driver.
 *
 * <p>The base mod's {@code ControlUnit.getOperator()} caches the resolved entity, and
 * {@code AbstractVehicle.readData} only calls {@code setOperatorId}, which does not replace that
 * cache. A client that had already asked who was driving therefore kept answering with the old
 * driver after the id changed, and a packet with no {@code driverId} at all - the base's way of
 * saying "nobody" - changed nothing. The commander's driving takeover moves the operator to a
 * different seat, so the cached answer has to be replaced, not just re-numbered; without this the
 * commander's own client would never start sending movement input and the released state would
 * keep the last cache.
 *
 * <p>Setting the operator, rather than just the id, also keeps the base mod's existing driver
 * changes honest: the client's {@code InputHandler} compares the local player against
 * {@code getOperator()} every tick, and a stale cache makes the wrong player think they are
 * driving.
 *
 * <p>{@code readData} is only called from the server-to-client entity broadcast, so this never
 * rewrites the server's operator.
 */
@Mixin(AbstractVehicle.class)
public abstract class AbstractVehicleDriverSyncMixin {

    @Inject(method = "readData", at = @At("HEAD"))
    private void ywzjarmor$syncControlOperator(CompoundTag data, CallbackInfo ci) {
        AbstractVehicle self = (AbstractVehicle) (Object) this;
        if (!data.contains("driverId")) {
            self.controlUnit.setOperator(null);
            return;
        }
        Entity driver = self.level().getEntity(data.getInt("driverId"));
        self.controlUnit.setOperator(driver instanceof LivingEntity living ? living : null);
    }
}
