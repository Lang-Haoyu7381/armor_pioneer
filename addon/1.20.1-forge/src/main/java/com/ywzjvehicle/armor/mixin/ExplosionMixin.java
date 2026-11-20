package com.ywzjvehicle.armor.mixin;

import com.ywzjvehicle.armor.ArmorSystem;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands the armour system the centre of the vanilla explosion whose damage is being applied.
 *
 * <p>{@code Explosion.explode()} calculates one damage number per affected entity and passes it
 * to {@code Entity.hurt}. The {@code DamageSource} it uses names the direct entity - the TNT, the
 * creeper, the end crystal - but not where the blast went off, and a bed or a respawn anchor has
 * no direct entity at all. Without the centre there is no direction, and without a direction the
 * armour table has no face to measure.
 *
 * <p>The injection sits on {@code explode()} itself, not on {@code finalizeExplosion}: the entity
 * loop that calls {@code hurt} is inside {@code explode()} in 1.20.1. The centre is pushed on the
 * way in and popped on every return, and {@link ArmorSystem#pushExplosion} keeps a stack so a
 * nested blast cannot steal the outer one's centre.
 *
 * <p>Nothing is decided here. The damage type check, the aircraft exclusion and the arithmetic all
 * live in {@link ArmorSystem}; this mixin only says where the blast was.
 */
@Mixin(Explosion.class)
public abstract class ExplosionMixin {

    /** The blast centre. Final, private, and read through the mixin's own shadow. */
    @Shadow
    @Final
    private double f_46013_;

    @Shadow
    @Final
    private double f_46014_;

    @Shadow
    @Final
    private double f_46015_;

    @Inject(method = "m_46061_", at = @At("HEAD"))
    private void ywzjarmor$pushBlastCentre(CallbackInfo ci) {
        ArmorSystem.pushExplosion(new Vec3(f_46013_, f_46014_, f_46015_));
    }

    @Inject(method = "m_46061_", at = @At("RETURN"))
    private void ywzjarmor$popBlastCentre(CallbackInfo ci) {
        ArmorSystem.popExplosion();
    }
}
