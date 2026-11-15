package com.minimapresize;

import java.awt.Rectangle;
import java.util.EnumMap;
import java.util.Map;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetSizeMode;

/** Give responsive central windows a smaller logical viewport before enlarging their pixels. */
final class InterfaceLayout
{
	private static final int MIN_WIDTH = 512;
	private static final int MIN_HEIGHT = 350;
	private final Map<WidgetRegion, SavedLayout> layouts = new EnumMap<>(WidgetRegion.class);

	private static final class SavedLayout
	{
		final Widget host;
		final int group;
		final int originalWidth;
		final int originalHeight;
		final int widthMode;
		final int heightMode;
		final Rectangle physical;
		final Rectangle nativeWindow;
		int appliedWidth;
		int appliedHeight;

		SavedLayout(Widget host, int group, Rectangle nativeWindow)
		{
			this.host = host;
			this.group = group;
			this.nativeWindow = new Rectangle(nativeWindow);
			physical = new Rectangle(new WidgetGeometry().bounds(host));
			originalWidth = host.getOriginalWidth();
			originalHeight = host.getOriginalHeight();
			widthMode = host.getWidthMode();
			heightMode = host.getHeightMode();
		}

		boolean ownsWidth() { return host.getWidthMode() == WidgetSizeMode.ABSOLUTE && host.getOriginalWidth() == appliedWidth; }
		boolean ownsHeight() { return host.getHeightMode() == WidgetSizeMode.ABSOLUTE && host.getOriginalHeight() == appliedHeight; }

		void restore()
		{
			boolean changed = false;
			// Preserve an axis deliberately changed by another plugin or a script.
			if (ownsWidth())
			{
				host.setOriginalWidth(originalWidth);
				host.setWidthMode(widthMode);
				changed = true;
			}
			if (ownsHeight())
			{
				host.setOriginalHeight(originalHeight);
				host.setHeightMode(heightMode);
				changed = true;
			}
			if (changed) { host.revalidateScroll(); }
		}
	}

	boolean update(WidgetRegion region, Widget host, int percent, int canvasWidth, int canvasHeight)
	{
		if (!region.interfaceWindow()) { return false; }
		SavedLayout saved = layouts.get(region);
		int group = region.interfaceId(host);
		boolean changed = false;
		if (saved != null && (saved.host != host || saved.group != group || percent <= 100
			|| !saved.ownsWidth() || !saved.ownsHeight()))
		{
			saved.restore();
			layouts.remove(region);
			saved = null;
			changed = true;
		}
		if (host == null || group < 0 || percent <= 100) { return changed; }
		double scale = Math.min(400, percent) / 100.0;
		Rectangle nativeWindow = saved == null ? region.captureBounds(host) : saved.nativeWindow;
		// Bank init can report a short/unfinished body before its final size check.
		// Establish its logical viewport immediately, rather than showing that
		// preliminary layout and deciding to reflow only on the following frame.
		boolean responsiveBank = (group == InterfaceID.BANKMAIN || group == InterfaceID.SHARED_BANK)
			&& host.getWidth() > 0 && host.getHeight() > 0;
		if (!responsiveBank && (nativeWindow.isEmpty()
			|| (nativeWindow.width * scale <= canvasWidth && nativeWindow.height * scale <= canvasHeight)))
		{
			if (saved != null) { saved.restore(); layouts.remove(region); return true; }
			return changed;
		}
		Rectangle available = saved == null ? new WidgetGeometry().bounds(host) : saved.physical;
		if (available == null || available.isEmpty()) { return changed; }
		int width = Math.min(available.width, Math.max(MIN_WIDTH, (int) Math.floor(available.width / scale)));
		int height = Math.min(available.height, Math.max(MIN_HEIGHT, (int) Math.floor(available.height / scale)));
		if (saved == null)
		{
			if (width == available.width && height == available.height) { return changed; }
			saved = new SavedLayout(host, group, nativeWindow);
			layouts.put(region, saved);
		}
		if (saved.appliedWidth != width || saved.appliedHeight != height)
		{
			host.setOriginalWidth(width);
			host.setOriginalHeight(height);
			host.setWidthMode(WidgetSizeMode.ABSOLUTE);
			host.setHeightMode(WidgetSizeMode.ABSOLUTE);
			saved.appliedWidth = width;
			saved.appliedHeight = height;
			// Native proportional/minus-sized bank layers and scrollbars reflow here.
			host.revalidateScroll();
			return true;
		}
		return changed;
	}

	MinimapTransform transform(WidgetRegion region, Rectangle source, int percent, int width, int height)
	{
		SavedLayout saved = layouts.get(region);
		return transform(region, source, percent, width, height, saved == null ? null : saved.host.getBounds());
	}

	MinimapTransform transform(WidgetRegion region, Rectangle source, int percent, int width, int height, Rectangle logical)
	{
		SavedLayout saved = layouts.get(region);
		if (saved == null) { return MinimapTransform.anchored(source, percent, width, height, false); }
		double scale = Math.min(Math.max(50, Math.min(400, percent)) / 100.0,
			Math.min((double) width / source.width, (double) height / source.height));
		int w = Math.max(1, (int) Math.round(source.width * scale));
		int h = Math.max(1, (int) Math.round(source.height * scale));
		double centerX = saved.physical.getCenterX() + (source.getCenterX() - logical.getCenterX()) * scale;
		double centerY = saved.physical.getCenterY() + (source.getCenterY() - logical.getCenterY()) * scale;
		int x = Math.max(0, Math.min(width - w, (int) Math.round(centerX - w / 2.0)));
		int y = Math.max(0, Math.min(height - h, (int) Math.round(centerY - h / 2.0)));
		return new MinimapTransform(source, new Rectangle(x, y, w, h));
	}

	void restore()
	{
		for (SavedLayout saved : layouts.values()) { saved.restore(); }
		layouts.clear();
	}
}
