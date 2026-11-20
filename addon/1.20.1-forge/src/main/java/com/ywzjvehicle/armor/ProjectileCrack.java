package com.ywzjvehicle.armor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.ywzj.vehicle.entity.weapon.BulletEntity;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The shock cone of a supersonic round, heard as the round goes past.
 *
 * <p>This is the sound that is <em>not</em> the muzzle report. A tank gun's round leaves at
 * several times the speed of sound and drags a cone behind it; a listener off to the side hears a
 * whip-crack when that cone sweeps over them, a moment after the round has gone by. Firing a gun
 * yourself, you hear it leave and go away; standing twenty blocks to the side, you hear it pass.
 *
 * <p><b>It is driven by the projectile, not by the firing event.</b> The base mod's
 * {@code BulletEntity} is a real client-side entity: its spawn packet carries its position and
 * velocity, and both sides tick it along the same line. On the shell's first client tick this
 * class takes that line and asks where it comes closest to the local player. That fixes both the
 * place and the time: the shell reaches the closest point after {@code along / speed} ticks, and
 * the shock itself then needs {@code miss / soundSpeed} more to cross the remaining distance.
 *
 * <p>No packet is involved. Each client makes the same measurement against its own player, so the
 * crack is in the right place for the shooter, a passenger and a bystander without any of them
 * hearing somebody else's version of it.
 *
 * <p><b>Near the muzzle the measurement is replaced on purpose.</b> For the crew, the closest
 * point is the muzzle itself, where playing a crack would only thicken the report. They get the
 * departure snap instead: the same sample, placed about 24 blocks down the line and delayed by the
 * shell's flight plus the sound's travel. That is the "round leaving" half of the effect, and it is
 * the half a single-player player can actually hear.
 *
 * <p>Only cannon rounds of 20 mm or more and 12 blocks/tick or faster are tracked. That excludes
 * machine-gun bullets and the 35 mm grenade launcher, whose 10 blocks/tick is not a shock wave.
 * Client-only: the class is registered from {@link ClientSetup} and called from a client mixin.
 */
public final class ProjectileCrack {

    /** Below this the round is a machine-gun bullet rather than a shell. */
    private static final float MIN_CALIBRE = 20.0F;
    /** Blocks per tick below which there is no sound barrier to break. */
    private static final double MIN_SPEED = 12.0;
    /**
     * A lower floor for full-calibre tank guns.
     *
     * <p>The 75 mm and 88 mm weapon files in {@code tank_assault} carry no {@code velocity} at
     * all, so they fall back to the base default of 10 blocks/tick - slower than a modern
     * autocannon round, and slower than the game's own speed of sound. Real 75/88 mm rounds are
     * supersonic, and their absence from the data is a gap rather than a statement, so a shell
     * of 60 mm or more is allowed through at 10. Anything small enough for 10 blocks/tick to be
     * its real speed - the 35 mm grenade launcher - is still excluded.
     */
    private static final double MIN_SPEED_LARGE = 10.0;

    /** How close the line of flight has to come before the crack is worth placing. */
    private static final double MIN_PASS_DISTANCE = 8.0;
    private static final double MAX_PASS_DISTANCE = 64.0;
    /** The departure snap is placed this far down the line for a near listener. */
    private static final double DEPARTURE_REACH = 32.0;
    /**
     * Two ticks of breathing room on the departure snap.
     *
     * <p>Physically the shock from 32 blocks ahead would be back in about four ticks; the report
     * is still at its loudest then, and at the same ear the two transients merge into one. The
     * small delay is the one deliberate lie in this class, and it is what makes the crack a
     * separate sound to the player firing rather than a thicker muzzle blast.
     */
    private static final int DEPARTURE_DELAY_TICKS = 3;
    /** Nearer than this to the muzzle the listener is crew, not a bystander. */
    private static final double CREW_RADIUS = 12.0;

    /** Speed of sound in blocks per tick: 343 m/s at 20 ticks per second. */
    private static final double SOUND_SPEED = 343.0 / 20.0;

    private static final int MAX_DELAY_TICKS = 60;

    private static final List<Pending> PENDING = new ArrayList<>();

    private ProjectileCrack() {
    }

    /**
     * Called once per shell, on its first client tick, from {@code BulletEntitySonicBoomMixin}.
     *
     * <p>Once is enough: the line does not change after the spawn packet has fixed the velocity,
     * and recomputing every tick would schedule a crack per tick.
     */
    public static void track(BulletEntity bullet) {
        if (bullet.getCaliber() < MIN_CALIBRE) {
            return;
        }
        ClientLevel level = Minecraft.m_91087_().f_91073_;
        Player player = Minecraft.m_91087_().f_91074_;
        if (level == null || player == null) {
            return;
        }

        Vec3 velocity = bullet.m_20184_();
        double speed = Math.sqrt(velocity.f_82479_ * velocity.f_82479_
                + velocity.f_82480_ * velocity.f_82480_
                + velocity.f_82481_ * velocity.f_82481_);
        double minimumSpeed = bullet.getCaliber() >= 60.0F ? MIN_SPEED_LARGE : MIN_SPEED;
        if (speed < minimumSpeed) {
            return;
        }
        double dx = velocity.f_82479_ / speed;
        double dy = velocity.f_82480_ / speed;
        double dz = velocity.f_82481_ / speed;

        Vec3 origin = bullet.m_20182_();
        Vec3 start = bullet.getStartPos() == null ? origin : bullet.getStartPos();
        Vec3 listener = player.m_20182_().m_82520_(0.0, 1.5, 0.0);

        double pitch = 1.18 - Math.min(0.30, bullet.getCaliber() / 500.0)
                + level.f_46441_.m_188501_() * 0.06;

        // Crew: the round leaves them, it does not pass them. Place the snap ahead of the muzzle
        // instead of at it, or the report and the crack land on the same ear at the same moment.
        if (distanceSqr(listener, start) < CREW_RADIUS * CREW_RADIUS) {
            Vec3 pass = new Vec3(start.f_82479_ + dx * DEPARTURE_REACH,
                    start.f_82480_ + dy * DEPARTURE_REACH,
                    start.f_82481_ + dz * DEPARTURE_REACH);
            int delay = (int) Math.ceil(DEPARTURE_REACH / speed
                    + Math.sqrt(distanceSqr(listener, pass)) / SOUND_SPEED)
                    + DEPARTURE_DELAY_TICKS;
            schedule(level, delay, pass, 3.0F, (float) pitch);
            return;
        }

        double toListenerX = listener.f_82479_ - origin.f_82479_;
        double toListenerY = listener.f_82480_ - origin.f_82480_;
        double toListenerZ = listener.f_82481_ - origin.f_82481_;
        double along = toListenerX * dx + toListenerY * dy + toListenerZ * dz;
        if (along <= 4.0) {
            // The round is already past, or going away; no cone will sweep over this listener.
            return;
        }

        Vec3 pass = new Vec3(origin.f_82479_ + dx * along,
                origin.f_82480_ + dy * along,
                origin.f_82481_ + dz * along);
        double miss = Math.sqrt(distanceSqr(listener, pass));
        if (miss < MIN_PASS_DISTANCE || miss > MAX_PASS_DISTANCE) {
            return;
        }

        int delay = (int) Math.ceil(along / speed + miss / SOUND_SPEED);
        schedule(level, delay, pass, 3.5F, (float) pitch);
    }

    private static void schedule(ClientLevel level, int delay, Vec3 position, float volume, float pitch) {
        if (delay < 1 || delay > MAX_DELAY_TICKS) {
            return;
        }
        PENDING.add(new Pending(level, delay, position, volume, pitch));
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
            if (pending.level != level) {
                // A world change: the point belongs to a level nobody is standing in any more.
                iterator.remove();
                continue;
            }
            if (--pending.delay <= 0) {
                level.m_7785_(pending.position.f_82479_, pending.position.f_82480_,
                        pending.position.f_82481_, ArmorSounds.PROJECTILE_CRACK.get(),
                        SoundSource.PLAYERS, pending.volume, pending.pitch, false);
                iterator.remove();
            }
        }
    }

    private static double distanceSqr(Vec3 a, Vec3 b) {
        double dx = a.f_82479_ - b.f_82479_;
        double dy = a.f_82480_ - b.f_82480_;
        double dz = a.f_82481_ - b.f_82481_;
        return dx * dx + dy * dy + dz * dz;
    }

    private static final class Pending {
        private final ClientLevel level;
        private int delay;
        private final Vec3 position;
        private final float volume;
        private final float pitch;

        private Pending(ClientLevel level, int delay, Vec3 position, float volume, float pitch) {
            this.level = level;
            this.delay = delay;
            this.position = position;
            this.volume = volume;
            this.pitch = pitch;
        }
    }
}
