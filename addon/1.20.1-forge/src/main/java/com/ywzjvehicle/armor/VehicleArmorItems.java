package com.ywzjvehicle.armor;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * The addon's two items.
 *
 * <p>The scanner carries no behaviour of its own - its readout is drawn client-side simply for
 * holding it - while the compendium opens a screen when it is used.
 */
public final class VehicleArmorItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, VehicleArmorMod.MOD_ID);

    public static final RegistryObject<Item> ARMOR_SCANNER =
            ITEMS.register("armor_scanner", () -> new Item(new Item.Properties()));

    public static final RegistryObject<Item> AMMO_COMPENDIUM =
            ITEMS.register("ammo_compendium", () -> new AmmoCompendiumItem(new Item.Properties()));

    /** The vehicle mod's own misc tab, so the scanner sits next to the tools it belongs with. */
    private static final ResourceKey<CreativeModeTab> VEHICLE_MISC_TAB = ResourceKey.m_135785_(
            Registries.f_279569_, new ResourceLocation("ywzj_vehicle", "tab_misc"));

    private VehicleArmorItems() {
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        modBus.register(VehicleArmorItems.class);
    }

    @SubscribeEvent
    public static void onBuildTab(BuildCreativeModeTabContentsEvent event) {
        if (VEHICLE_MISC_TAB.equals(event.getTabKey())) {
            event.accept(ARMOR_SCANNER);
            event.accept(AMMO_COMPENDIUM);
        }
    }
}
