import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks the two things about a mixin that nothing else here can.
 *
 * <p><b>One: that Mixin will be able to transform it at all.</b> A mixin is the part of this addon
 * {@code javac} cannot check. Its promises are made in strings and annotations that Mixin resolves
 * while the game is starting, and a mistake is not a compile error and not a failing tool - it is
 * "broken mod state" and a crash report before the main menu. That is what happened here: a
 * rest-length array was copied by asking the array to copy itself, and <b>Mixin cannot rewrite a
 * method invoked on an array type</b>. It resolves the receiver's class by name, an array's name is
 * {@code [D}, there is no such class, and the whole mod fails to load. So this walks each mixin's
 * constant pool for a method reference whose owner is an array type.
 *
 * <p><b>Two: that no mixin is still reading a field that has moved.</b> This is the check that was
 * missing, and the failure it catches is the quietest one in the project. When a config block moves -
 * as {@code suspension} did at v44, off each vehicle's armour and into a map of its own - the old
 * field is kept behind so that files written before the move still parse, and it is marked
 * {@code @Deprecated}. {@code javac} answers a reader of a deprecated field with a <em>note</em>, not
 * an error, and the game answers it with nothing at all: the field is there, it is readable, and it
 * simply holds null for ever. A mixin left reading it compiles, ships, and returns a null settings
 * object for every vehicle in the game.
 *
 * <p>What that looked like in practice: the wheels were still being biased correctly, because the
 * other mixin had been updated - so the vehicle really did move when a key was pressed, and the
 * action bar really did report a number, because the reply is computed from what the press asked for
 * rather than from what the vehicle is doing. The only thing wrong was that <em>nothing was level</em>:
 * the pose fell through to a placeholder that reads {@code (0, 0)}, which on an axis counted up from
 * {@code -max_drop} is the bottom step, not the middle. Every vehicle sat 0.2 blocks low and 2
 * degrees nose-down.
 *
 * <p>The list below is the registry of moved fields. It is hand-written because there is nothing to
 * derive it from: {@code @Deprecated} does not say "I moved", and a field is only worth listing here
 * once something has actually been left behind. Add a line when a key moves, and the mixins will be
 * held to it.
 *
 * <p><b>What it deliberately does not check</b>, having tried: whether the {@code @Shadow} fields and
 * {@code @Inject} method names exist on their targets. Mixin keeps those annotations at
 * {@code CLASS} retention, so reflection cannot see them at all - {@code getAnnotation} returns null
 * and every mixin looks like it neither shadows nor injects anything. Reading them out of the class
 * file by hand is a real annotation parser, and it would be checking the one failure that is already
 * loud, immediate and specific ("@Shadow field x was not found in target"). This tool exists for the
 * failures that were none of those.
 *
 * <p>Usage: MixinCheck
 */
public class MixinCheck {

    private static int failures;

    private static final String MOD_ID = "ywzj_vehicle_armor";

    /**
     * Fields that have moved, as {owning class in the JVM's internal form, field name}.
     *
     * <p>Only the field that owns the move is listed, not the class that is allowed to read it - the
     * reader is always {@code ArmorConfig} itself, which needs the old field for the migration and
     * for {@code sanitize}. The rule this encodes is "the moved field has no readers outside the
     * config", which is what makes a relocation complete rather than nearly complete.
     */
    private static final String[][] MOVED_FIELDS = {
            // v43 -> v44: off VehicleArmor and into Profile#suspensions, see adoptSuspensions.
            {"com/ywzjvehicle/armor/ArmorConfig$VehicleArmor", "suspension"},
    };

    /** Constant pool tags this parser steps over, by how many bytes their body is. */
    private static final int TAG_UTF8 = 1;
    private static final int TAG_INTEGER = 3;
    private static final int TAG_FLOAT = 4;
    private static final int TAG_LONG = 5;
    private static final int TAG_DOUBLE = 6;
    private static final int TAG_CLASS = 7;
    private static final int TAG_STRING = 8;
    private static final int TAG_FIELD_REF = 9;
    private static final int TAG_METHOD_REF = 10;
    private static final int TAG_INTERFACE_METHOD_REF = 11;
    private static final int TAG_NAME_AND_TYPE = 12;
    private static final int TAG_METHOD_HANDLE = 15;
    private static final int TAG_METHOD_TYPE = 16;
    private static final int TAG_DYNAMIC = 17;
    private static final int TAG_INVOKE_DYNAMIC = 18;
    private static final int TAG_MODULE = 19;
    private static final int TAG_PACKAGE = 20;

    public static void main(String[] args) {
        try {
            listed();
        } catch (Throwable thrown) {
            // Printed rather than thrown on: a tool that answers a bad mixin with a Java stack trace
            // on stderr is answering in a language the person reading the run did not ask for, and
            // the runner's output is the only place this can be seen at all.
            System.out.println("  FAIL the check itself could not run: " + thrown);
            thrown.printStackTrace(System.out);
            failures++;
        }
        System.out.println();
        if (failures == 0) {
            System.out.println("RESULT: every listed mixin is in the jar, can be transformed, and "
                    + "reads no field that has moved");
        } else {
            System.out.println("RESULT: " + failures + " check(s) failed - see above");
            System.exit(1);
        }
    }

    /** For every mixin the config lists: it is in the jar, calls nothing on an array, and reads no moved field. */
    private static void listed() throws Exception {
        System.out.println("=== what Mixin will have to transform ===");
        String json = resource(MOD_ID + ".mixins.json");
        check("the mixins config was found", json != null);
        if (json == null) {
            return;
        }
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        String pkg = root.get("package").getAsString();
        Set<String> names = new LinkedHashSet<>();
        root.getAsJsonArray("mixins").forEach(element -> names.add(element.getAsString()));
        // The client-only list is part of the same config and is just as fatal when wrong, so it is
        // checked by the same rules. A client mixin that references a client-only class is exactly
        // why the two lists exist.
        int common = names.size();
        if (root.has("client")) {
            root.getAsJsonArray("client").forEach(element -> names.add(element.getAsString()));
        }
        System.out.println("  " + names.size() + " mixin(s) in " + pkg
                + " (" + (names.size() - common) + " client-only)");

        for (String name : names) {
            String path = pkg.replace('.', '/') + "/" + name + ".class";
            byte[] bytecode = bytes(path);
            check(name + " is in the jar", bytecode != null);
            if (bytecode == null) {
                continue;
            }
            Pool pool = Pool.read(bytecode);
            List<String> owners = arrayOwners(pool);
            check(name + " invokes nothing on an array type", owners.isEmpty());
            if (!owners.isEmpty()) {
                System.out.println("       array receiver(s): " + owners
                        + " - a call on an array cannot be transformed; use Arrays.copyOf or "
                        + "System.arraycopy, which are calls on a class");
            }

            List<String> moved = movedFieldReaders(pool);
            check(name + " reads no field that has moved", moved.isEmpty());
            if (!moved.isEmpty()) {
                System.out.println("       stale read(s): " + moved
                        + " - a moved field is kept only so old config files still parse. It is null "
                        + "at runtime, so a reader silently gets nothing. Point it at the new home "
                        + "instead.");
            }
        }
    }

    /**
     * The array types this class calls a method on, read out of its constant pool.
     *
     * <p>From the compiled class rather than from the source, because that is where the call actually
     * is: the pool holds the owning class in the JVM's own internal form, and an array's form there is
     * literally {@code [D} - a name no class can be found under.
     */
    private static List<String> arrayOwners(Pool pool) {
        List<String> owners = new ArrayList<>();
        for (String owner : pool.methodOwners) {
            if (owner != null && owner.startsWith("[")) {
                owners.add(owner);
            }
        }
        return owners;
    }

    /** @return every read of a field on {@link #MOVED_FIELDS}, spelled {@code owner.name}. */
    private static List<String> movedFieldReaders(Pool pool) {
        List<String> readers = new ArrayList<>();
        for (String[] ref : pool.fieldRefs) {
            for (String[] moved : MOVED_FIELDS) {
                if (moved[0].equals(ref[0]) && moved[1].equals(ref[1])) {
                    readers.add(ref[0] + "." + ref[1]);
                }
            }
        }
        return readers;
    }

    /**
     * A constant pool, resolved down to the names a check can ask about.
     *
     * <p>Resolving here rather than at each use because a reference is two levels deep: a field
     * reference names a {@code NameAndType}, which names the field. Walking that by hand at every
     * check would be the same code three times with three chances to get an index wrong.
     */
    static final class Pool {
        /** {@code owner.name} for every method reference, or null where the pool entry was not one. */
        final List<String> methodOwners = new ArrayList<>();
        /** {@code {owner, name}} for every field reference. */
        final List<String[]> fieldRefs = new ArrayList<>();
        /** Every UTF-8 entry, verbatim. Class names, descriptors, string literals. */
        private String[] utf8 = new String[0];

        private Pool() {
        }

        /**
         * @return whether this class names {@code text} anywhere in its constant pool - a class it
         *         references, a field or method descriptor that mentions one, or a string literal.
         *
         * <p>Searching the whole pool rather than the resolved references on purpose. A class that
         * only ever takes an optional mod's type as a <em>parameter</em>, or declares a field of it,
         * carries that type's name in a descriptor and may hold no {@code Class} entry at all - and
         * it is exactly as fatal without the mod present as one that calls a method on it.
         */
        boolean mentions(String text) {
            for (String entry : this.utf8) {
                if (entry != null && entry.contains(text)) {
                    return true;
                }
            }
            return false;
        }

        /** @return every UTF-8 entry, for a caller that has more than one thing to look for. */
        List<String> utf8Values() {
            List<String> values = new ArrayList<>();
            for (String entry : this.utf8) {
                if (entry != null) {
                    values.add(entry);
                }
            }
            return values;
        }

        static Pool read(byte[] bytecode) throws IOException {
            Pool pool = new Pool();
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytecode))) {
                if (in.readInt() != 0xCAFEBABE) {
                    return pool;
                }
                in.readUnsignedShort();                       // minor version
                in.readUnsignedShort();                       // major version
                int count = in.readUnsignedShort();
                String[] utf8 = pool.utf8 = new String[count];
                int[] className = new int[count];
                int[] refOwner = new int[count];
                int[] refNameType = new int[count];
                int[] nameTypeName = new int[count];
                // Which kind of reference each slot is; the two families are read identically and
                // told apart afterwards, which is why one pair of arrays holds both.
                boolean[] isField = new boolean[count];
                for (int i = 1; i < count; i++) {
                    int tag = in.readUnsignedByte();
                    if (tag == TAG_UTF8) {
                        utf8[i] = in.readUTF();
                    } else if (tag == TAG_INTEGER || tag == TAG_FLOAT) {
                        in.skipBytes(4);
                    } else if (tag == TAG_LONG || tag == TAG_DOUBLE) {
                        in.skipBytes(8);
                        i++;                                  // a long or a double takes two slots
                    } else if (tag == TAG_CLASS) {
                        className[i] = in.readUnsignedShort();
                    } else if (tag == TAG_STRING || tag == TAG_METHOD_TYPE
                            || tag == TAG_MODULE || tag == TAG_PACKAGE) {
                        in.skipBytes(2);
                    } else if (tag == TAG_METHOD_HANDLE) {
                        in.skipBytes(3);
                    } else if (tag == TAG_FIELD_REF || tag == TAG_METHOD_REF
                            || tag == TAG_INTERFACE_METHOD_REF) {
                        refOwner[i] = in.readUnsignedShort();
                        refNameType[i] = in.readUnsignedShort();
                        isField[i] = tag == TAG_FIELD_REF;
                    } else if (tag == TAG_NAME_AND_TYPE || tag == TAG_DYNAMIC
                            || tag == TAG_INVOKE_DYNAMIC) {
                        int name = in.readUnsignedShort();
                        in.skipBytes(2);
                        if (tag == TAG_NAME_AND_TYPE) {
                            nameTypeName[i] = name;
                        }
                    } else {
                        // A tag this parser does not know. Stopping is right: a guess about the length
                        // of an unknown entry would read every entry after it out of the wrong offset,
                        // and reporting a call site found that way is worse than reporting none.
                        System.out.println("  note  unknown constant pool tag " + tag
                                + " - stopped reading the pool");
                        return pool;
                    }
                }
                for (int i = 1; i < count; i++) {
                    if (refOwner[i] == 0) {
                        continue;
                    }
                    String owner = at(utf8, className[refOwner[i]]);
                    int nameType = refNameType[i];
                    String name = nameType > 0 && nameType < count
                            ? at(utf8, nameTypeName[nameType]) : null;
                    if (owner == null || name == null) {
                        continue;
                    }
                    if (isField[i]) {
                        pool.fieldRefs.add(new String[]{owner, name});
                    } else {
                        pool.methodOwners.add(owner);
                    }
                }
            }
            return pool;
        }

        private static String at(String[] utf8, int index) {
            return index > 0 && index < utf8.length ? utf8[index] : null;
        }
    }

    private static byte[] bytes(String path) throws IOException {
        try (InputStream stream = MixinCheck.class.getClassLoader().getResourceAsStream(path)) {
            return stream == null ? null : stream.readAllBytes();
        }
    }

    private static String resource(String path) {
        try (InputStream stream = MixinCheck.class.getClassLoader().getResourceAsStream(path)) {
            return stream == null ? null : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
