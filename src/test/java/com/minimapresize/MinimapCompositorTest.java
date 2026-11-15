package com.minimapresize;

import java.awt.Rectangle;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import org.junit.Test;
import static org.junit.Assert.*;

public class MinimapCompositorTest
{
	@Test
	public void bulkGpuRowsMatchPixelExtractionAndTrackColorAndCoverageChanges()
	{
		java.util.Random random = new java.util.Random(721);
		for (int type : new int[]{BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_ARGB_PRE})
		{
			BufferedImage image = new BufferedImage(90, 70, type).getSubimage(7, 9, 60, 40);
			PackedPixels packed = PackedPixels.nativePixels(image);
			Rectangle bounds = new Rectangle(10, 5, 30, 20);
			MinimapCompositor compositor = new MinimapCompositor();
			int[] previous = new int[600], content = new int[600];
			for (int frame = 0; frame < 30; frame++)
			{
				int[] background = new int[600], expected = new int[600];
				for (int y = 0, i = 0; y < 20; y++)
				{
					for (int x = 0; x < 30; x++, i++)
					{
						background[i] = (y + frame / 5) % 3 == 0 ? 0x50202020 : 0;
						packed.pixels[packed.index(bounds.x + x, bounds.y + y)] = background[i];
						if (frame % 3 == 0)
						{
							int alpha = random.nextBoolean() ? 0 : random.nextInt(256);
							content[i] = alpha << 24 | random.nextInt(alpha + 1) * 0x010101;
						}
					}
				}
				compositor.capture(image, bounds, true);
				boolean changed = frame == 0, maskChanged = frame == 0;
				for (int y = 0, i = 0; y < 20; y++)
				{
					for (int x = 0; x < 30; x++, i++)
					{
						int raw = UiAlpha.over(content[i], background[i]);
						packed.pixels[packed.index(bounds.x + x, bounds.y + y)] = raw;
						expected[i] = raw == background[i] ? 0 : UiAlpha.extract(raw, background[i]);
						changed |= expected[i] != previous[i];
						maskChanged |= (expected[i] >>> 24 != 0) != (previous[i] >>> 24 != 0);
					}
				}
				BufferedImage foreground = compositor.extractAndRestore(null);
				assertArrayEquals(expected, PackedPixels.nativePixels(foreground).pixels);
				assertEquals(changed, compositor.changed());
				assertEquals(maskChanged, compositor.maskChanged());
				for (int y = 0; y < 20; y++)
				{
					assertArrayEquals(java.util.Arrays.copyOfRange(background, y * 30, (y + 1) * 30),
						java.util.Arrays.copyOfRange(packed.pixels, packed.index(10, 5 + y), packed.index(40, 5 + y)));
				}
				previous = expected;
			}
		}
	}

	@Test
	public void gpuForegroundPreservesAlphaAndRestoresOriginalBackground()
	{
		BufferedImage frame = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
		frame.setRGB(0, 0, 0xff112233);
		MinimapCompositor compositor = new MinimapCompositor();
		Rectangle source = new Rectangle(10, 0, 10, 10);
		compositor.capture(frame, source);
		frame.setRGB(12, 3, 0x80800000);
		BufferedImage foreground = compositor.extractAndRestore(null);
		assertEquals(0x80800000, foreground.getRGB(2, 3));
		assertEquals(0, foreground.getRGB(0, 0));
		assertEquals(0, frame.getRGB(12, 3));
		assertEquals(0xff112233, frame.getRGB(0, 0));
		assertFalse(compositor.matches(frame, source));
	}

	@Test
	public void softwareSceneIsRemovedFromCutoutAndMapInteriorRemainsOpaque()
	{
		BufferedImage frame = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
		MinimapCompositor compositor = new MinimapCompositor();
		compositor.capture(frame, new Rectangle(10, 0, 10, 10));
		frame.setRGB(19, 0, 0xffff0000);
		BufferedImage foreground = compositor.extractAndRestore(new Ellipse2D.Double(12, 2, 6, 6));
		assertEquals(0, foreground.getRGB(0, 0));
		assertEquals(0xff000000, foreground.getRGB(5, 5));
		assertEquals(0xffff0000, foreground.getRGB(9, 0));
		assertEquals(0xff000000, frame.getRGB(19, 0));
	}

	@Test
	public void translatedPackedSubimageUsesCorrectStrideAndRestoresOnlyTheCapture()
	{
		BufferedImage parent = new BufferedImage(30, 30, BufferedImage.TYPE_INT_ARGB);
		BufferedImage image = parent.getSubimage(7, 9, 15, 15);
		image.setRGB(0, 0, 0xff112233);
		MinimapCompositor compositor = new MinimapCompositor();
		compositor.capture(image, new Rectangle(3, 2, 8, 8));
		image.setRGB(4, 3, 0x80ff0000);
		BufferedImage foreground = compositor.extractAndRestore(null);
		assertEquals(0x80ff0000, foreground.getRGB(1, 1));
		assertEquals(0, image.getRGB(4, 3));
		assertEquals(0xff112233, image.getRGB(0, 0));
	}

	@Test
	public void nonPackedAndPremultipliedBuffersUseSafeFallback()
	{
		for (int type : new int[]{BufferedImage.TYPE_4BYTE_ABGR, BufferedImage.TYPE_INT_ARGB_PRE})
		{
			BufferedImage image = new BufferedImage(10, 10, type);
			MinimapCompositor compositor = new MinimapCompositor();
			compositor.capture(image, new Rectangle(2, 2, 5, 5));
			image.setRGB(3, 3, 0x80800000);
			assertEquals(image.getRGB(3, 3), compositor.extractAndRestore(null).getRGB(1, 1));
			assertEquals(0, image.getRGB(3, 3));
		}
	}

	@Test
	public void unchangedCutoutSkipsFilteringAndColorOnlyChangesDoNotRebuildHitMask()
	{
		BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
		MinimapCompositor compositor = new MinimapCompositor();
		Rectangle source = new Rectangle(2, 2, 5, 5);
		for (int i = 0; i < 3; i++)
		{
			compositor.capture(image, source);
			image.setRGB(3, 3, i == 2 ? 0xff00ff00 : 0xffff0000);
			compositor.extractAndRestore(null);
			assertEquals(i != 1, compositor.changed());
			assertEquals(i == 0, compositor.maskChanged());
		}
	}

	@Test
	public void rgbBuffersIgnoreUnusedAlphaBitsWhenComparingBackground()
	{
		BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
		int[] raw = ((java.awt.image.DataBufferInt) image.getRaster().getDataBuffer()).getData();
		MinimapCompositor compositor = new MinimapCompositor();
		compositor.capture(image, new Rectangle(0, 0, 10, 10));
		raw[1] = 0xff000000;
		assertEquals(0, compositor.extractAndRestore(null).getRGB(1, 0));
		assertEquals(0, raw[1]);
	}

	@Test
	public void oldCaptureIsRejectedWhenBufferOrBoundsChange()
	{
		BufferedImage frame = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
		MinimapCompositor compositor = new MinimapCompositor();
		Rectangle source = new Rectangle(10, 0, 10, 10);
		compositor.capture(frame, source);
		assertTrue(compositor.matches(frame, source));
		assertFalse(compositor.matches(frame, new Rectangle(9, 0, 10, 10)));
		assertFalse(compositor.matches(new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB), source));
		compositor.invalidate();
		assertFalse(compositor.matches(frame, source));
	}
}
