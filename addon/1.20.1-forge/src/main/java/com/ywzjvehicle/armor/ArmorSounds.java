package com.ywzjvehicle.armor;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * The cannon reports the addon plays instead of the base mod's single generic shot.
 *
 * <p>The files are synthesised by {@code tools/audio/make_cannon_audio.py}, not sampled from
 * another sound pack, so the set can be regenerated and there is nothing borrowed in it. Each
 * registration needs a matching entry in {@code assets/ywzj_vehicle_armor/sounds.json} and an
 * Ogg file beside it; {@code EffectsCheck} holds the three together.
 *
 * <p>Registration is common rather than client-only. A dedicated server still has to know the
 * sound events exist before a client refers to them, even though only the client ever plays them.
 */
public final class ArmorSounds {

    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, VehicleArmorMod.MOD_ID);

    /** 60-89 mm: the light end of the tank-gun range, including the 75 mm and 88 mm. */
    public static final RegistryObject<SoundEvent> CANNON_LIGHT = register("cannon_light");
    /** 90-109 mm, chiefly the 105 mm. */
    public static final RegistryObject<SoundEvent> CANNON_MEDIUM = register("cannon_medium");
    /** 110-139 mm: the modern 120 mm and 125 mm. */
    public static final RegistryObject<SoundEvent> CANNON_HEAVY = register("cannon_heavy");
    /** 140 mm and up, for anything the packs add above the modern tank-gun range. */
    public static final RegistryObject<SoundEvent> CANNON_ARTILLERY = register("cannon_artillery");
    /** 20-59 mm: the 30 mm and 35 mm autocannons and grenade launchers. */
    public static final RegistryObject<SoundEvent> AUTOCANNON = register("autocannon");
    /** The delayed report coming back from the surroundings; played after a tank gun. */
    public static final RegistryObject<SoundEvent> CANNON_ECHO = register("cannon_echo");
    /** Breech block and recoil, a fraction of a second behind the shot. */
    public static final RegistryObject<SoundEvent> BREECH_CLANK = register("breech_clank");
    /**
     * The shock cone of a supersonic round passing the listener - a whip, not a report.
     *
     * <p>Played by {@code ProjectileCrack} from the shell's own client-side flight, not from the
     * firing event: the whole point is where the round is when it goes past, and only the
     * projectile knows that.
     */
    public static final RegistryObject<SoundEvent> PROJECTILE_CRACK = register("projectile_crack");

    private ArmorSounds() {
    }

    private static RegistryObject<SoundEvent> register(String name) {
        ResourceLocation id = new ResourceLocation(VehicleArmorMod.MOD_ID, name);
        return SOUND_EVENTS.register(name, () -> SoundEvent.m_262824_(id));
    }

    public static void register(IEventBus modBus) {
        SOUND_EVENTS.register(modBus);
    }
}
