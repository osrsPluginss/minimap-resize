package com.minimapresize;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.runelite.client.events.PluginMessage;
import org.junit.Test;
import static org.junit.Assert.*;

public class MovementOverheadCompatibilityTest
{
	@Test
	public void allFourMovementIndicatorsUseTheirOwnSettingAtThePresentedPosition()
	{
		MinimapResizeConfig config = new MinimapResizeConfig()
		{
			@Override public int healthBarScale() { return 200; }
			@Override public int hitsplatScale() { return 50; }
			@Override public int prayerIconScale() { return 150; }
			@Override public int skullIconScale() { return 300; }
		};
		MovementOverheadCompatibility compatibility = new MovementOverheadCompatibility();
		assertDraw(compatibility, config, "HEALTH_BAR", 0, new Rectangle(100, 100, 10, 8), new Rectangle(95, 92, 20, 16));
		assertDraw(compatibility, config, "HITSPLAT", 3, new Rectangle(200, 100, 10, 8), new Rectangle(202, 104, 5, 4));
		assertDraw(compatibility, config, "PRAYER", 0, new Rectangle(300, 100, 10, 8), new Rectangle(298, 96, 15, 12));
		assertDraw(compatibility, config, "SKULL", 0, new Rectangle(400, 100, 10, 8), new Rectangle(390, 84, 30, 24));
		// Move on the next frame: the source pixels can be unchanged while the smooth position changes.
		assertDraw(compatibility, config, "PRAYER", 0, new Rectangle(325, 120, 10, 8), new Rectangle(323, 116, 15, 12));
	}

	@Test
	public void fourHitsplatSlotsKeepSeparateArtAndNumbersAndSurviveNativePassWithoutDoubleScaling()
	{
		BufferedImage target = new BufferedImage(500, 200, BufferedImage.TYPE_INT_ARGB_PRE);
		Graphics2D graphics = target.createGraphics();
		MinimapResizeConfig config = new MinimapResizeConfig() { @Override public int hitsplatScale() { return 200; } };
		MovementOverheadCompatibility compatibility = new MovementOverheadCompatibility();
		try
		{
			Color[] colours = {Color.RED, Color.BLUE, Color.GREEN, Color.MAGENTA};
			for (int slot = 0; slot < 4; slot++)
			{
				Rectangle bounds = new Rectangle(50 + slot * 100, 100, 10, 8);
				Color colour = colours[slot];
				AtomicInteger handled = new AtomicInteger();
				compatibility.render(message("HITSPLAT", slot, graphics, bounds, g -> {
					g.setColor(colour); g.fill(bounds);
					g.setColor(Color.WHITE); g.fillRect(bounds.x + 4, bounds.y + 2, 2, 3);
				}, handled), config, target, true);
				assertEquals(1, handled.get());
				assertEquals(colour.getRGB(), target.getRGB(bounds.x - 5, 92));
				assertEquals(Color.WHITE.getRGB(), target.getRGB(bounds.x + 3, 96));
			}
			int[] before = PackedPixels.nativePixels(target).pixels.clone();
			OverheadScaler nativePass = new OverheadScaler();
			nativePass.begin(target, new Rectangle(500, 200));
			nativePass.finish(target, graphics, config, new OverheadContext());
			assertArrayEquals(before, PackedPixels.nativePixels(target).pixels);
		}
		finally { graphics.dispose(); }
	}

	@Test
	public void normalSizeSpeechAndUnrelatedMessagesLeaveTheOriginalPainterInControl()
	{
		MovementOverheadCompatibility compatibility = new MovementOverheadCompatibility();
		MinimapResizeConfig config = new MinimapResizeConfig() {};
		BufferedImage target = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB_PRE);
		Graphics2D graphics = target.createGraphics();
		AtomicInteger handled = new AtomicInteger(), calls = new AtomicInteger();
		try
		{
			Consumer<Graphics2D> renderer = g -> calls.incrementAndGet();
			compatibility.render(message("PRAYER", 0, graphics, new Rectangle(20, 20, 10, 8), renderer, handled), config, target, true);
			MinimapResizeConfig larger = new MinimapResizeConfig() { @Override public int overheadTextScale() { return 200; } };
			compatibility.render(message("TEXT", 0, graphics, new Rectangle(20, 20, 10, 8), renderer, handled), larger, target, true);
			compatibility.render(new PluginMessage("other", "render-overhead", Map.of()), config, target, true);
			assertEquals(0, handled.get());
			assertEquals(0, calls.get());
		}
		finally { graphics.dispose(); }
	}

	private static void assertDraw(MovementOverheadCompatibility compatibility, MinimapResizeConfig config,
		String kind, int slot, Rectangle source, Rectangle expected)
	{
		BufferedImage target = new BufferedImage(500, 200, BufferedImage.TYPE_INT_ARGB_PRE);
		Graphics2D graphics = target.createGraphics();
		AtomicInteger handled = new AtomicInteger();
		try
		{
			compatibility.render(message(kind, slot, graphics, source, g -> { g.setColor(Color.RED); g.fill(source); }, handled), config, target, true);
			assertEquals(1, handled.get());
			Rectangle actual = null;
			for (int y = 0; y < target.getHeight(); y++)
			{
				for (int x = 0; x < target.getWidth(); x++)
				{
					if (target.getRGB(x, y) == 0) { continue; }
					if (actual == null) { actual = new Rectangle(x, y, 1, 1); }
					else { actual.add(new Rectangle(x, y, 1, 1)); }
				}
			}
			assertEquals(kind, expected, actual);
		}
		finally { graphics.dispose(); }
	}

	@Test
	public void authoredMovingPrayersUseHdSourceAtNormalAndEnlargedSizesWithoutNativeCapture()
	{
		for (int percent : new int[]{100, 150, 200, 400})
		{
			BufferedImage target = new BufferedImage(500, 250, BufferedImage.TYPE_INT_ARGB_PRE);
			BufferedImage art = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB_PRE);
			Graphics2D painter = art.createGraphics(); painter.setColor(Color.GREEN); painter.fillRect(0, 0, 128, 128); painter.dispose();
			Graphics2D graphics = target.createGraphics();
			AtomicInteger handled = new AtomicInteger();
			MovementOverheadCompatibility compatibility = new MovementOverheadCompatibility();
			try
			{
				for (int x : new int[]{100, 140})
				{
					Rectangle source = new Rectangle(x, 100, 10, 8);
					PluginMessage packet = message("PRAYER", 0, graphics, source, g -> fail("Do not capture an HD prayer through native resolution"), handled);
					Map<String, Object> data = new java.util.HashMap<>(packet.getData()); data.put("artwork", art);
					compatibility.render(new PluginMessage(packet.getNamespace(), packet.getName(), data), new MinimapResizeConfig()
					{ @Override public int prayerIconScale() { return percent; } @Override public int scalingFps() { return 1; } }, target, true);
					int width = (int) Math.round(10 * percent / 100.0), height = (int) Math.round(8 * percent / 100.0);
					assertEquals(Color.GREEN.getRGB(), target.getRGB(x + (10 - width) / 2, 108 - height));
				}
				assertEquals(2, handled.get());
				int[] before = PackedPixels.nativePixels(target).pixels.clone();
				OverheadScaler nativePass = new OverheadScaler();
				nativePass.begin(target, new Rectangle(500, 250));
				nativePass.finish(target, graphics, new MinimapResizeConfig() {}, new OverheadContext());
				assertArrayEquals("The moving overhead is an underlay, not a second native prayer", before, PackedPixels.nativePixels(target).pixels);
			}
			finally { graphics.dispose(); compatibility.clear(); }
		}
	}

	private static PluginMessage message(String kind, int slot, Graphics2D graphics, Rectangle bounds,
		Consumer<Graphics2D> renderer, AtomicInteger handled)
	{
		return new PluginMessage("responsive-movement", "render-overhead", Map.of("kind", kind, "slot", slot,
			"graphics", graphics, "bounds", bounds, "renderer", renderer, "handled", (Runnable) () -> handled.incrementAndGet()));
	}
}
