package com.ywzjvehicle.armor;

import com.ywzjvehicle.armor.client.ArmorUi;
import com.ywzjvehicle.armor.client.KeyBindings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.bus.api.SubscribeEvent;
import org.ywzj.vehicle.client.gui.VehicleHudLayout;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.structure.OBB;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the armour readout while the scanner is held in the main hand and pointed at a vehicle.
 *
 * <p>The sight line is cast against the same OBBs the damage system uses rather than the entity's
 * bounding box, so the readout names the plate a shot down that line would actually meet. The
 * plate itself is resolved by {@link ArmorSystem#plateFor}, which the damage system calls too -
 * so the number on screen cannot drift from the number used in combat.
 *
 * <p>One armour table is shown at a time, paged with the up and down arrows, because a vehicle
 * with several of them would otherwise push the readout too far down the screen to read while
 * still aiming - and because the tables are alternatives, not a list to compare at a glance.
 */
public final class ArmorScannerOverlay {

    /** How far the scanner reaches. Well past arm's length, since it is a sighting aid. */
    private static final double RANGE = 128.0;

    /** GLFW's action value for a key going down; a release would otherwise page twice per press. */
    private static final int ACTION_PRESS = 1;

    /**
     * Which table the readout is on. Kept as a running count rather than a bounded index and
     * reduced against the target's table list when drawn, so it stays sensible when the player
     * looks from a vehicle with three tables to one with two.
     */
    private static int view;

    private ArmorScannerOverlay() {
    }

    /** Pages the readout. Only while the scanner is in hand and nothing else has the keyboard. */
    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (event.getAction() != ACTION_PRESS) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null) {
            return;
        }
        if (minecraft.player.getMainHandItem().getItem() != VehicleArmorItems.ARMOR_SCANNER.get()) {
            return;
        }
        // The scanner pages with the same two keys the suspension uses. The crew handler sees the
        // scanner in hand and leaves the hull alone, so the shared mapping is not a conflict: the
        // item decides which meaning the press has.
        if (KeyBindings.CREW_UP.matches(event.getKey(), event.getScanCode())) {
            view--;
        } else if (KeyBindings.CREW_DOWN.matches(event.getKey(), event.getScanCode())) {
            view++;
        }
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!ArmorConfig.scannerShowsArmor()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return;
        }
        if (player.getMainHandItem().getItem() != VehicleArmorItems.ARMOR_SCANNER.get()) {
            return;
        }

        Vec3 from = new Vec3(player.getX(), player.getY() + player.getEyeHeight(), player.getZ());
        Vec3 look = VectorUtil.rotToVec(player.getXRot(), player.getYRot());
        Vec3 to = from.add(look.scale(RANGE));
        AABB area = player.getBoundingBox().inflate(RANGE);

        AbstractVehicle target = null;
        OBB targetObb = null;
        Vec3 targetPos = null;
        double bestDistance = Double.MAX_VALUE;

        for (Entity entity : minecraft.level.getEntities(player, area, candidate -> candidate instanceof AbstractVehicle)) {
            if (!(entity instanceof AbstractVehicle vehicle)) {
                continue;
            }
            if (ArmorSystem.armorFor(vehicle) == null) {
                continue;
            }
            VectorUtil.HitOBB hit = VectorUtil.hitOBB(vehicle.getOBBs(), from, to);
            if (hit == null) {
                continue;
            }
            if (hit.distance() < bestDistance) {
                bestDistance = hit.distance();
                target = vehicle;
                targetObb = hit.obb();
                targetPos = hit.hitPos();
            }
        }

        if (targetObb == null) {
            return;
        }

        PartUnit<?> partUnit = ArmorSystem.partUnitOf(target, targetObb);
        ArmorConfig.VehicleArmor armor = ArmorSystem.armorFor(target);

        // Entry 0 is the vehicle's own table - what a round naming no profile is measured against -
        // then one entry per ammunition profile in config order.
        List<String> profiles = new ArrayList<>(armor.overrides.keySet());
        int total = profiles.size() + 1;
        int selected = Math.floorMod(view, total);
        String profile = selected == 0 ? null : profiles.get(selected - 1);

        // Along the sight line, not the shooter's line: the readout answers "what does a shot from
        // here meet", and a screen that only works at an angle has to be shown as working or not
        // working depending on where the player is standing. Standing square on to a slat screen and
        // reading its numbers would be reading numbers that shot would never use.
        ArmorSystem.Zone zone =
                ArmorSystem.plateFor(armor, profile, target, targetObb, targetPos, partUnit, look);

        draw(event.getGuiGraphics(), minecraft, target, zone, profile, selected + 1, total);
    }

    /**
     * What to call the table currently on screen.
     *
     * <p>A table is named by the ammunition profile that selects it, and the profiles this addon
     * ships are named after warheads - so the footer reads 穿甲弹 / 破甲弹 / 高爆弹 rather than the
     * config's own keys, and does it in whatever language the client is set to.
     *
     * <p>The vehicle's own table has no profile at all, and it is what a round naming no profile is
     * measured against - every kinetic round, the dart and the machine guns alike - so it is the
     * armour-piercing table and says so. That is the same answer {@link AmmoCategory#derivedFrom}
     * gives for the same reason.
     *
     * <p>A profile naming no warhead keeps its own name. Calling it by a warhead would be a guess
     * printed as a fact, and the player would have no way to tell the two apart.
     */
    private static String tableLabel(String profile) {
        Warhead warhead = profile == null ? Warhead.AP : Warhead.byId(profile);
        return warhead == null ? profile
                : Component.translatable(warhead.translationKey()).getString();
    }

    /**
     * The readout, in base mod 0.6.2's HUD language.
     *
     * <p>Drawn beside the crosshair rather than across it, on the same 480-unit layout every other
     * panel uses, with the thickness as the one large number. The zone name is localised and, for a
     * plain hull or turret hit, carries the face - "炮塔正面" is the answer to the question the
     * scanner was taken out for, where "turret" was only half of it.
     */
    private static void draw(GuiGraphics graphics, Minecraft minecraft, AbstractVehicle target,
                             ArmorSystem.Zone zone, String profile, int page, int pages) {
        float scale = (float) minecraft.getWindow().getGuiScaledHeight() / 480.0F;
        float stroke = ArmorUi.stroke(scale);
        float width = 176.0F;
        float height = 88.0F;

        try (VehicleHudLayout layout = VehicleHudLayout.begin(graphics)) {
            float left = Math.min(layout.width() / 2.0F + 32.0F,
                    layout.width() - 18.0F - width);
            float top = 186.0F;
            ArmorUi.panel(graphics, left, top, left + width, top + height, stroke);

            String table = tableLabel(profile);
            ArmorUi.textRight(graphics, table, left + width - 8.0F, top + 6.0F, ArmorUi.DIM);
            int nameWidth = (int) (width - 16.0F - ArmorUi.font().width(table) - 6.0F);
            ArmorUi.text(graphics, ArmorUi.truncate(target.getDisplayName().getString(), nameWidth),
                    left + 8.0F, top + 6.0F, ArmorUi.DIM);
            ArmorUi.rule(graphics, left + 8.0F, left + width - 8.0F, top + 18.0F, stroke);

            ArmorUi.text(graphics, zoneLabel(zone), left + 8.0F, top + 23.0F, ArmorUi.TEXT);

            String thickness = ArmorUi.trim(zone.thickness);
            ArmorUi.textScaled(graphics, thickness, left + 8.0F, top + 34.0F, 1.7F,
                    ArmorUi.TEXT);
            float numberWidth = ArmorUi.font().width(thickness) * 1.7F;
            ArmorUi.textScaled(graphics,
                    Component.translatable("gui.ywzj_vehicle_armor.ammo_compendium.mm").getString(),
                    left + 10.0F + numberWidth, top + 42.0F, 0.8F, ArmorUi.MUTED);
            ArmorUi.textRight(graphics, String.format(java.util.Locale.ROOT, "×%.1f",
                    zone.multiplier), left + width - 8.0F, top + 40.0F, ArmorUi.TEXT);

            float detailY = top + 62.0F;
            if (zone.addonHp > 0.0F) {
                // The add-on layer sits in front of everything else on the flank, so it is named
                // first. The hit count is not shown: it is the damage model's memory, not a
                // property of the plate, and the scanner has no business reading it.
                ArmorUi.textScaled(graphics, Component.translatable(
                        "gui.ywzj_vehicle_armor.zone.addon_hp", ArmorUi.trim(zone.addonHp),
                        zone.addonFractions == null ? 0 : zone.addonFractions.length).getString(),
                        left + 8.0F, detailY, 0.75F, ArmorUi.WARN);
            } else if (zone.eraHp > 0.0F) {
                ArmorUi.textScaled(graphics, Component.translatable(
                        "gui.ywzj_vehicle_armor.zone.era_hp", ArmorUi.trim(zone.eraHp)).getString(),
                        left + 8.0F, detailY, 0.75F, ArmorUi.WARN);
            } else if (zone.loss > 0.0F) {
                ArmorUi.textScaled(graphics, Component.translatable(
                        "gui.ywzj_vehicle_armor.zone.loss", ArmorUi.trim(zone.loss)).getString(),
                        left + 8.0F, detailY, 0.75F, ArmorUi.WARN);
            } else {
                ArmorUi.textScaled(graphics,
                        Component.translatable("gui.ywzj_vehicle_armor.zone.equivalent").getString(),
                        left + 8.0F, detailY, 0.75F, ArmorUi.DIM);
            }

            ArmorUi.rule(graphics, left + 8.0F, left + width - 8.0F, top + height - 18.0F,
                    stroke);
            ArmorUi.textScaled(graphics,
                    Component.translatable("gui.ywzj_vehicle_armor.scanner.hint").getString(),
                    left + 8.0F, top + height - 13.0F, 0.7F, ArmorUi.DIM);
            ArmorUi.textRight(graphics, page + " / " + pages, left + width - 8.0F,
                    top + height - 13.0F, ArmorUi.DIM);
        }
    }

    /**
     * The zone's name for a player, and the face where the zone itself does not carry one.
     *
     * <p>{@link ArmorSystem.Zone#name} is the config's own vocabulary - "hull upper front",
     * "road wheels", and a part id when the hit landed on one. The first two are fixed phrases and
     * get translated; a part id is a pack's name for its own armour and is left alone rather than
     * being guessed at.
     */
    private static String zoneLabel(ArmorSystem.Zone zone) {
        String base = switch (zone.name) {
            case "turret", "hull" -> {
                String body = Component.translatable(
                        "gui.ywzj_vehicle_armor.zone." + zone.name).getString();
                String face = faceName(zone.face);
                yield face.isEmpty() ? body : body + face;
            }
            case "turret gun mount" -> name("gui.ywzj_vehicle_armor.zone.gun_mount");
            case "track front" -> name("gui.ywzj_vehicle_armor.zone.track_front");
            case "track rear" -> name("gui.ywzj_vehicle_armor.zone.track_rear");
            case "road wheels" -> name("gui.ywzj_vehicle_armor.zone.road_wheels");
            case "hull side (reactive)" -> name("gui.ywzj_vehicle_armor.zone.reactive_side");
            case "hull machine-gun port" -> name("gui.ywzj_vehicle_armor.zone.mg_port");
            case "hull upper front" -> name("gui.ywzj_vehicle_armor.zone.upper_front");
            case "hull lower front" -> name("gui.ywzj_vehicle_armor.zone.lower_front");
            case "hull upper front (ricochet)" ->
                    name("gui.ywzj_vehicle_armor.zone.upper_front_ricochet");
            case "hull lower front (belt)" ->
                    name("gui.ywzj_vehicle_armor.zone.lower_front_belt");
            default -> {
                // A part id is a pack's own name for a box bolted on the vehicle. Translate it when
                // a key exists and show the id when it does not - inventing a name would be worse
                // than the honest identifier, and the key lookup returns the key itself on a miss.
                String key = "gui.ywzj_vehicle_armor.zone.part." + zone.name;
                String translated = Component.translatable(key).getString();
                yield translated.equals(key) ? zone.name : translated;
            }
        };
        return base;
    }

    /**
     * @return the face's name, or "" for the axes that have no single face.
     *
     * <p>{@code OBB.embeddingFace} is signed: +/-1 is the vehicle's x axis (its flanks), +/-2 its y,
     * +/-3 its z. +z is the nose, so +3 is the front and -3 the rear.
     */
    private static String faceName(int face) {
        return switch (face) {
            case 3 -> name("gui.ywzj_vehicle_armor.face.front");
            case -3 -> name("gui.ywzj_vehicle_armor.face.rear");
            case 1 -> name("gui.ywzj_vehicle_armor.face.right");
            case -1 -> name("gui.ywzj_vehicle_armor.face.left");
            case 2 -> name("gui.ywzj_vehicle_armor.face.roof");
            case -2 -> name("gui.ywzj_vehicle_armor.face.floor");
            default -> "";
        };
    }

    private static String name(String key) {
        return Component.translatable(key).getString();
    }
}
