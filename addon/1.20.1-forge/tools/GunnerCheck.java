import com.ywzjvehicle.armor.ArmorConfig;
import com.ywzjvehicle.armor.Fuze;
import com.ywzjvehicle.armor.GunnerSettings;
import com.ywzjvehicle.armor.network.GunnerActionMessage;
import com.ywzjvehicle.armor.network.GunnerStateMessage;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Exercises the fuze state machine and both network messages without a game.
 *
 * <p>Everything here is a place where the feature can fail without saying anything:
 * <ul>
 *   <li>A message whose encoder and decoder disagree produces a fuze the player never chose, or a
 *       delay of some other number, and neither side has anything to complain about.</li>
 *   <li>A cycle that gets stuck, or a delay that steps past its end without wrapping, looks like a
 *       key that works every other press.</li>
 *   <li>{@code Fuze.byId} is handed whatever arrived over the wire, including values no version of
 *       this addon ever sent.</li>
 * </ul>
 */
public class GunnerCheck {

    private static int failures;

    public static void main(String[] args) throws Exception {
        UUID player = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

        cycle(player);
        delay(player);
        loads(player);
        loading(player);
        unknownIds();
        damageModel();
        wireFormat();

        System.out.println();
        if (failures == 0) {
            System.out.println("RESULT: fuze state machine and messages are consistent");
        } else {
            System.out.println("RESULT: " + failures + " check(s) failed - see above");
            System.exit(1);
        }
    }

    /**
     * Choosing a load is asking for it; the gun has to be re-fed before it is in the gun.
     *
     * <p>The whole of this is reachable without a game, which is why it is written down here: the
     * two fields are the difference between "the gunner asked for this round" and "this round comes
     * out when the trigger is pulled", and a build that collapses them would fire the new round the
     * instant the chooser was clicked - which is the behaviour this replaced, and which from the
     * gunner's seat looked like the click having done nothing at all.
     */
    private static void loading(UUID player) {
        System.out.println();
        System.out.println("=== a load arrives when the gun has been re-fed ===");
        GunnerSettings.forget(player);
        check("a fresh gunner has nothing waiting", GunnerSettings.get(player).pending < 0);
        check("and the pack's own round is what comes out", GunnerSettings.get(player).variant == 0);

        GunnerSettings.beginLoad(player, 1);
        check("asking for a load does not put it in the gun",
                GunnerSettings.get(player).variant == 0);
        check("but it is remembered", GunnerSettings.get(player).pending == 1);
        check("a shot fired now still uses the old round", GunnerSettings.get(player).variant == 0);

        check("finishing the reload fits it", GunnerSettings.commitLoad(player) == 1);
        check("and it is the round that comes out", GunnerSettings.get(player).variant == 1);
        check("and nothing is left waiting", GunnerSettings.get(player).pending < 0);
        check("so a second reload fits nothing",
                GunnerSettings.commitLoad(player) == -1);

        GunnerSettings.beginLoad(player, 2);
        GunnerSettings.beginLoad(player, 1);
        check("asking twice before the gun is fed replaces the request",
                GunnerSettings.get(player).pending == 1);
        check("and fitting it lands on the second request", GunnerSettings.commitLoad(player) == 1);
        check("the first request never reached the gun", GunnerSettings.get(player).variant == 1);

        // A gun that reloaded for its own reasons - an empty magazine - must not announce a round
        // nobody chose, which is what the -1 is for.
        check("committing with nothing waiting answers -1", GunnerSettings.commitLoad(player) == -1);
        check("and a player who has never asked cannot commit either",
                GunnerSettings.commitLoad(null) == -1);
    }

    private static void cycle(UUID player) {
        System.out.println("=== fuze cycle ===");
        GunnerSettings.forget(player);
        check("a fresh player starts on impact", GunnerSettings.get(player).fuze == Fuze.IMPACT);
        check("and has nothing recorded for them", !GunnerSettings.hasEntry(player));

        Fuze[] expected = {Fuze.PROXIMITY, Fuze.DELAY, Fuze.IMPACT, Fuze.PROXIMITY};
        for (Fuze want : expected) {
            GunnerSettings.cycleFuze(player);
            Fuze got = GunnerSettings.get(player).fuze;
            check("left arrow -> " + want, got == want);
        }
        check("changing the fuze records the player", GunnerSettings.hasEntry(player));
    }

    private static void delay(UUID player) {
        System.out.println();
        System.out.println("=== delay steps ===");
        // Ten steps of ten ticks should sweep the whole range and land back where it started, at
        // both ends inclusive: 10, 20 ... 100, 10.
        GunnerSettings.forget(player);
        int start = GunnerSettings.get(player).delayTicks;
        check("a fresh player starts at the shortest delay (" + GunnerSettings.DELAY_MIN_TICKS + "t)",
                start == GunnerSettings.DELAY_MIN_TICKS);

        int previous = start;
        boolean everyStepExact = true;
        boolean everyStepInRange = true;
        int value = start;
        for (int i = 0; i < 40; i++) {
            GunnerSettings.stepDelay(player);
            value = GunnerSettings.get(player).delayTicks;
            everyStepInRange &= value >= GunnerSettings.DELAY_MIN_TICKS
                    && value <= GunnerSettings.DELAY_MAX_TICKS;
            // Exactly one step up, or a wrap back to the shortest - never anything in between, and
            // never a value the player could not have reached by pressing the key.
            everyStepExact &= value == wrap(previous, GunnerSettings.DELAY_STEP_TICKS);
            previous = value;
        }
        check("every step lands inside 10..100", everyStepInRange);
        check("every step is one step up, or a wrap to the shortest", everyStepExact);
        System.out.println("  after 40 steps: " + value + "t (" + (value / 20.0F) + "s)");

        // Walk a whole cycle and count the distinct values, which must be the number of steps the
        // range holds - one short (0.5s .. 5s inclusive is ten) means an off-by-one at the top or
        // bottom that a player would feel as a setting they cannot reach.
        GunnerSettings.forget(player);
        java.util.Set<Integer> distinct = new java.util.LinkedHashSet<>();
        distinct.add(GunnerSettings.get(player).delayTicks);
        for (int i = 0; i < 9; i++) {
            GunnerSettings.stepDelay(player);
            distinct.add(GunnerSettings.get(player).delayTicks);
        }
        check("the range holds ten reachable settings", distinct.size() == 10);
        check("the largest is 100 ticks", distinct.contains(100));
        check("the smallest is 10 ticks", distinct.contains(10));
        System.out.println("  settings: " + distinct);
    }

    private static int wrap(int value, int delta) {
        int next = value + delta;
        if (next > 100) {
            next = 10;
        }
        if (next < 10) {
            next = 100;
        }
        return next;
    }

    /**
     * The load a slot is filled with.
     *
     * <p>The index arrives from a client and is resolved against a list the config owns, so the
     * interesting cases are the ends: index zero has to be the round the pack names the slot for
     * rather than the first variant, and an index past the end has to clamp rather than throw.
     */
    private static void loads(UUID player) throws Exception {
        System.out.println();
        System.out.println("=== slot loads ===");
        Method defaultProfile = ArmorConfig.class.getDeclaredMethod("defaultProfile");
        defaultProfile.setAccessible(true);
        ArmorConfig.Profile profile = (ArmorConfig.Profile) defaultProfile.invoke(null);
        ArmorConfig.Ammo dart = profile.ammo_penetration.get("vvp_ywzj:m256_120mm_apfsds");
        ArmorConfig.Ammo heat = profile.ammo_penetration.get("vvp_ywzj:2a46m1_125mm_heat");
        check("the dart slot is in the defaults", dart != null);
        if (dart == null) {
            return;
        }

        // Zero is the round as the pack defines it, so the count is one more than the list.
        check("the dart offers five loads", dart.variantCount() == 5);
        check("index 0 is the round itself", dart.variant(0) == dart);
        check("index 0 carries the round's own penetration", dart.variant(0).penetration == 570.0F);
        String[] names = {"M829A1", "M829A2", "M829A3", "M829A4"};
        float[] penetrations = {600.0F, 630.0F, 700.0F, 780.0F};
        for (int i = 0; i < names.length; i++) {
            ArmorConfig.Ammo load = dart.variant(i + 1);
            check("load " + (i + 1) + " is " + names[i], names[i].equals(load.name));
            check("  at " + penetrations[i] + "mm", load.penetration == penetrations[i]);
        }
        check("a load has no profile of its own", dart.variant(2).profile == null);
        check("the designations read back in order",
                dart.variantNames("M829").equals(java.util.List.of(
                        "M829", "M829A1", "M829A2", "M829A3", "M829A4")));

        // Past the end is clamped, not thrown: the index crossed a network.
        check("an index past the end clamps to the last load",
                dart.variant(99) == dart.variant(4));
        check("a negative index falls back to the round itself", dart.variant(-1) == dart);

        // Every other slot must be untouched by all of this.
        check("a round with no loads offers exactly one", heat != null && heat.variantCount() == 1);
        check("and hands itself back whatever the index", heat.variant(3) == heat);
        check("and is not selectable", !heat.selectable());
        // Which slots offer a choice, rather than how many. A count passes with any set of that
        // size, and the set is the thing that has to be right: the American M829 with its four
        // marks, the KE-W the Ukrainian tank carries *instead of* it - a different round in the
        // same gun, not a fifth load of the M829 the rest of the family fires - the 125mm darts of
        // the T-90M and the T-72B3M, the 125mm high-explosive shell, which is the only slot here
        // whose loads are not kinetic rounds at all, the VT-5's 105mm dart with its one older
        // mark, the VT-4's 125mm dart with the newer DTC10E beside the BTA4, and the K2's 120mm
        // dart with the imported German DM53 beside the domestic K279.
        //
        // The two dual-feed 30mm cannons are deliberately absent, and this is the line that says so.
        // Their two rounds are not a round and a load any more but two weapons in a belt - chosen
        // with the multi-weapon key in the pack's own machinery, with each round keeping its own
        // projectile and its own explosion, which a load carried as figures never could. A load list
        // left on either slot would be the old arrangement coming back.
        //
        // The Type 10's 120mm dart and the Tianma-20's 125mm dart are the two newest load lists:
        // each carries one older mark of its own tank's gun, not a round borrowed from the K2 or the
        // VT-4. That is the whole reason the two vehicles got weapon ids of their own in v60.
        java.util.Set<String> selectable = new java.util.TreeSet<>();
        for (java.util.Map.Entry<String, ArmorConfig.Ammo> entry : profile.ammo_penetration.entrySet()) {
            if (entry.getValue().selectable()) {
                selectable.add(entry.getKey());
            }
        }
        check("the slots offering a choice are the eleven that should, not " + selectable,
                selectable.equals(new java.util.TreeSet<>(java.util.List.of(
                        "armor_pioneer:cannon_105mm",
                        "armor_pioneer:cannon_120mm",
                        "armor_pioneer:cannon_120mm_dm33",
                        "armor_pioneer:cannon_120mm_type10",
                        "armor_pioneer:cannon_125mm",
                        "armor_pioneer:cannon_125mm_dprk",
                        "vvp_ywzj:2a46m5_125mm_apfsds",
                        "vvp_ywzj:2a46m5_125mm_he",
                        "vvp_ywzj:2a46m5_125mm_t90m_apfsds",
                        "vvp_ywzj:kew_120mm_apfsds",
                        "vvp_ywzj:m256_120mm_apfsds"))));
        check("and the two 30mm cannons offer none of it",
                java.util.stream.Stream.of("vvp_ywzj:ztm1_30mm", "vvp_ywzj:ztm1_30mm_apfsds",
                                "vvp_ywzj:xm813_30mm", "vvp_ywzj:xm813_30mm_apfsds")
                        .map(profile.ammo_penetration::get)
                        .allMatch(ammo -> ammo != null && ammo.variantCount() == 1));

        // The VT-5's dart is the newest load list, and its two silent failures are both checked:
        // a variant that is really a different round (which would make it a belt, not a load) and
        // a variant that forgot to drop the pack's raw explosion. The second one is easy to write:
        // variant() hands back the variant object itself, not a copy of the slot, so every field
        // that matters has to be stated on every load.
        ArmorConfig.Ammo vt5 = profile.ammo_penetration.get("armor_pioneer:cannon_105mm");
        check("the VT-5's dart is in the defaults", vt5 != null);
        if (vt5 != null) {
            check("and carries the BTA-2 plus one older load", vt5.variantCount() == 2);
            check("the BTA-2 is the default", vt5.variant(0).penetration == 550.0F);
            check("DTW-2 is the older load",
                    "DTW-2 APFSDS".equals(vt5.variant(1).name)
                            && vt5.variant(1).penetration == 450.0F);
            check("both loads drop the pack's own explosion",
                    vt5.variant(0).replacesBaseExplosion()
                            && vt5.variant(1).replacesBaseExplosion());
            check("both loads state their projectile",
                    vt5.usesKineticModel() && vt5.massKg() == 4.5F
                            && vt5.variant(1).usesKineticModel()
                            && vt5.variant(1).massKg() == 4.2F);
        }
        ArmorConfig.Ammo vt5Heat = profile.ammo_penetration.get("armor_pioneer:cannon_105mm_heat");
        check("the VT-5's HEAT uses the shaped-charge table and the jet model",
                vt5Heat != null && "heat".equals(vt5Heat.profile)
                        && vt5Heat.penetration == 450.0F && vt5Heat.usesHeatModel());
        ArmorConfig.Ammo btm1 = profile.ammo_penetration.get("armor_pioneer:cannon_105mm_bunker");
        check("the BTM1 bunker round is a tandem shaped charge on the jet model",
                btm1 != null && "heat".equals(btm1.profile)
                        && btm1.penetration == 600.0F && Boolean.TRUE.equals(btm1.tandem)
                        && btm1.usesHeatModel() && "cannon_heat".equals(btm1.category));
        ArmorConfig.Ammo vt5He = profile.ammo_penetration.get("armor_pioneer:cannon_105mm_he");
        check("the VT-5's HE is a 2.1 kg charge on the blast model",
                vt5He != null && "he".equals(vt5He.profile)
                        && vt5He.penetration == 60.0F && vt5He.usesHeModel()
                        && vt5He.charge_kg == 2.1F);
        ArmorConfig.Ammo gp105 = profile.ammo_penetration.get("armor_pioneer:cannon_105mm_atgm");
        check("the GP105 is a tandem shaped charge on the jet model",
                gp105 != null && "heat".equals(gp105.profile)
                        && gp105.penetration == 700.0F && Boolean.TRUE.equals(gp105.tandem)
                        && gp105.usesHeatModel());
        ArmorConfig.Ammo coax = profile.ammo_penetration.get("armor_pioneer:gun_7_62mm");
        ArmorConfig.Ammo hmg = profile.ammo_penetration.get("armor_pioneer:gun_12_7mm");
        check("the VT-5's machine guns are rated as machine guns",
                coax != null && coax.multiplier == 3.0F && "machine_gun".equals(coax.category)
                        && hmg != null && hmg.multiplier == 5.0F
                        && "machine_gun".equals(hmg.category));

        // The per-vehicle 7.62mm coaxial guns. One id per gun because the HUD reads the weapon
        // file's own `name`: the Chinese vehicles keep gun_7_62mm (the Type 86), while the
        // Type 10's Type 74, the K2's M60E2 and the Cheonma-20's PKT own ids of their own.
        // Every one carries the same 15mm/x3 machine-gun rating.
        for (String coaxId : java.util.List.of(
                "armor_pioneer:gun_7_62mm",
                "armor_pioneer:type74_7_62mm",
                "armor_pioneer:m60e2_7_62mm",
                "armor_pioneer:pkt_7_62mm")) {
            ArmorConfig.Ammo round = profile.ammo_penetration.get(coaxId);
            check(coaxId + " is rated as a machine gun",
                    round != null && round.penetration == 15.0F && round.multiplier == 3.0F
                            && "machine_gun".equals(round.category));
        }

        // The VT-4, the same pack's MBT: the six rounds on the Zhuhai plinth. The dart slot now
        // offers the DTC10E beside the BTA4, which is a load list of two marks of one kinetic
        // round - the same shape as the VT-5's - and the two genuinely different rounds (the
        // lateral-effect BTS8 and the GP125 missile) are weapon ids of their own.
        ArmorConfig.Ammo vt4Dart = profile.ammo_penetration.get("armor_pioneer:cannon_125mm");
        check("the VT-4's dart is in the defaults",
                vt4Dart != null && "cannon_ap".equals(vt4Dart.category)
                        && vt4Dart.penetration == 600.0F && vt4Dart.selectable()
                        && vt4Dart.variantCount() == 2);
        check("and the DTC10E is its second mark",
                vt4Dart != null && "DTC10E 125mm APFSDS".equals(vt4Dart.variant(1).name)
                        && vt4Dart.variant(1).penetration == 680.0F);
        ArmorConfig.Ammo vt4Heat = profile.ammo_penetration.get("armor_pioneer:cannon_125mm_heat");
        check("the VT-4's HEAT uses the shaped-charge table",
                vt4Heat != null && "heat".equals(vt4Heat.profile)
                        && vt4Heat.penetration == 550.0F && vt4Heat.replacesBaseExplosion());
        ArmorConfig.Ammo vt4He = profile.ammo_penetration.get("armor_pioneer:cannon_125mm_he");
        check("the VT-4's HE is the 125mm shell the T-64 already throws",
                vt4He != null && "he".equals(vt4He.profile)
                        && vt4He.penetration == 60.0F && vt4He.penetration_damage == 2000.0F);
        ArmorConfig.Ammo bts8 = profile.ammo_penetration.get("armor_pioneer:cannon_125mm_pele");
        check("the VT-4's PELE states its projectile and uses the lateral-effect model",
                bts8 != null && "cannon_ap".equals(bts8.category)
                        && bts8.penetration == 320.0F && bts8.usesPeleModel()
                        && !bts8.hasPenetrationBonus()
                        && bts8.mass_kg == 7.0F && bts8.muzzle_velocity == 1700.0F
                        && bts8.caliberMm() == 125.0F
                        && bts8.variantCount() == 1);
        ArmorConfig.Ammo gp125 = profile.ammo_penetration.get("armor_pioneer:cannon_125mm_atgm");
        check("the GP125 is a tandem shaped charge",
                gp125 != null && "heat".equals(gp125.profile)
                        && gp125.penetration == 800.0F && Boolean.TRUE.equals(gp125.tandem));

        // The K2: the domestic K279 is the default load and the imported German DM53 is
        // the second mark of the same slot. The KSTAM-II is a weapon id of its own rather
        // than a load, because its projectile and its lofted trajectory are its own.
        ArmorConfig.Ammo k2Dart = profile.ammo_penetration.get("armor_pioneer:cannon_120mm");
        check("the K2's dart is in the defaults",
                k2Dart != null && "cannon_ap".equals(k2Dart.category)
                        && k2Dart.penetration == 600.0F && k2Dart.selectable()
                        && k2Dart.variantCount() == 2);
        check("and the imported DM53 is its second mark",
                k2Dart != null && "DM53 APFSDS".equals(k2Dart.variant(1).name)
                        && k2Dart.variant(1).penetration == 700.0F);
        ArmorConfig.Ammo kstam = profile.ammo_penetration.get("armor_pioneer:cannon_120mm_kstam");
        check("the KSTAM-II is a shaped charge on the HEAT shelf",
                kstam != null && "heat".equals(kstam.profile)
                        && kstam.penetration == 200.0F
                        && kstam.shelf() == com.ywzjvehicle.armor.AmmoCategory.CANNON_HEAT
                        && kstam.replacesBaseExplosion());
        ArmorConfig.Ammo k2Hmg = profile.ammo_penetration.get("armor_pioneer:k6_12_7mm");
        check("the K2's K6 is rated as a machine gun",
                k2Hmg != null && k2Hmg.penetration == 30.0F && k2Hmg.multiplier == 5.0F
                        && "machine_gun".equals(k2Hmg.category));
        ArmorConfig.Ammo type10Hmg = profile.ammo_penetration.get(
                "armor_pioneer:gun_12_7mm_m2hb");
        check("the Type 10's Type 90 is still rated as a machine gun",
                type10Hmg != null && type10Hmg.penetration == 30.0F
                        && type10Hmg.multiplier == 5.0F
                        && "machine_gun".equals(type10Hmg.category));

        // The Leopard 2A4's own 120 mm family: the DM33 is the service dart, the older DM23
        // is its load variant, DM12 is the HEAT-MP and DM11 the HE shell. The FN-MAG is the
        // 7.62 mm coax/commander gun (still keyed by the id `mg3_7_62mm`).
        ArmorConfig.Ammo dm33 = profile.ammo_penetration.get(
                "armor_pioneer:cannon_120mm_dm33");
        check("the Leopard's DM33 is in the defaults",
                dm33 != null && "cannon_ap".equals(dm33.category)
                        && dm33.penetration == 480.0F && dm33.selectable()
                        && dm33.variantCount() == 2);
        check("and DM23 is its older mark",
                dm33 != null && "DM23 APFSDS".equals(dm33.variant(1).name)
                        && dm33.variant(1).penetration == 400.0F);
        check("both Leopard darts state their projectile",
                dm33 != null && dm33.usesKineticModel() && dm33.massKg() == 7.6F
                        && dm33.variant(1).usesKineticModel());
        ArmorConfig.Ammo dm12 = profile.ammo_penetration.get(
                "armor_pioneer:cannon_120mm_dm12");
        check("the Leopard's DM12 HEAT-MP is a shaped charge on the jet model",
                dm12 != null && "heat".equals(dm12.profile)
                        && dm12.penetration == 480.0F && dm12.usesHeatModel());
        ArmorConfig.Ammo dm11 = profile.ammo_penetration.get(
                "armor_pioneer:cannon_120mm_dm11");
        check("the Leopard's DM11 is a 3.5 kg HE charge",
                dm11 != null && "he".equals(dm11.profile)
                        && dm11.penetration == 60.0F && dm11.usesHeModel()
                        && dm11.charge_kg == 3.5F);
        ArmorConfig.Ammo mg3 = profile.ammo_penetration.get(
                "armor_pioneer:mg3_7_62mm");
        check("the Leopard's MG3 is rated as a machine gun",
                mg3 != null && mg3.penetration == 15.0F && mg3.multiplier == 3.0F
                        && "machine_gun".equals(mg3.category));

        // The Type 10 and the Tianma-20 carry their own tanks' rounds, not the K2's and the VT-4's.
        // The Type 10's gun is the JGSDF 120mm L/44: the Type 10 APFSDS as the default and the
        // licensed JM33 as the older mark, with the JM12A1 multi-purpose HEAT as its only other
        // round - the real tank carries no high-explosive shell. The Tianma's is the 2A46-derived
        // 125mm: a 630mm-class dart with 3BM42 as the older load, the BK-29 shaped charge and a
        // 125mm HE shell, plus the AGS-30 on the commander's station.
        ArmorConfig.Ammo type10Dart = profile.ammo_penetration.get(
                "armor_pioneer:cannon_120mm_type10");
        check("the Type 10's own dart is in the defaults",
                type10Dart != null && "cannon_ap".equals(type10Dart.category)
                        && type10Dart.penetration == 600.0F && type10Dart.selectable()
                        && type10Dart.variantCount() == 2);
        check("and the licensed JM33 is its older mark",
                type10Dart != null && "JM33 APFSDS".equals(type10Dart.variant(1).name)
                        && type10Dart.variant(1).penetration == 480.0F);
        check("both Type 10 darts state their projectile",
                type10Dart != null && type10Dart.usesKineticModel()
                        && type10Dart.variant(1).usesKineticModel());
        ArmorConfig.Ammo jm12 = profile.ammo_penetration.get("armor_pioneer:cannon_120mm_jm12");
        check("the JM12A1 is a multi-purpose shaped charge",
                jm12 != null && "heat".equals(jm12.profile)
                        && jm12.penetration == 480.0F
                        && Boolean.TRUE.equals(jm12.multi_purpose));
        check("... and both of its modes are on the jet model",
                jm12 != null && jm12.usesHeatModel() && jm12.proximity != null
                        && jm12.proximity.usesHeatModel());

        ArmorConfig.Ammo dprkDart = profile.ammo_penetration.get(
                "armor_pioneer:cannon_125mm_dprk");
        check("the Tianma-20's own dart is in the defaults",
                dprkDart != null && "cannon_ap".equals(dprkDart.category)
                        && dprkDart.penetration == 630.0F && dprkDart.selectable()
                        && dprkDart.variantCount() == 2);
        check("and 3BM42 is its older mark",
                dprkDart != null && "3BM42 APFSDS".equals(dprkDart.variant(1).name)
                        && dprkDart.variant(1).penetration == 457.0F);
        check("both Tianma darts state their projectile",
                dprkDart != null && dprkDart.usesKineticModel()
                        && dprkDart.variant(1).usesKineticModel()
                        && dprkDart.variant(1).massKg() == 7.05F);
        ArmorConfig.Ammo bk29 = profile.ammo_penetration.get(
                "armor_pioneer:cannon_125mm_dprk_heat");
        check("the BK-29 is a shaped charge on the HEAT shelf",
                bk29 != null && "heat".equals(bk29.profile)
                        && bk29.penetration == 520.0F && bk29.usesHeatModel()
                        && bk29.shelf() == com.ywzjvehicle.armor.AmmoCategory.CANNON_HEAT);
        ArmorConfig.Ammo dprkHe = profile.ammo_penetration.get(
                "armor_pioneer:cannon_125mm_dprk_he");
        check("and the 125mm HE shell is the same 3.5 kg charge every 125mm here throws",
                dprkHe != null && "he".equals(dprkHe.profile)
                        && dprkHe.penetration == 60.0F && dprkHe.usesHeModel()
                        && dprkHe.charge_kg == 3.5F);
        ArmorConfig.Ammo ags30 = profile.ammo_penetration.get("armor_pioneer:ags_30");
        check("the AGS-30 is rated as an automatic grenade launcher",
                ags30 != null && "he".equals(ags30.profile)
                        && "autocannon_he".equals(ags30.category) && ags30.penetration == 30.0F);

        // The T-90M's list, in the order the chooser shows it. The default is the best round and
        // the alternatives run downwards, which is the opposite of the M829's arrangement - so the
        // names are checked rather than assumed, since both are one list of strings.
        ArmorConfig.Ammo svinets = profile.ammo_penetration.get("vvp_ywzj:2a46m5_125mm_t90m_apfsds");
        check("the T-90M's loads run down from the default",
                svinets != null && svinets.penetration == 700.0F
                        && svinets.variantNames("3BM60").equals(java.util.List.of(
                                "3BM60", "3BM46", "3BM42M", "3BM42")));
        check("and each is rated below the one before it",
                svinets != null && svinets.variant(1).penetration == 600.0F
                        && svinets.variant(2).penetration == 520.0F
                        && svinets.variant(3).penetration == 457.0F);

        // The T-72B3M has the same gun and so the same four rounds, under its own weapon id.
        ArmorConfig.Ammo t72Dart = profile.ammo_penetration.get("vvp_ywzj:2a46m5_125mm_apfsds");
        check("the T-72B3M's dart carries the same four rounds",
                t72Dart != null && t72Dart.penetration == 700.0F && t72Dart.variantCount() == 4
                        && t72Dart.variant(3).penetration == svinets.variant(3).penetration);

        // And the high-explosive slot, whose second load is a different shell rather than a
        // different mark of the same one: same penetration, a heavier blast, and it is still filed
        // as high explosive rather than being dragged onto the dart's shelf by its load list.
        ArmorConfig.Ammo he = profile.ammo_penetration.get("vvp_ywzj:2a46m5_125mm_he");
        ArmorConfig.Ammo telnik = he == null ? null : he.variant(1);
        check("the 125mm HE slot offers the airburst shell",
                he != null && he.variantCount() == 2 && "he".equals(he.profile)
                        && telnik != null && "3OF82 Telnik".equals(telnik.name)
                        && telnik.penetration == he.penetration
                        && telnik.hasSpall()
                        && telnik.spall_damage_max > he.spall_damage_max);
        check("  and stays on the high-explosive shelf",
                he != null && he.shelf() == com.ywzjvehicle.armor.AmmoCategory.CANNON_HE);

        // The two capabilities a key can act on are carried by *different* rounds, and that is the
        // whole reason the round lookup must not filter on either one. It did filter, on
        // multi-purpose, and the load chooser was therefore handed null for the dart it was opened
        // over - which is what "clicking does nothing" was. Asserted here so that the day the two
        // do coincide, whoever makes them coincide is told that the lookup may be simplified, and
        // so that today's separation is a decision rather than an accident.
        ArmorConfig.Ammo multi = profile.ammo_penetration.get("vvp_ywzj:m256_120mm_heat_mp");
        check("the load chooser's round is not the fuze's round",
                dart.selectable() && !dart.multiPurpose());
        check("the fuze's round has no loads",
                multi != null && multi.multiPurpose() && !multi.selectable());

        // The selection itself, through the same door the network uses.
        GunnerSettings.forget(player);
        check("a fresh player is on the pack's own round", GunnerSettings.get(player).variant == 0);
        for (int i = 0; i <= 4; i++) {
            // Two steps now, because that is what a load is: the gunner asks, the gun is re-fed,
            // and only then does the round come out. See loading().
            GunnerSettings.beginLoad(player, i);
            GunnerSettings.commitLoad(player);
            check("selecting " + i + " takes", GunnerSettings.get(player).variant == i);
        }
        GunnerSettings.beginLoad(player, -5);
        GunnerSettings.commitLoad(player);
        check("a negative selection is floored at zero", GunnerSettings.get(player).variant == 0);
        System.out.println("  loads: " + dart.variantNames("M829") + " at "
                + java.util.stream.IntStream.range(0, 5)
                        .mapToObj(i -> trim(dart.variant(i).penetration))
                        .collect(java.util.stream.Collectors.joining(" / ")) + "mm");
    }

    private static String trim(float value) {
        return value == Math.round(value) ? String.valueOf(Math.round(value))
                : String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    private static void unknownIds() {
        System.out.println();
        System.out.println("=== ids off the wire ===");
        check("a negative id falls back to impact", Fuze.byId(-1) == Fuze.IMPACT);
        check("an id past the end falls back to impact",
                Fuze.byId(Fuze.values().length) == Fuze.IMPACT);
        check("a wild id falls back to impact", Fuze.byId(9999) == Fuze.IMPACT);
        for (Fuze fuze : Fuze.values()) {
            check("id " + fuze.ordinal() + " round trips to " + fuze, Fuze.byId(fuze.ordinal()) == fuze);
        }
    }

    private static void damageModel() throws Exception {
        System.out.println();
        System.out.println("=== damage model per fuze ===");
        Method defaultProfile = ArmorConfig.class.getDeclaredMethod("defaultProfile");
        defaultProfile.setAccessible(true);
        ArmorConfig.Profile profile = (ArmorConfig.Profile) defaultProfile.invoke(null);
        ArmorConfig.Ammo mp = profile.ammo_penetration.get("vvp_ywzj:m256_120mm_heat_mp");
        check("the multi-purpose round is in the defaults", mp != null);
        if (mp == null) {
            return;
        }
        check("impact uses the round itself", mp.damageModel(Fuze.IMPACT) == mp);
        check("proximity uses the second model", mp.damageModel(Fuze.PROXIMITY) == mp.proximity);
        check("delay uses the same second model", mp.damageModel(Fuze.DELAY) == mp.proximity);
        check("a null fuze is treated as impact", mp.damageModel(null) == mp);

        // The two models have to disagree in the ways that matter, or the fuze changes nothing.
        check("impact drops the pack's blast", mp.replacesBaseExplosion());
        check("proximity keeps it", !mp.proximity.replacesBaseExplosion());
        check("proximity carries no burst", mp.proximity.penetration_damage != null
                && mp.proximity.penetration_damage == 0.0F);
        check("the two models are different objects", mp.proximity != mp);

        // Every other round must be untouched by the whole mechanism.
        // Two rounds carry the switchable fuze: the Abrams' multi-purpose HEAT and, since v60, the
        // Type 10's JM12A1 HEAT-MP. Both are real multi-purpose rounds with selectable fuzes, so
        // the rule is now "exactly these two" rather than "exactly one" - a third would mean a
        // round quietly gained proximity behaviour, which is what the original count guarded.
        ArmorConfig.Ammo jm12Multi = profile.ammo_penetration.get(
                "armor_pioneer:cannon_120mm_jm12");
        int shared = 0;
        for (ArmorConfig.Ammo ammo : profile.ammo_penetration.values()) {
            if (ammo.multiPurpose()) {
                shared++;
            } else if (ammo.damageModel(Fuze.DELAY) != ammo) {
                check("a round with no proximity model ignores the fuze", false);
            }
        }
        check("exactly two rounds have a switchable fuze in the defaults (the Abrams' HE-MP and "
                        + "the Type 10's JM12A1)", shared == 2);
        check("and the Type 10's JM12A1 is one of them",
                jm12Multi != null && jm12Multi.multiPurpose()
                        && jm12Multi.proximity != null
                        && jm12Multi.damageModel(Fuze.PROXIMITY) == jm12Multi.proximity);
    }

    private static void wireFormat() {
        System.out.println();
        System.out.println("=== messages ===");
        int[][] actions = {
                {GunnerActionMessage.CYCLE_FUZE, 0},
                {GunnerActionMessage.STEP_DELAY, 0},
                {GunnerActionMessage.SELECT_VARIANT, 4},
        };
        for (int[] action : actions) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            GunnerActionMessage.encode(new GunnerActionMessage(action[0], action[1]), buffer);
            // Counted before decoding: decoding consumes the buffer, so asking afterwards always
            // answers zero and would "pass" for a message of any size.
            int size = buffer.readableBytes();
            GunnerActionMessage back = GunnerActionMessage.decode(buffer);
            check("action " + action[0] + " survives the wire",
                    back.action() == action[0] && back.value() == action[1]);
            check("  and takes exactly two bytes (was " + size + ")", size == 2);
        }

        // Sent by the server, so every field has to come back: a delay that decodes as zero would
        // arm the shell with no countdown at all, and a lost quiet flag announces every login.
        int[][] cases = {{0, 10, 0, 0}, {1, 100, 0, 1}, {2, 50, 4, 0}, {0, 10, 4, 0}};
        for (int[] c : cases) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            GunnerStateMessage.encode(new GunnerStateMessage(c[0], c[1], c[2], c[3] != 0), buffer);
            int size = buffer.readableBytes();
            GunnerStateMessage back = GunnerStateMessage.decode(buffer);
            check("state " + c[0] + "/" + c[1] + "/load " + c[2] + " survives the wire",
                    back.fuze() == c[0] && back.delayTicks() == c[1] && back.variant() == c[2]
                            && back.quiet() == (c[3] != 0));
            check("  and takes exactly four bytes (was " + size + ")", size == 4);
        }

        // The whole delay range has to fit in the signed byte the message uses, and so does the
        // load index - which is why the state message is bytes rather than var-ints.
        check("the longest delay fits in a byte", GunnerSettings.DELAY_MAX_TICKS <= 127);
        check("the fuze count fits in a byte", Fuze.values().length <= 127);
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
