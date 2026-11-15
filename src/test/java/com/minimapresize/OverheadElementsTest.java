package com.minimapresize;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static com.minimapresize.OverheadElements.Kind;
import static org.junit.Assert.*;

public class OverheadElementsTest
{
	@Test
	public void separatedActorsDoNotLeaveStaleTextOrBarsInPreviouslyOccupiedRows()
	{
		BufferedImage canvas = new BufferedImage(940, 740, BufferedImage.TYPE_INT_ARGB_PRE);
		BufferedImage layer = canvas.getSubimage(17, 23, 900, 700);
		BufferedImage icon = solid(12, 12, Color.BLUE);
		OverheadElements recognizer = new OverheadElements();
		List<OverheadElements.Template> templates = Collections.singletonList(new OverheadElements.Template(icon, Kind.PRAYER));
		for (int frame = 0; frame < 4; frame++)
		{
			Rectangle bar = new Rectangle(frame % 2 == 0 ? 80 : 700, 250, 30, 5);
			Rectangle text = new Rectangle(350, frame % 2 == 0 ? 100 : 450, 70, 8);
			Graphics2D g = layer.createGraphics();
			g.setComposite(java.awt.AlphaComposite.Clear);
			g.fillRect(0, 0, 900, 700);
			g.setComposite(java.awt.AlphaComposite.SrcOver);
			g.drawImage(icon, 5, 5, null);
			g.drawImage(icon, 850, 650, null);
			if (frame != 3)
			{
				g.setColor(Color.GREEN); g.fill(bar);
				g.setColor(Color.YELLOW); g.fill(text);
			}
			g.dispose();
			List<OverheadElements.Element> found = recognizer.identify(layer, templates, Arrays.asList(
				new OverheadElements.Element(bar, Kind.HEALTH_BAR), new OverheadElements.Element(text, Kind.TEXT)));
			assertEquals(frame == 3 ? 2 : 4, found.size());
			assertEquals(2, found.stream().filter(e -> e.kind == Kind.PRAYER).count());
			if (frame != 3)
			{
				assertTrue(found.stream().anyMatch(e -> e.kind == Kind.HEALTH_BAR && e.bounds.equals(bar)));
				assertTrue(found.stream().anyMatch(e -> e.kind == Kind.TEXT && e.bounds.equals(text)));
			}
		}
	}

	@Test
	public void sparseRecognitionPreservesTransparentSpriteBordersAndViewportCoordinates()
	{
		BufferedImage canvas = new BufferedImage(900, 700, BufferedImage.TYPE_INT_ARGB_PRE);
		BufferedImage layer = canvas.getSubimage(17, 23, 800, 600);
		BufferedImage icon = new BufferedImage(20, 24, BufferedImage.TYPE_INT_ARGB_PRE);
		Graphics2D iconGraphics = icon.createGraphics();
		iconGraphics.setColor(Color.BLUE);
		iconGraphics.fillRect(6, 7, 8, 10);
		iconGraphics.dispose();
		OverheadElements recognizer = new OverheadElements();
		List<OverheadElements.Template> templates = Collections.singletonList(new OverheadElements.Template(icon, Kind.PRAYER));
		for (java.awt.Point point : Arrays.asList(new java.awt.Point(0, 0), new java.awt.Point(700, 500), new java.awt.Point(200, 300)))
		{
			Graphics2D g = layer.createGraphics();
			g.setComposite(java.awt.AlphaComposite.Clear);
			g.fillRect(0, 0, layer.getWidth(), layer.getHeight());
			g.setComposite(java.awt.AlphaComposite.SrcOver);
			g.drawImage(icon, point.x, point.y, null);
			g.dispose();
			List<OverheadElements.Element> found = recognizer.identify(layer, templates, Collections.emptyList());
			assertEquals("Old camera positions must not become extra indicators", 1, found.size());
			assertEquals(Kind.PRAYER, found.get(0).kind);
			assertEquals(new Rectangle(point.x, point.y, 20, 24), found.get(0).bounds);
		}
	}

	@Test
	public void emptyOverheadPassSkipsActorCollectionAndRestoresSceneLabels()
	{
		BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB_PRE);
		image.setRGB(400, 300, Color.YELLOW.getRGB());
		OverheadScaler scaler = new OverheadScaler();
		Graphics2D g = image.createGraphics();
		try
		{
			scaler.begin(image, new Rectangle(20, 30, 700, 500));
			assertEquals(0, image.getRGB(400, 300));
			scaler.finish(image, g, new MinimapResizeConfig() {}, new OverheadContext(),
				() -> fail("An empty native pass must not enumerate or project actors"));
			assertEquals(Color.YELLOW.getRGB(), image.getRGB(400, 300));
		}
		finally { g.dispose(); scaler.clear(); }
	}

	@Test
	public void movingTextUsesCurrentPixelsAfterAnEmptyFrameAndSizeChanges()
	{
		OverheadElements recognizer = new OverheadElements();
		BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB_PRE);
		for (Rectangle text : Arrays.asList(new Rectangle(10, 10, 80, 8), new Rectangle(),
			new Rectangle(650, 500, 90, 10), new Rectangle(20, 20, 100, 12)))
		{
			Graphics2D g = image.createGraphics();
			g.setComposite(java.awt.AlphaComposite.Clear);
			g.fillRect(0, 0, 800, 600);
			g.setComposite(java.awt.AlphaComposite.SrcOver);
			g.setColor(Color.YELLOW);
			g.fill(text);
			g.dispose();
			List<OverheadElements.Element> found = recognizer.identify(image, Collections.emptyList(),
				Collections.singletonList(new OverheadElements.Element(text, Kind.TEXT)));
			assertEquals(text.isEmpty() ? 0 : 1, found.size());
			if (!text.isEmpty())
			{
				assertEquals(text, found.get(0).bounds);
				assertEquals(Kind.TEXT, found.get(0).kind);
				assertEquals(Color.YELLOW.getRGB(), found.get(0).layer.getRGB(text.x, text.y));
			}
		}
	}

	@Test
	public void adjacentPrayerAndSkullAreSeparatedBeforeTextClustering()
	{
		BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB_PRE);
		BufferedImage prayer = solid(6, 6, Color.BLUE), skull = solid(6, 6, Color.WHITE);
		Graphics2D g = image.createGraphics();
		g.drawImage(prayer, 30, 20, null);
		g.drawImage(skull, 30, 28, null); // The old cluster pass merges these.
		g.dispose();
		List<OverheadElements.Element> found = new OverheadElements().identify(image, Arrays.asList(
			new OverheadElements.Template(prayer, Kind.PRAYER), new OverheadElements.Template(skull, Kind.SKULL)), Collections.emptyList());
		assertEquals(2, found.size());
		assertEquals(Kind.PRAYER, found.get(0).kind);
		assertEquals(new Rectangle(30, 20, 6, 6), found.get(0).bounds);
		assertEquals(Kind.SKULL, found.get(1).kind);
		assertEquals(new Rectangle(30, 28, 6, 6), found.get(1).bounds);
		assertEquals(Color.BLUE.getRGB(), image.getRGB(30, 20)); // Recognition never changes the native buffer.
	}

	@Test
	public void occludedIconsAndAmbiguousTextHitsplatOverlapsRemainNative()
	{
		BufferedImage image = solid(6, 6, Color.BLUE);
		BufferedImage icon = solid(6, 6, Color.BLUE);
		image.setRGB(0, 0, Color.YELLOW.getRGB());
		Rectangle area = new Rectangle(6, 6);
		List<OverheadElements.Element> found = new OverheadElements().identify(image,
			Collections.singletonList(new OverheadElements.Template(icon, Kind.PRAYER)),
			Arrays.asList(new OverheadElements.Element(area, Kind.TEXT), new OverheadElements.Element(area, Kind.HITSPLAT)));
		assertEquals(1, found.size());
		assertEquals(Kind.NATIVE, found.get(0).kind);
	}

	@Test
	public void nativeHealthBarAndItsFillAreOneElement()
	{
		BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB_PRE);
		Graphics2D g = image.createGraphics();
		g.setColor(Color.GREEN); g.fillRect(30, 20, 12, 5);
		g.setColor(Color.RED); g.fillRect(42, 20, 18, 5);
		g.dispose();
		List<OverheadElements.Element> found = new OverheadElements().identify(image, Collections.emptyList(),
			Collections.singletonList(new OverheadElements.Element(new Rectangle(100, 100), Kind.HEALTH_BAR)));
		assertEquals(1, found.size());
		assertEquals(Kind.HEALTH_BAR, found.get(0).kind);
		assertEquals(new Rectangle(30, 20, 30, 5), found.get(0).bounds);
	}

	@Test
	public void spriteHealthBarRecognizesPartialFillWithoutMatchingASolidHitsplat()
	{
		BufferedImage front = solid(30, 6, Color.GREEN), back = solid(30, 6, Color.RED);
		front.setRGB(0, 0, Color.BLACK.getRGB()); back.setRGB(0, 0, Color.BLACK.getRGB());
		BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB_PRE);
		Graphics2D g = image.createGraphics();
		g.drawImage(back, 30, 20, null);
		g.drawImage(front, 30, 20, 40, 26, 0, 0, 10, 6, null);
		g.dispose();
		List<OverheadElements.Element> found = new OverheadElements().identify(image,
			Collections.singletonList(new OverheadElements.Template(front, back, Kind.HEALTH_BAR)), Collections.emptyList());
		assertEquals(1, found.size());
		assertEquals(Kind.HEALTH_BAR, found.get(0).kind);
		assertEquals(new Rectangle(30, 20, 30, 6), found.get(0).bounds);
	}

	@Test
	public void allFiveControlsProduceIndependentSizesAndLeaveUnknownIndicatorsAlone()
	{
		BufferedImage image = new BufferedImage(800, 200, BufferedImage.TYPE_INT_ARGB_PRE);
		OverheadScaler scaler = new OverheadScaler();
		OverheadContext context = new OverheadContext();
		MinimapResizeConfig config = new MinimapResizeConfig()
		{
			@Override public int healthBarScale() { return 200; }
			@Override public int hitsplatScale() { return 50; }
			@Override public int prayerIconScale() { return 150; }
			@Override public int skullIconScale() { return 300; }
			@Override public int overheadTextScale() { return 250; }
		};
		Graphics2D g = image.createGraphics();
		try
		{
			scaler.begin(image, new Rectangle(800, 200));
			g.setColor(Color.GREEN); g.fillRect(50, 100, 30, 5);
			g.setColor(Color.MAGENTA); g.fillRect(180, 100, 10, 8);
			g.setColor(Color.BLUE); g.fillRect(310, 100, 6, 6);
			g.setColor(Color.WHITE); g.fillRect(440, 100, 6, 6);
			g.setColor(Color.YELLOW); g.fillRect(570, 100, 24, 8);
			g.setColor(Color.ORANGE); g.fillRect(700, 100, 10, 10);
			context.templates.add(new OverheadElements.Template(solid(6, 6, Color.BLUE), Kind.PRAYER));
			context.templates.add(new OverheadElements.Template(solid(6, 6, Color.WHITE), Kind.SKULL));
			context.hints.add(new OverheadElements.Element(new Rectangle(40, 90, 50, 30), Kind.HEALTH_BAR));
			context.hints.add(new OverheadElements.Element(new Rectangle(170, 90, 30, 30), Kind.HITSPLAT));
			context.hints.add(new OverheadElements.Element(new Rectangle(560, 90, 50, 30), Kind.TEXT));
			scaler.finish(image, g, config, context);
			assertEquals(new Rectangle(35, 95, 60, 10), colourBounds(image, Color.GREEN));
			assertEquals(new Rectangle(182, 104, 5, 4), colourBounds(image, Color.MAGENTA));
			assertEquals(new Rectangle(309, 97, 9, 9), colourBounds(image, Color.BLUE));
			assertEquals(new Rectangle(434, 88, 18, 18), colourBounds(image, Color.WHITE));
			assertEquals(new Rectangle(552, 88, 60, 20), colourBounds(image, Color.YELLOW));
			assertEquals(new Rectangle(700, 100, 10, 10), colourBounds(image, Color.ORANGE));
		}
		finally { g.dispose(); scaler.clear(); }
	}

	@Test
	public void textSurroundingAnUnscaledIconDoesNotExtractOrDuplicateThatIcon()
	{
		BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB_PRE);
		OverheadScaler scaler = new OverheadScaler();
		OverheadContext context = new OverheadContext();
		MinimapResizeConfig config = new MinimapResizeConfig() { @Override public int overheadTextScale() { return 200; } };
		Graphics2D g = image.createGraphics();
		try
		{
			scaler.begin(image, new Rectangle(100, 100));
			g.setColor(Color.YELLOW); g.fillRect(30, 30, 24, 2); g.fillRect(30, 36, 24, 2);
			g.setColor(Color.BLUE); g.fillRect(40, 32, 4, 4);
			context.templates.add(new OverheadElements.Template(solid(4, 4, Color.BLUE), Kind.PRAYER));
			context.hints.add(new OverheadElements.Element(new Rectangle(100, 100), Kind.TEXT));
			scaler.finish(image, g, config, context);
			assertTrue(new Rectangle(40, 32, 4, 4).contains(colourBounds(image, Color.BLUE)));
		}
		finally { g.dispose(); scaler.clear(); }
	}

	private static BufferedImage solid(int width, int height, Color colour)
	{
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
		Graphics2D g = image.createGraphics();
		g.setColor(colour); g.fillRect(0, 0, width, height); g.dispose();
		return image;
	}

	private static Rectangle colourBounds(BufferedImage image, Color colour)
	{
		Rectangle result = null;
		for (int y = 0; y < image.getHeight(); y++)
		{
			for (int x = 0; x < image.getWidth(); x++)
			{
				if (image.getRGB(x, y) != colour.getRGB()) { continue; }
				if (result == null) { result = new Rectangle(x, y, 1, 1); }
				else { result.add(new Rectangle(x, y, 1, 1)); }
			}
		}
		return result;
	}
}
