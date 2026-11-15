package com.minimapresize;

import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.slf4j.LoggerFactory;

/** Opt-in, client-thread stage measurements. No per-frame formatting or sample allocations. */
final class RenderTimings
{
	enum Stage { PREPARE, CAPTURE, WIDGETS, OVERHEADS, OUTPUTS, PANEL_CAPTURE, PANEL_FILTER, PANEL_BLIT, HD_MINIMAP, HD_COMPASS, HD_ARTWORK, INPUT }
	private static final Stage[] STAGES = Stage.values();
	private static final long WINDOW = 10_000_000_000L;
	private final LongSupplier clock;
	private final Consumer<String> log;
	private final long[] totals = new long[STAGES.length];
	private final long[] maxima = new long[STAGES.length];
	private boolean enabled;
	private long windowStart, previousFrame, longestFrame;
	private int frames, panelFrames, panelChanges, panelUpdates;
	private int terrainUpdates, minimapUiUpdates;

	RenderTimings()
	{
		this(System::nanoTime, LoggerFactory.getLogger(RenderTimings.class)::info);
	}

	RenderTimings(LongSupplier clock, Consumer<String> log)
	{
		this.clock = clock;
		this.log = log;
	}

	void beginFrame(boolean active)
	{
		if (!active) { enabled = false; return; }
		long now = clock.getAsLong();
		if (!enabled)
		{
			enabled = true;
			reset(now);
			previousFrame = now;
		}
		long interval = now - previousFrame;
		previousFrame = now;
		longestFrame = Math.max(longestFrame, interval);
		if (now - windowStart >= WINDOW && frames > 0)
		{
			StringBuilder message = new StringBuilder(String.format(Locale.ROOT,
				"Resize timings: frames=%d fps=%.1f maxFrame=%.2fms panelFrames=%d panelChanges=%d panelUpdates=%d; stages mean/frame,max/call ms:",
				frames, frames * 1_000_000_000.0 / (now - windowStart), longestFrame / 1_000_000.0,
				panelFrames, panelChanges, panelUpdates));
			message.append(" terrainUpdates=").append(terrainUpdates).append(" minimapUiUpdates=").append(minimapUiUpdates);
			for (Stage stage : STAGES)
			{
				int index = stage.ordinal();
				message.append(String.format(Locale.ROOT, " %s=%.3f/%.3f", stage,
					totals[index] / (frames * 1_000_000.0), maxima[index] / 1_000_000.0));
			}
			log.accept(message.toString());
			reset(now);
		}
		frames++;
	}

	long start() { return enabled ? clock.getAsLong() : 0; }

	void finish(Stage stage, long start)
	{
		if (!enabled || start == 0) { return; }
		long duration = clock.getAsLong() - start;
		int index = stage.ordinal();
		totals[index] += duration;
		maxima[index] = Math.max(maxima[index], duration);
	}

	void panel(boolean changed, boolean updated)
	{
		if (!enabled) { return; }
		panelFrames++;
		if (changed) { panelChanges++; }
		if (updated) { panelUpdates++; }
	}

	void minimap(boolean terrain, boolean ui)
	{
		if (!enabled) { return; }
		if (terrain) { terrainUpdates++; }
		if (ui) { minimapUiUpdates++; }
	}

	private void reset(long now)
	{
		windowStart = now;
		longestFrame = 0;
		frames = panelFrames = panelChanges = panelUpdates = 0;
		terrainUpdates = minimapUiUpdates = 0;
		Arrays.fill(totals, 0);
		Arrays.fill(maxima, 0);
	}
}
