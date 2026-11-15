package com.minimapresize;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(MinimapResizeConfig.GROUP)
public interface MinimapResizeConfig extends Config
{
	String GROUP = "minimapresize";

	@ConfigSection(name = "Minimap", description = "Scale the minimap, compass and orbs together", position = 0)
	String MINIMAP = "minimap";

	@ConfigSection(name = "Modern layout tab bars", description = "Scale the two tab button bars independently", position = 10)
	String TAB_BARS = "tabBars";

	@ConfigSection(name = "Inventory / side panel", description = "Scale the open inventory, spellbook, prayer or other side panel", position = 20)
	String SIDE_PANEL = "sidePanel";

	@ConfigSection(name = "Filtering & sharpening", description = "UI image quality and shared refresh settings; the minimap has its own filter", position = 30)
	String FILTERING = "filtering";

	@ConfigSection(name = "Additional UI sizes", description = "Independent sizes without global Stretched Mode", position = 25)
	String ADDITIONAL = "additional";

	@ConfigSection(name = "Interaction compatibility", description = "Compatibility with plugins that use widgets without moving the mouse", position = 40)
	String INTERACTION = "interaction";

	@ConfigItem(keyName = "preservePanelActions", name = "Preserve off-hover panel actions", position = 0, section = INTERACTION,
		description = "Disable off-hover widget-tree gating, including frame/tab ancestors, for clients/plugins that use widgets directly. Mouse scaling is unchanged, but original widget locations can intercept clicks when regions are moved, shrunk or overlap. Unfinished opening banks remain gated. Off by default.")
	default boolean preservePanelActions() { return false; }

	// Hidden migration default: preserve sizes chosen before the individual controls existed.
	@ConfigItem(keyName = "overheadScale", name = "Legacy overhead size", hidden = true, section = ADDITIONAL,
		description = "Default for the separate overhead size controls.")
	@Range(min = 50, max = 400)
	@Units(Units.PERCENT)
	default int overheadScale() { return 100; }

	@ConfigItem(keyName = "healthBarScale", name = "Health bar size", position = 0, section = ADDITIONAL,
		description = "Native actor health bars. GPU/117 HD required. Unrecognized or inseparable overlapping indicators retain their normal size.")
	@Range(min = 50, max = 400)
	@Units(Units.PERCENT)
	default int healthBarScale() { return overheadScale(); }

	@ConfigItem(keyName = "hitsplatScale", name = "Hitsplat size", position = 1, section = ADDITIONAL,
		description = "Native damage splats, including their numbers. GPU/117 HD required. Inseparable overlapping indicators retain their normal size.")
	@Range(min = 50, max = 400)
	@Units(Units.PERCENT)
	default int hitsplatScale() { return overheadScale(); }

	@ConfigItem(keyName = "prayerIconScale", name = "Prayer icon size", position = 2, section = ADDITIONAL,
		description = "Player and NPC overhead prayer icons. GPU/117 HD required. Unrecognized or occluded icons retain their normal size.")
	@Range(min = 50, max = 400)
	@Units(Units.PERCENT)
	default int prayerIconScale() { return overheadScale(); }

	@ConfigItem(keyName = "skullIconScale", name = "Skull icon size", position = 3, section = ADDITIONAL,
		description = "Player overhead skull and loot-key icons. GPU/117 HD required. Unrecognized or occluded icons retain their normal size.")
	@Range(min = 50, max = 400)
	@Units(Units.PERCENT)
	default int skullIconScale() { return overheadScale(); }

	@ConfigItem(keyName = "overheadTextScale", name = "Overhead text size", position = 4, section = ADDITIONAL,
		description = "Native speech above players and NPCs. GPU/117 HD required. Ambiguous overlaps retain their normal size.")
	@Range(min = 50, max = 400)
	@Units(Units.PERCENT)
	default int overheadTextScale() { return overheadScale(); }

	@ConfigItem(keyName = "menuScale", name = "Right-click menu size", position = 5, section = ADDITIONAL,
		description = "Scale right-click menus and their input together. 100% is normal.")
	@Range(min = 50, max = 400)
	@Units(Units.PERCENT)
	default int menuScale() { return 100; }

	@ConfigItem(keyName = "chatScale", name = "Chat size", position = 6, section = ADDITIONAL,
		description = "Scale the chatbox, input line and chat controls from the bottom-left corner.")
	@Range(min = 50, max = 400)
	@Units(Units.PERCENT)
	default int chatScale() { return 100; }

	@ConfigItem(keyName = "hoverScale", name = "Hover text / tooltip size", position = 7, section = ADDITIONAL,
		description = "Scale RuneLite hover tooltips and the native top-left mouseover text.")
	@Range(min = 50, max = 400)
	@Units(Units.PERCENT)
	default int hoverScale() { return 100; }

	@ConfigItem(keyName = "interfaceScale", name = "Bank / GE / interfaces size", position = 8, section = ADDITIONAL,
		description = "Shared size for bank, GE and other central windows. Responsive windows reflow into a smaller native layout before scaling, like Stretched Mode. Fits all controls on screen.")
	@Range(min = 50, max = 400)
	@Units(Units.PERCENT)
	default int interfaceScale() { return 100; }

	@ConfigItem(keyName = "clickMarkerScale", name = "Click marker size", position = 9, section = ADDITIONAL,
		description = "Scale the yellow walking/running click crosses and red object/NPC interaction crosses. 100% is normal.")
	@Range(min = 50, max = 400)
	@Units(Units.PERCENT)
	default int clickMarkerScale() { return 100; }

	@ConfigItem(keyName = "scale", name = "Minimap size", position = 0, section = MINIMAP,
		description = "Size of the whole minimap cluster. 100% is normal. Requires a resizable layout.")
	@Range(min = 50, max = 400)
	@Units(Units.PERCENT)
	default int scale()
	{
		return 150;
	}

	@ConfigItem(keyName = "offsetX", name = "Move left", position = 1, section = MINIMAP,
		description = "Move the resized minimap left from its usual right edge, in game pixels.")
	@Range(min = 0, max = 2000)
	default int offsetX()
	{
		return 0;
	}

	@ConfigItem(keyName = "offsetY", name = "Move down", position = 2, section = MINIMAP,
		description = "Move the resized minimap down from its usual top edge, in game pixels.")
	@Range(min = 0, max = 2000)
	default int offsetY()
	{
		return 0;
	}

	@ConfigItem(keyName = "minimapFilter", name = "Minimap filter", position = 3, section = MINIMAP,
		description = "With HD UI's minimap enabled, filters only the rotating map terrain. Borders, controls and text use HD UI settings. Bilinear is fast; Nearest is fastest.")
	default ScalingFilter minimapFilter()
	{
		// Preserve existing inexpensive/pixel-sharp preferences, but do not apply
		// a costly static-UI filter to the rotating map unless explicitly selected.
		ScalingFilter shared = filter();
		return shared == ScalingFilter.NEAREST || shared == ScalingFilter.BILINEAR || shared == ScalingFilter.HYBRID
			? shared : ScalingFilter.BILINEAR;
	}

	// Retain the old key so existing users keep their chosen filter until they select a new one.
	@ConfigItem(keyName = "smooth", name = "Smooth scaling", position = 30, section = FILTERING, hidden = true,
		description = "Use bilinear filtering. Disable for sharp, pixelated scaling.")
	default boolean smooth()
	{
		return false;
	}

	@ConfigItem(keyName = "filter", name = "Scaling filter", position = 31, section = FILTERING,
		description = "Try xBR for pixel-art smoothing, Hybrid for crisp text, or Bicubic/Lanczos for fine detail. Your renderer still applies its final UI filter. The minimap has its own filter in the Minimap section.")
	default ScalingFilter filter()
	{
		return smooth() ? ScalingFilter.BILINEAR : ScalingFilter.NEAREST;
	}

	@ConfigItem(keyName = "sharpening", name = "Sharpening strength", position = 32, section = FILTERING,
		description = "Sharp Bicubic only. Start at 40%. Higher values add contrast; 0% is plain Bicubic.")
	@Range(min = 0, max = 100)
	@Units(Units.PERCENT)
	default int sharpening()
	{
		return 40;
	}

	@ConfigItem(keyName = "scalingFps", name = "Scaling refresh limit", position = 33, section = FILTERING,
		description = "Maximum scaling updates per second, not a delay. Try 30 to reduce CPU use; 120 allows twice as many updates as 60. 0 is unlimited. Input and drawing still run each game frame.")
	@Range(min = 0, max = 240)
	@Units("fps")
	default int scalingFps() { return 60; }

	@ConfigItem(keyName = "renderTimings", name = "Log render timings", position = 34, section = FILTERING,
		description = "Log resizer CPU timings every 10 seconds for FPS troubleshooting. Compare a closed panel, an open panel and camera movement. Disable after testing.")
	default boolean renderTimings() { return false; }

	@ConfigItem(keyName = "upperTabScale", name = "Upper tab bar size", position = 11, section = TAB_BARS,
		description = "Combat, inventory, prayer, spellbook, etc. Modern layout only. 100% is normal.")
	@Range(min = 50, max = 400)
	@Units(Units.PERCENT)
	default int upperTabScale() { return 100; }

	@ConfigItem(keyName = "upperTabLeft", name = "Upper bar: move left", position = 12, section = TAB_BARS,
		description = "Move the upper/movable tab bar left, in game pixels.")
	@Range(min = 0, max = 2000)
	default int upperTabLeft() { return 0; }

	@ConfigItem(keyName = "upperTabUp", name = "Upper bar: move up", position = 13, section = TAB_BARS,
		description = "Move the upper/movable tab bar up, in game pixels.")
	@Range(min = 0, max = 2000)
	default int upperTabUp() { return 0; }

	@ConfigItem(keyName = "lowerTabScale", name = "Lower tab bar size", position = 14, section = TAB_BARS,
		description = "Social, settings and other lower-row tabs. Modern layout only. 100% is normal.")
	@Range(min = 50, max = 400)
	@Units(Units.PERCENT)
	default int lowerTabScale() { return 100; }

	@ConfigItem(keyName = "lowerTabLeft", name = "Lower bar: move left", position = 15, section = TAB_BARS,
		description = "Move the lower/static tab bar left, in game pixels.")
	@Range(min = 0, max = 2000)
	default int lowerTabLeft() { return 0; }

	@ConfigItem(keyName = "lowerTabUp", name = "Lower bar: move up", position = 16, section = TAB_BARS,
		description = "Move the lower/static tab bar up, in game pixels.")
	@Range(min = 0, max = 2000)
	default int lowerTabUp() { return 0; }

	@ConfigItem(keyName = "panelScale", name = "Inventory / panel size", position = 21, section = SIDE_PANEL,
		description = "Classic: full inventory frame and tabs. Modern: open side panel. Updated HD UI combines panel resizing, Stretched Mode and display DPI into final-resolution item artwork.")
	@Range(min = 50, max = 400)
	@Units(Units.PERCENT)
	default int panelScale() { return 100; }

	@ConfigItem(keyName = "panelLeft", name = "Panel: move left", position = 22, section = SIDE_PANEL,
		description = "Move the scaled side panel left from its original right edge, in game pixels.")
	@Range(min = 0, max = 2000)
	default int panelLeft() { return 0; }

	@ConfigItem(keyName = "panelUp", name = "Panel: move up", position = 23, section = SIDE_PANEL,
		description = "Move the scaled side panel up from its original bottom edge, in game pixels.")
	@Range(min = 0, max = 2000)
	default int panelUp() { return 0; }
}
