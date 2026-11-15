package com.hdui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;

/** Contract fixture: one star feature narrower than a native pixel, and a translucent texel. */
public final class ScaledInventoryOverlay extends Overlay
{
	public Rectangle bounds = new Rectangle(0, 0, 100, 100);
	public int calls;
	public int colour = 0xffffcc;
	public Rectangle drag;
	public boolean solidMarker;
	public java.util.function.Consumer<Graphics2D> contents;

	public ScaledInventoryOverlay() { setLayer(OverlayLayer.ALWAYS_ON_TOP); }

	@Override public Dimension render(Graphics2D graphics)
	{
		calls++;
		if (contents != null) { contents.accept(graphics); return null; }
		graphics.setColor(new Color(colour));
		graphics.fill(new Rectangle2D.Double(bounds.x + 20.5, bounds.y + 25, .5, 3));
		if (solidMarker) { graphics.fillRect(bounds.x + 40, bounds.y + 40, 1, 1); }
		graphics.setColor(new Color(255, 0, 0, 128));
		graphics.fill(new Rectangle(bounds.x + 30, bounds.y + 30, 1, 1));
		graphics.setColor(Color.GREEN);
		graphics.fill(new Rectangle(bounds.x - 2, bounds.y - 2, 1, 1));
		if (drag != null)
		{
			graphics.setColor(new Color(255, 128, 0, 128));
			graphics.fill(drag);
		}
		return null;
	}
}
