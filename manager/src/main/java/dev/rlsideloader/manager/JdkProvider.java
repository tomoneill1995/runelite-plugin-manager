package dev.rlsideloader.manager;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Finds a Java 11 JDK for building plugins (the Plugin Hub template targets 11), or downloads
 * Eclipse Temurin 11 into ~/.rl-sideloader/jdks the first time one is needed, so users don't
 * have to install a JDK themselves.
 */
final class JdkProvider
{
	private static final Path DIR = Paths.HOME.resolve("jdks");
	private static final String ASSETS_URL = "https://api.adoptium.net/v3/assets/latest/11/hotspot"
		+ "?architecture=x64&image_type=jdk&os=windows&vendor=eclipse";
	/** For testing the download path on a machine that has a JDK */
	private static final boolean IGNORE_SYSTEM = Boolean.getBoolean("rlsideloader.ignoreSystemJdks");

	private final HttpClient http = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(15))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();

	private Path cached;

	/** A JDK 11 home, downloading one if none is installed */
	synchronized Path jdk11(Consumer<String> log) throws Exception
	{
		if (cached != null && isJdk11(cached))
		{
			return cached;
		}
		Optional<Path> found = findManaged().or(() -> IGNORE_SYSTEM ? Optional.empty() : findInstalled());
		cached = found.isPresent() ? found.get() : download(log);
		return cached;
	}

	private static Optional<Path> findManaged()
	{
		return children(DIR).stream().filter(JdkProvider::isJdk11).findFirst();
	}

	/** Common install locations for JDK 11 on Windows, plus JAVA_HOME */
	private static Optional<Path> findInstalled()
	{
		List<Path> candidates = new ArrayList<>();
		String javaHome = System.getenv("JAVA_HOME");
		if (javaHome != null && !javaHome.isBlank())
		{
			candidates.add(Path.of(javaHome));
		}
		for (String root : List.of("ProgramFiles", "ProgramFiles(x86)"))
		{
			String base = System.getenv(root);
			if (base == null)
			{
				continue;
			}
			for (String vendor : List.of("Eclipse Adoptium", "Microsoft", "Java", "Zulu", "AdoptOpenJDK", "Amazon Corretto", "BellSoft"))
			{
				candidates.addAll(children(Path.of(base, vendor)));
			}
		}
		candidates.addAll(children(Path.of(System.getProperty("user.home"), ".jdks")));
		return candidates.stream().filter(JdkProvider::isJdk11).findFirst();
	}

	private static boolean isJdk11(Path home)
	{
		Path release = home.resolve("release");
		if (!Files.isRegularFile(home.resolve("bin").resolve("javac.exe")) || !Files.isRegularFile(release))
		{
			return false;
		}
		try
		{
			return Files.readAllLines(release).stream().anyMatch(l -> l.startsWith("JAVA_VERSION=\"11"));
		}
		catch (IOException e)
		{
			return false;
		}
	}

	private static List<Path> children(Path dir)
	{
		if (!Files.isDirectory(dir))
		{
			return List.of();
		}
		List<Path> out = new ArrayList<>();
		try (DirectoryStream<Path> s = Files.newDirectoryStream(dir))
		{
			s.forEach(p ->
			{
				if (Files.isDirectory(p))
				{
					out.add(p);
				}
			});
		}
		catch (IOException ignored)
		{
		}
		return out;
	}

	private static final class Asset
	{
		Binary binary;
	}

	private static final class Binary
	{
		@com.google.gson.annotations.SerializedName("package")
		Package_ _package;
	}

	private static final class Package_
	{
		String name;
		String link;
		String checksum;
		long size;
	}

	private Path download(Consumer<String> log) throws Exception
	{
		HttpRequest assetsRequest = HttpRequest.newBuilder(URI.create(ASSETS_URL))
			.timeout(Duration.ofSeconds(30)).header("User-Agent", "rl-sideloader").build();
		String json = http.send(assetsRequest, HttpResponse.BodyHandlers.ofString()).body();
		List<Asset> assets = new Gson().fromJson(json, new TypeToken<List<Asset>>()
		{
		}.getType());
		if (assets == null || assets.isEmpty() || assets.get(0).binary == null || assets.get(0).binary._package == null)
		{
			throw new IOException("Couldn't find a Java 11 JDK to download from Adoptium");
		}
		Package_ pkg = assets.get(0).binary._package;

		log.accept("No Java 11 JDK found; downloading " + pkg.name + " (" + pkg.size / (1024 * 1024)
			+ " MB) from Eclipse Adoptium. One time only.");
		Files.createDirectories(DIR);
		Path zip = DIR.resolve(pkg.name + ".part");
		HttpRequest request = HttpRequest.newBuilder(URI.create(pkg.link))
			.timeout(Duration.ofMinutes(15)).header("User-Agent", "rl-sideloader").build();
		HttpResponse<InputStream> res = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
		if (res.statusCode() != 200)
		{
			throw new IOException("JDK download failed: HTTP " + res.statusCode());
		}

		MessageDigest sha = MessageDigest.getInstance("SHA-256");
		try (InputStream in = new DigestInputStream(res.body(), sha); OutputStream out = Files.newOutputStream(zip))
		{
			byte[] buf = new byte[1 << 16];
			long done = 0;
			long nextReport = 50L << 20;
			int n;
			while ((n = in.read(buf)) > 0)
			{
				out.write(buf, 0, n);
				done += n;
				if (done >= nextReport)
				{
					log.accept("  " + (done >> 20) + " / " + (pkg.size >> 20) + " MB");
					nextReport += 50L << 20;
				}
			}
		}

		StringBuilder hex = new StringBuilder();
		for (byte b : sha.digest())
		{
			hex.append(String.format("%02x", b));
		}
		if (!hex.toString().equalsIgnoreCase(pkg.checksum))
		{
			Files.deleteIfExists(zip);
			throw new IOException("JDK download checksum didn't match Adoptium's; not using it");
		}

		Path home = unzip(zip, DIR);
		Files.deleteIfExists(zip);
		if (!isJdk11(home))
		{
			throw new IOException("Downloaded JDK at " + home + " doesn't look like Java 11");
		}
		log.accept("Java 11 JDK ready at " + home);
		return home;
	}

	/** Extracts the zip (a single top-level folder) and returns that folder */
	private static Path unzip(Path zip, Path into) throws IOException
	{
		Path tmp = Files.createTempDirectory(into, "unzip");
		Path root = null;
		try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip)))
		{
			ZipEntry e;
			while ((e = in.getNextEntry()) != null)
			{
				Path target = tmp.resolve(e.getName()).normalize();
				if (!target.startsWith(tmp))
				{
					throw new IOException("Bad zip entry " + e.getName());
				}
				if (root == null)
				{
					root = tmp.resolve(tmp.relativize(target).getName(0));
				}
				if (e.isDirectory())
				{
					Files.createDirectories(target);
				}
				else
				{
					Files.createDirectories(target.getParent());
					Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
		if (root == null)
		{
			throw new IOException("Empty JDK zip");
		}
		Path dest = into.resolve(root.getFileName());
		Files.move(root, dest, StandardCopyOption.REPLACE_EXISTING);
		try (Stream<Path> left = Files.walk(tmp))
		{
			left.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		}
		return dest;
	}
}
