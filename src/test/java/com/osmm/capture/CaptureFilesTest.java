package com.osmm.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import java.util.List;
import net.runelite.http.api.RuneLiteAPI;
import org.junit.Test;

/** Reading a capture's header, notes and length, and the notes sidecar. Plain JUnit, no client. */
public class CaptureFilesTest
{
	private final Gson gson = RuneLiteAPI.GSON;   // what RuneLite injects as Gson

	private CaptureFiles.Summary summarise(String text) throws IOException
	{
		return CaptureFiles.summarise(new BufferedReader(new StringReader(text)), gson);
	}

	@Test
	public void readsHeaderNotesAndLength() throws IOException
	{
		CaptureFiles.Summary s = summarise(
			"{\"e\":\"hdr\",\"t\":0,\"tc\":5,\"c\":10,\"format\":\"osmm-capture\",\"version\":1,\"label\":\"tbow sounds\",\"utc\":\"2026-11-25T14:03:00Z\"}\n"
				+ "{\"e\":\"scene\",\"t\":0,\"tc\":5,\"c\":10,\"baseX\":3200,\"baseY\":3200,\"plane\":0,\"instance\":false}\n"
				+ "{\"e\":\"tick\",\"t\":1,\"tc\":6,\"c\":40,\"a\":[]}\n"
				+ "{\"e\":\"note\",\"t\":1,\"tc\":6,\"c\":41,\"text\":\"barrage walking\"}\n"
				+ "{\"e\":\"msg\",\"t\":1,\"tc\":6,\"c\":41,\"type\":\"GAMEMESSAGE\",\"text\":\"note this\"}\n"
				+ "{\"e\":\"tick\",\"t\":2,\"tc\":7,\"c\":70,\"a\":[]}\n"
				+ "{\"e\":\"note\",\"t\":2,\"tc\":7,\"c\":71,\"text\":\"a \\u003cb\\u003e tag\"}\n"
				+ "{\"e\":\"end\",\"t\":2,\"tc\":7,\"c\":72,\"why\":\"command\"}\n");
		assertTrue(s.header);
		assertEquals("tbow sounds", s.label);
		assertEquals("2026-11-25T14:03:00Z", s.utc);
		assertEquals(Arrays.asList(new CaptureFiles.Note(1, "barrage walking"), new CaptureFiles.Note(2, "a <b> tag")), s.notes);
		assertEquals(2, s.ticks);
		assertTrue(s.ended);
	}

	@Test
	public void aFileCutShort() throws IOException
	{
		CaptureFiles.Summary s = summarise(
			"{\"e\":\"hdr\",\"t\":0,\"tc\":5,\"c\":10,\"label\":\"auto\"}\n"
				+ "{\"e\":\"tick\",\"t\":7,\"tc\":6,\"c\":40,\"a\":[]}\n"
				+ "{\"e\":\"mv\",\"t\":7,\"r\":\"p1\",\"lx\":64");   // half a line
		assertEquals("auto", s.label);
		assertEquals(7, s.ticks);
		assertFalse(s.ended);
		assertTrue(s.notes.isEmpty());
	}

	@Test
	public void notACapture() throws IOException
	{
		CaptureFiles.Summary s = summarise("hello\n");
		assertFalse(s.header);
		assertEquals("", s.label);
		assertEquals(-1, s.ticks);
	}

	@Test
	public void sidecarRoundTrip()
	{
		List<CaptureFiles.Note> notes = Arrays.asList(new CaptureFiles.Note(40, "second"), new CaptureFiles.Note(3, "first"),
			new CaptureFiles.Note(40, "third, same tick"));
		String json = CaptureFiles.sidecarJson("my fight", notes, gson);
		assertEquals("{\"label\":\"my fight\",\"notes\":[{\"t\":3,\"text\":\"first\"},{\"t\":40,\"text\":\"second\"},"
			+ "{\"t\":40,\"text\":\"third, same tick\"}]}", json);
		CaptureFiles.Edits e = CaptureFiles.parseSidecar(new StringReader(json), gson);
		assertEquals("my fight", e.label);
		assertEquals(Arrays.asList(new CaptureFiles.Note(3, "first"), new CaptureFiles.Note(40, "second"),
			new CaptureFiles.Note(40, "third, same tick")), e.notes);
	}

	@Test
	public void sidecarMissingKeysLeaveTheFilesOwn()
	{
		CaptureFiles.Edits e = CaptureFiles.parseSidecar(new StringReader("{\"label\":\"only the label\"}"), gson);
		assertEquals("only the label", e.label);
		assertNull(e.notes);
		CaptureFiles.Summary file = new CaptureFiles.Summary();
		file.label = "file label";
		file.notes.add(new CaptureFiles.Note(5, "file note"));
		CaptureFiles.Entry entry = new CaptureFiles.Entry("capture_x.jsonl", 10, 0, file, e, false);
		assertEquals("only the label", entry.label());
		assertEquals(file.notes, entry.notes());
		CaptureFiles.Entry plain = new CaptureFiles.Entry("capture_x.jsonl", 10, 0, file, null, false);
		assertEquals("file label", plain.label());
	}

	@Test
	public void panelText()
	{
		assertEquals("0:00", CaptureFiles.duration(0));
		assertEquals("2:30", CaptureFiles.duration(250));
		assertEquals("1:00:00", CaptureFiles.duration(6000));
		assertEquals("1 tick (0:00)", CaptureFiles.ticksText(1));
		assertEquals("250 ticks (2:30)", CaptureFiles.ticksText(250));
		assertEquals("900 B", CaptureFiles.sizeText(900));
		assertEquals("12 KB", CaptureFiles.sizeText(12 * 1024));
		assertEquals("3.5 MB", CaptureFiles.sizeText(3 * 1024 * 1024 + 512 * 1024));
	}
}
