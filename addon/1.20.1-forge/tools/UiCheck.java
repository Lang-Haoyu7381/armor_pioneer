import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ywzjvehicle.armor.Suspension;
import com.ywzjvehicle.armor.network.SuspensionStateMessage;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Checks the two things about the new UI that can be checked without a game.
 *
 * <p><b>Words.</b> Every key the screens, the scanner and the HUD ask for has to exist in both
 * languages and both languages have to define the same set. A missing key renders as its own name,
 * which is the one UI bug a screenshot shows as "a bug in the addon" rather than "a missing
 * translation".
 *
 * <p><b>The wire.</b> The suspension reply grew a vehicle id, two step indices and an announce flag
 * in 0.6.2, and it is the packet that now puts the client's wheels back on the ground. A field that
 * encodes in the wrong order still produces a buffer that reads without throwing - it just puts the
 * pitch in the height and the wheels in the wrong place - so the round trip is asserted field by
 * field, and the buffer has to be empty at the end.
 *
 * <p>What this cannot check is pixels. The layout arithmetic is in virtual units against the base
 * mod's 480-unit space, and the only honest test of it is looking at the screen.
 */
public class UiCheck {

    private static int failures;

    public static void main(String[] args) {
        language();
        wire();
        if (failures > 0) {
            System.out.println("RESULT: " + failures + " UI check(s) failed");
            System.exit(1);
        }
        System.out.println("RESULT: every UI check passed");
    }

    // --- language -----------------------------------------------------------------------------

    private static final String[] KEYS = {
            // compendium
            "gui.ywzj_vehicle_armor.ammo_compendium.index",
            "gui.ywzj_vehicle_armor.ammo_compendium.rounds",
            "gui.ywzj_vehicle_armor.ammo_compendium.fuze_hint",
            "gui.ywzj_vehicle_armor.warhead.ap.hint",
            "gui.ywzj_vehicle_armor.warhead.heat.hint",
            "gui.ywzj_vehicle_armor.warhead.he.hint",
            // chooser
            "gui.ywzj_vehicle_armor.load_chooser.footer",
            "gui.ywzj_vehicle_armor.load_chooser.loaded",
            // scanner
            "gui.ywzj_vehicle_armor.scanner.hint",
            "gui.ywzj_vehicle_armor.zone.hull",
            "gui.ywzj_vehicle_armor.zone.turret",
            "gui.ywzj_vehicle_armor.zone.gun_mount",
            "gui.ywzj_vehicle_armor.zone.track_front",
            "gui.ywzj_vehicle_armor.zone.track_rear",
            "gui.ywzj_vehicle_armor.zone.road_wheels",
            "gui.ywzj_vehicle_armor.zone.reactive_side",
            "gui.ywzj_vehicle_armor.zone.mg_port",
            "gui.ywzj_vehicle_armor.zone.upper_front",
            "gui.ywzj_vehicle_armor.zone.lower_front",
            "gui.ywzj_vehicle_armor.zone.upper_front_ricochet",
            "gui.ywzj_vehicle_armor.zone.lower_front_belt",
            "gui.ywzj_vehicle_armor.zone.skin",
            "gui.ywzj_vehicle_armor.zone.wing_skin",
            "gui.ywzj_vehicle_armor.zone.equivalent",
            "gui.ywzj_vehicle_armor.zone.loss",
            "gui.ywzj_vehicle_armor.zone.era_hp",
            "gui.ywzj_vehicle_armor.zone.addon_hp",
            "gui.ywzj_vehicle_armor.zone.part.hull_era",
            "gui.ywzj_vehicle_armor.zone.part.additional_armor",
            "gui.ywzj_vehicle_armor.zone.part.additional_armor_rear",
            "gui.ywzj_vehicle_armor.zone.part.additional_armor_top",
            "gui.ywzj_vehicle_armor.zone.part.commander_machine_gun",
            "gui.ywzj_vehicle_armor.zone.part.turret_machine_gun",
            "gui.ywzj_vehicle_armor.zone.part.era_1",
            "gui.ywzj_vehicle_armor.zone.part.era_2",
            "gui.ywzj_vehicle_armor.zone.part.era_3",
            "gui.ywzj_vehicle_armor.zone.part.era_4",
            "gui.ywzj_vehicle_armor.zone.part.era_5",
            "gui.ywzj_vehicle_armor.face.front",
            "gui.ywzj_vehicle_armor.face.rear",
            "gui.ywzj_vehicle_armor.face.right",
            "gui.ywzj_vehicle_armor.face.left",
            "gui.ywzj_vehicle_armor.face.roof",
            "gui.ywzj_vehicle_armor.face.floor",
            // the persistent strip
            "hud.ywzj_vehicle_armor.station",
            "hud.ywzj_vehicle_armor.view",
            "hud.ywzj_vehicle_armor.periscope",
            "hud.ywzj_vehicle_armor.engine",
            "hud.ywzj_vehicle_armor.power",
            "hud.ywzj_vehicle_armor.hybrid",
            "hud.ywzj_vehicle_armor.electric",
            "hud.ywzj_vehicle_armor.hybrid_mode",
            "hud.ywzj_vehicle_armor.charging",
            "hud.ywzj_vehicle_armor.ammo",
            "hud.ywzj_vehicle_armor.fuze",
            "hud.ywzj_vehicle_armor.suspension",
            "hud.ywzj_vehicle_armor.roll",
            "hud.ywzj_vehicle_armor.fording",
            "hud.ywzj_vehicle_armor.order",
            "hud.ywzj_vehicle_armor.ready",
            "hud.ywzj_vehicle_armor.stowed",
            "hud.ywzj_vehicle_armor.deployed",
            "hud.ywzj_vehicle_armor.no_model",
            "hud.ywzj_vehicle_armor.blocks",
            "hud.ywzj_vehicle_armor.override",
            "hud.ywzj_vehicle_armor.designated",
            "hud.ywzj_vehicle_armor.solo",
            "hud.ywzj_vehicle_armor.wounded",
            // the hybrid drive's six answers
            "message.ywzj_vehicle_armor.hybrid_on",
            "message.ywzj_vehicle_armor.hybrid_off",
            "message.ywzj_vehicle_armor.hybrid_empty",
            "message.ywzj_vehicle_armor.hybrid_low",
            "message.ywzj_vehicle_armor.hybrid_none",
            "message.ywzj_vehicle_armor.hybrid_driver",
            // the controls category and every key mapping the addon registers
            "key.category.ywzj_vehicle_armor",
            "key.ywzj_vehicle_armor.crew_left",
            "key.ywzj_vehicle_armor.crew_right",
            "key.ywzj_vehicle_armor.crew_up",
            "key.ywzj_vehicle_armor.crew_down",
            "key.ywzj_vehicle_armor.load_chooser",
            "key.ywzj_vehicle_armor.fording",
            "key.ywzj_vehicle_armor.doors",
            "key.ywzj_vehicle_armor.hybrid",
            // the Armor Pioneer night sight's key and five answers
            "key.ywzj_vehicle_armor.night_vision",
            "message.ywzj_vehicle_armor.night_vision_on",
            "message.ywzj_vehicle_armor.night_vision_off",
            "message.ywzj_vehicle_armor.night_vision_scope",
            "message.ywzj_vehicle_armor.night_vision_pack",
            "key.ywzj_vehicle_armor.periscope",
            "message.ywzj_vehicle_armor.periscope_on",
            "message.ywzj_vehicle_armor.periscope_off",
            "message.ywzj_vehicle_armor.periscope_none",
            "message.ywzj_vehicle_armor.periscope_seat"
    };

    private static void language() {
        System.out.println();
        System.out.println("=== every UI line has words in both languages ===");
        JsonObject zh = lang("zh_cn");
        JsonObject en = lang("en_us");
        for (String key : KEYS) {
            check(zh != null && zh.has(key), "zh_cn has " + key);
            check(en != null && en.has(key), "en_us has " + key);
        }
        if (zh != null && en != null) {
            Set<String> a = new LinkedHashSet<>(zh.keySet());
            Set<String> b = new LinkedHashSet<>(en.keySet());
            a.removeAll(en.keySet());
            b.removeAll(zh.keySet());
            check(a.isEmpty(), "no key is defined only in zh_cn: " + a);
            check(b.isEmpty(), "no key is defined only in en_us: " + b);
        }
        // The four composed prefixes: the code appends an id, so a missing constant would only
        // show up as a raw key in game.
        for (String suffix : new String[]{"ap", "heat", "he"}) {
            check(zh != null && zh.has("gui.ywzj_vehicle_armor.warhead." + suffix),
                    "zh_cn has warhead." + suffix);
            check(en != null && en.has("gui.ywzj_vehicle_armor.warhead." + suffix),
                    "en_us has warhead." + suffix);
        }
    }

    private static JsonObject lang(String locale) {
        String path = "/assets/ywzj_vehicle_armor/lang/" + locale + ".json";
        try (InputStream in = UiCheck.class.getResourceAsStream(path)) {
            if (in == null) {
                check(false, "could not load " + path + " from the compiled classes");
                return null;
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (Exception e) {
            check(false, "could not read " + path + ": " + e);
            return null;
        }
    }

    // --- the suspension reply -----------------------------------------------------------------

    private static void wire() {
        System.out.println();
        System.out.println("=== the suspension reply survives the wire ===");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        // The steps are chosen to mean exactly these measurements under the default
        // Suspension.Settings (0.15 of rise, 2 degrees of pitch): the height axis has four
        // positions and the pitch axis nine, so 3 is the top and 1 is two degrees nose-down.
        SuspensionStateMessage sent =
                new SuspensionStateMessage(4242, true, true, 3, 1, 13, 0.15F, -1.5F, 2.5F, true);
        SuspensionStateMessage.encode(sent, buffer);
        check(buffer.readableBytes() > 0, "the message wrote something");
        SuspensionStateMessage received = SuspensionStateMessage.decode(buffer);
        check(received.vehicleEntityId() == 4242, "the vehicle id round trips");
        check(received.hasSuspension(), "the 'has a suspension' flag round trips");
        check(received.hasRoll(), "the 'has a lateral axis' flag round trips");
        check(received.heightStep() == 3, "the height step round trips (got "
                + received.heightStep() + ")");
        check(received.pitchStep() == 1, "the pitch step round trips (got "
                + received.pitchStep() + ")");
        check(received.rollStep() == 13, "the roll step round trips (got "
                + received.rollStep() + ")");
        check(Math.abs(received.rise() - 0.15F) < 1e-6F, "the rise round trips");
        check(Math.abs(received.pitchDegrees() + 1.5F) < 1e-6F, "the pitch round trips");
        check(Math.abs(received.rollDegrees() - 2.5F) < 1e-6F, "the lean round trips");
        check(received.announce(), "the announce flag round trips");
        check(sent.equals(received), "the whole record is equal after encode/decode");
        check(buffer.readableBytes() == 0, "the buffer is empty after decoding");

        // The two halves of the same message have to describe the same pose. A handler may use the
        // steps (to set the client's rest lengths) or the measurements (to print the line); if they
        // disagreed, the wheels and the text would tell different stories. The roll axis needs its
        // own settings here because the default has no lateral range, so a step of 2 would be the
        // level position and the reported 2.5 degrees could not agree with it.
        Suspension.Settings settings = new Suspension.Settings();
        settings.max_roll = 4.0F;
        Suspension.Pose pose = new Suspension.Pose(received.heightStep(), received.pitchStep(),
                received.rollStep());
        check(Math.abs(Suspension.height(settings, pose.height()) - received.rise()) < 1e-6F,
                "the height step and the reported rise are the same pose");
        check(Math.abs(Suspension.pitch(settings, pose.pitch()) - received.pitchDegrees()) < 1e-6F,
                "the pitch step and the reported pitch are the same pose");
        check(Math.abs(Suspension.roll(settings, pose.roll()) - received.rollDegrees()) < 1e-6F,
                "the roll step and the reported lean are the same pose");

        FriendlyByteBuf noSuspension = new FriendlyByteBuf(Unpooled.buffer());
        SuspensionStateMessage absent =
                new SuspensionStateMessage(7, false, false, 0, 0, 0, 0.0F, 0.0F, 0.0F, false);
        SuspensionStateMessage.encode(absent, noSuspension);
        SuspensionStateMessage read = SuspensionStateMessage.decode(noSuspension);
        check(!read.hasSuspension() && !read.hasRoll() && !read.announce()
                        && read.vehicleEntityId() == 7,
                "a 'no suspension' answer round trips too");
    }

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
