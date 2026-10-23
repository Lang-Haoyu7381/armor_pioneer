package com.ywzjvehicle.armor.mixin;

import com.ywzjvehicle.armor.ArmorConfig;
import com.ywzjvehicle.armor.ArmorSystem;
import com.ywzjvehicle.armor.Fording;
import com.ywzjvehicle.armor.FordingState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.SwitchableUnit;

/**
 * Lets a deployed fording kit keep the engine running in water.
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
 * <p>All of this is server-side. {@code tickPower} is inside the {@code !isClientSide} branch, and
 * nothing on the client reads the flag.
 */
@Mixin(AbstractVehicle.class)
public abstract class AbstractVehicleFordingMixin implements FordingState {

    /** The pack's own answer: whether this vehicle could wade before this addon touched it. */
    @Shadow
    protected boolean canWade;

    @Unique
    private boolean ywzjarmor$fording;

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
    public boolean ywzjarmor$fording() {
        return ywzjarmor$fording;
    }

    @Override
    public void ywzjarmor$setFording(boolean deployed) {
        ywzjarmor$fording = deployed;
        // Mirrored here as well as in the tick below, so the snorkel starts moving on the tick the
        // key was pressed rather than the one after it.
        SwitchableUnit<?> snorkel = ywzjarmor$snorkel();
        if (snorkel != null) {
            snorkel.setOn(deployed);
        }
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
    private void ywzjarmor$answerCanWade(CallbackInfo ci) {
        AbstractVehicle self = (AbstractVehicle) (Object) this;
        if (!ywzjarmor$baseKnown) {
            // Taken before anything of ours has written it - the vehicle is constructed with the
            // pack's value and this is the first tick it has ever been asked about.
            ywzjarmor$baseWade = this.canWade;
            ywzjarmor$baseKnown = true;
        }

        // The snorkel part is a mirror of the flag above, never a second opinion about it. The flag
        // is the whole of the state - it is what the engine reads and what the key writes - and this
        // pushes it onto the part, which is what makes the model move: the base mod syncs a
        // switchable part's boolean to clients and plays its animation on the edge.
        //
        // Re-asserted every tick rather than only when it is written, because the part has one way to
        // disagree that the flag does not: it is saved to NBT and the flag is not, so a vehicle
        // reloaded from a save can come back with the snorkel up and nothing deployed. This is also
        // why the mirror is not the other way round - a part-driven state would make the same
        // keypress survive a reload on a patched pack and not on an unpatched one.
        //
        // (The part could also be flipped by a player right-clicking it, which would be undone on the
        // next tick. Unreachable today: a part with no structure_bone has no OBB to be hit, and that
        // is how this one is configured.)
        SwitchableUnit<?> snorkel = ywzjarmor$snorkel();
        if (snorkel != null && snorkel.isOn() != ywzjarmor$fording) {
            snorkel.setOn(ywzjarmor$fording);
        }

        if (!ywzjarmor$fording && this.canWade == ywzjarmor$baseWade) {
            // Nothing of ours is deployed and the field still says what the pack said, so there is
            // nothing to answer. This is the case for every vehicle in the world that has no kit -
            // which is all of them but one - on every tick, and it costs two field reads rather than
            // a config lookup and a water probe. It also has to be checked *after* the pack's value
            // is remembered: a vehicle we had already written to has to fall through and be put back.
            return;
        }

        ArmorConfig.VehicleArmor armor = ArmorSystem.armorFor(self);
        Fording.Kit kit = armor == null ? null : armor.fording;
        if (kit == null) {
            // No kit configured. Put the field back where the pack left it, in case a config edit
            // took a kit away from a vehicle that had one deployed.
            this.canWade = ywzjarmor$baseWade;
            return;
        }
        if (!ywzjarmor$fording) {
            this.canWade = ywzjarmor$baseWade;
            return;
        }

        Fording.Probe probe = Fording.probe(self, kit);
        Fording.Verdict verdict = Fording.verdict(kit, true, probe);
        this.canWade = ywzjarmor$baseWade || Fording.mayRun(verdict);
        Fording.tick(self, kit, probe, verdict);
    }
}
