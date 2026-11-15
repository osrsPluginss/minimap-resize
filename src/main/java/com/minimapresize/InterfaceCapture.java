package com.minimapresize;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Collections;

/** Snapshot the native host before its attached group; choose the exact cutout after it draws. */
final class InterfaceCapture
{
	private final UiUnderlay underlay = new UiUnderlay();
	private BufferedImage frame;
	private Rectangle area;
	private int[] softwareBackground;
	private boolean gpu;

	void begin(BufferedImage image, Rectangle host, boolean gpu)
	{
		restore(image);
		area = host.intersection(new Rectangle(image.getWidth(), image.getHeight()));
		if (area.isEmpty()) { return; }
		this.gpu = gpu && PackedPixels.nativePixels(image) != null;
		frame = image;
		if (this.gpu) { underlay.captureAndClear(image, Collections.singletonList(area)); }
		else
		{
			int length = area.width * area.height;
			if (softwareBackground == null || softwareBackground.length < length) { softwareBackground = new int[length]; }
			PackedPixels pixels = PackedPixels.of(image);
			if (pixels == null) { image.getRGB(area.x, area.y, area.width, area.height, softwareBackground, 0, area.width); }
			else
			{
				for (int y = 0; y < area.height; y++)
				{
					System.arraycopy(pixels.pixels, pixels.index(area.x, area.y + y), softwareBackground, y * area.width, area.width);
				}
			}
		}
	}

	Rectangle visibleArea(BufferedImage image, Rectangle bounds)
	{
		return frame == image && area != null ? bounds.intersection(area) : new Rectangle();
	}

	void prepareLatePixels(BufferedImage image, Rectangle bounds)
	{
		if (frame == image && gpu) { underlay.restoreOutside(image, bounds); }
	}

	BufferedImage extract(BufferedImage image, Rectangle bounds, MinimapCompositor compositor)
	{
		if (frame != image || area == null || !area.contains(bounds) || bounds.isEmpty()) { return null; }
		try
		{
			int offset = (bounds.y - area.y) * area.width + bounds.x - area.x;
			compositor.captureBackground(image, bounds, gpu, gpu ? null : softwareBackground, offset, area.width);
			BufferedImage result = compositor.extractAndRestore(null);
			return compositor.hasVisiblePixels() ? result : null;
		}
		finally { restore(image); }
	}

	void restore(BufferedImage image)
	{
		if (gpu) { underlay.restore(image); }
		frame = null;
	}

	/** Keep the scene visible for an opening frame whose widget geometry is not ready. */
	void discard(BufferedImage image)
	{
		if (frame != image || area == null) { return; }
		if (gpu) { underlay.restoreOriginal(image); }
		else
		{
			PackedPixels pixels = PackedPixels.of(image);
			if (pixels == null) { image.setRGB(area.x, area.y, area.width, area.height, softwareBackground, 0, area.width); }
			else
			{
				for (int y = 0; y < area.height; y++)
				{
					System.arraycopy(softwareBackground, y * area.width, pixels.pixels, pixels.index(area.x, area.y + y), area.width);
				}
			}
		}
		frame = null;
	}

	void invalidate() { frame = null; underlay.invalidate(); }
	void clear() { invalidate(); underlay.clear(); softwareBackground = null; area = null; }
}
