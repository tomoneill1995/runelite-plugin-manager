package dev.rlsideloader.manager;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;

/**
 * Small custom components for the manager's look.
 */
final class Widgets
{
	private Widgets()
	{
	}

	/** A rounded panel that lightens on hover */
	static class Card extends JPanel
	{
		private boolean hover;
		private Color accentEdge;

		Card()
		{
			super(new BorderLayout(14, 0));
			setOpaque(false);
			setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseEntered(MouseEvent e)
				{
					setHover(true);
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					// children get the events while the pointer is over them
					if (!new Rectangle(getSize()).contains(e.getPoint()))
					{
						setHover(false);
					}
				}
			});
		}

		/** A coloured stripe on the left edge, e.g. for errors; null for none */
		void setAccentEdge(Color color)
		{
			if (color != accentEdge)
			{
				accentEdge = color;
				repaint();
			}
		}

		private void setHover(boolean hover)
		{
			this.hover = hover;
			repaint();
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(hover ? Theme.SURFACE_HOVER : Theme.SURFACE);
			g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
			if (accentEdge != null)
			{
				g2.setColor(accentEdge);
				g2.fillRoundRect(0, 0, 4, getHeight(), 4, 4);
			}
			g2.dispose();
		}
	}

	/** Vertical list container that tracks the viewport width, so text wraps */
	static class ListPanel extends JPanel implements Scrollable
	{
		ListPanel()
		{
			setLayout(new javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS));
			setBorder(BorderFactory.createEmptyBorder(12, 16, 16, 16));
			setBackground(Theme.BACKGROUND);
		}

		void setItems(List<? extends JComponent> items, JComponent emptyState)
		{
			removeAll();
			if (items.isEmpty() && emptyState != null)
			{
				add(emptyState);
			}
			for (int i = 0; i < items.size(); i++)
			{
				if (i > 0)
				{
					add(javax.swing.Box.createVerticalStrut(8));
				}
				add(items.get(i));
			}
			add(javax.swing.Box.createVerticalGlue());
			revalidate();
			repaint();
		}

		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return 24;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return visibleRect.height - 48;
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}

	/** Pill-shaped on/off switch */
	static final class ToggleSwitch extends JComponent
	{
		private boolean on;
		private final List<Consumer<Boolean>> listeners = new ArrayList<>();

		ToggleSwitch()
		{
			setPreferredSize(new Dimension(40, 22));
			setMaximumSize(getPreferredSize());
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					if (isEnabled())
					{
						on = !on;
						repaint();
						listeners.forEach(l -> l.accept(on));
					}
				}
			});
		}

		void onToggle(Consumer<Boolean> listener)
		{
			listeners.add(listener);
		}

		void setOn(boolean on)
		{
			if (this.on != on)
			{
				this.on = on;
				repaint();
			}
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int w = getWidth();
			int h = getHeight();
			g2.setColor(on ? Theme.ACCENT : new Color(0x4A4A4A));
			g2.fillRoundRect(0, 0, w, h, h, h);
			int knob = h - 6;
			g2.setColor(Color.WHITE);
			g2.fillOval(on ? w - knob - 3 : 3, 3, knob, knob);
			g2.dispose();
		}
	}

	/** Small rounded label */
	static JLabel chip(String text, Color background, Color foreground)
	{
		JLabel label = new JLabel(text)
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(getBackground());
				g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
				g2.dispose();
				super.paintComponent(g);
			}
		};
		label.setOpaque(false);
		label.setBackground(background);
		label.setForeground(foreground);
		label.setFont(Theme.font(Font.PLAIN, 11f));
		label.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
		return label;
	}

	/** Status dot + text */
	static final class StatusLabel extends JLabel
	{
		private Color dot = Theme.TEXT_DIM;

		StatusLabel()
		{
			setFont(Theme.font(Font.PLAIN, 12f));
			setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 0));
		}

		void set(String text, Color color)
		{
			setText(text);
			setForeground(color == Theme.TEXT_DIM ? Theme.TEXT_DIM : Theme.TEXT);
			dot = color;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(dot);
			int d = 8;
			g2.fillOval(1, (getHeight() - d) / 2, d, d);
			g2.dispose();
		}
	}

	static JButton accentButton(String text)
	{
		JButton b = new JButton(text);
		b.putClientProperty("FlatLaf.style", "background: #DC8A00; foreground: #FFFFFF; hoverBackground: #F09A10; "
			+ "pressedBackground: #B87300; borderWidth: 0; focusWidth: 0; font: bold");
		b.setMargin(new Insets(6, 16, 6, 16));
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		b.setFocusable(false);
		return b;
	}

	static JButton subtleButton(String text)
	{
		JButton b = new JButton(text);
		b.putClientProperty("FlatLaf.style", "background: #333333; hoverBackground: #3D3D3D; borderWidth: 0; focusWidth: 0");
		b.setMargin(new Insets(5, 12, 5, 12));
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		b.setFocusable(false);
		return b;
	}

	static JButton textButton(String text)
	{
		JButton b = new JButton(text);
		b.putClientProperty("JButton.buttonType", "borderless");
		b.setForeground(Theme.TEXT_DIM);
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		b.setFocusable(false);
		return b;
	}

	/** Non-editable wrapping text that looks like a label */
	static JTextArea wrappingText(Font font, Color color)
	{
		JTextArea area = new JTextArea();
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		area.setEditable(false);
		area.setFocusable(false);
		area.setOpaque(false);
		area.setBorder(null);
		area.setFont(font);
		area.setForeground(color);
		// setText would otherwise move the caret and scroll the list to it
		((javax.swing.text.DefaultCaret) area.getCaret()).setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);
		return area;
	}

	static JPanel row(int gap, JComponent... parts)
	{
		JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, gap, 0));
		p.setOpaque(false);
		for (JComponent c : parts)
		{
			p.add(c);
		}
		return p;
	}

	static JLabel emptyState(String title, String hint)
	{
		JLabel label = new JLabel("<html><div style='text-align:center'><b style='font-size:14px'>" + title
			+ "</b><br><br><span style='color:#9A9A9A'>" + hint + "</span></div></html>", SwingConstants.CENTER);
		label.setAlignmentX(0.5f);
		label.setBorder(BorderFactory.createEmptyBorder(60, 0, 0, 0));
		label.setMaximumSize(new Dimension(Integer.MAX_VALUE, 200));
		return label;
	}

	// ---- plugin icons ----

	private static final Map<String, Icon> ICONS = new ConcurrentHashMap<>();
	private static final Color[] ICON_COLORS = {
		new Color(0x2F6FDE), new Color(0x8957E5), new Color(0x2DA44E), new Color(0xD1495B),
		new Color(0xDC8A00), new Color(0x1B998B), new Color(0xBF3989), new Color(0x6E7781),
	};

	/** The project's icon.png if it has one, else a coloured tile with its initials */
	static Icon pluginIcon(String name, Path iconFile, int size)
	{
		// Plugin Hub icons download after the card is first shown, so the key notes whether the file exists yet
		boolean exists = iconFile != null && java.nio.file.Files.exists(iconFile);
		String key = name + "|" + iconFile + "|" + exists + "|" + size;
		if (!exists)
		{
			iconFile = null;
		}
		Path file = iconFile;
		return ICONS.computeIfAbsent(key, k ->
		{
			if (file != null)
			{
				try
				{
					BufferedImage img = ImageIO.read(file.toFile());
					if (img != null)
					{
						// keep the aspect ratio; Plugin Hub icons are 48x72
						double scale = Math.min((double) size / img.getWidth(), (double) size / img.getHeight());
						int w = Math.max(1, (int) Math.round(img.getWidth() * scale));
						int h = Math.max(1, (int) Math.round(img.getHeight() * scale));
						return new ImageIcon(roundedTile(img.getScaledInstance(w, h, Image.SCALE_SMOOTH), w, h, size));
					}
				}
				catch (Exception ignored)
				{
				}
			}
			return new ImageIcon(initialsTile(name, size));
		});
	}

	private static BufferedImage roundedTile(Image img, int w, int h, int size)
	{
		BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0x333333));
		g.fillRoundRect(0, 0, size, size, 12, 12);
		g.setClip(new java.awt.geom.RoundRectangle2D.Float(0, 0, size, size, 12, 12));
		g.drawImage(img, (size - w) / 2, (size - h) / 2, null);
		g.dispose();
		return out;
	}

	private static BufferedImage initialsTile(String name, int size)
	{
		String clean = name.replaceAll("\\[[^]]*]", "").replaceAll("[^A-Za-z0-9 ]", " ").trim();
		StringBuilder initials = new StringBuilder();
		for (String word : clean.split("\\s+"))
		{
			if (!word.isEmpty() && initials.length() < 2)
			{
				initials.append(Character.toUpperCase(word.charAt(0)));
			}
		}
		if (initials.length() == 0)
		{
			initials.append('?');
		}

		BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setColor(ICON_COLORS[Math.floorMod(clean.hashCode(), ICON_COLORS.length)]);
		g.fillRoundRect(0, 0, size, size, 12, 12);
		g.setColor(new Color(255, 255, 255, 40));
		g.setStroke(new BasicStroke(1f));
		g.drawRoundRect(0, 0, size - 1, size - 1, 12, 12);
		g.setColor(Color.WHITE);
		g.setFont(Theme.font(Font.BOLD, size * 0.38f));
		FontMetrics fm = g.getFontMetrics();
		String s = initials.toString();
		g.drawString(s, (size - fm.stringWidth(s)) / 2, (size - fm.getHeight()) / 2 + fm.getAscent());
		g.dispose();
		return out;
	}
}
