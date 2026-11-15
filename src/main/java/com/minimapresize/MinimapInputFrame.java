package com.minimapresize;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

/** A published frame owns its hit mask; the renderer never mutates it afterward. */
final class MinimapInputFrame
{
	private final MinimapTransform transform;
	private final WidgetRegion region;
	private final boolean[] occupied;
	private final Rectangle source;
	private final boolean[] supplement;
	private final long created = System.nanoTime();

	MinimapInputFrame(MinimapTransform transform, BufferedImage foreground)
	{
		this(WidgetRegion.MINIMAP, transform, foreground);
	}

	MinimapInputFrame(WidgetRegion region, MinimapTransform transform, BufferedImage foreground)
	{
		this(region, transform, foreground, null, true);
	}

	MinimapInputFrame(WidgetRegion region, MinimapTransform transform, BufferedImage foreground,
		MinimapInputFrame previous, boolean maskChanged)
	{
		this(region, transform, foreground, previous, maskChanged, null);
	}

	MinimapInputFrame(WidgetRegion region, MinimapTransform transform, BufferedImage foreground,
		MinimapInputFrame previous, boolean maskChanged, boolean[] supplement)
	{
		this.supplement = supplement;
		this.region = region;
		this.transform = transform;
		source = transform.source();
		if (region.rectangularInput())
		{
			occupied = null;
			return;
		}
		if (!maskChanged && previous != null && previous.region == region && previous.supplement == supplement
			&& previous.source.width == source.width && previous.source.height == source.height)
		{
			occupied = previous.occupied;
			return;
		}
		occupied = new boolean[source.width * source.height];
		int[] pixels = PackedPixels.alphaPixels(foreground);
		for (int i = 0; i < occupied.length; i++)
		{
			occupied[i] = (pixels[i] >>> 24) != 0 || supplement != null && i < supplement.length && supplement[i];
		}
	}

	boolean isFresh()
	{
		return System.nanoTime() - created < 500_000_000L;
	}

	Point translate(Point point)
	{
		return hit(point) ? transform.toSource(point) : point;
	}

	boolean hit(Point point)
	{
		return transform.containsDestination(point) && (region.rectangularInput() || occupied(transform.toSource(point)));
	}

	WidgetRegion region()
	{
		return region;
	}

	boolean containsSource(Point point) { return source.contains(point); }

	Point translateDrag(Point point)
	{
		// Drag deltas must use the captured control's transform even outside its bounds.
		return transform.toSource(point);
	}

	private boolean occupied(Point point)
	{
		int x = point.x - source.x;
		int y = point.y - source.y;
		return occupied[y * source.width + x];
	}
}
