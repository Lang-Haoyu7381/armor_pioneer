import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ywzjvehicle.armor.ArmorConfig;
import com.ywzjvehicle.armor.Hybrid;
import com.ywzjvehicle.armor.network.HybridStateMessage;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The ZTZ-100's hybrid drive, held to its own truth table without a game.
 *
 * <p>Four kinds of thing are checked, and they are the four that can be wrong silently:
 *
 * <ul>
 *   <li><b>The arithmetic.</b> {@link Hybrid#batteryTick} is pure, so every interesting state -
 *       driving, idle, coasting, an empty battery, a dry tank - is asserted here. A sign inverted in
 *       one branch makes the electric mode charge instead of drain, and nothing in the game reports
 *       that.</li>
 *   <li><b>The shipped configuration.</b> The ZTZ-100 has to be in {@code Profile#hybrids}, its
 *       settings have to be the ones the class documents, and the two derived durations have to be
 *       the numbers the README quotes.</li>
 *   <li><b>The wire.</b> A field encoded in the wrong order still round trips without throwing in
 *       many cases; the hybrid reply is asserted field by field and the buffer has to end empty.</li>
 *   <li><b>The wiring that no unit calls.</b> The mixins, the key letter and the two message ids are
 *       named in files nothing on this classpath executes, so they are read as text. A key that was
 *       never wired, or a mixin left out of the JSON, is otherwise only visible in game.</li>
 * </ul>
 *
 * <p>Usage: HybridCheck
 */
public class HybridCheck {

    private static int failures;

    public static void main(String[] args) throws Exception {
        defaults();
        batteryTable();
        wire();
        wiring();

        if (failures > 0) {
            System.out.println("RESULT: " + failures + " hybrid check(s) failed");
            System.exit(1);
        }
        System.out.println("RESULT: the hybrid drive's arithmetic, config, wire and wiring all agree");
    }

    // --- the shipped configuration -------------------------------------------------------------

    private static void defaults() throws Exception {
        System.out.println();
        System.out.println("=== the ZTZ-100's shipped hybrid drive ===");
        Method defaultProfile = ArmorConfig.class.getDeclaredMethod("defaultProfile");
        defaultProfile.setAccessible(true);
        ArmorConfig.Profile profile = (ArmorConfig.Profile) defaultProfile.invoke(null);

        Field versionField = ArmorConfig.class.getDeclaredField("CONFIG_VERSION");
        versionField.setAccessible(true);
        int version = versionField.getInt(null);
        check("the config version is " + version + " and the defaults carry it",
                profile.config_version == version);
        check("the defaults have a hybrids map", profile.hybrids != null);

        Hybrid.Settings settings = profile.hybrids.get("armor_pioneer:ztz100");
        check("armor_pioneer:ztz100 has a hybrid drive", settings != null);
        if (settings == null) {
            return;
        }
        Hybrid.Settings expected = new Hybrid.Settings();
        check("the capacity is " + expected.battery_capacity + "%",
                same(settings.battery_capacity, expected.battery_capacity));
        check("it starts full", same(settings.initial_charge, expected.initial_charge)
                && settings.initial_charge <= settings.capacity());
        check("the diesel charges at " + expected.charge_per_tick + "%/tick",
                same(settings.charge_per_tick, expected.charge_per_tick));
        check("idle drain is " + expected.idle_drain_per_tick + "%/tick",
                same(settings.idle_drain_per_tick, expected.idle_drain_per_tick));
        check("drive drain is " + expected.drive_drain_per_tick + "%/tick",
                same(settings.drive_drain_per_tick, expected.drive_drain_per_tick));
        check("regen is " + expected.regen_per_tick + "%/tick",
                same(settings.regen_per_tick, expected.regen_per_tick));
        check("the engage threshold is " + expected.min_start_percent + "%",
                same(settings.min_start_percent, expected.min_start_percent));
        check("electric power is " + expected.electric_power_scale + "x the diesel factor",
                same(settings.electric_power_scale, expected.electric_power_scale)
                        && settings.electric_power_scale < 1.0F);
        check("electric top speed is " + expected.electric_speed_fraction + " of the gearbox's",
                same(settings.electric_speed_fraction, expected.electric_speed_fraction)
                        && settings.electric_speed_fraction > 0.0F
                        && settings.electric_speed_fraction < 1.0F);

        // The two figures a crew actually feels, derived rather than copied a second time. If a
        // rate moves and the README does not, this is what catches it.
        float driveSeconds = settings.capacity()
                / Math.max(0.0001F, settings.drive_drain_per_tick) / 20.0F;
        float chargeSeconds = settings.capacity()
                / Math.max(0.0001F, settings.charge_per_tick) / 20.0F;
        check(String.format(java.util.Locale.ROOT,
                "a full battery gives %.1f s of full-throttle electric driving", driveSeconds),
                driveSeconds >= 20.0F && driveSeconds <= 45.0F);
        check(String.format(java.util.Locale.ROOT,
                "the diesel refills it from empty in %.1f s", chargeSeconds),
                chargeSeconds >= 50.0F && chargeSeconds <= 120.0F);
        check("a full battery is more than one engage threshold",
                settings.initial_charge > settings.min_start_percent);
        check("regen is slower than a held throttle drains",
                settings.regen_per_tick < settings.drive_drain_per_tick);

        // The lookup path the game uses: vehicle id first, display id second, absent means null.
        check("ArmorConfig finds the hybrid by vehicle id",
                ArmorConfig.hybridFor(new ResourceLocation("armor_pioneer", "ztz100")) != null);
        check("a vehicle with no entry answers null",
                ArmorConfig.hybridFor(new ResourceLocation("armor_pioneer", "vt5")) == null);
        check("a null id answers null", ArmorConfig.hybridFor(null) == null);
    }

    // --- the battery arithmetic ----------------------------------------------------------------

    private static void batteryTable() {
        System.out.println();
        System.out.println("=== one tick of battery accounting ===");
        Hybrid.Settings s = new Hybrid.Settings();

        // Driving drains at the drive rate.
        Hybrid.Battery driving = Hybrid.batteryTick(s, 50.0F, true, true, true, true, 1.0F);
        check("electric + throttle drains the drive rate",
                same(driving.amount(), 50.0F - s.drive_drain_per_tick) && driving.active());
        // Standing with the mode on drains the idle rate.
        Hybrid.Battery idle = Hybrid.batteryTick(s, 50.0F, true, true, true, false, 0.0F);
        check("electric + stationary drains the idle rate",
                same(idle.amount(), 50.0F - s.idle_drain_per_tick) && idle.active());
        // Coasting recovers more than idle costs, so the battery goes up.
        Hybrid.Battery coasting = Hybrid.batteryTick(s, 50.0F, true, true, true, false, 1.0F);
        check("electric + coasting recovers charge",
                coasting.amount() > 50.0F && coasting.active());
        // Running out turns the mode off and says so.
        Hybrid.Battery empty = Hybrid.batteryTick(s, 0.05F, true, true, true, true, 1.0F);
        check("electric + an empty battery turns electric off",
                same(empty.amount(), 0.0F) && !empty.active() && empty.depleted());
        // Diesel mode charges only with fuel and the engine running.
        Hybrid.Battery charged = Hybrid.batteryTick(s, 50.0F, false, true, true, false, 0.0F);
        check("diesel + engine + fuel charges the battery",
                same(charged.amount(), 50.0F + s.charge_per_tick) && !charged.active());
        Hybrid.Battery dryTank = Hybrid.batteryTick(s, 50.0F, false, true, false, false, 0.0F);
        check("diesel + engine + no fuel does not charge from nothing",
                same(dryTank.amount(), 50.0F));
        Hybrid.Battery engineOff = Hybrid.batteryTick(s, 50.0F, false, false, false, false, 0.0F);
        check("diesel + engine off + stationary neither charges nor drains",
                same(engineOff.amount(), 50.0F));
        Hybrid.Battery engineOffCoast = Hybrid.batteryTick(s, 50.0F, false, false, false,
                false, 1.0F);
        check("diesel + engine off + rolling still regenerates",
                same(engineOffCoast.amount(), 50.0F + s.regen_per_tick));
        // Capacity is a ceiling, not a suggestion.
        Hybrid.Battery full = Hybrid.batteryTick(s, 99.99F, false, true, true, false, 0.0F);
        check("charging never exceeds the capacity",
                same(full.amount(), s.capacity()));
        // The engage threshold is inclusive.
        check("the engage threshold is inclusive",
                Hybrid.canEngage(s, s.min_start_percent));
        check("one point below the threshold is refused",
                !Hybrid.canEngage(s, s.min_start_percent - 0.01F));
        check("a full battery reads 100%",
                same(Hybrid.percentOf(s, s.capacity()), 100.0F));
        check("zero reads 0%", same(Hybrid.percentOf(s, 0.0F), 0.0F));

        System.out.println();
        System.out.println("=== the action-bar codes ===");
        check("ON has a line", Hybrid.describe(Hybrid.ON, 50.0F, 10.0F) != null);
        check("OFF has a line", Hybrid.describe(Hybrid.OFF, 50.0F, 10.0F) != null);
        check("EMPTY has a line", Hybrid.describe(Hybrid.EMPTY, 0.0F, 10.0F) != null);
        check("LOW has a line", Hybrid.describe(Hybrid.LOW, 5.0F, 10.0F) != null);
        check("NONE has a line", Hybrid.describe(Hybrid.NONE, 0.0F, 10.0F) != null);
        check("DRIVER has a line", Hybrid.describe(Hybrid.DRIVER, 50.0F, 10.0F) != null);
        check("SYNC deliberately has no line", Hybrid.describe(Hybrid.SYNC, 50.0F, 10.0F) == null);
    }

    // --- the wire ------------------------------------------------------------------------------

    private static void wire() {
        System.out.println();
        System.out.println("=== the hybrid state survives the wire ===");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        HybridStateMessage sent = new HybridStateMessage(4242, true, true, 0.625F, 10.0F,
                Hybrid.ON, true);
        HybridStateMessage.encode(sent, buffer);
        check("the message wrote something", buffer.readableBytes() > 0);
        HybridStateMessage received = HybridStateMessage.decode(buffer);
        check("the vehicle id round trips", received.vehicleEntityId() == 4242);
        check("the fitted flag round trips", received.fitted());
        check("the active flag round trips", received.active());
        check("the battery fraction round trips", same(received.batteryFraction(), 0.625F));
        check("the engage threshold round trips", same(received.minStartPercent(), 10.0F));
        check("the action code round trips", received.code() == Hybrid.ON);
        check("the announce flag round trips", received.announce());
        check("the whole record is equal after encode/decode", sent.equals(received));
        check("the buffer is empty after decoding", buffer.readableBytes() == 0);

        FriendlyByteBuf absent = new FriendlyByteBuf(Unpooled.buffer());
        HybridStateMessage noHybrid = new HybridStateMessage(-1, false, false, 0.0F, 10.0F,
                Hybrid.NONE, true);
        HybridStateMessage.encode(noHybrid, absent);
        HybridStateMessage read = HybridStateMessage.decode(absent);
        check("a vehicle with no hybrid round trips as fitted=false",
                !read.fitted() && !read.active() && read.vehicleEntityId() == -1);
        check("the no-hybrid buffer is empty after decoding", absent.readableBytes() == 0);
    }

    // --- the wiring no unit test reaches -------------------------------------------------------

    private static void wiring() {
        System.out.println();
        System.out.println("=== the mixins, the key and the channel ===");
        String mixins = text("src/main/resources/ywzj_vehicle_armor.mixins.json");
        check("AbstractVehicleHybridMixin is in the common mixin list",
                mixins != null && list(mixins, "mixins").contains("AbstractVehicleHybridMixin"));
        check("TrackedVehicleElectricMixin is in the common mixin list",
                mixins != null && list(mixins, "mixins").contains("TrackedVehicleElectricMixin"));
        check("AbstractVehicleHybridSoundMixin is client-only",
                mixins != null && list(mixins, "client").contains("AbstractVehicleHybridSoundMixin"));
        check("TrackedVehicleElectricSmokeMixin is client-only",
                mixins != null && list(mixins, "client").contains("TrackedVehicleElectricSmokeMixin"));

        String hybridMixin = text("src/main/java/com/ywzjvehicle/armor/mixin/"
                + "AbstractVehicleHybridMixin.java");
        check("the server mixin cancels fuel burn while electric",
                hybridMixin != null && hybridMixin.contains("tickEnergy")
                        && hybridMixin.contains("Hybrid.isActive"));
        check("the server mixin ticks the battery and drives power while electric",
                hybridMixin != null && hybridMixin.contains("tickPower")
                        && hybridMixin.contains("Hybrid.tickServer")
                        && hybridMixin.contains("setPower")
                        && hybridMixin.contains("ci.cancel()"));
        check("the mixin's power branch runs only while the battery is driving",
                hybridMixin != null && hybridMixin.contains("if (!Hybrid.isActive(self))")
                        && !hybridMixin.contains("getEnergy() > 0.0F"));
        String action = text("src/main/java/com/ywzjvehicle/armor/network/HybridActionMessage.java");
        check("engaging turns the diesel off", action != null
                && action.contains("state.ywzjarmor$setHybridActive(true)")
                && action.contains("vehicle.toggleEngine(false)"));
        check("releasing turns the diesel back on", action != null
                && action.contains("state.ywzjarmor$setHybridActive(false)")
                && action.contains("vehicle.toggleEngine(true)"));
        String speedMixin = text("src/main/java/com/ywzjvehicle/armor/mixin/"
                + "TrackedVehicleElectricMixin.java");
        check("the top speed cap asks the hybrid for its fraction",
                speedMixin != null && speedMixin.contains("Hybrid.speedFraction")
                        && speedMixin.contains("maxSpeedForward"));

        String keys = text("src/main/java/com/ywzjvehicle/armor/WeaponKeyHandler.java");
        String bindings = text("src/main/java/com/ywzjvehicle/armor/client/KeyBindings.java");
        check("G is the hybrid key",
                keys != null && keys.contains("KeyBindings.HYBRID")
                        && bindings != null && bindings.contains("HYBRID = key(\"hybrid\", G)"));
        check("the key sends a HybridActionMessage",
                keys != null && keys.contains("HybridActionMessage")
                        && keys.contains("HybridActionMessage.TOGGLE"));
        check("the key does nothing on a vehicle with no hybrid",
                keys != null && keys.contains("ArmorSystem.hybridFor(vehicle) == null"));

        String network = text("src/main/java/com/ywzjvehicle/armor/network/"
                + "VehicleArmorNetwork.java");
        check("the channel version is 16", network != null && network.contains("VERSION = \"16\""));
        check("the hybrid request is registered",
                network != null && network.contains("HybridActionMessage.ID"));
        check("the hybrid reply is registered",
                network != null && network.contains("HybridStateMessage.ID"));

        String hud = text("src/main/java/com/ywzjvehicle/armor/client/ArmorHudOverlay.java");
        check("the HUD draws the hybrid row", hud != null && hud.contains("HybridClientState.view"));
        check("the HUD's power row includes the electric factor",
                hud != null && hud.contains("HybridClientState.powerFactor"));
    }

    private static java.util.Set<String> list(String json, String key) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray array = root.has(key) ? root.getAsJsonArray(key) : null;
        java.util.Set<String> names = new java.util.TreeSet<>();
        if (array != null) {
            for (var element : array) {
                names.add(element.getAsString());
            }
        }
        return names;
    }

    private static String text(String relative) {
        Path path = Path.of(relative);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean same(float a, float b) {
        return Math.abs(a - b) < 1.0E-4F;
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
