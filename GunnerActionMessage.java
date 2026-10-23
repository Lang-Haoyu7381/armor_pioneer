package com.ywzjvehicle.armor.network;

import com.ywzjvehicle.armor.ArmorConfig;
import com.ywzjvehicle.armor.CrewState;
import com.ywzjvehicle.armor.OperatedRound;
import com.ywzjvehicle.armor.GunnerSettings;
import com.ywzjvehicle.armor.RoundReload;
import com.ywzjvehicle.armor.VehicleArmorMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Client to server: the gunner pressed a key that changes what their gun is set to.
 *
 * <p>Only which key, and for the load choice which index, is sent - never the resulting state. The
 * server owns the setting, applies the change itself and then sends the result back, so a client
 * cannot put itself in a state the server disagrees with, nor name a load the addon does not have.
 */
public record GunnerActionMessage(int action, int value) {

    public static final int ID = 0;

    /** Left arrow: walk the fuze on to the next one. */
    public static final int CYCLE_FUZE = 0;

    /** Right arrow: advance the delay by one step. */
    public static final int STEP_DELAY = 1;

    /** Chosen from the load chooser: fill the slot with this mark of the round. */
    public static final int SELECT_VARIANT = 2;

    /** Minimum gap between "that key did nothing" reports, per player. */
    private static final long REJECT_INTERVAL_MILLIS = 5000L;

    private static final Map<UUID, Long> lastReject = new ConcurrentHashMap<>();

    public static void encode(GunnerActionMessage message, FriendlyByteBuf buffer) {
        buffer.writeByte(message.action);
        buffer.writeByte(message.value);
    }

    public static GunnerActionMessage decode(FriendlyByteBuf buffer) {
        return new GunnerActionMessage(buffer.readByte(), buffer.readByte());
    }

    public static void handle(GunnerActionMessage message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer sender = context.getSender();
        if (sender == null) {
            // No player attached means this did not come from a client at all; drop it rather
            // than acting on behalf of nobody.
            context.setPacketHandled(true);
            return;
        }
        // The change itself belongs on the server thread, which is what enqueueWork hands it to.
        context.enqueueWork(() -> {
            // A wounded gunner cannot work their weapon's settings either. Checked before the round
            // is even looked up, so the refusal is about the person and not about what is in the
            // breech - those are different answers and the player is told the right one.
            if (!CrewState.requireOperational(sender)) {
                return;
            }
            // Asked on the server rather than taken on the client's word. The client is the one
            // that has to guess which weapon is selected - it can be a tick behind, and it has no
            // authoritative seat list at all - so a client-side "is this the right round?" check
            // is a gate that can fail closed and leave the whole feature looking broken.
            ArmorConfig.Ammo round = OperatedRound.operatingRound(sender);
            if (round == null) {
                // Only worth a line when the vehicle does carry something this addon configures -
                // the arrow keys are a rotary-wing vehicle's controls too, and reporting those
                // would bury the case that matters.
                if (OperatedRound.anyRound(OperatedRound.vehicleOf(sender)) != null) {
                    reject(sender, "the weapon they operate is not one this addon configures");
                }
                return;
            }
            UUID id = sender.m_20148_();

            // What the action needs is checked here, one action at a time, rather than by asking
            // for a round that supports all of them. A dart has loads but no fuze to switch; the
            // multi-purpose round has a fuze but no loads. Filtering the round down to one that
            // supports everything would leave the other kind of key doing nothing at all.
            if (message.action == SELECT_VARIANT) {
                if (!round.selectable()) {
                    reject(sender, "that round has no other loads");
                    return;
                }
            } else if (!round.multiPurpose()) {
                reject(sender, "that round's fuze is fixed");
                return;
            }

            int variant = message.value;
            if (message.action == SELECT_VARIANT) {
                // Clamped rather than trusted: the index crossed a network, and the range it has to
                // be inside is this slot's own list, which only this side knows.
                variant = Math.max(0, Math.min(variant, round.variantCount() - 1));
                // **Asking is not loading.** The gun has to be re-fed with the new round, and until
                // that reload finishes the old one is what comes out - see RoundReload. So what is
                // recorded here is what the gunner wants, and the round arrives when the gun says
                // it has.
                if (variant == GunnerSettings.get(id).variant
                        && GunnerSettings.get(id).pending < 0) {
                    reject(sender, "that load is already in the gun");
                    return;
                }
                AbstractVehicleWeapon<?> weapon = OperatedRound.weaponOf(sender);
                if (!(weapon instanceof RoundReload reload)) {
                    reject(sender, "that weapon cannot be reloaded");
                    return;
                }
                GunnerSettings.beginLoad(id, variant);
                reload.ywzjarmor$startRoundReload();
                VehicleArmorMod.LOGGER.info("[Vehicle Armor] {} is loading {} ({}mm)",
                        sender.m_7755_().getString(), loadName(round, variant),
                        round.variant(variant).penetration);
            } else if (message.action == STEP_DELAY) {
                GunnerSettings.stepDelay(id);
            } else {
                GunnerSettings.cycleFuze(id);
            }

            if (message.action != SELECT_VARIANT) {
                GunnerSettings.Setting setting = GunnerSettings.get(id);
                VehicleArmorMod.LOGGER.info("[Vehicle Armor] {} fuze is now {} (delay {} ticks)",
                        sender.m_7755_().getString(), setting.fuze, setting.delayTicks);
            }
            // Answer with the state as it now stands, so the client's readout is what the server
            // will actually use on the next shot rather than what the client hoped it set. The
            // reply is also how the client learns the addon is on this server at all.
            //
            // For a load this reply deliberately says nothing new: the round has not gone in yet,
            // and the answer that reports it is sent when the reload finishes. The client announces
            // a load by noticing that it changed, so this one is silent on the action bar - which is
            // right, because nothing has changed yet.
            VehicleArmorNetwork.channel().send(PacketDistributor.PLAYER.with(() -> sender),
                    GunnerStateMessage.of(id));
        });
        context.setPacketHandled(true);
    }

    /** @return the designation of one of a slot's loads, falling back to the weapon's own name. */
    private static String loadName(ArmorConfig.Ammo round, int index) {
        return round.variantNames(round.name == null ? "?" : round.name).get(index);
    }

    /**
     * Records, but does not answer, an action that could not be applied.
     *
     * <p>Rate limited per player because the same key is a rotary-wing vehicle's control and a
     * tank's arrow keys, and one line per press would drown the log.
     *
     * <p>This used to be gated behind the same filter that picked the round out, so a press on a
     * round that filter rejected was dropped without a word - which is exactly how "the load
     * chooser does nothing" stayed invisible until it was reported.
     */
    private static void reject(ServerPlayer sender, String why) {
        long now = System.currentTimeMillis();
        Long last = lastReject.get(sender.m_20148_());
        if (last != null && now - last < REJECT_INTERVAL_MILLIS) {
            return;
        }
        lastReject.put(sender.m_20148_(), now);
        VehicleArmorMod.LOGGER.info("[Vehicle Armor] action from {} ignored: {}",
                sender.m_7755_().getString(), why);
    }
}
