# Cosmos

**English** | [简体中文](README.zh-CN.md)

> **1.1.0** · Paper 26.3 / Folia 26.3 · Java 21

Cosmos is a "cosmic dimension" plugin: fly up to the configured height in the overworld and you enter Cosmos; fall back down inside Cosmos and you return to the overworld.
Cosmos uses its own `ChunkGenerator` to generate planet after planet across three-dimensional space, with coordinates mapped to the overworld at a uniform `1:scale` ratio.

## 1. Core behavior

- **Bidirectional Y-axis teleport**: overworld `Y >= teleport-y-world` → Cosmos; Cosmos `Y <= teleport-y-cosmos` → overworld.
- **Strict coordinate mapping**: `x' = x / scale`, `z' = z / scale` (return trip `* scale`), Y always lands at the threshold ± `offset`.
  No safe-landing search, no snapping onto planets, no clamping to world height — identical to `/tp`.
- **Procedural planets**: the whole starfield is generated from coordinate hashes (the same seed always gives the same starfield), plus one extra fixed landmark planet at the origin; there is no hand-written planet list.
- **Floating dropped items**: dropped items do not fall inside Cosmos.
- **Low gravity for players**: slow sinking, **no flight granted**; players wearing an elytra are left entirely to vanilla gliding.

## 2. Feature overview

| Capability | Description |
|---|---|
| Teleport trigger | `PlayerMoveEvent` main path + a low-frequency per-player Y-axis sweep as a fallback |
| Coordinates | Horizontal `1:scale`; vertical determined by each side's teleport height + offset |
| Planets | All procedurally generated (grid + seed hash), the origin landmark is optional, overlapping and weight overrides supported |
| World | Auto-create / take over an existing world / watchdog re-takeover / border sync / view distance override |
| Platform | Paper and Folia (`folia-supported: true`), all thread ownership goes through `RegionSchedulerFacade` |
| Configuration | `config.yml` / `planets.yml` / `message.yml`, hot-reloaded by `/cosmos reload` |

## 3. Compatibility

| Item | Requirement |
|---|---|
| Server | Paper 26.3+, Folia 26.3+ (`api-version: '26.3'`) |
| Older versions | 1.21.x ~ 26.2 requires changing `api-version` in `plugin.yml` to `'1.21'`, and you must verify the API differences yourself |
| Java | 21 (`maven.compiler.release = 21`) |
| Dependencies | No hard dependencies; optional `Multiverse-Core` / `MyWorlds` / `PhantomWorlds` (`softdepend`) |

## 4. Installation

1. Build (see section 12) to get `target/Cosmos-1.1.0.jar`;
2. Put it into the server's `plugins/` directory;
3. Start the server once, and the plugin will:
   - generate `plugins/Cosmos/config.yml`, `planets.yml`, `message.yml`;
   - if the world named by `cosmos-world-name` does not exist, create it automatically per the configuration (`WORLD/cosmos/`);

     Note that **Folia's `CraftServer#createWorld` is not implemented** (it throws `UnsupportedOperationException` outright),
     so on pure Folia you must have a world management plugin create that world first, see section 13 method B;
   - set that world's game rules and spawn point (no platform is generated any more).
4. Run `/cosmos info` to check the state.

## 5. Project structure

```
Cosmos/
├── pom.xml
├── README.md
└── src/
    ├── main/
    │   ├── java/studio/liuye/cosmos/
    │   │   ├── CosmosPlugin.java                  # Main class: lifecycle, reload, info
    │   │   ├── command/CosmosCommand.java         # /cosmos command + tab completion
    │   │   ├── config/
    │   │   │   ├── CosmosSettings.java            # Immutable config snapshot + Builder
    │   │   │   ├── PlanetSettings.java            # Parameters of a single planet (record, produced by the coordinate hash)
    │   │   │   ├── ProceduralPlanetSettings.java  # Config of the procedural planet field (record)
    │   │   │   └── SettingsLoader.java            # Disk read (async-safe) / parsing (global thread)
    │   │   ├── item/ItemFloatService.java         # Floating dropped items
    │   │   ├── listener/
    │   │   │   ├── MovementListener.java          # PlayerMoveEvent → threshold check
    │   │   │   ├── ItemFloatListener.java         # EntitySpawnEvent / EntitiesLoadEvent
    │   │   │   └── PlayerLifecycleListener.java   # Create / clear periodic tasks on join / quit
    │   │   ├── platform/
    │   │   │   ├── FoliaSupport.java              # Detect Folia via ServerBuildInfo
    │   │   │   └── RegionSchedulerFacade.java     # Global / region / entity three-tier scheduling
    │   │   ├── player/PlayerFloatService.java     # Player low gravity (elytra players skipped)
    │   │   ├── teleport/
    │   │   │   ├── TeleportService.java           # Conversion, preloading, teleport, cooldown
    │   │   │   └── TeleportCooldowns.java         # Cooldown table issued per direction
    │   │   ├── util/
    │   │   │   ├── Messages.java                  # Prefix + color codes
    │   │   │   └── MessageService.java            # message.yml loading / placeholder substitution
    │   │   └── world/
    │   │       ├── Planet.java                    # Sphere math + bounding box
    │   │       ├── PlanetRegistry.java            # Unified view of fixed + procedural planets
    │   │       ├── ProceduralPlanetField.java     # Procedural planet field (deterministic hash)
    │   │       ├── PlanetChunkGenerator.java      # Level generator
    │   │       ├── CosmosBiomeProvider.java       # Constant biome
    │   │       └── CosmosWorldService.java        # World create / take over / rules / view distance / border
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

## 6. Experience with the default configuration

Climb upward in the overworld at `(0, 400, 0)` to `Y >= 400` and you are sent to `(0, 10, 0)` in Cosmos (X/Z ÷ 64, Y = `teleport-y-cosmos + offset`),
directly below the origin landmark planet at `(0, 60, 0)`; looking up you can see its underside (around `Y = 18`), and taking off with an elytra or a firework is enough to reach the planet belt.
Descend to `Y <= 0` in Cosmos and you return to `(0, 390, 0)` in the overworld.

## 7. Configuration

All three files live in `plugins/Cosmos/`; run `/cosmos reload` after editing for the changes to take effect (changes to the world environment require recreating the world).

### 7.1 config.yml

```yaml
scale: 64                     # 1 Cosmos block = 64 overworld blocks
main-world: "world"           # Overworld name; leave empty to use the server's first world
cosmos-world-name: "cosmos"
cosmos-world-environment: THE_END   # 0..255; use NORMAL for -64..319
teleport-y-world: 400         # Overworld Y >= 400 → go to Cosmos
teleport-y-cosmos: 0          # Cosmos Y <= 0 → back to the overworld
teleport-y-offset: 10         # Arrive in Cosmos at Y=10, return to the overworld at Y=390
teleport-cooldown-ms: 3000
teleport-preload-radius: 2    # Pre-warm a 5x5 chunk area before teleporting (outer ring is disk-read only, no generation)
portal-cooldown-ticks: 100
y-check-interval-ticks: 10
world-check-interval-ticks: 100
cosmos-view-distance: 0       # 0 = use the global simulation view distance; set 6~8 if you get stutter
cosmos-send-view-distance: 8  # Chunk view distance sent to the client
```

| | Key | Type | Default | Description |
|---|---|---|---|---|
| World | `cosmos-world-name` | string | `cosmos` | World name / folder name |
| World | `cosmos-world-environment` | enum | `THE_END` | `NORMAL` (-64..319) / `NETHER` / `THE_END` (0..255, default) |
| World | `cosmos-biome` | string | `THE_END` | Biome id, `minecraft:xxx` supported |
| World | `cosmos-world-seed` | long | `1337` | World seed (the generator uses no random numbers, only metadata consistency) |
| World | `cosmos-world-auto-create` | bool | `true` | Create the world automatically when it is missing; set `false` when a world management plugin owns it |
| World | `cosmos-world-border-sync` | bool | `true` | Cosmos border center / size follow the overworld at `1:scale` |
| World | `cosmos-world-gamerules` | map | see file | Keys use the 26.3 rule names (`spawn_mobs` / `advance_time` …) |
| World | `cosmos-view-distance` | int | `0` | Overrides the simulation view distance of Cosmos (0 = use the global one) |
| World | `cosmos-send-view-distance` | int | `8` | Separately narrows the chunk view distance sent to clients (0 = follow the simulation view distance) |
| Teleport | `teleport-y-world` | int | `400` | Overworld Y greater than or equal to this → teleport to Cosmos |
| Teleport | `teleport-y-cosmos` | int | `0` | Cosmos Y less than or equal to this → back to the overworld; also the lower bound for planet generation |
| Teleport | `teleport-y-offset` | int | `10` | `+offset` when going to Cosmos, `-offset` when returning to the overworld |
| Teleport | `teleport-cooldown-ms` | long | `3000` | One-way cooldown, prevents bouncing back and forth around the threshold |
| Teleport | `teleport-preload-radius` | int | `2` | Preload radius (5x5=25); the landing chunk is generated, the outer ring is disk-read only; teleport proceeds as usual after at most 3 seconds, `0` disables |
| Teleport | `portal-cooldown-ticks` | int | `100` | Additionally writes the vanilla portal cooldown once (0 = do not write) |
| Teleport | `y-check-interval-ticks` | int | `10` | Low-frequency per-player sweep interval (0 = disabled) |
| Teleport | `world-check-interval-ticks` | int | `100` | World takeover watchdog interval (0 = disabled) |
| Teleport | `fallback-location` | section | `cosmos, 0.5/160/0.5` | Landing spot used only by `/cosmos tp` (when the player is already in Cosmos) |
| Floating items | `item-float-enabled` | bool | `true` | Master switch for floating dropped items |
| Floating items | `float-experience-orb` | bool | `false` | Whether experience orbs float as well |
| Floating items | `float-freeze-velocity` | bool | `true` | Whether to zero the velocity at the same time |
| Floating items | `float-unlimited-lifetime` | bool | `false` | Whether floating dropped items never expire |
| Floating items | `float-sweep-interval-ticks` | int | `200` | Sweep interval for already existing dropped items (0 = disabled) |
| Floating items | `float-sweep-radius` | int | `16` | Sweep radius |
| Player | `player-float-enabled` | bool | `true` | Master switch for player low gravity |
| Player | `player-float-skip-elytra` | bool | `true` | Skip floating when an elytra is worn on the chest (keeps the vanilla gliding feel) |
| Player | `player-float-fall-speed` | double | `0.08` | Maximum sinking speed (blocks/tick); `0` = hover completely |
| Player | `player-float-interval-ticks` | int | `2` | Low-gravity sweep interval |
| Other | `debug` | bool | `false` | Log the details of every teleport / correction |

> **Height ranges**: without datapacks, `NORMAL` is -64..319 and `THE_END` / `NETHER` are 0..255.
> Planets are only generated above `teleport-y-cosmos`; anything beyond the world ceiling is flattened.

### 7.2 message.yml

`prefix` applies to all messages (leave it empty for no prefix); `teleport-message: false` means the player gets no message at all when crossing worlds.
For the keys under `messages:` see the file; keys you do not write fall back to the built-in defaults, so upgrades never fail because of a missing key.
Placeholders available for teleport messages: `{direction}` `{world}` `{x}` `{y}` `{z}` `{player}` `{reason}`.

### 7.3 planets.yml

Planets are **all generated procedurally by the `procedural` section** — there is no hand-written planet list and no per-planet coordinates:
the horizontal plane is split into a grid, and each cell hashes `(seed, cell coordinates)` into one sphere (or none).

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
  spawn-planet: true           # Force a landmark planet at the origin so there is something overhead at the landing spot
  spawn-radius: 42
  spawn-center-y: 60
  palette:
    - outer-block: STONE
      core-block: DEEPSLATE
```

**Procedural planet fields**

| Key | Type | Default | Description |
|---|---|---|---|
| `enabled` | bool | `true` | Turn it off and Cosmos becomes pure void (there is no other planet source) |
| `cell-size` | int | `96` | Grid cell edge length; **the larger, the sparser** |
| `chance` | double | `0.6` | Probability that a cell generates a planet; **the larger, the denser** |
| `min-radius` / `max-radius` | double | `6` / `28` | Radius range |
| `min-shell-thickness` / `max-shell-thickness` | double | `2` / `8` | Shell thickness range |
| `min-y` / `max-y` | int | `40` / `240` | Sphere-center Y range (also clipped by the world height) |
| `weight` | int | `0` | Draw weight when several spheres overlap in one chunk; rarely needs changing |
| `seed` | long | `20260101` | Change the number and you get a different starfield |
| `spawn-planet` | bool | `true` | Cell `(0,0)` always gets a planet centered at `(0, spawn-center-y, 0)`, regardless of `chance` |
| `spawn-radius` | double | `42` | Landmark planet radius |
| `spawn-center-y` | int | `60` | Landmark planet center Y |
| `palette` | list | 6 built-in entries | Each entry is one `outer-block` / `core-block` pair, picked at random |

Density quick reference:

| Want | Change |
|---|---|
| Denser | Lower `cell-size` to 64~80, or raise `chance` to 0.75 |
| Sparser | Raise `cell-size` to 128~192 |
| Bigger planets | Raise `max-radius` to 36~48 (note that adjacent planets will merge more often) |
| A different starfield | Change `seed` |
| No origin landmark | `spawn-planet: false` (there may be nothing near the landing spot, so you have to fly there yourself) |
| Disable procedural generation entirely | `procedural.enabled: false` |

> When the `procedural` section is missing (old configs), the plugin **enables the built-in defaults** and logs one line,
> so an upgrade never leaves you with an "empty sky".
> A `planets:` section from an old config is ignored with a one-time console notice and can simply be deleted.

## 8. Planet generation

### 8.1 Determinism

Procedural planets only feed two integers (the grid coordinates `cellX/cellZ`) and a seed into a SplitMix64 hash;
they never read `Random` and do not depend on chunk load order. The same `seed` + `cell-size` yields the same starfield on any server at any time.
Planet centers are constrained to their own cell, so "look up nearby planets" only needs the few cells around the landing point and is independent of the total planet count.

The only exception is the origin landmark: with `spawn-planet: true`, cell `(0,0)` rolls no dice and always generates a planet
centered at `(0, spawn-center-y, 0)` with radius `spawn-radius` (name `spawn`).

### 8.2 Overlap and weights

All planets intersecting a chunk are merged, sorted by `Planet#compareTo` and then drawn one by one:
lower `weight` is drawn first and covered by higher `weight`; with equal `weight`, larger spheres are drawn first and smaller ones after. There are no random numbers anywhere in this process,
so the same chunk always generates the same result.

### 8.3 Height

`PlanetChunkGenerator` only draws the part that intersects the current chunk's Y range: `minHeight = max(world floor, teleport-y-cosmos)`,
`maxHeight = world ceiling`. The top of a planet that exceeds the ceiling is flattened into a plateau — which is also why, under the default `THE_END` (0..255),
`min-y/max-y` use `40..240` and `spawn-center-y` uses `60`: the center is clamped once more so that as much of the sphere as possible stays visible.

### 8.4 Hot reload

`/cosmos reload` rebuilds the config snapshot and the planet registry and pushes them to the generator, but **chunks that have already been generated are not redrawn**.
For the new configuration to take full effect you need a new world (or players visiting regions they have never been to).

## 9. Runtime model

### 9.1 Thread ownership

| Operation | Thread |
|---|---|
| Reading config / parsing YAML | Async disk read → parsing on the global thread |
| Creating / taking over the world, setting rules, border, view distance | Global thread |
| `teleportAsync` follow-up (velocity, fall distance, cooldown) | The player's owning region thread (`EntityScheduler`) |
| Periodic tasks for dropped items / players | Region thread of the respective entity |

### 9.2 Full sequence of one teleport

```
PlayerMoveEvent (player region thread)
  └─ checkTransition: world name + Y comparison (pure math)
      └─ requestTo: cooldown / in-flight check → entityNextTick(player)   ← never teleport inline here
          └─ beginTransfer (next tick)
              └─ landing chunk getChunkAtAsync(..., true)
                 + outer ring getChunkAtAsync(..., false)                 ← disk read only, no generation
                  └─ allOf(...).orTimeout(3s)
                      └─ entityNextTick(player)
                          └─ player.teleportAsync(converted coordinates)
                              └─ back on the entity thread: clear velocity / fall distance / write cooldown
```

> **Why `entityNextTick` is mandatory**: after handling `PlayerMoveEvent`, regionized servers still run
> `player.absSnapTo(packet coordinates)`. If you call `teleportAsync` directly in the event callback, the target position is immediately reverted to the starting coordinates,
> and then `addPlayer` in the target world performs region validation against the old chunk, throwing `Cannot add entity off-main thread` and halting the server.

### 9.3 Folia detection

`ServerBuildInfo.buildInfo().isBrandCompatible(Key.key("papermc", "folia"))`; the result is used for logging / `/cosmos info`,
and for the startup correction strategy for "already existing dropped items": Folia has no global entity table, so scanning is scheduled per online player onto each player's own region thread;
on Paper the global thread simply iterates `World#getEntities()`.

## 10. Teleport details

### 10.1 Coordinate conversion

```
Overworld → Cosmos :  x' = x / scale , z' = z / scale , y' = teleport-y-cosmos + offset
Cosmos → Overworld :  x' = x * scale , z' = z * scale , y' = teleport-y-world  - offset
```

Example (defaults 64 / 400 / 0 / 10): overworld `(100, 400, 200)` → Cosmos `(1.5625, 10, 3.125)`;
descending to `Y <= 0` in Cosmos → overworld `(100, 390, 200)`.

### 10.2 Thresholds and hysteresis

Each world decides by **its own** threshold, and `offset` forms the hysteresis band: going to Cosmos lands at `teleport-y-cosmos + offset` (above the Cosmos threshold),
returning to the overworld lands at `teleport-y-world - offset` (below the overworld threshold), so landing never immediately triggers again.

### 10.3 Vehicles

A riding player is teleported as a whole (passengers follow the vehicle), with no separate correction; after a successful teleport neither the vehicle nor the passengers get any extra speed.

### 10.4 Cooldown and in-flight state

- `teleport-cooldown-ms`: written after a **successful** teleport; requests during the cooldown are dropped;
- `inFlight`: only one teleport per player at a time, so a burst of movement packets cannot trigger a chain of teleports.

### 10.5 View distance and preloading (stutter)

When crossing dimensions the client has to rebuild the mesh of every chunk within view distance, and that is exactly where Cosmos' performance bottleneck is:

- `cosmos-send-view-distance` (default 8) narrows the chunk view distance sent to the client on its own, **this is the most effective knob**, lower it to 6 if it still stutters;
- `cosmos-view-distance` (default 0 = follow global) controls the server-side simulation view distance;
- `teleport-preload-radius` (default 2) only **reads the chunks around the landing point back from disk** before teleporting (`generate=false`),
  the whole group waits at most 3 seconds and the teleport proceeds as usual on timeout — it never bulk-generates chunks before teleporting and never leaves the player stuck mid-teleport.

## 11. Floating

### 11.1 Dropped items

Dropped items inside Cosmos (optionally experience orbs) have gravity removed and horizontal velocity frozen, and you can configure whether they never expire;
already existing dropped items are covered both by a low-frequency sweep (`float-sweep-interval-ticks`) and by `EntitiesLoadEvent`.

### 11.2 Player low gravity

Once every `player-float-interval-ticks`, the fall velocity is clamped to `-player-float-fall-speed` and the fall distance is reset:
the result is a slow sink rather than free fall. **No flight is granted** (`allowFlight` / `flying` are never touched), and players who are already flying are skipped entirely.

**Elytra**: players with an elytra on their chest skip the sweep entirely (`player-float-skip-elytra`). The reason is very specific —
the client enters gliding first and only then sends `START_FALL_FLYING`, so on the server `isGliding()` is still `false` during those ticks;
sending a velocity packet in that window interrupts the "jump → spread wings" moment. With the skip in place, elytra users get the vanilla gliding feel inside Cosmos.
If a player is gliding when teleported, the velocity is **not** zeroed either (only the fall distance is cleared), to avoid losing momentum.

## 12. Build and test

### 12.1 Build

```bash
mvn -o clean package
# Output: target/Cosmos-1.1.0.jar
```

### 12.2 Unit tests

```bash
mvn -o test
```

40 in total, all passing:

| Test class | Count | Coverage |
|---|---|---|
| `ScalingTest` | 9 | Coordinate conversion, thresholds, vehicles, fallback for invalid values |
| `TeleportCooldownsTest` | 4 | One-way cooldown, cleanup, expiry |
| `PlanetTest` | 4 | Containment checks, core/shell blocks, bounding box |
| `PlanetRegistryTest` | 4 | Filtering disabled / too-small radii, sort stability |
| `PlanetChunkGeneratorTest` | 8 | Single-column fill, clipping, core override, empty registry |
| `ProceduralPlanetFieldTest` | 11 | Determinism, seed differences, density, Y range, agreement with brute force, palette, origin landmark (position / size / can be disabled), merge order of explicit + procedural |

### 12.3 Deployment verification checklist

1. Fly up to `Y >= 400` in the overworld → you should land at Cosmos `(x/64, 10, z/64)`;
2. `/execute in cosmos run tp @s 0 150 0` to inspect the origin landmark and the procedural planets;
3. Descend to `Y <= 0` in Cosmos → you should return to the overworld at `(x*64, 390, z*64)`;
4. Jump with an elytra in Cosmos → gliding should deploy and not be interrupted by velocity packets;
5. Drop an item → it hovers and does not fall;
6. `/cosmos info` → world, scale, planet count and procedural status should all match.

## 13. Deployment options

### Option A: the plugin creates the world itself

`cosmos-world-auto-create: true` (default). On startup it creates the `cosmos` world and takes over the generator.
Pros: zero dependencies; cons: not supported on pure Folia (`createWorld` is not implemented).

### Option B: hand it over to a world management plugin

First create a world named `cosmos` with Multiverse / MyWorlds / PhantomWorlds and set the generator to `Cosmos`,
then set `cosmos-world-auto-create` to `false`. On startup Cosmos **takes over** that existing world.

### Notes

- The world environment (`THE_END` / `NORMAL`) is fixed at creation time; changing the config only affects newly created worlds;
- `saveBundled` only writes when the file is missing, so an upgrade never overwrites the `config.yml` / `planets.yml` you modified;
- After takeover the world's game rules and spawn point are reset, and no platform is generated any more.

## 14. Commands and permissions

| Command | Permission | Description |
|---|---|---|
| `/cosmos reload` (alias `/cos`) | `cosmos.reload` (op by default) | Reload config / planets / message |
| `/cosmos tp <player>` | `cosmos.tp` (op by default) | Send a player into Cosmos (uses `fallback-location` when already in Cosmos) |
| `/cosmos info` | `cosmos.info` (everyone by default) | Runtime environment, scale, thresholds, world state, planet count |

`cosmos.admin` is the parent node of the three permissions above.

## 15. Known limitations

1. Without datapacks, world height is fixed at `NORMAL` -64..319, `THE_END`/`NETHER` 0..255;
2. No safe-landing search for the landing point: whatever the converted coordinate points at is where you land, the void is the void (`fallback-location` only serves `/cosmos tp`);
3. Generated chunks are not redrawn when the config changes; you need a new world or a new region;
4. Pure Folia cannot create worlds through the Bukkit API, so option B is required;
5. Planets are all procedurally generated; there is no hand-written planet list, and `weight` only decides the draw order when several spheres overlap in one chunk;
6. The top of an over-tall planet is flattened by the world ceiling; `procedural.max-y` is clamped into the build range automatically;
7. `teleport-y-cosmos` defaults to 0; if you change the Cosmos environment to `NORMAL` and want more vertical room for planets, you can raise it as a whole.

## 16. Troubleshooting

| Symptom | Check |
|---|---|
| Plugin does not load | Whether `api-version` matches the server (26.3 ↔ `'26.3'`, 1.21.x ↔ `'1.21'`) |
| `/cosmos info` reports the world as unavailable | Look at the creation failure reason in the console (disk, world name conflict, `auto-create=false`) |
| Flying up does not teleport | Whether `main-world` is named exactly like the actual overworld; whether `teleport-y-world` is actually reachable |
| Landing in the void after teleporting | Expected behavior: the landing point is the converted coordinate. To have planets near the landing point, confirm `procedural.spawn-planet: true` (origin landmark), or lower `spawn-center-y` so it sits just above the landing spot |
| Y after teleporting is not what you expect | Check `teleport-y-world` / `teleport-y-cosmos` / `teleport-y-offset` |
| Planets are not generated | Whether the world was created by the plugin; `procedural.enabled`; check the procedural status and origin landmark in `/cosmos info`; `/cosmos reload` only affects new chunks |
| Elytra does not fly in Cosmos | Confirm `player-float-skip-elytra: true`; if it is still abnormal, set `player-float-enabled` to false to rule out floating |
| Client freezes or even disconnects when entering / leaving Cosmos | Crossing dimensions rebuilds the mesh of the entire view distance: lower `cosmos-send-view-distance` to 6 and set `cosmos-view-distance` to 8; `teleport-preload-radius: 0` disables preloading entirely |
| Dropped items still fall in Cosmos | Whether `item-float-enabled` is true; drop a new item after `/cosmos reload` |
| Teleporting stops working after installing/removing a world management plugin | The watchdog takes over again within 100 ticks by default; you can also run `/cosmos reload` to retry immediately |
| Log says "generator is not the Cosmos planet generator" | That world was not created with the Cosmos generator: rebuild it with option B, or delete the world folder |
| Log says "the current server does not support Bukkit#createWorld" | You are running on pure Folia, switch to option B |
| Folia reports thread exceptions | Attach the full stack trace when reporting; all world operations inside the plugin should go through `RegionSchedulerFacade` |

## 17. Verification status

| Item | Result |
|---|---|
| `mvn -o clean package` (JDK 26, `--release 21`, Paper API 26.3) | Passed, artifact `target/Cosmos-1.1.0.jar` |
| Unit tests | 40 / 40 passed |
| Compiler warnings | Only deprecation notices for `WorldBorder#get/setWarningTime`, `Entity#isInWaterOrBubbleColumn` |
| Real-server end-to-end | **Not verified**: this machine only has a client, no reproducible Paper/Folia instance |

## 18. Changelog

### 1.1.0

- Added the procedural planet field (the `procedural` section of `planets.yml`); the same seed yields the same starfield, and density can be tuned with `cell-size` / `chance`;
- Planets are now **fully procedurally generated**: the hand-written `planets:` list is gone and every planet comes from the coordinate hash; added `spawn-planet` / `spawn-radius` / `spawn-center-y` to pin one landmark planet at the origin so there is something overhead at the landing spot;
- Fixed elytra gliding not working properly in Cosmos: players wearing an elytra skip the floating sweep, and gliding velocity is no longer zeroed on teleport;
- Added `cosmos-view-distance` / `cosmos-send-view-distance` / `teleport-preload-radius` to alleviate cross-dimension stutter;
- Removed two false-positive log messages: "teleport target Y is outside the build range" and "`teleport-y-world` is above the overworld's maximum height".

### 1.0.0

- First release: bidirectional Y-axis teleport, deterministic planet generation, floating dropped items, player low gravity, Folia compatibility.

## 19. License

[MIT](LICENSE) © 2026 LiuyeStudio. Free to use, modify and redistribute, as long as the copyright notice is retained.
