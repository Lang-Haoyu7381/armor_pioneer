package com.ywzjvehicle.armor;

import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Vector3f;
import org.ywzj.vehicle.all.AllSounds;
import org.ywzj.vehicle.api.event.HitVehicleEvent;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.weapon.AmmoEntity;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.structure.OBB;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Armour equivalence and residual-penetration damage.
 *
 * <p>Flow for a projectile that carries a configured ammunition type:
 * <ol>
 *   <li>Re-trace the shot against the vehicle the same way the base mod does.</li>
 *   <li>Work out whether the round struck the turret or the hull, and which of the
 *       six faces it entered through.</li>
 *   <li>Subtract the matching plate thickness from the round's penetration. A
 *       non-positive result means the round failed to penetrate and nothing is damaged.</li>
 *   <li>Otherwise deal {@code residual * damage_multiplier} to both the struck part and
 *       the vehicle, exactly like the vanilla damage system would have done.</li>
 * </ol>
 *
 * <p>Hits that are not covered by the config - other vehicles, other ammunition, or
 * explosions and collisions - return {@code false} and fall through to the original
 * behaviour untouched.
 */
public final class ArmorSystem {

    /**
     * How far, in blocks, a hit may sit from the turret's front plate and still count as
     * being on it. The ray is clipped exactly onto the surface, so this only absorbs
     * floating point noise and keeps hits on the protruding barrel out.
     */
    private static final double FRONT_FACE_TOLERANCE = 0.25D;

    /** Message id the base mod gives the damage source of one of its {@code VehicleExplosion}s. */
    private static final String EXPLOSION_MESSAGE_ID = "ywzj_vehicle.explosion";

    /**
     * The two damage types TACZ's bullets carry, as plain {@code ResourceLocation}s.
     *
     * <p>Built here rather than read out of TACZ's own {@code ModDamageTypes}, and that is the whole
     * reason a TACZ bullet can be recognised without naming a TACZ class: a vanilla damage type's
     * holder answers "are you this id" without the mod that registered it being present, and on a
     * machine without TACZ the answer is simply no. The only thing this addon needs TACZ for is
     * turning a bullet into a cartridge id, and that lives in {@link TaczBridge}.
     *
     * <p>Both types share one {@code message_id} ("tacz.bullet"), so the message id cannot tell them
     * apart and the holder is the only honest way to. TACZ also has a {@code _void} pair, for entity
     * types it tags to take damage regardless of armour; those are deliberately <b>not</b> listed,
     * so a void hit falls through to TACZ untouched - whatever that tag is for, this addon has no
     * business overriding it.
     */
    private static final ResourceLocation TACZ_BULLET = new ResourceLocation("tacz", "bullet");
    private static final ResourceLocation TACZ_BULLET_IGNORE_ARMOR =
            new ResourceLocation("tacz", "bullet_ignore_armor");

    /** @return whether this is one of the two halves of a TACZ bullet hit. */
    private static boolean isTaczHit(DamageSource source) {
        Holder<DamageType> type = source.m_269150_();
        return type.m_203373_(TACZ_BULLET) || type.m_203373_(TACZ_BULLET_IGNORE_ARMOR);
    }

    /**
     * @return whether this is the armour-ignoring half of a TACZ hit - the second of the two hurt
     *         calls {@code EntityKineticBullet.tacAttackEntity} makes per bullet.
     */
    private static boolean isTaczArmourPiercingHalf(DamageSource source) {
        return source.m_269150_().m_203373_(TACZ_BULLET_IGNORE_ARMOR);
    }

    private ArmorSystem() {
    }

    /**
     * @return {@code true} if this hit was handled here and the vanilla damage system
     *         must be skipped, {@code false} to leave the hit alone.
     */
    public static boolean tryHandle(DamageSource source, float amount, AbstractVehicle vehicle) {
        if (source == null || vehicle == null) {
            return false;
        }

        // A round with a warhead also detonates, and the base mod delivers that blast as a hit of
        // its own - one whose direct entity is still the projectile that caused it. Resolving it
        // here as well would push the same plate through the armour twice, so it is never treated
        // as a penetration; whether it lands as damage at all is the round's own choice.
        if (EXPLOSION_MESSAGE_ID.equals(source.m_19385_())) {
            if (!(source.m_7640_() instanceof AmmoEntity blast)) {
                return false;
            }
            ArmorConfig.Ammo warhead = modelFor(blast);
            // Only for vehicles this addon actually governs: elsewhere the blast is untouched.
            if (warhead == null || !warhead.replacesBaseExplosion() || armorFor(vehicle) == null) {
                return false;
            }
            VehicleArmorMod.LOGGER.info(
                    "[Vehicle Armor] {} blast of {} damage dropped by {}; this round carries its own",
                    vehicle.getVehicleId(), amount, blast.getWeaponId());
            return true;
        }

        // TACZ's bullets, when the gun mod is here and this cartridge has been rated.
        //
        // Recognised by damage type rather than by entity class, and that is deliberate: `tacz:bullet`
        // is an ordinary DamageSource type, so identifying a TACZ hit costs nothing and names no
        // TACZ class - which is what keeps every com.tacz reference in TaczBridge, where the mod
        // being absent cannot hurt. Reading the holder of a vanilla damage type is not going to
        // throw because some other mod is missing.
        if (isTaczHit(source)) {
            Entity bullet = source.m_7640_();
            ArmorConfig.Ammo taczRound = ArmorConfig.taczRoundFor(TaczBridge.ammoIdOf(bullet));
            // Both halves of the hit have to answer the same way, so a round this addon has not
            // rated leaves TACZ's own damage completely alone - including the armour-ignoring half,
            // which would otherwise be the only part of it that landed.
            if (bullet == null || taczRound == null || armorFor(vehicle) == null) {
                return false;
            }
            // TACZ delivers one bullet as two hurt calls, splitting the damage into a normal part
            // and an armour-ignoring part by the round's own `armor_ignore` fraction. Both are the
            // same bullet arriving at the same plate, so it is measured once - on the first of the
            // pair - and the second is swallowed unresolved. Resolving both would push the same
            // plate through twice and double the damage, which is the very shape of bug the
            // explosion branch above exists to prevent for warheads.
            if (isTaczArmourPiercingHalf(source)) {
                return true;
            }
            // No fuze and no calibre: a cartridge has neither. The fuze label is what the log line
            // prints, and "none" is the truthful answer rather than borrowing a tank shell's.
            //
            // The shooter comes off the damage source rather than off the bullet: getOwner() is
            // Projectile's, and `bullet` here is a plain Entity, which does not have it. TACZ builds
            // the source with the bullet as the direct entity and its owner as the causing one, so
            // this is the same person either way - and it is the one the hit marker goes to.
            return resolveHit(vehicle, source, taczRound,
                    bullet.m_20182_(), bullet.m_20184_(), "none", source.m_7639_(), 0.0F);
        }

        // Only projectiles that carry a known ammunition id take part.
        if (!(source.m_7640_() instanceof AmmoEntity ammo)) {
            return false;
        }
        ResourceLocation weaponId = ammo.getWeaponId();
        ArmorConfig.Ammo round = ArmorConfig.ammoFor(weaponId);
        if (round == null) {
            return false;
        }
        // A multi-purpose round carries two sets of numbers and the fuze decides which applies.
        // Everything else resolves to itself, so this is a no-op for every other round.
        Fuze fuze = fuzeOf(ammo);
        ArmorConfig.Ammo model = round.damageModel(fuze);
        // Then the mark the gun was loaded with, which is a second choice made the same way and
        // resolved after the fuze: the load list belongs to the round the slot fires, so a fuze
        // that swaps the whole damage model takes the load choice with it. No round has both yet.
        model = model.variant(loadOf(ammo));

        if (armorFor(vehicle) == null) {
            return false;
        }
        return resolveHit(vehicle, source, model, ammo.m_20182_(), ammo.m_20184_(),
                fuze.name(), ammo.m_19749_(), ammo.getCaliber());
    }

    /**
     * The whole armour decision, for any projectile this addon has rated - one tank shell or one
     * rifle bullet.
     *
     * <p>Taken out of {@link #tryHandle} when TACZ arrived, because the two paths differ in exactly
     * two things and both are arguments: where the round came from, and how it says what it can
     * penetrate. Everything after that - the re-traced ray, the part, the face, the reactive panels,
     * the residual, the damage - is one piece of arithmetic that both must get the same answer from.
     * Written twice it would drift, and the drift would look like "bullets behave oddly near the
     * turret ring" rather than like two copies of a formula disagreeing.
     *
     * @param model   the round's numbers. For a tank shell this is the selected damage model - the
     *                fuze's, then the load's; for a TACZ cartridge it is the cartridge.
     * @param shooter who to report the hit to, for the shooter's own hit marker
     * @param caliber the round's bore, or zero when there is nothing sensible to give
     * @return whether the hit was handled here, in which case the caller must swallow its own damage
     */
    private static boolean resolveHit(AbstractVehicle vehicle, DamageSource source,
                                      ArmorConfig.Ammo model, Vec3 start, Vec3 motion,
                                      String fuzeLabel, Entity shooter, float caliber) {
        ArmorConfig.VehicleArmor armor = armorFor(vehicle);
        if (armor == null) {
            return false;
        }
        float penetration = model.penetration;

        if (motion.m_82553_() < 1.0E-7) {
            return false;
        }
        Vec3 rayEnd = start.m_82549_(motion);

        Vec3 hitPos = VectorUtil.closestHitObbPosition(vehicle, start, rayEnd);
        if (hitPos == null) {
            return false;
        }

        Vec3 direction = motion.m_82541_();
        Vec3 probeEnd = hitPos.m_82549_(direction.m_82490_(0.1));
        PartUnit<?> partUnit = VectorUtil.hitPartUnit(vehicle, start, probeEnd, false);

        VectorUtil.HitOBB hitObb = VectorUtil.hitOBB(vehicle.getOBBs(), start, rayEnd);
        if (hitObb == null) {
            return false;
        }
        OBB obb = hitObb.obb();

        // The zone decision, tandem warheads and reactive armour included - see resolve(). A tandem
        // round that got through a light panel arrives scaled down; one that met a heavy panel is
        // stopped by it and uses it up.
        java.util.Set<String> spent = spentPanels(vehicle);
        Resolution resolution = resolve(armor, model.profile,
                hitFor(armor, vehicle, obb, hitPos, partUnit, direction), model.tandem(), spent);
        Zone zone = resolution.zone();
        // Named for the log even when the panel is the thing that stopped the round: a tandem round
        // held by reactive armour and an ordinary round held by it look identical otherwise.
        String panelName = resolution.panelName();
        if (resolution.panelUsed() != null && spent.add(resolution.panelUsed())) {
            VehicleArmorMod.LOGGER.info("[Vehicle Armor] {}: {} is spent",
                    vehicle.getVehicleId(), resolution.panelUsed());
        }

        float thickness = zone.thickness;
        float multiplier = zone.multiplier;

        // A round that carries its own multiplier ignores the per-plate ones. Machine guns do
        // this: they are not powerful enough for the plate they hit to matter.
        if (model.multiplier != null) {
            multiplier = model.multiplier;
        }

        if (ArmorConfig.angleNormalization()) {
            thickness = normalizeAgainstImpact(thickness, obb, zone.face, direction);
        }

        // What the screen took off the round, before the plate behind it is compared. Zero for
        // ordinary armour, so this is the same sum it always was for every zone but the screens.
        // `scale` is below one only for a tandem round that came through a light reactive panel,
        // which is the one thing that takes a share of a round rather than a number of millimetres.
        float effective = penetration * resolution.scale() - zone.loss;
        float residual = effective - thickness;
        // Equal counts as through: a round rated for exactly the plate it meets gets in, with
        // nothing left over. Without this a shell whose penetration and target thickness are the
        // same number - which is how the roofs are configured - could never penetrate at all.
        boolean penetrated = residual >= 0.0F;
        float damage = 0.0F;
        if (penetrated) {
            // Which of the three ways a round states its damage is the round's own business, and
            // decided in one place so that the table tool cannot be printing a different one.
            damage = model.penetrationDamage(residual, multiplier, thickness);
        }

        feedback(vehicle, shooter, caliber, hitPos, motion, damage, penetrated);
        if (penetrated) {
            applyDamage(source, vehicle, partUnit, damage);
            // What the round did to the people behind the plate. The compartment is the one the
            // struck part belongs to, not the one the shooter aimed at: a round through the
            // commander's mount injures the commander even though that part is not in the derived
            // turret list. CrewState ignores this on the client and when the vehicle is unmanned.
            CrewState.onPenetration(vehicle, armor,
                    CrewRoles.inTurretCompartment(armor, partUnit), partUnit,
                    residual, damage, caliber);
        }
        // A shaped-charge jet scatters spall whether or not it got through; a bursting charge is
        // already accounted for by its penetration damage, so it only throws a blast when the
        // plate held.
        if (model.hasSpall() && (penetrated ? model.spallOnPenetration() : true)) {
            applySpall(vehicle, model, thickness);
        }

        // INFO rather than DEBUG so it is visible in the console without touching the log
        // config: only ammunition listed in the config reaches this line, and that is the
        // tank's slow-firing main gun, so it cannot flood the log.
        // The loss appears in the line only when there is one: it is the number that explains why a
        // round which should have gone through did not, and a row of zeroes on every other hit
        // would bury it.
        if (panelName != null) {
            // A tandem hit names both plates, in the order it met them: without the first, the line
            // describes a round measured against armour it never reached - or, when the panel held,
            // a round with no explanation for why.
            VehicleArmorMod.LOGGER.info(
                    "[Vehicle Armor] {} hit on {}: {} fuze, tandem met the {} ({}mm of penetration"
                            + " left), plate {}mm, residual {}mm, damage {}",
                    vehicle.getVehicleId(), zone.name, fuzeLabel, panelName,
                    Math.round(penetration * resolution.scale()), thickness, residual, damage);
        } else if (zone.loss > 0.0F) {
            VehicleArmorMod.LOGGER.info(
                    "[Vehicle Armor] {} hit on {}: {} fuze, plate {}mm, screen took {}mm off the round,"
                            + " penetration {}mm, residual {}mm, damage {}",
                    vehicle.getVehicleId(), zone.name, fuzeLabel, thickness, zone.loss, penetration,
                    residual, damage);
        } else {
            VehicleArmorMod.LOGGER.info(
                    "[Vehicle Armor] {} hit on {}: {} fuze, plate {}mm, penetration {}mm, residual {}mm, damage {}",
                    vehicle.getVehicleId(), zone.name, fuzeLabel, thickness, penetration, residual, damage);
        }
        return true;
    }

    /**
     * The fuze a shell is carrying, as far as the damage system is concerned.
     *
     * <p>Read off the projectile rather than out of a table indexed by shot: two gunners firing at
     * the same moment would collide in any such table, and the shell is the only thing that
     * genuinely knows what it was armed with. Anything that is not a shell the mixin touched does
     * not implement {@link ShellState} and reports {@link Fuze#IMPACT} by falling through to the
     * default.
     */
    private static Fuze fuzeOf(AmmoEntity shell) {
        return shell instanceof ShellState holder ? holder.ywzjarmor$fuze() : Fuze.IMPACT;
    }

    /**
     * Which mark of its round a shell was fired with.
     *
     * <p>Read off the shell for the same reason the fuze is: the gunner can change the load between
     * the shot leaving and the shot landing, and it is the load it was fired with that is in the
     * air. Anything the mixin did not touch reports zero, which is the round as the pack defines it.
     */
    private static int loadOf(AmmoEntity shell) {
        return shell instanceof ShellState holder ? holder.ywzjarmor$load() : 0;
    }

    /**
     * @return the damage model a shell's blast should be measured against, or {@code null} when its
     *         weapon is not configured at all.
     *
     * <p>Resolved exactly the way the penetration path resolves it - fuze first, then the load -
     * because a load can differ in everything a round can, its blast included. Skipping the second
     * step here would leave the blast measured against the round the pack names the slot for while
     * the shot itself was measured against the load: the same shell, two different answers.
     */
    private static ArmorConfig.Ammo modelFor(AmmoEntity shell) {
        ArmorConfig.Ammo round = ArmorConfig.ammoFor(shell.getWeaponId());
        return round == null ? null : round.damageModel(fuzeOf(shell)).variant(loadOf(shell));
    }

    private static boolean isTurretPart(ArmorConfig.VehicleArmor armor, PartUnit<?> partUnit) {
        if (partUnit == null) {
            return false;
        }
        String id = partUnit.getId();
        if (id == null) {
            return false;
        }
        for (String turretId : armor.turret_part_ids) {
            if (turretId.equals(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the round went through the gun mount - the circular opening in the turret's
     * front plate that the barrel passes through.
     *
     * <p>The turret's largest cube is its body box, so the weak spot is modelled as a circle
     * lying on that box's front face.
     */
    private static boolean isGunMount(PartUnit<?> partUnit, Vec3 hitPos, ArmorConfig.WeakSpot spot) {
        if (partUnit == null) {
            return false;
        }
        VehicleCubeOBB box = partUnit.getLargestCube();
        return box != null && hitsFrontDisc(box.obb(), hitPos, spot);
    }

    /**
     * The weak spot's geometry, kept free of vehicle plumbing so it can be checked on its own:
     * the hit must land on the front face of the given box and within {@code radius} of the
     * configured centre, measured across that face.
     *
     * <p>The face check matters because the barrel is part of the turret too and sticks out
     * well past the front plate; a hit on the barrel must not be treated as armour.
     */
    static boolean hitsFrontDisc(OBB turretBox, Vec3 hitPos, ArmorConfig.WeakSpot spot) {
        if (spot.radius <= 0.0F) {
            return false;
        }
        Vector3f[] axes = turretBox.getAxes();
        Vector3f centre = turretBox.center();
        double dx = hitPos.f_82479_ - centre.x;
        double dy = hitPos.f_82480_ - centre.y;
        double dz = hitPos.f_82481_ - centre.z;

        double localX = dx * axes[0].x + dy * axes[0].y + dz * axes[0].z - spot.offset_x;
        double localY = dx * axes[1].x + dy * axes[1].y + dz * axes[1].z - spot.offset_y;
        double localZ = dx * axes[2].x + dy * axes[2].y + dz * axes[2].z;

        if (Math.abs(localZ - turretBox.extents().z) > FRONT_FACE_TOLERANCE) {
            return false;
        }
        return localX * localX + localY * localY <= (double) spot.radius * spot.radius;
    }

    /**
     * @return the armour profile configured for this vehicle, or {@code null} when it has none.
     *         The stable vehicle id is checked first; the display id is a cosmetic skin and is
     *         only consulted so a re-skinned vehicle can still carry its own profile.
     */
    public static ArmorConfig.VehicleArmor armorFor(AbstractVehicle vehicle) {
        ArmorConfig.VehicleArmor armor = ArmorConfig.armorFor(vehicle.getVehicleId());
        return armor != null ? armor : ArmorConfig.armorFor(vehicle.getDisplayId());
    }

    /**
     * The suspension this vehicle's crew can work, or {@code null} when it has none.
     *
     * <p>Resolved against the same two ids its armour is, and in the same order, because both come
     * out of the same config and a re-skinned variant can be given either independently. Kept apart
     * from {@link #armorFor} rather than looked up through it: the suspension is no longer a block
     * on a vehicle's armour, and the six vehicles that have one - the BMP-3, the BRM-3K, the
     * Khrizantema, the Challenger 2, the Puma and the Ajax - have no armour entry at all.
     */
    public static Suspension.Settings suspensionFor(AbstractVehicle vehicle) {
        Suspension.Settings settings = ArmorConfig.suspensionFor(vehicle.getVehicleId());
        return settings != null ? settings : ArmorConfig.suspensionFor(vehicle.getDisplayId());
    }

    /** The armour an impact meets: which zone it counts as, how thick, and how lethal. */
    public static final class Zone {
        public final String name;
        public final float thickness;
        public final float multiplier;
        /**
         * What the zone takes off the round on the way through, in millimetres of penetration,
         * before the plate behind it is compared. Zero for ordinary armour, which is all of it
         * except the screens configured to do this.
         */
        public final float loss;
        /** Face index from {@link OBB#embeddingFace}: +-1 side, +-2 roof/floor, +-3 front/rear. */
        public final int face;
        /**
         * The zone is explosive reactive armour, and which kind - or {@code null} for everything
         * else, which is nearly all of it.
         *
         * <p>It means nothing to an ordinary round, which is measured against the panel like any
         * other plate. It is what tells a tandem warhead's precursor charge how it fared, and only a
         * reactive zone has anything behind it to go on to. The running gear is not reactive in this
         * sense - a road wheel is not covering an armour plate, it is the thing the round met.
         */
        public final EraKind era;

        Zone(String name, float thickness, float multiplier, float loss, int face, EraKind era) {
            this.name = name;
            this.thickness = thickness;
            this.multiplier = multiplier;
            this.loss = loss;
            this.face = face;
            this.era = era;
        }
    }

    /**
     * One impact, as far as choosing a zone is concerned: where it landed and how it arrived.
     *
     * <p>A record rather than more parameters, because each new zone seems to want one: the
     * machine-gun port needed the hull's half-extents to turn a fraction back into a circle, and the
     * flank screens need the angle the round came in at. A growing positional list at a call site is
     * how the wrong two arguments get swapped, and both of those are {@code double[]}.
     *
     * <p>All the geometry is worked out in {@link #plateFor}; nothing here is decided, only stated.
     *
     * @param turret          whether the struck part belongs to the turret
     * @param face            face index from {@link OBB#embeddingFace}
     * @param hullLocal       normalised hull-local hit position, or {@code null} if unavailable
     * @param hullHalf        the hull box's half-extents in blocks, or {@code null} if unavailable
     * @param gunMount        whether the hit is inside the gun mount circle
     * @param angleFromNormal how obliquely the round arrived, in degrees away from the face's own
     *                        direction - {@code 0} square on, {@code 90} along the surface
     * @param partId          the part unit the hit landed on, or {@code null} when it landed on
     *                        hull structure that belongs to no part
     * @param throughLayers   whether the layers bolted to the outside have already been dealt with,
     *                        so the hit is being re-resolved against the armour behind them. Only a
     *                        tandem round ever asks this, and only on its second pass
     */
    public record Hit(boolean turret, int face, double[] hullLocal, double[] hullHalf,
                      boolean gunMount, double angleFromNormal, String partId, boolean throughLayers) {

        /**
         * The same impact, asked what lies behind the layers bolted to the outside.
         *
         * <p>Not a different hit: every fact about where it landed and how it arrived is the same,
         * and only the layers it has already been through are dropped. Keeping it as a copy of this
         * record rather than a second call to {@code hitFor} is what makes that structural.
         */
        public Hit pastLayers() {
            return new Hit(turret, face, hullLocal, hullHalf, gunMount, angleFromNormal, partId, true);
        }
    }

    /**
     * @return the part unit a given OBB belongs to, or {@code null} when it is hull structure.
     *         Compared by identity: the part unit hands out the very same OBB instances that
     *         {@code getOBBs()} collects.
     */
    public static PartUnit<?> partUnitOf(AbstractVehicle vehicle, OBB obb) {
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            for (OBB candidate : partUnit.getOBBs()) {
                if (candidate == obb) {
                    return partUnit;
                }
            }
        }
        return null;
    }

    /**
     * Works out which plate an impact at {@code hitPos} meets. Everything here is about turning a
     * world-space hit into the handful of facts {@link #resolveZone} needs; the decision itself
     * lives there, where it can be exercised without a vehicle.
     *
     * @param profile name of the ammunition's armour profile, looked up in the vehicle's
     *                {@code overrides}. {@code null} - or a name the vehicle does not define -
     *                resolves to the vehicle's own values.
     */
    public static Zone plateFor(ArmorConfig.VehicleArmor armor, String profile, AbstractVehicle vehicle,
                                OBB obb, Vec3 hitPos, PartUnit<?> partUnit, Vec3 direction) {
        return resolveZone(armor, profile, hitFor(armor, vehicle, obb, hitPos, partUnit, direction));
    }

    /**
     * The facts a zone decision is made from, gathered off the vehicle and the impact.
     *
     * <p>Split out of {@link #plateFor} so that a caller which has to ask the question twice - the
     * tandem resolution in {@link #resolve} - builds the impact once and asks about it twice, rather
     * than deriving the geometry again and risking the two passes describing different hits.
     */
    public static Hit hitFor(ArmorConfig.VehicleArmor armor, AbstractVehicle vehicle, OBB obb,
                             Vec3 hitPos, PartUnit<?> partUnit, Vec3 direction) {
        boolean turret = isTurretPart(armor, partUnit);
        int face = obb.embeddingFace(hitPos);
        ArmorConfig.WeakSpot weakSpot = armor.turret_weak_spot;
        boolean gunMount = turret && face == 3 && weakSpot != null && weakSpot.enabled
                && isGunMount(partUnit, hitPos, weakSpot);
        // Every hull-side decision - the glacis split, the track strips, the reactive panels, the
        // machine-gun port - is anchored to the hull box rather than to the part that was struck.
        double[] hullLocal = turret ? null : hullLocalPosition(vehicle, hitPos);
        double[] hullHalf = turret ? null : hullHalfExtents(vehicle);
        return new Hit(turret, face, hullLocal, hullHalf, gunMount,
                angleFromNormal(obb, face, direction),
                partUnit == null ? null : partUnit.getId(), false);
    }

    /**
     * A hit's final answer: the plate the round is measured against, and what it met on the way.
     *
     * @param zone      the plate the round is measured against
     * @param panelName the reactive panel this hit dealt with, or {@code null} for a hit that met no
     *                  panel at all. Named for the log even when the round was stopped by it, since
     *                  a stopped tandem round is otherwise indistinguishable from an ordinary one
     * @param scale     the fraction of the round's penetration that survived that panel - {@code 1}
     *                  for everything except a tandem round through a light panel
     * @param panelUsed the one-shot panel this hit used up, or {@code null}. The caller records it;
     *                  a panel that has been used is no longer in the way of anything
     */
    public record Resolution(Zone zone, String panelName, float scale, String panelUsed) {
    }

    /**
     * Resolves a hit, taking a tandem warhead's precursor charge into account.
     *
     * <p>An ordinary round meets the first panel there is and is measured against it, which is the
     * whole answer. A tandem round is not, and what happens instead depends on which kind of
     * reactive armour it is looking at:
     *
     * <ul>
     *   <li><b>light</b> - the precursor is enough. The main charge goes through the panel and is
     *       measured against the armour behind it, at {@link EraKind#TANDEM_FRACTION_THROUGH_LIGHT}
     *       of its penetration. The panel is not consumed: what it is worth to a tandem round is the
     *       third it takes, every time.</li>
     *   <li><b>heavy</b> - the precursor is not enough. The panel stops the round exactly as it would
     *       stop an ordinary shaped charge, and is used up doing so. The same hit against the same
     *       panel afterwards finds it gone and meets the armour behind.</li>
     * </ul>
     *
     * <p>This is the one place where {@code loss} and extra thickness come apart, and only for the
     * light panel: everywhere else a single zone decides the hit, which makes the two the same
     * arithmetic.
     *
     * <p>Lives here rather than inside {@code tryHandle} so the rule can be exercised without a
     * vehicle, a projectile or a level - the tool that checks it calls this same method.
     *
     * @param spent the panels already used up on this vehicle, by the key {@link #panelKey} builds,
     *              or {@code null} when nothing is spent. Whether a heavy panel is still there is
     *              not something this method can know on its own
     */
    static Resolution resolve(ArmorConfig.VehicleArmor armor, String profile, Hit hit, boolean tandem,
                              java.util.Set<String> spent) {
        Zone zone = resolveZone(armor, profile, hit);
        if (!tandem || zone.era == null) {
            return new Resolution(zone, null, 1.0F, null);
        }
        String panel = panelKey(hit, zone);

        if (zone.era == EraKind.LIGHT) {
            // Through, at two thirds. Nothing else off the round: the third is what this panel costs
            // a tandem warhead, and its thickness is the part of it the precursor already dealt with.
            return new Resolution(resolveZone(armor, profile, hit.pastLayers()), zone.name,
                    EraKind.TANDEM_FRACTION_THROUGH_LIGHT, null);
        }
        // Heavy. If it has already stopped one, it is not there any more and this round is the first
        // thing to reach the armour behind it.
        if (spent != null && spent.contains(panel)) {
            return new Resolution(resolveZone(armor, profile, hit.pastLayers()), zone.name, 1.0F, null);
        }
        // Still in place: this is the one it stops, and it does not survive it.
        return new Resolution(zone, zone.name, 1.0F, panel);
    }

    /**
     * Names a reactive panel for the purpose of remembering that it has been used.
     *
     * <p>A box bolted to the tank is identified by the part it is, which is what the model already
     * says and what survives the turret turning. A panel that is a configured zone rather than a
     * part - the flank screen - has no id to borrow, so it is named after itself; there is one per
     * flank per vehicle, and that is what "used up" means for it.
     */
    private static String panelKey(Hit hit, Zone zone) {
        return hit.partId() != null ? hit.partId() : "zone:" + zone.name;
    }

    /**
     * The reactive panels a vehicle has already used up.
     *
     * <p>The only state this addon keeps, and it is unavoidable: "can take one hit" is a statement
     * about the past, and no amount of geometry answers it. Keyed by the vehicle entity itself, so
     * the panels come and go with the tank - a weak map rather than a registry, because nothing
     * tells this addon when a vehicle is unloaded and a strong reference would keep every tank the
     * player has ever shot at alive for the session.
     *
     * <p>Not persisted, and deliberately: a world reload puts the panels back. Remembering across
     * sessions would mean writing vehicle state to disk, and a blown-off block that comes back on
     * restart is the same thing the base mod already does with the vehicle's own parts.
     */
    private static final java.util.Map<AbstractVehicle, java.util.Set<String>> SPENT_PANELS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** @return the panels this vehicle has used up, created on first use. */
    private static java.util.Set<String> spentPanels(AbstractVehicle vehicle) {
        return SPENT_PANELS.computeIfAbsent(vehicle,
                v -> java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>()));
    }

    /**
     * The zone decision itself, given the vehicle-dependent facts already worked out.
     *
     * <p>Kept separate from {@link #plateFor} so it can be called directly: a test that rebuilds
     * this logic instead of calling it cannot notice when the two disagree, which is exactly how
     * the turret/hull mix-up below slipped through.
     *
     * @param hit everything about the impact that a zone is allowed to care about
     */
    static Zone resolveZone(ArmorConfig.VehicleArmor armor, String profile, Hit hit) {
        boolean turret = hit.turret();
        int face = hit.face();
        double[] hullLocal = hit.hullLocal();
        double[] hullHalf = hit.hullHalf();
        boolean gunMount = hit.gunMount();
        boolean throughLayers = hit.throughLayers();

        ArmorConfig.ArmorOverride override = null;
        if (profile != null && armor.overrides != null) {
            override = armor.overrides.get(profile);
        }

        // The override's turret set only applies to turret hits and its hull set only to hull hits;
        // neither may stand in for the other, or a hull hit would be measured against turret armour.
        ArmorConfig.ArmorSet overrideSet = override == null ? null : (turret ? override.turret : override.hull);
        ArmorConfig.ArmorSet plates = pick(overrideSet, turret ? armor.turret : armor.hull);
        // Three deep, and each step is a real answer: the ammunition's own table if it named one,
        // then the vehicle's if it states its own, then the global default. A vehicle that says
        // nothing gets exactly what it got before this chain had a middle step.
        ArmorConfig.DamageMultiplier multipliers = pick(
                override == null ? null : override.damage_multiplier,
                pick(armor.damage_multiplier, ArmorConfig.damageMultiplier()));

        ArmorConfig.WeakSpot weakSpot = (turret && face == 3) ? armor.turret_weak_spot : null;
        ArmorConfig.FrontSplit split = (!turret && face == 3) ? armor.hull_front_split : null;
        ArmorConfig.TrackEnds trackEnds = (!turret && Math.abs(face) == 3) ? armor.track_ends : null;
        // Only the hull's flanks carry the reactive panels, and an absent plate means either that
        // the ammunition has no separate value for them or that the flank is uniform armour.
        // Both of this zone's conditions are about the layer itself, so a pass that has already
        // dealt with the layers skips it whole - there is no "the screen, but obliquely" reading of
        // a hit that has been through the screen.
        ArmorConfig.Plate reactive = (!throughLayers && !turret && Math.abs(face) == 1)
                ? pick(override == null ? null : override.hull_side_era, armor.hull_side_era)
                : null;
        // The running gear of a wheeled vehicle, which is the whole lower flank. Measured off the
        // model: on the BTR-3 the hull's own box encloses the wheels, so a ray from the side always
        // lands on the hull and this is the only way the wheels can be told apart from it.
        ArmorConfig.RoadWheels wheels = (!turret && Math.abs(face) == 1) ? armor.road_wheels : null;
        // The machine-gun port, on the hull's front face only - it is a hole in the glacis, and
        // there is no glacis on the other five faces to put a hole in.
        ArmorConfig.WeakSpot hullSpot = (!turret && face == 3) ? armor.hull_weak_spot : null;
        boolean machineGunPort = hullSpot != null && hullSpot.enabled
                && isHullWeakSpot(hullSpot, hullLocal, hullHalf);

        String name = turret ? "turret" : "hull";
        float thickness;
        float multiplier;
        float loss = 0.0F;
        EraKind era = null;

        // The ammunition's own value for this part, then the vehicle's. Ahead of every zone below,
        // because a box bolted to the outside of a tank is the first and only thing a shot at it
        // meets - the plate behind it is what the box is there to keep the round away from, and
        // measuring the round against that plate would be measuring it against something it has not
        // reached. It also keeps the boxes out of the zones below, which are all about the shapes of
        // the hull and turret and know nothing about what has been bolted on.
        ArmorConfig.Plate byPart = throughLayers ? null : partArmor(armor, override, hit.partId());
        if (byPart != null) {
            thickness = byPart.thickness;
            multiplier = byPart.multiplier;
            loss = byPart.penetration_loss;
            name = hit.partId();
            era = byPart.eraKind();
        } else if (gunMount && weakSpot != null) {
            // The gun mount is a gap in the turret front armour, not a plate.
            ArmorConfig.Plate values = pick(override == null ? null : override.turret_weak_spot,
                    new ArmorConfig.Plate(weakSpot.thickness, weakSpot.multiplier));
            thickness = values.thickness;
            multiplier = values.multiplier;
            loss = values.penetration_loss;
            name = "turret gun mount";
        } else if (trackEnds != null && trackEnds.enabled && hullLocal != null
                && isTrackEnd(trackEnds, hullLocal[0], hullLocal[1])) {
            // Checked before the hull front split: the track strips are the outer columns of
            // the very same face and would otherwise be swallowed by the glacis.
            ArmorConfig.Plate values = pick(override == null ? null : override.track_ends,
                    new ArmorConfig.Plate(trackEnds.thickness, trackEnds.multiplier));
            thickness = values.thickness;
            multiplier = values.multiplier;
            loss = values.penetration_loss;
            name = face > 0 ? "track front" : "track rear";
        } else if (wheels != null && wheels.enabled && hullLocal != null
                && isRoadWheel(wheels, hullLocal[1])) {
            // Checked before the armour behind it, for the same reason the track strips are checked
            // before the glacis: the running gear is the outermost thing on the flank, so it is what
            // a shot at that height actually meets.
            ArmorConfig.Plate values = pick(override == null ? null : override.road_wheels,
                    new ArmorConfig.Plate(wheels.thickness, wheels.multiplier));
            thickness = values.thickness;
            multiplier = values.multiplier;
            loss = values.penetration_loss;
            name = "road wheels";
        } else if (reactive != null && hullLocal != null && reactive.thickness > 0.0F
                && isReactivePanel(armor.hull_side_era_front_fraction, hullLocal[2])
                && hit.angleFromNormal() >= armor.hull_side_era_min_angle) {
            // The angle is the last of the conditions, and it reads as "the panels are not there":
            // below it the hit falls through to the flank behind them, which is what a screen full
            // of holes looks like from square on. Zero - the default - lets everything through.
            thickness = reactive.thickness;
            multiplier = reactive.multiplier;
            loss = reactive.penetration_loss;
            name = "hull side (reactive)";
            era = reactive.eraKind();
        } else if (machineGunPort) {
            // Ahead of the split: the port is a hole through the glacis, so a hit inside the circle
            // is a hit through the port whatever the plate around it is made of.
            ArmorConfig.Plate values = pick(override == null ? null : override.hull_weak_spot,
                    new ArmorConfig.Plate(hullSpot.thickness, hullSpot.multiplier));
            thickness = values.thickness;
            multiplier = values.multiplier;
            loss = values.penetration_loss;
            name = "hull machine-gun port";
        } else if (split != null && split.enabled) {
            // The hull's front plate is really a glacis above a lower plate, so the round is
            // matched against whichever of the two the impact height falls into.
            boolean upper = (hullLocal == null ? 0.0 : hullLocal[1]) >= split.split;
            ArmorConfig.Plate values = pick(hullFrontPlateOverride(override, upper),
                    upper ? split.upper : split.lower);
            thickness = values.thickness;
            multiplier = values.multiplier;
            loss = values.penetration_loss;
            name = upper ? "hull upper front" : "hull lower front";
        } else {
            thickness = faceValue(plates.front, plates.side, plates.rear, plates.roof, plates.floor, face);
            multiplier = faceValue(multipliers.front, multipliers.side, multipliers.rear,
                    multipliers.roof, multipliers.floor, face);
        }
        return new Zone(name, thickness, multiplier, loss, face, era);
    }

    /** @return the profile's value for a zone when it has one, otherwise the vehicle's own. */
    private static <T> T pick(T value, T fallback) {
        return value != null ? value : fallback;
    }

    /**
     * @return the armour value for the part the hit landed on, or {@code null} when neither the
     *         round nor the vehicle says anything about it - which is every vehicle whose armour is
     *         all faces and every hit that missed the parts altogether.
     */
    private static ArmorConfig.Plate partArmor(ArmorConfig.VehicleArmor armor,
                                               ArmorConfig.ArmorOverride override, String partId) {
        if (partId == null) {
            return null;
        }
        if (override != null && override.part_armor != null) {
            ArmorConfig.Plate byRound = override.part_armor.get(partId);
            if (byRound != null) {
                return byRound;
            }
        }
        return armor.part_armor == null ? null : armor.part_armor.get(partId);
    }

    /** @return the profile's upper or lower glacis, or {@code null} when it does not override it. */
    private static ArmorConfig.Plate hullFrontPlateOverride(ArmorConfig.ArmorOverride override, boolean upper) {
        if (override == null || override.hull_front_split == null) {
            return null;
        }
        return upper ? override.hull_front_split.upper : override.hull_front_split.lower;
    }

    /**
     * True when a hit on the hull's flank landed level with the explosive-reactive panels, which
     * cover only the forward part of the hull.
     *
     * @param frontFraction how much of the hull's length, from the nose back, they cover
     * @param hullLocalZ    hit position along the hull, {@code +1} at the nose
     */
    static boolean isReactivePanel(float frontFraction, double hullLocalZ) {
        float fraction = Math.max(0.0F, Math.min(1.0F, frontFraction));
        return hullLocalZ >= 1.0 - 2.0 * fraction;
    }

    /**
     * The hit position inside the hull box's own frame, normalised so that {@code +-1} lies on
     * the box surface on each axis. Index 0 is across the hull, 1 is up it, 2 is along it.
     *
     * <p>Measured against the main cube - the largest cube of the {@code main_structure} bone,
     * which is the bare hull and excludes the turret and every other part, so rotating the
     * turret cannot move any of these lines.
     *
     * @return {@code null} when the vehicle has no main cube yet.
     */
    private static double[] hullLocalPosition(AbstractVehicle vehicle, Vec3 hitPos) {
        VehicleCubeOBB mainCube = vehicle.getMainCubeOBB();
        if (mainCube == null) {
            return null;
        }
        OBB hull = mainCube.obb();
        Vector3f[] axes = hull.getAxes();
        Vector3f centre = hull.center();
        Vector3f extents = hull.extents();
        double dx = hitPos.f_82479_ - centre.x;
        double dy = hitPos.f_82480_ - centre.y;
        double dz = hitPos.f_82481_ - centre.z;
        return new double[]{
                axisOffset(dx, dy, dz, axes[0]) / Math.max(1.0E-3F, extents.x),
                axisOffset(dx, dy, dz, axes[1]) / Math.max(1.0E-3F, extents.y),
                axisOffset(dx, dy, dz, axes[2]) / Math.max(1.0E-3F, extents.z)
        };
    }

    /**
     * The hull box's half-extents in blocks, so a hit stated as a fraction of them can be turned
     * back into blocks.
     *
     * <p>Only the machine-gun port needs this. Every other hull zone is a line across the hull - a
     * track strip's inner edge, the glacis split, the height the wheels stop at - and a fraction is
     * the natural way to state a line. A circle is not: stated in fractions it comes out an ellipse,
     * because the hull is three times taller than it is wide, and a machine-gun port is round.
     *
     * @return {@code {x, y, z}} half-extents, or {@code null} when the vehicle has no main cube yet
     */
    private static double[] hullHalfExtents(AbstractVehicle vehicle) {
        VehicleCubeOBB mainCube = vehicle.getMainCubeOBB();
        if (mainCube == null) {
            return null;
        }
        Vector3f extents = mainCube.obb().extents();
        return new double[]{extents.x, extents.y, extents.z};
    }

    /**
     * True when a hull hit landed inside a weak spot's circle on the front plate.
     *
     * <p>Measured in blocks across the plate's face rather than in the normalised frame the other
     * hull zones use, and from the middle of that face, so {@code offset_x} and {@code radius} mean
     * what they look like they mean however the hull is proportioned.
     *
     * @param hullLocal normalised hit position, index 0 across and 1 up the hull
     * @param hullHalf  the hull box's half-extents in blocks, index 0 across and 1 up
     */
    static boolean isHullWeakSpot(ArmorConfig.WeakSpot spot, double[] hullLocal, double[] hullHalf) {
        if (spot.radius <= 0.0F || hullLocal == null || hullHalf == null) {
            return false;
        }
        double x = hullLocal[0] * hullHalf[0] - spot.offset_x;
        double y = hullLocal[1] * hullHalf[1] - spot.offset_y;
        return x * x + y * y <= (double) spot.radius * spot.radius;
    }

    private static double axisOffset(double dx, double dy, double dz, Vector3f axis) {
        return dx * axis.x + dy * axis.y + dz * axis.z;
    }

    /**
     * True when a hit on the hull's front or rear face landed on the running gear: either
     * outer strip of that face, below the configured height.
     *
     * <p>The stock T-64's track parts carry only nine 0.125-block locator cubes each, so they
     * are never actually struck; the track is therefore recognised by where the round landed
     * on the hull box rather than by part id.
     */
    /**
     * True when a hit on the hull's flank landed level with the road wheels: the whole strip below
     * the configured height, gaps between the wheels included.
     *
     * <p>The gaps are the one approximation here, and they were measured rather than waved at - on
     * the BTR-3 the eight wheel boxes cover about five sixths of the run, in four bands. Treating
     * the strip as solid costs a little accuracy in those gaps and saves a list of bands per
     * vehicle, which is the same trade the track strips make.
     *
     * @param localY hit height on the hull's normalised scale, {@code +1} at the top
     */
    static boolean isRoadWheel(ArmorConfig.RoadWheels wheels, double localY) {
        return localY <= wheels.top;
    }

    static boolean isTrackEnd(ArmorConfig.TrackEnds trackEnds, double localX, double localY) {
        return Math.abs(localX) >= trackEnds.inner_edge && localY <= trackEnds.top;
    }

    /**
     * Maps the OBB face index returned by {@link OBB#embeddingFace} onto one of the five
     * per-face values. Used for both the plate thickness and the damage multiplier so the
     * two can never disagree about which face was hit.
     *
     * <p>The vehicle's model faces +Z, so a positive Z face is the front.
     */
    private static float faceValue(float front, float side, float rear, float roof, float floor, int face) {
        switch (Math.abs(face)) {
            case 1:
                return side;
            case 3:
                return face > 0 ? front : rear;
            default:
                return face > 0 ? roof : floor;
        }
    }

    /** Effective thickness = nominal / cos(angle between the plate normal and the incoming round). */
    private static float normalizeAgainstImpact(float thickness, OBB obb, int face, Vec3 direction) {
        // Clamp so a nearly edge-on hit does not blow the thickness up to infinity.
        double cos = Math.max(0.05, cosineWithFace(obb, face, direction));
        return (float) (thickness / cos);
    }

    /**
     * @return how squarely the round meets a face, as the cosine of the angle between them: 1 for a
     *         hit straight on, approaching 0 for one arriving along the surface.
     *
     * <p>Signed values are folded to positive - a round leaving through the back of a plate met it
     * just as squarely as one entering the front - and the length of the normal is divided out,
     * because nothing here guarantees the model's axes are unit vectors.
     *
     * <p>One copy, used by both the slope normalisation and the arrival angle, because they are the
     * same quantity: two functions computing it separately is how a slope that reads as 30 degrees
     * to one of them reads as something else to the other.
     */
    private static double cosineWithFace(OBB obb, int face, Vec3 direction) {
        Vector3f normal = obb.getAxes()[Math.abs(face) - 1];
        double length = Math.sqrt(normal.x * normal.x + normal.y * normal.y + normal.z * normal.z);
        if (length < 1.0E-7) {
            return 1.0;
        }
        double cos = Math.abs(
                direction.f_82479_ * (normal.x / length)
                        + direction.f_82480_ * (normal.y / length)
                        + direction.f_82481_ * (normal.z / length));
        return Math.min(1.0, cos);
    }

    /**
     * @return how obliquely the round arrived, in degrees away from the face: {@code 0} square on,
     *         {@code 90} skimming along it. This is the number a screen's minimum angle is compared
     *         against, and it is the complement of what the slope normalisation uses.
     */
    static double angleFromNormal(OBB obb, int face, Vec3 direction) {
        return Math.toDegrees(Math.acos(cosineWithFace(obb, face, direction)));
    }

    private static void applyDamage(DamageSource source, AbstractVehicle vehicle, PartUnit<?> partUnit, float damage) {
        if (partUnit != null && partUnit.isDefensive() && !partUnit.isDetached()) {
            partUnit.hurt(source, damage);
        }
        // getHealth/setHealth are the vehicle's own methods (ContainerCraft), not LivingEntity's.
        vehicle.setHealth(Math.max(0.0F, vehicle.getHealth() - damage));
    }

    /**
     * The blast of a chemical round: damage that no armour stops, and a flash that leaves the
     * crew's eyes stinging.
     *
     * @param thickness the plate the round met, used to scale the damage when the round asks for it
     */
    private static void applySpall(AbstractVehicle vehicle, ArmorConfig.Ammo round, float thickness) {
        vehicle.setHealth(Math.max(0.0F, vehicle.getHealth() - spallDamage(round, thickness)));

        if (round.spall_blindness_ticks <= 0) {
            return;
        }
        // Resolved by name instead of through MobEffects.BLINDNESS: the SRG field numbering of
        // MobEffects does not follow declaration order, so the constant cannot be trusted when
        // reading the obfuscated jar this addon compiles against.
        MobEffect blindness = ForgeRegistries.MOB_EFFECTS.getValue(new ResourceLocation("blindness"));
        if (blindness == null) {
            return;
        }
        MobEffectInstance flash = new MobEffectInstance(blindness, round.spall_blindness_ticks);
        for (Entity passenger : vehicle.m_20197_()) {
            if (passenger instanceof LivingEntity living) {
                living.m_7292_(flash);
            }
        }
        // The driver normally rides as a passenger, but the seat is worth covering explicitly
        // rather than depending on that: re-applying an effect the crew already has is harmless.
        // getDriver() already returns a LivingEntity, so there is no pattern to match here.
        LivingEntity driver = vehicle.getDriver();
        if (driver != null) {
            driver.m_7292_(flash);
        }
    }

    /**
     * How hard a round's blast hits, given the plate it went off against.
     *
     * <p>Two shapes, chosen by the round. A shaped charge scatters a roughly fixed amount of spall,
     * so its damage is rolled inside a flat band. A high-explosive shell has nothing like that much
     * behind it, so its damage instead slides from {@code spall_damage_max} down to
     * {@code spall_damage_min} as the plate thickens: full strength up to the thickness it could
     * have penetrated, minimum once the plate reaches {@code spall_falloff_to}.
     */
    static float spallDamage(ArmorConfig.Ammo round, float thickness) {
        if (round.spallFallsOff()) {
            float t = (thickness - round.penetration) / (round.spall_falloff_to - round.penetration);
            t = Math.max(0.0F, Math.min(1.0F, t));
            return round.spall_damage_max + (round.spall_damage_min - round.spall_damage_max) * t;
        }
        float amount = round.spall_damage_min;
        if (round.spall_damage_max > round.spall_damage_min) {
            amount += ThreadLocalRandom.current().nextFloat()
                    * (round.spall_damage_max - round.spall_damage_min);
        }
        return amount;
    }

    /**
     * Plays the vehicle hit sound and re-posts {@link HitVehicleEvent} so the shooter's
     * client still draws a hit marker and a bullet hole.
     */
    private static void feedback(AbstractVehicle vehicle, Entity shooter, float caliber, Vec3 hitPos,
                                 Vec3 motion, float damage, boolean penetrated) {
        Vec3 soundOffset = vehicle.relativeRotPos(hitPos, true).m_82546_(vehicle.m_20182_());
        vehicle.playVehicleSound(hitSound(vehicle, damage), soundOffset, 1.0F, 4.0F, 1.0F, 0, false, false, true);

        if (shooter == null) {
            return;
        }
        Component message = Component.m_237115_(
                penetrated ? "message.ywzj_vehicle_armor.penetrated" : "message.ywzj_vehicle_armor.ricochet");
        MinecraftForge.EVENT_BUS.post(new HitVehicleEvent(
                shooter.m_20148_(),
                vehicle.m_19879_(),
                hitPos,
                motion,
                caliber,
                damage,
                message));
    }

    private static SoundEvent hitSound(AbstractVehicle vehicle, float damage) {
        float scale = damage / Math.max(1.0F, vehicle.getMaxHealth());
        if (scale >= 0.5F) {
            return AllSounds.VEHICLE_HIT_BIG.get();
        }
        if (scale >= 0.25F) {
            return AllSounds.VEHICLE_HIT_MED.get();
        }
        return AllSounds.VEHICLE_HIT_SMALL.get();
    }
}
