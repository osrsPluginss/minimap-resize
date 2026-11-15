package com.minimapresize;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;

/** Cached, separable fixed-point resampling; avoids Java2D's expensive per-frame transform path. */
final class WidgetImageScaler
{
	private static final int ONE = 4096;
	// This is the first of two possible scaling passes. Leave most edge smoothing
	// to the renderer instead of supplying already-softened pixel boundaries.
	private static final double HYBRID_TRANSITION_WIDTH = 0.5;
	private static final double HYBRID_BLEND = 0.5;
	private BufferedImage scaled;
	private BufferedImage sharpened;
	private BufferedImage lastSource;
	private BufferedImage lastResult;
	private ScalingFilter lastFilter;
	private int lastStrength;
	private int sourceWidth;
	private int sourceHeight;
	private ScalingFilter plannedFilter;
	private XbrImageScaler xbr;
	private int[] premultiplied;
	private int[] horizontal;
	private int[] blurHorizontal;
	private Axis xAxis;
	private Axis yAxis;
	private long lastScaledNanos;
	private boolean pendingChange;
	private boolean updated;
	private boolean invalidated;
	private boolean[] changedRows;
	private boolean[] outputRows;
	private int[] changedStarts;
	private int[] changedEnds;

	private static final class Axis
	{
		final int taps;
		final int[] positions;
		final int[] weights;

		Axis(int source, int destination, ScalingFilter filter)
		{
			double footprint = filter == ScalingFilter.LANCZOS ? Math.max(1.0, (double) source / destination) : 1;
			taps = filter == ScalingFilter.NEAREST ? 1
				: filter == ScalingFilter.BILINEAR || filter == ScalingFilter.HYBRID ? 2
				: filter == ScalingFilter.LANCZOS ? 2 * (int) Math.ceil(3 * footprint) : 4;
			positions = new int[destination * taps];
			weights = new int[positions.length];
			double[] samples = new double[taps];
			for (int i = 0; i < destination; i++)
			{
				double coordinate = (i + 0.5) * source / destination - 0.5;
				if (filter == ScalingFilter.HYBRID && destination > source)
				{
					// Hybrid mapping adapted from Casey Zwicker's 2025 CC0 RuneLite shader.
					// Narrow the transition and favour the containing source pixel. Width
					// alone cannot fix 150% scaling: samples exactly on a source boundary
					// would still become 50/50 blends, then be blended again by the renderer.
					double edge = coordinate + 0.5;
					double fraction = edge - Math.floor(edge);
					double scale = (double) destination / source / HYBRID_TRANSITION_WIDTH;
					coordinate = Math.floor(edge) + HYBRID_BLEND * (Math.min(fraction * scale, 0.5)
						- Math.min((1 - fraction) * scale, 0.5));
				}
				int floor = (int) Math.floor(coordinate);
				double sum = 0;
				for (int j = 0; j < taps; j++)
				{
					int sample = taps == 1 ? (int) Math.floor(coordinate + 0.5) : floor + j - (taps / 2 - 1);
					samples[j] = taps == 1 ? 1 : taps == 2 ? (j == 0 ? 1 - (coordinate - floor) : coordinate - floor)
						: filter == ScalingFilter.LANCZOS ? lanczos((coordinate - sample) / footprint)
						: cubic(coordinate - sample, filter == ScalingFilter.MITCHELL);
					positions[i * taps + j] = Math.max(0, Math.min(source - 1, sample));
					sum += samples[j];
				}
				int total = 0;
				int strongest = 0;
				for (int j = 0; j < taps; j++)
				{
					int weight = (int) Math.round(samples[j] / sum * ONE);
					weights[i * taps + j] = weight;
					total += weight;
					if (samples[j] > samples[strongest]) { strongest = j; }
				}
				// Preserve flat colours exactly; put rounding correction on the main tap.
				weights[i * taps + strongest] += ONE - total;
			}
		}

		private static double cubic(double distance, boolean mitchell)
		{
			double x = Math.abs(distance);
			if (mitchell)
			{
				return x <= 1 ? ((7 * x - 12) * x * x + 16.0 / 3) / 6
					: x < 2 ? (((-7.0 / 3 * x + 12) * x - 20) * x + 32.0 / 3) / 6 : 0;
			}
			return x <= 1 ? (1.5 * x - 2.5) * x * x + 1 : x < 2 ? ((-0.5 * x + 2.5) * x - 4) * x + 2 : 0;
		}

		private static double lanczos(double distance)
		{
			double x = Math.abs(distance);
			if (x < 1e-8) { return 1; }
			if (x >= 3) { return 0; }
			double piX = Math.PI * x;
			return Math.sin(piX) * Math.sin(piX / 3) / (piX * piX / 3);
		}
	}

	BufferedImage scale(BufferedImage source, int width, int height, ScalingFilter filter, int sharpening)
	{
		return scale(source, width, height, filter, sharpening, true);
	}

	BufferedImage scale(BufferedImage source, int width, int height, ScalingFilter filter, int sharpening, boolean pixelsChanged)
	{
		return scale(source, width, height, filter, sharpening, pixelsChanged, 0);
	}

	BufferedImage scale(BufferedImage source, int width, int height, ScalingFilter filter, int sharpening,
		boolean pixelsChanged, int refreshLimit)
	{
		updated = true;
		if (width <= 0 || height <= 0) { throw new IllegalArgumentException("Scaled image dimensions must be positive"); }
		if (width == source.getWidth() && height == source.getHeight())
		{
			updated = pixelsChanged || invalidated || lastSource != source || lastResult != source;
			lastSource = source;
			lastResult = source;
			lastFilter = filter;
			lastStrength = 0;
			pendingChange = false;
			invalidated = false;
			return source;
		}
		int strength = filter == ScalingFilter.SHARP_BICUBIC ? Math.max(0, Math.min(100, sharpening)) : 0;
		pendingChange |= pixelsChanged;
		long now = System.nanoTime();
		boolean reusable = lastSource == source && lastFilter == filter && lastStrength == strength
			&& lastResult != null && lastResult.getWidth() == width && lastResult.getHeight() == height;
		if (reusable)
		{
			if (!invalidated && (!pendingChange || (refreshLimit > 0 && now - lastScaledNanos < 1_000_000_000L / Math.min(240, refreshLimit))))
			{
				updated = false;
				return lastResult;
			}
		}
		// xBR is an enlargement filter. Use Catmull-Rom if either axis is reduced.
		ScalingFilter sampling = filter == ScalingFilter.SHARP_BICUBIC
			|| (filter == ScalingFilter.XBR && (width < source.getWidth() || height < source.getHeight()))
			? ScalingFilter.BICUBIC : filter;
		if (scaled == null || scaled.getWidth() != width || scaled.getHeight() != height
			|| sourceWidth != source.getWidth() || sourceHeight != source.getHeight() || plannedFilter != sampling)
		{
			scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
			sharpened = null;
			blurHorizontal = null;
			sourceWidth = source.getWidth();
			sourceHeight = source.getHeight();
			plannedFilter = sampling;
			xAxis = sampling == ScalingFilter.XBR ? null : new Axis(sourceWidth, width, sampling);
			yAxis = sampling == ScalingFilter.XBR ? null : new Axis(sourceHeight, height, sampling);
			premultiplied = new int[sourceWidth * sourceHeight];
			changedRows = new boolean[sourceHeight];
			outputRows = new boolean[height];
			changedStarts = sampling == ScalingFilter.XBR ? new int[sourceHeight] : null;
			changedEnds = sampling == ScalingFilter.XBR ? new int[sourceHeight] : null;
			horizontal = sampling == ScalingFilter.NEAREST || sampling == ScalingFilter.XBR ? null : new int[width * sourceHeight * 4];
		}
		premultiply(source, !reusable);
		if (sampling == ScalingFilter.XBR)
		{
			if (xbr == null) { xbr = new XbrImageScaler(); }
			xbr.scale(premultiplied, sourceWidth, sourceHeight, data(scaled), width, height, changedRows, changedStarts, changedEnds);
		}
		else { resample(width, height); }
		if (strength != 0)
		{
			if (sharpened == null)
			{
				sharpened = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
				blurHorizontal = new int[width * height * 4];
			}
			sharpen(strength / 100.0);
		}
		lastSource = source;
		lastFilter = filter;
		lastStrength = strength;
		lastResult = strength == 0 ? scaled : sharpened;
		lastScaledNanos = now;
		pendingChange = false;
		invalidated = false;
		return lastResult;
	}

	boolean updated() { return updated; }

	/** One completed interactive update bypasses background throttling; skipped captures do not consume it. */
	void invalidate() { invalidated = true; }

	private void premultiply(BufferedImage source, boolean force)
	{
		if (changedStarts != null)
		{
			Arrays.fill(changedStarts, force ? 0 : sourceWidth);
			Arrays.fill(changedEnds, force ? sourceWidth : 0);
		}
		if (source.getType() == BufferedImage.TYPE_INT_ARGB_PRE)
		{
			PackedPixels pixels = PackedPixels.nativePixels(source);
			for (int y = 0; y < sourceHeight; y++)
			{
				int src = pixels.index(0, y), dst = y * sourceWidth;
				if (changedStarts != null && !force)
				{
					int first = Arrays.mismatch(pixels.pixels, src, src + sourceWidth, premultiplied, dst, dst + sourceWidth);
					changedRows[y] = first >= 0;
					if (first < 0) { continue; }
					int end = sourceWidth;
					while (end > first + 1 && pixels.pixels[src + end - 1] == premultiplied[dst + end - 1]) { end--; }
					changedStarts[y] = first;
					changedEnds[y] = end;
					System.arraycopy(pixels.pixels, src + first, premultiplied, dst + first, end - first);
					continue;
				}
				changedRows[y] = force || !Arrays.equals(pixels.pixels, src, src + sourceWidth,
					premultiplied, dst, dst + sourceWidth);
				if (changedRows[y]) { System.arraycopy(pixels.pixels, src, premultiplied, dst, sourceWidth); }
			}
			return;
		}
		int[] pixels = PackedPixels.straightArgb(source);
		Arrays.fill(changedRows, force);
		for (int i = 0; i < pixels.length; i++)
		{
			int pixel = pixels[i];
			int alpha = pixel >>> 24;
			int converted = alpha == 255 ? pixel : alpha == 0 ? 0 : (alpha << 24)
				| ((((pixel >>> 16) & 255) * alpha + 127) / 255 << 16)
				| ((((pixel >>> 8) & 255) * alpha + 127) / 255 << 8)
				| ((pixel & 255) * alpha + 127) / 255;
			if (premultiplied[i] != converted)
			{
				int row = i / sourceWidth;
				changedRows[row] = true;
				if (changedStarts != null)
				{
					int x = i % sourceWidth;
					changedStarts[row] = Math.min(changedStarts[row], x);
					changedEnds[row] = Math.max(changedEnds[row], x + 1);
				}
			}
			premultiplied[i] = converted;
		}
	}

	private void resample(int width, int height)
	{
		int[] output = data(scaled);
		if (xAxis.taps == 1)
		{
			for (int y = 0, i = 0; y < height; y++)
			{
				if (!changedRows[yAxis.positions[y]]) { i += width; continue; }
				int row = yAxis.positions[y] * sourceWidth;
				for (int x = 0; x < width; x++, i++) { output[i] = premultiplied[row + xAxis.positions[x]]; }
			}
			return;
		}
		for (int y = 0, i = 0; y < sourceHeight; y++)
		{
			if (!changedRows[y]) { i += width * 4; continue; }
			int row = y * sourceWidth;
			for (int x = 0; x < width; x++, i += 4)
			{
				int axis = x * xAxis.taps;
				if (xAxis.taps <= 4)
				{
					// Keep the hot two/four-tap path unrolled; Lanczos needs the wider loop below.
					int p0 = premultiplied[row + xAxis.positions[axis]], w0 = xAxis.weights[axis];
					int p1 = premultiplied[row + xAxis.positions[axis + 1]], w1 = xAxis.weights[axis + 1];
					int p2 = xAxis.taps == 4 ? premultiplied[row + xAxis.positions[axis + 2]] : 0;
					int p3 = xAxis.taps == 4 ? premultiplied[row + xAxis.positions[axis + 3]] : 0;
					int w2 = xAxis.taps == 4 ? xAxis.weights[axis + 2] : 0, w3 = xAxis.taps == 4 ? xAxis.weights[axis + 3] : 0;
					horizontal[i] = ((p0 >>> 24) * w0 + (p1 >>> 24) * w1 + (p2 >>> 24) * w2 + (p3 >>> 24) * w3 + ONE / 2) >> 12;
					horizontal[i + 1] = (((p0 >>> 16) & 255) * w0 + ((p1 >>> 16) & 255) * w1 + ((p2 >>> 16) & 255) * w2 + ((p3 >>> 16) & 255) * w3 + ONE / 2) >> 12;
					horizontal[i + 2] = (((p0 >>> 8) & 255) * w0 + ((p1 >>> 8) & 255) * w1 + ((p2 >>> 8) & 255) * w2 + ((p3 >>> 8) & 255) * w3 + ONE / 2) >> 12;
					horizontal[i + 3] = ((p0 & 255) * w0 + (p1 & 255) * w1 + (p2 & 255) * w2 + (p3 & 255) * w3 + ONE / 2) >> 12;
					continue;
				}
				int a = 0, r = 0, g = 0, b = 0;
				for (int j = 0; j < xAxis.taps; j++)
				{
					int pixel = premultiplied[row + xAxis.positions[axis + j]], weight = xAxis.weights[axis + j];
					a += (pixel >>> 24) * weight;
					r += ((pixel >>> 16) & 255) * weight;
					g += ((pixel >>> 8) & 255) * weight;
					b += (pixel & 255) * weight;
				}
				horizontal[i] = (a + ONE / 2) >> 12;
				horizontal[i + 1] = (r + ONE / 2) >> 12;
				horizontal[i + 2] = (g + ONE / 2) >> 12;
				horizontal[i + 3] = (b + ONE / 2) >> 12;
			}
		}
		for (int y = 0, i = 0; y < height; y++)
		{
			int axis = y * yAxis.taps;
			boolean dirty = false;
			for (int tap = 0; tap < yAxis.taps; tap++) { dirty |= changedRows[yAxis.positions[axis + tap]]; }
			outputRows[y] = dirty;
			if (!dirty) { i += width; continue; }
			int row0 = yAxis.positions[axis] * width * 4, w0 = yAxis.weights[axis];
			int row1 = yAxis.positions[axis + 1] * width * 4, w1 = yAxis.weights[axis + 1];
			int row2 = yAxis.taps >= 4 ? yAxis.positions[axis + 2] * width * 4 : row0;
			int row3 = yAxis.taps >= 4 ? yAxis.positions[axis + 3] * width * 4 : row0;
			int w2 = yAxis.taps >= 4 ? yAxis.weights[axis + 2] : 0, w3 = yAxis.taps >= 4 ? yAxis.weights[axis + 3] : 0;
			for (int x = 0; x < width; x++, i++)
			{
				int offset = x * 4;
				if (yAxis.taps <= 4)
				{
					int alpha = clamp(weighted(row0, row1, row2, row3, offset, w0, w1, w2, w3), 255);
					output[i] = (alpha << 24) | (clamp(weighted(row0, row1, row2, row3, offset + 1, w0, w1, w2, w3), alpha) << 16)
						| (clamp(weighted(row0, row1, row2, row3, offset + 2, w0, w1, w2, w3), alpha) << 8)
						| clamp(weighted(row0, row1, row2, row3, offset + 3, w0, w1, w2, w3), alpha);
					continue;
				}
				int a = 0, r = 0, g = 0, b = 0;
				for (int j = 0; j < yAxis.taps; j++)
				{
					int sample = yAxis.positions[axis + j] * width * 4 + offset, weight = yAxis.weights[axis + j];
					a += horizontal[sample] * weight;
					r += horizontal[sample + 1] * weight;
					g += horizontal[sample + 2] * weight;
					b += horizontal[sample + 3] * weight;
				}
				int alpha = clamp((a + ONE / 2) >> 12, 255);
				output[i] = (alpha << 24) | (clamp((r + ONE / 2) >> 12, alpha) << 16)
					| (clamp((g + ONE / 2) >> 12, alpha) << 8) | clamp((b + ONE / 2) >> 12, alpha);
			}
		}
	}

	private int weighted(int row0, int row1, int row2, int row3, int offset, int w0, int w1, int w2, int w3)
	{
		return (horizontal[row0 + offset] * w0 + horizontal[row1 + offset] * w1
			+ horizontal[row2 + offset] * w2 + horizontal[row3 + offset] * w3 + ONE / 2) >> 12;
	}

	private void sharpen(double amount)
	{
		int width = scaled.getWidth();
		int height = scaled.getHeight();
		int[] input = data(scaled);
		int[] output = data(sharpened);
		// Separable [1,2,1] Gaussian: six samples instead of nine, preserving alpha-aware weights.
		for (int y = 0, i = 0; y < height; y++)
		{
			if (!outputRows[y]) { i += width * 4; continue; }
			int row = y * width;
			for (int x = 0; x < width; x++, i += 4)
			{
				int left = input[row + Math.max(0, x - 1)];
				int center = input[row + x];
				int right = input[row + Math.min(width - 1, x + 1)];
				blurHorizontal[i] = (left >>> 24) + 2 * (center >>> 24) + (right >>> 24);
				blurHorizontal[i + 1] = ((left >>> 16) & 255) + 2 * ((center >>> 16) & 255) + ((right >>> 16) & 255);
				blurHorizontal[i + 2] = ((left >>> 8) & 255) + 2 * ((center >>> 8) & 255) + ((right >>> 8) & 255);
				blurHorizontal[i + 3] = (left & 255) + 2 * (center & 255) + (right & 255);
			}
		}
		for (int y = 0, i = 0; y < height; y++)
		{
			if (!outputRows[Math.max(0, y - 1)] && !outputRows[y] && !outputRows[Math.min(height - 1, y + 1)])
			{
				i += width;
				continue;
			}
			int above = Math.max(0, y - 1) * width * 4;
			int row = y * width * 4;
			int below = Math.min(height - 1, y + 1) * width * 4;
			for (int x = 0; x < width; x++, i++)
			{
				int pixel = input[i], alpha = pixel >>> 24;
				if (alpha == 0) { output[i] = 0; continue; }
				int offset = x * 4;
				int sumAlpha = blurHorizontal[above + offset] + 2 * blurHorizontal[row + offset] + blurHorizontal[below + offset];
				double normalization = (double) alpha / sumAlpha;
				int r = channel((pixel >>> 16) & 255, verticalSum(above, row, below, offset + 1) * normalization, alpha, amount);
				int g = channel((pixel >>> 8) & 255, verticalSum(above, row, below, offset + 2) * normalization, alpha, amount);
				int b = channel(pixel & 255, verticalSum(above, row, below, offset + 3) * normalization, alpha, amount);
				output[i] = (alpha << 24) | (r << 16) | (g << 8) | b;
			}
		}
	}

	private int verticalSum(int above, int row, int below, int offset)
	{
		return blurHorizontal[above + offset] + 2 * blurHorizontal[row + offset] + blurHorizontal[below + offset];
	}

	private static int channel(int center, double blurred, int alpha, double amount)
	{
		return clamp((int) Math.round(center + amount * (center - blurred)), alpha);
	}

	private static int clamp(int value, int max) { return Math.max(0, Math.min(max, value)); }
	private static int[] data(BufferedImage image) { return ((DataBufferInt) image.getRaster().getDataBuffer()).getData(); }

	void clear()
	{
		invalidated = false;
		scaled = null;
		sharpened = null;
		lastSource = null;
		lastResult = null;
		premultiplied = null;
		horizontal = null;
		blurHorizontal = null;
		xAxis = null;
		yAxis = null;
		xbr = null;
		changedRows = null;
		outputRows = null;
		changedStarts = null;
		changedEnds = null;
	}
}
