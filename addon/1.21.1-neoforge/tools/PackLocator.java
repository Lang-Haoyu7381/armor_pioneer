import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Finds the installed vehicle packs and hands them to the checks as zips.
 *
 * <p>The base mod loads two shapes from {@code limitless_vehicle}: a zip, and a directory whose
 * root holds {@code vehicle_pack.meta.json}. The second shape is what the player edits, so a tool
 * that only walked {@code *.zip} would quietly stop seeing a pack the moment it was unpacked - and
 * every check would keep passing against the stale zip it found instead.
 *
 * <p>The checks read pack entries through {@link ZipFile}, which cannot open a directory. Rather
 * than rewrite every reader around a new abstraction, a directory is snapshotted into a zip under
 * the system temp directory and that snapshot's path is returned. The snapshot is keyed by the
 * directory's file count, total size and newest modification time, so it is rebuilt after an edit
 * while repeated runs inside one verification pass share it.
 */
public final class PackLocator {

    /**
     * Where the base mod keeps its packs, under {@code MC_ROOT}.
     *
     * <p>The instance comes from {@code MC_INSTANCE}, the same variable {@code LeopardAddonCheck}
     * uses: this copy serves the 1.21.1 tree, whose {@code verify1211_addon.ps1} sets it, and a
     * hard-coded 1.20.1 path would silently check a pack the game is not loading.
     */
    private static String packDir() {
        String instance = System.getenv().getOrDefault("MC_INSTANCE", "1.20.1-Forge_47.4.2");
        return "/versions/" + instance + "/limitless_vehicle";
    }

    private PackLocator() {
    }

    public static Path dir() {
        return Path.of(System.getenv().getOrDefault("MC_ROOT", "E:\\.minecraft") + packDir());
    }

    /**
     * The pack named on the command line, or the installed pack with this namespace.
     *
     * <p>A command-line argument may be either shape. The namespace is read from the pack's own
     * metadata rather than assumed from its file name, because a folder pack is free to be called
     * anything and the integration id lives in {@code vehicle_pack.meta.json}.
     */
    public static Path of(String[] args, String namespace) throws IOException {
        if (args.length > 0) {
            return asZip(Path.of(args[0]));
        }
        return find(namespace);
    }

    /** @return the installed pack whose metadata declares this namespace, as a readable zip. */
    public static Path find(String namespace) throws IOException {
        for (Path zip : allPackZips()) {
            String found = namespaceOf(zip);
            if (namespace.equals(found)) {
                return zip;
            }
        }
        throw new IllegalStateException(
                "no installed vehicle pack with namespace " + namespace + " under " + dir());
    }

    /**
     * Every loadable pack in the installation, sorted, with directory packs snapshotted.
     *
     * <p>Sorted then filtered the same way whoever calls it: the base mod scans this directory
     * without an order guarantee, but a check that prints packs in a different order every run is
     * a check whose diff nobody reads.
     */
    public static List<Path> allPackZips() throws IOException {
        List<Path> packs = new ArrayList<>();
        Path dir = dir();
        if (!Files.isDirectory(dir)) {
            return packs;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path path : (Iterable<Path>) stream.sorted()::iterator) {
                String name = path.getFileName().toString();
                if (Files.isDirectory(path)) {
                    if (Files.isRegularFile(path.resolve("vehicle_pack.meta.json"))) {
                        packs.add(asZip(path));
                    }
                } else if (name.endsWith(".zip")) {
                    packs.add(path);
                }
            }
        }
        return packs;
    }

    /**
     * @return the entry itself when it is a zip, or a zip snapshot when it is a pack directory.
     *
     * <p>Snapshots are cached by a cheap signature - count, total bytes and the newest mtime - and
     * written through a temporary file so a half-written snapshot is never opened by another tool.
     */
    public static Path asZip(Path entry) throws IOException {
        if (!Files.isDirectory(entry)) {
            if (!Files.isRegularFile(entry)) {
                throw new IOException("no pack at " + entry);
            }
            return entry;
        }
        if (!Files.isRegularFile(entry.resolve("vehicle_pack.meta.json"))) {
            throw new IOException("not a vehicle pack (no vehicle_pack.meta.json): " + entry);
        }
        Path cache = Path.of(System.getProperty("java.io.tmpdir"), "ywzj_vehicle_pack_cache");
        Files.createDirectories(cache);
        String leaf = entry.getFileName().toString();
        Path snapshot = cache.resolve(leaf + "-" + signature(entry) + ".zip");
        if (Files.isRegularFile(snapshot)) {
            return snapshot;
        }

        Path temp = Files.createTempFile(cache, leaf + "-", ".part");
        try {
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(temp));
                 Stream<Path> walk = Files.walk(entry)) {
                for (Path file : (Iterable<Path>) walk.sorted()::iterator) {
                    if (!Files.isRegularFile(file)) {
                        continue;
                    }
                    ZipEntry zipEntry = new ZipEntry(
                            entry.relativize(file).toString().replace('\\', '/'));
                    out.putNextEntry(zipEntry);
                    Files.copy(file, out);
                    out.closeEntry();
                }
            }
            Files.move(temp, snapshot, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        prune(cache, leaf, snapshot);
        return snapshot;
    }

    private static String namespaceOf(Path zip) throws IOException {
        try (ZipFile file = new ZipFile(zip.toFile())) {
            ZipEntry entry = file.getEntry("vehicle_pack.meta.json");
            if (entry == null) {
                return null;
            }
            try (InputStream in = file.getInputStream(entry)) {
                JsonObject meta = JsonParser.parseString(
                        new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                return meta.has("namespace") ? meta.get("namespace").getAsString() : null;
            }
        }
    }

    private static String signature(Path folder) throws IOException {
        long count = 0;
        long size = 0;
        long newest = 0;
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                count++;
                size += Files.size(path);
                newest = Math.max(newest, Files.getLastModifiedTime(path).toMillis());
            }
        }
        return Long.toHexString(count) + "-" + Long.toHexString(size) + "-"
                + Long.toHexString(newest);
    }

    /** Drops older snapshots of the same pack; the temp directory is not a history. */
    private static void prune(Path cache, String leaf, Path keep) {
        try (Stream<Path> stream = Files.list(cache)) {
            stream.filter(path -> path.getFileName().toString().startsWith(leaf + "-"))
                    .filter(path -> !path.equals(keep))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // Another check may still have the old snapshot open on Windows.
                        }
                    });
        } catch (IOException ignored) {
            // A cache that cannot be pruned is still a usable cache.
        }
    }
}
