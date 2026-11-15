package com.minimapresize;

import java.awt.image.BufferedImage;
import org.junit.Test;
import static org.junit.Assert.*;

public class HybridScalingTest
{
	@Test
	public void thinStrokesRetainMoreContrastAfterASecondStandardHybridPass()
	{
		// Irregular one/two-pixel strokes exercise both pixel phases at 150%,
		// rather than only wide edges that already land between output pixels.
		int[] strokes = {32, 32, 224, 32, 224, 224, 32, 32, 224, 32, 32, 224, 224, 224, 32, 32};
		for (boolean vertical : new boolean[]{false, true})
		{
			BufferedImage source = new BufferedImage(vertical ? 1 : strokes.length,
				vertical ? strokes.length : 1, BufferedImage.TYPE_INT_ARGB);
			for (int i = 0; i < strokes.length; i++)
			{
				source.setRGB(vertical ? 0 : i, vertical ? i : 0, 0xff000000 | strokes[i] * 0x010101);
			}
			for (int panelSize : new int[]{20, 24, 28, 36, 40})
			{
				BufferedImage firstPass = new WidgetImageScaler().scale(source,
					vertical ? 1 : panelSize, vertical ? panelSize : 1, ScalingFilter.HYBRID, 0);
				int[] revised = new int[panelSize];
				for (int i = 0; i < panelSize; i++)
				{
					int pixel = firstPass.getRGB(vertical ? 0 : i, vertical ? i : 0);
					assertEquals(255, pixel >>> 24);
					revised[i] = pixel & 255;
					assertTrue("No sharpening halos", revised[i] >= 32 && revised[i] <= 224);
				}
				int[] previous = standardHybrid(strokes, panelSize);
				for (double stretch : new double[]{1.25, 1.5, 2})
				{
					int screenSize = (int) Math.round(panelSize * stretch);
					assertTrue("Hybrid + Hybrid contrast at panel " + panelSize + ", stretch " + stretch,
						contrast(standardHybrid(revised, screenSize)) > contrast(standardHybrid(previous, screenSize)));
				}
			}
		}
	}

	@Test
	public void hybridStillBlendsBoundariesInsteadOfSimplyBecomingNearestNeighbor()
	{
		BufferedImage source = new BufferedImage(16, 1, BufferedImage.TYPE_INT_ARGB);
		for (int x = 0; x < 16; x++) { source.setRGB(x, 0, x % 2 == 0 ? 0xff000000 : 0xffffffff); }
		for (int width : new int[]{20, 24, 28, 36, 40})
		{
			BufferedImage result = new WidgetImageScaler().scale(source, width, 1, ScalingFilter.HYBRID, 0);
			boolean blended = false;
			for (int x = 0; x < width; x++)
			{
				int value = result.getRGB(x, 0) & 255;
				blended |= value > 0 && value < 255;
			}
			assertTrue("Keep antialiased boundaries at " + width, blended);
		}
	}

	@Test
	public void reductionRetainsBilinearSampling()
	{
		BufferedImage source = new BufferedImage(7, 5, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < 5; y++)
		{
			for (int x = 0; x < 7; x++) { source.setRGB(x, y, 0xff000000 | (x * 31 + y * 5) * 0x010101); }
		}
		WidgetImageScaler scaler = new WidgetImageScaler();
		int[] expected = scaler.scale(source, 4, 3, ScalingFilter.BILINEAR, 0).getRGB(0, 0, 4, 3, null, 0, 4);
		assertArrayEquals(expected, scaler.scale(source, 4, 3, ScalingFilter.HYBRID, 0).getRGB(0, 0, 4, 3, null, 0, 4));
	}

	/** Reference for the renderer's original Hybrid filter, not the revised plugin filter. */
	private static int[] standardHybrid(int[] source, int size)
	{
		int[] result = new int[size];
		double scale = (double) size / source.length;
		for (int i = 0; i < size; i++)
		{
			double uv = (i + 0.5) / scale;
			double fraction = uv - Math.floor(uv);
			double coordinate = Math.floor(uv) + Math.min(fraction * scale, 0.5)
				- Math.min((1 - fraction) * scale, 0.5);
			int left = (int) Math.floor(coordinate);
			double weight = coordinate - left;
			int a = source[Math.max(0, Math.min(source.length - 1, left))];
			int b = source[Math.max(0, Math.min(source.length - 1, left + 1))];
			result[i] = (int) Math.round(a + (b - a) * weight);
		}
		return result;
	}

	private static long contrast(int[] pixels)
	{
		long result = 0;
		for (int i = 1; i < pixels.length; i++)
		{
			int difference = pixels[i] - pixels[i - 1];
			result += difference * difference;
		}
		return result;
	}
}
