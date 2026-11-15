package com.minimapresize;

import java.awt.Graphics2D;
import java.awt.AlphaComposite;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.function.Consumer;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;

/** Draws HD UI's original-resolution icons after panel filtering, in its existing logical coordinates. */
final class HdUiCompatibility
{
	private static final String PAINTER = "com.hdui.ScaledInventoryOverlay";
	// Must match HD UI's logical routes. Upper/lower strips share one native
	// interface ID, so interface IDs alone cannot select the correct transform.
	static final int CLASSIC_TABS = -62001, UPPER_TABS = -62002, LOWER_TABS = -62003;
	static final int MINIMAP = -62004, COMPASS = -62005;
	private final OverlayManager manager;
	private final RenderTimings timings;
	private Overlay painter;
	private OverlayLayer originalLayer;
	private final Map<Set<Integer>, Layer> layers = new LinkedHashMap<Set<Integer>, Layer>(8, .75f, true)
	{
		@Override protected boolean removeEldestEntry(Map.Entry<Set<Integer>, Layer> entry) { return size() > 8; }
	};
	private static final class Layer
	{
		BufferedImage image;
		Long signature;
		Rectangle source, destination, area;
		boolean[] nativeMask;
		final CachedUiBlitter blitter = new CachedUiBlitter();
	}
	private long revision = 1;
	private final Map<Set<Integer>, Long> refreshed = new HashMap<>();
	private final Set<Set<Integer>> painted = new HashSet<>();
	private final Consumer<PluginMessage> messages;
	private Consumer<PluginMessage> renderBridge;
	private Overlay bridgeOwner;
	private Set<Integer> regions = Set.of();
	private boolean regionsKnown;
	private boolean minimapSupported;
	private Overlay probedMinimapPainter;
	private Map<String,Object> minimapStyle = Map.of();
	private final Map<BufferedImage,WidgetImageScaler> samplers = new LinkedHashMap<BufferedImage,WidgetImageScaler>(64,.75f,true)
	{ @Override protected boolean removeEldestEntry(Map.Entry<BufferedImage,WidgetImageScaler> e) { return size()>128; } };
	private final java.util.function.Function<Map<String,Object>,BufferedImage> minimapResampler = this::sampleMinimap;
	private static final Set<Integer> SIDE = Set.of(InterfaceID.INVENTORY, InterfaceID.BANKSIDE, InterfaceID.GE_OFFERS_SIDE,
		InterfaceID.TRADESIDE, InterfaceID.SHOPSIDE, InterfaceID.GE_PRICECHECKER_SIDE,
		InterfaceID.SEED_VAULT_DEPOSIT, InterfaceID.RAIDS_STORAGE_SIDE, InterfaceID.WILDERNESS_LOOTINGBAG,
		InterfaceID.MAGIC_SPELLBOOK, InterfaceID.PRAYERBOOK, InterfaceID.QUICKPRAYER,
		InterfaceID.EMOTE, InterfaceID.STATS, InterfaceID.COMBAT_INTERFACE, InterfaceID.AUTOCAST,
		InterfaceID.WORNITEMS, InterfaceID.EQUIPMENT_SIDE, InterfaceID.SideJournal.UNIVERSE >>> 16, CLASSIC_TABS);

	static Set<Integer> sideGroups() { return SIDE; }

	static Set<Integer> groupsForRegion(WidgetRegion region, int visibleGroup)
	{
		if (region == WidgetRegion.SIDE_PANEL) { return SIDE; }
		if (region == WidgetRegion.UPPER_TABS) { return Set.of(UPPER_TABS); }
		if (region == WidgetRegion.LOWER_TABS) { return Set.of(LOWER_TABS); }
		if (region == WidgetRegion.MINIMAP) { return Set.of(MINIMAP, COMPASS); }
		return region.interfaceWindow() && visibleGroup >= 0 ? Set.of(visibleGroup) : Set.of();
	}

	static Set<Integer> groupsForInterface(int group)
	{
		if (group == InterfaceID.EQUIPMENT) { return Set.of(group, InterfaceID.EQUIPMENT_SIDE); }
		if (group == InterfaceID.BANKMAIN || group == InterfaceID.SHARED_BANK) { return Set.of(group, InterfaceID.BANKSIDE); }
		if (group == InterfaceID.GE_OFFERS) { return Set.of(group, InterfaceID.GE_OFFERS_SIDE); }
		if (group == InterfaceID.TRADEMAIN || group == InterfaceID.TRADECONFIRM) { return Set.of(group, InterfaceID.TRADESIDE); }
		if (group == InterfaceID.SHOPMAIN) { return Set.of(group, InterfaceID.SHOPSIDE); }
		if (group == InterfaceID.GE_PRICECHECKER) { return Set.of(group, InterfaceID.GE_PRICECHECKER_SIDE); }
		if (group == InterfaceID.SEED_VAULT) { return Set.of(group, InterfaceID.SEED_VAULT_DEPOSIT); }
		if (group == InterfaceID.RAIDS_STORAGE_PRIVATE || group == InterfaceID.RAIDS_STORAGE_SHARED)
		{
			return Set.of(group, InterfaceID.RAIDS_STORAGE_SIDE);
		}
		// The HD plugin chooses which item widgets it supports. Every central host can provide
		// a scoped pass, including reward windows added later, with the ordinary inventory separate.
		return group >= 0 && group != InterfaceID.INVENTORY ? Set.of(group, InterfaceID.INVENTORY) : Set.of();
	}

	HdUiCompatibility(OverlayManager manager) { this(manager, message -> { }); }
	HdUiCompatibility(OverlayManager manager, Consumer<PluginMessage> messages)
	{
		this(manager, messages, null);
	}
	HdUiCompatibility(OverlayManager manager, Consumer<PluginMessage> messages, RenderTimings timings)
	{
		this.manager = manager; this.messages = messages; this.timings = timings;
	}

	void beginFrame(boolean needed)
	{
		painted.clear();
		if (!needed) { restore(); return; }
		if (painter != null && !manager.anyMatch(overlay -> overlay == painter))
		{
			painter.setLayer(originalLayer);
			painter = null;
			renderBridge = null; bridgeOwner = null;
			layers.clear();
			revision++;
		}
		if (painter == null)
		{
			manager.anyMatch(overlay ->
			{
				if (!PAINTER.equals(overlay.getClass().getName()) || overlay.getLayer() != OverlayLayer.ALWAYS_ON_TOP
					|| !overlay.getDrawHooks().isEmpty()) { return false; }
				painter = overlay;
				return true;
			});
			if (painter != null)
			{
				originalLayer = painter.getLayer();
				manager.remove(painter);
				painter.setLayer(OverlayLayer.MANUAL);
				manager.add(painter);
				revision++;
				bridgeOwner=painter;
				messages.accept(new PluginMessage("hd-ui","request-render-bridge",Map.of("painter",painter,
					"receiver",(Consumer<Consumer<PluginMessage>>) bridge -> renderBridge=bridge)));
				publish("scaled-regions", regions);
				send(new PluginMessage("hd-ui","minimap-resampler",Map.of("painter",painter,"provider",minimapResampler)));
				send(new PluginMessage("hd-ui","final-output-capability",Map.of("painter",painter,"supported",true)));
			}
		}
	}

	void regions(Set<Integer> groups)
	{
		regionsKnown = true;
		if (regions.equals(groups)) { return; }
		regions = Set.copyOf(groups);
		revision++;
		publish("scaled-regions", regions);
	}

	private void publish(String name, Set<Integer> groups)
	{
		if (painter != null) { send(new PluginMessage("hd-ui", name, Map.of("painter", painter, "groups", groups))); }
	}

	private void send(PluginMessage message)
	{
		// Negotiate once through the public bus. Frequent render queries then go
		// only to their owning painter, not every plugin subscribed to PluginMessage.
		if(renderBridge!=null&&message.getData().get("painter")==bridgeOwner) { renderBridge.accept(message); }
		else { messages.accept(message); }
	}

	void nativeChanged(Object owner)
	{
		if (painter != null && painter == owner) { revision++; }
	}

	void repeatRegions(Object owner)
	{
		if (painter != null && painter == owner) { publish("scaled-regions", regions); }
	}

	boolean refreshPending() { return refreshPending(SIDE); }
	boolean active() { return painter != null; }
	boolean available() { return painter != null || manager.anyMatch(overlay -> PAINTER.equals(overlay.getClass().getName())); }
	boolean minimapAvailable()
	{
		Overlay[] found = {painter != null && manager.anyMatch(o -> o == painter) ? painter : null};
		if (found[0] == null)
		{
			manager.anyMatch(overlay ->
			{
				if (!PAINTER.equals(overlay.getClass().getName())) { return false; }
				found[0] = overlay; return true;
			});
		}
		if (found[0] != probedMinimapPainter)
		{
			probedMinimapPainter = found[0]; minimapSupported = false;
			if (found[0] != null)
			{
				send(new PluginMessage("hd-ui", "request-minimap-capability", Map.of("painter", found[0],
					"receiver", (Consumer<Boolean>) supported -> minimapSupported = Boolean.TRUE.equals(supported))));
			}
		}
		if (found[0] != null)
		{
			send(new PluginMessage("hd-ui","request-minimap-style",Map.of("painter",found[0],
				"receiver",(Consumer<Map<String,Object>>) style -> minimapStyle=Map.copyOf(style))));
		}
		return minimapSupported && (!(minimapStyle.get("version") instanceof Integer) || Boolean.TRUE.equals(minimapStyle.get("enabled")));
	}
	boolean minimapCompositor()
	{
		Overlay owner=painter!=null?painter:probedMinimapPainter;
		return owner!=null && manager.anyMatch(o -> o==owner) && minimapStyle.get("version") instanceof Integer
			&& (Integer)minimapStyle.get("version")>=2 && Boolean.TRUE.equals(minimapStyle.get("enabled"));
	}
	ScalingFilter minimapUiFilter()
	{
		try { return ScalingFilter.valueOf(String.valueOf(minimapStyle.get("filter"))); }
		catch (IllegalArgumentException ex) { return ScalingFilter.BICUBIC; }
	}
	int minimapUiSharpening() { Object value=minimapStyle.get("sharpening");return value instanceof Integer ? Math.max(0,Math.min(100,(Integer)value)):35; }
	BufferedImage minimapText(BufferedImage image,int width,int height)
	{ return minimapText(image,width,height,null); }
	BufferedImage minimapText(BufferedImage image,int width,int height,Rectangle bounds)
	{
		BufferedImage[] result={null};
		Map<String,Object> request=new HashMap<>(Map.of("painter",painter,"image",image,"width",width,"height",height,
			"receiver",(Consumer<BufferedImage>) prepared -> result[0]=prepared));
		if(bounds!=null) { request.put("bounds",new Rectangle(bounds)); }
		send(new PluginMessage("hd-ui","prepare-minimap-text",request));
		return result[0]!=null?result[0]:samplers.computeIfAbsent(image,ignored -> new WidgetImageScaler()).scale(image,width,height,ScalingFilter.NEAREST,0);
	}
	private BufferedImage sampleMinimap(Map<String,Object> request)
	{
		BufferedImage image=(BufferedImage)request.get("image");int width=(Integer)request.get("width"),height=(Integer)request.get("height");
		ScalingFilter filter;
		try { filter=ScalingFilter.valueOf(String.valueOf(request.get("filter"))); } catch(IllegalArgumentException ex) { filter=ScalingFilter.BICUBIC; }
		int strength=request.get("sharpening") instanceof Integer?(Integer)request.get("sharpening"):0;
		return samplers.computeIfAbsent(image,ignored -> new WidgetImageScaler()).scale(image,width,height,filter,strength,false);
	}
	void refreshed() { refreshed(SIDE); }
	boolean refreshPending(Set<Integer> groups) { return !Long.valueOf(revision).equals(refreshed.get(groups)); }
	void refreshed(Set<Integer> groups) { refreshed.put(Set.copyOf(groups), revision); }

	void paint(Graphics2D graphics, Rectangle source, Rectangle destination, BufferedImage target, boolean gpu)
	{
		paint(graphics, source, destination, target, gpu, SIDE);
	}

	void paint(Graphics2D graphics, Rectangle source, Rectangle destination, BufferedImage target, boolean gpu, Set<Integer> groups)
	{
		long started=timings==null?0:timings.start();
		try { paintMeasured(graphics,source,destination,target,gpu,groups); }
		finally
		{
			if(timings!=null) { timings.finish(groups.equals(Set.of(COMPASS))?RenderTimings.Stage.HD_COMPASS:RenderTimings.Stage.HD_ARTWORK,started); }
		}
	}

	private void paintMeasured(Graphics2D graphics, Rectangle source, Rectangle destination, BufferedImage target, boolean gpu, Set<Integer> groups)
	{
		if (painter == null || source == null || source.isEmpty() || destination.isEmpty()
			|| regionsKnown && java.util.Collections.disjoint(regions, groups) || !painted.add(Set.copyOf(groups))) { return; }
		publish("render-groups", groups);
		boolean[] finalOutput={false};
		send(new PluginMessage("hd-ui","final-output-region",Map.of("painter",painter,"groups",groups,
			"source",new Rectangle(source),"destination",new Rectangle(destination),"receiver",(Consumer<Boolean>) handled -> finalOutput[0]=Boolean.TRUE.equals(handled))));
		try { render(graphics, source, destination, target, gpu, groups, finalOutput[0]); }
		finally { publish("render-groups", Set.of()); }
	}

	private void render(Graphics2D graphics, Rectangle source, Rectangle destination, BufferedImage target, boolean gpu, Set<Integer> groups, boolean finalOutput)
	{
		// Only the minimap consumes supplemental painted-pixel coverage.
		// Banks/side panels use rectangular input; tab input is supplied by its
		// native region. Once HD UI accepts final output, a second native-size
		// artwork pass for these regions is neither displayed nor read.
		if (finalOutput && !groups.contains(MINIMAP)) { return; }
		Long[] state = {null};
		Rectangle[] content = {null};
		// Older HD versions leave the optional bounds unanswered. Keep their full
		// region fallback, but do not clear/scan a minimap-sized image for a compass.
		send(new PluginMessage("hd-ui", "request-layer-state", Map.of("painter", painter,
			"receiver", (Consumer<Long>) signature -> state[0] = signature,
			"boundsReceiver", (Consumer<Rectangle>) bounds -> content[0] = bounds)));
		Rectangle area = new Rectangle(destination);
		if (content[0] != null)
		{
			if (content[0].isEmpty()) { return; }
			double sx=(double)destination.width/source.width,sy=(double)destination.height/source.height;
			int x=(int)Math.floor(destination.x+(content[0].x-source.x)*sx);
			int y=(int)Math.floor(destination.y+(content[0].y-source.y)*sy);
			int right=(int)Math.ceil(destination.x+(content[0].x+content[0].width-source.x)*sx);
			int bottom=(int)Math.ceil(destination.y+(content[0].y+content[0].height-source.y)*sy);
			area=new Rectangle(x,y,right-x,bottom-y);
		}
		Rectangle extra = painter.getBounds();
		if (extra != null && !extra.isEmpty())
		{
			double sx = (double) destination.width / source.width, sy = (double) destination.height / source.height;
			int left = (int) Math.floor(destination.x + (extra.x - source.x) * sx);
			int top = (int) Math.floor(destination.y + (extra.y - source.y) * sy);
			int right = (int) Math.ceil(destination.x + (extra.x + extra.width - source.x) * sx);
			int bottom = (int) Math.ceil(destination.y + (extra.y + extra.height - source.y) * sy);
			area = area.union(new Rectangle(left, top, Math.max(0, right - left), Math.max(0, bottom - top)));
		}
		if (target != null) { area = area.intersection(new Rectangle(0, 0, target.getWidth(), target.getHeight())); }
		if (area.isEmpty()) { return; }
		Layer cached = layers.computeIfAbsent(Set.copyOf(groups), ignored -> new Layer());
		boolean changed = state[0] == null || !state[0].equals(cached.signature)
			|| !source.equals(cached.source) || !destination.equals(cached.destination) || !area.equals(cached.area);
		if (cached.image == null || cached.image.getWidth() != area.width || cached.image.getHeight() != area.height)
		{
			cached.image = new BufferedImage(area.width, area.height, BufferedImage.TYPE_INT_ARGB_PRE);
			changed = true;
		}
		if (changed)
		{
			cached.nativeMask = null;
			Graphics2D copy = cached.image.createGraphics();
			try
			{
				copy.setComposite(AlphaComposite.Clear);
				copy.fillRect(0, 0, area.width, area.height);
				copy.setComposite(AlphaComposite.SrcOver);
				copy.clipRect(0, 0, area.width, area.height);
				copy.translate(destination.x - area.x, destination.y - area.y);
				copy.scale((double) destination.width / source.width, (double) destination.height / source.height);
				copy.translate(-source.x, -source.y);
				painter.render(copy);
			}
			finally { copy.dispose(); }
			cached.signature = state[0];
			cached.source = new Rectangle(source);
			cached.destination = new Rectangle(destination);
			cached.area = new Rectangle(area);
		}
		// Keep the native preview for hit masks, but the original HD materials
		// are drawn at the combined resizer/global-stretch resolution instead.
		if(!finalOutput) { cached.blitter.draw(target, cached.image, area.x, area.y, graphics, gpu, changed); }
	}

	/** Immutable native-coordinate coverage for painted controls omitted from capture. */
	boolean[] minimapInputMask()
	{
		Layer layer = layers.get(Set.of(MINIMAP));
		if (layer == null || layer.image == null || layer.source == null) { return null; }
		if (layer.nativeMask == null)
		{
			Rectangle source = layer.source, destination = layer.destination, area = layer.area;
			int[] pixels = PackedPixels.alphaPixels(layer.image);
			boolean[] mask = new boolean[source.width * source.height];
			for (int y = 0, i = 0; y < source.height; y++)
			{
				int py = (int) Math.floor(destination.y + (y + .5) * destination.height / source.height) - area.y;
				for (int x = 0; x < source.width; x++, i++)
				{
					int px = (int) Math.floor(destination.x + (x + .5) * destination.width / source.width) - area.x;
					mask[i] = px >= 0 && py >= 0 && px < area.width && py < area.height
						&& pixels[py * area.width + px] >>> 24 != 0;
				}
			}
			layer.nativeMask = mask;
		}
		return layer.nativeMask;
	}

	void restore()
	{
		layers.clear();
		samplers.clear();
		if (painter == null) { return; }
		revision++;
		publish("scaled-regions", Set.of());
		send(new PluginMessage("hd-ui","minimap-resampler",Map.of("painter",painter)));
		boolean registered = manager.remove(painter);
		painter.setLayer(originalLayer);
		if (registered) { manager.add(painter); }
		painter = null;
		renderBridge = null; bridgeOwner = null;
		minimapSupported = false;
		probedMinimapPainter = null;
		minimapStyle=Map.of();
	}
}
