package com.ywzjvehicle.armor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Armour profiles, ammunition penetration table and per-face damage multipliers.
 *
 * <p>Everything is stored in {@code config/ywzj_vehicle_armor.json}, which is created with the
 * documented defaults on first launch and can be edited freely afterwards.
 *
 * <p>Thickness is in millimetres, matching the values used by Squad / War Thunder.
 */
public final class ArmorConfig {

    private ArmorConfig() {
    }

    /** Six-face thickness of one armour block, in mm. */
    public static final class ArmorSet {
        public float front = 90.0F;
        public float side = 90.0F;
        public float rear = 90.0F;
        public float roof = 90.0F;
        public float floor = 90.0F;

        public ArmorSet() {
        }

        public ArmorSet(float front, float side, float rear, float roof, float floor) {
            this.front = front;
            this.side = side;
            this.rear = rear;
            this.roof = roof;
            this.floor = floor;
        }
    }

    /**
     * How far each vertical face of a box actually leans, in degrees from vertical.
     *
     * <p>A structure hitbox is a plain cuboid - the Panther's glacis is drawn as the hull box's
     * front face, which stands straight up - so the slope the real plate carries is not in the
     * geometry at all. It is stated here instead, and the damage path turns it into the
     * line-of-sight thickness a round arriving from a given direction actually has to cross.
     *
     * <p>The angle is measured the way armour is usually quoted: 0 for a vertical plate, 55 for
     * the Panther's glacis, and the plate is assumed to lean back so that its outward normal tilts
     * towards the roof (+Y of the struck box). A shot from the front therefore meets
     * {@code thickness / cos(slope)}, and one from 45 degrees off the nose meets
     * {@code thickness / cos(angle to that tilted normal)} - which is the 139mm and 197mm a
     * reference gives for 80mm at 55 degrees.
     *
     * <p>Null rather than all zeroes means "no opinion": the writer then emits nothing, and every
     * vehicle the addon has no measured slope for keeps the plain box behaviour it had before.
     */
    public static final class PlateSlopes {
        /** Upper and lower front plates, from vertical. */
        public float front = 0.0F;
        /** Left and right flanks, from vertical - the top leaning in. */
        public float side = 0.0F;
        /** The rear plate, from vertical - the top leaning forward. */
        public float rear = 0.0F;

        public PlateSlopes() {
        }

        public PlateSlopes(float front, float side, float rear) {
            this.front = front;
            this.side = side;
            this.rear = rear;
        }

        /**
         * @return the inclination of the plate the face code names, or zero for a roof or floor.
         *         Face codes are the ones {@code OBB.embeddingFace} returns: +-1 sides, +-2
         *         roof/floor, +-3 front/rear.
         */
        public float forFace(int face) {
            switch (Math.abs(face)) {
                case 1:
                    return side;
                case 3:
                    return face > 0 ? front : rear;
                default:
                    return 0.0F;
            }
        }

        /** @return whether any face leans at all. */
        public boolean any() {
            return front > 0.0F || side > 0.0F || rear > 0.0F;
        }
    }

    /**
     * Damage per millimetre of residual penetration, per armour face.
     *
     * <p>A thin plate and a thick plate are not equally lethal once pierced: a round that
     * barely gets through the upper front plate has little energy left, while one that
     * punches through a side skirt keeps most of it. Hence the much larger front value.
     */
    public static final class DamageMultiplier {
        public float front = 50.0F;
        public float side = 5.0F;
        public float rear = 5.0F;
        public float roof = 5.0F;
        public float floor = 5.0F;

        public DamageMultiplier() {
        }

        public DamageMultiplier(float front, float side, float rear, float roof, float floor) {
            this.front = front;
            this.side = side;
            this.rear = rear;
            this.roof = roof;
            this.floor = floor;
        }
    }

    /** One ammunition type: what it can pierce, and optionally a flat damage multiplier. */
    public static final class Ammo {
        /** Penetration in mm. */
        public float penetration = 0.0F;

        /**
         * Penetration against range, in metres from the muzzle, or {@code null} for a round whose
         * penetration does not change with distance.
         *
         * <p>This is the shape a ballistic table has: a hard projectile loses velocity - and with it
         * the thickness it can defeat - as it flies, and the loss is dramatic for a 1940s APCBC
         * shell and slight for a modern long rod. Each point is absolute millimetres, so a curve
         * anchored at the muzzle should start at {@link #penetration}; {@link #penetrationAt}
         * interpolates between points and holds the ends.
         *
         * <p>Deliberately per round and not a global rule: only rounds that state a curve get one,
         * which is how "kinetic only" is expressed without the damage system having to guess what
         * kind of projectile it is holding. A shaped charge's jet does not lose penetration the
         * same way and should state no curve at all.
         */
        public List<RangePoint> range_penetration;

        /** One point on {@link #range_penetration}. */
        public static final class RangePoint {
            /** Metres from the muzzle, which the game measures in blocks. */
            public float range;
            /** Penetration the round still has at that range, in millimetres. */
            public float penetration;

            public RangePoint() {
            }

            public RangePoint(float range, float penetration) {
                this.range = range;
                this.penetration = penetration;
            }
        }

        /**
         * Flat damage per mm of residual penetration. When absent the struck plate's own
         * multiplier is used - which is what the tank gun wants, since its lethality depends
         * on which plate it went through. Machine guns set this so that they behave the same
         * wherever they land.
         */
        public Float multiplier;

        /**
         * Name of the armour set this round is matched against, looked up in the vehicle's
         * {@code overrides}. A kinetic round is stopped by a sloped plate that a chemical round
         * barely notices, so rounds that work differently need different numbers for the same
         * tank; leaving this out means the vehicle's own values are used as-is.
         */
        public String profile;

        /**
         * Damage a penetrating hit deals, flat, instead of the usual residual-times-multiplier
         * formula. A high-explosive shell does not care how much of it got through - the charge
         * that ends up inside the tank is the same either way - so its lethality is a single
         * number rather than something proportional to the residual.
         *
         * <p>Absent means the residual formula is used.
         */
        public Float penetration_damage;

        /**
         * Extra damage rolled on top of a penetrating hit, whose upper end grows with the plate it
         * went through.
         *
         * <p>Added to whatever the hit was already worth - the residual formula, or
         * {@link #penetration_damage} where the round states one - not substituted for it. The roll
         * runs from {@link #penetration_bonus_min} to a ceiling that climbs from that same floor up
         * to {@link #penetration_bonus_max} as the struck plate thickens, reaching it at
         * {@link #penetration_bonus_max_at} millimetres. A round through a thin flank therefore
         * rolls near the floor; the same round through a thick glacis has the whole band to play
         * with.
         *
         * <p>This is what a shell with a bursting charge behind it does: the hole it punches is
         * worth what any kinetic round's hole is worth, and on top of that the plate it broke
         * becomes fragments, more of them the thicker the plate was. The penetration test is
         * untouched - the round still has to get through before any of this happens.
         *
         * <p>Setting {@link #penetration_bonus_max} is what turns it on; the other two have defaults
         * that make a lone max mean "roll from zero up to max, reached at 200mm".
         */
        public Float penetration_bonus_min;
        public Float penetration_bonus_max;
        public Float penetration_bonus_max_at;

        /**
         * Lowest and highest spall damage a hit scatters, in health points. This is the blast of a
         * chemical round rather than its jet. Both zero disables it.
         *
         * <p>How the two are read depends on {@link #spall_falloff_to}: without it the blast is a
         * flat band rolled at random, with it the damage slides from {@code max} down to
         * {@code min} as the plate gets thicker.
         */
        public float spall_damage_min = 0.0F;
        public float spall_damage_max = 0.0F;

        /**
         * Thickness at which the spall damage has fallen all the way to {@link #spall_damage_min}.
         * It starts falling once the plate is thicker than the round's own penetration, so the
         * blast is at full strength right up to the point where the round would have gone through.
         *
         * <p>Zero leaves the blast a flat random band instead.
         */
        public float spall_falloff_to = 0.0F;

        /**
         * Whether the blast also lands when the round penetrated. True by default, which is what a
         * shaped charge does - it scatters spall whether or not the jet got through. A shell whose
         * full effect is already covered by {@link #penetration_damage} sets this to false so the
         * penetrating and non-penetrating cases stay distinct.
         */
        public Boolean spall_on_penetration;

        /** Ticks of blindness the blast inflicts on the crew. Zero disables it. */
        public int spall_blindness_ticks = 0;

        /**
         * When true, this round's own blast numbers ({@link #spall_damage_min} and friends) are
         * the whole story: the base mod's separate explosion, which a warhead-carrying round fires
         * on every entity it strikes, is dropped against vehicles this addon has a profile for.
         *
         * <p>That explosion is authored as raw damage in the vehicle pack (the T-64's HEAT round
         * declares 1500 at radius 4) and knows nothing about armour, so leaving it in place makes
         * every armoured hit land for a thousand-odd points no matter what the plate says. The
         * blast is still fired - it still breaks blocks and still reaches everything else in
         * radius - only the damage to this vehicle is dropped.
         *
         * <p>Nullable so a file written before this key existed can be told apart from one where
         * the user deliberately turned it back off.
         */
        public Boolean replaces_base_explosion;

        /**
         * Marks a round whose fuze the player can switch in flight. Such a round carries a second
         * damage model in {@link #proximity} and obeys the fuze the gunner has selected rather than
         * its own single set of numbers.
         *
         * <p>Nullable, and only written when true, so every other round in the file stays a plain
         * entry and cannot accidentally be given a fuze it has no way to switch.
         */
        public Boolean multi_purpose;

        /**
         * The damage model in force while this round's fuze is set to proximity or delay.
         *
         * <p>Only meaningful on a {@link #multi_purpose} round. It is a complete entry of its own -
         * profile, penetration, blast, everything - rather than a set of overrides, because the two
         * models differ in every one of those numbers and a partial second model would silently
         * inherit the impact one's values.
         */
        public Ammo proximity;

        /** Radius in blocks the proximity fuze looks for targets in. Absent means three. */
        public Float proximity_radius;

        /**
         * How far above the target's highest point a top-attack round goes off, in blocks. Null on
         * every ordinary round, and that is what keeps this a per-weapon choice rather than a
         * property of all missiles.
         *
         * <p>A top-attack missile flies the base mod's PRESET profile - ascent, cruise, dive - and
         * this addon detonates it above the roof instead of letting it fly into the target. The
         * warhead is then resolved as a synthetic shot straight down, so it meets the thin roof
         * rather than whatever face the launcher happened to be looking at.
         */
        public Float top_attack_standoff;

        /** How far to either side of the target's outline the top-attack fuze looks, in blocks. */
        public Float top_attack_radius;

        /**
         * Lowest height above the roof at which the warhead may go off. Below this the missile is
         * inside the vehicle's own outline and the ordinary impact path is the honest answer.
         */
        public Float top_attack_min_height;

        /**
         * The certified mass of the flying projectile, in kilograms, and its muzzle velocity in
         * metres per second. Both absent - on every round that has not opted in - keeps the
         * residual-times-multiplier damage model, so this is a per-round choice and not a global
         * change to every gun.
         *
         * <p>A round that states them is measured by the debris model in {@link SpallModel}: the
         * certified penetration is anchored at this pair, the in-flight velocity comes from the
         * round's own range curve, and the damage comes from the plate fragments the round throws
         * into the compartment rather than from a fixed per-face multiplier.
         */
        public Float mass_kg;
        public Float muzzle_velocity;

        /**
         * The exponents of the penetration law, {@code P = P0 (m/m0)^a (v/v0)^b}. Absent uses the
         * long-rod pair - 0.70 for mass and 0.50 for velocity - which is also what the two tanks
         * this shipped for use.
         */
        public Float mass_exponent;
        public Float velocity_exponent;

        /**
         * The flying projectile's diameter in millimetres, when the entity path cannot supply one.
         * The hole a round makes in a plate is what turns plate thickness into fragment mass, so
         * the debris model needs it; a cannon shell already carries its caliber on the entity, and
         * this field is the fallback for the offline tools and for a round fired without one.
         */
        public Float caliber_mm;

        /**
         * A shaped charge's rated behind-armour damage, at the point where the whole jet reaches
         * the compartment. Absent - the default - leaves the round on the old residual-times-face
         * rule. Present requires {@code profile: "heat"}.
         *
         * <p>Damage falls as the fraction of the jet that survives the plate, {@code (r/P)^0.65},
         * rather than linearly with the residual: a jet that only just got through does very
         * little, and one with most of its length through is devastating.
         */
        public Float heat_damage;

        /**
         * The explosive charge of a high-explosive shell, in kilograms of TNT equivalent. Absent
         * leaves the round on its old flat {@code penetration_damage}. Present requires
         * {@code profile: "he"}.
         *
         * <p>A shell that gets inside bursts with the whole charge; one that is held outside scabs
         * the plate's inner face with the part of the blast the plate does not confine. Both are
         * fragments, and the plate's thickness decides how much material becomes one.
         *
         * <p>On a kinetic round the same field is the armour-piercing shell's base charge: the
         * debris model still makes the hole, and the charge bursts inside it. See
         * {@link #usesApheModel()}.
         */
        public Float charge_kg;

        /**
         * A lateral-effect round (PELE): an inert-filled penetrator whose body ruptures and sprays
         * fragments sideways once the nose has punched through a plate. Absent - the default -
         * leaves the round on the ordinary penetrating rule.
         *
         * <p>Present requires {@code mass_kg}, {@code muzzle_velocity} and {@code caliber_mm}: the
         * effect is the perforation energy the plate extracts from the projectile, so the model
         * needs to know what is flying and how fast. This is what {@code penetration_bonus_*} used
         * to stand in for on one round; the bonus band is the crude shape and this is the physics.
         */
        public Boolean pele;

        /**
         * A tandem warhead: a small precursor charge ahead of the main one, whose entire job is to
         * deal with the reactive armour the main charge would otherwise be wasted on.
         *
         * <p>In play it means the round is not stopped by an outer layer - a reactive block, an
         * add-on plate, a slat screen - and is measured instead against the armour behind it, paying
         * only what that layer takes off it. Against anything else it behaves exactly like an
         * ordinary shaped charge, so this is a property of the round and not of the ammunition type.
         *
         * <p>Nullable, and only written when true, so every other round stays a plain entry.
         */
        public Boolean tandem;

        /**
         * Which shelf this round sits on in the ammunition compendium, by {@link AmmoCategory#id}.
         *
         * <p>Absent - or naming a category that does not exist - falls back to
         * {@link AmmoCategory#derivedFrom}, which gets the warhead right and assumes a cannon.
         */
        public String category;

        /**
         * The other rounds this slot can be loaded with, in the order the chooser lists them.
         *
         * <p>A slot is one weapon that fires one kind of shell, and the base mod has no way to give
         * it a second - the vehicle's weapon list decides how many slots there are, and the slot's
         * weapon file decides what it fires. So the choice is made here instead: the gunner picks a
         * load, and the armour system measures the shot with that load's penetration.
         *
         * <p>These are the <em>alternatives</em>, not the whole set - the round the vehicle pack
         * names is what the slot fires when nothing has been chosen, and it comes first on the
         * list. {@link #variantCount()} counts it; {@link #variant} indexes from it.
         */
        public List<Ammo> variants;

        /**
         * The round's own designation, for the entries that have no weapon file to be named by -
         * which is to say the variants. Everything the pack ships is named by the pack.
         */
        public String name;

        /** @return how many loads this slot has, the pack's own round included. Never zero. */
        public int variantCount() {
            return 1 + (variants == null ? 0 : variants.size());
        }

        /**
         * The load the gunner's choice refers to.
         *
         * @param index {@code 0} for the round as the pack defines it, {@code 1..n} for the
         *              variants in order. Out-of-range values are clamped rather than refused: the
         *              index arrives from a client and from a config that may have been edited, and
         *              a shot measured against the nearest load is better than one measured against
         *              nothing.
         */
        public Ammo variant(int index) {
            if (variants == null || variants.isEmpty() || index <= 0) {
                return this;
            }
            return variants.get(Math.min(index, variants.size()) - 1);
        }

        /** @return whether this slot can fire anything other than the round the pack named it for. */
        public boolean selectable() {
            return variants != null && !variants.isEmpty();
        }

        /** @return whether this round has a penetration-against-range curve worth reading. */
        public boolean hasRangeFalloff() {
            return range_penetration != null && range_penetration.size() >= 2;
        }

        /**
         * @return what this round can penetrate after flying {@code metres}, or its flat
         *         {@link #penetration} when it states no curve.
         *
         * <p>Linear between points, held flat before the first and after the last. The list is
         * read defensively - a config file is hand-editable, and points may be unsorted or one
         * may be malformed - so the answer is the bracketing pair wherever the list puts them,
         * not the first pair that happens to be adjacent.
         */
        public float penetrationAt(float metres) {
            List<RangePoint> points = range_penetration;
            if (points == null || points.size() < 2) {
                return penetration;
            }
            RangePoint first = null;
            RangePoint last = null;
            RangePoint lower = null;
            RangePoint upper = null;
            for (RangePoint point : points) {
                if (point == null || !Float.isFinite(point.range)
                        || !Float.isFinite(point.penetration)) {
                    continue;
                }
                if (first == null || point.range < first.range) {
                    first = point;
                }
                if (last == null || point.range > last.range) {
                    last = point;
                }
                if (point.range <= metres && (lower == null || point.range > lower.range)) {
                    lower = point;
                }
                if (point.range >= metres && (upper == null || point.range < upper.range)) {
                    upper = point;
                }
            }
            if (first == null) {
                return penetration;
            }
            if (metres <= first.range) {
                return first.penetration;
            }
            if (upper == null) {
                return last.penetration;
            }
            if (lower == null) {
                return upper.penetration;
            }
            float span = upper.range - lower.range;
            if (span <= 0.0F) {
                return upper.penetration;
            }
            float share = (metres - lower.range) / span;
            return lower.penetration + (upper.penetration - lower.penetration) * share;
        }

        /**
         * Gives the round a range curve anchored at its own {@link #penetration}.
         *
         * <p>Pairs of {@code range, share}: at {@code range} metres the round keeps {@code share}
         * of whatever its muzzle penetration is. Anchoring on the figure the round already states
         * is what keeps a hand-tuned penetration and its curve from disagreeing, which is the
         * failure a curve written as absolute numbers would have the moment a player edited one.
         *
         * @param rangeSharePairs {@code range0, share0, range1, share1, ...}; ignored unless at
         *                        least two points are given
         */
        public Ammo falloffFromPenetration(float... rangeSharePairs) {
            if (rangeSharePairs == null || rangeSharePairs.length < 4
                    || rangeSharePairs.length % 2 != 0) {
                return this;
            }
            List<RangePoint> points = new java.util.ArrayList<>();
            for (int i = 0; i + 1 < rangeSharePairs.length; i += 2) {
                points.add(new RangePoint(rangeSharePairs[i],
                        penetration * rangeSharePairs[i + 1]));
            }
            range_penetration = points;
            return this;
        }

        /** @return the designations of every load, the pack's own round included where known. */
        public List<String> variantNames(String ownName) {
            List<String> names = new java.util.ArrayList<>();
            names.add(ownName);
            if (variants != null) {
                for (Ammo variant : variants) {
                    names.add(variant.name == null ? "?" : variant.name);
                }
            }
            return names;
        }

        /** @return the shelf to file this round under; never null. */
        public AmmoCategory shelf() {
            AmmoCategory named = AmmoCategory.byId(category);
            return named != null ? named : AmmoCategory.derivedFrom(profile);
        }

        /** @return whether this round rolls extra damage on a penetrating hit. */
        public boolean hasPenetrationBonus() {
            return penetration_bonus_max != null;
        }

        /**
         * Rolls the extra damage of a penetrating hit, given the plate the round went through.
         *
         * <p>The band's floor is {@link #penetration_bonus_min}; its ceiling climbs from that floor
         * up to {@link #penetration_bonus_max} as the plate approaches {@link #penetration_bonus_max_at}
         * millimetres, so a thin plate leaves the roll with nowhere to go and a thick one opens it
         * all the way up.
         *
         * <p>The clamps are the whole of the safety here, because none of the three numbers can be
         * trusted to be sensible: they come from a config file a person edits. A negative span would
         * otherwise roll damage off the bottom, and a zero thickness would divide by it.
         */
        public float rollPenetrationBonus(float thickness) {
            float floor = penetrationBonusFloor();
            return floor + ThreadLocalRandom.current().nextFloat()
                    * (penetrationBonusCeiling(thickness) - floor);
        }

        /** @return the bottom of the roll, which does not depend on what was hit. */
        public float penetrationBonusFloor() {
            return penetration_bonus_min == null ? 0.0F : penetration_bonus_min;
        }

        /**
         * @return the top of the roll against a plate of the given thickness - where the random draw
         *         is aiming. Exposed beside {@link #rollPenetrationBonus} so a tool can print the
         *         band a hit will land in rather than one arbitrary draw from it.
         */
        public float penetrationBonusCeiling(float thickness) {
            float floor = penetrationBonusFloor();
            float ceiling = penetration_bonus_max == null ? floor : penetration_bonus_max;
            if (ceiling < floor) {
                ceiling = floor;
            }
            float full = penetration_bonus_max_at == null ? 200.0F : penetration_bonus_max_at;
            float share = full <= 0.0F ? 1.0F : Math.max(0.0F, Math.min(1.0F, thickness / full));
            return floor + (ceiling - floor) * share;
        }

        /**
         * The damage of a hit that got through.
         *
         * <p>The one place this is decided, called by the damage system and by the table tool. It
         * used to be two copies of an if-else - one inline where the damage is applied, one in the
         * tool - which agreed only until a third kind of round existed, at which point the tool
         * went on printing the second kind's formula for it and no one could tell from the numbers.
         *
         * <p>Two decisions, in order: what the hole itself is worth, then what the plate thrown off
         * adds to it. Keeping them apart is what lets a round have a bursting charge behind an
         * ordinary kinetic penetration, which is exactly what an APHE shell is.
         *
         * @param thickness the plate that was struck, which only the bonus cares about
         */
        public float penetrationDamage(float residual, float multiplier, float thickness) {
            float damage = penetration_damage != null ? penetration_damage : residual * multiplier;
            return hasPenetrationBonus() ? damage + rollPenetrationBonus(thickness) : damage;
        }

        /** @return whether this round drops the base mod's explosion damage on armoured vehicles. */
        public boolean replacesBaseExplosion() {
            return replaces_base_explosion != null && replaces_base_explosion;
        }

        /** @return whether this round is fired from above rather than at the face aimed at. */
        public boolean isTopAttack() {
            return top_attack_standoff != null && top_attack_standoff > 0.0F;
        }

        /** @return the height above the roof to go off at; only meaningful for a top attack. */
        public float topAttackStandoff() {
            return top_attack_standoff == null ? 0.0F : top_attack_standoff;
        }

        /** @return how far to either side of the vehicle outline the fuze looks, in blocks. */
        public float topAttackRadius() {
            return top_attack_radius == null ? 3.0F : Math.max(0.5F, top_attack_radius);
        }

        /** @return the lowest height above the roof at which the warhead may go off. */
        public float topAttackMinHeight() {
            return top_attack_min_height == null ? 0.25F
                    : Math.max(0.0F, top_attack_min_height);
        }

        /** @return whether this round is measured by the debris model rather than residual times a multiplier. */
        public boolean usesKineticModel() {
            return mass_kg != null && mass_kg > 0.0F
                    && muzzle_velocity != null && muzzle_velocity > 0.0F;
        }

        /** @return the projectile mass in kilograms; only meaningful when the model is in use. */
        public float massKg() {
            return mass_kg == null ? 0.0F : mass_kg;
        }

        /** @return the certified muzzle velocity in metres per second. */
        public float muzzleVelocity() {
            return muzzle_velocity == null ? 0.0F : muzzle_velocity;
        }

        /** @return the mass exponent of the penetration law. */
        public float massExponent() {
            return mass_exponent == null ? 0.70F : Math.max(0.0F, mass_exponent);
        }

        /** @return the velocity exponent of the penetration law. */
        public float velocityExponent() {
            return velocity_exponent == null ? 0.50F : Math.max(0.01F, velocity_exponent);
        }

        /** @return the projectile diameter in millimetres, or zero when none is stated. */
        public float caliberMm() {
            return caliber_mm == null ? 0.0F : Math.max(0.0F, caliber_mm);
        }

        /** @return whether this shaped charge is measured by the jet-residual model. */
        public boolean usesHeatModel() {
            return "heat".equals(profile) && heat_damage != null && heat_damage > 0.0F;
        }

        /** @return whether this high-explosive shell is measured by the blast/scab model. */
        public boolean usesHeModel() {
            return "he".equals(profile) && charge_kg != null && charge_kg > 0.0F;
        }

        /** @return whether this round's lateral effect is measured instead of rolled. */
        public boolean usesPeleModel() {
            return pele != null && pele && usesKineticModel() && caliberMm() > 0.0F;
        }

        /**
         * @return whether this round is an armour-piercing shell with a bursting charge - a round
         *         measured by the debris model that also carries a base fuze and a small charge.
         *         Its damage is the hole plus the burst, and the burst only happens if the round
         *         got in; a shell that failed to penetrate never lights its base fuze.
         */
        public boolean usesApheModel() {
            return usesKineticModel() && !"he".equals(profile) && !"heat".equals(profile)
                    && charge_kg != null && charge_kg > 0.0F;
        }

        /** @return whether the player can switch this round's fuze. */
        public boolean multiPurpose() {
            return multi_purpose != null && multi_purpose;
        }

        /** @return whether this round carries a precursor charge that deals with outer layers. */
        public boolean tandem() {
            return tandem != null && tandem;
        }

        /** @return the radius the proximity fuze sweeps, in blocks. */
        public float proximityRadius() {
            return proximity_radius == null ? 3.0F : proximity_radius;
        }

        /**
         * The entry whose numbers apply for a given fuze.
         *
         * <p>An impact fuze is this round itself. Proximity and delay both use {@link #proximity} -
         * the two differ in how they are triggered, not in what goes off when they are, so they share
         * one model on purpose. A round with no proximity model falls back to itself whatever the
         * fuze says, which is what keeps every ordinary shell in the file unaffected.
         *
         * @return the model to measure armour against; never null.
         */
        public Ammo damageModel(Fuze fuze) {
            if (fuze == null || fuze == Fuze.IMPACT || proximity == null) {
                return this;
            }
            return proximity;
        }

        public Ammo() {
        }

        public Ammo(float penetration) {
            this.penetration = penetration;
        }

        public Ammo(float penetration, float multiplier) {
            this.penetration = penetration;
            this.multiplier = multiplier;
        }

        /** @return whether this round scatters spall damage on impact. */
        public boolean hasSpall() {
            return spall_damage_max > 0.0F;
        }

        /** @return whether the blast also lands when the round got through. Absent means yes. */
        public boolean spallOnPenetration() {
            return spall_on_penetration == null || spall_on_penetration;
        }

        /** @return whether spall damage falls off with plate thickness rather than being a flat band. */
        public boolean spallFallsOff() {
            return spall_falloff_to > penetration && spall_damage_max > spall_damage_min;
        }
    }

    /**
     * The ratings vanilla Minecraft's own weapons meet the armour table with.
     *
     * <p>Until this block existed, a bow, a crossbow, a thrown trident and a mob's sword were the
     * only ways left to damage one of these vehicles while ignoring every plate in the config: the
     * base mod's {@code DamageSystem} knows nothing about armour, so an arrow that the pack's own
     * model would bounce off simply took health off the tank. Those hits now come through
     * {@link VanillaWeapon} and end up in the same residual-penetration arithmetic as a tank shell.
     *
     * <p>What is covered is exactly the four kinds of hit that carry a weapon:
     * {@code minecraft:player_attack}, {@code minecraft:mob_attack} and
     * {@code minecraft:mob_attack_no_aggro} (a sword, an axe, a pickaxe, bare hands), and
     * {@code minecraft:arrow} / {@code minecraft:trident} (a bow, a crossbow, a thrown trident).
     * Explosions, fire, potions and snowballs are deliberately not covered - they are not a weapon
     * meeting a plate, and the base mod's own handling of them is left alone.
     *
     * <p>Player melee is still gated by the base mod's {@code allowMeleeDamageVehicle} setting,
     * which ships {@code false}. This block does not override that switch: with it off a sword
     * never reaches the armour table, exactly as before. With it on, a sword is measured like
     * everything else.
     *
     * <p>The figures are a conversion rather than a table because vanilla has no penetration
     * concept at all: what a hit is worth depends on the damage it actually carried, which already
     * includes the item's own attack damage, Sharpness, Strength and a critical hit. A diamond
     * sword at seven damage therefore penetrates 2.8mm at the shipped 0.4, an arrow from a fully
     * drawn bow about 2.4mm, and a netherite axe four - all below the 8mm roof of the lightest
     * vehicle in this config. That is the point: these are not anti-tank weapons, and against a
     * tank they ricochet and do nothing. A Power V critical arrow can just reach that 8mm roof,
     * and that is the strongest thing vanilla can do here.
     *
     * <p>{@link #overrides} is the escape hatch for anything the conversion gets wrong - a modded
     * sword with an absurd attack damage, or a pack whose arrows should count for more. An entry
     * there is a complete ammunition entry, not a patch on the conversion, exactly like a TACZ
     * cartridge: {@code penetration}, {@code profile}, {@code multiplier} and the spall fields all
     * work, and the entry wins outright. Keys are the item id for a melee hit and the damage-type
     * id for a projectile ({@code minecraft:arrow}, {@code minecraft:trident}), which happen to be
     * the item ids for the two projectiles vanilla ships.
     */
    public static final class VanillaWeapons {

        /**
         * Whether these hits are taken over at all. Absent - or null - means yes, which is what a
         * file written before this block existed reads as. {@code false} leaves every vanilla hit
         * to the base mod, damage and all.
         */
        public Boolean enabled = Boolean.TRUE;

        /**
         * Millimetres of penetration per point of damage the hit carried. The hit's damage is what
         * the attacking item, its enchantments, the player's Strength and a critical already add
         * up to, so this is the only number needed to rate every sword, axe and arrow.
         *
         * <p>Zero turns every vanilla hit into a ricochet without turning the feature off; that is
         * the setting to use if the sounds and hit markers are wanted but the damage is not.
         */
        public float penetration_per_damage = 0.4F;

        /**
         * Ceiling on {@link #penetration_per_damage}, in millimetres. Without it a modded weapon
         * with a four-digit attack damage would be handed a four-digit penetration and open a
         * main battle tank, which is the one thing this table exists to prevent. Ten still lets
         * the strongest vanilla hit - a critical, Sharpened, Strength-boosted axe - reach the
         * 8mm roof of a light vehicle; nothing in vanilla gets through anything thicker.
         */
        public float max_penetration = 10.0F;

        /**
         * Flat damage per millimetre of residual penetration, or absent to use the struck plate's
         * own multiplier. Half is deliberately softer than a tank round's: a knife that somehow
         * got through 8mm of steel has a few centimetres of blade left, not a bursting charge.
         */
        public Float damage_multiplier = 0.5F;

        /**
         * Complete round entries that win over the conversion, keyed by item id for a melee hit or
         * by damage-type id for a projectile. Empty by default - the conversion covers vanilla.
         */
        public Map<String, Ammo> overrides = new LinkedHashMap<>();

        /** @return whether vanilla hits are taken over. A missing key means yes. */
        public boolean active() {
            return enabled == null || enabled;
        }

        /** @return the rated round for this id, or {@code null} when the conversion applies. */
        public Ammo overrideFor(String id) {
            return id == null || overrides == null ? null : overrides.get(id);
        }

        /** Fills in anything a hand-edited file may have left out, and clamps the rest. */
        void sanitize() {
            if (penetration_per_damage < 0.0F) {
                penetration_per_damage = 0.0F;
            }
            if (max_penetration < 0.0F) {
                max_penetration = 0.0F;
            }
            if (damage_multiplier != null && damage_multiplier < 0.0F) {
                damage_multiplier = 0.0F;
            }
            if (overrides == null) {
                overrides = new LinkedHashMap<>();
            }
        }
    }

    /**
     * The ratings vanilla Minecraft's own explosions meet the armour table with.
     *
     * <p>An explosion is not a projectile: there is no round, no travelling ray, and the vanilla
     * damage system measures the blast against nothing at all - a TNT block beside a tank used to
     * take health off it exactly as it would off a wooden crate. This block closes that path for
     * the three vanilla explosion damage types: {@code minecraft:explosion},
     * {@code minecraft:player_explosion} and {@code minecraft:bad_respawn_point}. It covers TNT,
     * TNT minecarts, creepers (charged or not), end crystals, beds and respawn anchors, ghast
     * fireballs, charged wither skulls and the Wither's spawn blast.
     *
     * <p><b>How a blast is measured.</b> The damage the vanilla explosion calculated for this
     * vehicle - already falling off with distance and exposure - is converted to an equivalent
     * penetration, {@code damage * penetration_per_damage}, capped by
     * {@link #max_penetration}. The direction is the line from the blast's centre to the hull, and
     * the existing {@code resolveHit} arithmetic takes it from there: face, plate, reactive panels,
     * residual. The residual is multiplied by the plate's own multiplier unless
     * {@link #damage_multiplier} states a flat one, which is what "let the blast eat the armour
     * table's multiplier" means in practice.
     *
     * <p>The shipped 0.5 and 80 make a TNT block (29 raw) 14.5mm: harmless against any tank plate,
     * but enough to open the 8mm roof of a light vehicle. A charged creeper or an end crystal
     * (43 raw) reaches 21.5mm and can breach a thin roof; a ghast fireball's blast (8 raw) is 4mm
     * and bounces off even that. The amount already carries the falloff, so a distant blast is
     * weaker without any extra distance term here.
     *
     * <p><b>Aircraft are deliberately not covered.</b> The skin model is about penetration, and the
     * existing rule for an aircraft is that a near miss still blasts it through the base mod's own
     * explosion path. Vanilla blasts therefore skip a vehicle with an {@link VehicleArmor#aircraft}
     * block, exactly as the warhead-blast branch in {@code ArmorSystem} does.
     *
     * <p><b>The base mod's own explosion is not covered either.</b> {@code ywzj_vehicle.explosion}
     * is a different damage type and stays with that branch: a rated warhead whose round carries
     * its own blast is still dropped, and an unrated one still falls through.
     *
     * <p>{@link #overrides} is the escape hatch, keyed by the direct entity's type id
     * ({@code minecraft:tnt}, {@code minecraft:creeper}, {@code minecraft:end_crystal}, ...) or,
     * when the explosion has no direct entity (beds and respawn anchors), by the damage type id
     * ({@code minecraft:bad_respawn_point}). An entry is a complete ammunition entry and wins
     * outright, exactly like a TACZ cartridge.
     */
    public static final class VanillaExplosions {

        /** Whether vanilla explosions are taken over. A missing key means yes. */
        public Boolean enabled = Boolean.TRUE;

        /**
         * Millimetres of equivalent penetration per point of the blast damage the vanilla
         * explosion calculated for this vehicle. The damage already includes the explosion's
         * distance and exposure falloff, so this is the only number needed to rate every blast.
         */
        public float penetration_per_damage = 0.5F;

        /**
         * Ceiling on the figure above, in millimetres. Without it a modded explosion with a huge
         * raw damage would be handed a penetration to match.
         */
        public float max_penetration = 80.0F;

        /**
         * Flat damage per millimetre of residual penetration, or absent/null to use the struck
         * plate's own multiplier. The default is the latter - a blast that reaches the inside of a
         * tank should be as lethal as the plate it came through allows.
         */
        public Float damage_multiplier;

        /** Complete round entries that win over the conversion, keyed by entity type or damage type. */
        public Map<String, Ammo> overrides = new LinkedHashMap<>();

        /** @return whether vanilla explosions are taken over. A missing key means yes. */
        public boolean active() {
            return enabled == null || enabled;
        }

        /** @return the rated blast for this id, or {@code null} when the conversion applies. */
        public Ammo overrideFor(String id) {
            return id == null || overrides == null ? null : overrides.get(id);
        }

        /** Fills in anything a hand-edited file may have left out, and clamps the rest. */
        void sanitize() {
            if (penetration_per_damage < 0.0F) {
                penetration_per_damage = 0.0F;
            }
            if (max_penetration < 0.0F) {
                max_penetration = 0.0F;
            }
            if (damage_multiplier != null && damage_multiplier < 0.0F) {
                damage_multiplier = 0.0F;
            }
            if (overrides == null) {
                overrides = new LinkedHashMap<>();
            }
        }
    }

    /**
     * The armour side of a collision: what a crash does to a vehicle this addon governs.
     *
     * <p>The base mod computes one number for a collision - {@code 0.5 * mass_t * (dv * 20)^2 *
     * impact_multiplier} - and hands it to {@code AbstractVehicle.hurt} with the damage type
     * {@code ywzj_vehicle:vehicle_collision}. That path never looks at a plate, so a 40t tank at
     * 36 km/h takes about 40 health and a light vehicle takes the same face regardless of where it
     * was hit. This block closes that path the same way the explosion block does: the collision
     * damage becomes an equivalent penetration, the impact side is found from the vehicle's
     * pre-impact velocity (or from the other vehicle, when one rammed it), and the usual
     * {@code resolveHit} arithmetic decides whether the plate held.
     *
     * <p><b>The conversion.</b> {@code raw collision damage * penetration_per_damage}, capped by
     * {@link #max_penetration}. The shipped 0.1 makes a 40t tank at 36 km/h (200 raw) 20mm and at
     * 72 km/h (800 raw) 80mm: a main battle tank's 100mm+ side shrugs both off, a BTR-3's 16mm
     * side does not, and a light vehicle's 8mm roof takes real damage at speed. The residual is
     * multiplied by the plate's own multiplier unless {@link #damage_multiplier} states a flat one.
     *
     * <p><b>Crew shock.</b> A crash that the plate stops still throws the crew around. A short
     * nausea and slowness effect is applied to everyone riding, scaled by the impact and capped at
     * {@link Shock#max_nausea_ticks} / {@link Shock#max_slowness_ticks}. It is deliberately small:
     * the structural damage is zero when the armour holds, and the shock is the feedback that
     * something hit the tank. A penetrating crash also wounds the crew through the existing
     * {@code CrewState} path; the shock is on top of that, not instead of it.
     *
     * <p><b>Scope.</b> The base mod's collision source is only read for the vehicle ids in
     * {@link #vehicles}, shipped as the seven Armor Pioneer vehicles. Everything else keeps the
     * base mod's collision damage, exactly as before. Add ids to extend.
     *
     * <p><b>Aircraft are excluded</b> by the caller, like every other blast/crash rule: the skin
     * model is about penetration, and a crash is not a round.
     */
    public static final class VehicleCollisions {

        /** Whether collision damage is taken over. A missing key means yes. */
        public Boolean enabled = Boolean.TRUE;

        /**
         * The vehicles this applies to, by vehicle id. Empty means none. Shipped with the seven
         * Armor Pioneer vehicles; add ids to extend it to other packs.
         */
        public List<String> vehicles = new java.util.ArrayList<>(List.of(
                "armor_pioneer:vt5", "armor_pioneer:vt4", "armor_pioneer:k2",
                "armor_pioneer:type10", "armor_pioneer:tianma20", "armor_pioneer:ztz100",
                "armor_pioneer:leopard2a4"));

        /** Millimetres of equivalent penetration per point of raw collision damage. */
        public float penetration_per_damage = 0.1F;

        /** Ceiling on the equivalent penetration, in millimetres. */
        public float max_penetration = 500.0F;

        /**
         * Flat damage per millimetre of residual penetration, or absent/null to use the struck
         * plate's own multiplier. The default is the latter.
         */
        public Float damage_multiplier;

        /** The short shell-shock applied to the crew of a vehicle that was hit. */
        public Shock shock = new Shock();

        /** @return whether collision damage is taken over. */
        public boolean active() {
            return enabled == null || enabled;
        }

        /** @return whether this vehicle id is in scope. */
        public boolean appliesTo(String vehicleId) {
            return vehicleId != null && vehicles != null && vehicles.contains(vehicleId);
        }

        /** A crash's short-lived effect on the people inside. */
        public static final class Shock {
            /** Whether the crew feels anything. */
            public Boolean enabled = Boolean.TRUE;

            /** Raw collision damage below which nothing is felt. */
            public float min_damage = 20.0F;

            /** Raw collision damage at which the effect reaches its full length. */
            public float full_damage = 200.0F;

            /** Longest nausea, in ticks. 40 = 2 seconds. */
            public int max_nausea_ticks = 40;

            /** Longest slowness, in ticks. 20 = 1 second. */
            public int max_slowness_ticks = 20;

            /** @return whether the shock is on. */
            public boolean active() {
                return enabled == null || enabled;
            }

            /** Fills in anything a hand-edited file may have left out, and clamps the rest. */
            void sanitize() {
                if (min_damage < 0.0F) {
                    min_damage = 0.0F;
                }
                if (full_damage <= min_damage) {
                    full_damage = min_damage + 1.0F;
                }
                if (max_nausea_ticks < 0) {
                    max_nausea_ticks = 0;
                }
                if (max_slowness_ticks < 0) {
                    max_slowness_ticks = 0;
                }
            }
        }

        /** Fills in anything a hand-edited file may have left out, and clamps the rest. */
        void sanitize() {
            if (vehicles == null) {
                vehicles = new java.util.ArrayList<>();
            }
            if (penetration_per_damage < 0.0F) {
                penetration_per_damage = 0.0F;
            }
            if (max_penetration < 0.0F) {
                max_penetration = 0.0F;
            }
            if (damage_multiplier != null && damage_multiplier < 0.0F) {
                damage_multiplier = 0.0F;
            }
            if (shock == null) {
                shock = new Shock();
            }
            shock.sanitize();
        }
    }

    /**
     * What a vehicle can crush by driving into it.
     *
     * <p>This is the high-speed counterpart to the base mod's own block destruction. The base
     * destroys a small patch only after the vehicle has been <em>stuck against the same face for
     * ten ticks</em>, with no speed or mass term - a slow grind. This block reads the contacts the
     * base already collects ({@code VehicleCollectCollisionEvent}) and, while the vehicle is still
     * moving into the face, decides block by block whether the mass and speed are worth more than
     * the block's hardness.
     *
     * <p>The test is {@code hardness <= base_hardness + hardness_per_speed * speed *
     * sqrt(mass / mass_reference)} - speed in blocks per tick, mass in the vehicle's own tonnes.
     * The shipped numbers let a 40t tank break plants and glass at a crawl, wood and stone at a
     * few metres per second, and iron at speed; a 2t car reaches only the softest blocks. Obsidian
     * (hardness 50) and bedrock (-1) have their own hard caps and never break.
     *
     * <p><b>Scope and the ground.</b> Only the vehicle ids in {@link #vehicles} ram (shipped: the
     * seven Armor Pioneer vehicles), and only when the vehicle's own
     * {@code physics_info.can_destroy_block} and the base mod's global {@code canDestroyBlock} are
     * on. Natural terrain is protected by default: stone, dirt, sand, gravel, snow, ice, terracotta
     * and the rest of the world's own materials are never destroyed, however fast the vehicle is.
     * What a ram can still flatten is what someone built out of timber, cobblestone, bricks and
     * the like - a tank should level a shed, not eat a hillside. See
     * {@link #protect_natural_ground}.
     *
     * <p>Block entities (chests, machines) are skipped by default so a ram cannot delete their
     * contents. Block drops are off, matching the base mod; the break particles and sound are the
     * feedback.
     */
    public static final class VehicleRamming {

        /** Whether the high-speed ram runs. */
        public Boolean enabled = Boolean.TRUE;

        /** The vehicles allowed to ram, by vehicle id. Empty means none. */
        public List<String> vehicles = new java.util.ArrayList<>(List.of(
                "armor_pioneer:vt5", "armor_pioneer:vt4", "armor_pioneer:k2",
                "armor_pioneer:type10", "armor_pioneer:tianma20", "armor_pioneer:ztz100",
                "armor_pioneer:leopard2a4"));

        /** Speed below which nothing is crushed, in blocks per tick. 0.15 is about 3 m/s. */
        public float speed_threshold = 0.15F;

        /** Hardness crushable at any speed above the threshold. */
        public float base_hardness = 0.4F;

        /** Extra hardness per (block/tick) of speed, scaled by the mass factor. */
        public float hardness_per_speed = 10.0F;

        /** The mass the mass factor is measured against, in tonnes. */
        public float mass_reference_tons = 40.0F;

        /** Cap on blocks destroyed by one vehicle in one tick. */
        public int max_blocks_per_tick = 12;

        /** Skip blocks that carry a block entity (chests, machines) rather than deleting them. */
        public boolean skip_block_entities = true;

        /**
         * Whether the world's own ground is exempt from the ram.
         *
         * <p>On by default, and read by {@code VehicleRamming} through the game's own terrain
         * tags - {@code base_stone_overworld} / {@code base_stone_nether} / {@code dirt} /
         * {@code sand} / {@code snow} / {@code ice} / {@code terracotta} - plus the natural
         * blocks that carry no tag (gravel, clay, sandstone, end stone, calcite, dripstone,
         * amethyst, sculk, soul soil and a few more). A modded block tagged as one of these is
         * covered without being named; anything else is added to
         * {@link #natural_ground_blocks}.
         *
         * <p><b>This is a material rule, not a provenance rule.</b> Minecraft does not record
         * who placed a block, so a player-built dirt wall is protected exactly like a hillside.
         * Cobblestone, stone bricks, planks, logs, wool and glass carry no natural tag and are
         * still crushable, which is the intended split: the ground survives, buildings do not.
         */
        public Boolean protect_natural_ground = Boolean.TRUE;

        /**
         * Extra block ids the ram must never destroy, on top of the built-in terrain tags.
         *
         * <p>For a mod's ground that carries no vanilla terrain tag. Entries are namespaced
         * block ids (for example {@code some_mod:basalt_soil}), resolved at ram time; an id the
         * registry does not know is simply never matched.
         */
        public List<String> natural_ground_blocks = new java.util.ArrayList<>();

        /** @return whether the high-speed ram is on. */
        public boolean active() {
            return enabled == null || enabled;
        }

        /** @return whether this vehicle id is in scope. */
        public boolean appliesTo(String vehicleId) {
            return vehicleId != null && vehicles != null && vehicles.contains(vehicleId);
        }

        /** Fills in anything a hand-edited file may have left out, and clamps the rest. */
        void sanitize() {
            if (vehicles == null) {
                vehicles = new java.util.ArrayList<>();
            }
            if (speed_threshold < 0.0F) {
                speed_threshold = 0.0F;
            }
            if (base_hardness < 0.0F) {
                base_hardness = 0.0F;
            }
            if (hardness_per_speed < 0.0F) {
                hardness_per_speed = 0.0F;
            }
            if (mass_reference_tons <= 0.0F) {
                mass_reference_tons = 40.0F;
            }
            if (max_blocks_per_tick < 0) {
                max_blocks_per_tick = 0;
            }
            if (natural_ground_blocks == null) {
                natural_ground_blocks = new java.util.ArrayList<>();
            }
            // Null means "left out of a hand-written block", which takes the shipped answer.
            if (protect_natural_ground == null) {
                protect_natural_ground = Boolean.TRUE;
            }
        }
    }

    /**
     * @return the first of the given keys that holds a number, or {@code null} when none of them do.
     *
     * <p>For reading a value that has been renamed: the same setting under its old name has to keep
     * loading, or a file written by the build that used that name loses the setting and no upgrade
     * will ever put it back.
     */
    private static Float number(JsonObject object, String... keys) {
        for (String key : keys) {
            if (object.has(key) && !object.get(key).isJsonNull()) {
                return object.get(key).getAsFloat();
            }
        }
        return null;
    }

    /**
     * Reads an ammunition entry written either as the old bare number or as the current object
     * form, so an upgrade never has to throw away a config the user has already edited.
     */
    private static final class AmmoDeserializer implements JsonDeserializer<Ammo> {
        @Override
        public Ammo deserialize(JsonElement json, Type type, JsonDeserializationContext context) {
            if (json.isJsonPrimitive()) {
                return new Ammo(json.getAsFloat());
            }
            Ammo ammo = new Ammo();
            JsonObject object = json.getAsJsonObject();
            if (object.has("penetration")) {
                ammo.penetration = object.get("penetration").getAsFloat();
            }
            if (object.has("range_penetration") && object.get("range_penetration").isJsonArray()) {
                java.util.List<Ammo.RangePoint> points = new java.util.ArrayList<>();
                for (JsonElement element : object.getAsJsonArray("range_penetration")) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject point = element.getAsJsonObject();
                    if (!point.has("range") || !point.has("penetration")) {
                        continue;
                    }
                    points.add(new Ammo.RangePoint(point.get("range").getAsFloat(),
                            point.get("penetration").getAsFloat()));
                }
                ammo.range_penetration = points.size() >= 2 ? points : null;
            }
            if (object.has("multiplier") && !object.get("multiplier").isJsonNull()) {
                ammo.multiplier = object.get("multiplier").getAsFloat();
            }
            if (object.has("profile") && !object.get("profile").isJsonNull()) {
                ammo.profile = object.get("profile").getAsString();
            }
            if (object.has("spall_damage_min") && !object.get("spall_damage_min").isJsonNull()) {
                ammo.spall_damage_min = object.get("spall_damage_min").getAsFloat();
            }
            if (object.has("spall_damage_max") && !object.get("spall_damage_max").isJsonNull()) {
                ammo.spall_damage_max = object.get("spall_damage_max").getAsFloat();
            }
            if (object.has("spall_blindness_ticks") && !object.get("spall_blindness_ticks").isJsonNull()) {
                ammo.spall_blindness_ticks = object.get("spall_blindness_ticks").getAsInt();
            }
            if (object.has("penetration_damage") && !object.get("penetration_damage").isJsonNull()) {
                ammo.penetration_damage = object.get("penetration_damage").getAsFloat();
            }
            // Two names each, and the old one is not optional politeness. The bonus fields shipped
            // for one build under penetration_damage_*, and a config written by that build is
            // already at the current version - so nothing would rewrite it and the round would go
            // on firing with a bonus of zero, which is a number the file never said. Read under
            // either name; the next write uses the new one.
            ammo.penetration_bonus_min = number(object,
                    "penetration_bonus_min", "penetration_damage_min");
            ammo.penetration_bonus_max = number(object,
                    "penetration_bonus_max", "penetration_damage_max");
            ammo.penetration_bonus_max_at = number(object,
                    "penetration_bonus_max_at", "penetration_damage_max_at");
            if (object.has("spall_falloff_to") && !object.get("spall_falloff_to").isJsonNull()) {
                ammo.spall_falloff_to = object.get("spall_falloff_to").getAsFloat();
            }
            if (object.has("spall_on_penetration") && !object.get("spall_on_penetration").isJsonNull()) {
                ammo.spall_on_penetration = object.get("spall_on_penetration").getAsBoolean();
            }
            if (object.has("blast_radius")) {
                // Removed again: the swept sphere was answered by the thin roof no matter what it
                // struck, which made the glacis on a T-64 read as 60mm. Silently ignored so an
                // existing file still loads; it disappears the next time the config is written.
                VehicleArmorMod.LOGGER.info("[Vehicle Armor] ignoring obsolete blast_radius on an entry");
            }
            if (object.has("replaces_base_explosion") && !object.get("replaces_base_explosion").isJsonNull()) {
                ammo.replaces_base_explosion = object.get("replaces_base_explosion").getAsBoolean();
            }
            if (object.has("multi_purpose") && !object.get("multi_purpose").isJsonNull()) {
                ammo.multi_purpose = object.get("multi_purpose").getAsBoolean();
            }
            if (object.has("proximity_radius") && !object.get("proximity_radius").isJsonNull()) {
                ammo.proximity_radius = object.get("proximity_radius").getAsFloat();
            }
            if (object.has("top_attack_standoff") && !object.get("top_attack_standoff").isJsonNull()) {
                ammo.top_attack_standoff = object.get("top_attack_standoff").getAsFloat();
            }
            if (object.has("top_attack_radius") && !object.get("top_attack_radius").isJsonNull()) {
                ammo.top_attack_radius = object.get("top_attack_radius").getAsFloat();
            }
            if (object.has("top_attack_min_height") && !object.get("top_attack_min_height").isJsonNull()) {
                ammo.top_attack_min_height = object.get("top_attack_min_height").getAsFloat();
            }
            if (object.has("mass_kg") && !object.get("mass_kg").isJsonNull()) {
                ammo.mass_kg = object.get("mass_kg").getAsFloat();
            }
            if (object.has("muzzle_velocity") && !object.get("muzzle_velocity").isJsonNull()) {
                ammo.muzzle_velocity = object.get("muzzle_velocity").getAsFloat();
            }
            if (object.has("mass_exponent") && !object.get("mass_exponent").isJsonNull()) {
                ammo.mass_exponent = object.get("mass_exponent").getAsFloat();
            }
            if (object.has("velocity_exponent") && !object.get("velocity_exponent").isJsonNull()) {
                ammo.velocity_exponent = object.get("velocity_exponent").getAsFloat();
            }
            if (object.has("caliber_mm") && !object.get("caliber_mm").isJsonNull()) {
                ammo.caliber_mm = object.get("caliber_mm").getAsFloat();
            }
            if (object.has("heat_damage") && !object.get("heat_damage").isJsonNull()) {
                ammo.heat_damage = object.get("heat_damage").getAsFloat();
            }
            if (object.has("charge_kg") && !object.get("charge_kg").isJsonNull()) {
                ammo.charge_kg = object.get("charge_kg").getAsFloat();
            }
            if (object.has("pele") && !object.get("pele").isJsonNull()) {
                ammo.pele = object.get("pele").getAsBoolean();
            }
            if (object.has("tandem") && !object.get("tandem").isJsonNull()) {
                ammo.tandem = object.get("tandem").getAsBoolean();
            }
            if (object.has("proximity") && !object.get("proximity").isJsonNull()) {
                // Re-enters this same deserializer through the context, so the nested block accepts
                // the bare-number form and every optional key exactly as the outer one does.
                ammo.proximity = context.deserialize(object.get("proximity"), Ammo.class);
            }
            if (object.has("category") && !object.get("category").isJsonNull()) {
                ammo.category = object.get("category").getAsString();
            }
            if (object.has("name") && !object.get("name").isJsonNull()) {
                ammo.name = object.get("name").getAsString();
            }
            if (object.has("variants") && object.get("variants").isJsonArray()) {
                ammo.variants = new java.util.ArrayList<>();
                for (JsonElement variant : object.getAsJsonArray("variants")) {
                    // Each one is a full entry, so a variant can carry anything a round can - a
                    // different profile, its own blast - and not merely a different penetration.
                    ammo.variants.add(context.deserialize(variant, Ammo.class));
                }
            }
            return ammo;
        }
    }

    /**
     * Writes an ammunition entry back out, leaving out every field that still holds its default.
     *
     * <p>Without this each entry would carry a row of zeroes for settings only chemical rounds
     * use, and the file a player opens to edit would be mostly noise. The reader fills the
     * absent fields back in, so the round trip is lossless.
     */
    private static final class AmmoSerializer implements JsonSerializer<Ammo> {
        @Override
        public JsonElement serialize(Ammo ammo, Type type, JsonSerializationContext context) {
            JsonObject object = new JsonObject();
            object.addProperty("penetration", ammo.penetration);
            if (ammo.multiplier != null) {
                object.addProperty("multiplier", ammo.multiplier);
            }
            if (ammo.profile != null) {
                object.addProperty("profile", ammo.profile);
            }
            if (ammo.penetration_damage != null) {
                object.addProperty("penetration_damage", ammo.penetration_damage);
            }
            if (ammo.range_penetration != null && ammo.range_penetration.size() >= 2) {
                JsonArray list = new JsonArray();
                for (Ammo.RangePoint point : ammo.range_penetration) {
                    if (point == null) {
                        continue;
                    }
                    JsonObject one = new JsonObject();
                    one.addProperty("range", point.range);
                    one.addProperty("penetration", point.penetration);
                    list.add(one);
                }
                if (list.size() >= 2) {
                    object.add("range_penetration", list);
                }
            }
            // The floor and the plateau are only written when they say something. A round that rolls
            // from zero up to its max at 200mm is the shape the defaults already describe, and three
            // keys where one carries the meaning is three chances to edit them out of step.
            if (ammo.penetration_bonus_max != null) {
                if (ammo.penetration_bonus_min != null) {
                    object.addProperty("penetration_bonus_min", ammo.penetration_bonus_min);
                }
                object.addProperty("penetration_bonus_max", ammo.penetration_bonus_max);
                if (ammo.penetration_bonus_max_at != null) {
                    object.addProperty("penetration_bonus_max_at", ammo.penetration_bonus_max_at);
                }
            }
            if (ammo.spall_damage_min != 0.0F || ammo.spall_damage_max != 0.0F) {
                object.addProperty("spall_damage_min", ammo.spall_damage_min);
                object.addProperty("spall_damage_max", ammo.spall_damage_max);
            }
            if (ammo.spall_falloff_to != 0.0F) {
                object.addProperty("spall_falloff_to", ammo.spall_falloff_to);
            }
            if (Boolean.FALSE.equals(ammo.spall_on_penetration)) {
                // Only the exception is worth writing: leaving the key out means "yes", which is
                // what every round except a high-explosive shell wants.
                object.addProperty("spall_on_penetration", false);
            }
            if (ammo.spall_blindness_ticks != 0) {
                object.addProperty("spall_blindness_ticks", ammo.spall_blindness_ticks);
            }
            if (Boolean.TRUE.equals(ammo.replaces_base_explosion)) {
                object.addProperty("replaces_base_explosion", true);
            }
            if (Boolean.TRUE.equals(ammo.pele)) {
                object.addProperty("pele", true);
            }
            if (Boolean.TRUE.equals(ammo.multi_purpose)) {
                object.addProperty("multi_purpose", true);
            }
            if (ammo.proximity_radius != null) {
                object.addProperty("proximity_radius", ammo.proximity_radius);
            }
            if (ammo.top_attack_standoff != null) {
                object.addProperty("top_attack_standoff", ammo.top_attack_standoff);
            }
            if (ammo.top_attack_radius != null) {
                object.addProperty("top_attack_radius", ammo.top_attack_radius);
            }
            if (ammo.top_attack_min_height != null) {
                object.addProperty("top_attack_min_height", ammo.top_attack_min_height);
            }
            if (ammo.mass_kg != null) {
                object.addProperty("mass_kg", ammo.mass_kg);
            }
            if (ammo.muzzle_velocity != null) {
                object.addProperty("muzzle_velocity", ammo.muzzle_velocity);
            }
            if (ammo.mass_exponent != null) {
                object.addProperty("mass_exponent", ammo.mass_exponent);
            }
            if (ammo.velocity_exponent != null) {
                object.addProperty("velocity_exponent", ammo.velocity_exponent);
            }
            if (ammo.caliber_mm != null) {
                object.addProperty("caliber_mm", ammo.caliber_mm);
            }
            if (ammo.heat_damage != null) {
                object.addProperty("heat_damage", ammo.heat_damage);
            }
            if (ammo.charge_kg != null) {
                object.addProperty("charge_kg", ammo.charge_kg);
            }
            if (Boolean.TRUE.equals(ammo.tandem)) {
                object.addProperty("tandem", true);
            }
            if (ammo.category != null) {
                // Written even when it matches the derived answer: which shelf a round belongs on
                // is a choice about how the book reads, not a default to be left implicit.
                object.addProperty("category", ammo.category);
            }
            if (ammo.name != null) {
                object.addProperty("name", ammo.name);
            }
            if (ammo.variants != null && !ammo.variants.isEmpty()) {
                JsonArray list = new JsonArray();
                for (Ammo variant : ammo.variants) {
                    list.add(context.serialize(variant, Ammo.class));
                }
                object.add("variants", list);
            }
            if (ammo.proximity != null) {
                // Written through the context so the nested entry comes back out in exactly the
                // shape this serializer produces, and so its own missing keys stay missing.
                object.add("proximity", context.serialize(ammo.proximity, Ammo.class));
            }
            return object;
        }
    }

    /** One armour plate: how thick it is and how lethal a penetration through it is. */
    public static final class Plate {
        public float thickness = 90.0F;
        public float multiplier = 5.0F;

        /**
         * Millimetres of penetration the round loses in passing through, on top of whatever
         * {@link #thickness} it has to get past.
         *
         * <p>This is the other half of what armour does, and the half a thickness cannot express.
         * Adding thickness has two outcomes: the round is stopped, or it goes through completely
         * unharmed. Nothing in between. But a screen, a spaced gap or a reactive panel does not
         * care whether the round gets past - it cares that the round arrives on the far side
         * <em>worse than it was</em>. A rod whose length has been broken up, or a jet that has been
         * scattered, penetrates the main plate as a much weaker round: less residual, so less
         * damage, or no penetration at all where the same shot had gone through.
         *
         * <p>Read after the thickness, not instead of it: the round loses this much and then meets
         * the plate behind. Setting both to something sensible is how a real screen is written - a
         * few millimetres of steel, and the disruption it causes out of all proportion to that.
         *
         * <p>Zero, the default, is ordinary armour and what every plate written before this key
         * meant.
         */
        public float penetration_loss = 0.0F;

        /**
         * Explosive reactive armour: millimetres of penetration one panel takes off the round that
         * sets it off. Zero - the default - is ordinary or spaced armour.
         *
         * <p>A panel is single-use. The first round to hit an unspent one loses {@code era_hp} from
         * its penetration and the panel is used up; if the round had less than that to begin with,
         * the panel stops it outright. What is left goes on to the armour behind the panel, which is
         * the plate the panel exists to keep the round away from. A tandem warhead is not special-
         * cased: precursor and main charge are one round for this purpose.
         *
         * <p>Only meaningful where a plate is a panel bolted to the outside: {@code hull_side_era}
         * and a {@code part_armor} box. Written anywhere else it is inert, since no other zone is
         * something a round can be said to go through.
         *
         * <p>{@link #thickness} is the panel's own rating for the scanner and the tables; it is not
         * part of the arithmetic. The HP is the whole of what the panel does. Write the same number
         * in both so the readout and the rule cannot disagree.
         *
         * <p>This is the legacy single figure, used for both threat kinds and with no angle
         * falloff. {@link #era_ce} and {@link #era_ke} override it separately when a panel states
         * them; everything that does not keeps the flat behaviour this field has always had.
         */
        public float era_hp = 0.0F;

        /**
         * The panel's rating against a shaped-charge jet, in millimetres removed from the jet at
         * normal incidence. A jet is a thin, low-mass stream that the flying plates cut and
         * scatter, so a real ERA panel's chemical rating is much higher than its kinetic one.
         *
         * <p>Absent means {@link #era_hp} is used for both.
         */
        public Float era_ce;

        /**
         * The panel's rating against a long rod, in millimetres removed at normal incidence. The
         * rod is far harder to disturb: the plates can yaw and erode it, but most of the reduction
         * is the plate motion across the impact rather than a cut through the penetrator.
         *
         * <p>Absent means {@link #era_hp} is used for both.
         */
        public Float era_ke;

        /**
         * The share of the rating lost at 70 degrees from the panel's normal, 0..1. The flying
         * plates move across the impact, so a very oblique hit gets less of that motion to work
         * with. Absent - and on every legacy panel - is 0, which is the old flat rule.
         */
        public Float era_ce_falloff;
        public Float era_ke_falloff;

        /**
         * Multi-hit add-on armour: the pool, in millimetres, that {@link #addon_fractions} is a
         * fraction of. Zero - the default - is ordinary armour.
         *
         * <p>Where {@link #era_hp} is a single-use layer worth its whole HP, this is a layer that
         * survives more than one hit by getting weaker: each hit takes a different fraction of the
         * pool off the round, and once every fraction has been used the layer is gone. The Leopard
         * 2A4's hull flanks are the model for it - 150 HP, 60 % / 40 % / 20 %, so three hits worth
         * 90, 60 and 30 mm and nothing after them.
         *
         * <p>Written only where a plate is a layer in front of the hull's flanks
         * ({@code hull_side_addon}); anywhere else it is inert, the same rule {@link #era_hp}
         * follows.
         */
        public float addon_hp = 0.0F;

        /**
         * The fraction of {@link #addon_hp} each successive hit takes off the round, in order.
         *
         * <p>The array's length is how many hits the layer survives; the last entry is still taken
         * off the round that removes it. Null or empty means no layer, so a plate that only says
         * {@code addon_hp} does nothing at all - the fractions are the rule, and a pool with no
         * rule is not something to guess at.
         */
        public float[] addon_fractions;

        /**
         * The old ERA kind, read from a file written before v70 and never written again.
         *
         * <p>The kind named a rule - light let a tandem round through at two thirds, heavy stopped
         * it once - rather than a number. v70 replaced both with {@link #era_hp}, and
         * {@code upgrade()} converts a file that still carries the string. It stays a field so that
         * conversion can see what the old file said; nothing at runtime reads it.
         */
        @Deprecated
        public String era;

        public Plate() {
        }

        public Plate(float thickness, float multiplier) {
            this.thickness = thickness;
            this.multiplier = multiplier;
        }

        /** Marks this plate as reactive armour worth {@code hp} millimetres, for readable defaults. */
        public Plate reactiveHp(float hp) {
            this.era_hp = hp;
            return this;
        }

        /** @return whether this panel states a threat-specific rating of its own. */
        public boolean hasSplitEra() {
            return era_ce != null || era_ke != null;
        }

        /** @return what this panel takes off a shaped-charge jet at normal incidence. */
        public float eraCe() {
            return era_ce != null ? era_ce : era_hp;
        }

        /** @return what this panel takes off a long rod at normal incidence. */
        public float eraKe() {
            return era_ke != null ? era_ke : era_hp;
        }

        /** @return the share of {@link #eraCe()} lost at 70 degrees, clamped to 0..1. */
        public float eraCeFalloff() {
            return clampShare(era_ce_falloff);
        }

        /** @return the share of {@link #eraKe()} lost at 70 degrees, clamped to 0..1. */
        public float eraKeFalloff() {
            return clampShare(era_ke_falloff);
        }

        private static float clampShare(Float share) {
            if (share == null || share <= 0.0F) {
                return 0.0F;
            }
            return Math.min(1.0F, share);
        }
    }

    /**
     * Splits a vehicle's front armour into an upper and a lower plate, the way real tanks
     * have a glacis (首上) above a lower front plate (首下).
     *
     * <p>Minecraft collision boxes are plain cuboids with no sloped plates to detect, so the
     * split is made purely on where the round struck vertically: hits above the split line
     * use {@link #upper}, hits below it use {@link #lower}.
     */
    public static final class FrontSplit {
        /** Set to false to fall back to the single {@code front} thickness and multiplier. */
        public boolean enabled = true;

        /**
         * Where the two plates meet, as a position along the hull's vertical axis:
         * {@code +1} is the top of the hull box, {@code 0} the middle, {@code -1} the bottom.
         * Raise it to make the upper plate cover more of the front, lower it for the opposite.
         */
        public float split = 0.0F;

        public Plate upper = new Plate(440.0F, 50.0F);
        public Plate lower = new Plate(120.0F, 5.0F);

        /**
         * The top strip of the upper plate, or {@code null} for none.
         *
         * <p>Real glacides are not uniform: on the Chinese export tanks the very top of the upper
         * plate is laid back far enough to be a forced-ricochet face, and it is stated as a figure
         * of its own rather than as part of the plate below. A vehicle that says nothing here keeps
         * the two-zone behaviour every config written before this field had.
         */
        public Plate upper_band;

        /**
         * Bottom edge of {@link #upper_band}, on the same normalised scale as {@link #split}:
         * {@code +1} is the top of the hull box, {@code -1} the bottom. A hit at or above it is
         * measured against the band; anything below falls through to the ordinary upper plate.
         * It must be above {@link #split}, or the band covers no part of the glacis.
         */
        public float upper_band_split = 0.0F;

        /**
         * How far {@link #upper_band}'s own plate leans, in degrees from vertical, or zero to
         * share the vehicle's {@code hull_slopes.front}.
         *
         * <p>A band that stands on its own geometry states its own angle here, and its
         * {@link Plate#thickness} is then the steel rather than the line-of-sight figure. The
         * VT-4's top strip is the case: it is the near-horizontal face above the glacis - the
         * one that exists to ricochet frontal rounds - so treating it as a vertical slice of the
         * main plate hides the whole point of it. At 81 degrees 125mm of steel presents 799mm
         * from dead ahead, 1130mm from 45 degrees off the nose, and only 384mm from ten degrees
         * above: the frontal protection of a thick plate, and the soft top a ricochet face really
         * has.
         *
         * <p>Applies to kinetic rounds only, like every other stated slope.
         */
        public float upper_band_slope = 0.0F;

        /**
         * The top strip of the lower plate, or {@code null} for none - the main armour belt
         * continuing down past the glacis' lower edge.
         */
        public Plate lower_band;

        /**
         * Bottom edge of {@link #lower_band}, on the same normalised scale as {@link #split}.
         * A hit at or above it, and below {@link #split}, is measured against the band; a hit
         * below it is measured against the ordinary lower plate.
         */
        public float lower_band_split = 0.0F;

        /**
         * The inclination of the upper plate itself, in degrees from vertical, or zero to share
         * the vehicle's {@code hull_slopes.front}.
         *
         * <p>The two halves of a hull front are separate plates and often lean differently. The
         * Panther's glacis and lower plate both sit at 55 degrees and can share one figure; the
         * Tiger's driver's plate is 9 degrees and its lower nose 25, and stating them apart is
         * the difference between 101mm and 110mm of line-of-sight thickness on the lower plate.
         * Like every stated slope, the plate's {@link Plate#thickness} is then the steel.
         */
        public float upper_slope = 0.0F;

        /** The inclination of the lower plate itself, or zero to share the hull's front slope. */
        public float lower_slope = 0.0F;

        public FrontSplit() {
        }
    }

    /**
     * A small circle on a vehicle's front plate that is not the same thickness as the armour around
     * it - usually weaker, which is what the name assumes.
     *
     * <p>Two of them exist and they are the same mechanism doing opposite jobs. On the turret it is
     * the gun mount (炮根), a hole that has to exist for the barrel to pass through. On the hull it
     * is the machine-gun port (机枪口), another hole in an otherwise thick glacis. Neither is
     * required to be weaker - the Panther's gun shield is the strongest part of its front plate -
     * so what this describes is "a circle that is measured differently", and the numbers say which
     * way.
     */
    public static final class WeakSpot {
        /** Set to false to restore the surrounding front armour. */
        public boolean enabled = true;

        public float thickness = 200.0F;
        public float multiplier = 5.0F;

        /**
         * Radius of the weak spot in blocks, measured on the front plate it sits on.
         * A hit inside this circle is matched against {@link #thickness}.
         */
        public float radius = 0.4F;

        /**
         * Centre of the circle, in blocks, offset from the middle of that plate.
         *
         * <p>On the turret, along the turret box's own axes, so it follows the turret round. On the
         * hull, along the hull box's axes, and the horizontal one is the model's own X - which is
         * the side the machine gun is on, and is whatever the modeller chose it to be.
         */
        public float offset_x = 0.0F;
        public float offset_y = 0.0F;

        public WeakSpot() {
        }
    }

    /**
     * The front and rear ends of the track run, which stick out either side of the hull's
     * front and rear plates. Rounds hitting the running gear do far less to the vehicle
     * than one through the hull, hence the low multiplier.
     *
     * <p>This is defined geometrically rather than by part id: the stock T-64's track parts
     * carry only nine 0.125-block locator cubes each, so a ray essentially never registers
     * a hit on them.
     */
    public static final class TrackEnds {
        /** Set to false to give the running gear the surrounding hull armour again. */
        public boolean enabled = true;

        public float thickness = 200.0F;
        public float multiplier = 1.5F;

        /**
         * Inner edge of the track strip, as a fraction of the hull box's half-width.
         * Hits further out than this are treated as running gear.
         */
        public float inner_edge = 0.64F;

        /**
         * Top of the track strip, on the same normalised vertical scale as
         * {@link FrontSplit#split}: {@code +1} hull top, {@code 0} hull centre, {@code -1} bottom.
         */
        public float top = 0.0F;

        public TrackEnds() {
        }
    }

    /**
     * The road wheels of a wheeled vehicle, which run along the whole lower flank rather than
     * sitting at the ends of a track run.
     *
     * <p>Modelled geometrically for the same reason the track strips are: the wheels have real
     * collision boxes, but the hull's own box encloses them. On the BTR-3 the wheels reach out to
     * |X| = 23.01953 units and the bare hull to 23.10, so a ray from the side always enters the hull
     * first and a wheel is never the box that gets hit - measured with the mod's own
     * {@code hitOBB}, not assumed.
     */
    public static final class RoadWheels {
        /** Set to false to give the flanks the surrounding hull armour again. */
        public boolean enabled = true;

        public float thickness = 20.0F;
        public float multiplier = 1.5F;

        /**
         * Top of the wheel strip, on the same normalised vertical scale as {@link FrontSplit#split}:
         * {@code +1} hull top, {@code 0} hull centre, {@code -1} bottom.
         *
         * <p>The whole strip is treated as wheels, gaps between them included - the same
         * approximation the track strips make. On the BTR-3 the gaps are about a sixth of the run.
         */
        public float top = 0.04F;

        public RoadWheels() {
        }
    }

    /** Value-only form of {@link FrontSplit}: the split line itself stays with the vehicle. */
    public static final class FrontSplitPlates {
        public Plate upper;
        public Plate lower;

        public FrontSplitPlates() {
        }
    }

    /**
     * Armour values for one kind of ammunition, layered over the vehicle's own.
     *
     * <p>A chemical round is stopped by completely different things than a kinetic one - it cares
     * about the width of the armour it has to jet through rather than about a sloped plate - so
     * the two need separate numbers for the same vehicle. Any zone left out keeps the vehicle's
     * own value, so only the differences have to be written down.
     *
     * <p>Geometry - where the gun mount circle sits, where the glacis ends, where the track strips
     * begin - always comes from the vehicle, since it describes the tank and not the round.
     */
    public static final class ArmorOverride {
        public ArmorSet turret;
        public ArmorSet hull;
        public DamageMultiplier damage_multiplier;
        /** Thickness and multiplier only; the circle itself stays with the vehicle. */
        public Plate turret_weak_spot;
        /** The same for the machine-gun port, if the shell cares about it differently. */
        public Plate hull_weak_spot;
        /**
         * Per-part values for this kind of round. A part left out keeps the vehicle's own, which
         * is what a shell that does not care about the boxes has to do - a slat screen means
         * nothing to a high-explosive shell.
         */
        public Map<String, Plate> part_armor = new LinkedHashMap<>();
        public FrontSplitPlates hull_front_split;
        public Plate track_ends;
        /** Thickness and multiplier of the road wheels; the strip's height stays with the vehicle. */
        public Plate road_wheels;
        /** Armour over the explosive-reactive side panels; absent means the side is uniform. */
        public Plate hull_side_era;
        /** The hull's multi-hit add-on armour for this kind of round; absent means the vehicle's. */
        public Plate hull_side_addon;

        public ArmorOverride() {
        }
    }

    /** Armour of a single vehicle, split into turret and hull. */
    public static final class VehicleArmor {
        public ArmorSet turret = new ArmorSet(480.0F, 150.0F, 90.0F, 90.0F, 90.0F);
        public ArmorSet hull = new ArmorSet(440.0F, 100.0F, 90.0F, 90.0F, 90.0F);

        /**
         * Where the hull's plates actually lean, or {@code null} for a plain box.
         *
         * <p>The thicknesses in {@link #hull} and {@link #turret} are the plates themselves when a
         * slope is stated, and the effective line-of-sight figures they always were when it is
         * not. The distinction matters: an 80mm plate at 55 degrees and a 140mm vertical plate are
         * the same resistance from dead ahead and very different ones from 45 degrees off, and only
         * the geometry can tell the two apart.
         *
         * <p>Applied to kinetic rounds only. A shaped-charge jet is not defeated by the slope the
         * way a hard projectile is, and the {@code heat} / {@code he} tables were written in bare
         * plate thicknesses to say so - {@link ArmorSystem} leaves them alone.
         */
        public PlateSlopes hull_slopes;

        /** The turret's plate inclinations, or {@code null} for a plain box. See {@link #hull_slopes}. */
        public PlateSlopes turret_slopes;

        /**
         * When true, a kinetic round is measured against the face plate along the angle it actually
         * arrived at, without the vehicle stating a plate inclination.
         *
         * <p>This is the other half of {@link #hull_slopes}, for vehicles whose table cannot be
         * written as bare steel. A composite array is published as an equivalent - a figure that
         * already includes the obliquity of the plate it sits in - and Norinco publishes no
         * thickness for the VT-4 at all; writing the steel and a slope would invent both. What can
         * be done honestly is to treat the stated figure as the protection along the vehicle's own
         * front and scale it by how far the hit deviates from that direction: a dart 30 degrees off
         * the nose crosses about 1/cos(30) of the array a frontal one does. The Panther, whose
         * figures are steel, states slopes instead; the two are alternatives, and a vehicle that
         * states both is measured the slope way.
         *
         * <p>Kinetic only, and face plates only. A shaped-charge jet is not turned by the angle the
         * way a dart is, and the bolted-on layers - reactive panels above all - are values of their
         * own that were taken off the round before it reached the face.
         */
        public Boolean kinetic_angle_scaling;

        /** @return whether this vehicle asks for the per-hit direction factor. Null means no. */
        public boolean kineticAngleScaling() {
            return Boolean.TRUE.equals(kinetic_angle_scaling);
        }

        /**
         * @return whether this vehicle states any plate inclination at all - on the hull, the
         *         turret, or the hull-front split.
         *
         * <p>The signal that this vehicle's tables are geometry rather than flat box faces. Once
         * it is true, every face plate is measured along the impact angle, including the ones
         * with no lean of their own: a vertical flank, a roof and a floor all cross more steel
         * when the round arrives obliquely, and leaving them flat would make angling the hull
         * pointless and a roof immune to anything but a square-on hit.
         */
        public boolean hasStatedGeometry() {
            if (hull_slopes != null && hull_slopes.any()) {
                return true;
            }
            if (turret_slopes != null && turret_slopes.any()) {
                return true;
            }
            FrontSplit split = hull_front_split;
            return split != null && (split.upper_slope > 0.0F || split.lower_slope > 0.0F
                    || split.upper_band_slope > 0.0F);
        }

        /**
         * Damage per millimetre of residual penetration for the vehicle's own table, for rounds
         * that name no profile of their own.
         *
         * <p>Absent means the global {@link Profile#damage_multiplier} applies, which is what every
         * vehicle written before this key existed gets. It is worth stating per vehicle when its
         * plates are not the usual shape - the BTR-3's thin aluminium screens are equally lethal
         * wherever a round gets through them, so all five faces are x5 rather than the global x50
         * on the front.
         */
        public DamageMultiplier damage_multiplier;

        /**
         * Engine power in metric horsepower, as the real vehicle is published. Zero means this
         * addon has no figure for the vehicle, and the HUD leaves the row out rather than saying
         * nothing useful about a tank.
         *
         * <p>It lives in the armour block because that block is this addon's one description of a
         * vehicle, not because it has anything to do with protection. The figure is the engine's
         * rating, not the power that reaches the sprocket: no drive-line loss is modelled, for the
         * same reason no source publishes one consistently enough to subtract.
         */
        public float engine_horsepower = 0.0F;

        /**
         * Power-to-weight ratio in horsepower per tonne, as the vehicle is published. Zero means
         * derive it from {@link #engine_horsepower} and the vehicle's own curb weight when both
         * are known - the pack's {@code physics_info.mass} is the weight the game actually moves,
         * and a ratio read off that is honest about the vehicle in front of the player even when
         * it disagrees with a brochure.
         *
         * <p>The two fields are separate rather than one derived from the other because published
         * ratios are quoted at a stated combat weight, and a pack's mass is not always that
         * weight. The VT-5 is the case in point: 1,000 hp at the 33-tonne basic weight is
         * 30.3 hp/t, while the pack's 42.9-tonne model would give 23.3. The published ratio is
         * the one shown, and the difference is the pack's mass, not the engine's.
         */
        public float horsepower_per_ton = 0.0F;

        /**
         * Optional weak spot around the gun on the turret's front plate. Absent means the
         * turret front is uniform armour.
         */
        public WeakSpot turret_weak_spot;

        /**
         * Optional weak spot on the hull's front plate - the machine-gun port.
         *
         * <p>Checked before {@link #hull_front_split}, because the port sits inside the glacis and
         * a hit through it is a hit through the port, not through the plate around it. Its offsets
         * are in blocks from the middle of the hull's front face, the height measured the same way
         * {@code split} is normalised: {@code +1} the top of the face, {@code 0} the middle.
         */
        public WeakSpot hull_weak_spot;

        /**
         * Armour for a hit on a named part unit, which beats every face value below.
         *
         * <p>Modern add-on armour is modelled as boxes in their own right - the T-90M's reactive
         * panels are five separate parts on the model - and a shot that hits a box has hit the box,
         * not the plate the box exists to keep it away from. Without this the only way to give that
         * armour any numbers would be to raise the turret's face values to match, which is wrong in
         * both directions: every hit on the flank between the boxes would be measured as though it
         * had gone through one, and there would be nowhere to say what the box is worth against a
         * different kind of round.
         *
         * <p>Keyed by the part unit's own id, the same ids {@link #turret_part_ids} lists - so the
         * only thing needed to find a box is what the model already says.
         */
        public Map<String, Plate> part_armor = new LinkedHashMap<>();

        /**
         * Optional upper/lower split of the hull's front plate. Absent means the hull front
         * is one plate, using {@code hull.front} and the front damage multiplier.
         */
        public FrontSplit hull_front_split;

        /**
         * Optional armour for the track ends on the hull's front and rear faces. Absent means
         * the running gear is covered by the normal hull armour.
         */
        public TrackEnds track_ends;

        /**
         * Armour of the road wheels, for rounds that do not name a profile of their own. Absent
         * means the flanks are one uniform plate, which is what a tracked vehicle wants.
         */
        public RoadWheels road_wheels;

        /**
         * Armour over the explosive-reactive panels on the hull's flanks, for rounds that do not
         * name a profile of their own. Absent means the flank is one uniform plate.
         */
        public Plate hull_side_era;

        /**
         * Multi-hit add-on armour on the hull's flanks, in front of {@link #hull_side_era} and the
         * flank plate. Absent means the flank has no such layer.
         *
         * <p>The plate is read for {@link Plate#addon_hp} and {@link Plate#addon_fractions}; its
         * thickness and multiplier are the scanner's and the tables' figures, not part of the rule.
         * One pool per vehicle: the layer is the tank's flank kit, not one box per hitbox, so the
         * three hits are shared by both sides.
         */
        public Plate hull_side_addon;

        /**
         * How much of the hull's length, measured from the nose, the reactive panels cover.
         * Geometry, so it is shared by every ammunition.
         */
        public float hull_side_era_front_fraction = 0.5F;

        /**
         * How obliquely a round has to arrive for the flank panels to count at all, in degrees away
         * from the panel's face. Zero - the default - means they always do.
         *
         * <p>Slat and cage armour works by catching a shaped-charge jet across the gaps rather than
         * letting it through them, so it is worth a great deal against a round crossing it at a
         * shallow angle and worth nothing against one arriving square on. Stating it as a threshold
         * rather than as a curve keeps it legible: below the angle the panels are simply not there,
         * and the hit is measured against the flank behind them, which is the honest description of
         * a screen with holes in it.
         *
         * <p>Geometry, so it belongs to the vehicle and not to the round - the panels and their
         * angle are how the tank is built. What they are worth to a given warhead is in
         * {@code overrides}.
         */
        public float hull_side_era_min_angle = 0.0F;

        /**
         * The deep-fording kit this vehicle carries, if it has one. Absent means it has none and
         * water stops it dead, which is what every vehicle the pack does not call amphibious does.
         *
         * <p>The block's presence is the switch, the same rule {@link #part_armor} and
         * {@link #overrides} follow - see {@link Fording.Kit}.
         */
        public Fording.Kit fording;

        /**
         * Where the suspension used to live, before it moved to {@link Profile#suspensions}.
         *
         * <p>Here only so a file written at v38 through v43 still parses: Gson fills this in from
         * the {@code suspension} key that used to sit inside each vehicle, {@link #adoptSuspensions}
         * moves it to the map, and the writer never emits it again because nothing ever sets it.
         * Nothing reads it, and nothing should.
         *
         * <p>It moved because the two questions are not the same question. Armour is what a vehicle
         * is worth to a round that hits it, and it has to be answered for every vehicle the addon
         * describes; a suspension is a piece of equipment the crew can work, and a vehicle can have
         * one while this addon has no opinion at all about its armour. Keeping the block here meant
         * the only way to give a BMP-3 an adjustable suspension was to also tell the addon that a
         * BMP-3's turret front is 480mm, which is a lie the armour tables would have repeated.
         */
        @Deprecated
        public Suspension.Settings suspension;

        /** Named armour sets for particular kinds of round. Keyed by an ammunition's profile. */
        public Map<String, ArmorOverride> overrides = new LinkedHashMap<>();

        /**
         * The part id of a manually loaded gun's loader seat, or null on an autoloaded vehicle.
         *
         * <p>This is the one crew role no calibre or weapon layout can derive: a tank with an
         * autoloader has a spare seat, and a tank with a manual gun has a seat somebody has to be
         * in. Naming it here keeps the answer with the vehicle's own description instead of
         * guessing from a part name, and it is what {@link CrewRoles} reads to give that seat the
         * {@link CrewRole#LOADER} role.
         *
         * <p>No loader seat means no loader penalty: {@code reload.time} stays the figure the pack
         * wrote. On a manual gun the pack's figure is the trained loader's cycle, and a missing or
         * incapacitated loader doubles it - see {@link CrewRoles#loaderReloadFactor}.
         */
        public String loader_seat_id;

        /**
         * Part-unit ids that belong to the turret. Any other part - and any hit that
         * misses every part unit entirely - is treated as hull armour.
         *
         * <p>Armour parts only. The manual loader's seat is crew, not an armour box, and is
         * treated as turret crew from {@link #loader_seat_id} by {@link CrewRoles}, so it does
         * not belong in this list.
         */
        public String[] turret_part_ids = {
                "turret",
                "turret_machine_gun",
                "turret_smoke_grenade",
                "commander_machine_gun"
        };

        /**
         * Part-unit ids whose occupant rides with the hull even though the station's structure
         * group turns.
         *
         * <p>The base mod derives every weapon station's rider position from the station's own
         * structure group, so a gunner or commander on a rotating turret turns with it. That is
         * right for a manned turret and wrong for an unmanned one: the ZTZ-100's three crew sit in
         * the hull, and its turret is a remote mount. Naming the stations here makes the rider
         * position use the vehicle frame instead, so the crew stays in the hull while the turret
         * traverses. The guns, bolts, sights and hitboxes still follow the turret - only the body
         * does not.
         *
         * <p>A null list means no station is hull-bound, which is every vehicle that does not
         * explicitly say otherwise.
         */
        public List<String> hull_seat_ids;

        /** @return whether the rider of this part stays in the hull while its group turns. */
        public boolean isHullSeat(String partId) {
            return partId != null && this.hull_seat_ids != null
                    && this.hull_seat_ids.contains(partId);
        }

        /**
         * The skin-and-component model of a fixed-wing aircraft, or {@code null} for every vehicle
         * that is not one.
         *
         * <p>A tank's protection is a plate: a round either beats the number on the face it struck
         * or it does not, and the answer is the same wherever on that face it landed. An aircraft
         * has no such plate. Its outer surface is a few millimetres of aluminium, plywood or fabric
         * that almost anything goes through, and what the round does afterwards depends on where it
         * goes inside: through the pilot, the engine or a fuel tank is lethal, through an empty
         * stretch of fuselage is a pair of holes.
         *
         * <p>So a hit is resolved in three layers, in the order the round meets them:
         * <ol>
         *   <li>{@link AircraftArmor#skin} - the covering, per face. Below its thickness nothing
         *       happens at all.</li>
         *   <li>{@link AircraftArmor#components} - boxes inside the airframe, tested along the
         *       round's path in distance order. A component may carry {@code armor}, which the
         *       round must beat before it reaches the component itself; the seat back and the
         *       windscreen are such boxes with no effect of their own.</li>
         *   <li>{@link AircraftArmor#frame_multiplier} - what a skin hit that reaches no
         *       component is worth, per face.</li>
         * </ol>
         *
         * <p>Wings and tailplanes are handled by {@link AircraftArmor#wing}: the same thin skin,
         * a lower structural multiplier, and no components behind it. A hit belongs to that branch
         * when its lateral offset from the hull box's centre exceeds {@code wing_from} - a distance
         * in blocks, measured on the hull's own x axis, so it can be checked against the model.
         *
         * <p>Absent means "this vehicle has no aircraft model" - every tank, and any aircraft not
         * yet adapted. Nothing else in the armour file changes for those.
         */
        public AircraftArmor aircraft;
    }

    /**
     * The aircraft model: a skin, a structural response, and the components behind the skin.
     *
     * <p>See {@link VehicleArmor#aircraft} for how a round is resolved. The numbers are
     * deliberately small - a fighter is not a tank - and the components are what make the model
     * worth having: a 7.92mm round through the fuselage is a scratch, the same round through the
     * cockpit is a wounded pilot, and a .50 through the engine is the engine.
     */
    public static final class AircraftArmor {

        /** The outer covering, per face, in millimetres. A round with less penetration bounces. */
        public ArmorSet skin = new ArmorSet(2.0F, 2.0F, 2.0F, 2.0F, 3.0F);

        /**
         * Damage per millimetre of residual penetration for a hit that crosses the skin and reaches
         * no component. The wing's own multiplier lives on {@link #wing}.
         */
        public DamageMultiplier frame_multiplier = new DamageMultiplier(2.0F, 2.0F, 2.0F, 1.5F, 2.5F);

        /**
         * Wing and tailplane skin, as one plate: thickness then structural multiplier. A hole in a
         * wing is cheaper than a hole in the fuselage, and there is nothing behind it to hit.
         */
        public Plate wing = new Plate(1.5F, 1.5F);

        /**
         * How far from the hull box's centre a hit has to be, along the hull's x axis, to count as
         * a wing hit. Blocks, not a fraction: the number is meant to be measured against the model
         * and compared with the fuselage's own half-width, which is what {@code AircraftCheck}
         * does.
         */
        public float wing_from = 0.52F;

        /**
         * The power loading this aircraft's acceleration factor is measured against, in horsepower
         * per tonne.
         *
         * <p>Same idea as the profile-wide {@code engine_reference_hp_per_ton} the ground vehicles
         * use, but per aircraft: a fighter at 400 hp/t and a bomber at 150 hp/t are both "normal"
         * for their class, and one global reference would give the bomber a permanent 0.2x. The
         * shipped 420 is the three fighters' own average, so their factors start near 1.0 and the
         * feature changes nothing until a figure moves.
         */
        public float engine_reference_hp_per_ton = 420.0F;

        /** Exponent on the ratio; 1 is the physical law, 0 switches the factor off (always 1.0). */
        public float engine_power_exponent = 1.0F;

        /** Boxes inside the airframe, tested in order along the round's path. Never null. */
        public List<AircraftComponent> components = new ArrayList<>();
    }

    /**
     * One volume inside an aircraft: a component, or an armour plate in front of one.
     *
     * <p>The box is stated in blocks from the centre of the vehicle's main cube ({@code
     * main_structure}), on that cube's own axes - x to the vehicle's right, y up, z toward the
     * nose. That is the same frame {@code ArmorSystem.hullLocalPosition} measures in, so a box can
     * be checked against the pack's own structure model without converting anything.
     *
     * <p>A component with no effect of its own ({@link #damage_multiplier}, {@link #damage} and the
     * crew fields all zero) is a pure armour layer: the round has to beat {@link #armor} to get
     * past it, and stops there with only the frame's damage if it cannot. The seat back and the
     * windscreen are written that way.
     */
    public static final class AircraftComponent {

        /** The name the hit log and the checks use, e.g. {@code pilot}, {@code engine}. */
        public String name = "component";

        /**
         * The part unit this component belongs to, or {@code null} for a component the model does
         * not make a part (the engine). A part that is a seat is not damaged itself - its occupant
         * is, through the crew fields - while any other part (the fuel tank) loses health with the
         * vehicle.
         */
        public String part_id;

        /** Centre of the box, in blocks, from the main cube's centre, on its axes. */
        public float[] center;

        /** Half-extents of the box, in blocks. */
        public float[] half;

        /**
         * Millimetres of armour the round must beat before it reaches this component. Zero for a
         * component's own skin (the engine block is written with a small figure instead), and the
         * whole of a pure armour layer.
         */
        public float armor = 0.0F;

        /** Damage per millimetre of residual penetration after the armour above. */
        public float damage_multiplier = 0.0F;

        /** Flat damage added when this component is reached, whatever the residual. */
        public float damage = 0.0F;

        /** Flat damage to the occupant of {@link #part_id}'s seat, when it has one. */
        public float crew_damage = 0.0F;

        /** Further occupant damage per millimetre of residual penetration. */
        public float crew_damage_per_mm = 0.0F;

        public AircraftComponent() {
        }

        /** @return whether reaching this box does anything besides getting in the way. */
        public boolean hasEffect() {
            return damage_multiplier > 0.0F || damage > 0.0F
                    || crew_damage > 0.0F || crew_damage_per_mm > 0.0F;
        }
    }

    /**
     * Bumped whenever a new release introduces config keys that an existing file lacks.
     * A file written before versioning reports {@code 0} and is upgraded in place.
     *
     * <p>v38 -> v39: the M1296 gained a dart, which is one more entry in {@code ammo_penetration} -
     * the loop that copies in a key the file does not have yet, the same one every round added since
     * v9 has arrived through. Nothing is moved or rewritten, so a player who has rated the Dragoon's
     * rounds themselves keeps both of their figures and only gains the one they never had.
     *
     * <p>v39 -> v40: the two dual-feed 30mm cannons became belts that can each be loaded with either
     * round, which is a load list on four entries that already existed. The v21 -> v22 loop fills a
     * load list wherever the file has none, so nothing new is needed here - only the bump, or that
     * loop is never reached and both guns stay single-round for ever.
     *
     * <p>v40 -> v41: and then those belts became groups of two real weapons instead, switched with
     * the multi-weapon key, so that each round keeps its own projectile and - the point of the whole
     * change - its own explosion. That makes the four lists v40 added not merely redundant but wrong,
     * and this is the first version whose migration takes something away: see
     * {@link #dropShippedLoad}.
     *
     * <p>v41 -> v42: the Tiger E (Tiger I, early hull) was added - three new entries in
     * {@code ammo_penetration} (PzGr 39 / PzGr 40 / Sprgr 43) and one in {@code vehicles}. The
     * v9-ammo and v36-vehicle fill loops carry both, so this is the same shape as v38 -> v39.
     *
     * <p>v42 -> v43: the 0.6.1 vehicle pack cut every hull collision box back so the wheels stand
     * below it rather than inside it, which moved the two {@code road_wheels.top} figures - they are
     * fractions of that box. The vehicles are unchanged; the ruler is not. See
     * {@link #roadWheelRegrade}.
     *
     * <p>v43 -> v44: the suspension moved out of each vehicle's armour block and into a map of its
     * own, and six vehicles that really do have a hydropneumatic suspension were given one. This is
     * the one migration here that <b>relocates</b> rather than adds or moves a value: every file
     * written since v38 carries {@code stryker_m1296.suspension}, and leaving it there would leave
     * the Dragoon as the only vehicle in the game with working keys. See {@link #adoptSuspensions}.
     *
     * <p>v44 -> v45: {@code tacz_rounds}, the penetration figures for the gun mod's cartridges. A
     * whole new map, so this is the plainest shape there is - a loop that copies in every key the
     * file does not have, exactly like v9 -> v10 and v38 -> v39 - with one difference worth stating:
     * a file written before this version has no {@code tacz_rounds} key at all, so the fill has to
     * create the map as well as fill it.
     *
     * <p>v45 -> v46: the VT-5 from the Armor Pioneer pack - one new {@code vehicles} entry
     * ({@code armor_pioneer:vt5}) and two new rounds (its 105mm dart and 35mm grenade). The vehicle
     * loop adds the profile whole and the ammunition loop adds both rounds, so this is the
     * v22 -> v23 shape; the bump is only what makes a file already written at v45 look for them.
     *
     * <p>v46 -> v47: the VT-5's 105mm became a family - HEAT, HE and the GP-105 gun-launched
     * missile alongside the dart, and two older dart marks in the load chooser - and its two
     * machine guns moved onto pack-local ids so they can be rated. The new weapon ids arrive
     * through the ammunition loop; the two dart marks arrive through the v21 -> v22 loop, which
     * fills any entry whose list is empty. The base dart's figure is not touched.
     *
     * <p>v47 -> v48: the VT-4, the Armor Pioneer pack's second vehicle - one new
     * {@code vehicles} entry ({@code armor_pioneer:vt4}) and three new rounds (its 125mm dart,
     * shaped charge and high-explosive shell). Same shape as v45 -> v46: the vehicle loop adds
     * the profile whole and the ammunition loop adds the rounds, so the bump is only what makes
     * a file already written at v47 look for them.
     *
     * <p>v48 -> v49: the VT-4's 125mm was completed to the six rounds Norinco displays with the
     * tank. Two are new weapon ids (the BTS8 lateral-effect round and the GP125 missile) and
     * arrive through the ammunition loop; the DTC10E is a second mark of the dart slot, which
     * arrives through the v21 -> v22 loop because that slot had no load list. The BTA4's figure
     * is not touched - the newer round is the alternative, not the default.
     *
     * <p>v49 -> v50: the K2 Black Panther - one new {@code vehicles} entry
     * ({@code armor_pioneer:k2}) and three new rounds (its 120mm dart, shaped charge and
     * high-explosive shell). Same shape as v47 -> v48.
     *
     * <p>v50 -> v51: the K2's commander weapon became an M2HB under an id of its own
     * ({@code armor_pioneer:gun_12_7mm_m2hb}) instead of sharing the VT-5's QJC-88. It arrives
     * through the ammunition loop; no existing figure moves.
     *
     * <p>v51 -> v52: the K2's 120mm slot gained the imported DM53 as a second dart mark (through
     * the v21 -> v22 variant loop) and the gun-launched KSTAM-II as a weapon id of its own
     * (through the ammunition loop). The K279 remains the default load; no figure moves.
     *
     * <p>v52 -> v53: the K2 gains the adjustable suspension its real in-arm hydropneumatic system
     * has. It arrives through the generic {@code suspensions} fill in {@code adoptSuspensions}, so
     * a file already written at v52 gets the block without any per-vehicle code; what the bump
     * announces is the new default. The same version introduces {@code actuationRate}: a press no
     * longer jumps the rest lengths to their new value in one tick, and the existing configs get
     * the default rate because an absent field reads as its initialiser.
     *
     * <p>v53 -> v54: the K2's track strip moves inboard with the link-model track. Its structure
     * track path moved onto the display loop (x +-1.704, y 0.196..1.581, z -3.799..4.711) so the
     * TrackUnit link model sits on the wheels, and the shipped {@code track_ends.inner_edge} 1.56
     * was derived from the old, outboard path - it would miss the inner 0.18 of the link. Only that
     * shipped figure moves; a hand-edited inner edge is left alone.
     *
     * <p>v54 -> v55: the K2's model-specific track floating offset and its five-degree pitch. A
     * v54 file written before those changes has the suspension block at {@code max_pitch} 2.0 and
     * no {@code trackCenterOffset}; the upgrade fills the 0.0654 offset and opens only the shipped
     * 2.0 to 5.0. The local pack already carries 0.50 of suspension travel, which is what the
     * 0.450 the furthest wheel needs at five degrees. The track path's seam also moved to a
     * straight run so the link model is not sampled starting on the front sprocket curve; that is
     * a pack change, not a config field.
     *
     * <p>v55 -> v56: the K2 gains the lateral axis its in-arm suspension has, and the local pack
     * opens its travel from 0.50 to 0.60 to pay for it. An older file has no {@code max_roll} at
     * all - Gson leaves it at zero, which is the old behaviour - so the upgrade fills only the
     * shipped zero with the four degrees the K2 can really work; a vehicle the addon does not give
     * a lateral axis still reads zero, and a figure a player has typed is left alone.
     *
     * <p>v56 -> v57: the VT-5's 105mm family was renamed to the rounds on the Zhuhai display
     * stand, and the dart's figures were corrected with them - the BTA-2 is quoted at 550mm and
     * the DTW-2, its one older load, at 450. The shipped 500mm dart and the shipped two-load
     * placeholder list are moved only where the file still holds them, so a player who has rated
     * the round or written their own list keeps it. The BTM1 bunker round is a new weapon id and
     * arrives through the ammunition loop above.
     *
     * <p>v57 -> v58: the three Armor Pioneer vehicles gained an engine and a smoke-grenade bank.
     * The engine is two new fields, {@code engine_horsepower} and {@code horsepower_per_ton}, which
     * an older file reads as zero; only the shipped zero is filled, so a player who has rated an
     * engine keeps their figure. The smoke launcher is a new weapon part hanging off the turret,
     * so its id has to join each vehicle's {@code turret_part_ids} or a hit on the launcher is
     * measured as hull. A hand-written list is kept with the new id appended; a list that is
     * absent is replaced whole, because a file with no list at all has no opinion to preserve.
     *
     * <p>v58 -> v59: the engine figures stop being a readout. {@code engine_reference_hp_per_ton}
     * (25.0) and {@code engine_power_exponent} (1.0) are new profile-wide fields that turn hp/t into
     * an acceleration multiplier; a v58 file reads the initialisers and the bump rewrites it so the
     * two knobs appear. An engine figure a player has edited is not touched, and no vehicle's speed
     * or armour value moves.
     *
     * <p>v59 -> v60: the Type 10 and the Tianma-20, the two vehicles the pack gained after the K2.
     * Two new {@code vehicles} entries and six new rounds - the Type 10's own 120mm APFSDS and
     * JM12A1, and the Tianma's 125mm APFSDS / BK-29 / HE plus the AGS-30 - because the two tanks had
     * been drawing the K2's and the VT-4's weapon ids and the HUD named another country's
     * ammunition. Same shape as v47 -> v48: the vehicle loop adds both profiles whole and the
     * ammunition loop adds the six rounds, so the bump is only what makes a file already written at
     * v59 look for them. No existing figure moves.
     *
     * <p>v60 -> v61: the Type 10 gains the K2's ranged hydropneumatic suspension. It is one new key
     * in the {@code suspensions} map, so the generic fill in {@code adoptSuspensions} adds it whole
     * and no per-vehicle migration is needed; what the bump announces is the new default. The pack
     * raises the Type 10's suspension travel to 0.60 to pay for the height and the five-degree
     * pitch, the same raise the K2's local pack carries. No armour, engine or speed value moves.
     * The same release fixes the APS intercept sweep, which is code rather than config.
     *
     * <p>v61 -> v62: the Type 10 and the Tianma-20 gain smoke-grenade banks, from the marked
     * {@code smoke} bones the user added to their display models. Each is a new
     * {@code turret_smoke_grenade} part in the pack, so its id has to join the vehicle's
     * {@code turret_part_ids} - the same append {@code enginesAndSmoke} already does for the first
     * three, extended to these two. The row count becomes per-vehicle (the Type 10's eight tubes
     * are two rows, every twelve-tube bank is three), which is code rather than a config key. No
     * armour, engine or suspension value moves.
     *
     * <p>v62 -> v63: the Type 10 gains the K2's lateral axis. The v61 block gave it the K2's
     * height bracket and five-degree pitch but left {@code max_roll} at zero, so the left and
     * right keys did nothing on the tank the user asked to have made "the same as the K2". The
     * field already exists in a v61 file, so the generic fill in {@code adoptSuspensions} cannot
     * see the difference; {@link #openType10Lean} opens only the shipped zero and leaves a range
     * a player typed alone. The pack already carries the 0.60 of travel the four degrees spend,
     * so no pack value moves, and no armour, engine or speed value moves either.
     *
     * <p>v63 -> v64: the Type 10's armour table. The vehicle was given figures that were in places
     * <i>above</i> the 55-tonne K2's - turret side 120 against 100, hull side 90 against 80, and a
     * chemical turret front of 1200 against 1150 - which is the opposite of the trade the tank is
     * built on, and contradicted this file's own note that the Type 10 sits between the VT-4 and
     * the K2. {@link #regradeType10} moves each shipped figure to the corrected one, face by face,
     * and leaves any value a player has typed. The three tables move together: the kinetic set, the
     * {@code heat} override and the {@code he} override built from it by {@link #thinRoofs}.
     *
     * <p>v67 -> v68: the Leopard 2A4's fourth seat is a manual loader. {@code loader_seat_id} is a
     * new field on the vehicle block and reads as null in an older file; {@link #adoptLoaderSeats}
     * fills only that null. The loader is treated as turret crew from that field rather than from
     * {@code turret_part_ids}, because the seat is a crew station and not an armour box. The reload
     * itself is unchanged in the pack - {@code reload.time} stays the trained loader's 6 s - and the
     * penalty for a missing or wounded loader is applied at reload time, not written into the
     * config.
     *
     * <p>v68 -> v69: the VT-5 and the VT-4 stop drawing their tracks as baked-in plates and use the
     * same TrackUnit link belt as the K2 and Type 10. Both structure track paths were stale - the
     * VT-5's sat 0.5 blocks outboard of, and on the opposite side from, its display treads, and the
     * VT-4's likewise - so they were rebuilt onto the display loop and the shipped
     * {@code track_ends.inner_edge} 1.19, derived from the old outboard path, would start well
     * outside the real track. Only those two shipped figures move (to 0.87); a hand-edited edge is
     * left alone, and the ZTZ-100's derived figure happened to be unchanged, so its shipped 1.175
     * stays. This is a pack change plus these two figures - no new field.
     *
     * <p>v69 -> v70: reactive armour stopped being a kind and became a number. The old
     * {@code "era": "light"|"heavy"} key chose between two rules - light let a tandem round through
     * at two thirds, heavy stopped it once - and neither said how many millimetres a panel was
     * worth. The new {@code era_hp} is exactly that number, taken off the one round that sets the
     * panel off; the panel is then spent and the armour behind it is measured with what is left.
     * {@link #convertReactiveKinds} turns every old string into an HP equal to the panel's own
     * thickness, and {@link #adoptReactiveHp} fills a missing HP from the defaults. The VT-5 also
     * gains a real `hull_era` box in this release, which is the new part in the pack and the
     * {@code part_armor} entry the defaults carry.
     *
     * <p>v70 -> v71: an unmanned turret's crew stay in the hull. {@code hull_seat_ids} is a new
     * list on the vehicle block and reads as null in an older file; {@link #adoptHullSeats} fills
     * only that null. The ZTZ-100 is the vehicle that ships it - its gunner and commander sit
     * behind a remote mount, and the base mod derives every weapon station's rider position from
     * the station's own rotating group. Nothing else moves: the guns, bolts, sights and hitboxes
     * still follow the turret, and every other vehicle keeps the base behaviour.
     *
     * <p>v71 -> v72: the hatch periscope. {@code periscopes} is a new map, so an older file has no
     * key for it at all; {@link #adoptPeriscopes} creates it and adds the shipped entries, and only
     * where the file has none for that vehicle. The VT-5's commander station is the first entry:
     * a camera point measured on the structure model, from the part's own pivot, in the frame the
     * pack's {@code operator_view_offset} already uses. The map is separate from
     * {@link VehicleArmor} because a view is equipment a crew member uses, not a statement about
     * what a round does to the hull - the same reason {@code suspensions} and {@code hybrids} have
     * maps of their own.
     *
     * <p>v72 -> v73: the VT-5's commander and gunner seats were re-adapted to the corrected display
     * model. The commander is now on the left and the gunner on the right, the hatches follow
     * (commander {@code turret_door}, gunner {@code turret_door2}), and the periscope moves from
     * the old right hatch to the outer-left corner of the left one. A v72 file carries the old
     * shipped point (1.3475, 0.2036, 0.7807); {@link #moveVt5Periscope} moves exactly that triple
     * and leaves a hand-measured point alone. The pack side of the same change lives in
     * {@code vt5.json} and {@code vt5_controller.json} rather than here.
     *
     * <p>v73 -> v74: TACZ 1.2.0 added a .300 Blackout cartridge. A new key in a map that already
     * exists, so the same "fill only what is missing" loop below carries it, and a file where the
     * player has already rated that round keeps their figure.
     *
     * <p>v74 -> v75: the VT-4's hull and turret ERA became real parts. The user's display model now
     * marks four panels - `hull_era`, `hull_era2`, `turret_era` and `turret_era2` - and the
     * structure model carries a box for each. VT-5's `hull_era` is the model for this: a box
     * bolted to the outside of the tank, single-use, with `era_hp` taken off the one round that
     * sets it off. The VT-4's chemical front and side figures were written before there were tiles
     * to spend, so they were the ERA-inclusive totals. v75 was an interim split that put 450 on the
     * hull panels and 150 on the turret panels in the chemical overrides only.
     *
     * <p>v75 -> v76: the panels are FY-4E on both the hull and the turret, and the user's figure
     * for them is 200mm - so all four are now the same `Plate(200, 1).reactiveHp(200)` in the
     * vehicle's own `part_armor`, which is where VT-5's FY-4E box lives. That makes the panel a
     * layer against darts as well as shaped charges: 200 off a kinetic round before the 600/180
     * kinetic table, and 200 off a chemical round before the behind figures below. The four
     * chemical faces move from v75's 900/150/600 to 850/100/850, so the old 1050/1050/300 totals
     * are still what an intact panel adds up to (200 + 850, 200 + 100, 200 + 850). The v75 panel
     * entries are removed from `heat` and `he` where they still hold the shipped 450/150, which
     * lets the base 200 apply; a panel the player has given values of their own stays, because an
     * override is the player's statement about that round. Only shipped figures move, so a
     * hand-edited table keeps its values; the four boxes arrive through `adoptMissingBoxes`, and
     * the two turret parts are appended to `turret_part_ids` by {@link #vt4EraPanels}.
     *
     * <p>v76 -> v77: the VT-4's hull front gains its two outer bands. The user drew four zones on
     * the front of the display model: the glacis' top strip is a forced-ricochet face at 800, the
     * rest of the glacis keeps its 600, the main belt's extension below the nose is 480, and the
     * rest of the lower plate keeps its 180. {@code upper_band} and {@code lower_band} are new
     * optional plates on {@code hull_front_split}, each with its own edge, so a vehicle that states
     * neither keeps the two-zone face it always had; {@link #vt4HullBands} copies them into a VT-4
     * block only where it has none. The chemical table is not touched - the user's figures are
     * kinetic figures, and every chemical face keeps the value v76 shipped.
     *
     * <p>v77 -> v78: the Type 10's 120mm APFSDS is 600mm, on the user's call, where it shipped at
     * 650. Only the base figure moves, and only where the file still holds the shipped 650; the
     * JM33 load it can be swapped for is a separate field and stays at 480.
     *
     * <p>v78 -> v79: vanilla Minecraft's own weapons meet the armour table. {@code vanilla_weapons}
     * is a new profile-wide block, so a file older than this version has nothing to preserve and
     * receives the whole of it; a file that already carries the block keeps every figure in it.
     * What it does is in {@link VanillaWeapons}.
     *
     * <p>v79 -> v80: the Tianma-20's three reactive panels. The user's display model now marks two
     * hull-side skirt runs and a turret-roof slab as separate parts, and the structure model
     * carries a hitbox for each. They are flanks and roof, not the frontal arc whose ERA was
     * already folded into the front figures, so no existing number moves - the panels are an
     * additional single-use layer. All three are 200mm, the VT-4's FY-4E figure, since nothing
     * about this vehicle's ERA is public. The boxes arrive through {@link #adoptMissingBoxes};
     * {@link #tianmaEraPanels} adds the roof panel to the turret list, which a file already
     * carrying that list would otherwise never hear about.
     *
     * <p>v84 -> v85: the coaxial and commander machine guns are named per vehicle. The pack's
     * shared 7.62mm id could only carry one name, so the Type 10, K2 and Cheonma-20 each get a
     * weapon id of their own - {@code type74_7_62mm}, {@code m60e2_7_62mm},
     * {@code pkt_7_62mm} - and the K2's commander K6 gets {@code k6_12_7mm}. The Chinese
     * vehicles keep {@code gun_7_62mm} (the Type 86) and the Type 10 keeps
     * {@code gun_12_7mm_m2hb} (the Type 90). All four are new keys, so the ammunition loop at
     * the top of {@link #upgrade} adds them whole; no existing figure moves.
     *
     * <p>v85 -> v86: aircraft stop being tanks. The {@code tank_assault} pack's three fixed-wing
     * vehicles - the Bf 109E-4, the Fw 190A-5 and the P-51D-20 - are adapted with the new
     * {@code aircraft} block: a per-face skin, a wing plate, and the components behind the skin
     * (pilot, engine, fuel tank) together with the local armour in front of them (seat back,
     * windscreen). The three vehicles and the four aircraft guns are new keys, so the vehicle and
     * ammunition loops at the top of {@link #upgrade} add them whole. No existing vehicle or round
     * moves. The block is optional and null on every tank, so a file that does not have this
     * version sees no change at all.
     *
     * <p>v88 -> v89: the three aircraft gained their engine ratings and a power-to-weight factor.
     * {@code engine_horsepower} / {@code horsepower_per_ton} are the same two fields the ground
     * vehicles use (1100 / 407.4, 1700 / 425, 1490 / 430), filled only where the file still holds
     * zero; the two new {@code aircraft.engine_reference_hp_per_ton} (420) and
     * {@code aircraft.engine_power_exponent} (1.0) are per-aircraft knobs, so a fighter and a
     * bomber can each be measured against their own class. The factor multiplies the pack's
     * {@code thrust}; with the shipped reference the three start at x0.97 / x1.01 / x1.02, which is
     * to say the feature is visible in the HUD and changes no shipped handling. A player who has
     * rated an engine keeps their figure.
     *
     * <p>v91 -> v92: the Panther's kinetic table stops stating line-of-sight figures. Its 80mm
     * glacis at 55 degrees was written as the 140mm it presents from dead ahead, which made every
     * hit - from the front, from 45 degrees off, from anywhere - meet the same plate. The table
     * now holds the real plate thicknesses and the vehicle carries {@code hull_slopes} /
     * {@code turret_slopes}, so the damage path works out the equivalent for the direction the
     * round actually arrived from. Only the figures this addon shipped move; a hand-edited face
     * stays, and a file that already states slopes keeps them. The chemical table was already in
     * nominal thicknesses and does not move; slopes are kinetic-only, so it is not measured
     * against them either.
     *
     * <p>v92 -> v93: the VT-4 gains its direction model. Its tables are equivalents - a composite
     * array is published as the protection it gives along the front, obliquity included - so the
     * vehicle cannot state a plate slope without counting that lean twice. The new
     * {@code kinetic_angle_scaling} says instead that a kinetic round's deviation from the face's
     * normal should matter against the figure as written. Only the switch is added where the file
     * has none: no thickness moves, and a file that has already answered the question keeps its
     * answer. Reactive panels stay outside the factor - they are layers with their own HP, not
     * plates seen at an angle.
     *
     * <p>v93 -> v94: the VT-4's ricochet band stops being an equivalent. The strip above the
     * glacis is the near-horizontal face that exists to turn frontal rounds, and the flat 800 the
     * file held measured it as if it were a vertical slice of the glacis - so a plunging shot met
     * more of it than a frontal one, which is backwards. It is now the steel it is: 125mm at the
     * 81 degrees measured off the display model's tile rows, which is 799mm from dead ahead and
     * 384mm from ten degrees above. Only the shipped 800 moves, and a hand-set band thickness
     * stays; the band's own slope is a new field, filled where the file has none.
     *
     * <p>v94 -> v95: a second pack, {@code wks_6} (the War of Resistance Carrierpack's Vickers
     * 6-Ton), joins the defaults with one vehicle and its four rounds. Both are new keys, so both
     * arrive through the loops above and nothing needs a branch of its own - but a file already
     * written at v94 only reaches those loops through a version change, which is what this bump is
     * for. The vehicle has no reactive panels, no add-on boxes and no slopes, so there is nothing
     * for {@code inherit} to fill in either.
     *
     * <p>v95 -> v96: the Panther's armour-piercing round and the VT-4's two darts gain a
     * penetration-against-range curve. The curve is a new field on an existing round, so it is
     * filled only where the file has none, and it is anchored to whatever muzzle penetration that
     * round still holds - a hand-tuned round keeps its figure and the published shape together.
     * The profile also gains {@code ricochet_angle}, whose default arrives through the field
     * initialiser, so no existing value has to move for it.
     *
     * <p>v96 -> v97: the K2 joins the VT-4's direction model and the dart range curves. Its
     * {@code kinetic_angle_scaling} is a new switch, filled where the file has none; its 120mm
     * slot and the DM53 load gain the same long-rod curve as the VT-4's darts, filled only where
     * absent and anchored to each mark's own muzzle figure. No thickness moves.
     *
     * <p>v97 -> v98: the Tiger E joins the real-thickness model. Its tables already held the
     * plates - 100/80/80/25 - so what moves is the gun mount's circle (100 to the 120mm mantlet)
     * and what arrives is the geometry: the hull's rear plate at 9 degrees, the driver's plate at
     * 9 and the lower nose at 25, stated on the split's two plates because they lean differently.
     * The 8,8 cm PzGr 39 and PzGr 40 also gain their range curves, filled where absent.
     *
     * <p>v98 -> v99: vanilla explosions meet the armour table. {@code vanilla_explosions} is a new
     * profile-wide block, added whole to an older file - a blast has no penetration concept, so
     * every figure in it is new. It cannot be folded into {@code vanilla_weapons}: a blast has no
     * travelling round, and the blast's centre has to be captured separately by
     * {@code ExplosionMixin}. What it does is in {@link VanillaExplosions}.
     *
     * <p>v99 -> v100: collisions meet it too. {@code vehicle_collisions} rates the base mod's
     * {@code vehicle_collision} damage and adds the short crew shock; {@code vehicle_ramming}
     * gives the Armor Pioneer vehicles a speed/mass-based block-crush path on top of the base
     * mod's stuck grind. Both blocks are new, so an older file receives them whole; both ship
     * with the seven Armor Pioneer ids and leave every other vehicle on the base behaviour.
     *
     * <p>v100 -> v101: the ram stops eating the ground. {@code protect_natural_ground} and
     * {@code natural_ground_blocks} are two new fields inside the existing
     * {@code vehicle_ramming} block: the first ships on, the second empty. An existing block gains
     * both without any other figure moving, so a hand-tuned curve or scope list survives.
     *
     * <p>v101 -> v102: the KSTAM-II becomes a top-attack round. Its three new
     * {@code top_attack_*} fields are nullable and absent from a v101 file, so the upgrade fills
     * only the ones that are missing - a player who has tuned the standoff keeps it. Every other
     * ammunition entry stays an ordinary direct hit because the fields stay null.
     *
     * <p>v102 -> v103: the KSTAM detonates one block above the roof instead of four. The standoff
     * and its floor move only where the file still holds the shipped 4.0 / 1.5, so a value a
     * player has tuned is left alone.
     *
     * <p>v103 -> v104: reactive armour gains separate chemical and kinetic ratings and an angle
     * falloff. The VT-4's four FY-4E panels are filled where they still carry the shipped 200mm
     * HP and no split rating; every other panel keeps {@code era_hp} and behaves exactly as
     * before, so this is a change to the one vehicle whose panels have published threat-specific
     * roles rather than to the whole table.
     *
     * <p>v104 -> v105: the K2's K279 / DM53 and the VT-4's BTA4 / DTC10E state a mass, a muzzle
     * velocity, exponents and a caliber, which opts them into the debris model of
     * {@link SpallModel}. The fields are filled only where the round still holds the shipped
     * penetration, so a round a player has rated themselves keeps the old residual formula until
     * its owner adds the fields. The {@code spall} block is new and arrives whole.
     *
     * <p>v105 -> v106: the K2's 120mm HEAT / HE, the VT-4's 125mm HEAT / HE and the KSTAM's EFP
     * state their chemical inputs - a jet's rated damage and a shell's charge - which opts them
     * into {@link ChemicalModel}. The {@code chem} block arrives whole; the per-round fields are
     * filled only where the round still holds its shipped penetration.
     *
     * <p>v106 -> v107: the VT-4's BTS8 lateral-effect round states its projectile and opts into
     * {@link PeleModel}, and the {@code penetration_bonus_*} band it used to roll is retired -
     * only on an entry that still holds the shipped band, so a round whose band a player tuned
     * stays exactly where it was. The {@code pele} block arrives whole.
     *
     * <p>v107 -> v108: the Tiger's PzGr 39, PzGr 40 and Sprgr 43 state their projectiles. The
     * PzGr 39 opts into the debris model with a base charge ({@code charge_kg}) whose burst is
     * added inside the compartment - the APHE effect - and its {@code penetration_bonus_*} band is
     * retired on the shipped entry only. The Sprgr 43 moves from a flat {@code penetration_damage}
     * plus a spall band to {@code charge_kg}; the {@code chem} block gains
     * {@code aphe_confinement}, which an older file picks up from the field's own initialiser.
     *
     * <p>v108 -> v109: the rest of the Armor Pioneer fleet states its projectiles and charges -
     * the VT-5 / ZTZ-100 105mm family, the Type 10's and the Leopard 2A4's 120mm family, and the
     * Tianma-20's 125mm. Kinetically that is the darts and their loads; chemically it is the HEAT
     * rounds (including the Type 10's proximity model) and the three HE shells, each guarded by
     * its own shipped penetration.
     *
     * <p>v109 -> v110: the same five vehicles take {@code kinetic_angle_scaling}, the per-hit
     * direction factor the K2 and the VT-4 have had since v97 and v93. Their tables are composite
     * equivalents published along the nose, so nothing but the switch moves.
     */
    private static final int CONFIG_VERSION = 110;

    /** Root of the JSON config file. */
    public static final class Profile {
        /** 0 means the file predates versioning; see {@link #CONFIG_VERSION}. */
        public int config_version = 0;

        /**
         * When a weapon's own part unit has been shot down to zero health it can no longer
         * fire. Nullable so an older config file - which has no such key at all - can be told
         * apart from one where the user deliberately turned it off.
         */
        public Boolean destroyed_parts_cannot_fire;

        /**
         * Show the armour of whatever the armour scanner is pointed at. Nullable for the same
         * reason as {@link #destroyed_parts_cannot_fire}.
         */
        public Boolean scanner_shows_armor;

        /** Damage per millimetre of residual penetration, per armour face. */
        public DamageMultiplier damage_multiplier = new DamageMultiplier();

        /**
         * When true the nominal thickness is divided by cos(impact angle) against the struck
         * box's own face, so oblique hits are easier to penetrate (War Thunder style). Off by
         * default so that a perpendicular hit always yields exactly {@code (pen - thickness) *
         * multiplier}.
         *
         * <p>This is about the hitbox being a box: the round arrives off the face's own normal.
         * A plate that really leans - see {@link VehicleArmor#hull_slopes} - is handled by the
         * vehicle's geometry and does not need this switch; a vehicle with slopes is measured
         * against the tilted normal whether this is on or off. The switch therefore stays off
         * for every vehicle whose tables still state line-of-sight figures.
         */
        public boolean angle_normalization = false;

        /**
         * The angle, in degrees from the plate's own normal, at which a round is turned away
         * whatever its penetration. Zero disables the rule.
         *
         * <p>Measured against the plate the round actually met - a sloped plate's tilted normal,
         * not the vertical face of its hitbox - so a near-horizontal ricochet strip really does
         * throw off a plunging shot while a frontal one meets it square. Applied to every kind of
         * round: a hard projectile, a shaped-charge jet and a high-explosive shell can all be
         * turned away by a shallow enough angle, and the sources give 70 to 75 degrees from the
         * normal as the band in which they do.
         *
         * <p>Seventy is the default because it is the usual figure for a capped armour-piercing
         * shell; it is a profile-wide number rather than a per-round one because the difference
         * between the kinds is small next to the difference between a hit and a miss, and one
         * number a player can find and change is worth more than three they cannot.
         */
        public float ricochet_angle = 70.0F;

        /** Keyed by the vehicle's vehicle id, or by its display id for re-skinned variants. */
        public Map<String, VehicleArmor> vehicles = new LinkedHashMap<>();

        /**
         * The power-to-weight ratio the engine effect is measured against, in horsepower per tonne.
         *
         * <p>A vehicle at this ratio keeps the pack's acceleration. Heavier or less powerful ones
         * fall below it and accelerate more slowly; the Armor Pioneer light tank at 30.3 hp/t is
         * 21% above it at the shipped 25. See {@link #enginePowerScale}.
         *
         * <p>Twenty-five is the VT-4's published ratio, so the pack's middle tank is the reference
         * and no vehicle is slower than it was before the effect existed. Lower the reference to
         * make the effect stronger; set it to zero and the accessor falls back to 25.
         */
        public float engine_reference_hp_per_ton = 25.0F;

        /**
         * The exponent on the ratio in {@link #enginePowerScale}. One is the physical law -
         * acceleration is proportional to power per unit mass - and zero turns the effect off
         * (everything gets a factor of 1.0). The field exists so the feel can be tuned without a
         * rebuild; values above one exaggerate the difference, below one flatten it.
         */
        public float engine_power_exponent = 1.0F;

        /**
         * The vehicles whose crew can work an adjustable suspension, keyed by vehicle id.
         *
         * <p>A map of its own rather than a block inside {@link VehicleArmor} - see the note on the
         * deprecated field there. An entry here says one thing and only one thing: this vehicle has
         * springs the crew can command. It says nothing about armour, which is why a vehicle can
         * appear here and nowhere in {@link #vehicles}.
         *
         * <p>What it must not be is a list of vehicles that look like they ought to have one. The
         * mechanism lengthens the rest length of every {@code ywzj_vehicle:suspension} part the pack
         * gives the model, so an entry on a vehicle whose model has no such part answers both keys
         * and moves nothing - and the travel it can actually use is the pack's {@code max_droop},
         * which {@code SuspensionCheck} reads back and holds these figures against.
         */
        public Map<String, Suspension.Settings> suspensions = new LinkedHashMap<>();

        /**
         * The vehicles whose crew can work a hybrid-electric drive, keyed by vehicle id.
         *
         * <p>Presence is the switch, exactly as it is for {@link #suspensions}: an entry says this
         * vehicle has a battery and an electric mode, and says nothing about its armour. The
         * shipped entry is the ZTZ-100's, and {@code HybridCheck} holds its figures.
         */
        public Map<String, Hybrid.Settings> hybrids = new LinkedHashMap<>();

        /**
         * The vehicles whose crew have a hatch periscope, keyed by vehicle id.
         *
         * <p>A map of its own, like {@link #suspensions} and {@link #hybrids}: a periscope is a
         * crew station's view, not a statement about armour. An entry says one thing only - this
         * seat has a periscope, at this point on the structure. The shipped entry is the VT-5's
         * commander station; everything else answers "no periscope" and leaves its views alone.
         */
        public Map<String, Periscope.Settings> periscopes = new LinkedHashMap<>();

        /** Keyed by the weapon id carried by the projectile. */
        public Map<String, Ammo> ammo_penetration = new LinkedHashMap<>();

        /**
         * The same figures for TACZ's cartridges, keyed by the ammunition id rather than the gun.
         *
         * <p>A table of its own rather than more entries in {@link #ammo_penetration}, because the
         * two id spaces mean different things and only one of them is a weapon. Every entry in
         * {@code ammo_penetration} is keyed by a <em>weapon</em> - {@code vvp_ywzj:2a46m5_125mm_apfsds}
         * is a gun's barrel and its round together, which is right for a tank where the two never
         * come apart. TACZ's rounds are keyed by the cartridge, because that is what decides what a
         * bullet does to a plate: {@code tacz:762x39} out of a short AK and out of a long RPK is the
         * same hole in the same steel, and only one of those is worth a config entry.
         *
         * <p>Ammunition absent from this table is left entirely alone - TACZ's own damage applies,
         * exactly as it did before this addon had an opinion. That is the fallback for every gun pack
         * a player adds later, and it is the right one: a round this addon has never rated must not
         * quietly become harmless.
         */
        public Map<String, Ammo> tacz_rounds = new LinkedHashMap<>();

        /**
         * Vanilla Minecraft's own weapons - swords, axes, bows, crossbows, tridents - and what
         * they are worth against a plate. A block of its own for the same reason
         * {@link #tacz_rounds} is one: these are not rounds this addon knows by weapon id, and
         * their rating is a conversion rather than a table. See {@link VanillaWeapons}.
         */
        public VanillaWeapons vanilla_weapons = new VanillaWeapons();

        /**
         * Vanilla Minecraft's own explosions - TNT, creepers, end crystals, beds, ghast fireballs,
         * wither skulls - and what they are worth against a plate. See
         * {@link VanillaExplosions}.
         */
        public VanillaExplosions vanilla_explosions = new VanillaExplosions();

        /**
         * What a collision does to a vehicle this addon governs - the base mod's
         * {@code ywzj_vehicle:vehicle_collision} damage, measured against the plate the impact
         * actually met. See {@link VehicleCollisions}.
         */
        public VehicleCollisions vehicle_collisions = new VehicleCollisions();

        /**
         * The high-speed block-crush path for the Armor Pioneer vehicles - on top of, not instead
         * of, the base mod's own stuck grind. See {@link VehicleRamming}.
         */
        public VehicleRamming vehicle_ramming = new VehicleRamming();

        /**
         * The behind-armour debris model's constants. Used only for rounds that state a mass and
         * a muzzle velocity; see {@link SpallModel}. Every value here is a tunable, not a law.
         */
        public Spall spall = new Spall();

        /** The chemical-round model's constants. See {@link ChemicalModel}. */
        public Chem chem = new Chem();

        /** The lateral-effect round's constants. See {@link PeleModel}. */
        public Pele pele = new Pele();
    }

    /**
     * Constants of the lateral-effect (PELE) model.
     *
     * <p>A PELE round is a tube of inert material between a hard nose and a base: the nose punches
     * the plate, the plate slows the nose, the rear of the projectile keeps going, and the body
     * ruptures outwards behind the plate. The fragments are the projectile's own material, thrown
     * by the energy the plate took out of it - not plate fragments, and not a fixed roll.
     */
    public static final class Pele {
        /**
         * Plate thickness, in calibres, at which the lateral effect is fully engaged. A thinner
         * plate is punched cleanly and the body barely ruptures, so the effect scales with the
         * plate up to this point.
         */
        public float activation_calibers = 0.5F;
        /** Share of the perforation energy that goes sideways as fragments. */
        public float lateral_efficiency = 0.25F;
        /** Joules of lateral energy per point of damage. */
        public float energy_per_damage = 1000.0F;
    }

    private static final Pele DEFAULT_PELE = new Pele();

    /** @return the lateral-effect constants in force; never null. */
    public static Pele pele() {
        return profile != null && profile.pele != null ? profile.pele : DEFAULT_PELE;
    }

    /**
     * Constants of the chemical-round models.
     *
     * <p>A shaped charge is a jet, not a projectile: it is consumed by the plate, so there is no
     * over-penetration and its damage falls as a power of how much of the jet survives. A
     * high-explosive shell is a charge: if it gets inside the whole charge detonates there, and if
     * it is held outside the blast scabs the plate's inner face, with more material thrown the
     * thinner the plate is relative to the charge.
     */
    public static final class Chem {
        /** Exponent of the surviving-jet fraction. Below one, so a deep penetration saturates. */
        public float heat_damage_exponent = 0.65F;
        /** Share of the charge that counts inside when the shell perforates. */
        public float he_eta_inside = 1.0F;
        /** Share of the outside blast that becomes plate scab at a very thin plate. */
        public float he_eta_scab = 0.8F;
        /** Scabbable plate thickness per cube root of charge mass, mm per kg^(1/3). */
        public float he_scab_thickness_per_kg13 = 72.0F;
        /** Joules of TNT energy per point of damage. */
        public float he_energy_per_damage = 7322.0F;
        /**
         * How much harder a burst inside a closed fighting compartment hits than the same charge
         * in the open. The energy calibration above is for blast against structures; inside a
         * tank the overpressure has nowhere to go and the whole crew is in the same box, so an
         * armour-piercing shell's small base charge is worth several times its open-air figure.
         */
        public float aphe_confinement = 3.0F;
    }

    private static final Chem DEFAULT_CHEM = new Chem();

    /** @return the chemical model constants in force; never null. */
    public static Chem chem() {
        return profile != null && profile.chem != null ? profile.chem : DEFAULT_CHEM;
    }

    /**
     * Constants of the debris model.
     *
     * <p>The model is the one the design note describes: a round that gets through a plate makes
     * a hole, the material around it becomes fragments, and the energy those fragments carry is
     * what hurts the tank. A very thin plate over-penetrates - the round exits with most of its
     * energy and the plate is too thin to make a useful fragment cloud - so the damage also carries
     * an overmatch factor that falls to {@link #overmatch_damage_floor} for a plate thinner than
     * {@link #overmatch_full} of the projectile diameter.
     */
    public static final class Spall {
        /** Density of the plate, kg/m3. RHA. */
        public float density_kg_m3 = 7850.0F;
        /** Hole diameter divided by projectile diameter. Ductile perforation opens wider than the rod. */
        public float hole_diameter_factor = 1.1F;
        /** Thickness at which the plate's material is half ejected rather than confined, in mm. */
        public float confinement_mm = 100.0F;
        /** Fragment velocity as a fraction of impact velocity at full residual. */
        public float fragment_velocity_factor = 0.35F;
        /** The share of the projectile's own residual energy that counts as behind-armour energy. */
        public float rod_energy_factor = 0.2F;
        /** Joules of behind-armour energy per point of damage. */
        public float energy_per_damage = 1000.0F;
        /** Plate thickness / projectile diameter at which over-penetration begins. */
        public float overmatch_start = 0.50F;
        /** Plate thickness / projectile diameter at which over-penetration is complete. */
        public float overmatch_full = 0.15F;
        /** Damage left at full over-penetration. */
        public float overmatch_damage_floor = 0.25F;
    }

    private static final Spall DEFAULT_SPALL = new Spall();

    /** @return the debris constants in force; never null, and the defaults before the config loads. */
    public static Spall spall() {
        return profile != null && profile.spall != null ? profile.spall : DEFAULT_SPALL;
    }

    private static Profile profile = defaultProfile();

    /**
     * What {@link #vanillaWeapons()} answers before the config is loaded, or for a file that
     * somehow has no block. One shared instance rather than a fresh one per call: the only reader
     * is a hit resolution, and that runs once per arrow.
     */
    private static final VanillaWeapons VANILLA_DEFAULTS = new VanillaWeapons();

    /** What {@link #vanillaExplosions()} answers before the config is loaded. */
    private static final VanillaExplosions VANILLA_EXPLOSION_DEFAULTS = new VanillaExplosions();

    /** What {@link #vehicleCollisions()} answers before the config is loaded. */
    private static final VehicleCollisions VEHICLE_COLLISION_DEFAULTS = new VehicleCollisions();

    /** What {@link #vehicleRamming()} answers before the config is loaded. */
    private static final VehicleRamming VEHICLE_RAMMING_DEFAULTS = new VehicleRamming();

    private static Path configPath;

    /**
     * The Gson that reads the config file. Exposed so tooling can parse a real file exactly the
     * way the mod does instead of re-implementing the format rules and drifting from them.
     */
    public static Gson configReader() {
        return new GsonBuilder()
                .registerTypeAdapter(Ammo.class, new AmmoDeserializer())
                .create();
    }

    /**
     * The Gson that writes the config file. Exposed alongside {@link #configReader()} so tools can
     * show exactly the text the mod would produce instead of approximately it.
     */
    public static Gson configWriter() {
        return new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .registerTypeAdapter(Ammo.class, new AmmoSerializer())
                .create();
    }

    /** The name this addon wrote its config under before it was renamed. */
    private static final String PREVIOUS_CONFIG_NAME = "t64_armor.json";

    /**
     * Takes over the file this addon wrote under its former name, so a rename does not cost the
     * player every number they have tuned.
     *
     * <p>Moved rather than copied, and only when the new name is absent: a player who has already
     * run the renamed build and edited {@code ywzj_vehicle_armor.json} must never have it
     * overwritten by the stale file sitting next to it. Both conditions point the same way - the
     * old file is read exactly once, by the first launch after the rename.
     *
     * <p>The contents need no translation. The file is keyed by vehicle ids and weapon ids, none
     * of which carry this addon's name, and its version number is unchanged by a rename - so the
     * ordinary upgrade path that follows handles it as the same file it always was.
     */
    private static void adoptPreviousConfig(Path configDir) {
        Path previous = configDir.resolve(PREVIOUS_CONFIG_NAME);
        Path current = configDir.resolve("ywzj_vehicle_armor.json");
        if (Files.exists(current) || !Files.exists(previous)) {
            return;
        }
        try {
            Files.move(previous, current);
            VehicleArmorMod.LOGGER.info("[Vehicle Armor] took over {} as {}", PREVIOUS_CONFIG_NAME,
                    current.getFileName());
        } catch (Exception e) {
            // Reported rather than swallowed: the run continues on defaults, and without this line
            // a failed move is indistinguishable from "there was never an old file".
            VehicleArmorMod.LOGGER.warn("[Vehicle Armor] could not take over {}; starting from "
                    + "defaults instead", previous, e);
        }
    }

    public static void load() {
        Path configDir = FMLPaths.CONFIGDIR.get();
        adoptPreviousConfig(configDir);
        configPath = configDir.resolve("ywzj_vehicle_armor.json");

        boolean regenerate = !Files.exists(configPath);
        if (regenerate) {
            profile = defaultProfile();
        } else {
            Profile read = null;
            try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
                read = configReader().fromJson(reader, Profile.class);
            } catch (Exception e) {
                // A file written by an older version can fail to parse - for example when
                // damage_multiplier was still a single number instead of a per-face object.
                // Keep the old file as .bak rather than silently discarding the user's edits.
                VehicleArmorMod.LOGGER.warn("[Vehicle Armor] {} is not readable with this version, "
                        + "backing it up to ywzj_vehicle_armor.json.bak and writing fresh defaults", configPath);
                backup();
                read = null;
            }
            if (read == null) {
                profile = defaultProfile();
                regenerate = true;
            } else {
                if (read.config_version < CONFIG_VERSION) {
                    // The file parses, but it was written before the current keys existed.
                    // Without this the new settings would be absent and silently do nothing.
                    upgrade(read);
                    read.config_version = CONFIG_VERSION;
                    regenerate = true;
                }
                profile = sanitize(read);
            }
        }

        if (regenerate) {
            write(profile);
        }

        VehicleArmorMod.LOGGER.info(
                "[Vehicle Armor] {} vehicle profile(s), {} ammunition entry(ies), {} with a switchable fuze, "
                        + "multiplier x{} front / x{} side-rear, angle normalization {}",
                profile.vehicles.size(), profile.ammo_penetration.size(), multiPurposeCount(),
                profile.damage_multiplier.front, profile.damage_multiplier.side,
                profile.angle_normalization ? "on" : "off");
    }

    /**
     * Fills in settings that a file from an older release could not have known about.
     *
     * <p>Only vehicles the user already has are touched, and only where the setting is
     * absent - a deliberate {@code enabled: false} is left alone.
     */
    private static void upgrade(Profile loaded) {
        Profile defaults = defaultProfile();

        // v6 -> v7: the ammunition table learned about the coaxial and commander machine guns, and
        // this is still the loop every later round arrives through - the HE and HE-VT shells, the
        // Abrams' dart and shaped charge, the multi-purpose round, and its two machine guns. Only
        // keys the user does not already have are added, so removing an entry sticks once the
        // config is up to date.
        if (loaded.ammo_penetration != null) {
            for (Map.Entry<String, Ammo> entry : defaults.ammo_penetration.entrySet()) {
                if (!loaded.ammo_penetration.containsKey(entry.getKey())) {
                    loaded.ammo_penetration.put(entry.getKey(), entry.getValue());
                    VehicleArmorMod.LOGGER.info("[Vehicle Armor] added ammunition entry {}", entry.getKey());
                }
            }
        }

        // v10 -> v11: warhead rounds learned to drop the base mod's armour-blind explosion, which
        // until now landed on top of every armoured hit and drowned out the whole armour table.
        // Only filled in where the key is absent, so turning it back off sticks.
        if (loaded.ammo_penetration != null) {
            for (Map.Entry<String, Ammo> entry : defaults.ammo_penetration.entrySet()) {
                Ammo current = loaded.ammo_penetration.get(entry.getKey());
                if (current != null && current.replaces_base_explosion == null
                        && entry.getValue().replaces_base_explosion != null) {
                    current.replaces_base_explosion = entry.getValue().replaces_base_explosion;
                    VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: {} now replaces the base explosion",
                            entry.getKey());
                }
            }
        }

        // v21 -> v22: a slot can carry several loads. Filled in the same way and for the same
        // reason as the categories below: the entry already exists in every config, so without
        // this the Abrams would keep firing the one dart it has always had and the four new ones
        // would exist only in this file. Only where the file has none, so a player who has swapped
        // the list out keeps their own.
        if (loaded.ammo_penetration != null) {
            for (Map.Entry<String, Ammo> entry : defaults.ammo_penetration.entrySet()) {
                Ammo current = loaded.ammo_penetration.get(entry.getKey());
                Ammo fresh = entry.getValue();
                if (current != null && current.variants == null && fresh.variants != null) {
                    current.variants = fresh.variants;
                    VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: {} gained {} alternative load(s)",
                            entry.getKey(), fresh.variants.size());
                }
            }
        }

        // v56 -> v57: the VT-5's dart. The base round was shipped as a 500mm placeholder with two
        // older marks, DTW-105 480 and BTA-2 460; it is now the BTA-2 at 550mm with the DTW-2 at
        // 450 as its one load. The two fields move independently and only where they still hold
        // what shipped - a player who has rated the dart keeps their figure, and one who wrote
        // their own load list keeps that list. The BTM1 bunker round needs nothing here: it is a
        // new key, so the loop at the top of this method adds it whole.
        if (loaded.ammo_penetration != null) {
            Ammo dart = loaded.ammo_penetration.get("armor_pioneer:cannon_105mm");
            Ammo fresh = defaults.ammo_penetration.get("armor_pioneer:cannon_105mm");
            if (dart != null && fresh != null) {
                if (dart.penetration == 500.0F) {
                    dart.penetration = fresh.penetration;
                    VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: VT-5 dart 500 -> {}mm",
                            dart.penetration);
                }
                if (shippedVt5Loads(dart.variants)) {
                    dart.variants = fresh.variants;
                    VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: VT-5 dart loads -> {} mark(s)",
                            fresh.variants == null ? 0 : fresh.variants.size());
                }
            }
        }

        // v77 -> v78: the Type 10's dart was shipped at 650mm and is now 600. Only the base figure
        // moves, and only where the file still holds what shipped, so a player who has rated the
        // round keeps their number. The JM33 load is a separate field and stays where it is.
        if (loaded.ammo_penetration != null) {
            Ammo dart = loaded.ammo_penetration.get("armor_pioneer:cannon_120mm_type10");
            Ammo fresh = defaults.ammo_penetration.get("armor_pioneer:cannon_120mm_type10");
            if (dart != null && fresh != null && dart.penetration == 650.0F) {
                dart.penetration = fresh.penetration;
                VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: Type 10 dart 650 -> {}mm",
                        dart.penetration);
            }
        }

        // v31 -> v32: the T-90M's dart was rated 600mm and is now 700. Only the round the gunner
        // gets by default - the four loads it can be swapped for arrive through the loop directly
        // above, which fills any entry whose list is empty. Moved only where the file still holds
        // the figure that shipped, so a player who has rated it themselves keeps their number.
        if (loaded.ammo_penetration != null) {
            Ammo dart = loaded.ammo_penetration.get("vvp_ywzj:2a46m5_125mm_t90m_apfsds");
            Ammo fresh = defaults.ammo_penetration.get("vvp_ywzj:2a46m5_125mm_t90m_apfsds");
            if (dart != null && fresh != null && dart.penetration == 600.0F) {
                dart.penetration = fresh.penetration;
                VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: 2a46m5 dart 600 -> {}mm",
                        dart.penetration);
            }
        }

        // v20 -> v21: the ammunition compendium learned to file rounds by category. A file written
        // before it has none, and the fallback would put every machine-gun round on the cannon
        // shelf - so where the default names a shelf, an entry without one takes it. Only where the
        // default has one and the file does not, so a player who has moved a round keeps it moved.
        if (loaded.ammo_penetration != null) {
            for (Map.Entry<String, Ammo> entry : defaults.ammo_penetration.entrySet()) {
                Ammo current = loaded.ammo_penetration.get(entry.getKey());
                String wanted = entry.getValue().category;
                if (current != null && current.category == null && wanted != null) {
                    current.category = wanted;
                    VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: {} filed under {}",
                            entry.getKey(), wanted);
                }
            }
        }

        // v17 -> v18: the multi-purpose round, whose second damage model is a whole nested entry.
        // Adding the key is not enough on its own: if the entry is present but the nested block did
        // not survive - a round trip that dropped it, or a file hand-written from the shape of a
        // plain HEAT entry - the fuze would still switch and the shell would quietly stay a HEAT
        // round in every position. So a hollow entry is refilled from the default, and only when it
        // is genuinely hollow: one that has a proximity block of its own is left exactly as it is.
        if (loaded.ammo_penetration != null) {
            for (Map.Entry<String, Ammo> entry : defaults.ammo_penetration.entrySet()) {
                Ammo current = loaded.ammo_penetration.get(entry.getKey());
                Ammo fresh = entry.getValue();
                if (current != null && current.multi_purpose == null
                        && current.proximity == null && fresh.proximity != null) {
                    current.multi_purpose = fresh.multi_purpose;
                    current.proximity = fresh.proximity;
                    current.proximity_radius = fresh.proximity_radius;
                    VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: {} regained its switchable fuze",
                            entry.getKey());
                }
            }
        }

        // v5 -> v6: destroyed weapons could no longer fire. A file without the key gets the
        // new default; one that already says false keeps saying false.
        if (loaded.destroyed_parts_cannot_fire == null) {
            loaded.destroyed_parts_cannot_fire = defaults.destroyed_parts_cannot_fire;
            VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: destroyed_parts_cannot_fire = {}",
                    loaded.destroyed_parts_cannot_fire);
        }
        // v8 -> v9: the modding tool preview was replaced by the held scanner, so the old flag is
        // dropped and its replacement takes the default. The stale key simply disappears the next
        // time the file is written, since it is no longer part of Profile.
        if (loaded.scanner_shows_armor == null) {
            loaded.scanner_shows_armor = defaults.scanner_shows_armor;
            VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: scanner_shows_armor = {}",
                    loaded.scanner_shows_armor);
        }

        // v3 -> v4: the turret front multiplier dropped from 100 to 50. Only migrate the
        // value if it still sits at the old default, so a deliberate customisation survives.
        if (loaded.damage_multiplier != null && loaded.damage_multiplier.front == 100.0F) {
            loaded.damage_multiplier.front = defaults.damage_multiplier.front;
            VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: front damage multiplier 100 -> {}",
                    defaults.damage_multiplier.front);
        }

        // v37 -> v38: the M1296 Stryker, and the adjustable suspension it is the first to have.
        //
        // The vehicle is new to the defaults, so it arrives whole through the vehicle loop above and
        // its 30mm round arrives through the ammunition loop - nothing to migrate. The suspension is
        // a new optional block on an existing vehicle, which is what the inherit list is for, and
        // the bump is the only thing that makes a file already written at v37 look for it.
        //
        // v36 -> v37: the T-72B3M follows the T-90M to a half-and-half glacis - it is the same hull
        // shape drawn by the same people, and the two disagreed only because the first round of
        // changes named the T-90M and not this one. The move is in t90mRegrade, which is where the
        // glacis boundaries have lived since v26; the T-90M's rear plate also arrives here, as a new
        // `part_armor` key the pack patch creates a part for (see split_rear_armor.ps1).
        //
        // v35 -> v36: the T-90M's front face and turret blocks, regraded. Three figures move and all
        // three move the same way - only where the file still holds the one that shipped, so a
        // player who has tuned any of them keeps it. The glacis was the upper two thirds behind a
        // 520mm plate and is now the upper half behind a 700mm one, and the turret's reactive blocks
        // were rated as though what is behind them were the turret's 650mm front rather than the
        // flank or rear they actually sit on. See t90mRegrade.
        //
        // v34 -> v35: reactive blocks along the T-90M Proryv hulls, keyed by part id - `era_1` and
        // `era_2` on the 2022 and 2024. These are new *keys inside* `part_armor`, which is the one
        // shape none of the machinery below covers: inherit() hands over a whole block and only when
        // the player has none, and a file always has a `part_armor` map (empty, on the plain tank's
        // neighbours, and holding the turret blocks on these two), so the block is never null and
        // never arrives. adoptHp() only fills a value into boxes that are already there. So the two
        // new boxes need a migration of their own - see adoptMissingBoxes, which is the third of the
        // three places a new ArmorOverride/part_armor key has to be carried.
        //
        // v33 -> v34: the T-64's deep-fording kit. One new optional block on one existing vehicle,
        // which arrives through the inherit list below - and the bump is the only thing that makes a
        // file already written at v33 look for it. A block that never arrives is not an error: the
        // key is simply absent, the vehicle is read as having no kit, and the tank drowns exactly as
        // it did before anyone added one.
        //
        // v32 -> v33: the T-72B3M, and the 125mm high-explosive shell the T-72B3M and the T-90Ms now
        // both carry. The vehicle and all four of its rounds are new entries, so they arrive through
        // the loops below - including the shell's Telnik load, which is a new entry too. Nothing to
        // migrate, but the bump is what makes a file already written at v32 look for them.
        //
        // v31 -> v32: the T-90M's dart is named 3BM60 and rated 700mm, and the gun can be fed four
        // rounds instead of one. The loads arrive through the loop below; the rating is a value on
        // an entry the file already has, so it needs the migration further down.
        //
        // v30 -> v31: the Ukrainian Abrams' KE-W round, a new ammunition entry, and the depleted
        // uranium the A2 and the SEP are armoured with. The round arrives through the loop below;
        // the armour is a value on a vehicle the file already has, so an older file needs the
        // migration at the end of this method - a copy of the M1A1's figures that nothing would
        // otherwise correct.
        //
        // v29 -> v30: the rest of the M1 family. Three more vehicles, which arrive through the loop
        // below along with the tiles their models carry - so nothing to migrate, but the bump is
        // what makes a file already written at v29 look for them.
        //
        // v28 -> v29: reactive panels are told which kind they are, and the tandem rule stops being
        // "any layer is spent" and becomes "a light panel takes a third, a heavy one stops one and
        // goes with it". That kind is history at v70, which replaces it with a number; the two
        // steps run together below, so a file written at v28 still ends up with the right HP.
        //
        // v27 -> v28: the BTR-3's tandem ATGM, a new ammunition entry - which arrives through the
        // loop below, so there is nothing to migrate. The bump is still required: a file already
        // written at v27 would otherwise never see the round, because the upgrade path is only
        // reached by a version change.
        //
        // v26 -> v27: the T-90M's glacis boundary, whose sign was wrong in v26. A file that holds a
        // T-90M at v26 needs the migration below; one that has never seen a T-90M gets the corrected
        // value along with the vehicle itself, and the migration finds nothing to do.
        //
        // v25 -> v26: the three T-90Ms, and the per-part armour values their add-on boxes need.
        // Both arrive through the loops below - the vehicles are new to the defaults, and a map that
        // a file does not have is filled in empty by sanitize. Nothing to migrate.
        //
        // v24 -> v25: the machine-gun port, and two corrections to the Panther's glacis. The port is
        // the reason this bump exists at all - it is a new optional block, and until the list above
        // covered it, adding it to VehicleArmor and to the defaults put it nowhere: an existing file
        // has no such key, so the zone was in the code and not in the game, with no symptom beyond a
        // port that could not be hit.
        //
        // v23 -> v24: nothing to fill in, and the bump is the whole point. The Panther's damage
        // bonus was written for one build under the key names penetration_damage_*, and a config
        // that has already been through v23 holds those names. The reader accepts both, so the
        // values are not lost - but only a rewrite puts the current names back, and a rewrite only
        // happens on a version change. Without this bump the file would keep the old spelling
        // forever, and the next rename would have no such luck.
        //
        // v22 -> v23: the Panther G, from a second vehicle pack. It arrives through the loops below
        // and needs no branch of its own - the vehicle is new to the defaults, so the vehicle loop
        // adds it whole, and its three rounds are new ammunition, so the ammunition loop adds those.
        // The round that comes with a new damage roll rather than a residual share carries its own
        // three numbers, so there is nothing for an older file to be missing.
        for (Map.Entry<String, VehicleArmor> entry : defaults.vehicles.entrySet()) {
            VehicleArmor current = loaded.vehicles.get(entry.getKey());
            if (current == null) {
                // v13 -> v14: a vehicle this release adds. A file written before it could not have
                // had an entry for it, and one added here comes with all of its profiles already.
                loaded.vehicles.put(entry.getKey(), entry.getValue());
                VehicleArmorMod.LOGGER.info("[Vehicle Armor] added vehicle profile for {}", entry.getKey());
                continue;
            }
            // Every optional block a vehicle can have, as a list.
            //
            // Written as a list rather than as one if-statement per block because a block missing
            // from it is a block the player never receives, and that failure has no symptom: the
            // key is simply absent from their file, the code reads null, and the zone quietly does
            // not exist. hull_weak_spot was added to the defaults, added to ArmorSystem, and reached
            // nobody - the machine-gun port was in the code and not in the game. Three of these used
            // to be written out by hand and the fourth was forgotten, which is what a list prevents.
            //
            // Only where the player has nothing: a block they have is theirs, however they set it.
            String id = entry.getKey();
            inherit(id, "hull_front_split", current, entry.getValue(),
                    v -> v.hull_front_split, (v, x) -> v.hull_front_split = x);
            inherit(id, "turret_weak_spot", current, entry.getValue(),
                    v -> v.turret_weak_spot, (v, x) -> v.turret_weak_spot = x);
            inherit(id, "hull_weak_spot", current, entry.getValue(),
                    v -> v.hull_weak_spot, (v, x) -> v.hull_weak_spot = x);
            inherit(id, "track_ends", current, entry.getValue(),
                    v -> v.track_ends, (v, x) -> v.track_ends = x);
            inherit(id, "road_wheels", current, entry.getValue(),
                    v -> v.road_wheels, (v, x) -> v.road_wheels = x);
            inherit(id, "hull_side_era", current, entry.getValue(),
                    v -> v.hull_side_era, (v, x) -> v.hull_side_era = x);
            inherit(id, "damage_multiplier", current, entry.getValue(),
                    v -> v.damage_multiplier, (v, x) -> v.damage_multiplier = x);
            inherit(id, "fording", current, entry.getValue(),
                    v -> v.fording, (v, x) -> v.fording = x);
            // The aircraft model is a block like the others: a vehicle added as a tank and later
            // given a skin model receives it, and a block the player has edited is theirs. There is
            // no vehicle that is a tank today and an aircraft tomorrow, but a future pack may add an
            // aircraft under an id that was already configured, and this is what the list is for.
            inherit(id, "aircraft", current, entry.getValue(),
                    v -> v.aircraft, (v, x) -> v.aircraft = x);
            // The suspension used to be inherited here, one vehicle at a time. It is not any more:
            // it is a map of its own, and the vehicles that have one are the ones this addon puts
            // there - not every vehicle in the defaults that happens to lack the key. A tracked
            // vehicle handed a suspension block by inheritance would be answered by keys that move
            // nothing, which is exactly the shape of failure this list exists to prevent.
            // v9 -> v10, v11 -> v12: ammunition can name its own armour profile. Checked profile by
            // profile rather than by whether the map is empty, because a second profile has to be
            // able to arrive after the first one is already there.
            if (current.overrides == null) {
                current.overrides = new LinkedHashMap<>();
            }
            for (Map.Entry<String, ArmorOverride> profile : entry.getValue().overrides.entrySet()) {
                if (!current.overrides.containsKey(profile.getKey())) {
                    current.overrides.put(profile.getKey(), profile.getValue());
                    VehicleArmorMod.LOGGER.info("[Vehicle Armor] added '{}' armour profile for {}",
                            profile.getKey(), entry.getKey());
                }
            }
        }

        // v89 -> v90: the fording kit grew a preparation and a stowing time. They are new ints
        // inside a block that already exists, which is the shape the inherit list does not cover -
        // it hands over a whole block and only when the player has none. Gson builds an object
        // without running its field initialisers, so a kit read from a v89 file would otherwise
        // arrive with both times at zero and be refused as too fast to be real; the runtime treats
        // zero as the default, but writing the numbers back is what makes the file say what the
        // game is doing.
        for (VehicleArmor vehicle : loaded.vehicles.values()) {
            Fording.Kit kit = vehicle.fording;
            if (kit == null) {
                continue;
            }
            if (kit.prep_ticks <= 0) {
                kit.prep_ticks = Fording.DEFAULT_PREP_TICKS;
            }
            if (kit.stow_ticks <= 0) {
                kit.stow_ticks = Fording.DEFAULT_STOW_TICKS;
            }
        }

        // The Abrams' glacis/lower plate boundary was shipped wrong twice, so a file may be carrying
        // either of the two figures that came before: 0.0 (an even split, v14) or 0.6 (upper 30%,
        // v15). Either is moved to the current value, and anything else is left alone - a file that
        // has been hand-edited to some third number keeps it.
        // The Panther's glacis first shipped splitting the plate 65/35, and with a lower plate as
        // thick as the upper one. Both are corrected, and each only where the file still holds the
        // figure that shipped: a player who has moved it has said something, and this has no
        // business overruling them.
        VehicleArmor panther = loaded.vehicles.get("tank_assault:panther");
        VehicleArmor pantherDefault = defaults.vehicles.get("tank_assault:panther");
        if (panther != null && panther.hull_front_split != null && pantherDefault != null) {
            if (panther.hull_front_split.split == -0.3F) {
                panther.hull_front_split.split = pantherDefault.hull_front_split.split;
                VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: Panther glacis split -0.3 -> {}"
                        + " (glacis {}% -> {}% of the front face)", panther.hull_front_split.split,
                        Math.round((1.0F + 0.3F) * 50.0F),
                        Math.round((1.0F - panther.hull_front_split.split) * 50.0F));
            }
            if (panther.hull_front_split.lower != null
                    && panther.hull_front_split.lower.thickness == 200.0F
                    && pantherDefault.hull_front_split.lower.thickness != 200.0F) {
                panther.hull_front_split.lower.thickness = pantherDefault.hull_front_split.lower.thickness;
                VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: Panther lower plate 200 -> {}mm",
                        panther.hull_front_split.lower.thickness);
            }
        }

        VehicleArmor abrams = loaded.vehicles.get("vvp_ywzj:m1a1");
        VehicleArmor abramsDefault = defaults.vehicles.get("vvp_ywzj:m1a1");
        if (abrams != null && abrams.hull_front_split != null && abramsDefault != null) {
            float shipped = abrams.hull_front_split.split;
            float corrected = abramsDefault.hull_front_split.split;
            if (shipped == 0.0F || shipped == 0.6F) {
                abrams.hull_front_split.split = corrected;
                VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: Abrams hull front split {} -> {} "
                                + "(glacis {}% -> {}% of the front face)",
                        shipped, corrected,
                        Math.round((1.0F - shipped) * 50.0F), Math.round((1.0F - corrected) * 50.0F));
            }
        }

        // v26 -> v27, v35 -> v36 and v36 -> v37: the glacis boundaries, which the three T-90Ms have
        // moved twice and the T-72B3M once, and then the T-90M's plates. See the two methods.
        glacisSplit(loaded, defaults);
        t90mRegrade(loaded, defaults);

        // v30 -> v31: the depleted uranium. At v30 the A2 and the SEP shipped carrying a straight
        // copy of the M1A1's frontal armour, because nothing then said by how much they differed.
        // A file whose frontal plates are all still the M1A1's is that copy; one that has been
        // edited anywhere among them keeps what it says, the same rule the glacis boundaries are
        // held to. Only the frontal plates move - the DU arrays are in the turret front and the two
        // hull front plates and nowhere else, so nothing else about the tank changes.
        VehicleArmor m1a1Defaults = defaults.vehicles.get("vvp_ywzj:m1a1");
        for (String id : new String[]{"vvp_ywzj:m1a2", "vvp_ywzj:m1a2_sep"}) {
            VehicleArmor mine = loaded.vehicles.get(id);
            VehicleArmor theirs = defaults.vehicles.get(id);
            if (mine == null || theirs == null || m1a1Defaults == null
                    || !sharesFrontalArmour(mine, m1a1Defaults)) {
                continue;
            }
            adoptFrontalArmour(mine, theirs);
            VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: {} given its own frontal armour"
                            + " (turret front {}mm, glacis {}mm)", id, mine.turret.front,
                    mine.hull_front_split == null ? 0.0F : mine.hull_front_split.upper.thickness);
        }

        // v28 -> v29 -> v70: which panels are reactive armour, and what they are worth. The v29
        // step gave a panel the old kind when the file said nothing; v70 turns every kind into a
        // number. Both reach back through every vehicle and every profile, because a panel with no
        // value is not a broken panel - it is ordinary armour, and the quietest failure in the
        // file is a reactive box that reads as a plausible thickness and does nothing else.
        int converted = convertReactiveKinds(loaded);
        if (converted > 0) {
            VehicleArmorMod.LOGGER.info(
                    "[Vehicle Armor] upgrade: {} reactive panel(s) converted from a kind to HP",
                    converted);
        }
        adoptReactiveHp(loaded, defaults);

        // v34 -> v35: boxes the defaults grew that the file has never heard of. The two migrations
        // do different jobs and do not overlap - this one copies boxes in whole, the one above
        // fills a value into boxes already there - so the order between them is free. A box this
        // adds brings its HP with it.
        adoptMissingBoxes(loaded, defaults);

        // v40 -> v41: the two 30mm belts stopped carrying their other round as a load.
        //
        // Last of all, and that position is load-bearing rather than tidy: the v21 -> v22 loop above
        // fills a load list wherever the file has none, and every step here runs in one pass. Taking
        // the lists away before that loop ran would simply have them put back, and the game would
        // start with the four entries the config no longer describes.
        //
        // Each is named with the list it shipped with, so only that list is removed. The four pairs
        // are the belts as they were: a shell's slot carrying the dart, a dart's slot carrying the
        // shell, on each of the two guns.
        if (loaded.ammo_penetration != null) {
            dropShippedLoad(loaded.ammo_penetration.get("vvp_ywzj:ztm1_30mm"),
                    "3UBR8-30mm APFSDS", 90.0F);
            dropShippedLoad(loaded.ammo_penetration.get("vvp_ywzj:ztm1_30mm_apfsds"),
                    "3UOF8-30mm HE", 6.0F);
            dropShippedLoad(loaded.ammo_penetration.get("vvp_ywzj:xm813_30mm"),
                    "MK258-30mm APFSDS", 116.0F);
            dropShippedLoad(loaded.ammo_penetration.get("vvp_ywzj:xm813_30mm_apfsds"),
                    "XM813 30mm HE", 6.0F);
            VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: the two 30mm belts are groups of "
                    + "two rounds now - any load list still holding the round this addon put there "
                    + "(3UBR8, 3UOF8, MK258, XM813 30mm HE) has been removed. In game: the mouse "
                    + "wheel changes belt, F changes the round inside it.");
        }

        // v42 -> v43: the pack moved the ruler the two road-wheel figures are measured against.
        roadWheelRegrade(loaded);

        // v53 -> v54: the K2's track path moved onto the display centre for the link model, so
        // the shipped track-strip edge moved with it. This works on the vehicle block, like the
        // suspension move below, but does not touch the field that move empties.
        moveK2TrackInner(loaded, defaults);

        // v68 -> v69: the same move for the VT-5 and the VT-4, whose structure track paths were
        // rebuilt onto the display tread loop when they took the link belt.
        moveVt5Vt4TrackInner(loaded, defaults);

        // v57 -> v58: engine figures and the smoke launcher. Both work on the vehicle blocks, so
        // they run before the suspension move empties the deprecated block field.
        enginesAndSmoke(loaded, defaults);
        regradeTankAssault(loaded, defaults);
        // v91 -> v92: the Panther's kinetics become real plates plus their inclinations. Runs
        // after the regrade so a file that has never seen v91 meets the 140/105 it shipped with.
        slopePanther(loaded, defaults);
        // v92 -> v93: the VT-4's composite tables opt into the per-hit direction factor.
        vt4AngleScaling(loaded, defaults);
        // v93 -> v94: its ricochet band becomes real steel at the angle it is built at.
        vt4RicochetBand(loaded, defaults);
        // v95 -> v96: the Panther's AP and the VT-4's darts get their penetration-against-range
        // curves. Filled only where the file has none, so a hand-written curve is never replaced.
        ammoRangeFalloff(loaded);
        // v97 -> v98: the Tiger's plates gain their own angles, its mantlet its 120mm, and its
        // two armour-piercing rounds their curves.
        slopeTiger(loaded, defaults);
        // v87 -> v88: the Tank Assault ground vehicles gained their engine ratings. A file
        // written at v87 already carries the vehicles - they arrived one version earlier -
        // but with both fields at zero, which is exactly what an absent figure looks like.
        // Only a zero is filled; a player who typed their own stays.
        for (String id : new String[]{"tank_assault:a22f", "tank_assault:matilda_mk1", "tank_assault:valentine_mk2", "tank_assault:panzer4h", "tank_assault:ferdinand", "tank_assault:sturmtiger", "tank_assault:sdkfz251", "tank_assault:sdkfz2", "tank_assault:bmw_r75", "tank_assault:type82", "tank_assault:bt7v", "tank_assault:t26b", "tank_assault:kv1mod1942", "tank_assault:t3485", "tank_assault:t44a", "tank_assault:su122", "tank_assault:p40", "tank_assault:panther", "tank_assault:tiger"}) {
            VehicleArmor mine = loaded.vehicles == null ? null : loaded.vehicles.get(id);
            VehicleArmor theirs = defaults.vehicles == null ? null : defaults.vehicles.get(id);
            if (mine == null || theirs == null) {
                continue;
            }
            if (mine.engine_horsepower == 0.0F && theirs.engine_horsepower > 0.0F) {
                mine.engine_horsepower = theirs.engine_horsepower;
                VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: {} gained its {} hp engine",
                        id, theirs.engine_horsepower);
            }
            if (mine.horsepower_per_ton == 0.0F && theirs.horsepower_per_ton > 0.0F) {
                mine.horsepower_per_ton = theirs.horsepower_per_ton;
                VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: {} gained its {} hp/t",
                        id, theirs.horsepower_per_ton);
            }
        }

        // v88 -> v89: the three fixed-wing aircraft gained their engine ratings. The vehicles
        // arrived at v86, so a v88 file carries them with both fields at zero - the same shape as
        // the ground ratings above, and the same rule: only a zero is filled. The per-aircraft
        // reference and exponent are field initialisers the reader supplies and the rewrite emits,
        // so there is no value to move for them.
        for (String id : new String[]{"tank_assault:bf109e4", "tank_assault:fw190a5", "tank_assault:p51d20"}) {
            VehicleArmor mine = loaded.vehicles == null ? null : loaded.vehicles.get(id);
            VehicleArmor theirs = defaults.vehicles == null ? null : defaults.vehicles.get(id);
            if (mine == null || theirs == null) {
                continue;
            }
            if (mine.engine_horsepower == 0.0F && theirs.engine_horsepower > 0.0F) {
                mine.engine_horsepower = theirs.engine_horsepower;
                VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: {} gained its {} hp engine",
                        id, theirs.engine_horsepower);
            }
            if (mine.horsepower_per_ton == 0.0F && theirs.horsepower_per_ton > 0.0F) {
                mine.horsepower_per_ton = theirs.horsepower_per_ton;
                VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: {} gained its {} hp/t",
                        id, theirs.horsepower_per_ton);
            }
        }

        // v74 -> v75: the VT-4's reactive panels. Runs after enginesAndSmoke because that step is
        // what replaces a missing turret list whole; the panels are new boxes the defaults carry,
        // and their figures sit in front of faces whose shipped values this step moves.
        vt4EraPanels(loaded, defaults);

        // v76 -> v77: the VT-4's two hull-front bands. They sit on the split `vt4EraPanels` has
        // just finished reading, and they are kinetic figures the chemical step never touches.
        vt4HullBands(loaded, defaults);

        // v79 -> v80: the Tianma-20's three reactive panels. The boxes are new keys the defaults
        // carry, so `adoptMissingBoxes` has already put them in; what is left is the turret list,
        // which only this step knows about.
        tianmaEraPanels(loaded);

        // v80 -> v81 shipped the Leopard 2A4's flank kit as a layer on the vehicle block; v81 ->
        // v82 makes it two parts instead, which is why that copy step is gone and the removal
        // below runs. A file still carrying the shipped layer has it taken away there; the two
        // boxes arrive through adoptMissingBoxes like every other new part_armor key.

        // v67 -> v68: the Leopard 2A4's manual loader. A new field on the vehicle block, plus the
        // loader's seat joining the turret compartment. Runs before the suspension move for the
        // same reason as the step above.
        adoptLoaderSeats(loaded, defaults);

        // v70 -> v71: the ZTZ-100's unmanned turret. A new list on the vehicle block; an older
        // file has none and the shipped list is filled, a hand-written list is kept.
        adoptHullSeats(loaded, defaults);

        // v43 -> v44: the suspension moved to a map of its own. Runs last, and it has to: every
        // earlier step here works on the vehicle blocks, and this one empties a field inside them.
        adoptSuspensions(loaded, defaults);

        // v64 -> v65: the ZTZ-100's hybrid drive. A new map, so the loop creates it and fills it;
        // nothing in a v64 file can be carrying one. The ZTZ-100's armour block itself arrives
        // through the vehicle loop above, because it is new to the defaults.
        adoptHybrids(loaded, defaults);

        // v71 -> v72: the hatch periscope. A new map again, so the loop creates it and fills it;
        // nothing in a v71 file can be carrying one. The VT-5's entry arrives whole.
        adoptPeriscopes(loaded, defaults);

        // v72 -> v73: the VT-5's commander and gunner seats were re-adapted (commander left,
        // gunner right), and the periscope point moved with the commander to the left hatch. A
        // v72 file has the old shipped triple; move only that, so a hand-measured point sticks.
        moveVt5Periscope(loaded, defaults);

        // v62 -> v63: the Type 10 gains the K2's lateral axis. Its block already exists in a v61
        // or v62 file, so this is a targeted fill rather than a map loop - and it must run after
        // adoptSuspensions, which is what adds the block whole to a file older than v61.
        openType10Lean(loaded, defaults);

        // v63 -> v64: the Type 10's armour table. A value change on a block that already exists,
        // so it runs per face on the vehicle itself and moves only the figures that were shipped.
        regradeType10(loaded);

        // v44 -> v45: TACZ's cartridges. A new map that an older file has no key for at all, so the
        // loop both creates it and fills it - and only where the file has nothing, so a player who
        // has rated a round themselves keeps their number.
        if (loaded.tacz_rounds == null) {
            loaded.tacz_rounds = new LinkedHashMap<>();
        }
        int taczAdded = 0;
        for (Map.Entry<String, Ammo> entry : defaults.tacz_rounds.entrySet()) {
            if (!loaded.tacz_rounds.containsKey(entry.getKey())) {
                loaded.tacz_rounds.put(entry.getKey(), entry.getValue());
                taczAdded++;
            }
        }
        VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: {} TACZ cartridge(s) rated - the gun "
                + "mod's bullets meet the armour table now, and anything not in this table keeps "
                + "TACZ's own damage", taczAdded);

        // v78 -> v79: vanilla weapons. A whole block that could not exist before, so a file older
        // than this version has nothing to preserve - but a file already carrying it, hand-written
        // or written by a newer build, keeps every figure, which is why the null check is the only
        // condition. The block is fully defaulted, so this is also what makes the writer emit it.
        if (loaded.vanilla_weapons == null) {
            loaded.vanilla_weapons = defaults.vanilla_weapons;
            VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: added vanilla_weapons - swords, "
                    + "axes, arrows and tridents now meet the armour table");
        }

        // v82 -> v83: the Leopard 2A4's DM33 moved from 470mm to 480. Only where the file still
        // holds what shipped, so a player who has rated the round keeps their figure. The DM23
        // load is a separate field and stays at 400.
        if (loaded.ammo_penetration != null) {
            Ammo dm33 = loaded.ammo_penetration.get("armor_pioneer:cannon_120mm_dm33");
            Ammo fresh = defaults.ammo_penetration.get("armor_pioneer:cannon_120mm_dm33");
            if (dm33 != null && fresh != null && dm33.penetration == 470.0F) {
                dm33.penetration = fresh.penetration;
                VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: Leopard 2A4 DM33 470 -> {}mm",
                        dm33.penetration);
            }
        }

        // v83 -> v84: the Tianma-20's dart moved from 550mm to 630. Only where the file still holds
        // what shipped, so a player who has rated the round keeps their figure. The 3BM42 load is a
        // separate field and stays at 457.
        if (loaded.ammo_penetration != null) {
            Ammo dart = loaded.ammo_penetration.get("armor_pioneer:cannon_125mm_dprk");
            Ammo fresh = defaults.ammo_penetration.get("armor_pioneer:cannon_125mm_dprk");
            if (dart != null && fresh != null && dart.penetration == 550.0F) {
                dart.penetration = fresh.penetration;
                VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: Tianma-20 dart 550 -> {}mm",
                        dart.penetration);
            }
        }

        // v81 -> v82: the Leopard 2A4's flank kit moved from one layer on the vehicle block to
        // two part-armour boxes. A removal, so it runs after adoptMissingBoxes has put the two
        // boxes in - and last, because a pass that removed the layer first would have the layer
        // put back by nothing but would run the old copy step on a file that predates both.
        leopardSideAddonToParts(loaded);

        // v98 -> v99: vanilla explosions. A whole block that could not exist before, so a file
        // older than this version has nothing to preserve; a file that already carries it keeps
        // every figure. The block is fully defaulted, so this is also what makes the writer emit it.
        if (loaded.vanilla_explosions == null) {
            loaded.vanilla_explosions = defaults.vanilla_explosions;
            VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: added vanilla_explosions - TNT, "
                    + "creepers and the rest now meet the armour table");
        }

        // v99 -> v100: collisions and the high-speed ram. Two whole blocks; an older file has
        // neither. Both ship with the seven Armor Pioneer ids, so every other vehicle keeps the
        // base mod's behaviour until an id is added.
        if (loaded.vehicle_collisions == null) {
            loaded.vehicle_collisions = defaults.vehicle_collisions;
            VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: added vehicle_collisions - "
                    + "crashes now meet the armour table for the Armor Pioneer vehicles");
        }
        if (loaded.vehicle_ramming == null) {
            loaded.vehicle_ramming = defaults.vehicle_ramming;
            VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: added vehicle_ramming - the "
                    + "Armor Pioneer vehicles can crush blocks at speed");
        }

        // v100 -> v101: the ground guard inside the existing ram block. Gson runs the field
        // initialisers, so a file written at v100 usually already answers true; the explicit
        // assignment is for a hand-written block that left the key out entirely.
        if (loaded.vehicle_ramming != null) {
            if (loaded.vehicle_ramming.protect_natural_ground == null) {
                loaded.vehicle_ramming.protect_natural_ground = Boolean.TRUE;
            }
            if (loaded.vehicle_ramming.natural_ground_blocks == null) {
                loaded.vehicle_ramming.natural_ground_blocks = new java.util.ArrayList<>();
            }
        }

        // v101 -> v102: the KSTAM-II becomes a top-attack round. The three fields are new and
        // nullable, so a v101 file's entry has none of them; fill only what is absent. A player
        // who has set a standoff of their own keeps it, and the other missiles are untouched
        // because their entries never gain the keys.
        if (loaded.ammo_penetration != null && defaults.ammo_penetration != null) {
            Ammo kstam = loaded.ammo_penetration.get("armor_pioneer:cannon_120mm_kstam");
            Ammo freshKstam = defaults.ammo_penetration.get("armor_pioneer:cannon_120mm_kstam");
            if (kstam != null && freshKstam != null) {
                boolean moved = false;
                if (kstam.top_attack_standoff == null) {
                    kstam.top_attack_standoff = freshKstam.top_attack_standoff;
                    moved = true;
                }
                if (kstam.top_attack_radius == null) {
                    kstam.top_attack_radius = freshKstam.top_attack_radius;
                    moved = true;
                }
                if (kstam.top_attack_min_height == null) {
                    kstam.top_attack_min_height = freshKstam.top_attack_min_height;
                    moved = true;
                }
                if (moved) {
                    VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: the KSTAM-II now "
                            + "detonates above the target's roof");
                }
            }
        }

        // v102 -> v103: the standoff came down from four blocks to one. Only the shipped values
        // move - a player who set their own standoff keeps it, and the floor follows the standoff
        // only where it is still the old shipped 1.5.
        if (loaded.ammo_penetration != null) {
            Ammo kstam = loaded.ammo_penetration.get("armor_pioneer:cannon_120mm_kstam");
            if (kstam != null) {
                boolean moved = false;
                if (kstam.top_attack_standoff != null && kstam.top_attack_standoff == 4.0F) {
                    kstam.top_attack_standoff = 1.0F;
                    moved = true;
                }
                if (kstam.top_attack_min_height != null && kstam.top_attack_min_height == 1.5F) {
                    kstam.top_attack_min_height = 0.25F;
                    moved = true;
                }
                if (moved) {
                    VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: the KSTAM-II detonates "
                            + "1 block above the roof instead of 4");
                }
            }
        }

        // v103 -> v104: FY-4E gets separate CE and KE ratings. Only the four VT-4 part panels are
        // filled, and only while they still carry the shipped 200mm HP with no split rating - a
        // panel a player has revalued keeps the old flat rule rather than being overwritten.
        if (loaded.vehicles != null) {
            VehicleArmor vt4 = loaded.vehicles.get("armor_pioneer:vt4");
            if (vt4 != null && vt4.part_armor != null) {
                int filled = 0;
                for (String id : new String[]{"hull_era", "hull_era2", "turret_era", "turret_era2"}) {
                    Plate panel = vt4.part_armor.get(id);
                    if (panel != null && panel.era_hp == 200.0F && !panel.hasSplitEra()) {
                        panel.era_ce = 300.0F;
                        panel.era_ke = 150.0F;
                        panel.era_ce_falloff = 0.35F;
                        panel.era_ke_falloff = 0.50F;
                        filled++;
                    }
                }
                if (filled > 0) {
                    VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: the VT-4's FY-4E panels "
                            + "now take 300mm off a jet and 150mm off a dart, less at an angle");
                }
            }
        }

        // v104 -> v105: the four darts opt into the debris model. A whole new block first, then the
        // per-round inputs - each only where the round still holds the shipped penetration, so a
        // hand-rated round is left on the old formula instead of being silently re-measured.
        if (loaded.spall == null) {
            loaded.spall = defaults.spall;
            VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: added the spall block - kinetic "
                    + "damage is now measured from plate fragments");
        }
        if (loaded.ammo_penetration != null && defaults.ammo_penetration != null) {
            int shipped = 0;
            shipped += shipKineticModel(loaded.ammo_penetration.get("armor_pioneer:cannon_120mm"),
                    defaults.ammo_penetration.get("armor_pioneer:cannon_120mm"), 600.0F);
            Ammo k2 = loaded.ammo_penetration.get("armor_pioneer:cannon_120mm");
            Ammo freshK2 = defaults.ammo_penetration.get("armor_pioneer:cannon_120mm");
            if (k2 != null && freshK2 != null && k2.variants != null && !k2.variants.isEmpty()
                    && freshK2.variants != null && !freshK2.variants.isEmpty()) {
                shipped += shipKineticModel(k2.variants.get(0), freshK2.variants.get(0), 700.0F);
            }
            shipped += shipKineticModel(loaded.ammo_penetration.get("armor_pioneer:cannon_125mm"),
                    defaults.ammo_penetration.get("armor_pioneer:cannon_125mm"), 600.0F);
            Ammo vt4 = loaded.ammo_penetration.get("armor_pioneer:cannon_125mm");
            Ammo freshVt4 = defaults.ammo_penetration.get("armor_pioneer:cannon_125mm");
            if (vt4 != null && freshVt4 != null && vt4.variants != null && !vt4.variants.isEmpty()
                    && freshVt4.variants != null && !freshVt4.variants.isEmpty()) {
                shipped += shipKineticModel(vt4.variants.get(0), freshVt4.variants.get(0), 680.0F);
            }
            if (shipped > 0) {
                VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: {} dart(s) now deal damage "
                        + "from their plate fragments", shipped);
            }
        }

        // v105 -> v106: the chemical rounds opt into the jet-residual and blast/scab models. Same
        // rule as the darts: only where the round still holds its shipped penetration, and the
        // block arrives whole.
        if (loaded.chem == null) {
            loaded.chem = defaults.chem;
            VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: added the chem block - HEAT and "
                    + "HE now measure their damage from the jet and the plate scab");
        }
        if (loaded.ammo_penetration != null) {
            int chemical = 0;
            chemical += shipChemical(loaded.ammo_penetration.get("armor_pioneer:cannon_120mm_heat"),
                    480.0F, 1736.0F, null);
            chemical += shipChemical(loaded.ammo_penetration.get("armor_pioneer:cannon_120mm_he"),
                    60.0F, null, 3.5F);
            chemical += shipChemical(loaded.ammo_penetration.get("armor_pioneer:cannon_120mm_kstam"),
                    200.0F, 923.0F, null);
            chemical += shipChemical(loaded.ammo_penetration.get("armor_pioneer:cannon_125mm_heat"),
                    550.0F, 2030.0F, null);
            chemical += shipChemical(loaded.ammo_penetration.get("armor_pioneer:cannon_125mm_he"),
                    60.0F, null, 4.0F);
            if (chemical > 0) {
                VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: {} chemical round(s) now "
                        + "use the jet / blast model", chemical);
            }
        }

        // v106 -> v107: the VT-4's lateral-effect round opts into its own model, and the bonus band
        // it used to roll is retired. The band is removed only where the entry still holds the
        // shipped pair - a player who tuned it keeps the old behaviour rather than being handed a
        // model measured with a mass they never entered.
        if (loaded.pele == null) {
            loaded.pele = defaults.pele;
            VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: added the pele block - the "
                    + "lateral-effect round is measured from its perforation energy");
        }
        if (loaded.ammo_penetration != null && defaults.ammo_penetration != null
                && shipPele(loaded.ammo_penetration.get("armor_pioneer:cannon_125mm_pele"),
                        defaults.ammo_penetration.get("armor_pioneer:cannon_125mm_pele"))) {
            VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: the BTS8 now sprays fragments "
                    + "in proportion to the plate it perforates");
        }

        // v107 -> v108: the Tiger's three rounds state their projectiles. The PzGr 39's bonus band
        // is replaced by its base charge, so the band is retired - only on the shipped entry, so a
        // player who tuned it keeps the old answer. The APCR and the HE shell have no band; their
        // figures arrive where the entry still holds the shipped penetration.
        if (loaded.ammo_penetration != null && defaults.ammo_penetration != null) {
            int tiger = 0;
            tiger += shipTigerKinetic(
                    loaded.ammo_penetration.get("tank_assault:cannon_88mm_pzgr_39"),
                    defaults.ammo_penetration.get("tank_assault:cannon_88mm_pzgr_39"), true);
            tiger += shipTigerKinetic(
                    loaded.ammo_penetration.get("tank_assault:cannon_88mm_pzgr_40"),
                    defaults.ammo_penetration.get("tank_assault:cannon_88mm_pzgr_40"), false);
            tiger += shipTigerHe(
                    loaded.ammo_penetration.get("tank_assault:cannon_88mm_sprgr_43"),
                    defaults.ammo_penetration.get("tank_assault:cannon_88mm_sprgr_43"));
            if (tiger > 0) {
                VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: {} Tiger round(s) now state "
                        + "their projectile; the PzGr 39 bursts from its base charge", tiger);
            }
        }

        // v108 -> v109: the rest of the Armor Pioneer fleet. The VT-5 / ZTZ-100 105mm family, the
        // Type 10's and the Leopard 2A4's 120mm family, and the Tianma-20's 125mm. Every rule is
        // the one the earlier rounds already follow: kinetic inputs arrive only where the round
        // still holds its shipped penetration (each load checked separately), chemical inputs the
        // same, and a charge shell's old flat figures are cleared only where they are still the
        // shipped pair.
        if (loaded.ammo_penetration != null && defaults.ammo_penetration != null) {
            int fleet = 0;
            fleet += shipKineticModel(loaded.ammo_penetration.get("armor_pioneer:cannon_105mm"),
                    defaults.ammo_penetration.get("armor_pioneer:cannon_105mm"), 550.0F);
            fleet += shipKineticVariant(loaded.ammo_penetration.get("armor_pioneer:cannon_105mm"),
                    defaults.ammo_penetration.get("armor_pioneer:cannon_105mm"), 450.0F);
            fleet += shipKineticModel(
                    loaded.ammo_penetration.get("armor_pioneer:cannon_105mm_ztz100"),
                    defaults.ammo_penetration.get("armor_pioneer:cannon_105mm_ztz100"), 720.0F);
            fleet += shipKineticModel(
                    loaded.ammo_penetration.get("armor_pioneer:cannon_120mm_type10"),
                    defaults.ammo_penetration.get("armor_pioneer:cannon_120mm_type10"), 600.0F);
            fleet += shipKineticVariant(
                    loaded.ammo_penetration.get("armor_pioneer:cannon_120mm_type10"),
                    defaults.ammo_penetration.get("armor_pioneer:cannon_120mm_type10"), 480.0F);
            fleet += shipKineticModel(loaded.ammo_penetration.get("armor_pioneer:cannon_125mm_dprk"),
                    defaults.ammo_penetration.get("armor_pioneer:cannon_125mm_dprk"), 630.0F);
            fleet += shipKineticVariant(
                    loaded.ammo_penetration.get("armor_pioneer:cannon_125mm_dprk"),
                    defaults.ammo_penetration.get("armor_pioneer:cannon_125mm_dprk"), 457.0F);
            fleet += shipKineticModel(loaded.ammo_penetration.get("armor_pioneer:cannon_120mm_dm33"),
                    defaults.ammo_penetration.get("armor_pioneer:cannon_120mm_dm33"), 480.0F);
            fleet += shipKineticVariant(
                    loaded.ammo_penetration.get("armor_pioneer:cannon_120mm_dm33"),
                    defaults.ammo_penetration.get("armor_pioneer:cannon_120mm_dm33"), 400.0F);
            fleet += shipChemical(loaded.ammo_penetration.get("armor_pioneer:cannon_105mm_heat"),
                    450.0F, 1615.0F, null);
            fleet += shipChemical(loaded.ammo_penetration.get("armor_pioneer:cannon_105mm_bunker"),
                    600.0F, 2220.0F, null);
            fleet += shipChemical(loaded.ammo_penetration.get("armor_pioneer:cannon_105mm_atgm"),
                    700.0F, 2622.0F, null);
            fleet += shipChemical(
                    loaded.ammo_penetration.get("armor_pioneer:cannon_125mm_dprk_heat"),
                    520.0F, 1897.0F, null);
            fleet += shipChemical(loaded.ammo_penetration.get("armor_pioneer:cannon_120mm_dm12"),
                    480.0F, 1736.0F, null);
            // The Type 10's multi-purpose round has two models, and the proximity one is a round
            // of its own inside it.
            Ammo jm12 = loaded.ammo_penetration.get("armor_pioneer:cannon_120mm_jm12");
            if (jm12 != null && jm12.penetration == 480.0F) {
                if (jm12.heat_damage == null) {
                    jm12.heat_damage = 1736.0F;
                    fleet++;
                }
                if (jm12.proximity != null && jm12.proximity.heat_damage == null) {
                    jm12.proximity.heat_damage = 1736.0F;
                    fleet++;
                }
            }
            fleet += shipChargeShell(loaded.ammo_penetration.get("armor_pioneer:cannon_105mm_he"),
                    defaults.ammo_penetration.get("armor_pioneer:cannon_105mm_he"));
            fleet += shipChargeShell(
                    loaded.ammo_penetration.get("armor_pioneer:cannon_125mm_dprk_he"),
                    defaults.ammo_penetration.get("armor_pioneer:cannon_125mm_dprk_he"));
            fleet += shipChargeShell(loaded.ammo_penetration.get("armor_pioneer:cannon_120mm_dm11"),
                    defaults.ammo_penetration.get("armor_pioneer:cannon_120mm_dm11"));
            if (fleet > 0) {
                VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: {} Armor Pioneer round(s) "
                        + "now state their projectile or charge", fleet);
            }
        }

        // v109 -> v110: the rest of the composite fleet takes the direction model the K2 and the
        // VT-4 have had since v93 and v97. Their tables are equivalents along the nose - a
        // composite array published as the protection it gives head-on - so no thickness moves;
        // only the switch is filled, and only where the file has none.
        for (String id : new String[]{"armor_pioneer:vt5", "armor_pioneer:type10",
                "armor_pioneer:tianma20", "armor_pioneer:ztz100", "armor_pioneer:leopard2a4"}) {
            compositeAngleScaling(loaded, defaults, id);
        }
    }

    /** {@link #shipKineticModel} on the slot's first load, which has its own penetration. */
    private static int shipKineticVariant(Ammo loaded, Ammo fresh, float shippedPenetration) {
        if (loaded == null || fresh == null || loaded.variants == null || loaded.variants.isEmpty()
                || fresh.variants == null || fresh.variants.isEmpty()) {
            return 0;
        }
        return shipKineticModel(loaded.variants.get(0), fresh.variants.get(0), shippedPenetration);
    }

    /**
     * Moves a high-explosive shell from its flat inside figure to its charge, where it is still the
     * shipped entry. The flat numbers are cleared only when they are the pair the helper used to
     * write (a 2000- or 1200-point burst with the 300-1000 spall band).
     */
    private static int shipChargeShell(Ammo loaded, Ammo fresh) {
        if (loaded == null || fresh == null || loaded.penetration != fresh.penetration
                || loaded.charge_kg != null) {
            return 0;
        }
        loaded.charge_kg = fresh.charge_kg;
        if (loaded.penetration_damage != null
                && (loaded.penetration_damage == 2000.0F || loaded.penetration_damage == 1200.0F)
                && loaded.spall_damage_min == 300.0F && loaded.spall_damage_max == 1000.0F) {
            loaded.penetration_damage = null;
            loaded.spall_damage_min = 0.0F;
            loaded.spall_damage_max = 0.0F;
            loaded.spall_falloff_to = 0.0F;
        }
        return 1;
    }

    /**
     * Moves a Tiger kinetic round onto the debris model, where it still holds the shipped
     * penetration. The APHE round's bonus band is retired only if it is still the shipped one.
     */
    private static int shipTigerKinetic(Ammo loaded, Ammo fresh, boolean aphe) {
        if (loaded == null || fresh == null || loaded.penetration != fresh.penetration) {
            return 0;
        }
        if (aphe && loaded.penetration_bonus_max != null) {
            boolean shippedBand = loaded.penetration_bonus_max == 152.0F
                    && loaded.penetration_bonus_min != null
                    && loaded.penetration_bonus_min == 20.0F
                    && loaded.penetration_bonus_max_at != null
                    && loaded.penetration_bonus_max_at == 152.0F;
            if (!shippedBand) {
                return 0;
            }
            loaded.penetration_bonus_min = null;
            loaded.penetration_bonus_max = null;
            loaded.penetration_bonus_max_at = null;
        }
        boolean moved = false;
        if (loaded.mass_kg == null) {
            loaded.mass_kg = fresh.mass_kg;
            moved = true;
        }
        if (loaded.muzzle_velocity == null) {
            loaded.muzzle_velocity = fresh.muzzle_velocity;
            moved = true;
        }
        if (loaded.caliber_mm == null) {
            loaded.caliber_mm = fresh.caliber_mm;
            moved = true;
        }
        if (loaded.velocity_exponent == null && fresh.velocity_exponent != null) {
            loaded.velocity_exponent = fresh.velocity_exponent;
            moved = true;
        }
        if (aphe && loaded.charge_kg == null) {
            loaded.charge_kg = fresh.charge_kg;
            moved = true;
        }
        return moved ? 1 : 0;
    }

    /** Moves the Tiger's HE shell onto the blast model where it still holds its shipped figures. */
    private static int shipTigerHe(Ammo loaded, Ammo fresh) {
        if (loaded == null || fresh == null || loaded.penetration != fresh.penetration) {
            return 0;
        }
        boolean moved = false;
        if (loaded.charge_kg == null) {
            loaded.charge_kg = fresh.charge_kg;
            moved = true;
        }
        if (loaded.penetration_damage != null && loaded.penetration_damage == 300.0F
                && loaded.spall_damage_min == 50.0F && loaded.spall_damage_max == 150.0F) {
            loaded.penetration_damage = null;
            loaded.spall_damage_min = 0.0F;
            loaded.spall_damage_max = 0.0F;
        }
        return moved ? 1 : 0;
    }

    /**
     * Moves the lateral-effect round from the bonus band to the model, only where the entry is
     * still the shipped one. Returns whether anything changed.
     */
    private static boolean shipPele(Ammo loaded, Ammo fresh) {
        if (loaded == null || fresh == null || loaded.penetration != fresh.penetration) {
            return false;
        }
        if (loaded.penetration_bonus_max != null) {
            boolean shippedBand = loaded.penetration_bonus_max == 300.0F
                    && loaded.penetration_bonus_min != null
                    && loaded.penetration_bonus_min == 20.0F
                    && loaded.penetration_bonus_max_at != null
                    && loaded.penetration_bonus_max_at == 150.0F;
            if (!shippedBand) {
                return false;
            }
            loaded.penetration_bonus_min = null;
            loaded.penetration_bonus_max = null;
            loaded.penetration_bonus_max_at = null;
        }
        boolean moved = false;
        if (loaded.pele == null) {
            loaded.pele = Boolean.TRUE;
            moved = true;
        }
        if (loaded.mass_kg == null) {
            loaded.mass_kg = fresh.mass_kg;
            moved = true;
        }
        if (loaded.muzzle_velocity == null) {
            loaded.muzzle_velocity = fresh.muzzle_velocity;
            moved = true;
        }
        if (loaded.caliber_mm == null) {
            loaded.caliber_mm = fresh.caliber_mm;
            moved = true;
        }
        return moved;
    }

    /**
     * Copies a chemical round's paper model inputs in, only where the round still holds the
     * penetration the fresh entry was rated at. Null means the field does not apply to this round.
     */
    private static int shipChemical(Ammo loaded, float shippedPenetration, Float heatDamage,
                                    Float chargeKg) {
        if (loaded == null || loaded.penetration != shippedPenetration) {
            return 0;
        }
        int moved = 0;
        if (heatDamage != null && loaded.heat_damage == null) {
            loaded.heat_damage = heatDamage;
            moved++;
        }
        if (chargeKg != null && loaded.charge_kg == null) {
            loaded.charge_kg = chargeKg;
            moved++;
        }
        return moved > 0 ? 1 : 0;
    }

    /**
     * Copies the debris-model inputs from a fresh round to a loaded one, but only when the loaded
     * round still has the penetration the fresh one was rated at. A round whose figure a player has
     * changed is theirs, and re-measuring it with a mass and a velocity they did not choose would
     * change an answer they did.
     */
    private static int shipKineticModel(Ammo loaded, Ammo fresh, float shippedPenetration) {
        if (loaded == null || fresh == null || loaded.mass_kg != null) {
            return 0;
        }
        if (loaded.penetration != shippedPenetration) {
            return 0;
        }
        loaded.mass_kg = fresh.mass_kg;
        loaded.muzzle_velocity = fresh.muzzle_velocity;
        loaded.mass_exponent = fresh.mass_exponent;
        loaded.velocity_exponent = fresh.velocity_exponent;
        loaded.caliber_mm = fresh.caliber_mm;
        return 1;
    }

    /**
     * @return the penetration figures for a TACZ cartridge, or {@code null} when this addon has
     *         never rated it - which leaves the round to TACZ, damage and all.
     *
     * <p>Resolved by the cartridge the bullet was fired with, not by the gun that fired it. That is
     * a deliberate difference from {@link #ammoFor}, whose keys are weapon ids: a tank's gun and its
     * round are one object and never come apart, while a cartridge is a cartridge whatever is
     * chambered for it.
     */
    public static Ammo taczRoundFor(ResourceLocation ammoId) {
        if (profile == null || profile.tacz_rounds == null || ammoId == null) {
            return null;
        }
        return profile.tacz_rounds.get(ammoId.toString());
    }

    /**
     * @return every rated TACZ cartridge, keyed by ammunition id.
     *
     * <p>Unmodifiable for the same reason {@link #ammunition()} is: the compendium reads it, and a
     * book that could write back into the loaded config would be a way to change armour values by
     * turning a page.
     */
    public static Map<String, Ammo> taczRounds() {
        if (profile == null || profile.tacz_rounds == null) {
            return java.util.Map.of();
        }
        return java.util.Collections.unmodifiableMap(profile.tacz_rounds);
    }

    /**
     * @return the settings vanilla Minecraft's weapons are measured with; never null.
     *
     * <p>Defaults are returned before the config is loaded and for a file that somehow lost the
     * block, because the alternative - a null check at the hit site - is how a feature silently
     * stops working when a hand edit removes one key.
     */
    public static VanillaWeapons vanillaWeapons() {
        if (profile == null || profile.vanilla_weapons == null) {
            return VANILLA_DEFAULTS;
        }
        return profile.vanilla_weapons;
    }

    /**
     * @return the settings vanilla Minecraft's explosions are measured with; never null.
     *
     * <p>Same contract as {@link #vanillaWeapons()}: defaults before the config is loaded and for
     * a file that somehow lost the block.
     */
    public static VanillaExplosions vanillaExplosions() {
        if (profile == null || profile.vanilla_explosions == null) {
            return VANILLA_EXPLOSION_DEFAULTS;
        }
        return profile.vanilla_explosions;
    }

    /**
     * @return the settings collision damage is measured with; never null.
     *
     * <p>Same contract as {@link #vanillaWeapons()}: defaults before the config is loaded and for
     * a file that somehow lost the block.
     */
    public static VehicleCollisions vehicleCollisions() {
        if (profile == null || profile.vehicle_collisions == null) {
            return VEHICLE_COLLISION_DEFAULTS;
        }
        return profile.vehicle_collisions;
    }

    /**
     * @return the settings the high-speed ram is run with; never null.
     *
     * <p>Same contract as {@link #vehicleCollisions()}.
     */
    public static VehicleRamming vehicleRamming() {
        if (profile == null || profile.vehicle_ramming == null) {
            return VEHICLE_RAMMING_DEFAULTS;
        }
        return profile.vehicle_ramming;
    }

    /**
     * Moves the K2's track-strip inner edge onto the link model's figure.
     *
     * <p>The K2's track is no longer the display model's fixed tread loop: the pack builds the
     * visible link model along the 0.6.2 structure path, which also had to move from x = +-2.169
     * onto the display track centre (+-1.704) or the links would sit outside the wheels. The
     * shipped {@code inner_edge} 1.56 was derived from that old, outboard path; against the new
     * one it would start 0.18 of the hull half-width too far out and leave the inner edge of every
     * link rated as hull. The link model's inner face is 1.005, so 1.00 catches it whole.
     *
     * <p>Only the shipped figure is moved. A player who typed a different edge meant it, and the
     * same rule guards every other value migration here.
     *
     * <p>The same call fills the K2 suspension's {@code trackCenterOffset}: a file written at v53
     * has the suspension block but no such field, and without it the link model's lower run sits
     * 0.0654 blocks into the ground. Zero is the old behaviour; any non-zero value was chosen.
     */
    private static void moveK2TrackInner(Profile loaded, Profile defaults) {
        if (loaded.vehicles != null && defaults.vehicles != null) {
            VehicleArmor mine = loaded.vehicles.get("armor_pioneer:k2");
            VehicleArmor theirs = defaults.vehicles.get("armor_pioneer:k2");
            if (mine != null && mine.track_ends != null && theirs != null
                    && theirs.track_ends != null
                    && Math.abs(mine.track_ends.inner_edge - 1.56F) < 1.0E-4F) {
                mine.track_ends.inner_edge = theirs.track_ends.inner_edge;
                VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: the K2's track strip moved "
                        + "inboard with its link-model track ({} -> {})", 1.56F,
                        theirs.track_ends.inner_edge);
            }
        }
        // A file written at v53 already has the K2's suspension block, but not this field - Gson
        // leaves it at zero, which is the old behaviour. Fill it; a non-zero value was chosen.
        if (loaded.suspensions != null && defaults.suspensions != null) {
            Suspension.Settings mine = loaded.suspensions.get("armor_pioneer:k2");
            Suspension.Settings theirs = defaults.suspensions.get("armor_pioneer:k2");
            if (mine != null && theirs != null && mine.trackCenterOffset == 0.0F) {
                mine.trackCenterOffset = theirs.trackCenterOffset;
                VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: the K2's track floating "
                        + "reference gained its {} block offset", theirs.trackCenterOffset);
            }
            if (mine != null && theirs != null && mine.max_pitch == 2.0F
                    && theirs.max_pitch == 5.0F) {
                mine.max_pitch = theirs.max_pitch;
                VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: the K2's pitch range opened "
                        + "to +-{} degrees", theirs.max_pitch);
            }
            // A file written at v55 has the suspension block but no lateral axis at all: Gson
            // leaves a field it never saw at zero, which is the old, level-only behaviour. Fill
            // only the shipped zero - a player who typed a range meant it.
            if (mine != null && theirs != null && mine.max_roll == 0.0F && theirs.max_roll > 0.0F) {
                mine.max_roll = theirs.max_roll;
                VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: the K2's suspension gained "
                        + "its {} degree lateral axis", theirs.max_roll);
            }
        }
    }

    /**
     * Moves the VT-5's and the VT-4's track-strip inner edges onto their link-model paths.
     *
     * <p>Both vehicles drew their tracks as plates baked into the display model until v69, when
     * they took the K2's TrackUnit link belt. Their structure track paths were stale - each one
     * mirrored onto the opposite side and half a block outboard of the display tread loop - so the
     * paths were rebuilt onto that loop, and the shipped {@code inner_edge} 1.19 had been measured
     * off the old ones. Against the new path it would start about a third of the hull half-width
     * outside the real track and rate its inner edge as hull. Measured: 0.874 for the VT-5 and
     * 0.877 for the VT-4, so the shipped 0.87 catches both whole.
     *
     * <p>Only the shipped figure is moved. A player who typed a different edge meant it, the same
     * rule every other value migration here follows. The ZTZ-100 also switched its track model in
     * the pack, but its structure path already had the display loop's lateral position, so its
     * measured edge did not change and its shipped 1.175 stays.
     */
    private static void moveVt5Vt4TrackInner(Profile loaded, Profile defaults) {
        if (loaded.vehicles == null || defaults.vehicles == null) {
            return;
        }
        for (String id : new String[] {"armor_pioneer:vt5", "armor_pioneer:vt4"}) {
            VehicleArmor mine = loaded.vehicles.get(id);
            VehicleArmor shipped = defaults.vehicles.get(id);
            if (mine != null && mine.track_ends != null && shipped != null
                    && shipped.track_ends != null
                    && Math.abs(mine.track_ends.inner_edge - 1.19F) < 1.0E-4F) {
                mine.track_ends.inner_edge = shipped.track_ends.inner_edge;
                VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: the {} track strip moved "
                        + "inboard with its link-model track ({} -> {})", id, 1.19F,
                        shipped.track_ends.inner_edge);
            }
        }
    }

    /**
     * Opens the Type 10's lateral axis to the four degrees the K2's block carries.
     *
     * <p>The v61 block gave the Type 10 the K2's height bracket and pitch but no lateral axis, so
     * a v61 or v62 file has {@code max_roll} at the shipped zero and the left and right keys fall
     * through to the gunner's fuze keys. Gson has read that field since v56, which means a missing
     * key and a deliberate zero are indistinguishable here; only the shipped zero is opened, the
     * same rule the K2's own lateral-axis fill follows. A file older than v61 has no Type 10 block
     * at all, and {@link #adoptSuspensions} adds it whole from the new default, so it never
     * reaches this branch.
     *
     * <p>The variable names are their own rather than the K2 method's: the two methods sit in the
     * same class, and Java lets a local in one shadow nothing in the other, but a copy-paste edit
     * that merged them would have collided silently.
     */
    private static void openType10Lean(Profile loaded, Profile defaults) {
        if (loaded.suspensions == null || defaults.suspensions == null) {
            return;
        }
        Suspension.Settings type10 = loaded.suspensions.get("armor_pioneer:type10");
        Suspension.Settings shipped = defaults.suspensions.get("armor_pioneer:type10");
        if (type10 != null && shipped != null && type10.max_roll == 0.0F
                && shipped.max_roll > 0.0F) {
            type10.max_roll = shipped.max_roll;
            VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: the Type 10's suspension gained "
                    + "the K2's {} degree lateral axis", shipped.max_roll);
        }
    }

    /**
     * Moves the Type 10's armour table from the v63 figures to the v64 ones.
     *
     * <p><b>Why it has to be migrated rather than only changed in the default.</b> The profile is
     * written to disk and read back on every launch; a file already at v63 carries the old numbers,
     * and changing {@link #addType10} alone would leave every existing install on a table nobody
     * can see a difference in until they delete the file.
     *
     * <p><b>Face by face, and only the shipped figure.</b> Each field is replaced only if it still
     * holds the value the addon wrote. A player who has rated the tank themselves keeps their
     * numbers, and one edited face does not stop the other four from moving - which is the same
     * rule {@link #regradeRoadWheels} follows for a single field.
     *
     * <p><b>Three tables move together.</b> The kinetic set, the {@code heat} override, and the
     * {@code he} override that {@link #thinRoofs} built from the chemical one. The last is a copy
     * with its own roof and floor figures, so its old and new arrays differ from the chemical
     * table's in those two slots and must be listed separately: running the chemical array over it
     * would leave the HE table holding the old 60/50 roofs the copy never had.
     */
    private static void regradeType10(Profile loaded) {
        if (loaded.vehicles == null) {
            return;
        }
        VehicleArmor type10 = loaded.vehicles.get("armor_pioneer:type10");
        if (type10 == null) {
            return;
        }

        int moved = regradeFaces(type10.turret,
                new float[]{680.0F, 120.0F, 70.0F, 40.0F, 30.0F},
                new float[]{650.0F, 90.0F, 60.0F, 35.0F, 25.0F});
        moved += regradeFaces(type10.hull,
                new float[]{620.0F, 90.0F, 60.0F, 30.0F, 25.0F},
                new float[]{620.0F, 70.0F, 55.0F, 30.0F, 25.0F});
        moved += regradeWeakSpot(type10.turret_weak_spot, 480.0F, 450.0F);
        if (type10.hull_front_split != null) {
            moved += regradePlate(type10.hull_front_split.lower, 200.0F, 190.0F);
        }

        if (type10.overrides != null) {
            // The chemical table: fronts 1200/1100 -> 1100/1050, sides 320/120 and 160/90 down,
            // roofs and floors each one step lower.
            moved += regradeOverride(type10.overrides.get("heat"),
                    new float[]{1200.0F, 320.0F, 120.0F, 60.0F, 50.0F},
                    new float[]{1100.0F, 250.0F, 110.0F, 55.0F, 45.0F},
                    new float[]{1100.0F, 160.0F, 90.0F, 60.0F, 45.0F},
                    new float[]{1050.0F, 110.0F, 85.0F, 55.0F, 40.0F},
                    620.0F, 570.0F, 1100.0F, 1050.0F, 300.0F, 280.0F);
            // The HE copy: same fronts and sides, roofs 30/30 and 30/25.
            moved += regradeOverride(type10.overrides.get("he"),
                    new float[]{1200.0F, 320.0F, 120.0F, 30.0F, 30.0F},
                    new float[]{1100.0F, 250.0F, 110.0F, 30.0F, 30.0F},
                    new float[]{1100.0F, 160.0F, 90.0F, 30.0F, 25.0F},
                    new float[]{1050.0F, 110.0F, 85.0F, 30.0F, 25.0F},
                    620.0F, 570.0F, 1100.0F, 1050.0F, 300.0F, 280.0F);
        }

        if (moved > 0) {
            VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: the Type 10's armour table was "
                    + "regraded to sit at or below the K2's on every face - {} figure(s) moved "
                    + "(turret {} / hull {}, chemical turret {} / hull {})",
                    moved, type10.turret.front, type10.hull.front,
                    type10.overrides == null || type10.overrides.get("heat") == null
                            ? 0.0F : type10.overrides.get("heat").turret.front,
                    type10.overrides == null || type10.overrides.get("heat") == null
                            ? 0.0F : type10.overrides.get("heat").hull.front);
        }
    }

    /** Moves each of the five faces that still holds its shipped figure. @return how many moved. */
    private static int regradeFaces(ArmorSet set, float[] shipped, float[] corrected) {
        if (set == null) {
            return 0;
        }
        int moved = 0;
        if (set.front == shipped[0]) {
            set.front = corrected[0];
            moved++;
        }
        if (set.side == shipped[1]) {
            set.side = corrected[1];
            moved++;
        }
        if (set.rear == shipped[2]) {
            set.rear = corrected[2];
            moved++;
        }
        if (set.roof == shipped[3]) {
            set.roof = corrected[3];
            moved++;
        }
        if (set.floor == shipped[4]) {
            set.floor = corrected[4];
            moved++;
        }
        return moved;
    }

    /** @return 1 when the plate still held its shipped thickness and was moved, else 0. */
    private static int regradePlate(Plate plate, float shipped, float corrected) {
        if (plate == null || plate.thickness != shipped) {
            return 0;
        }
        plate.thickness = corrected;
        return 1;
    }

    /** The {@link WeakSpot} form, which is a turret face rather than a bolted-on plate. */
    private static int regradeWeakSpot(WeakSpot spot, float shipped, float corrected) {
        if (spot == null || spot.thickness != shipped) {
            return 0;
        }
        spot.thickness = corrected;
        return 1;
    }

    /**
     * The five-face regrade for one armour override, plus its gun-mount plate and both front
     * plates. Absent pieces are skipped, which is what a hand-edited file looks like.
     */
    private static int regradeOverride(ArmorOverride override,
                                       float[] shippedTurret, float[] correctedTurret,
                                       float[] shippedHull, float[] correctedHull,
                                       float shippedWeakSpot, float correctedWeakSpot,
                                       float shippedUpper, float correctedUpper,
                                       float shippedLower, float correctedLower) {
        if (override == null) {
            return 0;
        }
        int moved = regradeFaces(override.turret, shippedTurret, correctedTurret);
        moved += regradeFaces(override.hull, shippedHull, correctedHull);
        moved += regradePlate(override.turret_weak_spot, shippedWeakSpot, correctedWeakSpot);
        if (override.hull_front_split != null) {
            moved += regradePlate(override.hull_front_split.upper, shippedUpper, correctedUpper);
            moved += regradePlate(override.hull_front_split.lower, shippedLower, correctedLower);
        }
        return moved;
    }

    /**
     * Gives the Armor Pioneer vehicles their engine figures and files the smoke launcher under the
     * turret.
     *
     * <p>Both are new in v58 for the first three, and neither is a value on a key that already
     * existed in a form the generic loops can find. {@code engine_horsepower} and
     * {@code horsepower_per_ton} are plain fields: Gson leaves a field an older file never saw at
     * its initialiser, which is zero, so the shipped zero is the only figure that can be filled. A
     * player who typed 1,200 keeps it.
     *
     * <p>The smoke launcher is a new part unit in the pack, and {@code turret_part_ids} is the list
     * that says a hit on a part is measured as turret. Leaving the id out is not an error the game
     * reports - the launcher is simply rated as hull, its small boxes reading as a 40mm flank on a
     * tank whose turret front is 240. So the id is appended to a hand-written list, which changes
     * nothing else about it. A list that is missing or empty is replaced whole: there is no player
     * choice in it to preserve, and the one-entry fallback {@code sanitize} would otherwise write
     * would rate every part but the turret as hull.
     *
     * <p>The Type 10 and the Tianma-20 joined this loop in v62, when their marked smoke models
     * arrived. Their engine fields were already filled by the v60 vehicle loop, so for them this
     * only appends the smoke id - but the same code path, intentionally, so the two cannot drift.
     * The ZTZ-100 joins in v65 for the same reason: it arrived in the defaults with its hybrid
     * engine and its smoke bank together.
     */
    /**
     * v90 -> v91: the Tank Assault armour and ammunition figures were regraded against the
     * published plates and gun tables (the rules are in {@link #addTankAssaultGround}).
     *
     * <p>A vehicle is replaced whole only where every figure that decides its armour is still the
     * figure this addon shipped - both kinetic tables, both chemical tables, the damage multiplier
     * and the engine rating. One edited value means the vehicle is the player's and is left alone.
     * The ammunition entries move the same way: a penetration still equal to the old shipped
     * figure moves, a hand-rated one stays.
     */
    private static void regradeTankAssault(Profile loaded, Profile defaults) {
        if (loaded.vehicles == null || defaults.vehicles == null) {
            return;
        }
        // Kinetic and chemical tables, field by field. A face moves only where the file still holds
        // the figure this addon shipped before the regrade; a hand-set face is left alone.
        ship("tank_assault:a22f", loaded, defaults,
                new float[]{152, 95, 95, 25, 25}, new float[]{152, 95, 51, 20, 12},
                new float[]{152, 95, 95, 25, 25}, new float[]{152, 95, 51, 20, 12});
        ship("tank_assault:matilda_mk1", loaded, defaults,
                new float[]{60, 60, 60, 10, 10}, new float[]{60, 60, 60, 10, 10},
                new float[]{60, 60, 60, 10, 10}, new float[]{60, 60, 60, 10, 10});
        ship("tank_assault:valentine_mk2", loaded, defaults,
                new float[]{65, 60, 60, 20, 15}, new float[]{60, 50, 50, 20, 15},
                new float[]{65, 60, 60, 20, 15}, new float[]{60, 50, 50, 20, 15});
        ship("tank_assault:qf17pdr", loaded, defaults,
                new float[]{8, 8, 8, 2, 2}, new float[]{8, 8, 8, 2, 2},
                new float[]{8, 8, 8, 2, 2}, new float[]{8, 8, 8, 2, 2});
        ship("tank_assault:qf25", loaded, defaults,
                new float[]{8, 8, 8, 2, 2}, new float[]{8, 8, 8, 2, 2},
                new float[]{8, 8, 8, 2, 2}, new float[]{8, 8, 8, 2, 2});
        ship("tank_assault:panzer4h", loaded, defaults,
                new float[]{80, 30, 30, 16, 10}, new float[]{80, 30, 20, 12, 10},
                new float[]{80, 30, 30, 16, 10}, new float[]{80, 30, 20, 12, 10});
        ship("tank_assault:ferdinand", loaded, defaults,
                new float[]{200, 80, 80, 30, 20}, new float[]{200, 80, 80, 30, 20},
                new float[]{200, 80, 80, 30, 20}, new float[]{200, 80, 80, 30, 20});
        ship("tank_assault:sturmtiger", loaded, defaults,
                new float[]{150, 80, 80, 40, 20}, new float[]{150, 80, 80, 40, 20},
                new float[]{150, 80, 80, 40, 20}, new float[]{150, 80, 80, 40, 20});
        ship("tank_assault:sdkfz251", loaded, defaults,
                new float[]{10, 10, 10, 0, 0}, new float[]{15, 8, 8, 0, 6},
                new float[]{10, 10, 10, 0, 0}, new float[]{15, 8, 8, 0, 6});
        ship("tank_assault:sdkfz2", loaded, defaults,
                new float[]{2, 2, 2, 2, 2}, new float[]{2, 2, 2, 2, 2},
                new float[]{2, 2, 2, 2, 2}, new float[]{2, 2, 2, 2, 2});
        ship("tank_assault:bmw_r75", loaded, defaults,
                new float[]{2, 2, 2, 2, 2}, new float[]{2, 2, 2, 2, 2},
                new float[]{2, 2, 2, 2, 2}, new float[]{2, 2, 2, 2, 2});
        ship("tank_assault:type82", loaded, defaults,
                new float[]{2, 2, 2, 2, 2}, new float[]{2, 2, 2, 2, 2},
                new float[]{2, 2, 2, 2, 2}, new float[]{2, 2, 2, 2, 2});
        ship("tank_assault:bt7v", loaded, defaults,
                new float[]{15, 15, 13, 10, 10}, new float[]{22, 15, 13, 10, 6},
                new float[]{15, 15, 13, 10, 10}, new float[]{20, 13, 13, 10, 6});
        ship("tank_assault:t26b", loaded, defaults,
                new float[]{15, 15, 15, 10, 6}, new float[]{15, 15, 15, 10, 6},
                new float[]{15, 15, 15, 10, 6}, new float[]{15, 15, 15, 10, 6});
        ship("tank_assault:kv1mod1942", loaded, defaults,
                new float[]{100, 90, 90, 40, 30}, new float[]{100, 75, 70, 35, 30},
                new float[]{90, 75, 75, 40, 30}, new float[]{75, 75, 70, 35, 30});
        ship("tank_assault:t3485", loaded, defaults,
                new float[]{90, 75, 52, 20, 18}, new float[]{90, 45, 45, 20, 20},
                new float[]{75, 75, 52, 20, 18}, new float[]{45, 45, 45, 20, 20});
        ship("tank_assault:t44a", loaded, defaults,
                new float[]{120, 90, 75, 30, 20}, new float[]{90, 75, 45, 20, 20},
                new float[]{90, 75, 70, 30, 20}, new float[]{75, 75, 45, 20, 20});
        ship("tank_assault:su122", loaded, defaults,
                new float[]{45, 45, 45, 20, 15}, new float[]{45, 45, 45, 20, 15},
                new float[]{45, 45, 45, 20, 15}, new float[]{45, 45, 45, 20, 15});
        ship("tank_assault:p40", loaded, defaults,
                new float[]{50, 50, 40, 20, 15}, new float[]{50, 50, 40, 18, 14},
                new float[]{50, 50, 40, 20, 15}, new float[]{50, 50, 40, 18, 14});
        // The Panther: its old hull was 200/50/60/40/40 and its chemical hull 120/50/60/40/40.
        ship("tank_assault:panther", loaded, defaults,
                new float[]{110, 50, 50, 40, 40}, new float[]{200, 50, 60, 40, 40},
                new float[]{110, 50, 50, 40, 40}, new float[]{120, 50, 60, 40, 40});
        // The Tiger's hull front was set to the 60mm glacis figure on its own, below the 100mm
        // table; the live file therefore reads 60, not 100, and the old signature says so.
        ship("tank_assault:tiger", loaded, defaults,
                new float[]{100, 80, 80, 25, 28}, new float[]{60, 80, 80, 25, 25},
                new float[]{100, 80, 80, 25, 28}, new float[]{100, 80, 80, 25, 25});

        // Weak spots and glacis splits: separate fields from the tables.
        shipGunMount(loaded, "tank_assault:matilda_mk1", 50.0F, 65.0F);
        shipGunMount(loaded, "tank_assault:panzer4h", 80.0F, 50.0F);
        shipGunMount(loaded, "tank_assault:sdkfz251", 10.0F, 4.0F);
        shipGunMount(loaded, "tank_assault:p40", 50.0F, 60.0F);
        shipGunMount(loaded, "tank_assault:panther", 180.0F, 100.0F);
        shipHullWeak(loaded, "tank_assault:panther", 100.0F, 80.0F);
        shipSplit(loaded, "tank_assault:panther", 200.0F, 120.0F, 140.0F, 105.0F);
        shipOverrideSplit(loaded, "tank_assault:panther", 120.0F, 120.0F, 80.0F, 60.0F);
        shipSplit(loaded, "tank_assault:tiger", 60.0F, 100.0F, 100.0F, 100.0F);
        shipSplit(loaded, "tank_assault:ferdinand", 200.0F, 100.0F, 215.0F, 115.0F);
        shipSplit(loaded, "tank_assault:sturmtiger", 150.0F, 100.0F, 215.0F, 100.0F);

        regradeAircraft(loaded, defaults, "tank_assault:bf109e4",
                new float[]{2.0F, 2.0F, 2.0F, 2.0F, 3.0F}, 12.0F, 40.0F);
        regradeAircraft(loaded, defaults, "tank_assault:fw190a5",
                new float[]{2.5F, 2.0F, 2.0F, 2.0F, 3.0F}, 14.0F, 50.0F);
        regradeAircraft(loaded, defaults, "tank_assault:p51d20",
                new float[]{2.5F, 2.0F, 2.0F, 2.0F, 3.0F}, 13.0F, 40.0F);

        regradeAmmo(loaded, defaults, "tank_assault:cannon_40mm_ap_t_mk1", 53.0F);
        regradeAmmo(loaded, defaults, "tank_assault:cannon_40mm_aphe_t_mk1", 53.0F);
        regradeAmmo(loaded, defaults, "tank_assault:cannon_76mm_qf17_mk8", 170.0F);
        regradeAmmo(loaded, defaults, "tank_assault:cannon_87.6mm_qf25_ap_mk1t", 60.0F);
        regradeAmmo(loaded, defaults, "tank_assault:cannon_75mm_kwk40_pzgr39", 105.0F);
        regradeAmmo(loaded, defaults, "tank_assault:cannon_75mm_pzgr_39_42", 192.0F, 200.0F);
        regradeAmmo(loaded, defaults, "tank_assault:cannon_88mm_pzgr_39", 165.0F);
        regradeAmmo(loaded, defaults, "tank_assault:cannon_88mm_pzgr_40", 238.0F);
        regradeAmmo(loaded, defaults, "tank_assault:cannon_88mm_pzgr_39_43", 200.0F);
        regradeAmmo(loaded, defaults, "tank_assault:cannon_45mm_20k_br_240", 55.0F);
        regradeAmmo(loaded, defaults, "tank_assault:cannon_85mm_ubr_365", 100.0F);
        regradeAmmo(loaded, defaults, "tank_assault:cannon_122mm_m30_bp460a", 160.0F);
        regradeAmmo(loaded, defaults, "tank_assault:gun_mg17", 12.0F);
        regradeAmmo(loaded, defaults, "tank_assault:auto_cannon_mg151", 25.0F);
        regradeAmmo(loaded, defaults, "tank_assault:gun_m2_browning", 30.0F);
    }

    /**
     * v91 -> v92: the Panther's kinetic plates become the real thicknesses plus their slopes.
     *
     * <p>The v91 table stated 140mm for the glacis and 105mm for the lower plate: the line-of-sight
     * thicknesses 80mm and 60mm present at 55 degrees from dead ahead. Correct from the front, and
     * wrong from every other direction - a round 45 degrees off the nose met the same 140mm it
     * would have met head-on, when the plate really presents 197mm there. The table now states the
     * steel (80 and 60) and the vehicle states how far the plates lean, so the damage path can
     * work the equivalent out per hit. The turret moves the same way: 50/50 becomes 45/45 at 25
     * degrees, and the 110mm front plate gains its 11 degrees.
     *
     * <p>Every move is guarded by the figure it is moving. A face or a split the player has edited
     * is not one of ours any more and is left alone; a file that already carries slopes has had
     * this migration applied (or the player wrote their own) and keeps them. The chemical table is
     * deliberately not touched: it has been stated in nominal thicknesses since it was written,
     * which is what it should be, and slopes are kinetic-only so it is not measured against them.
     */
    private static void slopePanther(Profile loaded, Profile defaults) {
        if (loaded.vehicles == null || defaults.vehicles == null) {
            return;
        }
        String id = "tank_assault:panther";
        VehicleArmor mine = loaded.vehicles.get(id);
        VehicleArmor fresh = defaults.vehicles.get(id);
        if (mine == null || fresh == null) {
            return;
        }
        shipSet(mine.turret, fresh.turret, new float[]{110, 50, 50, 16, 16});
        shipSet(mine.hull, fresh.hull, new float[]{140, 58, 46, 16, 16});
        if (mine.hull_front_split != null) {
            shipFrontSplit(mine.hull_front_split, 140.0F, 105.0F, 80.0F, 60.0F);
        }
        boolean moved = false;
        if (mine.hull_slopes == null && fresh.hull_slopes != null) {
            mine.hull_slopes = fresh.hull_slopes;
            moved = true;
        }
        if (mine.turret_slopes == null && fresh.turret_slopes != null) {
            mine.turret_slopes = fresh.turret_slopes;
            moved = true;
        }
        if (moved) {
            VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: the Panther's plates are real "
                    + "thicknesses with a {} degree glacis now; the equivalent follows the angle a "
                    + "round arrives at", mine.hull_slopes == null ? 0.0F : mine.hull_slopes.front);
        }
    }

    /**
     * v92 -> v93: the VT-4 opts into the per-hit direction factor.
     *
     * <p>The switch is new, so a file written before it existed has no answer and inherits the
     * default's. A file that already carries one - true or false - has answered the question, and
     * overwriting it would be the migration deciding something the player did. No thickness moves:
     * the VT-4's tables are equivalents and stay as written, which is the whole reason it needs a
     * switch rather than the Panther's slope fields.
     */
    private static void vt4AngleScaling(Profile loaded, Profile defaults) {
        compositeAngleScaling(loaded, defaults, "armor_pioneer:vt4");
        // v96 -> v97: the K2 joins it. Its tables are draft equivalents from the same kind of
        // ceramic array, and the same rule applies - no slope to double-count the lean, but the
        // hit's deviation from the face normal does count.
        compositeAngleScaling(loaded, defaults, "armor_pioneer:k2");
    }

    private static void compositeAngleScaling(Profile loaded, Profile defaults, String id) {
        if (loaded.vehicles == null || defaults.vehicles == null) {
            return;
        }
        VehicleArmor mine = loaded.vehicles.get(id);
        VehicleArmor fresh = defaults.vehicles.get(id);
        if (mine == null || fresh == null || mine.kinetic_angle_scaling != null) {
            return;
        }
        mine.kinetic_angle_scaling = fresh.kinetic_angle_scaling;
        VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: {} composite tables now take the "
                + "hit angle into account; its reactive panels and bolted-on boxes do not", id);
    }

    /**
     * v93 -> v94: the VT-4's ricochet band becomes real steel at its own angle.
     *
     * <p>The band's 800 was an equivalent for the near-horizontal strip above the glacis, and the
     * per-hit direction factor measured it against the box's vertical front face - so a plunging
     * round met more of it than a frontal one. The steel plus the measured angle is the fix, and
     * the 125mm is the calibration that keeps the user's drawn 800 from dead ahead.
     *
     * <p>Only the shipped 800 moves: a band the player has valued themselves stays theirs, and
     * only the slope, which no file could have stated before this version, is filled.
     */
    private static void vt4RicochetBand(Profile loaded, Profile defaults) {
        if (loaded.vehicles == null || defaults.vehicles == null) {
            return;
        }
        VehicleArmor mine = loaded.vehicles.get("armor_pioneer:vt4");
        VehicleArmor fresh = defaults.vehicles.get("armor_pioneer:vt4");
        if (mine == null || fresh == null || mine.hull_front_split == null
                || fresh.hull_front_split == null) {
            return;
        }
        FrontSplit split = mine.hull_front_split;
        FrontSplit freshSplit = fresh.hull_front_split;
        boolean moved = false;
        if (split.upper_band != null && freshSplit.upper_band != null
                && split.upper_band.thickness == 800.0F) {
            split.upper_band.thickness = freshSplit.upper_band.thickness;
            moved = true;
        }
        if (split.upper_band_slope == 0.0F && freshSplit.upper_band_slope > 0.0F) {
            split.upper_band_slope = freshSplit.upper_band_slope;
            moved = true;
        }
        if (moved) {
            VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: VT-4 ricochet band is {}mm of "
                    + "steel at {} degrees now; its top is soft against plunging fire and its "
                    + "front is unchanged", split.upper_band == null ? 0.0F
                    : split.upper_band.thickness, split.upper_band_slope);
        }
    }

    /**
     * v97 -> v98: the Tiger E states the steel it is and its two AP curves.
     *
     * <p>The tables already held 100/80/80/25, which is what the plates are, so no thickness
     * moves except the mantlet: the gun-mount circle was 100 and the casting over the opening is
     * 120. The slopes are new fields - the hull's rear plate, and the two plates of the split -
     * filled where the file has none; an angle the player has stated stays. The two AP rounds
     * gain their curves where absent, anchored to whatever muzzle figure the round still holds.
     */
    private static void slopeTiger(Profile loaded, Profile defaults) {
        if (loaded.vehicles == null || defaults.vehicles == null) {
            return;
        }
        VehicleArmor mine = loaded.vehicles.get("tank_assault:tiger");
        VehicleArmor fresh = defaults.vehicles.get("tank_assault:tiger");
        if (mine == null || fresh == null) {
            return;
        }
        boolean moved = false;
        if (mine.hull_slopes == null && fresh.hull_slopes != null) {
            mine.hull_slopes = fresh.hull_slopes;
            moved = true;
        }
        FrontSplit split = mine.hull_front_split;
        FrontSplit freshSplit = fresh.hull_front_split;
        if (split != null && freshSplit != null) {
            if (split.upper_slope == 0.0F && freshSplit.upper_slope > 0.0F) {
                split.upper_slope = freshSplit.upper_slope;
                moved = true;
            }
            if (split.lower_slope == 0.0F && freshSplit.lower_slope > 0.0F) {
                split.lower_slope = freshSplit.lower_slope;
                moved = true;
            }
        }
        shipGunMount(loaded, "tank_assault:tiger", 100.0F, 120.0F);
        if (loaded.ammo_penetration != null) {
            shipRangeCurve(loaded.ammo_penetration.get("tank_assault:cannon_88mm_pzgr_39"),
                    TIGER_AP_RANGE_SHARE);
            shipRangeCurve(loaded.ammo_penetration.get("tank_assault:cannon_88mm_pzgr_40"),
                    TIGER_APCR_RANGE_SHARE);
        }
        if (moved) {
            VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: the Tiger's plates now state "
                    + "their own angles - 9 degrees upper front, 25 lower nose, 9 rear");
        }
    }

    /**
     * v95 -> v96: the Panther's AP and the VT-4's darts gain their range curves.
     *
     * <p>Only rounds that state no curve are filled. A curve is a statement about that round, and
     * one a player has written themselves is not this addon's to replace - the same rule every
     * value migration here holds to. The shape is anchored to the round's own penetration, so a
     * hand-tuned muzzle figure brings its decay with it and the two cannot disagree.
     */
    private static void ammoRangeFalloff(Profile loaded) {
        if (loaded.ammo_penetration == null) {
            return;
        }
        shipRangeCurve(loaded.ammo_penetration.get("tank_assault:cannon_75mm_pzgr_39_42"),
                APHE_RANGE_SHARE);
        shipRangeCurve(loaded.ammo_penetration.get("armor_pioneer:cannon_125mm"),
                DART_RANGE_SHARE);
        // v96 -> v97: the K2's 120mm, slot and DM53 load alike.
        shipRangeCurve(loaded.ammo_penetration.get("armor_pioneer:cannon_120mm"),
                DART_RANGE_SHARE);
    }

    private static void shipRangeCurve(Ammo round, float[] shape) {
        if (round == null) {
            return;
        }
        int added = 0;
        if (!round.hasRangeFalloff()) {
            round.falloffFromPenetration(shape);
            added++;
        }
        if (round.variants != null) {
            for (Ammo variant : round.variants) {
                if (variant != null && !variant.hasRangeFalloff()) {
                    variant.falloffFromPenetration(shape);
                    added++;
                }
            }
        }
        if (added > 0) {
            VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: {} gained a range curve "
                    + "({} point(s) filled)", round.name == null ? "an AP round" : round.name,
                    added);
        }
    }

    private static void ship(String id, Profile loaded, Profile defaults,
                             float[] oldTurret, float[] oldHull,
                             float[] oldChemicalTurret, float[] oldChemicalHull) {
        VehicleArmor mine = loaded.vehicles.get(id);
        VehicleArmor fresh = defaults.vehicles.get(id);
        if (mine == null || fresh == null) {
            return;
        }
        shipSet(mine.turret, fresh.turret, oldTurret);
        shipSet(mine.hull, fresh.hull, oldHull);
        ArmorOverride mineChemical = mine.overrides == null ? null : mine.overrides.get("heat");
        ArmorOverride freshChemical = fresh.overrides == null ? null : fresh.overrides.get("heat");
        if (mineChemical != null && freshChemical != null) {
            shipSet(mineChemical.turret, freshChemical.turret, oldChemicalTurret);
            shipSet(mineChemical.hull, freshChemical.hull, oldChemicalHull);
        }
        // The HE table is the chemical one restated; it has to move with it, or the two
        // kinds of round end up measured against different plates.
        ArmorOverride mineHe = mine.overrides == null ? null : mine.overrides.get("he");
        ArmorOverride freshHe = fresh.overrides == null ? null : fresh.overrides.get("he");
        if (mineHe != null && freshHe != null) {
            shipSet(mineHe.turret, freshHe.turret, oldChemicalTurret);
            shipSet(mineHe.hull, freshHe.hull, oldChemicalHull);
        }
        VehicleArmorMod.LOGGER.info(
                "[Vehicle Armor] upgrade: {} regraded against the published plates", id);
    }

    private static void shipSet(ArmorSet mine, ArmorSet fresh, float[] old) {
        if (mine == null || fresh == null) {
            return;
        }
        mine.front = shipped(mine.front, old[0], fresh.front);
        mine.side = shipped(mine.side, old[1], fresh.side);
        mine.rear = shipped(mine.rear, old[2], fresh.rear);
        mine.roof = shipped(mine.roof, old[3], fresh.roof);
        mine.floor = shipped(mine.floor, old[4], fresh.floor);
    }

    private static float shipped(float current, float oldValue, float fresh) {
        return current == oldValue ? fresh : current;
    }

    private static boolean samePlate(ArmorSet set, float[] want) {
        return set != null && set.front == want[0] && set.side == want[1] && set.rear == want[2]
                && set.roof == want[3] && set.floor == want[4];
    }

    private static void shipGunMount(Profile loaded, String id, float oldThickness,
                                     float freshThickness) {
        VehicleArmor mine = loaded.vehicles.get(id);
        if (mine == null) {
            return;
        }
        if (mine.turret_weak_spot != null && mine.turret_weak_spot.thickness == oldThickness) {
            mine.turret_weak_spot.thickness = freshThickness;
        }
        if (mine.overrides != null) {
            for (ArmorOverride override : mine.overrides.values()) {
                if (override != null && override.turret_weak_spot != null
                        && override.turret_weak_spot.thickness == oldThickness) {
                    override.turret_weak_spot.thickness = freshThickness;
                }
            }
        }
    }

    private static void shipHullWeak(Profile loaded, String id, float oldThickness,
                                     float freshThickness) {
        VehicleArmor mine = loaded.vehicles.get(id);
        if (mine == null) {
            return;
        }
        if (mine.hull_weak_spot != null && mine.hull_weak_spot.thickness == oldThickness) {
            mine.hull_weak_spot.thickness = freshThickness;
        }
        if (mine.overrides != null) {
            for (ArmorOverride override : mine.overrides.values()) {
                if (override != null && override.hull_weak_spot != null
                        && override.hull_weak_spot.thickness == oldThickness) {
                    override.hull_weak_spot.thickness = freshThickness;
                }
            }
        }
    }

    private static void shipSplit(Profile loaded, String id, float oldUpper, float oldLower,
                                  float freshUpper, float freshLower) {
        VehicleArmor mine = loaded.vehicles.get(id);
        if (mine == null) {
            return;
        }
        shipFrontSplit(mine.hull_front_split, oldUpper, oldLower, freshUpper, freshLower);
        if (mine.overrides != null) {
            for (ArmorOverride override : mine.overrides.values()) {
                if (override != null) {
                    shipFrontSplit(override.hull_front_split, oldUpper, oldLower,
                            freshUpper, freshLower);
                }
            }
        }
    }

    private static void shipOverrideSplit(Profile loaded, String id, float oldUpper, float oldLower,
                                          float freshUpper, float freshLower) {
        VehicleArmor mine = loaded.vehicles.get(id);
        if (mine == null || mine.overrides == null) {
            return;
        }
        for (ArmorOverride override : mine.overrides.values()) {
            if (override != null) {
                shipFrontSplit(override.hull_front_split, oldUpper, oldLower,
                        freshUpper, freshLower);
            }
        }
    }

    private static void shipFrontSplit(FrontSplit split, float oldUpper, float oldLower,
                                       float freshUpper, float freshLower) {
        if (split == null) {
            return;
        }
        if (split.upper != null && split.upper.thickness == oldUpper) {
            split.upper.thickness = freshUpper;
        }
        if (split.lower != null && split.lower.thickness == oldLower) {
            split.lower.thickness = freshLower;
        }
    }

    private static void shipFrontSplit(FrontSplitPlates split, float oldUpper, float oldLower,
                                       float freshUpper, float freshLower) {
        if (split == null) {
            return;
        }
        if (split.upper != null && split.upper.thickness == oldUpper) {
            split.upper.thickness = freshUpper;
        }
        if (split.lower != null && split.lower.thickness == oldLower) {
            split.lower.thickness = freshLower;
        }
    }

    private static void regradeAircraft(Profile loaded, Profile defaults, String id,
                                        float[] oldSkin, float oldSeat, float oldScreen) {
        VehicleArmor mine = loaded.vehicles.get(id);
        VehicleArmor fresh = defaults.vehicles.get(id);
        if (mine == null || fresh == null || mine.aircraft == null || fresh.aircraft == null) {
            return;
        }
        if (!samePlate(mine.aircraft.skin, oldSkin)
                || componentThickness(mine.aircraft, "seat armour") != oldSeat
                || componentThickness(mine.aircraft, "windscreen") != oldScreen) {
            return;
        }
        mine.aircraft.skin = fresh.aircraft.skin;
        mine.aircraft.frame_multiplier = fresh.aircraft.frame_multiplier;
        mine.aircraft.wing = fresh.aircraft.wing;
        mine.aircraft.components = fresh.aircraft.components;
        mine.hull = fresh.hull;
        mine.turret = fresh.turret;
        VehicleArmorMod.LOGGER.info(
                "[Vehicle Armor] upgrade: {} skin and crew plates regraded", id);
    }

    private static float componentThickness(AircraftArmor aircraft, String name) {
        if (aircraft.components == null) {
            return -1.0F;
        }
        for (AircraftComponent component : aircraft.components) {
            if (component != null && name.equals(component.name)) {
                return component.armor;
            }
        }
        return -1.0F;
    }

    private static void regradeAmmo(Profile loaded, Profile defaults, String id, float oldPenetration) {
        regradeAmmo(loaded, defaults, id, oldPenetration, oldPenetration);
    }

    /**
     * The same, with the old roll ceiling named separately: the Panther's dart shipped 192mm of
     * penetration and a 200mm ceiling, so a migration keyed on the penetration alone leaves the
     * ceiling where it was.
     */
    private static void regradeAmmo(Profile loaded, Profile defaults, String id, float oldPenetration,
                                    float oldBonusMax) {
        Ammo mine = loaded.ammo_penetration == null ? null : loaded.ammo_penetration.get(id);
        Ammo fresh = defaults.ammo_penetration == null ? null : defaults.ammo_penetration.get(id);
        if (mine == null || fresh == null) {
            return;
        }
        boolean moved = false;
        if (mine.penetration == oldPenetration) {
            mine.penetration = fresh.penetration;
            moved = true;
        }
        if (mine.penetration_bonus_max != null && mine.penetration_bonus_max == oldBonusMax) {
            mine.penetration_bonus_max = fresh.penetration_bonus_max;
            mine.penetration_bonus_max_at = fresh.penetration_bonus_max_at;
            moved = true;
        }
        if (moved) {
            VehicleArmorMod.LOGGER.info(
                    "[Vehicle Armor] upgrade: {} regraded to {} mm", id, fresh.penetration);
        }
    }

    private static void enginesAndSmoke(Profile loaded, Profile defaults) {
        for (String id : new String[]{"armor_pioneer:vt5", "armor_pioneer:vt4", "armor_pioneer:k2",
                "armor_pioneer:type10", "armor_pioneer:tianma20", "armor_pioneer:ztz100"}) {
            if (loaded.vehicles == null || defaults.vehicles == null) {
                return;
            }
            VehicleArmor mine = loaded.vehicles.get(id);
            VehicleArmor theirs = defaults.vehicles.get(id);
            if (mine == null || theirs == null) {
                continue;
            }
            if (mine.engine_horsepower == 0.0F && theirs.engine_horsepower > 0.0F) {
                mine.engine_horsepower = theirs.engine_horsepower;
                VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: {} gained its {} hp engine",
                        id, theirs.engine_horsepower);
            }
            if (mine.horsepower_per_ton == 0.0F && theirs.horsepower_per_ton > 0.0F) {
                mine.horsepower_per_ton = theirs.horsepower_per_ton;
                VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: {} gained its {} hp/t",
                        id, theirs.horsepower_per_ton);
            }
            if (mine.turret_part_ids == null || mine.turret_part_ids.length == 0) {
                mine.turret_part_ids = theirs.turret_part_ids.clone();
                VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: {} given the turret part list "
                        + "that includes its smoke launcher", id);
            } else if (!java.util.Arrays.asList(mine.turret_part_ids)
                    .contains("turret_smoke_grenade")) {
                String[] extended = java.util.Arrays.copyOf(mine.turret_part_ids,
                        mine.turret_part_ids.length + 1);
                extended[extended.length - 1] = "turret_smoke_grenade";
                mine.turret_part_ids = extended;
                VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: {}'s smoke launcher filed "
                        + "under the turret", id);
            }
        }
    }

    /**
     * v74 -> v75 -> v76: the VT-4's four FY-4E reactive panels.
     *
     * <p>The user's display model marks `hull_era`, `hull_era2`, `turret_era` and `turret_era2`
     * as separate parts, and the structure model carries a hitbox for each. Both hull and turret
     * are FY-4E, and the user's figure for the panel is 200mm, so all four are the same
     * {@code Plate(200, 1).reactiveHp(200)} on the vehicle block. A v74 file knows nothing about
     * them; a v75 file has the interim 450/150 entries in `heat`/`he`, which are removed where
     * they still hold those shipped figures.
     *
     * <p>The chemical table shipped before those tiles existed, so its 1050/300 figures were the
     * ERA-inclusive totals. They are now the armour behind the panels: turret front 1050/900 ->
     * 850, turret side 300/150 -> 100, hull upper front 1050/600 -> 850. With the 200mm panel the
     * totals are still 1050 (front and glacis) and 300 (turret side), and they drop to the behind
     * figure once the panel is spent. On the kinetic side there was no ERA figure before: 200mm
     * now comes off a dart first, then the unchanged 600/180 table.
     *
     * <p>Only the exact shipped figures move. A player who has typed a different number has said
     * something, and the panel then simply sits in front of their figure - the same rule the
     * glacis boundaries and the reactive HP are held to. Both the `heat` and `he` tables move
     * together; the `he` table is a copy of the chemical one and leaving it behind would have
     * high explosive measured against a different plate than the shaped charge beside it.
     *
     * <p>The base boxes arrive through {@link #adoptMissingBoxes}; this method moves the figures,
     * removes the v75 override panels where they still hold what v75 shipped, and appends the two
     * turret ids. It runs after {@link #enginesAndSmoke} because that is what replaces a missing
     * list whole, and appending to a list that does not exist yet would leave the panels rated as
     * hull.
     */
    private static void vt4EraPanels(Profile loaded, Profile defaults) {
        if (loaded.vehicles == null || defaults.vehicles == null) {
            return;
        }
        VehicleArmor mine = loaded.vehicles.get("armor_pioneer:vt4");
        VehicleArmor theirs = defaults.vehicles.get("armor_pioneer:vt4");
        if (mine == null || theirs == null) {
            return;
        }
        int moved = 0;
        for (String profile : new String[]{"heat", "he"}) {
            ArmorOverride my = mine.overrides == null ? null : mine.overrides.get(profile);
            ArmorOverride fresh = theirs.overrides == null ? null : theirs.overrides.get(profile);
            if (my == null || fresh == null) {
                continue;
            }
            if (my.turret != null) {
                // 1050 is the v74 shipped figure; 900 is the v75 interim one. Both are ours,
                // so both move; anything else is the player's and is left alone.
                if (my.turret.front == 1050.0F || my.turret.front == 900.0F) {
                    my.turret.front = fresh.turret == null ? 850.0F : fresh.turret.front;
                    moved++;
                }
                if (my.turret.side == 300.0F || my.turret.side == 150.0F) {
                    my.turret.side = fresh.turret == null ? 100.0F : fresh.turret.side;
                    moved++;
                }
            }
            if (my.hull != null && (my.hull.front == 1050.0F || my.hull.front == 600.0F)) {
                my.hull.front = fresh.hull == null ? 850.0F : fresh.hull.front;
                moved++;
            }
            if (my.hull_front_split != null && my.hull_front_split.upper != null
                    && (my.hull_front_split.upper.thickness == 1050.0F
                            || my.hull_front_split.upper.thickness == 600.0F)) {
                my.hull_front_split.upper.thickness = fresh.hull_front_split == null
                        ? 850.0F : fresh.hull_front_split.upper.thickness;
                moved++;
            }
        }
        // The v75 interim put 450/150 panels in heat and he. They are not the FY-4E figure the
        // user asked for; remove them where they still hold what v75 shipped, so the base 200
        // applies. A panel the player has valued themselves stays - an override is their
        // statement about that round, and silently deleting it would be the worse failure.
        int removed = 0;
        for (String profile : new String[]{"heat", "he"}) {
            ArmorOverride my = mine.overrides == null ? null : mine.overrides.get(profile);
            if (my == null) {
                continue;
            }
            removed += dropShippedPanel(my.part_armor, "hull_era", 450.0F, 1.0F, 450.0F);
            removed += dropShippedPanel(my.part_armor, "hull_era2", 450.0F, 1.0F, 450.0F);
            removed += dropShippedPanel(my.part_armor, "turret_era", 150.0F, 4.0F, 150.0F);
            removed += dropShippedPanel(my.part_armor, "turret_era2", 150.0F, 4.0F, 150.0F);
        }
        int ids = 0;
        for (String id : new String[]{"turret_era", "turret_era2"}) {
            if (mine.turret_part_ids == null) {
                break;
            }
            if (!java.util.Arrays.asList(mine.turret_part_ids).contains(id)) {
                String[] extended = java.util.Arrays.copyOf(mine.turret_part_ids,
                        mine.turret_part_ids.length + 1);
                extended[extended.length - 1] = id;
                mine.turret_part_ids = extended;
                ids++;
            }
        }
        if (moved > 0 || removed > 0 || ids > 0) {
            VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: VT-4 FY-4E panels - {} face(s) "
                    + "moved behind the 200mm panels, {} interim override panel(s) removed, "
                    + "{} turret part id(s) appended", moved, removed, ids);
        }
    }

    /**
     * v79 -> v80: the Tianma-20's three reactive panels.
     *
     * <p>The user's display model marks two hull-side skirt runs (`hull_era` / `hull_era2` in the
     * model, `hull_era_l` / `hull_era_r` as parts) and one turret-roof slab (`turret_era` /
     * `turret_era_roof`), and the structure model carries a hitbox for each. The two hull panels
     * are one run each, not one part per tile, the same way the VT-4's four panels are one box
     * each and VT-5's FY-4E box is one part.
     *
     * <p>The three boxes themselves arrive through {@link #adoptMissingBoxes}, which copies new
     * defaults into an existing file whole. This method only appends the roof panel to
     * `turret_part_ids`: a file that already carries the list keeps it, so the new id would
     * otherwise never be added and a hit on the roof panel would be measured as hull armour.
     * Appending is safe for the seat order - `turret_part_ids` is a role list, not the seat list.
     */
    private static void tianmaEraPanels(Profile loaded) {
        if (loaded.vehicles == null) {
            return;
        }
        VehicleArmor mine = loaded.vehicles.get("armor_pioneer:tianma20");
        if (mine == null || mine.turret_part_ids == null) {
            return;
        }
        if (java.util.Arrays.asList(mine.turret_part_ids).contains("turret_era_roof")) {
            return;
        }
        String[] extended = java.util.Arrays.copyOf(mine.turret_part_ids,
                mine.turret_part_ids.length + 1);
        extended[extended.length - 1] = "turret_era_roof";
        mine.turret_part_ids = extended;
        VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: the Tianma-20's turret-roof "
                + "reactive panel filed under the turret");
    }

    /**
     * v81 -&gt; v82: the Leopard 2A4's flank kit became parts.
     *
     * <p>v81 carried it as one layer on the vehicle block - a three-hit pool shared by both
     * flanks. The pack now carries the kit as two boxes with a part unit each, the T-90M's
     * Relikt shape, so the old layer would apply on top of both blocks: three hits of a mechanic
     * the tank no longer has. It is removed only where it is still exactly what shipped. A player
     * who has written their own pool has said what it is worth, and it is kept, with a line
     * saying why.
     *
     * <p>Neither replacement box is written here. They are new {@code part_armor} keys, and
     * {@link #adoptMissingBoxes} has copied them in by the time this runs. This step is a
     * removal, and removals run last.
     */
    private static void leopardSideAddonToParts(Profile loaded) {
        if (loaded.vehicles == null) {
            return;
        }
        VehicleArmor mine = loaded.vehicles.get("armor_pioneer:leopard2a4");
        if (mine == null || mine.hull_side_addon == null) {
            return;
        }
        Plate plate = mine.hull_side_addon;
        boolean shipped = plate.thickness == 90.0F && plate.multiplier == 5.0F
                && plate.addon_hp == 150.0F && plate.penetration_loss == 0.0F
                && plate.era_hp == 0.0F
                && plate.addon_fractions != null && plate.addon_fractions.length == 3
                && plate.addon_fractions[0] == 0.6F && plate.addon_fractions[1] == 0.4F
                && plate.addon_fractions[2] == 0.2F;
        if (!shipped) {
            VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: the Leopard 2A4's hand-written "
                    + "hull-side add-on layer has been kept. The flank blocks are parts now and "
                    + "spend a hit each, so the layer still applies in front of them");
            return;
        }
        mine.hull_side_addon = null;
        VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: the Leopard 2A4's hull-side add-on "
                + "layer has become two parts - one box a flank, 100mm off a dart and 300mm off a "
                + "jet, spent one hit each");
    }

    /**
     * v76 -> v77: the VT-4's two hull-front bands.
     *
     * <p>The split itself already exists in every file, so this is not a map fill: it is two new
     * optional plates on the split. An absent plate is how a vehicle says "this plate is one
     * figure", which is what every vehicle except the VT-4 still says - so the shipped bands are
     * copied in only where the file has none, and a band a player has given their own figures to
     * is left alone. The edge travels with the plate: a file carrying a plate but no edge would
     * hold a band at height zero, which is the quietest possible way to get this wrong.
     */
    private static void vt4HullBands(Profile loaded, Profile defaults) {
        if (loaded.vehicles == null || defaults.vehicles == null) {
            return;
        }
        VehicleArmor mine = loaded.vehicles.get("armor_pioneer:vt4");
        VehicleArmor theirs = defaults.vehicles.get("armor_pioneer:vt4");
        if (mine == null || theirs == null || mine.hull_front_split == null
                || theirs.hull_front_split == null) {
            return;
        }
        FrontSplit my = mine.hull_front_split;
        FrontSplit fresh = theirs.hull_front_split;
        int added = 0;
        if (my.upper_band == null && fresh.upper_band != null) {
            my.upper_band = fresh.upper_band;
            my.upper_band_split = fresh.upper_band_split;
            added++;
        }
        if (my.lower_band == null && fresh.lower_band != null) {
            my.lower_band = fresh.lower_band;
            my.lower_band_split = fresh.lower_band_split;
            added++;
        }
        if (added > 0) {
            VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: VT-4 hull front gained {} band(s)"
                    + " - the glacis' top strip and/or the main belt's extension", added);
        }
    }

    /**
     * Removes a panel from an override only when it is exactly what v75 shipped.
     *
     * <p>The v75 interim put 450 on the hull panels and 150 on the turret panels in the chemical
     * overrides; v76's FY-4E figure is 200 on the vehicle block. Leaving the interim entries would
     * make them override the 200 for the rounds they are filed under, so the heat table would keep
     * the old number while the kinetic table used the new one. A player who has typed their own
     * values has said something about that round and keeps them.
     *
     * @return 1 when the entry was removed, 0 otherwise
     */
    private static int dropShippedPanel(Map<String, Plate> map, String id,
                                        float thickness, float multiplier, float hp) {
        Plate plate = map == null ? null : map.get(id);
        if (plate == null || plate.thickness != thickness || plate.multiplier != multiplier
                || plate.era_hp != hp) {
            return 0;
        }
        map.remove(id);
        return 1;
    }

    /**
     * Gives a manually loaded vehicle its loader seat.
     *
     * <p>New in v68. {@code loader_seat_id} is a plain field, so a file written before it reads
     * null; only that null is filled, and a player who has pointed the role at another seat keeps
     * their choice. Nothing is added to {@code turret_part_ids}: the loader's seat is crew, not an
     * armour box, and {@link CrewRoles#inTurretCompartment} reads {@code loader_seat_id} directly
     * when it decides which compartment a turret hit reaches.
     */
    private static void adoptLoaderSeats(Profile loaded, Profile defaults) {
        if (loaded.vehicles == null || defaults.vehicles == null) {
            return;
        }
        for (Map.Entry<String, VehicleArmor> entry : defaults.vehicles.entrySet()) {
            VehicleArmor mine = loaded.vehicles.get(entry.getKey());
            VehicleArmor theirs = entry.getValue();
            if (mine == null || theirs == null || theirs.loader_seat_id == null) {
                continue;
            }
            if (mine.loader_seat_id == null) {
                mine.loader_seat_id = theirs.loader_seat_id;
                VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: {} gained a manual loader "
                        + "seat ({}) - its main gun reloads faster with somebody in it",
                        entry.getKey(), mine.loader_seat_id);
            }
        }
    }

    /**
     * Gives an unmanned-turret vehicle its hull-bound stations.
     *
     * <p>New in v71. {@code hull_seat_ids} is a plain list, so a file written before it reads
     * null; only that null is filled. A player who has written their own list keeps it, including
     * an empty one. The list is copied so later edits to the defaults cannot reach the loaded
     * profile.
     */
    private static void adoptHullSeats(Profile loaded, Profile defaults) {
        if (loaded.vehicles == null || defaults.vehicles == null) {
            return;
        }
        for (Map.Entry<String, VehicleArmor> entry : defaults.vehicles.entrySet()) {
            VehicleArmor mine = loaded.vehicles.get(entry.getKey());
            VehicleArmor theirs = entry.getValue();
            if (mine == null || theirs == null || theirs.hull_seat_ids == null) {
                continue;
            }
            if (mine.hull_seat_ids == null) {
                mine.hull_seat_ids = new ArrayList<>(theirs.hull_seat_ids);
                VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: {} rides with the hull in "
                        + "these seats: {}", entry.getKey(), mine.hull_seat_ids);
            }
        }
    }

    /**
     * Fills in a vehicle's hybrid drive where the file has no entry for it.
     *
     * <p>A plain map loop, like the ammunition and suspension ones: an entry a player has is
     * theirs, and one the file has never seen is copied from the defaults. The failure this
     * prevents is silent rather than loud - a vehicle with no {@code hybrids} key simply answers
     * "no hybrid" on both sides and behaves exactly like the diesel tank it already was, so the
     * feature would be absent with nothing in any log to say why.
     */
    private static void adoptHybrids(Profile loaded, Profile defaults) {
        if (loaded.hybrids == null) {
            loaded.hybrids = new LinkedHashMap<>();
        }
        for (Map.Entry<String, Hybrid.Settings> entry : defaults.hybrids.entrySet()) {
            if (!loaded.hybrids.containsKey(entry.getKey())) {
                loaded.hybrids.put(entry.getKey(), entry.getValue());
                VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: {} gained its hybrid drive",
                        entry.getKey());
            }
        }
    }

    /**
     * Fills in a vehicle's hatch periscope where the file has no entry for it.
     *
     * <p>A plain map loop, like the ammunition, suspension and hybrid ones: an entry a player has
     * is theirs, and one the file has never seen is copied from the defaults. The failure this
     * prevents is silent rather than loud - a vehicle with no {@code periscopes} key simply answers
     * "no periscope" on the client and keeps the base views, so the key would do nothing and there
     * would be nothing in any log to say why.
     */
    private static void adoptPeriscopes(Profile loaded, Profile defaults) {
        if (loaded.periscopes == null) {
            loaded.periscopes = new LinkedHashMap<>();
        }
        for (Map.Entry<String, Periscope.Settings> entry : defaults.periscopes.entrySet()) {
            if (!loaded.periscopes.containsKey(entry.getKey())) {
                loaded.periscopes.put(entry.getKey(), entry.getValue());
                VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: {} gained a hatch periscope",
                        entry.getKey());
            }
        }
    }

    /**
     * Moves the VT-5's periscope from the old right hatch to the left one.
     *
     * <p>The v72 file shipped the commander on the right, at (1.3475, 0.2036, 0.7807) from the
     * remote weapon station's pivot. The corrected display model puts the commander on the left;
     * the new point is (-0.4745, 0.3036, 1.0307). Only the exact shipped entry moves - the same
     * all-or-nothing rule the VT-5 dart load list follows - so a player who measured their own
     * point keeps it, and a file that has already moved (or never had the old values) is untouched.
     */
    private static void moveVt5Periscope(Profile loaded, Profile defaults) {
        if (loaded.periscopes == null || defaults.periscopes == null) {
            return;
        }
        Periscope.Settings mine = loaded.periscopes.get("armor_pioneer:vt5");
        Periscope.Settings fresh = defaults.periscopes.get("armor_pioneer:vt5");
        if (mine == null || fresh == null) {
            return;
        }
        if ("commander_machine_gun".equals(mine.seat_part_id)
                && "turret".equals(mine.yaw_part_id)
                && mine.offset_x == 1.3475F
                && mine.offset_y == 0.2036F
                && mine.offset_z == 0.7807F) {
            mine.offset_x = fresh.offset_x;
            mine.offset_y = fresh.offset_y;
            mine.offset_z = fresh.offset_z;
            VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: VT-5 periscope moved to the left "
                    + "hatch ({}, {}, {})", mine.offset_x, mine.offset_y, mine.offset_z);
        }
    }

    /**
     * Moves every per-vehicle {@code suspension} block into {@link Profile#suspensions}.
     *
     * <p>The only migration here that relocates a block rather than adding one or moving a value,
     * and the difference matters: the other shapes all leave the old key in place and harmless, so a
     * file that misses one still works. This one stops being read the moment the field it sits on
     * does. A file written at v38 through v43 holds the Dragoon's suspension inside
     * {@code stryker_m1296}, that block is still perfectly valid JSON, Gson still fills the
     * deprecated field from it, and nothing would look wrong - the Dragoon would simply be the only
     * vehicle in the game whose keys did anything, and the six new ones would be missing entirely.
     *
     * <p>The player's block wins over the default, and it is taken whatever it says: someone who has
     * tuned the Stryker's range to a tenth of a block meant it. Only a vehicle the file has no block
     * for at all is given the default.
     */
    private static void adoptSuspensions(Profile loaded, Profile defaults) {
        if (loaded.suspensions == null) {
            loaded.suspensions = new LinkedHashMap<>();
        }
        if (loaded.vehicles != null) {
            for (Map.Entry<String, VehicleArmor> entry : loaded.vehicles.entrySet()) {
                Suspension.Settings moved = entry.getValue().suspension;
                if (moved == null) {
                    continue;
                }
                entry.getValue().suspension = null;
                loaded.suspensions.put(entry.getKey(), moved);
                VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: {}'s suspension moved out of "
                        + "its armour block - a vehicle can have one without this addon having an "
                        + "opinion about its armour", entry.getKey());
            }
        }
        for (Map.Entry<String, Suspension.Settings> entry : defaults.suspensions.entrySet()) {
            if (!loaded.suspensions.containsKey(entry.getKey())) {
                loaded.suspensions.put(entry.getKey(), entry.getValue());
                VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: {} gained an adjustable "
                        + "suspension", entry.getKey());
            }
        }
    }

    /**
     * @return the suspension the vehicle's crew can work, or {@code null} when it has none - which
     *         is the answer for every vehicle but the handful the defaults name.
     *
     * <p>Resolved against the vehicle's own id, and its display id by the caller, in the same
     * two-step way its armour is: a re-skinned variant carries a display id of its own and can be
     * configured separately, so a suspension hung on one key must not silently apply to the other.
     */
    public static Suspension.Settings suspensionFor(ResourceLocation vehicleId) {
        if (profile == null || profile.suspensions == null || vehicleId == null) {
            return null;
        }
        return profile.suspensions.get(vehicleId.toString());
    }

    /**
     * @return the hybrid drive this vehicle carries, or {@code null} when it has none - which is
     *         every vehicle but the ZTZ-100 in the shipped defaults.
     *
     * <p>A map of its own, and for the same reason the suspension is: a drivetrain is equipment the
     * crew works, not a statement about what a round does to the hull. A vehicle can be a hybrid
     * without this addon having any opinion about its armour, and the lookup follows the same
     * vehicle-id-then-display-id order so a re-skinned variant can carry its own copy.
     */
    public static Hybrid.Settings hybridFor(ResourceLocation vehicleId) {
        if (profile == null || profile.hybrids == null || vehicleId == null) {
            return null;
        }
        return profile.hybrids.get(vehicleId.toString());
    }

    /**
     * @return the hatch periscope this vehicle carries, or {@code null} when it has none - which
     *         is every vehicle but the VT-5 in the shipped defaults.
     *
     * <p>Resolved against the vehicle's own id, and its display id by the caller, like the armour
     * and the suspension. The periscope is a viewpoint, not protection, so it is a map of its own
     * and a re-skinned variant can carry a different one without touching its armour block.
     */
    public static Periscope.Settings periscopeFor(ResourceLocation vehicleId) {
        if (profile == null || profile.periscopes == null || vehicleId == null) {
            return null;
        }
        return profile.periscopes.get(vehicleId.toString());
    }

    /**
     * Moves the two {@code road_wheels.top} figures the 0.6.1 pack invalidated.
     *
     * <p>Nothing about the vehicles changed - the eight wheel boxes on the BTR-3 and the M1296 are
     * the same cubes at the same heights they have always been - but {@code road_wheels.top} is the
     * wheel tops as a fraction of the **hull box's** height, and the pack cut that box's floor up
     * from the ground to y +0.72 blocks so the wheels stand below the hull instead of inside it.
     * Measured against the new box the same wheels come out at -0.283 and -0.318 where they used to
     * be +0.040 and +0.017.
     *
     * <p>Only the figure that shipped is moved, which is the rule every value migration here holds
     * to. A player who has measured their own wheels keeps their number - and because the two are
     * checked one at a time, a file edited for one vehicle is still migrated for the other.
     *
     * <p>The failure this prevents has no symptom in the config: an old figure stays a plausible
     * fraction between 0 and 1, the tool prints it without complaint, and the strip it describes is
     * simply somewhere else on the vehicle than the wheels are.
     */
    private static void roadWheelRegrade(Profile loaded) {
        if (loaded.vehicles == null) {
            return;
        }
        regradeRoadWheels(loaded, "vvp_ywzj:btr_3", 0.040F, -0.283F);
        regradeRoadWheels(loaded, "vvp_ywzj:stryker_m1296", 0.017F, -0.318F);
    }

    private static void regradeRoadWheels(Profile loaded, String id, float shipped, float corrected) {
        VehicleArmor mine = loaded.vehicles.get(id);
        if (mine == null || mine.road_wheels == null || mine.road_wheels.top != shipped) {
            return;
        }
        mine.road_wheels.top = corrected;
        VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: {}'s road-wheel strip {} -> {} "
                        + "(the 0.6.1 pack cut the hull box's floor up; the wheels did not move)",
                id, shipped, corrected);
    }

    /**
     * Adds the part-armour boxes a file has never had.
     *
     * <p>Every other migration here moves a value, or gives a kind to a box that is already in the
     * file. This one adds boxes, which is the third shape a new {@code part_armor} key can need and
     * the only one nothing else covers: {@link #inherit} hands over a whole block and only when the
     * player has none, so it never fires for a map that is present but lacking a key - and a
     * {@code part_armor} map is always present, empty at worst.
     *
     * <p>The failure it prevents has no symptom at all. A T-90M in an older file still has the
     * turret's blocks, still has the slat screen along the flank, and still prints a whole table of
     * plausible numbers - the flank is simply armor the model never got, and the one place that says
     * so is the model, which the config reader never looks at.
     *
     * <p>Only keys that are absent are copied. A box the player has is theirs, values and all, the
     * same rule {@link #adoptReactiveHp} holds to - including the case where they have deleted a
     * box on purpose, which this will put back on the next launch. That is the deliberate cost of
     * not being able to tell "never had it" from "removed it"; a release note, not a mechanism, is
     * what says a box was added, and the alternative is a config that silently differs per file.
     */
    private static void adoptMissingBoxes(Profile loaded, Profile defaults) {
        if (loaded.vehicles == null || defaults.vehicles == null) {
            return;
        }
        int adopted = 0;
        for (Map.Entry<String, VehicleArmor> entry : defaults.vehicles.entrySet()) {
            VehicleArmor mine = loaded.vehicles.get(entry.getKey());
            if (mine == null) {
                continue;
            }
            adopted += takeMissingBoxes(mine.part_armor, entry.getValue().part_armor);
            for (Map.Entry<String, ArmorOverride> profile : entry.getValue().overrides.entrySet()) {
                ArmorOverride myOverride = mine.overrides == null ? null
                        : mine.overrides.get(profile.getKey());
                if (myOverride == null) {
                    continue;
                }
                adopted += takeMissingBoxes(myOverride.part_armor, profile.getValue().part_armor);
            }
        }
        if (adopted > 0) {
            VehicleArmorMod.LOGGER.info(
                    "[Vehicle Armor] upgrade: {} part-armour box(es) this release added", adopted);
        }
    }

    /** @return how many boxes the file was missing, copies included. */
    private static int takeMissingBoxes(Map<String, Plate> mine, Map<String, Plate> defaults) {
        if (mine == null || defaults == null) {
            return 0;
        }
        int adopted = 0;
        for (Map.Entry<String, Plate> box : defaults.entrySet()) {
            if (!mine.containsKey(box.getKey())) {
                mine.put(box.getKey(), box.getValue());
                adopted++;
            }
        }
        return adopted;
    }

    /**
     * The glacis boundary corrections, for every tank whose has moved, each applied only where the
     * file still holds a figure that shipped.
     *
     * <p>One method for two tanks and three releases, because the rule is the same one every time and
     * it is the rule that matters: a file that has moved this figure has said something, and this has
     * no business overruling it. What it is here for is the file that has moved none of them, where
     * nothing looks wrong - a glacis boundary reads as a perfectly ordinary number whichever way it
     * points.
     *
     * <ul>
     *   <li><b>The T-90Ms</b> shipped it at v26 with its sign the wrong way round - {@code +0.3} where
     *       the intended "upper two thirds" is {@code -0.33} - and then at {@code -0.33} until it
     *       moved to half. Both old figures are in the list, since a file may be carrying either.</li>
     *   <li><b>The T-72B3M</b> shares that hull shape and shipped the same {@code -0.33}; it follows
     *       the T-90M to half at v37, having been left behind by the release that moved the T-90M's.
     *       </li>
     * </ul>
     */
    private static void glacisSplit(Profile loaded, Profile defaults) {
        if (loaded.vehicles == null) {
            return;
        }
        for (String id : new String[]{"vvp_ywzj:t90_m", "vvp_ywzj:t90_pror_2022",
                "vvp_ywzj:t90_pror_2024"}) {
            moveSplit(loaded, defaults, id, 0.3F, -0.33F);
        }
        moveSplit(loaded, defaults, "vvp_ywzj:t72_b3m", -0.33F);
    }

    /** One tank's boundary, moved when the file still holds one of {@code shipped}. */
    private static void moveSplit(Profile loaded, Profile defaults, String id, float... shipped) {
        VehicleArmor mine = loaded.vehicles.get(id);
        VehicleArmor theirs = defaults.vehicles.get(id);
        if (mine == null || theirs == null
                || mine.hull_front_split == null || theirs.hull_front_split == null) {
            return;
        }
        float was = mine.hull_front_split.split;
        boolean untouched = false;
        for (float figure : shipped) {
            untouched |= was == figure;
        }
        if (!untouched) {
            return;
        }
        mine.hull_front_split.split = theirs.hull_front_split.split;
        VehicleArmorMod.LOGGER.info("[Vehicle Armor] upgrade: {} glacis split {} -> {}"
                        + " (glacis {}% -> {}% of the front face)", id, was,
                mine.hull_front_split.split, Math.round((1.0F - was) * 50.0F),
                Math.round((1.0F - mine.hull_front_split.split) * 50.0F));
    }

    /**
     * The T-90M's plate corrections, each applied only where the file still holds what shipped.
     *
     * <p>Nothing about any of these looks wrong in the file it lands in - every one of them reads as
     * a perfectly ordinary armour value - which is the whole reason they need moving in the first
     * place rather than being left to the release note.
     *
     * <ul>
     *   <li><b>The glacis plate</b> was 520mm and is now 700. The hull front beside it is kept equal
     *       to it: dead while the split is enabled, but it is where the tank falls back to when the
     *       split is off, and leaving it behind would make that fallback a third weaker than the tank
     *       in front of it.</li>
     *   <li><b>The turret's reactive blocks</b> were rated 700 kinetic and 1100 chemical, as though
     *       what sat behind them were the turret's 650mm front. They sit on its flanks and across its
     *       rear, over 180 and 90, and are now the same figures the hull's blocks carry.</li>
     * </ul>
     */
    private static void t90mRegrade(Profile loaded, Profile defaults) {
        if (loaded.vehicles == null) {
            return;
        }
        int moved = 0;
        for (String id : new String[]{"vvp_ywzj:t90_m", "vvp_ywzj:t90_pror_2022",
                "vvp_ywzj:t90_pror_2024"}) {
            VehicleArmor mine = loaded.vehicles.get(id);
            VehicleArmor theirs = defaults.vehicles.get(id);
            if (mine == null || theirs == null) {
                continue;
            }
            // The glacis plate itself. The kinetic table is the only one that moves - the chemical
            // table's glacis has been 700 all along, which is where the new figure comes from.
            moved += regrade(mine.hull_front_split == null ? null : mine.hull_front_split.upper,
                    theirs.hull_front_split == null ? null : theirs.hull_front_split.upper,
                    520.0F, 10.0F);
            if (mine.hull != null && theirs.hull != null && mine.hull.front == 520.0F) {
                mine.hull.front = theirs.hull.front;
                moved++;
            }
            // The turret's blocks, which are the pair that was calibrated against the wrong face.
            // Every profile the file has, not just the chemical one: the figures live in each table
            // separately, and a block moved in one of them and left in another is the failure this
            // whole family of migrations exists for - the HE table would still stop a jet with 1100
            // while the shaped charge beside it was rated at 600.
            for (String block : new String[]{"era_3", "era_4", "era_5"}) {
                moved += regrade(mine.part_armor, theirs.part_armor, block, 700.0F, 12.0F);
            }
            if (mine.overrides != null && theirs.overrides != null) {
                for (Map.Entry<String, ArmorOverride> one : mine.overrides.entrySet()) {
                    ArmorOverride myProfile = one.getValue();
                    ArmorOverride theirProfile = theirs.overrides.get(one.getKey());
                    if (myProfile == null || theirProfile == null) {
                        continue;
                    }
                    for (String block : new String[]{"era_3", "era_4", "era_5"}) {
                        moved += regrade(myProfile.part_armor, theirProfile.part_armor, block,
                                1100.0F, 10.0F);
                    }
                }
            }
        }
        if (moved > 0) {
            VehicleArmorMod.LOGGER.info(
                    "[Vehicle Armor] upgrade: {} T-90M figure(s) regraded to this release", moved);
        }
    }

    /**
     * Gives a plate the current figures when it still carries the ones that shipped.
     *
     * <p>Both numbers are compared, thickness and multiplier together, because either one alone
     * would let a plate that the player has tuned pass as untouched: the blocks below differ in
     * both, and a player who moved only the multiplier has still moved something.
     *
     * @return 1 when the plate was carrying the shipped pair and has been moved, 0 for anything else
     */
    private static int regrade(Plate mine, Plate theirs,
                               float shippedThickness, float shippedMultiplier) {
        if (mine == null || theirs == null
                || mine.thickness != shippedThickness || mine.multiplier != shippedMultiplier) {
            return 0;
        }
        mine.thickness = theirs.thickness;
        mine.multiplier = theirs.multiplier;
        return 1;
    }

    /** The same, for one box of a per-part map. */
    private static int regrade(Map<String, Plate> mine, Map<String, Plate> theirs, String key,
                               float shippedThickness, float shippedMultiplier) {
        if (mine == null || theirs == null) {
            return 0;
        }
        return regrade(mine.get(key), theirs.get(key), shippedThickness, shippedMultiplier);
    }

    /**
     * @return whether a vehicle's frontal plates are all still the other's, plate for plate.
     *
     * <p>Three plates in two tables, six numbers, all of them compared: a file where the player has
     * moved any one of them is not the shipped copy any more, and the whole of it is left alone
     * rather than half-corrected into a tank that never existed.
     */
    private static boolean sharesFrontalArmour(VehicleArmor mine, VehicleArmor reference) {
        if (mine.turret == null || reference.turret == null || mine.hull_front_split == null
                || reference.hull_front_split == null) {
            return false;
        }
        if (mine.turret.front != reference.turret.front) {
            return false;
        }
        ArmorOverride myHeat = mine.overrides == null ? null : mine.overrides.get("heat");
        ArmorOverride theirHeat = reference.overrides == null ? null : reference.overrides.get("heat");
        return myHeat == null || theirHeat == null || (sameFrontalPlates(myHeat, theirHeat)
                && sameSplitThickness(mine.hull_front_split, reference.hull_front_split));
    }

    /** @return whether every override of one vehicle's front plates matches the other's. */
    private static boolean sameFrontalPlates(ArmorOverride mine, ArmorOverride theirs) {
        if (mine.turret == null || theirs.turret == null || mine.turret.front != theirs.turret.front) {
            return false;
        }
        if (mine.hull_front_split == null || theirs.hull_front_split == null) {
            return mine.hull_front_split == theirs.hull_front_split;
        }
        return sameSplitThickness(mine.hull_front_split, theirs.hull_front_split);
    }

    private static boolean sameSplitThickness(FrontSplit mine, FrontSplit theirs) {
        return mine.upper == null || theirs.upper == null
                ? mine.upper == theirs.upper
                : mine.upper.thickness == theirs.upper.thickness
                        && (mine.lower == null || theirs.lower == null
                                ? mine.lower == theirs.lower
                                : mine.lower.thickness == theirs.lower.thickness);
    }

    private static boolean sameSplitThickness(FrontSplitPlates mine, FrontSplitPlates theirs) {
        return mine.upper == null || theirs.upper == null
                ? mine.upper == theirs.upper
                : mine.upper.thickness == theirs.upper.thickness
                        && (mine.lower == null || theirs.lower == null
                                ? mine.lower == theirs.lower
                                : mine.lower.thickness == theirs.lower.thickness);
    }

    /**
     * Copies a vehicle's frontal armour from the defaults, overrides and all.
     *
     * <p>Every override is walked rather than only {@code heat}: the {@code he} table is a copy of
     * the chemical one, so moving one and not the other would leave the two kinds of round measured
     * against different turret fronts - a difference that shows up only as a high-explosive round
     * penetrating something the shaped charge beside it cannot.
     */
    private static void adoptFrontalArmour(VehicleArmor mine, VehicleArmor theirs) {
        if (mine.turret != null && theirs.turret != null) {
            mine.turret.front = theirs.turret.front;
        }
        if (mine.hull != null && theirs.hull != null) {
            mine.hull.front = theirs.hull.front;
        }
        if (mine.hull_front_split != null && theirs.hull_front_split != null) {
            mine.hull_front_split.upper.thickness = theirs.hull_front_split.upper.thickness;
            mine.hull_front_split.lower.thickness = theirs.hull_front_split.lower.thickness;
        }
        if (mine.overrides == null || theirs.overrides == null) {
            return;
        }
        for (Map.Entry<String, ArmorOverride> entry : theirs.overrides.entrySet()) {
            ArmorOverride myOverride = mine.overrides.get(entry.getKey());
            adoptFrontalPlate(myOverride, entry.getValue());
        }
    }

    /** One override's share of the same, at whatever depth that override happens to be written. */
    private static void adoptFrontalPlate(ArmorOverride mine, ArmorOverride theirs) {
        if (mine == null || theirs == null) {
            return;
        }
        if (mine.turret != null && theirs.turret != null) {
            mine.turret.front = theirs.turret.front;
        }
        if (mine.hull != null && theirs.hull != null) {
            mine.hull.front = theirs.hull.front;
        }
        if (mine.hull_front_split != null && theirs.hull_front_split != null) {
            mine.hull_front_split.upper.thickness = theirs.hull_front_split.upper.thickness;
            mine.hull_front_split.lower.thickness = theirs.hull_front_split.lower.thickness;
        }
    }

    /**
     * Turns the old `era` kind strings into `era_hp`, panel by panel.
     *
     * <p>The old key chose a rule, not a number: light let a tandem round through at two thirds,
     * heavy stopped it once. Neither said what the panel was worth against an ordinary round, and
     * v70 makes the number the whole rule. A file that still carries a kind is converted from the
     * panel's own {@code thickness} - the figure that used to be what a round had to beat at that
     * spot - and the string is cleared so the writer never emits it again.
     *
     * @return how many panels carried a kind
     */
    private static int convertReactiveKinds(Profile profile) {
        if (profile.vehicles == null) {
            return 0;
        }
        int converted = 0;
        for (VehicleArmor armor : profile.vehicles.values()) {
            converted += convertReactiveKind(armor.hull_side_era);
            converted += convertReactiveKind(armor.part_armor);
            if (armor.overrides == null) {
                continue;
            }
            for (ArmorOverride override : armor.overrides.values()) {
                converted += convertReactiveKind(override.hull_side_era);
                converted += convertReactiveKind(override.part_armor);
            }
        }
        return converted;
    }

    /** @return 1 when the plate carried an old kind, 0 otherwise. */
    private static int convertReactiveKind(Plate plate) {
        if (plate == null || plate.era == null) {
            return 0;
        }
        if (plate.era_hp <= 0.0F) {
            plate.era_hp = plate.thickness;
        }
        plate.era = null;
        return 1;
    }

    /** The same over a map of boxes, keyed by part id. */
    private static int convertReactiveKind(Map<String, Plate> boxes) {
        if (boxes == null) {
            return 0;
        }
        int converted = 0;
        for (Plate plate : boxes.values()) {
            converted += convertReactiveKind(plate);
        }
        return converted;
    }

    /**
     * Fills in a reactive panel's HP where an older file has none.
     *
     * <p>Every other migration here is about a value that came out wrong. This one is about a key
     * that simply was not there before, and the failure it prevents is the quietest in the file: a
     * panel with no HP is not a broken panel, it is ordinary armour - so a round passes through
     * reactive armour that was configured specifically to be its answer, and the numbers in the
     * table look exactly like numbers.
     *
     * <p>Taken from the defaults panel by panel, and only where the file says nothing. A player who
     * has given a panel an HP of their own has said something, and this has no business overruling
     * them - the same rule the glacis boundaries are held to.
     */
    private static void adoptReactiveHp(Profile loaded, Profile defaults) {
        if (loaded.vehicles == null) {
            return;
        }
        int adopted = 0;
        for (Map.Entry<String, VehicleArmor> entry : defaults.vehicles.entrySet()) {
            VehicleArmor mine = loaded.vehicles.get(entry.getKey());
            if (mine == null) {
                continue;
            }
            VehicleArmor theirs = entry.getValue();
            adopted += adoptHp(mine.hull_side_era, theirs.hull_side_era);
            adopted += adoptHp(mine.part_armor, theirs.part_armor);
            for (Map.Entry<String, ArmorOverride> profile : theirs.overrides.entrySet()) {
                ArmorOverride myOverride = mine.overrides == null ? null
                        : mine.overrides.get(profile.getKey());
                if (myOverride == null) {
                    continue;
                }
                adopted += adoptHp(myOverride.hull_side_era, profile.getValue().hull_side_era);
                adopted += adoptHp(myOverride.part_armor, profile.getValue().part_armor);
            }
        }
        if (adopted > 0) {
            VehicleArmorMod.LOGGER.info(
                    "[Vehicle Armor] upgrade: {} reactive panel(s) given their HP", adopted);
        }
    }

    /** @return 1 when a panel with no HP was given the default's, 0 otherwise. */
    private static int adoptHp(Plate mine, Plate defaults) {
        if (mine == null || defaults == null || mine.era_hp > 0.0F || defaults.era_hp <= 0.0F) {
            return 0;
        }
        mine.era_hp = defaults.era_hp;
        return 1;
    }

    /** The same over a map of boxes, keyed by part id. */
    private static int adoptHp(Map<String, Plate> mine, Map<String, Plate> defaults) {
        if (mine == null || defaults == null) {
            return 0;
        }
        int adopted = 0;
        for (Map.Entry<String, Plate> box : defaults.entrySet()) {
            adopted += adoptHp(mine.get(box.getKey()), box.getValue());
        }
        return adopted;
    }

    /**
     * Hands a vehicle in the player's file one of the default blocks it does not have.
     *
     * <p>Nothing happens when the player already has the block: theirs, however they set it. And
     * nothing happens when the default is absent either - a null default is how the defaults say
     * "this vehicle has no such zone", which is the BTR-3's missing gun mount and every tracked
     * vehicle's missing wheels. So a null default must never be copied in as a block of nulls.
     */
    private static <T> void inherit(String vehicle, String what, VehicleArmor current,
                                    VehicleArmor defaults,
                                    java.util.function.Function<VehicleArmor, T> get,
                                    java.util.function.BiConsumer<VehicleArmor, T> set) {
        T wanted = get.apply(defaults);
        if (get.apply(current) == null && wanted != null) {
            set.accept(current, wanted);
            VehicleArmorMod.LOGGER.info("[Vehicle Armor] added {} defaults for {}", what, vehicle);
        }
    }

    private static Profile defaultProfile() {
        Profile p = new Profile();
        p.config_version = CONFIG_VERSION;
        p.destroyed_parts_cannot_fire = Boolean.TRUE;
        p.scanner_shows_armor = Boolean.TRUE;
        // VVP T-64: turret front 480 / side 150 / rear 90, hull front 440 / side 100 / rear 90.
        // Roof and floor were not specified by the design, so they mirror the rear value.
        VehicleArmor t64 = new VehicleArmor();
        // The gun mount is a hole in the turret front armour for the barrel to pass through,
        // so it is far weaker than the 480mm around it. The barrel leaves the T-64 turret
        // through the middle of its front plate, hence a centred circle.
        t64.turret_weak_spot = new WeakSpot();
        // The track ends are the outer strips of the hull's front and rear faces, below the
        // hull box's vertical centre. inner_edge is measured off the model: the track's
        // centre line sits at |X| = 1.69 of the hull's 2.20 block half-width, so its inner
        // edge lands near 0.64 once allowance is made for track width.
        t64.track_ends = new TrackEnds();
        // The hull front is split into a glacis and a lower plate. The glacis is thick but
        // a round that barely squeezes through it keeps most of its energy, hence the high
        // multiplier; the thin lower plate is easy to pierce but spalls much less.
        t64.hull_front_split = new FrontSplit();
        t64.hull_front_split.upper = new Plate(440.0F, 50.0F);
        t64.hull_front_split.lower = new Plate(120.0F, 5.0F);

        // What a shaped-charge jet finds easy and hard, rather than what stops a kinetic dart.
        ArmorOverride t64Chemical = new ArmorOverride();
        t64Chemical.turret = new ArmorSet(880.0F, 300.0F, 200.0F, 200.0F, 200.0F);
        t64Chemical.hull = new ArmorSet(840.0F, 200.0F, 200.0F, 200.0F, 200.0F);
        t64Chemical.damage_multiplier = new DamageMultiplier(45.0F, 4.0F, 4.0F, 4.0F, 4.0F);
        t64Chemical.turret_weak_spot = new Plate(300.0F, 4.0F);
        t64Chemical.hull_front_split = new FrontSplitPlates();
        t64Chemical.hull_front_split.upper = new Plate(840.0F, 45.0F);
        t64Chemical.hull_front_split.lower = new Plate(120.0F, 4.0F);
        t64Chemical.track_ends = new Plate(200.0F, 1.5F);
        // Kontakt-1 class: a 500 HP panel. A hit that sets it off loses 500mm of penetration and the
        // panel is spent; what is left meets the hull's 200mm flank. A hit weaker than the panel is
        // stopped by it and spends it just the same.
        t64Chemical.hull_side_era = new Plate(500.0F, 40.0F).reactiveHp(500.0F);
        t64.overrides.put("heat", t64Chemical);
        // A high-explosive shell carries only 60mm of penetration, so the story it tells is the
        // opposite of the HEAT round's: it cannot touch the thick plates, but the thin roofs and
        // the hull floor let it straight in, and what happens then is a full charge going off
        // inside. Everything else about the two rounds is identical. The turret floor keeps its
        // chemical value - only the three plates named for it were thinned.
        t64.overrides.put("he", thinRoofs(t64Chemical, 60.0F, 200.0F, 60.0F, 60.0F));

        // The OPVT deep-fording kit, and the only vehicle here that carries one. The pack's own
        // answer for this hull is that water stops it: `t64.json` has no `can_wade`, so a T-64 that
        // drives in has its engine cut and cannot drive out again. Five metres is the published
        // figure for the real kit, measured over the ground the tank is standing on.
        //
        // The kit is not buoyancy - the tank keeps its weight and crosses on the riverbed, which is
        // what the real snorkel is for. See Fording.
        t64.fording = new Fording.Kit();

        p.vehicles.put("vvp_ywzj:t64", t64);
        addAbrams(p);
        addBtr3(p);
        addStryker(p);
        addPanther(p);
        addTigerE(p);
        addT90M(p);
        addT72B3M(p);
        addVt5(p);
        addVt4(p);
        addK2(p);
        addType10(p);
        addTianma20(p);
        addZtz100(p);
        addLeopard2a4(p);
        addWw2Aircraft(p);
        addTankAssaultGround(p);
        addWarOfResistance(p);

        // 2A46M-1 125mm APFSDS. No flat multiplier: its lethality comes from which plate it
        // went through, so it uses the per-plate values above.
        p.ammo_penetration.put("vvp_ywzj:2a46m1_125mm_apfsds",
                shelf(new Ammo(457.0F), AmmoCategory.CANNON_AP));
        p.ammo_penetration.put("vvp_ywzj:2a46m1_125mm_heat",
                shelf(shapedCharge(480.0F), AmmoCategory.CANNON_HEAT));
        p.ammo_penetration.put("vvp_ywzj:2a46m1_125mm_he",
                shelf(highExplosive(60.0F), AmmoCategory.CANNON_HE));
        p.ammo_penetration.put("vvp_ywzj:2a46m1_125mm_he_vt",
                shelf(proximityFuzed(60.0F), AmmoCategory.CANNON_HE));

        // M256 120mm, the Abrams' gun. The dart is a separate entry from the T-64's because the
        // two guns do not penetrate alike, and the shaped charge carries the same shape as the
        // 125mm one with only the numbers differing.
        //
        // No HE entry: the Abrams does not carry one in the pack, and a table entry for a weapon
        // that does not exist is a setting that can never do anything. Its armour table against HE
        // is still configured below - a 125mm HE shell fired at an Abrams is measured against it.
        // The Abrams' dart slot carries the whole M829 family. They are loads for one slot rather
        // than four more slots, because a vehicle's weapon list is how many *kinds* of shell it
        // carries and this is one kind - a kinetic dart - with several marks of it. The gunner
        // picks between them with the left Alt key; see GunnerSettings.
        Ammo dart = new Ammo(570.0F);
        dart.variants = new java.util.ArrayList<>(java.util.List.of(
                kineticVariant("M829A1", 600.0F),
                kineticVariant("M829A2", 630.0F),
                kineticVariant("M829A3", 700.0F),
                kineticVariant("M829A4", 780.0F)));
        p.ammo_penetration.put("vvp_ywzj:m256_120mm_apfsds", shelf(dart, AmmoCategory.CANNON_AP));

        // The KE-W, the export tungsten round the Ukrainian Abrams carries instead of the DU M829.
        // A round of its own rather than a fifth load on the M829 slot, because the four Abrams share
        // that slot: loads are the gunner's choice within one shell, and a Ukrainian tank's gunner
        // choosing between M829 and KE-W is a choice the real tank does not offer - it carries one.
        //
        // The figures are the published estimates for the two export marks, at 2km: the A1 at 570 to
        // 590mm, and the A2 at about 700. The A1 is the round General Dynamics' own comparison chart
        // sets against the baseline M829, which is why it lands on the M829's own 570 rather than on
        // the M829A1's 600 - a different maker's round at the same place in the line-up.
        //
        // The A3 and A4 exist but are deliberately absent: no figure for either is published, and the
        // one source that discusses them says outright that its numbers for them are a guess. A load
        // list is the wrong place to put a guess.
        Ammo kew = new Ammo(580.0F);
        kew.variants = new java.util.ArrayList<>(java.util.List.of(
                kineticVariant("KE-W A2", 700.0F)));
        p.ammo_penetration.put("vvp_ywzj:kew_120mm_apfsds", shelf(kew, AmmoCategory.CANNON_AP));
        p.ammo_penetration.put("vvp_ywzj:m256_120mm_heat",
                shelf(shapedCharge(480.0F), AmmoCategory.CANNON_HEAT));
        // The multi-purpose round, whose fuze the gunner switches in flight. It ships with its own
        // weapon id because the fuze belongs to the shell, not to the gun: two M1A1s can carry
        // different fuzes at the same time, and neither one changes what the plain HEAT round does.
        p.ammo_penetration.put("vvp_ywzj:m256_120mm_heat_mp",
                shelf(multiPurposeHeat(480.0F), AmmoCategory.CANNON_HEAT));

        // The Abrams' two machine guns, given the same treatment as the T-64's and the same numbers,
        // because they are the same guns by another name: the M240 is the 7.62mm the T-64's PKT is,
        // and the M2HB is the 12.7mm its NSVT is, down to the pack's own damage figures of 10 and
        // 20. Both are far too weak to trouble tank armour and both get a flat multiplier instead of
        // the per-plate ones - what they hit makes no difference at these energies.
        //
        // The M240 is listed twice on the M1A1 (the coaxial mount and the loader's), but that is one
        // weapon id and so one entry; the M2HB is the commander's.
        p.ammo_penetration.put("vvp_ywzj:m240_7_62mm",
                shelf(new Ammo(15.0F, 3.0F), AmmoCategory.MACHINE_GUN));
        p.ammo_penetration.put("vvp_ywzj:m2_50cal",
                shelf(new Ammo(30.0F, 5.0F), AmmoCategory.MACHINE_GUN));

        // BTR-3's ZTM-1 30mm cannon and its coaxial KT-7.62. The machine gun is the same 7.62mm as
        // the other two vehicles' and gets the same numbers.
        //
        // The high-explosive shell is the pack's existing one - the weapon file already carries the
        // explosion - and is left exactly as it was apart from being given a penetration. Six
        // millimetres does not reach the thinnest plate on any vehicle configured here (the BTR-3's
        // own roof, at eight), so it never penetrates and its damage remains what it always was:
        // the pack's own blast. That is deliberate, not an oversight: the addon drops a round's
        // armour-blind explosion only when it would drown out the armour table, and 10 damage
        // against 3000 health does not.
        // **Both belts carry both rounds, as two weapons rather than as one round and a load.** The
        // group they belong to is in the pack - see $belts in add_shells.ps1 - and the gunner cycles
        // within a belt with the multi-weapon key (F) and changes belt with the primary weapon
        // switch, which is the mouse wheel. What the belt decides is which round a gunner who has
        // just switched to this gun gets without asking: belt 1 arrives with the dart.
        //
        // Neither entry has a load list, and that is not an omission. The two rounds differ in their
        // projectile - the shell carries the pack's explosion and the dart carries none - and a load
        // is one weapon fired with another's figures, so a load could never have brought that
        // difference with it. Two weapons in a group can.
        Ammo autocannonHe = new Ammo(6.0F);
        autocannonHe.profile = "he";
        p.ammo_penetration.put("vvp_ywzj:ztm1_30mm",
                shelf(autocannonHe, AmmoCategory.AUTOCANNON_HE));
        // The dart. No profile and no flat multiplier, so it is measured against the vehicle's own
        // table with the vehicle's own multipliers - which is what makes the wheels come out at
        // x1.5 while every plate around them is x5.
        p.ammo_penetration.put("vvp_ywzj:ztm1_30mm_apfsds",
                shelf(new Ammo(90.0F), AmmoCategory.AUTOCANNON_AP));
        p.ammo_penetration.put("vvp_ywzj:kt_7_62_7_62mm",
                shelf(new Ammo(15.0F, 3.0F), AmmoCategory.MACHINE_GUN));

        // The RK-2S Barrier ATGM, and the first round here with a tandem warhead. Since v70 the
        // flag no longer changes the armour rule: a reactive panel takes its HP off this round like
        // any other, then the armour behind is measured. Against the T-90M's 600 HP block 800mm
        // leaves 200, which the 250mm turret side stops; the T-64's 500 HP panel leaves 300 against
        // a 200mm flank. See ArmorSystem.resolve.
        //
        // 800mm is the figure usually quoted for this missile. Against the vehicles here it is more
        // than the T-90M's turret front (650) and less than the T-64's (880), so it penetrates one
        // and not the other once it has got past whatever is bolted to the outside.
        Ammo rk2s = new Ammo(800.0F);
        rk2s.profile = "heat";
        rk2s.tandem = Boolean.TRUE;
        // The jet scatters spall whether or not it gets in, as every shaped charge here does. The
        // missile's own 120-damage explosion is left alone: it is small enough not to drown out the
        // table, which is the only reason this addon ever drops one.
        rk2s.spall_damage_min = 1.5F;
        rk2s.spall_damage_max = 3.0F;
        rk2s.spall_blindness_ticks = 20;
        p.ammo_penetration.put("vvp_ywzj:rk2s_barrier", shelf(rk2s, AmmoCategory.CANNON_HEAT));

        // Coaxial PKT 7.62mm and the commander's NSVT 12.7mm. Both carry far too little
        // penetration to bother tank armour, so they get a flat multiplier instead.
        p.ammo_penetration.put("vvp_ywzj:pkt_7_62mm",
                shelf(new Ammo(15.0F, 3.0F), AmmoCategory.MACHINE_GUN));
        p.ammo_penetration.put("vvp_ywzj:nsvt_12_7mm",
                shelf(new Ammo(30.0F, 5.0F), AmmoCategory.MACHINE_GUN));

        // --- Tiger E -------------------------------------------------------------------------
        // PzGr 39, the standard 88mm APCBC-HE round: 165mm of rolled-equivalent steel at the muzzle
        // (132mm at 1km, 106mm at 2km). One number at the muzzle, the shape every round here takes.
        // It carries the pack's small blast (250 dmg, radius 1.5) - well above any other round's flat
        // model - so it is dropped, as on the Panther's PzGr 39/42.
        //
        // It is an APHE shell, and the entry now states both halves of that. The projectile is the
        // real one - 10.2 kg at 773 m/s - so the debris model measures the hole from the plate the
        // round went through, and the base charge is the captured-document figure for this family,
        // 2.12 oz (60 g) of cyclonite behind a base fuze. Cyclonite is about 1.6 times TNT, so the
        // entry carries 0.10 kg of TNT equivalent. The old penetration_bonus band (20 up to 152 at
        // 152mm) was the shape of the same effect and is retired with it.
        Ammo tigerAp = new Ammo(152.0F);
        tigerAp.mass_kg = 10.2F;
        tigerAp.muzzle_velocity = 773.0F;
        tigerAp.caliber_mm = 88.0F;
        tigerAp.charge_kg = 0.10F;
        // The debris model recovers the in-flight velocity by inverting the penetration curve
        // through this exponent. The darts' 0.5 is their law; a full-calibre APCBC loses velocity
        // far faster than it loses penetration, and 1.65 is the fit through this round's own
        // published drop - 773 m/s at the muzzle, about 680 at one kilometre, 0.808 of the
        // muzzle penetration over the same distance.
        tigerAp.velocity_exponent = 1.65F;
        tigerAp.replaces_base_explosion = Boolean.TRUE;
        // The 88's own ballistics, anchored to the 152mm above: 136.5 at 500m, 122.8 at 1km,
        // 112.9 at 1.5km, 103.1 at 2km.
        tigerAp.falloffFromPenetration(TIGER_AP_RANGE_SHARE);
        p.ammo_penetration.put("tank_assault:cannon_88mm_pzgr_39",
                shelf(tigerAp, AmmoCategory.CANNON_AP));
        // PzGr 40, the tungsten APCR: 238mm at the muzzle (181mm at 1km, 137mm at 2km). Faster, lighter
        // core, smaller drag; in real life it traded penetration for the post-war fight the war had
        // become. The pack names the same shell and gives it 90 dmg of blast that is the wrong scale -
        // an APCR has no body's propagation whatever - so its `replaces_base_explosion` is on, just
        // like the dart side of the 30mm belts.
        //
        // The projectile itself: 7.3 kg at 930 m/s. An APCR has no bursting charge at all, so its
        // damage is the hole and the plate fragments and nothing else.
        Ammo tigerApcr = new Ammo(156.0F);
        tigerApcr.mass_kg = 7.3F;
        tigerApcr.muzzle_velocity = 930.0F;
        tigerApcr.caliber_mm = 88.0F;
        // Its velocity/penetration fit: 930 m/s at the muzzle, about 830 at one kilometre against
        // 0.790 of the muzzle penetration, which is an exponent of 2.0.
        tigerApcr.velocity_exponent = 2.0F;
        tigerApcr.replaces_base_explosion = Boolean.TRUE;
        // The tungsten core loses speed faster than the APCBC: 139.3 at 500m, 123.2 at 1km.
        tigerApcr.falloffFromPenetration(TIGER_APCR_RANGE_SHARE);
        p.ammo_penetration.put("tank_assault:cannon_88mm_pzgr_40",
                shelf(tigerApcr, AmmoCategory.CANNON_AP));
        // Sprgr 43, the high-explosive round. 60mm so it never penetrates the thinnest plate
        // configured here (the hull roof at 25mm is close, but a 60mm round against a 25mm plate
        // resolves as `60 - 25 = 35 > 0` - so I raised it). The pack's 900 dmg at radius 11 would
        // bury the table, so it is dropped.
        //
        // The charge is the captured-document figure for the Sprgr. L/4.5 family: 1.9 lb (0.86 kg)
        // of Amatol 40/60, about 0.70 kg of TNT equivalent. The old flat 300 inside and 50-150 of
        // scab were the shape of the same blast and are retired: the model now bursts with the
        // whole charge inside, and scabs a thin plate that keeps it out.
        Ammo tigerHe = new Ammo(60.0F);
        tigerHe.profile = "he";
        tigerHe.charge_kg = 0.70F;
        tigerHe.replaces_base_explosion = Boolean.TRUE;
        p.ammo_penetration.put("tank_assault:cannon_88mm_sprgr_43",
                shelf(tigerHe, AmmoCategory.CANNON_HE));

        // --- Tank Assault -------------------------------------------------------------------
        // PzGr 39/42, the Panther's armour-piercing round. It is an APHE shell, and both halves of
        // that are here: the hole it punches is worth what any kinetic round's hole is worth, and on
        // top of that comes a roll for what the plate threw off, whose ceiling climbs with the plate
        // it broke - about 56 through a 40mm roof, the whole 200 through the glacis. 192mm of
        // penetration is measured against the same equivalent thicknesses everything else is, so it
        // defeats the Panther's own glacis and not much else it meets today.
        Ammo pantherAp = new Ammo(168.0F);
        pantherAp.penetration_bonus_min = 20.0F;
        pantherAp.penetration_bonus_max = 168.0F;
        pantherAp.penetration_bonus_max_at = 168.0F;
        // The pack gives this round an 180-damage explosion, which knows nothing about armour and
        // would land on every hit on top of the roll. Dropped, as on every other round here whose
        // numbers are meant to be the whole answer.
        pantherAp.replaces_base_explosion = Boolean.TRUE;
        // A shell this old loses a great deal of penetration with range, and the table is what
        // says so. Anchored to the 168mm above, so the curve and the figure cannot disagree.
        pantherAp.falloffFromPenetration(APHE_RANGE_SHARE);
        p.ammo_penetration.put("tank_assault:cannon_75mm_pzgr_39_42",
                shelf(pantherAp, AmmoCategory.CANNON_AP));

        // Sprgr 42, the high-explosive round: 13mm of penetration, so it stops at the first real
        // plate and throws 50-150 of blast at it. Get through a roof and it is a flat 300 - a
        // bursting charge does not care how much of it got in, only that it did.
        Ammo pantherHe = new Ammo(13.0F);
        pantherHe.profile = "he";
        pantherHe.penetration_damage = 300.0F;
        pantherHe.spall_damage_min = 50.0F;
        pantherHe.spall_damage_max = 150.0F;
        // The blast is already the penetrating case's whole answer, so it must not also land there.
        pantherHe.spall_on_penetration = Boolean.FALSE;
        // The pack's own explosion for this one is 560 at radius 8, which would bury the table.
        pantherHe.replaces_base_explosion = Boolean.TRUE;
        p.ammo_penetration.put("tank_assault:cannon_75mm_sprgr_42",
                shelf(pantherHe, AmmoCategory.CANNON_HE));

        // The hull and coaxial MG 34, given the same treatment as every other machine gun here and
        // for the same reason: far too weak to trouble armour, so what it hit makes no difference.
        p.ammo_penetration.put("tank_assault:gun_mg34",
                shelf(new Ammo(15.0F, 3.0F), AmmoCategory.MACHINE_GUN));

        // --- Tank Assault aircraft ----------------------------------------------------------
        // The three fighters' guns. The machine guns keep the flat multiplier every other MG here
        // has: on a tank they are far too weak for the plate to matter. The two 20mm cannon are
        // high-explosive, and their penetration only has to beat an aircraft's 2-2.5mm covering -
        // what they are worth once inside is the charge, so their damage is a flat figure rather
        // than the residual formula. Their pack explosions are left in place (unlike the tank's
        // warheads): an aircraft takes blast damage, and the 20mm's 20-point blast is part of what
        // makes a mine shell hurt.
        p.ammo_penetration.put("tank_assault:gun_mg17",
                shelf(new Ammo(11.0F, 3.0F), AmmoCategory.MACHINE_GUN));
        p.ammo_penetration.put("tank_assault:gun_m2_browning",
                shelf(new Ammo(25.0F, 5.0F), AmmoCategory.MACHINE_GUN));
        // The 20mm rounds are rated against the seat backs, not just the skin: 22-25mm is what a
        // mine shell's kinetic core is worth, and it is what lets one reach a pilot behind 12-14mm
        // of armour that a rifle round cannot. Their damage is the charge (45/40), not the
        // residual, and their pack blast stays in place.
        Ammo mg151 = new Ammo(23.0F);
        mg151.profile = "he";
        mg151.penetration_damage = 45.0F;
        p.ammo_penetration.put("tank_assault:auto_cannon_mg151",
                shelf(mg151, AmmoCategory.AUTOCANNON_HE));
        Ammo mgffm = new Ammo(22.0F);
        mgffm.profile = "he";
        mgffm.penetration_damage = 40.0F;
        p.ammo_penetration.put("tank_assault:auto_cannon_mgffm",
                shelf(mgffm, AmmoCategory.AUTOCANNON_HE));

        // --- T-90M --------------------------------------------------------------------------
        // The dart, and the four 125mm rounds the 2A46M-5 can be fed. 3BM60 "Svinets-2" is the one
        // the gunner gets by default, because it is the round this turret was built around.
        //
        // The ladder is anchored at both ends and interpolated in the middle, which is worth being
        // plain about: 3BM42 is the round the T-64 already fires and keeps that entry's 457, and
        // 3BM60 is the figure the design gives. 3BM42M and 3BM46 sit between them in the order the
        // sources put them - an improved Mango, then Svinets - without either number coming from a
        // source that states it. Listed strongest first, so the chooser reads down from the default
        // rather than jumping to the weakest round and climbing back.
        Ammo t90Dart = new Ammo(700.0F);
        t90Dart.variants = new java.util.ArrayList<>(java.util.List.of(
                kineticVariant("3BM46", 600.0F),
                kineticVariant("3BM42M", 520.0F),
                kineticVariant("3BM42", 457.0F)));
        p.ammo_penetration.put("vvp_ywzj:2a46m5_125mm_t90m_apfsds",
                shelf(t90Dart, AmmoCategory.CANNON_AP));

        // The T-72B3M's 2A46M-5, which is the same gun firing the same ammunition - so the same
        // four rounds and the same ladder. Entries of its own only because the pack ships the
        // weapons separately, and the config is keyed by weapon id.
        Ammo t72Dart = new Ammo(700.0F);
        t72Dart.variants = new java.util.ArrayList<>(java.util.List.of(
                kineticVariant("3BM46", 600.0F),
                kineticVariant("3BM42M", 520.0F),
                kineticVariant("3BM42", 457.0F)));
        p.ammo_penetration.put("vvp_ywzj:2a46m5_125mm_apfsds",
                shelf(t72Dart, AmmoCategory.CANNON_AP));
        p.ammo_penetration.put("vvp_ywzj:2a46m5_125mm_heat",
                shelf(shapedCharge(520.0F), AmmoCategory.CANNON_HEAT));
        // The commander's Kord, the same 12.7mm the T-90M's 6P49MT is under another designation,
        // with the pack's own mounting difference and the same flat multiplier as every other MG.
        p.ammo_penetration.put("vvp_ywzj:6p49_kord_12_7mm",
                shelf(new Ammo(30.0F, 5.0F), AmmoCategory.MACHINE_GUN));

        // The 125mm high-explosive shell, carried by the T-72B3M and all three T-90Ms. It is one
        // entry because it is one round: 3OF26, the shell this gun family has thrown since the
        // 1970s, given the same numbers as the T-64's so that a 125mm high-explosive shell does
        // not mean two different things depending on which tank fired it.
        Ammo he125 = highExplosive(60.0F);
        he125.variants = new java.util.ArrayList<>(java.util.List.of(telnik()));
        p.ammo_penetration.put("vvp_ywzj:2a46m5_125mm_he",
                shelf(he125, AmmoCategory.CANNON_HE));
        p.ammo_penetration.put("vvp_ywzj:2a46m5_125mm_t90m_heat",
                shelf(shapedCharge(520.0F), AmmoCategory.CANNON_HEAT));
        // The commander's 6P49MT, the same 12.7mm as the NSVT with a different mounting.
        p.ammo_penetration.put("vvp_ywzj:6p49mt_kord_12_7mm",
                shelf(new Ammo(30.0F, 5.0F), AmmoCategory.MACHINE_GUN));

        // --- VT-5 (Armor Pioneer) -----------------------------------------------------------
        // The improved 105mm rifled gun's BTA-2 dart is quoted at 550mm RHA at 2000m, which is the
        // whole reason this light tank is dangerous: it cannot stop a modern dart, but it carries
        // one. The older DTW-2 is a load of the same round rather than a weapon of its own, so it
        // lives in the variant list and the gunner picks it with the same chooser as the M829
        // family.
        Ammo vt5Dart = new Ammo(550.0F);
        // The projectile: a 105mm dart of about 4.5 kg at 1600 m/s. No primary source states the
        // BTA-2's mass or velocity, so both are estimates on the ZTZ-100's 105mm figure.
        vt5Dart.mass_kg = 4.5F;
        vt5Dart.muzzle_velocity = 1600.0F;
        vt5Dart.caliber_mm = 105.0F;
        Ammo dtw2 = kineticVariant("DTW-2 APFSDS", 450.0F);
        dtw2.mass_kg = 4.2F;
        dtw2.muzzle_velocity = 1550.0F;
        dtw2.caliber_mm = 105.0F;
        vt5Dart.variants = new java.util.ArrayList<>(java.util.List.of(dtw2));
        vt5Dart.replaces_base_explosion = Boolean.TRUE;
        // A variant is a load, not a copy of the slot: variant() returns the variant object
        // itself, so every field that matters has to be stated on it. The dart's variants carry
        // the same "my damage, not the pack's 250-point explosion" flag as the base round.
        for (Ammo load : vt5Dart.variants) {
            load.replaces_base_explosion = Boolean.TRUE;
        }
        p.ammo_penetration.put("armor_pioneer:cannon_105mm",
                shelf(vt5Dart, AmmoCategory.CANNON_AP));

        // The rest of the 105mm family. These are separate weapon ids rather than loads: a load
        // only changes what the armour table says, while the projectile and its explosion belong
        // to the weapon file - and a HEAT or HE round needs its own.
        // The jet model's rating, calibrated so the round still deals what the old
        // residual-times-face rule dealt on a 120mm flank: the 330 left over, times four.
        Ammo vt5Heat = shapedCharge(450.0F);
        vt5Heat.heat_damage = 1615.0F;
        p.ammo_penetration.put("armor_pioneer:cannon_105mm_heat",
                shelf(vt5Heat, AmmoCategory.CANNON_HEAT));

        // The BTM1-105, the bunker round from the same display: a tandem shaped charge meant to
        // open a reinforced-concrete bunker. Its published claim is a number of concrete layers,
        // not an RHA figure, so 600mm is an estimate placed below the GP105's 700 and above the
        // plain HEAT round. The tandem flag is documentation since v70; the armour table sees the
        // 600mm like any other shaped charge.
        Ammo btm1 = shapedCharge(600.0F);
        btm1.tandem = Boolean.TRUE;
        btm1.heat_damage = 2220.0F;
        p.ammo_penetration.put("armor_pioneer:cannon_105mm_bunker",
                shelf(btm1, AmmoCategory.CANNON_HEAT));

        // A 105mm bursting charge is smaller than the 125mm shell: 2.1 kg of TNT equivalent,
        // which is the old 1200-point burst stated as the charge it was standing in for.
        Ammo vt5He = chargeShell(60.0F, 2.1F);
        p.ammo_penetration.put("armor_pioneer:cannon_105mm_he",
                shelf(vt5He, AmmoCategory.CANNON_HE));

        // The gun-launched missile. Its warhead is a tandem shaped charge; since v70 the flag is
        // documentation and the 700mm is measured against reactive panels like any other round.
        // Its flight is the base mod's.
        Ammo gp105 = shapedCharge(700.0F);
        gp105.tandem = Boolean.TRUE;
        gp105.heat_damage = 2622.0F;
        p.ammo_penetration.put("armor_pioneer:cannon_105mm_atgm",
                shelf(gp105, AmmoCategory.CANNON_HEAT));

        // The pack-local machine guns. They are the same guns the base mod ships, with ids this
        // pack owns - which is what lets the addon rate them at all: the base mod's generic
        // gun ids have no pack file, and an entry for one would never be matched to a name.
        //
        // One id per gun, not per calibre. The HUD shows the weapon file's own `name`, so a
        // shared 7.62mm id can only ever name one of the four guns. The Chinese vehicles
        // (VT-5, VT-4, ZTZ-100) all carry the Type 86 and share this id; the Type 10's Type 74,
        // the K2's M60E2 and the Cheonma-20's PKT are ids of their own. Same 15mm/x3 rating.
        p.ammo_penetration.put("armor_pioneer:gun_7_62mm",
                shelf(new Ammo(15.0F, 3.0F), AmmoCategory.MACHINE_GUN));
        p.ammo_penetration.put("armor_pioneer:type74_7_62mm",
                shelf(new Ammo(15.0F, 3.0F), AmmoCategory.MACHINE_GUN));
        p.ammo_penetration.put("armor_pioneer:m60e2_7_62mm",
                shelf(new Ammo(15.0F, 3.0F), AmmoCategory.MACHINE_GUN));
        p.ammo_penetration.put("armor_pioneer:pkt_7_62mm",
                shelf(new Ammo(15.0F, 3.0F), AmmoCategory.MACHINE_GUN));
        p.ammo_penetration.put("armor_pioneer:gun_12_7mm",
                shelf(new Ammo(30.0F, 5.0F), AmmoCategory.MACHINE_GUN));

        // The QLZ-04 35mm grenade launcher. An automatic grenade launcher is not an anti-tank
        // weapon, so its round is rated as what the pack says it is - high explosive with a small
        // charge - rather than being given a HEAT figure it does not have. It can still hurt a
        // thin roof; against a tank's front plate it does nothing but spall.
        Ammo qlz = new Ammo(30.0F);
        qlz.profile = "he";
        qlz.penetration_damage = 60.0F;
        qlz.spall_damage_min = 10.0F;
        qlz.spall_damage_max = 30.0F;
        qlz.spall_falloff_to = 400.0F;
        qlz.spall_on_penetration = Boolean.FALSE;
        qlz.spall_blindness_ticks = 10;
        qlz.replaces_base_explosion = Boolean.TRUE;
        p.ammo_penetration.put("armor_pioneer:qlz_04_35mm",
                shelf(qlz, AmmoCategory.AUTOCANNON_HE));

        // --- VT-4 (Armor Pioneer) -----------------------------------------------------------
        // The 125mm smoothbore. A different gun from the Russian 2A46 family even though the
        // calibre matches, and a different round. Six are on the plinth Norinco shows with the
        // tank - BTA4 and DTC10E darts, BTJ1 HEAT, BTE2 HE, BTS8 PELE and the GP125 missile -
        // and all six are here, under the ids the pack names.
        //
        // The two darts are marks of one round, not two weapon ids: the gun fires one kinetic
        // slot either way, and the gunner picks between them with the load chooser, the same
        // arrangement the M829 family and the VT-5's 105mm have. BTA4 keeps the 600mm the export
        // tank has always been rated at; DTC10E, the newer of the two, is the extra load.
        //
        // No `replaces_base_explosion` on the dart: this weapon's file has no explosion block,
        // so there is nothing to drop. The 105mm dart needed the flag for the opposite reason.
        Ammo vt4Dart = new Ammo(600.0F);
        vt4Dart.name = "BTA4 125mm APFSDS";
        // Both marks are long rods and both get the same decay shape, each anchored to its own
        // muzzle figure - which is why the curve is set on the variant as well as the slot.
        vt4Dart.falloffFromPenetration(DART_RANGE_SHARE);
        vt4Dart.mass_kg = 7.5F;
        vt4Dart.muzzle_velocity = 1700.0F;
        vt4Dart.mass_exponent = 0.70F;
        vt4Dart.velocity_exponent = 0.50F;
        vt4Dart.caliber_mm = 125.0F;
        Ammo dtc10e = kineticVariant("DTC10E 125mm APFSDS", 680.0F)
                .falloffFromPenetration(DART_RANGE_SHARE);
        dtc10e.mass_kg = 7.6F;
        dtc10e.muzzle_velocity = 1740.0F;
        dtc10e.mass_exponent = 0.70F;
        dtc10e.velocity_exponent = 0.50F;
        dtc10e.caliber_mm = 125.0F;
        vt4Dart.variants = new java.util.ArrayList<>(java.util.List.of(dtc10e));
        p.ammo_penetration.put("armor_pioneer:cannon_125mm",
                shelf(vt4Dart, AmmoCategory.CANNON_AP));

        // BTJ1, the shaped charge - a little above the Russian 125mm rounds (480 / 520) for the
        // larger warhead the Chinese round carries.
        Ammo vt4Heat = shapedCharge(550.0F);
        // The jet-residual model's rating, calibrated so the round still deals what the old
        // residual-times-face rule dealt on the flank the old table used as its side figure.
        vt4Heat.heat_damage = 2030.0F;
        p.ammo_penetration.put("armor_pioneer:cannon_125mm_heat",
                shelf(vt4Heat, AmmoCategory.CANNON_HEAT));

        // BTE2, the anti-personnel shell: the same 125mm HE the T-64 and the T-90M carry, down to
        // the 2000-point charge, so a 125mm high-explosive round means one thing whatever fired
        // it. The pack calls it a killing shell rather than a demolition one; the charge inside
        // is the same.
        Ammo vt4He = highExplosive(60.0F);
        vt4He.charge_kg = 4.0F;
        p.ammo_penetration.put("armor_pioneer:cannon_125mm_he",
                shelf(vt4He, AmmoCategory.CANNON_HE));

        // BTS8, the lateral-effect round (PELE): a penetrator with an inert filler behind a hard
        // nose, which breaks up sideways after it has punched its hole. It is not a dart - 320mm
        // is enough for a light vehicle and nowhere near a modern MBT face.
        //
        // The old entry stood in for the effect with a penetration_bonus band (20 up to 300 at
        // 150mm). That is the crude shape: it made the round better against every plate at once
        // and said nothing about the projectile or its speed. The lateral effect is the energy the
        // plate takes out of the body, so the entry states the projectile - 7.0 kg at 1700 m/s -
        // and PeleModel turns the perforation energy into fragments. The hole still does its own
        // residual damage; the lateral effect is added on top, which is what keeps the round
        // usable against a thin-skinned target that the effect itself barely engages.
        Ammo bts8 = new Ammo(320.0F);
        bts8.name = "BTS8 125mm PELE";
        bts8.mass_kg = 7.0F;
        bts8.muzzle_velocity = 1700.0F;
        bts8.caliber_mm = 125.0F;
        bts8.pele = Boolean.TRUE;
        p.ammo_penetration.put("armor_pioneer:cannon_125mm_pele",
                shelf(bts8, AmmoCategory.CANNON_AP));

        // GP125, the gun-launched missile: laser beam-riding (the pack's SACLOS), a tandem
        // shaped charge, and a much longer reach than the gun's own round. 800mm is the same
        // order as the GP-105's 700 and the 850 the published tables give this missile.
        Ammo gp125 = shapedCharge(800.0F);
        gp125.name = "GP125";
        gp125.tandem = Boolean.TRUE;
        p.ammo_penetration.put("armor_pioneer:cannon_125mm_atgm",
                shelf(gp125, AmmoCategory.CANNON_HEAT));

        addTaczRounds(p);

        // -----------------------------------------------------------------------------------------
        // The adjustable suspensions - see Suspension itself, and Profile#suspensions for why this
        // is a map of its own rather than a block on each vehicle's armour.
        //
        // Nine vehicles. The Stryker is the wheeled one and has shipped since v38; the other eight
        // are tracked, and they are here on two conditions at once - their models carry the
        // ywzj_vehicle:suspension parts the mechanism lengthens, and the real vehicle has a
        // hydropneumatic suspension that a crew could actually work.
        //
        //   BMP-3, BRM-3K and the 9K123 Khrizantema - one chassis, three vehicles. The BMP-3's
        //     suspension is hydropneumatic with a ground clearance the driver selects, which makes
        //     it the one suspension in this pack that is documented as crew-controlled at all.
        //   Challenger 2 - Horstman Hydrostrut; Horstman also build a ride-height control system.
        //   Puma - hydropneumatic.
        //   Ajax - hydrogas, on the ASCOD platform.
        //   K2 Black Panther - an in-arm hydropneumatic unit on each station. Its crew can select
        //     ride height and kneel the hull, which is the same feature this block drives.
        //   Type 10 - a hydropneumatic system with active attitude control; the JGSDF demonstrates
        //     the hull kneeling and pitching. It joined at v60 with the same 0.60 of pack travel
        //     the K2 carries, and at v63 it took the K2's four-degree lateral axis as well.
        //
        // The T-64, the T-72B3M, the three T-90Ms, the four Abrams, the Armor Pioneer VT-5 and
        // VT-4, and the Tianma-20 are deliberately not here. The pack gives every one of their
        // models suspension parts - the VT-5 and the VT-4 twelve each, the Tianma fourteen - so the
        // two keys would answer and the hull would really move. But every one of those tanks has
        // torsion bars, and "the model has springs" is not the same statement as "the tank has a
        // ride-height control". Giving them one would be inventing a feature, not modelling one.
        // The K2 and Type 10 above are the exceptions that prove the rule: their springs are the
        // real hydraulic systems, not torsion bars.
        p.suspensions.put("vvp_ywzj:stryker_m1296", new Suspension.Settings());
        for (String id : new String[]{
                "vvp_ywzj:bmp_3", "vvp_ywzj:brm", "vvp_ywzj:chryzantema",
                "vvp_ywzj:challenger", "vvp_ywzj:puma", "vvp_ywzj:ajax"}) {
            p.suspensions.put(id, hydropneumatic());
        }
        p.suspensions.put("armor_pioneer:k2", k2Suspension());
        p.suspensions.put("armor_pioneer:type10", type10Suspension());

        // -----------------------------------------------------------------------------------------
        // The hybrid drives - see Hybrid itself, and Profile#hybrids for why this is a map of its
        // own rather than a block on each vehicle's armour.
        //
        // One vehicle: the ZTZ-100, whose published reports describe a hybrid-electric powertrain.
        // The settings are the shipped tuning, not a measurement - they are chosen so that a full
        // battery gives about thirty-three seconds of full-throttle electric driving, the diesel
        // puts it back in under a minute and a half, and the electric mode is a genuine low-speed
        // mode (55% of the gearbox's top speed) rather than only a quieter one.
        p.hybrids.put("armor_pioneer:ztz100", new Hybrid.Settings());

        return p;
    }

    /**
     * What TACZ's cartridges do to a plate, as millimetres of rolled homogeneous armour.
     *
     * <p>TACZ has no penetration figure of its own. Its data gives each gun a flat {@code damage}
     * number, an {@code armor_ignore} fraction - which is about vanilla armour <em>points</em> - and a
     * damage-over-distance curve, and none of those is a distance through steel. So this table is
     * the whole of the modelling work, and every number in it is a real-world figure rather than a
     * number derived from TACZ's own balance:
     *
     * <p><b>That is a deliberate choice with a visible consequence.</b> TACZ's damage numbers are
     * tuned for how a gun should <em>feel</em>; these are tuned for what the round does to a plate.
     * The two disagree, and where they do, this table wins - so a weapon the gun pack rates as
     * strong can be one that cannot open a BTR-3 from the side. 7.62×39 and 5.56×45 are 10 and 12
     * millimetres, and every vehicle in this config is 35 or more on the flanks: against armour,
     * they do nothing at all, by design and in reality, and their answer to a tank is the roof, the
     * floor, or a different weapon.
     *
     * <p>One number per cartridge, taken at the muzzle, which is the convention the rest of this
     * config already uses for tank guns. Where a cartridge's penetration depends heavily on which
     * loading is in the magazine - .338 Lapua is 10mm as a match round and around 20 as AP - the
     * figure picked is the one an armour-piercing loading gets, because that is the round a player
     * reaches for when there is a vehicle in front of them.
     *
     * <h2>The two shaped charges</h2>
     *
     * <p>{@code tacz:40mm} and {@code tacz:rpg_rocket} carry {@code "profile": "heat"}, so they are
     * measured against the chemical table rather than the kinetic one - which is the only correct
     * way to rate a shaped charge, and the reason the profile key exists. Note that this makes the
     * RPG the one thing in TACZ that threatens a main battle tank: 330mm against the T-64's 440mm
     * glacis is a miss, and against its 100mm flank is through.
     *
     * <h2>Sources</h2>
     *
     * <p>Published figures, rounded to whole millimetres; the README carries the same table with the
     * same numbers. The pistol and revolver cartridges are the least interesting rows here - a
     * 9×19 ball round makes a dent, not a hole - and the ones that matter are the four at the
     * bottom of the rifle block and the two launchers.
     */
    private static void addTaczRounds(Profile p) {
        // Cartridges whose job is to stop a person. Against steel they are all but useless, and the
        // spread between 2 and 5 is more about bullet construction than about energy.
        p.tacz_rounds.put("tacz:22wmr", new Ammo(2.0F));
        p.tacz_rounds.put("tacz:9mm", new Ammo(3.0F));
        p.tacz_rounds.put("tacz:45acp", new Ammo(3.0F));
        p.tacz_rounds.put("tacz:357mag", new Ammo(4.0F));
        p.tacz_rounds.put("tacz:45_70", new Ammo(4.0F));
        p.tacz_rounds.put("tacz:50ae", new Ammo(4.0F));
        p.tacz_rounds.put("tacz:500mag", new Ammo(5.0F));
        p.tacz_rounds.put("tacz:762x25", new Ammo(5.0F));
        // Personal-defence cartridges: small, fast, and designed against body armour rather than
        // steel, which is why they beat the pistols above without coming near a rifle.
        p.tacz_rounds.put("tacz:46x30", new Ammo(5.0F));
        p.tacz_rounds.put("tacz:57x28", new Ammo(7.0F));
        // The intermediate rifle cartridges, and the line the whole feature turns on: these are what
        // an assault rifle fires, and none of them opens any vehicle in this config from the side.
        p.tacz_rounds.put("tacz:545x39", new Ammo(10.0F));
        p.tacz_rounds.put("tacz:556x45", new Ammo(12.0F));
        p.tacz_rounds.put("tacz:762x39", new Ammo(10.0F));
        p.tacz_rounds.put("tacz:58x42", new Ammo(10.0F));
        p.tacz_rounds.put("tacz:68x51fury", new Ammo(14.0F));
        // .300 Blackout is a 5.56 case cut down and loaded with a heavy 7.62 bullet. Supersonic it
        // sits just under 7.62x39; the subsonic loadings people actually fit a suppressor for are
        // worse, and this is the one figure for both. Estimated rather than measured: published
        // RHA figures for the cartridge are thin, and TACZ has no subsonic/supersonic distinction.
        p.tacz_rounds.put("tacz:300blk", new Ammo(9.0F));
        // Full-power rifle cartridges. .308 in its common ball loading is the weakest of these and
        // the only one that overlaps the intermediates; the two AP loadings below it are a class up.
        p.tacz_rounds.put("tacz:308", new Ammo(8.0F));
        p.tacz_rounds.put("tacz:30_06", new Ammo(13.0F));
        p.tacz_rounds.put("tacz:792x57", new Ammo(13.0F));
        p.tacz_rounds.put("tacz:762x54", new Ammo(10.0F));
        p.tacz_rounds.put("tacz:338", new Ammo(15.0F));
        // The anti-materiel round, and the only kinetic cartridge here that reliably opens a light
        // vehicle from any angle its armour does not face.
        p.tacz_rounds.put("tacz:50bmg", new Ammo(20.0F));
        // A slug is a large slow lump of lead: it dents a plate and kills what is behind soft cover.
        p.tacz_rounds.put("tacz:12g", new Ammo(5.0F));
        // The two warheads. Measured against the chemical table, so their figures are shaped-charge
        // penetration and not a hole punched by a bullet.
        Ammo hedp = shapedCharge(50.0F);
        hedp.profile = "heat";
        p.tacz_rounds.put("tacz:40mm", hedp);
        Ammo rpg = shapedCharge(330.0F);
        rpg.profile = "heat";
        p.tacz_rounds.put("tacz:rpg_rocket", rpg);
    }

    /**
     * The range a tracked vehicle's crew can work: 0.20 of rise, 0.20 of drop, two degrees of pitch.
     *
     * <p>All three figures come out of the pack rather than out of a book, because all three are
     * bounded by the model rather than by the real vehicle.
     *
     * <ul>
     *   <li><b>0.20 of rise.</b> What a wheel can be asked for is bounded by how far its contact
     *       probe reaches below its mount, and that reduces to the pack's {@code max_droop} - see
     *       {@code SuspensionUnit.getMinCompression}, where {@code maxLength} works out to exactly
     *       that. <b>The pack ships 0.1 for most tracked vehicles in it</b>, a tenth of a block and
     *       invisible on a tank, so the pack patch raises it to 0.32 and the travel becomes 0.31.
     *       The two axes share that: the longest of the seven is the K2, whose furthest wheel
     *       stands 2.859 blocks from the hull's balance point and so takes
     *       {@code tan(2) x 2.859 = 0.100} of the pitch. 0.20 + 0.100 = 0.300, leaving 0.010.
     *       The Challenger, the previous longest, is 2.531 and takes 0.088.</li>
     *   <li><b>0.20 of drop.</b> Bounded by the hull rather than by the springs. The hull's
     *       collision box stands 0.598 blocks above the lowest point of the running gear on the
     *       tightest of the seven - the Ajax - so lowering it by 0.20 still leaves 0.40 of daylight
     *       under the belly. The K2 has 0.619, so the same 0.20 leaves 0.419. That is the figure
     *       {@code SuspensionCheck} re-derives from the model, and it is the one number here that a
     *       wrong value would make obvious in game: too much and the hull is on the ground, held up
     *       by its own collision box rather than by its springs.</li>
     *   <li><b>2 degrees</b> of pitch, the same the Stryker ships with - roughly what a long
     *       120mm gun gains in depression from it, and about all the budget above will pay for.</li>
     *   <li><b>The rate</b> is the default {@code 0.0125} blocks a tick: 0.25 blocks a second, so
     *       the full 0.20 takes sixteen ticks and a half-degree pitch press about two. That is the
     *       actuator, not the spring; the spring and damper still shape the hull's last few
     *       centimetres.</li>
     * </ul>
     *
     * <p>The K2's Armor Pioneer definition was shipped with {@code max_droop} 0.123 and
     * {@code max_extension} 0.2705, which would not even pay for 0.10 of rise plus the pitch; the
     * local pack zip raises both to 0.32. The six vvp vehicles get the same treatment from
     * {@code add_suspension.ps1}. {@code SuspensionCheck} reads the figures back out of whichever
     * pack is installed and fails the build if either end does not fit.
     */
    private static Suspension.Settings hydropneumatic() {
        Suspension.Settings settings = new Suspension.Settings();
        settings.max_rise = 0.20F;
        settings.max_drop = 0.20F;
        settings.max_pitch = 2.0F;
        return settings;
    }

    /**
     * K2's block: the shared ranged hydropneumatic, five degrees of pitch instead of two, a
     * four-degree lateral axis, and the track centre offset its support cubes need.
     *
     * <p>The height bracket is the same as the other tracked vehicles. Five degrees is its own
     * request and its own pack figure: 0.20 of rise plus tan(5) x 2.859 of pitch is 0.450 of
     * droop. The four-degree lean adds tan(4) x 1.684 = 0.118 at the outermost wheel, for 0.568
     * at the worst corner, so the local pack raises its {@code max_droop}/{@code max_extension}
     * to 0.60 rather than the 0.32 the two-degree vehicles use.
     *
     * <p>The offset is model-specific: its suspension support cubes span each wheel, so their
     * bottom - which {@code calculateTrackBounds} uses as the track bottom - is 0.0654 blocks
     * below the display model's link centres. With the link model drawn along the floating path,
     * that difference put the lower run in the ground. Raising the floating reference by the same
     * amount puts the link centres back where the display loop had them; the top and ends are
     * untouched.
     */
    private static Suspension.Settings k2Suspension() {
        Suspension.Settings settings = hydropneumatic();
        // The K2's in-arm system has more pitch travel than the other tracked vehicles. Five
        // degrees at its furthest wheel (2.859 blocks) needs tan(5) x 2.859 = 0.250, and with the
        // 0.20 rise the worst wheel wants 0.450 of droop.
        settings.max_pitch = 5.0F;
        // And the same units work left and right apart. Four degrees across the 1.684-block half
        // track adds tan(4) x 1.684 = 0.118 at the outermost wheel, so the worst corner wants
        // 0.568 of droop; the local pack raises the units to 0.60 for the pair of axes. Five
        // degrees would be 0.597 and leave only 0.003 under the engine's clamp - see Suspension,
        // max_roll, and SuspensionCheck, which re-derives the sum from the pack.
        settings.max_roll = 4.0F;
        settings.trackCenterOffset = 0.0654F;
        return settings;
    }

    /**
     * The Type 10's block: the K2's ranged hydropneumatic at the same height bracket, a five-degree
     * pitch, and the K2's four-degree lateral axis.
     *
     * <p>The Type 10's suspension is genuinely hydropneumatic and actively controlled - the JGSDF
     * demonstrates the hull kneeling and pitching to change the gun's attitude - so it belongs in
     * this map for the same reason the K2 does, and its pack carries the same 0.60 of travel.
     * Five degrees at the furthest unit needs tan(5) x 2.781 = 0.243, and the four-degree lean adds
     * tan(4) x 1.544 = 0.108 at the outermost unit, so the worst wheel wants 0.551 of the 0.59 the
     * pack leaves after the resting centimetre - {@code SuspensionCheck} re-derives all three
     * figures from the model.
     *
     * <p>The lateral axis is a request, not a documented feature of the real vehicle: v61 shipped
     * this block without one, and v63 opens it to the K2's four degrees because the user asked for
     * the Type 10 to have the K2's unit. The pack already carries the travel the lean spends, so
     * nothing on the pack side moves. The 0.0543 offset is measured the same way as the K2's: the
     * suspension support cubes' bottom sits 0.8686 model units (0.0543 blocks) below the display
     * track's link centres, and without it the floating track path would sink.
     */
    private static Suspension.Settings type10Suspension() {
        Suspension.Settings settings = hydropneumatic();
        settings.max_pitch = 5.0F;
        settings.max_roll = 4.0F;
        settings.trackCenterOffset = 0.0543F;
        return settings;
    }

    /**
     * The three T-90Ms the pack ships: the plain one, and the 2022 and 2024 Proryv variants.
     *
     * <p>**These numbers are a first draft**, not a reading of anything. Every other vehicle here
     * had its armour given by the design; this one does not, so what is written below is a
     * plausible starting point drawn from the tank's public reputation and the scale the rest of
     * this file is on - the T-64's turret front is 480 and the Abrams' 600, and the T-90M is a
     * generation later than both. It is meant to be corrected in game with the armour scanner, and
     * the numbers are in whole tens on purpose: nothing here is precise enough for a five to mean
     * anything.
     *
     * <p>What is *not* a guess is the shape. The three share a hull and turret exactly - the same
     * cube, measured - so the table is built once and only the bolt-ons differ, which is also the
     * only thing that distinguishes the variants in the pack.
     */
    private static void addT90M(Profile p) {
        // Plain T-90M: one set of add-on plates around the turret and nothing else. No `era_1` or
        // `era_2` on the hull either - this variant is the one the other two were built from, and
        // the blocks along the flanks are part of what the Proryv rebuilds added.
        p.vehicles.put("vvp_ywzj:t90_m", t90m(new String[]{
                "turret", "turret_machine_gun", "turret_smoke_grenade", "commander_machine_gun",
                "additional_armor", "hatch_1", "hatch_2"}, new String[0]));
        // 2022: reactive blocks on the turret's two flanks, and the pair along the hull's.
        p.vehicles.put("vvp_ywzj:t90_pror_2022", t90m(new String[]{
                "turret", "turret_machine_gun", "turret_smoke_grenade", "commander_machine_gun",
                "additional_armor", "additional_armor_rear", "era_3", "era_4", "hatch_1", "hatch_2"},
                new String[]{"era_1", "era_2"}));
        // 2024: a third block across the turret's rear, and a slat screen over its roof.
        p.vehicles.put("vvp_ywzj:t90_pror_2024", t90m(new String[]{
                "turret", "turret_machine_gun", "turret_smoke_grenade", "commander_machine_gun",
                "additional_armor", "additional_armor_rear", "additional_armor_top",
                "era_3", "era_4", "era_5", "hatch_1", "hatch_2"},
                new String[]{"era_1", "era_2"}));
    }

    /**
     * Whether a variant's part list contains a given part, which is what has the model behind it.
     *
     * <p>The list comes from the model through VehicleScaffold, so it is the one statement here
     * about what a variant carries that is measured rather than asserted. A separate flag per
     * bolt-on would be a second statement, and the two could disagree - a "no slat screen" flag
     * beside a part list holding one is a contradiction nothing in the build would report.
     */
    private static boolean carries(String[] turretParts, String part) {
        for (String candidate : turretParts) {
            if (candidate.equals(part)) {
                return true;
            }
        }
        return false;
    }

    /**
     * One T-90M, with whatever the model of that variant bolts on.
     *
     * @param turretParts every part the model parents to the turret, from VehicleScaffold - the
     *                    reactive blocks and the add-on plates included, because a hit on one of
     *                    them is a hit on the turret and not on the hull underneath it. It is also
     *                    what decides which bolt-ons get a value: see {@link #carries}
     * @param sideBlocks  the reactive blocks along the hull's flanks, which this variant wears and
     *                    the plain T-90M does not. Kept apart from {@code turretParts} because they
     *                    are hull parts on the model: a block shot away there has to fall through to
     *                    the hull behind it, not to the turret. The Abrams' {@code tiles} are the
     *                    same split for the same reason
     */
    private static VehicleArmor t90m(String[] turretParts, String[] sideBlocks) {
        VehicleArmor t90m = new VehicleArmor();
        t90m.turret = new ArmorSet(500.0F, 180.0F, 90.0F, 40.0F, 50.0F);
        // The hull's own front value is dead while the split below is enabled - the front face is
        // matched against the glacis or the lower plate and never against this - but it is kept
        // equal to the glacis so that turning the split off falls back to a figure that is still
        // right for this tank. The Abrams holds its hull front to the same rule.
        t90m.hull = new ArmorSet(700.0F, 80.0F, 60.0F, 40.0F, 40.0F);
        t90m.damage_multiplier = new DamageMultiplier(10.0F, 5.0F, 5.0F, 5.0F, 5.0F);

        // Measured: the barrel leaves the turret box's front within a twentieth of a block of its
        // centre, so the circle is all but centred. The barrel itself is 0.23 blocks across, and
        // the mounting around it is what sets the radius.
        t90m.turret_weak_spot = new WeakSpot();
        t90m.turret_weak_spot.thickness = 400.0F;
        t90m.turret_weak_spot.multiplier = 8.0F;
        t90m.turret_weak_spot.radius = 0.35F;
        t90m.turret_weak_spot.offset_x = -0.044F;
        t90m.turret_weak_spot.offset_y = 0.033F;

        // The glacis is the upper half of the front face and the lower plate the lower half. This is
        // the one number in the block that is a choice rather than a reading: the hull is a single
        // cube in the structure model, so there are no sloped plates in it for a tool to find the
        // boundary between, and nothing about the tank can be measured to place the line. It is
        // where the design puts it.
        //
        // Note the encoding, which the T-72B3M below still uses: the boundary is a normalised height
        // with +1 at the top of the hull box, so "the upper two thirds" is a negative number and not
        // a positive one - the two spell the same magnitude with opposite meanings. Written the
        // wrong way round the thin lower plate ends up covering the bottom two thirds instead, and
        // nothing about the table says so: both rows print a plausible thickness, and only the
        // coverage figure gives it away.
        t90m.hull_front_split = new FrontSplit();
        t90m.hull_front_split.split = 0.0F;
        t90m.hull_front_split.upper = new Plate(700.0F, 10.0F);
        t90m.hull_front_split.lower = new Plate(150.0F, 10.0F);

        // The rest of the flank - what the reactive blocks above do not cover, which on the 2022 is
        // the rear 40% of it - is this slat screen, and it runs the whole length rather than some
        // forward part of it, so the fraction is the whole side. It is the blocks, not this, that
        // express the two variants' different coverage; anywhere a block sits, the block is what a
        // hit is measured against.
        t90m.hull_side_era_front_fraction = 1.0F;

        // And the screen only counts when the round arrives obliquely, which is the whole reason a
        // screen with gaps in it is worth having: a jet crossing the slats is caught by them, one
        // arriving square on passes between them. Note which way this reads in play - it protects the
        // flank from a shot coming in at an angle and does nothing about one coming in flat, so the
        // way to use a shaped charge on this tank is to come at it side on. The reactive blocks are
        // not held to this: a block is a solid box with no gaps in it, so it counts at any angle,
        // and only the screen left behind after they are gone has the angle rule.
        t90m.hull_side_era_min_angle = 30.0F;

        // The add-on armour. Each box is its own part on the model, so each gets its own number
        // rather than the turret's face being raised to meet it - which would also raise the armour
        // of every gap between the boxes, and leave nowhere to say what a box is worth to a
        // different kind of round.
        ArmorConfig.ArmorOverride t90mChemical = new ArmorConfig.ArmorOverride();
        t90mChemical.turret = new ArmorSet(650.0F, 250.0F, 110.0F, 40.0F, 50.0F);
        t90mChemical.hull = new ArmorSet(700.0F, 80.0F, 60.0F, 40.0F, 40.0F);
        t90mChemical.damage_multiplier = new DamageMultiplier(8.0F, 4.0F, 4.0F, 4.0F, 4.0F);
        t90mChemical.turret_weak_spot = new Plate(500.0F, 6.0F);
        t90mChemical.hull_front_split = new FrontSplitPlates();
        t90mChemical.hull_front_split.upper = new Plate(700.0F, 8.0F);
        t90mChemical.hull_front_split.lower = new Plate(200.0F, 8.0F);

        // The side screens against a shaped charge. Stated as what they take off the jet rather than
        // as how thick they are, because that is what a screen does: the steel is a few centimetres
        // and the disruption is worth several hundred millimetres. Absent from the kinetic table
        // entirely - a slat screen does not slow a dart down, and leaving it out says so.
        t90mChemical.hull_side_era = new Plate(30.0F, 4.0F);
        t90mChemical.hull_side_era.penetration_loss = 400.0F;

        for (String block : new String[]{"era_3", "era_4", "era_5"}) {
            if (!carries(turretParts, block)) {
                continue;
            }
            // Relikt: 300 HP against a dart and 600 against a shaped charge. Any hit that sets a
            // block off loses that much penetration and spends the block; the next round meets the
            // bare turret where it was. The figures are the panel's own worth - the turret side or
            // rear behind it is measured separately, with what is left.
            //
            // The same figures as the hull blocks, because it is the same blocks: `era_3` and `era_4`
            // run along the turret's flanks and `era_5` across its rear, and the armour under them is
            // the turret's side or rear, not its 500mm front. They were once rated far higher
            // (700/1100) on the reasoning that they sit on a turret rather than on a hull - which is
            // the one thing about them that does not matter: the figure is what a round has to beat
            // **at that spot**, and where the spot is decides what is underneath it, not which bone
            // the part hangs off. Note what identical numbers mean here: 300 over a hull's 80 side
            // is 220 of block, over the turret's 180 it is 120 of it.
            t90m.part_armor.put(block, new Plate(300.0F, 8.0F).reactiveHp(300.0F));
            t90mChemical.part_armor.put(block, new Plate(600.0F, 6.0F).reactiveHp(600.0F));
        }

        if (carries(turretParts, "additional_armor_rear")) {
            // The plate lying across the back of the turret. It used to be one of the five cubes of
            // `additional_armor`, which is one part unit and therefore one number - and the other
            // four cubes are the angled plates around the turret's front cheeks, so the rear plate
            // came out valued as a cheek no matter what was written. The pack patch
            // (`split_rear_armor.ps1`) moves it into a part of its own, which is what lets this line
            // exist at all: the armour system keys on the part a round hit, so a value is only
            // reachable once there is a part to key on.
            //
            // The same figures as the reactive blocks, and marked the same way - it is the rest of
            // the same package on the same turret. If it is in fact a plain plate rather than a
            // reactive one, the `era` key is the whole of what says otherwise: deleting it is the
            // whole of the change, and there is nothing else to unpick.
            t90m.part_armor.put("additional_armor_rear",
                    new Plate(300.0F, 8.0F).reactiveHp(300.0F));
            t90mChemical.part_armor.put("additional_armor_rear",
                    new Plate(600.0F, 6.0F).reactiveHp(600.0F));
        }
        if (carries(turretParts, "additional_armor")) {
            t90m.part_armor.put("additional_armor", new Plate(600.0F, 10.0F));
            t90mChemical.part_armor.put("additional_armor", new Plate(900.0F, 8.0F));
        }
        if (carries(turretParts, "additional_armor_top")) {
            // A slat screen over the roof: almost nothing to a dart, and a good deal to a jet from
            // above, which is the attack it exists to defeat.
            t90m.part_armor.put("additional_armor_top", new Plate(40.0F, 5.0F));
            t90mChemical.part_armor.put("additional_armor_top", new Plate(300.0F, 4.0F));
        }

        // The blocks along the flanks, which are the same Relikt as the turret's and are the one
        // thing that tells the two Proryv variants apart from the outside: measured off the model,
        // 2022 wears them over the forward 60% of the hull and 2024 over 93% of it. Both get the same
        // numbers - how far along the flank they reach is the model's business and needs no figure
        // here, which is the whole reason to key them by part instead of by a band along the side.
        //
        // Weaker than the turret's blocks because there is less behind them: these are thin boxes
        // along a hull side, not the thick Relikt array around a turret, and the figures follow the
        // same convention as everywhere else in this file - what a round has to beat **at that spot**,
        // the hull's own 80 included. So 300 kinetic is a couple of hundred of block over the side,
        // and 600 chemical against the chemical table's 80.
        for (String block : sideBlocks) {
            t90m.part_armor.put(block, new Plate(300.0F, 8.0F).reactiveHp(300.0F));
            t90mChemical.part_armor.put(block, new Plate(600.0F, 6.0F).reactiveHp(600.0F));
        }

        t90m.overrides.put("heat", t90mChemical);
        // Identical to the chemical table: this tank carries no high-explosive round of its own, and
        // the one thing a foreign one needs is to be measured against the chemical table rather than
        // against bare steel. Built through thinRoofs so the two cannot drift into being one object.
        t90m.overrides.put("he", thinRoofs(t90mChemical, 40.0F, 50.0F, 40.0F, 40.0F));

        t90m.turret_part_ids = turretParts;
        return t90m;
    }

    /**
     * The frontal armour of one Abrams generation, which is nearly the whole of what separates them.
     *
     * <p>Three plates, because the hull front is split into a glacis and a lower plate and both are
     * quoted for the chemical table as well: the six numbers here are the ones the depleted-uranium
     * arrays change. Everything else on the tank - sides, rear, roof, floor - is the same on all
     * four and lives in {@link #abrams} rather than here.
     */
    private record Frontal(float turretFront, float glacis, float lowerPlate,
                           float turretFrontHeat, float glacisHeat, float lowerPlateHeat) {
    }

    /**
     * The M1A1 as the design gives it: composite armour of the first generation, no depleted
     * uranium. The baseline the other three are measured against.
     */
    private static Frontal m1a1Frontal() {
        return new Frontal(600.0F, 800.0F, 400.0F, 1000.0F, 1000.0F, 600.0F);
    }

    /**
     * The M1A2, which adds depleted-uranium rods to the composite array - the step the sources call
     * "intermediate protection": the same armour, made denser.
     */
    private static Frontal m1a2Frontal() {
        return new Frontal(800.0F, 1000.0F, 500.0F, 1250.0F, 1250.0F, 750.0F);
    }

    /**
     * The M1A2 SEP, with third-generation depleted uranium and an advanced composite array.
     *
     * <p>940mm against kinetic energy is the published estimate for the Abrams' frontal arc (940 to
     * 960mm KE, and 1,320 to 1,620mm against shaped charges) - the low end of it, because the arc
     * includes angles the turret cheek is stronger than. The chemical figure sits inside that band.
     * The A2 is placed midway between it and the M1A1 rather than at a number of its own, since no
     * source gives the A2 separately and the sources describe the three as standard / intermediate /
     * advanced.
     */
    private static Frontal sepFrontal() {
        return new Frontal(940.0F, 1150.0F, 600.0F, 1500.0F, 1500.0F, 900.0F);
    }

    /**
     * VVP T-72B3M, the T-72B brought up to the 2010s standard: the same 2A46M-5 gun as the T-90M, a
     * Sosna-U sight, and Relikt explosive reactive armour over a hull and turret that are otherwise
     * a generation older.
     *
     * <p>**These numbers are a draft.** Like the T-90M's, the design gives no table for this tank,
     * so they are placed a little below the T-90M's - the same gun and add-on armour over an older
     * base - and are meant to be corrected in game with the armour scanner.
     *
     * <p>{@code turret_weak_spot} and {@code track_ends} are measured off the model; the glacis
     * split is the same assumption the T-90M uses, since this hull has the same shape.
     */
    private static void addT72B3M(Profile p) {
        VehicleArmor t72 = new VehicleArmor();
        t72.turret = new ArmorSet(480.0F, 160.0F, 80.0F, 40.0F, 40.0F);
        t72.hull = new ArmorSet(480.0F, 80.0F, 60.0F, 40.0F, 40.0F);
        t72.damage_multiplier = new DamageMultiplier(10.0F, 5.0F, 5.0F, 5.0F, 5.0F);

        // Measured: the barrel leaves the turret box a sixteenth of a block left of centre and
        // level with it - closer to centred than the T-90M's, and for the same reason (the 2A46
        // barrel leaves through the middle of the front plate whatever carries it).
        t72.turret_weak_spot = new WeakSpot();
        t72.turret_weak_spot.thickness = 380.0F;
        t72.turret_weak_spot.multiplier = 8.0F;
        t72.turret_weak_spot.radius = 0.35F;
        t72.turret_weak_spot.offset_x = -0.062F;
        t72.turret_weak_spot.offset_y = 0.005F;

        // The front face is split in half, as the T-90M's is: the same hull shape, drawn the same
        // way. Note the encoding for the neighbours that still need it - the boundary is a
        // normalised height with +1 at the top of the hull box, so "the upper two thirds" would be
        // a **negative** number and not a positive one.
        t72.hull_front_split = new FrontSplit();
        t72.hull_front_split.split = 0.0F;
        t72.hull_front_split.upper = new Plate(480.0F, 10.0F);
        t72.hull_front_split.lower = new Plate(140.0F, 10.0F);

        // Relikt along the flanks, as on the T-90M and for the same reasons - light screens of the
        // right kind would go in a part_armor box, but this model has no separate parts for them,
        // so the flank is a band and the only thing it can express is "past an angle or not at all".
        t72.hull_side_era_front_fraction = 1.0F;
        t72.hull_side_era_min_angle = 30.0F;

        ArmorOverride t72Chemical = new ArmorOverride();
        t72Chemical.turret = new ArmorSet(600.0F, 220.0F, 100.0F, 40.0F, 40.0F);
        t72Chemical.hull = new ArmorSet(620.0F, 80.0F, 60.0F, 40.0F, 40.0F);
        t72Chemical.damage_multiplier = new DamageMultiplier(8.0F, 4.0F, 4.0F, 4.0F, 4.0F);
        t72Chemical.turret_weak_spot = new Plate(450.0F, 6.0F);
        t72Chemical.hull_front_split = new FrontSplitPlates();
        t72Chemical.hull_front_split.upper = new Plate(620.0F, 8.0F);
        t72Chemical.hull_front_split.lower = new Plate(180.0F, 8.0F);
        t72Chemical.hull_side_era = new Plate(30.0F, 4.0F);
        t72Chemical.hull_side_era.penetration_loss = 400.0F;

        t72.overrides.put("heat", t72Chemical);
        // Identical to the chemical table, through thinRoofs so the two cannot become one object.
        t72.overrides.put("he", thinRoofs(t72Chemical, 40.0F, 40.0F, 40.0F, 40.0F));

        // Measured. This turret carries only its own machine gun and the smoke launchers - no
        // add-on boxes, so nothing here has a value of its own.
        t72.turret_part_ids = new String[]{
                "turret",
                "turret_machine_gun",
                "turret_smoke_grenade",
                "commander_machine_gun"
        };

        p.vehicles.put("vvp_ywzj:t72_b3m", t72);
    }

    /**
     * The M1 family: the four Abrams the pack ships.
     *
     * <p>All four are the same tank as far as the *geometry* goes. VehicleScaffold measures their
     * hull and turret boxes to the same numbers and their barrels to the same offsets, so the shape
     * of the armour is written once and the variants differ in two things only: what has been bolted
     * on, and how good the composite array inside is.
     *
     * <p>That second difference is real and is not a shape. The sources are explicit that the turret
     * armour's *thickness* was increased only twice in the whole programme - on the M1A1 in 1985 and
     * again on the SEPv3 in 2020 - so an M1A2 and an M1A2 SEP are the same thickness of a better
     * material. They are harder without being bigger, which is exactly what the three {@link Frontal}
     * constants express.
     */
    private static void addAbrams(Profile p) {
        // Everything bolted to the turret, so a hit on it is measured against turret armour rather
        // than the hull's. The machine-gun mounts and the hatches stick out far enough to be struck
        // in their own right; the seats and their locator cubes do not.
        String[] turret = {
                "turret",
                "turret_machine_gun",
                "turret_smoke_grenade",
                "turret_mg2",
                "turret_mg3",
                "hatch_1",
                "hatch_2"
        };
        p.vehicles.put("vvp_ywzj:m1a1", abrams(turret, new String[0], m1a1Frontal()));
        // The A2 is the M1A1 with a better fire-control suite, which this addon does not model, and
        // the second-generation armour, which it does.
        p.vehicles.put("vvp_ywzj:m1a2", abrams(turret, new String[0], m1a2Frontal()));
        // The Ukrainian M1A1 SA, which is an M1A1 with ARAT tiles across the hull's flanks. Tiles
        // on the hull are hull parts on the model, so they stay out of the turret list: a hit on
        // one that has been shot away must fall through to the hull behind it, not the turret.
        //
        // Its armour is the M1A1's: the SA rebuild added sights and a thermal sight, not a new
        // array. (The US did supply it DU ammunition - see the KEW round's note for what this mod
        // gives it instead.)
        p.vehicles.put("vvp_ywzj:m1a1_ukr", abrams(turret,
                new String[]{"era_1", "era_2"}, m1a1Frontal()));
        // The SEP, which adds tiles to the turret's flanks and a second heavy machine gun on the
        // roof - the one variant whose part list is not the M1A1's - over the third-generation
        // armour.
        p.vehicles.put("vvp_ywzj:m1a2_sep", abrams(new String[]{
                "turret",
                "turret_machine_gun",
                "turret_smoke_grenade",
                "turret_mg2",
                "turret_mg3",
                "turret_mg4",
                "era_3",
                "era_4",
                "hatch_1",
                "hatch_2"
        }, new String[]{"era_1", "era_2", "era_3", "era_4"}, sepFrontal()));
    }

    /**
     * One Abrams, with whatever reactive tiles that variant carries.
     *
     * @param turretParts every part the model parents to the turret, from VehicleScaffold
     * @param tiles       the reactive tile parts this variant wears, hull ones included - the
     *                    values are the same either way, and which face a spent tile falls through
     *                    to is decided by {@code turretParts}, not by this list
     * @param frontal     this generation's frontal armour, which is the only armour that differs
     */
    private static VehicleArmor abrams(String[] turretParts, String[] tiles, Frontal frontal) {
        VehicleArmor abrams = new VehicleArmor();
        abrams.turret = new ArmorSet(frontal.turretFront(), 250.0F, 200.0F, 100.0F, 100.0F);
        // The hull's own front value is dead while the split is enabled - a hit on the front face
        // is matched against the glacis or the lower plate - but it is kept equal to the glacis
        // rather than left at a third number, so turning the split off falls back to something
        // that is still right for this generation.
        abrams.hull = new ArmorSet(frontal.glacis(), 100.0F, 90.0F, 100.0F, 100.0F);

        // The gun mount circle. The barrel is 3.68/16 blocks across and leaves the turret front
        // slightly below and inboard of its centre - the two offsets are the barrel's axis
        // measured against the front plate's centre, both about a tenth of a block, where the
        // T-64's barrel is centred to within the same tenth and needs no offset at all.
        abrams.turret_weak_spot = new WeakSpot();
        abrams.turret_weak_spot.thickness = 350.0F;
        abrams.turret_weak_spot.multiplier = 10.0F;
        abrams.turret_weak_spot.offset_x = -0.10F;
        abrams.turret_weak_spot.offset_y = -0.10F;

        // Track strips: the running gear's locator cubes sit at |X| = 1.75..1.88 against a hull
        // half-width of 2.21, so the inner edge lands near 0.69 once the tread is allowed for.
        abrams.track_ends = new TrackEnds();
        abrams.track_ends.inner_edge = 0.69F;

        // The lower front plate is the big one on this tank - it carries the composite array, while
        // the glacis above it is a shallow steel plate - so it takes the bottom seven tenths of the
        // front face and the glacis only the top three. That is the opposite of an even split, and
        // of the T-64's.
        //
        // Neither the collision box nor the visual model can supply this: both give the hull a flat
        // vertical front, with no sloped plates to measure. So this one number is where the real
        // proportions are stated, and it is the only line to move if they are off. Note that the
        // first two figures shipped here were both wrong, so the upgrade carries them forward too.
        abrams.hull_front_split = new FrontSplit();
        abrams.hull_front_split.split = 0.4F;
        abrams.hull_front_split.upper = new Plate(frontal.glacis(), 50.0F);
        abrams.hull_front_split.lower = new Plate(frontal.lowerPlate(), 10.0F);

        ArmorOverride abramsChemical = new ArmorOverride();
        abramsChemical.turret = new ArmorSet(frontal.turretFrontHeat(), 400.0F, 200.0F, 100.0F, 100.0F);
        abramsChemical.hull = new ArmorSet(frontal.glacisHeat(), 200.0F, 200.0F, 100.0F, 100.0F);
        abramsChemical.damage_multiplier = new DamageMultiplier(45.0F, 4.0F, 4.0F, 4.0F, 4.0F);
        abramsChemical.turret_weak_spot = new Plate(500.0F, 8.0F);
        abramsChemical.hull_front_split = new FrontSplitPlates();
        abramsChemical.hull_front_split.upper = new Plate(frontal.glacisHeat(), 45.0F);
        abramsChemical.hull_front_split.lower = new Plate(frontal.lowerPlateHeat(), 8.0F);
        abramsChemical.track_ends = new Plate(300.0F, 2.0F);
        // Reactive tiles, on the variants that wear them and not on the ones that do not. ARAT is
        // an RPG-defeating tile, not a tank-round stopper, and 140/290 (dart) or 600/800 (shaped
        // charge) is what the panel itself takes off a hit. The tile is spent doing it; the armour
        // behind - the hull's 100/200 or the turret's 250/400 - is measured with the rest.
        //
        // Valued per part rather than as a band across the flank: these really are separate parts on
        // the model, with ids, so the tile covers exactly the boxes it is drawn on and nothing has to
        // be guessed about its height. (The T-90M's hull blocks were a band for a while, for want of
        // anything better, and became parts for the same reason.)
        for (String tile : tiles) {
            boolean onTurret = carries(turretParts, tile);
            abrams.part_armor.put(tile,
                    new Plate(onTurret ? 290.0F : 140.0F, 5.0F)
                            .reactiveHp(onTurret ? 290.0F : 140.0F));
            abramsChemical.part_armor.put(tile,
                    new Plate(onTurret ? 800.0F : 600.0F, 4.0F)
                            .reactiveHp(onTurret ? 800.0F : 600.0F));
        }
        // No hull_side_era: whatever tiles an Abrams wears are parts, and the rest of the flank is
        // one plate. Leaving the field absent is what says so.
        abrams.overrides.put("heat", abramsChemical);
        // The turret floor was not among the plates named for the HE round, so it keeps the
        // chemical value alongside them rather than being thinned with the three that were.
        abrams.overrides.put("he", thinRoofs(abramsChemical, 60.0F, 60.0F, 60.0F, 60.0F));

        abrams.turret_part_ids = turretParts;
        return abrams;
    }

    /**
     * VVP BTR-3, an eight-wheeled amphibious carrier whose armour is thin aluminium rather than
     * steel - a third of the T-64's on the flanks, and barely a fifth of it on the roof.
     *
     * <p>Three things about this one are unlike the two tanks:
     * <ul>
     *   <li>Every plate in a group carries the same multiplier, so the vehicle states its own
     *       {@code damage_multiplier} instead of leaning on the global one, whose front value of
     *       x50 belongs to a tank's glacis.</li>
     *   <li>It rides on wheels, which get a zone of their own. See {@link RoadWheels}.</li>
     *   <li>The glacis and the lower plate are the same thickness, so there is no split to make -
     *       the whole front face is one plate. The same goes for the gun mount, which is given the
     *       turret front's own numbers rather than a weak spot's.</li>
     * </ul>
     */
    private static void addBtr3(Profile p) {
        VehicleArmor btr = new VehicleArmor();
        btr.turret = new ArmorSet(32.0F, 16.0F, 16.0F, 8.0F, 8.0F);
        btr.hull = new ArmorSet(32.0F, 16.0F, 16.0F, 8.0F, 8.0F);
        // Equally lethal through any of its faces: nothing here is thick enough for the plate that
        // was pierced to say much about how much energy the round has left.
        btr.damage_multiplier = new DamageMultiplier(5.0F, 5.0F, 5.0F, 5.0F, 5.0F);

        // The wheels, measured off btr_3.structure.json rather than guessed. The wheel boxes have
        // not moved (y -1.030 .. +0.042 in the old hull frame), but the **hull box has**: the 0.6.1
        // pack cut its floor up from the ground to y +0.72 blocks, so the hull now stands above the
        // wheels instead of enclosing them. Normalised against the new box the wheel tops come out
        // at -0.283, below the hull's centre line, which is what the derived figure says.
        btr.road_wheels = new RoadWheels();
        btr.road_wheels.top = -0.283F;

        ArmorOverride btrChemical = new ArmorOverride();
        btrChemical.turret = new ArmorSet(32.0F, 16.0F, 16.0F, 8.0F, 8.0F);
        btrChemical.hull = new ArmorSet(32.0F, 16.0F, 16.0F, 8.0F, 8.0F);
        btrChemical.damage_multiplier = new DamageMultiplier(4.0F, 4.0F, 4.0F, 4.0F, 4.0F);
        btrChemical.road_wheels = new Plate(30.0F, 2.0F);
        btr.overrides.put("heat", btrChemical);
        // The HE table is the HEAT one exactly - every plate, the roofs included, was given the same
        // numbers. Built through thinRoofs rather than by pointing 'he' at the same object, so that
        // editing one of them later cannot silently edit the other.
        btr.overrides.put("he", thinRoofs(btrChemical, 8.0F, 8.0F, 8.0F, 8.0F));

        // Everything bolted to the small turret. turret_missile is the Barrier launcher, which sits
        // on the turret roof and is struck in its own right.
        btr.turret_part_ids = new String[]{
                "turret",
                "turret_machine_gun",
                "turret_missile",
                "turret_smoke_grenade"
        };

        p.vehicles.put("vvp_ywzj:btr_3", btr);
    }

    /**
     * VVP M1296 Stryker Dragoon - an eight-wheeled carrier with a 30mm turret, and the second
     * wheeled vehicle here.
     *
     * <p>**These numbers are a draft**, like the T-90M's and the T-72B3M's: the design gives no table
     * for this one. Where they come from is one published fact and one piece of arithmetic - the
     * vehicle is quoted as protecting against 14.5x114mm all round with its bolt-on ceramic armour,
     * and 14.5mm API goes through about 32 to 40mm of rolled steel at battle ranges, so "protected
     * against 14.5mm" means the armour is worth something over 35mm everywhere. The front is given a
     * little more than the flanks because that is where the add-on package is thickest, and the roof
     * and floor less because neither is what that armour is for. Against shaped charges the ceramic
     * is worth far more than against a dart, which is the one place these figures diverge sharply
     * from the kinetic ones.
     *
     * <p>It is built like the BTR-3 rather than like the tanks: flat multipliers on every face
     * (nothing here is thick enough for the pierced plate to say much about the energy left over),
     * a wheel zone, and no glacis split or gun-mount circle. The gun mount is worth a word: the
     * worksheet measures where this one's barrel is, and it is left unconfigured on purpose, for the
     * reason the BTR-3's is - a 30mm turret's front is thin all over, so a circle that says "here it
     * is 40 instead of 45" would be modelling a difference the real vehicle does not have.
     */
    private static void addStryker(Profile p) {
        VehicleArmor stryker = new VehicleArmor();
        // Front, side, rear, roof, floor. The first three are the all-round 14.5mm figure, the front
        // a step above it; the roof and floor are the hull's own steel.
        stryker.turret = new ArmorSet(45.0F, 35.0F, 35.0F, 15.0F, 20.0F);
        stryker.hull = new ArmorSet(45.0F, 35.0F, 35.0F, 15.0F, 20.0F);
        // Equally lethal through any face: at these thicknesses almost everything that hits it gets
        // in, so where it hit does not say much. The same choice the BTR-3 makes, and for the same
        // reason - the global front multiplier of x50 belongs to a tank's glacis.
        stryker.damage_multiplier = new DamageMultiplier(5.0F, 5.0F, 5.0F, 5.0F, 5.0F);

        // The wheels, measured: the wheel boxes reach y +0.017 in the **old** hull's normalised
        // frame, and -0.318 in the new one, because the 0.6.1 pack cut the hull box's floor up to
        // y +0.72 blocks. The wheels themselves are untouched at y 1.47 .. 21.57 px; only the box
        // they are measured against moved, which is why this is a different number rather than a
        // different vehicle. Same shape as the BTR-3's, and for the same reason (see RoadWheels).
        stryker.road_wheels = new RoadWheels();
        stryker.road_wheels.top = -0.318F;

        ArmorOverride strykerChemical = new ArmorOverride();
        strykerChemical.turret = new ArmorSet(250.0F, 180.0F, 180.0F, 45.0F, 70.0F);
        strykerChemical.hull = new ArmorSet(250.0F, 180.0F, 180.0F, 45.0F, 70.0F);
        strykerChemical.damage_multiplier = new DamageMultiplier(4.0F, 4.0F, 4.0F, 4.0F, 4.0F);
        // A wheel is a tyre and a rim, not armour - worth something to a jet and almost nothing to
        // a dart, and the same pair the BTR-3's wheels carry.
        strykerChemical.road_wheels = new Plate(30.0F, 2.0F);
        stryker.overrides.put("heat", strykerChemical);
        // The HE table is the chemical one: every plate here was given the same numbers for both.
        // Built through thinRoofs rather than by pointing 'he' at the same object, so that editing
        // one of them later cannot silently edit the other.
        stryker.overrides.put("he", thinRoofs(strykerChemical, 45.0F, 70.0F, 45.0F, 70.0F));

        // The turret's parts: the gun mount, the coaxial M240 and the smoke launchers. Measured off
        // the bone tree - the seats are excluded by name, and there is no commander's machine gun
        // (the M240 is a secondary on the turret_mg part, not a part of its own).
        stryker.turret_part_ids = new String[]{
                "turret",
                "turret_mg",
                "turret_smoke_grenade"
        };

        p.vehicles.put("vvp_ywzj:stryker_m1296", stryker);

        // The XM813's high-explosive round. This weapon id is the vehicle's own - the BTR-3's ZTM-1
        // is a different gun - so it needs an entry of its own, and it gets the treatment the
        // BTR-3's 30mm high-explosive round gets: a few millimetres of penetration, so that it never
        // reaches the thinnest plate configured here and its damage stays what the pack already
        // gives it.
        // Its two belts are arranged the same way the BTR-3's are, and for the same reason: the
        // XM813 is a dual-feed cannon, both feeds hold both rounds, and the belt a gunner arrives on
        // is the one carrying the dart. The grouping itself is in the pack - see $belts in
        // add_shells.ps1 - so neither of these entries needs a load list.
        Ammo xm813 = new Ammo(6.0F);
        xm813.profile = "he";
        p.ammo_penetration.put("vvp_ywzj:xm813_30mm",
                shelf(xm813, AmmoCategory.AUTOCANNON_HE));

        // The Dragoon's dart, and the round the vehicle exists for: an XM813 with nothing but high
        // explosive is a Stryker that cannot fight another armoured vehicle at all.
        //
        // It is a round in the **pack**, not a second number here: `add_shells.ps1` writes
        // `data/vvp_ywzj/weapons/xm813_30mm_apfsds.json` and puts its id into both of this turret's
        // belts, because nothing but the vehicle's own file can attach a weapon to it - and it is
        // the multi-weapon key that picks it up in game, one of the two rounds either belt can fire.
        //
        // 116mm is the published maximum for the MK258 Mod 1, the 30x173 APFSDS-T this gun fires:
        // 116mm of rolled steel at the muzzle, 104mm at a kilometre, from a 1,430 m/s projectile at
        // 200 rounds a minute. The addon's figure is one number at the muzzle, the shape every round
        // here has. Against this config's own 45mm front it goes through the vehicle that carries it
        // - which is the honest answer for a 30mm dart meeting a 30mm-class hull, and the reason the
        // figure was not trimmed to make the Dragoon proof against itself.
        p.ammo_penetration.put("vvp_ywzj:xm813_30mm_apfsds",
                shelf(new Ammo(116.0F), AmmoCategory.AUTOCANNON_AP));
        // Its coaxial M240 is the same weapon id the Abrams carries, so the existing entry covers it
        // - 7.62mm, 15mm of penetration and a flat x3.
    }

    /**
     * Tank Assault's Panther G - a late-war medium, and the first vehicle here from a second pack.
     *
     * <p>The geometry is measured off {@code panther.structure.json} the same way the others were:
     * the turret's three part units are the ones the model parents to the turret bone (its seats are
     * parented there too and are excluded by name), and the track's inner face sits at 0.59 of the
     * hull's half-width. The barrel is within a thousandth of a block of the turret box's centre, so
     * the gun shield's circle needs no offset.
     *
     * <p>What is not measured, and cannot be, is where the glacis ends: {@link FrontSplit#split} is
     * read off the drawing, not the collision box, and the Panther's sloped front plate covers most
     * of its face - hence {@code -0.3} against the T-64's {@code 0.0}.
     */
    private static void addPanther(Profile p) {
        VehicleArmor panther = new VehicleArmor();
        // Real plate thicknesses, not line-of-sight figures. The Panther is the first vehicle here
        // to state both halves: the numbers below are what the steel is, and the two slope blocks
        // are how much the plates lean, so the engine can work out what a round arriving from any
        // direction actually has to cross. 80mm at 55 degrees is 139mm from dead ahead and 197mm
        // from 45 degrees off the nose - the two figures the sources quote for this glacis.
        panther.turret = new ArmorSet(110.0F, 45.0F, 45.0F, 16.0F, 16.0F);
        panther.hull = new ArmorSet(80.0F, 50.0F, 40.0F, 16.0F, 16.0F);
        panther.hull_slopes = new PlateSlopes(55.0F, 30.0F, 30.0F);
        panther.turret_slopes = new PlateSlopes(11.0F, 25.0F, 25.0F);
        panther.damage_multiplier = new DamageMultiplier(10.0F, 5.0F, 5.0F, 5.0F, 5.0F);

        // The gun shield: a 100mm cast mantlet covering the opening the barrel passes through.
        // Unlike the T-64's and the Abrams', this is not a gap - it is nearly as thick as the
        // 110mm plate beside it, and the circle is measured on its own because it is a different
        // piece of steel, not because it is a hole.
        panther.turret_weak_spot = new WeakSpot();
        panther.turret_weak_spot.thickness = 100.0F;
        panther.turret_weak_spot.multiplier = 10.0F;

        // Half and half: the glacis takes the top half of the front face and the lower plate the
        // bottom half. Both lean 55 degrees - the slope above covers the split - so the real
        // plates are the two numbers here, 80mm above and 60mm below. Where the line falls decides
        // what a hit meets, and the machine-gun port is a third answer inside the glacis.
        panther.hull_front_split = new FrontSplit();
        panther.hull_front_split.split = 0.0F;
        panther.hull_front_split.upper = new Plate(80.0F, 10.0F);
        panther.hull_front_split.lower = new Plate(60.0F, 10.0F);

        // The bow machine gun's ball mount, a hole through the glacis. Measured off the model: the
        // MG barrel sits 0.604 blocks to one side of the hull box's centre and 0.700 blocks above
        // it, which puts the port in the upper half of the plate. The radius is the one judgement
        // here - 0.6 blocks across is about the size of the mounting - and the armour scanner is
        // the way to check it in game.
        panther.hull_weak_spot = new WeakSpot();
        panther.hull_weak_spot.thickness = 80.0F;
        panther.hull_weak_spot.multiplier = 10.0F;
        panther.hull_weak_spot.radius = 0.3F;
        panther.hull_weak_spot.offset_x = 0.604F;
        panther.hull_weak_spot.offset_y = 0.700F;

        // inner_edge is the measured inner face itself, not a value with margin below it. On the
        // shipped tracked vehicles the track is a row of tiny locator cubes and the threshold is
        // picked by eye; here the track is one box per side, and it matters which side of the line
        // the hull falls on: the track is 200mm and the flank beside it is 50mm.
        panther.track_ends = new TrackEnds();
        panther.track_ends.thickness = 50.0F;
        panther.track_ends.multiplier = 1.5F;
        panther.track_ends.inner_edge = 0.589F;

        ArmorOverride pantherChemical = new ArmorOverride();
        pantherChemical.turret = new ArmorSet(110.0F, 45.0F, 45.0F, 16.0F, 16.0F);
        pantherChemical.hull = new ArmorSet(80.0F, 50.0F, 40.0F, 16.0F, 16.0F);
        pantherChemical.damage_multiplier = new DamageMultiplier(4.0F, 4.0F, 4.0F, 4.0F, 4.0F);
        pantherChemical.turret_weak_spot = new Plate(100.0F, 8.0F);
        // Same thickness as the kinetic table, with the multiplier its glacis uses - the port is
        // thinner than the plate around it either way, and which warhead hit it changes only how
        // lethal getting through is.
        pantherChemical.hull_weak_spot = new Plate(80.0F, 8.0F);
        pantherChemical.hull_front_split = new FrontSplitPlates();
        pantherChemical.hull_front_split.upper = new Plate(80.0F, 8.0F);
        pantherChemical.hull_front_split.lower = new Plate(60.0F, 8.0F);
        pantherChemical.track_ends = new Plate(50.0F, 2.0F);
        panther.overrides.put("heat", pantherChemical);
        // Every plate the HE round meets is the plate the HEAT round meets, at the same number, so
        // the copy comes out identical. Built through thinRoofs rather than by pointing 'he' at the
        // same object, as on the BTR-3: editing one later must not silently edit the other.
        panther.overrides.put("he", thinRoofs(pantherChemical, 16.0F, 16.0F, 16.0F, 16.0F));

        // The two machine-gun mountings and the smoke launcher the model parents to the turret; its
        // gunner's and commander's seats are parented there as well, and are not armour.
        panther.turret_part_ids = new String[]{
                "turret",
                "turret_machine_gun",
                "turret_smoke_grenade"
        };

        panther.engine_horsepower = 700.0F;
        panther.horsepower_per_ton = 15.6F;
        p.vehicles.put("tank_assault:panther", panther);
    }

    /**
     * The Tiger E, the early-production Tiger with the drum magazine (so its d.h. reaches the floor of
     * the hull at the crew compartment). Front armour is **100mm plate** throughout - the doubled
     * 100/80 face is the late-Tiger F's silhouette, not this one's.
     *
     * <p>The plates, as the steel they are: the driver's plate 100mm at 9 degrees from vertical,
     * the lower nose 100mm at 25, the flanks 80 vertical and the rear 80 at 9; the turret front
     * 100 at 0 with a 120mm mantlet, its sides and rear 80, and every roof and floor 25. The
     * 60mm/80-degree strip some tables call the frontal step is not modelled on its own - the
     * pack's hull box has one front face - so the upper plate is the 100mm one.
     *
     * <p>The weak spot is the gun mount, a hole in the upper front of the turret through which the
     * 88mm barrel passes; offsets measured off the model. The 120mm mantlet covers it, so the
     * circle is where the plate is replaced by the casting rather than where it is thinner. The
     * hull weak spot the model carries is the bow machine gun's mount, but the hull has no
     * machine-gun port on this version - the Tiger E's bow MG was a ball mount that the model does
     * not draw as a barrel - so it is left to fire at a blank plate.
     */
    private static void addTigerE(Profile p) {
        VehicleArmor tiger = new VehicleArmor();
        tiger.turret = new ArmorSet(100.0F, 80.0F, 80.0F, 25.0F, 25.0F);
        tiger.hull = new ArmorSet(100.0F, 80.0F, 80.0F, 25.0F, 25.0F);
        tiger.damage_multiplier = new DamageMultiplier(10.0F, 5.0F, 5.0F, 5.0F, 5.0F);

        // The gun mount - the gun barrel passes through here, so the plate is what protects the crew
        // and that is thinner than the rest of the turret front. Measured off the model.
        tiger.turret_weak_spot = new WeakSpot();
        // The mantlet, 120mm of cast steel over the opening behind it. Thicker than the 100mm
        // plate around it - the circle says "this is a different piece", not "this is a hole".
        tiger.turret_weak_spot.thickness = 120.0F;
        tiger.turret_weak_spot.multiplier = 10.0F;
        // 0.08 blocks off centre, 0.07 above; the model has the barrel almost dead-centre.
        // 0.6 across covers the hole.
        tiger.turret_weak_spot.radius = 0.3F;
        tiger.turret_weak_spot.offset_x = -0.08F;
        tiger.turret_weak_spot.offset_y = 0.066F;

        // Glacis = upper, 60mm; lower plate = 100mm vertical. The split sits at 0.0 because the
        // hull box is one cube and the visual model has no sloped face to measure - the
        // installed geometry's 22° on the lower half is what `composite seat modules` see, not
        // what this addon measures. 0.0 means "hit goes by which half of the cube", which is
        // the only answer available.
        tiger.hull_front_split = new FrontSplit();
        tiger.hull_front_split.split = 0.0F;
        tiger.hull_front_split.upper = new Plate(100.0F, 10.0F);
        tiger.hull_front_split.lower = new Plate(100.0F, 10.0F);
        // The two front plates are separate pieces and lean differently: the driver's plate 9
        // degrees from vertical, the lower nose 25. State them apart; each is the steel, and the
        // engine turns it into 101mm and 110mm of line-of-sight thickness. The rear plate leans 9.
        tiger.hull_slopes = new PlateSlopes(0.0F, 0.0F, 9.0F);
        tiger.hull_front_split.upper_slope = 9.0F;
        tiger.hull_front_split.lower_slope = 25.0F;
        // Keep hull.front equal to the driver's plate so turning the split off falls back to the
        // 100mm upper plate rather than anything else.
        tiger.hull.front = 100.0F;

        // Tracks: same as the Panther's, one box per side. Measured off the model: inner face 0.59
        // blocks from the hull's centre, so the threshold is "broad enough to catch the whole row".
        tiger.track_ends = new TrackEnds();
        tiger.track_ends.thickness = 100.0F;
        tiger.track_ends.multiplier = 1.5F;
        tiger.track_ends.inner_edge = 0.591F;

        // Heat table. HEAT vs flat plate differs only by multiplier: 88mm HEAT against 80mm plate is
        // enough residual to penetrate, and against 100mm glacis is right on the edge. The turret
        // roof changes for HEAT-FS - 25mm of homogenous plate is more than the jet scatters through
        // any arity-relevant angle - but the addon is happy with shorter roofs, so the kinetic numbers
        // are kept.
        ArmorOverride tigerChemical = new ArmorOverride();
        tigerChemical.turret = new ArmorSet(100.0F, 80.0F, 80.0F, 25.0F, 25.0F);
        tigerChemical.hull = new ArmorSet(100.0F, 80.0F, 80.0F, 25.0F, 25.0F);
        tigerChemical.damage_multiplier = new DamageMultiplier(4.0F, 4.0F, 4.0F, 4.0F, 4.0F);
        tigerChemical.turret_weak_spot = new Plate(120.0F, 8.0F);
        tigerChemical.hull_front_split = new FrontSplitPlates();
        tigerChemical.hull_front_split.upper = new Plate(100.0F, 8.0F);
        tigerChemical.hull_front_split.lower = new Plate(100.0F, 8.0F);
        tigerChemical.track_ends = new Plate(60.0F, 2.0F);
        tiger.overrides.put("heat", tigerChemical);
        tiger.overrides.put("he", thinRoofs(tigerChemical, 25.0F, 25.0F, 25.0F, 25.0F));

        // The model parents the coaxial MG and smoke launcher to the turret, and no other part unit
        // of any kind. Same shape as the Panther's turret parts.
        tiger.turret_part_ids = new String[]{
                "turret",
                "turret_machine_gun",
                "turret_smoke_grenade"
        };

        tiger.engine_horsepower = 650.0F;
        tiger.horsepower_per_ton = 11.4F;
        p.vehicles.put("tank_assault:tiger", tiger);
    }

    /**
     * VT-5 (Armor Pioneer) - a 33 tonne export light tank, and the first vehicle here from a third
     * pack.
     *
     * <p>**These numbers are a draft**, in the same sense as the Stryker's: the design gives no
     * table, and the published material describes a class rather than a figure. What it does say is
     * consistent enough to anchor the table. The hull and turret are welded steel, which protects
     * against small arms and splinters all round; over the frontal arc the baseline vehicle is
     * expected to resist heavy machine-gun fire and 30mm APDS, and the Contemporary Ships article
     * quoted on the War Thunder forum puts the requirement at old 100mm full-calibre AP (BR-412D)
     * as well - a round worth roughly 150-200mm at battle ranges. That is the 240mm turret face and
     * 200mm glacis: enough for the threats it was built against, nowhere near a modern dart.
     *
     * <p>The modular package is what moves the chemical table. The in-service vehicles carry ERA on
     * the hull front and the turret front and sides, and the turret sides are also covered by cage
     * armour; against a shaped charge those are worth far more than the steel under them. The
     * kinetic table is left alone because neither ERA nor a cage changes what a dart does.
     *
     * <p>The rear, roof and floor are the hull's own steel. The 36 tonne figure with the heavier
     * package is not modelled as a second vehicle - the pack ships one VT-5.
     */
    private static void addVt5(Profile p) {
        VehicleArmor vt5 = new VehicleArmor();
        // Turret front 240, sides 60, rear 40, roof 30, floor 25; hull glacis 200, flanks 40,
        // rear 35, roof 25, floor 20.
        vt5.turret = new ArmorSet(240.0F, 60.0F, 40.0F, 30.0F, 25.0F);
        vt5.hull = new ArmorSet(200.0F, 40.0F, 35.0F, 25.0F, 20.0F);
        // A light tank's front is not an MBT glacis. The global x50 would turn a dart that gets
        // through the 240mm face into a one-shot - which is what that face is for - but it would
        // also make a 30mm round that got through 60mm of flank read like the glacis. The front
        // therefore gets x10 rather than x50, the same choice the Tiger's table makes.
        vt5.damage_multiplier = new DamageMultiplier(10.0F, 5.0F, 5.0F, 5.0F, 5.0F);
        // Like the K2 and the VT-4, the tables are equivalents rather than steel: the 240 and the
        // 200 are what the modular package is claimed to give along the nose, obliquity included.
        // The vehicle therefore takes the per-hit direction factor rather than stating a slope -
        // a dart 30 degrees off the nose crosses 240 / cos(30) = 277mm of turret array.
        vt5.kinetic_angle_scaling = Boolean.TRUE;

        // The Type 15's power pack: a 1,000 hp diesel in the 33.22-tonne vehicle this pack
        // describes, which is the 30.3 hp/t the type is published with. The ratio is the published
        // one rather than the pack mass's 23.3 - see horsepower_per_ton for why the two differ.
        vt5.engine_horsepower = 1000.0F;
        vt5.horsepower_per_ton = 30.3F;

        // The gun mount, measured off the structure model's `turret_barrel`: dead centre in X and
        // 0.125 blocks below the turret box's centre. A 105mm opening in a 240mm face leaves a
        // mantlet worth about 160mm, and a hit inside the circle is a hit on that.
        vt5.turret_weak_spot = new WeakSpot();
        vt5.turret_weak_spot.thickness = 160.0F;
        vt5.turret_weak_spot.multiplier = 8.0F;
        vt5.turret_weak_spot.radius = 0.35F;
        vt5.turret_weak_spot.offset_x = 0.0F;
        vt5.turret_weak_spot.offset_y = -0.125F;

        // The upper glacis is the large sloped plate and the lower plate the small one, so the
        // boundary sits below the hull box's middle: -0.2 leaves the glacis the upper 60% of the
        // face. 200mm up, 100mm down - the lower plate is where a light tank's protection thins.
        vt5.hull_front_split = new FrontSplit();
        vt5.hull_front_split.split = -0.2F;
        vt5.hull_front_split.upper = new Plate(200.0F, 10.0F);
        vt5.hull_front_split.lower = new Plate(100.0F, 10.0F);
        // Keep hull.front equal to the glacis so turning the split off falls back to that number.
        vt5.hull.front = 200.0F;

        // Tracks: measured off the structure model, which since v69 follows the display tread
        // loop now that the track is the TrackUnit link belt rather than baked-in plates. The
        // inner face is 0.874 of the hull's half-width; the threshold sits a hair below the
        // measurement (0.87) so a hit on the track's own inner face is not lost to rounding at the
        // boundary. `top` is the hull's mid-height.
        vt5.track_ends = new TrackEnds();
        vt5.track_ends.thickness = 60.0F;
        vt5.track_ends.multiplier = 1.5F;
        vt5.track_ends.inner_edge = 0.87F;
        vt5.track_ends.top = 0.0F;

        // Chemical table. The modular ERA/cage package is the difference: 650 and 600 on the two
        // fronts, 260 and 200 on the flanks, and the thin plates left where the steel is.
        ArmorOverride vt5Chemical = new ArmorOverride();
        vt5Chemical.turret = new ArmorSet(650.0F, 260.0F, 80.0F, 45.0F, 40.0F);
        vt5Chemical.hull = new ArmorSet(600.0F, 200.0F, 70.0F, 45.0F, 40.0F);
        vt5Chemical.damage_multiplier = new DamageMultiplier(4.0F, 4.0F, 4.0F, 4.0F, 4.0F);
        vt5Chemical.turret_weak_spot = new Plate(350.0F, 6.0F);
        vt5Chemical.hull_front_split = new FrontSplitPlates();
        vt5Chemical.hull_front_split.upper = new Plate(600.0F, 4.0F);
        vt5Chemical.hull_front_split.lower = new Plate(250.0F, 4.0F);
        vt5Chemical.track_ends = new Plate(80.0F, 2.0F);
        // The weapon station's own box is steel and glass, not the turret's composite; a shaped
        // charge gets further into it than into the face below it.
        vt5Chemical.part_armor.put("commander_machine_gun", new Plate(60.0F, 4.0F));
        // The ERA package covers the forward half of each flank. A hit on an unspent panel loses
        // 300mm of penetration and spends it; the hull's 200mm side is then measured with what is
        // left.
        vt5Chemical.hull_side_era = new Plate(300.0F, 4.0F).reactiveHp(300.0F);
        vt5.overrides.put("heat", vt5Chemical);
        vt5.overrides.put("he", thinRoofs(vt5Chemical, 30.0F, 30.0F, 30.0F, 25.0F));

        // The FY-4E box on the lower front plate. The display model carries it as `hull_era`; the
        // structure model carries a box of the same name, so the panel is what a round aimed at
        // the lower front meets first. 200 is the whole of what the panel is worth - the round
        // loses 200mm of penetration and the glacis behind it is measured with the rest. The
        // thickness is the same 200 so the scanner's two numbers cannot disagree.
        vt5.part_armor.put("hull_era", new Plate(200.0F, 1.0F).reactiveHp(200.0F));

        // The commander's remote weapon station needs a box of its own. The structure model parents
        // it to `turret_st`, a file-level root beside `turret` rather than under it, so the
        // bone-tree derivation does not count it as turret - and measuring it against the hull's
        // 40mm flank would be worse still. It is a thin armoured mount on top of the turret, so it
        // gets its own value, checked before every face-based zone.
        vt5.part_armor.put("commander_machine_gun", new Plate(30.0F, 5.0F));

        // The gun mount, the coaxial machine gun and the smoke-grenade bank are the parts the bone
        // tree does put under the turret. The remote weapon station is handled by the box above,
        // not by this list.
        vt5.turret_part_ids = new String[]{
                "turret",
                "turret_machine_gun",
                "turret_smoke_grenade"
        };

        // v89 -> v90: every Armor Pioneer vehicle carries the OPVT kit from now on. The pack grew a
        // snorkel bone and a switchable part for each of the seven, and a kit configured here is the
        // only thing that makes them do anything. Thirty seconds to fit, five metres: the T-64's
        // figures, because it is the same riverbed and the same pump.
        vt5.fording = new Fording.Kit();
        p.vehicles.put("armor_pioneer:vt5", vt5);

        // The commander's hatch periscope. The VT-5's seats were re-adapted on 2026-11-04 to the
        // corrected display model: the commander is now on the LEFT (under the remote weapon
        // station) and the gunner on the RIGHT. The commander's hatch is therefore `turret_door`
        // on the left, not the old `turret_door2` on the right.
        //
        // The camera point is measured off the current structure model's `turret_st` pivot,
        // (-0.4755, 2.8465, -0.1807) in the game frame, plus (-0.4745, 0.3036, 1.0307) to land at
        // (-0.950, 3.150, 0.850): the outer-left, forward part of the left hatch. That corner is
        // chosen rather than the hatch centre because the remote weapon station sits above the
        // centre-left of the hatch; this point is just outboard of its barrel and keeps the
        // centre ray and the whole +-10 deg cone clear. The hatch's own cubes top out at y 2.835,
        // so the camera clears the lid by 0.315 blocks, and the structure turret box (y 2.950) by
        // 0.200. Lowering it onto the lid puts the view inside the roof, which the check catches
        // the same way it caught the RWS sight.
        //
        // The part is the commander's remote weapon station: the player must be in that seat, and
        // the camera follows the turret's yaw because `yaw_part_id` names the turret and
        // `turret_st` is its child in the structure model. See PeriscopeView#yawOnly for why the
        // gun's pitch is deliberately excluded.
        Periscope.Settings vt5Periscope = new Periscope.Settings();
        vt5Periscope.seat_part_id = "commander_machine_gun";
        // Yaw follows the turret; the gun's pitch must not, or depressing the main gun would walk
        // the camera off the hatch and into the roof. See Periscope.Settings#yaw_part_id.
        vt5Periscope.yaw_part_id = "turret";
        vt5Periscope.offset_x = -0.4745F;
        vt5Periscope.offset_y = 0.3036F;
        vt5Periscope.offset_z = 1.0307F;
        p.periscopes.put("armor_pioneer:vt5", vt5Periscope);
    }

    /**
     * The VT-4 (MBT-3000), the Armor Pioneer pack's export main battle tank.
     *
     * <p>Where the VT-5 is a light tank with a thin skin, this is a full one: 52 tonnes, a 125mm
     * smoothbore, and steel hull and turret with a composite array over the frontal arc and
     * explosive reactive armour offered on top of it. Norinco publishes no thickness, so - like
     * the T-72B3M's - these figures are a draft, placed around the T-90M's: the two tanks solve
     * the same problem with the same kind of array, and the Russian tank's Relikt is the newer
     * package. They are meant to be corrected in game with the armour scanner.
     *
     * <p>The geometry, on the other hand, is measured rather than guessed. VehicleScaffold reads
     * it off the pack's structure file: hull centre (+0.00, +1.42, +0.38) half (1.78, 0.66, 4.12)
     * and turret (-0.02, +2.98, +0.02) half (2.00, 1.13, 2.67). The barrel leaves the turret box
     * 0.016 blocks to the right of its centre and 0.694 below it - that vertical offset is far
     * larger than the VT-5's because this model's turret box is much taller, not because the gun
     * sits any lower.
     */
    /**
     * One FY-4E panel.
     *
     * <p>The old single figure was 200mm for both threats, which flattered a long rod and
     * undervalued a jet. A real ERA panel is much stronger against a shaped charge, whose thin
     * stream the flying plates cut and scatter, than against a long rod, which they can only yaw
     * and erode. The split here is the same generation's published role - the panel is primarily
     * an anti-chemical layer that still takes a bite out of a dart - rather than a measured pair;
     * the two figures and both falloffs are configuration, not constants.
     *
     * <p>The thickness stays 200 so the scanner and the tables keep reading the panel's physical
     * rating; the CE and KE numbers are what the hit arithmetic uses.
     */
    private static Plate fy4e() {
        Plate panel = new Plate(200.0F, 1.0F).reactiveHp(200.0F);
        panel.era_ce = 300.0F;
        panel.era_ke = 150.0F;
        panel.era_ce_falloff = 0.35F;
        panel.era_ke_falloff = 0.50F;
        return panel;
    }

    private static void addVt4(Profile p) {
        VehicleArmor vt4 = new VehicleArmor();
        vt4.turret = new ArmorSet(600.0F, 180.0F, 80.0F, 40.0F, 40.0F);
        // Equal to the glacis while the split below is enabled, the rule every MBT here follows
        // so that turning the split off still leaves a figure that is right for this tank.
        vt4.hull = new ArmorSet(600.0F, 80.0F, 60.0F, 40.0F, 40.0F);
        // The same x10 front every tank in this file uses except the Abrams: a dart that punches
        // through a 600mm face is doing what that face exists to prevent, but a 30mm round through
        // 80mm of flank is not that and must not read like it.
        vt4.damage_multiplier = new DamageMultiplier(10.0F, 5.0F, 5.0F, 5.0F, 5.0F);

        // 1,300 hp at 52 tonnes: 25.0 hp/t, the export tank's published pair.
        vt4.engine_horsepower = 1300.0F;
        vt4.horsepower_per_ton = 25.0F;

        // The gun mount, from the measured offset. A 125mm bore in a 600mm face leaves a mantlet
        // around it worth about 420, and a hit inside this circle is a hit on that.
        vt4.turret_weak_spot = new WeakSpot();
        vt4.turret_weak_spot.thickness = 420.0F;
        vt4.turret_weak_spot.multiplier = 8.0F;
        vt4.turret_weak_spot.radius = 0.4F;
        vt4.turret_weak_spot.offset_x = 0.016F;
        vt4.turret_weak_spot.offset_y = -0.694F;

        // The glacis takes the upper 60% of the front face and the lower plate the rest, placed
        // the same way round as the VT-5's: whatever the array covers is the sloped plate above,
        // and the lower plate is where a tank this shape thins out. See the T-90M's note on the
        // sign - +1 is the top of the hull box, so "the larger plate on top" is a negative number.
        vt4.hull_front_split = new FrontSplit();
        vt4.hull_front_split.split = -0.2F;
        vt4.hull_front_split.upper = new Plate(600.0F, 10.0F);
        vt4.hull_front_split.lower = new Plate(180.0F, 10.0F);
        // The user's own reading of the tank, drawn on the front view of the display model as
        // four bands: the top of the glacis is a forced-ricochet face, the rest of the glacis is
        // the main array, the main belt's extension below the nose is 480, and the rest of the
        // lower plate keeps its 180.
        //
        // The two lines are fractions of the *plate they cut*, measured off that drawing and
        // mapped back onto the hull box with the display model's own geometry (`VehicleScaffold`
        // frame, 1/16 block): the glacis runs from the front deck at y 1.91 down to the nose at
        // 1.31, the lower plate from 1.31 down to the belly at 0.55. The hull box is
        // y 0.758..2.075, so +0.33 is 1.63 (46% down the glacis) and -0.93 is 0.80 (65% down the
        // lower plate). The split itself stays at -0.2, which is the glacis' lower edge at 1.29 -
        // the two independent anchors agree to 0.02 blocks.
        //
        // The top band is stated as the steel it is, not as an equivalent. It is the
        // near-horizontal strip above the glacis - the `hull_era2` rows on the display model,
        // whose tile centres drop 2.1 units over 12.7 units of run, which is 9.4 degrees above
        // horizontal and 80.6 from vertical. It is the face that exists to ricochet frontal
        // rounds, and treating it as a vertical slice of the main array (which is what the old
        // flat 800 did, with the vehicle's direction factor) hid the whole point of it: 125mm at
        // 81 degrees presents 799mm along a horizontal frontal line - the figure the user drew -
        // 1130mm from 45 degrees off the nose, and only 384mm from ten degrees above. The 125mm
        // is the calibration that holds the drawn 800 along that line, not a published figure;
        // change the thickness and the frontal equivalent moves with it.
        //
        // That line is also 81 degrees from the plate normal, so under the profile's ricochet
        // limit a horizontal frontal round is turned away before the steel is ever measured. The
        // strip is a ricochet face: only a dive of about 12 degrees or more arrives off the
        // normal closely enough to reach it, and a 45-degree dive meets it 36 degrees off the
        // normal with 154mm of line-of-sight thickness. The flatter the trajectory, the more the
        // strip does; that is what "forced ricochet" means for this face.
        //
        // A dart still meets the FY-4E panels first where they cover the glacis (200mm off), as
        // on every other panel-covered face; these figures are the armour behind them.
        vt4.hull_front_split.upper_band = new Plate(125.0F, 10.0F);
        vt4.hull_front_split.upper_band_split = 0.33F;
        vt4.hull_front_split.upper_band_slope = 81.0F;
        vt4.hull_front_split.lower_band = new Plate(480.0F, 10.0F);
        vt4.hull_front_split.lower_band_split = -0.93F;

        // The direction model for a composite tank. Norinco publishes no thickness for this
        // vehicle, so these tables are equivalents, not steel: 600mm of RHA against a dart already
        // includes the glacis' obliquity and the composite array inside it, and the 800mm ricochet
        // strip's figure already includes the extra lean that makes it one. Stating a plate slope
        // as well would count that obliquity a second time, which is why this vehicle carries no
        // `hull_slopes` / `turret_slopes`. What the tables cannot say is that the figure holds
        // along the front and grows as the hit moves off it, so the vehicle opts into the per-hit
        // direction factor instead: a dart 30 degrees off the nose meets 600 / cos(30) = 693mm of
        // array, and 45 degrees meets 848mm. The Panther is the other kind of entry - real steel
        // plus an inclination; the two are alternatives, not layers.
        //
        // The four FY-4E panels are deliberately outside this. They are parts of their own with
        // their own 200mm, taken off the round before the face behind them is measured, and the
        // zone pass leaves a part's box alone. So the panel does not grow with the angle and the
        // armour behind it does - the panel is a single-use layer, not a plate seen at an angle,
        // and scaling it too would charge the same protection twice.
        vt4.kinetic_angle_scaling = Boolean.TRUE;

        // Measured. As on the VT-5, the link belt now runs on the display tread loop, so the inner
        // face is 0.877 of the hull's half-width. The threshold sits a hair below the measurement
        // (0.87) so a hit on the track's own inner face is not lost to rounding at the boundary;
        // `top` is the hull's mid-height.
        vt4.track_ends = new TrackEnds();
        vt4.track_ends.thickness = 60.0F;
        vt4.track_ends.multiplier = 1.5F;
        vt4.track_ends.inner_edge = 0.87F;
        vt4.track_ends.top = 0.0F;

        // Chemical table. The figures here are the armour *behind* the FY-4E panels, not the
        // ERA-inclusive totals they used to be. The user marked four reactive panels on the
        // display model - both hull and turret are FY-4E - and the structure model carries a box
        // for each, so the panel pass takes its 200mm off a round before these faces are measured:
        //
        //   hull upper front   850 behind + 200 panel = the old 1050
        //   turret front       850 behind + 200 panel = the old 1050
        //   turret side        100 behind + 200 panel = the old 300
        //
        // The panels themselves live on the vehicle block, not in this override, for the same
        // reason VT-5's hull_era does: the user's figure is an anti-kinetic one, so a dart must
        // meet it too. 200mm comes off a dart before the 600/180 kinetic table, and 200 off a
        // shaped charge before the figures above. The totals are preserved while a panel is
        // intact and fall to the behind figure once it has been spent - the single-use rule
        // VT-5's hull_era uses. The two hull panels are split by height (lower strip and upper
        // array) and the two turret panels by side, so neither pair overlaps.
        ArmorOverride vt4Chemical = new ArmorOverride();
        vt4Chemical.turret = new ArmorSet(850.0F, 100.0F, 120.0F, 60.0F, 50.0F);
        vt4Chemical.hull = new ArmorSet(850.0F, 120.0F, 90.0F, 60.0F, 50.0F);
        vt4Chemical.damage_multiplier = new DamageMultiplier(6.0F, 4.0F, 4.0F, 4.0F, 4.0F);
        vt4Chemical.turret_weak_spot = new Plate(550.0F, 6.0F);
        vt4Chemical.hull_front_split = new FrontSplitPlates();
        vt4Chemical.hull_front_split.upper = new Plate(850.0F, 6.0F);
        vt4Chemical.hull_front_split.lower = new Plate(300.0F, 6.0F);
        vt4Chemical.track_ends = new Plate(80.0F, 2.0F);
        // The remote weapon station is a thin mount of steel and glass, not the turret's array.
        vt4Chemical.part_armor.put("commander_machine_gun", new Plate(60.0F, 4.0F));
        vt4.overrides.put("heat", vt4Chemical);
        vt4.overrides.put("he", thinRoofs(vt4Chemical, 30.0F, 30.0F, 30.0F, 25.0F));

        // The commander's remote weapon station needs a box of its own, as on the VT-5.
        //
        // The difference from the VT-5 is worth stating: there the station's bone is a file-level
        // root beside `turret`, so the bone-tree derivation misses it and it could only be a box.
        // Here the model parents `turret_st` to `turret`, so the derivation does count it and it
        // belongs in the list below - VehicleScaffold compares that list with what it derived, and
        // leaving the station out would be a config that describes a different model. The box is
        // still what sets its armour: `part_armor` is looked up before every face-based zone.
        vt4.part_armor.put("commander_machine_gun", new Plate(30.0F, 5.0F));

        // The FY-4E panels, hull and turret alike. On the vehicle block rather than in the
        // chemical override, exactly as VT-5's hull_era is: the user's 200mm is the anti-kinetic
        // figure, so a dart has to meet the panel before the kinetic 600/180 table. Thickness
        // equals HP so the scanner's two numbers cannot disagree.
        vt4.part_armor.put("hull_era", fy4e());
        vt4.part_armor.put("hull_era2", fy4e());
        vt4.part_armor.put("turret_era", fy4e());
        vt4.part_armor.put("turret_era2", fy4e());

        // Measured, and deliberately the whole derived set. The two doors are turret doors - a
        // hit on one zones as the turret, which is what it is; and the remote weapon station is
        // here for the reason given above, even though its own box decides the thickness.
        vt4.turret_part_ids = new String[]{
                "turret",
                "commander_machine_gun",
                "turret_machine_gun",
                "turret_smoke_grenade",
                "door_gunner",
                "door_commander",
                "turret_era",
                "turret_era2"
        };

        vt4.fording = new Fording.Kit();
        p.vehicles.put("armor_pioneer:vt4", vt4);
    }

    /**
     * K2 Black Panther. Geometry from VehicleScaffold: hull centre (+0.00, +1.49, +0.14)
     * half (1.37, 0.74, 4.53), turret centre (+0.26, +2.99, -0.28) half (1.61, 0.98, 3.38).
     * The track path is now at the display track centre (1.704 blocks), and the visible track is
     * the 0.654-block link model rendered along it: its inner face is 1.005 of the hull half-width,
     * while the structure path itself reads 1.225. The strip starts at 1.00 so the whole link is
     * caught. The main gun is 0.26 left of the turret box centre and 0.43 below it; the remote
     * weapon station is a separate part and gets its own 30 mm box rather than being counted as
     * turret array.
     */
    private static void addK2(Profile p) {
        VehicleArmor k2 = new VehicleArmor();
        // Draft figures from public K2 protection claims (resist current APFSDS / heavy ATGM).
        k2.turret = new ArmorSet(700.0F, 100.0F, 70.0F, 40.0F, 30.0F);
        k2.hull = new ArmorSet(650.0F, 80.0F, 60.0F, 35.0F, 30.0F);
        k2.damage_multiplier = new DamageMultiplier(10.0F, 5.0F, 5.0F, 5.0F, 5.0F);

        // 1,500 hp at 55 tonnes: 27.3 hp/t, Hyundai Rotem's published pair.
        k2.engine_horsepower = 1500.0F;
        k2.horsepower_per_ton = 27.3F;

        k2.turret_weak_spot = new WeakSpot();
        // Disabled for now. VehicleScaffold picks the remote weapon station's barrel as "the gun"
        // on this model (it has three barrel bones), so its derived offset is the RWS, not the main
        // gun. A weak spot in the wrong place is worse than none; turn it on with the main-gun
        // offset (-0.26, -0.43) once the tool can pick the main barrel.
        k2.turret_weak_spot.thickness = 450.0F;
        k2.turret_weak_spot.multiplier = 8.0F;
        k2.turret_weak_spot.radius = 0.0F;
        k2.turret_weak_spot.offset_x = -0.26F;
        k2.turret_weak_spot.offset_y = -0.43F;

        k2.hull_front_split = new FrontSplit();
        k2.hull_front_split.split = -0.2F;
        k2.hull_front_split.upper = new Plate(650.0F, 10.0F);
        k2.hull_front_split.lower = new Plate(200.0F, 10.0F);

        // The same direction model as the VT-4, and for the same reason. These tables are draft
        // equivalents from the K2's published protection claims - a ceramic composite array, not
        // bare steel - so the 700 and the 650 already include the obliquity of the plates they
        // are carried in. Stating a slope as well would count that lean twice; what the tables
        // cannot say is that the figure holds along the front and grows as the hit moves off it.
        // So the vehicle takes the per-hit direction factor: a dart 30 degrees off the nose meets
        // 700 / cos(30) = 808mm of turret array, and 45 degrees meets 990; the glacis's 650 goes
        // 750 / 919. Kinetic only, and face plates only - the remote weapon station and the coax
        // are boxes of their own and stay at their flat 30 and 20mm.
        k2.kinetic_angle_scaling = Boolean.TRUE;

        k2.track_ends = new TrackEnds();
        k2.track_ends.thickness = 60.0F;
        k2.track_ends.multiplier = 1.5F;
        k2.track_ends.inner_edge = 1.00F;
        k2.track_ends.top = 0.0F;

        ArmorOverride k2Chemical = new ArmorOverride();
        k2Chemical.turret = new ArmorSet(1150.0F, 300.0F, 120.0F, 60.0F, 50.0F);
        k2Chemical.hull = new ArmorSet(1050.0F, 120.0F, 90.0F, 60.0F, 50.0F);
        k2Chemical.damage_multiplier = new DamageMultiplier(6.0F, 4.0F, 4.0F, 4.0F, 4.0F);
        k2Chemical.turret_weak_spot = new Plate(550.0F, 6.0F);
        k2Chemical.hull_front_split = new FrontSplitPlates();
        k2Chemical.hull_front_split.upper = new Plate(1050.0F, 6.0F);
        k2Chemical.hull_front_split.lower = new Plate(300.0F, 6.0F);
        k2Chemical.track_ends = new Plate(80.0F, 2.0F);
        k2Chemical.part_armor.put("commander_machine_gun", new Plate(60.0F, 4.0F));
        k2.overrides.put("heat", k2Chemical);
        k2.overrides.put("he", thinRoofs(k2Chemical, 30.0F, 30.0F, 30.0F, 25.0F));

        k2.part_armor.put("commander_machine_gun", new Plate(30.0F, 5.0F));
        k2.part_armor.put("turret_machine_gun", new Plate(20.0F, 3.0F));

        k2.turret_part_ids = new String[]{
                "turret",
                "commander_machine_gun",
                "turret_machine_gun",
                "turret_smoke_grenade"
        };

        // 120mm K279 / K277 / K280. The domestic dart is the default load; the German
        // DM53 is an imported mark of the same slot, chosen with the left Alt chooser like
        // the VT-5's DTW/BTA marks. The chemical and HE rounds use the same helpers as the
        // other 120/125mm guns.
        Ammo k2Dart = new Ammo(600.0F);
        // The same long-rod curve as the VT-4's darts, anchored to each mark's own muzzle figure.
        k2Dart.falloffFromPenetration(DART_RANGE_SHARE);
        // The debris model's inputs. The mass and the muzzle velocity are the published order of
        // magnitude for the K279 and DM53, not a measured pair; what they change is the rate at
        // which the debris energy falls with velocity and the fragment mass the hole supports.
        k2Dart.mass_kg = 8.3F;
        k2Dart.muzzle_velocity = 1690.0F;
        k2Dart.mass_exponent = 0.70F;
        k2Dart.velocity_exponent = 0.50F;
        k2Dart.caliber_mm = 120.0F;
        Ammo dm53 = kineticVariant("DM53 APFSDS", 700.0F)
                .falloffFromPenetration(DART_RANGE_SHARE);
        dm53.mass_kg = 8.5F;
        dm53.muzzle_velocity = 1750.0F;
        dm53.mass_exponent = 0.70F;
        dm53.velocity_exponent = 0.50F;
        dm53.caliber_mm = 120.0F;
        k2Dart.variants = new java.util.ArrayList<>(java.util.List.of(dm53));
        p.ammo_penetration.put("armor_pioneer:cannon_120mm",
                shelf(k2Dart, AmmoCategory.CANNON_AP));
        Ammo k2Heat = shapedCharge(480.0F);
        k2Heat.heat_damage = 1736.0F;
        p.ammo_penetration.put("armor_pioneer:cannon_120mm_heat",
                shelf(k2Heat, AmmoCategory.CANNON_HEAT));
        Ammo k2He = highExplosive(60.0F);
        k2He.charge_kg = 3.5F;
        p.ammo_penetration.put("armor_pioneer:cannon_120mm_he",
                shelf(k2He, AmmoCategory.CANNON_HE));

        // KSTAM-II, the K2's indigenous top-attack gun-launched munition. Its EFP warhead
        // is rated as a shaped charge; the top plate is what the lofted trajectory is
        // meant to meet. The figure is an estimate - no primary source publishes it.
        //
        // The four top_attack_* fields are what turn the base mod's PRESET profile into an
        // actual top attack: the missile flies its ascent/cruise/dive path, and this addon
        // detonates it four blocks above the target's roof instead of letting it fly into the
        // target, then resolves the warhead as a shot straight down. Without them the round is
        // still a normal 200mm shaped charge fired at whatever face the gunner aimed at.
        Ammo kstam = shapedCharge(200.0F);
        kstam.top_attack_standoff = 1.0F;
        kstam.top_attack_radius = 3.0F;
        kstam.top_attack_min_height = 0.25F;
        // The EFP's behind-armour rating, calibrated on the old residual-and-roof-multiplier
        // figure for a 200mm warhead against a 40mm roof.
        kstam.heat_damage = 923.0F;
        p.ammo_penetration.put("armor_pioneer:cannon_120mm_kstam",
                shelf(kstam, AmmoCategory.CANNON_HEAT));

        // The K2's commander weapon is the K6, the Korean 12.7mm gun derived from the M2HB -
        // not the M2HB id itself, which the Type 10 keeps under its own designation. Its own
        // id is what puts the right name on the K2's HUD. Same 30mm/x5 rating as every heavy
        // machine gun here: far too weak to trouble tank armour, so a flat multiplier.
        p.ammo_penetration.put("armor_pioneer:k6_12_7mm",
                shelf(new Ammo(30.0F, 5.0F), AmmoCategory.MACHINE_GUN));
        // The Type 10's commander station: the Type 90, the M2HB under its JGSDF designation.
        p.ammo_penetration.put("armor_pioneer:gun_12_7mm_m2hb",
                shelf(new Ammo(30.0F, 5.0F), AmmoCategory.MACHINE_GUN));

        k2.fording = new Fording.Kit();
        p.vehicles.put("armor_pioneer:k2", k2);
    }

    /**
     * The Type 10 (10式戦車), the JGSDF's 48-tonne MBT, and the second vehicle the Armor Pioneer pack
     * gained after the K2.
     *
     * <p><b>Armament.</b> A Japan Steel Works 120mm L/44 smoothbore with an autoloader (22 rounds),
     * a Type 90 12.7mm machine gun - the M2HB under its Japanese designation - on the commander's
     * remote station, and a Type 74 7.62mm coaxial gun. The pack gives the tank its own two rounds,
     * {@code cannon_120mm_type10} and {@code cannon_120mm_jm12}; before those existed the Type 10
     * drew the K2's Korean K279/K277/K280 ids and the HUD named the wrong country's ammunition.
     *
     * <p><b>Armour.</b> Modular ceramic composite with lightweight upper armour; the array's
     * thickness is classified and no source publishes an RHAe figure. The draft below is placed
     * between the VT-4 and the K2 - the Type 10 belongs to the same protection generation as those
     * two and is accepted to be better protected than the Type 90 - and is meant to be corrected in
     * game with the armour scanner.
     *
     * <p><b>The front is close to the K2's; the flanks are not.</b> The tank is 48 tonnes against
     * the K2's 55, and the weight it saves is not taken from the front - the Type 10's stated
     * trade is a frontal array of the same generation carried on a hull whose sides, rear and roof
     * are deliberately thinner, which is what makes the 40-tonne air-transportable figure possible.
     * So every face here is at or below the K2's, and the sides are well below it: a table that gave
     * the lighter tank a thicker flank than the K2 (the first draft did: turret side 120, hull side
     * 90) was describing a vehicle nobody has built, and the chemical table was worse still, with a
     * turret front of 1200 against the K2's 1150. Both were corrected at v64.
     *
     * <p><b>Geometry, measured</b> with VehicleScaffold from the pack's own structure file: hull
     * centre (+0.00, +1.42, +0.41) half (1.84, 0.86, 4.08) and turret (0.00, +2.82, -0.11) half
     * (1.84, 0.74, 3.24). The barrel leaves the turret 0.103 blocks to the left of its centre and
     * 0.133 below it. The tracks start at 0.834 of the hull's half-width, so the config threshold
     * sits a hair under it at 0.83.
     */
    private static void addType10(Profile p) {
        VehicleArmor type10 = new VehicleArmor();
        type10.turret = new ArmorSet(650.0F, 90.0F, 60.0F, 35.0F, 25.0F);
        // Equal to the upper glacis while the split below is enabled.
        type10.hull = new ArmorSet(620.0F, 70.0F, 55.0F, 30.0F, 25.0F);
        type10.damage_multiplier = new DamageMultiplier(10.0F, 5.0F, 5.0F, 5.0F, 5.0F);
        // Composite equivalents, as on the K2 and the VT-4: the 650 and the 620 are along-nose
        // figures, so the hit's deviation from the nose is what the direction factor adds.
        type10.kinetic_angle_scaling = Boolean.TRUE;

        // 1,200 hp diesel, 48 tonnes at full loadout: 25.0 hp/t, the JGSDF's published pair and
        // the same reference ratio every other MBT here is measured against.
        type10.engine_horsepower = 1200.0F;
        type10.horsepower_per_ton = 25.0F;

        // A 120mm bore in a 650mm face leaves a mantlet worth about 450; the offsets are measured,
        // not assumed. The Type 10's gun is 12mm narrower than the Tianma's, which is the one place
        // the two tanks' real armament changes the geometry of this table rather than its numbers.
        type10.turret_weak_spot = new WeakSpot();
        type10.turret_weak_spot.thickness = 450.0F;
        type10.turret_weak_spot.multiplier = 8.0F;
        type10.turret_weak_spot.radius = 0.4F;
        type10.turret_weak_spot.offset_x = -0.103F;
        type10.turret_weak_spot.offset_y = -0.133F;

        // The glacis takes the upper 60% of the face; the lower plate is where a tank this shape
        // thins, the same split the VT-4 and K2 use. +1 is the top of the hull box, so the larger
        // plate on top is a negative split.
        type10.hull_front_split = new FrontSplit();
        type10.hull_front_split.split = -0.2F;
        type10.hull_front_split.upper = new Plate(620.0F, 10.0F);
        type10.hull_front_split.lower = new Plate(190.0F, 10.0F);

        // Measured. As on the other Armor Pioneer tanks the track sits inside the hull box - the
        // inner face is 0.834 of the hull's 1.84-block half-width - so 0.83 catches the whole link
        // while losing none of the flank above it.
        type10.track_ends = new TrackEnds();
        type10.track_ends.thickness = 60.0F;
        type10.track_ends.multiplier = 1.5F;
        type10.track_ends.inner_edge = 0.83F;
        type10.track_ends.top = 0.0F;

        // The chemical table. The modular ceramic array resists a shaped-charge jet far better than
        // a dart, so the fronts move from 650/620 to 1100/1050 while the thin plates stay thin, and
        // every one of those figures sits below the K2's 1150/1050.
        ArmorOverride type10Chemical = new ArmorOverride();
        type10Chemical.turret = new ArmorSet(1100.0F, 250.0F, 110.0F, 55.0F, 45.0F);
        type10Chemical.hull = new ArmorSet(1050.0F, 110.0F, 85.0F, 55.0F, 40.0F);
        type10Chemical.damage_multiplier = new DamageMultiplier(6.0F, 4.0F, 4.0F, 4.0F, 4.0F);
        type10Chemical.turret_weak_spot = new Plate(570.0F, 6.0F);
        type10Chemical.hull_front_split = new FrontSplitPlates();
        type10Chemical.hull_front_split.upper = new Plate(1050.0F, 6.0F);
        type10Chemical.hull_front_split.lower = new Plate(280.0F, 6.0F);
        type10Chemical.track_ends = new Plate(80.0F, 2.0F);
        type10Chemical.part_armor.put("commander_machine_gun", new Plate(60.0F, 4.0F));
        type10.overrides.put("heat", type10Chemical);
        type10.overrides.put("he", thinRoofs(type10Chemical, 30.0F, 30.0F, 30.0F, 25.0F));

        // The commander's station carries the Type 90 12.7mm (M2HB): thin steel and glass, not the
        // turret's array, and the coaxial 7.62mm's mount is thinner still.
        type10.part_armor.put("commander_machine_gun", new Plate(30.0F, 5.0F));
        type10.part_armor.put("turret_machine_gun", new Plate(20.0F, 3.0F));

        // Measured. Both doors are turret doors; the remote station is listed as well because the
        // model parents it under `turret`, exactly the case the VT-4 documents.
        type10.turret_part_ids = new String[]{
                "turret",
                "commander_machine_gun",
                "turret_machine_gun",
                "turret_smoke_grenade",
                "door_gunner",
                "door_commander"
        };

        type10.fording = new Fording.Kit();
        p.vehicles.put("armor_pioneer:type10", type10);

        // The JGSDF's 120mm rounds. The Type 10 APFSDS is the round the gun was built around; no
        // primary source states an RHAe figure, so 600 is an estimate placed on the K279's line
        // and below the imported DM53 (700). JM33 is the licensed DM33, quoted at about 480 -
        // carried as a load of the same kinetic slot, chosen with the load chooser like every other
        // dart here. The pair is local to this tank so the K2's slot is not handed a Japanese round
        // it never fired.
        Ammo type10Dart = new Ammo(600.0F);
        // The Type 10's own dart is not documented either; 7.5 kg at 1750 m/s is a 120mm tungsten
        // round on the L/44 class, and the load is the licensed DM33 - 7.6 kg at 1650.
        type10Dart.mass_kg = 7.5F;
        type10Dart.muzzle_velocity = 1750.0F;
        type10Dart.caliber_mm = 120.0F;
        Ammo jm33 = kineticVariant("JM33 APFSDS", 480.0F);
        jm33.mass_kg = 7.6F;
        jm33.muzzle_velocity = 1650.0F;
        jm33.caliber_mm = 120.0F;
        type10Dart.variants = new java.util.ArrayList<>(java.util.List.of(jm33));
        p.ammo_penetration.put("armor_pioneer:cannon_120mm_type10",
                shelf(type10Dart, AmmoCategory.CANNON_AP));

        // JM12A1, the multi-purpose HEAT round: a shaped charge whose fuze can be switched between
        // impact, proximity and delay. The multi-purpose helper is the same shape the Israeli and
        // Russian multi-purpose rounds use in this table.
        // Both of the round's modes are jets, so both entries get the jet model's rating - the
        // impact one and the proximity one inside it. Missing the inner one would leave the
        // airburst free to fall back to the old residual-times-face rule.
        Ammo jm12 = multiPurposeHeat(480.0F);
        jm12.heat_damage = 1736.0F;
        jm12.proximity.heat_damage = 1736.0F;
        p.ammo_penetration.put("armor_pioneer:cannon_120mm_jm12",
                shelf(jm12, AmmoCategory.CANNON_HEAT));
    }

    /**
     * The Tianma-20 (Chonma-2, 天马-20), the Korean People's Army's new MBT, as the pack models the
     * 2026 parade vehicle.
     *
     * <p><b>Armament.</b> A 125mm gun derived from the Soviet 2A46, a 7.62mm coaxial machine gun,
     * an AGS-30 30mm automatic grenade launcher on the commander's station, and two Bulsae-3
     * anti-tank missiles in external launchers. The pack cannot rate the Bulsae-3 - its model has no
     * separate launcher part - and it does carry the APS launchers the real vehicle's GL6-type
     * system is built around, which this addon already made functional as two automatic weapons.
     * The tank's rounds are local ids ({@code cannon_125mm_dprk*}) so the HUD no longer names the
     * VT-4's Chinese BTA4/DTJ1/BTE2/BTS8 rounds on a North Korean tank.
     *
     * <p><b>Armour.</b> Composite armour with explosive reactive armour over the frontal arc, plus
     * the active protection system. Nothing about the thickness is public; the draft below is placed
     * between the T-72B3M and the plain T-90M - the same generation of array - and is meant to be
     * corrected in game with the armour scanner. The frontal figures keep the front arc's ERA folded
     * in, as they always did; v80 also gives the user's three new reaction panels - the two hull-side
     * skirt runs and the turret-roof slab - their own boxes, described at the panels themselves.
     *
     * <p><b>Geometry, measured</b> with VehicleScaffold: hull centre (+0.00, +1.33, +0.43) half
     * (2.02, 0.70, 4.24) and turret (0.00, +2.74, -0.24) half (1.95, 0.85, 2.93). The barrel leaves
     * the turret 0.003 to the left of its centre and 0.287 below it. The tracks start at 0.791 of
     * the hull's half-width, so the config threshold sits at 0.79.
     */
    private static void addTianma20(Profile p) {
        VehicleArmor tianma = new VehicleArmor();
        tianma.turret = new ArmorSet(540.0F, 100.0F, 55.0F, 30.0F, 25.0F);
        // Equal to the glacis while the split below is enabled.
        tianma.hull = new ArmorSet(520.0F, 80.0F, 50.0F, 30.0F, 25.0F);
        tianma.damage_multiplier = new DamageMultiplier(10.0F, 5.0F, 5.0F, 5.0F, 5.0F);
        // Composite equivalents with reactive panels on top, as on the VT-4: the panels keep
        // their own box value and the array behind them takes the direction factor.
        tianma.kinetic_angle_scaling = Boolean.TRUE;

        // 1,200 hp diesel at 55 tonnes: 21.8 hp/t. The pack's mass is 48 tonnes, which would read
        // 25.0; the published ratio is the one shown, as with the VT-5. This is the slowest ratio
        // among the Armor Pioneer tanks, which is what a 55-tonne vehicle on a 1,200 hp engine is.
        tianma.engine_horsepower = 1200.0F;
        tianma.horsepower_per_ton = 21.8F;

        // A 125mm bore in a 540mm face leaves a mantlet worth about 360. The offsets are measured;
        // the vertical offset is large because this model's turret box is much taller than the
        // Type 10's, not because the gun sits unusually low.
        tianma.turret_weak_spot = new WeakSpot();
        tianma.turret_weak_spot.thickness = 360.0F;
        tianma.turret_weak_spot.multiplier = 8.0F;
        tianma.turret_weak_spot.radius = 0.4F;
        tianma.turret_weak_spot.offset_x = -0.003F;
        tianma.turret_weak_spot.offset_y = -0.287F;

        tianma.hull_front_split = new FrontSplit();
        tianma.hull_front_split.split = -0.2F;
        tianma.hull_front_split.upper = new Plate(520.0F, 10.0F);
        tianma.hull_front_split.lower = new Plate(180.0F, 10.0F);

        tianma.track_ends = new TrackEnds();
        tianma.track_ends.thickness = 60.0F;
        tianma.track_ends.multiplier = 1.5F;
        tianma.track_ends.inner_edge = 0.79F;
        tianma.track_ends.top = 0.0F;

        // The chemical table. The ERA and the composite front move it to 850/800 against a jet,
        // with the flanks left where the steel is. This front-arc ERA is part of the array rather
        // than boxes: the user's separate tiles are on the flanks and the roof (below), and their
        // own panels sit in front of the flank/roof figures without moving them.
        ArmorOverride tianmaChemical = new ArmorOverride();
        tianmaChemical.turret = new ArmorSet(850.0F, 260.0F, 110.0F, 55.0F, 45.0F);
        tianmaChemical.hull = new ArmorSet(800.0F, 150.0F, 80.0F, 55.0F, 40.0F);
        tianmaChemical.damage_multiplier = new DamageMultiplier(6.0F, 4.0F, 4.0F, 4.0F, 4.0F);
        tianmaChemical.turret_weak_spot = new Plate(450.0F, 6.0F);
        tianmaChemical.hull_front_split = new FrontSplitPlates();
        tianmaChemical.hull_front_split.upper = new Plate(800.0F, 6.0F);
        tianmaChemical.hull_front_split.lower = new Plate(260.0F, 6.0F);
        tianmaChemical.track_ends = new Plate(80.0F, 2.0F);
        tianmaChemical.part_armor.put("commander_machine_gun", new Plate(60.0F, 4.0F));
        tianmaChemical.part_armor.put("aps_left", new Plate(30.0F, 4.0F));
        tianmaChemical.part_armor.put("aps_right", new Plate(30.0F, 4.0F));
        tianma.overrides.put("heat", tianmaChemical);
        tianma.overrides.put("he", thinRoofs(tianmaChemical, 30.0F, 30.0F, 30.0F, 25.0F));

        // The commander's station: the AGS-30 mount is thin steel and glass. The coax mount is
        // thinner. The two APS launcher boxes are aluminium and optics - a hit there destroys the
        // launcher, which is what the real system's own armour is for, and it must not be measured
        // as the turret array behind it.
        tianma.part_armor.put("commander_machine_gun", new Plate(30.0F, 5.0F));
        tianma.part_armor.put("turret_machine_gun", new Plate(20.0F, 3.0F));
        tianma.part_armor.put("aps_left", new Plate(20.0F, 3.0F));
        tianma.part_armor.put("aps_right", new Plate(20.0F, 3.0F));

        // The three reaction panels the user bolted on in v80: two side-skirt runs over the front
        // half of each flank (`hull_era_l` / `hull_era_r`) and one slab on the turret roof
        // (`turret_era_roof`). They are the outermost thing on those faces, so a hit there meets
        // the panel first and then the flank or roof behind it. 200mm each - the same FY-4E figure
        // the VT-4's panels carry - because nothing about this vehicle's ERA is public; it is a
        // draft to correct with the scanner, like every other figure on this vehicle. No existing
        // number moves: the front-arc ERA stays folded into the frontal figures, and the flanks
        // and roof were never ERA-inclusive.
        tianma.part_armor.put("hull_era_l", new Plate(200.0F, 1.0F).reactiveHp(200.0F));
        tianma.part_armor.put("hull_era_r", new Plate(200.0F, 1.0F).reactiveHp(200.0F));
        tianma.part_armor.put("turret_era_roof", new Plate(200.0F, 1.0F).reactiveHp(200.0F));

        // Measured. The APS launchers are derived as turret parts because their bones hang off the
        // turret; they are documented `part_armor` boxes instead of list members, exactly the
        // VT-5's remote-station arrangement, so a hit on a launcher is measured against the box
        // rather than as the turret's composite. The roof panel is a turret part for the same
        // reason: a hit on it is a hit on the turret's roof, not on the hull.
        tianma.turret_part_ids = new String[]{
                "turret",
                "commander_machine_gun",
                "turret_machine_gun",
                "turret_smoke_grenade",
                "door_gunner",
                "door_commander",
                "turret_era_roof"
        };

        tianma.fording = new Fording.Kit();
        p.vehicles.put("armor_pioneer:tianma20", tianma);

        // The 2A46-derived 125mm's Korean rounds. The DPRK's current dart is not documented; 630 is
        // the user's figure for it, above the 3BM42 (457, the round the T-64 entry keeps) and close
        // to the newer Russian 3BM46/3BM60 pair, which is the direction a 2026 vehicle would have
        // gone. 3BM42 is the older load of the same slot.
        Ammo dprkDart = new Ammo(630.0F);
        dprkDart.mass_kg = 7.0F;
        dprkDart.muzzle_velocity = 1700.0F;
        dprkDart.caliber_mm = 125.0F;
        // 3BM42's own figures: the complete projectile is 7.05 kg and leaves a 2A46 at 1700 m/s.
        Ammo mango = kineticVariant("3BM42 APFSDS", 457.0F);
        mango.mass_kg = 7.05F;
        mango.muzzle_velocity = 1700.0F;
        mango.caliber_mm = 125.0F;
        dprkDart.variants = new java.util.ArrayList<>(java.util.List.of(mango));
        p.ammo_penetration.put("armor_pioneer:cannon_125mm_dprk",
                shelf(dprkDart, AmmoCategory.CANNON_AP));

        // BK-29, the 125mm shaped charge, a little above the Russian 2A46 rounds (480/520) for the
        // larger Korean warhead, and below the Chinese BTJ1 (550).
        Ammo bk29 = shapedCharge(520.0F);
        bk29.heat_damage = 1897.0F;
        p.ammo_penetration.put("armor_pioneer:cannon_125mm_dprk_heat",
                shelf(bk29, AmmoCategory.CANNON_HEAT));

        // The 125mm high-explosive shell, the same 3.5 kg / 2000-point round every other 125mm
        // here fires.
        p.ammo_penetration.put("armor_pioneer:cannon_125mm_dprk_he",
                shelf(chargeShell(60.0F, 3.5F), AmmoCategory.CANNON_HE));

        // The AGS-30 automatic grenade launcher: a 30x29mm high-explosive grenade, rated as what it
        // is - an area weapon that can scratch a roof and does nothing to a tank's front plate. The
        // same numbers as the QLZ-04 entry; the two launchers differ in calibre, not in effect
        // against armour.
        Ammo ags = new Ammo(30.0F);
        ags.profile = "he";
        ags.penetration_damage = 60.0F;
        ags.spall_damage_min = 10.0F;
        ags.spall_damage_max = 30.0F;
        ags.spall_falloff_to = 400.0F;
        ags.spall_on_penetration = Boolean.FALSE;
        ags.spall_blindness_ticks = 10;
        ags.replaces_base_explosion = Boolean.TRUE;
        p.ammo_penetration.put("armor_pioneer:ags_30",
                shelf(ags, AmmoCategory.AUTOCANNON_HE));
    }

    /**
     * The ZTZ-100 (100式), the Armor Pioneer pack's fourth-generation MBT.
     *
     * <p><b>Armament.</b> A 105mm smoothbore in an unmanned turret, a coaxial 7.62mm and a 12.7mm
     * remote weapon station; the GL-6 active protection system's two launcher boxes are modelled as
     * parts and are rated as boxes for the same reason the Tianma-20's are. Both APS mounts and the
     * smoke bank are listed as turret parts, because their bones hang off the turret.
     *
     * <p><b>Hybrid drive.</b> The published reports describe a hybrid-electric powertrain of about
     * 1,500 hp; 1,500 hp at the 40-tonne class weight is 37.5 hp/t. The electric half of that system
     * is this addon's own {@link Hybrid} feature, keyed to the vehicle in {@link Profile#hybrids} -
     * not a part of this armour table, and deliberately separate from it for the same reason the
     * suspension map is: a drivetrain is equipment, not protection.
     *
     * <p><b>Armour.</b> A composite array with add-on panels; no source publishes an RHAe figure.
     * The numbers below are the accepted draft from the vehicle's integration pass - the kinetic
     * front sits above the VT-4 and below the K2, the chemical front is carried by the modular
     * array, and the thin flanks and roof are left thin. They are meant to be corrected in game with
     * the armour scanner.
     *
     * <p><b>Geometry.</b> The turret machine guns and the two APS boxes are thin mount armour; the
     * glacis split, the track-strip edge and the gun-mount offsets are the figures from the same
     * integration pass, all measured against this model rather than copied from another tank.
     */
    private static void addZtz100(Profile p) {
        VehicleArmor ztz = new VehicleArmor();
        ztz.turret = new ArmorSet(650.0F, 120.0F, 80.0F, 40.0F, 30.0F);
        // Equal to the glacis while the split below is enabled.
        ztz.hull = new ArmorSet(700.0F, 80.0F, 50.0F, 40.0F, 25.0F);
        ztz.damage_multiplier = new DamageMultiplier(10.0F, 5.0F, 5.0F, 5.0F, 5.0F);
        // Composite equivalents plus add-on armour; the add-on boxes keep their own values and
        // the face plates behind them take the direction factor.
        ztz.kinetic_angle_scaling = Boolean.TRUE;

        // 1,500 hp hybrid at 40 tonnes: 37.5 hp/t. The 1,500 hp figure and the 85 km/h that goes
        // with it are the published estimates (VPK.name, 2025); the 40-tonne class weight is the
        // public estimate range's upper end and the pack's own mass.
        ztz.engine_horsepower = 1500.0F;
        ztz.horsepower_per_ton = 37.5F;

        // The 105mm bore in the 650mm face. The offsets are the measured gun axis; the radius is
        // kept at the other Armor Pioneer tanks' 0.35-0.4 so the mantlet is hittable.
        ztz.turret_weak_spot = new WeakSpot();
        ztz.turret_weak_spot.thickness = 300.0F;
        ztz.turret_weak_spot.multiplier = 8.0F;
        ztz.turret_weak_spot.radius = 0.35F;
        ztz.turret_weak_spot.offset_x = 0.006F;
        ztz.turret_weak_spot.offset_y = 0.018F;

        // The glacis is the upper part of the face, so the split line sits above the middle. The
        // lower plate is the thinner one, as on every other tank in the pack.
        ztz.hull_front_split = new FrontSplit();
        ztz.hull_front_split.split = 0.2F;
        ztz.hull_front_split.upper = new Plate(700.0F, 10.0F);
        ztz.hull_front_split.lower = new Plate(250.0F, 10.0F);

        // Measured: the track's inner face sits at 1.175 blocks, and the strip is the running gear
        // below the hull's vertical centre.
        ztz.track_ends = new TrackEnds();
        ztz.track_ends.thickness = 60.0F;
        ztz.track_ends.multiplier = 1.5F;
        ztz.track_ends.inner_edge = 1.175F;
        ztz.track_ends.top = 0.0F;

        // The chemical table. The modular array carries the turret and hull fronts to 900, the
        // flanks stay close to the steel, and the roof and floor are the plates the HE table below
        // thins. The two APS boxes, the commander's RWS and the coax are mount armour, not the
        // array.
        ArmorOverride ztzChemical = new ArmorOverride();
        ztzChemical.turret = new ArmorSet(900.0F, 300.0F, 120.0F, 60.0F, 40.0F);
        ztzChemical.hull = new ArmorSet(900.0F, 250.0F, 100.0F, 60.0F, 40.0F);
        ztzChemical.damage_multiplier = new DamageMultiplier(4.0F, 4.0F, 4.0F, 4.0F, 4.0F);
        ztzChemical.turret_weak_spot = new Plate(450.0F, 6.0F);
        ztzChemical.hull_front_split = new FrontSplitPlates();
        ztzChemical.hull_front_split.upper = new Plate(900.0F, 4.0F);
        ztzChemical.hull_front_split.lower = new Plate(350.0F, 4.0F);
        ztzChemical.track_ends = new Plate(80.0F, 2.0F);
        ztzChemical.part_armor.put("commander_machine_gun", new Plate(60.0F, 4.0F));
        ztzChemical.part_armor.put("turret_machine_gun", new Plate(40.0F, 4.0F));
        ztzChemical.part_armor.put("aps_left", new Plate(60.0F, 4.0F));
        ztzChemical.part_armor.put("aps_right", new Plate(60.0F, 4.0F));
        ztzChemical.part_armor.put("turret_smoke_grenade", new Plate(40.0F, 4.0F));
        ztz.overrides.put("heat", ztzChemical);
        ztz.overrides.put("he", thinRoofs(ztzChemical, 40.0F, 30.0F, 40.0F, 30.0F));

        // The station, coax and APS mounts are thin steel and glass; the smoke bank is the same.
        ztz.part_armor.put("commander_machine_gun", new Plate(30.0F, 5.0F));
        ztz.part_armor.put("turret_machine_gun", new Plate(20.0F, 5.0F));
        ztz.part_armor.put("aps_left", new Plate(40.0F, 5.0F));
        ztz.part_armor.put("aps_right", new Plate(40.0F, 5.0F));
        ztz.part_armor.put("turret_smoke_grenade", new Plate(20.0F, 5.0F));

        ztz.turret_part_ids = new String[]{
                "turret",
                "commander_machine_gun",
                "turret_machine_gun",
                "aps_left",
                "aps_right",
                "turret_smoke_grenade"
        };

        // The ZTZ-100 is the pack's unmanned-turret tank: all three crew sit in the hull, so its
        // gunner's and commander's seats must not turn with the remote mount. The pack's seat
        // models are parented to the hull for the same reason.
        ztz.hull_seat_ids = List.of("turret", "commander_machine_gun");

        ztz.fording = new Fording.Kit();
        p.vehicles.put("armor_pioneer:ztz100", ztz);

        // The 105mm dart. No source publishes an RHAe figure for the ZTZ-100's round; 720 is the
        // accepted estimate - above the VT-5's BTA-2 (550), which is an older 105mm mark, and below
        // the 120mm/125mm darts this table rates at 600-780. The pack's cannon is a single-round
        // loader, so there is no load list to carry.
        // The pack's own description gives the round 1706 m/s; 4.3 kg is a 105mm sub-projectile
        // on the VT-5's line.
        Ammo ztzDart = new Ammo(720.0F);
        ztzDart.mass_kg = 4.3F;
        ztzDart.muzzle_velocity = 1706.0F;
        ztzDart.caliber_mm = 105.0F;
        p.ammo_penetration.put("armor_pioneer:cannon_105mm_ztz100",
                shelf(ztzDart, AmmoCategory.CANNON_AP));
    }

    /**
     * The Leopard 2A4, the Armor Pioneer pack's Bundeswehr MBT and the first vehicle added after
     * the ZTZ-100.
     *
     * <p><b>Armour.</b> The 2A4's composite array is the 1980s B-technology package; no primary
     * source publishes its RHAe, and the widely repeated figures put the turret front at roughly
     * 420 mm against KE and 800 mm against shaped charges, with the hull glacis lower. The draft
     * below sits between the VT-4 and the K2 and is deliberately conservative - it is meant to be
     * corrected with the in-game armour scanner, like every other draft in this table. The combat
     * mass is 55.2 t and the engine the published 1,500 hp MTU MB 873.
     *
     * <p><b>Geometry, measured from the pack's structure file.</b> Hull centre (+0.00, +1.56,
     * +0.30) half (1.34, 0.97, 4.49); turret centre (+0.01, +2.89, +0.30) half (1.77, 0.78, 2.81).
     * The structure tool derives the gun-mount offset from the barrel box as (+0.02, -0.18), hence
     * the weak-spot offset. The track threshold is 0.92, below the measured inner face 1.245, so the
     * whole track strip is caught; the running gear is still the K2's.
     *
     * <p><b>Armament.</b> The pack's shared 120 mm family (K279/K277/K280) is reused, so the
     * Leopard inherits the armour addon's existing dart and shaped-charge ratings rather than
     * introducing a fourth set of 120 mm ids. The coax and the commander's 7.62 mm machine guns
     * (the pack's FN-MAG) use the pack's 7.62 mm machine-gun rating.
     */
    private static void addLeopard2a4(Profile p) {
        VehicleArmor leopard = new VehicleArmor();
        leopard.turret = new ArmorSet(420.0F, 80.0F, 60.0F, 40.0F, 30.0F);
        leopard.hull = new ArmorSet(350.0F, 60.0F, 50.0F, 35.0F, 30.0F);
        leopard.damage_multiplier = new DamageMultiplier(10.0F, 5.0F, 5.0F, 5.0F, 5.0F);
        // B-technology composite, published as equivalents along the front - the same situation
        // as the K2's, so the same per-hit direction factor rather than stated steel slopes.
        leopard.kinetic_angle_scaling = Boolean.TRUE;

        // 1,500 hp MTU MB 873 at 55.2 tonnes: 27.2 hp/t, the Leopard 2A4's published pair.
        leopard.engine_horsepower = 1500.0F;
        leopard.horsepower_per_ton = 27.2F;

        // The 120 mm bore in the 420 mm face. The offsets are the measured gun axis; the radius
        // matches the other Armor Pioneer tanks so the mantlet is hittable.
        leopard.turret_weak_spot = new WeakSpot();
        leopard.turret_weak_spot.thickness = 250.0F;
        leopard.turret_weak_spot.multiplier = 8.0F;
        leopard.turret_weak_spot.radius = 0.35F;
        leopard.turret_weak_spot.offset_x = 0.0F;
        leopard.turret_weak_spot.offset_y = -0.18F;

        // The glacis takes the upper part of the face; the lower plate is thinner, as on the
        // other MBTs here. A negative split keeps the larger plate on top.
        leopard.hull_front_split = new FrontSplit();
        leopard.hull_front_split.split = -0.2F;
        leopard.hull_front_split.upper = new Plate(350.0F, 10.0F);
        leopard.hull_front_split.lower = new Plate(180.0F, 10.0F);

        leopard.track_ends = new TrackEnds();
        leopard.track_ends.thickness = 60.0F;
        leopard.track_ends.multiplier = 1.5F;
        leopard.track_ends.inner_edge = 0.92F;
        leopard.track_ends.top = 0.0F;

        // The flank kit, keyed by parts rather than stated as a rule on the side: the model
        // carries it as `hull_addition_armor` / `hull_addition_armor2`, one box a flank, so a
        // round can land on a plate and the box decides where the plates are. That is the T-90M's
        // `era_1` / `era_2` shape, and it is why the kit covers the forward half of each flank
        // and nothing else. One hit spends a flank - the same one-use rule as every reactive
        // panel here - and the next round at that spot meets the hull side behind it. The two
        // sides are independent: spending the left leaves the right whole.
        leopard.part_armor.put("hull_addition_armor", new Plate(100.0F, 5.0F).reactiveHp(100.0F));
        leopard.part_armor.put("hull_addition_armor2", new Plate(100.0F, 5.0F).reactiveHp(100.0F));

        ArmorOverride leopardChemical = new ArmorOverride();
        leopardChemical.turret = new ArmorSet(800.0F, 250.0F, 100.0F, 60.0F, 50.0F);
        leopardChemical.hull = new ArmorSet(650.0F, 150.0F, 80.0F, 50.0F, 40.0F);
        leopardChemical.damage_multiplier = new DamageMultiplier(6.0F, 4.0F, 4.0F, 4.0F, 4.0F);
        leopardChemical.turret_weak_spot = new Plate(400.0F, 6.0F);
        leopardChemical.hull_front_split = new FrontSplitPlates();
        leopardChemical.hull_front_split.upper = new Plate(650.0F, 6.0F);
        leopardChemical.hull_front_split.lower = new Plate(250.0F, 6.0F);
        leopardChemical.track_ends = new Plate(80.0F, 2.0F);
        leopardChemical.part_armor.put("commander_machine_gun", new Plate(60.0F, 4.0F));
        leopardChemical.part_armor.put("turret_machine_gun", new Plate(40.0F, 4.0F));
        leopardChemical.part_armor.put("turret_smoke_grenade", new Plate(40.0F, 4.0F));
        // The same two boxes against a jet: 300 mm, half of the T-90M's Relikt figure and three
        // times this box's own dart figure. The kit is a thin plate package, not a Relikt array,
        // so it is set by what it is worth against the warhead it was made for rather than by
        // keeping the two tables in the same ratio. The multiplier on each plate is the flank's
        // own and is a table figure only: a reactive box hands the penetrating round on to the
        // zone behind it, so the damage multiplier that applies is that zone's, not the box's.
        leopardChemical.part_armor.put("hull_addition_armor", new Plate(300.0F, 4.0F).reactiveHp(300.0F));
        leopardChemical.part_armor.put("hull_addition_armor2", new Plate(300.0F, 4.0F).reactiveHp(300.0F));
        leopard.overrides.put("heat", leopardChemical);
        leopard.overrides.put("he", thinRoofs(leopardChemical, 40.0F, 30.0F, 40.0F, 30.0F));

        leopard.part_armor.put("commander_machine_gun", new Plate(30.0F, 5.0F));
        leopard.part_armor.put("turret_machine_gun", new Plate(20.0F, 3.0F));
        leopard.part_armor.put("turret_smoke_grenade", new Plate(20.0F, 5.0F));

        // The Leopard 2A4's fourth seat is a real, manual loader. The 120 mm L/44's 6-second
        // cycle is the trained loader's; without one the gun halves its rate (12 s). The loader
        // rides in the turret, so CrewRoles treats the configured loader seat as turret crew -
        // that is a crew-compartment question, not an armour-part one, so the seat is not added
        // to turret_part_ids.
        leopard.loader_seat_id = "loader_seat";
        leopard.turret_part_ids = new String[]{
                "turret",
                "commander_machine_gun",
                "turret_machine_gun",
                "turret_smoke_grenade"
        };

        // The Leopard 2A4's own 120 mm Rh-120 L/44 ammunition, not the K2's Korean
        // K279/K277/K280. DM33 is the service APFSDS, set at 480 mm: the midpoint of the
        // published pair (Multicrew's L/44 table gives 446 mm at 2 km and 485 mm at the muzzle),
        // which is the round's own choice of range. DM23 is the older ~400 mm mark and is the
        // load variant. DM12 HEAT-MP is rated 480 mm (Multicrew's DM12A1 table). DM11 is the HE
        // shell, so its 60 mm is the addon's usual HE penetration figure, not an anti-tank claim.
        // The projectiles: DM33 is a 7.6 kg sub-projectile at 1650 m/s out of the L/44, DM23 the
        // 7.2 kg earlier mark at the same muzzle velocity (both published as they left the tube).
        Ammo dm33 = new Ammo(480.0F);
        dm33.mass_kg = 7.6F;
        dm33.muzzle_velocity = 1650.0F;
        dm33.caliber_mm = 120.0F;
        Ammo dm23 = kineticVariant("DM23 APFSDS", 400.0F);
        dm23.mass_kg = 7.2F;
        dm23.muzzle_velocity = 1650.0F;
        dm23.caliber_mm = 120.0F;
        dm33.variants = new java.util.ArrayList<>(java.util.List.of(dm23));
        p.ammo_penetration.put("armor_pioneer:cannon_120mm_dm33",
                shelf(dm33, AmmoCategory.CANNON_AP));
        Ammo dm12 = shapedCharge(480.0F);
        dm12.heat_damage = 1736.0F;
        p.ammo_penetration.put("armor_pioneer:cannon_120mm_dm12",
                shelf(dm12, AmmoCategory.CANNON_HEAT));
        p.ammo_penetration.put("armor_pioneer:cannon_120mm_dm11",
                shelf(chargeShell(60.0F, 3.5F), AmmoCategory.CANNON_HE));
        // The FN-MAG is the Leopard's 7.62 mm coax/commander gun - one weapon id, fired by both
        // the turret's coax and the commander's RWS. Its id is still `mg3_7_62mm`, which is why
        // the pack's weapon file kept that name: the gun was renamed, the id was not, because
        // the id is this table's key and a config key that moves needs a migration. Same 15 mm/3x
        // machine-gun rating as every other rifle-calibre mount here.
        p.ammo_penetration.put("armor_pioneer:mg3_7_62mm",
                shelf(new Ammo(15.0F, 3.0F), AmmoCategory.MACHINE_GUN));

        leopard.fording = new Fording.Kit();
        p.vehicles.put("armor_pioneer:leopard2a4", leopard);
    }

    /**
     * A shaped charge: the usual residual-penetration formula, plus a small blast that no armour
     * stops. The blast also flashes the crew's eyes for a second - the one thing that makes a
     * non-penetrating hit worth firing.
     *
     * <p>It also replaces the pack's own explosion, which is authored as raw damage and knows
     * nothing about armour; left alone it lands on every hit and drowns out the whole table.
     */
    private static Ammo shapedCharge(float penetration) {
        Ammo round = new Ammo(penetration);
        round.profile = "heat";
        round.spall_damage_min = 1.5F;
        round.spall_damage_max = 3.0F;
        round.spall_blindness_ticks = 20;
        round.replaces_base_explosion = Boolean.TRUE;
        return round;
    }

    // ------------------------------------------------------------------ aircraft

    /**
     * The three fixed-wing aircraft in the {@code tank_assault} pack.
     *
     * <p>They are the first vehicles in this file that are not described by a plate. A fighter has
     * no glacis and no turret front: it has a skin a rifle round goes through, and the question
     * that matters is what the round finds on the other side. See {@link AircraftArmor} for the
     * rule and {@link ArmorSystem} for the resolution, and {@code AircraftCheck} for the
     * measurements these boxes come from.
     *
     * <p>Every box below was measured off the pack's structure model, in blocks from the centre of
     * the vehicle's {@code main_structure} cube, on that cube's axes (x right, y up, z nose). The
     * pilot and fuel volumes are the sizes of the parts the model carries, widened enough to be
     * the compartment rather than a single cube; the engine is the nose the model draws between the
     * cowling and the cockpit; the seat back and windscreen are the two armour plates, one behind
     * and one in front of the pilot. None of the three models carries a piece of armour thicker than
     * the seat's, which is why the components - not the skin - are what a hit is really about.
     *
     * <p>The figures are estimates against public descriptions, deliberately conservative on the
     * skin: a fighter's covering is 1.5-3mm, its seat back 8-12mm, its windscreen 40-50mm of
     * armoured glass. No source publishes a per-box table for a 1940s fighter, so the README marks
     * them as estimates and the check prints what they add up to rather than pretending otherwise.
     */
    private static void addWw2Aircraft(Profile p) {
        p.vehicles.put("tank_assault:bf109e4", bf109e4());
        p.vehicles.put("tank_assault:fw190a5", fw190a5());
        p.vehicles.put("tank_assault:p51d20", p51d20());
    }

    /** Bf 109E-4: the lightest airframe in the pack, and the one with the least seat armour. */
    private static VehicleArmor bf109e4() {
        AircraftComponent pilot = component("pilot", "pilot",
                new float[]{0.00F, 0.95F, 0.30F}, new float[]{0.24F, 0.32F, 0.25F});
        pilot.damage_multiplier = 8.0F;
        pilot.damage = 25.0F;
        pilot.crew_damage = 4.0F;
        pilot.crew_damage_per_mm = 0.25F;

        // The DB 601 sits in the nose. Six millimetres is the engine block and its accessories
        // rather than an armoured plate; the figure is what makes a rifle round reach it and a
        // cannon round hurt it.
        AircraftComponent engine = component("engine", null,
                new float[]{0.00F, 0.55F, 2.50F}, new float[]{0.50F, 0.45F, 0.65F});
        engine.armor = 6.0F;
        engine.damage_multiplier = 5.0F;
        engine.damage = 30.0F;

        // The fuselage tank under the cockpit floor, measured from the pack's own fuel_tank part.
        AircraftComponent fuel = component("fuel tank", "fuel_tank",
                new float[]{0.03F, -0.49F, 1.04F}, new float[]{0.54F, 0.39F, 1.04F});
        fuel.damage_multiplier = 4.0F;
        fuel.damage = 60.0F;

        return aircraft(new ArmorSet(1.5F, 1.5F, 1.5F, 1.5F, 2.0F),
                new DamageMultiplier(2.0F, 2.0F, 2.0F, 1.5F, 2.5F), 0.60F,
                1100.0F, 407.4F,
                pilot, engine, fuel,
                armour("seat armour", 8.0F,
                        new float[]{0.00F, 0.95F, -0.11F}, new float[]{0.24F, 0.32F, 0.06F}),
                armour("windscreen", 25.0F,
                        new float[]{0.00F, 1.10F, 0.63F}, new float[]{0.22F, 0.22F, 0.04F}));
    }

    /** Fw 190A-5: the radial engine is a big target and the seat back is the thickest here. */
    private static VehicleArmor fw190a5() {
        AircraftComponent pilot = component("pilot", "pilot",
                new float[]{0.00F, 1.20F, -0.05F}, new float[]{0.24F, 0.28F, 0.25F});
        pilot.damage_multiplier = 8.0F;
        pilot.damage = 25.0F;
        pilot.crew_damage = 4.0F;
        pilot.crew_damage_per_mm = 0.25F;

        AircraftComponent engine = component("engine", null,
                new float[]{0.00F, 0.50F, 2.20F}, new float[]{0.50F, 0.42F, 0.70F});
        engine.armor = 8.0F;
        engine.damage_multiplier = 5.0F;
        engine.damage = 30.0F;

        // The pack's fuel_tank part has no structure bone on this model, so the volume was measured
        // from the fuselage behind the cockpit instead of from the part. It still points at the
        // part id, which is what takes the damage.
        AircraftComponent fuel = component("fuel tank", "fuel_tank",
                new float[]{0.00F, 0.45F, -1.35F}, new float[]{0.28F, 0.35F, 0.55F});
        fuel.damage_multiplier = 4.0F;
        fuel.damage = 60.0F;

        return aircraft(new ArmorSet(1.5F, 1.5F, 1.5F, 1.5F, 2.0F),
                new DamageMultiplier(2.0F, 2.0F, 2.0F, 1.5F, 2.5F), 0.60F,
                1700.0F, 425.0F,
                pilot, engine, fuel,
                armour("seat armour", 13.0F,
                        new float[]{0.00F, 1.15F, -0.40F}, new float[]{0.24F, 0.30F, 0.06F}),
                armour("windscreen", 50.0F,
                        new float[]{0.00F, 1.30F, 0.42F}, new float[]{0.24F, 0.22F, 0.04F}));
    }

    /** P-51D-20: six .50s and the most health, with the fuel tank wrapping the cockpit's belly. */
    private static VehicleArmor p51d20() {
        AircraftComponent pilot = component("pilot", "pilot",
                new float[]{0.00F, 0.90F, -0.40F}, new float[]{0.24F, 0.28F, 0.22F});
        pilot.damage_multiplier = 8.0F;
        pilot.damage = 25.0F;
        pilot.crew_damage = 4.0F;
        pilot.crew_damage_per_mm = 0.25F;

        // The Merlin is ahead of the cockpit and behind the spinner: the pack's fuselage runs from
        // -6.69 to +2.38 blocks in structure z, the main cube's centre is at -0.94, so the nose's
        // own frame is z +1.8 .. +3.3. The first draft put this box at +0.6 .. +1.8, which is the
        // wing centre section - the check's airframe bound is what caught it.
        AircraftComponent engine = component("engine", null,
                new float[]{0.00F, 0.10F, 2.48F}, new float[]{0.50F, 0.45F, 0.62F});
        engine.armor = 6.0F;
        engine.damage_multiplier = 5.0F;
        engine.damage = 30.0F;

        AircraftComponent fuel = component("fuel tank", "fuel_tank",
                new float[]{0.00F, 0.12F, -0.31F}, new float[]{0.39F, 0.39F, 0.95F});
        fuel.damage_multiplier = 4.0F;
        fuel.damage = 60.0F;

        return aircraft(new ArmorSet(1.5F, 1.5F, 1.5F, 1.5F, 2.0F),
                new DamageMultiplier(2.0F, 2.0F, 2.0F, 1.5F, 2.5F), 0.60F,
                1490.0F, 430.0F,
                pilot, engine, fuel,
                armour("seat armour", 8.0F,
                        new float[]{0.00F, 0.90F, -0.70F}, new float[]{0.24F, 0.28F, 0.06F}),
                armour("windscreen", 38.0F,
                        new float[]{0.00F, 0.95F, -0.10F}, new float[]{0.24F, 0.22F, 0.04F}));
    }

    /**
     * Builds one aircraft's armour block.
     *
     * <p>{@code hull} and {@code turret} are set to the skin as well, which is what makes the
     * block safe to delete: without it the old table's 440mm would be what a round met, and the
     * difference between "I removed the aircraft model" and "I gave this fighter a battleship's
     * glacis" would be invisible in the file. {@code turret_part_ids} is empty on purpose - an
     * aircraft has no turret - and the sanitizer no longer fills an empty list.
     */
    private static VehicleArmor aircraft(ArmorSet skin, DamageMultiplier frame, float wingFrom,
                                         float horsepower, float horsepowerPerTon,
                                         AircraftComponent... components) {
        VehicleArmor vehicle = new VehicleArmor();
        vehicle.hull = new ArmorSet(skin.front, skin.side, skin.rear, skin.roof, skin.floor);
        vehicle.turret = new ArmorSet(skin.front, skin.side, skin.rear, skin.roof, skin.floor);
        vehicle.damage_multiplier = new DamageMultiplier(frame.front, frame.side, frame.rear,
                frame.roof, frame.floor);
        vehicle.engine_horsepower = horsepower;
        vehicle.horsepower_per_ton = horsepowerPerTon;
        vehicle.turret_part_ids = new String[0];
        vehicle.aircraft = new AircraftArmor();
        vehicle.aircraft.skin = skin;
        vehicle.aircraft.frame_multiplier = frame;
        vehicle.aircraft.wing = new Plate(1.5F, 1.5F);
        vehicle.aircraft.wing_from = wingFrom;
        for (AircraftComponent component : components) {
            vehicle.aircraft.components.add(component);
        }
        return vehicle;
    }

    private static AircraftComponent component(String name, String partId,
                                               float[] center, float[] half) {
        AircraftComponent component = new AircraftComponent();
        component.name = name;
        component.part_id = partId;
        component.center = center;
        component.half = half;
        return component;
    }

    /** A pure armour layer: it stops what it can and does nothing to what it cannot. */
    private static AircraftComponent armour(String name, float thickness,
                                            float[] center, float[] half) {
        AircraftComponent component = component(name, null, center, half);
        component.armor = thickness;
        return component;
    }

    /**
     * 3OF82 "Telnik" (3VOF128 as a round), the airburst shell the 2A46M-5 can be fed instead of the
     * standard high-explosive one: it is detonated at a set point on the trajectory and throws out
     * 5.4kg of fragments and ready-made elements, which is what makes it six to eight times as
     * effective against infantry as the shell it replaces.
     *
     * <p><b>Only the fragment weight is modelled here.</b> The six-to-eight-times figure is against
     * personnel, and this addon measures everything against armour - a tank's crew is not something
     * it can see, let alone count. So the round is a somewhat heavier blast and nothing more, and
     * the airburst itself is not modelled at all: the base mod's proximity fuze belongs to the
     * weapon the gun fires, not to the load inside it, so a load cannot switch one on. Giving this
     * round its own slot in the vehicle's weapon list - the way the T-64's HE-VT has one - is what
     * that would take.
     *
     * <p>The design gives no figure for it, so what is here is the shape of the real round: the
     * same penetration and the same burst inside, a larger blast outside.
     */
    private static Ammo telnik() {
        Ammo round = new Ammo(60.0F);
        round.name = "3OF82 Telnik";
        round.profile = "he";
        round.penetration_damage = 2000.0F;
        round.spall_damage_min = 400.0F;
        round.spall_damage_max = 1400.0F;
        round.spall_falloff_to = 900.0F;
        round.spall_on_penetration = Boolean.FALSE;
        round.spall_blindness_ticks = 20;
        round.replaces_base_explosion = Boolean.TRUE;
        return round;
    }

    // ---------------------------------------------------- Tank Assault, the WW2 ground vehicles

    /**
     * The rest of the {@code tank_assault} pack's ground vehicles.
     *
     * <p><b>Regraded in v91 against the published plates and gun tables.</b> The rules are stated
     * once here because they are what makes the table comparable across vehicles:
     *
     * <ul>
     *   <li>the kinetic face value is the plate's <em>line-of-sight</em> thickness - nominal
     *       thickness divided by the cosine of the slope from vertical - rounded to the nearest
     *       millimetre. A vertical plate is its nominal figure. This is the rule the T-34's
     *       45mm at 60 degrees already followed (90mm).</li>
     *   <li>the chemical ({@code heat}) table keeps the <em>nominal</em> plate: a shaped-charge jet
     *       is not defeated by slope the way a kinetic round is.</li>
     *   <li>penetration is the round's 0-degree figure at 500 m, the basis the pack's British and
     *       Soviet entries already used. HEAT is its rated normal penetration.</li>
     * </ul>
     *
     * <p>Sources: the type's English/Russian Wikipedia infobox and the armour breakdowns at
     * Quartermaster Section, Tank Encyclopedia, Panzerworld, onwar and battlefield.ru; the gun
     * tables are the 0-degree 500 m figures those pages quote. A figure no source gives - a turret
     * floor, mostly, because most turrets were floored by the hull roof - is marked as an estimate
     * in the vehicle's own comment rather than quietly filled in.
     *
     * <p>Soft-skinned vehicles carry no armour at all: a Kettenkrad, an R75 and a Kuebelwagen are
     * sheet steel and canvas, so their tables are zero. The two towed guns carry only their shield.
     *
     * <p>A casemate has its superstructure inside {@code main_structure}, so its {@code turret}
     * table is a copy of the hull's: the part the model calls {@code turret} is still rated as
     * turret, and a hit on it reads the same plate as a hit on the hull beside it.
     */
    private static void addTankAssaultGround(Profile p) {
        // --- British -------------------------------------------------------------------------
        // Churchill Mk VII: 152mm turret and driver's plate, 95mm flanks, 51mm tail. QMS gives the
        // hull roof 25 and floor 19; the turret roof 20. The turret floor was floored by the hull
        // roof, so 25 is the estimate.
        VehicleArmor a22f = ww2(new String[]{"turret", "turret_machine_gun", "turret_smoke_grenade"},
                new ArmorSet(152.0F, 95.0F, 95.0F, 20.0F, 25.0F),
                new ArmorSet(152.0F, 95.0F, 51.0F, 25.0F, 19.0F),
                new ArmorSet(152.0F, 95.0F, 95.0F, 20.0F, 25.0F),
                new ArmorSet(152.0F, 95.0F, 51.0F, 25.0F, 19.0F));
        gunMount(a22f, 0.070F, -0.103F, 130.0F);
        mgPort(a22f, -0.298F, 0.280F, 100.0F);
        trackEnds(a22f, 95.0F, 0.67F);
        a22f.engine_horsepower = 350.0F;
        a22f.horsepower_per_ton = 8.6F;
        p.vehicles.put("tank_assault:a22f", a22f);

        // Matilda I: 65mm turret, 60mm hull front and sides, 50mm hull rear, 12mm roof. The single
        // Vickers machine gun is the whole armament.
        VehicleArmor matilda = ww2(new String[]{"turret"},
                new ArmorSet(65.0F, 65.0F, 65.0F, 10.0F, 10.0F),
                new ArmorSet(60.0F, 60.0F, 50.0F, 12.0F, 10.0F),
                new ArmorSet(65.0F, 65.0F, 65.0F, 10.0F, 10.0F),
                new ArmorSet(60.0F, 60.0F, 50.0F, 12.0F, 10.0F));
        gunMount(matilda, 0.075F, -0.078F, 65.0F);
        trackEnds(matilda, 60.0F, 0.81F);
        matilda.engine_horsepower = 70.0F;
        matilda.horsepower_per_ton = 6.3F;
        p.vehicles.put("tank_assault:matilda_mk1", matilda);

        // Valentine Mk II: 65mm turret front and rear, 60mm hull sides and rear, 7mm hull floor.
        VehicleArmor valentine = ww2(new String[]{"turret", "turret_machine_gun"},
                new ArmorSet(65.0F, 60.0F, 65.0F, 20.0F, 15.0F),
                new ArmorSet(60.0F, 60.0F, 60.0F, 20.0F, 7.0F),
                new ArmorSet(65.0F, 60.0F, 65.0F, 20.0F, 15.0F),
                new ArmorSet(60.0F, 60.0F, 60.0F, 20.0F, 7.0F));
        gunMount(valentine, 0.007F, -0.089F, 60.0F);
        trackEnds(valentine, 60.0F, 0.85F);
        valentine.engine_horsepower = 131.0F;
        valentine.horsepower_per_ton = 8.2F;
        p.vehicles.put("tank_assault:valentine_mk2", valentine);

        // 17-pounder: a towed anti-tank gun. The shield's 8mm is an estimate - no source states it.
        VehicleArmor qf17 = ww2(new String[0],
                new ArmorSet(8.0F, 8.0F, 8.0F, 2.0F, 2.0F),
                new ArmorSet(8.0F, 8.0F, 8.0F, 2.0F, 2.0F),
                new ArmorSet(8.0F, 8.0F, 8.0F, 2.0F, 2.0F),
                new ArmorSet(8.0F, 8.0F, 8.0F, 2.0F, 2.0F));
        roadWheels(qf17, 5.0F, 1.0F);
        p.vehicles.put("tank_assault:qf17pdr", qf17);

        // 25-pounder: the field gun on the pack's tracked carriage. The same estimated shield.
        VehicleArmor qf25 = ww2(new String[]{"turret"},
                new ArmorSet(8.0F, 8.0F, 8.0F, 2.0F, 2.0F),
                new ArmorSet(8.0F, 8.0F, 8.0F, 2.0F, 2.0F),
                new ArmorSet(8.0F, 8.0F, 8.0F, 2.0F, 2.0F),
                new ArmorSet(8.0F, 8.0F, 8.0F, 2.0F, 2.0F));
        trackEnds(qf25, 5.0F, 0.73F);
        p.vehicles.put("tank_assault:qf25", qf25);

        // --- German --------------------------------------------------------------------------
        // Panzer IV Ausf. H: 82mm glacis (80 @ 14 degrees), 50mm turret front plus a 50mm mantlet,
        // 33mm turret sides (30 @ 26 degrees), 30mm hull sides, 20mm tail. The Schuerzen are not
        // modelled as parts, so the 30mm is the plate a round actually meets.
        VehicleArmor panzer4 = ww2(new String[]{"turret", "coax"},
                new ArmorSet(50.0F, 33.0F, 31.0F, 15.0F, 10.0F),
                new ArmorSet(82.0F, 30.0F, 20.0F, 12.0F, 10.0F),
                new ArmorSet(50.0F, 30.0F, 30.0F, 15.0F, 10.0F),
                new ArmorSet(80.0F, 30.0F, 20.0F, 12.0F, 10.0F));
        gunMount(panzer4, -0.001F, -0.009F, 50.0F);
        trackEnds(panzer4, 30.0F, 0.82F);
        panzer4.engine_horsepower = 300.0F;
        panzer4.horsepower_per_ton = 12.0F;
        p.vehicles.put("tank_assault:panzer4h", panzer4);

        // Ferdinand: the casemate front is 200 @ 20 degrees (213 LOS); the upper nose is the same
        // 200, the lower nose 80 @ 45 degrees (113 LOS); flanks 80 @ 20-28 degrees (85 LOS).
        VehicleArmor ferdinand = ww2(new String[]{"turret"},
                new ArmorSet(215.0F, 85.0F, 85.0F, 30.0F, 20.0F),
                new ArmorSet(215.0F, 85.0F, 85.0F, 30.0F, 20.0F),
                new ArmorSet(200.0F, 80.0F, 80.0F, 30.0F, 20.0F),
                new ArmorSet(200.0F, 80.0F, 80.0F, 30.0F, 20.0F));
        frontSplit(ferdinand, 0.0F, 215.0F, 10.0F, 115.0F, 8.0F);
        mgPort(ferdinand, 0.625F, -0.080F, 100.0F);
        trackEnds(ferdinand, 80.0F, 0.79F);
        ferdinand.engine_horsepower = 600.0F;
        ferdinand.horsepower_per_ton = 9.2F;
        p.vehicles.put("tank_assault:ferdinand", ferdinand);

        // Sturmtiger: the casemate front is 150 @ 45-47 degrees (about 215 LOS), flanks 80, roof
        // 40, floor 25; the lower hull nose is 100mm.
        VehicleArmor sturmtiger = ww2(new String[]{"turret"},
                new ArmorSet(215.0F, 80.0F, 80.0F, 40.0F, 25.0F),
                new ArmorSet(215.0F, 80.0F, 80.0F, 40.0F, 25.0F),
                new ArmorSet(150.0F, 80.0F, 80.0F, 40.0F, 25.0F),
                new ArmorSet(150.0F, 80.0F, 80.0F, 40.0F, 25.0F));
        frontSplit(sturmtiger, 0.0F, 215.0F, 10.0F, 100.0F, 8.0F);
        mgPort(sturmtiger, 1.195F, 0.284F, 100.0F);
        trackEnds(sturmtiger, 80.0F, 0.78F);
        sturmtiger.engine_horsepower = 700.0F;
        sturmtiger.horsepower_per_ton = 10.8F;
        p.vehicles.put("tank_assault:sturmtiger", sturmtiger);

        // Sd.Kfz.251: a 15mm nose, 8mm sides, no roof, 6mm floor. The MG shield is 4mm per the
        // Quartermaster breakdown, not the 10mm the first pass guessed.
        VehicleArmor sdkfz251 = ww2(new String[]{"turret"},
                new ArmorSet(4.0F, 4.0F, 4.0F, 0.0F, 0.0F),
                new ArmorSet(15.0F, 8.0F, 8.0F, 0.0F, 6.0F),
                new ArmorSet(4.0F, 4.0F, 4.0F, 0.0F, 0.0F),
                new ArmorSet(15.0F, 8.0F, 8.0F, 0.0F, 6.0F));
        gunMount(sdkfz251, 0.000F, 0.105F, 4.0F);
        trackEnds(sdkfz251, 8.0F, 0.79F);
        sdkfz251.engine_horsepower = 100.0F;
        sdkfz251.horsepower_per_ton = 11.8F;
        p.vehicles.put("tank_assault:sdkfz251", sdkfz251);

        // Sd.Kfz.2 Kettenkrad: not armoured and not armed. The table is zero, not 2mm: a round
        // that reaches it goes through the bodywork.
        VehicleArmor kettenkrad = ww2(new String[0],
                new ArmorSet(0.0F, 0.0F, 0.0F, 0.0F, 0.0F),
                new ArmorSet(0.0F, 0.0F, 0.0F, 0.0F, 0.0F),
                new ArmorSet(0.0F, 0.0F, 0.0F, 0.0F, 0.0F),
                new ArmorSet(0.0F, 0.0F, 0.0F, 0.0F, 0.0F));
        trackEnds(kettenkrad, 3.0F, 0.75F);
        kettenkrad.engine_horsepower = 36.0F;
        kettenkrad.horsepower_per_ton = 30.0F;
        p.vehicles.put("tank_assault:sdkfz2", kettenkrad);

        // BMW R75: an unarmoured motorcycle and sidecar with an optional MG 34.
        VehicleArmor r75 = ww2(new String[0],
                new ArmorSet(0.0F, 0.0F, 0.0F, 0.0F, 0.0F),
                new ArmorSet(0.0F, 0.0F, 0.0F, 0.0F, 0.0F),
                new ArmorSet(0.0F, 0.0F, 0.0F, 0.0F, 0.0F),
                new ArmorSet(0.0F, 0.0F, 0.0F, 0.0F, 0.0F));
        r75.engine_horsepower = 26.0F;
        r75.horsepower_per_ton = 37.1F;
        p.vehicles.put("tank_assault:bmw_r75", r75);

        // VW Type 82 Kuebelwagen: sheet steel, not armour, and no weapon.
        VehicleArmor type82 = ww2(new String[0],
                new ArmorSet(0.0F, 0.0F, 0.0F, 0.0F, 0.0F),
                new ArmorSet(0.0F, 0.0F, 0.0F, 0.0F, 0.0F),
                new ArmorSet(0.0F, 0.0F, 0.0F, 0.0F, 0.0F),
                new ArmorSet(0.0F, 0.0F, 0.0F, 0.0F, 0.0F));
        type82.engine_horsepower = 25.0F;
        type82.horsepower_per_ton = 21.6F;
        p.vehicles.put("tank_assault:type82", type82);

        // --- Soviet --------------------------------------------------------------------------
        // BT-7: 22mm sloped nose, 15mm flanks, a 15mm turret all round and the 45mm 20-K.
        VehicleArmor bt7 = ww2(new String[]{"turret", "turret_machine_gun", "rear_ball_mg"},
                new ArmorSet(15.0F, 15.0F, 15.0F, 10.0F, 10.0F),
                new ArmorSet(22.0F, 15.0F, 15.0F, 10.0F, 6.0F),
                new ArmorSet(15.0F, 15.0F, 15.0F, 10.0F, 10.0F),
                new ArmorSet(20.0F, 15.0F, 15.0F, 10.0F, 6.0F));
        gunMount(bt7, -0.008F, 0.097F, 15.0F);
        trackEnds(bt7, 15.0F, 0.85F);
        bt7.engine_horsepower = 400.0F;
        bt7.horsepower_per_ton = 28.8F;
        p.vehicles.put("tank_assault:bt7v", bt7);

        // T-26 Model 1933: 15mm all round, the same 45mm gun, and a two-man turret.
        VehicleArmor t26 = ww2(new String[]{"turret", "turret_machine_gun", "rear_ball_mg"},
                new ArmorSet(15.0F, 15.0F, 15.0F, 10.0F, 6.0F),
                new ArmorSet(15.0F, 15.0F, 15.0F, 10.0F, 6.0F),
                new ArmorSet(15.0F, 15.0F, 15.0F, 10.0F, 6.0F),
                new ArmorSet(15.0F, 15.0F, 15.0F, 10.0F, 6.0F));
        gunMount(t26, -0.034F, 0.032F, 15.0F);
        trackEnds(t26, 15.0F, 0.84F);
        t26.engine_horsepower = 90.0F;
        t26.horsepower_per_ton = 9.5F;
        p.vehicles.put("tank_assault:t26b", t26);

        // KV-1 Model 1942: the standard hull is 75mm @ 30 degrees (87 LOS) - the 100mm figure is
        // the KV-1E with applique armour, not this vehicle. Cast turret: 100 front, 90 sides, 75
        // rear, 40 roof. Floor 35 is an estimate (sources give 30-40).
        VehicleArmor kv1 = ww2(new String[]{"turret", "turret_machine_gun", "rear_ball_mg"},
                new ArmorSet(100.0F, 90.0F, 75.0F, 40.0F, 30.0F),
                new ArmorSet(85.0F, 75.0F, 70.0F, 35.0F, 35.0F),
                new ArmorSet(95.0F, 90.0F, 75.0F, 40.0F, 30.0F),
                new ArmorSet(75.0F, 75.0F, 70.0F, 35.0F, 35.0F));
        gunMount(kv1, -0.023F, 0.142F, 90.0F);
        mgPort(kv1, -0.473F, 0.401F, 75.0F);
        trackEnds(kv1, 75.0F, 0.76F);
        kv1.engine_horsepower = 600.0F;
        kv1.horsepower_per_ton = 12.8F;
        p.vehicles.put("tank_assault:kv1mod1942", kv1);

        // T-34-85: 45mm at 60 degrees on the glacis (90 LOS), a 90mm turret face, 75mm sides and
        // 52mm rear. The turret floor is not published; 15 is the estimate.
        VehicleArmor t3485 = ww2(new String[]{"turret", "turret_machine_gun"},
                new ArmorSet(90.0F, 75.0F, 52.0F, 20.0F, 15.0F),
                new ArmorSet(90.0F, 45.0F, 45.0F, 20.0F, 20.0F),
                new ArmorSet(75.0F, 75.0F, 52.0F, 20.0F, 15.0F),
                new ArmorSet(45.0F, 45.0F, 45.0F, 20.0F, 20.0F));
        frontSplit(t3485, 0.0F, 90.0F, 10.0F, 45.0F, 10.0F);
        gunMount(t3485, 0.045F, -0.166F, 90.0F);
        mgPort(t3485, 0.612F, 0.145F, 45.0F);
        trackEnds(t3485, 45.0F, 0.82F);
        t3485.engine_horsepower = 500.0F;
        t3485.horsepower_per_ton = 15.6F;
        p.vehicles.put("tank_assault:t3485", t3485);

        // T-44: 90mm glacis, 75mm flanks and a 120mm turret face; the turret roof is 15 and the
        // hull floor 15, not the 30 / 20 the first pass guessed.
        VehicleArmor t44 = ww2(new String[]{"turret", "turret_machine_gun"},
                new ArmorSet(120.0F, 90.0F, 75.0F, 15.0F, 20.0F),
                new ArmorSet(90.0F, 75.0F, 45.0F, 15.0F, 20.0F),
                new ArmorSet(90.0F, 75.0F, 70.0F, 15.0F, 20.0F),
                new ArmorSet(75.0F, 75.0F, 45.0F, 15.0F, 20.0F));
        gunMount(t44, 0.028F, 0.008F, 100.0F);
        trackEnds(t44, 75.0F, 0.82F);
        t44.engine_horsepower = 500.0F;
        t44.horsepower_per_ton = 15.7F;
        p.vehicles.put("tank_assault:t44a", t44);

        // SU-122: 45mm at 50 degrees on the casemate (70 LOS), 45 @ 40 degrees on the sides
        // (58 LOS), 40 @ 48 degrees at the rear (60 LOS). The superstructure is part of
        // main_structure, so the turret table is the hull's.
        VehicleArmor su122 = ww2(new String[]{"turret"},
                new ArmorSet(70.0F, 58.0F, 60.0F, 20.0F, 15.0F),
                new ArmorSet(70.0F, 58.0F, 60.0F, 20.0F, 15.0F),
                new ArmorSet(45.0F, 45.0F, 45.0F, 20.0F, 15.0F),
                new ArmorSet(45.0F, 45.0F, 45.0F, 20.0F, 15.0F));
        gunMount(su122, 0.000F, -0.132F, 45.0F);
        trackEnds(su122, 45.0F, 0.82F);
        su122.engine_horsepower = 500.0F;
        su122.horsepower_per_ton = 16.2F;
        p.vehicles.put("tank_assault:su122", su122);

        // --- Italian -------------------------------------------------------------------------
        // P26/40: the superstructure is 50 @ 45 degrees (71 LOS), the turret front 60 @ 15 degrees
        // (62 LOS), turret sides 45 @ 25 degrees (50 LOS). Floor 14 and turret floor 15 are
        // estimates.
        VehicleArmor p40 = ww2(new String[]{"turret", "turret_machine_gun", "turret_machine_gun2"},
                new ArmorSet(62.0F, 50.0F, 50.0F, 20.0F, 15.0F),
                new ArmorSet(71.0F, 45.0F, 52.0F, 18.0F, 14.0F),
                new ArmorSet(60.0F, 45.0F, 45.0F, 20.0F, 15.0F),
                new ArmorSet(50.0F, 45.0F, 40.0F, 18.0F, 14.0F));
        gunMount(p40, -0.001F, -0.134F, 60.0F);
        trackEnds(p40, 40.0F, 0.81F);
        p40.engine_horsepower = 330.0F;
        p40.horsepower_per_ton = 12.7F;
        p.vehicles.put("tank_assault:p40", p40);

        // --- the pack's remaining rounds -------------------------------------------------------
        // The 75mm QF (Churchill VII). M61 is an APCBC-HE: 76mm of rolled steel at 0 degrees /
        // 500 m, and the small bursting charge behind it gets a roll of its own.
        p.ammo_penetration.put("tank_assault:cannon_75mm_qf75_m61",
                shelf(aphe(76.0F, 20.0F, 76.0F), AmmoCategory.CANNON_AP));
        p.ammo_penetration.put("tank_assault:cannon_75mm_qf75_m48",
                shelf(bursting(13.0F, 300.0F, 50.0F, 150.0F), AmmoCategory.CANNON_HE));
        p.ammo_penetration.put("tank_assault:gun_besa",
                shelf(new Ammo(12.0F, 3.0F), AmmoCategory.MACHINE_GUN));

        // The German leftovers: the Ferdinand's 88mm PaK 43 (203mm at 0/500), the Panzer IV's
        // KwK 40 L/48 (108mm), and the two 7.92mm mountings the pack shares.
        p.ammo_penetration.put("tank_assault:cannon_88mm_pzgr_39_43",
                shelf(aphe(203.0F, 20.0F, 203.0F), AmmoCategory.CANNON_AP));
        p.ammo_penetration.put("tank_assault:cannon_75mm_kwk40_pzgr39",
                shelf(aphe(108.0F, 20.0F, 108.0F), AmmoCategory.CANNON_AP));
        p.ammo_penetration.put("tank_assault:cannon_75mm_kwk40_sprgr34",
                shelf(bursting(13.0F, 300.0F, 50.0F, 150.0F), AmmoCategory.CANNON_HE));
        p.ammo_penetration.put("tank_assault:mg34_792",
                shelf(new Ammo(12.0F, 3.0F), AmmoCategory.MACHINE_GUN));
        p.ammo_penetration.put("tank_assault:gun_mg42",
                shelf(new Ammo(12.0F, 3.0F), AmmoCategory.MACHINE_GUN));

        // The Soviet guns: the 45mm 20-K (46mm at 0/500), the 76mm ZiS-5 (70), the 85mm ZiS-S-53
        // (111), the 122mm M-30S (HEAT 140) and the DT.
        p.ammo_penetration.put("tank_assault:cannon_45mm_20k_br_240",
                shelf(aphe(46.0F, 10.0F, 46.0F), AmmoCategory.CANNON_AP));
        p.ammo_penetration.put("tank_assault:cannon_45mm_20k_uo_240",
                shelf(bursting(13.0F, 300.0F, 50.0F, 150.0F), AmmoCategory.CANNON_HE));
        p.ammo_penetration.put("tank_assault:cannon_76mm_ubr_354a",
                shelf(aphe(70.0F, 10.0F, 70.0F), AmmoCategory.CANNON_AP));
        p.ammo_penetration.put("tank_assault:cannon_76mm_uof_354",
                shelf(bursting(13.0F, 300.0F, 50.0F, 150.0F), AmmoCategory.CANNON_HE));
        p.ammo_penetration.put("tank_assault:cannon_85mm_ubr_365",
                shelf(aphe(111.0F, 20.0F, 111.0F), AmmoCategory.CANNON_AP));
        p.ammo_penetration.put("tank_assault:cannon_85mm_uo_365",
                shelf(bursting(13.0F, 300.0F, 50.0F, 150.0F), AmmoCategory.CANNON_HE));
        p.ammo_penetration.put("tank_assault:cannon_122mm_m30_bp460a",
                shelf(shapedCharge(140.0F), AmmoCategory.CANNON_HEAT));
        // 122mm of high explosive is a different proposition from the 75mm shells: it is through a
        // 40mm roof and its bursting charge is a 122mm one, not a 75's.
        Ammo of462 = bursting(45.0F, 1200.0F, 200.0F, 600.0F);
        of462.spall_blindness_ticks = 20;
        p.ammo_penetration.put("tank_assault:cannon_122mm_m30_of462",
                shelf(of462, AmmoCategory.CANNON_HE));
        p.ammo_penetration.put("tank_assault:gun_dt29",
                shelf(new Ammo(12.0F, 3.0F), AmmoCategory.MACHINE_GUN));

        // The Italian 75mm (70mm at 0/500), its HEAT shell (100mm, the only figure found and not
        // corroborated by a second source), and the Breda.
        p.ammo_penetration.put("tank_assault:cannon_75mm_ansaldo_l34_apcbc",
                shelf(aphe(70.0F, 10.0F, 70.0F), AmmoCategory.CANNON_AP));
        p.ammo_penetration.put("tank_assault:cannon_75mm_ansaldo_l34_he",
                shelf(bursting(13.0F, 300.0F, 50.0F, 150.0F), AmmoCategory.CANNON_HE));
        p.ammo_penetration.put("tank_assault:cannon_75mm_ansaldo_l34_heat",
                shelf(shapedCharge(100.0F), AmmoCategory.CANNON_HEAT));
        p.ammo_penetration.put("tank_assault:gun_breda_mod38",
                shelf(new Ammo(10.0F, 3.0F), AmmoCategory.MACHINE_GUN));

        // The British guns: the 2-pounder (55mm at 0/500), the 17-pounder (163), the 25-pounder
        // (65), the Vickers and the Valentine's BESA.
        p.ammo_penetration.put("tank_assault:cannon_40mm_ap_t_mk1",
                shelf(new Ammo(55.0F), AmmoCategory.CANNON_AP));
        p.ammo_penetration.put("tank_assault:cannon_40mm_aphe_t_mk1",
                shelf(aphe(55.0F, 10.0F, 55.0F), AmmoCategory.CANNON_AP));
        p.ammo_penetration.put("tank_assault:cannon_76mm_qf17_mk8",
                shelf(solidShot(163.0F), AmmoCategory.CANNON_AP));
        p.ammo_penetration.put("tank_assault:cannon_76mm_qf17_mk1",
                shelf(bursting(13.0F, 300.0F, 50.0F, 150.0F), AmmoCategory.CANNON_HE));
        p.ammo_penetration.put("tank_assault:cannon_87.6mm_qf25_ap_mk1t",
                shelf(solidShot(65.0F), AmmoCategory.CANNON_AP));
        p.ammo_penetration.put("tank_assault:cannon_87.6mm_qf25_shell_he_mk1d",
                shelf(bursting(13.0F, 300.0F, 50.0F, 150.0F), AmmoCategory.CANNON_HE));
        p.ammo_penetration.put("tank_assault:gun_vickers",
                shelf(new Ammo(10.0F, 3.0F), AmmoCategory.MACHINE_GUN));

        // The Sturmtiger's rocket. It is a demolition charge first and an anti-tank round second:
        // 60mm gets it through a roof, and 4,000 damage inside a hull is the whole point of a 380mm.
        Ammo stum = bursting(60.0F, 4000.0F, 300.0F, 1000.0F);
        stum.spall_falloff_to = 900.0F;
        stum.spall_blindness_ticks = 40;
        p.ammo_penetration.put("tank_assault:cannon_380mm_stum",
                shelf(stum, AmmoCategory.CANNON_HE));
    }

    /**
     * The War of Resistance Carrierpack: one vehicle, the Vickers 6-Ton.
     *
     * <p>The type is Type B - the single-turret export version with the short 47mm in a duplex
     * mounting with a coaxial Vickers - and it is a 1930s design, so its published plate figures
     * are the whole of its protection. The Jaegerplatoon data table for the Finnish Mk E Alt B is
     * the source, and it is the most detailed one found: hull front and upper sides 17.5mm, lower
     * hull 10mm, rear 10mm, roof and floor 5mm, turret front and sides 13.6mm. The turret roof (8)
     * and floor (5) are estimates - no source breaks the turret down further, and a turret floored
     * by the hull roof sits on 5mm at most.
     *
     * <p>The hull's one table has to carry both the 17.5 upper side and the 10 lower side; 18 is
     * the upper figure, because that is the plate a round crossing the flanks meets. The track
     * strips run beside the 10mm lower hull, so {@code track_ends} is 10.
     *
     * <p>The gun mount is measured from {@code turret_barrel}, not from the scaffold's pick: the
     * coaxial MG's cube under {@code turret_barrel} is the same length, so the scaffold takes
     * whichever it meets first, and on this model that is the coax, 0.18 blocks to one side of the
     * 47mm. The barrel's own centre is +0.072 across and +0.005 up from the turret box's centre;
     * both sit well inside the 0.3-block circle, but the hole is the cannon's.
     */
    private static void addWarOfResistance(Profile p) {
        // Vickers 6-Ton Type B: 17.5mm upper hull, 10mm lower hull and rear, 5mm roof and floor,
        // 13.6mm turret. The engine is the 92hp Armstrong-Siddeley Puma the Finnish tables name,
        // against the pack's own 7.2t mass.
        VehicleArmor wks = ww2(new String[]{"turret", "mg"},
                new ArmorSet(14.0F, 14.0F, 14.0F, 8.0F, 5.0F),
                new ArmorSet(18.0F, 18.0F, 10.0F, 5.0F, 5.0F),
                new ArmorSet(14.0F, 14.0F, 14.0F, 8.0F, 5.0F),
                new ArmorSet(18.0F, 18.0F, 10.0F, 5.0F, 5.0F));
        gunMount(wks, 0.072F, 0.005F, 14.0F);
        trackEnds(wks, 10.0F, 0.84F);
        wks.engine_horsepower = 92.0F;
        wks.horsepower_per_ton = 12.8F;
        p.vehicles.put("wks_6:wks_6", wks);

        // The Vickers 47mm: 26mm of rolled plate at 0 degrees / 500m, from the 1932 Verkfeld
        // table the Tank Archives reproduces (30 at 300m, 26 at 500m, 22.5 at 750m, 17 at 1500m).
        // AP is solid shot - the round the pack ships is marked AP, not APHE. The pack also ships
        // a 47mm HEAT round, which the tank never carried; it is rated as a small shaped charge
        // (60) so the slot behaves rather than being left at zero. The coax is the same Vickers
        // .303 the tank_assault pack already rates at 10.
        p.ammo_penetration.put("wks_6:cannon_47mm",
                shelf(solidShot(26.0F), AmmoCategory.CANNON_AP));
        p.ammo_penetration.put("wks_6:cannon_47mm_he",
                shelf(bursting(13.0F, 300.0F, 50.0F, 150.0F), AmmoCategory.CANNON_HE));
        p.ammo_penetration.put("wks_6:cannon_47mm_heat",
                shelf(shapedCharge(60.0F), AmmoCategory.CANNON_HEAT));
        p.ammo_penetration.put("wks_6:gun_vickers",
                shelf(new Ammo(10.0F, 3.0F), AmmoCategory.MACHINE_GUN));
    }

    /**
     * The shape every 1940s armoured vehicle here shares: one kinetic table, one chemical table,
     * and a HE table that differs only where the roof and floor are thin enough for a charge.
     */
    private static VehicleArmor ww2(String[] turretParts, ArmorSet turret, ArmorSet hull,
                                    ArmorSet chemicalTurret, ArmorSet chemicalHull) {
        VehicleArmor vehicle = new VehicleArmor();
        vehicle.turret = turret;
        vehicle.hull = hull;
        // A 1940s plate is not an MBT glacis: x10 on the front keeps a round that squeaks through
        // from reading like one that went through a modern composite array, and x5 everywhere else
        // is the global default. The same choice the Panther's and the Tiger's tables make.
        vehicle.damage_multiplier = new DamageMultiplier(10.0F, 5.0F, 5.0F, 5.0F, 5.0F);
        vehicle.turret_part_ids = turretParts;
        ArmorOverride chemical = new ArmorOverride();
        chemical.turret = chemicalTurret;
        chemical.hull = chemicalHull;
        chemical.damage_multiplier = new DamageMultiplier(4.0F, 4.0F, 4.0F, 4.0F, 4.0F);
        vehicle.overrides.put("heat", chemical);
        // Built through thinRoofs rather than by pointing 'he' at the same object, so editing one
        // later cannot silently edit the other.
        vehicle.overrides.put("he", thinRoofs(chemical,
                chemicalTurret.roof, chemicalTurret.floor,
                chemicalHull.roof, chemicalHull.floor));
        return vehicle;
    }

    /** The glacis / lower-plate boundary, and the plate behind each half. */
    private static void frontSplit(VehicleArmor vehicle, float split, float upperThickness,
                                   float upperMultiplier, float lowerThickness, float lowerMultiplier) {
        vehicle.hull_front_split = new FrontSplit();
        vehicle.hull_front_split.split = split;
        vehicle.hull_front_split.upper = new Plate(upperThickness, upperMultiplier);
        vehicle.hull_front_split.lower = new Plate(lowerThickness, lowerMultiplier);
        for (ArmorOverride override : vehicle.overrides.values()) {
            override.hull_front_split = new FrontSplitPlates();
            override.hull_front_split.upper = new Plate(upperThickness, 8.0F);
            override.hull_front_split.lower = new Plate(lowerThickness, 8.0F);
        }
    }

    /** Track strips on the hull's front and rear faces, for a vehicle with a modelled track run. */
    private static void trackEnds(VehicleArmor vehicle, float thickness, float innerEdge) {
        vehicle.track_ends = new TrackEnds();
        vehicle.track_ends.thickness = thickness;
        vehicle.track_ends.multiplier = 1.5F;
        vehicle.track_ends.inner_edge = innerEdge;
        for (ArmorOverride override : vehicle.overrides.values()) {
            override.track_ends = new Plate(thickness, 2.0F);
        }
    }

    /** The road wheels of a towed gun or a wheeled vehicle. */
    private static void roadWheels(VehicleArmor vehicle, float thickness, float top) {
        vehicle.road_wheels = new RoadWheels();
        vehicle.road_wheels.thickness = thickness;
        vehicle.road_wheels.multiplier = 1.5F;
        vehicle.road_wheels.top = top;
        for (ArmorOverride override : vehicle.overrides.values()) {
            override.road_wheels = new Plate(thickness, 2.0F);
        }
    }

    /** The barrel's hole in the turret front plate. */
    private static void gunMount(VehicleArmor vehicle, float x, float y, float thickness) {
        vehicle.turret_weak_spot = new WeakSpot();
        vehicle.turret_weak_spot.thickness = thickness;
        vehicle.turret_weak_spot.multiplier = 10.0F;
        vehicle.turret_weak_spot.radius = 0.3F;
        vehicle.turret_weak_spot.offset_x = x;
        vehicle.turret_weak_spot.offset_y = y;
        for (ArmorOverride override : vehicle.overrides.values()) {
            override.turret_weak_spot = new Plate(thickness, 8.0F);
        }
    }

    /** The bow machine gun's ball mount, a hole in the front plate. */
    private static void mgPort(VehicleArmor vehicle, float x, float y, float thickness) {
        vehicle.hull_weak_spot = new WeakSpot();
        vehicle.hull_weak_spot.thickness = thickness;
        vehicle.hull_weak_spot.multiplier = 10.0F;
        vehicle.hull_weak_spot.radius = 0.3F;
        vehicle.hull_weak_spot.offset_x = x;
        vehicle.hull_weak_spot.offset_y = y;
        for (ArmorOverride override : vehicle.overrides.values()) {
            override.hull_weak_spot = new Plate(thickness, 8.0F);
        }
    }

    /** A bursting round: the hole it punches, plus a roll for what the broken plate threw off. */
    private static Ammo aphe(float penetration, float bonusMin, float bonusMax) {
        Ammo round = new Ammo(penetration);
        round.penetration_bonus_min = bonusMin;
        round.penetration_bonus_max = bonusMax;
        round.penetration_bonus_max_at = bonusMax;
        round.replaces_base_explosion = Boolean.TRUE;
        return round;
    }

    /** Solid shot: no bursting charge, so no plate-fragment roll - just the hole. */
    private static Ammo solidShot(float penetration) {
        Ammo round = new Ammo(penetration);
        round.replaces_base_explosion = Boolean.TRUE;
        return round;
    }

    /** A high-explosive shell whose whole effect is its charge and its blast. */
    private static Ammo bursting(float penetration, float damage, float spallMin, float spallMax) {
        Ammo round = new Ammo(penetration);
        round.profile = "he";
        round.penetration_damage = damage;
        round.spall_damage_min = spallMin;
        round.spall_damage_max = spallMax;
        round.spall_on_penetration = Boolean.FALSE;
        round.replaces_base_explosion = Boolean.TRUE;
        return round;
    }

    /**
     * A bursting charge: a flat burst if it gets through, since how much of it got through does not
     * change how much goes off inside, and a blast that tails off from its strongest against a
     * plate it could almost beat to its weakest against one it has no business scratching.
     */
    private static Ammo highExplosive(float penetration) {
        Ammo round = new Ammo(penetration);
        round.profile = "he";
        round.penetration_damage = 2000.0F;
        round.spall_damage_min = 300.0F;
        round.spall_damage_max = 1000.0F;
        round.spall_falloff_to = 900.0F;
        // The burst inside and the blast outside are alternatives, not a sum.
        round.spall_on_penetration = Boolean.FALSE;
        round.spall_blindness_ticks = 20;
        round.replaces_base_explosion = Boolean.TRUE;
        return round;
    }

    /**
     * A high-explosive shell stated as its charge: the blast model's input in place of the flat
     * inside figure and the spall band, both of which would be inert on a round that is measured by
     * {@link ChemicalModel}. Clearing them is what keeps the file from carrying two answers.
     */
    private static Ammo chargeShell(float penetration, float chargeKg) {
        Ammo round = highExplosive(penetration);
        round.charge_kg = chargeKg;
        round.penetration_damage = null;
        round.spall_damage_min = 0.0F;
        round.spall_damage_max = 0.0F;
        round.spall_falloff_to = 0.0F;
        return round;
    }

    /**
     * The same shell with its bursting charge taken out, leaving only what goes off on the outside:
     * the pack's own explosion, at full strength, plus the blast.
     *
     * <p>The fuze that makes it go off beside its target rather than against it is the base mod's
     * own - {@code Explosion#proximityFuze}, set in the weapon file - so nothing here has to hunt
     * for targets; this entry only describes what the blast does to armour once it happens.
     *
     * <p>Two consequences of dropping the charge, both deliberate:
     * <ul>
     *   <li>{@code penetration_damage} is zero rather than absent. Absent would fall back to the
     *       residual formula, which is a penetrating shot's damage by another name, and that is
     *       the one thing this round is meant not to have.</li>
     *   <li>The blast therefore lands on every hit, penetrated or not. With no charge to be the
     *       alternative to, gating it on the armour would only mean a hit on a thin roof - the
     *       place this round is aimed - doing nothing at all.</li>
     * </ul>
     */
    private static Ammo proximityFuzed(float penetration) {
        Ammo round = new Ammo(penetration);
        round.profile = "he";
        round.penetration_damage = 0.0F;
        round.spall_damage_min = 300.0F;
        round.spall_damage_max = 1000.0F;
        round.spall_falloff_to = 900.0F;
        round.spall_on_penetration = Boolean.TRUE;
        round.spall_blindness_ticks = 20;
        // Absent already means "keep the pack's explosion", which is the point of this round.
        round.replaces_base_explosion = Boolean.FALSE;
        return round;
    }

    /**
     * A shaped charge whose fuze the gunner can switch between impact, proximity and delay.
     *
     * <p>Three fuzes, but only two damage models: the impact fuze is the plain shaped charge, and
     * proximity and delay share the second model because they differ in what triggers them and not
     * in what goes off when they do.
     *
     * <p>The proximity model is the proximity-fuzed shell's - the pack's own explosion at full
     * strength plus a blast that lands whether or not the armour was beaten - but with the armour
     * profile kept as {@code heat} rather than {@code he}. That is the one place this round differs
     * from the 125mm HE-VT: it is still a shaped charge, so it is measured against the chemical
     * table and carries 480mm of penetration, it simply goes off beside the target instead of
     * against it.
     */
    /**
     * One mark of a kinetic round, as a load for a slot rather than a slot of its own.
     *
     * <p>No profile and no flat multiplier, exactly like the round it stands in for: a dart's
     * lethality comes from which plate it went through, and every one of these is a dart.
     */
    /**
     * The PzGr 39/42's penetration against range, as shares of its muzzle figure.
     *
     * <p>Panzerworld's table for the 7,5 cm KwK 42 gives 138 / 124 / 111 / 99 / 89 mm at 100 to
     * 2000 metres. Those are 30-degree impacts, which is what the German manuals quoted; the shape
     * is what matters here, so the figures are extrapolated back to the muzzle (141.5mm) and each
     * point divided by it. The result is anchored to whatever 0-metre figure the file states, so a
     * player who retunes the round keeps their own penetration and the published decay with it.
     */
    private static final float[] APHE_RANGE_SHARE = {
            0.0F, 1.0F, 500.0F, 0.876F, 1000.0F, 0.784F, 1500.0F, 0.699F, 2000.0F, 0.629F
    };

    /**
     * A long-rod dart's penetration against range, as shares of its muzzle figure.
     *
     * <p>APFSDS is the opposite end of the same physics from a 1940s APCBC: a 125mm round leaves
     * at roughly 1,700 m/s and is still doing most of that at two kilometres, so it keeps about
     * 96 per cent of its penetration where the Panther's shell has lost nearly 40. The figures are
     * the published order for modern tungsten-alloy rods, expressed as a shape rather than as
     * absolute millimetres for the same reason as the table above.
     */
    private static final float[] DART_RANGE_SHARE = {
            0.0F, 1.0F, 1000.0F, 0.98F, 2000.0F, 0.96F
    };

    /**
     * The 8,8 cm PzGr 39's penetration against range, as shares of its muzzle figure.
     *
     * <p>Panzerworld's table for the KwK 36 gives 120 / 110 / 99 / 91 / 83 mm at 100 to 2000
     * metres (30-degree impacts). Extrapolated back to the muzzle (122.5mm) and divided by it, so
     * the curve is anchored to whatever 0-metre figure the file states.
     */
    private static final float[] TIGER_AP_RANGE_SHARE = {
            0.0F, 1.0F, 500.0F, 0.898F, 1000.0F, 0.808F, 1500.0F, 0.743F, 2000.0F, 0.678F
    };

    /**
     * The 8,8 cm PzGr 40 APCR's curve. Lighter and faster than the APCBC and it loses its
     * velocity - and so its penetration - faster: 171 / 156 / 138 / 123 / 110 mm at the same
     * ranges, normalised to the extrapolated muzzle figure of 174.75mm.
     */
    private static final float[] TIGER_APCR_RANGE_SHARE = {
            0.0F, 1.0F, 500.0F, 0.893F, 1000.0F, 0.790F, 1500.0F, 0.704F, 2000.0F, 0.630F
    };

    private static Ammo kineticVariant(String name, float penetration) {
        Ammo round = new Ammo(penetration);
        round.name = name;
        return round;
    }

    /**
     * Takes back a load list this addon shipped in an earlier version, where the file still holds
     * exactly that list.
     *
     * <p>The first migration here that removes rather than adds, and the reason it has to is that
     * the list stopped being merely redundant and became wrong: a load is fired as the weapon it is
     * loaded into, so the dart's belt left carrying v40's entry for the shell would measure every
     * dart it fired against six millimetres. Nothing else in {@code upgrade} can take it away - the
     * v21 -> v22 loop that filled it only ever writes, and the entry it hangs off has to stay,
     * because the round is now a weapon of its own.
     *
     * <p>Compared against the exact list that shipped - the name and the penetration both - so a
     * player who has tuned their own belt keeps it. That is the rule every value migration here
     * holds to, and it has a second effect worth knowing: a belt a player has emptied by hand is
     * left empty rather than refilled.
     */
    private static void dropShippedLoad(Ammo current, String name, float penetration) {
        if (current == null || current.variants == null || current.variants.size() != 1) {
            return;
        }
        Ammo only = current.variants.get(0);
        if (name.equals(only.name) && only.penetration == penetration) {
            current.variants = null;
        }
    }

    /**
     * The VT-5's dart load list as v56 shipped it: the DTW-105 at 480 and the BTA-2 at 460, in
     * that order.
     *
     * <p>The names and both figures are checked, so a player who has edited any part of the list
     * keeps the whole of their own arrangement rather than having half of it replaced. That is the
     * same all-or-nothing rule {@link #dropShippedLoad} holds to.
     */
    private static boolean shippedVt5Loads(List<Ammo> loads) {
        if (loads == null || loads.size() != 2) {
            return false;
        }
        Ammo first = loads.get(0);
        Ammo second = loads.get(1);
        return first != null && second != null
                && "DTW-105 APFSDS".equals(first.name) && first.penetration == 480.0F
                && "BTA-2 APFSDS".equals(second.name) && second.penetration == 460.0F;
    }

    /** Files a round on the compendium shelf it belongs on, and hands the round back. */
    private static Ammo shelf(Ammo round, AmmoCategory category) {
        round.category = category.id();
        return round;
    }

    private static Ammo multiPurposeHeat(float penetration) {
        Ammo impact = shapedCharge(penetration);
        impact.multi_purpose = Boolean.TRUE;
        impact.proximity_radius = 3.0F;

        Ammo proximity = proximityFuzed(penetration);
        proximity.profile = "heat";
        impact.proximity = proximity;
        return impact;
    }

    /**
     * A copy of a vehicle's chemical table with the thin plates restated - the whole of what
     * separates the HE round from the shaped charge it is otherwise identical to.
     *
     * <p>Each plate is named separately rather than taking one value, because the plates that get
     * thinned are not the same set on every vehicle: the T-64 was only given three of them, so its
     * turret floor keeps the chemical figure while the Abrams has all four thinned.
     *
     * <p>Built by copying rather than by writing a second complete table out, so the two profiles
     * cannot drift apart - a face left out of an override would silently fall back to the vehicle's
     * kinetic value, not to the other profile's.
     */
    private static ArmorOverride thinRoofs(ArmorOverride chemical,
                                           float turretRoof, float turretFloor,
                                           float hullRoof, float hullFloor) {
        ArmorOverride thin = new ArmorOverride();
        thin.turret = new ArmorSet(chemical.turret.front, chemical.turret.side, chemical.turret.rear,
                turretRoof, turretFloor);
        thin.hull = new ArmorSet(chemical.hull.front, chemical.hull.side, chemical.hull.rear,
                hullRoof, hullFloor);
        thin.damage_multiplier = chemical.damage_multiplier;
        thin.turret_weak_spot = chemical.turret_weak_spot;
        // Carried across like the rest: a vehicle with a machine-gun port has it however the round
        // that hits it works, and leaving this out would have the HE table quietly use the
        // vehicle's own port numbers while every other zone followed the copy.
        thin.hull_weak_spot = chemical.hull_weak_spot;
        thin.hull_front_split = chemical.hull_front_split;
        thin.track_ends = chemical.track_ends;
        thin.road_wheels = chemical.road_wheels;
        thin.hull_side_era = chemical.hull_side_era;
        // And the boxes bolted to the turret, for the same reason as the port above - and this one
        // bites harder. Left out, an HE round is measured against the vehicle's kinetic value for
        // each box while the shaped charge beside it gets the chemical one, so the explosive round
        // punches through reactive armour that stops the jet. This is the failure the paragraph
        // above describes, one field later: a member added to ArmorOverride lands here or it is
        // silently dropped, and nothing about the resulting table looks wrong.
        thin.part_armor = chemical.part_armor;
        return thin;
    }

    /** Fills in anything a hand-edited file may have left out. */
    private static Profile sanitize(Profile p) {
        if (p.damage_multiplier == null) {
            p.damage_multiplier = new DamageMultiplier();
        }
        if (p.vehicles == null) {
            p.vehicles = new LinkedHashMap<>();
        }
        if (p.ammo_penetration == null) {
            p.ammo_penetration = new LinkedHashMap<>();
        }
        if (p.suspensions == null) {
            p.suspensions = new LinkedHashMap<>();
        }
        if (p.hybrids == null) {
            p.hybrids = new LinkedHashMap<>();
        }
        if (p.periscopes == null) {
            p.periscopes = new LinkedHashMap<>();
        }
        if (p.tacz_rounds == null) {
            p.tacz_rounds = new LinkedHashMap<>();
        }
        if (p.vanilla_weapons == null) {
            p.vanilla_weapons = new VanillaWeapons();
        }
        p.vanilla_weapons.sanitize();
        if (p.vanilla_explosions == null) {
            p.vanilla_explosions = new VanillaExplosions();
        }
        p.vanilla_explosions.sanitize();
        if (p.vehicle_collisions == null) {
            p.vehicle_collisions = new VehicleCollisions();
        }
        p.vehicle_collisions.sanitize();
        if (p.vehicle_ramming == null) {
            p.vehicle_ramming = new VehicleRamming();
        }
        p.vehicle_ramming.sanitize();
        // A ricochet angle outside 0..89 is not a rule anyone can mean: zero is the documented
        // "off", and ninety would turn every hit away. NaN falls to zero as well rather than
        // poisoning each comparison with a value that is never less than anything.
        if (Float.isNaN(p.ricochet_angle) || p.ricochet_angle < 0.0F) {
            p.ricochet_angle = 0.0F;
        } else if (p.ricochet_angle > 89.0F) {
            p.ricochet_angle = 89.0F;
        }
        for (Ammo round : p.ammo_penetration.values()) {
            sanitizeRound(round);
        }
        // A reference of zero has no meaning (every ratio is infinite against it); a negative
        // exponent would make more power accelerate less. Zero exponent is the supported "off".
        if (p.engine_reference_hp_per_ton <= 0.0F) {
            p.engine_reference_hp_per_ton = 25.0F;
        }
        if (p.engine_power_exponent < 0.0F) {
            p.engine_power_exponent = 0.0F;
        }
        // The suspension lives in Profile#suspensions now, so anything still hanging off a vehicle
        // block is a leftover - a hand-edited file, or a migration that did not finish. Taken into
        // the map where the map has nothing for that vehicle, and dropped from the block either way,
        // because the writer must stop emitting a key nothing reads. A file already at
        // CONFIG_VERSION skips upgrade() entirely, which is why this cannot live there alone.
        for (Map.Entry<String, VehicleArmor> entry : p.vehicles.entrySet()) {
            Suspension.Settings stale = entry.getValue().suspension;
            if (stale == null) {
                continue;
            }
            entry.getValue().suspension = null;
            p.suspensions.putIfAbsent(entry.getKey(), stale);
        }
        for (VehicleArmor armor : p.vehicles.values()) {
            if (armor.turret == null) {
                armor.turret = new ArmorSet();
            }
            if (armor.hull == null) {
                armor.hull = new ArmorSet();
            }
            if (armor.part_armor == null) {
                // A file written before per-part values existed has no such map, and a null one
                // would read as "no part has a value" - which is the same answer, but only until
                // something tries to add one. The same is true of every override's, which is how
                // the first attempt at this crashed: the map was declared without an initialiser
                // and the defaults wrote into it before any file was read.
                armor.part_armor = new LinkedHashMap<>();
            }
            for (ArmorOverride values : armor.overrides.values()) {
                if (values != null && values.part_armor == null) {
                    values.part_armor = new LinkedHashMap<>();
                }
            }
            // Only a null list is filled. An empty one is a deliberate statement - an aircraft has
            // no turret, and a tank whose armour is all hull can say so - and replacing it with a
            // dead "turret" id would be the config quietly disagreeing with the model.
            if (armor.turret_part_ids == null) {
                armor.turret_part_ids = new String[]{"turret"};
            }
            if (armor.overrides == null) {
                armor.overrides = new LinkedHashMap<>();
            }
            // A slope is a clamped geometric fact. Ninety degrees is the arithmetic's own limit
            // (the thickness would divide by zero); 85 keeps the deepest a real glacis is ever
            // measured at and leaves a finite answer for a hand-typed number.
            armor.hull_slopes = sanitizeSlopes(armor.hull_slopes);
            armor.turret_slopes = sanitizeSlopes(armor.turret_slopes);
            // A hand-written "aircraft": {} keeps the field initialisers, but a file that spells a
            // sub-block out and nulls one (or a reader that fills an absent field with null) must
            // not become a plane with no skin. Fill each piece with the same values the class
            // starts from, and give the component list somewhere to live before the resolver walks
            // it.
            if (armor.aircraft != null) {
                AircraftArmor air = armor.aircraft;
                if (air.skin == null) {
                    air.skin = new ArmorSet(2.0F, 2.0F, 2.0F, 2.0F, 3.0F);
                }
                if (air.frame_multiplier == null) {
                    air.frame_multiplier = new DamageMultiplier(2.0F, 2.0F, 2.0F, 1.5F, 2.5F);
                }
                if (air.wing == null) {
                    air.wing = new Plate(1.5F, 1.5F);
                }
                if (air.components == null) {
                    air.components = new ArrayList<>();
                }
                // A reference of zero would divide by it; a negative exponent would make more
                // power accelerate less. The ground knobs have the same two clamps.
                if (air.engine_reference_hp_per_ton <= 0.0F) {
                    air.engine_reference_hp_per_ton = 420.0F;
                }
                if (air.engine_power_exponent < 0.0F) {
                    air.engine_power_exponent = 0.0F;
                }
            }
            // A present-but-incomplete split block is filled in; an absent one stays absent,
            // which is what disables the split.
            FrontSplit split = armor.hull_front_split;
            if (split != null) {
                if (split.upper == null) {
                    split.upper = new Plate(armor.hull.front, 100.0F);
                }
                if (split.lower == null) {
                    split.lower = new Plate(90.0F, 5.0F);
                }
            }
        }
        return p;
    }

    /** Sanitises one round's range curve, and those of its loads and its second damage model. */
    private static void sanitizeRound(Ammo round) {
        if (round == null) {
            return;
        }
        round.range_penetration = sanitizeRange(round.range_penetration);
        if (round.variants != null) {
            for (Ammo variant : round.variants) {
                sanitizeRound(variant);
            }
        }
        sanitizeRound(round.proximity);
    }

    /**
     * Drops malformed points and sorts the rest, or returns {@code null} when fewer than two are
     * left.
     *
     * <p>The lookup already tolerates an unsorted list, but a sorted one keeps the written file
     * readable and makes a duplicate range the only thing left to worry about - and that is
     * resolved by the lookup preferring the tighter bracket.
     */
    private static List<Ammo.RangePoint> sanitizeRange(List<Ammo.RangePoint> points) {
        if (points == null) {
            return null;
        }
        List<Ammo.RangePoint> clean = new java.util.ArrayList<>();
        for (Ammo.RangePoint point : points) {
            if (point == null || !Float.isFinite(point.range)
                    || !Float.isFinite(point.penetration)
                    || point.range < 0.0F || point.penetration < 0.0F) {
                continue;
            }
            clean.add(point);
        }
        if (clean.size() < 2) {
            return null;
        }
        clean.sort(java.util.Comparator.comparingDouble(point -> point.range));
        return clean;
    }

    /** Clamps a plate inclination to the range the arithmetic can answer for. Null stays null. */
    private static PlateSlopes sanitizeSlopes(PlateSlopes slopes) {
        if (slopes == null) {
            return null;
        }
        slopes.front = clampSlope(slopes.front);
        slopes.side = clampSlope(slopes.side);
        slopes.rear = clampSlope(slopes.rear);
        return slopes;
    }

    private static float clampSlope(float degrees) {
        if (Float.isNaN(degrees) || degrees < 0.0F) {
            return 0.0F;
        }
        return Math.min(85.0F, degrees);
    }

    private static void backup() {
        try {
            // Derived from the live path rather than written out again: this name has already been
            // changed once, and a second copy of it is a second thing to forget.
            Files.move(configPath, configPath.resolveSibling(configPath.getFileName() + ".bak"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            VehicleArmorMod.LOGGER.error("[Vehicle Armor] could not back up {}", configPath, e);
        }
    }

    private static void write(Profile p) {
        try {
            Files.createDirectories(configPath.getParent());
            try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
                configWriter().toJson(p, writer);
            }
        } catch (Exception e) {
            VehicleArmorMod.LOGGER.error("[Vehicle Armor] could not write default config to {}", configPath, e);
        }
    }

    /**
     * @return how many configured rounds have a fuze the player can switch. Reported at startup
     *         because the count going to zero is the one symptom of the weapon id in this file
     *         having drifted away from the id in the vehicle pack - a mismatch that otherwise
     *         produces no error at all, just a round nobody can arm.
     */
    private static int multiPurposeCount() {
        int count = 0;
        for (Ammo ammo : profile.ammo_penetration.values()) {
            if (ammo.multiPurpose()) {
                count++;
            }
        }
        return count;
    }

    /** @return the armour profile of the given vehicle, or {@code null} if it is not configured. */
    public static VehicleArmor armorFor(ResourceLocation displayId) {
        return displayId == null ? null : profile.vehicles.get(displayId.toString());
    }

    /**
     * @return every configured ammunition entry, keyed by weapon id.
     *
     * <p>Unmodifiable: the caller is the compendium, which lists them, and a listing that could
     * write back into the loaded config would be a way to change the armour values by opening a
     * book.
     */
    public static Map<String, Ammo> ammunition() {
        return java.util.Collections.unmodifiableMap(profile.ammo_penetration);
    }

    /** @return the given ammunition's entry, or {@code null} if it is not configured. */
    public static Ammo ammoFor(ResourceLocation weaponId) {
        return weaponId == null ? null : profile.ammo_penetration.get(weaponId.toString());
    }

    public static DamageMultiplier damageMultiplier() {
        return profile.damage_multiplier;
    }

    public static boolean angleNormalization() {
        return profile.angle_normalization;
    }

    /** @return the angle from the plate normal past which every round is turned away, or zero. */
    public static float ricochetAngle() {
        return profile.ricochet_angle;
    }

    /** @return whether a weapon whose part unit has been destroyed is barred from firing. */
    public static boolean destroyedPartsCannotFire() {
        return profile.destroyed_parts_cannot_fire == null || profile.destroyed_parts_cannot_fire;
    }

    /** @return whether the held scanner shows the armour it is pointed at. */
    public static boolean scannerShowsArmor() {
        return profile.scanner_shows_armor == null || profile.scanner_shows_armor;
    }

    /**
     * No engine is allowed to stall a vehicle, but the floor is low enough that the physical law
     * applies to everything configured.
     *
     * <p>It was 0.5, chosen when the only vehicles here were modern ones; every 1940s tank in the
     * Tank Assault pack lands below that, so the clamp flattened the whole era to the same answer.
     * The lowest figure any configured vehicle carries is the Matilda I's 6.3 hp/t - a quarter of
     * the 25 hp/t reference - so 0.2 is below all of them and the clamp is a typo guard again
     * rather than a second, hidden balance table.
     */
    public static final float MIN_ENGINE_POWER_SCALE = 0.2F;

    /** More than twice the reference is a fantasy; the clamp keeps a typo from launching a tank. */
    public static final float MAX_ENGINE_POWER_SCALE = 2.0F;

    public static float engineReferenceHpPerTon() {
        return profile == null ? 25.0F : profile.engine_reference_hp_per_ton;
    }

    public static float enginePowerExponent() {
        return profile == null ? 1.0F : profile.engine_power_exponent;
    }

    /**
     * The multiplier the engine's power-to-weight ratio applies to the vehicle's acceleration.
     *
     * <p>This is the physical half of the engine figures. The pack states horsepower and hp/t; the
     * base mod's movement solver applies a fixed acceleration and a fixed turn rate, so without
     * this a 1,000 hp light tank and a 1,500 hp main battle tank accelerate identically. The ratio
     * is what acceleration actually follows: a = P / (m v), so at a given speed the acceleration is
     * proportional to P/m, which is exactly horsepower per tonne. The factor is that ratio against
     * the configured reference, raised to the configured exponent.
     *
     * <p>Top speed is deliberately not touched: it is a gearbox and track-speed figure, and the
     * pack's speeds are the published road speeds. This changes how quickly a vehicle reaches its
     * speed and how hard it pulls uphill, not what that speed is.
     *
     * <p>Zero horsepower means the addon has no engine figure for this vehicle and the factor is
     * 1.0 - every vehicle the addon does not describe keeps exactly the handling it had. When hp/t
     * is absent but horsepower is present, it is derived from the mass the game moves, the same
     * fallback the HUD uses.
     */
    public static float enginePowerScale(float horsepower, float horsepowerPerTon, float massKg) {
        return powerScale(horsepower, horsepowerPerTon, massKg,
                engineReferenceHpPerTon(), enginePowerExponent());
    }

    /**
     * The same conversion for an aircraft, against that aircraft's own reference.
     *
     * <p>The ground reference is 25 hp/t because a 30-tonne tank at 750 hp is ordinary; a fighter
     * at 400-plus hp/t is ordinary too, and one global reference would clamp every aircraft at the
     * 2.0 ceiling. The reference is therefore per aircraft (see
     * {@link AircraftArmor#engine_reference_hp_per_ton}); the arithmetic, the clamps and the
     * "zero horsepower means no opinion" rule are the ground ones, deliberately.
     *
     * <p>The factor multiplies the pack's own {@code thrust}, not the mass or the drag: the pack's
     * thrust curve is the aircraft, and this is a correction on top of it, exactly as the ground
     * factor is a correction on top of the pack's acceleration. It reaches speed, climb and energy
     * through that one term; the aerodynamic turn-rate limits are not scaled.
     */
    public static float aircraftPowerScale(float horsepower, float horsepowerPerTon, float massKg,
                                           float referenceHpPerTon, float exponent) {
        return powerScale(horsepower, horsepowerPerTon, massKg,
                referenceHpPerTon > 0.0F ? referenceHpPerTon : 420.0F, exponent);
    }

    private static float powerScale(float horsepower, float horsepowerPerTon, float massKg,
                                    float reference, float exponent) {
        if (horsepower <= 0.0F) {
            return 1.0F;
        }
        float perTon = horsepowerPerTon > 0.0F
                ? horsepowerPerTon
                : horsepower / Math.max(0.001F, massKg / 1000.0F);
        if (perTon <= 0.0F || reference <= 0.0F || exponent <= 0.0F) {
            // A zero exponent is the supported way to switch the effect off.
            return 1.0F;
        }
        float scale = (float) Math.pow(perTon / reference, exponent);
        return Math.max(MIN_ENGINE_POWER_SCALE, Math.min(MAX_ENGINE_POWER_SCALE, scale));
    }
}
