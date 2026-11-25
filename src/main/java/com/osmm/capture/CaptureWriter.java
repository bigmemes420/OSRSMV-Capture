package com.osmm.capture;

import java.io.BufferedWriter;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import net.runelite.client.util.Filepath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes a capture's lines on a background thread, through Filepath. All its disk work (making the folder, opening,
 * writing, flushing and closing the file) happens on that thread, so neither the client thread nor the EDT ever waits
 * on the disk, and nothing here blocks the caller.
 */
final class CaptureWriter
{
	private static final Logger log = LoggerFactory.getLogger(CaptureWriter.class);

	@SuppressWarnings("StringOperationCanBeSimplified")
	private static final String STOP = new String("<stop>");   // compared by identity

	private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
	private final Filepath dir;
	private final String name;
	private final BiConsumer<CaptureWriter, Exception> onFailure;
	private final CompletableFuture<Void> closed = new CompletableFuture<>();
	private volatile boolean stopped;

	/**
	 * Starts writing a new file, name, in dir (made if missing). If it can't be opened or written, onFailure is called
	 * once, on the writer's thread, and the lines after are dropped.
	 */
	CaptureWriter(Filepath dir, String name, BiConsumer<CaptureWriter, Exception> onFailure)
	{
		this.dir = dir;
		this.name = name;
		this.onFailure = onFailure;
		Thread thread = new Thread(this::run, "osmm-capture-writer");
		thread.setDaemon(true);
		thread.start();
	}

	void write(String line)
	{
		if (!stopped)
		{
			queue.add(line);
		}
	}

	/** Ends the file: what's queued is written, then it's closed, on the writer's thread. Doesn't wait for it. */
	void close()
	{
		if (!stopped)
		{
			stopped = true;
			queue.add(STOP);
		}
	}

	/** Done once the file is closed (or couldn't be written). */
	CompletableFuture<Void> whenClosed()
	{
		return closed;
	}

	private void run()
	{
		try
		{
			dir.createDirectories();
			try (BufferedWriter out = dir.joinSegment(name).openBufferedWriter(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
			{
				for (;;)
				{
					String line = queue.poll(1, TimeUnit.SECONDS);
					if (line == null)
					{
						out.flush();   // at most a second's lines lost on a crash
						continue;
					}
					if (line == STOP)
					{
						break;
					}
					out.write(line);
					out.write('\n');
				}
			}
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
		catch (Exception e)   // IOException, or IllegalArgumentException from a bad name
		{
			stopped = true;
			queue.clear();
			log.warn("osmm-capture: can't write {}", name, e);
			onFailure.accept(this, e);
		}
		finally
		{
			closed.complete(null);
		}
	}
}
