package com.minimapresize;

import java.awt.Dimension;
import java.awt.Graphics2D;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

final class OverheadOverlay extends Overlay
{
	private final MinimapResizePlugin plugin;
	private final boolean before;

	OverheadOverlay(MinimapResizePlugin plugin, boolean before)
	{
		this.plugin = plugin;
		this.before = before;
		// Last ABOVE_SCENE and first UNDER_WIDGETS: only the game's overhead pass
		// lies between these, not RuneLite's scene labels or interface overlays.
		setPosition(before ? OverlayPosition.TOOLTIP : OverlayPosition.DETACHED);
		setLayer(before ? OverlayLayer.ABOVE_SCENE : OverlayLayer.UNDER_WIDGETS);
		setPriority(-10000);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		plugin.overheads(graphics, before);
		return null;
	}
}
