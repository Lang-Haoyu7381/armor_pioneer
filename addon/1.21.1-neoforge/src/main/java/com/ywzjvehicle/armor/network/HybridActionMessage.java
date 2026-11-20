package com.ywzjvehicle.armor.network;

import net.neoforged.neoforge.network.PacketDistributor;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.ywzjvehicle.armor.ArmorSystem;
import com.ywzjvehicle.armor.CrewState;
import com.ywzjvehicle.armor.Hybrid;
import com.ywzjvehicle.armor.HybridState;
import com.ywzjvehicle.armor.OperatedRound;
import com.ywzjvehicle.armor.VehicleArmorMod;

import net.minecraft.server.level.ServerPlayer;

import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * Client to server: toggle the hybrid drive on the vehicle I am driving.
 *
 * <p>An intent, not a state - the server owns the battery and the mode, because the client's copy
 * of the vehicle is not authoritative and a local latch would drift the first time the server
 * refused or the battery ran out. Every press asks the server to flip whatever it currently has, and
 * the answer is the state it ended in.
 *
 * <p><b>The driver works it.</b> A powertrain mode decides how the tank moves, so it is the same
 * crew rule the suspension and fording keys follow: the server checks seat zero, not the client.
 *
 * <p>Nothing here is a new action for a new key shape - it is the same record the other key-driven
 * features use, with a single action because there is only one thing to do.
 */
public record HybridActionMessage(int action) implements CustomPacketPayload {

    public static final int ID = 10;

    public static final CustomPacketPayload.Type<HybridActionMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    VehicleArmorMod.MOD_ID, "hybrid_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HybridActionMessage> CODEC =
            CustomPacketPayload.codec(HybridActionMessage::encode, HybridActionMessage::decode);

    @Override
    public CustomPacketPayload.Type<HybridActionMessage> type() {
        return TYPE;
    }

    public static final int TOGGLE = 0;

    public static void encode(HybridActionMessage message, RegistryFriendlyByteBuf buffer) {
        buffer.writeByte(message.action);
    }

    public static HybridActionMessage decode(RegistryFriendlyByteBuf buffer) {
        return new HybridActionMessage(buffer.readByte());
    }

    public static void handle(HybridActionMessage message, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }
        if (sender == null) {
                return;
        }
        context.enqueueWork(() -> {
            AbstractVehicle vehicle = OperatedRound.vehicleOf(sender);
            Hybrid.Settings settings = vehicle == null ? null : ArmorSystem.hybridFor(vehicle);
            if (vehicle == null || settings == null || !(vehicle instanceof HybridState state)) {
                // A vehicle with no hybrid, or a packet from someone this server does not think is
                // riding anything. Both leave the player with nothing to see, and the reply is what
                // makes that indistinguishable from a key that was never wired up impossible.
                reply(sender, vehicle, Hybrid.NONE);
                return;
            }
            if (!CrewState.requireDriver(sender, vehicle)) {
                reply(sender, vehicle, Hybrid.DRIVER);
                return;
            }

            float battery = Hybrid.batteryOf(state, settings);
            boolean active = state.ywzjarmor$hybridActive();
            int code;
            if (active) {
                state.ywzjarmor$setHybridActive(false);
                // Back to the diesel: the base power figure ramps again from its own engine flag.
                vehicle.toggleEngine(true);
                code = Hybrid.OFF;
            } else {
                if (!Hybrid.canEngage(settings, battery)) {
                    reply(sender, vehicle, Hybrid.LOW);
                    return;
                }
                state.ywzjarmor$setHybridActive(true);
                // A real engine-off electric mode. The movement solver's power figure is kept up
                // by AbstractVehicleHybridMixin for as long as the mode is on; the ignition flag
                // being off is what makes the diesel stop burning fuel and making noise.
                vehicle.toggleEngine(false);
                code = Hybrid.ON;
            }
            VehicleArmorMod.LOGGER.info(
                    "[Vehicle Armor] {} hybrid drive {} on {}: {}% of {}",
                    sender.getScoreboardName(), code == Hybrid.ON ? "electric" : "diesel",
                    vehicle.getVehicleId(),
                    Math.round(Hybrid.percentOf(settings, battery)), Math.round(settings.capacity()));
            broadcastState(vehicle, code, sender);
        });
    }

    /**
     * Sends the vehicle's current state to everyone tracking it.
     *
     * <p>Tracking rather than riding only, because the engine sounds are played by every client
     * near the tank: a client that only learned about the mode by riding would keep the diesel's
     * noise alive from the outside. The announcer - when there is one - is answered separately with
     * {@code announce} set, so exactly one client shows the action-bar line.
     */
    public static void broadcastState(AbstractVehicle vehicle, int code, ServerPlayer announcer) {
        if (vehicle == null) {
            return;
        }
        Hybrid.Settings settings = ArmorSystem.hybridFor(vehicle);
        if (settings == null || !(vehicle instanceof HybridState)) {
            return;
        }
        PacketDistributor.sendToPlayersTrackingEntity(vehicle,
                message(vehicle, settings, code, false));
        if (announcer != null) {
            PacketDistributor.sendToPlayer(announcer,
                    message(vehicle, settings, code, true));
        }
    }

    /**
     * Sends the current state to one player without a line.
     *
     * <p>Called when someone takes a seat: a crew member who climbs into a tank whose battery is
     * half full and whose electric mode is already engaged has to see that on the HUD before their
     * first keypress.
     */
    public static void syncState(ServerPlayer player, AbstractVehicle vehicle) {
        if (player == null || vehicle == null) {
            return;
        }
        Hybrid.Settings settings = ArmorSystem.hybridFor(vehicle);
        if (settings == null || !(vehicle instanceof HybridState)) {
            return;
        }
        PacketDistributor.sendToPlayer(player,
                message(vehicle, settings, Hybrid.SYNC, false));
    }

    private static void reply(ServerPlayer player, AbstractVehicle vehicle, int code) {
        Hybrid.Settings settings = vehicle == null ? null : ArmorSystem.hybridFor(vehicle);
        PacketDistributor.sendToPlayer(player,
                message(vehicle, settings, code, true));
    }

    private static HybridStateMessage message(AbstractVehicle vehicle, Hybrid.Settings settings,
                                              int code, boolean announce) {
        if (vehicle == null || settings == null || !(vehicle instanceof HybridState state)) {
            return new HybridStateMessage(vehicle == null ? -1 : vehicle.getId(), false, false,
                    0.0F, 10.0F, code, announce);
        }
        float battery = Hybrid.batteryOf(state, settings);
        float fraction = settings.capacity() <= 0.0F ? 0.0F
                : Math.max(0.0F, Math.min(1.0F, battery / settings.capacity()));
        return new HybridStateMessage(vehicle.getId(), true, state.ywzjarmor$hybridActive(),
                fraction, settings.min_start_percent, code, announce);
    }
}
