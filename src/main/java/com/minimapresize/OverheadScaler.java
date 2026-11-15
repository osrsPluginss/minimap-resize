package com.minimapresize;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.ToIntFunction;

/** Isolates the native overhead pass between ABOVE_SCENE and UNDER_WIDGETS. */
final class OverheadScaler
{
	private final UiUnderlay underlay = new UiUnderlay();
	private final List<Tile> tiles = new ArrayList<>();
	private Rectangle viewport;
	private BufferedImage frame;
	private int[] transparentRow;
	private final OverheadElements elements = new OverheadElements();
	private final AuthoredOverheadImages artwork = new AuthoredOverheadImages();

	private static final class Tile
	{
		BufferedImage image;
		boolean changed;
		BufferedImage authoredOutput;
		final WidgetImageScaler scaler = new WidgetImageScaler();
		final CachedUiBlitter blitter = new CachedUiBlitter();
	}

	void begin(BufferedImage image, Rectangle viewport)
	{
		reset(image);
		this.viewport = viewport.intersection(new Rectangle(image.getWidth(), image.getHeight()));
		if (this.viewport.isEmpty() || PackedPixels.nativePixels(image) == null) { return; }
		frame = image;
		underlay.captureAndClear(image, Collections.singletonList(this.viewport));
	}

	void finish(BufferedImage image, Graphics2D graphics, int percent, ScalingFilter filter, int sharpening, int fps)
	{
		if (frame != image || frame == null) { return; }
		BufferedImage layer = image.getSubimage(viewport.x, viewport.y, viewport.width, viewport.height);
		if (transparentRow == null || transparentRow.length != viewport.width) { transparentRow = new int[viewport.width]; }
		List<Rectangle> clusters = clusters(layer, transparentRow);
		// A pathological particle/text storm should retain its native image rather
		// than allocate thousands of independent scaling caches.
		if (clusters == null)
		{
			reset(image);
			return;
		}
		List<OverheadElements.Element> identified = new ArrayList<>();
		for (Rectangle rect : clusters) { identified.add(new OverheadElements.Element(rect, OverheadElements.Kind.NATIVE)); }
		finish(image, graphics, identified, element -> percent, filter, sharpening, fps);
	}

	void finish(BufferedImage image, Graphics2D graphics, MinimapResizeConfig config, OverheadContext context)
	{
		finish(image, graphics, config, context, () -> { });
	}

	void finishPrayers(BufferedImage image, Graphics2D graphics, OverheadContext context)
	{
		if (frame != image || frame == null) { return; }
		BufferedImage layer = image.getSubimage(viewport.x, viewport.y, viewport.width, viewport.height);
		Rectangle content = elements.contentBounds(layer);
		if (content.isEmpty()) { reset(image); return; }
		List<OverheadElements.Element> identified = elements.identify(layer, context.templates, context.hints, content, false);
		finish(image, graphics, identified, element -> 100, ScalingFilter.NEAREST, 0, 0);
	}

	void finish(BufferedImage image, Graphics2D graphics, MinimapResizeConfig config, OverheadContext context, Runnable collect)
	{
		if (frame != image || frame == null) { return; }
		BufferedImage layer = image.getSubimage(viewport.x, viewport.y, viewport.width, viewport.height);
		Rectangle content = elements.contentBounds(layer);
		if (content.isEmpty())
		{
			reset(image);
			return;
		}
		// No actor enumeration, projections, sprite lookups or recognition in an empty scene.
		collect.run();
		List<OverheadElements.Element> identified = elements.identify(layer, context.templates, context.hints, content);
		finish(image, graphics, identified, element -> OverheadElements.percent(element.kind, config),
			config.filter() == null ? ScalingFilter.NEAREST : config.filter(), config.sharpening(), config.scalingFps());
	}

	private void finish(BufferedImage image, Graphics2D graphics, List<OverheadElements.Element> identified,
		ToIntFunction<OverheadElements.Element> percent, ScalingFilter filter, int sharpening, int fps)
	{
		frame = null;
		identified.removeIf(element -> percent.applyAsInt(element) <= 0
			|| percent.applyAsInt(element) == 100 && element.artwork == null);
		BufferedImage layer = image.getSubimage(viewport.x, viewport.y, viewport.width, viewport.height);
		PackedPixels source = PackedPixels.nativePixels(layer);
		for (int i = 0; i < identified.size(); i++)
		{
			OverheadElements.Element element = identified.get(i);
			Rectangle rect = element.bounds;
			PackedPixels selected = element.layer == null ? source : PackedPixels.nativePixels(element.layer);
			if (i == tiles.size()) { tiles.add(new Tile()); }
			Tile tile = tiles.get(i);
			boolean changed = tile.image == null || tile.image.getWidth() != rect.width || tile.image.getHeight() != rect.height;
			if (changed) { tile.image = new BufferedImage(rect.width, rect.height, BufferedImage.TYPE_INT_ARGB_PRE); }
			PackedPixels target = PackedPixels.nativePixels(tile.image);
			for (int y = 0; y < rect.height; y++)
			{
				int src = selected.index(rect.x, rect.y + y), dst = target.index(0, y);
				changed |= !Arrays.equals(selected.pixels, src, src + rect.width, target.pixels, dst, dst + rect.width);
				System.arraycopy(selected.pixels, src, target.pixels, dst, rect.width);
				int original = source.index(rect.x, rect.y + y);
				for (int x = 0; x < rect.width; x++)
				{
					if (target.pixels[dst + x] != 0) { source.pixels[original + x] = 0; }
				}
			}
			tile.changed = changed;
		}
		underlay.restore(image);
		for (int i = 0; i < identified.size(); i++)
		{
			Rectangle rect = identified.get(i).bounds;
			Tile tile = tiles.get(i);
			double scale = Math.max(50, Math.min(400, percent.applyAsInt(identified.get(i)))) / 100.0;
			int w = Math.max(1, (int) Math.round(rect.width * scale));
			int h = Math.max(1, (int) Math.round(rect.height * scale));
			BufferedImage authored = identified.get(i).artwork;
			BufferedImage output = authored == null ? tile.scaler.scale(tile.image, w, h, filter, sharpening, tile.changed, fps)
				: artwork.image(authored, w, h);
			boolean updated = authored == null ? tile.scaler.updated() : tile.authoredOutput != output;
			tile.authoredOutput = authored == null ? null : output;
			int x = viewport.x + rect.x + (rect.width - w) / 2;
			int y = viewport.y + rect.y + rect.height - h;
			tile.blitter.draw(image, output, x, y, graphics, true, updated);
		}
		// Release inactive crowds immediately, keeping only this frame's caches.
		while (tiles.size() > identified.size()) { tiles.remove(tiles.size() - 1); }
	}

	static List<Rectangle> clusters(BufferedImage image)
	{
		return clusters(image, new int[image.getWidth()]);
	}

	private static List<Rectangle> clusters(BufferedImage image, int[] transparentRow)
	{
		PackedPixels pixels = PackedPixels.nativePixels(image);
		List<Rectangle> result = new ArrayList<>();
		for (int y = 0; y < image.getHeight(); y++)
		{
			int row = pixels.index(0, y);
			if (Arrays.equals(pixels.pixels, row, row + image.getWidth(), transparentRow, 0, image.getWidth())) { continue; }
			for (int x = 0; x < image.getWidth();)
			{
				while (x < image.getWidth() && pixels.pixels[row + x] == 0) { x++; }
				if (x == image.getWidth()) { break; }
				int start = x, end = x + 1;
				for (x++; x < image.getWidth() && x - end <= 12; x++)
				{
					if (pixels.pixels[row + x] != 0) { end = x + 1; }
				}
				Rectangle run = null;
				int left = start, top = y, right = end, bottom = y + 1;
				for (int i = result.size() - 1; i >= 0; i--)
				{
					Rectangle existing = result.get(i);
					if (existing.y + existing.height + 6 < y) { continue; }
					if (existing.x - 12 < right && existing.x + existing.width + 12 > left
						&& existing.y - 6 < bottom && existing.y + existing.height + 6 > top)
					{
						left = Math.min(left, existing.x);
						top = Math.min(top, existing.y);
						right = Math.max(right, existing.x + existing.width);
						bottom = Math.max(bottom, existing.y + existing.height);
						run = existing;
						result.remove(i);
					}
				}
				if (run == null) { run = new Rectangle(); }
				run.setBounds(left, top, right - left, bottom - top);
				result.add(run);
				if (result.size() > 512) { return null; }
			}
		}
		// Ensure no original pixels are duplicated by overlapping bounding boxes.
		for (int i = 0; i < result.size(); i++)
		{
			for (int j = i + 1; j < result.size(); j++)
			{
				if (result.get(i).intersects(result.get(j)))
				{
					result.get(i).add(result.remove(j));
					i = -1;
					break;
				}
			}
		}
		return result;
	}

	void reset(BufferedImage image)
	{
		underlay.restore(image);
		frame = null;
	}

	void newFrame() { frame = null; underlay.invalidate(); }
	void clear() { newFrame(); underlay.clear(); tiles.clear(); transparentRow = null; elements.clear(); artwork.clear(); }
}
