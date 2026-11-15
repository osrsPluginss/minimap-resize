package com.minimapresize;

import java.awt.*;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.Client;
import net.runelite.api.SpritePixels;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;

/** Terrain, native residual UI and immutable native-font glyphs have separate filters/order. */
final class MinimapLayers
{
    private static final Pattern COLOUR = Pattern.compile("<col=([0-9a-fA-F]{1,6})>");
    private final Map<Integer,SpritePixels> nativeMasks = new HashMap<>();
    private final WidgetImageScaler mapScaler = new WidgetImageScaler(), uiScaler = new WidgetImageScaler();
    private final CachedUiBlitter mapBlitter = new CachedUiBlitter(), uiBlitter = new CachedUiBlitter();
    private final Map<String,TextRun> texts = new LinkedHashMap<>();
    private Rectangle viewport, mapArea;
    private SpritePixels maskSprite;
    private boolean[] allowed, terrainAllowed;
    private Area coverage;
    private BufferedImage mapImage, uiImage, clippedMap;
    private int[] mapPixels, uiPixels;
    private int[] raw, ui, terrain;
    private boolean[] clipMask, clipStencil;
    private Rectangle clipArea;
    private int clipWidth, clipHeight;
    private boolean mapChanged, uiChanged;
    private boolean premultiplied;
    private boolean terrainUpdated;
    boolean terrainUpdated() { return terrainUpdated; }
    boolean uiUpdated() { return uiScaler.updated(); }
    private static final class TextRun
    {
        Rectangle bounds; BufferedImage image, prepared; int[] pixels;
        final CachedUiBlitter blitter = new CachedUiBlitter();
    }

    Shape coverage(Client client, ResizableLayout layout)
    {
        int component = layout == ResizableLayout.CLASSIC ? InterfaceID.ToplevelOsrsStretch.MINIMAP : InterfaceID.ToplevelPreEoc.MINIMAP;
        Widget widget = client.getWidget(component);
        if (widget == null || widget.isHidden() || widget.getBounds() == null || widget.getBounds().isEmpty()) { viewport=null;coverage=null;return null; }
        Rectangle bounds = widget.getBounds(); int id = widget.getSpriteId();
        SpritePixels sprite = client.getSpriteOverrides()==null?null:client.getSpriteOverrides().get(id);
        if (sprite == null)
        {
            sprite = nativeMasks.get(id);
            if (sprite == null)
            {
                SpritePixels[] loaded = client.getSprites(client.getIndexSprites(),id,0);
                if (loaded != null && loaded.length>0) { sprite=loaded[0];nativeMasks.put(id,sprite); }
            }
        }
        if (bounds.equals(viewport) && sprite==maskSprite && coverage!=null) { return coverage; }
        viewport=new Rectangle(bounds);maskSprite=sprite;allowed=new boolean[bounds.width*bounds.height];terrainAllowed=new boolean[allowed.length];coverage=new Area();
        int[] pixels=sprite==null?null:sprite.getPixels();int sw=sprite==null?0:sprite.getWidth(),sh=sprite==null?0:sprite.getHeight();
        int mw=sprite==null?0:Math.max(sw,sprite.getMaxWidth()),mh=sprite==null?0:Math.max(sh,sprite.getMaxHeight());
        for(int y=0;y<bounds.height;y++)
        {
            int first=-1,last=-1;
            for(int x=0;x<=bounds.width;x++)
            {
                boolean open=false;
                if(x<bounds.width)
                {
                    if(pixels!=null && pixels.length==sw*sh && mw>0 && mh>0)
                    {
                        int px=x*mw/bounds.width-sprite.getOffsetX(),py=y*mh/bounds.height-sprite.getOffsetY();
                        open=px<0||py<0||px>=sw||py>=sh||pixels[py*sw+px]==0;
                    }
                    else
                    { double dx=(x+.5-bounds.width/2.0)/(bounds.width/2.0),dy=(y+.5-bounds.height/2.0)/(bounds.height/2.0);open=dx*dx+dy*dy<=1; }
                    allowed[y*bounds.width+x]=open;
                }
                if(open) { if(first<0) { first=x; }last=x+1; }
            }
            // Native SpriteMask draws one span from first to last transparent
            // pixel (or the whole row when no transparent pixel exists). It is
            // not a per-pixel alpha stencil. Pixels behind chrome must therefore
            // leave the unthrottled UI layer too; retain the exact visual stencil
            // separately for post-filter clipping.
            if(first<0 && pixels!=null) { first=0;last=bounds.width; }
            if(first>=0)
            { Arrays.fill(terrainAllowed,y*bounds.width+first,y*bounds.width+last,true);coverage.add(new Area(new Rectangle(bounds.x+first,bounds.y+y,last-first,1))); }
        }
        return coverage;
    }

    void render(Client client,ResizableLayout layout,BufferedImage nativeFrame,Rectangle source,Rectangle destination,
        BufferedImage target,Graphics2D graphics,boolean gpu,ScalingFilter terrainFilter,int mapSharpening,int fps,HdUiCompatibility hd)
    {
        coverage(client,layout);
        terrainUpdated=false;
        int length=nativeFrame.getWidth()*nativeFrame.getHeight();
        if(raw==null||raw.length!=length) { raw=new int[length];ui=new int[length]; }
        premultiplied=nativeFrame.getType()==BufferedImage.TYPE_INT_ARGB_PRE;
        PackedPixels nativePixels=premultiplied||nativeFrame.getType()==BufferedImage.TYPE_INT_ARGB?PackedPixels.nativePixels(nativeFrame):null;
        if(nativePixels==null) { nativeFrame.getRGB(0,0,nativeFrame.getWidth(),nativeFrame.getHeight(),raw,0,nativeFrame.getWidth()); }
        else for(int y=0;y<nativeFrame.getHeight();y++)
        { System.arraycopy(nativePixels.pixels,nativePixels.index(0,y),raw,y*nativeFrame.getWidth(),nativeFrame.getWidth()); }
        System.arraycopy(raw,0,ui,0,length);mapArea=viewport==null?new Rectangle():viewport.intersection(source);
        int terrainLength=Math.max(0,mapArea.width*mapArea.height);
        if(terrain==null||terrain.length!=terrainLength) { terrain=new int[terrainLength]; }
        else { Arrays.fill(terrain,0); }
        if(!mapArea.isEmpty())
        {
            for(int y=0,i=0;y<mapArea.height;y++)for(int x=0;x<mapArea.width;x++,i++)
            {
                int gx=mapArea.x+x,gy=mapArea.y+y;
                if(!terrainAllowed[(gy-viewport.y)*viewport.width+gx-viewport.x]) { continue; }
                int index=(gy-source.y)*source.width+gx-source.x;
                if(index<0||index>=raw.length) { continue; }
                terrain[i]=straight(raw[index])|0xff000000;ui[index]=0;
            }
        }
        // Only open stencil pixels were terrain. The rectangular corners can
        // contain native controls (notably Logout), and must remain in the UI.
        int compassId=layout==ResizableLayout.CLASSIC?InterfaceID.ToplevelOsrsStretch.MAP_MINIMAP_GRAPHIC6:InterfaceID.ToplevelPreEoc.MAP_MINIMAP_GRAPHIC6;
        Widget compass=client.getWidget(compassId);
        if(compass!=null&&!compass.isHidden()&&compass.getBounds()!=null) { clear(ui,source,compass.getBounds()); }
        Set<String> seen=new HashSet<>();Set<Widget> visited=Collections.newSetFromMap(new IdentityHashMap<>());
        for(int root:new int[]{InterfaceID.Orbs.UNIVERSE,InterfaceID.OrbsNomap.UNIVERSE,InterfaceID.OrbsOsm.UNIVERSE,InterfaceID.OrbsOsmNomap.UNIVERSE})
        { collect(client.getWidget(root),visited,seen,raw,ui,terrain,source,0); }
        texts.keySet().retainAll(seen);
        boolean nativeSize=source.width==destination.width&&source.height==destination.height;
        if(nativeSize) { mapScaler.invalidate(); }
        if(nativeSize&&!mapArea.isEmpty())
        {
            // At 100% there is nothing to resample. Recombine the disjoint native
            // terrain/UI pixels and blit once instead of copying/clipping/blitting
            // a second terrain image every frame merely because HD UI is enabled.
            for(int y=0,i=0;y<mapArea.height;y++)for(int x=0;x<mapArea.width;x++,i++)
            {
                int gx=mapArea.x+x,gy=mapArea.y+y;
                if(terrain[i]!=0&&allowed[(gy-viewport.y)*viewport.width+gx-viewport.x])
                { ui[(gy-source.y)*source.width+gx-source.x]=terrain[i]; }
            }
        }
        mapChanged=mapImage==null||mapImage.getWidth()!=mapArea.width||mapImage.getHeight()!=mapArea.height||!Arrays.equals(mapPixels,terrain);
        if(!nativeSize&&!mapArea.isEmpty())
        {
            if(mapImage==null||mapImage.getWidth()!=mapArea.width||mapImage.getHeight()!=mapArea.height)
            { mapImage=new BufferedImage(mapArea.width,mapArea.height,BufferedImage.TYPE_INT_ARGB_PRE);mapPixels=((java.awt.image.DataBufferInt)mapImage.getRaster().getDataBuffer()).getData(); }
            if(mapChanged) { System.arraycopy(terrain,0,mapPixels,0,terrain.length); }
            Rectangle out=project(mapArea,source,destination);
            if(!out.isEmpty())
            {
                boolean maskChanged=clipStencil!=allowed||!mapArea.equals(clipArea)||clipWidth!=out.width||clipHeight!=out.height;
                if(maskChanged)
                {
                    clipMask=new boolean[out.width*out.height];clipStencil=allowed;clipArea=new Rectangle(mapArea);clipWidth=out.width;clipHeight=out.height;
                    for(int y=0,i=0;y<out.height;y++)for(int x=0;x<out.width;x++,i++)
                    {
                        int nx=mapArea.x+(int)((x+.5)*mapArea.width/out.width)-viewport.x;
                        int ny=mapArea.y+(int)((y+.5)*mapArea.height/out.height)-viewport.y;
                        clipMask[i]=nx>=0&&ny>=0&&nx<viewport.width&&ny<viewport.height&&allowed[ny*viewport.width+nx];
                    }
                    mapScaler.invalidate();
                }
                BufferedImage scaled=mapScaler.scale(mapImage,out.width,out.height,terrainFilter,mapSharpening,mapChanged,fps);
                terrainUpdated=mapScaler.updated();
                boolean changed=mapScaler.updated()||maskChanged||clippedMap==null||clippedMap.getWidth()!=out.width||clippedMap.getHeight()!=out.height;
                if(changed)
                {
                    if(clippedMap==null||clippedMap.getWidth()!=out.width||clippedMap.getHeight()!=out.height) { clippedMap=new BufferedImage(out.width,out.height,BufferedImage.TYPE_INT_ARGB_PRE); }
                    int[] data=((java.awt.image.DataBufferInt)clippedMap.getRaster().getDataBuffer()).getData();
                    PackedPixels pixels=PackedPixels.nativePixels(scaled);
                    for(int y=0;y<out.height;y++) { System.arraycopy(pixels.pixels,pixels.index(0,y),data,y*out.width,out.width); }
                    // All kernels, including Lanczos/xBR, are clipped AFTER filtering.
                    // A filter can never expand terrain into the frame or controls.
                    for(int i=0;i<data.length;i++) { if(!clipMask[i]) { data[i]=0; } }
                }
                mapBlitter.draw(target,clippedMap,out.x,out.y,graphics,gpu,changed);
            }
        }
        int uiType=premultiplied?BufferedImage.TYPE_INT_ARGB_PRE:BufferedImage.TYPE_INT_ARGB;
        uiChanged=uiImage==null||uiImage.getType()!=uiType||uiImage.getWidth()!=source.width||uiImage.getHeight()!=source.height||!Arrays.equals(uiPixels,ui);
        if(uiImage==null||uiImage.getType()!=uiType||uiImage.getWidth()!=source.width||uiImage.getHeight()!=source.height)
        { uiImage=new BufferedImage(source.width,source.height,uiType);uiPixels=((java.awt.image.DataBufferInt)uiImage.getRaster().getDataBuffer()).getData(); }
        if(uiChanged) { System.arraycopy(ui,0,uiPixels,0,ui.length); }
        BufferedImage residual=uiScaler.scale(uiImage,destination.width,destination.height,hd.minimapUiFilter(),hd.minimapUiSharpening(),uiChanged,0);
        uiBlitter.draw(target,residual,destination.x,destination.y,graphics,gpu,uiScaler.updated());
        hd.paint(graphics,source,destination,target,gpu,Set.of(HdUiCompatibility.COMPASS));
        hd.paint(graphics,source,destination,target,gpu,Set.of(HdUiCompatibility.MINIMAP));
        for(TextRun run:texts.values())
        {
            Rectangle out=project(run.bounds,source,destination);if(out.isEmpty()) { continue; }
            BufferedImage prepared=hd.minimapText(run.image,out.width,out.height,out);boolean changed=prepared!=run.prepared;run.prepared=prepared;
            run.blitter.draw(target,prepared,out.x,out.y,graphics,gpu,changed);
        }
    }
    private void collect(Widget widget,Set<Widget> visited,Set<String> seen,int[] raw,int[] ui,int[] terrain,Rectangle source,int depth)
    {
        if(widget==null||depth>16||widget.isHidden()||widget.isSelfHidden()||!visited.add(widget)) { return; }
        if(widget.getType()==WidgetType.TEXT && widget.getBounds()!=null && widget.getText()!=null && !widget.getText().isEmpty())
        {
            Rectangle bounds=new Rectangle(widget.getBounds());bounds.grow(1,1);bounds=bounds.intersection(source);
            if(!bounds.isEmpty())
            {
                Set<Integer> colours=new HashSet<>();colours.add(widget.getTextColor()&0xffffff);Matcher m=COLOUR.matcher(widget.getText());while(m.find()) { colours.add(Integer.parseInt(m.group(1),16)); }
                int[] glyphs=new int[bounds.width*bounds.height];boolean[] ink=new boolean[glyphs.length];
                for(int y=0,i=0;y<bounds.height;y++)for(int x=0;x<bounds.width;x++,i++)
                {
                    int index=(bounds.y-source.y+y)*source.width+bounds.x-source.x+x,p=straight(raw[index]);
                    if(p>>>24!=0 && colourMatches(p,colours)) { glyphs[i]=p;ink[i]=true; }
                }
                if(widget.getTextShadowed())
                {
                    for(int y=1;y<bounds.height;y++)for(int x=1;x<bounds.width;x++)
                    {
                        int i=y*bounds.width+x,index=(bounds.y-source.y+y)*source.width+bounds.x-source.x+x,p=straight(raw[index]);
                        if(ink[i-bounds.width-1] && (p>>>16&255)<=2 && (p>>>8&255)<=2 && (p&255)<=2 && p>>>24!=0) { glyphs[i]=p; }
                    }
                }
                for(int y=0,i=0;y<bounds.height;y++)for(int x=0;x<bounds.width;x++,i++)
                {
                    if(glyphs[i]>>>24==0) { continue; }
                    int gx=bounds.x+x,gy=bounds.y+y;ui[(gy-source.y)*source.width+gx-source.x]=0;
                    if(mapArea.contains(gx,gy)) { terrain[(gy-mapArea.y)*mapArea.width+gx-mapArea.x]=0; }
                }
                String key=widget.getId()+":"+bounds;seen.add(key);TextRun run=texts.computeIfAbsent(key,ignored -> new TextRun());run.bounds=bounds;
                if(run.image==null||!Arrays.equals(run.pixels,glyphs))
                { run.image=new BufferedImage(bounds.width,bounds.height,BufferedImage.TYPE_INT_ARGB);run.image.setRGB(0,0,bounds.width,bounds.height,glyphs,0,bounds.width);run.pixels=glyphs; }
            }
        }
        // Static/nested children only belong to containers. Asking the client for
        // them on every text/graphic leaf can scan an entire interface repeatedly.
        for(Widget[] list:widget.getType()==WidgetType.LAYER
            ?new Widget[][]{widget.getChildren(),widget.getStaticChildren(),widget.getNestedChildren()}:new Widget[][]{widget.getChildren()})
        { if(list!=null)for(Widget child:list) { collect(child,visited,seen,raw,ui,terrain,source,depth+1); } }
    }
    private int straight(int pixel)
    {
        int alpha=pixel>>>24;
        if(!premultiplied||alpha==255||alpha==0) { return pixel; }
        float scale=255f/alpha;
        return alpha<<24|(int)((pixel>>>16&255)*scale+.5f)<<16|(int)((pixel>>>8&255)*scale+.5f)<<8|(int)((pixel&255)*scale+.5f);
    }
    private static boolean colourMatches(int p,Set<Integer> colours)
    {
        for(int c:colours)if(Math.abs((p>>>16&255)-(c>>>16&255))<=2&&Math.abs((p>>>8&255)-(c>>>8&255))<=2&&Math.abs((p&255)-(c&255))<=2) { return true; }
        return false;
    }
    private static void clear(int[] pixels,Rectangle source,Rectangle region)
    {
        Rectangle r=source.intersection(region);if(r.isEmpty()) { return; }
        for(int y=r.y;y<r.y+r.height;y++)Arrays.fill(pixels,(y-source.y)*source.width+r.x-source.x,(y-source.y)*source.width+r.x-source.x+r.width,0);
    }
    static Rectangle project(Rectangle region,Rectangle source,Rectangle destination)
    {
        int x=(int)Math.round(destination.x+(region.x-source.x)*(double)destination.width/source.width);
        int y=(int)Math.round(destination.y+(region.y-source.y)*(double)destination.height/source.height);
        int right=(int)Math.round(destination.x+(region.x+region.width-source.x)*(double)destination.width/source.width);
        int bottom=(int)Math.round(destination.y+(region.y+region.height-source.y)*(double)destination.height/source.height);
        return new Rectangle(x,y,Math.max(0,right-x),Math.max(0,bottom-y));
    }
}
