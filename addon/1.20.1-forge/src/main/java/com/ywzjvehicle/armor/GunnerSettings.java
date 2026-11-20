package com.ywzjvehicle.armor;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What each gunner has their gun set to: the fuze, how long a delay fuze waits, and which mark of
 * a multi-mark round the slot is loaded with.
 *
 * <p>This is state a player carries rather than state a shell carries: two M1A1s on the same server
 * can have different settings, and a shell adopts whatever its gunner had selected at the moment it
 * was fired. Ownership is therefore per player, and the server is the authority - the client's copy
 * exists only so the readout can say what it was just set to.
 *
 * <p>The server map is a plain {@link HashMap} rather than a capability because every read and write
 * happens on the server thread: the packet handlers run through {@code enqueueWork} and the shells
 * read it from their own tick. Entries are dropped when a player logs out, so a returning player
 * starts from the default instead of inheriting what they last had in a different session.
 *
 * <p>The load index is one number per player rather than one per slot, which is enough because the
 * game has exactly one slot that offers a choice. It is resolved against whichever slot the gunner
 * is operating, and clamped to that slot's list - so if a second such slot ever appears, both would
 * share the choice rather than one of them reading off the end of an array.
 */
public final class GunnerSettings {

    private GunnerSettings() {
    }

    /** Shortest delay a gunner can select: half a second. */
    public static final int DELAY_MIN_TICKS = 10;

    /** Longest delay a gunner can select: five seconds. */
    public static final int DELAY_MAX_TICKS = 100;

    /** How much one press of the right arrow key adds. */
    public static final int DELAY_STEP_TICKS = 10;

    /** One player's settings. */
    public static final class Setting {
        public Fuze fuze = Fuze.IMPACT;
        public int delayTicks = DELAY_MIN_TICKS;

        /**
         * Which load the slot is filled with: {@code 0} is the round the vehicle pack names it for,
         * the rest are the slot's variants in order.
         */
        public int variant;

        /**
         * The load being fitted, or {@code -1} while nothing is being changed.
         *
         * <p>Kept apart from {@link #variant} because a round is not in the gun until the gun has
         * been re-fed with it. Choosing in the chooser is asking for it; the gun reloads over the
         * next few seconds, and until that finishes the round that comes out is still the old one.
         * Collapsing the two would be the same mistake as a magazine that refills itself the moment
         * the gunner thinks about it.
         */
        public int pending = -1;

        public void copyFrom(Setting other) {
            this.fuze = other.fuze;
            this.delayTicks = other.delayTicks;
            this.variant = other.variant;
            this.pending = other.pending;
        }
    }

    private static final Map<UUID, Setting> SERVER = new HashMap<>();
    private static final Setting CLIENT = new Setting();

    /**
     * What a player who has never touched the keys gets. Deliberately left out of the server map by
     * {@link #get}, so a shell fired by such a player does not quietly create an entry for them.
     */
    private static final Setting DEFAULT = new Setting();

    /** @return the given player's settings, or the defaults without recording them. */
    public static Setting get(UUID id) {
        Setting setting = id == null ? null : SERVER.get(id);
        return setting == null ? DEFAULT : setting;
    }

    /**
     * @return whether this player has ever changed anything, as opposed to being handed the
     *         defaults. Reported when a shell arms itself, because "the fuze was IMPACT" and "nobody
     *         ever set a fuze" look identical from the outside and only one of them is a bug.
     */
    public static boolean hasEntry(UUID id) {
        return id != null && SERVER.containsKey(id);
    }

    private static Setting edit(UUID id) {
        return SERVER.computeIfAbsent(id, key -> new Setting());
    }

    /** Walks a player's fuze on to the next one. @return the settings after the change. */
    public static Setting cycleFuze(UUID id) {
        Setting setting = edit(id);
        setting.fuze = setting.fuze.next();
        return setting;
    }

    /**
     * Advances a player's delay by one step, wrapping from the longest back to the shortest.
     *
     * @return the settings after the change.
     */
    public static Setting stepDelay(UUID id) {
        Setting setting = edit(id);
        setting.delayTicks += DELAY_STEP_TICKS;
        if (setting.delayTicks > DELAY_MAX_TICKS) {
            setting.delayTicks = DELAY_MIN_TICKS;
        }
        return setting;
    }

    /**
     * Asks for a slot to be filled with one of its marks.
     *
     * <p><b>This does not put the round in the gun.</b> It records what the gunner wants, and the
     * weapon is re-fed; {@link #commitLoad} is what makes it the round that comes out, and it is
     * called when that reload finishes. Asking twice before the first finishes simply replaces the
     * request - the gunner changed their mind, and the gun has not been fed yet either way.
     *
     * <p>Clamped to the range the slot actually offers, which the caller has already checked - the
     * index arrives from a client, and a client is not something to index an array with.
     *
     * @return the settings after the change.
     */
    public static Setting beginLoad(UUID id, int index) {
        Setting setting = edit(id);
        setting.pending = Math.max(0, index);
        return setting;
    }

    /**
     * Fits whatever was asked for, once the gun has been re-fed.
     *
     * @return the load now in the gun, or {@code -1} when nothing was waiting - which is the answer
     *         for a gun that reloaded for its own reasons, and is why the caller has to check rather
     *         than announce a round nobody chose.
     */
    public static int commitLoad(UUID id) {
        Setting setting = id == null ? null : SERVER.get(id);
        if (setting == null || setting.pending < 0) {
            return -1;
        }
        setting.variant = setting.pending;
        setting.pending = -1;
        return setting.variant;
    }

    /** Forgets a player's settings, so their next session starts from the defaults. */
    public static void forget(Player player) {
        if (player != null) {
            forget(player.m_20148_());
        }
    }

    /** Forgets one player's settings by id, which is also how a test gets a clean slate. */
    public static void forget(UUID id) {
        if (id != null) {
            SERVER.remove(id);
        }
    }

    /** @return a copy of the given settings, for putting on the wire. */
    public static Setting snapshot(UUID id) {
        Setting copy = new Setting();
        copy.copyFrom(get(id));
        return copy;
    }

    // --- the client's copy -------------------------------------------------------------------

    /** @return the client's copy of its own settings. Never null. */
    public static Setting client() {
        return CLIENT;
    }

    // --- the client's live telemetry for the persistent HUD ----------------------------------

    /**
     * The last suspension state the server reported, kept for the HUD.
     *
     * <p>The pose itself is not synced to the client - only the driver who pressed the key gets the
     * reply, and the base mod carries the resulting compression separately. A HUD that wants to
     * show "0.05 up, 1.0 nose-up" therefore has to remember the last authoritative answer rather
     * than recompute it, which is what these fields are. Before the first press the HUD says the
     * mechanism is ready instead of inventing a pose.
     *
     * <p>The lean is the third measurement, and it is zero on every vehicle without a roll axis -
     * the HUD asks the vehicle's settings whether the row applies rather than reading this number.
     */
    private static boolean suspensionReported;
    private static boolean suspensionPresent;
    private static float suspensionRise;
    private static float suspensionPitch;
    private static float suspensionRoll;

    public static void setClientSuspension(boolean present, float rise, float pitchDegrees,
                                           float rollDegrees) {
        suspensionReported = true;
        suspensionPresent = present;
        suspensionRise = rise;
        suspensionPitch = pitchDegrees;
        suspensionRoll = rollDegrees;
    }

    public static boolean suspensionReported() {
        return suspensionReported;
    }

    public static boolean suspensionPresent() {
        return suspensionPresent;
    }

    public static float suspensionRise() {
        return suspensionRise;
    }

    public static float suspensionPitch() {
        return suspensionPitch;
    }

    /** @return the last lean the server reported, in degrees; positive is the right side down. */
    public static float suspensionRoll() {
        return suspensionRoll;
    }

    private static boolean quietSync;

    /**
     * Replaces the client's copy. Only ever called with what the server just sent.
     *
     * @param quiet whether this is the greeting rather than a change
     */
    public static void setClient(Fuze fuze, int delayTicks, int variant, boolean quiet) {
        CLIENT.fuze = fuze;
        CLIENT.delayTicks = delayTicks;
        CLIENT.variant = variant;
        quietSync = quiet;
    }

    /**
     * @return whether the state that just arrived was the greeting, and clears the mark.
     *
     * <p>Read by the tick handler rather than by the message handler, because what to announce is
     * decided by comparing the new state with the previous one - and comparing needs somewhere to
     * compare from, which the greeting is what establishes.
     */
    public static boolean consumeQuietSync() {
        boolean quiet = quietSync;
        quietSync = false;
        return quiet;
    }

    private static boolean serverPresent;

    /**
     * The line waiting to be shown. Volatile because it is written from the network thread - that is
     * the whole point of parking it - and read from the client tick, and a plain field gives no
     * guarantee the tick ever sees what the handler wrote.
     */
    private static volatile Component pending;

    /**
     * Records that the server has spoken. There is no other way for a client to tell whether the
     * addon is installed on the other end - {@code isRemotePresent} answers the opposite question -
     * and without it the gunner would press the key, see nothing happen, and have no way to know
     * whether the key or the server was at fault.
     */
    public static void markServerPresent() {
        serverPresent = true;
    }

    /** @return whether this client has heard from the addon on the server it is connected to. */
    public static boolean serverPresent() {
        return serverPresent;
    }

    /** Forgets the server's existence, so the next connection has to prove itself again. */
    public static void forgetServer() {
        serverPresent = false;
    }

    /**
     * Parks an action-bar line for the client's own tick handler to display.
     *
     * <p>Indirect so that the network handler never names a client-only class: the message is built
     * here, on whichever side, and it is the client's HUD code that decides when to draw it.
     */
    public static void queueFeedback(Component message) {
        pending = message;
    }

    /** @return the parked line and clears it, or null when there is nothing to show. */
    public static Component takeFeedback() {
        Component message = pending;
        pending = null;
        return message;
    }

    /** Forgets everything about a connection, for when the client leaves one. */
    public static void resetClient() {
        CLIENT.fuze = Fuze.IMPACT;
        CLIENT.delayTicks = DELAY_MIN_TICKS;
        CLIENT.variant = 0;
        CLIENT.pending = -1;
        pending = null;
        suspensionReported = false;
        suspensionPresent = false;
        suspensionRise = 0.0F;
        suspensionPitch = 0.0F;
        suspensionRoll = 0.0F;
    }

    /**
     * An action-bar line describing a setting, built from a translated fuze name and, for a delay
     * fuze, the delay in seconds.
     *
     * <p>Building this is safe on either side - it is only the display of it that is client-only -
     * so the network handler can hand it straight to whatever shows it.
     */
    public static Component describe(Setting setting) {
        Component fuze = Component.m_237115_(setting.fuze.translationKey());
        if (setting.fuze != Fuze.DELAY) {
            return Component.m_237110_("fuze." + VehicleArmorMod.MOD_ID + ".set", fuze);
        }
        // Ticks are stored whole; the readout is the only place the half-seconds show up.
        float seconds = setting.delayTicks / 20.0F;
        return Component.m_237110_("fuze." + VehicleArmorMod.MOD_ID + ".set_delay", fuze,
                String.format(java.util.Locale.ROOT, "%.1f", seconds));
    }

    /** An action-bar line naming the round a slot has just been loaded with. */
    public static Component describeLoad(String name) {
        return Component.m_237110_("load." + VehicleArmorMod.MOD_ID + ".set", name);
    }

    /**
     * An action-bar line for a round that has been asked for and is not in the gun yet.
     *
     * <p>Said at the moment of choosing, by the client, because the round going in is seconds away
     * and a chooser that closed in silence would read as the click having done nothing - which is
     * exactly how "the load chooser does nothing" was reported the first time.
     */
    public static Component describeLoading(String name) {
        return Component.m_237110_("load." + VehicleArmorMod.MOD_ID + ".loading", name);
    }
}
