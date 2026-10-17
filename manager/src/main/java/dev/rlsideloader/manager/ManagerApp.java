package dev.rlsideloader.manager;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

public final class ManagerApp
{
	private ManagerApp()
	{
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
