package com.ywzjvehicle.armor;

import net.minecraft.resources.ResourceLocation;
import org.ywzj.vehicle.api.custom.IVehicleWeaponManager;
import org.ywzj.vehicle.custom.CommonAssetsManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The list of rounds the compendium shows: what each one is called, and what it can get through.
 *
 * <p>The names come from the vehicle pack rather than from this addon's config, so that the book and
 * the weapon selector cannot disagree about what a shell is called. What this addon knows is the
 * penetration; what the pack knows is the name; asking each for its own half is the only way the two
 * stay in step without a second copy to maintain.
 *
 * <p>Kept out of the screen so it can be read on either side - nothing here names a client class,
 * and the fallback below is what a dedicated server would get if anything ever asked it.
 */
public final class AmmoIndex {

    private AmmoIndex() {
    }

    /**
     * One line of the compendium.
     *
     * @param id         the weapon id, which is what the config is keyed by
     * @param name       what the pack calls it, or the id when the pack cannot be asked
     * @param category   the shelf it is filed under
     * @param penetration equivalent penetration in millimetres
     * @param proximity  the second model's penetration for a round with a switchable fuze, or
     *                   {@code null} for everything else
     */
    public record Entry(String id, String name, AmmoCategory category, float penetration,
                        Float proximity, List<Load> loads) {

        /** @return whether this round's fuze can be switched, so the book can say so. */
        public boolean switchable() {
            return proximity != null;
        }

        /** @return whether this slot can be filled with more than the round the pack names it for. */
        public boolean selectable() {
            return !loads.isEmpty();
        }
    }

    /**
     * One of the other marks a slot can be loaded with.
     *
     * @param name        its designation
     * @param penetration equivalent penetration in millimetres
     */
    public record Load(String name, float penetration) {
    }

    /**
     * Every configured round, in the order the book reads them: warhead by warhead, weapon class
     * within each, and hardest-hitting first within that.
     *
     * <p>The two axes are ordered by their own declarations rather than here - {@link Warhead} and
     * {@link WeaponClass} list themselves in the order the book shows them.
     */
    public static List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<String, ArmorConfig.Ammo> e : ArmorConfig.ammunition().entrySet()) {
            // Machine-gun rounds belong here too: they carry a flat multiplier rather than a
            // per-plate one, but they still have a penetration, and the book is a list of what
            // gets through what.
            ArmorConfig.Ammo ammo = e.getValue();
            List<Load> loads = new ArrayList<>();
            if (ammo.variants != null) {
                for (ArmorConfig.Ammo variant : ammo.variants) {
                    loads.add(new Load(variant.name == null ? "?" : variant.name,
                            variant.penetration));
                }
            }
            entries.add(new Entry(e.getKey(), displayName(e.getKey()), ammo.shelf(),
                    ammo.penetration,
                    ammo.proximity == null ? null : ammo.proximity.penetration, loads));
        }
        // The gun mod's cartridges, but only on a client that has the gun mod. A book that listed
        // rounds the player cannot find a gun for would be a list of figures rather than of
        // ammunition, and the config keeps the table whether TACZ is installed or not.
        if (TaczBridge.loaded()) {
            entries.addAll(taczEntries());
        }
        entries.sort(ORDER);
        return entries;
    }

    /**
     * The order the book reads: warhead by warhead, weapon class within each, hardest-hitting first
     * within that. Shared by both sources so a TACZ cartridge and a tank shell cannot end up in
     * different orders on the same page.
     */
    private static final Comparator<Entry> ORDER =
            Comparator.comparingInt((Entry entry) -> entry.category().warhead().ordinal())
                    .thenComparingInt(entry -> entry.category().weapon().ordinal())
                    .thenComparing(Comparator.comparingDouble(Entry::penetration).reversed())
                    .thenComparing(Entry::name);

    /**
     * The gun mod's cartridges as compendium rows.
     *
     * <p>Separate from {@link #entries()} so the offline checks can read it without a client, and
     * so the one place that decides "only when TACZ is installed" is the caller above.
     */
    public static List<Entry> taczEntries() {
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<String, ArmorConfig.Ammo> e : ArmorConfig.taczRounds().entrySet()) {
            ArmorConfig.Ammo ammo = e.getValue();
            List<Load> loads = new ArrayList<>();
            if (ammo.variants != null) {
                for (ArmorConfig.Ammo variant : ammo.variants) {
                    loads.add(new Load(variant.name == null ? "?" : variant.name,
                            variant.penetration));
                }
            }
            entries.add(new Entry(e.getKey(), taczDesignation(e.getKey()), smallArmsShelf(ammo),
                    ammo.penetration,
                    ammo.proximity == null ? null : ammo.proximity.penetration, loads));
        }
        entries.sort(ORDER);
        return entries;
    }

    /**
     * @return the small-arms shelf for a TACZ cartridge.
     *
     * <p>An explicit {@code category} in the config wins, so a hand-added round can be filed where
     * its owner wants it; otherwise the warhead picks the shelf. It is deliberately not
     * {@link ArmorConfig.Ammo#shelf()}: a cartridge with no category would land on the cannon
     * shelf, because that is the default for a round the config does not classify.
     */
    private static AmmoCategory smallArmsShelf(ArmorConfig.Ammo ammo) {
        if (ammo.category != null) {
            AmmoCategory named = AmmoCategory.byId(ammo.category);
            if (named != null && named.weapon() == WeaponClass.SMALL_ARMS) {
                return named;
            }
        }
        return switch (AmmoCategory.derivedFrom(ammo.profile).warhead()) {
            case HEAT -> AmmoCategory.SMALL_ARMS_HEAT;
            case HE -> AmmoCategory.SMALL_ARMS_HE;
            case AP -> AmmoCategory.SMALL_ARMS_AP;
        };
    }

    /**
     * How a TACZ cartridge is written in the book.
     *
     * <p>Not asked of the weapon registry: a cartridge is not a weapon file, and TACZ has no
     * display name for one. These are the designations themselves, neutral in both languages, and
     * an id with no entry falls back to its own name rather than disappearing - a cartridge added
     * by a gun pack the addon has not seen before still reaches the book.
     */
    private static String taczDesignation(String id) {
        String key = id.startsWith("tacz:") ? id.substring("tacz:".length()) : id;
        String named = TACZ_DESIGNATIONS.get(key);
        return named != null ? named : key;
    }

    /** The designations of the cartridges TACZ ships. */
    private static final Map<String, String> TACZ_DESIGNATIONS = Map.ofEntries(
            Map.entry("22wmr", ".22 WMR"),
            Map.entry("9mm", "9×19 mm"),
            Map.entry("45acp", ".45 ACP"),
            Map.entry("357mag", ".357 Magnum"),
            Map.entry("45_70", ".45-70"),
            Map.entry("50ae", ".50 AE"),
            Map.entry("500mag", ".500 S&W Magnum"),
            Map.entry("762x25", "7.62×25 mm"),
            Map.entry("46x30", "4.6×30 mm"),
            Map.entry("57x28", "5.7×28 mm"),
            Map.entry("545x39", "5.45×39 mm"),
            Map.entry("556x45", "5.56×45 mm"),
            Map.entry("762x39", "7.62×39 mm"),
            Map.entry("58x42", "5.8×42 mm"),
            Map.entry("68x51fury", "6.8×51 mm Fury"),
            Map.entry("300blk", "7.62×35 mm (.300 BLK)"),
            Map.entry("308", "7.62×51 mm"),
            Map.entry("30_06", ".30-06 Springfield"),
            Map.entry("792x57", "7.92×57 mm"),
            Map.entry("762x54", "7.62×54 mmR"),
            Map.entry("338", ".338 Lapua Magnum"),
            Map.entry("50bmg", "12.7×99 mm (.50 BMG)"),
            Map.entry("12g", "12 Gauge"),
            Map.entry("40mm", "40 mm"),
            Map.entry("rpg_rocket", "RPG-7 rocket"));

    /** @return the rounds of one warhead, in the order {@link #entries()} puts them. */
    public static List<Entry> entriesWith(Warhead warhead) {
        List<Entry> found = new ArrayList<>();
        for (Entry entry : entries()) {
            if (entry.category().warhead() == warhead) {
                found.add(entry);
            }
        }
        return found;
    }

    /** @return every shelf the given rounds are filed under, for {@link AmmoCategory#groupsByWeapon}. */
    public static List<AmmoCategory> shelvesOf(List<Entry> entries) {
        List<AmmoCategory> shelves = new ArrayList<>();
        for (Entry entry : entries) {
            shelves.add(entry.category());
        }
        return shelves;
    }

    /**
     * What the vehicle pack calls a round, falling back to its id whenever the pack cannot answer.
     *
     * <p>The fallback is not an error path - the weapon data is sent to the client over the network
     * and may simply not have arrived yet - but it is worth knowing that an id showing in the book
     * is exactly what "the pack could not be asked" looks like.
     */
    public static String displayName(String id) {
        try {
            IVehicleWeaponManager manager = CommonAssetsManager.vehicleWeaponManager();
            if (manager == null) {
                return id;
            }
            ResourceLocation key = new ResourceLocation(id);
            return manager.getIndex(key)
                    .map(index -> index.data())
                    .map(data -> data.getName())
                    // The data class defaults to this translation key when a weapon file names
                    // nothing; showing it would be worse than showing the id.
                    .filter(name -> name != null && !name.isBlank()
                            && !name.startsWith("vehicle.weapon"))
                    .orElse(id);
        } catch (RuntimeException e) {
            // A compendium that cannot name a round is still a compendium; one that throws while
            // the player is holding it is a crash.
            return id;
        }
    }
}
