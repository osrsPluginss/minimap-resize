package com.minimapresize;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;

/** Cached alpha runs: opaque UI copies in bulk, padding is skipped, only translucent runs blend. */
final class CachedUiBlitter
{
	private BufferedImage image;
	private int[][] runs;
	private int[] zeros;
	private boolean[] dirty;

	void draw(BufferedImage target, BufferedImage source, int x, int y, Graphics2D graphics,
		boolean gpu, boolean changed)
	{
		PackedPixels dst = gpu && target != null ? PackedPixels.nativePixels(target) : null;
		PackedPixels src = source.getType() == BufferedImage.TYPE_INT_ARGB_PRE ? PackedPixels.nativePixels(source) : null;
		if (dst == null || src == null) { UiBlitter.draw(target, source, x, y, graphics, gpu); return; }
		if (image != source || changed)
		{
			if (image != source)
			{
				image = source;
				runs = new int[source.getHeight()][];
				zeros = new int[source.getWidth()];
				dirty = new boolean[source.getHeight()];
			}
			Arrays.fill(dirty, true);
		}
		int left = Math.max(0, -x), right = Math.min(source.getWidth(), target.getWidth() - x);
		for (int row = Math.max(0, -y); row < Math.min(source.getHeight(), target.getHeight() - y); row++)
		{
			if (right <= left) { break; }
			int from = src.index(left, row), to = dst.index(x + left, y + row);
			// Most GPU UI is drawn over an empty UI buffer, even over a complex 3D scene.
			if (Arrays.equals(dst.pixels, to, to + right - left, zeros, 0, right - left))
			{
				System.arraycopy(src.pixels, from, dst.pixels, to, right - left);
				continue;
			}
			// Build runs only when this row actually overlaps existing UI pixels.
			if (dirty[row]) { classify(src, row, source.getWidth()); }
			int[] rowRuns = runs[row];
			for (int i = 0; rowRuns[i] >= 0; i += 3)
			{
				int start = Math.max(left, rowRuns[i]), end = Math.min(right, rowRuns[i + 1]);
				if (end <= start) { continue; }
				int s = src.index(start, row), d = dst.index(x + start, y + row);
				if (rowRuns[i + 2] == 255) { System.arraycopy(src.pixels, s, dst.pixels, d, end - start); }
				else
				{
					for (int col = start; col < end; col++, s++, d++) { dst.pixels[d] = UiAlpha.over(src.pixels[s], dst.pixels[d]); }
				}
			}
		}
	}

	private void classify(PackedPixels src, int row, int width)
	{
		int base = src.index(0, row), count = 0;
		int[] rowRuns = runs[row];
		if (rowRuns == null) { rowRuns = new int[16]; }
		for (int col = 0; col < width;)
		{
			int start = col, kind = alphaKind(src.pixels[base + col]);
			do { col++; }
			while (col < width && alphaKind(src.pixels[base + col]) == kind);
			if (kind == 0) { continue; }
			if (count + 4 > rowRuns.length) { rowRuns = Arrays.copyOf(rowRuns, rowRuns.length * 2); }
			rowRuns[count++] = start;
			rowRuns[count++] = col;
			rowRuns[count++] = kind;
		}
		rowRuns[count] = -1;
		runs[row] = rowRuns;
		dirty[row] = false;
	}

	private static int alphaKind(int pixel)
	{
		int alpha = pixel >>> 24;
		return alpha == 0 ? 0 : alpha == 255 ? 255 : 1;
	}

	void clear() { image = null; runs = null; zeros = null; dirty = null; }
}
