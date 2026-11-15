package com.minimapresize;

import com.sun.management.ThreadMXBean;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import java.util.List;
import java.util.stream.Collectors;

/** Repeatable CPU benchmark of the real capture/filter/input path, without a logged-in client. */
public final class ScalerBenchmark
{
	private static volatile int sink;
	private static boolean gpu;
	private static boolean animatedInventory;
	private static boolean overheads;
	private static boolean legacyCapture;
	private static boolean legacyBlit;
	private static boolean rotatingMinimap;
	private static int scale = 150;
	private static int canvasWidth = 1280;
	private static int canvasHeight = 800;

	private static final class Region
	{
		final WidgetRegion kind;
		final Rectangle source;
		final MinimapTransform transform;
		final MinimapCompositor compositor = new MinimapCompositor();
		final WidgetImageScaler scaler = new WidgetImageScaler();
		final CachedUiBlitter blitter = new CachedUiBlitter();
		final int[] content;
		final int[] mapTexture;
		MinimapInputFrame input;
		BufferedImage foreground;
		int updates;

		Region(WidgetRegion kind, Rectangle source)
		{
			this.kind = kind;
			source = new Rectangle(source);
			source.translate(canvasWidth - 1280, canvasHeight - 800);
			this.source = source;
			transform = MinimapTransform.fit(source, scale, 0, 0, canvasWidth, canvasHeight,
				kind != WidgetRegion.MINIMAP);
			content = new int[source.width * source.height];
			for (int y = 0; y < source.height; y++)
			{
				for (int x = 0; x < source.width; x++)
				{
					if (x > 3 && x < source.width - 4 && y > 3 && y < source.height - 4)
					{
						content[y * source.width + x] = 0xff000000 | (((x * 17 + y * 3) & 255) * 0x010101);
						if (gpu && kind == WidgetRegion.SIDE_PANEL) { content[y * source.width + x] = 0x6a303030; }
					}
				}
			}
			mapTexture = content.clone();
		}
	}

	private static void frame(BufferedImage image, Graphics2D graphics, Region[] regions, ScalingFilter filter,
		int tick, UiUnderlay underlay, List<Rectangle> sources)
	{
		int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
		Arrays.fill(pixels, 0);
		if (gpu && overheads)
		{
			for (Region region : regions)
			{
				if (region.kind != WidgetRegion.SIDE_PANEL) { continue; }
				for (int y = 4; y < region.source.height - 4; y += 8)
				{
					int x = 4 + (tick + y) % (region.source.width - 44);
					int index = (region.source.y + y) * image.getWidth() + region.source.x + x;
					Arrays.fill(pixels, index, index + 36, 0xffffffff);
				}
			}
		}
		if (gpu && !legacyCapture) { underlay.captureAndClear(image, sources); }
		for (Region region : regions)
		{
			region.compositor.capture(image, region.source, gpu);
			// Animated minimap; other UI regions change at a slower rate, like real item/icon content.
			region.content[region.content.length / 2] = 0xff000000 | ((region.kind == WidgetRegion.MINIMAP ? tick : tick / 80) & 255);
			if (rotatingMinimap && region.kind == WidgetRegion.MINIMAP)
			{
				// Camera movement changes the entire circular map, not just one pixel.
				double angle = tick * 0.025, cos = Math.cos(angle), sin = Math.sin(angle);
				for (int y = 0; y < 150; y++)
				{
					for (int x = 0; x < 150; x++)
					{
						int dx = x - 75, dy = y - 75;
						if (dx * dx + dy * dy >= 75 * 75) { continue; }
						int tx = Math.floorMod((int) (cos * dx - sin * dy) + 120, region.source.width);
						int ty = Math.floorMod((int) (sin * dx + cos * dy) + 90, region.source.height);
						region.content[(y + 10) * region.source.width + x + 50] = region.mapTexture[ty * region.source.width + tx] | 0xff000000;
					}
				}
			}
			if ((animatedInventory || overheads) && region.kind == WidgetRegion.SIDE_PANEL)
			{
				for (int y = 20; y < 52; y++)
				{
					for (int x = 20; x < 56; x++)
					{
						int animation = animatedInventory ? tick : tick / 80;
						region.content[y * region.source.width + x] = 0xff000000 | (((x * 17 + y * 3 + animation) & 255) * 0x010101);
					}
				}
			}
			for (int y = 0; y < region.source.height; y++)
			{
				int src = y * region.source.width;
				int dst = (region.source.y + y) * image.getWidth() + region.source.x;
				if (gpu)
				{
					for (int x = 0; x < region.source.width; x++)
					{
						pixels[dst + x] = UiAlpha.over(region.content[src + x], pixels[dst + x]);
					}
				}
				else { System.arraycopy(region.content, src, pixels, dst, region.source.width); }
			}
			region.foreground = region.compositor.extractAndRestore(region.kind == WidgetRegion.MINIMAP
				? new Ellipse2D.Double(region.source.x + 50, region.source.y + 10, 150, 150) : null);
		}
		underlay.restore(image);
		for (Region region : regions)
		{
			Rectangle destination = region.transform.destination();
			BufferedImage scaled = region.scaler.scale(region.foreground, destination.width, destination.height, filter, 40, region.compositor.changed());
			if (region.scaler.updated()) { region.updates++; }
			if (legacyBlit) { UiBlitter.draw(image, scaled, destination.x, destination.y, graphics, gpu); }
			else { region.blitter.draw(image, scaled, destination.x, destination.y, graphics, gpu, region.scaler.updated()); }
			region.input = new MinimapInputFrame(region.kind, region.transform, region.foreground, region.input, region.compositor.maskChanged());
			sink ^= scaled.getRGB(destination.width / 2, destination.height / 2);
		}
	}

	private static void run(String label, Region[] regions, ScalingFilter filter)
	{
		int warmup = Math.max(1, Integer.getInteger("benchmark.warmup", 100));
		int frames = Math.max(1, Integer.getInteger("benchmark.frames", 250));
		BufferedImage image = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		UiUnderlay underlay = new UiUnderlay();
		List<Rectangle> sources = Arrays.stream(regions).map(region -> region.source).collect(Collectors.toList());
		try
		{
			for (int i = 0; i < warmup; i++) { frame(image, graphics, regions, filter, i, underlay, sources); }
			for (Region region : regions) { region.updates = 0; }
			long[] timings = new long[frames];
			ThreadMXBean memory = (ThreadMXBean) ManagementFactory.getThreadMXBean();
			long thread = Thread.currentThread().getId();
			long bytes = memory.getThreadAllocatedBytes(thread);
			long start = System.nanoTime();
			for (int i = 0; i < frames; i++)
			{
				long before = System.nanoTime();
				frame(image, graphics, regions, filter, warmup + i, underlay, sources);
				timings[i] = System.nanoTime() - before;
			}
			long elapsed = System.nanoTime() - start;
			long allocated = memory.getThreadAllocatedBytes(thread) - bytes;
			int updates = Arrays.stream(regions).mapToInt(region -> region.updates).sum();
			Arrays.sort(timings);
			System.out.printf(Locale.ROOT, "%s / %s: %.3f ms/frame; p95 %.3f ms; p99 %.3f ms; %.1f KiB/frame; %d/%d filter updates%n",
				label, filter, elapsed / (frames * 1_000_000.0), timings[(int) Math.ceil(frames * 0.95) - 1] / 1_000_000.0,
				timings[(int) Math.ceil(frames * 0.99) - 1] / 1_000_000.0, allocated / (frames * 1024.0), updates, frames * regions.length);
		}
		finally
		{
			graphics.dispose();
		}
	}

	public static void main(String[] args)
	{
		gpu = Boolean.getBoolean("benchmark.gpu");
		animatedInventory = Boolean.getBoolean("benchmark.animatedInventory");
		overheads = Boolean.getBoolean("benchmark.overheads");
		legacyCapture = Boolean.getBoolean("benchmark.legacyCapture");
		legacyBlit = Boolean.getBoolean("benchmark.legacyBlit");
		rotatingMinimap = Boolean.getBoolean("benchmark.rotatingMinimap");
		scale = Integer.getInteger("benchmark.scale", 150);
		canvasWidth = Integer.getInteger("benchmark.width", 1280);
		canvasHeight = Integer.getInteger("benchmark.height", 800);
		System.out.println("Synthetic CPU capture/filter/input benchmark (not in-game FPS; excludes native widget traversal).");
		System.out.println("Native GPU alpha: " + gpu + "; animated inventory slot: " + animatedInventory + "; scale: " + scale);
		System.out.println("Moving overheads: " + overheads + "; legacy mixed-background capture: " + legacyCapture);
		System.out.println("Legacy pixel-by-pixel blit: " + legacyBlit);
		System.out.println("Rotating minimap: " + rotatingMinimap);
		System.out.println("Canvas: " + canvasWidth + "x" + canvasHeight);
		for (ScalingFilter filter : ScalingFilter.values())
		{
			String selected = System.getProperty("benchmark.filter");
			if (selected != null && !filter.name().equalsIgnoreCase(selected)) { continue; }
			if (Boolean.getBoolean("benchmark.inventoryOnly"))
			{
				run("Inventory alone", new Region[]{new Region(WidgetRegion.SIDE_PANEL, new Rectangle(1060, 400, 200, 260))}, filter);
				continue;
			}
			run("Minimap", new Region[]{new Region(WidgetRegion.MINIMAP, new Rectangle(1040, 0, 240, 180))}, filter);
			if (Boolean.getBoolean("benchmark.minimapOnly")) { continue; }
			run("Both tab bars", new Region[]{
				new Region(WidgetRegion.UPPER_TABS, new Rectangle(1040, 360, 240, 40)),
				new Region(WidgetRegion.LOWER_TABS, new Rectangle(1040, 660, 240, 40))}, filter);
			run("All four", new Region[]{
				new Region(WidgetRegion.MINIMAP, new Rectangle(1040, 0, 240, 180)),
				new Region(WidgetRegion.UPPER_TABS, new Rectangle(1040, 360, 240, 40)),
				new Region(WidgetRegion.LOWER_TABS, new Rectangle(1040, 660, 240, 40)),
				new Region(WidgetRegion.SIDE_PANEL, new Rectangle(1060, 400, 200, 260))}, filter);
			run("Inventory alone", new Region[]{new Region(WidgetRegion.SIDE_PANEL, new Rectangle(1060, 400, 200, 260))}, filter);
		}
	}
}
