package dev.rlsideloader.manager;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Watches plugin sources and calls back (debounced) when a row's files change.
 * <ul>
 *     <li>Project rows: everything under src/, plus the project's Gradle build files.</li>
 *     <li>Jar rows: the jar file itself.</li>
 * </ul>
 */
final class ChangeWatcher implements AutoCloseable
{
	private static final long DEBOUNCE_MS = 1500;
	private static final Set<String> BUILD_FILES = Set.of(
		"build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "gradle.properties");

	/** What a watch key is for: a row, and optionally the only file name in the folder that matters */
	private static final class Target
	{
		private final String rowKey;
		private final Path dir;
		private final Set<String> onlyFiles;
		private final boolean recursive;

		Target(String rowKey, Path dir, Set<String> onlyFiles, boolean recursive)
		{
			this.rowKey = rowKey;
			this.dir = dir;
			this.onlyFiles = onlyFiles;
			this.recursive = recursive;
		}

		String rowKey()
		{
			return rowKey;
		}

		Path dir()
		{
			return dir;
		}

		Set<String> onlyFiles()
		{
			return onlyFiles;
		}

		boolean recursive()
		{
			return recursive;
		}
	}

	private final WatchService watchService;
	private final Map<WatchKey, Target> targets = new ConcurrentHashMap<>();
	private final Map<String, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
	private final ScheduledExecutorService debouncer = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "watch-debounce"));
	private final Consumer<String> onChange;

	ChangeWatcher(Consumer<String> onChange) throws IOException
	{
		this.onChange = onChange;
		this.watchService = FileSystems.getDefault().newWatchService();
		daemon(this::loop, "watcher").start();
	}

	synchronized void watch(PluginRow row) throws IOException
	{
		unwatch(row);
		if (row.kind() == PluginRow.Kind.PROJECT)
		{
			register(row.key(), row.path(), BUILD_FILES, false);
			Path src = row.path().resolve("src");
			if (Files.isDirectory(src))
			{
				registerTree(row.key(), src);
			}
		}
		else
		{
			register(row.key(), row.path().getParent(), Set.of(row.path().getFileName().toString()), false);
		}
	}

	synchronized void unwatch(PluginRow row)
	{
		targets.entrySet().removeIf(e ->
		{
			if (e.getValue().rowKey().equals(row.key()))
			{
				e.getKey().cancel();
				return true;
			}
			return false;
		});
		ScheduledFuture<?> f = pending.remove(row.key());
		if (f != null)
		{
			f.cancel(false);
		}
	}

	private void registerTree(String rowKey, Path root) throws IOException
	{
		Files.walkFileTree(root, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException
			{
				register(rowKey, dir, null, true);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	private void register(String rowKey, Path dir, Set<String> onlyFiles, boolean recursive) throws IOException
	{
		WatchKey key = dir.register(watchService,
			StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
		targets.put(key, new Target(rowKey, dir, onlyFiles, recursive));
	}

	private void loop()
	{
		while (true)
		{
			WatchKey key;
			try
			{
				key = watchService.take();
			}
			catch (InterruptedException | ClosedWatchServiceException e)
			{
				return;
			}

			Target target = targets.get(key);
			if (target != null)
			{
				boolean relevant = false;
				for (WatchEvent<?> event : key.pollEvents())
				{
					if (!(event.context() instanceof Path))
					{
						relevant = true; // overflow: assume something changed
						continue;
					}
					Path name = (Path) event.context();
					if (target.onlyFiles() != null && !target.onlyFiles().contains(name.toString()))
					{
						continue;
					}
					relevant = true;

					Path child = target.dir().resolve(name);
					if (target.recursive() && event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(child))
					{
						try
						{
							synchronized (this)
							{
								registerTree(target.rowKey(), child);
							}
						}
						catch (IOException ignored)
						{
						}
					}
				}
				if (relevant)
				{
					schedule(target.rowKey());
				}
			}
			else
			{
				key.pollEvents();
			}

			if (!key.reset())
			{
				targets.remove(key);
			}
		}
	}

	private void schedule(String rowKey)
	{
		pending.compute(rowKey, (k, previous) ->
		{
			if (previous != null)
			{
				previous.cancel(false);
			}
			return debouncer.schedule(() ->
			{
				pending.remove(k);
				onChange.accept(k);
			}, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
		});
	}

	private static Thread daemon(Runnable r, String name)
	{
		Thread t = new Thread(r, "rl-sideloader-" + name);
		t.setDaemon(true);
		return t;
	}

	@Override
	public void close() throws IOException
	{
		debouncer.shutdownNow();
		watchService.close();
	}
}
