package com.ywzjvehicle.armor.network;

import com.ywzjvehicle.armor.Fuze;
import com.ywzjvehicle.armor.GunnerSettings;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Server to client: this is what your gun is set to now.
 *
 * <p>Sent once when a player joins and again after every change. Beyond keeping the readout honest
 * it doubles as the proof that the server is running the addon at all: a client that has never
 * received one has no business acting on the keys, and a client that has, has.
 *
 * <p>Nothing here names a client-only class - the built line is handed to the HUD through
 * {@link GunnerSettings}, and it is the client's own tick handler that displays it. That keeps this
 * class loadable on a dedicated server, where it is still registered and still sent.
 */
public record GunnerStateMessage(int fuze, int delayTicks, int variant, boolean quiet) {

    public static final int ID = 1;

    /** @return the current state of the given player, to be announced. */
    public static GunnerStateMessage of(UUID id) {
        return of(id, false);
    }

    /**
     * @param quiet {@code true} for the message sent at login, which is there to prove the server
     *              has the addon rather than to report a change the player just made. Announcing it
     *              would put a readout on the screen of everyone who joins, including those who
     *              never touch the keys.
     */
    public static GunnerStateMessage of(UUID id, boolean quiet) {
        GunnerSettings.Setting setting = GunnerSettings.snapshot(id);
        return new GunnerStateMessage(setting.fuze.ordinal(), setting.delayTicks, setting.variant,
                quiet);
    }

    /**
     * Written as plain bytes rather than var-ints on purpose: {@code writeByte} and {@code readByte}
     * come from netty and so keep their names in the SRG jar the addon compiles against, while the
     * var-int pair are Minecraft's own. Every value fits in a signed byte - three fuzes, a delay of
     * at most a hundred ticks, a load index, and a flag.
     */
    public static void encode(GunnerStateMessage message, FriendlyByteBuf buffer) {
        buffer.writeByte(message.fuze);
        buffer.writeByte(message.delayTicks);
        buffer.writeByte(message.variant);
        buffer.writeByte(message.quiet ? 1 : 0);
    }

    public static GunnerStateMessage decode(FriendlyByteBuf buffer) {
        return new GunnerStateMessage(buffer.readByte(), buffer.readByte(), buffer.readByte(),
                buffer.readByte() != 0);
    }

    public static void handle(GunnerStateMessage message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        // Only ever sent towards a client, so this is a guard against a malformed sender rather
        // than a real path - but acting on it server-side would let a client rewrite shared state.
        if (!context.getDirection().getReceptionSide().isClient()) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            Fuze fuze = Fuze.byId(message.fuze);
            // What to say is decided on the client's tick, by comparing this with the state before
            // it - the server knows what it changed, but only the client knows what to call it.
            GunnerSettings.setClient(fuze, message.delayTicks, message.variant, message.quiet);
            GunnerSettings.markServerPresent();
        });
        context.setPacketHandled(true);
    }
}
