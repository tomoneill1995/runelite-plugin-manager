package dev.rlsideloader.manager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Builds plugin repos with Gradle and finds the resulting plugin jar.
 */
final class GradleBuilder
{
	private GradleBuilder()
	{
	}

	/**
	 * @param javaHome JDK to run Gradle with and to offer as a toolchain; the Plugin Hub
	 *                 template asks for a Java 11 toolchain, so this is normally a JDK 11
	 */
	static Path build(Path repo, String task, Path javaHome, Consumer<String> log) throws IOException, InterruptedException
	{
		// a plugin inside a multi-plugin repo uses the wrapper from the repo root
		List<String> command = new ArrayList<>(List.of("cmd", "/c"));
		command.add(findWrapper(repo).map(Path::toString).orElse("gradle"));
		command.addAll(List.of("-p", repo.toString(), task, "--console=plain"));
		if (javaHome != null)
		{
			command.add("-Porg.gradle.java.installations.paths=" + javaHome);
		}

		long started = System.currentTimeMillis();
		ProcessBuilder pb = new ProcessBuilder(command)
			.directory(repo.toFile())
			.redirectErrorStream(true);
		if (javaHome != null)
		{
			pb.environment().put("JAVA_HOME", javaHome.toString());
		}
		Process process = pb.start();
		process.getOutputStream().close();

		boolean javaTooNew = false;
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)))
		{
			String line;
			while ((line = reader.readLine()) != null)
			{
				if (!line.isBlank())
				{
					log.accept("  " + line);
				}
				javaTooNew |= line.contains("Unsupported class file major version");
			}
		}

		int exit = process.waitFor();
		if (exit != 0)
		{
			if (javaTooNew)
			{
				throw new IOException("this project's Gradle is too old for the current Java; set \"javaHome\" for it in "
					+ Paths.CONFIG.getFileName() + " to an older JDK (e.g. 11)");
			}
			throw new IOException("Gradle " + task + " failed (exit " + exit + ")");
		}

		return findJar(repo, started)
			.orElseThrow(() -> new IOException("Build succeeded but no jar found in " + repo.resolve("build/libs")));
	}

	private static Optional<Path> findWrapper(Path repo)
	{
		for (Path dir = repo.toAbsolutePath(); dir != null; dir = dir.getParent())
		{
			Path wrapper = dir.resolve("gradlew.bat");
			if (Files.isRegularFile(wrapper))
			{
				return Optional.of(wrapper);
			}
		}
		return Optional.empty();
	}

	/**
	 * The newest plugin jar in build/libs, if any. Skips sources/javadoc jars and the
	 * plugin template's "-all" jar, which bundles the whole RuneLite client for running it.
	 */
	static Optional<Path> findJar(Path repo)
	{
		return findJar(repo, 0);
	}

	private static Optional<Path> findJar(Path repo, long newerThan)
	{
		Path libs = repo.resolve("build").resolve("libs");
		if (!Files.isDirectory(libs))
		{
			return Optional.empty();
		}

		try (Stream<Path> files = Files.list(libs))
		{
			List<Path> jars = files
				.filter(p -> isPluginJar(p.getFileName().toString()))
				.sorted(Comparator.comparing(GradleBuilder::modified).reversed())
				.collect(Collectors.toList());
			return jars.stream()
				.filter(p -> modified(p).toMillis() >= newerThan)
				.findFirst()
				// up-to-date builds don't rewrite the jar
				.or(() -> jars.stream().findFirst());
		}
		catch (IOException e)
		{
			return Optional.empty();
		}
	}

	private static boolean isPluginJar(String name)
	{
		return name.endsWith(".jar")
			&& !name.endsWith("-sources.jar")
			&& !name.endsWith("-javadoc.jar")
			&& !name.endsWith("-all.jar");
	}

	private static FileTime modified(Path p)
	{
		try
		{
			return Files.getLastModifiedTime(p);
		}
		catch (IOException e)
		{
			return FileTime.fromMillis(0);
		}
	}
}
