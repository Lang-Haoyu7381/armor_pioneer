package com.ywzjvehicle.armor;

import com.ywzjvehicle.armor.client.ArmorHudOverlay;
import com.ywzjvehicle.armor.client.KeyBindings;
import com.ywzjvehicle.armor.client.MuzzleFlashParticle;
import com.ywzjvehicle.armor.client.NightVisionHandler;
import com.ywzjvehicle.armor.client.PeriscopeView;
import com.ywzjvehicle.armor.client.ThermalSmokeOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client-only wiring.
 *
 * <p>Kept in its own class so nothing that references client classes is ever loaded on a dedicated
 * server: the mod constructor only touches this when the running side is the client.
 */
public final class ClientSetup {

    private ClientSetup() {
    }

    public static void register(IEventBus modBus) {
        NeoForge.EVENT_BUS.register(ArmorScannerOverlay.class);
        NeoForge.EVENT_BUS.register(WeaponKeyHandler.class);
        // The Armor Pioneer gunner's night sight: key 5, a green post-pass, and no server state.
        NeoForge.EVENT_BUS.register(NightVisionHandler.class);
        // The thermal sight's smoke cover, drawn after Oculus/shaderpack's final world composite.
        NeoForge.EVENT_BUS.register(ThermalSmokeOverlay.class);
        // The commander's hatch periscope: a camera point from the config, moved by a client mixin.
        NeoForge.EVENT_BUS.register(PeriscopeView.class);
        // Cannon reports and muzzle flashes - client-side only, from the base mod's own fire event.
        NeoForge.EVENT_BUS.register(WeaponEffects.class);
        // The supersonic round's shock cone, measured from the shell's own flight.
        NeoForge.EVENT_BUS.register(ProjectileCrack.class);
        // Particle providers are a mod-bus event; registered explicitly for the same reason the
        // rest of this class is: an annotation scan that does not happen loses the effect silently.
        modBus.addListener(ClientSetup::registerParticleProviders);
        // The persistent in-vehicle strip. NeoForge 1.21 draws HUD layers through
        // RegisterGuiLayersEvent rather than Forge's IGuiOverlay list; registering it above every
        // other layer is what puts it after the base mod's HUD, so it draws over the panels it
        // belongs beside instead of under them.
        modBus.addListener(ClientSetup::registerOverlays);
        // The night sight's key is a real key mapping, so it can be rebound with every other
        // vehicle key, and the shader chain is dropped when resource packs reload.
        modBus.addListener(NightVisionHandler::registerKeyMappings);
        // The periscope's key is a normal key mapping too, so it can be rebound in Options > Controls.
        modBus.addListener(PeriscopeView::registerKeyMappings);
        // The crew keys, the load chooser and the hybrid switch, in the addon's own category.
        modBus.addListener(KeyBindings::register);
        modBus.addListener(ClientSetup::registerReloadListeners);
    }

    /** One layer, above every other, for the reasons in {@link #register(IEventBus)}. */
    public static void registerOverlays(RegisterGuiLayersEvent event) {
        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(VehicleArmorMod.MOD_ID, "status"),
                new ArmorHudOverlay());
    }

    /** The night-sight shader is rebuilt from disk whenever a resource pack changes. */
    public static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(NightVisionHandler.RELOAD);
    }

    /**
     * One provider per flash size, carrying the size, lifetime and tint that the particle type
     * itself cannot.
     *
     * <p>{@code registerSprite} rather than {@code registerSpriteSet} is deliberate: the latter
     * takes a package-private interface type in the production jar, which an addon compiled
     * against that jar cannot name. See {@link MuzzleFlashParticle}.
     */
    public static void registerParticleProviders(RegisterParticleProvidersEvent event) {
        event.registerSprite(ArmorParticles.MUZZLE_FLASH_SMALL.get(),
                (type, level, x, y, z, vx, vy, vz) -> new MuzzleFlashParticle(level, x, y, z, vx, vy, vz,
                        "muzzle_flash", 4, 0.60F, 5, 1.0F, 0.92F, 0.70F, false));
        event.registerSprite(ArmorParticles.MUZZLE_FLASH.get(),
                (type, level, x, y, z, vx, vy, vz) -> new MuzzleFlashParticle(level, x, y, z, vx, vy, vz,
                        "muzzle_flash", 4, 1.25F, 6, 1.0F, 0.82F, 0.45F, false));
        event.registerSprite(ArmorParticles.MUZZLE_FLASH_LARGE.get(),
                (type, level, x, y, z, vx, vy, vz) -> new MuzzleFlashParticle(level, x, y, z, vx, vy, vz,
                        "muzzle_flash", 4, 2.10F, 7, 1.0F, 0.72F, 0.32F, false));
        event.registerSprite(ArmorParticles.MUZZLE_FLASH_JET.get(),
                (type, level, x, y, z, vx, vy, vz) -> new MuzzleFlashParticle(level, x, y, z, vx, vy, vz,
                        "muzzle_jet", 3, 1.00F, 6, 1.0F, 0.55F, 0.16F, false));
        event.registerSprite(ArmorParticles.MUZZLE_FLASH_SPARK.get(),
                (type, level, x, y, z, vx, vy, vz) -> new MuzzleFlashParticle(level, x, y, z, vx, vy, vz,
                        "muzzle_spark", 1, 0.14F, 12, 1.0F, 0.88F, 0.50F, true));
    }

    /**
     * Opens the ammunition compendium, called from the item's own use - which is on both sides, so
     * the caller checks the side before getting here.
     */
    public static void openAmmoCompendium() {
        Minecraft.getInstance().setScreen(new AmmoCompendiumScreen());
    }

    /** Opens the choice of load for the round in the gun, from the left Alt key. */
    public static void openLoadChooser(ArmorConfig.Ammo round, String ownName) {
        Minecraft.getInstance().setScreen(new LoadChooserScreen(round, ownName));
    }
}
