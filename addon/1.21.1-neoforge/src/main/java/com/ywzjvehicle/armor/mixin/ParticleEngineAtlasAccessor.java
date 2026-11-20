package com.ywzjvehicle.armor.mixin;

import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the particle atlas.
 *
 * <p>The particle atlas is not one of the atlases {@code Minecraft#getTextureAtlas} knows about:
 * it is an ordinary {@code TextureAtlas} field inside {@code ParticleEngine} and is stitched from
 * the pack's {@code particles/*.json} definitions. Asking {@code Minecraft} for
 * {@code textures/atlas/particles.png} returns a function over a different map, and calling it
 * throws a {@code NullPointerException} from a {@code Map.get} - which is exactly how the first
 * version of the flash failed, once per shot, with nothing visible in the world.
 *
 * <p>Client-only, like everything else that names a particle class.
 */
@Mixin(ParticleEngine.class)
public interface ParticleEngineAtlasAccessor {

    @Accessor("textureAtlas")
    TextureAtlas ywzjarmor$particleAtlas();
}
