package com.minimapresize;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class UiUnderlayTest
{
	@Test
	public void movingOpaqueOverheadsDoNotInvalidateUnchangedPanelPixels()
	{
		BufferedImage image = new BufferedImage(200, 260, BufferedImage.TYPE_INT_ARGB);
		int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
		Rectangle source = new Rectangle(0, 0, 200, 260);
		UiUnderlay underlay = new UiUnderlay();
		MinimapCompositor compositor = new MinimapCompositor();
		WidgetImageScaler scaler = new WidgetImageScaler();
		for (int frame = 0; frame < 20; frame++)
		{
			Arrays.fill(pixels, 0);
			pixels[frame * 200 + frame] = 0xffffffff;
			underlay.captureAndClear(image, Collections.singletonList(source));
			compositor.capture(image, source, true);
			Arrays.fill(pixels, 0x6a303030);
			BufferedImage foreground = compositor.extractAndRestore(null);
			scaler.scale(foreground, 300, 390, ScalingFilter.LANCZOS, 40, compositor.changed());
			assertEquals(frame == 0, compositor.changed());
			assertEquals(frame == 0, scaler.updated());
			underlay.restore(image);
			assertEquals(0xffffffff, pixels[frame * 200 + frame]);
		}
	}

	@Test
	public void overlappingRegionsRestoreBackdropOnlyOnceAndPreserveNewUnscaledUi()
	{
		for (int type : new int[]{BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_ARGB_PRE})
		{
			BufferedImage parent = new BufferedImage(30, 30, type);
			BufferedImage image = parent.getSubimage(4, 5, 20, 20);
			PackedPixels pixels = PackedPixels.nativePixels(image);
			Arrays.fill(pixels.pixels, 0x80606060);
			UiUnderlay underlay = new UiUnderlay();
			underlay.captureAndClear(image, Arrays.asList(new Rectangle(2, 2, 10, 10), new Rectangle(6, 6, 10, 10)));
			assertEquals(0, pixels.pixels[pixels.index(7, 7)]);
			pixels.pixels[pixels.index(7, 7)] = 0x80404040;
			underlay.restore(image);
			assertEquals(UiAlpha.over(0x80404040, 0x80606060), pixels.pixels[pixels.index(7, 7)]);
			assertEquals(0x80606060, pixels.pixels[pixels.index(3, 3)]);
			assertEquals(0x80606060, pixels.pixels[pixels.index(0, 0)]);
			underlay.restore(image);
			assertEquals(UiAlpha.over(0x80404040, 0x80606060), pixels.pixels[pixels.index(7, 7)]);
		}
	}

	@Test
	public void staleOrReplacedBuffersNeverReceivePreviousFramesUnderlay()
	{
		BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
		int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
		UiUnderlay underlay = new UiUnderlay();
		Arrays.fill(pixels, 0xffffffff);
		underlay.captureAndClear(image, Collections.singletonList(new Rectangle(10, 10)));
		underlay.invalidate();
		Arrays.fill(pixels, 0xff123456);
		underlay.restore(image);
		assertEquals(0xff123456, pixels[0]);
		underlay.captureAndClear(image, Collections.singletonList(new Rectangle(10, 10)));
		underlay.restore(new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB));
		underlay.restore(image);
		assertEquals(0, pixels[0]);
	}
}
