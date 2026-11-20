package com.ywzjvehicle.armor;

import com.mojang.blaze3d.platform.InputConstants;
import com.ywzjvehicle.armor.client.ArmorUi;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.ywzj.vehicle.client.gui.VehicleHudLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The compendium's two pages: an index of the three warheads, and the rounds of whichever one was
 * clicked.
 *
 * <p>Restyled for base mod 0.6.2, and rebuilt around its 480-unit HUD layout rather than a fixed
 * 250-pixel panel. On a large screen the old book was a small rectangle in the middle of a lot of
 * nothing; this one grows with the window up to a readable width and keeps a fixed height, so the
 * column headings, the values and the page footer stay where they were on the page before.
 *
 * <p>Two levels rather than one flat list because the rounds are not comparable across the
 * warheads - a 60mm high-explosive shell and a 780mm dart have nothing to say to each other, and a
 * single list sorted by penetration puts exactly those two next to each other with no way to tell
 * why.
 *
 * <p>Values now carry a bar as well as a number. The number answers "will it go through this
 * plate", which needs the scanner; the bar answers "which of these hits hardest", which is the
 * question a list is for - and it is drawn on the same scale for every round under the open
 * warhead, so a shaped charge and a dart can be compared without reading two four-digit numbers.
 */
public class AmmoCompendiumScreen extends Screen {

    /** Virtual HUD units, the same 480-tall space the base mod's overlays use. */
    private static final float VIRTUAL_HEIGHT = 480.0F;
    private static final float TOP = 18.0F;
    private static final float BOTTOM = 462.0F;
    private static final float PADDING = 12.0F;
    private static final float HEADER = 28.0F;
    private static final float COLUMN_HEAD = 22.0F;
    private static final float FOOTER = 22.0F;

    private static final float SHELF_ROW = 26.0F;
    private static final float ROW = 14.0F;

    private static final float VALUE_WIDTH = 58.0F;
    private static final float BAR_HEIGHT = 2.0F;

    /** The warhead being read, or {@code null} for the index. */
    private Warhead open;

    /** Which page of the open warhead's rows is showing. Always zero on the index. */
    private int page;

    public AmmoCompendiumScreen() {
        super(Component.m_237115_("gui.ywzj_vehicle_armor.ammo_compendium"));
    }

    /** What a row is, which decides how it is drawn and whether it can be clicked. */
    private enum Style {
        SHELF, BACK, GROUP, ROUND, LOAD
    }

    /** One drawn row, and what clicking it does. */
    private record Row(String label, String detail, String value, float fraction, Warhead openTo,
                       Style style) {
    }

    private List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        if (open == null) {
            for (Warhead warhead : Warhead.values()) {
                int count = AmmoIndex.entriesWith(warhead).size();
                rows.add(new Row(name(warhead.translationKey()),
                        name(warhead.translationKey() + ".hint"),
                        Component.m_237110_("gui.ywzj_vehicle_armor.ammo_compendium.count", count)
                                .getString(),
                        0.0F, warhead, Style.SHELF));
            }
            return rows;
        }

        rows.add(new Row(name("gui.ywzj_vehicle_armor.ammo_compendium.back"), "", "", 0.0F, null,
                Style.BACK));
        List<AmmoIndex.Entry> entries = AmmoIndex.entriesWith(open);
        boolean grouped = AmmoCategory.groupsByWeapon(open, AmmoIndex.shelvesOf(entries));
        float heaviest = 1.0F;
        for (AmmoIndex.Entry entry : entries) {
            heaviest = Math.max(heaviest, entry.penetration());
            for (AmmoIndex.Load load : entry.loads()) {
                heaviest = Math.max(heaviest, load.penetration());
            }
        }
        WeaponClass current = null;
        for (AmmoIndex.Entry entry : entries) {
            if (grouped && entry.category().weapon() != current) {
                current = entry.category().weapon();
                rows.add(new Row(name(current.translationKey()), "", "", 0.0F, null,
                        Style.GROUP));
            }
            rows.add(new Row(entry.name(), "", format(entry),
                    entry.penetration() / heaviest, null, Style.ROUND));
            // The other marks a slot can be loaded with, listed under it rather than as separate
            // rounds: they are not separate rounds, and filing them as such would have the book
            // claim the Abrams carries six kinds of shell.
            for (AmmoIndex.Load load : entry.loads()) {
                rows.add(new Row(load.name(), "", trim(load.penetration())
                        + name("gui.ywzj_vehicle_armor.ammo_compendium.mm"),
                        load.penetration() / heaviest, null, Style.LOAD));
            }
        }
        return rows;
    }

    // --- virtual-space geometry ---------------------------------------------------------------

    private float scale() {
        return f_96544_ / VIRTUAL_HEIGHT;
    }

    private float viewWidth() {
        return f_96543_ / scale();
    }

    private float panelWidth() {
        return ArmorUi.clamp(viewWidth() - 32.0F, 240.0F, 420.0F);
    }

    private float left() {
        return (viewWidth() - panelWidth()) / 2.0F;
    }

    private float right() {
        return left() + panelWidth();
    }

    private float rowsTop() {
        return TOP + PADDING + HEADER + COLUMN_HEAD;
    }

    private float rowHeight() {
        return open == null ? SHELF_ROW : ROW;
    }

    private float rowsBottom() {
        return BOTTOM - FOOTER;
    }

    private int pageSize() {
        return Pages.perPage((int) (rowsBottom() - rowsTop()), (int) rowHeight());
    }

    private List<Row> pageRows() {
        List<Row> all = rows();
        int size = pageSize();
        return all.subList(Pages.first(page, all.size(), size), Pages.end(page, all.size(), size));
    }

    private void turnPage(int direction) {
        page = Pages.turn(page, rows().size(), pageSize(), direction);
    }

    private float toVirtualX(double screenX) {
        return (float) screenX / scale();
    }

    private float toVirtualY(double screenY) {
        return (float) screenY / scale();
    }

    private int rowAt(double screenY) {
        float y = toVirtualY(screenY);
        if (y < rowsTop()) {
            return -1;
        }
        int index = (int) ((y - rowsTop()) / rowHeight());
        return index < pageRows().size() ? index : -1;
    }

    // --- drawing ------------------------------------------------------------------------------

    @Override
    public void m_88315_(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        float scale = scale();
        float stroke = ArmorUi.stroke(scale);
        float left = left();
        float right = right();
        float mx = toVirtualX(mouseX);
        float my = toVirtualY(mouseY);

        try (VehicleHudLayout layout = VehicleHudLayout.begin(graphics)) {
            // The whole window is dimmed first, so the book reads as a page laid over the world
            // rather than as floating text. Drawn inside the layout: the coordinates are virtual
            // units, and outside it they would be GUI pixels.
            ArmorUi.fill(graphics, 0, 0, viewWidth(), VIRTUAL_HEIGHT, 0xC0101010);
            ArmorUi.panel(graphics, left, TOP, right, BOTTOM, stroke);

            String title = open == null ? f_96539_.getString() : name(open.translationKey());
            ArmorUi.text(graphics, title, left + PADDING, TOP + 8.0F, ArmorUi.TEXT);
            String pageMark = open == null
                    ? name("gui.ywzj_vehicle_armor.ammo_compendium.index")
                    : name("gui.ywzj_vehicle_armor.ammo_compendium.rounds");
            ArmorUi.textRight(graphics, pageMark, right - PADDING, TOP + 8.0F, ArmorUi.DIM);
            ArmorUi.rule(graphics, left + PADDING, right - PADDING, TOP + PADDING + HEADER - 6.0F,
                    stroke);

            String heading = name(open == null
                    ? "gui.ywzj_vehicle_armor.ammo_compendium.category"
                    : "gui.ywzj_vehicle_armor.ammo_compendium.name");
            String headingRight = name(open == null
                    ? "gui.ywzj_vehicle_armor.ammo_compendium.amount"
                    : "gui.ywzj_vehicle_armor.ammo_compendium.penetration");
            float headY = TOP + PADDING + HEADER;
            ArmorUi.text(graphics, heading, left + PADDING, headY, ArmorUi.MUTED);
            ArmorUi.textRight(graphics, headingRight, right - PADDING, headY, ArmorUi.MUTED);

            List<Row> rows = pageRows();
            float height = rowHeight();
            float y = rowsTop();
            for (Row row : rows) {
                boolean overPanel = mx >= left && mx < right && my >= y && my < y + height;
                boolean clickable = row.openTo() != null || row.style() == Style.BACK;
                boolean selected = overPanel && clickable;
                if (selected) {
                    ArmorUi.fill(graphics, left + stroke, y, right - stroke, y + height,
                            ArmorUi.SELECTED);
                } else if (overPanel) {
                    ArmorUi.fill(graphics, left + stroke, y, right - stroke, y + height,
                            ArmorUi.HOVER);
                }

                int colour = switch (row.style()) {
                    case GROUP -> ArmorUi.MUTED;
                    case BACK, LOAD -> ArmorUi.DIM;
                    case SHELF, ROUND -> ArmorUi.TEXT;
                };
                if (selected) {
                    colour = ArmorUi.ON_SELECTED;
                }

                float indent = switch (row.style()) {
                    case ROUND -> 12.0F;
                    case LOAD -> 24.0F;
                    default -> 0.0F;
                };
                float textY = y + Math.round((height - 9.0F) / 2.0F);
                String label = row.style() == Style.BACK ? "← " + row.label() : row.label();
                ArmorUi.text(graphics, ArmorUi.truncate(label, (int) (panelWidth()
                                - PADDING * 2 - indent - VALUE_WIDTH - 8)),
                        left + PADDING + indent, textY, colour);

                if (row.style() == Style.SHELF && !row.detail().isEmpty()) {
                    // The index has room for the one line that says what the warhead is for. It is
                    // drawn under the name at 0.8 so the three rows read as headings, not entries.
                    ArmorUi.textScaled(graphics, row.detail(), left + PADDING,
                            y + height - 10.0F, 0.8F, selected ? ArmorUi.ON_SELECTED : ArmorUi.DIM);
                }

                if (!row.value().isEmpty()) {
                    ArmorUi.textRight(graphics, row.value(), right - PADDING, textY, colour);
                    if (row.style() == Style.ROUND || row.style() == Style.LOAD) {
                        ArmorUi.bar(graphics, right - PADDING - VALUE_WIDTH + 4.0F,
                                y + height - BAR_HEIGHT - 1.0F, VALUE_WIDTH - 8.0F, BAR_HEIGHT,
                                row.fraction(), selected ? ArmorUi.ON_SELECTED : ArmorUi.DIM);
                    }
                }
                y += height;
            }

            if (open != null && rows.size() == 1) {
                ArmorUi.text(graphics, name("gui.ywzj_vehicle_armor.ammo_compendium.empty"),
                        left + PADDING, rowsTop() + ROW, ArmorUi.DIM);
            }

            pageLine(graphics, left, right, stroke);
        }
    }

    /**
     * How far through the book this page is, and what turns it - drawn only when there is somewhere
     * to turn to, plus the one legend the values need.
     */
    private void pageLine(GuiGraphics graphics, float left, float right, float stroke) {
        float y = BOTTOM - FOOTER + 4.0F;
        ArmorUi.rule(graphics, left + PADDING, right - PADDING, y - 4.0F, stroke);

        String hint = name(open != null && rows().stream().anyMatch(
                r -> r.style() == Style.ROUND && r.value().contains(" / "))
                ? "gui.ywzj_vehicle_armor.ammo_compendium.fuze_hint"
                : "gui.ywzj_vehicle_armor.ammo_compendium.page_hint");
        ArmorUi.text(graphics, hint, left + PADDING, y, ArmorUi.DIM);

        int count = rows().size();
        int size = pageSize();
        int pages = Pages.count(count, size);
        if (pages <= 1) {
            return;
        }
        String indicator = Component.m_237110_("gui.ywzj_vehicle_armor.ammo_compendium.page",
                Pages.clamp(page, count, size) + 1, pages).getString();
        ArmorUi.textRight(graphics, indicator, right - PADDING, y, ArmorUi.DIM);
    }

    // --- input --------------------------------------------------------------------------------

    @Override
    public boolean m_6375_(double mouseX, double mouseY, int button) {
        float mx = toVirtualX(mouseX);
        float my = toVirtualY(mouseY);
        if (mx < left() || mx >= right() || my < rowsTop() || my >= rowsBottom()) {
            return super.m_6375_(mouseX, mouseY, button);
        }
        List<Row> rows = pageRows();
        int index = rowAt(mouseY);
        if (index < 0 || index >= rows.size()) {
            return super.m_6375_(mouseX, mouseY, button);
        }
        Row row = rows.get(index);
        if (row.style() == Style.BACK) {
            open = null;
            page = 0;
        } else if (row.openTo() != null) {
            open = row.openTo();
            page = 0;
        } else {
            return super.m_6375_(mouseX, mouseY, button);
        }
        return true;
    }

    @Override
    public boolean m_7933_(int keyCode, int scanCode, int modifiers) {
        // Escape steps back to the index before it closes the book, so the way out of a warhead is
        // the same key that got you into it. A second escape closes, which is vanilla's own
        // handling and is reached by falling through.
        if (open != null && keyCode == escapeKey()) {
            open = null;
            page = 0;
            return true;
        }
        // The arrows turn the page, which is the same pair the armour scanner pages its readout
        // with - so "up and down move through the tables" means the same gesture in both tools.
        if (open != null) {
            int up = keyCode("key.keyboard.up");
            int down = keyCode("key.keyboard.down");
            if (keyCode == up || keyCode == down) {
                turnPage(keyCode == up ? -1 : 1);
                return true;
            }
        }
        return super.m_7933_(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean m_6050_(double mouseX, double mouseY, double delta) {
        float mx = toVirtualX(mouseX);
        float my = toVirtualY(mouseY);
        boolean overPanel = mx >= left() && mx < right() && my >= TOP && my < BOTTOM;
        if (open != null && delta != 0.0 && overPanel) {
            turnPage(delta > 0.0 ? -1 : 1);
            return true;
        }
        return super.m_6050_(mouseX, mouseY, delta);
    }

    private static int escapeKey() {
        return keyCode("key.keyboard.escape");
    }

    /**
     * @return the GLFW key code Mojang has registered under the given name, or {@code -1} if it is
     *         unknown - which never matches a real key, so paging simply does nothing.
     */
    private static int keyCode(String name) {
        InputConstants.Key key = InputConstants.m_84851_(name);
        return key == null ? -1 : key.m_84873_();
    }

    // --- formatting ---------------------------------------------------------------------------

    /**
     * A round's penetration, or both of them when its fuze can be switched.
     *
     * <p>The pair is written "impact / proximity", and the footer spells out which is which rather
     * than putting two unlabelled numbers on the row. The two are shown even though they happen to
     * be the same number today, because the reason to read the entry is to know what the shell does
     * in each position - and a single figure would say the fuze changes nothing.
     */
    private static String format(AmmoIndex.Entry entry) {
        String millimetres = name("gui.ywzj_vehicle_armor.ammo_compendium.mm");
        if (!entry.switchable()) {
            return trim(entry.penetration()) + millimetres;
        }
        return String.format(Locale.ROOT, "%s / %s%s",
                trim(entry.penetration()), trim(entry.proximity()), millimetres);
    }

    private static String trim(float value) {
        return ArmorUi.trim(value);
    }

    private static String name(String translationKey) {
        return Component.m_237115_(translationKey).getString();
    }
}
