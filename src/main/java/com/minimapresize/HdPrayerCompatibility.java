package com.minimapresize;

import java.awt.image.BufferedImage;
import java.util.function.BiFunction;
import net.runelite.client.events.PluginMessage;

/** Optional, identity-owned HD UI overhead source; no plugin implementation dependency. */
final class HdPrayerCompatibility
{
	private Object owner;
	private BiFunction<Integer, Integer, BufferedImage> provider;

	@SuppressWarnings("unchecked")
	void update(PluginMessage event)
	{
		if (!"hd-ui".equals(event.getNamespace()) || !"overhead-provider".equals(event.getName())) { return; }
		Object source = event.getData().get("owner");
		if (source == null) { return; }
		if (Boolean.TRUE.equals(event.getData().get("enabled")) && event.getData().get("provider") instanceof BiFunction)
		{
			owner = source;
			provider = (BiFunction<Integer, Integer, BufferedImage>) event.getData().get("provider");
		}
		else if (owner == source) { clear(); }
	}

	boolean active() { return provider != null; }
	BufferedImage image(int archive, int file) { return provider == null ? null : provider.apply(archive, file); }
	void clear() { owner = null; provider = null; }
}
