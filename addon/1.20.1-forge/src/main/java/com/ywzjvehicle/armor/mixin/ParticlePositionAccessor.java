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

    @Accessor("f_107209_")
    double armor$getPreviousX();

    @Accessor("f_107210_")
    double armor$getPreviousY();

    @Accessor("f_107211_")
    double armor$getPreviousZ();

    @Accessor("f_107212_")
    double armor$getX();

    @Accessor("f_107213_")
    double armor$getY();

    @Accessor("f_107214_")
    double armor$getZ();
}
