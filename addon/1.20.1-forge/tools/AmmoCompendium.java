import com.ywzjvehicle.armor.AmmoCategory;
import com.ywzjvehicle.armor.AmmoIndex;
import com.ywzjvehicle.armor.ArmorConfig;
import com.ywzjvehicle.armor.Pages;
import com.ywzjvehicle.armor.Warhead;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Prints the ammunition compendium offline: one line per configured round, called what the vehicle
 * pack calls it, with the equivalent penetration the addon measures it by.
 *
 * <p>This is the same pairing the in-game book makes at runtime, where the name is asked of the base
 * mod's weapon registry. Doing it here as well is not duplication for its own sake: the registry
 * lives in the running game, so without this the only way to find out that a configured id has no
 * weapon file behind it - a round that shows up in the book as a bare <code>vvp_ywzj:something</code>
 * and cannot be loaded by anything - would be to open the book and look.
 *
 * <p>Usage: AmmoCompendium [pack.zip ...]   (defaults to every pack in the install)
 */
public class AmmoCompendium {

    /** Where the base mod keeps its packs, under {@code MC_ROOT}. */
    private static final String PACK_DIR = "/versions/1.20.1-Forge_47.4.2/limitless_vehicle";

    private static int failures;

    /**
     * The page sizes the paging walk is run at.
     *
     * <p>A spread rather than one number, because the failures this looks for are off-by-ones and
     * they only appear at particular remainders: a list of twenty-five rows divides evenly by five
     * and unevenly by four, and an arithmetic slip can be right about one and wrong about the other.
     * The extremes matter too - one row a page is every page a single row, and forty is the whole
     * book at once, and both have to still touch everything exactly once.
     */
    private static final int[] PAGE_SIZES = {1, 4, 7, 12, 40};

    public static void main(String[] args) throws Exception {
        List<Path> packs = resolvePacks(args);
        System.out.println("packs: " + packs.size());
        for (Path pack : packs) {
            System.out.println("  " + pack.getFileName());
        }

        Method defaultProfile = ArmorConfig.class.getDeclaredMethod("defaultProfile");
        defaultProfile.setAccessible(true);
        ArmorConfig.Profile profile = (ArmorConfig.Profile) defaultProfile.invoke(null);

        // A load is not always a different *mark* of the same round - the 125mm high-explosive slot
        // carries a second shell whose penetration is identical and whose blast is not, so what makes
        // it a different load would be invisible if the line showed penetration alone. Hence the
        // damage fields beside it: a load prints whatever it changes, and nothing where it changes
        // nothing.
        record Load(String name, float penetration, Float blast, Float spallMin, Float spallMax,
                    ArmorConfig.Ammo slot) {
            String detail() {
                List<String> parts = new ArrayList<>();
                // Only what the load changes. A load that prints the slot's own numbers back says
                // nothing and reads as though it differed there.
                if (blast != null && !blast.equals(slot.penetration_damage)) {
                    parts.add("blast " + trim(blast));
                }
                if (spallMin != null && spallMax != null
                        && (!spallMin.equals(slot.spall_damage_min)
                                || !spallMax.equals(slot.spall_damage_max))) {
                    parts.add("spall " + trim(spallMin) + "-" + trim(spallMax));
                }
                return String.join(", ", parts);
            }
        }
        record Row(String id, String name, AmmoCategory category, float penetration,
                   Float proximity, java.util.List<Load> loads, boolean found) {
        }
        List<Row> rows = new ArrayList<>();
        int missing = 0;
        int taczRows = 0;

        List<ZipFile> zips = new ArrayList<>();
        try {
            for (Path pack : packs) {
                zips.add(new ZipFile(pack.toFile()));
            }
            for (var entry : profile.ammo_penetration.entrySet()) {
                String id = entry.getKey();
                ArmorConfig.Ammo ammo = entry.getValue();
                // The namespace comes from the id, not from the pack: the config holds rounds from
                // every pack at once, and each id says where its own file is.
                String path = "data/" + id.substring(0, id.indexOf(':')) + "/weapons/"
                        + id.substring(id.indexOf(':') + 1) + ".json";
                ZipFile owner = null;
                ZipEntry file = null;
                for (ZipFile zip : zips) {
                    ZipEntry found = zip.getEntry(path);
                    if (found != null) {
                        owner = zip;
                        file = found;
                        break;
                    }
                }
                String name = owner == null ? id : nameOf(owner, file);
                if (file == null) {
                    missing++;
                }
                java.util.List<Load> loads = new java.util.ArrayList<>();
                if (ammo.variants != null) {
                    for (ArmorConfig.Ammo variant : ammo.variants) {
                        loads.add(new Load(variant.name == null ? "?" : variant.name,
                                variant.penetration, variant.penetration_damage,
                                variant.spall_damage_min, variant.spall_damage_max, ammo));
                    }
                }
                rows.add(new Row(id, name, ammo.shelf(), ammo.penetration,
                        ammo.proximity == null ? null : ammo.proximity.penetration, loads,
                        file != null));
            }

            // The gun mod's cartridges, on the same pages: with TACZ installed the book lists them
            // too, so the counts the paging walk uses have to include them or the two disagree
            // about how long the list a page is cut from actually is. They have no weapon file of
            // their own - they are cartridges, not vehicle weapons - hence found = true.
            for (var entry : AmmoIndex.taczEntries()) {
                rows.add(new Row(entry.id(), entry.name(), entry.category(), entry.penetration(),
                        entry.proximity(), java.util.List.of(), true));
                taczRows++;
            }
        } finally {
            for (ZipFile zip : zips) {
                zip.close();
            }
        }

        // The order the book uses: warhead by warhead, weapon class within each, hardest-hitting
        // first within that. Printing the index first is the point of the tool as much as the rounds
        // are - a round filed on the wrong warhead is only visible by reading which one it came out
        // under.
        rows.sort(Comparator.comparingInt((Row row) -> row.category().warhead().ordinal())
                .thenComparingInt(row -> row.category().weapon().ordinal())
                .thenComparing(Comparator.comparingDouble(Row::penetration).reversed())
                .thenComparing(Row::name));

        System.out.println();
        System.out.println("index (what the first page lists):");
        for (Warhead warhead : Warhead.values()) {
            long count = rows.stream().filter(row -> row.category().warhead() == warhead).count();
            System.out.printf("  %-24s %2d%n", warhead.id(), count);
        }

        List<AmmoCategory> shelves = rows.stream().map(Row::category).toList();

        System.out.println();
        Warhead lastWarhead = null;
        AmmoCategory lastShelf = null;
        for (Row row : rows) {
            if (row.category().warhead() != lastWarhead) {
                lastWarhead = row.category().warhead();
                lastShelf = null;
                System.out.println();
                System.out.println("### " + lastWarhead.id());
            }
            // The weapon heading, printed only where a warhead has more than one kind of gun behind
            // it - the same rule the book draws its group headings by, called rather than copied, so
            // the two cannot end up disagreeing about what a page looks like.
            if (AmmoCategory.groupsByWeapon(lastWarhead, shelves) && row.category() != lastShelf) {
                lastShelf = row.category();
                System.out.println();
                System.out.printf("  [%s]%n", lastShelf.id());
                System.out.printf("  %-30s %12s  %s%n", "name", "penetration", "id");
                System.out.println("  " + "-".repeat(84));
            }
            String value = row.proximity() == null
                    ? trim(row.penetration()) + "mm"
                    : trim(row.penetration()) + " / " + trim(row.proximity()) + "mm";
            System.out.printf("  %-30s %12s  %s%s%n", row.name(), value, row.id(),
                    row.found() ? "" : "   <- NO WEAPON FILE");
            // Indented under the slot they belong to, exactly as the book lists them: a load is not
            // a round of its own, and printing it as one would claim the Abrams carries five kinds
            // of shell when it carries one with five marks.
            for (var load : row.loads()) {
                System.out.printf("    %-28s %12s  %s%n", load.name(), trim(load.penetration()) + "mm",
                        load.detail());
            }
        }
        System.out.println();
        System.out.println((rows.size() - taczRows) + " vehicle round(s) + " + taczRows
                + " TACZ cartridge(s); " + (missing == 0
                ? "every vehicle round has a weapon file in the pack"
                : missing + " vehicle round(s) have NO weapon file and can never be fired"));

        // Rows per warhead, plus the way back to the index, is all the paging check needs - and it
        // is the same count the screen pages, so the two cannot disagree about how long the list a
        // page is cut from actually is.
        int[] roundsPerWarhead = new int[Warhead.values().length];
        for (Row row : rows) {
            roundsPerWarhead[row.category().warhead().ordinal()]++;
        }
        paging(roundsPerWarhead);

        // Not a failure: a config entry for a weapon the pack does not ship is a setting that can
        // never do anything, which is worth reporting and not worth refusing to build over.
        if (failures > 0) {
            System.out.println("RESULT: " + failures + " paging check(s) failed - see above");
            System.exit(1);
        }
        if (missing > 0) {
            System.out.println("RESULT: " + missing + " configured round(s) are unreachable");
        } else {
            System.out.println("RESULT: the compendium matches the pack");
        }
    }

    /**
     * Every row reachable, exactly once, at every page size - and the page keys stopping at the ends.
     *
     * <p>The failure this is for has no symptom. A page of thirty rows does not look like one with a
     * row missing, and an off-by-one at a page boundary either drops the row that straddles it or
     * shows it on both pages - neither of which anybody notices unless they happen to be looking for
     * that particular round. So the check is not "the arithmetic looks right": it is that walking
     * from the first page to the last touches every row once, in order, with nothing skipped and
     * nothing repeated.
     *
     * <p>Rows are counted with the way back to the index included, because that is a row of the same
     * list - the screen builds them together and pages the result, rather than pinning the way out
     * above the page. Worth stating, since it is the sort of thing that would otherwise be counted
     * one way here and another way there.
     */
    private static void paging(int[] roundsPerWarhead) {
        System.out.println();
        System.out.println("=== paging ===");
        for (Warhead warhead : Warhead.values()) {
            int content = roundsPerWarhead[warhead.ordinal()];
            int total = content + 1;
            StringBuilder counts = new StringBuilder();
            for (int perPage : PAGE_SIZES) {
                int pages = Pages.count(total, perPage);
                List<Integer> seen = new ArrayList<>();
                for (int p = 0; p < pages; p++) {
                    for (int i = Pages.first(p, total, perPage); i < Pages.end(p, total, perPage); i++) {
                        seen.add(i);
                    }
                }
                // Every row once, in order. `seen.get(i) == i` covers all three at once: a duplicate
                // shifts a later entry out of place, a gap leaves one too low, and a row that is
                // simply never shown leaves the list short - which the size test catches.
                boolean complete = seen.size() == total;
                boolean inOrder = true;
                for (int i = 0; i < seen.size(); i++) {
                    if (seen.get(i) != i) {
                        inOrder = false;
                    }
                }
                check(warhead.id() + ", " + perPage + " rows a page: all " + total
                        + " reachable once, in order", complete && inOrder);
                counts.append(" ").append(perPage).append("->").append(pages);
            }
            System.out.println("  " + warhead.id() + ": " + content + " round(s)" + counts);
        }

        // The keys stop at the ends rather than wrapping round, which is the whole difference
        // between a pair of arrow keys and a single cycling one: holding "page down" at the end and
        // being thrown back to the first page is the one thing a directional key must not do.
        check("page down at the end stays at the end",
                Pages.turn(2, 30, 12, 1) == 2 && Pages.turn(0, 30, 12, -1) == 0);
        check("and page up at the start stays at the start",
                Pages.turn(0, 30, 12, -1) == 0);
        check("turning moves exactly one page",
                Pages.turn(0, 30, 12, 1) == 1 && Pages.turn(1, 30, 12, 1) == 2);

        // Degenerate inputs, because the window height is a number the player controls and a panel
        // one pixel tall divides out to nothing.
        check("a window too short for one row still shows a page",
                Pages.perPage(0, 11) == 1 && Pages.perPage(-40, 11) == 1 && Pages.perPage(5, 11) == 1);
        check("a book with nothing in it is one empty page, not none",
                Pages.count(0, 11) == 1 && Pages.end(0, 0, 11) == 0);
        check("and a page index from a larger window is brought back into range",
                Pages.clamp(9, 5, 11) == 0 && Pages.first(9, 5, 11) == 0);
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }

    /**
     * The packs to read: the ones named on the command line, or every pack installed.
     *
     * <p>Every one of them, not the one a round is expected to be in. The config holds weapons from
     * all the packs at once, and this tool exists to say when a configured round has no weapon file
     * behind it - so a run that opened one pack would report every round belonging to the other as
     * unreachable, which is precisely the alarm it is meant to raise, raised falsely.
     */
    private static List<Path> resolvePacks(String[] args) throws Exception {
        List<Path> packs = new ArrayList<>();
        if (args.length > 0) {
            for (String arg : args) {
                packs.add(PackLocator.asZip(Path.of(arg)));
            }
            return packs;
        }
        packs.addAll(PackLocator.allPackZips());
        if (packs.isEmpty()) {
            packs.add(PackLocator.asZip(Path.of(System.getenv().getOrDefault("VVP_PACK",
                    "E:/.minecraft" + PACK_DIR + "/vvp_0.9.9_0.6.0+(1)(1).zip"))));
        }
        return packs;
    }

    /** Pulls the "name" value out of a weapon file without pretending to be a JSON parser. */
    private static String nameOf(ZipFile zip, ZipEntry file) throws Exception {
        String text;
        try (var stream = zip.getInputStream(file)) {
            text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        int key = text.indexOf("\"name\"");
        if (key < 0) {
            return "(unnamed)";
        }
        int open = text.indexOf('"', text.indexOf(':', key) + 1);
        int close = text.indexOf('"', open + 1);
        return open < 0 || close < 0 ? "(unnamed)" : text.substring(open + 1, close);
    }

    private static String trim(float value) {
        return value == Math.round(value) ? String.valueOf(Math.round(value))
                : String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
