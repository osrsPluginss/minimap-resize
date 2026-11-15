package com.minimapresize;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import net.runelite.client.config.RuneLiteConfig;
import net.runelite.client.config.TooltipPositionType;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.ui.overlay.components.LayoutableRenderableEntity;
import net.runelite.client.ui.overlay.components.PanelComponent;
import net.runelite.client.ui.overlay.components.TextComponent;
import net.runelite.client.ui.overlay.components.TooltipComponent;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import net.runelite.client.ui.overlay.tooltip.TooltipOverlay;

/** Renders existing tooltip contents at native resolution, then scales once near the visible cursor. */
final class HoverScaler
{
	private final Client client;
	private final OverlayManager overlays;
	private final TooltipManager tooltips;
	private final TooltipOverlay original;
	private final RuneLiteConfig runeLiteConfig;
	private final MinimapResizeConfig config;
	private final WidgetImageScaler scaler = new WidgetImageScaler();
	private final CachedUiBlitter blitter = new CachedUiBlitter();
	private BufferedImage scratch;
	private BufferedImage crop;
	private boolean detached;
	private boolean nativeHidden;
	private int oldWidth;
	private int oldHeight;

	HoverScaler(Client client, OverlayManager overlays, TooltipManager tooltips, TooltipOverlay original,
		RuneLiteConfig runeLiteConfig, MinimapResizeConfig config)
	{
		this.client = client;
		this.overlays = overlays;
		this.tooltips = tooltips;
		this.original = original;
		this.runeLiteConfig = runeLiteConfig;
		this.config = config;
	}

	void beginFrame(boolean active)
	{
		restoreNative();
		if (!active) { restore(); return; }
		if (!detached && original != null) { detached = overlays.remove(original); }
		nativeHidden = client.isMouseoverTextEnabled();
		if (nativeHidden) { client.setMouseoverTextEnabled(false); }
	}

	void drawNative(Graphics2D graphics)
	{
		boolean draw = nativeHidden;
		restoreNative();
		if (!draw || client.isMenuOpen()) { return; }
		MenuEntry[] entries = client.getMenuEntries();
		if (entries == null || entries.length < 2) { return; }
		MenuEntry entry = entries[entries.length - 1];
		String target = entry.getTarget();
		String text = entry.getOption() + (target == null || target.isEmpty() ? "" : " " + target);
		if (entries.length > 2) { text += "<col=ffffff> / " + (entries.length - 2) + " more options"; }
		TextComponent component = new TextComponent();
		component.setText(text.replace("</col>", "<col=ffffff>"));
		component.setColor(Color.WHITE);
		component.setFont(FontManager.getRunescapeBoldFont().deriveFont(
			FontManager.getRunescapeBoldFont().getSize2D() * config.hoverScale() / 100f));
		component.setPosition(client.getViewportXOffset() + 4, client.getViewportYOffset() + Math.max(14, 15 * config.hoverScale() / 100));
		component.render(graphics);
	}

	void drawTooltips(BufferedImage target, Graphics2D graphics, Point pointer)
	{
		if (!detached || tooltips == null || runeLiteConfig == null || target == null || tooltips.getTooltips().isEmpty()) { return; }
		int width = target.getWidth(), height = target.getHeight();
		if (scratch == null)
		{
			scratch = new BufferedImage(Math.min(width, 512), Math.min(height, 256), BufferedImage.TYPE_INT_ARGB_PRE);
			oldWidth = oldHeight = 0;
		}
		int w = 0, h = 0;
		try
		{
			for (int pass = 0; pass < 2; pass++)
			{
				Graphics2D g = scratch.createGraphics();
				Dimension size;
				try
				{
					g.setComposite(AlphaComposite.Clear);
					g.fillRect(0, 0, oldWidth, oldHeight);
					g.setComposite(AlphaComposite.SrcOver);
					OverlayUtil.setGraphicProperties(g);
					g.setFont(runeLiteConfig.tooltipFont().getFont());
					size = renderContents(g, tooltips.getTooltips());
				}
				finally { g.dispose(); }
				w = Math.min(width, size.width);
				h = Math.min(height, size.height);
				if (pass != 0 || (w <= scratch.getWidth() && h <= scratch.getHeight())) { break; }
				// Most tooltips need only a small texture, even on a 4K canvas.
				// Grow for larger custom panels after their first measured render.
				scratch = new BufferedImage(Math.min(width, Math.max(w, scratch.getWidth() * 2)),
					Math.min(height, Math.max(h, scratch.getHeight() * 2)), BufferedImage.TYPE_INT_ARGB_PRE);
				oldWidth = oldHeight = 0;
			}
		}
		finally { tooltips.clear(); }
		oldWidth = w;
		oldHeight = h;
		if (oldWidth <= 0 || oldHeight <= 0) { return; }
		boolean changed = crop == null || crop.getWidth() != oldWidth || crop.getHeight() != oldHeight;
		if (changed) { crop = new BufferedImage(oldWidth, oldHeight, BufferedImage.TYPE_INT_ARGB_PRE); }
		PackedPixels src = PackedPixels.nativePixels(scratch), dst = PackedPixels.nativePixels(crop);
		for (int y = 0; y < oldHeight; y++)
		{
			int s = src.index(0, y), d = dst.index(0, y);
			changed |= !Arrays.equals(src.pixels, s, s + oldWidth, dst.pixels, d, d + oldWidth);
			System.arraycopy(src.pixels, s, dst.pixels, d, oldWidth);
		}
		double scale = Math.min(config.hoverScale() / 100.0, Math.min((double) width / oldWidth, (double) height / oldHeight));
		BufferedImage output = scaler.scale(crop, Math.max(1, (int) Math.round(oldWidth * scale)),
			Math.max(1, (int) Math.round(oldHeight * scale)), config.filter() == null ? ScalingFilter.NEAREST : config.filter(), config.sharpening(), changed);
		int x = Math.max(0, Math.min(width - output.getWidth(), pointer.x));
		int y = runeLiteConfig.tooltipPosition() == TooltipPositionType.ABOVE_CURSOR
			? pointer.y - output.getHeight() : pointer.y + 24;
		y = Math.max(0, Math.min(height - output.getHeight(), y));
		blitter.draw(target, output, x, y, graphics, client.isGpu(), scaler.updated());
	}

	private Dimension renderContents(Graphics2D graphics, List<Tooltip> contents)
	{
		int width = 0, height = 0;
		for (Tooltip tooltip : contents)
		{
			LayoutableRenderableEntity component = tooltip.getComponent();
			if (component == null)
			{
				TooltipComponent text = new TooltipComponent();
				text.setText(tooltip.getText());
				text.setModIcons(client.getModIcons());
				text.setBackgroundColor(runeLiteConfig.overlayBackgroundColor());
				component = text;
			}
			else if (component instanceof PanelComponent)
			{
				((PanelComponent) component).setBackgroundColor(runeLiteConfig.overlayBackgroundColor());
			}
			component.setPreferredLocation(new Point(0, height));
			Dimension size = component.render(graphics);
			if (size != null) { width = Math.max(width, size.width); height += size.height + 2; }
		}
		return new Dimension(width, height);
	}

	private void restoreNative()
	{
		if (nativeHidden) { client.setMouseoverTextEnabled(true); nativeHidden = false; }
	}

	void restore()
	{
		restoreNative();
		if (detached) { overlays.add(original); detached = false; }
	}

	void clear() { restore(); scratch = null; crop = null; scaler.clear(); blitter.clear(); }
}
