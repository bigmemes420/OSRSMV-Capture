package com.osmm.capture;

import static net.runelite.api.GameState.CONNECTION_LOST;
import static net.runelite.api.GameState.HOPPING;
import static net.runelite.api.GameState.LOADING;
import static net.runelite.api.GameState.LOGGED_IN;
import static net.runelite.api.GameState.LOGGING_IN;
import static net.runelite.api.GameState.LOGIN_SCREEN;
import static net.runelite.api.GameState.STARTING;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.GameState;
import org.junit.Test;

/** "Start on login": which game state sequences start a recording on their own. Plain JUnit, no client. */
public class AutoStartTest
{
	private static final String STOP = "stop";     // you stop the recording (panel or ::rec stop)
	private static final String START = "start";   // you start one by hand

	/**
	 * Plays a sequence through the plugin's LoginWatch the way onGameStateChanged does (the login screen and a hop end
	 * a recording) and counts the recordings it starts on its own.
	 */
	private static int autoStarts(boolean option, Object... steps)
	{
		OsmmCapturePlugin.LoginWatch watch = new OsmmCapturePlugin.LoginWatch();
		boolean recording = false;
		int starts = 0;
		for (Object step : steps)
		{
			if (step == STOP)
			{
				if (recording)
				{
					watch.userStopped();
				}
				recording = false;
			}
			else if (step == START)
			{
				recording = true;
			}
			else
			{
				GameState s = (GameState) step;
				boolean start = watch.onState(s, option, recording);
				if (s == LOGIN_SCREEN || s == HOPPING)
				{
					recording = false;
				}
				if (start)
				{
					assertEquals("only a LOGGED_IN starts one", LOGGED_IN, s);
					recording = true;
					starts++;
				}
			}
		}
		return starts;
	}

	private static final Object[] LOGIN = {LOGIN_SCREEN, LOGGING_IN, LOADING, LOGGED_IN};
	private static final Object[] TELEPORT = {LOADING, LOGGED_IN};

	private static Object[] seq(Object... parts)
	{
		List<Object> out = new ArrayList<>();
		for (Object p : parts)
		{
			if (p instanceof Object[])
			{
				out.addAll(Arrays.asList((Object[]) p));
			}
			else
			{
				out.add(p);
			}
		}
		return out.toArray();
	}

	@Test
	public void loggingInStartsOne()
	{
		assertEquals(1, autoStarts(true, seq(STARTING, LOGIN)));
		assertEquals(1, autoStarts(true, seq(STARTING, LOGIN_SCREEN, LOGGING_IN, LOGGED_IN)));
	}

	@Test
	public void nothingWithTheOptionOff()
	{
		assertEquals(0, autoStarts(false, seq(STARTING, LOGIN, TELEPORT, HOPPING, LOADING, LOGGED_IN)));
	}

	@Test
	public void teleportsDontStartOne()
	{
		// the old bug: every map load after a stop started a new recording
		assertEquals(1, autoStarts(true, seq(STARTING, LOGIN, TELEPORT, STOP, TELEPORT, TELEPORT, TELEPORT)));
		// recording through teleports: still the one
		assertEquals(1, autoStarts(true, seq(STARTING, LOGIN, TELEPORT, TELEPORT)));
	}

	@Test
	public void stoppedWaitsForTheNextLogin()
	{
		assertEquals(1, autoStarts(true, seq(STARTING, LOGIN, STOP, TELEPORT)));
		assertEquals(2, autoStarts(true, seq(STARTING, LOGIN, STOP, TELEPORT, LOGIN)));
		// a dropped connection isn't a new session: no new recording after you stopped one
		assertEquals(1, autoStarts(true, seq(STARTING, LOGIN, STOP, CONNECTION_LOST, LOGGED_IN)));
		// started and stopped by hand: likewise
		assertEquals(1, autoStarts(true, seq(STARTING, LOGIN, STOP, START, STOP, TELEPORT)));
	}

	@Test
	public void aHopStartsAnother()
	{
		assertEquals(2, autoStarts(true, seq(STARTING, LOGIN, HOPPING, LOADING, LOGGED_IN)));
		assertEquals(2, autoStarts(true, seq(STARTING, LOGIN, HOPPING, LOGGED_IN)));
		assertEquals(2, autoStarts(true, seq(STARTING, LOGIN, STOP, HOPPING, LOADING, LOGGED_IN)));
		assertEquals(2, autoStarts(true, seq(STARTING, LOGIN, HOPPING, LOGGING_IN, LOADING, LOGGED_IN, TELEPORT)));
	}

	@Test
	public void aLostConnectionKeepsTheRecording()
	{
		// still recording when it comes back: no second one
		assertEquals(1, autoStarts(true, seq(STARTING, LOGIN, CONNECTION_LOST, LOADING, LOGGED_IN)));
	}

	@Test
	public void turnedOnWhileLoggedIn()
	{
		// the plugin's start feeds the current state: the first LOGGED_IN it sees counts as logging in
		assertEquals(1, autoStarts(true, LOGGED_IN));
		assertEquals(1, autoStarts(true, seq(LOGGED_IN, TELEPORT, TELEPORT)));
		assertEquals(1, autoStarts(true, seq(LOADING, LOGGED_IN, TELEPORT)));
		assertEquals(1, autoStarts(true, seq(LOGGED_IN, STOP, TELEPORT)));
	}

	@Test
	public void manualRecordingIsLeftAlone()
	{
		// a recording already running (started by hand) is never doubled
		assertFalse(OsmmCapturePlugin.shouldAutoStart(LOGIN_SCREEN, LOGGED_IN, true, true, false));
	}

	@Test
	public void theDecision()
	{
		assertTrue(OsmmCapturePlugin.shouldAutoStart(null, LOGGED_IN, true, false, false));
		assertTrue(OsmmCapturePlugin.shouldAutoStart(LOGIN_SCREEN, LOGGED_IN, true, false, false));
		assertTrue(OsmmCapturePlugin.shouldAutoStart(LOGGING_IN, LOGGED_IN, true, false, false));
		assertTrue(OsmmCapturePlugin.shouldAutoStart(CONNECTION_LOST, LOGGED_IN, true, false, false));
		assertTrue(OsmmCapturePlugin.shouldAutoStart(HOPPING, LOGGED_IN, true, false, false));
		assertFalse("a map load", OsmmCapturePlugin.shouldAutoStart(LOGGED_IN, LOGGED_IN, true, false, false));
		assertFalse("option off", OsmmCapturePlugin.shouldAutoStart(LOGIN_SCREEN, LOGGED_IN, false, false, false));
		assertFalse("you stopped one", OsmmCapturePlugin.shouldAutoStart(CONNECTION_LOST, LOGGED_IN, true, false, true));
		for (GameState s : GameState.values())
		{
			if (s != LOGGED_IN)
			{
				assertFalse(s.name(), OsmmCapturePlugin.shouldAutoStart(LOGIN_SCREEN, s, true, false, false));
			}
		}
	}
}
