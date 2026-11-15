package com.minimapresize;

import java.awt.Rectangle;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Function;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;

/** Output stacking order; extraction follows the actual layer hooks in either layout. */
enum WidgetRegion
{
	MINIMAP(InterfaceID.ToplevelOsrsStretch.MAP_CONTAINER, InterfaceID.ToplevelPreEoc.MAP_CONTAINER),
	LOWER_TABS(-1, InterfaceID.ToplevelPreEoc.SIDE_STATIC_LAYER),
	UPPER_TABS(-1, InterfaceID.ToplevelPreEoc.SIDE_MOVABLE_LAYER),
	// Classic's frame and tab buttons are part of SIDE_MENU; SIDE_CONTAINER is only an inner layer.
	SIDE_PANEL(InterfaceID.ToplevelOsrsStretch.SIDE_MENU, InterfaceID.ToplevelPreEoc.SIDE_CONTAINER),
	CHAT(InterfaceID.ToplevelOsrsStretch.CHAT_CONTAINER, InterfaceID.ToplevelPreEoc.CHAT_CONTAINER),
	INTERFACE(InterfaceID.ToplevelOsrsStretch.MAINMODAL, InterfaceID.ToplevelPreEoc.MAINMODAL),
	INTERFACE_CRM(InterfaceID.ToplevelOsrsStretch.MAINCRM, InterfaceID.ToplevelPreEoc.MAINCRM),
	INTERFACE_FLOAT(InterfaceID.ToplevelOsrsStretch.FLOATER, InterfaceID.ToplevelPreEoc.FLOATER);

	final int[] components;
	private final int classic;
	private final int modern;

	WidgetRegion(int classic, int modern)
	{
		this.classic = classic;
		this.modern = modern;
		this.components = classic == -1 ? new int[]{modern} : new int[]{classic, modern};
	}

	int component(ResizableLayout layout)
	{
		return layout == ResizableLayout.CLASSIC ? classic : layout == ResizableLayout.MODERN ? modern : -1;
	}

	Widget visibleWidget(Client client, ResizableLayout layout)
	{
		int component = component(layout);
		if (component == -1) { return null; }
		Widget widget = client.getWidget(component);
		if (widget == null || widget.isHidden()) { return null; }
		// MAINMODAL exists even with no window open. Never publish a scene-sized
		// invisible input target merely because its empty host is visible.
		if (interfaceWindow())
		{
			Widget[] children = widget.getNestedChildren();
			if (children == null) { return null; }
			for (Widget child : children)
			{
				if (child != null && !child.isHidden()) { return widget; }
			}
			return null;
		}
		return widget;
	}

	Rectangle bounds(Widget widget)
	{
		if (!interfaceWindow()) { return widget.getBounds(); }
		return interfaceBounds(widget, Widget::getBounds);
	}

	Rectangle captureBounds(Widget widget)
	{
		if (!interfaceWindow()) { return widget.getBounds(); }
		return interfaceBounds(widget, new WidgetGeometry()::bounds);
	}

	private static Rectangle interfaceBounds(Widget widget, Function<Widget, Rectangle> geometry)
	{
		Rectangle bounds = null;
		Map<Widget, Boolean> visited = new IdentityHashMap<>();
		Widget[] nested = widget.getNestedChildren();
		if (nested != null)
		{
			for (Widget root : nested)
			{
				if (root == null || root.isHidden()) { continue; }
				Rectangle area = visibleBounds(root, geometry.apply(widget), visited, geometry);
				if (area.isEmpty()) { continue; }
				if (bounds == null) { bounds = new Rectangle(area); }
				else { bounds.add(area); }
			}
		}
		return bounds == null ? new Rectangle() : bounds;
	}

	int interfaceId(Widget host)
	{
		if (!interfaceWindow() || host == null) { return -1; }
		Widget[] roots = host.getNestedChildren();
		if (roots != null)
		{
			for (Widget root : roots)
			{
				if (root != null && !root.isHidden()) { return root.getId() >>> 16; }
			}
		}
		return -1;
	}

	private static Rectangle visibleBounds(Widget widget, Rectangle clip, Map<Widget, Boolean> visited,
		Function<Widget, Rectangle> geometry)
	{
		if (widget == null || widget.isHidden() || visited.put(widget, true) != null) { return new Rectangle(); }
		Rectangle area = geometry.apply(widget);
		if (area == null || area.isEmpty()) { return new Rectangle(); }
		if (clip != null) { area = area.intersection(clip); }
		if (area.isEmpty()) { return new Rectangle(); }
		if (widget.getType() != WidgetType.LAYER) { return area; }
		// Bank/GE universe layers can cover the whole viewport despite drawing
		// a much smaller window. Use visible descendants, clipped at scroll layers.
		Rectangle result = new Rectangle();
		include(widget.getStaticChildren(), area, visited, result, geometry);
		include(widget.getDynamicChildren(), area, visited, result, geometry);
		include(widget.getNestedChildren(), area, visited, result, geometry);
		return result;
	}

	private static void include(Widget[] children, Rectangle clip, Map<Widget, Boolean> visited, Rectangle result,
		Function<Widget, Rectangle> geometry)
	{
		if (children == null) { return; }
		for (Widget child : children)
		{
			Rectangle area = visibleBounds(child, clip, visited, geometry);
			if (!area.isEmpty())
			{
				if (result.isEmpty()) { result.setBounds(area); }
				else { result.add(area); }
			}
		}
	}

	boolean rectangularInput()
	{
		// Empty inventory slots and classic frame controls stay interactive.
		return this == SIDE_PANEL || this == CHAT || interfaceWindow();
	}

	boolean interfaceWindow() { return this == INTERFACE || this == INTERFACE_CRM || this == INTERFACE_FLOAT; }
}
