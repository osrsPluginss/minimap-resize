package com.minimapresize;

import com.minimapresize.FakeHqIcons.UnderInterfacePainter;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class HqIconCompatibilityTest
{
	@Test
	public void togglesRestoreOnlyRegisteredPaintersAndRenderOncePerFrame()
	{
		OverlayManager manager = mock(OverlayManager.class);
		List<Overlay> registered = new ArrayList<>();
		when(manager.anyMatch(any())).thenAnswer(call -> registered.stream().anyMatch(call.getArgument(0)));
		when(manager.add(any())).thenAnswer(call -> registered.add(call.getArgument(0)));
		when(manager.remove(any())).thenAnswer(call -> registered.remove((Object) call.getArgument(0)));
		HqIconCompatibility bridge = new HqIconCompatibility(manager, overlay -> overlay instanceof UnderInterfacePainter);
		bridge.beginFrame(true);
		assertFalse(bridge.active());
		UnderInterfacePainter first = new UnderInterfacePainter();
		manager.add(first);
		bridge.beginFrame(true);
		assertTrue(bridge.active());
		Graphics2D graphics = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
		try
		{
			bridge.paint(graphics);
			bridge.paint(graphics);
			assertEquals(1, first.calls);
			bridge.beginFrame(true);
			bridge.paint(graphics);
			assertEquals(2, first.calls);
		}
		finally { graphics.dispose(); }
		manager.remove(first);
		bridge.beginFrame(true);
		assertFalse(bridge.active());
		assertEquals(OverlayLayer.ALWAYS_ON_TOP, first.getLayer());
		assertTrue(registered.isEmpty());
		UnderInterfacePainter second = new UnderInterfacePainter();
		manager.add(second);
		bridge.beginFrame(true);
		bridge.beginFrame(false);
		assertEquals(OverlayLayer.ALWAYS_ON_TOP, second.getLayer());
		assertEquals(1, registered.size());
		bridge.beginFrame(true);
		manager.remove(second);
		bridge.restore();
		assertTrue(registered.isEmpty());
		assertEquals(OverlayLayer.ALWAYS_ON_TOP, second.getLayer());
	}
}
