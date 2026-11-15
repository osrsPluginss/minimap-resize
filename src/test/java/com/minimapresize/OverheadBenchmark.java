package com.minimapresize;

import com.sun.management.ThreadMXBean;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

/** Measures the native overhead capture/recognition/scaling path, independently of in-game FPS. */
public final class OverheadBenchmark
{
	private static volatile int sink;
	private static final boolean SPREAD = Boolean.getBoolean("benchmark.spreadOverheads");
	private static final MinimapResizeConfig CONFIG = new MinimapResizeConfig()
	{
		@Override public int overheadScale() { return 140; }
		@Override public int overheadTextScale() { return 150; }
		@Override public ScalingFilter filter() { return ScalingFilter.XBR; }
		@Override public int scalingFps() { return 30; }
	};

	private static void frame(BufferedImage image, Graphics2D graphics, OverheadScaler scaler,
		OverheadContext context, int tick, boolean populated)
	{
		Arrays.fill(PackedPixels.nativePixels(image).pixels, 0);
		scaler.newFrame();
		scaler.begin(image, new Rectangle(image.getWidth(), image.getHeight()));
		context.hints.clear();
		if (populated)
		{
			int x = image.getWidth() / 2 + tick % 160, y = image.getHeight() / 2 + tick % 60;
			graphics.setColor(Color.GREEN);
			graphics.fillRect(x, y, 30, 5);
			graphics.setColor(Color.BLUE);
			graphics.fillRect(x + 8, y - 30, 12, 12);
			graphics.setColor(Color.YELLOW);
			graphics.fillRect(x - 30, y - 50, 90, 8);
			context.hints.add(new OverheadElements.Element(new Rectangle(x - 30, y - 50, 90, 8), OverheadElements.Kind.TEXT));
			context.hints.add(new OverheadElements.Element(new Rectangle(x, y, 30, 5), OverheadElements.Kind.HEALTH_BAR));
			if (SPREAD)
			{
				// Distant actors leave mostly empty rows inside the shared recognition bounds.
				graphics.setColor(Color.BLUE);
				graphics.fillRect(40 + tick % 20, 40, 12, 12);
				graphics.fillRect(image.getWidth() - 80, image.getHeight() - 80, 12, 12);
			}
		}
		scaler.finish(image, graphics, CONFIG, context);
		sink ^= image.getRGB(image.getWidth() / 2, image.getHeight() / 2);
	}

	private static void run(int width, int height, boolean populated)
	{
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		BufferedImage icon = new BufferedImage(12, 12, BufferedImage.TYPE_INT_ARGB);
		Graphics2D iconGraphics = icon.createGraphics();
		iconGraphics.setColor(Color.BLUE);
		iconGraphics.fillRect(0, 0, 12, 12);
		iconGraphics.dispose();
		OverheadContext context = new OverheadContext();
		context.templates.add(new OverheadElements.Template(icon, OverheadElements.Kind.PRAYER));
		OverheadScaler scaler = new OverheadScaler();
		Graphics2D graphics = image.createGraphics();
		int warmup = 200, frames = 400;
		try
		{
			for (int i = 0; i < warmup; i++) { frame(image, graphics, scaler, context, i, populated); }
			long[] timings = new long[frames];
			ThreadMXBean memory = (ThreadMXBean) ManagementFactory.getThreadMXBean();
			long thread = Thread.currentThread().getId(), bytes = memory.getThreadAllocatedBytes(thread);
			long start = System.nanoTime();
			for (int i = 0; i < frames; i++)
			{
				long before = System.nanoTime();
				frame(image, graphics, scaler, context, warmup + i, populated);
				timings[i] = System.nanoTime() - before;
			}
			long elapsed = System.nanoTime() - start, allocated = memory.getThreadAllocatedBytes(thread) - bytes;
			Arrays.sort(timings);
			System.out.printf(Locale.ROOT, "%dx%d / %s: %.3f ms/frame; p95 %.3f ms; p99 %.3f ms; %.1f KiB/frame%n",
				width, height, populated ? "moving indicators" : "empty scene", elapsed / (frames * 1_000_000.0),
				timings[(int) (frames * 0.95) - 1] / 1_000_000.0, timings[(int) (frames * 0.99) - 1] / 1_000_000.0,
				allocated / (frames * 1024.0));
		}
		finally { graphics.dispose(); scaler.clear(); }
	}

	public static void main(String[] args)
	{
		System.out.println("Synthetic overhead CPU benchmark; xBR, 140-150% sizes, 30 scaling updates/sec. Not in-game FPS.");
		System.out.println("Widely separated indicators: " + SPREAD);
		for (int[] size : new int[][]{{1920, 1080}, {2560, 1440}})
		{
			run(size[0], size[1], false);
			run(size[0], size[1], true);
		}
	}
}
