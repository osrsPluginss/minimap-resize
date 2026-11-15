package com.minimapresize;

import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class WidgetRegionTest
{
	@Test
	public void modalBoundsIgnoreFullViewportUniverseLayersAndClipScrolledContent()
	{
		Widget host = mock(Widget.class), universe = mock(Widget.class), frame = mock(Widget.class);
		Widget scroll = mock(Widget.class), rows = mock(Widget.class), hidden = mock(Widget.class);
		when(host.getBounds()).thenReturn(new java.awt.Rectangle(1000, 700));
		when(host.getNestedChildren()).thenReturn(new Widget[]{universe});
		when(universe.getBounds()).thenReturn(new java.awt.Rectangle(1000, 700));
		when(universe.getStaticChildren()).thenReturn(new Widget[]{frame, scroll, hidden});
		when(frame.getType()).thenReturn(net.runelite.api.widgets.WidgetType.RECTANGLE);
		when(frame.getBounds()).thenReturn(new java.awt.Rectangle(250, 100, 500, 400));
		when(scroll.getBounds()).thenReturn(new java.awt.Rectangle(260, 140, 470, 300));
		when(scroll.getDynamicChildren()).thenReturn(new Widget[]{rows});
		when(rows.getType()).thenReturn(net.runelite.api.widgets.WidgetType.TEXT);
		when(rows.getBounds()).thenReturn(new java.awt.Rectangle(260, -100, 470, 3000));
		when(hidden.isHidden()).thenReturn(true);
		when(hidden.getBounds()).thenReturn(new java.awt.Rectangle(1000, 700));
		assertEquals(new java.awt.Rectangle(250, 100, 500, 400), WidgetRegion.INTERFACE.bounds(host));
		when(universe.getStaticChildren()).thenReturn(new Widget[0]);
		assertTrue(WidgetRegion.INTERFACE.bounds(host).isEmpty());
	}

	@Test
	public void activeRootDeterminesLayoutRegardlessOfCachedWidgets()
	{
		Client client = mock(Client.class);
		when(client.isResized()).thenReturn(true);
		when(client.getTopLevelInterfaceId()).thenReturn(InterfaceID.TOPLEVEL_OSRS_STRETCH);
		assertEquals(ResizableLayout.CLASSIC, ResizableLayout.current(client));
		when(client.getTopLevelInterfaceId()).thenReturn(InterfaceID.TOPLEVEL_PRE_EOC);
		assertEquals(ResizableLayout.MODERN, ResizableLayout.current(client));
		when(client.getTopLevelInterfaceId()).thenReturn(InterfaceID.TOPLEVEL_DISPLAY);
		assertEquals(ResizableLayout.UNSUPPORTED, ResizableLayout.current(client));
		when(client.isResized()).thenReturn(false);
		when(client.getTopLevelInterfaceId()).thenReturn(InterfaceID.TOPLEVEL_PRE_EOC);
		assertEquals(ResizableLayout.UNSUPPORTED, ResizableLayout.current(client));
	}

	@Test
	public void classicInventoryUsesFullClassicFrameAndIgnoresVisibleModernCache()
	{
		Client client = mock(Client.class);
		Widget classic = mock(Widget.class);
		Widget modern = mock(Widget.class);
		when(client.getWidget(InterfaceID.ToplevelOsrsStretch.SIDE_MENU)).thenReturn(classic);
		when(client.getWidget(InterfaceID.ToplevelPreEoc.SIDE_CONTAINER)).thenReturn(modern);
		assertSame(classic, WidgetRegion.SIDE_PANEL.visibleWidget(client, ResizableLayout.CLASSIC));
		verify(client, never()).getWidget(InterfaceID.ToplevelPreEoc.SIDE_CONTAINER);
		verify(client, never()).getWidget(InterfaceID.ToplevelOsrsStretch.SIDE_CONTAINER);
	}

	@Test
	public void missingOrHiddenActivePanelDoesNotFallBackToAnotherLayout()
	{
		Client client = mock(Client.class);
		Widget classic = mock(Widget.class);
		Widget modern = mock(Widget.class);
		when(client.getWidget(InterfaceID.ToplevelPreEoc.SIDE_CONTAINER)).thenReturn(modern);
		assertNull(WidgetRegion.SIDE_PANEL.visibleWidget(client, ResizableLayout.CLASSIC));
		when(client.getWidget(InterfaceID.ToplevelOsrsStretch.SIDE_MENU)).thenReturn(classic);
		when(classic.isHidden()).thenReturn(true);
		assertNull(WidgetRegion.SIDE_PANEL.visibleWidget(client, ResizableLayout.CLASSIC));
		verify(client, never()).getWidget(InterfaceID.ToplevelPreEoc.SIDE_CONTAINER);
	}

	@Test
	public void modernBarsAreUnavailableInClassicLayout()
	{
		Client client = mock(Client.class);
		assertNull(WidgetRegion.UPPER_TABS.visibleWidget(client, ResizableLayout.CLASSIC));
		assertNull(WidgetRegion.LOWER_TABS.visibleWidget(client, ResizableLayout.CLASSIC));
		verifyNoInteractions(client);
	}
}
