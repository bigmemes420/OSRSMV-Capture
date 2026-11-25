package com.osmm.capture;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.Actor;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameState;
import net.runelite.api.GraphicsObject;
import net.runelite.api.HeadIcon;
import net.runelite.api.Hitsplat;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.Projectile;
import net.runelite.api.Skill;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.AreaSoundEffectPlayed;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.GraphicsObjectCreated;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.NpcChanged;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.PlayerDespawned;
import net.runelite.api.events.SoundEffectPlayed;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.RuneLiteProperties;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Filepath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records fights to a JSON-lines file (see FORMAT.md). It reads game state only: it plays nothing, adds no menu
 * entries, sends nothing to the game or anywhere else, and generates no input. Its only in-game output is a chat line
 * when a recording starts, is noted or stops, and (unless turned off) a small box saying it's recording or waiting for
 * combat. Its side panel (outside the game view) starts and stops recordings, adds notes, and lists the recordings
 * with their notes for editing. Recordings start by hand, on login ("Start on login") or in combat ("Start in
 * combat", with a lead-in from before the first hit: see LeadIn). Unless "Anonymise player names" is off, players'
 * names are written as codes, in game messages too (see PlayerNames and NameKey).
 */
@PluginDescriptor(
	name = "OSMM Recording",
	description = "Records fights to a JSON-lines file for Old School Movie Maker",
	tags = {"record", "capture", "developer", "osmm"},
	internalName = "osmm-capture"   // -> .runelite/plugin-data/osmm-capture (recordings in its recordings/)
)
public class OsmmCapturePlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(OsmmCapturePlugin.class);

	static final String FORMAT = "osmm-capture";
	static final int VERSION = 1;
	private static final int STICKY_TICKS = 50;
	// Special attack energy (0-1000), special attack on, run on. (Compile-time constants: javac writes the numbers in,
	// so the gameval class isn't needed at run time.)
	private static final Set<Integer> VARPS = Set.of(VarPlayerID.SA_ENERGY, VarPlayerID.SA_ATTACK, VarPlayerID.OPTION_RUN);
	private static final Set<Skill> SKILLS = EnumSet.of(Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE,
		Skill.RANGED, Skill.MAGIC, Skill.HITPOINTS, Skill.PRAYER);

	@Inject
	private Client client;

	@Inject
	private OsmmCaptureConfig config;

	@Inject
	private Gson gson;   // RuneLiteModule binds RuneLiteAPI.GSON

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private RecordingOverlay overlay;

	private volatile CaptureWriter writer;   // null when not recording
	private String fileName;
	private String label;
	private int tick;       // game ticks since the recording (or the lead-in) began
	private int tickBase;   // subtracted from tick for the file's t: the lead-in's start (0 without one)
	private boolean byCombat;   // the recording was started by "Start in combat"
	private final List<CaptureFiles.Note> notes = new ArrayList<>();   // this recording's, for the panel
	private LoginWatch loginWatch = new LoginWatch();   // (new each start: RuneLite keeps the plugin object)
	private CombatWatch combatWatch = new CombatWatch();   // "Start in combat" (new each start, like loginWatch)
	private volatile LeadIn leadIn;   // while armed for "Start in combat": the records, in memory instead of a file
	private final Set<Actor> facingMe = Collections.newSetFromMap(new IdentityHashMap<>());   // others facing you
	private volatile Status status = Status.IDLE;   // what the panel and the overlay show, set on the client thread
	private volatile boolean indicator;   // "Show recording indicator", for the overlay (read each frame)
	private volatile boolean clientClosing;   // no more starts once the client is closing
	private volatile boolean anonymise;   // "Anonymise player names", for the next recording (or lead-in)
	private PlayerNames names = PlayerNames.off();   // this recording's (and its lead-in's): set as it begins

	// "Anonymise player names": the key, loaded (or made) on the io thread; null until then. A start asked for before
	// it's loaded waits for it (pendingStart), so no recording ever writes a name for want of the key.
	private volatile byte[] nameKey;
	private volatile boolean nameKeyTemporary;   // the key file couldn't be read or written: this session's own key
	private final AtomicBoolean nameKeyLoading = new AtomicBoolean();
	private volatile Object nameKeyLoad;   // this startUp's load (one from an earlier run is ignored)
	private volatile PendingStart pendingStart;

	private Filepath dir;          // the plugin's folder: the key (NameKey) is kept here
	private Filepath recordings;   // its recordings folder (RecordingsFolder): the captures and their notes
	private ExecutorService io;   // file work for the panel: off the EDT and the client thread
	private volatile OsmmCapturePanel panel;
	private NavigationButton navButton;
	private boolean startWritten;   // your stats and the varps, once you're in the game
	private final Map<Actor, ActorState> states = new IdentityHashMap<>();
	private final Map<Actor, Integer> lastFought = new IdentityHashMap<>();
	private final List<Actor> tracked = new ArrayList<>();
	private final Set<Actor> trackedSet = Collections.newSetFromMap(new IdentityHashMap<>());
	private Set<Projectile> seenProjectiles = Collections.newSetFromMap(new IdentityHashMap<>());

	@Provides
	OsmmCaptureConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(OsmmCaptureConfig.class);
	}

	@Override
	protected void startUp() throws IOException   // (on the EDT)
	{
		dir = getPluginDirectory();
		recordings = RecordingsFolder.in(dir);
		io = Executors.newSingleThreadExecutor(r ->
		{
			Thread t = new Thread(r, "osmm-capture-files");
			t.setDaemon(true);
			return t;
		});
		nameKey = null;
		nameKeyTemporary = false;
		nameKeyLoading.set(false);
		nameKeyLoad = new Object();
		pendingStart = null;
		anonymise = config.anonymise();
		if (anonymise)
		{
			requestNameKey();   // (first on the io thread, before the panel's first listing)
		}
		moveOldRecordings();   // (on the io thread too, also before the panel's first listing there)
		status = Status.IDLE;
		loginWatch = new LoginWatch();
		combatWatch = new CombatWatch();
		leadIn = null;
		facingMe.clear();
		indicator = config.showIndicator();
		clientClosing = false;
		overlayManager.add(overlay);
		panel = new OsmmCapturePanel(this, new CaptureFiles(recordings, gson), io);
		navButton = NavigationButton.builder()
			.tooltip("OSMM Recording")
			.icon(recordIcon())
			.priority(10)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		// Turned on while already logged in: that counts as logging in, for "Start on login".
		clientThread.invokeLater(() ->
		{
			if (loginWatch.onState(client.getGameState(), config.autoStart(), writer != null))
			{
				start("auto");
			}
		});
	}

	@Override
	protected void shutDown()   // (on the EDT)
	{
		closeQuietly("plugin stopped");
		overlayManager.remove(overlay);
		clientToolbar.removeNavigation(navButton);
		OsmmCapturePanel p = panel;
		panel = null;
		if (p != null)
		{
			p.dispose();
		}
		io.shutdownNow();
		nameKeyLoad = null;
	}

	// Closing the client doesn't stop plugins, so without this a recording ran on to the window closing with no end.
	// The client waits (a while) for the file to be closed.
	@Subscribe
	public void onClientShutdown(ClientShutdown e)
	{
		clientClosing = true;
		CaptureWriter w = closeQuietly("client closed");
		if (w != null)
		{
			e.waitFor(w.whenClosed());
		}
	}

	// May not be on the client thread: touch nothing of the client here. Doesn't wait for the file to close.
	private CaptureWriter closeQuietly(String why)
	{
		CaptureWriter w = writer;
		writer = null;
		leadIn = null;
		pendingStart = null;
		status = Status.IDLE;
		if (w != null)
		{
			w.write("{\"e\":\"end\",\"why\":\"" + why + "\"}");
			w.close();
		}
		return w;
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (OsmmCaptureConfig.GROUP.equals(e.getGroup()))
		{
			indicator = config.showIndicator();
			anonymise = config.anonymise();
			if (anonymise)
			{
				requestNameKey();
			}
		}
	}

	/** For the overlay: "Show recording indicator" is on. */
	boolean indicatorShown()
	{
		return indicator;
	}

	// ---- start / stop -------------------------------------------------------------------------------

	@Subscribe
	public void onCommandExecuted(CommandExecuted e)
	{
		if (!"rec".equalsIgnoreCase(e.getCommand()))
		{
			return;
		}
		String[] a = e.getArguments();
		String sub = a.length > 0 ? a[0].toLowerCase() : "";
		String rest = a.length > 1 ? String.join(" ", Arrays.copyOfRange(a, 1, a.length)) : "";
		switch (sub)
		{
			case "start":
				start(rest);
				break;
			case "stop":
				if (writer == null)
				{
					say("Not recording.");
				}
				userStop("command");
				break;
			case "note":
				if (writer == null)
				{
					say("Not recording. ::rec start [label]");
				}
				else
				{
					note(rest);
				}
				break;
			default:
				say(writer != null ? "Recording to " + fileName : "Not recording. ::rec start [label]");
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		GameState s = e.getGameState();
		boolean autoStart = loginWatch.onState(s, config.autoStart(), writer != null);
		if (s == GameState.LOGGED_IN)
		{
			if (autoStart)
			{
				start("auto");
			}
			else if (capturing())
			{
				// (a reconnect can come without a LOADING: describe the actors again after every scene line)
				sceneReset();
				emitScene();
			}
		}
		else if (s == GameState.LOGIN_SCREEN || s == GameState.HOPPING)
		{
			stop(s.name());
			leadIn = null;
			pendingStart = null;
			combatWatch = new CombatWatch();
			facingMe.clear();
			publish();
		}
		else if (s == GameState.LOADING && capturing())
		{
			// local coordinates move with the new scene base: describe the actors again afterwards
			sceneReset();
		}
	}

	private void sceneReset()
	{
		states.clear();
		tracked.clear();
		trackedSet.clear();
		seenProjectiles.clear();
	}

	/** Recording, or armed for "Start in combat" (recording into the lead-in). */
	private boolean capturing()
	{
		return writer != null || leadIn != null;
	}

	/**
	 * Back to the start of a recording (or a lead-in): tick 0, nobody described yet, and names coded afresh, or not
	 * (anonymised: "Anonymise player names" is on and the key is loaded).
	 */
	private void resetRecordState(boolean anonymised)
	{
		names = anonymised ? new PlayerNames(nameKey) : PlayerNames.off();
		tick = 0;
		tickBase = 0;
		startWritten = false;
		states.clear();
		lastFought.clear();
		tracked.clear();
		trackedSet.clear();
		seenProjectiles.clear();
	}

	/** Starts a recording (client thread). Returns null, or why it didn't start. */
	private String start(String label)
	{
		return start(label, false);
	}

	/**
	 * Starts a recording; combat: "Start in combat" started it, and it begins with the lead-in if there is one. Else
	 * any lead-in is dropped and the recording starts afresh.
	 */
	private String start(String label, boolean combat)
	{
		if (writer != null)
		{
			say("Already recording to " + fileName);
			return "Already recording.";
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			say("Log in first.");
			return "Log in first.";
		}
		boolean anonymised = anonymise;   // (read once: the option can change on another thread)
		if (anonymised && !nameKeyReady())
		{
			// (the key is read from disk on the io thread: the start waits for it, a moment at most)
			pendingStart = new PendingStart(label, combat);
			return null;
		}
		pendingStart = null;
		LeadIn buffered = leadIn;
		leadIn = null;
		// (a lead-in kept with names anonymised, or not, only goes in a recording that has them the same)
		LeadIn.Drained lead = combat && buffered != null && names.enabled() == anonymised ? buffered.drain(gson) : null;
		int leadTicks = 0;
		if (lead == null)
		{
			resetRecordState(anonymised);
		}
		else
		{
			// carry on from the lead-in: the same ticks and actors, the file's t counted from the lead-in's start
			tickBase = lead.offset;
			leadTicks = tick - tickBase;
		}
		// (the writer makes the folder and opens the file on its own thread; a failure comes back to writerFailed)
		fileName = "capture_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".jsonl";
		CaptureWriter out = new CaptureWriter(recordings, fileName,
			(w, ex) -> clientThread.invoke(() -> writerFailed(w, ex)));
		writer = out;
		this.label = label;
		byCombat = combat;
		notes.clear();

		JsonObject h = rec("hdr");
		if (lead != null)
		{
			h.addProperty("t", 0);
			h.addProperty("tc", lead.tc);
			h.addProperty("c", lead.c);
		}
		h.addProperty("format", FORMAT);
		h.addProperty("version", VERSION);
		h.addProperty("label", label);
		h.addProperty("revision", client.getRevision());
		h.addProperty("runelite", RuneLiteProperties.getVersion());
		h.addProperty("world", client.getWorld());
		h.addProperty("utc", Instant.now().minusMillis((long) leadTicks * Constants.GAME_TICK_LENGTH).toString());
		h.addProperty("local", ref(client.getLocalPlayer()));
		h.addProperty("leadIn", leadTicks);
		h.addProperty("anonymised", names.enabled());
		JsonObject cfg = new JsonObject();
		cfg.addProperty("scope", config.scope().name());
		cfg.addProperty("radius", config.radius());
		cfg.addProperty("motion", config.motion());
		cfg.addProperty("sounds", config.sounds());
		cfg.addProperty("projectiles", config.projectiles());
		cfg.addProperty("gameMessages", config.gameMessages());
		cfg.addProperty("localStats", config.localStats());
		h.add("config", cfg);
		emit(h);
		if (lead == null)
		{
			emitScene();
		}
		else
		{
			for (String line : lead.lines)
			{
				out.write(line);
			}
		}
		say((combat ? "In combat: recording to " : "Recording to ") + fileName);
		if (names.enabled() && nameKeyTemporary)
		{
			say("Couldn't read or save the key for player codes, so this recording's codes won't match other recordings'.");
		}
		publish();
		return null;
	}

	/** A start waiting for the key (see {@link #startPending}). */
	private static final class PendingStart
	{
		final String label;
		final boolean combat;

		PendingStart(String label, boolean combat)
		{
			this.label = label;
			this.combat = combat;
		}
	}

	// ---- the recordings folder ------------------------------------------------------------------------

	/**
	 * Moves recordings from the plugin's folder (where they used to be kept, beside the key) into the recordings
	 * folder, on the io thread. Queued there before the panel's first listing, so the panel lists them where they now
	 * are; it lists again if anything moved, all the same.
	 */
	private void moveOldRecordings()
	{
		Filepath from = dir;
		Filepath to = recordings;
		try
		{
			io.execute(() ->
			{
				int moved = RecordingsFolder.moveOld(RecordingsFolder.store(from, to), System.currentTimeMillis(),
					name ->
					{
						CaptureWriter w = writer;
						return w != null && name.equals(w.name());
					});
				OsmmCapturePanel p = panel;
				if (moved > 0 && p != null)
				{
					SwingUtilities.invokeLater(p::refreshRecordings);
				}
			});
		}
		catch (RejectedExecutionException ignored)
		{
			// the plugin is stopping
		}
	}

	// ---- "Anonymise player names": the key ------------------------------------------------------------

	/** The key is loaded (client thread). Else it's asked for, and false. */
	private boolean nameKeyReady()
	{
		if (nameKey != null)
		{
			return true;
		}
		requestNameKey();
		return false;
	}

	/** Loads the key (or makes and saves one) on the io thread, once; then a start that waited for it goes ahead. */
	private void requestNameKey()
	{
		if (nameKey != null || !nameKeyLoading.compareAndSet(false, true))
		{
			return;
		}
		Object load = nameKeyLoad;
		Filepath folder = dir;
		try
		{
			io.execute(() -> loadNameKey(load, folder));
		}
		catch (RejectedExecutionException e)
		{
			nameKeyLoading.set(false);   // (the plugin is stopping)
		}
	}

	// io thread: reads the key, or makes and saves one.
	private void loadNameKey(Object load, Filepath folder)
	{
		byte[] key;
		boolean temporary = false;
		try
		{
			key = NameKey.loadOrCreate(NameKey.in(folder), new SecureRandom());
		}
		catch (IOException | RuntimeException e)
		{
			// (no key file: a key for this session only, so the recordings are still anonymised)
			log.debug("osmm-capture: can't read or save {}: {}", NameKey.FILE, e.toString());
			key = new byte[NameKey.BYTES];
			new SecureRandom().nextBytes(key);
			temporary = true;
		}
		if (load != nameKeyLoad)
		{
			return;   // the plugin was stopped (and maybe started again) meanwhile
		}
		nameKeyTemporary = temporary;
		nameKey = key;
		clientThread.invoke(this::startPending);
	}

	// Client thread: the key is loaded, so a start that waited for it goes ahead (if it still can).
	private void startPending()
	{
		PendingStart p = pendingStart;
		pendingStart = null;
		if (p != null && writer == null && !clientClosing && client.getGameState() == GameState.LOGGED_IN)
		{
			start(p.label, p.combat);
		}
	}

	private void stop(String why)
	{
		CaptureWriter w = writer;
		if (w == null)
		{
			return;
		}
		JsonObject o = rec("end");
		o.addProperty("why", why);
		emit(o);
		writer = null;
		w.close();
		say("Capture saved: " + fileName);
		publish();
		// the panel reads the finished file again once its last lines are on disk
		w.whenClosed().thenRun(() ->
		{
			OsmmCapturePanel p = panel;
			if (p != null)
			{
				SwingUtilities.invokeLater(p::refreshRecordings);
			}
		});
	}

	// Client thread: the file couldn't be opened or written, so the recording ends.
	private void writerFailed(CaptureWriter w, Exception ex)
	{
		if (writer != w)
		{
			return;
		}
		writer = null;
		combatWatch.userStopped();   // (so "Start in combat" doesn't try again at once, every tick)
		String why = "Couldn't write the capture file: " + ex.getMessage();
		say(why);
		publish();
		OsmmCapturePanel p = panel;
		if (p != null)
		{
			SwingUtilities.invokeLater(() -> p.showMessage(why));
		}
	}

	/**
	 * You stopped it (a command or the panel): "Start on login" then waits for the next real login, and "Start in
	 * combat" for the next fight.
	 */
	private void userStop(String why)
	{
		pendingStart = null;
		if (writer != null)
		{
			loginWatch.userStopped();
			combatWatch.userStopped();
		}
		stop(why);
	}

	private void note(String text)
	{
		JsonObject o = rec("note");
		o.addProperty("text", text);
		emit(o);
		notes.add(new CaptureFiles.Note(tick - tickBase, text));
		say("Noted.");
		publish();
	}

	// ---- the side panel's view and its buttons -------------------------------------------------------

	/** The recording in progress, as the panel shows it. */
	static final class Status
	{
		static final Status IDLE = new Status(null, "", 0, Collections.emptyList(), false);
		static final Status WAITING = new Status(null, "", 0, Collections.emptyList(), true);

		final String file;   // null when not recording
		final String label;
		final int ticks;
		final List<CaptureFiles.Note> notes;
		final boolean waiting;   // not recording, and "Start in combat" will start one in the next fight

		Status(String file, String label, int ticks, List<CaptureFiles.Note> notes, boolean waiting)
		{
			this.file = file;
			this.label = label;
			this.ticks = ticks;
			this.notes = notes;
			this.waiting = waiting;
		}
	}

	Status status()
	{
		return status;
	}

	// Client thread: hands the panel a copy, and tells it at once (it also polls each second, for the ticks).
	private void publish()
	{
		status = writer == null ? (waiting() ? Status.WAITING : Status.IDLE)
			: new Status(fileName, label, tick - tickBase, Collections.unmodifiableList(new ArrayList<>(notes)), false);
		OsmmCapturePanel p = panel;
		if (p != null)
		{
			SwingUtilities.invokeLater(p::statusChanged);
		}
	}

	// Client thread: whether "Start in combat" will start a recording in the next fight.
	private boolean waiting()
	{
		return writer == null && config.combatStart() && client.getGameState() == GameState.LOGGED_IN
			&& !combatWatch.heldOff();
	}

	// From the panel (EDT): the work is done on the client thread, and a failure is passed back.
	void panelStart(String label)
	{
		clientThread.invoke(() ->
		{
			String why = start(label);
			OsmmCapturePanel p = panel;
			if (why != null && p != null)
			{
				SwingUtilities.invokeLater(() -> p.showMessage(why));
			}
		});
	}

	void panelStop()
	{
		clientThread.invoke(() -> userStop("panel"));
	}

	void panelNote(String text)
	{
		clientThread.invoke(() ->
		{
			if (writer != null)
			{
				note(text);
			}
		});
	}

	/** The sidebar icon: a red record dot, drawn here so the plugin has no image files. */
	private static BufferedImage recordIcon()
	{
		BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(110, 20, 20));
		g.fillOval(1, 1, 14, 14);
		g.setColor(new Color(220, 40, 40));
		g.fillOval(2, 2, 12, 12);
		g.dispose();
		return img;
	}

	// ---- "Start on login" -----------------------------------------------------------------------------

	/**
	 * Whether a game state change should start a recording on its own. Only a real login does: a LOGGED_IN after the
	 * login screen, logging in, a lost connection or a hop, or the first one seen since the plugin started (previous
	 * null). A map load (a teleport, a new region: LOGGED_IN, LOADING, LOGGED_IN) doesn't. Never while recording, and
	 * not after you stopped a recording until the session ends (the login screen or a hop).
	 *
	 * @param previous the last state before this one other than LOADING; null when none has been seen
	 */
	static boolean shouldAutoStart(GameState previous, GameState now, boolean option, boolean recording, boolean userStopped)
	{
		if (now != GameState.LOGGED_IN || !option || recording || userStopped)
		{
			return false;
		}
		return previous == null || previous == GameState.LOGIN_SCREEN || previous == GameState.LOGGING_IN
			|| previous == GameState.CONNECTION_LOST || previous == GameState.HOPPING;
	}

	/** The state kept for shouldAutoStart, fed every game state change (on the client thread). */
	static final class LoginWatch
	{
		private GameState previous;   // the last state other than LOADING; null before the first
		private boolean userStopped;  // you stopped a recording this session

		/** Call with each new state before acting on it. True: start a recording. */
		boolean onState(GameState now, boolean option, boolean recording)
		{
			boolean start = shouldAutoStart(previous, now, option, recording, userStopped);
			if (now != GameState.LOADING)
			{
				previous = now;
			}
			if (now == GameState.LOGIN_SCREEN || now == GameState.HOPPING)
			{
				userStopped = false;
			}
			return start;
		}

		void userStopped()
		{
			userStopped = true;
		}
	}

	private void emitScene()
	{
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return;
		}
		JsonObject o = rec("scene");
		o.addProperty("baseX", wv.getBaseX());
		o.addProperty("baseY", wv.getBaseY());
		o.addProperty("plane", wv.getPlane());
		o.addProperty("instance", wv.isInstance());
		emit(o);
	}

	// ---- per tick / per cycle -----------------------------------------------------------------------

	@Subscribe
	public void onGameTick(GameTick e)
	{
		combatTick();
		if (!capturing())
		{
			return;
		}
		tick++;
		if (writer != null)
		{
			status = new Status(fileName, label, tick - tickBase, status.notes, false);   // (the panel's clock)
		}
		writeStartValues();
		updateTracked();
		JsonArray arr = new JsonArray();
		for (Actor a : tracked)
		{
			ActorState s = stateOf(a);
			WorldPoint wp = a.getWorldLocation();
			JsonObject o = new JsonObject();
			o.addProperty("r", ref(a));
			if (wp != null)
			{
				o.addProperty("x", wp.getX());
				o.addProperty("y", wp.getY());
				o.addProperty("z", wp.getPlane());
			}
			o.addProperty("a", a.getAnimation());
			o.addProperty("p", a.getPoseAnimation());
			o.addProperty("o", a.getOrientation());
			o.addProperty("i", ref(a.getInteracting()));
			o.addProperty("hr", a.getHealthRatio());
			o.addProperty("hs", a.getHealthScale());
			if (a instanceof Player)
			{
				Player p = (Player) a;
				HeadIcon h = p.getOverheadIcon();
				o.addProperty("oh", h == null ? null : h.name());
				o.addProperty("sk", p.getSkullIcon());
				checkEquipment(p, s);
			}
			else
			{
				NPC n = (NPC) a;
				o.addProperty("id", n.getId());
				int[] arch = n.getOverheadArchiveIds();
				short[] spr = n.getOverheadSpriteIds();
				if (!Arrays.equals(arch, s.npcOverheadArchives) || !Arrays.equals(spr, s.npcOverheadSprites))
				{
					s.npcOverheadArchives = arch == null ? null : arch.clone();
					s.npcOverheadSprites = spr == null ? null : spr.clone();
					o.add("ohA", ints(arch));
					o.add("ohS", shorts(spr));
				}
			}
			arr.add(o);
			checkSpotAnims(a, s);   // backup for GraphicChanged
		}
		JsonObject t = rec("tick");
		t.add("a", arr);
		emit(t);
	}

	// ---- "Start in combat" ----------------------------------------------------------------------------

	/**
	 * Each game tick, before it's recorded: starts or stops a recording for combat, then arms or disarms the lead-in
	 * (armed while "Start in combat" is on, nothing's recording and some seconds are to be kept).
	 */
	private void combatTick()
	{
		if (clientClosing)
		{
			return;
		}
		Player me = client.getLocalPlayer();
		CombatWatch.Recording now = writer == null ? CombatWatch.Recording.NONE
			: byCombat ? CombatWatch.Recording.COMBAT : CombatWatch.Recording.OTHER;
		CombatWatch.Action action = combatWatch.onTick(me != null && anyoneOnMe(me),
			me != null && attackable(me.getInteracting(), me),
			config.combatStart(), CombatWatch.stopTicks(config.combatStopSeconds()), now);
		if (action == CombatWatch.Action.STOP)
		{
			stop("out of combat");
		}
		else if (action == CombatWatch.Action.START)
		{
			start("combat", true);
		}

		int keep = CombatWatch.leadTicks(config.combatLeadSeconds());
		boolean anonymised = anonymise;
		boolean armed = config.combatStart() && keep > 0 && writer == null && client.getGameState() == GameState.LOGGED_IN
			&& (!anonymised || nameKeyReady());   // (no lead-in, so no names held, while the key loads)
		LeadIn b = leadIn;
		if (b != null && (!armed || b.overflowed() || names.enabled() != anonymised))
		{
			// (overflowed: one tick was over the memory bound. The option changed: its lines are coded as the next
			// recording's won't be. Start the lead-in again.)
			leadIn = null;
			b = null;
		}
		if (armed && b == null)
		{
			leadIn = new LeadIn(keep, LeadIn.MAX_BYTES);
			resetRecordState(anonymised);
			emitScene();
		}
		else if (b != null)
		{
			b.setMaxTicks(keep);
		}

		boolean waiting = waiting();
		if (writer == null && status.waiting != waiting)
		{
			publish();
		}
	}

	/** Another player, or an NPC with a combat level. */
	private static boolean attackable(Actor a, Player me)
	{
		if (a == null || a == me)
		{
			return false;
		}
		return a instanceof Player || (a instanceof NPC && a.getCombatLevel() > 0);
	}

	/** Whether an actor that's attackable faces you. */
	private boolean anyoneOnMe(Player me)
	{
		for (Actor a : facingMe)
		{
			if (a.getInteracting() == me && attackable(a, me))
			{
				return true;
			}
		}
		return false;
	}

	@Subscribe
	public void onClientTick(ClientTick e)
	{
		if (!capturing())
		{
			return;
		}
		if (config.motion())
		{
			for (Actor a : tracked)
			{
				motion(a);
			}
		}
		if (config.projectiles())
		{
			scanProjectiles();
		}
	}

	private void motion(Actor a)
	{
		ActorState s = stateOf(a);
		LocalPoint lp = a.getLocalLocation();
		if (lp == null)
		{
			return;
		}
		int lx = lp.getX();
		int ly = lp.getY();
		int an = a.getAnimation();
		int af = a.getAnimationFrame();
		int po = a.getPoseAnimation();
		int pf = a.getPoseAnimationFrame();
		int co = a.getCurrentOrientation();
		if (lx == s.lx && ly == s.ly && an == s.anim && af == s.animFrame && po == s.pose && pf == s.poseFrame && co == s.curOri)
		{
			return;
		}
		s.lx = lx;
		s.ly = ly;
		s.anim = an;
		s.animFrame = af;
		s.pose = po;
		s.poseFrame = pf;
		s.curOri = co;
		JsonObject o = rec("mv");
		o.addProperty("r", ref(a));
		o.addProperty("lx", lx);
		o.addProperty("ly", ly);
		o.addProperty("a", an);
		o.addProperty("af", af);
		o.addProperty("p", po);
		o.addProperty("pf", pf);
		o.addProperty("co", co);
		emit(o);
	}

	private void scanProjectiles()
	{
		Set<Projectile> now = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Projectile p : client.getProjectiles())
		{
			now.add(p);
			if (seenProjectiles.contains(p)
				|| !(relevant(p.getSourceActor()) || relevant(p.getTargetActor()) || near(p.getSourcePoint())))
			{
				continue;
			}
			JsonObject o = rec("proj");
			o.addProperty("id", p.getId());
			o.addProperty("src", ref(p.getSourceActor()));
			o.addProperty("tgt", ref(p.getTargetActor()));
			WorldPoint s = p.getSourcePoint();
			WorldPoint d = p.getTargetPoint();
			if (s != null)
			{
				o.addProperty("sx", s.getX());
				o.addProperty("sy", s.getY());
			}
			o.addProperty("sz", p.getSourceLevel());
			if (d != null)
			{
				o.addProperty("tx", d.getX());
				o.addProperty("ty", d.getY());
			}
			o.addProperty("tz", p.getTargetLevel());
			o.addProperty("start", p.getStartCycle());
			o.addProperty("end", p.getEndCycle());
			o.addProperty("h0", p.getStartHeight());
			o.addProperty("h1", p.getEndHeight());
			o.addProperty("slope", p.getSlope());
			o.addProperty("startPos", p.getStartPos());
			emit(o);
		}
		seenProjectiles = now;
	}

	// ---- one-off events -----------------------------------------------------------------------------

	@Subscribe
	public void onAnimationChanged(AnimationChanged e)
	{
		Actor a = e.getActor();
		if (!capturing() || !relevant(a))
		{
			return;
		}
		stateOf(a);
		LocalPoint lp = a.getLocalLocation();
		WorldPoint wp = a.getWorldLocation();
		JsonObject o = rec("anim");
		o.addProperty("r", ref(a));
		o.addProperty("a", a.getAnimation());
		o.addProperty("af", a.getAnimationFrame());
		o.addProperty("p", a.getPoseAnimation());
		if (lp != null)
		{
			o.addProperty("lx", lp.getX());
			o.addProperty("ly", lp.getY());
		}
		if (wp != null)
		{
			o.addProperty("x", wp.getX());
			o.addProperty("y", wp.getY());
		}
		o.addProperty("i", ref(a.getInteracting()));
		emit(o);
	}

	@Subscribe
	public void onGraphicChanged(GraphicChanged e)
	{
		Actor a = e.getActor();
		if (capturing() && relevant(a))
		{
			checkSpotAnims(a, stateOf(a));
		}
	}

	@Subscribe
	public void onGraphicsObjectCreated(GraphicsObjectCreated e)
	{
		if (!capturing())
		{
			return;
		}
		GraphicsObject g = e.getGraphicsObject();
		LocalPoint lp = g.getLocation();
		WorldView wv = g.getWorldView();
		if (lp == null || wv == null)
		{
			return;
		}
		WorldPoint wp = WorldPoint.fromLocal(wv, lp.getX(), lp.getY(), g.getLevel());
		if (!near(wp))
		{
			return;
		}
		JsonObject o = rec("gobj");
		o.addProperty("id", g.getId());
		o.addProperty("x", wp.getX());
		o.addProperty("y", wp.getY());
		o.addProperty("z", g.getLevel());
		o.addProperty("start", g.getStartCycle());
		o.addProperty("h", g.getZ());
		emit(o);
	}

	@Subscribe
	public void onSoundEffectPlayed(SoundEffectPlayed e)
	{
		if (!capturing() || !config.sounds())
		{
			return;
		}
		JsonObject o = rec("snd");
		o.addProperty("id", e.getSoundId());
		o.addProperty("d", e.getDelay());
		o.addProperty("src", ref(e.getSource()));
		emit(o);
	}

	@Subscribe
	public void onAreaSoundEffectPlayed(AreaSoundEffectPlayed e)
	{
		if (!capturing() || !config.sounds())
		{
			return;
		}
		JsonObject o = rec("asnd");
		o.addProperty("id", e.getSoundId());
		o.addProperty("d", e.getDelay());
		o.addProperty("src", ref(e.getSource()));
		o.addProperty("sx", e.getSceneX());
		o.addProperty("sy", e.getSceneY());
		o.addProperty("range", e.getRange());
		WorldView wv = client.getTopLevelWorldView();
		if (wv != null)
		{
			WorldPoint wp = WorldPoint.fromScene(wv, e.getSceneX(), e.getSceneY(), wv.getPlane());
			o.addProperty("x", wp.getX());
			o.addProperty("y", wp.getY());
			JsonArray on = new JsonArray();
			for (Actor a : tracked)
			{
				WorldArea area = a.getWorldArea();
				if (area != null && area.contains(wp))
				{
					on.add(ref(a));
				}
			}
			o.add("on", on);
		}
		emit(o);
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied e)
	{
		Actor a = e.getActor();
		Hitsplat h = e.getHitsplat();
		Player me = client.getLocalPlayer();
		if (a != null && me != null)
		{
			combatWatch.hitsplat(a == me, h);
		}
		if (!capturing() || !relevant(a))
		{
			return;
		}
		JsonObject o = rec("hit");
		o.addProperty("r", ref(a));
		o.addProperty("type", h.getHitsplatType());
		o.addProperty("amt", h.getAmount());
		o.addProperty("until", h.getDisappearsOnGameCycle());
		o.addProperty("hr", a.getHealthRatio());
		o.addProperty("hs", a.getHealthScale());
		emit(o);
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged e)
	{
		Actor src = e.getSource();
		Actor tgt = e.getTarget();
		Player me = client.getLocalPlayer();
		// Who faces you, for "Start in combat" to tell a hit from an attacker from self-inflicted damage. Turning to
		// face someone isn't a sign of combat by itself: a recording starting then would tell you that someone had
		// just targeted you, before any hit.
		if (me != null && src != null && src != me)
		{
			if (tgt == me)
			{
				facingMe.add(src);
			}
			else
			{
				facingMe.remove(src);
			}
		}
		if (!capturing() || !(relevant(src) || relevant(tgt)))
		{
			return;
		}
		JsonObject o = rec("int");
		o.addProperty("r", ref(e.getSource()));
		o.addProperty("tgt", ref(e.getTarget()));
		emit(o);
	}

	@Subscribe
	public void onActorDeath(ActorDeath e)
	{
		if (capturing() && relevant(e.getActor()))
		{
			JsonObject o = rec("death");
			o.addProperty("r", ref(e.getActor()));
			emit(o);
		}
	}

	@Subscribe
	public void onNpcChanged(NpcChanged e)
	{
		NPC n = e.getNpc();
		if (capturing() && relevant(n))
		{
			JsonObject o = rec("npcchg");
			o.addProperty("r", ref(n));
			o.addProperty("npc", n.getId());
			o.addProperty("old", e.getOld() == null ? -1 : e.getOld().getId());
			emit(o);
		}
	}

	@Subscribe
	public void onPlayerDespawned(PlayerDespawned e)
	{
		forget(e.getPlayer());
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned e)
	{
		forget(e.getNpc());
	}

	@Subscribe
	public void onChatMessage(ChatMessage e)
	{
		if (!capturing() || !config.gameMessages()
			|| (e.getType() != ChatMessageType.GAMEMESSAGE && e.getType() != ChatMessageType.SPAM))
		{
			return;
		}
		JsonObject o = rec("msg");
		o.addProperty("type", e.getType().name());
		o.addProperty("text", names.enabled() ? names.message(e.getMessage(), scenePlayerNames()) : e.getMessage());
		emit(o);
	}

	/** The names of the players in the scene, yours first: for finding names in a game message. */
	private List<String> scenePlayerNames()
	{
		List<String> out = new ArrayList<>();
		Player me = client.getLocalPlayer();
		if (me != null)
		{
			out.add(me.getName());
		}
		WorldView wv = client.getTopLevelWorldView();
		if (wv != null)
		{
			for (Player p : wv.players())
			{
				if (p != null)
				{
					out.add(p.getName());
				}
			}
		}
		return out;
	}

	@Subscribe
	public void onStatChanged(StatChanged e)
	{
		if (!capturing() || !config.localStats() || !SKILLS.contains(e.getSkill()))
		{
			return;
		}
		JsonObject o = rec("stat");
		o.addProperty("skill", e.getSkill().name());
		o.addProperty("lvl", e.getBoostedLevel());
		o.addProperty("base", e.getLevel());
		emit(o);
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged e)
	{
		if (!capturing() || !config.localStats() || e.getVarbitId() != -1 || !VARPS.contains(e.getVarpId()))
		{
			return;
		}
		JsonObject o = rec("varp");
		o.addProperty("id", e.getVarpId());
		o.addProperty("v", e.getValue());
		emit(o);
	}

	// ---- helpers ------------------------------------------------------------------------------------

	// Only changes are written as they happen, so the values they change from go in once, on the first tick you're in
	// the game.
	private void writeStartValues()
	{
		if (startWritten || !config.localStats() || client.getLocalPlayer() == null)
		{
			return;
		}
		startWritten = true;
		for (Skill skill : SKILLS)
		{
			JsonObject o = rec("stat");
			o.addProperty("skill", skill.name());
			o.addProperty("lvl", client.getBoostedSkillLevel(skill));
			o.addProperty("base", client.getRealSkillLevel(skill));
			emit(o);
		}
		for (int varp : VARPS)
		{
			JsonObject o = rec("varp");
			o.addProperty("id", varp);
			o.addProperty("v", client.getVarpValue(varp));
			emit(o);
		}
	}

	private void updateTracked()
	{
		tracked.clear();
		trackedSet.clear();
		Player me = client.getLocalPlayer();
		WorldView wv = client.getTopLevelWorldView();
		if (me == null || wv == null)
		{
			return;
		}
		track(me);
		for (Player p : wv.players())
		{
			consider(p, me);
		}
		for (NPC n : wv.npcs())
		{
			consider(n, me);
		}
	}

	private void consider(Actor a, Player me)
	{
		if (a == null || a == me)
		{
			return;
		}
		if (me.getInteracting() == a || a.getInteracting() == me)
		{
			lastFought.put(a, tick);
		}
		boolean keep;
		if (config.scope() == OsmmCaptureConfig.Scope.NEARBY)
		{
			keep = near(a.getWorldLocation());
		}
		else
		{
			Integer last = lastFought.get(a);
			keep = last != null && tick - last <= STICKY_TICKS;
		}
		if (keep)
		{
			track(a);
		}
	}

	private void track(Actor a)
	{
		if (trackedSet.add(a))
		{
			tracked.add(a);
		}
	}

	/** Tracked, or fighting the local player either way, or (NEARBY) within the radius. */
	private boolean relevant(Actor a)
	{
		if (a == null)
		{
			return false;
		}
		Player me = client.getLocalPlayer();
		if (a == me || trackedSet.contains(a))
		{
			return true;
		}
		if (me != null && (me.getInteracting() == a || a.getInteracting() == me))
		{
			return true;
		}
		return config.scope() == OsmmCaptureConfig.Scope.NEARBY && near(a.getWorldLocation());
	}

	private boolean near(WorldPoint wp)
	{
		Player me = client.getLocalPlayer();
		return wp != null && me != null && me.getWorldLocation().distanceTo(wp) <= config.radius();
	}

	private ActorState stateOf(Actor a)
	{
		ActorState s = states.get(a);
		if (s != null)
		{
			return s;
		}
		s = new ActorState();
		states.put(a, s);
		JsonObject o = rec("actor");
		o.addProperty("r", ref(a));
		o.addProperty("name", a instanceof Player ? names.player(a.getName()) : a.getName());   // (NPCs' as they are)
		o.addProperty("cb", a.getCombatLevel());
		WorldArea area = a.getWorldArea();   // (getFootprintSize isn't tiles: 51 for everyone)
		o.addProperty("size", area != null ? area.getWidth() : 1);
		o.addProperty("idle", a.getIdlePoseAnimation());
		o.addProperty("walk", a.getWalkAnimation());
		o.addProperty("run", a.getRunAnimation());
		if (a instanceof NPC)
		{
			o.addProperty("npc", ((NPC) a).getId());
		}
		else if (a instanceof Player)
		{
			Player p = (Player) a;
			PlayerComposition c = p.getPlayerComposition();
			if (c != null)
			{
				o.addProperty("gender", c.getGender());
				o.add("colors", ints(c.getColors()));
				int[] eq = c.getEquipmentIds();
				o.add("equip", ints(eq));
				s.equipment = eq == null ? null : eq.clone();
			}
			o.addProperty("team", p.getTeam());
			if (p == client.getLocalPlayer())
			{
				o.addProperty("self", true);   // (the header's "local" is missing when recording starts at login)
			}
		}
		emit(o);
		return s;
	}

	private void checkEquipment(Player p, ActorState s)
	{
		PlayerComposition c = p.getPlayerComposition();
		if (c == null)
		{
			return;
		}
		int[] eq = c.getEquipmentIds();
		if (eq == null || Arrays.equals(eq, s.equipment))
		{
			return;
		}
		s.equipment = eq.clone();
		JsonObject o = rec("eq");
		o.addProperty("r", ref(p));
		o.add("ids", ints(eq));
		o.addProperty("idle", p.getIdlePoseAnimation());
		o.addProperty("walk", p.getWalkAnimation());
		o.addProperty("run", p.getRunAnimation());
		emit(o);
	}

	private void checkSpotAnims(Actor a, ActorState s)
	{
		Map<Long, int[]> now = new HashMap<>();
		for (ActorSpotAnim sa : a.getSpotAnims())
		{
			long slot = sa.getHash();
			int[] v = {sa.getId(), sa.getStartCycle(), sa.getHeight()};
			now.put(slot, v);
			int[] old = s.spots.get(slot);
			if (old == null || old[0] != v[0] || old[1] != v[1])
			{
				JsonObject o = rec("spot");
				o.addProperty("r", ref(a));
				o.addProperty("slot", slot);
				o.addProperty("id", v[0]);
				o.addProperty("start", v[1]);
				o.addProperty("h", v[2]);
				emit(o);
			}
		}
		s.spots = now;
	}

	private void forget(Actor a)
	{
		facingMe.remove(a);
		if (capturing() && states.remove(a) != null)
		{
			JsonObject o = rec("gone");
			o.addProperty("r", ref(a));
			emit(o);
		}
		lastFought.remove(a);
	}

	private JsonObject rec(String type)
	{
		JsonObject o = new JsonObject();
		o.addProperty("e", type);
		o.addProperty("t", tick - tickBase);
		o.addProperty("tc", client.getTickCount());
		o.addProperty("c", client.getGameCycle());
		return o;
	}

	// To the file, or while armed for "Start in combat", to the lead-in.
	private void emit(JsonObject o)
	{
		CaptureWriter w = writer;
		if (w != null)
		{
			w.write(gson.toJson(o));
			return;
		}
		LeadIn b = leadIn;
		if (b != null)
		{
			b.add(o, gson.toJson(o));
		}
	}

	private void say(String text)
	{
		// CONSOLE, so onChatMessage (GAMEMESSAGE/SPAM only) doesn't record our own lines
		client.addChatMessage(ChatMessageType.CONSOLE, "", "[OSMM Recording] " + text, null);
	}

	private static String ref(Actor a)
	{
		if (a instanceof Player)
		{
			return "p" + ((Player) a).getId();
		}
		if (a instanceof NPC)
		{
			return "n" + ((NPC) a).getIndex();
		}
		return null;
	}

	private static JsonArray ints(int[] v)
	{
		JsonArray a = new JsonArray();
		if (v != null)
		{
			for (int x : v)
			{
				a.add(x);
			}
		}
		return a;
	}

	private static JsonArray shorts(short[] v)
	{
		JsonArray a = new JsonArray();
		if (v != null)
		{
			for (short x : v)
			{
				a.add(x);
			}
		}
		return a;
	}
}
