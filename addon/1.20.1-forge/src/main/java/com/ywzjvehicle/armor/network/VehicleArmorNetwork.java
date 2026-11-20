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
     * right behaviour for a version that cannot read the reply. At "8" the suspension reply names
     * the vehicle and carries the pose's step indices, because base mod 0.6.2 draws the wheels from
     * the synced compression and every client now needs the rest length the server is using. At "9"
     * the reply also carries the roll step and its measurement, for the lateral axis the K2's
     * in-arm suspension has and the Type 10 now carries too. At "10" the smoke bank gained its
     * three-group request: the pack's
     * launcher is no longer independent, so the addon's smoke key is the thing that fires it. At
     * "11" the commander's Left Ctrl is a toggle rather than a hold, and Left Shift toggles the
     * driving takeover; both travel as new actions in the existing crew message, but a client that
     * still holds the old key semantics cannot share a server with this one. At "12" the ZTZ-100's
     * hybrid drive joins the channel: a toggle request and a state reply, because the battery and
     * the electric mode are the server's to decide and every nearby client needs the mode for the
     * engine sound. At "13" the crew state also carries whether the commander has switched the
     * vehicle back to the base mod's own controls: the switch survives leaving the seat, so a
     * client that cannot read the flag would draw a HUD that disagrees with the gun. At "14" the
     * addon's own smoke-group message is gone: the Armor Pioneer banks use the base mod's H and
     * its independent ripple launcher again, so a client and server must agree that id 9 no
     * longer exists. At "15" the commander's Left Ctrl is the solo-control toggle and T cycles the
     * weapon that control fires: the new action value and the renamed solo flag only mean the same
     * thing on both sides of the new build. At "16" the fording reply carries the phase, its
     * percentage and the reason the server spoke: the kit is a timed procedure now, and a client
     * still reading the old one-byte "deployed" flag would draw a stowed kit as ready for the whole
     * thirty seconds.
     */
    private static final String VERSION = "16";

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
        CHANNEL.registerMessage(HybridActionMessage.ID, HybridActionMessage.class,
                HybridActionMessage::encode, HybridActionMessage::decode,
                HybridActionMessage::handle);
        CHANNEL.registerMessage(HybridStateMessage.ID, HybridStateMessage.class,
                HybridStateMessage::encode, HybridStateMessage::decode,
                HybridStateMessage::handle);
    }

    public static SimpleChannel channel() {
        return CHANNEL;
    }
}
