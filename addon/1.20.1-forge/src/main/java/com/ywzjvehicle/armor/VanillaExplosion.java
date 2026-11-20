package com.ywzjvehicle.armor;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Vanilla Minecraft's explosions, turned into the same armour-penetration question as a round.
 *
 * <p>A blast is not a projectile. The vanilla {@code Explosion} calculates one damage number per
 * affected entity - already falling off with distance and exposure - and hands it to
 * {@code Entity.hurt}, which for a vehicle reaches {@code DamageSystem.hurt} without ever looking
 * at a plate. This class supplies the two pieces that path lacks: a round whose penetration is the
 * blast's equivalent, and an identity for the override table. The blast's own centre is captured by
 * {@code ExplosionMixin}; {@link ArmorSystem} turns it into the ray.
 *
 * <p><b>Which explosions.</b> The three vanilla damage types are
 * {@code minecraft:explosion}, {@code minecraft:player_explosion} and
 * {@code minecraft:bad_respawn_point}. Together they cover TNT, TNT minecarts, creepers (charged or
 * not), end crystals, beds, respawn anchors, ghast fireballs, charged wither skulls and the
 * Wither's spawn blast. The base mod's own {@code ywzj_vehicle.explosion} is deliberately not one
 * of them: a rated warhead's blast is dropped by the branch in {@code ArmorSystem}, and an unrated
 * one still falls through to the base mod. Aircraft are excluded by the caller for the same reason
 * the warhead branch excludes them - a near miss is what kills a fighter, and the skin model is
 * about penetration, not blast.
 *
 * <p><b>The conversion.</b> The raw blast damage becomes an equivalent penetration by
 * {@link #penetrationFor}, and the residual is measured against the plate like any other round.
 * With the shipped factor the order of events is easy to read: a TNT block (29 raw) is 14.5mm and
 * stops at any tank plate but opens an 8mm light-vehicle roof; a charged creeper or an end crystal
 * (43 raw) is 21.5mm and can breach a thin roof; a ghast fireball's blast (8 raw) is 4mm and
 * bounces off the lot.
 *
 * <p><b>Overrides</b> are keyed by what the blast came from: the direct entity's type id
 * ({@code minecraft:tnt}, {@code minecraft:creeper}, {@code minecraft:end_crystal},
 * {@code minecraft:fireball}, {@code minecraft:wither_skull}, ...), or - when there is no direct
 * entity, as with a bed or a respawn anchor - the damage type id
 * ({@code minecraft:bad_respawn_point}). An entry is a complete ammunition entry and wins outright,
 * exactly like a TACZ cartridge.
 */
public final class VanillaExplosion {

    /** An ordinary blast: TNT, a creeper, an end crystal, a ghast's fireball, a Wither's spawn. */
    private static final ResourceLocation EXPLOSION =
            new ResourceLocation("minecraft", "explosion");

    /** A blast whose damage is credited to a player - a bed, a respawn anchor. */
    private static final ResourceLocation PLAYER_EXPLOSION =
            new ResourceLocation("minecraft", "player_explosion");

    /** A bed or respawn anchor that went off as a bad respawn point. */
    private static final ResourceLocation BAD_RESPAWN_POINT =
            new ResourceLocation("minecraft", "bad_respawn_point");

    /** Every damage type taken over, exposed so the offline check can compare it exactly. */
    private static final ResourceLocation[] TYPES = {
            EXPLOSION, PLAYER_EXPLOSION, BAD_RESPAWN_POINT
    };

    private VanillaExplosion() {
    }

    /** The explosion damage types this class takes over, as a copy. */
    public static ResourceLocation[] damageTypes() {
        return TYPES.clone();
    }

    /**
     * @return whether this hit is one of the vanilla explosion types and the feature is on. The
     *         caller still has to check the vehicle, and to exclude aircraft.
     */
    public static boolean handles(DamageSource source) {
        if (source == null || !ArmorConfig.vanillaExplosions().active()) {
            return false;
        }
        return kindOf(source) != null;
    }

    /**
     * @return the blast this hit is measured as; never null for a handled type.
     *
     * <p>A configured override is returned as-is. The conversion is delegated to
     * {@link #roundForAmount}, which returns a round even for a zero-damage blast; a
     * zero-penetration hit is a ricochet, not a fall-through to the base mod's 0.1 chip.
     */
    public static ArmorConfig.Ammo roundFor(DamageSource source, float amount) {
        ArmorConfig.Ammo rated = ArmorConfig.vanillaExplosions().overrideFor(keyFor(source));
        return rated != null ? rated : roundForAmount(amount);
    }

    /**
     * The converted blast for a hit worth {@code amount} raw explosion damage, bypassing the
     * override lookup. Always non-null.
     *
     * <p>Public so the offline check can exercise the arithmetic without a level or a vehicle.
     */
    public static ArmorConfig.Ammo roundForAmount(float amount) {
        ArmorConfig.VanillaExplosions settings = ArmorConfig.vanillaExplosions();
        ArmorConfig.Ammo blast = new ArmorConfig.Ammo(penetrationFor(amount,
                settings.penetration_per_damage, settings.max_penetration));
        blast.multiplier = settings.damage_multiplier;
        return blast;
    }

    /**
     * Millimetres of equivalent penetration for a blast that carried {@code amount} raw damage.
     *
     * <p>Linear and capped. A zero or negative factor means zero - a blast that cannot even mark
     * the plate - rather than a negative penetration that would make the residual arithmetic
     * report a five-block-thick plate. The cap is what stops a modded explosion with a huge raw
     * damage from being handed a penetration to match.
     */
    public static float penetrationFor(float amount, float perDamage, float max) {
        if (!(amount > 0.0F) || !(perDamage > 0.0F) || !(max > 0.0F)) {
            return 0.0F;
        }
        return Math.min(max, amount * perDamage);
    }

    /**
     * The id an override for this blast would be filed under: the direct entity's type id when
     * there is one, otherwise the explosion damage type id. A bed or a respawn anchor has no
     * direct entity - the block is the explosion - so it can only be rated by its damage type.
     */
    private static String keyFor(DamageSource source) {
        Entity direct = source.m_7640_();
        if (direct != null) {
            ResourceLocation type = ForgeRegistries.ENTITY_TYPES.getKey(direct.m_6095_());
            if (type != null) {
                return type.toString();
            }
        }
        ResourceLocation kind = kindOf(source);
        return kind == null ? null : kind.toString();
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
}
