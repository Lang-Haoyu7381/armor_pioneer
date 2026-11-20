"""High-explosive damage as fragments, for the K2 design note.

Both outcomes come out of the charge and the plate:

  penetrated (P_he >= t_eff): the shell bursts inside.
      E_in = eta_in * E_blast                         (eta_in = 1.0)
  not penetrated: the blast scabs the plate's inner face.
      t_scab = scab_factor * C^(1/3)
      eta    = eta_scab * clamp(1 - t_eff / t_scab, 0, 1)
      E_in   = eta * E_blast
  D = E_in / HE_ENERGY_PER_DAMAGE

Calibration: a 120 mm HE (3.5 kg TNT equivalent) bursting inside keeps the
current 2000 damage. The old 300..1000 fixed blast band is gone; a plate too
thick to scab takes no internal damage at all.
"""

import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

import kinetic_model_plots as km
import crew_chemical_plots as cc

plt.rcParams["font.sans-serif"] = ["Microsoft YaHei", "SimHei", "DejaVu Sans"]
plt.rcParams["axes.unicode_minus"] = False

HE_CHARGE_KG = 3.5
TNT_J_PER_KG = 4.184e6
HE_PENETRATION = 60.0
ETA_IN = 1.0
ETA_SCAB = 0.8
SCAB_FACTOR = 72.0        # mm of scabbable plate per kg^(1/3)
HE_DAMAGE_CAL = 2000.0    # the current flat figure for a 120 mm bursting inside
HE_ENERGY_PER_DAMAGE = ETA_IN * HE_CHARGE_KG * TNT_J_PER_KG / HE_DAMAGE_CAL

K2_HE = {
    "炮塔正面": 1150.0,
    "首上": 1050.0,
    "首下": 300.0,
    "炮塔侧面": 300.0,
    "车体侧面": 120.0,
    "车体后部": 90.0,
    "炮塔顶": 30.0,
    "车体顶": 30.0,
    "履带端面": 80.0,
}


def he_damage(t, charge_kg=HE_CHARGE_KG, penetration=HE_PENETRATION, angle_deg=0.0):
    te = km.effective_thickness(t, angle_deg)
    te = np.asarray(te, dtype=float)
    charge = np.asarray(charge_kg, dtype=float)
    blast = charge * TNT_J_PER_KG
    inside = te <= penetration
    t_scab = SCAB_FACTOR * charge ** (1.0 / 3.0)
    eta = ETA_SCAB * np.clip(1.0 - te / t_scab, 0.0, 1.0)
    energy = np.where(inside, ETA_IN * blast, eta * blast)
    return energy / HE_ENERGY_PER_DAMAGE


def report():
    print("== HE 校准 ==")
    print("  装药 %.1f kg TNT, E_blast = %.2f MJ, 每点伤害 %.1f J"
          % (HE_CHARGE_KG, HE_CHARGE_KG * TNT_J_PER_KG / 1e6, HE_ENERGY_PER_DAMAGE))
    print("  击穿 (t<=%.0f): D=%.0f" % (HE_PENETRATION, float(he_damage(40.0))))
    print()
    print("== 未击穿的板崩落（120mm HE）==")
    for t in (60, 61, 70, 80, 90, 100, 109, 120):
        print("  t=%3d  D=%7.0f" % (t, float(he_damage(float(t)))))
    print()
    print("== K2 HE 表 ==")
    for name, t in K2_HE.items():
        print("  %-8s t=%4.0f  D=%7.0f" % (name, t, float(he_damage(t))))
    print()
    print("== 乘员（HE）==")
    for label, d in [("击穿 2000", 2000), ("80mm 崩落 428", 428),
                     ("100mm 崩落 136", 136), ("厚板 0", 0)]:
        d = float(d)
        print("  %-16s D=%5.0f  p=%.3f  伤势=%.2f"
              % (label, d, float(cc.crew_probability(d)), float(cc.crew_severity(d))))


def make_figure(path):
    fig, axes = plt.subplots(2, 2, figsize=(14.0, 9.2))

    ax = axes[0][0]
    t = np.linspace(0, 150, 1200)
    d = he_damage(t)
    ax.plot(t, d, color="#d62728", lw=2, label="新：击穿/崩落")
    ax.axvline(HE_PENETRATION, color="black", ls=":", lw=1)
    ax.text(HE_PENETRATION + 1, 1200, "穿透极限 60mm", fontsize=9)
    old_t = np.linspace(HE_PENETRATION, 150, 300)
    old = 1000.0 + (300.0 - 1000.0) * (old_t - HE_PENETRATION) / (900.0 - HE_PENETRATION)
    ax.plot(old_t, old, color="gray", ls="--", lw=1.4, label="旧：固定 1000→300 带")
    ax.set_xlim(0, 150)
    ax.set_ylim(0, 2200)
    ax.set_title("15. 120mm HE 伤害 × 甲厚\n击穿=舱内爆轰，未击穿=板崩落，厚板为 0")
    ax.set_xlabel("甲厚 (mm)")
    ax.set_ylabel("伤害")
    ax.grid(alpha=0.3)
    ax.legend(fontsize=8)

    ax = axes[0][1]
    c = np.linspace(0.5, 6.0, 400)
    ax.plot(c, he_damage(40.0, charge_kg=c), color="#d62728", label="40mm 板（击穿）")
    ax.plot(c, he_damage(80.0, charge_kg=c), color="#1f77b4", label="80mm 板（崩落）")
    ax.axvline(HE_CHARGE_KG, color="black", ls=":", lw=1)
    ax.text(HE_CHARGE_KG + 0.1, 300, "K2 3.5 kg", fontsize=9)
    ax.set_title("16. 伤害 × 装药量\n击穿时线性，崩落时还受可崩落厚度限制")
    ax.set_xlabel("装药 (kg TNT 当量)")
    ax.set_ylabel("伤害")
    ax.grid(alpha=0.3)
    ax.legend(fontsize=8)

    ax = axes[1][0]
    a = np.linspace(0, 69.9, 600)
    d = he_damage(40.0, angle_deg=a)
    d = np.where(a < km.RICOCHET, d, 0.0)
    ax.plot(a, d, color="#d62728", lw=2, label="40mm 车顶")
    ax.axvline(km.RICOCHET, color="black", ls="--", lw=1)
    ax.text(km.RICOCHET - 2, 1800, "70° 跳弹", ha="right", fontsize=9)
    ax.set_xlim(0, 70)
    ax.set_ylim(0, 2200)
    ax.set_title("17. HE 伤害 × 入射角（40mm 板）\n拉长甲厚后先失去穿透、再失去崩落")
    ax.set_xlabel("与板法线的夹角 (deg)")
    ax.set_ylabel("伤害")
    ax.grid(alpha=0.3)
    ax.legend(fontsize=8)

    ax = axes[1][1]
    dd = np.linspace(0, 3500, 500)
    ax.plot(dd, cc.crew_probability(dd), color="#d62728", lw=2, label="每名乘员命中概率")
    for label, x in [("击穿 2000", 2000), ("80mm 崩落 428", 428),
                     ("100mm 崩落 136", 136)]:
        ax.plot(x, float(cc.crew_probability(x)), "o", color="black", ms=6)
        ax.annotate(label, (x, float(cc.crew_probability(x))), textcoords="offset points",
                    xytext=(-10, 12), ha="right", fontsize=8)
    ax.set_xlim(0, 3500)
    ax.set_ylim(0, 1.0)
    ax.set_title("18. HE 的乘员命中率\n与动能弹共用 dose 模型")
    ax.set_xlabel("舱内伤害 D")
    ax.set_ylabel("命中概率")
    ax.grid(alpha=0.3)
    ax.legend(fontsize=8)

    fig.suptitle("K2：高爆弹的破片化方案（设计草案，120mm HE / 3.5kg 装药）", fontsize=15)
    fig.tight_layout(rect=(0, 0, 1, 0.95))
    fig.savefig(path, dpi=150)
    plt.close(fig)
    print("\nfigure -> %s" % path)


if __name__ == "__main__":
    report()
    make_figure("he_model_curves.png")
