# 附属模组源码 `ywzj_vehicle_armor`

> 返回 [项目首页](../README.md)

本目录有两个**互相独立**的源码树。它们不是同一份代码的两个编译目标，类名、方法名、网络层都不同，改动需要分别进行。

| 目录 | 平台 | 产物 | `CONFIG_VERSION` |
|---|---|---|---|
| [`1.20.1-forge/`](1.20.1-forge/) | Minecraft 1.20.1 / Forge 47.x | `ywzj_vehicle_armor-2.0.22.jar` | 110 |
| [`1.21.1-neoforge/`](1.21.1-neoforge/) | Minecraft 1.21.1 / NeoForge 21.1 | `ywzj_vehicle_armor-2.0.2-1.21.1.jar` | 91 |

## 目录结构

```
1.20.1-forge/
├── src/main/java/com/ywzjvehicle/armor/   # 106 个源文件
│   ├── client/                           # HUD、按键、客户端状态
│   ├── mixin/                            # 注入点
│   └── network/                          # 网络协议（频道版本 11）
├── src/main/resources/                   # lang、贴图、声音、着色器、mixin 配置
├── tools/                                # 离线检查工具（43 个 java + 音频/贴图生成脚本）
├── build.ps1                             # javac 构建
├── verify.ps1                            # 42 项自检
└── add_vehicle.ps1                       # 新载具适配辅助

1.21.1-neoforge/
├── src/main/java/com/ywzjvehicle/armor/   # 96 个源文件（Mojang 官方名）
├── src/main/resources/                   # META-INF/neoforge.mods.toml
├── tools/                                # PackLocator、Mixin 审计、映射生成脚本
├── build1211.ps1
├── verify1211_addon.ps1
└── PORT_1.21.1.md                        # 移植记录
```

## 构建

构建脚本直接调用 `javac`，**不需要 Gradle**，但需要一台装好对应版本 Forge / NeoForge 的机器来提供编译依赖。

```powershell
# 1.20.1
cd addon/1.20.1-forge
powershell -ExecutionPolicy Bypass -File build.ps1
powershell -ExecutionPolicy Bypass -File verify.ps1

# 1.21.1
cd addon/1.21.1-neoforge
powershell -ExecutionPolicy Bypass -File build1211.ps1
python tools/mixin_audit.py
powershell -ExecutionPolicy Bypass -File verify1211_addon.ps1
```

默认路径是 `E:\.minecraft`，用环境变量覆盖：

| 变量 | 作用 |
|---|---|
| `MC_ROOT` | `.minecraft` 根目录 |
| `YWZJ_JAR` | 指定基础模组 jar（默认自动挑实例 `mods/` 里最新的） |
| `TACZ_JAR` | 指定 TACZ jar（仅编译期需要） |

> ⚠️ **编译用的基础模组 jar 必须是游戏实际加载的那一个。** `javac` 只检查注入方法体里的调用；对着另一个同名版本编译可以成功，但游戏第一次 tick 会抛 `NoSuchMethodError`。脚本默认自动解析，就是为了不让这件事靠人记得。

完整说明见 [docs/构建.md](../docs/构建.md)。

## 自检工具

`tools/` 下的检查工具都在 `verify.ps1` 里逐个运行，任何一项失败都会让整次验证失败：

| 类别 | 工具 |
|---|---|
| Mixin 与网络 | `MixinCheck` |
| 装甲与弹道 | `PenetrationTable`、`SlopeCheck`、`KineticCheck`、`ChemCheck`、`PeleCheck`、`ApheCheck`、`TopAttackCheck`、`TowCheck` |
| 载具适配 | `VehicleScaffold`、`SuspensionCheck`、`EngineCheck`、`SmokeCheck`、`SightCheck`、`PeriscopeCheck`、`LeopardAddonCheck`、`Vt4EraCheck`、`TianmaEraCheck` |
| 乘员与操控 | `CrewCheck`、`CrewOrderCheck`、`GunnerCheck`、`KeyBindCheck`、`HybridCheck` |
| 载具包与配置 | `LoaderCheck`、`RecipeCheck`、`FleetCheck`、`UpgradeCheck`、`AircraftCheck` |
| 其他系统 | `ApsCheck`、`EffectsCheck`、`ReloadCheck`、`FordingCheck`、`ExhaustCheck`、`SpeedCheck`、`CollisionCheck`、`VanillaCheck`、`TaczCheck`、`UiCheck`、`ConfigDump`、`FaceProbe`、`WheelProbe` |

`AmmoCompendium` 生成弹药合集数据，`PackLocator` 是其余工具共用的包定位器（无 `main`）。

## 1.21.1 分支的状态

1.21.1 树跟踪的是 **2026-11-18 的 2.0.2 状态**。以下 1.20.1 已具备的能力尚未移植：

- KSTAM-II 攻顶（`TopAttack`、`MissileTopAttackMixin`）
- 动能破片模型（`SpallModel`）
- 化学破甲模型（`ChemicalModel`）
- 横向效应弹 PELE（`PeleModel`）
- 穿甲榴弹 APHE 的专用结算
- 复合装甲方向模型
- 原版爆炸与碰撞接管（`VanillaExplosion`、`VehicleCollision`、`VehicleRamming`、`ExplosionMixin`）
- 固定翼动力倍率（`FixedWingVehiclePowerMixin`）

逐项技术细节见 [PORT_1.21.1.md](1.21.1-neoforge/PORT_1.21.1.md) 与 [docs/1.21.1-移植说明.md](../docs/1.21.1-移植说明.md)。
