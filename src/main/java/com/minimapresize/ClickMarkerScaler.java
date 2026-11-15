package com.minimapresize;

import java.awt.image.BufferedImage;
import net.runelite.api.Client;
import net.runelite.api.SpritePixels;

/** Replaces the native animation frames so native timing, colour and draw order are retained. */
final class ClickMarkerScaler
{
	private SpritePixels[] sprites;
	private SpritePixels[] originals;
	private SpritePixels[] replacements;
	private int percent;
	private ScalingFilter filter;
	private int sharpening;

	void update(Client client, int requestedPercent, ScalingFilter requestedFilter, int requestedSharpening)
	{
		int size = Math.max(50, Math.min(400, requestedPercent));
		ScalingFilter quality = requestedFilter == null ? ScalingFilter.NEAREST : requestedFilter;
		if (size == 100)
		{
			restore();
			return;
		}
		SpritePixels[] current = client.getCrossSprites();
		if (current == null)
		{
			restore();
			return;
		}
		if (current != sprites || percent != size || filter != quality || sharpening != requestedSharpening)
		{
			restore();
			sprites = current;
			originals = new SpritePixels[current.length];
			replacements = new SpritePixels[current.length];
			percent = size;
			filter = quality;
			sharpening = requestedSharpening;
		}
		for (int i = 0; i < current.length; i++)
		{
			if (current[i] == replacements[i]) { continue; }
			originals[i] = current[i];
			if (current[i] == null) { replacements[i] = null; continue; }
			replacements[i] = scaled(client, current[i], size, quality, requestedSharpening);
			current[i] = replacements[i];
		}
	}

	private static SpritePixels scaled(Client client, SpritePixels source, int percent, ScalingFilter filter, int sharpening)
	{
		double scale = percent / 100.0;
		int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
		int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
		BufferedImage image = new WidgetImageScaler().scale(source.toBufferedImage(), width, height,
			filter, sharpening, true, 0);
		int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
		// Native sprite drawing uses zero as transparent, rather than an alpha channel.
		for (int i = 0; i < pixels.length; i++)
		{
			pixels[i] = (pixels[i] >>> 24) < 128 ? 0 : Math.max(1, pixels[i] & 0xffffff);
		}
		SpritePixels result = client.createSpritePixels(pixels, width, height);
		int maxWidth = Math.max(1, (int) Math.round(source.getMaxWidth() * scale));
		int maxHeight = Math.max(1, (int) Math.round(source.getMaxHeight() * scale));
		result.setMaxWidth(maxWidth);
		result.setMaxHeight(maxHeight);
		// The client subtracts the native half-size from the click location. Compensate
		// through sprite offsets so all animation frames stay centred on that click.
		result.setOffsetX((int) Math.round(source.getOffsetX() * scale) + (source.getMaxWidth() - maxWidth) / 2);
		result.setOffsetY((int) Math.round(source.getOffsetY() * scale) + (source.getMaxHeight() - maxHeight) / 2);
		return result;
	}

	void restore()
	{
		if (sprites != null)
		{
			for (int i = 0; i < sprites.length; i++)
			{
				if (replacements[i] != null && sprites[i] == replacements[i]) { sprites[i] = originals[i]; }
			}
		}
		sprites = null;
		originals = null;
		replacements = null;
	}
}
