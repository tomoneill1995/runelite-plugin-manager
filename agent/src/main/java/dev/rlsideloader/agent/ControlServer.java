package dev.rlsideloader.agent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Line-based control channel on 127.0.0.1 for the manager app.
 * <p>
 * The port and a random token are written to ~/.rl-sideloader/agent.json. A client must
 * send "AUTH token" first; every command then gets one reply line, "OK [json]" or "ERR message".
 * <pre>
 * PING                 -> OK "pong"
 * LIST                 -> OK [{"id":..,"source":..,"plugins":[{"name":..,"class":..,"active":..}]}]
 * LOAD path/to.jar[\tdep1,dep2] -> OK "id"   (replaces it if already loaded; deps are ids
 *                         of loaded jars whose classes it uses, and must be loaded first)
 * UNLOAD id            -> OK
 * RELOAD id            -> OK "id"   (reloads from the original path)
 * SCAN                 -> OK ["id", ...]   (loads every jar in the plugins folder)
 * </pre>
 */
final class ControlServer
{
	private static final Logger log = LoggerFactory.getLogger(ControlServer.class);

	private final PluginHost host;
	private final AgentConfig config;
	private final String token;

	ControlServer(PluginHost host, AgentConfig config)
	{
		this.host = host;
		this.config = config;
		byte[] bytes = new byte[24];
		new SecureRandom().nextBytes(bytes);
		StringBuilder sb = new StringBuilder();
		for (byte b : bytes)
		{
			sb.append(String.format("%02x", b));
		}
		this.token = sb.toString();
	}

	void run() throws IOException
	{
		try (ServerSocket server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress()))
		{
			writeInfoFile(server.getLocalPort());
			log.info("Sideloader control channel listening on 127.0.0.1:{}", server.getLocalPort());

			while (!server.isClosed())
			{
				Socket socket = server.accept();
				Thread thread = new Thread(() -> handle(socket), "rl-sideloader-conn");
				thread.setDaemon(true);
				thread.start();
			}
		}
	}

	private void writeInfoFile(int port) throws IOException
	{
		Path info = config.getInfoFile();
		Files.createDirectories(info.getParent());
		String json = "{\"pid\":" + ProcessHandle.current().pid()
			+ ",\"port\":" + port
			+ ",\"token\":" + Json.str(token)
			+ ",\"pluginDir\":" + Json.str(config.getPluginDir().toString()) + "}";
		Files.write(info, json.getBytes(StandardCharsets.UTF_8));
		info.toFile().deleteOnExit();
	}

	private void handle(Socket socket)
	{
		try (socket;
			 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
			 PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true))
		{
			String auth = in.readLine();
			if (auth == null || !auth.startsWith("AUTH ") || !constantTimeEquals(auth.substring(5).trim(), token))
			{
				out.println("ERR unauthorized");
				return;
			}
			out.println("OK");

			String line;
			while ((line = in.readLine()) != null)
			{
				line = line.trim();
				if (line.isEmpty())
				{
					continue;
				}
				out.println(execute(line));
			}
		}
		catch (IOException e)
		{
			log.debug("Control connection closed", e);
		}
	}

	private String execute(String line)
	{
		int space = line.indexOf(' ');
		String cmd = (space < 0 ? line : line.substring(0, space)).toUpperCase();
		String arg = space < 0 ? "" : line.substring(space + 1).trim();

		try
		{
			switch (cmd)
			{
				case "PING":
					return "OK " + Json.str("pong");
				case "LIST":
					return "OK " + listJson();
				case "LOAD":
				{
					// LOAD path[<TAB>dep1,dep2]
					String[] parts = requireArg(arg).split("\t", 2);
					List<String> deps = parts.length < 2 || parts[1].isBlank()
						? List.of()
						: Arrays.asList(parts[1].trim().split("\\s*,\\s*"));
					return "OK " + Json.str(host.load(Paths.get(parts[0].trim()), deps).getId());
				}
				case "UNLOAD":
					host.unload(requireArg(arg));
					return "OK";
				case "RELOAD":
					return "OK " + Json.str(host.reload(requireArg(arg)).getId());
				case "SCAN":
					return "OK " + Json.strList(host.loadDirectory(config.getPluginDir()));
				default:
					return "ERR unknown command " + cmd;
			}
		}
		catch (Exception e)
		{
			log.warn("Sideloader command failed: {}", line, e);
			String msg = e.getMessage() != null ? e.getMessage() : e.toString();
			return "ERR " + msg.replace('\n', ' ');
		}
	}

	private String listJson()
	{
		StringBuilder sb = new StringBuilder("[");
		List<PluginHost.LoadedJar> jars = host.list();
		for (int i = 0; i < jars.size(); i++)
		{
			PluginHost.LoadedJar jar = jars.get(i);
			if (i > 0)
			{
				sb.append(',');
			}
			sb.append("{\"id\":").append(Json.str(jar.getId()))
				.append(",\"source\":").append(Json.str(jar.getSource().toString()))
				.append(",\"deps\":").append(Json.strList(jar.getDeps()))
				.append(",\"plugins\":[");
			List<Plugin> plugins = jar.getPlugins();
			for (int j = 0; j < plugins.size(); j++)
			{
				Plugin p = plugins.get(j);
				if (j > 0)
				{
					sb.append(',');
				}
				sb.append("{\"name\":").append(Json.str(p.getClass().getAnnotation(PluginDescriptor.class).name()))
					.append(",\"class\":").append(Json.str(p.getClass().getName()))
					.append(",\"active\":").append(host.isActive(p))
					.append('}');
			}
			sb.append("]}");
		}
		return sb.append(']').toString();
	}

	private static String requireArg(String arg)
	{
		if (arg.isEmpty())
		{
			throw new IllegalArgumentException("missing argument");
		}
		return arg;
	}

	private static boolean constantTimeEquals(String a, String b)
	{
		return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
	}
}
