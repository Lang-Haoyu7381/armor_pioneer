package com.ywzjvehicle.armor;

import net.minecraft.core.registries.Registries;

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
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.core.registries.BuiltInRegistries;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.ywzj.vehicle.all.AllSounds;
import org.ywzj.vehicle.api.event.HitVehicleEvent;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.weapon.AmmoEntity;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.structure.OBB;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
    private static final ResourceLocation TACZ_BULLET = ResourceLocation.fromNamespaceAndPath("tacz", "bullet");
    private static final ResourceLocation TACZ_BULLET_IGNORE_ARMOR =
            ResourceLocation.fromNamespaceAndPath("tacz", "bullet_ignore_armor");

    /** @return whether this is one of the two halves of a TACZ bullet hit. */
    private static boolean isTaczHit(DamageSource source) {
        Holder<DamageType> type = source.typeHolder();
        return type.is(TACZ_BULLET) || type.is(TACZ_BULLET_IGNORE_ARMOR);
    }

    /**
     * @return whether this is the armour-ignoring half of a TACZ hit - the second of the two hurt
     *         calls {@code EntityKineticBullet.tacAttackEntity} makes per bullet.
     */
    private static boolean isTaczArmourPiercingHalf(DamageSource source) {
        return source.typeHolder().is(TACZ_BULLET_IGNORE_ARMOR);
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
        if (EXPLOSION_MESSAGE_ID.equals(source.getMsgId())) {
            if (!(source.getDirectEntity() instanceof AmmoEntity blast)) {
                return false;
            }
            ArmorConfig.VehicleArmor victim = armorFor(vehicle);
            // Only for vehicles this addon actually governs: elsewhere the blast is untouched.
            //
            // An aircraft is one of the cases the rule below must not cover. The rule exists
            // because a tank's warhead round was already measured on the way in, so counting its
            // blast again would push the same plate through twice. An aircraft's skin model is
            // about penetration; a shell going off beside the fuselage is not a penetration, and
            // dropping it would make a fighter immune to the very near miss that kills it. The
            // direct hit is still measured once, and the extra blast is the difference between a
            // hit and a hit with a bursting charge behind it.
            if (victim == null || victim.aircraft != null) {
                return false;
            }
            ArmorConfig.Ammo warhead = modelFor(blast);
            if (warhead == null || !warhead.replacesBaseExplosion()) {
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
            Entity bullet = source.getDirectEntity();
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
                    bullet.position(), bullet.getDeltaMovement(), "none", source.getEntity(), 0.0F);
        }

        // Vanilla Minecraft's own weapons: a sword, an axe, a bow, a crossbow, a thrown trident.
        // They carry no ammunition id, so they are recognised by damage type and rated from the
        // damage the hit actually carried. The ray is the projectile's own for an arrow, and a
        // line from the attacker's eye to the hull for a melee swing - see VanillaWeapon.
        //
        // Logging is off for this path: a skeleton can loose an arrow every two seconds, and an
        // INFO line per hit would bury the tank rounds the log exists to show. The ricochet sound
        // and the shooter's hit marker are the feedback.
        if (VanillaWeapon.handles(source)) {
            ArmorConfig.Ammo round = VanillaWeapon.roundFor(source, amount);
            if (round == null || armorFor(vehicle) == null) {
                return false;
            }
            VanillaWeapon.Ray ray = VanillaWeapon.ray(source, vehicle);
            if (ray == null) {
                return false;
            }
            return resolveHit(vehicle, source, round, ray.start(), ray.motion(),
                    "none", source.getEntity(), 0.0F, false);
        }

        // Only projectiles that carry a known ammunition id take part.
        if (!(source.getDirectEntity() instanceof AmmoEntity ammo)) {
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
        return resolveHit(vehicle, source, model, ammo.position(), ammo.getDeltaMovement(),
                fuze.name(), ammo.getOwner(), ammo.getCaliber());
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
        return resolveHit(vehicle, source, model, start, motion, fuzeLabel, shooter, caliber, true);
    }

    /**
     * The same resolution, with the log line made optional.
     *
     * <p>Only vanilla weapons pass {@code false}. Everything this addon rated before them is a
     * deliberate, slow-firing round worth a line; a bow is not, and a skeleton can fire one every
     * two seconds for as long as it lives. The arithmetic and the damage are identical either way.
     */
    private static boolean resolveHit(AbstractVehicle vehicle, DamageSource source,
                                      ArmorConfig.Ammo model, Vec3 start, Vec3 motion,
                                      String fuzeLabel, Entity shooter, float caliber, boolean log) {
        ArmorConfig.VehicleArmor armor = armorFor(vehicle);
        if (armor == null) {
            return false;
        }
        float penetration = model.penetration;

        if (motion.length() < 1.0E-7) {
            return false;
        }
        Vec3 rayEnd = start.add(motion);

        Vec3 hitPos = VectorUtil.closestHitObbPosition(vehicle, start, rayEnd);
        if (hitPos == null) {
            return false;
        }

        Vec3 direction = motion.normalize();
        Vec3 probeEnd = hitPos.add(direction.scale(0.1));
        PartUnit<?> partUnit = VectorUtil.hitPartUnit(vehicle, start, probeEnd, false);

        VectorUtil.HitOBB hitObb = VectorUtil.hitOBB(vehicle.getOBBs(), start, rayEnd);
        if (hitObb == null) {
            return false;
        }
        OBB obb = hitObb.obb();

        // An aircraft is not described by a plate. The covering is the first layer, and what the
        // round finds behind it - a pilot, an engine, a fuel tank, or nothing - is the answer.
        // See resolveAircraftHit.
        if (armor.aircraft != null) {
            return resolveAircraftHit(vehicle, source, model, motion, fuzeLabel, shooter, caliber,
                    armor.aircraft, hitPos, direction, obb, partUnit, log);
        }

        // The zone decision, reactive panels included - see resolve(). A panel takes its HP off
        // the round and is spent; what is left is measured against the armour behind it.
        java.util.Set<String> spent = spentPanels(vehicle);
        Resolution resolution = resolve(armor, model.profile,
                hitFor(armor, vehicle, obb, hitPos, partUnit, direction), penetration, spent);
        Zone zone = resolution.zone();
        // Named for the log even when the panel is the thing that stopped the round: a round held
        // by reactive armour and one held by bare steel look identical otherwise.
        String panelName = resolution.panelName();
        if (resolution.panelUsed() != null && spent.add(resolution.panelUsed())) {
            VehicleArmorMod.LOGGER.info("[Vehicle Armor] {}: {} is spent",
                    vehicle.getVehicleId(), resolution.panelUsed());
            // The panel has done its job.  Its display bone goes dark (the part is destroyed),
            // but the part is NOT detached, so its OBB stays in the vehicle's hit list and the
            // next round meets the armour behind the empty box.  The repair tool heals this
            // part and PartUnitRepairMixin re-arms it.
            if (partUnit != null && isReactivePanel(vehicle, partUnit.getId())) {
                partUnit.setHealth(0.0F);
            }
        }
        // The hull-side add-on is a layer, not a part: its hit is recorded here and nothing is
        // destroyed. Every hit is a separate key, so the next one finds the layer one step weaker
        // and the repair tool's re-arm clears them all.
        if (resolution.addonUsed() != null && spent.add(resolution.addonUsed())) {
            VehicleArmorMod.LOGGER.info("[Vehicle Armor] {}: hull side add-on took hit {}/{}",
                    vehicle.getVehicleId(), resolution.addonHit(), resolution.addonHits());
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

        // What the panel took off the round, before the plate behind it is compared. Zero for
        // ordinary armour, so this is the same sum it always was for every zone but panels and
        // screens. A spent panel's zone is the armour behind it, and its own loss (if any) applies
        // there; a panel still in place uses its HP instead of its thickness, so its loss is not
        // taken twice.
        float effective = penetration - resolution.panelLoss()
                - (zone.eraHp > 0.0F ? 0.0F : zone.loss);
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
        if (log && panelName != null) {
            // A panel hit names both the panel and the plate behind it: without the first, the line
            // describes a round measured against armour it never reached - or, when the panel held,
            // a round with no explanation for why.
            VehicleArmorMod.LOGGER.info(
                    "[Vehicle Armor] {} hit on {}: {} fuze, reactive panel {} took {}mm"
                            + " ({}mm of penetration left), plate {}mm, residual {}mm, damage {}",
                    vehicle.getVehicleId(), zone.name, fuzeLabel, panelName,
                    Math.round(resolution.panelLoss()),
                    Math.round(penetration - resolution.panelLoss()), thickness, residual, damage);
        } else if (log && zone.loss > 0.0F) {
            VehicleArmorMod.LOGGER.info(
                    "[Vehicle Armor] {} hit on {}: {} fuze, plate {}mm, screen took {}mm off the round,"
                            + " penetration {}mm, residual {}mm, damage {}",
                    vehicle.getVehicleId(), zone.name, fuzeLabel, thickness, zone.loss, penetration,
                    residual, damage);
        } else if (log) {
            VehicleArmorMod.LOGGER.info(
                    "[Vehicle Armor] {} hit on {}: {} fuze, plate {}mm, penetration {}mm, residual {}mm, damage {}",
                    vehicle.getVehicleId(), zone.name, fuzeLabel, thickness, penetration, residual, damage);
        }
        return true;
    }

    /** @return the part unit with this id, or {@code null} when the model has none. */
    private static PartUnit<?> partById(AbstractVehicle vehicle, String id) {
        if (id == null) {
            return null;
        }
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (id.equals(part.getId())) {
                return part;
            }
        }
        return null;
    }

    /**
     * The scanner's view of an aircraft: the skin on the face the sight line meets, or the wing
     * skin when it is a wing hit. The components are not named here - which one a round reaches
     * depends on the round's line, not on the point the scanner is aimed at, and a readout that
     * named one would be answering a question the player did not ask.
     */
    private static Zone aircraftPlate(AbstractVehicle vehicle,
                                      ArmorConfig.AircraftArmor aircraft, OBB obb, Vec3 hitPos,
                                      PartUnit<?> partUnit) {
        int face = aircraftFace(vehicle, obb, hitPos, partUnit);
        boolean wing = isWingHit(vehicle, aircraft, obb, partUnit);
        float thickness = wing ? aircraft.wing.thickness
                : faceValue(aircraft.skin.front, aircraft.skin.side, aircraft.skin.rear,
                        aircraft.skin.roof, aircraft.skin.floor, face);
        float multiplier = wing ? aircraft.wing.multiplier
                : faceValue(aircraft.frame_multiplier.front, aircraft.frame_multiplier.side,
                        aircraft.frame_multiplier.rear, aircraft.frame_multiplier.roof,
                        aircraft.frame_multiplier.floor, face);
        return new Zone(wing ? "wing skin" : "skin", thickness, multiplier, 0.0F, face, 0.0F,
                0.0F, null);
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
        double dx = hitPos.x - centre.x;
        double dy = hitPos.y - centre.y;
        double dz = hitPos.z - centre.z;

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
     * @return whether this part is a bolted-on reactive panel ({@code part_armor} with ERA HP).
     *         Used by the client to hide a destroyed panel's display bone while keeping its OBB.
     */
    public static boolean isReactivePanel(AbstractVehicle vehicle, String partId) {
        if (vehicle == null || partId == null) {
            return false;
        }
        ArmorConfig.VehicleArmor armor = armorFor(vehicle);
        if (armor == null || armor.part_armor == null) {
            return false;
        }
        ArmorConfig.Plate panel = armor.part_armor.get(partId);
        return panel != null && panel.era_hp > 0.0F;
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

    /**
     * The hybrid drive this vehicle carries, or {@code null} when it has none.
     *
     * <p>Resolved against the same two ids its armour and suspension are, in the same order, and
     * kept apart from both for the same reason: a drivetrain is equipment, not protection, and a
     * vehicle can carry one without this addon having a table for what a round does to its hull.
     */
    public static Hybrid.Settings hybridFor(AbstractVehicle vehicle) {
        Hybrid.Settings settings = ArmorConfig.hybridFor(vehicle.getVehicleId());
        return settings != null ? settings : ArmorConfig.hybridFor(vehicle.getDisplayId());
    }

    /**
     * The hatch periscope this vehicle carries, or {@code null} when it has none.
     *
     * <p>Resolved against the same two ids its armour, suspension and hybrid are, in the same
     * order, and kept apart from all three: a periscope is a viewpoint on a seat, not protection
     * and not a drivetrain, and a vehicle can carry one without this addon having a table for
     * what a round does to its hull.
     */
    public static Periscope.Settings periscopeFor(AbstractVehicle vehicle) {
        Periscope.Settings settings = ArmorConfig.periscopeFor(vehicle.getVehicleId());
        return settings != null ? settings : ArmorConfig.periscopeFor(vehicle.getDisplayId());
    }

    /**
     * The multiplier this vehicle's power-to-weight ratio applies to its acceleration and turn.
     *
     * <p>Resolved through the same armour profile the HUD reads the engine row from, so the number
     * on screen and the number in the movement solver cannot drift apart. One is the answer for
     * every vehicle the addon has no engine figure for, including every vehicle outside the Armor
     * Pioneer pack. See {@link ArmorConfig#enginePowerScale}.
     */
    public static float enginePowerScale(AbstractVehicle vehicle) {
        ArmorConfig.VehicleArmor armor = armorFor(vehicle);
        return armor == null ? 1.0F
                : ArmorConfig.enginePowerScale(armor.engine_horsepower,
                        armor.horsepower_per_ton, vehicle.curbWeight);
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
         * Millimetres of penetration this zone's reactive panel takes off a round before the round
         * is measured against the armour behind it. Zero for everything that is not reactive armour,
         * which is nearly all of it.
         *
         * <p>The panel is single-use: the hit that spends it is also the only one it affects. The
         * running gear is not reactive in this sense - a road wheel is not covering an armour plate,
         * it is the thing the round met.
         */
        public final float eraHp;

        /**
         * The hull-side add-on armour's pool, in millimetres, or zero when the vehicle has no
         * such layer. Read with {@link #addonFractions}: one is the pool, the other the share each
         * hit takes out of it.
         *
         * <p>Unlike {@link #eraHp} this layer is not single-use; the hit that uses its last share
         * is the one that removes it.
         */
        public final float addonHp;

        /** The share of {@link #addonHp} each successive hit takes off, in order; null when none. */
        public final float[] addonFractions;

        Zone(String name, float thickness, float multiplier, float loss, int face, float eraHp,
             float addonHp, float[] addonFractions) {
            this.name = name;
            this.thickness = thickness;
            this.multiplier = multiplier;
            this.loss = loss;
            this.face = face;
            this.eraHp = eraHp;
            this.addonHp = addonHp;
            this.addonFractions = addonFractions;
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
     *                        so the hit is being re-resolved against the armour behind them. A
     *                        reactive panel asks this after it has taken its HP off the round
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
        // An aircraft has no turret and no glacis to report. The scanner shows the skin it would
        // meet, which is the honest answer; the components behind it are a property of the round's
        // path rather than of the point the sight line lands on.
        if (armor.aircraft != null) {
            return aircraftPlate(vehicle, armor.aircraft, obb, hitPos, partUnit);
        }
        return resolveZone(armor, profile, hitFor(armor, vehicle, obb, hitPos, partUnit, direction));
    }

    /**
     * The facts a zone decision is made from, gathered off the vehicle and the impact.
     *
     * <p>Split out of {@link #plateFor} so that a caller which has to ask the question twice - the
     * reactive-panel resolution in {@link #resolve} - builds the impact once and asks about it
     * twice, rather than deriving the geometry again and risking the two passes describing
     * different hits.
     */
    public static Hit hitFor(ArmorConfig.VehicleArmor armor, AbstractVehicle vehicle, OBB obb,
                             Vec3 hitPos, PartUnit<?> partUnit, Vec3 direction) {
        boolean turret = isTurretPart(armor, partUnit);
        // Every hull-side decision - the glacis split, the track strips, the reactive panels, the
        // machine-gun port - is anchored to the hull box rather than to the part that was struck.
        double[] hullLocal = turret ? null : hullLocalPosition(vehicle, hitPos);
        double[] hullHalf = turret ? null : hullHalfExtents(vehicle);
        int face = obb.embeddingFace(hitPos);
        if (isPartPanel(armor, partUnit)) {
            // A reactive panel is a box bolted to the outside, often slanted.  Its own box face
            // is whatever face the ray clipped first (a slanted tile's AABB is entered through
            // its top or side), not the vehicle face whose armour lies behind it.  The round's
            // travel direction, read in the panel's own axes, is the face it actually entered:
            // a shell moving -z has come through the +z (front) face.
            face = faceFromDirection(obb, direction);
        }
        ArmorConfig.WeakSpot weakSpot = armor.turret_weak_spot;
        boolean gunMount = turret && face == 3 && weakSpot != null && weakSpot.enabled
                && isGunMount(partUnit, hitPos, weakSpot);
        return new Hit(turret, face, hullLocal, hullHalf, gunMount,
                angleFromNormal(obb, face, direction),
                partUnit == null ? null : partUnit.getId(), false);
    }

    /** @return whether the struck part is a bolted-on reactive panel from {@code part_armor}. */
    private static boolean isPartPanel(ArmorConfig.VehicleArmor armor, PartUnit<?> partUnit) {
        if (partUnit == null || armor.part_armor == null) {
            return false;
        }
        ArmorConfig.Plate panel = armor.part_armor.get(partUnit.getId());
        return panel != null && panel.era_hp > 0.0F;
    }

    /**
     * The face a travelling round entered, in the OBB's own axes.
     *
     * <p>{@link OBB#embeddingFace} asks which face a <em>point</em> is nearest, which for a thin
     * or inflated panel box can be a side even when the round came through the front.  The
     * direction of travel answers the same question without depending on the box's inflation:
     * a round moving along -z entered the +z face.
     *
     * @return the same encoding as {@code embeddingFace}: +-1 = x sides, +-2 = y top/bottom,
     *         +-3 = z front/back
     */
    private static int faceFromDirection(OBB obb, Vec3 direction) {
        Vector3f[] axes = obb.getAxes();
        double dx = direction.x * axes[0].x + direction.y * axes[0].y
                + direction.z * axes[0].z;
        double dy = direction.x * axes[1].x + direction.y * axes[1].y
                + direction.z * axes[1].z;
        double dz = direction.x * axes[2].x + direction.y * axes[2].y
                + direction.z * axes[2].z;
        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        double az = Math.abs(dz);
        if (az >= ax && az >= ay) {
            return dz > 0.0 ? -3 : 3;
        }
        if (ax >= ay) {
            return dx > 0.0 ? -1 : 1;
        }
        return dy > 0.0 ? -2 : 2;
    }

    /**
     * A hit's final answer: the plate the round is measured against, and what it met on the way.
     *
     * @param zone      the plate the round is measured against - the armour behind the panel when
     *                  the round got through it, or the panel itself when it did not
     * @param panelName the reactive panel this hit dealt with, or {@code null} for a hit that met no
     *                  panel at all. Named for the log even after the panel has been spent
     * @param panelUsed the one-shot panel this hit used up, or {@code null}. The caller records it;
     *                  a panel that has been used is no longer in the way of anything
     * @param panelLoss millimetres of penetration the panel took off this round. Equal to the
     *                  panel's HP when the round got through, and to the round's whole penetration
     *                  when the panel stopped it
     */
    public record Resolution(Zone zone, String panelName, String panelUsed, float panelLoss,
                             String addonUsed, int addonHit, int addonHits) {

        /**
         * The same answer without the hull-side add-on layer: used where that layer has already
         * been dealt with, and by the reactive-panel-only path.
         */
        static Resolution withoutAddon(Zone zone, String panelName, String panelUsed,
                                       float panelLoss) {
            return new Resolution(zone, panelName, panelUsed, panelLoss, null, 0, 0);
        }
    }

    /**
     * Resolves a hit against a reactive panel, if it meets one.
     *
     * <p>A panel is a single-use layer in front of the armour behind it, and its HP is the number of
     * millimetres it takes off the one round that sets it off. The round then goes on to be measured
     * against the plate the panel covers, with what is left. A round with no more penetration than
     * the panel is worth is stopped by the panel itself; the panel is spent either way.
     *
     * <p>A tandem warhead is not special-cased. Its precursor and main charge are one round here:
     * the panel takes its HP off the whole, and whatever is left reaches the armour behind.
     *
     * <p>Lives here rather than inside {@code tryHandle} so the rule can be exercised without a
     * vehicle, a projectile or a level - the tool that checks it calls this same method.
     *
     * @param penetration what the round can penetrate before it meets anything
     * @param spent the panels already used up on this vehicle, by the key {@link #panelKey} builds,
     *              or {@code null} when nothing is spent. Whether a panel is still there is not
     *              something this method can know on its own
     */
    static Resolution resolve(ArmorConfig.VehicleArmor armor, String profile, Hit hit,
                              float penetration, java.util.Set<String> spent) {
        Zone zone = resolveZone(armor, profile, hit);

        // The hull-side add-on armour is the outermost layer on the flank, and the only one that
        // survives a hit: it takes its share of the round, comes back weaker, and is gone after its
        // last share. It is read before the reactive panel, which it sits in front of.
        if (zone.addonHp > 0.0F && zone.addonFractions != null && zone.addonFractions.length > 0) {
            int used = addonHits(spent);
            if (used < zone.addonFractions.length) {
                float loss = zone.addonHp * zone.addonFractions[used];
                String key = ADDON_KEY + "#" + (used + 1);
                if (penetration <= loss) {
                    // This share alone is worth more than the round: the layer holds it. The zone
                    // is returned with the layer still on it, so the thickness behind leaves the
                    // residual negative and nothing is hurt.
                    return new Resolution(zone, ADDON_NAME, null, penetration, key, used + 1,
                            zone.addonFractions.length);
                }
                Resolution behind = resolveReactive(armor, profile, hit, penetration - loss, spent);
                return new Resolution(behind.zone(), ADDON_NAME, behind.panelUsed(),
                        loss + behind.panelLoss(), key, used + 1, zone.addonFractions.length);
            }
        }
        return resolveReactive(armor, profile, hit, penetration, spent);
    }

    /**
     * The reactive-panel pass, with the hull-side add-on layer already dealt with - or absent.
     *
     * <p>Kept as its own method so the add-on can hand the round on with less penetration and get
     * the same answer the game would have given without it; a second copy of this rule is exactly
     * the sort of thing that agrees with itself and not with the game.
     */
    private static Resolution resolveReactive(ArmorConfig.VehicleArmor armor, String profile,
                                              Hit hit, float penetration,
                                              java.util.Set<String> spent) {
        Zone zone = resolveZone(armor, profile, hit);
        if (zone.eraHp <= 0.0F) {
            return Resolution.withoutAddon(zone, null, null, 0.0F);
        }
        String panel = panelKey(hit, zone);

        if (spent != null && spent.contains(panel)) {
            // Gone. This round is the first thing to reach the armour the panel was covering.
            return Resolution.withoutAddon(resolveZone(armor, profile, hit.pastLayers()),
                    zone.name, null, 0.0F);
        }
        if (penetration <= zone.eraHp) {
            // The panel took the whole round and is spent doing it. The panel's own zone is
            // returned, so its thickness leaves the residual negative and nothing behind is hurt.
            return Resolution.withoutAddon(zone, zone.name, panel, penetration);
        }
        // Through, less the panel's worth. What is left is measured against the armour behind.
        return Resolution.withoutAddon(resolveZone(armor, profile, hit.pastLayers()), zone.name,
                panel, zone.eraHp);
    }

    /** One hull-side add-on layer per vehicle: the hits are shared by both flanks. */
    private static final String ADDON_KEY = "hull-side-addon";
    private static final String ADDON_NAME = "hull side add-on";

    /**
     * @return how many hits the hull-side add-on layer has already taken. Encoded as one key per
     *         hit in the same set the reactive panels use, so a repair that clears that set puts
     *         the whole layer back.
     */
    private static int addonHits(java.util.Set<String> spent) {
        if (spent == null) {
            return 0;
        }
        int n = 0;
        while (spent.contains(ADDON_KEY + "#" + (n + 1))) {
            n++;
        }
        return n;
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
     * Puts a repaired reactive panel back in place.
     *
     * <p>The repair tool heals every part unit, including the ones a reactive panel is; a part
     * whose health is being restored is being put back on the tank, so the panel it carried is
     * back too.  Called from {@code PartUnitRepairMixin} on {@code PartUnit#setHealth}: a part
     * that is healed (or set to its current health by the repair tool) re-arms, a part whose
     * health falls to a hit does not.
     */
    public static void rearm(AbstractVehicle vehicle, String partId) {
        if (vehicle == null || partId == null) {
            return;
        }
        java.util.Set<String> spent = SPENT_PANELS.get(vehicle);
        if (spent != null && spent.remove(partId)) {
            VehicleArmorMod.LOGGER.info("[Vehicle Armor] {}: {} is re-armed",
                    vehicle.getVehicleId(), partId);
        }
    }

    /**
     * Puts every panel of a vehicle back in place, for a repair that is not tied to one part.
     *
     * <p>The repair tool heals the vehicle itself as well as its parts, and a tank whose
     * panels absorbed their hit may have taken no damage at all - every part is already at
     * full health, so no per-part heal can tell that the tool was used.  The tool's own
     * {@code setHealth} call is the signal; it re-arms the lot.
     */
    public static void rearmAll(AbstractVehicle vehicle) {
        if (vehicle == null) {
            return;
        }
        java.util.Set<String> spent = SPENT_PANELS.get(vehicle);
        if (spent != null && !spent.isEmpty()) {
            spent.clear();
            VehicleArmorMod.LOGGER.info("[Vehicle Armor] {}: reactive panels re-armed",
                    vehicle.getVehicleId());
        }
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
        // The hull's add-on armour is a layer on the flank in front of both the reactive panel and
        // the plate; unlike the panel it survives more than one hit. Its conditions are the panel's
        // minus the angle: it is a solid kit, not a screen, so every side hit meets it.
        ArmorConfig.Plate addon = (!throughLayers && !turret && Math.abs(face) == 1)
                ? pick(override == null ? null : override.hull_side_addon, armor.hull_side_addon)
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
        float eraHp = 0.0F;

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
            eraHp = byPart.era_hp;
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
            eraHp = reactive.era_hp;
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
            // matched against whichever of the two the impact height falls into - and against the
            // top strip's own figure when the plate has one.
            double height = hullLocal == null ? 0.0 : hullLocal[1];
            ArmorConfig.Plate values = frontPlate(split, override, height);
            thickness = values.thickness;
            multiplier = values.multiplier;
            loss = values.penetration_loss;
            name = frontZoneName(split, height);
        } else {
            thickness = faceValue(plates.front, plates.side, plates.rear, plates.roof, plates.floor, face);
            multiplier = faceValue(multipliers.front, multipliers.side, multipliers.rear,
                    multipliers.roof, multipliers.floor, face);
        }
        return new Zone(name, thickness, multiplier, loss, face, eraHp,
                addon == null ? 0.0F : addon.addon_hp,
                addon == null ? null : addon.addon_fractions);
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

    /**
     * The plate a hull-front hit meets, band by band.
     *
     * <p>Up to four zones can exist on the face, top to bottom: the upper plate's top strip
     * ({@link ArmorConfig.FrontSplit#upper_band}), the rest of the upper plate, the lower plate's
     * top strip ({@link ArmorConfig.FrontSplit#lower_band}), and the rest of the lower plate. A
     * vehicle that states neither band keeps the two-zone behaviour every earlier config had.
     *
     * <p>A round's own table wins outright when it has one: {@code hull_front_split} inside an
     * override is a complete statement about this face for that round, and every one of them was
     * written before the bands existed. Falling back band by band instead would give a shaped
     * charge the dart's figure on a strip its own table never named.
     */
    static ArmorConfig.Plate frontPlate(ArmorConfig.FrontSplit split,
                                        ArmorConfig.ArmorOverride override, double height) {
        ArmorConfig.FrontSplitPlates stated = override == null ? null : override.hull_front_split;
        if (stated != null) {
            ArmorConfig.Plate plate = height >= split.split ? stated.upper : stated.lower;
            if (plate != null) {
                return plate;
            }
            // A hand-edited table can name the split and still leave one half out. That half used
            // to fall back to the vehicle's ordinary plate, and it still does - the fallback is
            // deliberately not the bands, which are a finer statement than the table made.
            return height >= split.split ? split.upper : split.lower;
        }
        if (isUpperBand(split, height)) {
            return split.upper_band;
        }
        if (isLowerBand(split, height)) {
            return split.lower_band;
        }
        return height >= split.split ? split.upper : split.lower;
    }

    /**
     * The scanner's name for the zone a hull-front hit landed in, in the config's own vocabulary.
     *
     * <p>The band names are geometric facts about the tank, so they are used whether or not the
     * round's own table overrode the figures - the scanner is still being pointed at the glacis'
     * top strip.
     */
    static String frontZoneName(ArmorConfig.FrontSplit split, double height) {
        if (isUpperBand(split, height)) {
            return "hull upper front (ricochet)";
        }
        if (isLowerBand(split, height)) {
            return "hull lower front (belt)";
        }
        return height >= split.split ? "hull upper front" : "hull lower front";
    }

    /** @return whether the height falls in the upper plate's optional top strip. */
    private static boolean isUpperBand(ArmorConfig.FrontSplit split, double height) {
        return split.upper_band != null && height >= split.upper_band_split;
    }

    /**
     * @return whether the height falls in the lower plate's optional top strip. Bounded above by
     *         {@link ArmorConfig.FrontSplit#split} so a mistyped threshold cannot eat every hit
     *         below it.
     */
    private static boolean isLowerBand(ArmorConfig.FrontSplit split, double height) {
        return split.lower_band != null && height < split.split
                && height >= split.lower_band_split;
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
        double dx = hitPos.x - centre.x;
        double dy = hitPos.y - centre.y;
        double dz = hitPos.z - centre.z;
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
    /**
     * A hit on an aircraft: through the skin, then through whatever the round's own path meets.
     *
     * <p>The three layers are the model:
     * <ol>
     *   <li>the covering. Below its thickness the round does nothing at all, and that is the one
     *       outcome a fighter really has: most of the airframe is a few millimetres thick.</li>
     *   <li>the components. Every box is tested against the ray the round is travelling on, not
     *       against the surface it struck, because a hit through a wing root and one through the
     *       cockpit door are the same point of entry only in the sense that both are on the model.
     *       They are sorted by the distance at which the round reaches them, and an armour layer
     *       is paid for before the component behind it is tested - that is how a seat back works,
     *       and why it is a box of its own rather than a field on the pilot.</li>
     *   <li>the frame. A hit that reaches no component is a hole in the structure, worth the
     *       residual times the aircraft's own per-face multiplier.</li>
     * </ol>
     *
     * <p>The round's flat {@code multiplier} is deliberately not read here. It exists on a machine
     * gun so that its damage is the same wherever it lands on a tank, which is the opposite of what
     * this model needs: a wing, a fuselage and a pilot must be worth three different things.
     *
     * <p>A blast is not resolved here. See {@link #tryHandle}: an aircraft takes the base mod's
     * explosion, so a near miss still hurts and a bursting charge still adds to a direct hit.
     */
    private static boolean resolveAircraftHit(AbstractVehicle vehicle, DamageSource source,
                                              ArmorConfig.Ammo model, Vec3 motion, String fuzeLabel,
                                              Entity shooter, float caliber,
                                              ArmorConfig.AircraftArmor aircraft, Vec3 hitPos,
                                              Vec3 direction, OBB obb, PartUnit<?> partUnit,
                                              boolean log) {
        int face = aircraftFace(vehicle, obb, hitPos, partUnit);
        boolean wing = isWingHit(vehicle, aircraft, obb, partUnit);

        float thickness = wing ? aircraft.wing.thickness
                : faceValue(aircraft.skin.front, aircraft.skin.side, aircraft.skin.rear,
                        aircraft.skin.roof, aircraft.skin.floor, face);
        float zoneMultiplier = wing ? aircraft.wing.multiplier
                : faceValue(aircraft.frame_multiplier.front, aircraft.frame_multiplier.side,
                        aircraft.frame_multiplier.rear, aircraft.frame_multiplier.roof,
                        aircraft.frame_multiplier.floor, face);
        if (ArmorConfig.angleNormalization()) {
            thickness = normalizeAgainstImpact(thickness, obb, face, direction);
        }

        float penetration = model.penetration;
        if (penetration < thickness) {
            // The round did not get inside. This is the ordinary outcome for a rifle round against
            // a fighter's covering only when it is nearly edge-on; most rounds pass.
            feedback(vehicle, shooter, caliber, hitPos, motion, 0.0F, false);
            if (log) {
                VehicleArmorMod.LOGGER.info(
                        "[Vehicle Armor] {} hit on {}: {} fuze, skin {}mm held against {}mm",
                        vehicle.getVehicleId(), wing ? "wing skin" : "skin", fuzeLabel, thickness,
                        penetration);
            }
            return true;
        }
        float residual = penetration - thickness;

        // The components the round's line meets, nearest first. A plate is paid for before the
        // component behind it is tested; the first component with an effect is where the round
        // ends its journey as far as this addon is concerned.
        ComponentHit componentHit = null;
        float componentResidual = residual;
        String stoppedBy = null;
        float stoppedResidual = 0.0F;
        for (ComponentHit hit : componentHits(vehicle, aircraft, hitPos, direction)) {
            ArmorConfig.AircraftComponent component = hit.component();
            if (component.armor > 0.0F) {
                // Equality is a penetration, the same rule the plate path uses: a round rated for
                // exactly the plate it meets gets in with nothing left. A round that has less than
                // the plate is stopped in it.
                if (componentResidual < component.armor) {
                    stoppedBy = component.name;
                    stoppedResidual = componentResidual;
                    componentResidual = 0.0F;
                    break;
                }
                componentResidual -= component.armor;
            }
            if (component.hasEffect()) {
                componentHit = hit;
                break;
            }
        }

        float damage;
        String zoneName;
        boolean penetrated;
        if (componentHit != null) {
            ArmorConfig.AircraftComponent component = componentHit.component();
            damage = model.penetrationDamage(componentResidual,
                    component.damage_multiplier, thickness) + component.damage;
            zoneName = component.name;
            penetrated = true;
        } else if (stoppedBy != null) {
            // The round stopped in an armour plate inside the airframe. What it does is what any
            // skin hit does with what is left: the plate passes its energy into the structure,
            // and a charge that went off there still blasts the aircraft through the base mod.
            damage = stoppedResidual * zoneMultiplier;
            zoneName = stoppedBy;
            penetrated = false;
        } else {
            damage = model.penetrationDamage(residual, zoneMultiplier, thickness);
            zoneName = wing ? "wing skin" : "skin";
            penetrated = true;
        }

        feedback(vehicle, shooter, caliber, hitPos, motion, damage, penetrated);
        if (damage > 0.0F) {
            applyAircraftDamage(source, vehicle, partUnit,
                    componentHit == null ? null : componentHit.component(), damage);
        }
        if (componentHit != null) {
            woundComponentCrew(vehicle, source, componentHit.component(), componentResidual);
        }

        if (log) {
            if (componentHit != null) {
                VehicleArmorMod.LOGGER.info(
                        "[Vehicle Armor] {} hit on {}: {} fuze, skin {}mm, residual {}mm, damage {}",
                        vehicle.getVehicleId(), zoneName, fuzeLabel, thickness, componentResidual,
                        damage);
            } else if (stoppedBy != null) {
                VehicleArmorMod.LOGGER.info(
                        "[Vehicle Armor] {} hit on {}: {} fuze, {} stopped it with {}mm left, damage {}",
                        vehicle.getVehicleId(), wing ? "wing skin" : "skin", fuzeLabel, stoppedBy,
                        stoppedResidual, damage);
            } else {
                VehicleArmorMod.LOGGER.info(
                        "[Vehicle Armor] {} hit on {}: {} fuze, skin {}mm, penetration {}mm,"
                                + " residual {}mm, damage {}",
                        vehicle.getVehicleId(), zoneName, fuzeLabel, thickness, penetration,
                        residual, damage);
            }
        }
        return true;
    }

    /**
     * @return whether this hit belongs to the wing branch: the part of the airframe outside the
     *         fuselage, on the hull box's own x axis.
     *
     * <p>Measured from the struck <em>box</em>'s centre rather than the hit point. A wing hit
     * always lands on a {@code vehicle_body} cube whose centre is two or three blocks out, and a
     * fuselage hit on one whose centre is on the centreline; measuring the point instead makes the
     * two wing roots off by the small x offset every main cube carries. The threshold is in blocks
     * because that is what a modeller can measure, and {@code AircraftCheck} holds it between the
     * fuselage's half-width and the nearest wing cube.
     *
     * <p>A hit that lands on a part unit is never a wing hit: the landing gear and the doors are
     * outside the fuselage on some models, and they are equipment, not lifting surface.
     */
    private static boolean isWingHit(AbstractVehicle vehicle, ArmorConfig.AircraftArmor aircraft,
                                     OBB obb, PartUnit<?> partUnit) {
        if (partUnit != null) {
            return false;
        }
        VehicleCubeOBB main = vehicle.getMainCubeOBB();
        if (main == null) {
            return false;
        }
        OBB hull = main.obb();
        Vector3f[] axes = hull.getAxes();
        Vector3f centre = obb.center();
        Vector3f hullCentre = hull.center();
        double dx = centre.x - hullCentre.x;
        double dy = centre.y - hullCentre.y;
        double dz = centre.z - hullCentre.z;
        double lateral = Math.abs(dx * axes[0].x + dy * axes[0].y + dz * axes[0].z);
        return lateral > aircraft.wing_from;
    }

    /**
     * The face an aircraft hit entered.
     *
     * <p>The struck cube's own face answers this for a hull-structure hit. A hit on a part - a
     * door, the landing gear - has the part's axes, which are not the airframe's, so the main cube
     * is asked instead: "which side of the aircraft is this point on" is the question the skin
     * table answers.
     */
    private static int aircraftFace(AbstractVehicle vehicle, OBB obb, Vec3 hitPos,
                                    PartUnit<?> partUnit) {
        if (partUnit == null) {
            return obb.embeddingFace(hitPos);
        }
        VehicleCubeOBB main = vehicle.getMainCubeOBB();
        return main == null ? obb.embeddingFace(hitPos) : main.obb().embeddingFace(hitPos);
    }

    /** One component the round meets, and how far along its path the meeting happens. */
    private record ComponentHit(ArmorConfig.AircraftComponent component, double distance) {
    }

    /**
     * @return the components the round's line meets, nearest first.
     *
     * <p>Built as OBBs on the main cube's centre, axes and rotation rather than as world-space
     * boxes: the vehicle turns, banks and pitches, and a component has to turn with it. The box
     * may sit outside the main cube's own extents - the P-51's engine is ahead of its small
     * {@code main_structure} box and a pilot's head is above it on two of the three models - so
     * nothing here clamps a box to the hull.
     *
     * <p>The segment starts just inside the skin, not exactly on it: a component face that
     * coincides with the surface (the windscreen is the model for this) would otherwise be found
     * at a distance of zero by every round, whatever the angle.
     */
    private static List<ComponentHit> componentHits(AbstractVehicle vehicle,
                                                    ArmorConfig.AircraftArmor aircraft,
                                                    Vec3 hitPos, Vec3 direction) {
        VehicleCubeOBB main = vehicle.getMainCubeOBB();
        if (main == null || aircraft.components == null || aircraft.components.isEmpty()) {
            return List.of();
        }
        OBB hull = main.obb();
        Vector3f[] axes = hull.getAxes();
        Vec3 origin = hitPos.add(direction.scale(0.01));
        // OBB.rayIntersect is a ray test, not a segment test: the only thing that keeps a box
        // behind the aircraft out of the list is this length being the airframe's. The main cube
        // is a poor ruler - the P-51's is the cockpit box, and its engine is outside it - so the
        // vehicle's own structure length is used, with a floor for a model that reports nothing.
        double rayLength = Math.max(16.0, vehicle.getStructureLength() + 2.0);
        Vec3 end = hitPos.add(direction.scale(rayLength));
        List<ComponentHit> hits = new ArrayList<>();
        for (ArmorConfig.AircraftComponent component : aircraft.components) {
            if (component == null || component.center == null || component.half == null
                    || component.center.length != 3 || component.half.length != 3) {
                continue;
            }
            OBB box = componentBox(hull, axes, component);
            double distance;
            if (box.contains(origin)) {
                distance = 0.0;
            } else {
                Vec3 entry = OBB.rayIntersect(box, origin, end);
                if (entry == null) {
                    continue;
                }
                distance = entry.subtract(origin).dot(direction);
                if (distance < 0.0 || distance > rayLength) {
                    continue;
                }
            }
            hits.add(new ComponentHit(component, distance));
        }
        hits.sort(Comparator.comparingDouble(ComponentHit::distance));
        return hits;
    }

    /** A configured component, placed in the world on the main cube's frame. */
    private static OBB componentBox(OBB hull, Vector3f[] axes,
                                    ArmorConfig.AircraftComponent component) {
        Vector3f centre = new Vector3f(hull.center())
                .add(new Vector3f(axes[0]).mul(component.center[0]))
                .add(new Vector3f(axes[1]).mul(component.center[1]))
                .add(new Vector3f(axes[2]).mul(component.center[2]));
        return new OBB(centre,
                new Vector3f(component.half[0], component.half[1], component.half[2]),
                new Quaternionf(hull.rotation()));
    }

    /**
     * Puts an aircraft hit's damage where it belongs: on the part the component names, and on the
     * vehicle.
     *
     * <p>A seat is not damaged by a round that comes through its occupant - the seat is the crew
     * position, not an armour box - so only non-seat parts (the fuel tank) take part damage. The
     * frame hit passes the surface part instead, which is the door or the landing gear the round
     * came through.
     */
    private static void applyAircraftDamage(DamageSource source, AbstractVehicle vehicle,
                                            PartUnit<?> struck,
                                            ArmorConfig.AircraftComponent component, float damage) {
        if (component != null && component.part_id != null) {
            PartUnit<?> part = partById(vehicle, component.part_id);
            // A part with an occupant is a crew station: the occupant is what the round hurts,
            // and the seat itself is not an armour box. A part with nobody in it is equipment -
            // the P-51's fuel tank is a seat in the JSON by default, so the owner is the honest
            // test rather than the is_seat flag. (None of these parts declares max_health, so on
            // this pack the part's own health is inert and the vehicle's health is the effect.)
            if (part != null && part.getOwner() == null && part.isDefensive() && !part.isDetached()) {
                part.hurt(source, damage);
            }
        } else if (struck != null && struck.isDefensive() && !struck.isDetached()) {
            struck.hurt(source, damage);
        }
        vehicle.setHealth(Math.max(0.0F, vehicle.getHealth() - damage));
    }

    /**
     * Wounds the person in the seat a component names - the pilot, in practice.
     *
     * <p>A flat part plus a share of the residual, capped at full health: the flat is the bullet
     * that arrives with nothing left to give, the share is what a larger round does with the
     * energy it still has. The cap is the one number that has to be there - a main-gun hit through
     * the cockpit should kill, not overflow a player's health three times over.
     */
    private static void woundComponentCrew(AbstractVehicle vehicle, DamageSource source,
                                           ArmorConfig.AircraftComponent component, float residual) {
        if (component.crew_damage <= 0.0F && component.crew_damage_per_mm <= 0.0F) {
            return;
        }
        PartUnit<?> part = partById(vehicle, component.part_id);
        if (part == null) {
            return;
        }
        LivingEntity occupant = part.getOwner();
        if (occupant == null) {
            return;
        }
        float amount = Math.min(20.0F, component.crew_damage
                + component.crew_damage_per_mm * Math.max(0.0F, residual));
        if (amount > 0.0F) {
            occupant.hurt(source, amount);
        }
    }

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
                direction.x * (normal.x / length)
                        + direction.y * (normal.y / length)
                        + direction.z * (normal.z / length));
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
        Holder<MobEffect> blindness = BuiltInRegistries.MOB_EFFECT
                .getHolder(ResourceLocation.parse("blindness")).orElse(null);
        if (blindness == null) {
            return;
        }
        MobEffectInstance flash = new MobEffectInstance(blindness, round.spall_blindness_ticks);
        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof LivingEntity living) {
                living.addEffect(flash);
            }
        }
        // The driver normally rides as a passenger, but the seat is worth covering explicitly
        // rather than depending on that: re-applying an effect the crew already has is harmless.
        // getDriver() already returns a LivingEntity, so there is no pattern to match here.
        LivingEntity driver = vehicle.getDriver();
        if (driver != null) {
            driver.addEffect(flash);
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
        Vec3 soundOffset = vehicle.relativeRotPos(hitPos, true).subtract(vehicle.position());
        vehicle.playVehicleSound(hitSound(vehicle, damage), soundOffset, 1.0F, 4.0F, 1.0F, 0, false, false, true);

        if (shooter == null) {
            return;
        }
        Component message = Component.translatable(
                penetrated ? "message.ywzj_vehicle_armor.penetrated" : "message.ywzj_vehicle_armor.ricochet");
        NeoForge.EVENT_BUS.post(new HitVehicleEvent(
                shooter.getUUID(),
                vehicle.getId(),
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
