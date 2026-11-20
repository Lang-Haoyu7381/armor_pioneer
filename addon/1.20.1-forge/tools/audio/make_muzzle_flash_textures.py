"""Draw the layered muzzle-flash sprites used by the addon's additive particles.

Three shape families, all white with an alpha mask so the particle can tint
them without a second image:

* ``muzzle_flash_0..3`` - irregular fireballs for the hot core and the near
  part of the jet.  The outline wobbles at several angular frequencies, so no
  two frames share a silhouette.
* ``muzzle_jet_0..2`` - the same idea stretched along one axis and torn up
  more aggressively.  The particle rolls each quad at random, which turns
  three shapes into a turbulent chain rather than a repeating stamp.
* ``muzzle_spark_0`` - a small hot point with a symmetric glint.

They are drawn for additive blending: the alpha channel is the emitted
intensity, and the RGB is white because the particle supplies the colour.

    uv run --with numpy --with pillow python tools/audio/make_muzzle_flash_textures.py
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "src" / "main" / "resources" / "assets" / "ywzj_vehicle_armor" / "textures" / "particle"


def blob(size: int, seed: int, elongation: float, roughness: float, torn: float) -> np.ndarray:
    """One lumpy fireball, elongated along x by ``elongation``.

    The outline is made of three low angular harmonics - broad lobes, not rays.
    A few small blobs are then placed off-centre and added, which is what makes
    the silhouette read as burning gas rather than as a star.  High-frequency
    angular terms were tried and removed: at additive intensity they turn the
    image into a sunburst.
    """
    rng = np.random.default_rng(seed)
    yy, xx = np.mgrid[-1.0:1.0:size * 1j, -1.0:1.0:size * 1j]
    stretched = xx / elongation
    radius = np.sqrt(stretched * stretched + yy * yy)
    theta = np.arctan2(yy, stretched)

    phases = rng.uniform(0.0, 2.0 * np.pi, 5)
    wobble = (1.0
              + roughness * 0.30 * np.sin(3.0 * theta + phases[0])
              + roughness * 0.18 * np.sin(5.0 * theta + phases[1])
              + roughness * 0.10 * np.sin(2.0 * theta + phases[2]))
    shaped = radius / np.maximum(wobble, 0.40)

    body = np.exp(-(shaped / 0.56) ** 5)
    core = np.exp(-(radius / 0.16) ** 2)

    lumps = np.zeros_like(radius)
    lobes = 3 if elongation < 1.4 else 4
    for _ in range(lobes):
        cx = rng.uniform(-0.38, 0.38) * elongation
        cy = rng.uniform(-0.38, 0.38)
        spread = rng.uniform(0.14, 0.27)
        lumps += 0.34 * np.exp(-(((stretched - cx) / spread) ** 2
                                 + ((yy - cy) / spread) ** 2))

    # A soft dark lane through the middle, so the blob is not a uniform disc.
    lane = 1.0 - torn * 0.30 * (0.5 + 0.5 * np.sin(4.0 * theta + phases[3])) * body

    alpha = (0.95 * body * lane + 0.85 * core + 0.60 * np.clip(lumps, 0.0, 1.0))
    alpha *= np.clip(1.10 - radius, 0.0, 1.0)
    return np.clip(alpha, 0.0, 1.0)


def spark(size: int, seed: int) -> np.ndarray:
    """A small hot point with a four-ray glint.

    It is deliberately symmetric: the particle rolls its quad at random, and a
    directional tail would then point in a random screen direction instead of
    along the ember's own motion.  The motion is carried by the particle's
    velocity, not by the sprite.
    """
    rng = np.random.default_rng(seed)
    yy, xx = np.mgrid[-1.0:1.0:size * 1j, -1.0:1.0:size * 1j]
    radius = np.sqrt(xx * xx + yy * yy)
    core = np.exp(-(radius / (0.26 + 0.04 * rng.random())) ** 2)
    glint = 0.32 * (np.exp(-(np.abs(xx) / 0.055) ** 2) * np.exp(-np.abs(yy) / 0.8)
                    + np.exp(-(np.abs(yy) / 0.055) ** 2) * np.exp(-np.abs(xx) / 0.8))
    alpha = (core + glint) * np.clip(1.25 - radius, 0.0, 1.0)
    return np.clip(alpha, 0.0, 1.0)


def rgba(alpha: np.ndarray) -> np.ndarray:
    out = np.zeros((*alpha.shape, 4), dtype=np.uint8)
    out[..., 0] = 255
    out[..., 1] = 255
    out[..., 2] = 255
    out[..., 3] = np.round(alpha * 255.0).astype(np.uint8)
    return out


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)

    core = (
        (0, 1.00, 0.55, 0.40),
        (1, 1.08, 0.68, 0.55),
        (2, 0.94, 0.44, 0.30),
        (3, 1.14, 0.78, 0.65),
    )
    for index, elongation, roughness, torn in core:
        path = OUT / f"muzzle_flash_{index}.png"
        Image.fromarray(rgba(blob(64, 100 + index, elongation, roughness, torn)), mode="RGBA").save(path)
        print(f"{path.name}  {path.stat().st_size} bytes")

    for index in range(3):
        path = OUT / f"muzzle_jet_{index}.png"
        Image.fromarray(rgba(blob(64, 200 + index, 2.1 + index * 0.22, 0.85, 0.55)), mode="RGBA").save(path)
        print(f"{path.name}  {path.stat().st_size} bytes")

    path = OUT / "muzzle_spark_0.png"
    Image.fromarray(rgba(spark(32, 300)), mode="RGBA").save(path)
    print(f"{path.name}  {path.stat().st_size} bytes")


if __name__ == "__main__":
    main()
