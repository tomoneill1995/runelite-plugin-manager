package dev.rlsideloader.testplugin;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import javax.inject.Inject;
import javax.swing.JLabel;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.PluginPanel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Throwaway plugin for testing the sideloader. Bump VERSION, rebuild and reload
 * to see hot reload working in the sidebar.
 */
@PluginDescriptor(
	name = "Hello Sideload",
	description = "Test plugin for rl-sideloader"
)
public class HelloSideloadPlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(HelloSideloadPlugin.class);
	private static final String VERSION = "v3";

	@Inject
	private ClientToolbar clientToolbar;

	private NavigationButton button;

	@Override
	protected void startUp()
	{
		String loadedAt = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));

		PluginPanel panel = new PluginPanel()
		{
		};
		panel.setLayout(new BorderLayout());
		panel.add(new JLabel("Hello Sideload " + VERSION + ", loaded " + loadedAt), BorderLayout.NORTH);

		button = NavigationButton.builder()
			.tooltip("Hello Sideload " + VERSION)
			.icon(icon())
			.priority(1)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(button);

		log.info("Hello Sideload {} started", VERSION);
	}

	@Override
	protected void shutDown()
	{
		clientToolbar.removeNavigation(button);
		log.info("Hello Sideload {} stopped", VERSION);
	}

	private static BufferedImage icon()
	{
		BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setColor(new Color(0x2ecc71));
		g.fillOval(1, 1, 14, 14);
		g.dispose();
		return img;
	}
}
