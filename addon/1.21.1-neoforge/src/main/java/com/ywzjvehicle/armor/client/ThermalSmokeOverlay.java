package com.ywzjvehicle.armor.client;

import com.ywzjvehicle.armor.VehicleArmorMod;
import com.ywzjvehicle.armor.mixin.ParticlePositionAccessor;
import com.ywzjvehicle.armor.mixin.SingleQuadParticleSizeAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.bus.api.SubscribeEvent;
import org.ywzj.vehicle.client.particle.SmokeCloudParticle;
import org.ywzj.vehicle.client.shader.ThermalHandler;
import org.ywzj.vehicle.mixin.client.ParticleEngineAccessor;
import org.ywzj.vehicle.util.VectorUtil;

import java.util.Map;
import java.util.Queue;

/**
 * War Thunder-style smoke over a thermal sight, drawn in the GUI phase.
 *
 * <p>The first attempts put the smoke into the base's thermal buffer or painted it at
 * {@code RenderLevelStageEvent.AFTER_LEVEL}. Both are inside the world pipeline, and this instance
 * runs Oculus with the Sundial Lite shaderpack: the pack owns that pipeline and the final composite,
 * so a pass placed there can be overwritten or never sampled. That is the "光影" behaviour seen in
 * game, not a missing smoke field in the vehicle pack.
 *
 * <p>This class therefore works in screen space, at {@link RenderGuiEvent.Pre} - after the world
 * and the shaderpack's final composite, before the HUD. It projects every live
 * {@link SmokeCloudParticle} with the base mod's own {@code VectorUtil.worldToScreen} (the matrices
 * the base's aim reticle already uses) and draws a soft white puff at that point. The thermal image
 * is already in the framebuffer behind it, so the puffs cover the heat source; the HUD and the
 * scope reticle are drawn later and stay on top. Because it is GUI drawing, it is independent of
 * the shaderpack and of the thermal post chain.
 *
 * <p>Only {@link ThermalHandler#isActive()} (the thermal sight) is affected. Normal view and night
 * vision keep the game's own smoke. There is no depth test in GUI space, so a puff can also cover a
 * wall in front of it; the base thermal sight is a see-through sensor anyway, and the alternative
 * (sampling the shaderpack's depth buffer) is exactly what the pack makes unreliable.
 *
 * <p>The puff texture is a soft white disc at {@code textures/gui/thermal_smoke.png}. Its centre is
 * opaque enough that a thick bank hides the target; overlapping puffs build density, as they do in
 * War Thunder.
 */
public final class ThermalSmokeOverlay {

    private static final ResourceLocation PUFF =
            ResourceLocation.fromNamespaceAndPath("ywzj_vehicle_armor", "textures/gui/thermal_smoke.png");

    /** The GUI texture is 64 x 64. */
    private static final int TEXTURE_SIZE = 64;

    private static boolean logged;

    private ThermalSmokeOverlay() {
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null
                || !ThermalHandler.isActive()) {
            return;
        }
        Map<ParticleRenderType, Queue<Particle>> byType =
                ((ParticleEngineAccessor) minecraft.particleEngine).getParticles();
        Queue<Particle> particles = byType.get(ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT);
        if (particles == null || particles.isEmpty()) {
            return;
        }

        GuiGraphics graphics = event.getGuiGraphics();
        int guiWidth = graphics.guiWidth();
        int guiHeight = graphics.guiHeight();
        int windowWidth = minecraft.getWindow().getGuiScaledWidth();
        int windowHeight = minecraft.getWindow().getGuiScaledHeight();
        if (guiWidth <= 0 || guiHeight <= 0 || windowWidth <= 0 || windowHeight <= 0) {
            return;
        }
        double scaleX = (double) guiWidth / windowWidth;
        double scaleY = (double) guiHeight / windowHeight;
        double projectionY = Math.abs(VectorUtil.projectionMatrix.m11());
        if (projectionY < 1.0E-6) {
            return;
        }

        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        boolean drew = false;
        for (Particle particle : particles) {
            if (!(particle instanceof SmokeCloudParticle) || !particle.isAlive()) {
                continue;
            }
            ParticlePositionAccessor position = (ParticlePositionAccessor) particle;
            // The particle renderer interpolates the previous and current positions; match it,
            // or a fast cloud lags a tick behind the visible smoke.
            Vec3 centre = new Vec3(
                    Mth.lerp(partialTick, position.armor$getPreviousX(), position.armor$getX()),
                    Mth.lerp(partialTick, position.armor$getPreviousY(), position.armor$getY()),
                    Mth.lerp(partialTick, position.armor$getPreviousZ(), position.armor$getZ()));
            Vec3 screen = VectorUtil.worldToScreen(centre);
            double depth = screen.z;
            if (depth <= 0.1) {
                continue;
            }
            double worldSize = ((SingleQuadParticleSizeAccessor) particle).armor$getSize();
            if (worldSize <= 0.001) {
                continue;
            }
            double radiusPixels = 0.5 * worldSize * (windowHeight * 0.5) * projectionY / depth;
            if (radiusPixels < 1.0 || radiusPixels > 2000.0) {
                continue;
            }
            double radiusGui = radiusPixels * Math.min(scaleX, scaleY);
            int size = (int) Math.max(2.0, Math.round(radiusGui * 2.0));
            int x = (int) Math.round(screen.x * scaleX - size * 0.5);
            int y = (int) Math.round(screen.y * scaleY - size * 0.5);
            if (x + size < 0 || y + size < 0 || x > guiWidth || y > guiHeight) {
                continue;
            }
            graphics.blit(PUFF, x, y, 0, 0.0F, 0.0F, size, size,
                    TEXTURE_SIZE, TEXTURE_SIZE);
            drew = true;
        }
        if (drew && !logged) {
            logged = true;
            VehicleArmorMod.LOGGER.info("thermal smoke GUI overlay drawing");
        }
    }
}
