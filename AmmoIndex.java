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
        entries.sort(Comparator.comparingInt((Entry entry) -> entry.category().warhead().ordinal())
                .thenComparingInt(entry -> entry.category().weapon().ordinal())
                .thenComparing(Comparator.comparingDouble(Entry::penetration).reversed())
                .thenComparing(Entry::name));
        return entries;
    }

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
