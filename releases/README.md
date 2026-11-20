# 发行版

> 返回 [项目首页](../README.md)

本目录保存可以直接使用的构建产物。校验值见 [`SHA256SUMS.txt`](SHA256SUMS.txt)，可用 `certutil -hashfile <文件> SHA256` 核对。

## Minecraft 1.20.1 / Forge 47.x

| 文件 | 大小 | 说明 |
|---|---:|---|
| `ywzj_vehicle_armor-2.0.22.jar` | 763,185 B | 附属模组，功能完整的 1.20.1 主线 |

- 放入 `.minecraft/versions/1.20.1-Forge_47.4.2/mods/`
- 需要 `ywzj_vehicle-1.20.1-0.6.3.jar` 或更高
- 同一 `modId` 只能保留一份

## Minecraft 1.21.1 / NeoForge 21.1

| 文件 | 大小 | 说明 |
|---|---:|---|
| `ywzj_vehicle_armor-2.0.2-1.21.1.jar` | 718,340 B | 附属模组，独立移植分支 |

- 放入 `.minecraft/versions/1.21.1-NeoForge_21.1.230/mods/`
- 需要 `ywzj_vehicle-1.21.1-0.6.3.jar` 或更高
- 尚未移植 2.0.10+ 的部分特性，见 [addon/README.md](../addon/README.md)

## 载具包

| 文件 | 大小 | 说明 |
|---|---:|---|
| `armor_pioneer-1.1.6.zip` | 5,256,678 B | 装甲先锋载具包，7 辆坦克 |

- 放入 `.minecraft/versions/<版本>/limitless_vehicle/`
- zip 根目录直接包含 `vehicle_pack.meta.json`
- **同一命名空间只能有一份**：不要与同名文件夹或旧版 zip 并存

## 关于版本号

两个平台的版本号不同是正常的：

- `2.0.22` 是 1.20.1 主线的第 22 个 2.0 系列小版本；
- `2.0.2-1.21.1` 是 1.21.1 分支自己的计数，后缀标明平台。

两者不是「2.0.22 比 2.0.2 新」的关系。
