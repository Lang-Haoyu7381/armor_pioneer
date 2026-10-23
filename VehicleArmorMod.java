package com.ywzjvehicle.armor;

import com.ywzjvehicle.armor.network.VehicleArmorNetwork;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point of the T-64 armour-equivalence addon.
 *
 * <p>The damage logic lives in {@link ArmorSystem}, injected into
 * {@code org.ywzj.vehicle.vehicle.DamageSystem} by {@code DamageSystemMixin}. Trigger blocking
 * lives in {@link FireControlHandler} and the scanner readout in {@link ArmorScannerOverlay}.
 */
@Mod(VehicleArmorMod.MOD_ID)
public class VehicleArmorMod {

    public static final String MOD_ID = "ywzj_vehicle_armor";
    public static final Logger LOGGER = LoggerFactory.getLogger("VehicleArmor");

    public VehicleArmorMod() {
        ArmorConfig.load();

        // The fuze channel is registered on both sides and before anything can connect, so a
        // client that has the addon can send as soon as it is in a world.
        VehicleArmorNetwork.register();

        // Registered explicitly rather than via @EventBusSubscriber so the handlers cannot go
        // missing if annotation scanning is unavailable.
        MinecraftForge.EVENT_BUS.register(FireControlHandler.class);
        MinecraftForge.EVENT_BUS.register(GunnerSessionHandler.class);
        // Crew wounds, role announcements and the commander's live orders all ride on one server
        // tick handler; registering it explicitly keeps it next to the other event handlers.
        MinecraftForge.EVENT_BUS.register(CrewState.class);

        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        VehicleArmorItems.register(modBus);

        if (FMLEnvironment.dist.isClient()) {
            ClientSetup.register();
        }
    }
}
