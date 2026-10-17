package dev.rlsideloader.manager;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;

/**
 * Main window: Browse (every plugin in every source, Plugin Hub style), Installed, Sources, Log.
 */
final class ManagerFrame extends JFrame implements Controller.Listener
{
	private static final int MAX_LOG_LINES = 3000;
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
	private static final String ALL_SOURCES = "All sources";
	private static final int PAGE_SIZE = 50;
	private static final int TAB_INSTALLED = 0;
	private static final int TAB_BROWSE = 1;

	private final Controller controller;

	private final Widgets.StatusLabel agentStatus = new Widgets.StatusLabel();
	private final JButton launchButton = Widgets.accentButton("Launch RuneLite");
	private final JTabbedPane tabs = new JTabbedPane();

	private final JTextField search = new JTextField();
	private final JComboBox<String> sourceFilter = new JComboBox<>();
	private final Widgets.ListPanel browseList = new Widgets.ListPanel();
	private final Map<String, PluginCard> browseCards = new HashMap<>();
	private final JLabel browseCount = new JLabel();

	private final Widgets.ListPanel installedList = new Widgets.ListPanel();
	private final Map<String, PluginCard> installedCards = new HashMap<>();

	private final Widgets.ListPanel sourcesList = new Widgets.ListPanel();
	private final JTextArea logArea = new JTextArea();
	private final JLabel statusBar = new JLabel(" ");
	private boolean updatingFilter;
	private int browseLimit = PAGE_SIZE;
	private String lastFilterKey = "";
	private final JButton addHub = Widgets.subtleButton("+ Plugin Hub");

	ManagerFrame(Controller controller)
	{
		super("RuneLite Plugin Manager");
		this.controller = controller;
		setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		setMinimumSize(new Dimension(760, 520));
		setSize(1080, 760);
		setLocationRelativeTo(null);
		getContentPane().setBackground(Theme.BACKGROUND);

		add(buildHeader(), BorderLayout.NORTH);

		tabs.putClientProperty("JTabbedPane.tabAreaInsets", new java.awt.Insets(0, 12, 0, 12));
		tabs.putClientProperty("JTabbedPane.tabInsets", new java.awt.Insets(0, 16, 0, 16));
		tabs.addTab("Installed", scroll(installedList));
		tabs.addTab("Browse", buildBrowse());
		tabs.addTab("Sources", buildSources());
		tabs.addTab("Log", buildLog());
		add(tabs, BorderLayout.CENTER);

		statusBar.setFont(Theme.font(Font.PLAIN, 11.5f));
		statusBar.setForeground(Theme.TEXT_DIM);
		statusBar.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER),
			BorderFactory.createEmptyBorder(6, 16, 6, 16)));
		add(statusBar, BorderLayout.SOUTH);

		rowsChanged(true);
	}

	Controller controller()
	{
		return controller;
	}

	// ---- layout ----

	private JComponent buildHeader()
	{
		JLabel title = new JLabel("Plugin Manager");
		title.setFont(Theme.font(Font.BOLD, 18f));
		title.setForeground(Theme.TEXT);

		JPanel left = Widgets.row(0, title, (JComponent) Box.createHorizontalStrut(16), agentStatus);

		JButton pullAll = Widgets.subtleButton("Pull all");
		pullAll.setToolTipText("git pull every GitHub source and rebuild what changed");
		pullAll.addActionListener(e -> controller.pullAll());

		JButton settings = Widgets.subtleButton("Settings ▾");
		settings.addActionListener(e -> showSettings(settings));

		launchButton.addActionListener(e -> controller.launch());

		JPanel right = Widgets.row(8, pullAll, settings, launchButton);

		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(Theme.BACKGROUND);
		header.setBorder(BorderFactory.createEmptyBorder(14, 20, 6, 20));
		header.add(left, BorderLayout.WEST);
		header.add(right, BorderLayout.EAST);
		return header;
	}

	private JComponent buildBrowse()
	{
		search.putClientProperty("JTextField.placeholderText", "Search plugins, tags, authors, repos…");
		search.putClientProperty("JTextField.showClearButton", true);
		search.setPreferredSize(new Dimension(360, 34));
		search.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				applyBrowseFilter();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				applyBrowseFilter();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				applyBrowseFilter();
			}
		});

		sourceFilter.setPreferredSize(new Dimension(220, 34));
		sourceFilter.addActionListener(e ->
		{
			if (!updatingFilter)
			{
				applyBrowseFilter();
			}
		});

		JButton addGit = Widgets.subtleButton("+ Add GitHub repo");
		addGit.addActionListener(e -> addGitSource());

		browseCount.setForeground(Theme.TEXT_DIM);
		browseCount.setFont(Theme.font(Font.PLAIN, 12f));

		JPanel bar = new JPanel(new BorderLayout());
		bar.setBackground(Theme.BACKGROUND);
		bar.setBorder(BorderFactory.createEmptyBorder(12, 16, 0, 16));
		bar.add(Widgets.row(8, search, sourceFilter, browseCount), BorderLayout.WEST);
		bar.add(addGit, BorderLayout.EAST);

		JPanel panel = new JPanel(new BorderLayout());
		panel.add(bar, BorderLayout.NORTH);
		panel.add(scroll(browseList), BorderLayout.CENTER);
		return panel;
	}

	private JComponent buildSources()
	{
		JButton addGit = Widgets.accentButton("+ GitHub repo");
		addGit.addActionListener(e -> addGitSource());
		JButton addLocal = Widgets.subtleButton("+ Local project");
		addLocal.setToolTipText("A folder with one Gradle plugin project or several");
		addLocal.addActionListener(e -> chooseFolder("Plugin project folder").ifPresent(controller::addLocalSource));
		JButton addFolder = Widgets.subtleButton("+ Jar folder");
		addFolder.setToolTipText("A folder of built plugin jars");
		addFolder.addActionListener(e -> chooseFolder("Folder of plugin jars").ifPresent(controller::addFolderSource));
		JButton scan = Widgets.subtleButton("Scan folder…");
		scan.setToolTipText("Find plugin projects under a folder and add them");
		scan.addActionListener(e -> chooseFolder("Folder to scan for plugin projects").ifPresent(controller::scanForProjects));
		addHub.setToolTipText("Browse and install from the official RuneLite Plugin Hub");
		addHub.addActionListener(e -> controller.addHubSource());
		JButton rescan = Widgets.textButton("Rescan");
		rescan.setToolTipText("Re-read every source from disk");
		rescan.addActionListener(e -> controller.rescanSources());

		JPanel bar = new JPanel(new BorderLayout());
		bar.setBackground(Theme.BACKGROUND);
		bar.setBorder(BorderFactory.createEmptyBorder(12, 16, 0, 16));
		bar.add(Widgets.row(8, addGit, addHub, addLocal, addFolder, scan), BorderLayout.WEST);
		bar.add(rescan, BorderLayout.EAST);

		JPanel panel = new JPanel(new BorderLayout());
		panel.add(bar, BorderLayout.NORTH);
		panel.add(scroll(sourcesList), BorderLayout.CENTER);
		return panel;
	}

	private JComponent buildLog()
	{
		logArea.setEditable(false);
		logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		logArea.setBackground(new Color(0x171717));
		logArea.setForeground(new Color(0xCFCFCF));
		logArea.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
		JScrollPane sp = new JScrollPane(logArea);
		sp.setBorder(BorderFactory.createEmptyBorder());
		return sp;
	}

	private static JScrollPane scroll(JComponent content)
	{
		JScrollPane sp = new JScrollPane(content, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		sp.setBorder(BorderFactory.createEmptyBorder());
		sp.getViewport().setBackground(Theme.BACKGROUND);
		sp.getVerticalScrollBar().setUnitIncrement(24);
		return sp;
	}

	private void showSettings(JComponent anchor)
	{
		JPopupMenu menu = new JPopupMenu();
		JCheckBoxMenuItem telemetry = new JCheckBoxMenuItem("Disable RuneLite telemetry", controller.isTelemetryDisabled());
		telemetry.setToolTipText("Starts RuneLite with --disable-telemetry: no usage pings, error or crash reports");
		telemetry.addActionListener(e -> controller.setDisableTelemetry(telemetry.isSelected()));
		menu.add(telemetry);

		JCheckBoxMenuItem pull = new JCheckBoxMenuItem("Pull GitHub sources on launch", controller.isPullOnLaunch());
		pull.addActionListener(e -> controller.setPullOnLaunch(pull.isSelected()));
		menu.add(pull);
		menu.addSeparator();

		JMenuItem data = new JMenuItem("Open data folder");
		data.addActionListener(e -> openFolder(Paths.HOME));
		menu.add(data);
		JMenuItem log = new JMenuItem("Open log file");
		log.addActionListener(e -> openFolder(Paths.LOG));
		menu.add(log);

		menu.show(anchor, 0, anchor.getHeight());
	}

	// ---- updates ----

	@Override
	public void rowsChanged(boolean structural)
	{
		boolean connected = controller.isConnected();
		agentStatus.set(connected ? "RuneLite running  ·  pid " + controller.agentPid() : "RuneLite not running",
			connected ? Theme.GREEN : Theme.TEXT_DIM);
		launchButton.setEnabled(!connected);
		launchButton.setText(connected ? "RuneLite running" : "Launch RuneLite");

		List<PluginRow> rows = controller.rows();
		long installed = rows.stream().filter(PluginRow::installed).count();
		tabs.setTitleAt(TAB_INSTALLED, installed == 0 ? "Installed" : "Installed  " + installed);
		tabs.setTitleAt(TAB_BROWSE, "Browse  " + rows.size());

		if (structural)
		{
			rebuildSourceFilter();
			rebuildSources();
			applyBrowseFilter();
			rebuildInstalled();
		}
		else
		{
			if (installedCards.size() != installed)
			{
				rebuildInstalled();
			}
			browseCards.values().forEach(PluginCard::refresh);
			installedCards.values().forEach(PluginCard::refresh);
		}
		statusBar.setText(controller.lastLog().isEmpty() ? " " : controller.lastLog());
	}

	private void applyBrowseFilter()
	{
		String[] terms = search.getText().toLowerCase(Locale.ROOT).trim().split("\\s+");
		String source = (String) sourceFilter.getSelectedItem();

		List<PluginRow> visible = controller.rows().stream()
			.filter(r -> source == null || ALL_SOURCES.equals(source) || Sources.label(r.source()).equals(source))
			.filter(r -> matches(r, terms))
			.sorted(Comparator.comparing(r -> sortName(r.displayName())))
			.collect(Collectors.toList());

		String filterKey = search.getText() + "|" + source;
		if (!filterKey.equals(lastFilterKey))
		{
			lastFilterKey = filterKey;
			browseLimit = PAGE_SIZE;
		}

		// The Plugin Hub alone is ~2,600 plugins: only build cards for one page at a time
		List<JComponent> cards = new ArrayList<>();
		List<PluginRow> page = visible.subList(0, Math.min(browseLimit, visible.size()));
		for (PluginRow row : page)
		{
			PluginCard card = browseCards.computeIfAbsent(row.key() + "@" + System.identityHashCode(row),
				k -> new PluginCard(row, PluginCard.Mode.BROWSE, this));
			card.refresh();
			cards.add(card);
		}
		browseCards.values().removeIf(c -> !page.contains(c.row()));
		if (visible.size() > page.size())
		{
			JButton more = Widgets.subtleButton("Show more  (" + (visible.size() - page.size()) + " left)");
			more.setAlignmentX(0f);
			more.addActionListener(e ->
			{
				browseLimit += PAGE_SIZE;
				applyBrowseFilter();
			});
			JPanel holder = Widgets.row(0, more);
			holder.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
			cards.add(holder);
		}

		boolean noSources = controller.rows().isEmpty();
		browseList.setItems(cards, noSources
			? Widgets.emptyState("No plugins yet", "Add a GitHub repo of plugins with “+ Add GitHub repo”, or a local project under Sources.")
			: Widgets.emptyState("No matches", "Try a different search."));
		browseCount.setText(visible.size() == controller.rows().size() ? "" : visible.size() + " of " + controller.rows().size());
	}

	private static boolean matches(PluginRow row, String[] terms)
	{
		String haystack = (row.displayName() + " " + row.description() + " " + String.join(" ", row.tags()) + " "
			+ row.subtitle() + " " + row.pluginNames()).toLowerCase(Locale.ROOT);
		for (String term : terms)
		{
			if (!term.isEmpty() && !haystack.contains(term))
			{
				return false;
			}
		}
		return true;
	}

	/** Sort ignoring "[JR]" style prefixes */
	private static String sortName(String name)
	{
		return name.replaceAll("^\\s*\\[[^]]*]\\s*", "").toLowerCase(Locale.ROOT);
	}

	private void rebuildInstalled()
	{
		List<PluginCard> cards = new ArrayList<>();
		List<PluginRow> installed = controller.rows().stream()
			.filter(PluginRow::installed)
			.sorted(Comparator.comparing(r -> sortName(r.displayName())))
			.collect(Collectors.toList());
		for (PluginRow row : installed)
		{
			PluginCard card = installedCards.computeIfAbsent(row.key() + "@" + System.identityHashCode(row),
				k -> new PluginCard(row, PluginCard.Mode.INSTALLED, this));
			card.refresh();
			cards.add(card);
		}
		installedCards.values().removeIf(c -> !installed.contains(c.row()));
		installedList.setItems(cards, Widgets.emptyState("Nothing installed yet", "Find plugins under Browse and press Install."));
	}

	private void rebuildSourceFilter()
	{
		updatingFilter = true;
		Object selected = sourceFilter.getSelectedItem();
		sourceFilter.removeAllItems();
		sourceFilter.addItem(ALL_SOURCES);
		for (ManagerConfig.Source s : controller.sources())
		{
			sourceFilter.addItem(Sources.label(s));
		}
		sourceFilter.setSelectedItem(selected != null ? selected : ALL_SOURCES);
		updatingFilter = false;
	}

	private void rebuildSources()
	{
		addHub.setVisible(!controller.hasHubSource());
		List<JComponent> cards = new ArrayList<>();
		for (ManagerConfig.Source source : controller.sources())
		{
			cards.add(sourceCard(source));
		}
		sourcesList.setItems(cards, Widgets.emptyState("No sources", "Add a GitHub repo, a local plugin project or a folder of jars."));
	}

	private JComponent sourceCard(ManagerConfig.Source source)
	{
		Widgets.Card card = new Widgets.Card();
		String kind;
		switch (source.type)
		{
			case HUB:
				kind = "Official";
				break;
			case GIT:
				kind = "GitHub";
				break;
			case FOLDER:
				kind = "Jar folder";
				break;
			default:
				kind = "Local project";
		}
		String iconName = source.type == ManagerConfig.SourceType.HUB ? "Plugin Hub" : kind.equals("GitHub") ? "Git Hub" : kind;
		card.add(new JLabel(Widgets.pluginIcon(iconName, null, 36)), BorderLayout.WEST);

		JLabel title = new JLabel(Sources.label(source));
		title.setFont(Theme.font(Font.BOLD, 14f));
		long count = controller.pluginCount(source);
		String where = source.type == ManagerConfig.SourceType.HUB ? "prebuilt jars from repo.runelite.net, hash-checked" : source.path;
		JLabel subtitle = new JLabel(kind + "  ·  " + count + (count == 1 ? " plugin" : " plugins") + "  ·  " + where);
		subtitle.setFont(Theme.font(Font.PLAIN, 11.5f));
		subtitle.setForeground(Theme.TEXT_DIM);
		JPanel text = new JPanel(new BorderLayout(0, 3));
		text.setOpaque(false);
		text.add(title, BorderLayout.NORTH);
		text.add(subtitle, BorderLayout.CENTER);
		card.add(text, BorderLayout.CENTER);

		JPanel buttons = Widgets.row(6);
		if (source.type == ManagerConfig.SourceType.GIT || source.type == ManagerConfig.SourceType.HUB)
		{
			JButton pull = Widgets.subtleButton(source.type == ManagerConfig.SourceType.HUB ? "Refresh" : "Pull");
			pull.addActionListener(e -> controller.pull(source));
			buttons.add(pull);
		}
		if (source.type != ManagerConfig.SourceType.HUB)
		{
			JButton open = Widgets.subtleButton("Open");
			open.addActionListener(e -> openFolder(Path.of(source.path)));
			buttons.add(open);
		}
		JButton remove = Widgets.textButton("Remove");
		remove.addActionListener(e ->
		{
			if (confirm("Remove " + Sources.label(source) + "?",
				"Its plugins are unloaded and removed from the list. Nothing is deleted from disk."))
			{
				controller.removeSource(source);
			}
		});
		buttons.add(remove);
		card.add(buttons, BorderLayout.EAST);
		return card;
	}

	// ---- actions with confirmation ----

	void install(PluginRow row)
	{
		List<PluginRow> missing = controller.missingDependencies(row);
		if (!missing.isEmpty() && !confirm("Install " + row.displayName() + "?",
			"It needs these too, which will also be installed:\n\n" + bullets(missing)))
		{
			return;
		}
		controller.install(row);
	}

	void uninstall(PluginRow row)
	{
		List<PluginRow> dependents = controller.installedDependents(row);
		if (!dependents.isEmpty() && !confirm("Uninstall " + row.displayName() + "?",
			"These need it and will be uninstalled too:\n\n" + bullets(dependents)))
		{
			return;
		}
		controller.uninstall(row);
	}

	void setEnabled(PluginRow row, boolean enabled)
	{
		if (!enabled)
		{
			List<PluginRow> dependents = controller.installedDependents(row).stream()
				.filter(PluginRow::enabled).collect(Collectors.toList());
			if (!dependents.isEmpty() && !confirm("Turn off " + row.displayName() + "?",
				"These need it and will be turned off too:\n\n" + bullets(dependents)))
			{
				rowsChanged(false); // put the switch back
				return;
			}
		}
		controller.setEnabled(row, enabled);
	}

	private void addGitSource()
	{
		String input = (String) JOptionPane.showInputDialog(this,
			"GitHub repo (owner/repo) or any git URL.\nRepos with many plugins are fine; each one shows up in Browse.\n"
				+ "Private repos use your normal git sign-in.",
			"Add GitHub repo", JOptionPane.PLAIN_MESSAGE, null, null, "");
		if (input != null && !input.isBlank())
		{
			controller.addGitSource(input);
			search.setText(""); // so the new plugins aren't hidden by an old search
			sourceFilter.setSelectedItem(ALL_SOURCES);
			tabs.setSelectedIndex(TAB_BROWSE);
		}
	}

	private static String bullets(List<PluginRow> rows)
	{
		return rows.stream().map(r -> "•  " + r.displayName()).collect(Collectors.joining("\n"));
	}

	private boolean confirm(String title, String message)
	{
		return JOptionPane.showConfirmDialog(this, message, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
			== JOptionPane.OK_OPTION;
	}

	void openFolder(Path path)
	{
		try
		{
			Desktop.getDesktop().open(path.toFile());
		}
		catch (IOException | IllegalArgumentException e)
		{
			log("Unable to open " + path + ": " + e.getMessage());
		}
	}

	void openUrl(String url)
	{
		try
		{
			Desktop.getDesktop().browse(URI.create(url));
		}
		catch (IOException | IllegalArgumentException e)
		{
			log("Unable to open " + url + ": " + e.getMessage());
		}
	}

	private Optional<Path> chooseFolder(String title)
	{
		JFileChooser chooser = new JFileChooser(new File(System.getProperty("user.home")));
		chooser.setDialogTitle(title);
		chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		return chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION
			? Optional.of(chooser.getSelectedFile().toPath())
			: Optional.empty();
	}

	@Override
	public void log(String line)
	{
		logArea.append(LocalTime.now().format(TIME) + "  " + line + "\n");
		int excess = logArea.getLineCount() - MAX_LOG_LINES;
		if (excess > 0)
		{
			try
			{
				logArea.replaceRange("", 0, logArea.getLineEndOffset(excess - 1));
			}
			catch (BadLocationException ignored)
			{
			}
		}
		logArea.setCaretPosition(logArea.getDocument().getLength());
		if (!line.startsWith("  "))
		{
			statusBar.setText(line);
		}
	}
}
