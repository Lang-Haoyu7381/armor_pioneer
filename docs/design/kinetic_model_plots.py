"""K2 kinetic-penetration model, second revision: spall instead of a face multiplier.

Design-only. Two figures:

  kinetic_model_curves.png - penetration from velocity/mass, and damage from the
                             behind-armour debris, replacing residual x multiplier
  spall_model_curves.png   - the debris cloud itself: mass, fragment count,
                             fragment velocity, and where the energy goes

    P(v, m) = P0 * (m / m0)^alpha * (v / v0)^beta
    t_eff   = t / cos(theta)
    R       = (P - t_eff) / P                       (>= 0 to perforate)
    m_s     = rho * (pi/4) * d_h^2 * t_eff * c/(c + t_eff)
    v_f     = kappa * v * sqrt(R)
    v_exit  = v * sqrt(R)
    E_BA    = 0.5*m_s*v_f^2 + lambda * 0.5*m*v_exit^2
    D       = E_BA / E_UNIT

E_UNIT is calibrated so that DM53 through the K2's 80 mm flank at the muzzle
deals the same 3100 damage the old 620 mm x 5 gave - the new model changes the
*shape*, not the balance point.
"""

import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

plt.rcParams["font.sans-serif"] = ["Microsoft YaHei", "SimHei", "DejaVu Sans"]
plt.rcParams["axes.unicode_minus"] = False

# --- penetration -----------------------------------------------------------
ALPHA = 0.7
BETA = 0.5
RICOCHET = 70.0
K_DRAG = -np.log(0.9604) / 1000.0

K279 = {"name": "K279", "m": 8.3, "v0": 1690.0, "P0": 600.0}
DM53 = {"name": "DM53", "m": 8.5, "v0": 1750.0, "P0": 700.0}
ROUNDS = [K279, DM53]
COLORS = {K279["name"]: "#1f77b4", DM53["name"]: "#d62728"}

# --- spall -----------------------------------------------------------------
RHO = 7850.0          # kg/m3, RHA
HOLE_FACTOR = 1.1     # hole diameter / projectile diameter
CALIBER_MM = 120.0    # K2's 120 mm smoothbore
CONFINE_MM = 100.0    # thickness at which ejection efficiency halves
KAPPA = 0.35          # fragment velocity / impact velocity at R = 1
ROD_FACTOR = 0.2      # how much of the rod's residual energy counts as BAD
FRAG_MASS_KG = 0.002  # characteristic fragment mass, for the count readout

# Over-penetration: a plate thinner than this fraction of the bore cannot stop or
# tumble the round, which exits the far side carrying its remaining energy away.
OVER_START = 0.50     # t / d at which over-penetration begins
OVER_FULL = 0.15      # t / d at which it is complete
OVER_FLOOR = 0.25     # damage left at full over-penetration

K2_FACES = {
    "炮塔正面": (700.0, 10.0),
    "首上": (650.0, 10.0),
    "首下": (200.0, 10.0),
    "炮塔侧面": (100.0, 5.0),
    "车体侧面": (80.0, 5.0),
    "车顶/车底": (40.0, 5.0),
}


def penetration(rnd, v, m=None):
    m = rnd["m"] if m is None else m
    return rnd["P0"] * (m / rnd["m"]) ** ALPHA * (v / rnd["v0"]) ** BETA


def velocity_at(rnd, metres):
    return rnd["v0"] * np.exp(-K_DRAG * np.asarray(metres, dtype=float))


def effective_thickness(t, angle_deg):
    angle = np.minimum(np.asarray(angle_deg, dtype=float), 89.0)
    return t / np.cos(np.radians(angle))


def perforation(rnd, t, angle_deg, metres=0.0):
    v = velocity_at(rnd, metres)
    p = penetration(rnd, v)
    te = effective_thickness(t, angle_deg)
    return p, te, np.clip((p - te) / p, 0.0, None)


def spall_energy(rnd, t, angle_deg, metres=0.0):
    """Behind-armour energy in joules: plate debris + the rod's residual energy."""
    v = velocity_at(rnd, metres)
    p, te, R = perforation(rnd, t, angle_deg, metres)
    hole_d = HOLE_FACTOR * CALIBER_MM / 1000.0
    area = np.pi / 4.0 * hole_d ** 2
    te_m = te / 1000.0
    m_s = RHO * area * te_m * (CONFINE_MM / (CONFINE_MM + te))
    v_f = KAPPA * v * np.sqrt(R)
    v_exit = v * np.sqrt(R)
    e_plate = 0.5 * m_s * v_f ** 2
    e_rod = ROD_FACTOR * 0.5 * rnd["m"] * v_exit ** 2
    return e_plate + e_rod, e_plate, e_rod, m_s, v_f, v_exit


# Anchor: DM53, 80 mm flank, muzzle, square on.
_E_ANCHOR = spall_energy(DM53, 80.0, 0.0, 0.0)[0]
E_UNIT = _E_ANCHOR / 3100.0     # joules per point of damage; ~1000 J = 1 HP


def overmatch_factor(t_nominal, caliber_mm=CALIBER_MM):
    """Fraction of the debris energy that stays in the vehicle: 1 thick, FLOOR thin."""
    u = np.asarray(t_nominal, dtype=float) / caliber_mm
    over = np.clip((OVER_START - u) / (OVER_START - OVER_FULL), 0.0, 1.0)
    return 1.0 - over * (1.0 - OVER_FLOOR)


def damage(rnd, t, angle_deg, metres=0.0, with_overmatch=True):
    e, *_ = spall_energy(rnd, t, angle_deg, metres)
    inside = np.asarray(angle_deg, dtype=float) < RICOCHET
    factor = overmatch_factor(t) if with_overmatch else 1.0
    return np.where(inside, e / E_UNIT * factor, 0.0)


# ---------------------------------------------------------------- key numbers
def report():
    print("== 新伤害模型（破片），DM53 对 K2（0 m, 正入射）==")
    print("E_UNIT = %.1f J / 点伤害" % E_UNIT)
    for name, (t, mult) in K2_FACES.items():
        e, ep, er, ms, vf, ve = spall_energy(DM53, t, 0.0, 0.0)
        old = max(0.0, penetration(DM53, DM53["v0"]) - t) * mult
        final = float(damage(DM53, t, 0.0))
        print("  %-8s t=%3.0f | 破片 %5.2f kg  %5.0f m/s | 过穿×%.2f | 新伤害 %7.0f | 旧伤害 %7.0f"
              % (name, t, float(np.asarray(ms)), float(np.asarray(vf)),
                 float(overmatch_factor(t)), final, old))
    print()
    print("== 甲厚扫描（DM53, 0 m, 正入射）==")
    for t in (0, 20, 50, 80, 100, 150, 200, 300, 400, 500, 600, 650, 680, 699):
        e, ep, er, ms, vf, ve = spall_energy(DM53, float(t), 0.0, 0.0)
        print("  t=%3d  m_s=%5.2f kg  N=%5.0f  v_f=%6.0f m/s  E=%7.1f kJ"
              "  过穿×%.2f  D=%7.0f"
              % (t, float(np.asarray(ms)), float(np.asarray(ms)) / FRAG_MASS_KG,
                 float(np.asarray(vf)), e / 1000.0, float(overmatch_factor(t)),
                 float(damage(DM53, float(t), 0.0))))
    print()
    print("== 过穿（薄板，DM53, 0 m）==")
    for t in (10, 20, 35, 40, 60, 80):
        f = float(overmatch_factor(t))
        base = float(damage(DM53, float(t), 0.0, with_overmatch=False))
        print("  t=%3d (t/d=%.2f) 系数=%.3f  无过穿伤害=%7.0f  最终=%7.0f"
              % (t, t / CALIBER_MM, f, base, base * f))
    print()
    print("== 角度（DM53, K2 车体侧面 80mm, 0 m）==")
    for a in (0, 15, 30, 45, 60, 69):
        print("  %2d deg  t_eff=%7.1f  D=%7.0f" % (a, effective_thickness(80.0, a),
                                                  float(damage(DM53, 80.0, float(a)))))
    print("  70 deg  -> ricochet, D=0")
    print()
    print("== 距离（DM53, K2 首下 200mm）==")
    for s in (0, 500, 1000, 2000, 3000):
        print("  %4d m  D=%7.0f" % (s, float(damage(DM53, 200.0, 0.0, float(s)))))


def _finish(fig, path, title):
    fig.suptitle(title, fontsize=15)
    fig.tight_layout(rect=(0, 0, 1, 0.95))
    fig.savefig(path, dpi=150)
    plt.close(fig)
    print("\nfigure -> %s" % path)


# -------------------------------------------------------------------- figure 1
def make_kinetic_figure(path):
    fig, axes = plt.subplots(2, 3, figsize=(17.5, 9.6))

    ax = axes[0][0]
    v = np.linspace(800, 1900, 300)
    for rnd in ROUNDS:
        ax.plot(v, penetration(rnd, v), color=COLORS[rnd["name"]], label=rnd["name"])
        ax.plot(rnd["v0"], rnd["P0"], "o", color=COLORS[rnd["name"]], ms=7)
        ax.annotate("%.0f m/s" % rnd["v0"], (rnd["v0"], rnd["P0"]),
                    textcoords="offset points", xytext=(8, -14), fontsize=9,
                    color=COLORS[rnd["name"]])
    ax.set_title("① 穿深 × 弹速（弹重固定）\nP = P0·(v/v0)^%.2f" % BETA)
    ax.set_xlabel("命中速度 (m/s)")
    ax.set_ylabel("穿深 (mm RHAe)")
    ax.grid(alpha=0.3)
    ax.legend(fontsize=9)

    ax = axes[0][1]
    m = np.linspace(4.0, 12.0, 300)
    for rnd in ROUNDS:
        ax.plot(m, penetration(rnd, rnd["v0"], m), color=COLORS[rnd["name"]], label=rnd["name"])
        ax.plot(rnd["m"], rnd["P0"], "o", color=COLORS[rnd["name"]], ms=7)
        ax.annotate("%.1f kg" % rnd["m"], (rnd["m"], rnd["P0"]),
                    textcoords="offset points", xytext=(8, -14), fontsize=9,
                    color=COLORS[rnd["name"]])
    ax.set_title("② 穿深 × 弹重（速度固定）\nP = P0·(m/m0)^%.2f" % ALPHA)
    ax.set_xlabel("弹体质量 (kg)")
    ax.set_ylabel("穿深 (mm RHAe)")
    ax.grid(alpha=0.3)
    ax.legend(fontsize=9)

    ax = axes[0][2]
    s = np.linspace(0, 4000, 300)
    ax2 = ax.twinx()
    for rnd in ROUNDS:
        ax.plot(s, penetration(rnd, velocity_at(rnd, s)), color=COLORS[rnd["name"]],
                label="%s 穿深" % rnd["name"])
        ax2.plot(s, velocity_at(rnd, s), color=COLORS[rnd["name"]], ls="--", alpha=0.7,
                 label="%s 速度" % rnd["name"])
    ax.set_title("③ 距离：速度衰减 → 穿深下降\n(由现有 0.98@1km / 0.96@2km 曲线反推速度)")
    ax.set_xlabel("射程 (m)")
    ax.set_ylabel("穿深 (mm RHAe)")
    ax2.set_ylabel("命中速度 (m/s, 虚线)")
    ax.grid(alpha=0.3)
    lines1, labels1 = ax.get_legend_handles_labels()
    lines2, labels2 = ax2.get_legend_handles_labels()
    ax.legend(lines1 + lines2, labels1 + labels2, fontsize=7.5, loc="lower left")

    ax = axes[1][0]
    a = np.linspace(0, 70, 300)
    for name, (t, _) in [("炮塔正面 700", K2_FACES["炮塔正面"]),
                         ("首上 650", K2_FACES["首上"]),
                         ("首下 200", K2_FACES["首下"]),
                         ("车体侧面 80", K2_FACES["车体侧面"])]:
        ax.plot(a, effective_thickness(t, a), label="%s mm 板" % name)
    ax.axhline(K279["P0"], color=COLORS[K279["name"]], ls=":", lw=1.4)
    ax.axhline(DM53["P0"], color=COLORS[DM53["name"]], ls=":", lw=1.4)
    ax.text(2, K279["P0"] + 15, "K279 600", color=COLORS[K279["name"]], fontsize=9)
    ax.text(2, DM53["P0"] + 15, "DM53 700", color=COLORS[DM53["name"]], fontsize=9)
    ax.axvline(RICOCHET, color="black", ls="--", lw=1)
    ax.text(RICOCHET - 2, 80, "70° 跳弹", ha="right", fontsize=9)
    ax.set_ylim(0, 1800)
    ax.set_title("④ 有效甲厚 = 板厚 / cos θ（角度只算一次）")
    ax.set_xlabel("与板法线的夹角 (deg)")
    ax.set_ylabel("有效甲厚 (mm)")
    ax.grid(alpha=0.3)
    ax.legend(fontsize=8)

    # damage vs thickness: new spall model solid, old residual x multiplier dashed
    ax = axes[1][1]
    t = np.linspace(0, 720, 600)
    ax.plot(t, damage(DM53, t, 0.0, 0.0), color=COLORS[DM53["name"]],
            label="DM53 @0m（破片 + 过穿）")
    ax.plot(t, damage(DM53, t, 0.0, 2000.0), color=COLORS[DM53["name"]], ls="--",
            label="DM53 @2km（破片 + 过穿）")
    ax.plot(t, damage(K279, t, 0.0, 0.0), color=COLORS[K279["name"]],
            label="K279 @0m（破片 + 过穿）")
    ax.plot(t, damage(DM53, t, 0.0, 0.0, with_overmatch=False), color=COLORS[DM53["name"]],
            ls="-.", alpha=0.55, label="DM53 @0m（无过穿）")
    old = np.clip(penetration(DM53, DM53["v0"]) - t, 0, None) * 10
    ax.plot(t, old, color="gray", ls=":", lw=1.6, label="旧模型：余量 ×10（DM53 0m）")
    ax.set_title("⑤ 伤害 × 甲厚（正入射，DM53/K279）\n破片云能量 × 过穿系数，不再乘固定倍率")
    ax.set_xlabel("甲厚 (mm)")
    ax.set_ylabel("单发伤害")
    ax.grid(alpha=0.3)
    ax.legend(fontsize=7.2)

    ax = axes[1][2]
    a = np.linspace(0, 69.9, 300)
    for label, thick, rnd, ls in [
            ("车顶 40mm（过穿）", 40.0, DM53, "-"),
            ("车体侧面 80mm", 80.0, DM53, "-"),
            ("首下 200mm", 200.0, DM53, "-"),
            ("车体侧面 80mm (K279)", 80.0, K279, ":")]:
        ax.plot(a, damage(rnd, thick, a), color=COLORS[rnd["name"]], ls=ls, label=label)
    ax.axvline(RICOCHET, color="black", ls="--", lw=1)
    ax.text(RICOCHET - 2, ax.get_ylim()[1] * 0.92, "70° 强制跳弹", ha="right", fontsize=9)
    ax.set_title("⑥ 伤害 × 入射角（0 m）\n角度把甲厚拉长，余量随之缩小")
    ax.set_xlabel("与板法线的夹角 (deg)")
    ax.set_ylabel("单发伤害")
    ax.grid(alpha=0.3)
    ax.legend(fontsize=8)

    _finish(fig, path,
            "K2 动能穿甲新机制：弹速/弹重决定穿深，破片云决定伤害（设计草案）")


# -------------------------------------------------------------------- figure 2
def make_spall_figure(path):
    fig, axes = plt.subplots(2, 2, figsize=(14.0, 9.2))
    t = np.linspace(0, 720, 600)

    ax = axes[0][0]
    # The ejected mass depends on the hole and the plate, not on which round made it,
    # so one line is the whole story; both darts are 120 mm here.
    ms = spall_energy(DM53, t, 0.0, 0.0)[3]
    ax.plot(t, ms, color=COLORS[DM53["name"]], label="破片质量（120 mm 弹孔）")
    ax2 = ax.twinx()
    ax2.plot(t, ms / FRAG_MASS_KG, color="#555555", ls="--", alpha=0.8,
             label="破片数（2 g/片）")
    ax.set_title("⑦ 被击穿板的破片质量与片数\nm_s = ρ·(π/4)d_h²·t_eff·c/(c+t_eff)")
    ax.set_xlabel("甲厚 (mm)")
    ax.set_ylabel("破片质量 (kg)")
    ax2.set_ylabel("破片数（2 g/片）")
    ax.grid(alpha=0.3)
    lines1, labels1 = ax.get_legend_handles_labels()
    lines2, labels2 = ax2.get_legend_handles_labels()
    ax.legend(lines1 + lines2, labels1 + labels2, fontsize=8, loc="upper left")

    ax = axes[0][1]
    for rnd in ROUNDS:
        vf = spall_energy(rnd, t, 0.0, 0.0)[4]
        ve = spall_energy(rnd, t, 0.0, 0.0)[5]
        ax.plot(t, vf, color=COLORS[rnd["name"]], label="%s 破片速度" % rnd["name"])
        ax.plot(t, ve, color=COLORS[rnd["name"]], ls="--", alpha=0.6,
                label="%s 弹体余速" % rnd["name"])
    ax.set_title("⑧ 破片速度与弹体余速\nv_f = κ·v·√R，R = 1 − t_eff/P")
    ax.set_xlabel("甲厚 (mm)")
    ax.set_ylabel("速度 (m/s)")
    ax.grid(alpha=0.3)
    ax.legend(fontsize=7.5)

    ax = axes[1][0]
    e, ep, er, *_ = spall_energy(DM53, t, 0.0, 0.0)
    ax.plot(t, ep / 1000.0, color="#2ca02c", label="板破片能量")
    ax.plot(t, er / 1000.0, color="#9467bd", label="弹体残余能量×0.2")
    ax.plot(t, e / 1000.0, color="black", lw=1.6, label="总舱内能量（再乘过穿系数 = 伤害）")
    ax.set_title("⑨ 能量分配\nE_BA = ½m_s v_f² + 0.2·½m v_exit²")
    ax.set_xlabel("甲厚 (mm)")
    ax.set_ylabel("能量 (kJ)")
    ax.grid(alpha=0.3)
    ax.legend(fontsize=8)

    ax = axes[1][1]
    tt = np.linspace(0, 120, 400)
    ax.plot(tt, overmatch_factor(tt), color="#8c564b", lw=2.0,
            label="过穿系数（120 mm 弹）")
    ax.axvline(OVER_START * CALIBER_MM, color="black", ls=":", lw=1)
    ax.axvline(OVER_FULL * CALIBER_MM, color="black", ls=":", lw=1)
    ax.text(OVER_START * CALIBER_MM - 1, OVER_FLOOR + 0.03, "0.5d = 60mm",
            ha="right", fontsize=9)
    ax.text(OVER_FULL * CALIBER_MM + 1, OVER_FLOOR + 0.03, "0.15d = 18mm", fontsize=9)
    ax.annotate("K2 车顶 40mm → ×%.2f" % float(overmatch_factor(40.0)),
                (40.0, float(overmatch_factor(40.0))), textcoords="offset points",
                xytext=(14, 26), fontsize=9, color="#8c564b",
                arrowprops=dict(arrowstyle="-", color="#8c564b", lw=0.8))
    ax.set_ylim(0, 1.1)
    ax.set_title("⑩ 过穿系数 × 板厚/弹径\n板越薄，弹体越可能从另一侧带走剩余能量")
    ax.set_xlabel("名义板厚 (mm)")
    ax.set_ylabel("伤害系数")
    ax.grid(alpha=0.3)
    ax.legend(fontsize=8)

    _finish(fig, path,
            "K2 击穿后破片模型：破片从哪里来、有多快、能量怎么分、薄板如何过穿")


if __name__ == "__main__":
    report()
    make_kinetic_figure("kinetic_model_curves.png")
    make_spall_figure("spall_model_curves.png")
