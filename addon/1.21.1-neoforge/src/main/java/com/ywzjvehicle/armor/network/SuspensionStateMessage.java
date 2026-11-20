package com.ywzjvehicle.armor.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.PacketFlow;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import com.ywzjvehicle.armor.VehicleArmorMod;

import com.ywzjvehicle.armor.Suspension;

/**
 * Server to client: this is where the suspension now is, on a named vehicle.
 *
 * <p><b>It names the vehicle because the pose has to reach the client that draws the wheels.</b>
 * Until base mod 0.6.2 a client worked the wheel's visual travel out for itself, by probing the
 * ground from the hull it had already been told about - so the pose only had to travel far enough to
 * print a line in the action bar. 0.6.2 removed that probe and draws the wheel from the compressed
 * length the server synced, which is unchanged by raising the hull: the spring settles at the same
 * compression while the hull moves. Without the pose, a raised tank therefore has its wheels hanging
 * in the air on every client but the one that pressed the key. Sending it here puts both sides on
 * the same rest length.
 *
 * <p>The steps travel rather than only the measurements because the receiving side has to re-create
 * the {@link Suspension.Pose} the server holds; it resolves them against its own config, exactly as
 * the server did. The measurements still travel because the action-bar line names them and because
 * a client whose config was edited to a different range should not relabel the server's answer.
 *
 * <p>{@code announce} says whether this copy is the one the player asked for. A broadcast goes to
 * every rider so their wheels agree; only the sender gets the line.
 *
 * <p>The roll axis travels with the other two, and {@code hasRoll} says whether the vehicle has one
 * at all. Both are needed: the step is what re-creates the pose, and the flag is what tells the
 * action-bar line and the HUD whether a third number means anything on this tank.
 */
public record SuspensionStateMessage(int vehicleEntityId, boolean hasSuspension, boolean hasRoll,
                                     int heightStep, int pitchStep, int rollStep,
                                     float rise, float pitchDegrees, float rollDegrees,
                                     boolean announce) implements CustomPacketPayload {

    public static final int ID = 5;

    public static final CustomPacketPayload.Type<SuspensionStateMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    VehicleArmorMod.MOD_ID, "suspension_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SuspensionStateMessage> CODEC =
            CustomPacketPayload.codec(SuspensionStateMessage::encode, SuspensionStateMessage::decode);

    @Override
    public CustomPacketPayload.Type<SuspensionStateMessage> type() {
        return TYPE;
    }

    /** Plain bytes and floats, no var-ints: netty's methods keep their names in the SRG jar. */
    public static void encode(SuspensionStateMessage message, RegistryFriendlyByteBuf buffer) {
        buffer.writeInt(message.vehicleEntityId);
        buffer.writeByte(message.hasSuspension ? 1 : 0);
        buffer.writeByte(message.hasRoll ? 1 : 0);
        buffer.writeByte(message.heightStep);
        buffer.writeByte(message.pitchStep);
        buffer.writeByte(message.rollStep);
        buffer.writeFloat(message.rise);
        buffer.writeFloat(message.pitchDegrees);
        buffer.writeFloat(message.rollDegrees);
        buffer.writeByte(message.announce ? 1 : 0);
    }

    public static SuspensionStateMessage decode(RegistryFriendlyByteBuf buffer) {
        return new SuspensionStateMessage(buffer.readInt(), buffer.readByte() != 0,
                buffer.readByte() != 0, buffer.readByte(), buffer.readByte(), buffer.readByte(),
                buffer.readFloat(), buffer.readFloat(), buffer.readFloat(),
                buffer.readByte() != 0);
    }

    public static void handle(SuspensionStateMessage message, IPayloadContext context) {
        if (context.flow() != PacketFlow.CLIENTBOUND) {
            return;
        }
        // DistExecutor rather than a direct call so the client-only class is never loaded by a
        // dedicated server: this message is registered on both sides, and a class reference in a
        // branch that is not taken is still a reference in the constant pool.
        context.enqueueWork(() -> com.ywzjvehicle.armor.client.ClientSuspensionSync.apply(message));
    }
}
