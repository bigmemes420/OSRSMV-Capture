package com.osmm.capture;

import static com.osmm.capture.CombatWatch.Action.NONE;
import static com.osmm.capture.CombatWatch.Action.START;
import static com.osmm.capture.CombatWatch.Action.STOP;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import org.junit.Test;

/** "Start in combat": when CombatWatch starts and stops a recording. Plain JUnit, no client. */
public class CombatWatchTest
{
	private static final int STOP_TICKS = 50;   // 30 seconds

	/** A watch and what's recording, played the way the plugin's onGameTick plays it. */
	private static final class Game
	{
		final CombatWatch watch = new CombatWatch();
		CombatWatch.Recording recording = CombatWatch.Recording.NONE;
		boolean option = true;
		int stopTicks = STOP_TICKS;
		boolean onTarget;
		boolean attackerOnMe;   // an attackable actor faces you

		/** One tick; a START or STOP is acted on as the plugin does. */
		CombatWatch.Action tick()
		{
			CombatWatch.Action a = watch.onTick(attackerOnMe, onTarget, option, stopTicks, recording);
			if (a == START)
			{
				recording = CombatWatch.Recording.COMBAT;
			}
			else if (a == STOP)
			{
				recording = CombatWatch.Recording.NONE;
			}
			return a;
		}

		/** Quiet ticks until something happens; returns how many were played (up to max). */
		int quietUntilAction(int max)
		{
			for (int i = 1; i <= max; i++)
			{
				if (tick() != NONE)
				{
					return i;
				}
			}
			return max;
		}

		void hit()
		{
			watch.combatSeen();
		}

		/** A damage hitsplat on you, as the plugin passes it on. */
		void hitOnMe()
		{
			watch.hitsplat(true, splat(HitsplatID.DAMAGE_ME));
		}

		/** You stop it from the panel or with ::rec stop. */
		void userStop()
		{
			watch.userStopped();
			recording = CombatWatch.Recording.NONE;
		}
	}

	private static Hitsplat splat(int type)
	{
		return new Hitsplat()
		{
			@Override
			public int getHitsplatType()
			{
				return type;
			}

			@Override
			public int getAmount()
			{
				return 3;
			}

			@Override
			public int getDisappearsOnGameCycle()
			{
				return 0;
			}
		};
	}

	@Test
	public void startsOnAHitTaken()
	{
		assertTrue(CombatWatch.isCombatHit(true, splat(HitsplatID.DAMAGE_ME)));
		assertTrue("a 0 on you", CombatWatch.isCombatHit(true, splat(HitsplatID.BLOCK_ME)));
		assertTrue(CombatWatch.isCombatHit(true, splat(HitsplatID.DAMAGE_MAX_ME)));
		Game g = new Game();
		assertEquals(NONE, g.tick());
		g.hit();
		assertEquals(START, g.tick());
		assertEquals(NONE, g.tick());   // once
	}

	@Test
	public void startsOnAHitDealt()
	{
		assertTrue(CombatWatch.isCombatHit(false, splat(HitsplatID.DAMAGE_ME)));
		assertTrue("your 0", CombatWatch.isCombatHit(false, splat(HitsplatID.BLOCK_ME)));
		assertTrue(CombatWatch.isCombatHit(false, splat(HitsplatID.DAMAGE_ME_ORANGE)));
		assertFalse("someone else's hit on someone else", CombatWatch.isCombatHit(false, splat(HitsplatID.DAMAGE_OTHER)));
		assertFalse(CombatWatch.isCombatHit(false, splat(HitsplatID.BLOCK_OTHER)));
	}

	@Test
	public void healsAndPoisonDontStart()
	{
		int[] notCombat = {HitsplatID.HEAL, HitsplatID.POISON, HitsplatID.VENOM, HitsplatID.DISEASE,
			HitsplatID.DISEASE_BLOCKED, HitsplatID.BURN, HitsplatID.CORRUPTION, HitsplatID.PRAYER_DRAIN,
			HitsplatID.BLEED, HitsplatID.DOOM, HitsplatID.SANITY_DRAIN, HitsplatID.SANITY_RESTORE};
		for (int type : notCombat)
		{
			assertFalse("on you: " + type, CombatWatch.isCombatHit(true, splat(type)));
			assertFalse("on others: " + type, CombatWatch.isCombatHit(false, splat(type)));
		}
		// and with nothing seen, nothing starts
		Game g = new Game();
		assertEquals(200, g.quietUntilAction(200));
		assertFalse(g.watch.inCombat());
	}

	@Test
	public void stopsAfterTheTimeOutOfCombat()
	{
		Game g = new Game();
		g.hit();
		assertEquals(START, g.tick());
		// the 50th quiet tick after the hit
		assertEquals(STOP_TICKS, g.quietUntilAction(1000));
		assertEquals(CombatWatch.Recording.NONE, g.recording);
		assertFalse(g.watch.inCombat());
		assertEquals("no restart without a new fight", 300, g.quietUntilAction(300));
		g.hit();
		assertEquals("a new fight starts another", START, g.tick());
	}

	@Test
	public void aHitDuringTheCountdownResetsIt()
	{
		Game g = new Game();
		g.hit();
		g.tick();
		for (int i = 0; i < STOP_TICKS - 1; i++)
		{
			assertEquals(NONE, g.tick());
		}
		g.hit();   // just in time
		assertEquals(NONE, g.tick());
		assertEquals(STOP_TICKS, g.quietUntilAction(1000));
	}

	@Test
	public void facingAnAttackableTargetKeepsItGoing()
	{
		// splashing, or between slow hits: no hitsplats, but you're still on your target
		Game g = new Game();
		g.hit();
		g.tick();
		g.onTarget = true;
		assertEquals(500, g.quietUntilAction(500));
		g.onTarget = false;
		assertEquals(STOP_TICKS, g.quietUntilAction(1000));
	}

	@Test
	public void facingATargetAloneDoesntStart()
	{
		Game g = new Game();
		g.onTarget = true;
		assertEquals(100, g.quietUntilAction(100));
	}

	@Test
	public void aManualStopIsntUndoneUntilTheNextFight()
	{
		Game g = new Game();
		g.hit();
		assertEquals(START, g.tick());
		g.tick();
		g.userStop();
		assertTrue(g.watch.heldOff());
		// still fighting: no new recording
		for (int i = 0; i < 20; i++)
		{
			g.hit();
			assertEquals(NONE, g.tick());
		}
		// the fight ends (the timeout with no hits) ...
		assertEquals(STOP_TICKS + 5, g.quietUntilAction(STOP_TICKS + 5));
		assertFalse(g.watch.heldOff());
		// ... and the next one starts a recording
		g.hit();
		assertEquals(START, g.tick());
	}

	@Test
	public void stoppingOutsideAFightHoldsNothingOff()
	{
		Game g = new Game();
		g.recording = CombatWatch.Recording.OTHER;   // a manual recording, stopped while not fighting
		g.userStop();
		assertFalse(g.watch.heldOff());
		g.hit();
		assertEquals(START, g.tick());
	}

	@Test
	public void zeroNeverStops()
	{
		Game g = new Game();
		g.stopTicks = 0;
		g.hit();
		assertEquals(START, g.tick());
		assertEquals(5000, g.quietUntilAction(5000));
		assertEquals(CombatWatch.Recording.COMBAT, g.recording);
		// combat itself still ends (for holding off after a manual stop)
		assertFalse(g.watch.inCombat());
		g.hit();
		assertEquals("a new fight while it's still recording: nothing", NONE, g.tick());
	}

	@Test
	public void zeroStillHoldsOffAfterAManualStop()
	{
		Game g = new Game();
		g.stopTicks = 0;
		g.hit();
		g.tick();
		g.userStop();
		assertEquals(CombatWatch.NEVER_TIMEOUT, g.quietUntilAction(CombatWatch.NEVER_TIMEOUT));
		assertFalse(g.watch.heldOff());
		g.hit();
		assertEquals(START, g.tick());
	}

	@Test
	public void aRecordingAlreadyRunningIsLeftAlone()
	{
		Game g = new Game();
		g.recording = CombatWatch.Recording.OTHER;   // started by hand or on login
		g.hit();
		assertEquals(NONE, g.tick());
		assertTrue(g.watch.inCombat());
		assertEquals("never stopped out of combat", 1000, g.quietUntilAction(1000));
		assertEquals(CombatWatch.Recording.OTHER, g.recording);
	}

	@Test
	public void nothingWithTheOptionOff()
	{
		Game g = new Game();
		g.option = false;
		g.hit();
		assertEquals(NONE, g.tick());
		assertTrue(g.watch.inCombat());
		// turned on mid-fight: starts at once
		g.option = true;
		g.hit();
		assertEquals(START, g.tick());
	}

	@Test
	public void secondsToTicks()
	{
		assertEquals(50, CombatWatch.stopTicks(30));
		assertEquals(0, CombatWatch.stopTicks(0));
		assertEquals("at least 5 seconds", CombatWatch.ticks(5), CombatWatch.stopTicks(1));
		assertEquals(9, CombatWatch.stopTicks(5));
		assertEquals(1000, CombatWatch.stopTicks(600));
		assertEquals(10, CombatWatch.leadTicks(6));
		assertEquals(0, CombatWatch.leadTicks(0));
		assertEquals(50, CombatWatch.leadTicks(30));
		assertEquals(50, CombatWatch.leadTicks(99));
	}

	// ---- a hit on you counts only with someone to fight ----

	@Test
	public void aHitOnYouWithNobodyFacingYouStartsNothing()
	{
		// a rock cake (or a divine potion, a Zamorak brew, the locator orb, an overload): damage, no attacker
		Game g = new Game();
		for (int i = 0; i < 10; i++)
		{
			g.hitOnMe();
			assertEquals(NONE, g.tick());
		}
		assertFalse(g.watch.inCombat());
		assertEquals(200, g.quietUntilAction(200));
	}

	@Test
	public void aSelfHitMarkedMineStartsNothing()
	{
		// the API's "mine" only reads the type: damage you do to yourself can be "mine"
		int[] mine = {HitsplatID.DAMAGE_ME, HitsplatID.BLOCK_ME, HitsplatID.DAMAGE_MAX_ME, HitsplatID.DAMAGE_ME_ORANGE};
		for (int type : mine)
		{
			assertTrue(splat(type).isMine());
			Game g = new Game();
			g.watch.hitsplat(true, splat(type));
			assertEquals("type " + type, NONE, g.tick());
			assertFalse(g.watch.inCombat());
		}
		// the same hit on someone else is one you dealt
		Game g = new Game();
		g.watch.hitsplat(false, splat(HitsplatID.DAMAGE_ME));
		assertEquals(START, g.tick());
	}

	@Test
	public void aHitOnYouWithAnAttackerFacingYouStarts()
	{
		Game g = new Game();
		g.attackerOnMe = true;
		assertEquals(NONE, g.tick());   // facing you alone starts nothing
		g.hitOnMe();
		assertEquals(START, g.tick());

		// "mine" or "others'" on you: either way
		Game o = new Game();
		o.attackerOnMe = true;
		o.watch.hitsplat(true, splat(HitsplatID.DAMAGE_OTHER));
		assertEquals(START, o.tick());

		// a heal or poison still doesn't count, attacker or not
		Game h = new Game();
		h.attackerOnMe = true;
		h.watch.hitsplat(true, splat(HitsplatID.HEAL));
		h.watch.hitsplat(true, splat(HitsplatID.POISON));
		assertEquals(NONE, h.tick());
	}

	@Test
	public void anAttackerTurnedAwayCountsForTheGraceTicksOnly()
	{
		int grace = CombatWatch.ATTACKER_GRACE_TICKS;
		// the attacker faces you for a tick, then turns away; its hit lands grace ticks later: counts
		Game g = new Game();
		g.attackerOnMe = true;
		g.tick();
		g.attackerOnMe = false;
		for (int i = 1; i < grace; i++)
		{
			assertEquals(NONE, g.tick());
		}
		g.hitOnMe();
		assertEquals("judged " + grace + " ticks after it last faced you", START, g.tick());

		// one tick later: doesn't
		Game late = new Game();
		late.attackerOnMe = true;
		late.tick();
		late.attackerOnMe = false;
		for (int i = 1; i <= grace; i++)
		{
			late.tick();
		}
		late.hitOnMe();
		assertEquals(NONE, late.tick());
		assertFalse(late.watch.inCombat());
	}

	@Test
	public void anAttackerTurnedAwayKeepsAFightGoingForTheGraceTicksOnly()
	{
		int grace = CombatWatch.ATTACKER_GRACE_TICKS;
		Game g = new Game();
		g.stopTicks = CombatWatch.stopTicks(5);
		g.attackerOnMe = true;
		g.hitOnMe();
		assertEquals(START, g.tick());
		g.attackerOnMe = false;   // it turns away; damage on you goes on (a rock cake, say)
		int played = 0;
		CombatWatch.Action a = NONE;
		while (a == NONE && played < 1000)
		{
			g.hitOnMe();
			a = g.tick();
			played++;
		}
		// the hits within the grace ticks kept it going; the ones after didn't
		assertEquals(STOP, a);
		assertEquals(grace + g.stopTicks, played);
	}

	@Test
	public void aHitOnYouWhileYouFaceAnAttackableTargetCounts()
	{
		Game g = new Game();
		g.onTarget = true;
		assertEquals(NONE, g.tick());
		g.hitOnMe();
		assertEquals(START, g.tick());
	}
}
