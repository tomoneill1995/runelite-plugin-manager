package dev.rlsideloader.agent;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Agent options, passed as -javaagent:agent.jar=key=value;key=value
 * <ul>
 *     <li>home: sideloader data folder (default ~/.rl-sideloader)</li>
 *     <li>plugins: folder of plugin jars loaded at startup (default home/plugins)</li>
 *     <li>autoload: load the plugins folder at startup (default true; the manager app
 *     passes false and loads what it wants itself)</li>
 * </ul>
 */
final class AgentConfig
{
	private final Path home;
	private final Path pluginDir;
	private final boolean autoload;

	private AgentConfig(Path home, Path pluginDir, boolean autoload)
	{
		this.home = home;
		this.pluginDir = pluginDir;
		this.autoload = autoload;
	}

	static AgentConfig parse(String args)
	{
		Path home = Paths.get(System.getProperty("user.home"), ".rl-sideloader");
		Path pluginDir = null;
		boolean autoload = true;

		if (args != null)
		{
			for (String part : args.split(";"))
			{
				int eq = part.indexOf('=');
				if (eq < 0)
				{
					continue;
				}
				String key = part.substring(0, eq).trim();
				String value = part.substring(eq + 1).trim();
				switch (key)
				{
					case "home":
						home = Paths.get(value);
						break;
					case "plugins":
						pluginDir = Paths.get(value);
						break;
					case "autoload":
						autoload = Boolean.parseBoolean(value);
						break;
				}
			}
		}

		return new AgentConfig(home, pluginDir != null ? pluginDir : home.resolve("plugins"), autoload);
	}

	boolean isAutoload()
	{
		return autoload;
	}

	Path getHome()
	{
		return home;
	}

	Path getPluginDir()
	{
		return pluginDir;
	}

	Path getShadowDir()
	{
		return home.resolve("shadow");
	}

	Path getInfoFile()
	{
		return home.resolve("agent.json");
	}
}
