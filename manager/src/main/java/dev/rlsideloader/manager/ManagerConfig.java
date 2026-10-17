package dev.rlsideloader.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
	}

	static final class Source
	{
		SourceType type;
		String path;
		/** GIT only: the remote it was cloned from */
		String url;

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

		ManagerConfig config = new ManagerConfig();
		config.sources.add(new Source(SourceType.HUB, PluginHub.DIR.toString()));
		config.sources.add(new Source(SourceType.FOLDER, Paths.DEFAULT_PLUGINS.toString()));
		return config;
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
