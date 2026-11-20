package com.ywzjvehicle.armor.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.math.Axis;
import com.ywzjvehicle.armor.ArmorSystem;
import com.ywzjvehicle.armor.Periscope;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RotatableUnit;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeGroup;

/**
 * The commander's hatch periscope: a first-person view from a point on the vehicle's structure,
 * entered and left with one key.
 *
 * <p><b>What it is not.</b> The base mod already has an operator view, and it is a fine one - it
 * puts the head where the weapon unit says the occupant sits. On the VT-5 that point is the remote
 * weapon station's own mount, 1.05 blocks above it, which is a sight position rather than a hatch
 * position. The periscope is a second, measured point: the centre of the commander's hatch, just
 * above the turret roof, so a buttoned-up commander looks out over the vehicle instead of through
 * the weapon station.
 *
 * <p><b>How the camera is moved.</b> {@code LocalVehiclePlayer.cameraPosition} is the one method
 * the base's own camera mixin calls for the operator view, so the periscope's mixin cancels that
 * method only while this view is active and only for the configured seat part. The rest of the
 * base's operator view is kept deliberately: its free look, its player-rotation bookkeeping, its
 * pitch clamp and its FOV path all keep working, and the weapon station still follows the view, so
 * the trigger fires where the commander is looking rather than at a stale aim. In other words this
 * moves the head, not the head's behaviour.
 *
 * <p><b>Client-only, with no packet.</b> A viewpoint is not vehicle state. The server never hears
 * about it; the player's rotation is the same rotation vanilla already syncs from a rider, and the
 * periscope's config is read on both sides only because the same config file is read on both.
 *
 * <p>The view ends when the base view changes (V or X), when the player leaves the seat or the
 * vehicle, and on logout. There is no local on/off latch that can drift away from what is on
 * screen: every tick re-checks the seat and the base view that the camera override depends on.
 */
public final class PeriscopeView {

    /** GLFW's action value for a key going down; a repeat or a release must not toggle twice. */
    private static final int ACTION_PRESS = 1;

    /**
     * Y. V and X are the base mod's own view and scope keys, and the rest of the vehicle keys are
     * taken; Y is free on a ground vehicle and sits next to the keys a crew already uses.
     */
    public static final KeyMapping PERISCOPE_KEY = new KeyMapping(
            "key.ywzj_vehicle_armor.periscope",
            InputConstants.Type.KEYSYM, 89, KeyBindings.CATEGORY);

    private static boolean active;
    private static AbstractVehicle vehicle;
    private static PartUnit<?> seatPart;
    private static PartUnit<?> yawPart;
    private static Periscope.Settings settings;
    /** The turret yaw at the previous tick, so a traversing turret carries the view with it. */
    private static float lastYaw;

    private PeriscopeView() {
    }

    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(PERISCOPE_KEY);
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (event.getAction() != ACTION_PRESS
                || !PERISCOPE_KEY.m_90832_(event.getKey(), event.getScanCode())) {
            return;
        }
        Minecraft minecraft = Minecraft.m_91087_();
        LocalPlayer player = minecraft.f_91074_;
        if (player == null || minecraft.f_91080_ != null) {
            return;
        }
        Entity ridden = player.m_20202_();
        if (!(ridden instanceof AbstractVehicle riddenVehicle) || !riddenVehicle.m_6084_()) {
            return;
        }
        if (active && riddenVehicle == vehicle) {
            leave(player);
            return;
        }
        // A stale view from another vehicle cannot survive a tick, but a key press is not a tick.
        clear();
        enter(player, riddenVehicle);
    }

    /**
     * Enters the periscope for the player's own seat, or says why it cannot.
     *
     * <p>Every check is about this side's own knowledge: which seat the player occupies, whether
     * the config names a periscope for this vehicle, and whether that seat is the one configured.
     * The message is the answer rather than a silent no-op, because the alternative is a key that
     * does nothing on four vehicles out of five with no way to tell a missing config from a broken
     * key.
     */
    private static void enter(LocalPlayer player, AbstractVehicle riddenVehicle) {
        Periscope.Settings config = ArmorSystem.periscopeFor(riddenVehicle);
        if (config == null) {
            player.m_5661_(Component.m_237115_(
                    "message.ywzj_vehicle_armor.periscope_none"), true);
            return;
        }
        PartUnit<?> seat = null;
        for (PartUnit<?> part : riddenVehicle.getPartUnits()) {
            if (config.seat_part_id != null && config.seat_part_id.equals(part.getId())) {
                seat = part;
                break;
            }
        }
        if (seat == null) {
            player.m_5661_(Component.m_237115_(
                    "message.ywzj_vehicle_armor.periscope_none"), true);
            return;
        }
        if (riddenVehicle.getOwnOperatorUnit(player) != seat) {
            player.m_5661_(Component.m_237115_(
                    "message.ywzj_vehicle_armor.periscope_seat"), true);
            return;
        }
        PartUnit<?> yawUnit = null;
        if (config.yaw_part_id != null && !config.yaw_part_id.isBlank()) {
            for (PartUnit<?> part : riddenVehicle.getPartUnits()) {
                if (config.yaw_part_id.equals(part.getId())) {
                    yawUnit = part;
                    break;
                }
            }
        }

        LocalVehiclePlayer local = LocalVehiclePlayer.instance;
        // Keep the direction the player was already looking in. The base's switch to the operator
        // view aims the player at the weapon's current hit point, which is the wrong place to
        // start when what changed is the camera point rather than the thing being aimed.
        float yaw = player.m_146908_();
        float pitch = player.m_146909_();
        local.switchViewType(LocalVehiclePlayer.ViewType.OPERATOR);
        local.playerLerpSteps = 0.0F;
        player.m_146922_(yaw);
        player.m_146926_(pitch);
        local.cameraAimRotX = pitch;
        local.cameraAimRotY = yaw;
        local.cameraAimRotZ = 0.0F;
        local.cameraAimRotXO = pitch;
        local.cameraAimRotYO = yaw;
        local.cameraAimRotZO = 0.0F;

        vehicle = riddenVehicle;
        seatPart = seat;
        yawPart = yawUnit;
        settings = config;
        lastYaw = yawUnit instanceof RotatableUnit<?> rotatable ? rotatable.getYRot() : 0.0F;
        active = true;
        player.m_5661_(Component.m_237115_(
                "message.ywzj_vehicle_armor.periscope_on"), true);
    }

    /** Leaves the periscope and returns to the base's third-person view. */
    private static void leave(LocalPlayer player) {
        clear();
        LocalVehiclePlayer.instance.switchViewType(LocalVehiclePlayer.ViewType.THIRD_PERSON);
        player.m_5661_(Component.m_237115_(
                "message.ywzj_vehicle_armor.periscope_off"), true);
    }

    /**
     * The camera point, or {@code null} when this call is not the periscope's.
     *
     * <p>Called from the mixin at the head of {@code LocalVehiclePlayer.cameraPosition}. The offset
     * is added to the seat part's pivot exactly as the base adds {@code operator_view_offset}; the
     * point is then turned by the configured yaw part and placed in the world. With no yaw part it
     * falls back to {@code worldPositionWithBaseRot}, which keeps a hull-mounted periscope exactly
     * where the pack put it.
     */
    public static Vec3 cameraPosition(PartUnit<?> operatorUnit, float partialTick) {
        if (!active || settings == null || operatorUnit == null || operatorUnit != seatPart
                || LocalVehiclePlayer.instance.vehicle != vehicle) {
            return null;
        }
        Vec3 pivot = operatorUnit.getPivotOffset();
        Vec3 point = new Vec3(
                pivot.f_82479_ + settings.offset_x,
                pivot.f_82480_ + settings.offset_y,
                pivot.f_82481_ + settings.offset_z);
        if (yawPart instanceof RotatableUnit<?> rotatable && rotatable.getStructureGroup() != null) {
            return yawOnly(operatorUnit.getVehicle(), rotatable, point, partialTick);
        }
        // No yaw part: the point is fixed to the hull, not to whatever group the seat happens to
        // sit under. This is the one behaviour a blank yaw_part_id promises.
        return rotatedPoint(operatorUnit.getVehicle(), point, partialTick);
    }

    /**
     * The configured point rotated by the yaw part's <b>yaw only</b>, then placed in the world.
     *
     * <p>Why not {@code worldPositionWithBaseRot}: on the VT-5 the seat part's parent group is the
     * turret, and the base rotates that group by the turret's yaw <em>and the gun's pitch</em>
     * ({@code RotatableUnit.updateRot}). That is right for a sight bolted to the gun and wrong for
     * a periscope bolted to the roof: depressing the main gun by 13 degrees would swing the camera
     * 0.3 blocks off the hatch and down into it. The turret's yaw is the part that matters, so the
     * offset is turned by {@code vehicle rotation x base rotation x yaw} and not by the group's
     * combined rotation. The yaw is interpolated for the partial tick the same way the display is.
     */
    private static Vec3 yawOnly(AbstractVehicle host, RotatableUnit<?> yaw, Vec3 point,
                                float partialTick) {
        VehicleCubeGroup group = yaw.getStructureGroup();
        Quaternionf rotation = new Quaternionf(host.rotYXZ(partialTick));
        rotation.mul(group.baseRotation);
        if (group.parent != null) {
            rotation.mul(group.parent.globalTransform().rotation());
        }
        rotation.mul(Axis.f_252392_.m_252977_(yaw.getViewYRot(partialTick)));

        Vec3 pivot = yaw.getPivotOffset();
        Vector3f offset = new Vector3f(
                (float) (point.f_82479_ - pivot.f_82479_),
                (float) (point.f_82480_ - pivot.f_82480_),
                (float) (point.f_82481_ - pivot.f_82481_));
        rotation.transform(offset);
        Vec3 pivotWorld = rotatedPoint(host, pivot, partialTick);
        return pivotWorld.m_82520_(offset.x, offset.y, offset.z);
    }

    /** The vehicle-rotation-only placement of a point in the structure model. */
    private static Vec3 rotatedPoint(AbstractVehicle host, Vec3 point, float partialTick) {
        Vec3 center = host.centerOffset;
        Vector3f local = new Vector3f(
                (float) (point.f_82479_ - center.f_82479_),
                (float) (point.f_82480_ - center.f_82480_),
                (float) (point.f_82481_ - center.f_82481_));
        Vector3f world = host.rotYXZ(partialTick).transform(local);
        return host.position(partialTick).m_82549_(center).m_82520_(world.x, world.y, world.z);
    }

    /**
     * Called before the base changes view. Anything but another operator view is a way out.
     *
     * <p>A null target is the base's cycle, which from the operator view goes to third person, so
     * it is treated as a way out too. Entering the periscope itself asks for OPERATOR and is left
     * alone.
     */
    public static void beforeViewSwitch(LocalVehiclePlayer.ViewType toViewType) {
        if (active && toViewType != LocalVehiclePlayer.ViewType.OPERATOR) {
            clear();
        }
    }

    /** Whether the periscope is the view currently in force. The HUD reads this. */
    public static boolean active() {
        return active;
    }

    /**
     * Drops the view when anything it depends on has gone: the seat, the vehicle, the base view.
     *
     * <p>Runs on the client tick rather than on the key, because a seat change, a vehicle change
     * and the base's own view key are all ways out that never pass through this class. The check
     * is repeated every tick for the same reason the server repeats its seat checks: a client
     * latch that only moves on the events it knows about is wrong exactly when an event it did not
     * know about happens.
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !active) {
            return;
        }
        Minecraft minecraft = Minecraft.m_91087_();
        LocalPlayer player = minecraft.f_91074_;
        LocalVehiclePlayer local = LocalVehiclePlayer.instance;
        if (player == null
                || local.viewType != LocalVehiclePlayer.ViewType.OPERATOR
                || local.vehicle != vehicle
                || player.m_20202_() != vehicle
                || vehicle.getOwnOperatorUnit(player) != seatPart) {
            clear();
            return;
        }
        // The periscope is bolted to the turret, so a traversing turret carries the commander's
        // view with it. The point is already rotated by this method's sibling; this rotates the
        // look direction by the same delta, in the same direction. Doing it here rather than in
        // the camera mixin is deliberate: the base's operator view owns the look, and this is the
        // one place that knows both the turret's live yaw and the base view that is in force.
        if (yawPart instanceof RotatableUnit<?> rotatable) {
            float current = rotatable.getYRot();
            float delta = Mth.m_14177_(current - lastYaw);
            lastYaw = current;
            if (delta != 0.0F) {
                player.m_146922_(player.m_146908_() + delta);
                // The base's operator mouse handling accumulates into playerLocalYRot and rebuilds
                // the world rotation from it, so the player's yaw and that accumulator have to
                // move together. Without this line the next mouse movement would silently undo
                // the turret's contribution.
                local.playerLocalYRot += delta;
                local.cameraAimRotY += delta;
                local.cameraAimRotYO += delta;
            }
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    private static void clear() {
        active = false;
        vehicle = null;
        seatPart = null;
        yawPart = null;
        settings = null;
        lastYaw = 0.0F;
    }
}
