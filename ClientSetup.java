package com.ywzjvehicle.armor;

import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;

/**
 * Client-only wiring.
 *
 * <p>Kept in its own class so nothing that references client classes is ever loaded on a dedicated
 * server: the mod constructor only touches this when the running side is the client.
 */
public final class ClientSetup {

    private ClientSetup() {
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.register(ArmorScannerOverlay.class);
        MinecraftForge.EVENT_BUS.register(WeaponKeyHandler.class);
    }

    /**
     * Opens the ammunition compendium, called from the item's own use - which is on both sides, so
     * the caller checks the side before getting here.
     */
    public static void openAmmoCompendium() {
        Minecraft.m_91087_().m_91152_(new AmmoCompendiumScreen());
    }

    /** Opens the choice of load for the round in the gun, from the left Alt key. */
    public static void openLoadChooser(ArmorConfig.Ammo round, String ownName) {
        Minecraft.m_91087_().m_91152_(new LoadChooserScreen(round, ownName));
    }
}
