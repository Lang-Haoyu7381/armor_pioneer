package com.ywzjvehicle.armor.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * The addon's one network registration, carrying a gunner's fuze selections and a driver's fording
 * kit between a client and the server.
 *
 * <p>Registered on both sides unconditionally: a client that has the addon needs the payloads in
 * order to send anything at all, and a server that has them needs them in order to answer.
 *
 * <p><b>NeoForge has no SimpleChannel.</b> 1.21 replaced Forge's channel abstraction with typed
 * payloads registered against a protocol version, so the per-message ids of the 1.20.1 build are
 * gone. The version string survives and still does the job the old channel's did: a client and a
 * server that disagree are refused rather than left to misread each other's packets. Each message
 * carries its own {@code Type} and {@code StreamCodec}; see the records in this package.
 *
 * <p>One registrar rather than one per feature: the messages share the property that matters - each
 * is a key the player presses and a decision the server makes - and a second registrar would be a
 * second place for the "is the addon on the other end" answer to come from.
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
     * taken the gun, so the owner's own client follows the server's aim instead of its mouse. At "8"
     * the suspension reply names the vehicle and carries the pose's step indices, because base mod
     * 0.6.2 draws the wheels from the synced compression and every client now needs the rest length
     * the server is using. At "9" the reply also carries the roll step and its measurement, for the
     * lateral axis the K2's in-arm suspension has and the Type 10 now carries too. At "10" the smoke
     * bank gained its three-group request. At "11" the commander's Left Ctrl is a toggle rather than
     * a hold, and Left Shift toggles the driving takeover. At "12" the ZTZ-100's hybrid drive joins
     * the channel: a toggle request and a state reply, because the battery and the electric mode are
     * the server's to decide and every nearby client needs the mode for the engine sound. At "13"
     * the crew state also carries whether the commander has switched the vehicle back to the base
     * mod's own controls. At "14" the addon's own smoke-group message is gone. At "15" the
     * commander's Left Ctrl is the solo-control toggle and T cycles the weapon that control fires.
     * At "16" the fording reply carries the phase, its percentage and the reason the server spoke:
     * the kit is a timed procedure now, and a client still reading the old one-byte "deployed" flag
     * would draw a stowed kit as ready for the whole thirty seconds.
     */
    private static final String VERSION = "16";

    /**
     * Called from the mod constructor as a listener on the mod bus.
     *
     * <p>{@code RegisterPayloadHandlersEvent} is a mod-bus event; the registrar it hands back is
     * mutable, so every message is registered against this one object.
     */
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);

        registrar.playToServer(GunnerActionMessage.TYPE, GunnerActionMessage.CODEC,
                GunnerActionMessage::handle);
        registrar.playToClient(GunnerStateMessage.TYPE, GunnerStateMessage.CODEC,
                GunnerStateMessage::handle);
        registrar.playToServer(FordingActionMessage.TYPE, FordingActionMessage.CODEC,
                FordingActionMessage::handle);
        registrar.playToClient(FordingStateMessage.TYPE, FordingStateMessage.CODEC,
                FordingStateMessage::handle);
        registrar.playToServer(SuspensionActionMessage.TYPE, SuspensionActionMessage.CODEC,
                SuspensionActionMessage::handle);
        registrar.playToClient(SuspensionStateMessage.TYPE, SuspensionStateMessage.CODEC,
                SuspensionStateMessage::handle);
        registrar.playToServer(CrewActionMessage.TYPE, CrewActionMessage.CODEC,
                CrewActionMessage::handle);
        registrar.playToClient(CrewStateMessage.TYPE, CrewStateMessage.CODEC,
                CrewStateMessage::handle);
        registrar.playToServer(DoorActionMessage.TYPE, DoorActionMessage.CODEC,
                DoorActionMessage::handle);
        registrar.playToServer(HybridActionMessage.TYPE, HybridActionMessage.CODEC,
                HybridActionMessage::handle);
        registrar.playToClient(HybridStateMessage.TYPE, HybridStateMessage.CODEC,
                HybridStateMessage::handle);
    }
}
