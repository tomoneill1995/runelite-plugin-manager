package dev.rlsideloader.manager;

import com.google.gson.Gson;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A private plugin hub: a manifest.json somewhere on the web listing prebuilt plugin jars kept
 * next to it (see the rl-hub repo). Like the official Plugin Hub, jars are downloaded prebuilt
 * and checked against the manifest's SHA-256 before loading; unlike it there's no signature,
 * HTTPS and the secret URL are the trust.
 * <p>
 * Everything is cached under ~/.rl-sideloader/hubs/{hash of url}/ so the plugins still load offline.
 */
final class PrivateHub
{
	static final Path HUBS = Paths.HOME.resolve("hubs");
	private static final Gson GSON = new Gson();

	/** One plugin in the manifest */
	static final class Entry
	{
		String id;
		String name;
		String description;
		String author;
		List<String> tags;
		String version;
		String repo;
		String jar;
		String sha256;
		long size;
		String icon;
		List<String> pluginNames;
		List<String> pluginClasses;
		List<String> pluginDependencies;
	}

	private static final class Manifest
	{
		int format;
		String name;
		boolean installByDefault;
		List<Entry> plugins;
	}

	private final HttpClient http = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(15))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();

	private final String manifestUrl;
	private final Path dir;
	private final Path jars;
	private final Path icons;
	private volatile Manifest manifest;
	private volatile Map<String, Entry> entries = Map.of();

	PrivateHub(String manifestUrl, Path dir)
	{
		this.manifestUrl = manifestUrl;
		this.dir = dir;
		this.jars = dir.resolve("jars");
		this.icons = dir.resolve("icons");
	}

	/** "https://host/path/" or ".../manifest.json" -> the manifest URL */
	static String normalizeUrl(String input)
	{
		String url = input.trim();
		if (!url.contains("://"))
		{
			url = "https://" + url;
		}
		if (!url.toLowerCase(Locale.ROOT).endsWith(".json"))
		{
			url = url.replaceAll("/+$", "") + "/manifest.json";
		}
		return url;
	}

	/** Cache folder for a manifest URL; the same URL always maps to the same folder */
	static Path dirFor(String manifestUrl)
	{
		return HUBS.resolve(hex(sha256(manifestUrl.getBytes(StandardCharsets.UTF_8))).substring(0, 12));
	}

	String url()
	{
		return manifestUrl;
	}

	/** Display name from the manifest, or the host while it hasn't loaded */
	String name()
	{
		Manifest m = manifest;
		if (m != null && m.name != null && !m.name.isBlank())
		{
			return m.name;
		}
		try
		{
			String host = URI.create(manifestUrl).getHost();
			return host != null ? host : manifestUrl;
		}
		catch (RuntimeException e)
		{
			return manifestUrl;
		}
	}

	boolean installByDefault()
	{
		Manifest m = manifest;
		return m != null && m.installByDefault;
	}

	Map<String, Entry> entries()
	{
		return entries;
	}

	/** The placeholder folder that stands for a plugin in the row list (nothing is written there) */
	Path entryDir(Entry entry)
	{
		return dir.resolve("plugins").resolve(entry.id);
	}

	Path iconFile(Entry entry)
	{
		if (entry.icon == null || entry.icon.isBlank())
		{
			return null;
		}
		String name = entry.icon.substring(entry.icon.lastIndexOf('/') + 1);
		return icons.resolve(name);
	}

	/**
	 * Loads the manifest: downloaded when online is true or nothing is cached, otherwise from
	 * the cached copy. Returns the ids of plugins whose jar changed since the last load.
	 */
	List<String> load(boolean online) throws Exception
	{
		Path cached = dir.resolve("manifest.json");
		byte[] raw;
		if (online || !Files.exists(cached))
		{
			raw = get(manifestUrl);
			parse(raw); // validate before caching
			Files.createDirectories(dir);
			Path tmp = dir.resolve("manifest.json.tmp");
			Files.write(tmp, raw);
			Files.move(tmp, cached, StandardCopyOption.REPLACE_EXISTING);
		}
		else
		{
			raw = Files.readAllBytes(cached);
		}

		Map<String, Entry> previous = entries;
		Manifest m = parse(raw);
		Map<String, Entry> next = new LinkedHashMap<>();
		for (Entry e : m.plugins)
		{
			next.put(e.id, e);
		}
		manifest = m;
		entries = next;
		return next.values().stream()
			.filter(e -> previous.containsKey(e.id) && !previous.get(e.id).sha256.equalsIgnoreCase(e.sha256))
			.map(e -> e.id)
			.collect(Collectors.toList());
	}

	/** Downloads (or reuses) a plugin's jar, checking size and SHA-256 against the manifest */
	Path jar(Entry entry) throws Exception
	{
		Path file = jars.resolve(entry.id + "_" + entry.sha256.substring(0, 12) + ".jar");
		if (Files.exists(file) && matches(Files.readAllBytes(file), entry))
		{
			return file;
		}
		byte[] bytes = get(resolve(entry.jar));
		if (!matches(bytes, entry))
		{
			throw new IOException("Downloaded jar for " + entry.name + " doesn't match the hub's hash");
		}
		Files.createDirectories(jars);
		Files.write(file, bytes);
		removeOldJars(entry, file);
		return file;
	}

	/** Downloads a plugin's icon into the cache if it isn't there yet */
	void icon(Entry entry) throws Exception
	{
		Path file = iconFile(entry);
		if (file == null || Files.exists(file))
		{
			return;
		}
		byte[] bytes = get(resolve(entry.icon));
		Files.createDirectories(icons);
		Path tmp = icons.resolve(file.getFileName() + ".tmp");
		Files.write(tmp, bytes);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
	}

	private String resolve(String relative)
	{
		return URI.create(manifestUrl).resolve(relative).toString();
	}

	private static Manifest parse(byte[] raw) throws IOException
	{
		Manifest m;
		try
		{
			m = GSON.fromJson(new String(raw, StandardCharsets.UTF_8), Manifest.class);
		}
		catch (RuntimeException e)
		{
			throw new IOException("Not a plugin hub manifest: " + e.getMessage());
		}
		if (m == null || m.plugins == null)
		{
			throw new IOException("Not a plugin hub manifest (no plugins list)");
		}
		if (m.format > 1)
		{
			throw new IOException("Hub manifest format " + m.format + " needs a newer manager");
		}
		Map<String, Entry> seen = new HashMap<>();
		for (Entry e : m.plugins)
		{
			if (e.id == null || e.id.isBlank() || e.jar == null || e.sha256 == null || e.sha256.length() < 12)
			{
				throw new IOException("Hub manifest has an entry without id, jar or sha256");
			}
			if (seen.put(e.id, e) != null)
			{
				throw new IOException("Hub manifest lists " + e.id + " twice");
			}
			if (e.name == null || e.name.isBlank())
			{
				e.name = e.id;
			}
			e.tags = e.tags == null ? List.of() : e.tags;
			e.pluginNames = e.pluginNames == null || e.pluginNames.isEmpty() ? List.of(e.name) : e.pluginNames;
			e.pluginClasses = e.pluginClasses == null ? List.of() : e.pluginClasses;
			e.pluginDependencies = e.pluginDependencies == null ? List.of() : e.pluginDependencies;
		}
		return m;
	}

	private static boolean matches(byte[] bytes, Entry entry)
	{
		return (entry.size <= 0 || bytes.length == entry.size) && hex(sha256(bytes)).equalsIgnoreCase(entry.sha256);
	}

	private void removeOldJars(Entry entry, Path keep)
	{
		try (DirectoryStream<Path> old = Files.newDirectoryStream(jars, entry.id + "_*.jar"))
		{
			for (Path p : old)
			{
				if (!p.equals(keep))
				{
					Files.deleteIfExists(p);
				}
			}
		}
		catch (IOException ignored)
		{
		}
	}

	private byte[] get(String url) throws IOException, InterruptedException
	{
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(60))
			.header("User-Agent", "rl-sideloader")
			.build();
		HttpResponse<byte[]> res = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
		if (res.statusCode() != 200)
		{
			throw new IOException("HTTP " + res.statusCode() + " from " + url);
		}
		return res.body();
	}

	private static byte[] sha256(byte[] bytes)
	{
		try
		{
			return MessageDigest.getInstance("SHA-256").digest(bytes);
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException(e);
		}
	}

	private static String hex(byte[] digest)
	{
		StringBuilder sb = new StringBuilder();
		for (byte b : digest)
		{
			sb.append(String.format("%02x", b));
		}
		return sb.toString();
	}
}
