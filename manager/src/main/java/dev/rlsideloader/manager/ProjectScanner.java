package dev.rlsideloader.manager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds RuneLite plugin projects under a folder (a single plugin repo, or a repo of many
 * Gradle subprojects) and reads their details from source, without building anything.
 */
final class ProjectScanner
{
	private static final int MAX_DEPTH = 3;
	private static final Set<String> SKIP_DIRS = Set.of("build", "out", "bin", "target", "node_modules", "gradle", "src");

	private static final Pattern DESCRIPTOR = Pattern.compile("@PluginDescriptor\\s*\\(");
	private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)", Pattern.MULTILINE);
	private static final Pattern CLASS = Pattern.compile("\\bclass\\s+(\\w+)");
	private static final Pattern PLUGIN_DEPENDENCY = Pattern.compile(
		"@PluginDependency\\s*\\(\\s*(?:value\\s*=\\s*)?([\\w.]+?)(?:\\.class|::class)");
	private static final Pattern PROJECT_DEP = Pattern.compile(
		"project\\s*\\(\\s*(?:path\\s*[:=]\\s*)?['\"]:?([^'\"]+)['\"]");
	private static final Pattern STRING = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

	private ProjectScanner()
	{
	}

	static List<PluginInfo> scan(Path root)
	{
		List<PluginInfo> found = new ArrayList<>();
		walk(root, root, 0, found);
		return found;
	}

	private static void walk(Path root, Path dir, int depth, List<PluginInfo> found)
	{
		if (Files.isRegularFile(dir.resolve("build.gradle")) || Files.isRegularFile(dir.resolve("build.gradle.kts")))
		{
			PluginInfo info = read(root, dir);
			if (info != null)
			{
				found.add(info);
			}
		}
		if (depth >= MAX_DEPTH)
		{
			return;
		}
		try (Stream<Path> children = Files.list(dir))
		{
			for (Path child : children.filter(Files::isDirectory).sorted().collect(Collectors.toList()))
			{
				String name = child.getFileName().toString();
				if (!name.startsWith(".") && !SKIP_DIRS.contains(name))
				{
					walk(root, child, depth + 1, found);
				}
			}
		}
		catch (IOException ignored)
		{
		}
	}

	/**
	 * Reads one Gradle project, or returns null if it has no plugin classes of its own.
	 */
	private static PluginInfo read(Path root, Path dir)
	{
		Path src = dir.resolve("src").resolve("main");
		if (!Files.isDirectory(src))
		{
			return null;
		}

		List<String> names = new ArrayList<>();
		List<String> classes = new ArrayList<>();
		Set<String> tags = new LinkedHashSet<>();
		Set<String> pluginDeps = new LinkedHashSet<>();
		String description = "";
		boolean kotlin = false;

		try (Stream<Path> files = Files.walk(src))
		{
			for (Path file : files.filter(ProjectScanner::isSource).collect(Collectors.toList()))
			{
				String text;
				try
				{
					text = Files.readString(file);
				}
				catch (IOException | RuntimeException e)
				{
					continue;
				}
				kotlin |= file.toString().endsWith(".kt");

				Matcher m = DESCRIPTOR.matcher(text);
				if (!m.find())
				{
					continue;
				}
				String body = balanced(text, m.end());
				Matcher cls = CLASS.matcher(text);
				if (!cls.find(m.end() + body.length()))
				{
					continue;
				}

				Matcher pkg = PACKAGE.matcher(text);
				classes.add((pkg.find() ? pkg.group(1) + "." : "") + cls.group(1));
				names.add(stripHtml(attribute(body, "name", cls.group(1))));
				if (description.isEmpty())
				{
					description = stripHtml(attribute(body, "description", ""));
				}
				tags.addAll(arrayAttribute(body, "tags"));

				Matcher dep = PLUGIN_DEPENDENCY.matcher(text);
				while (dep.find())
				{
					String depClass = dep.group(1);
					pluginDeps.add(depClass.substring(depClass.lastIndexOf('.') + 1));
				}
			}
		}
		catch (IOException e)
		{
			return null;
		}

		if (classes.isEmpty())
		{
			return null;
		}

		Properties props = pluginProperties(dir);
		String author = props.getProperty("author", "").trim();
		String displayName = props.getProperty("displayName", "").trim();
		String propsDescription = props.getProperty("description", "").trim();
		if (description.isEmpty())
		{
			description = propsDescription;
		}
		for (String tag : props.getProperty("tags", "").split(","))
		{
			if (!tag.isBlank())
			{
				tags.add(tag.trim());
			}
		}

		String title = !displayName.isEmpty() ? displayName : names.get(0);
		Path icon = dir.resolve("icon.png");

		return new PluginInfo(dir, title, description, author, new ArrayList<>(tags), names, classes,
			projectDeps(dir), new ArrayList<>(pluginDeps), Files.isRegularFile(icon) ? icon : null, kotlin, null, null);
	}

	private static boolean isSource(Path p)
	{
		String name = p.toString();
		return name.endsWith(".java") || name.endsWith(".kt");
	}

	/** Text between an opening paren (just consumed) and its matching close, skipping strings */
	private static String balanced(String text, int start)
	{
		int depth = 1;
		boolean inString = false;
		for (int i = start; i < text.length(); i++)
		{
			char c = text.charAt(i);
			if (inString)
			{
				if (c == '\\')
				{
					i++;
				}
				else if (c == '"')
				{
					inString = false;
				}
			}
			else if (c == '"')
			{
				inString = true;
			}
			else if (c == '(')
			{
				depth++;
			}
			else if (c == ')' && --depth == 0)
			{
				return text.substring(start, i);
			}
		}
		return text.substring(start);
	}

	private static String attribute(String body, String key, String fallback)
	{
		Matcher m = Pattern.compile("\\b" + key + "\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(body);
		return m.find() ? unescape(m.group(1)) : fallback;
	}

	/** tags = {"a", "b"} in Java, tags = ["a", "b"] or arrayOf(...) in Kotlin */
	private static List<String> arrayAttribute(String body, String key)
	{
		List<String> values = new ArrayList<>();
		Matcher m = Pattern.compile("\\b" + key + "\\s*=\\s*(?:\\{([^}]*)}|\\[([^\\]]*)]|arrayOf\\(([^)]*)\\))").matcher(body);
		if (m.find())
		{
			String list = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
			Matcher s = STRING.matcher(list);
			while (s.find())
			{
				values.add(unescape(s.group(1)));
			}
		}
		return values;
	}

	private static String unescape(String s)
	{
		return s.replace("\\\"", "\"").replace("\\\\", "\\");
	}

	static String stripHtml(String s)
	{
		return s.replaceAll("<[^>]*>", "").replace("&nbsp;", " ").replace("&amp;", "&").replaceAll("\\s+", " ").trim();
	}

	private static Properties pluginProperties(Path dir)
	{
		Properties props = new Properties();
		Path file = dir.resolve("runelite-plugin.properties");
		if (Files.isRegularFile(file))
		{
			try (InputStream in = Files.newInputStream(file))
			{
				props.load(in);
			}
			catch (IOException ignored)
			{
			}
		}
		return props;
	}

	/**
	 * Folders of Gradle projects this one depends on, e.g. project(path: ':EthansApi'),
	 * resolved against the build's root (the folder with settings.gradle).
	 */
	private static List<Path> projectDeps(Path dir)
	{
		Path settingsRoot = dir;
		while (settingsRoot != null && !Files.isRegularFile(settingsRoot.resolve("settings.gradle"))
			&& !Files.isRegularFile(settingsRoot.resolve("settings.gradle.kts")))
		{
			settingsRoot = settingsRoot.getParent();
		}
		if (settingsRoot == null)
		{
			return List.of();
		}

		List<Path> deps = new ArrayList<>();
		for (String name : List.of("build.gradle", "build.gradle.kts"))
		{
			Path build = dir.resolve(name);
			if (!Files.isRegularFile(build))
			{
				continue;
			}
			try
			{
				Matcher m = PROJECT_DEP.matcher(Files.readString(build));
				while (m.find())
				{
					Path dep = settingsRoot.resolve(m.group(1).replace(':', '/')).normalize();
					if (!dep.equals(dir) && !deps.contains(dep))
					{
						deps.add(dep);
					}
				}
			}
			catch (IOException ignored)
			{
			}
		}
		return deps;
	}
}
