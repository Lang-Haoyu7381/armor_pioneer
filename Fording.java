package com.ywzjvehicle.armor;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.SwitchableUnit;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Deep fording: the OPVT kit a T-64 crew fits before crossing water.
 *
 * <p>Without it the base mod stalls the engine the moment the hull's centre is inside any fluid -
 * {@code AbstractVehicle.tickPower} clears {@code power}, {@code hasPower()} goes false, and
 * {@code TrackedVehicle.tickMove} then returns no motion at all. The tank becomes a dead weight on
 * the riverbed with no way to drive out.
 *
 * <p>With it the engine keeps running and the tank crosses **on the bottom**, which is what the real
 * kit does: the crew seals the hull, raises a snorkel for air, and drives along the riverbed. The kit
 * is therefore not buoyancy - the T-64 keeps its weight and sinks - it is air.
 *
 * <p>Everything that decides anything is {@link #verdict}, which is a pure function so the offline
 * tools can assert the truth table. What is left here is reading the world ({@link #probe}) and
 * acting on the answer ({@link #tick}).
 *
 * <p>All of this runs on the server thread: the base mod's three reads of {@code canWade} are inside
 * {@code m_8119_}'s server-only branch, and the mixin that answers them runs there too. That is why
 * nothing here is synchronized.
 */
public final class Fording {

    /**
     * The kit, as configured on a vehicle. The block being **present** is what says the vehicle has
     * one - the same presence-as-switch rule {@code part_armor} and {@code overrides} follow. A
     * separate "enabled" boolean would be the same switch written twice, and this addon has been bitten
     * by that before.
     */
    public static final class Kit {
        /** How much water the snorkel can stand, measured over the vehicle's floor, in blocks. */
        public float max_depth = 5.0F;
    }

    /** The real OPVT figure: five metres. */
    public static final float DEFAULT_MAX_DEPTH = 5.0F;

    /**
     * The vehicle part that is the snorkel itself, and the only thing about it that is not in the
     * config.
     *
     * <p>It is a constant rather than a config field because the pack patcher names it: the bone, the
     * animation, the controller entry and the part all have to carry the same id, and an id that two
     * places agree on is exactly what {@code FordingCheck} exists to check. Making it configurable
     * would add a second place to get it wrong without adding a thing anyone could want to change -
     * a different pack would need a different bone, not just a different name for it.
     *
     * <p>When a vehicle has this part, its on/off state is mirrored from this addon's own flag (see
     * {@code AbstractVehicleFordingMixin}), which is what plays the animation and hides the bone. When
     * it has none - a pack that was not patched - the kit still works and the player is told that
     * nothing will be seen to happen.
     */
    public static final String KIT_PART_ID = "snorkel";

    /**
     * @return the snorkel part on this vehicle, or {@code null} when its model has none.
     *
     * <p>Looked up by id rather than remembered, because a part list belongs to a vehicle instance and
     * the whole point is to answer for the one in front of us.
     */
    public static SwitchableUnit<?> snorkelOf(AbstractVehicle vehicle) {
        if (vehicle == null) {
            return null;
        }
        PartUnit<?> part = vehicle.getPartUnit(KIT_PART_ID).orElse(null);
        return part instanceof SwitchableUnit<?> switchable ? switchable : null;
    }

    /**
     * How much of the limit is used up before the crew is warned, as a fraction of it.
     *
     * <p>This is the guardrail that makes the failure survivable rather than merely fatal: past the
     * limit the engine floods and the tank cannot drive, so the warning has to arrive while there is
     * still water to spare.
     */
    private static final float WARN_AT = 0.8F;

    /** Long enough that the crew never surfaces between two applications, short enough to lapse. */
    private static final int SEAL_TICKS = 60;

    /** Below this much effect left, top it up rather than wait for the gap. */
    private static final int SEAL_REFRESH_BELOW = 20;

    private Fording() {
    }

    /** What the water around the vehicle looks like right now. */
    public record Probe(boolean inWater, boolean water, float depth) {

        /** Not in any fluid at all, which is the ordinary case for a tank on land. */
        public static final Probe DRY = new Probe(false, false, 0.0F);
    }

    /**
     * Why the engine may or may not run.
     *
     * <p>Named rather than reduced to a boolean because every one of these is a different thing to
     * tell the player, and because the interesting failures are the ones where two of them are
     * confused: reporting "no kit" to a driver whose kit is simply stowed sends them looking for a
     * config block that is already there.
     */
    public enum Verdict {
        /** This vehicle has no kit configured. */
        NO_KIT,
        /** It has one and the snorkel is stowed. */
        RETRACTED,
        /** Deployed, and not in water - nothing to protect the engine from. */
        DRY,
        /** Deployed in water within the limit. */
        WADING,
        /** Deployed in water deeper than the snorkel reaches. */
        TOO_DEEP,
        /** Deployed in something that is not water. */
        NOT_WATER;

        /**
         * The verdict a byte off the wire names.
         *
         * <p>Guarded rather than trusted, in the same spirit as {@code Fuze.byId}: the id crosses a
         * network and a malformed one would otherwise be an array index out of bounds on the client.
         * The fallback is the state a kit is in when nobody has touched it - which is what a vehicle
         * carrying one actually starts as - so an id nothing wrote reads as a claim that is true of
         * the world in every case except a desync mid-crossing.
         */
        public static Verdict byId(int id) {
            Verdict[] all = values();
            return id >= 0 && id < all.length ? all[id] : RETRACTED;
        }
    }

    /**
     * The whole decision, as a pure function.
     *
     * <p>Pure on purpose: this is the part that can be wrong without saying so - an inverted depth
     * comparison makes a tank that either never wades or always wades, and both look like a mixin
     * that did not apply. {@code FordingCheck} asserts this table offline.
     *
     * <p>The limit is inclusive: water exactly as deep as the snorkel is water it can cross.
     */
    public static Verdict verdict(Kit kit, boolean deployed, Probe probe) {
        if (kit == null) {
            return Verdict.NO_KIT;
        }
        if (!deployed) {
            return Verdict.RETRACTED;
        }
        if (!probe.inWater()) {
            return Verdict.DRY;
        }
        if (!probe.water()) {
            return Verdict.NOT_WATER;
        }
        return probe.depth() <= kit.max_depth ? Verdict.WADING : Verdict.TOO_DEEP;
    }

    /** @return whether the engine may run, given this verdict. */
    public static boolean mayRun(Verdict verdict) {
        return verdict == Verdict.WADING || verdict == Verdict.DRY;
    }

    /**
     * Reads the water at the vehicle.
     *
     * <p>The block sampled is the one the base mod's own check uses - the centre of the main cube -
     * so "there is fluid here" cannot disagree with "the engine stalls here". Only the fluid's kind
     * and its depth are added, and both are bounded: the walk to the surface stops as soon as the
     * water is deeper than the snorkel, so a tank in mid-ocean costs the same as one in a pond.
     */
    public static Probe probe(AbstractVehicle vehicle, Kit kit) {
        Level level = vehicle.m_9236_();
        BlockPos centre = BlockPos.m_274446_(new Vec3(vehicle.getMainCubeOBB().obb().center()));
        FluidState here = level.m_6425_(centre);
        if (here.m_76178_()) {
            return Probe.DRY;
        }
        if (!here.m_205070_(FluidTags.f_13131_)) {
            return new Probe(true, false, 0.0F);
        }

        // Depth is measured from the vehicle's floor, which is what the five metres of a real OPVT
        // is measured over: the water above the ground the tank is standing on.
        float floor = (float) vehicle.m_20191_().f_82289_;
        float limit = kit == null ? DEFAULT_MAX_DEPTH : kit.max_depth;
        float surface = centre.m_123342_();
        for (int y = centre.m_123342_(); ; y++) {
            BlockPos at = new BlockPos(centre.m_123341_(), y, centre.m_123343_());
            FluidState fluid = level.m_6425_(at);
            if (fluid.m_76178_() || !fluid.m_205070_(FluidTags.f_13131_)) {
                break;
            }
            surface = y + fluid.m_76155_(level, at);
            if (surface - floor > limit) {
                // Already past the snorkel. How much further down it goes is not a number anybody
                // can act on, and walking to the surface of an ocean to find it out is not either.
                break;
            }
        }
        return new Probe(true, true, Math.max(0.0F, surface - floor));
    }

    /**
     * Applies what the kit is doing to the crew, once per tick, from the vehicle's own tick.
     *
     * <p>Two jobs, and both are about the player rather than the tank:
     *
     * <ul>
     *   <li><b>The crew is sealed.</b> A tank on the riverbed is a submerged player, and vanilla
     *       drowns them in a couple of minutes - so a kit that kept the engine running and drowned
     *       the driver would be a mechanism nobody could use. The effect is applied rather than the
     *       air supply quietly refilled because the icon is the only sign the crew has that the hull
     *       is sealed.</li>
     *   <li><b>Warnings, once each way.</b> Every message here is sent on a crossing of a threshold
     *       rather than on a timer: past the depth limit the engine floods and the tank can no longer
     *       drive, so the warning that matters is the one that arrives at eighty percent, while the
     *       driver can still turn around.</li>
     * </ul>
     *
     * <p>Called only while the kit is deployed - a stowed kit has nothing to seal and nothing to warn
     * about, and the caller checks that before paying for a water probe.
     */
    public static void tick(AbstractVehicle vehicle, Kit kit, Probe probe, Verdict verdict) {
        if (kit == null) {
            return;
        }
        if (probe.inWater() && probe.water()) {
            seal(vehicle);
        }
        float limit = kit.max_depth;
        boolean nearly = verdict == Verdict.WADING && probe.depth() >= limit * WARN_AT;
        boolean flooded = verdict == Verdict.TOO_DEEP;
        if (!nearly && !flooded && !WARNINGS.containsKey(vehicle)) {
            // The ordinary case by a long way - a tank on land, or one crossing something shallow -
            // and it must not leave an entry behind for every vehicle in the world.
            return;
        }

        Warnings last = WARNINGS.computeIfAbsent(vehicle, v -> new Warnings());
        if (nearly && !last.nearly) {
            tell(vehicle, shallowWarning(probe.depth(), limit));
        }
        if (flooded && !last.flooded) {
            tell(vehicle, floodedMessage(probe.depth(), limit));
        }
        last.nearly = nearly;
        last.flooded = flooded;
    }

    /** Per-vehicle edge detectors for the two warnings, so each fires once per crossing. */
    private static final Map<AbstractVehicle, Warnings> WARNINGS = new WeakHashMap<>();

    private static final class Warnings {
        boolean nearly;
        boolean flooded;
    }

    /** Keeps the hull sealed for as long as the kit is deployed in water. */
    private static void seal(AbstractVehicle vehicle) {
        for (Entity passenger : vehicle.m_20197_()) {
            if (!(passenger instanceof LivingEntity living)) {
                continue;
            }
            MobEffectInstance current = living.m_21124_(MobEffects.f_19608_);
            if (current != null && current.m_19557_() > SEAL_REFRESH_BELOW) {
                continue;
            }
            living.m_7292_(new MobEffectInstance(MobEffects.f_19608_, SEAL_TICKS, 0,
                    false, true, true));
        }
    }

    /** Sends a line to everyone riding, which is the whole crew. */
    private static void tell(AbstractVehicle vehicle, Component message) {
        for (Entity passenger : vehicle.m_20197_()) {
            if (passenger instanceof Player player) {
                player.m_5661_(message, true);
            }
        }
    }

    /**
     * What to say about a verdict, which is the answer to a keypress.
     *
     * <p>Every verdict has a line, including the ones that change nothing - a driver who presses the
     * key and is told nothing has no way to tell a kit that is stowed from a key that is not wired
     * up. The two that {@link #tick} sends unprompted are the ones about a threshold being crossed.
     *
     * <p>{@code hasSnorkel} is whether the vehicle's model has a snorkel at all, which is a different
     * question from whether it works: the pack patch adds one, and an unpatched pack leaves the kit
     * working and invisible. Saying so on the line the player is already reading is the difference
     * between "the feature is broken" and "you have not run the pack patcher".
     */
    public static Component describe(Verdict verdict, float depth, float maxDepth, boolean hasSnorkel) {
        Component line = switch (verdict) {
            case NO_KIT -> Component.m_237115_("message." + VehicleArmorMod.MOD_ID + ".fording_none");
            case RETRACTED -> Component.m_237115_("message." + VehicleArmorMod.MOD_ID + ".fording_off");
            case DRY -> Component.m_237115_("message." + VehicleArmorMod.MOD_ID + ".fording_dry");
            case NOT_WATER -> Component.m_237115_("message." + VehicleArmorMod.MOD_ID + ".fording_lava");
            case WADING -> Component.m_237110_("message." + VehicleArmorMod.MOD_ID + ".fording_on",
                    round(depth), round(maxDepth));
            case TOO_DEEP -> floodedMessage(depth, maxDepth);
        };
        if (hasSnorkel) {
            return line;
        }
        return Component.m_237119_().m_7220_(line)
                .m_7220_(Component.m_237115_("message." + VehicleArmorMod.MOD_ID + ".fording_no_model"));
    }

    /**
     * The warning that arrives before the engine floods, while there is still water to spare.
     *
     * <p>Built here rather than inside {@link #tick} so the offline tools can read the key straight
     * off the component the game will look up, instead of keeping a second copy of which key belongs
     * to which message - a copy that would go stale without anything noticing.
     */
    public static Component shallowWarning(float depth, float maxDepth) {
        return Component.m_237110_("message." + VehicleArmorMod.MOD_ID + ".fording_shallow",
                round(depth), round(maxDepth));
    }

    private static Component floodedMessage(float depth, float maxDepth) {
        return Component.m_237110_("message." + VehicleArmorMod.MOD_ID + ".fording_deep",
                round(depth), round(maxDepth));
    }

    /** One decimal place, or a bare integer - "5" reads better than "5.0" in a one-line message. */
    private static String round(float value) {
        return value == Math.round(value) ? String.valueOf(Math.round(value))
                : String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
