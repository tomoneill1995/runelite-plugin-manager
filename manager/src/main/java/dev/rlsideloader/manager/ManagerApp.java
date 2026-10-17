package dev.rlsideloader.manager;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.Image;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

public final class ManagerApp
{
	private ManagerApp()
	{
	}

	private static List<Image> icons()
	{
		List<Image> images = new ArrayList<>();
		for (int size : new int[]{16, 32, 48, 256})
		{
			try (InputStream in = ManagerApp.class.getResourceAsStream("/icons/icon-" + size + ".png"))
			{
				if (in != null)
				{
					images.add(ImageIO.read(in));
				}
			}
			catch (IOException ignored)
			{
			}
		}
		return images;
	}

	public static void main(String[] args)
	{
		Theme.install();
		ManagerConfig config = ManagerConfig.load();

		SwingUtilities.invokeLater(() ->
		{
			Controller controller;
			try
			{
				controller = new Controller(config);
			}
			catch (IOException e)
			{
				JOptionPane.showMessageDialog(null, "Unable to start: " + e.getMessage());
				System.exit(1);
				return;
			}

			ManagerFrame frame = new ManagerFrame(controller);
			frame.setIconImages(icons());
			frame.addWindowListener(new WindowAdapter()
			{
				@Override
				public void windowClosed(WindowEvent e)
				{
					// RuneLite keeps running; plugins stay loaded until it closes
					controller.shutdown();
					System.exit(0);
				}
			});
			frame.setVisible(true);
			controller.start(frame);
		});
	}
}
