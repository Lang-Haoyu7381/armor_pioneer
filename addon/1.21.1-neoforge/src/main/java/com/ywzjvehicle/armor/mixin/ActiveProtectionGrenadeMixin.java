package com.ywzjvehicle.armor.mixin;

import com.ywzjvehicle.armor.ApsIntercept;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.weapon.ActiveProtectionGrenadeEntity;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Gives the Armor Pioneer APS a swept intercept instead of a point sample.
 *
 * <p>See {@link ApsIntercept} for why the base check misses a fast cannon shell. This adds a second
 * pass at the end of the grenade's tick: if the base's own 8-block box caught nothing, the segment
 * the interceptor flew since the previous tick is searched within {@link ApsIntercept#RADIUS}, and
 * the fastest live projectile on it is destroyed with the base's own effects. The base code is
 * untouched and still runs first, so where it does catch something nothing changes.
 *
 * <p><b>Only for {@code armor_pioneer} vehicles.</b> The gate is the launching vehicle's id
 * namespace, so every other pack's APS - including the VT-4A1's - keeps the original behaviour.
 * The addon owns this pack's numbers already; it has no business changing another pack's weapon.
 *
 * <p>Friendly projectiles are excluded. The base does not filter at all, and a swept 10-block
 * volume around the launcher would otherwise eat the tank's own main-gun round as it left the
 * barrel - the failure that makes this mixin worse than no mixin.
 *
 * <p>Common mixin, listed in {@code "mixins"}: the class it targets is common and the check is
 * server-side only.
 */
@Mixin(ActiveProtectionGrenadeEntity.class)
public abstract class ActiveProtectionGrenadeMixin {

    /** Where the interceptor was at the start of this tick, for the segment test. */
    @Unique
    private Vec3 ywzjarmor$lastPosition;

    @Inject(method = "tick", at = @At("HEAD"))
    private void ywzjarmor$rememberPosition(CallbackInfo ci) {
        ywzjarmor$lastPosition = ((ActiveProtectionGrenadeEntity) (Object) this).position();
    }

    /**
     * Strips friendly projectiles from the base's own candidate list.
     *
     * <p>Without this the base can eat the other launcher's interceptor - the two launchers are
     * 2.4 blocks apart, inside the base's 8-block box - and the tank's own main-gun round as it
     * leaves the muzzle. Both are the same failure the sweep's friendly filter guards against, and
     * the base's check runs before it, so the filter has to be applied here too.
     */
    @Redirect(method = "tick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getEntities"
                            + "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;"
                            + "Ljava/util/function/Predicate;)Ljava/util/List;"))
    private List<Entity> ywzjarmor$filterCandidates(Level level, Entity self, AABB box,
                                                    Predicate<Entity> predicate) {
        List<Entity> found = level.getEntities(self, box, predicate);
        ActiveProtectionGrenadeEntity aps = (ActiveProtectionGrenadeEntity) (Object) this;
        AbstractVehicle vehicle = aps.vehicle;
        if (ApsIntercept.isOurs(vehicle)) {
            found.removeIf(entity -> ApsIntercept.isFriendly(vehicle, entity));
        }
        return found;
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void ywzjarmor$sweepForFastRounds(CallbackInfo ci) {
        ActiveProtectionGrenadeEntity self = (ActiveProtectionGrenadeEntity) (Object) this;
        // The base caught something and removed itself; the client has nothing to decide.
        if (self.level().isClientSide() || self.isRemoved()) {
            return;
        }
        AbstractVehicle vehicle = self.vehicle;
        if (!ApsIntercept.isOurs(vehicle)) {
            return;
        }
        Vec3 to = self.position();
        Vec3 from = ywzjarmor$lastPosition == null ? to : ywzjarmor$lastPosition;
        AABB search = ApsIntercept.searchBox(from, to, ApsIntercept.RADIUS);
        List<Entity> candidates = self.level().getEntities(self, search,
                entity -> entity != self
                        && ApsIntercept.isCandidate(vehicle, entity)
                        && ApsIntercept.withinPath(from, to, entity.position()));
        if (candidates.isEmpty()) {
            return;
        }
        candidates.sort(Comparator.comparingDouble(
                entity -> -entity.getDeltaMovement().lengthSqr()));
        ywzjarmor$destroy(self, (Projectile) candidates.get(0));
    }

    /**
     * The base's own removal sequence: smoke at both ends, a trail along the line, the extinguisher
     * sound, and the intercept tip to the shell's owner. Kept identical so the swept catch looks
     * exactly like a base catch.
     */
    @Unique
    private void ywzjarmor$destroy(ActiveProtectionGrenadeEntity self, Projectile target) {
        ServerLevel level = (ServerLevel) self.level();
        Vec3 from = self.position();
        Vec3 to = target.position();
        for (ServerPlayer player : level.getPlayers(candidate -> candidate.distanceTo(self) < 256.0F)) {
            level.sendParticles(player, ParticleTypes.EXPLOSION, true,
                    from.x, from.y, from.z, 3, 0.2, 0.2, 0.2, 0.15);
            level.sendParticles(player, ParticleTypes.EXPLOSION, true,
                    to.x, to.y, to.z, 3, 0.2, 0.2, 0.2, 0.15);
            Vec3 delta = to.subtract(from);
            int steps = 16;
            for (int i = 0; i <= steps; i++) {
                double t = (double) i / (double) steps;
                Vec3 pos = from.add(delta.scale(t));
                level.sendParticles(ParticleTypes.SMOKE, pos.x, pos.y, pos.z,
                        1, 0.0, 0.02, 0.0, 0.0);
            }
            level.playSound(null, from.x, from.y, from.z,
                    SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 0.4F, 1.6F);
        }
        self.discard();
        target.discard();
        if (target.getOwner() instanceof ServerPlayer shooter) {
            shooter.displayClientMessage(Component.translatable("tips.active_protection_system_intercept"), true);
        }
    }
}
