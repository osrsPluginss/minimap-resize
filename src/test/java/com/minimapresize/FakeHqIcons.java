package com.minimapresize;

import java.awt.Dimension;
import java.awt.Graphics2D;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** Test double for the upstream late GPU painter; no upstream code is bundled. */
public final class FakeHqIcons
{
	public static final class UnderInterfacePainter extends Overlay
	{
		public Runnable draw = () -> { };
		public int calls;

		public UnderInterfacePainter()
		{
			setLayer(OverlayLayer.ALWAYS_ON_TOP);
			setPosition(OverlayPosition.DYNAMIC);
			setPriority(PRIORITY_HIGHEST + 1);
		}

		@Override
		public Dimension render(Graphics2D graphics)
		{
			calls++;
			draw.run();
			return null;
		}
	}
}
