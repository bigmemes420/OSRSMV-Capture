# Capture format, version 1

What the OSMM Capture plugin writes. One file per recording, named
`capture_<yyyy-MM-dd_HH-mm-ss>.jsonl`, in `%USERPROFILE%\.runelite\plugin-data\osmm-capture\recordings\`, with its
notes sidecar ([below](#notes-sidecar)) beside it. The key for player codes, `anonymise.key`, is one folder up, in
`osmm-capture\` itself, so the recordings folder can be shared without it. (Earlier versions kept the recordings in
`osmm-capture\` too; the plugin moves them into `recordings\` when it starts, never over a file of the same name.)

- UTF-8, one JSON object per line ("JSON lines"), in the order the client posted the events.
- A key that would be `null` is left out: read a missing key as null.
- Text is ordinary JSON. Characters such as `<`, `>`, `=` and `'` are written as `<`-style escapes, which any
  JSON reader turns back.

## Common fields (every line)

| Key | Type | Meaning |
|---|---|---|
| `e` | string | Record type (see below). |
| `t` | int | Game ticks seen since the recording began (0 before the first), or since its lead-in began ([below](#recordings-started-in-combat)). A `tick` line carries the new count. |
| `tc` | int | The client's server tick count (`client.getTickCount()`). |
| `c` | int | The client's game cycle (`client.getGameCycle()`), one every 20 ms. The same clock as projectile, spot animation, graphics object and hitsplat cycles. |

## Actor references

`r`, `i`, `src`, `tgt` and the entries of `on` name an actor:

- `"p<index>"`: a player, by its index while it's in view (`Player.getId()`).
- `"n<index>"`: an NPC, by its index (`NPC.getIndex()`).
- Missing or null: none.

Indexes are reused after an actor leaves view. An `actor` record (re)defines a reference; a `gone` record ends it.

## Grouping lines into server ticks

Everything a tick's packets cause (animations, spot animations, hitsplats, sounds the server sends, interacting
changes) arrives in the same cycle as that tick's `tick` line, just before it. So:

- A line whose `c` equals the `c` of the next `tick` line came with that tick.
- A line whose `c` is lower happened between ticks, on the client's own clock (frame sounds, per-cycle motion,
  projectiles first seen late).

## Records

| `e` | Fields | Written when |
|---|---|---|
| `hdr` | `format` (`"osmm-capture"`), `version` (1), `label`, `revision` (game revision), `runelite` (client version), `world`, `utc` (start time, ISO 8601), `local` (your own reference; missing when recording starts at login, see `actor`'s `self`), `leadIn` (ticks recorded before combat started it; 0 when none), `anonymised` (true: players' names are codes, [below](#anonymised-names)), `config` {`scope`, `radius`, `motion`, `sounds`, `projectiles`, `gameMessages`, `localStats`}. `label` is `auto` for a recording started on login and `combat` for one started in combat, unless edited. | First line. |
| `scene` | `baseX`, `baseY` (world tile of the scene's corner), `plane`, `instance` (bool) | After `hdr`, and after each map load or reconnect. Every actor is described again (`actor`) after it. |
| `actor` | `r`, `name` (a player's is a code when `anonymised`; an NPC's is its own), `cb` (combat level), `size` (tiles), `idle`, `walk`, `run` (its base animations); NPC: `npc` (NPC id); player: `gender` (0 male, 1 female), `colors` [5], `equip` [12], `team`, `self` (true for you) | The first time an actor is recorded, and again after each `scene` line. |
| `eq` | `r`, `ids` [12], `idle`, `walk`, `run` | A recorded player's equipment changes (switches). |
| `tick` | `a`: one object per tracked actor (below) | Every game tick. |
| `mv` | `r`, `lx`, `ly` (drawn position, local units), `a` (animation), `af` (its frame), `p` (pose animation), `pf` (its frame), `co` (drawn orientation) | Every client cycle, for each tracked actor, **only when one of these changed**. Needs "Per-cycle motion". |
| `anim` | `r`, `a`, `af`, `p`, `lx`, `ly`, `x`, `y` (server tile), `i` (who it faces) | The client reports an animation change. |
| `spot` | `r`, `slot`, `id` (spot animation id), `start` (cycle it starts), `h` (height) | A spot animation appears, or its id or start changes, in a slot. |
| `gobj` | `id`, `x`, `y`, `z`, `start`, `h` | A graphics object (a spot animation on a tile) is created within the radius. |
| `proj` | `id`, `src`, `tgt`, `sx`, `sy`, `sz` (source tile and level), `tx`, `ty`, `tz` (target tile and level), `start`, `end` (cycles), `h0`, `h1` (start and end heights), `slope`, `startPos` | A projectile is first seen. Needs "Projectiles". |
| `snd` | `id` (sound id), `d` (delay, cycles), `src` | A sound effect plays (not positional). Needs "Sounds". |
| `asnd` | `id`, `d`, `src`, `sx`, `sy` (scene tile), `range` (tiles), `x`, `y` (world tile), `on` [] (tracked actors standing on that tile) | An area (positional) sound plays. Needs "Sounds". |
| `hit` | `r`, `type` (hitsplat type), `amt`, `until` (cycle it disappears), `hr`, `hs` (the health bar at the end of the tick, the same for every hitsplat that tick) | A hitsplat lands on a relevant actor. |
| `int` | `r`, `tgt` | A relevant actor starts or stops facing someone. |
| `death` | `r` | A relevant actor dies. |
| `npcchg` | `r`, `npc` (new id), `old` (old id, -1 if unknown) | An NPC transforms (boss phases). |
| `gone` | `r` | A recorded actor leaves view. |
| `msg` | `type` (`GAMEMESSAGE` or `SPAM`), `text` (players' names in it are codes when `anonymised`) | A game message. Needs "Game messages". |
| `stat` | `skill`, `lvl` (boosted), `base` | One of your combat stats changes. Needs "Your stats and spec". |
| `varp` | `id`, `v` | Special energy (300, 0 to 1000), special attack on (301) or run on (173) changes. Needs "Your stats and spec". |
| `note` | `text` | You add a note while recording (the side panel, or `::rec note <text>`). A notes sidecar replaces these (below). |
| `end` | `why` (`command`, `panel`, `LOGIN_SCREEN`, `HOPPING`, `plugin stopped`, `client closed`, or `out of combat` for a recording started in combat) | Last line. |

### The per-actor object in `tick`

| Key | Meaning |
|---|---|
| `r` | Reference. |
| `x`, `y`, `z` | The server's tile (where the game says the actor is; the drawn one trails it). |
| `a`, `p` | Animation and pose animation (-1 none). |
| `o` | Target orientation, 0 to 2047 (0 south, 512 west, 1024 north, 1536 east). |
| `i` | Who it faces. |
| `hr`, `hs` | Health bar fill and its scale (-1 when no bar shows). |
| `oh` | Player: overhead prayer (`MELEE`, `RANGED`, `MAGIC`, `SMITE`, `REDEMPTION`, `RETRIBUTION`, ...), missing if none. |
| `sk` | Player: skull icon (-1 none). |
| `id` | NPC: its id. |
| `ohA`, `ohS` | NPC: overhead icon archive and sprite ids, only on ticks they change. |

## Values

- **Local units**: 128 to a tile; a tile's centre is `tile * 128 + 64`. With the `scene` record:
  world tile = `baseX + (lx >> 7)`, and the same for y.
- **Equipment** (`equip`, `ids`), indexed head 0, cape 1, amulet 2, weapon 3, torso 4, shield 5, arms 6, legs 7,
  hair 8, hands 9, boots 10, jaw 11: a value of 2048 or more is the item `value - 2048`; 256 to 2047 is the body
  kit `value - 256`; 0 is nothing.
- **Hitsplat types**: 12 block (on you), 13 block (on others), 16 damage (on you), 17 damage (on others),
  43 max hit (on you), 65 poison, 5 venom, 6 heal, 74 burn, 0 corruption; others as RuneLite's `HitsplatID`.
- **Start values**: `stat` for each skill and `varp` for each watched varp are written once on the first tick you're
  in the game, then only as they change.
- **Delays** (`d`) and all cycle fields are in 20 ms client cycles.

## Who is tracked

Tracked actors get `tick` and `mv` lines.

- Scope **FIGHT**: you, and anyone you face or who faces you, kept for 50 ticks after the last such tick.
- Scope **NEARBY**: every player and NPC within the radius (tiles, the larger of the x and y distance) of you.

One-off records (`anim`, `spot`, `hit`, `int`, `death`, `npcchg`) are written for "relevant" actors: tracked ones,
you, anyone facing you or faced by you, and in NEARBY anyone within the radius. Sounds (`snd`, `asnd`) are written
whoever made them. Graphics objects and projectiles are written within the radius, or when a relevant actor fires
or is the target.

## Anonymised names

With "Anonymise player names" on (the default), the header has `anonymised: true` and every player's name is
written as a code: `Player ` and six upper-case hex digits, e.g. `Player 7F3A9C`. Yours is too; your `actor` line
still has `self`.

- The code is the start of HMAC-SHA256 of the name, under a random 32-byte key the plugin makes the first time it's
  needed and keeps in `osmm-capture\anonymise.key`, outside the `recordings\` folder (never in RuneLite's
  settings). The name is compared as the game compares names: tags removed, any case, and spaces, non-breaking spaces, underscores and
  hyphens the same.
- So a player has the same code in every recording made with the same key: across recordings, logins and restarts.
  Deleting `anonymise.key` makes a new key the next time the plugin starts, and from then on everyone gets new codes.
  Anyone with the key can check whether a code is a given name, so don't share it.
- In a game message (`msg`), each name of a player seen in the recording or in the scene is replaced by its code, as a
  whole word, whatever its case; tags around it stay.
- Within one recording a code is one player's. If two names come to the same six digits, the later one gets eight
  (or more), so that player's code can differ between recordings.
- NPCs' names, labels and notes are written as they are.
- The option is read when a recording (or its lead-in) starts; changing it takes effect from the next one. With it
  off, `anonymised` is false and names are written as they are.

## Recordings started in combat

With "Start in combat" on, the plugin keeps the last few seconds in memory while nothing is recording, and a
recording it starts in combat begins with them: the attack animation, projectile and sounds of the opening attack
come before the first hitsplat, which is when combat is noticed.

- The header's `label` is `combat`, and `leadIn` is the number of ticks kept from before combat was noticed
  (normally the "Seconds kept from before combat" option in ticks; fewer if less was held, or if a crowded area
  hit the memory bound). The lines up to `t` = `leadIn` are the lead-in; the hitsplat that started the recording is
  among its last.
- `t` 0 is the start of the lead-in. The lines at `t` 0 after the header set the scene as it stood then: `scene`,
  your `stat` and `varp` values, and an `actor` (with any `eq` and `npcchg`) for each actor still in view, so every
  reference is described before it's used. Their `tc` and `c` are from when each was first recorded, so they can
  be earlier than the header's.
- The header's `tc` and `c` are those of the lead-in's first line, and `utc` is the time combat was noticed less
  `leadIn` ticks.
- A map load inside the lead-in is a `scene` line like any other.
- It ends with `end` `why: "out of combat"` once there has been no hitsplat that counts (yours on someone else, or
  one on you while an attackable actor faces you, or did within 5 ticks, or you face one), and you haven't faced an
  attackable target, for the "Stop after (seconds) out of combat" time (unless that's 0, never). A death or a
  teleport away ends combat the same way.

Other recordings have `leadIn` 0 and start at the moment they're started.

## Notes sidecar

Labels and notes edited afterwards (in the side panel) go in a separate file beside the capture, so its records are
never rewritten: `<capture name>.notes.json`, e.g. `capture_2026-11-25_14-03-00.jsonl.notes.json`. One JSON object,
UTF-8:

```json
{"label": "tbow sounds", "notes": [{"t": 12, "text": "first shot"}, {"t": 40, "text": "spec"}]}
```

- `label` (string): replaces the header's `label`.
- `notes` (array, in tick order): replaces all the capture's `note` records. Each has `t` (the tick, as a record's
  `t`) and `text`. An empty array means no notes.
- When the file is there, read the label and notes from it, not from the capture. A key that's missing (or the wrong
  type) leaves the capture's own value. A sidecar that can't be read is ignored.
- Deleting the sidecar restores the capture's own label and notes.

## Size

`mv` lines dominate: at most about 50 a second for each moving actor, at roughly 110 bytes each. A five-minute
one-on-one is a few megabytes.

The lead-in is held in memory: about 15 to 30 KB a second for a one-on-one with Who to record FIGHT, and up to about
half a megabyte a second with NEARBY in a crowd. It never takes more than about 8 MB; past that the oldest ticks go
first, so a crowded lead-in can be shorter than asked.
