package dev.rlsideloader.agent;

import com.google.inject.Injector;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import javax.swing.SwingUtilities;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.ExternalPluginsChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads and unloads plugin jars at runtime, the same way RuneLite's
 * ExternalPluginManager installs and removes Plugin Hub plugins.
 */
final class PluginHost
{
	private static final Logger log = LoggerFactory.getLogger(PluginHost.class);

	private final PluginManager pluginManager;
	private final EventBus eventBus;
	private final Path shadowDir;
	private final Map<String, LoadedJar> loaded = new LinkedHashMap<>();

	PluginHost(Injector injector, Path shadowDir) throws IOException
	{
		this.pluginManager = injector.getInstance(PluginManager.class);
		this.eventBus = injector.getInstance(EventBus.class);
		this.shadowDir = shadowDir;
		Files.createDirectories(shadowDir);
		clearShadowDir();
	}

	synchronized List<LoadedJar> list()
	{
		return new ArrayList<>(loaded.values());
	}

	boolean isActive(Plugin plugin)
	{
		return pluginManager.isPluginActive(plugin);
	}

	/**
	 * Loads every jar in a folder. Failures are logged and skipped.
	 */
	synchronized List<String> loadDirectory(Path dir) throws IOException
	{
		List<String> ids = new ArrayList<>();
		if (!Files.isDirectory(dir))
		{
			Files.createDirectories(dir);
			return ids;
		}

		try (DirectoryStream<Path> jars = Files.newDirectoryStream(dir, "*.jar"))
		{
			for (Path jar : jars)
			{
				try
				{
					ids.add(load(jar, List.of()).getId());
				}
				catch (Exception e)
				{
					log.warn("Unable to sideload {}", jar, e);
				}
			}
		}
		return ids;
	}

	/**
	 * Loads a jar, replacing it if a jar with the same id is already loaded.
	 *
	 * @param deps ids of already-loaded jars whose classes this one uses
	 */
	synchronized LoadedJar load(Path source, List<String> deps) throws Exception
	{
		source = source.toAbsolutePath().normalize();
		String id = idFor(source);

		List<SideloadClassLoader> depLoaders = new ArrayList<>();
		for (String dep : deps)
		{
			LoadedJar d = loaded.get(dep);
			if (d == null || dep.equals(id))
			{
				throw new IllegalArgumentException("Dependency not loaded: " + dep);
			}
			depLoaders.add(d.getClassLoader());
		}

		// Jars that depend on this one hold its old classes, so they get reloaded after it
		List<LoadedJar> dependents = loaded.containsKey(id) ? transitiveDependents(id) : List.of();
		if (loaded.containsKey(id))
		{
			unload(id);
		}

		LoadedJar jar = loadOne(id, source, deps, depLoaders);
		for (LoadedJar dependent : dependents)
		{
			try
			{
				load(dependent.getSource(), dependent.getDeps());
			}
			catch (Exception e)
			{
				log.warn("Unable to reload {} after its dependency {} changed", dependent.getId(), id, e);
			}
		}
		return jar;
	}

	private LoadedJar loadOne(String id, Path source, List<String> deps, List<SideloadClassLoader> depLoaders) throws Exception
	{
		// Load from a copy so the original jar isn't locked and can be rebuilt while loaded
		Path shadow = shadowDir.resolve(id + "-" + System.nanoTime() + ".jar");
		Files.copy(source, shadow);

		SideloadClassLoader cl = new SideloadClassLoader(id, shadow.toUri().toURL(), depLoaders);
		List<Plugin> plugins = Collections.emptyList();
		try
		{
			List<Class<?>> classes = findPluginClasses(cl, shadow);
			if (classes.isEmpty())
			{
				throw new IllegalArgumentException("No plugins found in " + source.getFileName());
			}
			checkNotAlreadyLoaded(classes);

			plugins = pluginManager.loadPlugins(classes, null);
			pluginManager.loadDefaultPluginConfiguration(plugins);

			List<Plugin> started = plugins;
			onEdt(() ->
			{
				for (Plugin plugin : started)
				{
					pluginManager.startPlugin(plugin);
				}
			});
		}
		catch (Exception e)
		{
			stopAndRemove(plugins);
			closeQuietly(cl, shadow);
			throw e;
		}

		LoadedJar jar = new LoadedJar(id, source, shadow, cl, plugins, deps);
		loaded.put(id, jar);
		eventBus.post(new ExternalPluginsChanged());
		log.info("Sideloaded {} ({} plugin(s){})", id, plugins.size(), deps.isEmpty() ? "" : ", using " + deps);
		return jar;
	}

	/**
	 * Unloads a jar, and first every jar that depends on it.
	 */
	synchronized void unload(String id)
	{
		LoadedJar jar = loaded.get(id);
		if (jar == null)
		{
			throw new IllegalArgumentException("Not loaded: " + id);
		}

		List<LoadedJar> dependents = transitiveDependents(id);
		Collections.reverse(dependents);
		for (LoadedJar dependent : dependents)
		{
			unloadOne(dependent);
		}
		unloadOne(jar);
		eventBus.post(new ExternalPluginsChanged());
	}

	private void unloadOne(LoadedJar jar)
	{
		loaded.remove(jar.getId());
		stopAndRemove(jar.getPlugins());
		closeQuietly(jar.getClassLoader(), jar.getShadow());
		log.info("Unloaded {}", jar.getId());
	}

	synchronized LoadedJar reload(String id) throws Exception
	{
		LoadedJar jar = loaded.get(id);
		if (jar == null)
		{
			throw new IllegalArgumentException("Not loaded: " + id);
		}
		return load(jar.getSource(), jar.getDeps());
	}

	/**
	 * Every loaded jar that depends on id, directly or not, in load order (dependencies first).
	 */
	private List<LoadedJar> transitiveDependents(String id)
	{
		Set<String> found = new HashSet<>();
		Deque<String> todo = new ArrayDeque<>(List.of(id));
		while (!todo.isEmpty())
		{
			String current = todo.pop();
			for (LoadedJar jar : loaded.values())
			{
				if (jar.getDeps().contains(current) && found.add(jar.getId()))
				{
					todo.push(jar.getId());
				}
			}
		}

		List<LoadedJar> ordered = new ArrayList<>();
		for (LoadedJar jar : loaded.values())
		{
			if (found.contains(jar.getId()))
			{
				ordered.add(jar);
			}
		}
		return ordered;
	}

	private void stopAndRemove(List<Plugin> plugins)
	{
		// stop in reverse load order so dependents stop before their dependencies
		List<Plugin> reversed = new ArrayList<>(plugins);
		Collections.reverse(reversed);
		for (Plugin plugin : reversed)
		{
			try
			{
				onEdt(() -> pluginManager.stopPlugin(plugin));
			}
			catch (Exception e)
			{
				log.warn("Unable to cleanly stop {}", plugin.getClass().getName(), e);
			}
			pluginManager.remove(plugin);
		}
	}

	/**
	 * Refuses plugins RuneLite already has, e.g. the same plugin installed from the Plugin Hub,
	 * or a copy sideloaded under another id. Running both would double up every overlay and event.
	 */
	private void checkNotAlreadyLoaded(List<Class<?>> classes)
	{
		for (Class<?> clazz : classes)
		{
			for (Plugin existing : pluginManager.getPlugins())
			{
				if (existing.getClass().getName().equals(clazz.getName()))
				{
					ClassLoader cl = existing.getClass().getClassLoader();
					String owner = cl instanceof SideloadClassLoader ? "sideloaded as " + cl.getName() : "RuneLite (Plugin Hub or built in)";
					throw new IllegalStateException(clazz.getSimpleName() + " is already loaded by " + owner);
				}
			}
		}
	}

	private static List<Class<?>> findPluginClasses(ClassLoader cl, Path jarPath) throws IOException
	{
		List<Class<?>> classes = new ArrayList<>();
		try (JarFile jar = new JarFile(jarPath.toFile()))
		{
			Enumeration<JarEntry> entries = jar.entries();
			while (entries.hasMoreElements())
			{
				String name = entries.nextElement().getName();
				if (!name.endsWith(".class") || name.startsWith("META-INF/") || name.endsWith("module-info.class"))
				{
					continue;
				}

				String className = name.substring(0, name.length() - ".class".length()).replace('/', '.');
				try
				{
					Class<?> clazz = Class.forName(className, false, cl);
					if (clazz.getSuperclass() == Plugin.class && clazz.isAnnotationPresent(PluginDescriptor.class))
					{
						classes.add(clazz);
					}
				}
				catch (LinkageError | ClassNotFoundException e)
				{
					log.debug("Skipping {}: {}", className, e.toString());
				}
			}
		}
		return classes;
	}

	private void clearShadowDir()
	{
		try (DirectoryStream<Path> stale = Files.newDirectoryStream(shadowDir, "*.jar"))
		{
			for (Path p : stale)
			{
				Files.deleteIfExists(p);
			}
		}
		catch (IOException e)
		{
			log.debug("Unable to clear shadow dir", e);
		}
	}

	private static void closeQuietly(SideloadClassLoader cl, Path shadow)
	{
		try
		{
			cl.close();
			Files.deleteIfExists(shadow);
		}
		catch (IOException e)
		{
			// still in use; cleared on next startup
		}
	}

	private static String idFor(Path jar)
	{
		String name = jar.getFileName().toString();
		return name.endsWith(".jar") ? name.substring(0, name.length() - 4) : name;
	}

	@FunctionalInterface
	private interface EdtTask
	{
		void run() throws Exception;
	}

	/**
	 * Plugins must start and stop on the Swing event thread.
	 */
	private static void onEdt(EdtTask task) throws Exception
	{
		if (SwingUtilities.isEventDispatchThread())
		{
			task.run();
			return;
		}

		Exception[] failure = new Exception[1];
		try
		{
			SwingUtilities.invokeAndWait(() ->
			{
				try
				{
					task.run();
				}
				catch (Exception e)
				{
					failure[0] = e;
				}
			});
		}
		catch (InvocationTargetException e)
		{
			throw new Exception(e.getCause());
		}
		if (failure[0] != null)
		{
			throw failure[0];
		}
	}

	static final class LoadedJar
	{
		private final String id;
		private final Path source;
		private final Path shadow;
		private final SideloadClassLoader classLoader;
		private final List<Plugin> plugins;
		private final List<String> deps;

		LoadedJar(String id, Path source, Path shadow, SideloadClassLoader classLoader, List<Plugin> plugins, List<String> deps)
		{
			this.id = id;
			this.source = source;
			this.shadow = shadow;
			this.classLoader = classLoader;
			this.plugins = plugins;
			this.deps = List.copyOf(deps);
		}

		List<String> getDeps()
		{
			return deps;
		}

		String getId()
		{
			return id;
		}

		Path getSource()
		{
			return source;
		}

		Path getShadow()
		{
			return shadow;
		}

		SideloadClassLoader getClassLoader()
		{
			return classLoader;
		}

		List<Plugin> getPlugins()
		{
			return plugins;
		}
	}
}
