package dev.rlsideloader.manager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Runs the git CLI. Authentication is left entirely to git (credential manager, SSH keys),
 * so private repos work the same as they do in a terminal.
 */
final class Git
{
	private Git()
	{
	}

	static void cloneRepo(String url, Path dir, Consumer<String> log) throws IOException, InterruptedException
	{
		run(dir.getParent(), log, "clone", url, dir.getFileName().toString());
	}

	/**
	 * Fast-forward pull. Returns true if HEAD moved.
	 */
	static boolean pull(Path dir, Consumer<String> log) throws IOException, InterruptedException
	{
		String before = head(dir);
		run(dir, log, "pull", "--ff-only");
		return !before.equals(head(dir));
	}

	private static String head(Path dir) throws IOException, InterruptedException
	{
		List<String> out = new ArrayList<>();
		run(dir, out::add, "rev-parse", "HEAD");
		return out.isEmpty() ? "" : out.get(0).trim();
	}

	/**
	 * Folder name for a clone: owner-repo from a GitHub style URL.
	 */
	static String folderName(String url)
	{
		String s = url.trim().replaceAll("[/\\\\]+$", "");
		if (s.endsWith(".git"))
		{
			s = s.substring(0, s.length() - 4);
		}
		String[] parts = s.split("[/:]");
		String name = parts.length >= 2 ? parts[parts.length - 2] + "-" + parts[parts.length - 1] : parts[parts.length - 1];
		return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
	}

	/**
	 * Accepts "owner/repo" as shorthand for a GitHub repo.
	 */
	static String normalizeUrl(String input)
	{
		String s = input.trim();
		if (s.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))
		{
			return "https://github.com/" + s + ".git";
		}
		return s;
	}

	private static void run(Path dir, Consumer<String> log, String... args) throws IOException, InterruptedException
	{
		List<String> command = new ArrayList<>(List.of("git"));
		command.addAll(List.of(args));
		ProcessBuilder pb = new ProcessBuilder(command)
			.directory(dir.toFile())
			.redirectErrorStream(true);
		// no console to answer a username prompt; a credential manager window still works
		pb.environment().put("GIT_TERMINAL_PROMPT", "0");
		Process process = pb.start();
		process.getOutputStream().close();

		try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)))
		{
			String line;
			while ((line = reader.readLine()) != null)
			{
				if (!line.isBlank())
				{
					log.accept(line);
				}
			}
		}

		int exit = process.waitFor();
		if (exit != 0)
		{
			throw new IOException("git " + args[0] + " failed (exit " + exit + ")");
		}
	}
}
