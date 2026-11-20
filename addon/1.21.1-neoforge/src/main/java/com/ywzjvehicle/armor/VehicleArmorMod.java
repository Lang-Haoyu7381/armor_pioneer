package com.ywzjvehicle.armor;

import com.ywzjvehicle.armor.network.VehicleArmorNetwork;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
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

    /**
     * NeoForge hands the mod event bus to the constructor. 1.20.1 reached it through
     * {@code FMLJavaModLoadingContext}; that class is gone, and constructor injection is the
     * supported replacement.
     */
    public VehicleArmorMod(IEventBus modBus) {
        ArmorConfig.load();

        // The payloads are registered on both sides and before anything can connect, so a client
        // that has the addon can send as soon as it is in a world.
        modBus.addListener(VehicleArmorNetwork::register);

        // Registered explicitly rather than via @EventBusSubscriber so the handlers cannot go
        // missing if annotation scanning is unavailable.
        NeoForge.EVENT_BUS.register(FireControlHandler.class);
        NeoForge.EVENT_BUS.register(GunnerSessionHandler.class);
        // Crew wounds, role announcements and the commander's live orders all ride on one server
        // tick handler; registering it explicitly keeps it next to the other event handlers.
        NeoForge.EVENT_BUS.register(CrewState.class);
        // TACZ's explosive rounds detonate after the direct hit has already been resolved here;
        // taking the struck vehicle out of that blast is how the round stays measured once.
        NeoForge.EVENT_BUS.register(TaczExplosions.class);

        VehicleArmorItems.register(modBus);
        // The sound events and particle types have to exist on both sides: a client names them in
        // packets, even though only a client ever plays or spawns them.
        ArmorSounds.register(modBus);
        ArmorParticles.register(modBus);

        if (FMLEnvironment.dist.isClient()) {
            ClientSetup.register(modBus);
        }
    }
}
