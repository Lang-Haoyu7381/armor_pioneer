package com.ywzjvehicle.armor.network;

import com.ywzjvehicle.armor.ArmorSystem;
import com.ywzjvehicle.armor.CrewState;
import com.ywzjvehicle.armor.OperatedRound;
import com.ywzjvehicle.armor.Suspension;
import com.ywzjvehicle.armor.SuspensionState;
import com.ywzjvehicle.armor.VehicleArmorMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.function.Supplier;

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
public record SuspensionActionMessage(int axis, int direction) {

    public static final int ID = 4;

    /** The two axes, in the order their keys are laid out. */
    public static final int HEIGHT = 0;
    public static final int PITCH = 1;

    /** Which way a press asked to go. Anything non-negative raises, so a stray byte cannot lower. */
    public static final int UP = 1;
    public static final int DOWN = -1;

    public static void encode(SuspensionActionMessage message, FriendlyByteBuf buffer) {
        buffer.writeByte(message.axis);
        buffer.writeByte(message.direction);
    }

    public static SuspensionActionMessage decode(FriendlyByteBuf buffer) {
        return new SuspensionActionMessage(buffer.readByte(), buffer.readByte());
    }

    public static void handle(SuspensionActionMessage message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer sender = context.getSender();
        if (sender == null) {
            context.setPacketHandled(true);
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
                reply(sender, null, settings);
                return;
            }

            Suspension.Pose before = state.ywzjarmor$suspensionPose();
            // Ride height and pitch are the driver's controls, on the same rule the real vehicle
            // uses: the machine is driven from seat zero, and a passenger reaching over the
            // driver's shoulder is not part of the crew layout. The reply still carries the pose,
            // so a refused press does not look like a lost packet.
            if (!CrewState.requireDriver(sender, vehicle)) {
                reply(sender, before, settings);
                return;
            }
            Suspension.Pose after = message.axis == PITCH
                    ? new Suspension.Pose(before.height(), Suspension.step(before.pitch(),
                            Suspension.pitchSteps(settings), message.direction()))
                    : new Suspension.Pose(Suspension.step(before.height(),
                            Suspension.heightSteps(settings), message.direction()), before.pitch());
            state.ywzjarmor$setSuspensionPose(after);
            // Names the axis and where the step went, because "the key answered and nothing moved"
            // has two very different causes - a press at the end of its travel, which is this line
            // reading 2->2, and a wheel that was never asked for anything, which is the same line
            // absent. The measurement alone cannot tell them apart.
            VehicleArmorMod.LOGGER.info("[Vehicle Armor] {} suspension {} {} -> {} on {}: {} blocks, {} degrees",
                    sender.m_6302_(), message.axis == PITCH ? "pitch" : "height",
                    message.axis == PITCH ? before.pitch() : before.height(),
                    message.axis == PITCH ? after.pitch() : after.height(),
                    vehicle.getVehicleId(),
                    Suspension.height(settings, after.height()),
                    Suspension.pitch(settings, after.pitch()));
            reply(sender, after, settings);
        });
        context.setPacketHandled(true);
    }

    private static void reply(ServerPlayer player, Suspension.Pose pose,
                             Suspension.Settings settings) {
        VehicleArmorNetwork.channel().send(PacketDistributor.PLAYER.with(() -> player),
                pose == null || settings == null
                        ? new SuspensionStateMessage(false, 0.0F, 0.0F)
                        : new SuspensionStateMessage(true,
                                Suspension.height(settings, pose.height()),
                                Suspension.pitch(settings, pose.pitch())));
    }
}
