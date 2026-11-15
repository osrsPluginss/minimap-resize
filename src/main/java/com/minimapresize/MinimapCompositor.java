package com.minimapresize;

import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;

/** Captures a native layer and restores its background using reusable int arrays. */
final class MinimapCompositor
{
	private BufferedImage frame;
	private BufferedImage lastImage;
	private PackedPixels packed;
	private Rectangle source;
	private int[] background;
	private int[] fallbackPixels;
	private BufferedImage foreground;
	private Shape coverageShape;
	private Rectangle coverageSource;
	private boolean[] coverage;
	private boolean changed;
	private boolean maskChanged;
	private boolean nativePremultiplied;
	private boolean foregroundReset;
	private int[] transparentRow;
	private boolean[] emptyBackgroundRows;

	void capture(BufferedImage frame, Rectangle source)
	{
		capture(frame, source, false);
	}

	void capture(BufferedImage frame, Rectangle source, boolean nativePremultiplied)
	{
		capture(frame, source, nativePremultiplied, false, null, 0, 0);
	}

	void captureBackground(BufferedImage frame, Rectangle source, boolean nativePremultiplied,
		int[] backdrop, int offset, int stride)
	{
		capture(frame, source, nativePremultiplied, true, backdrop, offset, stride);
	}

	private void capture(BufferedImage frame, Rectangle source, boolean nativePremultiplied,
		boolean supplied, int[] backdrop, int offset, int stride)
	{
		this.frame = frame;
		this.source = new Rectangle(source);
		if (lastImage != frame || this.nativePremultiplied != nativePremultiplied)
		{
			lastImage = frame;
			packed = nativePremultiplied ? PackedPixels.nativePixels(frame) : PackedPixels.of(frame);
		}
		this.nativePremultiplied = nativePremultiplied && packed != null;
		if (this.nativePremultiplied && (transparentRow == null || transparentRow.length != source.width
			|| emptyBackgroundRows.length != source.height))
		{
			transparentRow = new int[source.width];
			emptyBackgroundRows = new boolean[source.height];
		}
		int length = source.width * source.height;
		if (background == null || background.length != length) { background = new int[length]; }
		int type = this.nativePremultiplied ? BufferedImage.TYPE_INT_ARGB_PRE : BufferedImage.TYPE_INT_ARGB;
		if (foreground == null || foreground.getWidth() != source.width || foreground.getHeight() != source.height || foreground.getType() != type)
		{
			foreground = new BufferedImage(source.width, source.height, type);
			foregroundReset = true;
		}
		if (supplied)
		{
			if (backdrop == null) { Arrays.fill(background, 0); }
			else
			{
				for (int y = 0; y < source.height; y++)
				{
					System.arraycopy(backdrop, offset + y * stride, background, y * source.width, source.width);
				}
			}
		}
		else if (packed == null)
		{
			frame.getRGB(source.x, source.y, source.width, source.height, background, 0, source.width);
		}
		else
		{
			for (int y = 0; y < source.height; y++)
			{
				System.arraycopy(packed.pixels, packed.index(source.x, source.y + y), background, y * source.width, source.width);
			}
		}
		if (this.nativePremultiplied)
		{
			for (int y = 0; y < source.height; y++)
			{
				emptyBackgroundRows[y] = Arrays.equals(background, y * source.width, (y + 1) * source.width,
					transparentRow, 0, source.width);
			}
		}
	}

	boolean matches(BufferedImage frame, Rectangle source)
	{
		return this.frame == frame && source.equals(this.source);
	}

	BufferedImage extractAndRestore(Shape opaqueMapInterior)
	{
		updateCoverage(opaqueMapInterior);
		int[] pixels = ((DataBufferInt) foreground.getRaster().getDataBuffer()).getData();
		if (packed == null)
		{
			if (fallbackPixels == null || fallbackPixels.length != pixels.length) { fallbackPixels = new int[pixels.length]; }
			frame.getRGB(source.x, source.y, source.width, source.height, fallbackPixels, 0, source.width);
		}
		changed = foregroundReset;
		maskChanged = foregroundReset;
		for (int y = 0, i = 0; y < source.height; y++)
		{
			int row = packed == null ? 0 : packed.index(source.x, source.y + y);
			if (nativePremultiplied && emptyBackgroundRows[y])
			{
				// Isolated GPU UI has no backdrop to unblend. Most rows are identical
				// between frames (or empty overhead rows); use vectorized comparisons.
				if (!Arrays.equals(packed.pixels, row, row + source.width, pixels, i, i + source.width))
				{
					changed = true;
					if (!maskChanged)
					{
						for (int x = 0; x < source.width; x++)
						{
							if ((packed.pixels[row + x] >>> 24 != 0) != (pixels[i + x] >>> 24 != 0))
							{
								maskChanged = true;
								break;
							}
						}
					}
					System.arraycopy(packed.pixels, row, pixels, i, source.width);
				}
				Arrays.fill(packed.pixels, row, row + source.width, 0);
				i += source.width;
				continue;
			}
			for (int x = 0; x < source.width; x++, i++)
			{
				int raw = packed == null ? fallbackPixels[i] : packed.pixels[row + x];
				int difference = raw ^ background[i];
				boolean same = !nativePremultiplied && packed != null && packed.opaque ? (difference & 0xffffff) == 0 : difference == 0;
				int pixel = same && (coverage == null || !coverage[i]) ? 0
					: nativePremultiplied ? UiAlpha.extract(raw, background[i])
					: packed != null && packed.opaque ? raw | 0xff000000 : raw;
				int previous = pixels[i];
				changed |= pixel != previous;
				maskChanged |= (pixel >>> 24 != 0) != (previous >>> 24 != 0);
				pixels[i] = pixel;
			}
			if (packed != null)
			{
				System.arraycopy(background, y * source.width, packed.pixels, row, source.width);
			}
		}
		if (packed == null)
		{
			frame.setRGB(source.x, source.y, source.width, source.height, background, 0, source.width);
		}
		frame = null;
		foregroundReset = false;
		return foreground;
	}

	private void updateCoverage(Shape shape)
	{
		if (shape == null)
		{
			coverage = null;
			coverageShape = null;
			return;
		}
		if (shape.equals(coverageShape) && source.equals(coverageSource)) { return; }
		coverageShape = shape;
		coverageSource = new Rectangle(source);
		coverage = new boolean[source.width * source.height];
		for (int y = 0, i = 0; y < source.height; y++)
		{
			for (int x = 0; x < source.width; x++, i++)
			{
				coverage[i] = shape.contains(source.x + x + 0.5, source.y + y + 0.5);
			}
		}
	}

	boolean changed() { return changed; }
	boolean maskChanged() { return maskChanged; }
	boolean hasVisiblePixels()
	{
		if (foreground == null) { return false; }
		for (int pixel : ((DataBufferInt) foreground.getRaster().getDataBuffer()).getData())
		{
			if (pixel >>> 24 != 0) { return true; }
		}
		return false;
	}
	void invalidate() { frame = null; }

	void clear()
	{
		frame = null;
		lastImage = null;
		packed = null;
		source = null;
		background = null;
		fallbackPixels = null;
		foreground = null;
		coverage = null;
		coverageShape = null;
		coverageSource = null;
		transparentRow = null;
		emptyBackgroundRows = null;
	}
}
