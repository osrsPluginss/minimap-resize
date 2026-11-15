package com.minimapresize;

import java.util.*;
import java.util.function.Predicate;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;

/** Screen-anchored FPS/ping belong to the backdrop, never a widget cutout. */
final class UnscaledDiagnostics
{
    private static final Set<String> TYPES = Set.of("net.runelite.client.plugins.fps.FpsOverlay",
        "net.runelite.client.plugins.worldhopper.WorldHopperPingOverlay");
    private final OverlayManager manager;
    private final Predicate<Overlay> identifies;
    private final Map<Overlay,OverlayLayer> borrowed = new IdentityHashMap<>();

    UnscaledDiagnostics(OverlayManager manager)
    {
        this(manager, overlay -> TYPES.contains(overlay.getClass().getName()));
    }
    UnscaledDiagnostics(OverlayManager manager, Predicate<Overlay> identifies)
    { this.manager=manager;this.identifies=identifies; }

    void beginFrame(boolean needed)
    {
        if(!needed) { restore();return; }
        for(Iterator<Map.Entry<Overlay,OverlayLayer>> it=borrowed.entrySet().iterator();it.hasNext();)
        {
            Map.Entry<Overlay,OverlayLayer> e=it.next();
            if(!manager.anyMatch(o -> o==e.getKey())) { e.getKey().setLayer(e.getValue());it.remove(); }
        }
        List<Overlay> candidates=new ArrayList<>();
        manager.anyMatch(o -> { if(identifies.test(o)&&o.getLayer()==OverlayLayer.ABOVE_WIDGETS&&o.getDrawHooks().isEmpty()) { candidates.add(o); }return false; });
        for(Overlay overlay:candidates)
        {
            borrowed.put(overlay,overlay.getLayer());manager.remove(overlay);
            // The existing late UNDER_WIDGETS capture saves these native-sized
            // labels as backdrop. Restoring that backdrop keeps them behind UI.
            overlay.setLayer(OverlayLayer.UNDER_WIDGETS);manager.add(overlay);
        }
    }
    void restore()
    {
        for(Map.Entry<Overlay,OverlayLayer> e:borrowed.entrySet())
        {
            Overlay overlay=e.getKey();boolean registered=manager.remove(overlay);
            if(overlay.getLayer()==OverlayLayer.UNDER_WIDGETS) { overlay.setLayer(e.getValue()); }
            if(registered) { manager.add(overlay); }
        }
        borrowed.clear();
    }
}
