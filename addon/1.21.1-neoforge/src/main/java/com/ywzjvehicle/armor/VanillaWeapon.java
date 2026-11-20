package com.ywzjvehicle.armor;

import net.minecraft.core.registries.Registries;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.registries.BuiltInRegistries;
import org.joml.Vector3f;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * Vanilla Minecraft's own weapons, turned into an armour-penetration question.
 *
 * <p>Every hit in the vanilla game arrives at {@code AbstractVehicle.hurt}, which calls the base
 * mod's {@code DamageSystem.hurt} - the very method {@code DamageSystemMixin} already intercepts
 * for tank shells and TACZ bullets. A bow, a crossbow, a thrown trident and a mob's sword used to
 * fall straight through that interception into the base mod's armour-blind damage. This class is
 * the missing branch: it recognises those four kinds of hit and gives the armour system a round
 * and a ray to work with.
 *
 * <p><b>Identity is the damage type, not the entity class.</b> The four types are
 * {@code minecraft:player_attack}, {@code minecraft:mob_attack},
 * {@code minecraft:mob_attack_no_aggro}, {@code minecraft:arrow} and {@code minecraft:trident}.
 * Reading a vanilla damage-type holder costs nothing and names no mod's class, so the same rule
 * that keeps TACZ optional applies here: there is no {@code Arrow.class} check that would tie the
 * addon to a particular implementation of a bow.
 *
 * <p><b>The round.</b> Vanilla has no penetration concept, so the figure is derived from the
 * damage the hit actually carried - attack damage, Sharpness, Strength and a critical all
 * included - by {@link #penetrationFor}. An {@link ArmorConfig.VanillaWeapons#overrides} entry
 * wins outright and is a complete ammunition entry, exactly like a rated TACZ cartridge.
 *
 * <p><b>The ray.</b> A projectile knows its own position and velocity, so it uses them, and the
 * base mod's re-trace does the rest. A melee hit has neither: it is a sword swing at something the
 * player is touching. That ray is built from the attacker's eye to the vehicle's hull centre,
 * carried one block past it, and handed to the same re-trace. Aiming at the hull rather than at
 * the nearest point on the bounding box matters because the hull is a real OBB: a ray aimed at a
 * point in empty space inside the box would find nothing to hit, and the hit would fall back to
 * the base mod's damage - the one outcome this class exists to prevent.
 *
 * <p><b>Player melee is gated before this class runs.</b> The base mod's
 * {@code AbstractVehicle.isInvulnerableTo} refuses a {@code player_attack} entirely unless
 * {@code allowMeleeDamageVehicle} is on, and it ships off. This class does not override that
 * switch; with it off a sword never reaches here, and with it on a sword meets the armour table
 * like everything else. Mob attacks and projectiles are not gated by that setting.
 *
 * <p>Only vehicles this addon has an armour profile for are handled - the caller checks, as it
 * does for every other round - so an unconfigured vehicle keeps the base mod's handling exactly.
 */
public final class VanillaWeapon {

    /** A player swinging whatever is in their main hand. */
    private static final ResourceLocation PLAYER_ATTACK =
            ResourceLocation.fromNamespaceAndPath("minecraft", "player_attack");

    /** A mob doing the same. */
    private static final ResourceLocation MOB_ATTACK =
            ResourceLocation.fromNamespaceAndPath("minecraft", "mob_attack");

    /** A mob whose attack does not make the target angry - a goat's ram, for instance. */
    private static final ResourceLocation MOB_ATTACK_NO_AGGRO =
            ResourceLocation.fromNamespaceAndPath("minecraft", "mob_attack_no_aggro");

    /** An arrow, from a bow, a crossbow or a dispenser; spectral arrows carry the same type. */
    private static final ResourceLocation ARROW = ResourceLocation.fromNamespaceAndPath("minecraft", "arrow");

    /** A thrown trident. The melee swing with one is {@link #PLAYER_ATTACK}. */
    private static final ResourceLocation TRIDENT = ResourceLocation.fromNamespaceAndPath("minecraft", "trident");

    /**
     * Every type taken over, in one place so the check tool can hold the class to the list rather
     * than to a comment. Order is irrelevant: the types are mutually exclusive.
     */
    private static final ResourceLocation[] TYPES = {
            PLAYER_ATTACK, MOB_ATTACK, MOB_ATTACK_NO_AGGRO, ARROW, TRIDENT
    };

    private VanillaWeapon() {
    }

    /**
     * The damage types this class takes over, as a copy.
     *
     * <p>Exposed for the offline check, and exposed as the ids themselves rather than as a count:
     * a byte scan of the class cannot tell {@code arrow} from {@code arrow_typo}, because the typo
     * contains the right word. Comparing this array exactly is the only check that fails when one
     * of them is renamed - which is the failure mode that would otherwise be silent in play.
     */
    public static ResourceLocation[] damageTypes() {
        return TYPES.clone();
    }

    /**
     * @return whether this hit is one of the weapons in {@link #TYPES} and the feature is on.
     *         The caller still has to check that the vehicle has an armour profile.
     */
    public static boolean handles(DamageSource source) {
        if (source == null || !ArmorConfig.vanillaWeapons().active()) {
            return false;
        }
        return kindOf(source) != null;
    }

    /**
     * @return the round this hit is measured as, or {@code null} when it carries no penetration at
     *         all - a zero-damage hit, or a configuration that turned the conversion off.
     *
     * <p>A configured override is returned as-is rather than blended with the conversion, for the
     * same reason a TACZ cartridge is: the entry is the player's complete statement about that
     * weapon, and patching the conversion with half of it would make the rest silently apply.
     */
    public static ArmorConfig.Ammo roundFor(DamageSource source, float amount) {
        ArmorConfig.VanillaWeapons settings = ArmorConfig.vanillaWeapons();
        ArmorConfig.Ammo rated = settings.overrideFor(keyFor(source));
        if (rated != null) {
            return rated;
        }
        float penetration = penetrationFor(amount, settings.penetration_per_damage,
                settings.max_penetration);
        if (penetration <= 0.0F) {
            return null;
        }
        ArmorConfig.Ammo round = new ArmorConfig.Ammo(penetration);
        round.multiplier = settings.damage_multiplier;
        return round;
    }

    /**
     * The ray the hit is re-traced along.
     *
     * <p>A projectile supplies its own position and velocity, which is what the base mod's own
     * {@code DamageSystem} reads. A melee hit has to be given one: the attacker's eye, towards the
     * hull's centre, carried past it. The one block of overshoot is what keeps a player standing
     * right against the tank from producing a ray that ends on the surface before it enters.
     *
     * @return {@code null} when there is nothing sensible to trace - no attacker, a zero-length
     *         ray, or a projectile that has already stopped.
     */
    public static Ray ray(DamageSource source, AbstractVehicle vehicle) {
        Entity direct = source.getDirectEntity();
        if (direct instanceof Projectile projectile) {
            Vec3 motion = projectile.getDeltaMovement();
            return motion.length() < 1.0E-7 ? null : new Ray(projectile.position(), motion);
        }
        Entity attacker = source.getEntity();
        if (attacker == null || vehicle == null) {
            return null;
        }
        Vec3 start = attacker.getEyePosition();
        Vec3 aim = vehicle.getBoundingBox().getCenter();
        if (vehicle.getMainCubeOBB() != null) {
            Vector3f centre = vehicle.getMainCubeOBB().obb().center();
            aim = new Vec3(centre.x, centre.y, centre.z);
        }
        Vec3 delta = aim.subtract(start);
        double distance = delta.length();
        if (distance < 1.0E-4) {
            return null;
        }
        return new Ray(start, delta.normalize().scale(distance + 1.0D));
    }

    /**
     * Millimetres of penetration for a hit worth {@code amount} damage.
     *
     * <p>Linear and capped, and both clamps are load-bearing. A zero or negative factor means zero,
     * not a negative penetration that would make the residual arithmetic report a five-block-thick
     * plate. The cap is what stops a modded weapon with four-digit attack damage from being handed
     * a four-digit penetration; without it the conversion would rate anything the item says.
     *
     * <p>Public so the offline check can exercise the arithmetic without a level, a damage source
     * or a vehicle.
     */
    public static float penetrationFor(float amount, float perDamage, float max) {
        if (!(amount > 0.0F) || !(perDamage > 0.0F) || !(max > 0.0F)) {
            return 0.0F;
        }
        return Math.min(max, amount * perDamage);
    }

    /**
     * The id an override for this hit would be filed under: the damage type for a projectile, the
     * held item for a melee hit. Both are the same string for the two projectiles vanilla ships -
     * {@code minecraft:arrow} and {@code minecraft:trident} are item ids too - which is why one map
     * can serve both without a prefix.
     */
    private static String keyFor(DamageSource source) {
        if (source.getDirectEntity() instanceof Projectile) {
            ResourceLocation kind = kindOf(source);
            return kind == null ? null : kind.toString();
        }
        Entity attacker = source.getEntity();
        if (!(attacker instanceof LivingEntity living)) {
            return null;
        }
        ItemStack held = living.getMainHandItem();
        if (held == null || held.isEmpty()) {
            return null;
        }
        ResourceLocation item = BuiltInRegistries.ITEM.getKey(held.getItem());
        return item == null ? null : item.toString();
    }

    /** @return which of {@link #TYPES} this source is, or {@code null} for anything else. */
    private static ResourceLocation kindOf(DamageSource source) {
        Holder<DamageType> type = source.typeHolder();
        for (ResourceLocation id : TYPES) {
            if (type.is(id)) {
                return id;
            }
        }
        return null;
    }

    /** A ray start and direction, as the armour system's re-trace wants them. */
    public record Ray(Vec3 start, Vec3 motion) {
    }
}
