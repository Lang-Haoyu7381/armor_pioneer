package com.ywzjvehicle.armor;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Vector3f;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * Vanilla Minecraft's own weapons, turned into an armour-penetration question.
 *
 * <p>Every hit in the vanilla game arrives at {@code AbstractVehicle.hurt}, which calls the base
 * mod's {@code DamageSystem.hurt} - the very method {@code DamageSystemMixin} already intercepts
 * for tank shells and TACZ bullets. A bow, a crossbow, a thrown trident, a blaze's fireball, a
 * shulker bullet, a thrown snowball and a mob's sword used to fall straight through that
 * interception into the base mod's armour-blind damage. This class is the missing branch: it
 * recognises those kinds of hit and gives the armour system a round and a ray to work with.
 *
 * <p><b>Identity is the damage type, not the entity class.</b> The types are
 * {@code player_attack}, {@code mob_attack}, {@code mob_attack_no_aggro}, {@code arrow},
 * {@code trident}, {@code fireball}, {@code unattributed_fireball}, {@code wither_skull},
 * {@code mob_projectile} and {@code thrown}, all in the {@code minecraft} namespace. Reading a
 * vanilla damage-type holder costs nothing and names no mod's class, so the same rule that keeps
 * TACZ optional applies here: there is no {@code Arrow.class} check that would tie the addon to a
 * particular implementation of a bow. {@code thrown} is deliberate: its vanilla damage is zero,
 * but the base mod turns anything below its threshold into 0.1, so snowballs and eggs would
 * otherwise be a way to chip a tank to death through every plate. It is rated at zero penetration,
 * which is a ricochet - not a fall-through.
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
            new ResourceLocation("minecraft", "player_attack");

    /** A mob doing the same. */
    private static final ResourceLocation MOB_ATTACK =
            new ResourceLocation("minecraft", "mob_attack");

    /** A mob whose attack does not make the target angry - a goat's ram, for instance. */
    private static final ResourceLocation MOB_ATTACK_NO_AGGRO =
            new ResourceLocation("minecraft", "mob_attack_no_aggro");

    /** An arrow, from a bow, a crossbow or a dispenser; spectral arrows carry the same type. */
    private static final ResourceLocation ARROW = new ResourceLocation("minecraft", "arrow");

    /** A thrown trident. The melee swing with one is {@link #PLAYER_ATTACK}. */
    private static final ResourceLocation TRIDENT = new ResourceLocation("minecraft", "trident");

    /** A fireball with a living owner: a blaze, a ghast, or a fire charge. */
    private static final ResourceLocation FIREBALL = new ResourceLocation("minecraft", "fireball");

    /** A fireball with no owner - the one a dispenser's fire charge produces. */
    private static final ResourceLocation UNATTRIBUTED_FIREBALL =
            new ResourceLocation("minecraft", "unattributed_fireball");

    /** A wither skull. */
    private static final ResourceLocation WITHER_SKULL =
            new ResourceLocation("minecraft", "wither_skull");

    /** A shulker bullet or a llama's spit. */
    private static final ResourceLocation MOB_PROJECTILE =
            new ResourceLocation("minecraft", "mob_projectile");

    /**
     * A snowball, an egg or an ender pearl. The vanilla damage is zero, but the base mod clamps
     * anything below its damage threshold to 0.1 - so without this type the cheapest projectile
     * in the game is a way to chip a tank to death through every plate.
     */
    private static final ResourceLocation THROWN = new ResourceLocation("minecraft", "thrown");

    /**
     * Every type taken over, in one place so the check tool can hold the class to the list rather
     * than to a comment. Order is irrelevant: the types are mutually exclusive.
     */
    private static final ResourceLocation[] TYPES = {
            PLAYER_ATTACK, MOB_ATTACK, MOB_ATTACK_NO_AGGRO, ARROW, TRIDENT,
            FIREBALL, UNATTRIBUTED_FIREBALL, WITHER_SKULL, MOB_PROJECTILE, THROWN
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
     * @return the round this hit is measured as; never null for a handled type.
     *
     * <p>A configured override is returned as-is rather than blended with the conversion, for the
     * same reason a TACZ cartridge is: the entry is the player's complete statement about that
     * weapon, and patching the conversion with half of it would make the rest silently apply.
     *
     * <p>The conversion itself is delegated to {@link #roundForAmount}, which returns a round
     * even when the penetration is zero. That is the whole point of handling {@code thrown}: a
     * zero-penetration ricochet cancels the base hit, while returning null would let the base mod
     * substitute its own 0.1 and hand a snowball a way past every plate.
     */
    public static ArmorConfig.Ammo roundFor(DamageSource source, float amount) {
        ArmorConfig.Ammo rated = ArmorConfig.vanillaWeapons().overrideFor(keyFor(source));
        return rated != null ? rated : roundForAmount(amount);
    }

    /**
     * The converted round for a hit worth {@code amount} damage, bypassing the override lookup.
     *
     * <p>Always non-null. Zero damage is a valid rating here - it means "nothing this weapon can
     * do gets through", and the hit resolves as a ricochet.
     *
     * <p>Public so the offline check can exercise the zero-damage case without a level, a damage
     * source or a vehicle.
     */
    public static ArmorConfig.Ammo roundForAmount(float amount) {
        ArmorConfig.VanillaWeapons settings = ArmorConfig.vanillaWeapons();
        ArmorConfig.Ammo round = new ArmorConfig.Ammo(penetrationFor(amount,
                settings.penetration_per_damage, settings.max_penetration));
        round.multiplier = settings.damage_multiplier;
        return round;
    }

    /**
     * The ray the hit is re-traced along.
     *
     * <p>A projectile supplies its own position and velocity, which is what the base mod's own
     * {@code DamageSystem} reads. A melee hit has to be given one, and that is
     * {@link #fromPoint} with the attacker's eye.
     *
     * @return {@code null} when there is nothing sensible to trace - no attacker, a zero-length
     *         ray, or a projectile that has already stopped.
     */
    public static Ray ray(DamageSource source, AbstractVehicle vehicle) {
        Entity direct = source.m_7640_();
        if (direct instanceof Projectile projectile) {
            Vec3 motion = projectile.m_20184_();
            return motion.m_82553_() < 1.0E-7 ? null : new Ray(projectile.m_20182_(), motion);
        }
        Entity attacker = source.m_7639_();
        return fromPoint(attacker == null ? null : attacker.m_146892_(), vehicle);
    }

    /**
     * A ray from an arbitrary point to the vehicle's hull centre, carried one block past it.
     *
     * <p>Two callers use this. A melee swing starts at the attacker's eye, because a sword has no
     * projectile and the swing itself is the only line the hit has. An explosion starts at the
     * blast's centre, because the blast has no travelling round either - the mixin that captures
     * the centre hands it here, and this is what turns it into the face the blast actually meets.
     *
     * <p>Aiming at the hull's own centre rather than at the nearest point on the bounding box
     * matters because the hull is a real OBB: a ray aimed at a point in empty space inside the box
     * would find nothing to hit, and the hit would fall back to the base mod's damage - the one
     * outcome this class exists to prevent. The one block of overshoot keeps a start that is right
     * against the hull from producing a ray that ends on the surface before it enters.
     *
     * @return {@code null} when the start is missing or already at the hull centre.
     */
    public static Ray fromPoint(Vec3 start, AbstractVehicle vehicle) {
        if (start == null || vehicle == null) {
            return null;
        }
        Vec3 aim = vehicle.m_20191_().m_82399_();
        if (vehicle.getMainCubeOBB() != null) {
            Vector3f centre = vehicle.getMainCubeOBB().obb().center();
            aim = new Vec3(centre.x, centre.y, centre.z);
        }
        Vec3 delta = aim.m_82546_(start);
        double distance = delta.m_82553_();
        if (distance < 1.0E-4) {
            return null;
        }
        return new Ray(start, delta.m_82541_().m_82490_(distance + 1.0D));
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
        if (source.m_7640_() instanceof Projectile) {
            ResourceLocation kind = kindOf(source);
            return kind == null ? null : kind.toString();
        }
        Entity attacker = source.m_7639_();
        if (!(attacker instanceof LivingEntity living)) {
            return null;
        }
        ItemStack held = living.m_21205_();
        if (held == null || held.m_41619_()) {
            return null;
        }
        ResourceLocation item = ForgeRegistries.ITEMS.getKey(held.m_41720_());
        return item == null ? null : item.toString();
    }

    /** @return which of {@link #TYPES} this source is, or {@code null} for anything else. */
    private static ResourceLocation kindOf(DamageSource source) {
        Holder<DamageType> type = source.m_269150_();
        for (ResourceLocation id : TYPES) {
            if (type.m_203373_(id)) {
                return id;
            }
        }
        return null;
    }

    /** A ray start and direction, as the armour system's re-trace wants them. */
    public record Ray(Vec3 start, Vec3 motion) {
    }
}
