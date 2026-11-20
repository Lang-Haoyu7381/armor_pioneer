"""Crew wound dose and the chemical-round proposal, for the K2 design note.

Crew: the same behind-armour damage number that the vehicle takes is turned into
a wound dose. Each crew member in the struck compartment rolls independently.

HEAT: a jet is consumed by the plate, so there is no over-penetration and no
velocity/mass scaling. Damage comes from the residual jet length:

    r = P_jet - t/cos(theta)
    D = D_rated * (r / P_jet)^0.65

calibrated so the K2's own 480 mm HEAT at its 120 mm hull flank keeps the
current 1440 damage; the fixed chemical face multiplier then goes away.

HE is unchanged: its damage is already a flat charge figure, not residual x
multiplier.
"""

import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

import kinetic_model_plots as km

plt.rcParams["font.sans-serif"] = ["Microsoft YaHei", "SimHei", "DejaVu Sans"]
plt.rcParams["axes.unicode_minus"] = False

CREW_REFERENCE = 3000.0   # damage at which one crew member is certain to be hit
CREW_EXPOSURE = 0.6       # fraction of the compartment the cone covers
CREW_MAX_P = 0.95
CREW_SEVERITY = 11.5      # maps dose 1.0 to the current maximum of 12

HEAT_P = 480.0            # the K2's 120 mm shaped charge
HEAT_EXPONENT = 0.65
HEAT_CAL_PLATE = 120.0    # hull flank in the chemical table
HEAT_CAL_MULT = 4.0       # old side multiplier
HEAT_CAL_DAMAGE = (HEAT_P - HEAT_CAL_PLATE) * HEAT_CAL_MULT    # 1440
HEAT_D_RATED = HEAT_CAL_DAMAGE / ((HEAT_P - HEAT_CAL_PLATE) / HEAT_P) ** HEAT_EXPONENT

# K2 chemical table (the `heat` override), with the old face multiplier.
K2_CHEM = {
    "炮塔正面": (1150.0, 6.0),
    "首上": (1050.0, 6.0),
    "首下": (300.0, 6.0),
    "炮塔侧面": (300.0, 4.0),
    "车体侧面": (120.0, 4.0),
    "炮塔顶": (60.0, 4.0),
}


def crew_probability(damage):
    dose = np.asarray(damage, dtype=float) / CREW_REFERENCE
    return np.clip(dose * CREW_EXPOSURE, 0.0, CREW_MAX_P)


def crew_severity(damage):
    dose = np.asarray(damage, dtype=float) / CREW_REFERENCE
    return np.clip(0.5 + dose * CREW_SEVERITY, 0.5, 12.0)


def heat_damage(t, angle_deg=0.0):
    te = km.effective_thickness(t, angle_deg)
    r = np.clip(HEAT_P - te, 0.0, None)
    return HEAT_D_RATED * (r / HEAT_P) ** HEAT_EXPONENT


def heat_old(t, multiplier):
    r = np.clip(HEAT_P - np.asarray(t, dtype=float), 0.0, None)
    return r * multiplier


def report():
    print("== 乘员剂量（伤害 D → 每人）==")
    for label, d in [("80mm 侧面", 3100), ("40mm 车顶", 1927), ("20mm 薄板", 816),
                     ("650mm 首上", 311), ("HEAT 侧面 120mm", 1440)]:
        print("  %-16s D=%4d  p=%.3f  伤势=%.2f"
              % (label, d, float(crew_probability(d)), float(crew_severity(d))))
    print()
    print("== HEAT（K2 现行 480mm 弹，化学表）==")
    print("  D_rated = %.1f" % HEAT_D_RATED)
    for name, (t, mult) in K2_CHEM.items():
        new = float(heat_damage(t))
        old = float(heat_old(t, mult))
        print("  %-8s t=%4.0f  新=%7.1f  旧=%7.1f" % (name, t, new, old))


def make_figure(path):
    fig, axes = plt.subplots(2, 2, figsize=(14.0, 9.2))

    d = np.linspace(0, 3500, 500)
    ax = axes[0][0]
    ax.plot(d, crew_probability(d), color="#d62728", lw=2, label="每名乘员命中概率")
    for label, x in [("80mm 侧面 3100", 3100), ("40mm 车顶 1927", 1927),
                     ("20mm 薄板 816", 816), ("650mm 首上 311", 311)]:
        ax.plot(x, float(crew_probability(x)), "o", color="black", ms=6)
        ax.annotate(label, (x, float(crew_probability(x))), textcoords="offset points",
                    xytext=(-10, 12), ha="right", fontsize=8)
    ax.set_xlim(0, 3500)
    ax.set_ylim(0, 1.0)
    ax.set_title("11. 乘员命中概率 × 舱内伤害\np = 0.6 · D / 3000，上限 0.95，每人独立判定")
    ax.set_xlabel("舱内伤害 D")
    ax.set_ylabel("命中概率")
    ax.grid(alpha=0.3)
    ax.legend(fontsize=8)

    ax = axes[0][1]
    ax.plot(d, crew_severity(d), color="#9467bd", lw=2, label="伤势（0.5–12）")
    for label, x in [("3100", 3100), ("1927", 1927), ("816", 816), ("311", 311)]:
        ax.plot(x, float(crew_severity(x)), "o", color="black", ms=6)
        ax.annotate(label, (x, float(crew_severity(x))), textcoords="offset points",
                    xytext=(-8, 10), ha="right", fontsize=8)
    ax.set_xlim(0, 3500)
    ax.set_ylim(0, 13)
    ax.set_title("12. 乘员伤势 × 舱内伤害\n0.5 + 11.5 · D/3000")
    ax.set_xlabel("舱内伤害 D")
    ax.set_ylabel("伤势")
    ax.grid(alpha=0.3)
    ax.legend(fontsize=8)

    ax = axes[1][0]
    r = np.linspace(0, HEAT_P, 500)
    ax.plot(r / HEAT_P, HEAT_D_RATED * (r / HEAT_P) ** HEAT_EXPONENT,
            color="#ff7f0e", lw=2, label="新：D = D_rated·(r/P)^0.65")
    ax.plot(r / HEAT_P, r * 4.0, color="gray", ls=":", lw=1.6, label="旧：余量 ×4（侧面）")
    for t in (60, 120, 300):
        rr = HEAT_P - t
        val = HEAT_D_RATED * (rr / HEAT_P) ** HEAT_EXPONENT
        ax.plot(rr / HEAT_P, val, "o", color="black", ms=6)
        ax.annotate("%.0fmm 板" % t, (rr / HEAT_P, val),
                    textcoords="offset points", xytext=(-8, 10), ha="right", fontsize=8)
    ax.set_xlim(0, 1.0)
    ax.set_title("13. HEAT 伤害 × 射流余量\n射流被消耗，没有过穿")
    ax.set_xlabel("r / P_jet")
    ax.set_ylabel("伤害")
    ax.grid(alpha=0.3)
    ax.legend(fontsize=8)

    ax = axes[1][1]
    t = np.linspace(0, 500, 500)
    ax.plot(t, heat_damage(t), color="#ff7f0e", lw=2, label="新：射流余量曲线")
    ax.plot(t, heat_old(t, 4.0), color="gray", ls=":", lw=1.6, label="旧：余量 ×4（侧面）")
    for name, (tt, mult) in K2_CHEM.items():
        if tt > HEAT_P:
            continue
        val = float(heat_damage(tt))
        ax.plot(tt, val, "o", color="black", ms=6)
        ax.annotate(name, (tt, val), textcoords="offset points",
                    xytext=(8, 8), fontsize=8)
    ax.set_xlim(0, 500)
    ax.set_title("14. K2 的 480mm HEAT 对各厚度（化学表）\n用射流余量取代固定倍率")
    ax.set_xlabel("化学等效甲厚 (mm)")
    ax.set_ylabel("伤害")
    ax.grid(alpha=0.3)
    ax.legend(fontsize=8)

    fig.suptitle("K2：乘员剂量与化学弹（HEAT）方案（设计草案）", fontsize=15)
    fig.tight_layout(rect=(0, 0, 1, 0.95))
    fig.savefig(path, dpi=150)
    plt.close(fig)
    print("\nfigure -> %s" % path)


if __name__ == "__main__":
    report()
    make_figure("crew_chemical_curves.png")
