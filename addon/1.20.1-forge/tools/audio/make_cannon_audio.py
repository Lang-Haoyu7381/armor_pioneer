"""Synthesise the cannon reports used by the armour addon.

The point of synthesising these instead of shipping a recording is twofold: the
set is reproducible from this file, and nothing here is copied from someone
else's sound pack.  The model is the one a real report follows - a supersonic
crack, a low pressure wave, the blast body, then the reflections of the
surroundings - and the classes differ only in where those three sit.

Run it with the dependencies `uv` is told to fetch:

    uv run --with numpy --with soundfile python tools/audio/make_cannon_audio.py

Every file is mono 44.1 kHz Ogg Vorbis, which is what Minecraft's SoundManager
decodes.  The seed is fixed per class, so re-running rewrites byte-identical
audio (the encoder is deterministic for fixed input).
"""

from __future__ import annotations

import math
from pathlib import Path

import numpy as np
import soundfile as sf

SR = 44100
ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "src" / "main" / "resources" / "assets" / "ywzj_vehicle_armor" / "sounds" / "weapon"


# ---------------------------------------------------------------- primitives

def spectral_filter(x: np.ndarray, lo: float | None = None, hi: float | None = None,
                    soft: float = 0.18) -> np.ndarray:
    """A soft-edged band limit applied in the frequency domain.

    A true one-pole filter would be a Python loop over a hundred thousand
    samples.  The signal here is short and the exact roll-off does not matter,
    only that the band is where the ear expects it, so a sigmoid mask is both
    faster and easier to reason about.
    """
    spectrum = np.fft.rfft(x)
    freqs = np.fft.rfftfreq(x.size, 1.0 / SR)
    mask = np.ones_like(freqs)
    if lo is not None:
        mask *= 1.0 / (1.0 + np.exp(-(freqs - lo) / (lo * soft + 1.0)))
    if hi is not None:
        mask *= 1.0 / (1.0 + np.exp((freqs - hi) / (hi * soft + 1.0)))
    return np.fft.irfft(spectrum * mask, n=x.size)


def envelope(t: np.ndarray, attack: float, tau: float) -> np.ndarray:
    """A click-free attack followed by an exponential decay."""
    rise = np.clip(t / max(attack, 1e-5), 0.0, 1.0)
    return rise * np.exp(-np.maximum(t - attack, 0.0) / max(tau, 1e-5))


def sweep(t: np.ndarray, f_hi: float, f_lo: float, tau: float) -> np.ndarray:
    """A sine whose pitch falls from f_hi to f_lo - the body of the blast."""
    freq = f_lo + (f_hi - f_lo) * np.exp(-t / max(tau, 1e-5))
    return np.sin(2.0 * np.pi * np.cumsum(freq) / SR)


def convolve(x: np.ndarray, ir: np.ndarray) -> np.ndarray:
    n = x.size + ir.size - 1
    nfft = 1 << (n - 1).bit_length()
    y = np.fft.irfft(np.fft.rfft(x, nfft) * np.fft.rfft(ir, nfft), nfft)
    return y[: x.size]


def soft_clip(x: np.ndarray, drive: float) -> np.ndarray:
    return np.tanh(x * drive) / math.tanh(drive)


def normalise(x: np.ndarray, peak: float = 0.92) -> np.ndarray:
    top = float(np.max(np.abs(x)))
    if top < 1e-9:
        return x
    return x * (peak / top)


def fade_tail(x: np.ndarray, seconds: float = 0.08) -> np.ndarray:
    n = min(x.size, int(SR * seconds))
    if n <= 1:
        return x
    x[-n:] *= np.linspace(1.0, 0.0, n)
    return x


# ---------------------------------------------------------------- generators

def cannon_report(*, duration: float, f_hi: float, f_lo: float, tau_low: float,
                  tau_body: float, crack: float, tail: float, sub: float,
                  seed: int) -> np.ndarray:
    rng = np.random.default_rng(seed)
    n = int(SR * duration)
    t = np.arange(n, dtype=np.float64) / SR
    noise = rng.standard_normal(n)

    # The supersonic crack: the first thing heard, and gone in ten milliseconds.
    head = spectral_filter(noise, 1700.0, 10000.0) * envelope(t, 0.00035, 0.009) * crack

    # The blast body: broad noise around the pressure peak.  Given the same
    # peak amplitude, low frequencies carry far more of the energy, so the mid
    # band is deliberately weighted above unity.  Without it the shot measures
    # as 98% sub-bass and disappears on a laptop speaker.
    body = spectral_filter(noise, 150.0, 2600.0) * envelope(t, 0.0012, tau_body) * 1.5
    blast = spectral_filter(noise, 320.0, 4000.0) * envelope(t, 0.0008, 0.055) * 0.6

    # The pressure wave itself, which is what a large gun feels like.
    rumble = spectral_filter(noise, 25.0, 220.0) * envelope(t, 0.004, tau_low) * 0.6
    thump = sweep(t, f_hi, f_lo, 0.075) * envelope(t, 0.002, tau_low) * sub

    signal = head + body + blast + rumble + thump

    # Ground and treeline reflections.  Each is the body delayed and low-passed,
    # which is enough to place the shot in a space instead of in a vacuum.
    out = signal.copy()
    for delay_ms, gain, lowpass in ((28.0, 0.34, 2400.0), (61.0, 0.20, 1500.0),
                                    (104.0, 0.12, 900.0), (171.0, 0.07, 600.0)):
        delay = int(SR * delay_ms / 1000.0)
        if delay <= 0 or delay >= n:
            continue
        piece = spectral_filter(signal[: n - delay], None, lowpass) * gain
        out[delay:] += piece

    # A diffuse tail, made by convolving the report with decaying noise.  It is
    # what makes the shot keep going after the muzzle has stopped flashing.
    if tail > 0.0:
        tail_n = int(SR * min(duration * 0.95, 2.4))
        tail_t = np.arange(tail_n, dtype=np.float64) / SR
        ir = rng.standard_normal(tail_n) * np.exp(-tail_t / 0.42)
        ir = spectral_filter(ir, 45.0, 2800.0)
        ir /= math.sqrt(float(np.sum(ir * ir))) + 1e-9
        diffuse = convolve(signal, ir)
        diffuse /= float(np.max(np.abs(diffuse))) + 1e-9
        out += diffuse * tail

    # 10 kHz ceiling and a gentle saturator: the previous 1.6 drive was a large
    # part of the "fuzzy" sound at the shot.  The transient is still the loudest
    # thing in the file; it just no longer has a dense blanket of harmonics over
    # the whole blast.
    out = spectral_filter(out, 28.0, 10000.0)
    out = soft_clip(out, 0.9)
    return fade_tail(normalise(out, 0.90), 0.12)


def distant_echo(seed: int) -> np.ndarray:
    """The report as it comes back from the treeline - no crack, just weight."""
    rng = np.random.default_rng(seed)
    duration = 2.8
    n = int(SR * duration)
    t = np.arange(n, dtype=np.float64) / SR
    noise = rng.standard_normal(n)

    body = spectral_filter(noise, 40.0, 1300.0) * envelope(t, 0.030, 0.55)
    low = sweep(t, 62.0, 26.0, 0.10) * envelope(t, 0.020, 0.42) * 0.45
    signal = body + low

    for delay_ms, gain in ((150.0, 0.22), (310.0, 0.13), (520.0, 0.07)):
        delay = int(SR * delay_ms / 1000.0)
        signal[delay:] += signal[: n - delay] * gain

    signal = soft_clip(signal, 1.2)
    return fade_tail(normalise(signal, 0.55), 0.20)


def breech_clank(seed: int) -> np.ndarray:
    """Breech block and recoil, a beat after the shot."""
    rng = np.random.default_rng(seed)
    duration = 0.75
    n = int(SR * duration)
    t = np.arange(n, dtype=np.float64) / SR
    noise = rng.standard_normal(n)

    click = spectral_filter(noise, 900.0, 9000.0) * envelope(t, 0.0003, 0.005) * 0.9
    metal = np.zeros(n)
    for freq, tau, gain in ((760.0, 0.11, 0.42), (1310.0, 0.085, 0.30),
                            (2180.0, 0.060, 0.22), (3190.0, 0.040, 0.15)):
        metal += gain * np.sin(2.0 * np.pi * freq * t) * envelope(t, 0.001, tau)
    # A second, softer strike is the block settling.
    thud = spectral_filter(noise, 90.0, 500.0) * envelope(np.maximum(t - 0.11, 0.0), 0.002, 0.05) * 0.35
    signal = click + metal + thud
    return fade_tail(normalise(soft_clip(signal, 1.2), 0.62), 0.05)


def autocannon(seed: int) -> np.ndarray:
    """Short, hard and dry: an autocannon round, not a tank gun."""
    return cannon_report(duration=0.72, f_hi=260.0, f_lo=95.0, tau_low=0.055,
                         tau_body=0.035, crack=0.95, tail=0.06, sub=0.25, seed=seed)


def projectile_crack(seed: int) -> np.ndarray:
    """The shock cone of a supersonic round passing the listener.

    This is deliberately not a scaled-down report.  A passing shock is a
    whip-crack: a few hundred microseconds of broadband shock, a short
    high-mid whip, a falling narrow band as the cone sweeps past, and a
    shallow low-frequency N-wave under it.  It must stay under half a second;
    anything longer reads as an explosion rather than as a projectile.
    """
    rng = np.random.default_rng(seed)
    duration = 0.62
    n = int(SR * duration)
    t = np.arange(n, dtype=np.float64) / SR
    noise = rng.standard_normal(n)

    # The leading shock wave.
    snap = spectral_filter(noise, 3000.0, 15000.0) * envelope(t, 0.00015, 0.0025) * 1.1
    # The whip: the part the ear identifies as 'a round went past'.
    whip = spectral_filter(noise, 900.0, 7000.0) * envelope(t, 0.0004, 0.014) * 1.35
    # The cone sweeping past: a narrow band falling in pitch.
    chirp_freq = 1400.0 * np.exp(-t / 0.035) + 500.0
    chirp = np.sin(2.0 * np.pi * np.cumsum(chirp_freq) / SR) * envelope(t, 0.001, 0.028) * 0.24
    # The pressure step itself, felt more than heard.  Kept below the whip:
    # this sample is heard at a passing distance, not from the muzzle, and too
    # much N-wave turns the whip into a small explosion.
    n_wave = sweep(t, 180.0, 55.0, 0.020) * envelope(t, 0.0008, 0.045) * 0.42

    signal = snap + whip + chirp + n_wave

    tail_n = int(SR * 0.20)
    tail_t = np.arange(tail_n, dtype=np.float64) / SR
    ir = rng.standard_normal(tail_n) * np.exp(-tail_t / 0.055)
    ir = spectral_filter(ir, 400.0, 6000.0)
    ir /= math.sqrt(float(np.sum(ir * ir))) + 1e-9
    diffuse = convolve(signal, ir)
    diffuse /= float(np.max(np.abs(diffuse))) + 1e-9
    signal += diffuse * 0.16

    signal = spectral_filter(signal, 60.0, 12000.0)
    signal = soft_clip(signal, 1.2)
    return fade_tail(normalise(signal, 0.86), 0.04)


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    # Two takes per report, picked at random by Minecraft. A real gun does not
    # fire the same waveform twice, and pitch jitter alone cannot hide a repeat
    # heard five times in a row. The echo and the clank are single: they are
    # background layers and repeating them would be more noticeable than not.
    sounds = {
        "cannon_light": [
            cannon_report(duration=3.0, f_hi=150.0, f_lo=40.0, tau_low=0.42,
                          tau_body=0.10, crack=0.9, tail=0.30, sub=0.50, seed=1101),
            cannon_report(duration=3.1, f_hi=144.0, f_lo=42.0, tau_low=0.44,
                          tau_body=0.11, crack=0.86, tail=0.31, sub=0.52, seed=1201),
        ],
        "cannon_medium": [
            cannon_report(duration=3.5, f_hi=128.0, f_lo=34.0, tau_low=0.52,
                          tau_body=0.12, crack=0.92, tail=0.36, sub=0.58, seed=1102),
            cannon_report(duration=3.6, f_hi=134.0, f_lo=33.0, tau_low=0.54,
                          tau_body=0.13, crack=0.88, tail=0.37, sub=0.60, seed=1202),
        ],
        "cannon_heavy": [
            cannon_report(duration=4.0, f_hi=112.0, f_lo=29.0, tau_low=0.62,
                          tau_body=0.14, crack=0.95, tail=0.42, sub=0.62, seed=1103),
            cannon_report(duration=4.1, f_hi=118.0, f_lo=28.0, tau_low=0.64,
                          tau_body=0.15, crack=0.9, tail=0.43, sub=0.64, seed=1203),
        ],
        "cannon_artillery": [
            cannon_report(duration=4.4, f_hi=96.0, f_lo=24.0, tau_low=0.78,
                          tau_body=0.16, crack=0.9, tail=0.50, sub=0.68, seed=1104),
            cannon_report(duration=4.5, f_hi=101.0, f_lo=23.0, tau_low=0.80,
                          tau_body=0.17, crack=0.86, tail=0.51, sub=0.70, seed=1204),
        ],
        "autocannon": [autocannon(1105), autocannon(1205)],
        "projectile_crack": [projectile_crack(1301), projectile_crack(1302), projectile_crack(1303)],
        "cannon_echo": [distant_echo(1106)],
        "breech_clank": [breech_clank(1107)],
    }
    for name, takes in sounds.items():
        for index, samples in enumerate(takes, start=1):
            suffix = "" if len(takes) == 1 else f"_{index}"
            path = OUT / f"{name}{suffix}.ogg"
            # 0.02 is close to the quality end of libsndfile's 0..1 Ogg scale:
            # the old 0.05 was already fine, but noise tails are exactly where a
            # mid bitrate starts to sound like static, so the headroom is cheap.
            sf.write(path, samples.astype(np.float32), SR, format="OGG", subtype="VORBIS",
                     compression_level=0.02)
            rms = float(np.sqrt(np.mean(samples * samples)))
            print(f"{path.name:22s} {len(samples) / SR:5.2f}s  peak={np.max(np.abs(samples)):.2f}  rms={rms:.3f}  {path.stat().st_size} bytes")


if __name__ == "__main__":
    main()

