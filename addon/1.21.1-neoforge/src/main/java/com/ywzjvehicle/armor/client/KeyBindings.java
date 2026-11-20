package com.ywzjvehicle.armor.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * Every key this addon reads, as a real {@link KeyMapping}.
 *
 * <p>Before this class the crew keys were literal GLFW codes compared against
 * {@code InputEvent.Key} - they worked, but nothing in Options &gt; Controls could see them, so a
 * player whose keyboard or other mods claimed K, P, G or the arrow keys had no way to move them.
 * A key mapping is registered on the mod bus and then appears in the controls screen under its
 * own category, saved with every other binding.
 *
 * <p>Two of these are deliberately shared with something else:
 * <ul>
 *   <li>{@link #LOAD_CHOOSER} is also the modifier for the suspension pitch chord. Holding it and
 *       pressing the base mod's forward/back keys pitches the hull; tapping it on its own opens
 *       the load chooser on release.</li>
 *   <li>The arrow keys are also the base mod's functional keys. This handler never cancels an
 *       event and never consumes a key, so a helicopter still flies with them; on a ground
 *       vehicle the server is the side that decides whether the press means anything.</li>
 * </ul>
 *
 * <p>The four arrow keys are shared by two addon actions each (lean/fuze and hull/scanner
 * paging), because the old handler already disambiguated them by seat and by item. One mapping
 * per physical key keeps that behaviour while still making the key rebindable; splitting it into
 * four mappings with the same defaults would only produce red conflict markers for two keys that
 * are not actually in conflict.
 *
 * <p>The numeric codes are GLFW's, spelled out for the same reason {@code NightVisionHandler}
 * spells out 53: this addon compiles without LWJGL on the classpath.
 */
public final class KeyBindings {

    /** The controls-screen category shared with the night sight and the periscope. */
    public static final String CATEGORY = "key.category.ywzj_vehicle_armor";

    private static final int LEFT = 263;
    private static final int RIGHT = 262;
    private static final int UP = 265;
    private static final int DOWN = 264;
    private static final int LEFT_ALT = 342;
    private static final int K = 75;
    private static final int P = 80;
    private static final int G = 71;

    /** Lean left on a lateral suspension, otherwise cycle the gunner's fuze. */
    public static final KeyMapping CREW_LEFT = key("crew_left", LEFT);

    /** Lean right on a lateral suspension, otherwise step the gunner's fuze delay. */
    public static final KeyMapping CREW_RIGHT = key("crew_right", RIGHT);

    /** Raise the hull; while the scanner is in hand, page its readout up. */
    public static final KeyMapping CREW_UP = key("crew_up", UP);

    /** Lower the hull; while the scanner is in hand, page its readout down. */
    public static final KeyMapping CREW_DOWN = key("crew_down", DOWN);

    /** Opens the load chooser on release; held, it is the suspension pitch modifier. */
    public static final KeyMapping LOAD_CHOOSER = key("load_chooser", LEFT_ALT);

    /** The driver's fording kit. */
    public static final KeyMapping FORDING = key("fording", K);

    /** The hatches linked to the sender's seat. */
    public static final KeyMapping DOORS = key("doors", P);

    /** The ZTZ-100's diesel/electric switch. */
    public static final KeyMapping HYBRID = key("hybrid", G);

    private KeyBindings() {
    }

    private static KeyMapping key(String path, int code) {
        return new KeyMapping("key.ywzj_vehicle_armor." + path,
                InputConstants.Type.KEYSYM, code, CATEGORY);
    }

    /** Mod-bus listener; see {@code ClientSetup}. */
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(CREW_LEFT);
        event.register(CREW_RIGHT);
        event.register(CREW_UP);
        event.register(CREW_DOWN);
        event.register(LOAD_CHOOSER);
        event.register(FORDING);
        event.register(DOORS);
        event.register(HYBRID);
    }
}
