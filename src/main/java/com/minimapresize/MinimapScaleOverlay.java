package com.minimapresize;

import java.awt.Dimension;
import java.awt.Graphics2D;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

final class MinimapScaleOverlay extends Overlay
{
	private final MinimapResizePlugin plugin;
	private final WidgetRegion region;
	private final int component;

	MinimapScaleOverlay(MinimapResizePlugin plugin, WidgetRegion region, int component)
	{
		this.plugin = plugin;
		this.region = region;
		this.component = component;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.MANUAL);
		setPriority(PRIORITY_HIGHEST + 1);
		// CHAT_CONTAINER's layer callback runs before its attached chatbox paints.
		// Wait for the whole group, including its typing line and controls.
		if (region == WidgetRegion.CHAT) { drawAfterInterface(InterfaceID.CHATBOX); }
		else { drawAfterLayer(component); }
	}

	@Override
	public String getName() { return "MinimapResize_" + region + "_" + component; }

	@Override
	public Dimension render(Graphics2D graphics)
	{
		plugin.drawScaled(region, component, graphics);
		return null;
	}
}
