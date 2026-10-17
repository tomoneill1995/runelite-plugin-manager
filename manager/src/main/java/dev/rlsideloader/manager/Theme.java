package dev.rlsideloader.manager;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import java.awt.Color;
import java.awt.Font;
import java.util.Map;
import javax.swing.UIManager;

/**
 * Colours and fonts, loosely matching RuneLite's dark sidebar with its orange accent.
 */
final class Theme
{
	static final Color ACCENT = new Color(0xDC8A00);
	static final Color BACKGROUND = new Color(0x1E1E1E);
	static final Color SURFACE = new Color(0x282828);
	static final Color SURFACE_HOVER = new Color(0x313131);
	static final Color BORDER = new Color(0x3A3A3A);
	static final Color TEXT = new Color(0xE6E6E6);
	static final Color TEXT_DIM = new Color(0x9A9A9A);
	static final Color GREEN = new Color(0x3FB950);
	static final Color RED = new Color(0xF0605A);
	static final Color AMBER = new Color(0xE3B341);
	static final Color CHIP = new Color(0x363636);

	private Theme()
	{
	}

	static void install()
	{
		FlatLaf.setGlobalExtraDefaults(Map.of("@accentColor", "#DC8A00", "@background", "#1E1E1E"));
		FlatDarkLaf.setup();
		UIManager.put("Component.arc", 10);
		UIManager.put("Button.arc", 10);
		UIManager.put("TextComponent.arc", 10);
		UIManager.put("ScrollBar.showButtons", false);
		UIManager.put("ScrollBar.width", 10);
		UIManager.put("ScrollBar.thumbArc", 999);
		UIManager.put("ScrollBar.thumbInsets", new java.awt.Insets(2, 2, 2, 2));
		UIManager.put("TabbedPane.showTabSeparators", false);
		UIManager.put("TabbedPane.tabHeight", 38);
		UIManager.put("TabbedPane.underlineColor", ACCENT);
		UIManager.put("TabbedPane.selectedBackground", BACKGROUND);
		UIManager.put("Panel.background", BACKGROUND);
		UIManager.put("PopupMenu.borderCornerRadius", 8);
	}

	static Font font(int style, float size)
	{
		return UIManager.getFont("Label.font").deriveFont(style, size);
	}
}
