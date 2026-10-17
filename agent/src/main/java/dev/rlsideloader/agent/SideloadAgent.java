package dev.rlsideloader.agent;

import com.google.inject.Injector;
import java.lang.instrument.Instrumentation;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.RuneLite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java agent entry point. Loaded with -javaagent:rl-sideloader-agent.jar[=options].
 * <p>
 * premain runs before RuneLite's main, so all the real work happens on a background
 * thread that waits for the client to finish starting up.
 */
public final class SideloadAgent
{
	private static final String RUNELITE_CLASS = "net.runelite.client.RuneLite";
	private static final Set<GameState> NOT_READY = EnumSet.of(GameState.UNKNOWN, GameState.STARTING);
	private static final long POLL_MS = 500;
	private static final long STARTUP_GRACE_MS = 2000;

	private SideloadAgent()
	{
	}

	public static void premain(String args, Instrumentation inst)
	{
		AgentConfig config = AgentConfig.parse(args);
		Thread thread = new Thread(() -> run(config, inst), "rl-sideloader");
		thread.setDaemon(true);
		thread.start();
	}

	private static void run(AgentConfig config, Instrumentation inst)
	{
		try
		{
			// Don't touch RuneLite classes until the launcher has loaded them itself
			while (Arrays.stream(inst.getAllLoadedClasses()).noneMatch(c -> c.getName().equals(RUNELITE_CLASS)))
			{
				Thread.sleep(POLL_MS);
			}

			Logger log = LoggerFactory.getLogger(SideloadAgent.class);
			if (RuneLite.class.getClassLoader() != SideloadAgent.class.getClassLoader())
			{
				log.error("RuneLite is not on the system class path (REFLECT launch mode?); sideloader disabled");
				return;
			}

			Injector injector = waitForClient();
			log.info("Client ready, starting sideloader (plugins dir: {})", config.getPluginDir());

			PluginHost host = new PluginHost(injector, config.getShadowDir());
			if (config.isAutoload())
			{
				host.loadDirectory(config.getPluginDir());
			}

			new ControlServer(host, config).run();
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
		catch (Throwable e)
		{
			LoggerFactory.getLogger(SideloadAgent.class).error("Sideloader failed", e);
		}
	}

	/**
	 * Waits until the injector exists and the game has reached the login screen,
	 * by which point RuneLite has finished starting its own plugins.
	 */
	private static Injector waitForClient() throws InterruptedException
	{
		while (true)
		{
			Injector injector = RuneLite.getInjector();
			if (injector != null)
			{
				try
				{
					Client client = injector.getInstance(Client.class);
					if (client != null && !NOT_READY.contains(client.getGameState()))
					{
						Thread.sleep(STARTUP_GRACE_MS);
						return injector;
					}
				}
				catch (RuntimeException e)
				{
					// client not constructed yet
				}
			}
			Thread.sleep(POLL_MS);
		}
	}
}
