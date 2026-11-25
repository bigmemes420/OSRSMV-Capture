# OSMM capture: recording fights in the real game

A small RuneLite plugin that writes down what happens in a fight, tick by tick and frame by frame: who animates
with what, which sounds play and when, where everyone stands, projectiles, graphics, hitsplats, gear and
switches. Old School Movie Maker imports these files ("Importing a capture" in its How to Use guide, section 22), and they settle questions the game's cache can't
answer (which sounds the server plays, how a player moves while attacking).

You run it yourself, by hand, in a RuneLite you start from this folder.

## Contents

- [What it does and doesn't do](#what-it-does-and-doesnt-do)
- [The rules, in plain words](#the-rules-in-plain-words)
- [One-time setup on Windows](#one-time-setup-on-windows)
- [Before you record](#before-you-record)
- [Recording](#recording)
- [What to record](#what-to-record)
- [Sending the files back](#sending-the-files-back)
- [Troubleshooting](#troubleshooting)
- [The format](#the-format)

## What it does and doesn't do

It **only reads** what your client already knows and writes it to a file on your computer.

- In game it shows only a small recording indicator (you can turn it off): no markers, no timers, no extra sounds,
  no menu entries.
- It sends nothing: not to the game, not to the internet, not to anyone.
- It never clicks, moves the mouse or presses keys. You play normally.
- Its only other output in game is a chat line when a recording starts, is noted or stops. Its side panel sits in
  RuneLite's sidebar, beside the game, not in it.

Files go to `%USERPROFILE%\.runelite\plugin-data\osmm-capture\`.

## The rules, in plain words

- **Approved clients.** Jagex's
  [Third Party Client Guidelines](https://secure.runescape.com/m=news/third-party-client-guidelines?oldschool=1)
  say the only approved third-party clients are the ones on their list (RuneLite and HDOS). Using any other client
  may lead to permanent account action.
- **A RuneLite you start yourself.** This kit starts RuneLite's own published client in developer mode, the way
  RuneLite's wiki tells plugin developers to
  ([Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts),
  [Using the client developer tools](https://github.com/runelite/runelite/wiki/Using-the-client-developer-tools)).
  Neither Jagex page mentions this setup or plugins that aren't on the Plugin Hub. Jagex's legal page "Macro and
  client features not permitted" ([legal.jagex.com](https://legal.jagex.com/)) forbids modified versions of the game
  applets; this kit doesn't modify anything, but whether a self-started client counts is Jagex's call, not ours.
  **Whether to run it is your decision.**
- **No automation.** No tool, script or AI plays for you. Play by hand.
- **Nothing on screen but the indicator.** The guidelines ban in-game helpers such as freeze timers, projectile
  landing spots, prayer hints and extra audio cues. The plugin shows none of these: its one overlay says only whether
  it's recording. Don't add others to it, and don't use the Developer Tools' detached camera to click in the world.
- **Other players.** Captures hold other players' names and gear. Keep them private and strip the names before
  sharing ([Sending the files back](#sending-the-files-back)). RuneLite's own rules reject plugins that share other
  players' data ([Rejected or Rolled Back Features](https://github.com/runelite/runelite/wiki/Rejected-or-Rolled-Back-Features));
  this one keeps everything on your disk.
- **Never log in with the folders in `C:\Users\Rooty\ideaProjects`** (`runelite`, `rootylite`). They're
  OpenOSRS-based, not RuneLite, so not an approved client.

## One-time setup on Windows

### 1. A JDK: 11, 17 or 21

The build runs on Java 11, 17 or 21. **Not 25 or 26**: the Gradle version this uses (8.10) can't run on them
([Gradle's compatibility matrix](https://docs.gradle.org/current/userguide/compatibility.html)).

This machine already has `C:\Program Files\Java\jdk-17` and `C:\Program Files\Java\jdk-21.0.11`. Use either.
Otherwise install Eclipse Temurin 17 or 21 from [adoptium.net](https://adoptium.net/).

### 2. IntelliJ IDEA (recommended) or Gradle

**IntelliJ IDEA** (the free edition is enough, from [jetbrains.com/idea](https://www.jetbrains.com/idea/download/))
brings Gradle with it.

1. **File > Open**, pick the `tools\runelite-capture` folder (the one with `build.gradle`), and choose **Trust
   Project**.
2. Point Gradle at your JDK: **File > Settings > Build, Execution, Deployment > Build Tools > Gradle**, set
   **Gradle JVM** to `jdk-17` or `jdk-21` (use **Add JDK...** and browse to `C:\Program Files\Java\jdk-17` if it
   isn't listed). If there's a **Distribution** choice, pick **Wrapper**: IntelliJ then fetches Gradle 8.10 itself,
   as `gradle\wrapper\gradle-wrapper.properties` says.
3. Also set **File > Project Structure > Project > SDK** to the same JDK.
4. Let the Gradle sync finish (progress bar at the bottom). The first sync downloads RuneLite's client from
   `repo.runelite.net`, which takes a minute.

**Without IntelliJ**: download Gradle 8.10 ("binary-only") from [gradle.org/releases](https://gradle.org/releases/),
unzip it to `C:\Gradle\gradle-8.10`, then in PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
cd C:\Users\Rooty\source\repos\OSRSMapViewerNativePort\tools\runelite-capture
C:\Gradle\gradle-8.10\bin\gradle.bat run
```

(The folder has no `gradlew.bat`; it can't be made without Gradle already installed.)

### 3. Start the client

- **IntelliJ**: open the **Gradle** tool window (elephant icon, right edge), then **osmm-capture > Tasks >
  application > run**, and double-click it. Next time it's in the run list at the top right.
- **Gradle**: `gradle run`, as above.

The `run` task starts RuneLite with the plugin built in, in developer mode (`--developer-mode --debug`) and with
assertions on (`-ea`), which developer mode requires. A RuneLite window opens; the first start downloads the game
cache. The console shows a lot of debug lines: that's normal.

### 4. Log in with your Jagex account

1. On the RuneLite sidebar (right edge), click the **Jagex auth panel** button.
2. Click **Add Jagex Account** and finish the Jagex login in the window that opens.
3. Pick your character, then **Play Now** on the login screen.

Next time: pick the character and **Play Now**. No password or credentials file is stored by this kit; RuneLite
keeps the session in Windows' credential store. If you have an old `%USERPROFILE%\.runelite\credentials.properties`
from earlier development, RuneLite's wiki recommends deleting it.

### 5. After a game update (usually Wednesdays)

The client must match the game. The build asks for RuneLite's latest release, but Gradle remembers the answer for up
to a day. If the client won't connect after an update, run once with `--refresh-dependencies`:

- **IntelliJ**: in the Gradle tool window, click **Execute Gradle Task** (the elephant button) and type
  `run --refresh-dependencies`.
- **Gradle**: `gradle run --refresh-dependencies`.

If it still won't connect, RuneLite hasn't released its update yet; try again later that day.

## Before you record

**Game settings** (Settings, the spanner icon, **Audio** tab):

- **Sound effects** above 0.
- **Area sounds** above 0.

The client skips sounds whose volume is 0 without RuneLite ever seeing them, so they wouldn't be recorded. Any
non-zero volume will do. Music doesn't matter.

**Stay near what you record.** Area sounds only reach you within their range (often only a few tiles), and only
what reaches you is recorded.

**The plugin's options**: the wrench icon on the sidebar, search for **OSMM**.

| Option | Default | What it does |
|---|---|---|
| Show recording indicator | on | A small box at the top left of the game while recording or waiting for combat ([below](#the-recording-indicator)). |
| Start on login | off | Starts a recording when you log in or hop worlds, or when the plugin is turned on while you're logged in. Not on teleports or other map loads, and not again after you stop one until you next log in or hop. |
| Start in combat | off | Starts a recording when something attacking you hits you or you hit something, and stops it once you're out of combat ([below](#recording-fights-automatically)). |
| Stop after (seconds) out of combat | 30 | For recordings started in combat: how long with no hits, and not attacking anything, before it stops. 5 to 600, or 0 to keep recording until you stop it. |
| Seconds kept from before combat | 6 | Recordings started in combat begin this long before the first hit, so the opening attack is in them. 0 to 30. |
| Who to record | FIGHT | FIGHT: you and whoever you fight (kept for 50 ticks after). NEARBY: every player and NPC within the radius. |
| Radius (tiles) | 15 | For NEARBY, and how far away graphics and projectiles are recorded. |
| Per-cycle motion | on | Positions and animation frames 50 times a second when they change. Needed for movement questions. |
| Sounds | on | Sound effects and area sounds. |
| Projectiles | on | Each projectile once. |
| Game messages | on | Game messages ("You have been frozen!"). |
| Your stats and spec | on | Your combat stats, special energy, spec and run toggles. |

Changes apply to the next recording; the indicator and the combat options apply at once.

## Recording

### With the side panel

Click the red dot on RuneLite's sidebar (**OSMM capture**). From the top:

- **Status**: "Not recording", "Waiting for combat", or the file and how long it's run ("250 ticks (2:30)"). Type a **Label** if you
  like (it goes in the file, e.g. `tbow sounds`), then **Start**. **Stop** ends and saves the file.
- **Note** (while recording): type what you just did and press **Add note** or Enter, e.g. `barrage while
  walking`. Do it straight after each thing you do. The notes so far are listed under it, each with its tick.
- **Recordings**: every capture in the folder, newest first, with its label, date and time, length and size.
  Click one to edit its label and notes (below), open the folder, or delete it (it asks first; not the one
  still recording).

### With chat commands

The same, typed in the chatbox (with the two colons):

| Command | What it does |
|---|---|
| `::rec start <label>` | Starts a new file. The label is optional, e.g. `::rec start tbow sounds`. |
| `::rec note <text>` | Marks a moment, e.g. `::rec note barrage while walking`. |
| `::rec stop` | Ends and saves the file. |
| `::rec` | Says whether it's recording, and to which file. |

A recording also ends when you log out or hop worlds. Each start makes a new file:

```
%USERPROFILE%\.runelite\plugin-data\osmm-capture\capture_2026-11-23_20-15-04.jsonl
```

(**Open folder** in the panel opens it, or paste `%USERPROFILE%\.runelite\plugin-data\osmm-capture` into
Explorer's address bar.)

### Recording fights automatically

Turn on **Start in combat**. The status then says "Waiting for combat", and a recording starts by itself when:

- something attacking you hits you (a hitsplat on you, including a 0, while another player or a monster with a
  combat level faces you, or did in the last 3 seconds, or while you face one), or
- you hit something (your hitsplat on anyone else, including a 0).

Damage you do to yourself doesn't count when nothing is attacking you: a rock cake, a divine potion, a Zamorak
brew, the locator orb, a Guthix rest, a Nightmare Zone overload. Nor do heals, poison, venom, disease and burn. Just
facing each other doesn't count either: a splash shows no hitsplat, so a fight of splashes alone starts nothing until
a hitsplat lands (the seconds kept from before it still catch the start). The recording is labelled `combat`, and starts a few seconds
before the first hit (**Seconds kept from before combat**), so the opening attack's animation, projectile and sound
are in it. It stops by itself once nothing has hit you or been hit by you, and you haven't faced anything to attack,
for **Stop after (seconds) out of combat**: after the kill, a death, or a teleport away.

- **Stop** (or `::rec stop`) in a fight ends it for good: no new recording starts until that fight is over and
  another begins.
- A recording you started yourself, or one started on login, is left alone: combat neither stops it nor starts a
  second.
- Logging out or hopping ends it, as with any recording.

While waiting, the plugin keeps those few seconds in memory (about 30 KB a second in a one-on-one, never more than
about 8 MB), not on disk.

### The recording indicator

With **Show recording indicator** on, a small box at the top left of the game shows a red dot, "Recording", the time
and the ticks while recording, and a grey "Waiting for combat" while **Start in combat** is waiting. Otherwise it
shows nothing. Hold **Alt** and drag it to move it, like any RuneLite overlay; Alt and right-click it to reset it.

### Editing notes afterwards

Pick a finished recording in the panel's list. Its own view opens, with its label and notes, each note with its tick:

- Change the label or any note's text or tick; the cross deletes a note.
- **Add note** adds one; type the tick it belongs to (the panel's clock and each note show ticks).
- **Save** keeps the changes. **Back** returns to the main view, asking first if there are unsaved changes.
- **Delete** removes the recording and its notes, after asking. (**Open folder** is on the main view.)

The recording itself isn't changed: the edits go in a small file beside it, `<capture name>.notes.json`, which
replaces the file's own label and notes for anything that reads it ([FORMAT.md](FORMAT.md#notes-sidecar)); Old School
Movie Maker's importer doesn't read it yet.
Delete that file to go back to the originals.

**Size**: a quiet minute is tens of kilobytes; a busy one-on-one with motion on is up to about a megabyte a
minute, so a five-minute fight is a few megabytes. Short recordings, one topic each, are easiest to use.

## What to record

Each recipe says whether it needs a **friend** (a second player, or your own second account on another computer)
or works **solo**. For anything against another player, use a safe area where nothing is lost on death:

- **PvP Arena, Legacy Duels** ([wiki](https://oldschool.runescape.wiki/w/PvP_Arena)): Al Kharid, on a Legacy Duels
  world. Challenge your friend, bring your own gear, and leave **Movement** allowed in the duel options. Good for
  clean one-on-ones.
- **Clan Wars free-for-all** ([wiki](https://oldschool.runescape.wiki/w/Clan_Wars)): the white portal at Ferox
  Enclave. Nothing is lost on death, and there's room to walk and run, which the movement recipes need.

Every recipe: start a recording with a label first, add a note saying what you did straight after each try, and
stop at the end. Below, `::rec note barrage walking` means a note saying "barrage walking": type it in the panel's
**Note** box or as the command. Do each thing two or three times. If something went wrong, say so in a note
(`::rec note that one missed`); don't worry about getting timing exact, the file has the exact ticks.

"Attack while walking" below means: click the attack (or spell) on the target, then **straight away** (within half a
second) click a tile further along your path, so you keep moving. That's the usual "attack and step" from PvP.

### (a) Attacking or casting while moving (friend)

Options: Who to record **FIGHT**, Per-cycle motion **on**. Your friend stands still a few tiles to one side of a
straight line you can walk along (Clan Wars free-for-all is ideal).

1. **Barrage while walking.** Run off. Click a tile about 10 tiles away. After 2 or 3 steps, cast Ice Barrage (or
   any barrage or burst you have) on your friend and keep walking. `::rec note barrage walking`.
2. **Barrage while running.** The same with run on. `::rec note barrage running`.
3. **Barrage standing.** Stand still and cast it. `::rec note barrage standing`.
4. **Ballista from standing.** Stand still, attack your friend with a heavy or light ballista, and in the same moment
   click a tile one step away, then keep walking a few tiles. `::rec note ballista standing then step`.
5. **Ballista while moving.** Walking, attack and keep walking. `::rec note ballista walking`.
6. **Ballista special**, both ways (4 and 5 with the special on). `::rec note ballista spec standing` /
   `ballista spec walking`.
7. **Crossbow every 5 ticks while walking.** Any crossbow on **Rapid** (it then fires every 5 ticks, 3 seconds).
   Walk back and forth past your friend for about 30 seconds, attacking each time it's ready and keeping on walking.
   `::rec note crossbow rapid walking`.
8. **Melee swing while stepping.** With any melee weapon: from two tiles away, attack your friend so you step in and
   swing; then swing and step away straight after. `::rec note melee step in` / `melee step out`.
9. **Optional, solo**: 4, 5 and 7 at a monster instead of a player (the game uses different animations against
   NPCs). `::rec note ... at npc`.

### (b) Sounds

Options: Who to record **FIGHT** (or **NEARBY**, radius 20, for group bosses), Sounds **on**. Sounds are recorded
whoever makes them. Stand within a few tiles. One weapon or action per note.

**Weapons (solo, at any monster; at your friend works too).** For each: a few normal attacks, then
`::rec note <weapon> attacks`; where it has a special, a couple of specials, then `::rec note <weapon> spec`.

1. **Twisted bow**: 3 or 4 shots. `::rec note tbow`.
2. **Zaryte crossbow**: normal shots, then its special. `::rec note zcb` / `zcb spec`.
3. **Elder maul**: a few hits (it has no special). `::rec note elder maul`.
4. **Scythe of vitur** (charged): hits on a big monster (it hits up to three times) and on a small one.
   `::rec note scythe big` / `scythe small`.
5. **Trident** (of the seas or swamp, charged): a few casts. `::rec note trident`.
6. **Rune thrownaxe special (Chainhit)**: in a multi-combat area with several monsters close together, so it
   bounces. `::rec note thrownaxe chainhit`.

**A 0 hit's armour sound (friend).** Your friend attacks you with something weak until a few 0s (blue hitsplats)
show, while you wear:

7. Full metal armour (e.g. rune or bandos). `::rec note zeros in plate`.
8. Leather or dragonhide. `::rec note zeros in hide`.
9. Robes, then nothing at all. `::rec note zeros in robes` / `zeros no armour`.

If you and your friend have characters of different genders, swap roles once too
(`::rec note zeros on female` / `on male`): the hurt sounds differ.

**Bosses (solo or with a group).** Start recording before the fight, note each phase or special as it happens
(`::rec note sire phase 2`), and stop after the kill. Stay close to the boss where it's safe.

10. **The Nightmare** (or Phosani's Nightmare solo). Who to record NEARBY.
11. **Abyssal Sire**, all phases.
12. **Nex**. NEARBY.
13. **Tombs of Amascut**: Akkha, Ba-Ba, Kephri, Zebak and the Wardens. Entry mode is fine. One recording per boss.

### (c) Movement stall timing (friend)

What this answers: when a player attacks while moving, does the game freeze them in place for the animation and
then hurry them along afterwards, for how long, and how fast. Options: Who to record **FIGHT**, Per-cycle motion
**on**. Your friend stands still, about 5 tiles to the side of a long straight line you walk along, so you stay in
range the whole way. Keep it quiet around you. One recording per recipe, and note each try.

1. **A: walk, barrage on the way.** Run off. Start walking along the line (at least 15 tiles to go). On about the
   fourth step, cast Ice Barrage on your friend and keep walking for at least 10 more tiles.
   `::rec note A barrage walking`.
2. **C: the same running.** Run on, same as A. `::rec note C barrage running`.
3. **D: ballista from standing, stepping off at once.** Stand still. Attack your friend with the ballista and click a
   tile on the line in the same moment, then keep walking at least 6 tiles. `::rec note D ballista standing step`.
   Repeat with the ballista special: `::rec note D ballista spec`.
4. **E: ballista while walking.** Walk along the line; on about the fourth step, attack with the ballista and keep
   walking. `::rec note E ballista walking`.
5. **F: walk, barrage, stop.** Run off. Click a tile exactly 7 tiles away. On about the fourth step cast Ice Barrage
   and click the same tile again, so you stop there. `::rec note F barrage then stop`.
6. **B: crossbow every 5 ticks while walking.** Any crossbow on Rapid. Walk along the line and back for about
   30 seconds without stopping, attacking every time the crossbow is ready. `::rec note B crossbow every 5`.
7. **G: barrage every 5 ticks while walking.** As B, casting Ice Barrage each time it's ready (every 5 ticks).
   `::rec note G barrage every 5`.
8. **Optional**: A and B at a monster instead of your friend. `::rec note A at npc` / `B at npc`.

If you can't use Ice Barrage, any Ancient spell works (say which in the note); if you can't use a ballista, skip D
and E.

### (d) A whole fight, for importing

- **Your own fight**: Who to record **FIGHT**. `::rec start <label>` before it starts, `::rec stop` after. Note
  anything worth knowing (`::rec note he switched to mage`).
- **A fight you watch**: Who to record **NEARBY**, radius 15 to 20, and stand where you can see it.
- Keep each file to one fight. Gear, switches, positions, health, prayers, animations, graphics, projectiles,
  hitsplats and sounds are all in it.

### (e) Can a hit on a player land for 0? (friend)

Since May 2024 a player's hit that passes the accuracy roll can't do 0 against monsters. No source says whether that
holds against players. Wind Strike settles it: its most is 2, and a miss splashes (a blue cloud, no hitsplat), so a
blue 0 hitsplat with the spell's impact means a hit that landed for 0.

Options: Who to record **FIGHT**. No magic gear on you (nothing adding magic damage); your friend in melee armour (it's
easy to hit with magic), no prayers.

1. Cast **Wind Strike** on your friend about 40 times. `::rec note wind strike no prayer`.
2. Then the same about 20 times with your friend on **Protect from Magic**. `::rec note wind strike protect magic`.

If any landed cast shows a 0 in step 1, the rule doesn't apply in PvP; if every landed cast is 1 or 2, it does.

### (f) Duke Sucellus (solo)

The viewer's Duke guesses a few things the wiki doesn't say. Needs Desert Treasure II done (the normal Duke, not the
quest one or Awakened). Options: Who to record **FIGHT**, Per-cycle motion **on**, Sounds **on**. One recording per
kill: `::rec start duke`, wake him, fight, and `::rec stop` once he's back asleep.

1. **His magic through Protect from Magic.** Stand off his melee range with Protect from Magic on and let him cast
   at you for as long as is safe (20 casts or more). `::rec note magic prayed`. His biggest hit through the prayer
   says how much it takes off (the viewer guesses a quarter, 48 down to 36).
2. **His gas.** When he spits gas at a vent, stand in it for a few ticks without a slayer helmet or gas mask
   (`::rec note gas no mask`), and on another flare with one on (`::rec note gas mask`). This shows how long it lies,
   how often it hits, and what the mask takes off.
3. **The kill and after.** Stay until he's asleep again. This shows his dead pose and how long he takes to come back.

His animations (the gas spit, his faster attack below 25%) and when the icicles land against his slam are all in
the file without notes.

## Sending the files back

1. Open `%USERPROFILE%\.runelite\plugin-data\osmm-capture\`.
2. **Strip other players' names.** `strip_names.py`, beside this README, makes a copy with each player's name
   replaced by `Player 1`, `Player 2` and so on (the same person the same number all through, so the importer still
   tells them apart), game messages left out (they can mention names), and any name in your notes replaced too.
   Change the file name:

   ```powershell
   python -I tools\runelite-capture\strip_names.py "$env:USERPROFILE\.runelite\plugin-data\osmm-capture\capture_2026-11-23_20-15-04.jsonl" capture_shared.jsonl
   ```

3. Drop the `_shared.jsonl` files into the repo's `captures\` folder (it's ignored by git, so they never get
   committed), or attach them in chat. Say which recipe each one is if the label and notes don't.
4. If you edited a recording's notes, send its `.notes.json` too, renamed to match (e.g.
   `capture_shared.jsonl.notes.json`). `strip_names.py` doesn't read it, so check it for names first.

## Troubleshooting

| Problem | Fix |
|---|---|
| "Developers should enable assertions; Add -ea to your JVM arguments", or the client quits at once | Start it with the `run` task, which adds `-ea`. If you made your own run configuration, add `-ea` to its VM options and `--developer-mode --debug` to its program arguments. |
| Gradle fails with "Unsupported class file major version" or won't sync | The Gradle JVM is Java 25 or 26. Set it to 17 or 21 (setup step 2), or set `JAVA_HOME` to one of them. |
| "package lombok... does not exist", in `ExamplePlugin.java` or `ExampleConfig.java` | The project was started from RuneLite's example-plugin template, whose `com\example` files need Lombok, which this kit leaves out. Delete `src\main\java\com\example` and `src\test\java\com\example`; this kit's own files are in `com\osmm\capture`. |
| Gradle can't find `net.runelite:client` | Check the internet connection (`repo.runelite.net`), then sync again. |
| The client won't connect after an update | Run with `--refresh-dependencies` (setup step 5). If that doesn't help, RuneLite hasn't updated yet. |
| No Jagex auth panel on the sidebar | The client isn't in developer mode: start it with the `run` task, not the normal RuneLite launcher. |
| The plugin isn't in the list | Search for **OSMM** in the plugin list (wrench icon). It's only in the client started by `run`, never in your normal RuneLite. Check the console for an error mentioning `osmm`. |
| No red dot on the sidebar | The plugin is off (wrench icon, search for **OSMM**), or the sidebar is hidden or needs scrolling. |
| **Start** (or `::rec start`) says "Log in first." | It only records while logged in to the game. |
| "Couldn't write the capture file" | The folder above couldn't be written; check disk space and that the folder isn't read-only. |
| A recording starts on its own | **Start on login** is on: it starts one each time you log in or hop. Stop it in the panel (or `::rec stop`); it won't start again until you next log in or hop. Or **Start in combat** is on and something attacking you hit you, or you hit something. Self-inflicted damage (a rock cake, a divine potion, an overload) counts only while a player or a monster with a combat level is facing you (or did in the last 3 seconds), or you're facing one: following or trading a player while eating a rock cake can do it. |
| No recording indicator | **Show recording indicator** is off, or nothing is recording or waiting. If it was dragged out of the way, hold **Alt** to see the overlays' outlines and right-click it to put it back. |
| A fight didn't start a recording | **Start in combat** is off; or a recording was already running (it's left alone); or you pressed **Stop** earlier in that fight (wait until it's over); or no hitsplat landed, as when splashing on someone who doesn't fight back; or you were hit by something that wasn't facing you (and you weren't facing anything to attack), which counts as self-inflicted. |
| A recording started in combat stopped too soon | Raise **Stop after (seconds) out of combat**, or set it to 0 and stop it yourself. It counts from the last hit that counts (by you on something else, or on you from something attacking you), or the last moment you faced something to attack. |
| The start of the fight is missing | Raise **Seconds kept from before combat**. In a crowd with Who to record NEARBY, the memory bound can cut it short. |
| Notes typed with the panel or `::rec note` are missing | Notes only go in while recording. For a finished recording, add them in the panel's editor and **Save**. |
| No `snd` or `asnd` lines in the file | Sound effects or Area sounds volume is 0, you were too far away, or the plugin's Sounds option is off. |
| No `mv` lines | Per-cycle motion is off, or nobody tracked was moving. |
| Someone you fought is missing | With FIGHT, only people who faced you (or you them) are recorded. Use NEARBY. |

The client's own log is `%USERPROFILE%\.runelite\logs\client.log`.

## The format

Each file is JSON lines: one record per line, with the tick (`t`, `tc`) and the 20 ms client cycle (`c`) on every
line. Records include `actor` (looks), `tick` (everyone's tile, animation, health and overheads each tick), `mv`
(drawn position 50 times a second), `anim`, `spot`, `proj`, `hit`, `snd`, `asnd` and your `note`s. Lines that came
with a server tick share its `c`. A recording started in combat begins with its lead-in (the header's `leadIn`
says how many ticks). Edited labels and notes are in a `.notes.json` beside the file. Full details:
[FORMAT.md](FORMAT.md).

## Files here

| File | What it is |
|---|---|
| `build.gradle`, `settings.gradle` | The Gradle build, with the `run` task. |
| `gradle/wrapper/gradle-wrapper.properties` | Tells IntelliJ which Gradle to fetch (8.10). |
| `runelite-plugin.properties` | The plugin's name and main class. |
| `src/main/java/com/osmm/capture/` | The plugin: `OsmmCapturePlugin` (events to records, "Start on login"), `CombatWatch` ("Start in combat": when a fight starts and ends), `LeadIn` (the seconds kept from before combat), `RecordingOverlay` (the recording indicator), `OsmmCapturePanel` (the side panel), `CaptureFiles` (reads the recordings' headers and notes, writes the notes sidecars), `OsmmCaptureConfig` (options), `CaptureWriter` (writes on a background thread), `ActorState` (what was last written per actor). |
| `src/test/java/com/osmm/capture/OsmmCapturePluginTest.java` | Starts RuneLite with the plugin loaded (what `run` runs). |
| `src/test/java/com/osmm/capture/AutoStartTest.java`, `CombatWatchTest.java`, `LeadInTest.java`, `CaptureFilesTest.java` | Plain tests, no client or login: when "Start on login" and "Start in combat" start and stop a recording, the lead-in, and reading recordings and sidecars. `gradle test` runs them. |
| `FORMAT.md` | The file format. |
| `LICENSE` | BSD 2-Clause, as RuneLite's own. |
