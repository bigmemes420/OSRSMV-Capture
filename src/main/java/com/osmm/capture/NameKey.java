package com.osmm.capture;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import net.runelite.client.util.Filepath;

/**
 * The secret key for "Anonymise player names" (see {@link PlayerNames}): 32 random bytes, made the first time it's
 * needed and kept in the plugin's folder as {@value #FILE}, so a player keeps the same code across recordings and
 * restarts. It's outside the recordings folder (RecordingsFolder), so sharing that folder doesn't share the key. It's
 * kept only there: not in RuneLite's config, which can sync to RuneLite's servers. Read and written off
 * the client thread. Never logged.
 */
final class NameKey
{
	static final String FILE = "anonymise.key";
	static final int BYTES = 32;
	private static final int MIN_BYTES = 16;     // a shorter file (cut short, emptied) is made again
	private static final int MAX_READ = 1024;

	/** Where the key is kept. The plugin's is the file in its folder; the tests use their own. */
	interface Store
	{
		/** The key's bytes, or null when there's none yet. */
		byte[] read() throws IOException;

		void write(byte[] key) throws IOException;
	}

	private NameKey()
	{
	}

	/** The key kept in the store, or a new one made and kept there when there's none (or it's too short to be one). */
	static byte[] loadOrCreate(Store store, SecureRandom random) throws IOException
	{
		byte[] key = store.read();
		if (key != null && key.length >= MIN_BYTES)
		{
			return key;
		}
		key = new byte[BYTES];
		random.nextBytes(key);
		store.write(key);
		return key;
	}

	/** {@value #FILE} in the plugin's folder (the folder is made when the key is first written). */
	static Store in(Filepath dir)
	{
		return new Store()
		{
			@Override
			public byte[] read() throws IOException
			{
				Filepath f = dir.joinSegment(FILE);
				if (!f.isFile())
				{
					return null;
				}
				try (InputStream in = f.openInputStream())
				{
					return in.readNBytes(MAX_READ);
				}
			}

			@Override
			public void write(byte[] key) throws IOException
			{
				dir.createDirectories();
				dir.joinSegment(FILE).write(key, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
					StandardOpenOption.WRITE);
			}
		};
	}
}
