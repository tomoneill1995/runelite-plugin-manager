package dev.rlsideloader.manager;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Talks to the agent running inside RuneLite over its 127.0.0.1 control channel.
 */
final class AgentClient implements Closeable
{
	// Plain classes rather than records: the manager targets Java 11 so it can run on RuneLite's bundled JRE

	static final class Info
	{
		private long pid;
		private int port;
		private String token;

		long pid()
		{
			return pid;
		}

		int port()
		{
			return port;
		}

		String token()
		{
			return token;
		}
	}

	static final class LoadedPlugin
	{
		private String name;
		@SerializedName("class")
		private String className;
		private boolean active;

		String name()
		{
			return name;
		}

		String className()
		{
			return className;
		}

		boolean active()
		{
			return active;
		}
	}

	static final class LoadedJar
	{
		private String id;
		private List<LoadedPlugin> plugins;

		String id()
		{
			return id;
		}

		List<LoadedPlugin> plugins()
		{
			return plugins;
		}
	}

	/** The agent replied ERR: the command failed but the connection is fine */
	static final class AgentException extends Exception
	{
		AgentException(String message)
		{
			super(message);
		}
	}

	private static final Gson GSON = new Gson();
	private static final int TIMEOUT_MS = 30_000;

	private final Info info;
	private final Socket socket;
	private final BufferedReader in;
	private final PrintWriter out;

	private AgentClient(Info info, Socket socket) throws IOException
	{
		this.info = info;
		this.socket = socket;
		this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
		this.out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
	}

	/**
	 * Every running client with the agent: one agents/{pid}.json per client, plus the legacy
	 * agent.json an older agent build writes. Files left behind by killed clients are removed.
	 */
	static List<Info> discover()
	{
		List<Path> files = new ArrayList<>();
		if (Files.isDirectory(Paths.AGENTS_DIR))
		{
			try (Stream<Path> s = Files.list(Paths.AGENTS_DIR))
			{
				s.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(files::add);
			}
			catch (IOException ignored)
			{
			}
		}
		if (Files.exists(Paths.AGENT_INFO))
		{
			files.add(Paths.AGENT_INFO);
		}

		List<Info> out = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		for (Path file : files)
		{
			Info info = read(file);
			if (info == null || !seen.add(info.pid()))
			{
				continue;
			}
			if (!ProcessHandle.of(info.pid()).map(ProcessHandle::isAlive).orElse(false))
			{
				// left behind if RuneLite was killed
				if (!file.equals(Paths.AGENT_INFO))
				{
					try
					{
						Files.deleteIfExists(file);
					}
					catch (IOException ignored)
					{
					}
				}
				continue;
			}
			out.add(info);
		}
		out.sort(Comparator.comparingLong(Info::pid));
		return out;
	}

	private static Info read(Path file)
	{
		try
		{
			return GSON.fromJson(Files.readString(file), Info.class);
		}
		catch (IOException | RuntimeException e)
		{
			return null;
		}
	}

	/**
	 * Connects to one running agent, or returns null if it can't be reached.
	 */
	static AgentClient connect(Info info)
	{
		try
		{
			Socket socket = new Socket();
			socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), info.port()), 1000);
			socket.setSoTimeout(TIMEOUT_MS);
			AgentClient client = new AgentClient(info, socket);
			client.out.println("AUTH " + info.token());
			if (!"OK".equals(client.in.readLine()))
			{
				client.close();
				return null;
			}
			return client;
		}
		catch (IOException | RuntimeException e)
		{
			return null;
		}
	}

	long pid()
	{
		return info.pid();
	}

	List<LoadedJar> list() throws IOException, AgentException
	{
		return GSON.fromJson(send("LIST"), new TypeToken<List<LoadedJar>>()
		{
		}.getType());
	}

	/**
	 * @param deps ids of loaded jars whose classes this one uses
	 */
	void load(Path jar, List<String> deps) throws IOException, AgentException
	{
		send("LOAD " + jar.toAbsolutePath() + (deps.isEmpty() ? "" : "\t" + String.join(",", deps)));
	}

	void unload(String id) throws IOException, AgentException
	{
		send("UNLOAD " + id);
	}

	/**
	 * Sends one command and returns the JSON payload of the reply (empty if none).
	 */
	private synchronized String send(String command) throws IOException, AgentException
	{
		out.println(command);
		String reply = in.readLine();
		if (reply == null)
		{
			throw new IOException("Agent closed the connection");
		}
		if (reply.startsWith("ERR"))
		{
			throw new AgentException(reply.substring(3).trim());
		}
		return reply.length() > 2 ? reply.substring(3) : "";
	}

	@Override
	public void close()
	{
		try
		{
			socket.close();
		}
		catch (IOException ignored)
		{
		}
	}
}
