package com.ywzjvehicle.armor;

import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.fml.ModList;

/**
 * The only place in this addon that names TACZ.
 *
 * <p>TACZ - Timeless and Classics Zero, the gun mod - is an <b>optional</b> dependency. This addon
 * loads, and every other feature works, with it nowhere on the disk. That makes every reference to
 * {@code com.tacz.*} a loaded gun pointed at players who do not have it: a class that mentions a
 * missing type fails the moment the JVM loads it, which is before the main menu, with a
 * {@code NoClassDefFoundError} that names neither this addon's intent nor the mod that is absent.
 *
 * <p>So the references live here and nowhere else, and {@code TaczCheck} holds that to it by walking
 * every compiled class's constant pool. That check is the reason this class is worth a file of its
 * own: a rule that only one class may name TACZ is worth nothing unless something enforces it, and
 * the failure it prevents cannot be reproduced on a machine that has TACZ installed.
 *
 * <h2>Why the TACZ-touching methods are in a nested class</h2>
 *
 * <p>Because {@code loaded()} answering false is not enough on its own. A method that begins
 * {@code if (!loaded()) return null;} and <em>then</em> does {@code instanceof EntityKineticBullet}
 * still has that class named in its constant pool, and whether the JVM resolves it eagerly - while
 * verifying the method, before the first line runs - is a question about the verifier rather than
 * about this code. {@link Hook} sidesteps the question: the outer class names no TACZ type at all,
 * so loading it is always safe, and the nested class is loaded lazily on the first execution of one
 * of its methods, which every caller reaches only after {@link #loaded()} has said yes.
 *
 * <p>The one thing this class does <em>not</em> do is decide what counts as a TACZ hit. That is done
 * off the vanilla damage type ({@code tacz:bullet}) in {@code ArmorSystem}, which needs no TACZ type
 * to read - see {@code ArmorSystem.isTaczHit}. This class's whole job is turning a bullet entity
 * into a cartridge id.
 */
public final class TaczBridge {

    private static final String MOD_ID = "tacz";

    /** Cached, and nullable so "not asked yet" is distinguishable from "no". */
    private static Boolean loaded;

    private TaczBridge() {
    }

    /** @return whether TACZ is on this instance at all. Never touches a TACZ type. */
    public static boolean loaded() {
        if (loaded == null) {
            try {
                loaded = ModList.get() != null && ModList.get().isLoaded(MOD_ID);
            } catch (Throwable absent) {
                // Asked too early, or somewhere with no mod list at all - a headless tool, or an
                // event that runs before Forge has finished. "Not loaded" is the safe answer: it
                // turns this addon's TACZ handling off rather than crashing on the way to finding out.
                loaded = Boolean.FALSE;
            }
        }
        return loaded;
    }

    /**
     * @return the cartridge a TACZ bullet was fired with - {@code tacz:50bmg}, {@code tacz:762x39} -
     *         or {@code null} when the entity is not a TACZ bullet at all.
     *
     * <p>The ammunition, not the gun: penetration is a property of the round, and the same cartridge
     * out of a longer barrel is the same hole in the same plate. Guns differ in velocity and in how
     * many of them arrive per minute, which is TACZ's business and not this addon's.
     */
    public static ResourceLocation ammoIdOf(Entity entity) {
        return loaded() && entity != null ? Hook.ammoId(entity) : null;
    }

    /** @return the gun that fired it, for the log line only, or {@code null}. */
    public static ResourceLocation gunIdOf(Entity entity) {
        return loaded() && entity != null ? Hook.gunId(entity) : null;
    }

    /**
     * Everything that names a TACZ type.
     *
     * <p>Loaded only after {@link #loaded()} has already answered yes. Nothing outside this class
     * refers to it, so the outer class can be loaded, verified and called on a machine with no TACZ.
     */
    private static final class Hook {

        private Hook() {
        }

        static ResourceLocation ammoId(Entity entity) {
            return entity instanceof EntityKineticBullet bullet ? bullet.getAmmoId() : null;
        }

        static ResourceLocation gunId(Entity entity) {
            return entity instanceof EntityKineticBullet bullet ? bullet.getGunId() : null;
        }
    }
}
