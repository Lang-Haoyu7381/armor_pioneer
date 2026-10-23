package com.ywzjvehicle.armor;

import com.mojang.blaze3d.platform.InputConstants;
import com.ywzjvehicle.armor.network.CrewActionMessage;
import com.ywzjvehicle.armor.network.DoorActionMessage;
import com.ywzjvehicle.armor.network.FordingActionMessage;
import com.ywzjvehicle.armor.network.GunnerActionMessage;
import com.ywzjvehicle.armor.network.SuspensionActionMessage;
import com.ywzjvehicle.armor.network.VehicleArmorNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.ywzj.vehicle.custom.weapon.data.BaseVehicleWeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleMultiWeapons;

/**
 * The crew's keys: left and right arrows cycle the fuze and step its delay, K works the driver's
 * fording kit, up and down raise and lower the hull, and Alt with W or S pitches its nose.
 *
 * <p>Left Alt on its own still opens the choice of load for the round in the gun - it is the same
 * key, read as a chord key rather than as a key of its own, so the chooser waits for the release.
 * That is not a flourish: a screen blocks every key this handler reads, so a chooser that opened on
 * the press would eat the W of the very chord the pitch is made of.
 *
 * <p>A press is only sent while the player is riding a vehicle, and what it means is decided on the
 * server. The arrow keys are also read by the base mod as {@code functionalLeft} and
 * {@code functionalRight}, and now {@code functionalUp} and {@code functionalDown}, which a
 * rotary-wing or fixed-wing vehicle uses for its own controls - but this handler never cancels the
 * event and never consumes the key, so a helicopter flies exactly as it did, and the worst an
 * unmeant press costs is a packet the server throws away. On a ground vehicle nothing in the base
 * mod reads them at all, which is what leaves them free for a tank's own controls.
 *
 * <p>The four suspension keys are the ones with a direction, and that is not decoration: a key that
 * says "up" has to stop at the top rather than wrap to the bottom, so the server is told which way
 * and works out where that lands. See {@code SuspensionActionMessage}.
 *
 * <p>That division is deliberate, and is the fix for a bug worth remembering: the client used to
 * decide for itself whether the round in the gun was one it could change, and to stay silent until
 * the server had introduced itself. Both were guesses, both could fail closed, and when they did the
 * keys simply stopped working - with nothing in any log to say which of the two had refused.
 */
public final class WeaponKeyHandler {

    /** GLFW's action value for a key going down; a release would otherwise fire twice per press. */
    private static final int ACTION_PRESS = 1;

    /** GLFW's action value for a key coming up, which is what ends an Alt chord. */
    private static final int ACTION_RELEASE = 0;

    /**
     * GLFW's modifier bit for Alt, spelled out rather than taken from LWJGL.
     *
     * <p>It is the only way to tell "Alt and W" from the driver's throttle: W on its own is the base
     * mod's forward key and stays one, so the pitch is a chord rather than a key, and a chord has to
     * be read off the modifier mask the event carries.
     */
    private static final int MOD_ALT = 0x0004;

    /** Set when a press was used with Alt down, so releasing Alt does not also open the chooser. */
    private static boolean altChorded;

    /** Whether Left Ctrl is currently held, so press and release each send one message. */
    private static boolean overrideHeld;

    /** How far the commander's mark reaches; the server enforces the same number. */
    private static final double DESIGNATION_RANGE = 256.0;

    /** How long to wait for a reply before concluding the server has no addon to reply with. */
    private static final long REPLY_TIMEOUT_MILLIS = 1500L;

    private static final long NO_SERVER_WARNING_INTERVAL_MILLIS = 5000L;

    /** What the client last asked the server for, and when, so the two can be told apart. */
    private static long pendingSince;
    private static long lastNoServerWarningMillis;

    /** The state as the client last saw it, which is what makes "what changed" answerable. */
    private static final GunnerSettings.Setting lastSeen = new GunnerSettings.Setting();

    /**
     * The slot the chooser was last opened for.
     *
     * <p>Kept because the reply to loading a round says only which index is now in the gun; naming
     * it needs the round's list, and the round is what the player opened. Resolved once, when they
     * open it, rather than re-derived on every tick.
     */
    private static ArmorConfig.Ammo chooserRound;

    /** The name of the round in the gun, as the vehicle pack calls it. */
    private static String chooserName;

    private WeaponKeyHandler() {
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        Minecraft minecraft = Minecraft.m_91087_();
        LocalPlayer player = minecraft.f_91074_;
        if (player == null || minecraft.f_91080_ != null) {
            return;
        }
        // Left Ctrl is the commander's "hold to lay the main gun": a press starts the override and a
        // release ends it. Handled on both edges because it is a hold rather than a toggle. On foot
        // the press is dropped; the release still clears the local flag so the next seat does not
        // inherit a press that was never sent.
        if (event.getKey() == keyCode("key.keyboard.left.control")) {
            if (event.getAction() == ACTION_PRESS) {
                sendOverride(player, true);
            } else if (event.getAction() == ACTION_RELEASE) {
                sendOverride(player, false);
            }
            return;
        }
        // Alt became a chord key: held with W and S it pitches the hull. So the load chooser, which
        // was plain Alt, now fires on the RELEASE and only if nothing was pressed in between.
        // Opening it on the press would take the screen, and this handler refuses to run at all
        // while a screen is open - so the W that followed would be swallowed and the pitch keys
        // would look dead on exactly the vehicles that have something for Alt to open.
        if (event.getKey() == keyCode("key.keyboard.left.alt")) {
            if (event.getAction() == ACTION_RELEASE) {
                boolean chorded = altChorded;
                altChorded = false;
                if (!chorded && player.m_20202_() instanceof AbstractVehicle) {
                    openChooser(player);
                }
            } else if (event.getAction() == ACTION_PRESS) {
                altChorded = false;
            }
            return;
        }
        if (event.getAction() != ACTION_PRESS) {
            return;
        }
        boolean withAlt = (event.getModifiers() & MOD_ALT) != 0;
        boolean cycleFuze = event.getKey() == keyCode("key.keyboard.left");
        boolean stepDelay = event.getKey() == keyCode("key.keyboard.right");
        boolean toggleFording = event.getKey() == keyCode("key.keyboard.k");
        boolean raiseHull = event.getKey() == keyCode("key.keyboard.up");
        boolean lowerHull = event.getKey() == keyCode("key.keyboard.down");
        boolean noseDown = withAlt && event.getKey() == keyCode("key.keyboard.w");
        boolean noseUp = withAlt && event.getKey() == keyCode("key.keyboard.s");
        boolean designate = event.getKey() == keyCode("key.keyboard.m");
        boolean toggleDoors = event.getKey() == keyCode("key.keyboard.p");
        if (!cycleFuze && !stepDelay && !toggleFording
                && !raiseHull && !lowerHull && !noseUp && !noseDown && !designate
                && !toggleDoors) {
            return;
        }
        // Riding anything at all is the whole of the client's gate for the arrow keys. The round
        // actually in the gun is decided by the server - see GunnerActionMessage - because a
        // client-side guess at it can only ever fail closed, and a key that does nothing without
        // saying why is the worst possible outcome for a feature whose use is pressing a key.
        if (!(player.m_20202_() instanceof AbstractVehicle)) {
            return;
        }
        altChorded |= noseUp || noseDown;
        pendingSince = System.currentTimeMillis();
        if (toggleDoors) {
            // The server picks the hatch linked to the sender's seat (or the nearest
            // one) and flips its base-mod SwitchableUnit state; the state sync and
            // the switchable animation do the rest.
            VehicleArmorNetwork.channel().sendToServer(new DoorActionMessage());
            return;
        }
        if (designate) {
            // Aiming at a target marks it; aiming at nothing clears the mark. The client sends only
            // what it can see, and the server decides whether this player is the commander and
            // whether the target is a real one in range.
            int target = targetUnderCrosshair(player, (AbstractVehicle) player.m_20202_());
            VehicleArmorNetwork.channel().sendToServer(
                    new CrewActionMessage(CrewActionMessage.DESIGNATE, target));
            return;
        }
        if (toggleFording) {
            // Also an intent rather than a state: whether this vehicle has a kit, and whether it is
            // up, are both the server's to know - see FordingActionMessage.
            VehicleArmorNetwork.channel().sendToServer(
                    new FordingActionMessage(FordingActionMessage.TOGGLE));
            return;
        }
        if (raiseHull || lowerHull || noseUp || noseDown) {
            // The armour scanner's overlay reads the two arrow keys for its page while it is in
            // hand, and it cannot consume them - so while it is held up and down are the scanner's
            // and the hull stays where it is. Otherwise a gunner leafing through the plates on a
            // moving vehicle would also be walking the suspension underneath himself. The pitch
            // chord is left alone: the scanner never reads W or S.
            if ((raiseHull || lowerHull)
                    && player.m_21205_().m_41720_() == VehicleArmorItems.ARMOR_SCANNER.get()) {
                return;
            }
            // Four keys, two axes, and each one moves whatever the server currently has by one step
            // in the direction it names: the client cannot know the vehicle's pose, and a client that
            // guessed it would walk the suspension the wrong way half the time. What it *can* say is
            // which way the crew asked for, which is why the direction is sent and the position is
            // not. See SuspensionActionMessage.
            //
            // Up is the hull rising and the nose lifting; W pitches the nose down and S lifts it,
            // which is the one place the two axes do not read the same way round - W is forward.
            VehicleArmorNetwork.channel().sendToServer(new SuspensionActionMessage(
                    raiseHull || lowerHull ? SuspensionActionMessage.HEIGHT
                            : SuspensionActionMessage.PITCH,
                    lowerHull || noseDown ? SuspensionActionMessage.DOWN : SuspensionActionMessage.UP));
            return;
        }
        VehicleArmorNetwork.channel().sendToServer(new GunnerActionMessage(
                stepDelay ? GunnerActionMessage.STEP_DELAY : GunnerActionMessage.CYCLE_FUZE, 0));
    }

    /**
     * Opens the load chooser for whatever the gunner is holding.
     *
     * <p>This one does resolve the round on the client, because the page has to be built before it
     * can be shown - and a page that cannot be built is visible, unlike a key that does nothing.
     * The resolution is the same chain the base mod's own weapon overlay uses to draw what is
     * loaded, so if it fails, so does the readout the player is already looking at.
     */
    private static void openChooser(LocalPlayer player) {
        ArmorConfig.Ammo round = operatingRound(player);
        if (round == null || !round.selectable()) {
            player.m_5661_(Component.m_237115_("message.ywzj_vehicle_armor.no_loads"), true);
            return;
        }
        chooserRound = round;
        chooserName = operatedRoundName(player);
        ClientSetup.openLoadChooser(chooserRound, chooserName);
    }

    /** Sends the player's choice to the server, which is the side that decides it counts. */
    public static void chooseLoad(int index) {
        pendingSince = System.currentTimeMillis();
        VehicleArmorNetwork.channel().sendToServer(
                new GunnerActionMessage(GunnerActionMessage.SELECT_VARIANT, index));
        // Said before the server has answered, and said here rather than from the reply, because
        // the reply for a load choice deliberately reports no change: the round is not in the gun
        // yet. A chooser that closed in silence for the next few seconds would read as the click
        // having done nothing - which is how this was reported the first time round.
        LocalPlayer player = Minecraft.m_91087_().f_91074_;
        if (player != null && chooserRound != null) {
            player.m_5661_(GunnerSettings.describeLoading(
                    chooserRound.variantNames(chooserName).get(
                            Math.max(0, Math.min(index, chooserRound.variantCount() - 1)))), true);
        }
    }

    /**
     * Shows whatever the server last sent, one message per change.
     *
     * <p>Done on the tick rather than from the packet handler because the handler runs on the
     * network thread and this is a screen operation - and because a change is only recognisable by
     * comparing the new state with the previous one, which needs somewhere to compare from.
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // A player who leaves a seat while holding the override key has not released it as far as
        // the keyboard is concerned, so the local edge state is dropped here. The server also
        // clears the order when the commander stops riding the vehicle - this is only so the next
        // press in a new seat is an actual press.
        LocalPlayer riding = Minecraft.m_91087_().f_91074_;
        if (riding != null && !(riding.m_20202_() instanceof AbstractVehicle)) {
            overrideHeld = false;
        }
        GunnerSettings.Setting now = GunnerSettings.client();
        boolean greeting = GunnerSettings.consumeQuietSync();
        if (greeting) {
            // The first state after joining establishes the baseline; announcing it would put a
            // readout on the screen of everyone who joins, including those who never touch a key.
            lastSeen.copyFrom(now);
            return;
        }
        // A line the server built and parked: that is how the fording kit answers, because it is a
        // sentence about water the server measured rather than a change to compare against what the
        // client last had. The fuze readout below is the other shape, and both end up here.
        Component message = GunnerSettings.takeFeedback();
        if (message == null) {
            // Nested rather than chained: a parked line is about a different key, so a fuze change
            // that happens to land on the same tick must not quietly replace it.
            if (now.variant != lastSeen.variant && chooserRound != null) {
                message = GunnerSettings.describeLoad(
                        chooserRound.variantNames(chooserName).get(now.variant));
            } else if (now.fuze != lastSeen.fuze || now.delayTicks != lastSeen.delayTicks) {
                message = GunnerSettings.describe(now);
            }
        }
        lastSeen.copyFrom(now);

        LocalPlayer player = Minecraft.m_91087_().f_91074_;
        if (message != null && player != null) {
            pendingSince = 0L;
            player.m_5661_(message, true);
            return;
        }
        warnIfUnanswered();
    }

    /**
     * Says so, once, when a press went unanswered on a server that has never spoken to us.
     *
     * <p>This replaces an outright refusal to send anything until the server had introduced itself,
     * which had the whole feature hanging off one message sent at login: if that message was lost or
     * arrived too early, the keys were dead for the rest of the session and the only way to find out
     * why was to guess. Now the press always goes out, and the reply - or the lack of one - is the
     * answer.
     */
    private static void warnIfUnanswered() {
        if (pendingSince == 0L || GunnerSettings.serverPresent()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - pendingSince < REPLY_TIMEOUT_MILLIS) {
            return;
        }
        pendingSince = 0L;
        if (now - lastNoServerWarningMillis < NO_SERVER_WARNING_INTERVAL_MILLIS) {
            return;
        }
        lastNoServerWarningMillis = now;
        LocalPlayer player = Minecraft.m_91087_().f_91074_;
        if (player != null) {
            player.m_5661_(Component.m_237115_("message.ywzj_vehicle_armor.no_server_mod"), true);
        }
    }

    /** Leaves nothing behind for the next server, which may not know anything about any of this. */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        GunnerSettings.resetClient();
        GunnerSettings.forgetServer();
        pendingSince = 0L;
        chooserRound = null;
        chooserName = null;
        overrideHeld = false;
        CrewClientState.clear();
        lastSeen.copyFrom(GunnerSettings.client());
    }

    /**
     * Sends one edge of the commander's aim override.
     *
     * <p>The hold is tracked locally so a key repeat cannot start the same override every tick.
     * Nothing is sent while on foot, and the flag is not latched there either - a press that was not
     * sent must not turn the release into a stop for a seat the player has since taken.
     */
    private static void sendOverride(LocalPlayer player, boolean on) {
        if (overrideHeld == on) {
            return;
        }
        if (!(player.m_20202_() instanceof AbstractVehicle)) {
            overrideHeld = false;
            return;
        }
        overrideHeld = on;
        pendingSince = System.currentTimeMillis();
        VehicleArmorNetwork.channel().sendToServer(new CrewActionMessage(
                on ? CrewActionMessage.OVERRIDE_START : CrewActionMessage.OVERRIDE_STOP, 0));
    }

    /**
     * @return the id of the vehicle under the crosshair, or zero when there is none.
     *
     * <p>A plain entity ray rather than the vanilla pick, which only reaches about four blocks: the
     * commander's job is to mark something worth shooting at, and that is rarely within arm's reach.
     * The same 256-block limit is enforced on the server, where the answer counts.
     */
    private static int targetUnderCrosshair(LocalPlayer player, AbstractVehicle own) {
        Level level = player.m_9236_();
        if (level == null) {
            return 0;
        }
        Vec3 start = player.m_146892_();
        Vec3 end = start.m_82549_(player.m_20252_(1.0F).m_82490_(DESIGNATION_RANGE));
        AABB box = player.m_20191_().m_82400_(DESIGNATION_RANGE);
        EntityHitResult hit = ProjectileUtil.m_150175_(level, player, start, end, box,
                entity -> entity instanceof AbstractVehicle && entity != own && entity.m_6084_(),
                0.3F);
        return hit == null ? 0 : hit.m_82443_().m_19879_();
    }

    /** @return the configured round the player is operating, or {@code null} if there is none. */
    private static ArmorConfig.Ammo operatingRound(LocalPlayer player) {
        Entity ridden = player.m_20202_();
        if (!(ridden instanceof AbstractVehicle vehicle)) {
            return null;
        }
        PartUnit<?> unit = vehicle.getOwnOperatorUnit(player);
        if (!(unit instanceof WeaponUnit weaponUnit)) {
            return null;
        }
        AbstractVehicleWeapon<?> weapon = weaponUnit.getCurrentWeapon().orElse(null);
        // A multi-round mount holds the sub-weapons; the mount's own data is not the round's.
        if (weapon instanceof VehicleMultiWeapons multi) {
            weapon = multi.getSelectedWeapon();
        }
        if (weapon == null || weapon.getData() == null) {
            return null;
        }
        BaseVehicleWeaponData data = weapon.getData();
        return ArmorConfig.ammoFor(data.getWeaponId());
    }

    /**
     * What the vehicle pack calls the round in the gun, falling back to its id.
     *
     * <p>Only used to head the chooser and to name index zero - the round the pack names - since
     * the alternatives carry their designations in the config.
     */
    private static String operatedRoundName(LocalPlayer player) {
        Entity ridden = player.m_20202_();
        if (!(ridden instanceof AbstractVehicle vehicle)) {
            return "?";
        }
        PartUnit<?> unit = vehicle.getOwnOperatorUnit(player);
        if (!(unit instanceof WeaponUnit weaponUnit)) {
            return "?";
        }
        AbstractVehicleWeapon<?> weapon = weaponUnit.getCurrentWeapon().orElse(null);
        if (weapon instanceof VehicleMultiWeapons multi) {
            weapon = multi.getSelectedWeapon();
        }
        if (weapon == null || weapon.getData() == null) {
            return "?";
        }
        String name = weapon.getData().getName();
        return name == null || name.isBlank() ? "?" : name;
    }

    /**
     * @return the GLFW key code Mojang has registered under the given name, or {@code -1} if it is
     *         unknown - which never matches a real key, so the keys simply do nothing.
     */
    private static int keyCode(String name) {
        InputConstants.Key key = InputConstants.m_84851_(name);
        return key == null ? -1 : key.m_84873_();
    }
}
