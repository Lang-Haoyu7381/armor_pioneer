package com.ywzjvehicle.armor;

/**
 * How much smoke and dust a shot throws, by calibre.
 *
 * <p>A real 120 mm tank gun does not make a puff. It makes a white-grey cloud several tank
 * lengths across that stands on the range for seconds, and the blast strips dust off the ground
 * around the vehicle. The first version of the effect used the same eight small puffs for every
 * gun, which is why it read as a sparkler next to the real thing.
 *
 * <p>The numbers scale in four steps - autocannon, light tank gun, 105, modern 120/125 - and are
 * deliberately capped. They are the interface between the look and the particle budget, so they
 * live in a common class with no client types: {@code EffectsCheck} can hold the steps in place
 * without launching a game.
 */
public final class BlastScale {

    private BlastScale() {
    }

    /** Billowing smoke puffs emitted along the barrel direction. */
    public static int smokePuffs(float calibre) {
        if (calibre < 60.0F) {
            return 4;
        }
        if (calibre < 90.0F) {
            return 18;
        }
        if (calibre < 110.0F) {
            return 26;
        }
        if (calibre < 140.0F) {
            return 38;
        }
        return 48;
    }

    /** Low dust puffs raised around the vehicle. Autocannon rounds do not raise a ring. */
    public static int dustPuffs(float calibre) {
        if (calibre < 60.0F) {
            return 0;
        }
        if (calibre < 90.0F) {
            return 8;
        }
        if (calibre < 110.0F) {
            return 16;
        }
        if (calibre < 140.0F) {
            return 24;
        }
        return 30;
    }

    /** Upper bound of a smoke puff's life, in ticks. */
    public static int smokeLifeTicks(float calibre) {
        if (calibre < 60.0F) {
            return 40;
        }
        if (calibre < 90.0F) {
            return 90;
        }
        if (calibre < 110.0F) {
            return 120;
        }
        if (calibre < 140.0F) {
            return 150;
        }
        return 170;
    }

    /** How many of the smoke puffs are given the long, drifting life. */
    public static int longLivedPuffs(int puffs) {
        return Math.max(1, puffs / 3);
    }
}
