package com.minimapresize;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import net.runelite.client.events.PluginMessage;

/** Resizes Responsive Movement's typed local-player painters before the native actor pass. */
final class MovementOverheadCompatibility
{
	private final Map<String, Tile> tiles = new HashMap<>();
	private final AuthoredOverheadImages artwork = new AuthoredOverheadImages();

	private static final class Tile
	{
		BufferedImage image;
		int[] previous;
		final WidgetImageScaler scaler = new WidgetImageScaler();
		final CachedUiBlitter blitter = new CachedUiBlitter();
		BufferedImage authoredOutput;
	}

	@SuppressWarnings("unchecked")
	void render(PluginMessage event, MinimapResizeConfig config, BufferedImage target, boolean gpu)
	{
		if (!"responsive-movement".equals(event.getNamespace()) || !"render-overhead".equals(event.getName())) { return; }
		Map<String, Object> data = event.getData();
		Object kindValue = data.get("kind"), slotValue = data.get("slot");
		if (!(kindValue instanceof String) || !(slotValue instanceof Integer)
			|| !(data.get("bounds") instanceof Rectangle) || !(data.get("graphics") instanceof Graphics2D)
			|| !(data.get("renderer") instanceof Consumer) || !(data.get("handled") instanceof Runnable)) { return; }
		OverheadElements.Kind kind;
		try { kind = OverheadElements.Kind.valueOf((String) kindValue); }
		catch (IllegalArgumentException ignored) { return; }
		// Speech keeps its existing native-text handling. The handoff covers the replaced actor UI.
		if (kind == OverheadElements.Kind.TEXT || kind == OverheadElements.Kind.NATIVE) { return; }
		int slot = (Integer) slotValue;
		if (slot < 0 || slot > 3) { return; }
		int percent = OverheadElements.percent(kind, config);
		BufferedImage authored = kind == OverheadElements.Kind.PRAYER && data.get("artwork") instanceof BufferedImage
			? (BufferedImage) data.get("artwork") : null;
		if (percent <= 0 || percent == 100 && authored == null) { return; }
		Rectangle bounds = (Rectangle) data.get("bounds");
		if (bounds.isEmpty()) { return; }
		Graphics2D graphics = (Graphics2D) data.get("graphics");
		Tile tile = tiles.computeIfAbsent(kind.name() + slot, key -> new Tile());
		if (authored != null)
		{
			double scale = Math.max(50, Math.min(400, percent)) / 100.0;
			int width = Math.max(1, (int) Math.round(bounds.width * scale));
			int height = Math.max(1, (int) Math.round(bounds.height * scale));
			BufferedImage output = artwork.image(authored, width, height);
			tile.blitter.draw(target, output, bounds.x + (bounds.width - width) / 2, bounds.y + bounds.height - height,
				graphics, gpu, tile.authoredOutput != output);
			tile.authoredOutput = output;
			((Runnable) data.get("handled")).run();
			return;
		}
		tile.authoredOutput = null;
		if (tile.image == null || tile.image.getWidth() != bounds.width || tile.image.getHeight() != bounds.height)
		{
			tile.image = new BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_ARGB_PRE);
			tile.previous = null;
		}
		Graphics2D capture = tile.image.createGraphics();
		try
		{
			capture.setComposite(AlphaComposite.Clear);
			capture.fillRect(0, 0, bounds.width, bounds.height);
			capture.setComposite(AlphaComposite.SrcOver);
			capture.setRenderingHints(graphics.getRenderingHints());
			capture.setFont(graphics.getFont());
			capture.setColor(graphics.getColor());
			capture.translate(-bounds.x, -bounds.y);
			((Consumer<Graphics2D>) data.get("renderer")).accept(capture);
		}
		finally { capture.dispose(); }
		int[] pixels = PackedPixels.nativePixels(tile.image).pixels;
		boolean changed = !Arrays.equals(pixels, tile.previous);
		if (tile.previous == null) { tile.previous = pixels.clone(); }
		else if (changed) { System.arraycopy(pixels, 0, tile.previous, 0, pixels.length); }
		double scale = Math.max(50, Math.min(400, percent)) / 100.0;
		int width = Math.max(1, (int) Math.round(bounds.width * scale));
		int height = Math.max(1, (int) Math.round(bounds.height * scale));
		BufferedImage output = tile.scaler.scale(tile.image, width, height,
			config.filter() == null ? ScalingFilter.NEAREST : config.filter(), config.sharpening(), changed, config.scalingFps());
		tile.blitter.draw(target, output, bounds.x + (bounds.width - width) / 2, bounds.y + bounds.height - height,
			graphics, gpu, tile.scaler.updated());
		((Runnable) data.get("handled")).run();
	}

	void clear() { tiles.clear(); artwork.clear(); }
}
