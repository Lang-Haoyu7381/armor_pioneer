package com.ywzjvehicle.armor;

import net.neoforged.neoforge.network.PacketDistributor;

import com.ywzjvehicle.armor.client.KeyBindings;
import com.ywzjvehicle.armor.network.DoorActionMessage;
import com.ywzjvehicle.armor.network.FordingActionMessage;
import com.ywzjvehicle.armor.network.GunnerActionMessage;
import com.ywzjvehicle.armor.network.HybridActionMessage;
import com.ywzjvehicle.armor.network.SuspensionActionMessage;
import com.ywzjvehicle.armor.network.VehicleArmorNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import org.ywzj.vehicle.all.AllKeys;
import org.ywzj.vehicle.custom.weapon.data.BaseVehicleWeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleMultiWeapons;

/**
 * The crew's keys: left and right arrows lean the hull on a vehicle whose suspension has a lateral
 * axis (the K2) and cycle the fuze and step its delay everywhere else, K works the driver's fording
 * kit, up and down raise and lower the hull, and the load-chooser key held with the base mod's
 * forward/back keys pitches its nose.
 *
 * <p>Every key is a {@code KeyMapping} in {@link KeyBindings}, so it appears in Options &gt;
 * Controls under the addon's own category and can be rebound. The pitch chord is the one exception
 * in shape rather than in kind: the modifier is the load-chooser mapping and the direction keys are
 * the base mod's own forward/back mappings, so both halves are rebindable - just in the place that
 * already owns them.
 *
 * <p><b>The base mod keeps the driving and firing controls.</b> This handler adds only the addon's
 * own stations (suspension, fording, fuze, load, hybrid, doors); it never takes the wheel or
 * another seat's weapon. Seat zero's occupant drives and every seat operates its own weapon,
 * exactly as the base mod does; the base mod's scroll wheel switches that seat's weapon.
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
 * <p>The suspension keys are the ones with a direction, and that is not decoration: a key that says
 * "up" has to stop at the top rather than wrap to the bottom, so the server is told which way and
 * works out where that lands. See {@code SuspensionActionMessage}.
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
     * Whether the load-chooser key is currently held.
     *
     * <p>Tracked from the key events rather than read from {@code KeyMapping.isDown()}, so the
     * pitch chord works on the exact press that carries it and a release that arrives while a
     * screen is opening cannot leave the modifier stuck down. The key itself is
     * {@link KeyBindings#LOAD_CHOOSER}, so rebinding it moves the chord with it.
     */
    private static boolean modifierHeld;

    /** Set when a press was used with the modifier down, so releasing it does not also open the chooser. */
    private static boolean altChorded;

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
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.screen != null) {
            // A screen swallows the release, and a modifier left stuck down would turn the next
            // forward key into a pitch nobody asked for. A screen is the one place the state
            // cannot be observed, so clear it here.
            modifierHeld = false;
            altChorded = false;
            return;
        }
        // The load-chooser key is also the suspension pitch modifier: held with the base mod's
        // forward/back keys it pitches the hull, and a tap on its own opens the chooser on the
        // RELEASE and only if no pitch was taken in between. Opening it on the press would take
        // the screen, and this handler refuses to run while a screen is open - so the forward key
        // of the very chord would be swallowed and the pitch would look dead.
        if (KeyBindings.LOAD_CHOOSER.matches(event.getKey(), event.getScanCode())) {
            if (event.getAction() == ACTION_RELEASE) {
                modifierHeld = false;
                boolean chorded = altChorded;
                altChorded = false;
                if (!chorded && player.getVehicle() instanceof AbstractVehicle) {
                    openChooser(player);
                }
            } else if (event.getAction() == ACTION_PRESS) {
                modifierHeld = true;
                altChorded = false;
            }
            return;
        }
        if (event.getAction() != ACTION_PRESS) {
            return;
        }
        boolean leftKey = KeyBindings.CREW_LEFT.matches(event.getKey(), event.getScanCode());
        boolean rightKey = KeyBindings.CREW_RIGHT.matches(event.getKey(), event.getScanCode());
        boolean toggleFording = KeyBindings.FORDING.matches(event.getKey(), event.getScanCode());
        boolean raiseHull = KeyBindings.CREW_UP.matches(event.getKey(), event.getScanCode());
        boolean lowerHull = KeyBindings.CREW_DOWN.matches(event.getKey(), event.getScanCode());
        // The chord's direction keys are the base mod's own forward/back mappings, so rebinding
        // the drive keys moves the pitch with them. They stay the base mod's keys: this handler
        // never cancels or consumes them, so the forward key on its own still drives.
        boolean noseDown = modifierHeld
                && AllKeys.FORWARD.matches(event.getKey(), event.getScanCode());
        boolean noseUp = modifierHeld
                && AllKeys.BACKWARD.matches(event.getKey(), event.getScanCode());
        boolean toggleDoors = KeyBindings.DOORS.matches(event.getKey(), event.getScanCode());
        // G is the base mod's landing-gear key. On a ground vehicle it does nothing, so it is free
        // for the ZTZ-100's hybrid mode - and the handler only sends anything when the local config
        // says this vehicle has a hybrid at all, so an aircraft keeps its gear key untouched.
        boolean toggleHybrid = KeyBindings.HYBRID.matches(event.getKey(), event.getScanCode());
        if (!leftKey && !rightKey && !toggleFording
                && !raiseHull && !lowerHull && !noseUp && !noseDown
                && !toggleDoors && !toggleHybrid) {
            return;
        }
        // Riding anything at all is the whole of the client's gate for the arrow keys. The round
        // actually in the gun is decided by the server - see GunnerActionMessage - because a
        // client-side guess at it can only ever fail closed, and a key that does nothing without
        // saying why is the worst possible outcome for a feature whose use is pressing a key.
        if (!(player.getVehicle() instanceof AbstractVehicle vehicle)) {
            return;
        }
        if (toggleHybrid) {
            // Nothing is sent on a vehicle the local config has never heard of, which is what keeps
            // the base mod's landing-gear key working on every aircraft. The server decides the new
            // state and answers; see HybridActionMessage.
            if (ArmorSystem.hybridFor(vehicle) == null) {
                return;
            }
            pendingSince = System.currentTimeMillis();
            PacketDistributor.sendToServer(new HybridActionMessage(
                    HybridActionMessage.TOGGLE));
            return;
        }
        // Left and right are the driver's lean keys on a vehicle that has a lateral suspension
        // axis, and the gunner's fuze keys everywhere else. Both halves of that test matter: the
        // axis decides whether the vehicle can lean at all, and the seat decides who is allowed to
        // work it - so a gunner sitting in the K2 keeps the fuze keys instead of getting "driver
        // only" back for a press that was never about the suspension. The server re-checks the
        // seat; this only decides which message to send.
        boolean lean = CrewRoles.isDriverSeat(vehicle, player) && hasLateralAxis(vehicle);
        boolean rollLeft = lean && leftKey;
        boolean rollRight = lean && rightKey;
        boolean cycleFuze = !lean && leftKey;
        boolean stepDelay = !lean && rightKey;
        altChorded |= noseUp || noseDown;
        pendingSince = System.currentTimeMillis();
        if (toggleDoors) {
            // The server picks the hatch linked to the sender's seat (or the nearest
            // one) and flips its base-mod SwitchableUnit state; the state sync and
            // the switchable animation do the rest.
            PacketDistributor.sendToServer(new DoorActionMessage());
            return;
        }
        if (toggleFording) {
            // Also an intent rather than a state: whether this vehicle has a kit, and whether it is
            // up, are both the server's to know - see FordingActionMessage.
            PacketDistributor.sendToServer(
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
                    && player.getMainHandItem().getItem() == VehicleArmorItems.ARMOR_SCANNER.get()) {
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
            PacketDistributor.sendToServer(new SuspensionActionMessage(
                    raiseHull || lowerHull ? SuspensionActionMessage.HEIGHT
                            : SuspensionActionMessage.PITCH,
                    lowerHull || noseDown ? SuspensionActionMessage.DOWN : SuspensionActionMessage.UP));
            return;
        }
        if (rollLeft || rollRight) {
            // The key names the side rather than the direction: left lowers the left side and right
            // lowers the right side, which is the same sentence from either end. The server counts
            // the step and stops at the end of the travel.
            PacketDistributor.sendToServer(new SuspensionActionMessage(
                    SuspensionActionMessage.ROLL,
                    rollRight ? SuspensionActionMessage.UP : SuspensionActionMessage.DOWN));
            return;
        }
        PacketDistributor.sendToServer(new GunnerActionMessage(
                stepDelay ? GunnerActionMessage.STEP_DELAY : GunnerActionMessage.CYCLE_FUZE, 0));
    }

    /** @return whether this vehicle's crew can work its left and right suspension stations apart. */
    private static boolean hasLateralAxis(AbstractVehicle vehicle) {
        Suspension.Settings settings = ArmorSystem.suspensionFor(vehicle);
        return settings != null && settings.max_roll > 0.0F;
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
            player.displayClientMessage(Component.translatable("message.ywzj_vehicle_armor.no_loads"), true);
            return;
        }
        chooserRound = round;
        chooserName = operatedRoundName(player);
        ClientSetup.openLoadChooser(chooserRound, chooserName);
    }

    /** Sends the player's choice to the server, which is the side that decides it counts. */
    public static void chooseLoad(int index) {
        pendingSince = System.currentTimeMillis();
        PacketDistributor.sendToServer(
                new GunnerActionMessage(GunnerActionMessage.SELECT_VARIANT, index));
        // Said before the server has answered, and said here rather than from the reply, because
        // the reply for a load choice deliberately reports no change: the round is not in the gun
        // yet. A chooser that closed in silence for the next few seconds would read as the click
        // having done nothing - which is how this was reported the first time round.
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && chooserRound != null) {
            player.displayClientMessage(GunnerSettings.describeLoading(
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
    public static void onClientTick(ClientTickEvent.Post event) {
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

        LocalPlayer player = Minecraft.getInstance().player;
        if (message != null && player != null) {
            pendingSince = 0L;
            player.displayClientMessage(message, true);
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
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.translatable("message.ywzj_vehicle_armor.no_server_mod"), true);
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
        modifierHeld = false;
        altChorded = false;
        CrewClientState.clear();
        com.ywzjvehicle.armor.client.HybridClientState.clear();
        lastSeen.copyFrom(GunnerSettings.client());
    }

    /** @return the configured round the player is operating, or {@code null} if there is none. */
    private static ArmorConfig.Ammo operatingRound(LocalPlayer player) {
        Entity ridden = player.getVehicle();
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
        Entity ridden = player.getVehicle();
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

}
