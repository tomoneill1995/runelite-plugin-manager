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
import java.util.List;

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
	 * Connects to the running agent, or returns null if no client with the agent is running.
	 */
	static AgentClient tryConnect()
	{
		Path file = Paths.AGENT_INFO;
		if (!Files.exists(file))
		{
			return null;
		}

		try
		{
			Info info = GSON.fromJson(Files.readString(file), Info.class);
			// agent.json is left behind if RuneLite is killed
			if (info == null || ProcessHandle.of(info.pid()).map(ProcessHandle::isAlive).orElse(false) == false)
			{
				return null;
			}

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
