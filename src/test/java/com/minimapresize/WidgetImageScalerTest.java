package com.minimapresize;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import org.junit.Test;
import static org.junit.Assert.*;

public class WidgetImageScalerTest
{
	@Test public void unchangedIdentityFramesDoNotInvalidateBlitAndMaskCaches()
	{
		WidgetImageScaler scaler=new WidgetImageScaler();BufferedImage source=new BufferedImage(8,8,BufferedImage.TYPE_INT_ARGB_PRE);
		assertSame(source,scaler.scale(source,8,8,ScalingFilter.BILINEAR,0,false));assertTrue(scaler.updated());
		scaler.scale(source,8,8,ScalingFilter.BILINEAR,0,false);assertFalse(scaler.updated());
		scaler.invalidate();scaler.scale(source,8,8,ScalingFilter.BILINEAR,0,false);assertTrue(scaler.updated());
		source.setRGB(4,4,0xff123456);scaler.scale(source,8,8,ScalingFilter.BILINEAR,0,true);assertTrue(scaler.updated());
		assertArrayEquals(pixels(new WidgetImageScaler().scale(source,16,16,ScalingFilter.BILINEAR,0)),
			pixels(scaler.scale(source,16,16,ScalingFilter.BILINEAR,0,false)));
	}

	@Test
	public void sparseXbrUpdatesMatchFullFilteringAcrossEdgesAndDeferredChanges()
	{
		java.util.Random random = new java.util.Random(926134);
		for (int type : new int[]{BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_ARGB_PRE})
		{
			BufferedImage source = new BufferedImage(51, 43, type).getSubimage(3, 5, 41, 31);
			WidgetImageScaler cached = new WidgetImageScaler();
			for (int[] size : new int[][]{{80, 60}, {83, 63}, {123, 93}})
			{
				for (int y = 0; y < 31; y++)
				{
					for (int x = 0; x < 41; x++) { source.setRGB(x, y, random.nextInt()); }
				}
				cached.scale(source, size[0], size[1], ScalingFilter.XBR, 0);
				for (int frame = 0; frame < 24; frame++)
				{
					// First change every corner, then isolated pixels and small item-sized blocks.
					int x = frame < 4 ? frame % 2 * 40 : random.nextInt(41);
					int y = frame < 4 ? frame / 2 * 30 : random.nextInt(31);
					source.setRGB(x, y, frame % 2 == 0 ? 0 : random.nextInt());
					cached.scale(source, size[0], size[1], ScalingFilter.XBR, 0, true, 1);
					int otherX = random.nextInt(41), otherY = random.nextInt(31);
					for (int dy = 0; dy < Math.min(3, 31 - otherY); dy++)
					{
						for (int dx = 0; dx < Math.min(4, 41 - otherX); dx++)
						{ source.setRGB(otherX + dx, otherY + dy, random.nextInt()); }
					}
					BufferedImage actual = cached.scale(source, size[0], size[1], ScalingFilter.XBR, 0, true, 0);
					BufferedImage expected = new WidgetImageScaler().scale(source, size[0], size[1], ScalingFilter.XBR, 0);
					assertArrayEquals("Sparse/deferred xBR frame " + frame, pixels(expected), pixels(actual));
				}
			}
		}
	}

	@Test
	public void bilinearAndHybridPreservePremultipliedAlphaAtFractionalSizes()
	{
		BufferedImage source = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB_PRE);
		int[] input = ((DataBufferInt) source.getRaster().getDataBuffer()).getData();
		input[0] = 0;
		input[1] = 0x80800000;
		input[2] = 0xff00ff00;
		input[3] = 0x40000040;
		BufferedImage result = new WidgetImageScaler().scale(source, 3, 3, ScalingFilter.BILINEAR, 0);
		int[] output = ((DataBufferInt) result.getRaster().getDataBuffer()).getData();
		// Center averages the four corners, with integer rounding after each axis.
		assertEquals(0x70204010, output[4]);
		assertEquals(input[0], output[0]);
		assertEquals(input[1], output[2]);
		assertEquals(input[2], output[6]);
		assertEquals(input[3], output[8]);
		for (ScalingFilter filter : new ScalingFilter[]{ScalingFilter.BILINEAR, ScalingFilter.HYBRID})
		{
			for (int[] size : new int[][]{{1, 7}, {7, 1}, {13, 19}})
			{
				result = new WidgetImageScaler().scale(source, size[0], size[1], filter, 0);
				for (int pixel : ((DataBufferInt) result.getRaster().getDataBuffer()).getData())
				{
					int alpha = pixel >>> 24;
					assertTrue(((pixel >>> 16) & 255) <= alpha);
					assertTrue(((pixel >>> 8) & 255) <= alpha);
					assertTrue((pixel & 255) <= alpha);
				}
			}
		}
	}

	@Test public void xbrReconstructsTransparentEdgesOfBlackCircularRims()
	{
		BufferedImage source=new BufferedImage(26,26,BufferedImage.TYPE_INT_ARGB);
		for(int y=0;y<26;y++)for(int x=0;x<26;x++)
		{ double r=Math.hypot(x-12.5,y-12.5);if(r>=9&&r<12) { source.setRGB(x,y,0xff000000); } }
		WidgetImageScaler scaler=new WidgetImageScaler();BufferedImage result=scaler.scale(source,78,78,ScalingFilter.XBR,0);
		int partial=0;
		for(int p:pixels(result)) { int alpha=p>>>24;if(alpha>0&&alpha<255) { partial++; }assertEquals(0,p&0xffffff); }
		assertTrue("Black outlines must have reconstructed alpha coverage, not square native steps",partial>100);
		assertEquals("The genuine round opening stays clear",0,result.getRGB(39,39));
		assertSame(result,scaler.scale(source,78,78,ScalingFilter.XBR,0,false));assertFalse(scaler.updated());
	}
	@Test public void interactiveInvalidationBypassesTheLimitOnceWithoutDiscardingTheCachedImage()
	{
		BufferedImage source = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB_PRE);
		WidgetImageScaler scaler = new WidgetImageScaler();
		for (int y = 0; y < 8; y++) { for (int x = 0; x < 8; x++) { source.setRGB(x, y, 0xffff0000); } }
		BufferedImage image = scaler.scale(source, 16, 16, ScalingFilter.NEAREST, 0, true, 1);
		source.setRGB(3, 3, 0xff0000ff); scaler.invalidate();
		assertSame(image, scaler.scale(source, 16, 16, ScalingFilter.NEAREST, 0, true, 1));
		assertEquals(0xff0000ff, image.getRGB(6, 6)); assertTrue(scaler.updated());
		source.setRGB(3, 3, 0xff00ff00);
		assertSame(image, scaler.scale(source, 16, 16, ScalingFilter.NEAREST, 0, true, 1));
		assertEquals("Normal throttling resumes after the one interactive refresh", 0xff0000ff, image.getRGB(6, 6));
		assertFalse(scaler.updated());
	}
	@Test
	public void defaultMinimapFilterKeepsLightweightPreferencesAndAvoidsExpensiveUiFilters()
	{
		for (ScalingFilter selected : ScalingFilter.values())
		{
			MinimapResizeConfig config = new MinimapResizeConfig()
			{
				@Override public ScalingFilter filter() { return selected; }
			};
			ScalingFilter expected = selected == ScalingFilter.NEAREST || selected == ScalingFilter.HYBRID
				? selected : ScalingFilter.BILINEAR;
			assertEquals(expected, config.minimapFilter());
			assertEquals(selected, config.filter());
		}
	}

	@Test
	public void partialUpdatesMatchFullResamplingIncludingAlphaEdgesAndFilterSupport()
	{
		java.util.Random random = new java.util.Random(7625);
		for (int type : new int[]{BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_ARGB_PRE})
		{
			for (ScalingFilter filter : ScalingFilter.values())
			{
				for (int[] size : new int[][]{{47, 61}, {12, 17}, {31, 59}})
				{
					BufferedImage source = new BufferedImage(31, 37, type);
					for (int y = 0; y < 37; y++)
					{
						for (int x = 0; x < 31; x++) { source.setRGB(x, y, random.nextInt()); }
					}
					WidgetImageScaler cached = new WidgetImageScaler();
					cached.scale(source, size[0], size[1], filter, 60);
					for (int frame = 0; frame < 12; frame++)
					{
						int row = frame == 0 ? 0 : frame == 1 ? 36 : random.nextInt(37);
						for (int x = 0; x < 31; x++) { source.setRGB(x, row, frame % 3 == 0 ? 0 : random.nextInt()); }
						BufferedImage actual = cached.scale(source, size[0], size[1], filter, 60);
						BufferedImage expected = new WidgetImageScaler().scale(source, size[0], size[1], filter, 60);
						assertArrayEquals(filter + " partial update " + frame,
							((DataBufferInt) expected.getRaster().getDataBuffer()).getData(),
							((DataBufferInt) actual.getRaster().getDataBuffer()).getData());
					}
				}
			}
		}
	}

	@Test
	public void deferredRowChangesAndSettingChangesDoNotLeaveStalePixels()
	{
		BufferedImage source = new BufferedImage(21, 43, BufferedImage.TYPE_INT_ARGB_PRE);
		WidgetImageScaler cached = new WidgetImageScaler();
		for (ScalingFilter filter : ScalingFilter.values())
		{
			cached.scale(source, 37, 71, filter, 40);
			source.setRGB(10, 4, 0xffeeeeee);
			cached.scale(source, 37, 71, filter, 40, true, 1);
			source.setRGB(10, 38, 0x80ffaa11);
			BufferedImage actual = cached.scale(source, 37, 71, filter, 40, true, 0);
			assertArrayEquals(pixels(new WidgetImageScaler().scale(source, 37, 71, filter, 40)), pixels(actual));
			// Force sharpening/filter invalidation even when source pixels are unchanged.
			actual = cached.scale(source, 37, 71, ScalingFilter.SHARP_BICUBIC, 80, false, 0);
			assertArrayEquals(pixels(new WidgetImageScaler().scale(source, 37, 71, ScalingFilter.SHARP_BICUBIC, 80)), pixels(actual));
			source.setRGB(10, 4, 0);
			source.setRGB(10, 38, 0);
		}
	}

	private BufferedImage detailImage()
	{
		BufferedImage image = new BufferedImage(16, 8, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < image.getHeight(); y++)
		{
			for (int x = 0; x < image.getWidth(); x++)
			{
				int value = (x / 2) % 2 == 0 ? 48 : 208;
				image.setRGB(x, y, 0xff000000 | value * 0x010101);
			}
		}
		return image;
	}

	private int[] pixels(BufferedImage image)
	{
		return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
	}

	private long edgeContrast(BufferedImage image)
	{
		long contrast = 0;
		for (int x = 1; x < image.getWidth(); x++)
		{
			int delta = (image.getRGB(x, image.getHeight() / 2) & 255)
				- (image.getRGB(x - 1, image.getHeight() / 2) & 255);
			contrast += delta * delta;
		}
		return contrast;
	}

	@Test
	public void sharpBicubicIncreasesFineEdgeContrastAndStrengthIsAdjustable()
	{
		BufferedImage source = detailImage();
		WidgetImageScaler scaler = new WidgetImageScaler();
		long plain = edgeContrast(scaler.scale(source, 24, 12, ScalingFilter.BICUBIC, 40));
		long mild = edgeContrast(scaler.scale(source, 24, 12, ScalingFilter.SHARP_BICUBIC, 40));
		long strong = edgeContrast(scaler.scale(source, 24, 12, ScalingFilter.SHARP_BICUBIC, 100));
		assertTrue("Sharpening should increase edge contrast over plain Bicubic", mild > plain);
		assertTrue("Higher strength should increase contrast on this detail pattern", strong > mild);
	}

	@Test
	public void zeroStrengthMatchesPlainBicubicAndOtherModesIgnoreStrength()
	{
		WidgetImageScaler scaler = new WidgetImageScaler();
		BufferedImage source = detailImage();
		int[] bicubic = pixels(scaler.scale(source, 11, 5, ScalingFilter.BICUBIC, 100));
		assertArrayEquals(bicubic, pixels(scaler.scale(source, 11, 5, ScalingFilter.SHARP_BICUBIC, 0)));
		for (ScalingFilter filter : ScalingFilter.values())
		{
			if (filter == ScalingFilter.SHARP_BICUBIC) { continue; }
			int[] without = pixels(scaler.scale(source, 24, 12, filter, 0));
			assertArrayEquals(without, pixels(scaler.scale(source, 24, 12, filter, 100)));
		}
	}

	@Test
	public void sharpeningPreservesAlphaAndValidPremultipliedColors()
	{
		BufferedImage source = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
		for (int y = 1; y < 7; y++)
		{
			for (int x = 1; x < 7; x++)
			{
				source.setRGB(x, y, (x % 2 == 0 ? 0x80 : 0xff) << 24 | 0x33cc88);
			}
		}
		WidgetImageScaler scaler = new WidgetImageScaler();
		int[] plain = pixels(scaler.scale(source, 13, 13, ScalingFilter.BICUBIC, 0));
		BufferedImage sharp = scaler.scale(source, 13, 13, ScalingFilter.SHARP_BICUBIC, 100);
		int[] sharpened = pixels(sharp);
		int[] premultiplied = ((DataBufferInt) sharp.getRaster().getDataBuffer()).getData();
		for (int i = 0; i < plain.length; i++)
		{
			int alpha = plain[i] >>> 24;
			assertEquals(alpha, sharpened[i] >>> 24);
			assertTrue(((premultiplied[i] >>> 16) & 255) <= alpha);
			assertTrue(((premultiplied[i] >>> 8) & 255) <= alpha);
			assertTrue((premultiplied[i] & 255) <= alpha);
			if (alpha == 0)
			{
				assertEquals(0, premultiplied[i]);
			}
		}
	}

	@Test
	public void transparentBordersDoNotBecomeDarkOrPickUpHiddenPixelColors()
	{
		BufferedImage source = new BufferedImage(5, 5, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < 5; y++)
		{
			for (int x = 0; x < 5; x++)
			{
				source.setRGB(x, y, x > 0 && x < 4 && y > 0 && y < 4 ? 0xffffffff : 0x00ff0000);
			}
		}
		WidgetImageScaler scaler = new WidgetImageScaler();
		BufferedImage output = scaler.scale(source, 9, 9, ScalingFilter.SHARP_BICUBIC, 100);
		for (int pixel : pixels(output))
		{
			if ((pixel >>> 24) > 0)
			{
				assertEquals("White edges should stay white at every coverage", 0xffffff, pixel & 0xffffff);
			}
		}
	}

	@Test
	public void reusedBuffersHaveNoOldFrameGhostsAndDoNotChangeSource()
	{
		BufferedImage source = detailImage();
		int[] original = pixels(source);
		WidgetImageScaler scaler = new WidgetImageScaler();
		BufferedImage first = scaler.scale(source, 24, 12, ScalingFilter.SHARP_BICUBIC, 40);
		assertArrayEquals(original, pixels(source));
		BufferedImage transparent = new BufferedImage(16, 8, BufferedImage.TYPE_INT_ARGB);
		BufferedImage next = scaler.scale(transparent, 24, 12, ScalingFilter.SHARP_BICUBIC, 40);
		assertSame(first, next);
		assertArrayEquals(new int[24 * 12], pixels(next));
		scaler.clear();
		assertNotSame(first, scaler.scale(source, 24, 12, ScalingFilter.SHARP_BICUBIC, 40));
	}

	@Test
	public void oneToOneMovingDoesNotFilterOrSharpenOriginalImage()
	{
		BufferedImage source = detailImage();
		WidgetImageScaler scaler = new WidgetImageScaler();
		for (ScalingFilter filter : ScalingFilter.values())
		{
			assertSame(source, scaler.scale(source, 16, 8, filter, 100));
		}
	}

	@Test
	public void unchangedPixelsReuseFilteredImageAndChangedSettingsInvalidateIt()
	{
		WidgetImageScaler scaler = new WidgetImageScaler();
		BufferedImage source = detailImage();
		BufferedImage first = scaler.scale(source, 24, 12, ScalingFilter.SHARP_BICUBIC, 40, true);
		int[] expected = pixels(first);
		assertTrue(scaler.updated());
		assertSame(first, scaler.scale(source, 24, 12, ScalingFilter.SHARP_BICUBIC, 40, false));
		assertFalse(scaler.updated());
		assertArrayEquals(expected, pixels(first));
		scaler.scale(source, 24, 12, ScalingFilter.SHARP_BICUBIC, 60, false);
		assertTrue(scaler.updated());
		scaler.scale(source, 25, 12, ScalingFilter.SHARP_BICUBIC, 60, false);
		assertTrue(scaler.updated());
	}

	@Test
	public void refreshLimitDefersChangesWithoutLosingThemAndDoesNotDelaySettings()
	{
		WidgetImageScaler scaler = new WidgetImageScaler();
		BufferedImage source = detailImage();
		BufferedImage first = scaler.scale(source, 24, 12, ScalingFilter.NEAREST, 0, true, 1);
		int[] expected = pixels(first);
		source.setRGB(8, 4, 0xffff0000);
		scaler.scale(source, 24, 12, ScalingFilter.NEAREST, 0, true, 1);
		assertFalse(scaler.updated());
		assertArrayEquals(expected, pixels(first));
		// No new source change, but the deferred change must still be rendered when unlocked.
		scaler.scale(source, 24, 12, ScalingFilter.NEAREST, 0, false, 0);
		assertTrue(scaler.updated());
		assertFalse(java.util.Arrays.equals(expected, pixels(first)));
		scaler.scale(source, 24, 12, ScalingFilter.BILINEAR, 0, false, 1);
		assertTrue(scaler.updated());
	}

	@Test
	public void translatedSourceSubimageIsFilteredFromItsOwnPixels()
	{
		BufferedImage parent = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
		BufferedImage subimage = parent.getSubimage(6, 5, 8, 8);
		for (int y = 0; y < 8; y++)
		{
			for (int x = 0; x < 8; x++) { subimage.setRGB(x, y, 0xff33cc88); }
		}
		for (ScalingFilter filter : ScalingFilter.values())
		{
			BufferedImage result = new WidgetImageScaler().scale(subimage, 12, 12, filter, 40);
			for (int pixel : pixels(result)) { assertEquals(0xff33cc88, pixel); }
		}
	}

	@Test
	public void defaultFilterKeepsThePreviousSmoothSetting()
	{
		MinimapResizeConfig oldSmooth = new MinimapResizeConfig()
		{
			@Override public boolean smooth() { return true; }
		};
		assertEquals(ScalingFilter.BILINEAR, oldSmooth.filter());
		assertEquals(ScalingFilter.NEAREST, new MinimapResizeConfig() {}.filter());
	}

	@Test
	public void hybridKeepsIntegerPixelsAndOnlyBlendsFractionalBoundaries()
	{
		BufferedImage source = new BufferedImage(4, 1, BufferedImage.TYPE_INT_ARGB);
		for (int x = 0; x < 4; x++) { source.setRGB(x, 0, x % 2 == 0 ? 0xff000000 : 0xffffffff); }
		WidgetImageScaler scaler = new WidgetImageScaler();
		for (int multiple : new int[]{2, 3})
		{
			int[] nearest = pixels(scaler.scale(source, 4 * multiple, multiple, ScalingFilter.NEAREST, 0));
			assertArrayEquals(nearest, pixels(scaler.scale(source, 4 * multiple, multiple, ScalingFilter.HYBRID, 0)));
		}
		// Boundary pixels retain 75% of their containing source pixel instead of
		// being flattened to 50/50 grey before the renderer's second filter pass.
		assertArrayEquals(new int[]{0xff000000, 0xffbfbfbf, 0xffffffff, 0xff000000, 0xffbfbfbf, 0xffffffff},
			pixels(scaler.scale(source, 6, 1, ScalingFilter.HYBRID, 0)));
	}

	@Test
	public void mitchellSoftensAnImpulseWhileCatmullRomRetainsItsPeak()
	{
		BufferedImage source = new BufferedImage(3, 1, BufferedImage.TYPE_INT_ARGB);
		source.setRGB(0, 0, 0xff000000);
		source.setRGB(1, 0, 0xffffffff);
		source.setRGB(2, 0, 0xff000000);
		WidgetImageScaler scaler = new WidgetImageScaler();
		assertEquals(0xffffffff, scaler.scale(source, 9, 1, ScalingFilter.BICUBIC, 0).getRGB(4, 0));
		assertEquals(0xffe3e3e3, scaler.scale(source, 9, 1, ScalingFilter.MITCHELL, 0).getRGB(4, 0));
	}

	@Test
	public void lanczosReductionSuppressesHighFrequencyAliasing()
	{
		BufferedImage source = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < 32; y++)
		{
			for (int x = 0; x < 32; x++) { source.setRGB(x, y, (x + y) % 2 == 0 ? 0xff000000 : 0xffffffff); }
		}
		BufferedImage result = new WidgetImageScaler().scale(source, 17, 17, ScalingFilter.LANCZOS, 0);
		for (int y = 3; y < 14; y++)
		{
			for (int x = 3; x < 14; x++)
			{
				assertTrue("Checkerboard should reduce to grey", Math.abs(128 - (result.getRGB(x, y) & 255)) <= 2);
			}
		}
	}

	@Test
	public void xbrSmoothsDiagonalStairsWithoutBlurringFlatInteriors()
	{
		BufferedImage source = new BufferedImage(9, 9, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < 9; y++)
		{
			for (int x = 0; x < 9; x++) { source.setRGB(x, y, x >= y ? 0xffffffff : 0xff000000); }
		}
		int[] original = pixels(source);
		WidgetImageScaler scaler = new WidgetImageScaler();
		for (int size : new int[]{14, 18, 23})
		{
			BufferedImage result = scaler.scale(source, size, size, ScalingFilter.XBR, 0);
			boolean blendedEdge = false;
			for (int p : pixels(result))
			{
				assertEquals(255, p >>> 24);
				blendedEdge |= (p & 255) > 0 && (p & 255) < 255;
			}
			assertTrue("xBR must actually reconstruct the diagonal at " + size, blendedEdge);
			assertEquals(0xffffffff, result.getRGB(size - 2, 1));
			assertEquals(0xff000000, result.getRGB(1, size - 2));
		}
		assertArrayEquals(original, pixels(source));
	}

	@Test
	public void xbrReductionUsesBicubicIncludingMixedAxisResizing()
	{
		BufferedImage source = detailImage();
		WidgetImageScaler scaler = new WidgetImageScaler();
		for (int[] size : new int[][]{{8, 4}, {24, 4}, {8, 12}})
		{
			int[] expected = pixels(scaler.scale(source, size[0], size[1], ScalingFilter.BICUBIC, 0));
			assertArrayEquals(expected, pixels(scaler.scale(source, size[0], size[1], ScalingFilter.XBR, 0)));
		}
	}

	@Test
	public void switchingAllFiltersAndSizesDoesNotReuseWrongKernelOrOldPixels()
	{
		BufferedImage source = detailImage();
		WidgetImageScaler scaler = new WidgetImageScaler();
		for (int[] size : new int[][]{{24, 12}, {11, 5}, {24, 12}})
		{
			for (ScalingFilter filter : ScalingFilter.values())
			{
				int[] expected = pixels(new WidgetImageScaler().scale(source, size[0], size[1], filter, 40));
				assertArrayEquals(filter.toString(), expected, pixels(scaler.scale(source, size[0], size[1], filter, 40, false, 1)));
				assertTrue(scaler.updated());
				scaler.scale(source, size[0], size[1], filter, 40, false, 1);
				assertFalse(scaler.updated());
			}
		}
		// Exercise xBR's reused classification buffers after every edge disappears.
		BufferedImage blank = new BufferedImage(16, 8, BufferedImage.TYPE_INT_ARGB);
		assertArrayEquals(new int[24 * 12], pixels(scaler.scale(blank, 24, 12, ScalingFilter.XBR, 0)));
	}

	@Test
	public void everyFilterPreservesTransparentEdgesAndPremultipliedChannelBounds()
	{
		BufferedImage source = new BufferedImage(7, 7, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < 7; y++)
		{
			for (int x = 0; x < 7; x++)
			{
				source.setRGB(x, y, x > y ? (x % 2 == 0 ? 0x80ffffff : 0xffffffff) : 0x00ff0000);
			}
		}
		for (ScalingFilter filter : ScalingFilter.values())
		{
			for (int size : new int[]{4, 11, 14})
			{
				BufferedImage result = new WidgetImageScaler().scale(source, size, size, filter, 40);
				for (int p : ((DataBufferInt) result.getRaster().getDataBuffer()).getData())
				{
					int alpha = p >>> 24;
					assertEquals(filter + ": hidden red must not bleed into white", alpha, (p >>> 16) & 255);
					assertEquals(alpha, (p >>> 8) & 255);
					assertEquals(alpha, p & 255);
				}
			}
		}
	}

	@Test
	public void singlePixelAndThinImagesWorkWithEveryFilter()
	{
		for (ScalingFilter filter : ScalingFilter.values())
		{
			WidgetImageScaler scaler = new WidgetImageScaler();
			for (int[] size : new int[][]{{1, 1}, {1, 8}, {8, 1}})
			{
				BufferedImage source = new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_ARGB_PRE);
				java.util.Arrays.fill(((DataBufferInt) source.getRaster().getDataBuffer()).getData(), 0x80402010);
				for (int target : new int[]{1, 13})
				{
					BufferedImage result = scaler.scale(source, target, target, filter, 40);
					for (int p : ((DataBufferInt) result.getRaster().getDataBuffer()).getData()) { assertEquals(0x80402010, p); }
				}
			}
		}
	}
}
