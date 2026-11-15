package com.minimapresize;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.HealthBarConfig;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.SpritePixels;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.gameval.SpriteID;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.FontManager;
import static com.minimapresize.OverheadElements.Kind;

/** Public actor metadata narrows text/splat recognition; icon recognition uses actual cache sprites. */
final class OverheadContext
{
	private static final BufferedImage ANCHOR = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
	private final Map<Actor, Integer> hitsplats = new IdentityHashMap<>();
	private final Map<Long, OverheadElements.Template> icons = new LinkedHashMap<>();
	private final Map<HealthBarConfig, OverheadElements.Template> healthBars = new LinkedHashMap<>();
	final List<OverheadElements.Element> hints = new ArrayList<>();
	final List<OverheadElements.Template> templates = new ArrayList<>();

	/** HD-only fast path: capture columns above supported prayer actors, never an otherwise empty viewport. */
	Rectangle collectPrayers(Client client, SpriteManager sprites, Rectangle viewport,
		BiFunction<Integer, Integer, BufferedImage> artwork)
	{
		hints.clear();
		templates.clear();
		Set<OverheadElements.Template> active = new LinkedHashSet<>();
		Rectangle capture = new Rectangle();
		for (Player player : client.getPlayers())
		{
			if (player.getOverheadIcon() == null) { continue; }
			int file = player.getOverheadIcon().ordinal();
			if (artwork.apply(SpriteID.HEADICONS_PRAYER, file) == null) { continue; }
			icon(sprites, SpriteID.HEADICONS_PRAYER, file, Kind.PRAYER, active, artwork);
			includePrayerColumn(capture, player, viewport, 1);
		}
		for (NPC npc : client.getNpcs())
		{
			int[] archives = npc.getOverheadArchiveIds();
			short[] files = npc.getOverheadSpriteIds();
			if (archives == null || files == null) { continue; }
			boolean supported = false;
			int count = Math.min(archives.length, files.length);
			for (int i = 0; i < count; i++)
			{
				if (archives[i] < 0 || files[i] < 0 || artwork.apply(archives[i], (int) files[i]) == null) { continue; }
				icon(sprites, archives[i], files[i], Kind.PRAYER, active, artwork);
				supported = true;
			}
			if (supported) { includePrayerColumn(capture, npc, viewport, count); }
		}
		templates.addAll(active);
		return active.isEmpty() ? new Rectangle() : capture;
	}

	private static void includePrayerColumn(Rectangle capture, Actor actor, Rectangle viewport, int slots)
	{
		Point head = actor.getCanvasImageLocation(ANCHOR, actor.getLogicalHeight() + 15);
		if (head == null) { return; }
		// Include the whole column above the head: variable health bars/skulls can push
		// prayers upward. Native multi-icon NPCs may also spread their slots horizontally.
		int radius = 64 + Math.max(0, slots - 1) * 32;
		Rectangle column = new Rectangle(head.getX() - radius, viewport.y, radius * 2,
			Math.max(0, Math.min(viewport.height, head.getY() + 64 - viewport.y))).intersection(viewport);
		if (column.isEmpty()) { return; }
		if (capture.isEmpty()) { capture.setBounds(column); }
		else { capture.add(column); }
	}

	void hitsplat(HitsplatApplied event)
	{
		if (event.getActor() == null || event.getHitsplat() == null) { return; }
		hitsplats.merge(event.getActor(), event.getHitsplat().getDisappearsOnGameCycle(), Math::max);
	}

	void healthBar(HealthBarConfig config)
	{
		if (config == null || healthBars.containsKey(config)) { return; }
		SpritePixels front = config.getHealthBarFrontSprite(), back = config.getHealthBarBackSprite();
		if (front == null || back == null || front.getWidth() != back.getWidth() || front.getHeight() != back.getHeight()
			|| front.getOffsetX() != back.getOffsetX() || front.getOffsetY() != back.getOffsetY()) { return; }
		if (healthBars.size() >= 64) { healthBars.remove(healthBars.keySet().iterator().next()); }
		healthBars.put(config, new OverheadElements.Template(front.toBufferedImage(), back.toBufferedImage(), Kind.HEALTH_BAR));
	}

	void collect(Client client, SpriteManager sprites, Graphics2D graphics, Rectangle viewport)
	{
		collect(client, sprites, graphics, viewport, (archive, file) -> null);
	}

	void collect(Client client, SpriteManager sprites, Graphics2D graphics, Rectangle viewport,
		BiFunction<Integer, Integer, BufferedImage> artwork)
	{
		hints.clear();
		templates.clear();
		hitsplats.entrySet().removeIf(entry -> entry.getValue() <= client.getGameCycle());
		Set<OverheadElements.Template> activeIcons = new LinkedHashSet<>();
		for (Player player : client.getPlayers())
		{
			if (player.getSkullIcon() >= 0) { icon(sprites, SpriteID.HEADICONS_PK, player.getSkullIcon(), Kind.SKULL, activeIcons, artwork); }
			if (player.getOverheadIcon() != null) { icon(sprites, SpriteID.HEADICONS_PRAYER, player.getOverheadIcon().ordinal(), Kind.PRAYER, activeIcons, artwork); }
			actor(player, graphics, viewport);
		}
		for (NPC npc : client.getNpcs())
		{
			int[] archives = npc.getOverheadArchiveIds();
			short[] files = npc.getOverheadSpriteIds();
			if (archives != null && files != null)
			{
				for (int i = 0; i < Math.min(archives.length, files.length); i++)
				{
					if (archives[i] >= 0 && files[i] >= 0) { icon(sprites, archives[i], files[i], Kind.PRAYER, activeIcons, artwork); }
				}
			}
			actor(npc, graphics, viewport);
		}
		// Hint arrows belong to none of the five size controls, even over an active hit.
		icon(sprites, SpriteID.HEADICONS_HINT, 0, Kind.NATIVE, activeIcons, artwork);
		icon(sprites, SpriteID.HEADICONS_HINT, 1, Kind.NATIVE, activeIcons, artwork);
		templates.addAll(activeIcons);
		templates.addAll(healthBars.values());
	}

	private void actor(Actor actor, Graphics2D graphics, Rectangle viewport)
	{
		String text = actor.getOverheadText();
		boolean speech = text != null && !text.isEmpty() && actor.getOverheadCycle() > 0;
		boolean health = actor.getHealthRatio() >= 0;
		Point head = speech || health ? actor.getCanvasImageLocation(ANCHOR, actor.getLogicalHeight()) : null;
		if (head != null)
		{
			int x = head.getX() - viewport.x, y = head.getY() - viewport.y;
			if (speech)
			{
				int width = graphics.getFontMetrics(FontManager.getRunescapeBoldFont()).stringWidth(text);
				hints.add(new OverheadElements.Element(new Rectangle(x - width / 2 - 32, y - 80, width + 64, 96), Kind.TEXT));
			}
			if (health)
			{
				hints.add(new OverheadElements.Element(new Rectangle(x - 256, y - 100, 512, 120), Kind.HEALTH_BAR));
			}
		}
		if (hitsplats.containsKey(actor))
		{
			Point body = actor.getCanvasImageLocation(ANCHOR, actor.getLogicalHeight() / 2);
			if (body != null)
			{
				hints.add(new OverheadElements.Element(new Rectangle(body.getX() - viewport.x - 80,
					body.getY() - viewport.y - 50, 160, 100), Kind.HITSPLAT));
			}
		}
	}

	private void icon(SpriteManager sprites, int archive, int file, Kind kind, Set<OverheadElements.Template> active,
		BiFunction<Integer, Integer, BufferedImage> artwork)
	{
		long key = (long) archive << 32 | (long) kind.ordinal() << 24 | file;
		OverheadElements.Template template = icons.get(key);
		if (template == null)
		{
			BufferedImage image = sprites.getSprite(archive, file);
			if (image == null) { return; }
			template = new OverheadElements.Template(image, kind);
			if (icons.size() >= 128) { icons.remove(icons.keySet().iterator().next()); }
			icons.put(key, template);
		}
		template.artwork = kind == Kind.PRAYER ? artwork.apply(archive, file) : null;
		active.add(template);
	}

	void clear()
	{
		hitsplats.clear(); icons.clear(); healthBars.clear(); hints.clear(); templates.clear();
	}

	void clearActors() { hitsplats.clear(); hints.clear(); templates.clear(); }
}
