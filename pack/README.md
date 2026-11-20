# 装甲先锋载具包 `armor_pioneer`

> 返回 [项目首页](../README.md)

一个「永无止境：载具」(`ywzj_vehicle`) 的载具包，包含 7 辆主战坦克，并为它们提供完整的操控、装甲与乘员行为。

| 载具 | 命名空间 id | 主炮 | 特色 |
|---|---|---|---|
| VT-5 | `armor_pioneer:vt5` | 105 mm 五弹种循环 | 五发弹种（BTA-2 / DTB2 / BTM1 / DTP2 / GP105），12 管烟雾，牵引钩 |
| VT-4 | `armor_pioneer:vt4` | 125 mm | 12 管烟雾，牵引钩 |
| K2 | `armor_pioneer:k2` | 120 mm | 21 管烟雾，KSTAM-II 攻顶弹，侧倾悬挂 |
| 10 式 | `armor_pioneer:type10` | 120 mm | 8 管烟雾，侧倾悬挂 |
| 天马-20 | `armor_pioneer:tianma20` | 125 mm | 12 管烟雾 |
| ZTZ-100 | `armor_pioneer:ztz100` | 105 mm | 混动系统（`G` 切换柴油 / 纯电） |
| 豹2A4 | `armor_pioneer:leopard2a4` | 120 mm | 16 管烟雾 |

版本 **1.1.6**，依赖 `ywzj_vehicle` **`[0.6.3,)`**。

## 安装

**方式一：解压成文件夹**（推荐，改模型和数值不用重新打包）

```
.minecraft/versions/<版本>/limitless_vehicle/armor_pioneer-1.1.6-0.6.3/
├── vehicle_pack.meta.json
├── assets/
└── data/
```

**方式二：直接放 zip**

```
.minecraft/versions/<版本>/limitless_vehicle/armor_pioneer-1.1.6.zip
```

zip 的**根目录**必须直接是 `vehicle_pack.meta.json`、`assets/`、`data/`，不要再套一层文件夹。

> ⚠️ **同一命名空间同时只能存在一个包。** 文件夹和 zip 同时存在时，基础模组只收扫描到的第一个，另一个打一行 `Duplicated namespace`。换包时把旧的移出 `limitless_vehicle/`，不要只改后缀留在原地——**后缀为 `.bak` 的目录仍然会被扫描到并视为重复包**。

## 目录结构

```
armor_pioneer/
├── vehicle_pack.meta.json    # 包元数据（单行 UTF-8，无 BOM，无末尾换行）
├── pack.png                  # 列表封面
├── assets/armor_pioneer/     # 客户端资源
│   ├── animations/bedrock/entity/       # 动画（含开火、舱盖）
│   ├── animation_controllers/           # 动画控制器（骨骼绑定、履带、事件动画）
│   ├── display/{vehicle,weapon,decoration}/  # 显示模型定义（Blockbench）
│   ├── lang/                            # zh_cn / en_us
│   ├── models/bedrock/{entity,decoration,effect}/
│   ├── sounds/weapon/artillery/
│   └── textures/{entity,decoration,slot}/
└── data/armor_pioneer/       # 服务端数据
    ├── models/bedrock/vehicle/          # 结构模型（骨骼父级、碰撞盒来源）
    ├── vehicles/                        # 7 个载具定义（部件列表、武器、座位）
    ├── weapons/                         # 34 个武器定义（弹道、装填、弹药）
    ├── recipes/                         # 1.20.1 配方（forge: 标签、result.nbt）
    └── recipe/                          # 1.21 配方（c: 标签、components）
```

`recipes/`（复数）是 1.20.1 形式，`recipe/`（单数）是 1.21 形式，两者都保留，基础模组按版本各取所需。

## 文件格式约定

这个包的 JSON 是**手工维护**的，格式本身就是约定的一部分：

- **换行统一 CRLF**，缩进 4 空格，**无 BOM**。
- `vehicle_pack.meta.json` 是**单行**、无末尾换行。
- `data/armor_pioneer/weapons/cannon_105mm.json` 是**压缩成一行**的；同族其它 105 mm 武器是 4 空格缩进。
- `assets/armor_pioneer/animation_controllers/vt4_controller.json` 与 `vt5_controller.json` 含有 `//` 注释（基础模组用宽松解析器读取）。
- 编辑前请先看 [docs/载具包改动.md](../docs/载具包改动.md)。

## 配套

- 装甲数值不在这里，而在附属模组的 `config/ywzj_vehicle_armor.json`。
- `patchers/` 是 1.0.0 时代对着 zip 改包的 PowerShell 补丁脚本，1.1.6 起包已改为文件夹形式，这些脚本**仅作存档参考**。
