package com.osmm.capture;

import net.runelite.api.Constants;
import net.runelite.api.Hitsplat;

/**
 * "Start in combat": whether you're in combat, and when that starts or stops a recording. Fed on the client thread
 * (signs of combat as they happen, then each game tick); plain logic, no client, so it's tested on its own.
 *
 * <p>Combat begins with a sign of it (see {@link #hitsplat}) and ends once there's been none for the timeout, nor any
 * tick with you facing an attackable target.
 *
 * <p>A hit on you is a sign of combat only when there's someone to fight: an attackable actor (another player, or an
 * NPC with a combat level) faced you on that tick or within {@link #ATTACKER_GRACE_TICKS} before it, or you face an
 * attackable target. Self-inflicted damage (a rock cake, divine potions, a Zamorak brew, the locator orb, a Guthix
 * rest, Nightmare Zone overloads) has no attacker, so it starts nothing. Who faces whom is used only for this
 * decision: none of it is shown.
 */
final class CombatWatch
{
	/** What's recording now, as far as this cares. */
	enum Recording
	{
		NONE,
		OTHER,    // started by hand or on login: combat leaves it alone
		COMBAT    // started by combat: it stops once out of combat
	}

	enum Action
	{
		NONE,
		START,
		STOP
	}

	/** How long combat lasts after the last sign of it when "Stop after" is 0 (never): for holding off after a stop. */
	static final int NEVER_TIMEOUT = 50;

	/**
	 * How many ticks an attacker that has stopped facing you still counts for a hit on you: 5 (3 seconds). A hit can
	 * land well after the attack, and the attacker can turn away (walk off, pick another target, die) the tick after
	 * attacking. The longest usual delay is a spell from 10 tiles, which lands 4 ticks after the cast (1 + (1 +
	 * distance) / 3; ranged from 10 tiles is 3: 1 + (3 + distance) / 6), and one more tick covers who-faces-whom being
	 * sampled once a tick. Any longer and a rock cake eaten a few seconds after a fight would count again.
	 */
	static final int ATTACKER_GRACE_TICKS = 5;

	private int now;                // ticks seen
	private int lastActive;         // the last tick with a sign of combat, or facing an attackable target in combat
	private int lastAttacker = -ATTACKER_GRACE_TICKS - 1;   // the last tick an attackable actor faced you (none yet)
	private boolean seen;           // a sign of combat since the last tick
	private boolean hitTaken;       // a combat hitsplat on you since the last tick, judged at the tick
	private boolean inCombat;
	private boolean heldOff;        // you stopped a recording during this fight

	/**
	 * Whether a hitsplat's type is an attack's: a damage or block hitsplat on you (whoever's), or one of yours on
	 * someone else. Heals, poison, venom, disease, burn and the other non-attack types are neither "mine" nor
	 * "others'" in the API, so they don't count. (Whether a hit on you counts also needs someone to fight: see
	 * {@link #hitsplat}.)
	 */
	static boolean isCombatHit(boolean onLocalPlayer, Hitsplat h)
	{
		return onLocalPlayer ? h.isMine() || h.isOthers() : h.isMine();
	}

	/**
	 * A hitsplat on anyone (client thread, as it's applied). One of yours on someone else is a sign of combat at once.
	 * One on you is judged at the next tick, once who faces you that tick is known: it counts only with an attacker
	 * on you or you on a target (see {@link #onTick}). A hit on you never counts as one you dealt, even when its type
	 * is "mine": {@link Hitsplat#isMine()} only reads the type, which can't tell who caused it, so self-inflicted
	 * damage may well be "mine".
	 */
	void hitsplat(boolean onLocalPlayer, Hitsplat h)
	{
		if (!isCombatHit(onLocalPlayer, h))
		{
			return;
		}
		if (onLocalPlayer)
		{
			hitTaken = true;
		}
		else
		{
			combatSeen();
		}
	}

	/** Game ticks for a number of seconds, rounded up. */
	static int ticks(int seconds)
	{
		int ms = Math.max(0, seconds) * 1000;
		return (ms + Constants.GAME_TICK_LENGTH - 1) / Constants.GAME_TICK_LENGTH;
	}

	/** "Stop after (seconds) out of combat" in ticks: 0 for never, else at least 5 seconds' worth. */
	static int stopTicks(int seconds)
	{
		return seconds <= 0 ? 0 : ticks(Math.min(600, Math.max(5, seconds)));
	}

	/** "Seconds kept from before combat" in ticks (0 to 30 seconds). */
	static int leadTicks(int seconds)
	{
		return ticks(Math.min(30, seconds));
	}

	/** A sign of combat: a hit that counts. */
	void combatSeen()
	{
		seen = true;
	}

	/**
	 * Call once a game tick, before anything is recorded for it.
	 *
	 * @param attackerOnMe       an attackable actor faces you (lets a hit on you count, now and for the grace ticks)
	 * @param onAttackableTarget you're facing an attackable target (lets a hit on you count, and keeps a fight going
	 *                           between hits)
	 * @param option             "Start in combat" is on
	 * @param stopTicks          {@link #stopTicks}: ticks out of combat before a combat-started recording stops (0 never)
	 * @param recording          what's recording now
	 */
	Action onTick(boolean attackerOnMe, boolean onAttackableTarget, boolean option, int stopTicks, Recording recording)
	{
		now++;
		if (attackerOnMe)
		{
			lastAttacker = now;
		}
		if (hitTaken)
		{
			hitTaken = false;
			if (onAttackableTarget || now - lastAttacker <= ATTACKER_GRACE_TICKS)
			{
				seen = true;
			}
		}
		if (seen)
		{
			seen = false;
			inCombat = true;
			lastActive = now;
		}
		else if (inCombat && onAttackableTarget)
		{
			lastActive = now;
		}
		if (inCombat && now - lastActive >= (stopTicks > 0 ? stopTicks : NEVER_TIMEOUT))
		{
			inCombat = false;
			heldOff = false;
		}
		if (recording == Recording.COMBAT && !inCombat && stopTicks > 0)
		{
			return Action.STOP;
		}
		if (recording == Recording.NONE && inCombat && option && !heldOff)
		{
			return Action.START;
		}
		return Action.NONE;
	}

	/** You stopped a recording (any kind): in a fight, none starts again until it's over and another begins. */
	void userStopped()
	{
		if (inCombat)
		{
			heldOff = true;
		}
	}

	boolean inCombat()
	{
		return inCombat;
	}

	/** Not starting one until this fight ends (you stopped one during it). */
	boolean heldOff()
	{
		return heldOff;
	}
}
