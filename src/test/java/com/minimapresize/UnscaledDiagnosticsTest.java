package com.minimapresize;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import net.runelite.client.ui.overlay.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class UnscaledDiagnosticsTest
{
    @Test public void screenDiagnosticsMoveBeforeCaptureAndRestoreWithoutResurrectingDisabledOverlays()
    {
        OverlayManager manager=mock(OverlayManager.class);List<Overlay> registered=new ArrayList<>();
        when(manager.add(any())).thenAnswer(c -> registered.add(c.getArgument(0)));
        when(manager.remove(any())).thenAnswer(c -> registered.remove((Object)c.getArgument(0)));
        when(manager.anyMatch(any())).thenAnswer(c -> registered.stream().anyMatch(c.getArgument(0)));
        Overlay fps=overlay(),ping=overlay(),other=overlay();registered.addAll(List.of(fps,ping,other));
        UnscaledDiagnostics compatibility=new UnscaledDiagnostics(manager,o -> o==fps||o==ping);
        compatibility.beginFrame(true);compatibility.beginFrame(true);
        assertEquals(OverlayLayer.UNDER_WIDGETS,fps.getLayer());assertEquals(OverlayLayer.UNDER_WIDGETS,ping.getLayer());
        assertEquals(OverlayLayer.ABOVE_WIDGETS,other.getLayer());assertEquals(3,registered.size());
        registered.remove(ping);compatibility.beginFrame(true);assertEquals(OverlayLayer.ABOVE_WIDGETS,ping.getLayer());
        compatibility.beginFrame(false);assertEquals(OverlayLayer.ABOVE_WIDGETS,fps.getLayer());assertFalse(registered.contains(ping));assertEquals(2,registered.size());
    }

    @Test public void diagnosticsRemainNativeSizedBehindScaledControlsInSoftwareAndGpuBuffers()
    {
        for(boolean gpu:new boolean[]{false,true})
        {
            BufferedImage frame=new BufferedImage(100,70,gpu?BufferedImage.TYPE_INT_ARGB_PRE:BufferedImage.TYPE_INT_ARGB);
            Graphics2D g=frame.createGraphics();if(!gpu) { g.setColor(new Color(0x222222));g.fillRect(0,0,100,70); }
            g.setColor(Color.YELLOW);g.fillRect(84,2,4,3);g.fillRect(94,2,4,3);g.dispose();
            Rectangle source=new Rectangle(60,0,40,30);UiUnderlay underlay=new UiUnderlay();if(gpu) { underlay.captureAndClear(frame,List.of(source)); }
            MinimapCompositor compositor=new MinimapCompositor();compositor.capture(frame,source,gpu);
            g=frame.createGraphics();g.setColor(Color.RED);g.fillRect(96,0,4,4);g.dispose();
            BufferedImage foreground=compositor.extractAndRestore(null);underlay.restore(frame);
            g=frame.createGraphics();g.drawImage(new WidgetImageScaler().scale(foreground,80,60,ScalingFilter.NEAREST,0),20,0,null);g.dispose();
            assertEquals("Uncovered diagnostics stay at their native anchor/size",Color.YELLOW.getRGB(),frame.getRGB(84,3));
            assertNotEquals("No enlarged diagnostic copy is captured to the left",Color.YELLOW.getRGB(),frame.getRGB(68,4));
            assertEquals("Scaled Logout remains above the native diagnostic text",Color.RED.getRGB(),frame.getRGB(94,3));
        }
    }
    private static Overlay overlay()
    {
        Overlay overlay=new Overlay() { @Override public Dimension render(Graphics2D g) { return null; } };
        overlay.setLayer(OverlayLayer.ABOVE_WIDGETS);overlay.setPosition(OverlayPosition.DYNAMIC);return overlay;
    }
}
