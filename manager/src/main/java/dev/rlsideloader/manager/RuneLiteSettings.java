package dev.rlsideloader.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * RuneLite's launcher settings: settings.json next to the RuneLite install (%LOCALAPPDATA%\RuneLite),
 * the file behind "RuneLite (configure)".
 * <p>
 * When RuneLite is started by the Jagex Launcher this manager isn't in the loop, so the agent goes
 * in here instead: the launcher passes {@code jvmArguments} to the client in FORK/JVM launch mode
 * (what RuneLite.exe uses), and the client inherits the Jagex session from its environment.
 * Only entries this manager wrote are ever touched; the rest of the file is preserved as-is.
 */
final class RuneLiteSettings
{
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** Part of every agent jar name, so stale entries from older manager builds are replaced too */
	private static final String AGENT_MARKER = "rl-sideloader-agent-";

	private RuneLiteSettings()
	{
	}

	static JsonObject load() throws IOException
	{
		if (!Files.exists(Paths.RUNELITE_SETTINGS))
		{
			return new JsonObject();
		}
		JsonElement root = JsonParser.parseString(Files.readString(Paths.RUNELITE_SETTINGS));
		return root != null && root.isJsonObject() ? root.getAsJsonObject() : new JsonObject();
	}

	static void save(JsonObject settings) throws IOException
	{
		Files.createDirectories(Paths.RUNELITE_SETTINGS.getParent());
		Path tmp = Paths.RUNELITE_SETTINGS.resolveSibling("settings.json.tmp");
		Files.writeString(tmp, GSON.toJson(settings), StandardCharsets.UTF_8);
		Files.move(tmp, Paths.RUNELITE_SETTINGS, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	/** The launch mode written in the file, or null when unset (the launcher's default is AUTO) */
	static String launchMode(JsonObject settings)
	{
		JsonElement mode = settings.get("launchMode");
		return mode == null || mode.isJsonNull() ? null : mode.getAsString();
	}

	/**
	 * Rewrites one list setting: drops every entry in {@code previous} (what this manager wrote
	 * last time) and any stale agent entry, keeps everything else, then appends {@code wanted}.
	 */
	static void setManaged(JsonObject settings, String key, List<String> previous, List<String> wanted)
	{
		List<String> kept = new ArrayList<>();
		JsonElement existing = settings.get(key);
		if (existing != null && existing.isJsonArray())
		{
			for (JsonElement e : existing.getAsJsonArray())
			{
				String s = e.isJsonNull() ? "" : e.getAsString();
				if (s.isEmpty() || previous.contains(s) || wanted.contains(s) || s.contains(AGENT_MARKER))
				{
					continue;
				}
				kept.add(s);
			}
		}
		kept.addAll(wanted);

		JsonArray array = new JsonArray();
		for (String s : kept)
		{
			array.add(s);
		}
		settings.add(key, array);
	}
}
