# OSMM Recording

A RuneLite plugin that records fights to a file, tick by tick: who animates with what, where everyone stands, gear
and switches, projectiles, graphics, hitsplats and sounds. Old School Movie Maker imports these files to play the
fight back.

It only reads what your client already knows and writes it to your computer. It sends nothing anywhere, never clicks
or presses keys, and shows nothing in game but a small recording indicator and its own chat lines about recordings
(started, noted, stopped, or why not).

Recordings go to `%USERPROFILE%\.runelite\plugin-data\osmm-capture\recordings\`. Ones saved by an earlier version
(one folder up) are moved there when the plugin starts.

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
| Anonymise player names | on | Writes each player, you included, as a code such as `Player 7F3A9C` instead of their name, in game messages too. The same player gets the same code ([Player names](#player-names)). |
| Start on login | off | Starts a recording when you log in or hop, not on teleports. Not again after you stop one until your next login. |
| Start in combat | off | Starts a recording when something attacking you hits you, or you hit something. Self-damage (rock cakes, divine potions), heals and poison don't count. |
| Stop after (seconds) out of combat | 30 | Stops a combat recording after this long without fighting; 0 never stops it. |
| Seconds kept from before combat | 6 | A combat recording starts this long before the first hit, so the opening attack is in it. |
| Who to record | FIGHT | FIGHT: you and whoever you fight. NEARBY: everyone within the radius. |
| Radius (tiles) | 15 | For NEARBY, and how far away graphics and projectiles are recorded. |
| Per-cycle motion | on | Positions and animation frames 50 times a second. |
| Sounds, Projectiles, Game messages | on | Whether each is recorded. |
| Your stats and spec | on | Your combat stats, special energy, spec and run toggles. |

The file format is in [FORMAT.md](FORMAT.md).

## Player names

**Anonymise player names** is on by default. Every player, you included, is written as a code such as
`Player 7F3A9C` instead of their name, and the names of players in the recording or in view are replaced the same way
inside game messages ("You have defeated …"). Old School Movie Maker shows the codes as the players' names.

- **The same player gets the same code**, whatever the capitals or spaces in their name, across recordings and
  restarts, so you can follow someone from one recording to the next. Two exceptions: if two names come to the same
  code in one recording, the later one gets a longer code; and if the key file can't be read or saved, the chat says
  so and that session's codes won't match other recordings'.
- **The codes come from a secret key** in `anonymise.key`, made the first time it's needed. It's kept in
  `osmm-capture\`, outside the `recordings\` folder, so sharing that folder doesn't share the key. It never leaves
  your computer and isn't kept in RuneLite's settings. Don't share it: with it, a name can be matched to its code.
  Deleting it gives everyone new codes after the next restart.
- **What stays:** NPC names, your labels and notes as you typed them, a game message's name of a player who was never
  in the recording or in view, and everyone's gear, positions and the times. A recording isn't fully anonymous, so
  still share it with care.
- **Turned off,** recordings hold real names. Each file's header says which (`"anonymised": true` or `false`), and
  turning it on or off takes effect from the next recording.

## Licence

BSD 2-Clause: see [LICENSE](LICENSE).
