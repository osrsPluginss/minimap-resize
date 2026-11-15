package com.minimapresize;

import java.awt.Dimension;
import java.awt.Graphics2D;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** A host's layer hook precedes its attached interface; capture after the attached group instead. */
final class InterfaceScaleOverlay extends Overlay
{
	private final MinimapResizePlugin plugin;
	private final WidgetRegion region;
	private final int interfaceId;

	InterfaceScaleOverlay(MinimapResizePlugin plugin, WidgetRegion region, int interfaceId)
	{
		this.plugin = plugin;
		this.region = region;
		this.interfaceId = interfaceId;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.MANUAL);
		setPriority(PRIORITY_HIGHEST + 1);
		drawAfterInterface(interfaceId);
	}

	@Override
	public String getName() { return "MinimapResize_" + region + "_Interface_" + interfaceId; }

	@Override
	public Dimension render(Graphics2D graphics)
	{
		plugin.drawInterfaceScaled(region, interfaceId, graphics);
		return null;
	}
}
