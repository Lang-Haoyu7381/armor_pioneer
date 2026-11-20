package com.ywzjvehicle.armor.network;

import net.neoforged.neoforge.network.PacketDistributor;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.ywzjvehicle.armor.ArmorSystem;
import com.ywzjvehicle.armor.CrewState;
import com.ywzjvehicle.armor.OperatedRound;
import com.ywzjvehicle.armor.Suspension;
import com.ywzjvehicle.armor.SuspensionState;
import com.ywzjvehicle.armor.VehicleArmorMod;

import net.minecraft.server.level.ServerPlayer;

import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * Client to server: move one axis of the suspension on the vehicle I am riding, one step, this way.
 *
 * <p>An intent, not a state, for the same reason the fording key is: the pose lives on the vehicle
 * entity, and the client's copy of that is not authoritative. A client that believed the tank was
 * already raised would send "raise" when it meant "lower", and the driver would be holding a key
 * that does the opposite of what the last message said - so the server is asked to move whatever it
 * currently has, and it answers with the result.
 *
 * <p><b>The direction travels with the message</b> because every key has one: the arrows mean up and
 * down, not "the next one along". What the client still cannot say is <em>where</em> the axis is, so
 * the server is the only side that can answer "there is no higher to go" - which is why the reply
 * carries the position it ended at rather than an acknowledgement.
 *
 * <p>One axis per press rather than one message carrying both, because the two axes are two separate
 * controls and pressing one must not disturb the other: someone who has put the nose down and then
 * raises the hull expects to keep the nose down.
 */
public record SuspensionActionMessage(int axis, int direction) implements CustomPacketPayload {

    public static final int ID = 4;

    public static final CustomPacketPayload.Type<SuspensionActionMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    VehicleArmorMod.MOD_ID, "suspension_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SuspensionActionMessage> CODEC =
            CustomPacketPayload.codec(SuspensionActionMessage::encode, SuspensionActionMessage::decode);

    @Override
    public CustomPacketPayload.Type<SuspensionActionMessage> type() {
        return TYPE;
    }

    /** The three axes, in the order their keys are laid out. */
    public static final int HEIGHT = 0;
    public static final int PITCH = 1;
    public static final int ROLL = 2;

    /**
     * Which way a press asked to go. Anything non-negative raises, so a stray byte cannot lower.
     *
     * <p>On the roll axis "up" is the axis's own increasing direction, which is the right side
     * going down - the left and right arrows send {@link #DOWN} and {@link #UP} respectively, and
     * the server counts, so the side that moves is decided in one place.
     */
    public static final int UP = 1;
    public static final int DOWN = -1;

    public static void encode(SuspensionActionMessage message, RegistryFriendlyByteBuf buffer) {
        buffer.writeByte(message.axis);
        buffer.writeByte(message.direction);
    }

    public static SuspensionActionMessage decode(RegistryFriendlyByteBuf buffer) {
        return new SuspensionActionMessage(buffer.readByte(), buffer.readByte());
    }

    public static void handle(SuspensionActionMessage message, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }
        if (sender == null) {
                return;
        }
        context.enqueueWork(() -> {
            AbstractVehicle vehicle = OperatedRound.vehicleOf(sender);
            Suspension.Settings settings = vehicle == null ? null
                    : ArmorSystem.suspensionFor(vehicle);
            if (vehicle == null || settings == null || !(vehicle instanceof SuspensionState state)) {
                // A vehicle with no suspension, nobody riding anything, or a vehicle class the mixin
                // did not reach. All three answer the same way, because all three leave the driver
                // with nothing to see - and saying so is the difference between "this tank has no
                // suspension" and "the key is not wired up".
                send(sender, vehicle, null, settings, true);
                return;
            }

            Suspension.Pose before = state.ywzjarmor$suspensionPose();
            // Ride height and pitch are the driver's controls, on the same rule the real vehicle
            // uses: the machine is driven from seat zero, and a passenger reaching over the
            // driver's shoulder is not part of the crew layout. The reply still carries the pose,
            // so a refused press does not look like a lost packet.
            if (!CrewState.requireDriver(sender, vehicle)) {
                send(sender, vehicle, before, settings, true);
                return;
            }
            Suspension.Pose after;
            if (message.axis == PITCH) {
                after = new Suspension.Pose(before.height(),
                        Suspension.step(before.pitch(), Suspension.pitchSteps(settings),
                                message.direction()),
                        before.roll());
            } else if (message.axis == ROLL) {
                after = new Suspension.Pose(before.height(), before.pitch(),
                        Suspension.step(before.roll(), Suspension.rollSteps(settings),
                                message.direction()));
            } else {
                after = new Suspension.Pose(
                        Suspension.step(before.height(), Suspension.heightSteps(settings),
                                message.direction()),
                        before.pitch(), before.roll());
            }
            state.ywzjarmor$setSuspensionPose(after);
            // Names the axis and where the step went, because "the key answered and nothing moved"
            // has two very different causes - a press at the end of its travel, which is this line
            // reading 2->2, and a wheel that was never asked for anything, which is the same line
            // absent. The measurement alone cannot tell them apart.
            VehicleArmorMod.LOGGER.info(
                    "[Vehicle Armor] {} suspension {} {} -> {} on {}: {} blocks, {} degrees, {} of lean",
                    sender.getScoreboardName(), axisName(message.axis),
                    stepIndex(before, message.axis), stepIndex(after, message.axis),
                    vehicle.getVehicleId(),
                    Suspension.height(settings, after.height()),
                    Suspension.pitch(settings, after.pitch()),
                    Suspension.roll(settings, after.roll()));
            // Everyone aboard, not just the driver: 0.6.2 draws the wheel from the synced
            // compression, and the compression does not change when the rest length does, so a
            // passenger who was not told the pose would see the running gear float. Only the
            // driver's copy is announced in the action bar.
            broadcast(vehicle, after, settings, sender);
        });
    }

    /**
     * Sends the vehicle's current pose to one player, without a line.
     *
     * <p>Called when someone takes a seat: the pose is not saved, but a crew member entering a tank
     * another driver has already raised still has to see it at that height.
     */
    public static void syncPose(ServerPlayer player, AbstractVehicle vehicle) {
        if (player == null || vehicle == null) {
            return;
        }
        Suspension.Settings settings = ArmorSystem.suspensionFor(vehicle);
        if (settings == null || !(vehicle instanceof SuspensionState state)) {
            return;
        }
        send(player, vehicle, state.ywzjarmor$suspensionPose(), settings, false);
    }

    private static void broadcast(AbstractVehicle vehicle, Suspension.Pose pose,
                                  Suspension.Settings settings, ServerPlayer announcer) {
        for (net.minecraft.world.entity.Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof ServerPlayer player) {
                send(player, vehicle, pose, settings, player == announcer);
            }
        }
    }

    private static void send(ServerPlayer player, AbstractVehicle vehicle, Suspension.Pose pose,
                             Suspension.Settings settings, boolean announce) {
        PacketDistributor.sendToPlayer(player,
                message(vehicle, pose, settings, announce));
    }

    private static SuspensionStateMessage message(AbstractVehicle vehicle, Suspension.Pose pose,
                                                  Suspension.Settings settings,
                                                  boolean announce) {
        int id = vehicle == null ? -1 : vehicle.getId();
        if (vehicle == null || pose == null || settings == null) {
            return new SuspensionStateMessage(id, false, false, 0, 0, 0,
                    0.0F, 0.0F, 0.0F, announce);
        }
        return new SuspensionStateMessage(id, true, settings.max_roll > 0.0F,
                pose.height(), pose.pitch(), pose.roll(),
                Suspension.height(settings, pose.height()),
                Suspension.pitch(settings, pose.pitch()),
                Suspension.roll(settings, pose.roll()), announce);
    }

    /** @return the axis name for the log line, so every axis is as easy to read off as height was. */
    private static String axisName(int axis) {
        return axis == PITCH ? "pitch" : axis == ROLL ? "roll" : "height";
    }

    /** @return the step index the given axis is at, for the log line's {@code before -> after}. */
    private static int stepIndex(Suspension.Pose pose, int axis) {
        return axis == PITCH ? pose.pitch() : axis == ROLL ? pose.roll() : pose.height();
    }
}
