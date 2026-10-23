package com.ywzjvehicle.armor;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * The choice of load for the round in the gun: every mark the slot can be filled with, and which
 * one is in it now.
 *
 * <p>Laid out like the ammunition compendium's pages on purpose - same panel, same green - because
 * it answers the same kind of question at a smaller scale, and a second, differently-shaped window
 * for "what can this gun fire" would read as a different feature.
 *
 * <p>The wheel moves the mark and a left click confirms it and closes the page. The mark always
 * shows what a click would load, so there is never a question of which one is about to be taken:
 * the mark opens on what the gun already has, and the only thing that moves it is the wheel or the
 * mouse, whichever the player touched last.
 */
public class LoadChooserScreen extends Screen {

    private static final int PANEL_WIDTH = 250;
    private static final int ROW_HEIGHT = 12;
    private static final int MIN_ROW_HEIGHT = 7;
    private static final int PADDING = 8;
    private static final int TITLE_GAP = 16;
    private static final int HINT_GAP = 12;

    private static final int BACKGROUND = 0xC0101010;
    private static final int PANEL = 0xFF1C2B1C;
    private static final int BORDER = 0xFF4C7A4C;
    private static final int TITLE = 0xFFE8F5E8;
    private static final int HEADING = 0xFF9DC79D;
    private static final int NAME = 0xFFE0E0E0;
    private static final int VALUE = 0xFFB6E3B6;
    private static final int DIM = 0xFF7A8F7A;
    private static final int HOVER = 0x30FFFFFF;
    private static final int CHOSEN = 0xFF6FCF6F;

    private final ArmorConfig.Ammo round;

    /** The name the vehicle pack gives the round in the gun, which is the load at index zero. */
    private final String ownName;

    private final List<String> names;

    /**
     * The load a click would take. Starts on whatever the gun already has, so opening the page and
     * clicking straight away is the way to say "no change".
     */
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

    /** @return the row the given screen y falls on, or {@code -1} when it falls on none. */
    private int rowAt(double mouseY) {
        int[] layout = layout();
        if (mouseY < layout[0]) {
            return -1;
        }
        int index = (int) ((mouseY - layout[0]) / layout[1]);
        return index >= 0 && index < names.size() ? index : -1;
    }

    private int panelTop() {
        return 20;
    }

    private int panelBottom() {
        return f_96544_ - 20;
    }

    private int panelLeft() {
        return (f_96543_ - PANEL_WIDTH) / 2;
    }

    /** Where the rows start and how tall they are, worked out alike for drawing and for clicking. */
    private int[] layout() {
        int first = panelTop() + PADDING + TITLE_GAP + HINT_GAP;
        int room = panelBottom() - PADDING - first;
        int height = Math.max(MIN_ROW_HEIGHT, Math.min(ROW_HEIGHT, room / Math.max(1, names.size())));
        return new int[]{first, height};
    }

    @Override
    public void m_88315_(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int left = panelLeft();
        int right = left + PANEL_WIDTH;
        int top = panelTop();
        int bottom = panelBottom();

        graphics.m_280509_(0, 0, f_96543_, f_96544_, BACKGROUND);
        graphics.m_280509_(left, top, right, bottom, PANEL);
        graphics.m_280509_(left, top, right, top + 1, BORDER);
        graphics.m_280509_(left, bottom - 1, right, bottom, BORDER);
        graphics.m_280509_(left, top, left + 1, bottom, BORDER);
        graphics.m_280509_(right - 1, top, right, bottom, BORDER);

        String title = Component.m_237110_("gui.ywzj_vehicle_armor.load_chooser.title", ownName).getString();
        graphics.m_280056_(f_96547_, title, (f_96543_ - f_96547_.m_92895_(title)) / 2,
                top + PADDING, TITLE, true);

        // The controls are spelled out rather than left to be discovered: a wheel is not the first
        // thing anyone reaches for in a list, and a click that closes the page is worth warning
        // about before it happens rather than after.
        String hint = Component.m_237115_("gui.ywzj_vehicle_armor.load_chooser.hint").getString();
        graphics.m_280056_(f_96547_, hint, (f_96543_ - f_96547_.m_92895_(hint)) / 2,
                top + PADDING + 12, DIM, false);

        String heading = Component.m_237115_("gui.ywzj_vehicle_armor.ammo_compendium.name").getString();
        String headingRight =
                Component.m_237115_("gui.ywzj_vehicle_armor.ammo_compendium.penetration").getString();
        int headingY = top + PADDING + TITLE_GAP;
        graphics.m_280056_(f_96547_, heading, left + PADDING, headingY, HEADING, false);
        graphics.m_280056_(f_96547_, headingRight,
                right - PADDING - f_96547_.m_92895_(headingRight), headingY, HEADING, false);

        // The mouse moves the mark too, but only when it is actually moved: hovering sets it every
        // frame otherwise, and a wheel that scrolls the list while the cursor sits still over one
        // row would have the mark snap straight back to that row.
        if (mouseX != lastMouseX || mouseY != lastMouseY) {
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            int hovered = rowAt(mouseY);
            if (hovered >= 0 && mouseX >= left + 1 && mouseX < right - 1) {
                cursor = hovered;
            }
        }

        int[] layout = layout();
        int y = layout[0];
        for (int i = 0; i < names.size(); i++) {
            boolean marked = i == cursor;
            if (marked) {
                graphics.m_280509_(left + 1, y - 1, right - 1, y + layout[1] - 2, HOVER);
            }
            // The mark is a character rather than a colour alone, so the choice survives being
            // looked at by someone who cannot tell the two greens apart.
            String label = (marked ? "▸ " : "  ") + names.get(i);
            graphics.m_280056_(f_96547_, label, left + PADDING, y, marked ? CHOSEN : NAME, false);
            String value = trim(round.variant(i).penetration)
                    + Component.m_237115_("gui.ywzj_vehicle_armor.ammo_compendium.mm").getString();
            graphics.m_280056_(f_96547_, value,
                    right - PADDING - f_96547_.m_92895_(value), y, marked ? CHOSEN : VALUE, false);
            y += layout[1];
        }
    }

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
            // The mark has moved, so the mouse is no longer what set it; forgetting where the mouse
            // was stops the next frame from treating an unmoved mouse as a fresh hover.
            lastMouseX = Double.NaN;
            lastMouseY = Double.NaN;
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

    private static String trim(float value) {
        return value == Math.round(value)
                ? String.valueOf(Math.round(value))
                : String.format(Locale.ROOT, "%.1f", value);
    }
}
