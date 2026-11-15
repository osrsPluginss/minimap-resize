package com.minimapresize;

import java.awt.Point;
import java.awt.Rectangle;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.api.widgets.WidgetType;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class InterfaceLayoutTest
{
	/** Model native MINUS/ABSOLUTE sizing and a bank that grows with its available height. */
	static final class ResponsiveWindow
	{
		final Widget host = mock(Widget.class);
		final Widget root = mock(Widget.class);
		final Widget frame = mock(Widget.class);
		final Widget close = mock(Widget.class);
		final Widget bottom = mock(Widget.class);
		final Rectangle hostBounds = new Rectangle();
		final Rectangle windowBounds = new Rectangle();
		final Rectangle closeBounds = new Rectangle();
		final Rectangle bottomBounds = new Rectangle();
		final java.util.Map<Widget, Point> renderPositions = new java.util.IdentityHashMap<>();
		boolean deferRenderPositions;
		boolean centeredHost;
		int parentWidth = 1000, parentHeight = 700;
		int topInset = 12, heightMargin = 24;
		int originalWidth, originalHeight;
		int widthMode = WidgetSizeMode.MINUS, heightMode = WidgetSizeMode.MINUS;
		int maximumHeight = 544;
		int group = InterfaceID.BANKMAIN;

		ResponsiveWindow(int hostId)
		{
			when(host.getId()).thenReturn(hostId);
			when(host.getNestedChildren()).thenReturn(new Widget[]{root});
			when(host.getBounds()).thenAnswer(call -> renderedBounds(host, hostBounds));
			when(host.getWidth()).thenAnswer(call -> hostBounds.width);
			when(host.getHeight()).thenAnswer(call -> hostBounds.height);
			when(host.getRelativeX()).thenAnswer(call -> hostBounds.x);
			when(host.getRelativeY()).thenAnswer(call -> hostBounds.y);
			when(host.getOriginalWidth()).thenAnswer(call -> originalWidth);
			when(host.getOriginalHeight()).thenAnswer(call -> originalHeight);
			when(host.getWidthMode()).thenAnswer(call -> widthMode);
			when(host.getHeightMode()).thenAnswer(call -> heightMode);
			when(host.setOriginalWidth(anyInt())).thenAnswer(call -> { originalWidth = call.getArgument(0); return host; });
			when(host.setOriginalHeight(anyInt())).thenAnswer(call -> { originalHeight = call.getArgument(0); return host; });
			when(host.setWidthMode(anyInt())).thenAnswer(call -> { widthMode = call.getArgument(0); return host; });
			when(host.setHeightMode(anyInt())).thenAnswer(call -> { heightMode = call.getArgument(0); return host; });
			doAnswer(call -> { reflow(); return null; }).when(host).revalidateScroll();
			when(root.getId()).thenAnswer(call -> group << 16);
			when(root.getBounds()).thenAnswer(call -> renderedBounds(root, hostBounds));
			when(root.getWidth()).thenAnswer(call -> hostBounds.width);
			when(root.getHeight()).thenAnswer(call -> hostBounds.height);
			when(root.getParent()).thenReturn(host);
			when(root.getStaticChildren()).thenReturn(new Widget[]{frame, close, bottom});
			when(frame.getType()).thenReturn(WidgetType.RECTANGLE);
			when(frame.getBounds()).thenAnswer(call -> renderedBounds(frame, windowBounds));
			when(frame.getWidth()).thenAnswer(call -> windowBounds.width);
			when(frame.getHeight()).thenAnswer(call -> windowBounds.height);
			when(frame.getRelativeX()).thenAnswer(call -> windowBounds.x - hostBounds.x);
			when(frame.getRelativeY()).thenAnswer(call -> windowBounds.y - hostBounds.y);
			when(frame.getParent()).thenReturn(root);
			when(close.getType()).thenReturn(WidgetType.RECTANGLE);
			when(close.getBounds()).thenAnswer(call -> renderedBounds(close, closeBounds));
			when(close.getWidth()).thenAnswer(call -> closeBounds.width);
			when(close.getHeight()).thenAnswer(call -> closeBounds.height);
			when(close.getRelativeX()).thenAnswer(call -> closeBounds.x - hostBounds.x);
			when(close.getRelativeY()).thenAnswer(call -> closeBounds.y - hostBounds.y);
			when(close.getParent()).thenReturn(root);
			when(bottom.getType()).thenReturn(WidgetType.RECTANGLE);
			when(bottom.getBounds()).thenAnswer(call -> renderedBounds(bottom, bottomBounds));
			when(bottom.getWidth()).thenAnswer(call -> bottomBounds.width);
			when(bottom.getHeight()).thenAnswer(call -> bottomBounds.height);
			when(bottom.getRelativeX()).thenAnswer(call -> bottomBounds.x - hostBounds.x);
			when(bottom.getRelativeY()).thenAnswer(call -> bottomBounds.y - hostBounds.y);
			when(bottom.getParent()).thenReturn(root);
			reflow();
			commitNativeDraw();
		}

		private Rectangle renderedBounds(Widget widget, Rectangle layoutBounds)
		{
			Point position = deferRenderPositions ? renderPositions.get(widget) : null;
			return position == null ? new Rectangle(layoutBounds)
				: new Rectangle(position.x, position.y, layoutBounds.width, layoutBounds.height);
		}

		void commitNativeDraw()
		{
			renderPositions.put(host, hostBounds.getLocation());
			renderPositions.put(root, hostBounds.getLocation());
			renderPositions.put(frame, windowBounds.getLocation());
			renderPositions.put(close, closeBounds.getLocation());
			renderPositions.put(bottom, bottomBounds.getLocation());
		}

		void reflow()
		{
			int width = widthMode == WidgetSizeMode.MINUS ? parentWidth - originalWidth : originalWidth;
			int height = heightMode == WidgetSizeMode.MINUS ? parentHeight - originalHeight : originalHeight;
			hostBounds.setBounds(centeredHost ? (parentWidth - width) / 2 : 0,
				centeredHost ? (parentHeight - height) / 2 : 0, width, height);
			windowBounds.setBounds(hostBounds.x + (width - 488) / 2, hostBounds.y + topInset,
				488, Math.min(maximumHeight, height - heightMargin));
			closeBounds.setBounds(windowBounds.x + 464, windowBounds.y + 8, 16, 16);
			bottomBounds.setBounds(windowBounds.x + 20, windowBounds.y + windowBounds.height - 24, 24, 16);
		}
	}

	@Test
	public void reflowedCenteredHostUsesItsFreshOriginForTheFirstScaledFrame()
	{
		ResponsiveWindow bank = new ResponsiveWindow(InterfaceID.ToplevelPreEoc.MAINMODAL);
		bank.deferRenderPositions = true;
		// Native center positioning updates relative X/Y during revalidation, while
		// recorded render X/Y still describe the old full-size host until drawing.
		when(bank.host.getRelativeX()).thenAnswer(call -> (1000 - bank.hostBounds.width) / 2);
		when(bank.host.getRelativeY()).thenAnswer(call -> (700 - bank.hostBounds.height) / 2);
		InterfaceLayout layout = new InterfaceLayout();
		layout.update(WidgetRegion.INTERFACE, bank.host, 200, 1000, 700);
		Rectangle predicted = WidgetRegion.INTERFACE.captureBounds(bank.host);
		assertEquals(new Rectangle(256, 187, 488, 326), predicted);
		assertNotEquals(predicted, WidgetRegion.INTERFACE.bounds(bank.host));
		// Scaling now occurs after native host drawing, where its real origin is authoritative.
		bank.renderPositions.put(bank.host, new Point(244, 175));
		MinimapTransform transform = layout.transform(WidgetRegion.INTERFACE, predicted, 200, 1000, 700);
		assertEquals(new Rectangle(12, 24, 976, 652), transform.destination());
		assertEquals(new Point(728, 203), transform.toSource(new Point(956, 56)));
		layout.restore();
	}

	@Test
	public void bankReflowsBeforeScalingPastItsOld129PercentCeiling()
	{
		ResponsiveWindow bank = new ResponsiveWindow(InterfaceID.ToplevelPreEoc.MAINMODAL);
		Rectangle natural = new Rectangle(bank.windowBounds);
		MinimapTransform old = MinimapTransform.anchored(natural, 200, 1000, 700, false);
		assertEquals(700, old.destination().height);
		assertEquals(1.29, (double) old.destination().height / natural.height, 0.01);
		InterfaceLayout layout = new InterfaceLayout();
		assertTrue(layout.update(WidgetRegion.INTERFACE, bank.host, 200, 1000, 700));
		assertEquals(new Rectangle(512, 350), bank.hostBounds);
		assertTrue(bank.windowBounds.height < natural.height);
		Rectangle source = WidgetRegion.INTERFACE.bounds(bank.host);
		MinimapTransform scaled = layout.transform(WidgetRegion.INTERFACE, source, 200, 1000, 700);
		assertEquals(new Rectangle(12, 24, 976, 652), scaled.destination());
		assertTrue(scaled.destination().width > old.destination().width);
		for (Rectangle control : new Rectangle[]{bank.closeBounds, bank.bottomBounds})
		{
			Point nativePoint = new Point(control.x + 8, control.y + 8);
			Point visiblePoint = new Point(scaled.destination().x + (nativePoint.x - source.x) * 2,
				scaled.destination().y + (nativePoint.y - source.y) * 2);
			assertTrue(new Rectangle(1000, 700).contains(visiblePoint));
			assertEquals(nativePoint, scaled.toSource(visiblePoint));
		}
		assertFalse(layout.update(WidgetRegion.INTERFACE, bank.host, 200, 1000, 700));
		verify(bank.host, times(1)).revalidateScroll();
		layout.restore();
		assertEquals(new Rectangle(1000, 700), bank.hostBounds);
		assertEquals(natural, bank.windowBounds);
	}

	@Test
	public void loweringSizeOrClosingWindowRestoresNativeSizingModes()
	{
		ResponsiveWindow bank = new ResponsiveWindow(InterfaceID.ToplevelPreEoc.MAINMODAL);
		InterfaceLayout layout = new InterfaceLayout();
		layout.update(WidgetRegion.INTERFACE, bank.host, 200, 1000, 700);
		assertTrue(layout.update(WidgetRegion.INTERFACE, bank.host, 100, 1000, 700));
		assertEquals(new Rectangle(1000, 700), bank.hostBounds);
		assertEquals(WidgetSizeMode.MINUS, bank.widthMode);
		assertEquals(WidgetSizeMode.MINUS, bank.heightMode);
		layout.update(WidgetRegion.INTERFACE, bank.host, 200, 1000, 700);
		assertTrue(layout.update(WidgetRegion.INTERFACE, null, 200, 1000, 700));
		assertEquals(new Rectangle(1000, 700), bank.hostBounds);
		layout.restore();
	}

	@Test
	public void fixedWindowsThatFitDoNotReflowAndGroupChangesRestoreParent()
	{
		ResponsiveWindow bank = new ResponsiveWindow(InterfaceID.ToplevelPreEoc.MAINMODAL);
		InterfaceLayout layout = new InterfaceLayout();
		layout.update(WidgetRegion.INTERFACE, bank.host, 200, 1000, 700);
		bank.group = InterfaceID.GE_OFFERS;
		bank.maximumHeight = 335;
		assertTrue(layout.update(WidgetRegion.INTERFACE, bank.host, 200, 1000, 700));
		assertEquals(new Rectangle(1000, 700), bank.hostBounds);
		clearInvocations(bank.host);
		assertFalse(layout.update(WidgetRegion.INTERFACE, bank.host, 200, 1000, 700));
		verify(bank.host, never()).revalidateScroll();
	}

	@Test
	public void extremeSizesKeepMinimumLayoutAndControlsOnScreenWithoutOverwritingExternalChanges()
	{
		ResponsiveWindow bank = new ResponsiveWindow(InterfaceID.ToplevelPreEoc.MAINMODAL);
		InterfaceLayout layout = new InterfaceLayout();
		layout.update(WidgetRegion.INTERFACE, bank.host, 400, 1000, 700);
		assertEquals(new Rectangle(512, 350), bank.hostBounds);
		MinimapTransform transform = layout.transform(WidgetRegion.INTERFACE, bank.windowBounds, 400, 1000, 700);
		assertTrue(new Rectangle(1000, 700).contains(transform.destination()));
		assertEquals(1000, transform.destination().width);
		// Another script deliberately changes width while our height is still active.
		bank.host.setOriginalWidth(777);
		bank.host.revalidateScroll();
		layout.restore();
		assertEquals(777, bank.originalWidth);
		assertEquals(WidgetSizeMode.ABSOLUTE, bank.widthMode);
		assertEquals(0, bank.originalHeight);
		assertEquals(WidgetSizeMode.MINUS, bank.heightMode);
	}
}
