package com.minimapresize;

import java.awt.Dimension;
import java.awt.Graphics2D;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

final class HoverOverlay extends Overlay
{
	private final MinimapResizePlugin plugin;
	private final boolean tooltip;

	HoverOverlay(MinimapResizePlugin plugin, boolean tooltip)
	{
		this.plugin = plugin;
		this.tooltip = tooltip;
		setPosition(tooltip ? OverlayPosition.TOOLTIP : OverlayPosition.DYNAMIC);
		setLayer(tooltip ? OverlayLayer.ABOVE_WIDGETS : OverlayLayer.ALWAYS_ON_TOP);
		setPriority(tooltip ? PRIORITY_HIGHEST : -10000);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		plugin.drawHover(graphics, tooltip);
		return null;
	}
}
