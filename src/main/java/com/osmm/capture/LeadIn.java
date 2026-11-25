package com.osmm.capture;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The lead-in for "Start in combat". While it's armed, the plugin records as usual but its lines come here instead of
 * to a file: the last few ticks' lines, as written, grouped by their tick ({@code t}). Older ticks are dropped, but a
 * dropped line that sets state later lines rely on (the scene, an actor's description, its gear, an NPC's change of
 * form or overheads, your stats and the watched varps) is kept, the latest of each. When combat starts, {@link #drain}
 * hands back those kept lines (as tick 0) followed by the ticks still held, with every {@code t} re-based so that the
 * file's tick 0 is the lead-in's start. So the file reads as if it had been recording from then: the scene first,
 * each actor described before it's referenced, and a map load inside the lead-in just another scene line.
 *
 * <p>Bounded by ticks ({@link #setMaxTicks}) and by memory: past {@code maxBytes} the oldest ticks go early, and if
 * one tick alone is over it, {@link #overflowed} says so and the plugin starts the lead-in again. Client thread only.
 */
final class LeadIn
{
	/** About this much heap at most (an estimate: the lines' characters and the objects that hold them). */
	static final long MAX_BYTES = 8L << 20;
	private static final int ENTRY_OVERHEAD = 80;   // the line's String and array headers, the Entry, the list slot

	private static final class Entry
	{
		final int t;
		final String type;
		final String key;                          // what state it sets (see keyOf); null for events
		final String json;
		final Map<String, JsonObject> overheads;   // tick lines: NPCs' overheads that changed, by ref; usually null

		Entry(int t, String type, String key, String json, Map<String, JsonObject> overheads)
		{
			this.t = t;
			this.type = type;
			this.key = key;
			this.json = json;
			this.overheads = overheads;
		}
	}

	private static final class Group
	{
		final int t;
		final int tc;
		final int c;
		final List<Entry> entries = new ArrayList<>();
		long bytes;

		Group(int t, int tc, int c)
		{
			this.t = t;
			this.tc = tc;
			this.c = c;
		}
	}

	/** What {@link #drain} hands back. */
	static final class Drained
	{
		final int offset;          // subtracted from the plugin's tick count to give the file's t
		final int tc;              // the lead-in's first tick count and cycle (for the header)
		final int c;
		final List<String> lines;  // the lines to write after the header

		Drained(int offset, int tc, int c, List<String> lines)
		{
			this.offset = offset;
			this.tc = tc;
			this.c = c;
			this.lines = lines;
		}
	}

	private final ArrayDeque<Group> groups = new ArrayDeque<>();
	private final long maxBytes;
	private int maxTicks;
	private long bytes;
	private boolean overflowed;

	// left behind by the dropped ticks: the latest line of each kind that sets state
	private Entry scene;
	private final Map<String, Entry> settings = new LinkedHashMap<>();   // "stat:<skill>", "varp:<id>"
	private final Map<String, Entry[]> actors = new LinkedHashMap<>();   // ref -> {actor, eq, npcchg}
	private final Map<String, JsonObject> overheads = new HashMap<>();   // ref -> {ohA, ohS}

	LeadIn(int maxTicks, long maxBytes)
	{
		this.maxTicks = Math.max(1, maxTicks);
		this.maxBytes = maxBytes;
	}

	/** Ticks held (a change applies from the next tick). */
	void setMaxTicks(int maxTicks)
	{
		this.maxTicks = Math.max(1, maxTicks);
	}

	/** One tick's lines came to more than the memory bound: start again. */
	boolean overflowed()
	{
		return overflowed;
	}

	int ticksHeld()
	{
		return groups.size();
	}

	long bytes()
	{
		return bytes;
	}

	/** A record as the plugin would write it: o, and json, o serialised. Its "e" and "t" come first, as rec() makes them. */
	void add(JsonObject o, String json)
	{
		if (overflowed)
		{
			return;
		}
		int t = o.get("t").getAsInt();
		String type = o.get("e").getAsString();
		Group g = groups.peekLast();
		if (g == null || g.t != t)
		{
			g = new Group(t, intOf(o, "tc"), intOf(o, "c"));
			groups.addLast(g);
			while (groups.size() > maxTicks)
			{
				dropOldest();
			}
		}
		Entry e = new Entry(t, type, keyOf(type, o), json, "tick".equals(type) ? overheadsOf(o) : null);
		long b = json.length() + ENTRY_OVERHEAD;
		g.entries.add(e);
		g.bytes += b;
		bytes += b;
		while (bytes > maxBytes && groups.size() > 1)
		{
			dropOldest();
		}
		if (bytes > maxBytes)
		{
			overflowed = true;
		}
	}

	/**
	 * Everything held, ready to write after a header: the kept state lines at t 0, then the ticks held, re-based so the
	 * oldest is tick 1 (or, when nothing has been dropped yet, as they are). Null when empty. Empties the lead-in.
	 */
	Drained drain(Gson gson)
	{
		Group first = groups.peekFirst();
		if (first == null)
		{
			return null;
		}
		int offset = Math.max(first.t - 1, 0);
		List<String> out = new ArrayList<>();
		if (scene != null)
		{
			out.add(retime(scene, 0));
		}
		for (Entry e : settings.values())
		{
			out.add(retime(e, 0));
		}
		for (Entry[] a : actors.values())
		{
			for (Entry e : a)
			{
				if (e != null)
				{
					out.add(retime(e, 0));
				}
			}
		}
		// Overheads set in a dropped tick go on the actor's next tick line (the plugin writes them only on a change).
		Map<String, JsonObject> pending = new HashMap<>(overheads);
		for (Group g : groups)
		{
			for (Entry e : g.entries)
			{
				String line = retime(e, e.t - offset);
				if (!pending.isEmpty())
				{
					if ("tick".equals(e.type))
					{
						line = addOverheads(line, pending, gson);
					}
					else if ("scene".equals(e.type))
					{
						pending.clear();
					}
					else if ("actor".equals(e.type) || "gone".equals(e.type))
					{
						pending.remove(e.key);
					}
				}
				out.add(line);
			}
		}
		Drained d = new Drained(offset, first.tc, first.c, out);
		groups.clear();
		bytes = 0;
		scene = null;
		settings.clear();
		actors.clear();
		overheads.clear();
		return d;
	}

	private void dropOldest()
	{
		Group g = groups.removeFirst();
		bytes -= g.bytes;
		for (Entry e : g.entries)
		{
			keep(e);
		}
	}

	/** A dropped line: keep it if it sets state, replacing what it supersedes. */
	private void keep(Entry e)
	{
		switch (e.type)
		{
			case "scene":
				// the plugin describes every actor again after a scene line (a map load), so the old ones are done with
				scene = e;
				actors.clear();
				overheads.clear();
				break;
			case "stat":
			case "varp":
				settings.put(e.key, e);
				break;
			case "actor":
				actors.put(e.key, new Entry[]{e, null, null});
				overheads.remove(e.key);
				break;
			case "eq":
			case "npcchg":
			{
				Entry[] a = actors.get(e.key);
				if (a != null)
				{
					a["eq".equals(e.type) ? 1 : 2] = e;
				}
				break;
			}
			case "gone":
				actors.remove(e.key);
				overheads.remove(e.key);
				break;
			case "tick":
				if (e.overheads != null)
				{
					overheads.putAll(e.overheads);
				}
				break;
			default:
				// an event: gone with its tick
		}
	}

	/** The state a line sets, as a key: the actor's ref, "stat:<skill>", "varp:<id>", "" for the scene; else null. */
	static String keyOf(String type, JsonObject o)
	{
		switch (type)
		{
			case "scene":
				return "";
			case "actor":
			case "eq":
			case "npcchg":
			case "gone":
				return stringOf(o, "r");
			case "stat":
				return "stat:" + stringOf(o, "skill");
			case "varp":
				return "varp:" + stringOf(o, "id");
			default:
				return null;
		}
	}

	private static Map<String, JsonObject> overheadsOf(JsonObject tick)
	{
		JsonElement a = tick.get("a");
		if (a == null || !a.isJsonArray())
		{
			return null;
		}
		Map<String, JsonObject> out = null;
		for (JsonElement el : a.getAsJsonArray())
		{
			if (el.isJsonObject() && el.getAsJsonObject().has("ohA"))
			{
				JsonObject actor = el.getAsJsonObject();
				JsonObject oh = new JsonObject();
				oh.add("ohA", actor.get("ohA"));
				oh.add("ohS", actor.get("ohS"));
				if (out == null)
				{
					out = new HashMap<>();
				}
				out.put(stringOf(actor, "r"), oh);
			}
		}
		return out;
	}

	/** A tick line with the pending overheads added to the actors it lists (each actor's taken off pending). */
	private static String addOverheads(String line, Map<String, JsonObject> pending, Gson gson)
	{
		JsonObject o = gson.fromJson(line, JsonObject.class);
		JsonElement a = o.get("a");
		if (a == null || !a.isJsonArray())
		{
			return line;
		}
		boolean changed = false;
		for (JsonElement el : a.getAsJsonArray())
		{
			if (!el.isJsonObject())
			{
				continue;
			}
			JsonObject actor = el.getAsJsonObject();
			JsonObject oh = pending.remove(stringOf(actor, "r"));
			if (oh != null && !actor.has("ohA"))
			{
				actor.add("ohA", oh.get("ohA"));
				actor.add("ohS", oh.get("ohS"));
				changed = true;
			}
		}
		return changed ? gson.toJson(o) : line;
	}

	/** The line with its t changed. rec() puts "e" then "t" first, so the first ,"t":N, is the common field. */
	private static String retime(Entry e, int t)
	{
		if (e.t == t)
		{
			return e.json;
		}
		return retime(e.json, e.t, t);
	}

	static String retime(String json, int from, int to)
	{
		String old = ",\"t\":" + from + ",";
		int i = json.indexOf(old);
		if (i < 0)
		{
			return json;   // (can't happen: every line comes from rec())
		}
		return json.substring(0, i) + ",\"t\":" + to + "," + json.substring(i + old.length());
	}

	private static String stringOf(JsonObject o, String key)
	{
		JsonElement v = o.get(key);
		return v == null || v.isJsonNull() ? null : v.getAsString();
	}

	private static int intOf(JsonObject o, String key)
	{
		JsonElement v = o.get(key);
		return v == null || v.isJsonNull() ? 0 : v.getAsInt();
	}
}
