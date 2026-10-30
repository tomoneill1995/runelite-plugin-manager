package dev.rlsideloader.manager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Starts the stock RuneLite install with the agent attached.
 * <p>
 * Runs the installed launcher jar on RuneLite's bundled JRE, so the launcher still updates and
 * hash-verifies the client as normal. RuneLite.exe isn't used: starting it with the agent
 * exited before the client ran (its packr config sets runelite.launcher.nojvm, which probably
 * rules out the JVM launch mode this needs).
 * <p>
 * JVM arguments for the client go through the launcher's RUNELITE_VMARGS environment variable:
 * the agent first, then any extra arguments the user configured, so theirs win on conflicts
 * (e.g. their -Xmx overrides RuneLite's default).
 */
final class RuneLiteLauncher
{
	private static final String AGENT_RESOURCE = "/rl-sideloader-agent.jar";

	private RuneLiteLauncher()
	{
	}

	/**
	 * @param disableTelemetry start the client with --disable-telemetry
	 * @param extraJvmArgs     extra JVM arguments for the client, whitespace separated; may be null or blank
	 */
	static Process launch(boolean disableTelemetry, String extraJvmArgs) throws IOException
	{
		Path install = Paths.RUNELITE_INSTALL;
		Path javaw = install.resolve("jre").resolve("bin").resolve("javaw.exe");
		Path launcherJar = install.resolve("RuneLite.jar");
		if (!Files.exists(javaw) || !Files.exists(launcherJar))
		{
			throw new IOException("RuneLite isn't installed at " + install);
		}

		Path agent = extractAgent();
		ProcessBuilder pb = new ProcessBuilder(javaw.toString(), "-Xmx768m", "-Xss2m",
			"-cp", launcherJar.toString(), "net.runelite.launcher.Launcher")
			.directory(install.toFile())
			.redirectOutput(ProcessBuilder.Redirect.DISCARD)
			.redirectError(ProcessBuilder.Redirect.DISCARD);

		// The manager decides what to load, so the agent's own folder autoload is off
		// home: so the agent writes agent.json where this manager looks for it
		String vmArgs = "-javaagent:" + agent + "=autoload=false;home=" + Paths.HOME;
		if (extraJvmArgs != null && !extraJvmArgs.isBlank())
		{
			vmArgs += " " + extraJvmArgs.trim();
		}
		pb.environment().put("RUNELITE_VMARGS", vmArgs);

		if (disableTelemetry)
		{
			pb.environment().put("RUNELITE_ARGS", "--disable-telemetry");
		}
		else
		{
			pb.environment().remove("RUNELITE_ARGS");
		}
		return pb.start();
	}

	/**
	 * Copies the bundled agent out to a file named by its hash, since a running client keeps
	 * its agent jar locked.
	 */
	private static Path extractAgent() throws IOException
	{
		byte[] bytes;
		try (InputStream in = RuneLiteLauncher.class.getResourceAsStream(AGENT_RESOURCE))
		{
			if (in == null)
			{
				throw new IOException("Agent jar missing from the manager build");
			}
			bytes = in.readAllBytes();
		}

		String hash;
		try
		{
			StringBuilder sb = new StringBuilder();
			for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
			{
				sb.append(String.format("%02x", b));
			}
			hash = sb.substring(0, 12);
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException(e);
		}

		Files.createDirectories(Paths.AGENT_DIR);
		Path target = Paths.AGENT_DIR.resolve("rl-sideloader-agent-" + hash + ".jar");
		if (!Files.exists(target))
		{
			Files.write(target, bytes);
		}
		return target;
	}
}
