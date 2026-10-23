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
            if (Boolean.TRUE.equals(ammo.multi_purpose)) {
                object.addProperty("multi_purpose", true);
            }
            if (ammo.proximity_radius != null) {
                object.addProperty("proximity_radius", ammo.proximity_radius);
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
         * This plate is explosive reactive armour, and how heavy: {@link EraKind#LIGHT} or
         * {@link EraKind#HEAVY}. Absent - the default - is ordinary or spaced armour.
         *
         * <p>It changes nothing for an ordinary round, which meets the panel and is measured
         * against it. It is what a <em>tandem</em> warhead's precursor charge runs into, and there
         * the two kinds behave in opposite ways: a light panel cannot stop the main charge at all
         * and merely takes a third of its penetration, while a heavy one stops it outright - once,
         * and is used up doing so.
         *
         * <p>Only meaningful where a plate is a panel bolted to the outside: {@code hull_side_era}
         * and a {@code part_armor} box. Written anywhere else it is inert, since no other zone is
         * something a round can be said to go through.
         *
         * <p>A key rather than a number, because the two kinds differ in kind: one scales the round
         * and lets it past, the other annihilates it and is consumed. There is no single number that
         * says both.
         */
        public String era;

        public Plate() {
        }

        public Plate(float thickness, float multiplier) {
            this.thickness = thickness;
            this.multiplier = multiplier;
        }

        /** @return which kind of reactive armour this is, or {@code null} for none. */
        public EraKind eraKind() {
            return era == null ? null : EraKind.byId(era);
        }

        /** Marks this plate as reactive armour and returns it, for building defaults readably. */
        public Plate reactive(EraKind kind) {
            this.era = kind.id();
            return this;
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

        public ArmorOverride() {
        }
    }

    /** Armour of a single vehicle, split into turret and hull. */
    public static final class VehicleArmor {
        public ArmorSet turret = new ArmorSet(480.0F, 150.0F, 90.0F, 90.0F, 90.0F);
        public ArmorSet hull = new ArmorSet(440.0F, 100.0F, 90.0F, 90.0F, 90.0F);

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
         * Part-unit ids that belong to the turret. Any other part - and any hit that
         * misses every part unit entirely - is treated as hull armour.
         */
        public String[] turret_part_ids = {
                "turret",
                "turret_machine_gun",
                "turret_smoke_grenade",
                "commander_machine_gun"
        };
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
     */
    private static final int CONFIG_VERSION = 47;

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
         * When true the nominal thickness is divided by cos(impact angle), so sloped
         * or angled hits are easier to penetrate (War Thunder style). Off by default so
         * that a perpendicular hit always yields exactly {@code (pen - thickness) * multiplier}.
         */
        public boolean angle_normalization = false;

        /** Keyed by the vehicle's vehicle id, or by its display id for re-skinned variants. */
        public Map<String, VehicleArmor> vehicles = new LinkedHashMap<>();

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
    }

    private static Profile profile = defaultProfile();
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
        // never arrives. adoptBoxes() only gives a kind to boxes that are already there. So the two
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
        // goes with it". The tandem flag on the round is unchanged; what changed is what the panels
        // do about it, so every panel a file already has needs its kind - see adoptReactiveKinds.
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

        // v28 -> v29: which panels are reactive armour, and which kind. A key that did not exist
        // before, on panels that did - so unlike the round above, this one reaches back through
        // every vehicle and every profile rather than naming the two that prompted it.
        adoptReactiveKinds(loaded, defaults);

        // v34 -> v35: boxes the defaults grew that the file has never heard of. The two migrations
        // do different jobs and do not overlap - this one copies boxes in whole, the one above gives
        // a kind to boxes already there - so the order between them is free. A box this adds brings
        // its kind with it.
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

        // v43 -> v44: the suspension moved to a map of its own. Runs last, and it has to: every
        // earlier step here works on the vehicle blocks, and this one empties a field inside them.
        adoptSuspensions(loaded, defaults);

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
     * same rule {@link #adoptReactiveKinds} holds to - including the case where they have deleted a
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
     * Gives the reactive panels of an older file the kind they are.
     *
     * <p>Every other migration here is about a value that came out wrong. This one is about a key
     * that simply was not there before, and the failure it prevents is the quietest in the file: a
     * panel with no kind is not a broken panel, it is ordinary armour - so a tandem warhead sails
     * through reactive armour that was configured specifically to be its answer, and the numbers in
     * the table look exactly like numbers.
     *
     * <p>Taken from the defaults panel by panel, and only where the file says nothing. A player who
     * has given a panel a kind of their own has said something, and this has no business overruling
     * them - the same rule the glacis boundaries are held to.
     */
    private static void adoptReactiveKinds(Profile loaded, Profile defaults) {
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
            adopted += adoptKind(mine.hull_side_era, theirs.hull_side_era);
            adopted += adoptBoxes(mine.part_armor, theirs.part_armor);
            for (Map.Entry<String, ArmorOverride> profile : theirs.overrides.entrySet()) {
                ArmorOverride myOverride = mine.overrides == null ? null
                        : mine.overrides.get(profile.getKey());
                if (myOverride == null) {
                    continue;
                }
                adopted += adoptKind(myOverride.hull_side_era, profile.getValue().hull_side_era);
                adopted += adoptBoxes(myOverride.part_armor, profile.getValue().part_armor);
            }
        }
        if (adopted > 0) {
            VehicleArmorMod.LOGGER.info(
                    "[Vehicle Armor] upgrade: {} reactive panel(s) given the kind they are", adopted);
        }
    }

    /** @return 1 when a panel that had no kind was given the default's, 0 otherwise. */
    private static int adoptKind(Plate mine, Plate defaults) {
        if (mine == null || defaults == null || mine.era != null || defaults.era == null) {
            return 0;
        }
        mine.era = defaults.era;
        return 1;
    }

    /** The same over a map of boxes, keyed by part id. */
    private static int adoptBoxes(Map<String, Plate> mine, Map<String, Plate> defaults) {
        if (mine == null || defaults == null) {
            return 0;
        }
        int adopted = 0;
        for (Map.Entry<String, Plate> box : defaults.entrySet()) {
            adopted += adoptKind(mine.get(box.getKey()), box.getValue());
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
        // Light reactive armour: the Kontakt-1 class of panel, which a tandem warhead's precursor
        // charge is enough to deal with. A tandem round therefore still gets through - at two thirds
        // of its penetration, which is the most this panel can do about one.
        t64Chemical.hull_side_era = new Plate(500.0F, 40.0F).reactive(EraKind.LIGHT);
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

        // The RK-2S Barrier ATGM, and the first round here with a tandem warhead. Its precursor
        // charge is aimed at reactive armour, and how it fares against it depends on which kind it
        // meets - the T-64's light panels cost it a third of its penetration, the T-90M's heavy ones
        // stop it outright, once each. See ArmorSystem.resolve.
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
        Ammo tigerAp = new Ammo(165.0F);
        tigerAp.penetration_bonus_min = 20.0F;
        tigerAp.penetration_bonus_max = 165.0F;
        tigerAp.penetration_bonus_max_at = 165.0F;
        tigerAp.replaces_base_explosion = Boolean.TRUE;
        p.ammo_penetration.put("tank_assault:cannon_88mm_pzgr_39",
                shelf(tigerAp, AmmoCategory.CANNON_AP));
        // PzGr 40, the tungsten APCR: 238mm at the muzzle (181mm at 1km, 137mm at 2km). Faster, lighter
        // core, smaller drag; in real life it traded penetration for the post-war fight the war had
        // become. The pack names the same shell and gives it 90 dmg of blast that is the wrong scale -
        // an APCR has no body's propagation whatever - so its `replaces_base_explosion` is on, just
        // like the dart side of the 30mm belts.
        Ammo tigerApcr = new Ammo(238.0F);
        tigerApcr.replaces_base_explosion = Boolean.TRUE;
        p.ammo_penetration.put("tank_assault:cannon_88mm_pzgr_40",
                shelf(tigerApcr, AmmoCategory.CANNON_AP));
        // Sprgr 43, the high-explosive round. 60mm so it never penetrates the thinnest plate
        // configured here (the hull roof at 25mm is close, but a 60mm round against a 25mm plate
        // resolves as `60 - 25 = 35 > 0` - so I raised it). The pack's 900 dmg at radius 11 would
        // bury the table, so it is dropped.
        Ammo tigerHe = new Ammo(60.0F);
        tigerHe.profile = "he";
        tigerHe.penetration_damage = 300.0F;
        tigerHe.spall_damage_min = 50.0F;
        tigerHe.spall_damage_max = 150.0F;
        tigerHe.spall_on_penetration = Boolean.FALSE;
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
        Ammo pantherAp = new Ammo(192.0F);
        pantherAp.penetration_bonus_min = 20.0F;
        pantherAp.penetration_bonus_max = 200.0F;
        pantherAp.penetration_bonus_max_at = 200.0F;
        // The pack gives this round an 180-damage explosion, which knows nothing about armour and
        // would land on every hit on top of the roll. Dropped, as on every other round here whose
        // numbers are meant to be the whole answer.
        pantherAp.replaces_base_explosion = Boolean.TRUE;
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
        // The improved 105mm rifled gun's APFSDS is quoted at 500mm RHA, which is the whole reason
        // this light tank is dangerous: it cannot stop a modern dart, but it carries one. The two
        // older marks are loads of the same round rather than separate weapons, so they live in
        // the variant list and the gunner picks them with the same chooser as the M829 family.
        Ammo vt5Dart = new Ammo(500.0F);
        vt5Dart.variants = new java.util.ArrayList<>(java.util.List.of(
                kineticVariant("DTW-105 APFSDS", 480.0F),
                kineticVariant("BTA-2 APFSDS", 460.0F)));
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
        p.ammo_penetration.put("armor_pioneer:cannon_105mm_heat",
                shelf(shapedCharge(450.0F), AmmoCategory.CANNON_HEAT));

        Ammo vt5He = highExplosive(60.0F);
        // A 105mm bursting charge is smaller than the 125mm shell that helper was written for.
        vt5He.penetration_damage = 1200.0F;
        p.ammo_penetration.put("armor_pioneer:cannon_105mm_he",
                shelf(vt5He, AmmoCategory.CANNON_HE));

        // The gun-launched missile. Its warhead is a tandem shaped charge; the tandem flag is what
        // gives it the two-stage result against reactive armour. Its flight is the base mod's.
        Ammo gp105 = shapedCharge(700.0F);
        gp105.tandem = Boolean.TRUE;
        p.ammo_penetration.put("armor_pioneer:cannon_105mm_atgm",
                shelf(gp105, AmmoCategory.CANNON_HEAT));

        // The pack-local machine guns. They are the same guns the base mod ships, with ids this
        // pack owns - which is what lets the addon rate them at all: the base mod's generic
        // gun ids have no pack file, and an entry for one would never be matched to a name.
        p.ammo_penetration.put("armor_pioneer:gun_7_62mm",
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

        addTaczRounds(p);

        // -----------------------------------------------------------------------------------------
        // The adjustable suspensions - see Suspension itself, and Profile#suspensions for why this
        // is a map of its own rather than a block on each vehicle's armour.
        //
        // Seven vehicles. The Stryker is the wheeled one and has shipped since v38; the other six
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
        //
        // The T-64, the T-72B3M, the three T-90Ms and the four Abrams are deliberately not here.
        // The 0.6.1 pack gives every one of their models suspension parts, so the two keys would
        // answer and the hull would really move - but all nine of those tanks have torsion bars,
        // and "the model has springs" is not the same statement as "the tank has a ride-height
        // control". Giving them one would be inventing a feature, not modelling one.
        p.suspensions.put("vvp_ywzj:stryker_m1296", new Suspension.Settings());
        for (String id : new String[]{
                "vvp_ywzj:bmp_3", "vvp_ywzj:brm", "vvp_ywzj:chryzantema",
                "vvp_ywzj:challenger", "vvp_ywzj:puma", "vvp_ywzj:ajax"}) {
            p.suspensions.put(id, hydropneumatic());
        }

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
     *       that. <b>The pack ships 0.1 for every tracked vehicle in it</b>, a tenth of a block and
     *       invisible on a tank, so {@code add_suspension.ps1} raises it to 0.32 and the travel
     *       becomes 0.31. The two axes share that: the longest of the six is the Challenger, whose
     *       furthest wheel stands 2.531 blocks from the hull's balance point and so takes
     *       {@code tan(2) x 2.531 = 0.088} of the pitch. 0.20 + 0.088 = 0.288, leaving 0.022.</li>
     *   <li><b>0.20 of drop.</b> Bounded by the hull rather than by the springs. The hull's
     *       collision box stands 0.598 blocks above the lowest point of the running gear on the
     *       tightest of the six - the Ajax - so lowering it by 0.20 still leaves 0.40 of daylight
     *       under the belly. That is the figure {@code SuspensionCheck} re-derives from the model,
     *       and it is the one number here that a wrong value would make obvious in game: too much
     *       and the hull is on the ground, held up by its own collision box rather than by its
     *       springs.</li>
     *   <li><b>2 degrees</b> of pitch, the same the Stryker ships with - roughly what a long
     *       120mm gun gains in depression from it, and about all the budget above will pay for.</li>
     * </ul>
     */
    private static Suspension.Settings hydropneumatic() {
        Suspension.Settings settings = new Suspension.Settings();
        settings.max_rise = 0.20F;
        settings.max_drop = 0.20F;
        settings.max_pitch = 2.0F;
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
            // Heavy reactive armour: the Relikt class, which a precursor charge is not enough for.
            // One tandem warhead is stopped dead by each block and the block goes with it; the next
            // one meets bare turret where it was. Ordinary rounds are unaffected - they are stopped
            // by it too, and always were, without using it up.
            //
            // The same figures as the hull blocks, because it is the same blocks: `era_3` and `era_4`
            // run along the turret's flanks and `era_5` across its rear, and the armour under them is
            // the turret's side or rear, not its 500mm front. They were once rated far higher
            // (700/1100) on the reasoning that they sit on a turret rather than on a hull - which is
            // the one thing about them that does not matter: the figure is what a round has to beat
            // **at that spot**, and where the spot is decides what is underneath it, not which bone
            // the part hangs off. Note what identical numbers mean here: 300 over a hull's 80 side
            // is 220 of block, over the turret's 180 it is 120 of it.
            t90m.part_armor.put(block, new Plate(300.0F, 8.0F).reactive(EraKind.HEAVY));
            t90mChemical.part_armor.put(block, new Plate(600.0F, 6.0F).reactive(EraKind.HEAVY));
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
                    new Plate(300.0F, 8.0F).reactive(EraKind.HEAVY));
            t90mChemical.part_armor.put("additional_armor_rear",
                    new Plate(600.0F, 6.0F).reactive(EraKind.HEAVY));
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
            t90m.part_armor.put(block, new Plate(300.0F, 8.0F).reactive(EraKind.HEAVY));
            t90mChemical.part_armor.put(block, new Plate(600.0F, 6.0F).reactive(EraKind.HEAVY));
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
        // a light tile - a few centimetres of steel over an explosive sandwich, made to spoil an
        // RPG rather than to stop a tank round - so it is the light kind: a tandem warhead gets
        // through it at two thirds of its penetration, and a plain shaped charge does not.
        //
        // Valued per part rather than as a band across the flank: these really are separate parts on
        // the model, with ids, so the tile covers exactly the boxes it is drawn on and nothing has to
        // be guessed about its height. (The T-90M's hull blocks were a band for a while, for want of
        // anything better, and became parts for the same reason.)
        //
        // Each figure is what a round has to beat **at that spot**, not what the tile is worth on
        // its own, because one zone decides a hit: a plate bolted on the outside is the only plate
        // the round is measured against, so it has to carry the armour underneath it too. Hence the
        // sums below - 40mm of tile over the hull's 100 (140) or the turret's 250 (290), and 400 of
        // tile over the hull's 200 (600) or the turret's 400 (800). The T-90M's boxes follow the same
        // convention: 600 on a flank is 600 minus whatever plate is under it, not 600 of block.
        for (String tile : tiles) {
            boolean onTurret = carries(turretParts, tile);
            abrams.part_armor.put(tile,
                    new Plate(onTurret ? 290.0F : 140.0F, 5.0F).reactive(EraKind.LIGHT));
            abramsChemical.part_armor.put(tile,
                    new Plate(onTurret ? 800.0F : 600.0F, 4.0F).reactive(EraKind.LIGHT));
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
        panther.turret = new ArmorSet(110.0F, 50.0F, 50.0F, 40.0F, 40.0F);
        panther.hull = new ArmorSet(200.0F, 50.0F, 60.0F, 40.0F, 40.0F);
        panther.damage_multiplier = new DamageMultiplier(10.0F, 5.0F, 5.0F, 5.0F, 5.0F);

        // The gun shield. Unlike the T-64's and the Abrams', this is the strongest part of the front
        // plate rather than a gap in it - the same circle, doing the opposite job.
        panther.turret_weak_spot = new WeakSpot();
        panther.turret_weak_spot.thickness = 180.0F;
        panther.turret_weak_spot.multiplier = 10.0F;

        // Half and half: the glacis takes the top half of the front face and the lower plate the
        // bottom half. The glacis is the thick one - 200mm against 120 - so where the line falls
        // decides what a hit meets, and the machine-gun port is a third answer inside the glacis.
        panther.hull_front_split = new FrontSplit();
        panther.hull_front_split.split = 0.0F;
        panther.hull_front_split.upper = new Plate(200.0F, 10.0F);
        panther.hull_front_split.lower = new Plate(120.0F, 10.0F);

        // The bow machine gun's ball mount, a hole through the glacis. Measured off the model: the
        // MG barrel sits 0.604 blocks to one side of the hull box's centre and 0.700 blocks above
        // it, which puts the port in the upper half of the plate. The radius is the one judgement
        // here - 0.6 blocks across is about the size of the mounting - and the armour scanner is
        // the way to check it in game.
        panther.hull_weak_spot = new WeakSpot();
        panther.hull_weak_spot.thickness = 100.0F;
        panther.hull_weak_spot.multiplier = 10.0F;
        panther.hull_weak_spot.radius = 0.3F;
        panther.hull_weak_spot.offset_x = 0.604F;
        panther.hull_weak_spot.offset_y = 0.700F;

        // inner_edge is the measured inner face itself, not a value with margin below it. On the
        // shipped tracked vehicles the track is a row of tiny locator cubes and the threshold is
        // picked by eye; here the track is one box per side, and it matters which side of the line
        // the hull falls on: the track is 200mm and the flank beside it is 50mm.
        panther.track_ends = new TrackEnds();
        panther.track_ends.thickness = 200.0F;
        panther.track_ends.multiplier = 1.5F;
        panther.track_ends.inner_edge = 0.589F;

        ArmorOverride pantherChemical = new ArmorOverride();
        pantherChemical.turret = new ArmorSet(110.0F, 50.0F, 50.0F, 40.0F, 40.0F);
        pantherChemical.hull = new ArmorSet(120.0F, 50.0F, 60.0F, 40.0F, 40.0F);
        pantherChemical.damage_multiplier = new DamageMultiplier(4.0F, 4.0F, 4.0F, 4.0F, 4.0F);
        pantherChemical.turret_weak_spot = new Plate(180.0F, 8.0F);
        // Same thickness as the kinetic table, with the multiplier its glacis uses - the port is
        // thinner than the plate around it either way, and which warhead hit it changes only how
        // lethal getting through is.
        pantherChemical.hull_weak_spot = new Plate(100.0F, 8.0F);
        pantherChemical.hull_front_split = new FrontSplitPlates();
        pantherChemical.hull_front_split.upper = new Plate(120.0F, 8.0F);
        pantherChemical.hull_front_split.lower = new Plate(120.0F, 8.0F);
        pantherChemical.track_ends = new Plate(150.0F, 2.0F);
        panther.overrides.put("heat", pantherChemical);
        // Every plate the HE round meets is the plate the HEAT round meets, at the same number, so
        // the copy comes out identical. Built through thinRoofs rather than by pointing 'he' at the
        // same object, as on the BTR-3: editing one later must not silently edit the other.
        panther.overrides.put("he", thinRoofs(pantherChemical, 40.0F, 40.0F, 40.0F, 40.0F));

        // The two machine-gun mountings and the smoke launcher the model parents to the turret; its
        // gunner's and commander's seats are parented there as well, and are not armour.
        panther.turret_part_ids = new String[]{
                "turret",
                "turret_machine_gun",
                "turret_smoke_grenade"
        };

        p.vehicles.put("tank_assault:panther", panther);
    }

    /**
     * The Tiger E, the early-production Tiger with the drum magazine (so its d.h. reaches the floor of
     * the hull at the crew compartment). Front armour is **100mm plate** throughout - the doubled
     * 100/80 face is the late-Tiger F's silhouette, not this one's.
     *
     * <p>The glacis plate is 60mm @ 80° (close to horizontal, so very effective), the lower nose plate
     * 100mm @ 22° vertical, and the side plates 80mm. The turret front, sides and rear are 80/80/80.
     *
     * <p>The weak spot is the gun mount, a hole in the upper front of the turret through which the
     * 88mm barrel passes; offsets measured off the model. The hull weak spot the model carries is the
     * bow machine gun's mount, but the hull has no machine-gun port on this version - the Tiger E's
     * bow MG was a ball mount that the model does not draw as a barrel - so it is left to fire at a
     * blank plate.
     */
    private static void addTigerE(Profile p) {
        VehicleArmor tiger = new VehicleArmor();
        tiger.turret = new ArmorSet(100.0F, 80.0F, 80.0F, 25.0F, 28.0F);
        tiger.hull = new ArmorSet(100.0F, 80.0F, 80.0F, 25.0F, 25.0F);
        tiger.damage_multiplier = new DamageMultiplier(10.0F, 5.0F, 5.0F, 5.0F, 5.0F);

        // The gun mount - the gun barrel passes through here, so the plate is what protects the crew
        // and that is thinner than the rest of the turret front. Measured off the model.
        tiger.turret_weak_spot = new WeakSpot();
        tiger.turret_weak_spot.thickness = 100.0F;
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
        tiger.hull_front_split.upper = new Plate(60.0F, 10.0F);
        tiger.hull_front_split.lower = new Plate(100.0F, 10.0F);
        // Keep hull.front equal to the glacis so turning the split off falls back to the 60mm
        // number rather than the 100mm lower plate.
        tiger.hull.front = 60.0F;

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
        tigerChemical.turret = new ArmorSet(100.0F, 80.0F, 80.0F, 25.0F, 28.0F);
        tigerChemical.hull = new ArmorSet(100.0F, 80.0F, 80.0F, 25.0F, 25.0F);
        tigerChemical.damage_multiplier = new DamageMultiplier(4.0F, 4.0F, 4.0F, 4.0F, 4.0F);
        tigerChemical.turret_weak_spot = new Plate(100.0F, 8.0F);
        tigerChemical.hull_front_split = new FrontSplitPlates();
        tigerChemical.hull_front_split.upper = new Plate(60.0F, 8.0F);
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

        // Tracks: measured off the structure model. Unlike an MBT's, the VT-5's track runs
        // entirely outside the hull box - the inner face is 1.197 of the hull's half-width - so
        // the strip is the whole track rather than a margin inside the flank. The threshold sits a
        // hair below the measurement (1.19) so a hit on the track's own inner face is not lost to
        // rounding at the boundary. `top` is the hull's mid-height.
        vt5.track_ends = new TrackEnds();
        vt5.track_ends.thickness = 60.0F;
        vt5.track_ends.multiplier = 1.5F;
        vt5.track_ends.inner_edge = 1.19F;
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
        // The ERA package covers the forward half of each flank. It is rated as light: a tandem
        // round's precursor is assumed to deal with one layer, so the jet still arrives at two
        // thirds of its penetration.
        vt5Chemical.hull_side_era = new Plate(300.0F, 4.0F).reactive(EraKind.LIGHT);
        vt5.overrides.put("heat", vt5Chemical);
        vt5.overrides.put("he", thinRoofs(vt5Chemical, 30.0F, 30.0F, 30.0F, 25.0F));

        // The commander's remote weapon station needs a box of its own. The structure model parents
        // it to `turret_st`, a file-level root beside `turret` rather than under it, so the
        // bone-tree derivation does not count it as turret - and measuring it against the hull's
        // 40mm flank would be worse still. It is a thin armoured mount on top of the turret, so it
        // gets its own value, checked before every face-based zone.
        vt5.part_armor.put("commander_machine_gun", new Plate(30.0F, 5.0F));

        // The gun mount and the coaxial machine gun are the parts the bone tree does put under the
        // turret. The remote weapon station is handled by the box above, not by this list.
        vt5.turret_part_ids = new String[]{
                "turret",
                "turret_machine_gun"
        };

        p.vehicles.put("armor_pioneer:vt5", vt5);
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
        if (p.tacz_rounds == null) {
            p.tacz_rounds = new LinkedHashMap<>();
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
            if (armor.turret_part_ids == null || armor.turret_part_ids.length == 0) {
                armor.turret_part_ids = new String[]{"turret"};
            }
            if (armor.overrides == null) {
                armor.overrides = new LinkedHashMap<>();
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

    /** @return whether a weapon whose part unit has been destroyed is barred from firing. */
    public static boolean destroyedPartsCannotFire() {
        return profile.destroyed_parts_cannot_fire == null || profile.destroyed_parts_cannot_fire;
    }

    /** @return whether the held scanner shows the armour it is pointed at. */
    public static boolean scannerShowsArmor() {
        return profile.scanner_shows_armor == null || profile.scanner_shows_armor;
    }
}
