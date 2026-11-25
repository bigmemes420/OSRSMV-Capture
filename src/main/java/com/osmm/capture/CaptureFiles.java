package com.osmm.capture;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.runelite.client.util.Filepath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The capture files in the plugin's folder, for the side panel: what each one is (its header, its length and its
 * notes, read by streaming the file once), and the notes sidecar (`<capture>.notes.json`) that edits its label and
 * notes without touching its records. Used off the client thread and off the EDT.
 */
final class CaptureFiles
{
	private static final Logger log = LoggerFactory.getLogger(CaptureFiles.class);

	static final String EXTENSION = ".jsonl";
	static final String SIDECAR = ".notes.json";

	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.UK)
		.withZone(ZoneId.systemDefault());

	/** A note: the tick it's on (the `t` of its record) and what it says. */
	static final class Note
	{
		final int tick;
		final String text;

		Note(int tick, String text)
		{
			this.tick = tick;
			this.text = text == null ? "" : text;
		}

		@Override
		public boolean equals(Object o)
		{
			return o instanceof Note && ((Note) o).tick == tick && ((Note) o).text.equals(text);
		}

		@Override
		public int hashCode()
		{
			return tick * 31 + text.hashCode();
		}

		@Override
		public String toString()
		{
			return tick + ": " + text;
		}
	}

	/** What a capture file says about itself: its header's label and start time, its note records and its length. */
	static final class Summary
	{
		String label = "";
		String utc;          // the header's start time, or null
		boolean header;      // the first line is a header
		boolean ended;       // it has an `end` line
		int ticks = -1;      // the `t` of its last whole line (the end line's, once it's ended); -1 unknown
		final List<Note> notes = new ArrayList<>();
	}

	/** The sidecar's edits: either may be null (then the file's own stands). */
	static final class Edits
	{
		String label;
		List<Note> notes;
	}

	/** One capture in the folder, as the panel lists it. */
	static final class Entry
	{
		final String name;
		final long size;
		final long modified;   // ms since the epoch
		final Summary file;    // null for the recording in progress (not read)
		final Edits edits;     // null without a sidecar
		final boolean recording;

		Entry(String name, long size, long modified, Summary file, Edits edits, boolean recording)
		{
			this.name = name;
			this.size = size;
			this.modified = modified;
			this.file = file;
			this.edits = edits;
			this.recording = recording;
		}

		String label()
		{
			if (edits != null && edits.label != null)
			{
				return edits.label;
			}
			return file != null ? file.label : "";
		}

		List<Note> notes()
		{
			if (edits != null && edits.notes != null)
			{
				return edits.notes;
			}
			return file != null ? file.notes : Collections.emptyList();
		}

		/** When it started: the header's time, else the file's last change. */
		Instant start()
		{
			if (file != null && file.utc != null)
			{
				try
				{
					return Instant.parse(file.utc);
				}
				catch (DateTimeParseException ignored)
				{
				}
			}
			return Instant.ofEpochMilli(modified);
		}
	}

	private static final class Cached
	{
		final long size;
		final long modified;
		final Summary summary;

		Cached(long size, long modified, Summary summary)
		{
			this.size = size;
			this.modified = modified;
			this.summary = summary;
		}
	}

	private final Filepath dir;
	private final Gson gson;
	private final Gson pretty;
	private final Map<String, Cached> cache = new HashMap<>();

	CaptureFiles(Filepath dir, Gson gson)
	{
		this.dir = dir;
		this.gson = gson;
		this.pretty = gson.newBuilder().setPrettyPrinting().create();
	}

	Filepath dir()
	{
		return dir;
	}

	/**
	 * Every capture in the folder, newest first. A file is read only when it's new or changed since the last call; the
	 * one being recorded to (recordingName, may be null) isn't read at all.
	 */
	synchronized List<Entry> list(String recordingName) throws IOException
	{
		List<Entry> out = new ArrayList<>();
		if (!dir.exists())
		{
			return out;
		}
		List<Filepath> files;
		try (Stream<Filepath> s = dir.walk(1))
		{
			files = s.filter(f -> f.isFile() && f.getFileName().endsWith(EXTENSION)).collect(Collectors.toList());
		}
		Map<String, Cached> seen = new HashMap<>();
		for (Filepath f : files)
		{
			String name = f.getFileName();
			long size;
			long modified;
			try
			{
				size = f.size();
				modified = f.getLastModifiedTime().toMillis();
			}
			catch (IOException e)
			{
				continue;   // gone since the listing
			}
			boolean recording = name.equals(recordingName);
			Summary summary = null;
			if (!recording)
			{
				Cached c = cache.get(name);
				if (c == null || c.size != size || c.modified != modified)
				{
					try (BufferedReader in = new BufferedReader(f.openReader(), 1 << 16))
					{
						c = new Cached(size, modified, summarise(in, gson));
					}
					catch (IOException | RuntimeException e)
					{
						log.debug("osmm-capture: can't read {}", name, e);
						continue;
					}
				}
				seen.put(name, c);
				summary = c.summary;
			}
			out.add(new Entry(name, size, modified, summary, readEdits(name), recording));
		}
		cache.keySet().retainAll(seen.keySet());
		cache.putAll(seen);
		out.sort(Comparator.comparing(Entry::start).reversed().thenComparing(e -> e.name, Comparator.reverseOrder()));
		return out;
	}

	/** Writes the sidecar for a capture: its label and notes as edited. The capture itself isn't touched. */
	synchronized void saveEdits(String name, String label, List<Note> notes) throws IOException
	{
		dir.joinSegment(name + SIDECAR).write(sidecarJson(label, notes, pretty) + "\n",
			StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
	}

	/** Deletes a capture and its sidecar. */
	synchronized void delete(String name) throws IOException
	{
		dir.joinSegment(name).deleteIfExists();
		dir.joinSegment(name + SIDECAR).deleteIfExists();
		cache.remove(name);
	}

	private Edits readEdits(String name)
	{
		Filepath f = dir.joinSegment(name + SIDECAR);
		if (!f.isFile())
		{
			return null;
		}
		try (Reader in = f.openReader())
		{
			return parseSidecar(in, gson);
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("osmm-capture: can't read the notes for {}", name, e);
			return null;   // unreadable: the file's own label and notes stand
		}
	}

	// ---- reading and writing (static, for the tests) --------------------------------------------------

	/**
	 * Streams a capture once, parsing only the header (the first line), the `note` lines and the last whole line (for
	 * its tick). Every other line is only checked for its start.
	 */
	static Summary summarise(BufferedReader in, Gson gson) throws IOException
	{
		Summary s = new Summary();
		String line;
		String last = null;
		String beforeLast = null;
		boolean first = true;
		while ((line = in.readLine()) != null)
		{
			if (line.isEmpty())
			{
				continue;
			}
			if (first)
			{
				first = false;
				JsonObject h = parse(line, gson);
				if (h != null && "hdr".equals(string(h, "e")))
				{
					s.header = true;
					String label = string(h, "label");
					s.label = label == null ? "" : label;
					s.utc = string(h, "utc");
				}
			}
			else if (line.startsWith("{\"e\":\"note\""))
			{
				JsonObject n = parse(line, gson);
				if (n != null)
				{
					s.notes.add(new Note(integer(n, "t", 0), string(n, "text")));
				}
			}
			beforeLast = last;
			last = line;
		}
		// (a file cut short can end in half a line: then the one before it counts)
		for (String l : new String[]{last, beforeLast})
		{
			JsonObject o = l == null ? null : parse(l, gson);
			if (o != null)
			{
				s.ticks = integer(o, "t", -1);
				s.ended = "end".equals(string(o, "e"));
				break;
			}
		}
		return s;
	}

	/** Reads a sidecar. A missing or wrongly typed key is left null, so the capture's own value stands. */
	static Edits parseSidecar(Reader in, Gson gson)
	{
		JsonObject o = gson.fromJson(in, JsonObject.class);
		if (o == null)
		{
			return null;
		}
		Edits e = new Edits();
		e.label = string(o, "label");
		JsonElement notes = o.get("notes");
		if (notes != null && notes.isJsonArray())
		{
			e.notes = new ArrayList<>();
			for (JsonElement n : notes.getAsJsonArray())
			{
				if (n.isJsonObject())
				{
					e.notes.add(new Note(integer(n.getAsJsonObject(), "t", 0), string(n.getAsJsonObject(), "text")));
				}
			}
		}
		return e;
	}

	/** A sidecar's text: {"label": ..., "notes": [{"t": tick, "text": ...}]}, the notes in tick order. */
	static String sidecarJson(String label, List<Note> notes, Gson gson)
	{
		List<Note> sorted = new ArrayList<>(notes);
		sorted.sort(Comparator.comparingInt(n -> n.tick));   // (stable: same-tick notes keep their order)
		JsonObject o = new JsonObject();
		o.addProperty("label", label == null ? "" : label);
		JsonArray a = new JsonArray();
		for (Note n : sorted)
		{
			JsonObject j = new JsonObject();
			j.addProperty("t", n.tick);
			j.addProperty("text", n.text);
			a.add(j);
		}
		o.add("notes", a);
		return gson.toJson(o);
	}

	private static JsonObject parse(String line, Gson gson)
	{
		try
		{
			JsonElement e = gson.fromJson(line, JsonElement.class);
			return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	private static String string(JsonObject o, String key)
	{
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	private static int integer(JsonObject o, String key, int otherwise)
	{
		JsonElement e = o.get(key);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber())
		{
			return otherwise;
		}
		return e.getAsInt();
	}

	// ---- text for the panel -----------------------------------------------------------------------------

	/** Game time for a number of ticks (0.6 s each): m:ss, or h:mm:ss from an hour. */
	static String duration(int ticks)
	{
		long seconds = Math.max(0, ticks) * 600L / 1000;
		long h = seconds / 3600;
		long m = seconds / 60 % 60;
		long s = seconds % 60;
		return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
	}

	/** "1 tick (0:00)", "250 ticks (2:30)". */
	static String ticksText(int ticks)
	{
		return ticks + (ticks == 1 ? " tick (" : " ticks (") + duration(ticks) + ")";
	}

	static String sizeText(long bytes)
	{
		if (bytes < 1024)
		{
			return bytes + " B";
		}
		if (bytes < 1024 * 1024)
		{
			return (bytes + 512) / 1024 + " KB";
		}
		return String.format(Locale.UK, "%.1f MB", bytes / (1024.0 * 1024.0));
	}

	static String when(Instant t)
	{
		return WHEN.format(t);
	}
}
