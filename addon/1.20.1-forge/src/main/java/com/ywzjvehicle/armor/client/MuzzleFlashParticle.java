package com.ywzjvehicle.armor.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.ywzjvehicle.armor.VehicleArmorMod;
import com.ywzjvehicle.armor.mixin.ParticleEngineAtlasAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.Set;

/**
 * One layer of the muzzle flash, drawn additively.
 *
 * <p>Additive blending is the whole difference between a bright edge and a light source. Ordinary
 * alpha blending can only replace what is behind a sprite; adding it means five overlapping sheets
 * turn into a white-hot centre, and a sheet over dark ground lights that ground without washing it
 * out. The four layers the effect actually uses - core, jet, side blast and sparks - all share this
 * particle class and differ only in texture family, size, life and tint.
 *
 * <p>Each sheet picks a random frame and a random roll at spawn. That is what keeps a burst of
 * twenty particles from looking like the same stamp twenty times; the frame also changes once,
 * just over halfway through its life, so the flame breaks up while it fades.
 *
 * <p>The texture is resolved from the particle atlas by hand rather than through a
 * {@code SpriteSet}. Forge's {@code registerSpriteSet} takes a package-private interface type in
 * the SRG jar, which an addon compiled against that jar cannot name; {@code registerSprite} is the
 * public half of the same API.
 */
public final class MuzzleFlashParticle extends TextureSheetParticle {

    /**
     * Additive, depth-tested, fully bright.
     *
     * <p>{@code depthMask(false)} is deliberate: the sheets must not occlude each other, or the
     * second layer would be hidden by the first and the flash would lose its accumulated heat.
     * They are still depth-test capable, so a wall between the muzzle and the camera hides them.
     */
    private static final ParticleRenderType ADDITIVE = new ParticleRenderType() {
        @Override
        public void m_6505_(BufferBuilder buffer, TextureManager textureManager) {
            RenderSystem.depthMask(false);
            RenderSystem.setShader(GameRenderer::m_172829_);
            RenderSystem.setShaderTexture(0, TextureAtlas.f_118260_);
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
            buffer.m_166779_(VertexFormat.Mode.QUADS, DefaultVertexFormat.f_85813_);
        }

        @Override
        public void m_6294_(Tesselator tesselator) {
            tesselator.m_85914_();
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
        }
    };

    private static final Set<ResourceLocation> MISSING_REPORTED = new HashSet<>();

    private final TextureAtlasSprite[] frames;
    private final boolean spark;
    private int frameIndex;
    private boolean switched;

    public MuzzleFlashParticle(ClientLevel level, double x, double y, double z,
                               double vx, double vy, double vz,
                               String textureBase, int frameCount,
                               float size, int life,
                               float red, float green, float blue, boolean spark) {
        super(level, x, y, z, vx, vy, vz);

        // Resolved at spawn rather than provider registration: the provider is registered while
        // the client is still starting, and the particle atlas is not necessarily stitched yet.
        //
        // The atlas comes from ParticleEngine, not from Minecraft#getTextureAtlas: the particle
        // atlas is not in Minecraft's atlas map, and asking for it there throws from Map.get.
        TextureAtlas atlas = null;
        if (Minecraft.m_91087_().f_91061_ != null) {
            atlas = ((ParticleEngineAtlasAccessor) Minecraft.m_91087_().f_91061_)
                    .ywzjarmor$particleAtlas();
        }
        this.frames = new TextureAtlasSprite[frameCount];
        for (int i = 0; i < frameCount; i++) {
            ResourceLocation id = new ResourceLocation(VehicleArmorMod.MOD_ID,
                    textureBase + "_" + i);
            this.frames[i] = atlas == null ? null : atlas.m_118316_(id);
            if (this.frames[i] == null && MISSING_REPORTED.add(id)) {
                VehicleArmorMod.LOGGER.warn(
                        "[Vehicle Armor] particle sprite {} is not in the particle atlas", id);
            }
        }
        this.frameIndex = level.f_46441_.m_188503_(frameCount);
        this.spark = spark;

        this.f_107663_ = size * (0.72F + level.f_46441_.m_188501_() * 0.56F);
        this.f_107225_ = Math.max(2, life + level.f_46441_.m_188503_(3) - 1);
        this.f_107226_ = spark ? 0.12F : 0.0F;
        this.f_107219_ = false;

        float tint = 0.88F + level.f_46441_.m_188501_() * 0.24F;
        this.m_107253_(Math.min(1.0F, red * tint), Math.min(1.0F, green * tint),
                Math.min(1.0F, blue * tint));
        this.m_107271_(1.0F);

        // Roll about the view axis. Both fields are set so the sheet does not rotate over its
        // life; the shape is randomised once, which is enough at a four-tick lifetime.
        this.f_107231_ = level.f_46441_.m_188501_() * (float) (Math.PI * 2.0);
        this.f_107204_ = this.f_107231_;

        if (this.frames[this.frameIndex] != null) {
            this.m_108337_(this.frames[this.frameIndex]);
        } else {
            for (TextureAtlasSprite frame : this.frames) {
                if (frame != null) {
                    this.m_108337_(frame);
                    break;
                }
            }
        }
    }

    @Override
    public void m_5989_() {
        super.m_5989_();
        if (this.f_107220_) {
            return;
        }
        float progress = (float) this.f_107224_ / (float) this.f_107225_;

        if (!this.spark && !this.switched && this.frames.length > 1 && progress > 0.55F) {
            this.switched = true;
            this.frameIndex = (this.frameIndex + 1
                    + this.f_107223_.m_188503_(this.frames.length - 1)) % this.frames.length;
            if (this.frames[this.frameIndex] != null) {
                this.m_108337_(this.frames[this.frameIndex]);
            }
        }

        this.f_107230_ = Math.max(0.0F, 1.0F - progress * progress);
        this.f_107663_ *= this.spark ? 0.985F : 0.965F;
        float drag = this.spark ? 0.97F : 0.86F;
        this.f_107215_ *= drag;
        this.f_107216_ *= drag;
        this.f_107217_ *= drag;
    }

    /**
     * Draws nothing until a sprite has been resolved.
     *
     * <p>The alternative is a null sprite reaching the quad renderer and throwing in the render
     * loop; a missing texture should cost one warning line, not the frame.
     */
    @Override
    public void m_5744_(VertexConsumer buffer, Camera camera, float partialTick) {
        if (this.f_108321_ == null) {
            return;
        }
        super.m_5744_(buffer, camera, partialTick);
    }

    /** Full daylight, so the flame is not swallowed by a night-time lightmap. */
    @Override
    protected int m_6355_(float partialTick) {
        return 0xF000F0;
    }

    @Override
    public ParticleRenderType m_7556_() {
        return ADDITIVE;
    }
}
