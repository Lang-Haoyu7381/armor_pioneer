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
import net.minecraftforge.network.PacketDistributor;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.function.Supplier;

/**
 * Client to server: deploy or stow the fording kit on the vehicle I am riding.
 *
 * <p>The client sends an intent and not a state, because only the server knows what the kit is doing
 * now: the flag lives on the vehicle entity, which the client's copy of is not authoritative for.
 * Sending "deploy" from a client that believed the kit was stowed would be a guess, and a guess that
 * is wrong either way leaves the driver holding a key that does nothing - which is the one outcome
 * this addon has spent the most effort avoiding.
 *
 * <p><b>The driver works it.</b> The kit is bolted to the tank, but the decision to deploy it is a
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
                reply(sender, Fording.Verdict.NO_KIT, false, 0.0F, Fording.DEFAULT_MAX_DEPTH, true);
                return;
            }
            if (!(vehicle instanceof FordingState state)) {
                // A vehicle class the mixin did not reach. Nothing to toggle and nothing to say that
                // would be true, so it is reported the same way a missing kit is.
                reply(sender, Fording.Verdict.NO_KIT, false, 0.0F, kit.max_depth, true);
                return;
            }

            boolean currentlyDeployed = state.ywzjarmor$fording();
            if (!CrewState.requireDriver(sender, vehicle)) {
                // The kit is still there and the state is unchanged; answer with what it is, so a
                // passenger's press does not look like the server having no addon at all.
                Fording.Probe probe = Fording.probe(vehicle, kit);
                reply(sender, Fording.verdict(kit, currentlyDeployed, probe), currentlyDeployed,
                        probe.depth(), kit.max_depth, Fording.snorkelOf(vehicle) != null);
                return;
            }

            boolean deployed = !currentlyDeployed;
            state.ywzjarmor$setFording(deployed);
            Fording.Probe probe = Fording.probe(vehicle, kit);
            Fording.Verdict verdict = Fording.verdict(kit, deployed, probe);
            // "snorkel" is the one thing here that a client and a server can disagree about: the
            // model belongs to the pack each side loaded, so a patched server with an unpatched
            // client deploys a kit nobody can see. The log line is where that is diagnosable.
            boolean hasSnorkel = Fording.snorkelOf(vehicle) != null;
            VehicleArmorMod.LOGGER.info(
                    "[Vehicle Armor] {} fording kit {} on {}: {} (depth {} of {}, snorkel part: {})",
                    sender.m_6302_(), deployed ? "deployed" : "stowed", vehicle.getVehicleId(),
                    verdict, probe.depth(), kit.max_depth, hasSnorkel ? "yes" : "NO");
            reply(sender, verdict, deployed, probe.depth(), kit.max_depth, hasSnorkel);
        });
        context.setPacketHandled(true);
    }

    private static void reply(ServerPlayer player, Fording.Verdict verdict, boolean deployed,
                              float depth, float maxDepth, boolean hasSnorkel) {
        VehicleArmorNetwork.channel().send(PacketDistributor.PLAYER.with(() -> player),
                new FordingStateMessage(verdict.ordinal(), deployed, depth, maxDepth, hasSnorkel));
    }
}
