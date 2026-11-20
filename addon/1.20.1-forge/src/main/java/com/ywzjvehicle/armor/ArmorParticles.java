package com.ywzjvehicle.armor;

import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * The three sizes of muzzle flash.
 *
 * <p>Size is part of the particle type rather than a number carried through the network: every
 * flash is spawned by a client for its own eyes, so there is nothing to serialise, and three types
 * cover the whole range from an autocannon's needle to a 152 mm gun's sheet of flame. The actual
 * scale within a type is randomised by the particle itself.
 *
 * <p>{@link SimpleParticleType}'s constructor is protected in the production (SRG) jar, so the
 * types are instances of a tiny subclass rather than of {@code SimpleParticleType} directly.
 */
public final class ArmorParticles {

    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(ForgeRegistries.PARTICLE_TYPES, VehicleArmorMod.MOD_ID);

    public static final RegistryObject<SimpleParticleType> MUZZLE_FLASH_SMALL =
            PARTICLE_TYPES.register("muzzle_flash_small", MuzzleFlashType::new);
    public static final RegistryObject<SimpleParticleType> MUZZLE_FLASH =
            PARTICLE_TYPES.register("muzzle_flash", MuzzleFlashType::new);
    public static final RegistryObject<SimpleParticleType> MUZZLE_FLASH_LARGE =
            PARTICLE_TYPES.register("muzzle_flash_large", MuzzleFlashType::new);
    /** The stretched, torn sheets that make the forward jet. */
    public static final RegistryObject<SimpleParticleType> MUZZLE_FLASH_JET =
            PARTICLE_TYPES.register("muzzle_flash_jet", MuzzleFlashType::new);
    /** Small additive embers thrown forward and outward. */
    public static final RegistryObject<SimpleParticleType> MUZZLE_FLASH_SPARK =
            PARTICLE_TYPES.register("muzzle_flash_spark", MuzzleFlashType::new);

    private ArmorParticles() {
    }

    public static void register(IEventBus modBus) {
        PARTICLE_TYPES.register(modBus);
    }

    /** A simple particle type whose constructor the SRG mapping left protected. */
    private static final class MuzzleFlashType extends SimpleParticleType {
        private MuzzleFlashType() {
            super(false);
        }
    }
}
