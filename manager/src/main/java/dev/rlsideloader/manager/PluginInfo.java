package dev.rlsideloader.manager;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * What a plugin is: the Plugin Hub style details shown in Browse. Read from a project's
 * source, or from the Plugin Hub manifest.
 */
final class PluginInfo
{
	/** The Gradle project folder; for Plugin Hub entries a placeholder under the hub cache */
	final Path dir;
	final String title;
	final String description;
	final String author;
	final List<String> tags;
	/** @PluginDescriptor names, one per plugin class in the project */
	final List<String> pluginNames;
	/** Fully qualified plugin class names */
	final List<String> pluginClasses;
	/** Other project folders this one builds against (Gradle project(...) dependencies) */
	final List<Path> projectDeps;
	/** Simple class names from @PluginDependency */
	final List<String> pluginDependencies;
	/** May not exist yet for Plugin Hub entries (icons download lazily) */
	final Path icon;
	final boolean kotlin;
	/** Plugin Hub only */
	final String version;
	final String warning;

	PluginInfo(Path dir, String title, String description, String author, List<String> tags,
		List<String> pluginNames, List<String> pluginClasses, List<Path> projectDeps,
		List<String> pluginDependencies, Path icon, boolean kotlin, String version, String warning)
	{
		this.dir = dir;
		this.title = title;
		this.description = description;
		this.author = author;
		this.tags = List.copyOf(tags);
		this.pluginNames = List.copyOf(pluginNames);
		this.pluginClasses = List.copyOf(pluginClasses);
		this.projectDeps = List.copyOf(projectDeps);
		this.pluginDependencies = List.copyOf(pluginDependencies);
		this.icon = icon;
		this.kotlin = kotlin;
		this.version = version;
		this.warning = warning;
	}

	static PluginInfo fromHub(PluginHub.Entry e)
	{
		return new PluginInfo(PluginHub.DIR.resolve(e.internalName), e.displayName,
			e.description == null ? "" : e.description, e.author == null ? "" : e.author,
			e.tags == null ? List.of() : Arrays.asList(e.tags), List.of(e.displayName), List.of(), List.of(), List.of(),
			e.iconFile(), false, e.version, e.warning);
	}
}
