package com.ywzjvehicle.armor.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import org.ywzj.vehicle.util.RenderHelper;
import org.ywzj.vehicle.util.VehicleHudText;

/**
 * The addon's share of base mod 0.6.2's HUD language.
 *
 * <p>0.6.2 replaced the vehicle HUD with one 480-unit-tall virtual layout
 * ({@link org.ywzj.vehicle.client.gui.VehicleHudLayout}) drawn in near-white on a translucent
 * grey panel. The addon's own windows predate that and were a fixed 250-pixel green book; they
 * are restyled to this so that opening the compendium in a vehicle does not look like a different
 * game.
 *
 * <p>Everything here is client-only and nothing but drawing lives in it: the colours, a panel, a
 * separator, a proportional bar and the two rounded formatters the screens share. Keeping the
 * tokens in one place is what stops the three surfaces drifting apart again.
 */
public final class ArmorUi {

    /** Body text. The base HUD's own white. */
    public static final int TEXT = 0xFFF2F2F2;

    /** Secondary text: hints, headings, the non-selected half of a pair. */
    public static final int MUTED = 0xFFB8B8B8;

    /** Tertiary text: page numbers and things that are present but not the point. */
    public static final int DIM = 0xFF8A8A8A;

    /** Text drawn on top of a selected (inverted) row. */
    public static final int ON_SELECTED = 0xFF161616;

    /** The base mod's panel fill, used by its tips panel and status block. */
    public static final int PANEL = 0x80404040;

    /** A lighter wash for the row under the pointer that is not the chosen one. */
    public static final int HOVER = 0x26FFFFFF;

    /** The selected row is a solid bar of the base HUD's border colour. */
    public static final int SELECTED = 0xFFF2F2F2;

    /** A warning: shallow water, a round that will not reach. */
    public static final int WARN = 0xFFFFB74D;

    /** The unfilled part of a bar. */
    public static final int BAR_TRACK = 0x59FFFFFF;

    private ArmorUi() {
    }

    /**
     * One physical pixel in virtual HUD units, which is what a 1-pixel border has to be.
     *
     * <p>The same expression the base mod uses in its own panels. {@code Window.m_85442_()} is the
     * framebuffer height (not the GUI scale, despite what the name suggests) - checked against the
     * decompiled class, because the border being 1.3 pixels instead of 1 is the sort of thing that
     * only shows up as "the frame looks slightly thick".
     */
    public static float stroke(float scale) {
        return 480.0F / (float) Math.max(1, Minecraft.m_91087_().m_91268_().m_85442_()) / scale;
    }

    /** Fills a rectangle in GUI-ghost render type, the base mod's choice for HUD geometry. */
    public static void fill(GuiGraphics graphics, float left, float top, float right, float bottom,
                            int color) {
        RenderHelper.fill(graphics, RenderType.m_285907_(), left, top, right, bottom, 0.0F, color);
    }

    /** A filled panel with a one-pixel border, in the base HUD's colours. */
    public static void panel(GuiGraphics graphics, float left, float top, float right, float bottom,
                             float stroke) {
        fill(graphics, left, top, right, bottom, PANEL);
        fill(graphics, left, top, right, top + stroke, TEXT);
        fill(graphics, left, bottom - stroke, right, bottom, TEXT);
        fill(graphics, left, top, left + stroke, bottom, TEXT);
        fill(graphics, right - stroke, top, right, bottom, TEXT);
    }

    /** A horizontal rule, centred on {@code y}. */
    public static void rule(GuiGraphics graphics, float left, float right, float y, float stroke) {
        fill(graphics, left, y - stroke / 2.0F, right, y + stroke / 2.0F, TEXT);
    }

    /** A vertical rule, centred on {@code x}. */
    public static void column(GuiGraphics graphics, float x, float top, float bottom, float stroke) {
        fill(graphics, x - stroke / 2.0F, top, x + stroke / 2.0F, bottom, TEXT);
    }

    /** Left-aligned text at a virtual coordinate. */
    public static void text(GuiGraphics graphics, String text, float x, float y, int color) {
        VehicleHudText.drawString(graphics, text, x, y, color);
    }

    /** Right-aligned text whose right edge is {@code right}. */
    public static void textRight(GuiGraphics graphics, String text, float right, float y, int color) {
        VehicleHudText.drawString(graphics, text, right - VehicleHudText.width(text), y, color);
    }

    /** Centred text whose centre is {@code centreX}. */
    public static void textCentred(GuiGraphics graphics, String text, float centreX, float y,
                                   int color) {
        VehicleHudText.drawString(graphics, text, centreX - VehicleHudText.width(text) / 2.0F, y,
                color);
    }

    /** Text drawn at {@code scale}, positioned by its top-left corner. */
    public static void textScaled(GuiGraphics graphics, String text, float x, float y, float scale,
                                  int color) {
        if (text == null || text.isEmpty()) {
            return;
        }
        graphics.m_280168_().m_85836_();
        try {
            graphics.m_280168_().m_252880_(x, y, 0.0F);
            graphics.m_280168_().m_85841_(scale, scale, 1.0F);
            graphics.m_280056_(Minecraft.m_91087_().f_91062_, text, 0, 0, color, true);
        } finally {
            graphics.m_280168_().m_85849_();
        }
    }

    /**
     * A proportional bar.
     *
     * <p>Drawn rather than widgetised because every caller has one number to show and a bar is the
     * cheapest way to make two numbers comparable at a glance - which is the whole job of the
     * compendium's value column.
     *
     * @param fraction 0..1, clamped
     */
    public static void bar(GuiGraphics graphics, float left, float y, float width, float height,
                           float fraction, int color) {
        fill(graphics, left, y, left + width, y + height, BAR_TRACK);
        float filled = width * Math.max(0.0F, Math.min(1.0F, fraction));
        if (filled > 0.0F) {
            fill(graphics, left, y, left + filled, y + height, color);
        }
    }

    /** The font, for callers that need a width they cannot get from {@link VehicleHudText}. */
    public static Font font() {
        return Minecraft.m_91087_().f_91062_;
    }

    /**
     * Truncates to a pixel width, with an ellipsis when there is room for one.
     *
     * <p>One copy rather than four: the compendium, the chooser, the scanner and the HUD all need
     * the same "does it fit, and if not where" answer, and four copies of the ellipsis arithmetic is
     * four chances for one of them to overrun its column.
     */
    public static String truncate(String text, int width) {
        if (text == null) {
            return "";
        }
        Font font = font();
        if (font.m_92895_(text) <= width) {
            return text;
        }
        String suffix = "...";
        if (width < font.m_92895_(suffix)) {
            return "";
        }
        return font.m_92834_(text, width - font.m_92895_(suffix)) + suffix;
    }

    /** Whole numbers without a trailing ".0"; the config is in whole millimetres in practice. */
    public static String trim(float value) {
        return value == Math.round(value) ? String.valueOf(Math.round(value))
                : String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    /** One decimal place, always - blocks and degrees read better with a fixed width. */
    public static String oneDecimal(float value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    /** A width clamped to a range, which is the only sizing rule any of these panels needs. */
    public static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
