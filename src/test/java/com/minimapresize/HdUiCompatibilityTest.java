package com.minimapresize;

import com.hdui.ScaledInventoryOverlay;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.api.gameval.InterfaceID;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class HdUiCompatibilityTest
{
	@Test @SuppressWarnings("unchecked") public void finalBankAndSideOutputSkipUnusedPreviewButResumeWhenFinalOutputStops() throws Exception
	{
		for(Set<Integer> groups:List.of(Set.of(InterfaceID.BANKMAIN),HdUiCompatibility.sideGroups(),
			Set.of(HdUiCompatibility.UPPER_TABS),Set.of(HdUiCompatibility.LOWER_TABS)))
		{
			OverlayManager manager=mock(OverlayManager.class);List<Overlay> registered=new ArrayList<>();
			when(manager.anyMatch(any())).thenAnswer(c -> registered.stream().anyMatch(c.getArgument(0)));
			when(manager.add(any())).thenAnswer(c -> registered.add(c.getArgument(0)));
			when(manager.remove(any())).thenAnswer(c -> registered.remove((Object)c.getArgument(0)));
			ScaledInventoryOverlay painter=new ScaledInventoryOverlay();registered.add(painter);
			java.util.concurrent.atomic.AtomicBoolean finalOutput=new java.util.concurrent.atomic.AtomicBoolean(true);
			java.util.concurrent.atomic.AtomicInteger routes=new java.util.concurrent.atomic.AtomicInteger(),queries=new java.util.concurrent.atomic.AtomicInteger();
			HdUiCompatibility bridge=new HdUiCompatibility(manager,message ->
			{
				if("final-output-region".equals(message.getName()))
				{ routes.incrementAndGet();((java.util.function.Consumer<Boolean>)message.getData().get("receiver")).accept(finalOutput.get()); }
				if("request-layer-state".equals(message.getName())) { queries.incrementAndGet(); }
			});
			bridge.regions(groups);
			BufferedImage image=new BufferedImage(800,900,BufferedImage.TYPE_INT_ARGB_PRE);Graphics2D g=image.createGraphics();
			try
			{
				for(int frame=0;frame<20;frame++)
				{
					bridge.beginFrame(true);
					bridge.paint(g,new Rectangle(488,571),new Rectangle(732,857),image,true,groups);
				}
				assertEquals(20,routes.get());assertEquals(0,painter.calls);assertEquals(0,queries.get());
				java.lang.reflect.Field layers=HdUiCompatibility.class.getDeclaredField("layers");layers.setAccessible(true);
				assertTrue("Unused bank-sized images must not be allocated",((java.util.Map<?,?>)layers.get(bridge)).isEmpty());
				finalOutput.set(false);bridge.beginFrame(true);
				bridge.paint(g,new Rectangle(488,571),new Rectangle(732,857),image,true,groups);
				assertEquals("Turning off Stretched Mode must restore the ordinary painter",1,painter.calls);
			}
			finally { g.dispose();bridge.restore(); }
		}
	}

	@Test @SuppressWarnings("unchecked") public void negotiatedRenderBridgeAvoidsBroadcastsAndUsesTightCompassSurfaces() throws Exception
	{
		OverlayManager manager=mock(OverlayManager.class);List<Overlay> registered=new ArrayList<>();
		when(manager.anyMatch(any())).thenAnswer(c -> registered.stream().anyMatch(c.getArgument(0)));
		when(manager.add(any())).thenAnswer(c -> registered.add(c.getArgument(0)));
		when(manager.remove(any())).thenAnswer(c -> registered.remove((Object)c.getArgument(0)));
		ScaledInventoryOverlay painter=new ScaledInventoryOverlay();registered.add(painter);
		painter.contents=g -> { g.setColor(java.awt.Color.ORANGE);g.fillRect(10,20,20,20); };
		java.util.concurrent.atomic.AtomicInteger broadcasts=new java.util.concurrent.atomic.AtomicInteger(),requests=new java.util.concurrent.atomic.AtomicInteger();
		HdUiCompatibility bridge=new HdUiCompatibility(manager,message ->
		{
			broadcasts.incrementAndGet();
			if("request-render-bridge".equals(message.getName()))
			{
				((java.util.function.Consumer<java.util.function.Consumer<net.runelite.client.events.PluginMessage>>)message.getData().get("receiver")).accept(request ->
				{
					requests.incrementAndGet();assertSame(painter,request.getData().get("painter"));
					if("request-layer-state".equals(request.getName()))
					{
						((java.util.function.Consumer<Long>)request.getData().get("receiver")).accept(1L);
						((java.util.function.Consumer<Rectangle>)request.getData().get("boundsReceiver")).accept(new Rectangle(10,20,20,20));
					}
				});
			}
		});
		bridge.regions(Set.of(HdUiCompatibility.COMPASS));
		for(int frame=0;frame<3;frame++)
		{
			bridge.beginFrame(true);BufferedImage image=new BufferedImage(200,200,BufferedImage.TYPE_INT_ARGB_PRE);Graphics2D g=image.createGraphics();
			bridge.paint(g,new Rectangle(100,100),new Rectangle(200,200),image,true,Set.of(HdUiCompatibility.COMPASS));g.dispose();
			assertEquals(java.awt.Color.ORANGE.getRGB(),image.getRGB(25,45));assertEquals(0,image.getRGB(5,5));
		}
		assertEquals("Only initial negotiation reaches the public event bus",1,broadcasts.get());assertTrue(requests.get()>5);
		java.lang.reflect.Field layers=HdUiCompatibility.class.getDeclaredField("layers");layers.setAccessible(true);
		Object layer=((java.util.Map<?,?>)layers.get(bridge)).get(Set.of(HdUiCompatibility.COMPASS));
		java.lang.reflect.Field image=layer.getClass().getDeclaredField("image");image.setAccessible(true);
		assertEquals(40,((BufferedImage)image.get(layer)).getWidth());assertEquals(40,((BufferedImage)image.get(layer)).getHeight());
		bridge.restore();
	}

	@Test @SuppressWarnings("unchecked") public void finalCompassOutputDoesNotAlsoRenderAnInvisibleNativePreview()
	{
		OverlayManager manager=mock(OverlayManager.class);List<Overlay> registered=new ArrayList<>();
		when(manager.anyMatch(any())).thenAnswer(c -> registered.stream().anyMatch(c.getArgument(0)));
		when(manager.add(any())).thenAnswer(c -> registered.add(c.getArgument(0)));
		when(manager.remove(any())).thenAnswer(c -> registered.remove((Object)c.getArgument(0)));
		ScaledInventoryOverlay painter=new ScaledInventoryOverlay();registered.add(painter);
		java.util.concurrent.atomic.AtomicBoolean finalOutput=new java.util.concurrent.atomic.AtomicBoolean(true);
		java.util.concurrent.atomic.AtomicInteger routes=new java.util.concurrent.atomic.AtomicInteger();
		HdUiCompatibility bridge=new HdUiCompatibility(manager,message ->
		{
			if("final-output-region".equals(message.getName()))
			{ routes.incrementAndGet();((java.util.function.Consumer<Boolean>)message.getData().get("receiver")).accept(finalOutput.get()); }
		});
		bridge.regions(Set.of(HdUiCompatibility.COMPASS));
		BufferedImage image=new BufferedImage(200,200,BufferedImage.TYPE_INT_ARGB_PRE);Graphics2D g=image.createGraphics();
		try
		{
			for(int frame=0;frame<4;frame++)
			{ bridge.beginFrame(true);bridge.paint(g,new Rectangle(100,100),new Rectangle(200,200),image,true,Set.of(HdUiCompatibility.COMPASS)); }
			assertEquals(4,routes.get());assertEquals(0,painter.calls);
			finalOutput.set(false);bridge.beginFrame(true);
			bridge.paint(g,new Rectangle(100,100),new Rectangle(200,200),image,true,Set.of(HdUiCompatibility.COMPASS));
			assertEquals("Ordinary non-stretched output must still paint the compass",1,painter.calls);
		}
		finally { g.dispose();bridge.restore(); }
	}

	@Test @SuppressWarnings("unchecked") public void finalStretchedOutputSkipsNativeBlittingButRetainsControlHitCoverageEveryFrame()
	{
		OverlayManager manager=mock(OverlayManager.class);List<Overlay> registered=new ArrayList<>();
		when(manager.anyMatch(any())).thenAnswer(c -> registered.stream().anyMatch(c.getArgument(0)));
		when(manager.add(any())).thenAnswer(c -> registered.add(c.getArgument(0)));
		when(manager.remove(any())).thenAnswer(c -> registered.remove((Object)c.getArgument(0)));
		ScaledInventoryOverlay painter=new ScaledInventoryOverlay();painter.bounds=new Rectangle(0,0,100,100);painter.solidMarker=true;registered.add(painter);
		java.util.concurrent.atomic.AtomicInteger routes=new java.util.concurrent.atomic.AtomicInteger();
		HdUiCompatibility bridge=new HdUiCompatibility(manager,message ->
		{
			if("request-layer-state".equals(message.getName())) { ((java.util.function.Consumer<Long>)message.getData().get("receiver")).accept(1L); }
			if("final-output-region".equals(message.getName())) { routes.incrementAndGet();((java.util.function.Consumer<Boolean>)message.getData().get("receiver")).accept(true); }
		});
		bridge.regions(Set.of(HdUiCompatibility.MINIMAP));
		for(int frame=0;frame<3;frame++)
		{
			bridge.beginFrame(true);BufferedImage image=new BufferedImage(220,220,BufferedImage.TYPE_INT_ARGB_PRE);Graphics2D g=image.createGraphics();
			bridge.paint(g,painter.bounds,new Rectangle(0,0,200,200),image,true,Set.of(HdUiCompatibility.MINIMAP));g.dispose();
			assertEquals("No low-res HD copy enters the global stretch",0,image.getRGB(80,80));
			boolean[] mask=bridge.minimapInputMask();assertNotNull(mask);assertTrue("Painted controls still receive native clicks",mask[40*100+40]);
		}
		assertEquals("Final target is republished even when the native preview is cached",3,routes.get());assertEquals(1,painter.calls);
	}
	@Test public void tabStripsHaveDistinctTransformsDespiteSharingTheNativeFrameInterface()
	{
		assertEquals(Set.of(HdUiCompatibility.UPPER_TABS), HdUiCompatibility.groupsForRegion(WidgetRegion.UPPER_TABS, -1));
		assertEquals(Set.of(HdUiCompatibility.LOWER_TABS), HdUiCompatibility.groupsForRegion(WidgetRegion.LOWER_TABS, -1));
		assertTrue(HdUiCompatibility.sideGroups().contains(HdUiCompatibility.CLASSIC_TABS));
		assertTrue(HdUiCompatibility.sideGroups().contains(InterfaceID.SideJournal.UNIVERSE >>> 16));
		assertFalse(HdUiCompatibility.sideGroups().contains(HdUiCompatibility.UPPER_TABS));
		assertFalse(HdUiCompatibility.sideGroups().contains(HdUiCompatibility.LOWER_TABS));
		assertEquals(Set.of(HdUiCompatibility.MINIMAP, HdUiCompatibility.COMPASS), HdUiCompatibility.groupsForRegion(WidgetRegion.MINIMAP, -1));
	}

	@Test @SuppressWarnings("unchecked") public void independentUpperAndLowerPaintsReuseTheirOwnLayers()
	{
		OverlayManager manager = mock(OverlayManager.class); List<Overlay> registered = new ArrayList<>();
		when(manager.anyMatch(any())).thenAnswer(call -> registered.stream().anyMatch(call.getArgument(0)));
		when(manager.add(any())).thenAnswer(call -> registered.add(call.getArgument(0)));
		when(manager.remove(any())).thenAnswer(call -> registered.remove((Object) call.getArgument(0)));
		ScaledInventoryOverlay painter = new ScaledInventoryOverlay(); manager.add(painter);
		java.util.concurrent.atomic.AtomicReference<Set<Integer>> drawing = new java.util.concurrent.atomic.AtomicReference<>(Set.of());
		painter.contents = g ->
		{
			if (drawing.get().contains(HdUiCompatibility.UPPER_TABS)) { g.setColor(java.awt.Color.RED); g.fillRect(10, 10, 2, 2); }
			if (drawing.get().contains(HdUiCompatibility.LOWER_TABS)) { g.setColor(java.awt.Color.GREEN); g.fillRect(10, 210, 2, 2); }
		};
		HdUiCompatibility bridge = new HdUiCompatibility(manager, message ->
		{
			if ("render-groups".equals(message.getName())) { drawing.set((Set<Integer>) message.getData().get("groups")); }
			if ("request-layer-state".equals(message.getName())) { ((java.util.function.Consumer<Long>) message.getData().get("receiver")).accept(7L); }
		});
		bridge.regions(Set.of(HdUiCompatibility.UPPER_TABS, HdUiCompatibility.LOWER_TABS));
		for (int frame = 0; frame < 5; frame++)
		{
			BufferedImage image = new BufferedImage(300, 350, BufferedImage.TYPE_INT_ARGB_PRE); Graphics2D g = image.createGraphics();
			bridge.beginFrame(true);
			bridge.paint(g, new Rectangle(0, 0, 100, 50), new Rectangle(10, 10, 186, 93), image, true, Set.of(HdUiCompatibility.UPPER_TABS));
			bridge.paint(g, new Rectangle(0, 200, 100, 50), new Rectangle(10, 200, 200, 100), image, true, Set.of(HdUiCompatibility.LOWER_TABS));
			g.dispose(); assertEquals(0xffff0000, image.getRGB(30, 30)); assertEquals(0xff00ff00, image.getRGB(31, 221));
		}
		assertEquals("Stable tab strips render once each", 2, painter.calls); bridge.restore();
	}

	@Test public void equipmentStatsWindowIncludesItsSeparateInventoryAndWornTabUsesTheSidebarPass()
	{
		assertEquals(Set.of(InterfaceID.EQUIPMENT, InterfaceID.EQUIPMENT_SIDE),
			HdUiCompatibility.groupsForInterface(InterfaceID.EQUIPMENT));
		assertTrue(HdUiCompatibility.sideGroups().contains(InterfaceID.WORNITEMS));
		assertTrue(HdUiCompatibility.sideGroups().contains(InterfaceID.EQUIPMENT_SIDE));
		assertFalse(HdUiCompatibility.sideGroups().contains(InterfaceID.EQUIPMENT));
	}

	@Test @SuppressWarnings("unchecked")
	public void stableHdLayersAt186PercentAreReusedButStateGeometryAndRestartChangesRepaintImmediately()
	{
		OverlayManager manager = mock(OverlayManager.class);
		List<Overlay> registered = new ArrayList<>();
		when(manager.anyMatch(any())).thenAnswer(call -> registered.stream().anyMatch(call.getArgument(0)));
		when(manager.add(any())).thenAnswer(call -> registered.add(call.getArgument(0)));
		when(manager.remove(any())).thenAnswer(call -> registered.remove((Object) call.getArgument(0)));
		ScaledInventoryOverlay painter = new ScaledInventoryOverlay(); manager.add(painter);
		painter.bounds = new Rectangle(100, 100, 100, 100); painter.solidMarker = true;
		java.util.concurrent.atomic.AtomicLong revision = new java.util.concurrent.atomic.AtomicLong(1);
		HdUiCompatibility bridge = new HdUiCompatibility(manager, message ->
		{
			if ("request-layer-state".equals(message.getName()))
			{
				((java.util.function.Consumer<Long>) message.getData().get("receiver")).accept(revision.get());
			}
		});
		BufferedImage output = new BufferedImage(400, 400, BufferedImage.TYPE_INT_ARGB_PRE);
		Graphics2D graphics = output.createGraphics();
		Rectangle destination = new Rectangle(100, 100, 186, 186);
		try
		{
			for (int frame = 0; frame < 20; frame++)
			{
				graphics.setComposite(java.awt.AlphaComposite.Clear); graphics.fillRect(0, 0, 400, 400);
				graphics.setComposite(java.awt.AlphaComposite.SrcOver);
				bridge.beginFrame(true); bridge.paint(graphics, painter.bounds, destination, output, true);
				assertEquals(0xffffffcc, output.getRGB(175, 175));
				assertEquals("No alpha accumulation between frames", 128, output.getRGB(156, 156) >>> 24);
			}
			assertEquals("An unchanged panel paints its HD contents once, not once per camera frame", 1, painter.calls);
			painter.colour = 0x00ff00; revision.incrementAndGet();
			bridge.beginFrame(true); bridge.paint(graphics, painter.bounds, destination, output, true);
			assertEquals(2, painter.calls); assertEquals(0xff00ff00, output.getRGB(175, 175));
			destination.translate(-20, 0);
			bridge.beginFrame(true); bridge.paint(graphics, painter.bounds, destination, output, true);
			assertEquals(3, painter.calls);
			bridge.restore(); bridge.beginFrame(true); bridge.paint(graphics, painter.bounds, destination, output, true);
			assertEquals("Re-enabling cannot reuse another painter session's pixels", 4, painter.calls);
		}
		finally { graphics.dispose(); bridge.restore(); }
	}

	@Test public void retainsSubNativePixelDetailsAtPanelResolutionAndRestoresThePainter()
	{
		OverlayManager manager = mock(OverlayManager.class);
		List<Overlay> registered = new ArrayList<>();
		when(manager.anyMatch(any())).thenAnswer(call -> registered.stream().anyMatch(call.getArgument(0)));
		when(manager.add(any())).thenAnswer(call -> registered.add(call.getArgument(0)));
		when(manager.remove(any())).thenAnswer(call -> registered.remove((Object) call.getArgument(0)));
		ScaledInventoryOverlay painter = new ScaledInventoryOverlay();
		painter.bounds = new Rectangle(300, 200, 100, 100);
		manager.add(painter);
		HdUiCompatibility bridge = new HdUiCompatibility(manager);
		for (boolean gpu : new boolean[]{false, true})
		{
			bridge.beginFrame(true);
			assertEquals(OverlayLayer.MANUAL, painter.getLayer());
			BufferedImage output = new BufferedImage(250, 250, BufferedImage.TYPE_INT_ARGB_PRE);
			Graphics2D graphics = output.createGraphics();
			int calls = painter.calls;
			Rectangle destination = new Rectangle(10, 10, 200, 200);
			bridge.paint(graphics, painter.bounds, destination, output, gpu);
			bridge.paint(graphics, painter.bounds, destination, output, gpu);
			graphics.dispose();
			assertEquals(calls + 1, painter.calls);
			assertEquals("Half-native-pixel star detail must occupy its own output pixel", 0xffffffcc, output.getRGB(51, 60));
			assertEquals(0, output.getRGB(50, 60));
			assertEquals(128, output.getRGB(70, 70) >>> 24);
			assertEquals("Painter must stay clipped to the scaled panel", 0, output.getRGB(6, 6));
		}
		bridge.beginFrame(false);
		assertEquals(OverlayLayer.ALWAYS_ON_TOP, painter.getLayer());
		assertEquals(1, registered.size());
		bridge.beginFrame(true);
		manager.remove(painter);
		bridge.beginFrame(true);
		bridge.restore();
		assertEquals(OverlayLayer.ALWAYS_ON_TOP, painter.getLayer());
		assertTrue(registered.isEmpty());
	}

	@Test public void draggedHdPixelsCanExtendPastThePanelWhileRemainingClippedToTheCanvas()
	{
		OverlayManager manager = mock(OverlayManager.class);
		List<Overlay> registered = new ArrayList<>();
		when(manager.anyMatch(any())).thenAnswer(call -> registered.stream().anyMatch(call.getArgument(0)));
		when(manager.add(any())).thenAnswer(call -> registered.add(call.getArgument(0)));
		when(manager.remove(any())).thenAnswer(call -> registered.remove((Object) call.getArgument(0)));
		ScaledInventoryOverlay painter = new ScaledInventoryOverlay();
		painter.bounds = new Rectangle(100, 100, 100, 100);
		painter.drag = new Rectangle(80, 90, 36, 32);
		painter.getBounds().setBounds(painter.drag);
		manager.add(painter);
		HdUiCompatibility bridge = new HdUiCompatibility(manager);
		for (boolean gpu : new boolean[]{false, true})
		{
			bridge.beginFrame(true);
			BufferedImage output = new BufferedImage(320, 320, BufferedImage.TYPE_INT_ARGB_PRE);
			Graphics2D graphics = output.createGraphics();
			bridge.paint(graphics, painter.bounds, new Rectangle(100, 100, 200, 200), output, gpu);
			graphics.dispose();
			assertEquals("The dragged icon must not disappear at the panel edge", 0x80ff8000, output.getRGB(60, 80));
			assertEquals(0, output.getRGB(59, 80));
		}
		bridge.refreshed();
		assertFalse(bridge.refreshPending());
		bridge.nativeChanged(new Object());
		assertFalse(bridge.refreshPending());
		bridge.nativeChanged(painter);
		assertTrue(bridge.refreshPending());
		bridge.refreshed();
		bridge.beginFrame(true);
		assertFalse("Continuing animation does not bypass the background limiter", bridge.refreshPending());
		bridge.restore();
		assertTrue("Returning to native sprites must flush the cached empty panel", bridge.refreshPending());
	}

	@Test public void bankAndSidebarHaveIndependentRenderPassesAndCacheAcknowledgements()
	{
		OverlayManager manager = mock(OverlayManager.class);
		List<Overlay> registered = new ArrayList<>();
		when(manager.anyMatch(any())).thenAnswer(call -> registered.stream().anyMatch(call.getArgument(0)));
		when(manager.add(any())).thenAnswer(call -> registered.add(call.getArgument(0)));
		when(manager.remove(any())).thenAnswer(call -> registered.remove((Object) call.getArgument(0)));
		ScaledInventoryOverlay painter = new ScaledInventoryOverlay(); manager.add(painter);
		Rectangle side = new Rectangle(300, 200, 100, 100), bank = new Rectangle(50, 50, 100, 100);
		Set<Integer> sideGroups = Set.of(InterfaceID.INVENTORY, InterfaceID.BANKSIDE), bankGroups = Set.of(InterfaceID.BANKMAIN);
		HdUiCompatibility bridge = new HdUiCompatibility(manager, message ->
		{
			if (!"render-groups".equals(message.getName())) { return; }
			Set<?> groups = (Set<?>) message.getData().get("groups");
			if (groups.isEmpty()) { return; }
			painter.bounds = groups.contains(InterfaceID.BANKMAIN) ? bank : side;
			painter.colour = groups.contains(InterfaceID.BANKMAIN) ? 0xff00ff : 0xffffcc;
		});
		bridge.beginFrame(true);
		bridge.regions(Set.of(InterfaceID.INVENTORY, InterfaceID.BANKSIDE, InterfaceID.BANKMAIN));
		BufferedImage output = new BufferedImage(600, 450, BufferedImage.TYPE_INT_ARGB_PRE);
		Graphics2D graphics = output.createGraphics();
		bridge.paint(graphics, side, new Rectangle(350, 200, 200, 200), output, true, sideGroups);
		bridge.paint(graphics, bank, new Rectangle(0, 0, 200, 200), output, true, bankGroups);
		graphics.dispose();
		assertEquals("Bank drawing cannot consume the sidebar's one-paint guard", 2, painter.calls);
		assertEquals(0xffffffcc, output.getRGB(391, 250));
		assertEquals(0xffff00ff, output.getRGB(41, 50));
		bridge.refreshed(sideGroups);
		assertFalse(bridge.refreshPending(sideGroups)); assertTrue(bridge.refreshPending(bankGroups));
		bridge.refreshed(bankGroups); assertFalse(bridge.refreshPending(bankGroups));
		bridge.nativeChanged(painter);
		assertTrue(bridge.refreshPending(sideGroups)); assertTrue(bridge.refreshPending(bankGroups));
	}
}
