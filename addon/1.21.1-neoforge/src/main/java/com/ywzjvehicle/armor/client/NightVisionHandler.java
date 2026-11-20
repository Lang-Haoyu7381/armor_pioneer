package com.ywzjvehicle.armor.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.ywzjvehicle.armor.VehicleArmorMod;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * The Armor Pioneer tanks' night sight: a green image-intensifier pass over the gunner's scope.
 *
 * <p>The base mod ships thermal imaging but no night vision. Thermal is per-weapon data
 * ({@code with_thermal_imager}) and its own key, 4; this is the missing half, and it deliberately
 * mirrors the same rules: it only exists in the gunner's sight ({@code viewType == SCOPE}), only on
 * a weapon whose optic is the CRT style the scope draws, and only on the Armor Pioneer pack. Key 5
 * is registered as a normal key mapping, so it can be rebound in Options > Controls.
 *
 * <p>The effect is a post-chain in the addon's own namespace rather than a potion effect. A potion
 * would brighten the driver's third-person view and leave a Night Vision icon in the HUD, and it
 * would still be there after leaving the vehicle; this pass is bound to the scope's own frame and
 * disappears the moment the scope does. It also keeps the vehicle-pack files data-only: thermal is
 * a flag in the pack, night vision is the addon's sight.
 *
 * <p>Night vision and thermal are mutually exclusive. Turning night vision on clears the base's
 * {@code thermalImaging} flag; turning thermal on afterwards switches night vision off, so turning
 * thermal off again returns to the plain sight rather than silently back to a green image the
 * player thought they had left. Pressing 5 still brings night vision back from a thermal view,
 * which is why the toggle tests whether the effect is actually visible, not merely enabled.
 *
 * <p>Nothing here is server state. There is no packet and no config: the only thing that differs
 * between players is what each one chose to look through, and the server has no part in a scope
 * shader.
 */
public final class NightVisionHandler implements ResourceManagerReloadListener {

    /** The pack this sight belongs to; the key does nothing on any other pack's vehicle. */
    private static final String PACK_NAMESPACE = "armor_pioneer";

    /** GLFW's action value for a key going down. */
    private static final int ACTION_PRESS = 1;

    /** The post chain, in the addon's own assets, and its program is named to match. */
    private static final ResourceLocation EFFECT =
            ResourceLocation.fromNamespaceAndPath("ywzj_vehicle_armor", "shaders/post/night_vision.json");

    /**
     * The sight key. 5 sits next to the base mod's thermal key 4, and 5 is not used by the base
     * mod's own bindings. A KeyMapping rather than a literal key, so it can be rebound.
     */
    public static final KeyMapping NIGHT_VISION_KEY = new KeyMapping(
            "key.ywzj_vehicle_armor.night_vision",
            InputConstants.Type.KEYSYM, 53, KeyBindings.CATEGORY);

    /** Registered only so a resource reload can drop the compiled post chain. */
    public static final NightVisionHandler RELOAD = new NightVisionHandler();

    private static PostChain chain;
    private static int lastWidth;
    private static int lastHeight;

    /** Whether the player has switched the sight on; it survives leaving and re-entering scope. */
    private static boolean enabled;

    private NightVisionHandler() {
    }

    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(NIGHT_VISION_KEY);
    }

    /** Called by the client setup: the key, the frame pass and the two state resets. */
    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (event.getAction() != ACTION_PRESS || !isNightVisionKey(event)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.screen != null) {
            return;
        }
        Entity ridden = player.getVehicle();
        if (!(ridden instanceof AbstractVehicle vehicle)) {
            return;
        }
        if (!isPackVehicle(vehicle)) {
            player.displayClientMessage(Component.translatable(
                    "message.ywzj_vehicle_armor.night_vision_pack"), true);
            return;
        }
        LocalVehiclePlayer local = LocalVehiclePlayer.instance;
        WeaponUnit weapon = local.getWeaponUnit();
        if (local.viewType != LocalVehiclePlayer.ViewType.SCOPE || weapon == null
                || weapon.getOpticalSightType() != WeaponUnitData.OpticalSightType.CRT) {
            player.displayClientMessage(Component.translatable(
                    "message.ywzj_vehicle_armor.night_vision_scope"), true);
            return;
        }
        // "showing", not "enabled": while thermal is up the green pass is hidden, and a press then
        // has to bring it back rather than switch off something the player cannot see.
        boolean showing = enabled && !local.thermalImaging;
        if (showing) {
            enabled = false;
            player.displayClientMessage(Component.translatable(
                    "message.ywzj_vehicle_armor.night_vision_off"), true);
        } else {
            enabled = true;
            local.thermalImaging = false;
            player.displayClientMessage(Component.translatable(
                    "message.ywzj_vehicle_armor.night_vision_on"), true);
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        if (!enabled || !inGunnerSight()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!ensureChain(minecraft)) {
            return;
        }
        try {
            chain.process(event.getPartialTick().getGameTimeDeltaPartialTick(false));
        } catch (Exception e) {
            // A shader that will not draw must not take the frame with it, and it must not retry
            // every frame either: log once, drop the chain, and switch the sight off.
            VehicleArmorMod.LOGGER.error("Night vision post chain failed", e);
            enabled = false;
            cleanup();
            return;
        }
        minecraft.getMainRenderTarget().bindWrite(true);
    }

    /** Dropping out of the vehicle switches the sight off, exactly as if it had never been on. */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!enabled) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || !(player.getVehicle() instanceof AbstractVehicle vehicle)
                || !isPackVehicle(vehicle)) {
            enabled = false;
            return;
        }
        if (LocalVehiclePlayer.instance.thermalImaging) {
            // Thermal took over: the two image modes are exclusive, and turning thermal off again
            // should return to the plain sight, not silently back to a night vision the player
            // had left behind.
            enabled = false;
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        enabled = false;
        cleanup();
    }

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        cleanup();
    }

    /** True only for the sight the scope actually draws, on the pack the sight belongs to. */
    private static boolean inGunnerSight() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || !(player.getVehicle() instanceof AbstractVehicle vehicle)
                || !isPackVehicle(vehicle)) {
            return false;
        }
        LocalVehiclePlayer local = LocalVehiclePlayer.instance;
        if (local.viewType != LocalVehiclePlayer.ViewType.SCOPE || local.thermalImaging) {
            return false;
        }
        WeaponUnit weapon = local.getWeaponUnit();
        return weapon != null
                && weapon.getOpticalSightType() == WeaponUnitData.OpticalSightType.CRT;
    }

    /** The same two-sided match the base mod's own key handler uses, so rebinding still works. */
    private static boolean isNightVisionKey(InputEvent.Key event) {
        return NIGHT_VISION_KEY.matches(event.getKey(), event.getScanCode())
                || NIGHT_VISION_KEY.matchesMouse(event.getKey());
    }

    private static boolean isPackVehicle(AbstractVehicle vehicle) {
        return vehicle.getVehicleId() != null
                && PACK_NAMESPACE.equals(vehicle.getVehicleId().getNamespace());
    }

    private static boolean ensureChain(Minecraft minecraft) {
        if (chain == null) {
            try {
                chain = new PostChain(minecraft.getTextureManager(), minecraft.getResourceManager(),
                        minecraft.getMainRenderTarget(), EFFECT);
                chain.resize(minecraft.getWindow().getWidth(),
                        minecraft.getWindow().getHeight());
                lastWidth = minecraft.getWindow().getWidth();
                lastHeight = minecraft.getWindow().getHeight();
            } catch (Exception e) {
                VehicleArmorMod.LOGGER.error("Could not create the night vision post chain", e);
                enabled = false;
                cleanup();
                return false;
            }
        }
        if (lastWidth != minecraft.getWindow().getWidth()
                || lastHeight != minecraft.getWindow().getHeight()) {
            lastWidth = minecraft.getWindow().getWidth();
            lastHeight = minecraft.getWindow().getHeight();
            chain.resize(lastWidth, lastHeight);
        }
        return true;
    }

    private static void cleanup() {
        if (chain != null) {
            chain.close();
            chain = null;
        }
        lastWidth = 0;
        lastHeight = 0;
    }
}
