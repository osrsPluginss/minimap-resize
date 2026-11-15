package com.minimapresize;

import java.awt.Canvas;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MainBufferProvider;
import net.runelite.api.Menu;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.camera.CameraConfig;
import net.runelite.client.plugins.camera.CameraPlugin;
import net.runelite.client.ui.overlay.OverlayManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Exercise the render/input lifecycle together, including native input-property restoration. */
public class MinimapResizePluginTest
{
	@Rule public MockitoRule mocks = MockitoJUnit.rule();
	@Mock private Client client;
	@Mock private MinimapResizeConfig config;
	@Mock private OverlayManager overlayManager;
	@Mock private MouseManager mouseManager;
	@Mock private ClientThread clientThread;
	@Mock private ConfigManager configManager;
	@Mock private PluginManager pluginManager;
	@Mock private CameraConfig cameraConfig;
	@Spy private EventBus eventBus = new EventBus(error -> { throw new AssertionError("Event subscriber failed", error); });
	@InjectMocks private MinimapResizePlugin plugin;

	private BufferedImage image;
	private Widget map;
	private Widget upper;
	private Widget lower;
	private Widget panel;
	private Widget classicMap;
	private Widget classicPanel;
	private final Map<WidgetRegion, Widget> widgets = new EnumMap<>(WidgetRegion.class);
	private final Map<WidgetRegion, Widget> classicWidgets = new EnumMap<>(WidgetRegion.class);
	private final java.util.List<net.runelite.client.ui.overlay.Overlay> registeredOverlays = new java.util.ArrayList<>();

	@Before
	public void setup()
	{
		when(overlayManager.add(any())).thenAnswer(call -> registeredOverlays.add(call.getArgument(0)));
		when(overlayManager.remove(any())).thenAnswer(call -> registeredOverlays.remove((Object) call.getArgument(0)));
		when(overlayManager.anyMatch(any())).thenAnswer(call -> registeredOverlays.stream().anyMatch(call.getArgument(0)));
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.isResized()).thenReturn(true);
		when(client.getTopLevelInterfaceId()).thenReturn(InterfaceID.TOPLEVEL_PRE_EOC);
		when(config.scale()).thenReturn(50);
		when(config.upperTabScale()).thenReturn(50);
		when(config.lowerTabScale()).thenReturn(50);
		when(config.panelScale()).thenReturn(100);
		when(configManager.getConfig(CameraConfig.class)).thenReturn(cameraConfig);
		when(configManager.getConfig(net.runelite.client.plugins.stretchedmode.StretchedModeConfig.class))
			.thenReturn(mock(net.runelite.client.plugins.stretchedmode.StretchedModeConfig.class));
		doAnswer(invocation -> { ((Runnable) invocation.getArgument(0)).run(); return null; })
			.when(clientThread).invokeLater(any(Runnable.class));
		image = new BufferedImage(1000, 700, BufferedImage.TYPE_INT_ARGB);
		MainBufferProvider buffer = mock(MainBufferProvider.class);
		when(buffer.getImage()).thenReturn(image);
		when(client.getBufferProvider()).thenReturn(buffer);
		map = widget(InterfaceID.ToplevelPreEoc.MAP_CONTAINER, new Rectangle(750, 0, 250, 180));
		upper = widget(InterfaceID.ToplevelPreEoc.SIDE_MOVABLE_LAYER, new Rectangle(760, 400, 240, 40));
		lower = widget(InterfaceID.ToplevelPreEoc.SIDE_STATIC_LAYER, new Rectangle(760, 660, 240, 40));
		panel = widget(InterfaceID.ToplevelPreEoc.SIDE_CONTAINER, new Rectangle(790, 440, 200, 220));
		// Deliberately keep both roots cached and "visible" to reproduce the reported bug.
		classicMap = widget(InterfaceID.ToplevelOsrsStretch.MAP_CONTAINER, new Rectangle(750, 0, 250, 180));
		classicPanel = widget(InterfaceID.ToplevelOsrsStretch.SIDE_MENU, new Rectangle(760, 400, 240, 300));
		classicWidgets.put(WidgetRegion.MINIMAP, classicMap);
		classicWidgets.put(WidgetRegion.SIDE_PANEL, classicPanel);
		widgets.put(WidgetRegion.MINIMAP, map);
		widgets.put(WidgetRegion.LOWER_TABS, lower);
		widgets.put(WidgetRegion.UPPER_TABS, upper);
		widgets.put(WidgetRegion.SIDE_PANEL, panel);
		plugin.startUp();
	}

	private Widget widget(int id, Rectangle bounds)
	{
		Widget widget = mock(Widget.class);
		when(widget.getId()).thenReturn(id);
		when(widget.getBounds()).thenReturn(bounds);
		when(widget.getNoClickThrough()).thenReturn(true);
		when(client.getWidget(id)).thenReturn(widget);
		return widget;
	}

	@Test @SuppressWarnings("unchecked")
	public void themedMinimapCompositesLateNativeOrbTextAtNormalAndResizedOutput()
	{
		when(config.upperTabScale()).thenReturn(100);when(config.lowerTabScale()).thenReturn(100);when(config.panelScale()).thenReturn(100);
		com.hdui.ScaledInventoryOverlay painter=new com.hdui.ScaledInventoryOverlay();overlayManager.add(painter);
		java.util.concurrent.atomic.AtomicReference<java.util.Set<Integer>> drawing=new java.util.concurrent.atomic.AtomicReference<>(java.util.Set.of());
		eventBus.register(PluginMessage.class,event ->
		{
			if(event.getData().get("painter")!=painter) { return; }Map<String,Object> d=event.getData();
			if("request-minimap-capability".equals(event.getName())) { ((java.util.function.Consumer<Boolean>)d.get("receiver")).accept(true); }
			if("request-minimap-style".equals(event.getName())) { ((java.util.function.Consumer<Map<String,Object>>)d.get("receiver")).accept(Map.of("version",2,"enabled",true,"filter","BICUBIC","sharpening",35,"text","GAME")); }
			if("render-groups".equals(event.getName())) { drawing.set((java.util.Set<Integer>)d.get("groups")); }
			if("request-layer-state".equals(event.getName())) { ((java.util.function.Consumer<Long>)d.get("receiver")).accept(1L); }
			if("prepare-minimap-text".equals(event.getName()))
			{ ((java.util.function.Consumer<BufferedImage>)d.get("receiver")).accept(new WidgetImageScaler().scale((BufferedImage)d.get("image"),(Integer)d.get("width"),(Integer)d.get("height"),ScalingFilter.NEAREST,0)); }
		},0);
		for(boolean gpu:new boolean[]{false,true})for(int activeLayout:new int[]{InterfaceID.TOPLEVEL_PRE_EOC,InterfaceID.TOPLEVEL_OSRS_STRETCH})
		{
			when(client.isGpu()).thenReturn(gpu);when(client.getTopLevelInterfaceId()).thenReturn(activeLayout);
			Widget host=activeLayout==InterfaceID.TOPLEVEL_PRE_EOC?map:classicMap;Rectangle source=host.getBounds();
			int component=activeLayout==InterfaceID.TOPLEVEL_PRE_EOC?InterfaceID.ToplevelPreEoc.MINIMAP:InterfaceID.ToplevelOsrsStretch.MINIMAP;
			Widget viewport=widget(component,new Rectangle(source.x+60,10,152,152));when(viewport.getSpriteId()).thenReturn(1178);
			Widget root=mock(Widget.class),text=mock(Widget.class);when(client.getWidget(InterfaceID.Orbs.UNIVERSE)).thenReturn(root);when(root.getChildren()).thenReturn(new Widget[]{text});
			when(text.getId()).thenReturn(InterfaceID.Orbs.UNIVERSE+10);when(text.getType()).thenReturn(net.runelite.api.widgets.WidgetType.TEXT);when(text.getBounds()).thenReturn(new Rectangle(source.x+5,36,7,9));when(text.getText()).thenReturn("99");when(text.getTextColor()).thenReturn(0xffff00);
			painter.contents=g -> { if(drawing.get().contains(HdUiCompatibility.MINIMAP)) { g.setColor(Color.MAGENTA);g.fillRect(source.x,26,57,34);g.setColor(Color.RED);g.fillRect(source.x+28,30,26,26); } };
			for(int scale:new int[]{100,186})for(ScalingFilter filter:ScalingFilter.values())
			{
				when(config.scale()).thenReturn(scale);when(config.minimapFilter()).thenReturn(filter);
				Graphics2D g=image.createGraphics();g.setComposite(AlphaComposite.Clear);g.fillRect(0,0,image.getWidth(),image.getHeight());g.setComposite(AlphaComposite.SrcOver);
				plugin.onBeforeRender(null);plugin.captureBackground();g.setColor(Color.GREEN);g.fillOval(source.x+60,10,152,152);
				plugin.drawScaled(WidgetRegion.MINIMAP,host.getId(),g);
				// Attached Orbs groups can finish AFTER the MAP_CONTAINER layer hook.
				g.setColor(Color.YELLOW);g.fillRect(source.x+6,37,3,5);plugin.drawOutputs(g);plugin.finishRender();g.dispose();
				Rectangle destination=MinimapTransform.fit(source,scale,0,0,image.getWidth(),image.getHeight()).destination();
				int x=destination.x+(int)(7.5*destination.width/source.width),y=destination.y+(int)(39.5*destination.height/source.height);
				assertEquals("Late native values survive every map filter at "+scale,Color.YELLOW.getRGB(),image.getRGB(x,y));
				int bx=destination.x+(int)(15.5*destination.width/source.width),by=destination.y+(int)(35.5*destination.height/source.height);
				assertEquals("Resource-pack plate keeps its own colour",Color.MAGENTA.getRGB(),image.getRGB(bx,by));
				if(scale==100) { assertFalse("Identity rendering keeps native input routing",plugin.getInputFrames().stream().anyMatch(f -> f.region()==WidgetRegion.MINIMAP)); }
			}
		}
	}

	@Test @SuppressWarnings("unchecked")
	public void minimapOnlyHdScalingKeepsNativeNumbersAboveArtAndPreservesPaintedControlInput()
	{
		when(config.upperTabScale()).thenReturn(100); when(config.lowerTabScale()).thenReturn(100);
		when(config.panelScale()).thenReturn(100); when(config.minimapFilter()).thenReturn(ScalingFilter.NEAREST);
		com.hdui.ScaledInventoryOverlay painter = new com.hdui.ScaledInventoryOverlay(); overlayManager.add(painter);
		java.util.concurrent.atomic.AtomicReference<java.util.Set<Integer>> drawing = new java.util.concurrent.atomic.AtomicReference<>(java.util.Set.of());
		eventBus.register(PluginMessage.class, event ->
		{
			if (event.getData().get("painter") != painter) { return; }
			if ("request-minimap-capability".equals(event.getName())) { ((java.util.function.Consumer<Boolean>) event.getData().get("receiver")).accept(true); }
			if ("render-groups".equals(event.getName())) { drawing.set((java.util.Set<Integer>) event.getData().get("groups")); }
			if ("request-layer-state".equals(event.getName())) { ((java.util.function.Consumer<Long>) event.getData().get("receiver")).accept(1L); }
		}, 0);
		for (boolean gpu : new boolean[]{false, true})
		{
			when(client.isGpu()).thenReturn(gpu);
			for (int layout : new int[]{InterfaceID.TOPLEVEL_PRE_EOC, InterfaceID.TOPLEVEL_OSRS_STRETCH})
			{
				when(client.getTopLevelInterfaceId()).thenReturn(layout);
				Widget host = layout == InterfaceID.TOPLEVEL_PRE_EOC ? map : classicMap; Rectangle source = host.getBounds();
				painter.contents = graphics ->
				{
					if (drawing.get().contains(HdUiCompatibility.MINIMAP)) { graphics.setColor(Color.GREEN); graphics.fillRect(source.x + 10, 20, 26, 26); }
					if (drawing.get().contains(HdUiCompatibility.COMPASS)) { graphics.setColor(Color.BLUE); graphics.fillRect(source.x + 80, 12, 8, 8); }
				};
				for (int scale : new int[]{150, 186, 275})
				{
					when(config.scale()).thenReturn(scale);
					for (int frame = 0; frame < 3; frame++)
					{
						Graphics2D g = image.createGraphics();
						g.setComposite(AlphaComposite.Clear);g.fillRect(0, 0, image.getWidth(), image.getHeight());g.setComposite(AlphaComposite.SrcOver);
						plugin.onBeforeRender(null); plugin.captureBackground();
						g.setColor(Color.YELLOW);g.fillRect(source.x + 12, 22, 4, 4);
						g.setColor(Color.WHITE);g.fillRect(source.x + 80, 12, 8, 8);
						plugin.drawScaled(WidgetRegion.MINIMAP, host.getId(), g);plugin.drawOutputs(g);plugin.finishRender();g.dispose();
					}
					Rectangle destination = MinimapTransform.fit(source, scale, 0, 0, image.getWidth(), image.getHeight()).destination();
					int bgX = destination.x + (int) (24.0 * destination.width / source.width), bgY = destination.y + (int) (34.0 * destination.height / source.height);
					assertEquals("Painted orb background is present", Color.GREEN.getRGB(), image.getRGB(bgX, bgY));
					assertEquals("Live native numbers must stay in front of the HD background", Color.YELLOW.getRGB(), image.getRGB(destination.x + (int) (14.0 * destination.width / source.width), destination.y + (int) (24.0 * destination.height / source.height)));
					assertEquals("Rotating HD compass pass is above the native face", Color.BLUE.getRGB(), image.getRGB(destination.x + (int) (84.0 * destination.width / source.width), destination.y + (int) (16.0 * destination.height / source.height)));
					assertTrue("Controls remain clickable even when their native graphics were suppressed", plugin.getInputFrames().stream().anyMatch(f -> f.region() == WidgetRegion.MINIMAP && f.hit(new Point(bgX, bgY))));
				}
			}
		}
	}

	@Test
	public void hdOverheadProviderActivatesNativeReplacementEvenWhenEveryOverheadSizeIsNormal() throws Exception
	{
		when(client.isGpu()).thenReturn(true);
		when(client.getViewportXOffset()).thenReturn(20); when(client.getViewportYOffset()).thenReturn(30);
		when(client.getViewportWidth()).thenReturn(200); when(client.getViewportHeight()).thenReturn(180);
		when(config.prayerIconScale()).thenReturn(100);
		net.runelite.api.Player player = mock(net.runelite.api.Player.class);
		when(client.getPlayers()).thenReturn(java.util.Collections.singletonList(player));
		when(client.getNpcs()).thenReturn(java.util.Collections.emptyList());
		when(player.getSkullIcon()).thenReturn(-1); when(player.getHealthRatio()).thenReturn(-1);
		when(player.getOverheadIcon()).thenReturn(net.runelite.api.HeadIcon.SMITE);
		when(player.getCanvasImageLocation(any(BufferedImage.class), anyInt())).thenReturn(new net.runelite.api.Point(72, 110));
		net.runelite.client.game.SpriteManager sprites = mock(net.runelite.client.game.SpriteManager.class);
		java.lang.reflect.Field field = MinimapResizePlugin.class.getDeclaredField("spriteManager"); field.setAccessible(true); field.set(plugin, sprites);
		BufferedImage nativeIcon = new BufferedImage(25, 25, BufferedImage.TYPE_INT_ARGB_PRE);
		Graphics2D iconGraphics = nativeIcon.createGraphics(); iconGraphics.setColor(java.awt.Color.BLUE); iconGraphics.fillRect(0, 0, 25, 25); iconGraphics.dispose();
		when(sprites.getSprite(440, 4)).thenReturn(nativeIcon);
		BufferedImage hd = new BufferedImage(480, 480, BufferedImage.TYPE_INT_ARGB_PRE);
		Graphics2D hdGraphics = hd.createGraphics(); hdGraphics.setColor(java.awt.Color.RED); hdGraphics.fillRect(0, 0, 480, 480); hdGraphics.dispose();
		Object owner = new Object();
		java.util.function.BiFunction<Integer, Integer, BufferedImage> provider = (archive, file) -> hd;
		plugin.onPluginMessage(new PluginMessage("hd-ui", "overhead-provider", Map.of("owner", owner, "enabled", true, "provider", provider)));
		Graphics2D graphics = image.createGraphics();
		try
		{
			plugin.overheads(graphics, true); graphics.drawImage(nativeIcon, 60, 70, null); plugin.overheads(graphics, false);
			assertEquals(java.awt.Color.RED.getRGB(), image.getRGB(60, 70));
			plugin.onPluginMessage(new PluginMessage("hd-ui", "overhead-provider", Map.of("owner", owner, "enabled", false)));
			graphics.setComposite(java.awt.AlphaComposite.Clear); graphics.fillRect(0, 0, image.getWidth(), image.getHeight()); graphics.setComposite(java.awt.AlphaComposite.SrcOver);
			plugin.overheads(graphics, true); graphics.drawImage(nativeIcon, 60, 70, null); plugin.overheads(graphics, false);
			assertEquals(java.awt.Color.BLUE.getRGB(), image.getRGB(60, 70));
		}
		finally { graphics.dispose(); }
	}

	@Test
	public void highPollingCameraMotionQueuesAtMostOneGateTaskPerClientCycle()
	{
		when(config.scale()).thenReturn(100);
		when(config.panelScale()).thenReturn(186);
		render();
		clearInvocations(clientThread);
		for (int i = 0; i < 1000; i++) { plugin.recordPointer(new Point(400 + i % 20, 200), null, MouseEvent.MOUSE_DRAGGED, i); }
		verify(clientThread, times(1)).invokeLater(any(Runnable.class));
		plugin.onClientTick(null);
		plugin.recordPointer(new Point(420, 200), null, MouseEvent.MOUSE_DRAGGED, 1001);
		verify(clientThread, times(2)).invokeLater(any(Runnable.class));
	}

	private void render()
	{
		render(WidgetRegion.values());
	}

	@Test public void startupFrameAndInputCallbacksDoNotReadAnUninitializedTopLevelWorldView()
	{
		when(client.getGameState()).thenReturn(GameState.STARTING);
		when(client.getTopLevelInterfaceId()).thenThrow(new NullPointerException("World view not initialized"));
		clearInvocations(client);
		plugin.onBeforeRender(null); plugin.onClientTick(null); plugin.onPostClientTick(null);
		assertTrue(plugin.getInputFrames().isEmpty());
		verify(client, never()).getTopLevelInterfaceId();
	}

	@Test
	public void lateNativeInventoryAndEquipmentIconsAreCapturedOnTheirFirstFrameWithoutHdUi()
	{
		when(config.scale()).thenReturn(100); when(config.upperTabScale()).thenReturn(100);
		when(config.lowerTabScale()).thenReturn(100); when(config.panelScale()).thenReturn(186);
		when(config.filter()).thenReturn(ScalingFilter.NEAREST); when(config.scalingFps()).thenReturn(1);
		for (int root : new int[]{InterfaceID.TOPLEVEL_PRE_EOC, InterfaceID.TOPLEVEL_OSRS_STRETCH})
		{
			when(client.getTopLevelInterfaceId()).thenReturn(root);
			for (boolean gpu : new boolean[]{false, true})
			{
				when(client.isGpu()).thenReturn(gpu);
				plugin.onCanvasSizeChanged(null);
				Point item = renderLateNativeItem(Color.BLUE, true);
				assertEquals("The actual first frame must include items drawn after the side-host layer hook: root=" + root + " gpu=" + gpu,
					Color.BLUE.getRGB(), image.getRGB(item.x, item.y));
			}
		}
	}

	@Test
	public void itemContainerChangesRefreshTheResizedItemImmediatelyThenResumeNormalThrottling()
	{
		eventBus.register(plugin);
		when(config.scale()).thenReturn(100); when(config.upperTabScale()).thenReturn(100);
		when(config.lowerTabScale()).thenReturn(100); when(config.panelScale()).thenReturn(186);
		when(config.filter()).thenReturn(ScalingFilter.NEAREST); when(config.scalingFps()).thenReturn(1);
		when(client.isGpu()).thenReturn(true);
		Point item = renderLateNativeItem(null, false);
		assertEquals(Color.RED.getRGB(), image.getRGB(item.x, item.y));
		eventBus.post(new net.runelite.api.events.ItemContainerChanged(net.runelite.api.InventoryID.EQUIPMENT.getId(),
			mock(net.runelite.api.ItemContainer.class)));
		item = renderLateNativeItem(Color.BLUE, false);
		assertEquals("Equipment changes cannot wait behind the panel's 1-FPS cache", Color.BLUE.getRGB(), image.getRGB(item.x, item.y));
		item = renderLateNativeItem(Color.GREEN, false);
		assertEquals("Unrelated pixel updates must still observe the configured limit", Color.BLUE.getRGB(), image.getRGB(item.x, item.y));
		eventBus.post(new net.runelite.api.events.ItemContainerChanged(net.runelite.api.InventoryID.INVENTORY.getId(),
			mock(net.runelite.api.ItemContainer.class)));
		item = renderLateNativeItem(Color.GREEN, false);
		assertEquals(Color.GREEN.getRGB(), image.getRGB(item.x, item.y));
		eventBus.unregister(plugin);
	}

	@Test
	public void inventoryEquipmentTabChangesRefreshImmediatelyWithXbrAt202Percent()
	{
		when(config.scale()).thenReturn(100); when(config.upperTabScale()).thenReturn(100);
		when(config.lowerTabScale()).thenReturn(100); when(config.panelScale()).thenReturn(202);
		when(config.filter()).thenReturn(ScalingFilter.XBR); when(config.scalingFps()).thenReturn(1);
		when(client.isGpu()).thenReturn(true);
		when(client.getVarcIntValue(net.runelite.api.VarClientInt.INVENTORY_TAB)).thenReturn(3);
		renderLateNativeItem(null, true);
		when(client.getVarcIntValue(net.runelite.api.VarClientInt.INVENTORY_TAB)).thenReturn(4);
		Point item = renderLateNativeItem(Color.BLUE, true);
		assertEquals("Opening worn equipment must not reuse the previous inventory image", Color.BLUE.getRGB(), image.getRGB(item.x, item.y));
		item = renderLateNativeItem(Color.GREEN, true);
		assertEquals("The tab transition bypass lasts one update, not forever", Color.BLUE.getRGB(), image.getRGB(item.x, item.y));
		when(client.getVarcIntValue(net.runelite.api.VarClientInt.INVENTORY_TAB)).thenReturn(3);
		item = renderLateNativeItem(Color.GREEN, true);
		assertEquals(Color.GREEN.getRGB(), image.getRGB(item.x, item.y));
	}

	private Point renderLateNativeItem(Color itemColor, boolean afterHostHook)
	{
		Widget host = client.getTopLevelInterfaceId() == InterfaceID.TOPLEVEL_PRE_EOC ? panel : classicPanel;
		Rectangle source = host.getBounds();
		Graphics2D g = image.createGraphics();
		try
		{
			g.setComposite(AlphaComposite.Clear); g.fillRect(0, 0, image.getWidth(), image.getHeight());
			g.setComposite(AlphaComposite.SrcOver);
			plugin.onBeforeRender(null); plugin.captureBackground();
			g.setColor(Color.RED); g.fill(source);
			if (!afterHostHook && itemColor != null) { g.setColor(itemColor); g.fillRect(source.x + 40, source.y + 60, 20, 20); }
			plugin.drawScaled(WidgetRegion.SIDE_PANEL, host.getId(), g);
			if (afterHostHook && itemColor != null) { g.setColor(itemColor); g.fillRect(source.x + 40, source.y + 60, 20, 20); }
			plugin.drawOutputs(g); plugin.finishRender();
		}
		finally { g.dispose(); }
		Rectangle destination = MinimapTransform.fit(source, config.panelScale(), 0, 0, image.getWidth(), image.getHeight(), true).destination();
		return new Point(destination.x + (int) Math.floor(50.0 * destination.width / source.width),
			destination.y + (int) Math.floor(70.0 * destination.height / source.height));
	}

	private void render(WidgetRegion... order)
	{
		render(true, order);
	}

	private void render(boolean drawMap, WidgetRegion... order)
	{
		Graphics2D clear = image.createGraphics();
		clear.setComposite(AlphaComposite.Clear);
		clear.fillRect(0, 0, image.getWidth(), image.getHeight());
		clear.dispose();
		plugin.onBeforeRender(null);
		plugin.captureBackground();
		Graphics2D graphics = image.createGraphics();
		try
		{
			for (WidgetRegion region : order)
			{
				Widget widget = client.getTopLevelInterfaceId() == InterfaceID.TOPLEVEL_OSRS_STRETCH
					? classicWidgets.get(region) : client.getTopLevelInterfaceId() == InterfaceID.TOPLEVEL_PRE_EOC
					? widgets.get(region) : null;
				if (widget != null && !widget.isHidden())
				{
					if (drawMap || region != WidgetRegion.MINIMAP)
					{
						graphics.setColor(widget == classicPanel ? Color.BLUE : Color.RED);
						graphics.fill(widget.getBounds());
					}
					plugin.drawScaled(region, widget.getId(), graphics);
				}
			}
			plugin.drawOutputs(graphics);
		}
		finally
		{
			graphics.dispose();
		}
		plugin.finishRender();
	}

	@After
	public void cleanup() { plugin.shutDown(); }

	private void renderAfterInterface(int group, Graphics2D graphics)
	{
		for (net.runelite.client.ui.overlay.Overlay overlay : new java.util.ArrayList<>(registeredOverlays))
		{
			if (overlay.getDrawHooks().contains(group << 16 | 0xffff)) { overlay.render(graphics); }
		}
	}

	private void renderAfterLayer(int component, Graphics2D graphics)
	{
		for (net.runelite.client.ui.overlay.Overlay overlay : new java.util.ArrayList<>(registeredOverlays))
		{
			if (overlay.getDrawHooks().contains(component)) { overlay.render(graphics); }
		}
	}

	private Widget prepareCentralWindow(int group, Rectangle bounds)
	{
		when(client.isGpu()).thenReturn(true);
		when(config.scale()).thenReturn(100);
		when(config.upperTabScale()).thenReturn(100);
		when(config.lowerTabScale()).thenReturn(100);
		when(config.interfaceScale()).thenReturn(150);
		when(config.filter()).thenReturn(ScalingFilter.NEAREST);
		Widget host = widget(WidgetRegion.INTERFACE.component(ResizableLayout.current(client)), new Rectangle(1000, 700));
		Widget window = mock(Widget.class);
		when(window.getId()).thenReturn(group << 16);
		when(window.getType()).thenReturn(net.runelite.api.widgets.WidgetType.RECTANGLE);
		when(window.getBounds()).thenReturn(bounds);
		when(host.getNestedChildren()).thenReturn(new Widget[]{window});
		return window;
	}

	private void renderCentralWindow(Widget window, Color color)
	{
		Graphics2D graphics = image.createGraphics();
		try
		{
			graphics.setComposite(AlphaComposite.Clear);
			graphics.fillRect(0, 0, 1000, 700);
			graphics.setComposite(AlphaComposite.SrcOver);
			plugin.onBeforeRender(null);
			plugin.captureBackground();
			plugin.drawScaled(WidgetRegion.INTERFACE, WidgetRegion.INTERFACE.component(ResizableLayout.current(client)), graphics);
			graphics.setColor(color);
			graphics.fill(window.getBounds());
			renderAfterInterface(window.getId() >>> 16, graphics);
			plugin.drawOutputs(graphics);
			plugin.finishRender();
		}
		finally { graphics.dispose(); }
	}

	@Test
	public void compatibilityStillGatesInvisibleUnreadyBankOpeningFrames()
	{
		when(config.preservePanelActions()).thenReturn(true);
		unreadyBankKeepsItsBackdropVisibleUntilRealWindowBoundsAreAvailable();
	}

	@Test
	public void unreadyBankKeepsItsBackdropVisibleUntilRealWindowBoundsAreAvailable()
	{
		// Rollback guard: uninitialized metadata must not turn arbitrary painted
		// fragments into a guessed window or a scaled input target.
		image = new BufferedImage(1432, 862, BufferedImage.TYPE_INT_ARGB);
		when(((MainBufferProvider) client.getBufferProvider()).getImage()).thenReturn(image);
		Rectangle nativeWindow = new Rectangle(472, 185, 488, 492);
		Rectangle nativeClose = new Rectangle(924, 193, 20, 16);
		for (int rootId : new int[]{InterfaceID.TOPLEVEL_PRE_EOC, InterfaceID.TOPLEVEL_OSRS_STRETCH})
		{
			when(client.getTopLevelInterfaceId()).thenReturn(rootId);
			Widget window = prepareCentralWindow(InterfaceID.BANKMAIN, new Rectangle());
			when(config.interfaceScale()).thenReturn(174);
			Widget host = client.getWidget(WidgetRegion.INTERFACE.component(ResizableLayout.current(client)));
			when(host.getBounds()).thenReturn(new Rectangle(460, 183, 512, 495));
			for (int frame = 0; frame < 2; frame++)
			{
				when(window.getBounds()).thenReturn(frame == 0 ? new Rectangle() : nativeWindow);
				Graphics2D graphics = image.createGraphics();
				try
				{
					graphics.setComposite(AlphaComposite.Clear);
					graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
					graphics.setComposite(AlphaComposite.SrcOver);
					plugin.onBeforeRender(null);
					plugin.captureBackground();
					graphics.setColor(Color.BLUE);
					graphics.fill(nativeWindow); // Pre-interface scene/UI backdrop.
					plugin.drawScaled(WidgetRegion.INTERFACE, host.getId(), graphics);
					graphics.setColor(Color.GREEN);
					graphics.fill(nativeWindow);
					graphics.setColor(Color.RED);
					graphics.fill(nativeClose);
					renderAfterInterface(InterfaceID.BANKMAIN, graphics);
					plugin.drawOutputs(graphics);
					plugin.finishRender();
				}
				finally { graphics.dispose(); }
				if (frame == 0)
				{
					assertTrue(plugin.getInputFrames().isEmpty());
					assertEquals("Keep the original backdrop instead of presenting a native-size bank", Color.BLUE.getRGB(), image.getRGB(934, 201));
					assertEquals(0, image.getRGB(292, 3));
					verify(host).setNoClickThrough(false);
				}
				else
				{
					assertEquals(Color.GREEN.getRGB(), image.getRGB(292, 3));
					assertEquals(Color.RED.getRGB(), image.getRGB(1096, 31));
					assertTrue(nativeClose.contains(InputRoute.resolve(new Point(1096, 31), plugin.getInputFrames()).point));
					verify(host).setNoClickThrough(true);
				}
			}
		}
	}

	@Test
	public void mismatchedOpeningHostKeepsTheSceneThenShowsTheFullBankWhenItSettles()
	{
		image = new BufferedImage(1396, 862, BufferedImage.TYPE_INT_ARGB);
		when(((MainBufferProvider) client.getBufferProvider()).getImage()).thenReturn(image);
		when(client.isGpu()).thenReturn(true);
		when(config.scale()).thenReturn(100);
		when(config.upperTabScale()).thenReturn(100);
		when(config.lowerTabScale()).thenReturn(100);
		when(config.interfaceScale()).thenReturn(137);
		when(config.filter()).thenReturn(ScalingFilter.NEAREST);
		for (int rootId : new int[]{InterfaceID.TOPLEVEL_PRE_EOC, InterfaceID.TOPLEVEL_OSRS_STRETCH})
		{
			when(client.getTopLevelInterfaceId()).thenReturn(rootId);
			int hostId = WidgetRegion.INTERFACE.component(ResizableLayout.current(client));
			InterfaceLayoutTest.ResponsiveWindow bank = new InterfaceLayoutTest.ResponsiveWindow(hostId);
			bank.parentWidth = 1396;
			bank.parentHeight = 862;
			bank.centeredHost = true;
			bank.widthMode = net.runelite.api.widgets.WidgetSizeMode.ABSOLUTE;
			bank.originalWidth = 512;
			bank.maximumHeight = 1000;
			bank.topInset = 2;
			bank.heightMargin = 3;
			bank.deferRenderPositions = true;
			bank.reflow();
			bank.commitNativeDraw();
			when(client.getWidget(hostId)).thenReturn(bank.host);
			int[] scene = null;
			for (int frame = 0; frame < 2; frame++)
			{
				Graphics2D graphics = image.createGraphics();
				try
				{
					graphics.setColor(new Color(0x123456));
					graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
					if (frame == 0) { scene = PackedPixels.nativePixels(image).pixels.clone(); }
					plugin.onBeforeRender(null);
					if (frame == 0)
					{
						// Replay the logged old y=0 host and cropped y=530 opening body.
						bank.renderPositions.put(bank.host, new Point(442, 0));
						bank.renderPositions.put(bank.root, new Point(442, 0));
						bank.renderPositions.put(bank.frame, new Point(454, 530));
						bank.renderPositions.put(bank.close, new Point(918, 538));
						bank.renderPositions.put(bank.bottom, new Point(474, 1132));
					}
					else { bank.commitNativeDraw(); }
					plugin.captureBackground();
					plugin.drawScaled(WidgetRegion.INTERFACE, hostId, graphics);
					graphics.setColor(Color.GREEN);
					graphics.fill(bank.windowBounds);
					renderAfterInterface(InterfaceID.BANKMAIN, graphics);
					graphics.setColor(Color.RED);
					graphics.fill(bank.closeBounds); // Late native/icon draw must also be withheld.
					plugin.drawOutputs(graphics);
					plugin.finishRender();
				}
				finally { graphics.dispose(); }
				if (frame == 0)
				{
					assertArrayEquals("An unstable opening must present only the saved scene", scene, PackedPixels.nativePixels(image).pixels);
					assertTrue(plugin.getInputFrames().isEmpty());
				}
				else
				{
					assertEquals(Color.GREEN.getRGB(), image.getRGB(364, 3));
					assertEquals(Color.GREEN.getRGB(), image.getRGB(1032, 860));
					assertEquals(WidgetRegion.INTERFACE, InputRoute.resolve(new Point(400, 400), plugin.getInputFrames()).target);
				}
			}
		}
	}

	@Test
	public void bankLayoutIsPreparedBeforeNativePositionsAreCachedInsteadOfScalingA99PixelStrip()
	{
		image = new BufferedImage(1396, 862, BufferedImage.TYPE_INT_ARGB);
		when(((MainBufferProvider) client.getBufferProvider()).getImage()).thenReturn(image);
		when(client.isGpu()).thenReturn(true);
		when(config.scale()).thenReturn(100);
		when(config.upperTabScale()).thenReturn(100);
		when(config.lowerTabScale()).thenReturn(100);
		when(config.interfaceScale()).thenReturn(137);
		when(config.filter()).thenReturn(ScalingFilter.NEAREST);
		for (int rootId : new int[]{InterfaceID.TOPLEVEL_PRE_EOC, InterfaceID.TOPLEVEL_OSRS_STRETCH})
		{
			when(client.getTopLevelInterfaceId()).thenReturn(rootId);
			int hostId = WidgetRegion.INTERFACE.component(ResizableLayout.current(client));
			InterfaceLayoutTest.ResponsiveWindow bank = new InterfaceLayoutTest.ResponsiveWindow(hostId);
			bank.parentWidth = 1396;
			bank.parentHeight = 862;
			bank.centeredHost = true;
			bank.widthMode = net.runelite.api.widgets.WidgetSizeMode.ABSOLUTE;
			bank.originalWidth = 512;
			bank.maximumHeight = 1000;
			bank.topInset = 2;
			bank.heightMargin = 3;
			bank.deferRenderPositions = true;
			bank.reflow();
			bank.commitNativeDraw();
			when(client.getWidget(hostId)).thenReturn(bank.host);
			for (int opening = 0; opening < 3; opening++)
			{
				when(bank.host.getNestedChildren()).thenReturn(new Widget[]{bank.root});
				Graphics2D graphics = image.createGraphics();
				try
				{
					graphics.setComposite(AlphaComposite.Clear);
					graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
					graphics.setComposite(AlphaComposite.SrcOver);
					clearInvocations(bank.host);
					plugin.onBeforeRender(null);
					assertEquals("Layout must settle before the native root-position cache pass", new Rectangle(442, 116, 512, 629), bank.hostBounds);
					bank.commitNativeDraw();
					plugin.captureBackground();
					plugin.drawScaled(WidgetRegion.INTERFACE, hostId, graphics);
					verify(bank.host, times(1)).revalidateScroll();
					assertEquals(new Rectangle(442, 116, 512, 629), bank.host.getBounds());
					assertEquals(new Rectangle(454, 118, 488, 626), WidgetRegion.INTERFACE.bounds(bank.host));
					graphics.setColor(Color.GREEN);
					graphics.fill(bank.windowBounds);
					graphics.setColor(Color.RED);
					graphics.fill(bank.closeBounds);
					renderAfterInterface(InterfaceID.BANKMAIN, graphics);
					plugin.drawOutputs(graphics);
					plugin.finishRender();
					assertEquals(Color.GREEN.getRGB(), image.getRGB(364, 3));
					assertEquals("The full 858-pixel bank must be present immediately", Color.GREEN.getRGB(), image.getRGB(1032, 860));
					assertEquals(WidgetRegion.INTERFACE, InputRoute.resolve(new Point(400, 400), plugin.getInputFrames()).target);
					when(bank.host.getNestedChildren()).thenReturn(new Widget[0]);
					plugin.onBeforeRender(null);
					bank.commitNativeDraw();
					plugin.captureBackground();
					plugin.drawOutputs(graphics);
					plugin.finishRender();
					assertTrue(plugin.getInputFrames().isEmpty());
				}
				finally { graphics.dispose(); }
			}
		}
	}

	@Test
	public void unfinishedBankSetupAndLateGeometryChangesNeverPresentIntermediateNativeSize()
	{
		when(client.isGpu()).thenReturn(true);
		when(config.scale()).thenReturn(100);
		when(config.upperTabScale()).thenReturn(100);
		when(config.lowerTabScale()).thenReturn(100);
		when(config.interfaceScale()).thenReturn(200);
		when(config.filter()).thenReturn(ScalingFilter.NEAREST);
		for (int rootId : new int[]{InterfaceID.TOPLEVEL_PRE_EOC, InterfaceID.TOPLEVEL_OSRS_STRETCH})
		{
			when(client.getTopLevelInterfaceId()).thenReturn(rootId);
			int hostId = WidgetRegion.INTERFACE.component(ResizableLayout.current(client));
			InterfaceLayoutTest.ResponsiveWindow bank = new InterfaceLayoutTest.ResponsiveWindow(hostId);
			when(client.getWidget(hostId)).thenReturn(bank.host);
			bank.maximumHeight = 180;
			bank.reflow();
			bank.commitNativeDraw();
			when(bank.host.getNestedChildren()).thenReturn(new Widget[0]);
			for (int frame = 0; frame < 3; frame++)
			{
				Graphics2D graphics = image.createGraphics();
				try
				{
					graphics.setComposite(AlphaComposite.Clear);
					graphics.fillRect(0, 0, 1000, 700);
					graphics.setComposite(AlphaComposite.SrcOver);
					plugin.onBeforeRender(null);
					plugin.captureBackground();
					// The group can become visible after the early UNDER_WIDGETS pass.
					when(bank.host.getNestedChildren()).thenReturn(new Widget[]{bank.root});
					plugin.drawScaled(WidgetRegion.INTERFACE, hostId, graphics);
					assertEquals("A late-attached bank must wait for safe BeforeRender preparation, not reflow during drawing",
						frame == 0 ? new Rectangle(1000, 700) : new Rectangle(512, 350), bank.hostBounds);
					// Native bank setup finishes after backdrop capture, not before it.
					bank.maximumHeight = 544;
					bank.reflow();
					bank.commitNativeDraw();
					graphics.setColor(Color.GREEN);
					graphics.fill(bank.windowBounds);
					graphics.setColor(Color.RED);
					graphics.fill(bank.closeBounds);
					renderAfterInterface(InterfaceID.BANKMAIN, graphics);
					plugin.drawOutputs(graphics);
					plugin.finishRender();
					if (frame == 0)
					{
						assertTrue("An unprepared late opening must not publish input", plugin.getInputFrames().isEmpty());
						assertEquals("Remove the native-size late opening until safe layout preparation", 0, image.getRGB(500, 100));
						continue;
					}
					assertEquals(Color.GREEN.getRGB(), image.getRGB(12, 24));
					assertEquals("Every presented opening frame must have the final full height", Color.GREEN.getRGB(), image.getRGB(987, 675));
					assertEquals(Color.RED.getRGB(), image.getRGB(956, 56));
					assertEquals(Color.GREEN.getRGB(), image.getRGB(484, 28));
					assertEquals(new Point(484, 28), InputRoute.resolve(new Point(956, 56), plugin.getInputFrames()).point);
				}
				finally { graphics.dispose(); }
			}
			plugin.onCanvasSizeChanged(null);
		}
	}

	@Test
	public void establishedBankDoesNotReenterOpeningGuardAfterEquipmentRefreshInvalidatesInput()
	{
		for (boolean gpu : new boolean[]{false, true})
		for (int root : new int[]{InterfaceID.TOPLEVEL_PRE_EOC, InterfaceID.TOPLEVEL_OSRS_STRETCH})
		for (int group : new int[]{InterfaceID.BANKMAIN, InterfaceID.SHARED_BANK})
		{
			when(client.getTopLevelInterfaceId()).thenReturn(root);
			Widget window = prepareCentralWindow(group, new Rectangle(300, 120, 300, 240));
			when(client.isGpu()).thenReturn(gpu);
			Widget host = client.getWidget(WidgetRegion.INTERFACE.component(ResizableLayout.current(client)));
			renderCentralWindow(window, Color.GREEN);
			assertEquals(Color.GREEN.getRGB(), image.getRGB(240, 75));
			plugin.onClientTick(null);
			plugin.onItemContainerChanged(null);
			// A missed capture/refresh drops the transient input frame. This must
			// not turn an already presented bank into a newly opening bank.
			plugin.onBeforeRender(null);
			plugin.captureBackground();
			plugin.finishRender();
			when(host.getWidth()).thenReturn(512);
			when(host.getHeight()).thenReturn(350);
			when(host.getRelativeY()).thenReturn(1);
			// Rendered positions remain authoritative for the current native draw;
			// scripts have already changed the next logical layout and origin.
			renderCentralWindow(window, Color.MAGENTA);
			assertEquals("Refresh must present current bank pixels, not hide it or replay old items",
				Color.MAGENTA.getRGB(), image.getRGB(240, 75));
			assertEquals(new Point(310, 130), InputRoute.resolve(new Point(240, 75), plugin.getInputFrames()).point);
			// A real close/reopen must still rearm the opening safety gate.
			when(host.getNestedChildren()).thenReturn(new Widget[0]);
			renderCentralWindow(window, Color.BLUE);
			when(host.getNestedChildren()).thenReturn(new Widget[]{window});
			renderCentralWindow(window, Color.RED);
			assertTrue("An unfinished reopened bank must not publish input", plugin.getInputFrames().isEmpty());
			assertEquals("Unready reopening remains hidden", 0, image.getRGB(240, 75));
			plugin.onCanvasSizeChanged(null);
		}
	}

	@Test
	public void bankResizeRunsNativeTimerAfterParentReflow()
	{
		when(config.scale()).thenReturn(100);
		when(config.upperTabScale()).thenReturn(100);
		when(config.lowerTabScale()).thenReturn(100);
		when(config.interfaceScale()).thenReturn(150);
		int hostId = InterfaceID.ToplevelPreEoc.MAINMODAL;
		InterfaceLayoutTest.ResponsiveWindow bank = new InterfaceLayoutTest.ResponsiveWindow(hostId);
		bank.parentHeight = 862;
		bank.maximumHeight = 800;
		bank.reflow();
		when(client.getWidget(hostId)).thenReturn(bank.host);
		when(client.getWidget(InterfaceID.Bankmain.UNIVERSE)).thenReturn(bank.frame);
		// Unlike the old fixture, parent revalidation must NOT repair an absolute
		// bank frame. Only the native timer computes min(800, parentHeight - 3).
		doAnswer(call ->
		{
			int oldHeight = bank.windowBounds.height;
			bank.reflow();
			bank.windowBounds.height = oldHeight;
			return null;
		}).when(bank.host).revalidateScroll();
		doAnswer(call ->
		{
			assertEquals(574, bank.host.getHeight());
			bank.windowBounds.height = Math.min(800, bank.host.getHeight() - 3);
			return null;
		}).when(client).runScript(eq(839), eq(InterfaceID.Bankmain.UNIVERSE), eq(InterfaceID.BANKMAIN << 16),
			eq(-1), eq(-1), eq(InterfaceID.Bankmain.SCROLLBAR), eq(InterfaceID.Bankmain.ITEMS));
		plugin.onBeforeRender(null);
		assertEquals(571, bank.windowBounds.height);
		// Simulate toplevel_resize restoring the native parent, followed by its
		// timer growing the bank. The next preparation must fix both together.
		bank.originalHeight = 0;
		bank.heightMode = net.runelite.api.widgets.WidgetSizeMode.MINUS;
		bank.reflow();
		assertEquals(800, bank.windowBounds.height);
		plugin.onBeforeRender(null);
		assertEquals(571, bank.windowBounds.height);
		clearInvocations(client, bank.host);
		plugin.onItemContainerChanged(null);
		plugin.onBeforeRender(null);
		verify(bank.host, never()).revalidateScroll();
		verify(client, never()).runScript(any(Object[].class));
	}

	@Test
	public void containerUpdatesDoNotRevalidateUnchangedBankLayout()
	{
		when(config.scale()).thenReturn(100);
		when(config.upperTabScale()).thenReturn(100);
		when(config.lowerTabScale()).thenReturn(100);
		when(config.interfaceScale()).thenReturn(200);
		for (int root : new int[]{InterfaceID.TOPLEVEL_PRE_EOC, InterfaceID.TOPLEVEL_OSRS_STRETCH})
		for (boolean compatibility : new boolean[]{false, true})
		{
			when(client.getTopLevelInterfaceId()).thenReturn(root);
			when(config.preservePanelActions()).thenReturn(compatibility);
			int hostId = WidgetRegion.INTERFACE.component(ResizableLayout.current(client));
			InterfaceLayoutTest.ResponsiveWindow bank = new InterfaceLayoutTest.ResponsiveWindow(hostId);
			when(client.getWidget(hostId)).thenReturn(bank.host);
			plugin.onBeforeRender(null);
			Rectangle expectedWindow = new Rectangle(bank.windowBounds);
			Rectangle expectedHost = new Rectangle(bank.hostBounds);
			clearInvocations(bank.host);
			plugin.onItemContainerChanged(null);
			verify(bank.host, never()).revalidateScroll();
			plugin.onBeforeRender(null);
			assertEquals(expectedWindow, bank.windowBounds);
			assertEquals(expectedHost, bank.hostBounds);
			verify(bank.host, never()).revalidateScroll();
			clearInvocations(bank.host);
			plugin.onBeforeRender(null);
			verify(bank.host, never()).revalidateScroll();
			plugin.onCanvasSizeChanged(null);
		}
	}

	@Test
	public void recordedEquipmentRefreshOriginMismatchKeepsFullBankAndCurrentPixels()
	{
		image = new BufferedImage(1432, 862, BufferedImage.TYPE_INT_ARGB);
		when(((MainBufferProvider) client.getBufferProvider()).getImage()).thenReturn(image);
		for (boolean gpu : new boolean[]{false, true})
		for (boolean compatibility : new boolean[]{false, true})
		for (int root : new int[]{InterfaceID.TOPLEVEL_PRE_EOC, InterfaceID.TOPLEVEL_OSRS_STRETCH})
		for (int group : new int[]{InterfaceID.BANKMAIN, InterfaceID.SHARED_BANK})
		{
			when(client.getTopLevelInterfaceId()).thenReturn(root);
			when(client.isGpu()).thenReturn(gpu);
			when(config.preservePanelActions()).thenReturn(compatibility);
			when(config.scale()).thenReturn(100);
			when(config.upperTabScale()).thenReturn(100);
			when(config.lowerTabScale()).thenReturn(100);
			when(config.interfaceScale()).thenReturn(150);
			when(config.filter()).thenReturn(ScalingFilter.NEAREST);
			int hostId = WidgetRegion.INTERFACE.component(ResizableLayout.current(client));
			InterfaceLayoutTest.ResponsiveWindow bank = new InterfaceLayoutTest.ResponsiveWindow(hostId);
			bank.group = group;
			bank.parentWidth = 1432;
			bank.parentHeight = 862;
			bank.originalWidth = 920;
			bank.centeredHost = true;
			bank.maximumHeight = 1000;
			bank.topInset = 2;
			bank.heightMargin = 3;
			bank.reflow();
			when(client.getWidget(hostId)).thenReturn(bank.host);
			for (int frame = 0; frame < 4; frame++)
			{
				Graphics2D g = image.createGraphics();
				try
				{
					g.setComposite(AlphaComposite.Clear);
					g.fillRect(0, 0, image.getWidth(), image.getHeight());
					g.setComposite(AlphaComposite.SrcOver);
					plugin.onBeforeRender(null);
					bank.commitNativeDraw();
					bank.deferRenderPositions = true;
					if (frame == 1)
					{
						plugin.onItemContainerChanged(null);
						// Exact live failure: host render Y=0 versus layout Y=144,
						// child render Y=290 causes the old clipped 488x284 capture.
						bank.renderPositions.put(bank.host, new Point(460, 0));
						bank.renderPositions.put(bank.frame, new Point(472, 290));
						bank.renderPositions.put(bank.close, new Point(bank.closeBounds.x, bank.closeBounds.y + 144));
						bank.renderPositions.put(bank.bottom, new Point(bank.bottomBounds.x, bank.bottomBounds.y + 144));
						assertEquals(new Rectangle(472, 290, 488, 284), WidgetRegion.INTERFACE.bounds(bank.host));
					}
					if (frame == 2)
					{
						// The host has caught up but suppressed descendants have not.
						bank.renderPositions.put(bank.frame, new Point(472, 290));
						bank.renderPositions.put(bank.close, new Point(bank.closeBounds.x, bank.closeBounds.y + 144));
						bank.renderPositions.put(bank.bottom, new Point(bank.bottomBounds.x, bank.bottomBounds.y + 144));
						assertNotEquals(WidgetRegion.INTERFACE.captureBounds(bank.host),WidgetRegion.INTERFACE.bounds(bank.host));
					}
					plugin.captureBackground();
					plugin.drawScaled(WidgetRegion.INTERFACE, hostId, g);
					Color current = frame == 1 || frame == 2 ? Color.MAGENTA : Color.GREEN;
					g.setColor(current);
					g.fill(bank.windowBounds);
					g.setColor(Color.RED);
					g.fill(bank.closeBounds);
					renderAfterInterface(group, g);
					plugin.drawOutputs(g);
					plugin.finishRender();
					assertEquals("Full bank top stays at the same screen position", current.getRGB(), image.getRGB(351, 4));
					assertEquals("Full bank bottom must not be clipped", current.getRGB(), image.getRGB(351, 858));
					Point nativeClose = new Point(bank.closeBounds.x + 8, bank.closeBounds.y + 8);
					Point visibleClose = new Point(350 + (int) Math.ceil((nativeClose.x - 472) * 732.0 / 488),
						3 + (int) Math.ceil((nativeClose.y - 146) * 857.0 / 571));
					assertEquals(Color.RED.getRGB(), image.getRGB(visibleClose.x, visibleClose.y));
					assertEquals(nativeClose, InputRoute.resolve(visibleClose, plugin.getInputFrames()).point);
				}
				finally { g.dispose(); }
			}
			plugin.onCanvasSizeChanged(null);
		}
	}

	@Test
	public void equipmentScriptSizingChangesAreNotRevalidatedInsideNativeDrawing()
	{
		for (boolean gpu : new boolean[]{false, true})
		for (boolean compatibility : new boolean[]{false, true})
		for (int root : new int[]{InterfaceID.TOPLEVEL_PRE_EOC, InterfaceID.TOPLEVEL_OSRS_STRETCH})
		{
			when(client.getTopLevelInterfaceId()).thenReturn(root);
			when(client.isGpu()).thenReturn(gpu);
			when(config.preservePanelActions()).thenReturn(compatibility);
			when(config.scale()).thenReturn(100);
			when(config.upperTabScale()).thenReturn(100);
			when(config.lowerTabScale()).thenReturn(100);
			when(config.interfaceScale()).thenReturn(200);
			when(config.filter()).thenReturn(ScalingFilter.NEAREST);
			int hostId = WidgetRegion.INTERFACE.component(ResizableLayout.current(client));
			InterfaceLayoutTest.ResponsiveWindow bank = new InterfaceLayoutTest.ResponsiveWindow(hostId);
			when(client.getWidget(hostId)).thenReturn(bank.host);
			for (int frame = 0; frame < 3; frame++)
			{
				Graphics2D g = image.createGraphics();
				try
				{
					g.setComposite(AlphaComposite.Clear);
					g.fillRect(0, 0, 1000, 700);
					g.setComposite(AlphaComposite.SrcOver);
					plugin.onBeforeRender(null);
					clearInvocations(bank.host);
					if (frame == 1)
					{
						// An equipment script rewrites sizing metadata after frame
						// preparation. Current native positions must not be reflowed
						// by our capture hooks, regardless of who initiated the op.
						bank.originalWidth = 0;
						bank.widthMode = net.runelite.api.widgets.WidgetSizeMode.MINUS;
						plugin.onItemContainerChanged(null);
					}
					plugin.captureBackground();
					plugin.drawScaled(WidgetRegion.INTERFACE, hostId, g);
					verify(bank.host, never()).revalidateScroll();
					g.setColor(frame == 1 ? Color.MAGENTA : Color.GREEN);
					g.fill(bank.windowBounds);
					g.setColor(Color.RED);
					g.fill(bank.closeBounds);
					renderAfterInterface(InterfaceID.BANKMAIN, g);
					plugin.drawOutputs(g);
					plugin.finishRender();
					assertEquals(frame == 1 ? Color.MAGENTA.getRGB() : Color.GREEN.getRGB(), image.getRGB(12, 24));
					assertEquals(Color.RED.getRGB(), image.getRGB(956, 56));
					assertEquals(new Point(484, 28), InputRoute.resolve(new Point(956, 56), plugin.getInputFrames()).point);
				}
				finally { g.dispose(); }
			}
			plugin.onCanvasSizeChanged(null);
		}
	}

	@Test
	public void responsiveBankIsScaledOnItsFirstFrameEvenWhenRenderCoordinatesAreStillOld()
	{
		when(client.isGpu()).thenReturn(true);
		when(config.scale()).thenReturn(100);
		when(config.upperTabScale()).thenReturn(100);
		when(config.lowerTabScale()).thenReturn(100);
		when(config.interfaceScale()).thenReturn(200);
		when(config.filter()).thenReturn(ScalingFilter.NEAREST);
		for (int rootId : new int[]{InterfaceID.TOPLEVEL_PRE_EOC, InterfaceID.TOPLEVEL_OSRS_STRETCH})
		{
			when(client.getTopLevelInterfaceId()).thenReturn(rootId);
			int hostId = WidgetRegion.INTERFACE.component(ResizableLayout.current(client));
			InterfaceLayoutTest.ResponsiveWindow bank = new InterfaceLayoutTest.ResponsiveWindow(hostId);
			bank.deferRenderPositions = true;
			when(client.getWidget(hostId)).thenReturn(bank.host);
			for (int opening = 0; opening < 3; opening++)
			{
				when(bank.host.getNestedChildren()).thenReturn(new Widget[]{bank.root});
				bank.commitNativeDraw();
				Graphics2D graphics = image.createGraphics();
				try
				{
					graphics.setComposite(AlphaComposite.Clear);
					graphics.fillRect(0, 0, 1000, 700);
					graphics.setComposite(AlphaComposite.SrcOver);
					plugin.onBeforeRender(null);
					plugin.captureBackground();
					plugin.drawScaled(WidgetRegion.INTERFACE, hostId, graphics);
					assertNotEquals("Native render X is not updated by revalidateScroll", bank.windowBounds.x, bank.frame.getBounds().x);
					// The native pass updates render coordinates only now, after our background capture.
					bank.commitNativeDraw();
					graphics.setColor(Color.GREEN);
					graphics.fill(bank.windowBounds);
					graphics.setColor(Color.RED);
					graphics.fill(bank.closeBounds);
					renderAfterInterface(InterfaceID.BANKMAIN, graphics);
					plugin.drawOutputs(graphics);
					plugin.finishRender();
					assertEquals("First presented frame must already have the final 200% size", Color.GREEN.getRGB(), image.getRGB(12, 24));
					assertEquals(Color.GREEN.getRGB(), image.getRGB(987, 675));
					assertEquals(Color.RED.getRGB(), image.getRGB(956, 56));
					assertEquals("No native-size close button may flash through", Color.GREEN.getRGB(), image.getRGB(484, 28));
					assertEquals(new Point(484, 28), InputRoute.resolve(new Point(956, 56), plugin.getInputFrames()).point);
					when(bank.host.getNestedChildren()).thenReturn(new Widget[0]);
					plugin.onBeforeRender(null);
					plugin.captureBackground();
					plugin.drawOutputs(graphics);
					plugin.finishRender();
					assertTrue(plugin.getInputFrames().isEmpty());
					assertEquals(new Rectangle(1000, 700), bank.hostBounds);
				}
				finally { graphics.dispose(); }
			}
		}
	}

	@Test
	public void fixedInterfacesUseFreshLayoutPositionsOnTheirFirstDraw()
	{
		for (int group : new int[]{InterfaceID.GE_OFFERS, InterfaceID.SHOPMAIN, InterfaceID.TRADEMAIN})
		{
			Widget window = prepareCentralWindow(group, new Rectangle(0, 0, 300, 240));
			Widget host = client.getWidget(WidgetRegion.INTERFACE.component(ResizableLayout.current(client)));
			when(window.getWidth()).thenReturn(300);
			when(window.getHeight()).thenReturn(240);
			when(window.getRelativeX()).thenReturn(300);
			when(window.getRelativeY()).thenReturn(120);
			when(window.getParent()).thenReturn(host);
			Graphics2D graphics = image.createGraphics();
			try
			{
				graphics.setComposite(AlphaComposite.Clear);
				graphics.fillRect(0, 0, 1000, 700);
				graphics.setComposite(AlphaComposite.SrcOver);
				plugin.onBeforeRender(null);
				plugin.captureBackground();
				plugin.drawScaled(WidgetRegion.INTERFACE, WidgetRegion.INTERFACE.component(ResizableLayout.current(client)), graphics);
				when(window.getBounds()).thenReturn(new Rectangle(300, 120, 300, 240));
				graphics.setColor(Color.BLUE);
				graphics.fill(window.getBounds());
				renderAfterInterface(group, graphics);
				plugin.drawOutputs(graphics);
				plugin.finishRender();
				assertEquals("Do not present a small native frame before the enlarged interface", Color.BLUE.getRGB(), image.getRGB(225, 60));
				assertEquals(new Point(310, 130), InputRoute.resolve(new Point(240, 75), plugin.getInputFrames()).point);
			}
			finally { graphics.dispose(); }
		}
	}

	@Test
	public void responsiveBankGrowsPast129PercentWithAlignedControlsAndRestoredLayout()
	{
		when(client.isGpu()).thenReturn(true);
		when(config.scale()).thenReturn(100);
		when(config.upperTabScale()).thenReturn(100);
		when(config.lowerTabScale()).thenReturn(100);
		when(config.interfaceScale()).thenReturn(200);
		when(config.filter()).thenReturn(ScalingFilter.NEAREST);
		for (int rootId : new int[]{InterfaceID.TOPLEVEL_PRE_EOC, InterfaceID.TOPLEVEL_OSRS_STRETCH})
		{
			when(client.getTopLevelInterfaceId()).thenReturn(rootId);
			int hostId = WidgetRegion.INTERFACE.component(ResizableLayout.current(client));
			InterfaceLayoutTest.ResponsiveWindow bank = new InterfaceLayoutTest.ResponsiveWindow(hostId);
			when(client.getWidget(hostId)).thenReturn(bank.host);
			Graphics2D graphics = image.createGraphics();
			try
			{
				graphics.setComposite(AlphaComposite.Clear);
				graphics.fillRect(0, 0, 1000, 700);
				graphics.setComposite(AlphaComposite.SrcOver);
				plugin.onBeforeRender(null);
				plugin.captureBackground();
				plugin.drawScaled(WidgetRegion.INTERFACE, hostId, graphics);
				graphics.setColor(Color.GREEN);
				graphics.fill(bank.windowBounds);
				graphics.setColor(Color.RED);
				graphics.fill(bank.closeBounds);
				graphics.fill(bank.bottomBounds);
				renderAfterInterface(InterfaceID.BANKMAIN, graphics);
				plugin.drawOutputs(graphics);
				plugin.finishRender();
			}
			finally { graphics.dispose(); }
			assertEquals(new Rectangle(512, 350), bank.hostBounds);
			assertEquals(Color.GREEN.getRGB(), image.getRGB(12, 24));
			assertEquals(Color.GREEN.getRGB(), image.getRGB(987, 675));
			for (Rectangle control : new Rectangle[]{bank.closeBounds, bank.bottomBounds})
			{
				Point nativePoint = new Point(control.x + 8, control.y + 8);
				Point visible = new Point(12 + 2 * (nativePoint.x - bank.windowBounds.x),
					24 + 2 * (nativePoint.y - bank.windowBounds.y));
				assertEquals(Color.RED.getRGB(), image.getRGB(visible.x, visible.y));
				assertEquals(nativePoint, InputRoute.resolve(visible, plugin.getInputFrames()).point);
			}
			plugin.onCanvasSizeChanged(null);
			assertEquals(new Rectangle(1000, 700), bank.hostBounds);
			assertTrue(plugin.getInputFrames().isEmpty());
		}
	}

	@Test
	public void centralInterfaceHooksFollowGroupChangesClosingBypassAndShutdown()
	{
		Widget window = prepareCentralWindow(InterfaceID.GE_OFFERS, new Rectangle(300, 120, 300, 240));
		Widget host = client.getWidget(WidgetRegion.INTERFACE.component(ResizableLayout.current(client)));
		renderCentralWindow(window, Color.GREEN);
		assertEquals(Color.GREEN.getRGB(), image.getRGB(225, 60));
		assertEquals(1L, registeredOverlays.stream().filter(overlay -> overlay instanceof InterfaceScaleOverlay).count());
		when(window.getId()).thenReturn(InterfaceID.BANKMAIN << 16);
		renderCentralWindow(window, Color.BLUE);
		assertEquals(Color.BLUE.getRGB(), image.getRGB(225, 60));
		assertEquals(1L, registeredOverlays.stream().filter(overlay -> overlay instanceof InterfaceScaleOverlay).count());
		assertFalse(registeredOverlays.stream().anyMatch(overlay -> overlay.getDrawHooks().contains(InterfaceID.GE_OFFERS << 16 | 0xffff)));
		assertTrue(registeredOverlays.stream().anyMatch(overlay -> overlay.getDrawHooks().contains(InterfaceID.BANKMAIN << 16 | 0xffff)));
		when(host.getNestedChildren()).thenReturn(new Widget[0]);
		renderCentralWindow(window, Color.BLUE);
		assertTrue(plugin.getInputFrames().isEmpty());
		assertFalse(registeredOverlays.stream().anyMatch(overlay -> overlay instanceof InterfaceScaleOverlay));
		when(host.getNestedChildren()).thenReturn(new Widget[]{window});
		when(config.interfaceScale()).thenReturn(100);
		renderCentralWindow(window, Color.BLUE);
		assertEquals(0, image.getRGB(225, 60));
		assertTrue(plugin.getInputFrames().isEmpty());
		assertFalse(registeredOverlays.stream().anyMatch(overlay -> overlay instanceof InterfaceScaleOverlay));
		when(config.interfaceScale()).thenReturn(150);
		renderCentralWindow(window, Color.BLUE);
		assertEquals(1L, registeredOverlays.stream().filter(overlay -> overlay instanceof InterfaceScaleOverlay).count());
		plugin.shutDown();
		assertFalse(registeredOverlays.stream().anyMatch(overlay -> overlay instanceof InterfaceScaleOverlay));
	}

	@Test
	public void completedBoundsAreScaledImmediatelyAndBlankWindowsNeverPublishInput()
	{
		Widget window = prepareCentralWindow(InterfaceID.GE_OFFERS, new Rectangle(300, 120, 300, 240));
		Graphics2D graphics = image.createGraphics();
		try
		{
			plugin.onBeforeRender(null);
			plugin.captureBackground();
			plugin.drawScaled(WidgetRegion.INTERFACE, WidgetRegion.INTERFACE.component(ResizableLayout.current(client)), graphics);
			renderAfterInterface(InterfaceID.GE_OFFERS, graphics); // No actual window pixels drawn.
			plugin.drawOutputs(graphics);
			plugin.finishRender();
			assertTrue(plugin.getInputFrames().isEmpty());
			plugin.onBeforeRender(null);
			plugin.captureBackground();
			plugin.drawScaled(WidgetRegion.INTERFACE, WidgetRegion.INTERFACE.component(ResizableLayout.current(client)), graphics);
			// Native render coordinates settle only during drawing after a window move.
			when(window.getBounds()).thenReturn(new Rectangle(400, 200, 300, 240));
			graphics.setColor(Color.BLUE);
			graphics.fill(window.getBounds());
			renderAfterInterface(InterfaceID.GE_OFFERS, graphics);
			plugin.drawOutputs(graphics);
			plugin.finishRender();
			assertEquals(Color.BLUE.getRGB(), image.getRGB(450, 250));
			assertEquals("Do not fall back to a small native window when geometry settles late", Color.BLUE.getRGB(), image.getRGB(325, 140));
			assertEquals(new Point(410, 210), InputRoute.resolve(new Point(340, 155), plugin.getInputFrames()).point);
		}
		finally { graphics.dispose(); }
		renderCentralWindow(window, Color.BLUE);
		assertEquals(Color.BLUE.getRGB(), image.getRGB(325, 140));
		assertEquals(new Point(410, 210), InputRoute.resolve(new Point(340, 155), plugin.getInputFrames()).point);
	}

	@Test
	public void hqLateInterfacePixelsUseTheCompletedNestedWindowCapture() throws Exception
	{
		Widget window = prepareCentralWindow(InterfaceID.GE_OFFERS, new Rectangle(300, 120, 300, 240));
		when(config.panelScale()).thenReturn(150);
		java.lang.reflect.Field bridge = MinimapResizePlugin.class.getDeclaredField("hqIcons");
		bridge.setAccessible(true);
		bridge.set(plugin, new HqIconCompatibility(overlayManager, overlay -> overlay instanceof FakeHqIcons.UnderInterfacePainter));
		FakeHqIcons.UnderInterfacePainter painter = new FakeHqIcons.UnderInterfacePainter();
		overlayManager.add(painter);
		Graphics2D graphics = image.createGraphics();
		try
		{
			plugin.onBeforeRender(null);
			plugin.captureBackground();
			plugin.drawScaled(WidgetRegion.INTERFACE, WidgetRegion.INTERFACE.component(ResizableLayout.current(client)), graphics);
			graphics.setColor(Color.GREEN);
			graphics.fill(window.getBounds());
			renderAfterInterface(InterfaceID.GE_OFFERS, graphics);
			painter.draw = () ->
			{
				graphics.setColor(Color.RED);
				graphics.fillRect(330, 140, 20, 15);
			};
			// Another region's hook must not replace the deferred window backdrop.
			plugin.drawScaled(WidgetRegion.MINIMAP, map.getId(), graphics);
			plugin.drawOutputs(graphics);
			plugin.finishRender();
			assertEquals(1, painter.calls);
			assertEquals(Color.RED.getRGB(), image.getRGB(280, 100));
			assertEquals(Color.GREEN.getRGB(), image.getRGB(335, 145));
			assertEquals(new Point(336, 146), InputRoute.resolve(new Point(280, 100), plugin.getInputFrames()).point);
		}
		finally { graphics.dispose(); }
	}

	@Test
	public void nestedGeAndOtherWindowsScaleAfterTheirContentsDrawWithMatchingClicks()
	{
		when(client.isGpu()).thenReturn(true);
		when(config.scale()).thenReturn(100);
		when(config.upperTabScale()).thenReturn(100);
		when(config.lowerTabScale()).thenReturn(100);
		when(config.interfaceScale()).thenReturn(150);
		when(config.filter()).thenReturn(ScalingFilter.NEAREST);
		for (int rootId : new int[]{InterfaceID.TOPLEVEL_PRE_EOC, InterfaceID.TOPLEVEL_OSRS_STRETCH})
		{
			when(client.getTopLevelInterfaceId()).thenReturn(rootId);
			ResizableLayout activeLayout = ResizableLayout.current(client);
			for (WidgetRegion region : new WidgetRegion[]{WidgetRegion.INTERFACE, WidgetRegion.INTERFACE_CRM, WidgetRegion.INTERFACE_FLOAT})
			{
				Widget host = widget(region.component(activeLayout), new Rectangle(1000, 700));
				for (int group : new int[]{InterfaceID.GE_OFFERS, InterfaceID.BANKMAIN, InterfaceID.SHOPMAIN, InterfaceID.TRADEMAIN})
				{
					Widget universe = mock(Widget.class), frame = mock(Widget.class), control = mock(Widget.class);
					when(universe.getId()).thenReturn(group << 16);
					when(universe.getBounds()).thenReturn(new Rectangle(1000, 700));
					when(universe.getStaticChildren()).thenReturn(new Widget[]{frame, control});
					when(frame.getType()).thenReturn(net.runelite.api.widgets.WidgetType.RECTANGLE);
					when(frame.getBounds()).thenReturn(new Rectangle(300, 120, 300, 240));
					when(control.getType()).thenReturn(net.runelite.api.widgets.WidgetType.RECTANGLE);
					when(control.getBounds()).thenReturn(new Rectangle(330, 140, 20, 15));
					when(host.getNestedChildren()).thenReturn(new Widget[]{universe});
					Graphics2D graphics = image.createGraphics();
					try
					{
						graphics.setComposite(AlphaComposite.Clear);
						graphics.fillRect(0, 0, 1000, 700);
						graphics.setComposite(AlphaComposite.SrcOver);
						plugin.onBeforeRender(null);
						plugin.captureBackground();
						// RuneLite reports the host's static layer before drawing its attached group.
						plugin.drawScaled(region, host.getId(), graphics);
						graphics.setColor(Color.GREEN);
						graphics.fill(frame.getBounds());
						graphics.setColor(Color.RED);
						graphics.fill(control.getBounds());
						renderAfterInterface(group, graphics);
						plugin.drawOutputs(graphics);
						plugin.finishRender();
					}
					finally { graphics.dispose(); }
					assertEquals("Window must visibly enlarge after its nested group draws", Color.GREEN.getRGB(), image.getRGB(225, 60));
					assertEquals(Color.RED.getRGB(), image.getRGB(280, 100));
					assertEquals("Native-size control must not remain under the scaled window", Color.GREEN.getRGB(), image.getRGB(335, 145));
					MinimapMouseListener listener = new MinimapMouseListener(client, plugin);
					MouseEvent click = listener.mousePressed(new MouseEvent(new Canvas(), MouseEvent.MOUSE_PRESSED, 1000,
						0, 280, 100, 1, false, MouseEvent.BUTTON1));
					assertEquals(new Point(336, 146), click.getPoint());
					assertTrue(control.getBounds().contains(click.getPoint()));
					listener.mouseReleased(new MouseEvent(new Canvas(), MouseEvent.MOUSE_RELEASED, 1001,
						0, 280, 100, 1, false, MouseEvent.BUTTON1));
					when(client.getMouseLastPressedMillis()).thenReturn(1000L);
					plugin.onClientTick(null);
				}
				when(host.getNestedChildren()).thenReturn(new Widget[0]);
			}
		}
	}

	@Test
	public void startupRegistersWithRealEventBusAndLateHoverListenerUnregistersOnShutdown()
	{
		java.util.concurrent.atomic.AtomicBoolean nativeHover = new java.util.concurrent.atomic.AtomicBoolean(true);
		when(client.isMouseoverTextEnabled()).thenAnswer(call -> nativeHover.get());
		doAnswer(call -> { nativeHover.set(call.getArgument(0)); return null; })
			.when(client).setMouseoverTextEnabled(anyBoolean());
		when(config.hoverScale()).thenReturn(150);
		// PluginManager performs this real registration when enabling the toggle.
		eventBus.register(plugin);
		eventBus.register(BeforeRender.class, event ->
		{
			assertTrue("The late hover hook must run after normal-priority plugins", nativeHover.get());
		}, 0);
		eventBus.post(new BeforeRender());
		assertFalse("The late hook suppresses native hover text for our scaled replacement", nativeHover.get());
		Graphics2D graphics = image.createGraphics();
		try { plugin.drawHover(graphics, false); }
		finally { graphics.dispose(); }
		assertTrue(nativeHover.get());
		plugin.shutDown();
		eventBus.unregister(plugin);
		clearInvocations(client);
		eventBus.post(new BeforeRender());
		verify(client, never()).isMouseoverTextEnabled();
		verify(client, never()).setMouseoverTextEnabled(anyBoolean());
	}

	@Test
	public void chatScalesAfterItsAttachedGroupDrawsWithAlignedControlsAndNoNativeGhosts()
	{
		when(config.scale()).thenReturn(100);
		when(config.upperTabScale()).thenReturn(100);
		when(config.lowerTabScale()).thenReturn(100);
		when(config.filter()).thenReturn(ScalingFilter.NEAREST);
		for (int root : new int[]{InterfaceID.TOPLEVEL_PRE_EOC, InterfaceID.TOPLEVEL_OSRS_STRETCH})
		{
			when(client.getTopLevelInterfaceId()).thenReturn(root);
			ResizableLayout layout = ResizableLayout.current(client);
			Widget chat = widget(WidgetRegion.CHAT.component(layout), new Rectangle(10, 500, 400, 180));
			for (boolean gpu : new boolean[]{false, true})
			{
				when(client.isGpu()).thenReturn(gpu);
				for (int percent : new int[]{150, 50, 200, 100})
				{
					when(config.chatScale()).thenReturn(percent);
					Graphics2D g = image.createGraphics();
					try
					{
						g.setColor(Color.DARK_GRAY);
						g.fillRect(0, 0, 1000, 700);
						plugin.onBeforeRender(null);
						plugin.captureBackground();
						// The host's layer finishes BEFORE the attached CHATBOX group paints.
						renderAfterLayer(chat.getId(), g);
						g.setColor(Color.BLUE);
						g.fill(chat.getBounds());
						g.setColor(Color.RED);
						g.fillRect(30, 620, 100, 10); // typing line
						g.setColor(Color.GREEN);
						g.fillRect(370, 650, 20, 10); // chat controls
						renderAfterInterface(InterfaceID.CHATBOX, g);
						plugin.drawOutputs(g);
						plugin.finishRender();
					}
					finally { g.dispose(); }
					BufferedImage expected = new BufferedImage(1000, 700, BufferedImage.TYPE_INT_ARGB);
					Graphics2D e = expected.createGraphics();
					try
					{
						e.setColor(Color.DARK_GRAY);
						e.fillRect(0, 0, 1000, 700);
						e.setColor(Color.BLUE);
						e.fillRect(10, 680 - 180 * percent / 100, 400 * percent / 100, 180 * percent / 100);
						e.setColor(Color.RED);
						e.fillRect(10 + 20 * percent / 100, 680 - 60 * percent / 100, 100 * percent / 100, 10 * percent / 100);
						e.setColor(Color.GREEN);
						e.fillRect(10 + 360 * percent / 100, 680 - 30 * percent / 100, 20 * percent / 100, 10 * percent / 100);
					}
					finally { e.dispose(); }
					String context = layout + " gpu=" + gpu + " chat=" + percent;
					assertArrayEquals(context, expected.getRGB(0, 0, 1000, 700, null, 0, 1000),
						image.getRGB(0, 0, 1000, 700, null, 0, 1000));
					Point control = new Point(10 + 370 * percent / 100, 680 - 25 * percent / 100);
					InputRoute route = InputRoute.resolve(control, plugin.getInputFrames());
					if (percent == 100)
					{
						assertNull(context, route.target);
						assertEquals(control, route.point);
					}
					else
					{
						assertEquals(context, WidgetRegion.CHAT, route.target);
						assertTrue(context, new Rectangle(370, 650, 20, 10).contains(route.point));
						MinimapMouseListener listener = new MinimapMouseListener(client, plugin);
						MouseEvent wheel = listener.mouseWheelMoved(new java.awt.event.MouseWheelEvent(new Canvas(),
							MouseEvent.MOUSE_WHEEL, 123, 0, control.x, control.y, 0, false,
							java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 1));
						assertEquals(context, route.point, wheel.getPoint());
					}
				}
			}
		}
	}

	@Test
	public void compatibilityRetainsChatInputAndBankToInventoryDragTransforms()
	{
		when(config.preservePanelActions()).thenReturn(true);
		chatAndCentralInterfacesScaleAndRouteIndependentlyInBothLayouts();
	}

	@Test
	public void chatAndCentralInterfacesScaleAndRouteIndependentlyInBothLayouts()
	{
		when(client.isGpu()).thenReturn(true);
		when(config.scale()).thenReturn(100);
		when(config.upperTabScale()).thenReturn(100);
		when(config.lowerTabScale()).thenReturn(100);
		when(config.chatScale()).thenReturn(150);
		when(config.interfaceScale()).thenReturn(150);
		when(config.panelScale()).thenReturn(150);
		when(config.filter()).thenReturn(ScalingFilter.NEAREST);
		for (int root : new int[]{InterfaceID.TOPLEVEL_PRE_EOC, InterfaceID.TOPLEVEL_OSRS_STRETCH})
		{
			when(client.getTopLevelInterfaceId()).thenReturn(root);
			ResizableLayout layout = root == InterfaceID.TOPLEVEL_PRE_EOC ? ResizableLayout.MODERN : ResizableLayout.CLASSIC;
			Widget chat = widget(WidgetRegion.CHAT.component(layout), new Rectangle(10, 500, 400, 180));
			Widget host = widget(WidgetRegion.INTERFACE.component(layout), new Rectangle(1000, 700));
			Widget window = mock(Widget.class);
			when(window.getId()).thenReturn(InterfaceID.BANKMAIN << 16);
			when(window.getBounds()).thenReturn(new Rectangle(300, 120, 300, 240));
			when(window.getType()).thenReturn(net.runelite.api.widgets.WidgetType.RECTANGLE);
			when(host.getNestedChildren()).thenReturn(new Widget[]{window});
			Widget inventory = layout == ResizableLayout.MODERN ? panel : classicPanel;
			Graphics2D g = image.createGraphics();
			try
			{
				g.setComposite(AlphaComposite.Clear);
				g.fillRect(0, 0, 1000, 700);
				g.setComposite(AlphaComposite.SrcOver);
				plugin.onBeforeRender(null);
				plugin.captureBackground();
				plugin.drawScaled(WidgetRegion.INTERFACE, host.getId(), g);
				g.setColor(Color.GREEN);
				g.fill(window.getBounds());
				renderAfterInterface(InterfaceID.BANKMAIN, g);
				renderAfterLayer(chat.getId(), g);
				g.setColor(Color.BLUE);
				g.fill(chat.getBounds());
				renderAfterInterface(InterfaceID.CHATBOX, g);
				g.setColor(Color.RED);
				g.fill(inventory.getBounds());
				plugin.drawScaled(WidgetRegion.SIDE_PANEL, inventory.getId(), g);
				plugin.drawOutputs(g);
				plugin.finishRender();
			}
			finally { g.dispose(); }
			assertEquals(Color.BLUE.getRGB(), image.getRGB(20, 600));
			assertEquals(Color.GREEN.getRGB(), image.getRGB(240, 75));
			InputRoute chatInput = InputRoute.resolve(new Point(20, 600), plugin.getInputFrames());
			assertEquals(WidgetRegion.CHAT, chatInput.target);
			assertEquals(new Point(16, 626), chatInput.point);
			InputRoute bankInput = InputRoute.resolve(new Point(240, 75), plugin.getInputFrames());
			assertEquals(WidgetRegion.INTERFACE, bankInput.target);
			assertEquals(new Point(310, 130), bankInput.point);
			MinimapMouseListener listener = new MinimapMouseListener(client, plugin);
			listener.mousePressed(new MouseEvent(new Canvas(), MouseEvent.MOUSE_PRESSED, 123, 0, 240, 75, 1, false, MouseEvent.BUTTON1));
			when(client.getMouseLastPressedMillis()).thenReturn(123L);
			plugin.onClientTick(null);
			Point expected = InputRoute.resolve(new Point(800, 500), plugin.getInputFrames()).point;
			clearInvocations(host, inventory);
			MouseEvent drag = listener.mouseDragged(new MouseEvent(new Canvas(), MouseEvent.MOUSE_DRAGGED, 124,
				java.awt.event.InputEvent.BUTTON1_DOWN_MASK, 800, 500, 0, false));
			assertEquals(expected, drag.getPoint());
			plugin.onPostClientTick(null);
			verify(host, never()).setNoClickThrough(false);
			verify(inventory, never()).setNoClickThrough(false);
			listener.mouseReleased(new MouseEvent(new Canvas(), MouseEvent.MOUSE_RELEASED, 125, 0, 800, 500, 1, false, MouseEvent.BUTTON1));
			plugin.onClientTick(null);
			// Closing the bank leaves no invisible rectangle swallowing scene input.
			when(host.getNestedChildren()).thenReturn(new Widget[0]);
			plugin.onBeforeRender(null);
			plugin.captureBackground();
			assertNull(InputRoute.resolve(new Point(240, 75), plugin.getInputFrames()).target);
		}
	}

	@Test
	public void compatibilityKeepsWindowAndInventoryOperationsAvailableAwayFromTheMouse()
	{
		when(config.preservePanelActions()).thenReturn(true);
		when(config.scale()).thenReturn(100);
		when(config.upperTabScale()).thenReturn(100);
		when(config.lowerTabScale()).thenReturn(100);
		when(config.filter()).thenReturn(ScalingFilter.NEAREST);
		for (boolean gpu : new boolean[]{false, true})
		for (int root : new int[]{InterfaceID.TOPLEVEL_PRE_EOC, InterfaceID.TOPLEVEL_OSRS_STRETCH})
		for (WidgetRegion region : new WidgetRegion[]{WidgetRegion.INTERFACE, WidgetRegion.INTERFACE_CRM, WidgetRegion.INTERFACE_FLOAT})
		for (int group : new int[]{InterfaceID.BANKMAIN, InterfaceID.GE_OFFERS, InterfaceID.SHOPMAIN, InterfaceID.TRADEMAIN})
		{
			when(client.isGpu()).thenReturn(gpu);
			when(client.getTopLevelInterfaceId()).thenReturn(root);
			ResizableLayout layout = ResizableLayout.current(client);
			Widget host = widget(region.component(layout), new Rectangle(1000, 700));
			Widget window = widget(group << 16, new Rectangle(300, 120, 300, 240));
			when(window.getType()).thenReturn(net.runelite.api.widgets.WidgetType.RECTANGLE);
			when(host.getNestedChildren()).thenReturn(new Widget[]{window});
			Widget inventory = layout == ResizableLayout.MODERN ? panel : classicPanel;
			Widget item = mock(Widget.class);
			when(inventory.getDynamicChildren()).thenReturn(new Widget[]{item});
			for (Widget operation : new Widget[]{window, item})
			{
				when(operation.getActions()).thenReturn(new String[]{"Use", null, "Examine"});
				when(operation.getClickMask()).thenReturn(1234);
				when(operation.getOnOpListener()).thenReturn(new Object[]{914, "argument"});
			}
			for (int windowScale : new int[]{50, 100, 150})
			for (int panelScale : new int[]{50, 100, 150})
			{
				when(config.interfaceScale()).thenReturn(windowScale);
				when(config.panelScale()).thenReturn(panelScale);
				Graphics2D g = image.createGraphics();
				try
				{
					g.setComposite(AlphaComposite.Clear);
					g.fillRect(0, 0, image.getWidth(), image.getHeight());
					g.setComposite(AlphaComposite.SrcOver);
					plugin.onBeforeRender(null);
					plugin.captureBackground();
					plugin.drawScaled(region, host.getId(), g);
					g.setColor(Color.GREEN);
					g.fill(window.getBounds());
					renderAfterInterface(group, g);
					g.setColor(Color.RED);
					g.fill(inventory.getBounds());
					plugin.drawScaled(WidgetRegion.SIDE_PANEL, inventory.getId(), g);
					plugin.drawOutputs(g);
					plugin.finishRender();
				}
				finally { g.dispose(); }
				if (windowScale != 100) { assertTrue(plugin.getInputFrames().stream().anyMatch(f -> f.region() == region)); }
				if (panelScale != 100) { assertTrue(plugin.getInputFrames().stream().anyMatch(f -> f.region() == WidgetRegion.SIDE_PANEL)); }
				MinimapMouseListener listener = new MinimapMouseListener(client, plugin);
				// Scene, original window, original inventory, and outside the canvas.
				java.util.List<Point> points = new java.util.ArrayList<>(Arrays.asList(
					new Point(100, 50), new Point(400, 200), new Point(900, 500), new Point(-1, -1)));
				for (Rectangle bounds : new Rectangle[]{
					MinimapTransform.anchored(window.getBounds(), windowScale, 1000, 700, false).destination(),
					MinimapTransform.fit(inventory.getBounds(), panelScale, 0, 0, 1000, 700, true).destination()})
				{
					points.add(new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2));
				}
				for (Point point : points)
				{
					plugin.onClientTick(null);
					InputRoute expected = InputRoute.resolve(point, plugin.getInputFrames());
					MouseEvent move = listener.mouseMoved(new MouseEvent(new Canvas(), MouseEvent.MOUSE_MOVED,
						122, 0, point.x, point.y, 0, false));
					assertEquals("Compatibility must not bypass mouse transforms", expected.point, move.getPoint());
					plugin.onPostClientTick(null);
					for (Widget operation : new Widget[]{window, item})
					{
						verify(operation, never()).clearActions();
						verify(operation, never()).setClickMask(anyInt());
						verify(operation, never()).setOnOpListener((Object[]) any());
						assertEquals("Use", operation.getActions()[0]);
						assertEquals(1234, operation.getClickMask());
						assertNotNull(operation.getOnOpListener());
					}
				}
			}
			// Do not leave earlier modal hosts open for the next matrix case.
			when(host.getNestedChildren()).thenReturn(new Widget[0]);
		}
	}

	@Test
	public void compatibilityToggleRestoresAlreadyExcludedOperationsAndCanReturnToNormalGating()
	{
		when(config.panelScale()).thenReturn(150);
		Widget item = mock(Widget.class);
		when(item.getActions()).thenReturn(new String[]{"Use", "Drop"});
		when(item.getClickMask()).thenReturn(1234);
		Object[] onOp = {914};
		when(item.getOnOpListener()).thenReturn(onOp);
		when(panel.getDynamicChildren()).thenReturn(new Widget[]{item});
		render();
		MinimapMouseListener listener = new MinimapMouseListener(client, plugin);
		listener.mouseMoved(new MouseEvent(new Canvas(), MouseEvent.MOUSE_MOVED, 122, 0, 950, 50, 0, false));
		verify(item, atLeastOnce()).clearActions();
		verify(item, atLeastOnce()).setClickMask(0);
		verify(item, atLeastOnce()).setOnOpListener((Object[]) null);
		clearInvocations(item);
		when(config.preservePanelActions()).thenReturn(true);
		plugin.applyInputGate();
		verify(item).setAction(0, "Use");
		verify(item).setAction(1, "Drop");
		verify(item).setClickMask(1234);
		verify(item).setOnOpListener(onOp);
		clearInvocations(item);
		plugin.applyInputGate();
		verify(item, never()).clearActions();
		when(config.preservePanelActions()).thenReturn(false);
		plugin.applyInputGate();
		verify(item).clearActions();
		verify(item).setClickMask(0);
		verify(item).setOnOpListener((Object[]) null);
	}

	@Test
	public void compatibilityDoesNotGateFrameAncestorsThatMayContainInventoryWidgets()
	{
		when(config.preservePanelActions()).thenReturn(true);
		when(config.panelScale()).thenReturn(150);
		Widget item = mock(Widget.class);
		when(item.getActions()).thenReturn(new String[]{"Use", "Drop"});
		when(item.getClickMask()).thenReturn(1234);
		when(upper.getNestedChildren()).thenReturn(new Widget[]{panel});
		when(panel.getDynamicChildren()).thenReturn(new Widget[]{item});
		render();
		clearInvocations(map, upper, lower, panel);
		plugin.recordPointer(new Point(740, 380), WidgetRegion.SIDE_PANEL, MouseEvent.MOUSE_MOVED, 0);
		plugin.applyInputGate();
		verify(map, never()).setNoClickThrough(false);
		verify(upper, never()).setNoClickThrough(false);
		verify(lower, never()).setNoClickThrough(false);
		verify(panel, never()).setNoClickThrough(false);
		verify(item, never()).clearActions();
		verify(item, never()).setClickMask(anyInt());
	}

	@Test
	public void holdingEitherCameraButtonInSceneDoesNotTraverseInventoryEvenWithoutMouseMovement()
	{
		when(config.panelScale()).thenReturn(150);
		when(config.scale()).thenReturn(100);
		when(config.upperTabScale()).thenReturn(100);
		when(config.lowerTabScale()).thenReturn(100);
		when(cameraConfig.rightClickMovesCamera()).thenReturn(true);
		when(cameraConfig.rightClickMenuBlocksCamera()).thenReturn(false);
		plugin.onPluginChanged(new PluginChanged(mock(CameraPlugin.class), true));
		Widget item = mock(Widget.class);
		when(panel.getDynamicChildren()).thenReturn(new Widget[]{item});
		when(classicPanel.getDynamicChildren()).thenReturn(new Widget[]{item});
		when(item.getActions()).thenReturn(new String[]{"Use", "Drop"});
		for (int root : new int[]{InterfaceID.TOPLEVEL_PRE_EOC, InterfaceID.TOPLEVEL_OSRS_STRETCH})
		{
			when(client.getTopLevelInterfaceId()).thenReturn(root);
			for (int button : new int[]{MouseEvent.BUTTON2, MouseEvent.BUTTON3})
			{
				render();
				MinimapMouseListener listener = new MinimapMouseListener(client, plugin);
				listener.mouseMoved(new MouseEvent(new Canvas(), MouseEvent.MOUSE_MOVED, 122, 0, 400, 300, 0, false));
				clearInvocations(panel, classicPanel, item);
				listener.mousePressed(new MouseEvent(new Canvas(), MouseEvent.MOUSE_PRESSED, 123,
					java.awt.event.InputEvent.getMaskForButton(button), 400, 300, 1, false, button));
				when(client.getMouseLastPressedMillis()).thenReturn(123L);
				for (int frame = 0; frame < 20; frame++)
				{
					plugin.onClientTick(null);
					plugin.onPostClientTick(null);
					render();
				}
				for (Widget inventory : new Widget[]{panel, classicPanel})
				{
					verify(inventory, never()).getDynamicChildren();
					verify(inventory, never()).getStaticChildren();
					verify(inventory, never()).getNestedChildren();
					verify(inventory, never()).setNoClickThrough(anyBoolean());
				}
				verifyNoInteractions(item);
				listener.mouseReleased(new MouseEvent(new Canvas(), MouseEvent.MOUSE_RELEASED, 124,
					0, 400, 300, 1, false, button));
			}
		}
	}

	@Test
	public void cameraGateTracksTranslatedNativePointerAndReleasesOutsideOriginalBounds()
	{
		when(config.panelScale()).thenReturn(150);
		when(config.scale()).thenReturn(100);
		when(config.upperTabScale()).thenReturn(100);
		when(config.lowerTabScale()).thenReturn(100);
		render();
		MinimapMouseListener listener = new MinimapMouseListener(client, plugin);
		listener.mouseMoved(new MouseEvent(new Canvas(), MouseEvent.MOUSE_MOVED, 122, 0, 740, 380, 0, false));
		listener.mousePressed(new MouseEvent(new Canvas(), MouseEvent.MOUSE_PRESSED, 123,
			java.awt.event.InputEvent.BUTTON2_DOWN_MASK, 740, 380, 1, false, MouseEvent.BUTTON2));
		verify(panel).setNoClickThrough(false);
		when(client.getMouseLastPressedMillis()).thenReturn(123L);
		plugin.onClientTick(null);
		clearInvocations(panel);
		MouseEvent inside = listener.mouseDragged(new MouseEvent(new Canvas(), MouseEvent.MOUSE_DRAGGED, 124,
			java.awt.event.InputEvent.BUTTON2_DOWN_MASK, 720, 400, 0, false));
		assertEquals(new Point(803, 493), inside.getPoint());
		verify(panel).setNoClickThrough(false);
		// Physical pointer is now inside the original panel; native pointer is below it.
		MouseEvent outside = listener.mouseDragged(new MouseEvent(new Canvas(), MouseEvent.MOUSE_DRAGGED, 125,
			java.awt.event.InputEvent.BUTTON2_DOWN_MASK, 900, 600, 0, false));
		assertEquals(new Point(983, 693), outside.getPoint());
		verify(panel).setNoClickThrough(true);
		clearInvocations(panel);
		for (int frame = 0; frame < 10; frame++)
		{
			plugin.onClientTick(null);
			plugin.onPostClientTick(null);
			render();
		}
		verify(panel, never()).getDynamicChildren();
		verify(panel, never()).setNoClickThrough(anyBoolean());
	}

	@Test
	public void pendingCameraPressKeepsItsNativeLocationGatedAfterPointerMovesAway()
	{
		when(config.panelScale()).thenReturn(150);
		render();
		MinimapMouseListener listener = new MinimapMouseListener(client, plugin);
		listener.mouseMoved(new MouseEvent(new Canvas(), MouseEvent.MOUSE_MOVED, 122, 0, 740, 380, 0, false));
		listener.mousePressed(new MouseEvent(new Canvas(), MouseEvent.MOUSE_PRESSED, 123,
			java.awt.event.InputEvent.BUTTON2_DOWN_MASK, 740, 380, 1, false, MouseEvent.BUTTON2));
		clearInvocations(panel);
		listener.mouseDragged(new MouseEvent(new Canvas(), MouseEvent.MOUSE_DRAGGED, 124,
			java.awt.event.InputEvent.BUTTON2_DOWN_MASK, 400, 300, 0, false));
		verify(panel, never()).setNoClickThrough(true);
		when(client.getMouseLastPressedMillis()).thenReturn(123L);
		plugin.onClientTick(null);
		verify(panel).setNoClickThrough(true);
		clearInvocations(panel);
		plugin.onPostClientTick(null);
		verify(panel, never()).setNoClickThrough(false);
	}

	@Test
	public void missingWidgetAndOutputHooksStillRestoreUnderlayAtRenderEnd()
	{
		when(client.isGpu()).thenReturn(true);
		when(config.panelScale()).thenReturn(150);
		int[] pixels = ((java.awt.image.DataBufferInt) image.getRaster().getDataBuffer()).getData();
		plugin.onBeforeRender(null);
		pixels[500 * 1000 + 850] = 0xffffffff;
		plugin.captureBackground();
		assertEquals(0, pixels[500 * 1000 + 850]);
		// An unscaled interface draws after capture, but the scaled widget is hidden.
		pixels[500 * 1000 + 850] = 0x80404040;
		plugin.finishRender();
		assertEquals(UiAlpha.over(0x80404040, 0xffffffff), pixels[500 * 1000 + 850]);
	}

	@Test
	public void transparentInventoryDoesNotScaleOrDuplicateUnderlyingTextInEitherLayout()
	{
		when(client.isGpu()).thenReturn(true);
		when(config.panelScale()).thenReturn(150);
		when(config.scale()).thenReturn(100);
		when(config.upperTabScale()).thenReturn(100);
		when(config.lowerTabScale()).thenReturn(100);
		int[] pixels = ((java.awt.image.DataBufferInt) image.getRaster().getDataBuffer()).getData();
		int panelColor = 0x6a303030;
		for (int root : new int[]{InterfaceID.TOPLEVEL_PRE_EOC, InterfaceID.TOPLEVEL_OSRS_STRETCH})
		{
			when(client.getTopLevelInterfaceId()).thenReturn(root);
			Widget inventory = root == InterfaceID.TOPLEVEL_PRE_EOC ? panel : classicPanel;
			Rectangle bounds = inventory.getBounds();
			Rectangle destination = MinimapTransform.fit(bounds, 150, 0, 0, 1000, 700, true).destination();
			for (ScalingFilter filter : ScalingFilter.values())
			{
				when(config.filter()).thenReturn(filter);
				Arrays.fill(pixels, 0);
				plugin.onBeforeRender(null);
				// Opaque overhead plus its translucent edge, already present before widgets.
				for (int y = bounds.y + 60; y < bounds.y + 68; y++)
				{
					Arrays.fill(pixels, y * 1000 + bounds.x + 60, y * 1000 + bounds.x + 90, 0xffffffff);
					pixels[y * 1000 + bounds.x + 90] = 0x80606060;
				}
				int[] expected = pixels.clone();
				plugin.captureBackground();
				for (int y = bounds.y; y < bounds.y + bounds.height; y++)
				{
					for (int x = bounds.x; x < bounds.x + bounds.width; x++)
					{
						int index = y * 1000 + x;
						pixels[index] = UiAlpha.over(panelColor, pixels[index]);
					}
				}
				Graphics2D graphics = image.createGraphics();
				try
				{
					plugin.drawScaled(WidgetRegion.SIDE_PANEL, inventory.getId(), graphics);
					plugin.drawOutputs(graphics);
				}
				finally { graphics.dispose(); }
				for (int y = destination.y; y < destination.y + destination.height; y++)
				{
					for (int x = destination.x; x < destination.x + destination.width; x++)
					{
						int index = y * 1000 + x;
						expected[index] = UiAlpha.over(panelColor, expected[index]);
					}
				}
				assertArrayEquals("Only the panel should scale, not the overhead: " + root + " / " + filter, expected, pixels);
				plugin.finishRender();
			}
		}
	}

	@Test
	public void movingOffEnlargedInventoryIntoSceneDoesNotTraverseOrRewriteItsWidgets()
	{
		when(config.panelScale()).thenReturn(150);
		when(config.scale()).thenReturn(100);
		when(config.upperTabScale()).thenReturn(100);
		when(config.lowerTabScale()).thenReturn(100);
		Widget item = mock(Widget.class);
		when(panel.getDynamicChildren()).thenReturn(new Widget[]{item});
		when(item.getActions()).thenReturn(new String[]{"Use", "Drop"});
		render();
		MinimapMouseListener listener = new MinimapMouseListener(client, plugin);
		listener.mouseMoved(new MouseEvent(new Canvas(), MouseEvent.MOUSE_MOVED, 122, 0, 740, 380, 0, false));
		listener.mouseMoved(new MouseEvent(new Canvas(), MouseEvent.MOUSE_MOVED, 123, 0, 400, 300, 0, false));
		clearInvocations(panel, item);
		for (int frame = 0; frame < 20; frame++)
		{
			plugin.onClientTick(null);
			plugin.onPostClientTick(null);
			render();
		}
		verify(panel, never()).getStaticChildren();
		verify(panel, never()).getDynamicChildren();
		verify(panel, never()).getNestedChildren();
		verify(panel, never()).setNoClickThrough(anyBoolean());
		verifyNoInteractions(item);
		assertEquals(WidgetRegion.SIDE_PANEL, InputRoute.resolve(new Point(740, 380), plugin.getInputFrames()).target);
		assertEquals(Color.RED.getRGB(), image.getRGB(740, 380));
	}

	@Test
	public void sceneMotionAcrossOriginalPanelBoundsUpdatesGateEvenWithSameNullTarget()
	{
		when(config.panelScale()).thenReturn(50);
		when(config.scale()).thenReturn(100);
		when(config.upperTabScale()).thenReturn(100);
		when(config.lowerTabScale()).thenReturn(100);
		render();
		MinimapMouseListener listener = new MinimapMouseListener(client, plugin);
		listener.mouseMoved(new MouseEvent(new Canvas(), MouseEvent.MOUSE_MOVED, 122, 0, 400, 300, 0, false));
		clearInvocations(panel);
		assertEquals(new Point(800, 500), listener.mouseMoved(
			new MouseEvent(new Canvas(), MouseEvent.MOUSE_MOVED, 123, 0, 800, 500, 0, false)).getPoint());
		verify(panel).setNoClickThrough(false);
		listener.mouseMoved(new MouseEvent(new Canvas(), MouseEvent.MOUSE_MOVED, 124, 0, 400, 300, 0, false));
		verify(panel).setNoClickThrough(true);
		clearInvocations(panel);
		plugin.applyInputGate();
		verify(panel, never()).setNoClickThrough(anyBoolean());
	}

	@Test
	public void scenePressOnExposedOriginalPanelRemainsGatedUntilNativeInputConsumesIt()
	{
		when(config.panelScale()).thenReturn(50);
		render();
		MinimapMouseListener listener = new MinimapMouseListener(client, plugin);
		listener.mousePressed(new MouseEvent(new Canvas(), MouseEvent.MOUSE_PRESSED, 123, 0, 800, 500, 1, false, MouseEvent.BUTTON1));
		verify(panel).setNoClickThrough(false);
		clearInvocations(panel);
		listener.mouseMoved(new MouseEvent(new Canvas(), MouseEvent.MOUSE_MOVED, 124, 0, 400, 300, 0, false));
		plugin.onPostClientTick(null);
		verify(panel, never()).setNoClickThrough(true);
		when(client.getMouseLastPressedMillis()).thenReturn(123L);
		plugin.onClientTick(null);
		verify(panel).setNoClickThrough(true);
		clearInvocations(panel);
		plugin.onPostClientTick(null);
		verify(panel, never()).setNoClickThrough(false);
	}

	@Test
	public void hqLateIconsAreScaledWithTheirPanelAndPreserveTransparency() throws Exception
	{
		java.lang.reflect.Field bridge = MinimapResizePlugin.class.getDeclaredField("hqIcons");
		bridge.setAccessible(true);
		bridge.set(plugin, new HqIconCompatibility(overlayManager,
			overlay -> overlay instanceof FakeHqIcons.UnderInterfacePainter));
		when(client.isGpu()).thenReturn(true);
		when(config.panelScale()).thenReturn(150);
		when(config.panelLeft()).thenReturn(300);
		when(config.filter()).thenReturn(ScalingFilter.NEAREST);
		FakeHqIcons.UnderInterfacePainter painter = new FakeHqIcons.UnderInterfacePainter();
		overlayManager.add(painter);
		int[] pixels = ((java.awt.image.DataBufferInt) image.getRaster().getDataBuffer()).getData();
		for (int root : new int[]{InterfaceID.TOPLEVEL_OSRS_STRETCH, InterfaceID.TOPLEVEL_PRE_EOC})
		{
			when(client.getTopLevelInterfaceId()).thenReturn(root);
			Widget inventory = root == InterfaceID.TOPLEVEL_OSRS_STRETCH ? classicPanel : panel;
			Rectangle bounds = inventory.getBounds();
			Arrays.fill(pixels, 0);
			plugin.onBeforeRender(null);
			assertEquals(net.runelite.client.ui.overlay.OverlayLayer.MANUAL, painter.getLayer());
			pixels[(bounds.y + 80) * 1000 + bounds.x + 80] = 0xffffffff;
			plugin.captureBackground();
			for (int y = bounds.y; y < bounds.y + bounds.height; y++)
			{
				Arrays.fill(pixels, y * image.getWidth() + bounds.x,
					y * image.getWidth() + bounds.x + bounds.width, 0x6a303030);
			}
			// Model HQ's transparent patch, then its late replacement plus restored panel alpha.
			for (int y = bounds.y + 20; y < bounds.y + 52; y++)
			{
				Arrays.fill(pixels, y * image.getWidth() + bounds.x + 20,
					y * image.getWidth() + bounds.x + 56, 0);
			}
			painter.draw = () ->
			{
				for (int y = bounds.y + 20; y < bounds.y + 52; y++)
				{
					Arrays.fill(pixels, y * image.getWidth() + bounds.x + 20,
						y * image.getWidth() + bounds.x + 56, 0xffabcdef);
				}
			};
			Graphics2D graphics = image.createGraphics();
			try
			{
				plugin.drawScaled(WidgetRegion.SIDE_PANEL, inventory.getId(), graphics);
				// A later region's hook must not overwrite the panel's original backdrop.
				plugin.drawScaled(WidgetRegion.MINIMAP,
					root == InterfaceID.TOPLEVEL_OSRS_STRETCH ? classicMap.getId() : map.getId(), graphics);
				int calls = painter.calls;
				plugin.drawOutputs(graphics);
				assertEquals(calls + 1, painter.calls);
			}
			finally { graphics.dispose(); }
			Rectangle destination = MinimapTransform.fit(bounds, 150, 300, 0, 1000, 700, true).destination();
			assertEquals(0xffabcdef, pixels[(destination.y + 35) * 1000 + destination.x + 35]);
			assertEquals("Icon must grow from 36 to 54 pixels wide", 0xffabcdef,
				pixels[(destination.y + 35) * 1000 + destination.x + 83]);
			assertEquals(0x6a303030, pixels[(destination.y + 35) * 1000 + destination.x + 84]);
			assertEquals(0x6a303030, pixels[(destination.y + 90) * 1000 + destination.x + 90]);
			assertEquals("HQ capture must not enlarge the underlying overhead", 0x6a303030,
				pixels[(destination.y + 120) * 1000 + destination.x + 120]);
			assertEquals("Underlying overhead stays at its scene position", 0xffffffff,
				pixels[(bounds.y + 80) * 1000 + bounds.x + 80]);
			assertEquals("No native-size ghost remains", 0, pixels[(bounds.y + 25) * 1000 + bounds.x + 25]);
			assertEquals(WidgetRegion.SIDE_PANEL,
				InputRoute.resolve(new Point(destination.x + 35, destination.y + 35), plugin.getInputFrames()).target);
		}
		plugin.shutDown();
		assertEquals(net.runelite.client.ui.overlay.OverlayLayer.ALWAYS_ON_TOP, painter.getLayer());
		assertTrue(overlayManager.anyMatch(overlay -> overlay == painter));
	}

	@Test
	public void hdUiDetailsAreDrawnAfterPanelFilteringWithMatchingInputInBothLayouts()
	{
		// This regression isolates the sidebar. Tab strips have their own HD
		// passes now and are exercised independently below.
		when(config.upperTabScale()).thenReturn(100);
		when(config.lowerTabScale()).thenReturn(100);
		for (int group : new int[]{InterfaceID.MAGIC_SPELLBOOK, InterfaceID.PRAYERBOOK, InterfaceID.QUICKPRAYER,
			InterfaceID.EMOTE, InterfaceID.STATS, InterfaceID.COMBAT_INTERFACE, InterfaceID.AUTOCAST,
			InterfaceID.WORNITEMS, InterfaceID.EQUIPMENT_SIDE})
		{
			assertTrue("HD UI must receive a scoped pass for tab " + group, HdUiCompatibility.sideGroups().contains(group));
		}
		com.hdui.ScaledInventoryOverlay painter = new com.hdui.ScaledInventoryOverlay();
		overlayManager.add(painter);
		when(config.panelScale()).thenReturn(200);
		when(config.panelLeft()).thenReturn(300);
		when(config.filter()).thenReturn(ScalingFilter.BILINEAR);
		for (boolean gpu : new boolean[]{false, true})
		{
			when(client.isGpu()).thenReturn(gpu);
			for (int root : new int[]{InterfaceID.TOPLEVEL_OSRS_STRETCH, InterfaceID.TOPLEVEL_PRE_EOC})
			{
				when(client.getTopLevelInterfaceId()).thenReturn(root);
				painter.bounds = root == InterfaceID.TOPLEVEL_OSRS_STRETCH ? classicPanel.getBounds() : panel.getBounds();
				int calls = painter.calls;
				render();
				assertEquals(calls + 1, painter.calls);
				Rectangle destination = MinimapTransform.fit(painter.bounds, 200, 300, 0, 1000, 700, true).destination();
				assertEquals(0xffffffcc, image.getRGB(destination.x + 41, destination.y + 50));
				assertNotEquals("The fine star feature must not become a doubled native pixel", 0xffffffcc,
					image.getRGB(destination.x + 40, destination.y + 50));
				assertEquals(WidgetRegion.SIDE_PANEL,
					InputRoute.resolve(new Point(destination.x + 41, destination.y + 50), plugin.getInputFrames()).target);
			}
		}
		when(config.panelScale()).thenReturn(100);
		when(config.panelLeft()).thenReturn(0);
		render();
		assertEquals(net.runelite.client.ui.overlay.OverlayLayer.ALWAYS_ON_TOP, painter.getLayer());
	}

	@Test public void hdUiTabOnlyScalingUsesBothStripTransformsAndIgnoresInactiveLayoutRoots()
	{
		when(config.scale()).thenReturn(100); when(config.panelScale()).thenReturn(100);
		when(config.upperTabScale()).thenReturn(150); when(config.lowerTabScale()).thenReturn(200);
		when(config.filter()).thenReturn(ScalingFilter.NEAREST);
		com.hdui.ScaledInventoryOverlay painter = new com.hdui.ScaledInventoryOverlay();
		painter.contents = g ->
		{
			Rectangle top = upper.getBounds(), bottom = lower.getBounds();
			g.setColor(Color.BLUE); g.fillRect(top.x + 20, top.y + 10, 2, 2);
			g.setColor(Color.GREEN); g.fillRect(bottom.x + 30, bottom.y + 10, 2, 2);
		};
		overlayManager.add(painter); render();
		assertEquals(net.runelite.client.ui.overlay.OverlayLayer.MANUAL, painter.getLayer());
		assertEquals("Only the two scaled strips need an HD pass", 2, painter.calls);
		Rectangle top = upper.getBounds(), bottom = lower.getBounds();
		Rectangle td = MinimapTransform.fit(top, 150, 0, 0, 1000, 700, true).destination();
		Rectangle bd = MinimapTransform.fit(bottom, 200, 0, 0, 1000, 700, true).destination();
		Point tp = new Point(td.x + (int) (20.5 * td.width / top.width), td.y + (int) (10.5 * td.height / top.height));
		Point bp = new Point(bd.x + (int) (30.5 * bd.width / bottom.width), bd.y + (int) (10.5 * bd.height / bottom.height));
		assertEquals(Color.BLUE.getRGB(), image.getRGB(tp.x, tp.y)); assertEquals(Color.GREEN.getRGB(), image.getRGB(bp.x, bp.y));
		assertEquals(WidgetRegion.UPPER_TABS, InputRoute.resolve(tp, plugin.getInputFrames()).target);
		assertEquals(WidgetRegion.LOWER_TABS, InputRoute.resolve(bp, plugin.getInputFrames()).target);
		when(client.getTopLevelInterfaceId()).thenReturn(InterfaceID.TOPLEVEL_OSRS_STRETCH); render();
		assertEquals("Cached modern widgets must not keep HD strip suppression alive in classic layout", 2, painter.calls);
		assertEquals(net.runelite.client.ui.overlay.OverlayLayer.ALWAYS_ON_TOP, painter.getLayer());
		when(client.getTopLevelInterfaceId()).thenReturn(InterfaceID.TOPLEVEL_PRE_EOC);
		when(config.upperTabScale()).thenReturn(100); when(config.lowerTabScale()).thenReturn(100); render();
		assertEquals(net.runelite.client.ui.overlay.OverlayLayer.ALWAYS_ON_TOP, painter.getLayer());
	}

	@Test
	public void hdFramesKeepUpdatingAtOneFpsAndNativeTransitionsFlushStaleInventoryPixelsImmediately()
	{
		com.hdui.ScaledInventoryOverlay painter = new com.hdui.ScaledInventoryOverlay();
		overlayManager.add(painter);
		when(config.panelScale()).thenReturn(200);
		when(config.panelLeft()).thenReturn(300);
		when(config.filter()).thenReturn(ScalingFilter.NEAREST);
		when(config.scalingFps()).thenReturn(1);
		for (boolean gpu : new boolean[]{false, true})
		{
			when(client.isGpu()).thenReturn(gpu);
			for (int root : new int[]{InterfaceID.TOPLEVEL_OSRS_STRETCH, InterfaceID.TOPLEVEL_PRE_EOC})
			{
				when(client.getTopLevelInterfaceId()).thenReturn(root);
				Widget inventory = root == InterfaceID.TOPLEVEL_OSRS_STRETCH ? classicPanel : panel;
				painter.bounds = inventory.getBounds();
				Rectangle destination = MinimapTransform.fit(painter.bounds, 200, 300, 0, 1000, 700, true).destination();
				painter.colour = 0xffffcc;
				renderHdPanel(inventory, painter, Color.RED, false, false);
				assertEquals(Color.RED.getRGB(), image.getRGB(destination.x + 5, destination.y + 5));
				painter.colour = 0x00ffff;
				renderHdPanel(inventory, painter, Color.BLUE, false, false);
				assertEquals("The ordinary panel must still respect the one-FPS refresh limit", Color.RED.getRGB(),
					image.getRGB(destination.x + 5, destination.y + 5));
				assertEquals("HD animation is composited every frame even while filtering is held", 0xff00ffff,
					image.getRGB(destination.x + 41, destination.y + 50));
				renderHdPanel(inventory, painter, Color.BLUE, true, true);
				assertEquals(Color.BLUE.getRGB(), image.getRGB(destination.x + 5, destination.y + 5));
				assertEquals(Color.MAGENTA.getRGB(), image.getRGB(destination.x + 125, destination.y + 125));
				painter.colour = 0xffffcc;
				renderHdPanel(inventory, painter, Color.BLUE, true, false);
				assertEquals("A drag/drop ownership change must remove the native-size ghost immediately", Color.BLUE.getRGB(),
					image.getRGB(destination.x + 125, destination.y + 125));
				assertEquals(0xffffffcc, image.getRGB(destination.x + 41, destination.y + 50));
				painter.colour = 0x00ff00;
				renderHdPanel(inventory, painter, Color.RED, false, false);
				assertEquals("The transition bypass applies once, not to every subsequent frame", Color.BLUE.getRGB(),
					image.getRGB(destination.x + 5, destination.y + 5));
				assertEquals(0xff00ff00, image.getRGB(destination.x + 41, destination.y + 50));
			}
		}
	}

	private void renderHdPanel(Widget inventory, com.hdui.ScaledInventoryOverlay painter,
		Color background, boolean nativeChanged, boolean nativeIcon)
	{
		Graphics2D graphics = image.createGraphics();
		try
		{
			graphics.setComposite(AlphaComposite.Clear);
			graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
			graphics.setComposite(AlphaComposite.SrcOver);
			plugin.onBeforeRender(null);
			plugin.captureBackground();
			Rectangle bounds = inventory.getBounds();
			graphics.setColor(background);
			graphics.fill(bounds);
			if (nativeIcon)
			{
				graphics.setColor(Color.MAGENTA);
				graphics.fillRect(bounds.x + 60, bounds.y + 60, 8, 8);
			}
			if (nativeChanged)
			{
				plugin.onPluginMessage(new PluginMessage("hd-ui", "native-inventory-changed", Map.of("painter", painter)));
			}
			plugin.drawScaled(WidgetRegion.SIDE_PANEL, inventory.getId(), graphics);
			plugin.drawOutputs(graphics);
			plugin.finishRender();
		}
		finally { graphics.dispose(); }
	}

	@Test
	public void hdBankAndBankSideBothPaintAtNativeAndEnlargedWindowSizes()
	{
		assertHdItemWindowAndSide(InterfaceID.BANKMAIN, InterfaceID.BANKSIDE);
	}

	@Test
	public void hdGrandExchangeAndItsInventoryBothPaintAtNativeAndEnlargedWindowSizes()
	{
		assertHdItemWindowAndSide(InterfaceID.GE_OFFERS, InterfaceID.GE_OFFERS_SIDE);
	}

	@Test
	public void hdTradeOffersAndTradeInventoryUseSeparateNativeAndScaledPasses()
	{
		assertHdItemWindowAndSide(InterfaceID.TRADEMAIN, InterfaceID.TRADESIDE);
	}

	@Test
	public void hdTradeConfirmationKeepsTheTradeInventoryPainter()
	{
		assertHdItemWindowAndSide(InterfaceID.TRADECONFIRM, InterfaceID.TRADESIDE);
	}

	@Test
	public void hdClueRewardWindowAndOrdinaryInventoryUseSeparatePasses()
	{
		assertHdItemWindowAndSide(InterfaceID.TRAIL_REWARDSCREEN, InterfaceID.INVENTORY);
	}

	@Test
	public void hdRaidRewardsUseTheGenericCentralWindowPass()
	{
		assertHdItemWindowAndSide(InterfaceID.TOA_CHESTS, InterfaceID.INVENTORY);
	}

	@Test
	public void hdShopWindowAndShopInventoryUseSeparatePasses()
	{
		assertHdItemWindowAndSide(InterfaceID.SHOPMAIN, InterfaceID.SHOPSIDE);
	}

	@Test
	public void hdCollectionLogAndOrdinaryInventoryUseSeparatePasses()
	{
		assertHdItemWindowAndSide(InterfaceID.COLLECTION, InterfaceID.INVENTORY);
	}

	private void assertHdItemWindowAndSide(int mainGroup, int sideGroup)
	{
		com.hdui.ScaledInventoryOverlay painter = new com.hdui.ScaledInventoryOverlay();
		painter.solidMarker = true;
		overlayManager.add(painter);
		java.util.Set<Integer> capabilities = new java.util.HashSet<>();
		EventBus.Subscriber messages = eventBus.register(PluginMessage.class, message ->
		{
			if (message.getData().get("painter") != painter) { return; }
			Object groups = message.getData().get("groups");
			if (!(groups instanceof java.util.Set)) { return; }
			java.util.Set<?> selected = (java.util.Set<?>) groups;
			if ("scaled-regions".equals(message.getName()))
			{
				capabilities.clear();
				for (Object group : selected) { capabilities.add((Integer) group); }
			}
			if ("render-groups".equals(message.getName()) && !selected.isEmpty())
			{
				painter.bounds = selected.contains(mainGroup) ? new Rectangle(100, 50, 200, 150)
					: client.getTopLevelInterfaceId() == InterfaceID.TOPLEVEL_OSRS_STRETCH ? classicPanel.getBounds() : panel.getBounds();
				painter.colour = selected.contains(mainGroup) ? 0xff00ff : 0xffffcc;
			}
		}, 0);
		try
		{
			for (boolean gpu : new boolean[]{false, true})
			{
				for (int root : new int[]{InterfaceID.TOPLEVEL_OSRS_STRETCH, InterfaceID.TOPLEVEL_PRE_EOC})
				{
					when(client.getTopLevelInterfaceId()).thenReturn(root);
					Widget window = prepareCentralWindow(mainGroup, new Rectangle(100, 50, 200, 150));
					when(client.isGpu()).thenReturn(gpu);
					when(config.panelLeft()).thenReturn(0);
					when(config.scalingFps()).thenReturn(1);
					Widget inventory = root == InterfaceID.TOPLEVEL_OSRS_STRETCH ? classicPanel : panel;
					Widget host = client.getWidget(WidgetRegion.INTERFACE.component(ResizableLayout.current(client)));
					for (int panelScale : new int[]{100, 200})
					{
						when(config.panelScale()).thenReturn(panelScale);
						for (int scale : new int[]{100, 150, 200})
						{
							when(config.interfaceScale()).thenReturn(scale);
							int calls = painter.calls;
							Graphics2D graphics = image.createGraphics();
							graphics.setComposite(AlphaComposite.Clear); graphics.fillRect(0, 0, 1000, 700);
							graphics.setComposite(AlphaComposite.SrcOver);
							plugin.onBeforeRender(null); plugin.captureBackground();
							graphics.setColor(Color.RED); graphics.fill(inventory.getBounds());
							plugin.drawScaled(WidgetRegion.SIDE_PANEL, inventory.getId(), graphics);
							plugin.drawScaled(WidgetRegion.INTERFACE, host.getId(), graphics);
							graphics.setColor(Color.BLUE); graphics.fill(window.getBounds());
							renderAfterInterface(mainGroup, graphics);
							plugin.drawOutputs(graphics); plugin.finishRender(); graphics.dispose();
							assertEquals("Both interface " + mainGroup + " and its inventory must receive an HD pass at scale " + scale, calls + 2, painter.calls);
							assertTrue(capabilities.contains(mainGroup));
							assertTrue(capabilities.contains(sideGroup));
							int centralDetail = 0, sideDetail = 0;
							for (int pixel : image.getRGB(0, 0, 1000, 700, null, 0, 1000))
							{
								if (pixel == 0xffff00ff) { centralDetail++; }
								if (pixel == 0xffffffcc) { sideDetail++; }
							}
							assertTrue("Central item detail must be visible", centralDetail > 0);
							assertTrue("Side-panel detail must be visible", sideDetail > 0);
						}
					}
				}
			}
		}
		finally { eventBus.unregister(messages); }
	}

	@Test
	public void translucentInventoryInteriorAndBorderKeepNativeOpacityInBothLayouts()
	{
		when(client.isGpu()).thenReturn(true);
		when(config.panelScale()).thenReturn(150);
		when(config.scale()).thenReturn(100);
		when(config.upperTabScale()).thenReturn(100);
		when(config.lowerTabScale()).thenReturn(100);
		int translucent = 0x6a303030;
		int border = 0xffa08060;
		for (int root : new int[]{InterfaceID.TOPLEVEL_OSRS_STRETCH, InterfaceID.TOPLEVEL_PRE_EOC})
		{
			when(client.getTopLevelInterfaceId()).thenReturn(root);
			Widget inventory = root == InterfaceID.TOPLEVEL_OSRS_STRETCH ? classicPanel : panel;
			Rectangle bounds = inventory.getBounds();
			MinimapTransform transform = MinimapTransform.fit(bounds, 150, 0, 0, image.getWidth(), image.getHeight(), true);
			int[] pixels = ((java.awt.image.DataBufferInt) image.getRaster().getDataBuffer()).getData();
			for (ScalingFilter filter : ScalingFilter.values())
			{
				when(config.filter()).thenReturn(filter);
				when(config.sharpening()).thenReturn(40);
				java.util.Arrays.fill(pixels, 0);
				plugin.onBeforeRender(null);
				plugin.captureBackground();
				for (int y = bounds.y; y < bounds.y + bounds.height; y++)
				{
					for (int x = bounds.x; x < bounds.x + bounds.width; x++)
					{
						boolean edge = x < bounds.x + 8 || y < bounds.y + 8
							|| x >= bounds.x + bounds.width - 8 || y >= bounds.y + bounds.height - 8;
						pixels[y * image.getWidth() + x] = edge ? border : translucent;
					}
				}
				Graphics2D graphics = image.createGraphics();
				try
				{
					plugin.drawScaled(WidgetRegion.SIDE_PANEL, inventory.getId(), graphics);
					plugin.drawOutputs(graphics);
				}
				finally { graphics.dispose(); }
				Rectangle destination = transform.destination();
				assertEquals("Interior must not darken or become opaque: " + root + " / " + filter,
					translucent, pixels[(destination.y + destination.height / 2) * image.getWidth() + destination.x + destination.width / 2]);
				assertEquals("Opaque frame color must not change: " + root + " / " + filter,
					border, pixels[(destination.y + 3) * image.getWidth() + destination.x + 3]);
			}
		}
	}

	@Test
	public void middleCameraDragCrossesScaledInventoryWithoutChangingMouseDelta()
	{
		when(config.panelScale()).thenReturn(150);
		render();
		MinimapMouseListener listener = new MinimapMouseListener(client, plugin);
		listener.mouseMoved(new MouseEvent(new Canvas(), MouseEvent.MOUSE_MOVED, 122, 0, 400, 300, 0, false));
		listener.mousePressed(new MouseEvent(new Canvas(), MouseEvent.MOUSE_PRESSED, 123, java.awt.event.InputEvent.BUTTON2_DOWN_MASK,
			400, 300, 1, false, MouseEvent.BUTTON2));
		clearInvocations(panel);
		MouseEvent drag = new MouseEvent(new Canvas(), MouseEvent.MOUSE_DRAGGED, 124, java.awt.event.InputEvent.BUTTON2_DOWN_MASK,
			740, 380, 0, false);
		assertEquals(new Point(740, 380), listener.mouseDragged(drag).getPoint());
		plugin.onPostClientTick(null);
		verify(panel, never()).setNoClickThrough(true);
		listener.mouseReleased(new MouseEvent(new Canvas(), MouseEvent.MOUSE_RELEASED, 125, 0, 740, 380, 1, false, MouseEvent.BUTTON2));
		MouseEvent hover = new MouseEvent(new Canvas(), MouseEvent.MOUSE_MOVED, 126, 0, 740, 380, 0, false);
		assertEquals(new Point(823, 473), listener.mouseMoved(hover).getPoint());
	}

	@Test
	public void rightCameraPressRetainsWidgetMenusAndKeepsMenuMotionContinuous()
	{
		when(config.panelScale()).thenReturn(150);
		when(cameraConfig.rightClickMovesCamera()).thenReturn(true);
		when(cameraConfig.rightClickMenuBlocksCamera()).thenReturn(false);
		plugin.onPluginChanged(new PluginChanged(mock(CameraPlugin.class), true));
		render();
		Menu menu = mock(Menu.class);
		when(client.getMenu()).thenReturn(menu);
		when(menu.getMenuX()).thenReturn(753);
		when(menu.getMenuY()).thenReturn(473);
		when(menu.getMenuWidth()).thenReturn(140);
		when(menu.getMenuHeight()).thenReturn(180);
		MinimapMouseListener listener = new MinimapMouseListener(client, plugin);
		listener.mouseMoved(new MouseEvent(new Canvas(), MouseEvent.MOUSE_MOVED, 122, 0, 740, 380, 0, false));
		clearInvocations(panel);
		MouseEvent press = new MouseEvent(new Canvas(), MouseEvent.MOUSE_PRESSED, 123,
			java.awt.event.InputEvent.BUTTON3_DOWN_MASK, 740, 380, 1, true, MouseEvent.BUTTON3);
		assertEquals(new Point(823, 473), listener.mousePressed(press).getPoint());
		verify(panel, never()).setNoClickThrough(false);
		when(client.isMenuOpen()).thenReturn(true);
		plugin.onMenuOpened(null);
		MouseEvent drag = new MouseEvent(new Canvas(), MouseEvent.MOUSE_DRAGGED, 124,
			java.awt.event.InputEvent.BUTTON3_DOWN_MASK, 750, 400, 0, false);
		assertEquals(new Point(833, 493), listener.mouseDragged(drag).getPoint());
		assertEquals(new Point(833, 493), plugin.translateMenuPoint(new Point(750, 400)));
	}

	@Test
	public void cameraSettingsRespectNativeToggleAndCameraPluginActivity()
	{
		MouseEvent middle = new MouseEvent(new Canvas(), MouseEvent.MOUSE_PRESSED, 123, 0, 400, 300, 1, false, MouseEvent.BUTTON2);
		MouseEvent right = new MouseEvent(new Canvas(), MouseEvent.MOUSE_PRESSED, 123, 0, 400, 300, 1, false, MouseEvent.BUTTON3);
		assertTrue(plugin.isCameraPress(middle));
		assertFalse(plugin.isCameraPress(right));
		when(client.getVarbitValue(VarbitID.MOUSECAM_DISABLED)).thenReturn(1);
		plugin.onPostClientTick(null);
		assertFalse(plugin.isCameraPress(middle));
		when(cameraConfig.rightClickMovesCamera()).thenReturn(true);
		when(cameraConfig.rightClickMenuBlocksCamera()).thenReturn(false);
		CameraPlugin cameraPlugin = mock(CameraPlugin.class);
		plugin.onPluginChanged(new PluginChanged(cameraPlugin, true));
		assertTrue(plugin.isCameraPress(right));
		assertTrue(plugin.isCameraPress(middle));
		when(cameraConfig.rightClickMenuBlocksCamera()).thenReturn(true);
		plugin.onPostClientTick(null);
		assertFalse(plugin.isCameraPress(right));
		when(client.getVarbitValue(VarbitID.MOUSECAM_DISABLED)).thenReturn(0);
		plugin.onPostClientTick(null);
		MouseEvent altLeft = new MouseEvent(new Canvas(), MouseEvent.MOUSE_PRESSED, 123,
			java.awt.event.InputEvent.ALT_DOWN_MASK, 400, 300, 1, false, MouseEvent.BUTTON1);
		assertTrue(plugin.isCameraPress(altLeft));
		when(cameraConfig.rightClickMenuBlocksCamera()).thenReturn(false);
		plugin.onPluginChanged(new PluginChanged(cameraPlugin, false));
		assertFalse("Saved right-camera settings must not apply with Camera disabled", plugin.isCameraPress(right));
	}

	@Test
	public void lateCameraPluginEnablementKeepsButtonRemappersBeforeOurListener()
	{
		clearInvocations(mouseManager);
		plugin.onPluginChanged(new PluginChanged(mock(CameraPlugin.class), true));
		org.mockito.InOrder order = inOrder(mouseManager);
		order.verify(mouseManager).unregisterMouseListener(any(MinimapMouseListener.class));
		order.verify(mouseManager).registerMouseListener(any(MinimapMouseListener.class));
		order.verify(mouseManager).unregisterMouseWheelListener(any(MinimapMouseListener.class));
		order.verify(mouseManager).registerMouseWheelListener(any(MinimapMouseListener.class));
	}

	@Test
	public void inventoryRightClickAndMenuMovementUseTheSameNativeMenuBounds()
	{
		when(config.panelScale()).thenReturn(150);
		render();
		Menu menu = mock(Menu.class);
		when(client.getMenu()).thenReturn(menu);
		when(menu.getMenuX()).thenReturn(753);
		when(menu.getMenuY()).thenReturn(473);
		when(menu.getMenuWidth()).thenReturn(140);
		when(menu.getMenuHeight()).thenReturn(180);
		MinimapMouseListener listener = new MinimapMouseListener(client, plugin);
		MouseEvent press = new MouseEvent(new Canvas(), MouseEvent.MOUSE_PRESSED, 123, 0,
			740, 380, 1, true, MouseEvent.BUTTON3);
		assertEquals(new Point(823, 473), listener.mousePressed(press).getPoint());
		when(client.isMenuOpen()).thenReturn(true);
		plugin.onMenuOpened(null);
		MouseEvent move = new MouseEvent(new Canvas(), MouseEvent.MOUSE_MOVED, 124, 0, 750, 400, 0, false);
		Point menuPoint = listener.mouseMoved(move).getPoint();
		assertEquals(new Point(833, 493), menuPoint);
		assertTrue(new Rectangle(753, 473, 140, 180).contains(menuPoint));
		MouseEvent select = new MouseEvent(new Canvas(), MouseEvent.MOUSE_PRESSED, 125, 0,
			750, 400, 1, false, MouseEvent.BUTTON1);
		assertEquals(menuPoint, listener.mousePressed(select).getPoint());
		when(client.isMenuOpen()).thenReturn(false);
		MouseEvent scene = new MouseEvent(new Canvas(), MouseEvent.MOUSE_MOVED, 126, 0, 400, 300, 0, false);
		assertEquals(new Point(400, 300), listener.mouseMoved(scene).getPoint());
	}

	@Test
	public void refreshLimitKeepsHitMaskAlignedToCachedVisibleImageThenAppliesDeferredChange()
	{
		when(config.scalingFps()).thenReturn(1);
		render();
		assertEquals(WidgetRegion.MINIMAP, InputRoute.resolve(new Point(900, 40), plugin.getInputFrames()).target);
		render(false, WidgetRegion.values());
		assertEquals("The cached map remains visible until the refresh limit permits updating", Color.RED.getRGB(), image.getRGB(900, 40));
		assertEquals(WidgetRegion.MINIMAP, InputRoute.resolve(new Point(900, 40), plugin.getInputFrames()).target);
		when(config.scalingFps()).thenReturn(0);
		render(false, WidgetRegion.values());
		assertEquals(0, image.getRGB(900, 40));
		assertNull(InputRoute.resolve(new Point(900, 40), plugin.getInputFrames()).target);
	}

	@Test
	public void layerRefreshPreservesAnUnscaledPanelDrawnBeforeTheMinimap()
	{
		Rectangle mapBounds = map.getBounds();
		when(panel.getBounds()).thenReturn(mapBounds);
		plugin.onBeforeRender(null);
		plugin.captureBackground();
		Graphics2D graphics = image.createGraphics();
		try
		{
			graphics.setColor(Color.BLUE);
			graphics.fill(panel.getBounds());
			plugin.drawScaled(WidgetRegion.SIDE_PANEL, panel.getId(), graphics);
			graphics.setColor(Color.RED);
			graphics.fillRect(900, 100, 8, 8);
			plugin.drawScaled(WidgetRegion.MINIMAP, map.getId(), graphics);
			plugin.drawOutputs(graphics);
		}
		finally
		{
			graphics.dispose();
		}
		assertEquals("Removing the original minimap must restore the current underlying panel", Color.BLUE.getRGB(), image.getRGB(900, 100));
		assertEquals(Color.RED.getRGB(), image.getRGB(950, 50));
	}

	@Test
	public void enlargedInventoryRoutesItemsWithoutChangingTabSize()
	{
		when(config.panelScale()).thenReturn(150);
		render();
		InputRoute item = InputRoute.resolve(new Point(740, 380), plugin.getInputFrames());
		assertEquals(WidgetRegion.SIDE_PANEL, item.target);
		assertEquals(new Point(823, 473), item.point);
		assertEquals(Color.RED.getRGB(), image.getRGB(740, 380));
		InputRoute overlap = InputRoute.resolve(new Point(900, 430), plugin.getInputFrames());
		assertEquals("Open panel should be on top of tabs it covers", WidgetRegion.SIDE_PANEL, overlap.target);
		assertEquals(4, plugin.getInputFrames().size());
	}

	@Test
	public void shrunkInventoryHasClickThroughAtOldLocations()
	{
		when(config.panelScale()).thenReturn(50);
		render();
		MinimapMouseListener listener = new MinimapMouseListener(client, plugin);
		for (Point oldLocation : Arrays.asList(new Point(600, 500), new Point(800, 500)))
		{
			MouseEvent click = new MouseEvent(new Canvas(), MouseEvent.MOUSE_PRESSED, 123, 0,
				oldLocation.x, oldLocation.y, 1, false, MouseEvent.BUTTON1);
			assertEquals(oldLocation, listener.mousePressed(click).getPoint());
			assertFalse(click.isConsumed());
			assertEquals(0, image.getRGB(oldLocation.x, oldLocation.y));
		}
		verify(panel, atLeastOnce()).setNoClickThrough(false);
		InputRoute itemClick = InputRoute.resolve(new Point(950, 600), plugin.getInputFrames());
		assertEquals(WidgetRegion.SIDE_PANEL, itemClick.target);
		assertEquals(new Point(910, 540), itemClick.point);
	}

	@Test
	public void classicPanelUsesTheCorrectContainerAndAlternateHookOrder()
	{
		when(config.panelScale()).thenReturn(150);
		when(client.getTopLevelInterfaceId()).thenReturn(InterfaceID.TOPLEVEL_OSRS_STRETCH);
		render(WidgetRegion.SIDE_PANEL, WidgetRegion.MINIMAP);
		assertEquals(2, plugin.getInputFrames().size());
		assertEquals(WidgetRegion.SIDE_PANEL, InputRoute.resolve(new Point(740, 380), plugin.getInputFrames()).target);
		assertEquals(Color.BLUE.getRGB(), image.getRGB(670, 280));
		assertEquals(new Point(826, 486), InputRoute.resolve(new Point(740, 380), plugin.getInputFrames()).point);
		verify(panel, never()).setNoClickThrough(false);
		verify(lower, never()).setNoClickThrough(false);
		verify(upper, never()).setNoClickThrough(false);
	}

	@Test
	public void closingSidePanelClearsItsFrameAndRestoresInputProperties()
	{
		when(config.panelScale()).thenReturn(150);
		plugin.recordPointer(new Point(900, 40), WidgetRegion.MINIMAP, MouseEvent.MOUSE_MOVED, 0);
		render();
		verify(panel, atLeastOnce()).setNoClickThrough(false);
		when(panel.isHidden()).thenReturn(true);
		render();
		assertNull(InputRoute.resolve(new Point(740, 380), plugin.getInputFrames()).target);
		verify(panel, atLeastOnce()).setNoClickThrough(true);
	}

	@Test
	public void draggingInventoryOutsideItsBoundsKeepsNativeInputUntilReleaseIsProcessed()
	{
		when(config.panelScale()).thenReturn(150);
		render();
		MinimapMouseListener listener = new MinimapMouseListener(client, plugin);
		listener.mousePressed(new MouseEvent(new Canvas(), MouseEvent.MOUSE_PRESSED, 123, 0, 740, 380, 1, false, MouseEvent.BUTTON1));
		when(client.getMouseLastPressedMillis()).thenReturn(123L);
		plugin.onClientTick(null);
		clearInvocations(panel);
		listener.mouseDragged(new MouseEvent(new Canvas(), MouseEvent.MOUSE_DRAGGED, 124, 0, 640, 300, 0, false));
		plugin.onPostClientTick(null);
		verify(panel, never()).setNoClickThrough(false);
		listener.mouseReleased(new MouseEvent(new Canvas(), MouseEvent.MOUSE_RELEASED, 125, 0, 640, 300, 1, false, MouseEvent.BUTTON1));
		plugin.onClientTick(null);
		plugin.onPostClientTick(null);
		verify(panel, never()).setNoClickThrough(false);
	}

	@Test
	public void minimapFilterIsIndependentOfInventoryFilterAndChangesImmediatelyInBothLayouts()
	{
		when(config.scale()).thenReturn(125);
		when(config.panelScale()).thenReturn(150);
		when(config.upperTabScale()).thenReturn(100);
		when(config.lowerTabScale()).thenReturn(100);
		when(config.scalingFps()).thenReturn(1);
		when(config.sharpening()).thenReturn(40);
		for (int root : new int[]{InterfaceID.TOPLEVEL_PRE_EOC, InterfaceID.TOPLEVEL_OSRS_STRETCH})
		{
			when(client.getTopLevelInterfaceId()).thenReturn(root);
			for (boolean gpu : new boolean[]{false, true})
			{
				when(client.isGpu()).thenReturn(gpu);
				for (ScalingFilter mapFilter : new ScalingFilter[]{ScalingFilter.BILINEAR, ScalingFilter.NEAREST, ScalingFilter.HYBRID})
				{
					when(config.minimapFilter()).thenReturn(mapFilter);
					for (ScalingFilter panelFilter : new ScalingFilter[]{ScalingFilter.XBR, ScalingFilter.LANCZOS})
					{
						when(config.filter()).thenReturn(panelFilter);
						BufferedImage expected = new BufferedImage(1000, 700, BufferedImage.TYPE_INT_ARGB);
						Graphics2D graphics = image.createGraphics(), expectedGraphics = expected.createGraphics();
						try
						{
							graphics.setComposite(AlphaComposite.Clear);
							graphics.fillRect(0, 0, 1000, 700);
							graphics.setComposite(AlphaComposite.SrcOver);
							plugin.onBeforeRender(null);
							plugin.captureBackground();
							for (WidgetRegion region : new WidgetRegion[]{WidgetRegion.MINIMAP, WidgetRegion.SIDE_PANEL})
							{
								Widget widget = (root == InterfaceID.TOPLEVEL_PRE_EOC ? widgets : classicWidgets).get(region);
								Rectangle source = widget.getBounds();
								BufferedImage texture = new BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB_PRE);
								for (int y = 2; y < source.height - 2; y++)
								{
									for (int x = 2; x < source.width - 2; x++) { texture.setRGB(x, y, 0xff000000 | ((x * 17 + y * 3) & 255) * 0x010101); }
								}
								graphics.drawImage(texture, source.x, source.y, null);
								plugin.drawScaled(region, widget.getId(), graphics);
								MinimapTransform transform = MinimapTransform.fit(source, region == WidgetRegion.MINIMAP ? 125 : 150,
									0, 0, 1000, 700, region != WidgetRegion.MINIMAP);
								Rectangle destination = transform.destination();
								BufferedImage filtered = new WidgetImageScaler().scale(texture, destination.width, destination.height,
									region == WidgetRegion.MINIMAP ? mapFilter : panelFilter, 40);
								UiBlitter.draw(expected, filtered, destination.x, destination.y, expectedGraphics, gpu);
							}
							plugin.drawOutputs(graphics);
							plugin.finishRender();
							assertArrayEquals(root + "/" + gpu + "/" + mapFilter + "/" + panelFilter,
								expected.getRGB(0, 0, 1000, 700, null, 0, 1000), image.getRGB(0, 0, 1000, 700, null, 0, 1000));
							InputRoute route = InputRoute.resolve(new Point(900, 40), plugin.getInputFrames());
							assertEquals(WidgetRegion.MINIMAP, route.target);
							assertEquals(MinimapTransform.fit(map.getBounds(), 125, 0, 0, 1000, 700).toSource(new Point(900, 40)), route.point);
						}
						finally
						{
							graphics.dispose();
							expectedGraphics.dispose();
						}
					}
				}
			}
		}
	}

	@Test
	public void everyFilterPreservesTabRoutingAndClickThroughAtOldLocations()
	{
		when(config.sharpening()).thenReturn(40);
		for (ScalingFilter filter : ScalingFilter.values())
		{
			when(config.filter()).thenReturn(filter);
			render();
			InputRoute click = InputRoute.resolve(new Point(900, 430), plugin.getInputFrames());
			assertEquals(WidgetRegion.UPPER_TABS, click.target);
			assertEquals(new Point(800, 420), click.point);
			InputRoute old = InputRoute.resolve(new Point(800, 420), plugin.getInputFrames());
			assertNull(old.target);
			assertEquals(new Point(800, 420), old.point);
			assertEquals(0, image.getRGB(800, 420));
		}
	}

	@Test
	public void shrinkingAllThreeRegionsLeavesOriginalAreaClickableAndClearsNativeBlockers()
	{
		render();
		assertEquals(3, plugin.getInputFrames().size());
		MinimapMouseListener listener = new MinimapMouseListener(client, plugin);
		for (Point oldLocation : Arrays.asList(new Point(800, 120), new Point(800, 420), new Point(800, 680)))
		{
			MouseEvent click = new MouseEvent(new Canvas(), MouseEvent.MOUSE_PRESSED, 123, 0,
				oldLocation.x, oldLocation.y, 1, false, MouseEvent.BUTTON1);
			assertEquals(oldLocation, listener.mousePressed(click).getPoint());
			assertFalse(click.isConsumed());
			assertEquals(0, image.getRGB(oldLocation.x, oldLocation.y));
		}
		verify(map, atLeastOnce()).setNoClickThrough(false);
		verify(upper, atLeastOnce()).setNoClickThrough(false);
		verify(lower, atLeastOnce()).setNoClickThrough(false);
		assertEquals(Color.RED.getRGB(), image.getRGB(900, 40));
		assertEquals(Color.RED.getRGB(), image.getRGB(900, 430));
		assertEquals(Color.RED.getRGB(), image.getRGB(900, 690));
	}

	@Test
	public void resizedButtonTargetKeepsNativeOperationsWhileOtherRegionsPassThrough()
	{
		render();
		clearInvocations(map, upper, lower);
		MinimapMouseListener listener = new MinimapMouseListener(client, plugin);
		MouseEvent click = new MouseEvent(new Canvas(), MouseEvent.MOUSE_PRESSED, 123, 0,
			900, 430, 1, false, MouseEvent.BUTTON1);
		assertEquals(new Point(800, 420), listener.mousePressed(click).getPoint());
		verify(upper, never()).setNoClickThrough(false);
		verify(lower).setNoClickThrough(false);
		verify(map).setNoClickThrough(false);
		// A quick move away must not disable the button before the pending click is processed.
		clearInvocations(upper);
		listener.mouseMoved(new MouseEvent(new Canvas(), MouseEvent.MOUSE_MOVED, 124, 0, 400, 400, 0, false));
		verify(upper, never()).setNoClickThrough(false);
		when(client.getMouseLastPressedMillis()).thenReturn(123L);
		plugin.onClientTick(null);
		plugin.onPostClientTick(null);
		verify(upper, never()).setNoClickThrough(false);
	}

	@Test
	public void inputPropertiesAreRestoredBeforeRenderingScriptsAndShutdown()
	{
		// Menus retain conservative exclusion of all scaled regions.
		when(client.isMenuOpen()).thenReturn(true);
		render();
		clearInvocations(map, upper, lower);
		plugin.onClientTick(null);
		verify(map).setNoClickThrough(true);
		verify(upper).setNoClickThrough(true);
		verify(lower).setNoClickThrough(true);
		plugin.onPostClientTick(null);
		clearInvocations(map);
		plugin.onBeforeRender(null);
		verify(map).setNoClickThrough(true);
		plugin.finishRender();
		clearInvocations(map);
		plugin.shutDown();
		verify(map).setNoClickThrough(true);
		assertTrue(plugin.getInputFrames().isEmpty());
	}

	@Test
	public void classicLayoutDoesNotScaleOrGateTheModernTabBars()
	{
		when(client.getTopLevelInterfaceId()).thenReturn(InterfaceID.TOPLEVEL_OSRS_STRETCH);
		render();
		assertEquals(1, plugin.getInputFrames().size());
		assertEquals(WidgetRegion.MINIMAP, plugin.getInputFrames().get(0).region());
		verify(upper, never()).setNoClickThrough(false);
		verify(lower, never()).setNoClickThrough(false);
		verify(client, never()).getWidget(InterfaceID.ToplevelPreEoc.SIDE_STATIC_LAYER);
		verify(client, never()).getWidget(InterfaceID.ToplevelPreEoc.SIDE_MOVABLE_LAYER);
	}

	@Test
	public void modernLayoutNeverUsesCachedClassicWidgets()
	{
		when(config.panelScale()).thenReturn(150);
		render();
		assertEquals(Color.RED.getRGB(), image.getRGB(740, 380));
		assertEquals(new Point(823, 473), InputRoute.resolve(new Point(740, 380), plugin.getInputFrames()).point);
		verify(classicPanel, never()).setNoClickThrough(false);
		verify(classicMap, never()).setNoClickThrough(false);
		verify(client, never()).getWidget(InterfaceID.ToplevelOsrsStretch.SIDE_MENU);
	}

	@Test
	public void modernToClassicSwitchDropsOldMappingsBeforeRenderAndRestoresOldFlags()
	{
		when(config.panelScale()).thenReturn(150);
		plugin.recordPointer(new Point(900, 40), WidgetRegion.MINIMAP, MouseEvent.MOUSE_MOVED, 0);
		render();
		verify(panel, atLeastOnce()).setNoClickThrough(false);
		when(client.getTopLevelInterfaceId()).thenReturn(InterfaceID.TOPLEVEL_OSRS_STRETCH);
		assertTrue("AWT must not use modern mappings while the active root is classic", plugin.getInputFrames().isEmpty());
		render();
		verify(panel, atLeastOnce()).setNoClickThrough(true);
		assertEquals(2, plugin.getInputFrames().size());
		assertEquals(Color.BLUE.getRGB(), image.getRGB(740, 380));
		assertEquals(new Point(826, 486), InputRoute.resolve(new Point(740, 380), plugin.getInputFrames()).point);
		assertEquals(0, image.getRGB(600, 300));
	}

	@Test
	public void classicToModernSwitchRestoresClassicFlagsAndReenablesModernBars()
	{
		when(config.panelScale()).thenReturn(150);
		plugin.recordPointer(new Point(900, 40), WidgetRegion.MINIMAP, MouseEvent.MOUSE_MOVED, 0);
		when(client.getTopLevelInterfaceId()).thenReturn(InterfaceID.TOPLEVEL_OSRS_STRETCH);
		render();
		verify(classicPanel, atLeastOnce()).setNoClickThrough(false);
		when(client.getTopLevelInterfaceId()).thenReturn(InterfaceID.TOPLEVEL_PRE_EOC);
		assertTrue(plugin.getInputFrames().isEmpty());
		render();
		verify(classicPanel, atLeastOnce()).setNoClickThrough(true);
		assertEquals(4, plugin.getInputFrames().size());
		assertEquals(Color.RED.getRGB(), image.getRGB(740, 380));
		assertEquals(new Point(823, 473), InputRoute.resolve(new Point(740, 380), plugin.getInputFrames()).point);
		assertEquals(0, image.getRGB(650, 270));
	}

	@Test
	public void inactiveModernLayerHookCannotConsumeTheClassicPanelCapture()
	{
		when(config.panelScale()).thenReturn(150);
		when(client.getTopLevelInterfaceId()).thenReturn(InterfaceID.TOPLEVEL_OSRS_STRETCH);
		plugin.onBeforeRender(null);
		plugin.captureBackground();
		Graphics2D graphics = image.createGraphics();
		try
		{
			plugin.drawScaled(WidgetRegion.SIDE_PANEL, InterfaceID.ToplevelPreEoc.SIDE_CONTAINER, graphics);
			graphics.setColor(Color.BLUE);
			graphics.fill(classicPanel.getBounds());
			plugin.drawScaled(WidgetRegion.SIDE_PANEL, classicPanel.getId(), graphics);
			plugin.drawOutputs(graphics);
		}
		finally { graphics.dispose(); }
		assertEquals(1, plugin.getInputFrames().size());
		assertEquals(Color.BLUE.getRGB(), image.getRGB(670, 280));
	}

	@Test
	public void unsupportedOrFixedRootBypassesAllCachedResizableWidgets()
	{
		plugin.recordPointer(new Point(900, 40), WidgetRegion.MINIMAP, MouseEvent.MOUSE_MOVED, 0);
		render();
		when(client.getTopLevelInterfaceId()).thenReturn(InterfaceID.TOPLEVEL_DISPLAY);
		assertTrue(plugin.getInputFrames().isEmpty());
		render();
		assertTrue(plugin.getInputFrames().isEmpty());
		verify(upper, atLeastOnce()).setNoClickThrough(true);
		when(client.getTopLevelInterfaceId()).thenReturn(InterfaceID.TOPLEVEL_PRE_EOC);
		when(client.isResized()).thenReturn(false);
		render();
		assertTrue(plugin.getInputFrames().isEmpty());
	}

	@Test
	public void tabBarsCanScaleWhileTheMinimapRemainsAtNormalSize()
	{
		when(config.scale()).thenReturn(100);
		render();
		assertEquals(2, plugin.getInputFrames().size());
		assertEquals(WidgetRegion.LOWER_TABS, plugin.getInputFrames().get(0).region());
		assertEquals(WidgetRegion.UPPER_TABS, plugin.getInputFrames().get(1).region());
		verify(map, never()).setNoClickThrough(false);
		assertEquals(Color.RED.getRGB(), image.getRGB(800, 120));
	}

	@Test
	public void unscaledWidgetsSkipCompatibilityTraversalAndMouseGateTasksWithOtherSizesActive()
	{
		when(client.isGpu()).thenReturn(true);
		when(config.scale()).thenReturn(100);
		when(config.upperTabScale()).thenReturn(100);
		when(config.lowerTabScale()).thenReturn(100);
		when(config.chatScale()).thenReturn(100);
		when(config.interfaceScale()).thenReturn(100);
		when(config.healthBarScale()).thenReturn(140);
		when(config.hoverScale()).thenReturn(130);
		when(config.menuScale()).thenReturn(130);
		Widget host = widget(WidgetRegion.INTERFACE.component(ResizableLayout.MODERN), new Rectangle(1000, 700));
		clearInvocations(host, clientThread);
		render();
		assertTrue(plugin.getInputFrames().isEmpty());
		verify(host, never()).getNestedChildren();
		verify(host, never()).getStaticChildren();
		verify(host, never()).getDynamicChildren();
		MinimapMouseListener listener = new MinimapMouseListener(client, plugin);
		for (int i = 0; i < 100; i++)
		{
			MouseEvent move = new MouseEvent(new Canvas(), MouseEvent.MOUSE_MOVED, 1000 + i, 0, 300 + i, 300, 0, false);
			assertEquals(new Point(300 + i, 300), listener.mouseMoved(move).getPoint());
		}
		verify(clientThread, never()).invokeLater(any(Runnable.class));
	}

	@Test
	public void hundredPercentBypassesInputGateAndLayoutChangeClearsOldFrames()
	{
		when(config.scale()).thenReturn(100);
		when(config.upperTabScale()).thenReturn(100);
		when(config.lowerTabScale()).thenReturn(100);
		render();
		assertTrue(plugin.getInputFrames().isEmpty());
		verify(map, never()).setNoClickThrough(false);
		verify(upper, never()).setNoClickThrough(false);
		verify(lower, never()).setNoClickThrough(false);
		when(config.scale()).thenReturn(50);
		plugin.recordPointer(new Point(800, 120), null, MouseEvent.MOUSE_MOVED, 0);
		render();
		assertEquals(1, plugin.getInputFrames().size());
		plugin.onResizeableChanged(null);
		assertTrue(plugin.getInputFrames().isEmpty());
		verify(map, atLeastOnce()).setNoClickThrough(true);
	}
}
