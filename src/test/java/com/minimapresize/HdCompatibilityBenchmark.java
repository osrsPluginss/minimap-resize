package com.minimapresize;

import com.hdui.ScaledInventoryOverlay;
import com.sun.management.ThreadMXBean;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayManager;
import static org.mockito.Mockito.*;

/** Controlled CPU comparisons of the replaced paths. This does not predict in-game FPS. */
public final class HdCompatibilityBenchmark
{
	private static volatile int sink;

	private static void measure(String label, Runnable frame)
	{
		for (int i = 0; i < 300; i++) { frame.run(); }
		int count = 800;
		long[] samples = new long[count];
		ThreadMXBean memory = (ThreadMXBean) ManagementFactory.getThreadMXBean();
		long thread = Thread.currentThread().getId(), bytes = memory.getThreadAllocatedBytes(thread);
		long start = System.nanoTime();
		for (int i = 0; i < count; i++)
		{
			long before = System.nanoTime(); frame.run(); samples[i] = System.nanoTime() - before;
		}
		long elapsed = System.nanoTime() - start, allocated = memory.getThreadAllocatedBytes(thread) - bytes;
		Arrays.sort(samples);
		System.out.printf(Locale.ROOT, "%s: %.3f ms/frame; p95 %.3f ms; %.1f KiB/frame%n", label,
			elapsed / (count * 1_000_000.0), samples[(int) (count * .95)] / 1_000_000.0, allocated / (count * 1024.0));
	}

	@SuppressWarnings("unchecked")
	private static void panel(boolean cache)
	{
		OverlayManager manager = mock(OverlayManager.class);
		List<Overlay> registered = new ArrayList<>();
		when(manager.anyMatch(any())).thenAnswer(call -> registered.stream().anyMatch(call.getArgument(0)));
		when(manager.add(any())).thenAnswer(call -> registered.add(call.getArgument(0)));
		when(manager.remove(any())).thenAnswer(call -> registered.remove((Object) call.getArgument(0)));
		ScaledInventoryOverlay painter = new ScaledInventoryOverlay(); manager.add(painter);
		painter.bounds = new Rectangle(0, 0, 190, 261);
		BufferedImage icon = new BufferedImage(67, 60, BufferedImage.TYPE_INT_ARGB_PRE);
		Graphics2D art = icon.createGraphics();
		art.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		art.setColor(new Color(200, 160, 100)); art.fillOval(4, 3, 59, 54); art.dispose();
		painter.contents = g ->
		{
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			for (int i = 0; i < 28; i++) { g.drawImage(icon, 8 + i % 4 * 44, 4 + i / 4 * 36, 36, 32, null); }
		};
		HdUiCompatibility bridge = new HdUiCompatibility(manager, message ->
		{
			if (cache && "request-layer-state".equals(message.getName()))
			{
				((Consumer<Long>) message.getData().get("receiver")).accept(1L);
			}
		});
		BufferedImage image = new BufferedImage(354, 486, BufferedImage.TYPE_INT_ARGB_PRE);
		Graphics2D graphics = image.createGraphics();
		Rectangle destination = new Rectangle(0, 0, 354, 486);
		try
		{
			measure("186% HD panel / " + (cache ? "cached layer" : "per-frame redraw"), () ->
			{
				Arrays.fill(PackedPixels.nativePixels(image).pixels, 0xff303030);
				bridge.beginFrame(true); bridge.paint(graphics, painter.bounds, destination, image, true);
				sink ^= image.getRGB(30, 30);
			});
		}
		finally { graphics.dispose(); bridge.restore(); }
	}

	private static void overhead(boolean fast, boolean populated)
	{
		BufferedImage image = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_ARGB_PRE);
		Graphics2D graphics = image.createGraphics();
		BufferedImage nativeIcon = new BufferedImage(25, 25, BufferedImage.TYPE_INT_ARGB_PRE);
		BufferedImage hd = new BufferedImage(400, 400, BufferedImage.TYPE_INT_ARGB_PRE);
		Arrays.fill(PackedPixels.nativePixels(nativeIcon).pixels, 0xff0000ff);
		Arrays.fill(PackedPixels.nativePixels(hd).pixels, 0xff00ff00);
		OverheadContext context = new OverheadContext();
		OverheadElements.Template template = new OverheadElements.Template(nativeIcon, OverheadElements.Kind.PRAYER);
		template.artwork = hd; context.templates.add(template);
		OverheadScaler scaler = new OverheadScaler();
		MinimapResizeConfig config = new MinimapResizeConfig() { };
		int[] frame = {0};
		try
		{
			measure("1080p HD overhead / " + (populated ? "moving prayer / " : "no prayers / ") + (fast ? "targeted" : "full viewport"), () ->
			{
				Arrays.fill(PackedPixels.nativePixels(image).pixels, 0);
				int x = 900 + frame[0]++ % 160, y = 480;
				scaler.newFrame();
				if (!fast || populated)
				{
					scaler.begin(image, fast ? new Rectangle(x - 64, 0, 128, y + 100) : new Rectangle(1920, 1080));
				}
				if (populated) { graphics.drawImage(nativeIcon, x, y, null); }
				if (fast) { scaler.finishPrayers(image, graphics, context); }
				else { scaler.finish(image, graphics, config, context); }
				sink ^= image.getRGB(x, y);
			});
		}
		finally { graphics.dispose(); scaler.clear(); }
	}

	@SuppressWarnings("unchecked")
	private static void bankFinalOutput()
	{
		OverlayManager manager=mock(OverlayManager.class);
		List<Overlay> registered=new ArrayList<>();
		when(manager.anyMatch(any())).thenAnswer(call -> registered.stream().anyMatch(call.getArgument(0)));
		when(manager.add(any())).thenAnswer(call -> registered.add(call.getArgument(0)));
		when(manager.remove(any())).thenAnswer(call -> registered.remove((Object)call.getArgument(0)));
		ScaledInventoryOverlay painter=new ScaledInventoryOverlay();registered.add(painter);
		painter.bounds=new Rectangle(0,0,488,571);
		BufferedImage chrome=new BufferedImage(732,857,BufferedImage.TYPE_INT_ARGB_PRE);
		Graphics2D bg=chrome.createGraphics();bg.setColor(new Color(50,45,40));bg.fillRect(0,0,732,857);bg.dispose();
		BufferedImage icon=new BufferedImage(54,48,BufferedImage.TYPE_INT_ARGB_PRE);
		Graphics2D art=icon.createGraphics();art.setColor(new Color(200,160,100,220));art.fillOval(3,3,48,42);art.dispose();
		painter.contents=g ->
		{
			g.drawImage(chrome,0,0,488,571,null);
			for(int i=0;i<100;i++) { g.drawImage(icon,12+i%8*54,42+i/8*38,36,32,null); }
		};
		long[] signature={0};
		HdUiCompatibility bridge=new HdUiCompatibility(manager,message ->
		{
			if("request-layer-state".equals(message.getName()))
			{ ((Consumer<Long>)message.getData().get("receiver")).accept(++signature[0]); }
			if("final-output-region".equals(message.getName()))
			{ ((Consumer<Boolean>)message.getData().get("receiver")).accept(true); }
		});
		java.util.Set<Integer> groups=java.util.Set.of(net.runelite.api.gameval.InterfaceID.BANKMAIN);
		bridge.regions(groups);
		BufferedImage output=new BufferedImage(1920,1080,BufferedImage.TYPE_INT_ARGB_PRE);
		Graphics2D g=output.createGraphics();
		try
		{
			measure("Changing 100-item bank / final-output handoff",() ->
			{
				bridge.beginFrame(true);
				bridge.paint(g,painter.bounds,new Rectangle(350,3,732,857),output,true,groups);
			});
			System.out.println("Invisible bank preview paints: "+painter.calls);
		}
		finally { g.dispose();bridge.restore(); }
	}

	public static void main(String[] args)
	{
		if(args.length>0&&"bank".equals(args[0])) { bankFinalOutput();return; }
		System.out.println("Synthetic hot-path comparison; CPU timings only, not in-game FPS. Panel fixture excludes live-widget signature traversal.");
		panel(false); panel(true);
		for (boolean populated : new boolean[]{false, true}) { overhead(false, populated); overhead(true, populated); }
	}
}
