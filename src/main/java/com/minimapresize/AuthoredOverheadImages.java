package com.minimapresize;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared immutable samples: crowds with the same prayer do not resample its HD source per actor. */
final class AuthoredOverheadImages
{
	private final Map<Key, BufferedImage> images = new LinkedHashMap<Key, BufferedImage>(32, .75f, true)
	{
		@Override protected boolean removeEldestEntry(Map.Entry<Key, BufferedImage> entry) { return size() > 64; }
	};

	BufferedImage image(BufferedImage source, int width, int height)
	{
		Key key = new Key(source, width, height);
		BufferedImage image = images.get(key);
		if (image == null)
		{
			// Full-footprint, premultiplied sampling; do not apply pixel-art filters or sharpening.
			image = new WidgetImageScaler().scale(source, width, height, ScalingFilter.LANCZOS, 0, true, 0);
			images.put(key, image);
		}
		return image;
	}

	void clear() { images.clear(); }

	private static final class Key
	{
		final BufferedImage source;
		final int width, height;
		Key(BufferedImage source, int width, int height) { this.source = source; this.width = width; this.height = height; }
		@Override public int hashCode() { return (System.identityHashCode(source) * 31 + width) * 31 + height; }
		@Override public boolean equals(Object other)
		{
			if (!(other instanceof Key)) { return false; }
			Key key = (Key) other;
			return source == key.source && width == key.width && height == key.height;
		}
	}
}
