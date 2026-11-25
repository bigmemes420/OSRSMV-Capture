package com.osmm.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.runelite.http.api.RuneLiteAPI;
import org.junit.Test;

/** "Anonymise player names": the codes, game messages, the key, and the lead-in. Plain JUnit, no client. */
public class PlayerNamesTest
{
	private final Gson gson = RuneLiteAPI.GSON;   // what RuneLite injects as Gson

	private static byte[] key(int seed)
	{
		byte[] k = new byte[NameKey.BYTES];
		for (int i = 0; i < k.length; i++)
		{
			k[i] = (byte) (seed * 31 + i * 7);
		}
		return k;
	}

	@Test
	public void theSamePlayerGetsTheSameCode()
	{
		PlayerNames n = new PlayerNames(key(1));
		String code = n.player("Iron Man_99");
		assertEquals(code, n.player("iron man_99"));
		assertEquals(code, n.player("IRON_MAN 99"));
		assertEquals(code, n.player("Iron\u00A0Man\u00A099"));
		assertEquals(code, n.player("Iron-Man-99"));
		assertEquals(code, n.player("<img=2>Iron Man 99"));
		// and in another recording made with the same key
		assertEquals(code, new PlayerNames(key(1)).player("iron man 99"));
	}

	@Test
	public void differentPlayersGetDifferentCodes()
	{
		PlayerNames n = new PlayerNames(key(1));
		assertNotEquals(n.player("Zezima"), n.player("Zezima2"));
		assertNotEquals(n.player("Lynx Titan"), n.player("Lynx Titans"));
		assertEquals(n.player("Lynx Titan"), n.player(" Lynx Titan "));   // (trimmed)
	}

	@Test
	public void aDifferentKeyGivesDifferentCodes()
	{
		assertNotEquals(new PlayerNames(key(1)).player("Zezima"), new PlayerNames(key(2)).player("Zezima"));
	}

	@Test
	public void theCodesFormat()
	{
		PlayerNames n = new PlayerNames(key(1));
		for (String name : Arrays.asList("Zezima", "a", "Lynx Titan", "12345678901"))
		{
			String code = n.player(name);
			assertTrue(code, code.matches("Player [0-9A-F]{6}"));
		}
	}

	@Test
	public void twoNamesWithTheSameSixDigitsInARecording()
	{
		PlayerNames n = new PlayerNames(key(3));
		Map<String, String> byCode = new HashMap<>();
		String first = null;
		String second = null;
		for (int i = 0; i < 200_000 && second == null; i++)
		{
			String name = "name" + i;
			String code = n.player(name);
			if (code.length() > PlayerNames.PREFIX.length() + PlayerNames.DIGITS)
			{
				second = name;
				first = byCode.get(code.substring(0, PlayerNames.PREFIX.length() + PlayerNames.DIGITS));
			}
			else
			{
				byCode.put(code, name);
			}
		}
		assertNotNull("no two names shared six digits", second);
		assertNotNull(first);
		String a = n.player(first);
		String b = n.player(second);
		assertTrue(a, a.matches("Player [0-9A-F]{6}"));
		assertTrue(b, b.matches("Player [0-9A-F]{8}"));
		assertTrue(b.startsWith(a));
		// each keeps its own code for the rest of the recording
		assertEquals(a, n.player(first));
		assertEquals(b, n.player(second));
		// a recording without the first has the second's six-digit code
		assertEquals(a, new PlayerNames(key(3)).player(second));
	}

	@Test
	public void namesInMessages()
	{
		PlayerNames n = new PlayerNames(key(1));
		String zez = n.player("Zezima");
		String lynx = n.player("Lynx Titan");
		List<String> none = Collections.emptyList();

		assertEquals("You have defeated " + zez + ".", n.message("You have defeated Zezima.", none));
		// tags around the name are kept; case and spacing don't matter
		assertEquals("<col=ff0000>" + zez + "</col> has accepted your trade.",
			n.message("<col=ff0000>zEZIMA</col> has accepted your trade.", none));
		assertEquals("<img=2>" + lynx + " wishes to duel with you.",
			n.message("<img=2>Lynx\u00A0Titan wishes to duel with you.", none));
		assertEquals(lynx + " and " + zez + " and " + zez + "'s pet.",
			n.message("lynx_titan and ZEZIMA and Zezima's pet.", none));
		// a player in the scene not yet seen in the recording
		String other = n.message("You were killed by Bob The Great!", Arrays.asList("Bob the great", null, ""));
		assertEquals("You were killed by " + n.player("bob the great") + "!", other);
		// no names: as it was
		String plain = "Oh dear, you are dead!";
		assertSame(plain, n.message(plain, Arrays.asList("Somebody")));
	}

	@Test
	public void onlyWholeWords()
	{
		PlayerNames n = new PlayerNames(key(1));
		List<String> scene = Arrays.asList("Al", "Ice", "col", "Ice Cold");
		assertEquals("Your Alchemy is fine. Price check.", n.message("Your Alchemy is fine. Price check.", scene));
		// a name that's also inside a tag isn't touched there
		assertEquals("<col=ef1020>" + n.player("col") + "</col>", n.message("<col=ef1020>col</col>", scene));
		// the longer of two names wins
		assertEquals(n.player("Ice Cold") + " hits " + n.player("Al") + ".", n.message("Ice Cold hits Al.", scene));
		assertEquals("Cold " + n.player("Ice") + ".", n.message("Cold Ice.", scene));
	}

	@Test
	public void offLeavesNamesAsTheyAre()
	{
		PlayerNames n = PlayerNames.off();
		assertFalse(n.enabled());
		assertEquals("Zezima", n.player("Zezima"));
		assertNull(n.player(null));
		assertEquals("You have defeated Zezima.", n.message("You have defeated Zezima.", Arrays.asList("Zezima")));
		assertTrue(new PlayerNames(key(1)).enabled());
	}

	/** A key store in memory, counting writes. */
	private static final class MemoryStore implements NameKey.Store
	{
		byte[] bytes;
		int writes;

		@Override
		public byte[] read()
		{
			return bytes == null ? null : bytes.clone();
		}

		@Override
		public void write(byte[] key)
		{
			bytes = key.clone();
			writes++;
		}
	}

	@Test
	public void theKeyIsMadeOnceThenKept() throws IOException
	{
		MemoryStore store = new MemoryStore();
		SecureRandom random = new SecureRandom();
		byte[] first = NameKey.loadOrCreate(store, random);
		assertEquals(NameKey.BYTES, first.length);
		assertEquals(1, store.writes);
		byte[] again = NameKey.loadOrCreate(store, random);
		assertTrue(Arrays.equals(first, again));
		assertEquals(1, store.writes);
		assertEquals(new PlayerNames(first).player("Zezima"), new PlayerNames(again).player("Zezima"));

		// a file cut short is made again (new codes)
		store.bytes = new byte[3];
		byte[] remade = NameKey.loadOrCreate(store, random);
		assertEquals(NameKey.BYTES, remade.length);
		assertEquals(2, store.writes);
		assertFalse(Arrays.equals(first, remade));

		// deleted: a new key, so new codes
		store.bytes = null;
		byte[] fresh = NameKey.loadOrCreate(store, random);
		assertNotEquals(new PlayerNames(remade).player("Zezima"), new PlayerNames(fresh).player("Zezima"));
	}

	/**
	 * The lead-in, recorded as the plugin records it with the option on (actor names and messages through
	 * PlayerNames as the records are made), holds no real name: not in the ticks it keeps, nor in the actor lines it
	 * keeps from the ticks it dropped.
	 */
	@Test
	public void theLeadInHoldsNoNames()
	{
		PlayerNames n = new PlayerNames(key(1));
		LeadIn lead = new LeadIn(3, LeadIn.MAX_BYTES);
		List<String> scene = Arrays.asList("Me Myself", "Zezima", "Lynx Titan");
		for (int t = 0; t < 10; t++)
		{
			if (t == 0 || t == 6)
			{
				lead.add(actor(t, "p1", n.player("Me Myself"), true), json(actor(t, "p1", n.player("Me Myself"), true)));
				JsonObject z = actor(t, "p2", n.player("Zezima"), false);
				lead.add(z, json(z));
				JsonObject npc = actor(t, "n7", "Guard", false);   // (NPCs keep their names)
				lead.add(npc, json(npc));
			}
			JsonObject m = rec("msg", t);
			m.addProperty("type", "GAMEMESSAGE");
			m.addProperty("text", n.message("<col=ff0000>Zezima</col> and LYNX_TITAN attack Me Myself.", scene));
			lead.add(m, json(m));
			JsonObject tick = rec("tick", t);
			lead.add(tick, json(tick));
		}
		LeadIn.Drained d = lead.drain(gson);
		assertNotNull(d);
		String all = String.join("\n", d.lines).toLowerCase(Locale.ROOT);
		for (String name : Arrays.asList("zezima", "lynx", "titan", "me myself", "myself"))
		{
			assertFalse(name + " in " + all, all.contains(name));
		}
		assertTrue(all.contains(n.player("Zezima").toLowerCase(Locale.ROOT)));
		assertTrue(all.contains(n.player("Lynx Titan").toLowerCase(Locale.ROOT)));
		assertTrue(all.contains("guard"));
	}

	private static JsonObject rec(String type, int t)
	{
		JsonObject o = new JsonObject();
		o.addProperty("e", type);
		o.addProperty("t", t);
		o.addProperty("tc", 1000 + t);
		o.addProperty("c", 50000 + t * 30);
		return o;
	}

	private static JsonObject actor(int t, String ref, String name, boolean self)
	{
		JsonObject o = rec("actor", t);
		o.addProperty("r", ref);
		o.addProperty("name", name);
		if (self)
		{
			o.addProperty("self", true);
		}
		return o;
	}

	private String json(JsonObject o)
	{
		return gson.toJson(o);
	}
}
