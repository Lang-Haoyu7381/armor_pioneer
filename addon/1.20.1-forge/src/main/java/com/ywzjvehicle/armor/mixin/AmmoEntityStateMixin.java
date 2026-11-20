package com.ywzjvehicle.armor.mixin;

import com.ywzjvehicle.armor.ArmorConfig;
import com.ywzjvehicle.armor.Fuze;
import com.ywzjvehicle.armor.ShellState;
import com.ywzjvehicle.armor.GunnerSettings;
import com.ywzjvehicle.armor.VehicleArmorMod;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.vehicle.entity.weapon.AmmoEntity;
import org.ywzj.vehicle.util.VehicleExplosion;
import org.ywzj.vehicle.vehicle.pojo.Explosion;

import java.util.UUID;

/**
 * Gives a shell its fuze.
 *
 * <p>Everything here hangs off one question asked on the shell's first tick: is this weapon a
 * multi-purpose round, and if so what did the gunner have selected? An ordinary shell answers no and
 * is left completely alone - not one field of it is touched.
 *
 * <p>The fuze belongs to the shell rather than to the gun on purpose. Two M1A1s can be carrying
 * different fuzes at once, and a shell already in the air keeps the fuze it was fired with even if
 * the gunner switches immediately afterwards.
 *
 * <p>Three of the four pieces of behaviour are here rather than in the weapon data:
 * <ul>
 *   <li>Adoption, including a private copy of the explosion. The pack's {@code Explosion} object is
 *       shared by every shell that weapon ever fires, so setting {@code proximityFuze} on it
 *       directly would arm every round the tank has fired or will fire.</li>
 *   <li>The delay fuze's break-through, as a redirect on the one block clip in {@code tickHit}.
 *       Breaking the block and reporting a miss for it skips the whole block branch - the
 *       particles, the explosion and the shell's own removal - leaving it in the air.</li>
 *   <li>The crawl afterwards, and the manual detonation when the countdown runs out.</li>
 * </ul>
 *
 * <p>Nothing here is client-side: {@code tickHit} is only called when the level is not client-side,
 * so the fuze never has to be synced to be correct.
 */
@Mixin(AmmoEntity.class)
public abstract class AmmoEntityStateMixin implements ShellState {

    /**
     * Speed a delay-fuzed shell is held at once it is through its layer of cover, in blocks per tick.
     *
     * <p>A third of a block a tick turns the delay into a distance - half a second is about three
     * blocks past the wall, five seconds is about twenty-five. A decay factor was the obvious
     * alternative and does not work: the shell would stall within a few ticks and every setting
     * above the first would land in the same place.
     */
    private static final double CRAWL_SPEED = 0.25;

    /** Set by the target to mark the shell for removal; there is no public way to write it. */
    @Shadow
    private boolean discard;

    /** Ticks the shell has left before the base removes it, counted down by {@code BulletEntity}. */
    @Shadow
    public int life;

    /** The round's explosion, shared between every shell fired from the same weapon. */
    @Shadow
    public Explosion explosion;

    @Unique
    private Fuze ywzjarmor$fuze = Fuze.IMPACT;
    @Unique
    private int ywzjarmor$delayTicks = GunnerSettings.DELAY_MIN_TICKS;
    @Unique
    private int ywzjarmor$load;
    @Unique
    private int ywzjarmor$countdown;
    @Unique
    private boolean ywzjarmor$adopted;
    @Unique
    private boolean ywzjarmor$armed;
    @Unique
    private boolean ywzjarmor$passed;
    @Unique
    private boolean ywzjarmor$detonated;
    @Unique
    private double ywzjarmor$dirX;
    @Unique
    private double ywzjarmor$dirY;
    @Unique
    private double ywzjarmor$dirZ;

    @Override
    public Fuze ywzjarmor$fuze() {
        return ywzjarmor$fuze;
    }

    @Override
    public int ywzjarmor$delayTicks() {
        return ywzjarmor$delayTicks;
    }

    @Override
    public int ywzjarmor$load() {
        return ywzjarmor$load;
    }

    /**
     * Runs at the top of every hit tick: adopts the fuze on the first one, then drives the delay
     * fuze's countdown.
     */
    @Inject(method = "tickHit", at = @At("HEAD"), cancellable = true)
    private void ywzjarmor$tickFuze(CallbackInfo ci) {
        if (!ywzjarmor$adopted) {
            ywzjarmor$adopted = true;
            ywzjarmor$adopt();
        }
        if (ywzjarmor$fuze != Fuze.DELAY || !ywzjarmor$armed) {
            return;
        }
        // tickMove applies drag and gravity to whatever is set here at the end of the tick, so the
        // heading is restated from scratch rather than scaled down: scaling would let the two
        // accumulate and slowly push the shell off the line it was fired along.
        ywzjarmor$holdCourse();
        if (--ywzjarmor$countdown <= 0) {
            ywzjarmor$detonate();
            ci.cancel();
        }
    }

    /**
     * Takes the settings the gunner had when this shell was fired, and re-arms the shell to match.
     *
     * <p>The gunner is the shell's own owner, which the base sets when the round is fired, so a
     * shell cannot pick up another player's setting however many are firing at once.
     *
     * <p>What is read first is the load, before any question of whether the round has a fuze. That
     * ordering is the whole point: a dart has loads and a fixed fuze, and an early return written
     * for the fuze - which is what this used to open with - left every dart frozen on the round the
     * pack names it for. The chooser worked, the setting was stored, the log said a load had been
     * chosen, and the shell went on firing the original. Reading the load before deciding what else
     * to do makes that impossible to reintroduce by accident here.
     */
    @Unique
    private void ywzjarmor$adopt() {
        AmmoEntity self = (AmmoEntity) (Object) this;
        ArmorConfig.Ammo ammo = ArmorConfig.ammoFor(self.getWeaponId());
        if (ammo == null) {
            // Not a round this addon configures: nothing to adopt, and nothing to log either, or
            // every machine-gun burst on an unconfigured vehicle would fill the console.
            return;
        }

        Entity owner = self.m_19749_();
        UUID ownerId = owner == null ? null : owner.m_20148_();
        GunnerSettings.Setting setting = GunnerSettings.get(ownerId);
        // Clamped against this round's own list rather than trusted: the index belongs to whichever
        // slot the gunner was operating, and a slip of it must not read off the end of an array.
        ywzjarmor$load = Math.max(0, Math.min(setting.variant, ammo.variantCount() - 1));

        if (!ammo.multiPurpose()) {
            // No fuze to take and no explosion to re-point. Logged only for a round that has loads,
            // which is the slow-firing gun whose choice could be wrong - and where "fired with
            // M829" instead of "M829A3" is the one line that says the load never arrived.
            //
            // **The load's own numbers are printed, not just its name.** The name alone says a
            // choice was made; the penetration says what the choice was worth, and it is the only
            // place that is visible without a hit to read. A round fired at the ground resolves
            // nothing - the addon governs vehicles, and there is no table for dirt - so a gunner
            // testing a new load against terrain would otherwise see two identical shots and no
            // evidence either way.
            if (ammo.selectable()) {
                ArmorConfig.Ammo load = ammo.variant(ywzjarmor$load);
                VehicleArmorMod.LOGGER.info("[Vehicle Armor] {} fired with {} - {}mm, {}{}",
                        self.getWeaponId(), loadName(ammo, self.getWeaponId().toString()),
                        load.penetration,
                        load.profile == null ? "kinetic" : load.profile,
                        GunnerSettings.hasEntry(ownerId) ? ""
                                : " - nothing was ever selected for " + ownerId);
            }
            return;
        }

        ywzjarmor$fuze = setting.fuze;
        ywzjarmor$delayTicks = setting.delayTicks;
        // Logged because "the fuze was IMPACT" and "the fuze key never reached the server" are the
        // same line in every other log, and the difference is the whole diagnosis.
        VehicleArmorMod.LOGGER.info("[Vehicle Armor] {} fired with the {} fuze, {} loaded{}{}",
                self.getWeaponId(), ywzjarmor$fuze, loadName(ammo, self.getWeaponId().toString()),
                ammo.selectable() ? " at " + ammo.variant(ywzjarmor$load).penetration + "mm" : "",
                GunnerSettings.hasEntry(ownerId)
                        ? " (delay " + ywzjarmor$delayTicks + " ticks)"
                        : " - nothing was ever selected for " + ownerId);

        Explosion shared = explosion;
        Explosion own = new Explosion();
        if (shared != null) {
            own.explode = shared.explode;
            own.damage = shared.damage;
            own.radius = shared.radius;
            own.proximityFuze = shared.proximityFuze;
            own.proximityRadius = shared.proximityRadius;
            own.destroyBlock = shared.destroyBlock;
        }

        if (ywzjarmor$fuze == Fuze.PROXIMITY) {
            // The base's own fuze already sweeps for entities, so switching it on with the round's
            // radius is the whole of what proximity needs.
            own.proximityFuze = true;
            own.proximityRadius = ammo.proximityRadius();
        } else if (ywzjarmor$fuze == Fuze.DELAY) {
            own.proximityFuze = false;
            // The base counts 'life' down every tick and removes the shell below zero, so a delay
            // longer than the shell was meant to fly would expire before the fuze ever fired.
            life = Math.max(life, ywzjarmor$delayTicks + 5);
        }
        explosion = own;
    }

    /**
     * Replaces the block clip in {@code tickHit}, so a delay-fuzed shell breaks through one layer of
     * soft cover instead of going off against it.
     *
     * <p>The block is destroyed and the clip's result is reported as a miss, which is what leaves
     * the shell in the air: the base mod's whole block branch - the particles, the explosion and the
     * shell's own removal - hangs off that one result. The miss also feeds the entity branch's
     * comparison, so an entity standing behind the cover is still hit normally.
     */
    @Redirect(method = "tickHit",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;m_45547_(Lnet/minecraft/world/level/ClipContext;)"
                            + "Lnet/minecraft/world/phys/BlockHitResult;"))
    private BlockHitResult ywzjarmor$clipThroughCover(Level level, ClipContext context) {
        BlockHitResult hit = level.m_45547_(context);
        if (hit.m_6662_() == HitResult.Type.MISS || ywzjarmor$fuze != Fuze.DELAY) {
            return hit;
        }
        BlockPos pos = hit.m_82425_();
        BlockState state = level.m_8055_(pos);
        if (ywzjarmor$passed || !ywzjarmor$softCover(state)) {
            // One layer only: a second wall stops it, and so does anything a shell should not be
            // able to bury itself in.
            return hit;
        }

        // Broken outright rather than quietly flown through. Removing it also means the shell
        // cannot be caught by the far face of the very block it just went into - there is nothing
        // left there to report - which is why no note of the block's position is kept any more.
        // No drops: this is a shell punching a hole, not a player mining, and a burst of item
        // entities for every layer of dirt shot at would litter the world for nothing. The
        // explosion that follows still drops what it breaks.
        level.m_46961_(pos, false);

        ywzjarmor$passed = true;
        ywzjarmor$armed = true;
        ywzjarmor$countdown = ywzjarmor$delayTicks;
        VehicleArmorMod.LOGGER.info("[Vehicle Armor] delay fuze broke through {} at {}; {} ticks to burst",
                state.m_60734_(), pos, ywzjarmor$countdown);
        Vec3 velocity = ((Entity) (Object) this).m_20184_();
        double speed = velocity.m_82553_();
        if (speed > 1.0E-7) {
            ywzjarmor$dirX = velocity.f_82479_ / speed;
            ywzjarmor$dirY = velocity.f_82480_ / speed;
            ywzjarmor$dirZ = velocity.f_82481_ / speed;
            ywzjarmor$holdCourse();
        }
        return ywzjarmor$miss(hit);
    }

    @Unique
    private void ywzjarmor$holdCourse() {
        ((Entity) (Object) this).m_20256_(new Vec3(ywzjarmor$dirX * CRAWL_SPEED,
                ywzjarmor$dirY * CRAWL_SPEED, ywzjarmor$dirZ * CRAWL_SPEED));
    }

    /**
     * Sets the shell off where it is, once the delay has run out.
     *
     * <p>Built exactly as the base builds the one on impact - the owner as the source, the shell
     * itself as the direct entity - so that the armour system recognises it as this round's
     * explosion and measures it against the proximity rather than the impact damage model.
     */
    @Unique
    private void ywzjarmor$detonate() {
        if (ywzjarmor$detonated) {
            return;
        }
        ywzjarmor$detonated = true;
        AmmoEntity self = (AmmoEntity) (Object) this;
        Explosion data = explosion;
        if (data != null && data.explode) {
            new VehicleExplosion(self.m_9236_(), self.m_19749_(), self, self.m_20182_(),
                    data.radius, data.damage, data.destroyBlock).explode();
        }
        discard = true;
    }

    /**
     * @return the designation of the load at the index this shell adopted, for the log line - the
     *         one place a wrong load is visible as a name rather than as a number on a hit.
     *
     * <p>The clamp is repeated here rather than trusted from {@code ywzjarmor$load}, so this can be
     * called from either branch of {@code ywzjarmor$adopt} without the two having to agree on
     * whether the index was already bounded.
     */
    @Unique
    private String loadName(ArmorConfig.Ammo ammo, String fallback) {
        java.util.List<String> names = ammo.variantNames(ammo.name == null ? fallback : ammo.name);
        int index = Math.max(0, Math.min(ywzjarmor$load, names.size() - 1));
        return names.get(index);
    }

    /** @return the hit restated as a miss in the same place, so the shell carries on through it. */
    @Unique
    private static BlockHitResult ywzjarmor$miss(BlockHitResult hit) {
        return BlockHitResult.m_82426_(hit.m_82450_(), hit.m_82434_(), hit.m_82425_());
    }

    /**
     * What counts as cover a delay-fuzed round will burrow through: soft ground, and things made of
     * wood. Stone, metal and everything else stops it.
     *
     * <p>Written out tag by tag rather than checked against a list of blocks, so it follows the same
     * definitions the game uses for what a shovel or an axe is meant for, and so a modded block
     * tagged as dirt or as planks is covered without being named. {@code mineable/shovel} is the one
     * that carries gravel, which {@code sand} does not.
     */
    @Unique
    private static boolean ywzjarmor$softCover(BlockState state) {
        return state.m_204336_(BlockTags.f_144274_)      // dirt
                || state.m_204336_(BlockTags.f_13029_)   // sand
                || state.m_204336_(BlockTags.f_144283_)  // mineable/shovel - includes gravel
                || state.m_204336_(BlockTags.f_13106_)   // logs
                || state.m_204336_(BlockTags.f_13090_)   // planks
                || state.m_204336_(BlockTags.f_13035_)   // leaves
                || state.m_204336_(BlockTags.f_144280_); // mineable/axe
    }
}
