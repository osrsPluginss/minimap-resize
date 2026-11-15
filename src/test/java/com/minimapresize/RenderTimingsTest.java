package com.minimapresize;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class RenderTimingsTest
{
	@Test
	public void disabledTimingDoesNotReadTheClockOrLog()
	{
		RenderTimings timings = new RenderTimings(() -> { fail("Disabled diagnostics must not sample time"); return 0; },
			message -> fail("Disabled diagnostics must not log"));
		timings.beginFrame(false);
		assertEquals(0, timings.start());
		timings.finish(RenderTimings.Stage.INPUT, 0);
		timings.panel(true, true);
	}

	@Test
	public void reportsStageCostsAndPanelRefreshesOncePerWindowAndResetsAfterDisable()
	{
		AtomicLong clock = new AtomicLong(1);
		List<String> messages = new ArrayList<>();
		RenderTimings timings = new RenderTimings(clock::get, messages::add);
		for (int i = 0; i < 100; i++)
		{
			clock.set(1 + i * 100_000_000L);
			timings.beginFrame(true);
			long start = timings.start();
			clock.addAndGet(2_000_000);
			timings.finish(RenderTimings.Stage.PANEL_FILTER, start);
			timings.panel(true, i % 2 == 0);
		}
		assertTrue(messages.isEmpty());
		clock.set(10_000_000_001L);
		timings.beginFrame(true);
		assertEquals(1, messages.size());
		assertTrue(messages.get(0).contains("frames=100 fps=10.0 maxFrame=100.00ms"));
		assertTrue(messages.get(0).contains("panelFrames=100 panelChanges=100 panelUpdates=50"));
		assertTrue(messages.get(0).contains("PANEL_FILTER=2.000/2.000"));
		timings.beginFrame(false);
		clock.set(100_000_000_001L);
		timings.beginFrame(true);
		assertEquals("Disabled time must not produce a spurious slow-frame report", 1, messages.size());
		clock.addAndGet(10_000_000_000L);
		timings.beginFrame(true);
		assertEquals(2, messages.size());
		assertTrue(messages.get(1).contains("panelFrames=0 panelChanges=0 panelUpdates=0"));
		assertTrue(messages.get(1).contains("PANEL_FILTER=0.000/0.000"));
	}
}
