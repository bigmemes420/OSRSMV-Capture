package com.osmm.capture;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(OsmmCaptureConfig.GROUP)
public interface OsmmCaptureConfig extends Config
{
	String GROUP = "osmmcapture";

	enum Scope
	{
		FIGHT,   // the local player and whoever it fights
		NEARBY   // every player and NPC within the radius
	}

	@ConfigItem(keyName = "showIndicator", name = "Show recording indicator",
		description = "A small box in game while recording or waiting for combat. Alt-drag to move it", position = 0)
	default boolean showIndicator()
	{
		return true;
	}

	@ConfigItem(keyName = "anonymise", name = "Anonymise player names",
		description = "Writes each player as a code such as Player 7F3A9C instead of their name; the same player always"
			+ " gets the same code", position = 1)
	default boolean anonymise()
	{
		return true;
	}

	@ConfigItem(keyName = "autoStart", name = "Start on login",
		description = "Start a recording when logged in (else type ::rec start)", position = 2)
	default boolean autoStart()
	{
		return false;
	}

	@ConfigItem(keyName = "combatStart", name = "Start in combat",
		description = "Start a recording when you hit something or something attacking you hits you, and stop it once"
			+ " out of combat", position = 3)
	default boolean combatStart()
	{
		return false;
	}

	@Range(max = 600)
	@ConfigItem(keyName = "combatStopSeconds", name = "Stop after (seconds) out of combat",
		description = "For recordings started in combat: 5 to 600 seconds, or 0 to never stop on its own", position = 4)
	default int combatStopSeconds()
	{
		return 30;
	}

	@Range(max = 30)
	@ConfigItem(keyName = "combatLeadSeconds", name = "Seconds kept from before combat",
		description = "Recordings started in combat begin this long before the first hit, to catch the opening attack",
		position = 5)
	default int combatLeadSeconds()
	{
		return 6;
	}

	@ConfigItem(keyName = "scope", name = "Who to record",
		description = "FIGHT: you and whoever you fight. NEARBY: everyone within the radius", position = 6)
	default Scope scope()
	{
		return Scope.FIGHT;
	}

	@Range(min = 1, max = 32)
	@ConfigItem(keyName = "radius", name = "Radius (tiles)",
		description = "For NEARBY, graphics objects and projectiles", position = 7)
	default int radius()
	{
		return 15;
	}

	@ConfigItem(keyName = "motion", name = "Per-cycle motion",
		description = "Position, animation and frame every 20 ms when they change", position = 8)
	default boolean motion()
	{
		return true;
	}

	@ConfigItem(keyName = "sounds", name = "Sounds",
		description = "Sound effects and area sounds", position = 9)
	default boolean sounds()
	{
		return true;
	}

	@ConfigItem(keyName = "projectiles", name = "Projectiles",
		description = "Each projectile once", position = 10)
	default boolean projectiles()
	{
		return true;
	}

	@ConfigItem(keyName = "gameMessages", name = "Game messages",
		description = "Game and filtered messages", position = 11)
	default boolean gameMessages()
	{
		return true;
	}

	@ConfigItem(keyName = "localStats", name = "Your stats and spec",
		description = "Combat stats, special energy, run", position = 12)
	default boolean localStats()
	{
		return true;
	}
}
