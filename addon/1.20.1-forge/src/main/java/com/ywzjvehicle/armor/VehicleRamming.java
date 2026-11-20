package com.ywzjvehicle.armor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.ywzj.vehicle.all.AllConfigs;
import org.ywzj.vehicle.api.event.VehicleCollectCollisionEvent;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;

import java.util.HashSet;
import java.util.Set;

/**
 * The high-speed block-crush path for the Armor Pioneer vehicles.
 *
 * <p>The base mod already destroys blocks, but only after the vehicle has been stuck against the
 * same face for ten ticks, and with no speed or mass term - a slow grind. This handler runs on the
 * contacts the base already collects ({@link VehicleCollectCollisionEvent}) and, while the vehicle
 * is still moving into a face, destroys the blocks whose hardness is below the vehicle's
 * speed-and-mass budget. The base grind stays in place; this is the high-speed counterpart.
 *
 * <p><b>The budget</b> is {@link #hardnessLimit}: {@code base_hardness + hardness_per_speed *
 * speed * sqrt(mass / mass_reference)}, speed in blocks per tick and mass in tonnes. The shipped
 * numbers let a 40t tank break plants and glass at the threshold, wood and stone at a few metres
 * per second, and iron at speed; a 2t car reaches only the softest blocks. Obsidian (50) and
 * bedrock (-1) have their own hard caps and never break.
 *
 * <p><b>Scope and flags.</b> Only the ids in {@code vehicle_ramming.vehicles} ram (shipped: the
 * seven Armor Pioneer vehicles), and only when the vehicle's own
 * {@code physics_info.can_destroy_block} and the base mod's global {@code canDestroyBlock} are on.
 * The per-vehicle flag is the pack's statement about what that vehicle may do; this handler does
 * not override it.
 *
 * <p><b>The ground is exempt.</b> Natural terrain is never destroyed, however fast the vehicle
 * is: the game's own terrain tags ({@code base_stone_overworld}, {@code base_stone_nether},
 * {@code dirt}, {@code sand}, {@code snow}, {@code ice}, {@code terracotta}) plus the untagged
 * natural blocks (gravel, clay, sandstones, end stone, calcite, dripstone, amethyst, sculk, soul
 * soil and a few more) are filtered before the hardness test. A modded block with a vanilla
 * terrain tag is covered; anything else goes in {@code natural_ground_blocks}. Because Minecraft
 * records no provenance, this is a material rule: a player-built dirt wall survives too, while
 * cobblestone, stone bricks, planks, logs and glass do not.
 *
 * <p>Block drops are off, matching the base mod; the break particles and sound are the feedback.
 * No claim/permission check yet - another thing a later pass should add.
 */
public final class VehicleRamming {

    private VehicleRamming() {
    }

    private static boolean naturalGround(ArmorConfig.VehicleRamming settings, BlockState state) {
        return NaturalGround.protects(settings, state);
    }

    /**
     * The natural-ground test, in a holder of its own.
     *
     * <p>Deliberately lazy: reading {@code Registries.BLOCK} for a tag key pulls in
     * {@code BuiltInRegistries}, and a class initialiser that does it in {@code VehicleRamming}
     * itself would run whenever the offline check calls {@link #hardnessLimit} - before the game
     * has bootstrapped its registries, and without the libraries that initialisation needs. A
     * nested class is initialised on first use, which here is the first real block contact.
     */
    private static final class NaturalGround {

        private static TagKey<Block> tag(String path) {
            return TagKey.m_203882_(Registries.f_256747_,
                    new ResourceLocation("minecraft", path));
        }

        /** The world's stone, in all three dimensions. */
        private static final TagKey<Block> BASE_STONE_OVERWORLD = tag("base_stone_overworld");
        private static final TagKey<Block> BASE_STONE_NETHER = tag("base_stone_nether");
        private static final TagKey<Block> DIRT = tag("dirt");
        private static final TagKey<Block> SAND = tag("sand");
        private static final TagKey<Block> SNOW = tag("snow");
        private static final TagKey<Block> ICE = tag("ice");
        private static final TagKey<Block> TERRACOTTA = tag("terracotta");

        /**
         * Natural blocks that carry no vanilla terrain tag, by id.
         *
         * <p>Resolved through {@link ForgeRegistries#BLOCKS} at ram time rather than held as
         * {@code Block} references: a block absent from this pack must not make the class fail to
         * load, and an id the registry does not know simply never matches.
         */
        private static final Set<String> UNTAGGED_NATURAL = Set.of(
                "minecraft:gravel", "minecraft:clay",
                "minecraft:end_stone",
                "minecraft:sandstone", "minecraft:red_sandstone",
                "minecraft:calcite", "minecraft:dripstone_block", "minecraft:pointed_dripstone",
                "minecraft:amethyst_block", "minecraft:budding_amethyst",
                "minecraft:smooth_basalt", "minecraft:magma_block", "minecraft:glowstone",
                "minecraft:soul_sand", "minecraft:soul_soil",
                "minecraft:farmland", "minecraft:dirt_path",
                "minecraft:sculk", "minecraft:sculk_vein", "minecraft:sculk_catalyst",
                "minecraft:sculk_shrieker", "minecraft:sculk_sensor",
                "minecraft:calibrated_sculk_sensor",
                "minecraft:crimson_nylium", "minecraft:warped_nylium",
                "minecraft:nether_wart_block", "minecraft:warped_wart_block",
                "minecraft:obsidian", "minecraft:crying_obsidian");

        /**
         * @return whether this block is the world's own ground and must survive the ram.
         *
         * <p>Tags first, because that is what covers modded terrain without naming it; the id
         * set second, for the natural blocks vanilla leaves untagged; the config's extra list
         * last, for a mod's ground that neither covers.
         */
        private static boolean protects(ArmorConfig.VehicleRamming settings, BlockState state) {
            if (!(settings.protect_natural_ground == null || settings.protect_natural_ground)) {
                return false;
            }
            if (state.m_204336_(BASE_STONE_OVERWORLD)
                    || state.m_204336_(BASE_STONE_NETHER)
                    || state.m_204336_(DIRT)
                    || state.m_204336_(SAND)
                    || state.m_204336_(SNOW)
                    || state.m_204336_(ICE)
                    || state.m_204336_(TERRACOTTA)) {
                return true;
            }
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.m_60734_());
            if (id == null) {
                return false;
            }
            String key = id.toString();
            if (UNTAGGED_NATURAL.contains(key)) {
                return true;
            }
            return settings.natural_ground_blocks != null
                    && settings.natural_ground_blocks.contains(key);
        }
    }

    /**
     * The hardness budget for one vehicle at one speed.
     *
     * <p>Public so the offline check can exercise the curve without a level or a collision event.
     */
    public static float hardnessLimit(ArmorConfig.VehicleRamming settings, double speed,
                                      double massTonnes) {
        double reference = Math.max(0.001D, settings.mass_reference_tons);
        double massFactor = Math.sqrt(Math.max(0.0D, massTonnes / reference));
        return (float) (settings.base_hardness + settings.hardness_per_speed * speed * massFactor);
    }

    @SubscribeEvent
    public static void onCollect(VehicleCollectCollisionEvent event) {
        ArmorConfig.VehicleRamming settings = ArmorConfig.vehicleRamming();
        if (!settings.active()) {
            return;
        }
        AbstractVehicle vehicle = event.getVehicle();
        if (vehicle == null || !settings.appliesTo(vehicle.getVehicleId().toString())) {
            return;
        }
        Level level = vehicle.m_9236_();
        if (level == null || level.m_5776_()) {
            return;
        }
        // The vehicle's own statement and the server's global switch. A vehicle the pack marked
        // as unable to destroy blocks keeps that behaviour.
        if (!vehicle.physicsEngine.physicsInfo.canDestroyBlock
                || !((Boolean) AllConfigs.common.canDestroyBlock.get())) {
            return;
        }
        Vec3 velocity = vehicle.m_20184_();
        double speed = velocity.m_82553_();
        if (speed < settings.speed_threshold) {
            return;
        }
        float limit = hardnessLimit(settings, speed, vehicle.curbWeight / 1000.0D);
        Vec3 centre = centre(vehicle);
        Set<BlockPos> candidates = new HashSet<>();
        for (VehicleCubeOBB.CubePoint point : event.getTouchPoints()) {
            VehicleCubeOBB.CubeFace face = point.cubeFace();
            if (face == VehicleCubeOBB.CubeFace.TOP || face == VehicleCubeOBB.CubeFace.BOTTOM) {
                continue;
            }
            Vec3 world = new Vec3(point.cachedWorldPos());
            // Only the leading side: the contact has to be in the direction the vehicle is moving.
            if (world.m_82546_(centre).m_82526_(velocity) <= 0.0) {
                continue;
            }
            BlockPos base = BlockPos.m_274446_(world);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    candidates.add(base.m_7918_(dx, 0, dz));
                }
            }
        }
        int destroyed = 0;
        for (BlockPos pos : candidates) {
            if (destroyed >= settings.max_blocks_per_tick) {
                break;
            }
            BlockState state = level.m_8055_(pos);
            if (state.m_60795_()) {
                continue;
            }
            if (settings.skip_block_entities
                    && state.m_60734_() instanceof net.minecraft.world.level.block.EntityBlock) {
                continue;
            }
            // The world's own ground is never rammed away, whatever the speed. Material rule,
            // not provenance: see the note on VehicleRamming#protect_natural_ground.
            if (naturalGround(settings, state)) {
                continue;
            }
            float hardness = state.m_60800_(level, pos);
            if (hardness < 0.0F || hardness >= 50.0F || hardness > limit) {
                continue;
            }
            level.m_46953_(pos, false, vehicle);
            destroyed++;
        }
    }

    private static Vec3 centre(AbstractVehicle vehicle) {
        if (vehicle.getMainCubeOBB() != null) {
            org.joml.Vector3f centre = vehicle.getMainCubeOBB().obb().center();
            return new Vec3(centre.x, centre.y, centre.z);
        }
        return vehicle.m_20191_().m_82399_();
    }
}
