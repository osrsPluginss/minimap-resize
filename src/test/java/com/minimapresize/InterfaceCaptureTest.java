package com.minimapresize;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class InterfaceCaptureTest
{
	private void fill(BufferedImage image, Rectangle area, int color)
	{
		PackedPixels pixels = PackedPixels.nativePixels(image);
		for (int y = area.y; y < area.y + area.height; y++)
		{
			Arrays.fill(pixels.pixels, pixels.index(area.x, y), pixels.index(area.x + area.width, y), color);
		}
	}

	@Test
	public void discardingUnreadyUiRestoresTheExactHostBackdropAndLeavesOutsidePixelsAlone()
	{
		for (int mode = 0; mode < 4; mode++)
		{
			int type = mode == 0 || mode == 3 ? BufferedImage.TYPE_INT_RGB
				: mode == 1 ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_ARGB_PRE;
			BufferedImage image = new BufferedImage(100, 80, type);
			PackedPixels pixels = PackedPixels.nativePixels(image);
			Rectangle host = new Rectangle(10, 10, 80, 60);
			pixels.pixels[pixels.index(45, 35)] = 0xffffffff;
			pixels.pixels[pixels.index(46, 35)] = 0x80606060;
			int[] expected = pixels.pixels.clone();
			InterfaceCapture capture = new InterfaceCapture();
			capture.begin(image, host, mode != 3);
			fill(image, new Rectangle(30, 25, 40, 40), 0xff00ff00);
			pixels.pixels[pixels.index(50, 40)] = 0xffff0000; // Late native/HQ paint.
			pixels.pixels[pixels.index(2, 2)] = 0xff123456;
			expected[pixels.index(2, 2)] = 0xff123456;
			capture.discard(image);
			assertArrayEquals("No small interface, black fill, or alpha residue should be presented", expected, pixels.pixels);
			capture.discard(image);
			capture.restore(image);
			assertArrayEquals(expected, pixels.pixels);
		}
	}

	@Test
	public void gpuWindowCanSettleAnywhereInsideItsHostWithoutMovingUnderlyingText()
	{
		for (int type : new int[]{BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_ARGB_PRE})
		{
			BufferedImage image = new BufferedImage(120, 100, type).getSubimage(7, 9, 100, 80);
			PackedPixels pixels = PackedPixels.nativePixels(image);
			Rectangle actual = new Rectangle(40, 30, 30, 30);
			pixels.pixels[pixels.index(45, 35)] = 0xffffffff;
			pixels.pixels[pixels.index(46, 35)] = 0x80606060;
			pixels.pixels[pixels.index(2, 2)] = 0xff123456;
			InterfaceCapture capture = new InterfaceCapture();
			MinimapCompositor compositor = new MinimapCompositor();
			capture.begin(image, new Rectangle(10, 10, 80, 60), true);
			assertEquals(0, pixels.pixels[pixels.index(45, 35)]);
			fill(image, actual, 0x6a303030);
			BufferedImage foreground = capture.extract(image, actual, compositor);
			for (int pixel : PackedPixels.nativePixels(foreground).pixels) { assertEquals(0x6a303030, pixel); }
			assertEquals(0xffffffff, pixels.pixels[pixels.index(45, 35)]);
			assertEquals(0x80606060, pixels.pixels[pixels.index(46, 35)]);
			assertEquals(0, pixels.pixels[pixels.index(50, 50)]);
			assertEquals(0xff123456, pixels.pixels[pixels.index(2, 2)]);
			capture.clear();
		}
	}

	@Test
	public void lateIconsRetainWindowIsolationWhileOtherUiIsRestoredOnlyOnce()
	{
		BufferedImage image = new BufferedImage(100, 80, BufferedImage.TYPE_INT_ARGB_PRE);
		PackedPixels pixels = PackedPixels.nativePixels(image);
		Rectangle window = new Rectangle(40, 30, 30, 30);
		pixels.pixels[pixels.index(45, 35)] = 0xffffffff;
		pixels.pixels[pixels.index(20, 20)] = 0x80606060;
		InterfaceCapture capture = new InterfaceCapture();
		capture.begin(image, new Rectangle(100, 80), true);
		fill(image, window, 0x6a303030);
		capture.prepareLatePixels(image, window);
		assertEquals(0x80606060, pixels.pixels[pixels.index(20, 20)]);
		assertEquals(0x6a303030, pixels.pixels[pixels.index(45, 35)]);
		pixels.pixels[pixels.index(20, 20)] = 0xff0000ff;
		pixels.pixels[pixels.index(50, 40)] = 0xffff0000;
		BufferedImage foreground = capture.extract(image, window, new MinimapCompositor());
		assertEquals(0xffff0000, foreground.getRGB(10, 10));
		assertEquals(0xffffffff, pixels.pixels[pixels.index(45, 35)]);
		assertEquals(0xff0000ff, pixels.pixels[pixels.index(20, 20)]);
		capture.restore(image);
		assertEquals(0xff0000ff, pixels.pixels[pixels.index(20, 20)]);
	}

	@Test
	public void softwareCaptureUsesTheOriginalHostBackdropForTheFinalWindowRectangle()
	{
		BufferedImage image = new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB);
		PackedPixels pixels = PackedPixels.nativePixels(image);
		Arrays.fill(pixels.pixels, 0x00ff00);
		InterfaceCapture capture = new InterfaceCapture();
		capture.begin(image, new Rectangle(10, 10, 80, 60), false);
		Rectangle actual = new Rectangle(40, 30, 30, 30);
		fill(image, actual, 0xff0000);
		pixels.pixels[pixels.index(20, 20)] = 0x0000ff;
		BufferedImage foreground = capture.extract(image, actual, new MinimapCompositor());
		assertEquals(0xffff0000, foreground.getRGB(10, 10));
		assertEquals(0x00ff00, pixels.pixels[pixels.index(50, 40)]);
		assertEquals(0x0000ff, pixels.pixels[pixels.index(20, 20)]);
	}
}
