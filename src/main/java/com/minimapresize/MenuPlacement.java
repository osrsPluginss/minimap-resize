package com.minimapresize;

import java.awt.Point;
import java.awt.Rectangle;

/** Native menu placement rules in game-buffer coordinates, after global stretch translation. */
final class MenuPlacement
{
	final Rectangle nativeBounds;
	final Rectangle visibleBounds;
	final int offsetX;
	final int offsetY;
	final double scaleX;
	final double scaleY;

	MenuPlacement(Rectangle nativeBounds, Point cursor, int width, int height)
	{
		this(nativeBounds, cursor, width, height, 100);
	}

	MenuPlacement(Rectangle nativeBounds, Point cursor, int width, int height, int percent)
	{
		this(nativeBounds, cursor, width, height, percent, nativeBounds);
	}

	MenuPlacement(Rectangle nativeBounds, Point cursor, int width, int height, int percent, Rectangle menuArea)
	{
		this.nativeBounds = new Rectangle(nativeBounds);
		double scale = Math.min(Math.max(50, Math.min(400, percent)) / 100.0,
			Math.min((double) width / menuArea.width, (double) height / menuArea.height));
		int w = Math.max(1, (int) Math.round(nativeBounds.width * scale));
		int h = Math.max(1, (int) Math.round(nativeBounds.height * scale));
		int x = Math.max(0, Math.min(Math.max(0, width - w), cursor.x - w / 2));
		int y = Math.max(0, Math.min(Math.max(0, height - h), cursor.y));
		scaleX = (double) w / nativeBounds.width;
		scaleY = (double) h / nativeBounds.height;
		if (!menuArea.equals(nativeBounds))
		{
			int left = (int) Math.round((menuArea.x - nativeBounds.x) * scaleX);
			int top = (int) Math.round((menuArea.y - nativeBounds.y) * scaleY);
			int right = (int) Math.round((menuArea.x + menuArea.width - nativeBounds.x) * scaleX);
			int bottom = (int) Math.round((menuArea.y + menuArea.height - nativeBounds.y) * scaleY);
			x = Math.max(-left, Math.min(width - right, x));
			y = Math.max(-top, Math.min(height - bottom, y));
		}
		visibleBounds = new Rectangle(x, y, w, h);
		offsetX = x - nativeBounds.x;
		offsetY = y - nativeBounds.y;
	}

	MenuPlacement(Rectangle nativeBounds, Point cameraInputOffset)
	{
		this.nativeBounds = new Rectangle(nativeBounds);
		offsetX = -cameraInputOffset.x;
		offsetY = -cameraInputOffset.y;
		visibleBounds = new Rectangle(nativeBounds);
		visibleBounds.translate(offsetX, offsetY);
		scaleX = scaleY = 1;
	}

	Point toNative(Point point)
	{
		// Apply the same translation outside the menu too, preserving its normal close margin.
		return new Point(nativeBounds.x + (int) Math.floor((point.x - visibleBounds.x) / scaleX),
			nativeBounds.y + (int) Math.floor((point.y - visibleBounds.y) / scaleY));
	}

	Rectangle toVisible(Rectangle area)
	{
		int x = visibleBounds.x + (int) Math.round((area.x - nativeBounds.x) * scaleX);
		int y = visibleBounds.y + (int) Math.round((area.y - nativeBounds.y) * scaleY);
		int right = visibleBounds.x + (int) Math.round((area.x + area.width - nativeBounds.x) * scaleX);
		int bottom = visibleBounds.y + (int) Math.round((area.y + area.height - nativeBounds.y) * scaleY);
		return new Rectangle(x, y, Math.max(1, right - x), Math.max(1, bottom - y));
	}
}
