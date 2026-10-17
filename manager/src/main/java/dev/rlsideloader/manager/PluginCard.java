package dev.rlsideloader.manager;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;

/**
 * One plugin, shown Plugin Hub style: icon, name, author and source, description, tags.
 * In Browse it offers Install/Remove; under Installed it has an on/off switch and actions.
 */
final class PluginCard extends Widgets.Card
{
	enum Mode
	{
		BROWSE,
		INSTALLED,
	}

	private static final int ICON_SIZE = 44;
	private static final int MAX_DESCRIPTION = 260;

	private final PluginRow row;
	private final Mode mode;
	private final ManagerFrame frame;

	private final JLabel icon = new JLabel();
	private final JLabel title = new JLabel();
	private final JLabel subtitle = new JLabel();
	private final JTextArea description = Widgets.wrappingText(Theme.font(Font.PLAIN, 12.5f), new Color(0xC8C8C8));
	private final JPanel chips = Widgets.row(6);
	private final JPanel titleChips = Widgets.row(6);
	private final JTextArea error = Widgets.wrappingText(Theme.font(Font.PLAIN, 12f), Theme.RED);
	private final JPanel actions = new JPanel(new GridBagLayout());
	private final Widgets.ToggleSwitch toggle = new Widgets.ToggleSwitch();
	private final JButton installButton = Widgets.accentButton("Install");
	private final JButton removeButton = Widgets.subtleButton("Remove");
	private final JButton reloadButton = Widgets.subtleButton("Reload");
	private final JButton moreButton = Widgets.textButton("•••");
	private String renderedSignature = "";

	PluginCard(PluginRow row, Mode mode, ManagerFrame frame)
	{
		this.row = row;
		this.mode = mode;
		this.frame = frame;

		icon.setVerticalAlignment(JLabel.TOP);
		add(icon, BorderLayout.WEST);

		title.setFont(Theme.font(Font.BOLD, 14.5f));
		title.setForeground(Theme.TEXT);
		subtitle.setFont(Theme.font(Font.PLAIN, 11.5f));
		subtitle.setForeground(Theme.TEXT_DIM);

		JPanel titleRow = new JPanel(new BorderLayout(8, 0));
		titleRow.setOpaque(false);
		titleRow.add(title, BorderLayout.WEST);
		titleRow.add(titleChips, BorderLayout.CENTER);

		JPanel content = new JPanel();
		content.setOpaque(false);
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		for (Component c : new Component[]{titleRow, subtitle, description, chips, error})
		{
			((javax.swing.JComponent) c).setAlignmentX(0f);
		}
		content.add(titleRow);
		content.add(Box.createVerticalStrut(2));
		content.add(subtitle);
		content.add(Box.createVerticalStrut(6));
		content.add(description);
		content.add(Box.createVerticalStrut(6));
		content.add(chips);
		content.add(error);
		error.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
		add(content, BorderLayout.CENTER);

		actions.setOpaque(false);
		add(actions, BorderLayout.EAST);

		installButton.addActionListener(e -> frame.install(row));
		removeButton.addActionListener(e -> frame.uninstall(row));
		reloadButton.addActionListener(e -> frame.controller().reload(row));
		reloadButton.setToolTipText("Rebuild and reload now");
		toggle.onToggle(on -> frame.setEnabled(row, on));
		moreButton.addActionListener(e -> showMenu());

		refresh();
	}

	PluginRow row()
	{
		return row;
	}

	/** Re-reads the row; cheap when nothing changed */
	void refresh()
	{
		boolean connected = frame.controller().isConnected();
		frame.controller().ensureIcon(row);
		boolean hasIcon = row.info != null && row.info.icon != null && java.nio.file.Files.exists(row.info.icon);
		String signature = row.displayName() + row.subtitle() + row.description() + row.tags() + row.installed()
			+ row.enabled() + row.settings().autoReload + row.state() + row.status(connected) + row.error + row.depNames()
			+ row.pluginNames() + hasIcon;
		if (signature.equals(renderedSignature))
		{
			return;
		}
		renderedSignature = signature;

		icon.setIcon(Widgets.pluginIcon(row.displayName(), row.info == null ? null : row.info.icon, ICON_SIZE));
		title.setText(row.displayName());
		subtitle.setText(row.subtitle());

		String desc = row.description();
		if (desc.isEmpty())
		{
			desc = row.kind() == PluginRow.Kind.JAR ? "Plugin jar " + row.path().getFileName() : "";
		}
		if (mode == Mode.BROWSE && desc.length() > MAX_DESCRIPTION)
		{
			desc = desc.substring(0, MAX_DESCRIPTION).trim() + "…";
		}
		description.setText(desc);
		description.setVisible(!desc.isEmpty());

		titleChips.removeAll();
		if (mode == Mode.INSTALLED)
		{
			titleChips.add(statusChip(connected));
			if (row.settings().autoReload)
			{
				titleChips.add(Widgets.chip("Auto-reload", Theme.CHIP, Theme.TEXT_DIM));
			}
		}
		else if (row.installed())
		{
			titleChips.add(Widgets.chip("Installed", new Color(0x23361F), Theme.GREEN));
		}

		chips.removeAll();
		List<String> tags = row.tags();
		for (int i = 0; i < Math.min(tags.size(), 6); i++)
		{
			chips.add(Widgets.chip(tags.get(i), Theme.CHIP, Theme.TEXT_DIM));
		}
		if (row.info != null && row.info.kotlin)
		{
			chips.add(Widgets.chip("Kotlin", Theme.CHIP, Theme.TEXT_DIM));
		}
		if (row.info != null && row.info.warning != null && !row.info.warning.isBlank())
		{
			String w = row.info.warning.length() > 90 ? row.info.warning.substring(0, 90).trim() + "…" : row.info.warning;
			JLabel warning = new JLabel("⚠ " + w);
			warning.setToolTipText(row.info.warning);
			warning.setFont(Theme.font(Font.PLAIN, 11.5f));
			warning.setForeground(Theme.AMBER);
			chips.add(warning);
		}
		if (!row.deps.isEmpty())
		{
			JLabel requires = new JLabel("Requires " + row.depNames());
			requires.setFont(Theme.font(Font.PLAIN, 11.5f));
			requires.setForeground(Theme.AMBER);
			chips.add(requires);
		}
		chips.setVisible(chips.getComponentCount() > 0);

		String err = mode == Mode.INSTALLED ? row.error : null;
		error.setText(err == null ? "" : err);
		error.setVisible(err != null);
		setAccentEdge(err != null ? Theme.RED : null);

		layoutActions();
		revalidate();
		repaint();
	}

	private JLabel statusChip(boolean connected)
	{
		String text = row.status(connected);
		switch (row.state())
		{
			case RUNNING:
				return Widgets.chip("● " + text, new Color(0x1F3324), Theme.GREEN);
			case BUSY:
				return Widgets.chip(text, new Color(0x3A3020), Theme.AMBER);
			case ERROR:
				return Widgets.chip(text, new Color(0x3D2222), Theme.RED);
			case WARN:
				return Widgets.chip(text, new Color(0x3A3020), Theme.AMBER);
			default:
				return Widgets.chip(text, Theme.CHIP, Theme.TEXT_DIM);
		}
	}

	private void layoutActions()
	{
		actions.removeAll();
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.anchor = GridBagConstraints.NORTHEAST;
		c.insets = new Insets(0, 0, 6, 0);

		if (mode == Mode.BROWSE)
		{
			actions.add(row.installed() ? removeButton : installButton, c);
		}
		else
		{
			toggle.setOn(row.enabled());
			toggle.setToolTipText(row.enabled() ? "Turn off (unload from RuneLite)" : "Turn on (load into RuneLite)");
			actions.add(toggle, c);
			c.gridy = 1;
			JPanel buttons = Widgets.row(4, reloadButton, moreButton);
			actions.add(buttons, c);
			reloadButton.setEnabled(!row.building);
		}
		c.gridy = 2;
		c.weighty = 1;
		actions.add(Box.createGlue(), c);
	}

	private void showMenu()
	{
		JPopupMenu menu = new JPopupMenu();
		boolean hub = row.kind() == PluginRow.Kind.HUB;
		JMenuItem reload = new JMenuItem(row.kind() == PluginRow.Kind.PROJECT ? "Rebuild and reload" : "Reload");
		reload.addActionListener(e -> frame.controller().reload(row));
		menu.add(reload);

		if (!hub)
		{
			JCheckBoxMenuItem auto = new JCheckBoxMenuItem("Auto-reload when files change", row.settings().autoReload);
			auto.addActionListener(e -> frame.controller().setAutoReload(row, auto.isSelected()));
			menu.add(auto);
			menu.addSeparator();

			JMenuItem open = new JMenuItem("Open folder");
			open.addActionListener(e -> frame.openFolder(row.kind() == PluginRow.Kind.PROJECT ? row.path() : row.path().getParent()));
			menu.add(open);
		}
		else
		{
			menu.addSeparator();
		}

		String web = Sources.webUrl(row.source(), row.path());
		if (web != null)
		{
			JMenuItem github = new JMenuItem(hub ? "View on Plugin Hub" : "View on GitHub");
			github.addActionListener(e -> frame.openUrl(web));
			menu.add(github);
		}
		menu.addSeparator();

		JMenuItem uninstall = new JMenuItem("Uninstall");
		uninstall.setForeground(Theme.RED);
		uninstall.addActionListener(e -> frame.uninstall(row));
		menu.add(uninstall);

		menu.show(moreButton, 0, moreButton.getHeight());
	}
}
