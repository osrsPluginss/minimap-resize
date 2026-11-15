package com.minimapresize;

import com.google.inject.Provides;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.VarClientInt;
import net.runelite.api.MainBufferProvider;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.BeforeMenuRender;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.CanvasSizeChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.FocusChanged;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.PostClientTick;
import net.runelite.api.events.PostHealthBarConfig;
import net.runelite.api.events.ResizeableChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.camera.CameraConfig;
import net.runelite.client.plugins.camera.CameraPlugin;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
	name = "Inventory & Minimap Resize",
	description = "Independent inventory, minimap, chat, menu, overhead, tooltip and interface scaling",
	tags = {"minimap", "resize", "scale", "orbs", "tabs", "stretch", "filter", "sharpen", "inventory"}
)
public class MinimapResizePlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(MinimapResizePlugin.class);
	static final String VERSION = "0.4.23";
	@Inject private Client client;
	@Inject private MinimapResizeConfig config;
	@Inject private OverlayManager overlayManager;
	@Inject private MouseManager mouseManager;
	@Inject private ClientThread clientThread;
	@Inject private ConfigManager configManager;
	@Inject private PluginManager pluginManager;
	@Inject private EventBus eventBus;
	@Inject private net.runelite.client.game.SpriteManager spriteManager;
	private EventBus.Subscriber hoverRenderSubscription;
	@Inject private net.runelite.client.ui.overlay.tooltip.TooltipManager tooltipManager;
	@Inject private net.runelite.client.ui.overlay.tooltip.TooltipOverlay tooltipOverlay;
	private HoverScaler hoverScaler;
	private MinimapMouseListener mouseListener;
	private MenuRelocator menus;
	private HqIconCompatibility hqIcons;
	private HdUiCompatibility hdUiIcons;
	private CameraConfig cameraConfig;
	private volatile boolean cameraPluginActive;
	private volatile boolean stretchedModeActive;
	private LoginStretch loginStretch;
	private volatile int cameraButtons;
	private volatile Point cameraInputOffset;

	private final Map<WidgetRegion, RegionState> states = new EnumMap<>(WidgetRegion.class);
	private final List<Overlay> overlays = new ArrayList<>();
	private final WidgetInputGate inputGate = new WidgetInputGate();
	private final UiUnderlay underlay = new UiUnderlay();
	private final OverheadScaler overheadScaler = new OverheadScaler();
	private final OverheadContext overheadContext = new OverheadContext();
	private final MovementOverheadCompatibility movementOverheads = new MovementOverheadCompatibility();
	private final HdPrayerCompatibility hdPrayers = new HdPrayerCompatibility();
	private final ClickMarkerScaler clickMarkerScaler = new ClickMarkerScaler();
	private final InterfaceLayout interfaceLayout = new InterfaceLayout();
	private final RenderTimings renderTimings = new RenderTimings();
	private final AtomicReference<PendingPress> pendingPress = new AtomicReference<>();
	private final AtomicBoolean gateQueued = new AtomicBoolean();
	private volatile long pointerGateKey = Long.MIN_VALUE;
	private volatile List<MinimapInputFrame> inputFrames = Collections.emptyList();
	private volatile Point pointer = new Point(-1, -1);
	private volatile WidgetRegion dragTarget;
	private volatile WidgetRegion dropTarget;
	private volatile boolean dragReleased;
	private volatile boolean enabled;
	private boolean rendering;
	private boolean gateApplied;
	private boolean prayerOnly;
	private int lastSideTab = -1;
	private UnscaledDiagnostics unscaledDiagnostics;
	private int lastGatedRegions;
	private int interfaceDiagnosticsRemaining = 64;
	private int lastContainerTick = -1;
	private int bankGeometryDetailKinds;
	private volatile ResizableLayout layout = ResizableLayout.UNSUPPORTED;

	private static final class RegionState
	{
		final MinimapCompositor compositor = new MinimapCompositor();
		final MinimapLayers minimapLayers = new MinimapLayers();
		final WidgetImageScaler scaler = new WidgetImageScaler();
		final CachedUiBlitter blitter = new CachedUiBlitter();
		final InterfaceCapture interfaceCapture = new InterfaceCapture();
		Widget widget;
		Widget presentedInterfaceHost;
		Widget preparedInterfaceHost;
		int preparedInterfaceId = -1;
		Rectangle presentedSource;
		Rectangle presentedDestination;
		Rectangle source;
		MinimapTransform transform;
		BufferedImage foreground;
		MinimapInputFrame input;
		boolean rendered;
		boolean pendingMaskChange;
		boolean deferred;
		boolean suppressOpeningFrame;
		int interfaceId = -1;
		InterfaceScaleOverlay interfaceOverlay;
		int openingFrames;
		boolean openingWaitLogged;
	}

	private static final class PendingPress
	{
		final WidgetRegion target;
		final Point point;
		final long when;
		final long created = System.nanoTime();

		PendingPress(WidgetRegion target, Point point, long when)
		{
			this.target = target;
			this.point = new Point(point);
			this.when = when;
		}
	}

	@Provides
	MinimapResizeConfig provideConfig(ConfigManager manager)
	{
		MinimapResizeConfig provided = manager.getConfig(MinimapResizeConfig.class);
		// The settings panel reads stored enum names directly, not proxy defaults.
		// Initialize new keys even when the sideload path has not applied defaults
		// yet, including when settings are opened before the plugin is enabled.
		manager.setDefaultConfiguration(provided, false);
		return provided;
	}

	@Override
	protected void startUp()
	{
		log.info("Inventory & Minimap Resize {} starting", VERSION);
		interfaceDiagnosticsRemaining = 64;
		bankGeometryDetailKinds = 0;
		menus = new MenuRelocator(client, config);
		hoverScaler = new HoverScaler(client, overlayManager, tooltipManager, tooltipOverlay,
			configManager.getConfig(net.runelite.client.config.RuneLiteConfig.class), config);
		hqIcons = new HqIconCompatibility(overlayManager);
		hdUiIcons = new HdUiCompatibility(overlayManager, eventBus::post, renderTimings);
		unscaledDiagnostics = new UnscaledDiagnostics(overlayManager);
		cameraConfig = configManager.getConfig(CameraConfig.class);
		cameraPluginActive = false;
		stretchedModeActive = false;
		for (Plugin plugin : pluginManager.getPlugins())
		{
			if (plugin instanceof CameraPlugin && pluginManager.isPluginActive(plugin)) { cameraPluginActive = true; }
			if (plugin instanceof net.runelite.client.plugins.stretchedmode.StretchedModePlugin
				&& pluginManager.isPluginActive(plugin)) { stretchedModeActive = true; }
		}
		loginStretch = new LoginStretch(client, mouseManager,
			configManager.getConfig(net.runelite.client.plugins.stretchedmode.StretchedModeConfig.class));
		mouseListener = new MinimapMouseListener(client, this);
		for (WidgetRegion region : WidgetRegion.values())
		{
			states.put(region, new RegionState());
			for (int component : region.components)
			{
				overlays.add(new MinimapScaleOverlay(this, region, component));
			}
		}
		overlays.add(new MinimapCaptureOverlay(this));
		overlays.add(new OverheadOverlay(this, true));
		overlays.add(new OverheadOverlay(this, false));
		overlays.add(new HoverOverlay(this, true));
		overlays.add(new HoverOverlay(this, false));
		overlays.add(new ScaledWidgetsOverlay(this));
		overlays.add(new InputGateOverlay(this));
		overlays.add(new MenuBackgroundOverlay(menus));
		overlays.add(new MenuRelocationOverlay(menus));
		enabled = true;
		overlays.forEach(overlayManager::add);
		// A plugin cannot declare two annotated handlers with different names for
		// the same event. Keep hover preparation late without invalid registration.
		hoverRenderSubscription = eventBus.register(BeforeRender.class, event -> prepareHoverFrame(), -1000);
		// Stretched Mode registers at index zero; these receive game-space coordinates.
		mouseManager.registerMouseListener(mouseListener);
		mouseManager.registerMouseWheelListener(mouseListener);
		clientThread.invokeLater(this::updateCameraButtons);
		clientThread.invokeLater(() -> { if (enabled) { loginStretch.update(stretchedModeActive); } });
		clientThread.invokeLater(() -> eventBus.post(new PluginMessage("hd-ui", "request-overhead-provider", Map.of())));
	}

	@Override
	protected void shutDown()
	{
		enabled = false;
		if (unscaledDiagnostics != null) { unscaledDiagnostics.restore(); }
		hdPrayers.clear();
		eventBus.unregister(hoverRenderSubscription);
		hoverRenderSubscription = null;
		cameraButtons = 0;
		cameraInputOffset = null;
		inputFrames = Collections.emptyList();
		layout = ResizableLayout.UNSUPPORTED;
		pendingPress.set(null);
		dragTarget = null;
		dropTarget = null;
		mouseManager.unregisterMouseListener(mouseListener);
		mouseManager.unregisterMouseWheelListener(mouseListener);
		overlays.forEach(overlayManager::remove);
		overlays.clear();
		clientThread.invokeLater(() ->
		{
			loginStretch.close();
			underlay.restore(bufferImage());
			overheadScaler.reset(bufferImage());
			clickMarkerScaler.restore();
			hqIcons.restore();
			hdUiIcons.restore();
			hoverScaler.clear();
			inputGate.restore();
			interfaceLayout.restore();
			menus.reset();
			inputGate.invalidateTraversal();
			gateApplied = false;
			if (!enabled)
			{
				underlay.clear();
				overheadScaler.clear();
				overheadContext.clear();
				movementOverheads.clear();
				states.values().forEach(state ->
				{
					state.compositor.clear();
					state.scaler.clear();
					state.blitter.clear();
					state.interfaceCapture.restore(bufferImage());
					state.interfaceCapture.clear();
				});
				states.clear();
			}
		});
	}

	@Subscribe(priority = 1000)
	public void onBeforeRender(BeforeRender event)
	{
		renderTimings.beginFrame(config.renderTimings());
		long started = renderTimings.start();
		try { prepareFrame(); }
		finally { renderTimings.finish(RenderTimings.Stage.PREPARE, started); }
	}

	private void prepareFrame()
	{
		loginStretch.update(stretchedModeActive);
		// The renderer has started a new frame; never restore a previous frame's pixels.
		underlay.invalidate();
		overheadScaler.newFrame();
		clickMarkerScaler.update(client, enabled && client.getGameState() == GameState.LOGGED_IN
			? config.clickMarkerScale() : 100, config.filter(), config.sharpening());
		synchronizeLayout();
		if (unscaledDiagnostics != null) { unscaledDiagnostics.beginFrame(canScale() && scalingRequested(WidgetRegion.MINIMAP)); }
		hqIcons.beginFrame(canScale() && client.isGpu()
			&& (config.panelScale() != 100 || config.panelLeft() != 0 || config.panelUp() != 0));
		Set<Integer> hdRegions = new HashSet<>();
		if (canScale() && hdUiIcons.available())
		{
			if (hdUiIcons.minimapAvailable() && scalingRequested(WidgetRegion.MINIMAP) && WidgetRegion.MINIMAP.visibleWidget(client, layout) != null)
			{
				hdRegions.add(HdUiCompatibility.MINIMAP); hdRegions.add(HdUiCompatibility.COMPASS);
			}
			if (config.panelScale() != 100 || config.panelLeft() != 0 || config.panelUp() != 0)
			{
				hdRegions.addAll(HdUiCompatibility.sideGroups());
			}
			if (scalingRequested(WidgetRegion.UPPER_TABS) && WidgetRegion.UPPER_TABS.visibleWidget(client, layout) != null)
			{
				hdRegions.add(HdUiCompatibility.UPPER_TABS);
			}
			if (scalingRequested(WidgetRegion.LOWER_TABS) && WidgetRegion.LOWER_TABS.visibleWidget(client, layout) != null)
			{
				hdRegions.add(HdUiCompatibility.LOWER_TABS);
			}
			for (WidgetRegion region : WidgetRegion.values())
			{
				if (!region.interfaceWindow()) { continue; }
				int group = region.interfaceId(region.visibleWidget(client, layout));
				hdRegions.addAll(HdUiCompatibility.groupsForInterface(group));
			}
		}
		hdUiIcons.beginFrame(!hdRegions.isEmpty());
		hdUiIcons.regions(hdRegions);
		// Input property changes are only for hit testing, never for rendering.
		inputGate.restore();
		// Revalidate central hosts before the native draw pass caches screen X/Y.
		// Doing this first in UNDER_WIDGETS/the host callback can move the logical
		// host while the current draw still uses its old origin, clipping the bank.
		prepareInterfaceLayouts();
		gateApplied = false;
		rendering = true;
		for (RegionState state : states.values())
		{
			state.compositor.invalidate();
			state.interfaceCapture.invalidate();
			state.foreground = null;
			state.deferred = false;
			state.suppressOpeningFrame = false;
			if (!state.rendered || !canScale())
			{
				state.input = null;
			}
			state.rendered = false;
		}
		publishFrames();
	}

	private void prepareInterfaceLayouts()
	{
		BufferedImage image = bufferImage();
		if (!canScale() || image == null) { return; }
		for (Map.Entry<WidgetRegion, RegionState> entry : states.entrySet())
		{
			WidgetRegion region = entry.getKey();
			if (!region.interfaceWindow()) { continue; }
			Widget host = scalingRequested(region) ? region.visibleWidget(client, layout) : null;
			entry.getValue().preparedInterfaceHost = host;
			entry.getValue().preparedInterfaceId = region.interfaceId(host);
			boolean changed = interfaceLayout.update(region, host, config.interfaceScale(), image.getWidth(), image.getHeight());
			if (changed)
			{
				resizeBankFrame(region, host);
				entry.getValue().input = null;
			}
		}
	}

	private void resizeBankFrame(WidgetRegion region, Widget host)
	{
		if (host == null || region.interfaceId(host) != InterfaceID.BANKMAIN) { return; }
		Widget frame = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
		if (frame == null || frame.getParent() == null) { return; }
		// 839 is the timer clientscript; ScriptID.BANKMAIN_SIZE_CHECK (840) is
		// its nested proc and must not be invoked as a top-level script.
		// Native top-level resize can grow the parent and run the bank timer before
		// we shrink it again. Revalidation alone leaves the frame's absolute height
		// unchanged. Run its own size check now, including scrollbar rebuilding.
		client.runScript(839, InterfaceID.Bankmain.UNIVERSE, frame.getParent().getId(),
			-1, -1, InterfaceID.Bankmain.SCROLLBAR, InterfaceID.Bankmain.ITEMS);
	}

	@Subscribe
	public void onPluginMessage(PluginMessage event)
	{
		hdPrayers.update(event);
		if ("responsive-movement".equals(event.getNamespace()) && canScale() && client.isGpu())
		{
			movementOverheads.render(event, config, bufferImage(), true);
		}
		if ("hd-ui".equals(event.getNamespace()) && hdUiIcons != null)
		{
			if ("native-inventory-changed".equals(event.getName())) { hdUiIcons.nativeChanged(event.getData().get("painter")); }
			else if ("request-regions".equals(event.getName())) { hdUiIcons.repeatRegions(event.getData().get("painter")); }
		}
	}

	private void prepareHoverFrame()
	{
		hoverScaler.beginFrame(canScale() && config.hoverScale() > 0 && config.hoverScale() != 100);
	}

	void drawHover(Graphics2D graphics, boolean tooltip)
	{
		if (tooltip) { hoverScaler.drawTooltips(bufferImage(), graphics, pointer); }
		else { hoverScaler.drawNative(graphics); }
	}

	@Subscribe(priority = 1000)
	public void onClientTick(ClientTick event)
	{
		loginStretch.update(stretchedModeActive);
		// Recheck once per input cycle, plus actual crossings of native/scaled UI bounds.
		pointerGateKey = Long.MIN_VALUE;
		updateCameraButtons();
		synchronizeLayout();
		// ClientTick is AFTER the native interface input pass, BEFORE client scripts.
		// Restore here so scripts can update or intentionally hide the real widgets.
		inputGate.restore();
		inputGate.invalidateTraversal();
		gateApplied = false;
		PendingPress press = pendingPress.get();
		if (press != null && client.getMouseLastPressedMillis() >= press.when)
		{
			pendingPress.compareAndSet(press, null);
		}
		if (dragReleased && client.getMouseCurrentButton() == 0)
		{
			dragTarget = null;
			dropTarget = null;
			dragReleased = false;
		}
	}

	@Subscribe(priority = -1000)
	public void onPostClientTick(PostClientTick event)
	{
		updateCameraButtons();
		rendering = false;
		applyInputGate();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) { overheadContext.clearActors(); reset("game-state " + client.getGameState()); }

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event) { overheadContext.hitsplat(event); }

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		lastContainerTick = client.getTickCount();
		// Native item appearance/removal is interactive state, not a background
		// animation. Its next completed capture must bypass the scaler's FPS limit.
		for (Map.Entry<WidgetRegion, RegionState> entry : states.entrySet())
		{
			if (entry.getKey() == WidgetRegion.SIDE_PANEL || entry.getKey().interfaceWindow()) { entry.getValue().scaler.invalidate(); }
		}
	}

	@Subscribe
	public void onPostHealthBarConfig(PostHealthBarConfig event) { overheadContext.healthBar(event.getHealthBarConfig()); }

	@Subscribe
	public void onResizeableChanged(ResizeableChanged event) { loginStretch.resized(); reset("resizable-mode"); }

	@Subscribe
	public void onCanvasSizeChanged(CanvasSizeChanged event) { reset("canvas-size"); }

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		if (canScale() && synchronizeLayout()) { menus.opened(); }
		else { menus.reset(); }
	}

	@Subscribe(priority = 1000)
	public void onBeforeMenuRender(BeforeMenuRender event)
	{
		// This is after tooltip drawing and before native/custom menu painters.
		// The overlay capture remains a fallback for clients without this callback.
		if (canScale()) { menus.captureBackground(); }
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (MinimapResizeConfig.GROUP.equals(event.getGroup()))
		{
			inputFrames = Collections.emptyList();
			clientThread.invokeLater(() -> reset("config " + event.getKey()));
		}
		else if ("zoom".equals(event.getGroup()))
		{
			clientThread.invokeLater(this::updateCameraButtons);
		}
	}

	private void reset(String reason)
	{
		for (Map.Entry<WidgetRegion, RegionState> entry : states.entrySet())
		{
			if (entry.getValue().interfaceId >= 0) { diagnoseInterface("reset " + reason, entry.getKey(), entry.getValue()); }
		}
		movementOverheads.clear();
		underlay.restore(bufferImage());
		overheadScaler.reset(bufferImage());
		if (!enabled || client.getGameState() != GameState.LOGGED_IN)
		{
			mouseListener.resetGestures();
			cameraInputOffset = null;
		}
		menus.reset();
		inputGate.restore();
		inputGate.invalidateTraversal();
		gateApplied = false;
		inputFrames = Collections.emptyList();
		interfaceLayout.restore();
		pendingPress.set(null);
		dragTarget = null;
		dropTarget = null;
		for (Map.Entry<WidgetRegion, RegionState> entry : states.entrySet())
		{
			RegionState state = entry.getValue();
			state.interfaceCapture.restore(bufferImage());
			updateInterfaceOverlay(entry.getKey(), state, -1);
			state.widget = null;
			state.presentedInterfaceHost = null;
			state.preparedInterfaceHost = null;
			state.preparedInterfaceId = -1;
			state.presentedSource = null;
			state.presentedDestination = null;
			state.source = null;
			state.transform = null;
			state.input = null;
			state.rendered = false;
			state.foreground = null;
			state.compositor.invalidate();
			state.pendingMaskChange = false;
			state.deferred = false;
			state.suppressOpeningFrame = false;
		}
	}

	/** Client thread only: restore old widget flags before discarding the old root's state. */
	private boolean synchronizeLayout()
	{
		// The top-level world view is not initialized during early client startup.
		ResizableLayout current = enabled && client.getGameState() == GameState.LOGGED_IN
			? ResizableLayout.current(client) : ResizableLayout.UNSUPPORTED;
		if (current != layout)
		{
			reset("root-layout " + layout + " -> " + current);
			underlay.clear();
			for (RegionState state : states.values())
			{
				state.compositor.clear();
				state.scaler.clear();
				state.blitter.clear();
			}
			layout = current;
		}
		return current != ResizableLayout.UNSUPPORTED;
	}

	private boolean canScale()
	{
		return enabled && client.getGameState() == GameState.LOGGED_IN
			&& ResizableLayout.current(client) != ResizableLayout.UNSUPPORTED;
	}

	private BufferedImage bufferImage()
	{
		if (!(client.getBufferProvider() instanceof MainBufferProvider))
		{
			return null;
		}
		Image image = ((MainBufferProvider) client.getBufferProvider()).getImage();
		return image instanceof BufferedImage ? (BufferedImage) image : null;
	}

	void captureBackground()
	{
		long started = renderTimings.start();
		try { captureBackgroundMeasured(); }
		finally { renderTimings.finish(RenderTimings.Stage.CAPTURE, started); }
	}

	private void captureBackgroundMeasured()
	{
		BufferedImage image = bufferImage();
		if (!synchronizeLayout() || !canScale() || image == null)
		{
			reset("capture-unavailable");
			return;
		}
		List<Rectangle> sources = new ArrayList<>();
		for (Map.Entry<WidgetRegion, RegionState> entry : states.entrySet())
		{
			WidgetRegion region = entry.getKey();
			RegionState state = entry.getValue();
			state.transform = null;
			state.source = null;
			if (!scalingRequested(region))
			{
				updateInterfaceOverlay(region, state, -1);
				state.widget = null;
				state.input = null;
				continue;
			}
			state.widget = region.visibleWidget(client, layout);
			// Layout writes belong exclusively to BeforeRender. An equipment script
			// can rewrite host sizing after preparation; revalidating here would
			// change logical geometry underneath already cached native draw positions.
			updateInterfaceOverlay(region, state, region.interfaceId(state.widget));
			if (state.widget == null)
			{
				state.input = null;
				continue;
			}
			// Central interfaces get their backdrop at the host's native layer hook,
			// immediately before the attached group draws. Their final bounds need
			// not be guessed here while opening scripts are still settling.
			if (region.interfaceWindow()) { continue; }
			// Relative positions and sizes are current after native revalidation;
			// getBounds() screen positions are not updated until the widget draws.
			Rectangle source = region.captureBounds(state.widget);
			if (source.isEmpty() || !new Rectangle(image.getWidth(), image.getHeight()).contains(source))
			{
				state.input = null;
				continue;
			}
			state.transform = transform(region, source, image.getWidth(), image.getHeight());
			if (state.transform.isIdentity() && !(region == WidgetRegion.MINIMAP && hdUiIcons.minimapCompositor()))
			{
				state.transform = null;
				state.input = null;
				continue;
			}
			sources.add(source);
			state.source = source;
		}
		// GPU scene overlays share the UI buffer. Save and remove them before any
		// widget drawing instead of trying to unblend an opaque overhead afterward.
		if (client.isGpu()) { underlay.captureAndClear(image, sources); }
		for (RegionState state : states.values())
		{
			if (state.transform != null)
			{
				state.compositor.capture(image, state.transform.source(), client.isGpu());
			}
		}
		publishFrames();
	}

	private void updateInterfaceOverlay(WidgetRegion region, RegionState state, int interfaceId)
	{
		if (!region.interfaceWindow() || state.interfaceId == interfaceId) { return; }
		diagnoseInterface("group-change " + state.interfaceId + " -> " + interfaceId, region, state);
		if (state.interfaceOverlay != null)
		{
			overlayManager.remove(state.interfaceOverlay);
			overlays.remove(state.interfaceOverlay);
			state.interfaceOverlay = null;
		}
		state.interfaceId = interfaceId;
		state.presentedInterfaceHost = null;
		state.presentedSource = null;
		state.presentedDestination = null;
		state.openingFrames = interfaceId >= 0 ? 2 : 0;
		state.openingWaitLogged = false;
		state.deferred = false;
		state.suppressOpeningFrame = false;
		state.input = null;
		state.foreground = null;
		state.compositor.invalidate();
		if (interfaceId >= 0)
		{
			state.interfaceOverlay = new InterfaceScaleOverlay(this, region, interfaceId);
			overlays.add(state.interfaceOverlay);
			overlayManager.add(state.interfaceOverlay);
		}
	}

	/** Bounded live evidence for transient interface rebuilds; never log item contents. */
	private void diagnoseInterface(String reason, WidgetRegion region, RegionState state)
	{
		if (interfaceDiagnosticsRemaining <= 0) { return; }
		interfaceDiagnosticsRemaining--;
		Widget host = state.widget;
		log.info("Interface lifecycle: reason={} region={} group={} tick={} containerTick={} hostIdentity={} presentedIdentity={} rendered={} source={} host={} layoutHost={} compatibility={}",
			reason, region, state.interfaceId, client.getTickCount(), lastContainerTick,
			System.identityHashCode(host), System.identityHashCode(state.presentedInterfaceHost), state.rendered,
			state.source, host == null ? null : host.getBounds(), host == null ? null : new WidgetGeometry().bounds(host),
			config.preservePanelActions());
	}

	void overheads(Graphics2D graphics, boolean before)
	{
		long started = renderTimings.start();
		try { overheadsMeasured(graphics, before); }
		finally { renderTimings.finish(RenderTimings.Stage.OVERHEADS, started); }
	}

	private void overheadsMeasured(Graphics2D graphics, boolean before)
	{
		BufferedImage image = bufferImage();
		if (!canScale() || !client.isGpu() || image == null || !OverheadElements.requested(config) && !hdPrayers.active())
		{
			overheadScaler.reset(image);
			return;
		}
		if (before)
		{
			prayerOnly = !OverheadElements.requested(config);
			Rectangle viewport = new Rectangle(client.getViewportXOffset(), client.getViewportYOffset(),
				client.getViewportWidth(), client.getViewportHeight());
			if (prayerOnly)
			{
				viewport = overheadContext.collectPrayers(client, spriteManager, viewport, hdPrayers::image);
				if (viewport.isEmpty()) { overheadScaler.reset(image); return; }
			}
			overheadScaler.begin(image, viewport);
		}
		else if (prayerOnly) { overheadScaler.finishPrayers(image, graphics, overheadContext); }
		else
		{
			overheadScaler.finish(image, graphics, config, overheadContext, () ->
				overheadContext.collect(client, spriteManager, graphics, new Rectangle(client.getViewportXOffset(),
					client.getViewportYOffset(), client.getViewportWidth(), client.getViewportHeight()), hdPrayers::image));
		}
	}

	private MinimapTransform transform(WidgetRegion region, Rectangle source, int width, int height)
	{
		switch (region)
		{
			case CHAT:
				return MinimapTransform.anchored(source, config.chatScale(), width, height, true);
			case INTERFACE:
			case INTERFACE_CRM:
			case INTERFACE_FLOAT:
				return interfaceLayout.transform(region, source, config.interfaceScale(), width, height);
			case SIDE_PANEL:
				return MinimapTransform.fit(source, config.panelScale(), config.panelLeft(), config.panelUp(), width, height, true);
			case UPPER_TABS:
				return MinimapTransform.fit(source, config.upperTabScale(), config.upperTabLeft(), config.upperTabUp(), width, height, true);
			case LOWER_TABS:
				return MinimapTransform.fit(source, config.lowerTabScale(), config.lowerTabLeft(), config.lowerTabUp(), width, height, true);
			default:
				return MinimapTransform.fit(source, config.scale(), config.offsetX(), config.offsetY(), width, height);
		}
	}

	private boolean scalingRequested(WidgetRegion region)
	{
		switch (region)
		{
			case CHAT: return config.chatScale() > 0 && config.chatScale() != 100;
			case INTERFACE:
			case INTERFACE_CRM:
			case INTERFACE_FLOAT: return config.interfaceScale() > 0 && config.interfaceScale() != 100;
			case SIDE_PANEL: return config.panelScale() != 100 || config.panelLeft() != 0 || config.panelUp() != 0;
			case UPPER_TABS: return config.upperTabScale() != 100 || config.upperTabLeft() != 0 || config.upperTabUp() != 0;
			case LOWER_TABS: return config.lowerTabScale() != 100 || config.lowerTabLeft() != 0 || config.lowerTabUp() != 0;
			case MINIMAP: return config.scale() != 100 || config.offsetX() != 0 || config.offsetY() != 0
				|| hdUiIcons != null && hdUiIcons.minimapCompositor();
			default: return config.scale() != 100 || config.offsetX() != 0 || config.offsetY() != 0;
		}
	}

	void drawScaled(WidgetRegion region, int component, Graphics2D graphics)
	{
		long started = renderTimings.start();
		try { drawScaledMeasured(region, component, graphics); }
		finally { renderTimings.finish(RenderTimings.Stage.WIDGETS, started); }
	}

	private void drawScaledMeasured(WidgetRegion region, int component, Graphics2D graphics)
	{
		if (region.interfaceWindow())
		{
			captureInterfaceBackground(region, component);
			return;
		}
		drawRegion(region, component, graphics);
	}

	private void captureInterfaceBackground(WidgetRegion region, int component)
	{
		BufferedImage image = bufferImage();
		if (!canScale() || image == null || region.component(layout) != component || !scalingRequested(region)) { return; }
		RegionState state = states.get(region);
		Widget host = region.visibleWidget(client, layout);
		if (state == null || host == null) { return; }
		state.widget = host;
		updateInterfaceOverlay(region, state, region.interfaceId(host));
		Rectangle captureHost = new Rectangle(host.getBounds());
		if (bankInterface(state.interfaceId))
		{
			// Equipment scripts can invalidate render positions even on an established
			// bank. Capture BOTH host origins before drawing, not just while opening,
			// otherwise the current logical bank is cropped to the stale host bottom.
			captureHost.add(new WidgetGeometry().bounds(host));
		}
		state.interfaceCapture.begin(image, captureHost, client.isGpu());
	}

	private static boolean bankInterface(int interfaceId)
	{
		return interfaceId == InterfaceID.BANKMAIN || interfaceId == InterfaceID.SHARED_BANK;
	}

	private void diagnoseBankGeometry(Widget host, boolean mismatched)
	{
		int kind = mismatched ? 2 : 1;
		if ((bankGeometryDetailKinds & kind) != 0) { return; }
		bankGeometryDetailKinds |= kind;
		List<Widget> sample = new ArrayList<>();
		sample.add(host);
		Widget[] roots = host.getNestedChildren();
		if (roots != null)
		{
			for (Widget root : roots)
			{
				if (root == null) { continue; }
				sample.add(root);
				Widget[] children = root.getStaticChildren();
				if (children != null)
				{
					for (Widget child : children)
					{
						if (child != null && sample.size() < 10) { sample.add(child); }
					}
				}
				if (sample.size() >= 10) { break; }
			}
		}
		WidgetGeometry geometry = new WidgetGeometry();
		for (Widget widget : sample)
		{
			log.info("Bank geometry sample: mismatch={} id={} rendered={} projected={} original=({},{},{},{}) relative=({},{}) modes=({},{},{},{})",
				mismatched, widget.getId(), widget.getBounds(), geometry.bounds(widget),
				widget.getOriginalX(), widget.getOriginalY(), widget.getOriginalWidth(), widget.getOriginalHeight(),
				widget.getRelativeX(), widget.getRelativeY(), widget.getXPositionMode(), widget.getYPositionMode(),
				widget.getWidthMode(), widget.getHeightMode());
		}
	}

	void drawInterfaceScaled(WidgetRegion region, int interfaceId, Graphics2D graphics)
	{
		RegionState state = states.get(region);
		if (state == null || !region.interfaceWindow() || state.interfaceId != interfaceId) { return; }
		BufferedImage image = bufferImage();
		if (!canScale() || image == null) { return; }
		Widget host = region.visibleWidget(client, layout);
		if (host != state.widget || region.interfaceId(host) != interfaceId)
		{
			diagnoseInterface("completed-host-changed", region, state);
			state.interfaceCapture.restore(image);
			state.input = null;
			return;
		}
		Rectangle layoutHost = new WidgetGeometry().bounds(host);
		Rectangle renderedHost = host.getBounds();
		boolean correctedBankOrigin = bankInterface(interfaceId) && state.presentedInterfaceHost == host
			&& renderedHost.width == layoutHost.width && renderedHost.height == layoutHost.height
			&& !renderedHost.getLocation().equals(layoutHost.getLocation());
		// Descendant origins can be stale independently of the host (including
		// HD-suppressed chrome). Always use one logical tree for bank capture and
		// output anchoring; checking only the host misses partial native redraws.
		// A genuinely pending size change still belongs to the next native draw.
		boolean logicalBank = bankInterface(interfaceId)
			&& renderedHost.width == layoutHost.width && renderedHost.height == layoutHost.height;
		Rectangle actual = state.interfaceCapture.visibleArea(image,
			logicalBank ? region.captureBounds(host) : region.bounds(host));
		// Input frames are transient: equipment/container refreshes, layout
		// revalidation or a skipped capture can invalidate one without closing
		// the bank. Only an actually unpresented host needs the opening guard.
		boolean openingBank = bankInterface(interfaceId) && state.presentedInterfaceHost != host;
		boolean prepared = state.preparedInterfaceHost == host && state.preparedInterfaceId == interfaceId;
		if (openingBank && (!prepared || actual.isEmpty() || !host.getBounds().equals(layoutHost)))
		{
			// Present the pre-bank scene for this unfinished frame, including after
			// any late icon drawing. No pixel-based bounds inference or stale image.
			state.suppressOpeningFrame = true;
			state.foreground = null;
			state.input = null;
			if (!state.openingWaitLogged)
			{
				log.info("Bank opening frame withheld until geometry is ready: group={} bounds={} host={} layoutHost={}",
					interfaceId, actual, host.getBounds(), layoutHost);
				state.openingWaitLogged = true;
			}
			return;
		}
		if (actual.isEmpty())
		{
			diagnoseInterface("completed-capture-empty", region, state);
			if (state.openingFrames > 0)
			{
				log.info("Interface scaling has no completed host capture: region={} group={} bounds={}", region, interfaceId, region.bounds(host));
				state.openingFrames--;
			}
			state.interfaceCapture.restore(image);
			state.input = null;
			return;
		}
		state.source = actual;
		if (bankInterface(interfaceId)) { diagnoseBankGeometry(host, correctedBankOrigin); }
		if (correctedBankOrigin) { diagnoseInterface("corrected-bank-render-origin", region, state); }
		state.suppressOpeningFrame = false;
		state.transform = logicalBank
			? interfaceLayout.transform(region, actual, config.interfaceScale(), image.getWidth(), image.getHeight(), layoutHost)
			: transform(region, actual, image.getWidth(), image.getHeight());
		if (state.transform.isIdentity())
		{
			state.interfaceCapture.restore(image);
			state.input = null;
			return;
		}
		state.deferred = hqIcons.active();
		if (state.deferred) { state.interfaceCapture.prepareLatePixels(image, actual); }
		else { state.foreground = state.interfaceCapture.extract(image, actual, state.compositor); }
		if (state.openingFrames > 0)
		{
			log.info("Interface opening scale: region={} group={} source={} output={} host={} layoutHost={} deferred={}",
				region, interfaceId, actual, state.transform.destination(), host.getBounds(),
				new WidgetGeometry().bounds(host), state.deferred);
			state.openingFrames--;
		}
	}

	private void drawRegion(WidgetRegion region, int component, Graphics2D graphics)
	{
		RegionState state = states.get(region);
		BufferedImage image = bufferImage();
		if (!synchronizeLayout() || state == null || !canScale() || image == null || region.component(layout) != component)
		{
			return;
		}
		Widget widget = state.widget == null ? null : region.visibleWidget(client, layout);
		if (widget != null && widget == state.widget
			&& state.compositor.matches(image, region.interfaceWindow() ? state.source : region.bounds(widget)))
		{
			// The side-host layer hook can precede newly attached inventory/equipment
			// contents and late item overlays. Capture the completed panel at drawOutputs,
			// not just when HQ happens to be active. Otherwise a new item misses a frame.
			state.deferred = region == WidgetRegion.SIDE_PANEL || region == WidgetRegion.MINIMAP && hdUiIcons.minimapCompositor();
			if (!state.deferred)
			{
				state.foreground = state.compositor.extractAndRestore(region == WidgetRegion.MINIMAP ? mapInterior() : null);
			}
		}
		else
		{
			state.input = null;
		}
		// Refresh every capture whose hook has not fired yet. Classic and modern
		// have different nesting/draw orders, so enum order cannot predict the next layer.
		for (Map.Entry<WidgetRegion, RegionState> entry : states.entrySet())
		{
			RegionState later = entry.getValue();
			Rectangle bounds = later.widget == null ? null
				: entry.getKey().interfaceWindow() ? later.source : entry.getKey().bounds(later.widget);
			if (entry.getKey() != region && !later.deferred && later.widget != null
				&& bounds != null && later.compositor.matches(image, bounds))
			{
				later.compositor.capture(image, bounds, client.isGpu());
			}
		}
	}

	void drawOutputs(Graphics2D graphics)
	{
		long started = renderTimings.start();
		try { drawOutputsMeasured(graphics); }
		finally { renderTimings.finish(RenderTimings.Stage.OUTPUTS, started); }
	}

	private void drawOutputsMeasured(Graphics2D graphics)
	{
		// Runs after HQ's ABOVE_WIDGETS dragged-item painter, before our scaling and menus.
		// The original ALWAYS_ON_TOP pass is suppressed to prevent a second native-size draw.
		hqIcons.paint(graphics);
		if (!synchronizeLayout() || !canScale())
		{
			return;
		}
		int sideTab = client.getVarcIntValue(VarClientInt.INVENTORY_TAB);
		if (sideTab != lastSideTab)
		{
			lastSideTab = sideTab;
			RegionState side = states.get(WidgetRegion.SIDE_PANEL);
			if (side != null) { side.scaler.invalidate(); }
		}
		for (Map.Entry<WidgetRegion, RegionState> entry : states.entrySet())
		{
			WidgetRegion region = entry.getKey();
			RegionState state = entry.getValue();
			if (state.suppressOpeningFrame)
			{
				state.interfaceCapture.discard(bufferImage());
				state.foreground = null;
				state.input = null;
				state.deferred = false;
				continue;
			}
			if (!state.deferred) { continue; }
			Widget widget = region.visibleWidget(client, layout);
			if (region.interfaceWindow())
			{
				if (widget == state.widget && widget != null && region.interfaceId(widget) == state.interfaceId)
				{
					state.foreground = state.interfaceCapture.extract(bufferImage(), state.source, state.compositor);
				}
				else { state.interfaceCapture.restore(bufferImage()); state.input = null; }
			}
			else if (widget != null && widget == state.widget && state.source != null
				&& state.source.equals(region.bounds(widget)) && state.compositor.matches(bufferImage(), state.source))
			{
				long started = region == WidgetRegion.SIDE_PANEL ? renderTimings.start() : 0;
				state.foreground = state.compositor.extractAndRestore(region == WidgetRegion.MINIMAP ? mapInterior() : null);
				if (region == WidgetRegion.SIDE_PANEL) { renderTimings.finish(RenderTimings.Stage.PANEL_CAPTURE, started); }
			}
			else { state.input = null; }
			state.deferred = false;
		}
		underlay.restore(bufferImage());
		for (RegionState state : states.values()) { state.interfaceCapture.restore(bufferImage()); }
		Graphics2D g = (Graphics2D) graphics.create();
		try
		{
			g.setComposite(AlphaComposite.SrcOver);
			ScalingFilter filter = config.filter();
			if (filter == null)
			{
				filter = config.smooth() ? ScalingFilter.BILINEAR : ScalingFilter.NEAREST;
			}
			for (Map.Entry<WidgetRegion, RegionState> entry : states.entrySet())
			{
				RegionState state = entry.getValue();
				if (state.foreground == null && !hdUiIcons.active())
				{
					if (state.presentedInterfaceHost != null) { diagnoseInterface("presented-output-missing", entry.getKey(), state); }
					state.input = null;
					continue;
				}
				int visibleGroup = !hdUiIcons.active() || !entry.getKey().interfaceWindow() ? -1
					: state.interfaceId >= 0 ? state.interfaceId
					: entry.getKey().interfaceId(entry.getKey().visibleWidget(client, layout));
				Set<Integer> hdGroups = !hdUiIcons.active() ? Set.of()
					: HdUiCompatibility.groupsForRegion(entry.getKey(), visibleGroup);
				boolean minimap = entry.getKey() == WidgetRegion.MINIMAP;
				if (minimap && !hdGroups.isEmpty()) { hdGroups = Set.of(HdUiCompatibility.MINIMAP); }
				if (state.foreground == null)
				{
					if (!hdGroups.isEmpty() && !scalingRequested(entry.getKey()) && !state.suppressOpeningFrame)
					{
						Widget nativeHost = entry.getKey().visibleWidget(client, layout);
						if (nativeHost != null)
						{
							Rectangle nativeBounds = entry.getKey().bounds(nativeHost);
							hdUiIcons.paint(g, nativeBounds, nativeBounds, bufferImage(), client.isGpu(), hdGroups);
							hdUiIcons.refreshed(hdGroups);
						}
					}
					state.input = null;
					continue;
				}
				Rectangle destination = state.transform.destination();
				if (entry.getKey().interfaceWindow() && state.presentedInterfaceHost == state.widget
					&& state.presentedSource != null && (!state.presentedSource.equals(state.source)
					|| !destination.equals(state.presentedDestination)))
				{
					diagnoseInterface("geometry-change previousSource=" + state.presentedSource
						+ " previousOutput=" + state.presentedDestination + " output=" + destination, entry.getKey(), state);
				}
				state.pendingMaskChange |= state.compositor.maskChanged();
				boolean newInput = state.input == null;
				if (minimap && hdUiIcons.minimapCompositor())
				{
					ScalingFilter terrainFilter = config.minimapFilter() == null ? ScalingFilter.BILINEAR : config.minimapFilter();
					long started=renderTimings.start();
					state.minimapLayers.render(client,layout,state.foreground,state.source,destination,bufferImage(),g,client.isGpu(),
						terrainFilter,config.sharpening(),config.scalingFps(),hdUiIcons);
					renderTimings.finish(RenderTimings.Stage.HD_MINIMAP,started);
					renderTimings.minimap(state.minimapLayers.terrainUpdated(),state.minimapLayers.uiUpdated());
					state.input = state.transform.isIdentity() ? null : new MinimapInputFrame(entry.getKey(),state.transform,state.foreground,
						state.input,state.pendingMaskChange,hdUiIcons.minimapInputMask());
					state.pendingMaskChange=false;state.rendered=true;hdUiIcons.refreshed(hdGroups);continue;
				}
				// Camera rotation invalidates most map rows every frame. Keep its quality
				// independent of expensive filters chosen for mostly-static inventory UI.
				ScalingFilter regionFilter = entry.getKey() == WidgetRegion.MINIMAP ? config.minimapFilter() : filter;
				if (regionFilter == null) { regionFilter = ScalingFilter.BILINEAR; }
				boolean hdTransition = !hdGroups.isEmpty() && hdUiIcons.refreshPending(hdGroups);
				boolean panel = entry.getKey() == WidgetRegion.SIDE_PANEL;
				long filterStarted = panel ? renderTimings.start() : 0;
				BufferedImage filtered = state.scaler.scale(state.foreground, destination.width, destination.height,
					regionFilter, config.sharpening(), state.compositor.changed() || newInput || hdTransition,
					newInput || hdTransition ? 0 : config.scalingFps());
				if (panel)
				{
					renderTimings.finish(RenderTimings.Stage.PANEL_FILTER, filterStarted);
					renderTimings.panel(state.compositor.changed(), state.scaler.updated());
				}
				if (hdTransition && state.scaler.updated()) { hdUiIcons.refreshed(hdGroups); }
				if (minimap && !hdGroups.isEmpty()) { hdUiIcons.paint(g, state.source, destination, bufferImage(), client.isGpu(), hdGroups); }
				long blitStarted = panel ? renderTimings.start() : 0;
				state.blitter.draw(bufferImage(), filtered, destination.x, destination.y, g, client.isGpu(), state.scaler.updated());
				if (panel) { renderTimings.finish(RenderTimings.Stage.PANEL_BLIT, blitStarted); }
				if (!hdGroups.isEmpty())
				{
					hdUiIcons.paint(g, state.source, destination, bufferImage(), client.isGpu(),
						minimap ? Set.of(HdUiCompatibility.COMPASS) : hdGroups);
				}
				state.input = new MinimapInputFrame(entry.getKey(), state.transform, state.foreground,
					state.input, state.scaler.updated() && state.pendingMaskChange, minimap ? hdUiIcons.minimapInputMask() : null);
				if (state.scaler.updated()) { state.pendingMaskChange = false; }
				state.rendered = true;
				if (entry.getKey().interfaceWindow())
				{
					state.presentedInterfaceHost = state.widget;
					state.presentedSource = new Rectangle(state.source);
					state.presentedDestination = destination;
				}
			}
		}
		finally
		{
			g.dispose();
		}
		publishFrames();
	}

	void finishRender()
	{
		// Also restore if a widget/output hook was skipped this frame.
		for (RegionState state : states.values())
		{
			if (state.suppressOpeningFrame) { state.interfaceCapture.discard(bufferImage()); }
		}
		underlay.restore(bufferImage());
		for (RegionState state : states.values()) { state.interfaceCapture.restore(bufferImage()); }
		overheadScaler.reset(bufferImage());
		rendering = false;
		applyInputGate();
	}

	private Shape mapInterior()
	{
		if (hdUiIcons.minimapCompositor())
		{
			RegionState state=states.get(WidgetRegion.MINIMAP);
			if (state!=null) { return state.minimapLayers.coverage(client,layout); }
		}
		int id = layout == ResizableLayout.CLASSIC
			? InterfaceID.ToplevelOsrsStretch.MINIMAP : InterfaceID.ToplevelPreEoc.MINIMAP;
		Widget map = client.getWidget(id);
		if (map == null || map.isHidden())
		{
			return null;
		}
		Rectangle bounds = map.getBounds();
		return new Ellipse2D.Double(bounds.x + 2, bounds.y + 2, Math.max(0, bounds.width - 4), Math.max(0, bounds.height - 4));
	}

	private void publishFrames()
	{
		List<MinimapInputFrame> frames = new ArrayList<>();
		for (RegionState state : states.values())
		{
			if (state.input != null)
			{
				frames.add(state.input);
			}
		}
		inputFrames = Collections.unmodifiableList(frames);
	}

	List<MinimapInputFrame> getInputFrames()
	{
		return enabled && client.getGameState() == GameState.LOGGED_IN
			&& layout != ResizableLayout.UNSUPPORTED && layout == ResizableLayout.current(client)
			? inputFrames : Collections.emptyList();
	}

	void recordMenuPress(Point cursor, Point nativePoint, WidgetRegion target)
	{
		if (enabled && layout == ResizableLayout.current(client)) { menus.recordPress(cursor, nativePoint, target); }
	}

	Point translateMenuPoint(Point point)
	{
		return enabled && layout == ResizableLayout.current(client) ? menus.translate(point) : point;
	}

	private void updateCameraButtons()
	{
		if (!enabled || client.getGameState() != GameState.LOGGED_IN) { cameraButtons = 0; return; }
		boolean rightAlways = cameraPluginActive && cameraConfig != null && cameraConfig.rightClickMovesCamera()
			&& !cameraConfig.rightClickMenuBlocksCamera();
		int buttons = client.getVarbitValue(VarbitID.MOUSECAM_DISABLED) == 0 || rightAlways ? 1 << MouseEvent.BUTTON2 : 0;
		if (rightAlways) { buttons |= 1 << MouseEvent.BUTTON3; }
		cameraButtons = buttons;
	}

	boolean isCameraPress(MouseEvent event)
	{
		// Match native Alt/middle and Meta/right button interpretation after remappers.
		int button = event.isAltDown() || event.getButton() == MouseEvent.BUTTON2 ? MouseEvent.BUTTON2
			: event.isMetaDown() || event.getButton() == MouseEvent.BUTTON3 ? MouseEvent.BUTTON3 : event.getButton();
		return button > 0 && button < 32 && (cameraButtons & (1 << button)) != 0;
	}

	void recordCameraInputOffset(Point offset)
	{
		if (java.util.Objects.equals(cameraInputOffset, offset)) { return; }
		cameraInputOffset = offset == null ? null : new Point(offset);
		if (enabled) { menus.setCameraInputOffset(offset); }
	}

	@Subscribe
	public void onFocusChanged(FocusChanged event)
	{
		if (!event.isFocused())
		{
			mouseListener.resetGestures();
			recordCameraInputOffset(null);
			dragTarget = null;
			dropTarget = null;
		}
	}

	@Subscribe
	public void onPluginChanged(PluginChanged event)
	{
		if (event.getPlugin() instanceof net.runelite.client.plugins.stretchedmode.StretchedModePlugin)
		{
			stretchedModeActive = event.isLoaded();
			clientThread.invokeLater(() -> { if (enabled) { loginStretch.update(stretchedModeActive); } });
		}
		if (event.getPlugin() instanceof CameraPlugin)
		{
			cameraPluginActive = event.isLoaded();
			clientThread.invokeLater(this::updateCameraButtons);
		}
		if (enabled && event.getPlugin() != this && event.isLoaded())
		{
			// See final remapped buttons even when another input plugin is enabled later.
			mouseManager.unregisterMouseListener(mouseListener);
			mouseManager.registerMouseListener(mouseListener);
			mouseManager.unregisterMouseWheelListener(mouseListener);
			mouseManager.registerMouseWheelListener(mouseListener);
		}
	}

	void recordPointer(Point point, WidgetRegion target, int eventId, long when)
	{
		pointer = new Point(point);
		// Tooltip/menu/overhead-only scaling does not change widget hit testing.
		// High-polling-rate mice must not enqueue client-thread work for those settings.
		if (inputFrames.isEmpty()) { return; }
		if (eventId == MouseEvent.MOUSE_PRESSED)
		{
			pendingPress.set(new PendingPress(target, point, when));
		}
		Point offset = cameraInputOffset;
		Point nativePoint = offset == null ? point : new Point(point.x + offset.x, point.y + offset.y);
		int sources = 0;
		for (MinimapInputFrame frame : inputFrames)
		{
			if (frame.containsSource(nativePoint)) { sources |= 1 << frame.region().ordinal(); }
		}
		long key = sources;
		key = key * 31 + (target == null ? -1 : target.ordinal());
		key = key * 31 + (dragTarget == null ? -1 : dragTarget.ordinal());
		key = key * 31 + (dropTarget == null ? -1 : dropTarget.ordinal());
		key = key * 31 + (offset == null ? 0 : 1);
		key = key * 31 + (client.isMenuOpen() ? 1 : 0);
		if (eventId != MouseEvent.MOUSE_PRESSED && pointerGateKey == key) { return; }
		pointerGateKey = key;
		// Runs at the start of the next client cycle, before native interface hit testing.
		if (gateQueued.compareAndSet(false, true))
		{
			clientThread.invokeLater(() ->
			{
				gateQueued.set(false);
				applyInputGate();
			});
		}
	}

	void recordDragTarget(WidgetRegion target, boolean released)
	{
		dragTarget = target;
		if (target == null) { dropTarget = null; }
		dragReleased = released;
	}

	void recordDropTarget(WidgetRegion target) { dropTarget = target; }

	void applyInputGate()
	{
		long started = renderTimings.start();
		try { applyInputGateMeasured(); }
		finally { renderTimings.finish(RenderTimings.Stage.INPUT, started); }
	}

	private void applyInputGateMeasured()
	{
		if (rendering)
		{
			return;
		}
		if (!synchronizeLayout() || !canScale())
		{
			inputGate.restore();
			gateApplied = false;
			return;
		}
		Point currentPointer = pointer;
		WidgetRegion target = client.isMenuOpen() ? null : InputRoute.resolve(currentPointer, getInputFrames()).target;
		PendingPress press = pendingPress.get();
		boolean pending = press != null && System.nanoTime() - press.created < 500_000_000L;
		if (pending)
		{
			// Keep the click's target available even if the pointer moves before the input pass.
			target = press.target;
		}
		if (dragTarget != null && !client.isMenuOpen())
		{
			target = dragTarget;
		}
		Point cameraOffset = cameraInputOffset;
		if (cameraOffset != null)
		{
			// A right-camera press may also open an object/widget menu. Preserve that
			// click's operations for its input pass, then gate only UI at native coordinates.
			target = pending ? press.target : null;
		}
		// Ordinary scene motion is not translated. Native widgets cannot intercept it
		// outside their original rectangles, so do not traverse/mutate whole UI trees
		// merely because the pointer is no longer over the enlarged inventory.
		// Ordinary presses, item drags and menus keep the conservative gate. Camera
		// holds have a fixed translation, so check those actual native coordinates.
		boolean sceneOnly = target == null && !pending && dragTarget == null
			&& cameraOffset == null && !client.isMenuOpen();
		Point cameraPointer = cameraOffset == null ? null
			: new Point(currentPointer.x + cameraOffset.x, currentPointer.y + cameraOffset.y);
		Point cameraPress = cameraOffset == null || !pending ? null
			: new Point(press.point.x + cameraOffset.x, press.point.y + cameraOffset.y);
		int regions = 0;
		boolean preservePanelActions = config.preservePanelActions();
		for (Map.Entry<WidgetRegion, RegionState> entry : states.entrySet())
		{
			RegionState state = entry.getValue();
			if (state.suppressOpeningFrame && state.widget != null)
			{
				regions |= 1 << entry.getKey().ordinal();
				continue;
			}
			// Some integrations read/dispatch widget operations without moving the
			// physical cursor. Excluding their tree makes those operations disappear
			// solely because another panel is hovered. This opt-in mode retains the
			// whole native operation state, not just the displayed action strings.
			// Bypass ALL off-hover trees, not just rectangular regions: frame/tab
			// ancestors can contain the side inventory as a nested descendant.
			// The invisible opening-frame safety gate above remains independent.
			if (preservePanelActions) { continue; }
			if (state.input != null && state.input.isFresh() && entry.getKey() != target && entry.getKey() != dropTarget
				&& (cameraPointer != null
					? state.input.containsSource(cameraPointer) || (cameraPress != null && state.input.containsSource(cameraPress))
					: !sceneOnly || state.input.containsSource(currentPointer)))
			{
				regions |= 1 << entry.getKey().ordinal();
			}
		}
		if (gateApplied && regions == lastGatedRegions) { return; }
		inputGate.restore();
		lastGatedRegions = regions;
		gateApplied = true;
		for (Map.Entry<WidgetRegion, RegionState> entry : states.entrySet())
		{
			if ((regions & (1 << entry.getKey().ordinal())) != 0)
			{
				inputGate.exclude(entry.getValue().widget);
			}
		}
	}
}
