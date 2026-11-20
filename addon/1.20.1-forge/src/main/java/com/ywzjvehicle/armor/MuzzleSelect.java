package com.ywzjvehicle.armor;

import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.ywzj.vehicle.vehicle.pojo.AimContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Picks the real muzzle out of a weapon unit's bolts.
 *
 * <p>A weapon unit derives its bolts from the structure model: one bolt per cube of the
 * {@code <bone>_barrel} bone. That is correct for a model whose barrel is one cube, and wrong for
 * a model whose barrel is split into a rear sleeve and a front tube. The VT-4's
 * {@code turret_barrel} is exactly that - a 30-unit rear cube and a 99-unit front cube - so the
 * unit ends up with two bolts: one whose muzzle is about three blocks from the turret and one at
 * the real muzzle eleven blocks out. {@code getCurrentBolt()} advances through them, so every
 * other shot fires (and every other effect plays) from the <em>rear</em> bolt. The visible result
 * is a muzzle flash at the gun root about half the time.
 *
 * <p>This class does not care which bolt is current. It projects every muzzle onto the firing
 * direction and treats the furthest one as the barrel's mouth. Two genuinely different barrels -
 * a twin mount, say - sit at the same projection and are both kept; a rear sleeve is behind the
 * front tube and is dropped.
 *
 * <p>The base mod's own effects are remapped rather than cancelled: {@link #remapRear} returns a
 * list of the same length, with each rear artifact's position replaced by the front muzzle. That
 * keeps {@code countFire(aimContexts.size())} and the number of smoke puffs calculated by the base
 * exactly as they were, while the puffs themselves land in the right place.
 *
 * <p>Common code, no client classes: the selection is testable without a game, and the client
 * effect and the base mixins both call it.
 */
public final class MuzzleSelect {

    /** How far behind the front muzzle a bolt may sit and still count as a real muzzle. */
    public static final double TOLERANCE = 1.0;

    private MuzzleSelect() {
    }

    /** The front-most contexts, one per real muzzle. Used by the addon's own effects. */
    public static List<AimContext> frontMost(List<AimContext> contexts, Vec3 origin, double tolerance) {
        List<AimContext> usable = usable(contexts);
        if (usable.isEmpty()) {
            return List.of();
        }
        Vec3 fallback = fallbackDirection(usable);
        double max = maxProjection(usable, origin, fallback);
        List<AimContext> front = new ArrayList<>();
        for (AimContext context : usable) {
            if (projection(context, origin, fallback) >= max - tolerance) {
                front.add(context);
            }
        }
        return front;
    }

    /**
     * A copy of {@code contexts} with every rear artifact moved to the first front muzzle.
     *
     * <p>Same length, same order, same directions, same aim points. Only {@code from} changes,
     * and only for the bolts that are behind the front.
     */
    public static List<AimContext> remapRear(List<AimContext> contexts, Vec3 origin, double tolerance) {
        List<AimContext> usable = usable(contexts);
        if (usable.isEmpty()) {
            return usable;
        }
        Vec3 fallback = fallbackDirection(usable);
        double max = maxProjection(usable, origin, fallback);
        AimContext firstFront = null;
        for (AimContext context : usable) {
            if (projection(context, origin, fallback) >= max - tolerance) {
                firstFront = context;
                break;
            }
        }
        if (firstFront == null) {
            return usable;
        }
        List<AimContext> out = new ArrayList<>(usable.size());
        for (AimContext context : usable) {
            if (projection(context, origin, fallback) >= max - tolerance) {
                out.add(context);
            } else {
                out.add(copyAt(context, firstFront.from));
            }
        }
        return out;
    }

    /** Whether this context's muzzle is at (or close enough to) the front. */
    public static boolean isFrontMost(AimContext context, List<AimContext> all, Vec3 origin,
                                      double tolerance) {
        if (context == null || context.from == null) {
            return false;
        }
        List<AimContext> usable = usable(all);
        if (usable.isEmpty()) {
            return true;
        }
        Vec3 fallback = fallbackDirection(usable);
        return projection(context, origin, fallback) >= maxProjection(usable, origin, fallback) - tolerance;
    }

    private static List<AimContext> usable(List<AimContext> contexts) {
        if (contexts == null) {
            return List.of();
        }
        List<AimContext> usable = new ArrayList<>(contexts.size());
        for (AimContext context : contexts) {
            if (context != null && context.from != null) {
                usable.add(context);
            }
        }
        return usable;
    }

    private static double maxProjection(List<AimContext> contexts, Vec3 origin, Vec3 fallback) {
        double max = -Double.MAX_VALUE;
        for (AimContext context : contexts) {
            max = Math.max(max, projection(context, origin, fallback));
        }
        return max;
    }

    private static double projection(AimContext context, Vec3 origin, Vec3 fallback) {
        Vec3 direction = direction(context, fallback);
        Vec3 from = context.from;
        Vec3 base = origin == null ? new Vec3(0.0, 0.0, 0.0) : origin;
        return (from.f_82479_ - base.f_82479_) * direction.f_82479_
                + (from.f_82480_ - base.f_82480_) * direction.f_82480_
                + (from.f_82481_ - base.f_82481_) * direction.f_82481_;
    }

    private static Vec3 direction(AimContext context, Vec3 fallback) {
        Vec2 rotation = context.direction;
        if (rotation == null) {
            return fallback;
        }
        Vec3 direction = direction(rotation);
        double length = direction.m_82553_();
        return length < 1.0E-6 ? fallback : direction.m_82490_(1.0 / length);
    }

    private static Vec3 fallbackDirection(List<AimContext> contexts) {
        for (AimContext context : contexts) {
            if (context.direction != null) {
                Vec3 direction = direction(context.direction);
                if (direction.m_82553_() > 1.0E-6) {
                    return direction.m_82541_();
                }
            }
        }
        return new Vec3(0.0, 0.0, 1.0);
    }

    /**
     * The base mod's {@code VectorUtil.rotToVec}, written out with {@code Math} rather than
     * {@code Mth}.
     *
     * <p>The two produce the same unit vector; the difference is that {@code Mth}'s static
     * initialiser pulls in {@code Util} and the logging library. This class is exercised by
     * {@code EffectsCheck} on a plain JVM, and a geometry helper should not need the game's
     * logging stack to answer which of two points is further along a line.
     */
    private static Vec3 direction(Vec2 rotation) {
        double pitch = rotation.f_82470_ * Math.PI / 180.0;
        double yaw = -rotation.f_82471_ * Math.PI / 180.0;
        double cosYaw = Math.cos(yaw);
        double sinYaw = Math.sin(yaw);
        double cosPitch = Math.cos(pitch);
        double sinPitch = Math.sin(pitch);
        return new Vec3(sinYaw * cosPitch, -sinPitch, cosYaw * cosPitch);
    }

    private static AimContext copyAt(AimContext source, Vec3 from) {
        AimContext copy = new AimContext();
        copy.from = from;
        copy.direction = source.direction;
        copy.position = source.position;
        return copy;
    }
}
