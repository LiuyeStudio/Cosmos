# Cosmos

[English](README.md) | **简体中文**

> **1.1.0** · Paper 26.3 / Folia 26.3 · Java 21

Cosmos 是一个「宇宙维度」插件：主世界飞到指定高度就进入 Cosmos，在 Cosmos 里下落回主世界。
Cosmos 由自写的 `ChunkGenerator` 在三维空间中生成一颗颗星球，坐标与主世界按 `1:scale` 等比例换算。

## 1. 核心行为

- **双向 Y 轴传送**：主世界 `Y >= teleport-y-world` → Cosmos；Cosmos `Y <= teleport-y-cosmos` → 主世界。
- **坐标严格换算**：`x' = x / scale`、`z' = z / scale`（回程 `* scale`），Y 固定落在阈值 ± `offset`。
  不找安全落点、不吸附星球、不裁剪世界高度 —— 与 `/tp` 一致。
- **程序化星球**：整片星空由坐标哈希生成（同一个种子永远同一片星空），原点额外固定一颗地标星球；没有手写星球列表。
- **掉落物悬浮**：Cosmos 里的掉落物不下落。
- **玩家低重力**：缓慢下沉，**不授予飞行**；穿鞘翅的玩家完全交给原版滑翔。

## 2. 特性一览

| 能力 | 说明 |
|---|---|
| 传送触发 | `PlayerMoveEvent` 主路径 + 每玩家低频 Y 轴巡检兜底 |
| 坐标 | 水平 `1:scale`，垂直由各自的传送高度 + offset 决定 |
| 星球 | 全部程序化生成（网格 + 种子哈希），原点地标可选，支持重叠与权重覆盖 |
| 世界 | 自动创建 / 接管既有世界 / 看门狗重新接管 / 边界同步 / 视距覆盖 |
| 平台 | Paper 与 Folia（`folia-supported: true`），线程归属全部走 `RegionSchedulerFacade` |
| 配置 | `config.yml` / `planets.yml` / `message.yml`，`/cosmos reload` 热重载 |

## 3. 兼容性

| 项目 | 要求 |
|---|---|
| 服务端 | Paper 26.3+、Folia 26.3+（`api-version: '26.3'`） |
| 旧版本 | 1.21.x ~ 26.2 需把 `plugin.yml` 的 `api-version` 改成 `'1.21'`，并自行确认 API 差异 |
| Java | 21（`maven.compiler.release = 21`） |
| 依赖 | 无硬依赖；可选 `Multiverse-Core` / `MyWorlds` / `PhantomWorlds`（`softdepend`） |

## 4. 安装

1. 构建（见第 12 节）得到 `target/Cosmos-1.1.0.jar`；
2. 放入服务端 `plugins/` 目录；
3. 启动一次服务器，插件会：
   - 生成 `plugins/Cosmos/config.yml`、`planets.yml`、`message.yml`；
   - 若 `cosmos-world-name` 指定的世界不存在，按配置自动创建（`WORLD/cosmos/`）；

     注意 **Folia 的 `CraftServer#createWorld` 未实现**（直接抛 `UnsupportedOperationException`），
     纯 Folia 上必须让世界管理插件创建好该世界，见第 13 节方式 B；
   - 设置该世界的游戏规则与出生点（不再生成任何平台）。
4. `/cosmos info` 检查状态。

## 5. 项目结构

```
Cosmos/
├── pom.xml
├── README.md
└── src/
    ├── main/
    │   ├── java/studio/liuye/cosmos/
    │   │   ├── CosmosPlugin.java                  # 主类：生命周期、reload、info
    │   │   ├── command/CosmosCommand.java         # /cosmos 命令 + Tab 补全
    │   │   ├── config/
    │   │   │   ├── CosmosSettings.java            # 不可变配置快照 + Builder
    │   │   │   ├── PlanetSettings.java            # 单颗星球的参数（record，由坐标哈希产出）
    │   │   │   ├── ProceduralPlanetSettings.java  # 程序化星球场的配置（record）
    │   │   │   └── SettingsLoader.java            # 读盘（异步安全）/ 解析（全局线程）
    │   │   ├── item/ItemFloatService.java         # 掉落物悬浮
    │   │   ├── listener/
    │   │   │   ├── MovementListener.java          # PlayerMoveEvent → 阈值判定
    │   │   │   ├── ItemFloatListener.java         # EntitySpawnEvent / EntitiesLoadEvent
    │   │   │   └── PlayerLifecycleListener.java   # 加入/退出时建/清周期任务
    │   │   ├── platform/
    │   │   │   ├── FoliaSupport.java              # ServerBuildInfo 检测 Folia
    │   │   │   └── RegionSchedulerFacade.java     # 全局 / 区域 / 实体 三层调度
    │   │   ├── player/PlayerFloatService.java     # 玩家低重力（鞘翅玩家跳过）
    │   │   ├── teleport/
    │   │   │   ├── TeleportService.java           # 换算、预加载、传送、冷却
    │   │   │   └── TeleportCooldowns.java         # 单向下发的冷却表
    │   │   ├── util/
    │   │   │   ├── Messages.java                  # 前缀 + 颜色码
    │   │   │   └── MessageService.java            # message.yml 加载 / 占位符替换
    │   │   └── world/
    │   │       ├── Planet.java                    # 球体数学 + 包围盒
    │   │       ├── PlanetRegistry.java            # 固定 + 程序化的统一视图
    │   │       ├── ProceduralPlanetField.java     # 程序化星球场（确定性哈希）
    │   │       ├── PlanetChunkGenerator.java      # 关卡生成器
    │   │       ├── CosmosBiomeProvider.java       # 恒定群系
    │   │       └── CosmosWorldService.java        # 世界创建 / 接管 / 规则 / 视距 / 边界
    │   └── resources/
    │       ├── config.yml
    │       ├── planets.yml
    │       ├── message.yml
    │       └── plugin.yml
    └── test/java/studio/liuye/cosmos/
        ├── teleport/ScalingTest.java
        ├── teleport/TeleportCooldownsTest.java
        ├── world/PlanetTest.java
        ├── world/PlanetRegistryTest.java
        ├── world/PlanetChunkGeneratorTest.java
        └── world/ProceduralPlanetFieldTest.java
```

## 6. 默认配置下的体验

在主世界 `(0, 400, 0)` 向上爬到 `Y >= 400`，会被送到 Cosmos 的 `(0, 10, 0)`（X/Z ÷ 64，Y = `teleport-y-cosmos + offset`），
位置在原点地标星球 `(0, 60, 0)` 正下方；抬头能看到它的底部（约 `Y = 18`），穿鞘翅起飞或用烟花就能进入星球带。
在 Cosmos 降到 `Y <= 0`，回到主世界 `(0, 390, 0)`。

## 7. 配置

三个文件都在 `plugins/Cosmos/`，改完 `/cosmos reload` 生效（世界环境的改动需要重建世界）。

### 7.1 config.yml

```yaml
scale: 64                     # Cosmos 1 格 = 主世界 64 格
main-world: "world"           # 主世界名；留空则用服务器第一个世界
cosmos-world-name: "cosmos"
cosmos-world-environment: THE_END   # 0..255；想要 -64..319 改成 NORMAL
teleport-y-world: 400         # 主世界 Y >= 400 → 去 Cosmos
teleport-y-cosmos: 0          # Cosmos Y <= 0 → 回主世界
teleport-y-offset: 10         # 去 Cosmos 落在 Y=10，回主世界落在 Y=390
teleport-cooldown-ms: 3000
teleport-preload-radius: 2    # 传送前预热 5x5 区块（外圈只读盘、不生成）
portal-cooldown-ticks: 100
y-check-interval-ticks: 10
world-check-interval-ticks: 100
cosmos-view-distance: 0       # 0 = 沿用全局模拟视距；卡顿可设 6~8
cosmos-send-view-distance: 8  # 发给客户端的区块视距
```

| | 键 | 类型 | 默认 | 说明 |
|---|---|---|---|---|
| 世界 | `cosmos-world-name` | string | `cosmos` | 世界名 / 文件夹名 |
| 世界 | `cosmos-world-environment` | enum | `THE_END` | `NORMAL`（-64..319）/ `NETHER` / `THE_END`（0..255，默认） |
| 世界 | `cosmos-biome` | string | `THE_END` | 群系 id，支持 `minecraft:xxx` |
| 世界 | `cosmos-world-seed` | long | `1337` | 世界种子（生成器不用随机数，仅元数据一致） |
| 世界 | `cosmos-world-auto-create` | bool | `true` | 世界不存在时自动创建；交给世界管理插件时设 `false` |
| 世界 | `cosmos-world-border-sync` | bool | `true` | Cosmos 边界中心 / 尺寸按 `1:scale` 跟随主世界 |
| 世界 | `cosmos-world-gamerules` | map | 见文件 | 键名用 26.3 的规则名（`spawn_mobs` / `advance_time` …） |
| 世界 | `cosmos-view-distance` | int | `0` | 覆盖 Cosmos 的模拟视距（0 = 沿用全局） |
| 世界 | `cosmos-send-view-distance` | int | `8` | 单独收窄下发给客户端的区块视距（0 = 跟随模拟视距） |
| 传送 | `teleport-y-world` | int | `400` | 主世界 Y 大于等于它 → 传送去 Cosmos |
| 传送 | `teleport-y-cosmos` | int | `0` | Cosmos Y 小于等于它 → 回主世界；同时是星球生成下限 |
| 传送 | `teleport-y-offset` | int | `10` | 去 Cosmos `+offset`，回主世界 `-offset` |
| 传送 | `teleport-cooldown-ms` | long | `3000` | 单向冷却，防止阈值附近来回循环 |
| 传送 | `teleport-preload-radius` | int | `2` | 预热半径（5x5=25）；落点区块会生成，外圈只读盘；最多等 3 秒就照常传送，`0` 关闭 |
| 传送 | `portal-cooldown-ticks` | int | `100` | 额外写一次原版 portal cooldown（0 = 不写） |
| 传送 | `y-check-interval-ticks` | int | `10` | 每玩家低频巡检周期（0 = 关闭） |
| 传送 | `world-check-interval-ticks` | int | `100` | 世界接管看门狗周期（0 = 关闭） |
| 传送 | `fallback-location` | section | `cosmos, 0.5/160/0.5` | 仅 `/cosmos tp`（玩家已在 Cosmos 时）使用的落点 |
| 悬浮物 | `item-float-enabled` | bool | `true` | 掉落物悬浮总开关 |
| 悬浮物 | `float-experience-orb` | bool | `false` | 经验球是否也悬浮 |
| 悬浮物 | `float-freeze-velocity` | bool | `true` | 是否同时把速度清零 |
| 悬浮物 | `float-unlimited-lifetime` | bool | `false` | 悬浮掉落物是否永不过期 |
| 悬浮物 | `float-sweep-interval-ticks` | int | `200` | 已存在掉落物的巡检周期（0 = 关闭） |
| 悬浮物 | `float-sweep-radius` | int | `16` | 巡检半径 |
| 玩家 | `player-float-enabled` | bool | `true` | 玩家低重力总开关 |
| 玩家 | `player-float-skip-elytra` | bool | `true` | 胸口穿鞘翅时跳过悬浮（保留原版滑翔手感） |
| 玩家 | `player-float-fall-speed` | double | `0.08` | 下沉速度上限（格/tick）；`0` = 完全悬停 |
| 玩家 | `player-float-interval-ticks` | int | `2` | 低重力巡检周期 |
| 其它 | `debug` | bool | `false` | 输出每次传送 / 校正的细节 |

> **高度范围**：不使用数据包时，`NORMAL` 是 -64..319，`THE_END` / `NETHER` 是 0..255。
> 星球只生成在 `teleport-y-cosmos` 以上，超出世界上限的部分会被削平。

### 7.2 message.yml

`prefix` 对所有消息生效（留空即无前缀），`teleport-message: false` 表示跨世界时不给玩家任何提示。
`messages:` 下的键见文件；未写的键会回落到内置默认值，所以升级不会因为缺键而报错。
传送相关可用占位符：`{direction}` `{world}` `{x}` `{y}` `{z}` `{player}` `{reason}`。

### 7.3 planets.yml

星球**全部由 `procedural` 段程序化生成**——没有手写星球列表，也不需要逐颗配置坐标：
把水平面切成网格，每个单元用 `(seed, 单元坐标)` 哈希出一颗球（或没有球）。

```yaml
procedural:
  enabled: true
  cell-size: 96
  chance: 0.6
  min-radius: 6
  max-radius: 28
  min-shell-thickness: 2
  max-shell-thickness: 8
  min-y: 40
  max-y: 240
  weight: 0
  seed: 20260101
  spawn-planet: true           # 原点强制一颗地标星球，保证落点抬头有东西
  spawn-radius: 42
  spawn-center-y: 60
  palette:
    - outer-block: STONE
      core-block: DEEPSLATE
```

**字段**

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `enabled` | bool | `true` | 关掉之后 Cosmos 就是纯虚空（没有别的星球来源） |
| `cell-size` | int | `96` | 网格单元边长；**越大越稀疏** |
| `chance` | double | `0.6` | 每个单元生成星球的概率；**越大越密** |
| `min-radius` / `max-radius` | double | `6` / `28` | 半径范围 |
| `min-shell-thickness` / `max-shell-thickness` | double | `2` / `8` | 外壳厚度范围 |
| `min-y` / `max-y` | int | `40` / `240` | 球心 Y 范围（还会被世界高度裁剪） |
| `weight` | int | `0` | 同区块内多颗球重叠时的绘制权重；一般不用改 |
| `seed` | long | `20260101` | 换一个数字就是另一片星空 |
| `spawn-planet` | bool | `true` | 单元 `(0,0)` 必定有一颗球心在 `(0, spawn-center-y, 0)` 的星球，不受 `chance` 影响 |
| `spawn-radius` | double | `42` | 地标星球半径 |
| `spawn-center-y` | int | `60` | 地标星球球心 Y |
| `palette` | list | 内置 6 组 | 每项一组 `outer-block` / `core-block`，随机挑选 |

调密度速查：

| 想要 | 改法 |
|---|---|
| 更密 | `cell-size` 降到 64~80，或 `chance` 提到 0.75 |
| 更稀 | `cell-size` 提到 128~192 |
| 更大的星球 | `max-radius` 提到 36~48（注意相邻星球会更常粘连） |
| 换一片星空 | 改 `seed` |
| 不要原点地标 | `spawn-planet: false`（落点附近可能什么都没有，需要自己飞） |
| 完全关闭程序化 | `procedural.enabled: false` |

> `procedural` 段缺失时（老配置），插件会**默认启用**内置参数并记录一行日志，
> 所以升级后不会出现「天空一片空」。
> 老配置里的 `planets:` 段会被忽略，并在控制台提示一次，可以直接删掉。

## 8. 星球生成

### 8.1 确定性

程序化星球只用两个整数（网格坐标 `cellX/cellZ`）和一个种子做 SplitMix64 哈希，
不读 `Random`、不依赖区块加载顺序。同一个 `seed` + `cell-size` 在任何服务器、任何时间都得到同一片星空。
星球中心被限制在自己的网格内，所以「查附近星球」只需要看落点周围的几个单元，与星球总数无关。

唯一的例外是原点地标：`spawn-planet: true` 时单元 `(0,0)` 不掷骰子，固定生成一颗
球心在 `(0, spawn-center-y, 0)`、半径 `spawn-radius` 的星球（名字 `spawn`）。

### 8.2 重叠与权重

所有与区块相交的星球合并后按 `Planet#compareTo` 排序再逐颗绘制：
`weight` 小的先画、被 `weight` 大的覆盖；同 `weight` 时大球先画、小球后画。整个过程没有随机数，
同一区块每次生成结果一致。

### 8.3 高度

`PlanetChunkGenerator` 只绘制「与当前区块 Y 范围相交」的部分：`minHeight = max(世界下限, teleport-y-cosmos)`，
`maxHeight = 世界上限`。超出上限的星球顶部会被削平成平台状 —— 这也是默认 `THE_END`（0..255）下
`min-y/max-y` 取 `40..240`、`spawn-center-y` 取 `60` 的原因：球心会被再夹一次，保证整颗球尽量可见。

### 8.4 热重载

`/cosmos reload` 会重建配置快照与星球注册表并推给生成器，但**已经生成的区块不会重画**。
想让新配置完全生效，需要新世界（或让玩家去没去过的区域）。

## 9. 运行时模型

### 9.1 线程归属

| 操作 | 线程 |
|---|---|
| 读配置 / 解析 YAML | 异步读盘 → 全局线程解析 |
| 创建 / 接管世界、设置规则、边界、视距 | 全局线程 |
| `teleportAsync` 的收尾（速度、坠落距离、冷却） | 玩家所属区域线程（`EntityScheduler`） |
| 掉落物 / 玩家的周期任务 | 各自所属实体的区域线程 |

### 9.2 一次传送的完整时序

```
PlayerMoveEvent (玩家区域线程)
  └─ checkTransition: 世界名 + Y 比较（纯数学）
      └─ requestTo: 冷却 / 在途检查 → entityNextTick（玩家）   ← 绝不在这里内联传送
          └─ beginTransfer (下一个 tick)
              └─ 落点区块 getChunkAtAsync(..., true)
                 + 外圈 getChunkAtAsync(..., false)            ← 只读盘，不生成
                  └─ allOf(...).orTimeout(3s)
                      └─ entityNextTick(player)
                          └─ player.teleportAsync(换算后的坐标)
                              └─ 回到实体线程：清速度 / 坠落距离 / 写冷却
```

> **为什么必须 `entityNextTick`**：区域化服务端在处理完 `PlayerMoveEvent` 后还会执行
> `player.absSnapTo(包内坐标)`。如果在事件回调里直接 `teleportAsync`，目标位置会立刻被改回出发坐标，
> 随后在目标世界 `addPlayer` 时用旧区块做区域校验，抛 `Cannot add entity off-main thread` 并 halt 服务端。

### 9.3 Folia 检测

`ServerBuildInfo.buildInfo().isBrandCompatible(Key.key("papermc", "folia"))`，结果用于日志 / `/cosmos info`，
以及启动时「已存在掉落物」的校正策略：Folia 没有全局实体表，按在线玩家分别调度到各自区域线程扫描；
Paper 上在全局线程直接遍历 `World#getEntities()`。

## 10. 传送细节

### 10.1 坐标换算

```
主世界 → Cosmos :  x' = x / scale , z' = z / scale , y' = teleport-y-cosmos + offset
Cosmos → 主世界 :  x' = x * scale , z' = z * scale , y' = teleport-y-world  - offset
```

例（默认 64 / 400 / 0 / 10）：主世界 `(100, 400, 200)` → Cosmos `(1.5625, 10, 3.125)`；
从 Cosmos 降到 `Y <= 0` → 主世界 `(100, 390, 200)`。

### 10.2 阈值与迟滞

两个世界各用**自己**的阈值判定，`offset` 形成迟滞区：去 Cosmos 落在 `teleport-y-cosmos + offset`（在 Cosmos 阈值之上），
回主世界落在 `teleport-y-world - offset`（在主世界阈值之下），因此落地不会立刻再次触发。

### 10.3 载具

骑乘中的玩家整体传送（乘客跟着载具），不做单独修正；传送成功后载具与乘客都不再额外加速。

### 10.4 冷却与在途

- `teleport-cooldown-ms`：传送**成功**后写入，冷却期内的请求直接丢弃；
- `inFlight`：同一玩家同时只允许一次传送，避免移动包洪峰触发连环传送。

### 10.5 视距与预加载（卡顿）

跨维度时客户端要把视距内的区块全部重建网格，Cosmos 的效率瓶颈就在这里：

- `cosmos-send-view-distance`（默认 8）单独收窄下发的区块视距，**这是最有效的一档**，卡就再降到 6；
- `cosmos-view-distance`（默认 0 = 跟随全局）控制服务端模拟视距；
- `teleport-preload-radius`（默认 2）只在传送前把落点周围的区块**从磁盘读回来**（`generate=false`），
  整组最多等 3 秒，超时照常传送 —— 它不会在传送前批量生成区块，也不会把玩家卡在传送中。

## 11. 悬浮

### 11.1 掉落物

Cosmos 内的掉落物（可选经验球）取消重力并冻结水平速度，可配置是否永不过期；
已存在的掉落物由低频巡检（`float-sweep-interval-ticks`）与 `EntitiesLoadEvent` 双重覆盖。

### 11.2 玩家低重力

每 `player-float-interval-ticks` 一次，把下落速度夹到 `-player-float-fall-speed`，并清零坠落距离：
表现为缓慢下沉而不是自由落体。**不授予飞行**（不碰 `allowFlight` / `flying`），飞行中的玩家直接跳过。

**鞘翅**：胸口穿鞘翅的玩家整个跳过巡检（`player-float-skip-elytra`）。原因很具体 ——
客户端先进入滑翔、再发 `START_FALL_FLYING`，服务端在这几 tick 内 `isGliding()` 仍是 `false`，
如果此时下发速度包，就会把「起跳 → 展翅」那一下打断。跳过之后穿鞘翅者在 Cosmos 里就是原版滑翔手感。
传送时若玩家正在滑翔，也**不会**被清零速度（只清坠落距离），避免动量丢失。

## 12. 构建与测试

### 12.1 构建

```bash
mvn -o clean package
# 产物：target/Cosmos-1.1.0.jar
```

`paper-api` 26.3 以 Java 25 字节码发布，因此编译需要 **JDK 25 或更高**（插件产物本身仍指向 Java 21，由 `maven.compiler.release` 控制）。仓库自带的 GitHub Actions 因此固定使用 Temurin 25。

### 12.2 单元测试

```bash
mvn -o test
```

共 40 个，全部通过：

| 测试类 | 数量 | 覆盖 |
|---|---|---|
| `ScalingTest` | 9 | 坐标换算、阈值、载具、非法值兜底 |
| `TeleportCooldownsTest` | 4 | 单向冷却、清理、过期 |
| `PlanetTest` | 4 | 包含判定、核心/外壳方块、包围盒 |
| `PlanetRegistryTest` | 4 | 过滤禁用 / 过小半径、排序稳定性 |
| `PlanetChunkGeneratorTest` | 8 | 单列填充、裁剪、核心覆盖、空注册表 |
| `ProceduralPlanetFieldTest` | 11 | 确定性、种子差异、密度、Y 范围、与暴力枚举一致、调色板、原点地标（位置 / 尺寸 / 可关闭）、显式+程序化合并顺序 |

### 12.3 部署验证清单

1. 主世界飞到 `Y >= 400` → 应落在 Cosmos `(x/64, 10, z/64)`；
2. `/execute in cosmos run tp @s 0 150 0` 观察原点地标与程序化星球；
3. 在 Cosmos 降到 `Y <= 0` → 应回到主世界 `(x*64, 390, z*64)`；
4. 穿鞘翅在 Cosmos 起跳 → 能展开滑翔，且不会被速度包打断；
5. 丢一个物品 → 悬停不下落；
6. `/cosmos info` → 世界、缩放、星球数、程序化状态都对得上。

## 13. 部署方式

### 方式 A：插件自建世界

`cosmos-world-auto-create: true`（默认）。启动时创建 `cosmos` 世界并接管生成器。
优点：零依赖；缺点：纯 Folia 不支持（`createWorld` 未实现）。

### 方式 B：交给世界管理插件

先用 Multiverse / MyWorlds / PhantomWorlds 创建名为 `cosmos` 的世界，生成器填 `Cosmos`，
再把 `cosmos-world-auto-create` 设为 `false`。Cosmos 启动时会**接管**这个已存在的世界。

### 注意事项

- 世界环境（`THE_END` / `NORMAL`）在创建时固定，改配置只对新建世界生效；
- `saveBundled` 只在文件缺失时写入，升级不会覆盖你改过的 `config.yml` / `planets.yml`；
- 接管后会重设该世界的游戏规则与出生点，不会再生成平台。

## 14. 命令与权限

| 命令 | 权限 | 说明 |
|---|---|---|
| `/cosmos reload`（别名 `/cos`） | `cosmos.reload`（默认 op） | 重载 config / planets / message |
| `/cosmos tp <player>` | `cosmos.tp`（默认 op） | 把玩家送进 Cosmos（已在 Cosmos 时用 `fallback-location`） |
| `/cosmos info` | `cosmos.info`（默认所有人） | 运行环境、缩放、阈值、世界状态、星球数 |

`cosmos.admin` 是上面三个权限的父节点。

## 15. 已知限制

1. 不使用数据包时，世界高度固定为 `NORMAL` -64..319、`THE_END`/`NETHER` 0..255；
2. 落点不做安全搜索：换算到虚空就是虚空（`fallback-location` 只服务 `/cosmos tp`）；
3. 已生成的区块不会因改配置而重画，需要新世界或新区域；
4. 纯 Folia 无法用 Bukkit API 创建世界，必须走方式 B；
5. 星球全部程序化生成，没有手写星球；`weight` 只影响同区块内多颗球重叠时的覆盖顺序；
6. 超高的星球顶部会被世界上限削平，`procedural.max-y` 会被自动夹到建筑范围内；
7. `teleport-y-cosmos` 默认 0，若把 Cosmos 环境改成 `NORMAL` 且希望星球有更多垂直空间，可整体抬高。

## 16. 故障排查

| 现象 | 排查 |
|---|---|
| 插件没加载 | `api-version` 是否与服务端匹配（26.3 ↔ `'26.3'`，1.21.x ↔ `'1.21'`） |
| `/cosmos info` 显示世界不可用 | 看控制台创建失败原因（磁盘、世界名冲突、`auto-create=false`） |
| 上升没有传送 | `main-world` 是否与实际主世界同名；`teleport-y-world` 是否真的能飞到 |
| 传送后落到虚空 | 预期行为：落点就是换算坐标。想要落点附近有星球，确认 `procedural.spawn-planet: true`（原点地标），或把 `spawn-center-y` 调低到落点上方 |
| 传送后 Y 不符合预期 | 看 `teleport-y-world` / `teleport-y-cosmos` / `teleport-y-offset` |
| 星球没有生成 | 世界是否由插件创建；`procedural.enabled`；`/cosmos info` 看程序化状态与原点地标；`/cosmos reload` 只影响新区块 |
| 鞘翅在 Cosmos 飞不起来 | 确认 `player-float-skip-elytra: true`；仍异常时把 `player-float-enabled` 设为 false 排除悬浮因素 |
| 进/出 Cosmos 客户端卡住甚至掉线 | 跨维度要重建整个视距的网格：`cosmos-send-view-distance` 降到 6，`cosmos-view-distance` 设 8；`teleport-preload-radius: 0` 完全关掉预加载 |
| 掉落在 Cosmos 仍会下落 | `item-float-enabled` 是否为 true；`/cosmos reload` 后重新丢一个 |
| 世界管理插件装卸后传送失效 | 看门狗默认 100 tick 内自动接管；也可 `/cosmos reload` 立即重试 |
| 日志提示「生成器不是 Cosmos 星球生成器」 | 该世界不是用 Cosmos 生成器创建的：用方式 B 重建，或删掉世界文件夹 |
| 日志提示「当前服务端不支持 Bukkit#createWorld」 | 运行在纯 Folia 上，改用方式 B |
| Folia 报线程异常 | 上报时附完整堆栈；插件内世界操作应全部经过 `RegionSchedulerFacade` |

## 17. 验证状态

| 项目 | 结果 |
|---|---|
| `mvn -o clean package`（JDK 26，`--release 21`，Paper API 26.3） | 通过，产物 `target/Cosmos-1.1.0.jar` |
| 单元测试 | 40 / 40 通过 |
| 编译告警 | 仅 `WorldBorder#get/setWarningTime`、`Entity#isInWaterOrBubbleColumn` 的废弃提示 |
| 真实服务端端到端 | **未验证**：本机只有客户端，没有可复现的 Paper/Folia 实例 |

## 18. 变更记录

### 1.1.0

- 新增程序化星球场（`planets.yml` 的 `procedural` 段），同一个种子得到同一片星空，可按 `cell-size` / `chance` 调密度；
- 星球改为**完全程序化生成**：删除 `planets:` 手写列表，所有星球都由坐标哈希产出；新增 `spawn-planet` / `spawn-radius` / `spawn-center-y`，在原点固定一颗地标星球，保证落点抬头有东西；
- 修复鞘翅在 Cosmos 无法正常滑翔：穿鞘翅的玩家跳过悬浮巡检，传送时不再清零滑翔速度；
- 新增 `cosmos-view-distance` / `cosmos-send-view-distance` / `teleport-preload-radius`，缓解跨维度卡顿；
- 移除「传送目标 Y 超出建筑范围」「`teleport-y-world` 高于主世界最高高度」两条误报日志。

### 1.0.0

- 首个版本：双向 Y 轴传送、确定性星球生成、掉落物悬浮、玩家低重力、Folia 兼容。

## 19. 许可证

[MIT](LICENSE) © 2026 LiuyeStudio。可自由使用、修改、再分发，保留版权声明即可。

