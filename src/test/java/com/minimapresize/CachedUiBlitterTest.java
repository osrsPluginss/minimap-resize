package com.minimapresize;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class CachedUiBlitterTest
{
	@Test
	public void cachedRunsMatchReferenceBlendingAcrossFramesFormatsClippingAndSubimages()
	{
		Random random = new Random(872);
		for (int type : new int[]{BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_ARGB_PRE})
		{
			BufferedImage parent = new BufferedImage(100, 80, type);
			BufferedImage actual = parent.getSubimage(7, 9, 80, 60);
			BufferedImage expected = new BufferedImage(80, 60, type);
			BufferedImage source = new BufferedImage(120, 50, BufferedImage.TYPE_INT_ARGB_PRE).getSubimage(11, 4, 90, 40);
			PackedPixels pixels = PackedPixels.nativePixels(source);
			PackedPixels a = PackedPixels.nativePixels(actual), e = PackedPixels.nativePixels(expected);
			CachedUiBlitter blitter = new CachedUiBlitter();
			Graphics2D ag = actual.createGraphics(), eg = expected.createGraphics();
			try
			{
				for (int frame = 0; frame < 30; frame++)
				{
					boolean changed = frame % 3 == 0;
					if (changed)
					{
						for (int y = 0; y < 40; y++)
						{
							for (int x = 0; x < 90; x++)
							{
								int alpha = (x + frame) % 3 == 0 ? 0 : (x + frame) % 3 == 1 ? 255 : 128;
								int color = random.nextInt(alpha + 1);
								pixels.pixels[pixels.index(x, y)] = (alpha << 24) | color * 0x010101;
							}
						}
					}
					for (int y = 0; y < 60; y++)
					{
						int background = frame % 2 == 0 || y % 3 == 0 ? 0 : 0x80505050;
						Arrays.fill(a.pixels, a.index(0, y), a.index(0, y) + 80, background);
						Arrays.fill(e.pixels, e.index(0, y), e.index(0, y) + 80, background);
					}
					int x = frame - 15, y = frame - 10;
					blitter.draw(actual, source, x, y, ag, true, changed);
					UiBlitter.draw(expected, source, x, y, eg, true);
					for (int row = 0; row < 60; row++)
					{
						assertArrayEquals(Arrays.copyOfRange(e.pixels, e.index(0, row), e.index(0, row) + 80),
							Arrays.copyOfRange(a.pixels, a.index(0, row), a.index(0, row) + 80));
					}
				}
			}
			finally { ag.dispose(); eg.dispose(); }
		}
	}
}
