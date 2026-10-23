package com.ywzjvehicle.armor;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The compendium's two pages: an index of the three warheads, and the rounds of whichever one was
 * clicked.
 *
 * <p>Two levels rather than one flat list because the rounds are not comparable across the
 * warheads - a 60mm high-explosive shell and a 780mm dart have nothing to say to each other, and a
 * single list sorted by penetration puts exactly those two next to each other with no way to tell
 * why.
 *
 * <p>The second page carries a third level of a sort: its rounds are grouped under the weapon that
 * fires them, because "which gun" is the other half of what a round is. The grouping is only drawn
 * when a warhead actually has more than one kind of gun behind it - underneath 破甲弹, where every
 * round today comes out of a tank gun, a heading would be a line of the page spent saying nothing.
 *
 * <p>What a round's page shows is the addon's own view of it: the equivalent penetration it is
 * measured by, with the name taken from the vehicle pack (see {@link AmmoIndex}). It says nothing
 * about armour, because a round has no answer to "what does this penetrate" on its own - that
 * depends on the plate, and the armour scanner is the tool for the other half of the question.
 *
 * <p>A warhead with more rounds than the panel is tall takes several pages, turned with the up and
 * down arrows or the mouse wheel. Before that it shrank the rows to fit, down to six pixels each -
 * which stops working exactly when the book gets useful: at forty rounds the text is drawn on top of
 * itself, and the whole page is unreadable rather than merely cramped. A page is the honest answer,
 * and it is what a book does.
 *
 * <p>Drawn with plain fills rather than widgets: there are two states and a handful of rows, and a
 * real widget stack would need the screen to hand its children their own input before it could
 * decide anything itself.
 */
public class AmmoCompendiumScreen extends Screen {

    private static final int PANEL_WIDTH = 250;
    private static final int ROW_HEIGHT = 11;
    private static final int PADDING = 8;
    private static final int TITLE_GAP = 16;
    private static final int HEADING_GAP = 12;

    /**
     * Room kept at the bottom of the panel for the page line, whether or not there is one.
     *
     * <p>Reserved always rather than only when the book is long enough to need it, because
     * otherwise the page size changes at the moment a warhead crosses from one page to two - and the
     * rows would visibly reflow on the click that took you from three rounds to four.
     */
    private static final int FOOTER_GAP = 12;

    private static final int BACKGROUND = 0xC0101010;
    private static final int PANEL = 0xFF1C2B1C;
    private static final int BORDER = 0xFF4C7A4C;
    private static final int TITLE = 0xFFE8F5E8;
    private static final int HEADING = 0xFF9DC79D;
    private static final int NAME = 0xFFE0E0E0;
    private static final int VALUE = 0xFFB6E3B6;
    private static final int DIM = 0xFF7A8F7A;
    private static final int HOVER = 0x30FFFFFF;

    /** Indent per row kind, in pixels: a round sits under its weapon heading, a load under its slot. */
    private static final int GROUP_INDENT = 8;

    /** The warhead being read, or {@code null} for the index. */
    private Warhead open;

    /**
     * Which page of the open warhead's rows is showing.
     *
     * <p>Always zero on the index, which lists three warheads and never needs a second page - and
     * reset to zero whenever the page is changed, so opening a warhead always starts at its
     * beginning rather than wherever the last one was left.
     */
    private int page;

    public AmmoCompendiumScreen() {
        super(Component.m_237115_("gui.ywzj_vehicle_armor.ammo_compendium"));
    }

    /** What a row is, which decides how it is drawn and whether it can be clicked. */
    private enum Style {
        /** One of the three warheads on the index. */
        SHELF,
        /** The way back to the index. */
        BACK,
        /** The weapon that fires the rounds under it. Not clickable. */
        GROUP,
        /** A round. */
        ROUND,
        /** Another mark a slot can be loaded with, listed under it. */
        LOAD
    }

    /** One drawn row, and what clicking it does. */
    private record Row(String label, String value, Warhead openTo, Style style) {
    }

    private List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        if (open == null) {
            for (Warhead warhead : Warhead.values()) {
                int count = AmmoIndex.entriesWith(warhead).size();
                rows.add(new Row(name(warhead.translationKey()),
                        Component.m_237115_("gui.ywzj_vehicle_armor.ammo_compendium.count").getString()
                                .formatted(count),
                        warhead, Style.SHELF));
            }
            return rows;
        }

        rows.add(new Row(name("gui.ywzj_vehicle_armor.ammo_compendium.back"), "", null, Style.BACK));
        List<AmmoIndex.Entry> entries = AmmoIndex.entriesWith(open);
        boolean grouped = AmmoCategory.groupsByWeapon(open, AmmoIndex.shelvesOf(entries));
        WeaponClass current = null;
        for (AmmoIndex.Entry entry : entries) {
            if (grouped && entry.category().weapon() != current) {
                current = entry.category().weapon();
                rows.add(new Row(name(current.translationKey()), "", null, Style.GROUP));
            }
            rows.add(new Row(entry.name(), format(entry), null, Style.ROUND));
            // The other marks a slot can be loaded with, listed under it rather than as separate
            // rounds: they are not separate rounds, and filing them as such would have the book
            // claim the Abrams carries six kinds of shell.
            for (AmmoIndex.Load load : entry.loads()) {
                rows.add(new Row(load.name(),
                        trim(load.penetration())
                                + name("gui.ywzj_vehicle_armor.ammo_compendium.mm"),
                        null, Style.LOAD));
            }
        }
        return rows;
    }

    /** Where the rows start, worked out the same way for drawing and clicking. */
    private int firstRow() {
        return panelTop() + PADDING + TITLE_GAP + HEADING_GAP;
    }

    /**
     * How many rows a page holds at this window size.
     *
     * <p>Rows are the height they have always been; a list that does not fit now goes onto another
     * page rather than being squashed into this one. {@link Pages#perPage} floors and never returns
     * zero, so even a window too short for one row still shows a page with a row on it.
     */
    private int pageSize() {
        int room = panelBottom() - PADDING - FOOTER_GAP - firstRow();
        return Pages.perPage(room, ROW_HEIGHT);
    }

    /**
     * The rows on the page being shown - which is what is drawn, and what a click lands on.
     *
     * <p>Both of those go through here rather than through {@link #rows()} so they cannot disagree:
     * a click that indexes the full list while the drawing shows the third page would open whatever
     * happened to be at that line instead of the row under the cursor.
     */
    private List<Row> pageRows() {
        List<Row> all = rows();
        int size = pageSize();
        return all.subList(Pages.first(page, all.size(), size), Pages.end(page, all.size(), size));
    }

    /** Turns one page, stopping at the ends. */
    private void turnPage(int direction) {
        page = Pages.turn(page, rows().size(), pageSize(), direction);
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

    @Override
    public void m_88315_(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int left = panelLeft();
        int top = panelTop();
        int bottom = panelBottom();
        int right = left + PANEL_WIDTH;

        graphics.m_280509_(0, 0, f_96543_, f_96544_, BACKGROUND);
        graphics.m_280509_(left, top, right, bottom, PANEL);
        // A one-pixel frame, drawn as four fills: there is no outline helper worth a dependency.
        graphics.m_280509_(left, top, right, top + 1, BORDER);
        graphics.m_280509_(left, bottom - 1, right, bottom, BORDER);
        graphics.m_280509_(left, top, left + 1, bottom, BORDER);
        graphics.m_280509_(right - 1, top, right, bottom, BORDER);

        String title = open == null ? f_96539_.getString() : name(open.translationKey());
        graphics.m_280056_(f_96547_, title, (f_96543_ - f_96547_.m_92895_(title)) / 2,
                top + PADDING, TITLE, true);

        String heading = name(open == null
                ? "gui.ywzj_vehicle_armor.ammo_compendium.category"
                : "gui.ywzj_vehicle_armor.ammo_compendium.name");
        String headingRight = name(open == null
                ? "gui.ywzj_vehicle_armor.ammo_compendium.amount"
                : "gui.ywzj_vehicle_armor.ammo_compendium.penetration");
        int headingY = top + PADDING + TITLE_GAP;
        graphics.m_280056_(f_96547_, heading, left + PADDING, headingY, HEADING, false);
        graphics.m_280056_(f_96547_, headingRight, right - PADDING - f_96547_.m_92895_(headingRight),
                headingY, HEADING, false);

        List<Row> rows = pageRows();
        int y = firstRow();

        for (Row row : rows) {
            boolean hovered = mouseY >= y && mouseY < y + ROW_HEIGHT
                    && mouseX >= left + 1 && mouseX < right - 1;
            // Only things that can be clicked light up; a hover highlight on a row that does
            // nothing is a promise the page does not keep.
            boolean clickable = row.openTo() != null || row.style() == Style.BACK;
            // A heading is a label for the rows under it and a load is one of the ways its slot can
            // be fired, so neither opens anything: only the index and the way back do.
            if (hovered && clickable) {
                graphics.m_280509_(left + 1, y - 1, right - 1, y + ROW_HEIGHT - 2, HOVER);
            }
            int colour;
            switch (row.style()) {
                case GROUP:
                    colour = HEADING;
                    break;
                case BACK:
                case LOAD:
                    colour = DIM;
                    break;
                case SHELF:
                    colour = hovered ? TITLE : VALUE;
                    break;
                default:
                    colour = NAME;
            }
            graphics.m_280056_(f_96547_, row.label(),
                    left + PADDING + indent(row.style()), y, colour, false);
            if (!row.value().isEmpty()) {
                // A round's value is the point of the row, so it reads bright; a count beside a
                // warhead is a note, and the two greys keep them apart.
                int valueColour = row.style() == Style.ROUND ? VALUE : DIM;
                graphics.m_280056_(f_96547_, row.value(),
                        right - PADDING - f_96547_.m_92895_(row.value()), y, valueColour, false);
            }
            y += ROW_HEIGHT;
        }

        if (open != null && rows.size() == 1) {
            graphics.m_280056_(f_96547_,
                    name("gui.ywzj_vehicle_armor.ammo_compendium.empty"),
                    left + PADDING, firstRow() + ROW_HEIGHT, DIM, false);
        }

        pageLine(graphics, left, right, bottom);
    }

    /**
     * How far through the book this page is, and what turns it - drawn only when there is somewhere
     * to turn to.
     *
     * <p>The number is the page <em>shown</em> rather than the one stored, because those differ the
     * moment a window is resized smaller: the stored index is left where it was so that growing the
     * window again comes back to the same page, and the count is what fits now.
     */
    private void pageLine(GuiGraphics graphics, int left, int right, int bottom) {
        int rows = rows().size();
        int size = pageSize();
        int pages = Pages.count(rows, size);
        if (pages <= 1) {
            return;
        }
        int y = bottom - PADDING - 9;
        String hint = name("gui.ywzj_vehicle_armor.ammo_compendium.page_hint");
        graphics.m_280056_(f_96547_, hint, left + PADDING, y, DIM, false);

        String indicator = Component.m_237110_("gui.ywzj_vehicle_armor.ammo_compendium.page",
                Pages.clamp(page, rows, size) + 1, pages).getString();
        graphics.m_280056_(f_96547_, indicator, right - PADDING - f_96547_.m_92895_(indicator),
                y, DIM, false);
    }

    private static int indent(Style style) {
        switch (style) {
            case ROUND:
                return GROUP_INDENT;
            case LOAD:
                return GROUP_INDENT * 2;
            default:
                return 0;
        }
    }

    @Override
    public boolean m_6375_(double mouseX, double mouseY, int button) {
        int left = panelLeft();
        int right = left + PANEL_WIDTH;
        if (mouseX < left + 1 || mouseX >= right - 1 || mouseY < panelTop() || mouseY >= panelBottom()) {
            return super.m_6375_(mouseX, mouseY, button);
        }
        List<Row> rows = pageRows();
        int index = (int) ((mouseY - firstRow()) / ROW_HEIGHT);
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
            // A round, a load or a heading does nothing: the book lists what a gun can fire, it
            // does not load it.
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

    /**
     * The mouse wheel turns the page too, and only while the pointer is over the book.
     *
     * <p>Wheel up is the page before, which is what every other list on a computer does. Guarded on
     * the pointer because a screen that pages from anywhere swallows the wheel of anything drawn
     * over it later, and because scrolling a list you are not pointing at is the sort of thing that
     * gets reported as a bug in whatever the item under the cursor was.
     */
    @Override
    public boolean m_6050_(double mouseX, double mouseY, double delta) {
        int left = panelLeft();
        int right = left + PANEL_WIDTH;
        boolean overPanel = mouseX >= left && mouseX < right
                && mouseY >= panelTop() && mouseY < panelBottom();
        if (open != null && delta != 0.0 && overPanel) {
            turnPage(delta > 0.0 ? -1 : 1);
            return true;
        }
        return super.m_6050_(mouseX, mouseY, delta);
    }

    /** @return the GLFW code Mojang has registered for Escape, or {@code -1} if it is unknown. */
    private static int escapeKey() {
        return keyCode("key.keyboard.escape");
    }

    /**
     * @return the GLFW key code Mojang has registered under the given name, or {@code -1} if it is
     *         unknown - which never matches a real key, so paging simply does nothing.
     *
     * <p>Looked up by name rather than taken from GLFW: that constant would mean another jar on the
     * compile classpath, which this addon has so far avoided, and the lookup is the same one the
     * scanner already pages with.
     */
    private static int keyCode(String name) {
        InputConstants.Key key = InputConstants.m_84851_(name);
        return key == null ? -1 : key.m_84873_();
    }

    /**
     * A round's penetration, or both of them when its fuze can be switched.
     *
     * <p>The two are shown for a multi-purpose round even though they happen to be the same number
     * today, because the reason to read the entry is to know what the shell does in each position -
     * and a single figure would say the fuze changes nothing.
     */
    private static String format(AmmoIndex.Entry entry) {
        String millimetres = name("gui.ywzj_vehicle_armor.ammo_compendium.mm");
        if (!entry.switchable()) {
            return trim(entry.penetration()) + millimetres;
        }
        return String.format(Locale.ROOT, "%s / %s%s",
                trim(entry.penetration()), trim(entry.proximity()), millimetres);
    }

    /** Whole numbers without a trailing ".0"; the config is in whole millimetres in practice. */
    private static String trim(float value) {
        return value == Math.round(value)
                ? String.valueOf(Math.round(value))
                : String.format(Locale.ROOT, "%.1f", value);
    }

    private static String name(String translationKey) {
        return Component.m_237115_(translationKey).getString();
    }
}
