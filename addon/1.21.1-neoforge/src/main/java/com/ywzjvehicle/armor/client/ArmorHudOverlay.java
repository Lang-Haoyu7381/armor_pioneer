package com.ywzjvehicle.armor.client;

import com.ywzjvehicle.armor.ArmorConfig;
import com.ywzjvehicle.armor.ArmorSystem;
import com.ywzjvehicle.armor.CrewClientState;
import com.ywzjvehicle.armor.CrewRole;
import com.ywzjvehicle.armor.CrewRoles;
import com.ywzjvehicle.armor.Fording;
import com.ywzjvehicle.armor.Fuze;
import com.ywzjvehicle.armor.GunnerSettings;
import com.ywzjvehicle.armor.Hybrid;
import com.ywzjvehicle.armor.OperatedRound;
import com.ywzjvehicle.armor.Suspension;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.ywzj.vehicle.client.gui.VehicleHudLayout;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VehicleHudText;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.SwitchableUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The addon's persistent in-vehicle strip, in base mod 0.6.2's HUD language.
 *
 * <p>Before this, everything the addon knows about a vehicle's state was said once in the action
 * bar and then faded: the selected fuze, the load in the breech, the suspension pose, whether the
 * fording kit was up. That is the wrong medium for state - a crew member who has just climbed into a
 * seat has to press a key to find out what is set, and a readout that has already faded is
 * indistinguishable from one that never appeared. This panel answers the same questions all the
 * time, in the top-left corner where the base HUD leaves room.
 *
 * <p>Every line is optional and disappears when it does not apply: a plain armoured car gets a
 * station and a round, a T-64 with a suspension and a snorkel gets five rows. Nothing is shown for a
 * vehicle the addon does not configure, and nothing is shown on foot.
 *
 * <p><b>What is measured and what is remembered.</b> The round, the fuze and the role are read from
 * the vehicle and the settings every frame - they cannot go stale. The suspension pose is the last
 * pose the server reported, because the pose itself is not synced and recomputing it client-side
 * would be a second opinion that disagrees exactly when it matters; before the first press the row
 * says the mechanism is ready rather than inventing a height. The water depth is measured here,
 * with the same {@link Fording#probe} the server uses, because the sea does not need the server's
 * permission and a depth readout that only updated on a keypress would be useless while crossing.
 */
public final class ArmorHudOverlay implements LayeredDraw.Layer {

    private static final float MARGIN = 18.0F;
    private static final float TOP = 18.0F;
    private static final float WIDTH = 196.0F;
    private static final float ROW = 12.0F;
    private static final float PADDING = 7.0F;
    private static final float LABEL_WIDTH = 42.0F;

    /** Water is re-probed every few ticks rather than every frame; it walks blocks. */
    private static final int PROBE_INTERVAL = 5;

    private static int probeVehicle = -1;
    private static int probeTick = Integer.MIN_VALUE;
    private static Fording.Probe probe = Fording.Probe.DRY;

    /** One line: a label in the dim column and a value in the bright one. */
    private record Row(String label, String value, int colour) {
    }

    /**
     * NeoForge 1.21's layer signature: the layer gets the graphics and a delta tracker, not the
     * Forge GUI object and a pre-measured screen size. The size comes off the graphics it was
     * handed, which is the same window the old arguments described.
     */
    @Override
    public void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        int screenWidth = graphics.guiWidth();
        int screenHeight = graphics.guiHeight();
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.screen != null) {
            return;
        }
        // In a scope the screen belongs to the sight; a status strip over the reticle is in the way.
        if (LocalVehiclePlayer.instance.viewType == LocalVehiclePlayer.ViewType.SCOPE) {
            return;
        }
        if (!(player.getVehicle() instanceof AbstractVehicle vehicle)) {
            return;
        }
        // Only for vehicles this addon actually governs. A station line over a helicopter the
        // config has never heard of would be true and useless, and a HUD that appears everywhere is
        // a HUD nobody reads.
        ArmorConfig.VehicleArmor armor = ArmorSystem.armorFor(vehicle);
        if (armor == null && ArmorSystem.suspensionFor(vehicle) == null
                && ArmorSystem.hybridFor(vehicle) == null) {
            return;
        }

        List<Row> rows = buildRows(player, vehicle, armor);
        if (rows.isEmpty()) {
            return;
        }

        float scale = (float) screenHeight / 480.0F;
        float stroke = ArmorUi.stroke(scale);
        float width = Math.min(WIDTH, Math.max(120.0F, (float) screenWidth / scale / 2.0F - 24.0F));
        float height = PADDING * 2.0F + rows.size() * ROW + (rows.size() > 1 ? 3.0F : 0.0F);

        try (VehicleHudLayout layout = VehicleHudLayout.begin(graphics)) {
            float left = MARGIN;
            ArmorUi.panel(graphics, left, TOP, left + width, TOP + height, stroke);

            float y = TOP + PADDING;
            for (int index = 0; index < rows.size(); index++) {
                Row row = rows.get(index);
                ArmorUi.text(graphics, row.label(), left + PADDING, y, ArmorUi.DIM);
                ArmorUi.text(graphics,
                        ArmorUi.truncate(row.value(),
                                (int) (width - PADDING * 2.0F - LABEL_WIDTH)),
                        left + PADDING + LABEL_WIDTH, y, row.colour());
                y += ROW;
                if (index == 0 && rows.size() > 1) {
                    ArmorUi.rule(graphics, left + PADDING, left + width - PADDING, y + 1.0F, stroke);
                    y += 3.0F;
                }
            }
        }
    }

    private static List<Row> buildRows(LocalPlayer player, AbstractVehicle vehicle,
                                       ArmorConfig.VehicleArmor armor) {
        List<Row> rows = new ArrayList<>();

        // --- station ------------------------------------------------------------------------
        CrewRole role = CrewRoles.roleOf(vehicle, player);
        String station = Component.translatable(role.key()).getString();
        boolean wounded = CrewClientState.isIncapacitated();
        rows.add(new Row(label("station"), wounded
                ? station + " · " + label("wounded") : station,
                wounded ? ArmorUi.WARN : ArmorUi.TEXT));

        // The periscope is a view, not equipment, so it gets one line rather than a control of its
        // own: the camera itself is the evidence. It disappears the moment the base view does.
        if (PeriscopeView.active()) {
            rows.add(new Row(label("view"), label("periscope"), ArmorUi.TEXT));
        }

        // --- engine -------------------------------------------------------------------------
        // Two facts a crew uses before a fight and neither of which the base HUD states: how much
        // engine the vehicle has, and how much of it there is per tonne. The ratio is the one that
        // says whether a tank can climb, so it is shown beside the rating rather than left to be
        // worked out from two numbers on the armour table. A published ratio wins; only where the
        // config has none is it derived from the mass the game actually moves.
        if (armor != null && armor.engine_horsepower > 0.0F) {
            float horsepowerPerTonne = armor.horsepower_per_ton > 0.0F
                    ? armor.horsepower_per_ton
                    : armor.engine_horsepower / Math.max(0.001F, vehicle.curbWeight / 1000.0F);
            rows.add(new Row(label("engine"), String.format(Locale.ROOT,
                    "%.0f hp · %.1f hp/t", armor.engine_horsepower, horsepowerPerTonne),
                    ArmorUi.TEXT));
            // The physical half of the same two figures: what the movement solver actually
            // multiplies the pack's acceleration and turn rate by. Shown so the effect is not an
            // invisible handling change - the crew can see the vehicle is pulling harder. The
            // electric factor is composed in here because the solver composes it, so the row is
            // the number in force rather than the diesel's number.
            float factor = ArmorSystem.enginePowerScale(vehicle)
                    * HybridClientState.powerFactor(vehicle);
            rows.add(new Row(label("power"), String.format(Locale.ROOT, "×%.2f", factor),
                    ArmorUi.TEXT));
        }

        // --- the hybrid drive ---------------------------------------------------------------
        // Two facts on one line, because they are one decision: which source is pulling the tank,
        // and how much charge is left. The mode is the server's answer, never a local latch; before
        // the first state message the row says the mechanism is ready rather than inventing a
        // charge.
        Hybrid.Settings hybrid = ArmorSystem.hybridFor(vehicle);
        if (hybrid != null) {
            HybridClientState.View view = HybridClientState.view(vehicle);
            if (view == null) {
                rows.add(new Row(label("hybrid"), label("ready"), ArmorUi.MUTED));
            } else {
                int percent = Math.round(view.batteryFraction() * 100.0F);
                String mode = view.active() ? label("electric")
                        : view.batteryFraction() < 0.999F && vehicle.isEngineOn()
                                ? label("charging")
                                : label("hybrid_mode");
                int colour = percent < view.minStartPercent() ? ArmorUi.WARN : ArmorUi.TEXT;
                rows.add(new Row(label("hybrid"),
                        String.format(Locale.ROOT, "%s %d%%", mode, percent), colour));
            }
        }

        // --- the gun ------------------------------------------------------------------------
        ArmorConfig.Ammo round = OperatedRound.operatingRound(player);
        AbstractVehicleWeapon<?> weapon = OperatedRound.weaponOf(player);
        String ownName = weapon == null || weapon.getData() == null ? null
                : weapon.getData().getName();
        if (round != null) {
            String name = ownName == null || ownName.isBlank() ? round.name : ownName;
            if (round.selectable()) {
                List<String> loads = round.variantNames(name);
                int variant = Math.max(0, Math.min(GunnerSettings.client().variant,
                        loads.size() - 1));
                name = loads.get(variant);
            }
            rows.add(new Row(label("ammo"), name == null ? "?" : name, ArmorUi.TEXT));

            if (round.proximity != null) {
                Fuze fuze = GunnerSettings.client().fuze;
                String value = Component.translatable(fuze.translationKey()).getString();
                if (fuze == Fuze.DELAY) {
                    value += " " + String.format(Locale.ROOT, "%.1fs",
                            GunnerSettings.client().delayTicks / 20.0F);
                }
                rows.add(new Row(label("fuze"), value, ArmorUi.TEXT));
            }
        }

        // --- suspension ---------------------------------------------------------------------
        Suspension.Settings suspension = ArmorSystem.suspensionFor(vehicle);
        if (suspension != null) {
            boolean reported = GunnerSettings.suspensionReported()
                    && GunnerSettings.suspensionPresent();
            String value;
            int colour;
            if (reported) {
                value = String.format(Locale.ROOT, "%.2f / %+.1f°",
                        GunnerSettings.suspensionRise(), GunnerSettings.suspensionPitch());
                colour = ArmorUi.TEXT;
            } else {
                value = label("ready");
                colour = ArmorUi.MUTED;
            }
            rows.add(new Row(label("suspension"), value, colour));
            // A third row only where there is a third axis. On every torsion-bar vehicle the
            // settings say zero, and a permanent "0.0°" would read as a lean that is not working.
            if (suspension.max_roll > 0.0F) {
                String lean = reported
                        ? String.format(Locale.ROOT, "%+.1f°", GunnerSettings.suspensionRoll())
                        : label("ready");
                rows.add(new Row(label("roll"), lean, reported ? ArmorUi.TEXT : ArmorUi.MUTED));
            }
        }

        // --- fording ------------------------------------------------------------------------
        Fording.Kit kit = armor == null ? null : armor.fording;
        if (kit != null) {
            SwitchableUnit<?> snorkel = Fording.snorkelOf(vehicle);
            if (snorkel != null) {
                boolean deployed = snorkel.isOn();
                if (!deployed) {
                    rows.add(new Row(label("fording"), label("stowed"), ArmorUi.MUTED));
                } else {
                    Fording.Probe water = probe(vehicle, kit);
                    if (water.water() && water.inWater()) {
                        boolean deep = water.depth() > kit.max_depth;
                        String value = String.format(Locale.ROOT, "%s / %s %s",
                                ArmorUi.trim(water.depth()), ArmorUi.trim(kit.max_depth),
                                label("blocks"));
                        rows.add(new Row(label("fording"), value,
                                deep ? ArmorUi.WARN : ArmorUi.TEXT));
                    } else if (water.inWater()) {
                        rows.add(new Row(label("fording"), label("deployed"),
                                ArmorUi.WARN));
                    } else {
                        rows.add(new Row(label("fording"), label("deployed"), ArmorUi.TEXT));
                    }
                }
            } else {
                // The kit works without the model; the key is what says whether it is up.
                rows.add(new Row(label("fording"), label("no_model"), ArmorUi.MUTED));
            }
        }

        // --- the commander's order ----------------------------------------------------------
        if (CrewClientState.isSolo(vehicle)) {
            // A mode that outlives the commander's seat has to be visible to whoever sits down
            // next, not just to the person who pressed the key.
            rows.add(new Row(label("order"), label("solo"), ArmorUi.TEXT));
        } else if (CrewClientState.isSlaved(vehicle)) {
            rows.add(new Row(label("order"), label("override"), ArmorUi.TEXT));
        } else if (CrewClientState.designated() != 0) {
            rows.add(new Row(label("order"), label("designated"), ArmorUi.TEXT));
        }

        return rows;
    }

    /**
     * The water at this vehicle, measured at most every {@link #PROBE_INTERVAL} ticks.
     *
     * <p>The probe walks the water column and asks the level for collision boxes; doing that every
     * frame for a persistent line would be a per-frame cost for a number that changes by centimetres.
     */
    private static Fording.Probe probe(AbstractVehicle vehicle, Fording.Kit kit) {
        int id = vehicle.getId();
        if (id != probeVehicle) {
            probeVehicle = id;
            probeTick = Integer.MIN_VALUE;
            probe = Fording.Probe.DRY;
        }
        int tick = vehicle.tickCount;
        if (tick != probeTick && tick % PROBE_INTERVAL == 0) {
            probeTick = tick;
            probe = Fording.probe(vehicle, kit);
        }
        return probe;
    }

    private static String label(String key) {
        return Component.translatable("hud.ywzj_vehicle_armor." + key).getString();
    }
}
