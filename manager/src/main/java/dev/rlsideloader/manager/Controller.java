package dev.rlsideloader.manager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.swing.SwingUtilities;

/**
 * The manager's logic: turns configured sources into plugin rows, keeps them in sync with the
 * agent, and runs builds/loads. Slow work runs on a single worker thread so operations never
 * overlap; the poller connects to the agent and refreshes status every couple of seconds.
 * <p>
 * Like RuneLite's Plugin Hub, a plugin is "installed" (listed under Installed) separately from
 * "enabled" (loaded into the client).
 */
final class Controller
{
	interface Listener
	{
		/** structural: rows or sources were added or removed, not just updated */
		void rowsChanged(boolean structural);

		void log(String line);
	}

	@FunctionalInterface
	private interface Task
	{
		void run() throws Exception;
	}

	private static final int SCAN_DEPTH = 3;
	private static final Set<String> SCAN_SKIP = Set.of("build", "node_modules", "out", "bin", "target", "src", "gradle");

	private final ManagerConfig config;
	private final ChangeWatcher watcher;
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> daemon(r, "worker"));
	private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "poller"));
	/** Plugin projects found in each LOCAL/GIT source, by source path; rescanned on demand */
	private final Map<String, List<PluginInfo>> projects = new ConcurrentHashMap<>();

	private final PluginHub hub = new PluginHub();
	/** Plugin Hub entries as PluginInfo, rebuilt when the manifest loads */
	private volatile List<PluginInfo> hubInfos = List.of();
	private final ExecutorService iconLoader = Executors.newFixedThreadPool(3, r -> daemon(r, "icons"));
	private final Set<String> iconsRequested = ConcurrentHashMap.newKeySet();

	private volatile List<PluginRow> rows = List.of();
	private volatile AgentClient agent;
	private volatile String lastListSignature = "";
	private volatile String lastLog = "";
	private Listener listener;

	Controller(ManagerConfig config) throws IOException
	{
		this.config = config;
		this.watcher = new ChangeWatcher(this::onFilesChanged);
	}

	void start(Listener listener)
	{
		this.listener = listener;
		worker.execute(() ->
		{
			rescanAll();
			refreshRows();
			poller.scheduleWithFixedDelay(this::poll, 0, 2, TimeUnit.SECONDS);
		});
	}

	// ---- queries, for the UI ----

	List<PluginRow> rows()
	{
		return rows;
	}

	List<ManagerConfig.Source> sources()
	{
		synchronized (config)
		{
			return List.copyOf(config.sources);
		}
	}

	long pluginCount(ManagerConfig.Source source)
	{
		return rows.stream().filter(r -> r.source() == source).count();
	}

	boolean isConnected()
	{
		return agent != null;
	}

	long agentPid()
	{
		AgentClient a = agent;
		return a == null ? -1 : a.pid();
	}

	String lastLog()
	{
		return lastLog;
	}

	boolean isTelemetryDisabled()
	{
		return config.disableTelemetry;
	}

	boolean isPullOnLaunch()
	{
		return config.pullOnLaunch;
	}

	/** Installed rows that need this one, directly or not */
	List<PluginRow> installedDependents(PluginRow row)
	{
		List<PluginRow> out = new ArrayList<>();
		collectDependents(row, out);
		out.removeIf(r -> !r.installed());
		return out;
	}

	/** Rows this one needs that aren't installed yet */
	List<PluginRow> missingDependencies(PluginRow row)
	{
		Set<PluginRow> all = new LinkedHashSet<>();
		collectDeps(row, all);
		return all.stream().filter(r -> !r.installed()).collect(Collectors.toList());
	}

	// ---- actions, called from the UI ----

	void launch()
	{
		if (agent != null)
		{
			log("RuneLite is already running with the agent");
			return;
		}
		worker.execute(() ->
		{
			if (config.pullOnLaunch)
			{
				pullAllNow();
			}
			try
			{
				RuneLiteLauncher.launch(config.disableTelemetry);
				log("Starting RuneLite" + (config.disableTelemetry ? " (telemetry off)" : "")
					+ "; plugins load once it reaches the login screen");
			}
			catch (IOException e)
			{
				log("Launch failed: " + e.getMessage());
			}
		});
	}

	/** Install (and enable) a plugin and everything it needs */
	void install(PluginRow row)
	{
		Set<PluginRow> chain = new LinkedHashSet<>();
		collectDeps(row, chain);
		chain.add(row);
		for (PluginRow r : chain)
		{
			if (r != row && !r.installed())
			{
				log("Also installing " + r.displayName() + " (needed by " + row.displayName() + ")");
			}
			r.settings().installed = true;
			r.settings().enabled = true;
		}
		save();
		if (agent != null)
		{
			runFor(row, "Install", () -> loadRow(row, false));
		}
		else
		{
			log("Installed " + row.displayName() + "; it loads when RuneLite starts");
		}
		changed(true);
	}

	/** Uninstall a plugin, and anything installed that needs it */
	void uninstall(PluginRow row)
	{
		List<PluginRow> dependents = installedDependents(row);
		for (PluginRow r : dependents)
		{
			r.settings().installed = false;
			r.settings().enabled = false;
		}
		row.settings().installed = false;
		row.settings().enabled = false;
		row.settings().autoReload = false;
		watcher.unwatch(row);
		save();
		if (agent != null && row.loaded != null)
		{
			runFor(row, "Uninstall", () -> unloadRow(row)); // the agent unloads dependents first
		}
		log("Uninstalled " + row.displayName()
			+ (dependents.isEmpty() ? "" : " and " + dependents.stream().map(PluginRow::displayName).collect(Collectors.joining(", "))));
		changed(true);
	}

	void setEnabled(PluginRow row, boolean enabled)
	{
		Set<PluginRow> affected = new LinkedHashSet<>();
		if (enabled)
		{
			collectDeps(row, affected);
		}
		else
		{
			List<PluginRow> dependents = new ArrayList<>();
			collectDependents(row, dependents);
			affected.addAll(dependents);
		}
		affected.add(row);
		for (PluginRow r : affected)
		{
			r.settings().enabled = enabled;
			r.settings().installed |= enabled;
		}
		save();
		if (agent != null)
		{
			runFor(row, enabled ? "Load" : "Unload", () ->
			{
				if (enabled)
				{
					loadRow(row, false);
				}
				else
				{
					unloadRow(row);
				}
			});
		}
		changed(false);
	}

	void setAutoReload(PluginRow row, boolean autoReload)
	{
		row.settings().autoReload = autoReload;
		save();
		try
		{
			if (autoReload)
			{
				watcher.watch(row);
				log("Watching " + row.displayName() + " for changes");
			}
			else
			{
				watcher.unwatch(row);
			}
		}
		catch (IOException e)
		{
			log("Unable to watch " + row.path() + ": " + e.getMessage());
		}
		changed(false);
	}

	/** Rebuild (projects) and reload into the client if it's running and the row is enabled */
	void reload(PluginRow row)
	{
		runFor(row, "Reload", () -> loadRow(row, true));
	}

	void setDisableTelemetry(boolean disable)
	{
		config.disableTelemetry = disable;
		save();
		log("Telemetry " + (disable ? "off" : "on") + " from the next launch");
	}

	void setPullOnLaunch(boolean pull)
	{
		config.pullOnLaunch = pull;
		save();
	}

	/**
	 * Clones a git repo ("owner/repo" or any git URL) and lists the plugins in it.
	 */
	void addGitSource(String input)
	{
		worker.execute(() ->
		{
			String url = Git.normalizeUrl(input);
			Path dir = Paths.GIT_REPOS.resolve(Git.folderName(url));
			try
			{
				Files.createDirectories(Paths.GIT_REPOS);
				if (Files.isDirectory(dir.resolve(".git")))
				{
					log(dir.getFileName() + " is already cloned; adding it");
				}
				else
				{
					log("Cloning " + url);
					Git.cloneRepo(url, dir, this::log);
				}
			}
			catch (Exception e)
			{
				log("Clone failed: " + e.getMessage());
				return;
			}

			ManagerConfig.Source source = addSourceIfNew(ManagerConfig.SourceType.GIT, dir);
			if (source != null)
			{
				source.url = url;
				save();
			}
			rescan(dir);
			refreshRows();
			int count = projects.getOrDefault(dir.toString(), List.of()).size();
			log(count == 0
				? "No RuneLite plugin projects found in " + url
				: "Found " + count + " plugin(s) in " + Sources.label(sourceFor(dir)) + "; see Browse");
		});
	}

	void addLocalSource(Path path)
	{
		worker.execute(() ->
		{
			if (addSourceIfNew(ManagerConfig.SourceType.LOCAL, path) == null)
			{
				log(path + " is already a source");
				return;
			}
			save();
			rescan(path.toAbsolutePath().normalize());
			refreshRows();
			int count = projects.getOrDefault(path.toAbsolutePath().normalize().toString(), List.of()).size();
			log(count == 0 ? "No plugin projects found in " + path : "Found " + count + " plugin(s) in " + path.getFileName());
		});
	}

	void addFolderSource(Path path)
	{
		worker.execute(() ->
		{
			if (addSourceIfNew(ManagerConfig.SourceType.FOLDER, path) != null)
			{
				save();
				refreshRows();
			}
		});
	}

	void removeSource(ManagerConfig.Source source)
	{
		worker.execute(() ->
		{
			synchronized (config)
			{
				config.sources.remove(source);
			}
			projects.remove(source.path);
			save();
			refreshRows();
			log("Removed source " + Sources.label(source) + " (files left on disk)");
		});
	}

	/** Find folders with plugin projects under root and add each as a local source */
	void scanForProjects(Path root)
	{
		worker.execute(() ->
		{
			List<Path> found = new ArrayList<>();
			scanRoots(root, 0, found);
			int added = 0;
			for (Path dir : found)
			{
				if (addSourceIfNew(ManagerConfig.SourceType.LOCAL, dir) != null)
				{
					rescan(dir);
					log("Found plugin project(s) in " + dir);
					added++;
				}
			}
			log("Scan of " + root + ": " + found.size() + " folder(s) with plugins, " + added + " new");
			if (added > 0)
			{
				save();
				refreshRows();
			}
		});
	}

	/** git pull one source (or refresh the Plugin Hub), then rebuild and reload what changed */
	void pull(ManagerConfig.Source source)
	{
		worker.execute(() -> pullNow(source));
	}

	boolean hasHubSource()
	{
		return sources().stream().anyMatch(s -> s.type == ManagerConfig.SourceType.HUB);
	}

	void addHubSource()
	{
		worker.execute(() ->
		{
			if (addSourceIfNew(ManagerConfig.SourceType.HUB, PluginHub.DIR) == null)
			{
				return;
			}
			save();
			loadHub(true);
			refreshRows();
		});
	}

	/** Fetch a Plugin Hub icon in the background, if it isn't cached yet */
	void ensureIcon(PluginRow row)
	{
		PluginInfo info = row.info;
		if (row.kind() != PluginRow.Kind.HUB || info == null || info.icon == null || Files.exists(info.icon)
			|| !iconsRequested.add(info.icon.toString()))
		{
			return;
		}
		PluginHub.Entry entry = hub.entries().get(row.path().getFileName().toString());
		if (entry == null)
		{
			return;
		}
		iconLoader.execute(() ->
		{
			try
			{
				hub.icon(entry);
				changed(false);
			}
			catch (Exception e)
			{
				iconsRequested.remove(info.icon.toString()); // try again later
			}
		});
	}

	/**
	 * Loads the Plugin Hub manifest (downloading it when online or not cached).
	 * Returns the internal names of plugins whose jar changed.
	 */
	private List<String> loadHub(boolean online)
	{
		try
		{
			List<String> updated = hub.load(online);
			hubInfos = hub.entries().values().stream().map(PluginInfo::fromHub).collect(Collectors.toList());
			if (online)
			{
				log("Plugin Hub: " + hubInfos.size() + " plugins (RuneLite " + hub.version() + ")"
					+ (updated.isEmpty() ? "" : ", " + updated.size() + " updated"));
			}
			return updated;
		}
		catch (Exception e)
		{
			log("Plugin Hub unavailable: " + e.getMessage());
			return List.of();
		}
	}

	void pullAll()
	{
		worker.execute(this::pullAllNow);
	}

	/** Re-read every source from disk (picks up edits to plugin names, new projects, etc.) */
	void rescanSources()
	{
		worker.execute(() ->
		{
			rescanAll();
			refreshRows();
			changed(true); // details may have changed even if no rows were added or removed
			log("Rescanned sources");
		});
	}

	void shutdown()
	{
		save();
		poller.shutdownNow();
		worker.shutdownNow();
		AgentClient a = agent;
		if (a != null)
		{
			a.close();
		}
		try
		{
			watcher.close();
		}
		catch (IOException ignored)
		{
		}
	}

	// ---- background ----

	private void poll()
	{
		try
		{
			refreshRows(); // picks up jars added to folder sources

			AgentClient a = agent;
			if (a == null)
			{
				a = AgentClient.tryConnect();
				if (a == null)
				{
					return;
				}
				agent = a;
				log("Connected to RuneLite (pid " + a.pid() + ")");
				for (PluginRow row : rows)
				{
					row.error = null;
				}
				changed(false);
				worker.execute(this::syncAll);
			}
			applyList(a.list());
		}
		catch (IOException e)
		{
			disconnect();
		}
		catch (Exception e)
		{
			log("Status refresh failed: " + e.getMessage());
		}
	}

	private void disconnect()
	{
		AgentClient a = agent;
		if (a == null)
		{
			return;
		}
		a.close();
		agent = null;
		lastListSignature = "";
		for (PluginRow row : rows)
		{
			row.loaded = null;
		}
		log("RuneLite closed");
		changed(false);
	}

	/** After connecting: load enabled rows (dependencies first), unload disabled ones */
	private void syncAll()
	{
		AgentClient a = agent;
		if (a == null)
		{
			return;
		}
		try
		{
			applyList(a.list());
		}
		catch (Exception e)
		{
			log("Sync failed: " + e.getMessage());
			return;
		}

		for (PluginRow row : rows)
		{
			if (row.enabled() && row.loaded == null && row.error == null)
			{
				runNow(row, "Load", () -> loadRow(row, false));
			}
			else if (!row.enabled() && row.loaded != null)
			{
				runNow(row, "Unload", () -> unloadRow(row));
			}
		}
	}

	private void onFilesChanged(String rowKey)
	{
		for (PluginRow row : rows)
		{
			if (row.key().equals(rowKey) && row.settings().autoReload && row.enabled())
			{
				log("Change detected in " + row.displayName());
				reload(row);
			}
		}
	}

	private void loadRow(PluginRow row, boolean rebuild) throws Exception
	{
		loadRow(row, rebuild, new HashSet<>());
	}

	/**
	 * Loads a row's dependencies, builds it if needed, copies the jar to staging/{id}.jar and
	 * loads it. The agent replaces an already-loaded jar with the same id (and reloads what
	 * depends on it), so this doubles as reload.
	 */
	private void loadRow(PluginRow row, boolean rebuild, Set<PluginRow> visiting) throws Exception
	{
		if (!visiting.add(row))
		{
			throw new IllegalStateException("Dependency cycle at " + row.displayName());
		}
		for (PluginRow dep : row.deps)
		{
			if (row.enabled() && !dep.enabled())
			{
				log("Turning on " + dep.displayName() + " (needed by " + row.displayName() + ")");
				dep.settings().installed = true;
				dep.settings().enabled = true;
				save();
				changed(true);
			}
			if (dep.loaded == null)
			{
				try
				{
					loadRow(dep, false, visiting);
				}
				catch (Exception e)
				{
					dep.error = e.getMessage();
					throw new IllegalStateException("needs " + dep.displayName() + ", which failed: " + e.getMessage());
				}
			}
		}

		Path jar;
		if (row.kind() == PluginRow.Kind.HUB)
		{
			PluginHub.Entry entry = hub.entries().get(row.path().getFileName().toString());
			if (entry == null)
			{
				throw new IllegalStateException("no longer on the Plugin Hub");
			}
			row.building = true;
			changed(false);
			try
			{
				jar = hub.jar(entry);
			}
			finally
			{
				row.building = false;
			}
		}
		else if (row.kind() == PluginRow.Kind.PROJECT)
		{
			Optional<Path> built = rebuild || row.needsBuild ? Optional.empty() : GradleBuilder.findJar(row.path());
			jar = built.isPresent() ? built.get() : build(row);
			row.needsBuild = false;
		}
		else
		{
			jar = row.path();
		}

		Files.createDirectories(Paths.STAGING);
		Path staged = Paths.STAGING.resolve(row.id() + ".jar");
		Files.copy(jar, staged, StandardCopyOption.REPLACE_EXISTING);

		AgentClient a = agent;
		if (a != null && row.enabled())
		{
			a.load(staged, row.deps.stream().map(PluginRow::id).collect(Collectors.toList()));
			row.error = null;
			applyList(a.list());
			log("Loaded " + row.displayName() + (row.pluginNames().isEmpty() ? "" : " (" + row.pluginNames() + ")"));
		}
	}

	private Path build(PluginRow row) throws Exception
	{
		String task = row.settings().buildTask == null ? "jar" : row.settings().buildTask;
		row.building = true;
		changed(false);
		log("Building " + row.displayName() + " (gradle " + task + ")");
		try
		{
			Path jar = GradleBuilder.build(row.path(), task, row.settings().javaHome, this::log);
			log("Built " + jar.getFileName());
			return jar;
		}
		finally
		{
			row.building = false;
			changed(false);
		}
	}

	private void unloadRow(PluginRow row) throws Exception
	{
		AgentClient a = agent;
		if (a == null || row.loaded == null)
		{
			return;
		}
		a.unload(row.id());
		applyList(a.list());
		log("Unloaded " + row.displayName());
	}

	private void applyList(List<AgentClient.LoadedJar> jars)
	{
		Map<String, AgentClient.LoadedJar> byId = new HashMap<>();
		StringBuilder signature = new StringBuilder();
		for (AgentClient.LoadedJar jar : jars)
		{
			byId.putIfAbsent(jar.id(), jar);
			signature.append(jar.id()).append(':');
			for (AgentClient.LoadedPlugin p : jar.plugins())
			{
				signature.append(p.className()).append(p.active()).append(',');
			}
			signature.append(';');
		}
		for (PluginRow row : rows)
		{
			AgentClient.LoadedJar jar = byId.get(row.id());
			row.loaded = jar == null ? null : jar.plugins();
		}
		// polling happens every 2s; only repaint when something changed
		if (!signature.toString().equals(lastListSignature))
		{
			lastListSignature = signature.toString();
			changed(false);
		}
	}

	private void rescanAll()
	{
		for (ManagerConfig.Source source : sources())
		{
			if (source.type == ManagerConfig.SourceType.HUB)
			{
				loadHub(false); // cached manifest; Pull refreshes it
			}
			else if (source.type != ManagerConfig.SourceType.FOLDER)
			{
				rescan(Path.of(source.path));
			}
		}
	}

	private void rescan(Path sourceRoot)
	{
		projects.put(sourceRoot.toString(), Files.isDirectory(sourceRoot) ? ProjectScanner.scan(sourceRoot) : List.of());
	}

	/**
	 * Rebuilds the row list from the configured sources, keeping existing row objects
	 * (and their state) for paths that are still there.
	 */
	private void refreshRows()
	{
		boolean structural;
		synchronized (config)
		{
			Map<String, PluginRow> existing = new HashMap<>();
			for (PluginRow row : rows)
			{
				existing.put(row.key(), row);
			}

			List<PluginRow> next = new ArrayList<>();
			Set<String> usedIds = new HashSet<>();
			List<PluginRow> created = new ArrayList<>();
			for (ManagerConfig.Source source : config.sources)
			{
				Path root = Path.of(source.path);
				if (source.type == ManagerConfig.SourceType.FOLDER)
				{
					for (Path jar : listJars(root))
					{
						next.add(row(existing, created, usedIds, PluginRow.Kind.JAR, jar, source, null));
					}
				}
				else if (source.type == ManagerConfig.SourceType.HUB)
				{
					for (PluginInfo info : hubInfos)
					{
						next.add(row(existing, created, usedIds, PluginRow.Kind.HUB, info.dir, source, info));
					}
				}
				else
				{
					for (PluginInfo info : projects.getOrDefault(root.toString(), List.of()))
					{
						next.add(row(existing, created, usedIds, PluginRow.Kind.PROJECT, info.dir, source, info));
					}
				}
			}
			resolveDeps(next);

			Set<String> nextKeys = next.stream().map(PluginRow::key).collect(Collectors.toSet());
			List<PluginRow> gone = rows.stream().filter(r -> !nextKeys.contains(r.key())).collect(Collectors.toList());
			structural = !created.isEmpty() || !gone.isEmpty() || next.size() != rows.size();
			if (!structural)
			{
				return;
			}

			for (PluginRow row : gone)
			{
				watcher.unwatch(row);
				if (row.loaded != null)
				{
					runFor(row, "Unload", () -> unloadRow(row));
				}
			}
			for (PluginRow row : created)
			{
				if (row.settings().autoReload)
				{
					try
					{
						watcher.watch(row);
					}
					catch (IOException e)
					{
						log("Unable to watch " + row.path() + ": " + e.getMessage());
					}
				}
			}
			rows = List.copyOf(next);

			if (agent != null)
			{
				for (PluginRow row : created)
				{
					if (row.enabled())
					{
						runFor(row, "Load", () -> loadRow(row, false));
					}
				}
			}
		}
		changed(true);
	}

	private PluginRow row(Map<String, PluginRow> existing, List<PluginRow> created, Set<String> usedIds,
		PluginRow.Kind kind, Path path, ManagerConfig.Source source, PluginInfo info)
	{
		PluginRow row = existing.get(path.toString());
		if (row == null || row.source() != source)
		{
			row = new PluginRow(kind, path, source, config.settingsFor(path.toString()), idFor(kind, path, source, usedIds));
			created.add(row);
		}
		usedIds.add(row.id());
		if (info != null)
		{
			row.info = info;
		}
		return row;
	}

	/** Links each project row to the rows it needs, from Gradle project deps and @PluginDependency */
	private static void resolveDeps(List<PluginRow> rows)
	{
		Map<Path, PluginRow> byDir = new HashMap<>();
		Map<String, PluginRow> byClass = new HashMap<>();
		for (PluginRow row : rows)
		{
			if (row.info != null)
			{
				byDir.put(row.path().normalize(), row);
				for (String cls : row.info.pluginClasses)
				{
					byClass.putIfAbsent(cls.substring(cls.lastIndexOf('.') + 1), row);
				}
			}
		}
		for (PluginRow row : rows)
		{
			PluginInfo info = row.info;
			if (info == null)
			{
				continue;
			}
			Set<PluginRow> deps = new LinkedHashSet<>();
			info.projectDeps.stream().map(Path::normalize).map(byDir::get).filter(Objects::nonNull).forEach(deps::add);
			info.pluginDependencies.stream().map(byClass::get).filter(Objects::nonNull).forEach(deps::add);
			deps.remove(row);
			row.deps = List.copyOf(deps);
		}
	}

	private void collectDeps(PluginRow row, Set<PluginRow> out)
	{
		for (PluginRow dep : row.deps)
		{
			if (!out.contains(dep))
			{
				collectDeps(dep, out);
				out.add(dep);
			}
		}
	}

	private void collectDependents(PluginRow row, List<PluginRow> out)
	{
		for (PluginRow r : rows)
		{
			if (r.deps.contains(row) && !out.contains(r))
			{
				out.add(r);
				collectDependents(r, out);
			}
		}
	}

	/**
	 * Stable id, so a restarted manager matches what the running agent already has loaded.
	 * Projects: source folder name, plus the subproject folder for multi-plugin repos.
	 */
	private static String idFor(PluginRow.Kind kind, Path path, ManagerConfig.Source source, Set<String> used)
	{
		String name;
		if (kind == PluginRow.Kind.HUB)
		{
			name = "hub_" + path.getFileName();
		}
		else if (kind == PluginRow.Kind.JAR)
		{
			name = path.getFileName().toString().replaceAll("\\.jar$", "");
		}
		else
		{
			Path root = Path.of(source.path);
			name = root.getFileName().toString();
			if (!root.equals(path))
			{
				name += "_" + root.relativize(path).toString().replace('\\', '_').replace('/', '_');
			}
		}
		String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
		String id = base;
		for (int i = 2; used.contains(id); i++)
		{
			id = base + "-" + i;
		}
		return id;
	}

	private static List<Path> listJars(Path folder)
	{
		if (!Files.isDirectory(folder))
		{
			return List.of();
		}
		try (Stream<Path> files = Files.list(folder))
		{
			return files.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().collect(Collectors.toList());
		}
		catch (IOException e)
		{
			return List.of();
		}
	}

	private void pullAllNow()
	{
		for (ManagerConfig.Source source : sources())
		{
			if (source.type == ManagerConfig.SourceType.GIT || source.type == ManagerConfig.SourceType.HUB)
			{
				pullNow(source);
			}
		}
	}

	private void pullNow(ManagerConfig.Source source)
	{
		if (source.type == ManagerConfig.SourceType.HUB)
		{
			List<String> updated = loadHub(true);
			refreshRows();
			for (PluginRow row : rows)
			{
				if (row.kind() == PluginRow.Kind.HUB && updated.contains(row.path().getFileName().toString())
					&& agent != null && row.enabled())
				{
					runNow(row, "Update", () -> loadRow(row, true));
				}
			}
			changed(true);
			return;
		}

		Path dir = Path.of(source.path);
		boolean changed;
		try
		{
			log("Pulling " + Sources.label(source));
			changed = Git.pull(dir, this::log);
		}
		catch (Exception e)
		{
			log("Pull failed for " + Sources.label(source) + ": " + e.getMessage());
			return;
		}

		if (!changed)
		{
			log(Sources.label(source) + " is up to date");
			return;
		}

		rescan(dir); // a pull can add or remove plugin projects, or change their details
		refreshRows();
		for (PluginRow row : rows)
		{
			if (row.source() == source)
			{
				row.needsBuild = true;
				if (agent != null && row.enabled())
				{
					runNow(row, "Rebuild", () -> loadRow(row, true));
				}
			}
		}
		changed(false);
	}

	private ManagerConfig.Source sourceFor(Path dir)
	{
		synchronized (config)
		{
			return config.sources.stream().filter(s -> s.path.equalsIgnoreCase(dir.toString())).findFirst()
				.orElse(new ManagerConfig.Source(ManagerConfig.SourceType.LOCAL, dir.toString()));
		}
	}

	/** Adds a source unless one with the same path exists; returns it, or null if it existed */
	private ManagerConfig.Source addSourceIfNew(ManagerConfig.SourceType type, Path path)
	{
		String p = path.toAbsolutePath().normalize().toString();
		synchronized (config)
		{
			for (ManagerConfig.Source s : config.sources)
			{
				if (s.path.equalsIgnoreCase(p))
				{
					return null;
				}
			}
			ManagerConfig.Source source = new ManagerConfig.Source(type, p);
			config.sources.add(source);
			return source;
		}
	}

	/** Top-most folders under dir that contain plugin projects */
	private void scanRoots(Path dir, int depth, List<Path> found)
	{
		boolean isBuild = Files.isRegularFile(dir.resolve("settings.gradle")) || Files.isRegularFile(dir.resolve("settings.gradle.kts"))
			|| Files.isRegularFile(dir.resolve("build.gradle")) || Files.isRegularFile(dir.resolve("build.gradle.kts"));
		if (isBuild && !ProjectScanner.scan(dir).isEmpty())
		{
			found.add(dir.toAbsolutePath().normalize());
			return; // its subprojects are part of it
		}
		if (depth >= SCAN_DEPTH)
		{
			return;
		}
		try (Stream<Path> children = Files.list(dir))
		{
			for (Path child : children.filter(Files::isDirectory).collect(Collectors.toList()))
			{
				String name = child.getFileName().toString();
				if (!name.startsWith(".") && !SCAN_SKIP.contains(name))
				{
					scanRoots(child, depth + 1, found);
				}
			}
		}
		catch (IOException ignored)
		{
		}
	}

	/** Queue work for a row on the worker; failures are shown on the row */
	private void runFor(PluginRow row, String what, Task task)
	{
		worker.execute(() -> runNow(row, what, task));
	}

	private void runNow(PluginRow row, String what, Task task)
	{
		try
		{
			row.error = null;
			task.run();
		}
		catch (Exception e)
		{
			String msg = e.getMessage() != null ? e.getMessage() : e.toString();
			row.error = msg;
			log(what + " " + row.displayName() + " failed: " + msg);
		}
		finally
		{
			changed(false);
		}
	}

	private void save()
	{
		try
		{
			config.save();
		}
		catch (IOException e)
		{
			log("Unable to save settings: " + e.getMessage());
		}
	}

	private void changed(boolean structural)
	{
		Listener l = listener;
		if (l != null)
		{
			SwingUtilities.invokeLater(() -> l.rowsChanged(structural));
		}
	}

	private void log(String line)
	{
		synchronized (Paths.LOG)
		{
			try
			{
				Files.writeString(Paths.LOG, LocalDateTime.now().withNano(0) + "  " + line + System.lineSeparator(),
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			}
			catch (IOException ignored)
			{
			}
		}
		if (!line.startsWith("  "))
		{
			lastLog = line;
		}
		Listener l = listener;
		if (l != null)
		{
			SwingUtilities.invokeLater(() -> l.log(line));
		}
	}

	private static Thread daemon(Runnable r, String name)
	{
		Thread t = new Thread(r, "rl-sideloader-" + name);
		t.setDaemon(true);
		return t;
	}
}
