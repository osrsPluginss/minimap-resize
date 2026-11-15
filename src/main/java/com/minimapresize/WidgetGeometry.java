package com.minimapresize;

import java.awt.Rectangle;
import java.util.IdentityHashMap;
import java.util.Map;
import net.runelite.api.widgets.Widget;

/** One capture's projected native positions; render X/Y can still belong to the previous frame. */
final class WidgetGeometry
{
	private final Map<Widget, Rectangle> projected = new IdentityHashMap<>();

	Rectangle bounds(Widget widget)
	{
		Rectangle cached = projected.get(widget);
		if (cached != null) { return cached; }
		int width = widget.getWidth(), height = widget.getHeight();
		if (width <= 0 || height <= 0)
		{
			Rectangle rendered = widget.getBounds();
			Rectangle result = rendered == null ? new Rectangle() : new Rectangle(rendered);
			projected.put(widget, result);
			return result;
		}
		Rectangle result = new Rectangle(widget.getRelativeX(), widget.getRelativeY(), width, height);
		projected.put(widget, result);
		Widget parent = widget.getParent();
		if (parent != null)
		{
			Rectangle parentBounds = bounds(parent);
			// Static/dynamic children inherit scrolling. An attached interface group
			// is drawn at its host origin, independently of the host's scroll offsets.
			boolean sameGroup = widget.getId() >>> 16 == parent.getId() >>> 16;
			result.translate(parentBounds.x - (sameGroup ? parent.getScrollX() : 0),
				parentBounds.y - (sameGroup ? parent.getScrollY() : 0));
		}
		return result;
	}
}
