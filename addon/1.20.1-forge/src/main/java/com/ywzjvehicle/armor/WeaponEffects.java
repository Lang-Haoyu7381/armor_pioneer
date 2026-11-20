package com.ywzjvehicle.armor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.ywzj.vehicle.api.event.VehicleFireEvent;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.particle.SmokeCloudOption;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleCannon;
import org.ywzj.vehicle.vehicle.weapon.VehicleMultiWeapons;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The addon's own cannon report and muzzle flash.
 *
 * <p>The base mod plays one fire sound per weapon from the pack's display file - which is the same
 * 125 mm recording for every 105, 120 and 125 mm gun in the packs - and spawns two clouds of smoke
 * at the muzzle. That is the whole of the effect. This class replaces the sound for cannon-class
 * weapons with one picked by calibre, adds the flash itself, adds the smoke that blows forward
 * from the muzzle, and puts a delayed reflection and a breech clank behind the shot.
 *
 * <p><b>Every shot arrives here through the server's own broadcast.</b> A client firing a weapon
 * sends the shot to the server; the server confirms it by sending {@code ServerVehicleFire} to
 * every tracking client, including the shooter, and the base mod turns that packet into a client
 * side {@code VehicleFireEvent.Post}. Playing from that event therefore gives the shooter and
 * every observer the same effect, without a second packet of our own and without trusting the
 * client's copy of the shot.
 *
 * <p>Only {@link VehicleCannon} weapons of 20 mm and above are handled. Missiles, rockets, bombs
 * and grenades keep the base mod's effects, and so do the 7.62 mm and 12.7 mm machine guns - those
 * are not cannon reports and the pack's own recordings are already the right shape for them.
 *
 * <p><b>And only the Armor Pioneer pack is handled.</b> The replacement exists because that
 * pack's 105, 120 and 125 mm display files all point at one generic recording; a pack that ships
 * its own gun sounds - a 47 mm Vickers, a 75 mm KwK - already has the right report and loses it
 * if this class takes the shot over. So the effects are the addon's for {@code armor_pioneer}
 * vehicles and the base mod's everywhere else. The caller that matters is
 * {@code AbstractVehicleWeaponFireMixin}, which suppresses the base sound exactly when
 * {@link #handles} says yes: one decision, and a shot never ends up with both or neither.
 *
 * <p>This class names client classes, so it must never be loaded on a dedicated server. It is
 * registered from {@link ClientSetup} and from a client-side mixin only.
 */
public final class WeaponEffects {

    /** Below this the weapon is a machine gun rather than a cannon. */
    private static final float MIN_CALIBRE = 20.0F;

    /**
     * The only pack whose shots this class takes over.
     *
     * <p>Armor Pioneer is the pack the effects were built for; every other pack ships its own
     * per-gun recordings, and replacing them is a loss rather than an improvement.
     */
    private static final String PACK_NAMESPACE = "armor_pioneer";

    /** Sounds waiting for their moment: the echo and the breech, never the shot itself. */
    private static final List<Pending> PENDING = new ArrayList<>();

    private WeaponEffects() {
    }

    /**
     * Whether the addon owns the effect for this weapon.
     *
     * <p>Also consulted by {@code AbstractVehicleWeaponFireMixin}: the base mod's own fire-sound
     * call is suppressed exactly when this returns true, so a shot never has two reports layered
     * on top of each other.
     */
    public static boolean handles(AbstractVehicleWeapon<?> weapon) {
        AbstractVehicleWeapon<?> selected = selected(weapon);
        if (!(selected instanceof VehicleCannon)) {
            return false;
        }
        if (selected.getData() == null) {
            return false;
        }
        Float calibre = selected.getData().getCaliber();
        if (calibre == null || calibre < MIN_CALIBRE) {
            return false;
        }
        return inArmorPioneer(selected);
    }

    /**
     * Whether the vehicle this weapon belongs to is from the pack whose effects the addon owns.
     *
     * <p>Read from the vehicle's own id rather than from the armour config: a weapon can be rated
     * in the config and still belong to a pack whose own sound is better, and the fire effect has
     * nothing to do with whether the round is rated at all.
     */
    private static boolean inArmorPioneer(AbstractVehicleWeapon<?> weapon) {
        AbstractVehicle vehicle = weapon.getVehicle();
        return vehicle != null && vehicle.getVehicleId() != null
                && PACK_NAMESPACE.equals(vehicle.getVehicleId().m_135827_());
    }

    /** The weapon a shot really comes from: the selected sub-weapon of a multi-weapon mount. */
    private static AbstractVehicleWeapon<?> selected(AbstractVehicleWeapon<?> weapon) {
        if (weapon instanceof VehicleMultiWeapons multi) {
            AbstractVehicleWeapon<?> chosen = multi.getSelectedWeapon();
            if (chosen != null) {
                return chosen;
            }
        }
        return weapon;
    }

    @SubscribeEvent
    public static void onVehicleFire(VehicleFireEvent.Post event) {
        if (!event.isClientSide()) {
            return;
        }
        AbstractVehicleWeapon<?> weapon = selected(event.getWeapon());
        if (!handles(weapon)) {
            return;
        }
        ClientLevel level = Minecraft.m_91087_().f_91073_;
        if (level == null) {
            return;
        }

        float calibre = weapon.getData().getCaliber();
        Kind kind = kind(calibre);
        List<AimContext> contexts = contexts(weapon);
        if (contexts.isEmpty()) {
            return;
        }

        RandomSource random = level.f_46441_;
        Vec3 reportAt = contexts.get(0).from;
        if (reportAt == null) {
            reportAt = weapon.getVehicle().m_20182_();
        }

        // The shot itself, once per trigger pull rather than once per bolt: a salvo of ten rounds
        // leaves one muzzle and makes one report, and ten overlapping copies of it would only
        // clip the mixer.
        float volume = volume(kind);
        float pitch = 0.97F + random.m_188501_() * 0.06F;
        play(level, reportAt, sound(kind), volume, pitch);

        if (kind != Kind.AUTOCANNON) {
            int echoDelay = 14 + random.m_188503_(10) + (kind == Kind.HEAVY || kind == Kind.ARTILLERY ? 5 : 0);
            // The echo and the breech are background layers. They used to be loud enough to
            // stack on the report's first tenth of a second and clip the mixer; the breech is
            // also moved out of that window so the shot itself has room.
            PENDING.add(new Pending(echoDelay, reportAt, ArmorSounds.CANNON_ECHO.get(),
                    Math.max(1.8F, volume * 0.45F), 0.78F + random.m_188501_() * 0.08F));
            int breechDelay = 8 + random.m_188503_(6);
            PENDING.add(new Pending(breechDelay, reportAt, ArmorSounds.BREECH_CLANK.get(),
                    1.6F + calibre / 120.0F, 0.95F + random.m_188501_() * 0.14F));
        }

        Vec3 ground = weapon.getVehicle().m_20182_();
        for (AimContext context : contexts) {
            Vec3 muzzle = context.from;
            if (muzzle == null) {
                continue;
            }
            Vec3 direction = direction(context, weapon);
            spawnFlash(level, muzzle, direction, kind, calibre, random);
            spawnSmoke(level, muzzle, direction, kind, calibre, random, ground);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING.isEmpty()) {
            return;
        }
        ClientLevel level = Minecraft.m_91087_().f_91073_;
        if (level == null) {
            PENDING.clear();
            return;
        }
        Iterator<Pending> iterator = PENDING.iterator();
        while (iterator.hasNext()) {
            Pending pending = iterator.next();
            if (--pending.delay <= 0) {
                play(level, pending.position, pending.event, pending.volume, pending.pitch);
                iterator.remove();
            }
        }
    }

    // ------------------------------------------------------------------ shot geometry

    /**
     * The real muzzles of this unit, for the addon's own effects.
     *
     * <p>Not the current bolt: a barrel modelled as two cubes gives the unit a rear bolt as well
     * as a front one, and the base alternates between them. Taking the front-most projection
     * keeps every flash, puff and report at the barrel's actual mouth. Duplicate positions are
     * collapsed so a two-cube barrel does not spawn two identical flashes.
     */
    public static List<AimContext> effectContexts(WeaponUnit unit) {
        if (unit == null || unit.getVehicle() == null) {
            return List.of();
        }
        List<AimContext> front = MuzzleSelect.frontMost(unit.aimContexts(),
                unit.getVehicle().m_20182_(), MuzzleSelect.TOLERANCE);
        List<AimContext> unique = new ArrayList<>(front.size());
        for (AimContext candidate : front) {
            boolean duplicate = false;
            for (AimContext kept : unique) {
                double dx = candidate.from.f_82479_ - kept.from.f_82479_;
                double dy = candidate.from.f_82480_ - kept.from.f_82480_;
                double dz = candidate.from.f_82481_ - kept.from.f_82481_;
                if (dx * dx + dy * dy + dz * dz < 0.0025) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                unique.add(candidate);
            }
        }
        return unique;
    }

    /**
     * The same number of contexts the base mod asked for, with rear bolts moved to the front.
     *
     * <p>Used by the mixin on {@code onClientFire}: the base's smoke loops and its
     * {@code countFire(aimContexts.size())} both keep working, but the smoke lands at the muzzle
     * instead of at the gun root every other shot.
     */
    public static List<AimContext> baseContexts(WeaponUnit unit) {
        if (unit == null || unit.getVehicle() == null) {
            return List.of();
        }
        return MuzzleSelect.remapRear(unit.aimContexts(),
                unit.getVehicle().m_20182_(), MuzzleSelect.TOLERANCE);
    }

    /** The front-most context, preferring the current bolt when it is already a real muzzle. */
    public static AimContext baseContext(WeaponUnit unit) {
        if (unit == null || unit.getVehicle() == null) {
            return null;
        }
        AimContext current = unit.aimContext();
        if (current == null) {
            return null;
        }
        Vec3 origin = unit.getVehicle().m_20182_();
        if (MuzzleSelect.isFrontMost(current, unit.aimContexts(), origin, MuzzleSelect.TOLERANCE)) {
            return current;
        }
        List<AimContext> front = MuzzleSelect.frontMost(unit.aimContexts(), origin,
                MuzzleSelect.TOLERANCE);
        return front.isEmpty() ? current : front.get(0);
    }

    private static List<AimContext> contexts(AbstractVehicleWeapon<?> weapon) {
        return effectContexts(weapon.getWeaponUnit());
    }

    private static Vec3 direction(AimContext context, AbstractVehicleWeapon<?> weapon) {
        Vec2 rotation = context.direction;
        if (rotation != null) {
            return VectorUtil.rotToVec(rotation.f_82470_, rotation.f_82471_);
        }
        // No bolt rotation (a weapon with no turn group): point away from the vehicle instead of
        // dropping the effect altogether.
        Vec3 away = context.from == null ? null : context.from.m_82546_(weapon.getVehicle().m_20182_());
        if (away == null || away.m_82553_() < 1.0E-4) {
            return new Vec3(0.0, 0.0, 1.0);
        }
        return away.m_82541_();
    }

    // ------------------------------------------------------------------ the visuals

    private static void spawnFlash(ClientLevel level, Vec3 muzzle, Vec3 direction, Kind kind,
                                   float calibre, RandomSource random) {
        float scale = Math.max(0.35F, Math.min(2.6F, calibre / 100.0F));
        double reach = 0.5 + scale * 1.2;
        Vec3 side = horizontalSide(direction);
        Vec3 up = cross(direction, side);

        // The hot core. Overlapping additive sheets are what makes a white centre instead of a
        // yellow disc; the size ladder gives the flash a hot spot rather than a uniform glow.
        int cores = switch (kind) {
            case AUTOCANNON -> 1;
            case LIGHT -> 2;
            case MEDIUM -> 3;
            case HEAVY -> 4;
            case ARTILLERY -> 5;
        };
        for (int i = 0; i < cores; i++) {
            Vec3 out = radial(side, up, random);
            double along = 0.06 + random.m_188500_() * 0.18;
            double spread = 0.05 * scale * random.m_188500_();
            Vec3 p = muzzle.m_82549_(direction.m_82490_(along)).m_82549_(out.m_82490_(spread));
            Vec3 v = direction.m_82490_(0.02)
                    .m_82549_(out.m_82490_(0.03 + random.m_188500_() * 0.05));
            add(level, coreType(kind, i), p, v);
        }

        // The forward jet: a chain of torn, stretched sheets, shrinking as it goes. The
        // turbulence comes from the per-particle frame and roll, not from the chain itself.
        int jets = switch (kind) {
            case AUTOCANNON -> 2;
            case LIGHT -> 5;
            case MEDIUM -> 6;
            case HEAVY -> 8;
            case ARTILLERY -> 9;
        };
        for (int i = 0; i < jets; i++) {
            double t = jets == 1 ? 0.0 : (double) i / (double) (jets - 1);
            double along = 0.12 + reach * t;
            double spread = (0.02 + 0.14 * t) * scale;
            Vec3 out = radial(side, up, random);
            Vec3 p = muzzle.m_82549_(direction.m_82490_(along))
                    .m_82549_(out.m_82490_((random.m_188500_() - 0.5) * spread));
            Vec3 v = direction.m_82490_(0.05 * (1.0 - t)).m_82549_(out.m_82490_(0.01));
            var type = t < 0.28 && (kind == Kind.HEAVY || kind == Kind.ARTILLERY)
                    ? ArmorParticles.MUZZLE_FLASH.get()
                    : (t < 0.72 ? ArmorParticles.MUZZLE_FLASH_JET.get()
                    : ArmorParticles.MUZZLE_FLASH_SMALL.get());
            add(level, type, p, v);
        }

        // Side blast. A big gun's flame does not all go forward; some of it escapes around the
        // muzzle brake and rolls backward along the barrel.
        if (kind == Kind.HEAVY || kind == Kind.ARTILLERY) {
            int petals = kind == Kind.ARTILLERY ? 5 : 4;
            for (int i = 0; i < petals; i++) {
                Vec3 out = radial(side, up, random);
                Vec3 p = muzzle.m_82549_(out.m_82490_(0.16 * scale))
                        .m_82549_(direction.m_82490_(0.05));
                Vec3 v = out.m_82490_(0.14 + random.m_188500_() * 0.18)
                        .m_82546_(direction.m_82490_(0.03));
                add(level, ArmorParticles.MUZZLE_FLASH_JET.get(), p, v);
            }
        }

        // Embers. Short, bright, and the only layer with gravity - they are the part that tells
        // the eye the blast is made of matter rather than light.
        int sparks = switch (kind) {
            case AUTOCANNON -> 3;
            case LIGHT -> 7;
            case MEDIUM -> 11;
            case HEAVY -> 16;
            case ARTILLERY -> 20;
        };
        for (int i = 0; i < sparks; i++) {
            Vec3 out = radial(side, up, random);
            Vec3 p = muzzle.m_82549_(direction.m_82490_(0.08))
                    .m_82549_(out.m_82490_(random.m_188500_() * 0.12 * scale));
            Vec3 v = direction.m_82490_(0.12 + random.m_188500_() * 0.45)
                    .m_82549_(out.m_82490_(0.05 + random.m_188500_() * 0.25))
                    .m_82549_(new Vec3(0.0, (random.m_188500_() - 0.5) * 0.25, 0.0));
            add(level, ArmorParticles.MUZZLE_FLASH_SPARK.get(), p, v);
        }
    }

    private static void add(ClientLevel level, net.minecraft.core.particles.SimpleParticleType type,
                            Vec3 position, Vec3 velocity) {
        level.m_6493_(type, true, position.f_82479_, position.f_82480_, position.f_82481_,
                velocity.f_82479_, velocity.f_82480_, velocity.f_82481_);
    }

    private static net.minecraft.core.particles.SimpleParticleType coreType(Kind kind, int index) {
        return switch (kind) {
            case AUTOCANNON -> ArmorParticles.MUZZLE_FLASH_SMALL.get();
            case LIGHT, MEDIUM -> index == 0
                    ? ArmorParticles.MUZZLE_FLASH.get()
                    : ArmorParticles.MUZZLE_FLASH_SMALL.get();
            case HEAVY, ARTILLERY -> index == 0
                    ? ArmorParticles.MUZZLE_FLASH_LARGE.get()
                    : (index == 1 ? ArmorParticles.MUZZLE_FLASH.get()
                    : ArmorParticles.MUZZLE_FLASH_SMALL.get());
        };
    }

    /** A horizontal unit vector perpendicular to the barrel. */
    private static Vec3 horizontalSide(Vec3 direction) {
        double sx = direction.f_82481_;
        double sz = -direction.f_82479_;
        double length = Math.sqrt(sx * sx + sz * sz);
        if (length < 1.0E-4) {
            return new Vec3(1.0, 0.0, 0.0);
        }
        return new Vec3(sx / length, 0.0, sz / length);
    }

    private static Vec3 cross(Vec3 a, Vec3 b) {
        double x = a.f_82480_ * b.f_82481_ - a.f_82481_ * b.f_82480_;
        double y = a.f_82481_ * b.f_82479_ - a.f_82479_ * b.f_82481_;
        double z = a.f_82479_ * b.f_82480_ - a.f_82480_ * b.f_82479_;
        double length = Math.sqrt(x * x + y * y + z * z);
        if (length < 1.0E-5) {
            return new Vec3(0.0, 1.0, 0.0);
        }
        return new Vec3(x / length, y / length, z / length);
    }

    private static Vec3 radial(Vec3 side, Vec3 up, RandomSource random) {
        double angle = random.m_188500_() * Math.PI * 2.0;
        double cosine = Math.cos(angle);
        double sine = Math.sin(angle);
        return new Vec3(side.f_82479_ * cosine + up.f_82479_ * sine,
                side.f_82480_ * cosine + up.f_82480_ * sine,
                side.f_82481_ * cosine + up.f_82481_ * sine);
    }

    private static void spawnSmoke(ClientLevel level, Vec3 muzzle, Vec3 direction, Kind kind,
                                   float calibre, RandomSource random, Vec3 ground) {
        float scale = Math.max(0.35F, Math.min(2.6F, calibre / 100.0F));
        double reach = 0.5 + scale * 1.2;
        Vec3 side = horizontalSide(direction);
        Vec3 up = cross(direction, side);

        // The fireball's own smoke: short, orange, and gone before the grey cloud forms.
        int hot = kind == Kind.AUTOCANNON ? 1 : 3;
        for (int i = 0; i < hot; i++) {
            double along = 0.15 + random.m_188500_() * reach * 0.4;
            float size = (0.32F + 0.24F * scale) * (0.7F + random.m_188501_() * 0.5F);
            Vec3 out = radial(side, up, random);
            level.m_6493_(new SmokeCloudOption(1.0F, 0.53F, 0.15F, 0.85F, 0.0F,
                            5 + random.m_188503_(4), size, size * 2.8F, 0.0F), true,
                    muzzle.f_82479_ + direction.f_82479_ * along + out.f_82479_ * 0.1,
                    muzzle.f_82480_ + direction.f_82480_ * along + out.f_82480_ * 0.1,
                    muzzle.f_82481_ + direction.f_82481_ * along + out.f_82481_ * 0.1,
                    direction.f_82479_ * 0.05, direction.f_82480_ * 0.05 + 0.01,
                    direction.f_82481_ * 0.05);
        }

        // The main cloud. This is the shape the real firing video is built around: a cone of
        // white-grey smoke several block-lengths long that keeps growing after the shot and then
        // hangs in the air. A third of the puffs get the long life; the rest are the dense core.
        int puffs = BlastScale.smokePuffs(calibre);
        int longLived = BlastScale.longLivedPuffs(puffs);
        int maxLife = BlastScale.smokeLifeTicks(calibre);
        for (int i = 0; i < puffs; i++) {
            boolean slow = i < longLived;
            double along = 0.2 + random.m_188500_() * (reach + 1.5 + (slow ? 1.5 : 0.0));
            double spread = (0.08 + 0.45 * scale) * random.m_188500_();
            Vec3 out = radial(side, up, random);
            Vec3 p = muzzle.m_82549_(direction.m_82490_(along))
                    .m_82549_(out.m_82490_(spread))
                    .m_82549_(new Vec3(0.0, 0.05 + random.m_188500_() * 0.3 * scale, 0.0));
            Vec3 v = direction.m_82490_(0.04 + random.m_188500_() * 0.08)
                    .m_82549_(out.m_82490_(0.02 + random.m_188500_() * 0.05))
                    .m_82549_(new Vec3(0.0, 0.02 + random.m_188500_() * 0.035, 0.0));

            float startSize = (0.30F + 0.20F * scale) * (0.7F + random.m_188501_() * 0.7F);
            float endSize = startSize * (4.0F + random.m_188501_() * 3.5F);
            int life = slow
                    ? maxLife - random.m_188503_(Math.max(1, maxLife / 3))
                    : 35 + random.m_188503_(Math.max(1, maxLife / 2));
            // The core of the cloud is darker (unburnt propellant), the outside is white steam.
            float shade = i < puffs / 4
                    ? 0.42F + random.m_188501_() * 0.16F
                    : 0.58F + random.m_188501_() * 0.30F;
            level.m_6493_(new SmokeCloudOption(shade, shade, shade * 0.97F, 0.48F, 0.0F, life,
                            startSize, endSize, -0.02F), true,
                    p.f_82479_, p.f_82480_, p.f_82481_,
                    v.f_82479_, v.f_82480_, v.f_82481_);
        }

        // Sideways blast for the big guns: smoke driven out of the muzzle brake and rolling back.
        if (kind == Kind.HEAVY || kind == Kind.ARTILLERY) {
            int sidePuffs = kind == Kind.ARTILLERY ? 8 : 6;
            for (int i = 0; i < sidePuffs; i++) {
                Vec3 out = radial(side, up, random);
                Vec3 p = muzzle.m_82549_(out.m_82490_(0.4 + 0.4 * scale))
                        .m_82549_(direction.m_82490_(0.1));
                Vec3 v = out.m_82490_(0.08 + random.m_188500_() * 0.12)
                        .m_82546_(direction.m_82490_(0.02))
                        .m_82549_(new Vec3(0.0, 0.01, 0.0));
                float startSize = (0.30F + 0.16F * scale) * (0.7F + random.m_188501_() * 0.5F);
                level.m_6493_(new SmokeCloudOption(0.52F, 0.52F, 0.51F, 0.42F, 0.0F,
                                30 + random.m_188503_(40), startSize, startSize * 4.5F, -0.01F),
                        true, p.f_82479_, p.f_82480_, p.f_82481_,
                        v.f_82479_, v.f_82480_, v.f_82481_);
            }
        }

        // Ground dust. The blast strips dust off the range around the vehicle, not just at the
        // muzzle: a low ring biased toward the direction the gun is pointing. It lingers and
        // drifts outward while the white cloud goes up.
        int dust = BlastScale.dustPuffs(calibre);
        if (dust > 0 && ground != null) {
            double dustY = Math.min(ground.f_82480_ + 0.2, muzzle.f_82480_ - 1.5);
            double forwardAngle = Math.atan2(direction.f_82479_, direction.f_82481_);
            for (int i = 0; i < dust; i++) {
                double angle = i < dust / 2
                        ? forwardAngle + (random.m_188500_() - 0.5) * Math.PI * 0.9
                        : (i + random.m_188500_()) / dust * Math.PI * 2.0;
                double radius = (1.8 + random.m_188500_() * 2.4) * Math.max(0.8, scale * 0.9);
                double cx = Math.cos(angle);
                double cz = Math.sin(angle);
                Vec3 p = new Vec3(ground.f_82479_ + cx * radius,
                        dustY + random.m_188500_() * 0.25,
                        ground.f_82481_ + cz * radius);
                Vec3 v = new Vec3(cx * (0.05 + random.m_188500_() * 0.12),
                        0.02 + random.m_188500_() * 0.03,
                        cz * (0.05 + random.m_188500_() * 0.12));
                float startSize = (0.35F + 0.18F * scale) * (0.7F + random.m_188501_() * 0.6F);
                float shade = 0.58F + random.m_188501_() * 0.20F;
                level.m_6493_(new SmokeCloudOption(shade, shade * 0.94F, shade * 0.80F,
                                0.42F, 0.0F, 30 + random.m_188503_(50),
                                startSize, startSize * (3.5F + random.m_188501_() * 2.5F), -0.01F),
                        true, p.f_82479_, p.f_82480_, p.f_82481_,
                        v.f_82479_, v.f_82480_, v.f_82481_);
            }
        }

        // A dirty tail that rolls back over the turret roof.
        if (kind == Kind.HEAVY || kind == Kind.ARTILLERY) {
            for (int i = 0; i < 3; i++) {
                double along = -0.10 - random.m_188500_() * 0.35;
                float size = (0.26F + 0.14F * scale) * (0.8F + random.m_188501_() * 0.5F);
                level.m_6493_(new SmokeCloudOption(0.40F, 0.40F, 0.39F, 0.42F, 0.0F,
                                30 + random.m_188503_(30), size, size * 4.0F, -0.01F), true,
                        muzzle.f_82479_ + direction.f_82479_ * along,
                        muzzle.f_82480_ + direction.f_82480_ * along + 0.05,
                        muzzle.f_82481_ + direction.f_82481_ * along,
                        -direction.f_82479_ * 0.03, 0.015, -direction.f_82481_ * 0.03);
            }
        }
    }

    // ------------------------------------------------------------------ the report

    private static Kind kind(float calibre) {
        if (calibre >= 140.0F) {
            return Kind.ARTILLERY;
        }
        if (calibre >= 110.0F) {
            return Kind.HEAVY;
        }
        if (calibre >= 90.0F) {
            return Kind.MEDIUM;
        }
        if (calibre >= 60.0F) {
            return Kind.LIGHT;
        }
        return Kind.AUTOCANNON;
    }

    private static SoundEvent sound(Kind kind) {
        return switch (kind) {
            case AUTOCANNON -> ArmorSounds.AUTOCANNON.get();
            case LIGHT -> ArmorSounds.CANNON_LIGHT.get();
            case MEDIUM -> ArmorSounds.CANNON_MEDIUM.get();
            case HEAVY -> ArmorSounds.CANNON_HEAVY.get();
            case ARTILLERY -> ArmorSounds.CANNON_ARTILLERY.get();
        };
    }

    /**
     * Playback volume, kept close to the base mod's own 4.0.
     *
     * <p>The first version used 8-12 because "a cannon should be loud". At those gains the report,
     * the echo, the breech and the projectile crack sum well past full scale, and the sound engine
     * turns that into the fuzzy, broken edge this volume is here to avoid. The base recording is
     * played at 4; a layered report at 4.5-5 is already louder than it without clipping its own
     * layers into noise.
     */
    private static float volume(Kind kind) {
        return switch (kind) {
            case AUTOCANNON -> 3.0F;
            case LIGHT -> 4.0F;
            case MEDIUM -> 4.5F;
            case HEAVY -> 5.0F;
            case ARTILLERY -> 5.5F;
        };
    }

    private static void play(ClientLevel level, Vec3 position, SoundEvent event, float volume, float pitch) {
        level.m_7785_(position.f_82479_, position.f_82480_, position.f_82481_,
                event, SoundSource.PLAYERS, volume, pitch, false);
    }

    private enum Kind {
        AUTOCANNON, LIGHT, MEDIUM, HEAVY, ARTILLERY
    }

    private static final class Pending {
        private int delay;
        private final Vec3 position;
        private final SoundEvent event;
        private final float volume;
        private final float pitch;

        private Pending(int delay, Vec3 position, SoundEvent event, float volume, float pitch) {
            this.delay = delay;
            this.position = position;
            this.event = event;
            this.volume = volume;
            this.pitch = pitch;
        }
    }
}
