package dev.rlsideloader.manager;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * One plugin entry: a plugin project (in a GitHub or local source) or one jar from a folder.
 * Mutable runtime state is only changed by {@link Controller}.
 */
final class PluginRow
{
	enum Kind
	{
		PROJECT,
		JAR,
		/** From the official Plugin Hub: prebuilt jar, nothing to build or watch */
		HUB,
	}

	private final Kind kind;
	private final Path path;
	private final ManagerConfig.Source source;
	private final ManagerConfig.RowSettings settings;
	/** Name the agent knows it by: the staged jar is ~/.rl-sideloader/staging/{id}.jar */
	private final String id;

	/** Details read from source; null for jars */
	volatile PluginInfo info;
	/** Rows this one needs loaded first */
	volatile List<PluginRow> deps = List.of();

	volatile boolean building;
	/** Sources changed (git pull) since the last build */
	volatile boolean needsBuild;
	volatile String error;
	/** What the agent reports for this row, or null if it isn't loaded */
	volatile List<AgentClient.LoadedPlugin> loaded;

	PluginRow(Kind kind, Path path, ManagerConfig.Source source, ManagerConfig.RowSettings settings, String id)
	{
		this.kind = kind;
		this.path = path;
		this.source = source;
		this.settings = settings;
		this.id = id;
	}

	Kind kind()
	{
		return kind;
	}

	Path path()
	{
		return path;
	}

	ManagerConfig.Source source()
	{
		return source;
	}

	ManagerConfig.RowSettings settings()
	{
		return settings;
	}

	String id()
	{
		return id;
	}

	String key()
	{
		return path.toString();
	}

	boolean installed()
	{
		return settings.installed;
	}

	boolean enabled()
	{
		return settings.enabled;
	}

	String displayName()
	{
		PluginInfo i = info;
		if (i != null && !i.title.isEmpty())
		{
			return i.title;
		}
		String name = path.getFileName().toString();
		return name.endsWith(".jar") ? name.substring(0, name.length() - 4) : name;
	}

	String description()
	{
		PluginInfo i = info;
		return i == null ? "" : i.description;
	}

	List<String> tags()
	{
		PluginInfo i = info;
		return i == null ? List.of() : i.tags;
	}

	/** "author · where it's from" */
	String subtitle()
	{
		PluginInfo i = info;
		String where = Sources.label(source);
		if (kind == Kind.PROJECT && !Path.of(source.path).equals(path))
		{
			where += " / " + path.getFileName();
		}
		if (i != null && i.version != null && !i.version.isEmpty())
		{
			// some Plugin Hub versions are commit hashes
			where += "  ·  " + (Character.isDigit(i.version.charAt(0)) ? "v" + i.version : i.version);
		}
		return i != null && !i.author.isEmpty() ? i.author + "  ·  " + where : where;
	}

	String depNames()
	{
		return deps.stream().map(PluginRow::displayName).collect(Collectors.joining(", "));
	}

	String pluginNames()
	{
		List<AgentClient.LoadedPlugin> l = loaded;
		if (l != null)
		{
			return l.stream().map(AgentClient.LoadedPlugin::name).map(ProjectScanner::stripHtml).collect(Collectors.joining(", "));
		}
		PluginInfo i = info;
		return i == null ? "" : String.join(", ", i.pluginNames);
	}

	enum State
	{
		IDLE,
		BUSY,
		RUNNING,
		WARN,
		ERROR,
	}

	State state()
	{
		if (building)
		{
			return State.BUSY;
		}
		if (error != null)
		{
			return State.ERROR;
		}
		List<AgentClient.LoadedPlugin> l = loaded;
		if (l != null)
		{
			return l.stream().allMatch(AgentClient.LoadedPlugin::active) ? State.RUNNING : State.WARN;
		}
		return State.IDLE;
	}

	String status(boolean connected)
	{
		switch (state())
		{
			case BUSY:
				return "Building…";
			case ERROR:
				return "Error";
			case RUNNING:
				return "Running";
			case WARN:
				return "Off in RuneLite";
			default:
				if (!settings.enabled)
				{
					return "Disabled";
				}
				return connected ? "Not loaded" : "Loads on launch";
		}
	}
}
