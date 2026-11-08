# Cosmos

**English** | [简体中文](README.zh-CN.md)

> **1.1.1** · Paper 26.3 / Folia 26.3

Cosmos adds a second dimension above your world: keep flying up and you break through into a silent starfield where every planet is generated from a coordinate hash. Fall back down and you return to the same spot you left from.

## Features

| Feature | What it gives you |
|---|---|
| **A sky full of planets** | Every planet is generated from its coordinates and a seed, so the same seed always produces the same sky. Spheres of stone, sand, ice, basalt and more, each with a crust, a thicker shell and a solid core. |
| **Y-axis travel** | Fly past `Y = 400` in the overworld to enter Cosmos; drop to `Y = 0` or below in Cosmos to come home. No portals, no commands needed. |
| **Scale that keeps you oriented** | The two worlds share one horizontal map at 1:64 — `x = 1600` in the overworld is `x = 25` in Cosmos. Your position always maps back. |
| **Low gravity, elytra-first** | Falling is slowed to a gentle drift, and anyone wearing an elytra is left completely alone so gliding feels vanilla. |
| **Floating items** | Dropped items hang in space instead of dropping forever (experience orbs optional). |
| **A landmark to land under** | A guaranteed planet sits right above the arrival point, so stepping into the void is never a dead end. |
| **Folia ready** | Runs on both Paper and Folia, detected at runtime. |

## Requirements

- Paper **26.3** or Folia **26.3** (older 1.21.x servers work only with a jar built for their API version)
- Java 21 or newer on the server

## Install

1. Drop `Cosmos-1.1.1.jar` into `plugins/`.
2. Start the server.
3. That's it — the `cosmos` world is created the first time someone travels there, or immediately with `/cosmos reload`.

## Your first trip

1. Stand in the overworld and fly (or build) up to `Y = 400`.
2. You are moved to Cosmos at `(x / 64, 10, z / 64)`. The landing spot is checked first: if that column holds a planet you stand on its surface, otherwise you land just above the origin landmark — never in open space with nothing below.
3. Look up: that planet is your landmark. Everything else is procedural sky.
4. To come back, drop to `Y = 0` or below in Cosmos and you reappear at `(x * 64, 390, z * 64)`.

A 3-second cooldown after each jump prevents bouncing back and forth, and the Cosmos world border always mirrors your overworld border.

> **On a 320-block-tall overworld** the build limit is `Y = 319`, so reaching `Y = 400` needs an elytra and fireworks. That is intentional — but you can lower `teleport-y-world` (for example to `330`) if you prefer an easier trip.

## Configuration

Three files, all in `plugins/Cosmos/`: `config.yml` (travel, world, floating), `planets.yml` (the starfield) and `message.yml` (every player-facing string). Run `/cosmos reload` after editing; world-level settings (name, environment, seed) only apply to a world that is created afterwards.

### `config.yml` — travel

| Key | Default | Meaning |
|---|---|---|
| `scale` | `64` | Horizontal ratio between the worlds. `4` means Cosmos is 4× smaller. |
| `main-world` | `world` | The overworld name travel starts from. |
| `teleport-y-world` | `400` | `Y` in the overworld that sends you up to Cosmos. |
| `teleport-y-cosmos` | `0` | `Y` in Cosmos that sends you back down. |
| `teleport-y-offset` | `10` | Safety margin: you arrive at `teleport-y-cosmos + offset` and leave at `teleport-y-world - offset`. |
| `teleport-cooldown-ms` | `3000` | Ignore new jumps for this long after one. |
| `portal-cooldown-ticks` | `100` | Vanilla portal cooldown applied after travel. |
| `y-check-interval-ticks` | `10` | How often player heights are checked. |
| `teleport-preload-radius` | `2` | Chunks around the landing spot loaded before the jump (radius 2 = 5×5). `0` disables preloading. |
| `fallback-location` | `cosmos 0.5 160 0.5` | Where `/cosmos tp` puts players who have no position to map. |

### `config.yml` — the Cosmos world

| Key | Default | Meaning |
|---|---|---|
| `cosmos-world-name` | `cosmos` | Name of the dimension's world folder. Change it to get a brand-new sky. |
| `cosmos-world-environment` | `THE_END` | World type; `THE_END` gives a dark, cloudless sky. Height limits follow the environment. |
| `cosmos-biome` | `THE_END` | Biome used everywhere. |
| `cosmos-world-seed` | `1337` | Seed of the world itself (terrain features, not planets). |
| `cosmos-world-auto-create` | `true` | Create the world automatically if it does not exist. |
| `cosmos-world-border-sync` | `true` | Mirror the overworld border into Cosmos at the chosen scale. |
| `cosmos-world-gamerules` | see file | Gamerules applied to Cosmos (mob spawning off, time running, weather frozen, fall damage on). |
| `world-check-interval-ticks` | `100` | How often the plugin looks for the world / border changes. |
| `cosmos-view-distance` | `0` | Simulation view distance for Cosmos. `0` keeps the server default; `6`–`8` makes dimension changes much lighter. |
| `cosmos-send-view-distance` | `8` | Chunks sent to the client in Cosmos. Lower = faster to enter and leave, but fog closer in. |

### `config.yml` — floating and low gravity

| Key | Default | Meaning |
|---|---|---|
| `item-float-enabled` | `true` | Dropped items stop falling in Cosmos. |
| `float-experience-orb` | `false` | Also freeze experience orbs. |
| `float-freeze-velocity` | `true` | Stop items completely instead of letting them drift. |
| `float-unlimited-lifetime` | `false` | Keep floating items forever. |
| `float-sweep-interval-ticks` | `200` | How often the area around each player is re-checked. |
| `float-sweep-radius` | `16` | Radius of that re-check — dropped items inside it are re-applied the floating state. |
| `player-float-enabled` | `true` | Slow down falling in Cosmos. |
| `player-float-fall-speed` | `0.08` | Maximum downward speed while falling, in blocks per tick. |
| `player-float-interval-ticks` | `2` | How often that speed limit is applied. |
| `player-float-skip-elytra` | `true` | Leave players wearing an elytra alone — **keep this on** unless you want the slow fall to fight your glider. |
| `debug` | `false` | Extra log lines. |

### `planets.yml` — the starfield

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Turn all procedural planets off (the world becomes empty space). |
| `cell-size` | `96` | Size of the grid cell each planet is placed in. Smaller = denser sky. |
| `chance` | `0.6` | Probability that a cell holds a planet. Higher = denser sky. |
| `min-radius` / `max-radius` | `6` / `28` | Size range of generated planets. |
| `min-shell-thickness` / `max-shell-thickness` | `2` / `8` | Thickness of the crust between surface and core. |
| `min-y` / `max-y` | `40` / `240` | Height band the whole planet may occupy; the centre is inset by its radius, then clamped to the world's build range. |
| `seed` | `20260101` | Change it for a completely different sky. |
| `spawn-planet` | `true` | Keep one planet directly above the arrival point. |
| `spawn-radius` / `spawn-center-y` | `42` / `60` | Size and height of that landmark planet. |
| `weight` | `0` | Draw order when planets overlap: lower first; at equal weight larger planets are drawn first, so smaller ones end up on top. |
| `palette` | 6 entries | Block pairs (`outer-block` / `core-block`) used to build planets; one is picked per planet. |

Quick tuning:

- **More planets** — lower `cell-size` (e.g. `64`) or raise `chance` (e.g. `0.8`).
- **Fewer, bigger planets** — raise `cell-size`, raise `max-radius`.
- **Different sky, same world** — change `seed`.
- **No landmark** — `spawn-planet: false`.
- **Empty space** — `enabled: false`.

## Commands and permissions

| Command | Permission | Default | What it does |
|---|---|---|---|
| `/cosmos reload` (`/cos reload`) | `cosmos.reload` | op | Reload `config.yml`, `planets.yml` and `message.yml`. |
| `/cosmos tp <player>` | `cosmos.tp` | op | Send a player into Cosmos. |
| `/cosmos info` | `cosmos.info` | everyone | Show scale, thresholds and world status. |

`cosmos.admin` grants all three. Every message (prefix, colours, wording) is editable in `message.yml`.

## Troubleshooting

| Symptom | Fix |
|---|---|
| Flying up does nothing | You must be in `main-world` and at or above `teleport-y-world`. Check `/cosmos info`. |
| I land on a planet instead of the mapped height | Expected — the arrival snaps to the surface so you cannot fall straight back through `Y = 0`. Set `spawn-planet: false` and make sure the column is empty if you want the raw height. |
| Planets look sparse | Lower `cell-size` or raise `chance`; or pick a new `seed`. |
| The sky looks nothing like the screenshots | Chunks that already exist are never redrawn. Travel to new areas, or change `cosmos-world-name` for a fresh world. |
| Elytra barely works in Cosmos | Make sure `player-float-skip-elytra` is `true` (the default). |
| Client freezes when entering or leaving | Lower `cosmos-send-view-distance` to `6`, and `teleport-preload-radius` to `0`. |
| My old `planets:` list is ignored | Correct — hand-written planets were replaced by the procedural field. Delete the section. |
| Config edits do nothing | Run `/cosmos reload`; changing `cosmos-world-name`, environment or seed requires a new world. |

## Known limitations

- Planets are static solid spheres; they do not orbit or pull you in.
- Cosmos is a normal Bukkit world, so its height limit follows `cosmos-world-environment` (`THE_END` = 256 blocks). Planets that would stick out are flattened at the top.
- `THE_END` skies have no sun or clouds — that is the point, but it means no natural light source.
- Changing the environment or seed of an existing world is not supported; rename the world instead.
- The safe-arrival snap only applies to overworld → Cosmos; the return trip still uses exactly `teleport-y-world - offset`.
- Folia has no `createWorld` API: prepare the `cosmos` world ahead of time (or create it once on Paper and copy the folder over).

## License

MIT © 2026 LiuyeStudio — see [LICENSE](LICENSE).
