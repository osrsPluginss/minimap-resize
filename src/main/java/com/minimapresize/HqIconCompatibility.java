package com.minimapresize;

import java.awt.Graphics2D;
import java.util.function.Predicate;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;

/** Coordinates the existing HQ painter through RuneLite's public overlay API. */
final class HqIconCompatibility
{
	private static final String PAINTER = "io.mark.hditemicons.hd.HdItemIcons$UnderInterfacePainter";
	private final OverlayManager manager;
	private final Predicate<Overlay> identifiesPainter;
	private Overlay painter;
	private OverlayLayer originalLayer;
	private boolean painted;

	HqIconCompatibility(OverlayManager manager)
	{
		this(manager, overlay -> PAINTER.equals(overlay.getClass().getName()));
	}

	HqIconCompatibility(OverlayManager manager, Predicate<Overlay> identifiesPainter)
	{
		this.manager = manager;
		this.identifiesPainter = identifiesPainter;
	}

	void beginFrame(boolean needed)
	{
		painted = false;
		if (!needed)
		{
			restore();
			return;
		}
		if (painter != null && !manager.anyMatch(overlay -> overlay == painter))
		{
			// HQ was disabled. Restore the detached instance without re-registering it.
			painter.setLayer(originalLayer);
			painter = null;
		}
		if (painter == null)
		{
			manager.anyMatch(overlay ->
			{
				if (!identifiesPainter.test(overlay)
					|| overlay.getLayer() != OverlayLayer.ALWAYS_ON_TOP || !overlay.getDrawHooks().isEmpty()) { return false; }
				painter = overlay;
				return true;
			});
			if (painter != null)
			{
				originalLayer = painter.getLayer();
				manager.remove(painter);
				painter.setLayer(OverlayLayer.MANUAL);
				manager.add(painter);
			}
		}
	}

	boolean active() { return painter != null; }

	void paint(Graphics2D graphics)
	{
		if (painter == null || painted) { return; }
		painted = true;
		Graphics2D copy = (Graphics2D) graphics.create();
		try { painter.render(copy); }
		finally { copy.dispose(); }
	}

	void restore()
	{
		if (painter == null) { return; }
		boolean registered = manager.remove(painter);
		painter.setLayer(originalLayer);
		if (registered) { manager.add(painter); }
		painter = null;
	}
}
