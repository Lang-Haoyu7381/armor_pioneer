package com.ywzjvehicle.armor;

import net.neoforged.neoforge.network.PacketDistributor;

import com.ywzjvehicle.armor.network.GunnerStateMessage;
import com.ywzjvehicle.armor.network.VehicleArmorNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;

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
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        UUID id = player.getUUID();
        if (!greeted.add(id)) {
            return;
        }
        PacketDistributor.sendToPlayer(player,
                GunnerStateMessage.of(id, true));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            greeted.remove(player.getUUID());
        }
        GunnerSettings.forget(event.getEntity());
    }
}
