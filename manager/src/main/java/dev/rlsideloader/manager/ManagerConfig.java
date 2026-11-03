package dev.rlsideloader.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persisted manager settings, stored in ~/.rl-sideloader/manager.json.
 */
final class ManagerConfig
{
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	enum SourceType
	{
		/** A local folder with one Gradle plugin project or several */
		LOCAL,
		/** Older name for LOCAL in saved settings */
		@com.google.gson.annotations.SerializedName("REPO")
		REPO_LEGACY,
		/** A folder of ready-built plugin jars */
		FOLDER,
		/** A git remote cloned into ~/.rl-sideloader/repos; may hold one plugin project or several */
		GIT,
		/** The official RuneLite Plugin Hub (prebuilt, hash-checked jars) */
		HUB,
		/** A private hub: a manifest.json URL with prebuilt jars next to it; path is its cache folder */
		PRIVATE_HUB,
	}

	static final class Source
	{
		SourceType type;
		String path;
		/** GIT: the remote it was cloned from. PRIVATE_HUB: the manifest URL */
		String url;
		/** PRIVATE_HUB: the hub's name from its manifest, remembered for offline display */
		String name;

		Source(SourceType type, String path)
		{
			this.type = type;
			this.path = path;
		}
	}

	static final class RowSettings
	{
		/** Shown under Installed (like installing from the Plugin Hub) */
		boolean installed;
		/** Loaded into RuneLite */
		boolean enabled;
		boolean autoReload;
		/** Gradle task that builds the plugin jar; REPO rows only */
		String buildTask = "jar";
		/** JDK to run Gradle with; REPO rows only. For old projects whose Gradle can't run on the default Java. */
		String javaHome;
	}

	boolean disableTelemetry = true;
	/** Pull git sources (and rebuild what changed) before launching RuneLite */
	boolean pullOnLaunch = true;
	/** Extra JVM arguments for the RuneLite client, whitespace separated; added to RUNELITE_VMARGS after the agent */
	String jvmArgs = "";
	/** Put the agent into RuneLite's own launcher settings, so clients started by the Jagex Launcher load plugins too */
	boolean jagexLauncherMode;
	/** Exactly what this manager last wrote into ~/.runelite/settings.json, so it can remove just those entries */
	List<String> jagexJvmArguments = new ArrayList<>();
	List<String> jagexClientArguments = new ArrayList<>();
	List<Source> sources = new ArrayList<>();
	/** Keyed by the row's path (repo folder or jar file) */
	Map<String, RowSettings> rows = new LinkedHashMap<>();

	static ManagerConfig load()
	{
		try
		{
			if (Files.exists(Paths.CONFIG))
			{
				ManagerConfig config = GSON.fromJson(Files.readString(Paths.CONFIG), ManagerConfig.class);
				if (config != null)
				{
					// settings from before "installed" existed
					config.rows.values().forEach(r -> r.installed |= r.enabled);
					config.sources.forEach(s ->
					{
						if (s.type == SourceType.REPO_LEGACY)
						{
							s.type = SourceType.LOCAL;
						}
					});
					return config;
				}
			}
		}
		catch (IOException | RuntimeException e)
		{
			System.err.println("Unable to read " + Paths.CONFIG + ": " + e);
		}

		ManagerConfig config = defaults();
		if (config.sources.stream().noneMatch(s -> s.type == SourceType.HUB))
		{
			config.sources.add(0, new Source(SourceType.HUB, PluginHub.DIR.toString()));
		}
		if (config.sources.stream().noneMatch(s -> s.type == SourceType.FOLDER))
		{
			config.sources.add(new Source(SourceType.FOLDER, Paths.DEFAULT_PLUGINS.toString()));
		}
		return config;
	}

	/**
	 * First-run settings: manager-defaults.json next to the app, if someone shipped one (e.g. a
	 * zip pre-pointed at a private hub, so the person installing it has nothing to set up).
	 */
	private static ManagerConfig defaults()
	{
		for (Path dir : appDirs())
		{
			Path file = dir.resolve("manager-defaults.json");
			if (!Files.isRegularFile(file))
			{
				continue;
			}
			try
			{
				ManagerConfig config = GSON.fromJson(Files.readString(file), ManagerConfig.class);
				if (config != null)
				{
					config.sources.removeIf(s -> s.type == null || (s.path == null && s.url == null));
					for (Source s : config.sources)
					{
						if (s.type == SourceType.PRIVATE_HUB && s.url != null)
						{
							s.url = PrivateHub.normalizeUrl(s.url);
							s.path = PrivateHub.dirFor(s.url).toString();
						}
					}
					copyExtraJars(dir.resolve("extra"));
					System.err.println("Using first-run settings from " + file);
					return config;
				}
			}
			catch (IOException | RuntimeException e)
			{
				System.err.println("Unable to read " + file + ": " + e);
			}
		}
		return new ManagerConfig();
	}

	/**
	 * Jars shipped in an "extra" folder next to the app (another -javaagent, say) are copied to
	 * ~/.rl-sideloader/extra, a path without spaces that "${extra}" in the JVM arguments points at.
	 */
	private static void copyExtraJars(Path from)
	{
		if (!Files.isDirectory(from))
		{
			return;
		}
		try (var jars = Files.list(from))
		{
			Files.createDirectories(Paths.EXTRA);
			for (Path jar : jars.filter(p -> p.toString().endsWith(".jar")).collect(java.util.stream.Collectors.toList()))
			{
				Files.copy(jar, Paths.EXTRA.resolve(jar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
			}
		}
		catch (IOException e)
		{
			System.err.println("Unable to copy extra jars from " + from + ": " + e);
		}
	}

	/** Where the app lives: the packaged exe's folder, else the working directory */
	private static List<Path> appDirs()
	{
		List<Path> dirs = new ArrayList<>();
		String packaged = System.getProperty("jpackage.app-path");
		if (packaged != null && Path.of(packaged).getParent() != null)
		{
			dirs.add(Path.of(packaged).getParent());
		}
		ProcessHandle.current().info().command().map(Path::of).map(Path::getParent).ifPresent(dirs::add);
		dirs.add(Path.of(System.getProperty("user.dir")));
		return dirs;
	}

	synchronized void save() throws IOException
	{
		Files.createDirectories(Paths.HOME);
		var tmp = Paths.CONFIG.resolveSibling("manager.json.tmp");
		Files.writeString(tmp, GSON.toJson(this), StandardCharsets.UTF_8);
		Files.move(tmp, Paths.CONFIG, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	synchronized RowSettings settingsFor(String key)
	{
		return rows.computeIfAbsent(key, k -> new RowSettings());
	}
}
