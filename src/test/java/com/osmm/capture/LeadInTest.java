package com.osmm.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.http.api.RuneLiteAPI;
import org.junit.Test;

/** The "Start in combat" lead-in: bounded, re-based, and every actor described before it's referenced. Plain JUnit. */
public class LeadInTest
{
	private final Gson gson = RuneLiteAPI.GSON;   // what RuneLite injects as Gson

	/** Stands in for the plugin: stamps records as rec() does and hands them to the lead-in as emit() does. */
	private final class Sim
	{
		final LeadIn lead;
		int tick;

		Sim(int maxTicks, long maxBytes)
		{
			lead = new LeadIn(maxTicks, maxBytes);
		}

		JsonObject rec(String type)
		{
			JsonObject o = new JsonObject();
			o.addProperty("e", type);
			o.addProperty("t", tick);
			o.addProperty("tc", 1000 + tick);
			o.addProperty("c", 50000 + tick * 30);
			return o;
		}

		void emit(JsonObject o)
		{
			lead.add(o, gson.toJson(o));
		}

		void scene(int baseX)
		{
			JsonObject o = rec("scene");
			o.addProperty("baseX", baseX);
			o.addProperty("baseY", 3200);
			o.addProperty("plane", 0);
			o.addProperty("instance", false);
			emit(o);
		}

		void actor(String ref)
		{
			JsonObject o = rec("actor");
			o.addProperty("r", ref);
			o.addProperty("name", "name " + ref);
			emit(o);
		}

		void simple(String type, String ref)
		{
			JsonObject o = rec(type);
			o.addProperty("r", ref);
			emit(o);
		}

		void hit(String on)
		{
			JsonObject o = rec("hit");
			o.addProperty("r", on);
			o.addProperty("type", 16);
			o.addProperty("amt", 12);
			emit(o);
		}

		void projectile(String from, String to)
		{
			JsonObject o = rec("proj");
			o.addProperty("id", 1465);
			o.addProperty("src", from);
			o.addProperty("tgt", to);
			emit(o);
		}

		void stat(String skill, int lvl)
		{
			JsonObject o = rec("stat");
			o.addProperty("skill", skill);
			o.addProperty("lvl", lvl);
			o.addProperty("base", 99);
			emit(o);
		}

		/** A new tick: the tick line, listing these actors (each facing the next). */
		void tickLine(String... refs)
		{
			tick++;
			JsonArray a = new JsonArray();
			for (int i = 0; i < refs.length; i++)
			{
				JsonObject actor = new JsonObject();
				actor.addProperty("r", refs[i]);
				actor.addProperty("x", 3200 + tick);
				actor.addProperty("y", 3200);
				if (refs.length > 1)
				{
					actor.addProperty("i", refs[(i + 1) % refs.length]);
				}
				a.add(actor);
			}
			JsonObject o = rec("tick");
			o.add("a", a);
			emit(o);
		}

		/** Per-cycle motion lines for an actor (as the plugin's ClientTick writes them). */
		void motion(String ref, int n)
		{
			for (int i = 0; i < n; i++)
			{
				JsonObject o = rec("mv");
				o.addProperty("r", ref);
				o.addProperty("lx", 6000 + i);
				o.addProperty("ly", 6000);
				emit(o);
			}
		}
	}

	private List<JsonObject> parse(List<String> lines)
	{
		List<JsonObject> out = new ArrayList<>();
		for (String l : lines)
		{
			out.add(gson.fromJson(l, JsonObject.class));
		}
		return out;
	}

	private static List<String> refsIn(JsonObject o)
	{
		List<String> refs = new ArrayList<>();
		for (String k : new String[]{"r", "i", "src", "tgt"})
		{
			if (o.has(k) && !o.get(k).isJsonNull())
			{
				refs.add(o.get(k).getAsString());
			}
		}
		if (o.has("a") && o.get("a").isJsonArray())
		{
			for (JsonElement el : o.getAsJsonArray("a"))
			{
				refs.addAll(refsIn(el.getAsJsonObject()));
			}
		}
		return refs;
	}

	/**
	 * What a reader needs: the scene first, t never going down and from 0, and every actor referenced after its actor
	 * line (and before any gone line ends it; a scene line ends them all, as the plugin describes everyone again).
	 */
	private static void assertReadable(List<JsonObject> lines)
	{
		assertEquals("scene", lines.get(0).get("e").getAsString());
		assertEquals(0, lines.get(0).get("t").getAsInt());
		Set<String> described = new HashSet<>();
		int lastT = 0;
		for (JsonObject o : lines)
		{
			int t = o.get("t").getAsInt();
			assertTrue("t goes down at " + o, t >= lastT);
			lastT = t;
			String e = o.get("e").getAsString();
			switch (e)
			{
				case "scene":
					described.clear();
					break;
				case "actor":
					described.add(o.get("r").getAsString());
					break;
				case "gone":
					assertTrue("gone before described: " + o, described.remove(o.get("r").getAsString()));
					break;
				default:
					for (String r : refsIn(o))
					{
						if (!described.contains(r))
						{
							fail(r + " referenced before it's described: " + o);
						}
					}
			}
		}
	}

	private static List<Integer> tickLineTs(List<JsonObject> lines)
	{
		List<Integer> out = new ArrayList<>();
		for (JsonObject o : lines)
		{
			if ("tick".equals(o.get("e").getAsString()))
			{
				out.add(o.get("t").getAsInt());
			}
		}
		return out;
	}

	@Test
	public void boundedByTicksAndRebased()
	{
		Sim s = new Sim(10, LeadIn.MAX_BYTES);
		s.scene(3200);
		s.actor("p1");
		for (int i = 0; i < 100; i++)
		{
			s.tickLine("p1");
			s.motion("p1", 20);
			assertTrue(s.lead.ticksHeld() <= 10);
		}
		assertEquals(10, s.lead.ticksHeld());
		LeadIn.Drained d = s.lead.drain(gson);
		assertNotNull(d);
		assertEquals("the oldest tick held is 91: tick 1 in the file", 90, d.offset);
		assertEquals("the plugin's lead-in length: its tick less the offset", 10, s.tick - d.offset);
		List<JsonObject> lines = parse(d.lines);
		assertReadable(lines);
		assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), tickLineTs(lines));
		assertEquals("scene and p1 kept, then 10 ticks of 21 lines", 2 + 10 * 21, lines.size());
		assertEquals("the header gets the lead-in's first tick count", 1091, d.tc);
		// emptied
		assertNull(s.lead.drain(gson));
		assertEquals(0, s.lead.bytes());
	}

	@Test
	public void nothingDroppedYetKeepsTheTicksAsTheyAre()
	{
		Sim s = new Sim(10, LeadIn.MAX_BYTES);
		s.scene(3200);
		s.actor("p1");
		for (int i = 0; i < 4; i++)
		{
			s.tickLine("p1");
		}
		LeadIn.Drained d = s.lead.drain(gson);
		assertEquals(0, d.offset);
		List<JsonObject> lines = parse(d.lines);
		assertReadable(lines);
		assertEquals(List.of(1, 2, 3, 4), tickLineTs(lines));
		assertEquals(6, lines.size());
	}

	@Test
	public void boundedByMemory()
	{
		long max = 40_000;
		Sim s = new Sim(50, max);
		s.scene(3200);
		s.actor("p1");
		for (int i = 0; i < 200; i++)
		{
			s.tickLine("p1");
			s.motion("p1", 30);
			assertTrue("over the bound: " + s.lead.bytes(), s.lead.bytes() <= max);
		}
		assertFalse(s.lead.overflowed());
		assertTrue("fewer ticks than asked, to stay in the bound", s.lead.ticksHeld() < 50);
		assertReadable(parse(s.lead.drain(gson).lines));
	}

	@Test
	public void oneTickOverTheBoundOverflows()
	{
		Sim s = new Sim(10, 5_000);
		s.scene(3200);
		s.actor("p1");
		s.tickLine("p1");
		s.motion("p1", 200);
		assertTrue(s.lead.overflowed());
	}

	@Test
	public void actorsAreDescribedBeforeTheyreReferenced()
	{
		Sim s = new Sim(10, LeadIn.MAX_BYTES);
		s.scene(3200);
		s.actor("p1");                  // you
		s.actor("p2");                  // an opponent, there from the start: its actor line will be dropped
		for (int i = 0; i < 40; i++)
		{
			if (s.tick == 2)
			{
				s.simple("eq", "p2");   // and its gear switch
			}
			if (s.tick == 5)
			{
				s.actor("n5");
				s.simple("npcchg", "n5");
			}
			if (s.tick == 8)
			{
				s.simple("gone", "n5"); // gone before the lead-in: not described again
			}
			if (s.tick == 35)
			{
				s.actor("n5");          // the index reused, inside the lead-in
			}
			if (s.tick >= 35)
			{
				s.tickLine("p1", "p2", "n5");
			}
			else
			{
				s.tickLine("p1", "p2");
			}
			s.motion("p2", 3);
			s.projectile("p2", "p1");
			s.hit("p1");
		}
		LeadIn.Drained d = s.lead.drain(gson);
		List<JsonObject> lines = parse(d.lines);
		assertReadable(lines);
		// p2's description and its last gear are in the kept lines, at t 0; n5 only from its new description
		int p2 = -1;
		int p2eq = -1;
		int n5 = 0;
		for (int i = 0; i < lines.size(); i++)
		{
			JsonObject o = lines.get(i);
			String e = o.get("e").getAsString();
			String r = o.has("r") ? o.get("r").getAsString() : "";
			if (e.equals("actor") && r.equals("p2"))
			{
				p2 = i;
				assertEquals(0, o.get("t").getAsInt());
			}
			if (e.equals("eq") && r.equals("p2"))
			{
				p2eq = i;
				assertEquals(0, o.get("t").getAsInt());
			}
			if ((e.equals("actor") || e.equals("npcchg")) && r.equals("n5"))
			{
				n5++;
			}
		}
		assertTrue(p2 >= 0 && p2eq > p2);
		assertEquals("n5's old description and change of form went with its gone", 1, n5);
	}

	@Test
	public void aMapLoadInsideTheLeadIn()
	{
		// the plugin, at a map load: a new scene line, then everyone described again as they turn up
		Sim s = new Sim(20, LeadIn.MAX_BYTES);
		s.scene(3200);
		s.actor("p1");
		s.actor("n1");
		for (int i = 0; i < 30; i++)
		{
			if (s.tick == 15)
			{
				s.scene(3264);
				s.actor("p1");
				s.actor("n2");
			}
			s.tickLine("p1", s.tick < 15 ? "n1" : "n2");
		}
		List<JsonObject> lines = parse(s.lead.drain(gson).lines);
		assertReadable(lines);
		List<Integer> baseXs = new ArrayList<>();
		for (JsonObject o : lines)
		{
			if (o.get("e").getAsString().equals("scene"))
			{
				baseXs.add(o.get("baseX").getAsInt());
			}
		}
		assertEquals("the old scene first, then the new one", List.of(3200, 3264), baseXs);
	}

	@Test
	public void aMapLoadDroppedFromTheLeadIn()
	{
		Sim s = new Sim(10, LeadIn.MAX_BYTES);
		s.scene(3200);
		s.actor("p1");
		s.actor("n1");
		for (int i = 0; i < 30; i++)
		{
			if (s.tick == 15)
			{
				s.scene(3264);
				s.actor("p1");
				s.actor("n2");
			}
			s.tickLine("p1", s.tick < 15 ? "n1" : "n2");
		}
		List<JsonObject> lines = parse(s.lead.drain(gson).lines);
		assertReadable(lines);
		assertEquals(3264, lines.get(0).get("baseX").getAsInt());
		for (JsonObject o : lines)
		{
			assertFalse("n1 belonged to the old scene", o.toString().contains("\"n1\""));
		}
	}

	@Test
	public void npcOverheadsAndStatsSurviveTheDrop()
	{
		Sim s = new Sim(5, LeadIn.MAX_BYTES);
		s.scene(3200);
		s.actor("p1");
		s.actor("n7");
		s.tick++;
		s.stat("ATTACK", 99);    // the start values, on the first tick
		JsonObject t = s.rec("tick");
		JsonArray a = new JsonArray();
		JsonObject n7 = new JsonObject();
		n7.addProperty("r", "n7");
		JsonArray arch = new JsonArray();
		arch.add(440);
		JsonArray spr = new JsonArray();
		spr.add(2);
		n7.add("ohA", arch);   // a demonic gorilla's protect from missiles, set long before the fight
		n7.add("ohS", spr);
		a.add(n7);
		t.add("a", a);
		s.emit(t);
		s.tickLine("p1", "n7");
		s.stat("ATTACK", 118);   // a potion
		for (int i = 0; i < 20; i++)
		{
			s.tickLine("p1", "n7");
		}
		List<JsonObject> lines = parse(s.lead.drain(gson).lines);
		assertReadable(lines);
		JsonObject stat = null;
		JsonObject firstTick = null;
		for (JsonObject o : lines)
		{
			String e = o.get("e").getAsString();
			if (e.equals("stat"))
			{
				assertNull("one stat line per skill", stat);
				stat = o;
			}
			if (e.equals("tick") && firstTick == null)
			{
				firstTick = o;
			}
		}
		assertNotNull(stat);
		assertEquals("the latest value", 118, stat.get("lvl").getAsInt());
		assertEquals(0, stat.get("t").getAsInt());
		JsonObject first = null;
		for (JsonElement el : firstTick.getAsJsonArray("a"))
		{
			if (el.getAsJsonObject().get("r").getAsString().equals("n7"))
			{
				first = el.getAsJsonObject();
			}
		}
		assertNotNull(first);
		assertEquals(440, first.getAsJsonArray("ohA").get(0).getAsInt());
		assertEquals(2, first.getAsJsonArray("ohS").get(0).getAsInt());
	}

	@Test
	public void retime()
	{
		assertEquals("{\"e\":\"mv\",\"t\":3,\"tc\":512,\"c\":9000,\"r\":\"p1\"}",
			LeadIn.retime("{\"e\":\"mv\",\"t\":125,\"tc\":512,\"c\":9000,\"r\":\"p1\"}", 125, 3));
		// a "t" further on (a note's text, say) is left alone
		assertEquals("{\"e\":\"note\",\"t\":0,\"tc\":5,\"c\":7,\"text\":\",\\\"t\\\":12,\"}",
			LeadIn.retime("{\"e\":\"note\",\"t\":12,\"tc\":5,\"c\":7,\"text\":\",\\\"t\\\":12,\"}", 12, 0));
	}
}
