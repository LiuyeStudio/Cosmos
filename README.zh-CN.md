# Cosmos

[English](README.md) | **简体中文**

> **1.2.0** · Paper 26.3 / Folia 26.3

Cosmos 在你的世界正上方多开了一个维度：一直往上飞，你会穿过边界进入一片寂静星空，那里每一颗星球都由坐标哈希生成；向下坠落，你又会回到出发的地方。

## 功能

| 功能 | 你能得到什么 |
|---|---|
| **满天星球** | 每颗星球都由「坐标 + 种子」生成，同一个种子永远得到同一片星空。石头、沙子、冰、玄武岩……每颗都有外壳、厚壳层和实心核心。 |
| **沿 Y 轴往返** | 在主世界飞过 `Y = 400` 进入 Cosmos；在 Cosmos 掉到 `Y = 0`（含）以下就回家。不用传送门，不用命令。 |
| **缩放但方位不变** | 两个世界共享同一张平面地图，比例 1:64 —— 主世界 `x = 1600` 对应 Cosmos `x = 25`，你永远能算回自己的位置。 |
| **低重力，优先照顾鞘翅** | 下坠被放慢成缓慢漂浮；穿鞘翅的玩家会被完全跳过，滑翔手感和原版一致。 |
| **掉落物悬浮** | 丢出的物品停在太空里，不会无限下坠（可选同时冻结经验球）。 |
| **重力方块悬停** | 沙子、沙砾、混凝土粉末、铁砧在 Cosmos 里停在原地：挖掉它们下面的方块、或者在半空直接放，都不会塌下来。 |
| **落地就有地标** | 落点正上方固定有一颗星球，踏进虚空也不会眼前一空。 |
| **支持 Folia** | Paper 和 Folia 都能跑，运行时自动识别。 |

## 环境要求

- Paper **26.3** 或 Folia **26.3**（更老的 1.21.x 服务端需要对应 API 版本重新打包的 jar）
- 服务端 Java 21 或更高

## 安装

1. 把 `Cosmos-1.2.0.jar` 放进 `plugins/`。
2. 启动服务端。
3. 就这样 —— `cosmos` 世界会在第一次有人前往时自动创建，也可以执行 `/cosmos reload` 立即创建。

## 第一次旅行

1. 站在主世界，飞到（或搭到）`Y = 400`。
2. 你会被送到 Cosmos 的 `(x / 64, 10, z / 64)`。落点会做一次安全检查：那一列上有星球就直接站到球面上；没有就落到原点地标上方，绝不会悬空下坠。
3. 抬头：那颗星球就是你的地标，其余都是程序化生成的星空。
4. 想回来就在 Cosmos 掉到 `Y = 0`（含）以下，你会出现在主世界 `(x * 64, 390, z * 64)`。

每次穿越后有 3 秒冷却，避免来回弹跳；Cosmos 的世界边界始终按比例镜像主世界边界。

> **主世界高度只有 320 格时**，建筑上限是 `Y = 319`，要够到 `Y = 400` 需要鞘翅 + 烟花。这是刻意的设计 —— 但如果你想要更轻松的旅程，可以把 `teleport-y-world` 调到 `330` 之类的值。

## 配置

三个文件，都在 `plugins/Cosmos/`：`config.yml`（旅行 / 世界 / 悬浮）、`planets.yml`（星空）、`message.yml`（所有提示文字）。改完执行 `/cosmos reload`；与世界本身相关的设置（名称、环境、种子）只对之后新建的世界生效。

### `config.yml` —— 旅行

| 键 | 默认值 | 说明 |
|---|---|---|
| `scale` | `64` | 两个世界的水平比例。填 `4` 表示 Cosmos 只有 4 倍小。 |
| `main-world` | `world` | 作为出发点的主世界名称。 |
| `teleport-y-world` | `400` | 主世界中送你去 Cosmos 的 `Y`。 |
| `teleport-y-cosmos` | `0` | Cosmos 中送你回主世界的 `Y`。 |
| `teleport-y-offset` | `10` | 安全余量：到达位置是 `teleport-y-cosmos + offset`，离开位置是 `teleport-y-world - offset`。 |
| `teleport-cooldown-ms` | `3000` | 一次穿越后忽略新穿越的时长。 |
| `portal-cooldown-ticks` | `100` | 穿越后施加的原版传送门冷却。 |
| `y-check-interval-ticks` | `10` | 检查玩家高度的频率。 |
| `teleport-preload-radius` | `2` | 穿越前预加载落点周围区块的半径（半径 2 = 5×5）。填 `0` 完全关闭预加载。 |
| `fallback-location` | `cosmos 0.5 160 0.5` | `/cosmos tp` 找不到可换算位置时把玩家放在这里。 |

### `config.yml` —— Cosmos 世界

| 键 | 默认值 | 说明 |
|---|---|---|
| `cosmos-world-name` | `cosmos` | 维度对应的世界文件夹名。改掉它就能得到一片全新的星空。 |
| `cosmos-world-environment` | `THE_END` | 世界类型；`THE_END` 提供黑暗无云的天空，高度上限也随之决定。 |
| `cosmos-biome` | `THE_END` | 全图使用的生物群系。 |
| `cosmos-world-seed` | `1337` | 世界本身的种子（影响地形特征，不影响星球）。 |
| `cosmos-world-auto-create` | `true` | 世界不存在时自动创建。 |
| `cosmos-world-border-sync` | `true` | 按比例把主世界边界镜像到 Cosmos。 |
| `cosmos-world-gamerules` | 见文件 | 应用到 Cosmos 的游戏规则（关闭刷怪、时间流动、天气冻结、保留摔落伤害）。 |
| `world-check-interval-ticks` | `100` | 检查世界/边界变化的频率。 |
| `cosmos-view-distance` | `0` | Cosmos 的模拟视距。`0` 沿用服务端默认；`6`–`8` 能显著减轻切换维度时的负担。 |
| `cosmos-send-view-distance` | `8` | 下发给客户端的区块视距。越小进出越快，但雾更近。 |

### `config.yml` —— 悬浮与低重力

| 键 | 默认值 | 说明 |
|---|---|---|
| `item-float-enabled` | `true` | 在 Cosmos 中掉落物不再下坠。 |
| `float-experience-orb` | `false` | 经验球也一起冻结。 |
| `float-freeze-velocity` | `true` | 让物品完全静止，而不是缓慢漂移。 |
| `float-unlimited-lifetime` | `false` | 悬浮物品永久保留。 |
| `float-sweep-interval-ticks` | `200` | 重新检查玩家周围区域的频率。 |
| `float-sweep-radius` | `16` | 重新检查的半径 —— 范围内的掉落物会被重新设为悬浮。 |
| `float-gravity-blocks` | `true` | 沙子、沙砾、混凝土粉末、铁砧等重力方块在 Cosmos 中停在原地。 |
| `player-float-enabled` | `true` | 放慢 Cosmos 中的下坠。 |
| `player-float-fall-speed` | `0.08` | 下坠时允许的最大速度（格/刻）。 |
| `player-float-interval-ticks` | `2` | 施加该速度限制的频率。 |
| `player-float-skip-elytra` | `true` | 完全跳过穿鞘翅的玩家 —— **建议保持开启**，否则慢速下坠会干扰滑翔。 |
| `debug` | `false` | 输出更多日志。 |

### `planets.yml` —— 星空

| 键 | 默认值 | 说明 |
|---|---|---|
| `enabled` | `true` | 关闭全部程序化星球（世界变成纯虚空）。 |
| `cell-size` | `96` | 每颗星球所占网格单元的边长。越小星空越密。 |
| `chance` | `0.6` | 每个单元生成星球的概率。越大星空越密。 |
| `min-radius` / `max-radius` | `6` / `28` | 星球尺寸范围。 |
| `min-shell-thickness` / `max-shell-thickness` | `2` / `8` | 表面到核心之间的壳层厚度。 |
| `min-y` / `max-y` | `40` / `240` | 整颗星球允许占据的高度范围（球心会自动内缩一个半径，再与世界建筑范围取交集）。 |
| `seed` | `20260101` | 换成别的值即可得到完全不同的星空。 |
| `spawn-planet` | `true` | 在落点正上方保留一颗地标星球。 |
| `spawn-radius` / `spawn-center-y` | `42` / `60` | 地标星球的尺寸与高度。 |
| `weight` | `0` | 星球重叠时的绘制顺序：数值小的先画；同值时大球先画、小球后画（小球盖在上面）。 |
| `palette` | 6 组 | 用来构成星球的方块对（`outer-block` / `core-block`），每颗星球随机选一组。 |

快速调节：

- **更多星球** —— 调小 `cell-size`（例如 `64`）或调大 `chance`（例如 `0.8`）。
- **更少但更大的星球** —— 调大 `cell-size`，并调大 `max-radius`。
- **同一个世界换星空** —— 改 `seed`。
- **不要地标** —— `spawn-planet: false`。
- **纯虚空** —— `enabled: false`。

## 命令与权限

| 命令 | 权限 | 默认 | 作用 |
|---|---|---|---|
| `/cosmos reload`（`/cos reload`） | `cosmos.reload` | op | 重载 `config.yml`、`planets.yml` 与 `message.yml`。 |
| `/cosmos tp <玩家>` | `cosmos.tp` | op | 把玩家送入 Cosmos。 |
| `/cosmos info` | `cosmos.info` | 所有人 | 显示缩放比例、阈值和世界状态。 |

`cosmos.admin` 一次授予以上三项。所有提示（前缀、颜色、措辞）都可以在 `message.yml` 里改。

## 常见问题

| 现象 | 处理 |
|---|---|
| 一直往上飞却没反应 | 必须身处 `main-world`，且到达 `teleport-y-world`。用 `/cosmos info` 确认。 |
| 传送后落在星球上 / 地标上 | 正常 —— 落点会吸附到球面，避免传进虚空后又被弹回去。想强制用换算出的原始 `Y`，把 `spawn-planet` 设为 `false` 并确保落点列上没有星球。 |
| 星球太稀疏 | 调小 `cell-size` 或调大 `chance`；也可以换 `seed`。 |
| 星空和截图完全不一样 | 已生成的区块不会重画。去新区域，或改 `cosmos-world-name` 换一个新世界。 |
| 鞘翅在 Cosmos 里不好用 | 确认 `player-float-skip-elytra` 是 `true`（默认值）。 |
| 进出 Cosmos 时客户端卡住 | 把 `cosmos-send-view-distance` 调到 `6`，`teleport-preload-radius` 调到 `0`。 |
| 旧的 `planets:` 列表没生效 | 正常 —— 手写星球已被程序化生成取代，删掉该段即可。 |
| 沙子 / 沙砾还是会掉下来 | 确认 `float-gravity-blocks` 是 `true`，并且人在 Cosmos 维度里；只在该维度生效。 |
| 改了配置没变化 | 执行 `/cosmos reload`；改 `cosmos-world-name`、环境或种子需要新建世界。 |

## 已知限制

- 星球是静态实心球体，不会公转，也不会吸引玩家。
- Cosmos 是普通 Bukkit 世界，高度上限由 `cosmos-world-environment` 决定（`THE_END` = 256 格）；超出顶部的部分会被削平。
- `THE_END` 天空没有太阳和云 —— 这正是风格所在，但也意味着没有自然光源。
- 不支持修改已有世界的环境或种子；请改用新的世界名。
- 落点安全吸附只在「主世界 → Cosmos」时生效；回主世界仍严格使用 `teleport-y-world - offset`。
- Folia 没有 `createWorld` API：第一次需要先在 `server.properties` / 世界管理插件里准备好 `cosmos` 世界（或让它在 Paper 上创建一次，再把文件夹搬过去）。

## 许可证

MIT © 2026 LiuyeStudio —— 见 [LICENSE](LICENSE)。
