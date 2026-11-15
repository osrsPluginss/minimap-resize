package com.minimapresize;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Collections;
import net.runelite.api.Client;
import net.runelite.api.Hitsplat;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.client.game.SpriteManager;
import org.junit.Test;
import static com.minimapresize.OverheadElements.Kind;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class OverheadContextTest
{
	@Test
	public void hdOnlyCaptureSkipsEmptyScenesAndRestrictsRecognitionToVisibleSupportedPrayerColumns()
	{
		Client client = mock(Client.class);
		Player player = mock(Player.class);
		when(client.getPlayers()).thenReturn(Collections.singletonList(player));
		when(client.getNpcs()).thenReturn(Collections.emptyList());
		SpriteManager sprites = mock(SpriteManager.class);
		OverheadContext context = new OverheadContext();
		Rectangle viewport = new Rectangle(20, 30, 1920, 1080);
		BufferedImage hd = new BufferedImage(400, 400, BufferedImage.TYPE_INT_ARGB_PRE);
		assertTrue(context.collectPrayers(client, sprites, viewport, (archive, file) -> hd).isEmpty());
		verify(player, never()).getCanvasImageLocation(any(BufferedImage.class), anyInt());
		verifyNoInteractions(sprites);
		when(player.getOverheadIcon()).thenReturn(net.runelite.api.HeadIcon.SMITE);
		when(player.getLogicalHeight()).thenReturn(200);
		when(player.getCanvasImageLocation(any(BufferedImage.class), eq(215))).thenReturn(new Point(500, 400));
		when(sprites.getSprite(440, 4)).thenReturn(new BufferedImage(25, 25, BufferedImage.TYPE_INT_ARGB_PRE));
		Rectangle capture = context.collectPrayers(client, sprites, viewport, (archive, file) -> hd);
		assertEquals(new Rectangle(436, 30, 128, 434), capture);
		assertEquals(1, context.templates.size()); assertTrue(context.hints.isEmpty());
		assertSame(hd, context.templates.get(0).artwork);
		verify(player, never()).getHealthRatio(); verify(player, never()).getOverheadText();
		when(player.getCanvasImageLocation(any(BufferedImage.class), eq(215))).thenReturn(null);
		assertTrue(context.collectPrayers(client, sprites, viewport, (archive, file) -> hd).isEmpty());
		assertTrue(context.collectPrayers(client, sprites, viewport, (archive, file) -> null).isEmpty());
	}
	@Test
	public void actorsWithoutSpeechHealthBarsOrHitsplatsDoNotNeedCameraProjections()
	{
		Client client = mock(Client.class);
		Player player = mock(Player.class);
		when(client.getPlayers()).thenReturn(Collections.singletonList(player));
		when(client.getNpcs()).thenReturn(Collections.emptyList());
		when(player.getSkullIcon()).thenReturn(-1);
		when(player.getHealthRatio()).thenReturn(-1);
		OverheadContext context = new OverheadContext();
		Graphics2D g = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB_PRE).createGraphics();
		try
		{
			context.collect(client, mock(SpriteManager.class), g, new Rectangle(800, 600));
			assertTrue(context.hints.isEmpty());
			verify(player, never()).getCanvasImageLocation(any(BufferedImage.class), anyInt());
			verify(player, never()).getLogicalHeight();
		}
		finally { g.dispose(); }
	}

	@Test
	public void usesActorAnchorsAndExpiresHitsplatsWithoutLosingNewerHits()
	{
		Client client = mock(Client.class);
		Player player = mock(Player.class);
		when(client.getPlayers()).thenReturn(Collections.singletonList(player));
		when(client.getNpcs()).thenReturn(Collections.emptyList());
		when(client.getGameCycle()).thenReturn(100);
		when(player.getSkullIcon()).thenReturn(-1);
		when(player.getLogicalHeight()).thenReturn(200);
		when(player.getCanvasImageLocation(any(BufferedImage.class), eq(200))).thenReturn(new Point(250, 100));
		when(player.getCanvasImageLocation(any(BufferedImage.class), eq(100))).thenReturn(new Point(250, 180));
		when(player.getOverheadText()).thenReturn("Hello");
		when(player.getOverheadCycle()).thenReturn(50);
		when(player.getHealthRatio()).thenReturn(10);
		OverheadContext context = new OverheadContext();
		context.hitsplat(hit(player, 200));
		context.hitsplat(hit(player, 120));
		Graphics2D g = new BufferedImage(500, 300, BufferedImage.TYPE_INT_ARGB_PRE).createGraphics();
		try
		{
			SpriteManager sprites = mock(SpriteManager.class);
			Rectangle viewport = new Rectangle(50, 30, 400, 270);
			context.collect(client, sprites, g, viewport);
			assertEquals(3, context.hints.size());
			OverheadElements.Element splat = context.hints.stream().filter(e -> e.kind == Kind.HITSPLAT).findFirst().get();
			assertEquals(new Rectangle(120, 100, 160, 100), splat.bounds);
			when(client.getGameCycle()).thenReturn(150);
			context.collect(client, sprites, g, viewport);
			assertTrue(context.hints.stream().anyMatch(e -> e.kind == Kind.HITSPLAT));
			when(client.getGameCycle()).thenReturn(200);
			context.collect(client, sprites, g, viewport);
			assertFalse(context.hints.stream().anyMatch(e -> e.kind == Kind.HITSPLAT));
			context.clear();
			assertTrue(context.hints.isEmpty());
		}
		finally { g.dispose(); }
	}

	private static HitsplatApplied hit(Player player, int expiry)
	{
		Hitsplat hitsplat = mock(Hitsplat.class);
		when(hitsplat.getDisappearsOnGameCycle()).thenReturn(expiry);
		HitsplatApplied event = new HitsplatApplied();
		event.setActor(player);
		event.setHitsplat(hitsplat);
		return event;
	}

	@Test
	public void cachedPlayerAndNpcTemplatesRefreshAuthoredSourcesWithoutExtraProjection()
	{
		Client client = mock(Client.class);
		Player player = mock(Player.class);
		net.runelite.api.NPC npc = mock(net.runelite.api.NPC.class);
		when(client.getPlayers()).thenReturn(Collections.singletonList(player));
		when(client.getNpcs()).thenReturn(Collections.singletonList(npc));
		when(player.getSkullIcon()).thenReturn(-1); when(player.getHealthRatio()).thenReturn(-1);
		when(npc.getHealthRatio()).thenReturn(-1);
		when(player.getOverheadIcon()).thenReturn(net.runelite.api.HeadIcon.SMITE);
		when(npc.getOverheadArchiveIds()).thenReturn(new int[]{440});
		when(npc.getOverheadSpriteIds()).thenReturn(new short[]{6});
		SpriteManager sprites = mock(SpriteManager.class);
		BufferedImage nativeIcon = new BufferedImage(25, 25, BufferedImage.TYPE_INT_ARGB_PRE);
		when(sprites.getSprite(440, 4)).thenReturn(nativeIcon); when(sprites.getSprite(440, 6)).thenReturn(nativeIcon);
		BufferedImage hd = new BufferedImage(480, 480, BufferedImage.TYPE_INT_ARGB);
		OverheadContext context = new OverheadContext();
		Graphics2D graphics = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB_PRE).createGraphics();
		try
		{
			context.collect(client, sprites, graphics, new Rectangle(200, 200), (archive, file) -> archive == 440 ? hd : null);
			assertEquals(2, context.templates.size());
			for (OverheadElements.Template template : context.templates) { assertSame(hd, template.artwork); }
			context.collect(client, sprites, graphics, new Rectangle(200, 200));
			for (OverheadElements.Template template : context.templates) { assertNull(template.artwork); }
			verify(sprites, times(1)).getSprite(440, 4); verify(sprites, times(1)).getSprite(440, 6);
			verify(player, never()).getCanvasImageLocation(any(BufferedImage.class), anyInt());
			verify(npc, never()).getCanvasImageLocation(any(BufferedImage.class), anyInt());
		}
		finally { graphics.dispose(); }
	}
}
