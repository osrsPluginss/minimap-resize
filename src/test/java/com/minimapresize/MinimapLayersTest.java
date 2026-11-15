package com.minimapresize;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.runelite.api.Client;
import net.runelite.api.SpritePixels;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayManager;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class MinimapLayersTest
{
    @Test public void nativeSizeBypassDoesNotLeaveAStaleTerrainCacheWhenScalingReturns()
    {
        Fixture f=new Fixture(ResizableLayout.MODERN,ScalingFilter.BILINEAR,false);
        f.render(true,ScalingFilter.BILINEAR,200);f.terrainColour=Color.BLUE;
        assertEquals(Color.BLUE.getRGB(),at(f.render(true,ScalingFilter.BILINEAR,100),50,30,100));
        assertEquals("Returning to a previous size bypasses its old refresh-limited image",Color.BLUE.getRGB(),at(f.render(true,ScalingFilter.BILINEAR,200),50,30,200));
    }

    @Test public void nativeRowSpansBehindChromeNeverInvalidateTheResidualUiDuringRotation() throws Exception
    {
        Fixture f=new Fixture(ResizableLayout.MODERN,ScalingFilter.BILINEAR,false);
        int[] mask=f.client.getSpriteOverrides().get(1178).getPixels();
        Arrays.fill(mask,0,60,1);mask[30*60+30]=1;
        Shape nativeSpan=f.layers.coverage(f.client,f.layout);
        assertTrue("Native all-opaque mask rows still draw a full terrain span",nativeSpan.contains(40.5,5.5));
        assertTrue("Native terrain spans include internal opaque stencil pixels",nativeSpan.contains(50.5,35.5));
        BufferedImage first=f.render(true,ScalingFilter.BILINEAR,200);
        assertEquals("Visual mask still clips the top border",0,at(first,40,5,200));
        assertEquals("Visual mask still clips internal chrome",0,at(first,50,35,200));
        f.terrainColour=Color.BLUE;f.render(true,ScalingFilter.BILINEAR,200);
        java.lang.reflect.Field field=MinimapLayers.class.getDeclaredField("uiScaler");field.setAccessible(true);
        assertFalse("Camera changes behind chrome must not trigger the unthrottled UI filter",((WidgetImageScaler)field.get(f.layers)).updated());
    }

    @Test public void terrainHonoursRefreshLimitWhileNativeControlsAndTextUpdateImmediately()
    {
        Fixture f=new Fixture(ResizableLayout.MODERN,ScalingFilter.NEAREST,false);
        assertEquals(Color.GREEN.getRGB(),at(f.render(true,ScalingFilter.NEAREST,200),50,30,200));
        f.terrainColour=Color.BLUE;f.secondValue=true;f.logout=true;
        BufferedImage limited=f.render(true,ScalingFilter.NEAREST,200);
        assertEquals("Terrain uses the same refresh limit as the non-HD path",Color.GREEN.getRGB(),at(limited,50,30,200));
        assertEquals("Text stays responsive while terrain is cached",Color.YELLOW.getRGB(),at(limited,78,46,200));
        assertEquals("Residual UI is not terrain-throttled",Color.CYAN.getRGB(),at(limited,75,9,200));
        f.fps=0;
        assertEquals("A deferred terrain change is not lost through reused scratch arrays",Color.BLUE.getRGB(),at(f.render(true,ScalingFilter.NEAREST,200),50,30,200));
        f.logout=false;
        assertEquals("Removed residual UI does not remain in reused buffers",0,at(f.render(true,ScalingFilter.NEAREST,200),75,9,200));
    }
    @Test public void nativeLogoutInTheStencilCornerIsNeverErasedWithTheTerrain()
    {
        for(boolean gpu:new boolean[]{false,true})for(ResizableLayout layout:new ResizableLayout[]{ResizableLayout.CLASSIC,ResizableLayout.MODERN})
        for(int percent:new int[]{100,186,275})
        {
            Fixture f=new Fixture(layout,ScalingFilter.XBR,false);f.logout=true;
            BufferedImage result=f.render(gpu,ScalingFilter.XBR,percent);
            assertEquals("Logout survives inside the map rectangle but outside the actual stencil",Color.CYAN.getRGB(),at(result,75,9,percent));
        }
    }
    @Test public void everyMapAndUiFilterKeepsTerrainInsideTheThemeMaskAndControlsInFront()
    {
        for(boolean gpu:new boolean[]{false,true})for(ResizableLayout layout:new ResizableLayout[]{ResizableLayout.CLASSIC,ResizableLayout.MODERN})
        {
            for(ScalingFilter uiFilter:ScalingFilter.values())
            {
                Fixture f=new Fixture(layout,uiFilter,false);int[] uiReference=null;
                for(ScalingFilter mapFilter:ScalingFilter.values())
                {
                    BufferedImage result=f.render(gpu,mapFilter,186);
                    assertEquals("Terrain centre",Color.GREEN.getRGB(),at(result,50,30,186));
                    assertEquals("Theme border covers terrain",Color.MAGENTA.getRGB(),at(result,71,36,186));
                    assertEquals("World-map/orb interior cannot be covered by terrain",Color.RED.getRGB(),at(result,86,50,186));
                    assertEquals("No terrain escapes the resource-pack stencil",0,at(result,22,7,186));
                    assertEquals("Pack stencil notch is respected",0,at(result,33,48,186));
                    assertEquals("Native font numbers are above HD interiors",Color.YELLOW.getRGB(),at(result,76,43,186));
                    Rectangle r=MinimapLayers.project(new Rectangle(70,35,25,25),f.source,f.destination(186));
                    int[] controls=result.getRGB(r.x,r.y,r.width,r.height,null,0,r.width);
                    if(uiReference==null) { uiReference=controls; } else { assertArrayEquals("Map filtering cannot change any UI/control/text pixel",uiReference,controls); }
                    assertTrue(f.textRequests>0);assertFalse("Terrain/background pixels are never mistaken for native font glyphs",f.textContainedGreen);
                }
            }
        }
    }

    @Test public void valuesUpdateImmediatelyAndTextModeIsIndependentOfArtworkAndTerrainFiltering()
    {
        for(boolean smooth:new boolean[]{false,true})
        {
            Fixture f=new Fixture(ResizableLayout.MODERN,ScalingFilter.LANCZOS,smooth);
            BufferedImage first=f.render(true,ScalingFilter.SHARP_BICUBIC,200);
            int initial=at(first,76,43,200);
            if(!smooth) { assertEquals(Color.YELLOW.getRGB(),initial); }
            else { assertTrue("Smooth glyph coverage stays visibly yellow",(initial>>>16&255)>200&&(initial>>>8&255)>100&&(initial&255)<40); }
            f.secondValue=true;BufferedImage second=f.render(true,ScalingFilter.XBR,200);
            int updated=at(second,78,46,200);
            assertTrue("New glyph is drawn immediately",(updated>>>16&255)>200&&(updated>>>8&255)>100&&(updated&255)<40);
            assertEquals(Color.RED.getRGB(),at(second,76,43,200));
        }
    }

    private static int at(BufferedImage image,double x,double y,int percent)
    { return image.getRGB((int)Math.floor(5+(x+.5)*percent/100),(int)Math.floor(7+(y+.5)*percent/100)); }

    private static final class Fixture
    {
        final Client client=mock(Client.class);final Rectangle source=new Rectangle(0,0,100,80);
        final MinimapLayers layers=new MinimapLayers();final HdUiCompatibility bridge;
        final ResizableLayout layout;final ScalingFilter uiFilter;final boolean smooth;
        int textRequests;boolean textContainedGreen,secondValue,logout;
        Color terrainColour=Color.GREEN;int fps=1;
        @SuppressWarnings("unchecked") Fixture(ResizableLayout layout,ScalingFilter uiFilter,boolean smooth)
        {
            this.layout=layout;this.uiFilter=uiFilter;this.smooth=smooth;
            Widget map=mock(Widget.class);when(map.getBounds()).thenReturn(new Rectangle(20,5,60,60));when(map.getSpriteId()).thenReturn(1178);
            when(client.getWidget(layout==ResizableLayout.CLASSIC?InterfaceID.ToplevelOsrsStretch.MINIMAP:InterfaceID.ToplevelPreEoc.MINIMAP)).thenReturn(map);
            SpritePixels stencil=mock(SpritePixels.class);int[] pixels=new int[3600];
            for(int y=0;y<60;y++)for(int x=0;x<60;x++)
            { double dx=x+.5-30,dy=y+.5-30;pixels[y*60+x]=dx*dx+dy*dy<=900&&!(x<15&&y>40)?0:1; }
            when(stencil.getPixels()).thenReturn(pixels);when(stencil.getWidth()).thenReturn(60);when(stencil.getHeight()).thenReturn(60);when(stencil.getMaxWidth()).thenReturn(60);when(stencil.getMaxHeight()).thenReturn(60);
            when(client.getSpriteOverrides()).thenReturn(Map.of(1178,stencil));
            Widget root=mock(Widget.class),text=mock(Widget.class);when(root.getChildren()).thenReturn(new Widget[]{text});when(client.getWidget(InterfaceID.Orbs.UNIVERSE)).thenReturn(root);
            when(text.getType()).thenReturn(WidgetType.TEXT);when(text.getId()).thenReturn(InterfaceID.Orbs.UNIVERSE+10);when(text.getBounds()).thenReturn(new Rectangle(75,42,5,7));when(text.getText()).thenAnswer(call -> secondValue?"98":"99");when(text.getTextColor()).thenReturn(0xffff00);when(text.getTextShadowed()).thenReturn(true);
            OverlayManager manager=mock(OverlayManager.class);List<Overlay> registered=new ArrayList<>();
            when(manager.add(any())).thenAnswer(call -> registered.add(call.getArgument(0)));when(manager.remove(any())).thenAnswer(call -> registered.remove((Object)call.getArgument(0)));when(manager.anyMatch(any())).thenAnswer(call -> registered.stream().anyMatch(call.getArgument(0)));
            com.hdui.ScaledInventoryOverlay painter=new com.hdui.ScaledInventoryOverlay();manager.add(painter);AtomicReference<Set<Integer>> drawing=new AtomicReference<>(Set.of());
            painter.contents=g ->
            {
                if(!drawing.get().contains(HdUiCompatibility.MINIMAP)) { return; }
                g.setColor(Color.MAGENTA);g.fillRect(70,35,25,25);g.setColor(Color.RED);g.fillRect(73,38,19,19);
            };
            bridge=new HdUiCompatibility(manager,message ->
            {
                Map<String,Object> d=message.getData();
                if("request-minimap-capability".equals(message.getName())) { ((Consumer<Boolean>)d.get("receiver")).accept(true); }
                if("request-minimap-style".equals(message.getName())) { ((Consumer<Map<String,Object>>)d.get("receiver")).accept(Map.of("version",2,"enabled",true,"filter",uiFilter.name(),"sharpening",35,"text",smooth?"SMOOTH_XBR":"GAME")); }
                if("render-groups".equals(message.getName())) { drawing.set((Set<Integer>)d.get("groups")); }
                if("request-layer-state".equals(message.getName())) { ((Consumer<Long>)d.get("receiver")).accept(1L); }
                if("prepare-minimap-text".equals(message.getName()))
                {
                    BufferedImage glyphs=(BufferedImage)d.get("image");textRequests++;
                    for(int p:glyphs.getRGB(0,0,glyphs.getWidth(),glyphs.getHeight(),null,0,glyphs.getWidth())) { textContainedGreen|=p==Color.GREEN.getRGB(); }
                    BufferedImage result=new WidgetImageScaler().scale(glyphs,(Integer)d.get("width"),(Integer)d.get("height"),smooth?ScalingFilter.XBR:ScalingFilter.NEAREST,0);
                    ((Consumer<BufferedImage>)d.get("receiver")).accept(result);
                }
            });
            assertTrue(bridge.minimapAvailable());assertTrue(bridge.minimapCompositor());bridge.beginFrame(true);bridge.regions(Set.of(HdUiCompatibility.MINIMAP,HdUiCompatibility.COMPASS));
        }
        Rectangle destination(int percent) { return new Rectangle(5,7,percent,(int)Math.round(80*percent/100.0)); }
        BufferedImage render(boolean gpu,ScalingFilter filter,int percent)
        {
            BufferedImage nativeFrame=new BufferedImage(100,80,BufferedImage.TYPE_INT_ARGB);Graphics2D g=nativeFrame.createGraphics();
            g.setColor(terrainColour);g.fill(layers.coverage(client,layout));
            if(logout) { g.setColor(Color.CYAN);g.fillRect(73,7,6,6); }
            g.setColor(Color.BLACK);g.fillRect(secondValue?79:77,secondValue?47:44,1,1);g.setColor(Color.YELLOW);g.fillRect(secondValue?78:76,secondValue?46:43,1,1);g.dispose();
            BufferedImage output=new BufferedImage(420,350,gpu?BufferedImage.TYPE_INT_ARGB_PRE:BufferedImage.TYPE_INT_ARGB);g=output.createGraphics();
            bridge.beginFrame(true);layers.render(client,layout,nativeFrame,source,destination(percent),output,g,gpu,filter,35,fps,bridge);g.dispose();return output;
        }
    }
}
