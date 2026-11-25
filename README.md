# OSMM capture

A RuneLite plugin that records fights to a file, tick by tick: who animates with what, where everyone stands, gear
and switches, projectiles, graphics, hitsplats and sounds. Old School Movie Maker imports these files to play the
fight back.

It only reads what your client already knows and writes it to your computer. It sends nothing anywhere, never clicks
or presses keys, and shows nothing in game but a small recording indicator.

Recordings go to `%USERPROFILE%\.runelite\plugin-data\osmm-capture\`.

## Running it

It isn't on the Plugin Hub, so start it in RuneLite's developer client from this folder:

```powershell
.\gradlew.bat run
```

Log in with your Jagex account as RuneLite's wiki describes:
[Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts). The build needs Java 11, 17
or 21. After a game update, if the client won't connect, run `.\gradlew.bat run --refresh-dependencies`.

For sounds to be recorded, set **Sound effects** and **Area sounds** above 0 in the game's audio settings.

## Using it

Click the red dot on RuneLite's sidebar to open the panel.

- **Start** and **Stop** a recording. A **Label** is optional and goes in the file.
- While recording, type a **Note** and press **Add note** (or Enter) to mark a moment, e.g. `barrage while walking`.
- **Recordings** lists your captures, newest first. Click one to edit its label and notes, or delete it; **Back**
  returns to the list. **Open folder** shows where they're saved. Edits are saved beside the recording in
  `<name>.notes.json`; the recording itself isn't changed.

Chat commands do the same: `::rec start <label>`, `::rec note <text>`, `::rec stop`, and `::rec` for the status.

A recording also ends when you log out or hop worlds.

## Options

In RuneLite's settings (the wrench), search for **OSMM**.

| Option | Default | What it does |
|---|---|---|
| Show recording indicator | on | A small box in game while recording or waiting for combat. Alt-drag to move it. |
| Start on login | off | Starts a recording when you log in or hop, not on teleports. Not again after you stop one until your next login. |
| Start in combat | off | Starts a recording when something attacking you hits you, or you hit something. Self-damage (rock cakes, divine potions), heals and poison don't count. |
| Stop after (seconds) out of combat | 30 | Stops a combat recording after this long without fighting; 0 never stops it. |
| Seconds kept from before combat | 6 | A combat recording starts this long before the first hit, so the opening attack is in it. |
| Who to record | FIGHT | FIGHT: you and whoever you fight. NEARBY: everyone within the radius. |
| Radius (tiles) | 15 | For NEARBY, and how far away graphics and projectiles are recorded. |
| Per-cycle motion | on | Positions and animation frames 50 times a second. |
| Sounds, Projectiles, Game messages | on | Whether each is recorded. |
| Your stats and spec | on | Your combat stats, special energy, spec and run toggles. |

Recordings hold other players' names and gear: keep them to yourself. The file format is in
[FORMAT.md](FORMAT.md).

## Licence

BSD 2-Clause: see [LICENSE](LICENSE).
