package com.minimapresize;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Conservative segmentation of the native, transparent actor-indicator layer. */
final class OverheadElements
{
	enum Kind { HEALTH_BAR, HITSPLAT, PRAYER, SKULL, TEXT, NATIVE }

	static final class Element
	{
		final Rectangle bounds;
		final Kind kind;
		final BufferedImage layer;
		final BufferedImage artwork;

		Element(Rectangle bounds, Kind kind)
		{
			this(bounds, kind, null);
		}

		Element(Rectangle bounds, Kind kind, BufferedImage layer)
		{
			this(bounds, kind, layer, null);
		}

		Element(Rectangle bounds, Kind kind, BufferedImage layer, BufferedImage artwork)
		{
			this.bounds = new Rectangle(bounds);
			this.kind = kind;
			this.layer = layer;
			this.artwork = artwork;
		}
	}

	static final class Template
	{
		final Kind kind;
		final int width, height;
		final int[] pixels;
		final int[] alternate;
		final int seed;
		BufferedImage artwork;

		Template(BufferedImage image, Kind kind)
		{
			this(image, null, kind);
		}

		Template(BufferedImage image, BufferedImage alternate, Kind kind)
		{
			this.kind = kind;
			width = image.getWidth();
			height = image.getHeight();
			pixels = image.getRGB(0, 0, width, height, null, 0, width);
			this.alternate = alternate == null ? null : alternate.getRGB(0, 0, width, height, null, 0, width);
			int first = -1;
			for (int i = 0; i < pixels.length; i++)
			{
				if (pixels[i] != 0 || (this.alternate != null && this.alternate[i] != 0)) { first = i; break; }
			}
			seed = first;
		}
	}

	private BufferedImage scratch;
	private int[] candidates = new int[4096];
	private int[] transparentRow;
	private int[] rowStarts;
	private int[] rowEnds;

	/** Vectorized empty-row checks avoid scalar recognition passes over the whole canvas. */
	Rectangle contentBounds(BufferedImage layer)
	{
		int width = layer.getWidth(), height = layer.getHeight();
		if (transparentRow == null || transparentRow.length != width) { transparentRow = new int[width]; }
		PackedPixels source = PackedPixels.nativePixels(layer);
		int left = width, right = 0, top = height, bottom = 0;
		for (int y = 0; y < height; y++)
		{
			int row = source.index(0, y);
			int first = Arrays.mismatch(source.pixels, row, row + width, transparentRow, 0, width);
			if (first < 0) { continue; }
			int last = width - 1;
			while (last > first && source.pixels[row + last] == 0) { last--; }
			left = Math.min(left, first);
			right = Math.max(right, last + 1);
			top = Math.min(top, y);
			bottom = y + 1;
		}
		return bottom == 0 ? new Rectangle() : new Rectangle(left, top, right - left, bottom - top);
	}

	List<Element> identify(BufferedImage layer, List<Template> templates, List<Element> hints)
	{
		return identify(layer, templates, hints, contentBounds(layer));
	}

	List<Element> identify(BufferedImage layer, List<Template> templates, List<Element> hints, Rectangle content)
	{
		return identify(layer, templates, hints, content, true);
	}

	List<Element> identify(BufferedImage layer, List<Template> templates, List<Element> hints, Rectangle content, boolean recognizeRemaining)
	{
		if (content.isEmpty()) { return new ArrayList<>(); }
		// Include transparent sprite borders, which are part of exact template matching.
		int paddingX = 0, paddingY = 0;
		for (Template template : templates)
		{
			paddingX = Math.max(paddingX, template.width);
			paddingY = Math.max(paddingY, template.height);
		}
		Rectangle area = new Rectangle(content);
		area.grow(paddingX, paddingY);
		area = area.intersection(new Rectangle(layer.getWidth(), layer.getHeight()));
		int right = area.x + area.width, bottom = area.y + area.height;
		if (scratch == null || scratch.getWidth() != layer.getWidth() || scratch.getHeight() != layer.getHeight())
		{
			scratch = new BufferedImage(layer.getWidth(), layer.getHeight(), BufferedImage.TYPE_INT_ARGB_PRE);
			rowStarts = new int[layer.getHeight()];
			rowEnds = new int[layer.getHeight()];
		}
		if (transparentRow == null || transparentRow.length != layer.getWidth()) { transparentRow = new int[layer.getWidth()]; }
		PackedPixels source = PackedPixels.nativePixels(layer), work = PackedPixels.nativePixels(scratch);
		int count = 0;
		for (int y = area.y; y < bottom; y++)
		{
			int row = source.index(area.x, y);
			// Only these spans could contain pixels from the previous recognition.
			// Clearing them also handles actors that moved away or disappeared.
			Arrays.fill(work.pixels, work.index(rowStarts[y], y), work.index(rowEnds[y], y), 0);
			// A few distant actors can span most of a 1440p/4K viewport. Keep the
			// scratch backdrop current, but do not scalar-scan all that empty space
			// twice (candidate collection and fallback health-bar recognition).
			int first = Arrays.mismatch(source.pixels, row, row + area.width, transparentRow, 0, area.width);
			if (first < 0) { rowStarts[y] = rowEnds[y] = area.x; continue; }
			int last = area.width - 1;
			while (last > first && source.pixels[row + last] == 0) { last--; }
			rowStarts[y] = area.x + first;
			rowEnds[y] = area.x + last + 1;
			System.arraycopy(source.pixels, row + first, work.pixels, work.index(rowStarts[y], y), last - first + 1);
			for (int x = rowStarts[y]; x < rowEnds[y]; x++)
			{
				if (source.pixels[source.index(x, y)] == 0) { continue; }
				if (count == candidates.length) { candidates = Arrays.copyOf(candidates, candidates.length * 2); }
				candidates[count++] = y * layer.getWidth() + x;
			}
		}
		List<Element> result = new ArrayList<>();
		for (Template template : templates)
		{
			if (template.seed < 0 || template.width > layer.getWidth() || template.height > layer.getHeight()) { continue; }
			int sx = template.seed % template.width, sy = template.seed / template.width;
			for (int c = 0; c < count; c++)
			{
				int x = candidates[c] % layer.getWidth() - sx, y = candidates[c] / layer.getWidth() - sy;
				if (x < area.x || y < area.y || x + template.width > right || y + template.height > bottom) { continue; }
				int seedPixel = work.pixels[work.index(x + sx, y + sy)];
				if (seedPixel == 0 || !matches(seedPixel, template, template.seed)) { continue; }
				if (!matches(work, x, y, template)) { continue; }
				Rectangle bounds = new Rectangle(x, y, template.width, template.height);
				result.add(new Element(bounds, template.kind, null, template.artwork));
				clear(work, bounds);
			}
		}
		// Authored-prayer-only mode leaves speech, bars, splats and plugin overlays untouched.
		// Do not run clustering or fallback recognition for categories that were not requested.
		if (!recognizeRemaining) { return result; }
		// The fallback health bar consists of a solid 5-pixel-high red/green strip.
		// Require identical rows, not merely red/green pixels somewhere in a splat.
		for (int y = area.y; y < bottom; y++)
		{
			for (int x = rowStarts[y]; x < rowEnds[y];)
			{
				if (!barColour(work.pixels[work.index(x, y)])) { x++; continue; }
				int left = x;
				while (x < right && barColour(work.pixels[work.index(x, y)])) { x++; }
				int width = x - left;
				if (width < 12) { continue; }
				int height = 1;
				while (y + height < bottom && height <= 12
					&& Arrays.equals(work.pixels, work.index(left, y), work.index(left, y) + width,
						work.pixels, work.index(left, y + height), work.index(left, y + height) + width)) { height++; }
				if (height != 5) { continue; }
				Rectangle bounds = new Rectangle(left, y, width, height);
				if (!containedBy(bounds, Kind.HEALTH_BAR, hints)) { continue; }
				boolean ambiguous = containedBy(bounds, Kind.HITSPLAT, hints) || containedBy(bounds, Kind.TEXT, hints);
				result.add(new Element(bounds, ambiguous ? Kind.NATIVE : Kind.HEALTH_BAR));
				clear(work, bounds);
			}
		}
		// Only this rectangle was refreshed; pixels outside it can belong to an old camera frame.
		List<Rectangle> remaining = OverheadScaler.clusters(scratch.getSubimage(area.x, area.y, area.width, area.height));
		if (remaining == null) { return result; }
		for (Rectangle bounds : remaining)
		{
			bounds.translate(area.x, area.y);
			boolean text = containedBy(bounds, Kind.TEXT, hints);
			boolean splat = containedBy(bounds, Kind.HITSPLAT, hints);
			// A combined text/splat cluster must never inherit just one of its settings.
			Kind kind = text == splat ? Kind.NATIVE : text ? Kind.TEXT : Kind.HITSPLAT;
			result.add(new Element(bounds, kind, scratch));
		}
		return result;
	}

	private static boolean containedBy(Rectangle bounds, Kind kind, List<Element> hints)
	{
		for (Element hint : hints)
		{
			if (hint.kind == kind && hint.bounds.contains(bounds)) { return true; }
		}
		return false;
	}

	private static boolean barColour(int pixel)
	{
		return pixel == 0xffff0000 || pixel == 0xff00ff00;
	}

	private static boolean matches(int pixel, Template template, int index)
	{
		return pixel == template.pixels[index] || template.alternate != null && pixel == template.alternate[index];
	}

	private static boolean matches(PackedPixels work, int x, int y, Template template)
	{
		for (int ty = 0; ty < template.height; ty++)
		{
			for (int tx = 0; tx < template.width; tx++)
			{
				if (!matches(work.pixels[work.index(x + tx, y + ty)], template, ty * template.width + tx)) { return false; }
			}
		}
		return true;
	}

	private static void clear(PackedPixels work, Rectangle bounds)
	{
		for (int y = bounds.y; y < bounds.y + bounds.height; y++)
		{
			Arrays.fill(work.pixels, work.index(bounds.x, y), work.index(bounds.x, y) + bounds.width, 0);
		}
	}

	static int percent(Kind kind, MinimapResizeConfig config)
	{
		switch (kind)
		{
			case HEALTH_BAR: return config.healthBarScale();
			case HITSPLAT: return config.hitsplatScale();
			case PRAYER: return config.prayerIconScale();
			case SKULL: return config.skullIconScale();
			case TEXT: return config.overheadTextScale();
			default: return 100;
		}
	}

	static boolean requested(MinimapResizeConfig config)
	{
		for (Kind kind : Kind.values())
		{
			int size = percent(kind, config);
			if (size > 0 && size != 100) { return true; }
		}
		return false;
	}

	void clear() { scratch = null; candidates = new int[4096]; transparentRow = null; rowStarts = null; rowEnds = null; }
}
