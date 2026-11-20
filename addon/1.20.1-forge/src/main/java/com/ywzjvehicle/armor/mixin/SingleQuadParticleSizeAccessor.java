package com.ywzjvehicle.armor.mixin;

import net.minecraft.client.particle.SingleQuadParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The billboard size a single-quad particle renders at, which is the smoke puff's world size. */
@Mixin(SingleQuadParticle.class)
public interface SingleQuadParticleSizeAccessor {

    @Accessor("f_107663_")
    float armor$getSize();
}
