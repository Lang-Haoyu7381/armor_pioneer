package com.ywzjvehicle.armor.network;

import com.ywzjvehicle.armor.VehicleArmorMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * The addon's one network channel, carrying a gunner's fuze selections and a driver's fording kit
 * between a client and the server.
 *
 * <p>Registered on both sides unconditionally: a client that has the addon needs the channel in
 * order to send anything at all, and a server that has it needs the channel in order to answer.
 * The message ids are fixed and small, since this is the only traffic on it.
 *
 * <p>One channel rather than one per feature: the two share the property that matters - each is a
 * key the player presses and a decision the server makes - and a second channel would be a second
 * place for the "is the addon on the other end" answer to come from.
 */
public final class VehicleArmorNetwork {

    private VehicleArmorNetwork() {
    }

    /**
     * Bumped whenever the message layout changes. A client and server that disagree are refused
     * rather than left to misread each other's packets.
     *
     * <p>At "2" the fording reply carries whether the vehicle's model has a snorkel at all. At "3"
     * there are two more messages, for the two suspension keys. At "4" the suspension request
     * carries a direction as well as an axis, because the keys that drive it are direction keys and
     * a step off the end of the travel is not a wrap. At "5" the commander's designation and aim
     * override join the same channel: they are the same shape - a key, and a decision the server
     * makes from its own seat table - and a second channel would be a second place for "is the addon
     * on the other end" to come from. At "6" the server also tells the crew when the commander has
     * taken the gun, so the owner's own client follows the server's aim instead of its mouse. An
     * older client on a newer server is refused the channel and falls back to what it already does
     * for a server without the addon - the keys say so rather than doing nothing - which is the
     * right behaviour for a version that cannot read the reply.
     */
    private static final String VERSION = "7";

    private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(VehicleArmorMod.MOD_ID, "fuze"))
            .networkProtocolVersion(() -> VERSION)
            .clientAcceptedVersions(VERSION::equals)
            .serverAcceptedVersions(VERSION::equals)
            .simpleChannel();

    /** Called from the mod constructor, so the channel exists before any player can connect. */
    public static void register() {
        CHANNEL.registerMessage(GunnerActionMessage.ID, GunnerActionMessage.class,
                GunnerActionMessage::encode, GunnerActionMessage::decode,
                GunnerActionMessage::handle);
        CHANNEL.registerMessage(GunnerStateMessage.ID, GunnerStateMessage.class,
                GunnerStateMessage::encode, GunnerStateMessage::decode,
                GunnerStateMessage::handle);
        CHANNEL.registerMessage(FordingActionMessage.ID, FordingActionMessage.class,
                FordingActionMessage::encode, FordingActionMessage::decode,
                FordingActionMessage::handle);
        CHANNEL.registerMessage(FordingStateMessage.ID, FordingStateMessage.class,
                FordingStateMessage::encode, FordingStateMessage::decode,
                FordingStateMessage::handle);
        CHANNEL.registerMessage(SuspensionActionMessage.ID, SuspensionActionMessage.class,
                SuspensionActionMessage::encode, SuspensionActionMessage::decode,
                SuspensionActionMessage::handle);
        CHANNEL.registerMessage(SuspensionStateMessage.ID, SuspensionStateMessage.class,
                SuspensionStateMessage::encode, SuspensionStateMessage::decode,
                SuspensionStateMessage::handle);
        CHANNEL.registerMessage(CrewActionMessage.ID, CrewActionMessage.class,
                CrewActionMessage::encode, CrewActionMessage::decode,
                CrewActionMessage::handle);
        CHANNEL.registerMessage(CrewStateMessage.ID, CrewStateMessage.class,
                CrewStateMessage::encode, CrewStateMessage::decode,
                CrewStateMessage::handle);
        CHANNEL.registerMessage(DoorActionMessage.ID, DoorActionMessage.class,
                DoorActionMessage::encode, DoorActionMessage::decode,
                DoorActionMessage::handle);
    }

    public static SimpleChannel channel() {
        return CHANNEL;
    }
}
