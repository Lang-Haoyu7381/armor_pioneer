package com.ywzjvehicle.armor.mixin;

import com.ywzjvehicle.armor.CrewControl;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.network.message.ClientVehicleAction;
import org.ywzj.vehicle.vehicle.part.PartUnit;

/**
 * Makes a shot fired while the commander is laying the gun leave along the gun's line.
 *
 * <p>The base mod builds the shot on the client and sends the muzzle position and direction with
 * the packet; the server fires exactly what it is given. That is fine while the owner's mouse is
 * the only thing aiming the gun. With a commander's order active, the server has moved the gun -
 * and the client's copy is made to follow - but a client can still be a tick behind, and a shot
 * that is a tick behind is a miss the player will blame on the gun. Rewriting the contexts here,
 * before {@code PartUnit} hands them to the vehicle, keeps the order authoritative without
 * cancelling or rebuilding the shot.
 *
 * <p>It only touches rounds from the vehicle's main gun, and only while an order is active: every
 * other shot keeps the base mod's own contexts, byte for byte.
 */
@Mixin(PartUnit.class)
public abstract class CrewShotMixin {

    @Shadow
    @Final
    protected AbstractVehicle vehicle;

    @Inject(method = "onClientMessageReceived", at = @At("HEAD"))
    private void ywzjarmor$rewriteCrewShot(ClientVehicleAction message, Player player,
                                           CallbackInfo ci) {
        if (message.shoot) {
            CrewControl.rewriteShot(this.vehicle, message.partUnitIndex, message.aimContexts);
        }
    }
}
