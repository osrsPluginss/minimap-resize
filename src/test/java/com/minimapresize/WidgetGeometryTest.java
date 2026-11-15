package com.minimapresize;

import java.awt.Rectangle;
import net.runelite.api.widgets.Widget;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class WidgetGeometryTest
{
	private Widget widget(int id, Rectangle layout, Widget parent)
	{
		Widget widget = mock(Widget.class);
		when(widget.getId()).thenReturn(id);
		when(widget.getWidth()).thenReturn(layout.width);
		when(widget.getHeight()).thenReturn(layout.height);
		when(widget.getRelativeX()).thenReturn(layout.x);
		when(widget.getRelativeY()).thenReturn(layout.y);
		when(widget.getParent()).thenReturn(parent);
		when(widget.getBounds()).thenReturn(new Rectangle(900, 900, layout.width, layout.height));
		return widget;
	}

	@Test
	public void projectedPositionsFollowParentScrollButAttachedGroupsUseTheUnscrolledHostOrigin()
	{
		Widget top = widget(164 << 16, new Rectangle(20, 30, 1000, 700), null);
		when(top.getScrollX()).thenReturn(5);
		when(top.getScrollY()).thenReturn(7);
		Widget host = widget(164 << 16 | 16, new Rectangle(100, 50, 512, 350), top);
		when(host.getScrollX()).thenReturn(11);
		when(host.getScrollY()).thenReturn(13);
		Widget bank = widget(12 << 16, new Rectangle(0, 0, 512, 350), host);
		when(bank.getScrollX()).thenReturn(2);
		when(bank.getScrollY()).thenReturn(3);
		Widget scroll = widget(12 << 16 | 3, new Rectangle(10, 20, 450, 300), bank);
		when(scroll.getScrollX()).thenReturn(7);
		when(scroll.getScrollY()).thenReturn(9);
		Widget control = widget(12 << 16 | 4, new Rectangle(30, 40, 50, 20), scroll);
		WidgetGeometry geometry = new WidgetGeometry();
		assertEquals(new Rectangle(115, 73, 512, 350), geometry.bounds(host));
		assertEquals(new Rectangle(115, 73, 512, 350), geometry.bounds(bank));
		assertEquals(new Rectangle(146, 121, 50, 20), geometry.bounds(control));
		assertEquals(new Rectangle(900, 900, 50, 20), control.getBounds());
	}
}
