package com.ywzjvehicle.armor.mixin;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * A particle's current world position.
 *
 * <p>Its bounding box is not usable here: {@code Particle} leaves it at the shared zero-size box
 * unless a subclass explicitly sets one, and {@code SmokeCloudParticle} does not. The three
 * protected doubles below are the position the renderer itself uses.
 */
@Mixin(Particle.class)
public interface ParticlePositionAccessor {

    @Accessor("xo")
    double armor$getPreviousX();

    @Accessor("yo")
    double armor$getPreviousY();

    @Accessor("zo")
    double armor$getPreviousZ();

    @Accessor("x")
    double armor$getX();

    @Accessor("y")
    double armor$getY();

    @Accessor("z")
    double armor$getZ();
}
