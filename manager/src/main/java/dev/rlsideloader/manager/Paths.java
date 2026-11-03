package dev.rlsideloader.manager;

import java.nio.file.Path;

/**
 * Well-known locations. The agent uses the same home folder.
 */
final class Paths
{
	static final Path HOME = Path.of(System.getProperty("user.home"), ".rl-sideloader");
	static final Path CONFIG = HOME.resolve("manager.json");
	static final Path AGENT_INFO = HOME.resolve("agent.json");
	/** One {pid}.json per running client with the agent */
	static final Path AGENTS_DIR = HOME.resolve("agents");
	static final Path LOG = HOME.resolve("manager.log");
	static final Path STAGING = HOME.resolve("staging");
	static final Path AGENT_DIR = HOME.resolve("agent");
	static final Path DEFAULT_PLUGINS = HOME.resolve("plugins");
	static final Path GIT_REPOS = HOME.resolve("repos");

	static final Path RUNELITE_INSTALL = Path.of(System.getenv("LOCALAPPDATA"), "RuneLite");
	/**
	 * The launcher settings behind "RuneLite (configure)". The launcher keeps this next to the
	 * install (not in ~/.runelite, which only holds the client's data).
	 */
	static final Path RUNELITE_SETTINGS = RUNELITE_INSTALL.resolve("settings.json");

	private Paths()
	{
	}
}
