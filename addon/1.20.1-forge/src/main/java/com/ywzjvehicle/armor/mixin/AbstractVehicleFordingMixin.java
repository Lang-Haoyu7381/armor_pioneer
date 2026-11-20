package com.ywzjvehicle.armor.mixin;

import com.ywzjvehicle.armor.ArmorConfig;
import com.ywzjvehicle.armor.ArmorSystem;
import com.ywzjvehicle.armor.Fording;
import com.ywzjvehicle.armor.FordingState;
import com.ywzjvehicle.armor.network.FordingStateMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.SwitchableUnit;

/**
 * Runs the fording kit's preparation, and answers the engine's question about water.
 *
 * <p>The base mod asks one question in three places - {@code tickPower} and the two subclasses'
 * {@code tickMove} - all of them reading the field {@code canWade}, which is filled once from the
 * vehicle pack's {@code can_wade} key. Answering them one at a time would be three chances to miss
 * one, and a missed one is invisible: the engine would idle happily while the tank refused to move,
 * or the reverse. So the answer is written where it comes from, once per tick, before any of them
 * read it: {@code tickPower} runs first in {@code m_8119_}'s server-only branch, {@code tickMove}
 * last, and all three reads happen in between.
 *
 * <p>Writing the field rather than redirecting the reads is also why this is an {@code @Inject} and
 * not a {@code @Redirect}: a field read compiled into a subclass names the <em>subclass</em> as the
 * owner, so a redirect written against {@code AbstractVehicle} matches in one place and silently
 * does not in the others. The pack's own value is remembered once and restored whenever the kit is
 * stowed or the water is too deep - so the amphibious vehicles the pack already ships (the BMPs, the
 * BTRs, whose {@code can_wade} is true) are handed back exactly what they had.
 *
 * <p><b>The preparation is the feature.</b> A keypress does not deploy the kit; it starts the crew
 * on the real procedure, and the kit becomes usable only when the crew has finished. The three
 * steps are here rather than in {@link Fording} because they need the vehicle - its movement, its
 * passengers, its snorkel part - while the boundaries between them are pure and live in
 * {@code Fording.step}. Cancelling on movement or on entering the water is the same rule the real
 * procedure has: a crew does not seal a hull while the driver is rolling.
 *
 * <p>All of this is server-side. {@code tickPower} is inside the {@code !isClientSide} branch, and
 * nothing on the client reads the phase.
 */
@Mixin(AbstractVehicle.class)
public abstract class AbstractVehicleFordingMixin implements FordingState {

    /** The pack's own answer: whether this vehicle could wade before this addon touched it. */
    @Shadow
    protected boolean canWade;

    @Unique
    private Fording.Phase ywzjarmor$phase = Fording.Phase.STOWED;

    /** Ticks spent in the current procedure; reset on every transition. */
    @Unique
    private int ywzjarmor$elapsed;

    /** The last step announced, so the action bar changes when the step does rather than every tick. */
    @Unique
    private int ywzjarmor$lastStep = -1;

    @Unique
    private double ywzjarmor$lastX;
    @Unique
    private double ywzjarmor$lastZ;
    @Unique
    private boolean ywzjarmor$positionKnown;

    @Unique
    private boolean ywzjarmor$baseWade;

    @Unique
    private boolean ywzjarmor$baseKnown;

    /** The snorkel part, or null on a vehicle whose model has none. Resolved once - see below. */
    @Unique
    private SwitchableUnit<?> ywzjarmor$snorkel;

    @Unique
    private boolean ywzjarmor$snorkelKnown;

    @Override
    public Fording.Phase ywzjarmor$fordingPhase() {
        return ywzjarmor$phase;
    }

    @Override
    public int ywzjarmor$fordingPercent() {
        ArmorConfig.VehicleArmor armor = ArmorSystem.armorFor((AbstractVehicle) (Object) this);
        Fording.Kit kit = armor == null ? null : armor.fording;
        return Fording.percent(ywzjarmor$phase, ywzjarmor$elapsed, kit);
    }

    @Override
    public void ywzjarmor$beginFording(Fording.Phase phase) {
        ywzjarmor$phase = phase;
        ywzjarmor$elapsed = 0;
        ywzjarmor$lastStep = -1;
        if (phase == Fording.Phase.STOWING) {
            // The tube comes down first; the seal and the turret lock are released with it. The
            // real order is remove the tube, then break the seal, then traverse - and the base mod
            // cannot show a half-sealed tank, so the earliest honest moment is the start.
            SwitchableUnit<?> snorkel = ywzjarmor$snorkel();
            if (snorkel != null) {
                snorkel.setOn(false);
            }
        }
    }

    @Override
    public void ywzjarmor$resetFording() {
        ywzjarmor$phase = Fording.Phase.STOWED;
        ywzjarmor$elapsed = 0;
        ywzjarmor$lastStep = -1;
        SwitchableUnit<?> snorkel = ywzjarmor$snorkel();
        if (snorkel != null) {
            snorkel.setOn(false);
        }
        if (ywzjarmor$baseKnown) {
            this.canWade = ywzjarmor$baseWade;
        }
        Fording.forget((AbstractVehicle) (Object) this);
    }

    /**
     * @return the snorkel part on this vehicle, or {@code null} if its model has none.
     *
     * <p>Asked once and remembered. A vehicle's part list is built with the entity and does not
     * change while it exists, so the answer cannot go stale; and a vehicle spawned before the pack
     * was patched goes without a snorkel for the rest of its life, which is exactly what happens to
     * every other part of a list that was read from a pack that no longer says the same thing.
     */
    @Unique
    private SwitchableUnit<?> ywzjarmor$snorkel() {
        if (!ywzjarmor$snorkelKnown) {
            ywzjarmor$snorkel = Fording.snorkelOf((AbstractVehicle) (Object) this);
            ywzjarmor$snorkelKnown = true;
        }
        return ywzjarmor$snorkel;
    }

    @Inject(method = "tickPower", at = @At("HEAD"))
    private void ywzjarmor$tickKit(CallbackInfo ci) {
        AbstractVehicle self = (AbstractVehicle) (Object) this;
        if (!ywzjarmor$baseKnown) {
            // Taken before anything of ours has written it - the vehicle is constructed with the
            // pack's value and this is the first tick it has ever been asked about.
            ywzjarmor$baseWade = this.canWade;
            ywzjarmor$baseKnown = true;
        }

        boolean still = ywzjarmor$sampleStill(self);
        SwitchableUnit<?> snorkel = ywzjarmor$snorkel();
        ArmorConfig.VehicleArmor armor = ArmorSystem.armorFor(self);
        Fording.Kit kit = armor == null ? null : armor.fording;

        if (kit == null || self.isDestroyed()) {
            // No kit configured, or a wreck. Putting the field back in case a config edit took a
            // kit away from a vehicle that had one fitted; a wreck cannot be driven anyway.
            if (ywzjarmor$phase != Fording.Phase.STOWED) {
                ywzjarmor$resetFording();
            } else {
                this.canWade = ywzjarmor$baseWade;
            }
            if (snorkel != null && snorkel.isOn()) {
                snorkel.setOn(false);
            }
            return;
        }

        switch (ywzjarmor$phase) {
            case STOWED -> {
                this.canWade = ywzjarmor$baseWade;
                if (snorkel != null && snorkel.isOn()) {
                    snorkel.setOn(false);
                }
            }
            case PREPARING -> ywzjarmor$tickPreparing(self, kit, snorkel, still);
            case READY -> {
                if (snorkel != null && !snorkel.isOn()) {
                    snorkel.setOn(true);
                }
                Fording.Probe probe = Fording.probe(self, kit);
                Fording.Verdict verdict = Fording.verdict(kit, Fording.Phase.READY, probe);
                this.canWade = ywzjarmor$baseWade || Fording.mayRun(verdict);
                Fording.tick(self, kit, probe, verdict);
            }
            case STOWING -> ywzjarmor$tickStowing(self, kit, snorkel, still);
        }
    }

    /**
     * Sealing, erecting, checking - thirty seconds of work that ends in a usable kit.
     *
     * <p>Movement and water both cancel rather than pause: the crew is outside doing the job on a
     * real tank, and a tank that rolls away has left them behind. The driver is checked as well,
     * because the alternative is a kit that finishes fitting itself after its crew has been killed.
     */
    @Unique
    private void ywzjarmor$tickPreparing(AbstractVehicle self, Fording.Kit kit,
                                         SwitchableUnit<?> snorkel, boolean still) {
        Fording.Probe probe = Fording.probe(self, kit);
        if (!still || probe.inWater() || self.getDriver() == null) {
            ywzjarmor$cancel(self, kit, Fording.Note.CANCELLED, probe);
            return;
        }

        ywzjarmor$elapsed++;
        int percent = Fording.percent(Fording.Phase.PREPARING, ywzjarmor$elapsed, kit);

        // The seal is the stage that locks the turret, and the part's boolean is the only thing
        // both sides agree on. Forty percent is where the ring is closed and the tube goes up.
        boolean sealed = Fording.sealComplete(percent);
        if (snorkel != null && snorkel.isOn() != sealed) {
            snorkel.setOn(sealed);
        }

        if (ywzjarmor$elapsed >= Fording.prepTicks(kit)) {
            ywzjarmor$phase = Fording.Phase.READY;
            ywzjarmor$elapsed = Fording.prepTicks(kit);
            ywzjarmor$lastStep = -1;
            if (snorkel != null && !snorkel.isOn()) {
                snorkel.setOn(true);
            }
            // Ready on dry ground, so the verdict is DRY and the engine keeps running. The depth
            // check begins the moment the hull reaches the water.
            this.canWade = ywzjarmor$baseWade
                    || Fording.mayRun(Fording.verdict(kit, Fording.Phase.READY, probe));
            ywzjarmor$report(self, kit, Fording.Note.READY, 100, probe);
            return;
        }

        Fording.Step step = Fording.step(Fording.Phase.PREPARING, percent);
        if (step.ordinal() != ywzjarmor$lastStep || ywzjarmor$elapsed % 20 == 0) {
            ywzjarmor$lastStep = step.ordinal();
            ywzjarmor$report(self, kit, Fording.Note.STAGE, percent, probe);
        }
        this.canWade = ywzjarmor$baseWade;
    }

    /**
     * Taking the kit down.
     *
     * <p>Movement pauses this rather than cancelling it, because the crew is back on land and can
     * simply stop; water refuses it outright - the tube comes down when the tank is out, not while
     * it is still on the bottom. A refusal leaves the kit READY, sealed and locked, so the driver
     * can try again on better ground.
     */
    @Unique
    private void ywzjarmor$tickStowing(AbstractVehicle self, Fording.Kit kit,
                                       SwitchableUnit<?> snorkel, boolean still) {
        Fording.Probe probe = Fording.probe(self, kit);
        if (probe.inWater()) {
            ywzjarmor$phase = Fording.Phase.READY;
            ywzjarmor$elapsed = 0;
            ywzjarmor$lastStep = -1;
            if (snorkel != null && !snorkel.isOn()) {
                snorkel.setOn(true);
            }
            this.canWade = ywzjarmor$baseWade
                    || Fording.mayRun(Fording.verdict(kit, Fording.Phase.READY, probe));
            ywzjarmor$report(self, kit, Fording.Note.NEED_LAND, 0, probe);
            return;
        }
        if (!still || self.getDriver() == null) {
            // Paused, not cancelled: the kit is still fitted and still sealed.
            this.canWade = ywzjarmor$baseWade;
            return;
        }

        ywzjarmor$elapsed++;
        int percent = Fording.percent(Fording.Phase.STOWING, ywzjarmor$elapsed, kit);
        if (ywzjarmor$elapsed >= Fording.stowTicks(kit)) {
            ywzjarmor$phase = Fording.Phase.STOWED;
            ywzjarmor$elapsed = 0;
            ywzjarmor$lastStep = -1;
            this.canWade = ywzjarmor$baseWade;
            Fording.forget(self);
            ywzjarmor$report(self, kit, Fording.Note.STOWED, 0, probe);
            return;
        }
        if (ywzjarmor$elapsed % 20 == 0) {
            ywzjarmor$report(self, kit, Fording.Note.STAGE, percent, probe);
        }
        this.canWade = ywzjarmor$baseWade;
    }

    /** Puts everything back and tells the crew why, on the tick it happens. */
    @Unique
    private void ywzjarmor$cancel(AbstractVehicle self, Fording.Kit kit, Fording.Note note,
                                  Fording.Probe probe) {
        ywzjarmor$phase = Fording.Phase.STOWED;
        ywzjarmor$elapsed = 0;
        ywzjarmor$lastStep = -1;
        SwitchableUnit<?> snorkel = ywzjarmor$snorkel();
        if (snorkel != null) {
            snorkel.setOn(false);
        }
        this.canWade = ywzjarmor$baseWade;
        Fording.forget(self);
        ywzjarmor$report(self, kit, note, 0, probe);
    }

    /**
     * @return whether the hull moved less than the "stopped" threshold since the last tick.
     *
     * <p>Position delta rather than velocity because a tracked vehicle's delta includes the little
     * surges the physics gives it while the driver holds still; comparing two samples of the same
     * quantity is what makes the threshold mean "did not move" rather than "is not being pushed".
     */
    @Unique
    private boolean ywzjarmor$sampleStill(AbstractVehicle self) {
        double x = self.m_20185_();
        double z = self.m_20189_();
        boolean still = true;
        if (ywzjarmor$positionKnown) {
            double dx = x - ywzjarmor$lastX;
            double dz = z - ywzjarmor$lastZ;
            still = dx * dx + dz * dz <= Fording.STILL_SPEED_SQR;
        }
        ywzjarmor$lastX = x;
        ywzjarmor$lastZ = z;
        ywzjarmor$positionKnown = true;
        return still;
    }

    /** Sends one status line to the crew, built from the current phase and this tick's water. */
    @Unique
    private void ywzjarmor$report(AbstractVehicle self, Fording.Kit kit, Fording.Note note,
                                  int percent, Fording.Probe probe) {
        Fording.Report report = new Fording.Report(
                Fording.verdict(kit, ywzjarmor$phase, probe),
                note,
                ywzjarmor$phase,
                percent,
                probe.depth(),
                Fording.maxDepth(kit),
                Fording.prepTicks(kit) / 20,
                ywzjarmor$snorkel() != null);
        FordingStateMessage.sendToVehicle(self, report);
    }
}
