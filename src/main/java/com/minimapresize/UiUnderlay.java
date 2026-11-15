package com.minimapresize;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;

/** Keeps scene labels/overheads out of GPU widget captures, including opaque text. */
final class UiUnderlay
{
	private BufferedImage frame;
	private PackedPixels packed;
	private int[] background;
	private int[] starts;
	private int[] ends;
	private int[] transparent;
	private boolean[] occupiedRows;
	private int width;

	void captureAndClear(BufferedImage image, List<Rectangle> regions)
	{
		invalidate();
		if (regions.isEmpty()) { return; }
		PackedPixels pixels = PackedPixels.nativePixels(image);
		if (pixels == null) { return; }
		int height = image.getHeight();
		if (background == null || width != image.getWidth() || starts.length != height)
		{
			width = image.getWidth();
			background = new int[width * height];
			starts = new int[height];
			ends = new int[height];
			transparent = new int[width];
			occupiedRows = new boolean[height];
		}
		Arrays.fill(starts, width);
		Arrays.fill(ends, 0);
		for (Rectangle region : regions)
		{
			for (int y = Math.max(0, region.y); y < Math.min(height, region.y + region.height); y++)
			{
				starts[y] = Math.max(0, Math.min(starts[y], region.x));
				ends[y] = Math.min(width, Math.max(ends[y], region.x + region.width));
			}
		}
		// One span per row also handles overlapping captures without clearing or
		// restoring their shared backdrop twice. Buffers are reused across frames.
		for (int y = 0; y < height; y++)
		{
			int length = ends[y] - starts[y];
			occupiedRows[y] = false;
			if (length <= 0) { continue; }
			int index = pixels.index(starts[y], y);
			if (Arrays.equals(pixels.pixels, index, index + length, transparent, 0, length)) { continue; }
			occupiedRows[y] = true;
			System.arraycopy(pixels.pixels, index, background, y * width + starts[y], length);
			Arrays.fill(pixels.pixels, index, index + length, 0);
		}
		frame = image;
		packed = pixels;
	}

	void restore(BufferedImage image)
	{
		if (frame == null || image != frame) { invalidate(); return; }
		for (int y = 0; y < starts.length; y++)
		{
			if (!occupiedRows[y]) { continue; }
			restoreSpan(y, starts[y], ends[y]);
		}
		invalidate();
	}

	/** Replace an unfinished interface draw with precisely the saved pre-interface pixels. */
	void restoreOriginal(BufferedImage image)
	{
		if (frame == null || image != frame) { invalidate(); return; }
		for (int y = 0; y < starts.length; y++)
		{
			int length = ends[y] - starts[y];
			if (length <= 0) { continue; }
			int dst = packed.index(starts[y], y);
			if (occupiedRows[y]) { System.arraycopy(background, y * width + starts[y], packed.pixels, dst, length); }
			else { Arrays.fill(packed.pixels, dst, dst + length, 0); }
		}
		invalidate();
	}

	/** Restore the rest of the host while HQ finishes drawing inside the window. */
	void restoreOutside(BufferedImage image, Rectangle keep)
	{
		if (frame == null || image != frame) { invalidate(); return; }
		for (int y = 0; y < starts.length; y++)
		{
			if (!occupiedRows[y]) { continue; }
			if (y < keep.y || y >= keep.y + keep.height)
			{
				restoreSpan(y, starts[y], ends[y]);
				occupiedRows[y] = false;
				continue;
			}
			restoreSpan(y, starts[y], Math.min(ends[y], keep.x));
			restoreSpan(y, Math.max(starts[y], keep.x + keep.width), ends[y]);
			starts[y] = Math.max(starts[y], keep.x);
			ends[y] = Math.min(ends[y], keep.x + keep.width);
			occupiedRows[y] = ends[y] > starts[y];
		}
	}

	private void restoreSpan(int y, int start, int end)
	{
		if (end <= start) { return; }
		int dst = packed.index(start, y), src = y * width + start, length = end - start;
		if (Arrays.equals(packed.pixels, dst, dst + length, transparent, 0, length))
		{
			System.arraycopy(background, src, packed.pixels, dst, length);
			return;
		}
		for (int x = start; x < end; x++, dst++, src++)
		{
			packed.pixels[dst] = UiAlpha.over(packed.pixels[dst], background[src]);
		}
	}

	void invalidate()
	{
		frame = null;
		packed = null;
	}

	void clear()
	{
		invalidate();
		background = null;
		starts = null;
		ends = null;
		transparent = null;
		occupiedRows = null;
	}
}
