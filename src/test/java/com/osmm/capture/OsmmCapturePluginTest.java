package com.osmm.capture;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Starts RuneLite with the capture plugin loaded. Run through the `run` Gradle task (it adds -ea and --developer-mode). */
public class OsmmCapturePluginTest
{
	@SuppressWarnings("unchecked")
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(OsmmCapturePlugin.class);
		RuneLite.main(args);
	}
}
