package dev.rlsideloader.manager;

import java.nio.file.Path;

final class Sources
{
	private Sources()
	{
	}

	/** Short name for a source: owner/repo for GitHub, else the folder name */
	static String label(ManagerConfig.Source source)
	{
		if (source.type == ManagerConfig.SourceType.HUB)
		{
			return "RuneLite Plugin Hub";
		}
		if (source.type == ManagerConfig.SourceType.GIT && source.url != null)
		{
			String s = source.url.replaceAll("\\.git$", "").replaceAll("/+$", "");
			String[] parts = s.split("[/:]");
			if (parts.length >= 2)
			{
				return parts[parts.length - 2] + "/" + parts[parts.length - 1];
			}
		}
		Path p = Path.of(source.path);
		return p.getFileName() != null ? p.getFileName().toString() : source.path;
	}

	/** Web page for a plugin folder in a GitHub source, or null */
	static String webUrl(ManagerConfig.Source source, Path dir)
	{
		if (source.type == ManagerConfig.SourceType.HUB)
		{
			return PluginHub.webUrl(dir.getFileName().toString());
		}
		if (source.type != ManagerConfig.SourceType.GIT || source.url == null || !source.url.contains("github.com"))
		{
			return null;
		}
		String base = source.url.replaceAll("\\.git$", "").replaceAll("^git@github\\.com:", "https://github.com/");
		Path root = Path.of(source.path);
		String rel = root.relativize(dir).toString().replace('\\', '/');
		return rel.isEmpty() ? base : base + "/tree/HEAD/" + rel;
	}
}
