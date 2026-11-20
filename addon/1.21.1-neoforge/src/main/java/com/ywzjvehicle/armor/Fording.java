package com.ywzjvehicle.armor;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
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
 * Deep fording: the OPVT kit a tank crew fits before crossing water.
 *
 * <p>Without it the base mod stalls the engine the moment the hull's centre is inside any fluid -
 * {@code AbstractVehicle.tickPower} clears {@code power}, {@code hasPower()} goes false, and
 * {@code TrackedVehicle.tickMove} then returns no motion at all. The tank becomes a dead weight on
 * the riverbed with no way to drive out.
 *
 * <p>With it the engine keeps running and the tank crosses **on the bottom**, which is what the real
 * kit does: the crew seals the hull, raises a snorkel for air, and drives along the riverbed. The kit
 * is therefore not buoyancy - the tank keeps its weight and sinks - it is air.
 *
 * <p><b>The real thing takes time.</b> A crew spends twenty to thirty minutes sealing the hull,
 * erecting the tube and checking the pumps and the compass, and it does that stopped, on dry ground.
 * A keypress that deployed a complete OPVT kit in one tick was the wrong shape: it made the kit a
 * toggle rather than a piece of work, and it hid the two things the real procedure is about - the
 * hull is sealed before the tube goes up, and the turret ring is part of the seal. So the kit runs
 * through {@link Phase}: sealing, then the tube, then checks; the turret is locked from the moment
 * the seal is made, and every step needs the tank stationary and out of the water.
 *
 * <p>Everything that decides anything is a pure function - {@link #verdict}, {@link #step},
 * {@link #percent}, {@link #sealComplete} - so the offline tools can assert the truth table. What is
 * left here is reading the world ({@link #probe}) and acting on the answer ({@link #tick}).
 *
 * <p>All of this runs on the server thread: the base mod's three reads of {@code canWade} are inside
 * {@code tick}'s server-only branch, and the mixin that answers them runs there too. That is why
 * nothing here is synchronized.
 */
public final class Fording {

    /** The real OPVT figure: five metres. */
    public static final float DEFAULT_MAX_DEPTH = 5.0F;

    /**
     * Twenty to thirty real minutes, compressed forty to sixty times: thirty seconds.
     *
     * <p>The compression is the design decision, not a shortcut. At one-to-one the kit would be
     * unusable outside an exercise, and at one second it would be the toggle this replaces. Thirty
     * seconds is long enough that the crew has to commit before driving into the water and short
     * enough that one player can still do it alone. Servers that want the full ritual can raise it.
     */
    public static final int DEFAULT_PREP_TICKS = 600;

    /** Removing the kit is quicker than fitting it: ten seconds. */
    public static final int DEFAULT_STOW_TICKS = 200;

    /** Anything below this is a broken config rather than a very fast crew. */
    private static final int MIN_PREP_TICKS = 20;
    private static final int MIN_STOW_TICKS = 10;

    /**
     * Where the two procedures divide, as a percentage of the whole.
     *
     * <p>Sealing is the long job on a real tank - every hatch, every vent, the barrel cap - and it
     * is the part that locks the turret. The tube is quick by comparison and the checks are the
     * shorter still.
     */
    public static final int SEAL_PERCENT = 40;
    public static final int SNORKEL_PERCENT = 80;

    /**
     * How fast a hull may be moving and still count as stopped, as a squared horizontal delta per
     * tick. 0.05 blocks a tick is about one metre a second; a tracked vehicle that is braking is
     * under it within a few ticks.
     */
    public static final double STILL_SPEED_SQR = 0.0025D;

    /** Long enough that the crew never surfaces between two applications, short enough to lapse. */
    private static final int SEAL_TICKS = 60;

    /** Below this much effect left, top it up rather than wait for the gap. */
    private static final int SEAL_REFRESH_BELOW = 20;

    /** The guardrail that makes the failure survivable: warn while there is still water to spare. */
    private static final float WARN_AT = 0.8F;

    /**
     * The kit, as configured on a vehicle. The block being **present** is what says the vehicle has
     * one - the same presence-as-switch rule {@code part_armor} and {@code overrides} follow. A
     * separate "enabled" boolean would be the same switch written twice, and this addon has been bitten
     * by that before.
     */
    public static final class Kit {
        /** How much water the snorkel can stand, measured over the vehicle's floor, in blocks. */
        public float max_depth = DEFAULT_MAX_DEPTH;

        /** Ticks the crew spends sealing, erecting and checking before the kit is ready. */
        public int prep_ticks = DEFAULT_PREP_TICKS;

        /** Ticks the crew spends taking it all down again. */
        public int stow_ticks = DEFAULT_STOW_TICKS;
    }

    /**
     * The vehicle part that is the snorkel itself, and the only thing about it that is not in the
     * config.
     *
     * <p>It is a constant rather than a config field because the pack patcher names it: the bone, the
     * animation, the controller entry and the part all have to carry the same id, and an id that two
     * places agree on is exactly what the off-line model check exists to assert. Making it
     * configurable would add a second place to get it wrong without adding a thing anyone could want
     * to change - a different pack would need a different bone, not just a different name for it.
     *
     * <p>When a vehicle has this part, its on/off state is mirrored from this addon's own phase (see
     * {@code AbstractVehicleFordingMixin}), which is what plays the animation. It is also the
     * client-side half of the turret lock: the boolean is synced by the base mod, so the client can
     * freeze its own predicted turret yaw without a second packet. When a vehicle has no part - a
     * pack that was not patched - the kit still works and the player is told that nothing will be
     * seen to happen.
     */
    public static final String KIT_PART_ID = "snorkel";

    /** What the kit is doing. The old boolean is two of these plus a transition either way. */
    public enum Phase {
        /** Nothing fitted; the snorkel is down and the engine is unprotected. */
        STOWED,
        /** The crew is sealing the hull, erecting the tube and checking the kit. */
        PREPARING,
        /** The kit is fitted; the engine runs in water and the turret is locked. */
        READY,
        /** The crew is taking the kit down. */
        STOWING;

        public static Phase byId(int id) {
            Phase[] all = values();
            return id >= 0 && id < all.length ? all[id] : STOWED;
        }
    }

    /** The step of the procedure, which is what the progress line names. */
    public enum Step {
        NONE,
        /** Sealing the hull - hatches, vents, the barrel cap, the turret ring. */
        SEAL,
        /** Erecting and bracing the air tube. */
        SNORKEL,
        /** Pumps, compass, engine check. */
        CHECK,
        /** Taking it all down again. */
        STOW
    }

    /**
     * Why this particular message was sent.
     *
     * <p>Named rather than folded into {@link Verdict} because they answer different questions: the
     * verdict is about the water, the note is about the keypress or the transition that produced the
     * line. A refusal to start while moving is not a statement about the water at all.
     */
    public enum Note {
        /** An ordinary status line about the water. */
        NONE,
        NOT_DRIVER,
        NEED_STILL,
        NEED_LAND,
        CANCELLED,
        PREP_STARTED,
        STAGE,
        READY,
        STOW_STARTED,
        STOWED;

        public static Note byId(int id) {
            Note[] all = values();
            return id >= 0 && id < all.length ? all[id] : NONE;
        }
    }

    /**
     * What the water around the vehicle looks like right now.
     *
     * <p>{@code inWater} is "any fluid at all", which is what the base mod's stall checks. The two
     * separate booleans are what lets lava be refused as lava rather than as too-deep water.
     */
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
        /** It has one and the kit is stowed or still being fitted. */
        RETRACTED,
        /** Ready, and not in water - nothing to protect the engine from. */
        DRY,
        /** Ready in water within the limit. */
        WADING,
        /** Ready in water deeper than the snorkel reaches. */
        TOO_DEEP,
        /** Ready in something that is not water. */
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
     * One status line, as values rather than as a component.
     *
     * <p>The server sends this and the client builds the sentence, which is what keeps the text in
     * the player's own language. {@code seconds} is the configured preparation time, carried so the
     * "this will take about N seconds" line names the server's number rather than a default the
     * client guessed.
     */
    public record Report(Verdict verdict, Note note, Phase phase, int percent, float depth,
                         float maxDepth, int seconds, boolean hasSnorkel) {
    }

    private Fording() {
    }

    /** @return the configured limit, or the five-metre default when the config says nothing sane. */
    public static float maxDepth(Kit kit) {
        return kit == null || kit.max_depth <= 0.0F ? DEFAULT_MAX_DEPTH : kit.max_depth;
    }

    /** @return the configured preparation time, or the thirty-second default. */
    public static int prepTicks(Kit kit) {
        return kit == null || kit.prep_ticks < MIN_PREP_TICKS
                ? DEFAULT_PREP_TICKS : kit.prep_ticks;
    }

    /** @return the configured stowing time, or the ten-second default. */
    public static int stowTicks(Kit kit) {
        return kit == null || kit.stow_ticks < MIN_STOW_TICKS
                ? DEFAULT_STOW_TICKS : kit.stow_ticks;
    }

    /**
     * The whole water decision, as a pure function.
     *
     * <p>Pure on purpose: this is the part that can be wrong without saying so - an inverted depth
     * comparison makes a tank that either never wades or always wades, and both look like a mixin
     * that did not apply. The off-line checks assert this table.
     *
     * <p>The limit is inclusive: water exactly as deep as the snorkel is water it can cross. A kit
     * that is not READY protects nothing - the engine does not care how far through the procedure
     * the crew is.
     */
    public static Verdict verdict(Kit kit, Phase phase, Probe probe) {
        if (kit == null) {
            return Verdict.NO_KIT;
        }
        if (phase != Phase.READY) {
            return Verdict.RETRACTED;
        }
        if (!probe.inWater()) {
            return Verdict.DRY;
        }
        if (!probe.water()) {
            return Verdict.NOT_WATER;
        }
        return probe.depth() <= maxDepth(kit) ? Verdict.WADING : Verdict.TOO_DEEP;
    }

    /** @return whether the engine may run, given this verdict. */
    public static boolean mayRun(Verdict verdict) {
        return verdict == Verdict.WADING || verdict == Verdict.DRY;
    }

    /**
     * Which step of the procedure a percentage belongs to.
     *
     * <p>The boundaries are inclusive at the top of each step, so forty percent is the first tick of
     * the tube rather than the last tick of the seal - the seal is finished exactly when the turret
     * locks, and {@link #sealComplete} says the same thing about the same number.
     */
    public static Step step(Phase phase, int percent) {
        return switch (phase) {
            case PREPARING -> percent < SEAL_PERCENT ? Step.SEAL
                    : percent < SNORKEL_PERCENT ? Step.SNORKEL : Step.CHECK;
            case STOWING -> Step.STOW;
            default -> Step.NONE;
        };
    }

    /** @return whether the hull seal is made at this percentage, which is what locks the turret. */
    public static boolean sealComplete(int percent) {
        return percent >= SEAL_PERCENT;
    }

    /**
     * @return the percentage of the current procedure completed, 0..100.
     *
     * <p>Rounded down so that the line never says 100% before {@link #Phase#READY} is true: the last
     * tick of the procedure is the one that finishes it, and a crew told "100%" that cannot yet
     * drive is being lied to.
     */
    public static int percent(Phase phase, int elapsed, Kit kit) {
        if (phase == Phase.READY) {
            return 100;
        }
        if (phase == Phase.STOWED) {
            return 0;
        }
        int total = phase == Phase.STOWING ? stowTicks(kit) : prepTicks(kit);
        if (total <= 0) {
            return 100;
        }
        long value = (long) elapsed * 100L / total;
        return (int) Math.max(0L, Math.min(100L, value));
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
        Level level = vehicle.level();
        BlockPos centre = BlockPos.containing(new Vec3(vehicle.getMainCubeOBB().obb().center()));
        FluidState here = level.getFluidState(centre);
        if (here.isEmpty()) {
            return Probe.DRY;
        }
        if (!here.is(FluidTags.WATER)) {
            return new Probe(true, false, 0.0F);
        }

        // Depth is measured from the vehicle's floor, which is what the five metres of a real OPVT
        // is measured over: the water above the ground the tank is standing on.
        float floor = (float) vehicle.getBoundingBox().minY;
        float limit = maxDepth(kit);
        float surface = centre.getY();
        for (int y = centre.getY(); ; y++) {
            BlockPos at = new BlockPos(centre.getX(), y, centre.getZ());
            FluidState fluid = level.getFluidState(at);
            if (fluid.isEmpty() || !fluid.is(FluidTags.WATER)) {
                break;
            }
            surface = y + fluid.getHeight(level, at);
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
     * <p>Called only while the kit is READY - a kit being fitted or taken down has nothing to seal
     * and nothing to warn about, and the caller checks that before paying for a water probe.
     */
    public static void tick(AbstractVehicle vehicle, Kit kit, Probe probe, Verdict verdict) {
        if (kit == null) {
            return;
        }
        if (probe.inWater() && probe.water()) {
            seal(vehicle);
        }
        float limit = maxDepth(kit);
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

    /** Forgets a vehicle's edge detectors, so a removed vehicle does not keep them alive forever. */
    public static void forget(AbstractVehicle vehicle) {
        WARNINGS.remove(vehicle);
    }

    /** Per-vehicle edge detectors for the two warnings, so each fires once per crossing. */
    private static final Map<AbstractVehicle, Warnings> WARNINGS = new WeakHashMap<>();

    private static final class Warnings {
        boolean nearly;
        boolean flooded;
    }

    /** Keeps the hull sealed for as long as the kit is READY in water. */
    private static void seal(AbstractVehicle vehicle) {
        for (Entity passenger : vehicle.getPassengers()) {
            if (!(passenger instanceof LivingEntity living)) {
                continue;
            }
            MobEffectInstance current = living.getEffect(MobEffects.WATER_BREATHING);
            if (current != null && current.getDuration() > SEAL_REFRESH_BELOW) {
                continue;
            }
            living.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, SEAL_TICKS, 0,
                    false, true, true));
        }
    }

    /** Sends a line to everyone riding, which is the whole crew. */
    private static void tell(AbstractVehicle vehicle, Component message) {
        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof Player player) {
                player.displayClientMessage(message, true);
            }
        }
    }

    /** @return the snorkel part on this vehicle, or {@code null} when its model has none. */
    public static SwitchableUnit<?> snorkelOf(AbstractVehicle vehicle) {
        if (vehicle == null) {
            return null;
        }
        PartUnit<?> part = vehicle.getPartUnit(KIT_PART_ID).orElse(null);
        return part instanceof SwitchableUnit<?> switchable ? switchable : null;
    }

    /**
     * What to say about a report.
     *
     * <p>Every answer has a line, including the ones that change nothing - a driver who presses the
     * key and is told nothing has no way to tell a kit that is stowed from a key that is not wired
     * up. The two that {@link #tick} sends unprompted are the ones about a threshold being crossed.
     *
     * <p>{@code hasSnorkel} is whether the vehicle's model has a snorkel at all, which is a different
     * question from whether it works: the pack patch adds one, and an unpatched pack leaves the kit
     * working and invisible. Saying so on the line the player is already reading is the difference
     * between "the feature is broken" and "you have not run the pack patcher".
     */
    public static Component describe(Report report) {
        Component line = switch (report.note()) {
            case NOT_DRIVER -> t("fording_not_driver");
            case NEED_STILL -> t("fording_need_still");
            case NEED_LAND -> t("fording_need_land");
            case CANCELLED -> t("fording_cancel");
            case PREP_STARTED -> Component.translatable(key("fording_prep"), report.seconds());
            case STOW_STARTED -> Component.translatable(key("fording_stow_start"), report.seconds());
            case READY -> Component.translatable(key("fording_ready"), round(report.maxDepth()));
            case STOWED -> t("fording_off");
            case STAGE -> stageLine(report);
            case NONE -> verdictLine(report);
        };
        if (report.hasSnorkel()) {
            return line;
        }
        return Component.empty().append(line).append(t("fording_no_model"));
    }

    /**
     * The line for a procedure in progress.
     *
     * <p>Called for both directions, because they are the same question - how far through the job is
     * the crew - and the step name is what tells them which job. The turret lock is mentioned on the
     * two steps where it is true rather than as a separate message, because it is a consequence of
     * the seal and not an event of its own.
     */
    private static Component stageLine(Report report) {
        Step step = step(report.phase(), report.percent());
        String name = switch (step) {
            case SEAL -> "fording_seal";
            case SNORKEL -> "fording_snorkel";
            case CHECK -> "fording_check";
            case STOW -> "fording_stow";
            case NONE -> null;
        };
        MutableComponent line = name == null
                ? Component.empty()
                : Component.empty().append(Component.translatable(key(name), report.percent()));
        if ((step == Step.SNORKEL || step == Step.CHECK) && report.hasSnorkel()) {
            line.append(t("fording_turret_locked"));
        }
        return line;
    }

    /** The ordinary water line, used when the message is not about a keypress or a transition. */
    private static Component verdictLine(Report report) {
        return switch (report.verdict()) {
            case NO_KIT -> t("fording_none");
            case RETRACTED -> t("fording_off");
            case DRY -> t("fording_dry");
            case NOT_WATER -> t("fording_lava");
            case WADING -> Component.translatable(key("fording_on"),
                    round(report.depth()), round(report.maxDepth()));
            case TOO_DEEP -> floodedMessage(report.depth(), report.maxDepth());
        };
    }

    /**
     * The warning that arrives before the engine floods, while there is still water to spare.
     *
     * <p>Built here rather than inside {@link #tick} so the off-line tools can read the key straight
     * off the component the game will look up, instead of keeping a second copy of which key belongs
     * to which message - a copy that would go stale without anything noticing.
     */
    public static Component shallowWarning(float depth, float maxDepth) {
        return Component.translatable(key("fording_shallow"), round(depth), round(maxDepth));
    }

    private static Component floodedMessage(float depth, float maxDepth) {
        return Component.translatable(key("fording_deep"), round(depth), round(maxDepth));
    }

    private static Component t(String name) {
        return Component.translatable(key(name));
    }

    private static String key(String name) {
        return "message." + VehicleArmorMod.MOD_ID + "." + name;
    }

    /** One decimal place, or a bare integer - "5" reads better than "5.0" in a one-line message. */
    private static String round(float value) {
        return value == Math.round(value) ? String.valueOf(Math.round(value))
                : String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
