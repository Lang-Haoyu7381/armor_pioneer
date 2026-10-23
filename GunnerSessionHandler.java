package com.ywzjvehicle.armor;

import com.ywzjvehicle.armor.network.GunnerStateMessage;
import com.ywzjvehicle.armor.network.VehicleArmorNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Tells each player which fuze they have selected, and forgets it when they leave.
 *
 * <p>The greeting goes out on the player's first tick in the level rather than on the login event.
 * Login fires while the connection is still being set up, and a message sent then can be dropped -
 * which is exactly the kind of failure that leaves a client convinced the server has no addon, and
 * leaves nobody able to tell why. A tick is late enough that the connection is certainly usable.
 *
 * <p>Nothing else depends on the greeting arriving any more - the client's keys work whether or not
 * it ever hears one, and a reply to any of them proves the server's presence just as well. It is
 * kept because it makes the first press behave like every other one instead of being the packet
 * that discovers the server.
 *
 * <p>Forgetting on logout is the other half. Selections are keyed by UUID and would otherwise
 * outlive the session, so a player would come back to a fuze they set days ago on a different
 * server and have no reason to suspect it.
 */
public final class GunnerSessionHandler {

    private GunnerSessionHandler() {
    }

    /** Players already greeted this session; emptied as they leave. */
    private static final Set<UUID> greeted = new HashSet<>();

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END
                || !(event.player instanceof ServerPlayer player)) {
            return;
        }
        UUID id = player.m_20148_();
        if (!greeted.add(id)) {
            return;
        }
        VehicleArmorNetwork.channel().send(PacketDistributor.PLAYER.with(() -> player),
                GunnerStateMessage.of(id, true));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            greeted.remove(player.m_20148_());
        }
        GunnerSettings.forget(event.getEntity());
    }
}
