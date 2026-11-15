/*
 * CPU adaptation of Hyllian's xBR-lv2 shader (as distributed by RuneLite).
 * Copyright (C) 2011-2016 Hyllian - sergiogdb@gmail.com
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * The original incorporates ideas from Joshua Street's SABR shader.
 */
package com.minimapresize;

/**
 * Experimental CORNER_C / small-details xBR level 2 on premultiplied pixels.
 * Classify each source corner once, then blend at fractional destination positions.
 * Unlike the shader snapshot, the far-right (f4) neighbour is explicitly initialized.
 * This is a CPU adaptation, not a promise of pixel-identical GPU/117 HD output.
 */
final class XbrImageScaler
{
	private static final int[] FX = {1, 0, -1, 0};
	private static final int[] FY = {0, -1, 0, 1};
	private static final int[] HX = {0, 1, 0, -1};
	private static final int[] HY = {1, 0, -1, 0};
	private static final float[] A = {1, -1, -1, 1};
	private static final float[] B = {1, 1, -1, -1};
	private static final float[] C = {1.5f, 0.5f, -0.5f, 0.5f};
	private static final float[] BX = {0.5f, 2, -0.5f, -2};
	private static final float[] CX = {1, 1, -0.5f, 0};
	private static final float[] BY = {2, 0.5f, -2, -0.5f};
	private static final float[] CY = {2, 0, -1, 0.5f};
	private float[] luma;
	private float[] detailLuma;
	private byte[] corners;
	private int[] neighbours;
	private final float[] blend = new float[4];
	private int[] columns;
	private float[] fractions;
	private int width;
	private int height;
	private int plannedWidth;
	private int plannedSourceWidth;
	private int[] classifiedStarts;
	private int[] classifiedEnds;
	private int[] columnStarts;

	void scale(int[] source, int width, int height, int[] output, int targetWidth, int targetHeight)
	{
		scale(source, width, height, output, targetWidth, targetHeight, null);
	}

	void scale(int[] source, int width, int height, int[] output, int targetWidth, int targetHeight, boolean[] changedRows)
	{
		scale(source, width, height, output, targetWidth, targetHeight, changedRows, null, null);
	}

	void scale(int[] source, int width, int height, int[] output, int targetWidth, int targetHeight,
		boolean[] changedRows, int[] changedStarts, int[] changedEnds)
	{
		boolean reset = this.width != width || this.height != height || luma == null;
		this.width = width;
		this.height = height;
		if (luma == null || luma.length != source.length)
		{
			luma = new float[source.length];
			detailLuma = new float[source.length];
			corners = new byte[source.length * 4];
			neighbours = new int[source.length * 4];
		}
		if (classifiedStarts == null || classifiedStarts.length != height)
		{
			classifiedStarts = new int[height];
			classifiedEnds = new int[height];
		}
		for (int y = 0; y < height; y++)
		{
			if (!reset && changedRows != null && !changedRows[y]) { continue; }
			int first = reset || changedStarts == null ? 0 : changedStarts[y];
			int end = reset || changedEnds == null ? width : changedEnds[y];
			for (int i = y * width + first; i < y * width + end; i++)
			{
				int p = source[i];
				int r = (p >>> 16) & 255, g = (p >>> 8) & 255, b = p & 255;
				// Opaque black outlines differ from transparent black. A constant
				// opacity term leaves opaque colour comparisons unchanged, while
				// allowing xBR to reconstruct the outside of dark circular rims.
				float opacity = 48f * (p >>> 24) / 255;
				luma[i] = opacity + (14.352f * r + 28.176f * g + 5.472f * b) / 255;
				detailLuma[i] = opacity + (10.63f * r + 35.76f * g + 3.61f * b) / 255;
			}
		}
		for (int y = 0; y < height; y++)
		{
			int first = width, end = 0;
			if (reset || changedRows == null) { first = 0; end = width; }
			// Every rotated xBR neighborhood fits within two source pixels in each
			// direction. A changing number/item should not reclassify the whole panel.
			else for (int row = Math.max(0, y - 2); row <= Math.min(height - 1, y + 2); row++)
			{
				if (!changedRows[row]) { continue; }
				first = Math.min(first, changedStarts == null ? 0 : Math.max(0, changedStarts[row] - 2));
				end = Math.max(end, changedEnds == null ? width : Math.min(width, changedEnds[row] + 2));
			}
			classifiedStarts[y] = first;
			classifiedEnds[y] = end;
			for (int x = first; x < end; x++)
			{
				for (int corner = 0; corner < 4; corner++) { classify(source, x, y, corner); }
			}
		}
		if (plannedWidth != targetWidth || plannedSourceWidth != width)
		{
			columns = new int[targetWidth];
			fractions = new float[targetWidth];
			for (int x = 0; x < targetWidth; x++)
			{
				double position = (x + 0.5) * width / targetWidth;
				columns[x] = (int) position;
				fractions[x] = (float) (position - columns[x]);
			}
			columnStarts = new int[width + 1];
			for (int column = 0, x = 0; column <= width; column++)
			{
				while (x < targetWidth && columns[x] < column) { x++; }
				columnStarts[column] = x;
			}
			plannedWidth = targetWidth;
			plannedSourceWidth = width;
		}
		float delta = 1f / (float) Math.ceil((double) targetWidth / width);
		for (int y = 0; y < targetHeight; y++)
		{
			double position = (y + 0.5) * height / targetHeight;
			int row = (int) position * width;
			int startColumn = classifiedStarts[(int) position], endColumn = classifiedEnds[(int) position];
			if (startColumn >= endColumn) { continue; }
			float py = (float) (position - Math.floor(position));
			int left = columnStarts[startColumn], right = columnStarts[endColumn];
			for (int x = left, out = y * targetWidth + left; x < right; x++, out++)
			{
				int index = row + columns[x], base = index * 4, e = source[index];
				if ((corners[base] | corners[base + 1] | corners[base + 2] | corners[base + 3]) == 0)
				{
					output[out] = e;
					continue;
				}
				float px = fractions[x];
				for (int k = 0; k < 4; k++)
				{
					int flags = corners[base + k];
					float line = A[k] * py + B[k] * px - C[k];
					float amount = (flags & 1) == 0 ? 0 : ramp(line - 0.25f, delta);
					if ((flags & 2) != 0) { amount = Math.max(amount, ramp(line, delta)); }
					if ((flags & 4) != 0)
					{
						amount = Math.max(amount, ramp(A[k] * py + BX[k] * px - CX[k], (k % 2 == 0 ? 0.5f : 1) * delta));
					}
					if ((flags & 8) != 0)
					{
						amount = Math.max(amount, ramp(A[k] * py + BY[k] * px - CY[k], (k % 2 == 0 ? 1 : 0.5f) * delta));
					}
					blend[k] = amount;
				}
				int first = mix(mix(e, neighbours[base], blend[0]), neighbours[base + 2], blend[2]);
				int second = mix(mix(e, neighbours[base + 1], blend[1]), neighbours[base + 3], blend[3]);
				output[out] = colorDistance(e, first) <= colorDistance(e, second) ? second : first;
			}
		}
	}

	private void classify(int[] source, int x, int y, int k)
	{
		int fx = FX[k], fy = FY[k], hx = HX[k], hy = HY[k];
		int center = y * width + x, slot = center * 4 + k;
		int right = index(x + fx, y + fy), down = index(x + hx, y + hy);
		float e = luma[center], f = luma[right], h = luma[down];
		corners[slot] = 0;
		neighbours[slot] = source[Math.abs(e - f) <= Math.abs(e - h) ? right : down];
		if (e == f || e == h) { return; }
		float b = luma[index(x - hx, y - hy)];
		float c = luma[index(x + fx - hx, y + fy - hy)];
		float d = luma[index(x - fx, y - fy)];
		float g = luma[index(x - fx + hx, y - fy + hy)];
		float i = luma[index(x + fx + hx, y + fy + hy)];
		float i4 = detailLuma[index(x + 2 * fx + hx, y + 2 * fy + hy)];
		float i5 = detailLuma[index(x + fx + 2 * hx, y + fy + 2 * hy)];
		float h5 = detailLuma[index(x + 2 * hx, y + 2 * hy)];
		float f4 = detailLuma[index(x + 2 * fx, y + 2 * fy)];
		float wd1 = Math.abs(e - c) + Math.abs(e - g) + Math.abs(i - f4) + Math.abs(i - h5)
			+ Math.abs(b - d) + Math.abs(i4 - i5) + 2 * Math.abs(h - f);
		float wd2 = Math.abs(h - d) + Math.abs(h - i5) + Math.abs(f - b) + Math.abs(f - i4)
			+ Math.abs(g - h5) + Math.abs(c - f4) + 2 * Math.abs(e - i);
		int flags = wd1 <= wd2 ? 1 : 0;
		boolean corner = (!equal(f, b) && !equal(f, c)) || (!equal(h, d) && !equal(h, g))
			|| (equal(e, i) && ((!equal(f, f4) && !equal(f, i4)) || (!equal(h, h5) && !equal(h, i5))))
			|| equal(e, g) || equal(e, c);
		if (wd1 + 0.1f <= wd2 && corner)
		{
			flags |= 2;
			if (2 * Math.abs(f - g) <= Math.abs(h - c) && e != g && d != g) { flags |= 4; }
			if (2 * Math.abs(h - c) <= Math.abs(f - g) && e != c && b != c) { flags |= 8; }
		}
		corners[slot] = (byte) flags;
	}

	private int index(int x, int y)
	{
		return Math.max(0, Math.min(height - 1, y)) * width + Math.max(0, Math.min(width - 1, x));
	}

	private static boolean equal(float a, float b) { return Math.abs(a - b) <= 9; }
	private static float ramp(float value, float delta) { return Math.max(0, Math.min(1, (value + delta) / (2 * delta))); }

	private static int mix(int a, int b, float amount)
	{
		if (amount <= 0) { return a; }
		if (amount >= 1) { return b; }
		int result = 0;
		for (int shift = 0; shift <= 24; shift += 8)
		{
			int from = (a >>> shift) & 255, to = (b >>> shift) & 255;
			result |= Math.round(from + (to - from) * amount) << shift;
		}
		return result;
	}

	private static int colorDistance(int a, int b)
	{
		return Math.abs(((a >>> 16) & 255) - ((b >>> 16) & 255))
			+ Math.abs((a >>> 24) - (b >>> 24))
			+ Math.abs(((a >>> 8) & 255) - ((b >>> 8) & 255)) + Math.abs((a & 255) - (b & 255));
	}
}
