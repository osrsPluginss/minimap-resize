package com.minimapresize;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicBoolean;
import net.runelite.api.Client;
import net.runelite.client.config.RuneLiteConfig;
import net.runelite.client.config.TooltipPositionType;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.components.LayoutableRenderableEntity;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import net.runelite.client.ui.overlay.tooltip.TooltipOverlay;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class HoverScalerTest
{
	@Test
	public void scaledTooltipUsesVisibleCursorClampsToCanvasAndRestoresOriginalPainter()
	{
		Client client = mock(Client.class);
		when(client.isGpu()).thenReturn(true);
		OverlayManager overlays = mock(OverlayManager.class);
		TooltipOverlay original = mock(TooltipOverlay.class);
		when(overlays.remove(original)).thenReturn(true);
		TooltipManager manager = new TooltipManager();
		MinimapResizeConfig config = mock(MinimapResizeConfig.class);
		when(config.hoverScale()).thenReturn(200);
		when(config.filter()).thenReturn(ScalingFilter.NEAREST);
		RuneLiteConfig rl = new RuneLiteConfig() {};
		HoverScaler hover = new HoverScaler(client, overlays, manager, original, rl, config);
		hover.beginFrame(true);
		LayoutableRenderableEntity component = mock(LayoutableRenderableEntity.class);
		when(component.render(any())).thenAnswer(call ->
		{
			Graphics2D g = call.getArgument(0);
			g.setColor(Color.RED);
			g.fillRect(0, 0, 20, 10);
			return new Dimension(20, 10);
		});
		manager.add(new Tooltip(component));
		BufferedImage image = new BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB_PRE);
		Graphics2D g = image.createGraphics();
		try { hover.drawTooltips(image, g, new Point(195, 90)); }
		finally { g.dispose(); }
		int y = rl.tooltipPosition() == TooltipPositionType.ABOVE_CURSOR ? 66 : 76;
		assertEquals(Color.RED.getRGB(), image.getRGB(160, y));
		assertEquals(Color.RED.getRGB(), image.getRGB(199, y + 19));
		assertEquals(0, image.getRGB(159, y));
		assertTrue(manager.getTooltips().isEmpty());
		verify(original, never()).render(any());
		hover.beginFrame(true);
		verify(overlays, times(1)).remove(original);
		hover.beginFrame(false);
		hover.clear();
		verify(overlays, times(1)).add(original);
	}

	@Test
	public void largeCustomTooltipGrowsItsTextureAndKeepsItsFullScaledContents()
	{
		Client client = mock(Client.class);
		when(client.isGpu()).thenReturn(true);
		OverlayManager overlays = mock(OverlayManager.class);
		TooltipOverlay original = mock(TooltipOverlay.class);
		when(overlays.remove(original)).thenReturn(true);
		TooltipManager manager = new TooltipManager();
		MinimapResizeConfig config = mock(MinimapResizeConfig.class);
		when(config.hoverScale()).thenReturn(200);
		when(config.filter()).thenReturn(ScalingFilter.NEAREST);
		RuneLiteConfig rl = mock(RuneLiteConfig.class, CALLS_REAL_METHODS);
		when(rl.tooltipPosition()).thenReturn(TooltipPositionType.UNDER_CURSOR);
		HoverScaler hover = new HoverScaler(client, overlays, manager, original, rl, config);
		hover.beginFrame(true);
		LayoutableRenderableEntity component = mock(LayoutableRenderableEntity.class);
		when(component.render(any())).thenAnswer(call ->
		{
			Graphics2D g = call.getArgument(0);
			g.setColor(Color.BLUE);
			g.fillRect(0, 0, 600, 300);
			return new Dimension(600, 300);
		});
		manager.add(new Tooltip(component));
		BufferedImage image = new BufferedImage(1200, 800, BufferedImage.TYPE_INT_ARGB_PRE);
		Graphics2D g = image.createGraphics();
		try { hover.drawTooltips(image, g, new Point(10, 0)); }
		finally { g.dispose(); }
		assertEquals(Color.BLUE.getRGB(), image.getRGB(0, 24));
		assertEquals(Color.BLUE.getRGB(), image.getRGB(1199, 623));
		assertEquals(0, image.getRGB(1199, 624));
		verify(component, times(2)).render(any());
		assertTrue(manager.getTooltips().isEmpty());
		hover.clear();
	}

	@Test
	public void nativeHoverPreferenceIsRestoredOnSkippedFrameAndShutdown()
	{
		Client client = mock(Client.class);
		AtomicBoolean enabled = new AtomicBoolean(true);
		when(client.isMouseoverTextEnabled()).thenAnswer(call -> enabled.get());
		doAnswer(call -> { enabled.set(call.getArgument(0)); return null; }).when(client).setMouseoverTextEnabled(anyBoolean());
		HoverScaler hover = new HoverScaler(client, mock(OverlayManager.class), null, null, null, mock(MinimapResizeConfig.class));
		hover.beginFrame(true);
		assertFalse(enabled.get());
		hover.beginFrame(true);
		assertFalse(enabled.get());
		hover.clear();
		assertTrue(enabled.get());
		enabled.set(false); // Mouse Tooltips intentionally hides the native hover text.
		clearInvocations(client);
		hover.beginFrame(true);
		hover.clear();
		assertFalse(enabled.get());
		verify(client, never()).setMouseoverTextEnabled(anyBoolean());
	}
}
