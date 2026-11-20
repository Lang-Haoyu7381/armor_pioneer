package com.ywzjvehicle.armor;

import com.mojang.blaze3d.platform.InputConstants;
import com.ywzjvehicle.armor.client.ArmorUi;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.ywzj.vehicle.client.gui.VehicleHudLayout;

import java.util.List;

/**
 * The choice of load for the round in the gun: every mark the slot can be filled with, and which
 * one is in it now.
 *
 * <p>Laid out with the same panel and the same type as the ammunition compendium, because it
 * answers the same kind of question at a smaller scale - a second, differently-shaped window for
 * "what can this gun fire" would read as a different feature.
 *
 * <p>A row now carries a bar as well as a number, scaled against the hardest-hitting load in the
 * list. The mark shows what a click would load; {@code 装填} marks what is actually in the breech,
 * which is a different thing for the few seconds the gun spends re-feeding after a choice. The two
 * are drawn apart on purpose: collapsing them would have the page claim the new round is in the gun
 * the moment it is asked for.
 */
public class LoadChooserScreen extends Screen {

    private static final float VIRTUAL_HEIGHT = 480.0F;
    private static final float PADDING = 12.0F;
    private static final float HEADER = 40.0F;
    private static final float FOOTER = 22.0F;

    private static final float ROW = 20.0F;
    private static final float MIN_ROW = 13.0F;
    private static final float VALUE_WIDTH = 58.0F;
    private static final float BAR_HEIGHT = 2.0F;

    private final ArmorConfig.Ammo round;

    /** The name the vehicle pack gives the round in the gun, which is the load at index zero. */
    private final String ownName;

    private final List<String> names;

    /** What a click would take. Starts on whatever the gun already has. */
    private int cursor;

    /** Where the mouse was last frame, so that hovering only moves the mark when it is moved. */
    private double lastMouseX = Double.NaN;
    private double lastMouseY = Double.NaN;

    public LoadChooserScreen(ArmorConfig.Ammo round, String ownName) {
        super(Component.m_237115_("gui.ywzj_vehicle_armor.load_chooser"));
        this.round = round;
        this.ownName = ownName;
        this.names = round.variantNames(ownName);
        this.cursor = Math.max(0, Math.min(GunnerSettings.client().variant, names.size() - 1));
    }

    // --- geometry -----------------------------------------------------------------------------

    private float scale() {
        return f_96544_ / VIRTUAL_HEIGHT;
    }

    private float viewWidth() {
        return f_96543_ / scale();
    }

    private float panelWidth() {
        return ArmorUi.clamp(viewWidth() - 32.0F, 260.0F, 360.0F);
    }

    private float left() {
        return (viewWidth() - panelWidth()) / 2.0F;
    }

    private float right() {
        return left() + panelWidth();
    }

    private float rowHeight() {
        float available = VIRTUAL_HEIGHT - 96.0F - HEADER - FOOTER;
        return Math.max(MIN_ROW, Math.min(ROW, available / Math.max(1, names.size())));
    }

    private float panelHeight() {
        return HEADER + names.size() * rowHeight() + FOOTER;
    }

    private float top() {
        return (VIRTUAL_HEIGHT - panelHeight()) / 2.0F;
    }

    private float rowsTop() {
        return top() + HEADER;
    }

    private float rowsBottom() {
        return rowsTop() + names.size() * rowHeight();
    }

    private int rowAt(double screenY) {
        float y = (float) screenY / scale();
        if (y < rowsTop() || y >= rowsBottom()) {
            return -1;
        }
        int index = (int) ((y - rowsTop()) / rowHeight());
        return Math.max(0, Math.min(index, names.size() - 1));
    }

    // --- drawing ------------------------------------------------------------------------------

    @Override
    public void m_88315_(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        float scale = scale();
        float stroke = ArmorUi.stroke(scale);
        float left = left();
        float right = right();
        float top = top();
        float bottom = top + panelHeight();

        // The mouse moves the mark too, but only when it is actually moved: hovering sets it every
        // frame otherwise, and a wheel that scrolls the list while the cursor sits still over one
        // row would have the mark snap straight back to that row.
        if (mouseX != lastMouseX || mouseY != lastMouseY) {
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            int hovered = rowAt(mouseY);
            if (hovered >= 0) {
                cursor = hovered;
            }
        }

        try (VehicleHudLayout layout = VehicleHudLayout.begin(graphics)) {
            ArmorUi.fill(graphics, 0, 0, viewWidth(), VIRTUAL_HEIGHT, 0xC0101010);
            ArmorUi.panel(graphics, left, top, right, bottom, stroke);

            ArmorUi.text(graphics, f_96539_.getString(), left + PADDING, top + 8.0F,
                    ArmorUi.TEXT);
            ArmorUi.textRight(graphics, ownName, right - PADDING, top + 8.0F, ArmorUi.DIM);
            String hint = Component.m_237115_("gui.ywzj_vehicle_armor.load_chooser.hint")
                    .getString();
            ArmorUi.textScaled(graphics, hint, left + PADDING, top + 24.0F, 0.75F, ArmorUi.MUTED);
            ArmorUi.rule(graphics, left + PADDING, right - PADDING, top + HEADER - 6.0F, stroke);

            float heaviest = 1.0F;
            for (int i = 0; i < names.size(); i++) {
                heaviest = Math.max(heaviest, Math.abs(round.variant(i).penetration));
            }

            int loaded = Math.max(0, Math.min(GunnerSettings.client().variant, names.size() - 1));
            float height = rowHeight();
            float y = rowsTop();
            for (int i = 0; i < names.size(); i++) {
                boolean marked = i == cursor;
                if (marked) {
                    ArmorUi.fill(graphics, left + stroke, y, right - stroke, y + height,
                            ArmorUi.SELECTED);
                } else if (i == loaded) {
                    ArmorUi.fill(graphics, left + stroke, y, right - stroke, y + height,
                            ArmorUi.HOVER);
                }
                int colour = marked ? ArmorUi.ON_SELECTED
                        : (i == loaded ? ArmorUi.TEXT : ArmorUi.MUTED);

                // The mark is a character rather than a colour alone, so the choice survives being
                // looked at by someone who cannot tell two shades apart.
                String label = (marked ? "▸ " : "  ") + names.get(i);
                if (i == loaded) {
                    label += "  " + Component.m_237115_(
                            "gui.ywzj_vehicle_armor.load_chooser.loaded").getString();
                }
                float textY = y + Math.round((height - 9.0F) / 2.0F);
                ArmorUi.text(graphics, ArmorUi.truncate(label, (int) (panelWidth()
                                - PADDING * 2 - VALUE_WIDTH - 8)), left + PADDING, textY, colour);

                String value = ArmorUi.trim(round.variant(i).penetration)
                        + Component.m_237115_("gui.ywzj_vehicle_armor.ammo_compendium.mm")
                        .getString();
                ArmorUi.textRight(graphics, value, right - PADDING, textY, colour);
                ArmorUi.bar(graphics, right - PADDING - VALUE_WIDTH + 4.0F,
                        y + height - BAR_HEIGHT - 2.0F, VALUE_WIDTH - 8.0F, BAR_HEIGHT,
                        Math.abs(round.variant(i).penetration) / heaviest,
                        marked ? ArmorUi.ON_SELECTED : (i == loaded ? ArmorUi.TEXT : ArmorUi.DIM));
                y += height;
            }

            ArmorUi.rule(graphics, left + PADDING, right - PADDING, rowsBottom() + 6.0F, stroke);
            ArmorUi.textScaled(graphics,
                    Component.m_237115_("gui.ywzj_vehicle_armor.load_chooser.footer").getString(),
                    left + PADDING, rowsBottom() + 12.0F, 0.75F, ArmorUi.DIM);
        }
    }

    // --- input --------------------------------------------------------------------------------

    /**
     * The wheel walks the mark along the list.
     *
     * <p>Clamped rather than wrapped: with a handful of loads, running off the end of a list that
     * is drawn in full should mean the end, not a jump back to the start that looks like a
     * mis-scroll.
     */
    @Override
    public boolean m_6050_(double mouseX, double mouseY, double scrollDelta) {
        if (scrollDelta != 0.0) {
            cursor = Math.max(0, Math.min(names.size() - 1,
                    cursor - (int) Math.signum(scrollDelta)));
            // Remember where the mouse was during the wheel, rather than forgetting it. The next
            // frame compares against this, sees no movement, and leaves the wheel's choice alone;
            // forgetting it made the comparison always true, so the row under a stationary cursor
            // snapped the mark straight back and the wheel looked like it did nothing.
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return true;
        }
        return super.m_6050_(mouseX, mouseY, scrollDelta);
    }

    /**
     * Confirms the marked load and closes.
     *
     * <p>What is taken is the mark, not the row under the pointer - the two are the same whenever
     * the pointer is over a row, because hovering moves the mark, and when the pointer is off the
     * list altogether the mark is still plainly the thing on screen. A click anywhere on the page
     * therefore means the same thing, and the page closes on it.
     */
    @Override
    public boolean m_6375_(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.m_6375_(mouseX, mouseY, button);
        }
        WeaponKeyHandler.chooseLoad(cursor);
        m_7379_();
        return true;
    }

    @Override
    public boolean m_7933_(int keyCode, int scanCode, int modifiers) {
        // Left Alt opened this, so left Alt closes it - the key that got you in is the key that
        // gets you out. A second Escape does the same, by falling through to vanilla.
        if (keyCode == altKey()) {
            m_7379_();
            return true;
        }
        return super.m_7933_(keyCode, scanCode, modifiers);
    }

    /** @return the GLFW code Mojang has registered for left Alt, or {@code -1} if unknown. */
    private static int altKey() {
        InputConstants.Key key = InputConstants.m_84851_("key.keyboard.left.alt");
        return key == null ? -1 : key.m_84873_();
    }
}
