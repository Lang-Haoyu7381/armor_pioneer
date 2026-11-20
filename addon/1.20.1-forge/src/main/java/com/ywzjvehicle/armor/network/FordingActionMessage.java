package com.ywzjvehicle.armor.network;

import com.ywzjvehicle.armor.ArmorConfig;
import com.ywzjvehicle.armor.ArmorSystem;
import com.ywzjvehicle.armor.CrewState;
import com.ywzjvehicle.armor.Fording;
import com.ywzjvehicle.armor.FordingState;
import com.ywzjvehicle.armor.OperatedRound;
import com.ywzjvehicle.armor.VehicleArmorMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.function.Supplier;

/**
 * Client to server: work the fording kit on the vehicle I am riding.
 *
 * <p>The client sends an intent and not a state, because only the server knows what the kit is doing
 * now: the phase lives on the vehicle entity, which the client's copy of is not authoritative for.
 * Sending "deploy" from a client that believed the kit was stowed would be a guess, and a guess that
 * is wrong either way leaves the driver holding a key that does nothing - which is the one outcome
 * this addon has spent the most effort avoiding.
 *
 * <p><b>One key, four meanings.</b> The server decides from its own phase: stowed starts the
 * preparation, ready starts the removal, and either transition in progress cancels. That is why
 * there is one action and not four - a client that has to remember which one to send is a client
 * that can be out of date by one press.
 *
 * <p><b>The driver works it.</b> The kit is bolted to the tank, but the decision to fit it is a
 * driving decision: it changes what the vehicle can cross, and the person steering is the one who
 * has to make that call. The server checks the seat rather than the client, for the same reason
 * every other key here does.
 */
public record FordingActionMessage(int action) {

    public static final int ID = 2;

    public static final int TOGGLE = 0;

    public static void encode(FordingActionMessage message, FriendlyByteBuf buffer) {
        buffer.writeByte(message.action);
    }

    public static FordingActionMessage decode(FriendlyByteBuf buffer) {
        return new FordingActionMessage(buffer.readByte());
    }

    public static void handle(FordingActionMessage message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer sender = context.getSender();
        if (sender == null) {
            context.setPacketHandled(true);
            return;
        }
        // The change itself belongs on the server thread, which is what enqueueWork hands it to.
        context.enqueueWork(() -> {
            AbstractVehicle vehicle = OperatedRound.vehicleOf(sender);
            ArmorConfig.VehicleArmor armor = vehicle == null ? null : ArmorSystem.armorFor(vehicle);
            Fording.Kit kit = armor == null ? null : armor.fording;
            if (vehicle == null || kit == null) {
                // Two different situations with one answer between them, which is worth admitting:
                // a vehicle with no kit, and a packet that arrived from someone this server does not
                // think is riding anything. The second is unreachable from this addon's own client,
                // which only sends while riding, so it is a desync or a hand-made packet - and it is
                // logged as such rather than being silently indistinguishable from the first.
                if (vehicle == null) {
                    VehicleArmorMod.LOGGER.info(
                            "[Vehicle Armor] {} pressed the fording key while not in a vehicle",
                            sender.m_6302_());
                }
                reply(sender, new Fording.Report(Fording.Verdict.NO_KIT, Fording.Note.NONE,
                        Fording.Phase.STOWED, 0, 0.0F, Fording.DEFAULT_MAX_DEPTH,
                        Fording.DEFAULT_PREP_TICKS / 20, false));
                return;
            }
            if (!(vehicle instanceof FordingState state)) {
                // A vehicle class the mixin did not reach. Nothing to toggle and nothing to say that
                // would be true, so it is reported the same way a missing kit is.
                reply(sender, new Fording.Report(Fording.Verdict.NO_KIT, Fording.Note.NONE,
                        Fording.Phase.STOWED, 0, 0.0F, Fording.maxDepth(kit),
                        Fording.prepTicks(kit) / 20, true));
                return;
            }

            boolean hasSnorkel = Fording.snorkelOf(vehicle) != null;
            if (!CrewState.requireDriver(sender, vehicle)) {
                // The kit is still there and the phase is unchanged; answer with what it is, so a
                // passenger's press does not look like the server having no addon at all.
                Fording.Probe probe = Fording.probe(vehicle, kit);
                reply(sender, report(state, kit, Fording.Note.NOT_DRIVER, probe, hasSnorkel));
                return;
            }

            Fording.Probe probe = Fording.probe(vehicle, kit);
            Fording.Phase phase = state.ywzjarmor$fordingPhase();
            switch (phase) {
                case STOWED -> {
                    if (probe.inWater()) {
                        reply(sender, report(state, kit, Fording.Note.NEED_LAND, probe, hasSnorkel));
                        return;
                    }
                    if (!still(vehicle)) {
                        reply(sender, report(state, kit, Fording.Note.NEED_STILL, probe, hasSnorkel));
                        return;
                    }
                    state.ywzjarmor$beginFording(Fording.Phase.PREPARING);
                    reply(sender, report(state, kit, Fording.Note.PREP_STARTED, probe, hasSnorkel));
                }
                case PREPARING, STOWING -> {
                    state.ywzjarmor$resetFording();
                    reply(sender, report(state, kit, Fording.Note.CANCELLED, probe, hasSnorkel));
                }
                case READY -> {
                    if (probe.inWater()) {
                        reply(sender, report(state, kit, Fording.Note.NEED_LAND, probe, hasSnorkel));
                        return;
                    }
                    if (!still(vehicle)) {
                        reply(sender, report(state, kit, Fording.Note.NEED_STILL, probe, hasSnorkel));
                        return;
                    }
                    state.ywzjarmor$beginFording(Fording.Phase.STOWING);
                    reply(sender, report(state, kit, Fording.Note.STOW_STARTED, probe, hasSnorkel));
                }
            }

            // "snorkel" is the one thing here that a client and a server can disagree about: the
            // model belongs to the pack each side loaded, so a patched server with an unpatched
            // client fits a kit nobody can see. The log line is where that is diagnosable.
            VehicleArmorMod.LOGGER.info(
                    "[Vehicle Armor] {} fording kit {} on {}: phase {}, {}% (depth {} of {}, "
                            + "snorkel part: {})",
                    sender.m_6302_(), phase == Fording.Phase.STOWED ? "started" : "changed",
                    vehicle.getVehicleId(), state.ywzjarmor$fordingPhase(),
                    state.ywzjarmor$fordingPercent(), probe.depth(), Fording.maxDepth(kit),
                    hasSnorkel ? "yes" : "NO");
        });
        context.setPacketHandled(true);
    }

    /** @return a status built from the vehicle's current phase and this tick's water. */
    private static Fording.Report report(FordingState state, Fording.Kit kit, Fording.Note note,
                                         Fording.Probe probe, boolean hasSnorkel) {
        Fording.Phase phase = state.ywzjarmor$fordingPhase();
        return new Fording.Report(Fording.verdict(kit, phase, probe), note, phase,
                state.ywzjarmor$fordingPercent(), probe.depth(), Fording.maxDepth(kit),
                Fording.prepTicks(kit) / 20, hasSnorkel);
    }

    /**
     * @return whether the hull is stopped.
     *
     * <p>Velocity rather than a position sample here because the keypress is a single instant: the
     * mixin's two-sample verdict is what guards the procedure tick by tick, and this is the same
     * threshold applied to the number the entity already carries.
     */
    private static boolean still(AbstractVehicle vehicle) {
        double dx = vehicle.m_20184_().f_82479_;
        double dz = vehicle.m_20184_().f_82481_;
        return dx * dx + dz * dz <= Fording.STILL_SPEED_SQR;
    }

    private static void reply(ServerPlayer player, Fording.Report report) {
        FordingStateMessage.sendTo(player, report);
    }
}
