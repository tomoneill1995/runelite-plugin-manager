package dev.rlsideloader.manager;

import com.google.gson.Gson;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The official RuneLite Plugin Hub, read the same way the client reads it: the signed manifest
 * from repo.runelite.net, verified with the certificate inside the installed RuneLite client.
 * Jars are downloaded prebuilt and checked against the manifest's SHA-256 before loading.
 */
final class PluginHub
{
	static final Path DIR = Paths.HOME.resolve("hub");
	private static final Path JARS = DIR.resolve("jars");
	private static final Path ICONS = DIR.resolve("icons");
	private static final String CERT = "net/runelite/client/externalplugins/externalplugins.crt";
	private static final String PROPERTIES = "net/runelite/client/runelite.properties";
	private static final Gson GSON = new Gson();

	/** One Hub plugin: display data joined with its jar */
	static final class Entry
	{
		String internalName;
		String displayName;
		String version;
		String iconHash;
		String author;
		String description;
		String warning;
		String[] tags;
		String unavailableReason;
		long lastUpdatedAt;
		String jarHash;
		int jarSize;

		Path iconFile()
		{
			return iconHash == null ? null : ICONS.resolve(internalName + "_" + iconHash + ".png");
		}
	}

	private static final class Manifest
	{
		List<Jar> jars = List.of();
		List<Entry> display = List.of();
	}

	private static final class Jar
	{
		String internalName;
		String jarHash;
		int jarSize;
	}

	private final HttpClient http = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(15))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();

	private volatile String baseUrl;
	private volatile String version;
	private volatile Map<String, Entry> entries = Map.of();

	Map<String, Entry> entries()
	{
		return entries;
	}

	String version()
	{
		return version;
	}

	/**
	 * Loads the manifest: downloaded fresh when online is true or nothing is cached,
	 * otherwise from the cached copy. Returns the plugins whose jar changed since the last load.
	 */
	List<String> load(boolean online) throws Exception
	{
		Client client = installedClient();
		baseUrl = client.hubUrl;
		version = client.hubVersion;

		Path cached = DIR.resolve("manifest_" + version + "_full.js");
		byte[] raw;
		if (online || !Files.exists(cached))
		{
			raw = get(baseUrl + "/manifest/" + version + "_full.js");
			parse(raw, client.certificate); // verify before caching
			Files.createDirectories(DIR);
			Path tmp = DIR.resolve("manifest.tmp");
			Files.write(tmp, raw);
			Files.move(tmp, cached, StandardCopyOption.REPLACE_EXISTING);
		}
		else
		{
			raw = Files.readAllBytes(cached);
		}

		Map<String, Entry> previous = entries;
		Map<String, Entry> next = parse(raw, client.certificate);
		entries = next;
		return next.values().stream()
			.filter(e -> previous.containsKey(e.internalName) && !previous.get(e.internalName).jarHash.equals(e.jarHash))
			.map(e -> e.internalName)
			.collect(Collectors.toList());
	}

	/** Downloads (or reuses) a plugin's jar, checking size and SHA-256 against the manifest */
	Path jar(Entry entry) throws Exception
	{
		Path file = JARS.resolve(entry.internalName + "_" + entry.jarHash + ".jar");
		if (Files.exists(file) && matches(Files.readAllBytes(file), entry))
		{
			return file;
		}
		byte[] bytes = get(baseUrl + "/jar/" + entry.internalName + "_" + entry.jarHash + ".jar");
		if (!matches(bytes, entry))
		{
			throw new IOException("Downloaded jar for " + entry.displayName + " doesn't match the Plugin Hub's hash");
		}
		Files.createDirectories(JARS);
		Files.write(file, bytes);
		removeOldJars(entry, file);
		return file;
	}

	/** Downloads a plugin's icon into the cache if it isn't there yet */
	void icon(Entry entry) throws Exception
	{
		Path file = entry.iconFile();
		if (file == null || Files.exists(file))
		{
			return;
		}
		byte[] bytes = get(baseUrl + "/icon/" + entry.internalName + "_" + entry.iconHash + ".png");
		Files.createDirectories(ICONS);
		Path tmp = ICONS.resolve(file.getFileName() + ".tmp");
		Files.write(tmp, bytes);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
	}

	/** Plugin Hub web page for a plugin */
	static String webUrl(String internalName)
	{
		return "https://runelite.net/plugin-hub/show/" + internalName;
	}

	private static boolean matches(byte[] bytes, Entry entry) throws Exception
	{
		if (bytes.length != entry.jarSize)
		{
			return false;
		}
		byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).equals(entry.jarHash);
	}

	private static void removeOldJars(Entry entry, Path keep)
	{
		try (DirectoryStream<Path> old = Files.newDirectoryStream(JARS, entry.internalName + "_*.jar"))
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

	/** Manifest format: 4-byte signature length, SHA256withRSA signature, then JSON */
	private static Map<String, Entry> parse(byte[] raw, Certificate certificate) throws Exception
	{
		ByteBuffer buf = ByteBuffer.wrap(raw);
		int sigLength = buf.getInt();
		byte[] signature = new byte[sigLength];
		buf.get(signature);
		byte[] data = new byte[buf.remaining()];
		buf.get(data);

		Signature verifier = Signature.getInstance("SHA256withRSA");
		verifier.initVerify(certificate);
		verifier.update(data);
		if (!verifier.verify(signature))
		{
			throw new IOException("Plugin Hub manifest failed signature verification");
		}

		Manifest manifest = GSON.fromJson(new String(data, StandardCharsets.UTF_8), Manifest.class);
		Map<String, Jar> jars = new HashMap<>();
		for (Jar jar : manifest.jars)
		{
			jars.put(jar.internalName, jar);
		}
		Map<String, Entry> out = new HashMap<>();
		for (Entry entry : manifest.display)
		{
			Jar jar = jars.get(entry.internalName);
			if (jar == null || entry.unavailableReason != null)
			{
				continue;
			}
			entry.jarHash = jar.jarHash;
			entry.jarSize = jar.jarSize;
			out.put(entry.internalName, entry);
		}
		return out;
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

	private static final class Client
	{
		String hubUrl;
		String hubVersion;
		Certificate certificate;
	}

	/**
	 * Hub URL, version and signing certificate from the newest installed RuneLite client,
	 * so the manifest matches the client the plugins will run in.
	 */
	private static Client installedClient() throws Exception
	{
		Path repo = Path.of(System.getProperty("user.home"), ".runelite", "repository2");
		Optional<Path> jar;
		try (Stream<Path> files = Files.list(repo))
		{
			jar = files.filter(p -> p.getFileName().toString().matches("client-[\\d.]+\\.jar"))
				.max(Comparator.comparing(PluginHub::versionKey));
		}
		if (jar.isEmpty())
		{
			throw new IOException("No RuneLite client found in " + repo + "; launch RuneLite once first");
		}

		try (ZipFile zip = new ZipFile(jar.get().toFile()))
		{
			Properties props = new Properties();
			ZipEntry propsEntry = zip.getEntry(PROPERTIES);
			ZipEntry certEntry = zip.getEntry(CERT);
			if (propsEntry == null || certEntry == null)
			{
				throw new IOException("Unexpected RuneLite client layout in " + jar.get().getFileName());
			}
			try (InputStream in = zip.getInputStream(propsEntry))
			{
				props.load(in);
			}
			Client client = new Client();
			client.hubUrl = props.getProperty("runelite.pluginhub.url", "https://repo.runelite.net/plugins");
			client.hubVersion = props.getProperty("runelite.pluginhub.version", props.getProperty("runelite.version"));
			try (InputStream in = zip.getInputStream(certEntry))
			{
				client.certificate = CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(in.readAllBytes()));
			}
			return client;
		}
	}

	/** client-1.13.2.jar -> sortable "00001.00013.00002" */
	private static String versionKey(Path p)
	{
		String v = p.getFileName().toString().replaceAll("^client-|\\.jar$", "");
		List<String> parts = new ArrayList<>();
		for (String part : v.split("\\."))
		{
			parts.add(String.format("%05d", Integer.parseInt(part)));
		}
		return String.join(".", parts);
	}
}
