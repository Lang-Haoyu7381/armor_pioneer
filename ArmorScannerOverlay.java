package com.ywzjvehicle.armor;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
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
        Minecraft minecraft = Minecraft.m_91087_();
        if (minecraft.f_91074_ == null || minecraft.f_91080_ != null) {
            return;
        }
        if (minecraft.f_91074_.m_21205_().m_41720_() != VehicleArmorItems.ARMOR_SCANNER.get()) {
            return;
        }
        if (event.getKey() == keyCode("key.keyboard.up")) {
            view--;
        } else if (event.getKey() == keyCode("key.keyboard.down")) {
            view++;
        }
    }

    /**
     * @return the GLFW key code Mojang has registered under the given name, or {@code -1} if it is
     *         unknown - which never matches a real key, so paging simply does nothing.
     */
    private static int keyCode(String name) {
        InputConstants.Key key = InputConstants.m_84851_(name);
        return key == null ? -1 : key.m_84873_();
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!ArmorConfig.scannerShowsArmor()) {
            return;
        }
        Minecraft minecraft = Minecraft.m_91087_();
        LocalPlayer player = minecraft.f_91074_;
        if (player == null || minecraft.f_91073_ == null) {
            return;
        }
        if (player.m_21205_().m_41720_() != VehicleArmorItems.ARMOR_SCANNER.get()) {
            return;
        }

        Vec3 from = new Vec3(player.m_20185_(), player.m_20186_() + player.m_20192_(), player.m_20189_());
        Vec3 look = VectorUtil.rotToVec(player.m_146909_(), player.m_146908_());
        Vec3 to = from.m_82549_(look.m_82490_(RANGE));
        AABB area = player.m_20191_().m_82400_(RANGE);

        AbstractVehicle target = null;
        OBB targetObb = null;
        Vec3 targetPos = null;
        double bestDistance = Double.MAX_VALUE;

        for (Entity entity : minecraft.f_91073_.m_6249_(player, area, candidate -> candidate instanceof AbstractVehicle)) {
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

        draw(event.getGuiGraphics(), minecraft,
                String.format("%s  %.0fmm  x%.1f", zone.name, zone.thickness, zone.multiplier),
                tableLabel(profile) + "   " + (selected + 1) + "/" + total + "   Up/Down");
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
                : Component.m_237115_(warhead.translationKey()).getString();
    }

    private static void draw(GuiGraphics graphics, Minecraft minecraft, String readout, String footer) {
        Font font = minecraft.f_91062_;
        int widest = Math.max(font.m_92895_(readout), font.m_92895_(footer));
        int x = minecraft.m_91268_().m_85445_() / 2 + 14;
        int y = minecraft.m_91268_().m_85446_() / 2 + 6;
        int height = 11 + font.f_92710_;
        graphics.m_280509_(x - 4, y - 3, x + widest + 4, y - 3 + height + 2, 0xB0000000);
        graphics.m_280056_(font, readout, x, y, 0xFFFFFF, true);
        // Dimmer than the reading itself: which table is on screen matters, but the number is what
        // the scanner is for.
        graphics.m_280056_(font, footer, x, y + font.f_92710_, 0xAAAAAA, true);
    }
}
