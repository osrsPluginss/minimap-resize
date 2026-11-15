package com.minimapresize;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class OverheadScalerTest
{
	@Test
	public void sparseGpuOverheadsRespectViewportBoundsWithRgbAndSubimageWrappers()
	{
		for (int type : new int[]{BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_INT_ARGB_PRE})
		{
			BufferedImage image = new BufferedImage(220, 170, type).getSubimage(10, 10, 200, 150);
			PackedPixels pixels = PackedPixels.nativePixels(image);
			pixels.pixels[pixels.index(0, 0)] = 0xff123456;
			pixels.pixels[pixels.index(105, 85)] = 0x80606060;
			OverheadScaler scaler = new OverheadScaler();
			scaler.begin(image, new Rectangle(50, 30, 120, 100));
			for (int y = 70; y < 80; y++)
			{
				Arrays.fill(pixels.pixels, pixels.index(90, y), pixels.index(100, y), 0xffffffff);
			}
			Graphics2D g = image.createGraphics();
			try { scaler.finish(image, g, 200, ScalingFilter.NEAREST, 0, 0); }
			finally { g.dispose(); }
			assertEquals(0xff123456, pixels.pixels[pixels.index(0, 0)]);
			assertEquals(0x80606060, pixels.pixels[pixels.index(105, 85)]);
			assertEquals(0xffffffff, pixels.pixels[pixels.index(85, 60)]);
			assertEquals(0xffffffff, pixels.pixels[pixels.index(104, 79)]);
			assertEquals(0, pixels.pixels[pixels.index(105, 79)]);
			scaler.clear();
		}
	}

	@Test
	public void nativeSkullsPrayersAndTextScaleAroundTheirOwnLocationsWithoutScalingSceneOverlays()
	{
		BufferedImage image = new BufferedImage(500, 300, BufferedImage.TYPE_INT_ARGB_PRE);
		PackedPixels pixels = PackedPixels.nativePixels(image);
		OverheadScaler scaler = new OverheadScaler();
		Graphics2D g = image.createGraphics();
		try
		{
			for (int frame = 0; frame < 3; frame++)
			{
				Arrays.fill(pixels.pixels, 0);
				g.setColor(Color.BLUE);
				g.fillRect(100, 120, 10, 10); // RuneLite ABOVE_SCENE label, below native skull.
				scaler.begin(image, new Rectangle(500, 300));
				g.setColor(Color.RED);
				g.fillRect(100 + frame, 120, 10, 10);
				g.setColor(Color.GREEN);
				g.fillRect(300, 200, 20, 10);
				scaler.finish(image, g, 200, ScalingFilter.NEAREST, 0, 0);
				assertEquals(Color.RED.getRGB(), image.getRGB(95 + frame, 110));
				assertEquals(Color.RED.getRGB(), image.getRGB(114 + frame, 129));
				assertEquals(Color.GREEN.getRGB(), image.getRGB(290, 190));
				assertEquals(Color.GREEN.getRGB(), image.getRGB(329, 209));
				assertEquals(0, image.getRGB(330, 210));
				assertEquals(0, image.getRGB(250, 150));
			}
			// Shrinking exposes the unscaled blue scene label beneath the red skull.
			Arrays.fill(pixels.pixels, 0);
			g.setColor(Color.BLUE);
			g.fillRect(100, 120, 10, 10);
			scaler.begin(image, new Rectangle(500, 300));
			g.setColor(Color.RED);
			g.fillRect(100, 120, 10, 10);
			scaler.finish(image, g, 50, ScalingFilter.NEAREST, 0, 0);
			assertEquals(Color.BLUE.getRGB(), image.getRGB(100, 120));
			assertEquals(Color.RED.getRGB(), image.getRGB(102, 125));
		}
		finally { g.dispose(); scaler.clear(); }
	}

	@Test
	public void adjacentTextGlyphsGroupButDistantActorsDoNot()
	{
		BufferedImage image = new BufferedImage(500, 300, BufferedImage.TYPE_INT_ARGB_PRE);
		Graphics2D g = image.createGraphics();
		g.setColor(Color.WHITE);
		for (int x = 20; x < 100; x += 8) { g.fillRect(x, 30, 4, 10); }
		g.fillRect(300, 100, 24, 24);
		g.dispose();
		List<Rectangle> clusters = OverheadScaler.clusters(image);
		assertEquals(2, clusters.size());
		assertTrue(clusters.contains(new Rectangle(20, 30, 76, 10)));
		assertTrue(clusters.contains(new Rectangle(300, 100, 24, 24)));
	}

	@Test
	public void abortRestoresUnderlyingOverlayAndNewFrameNeverReplaysOldPixels()
	{
		BufferedImage image = new BufferedImage(50, 50, BufferedImage.TYPE_INT_ARGB_PRE);
		image.setRGB(10, 10, Color.WHITE.getRGB());
		OverheadScaler scaler = new OverheadScaler();
		scaler.begin(image, new Rectangle(50, 50));
		assertEquals(0, image.getRGB(10, 10));
		scaler.reset(image);
		assertEquals(Color.WHITE.getRGB(), image.getRGB(10, 10));
		scaler.begin(image, new Rectangle(50, 50));
		scaler.newFrame();
		scaler.reset(image);
		assertEquals(0, image.getRGB(10, 10));
	}
}
