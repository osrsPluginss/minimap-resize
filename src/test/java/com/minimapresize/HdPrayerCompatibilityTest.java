package com.minimapresize;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.function.BiFunction;
import net.runelite.client.events.PluginMessage;
import org.junit.Test;
import static org.junit.Assert.*;

public class HdPrayerCompatibilityTest
{
	@Test public void registrationAndOutOfOrderReleasePreserveTheCurrentProvider()
	{
		HdPrayerCompatibility compatibility = new HdPrayerCompatibility();
		Object old = new Object(), current = new Object();
		BufferedImage first = solid(64, Color.RED), second = solid(64, Color.GREEN);
		compatibility.update(provider(old, true, (archive, file) -> first));
		assertSame(first, compatibility.image(440, 4));
		compatibility.update(provider(current, true, (archive, file) -> second));
		compatibility.update(provider(old, false, (archive, file) -> first));
		assertSame(second, compatibility.image(440, 4));
		compatibility.update(provider(current, false, (archive, file) -> second));
		assertFalse(compatibility.active()); assertNull(compatibility.image(440, 4));
	}

	@Test public void nativePrayerReplacementRetainsBottomCentreAndSceneLabelsAtAllSizes()
	{
		for (int percent : new int[]{50, 100, 150, 200, 400})
		{
			BufferedImage target = new BufferedImage(200, 150, BufferedImage.TYPE_INT_ARGB_PRE);
			Graphics2D graphics = target.createGraphics();
			OverheadScaler scaler = new OverheadScaler();
			OverheadContext context = new OverheadContext();
			BufferedImage nativeIcon = solid(8, Color.BLUE);
			OverheadElements.Template template = new OverheadElements.Template(nativeIcon, OverheadElements.Kind.PRAYER);
			template.artwork = solid(128, Color.RED); context.templates.add(template);
			try
			{
				target.setRGB(15, 25, Color.YELLOW.getRGB());
				scaler.begin(target, new Rectangle(10, 20, 180, 120));
				graphics.drawImage(nativeIcon, 60, 70, null);
				scaler.finish(target, graphics, new MinimapResizeConfig() { @Override public int prayerIconScale() { return percent; } }, context);
				int size = (int) Math.round(8 * percent / 100.0);
				Rectangle expected = new Rectangle(60 + (8 - size) / 2, 78 - size, size, size);
				assertEquals("Bottom-centred HD placement at " + percent, expected, bounds(target, Color.RED.getRGB()));
				assertNull("The original prayer must be removed once", bounds(target, Color.BLUE.getRGB()));
				assertEquals(Color.YELLOW.getRGB(), target.getRGB(15, 25));
			}
			finally { graphics.dispose(); scaler.clear(); }
		}
	}

	@Test public void lowRefreshLimitsDoNotFreezePrayerChangesAndOccludedIconsRemainNative()
	{
		BufferedImage target = new BufferedImage(200, 150, BufferedImage.TYPE_INT_ARGB_PRE);
		Graphics2D graphics = target.createGraphics();
		OverheadScaler scaler = new OverheadScaler();
		OverheadContext context = new OverheadContext();
		BufferedImage nativeIcon = solid(8, Color.BLUE);
		OverheadElements.Template template = new OverheadElements.Template(nativeIcon, OverheadElements.Kind.PRAYER);
		context.templates.add(template);
		MinimapResizeConfig config = new MinimapResizeConfig()
		{ @Override public int prayerIconScale() { return 200; } @Override public int scalingFps() { return 1; } };
		try
		{
			for (Color colour : new Color[]{Color.RED, Color.GREEN})
			{
				graphics.setComposite(AlphaComposite.Clear); graphics.fillRect(0, 0, 200, 150); graphics.setComposite(AlphaComposite.SrcOver);
				scaler.newFrame(); template.artwork = solid(128, colour);
				scaler.begin(target, new Rectangle(200, 150)); graphics.drawImage(nativeIcon, 60, 70, null);
				scaler.finish(target, graphics, config, context);
				assertEquals(colour.getRGB(), target.getRGB(60, 70));
			}
			graphics.setComposite(AlphaComposite.Clear); graphics.fillRect(0, 0, 200, 150); graphics.setComposite(AlphaComposite.SrcOver);
			scaler.newFrame(); scaler.begin(target, new Rectangle(200, 150)); graphics.drawImage(nativeIcon, 60, 70, null);
			target.setRGB(63, 73, Color.YELLOW.getRGB());
			scaler.finish(target, graphics, config, context);
			assertEquals(Color.BLUE.getRGB(), target.getRGB(60, 70));
			assertEquals(Color.YELLOW.getRGB(), target.getRGB(63, 73));
			assertNull(bounds(target, Color.GREEN.getRGB()));
		}
		finally { graphics.dispose(); scaler.clear(); }
	}

	@Test public void authoredSourcesShareSamplesAndPreserveDetailAbsentFromTheNativeSprite()
	{
		AuthoredOverheadImages images = new AuthoredOverheadImages();
		BufferedImage source = solid(128, Color.RED);
		Graphics2D graphics = source.createGraphics(); graphics.setColor(Color.WHITE); graphics.fillRect(61, 0, 6, 128); graphics.dispose();
		BufferedImage sampled = images.image(source, 8, 8);
		assertSame(sampled, images.image(source, 8, 8));
		boolean detail = false;
		for (int pixel : sampled.getRGB(0, 0, 8, 8, null, 0, 8)) { detail |= (pixel & 255) > 0; }
		assertTrue("The full-resolution highlight must survive downsampling", detail);
		BufferedImage changed = images.image(solid(128, Color.GREEN), 8, 8);
		assertEquals(Color.GREEN.getRGB(), changed.getRGB(4, 4)); assertNotSame(sampled, changed);
	}

	private static PluginMessage provider(Object owner, boolean enabled, BiFunction<Integer, Integer, BufferedImage> provider)
	{
		return new PluginMessage("hd-ui", "overhead-provider", Map.of("owner", owner, "enabled", enabled, "provider", provider));
	}
	private static BufferedImage solid(int size, Color colour)
	{
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB_PRE);
		Graphics2D graphics = image.createGraphics(); graphics.setColor(colour); graphics.fillRect(0, 0, size, size); graphics.dispose(); return image;
	}
	private static Rectangle bounds(BufferedImage image, int colour)
	{
		Rectangle result = null;
		for (int y = 0; y < image.getHeight(); y++) { for (int x = 0; x < image.getWidth(); x++)
		{ if (image.getRGB(x, y) == colour) { if (result == null) { result = new Rectangle(x, y, 1, 1); } else { result.add(new Rectangle(x, y, 1, 1)); } } } }
		return result;
	}
}
