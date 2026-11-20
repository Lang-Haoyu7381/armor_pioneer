package com.ywzjvehicle.armor.mixin;

import com.ywzjvehicle.armor.WeaponEffects;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.Collections;
import java.util.List;

/**
 * Takes the base mod's fire sound out of the shot for the weapons {@link WeaponEffects} covers.
 *
 * <p>The base mod plays a weapon's fire sound from its display file, and every 105, 120 and 125 mm
 * gun in the packs points at the same {@code ywzj_vehicle:cannon_125mm_shot}. Leaving that call in
 * place would put two reports on the same shot; {@code WeaponEffects} plays a calibre-appropriate
 * one, so this redirect drops only that one call and leaves the method's smoke, its shell sound and
 * its animations exactly as they were.
 *
 * <p><b>0.6.2.1 split the method these redirects sat in.</b> In 0.6.2 {@code onClientFire} called
 * {@code Level#playLocalSound} and {@code WeaponUnit#aimContext}/{@code aimContexts} itself. In
 * 0.6.2.1 the sound moved into {@code playFireSounds} and the context selection into
 * {@code getFiringAimContexts}, so the three old redirects resolve to nothing and the mixin's
 * {@code defaultRequire 1} turns that into a load-time failure. The sound redirect follows the
 * call into {@code playFireSounds}; the context redirect stays in {@code onClientFire} and wraps
 * its one call to {@code getFiringAimContexts}. Wrapping that call rather than redirecting inside
 * {@code getFiringAimContexts} is deliberate: the same method also builds the contexts
 * {@code doClientShoot} sends to the server, and those must keep the base's own bolt positions -
 * this mixin only moves the client-side muzzle effects.
 *
 * <p>Client-only, and listed under {@code client} in the mixin configuration: {@code onClientFire}
 * only ever runs on a client, and it reaches a client-only class.
 */
@Mixin(AbstractVehicleWeapon.class)
public abstract class AbstractVehicleWeaponFireMixin {

    @Redirect(
            method = "playFireSounds",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;playSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V"
            )
    )
    private void ywzjarmor$suppressBaseFireSound(Level level, Player player,
                                                 double x, double y, double z,
                                                 SoundEvent sound, SoundSource source,
                                                 float volume, float pitch) {
        AbstractVehicleWeapon<?> self = (AbstractVehicleWeapon<?>) (Object) this;
        if (WeaponEffects.handles(self)) {
            return;
        }
        level.playSound(player, x, y, z, sound, source, volume, pitch);
    }

    /**
     * Replaces the one context-selection call {@code onClientFire} makes with the muzzle-corrected
     * list, reproducing the base's own switch so the number and order of effects do not change.
     *
     * <p>Only the effect positions change. The returned list has the same length and order, so
     * {@code countFire(aimContexts.size())} and the number of puffs are untouched; a rear bolt's
     * puff is simply spawned at the front muzzle instead.
     */
    @Redirect(
            method = "onClientFire",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/ywzj/vehicle/vehicle/weapon/AbstractVehicleWeapon;getFiringAimContexts()Ljava/util/List;"
            )
    )
    private List<AimContext> ywzjarmor$frontContexts(AbstractVehicleWeapon<?> self) {
        WeaponUnit unit = self.getWeaponUnit();
        return switch (unit.getFiringMode()) {
            case RIPPLE -> Collections.singletonList(WeaponEffects.baseContext(unit));
            case SALVO, FULL_SALVO -> WeaponEffects.baseContexts(unit);
        };
    }
}
