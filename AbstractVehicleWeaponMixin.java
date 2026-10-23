package com.ywzjvehicle.armor.mixin;

import com.ywzjvehicle.armor.ArmorConfig;
import com.ywzjvehicle.armor.GunnerSettings;
import com.ywzjvehicle.armor.RoundReload;
import com.ywzjvehicle.armor.VehicleArmorMod;
import com.ywzjvehicle.armor.network.GunnerStateMessage;
import com.ywzjvehicle.armor.network.VehicleArmorNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.UUID;

/**
 * Re-feeds a gun when the gunner changes what it is loaded with, and fits the round when that
 * finishes.
 *
 * <p>Nothing about a shell changes until it is in the gun. That is the whole of this class: the
 * chooser records a request, this starts the base mod's own reload, the gun is unusable while it
 * runs - {@code shoot} refuses while {@code isReloading} - and at the end the requested round
 * becomes the loaded one. Before it, the gunner is still firing the old round; after it, the new
 * one. A load that took effect the instant it was clicked was a number changing with nothing to
 * show for it, which is exactly how it was reported.
 *
 * <p><b>The reload is the base mod's, ticked by this class.</b> {@code tickReload} is only reached
 * from {@code tick} when the magazine is empty - a gun with rounds in it never reloads itself - so a
 * reload started here would otherwise sit at its first tick forever and leave the gun unable to
 * fire. The magazine is deliberately left alone: dumping a full belt to force a reload would cost
 * the crew every round in it. What the reload does cost is what any reload costs, which is the time
 * it takes, and on a gun that is not full, the rounds it tops itself up with.
 */
@Mixin(AbstractVehicleWeapon.class)
public abstract class AbstractVehicleWeaponMixin implements RoundReload {

    /** Set while this gun is being re-fed with the round the gunner asked for. */
    @Unique
    private boolean ywzjarmor$roundReload;

    @Override
    public void ywzjarmor$startRoundReload() {
        ywzjarmor$roundReload = true;
        ((AbstractVehicleWeapon<?>) (Object) this).startReload();
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void ywzjarmor$tickRoundReload(CallbackInfo ci) {
        if (!ywzjarmor$roundReload) {
            return;
        }
        AbstractVehicleWeapon<?> self = (AbstractVehicleWeapon<?>) (Object) this;
        if (self.getVehicle().m_9236_().m_5776_()) {
            return;
        }
        // An empty gun is already reloading on its own, and the base mod is ticking that one - so
        // this ticks only the reload it started itself, or the two would run at double speed.
        if (self.getRemainAmmo() > 0 && self.isReloading()) {
            self.tickReload();
        }
        if (self.isReloading()) {
            return;
        }
        ywzjarmor$roundReload = false;
        ywzjarmor$fit(self);
    }

    /**
     * Puts the requested round in the gun, and tells the gunner it is there.
     *
     * <p>Silent when nothing was waiting: a gun that reloaded for its own reasons - an empty
     * magazine refilling - must not announce a round nobody chose.
     */
    @Unique
    private void ywzjarmor$fit(AbstractVehicleWeapon<?> self) {
        LivingEntity owner = self.getWeaponUnit().getOwner();
        if (!(owner instanceof ServerPlayer player)) {
            return;
        }
        UUID id = player.m_20148_();
        int fitted = GunnerSettings.commitLoad(id);
        if (fitted < 0) {
            return;
        }
        ArmorConfig.Ammo round = ArmorConfig.ammoFor(self.getData().getWeaponId());
        if (round == null) {
            return;
        }
        VehicleArmorMod.LOGGER.info("[Vehicle Armor] {} loaded {} ({}mm)",
                player.m_7755_().getString(),
                round.variantNames(round.name == null ? "?" : round.name).get(fitted),
                round.variant(fitted).penetration);
        // The client announces a round by noticing that it changed, so the state has to be sent
        // again for the action bar to say anything - and this is the moment the change really
        // happened, rather than the moment it was asked for.
        VehicleArmorNetwork.channel().send(PacketDistributor.PLAYER.with(() -> player),
                GunnerStateMessage.of(id));
    }
}
