# Inventory & Minimap Resize

Independently resize the minimap/orbs, inventory and other side panels, modern-layout tab bars, overheads, right-click menus, chat, hover tooltips and central interfaces in RuneLite. Choose smoother or sharper filtering and keep input aligned with the visible controls.

Source: [PHYSIQUE-sys/inventory-minimap-resize](https://github.com/PHYSIQUE-sys/inventory-minimap-resize)

Plugin Hub submission: [runelite/plugin-hub#17942](https://github.com/runelite/plugin-hub/pull/17942). Installation through the Hub becomes available after maintainer approval and merge.

## Current development fixes

- **0.4.25:** skip undisplayed native-resolution HD previews for bank, side panels and tabs when HD UI accepts the final Stretched Mode output. Only the minimap consumes the supplemental painted hit mask, so its preview remains. The final route is still published every frame and ordinary rendering resumes immediately if the final-output handoff stops. Includes regression coverage and a `hdCompatibilityBenchmark --args=bank` synthetic 100-item bank benchmark; these timings are not live FPS measurements.

- **0.4.24 / HD UI 0.2.85:** use logical bank geometry for capture and output anchoring even when only descendants have stale rendered origins. HD UI uses the same projected layout for chrome, items and clipping, and clips off-screen chrome before sizing its cached layer. The recorded host/child mismatch and partial native redraw are covered by regression tests. Live confirmation is still required.

- **0.4.23:** replace the 0.4.21 container-triggered parent revalidation and 0.4.22 remembered-frame repair with one native bank size-check after `InterfaceLayout` changes the host. Native `toplevel_resize` can restore the full-height modal; shrinking that host again does not recalculate the bank's absolute height. Run clientscript 839 with invalid previous dimensions so its size-check proc 840 recalculates the bank and scrollbars before drawing. The actual sized container is `Bankmain.UNIVERSE` (786433), not `Bankmain.FRAME` (786434), which the previous repair incorrectly targeted. Regression coverage now leaves absolute frame height unchanged during parent revalidation and exercises the full-height-to-scaled transition plus idle/container-update bypass. Compared `first.mkv` and the 13:08 recording: both have the same upward shift/cropped title on equip/deposit. Tests pass; live resolution still requires verification.

- **0.4.22:** the second recording and geometry sample identify an absolute bank-frame rewrite: fitting `originalHeight=571, originalY=1` becomes `originalHeight=800, originalY=31` inside a 574-pixel logical host, producing relative Y=-257. Retain the last fitting frame's sizing inputs for that host/layout, and repair an oversized absolute request synchronously after bank scripts (including scripts called by later BeforeRender subscribers) and during safe preparation. Revalidate only that frame subtree, not its top-level host. Repeated notifications with fitting geometry do nothing; a reentrancy guard prevents repair-generated script callbacks from looping. Restore the latest native request on exit without overwriting external edits. Remove the 0.4.21 script-to-parent-revalidation feedback loop. Tests exercise the recorded sizing inputs, script callback reentrancy, later render subscribers, intentional fitting sizes and restoration. Live verification remains required.

- **0.4.21:** the supplied 60-fps recording shows the title/upper bank rows disappearing at about 9.00 s (equip) and 12.02 s (deposit-worn), not merely an opening flash. Coalesce container updates and bank init/build/finish/size-check script notifications into one safe BeforeRender host revalidation even when its sizing metadata is unchanged. This refreshes derived native descendant layout instead of assuming host-size equality means the entire bank tree is current. No mouse press is required. Tests verify descendant refresh, coalescing and idle bypass in both layouts with compatibility on/off. One stable and one mismatched geometry sample (widget geometry only, no item contents) help distinguish remaining live layout failures. Live resolution is not yet confirmed.

- **0.4.20:** address the recorded equipment-refresh mismatch in the 0.4.19 live log: bank source `(472,146,488,571)` briefly became `(472,290,488,284)`, moving output from `(350,3,732,857)` to `(350,436,732,426)`. Capture both rendered and logical bank-host extents on established frames too. When the established host has unchanged dimensions but a stale rendered origin, project current widget bounds/clips from its logical origin and use that same logical origin for output anchoring. Do not replay an old bank image or alter widget input metadata. Opening and genuinely different-size frames retain their existing readiness handling. The regression models the recorded origins, live current pixels, full top/bottom coverage and close input in both renderers/layouts and compatibility modes. Live verification remains required.

- **0.4.19:** remove central-host layout revalidation from background-capture and native host-draw callbacks. Only BeforeRender may apply/restore the smaller logical viewport. Equipment/container scripts that rewrite sizing metadata during a draw therefore cannot make those callbacks reflow geometry after native screen positions have been cached. A bank attached after preparation waits for the next safe preparation before first presentation, rather than triggering reflow inside drawing. Regression coverage checks current bank pixels and close-button input across the update and next frame, both renderers/layouts, and compatibility on/off without relying on a manual mouse press. Bounded diagnostics now include geometry/output changes even when no capture is missing. This fixes a reproduced ordering defect; live deposit-worn and custom-client equip/deposit behavior remains to be verified.

- **0.4.18:** compatibility mode now bypasses every off-hover widget tree, including tab/frame ancestors, rather than only rectangular region roots. Invisible unfinished bank openings remain protected. This broadens the native-location click-through tradeoff to all resized regions. Add bounded interface lifecycle diagnostics (no item contents) showing resets, group changes and missing completed captures with container-update tick correlation. The user's live reports show 0.4.17 and compatibility enabled did not resolve either symptom; the bank flash is not yet confirmed fixed.

- **0.4.17:** separate a central window's successfully presented host from its transient input frame. An already displayed bank no longer re-enters the scene-only opening guard merely because a container/equipment refresh or missed capture invalidates input. Current completed native pixels and matching input are still used; no previous bank screenshot is replayed. Real close/reopen, interface changes and canvas/layout resets rearm the guard. A regression reproduces the false-opening flash and checks both renderers, resizable layouts, bank/shared-bank and close/reopen safety. Live deposit-worn/equip-in-bank verification remains required.

- **0.4.16:** add **Interaction compatibility → Preserve off-hover panel actions** for integrations that read or invoke widget operations without moving the mouse into the target panel. Enable it to retain inventory/bank/GE/chat/window actions, click masks and operation listeners regardless of hover. Scaling and mouse/menu/drag transforms are unchanged. The default remains off to preserve normal click-through behavior: with compatibility enabled, original native panel locations can intercept clicks when panels are shrunk, moved or overlap. Invisible unfinished bank-opening frames remain gated, and minimap/tab hit masks keep their normal gating. Live verification with the affected custom client is required.

- **0.4.12 / HD UI 0.2.40:** forward original-material region transforms and orb glyph placement to HD UI's post-stretch pass. Local resizer scale, native Stretched Mode and monitor DPI are composed into final-resolution artwork. Low-resolution previews are retained for painted control hit masks, while duplicate native HD blits are omitted. The capability handshake preserves legacy behavior with an older HD plugin. The HD update also supersamples the rotating compass and pixel-aligns its final output at fractional sizes.

- **0.4.11 / HD UI 0.2.39:** FPS and World Hopper ping are moved to the native-sized pre-widget backdrop while minimap composition is active, retaining their existing screen anchor and restoring their original overlay layers when scaling/plugins are disabled. They are never captured/enlarged into map terrain or controls. The paired HD update stabilizes Smooth xBR numbers across fractional scales, removes a second bilinear pass on painted minimap controls, enlarges special-attack swords another 5%, and adds textured compass background brightness.

- **0.4.10 / HD UI 0.2.38:** xBR includes opacity in edge detection so dark round outlines are reconstructed against transparency. Terrain extraction preserves native UI in closed stencil corners, fixing the square cutout in Logout. The paired HD UI update uses cached full-footprint reduction for painted edges/XP/Wiki, clears theme-specific XP lettering, fixes the 14-bit compass angle and adjusts HP/special artwork.

- **0.4.9 / HD UI 0.2.37:** separate filtered terrain from resource-pack controls and native-font text. All terrain filters are clipped to the active map stencil after resampling, then borders/controls are painted above the map. HD UI owns the surrounding UI filter/sharpening and orb **Stack text style**. Capture waits for late attached Orb groups, including at 100% output. This preserves the pack's frame/compass/XP colours and prevents terrain from covering buttons/orbs. Regression matrices cover every terrain/UI filter combination, both layouts, software/GPU, normal/resized output and changing values.

- **0.4.8 / HD UI 0.2.36:** capability-negotiated full-resolution minimap artwork. Painted orbs/frames render beneath native values and percentage masks; a separate rotating compass pass renders above capture with its complete fixed border. The existing minimap input transform includes painted control coverage. Inventory and upper/lower tab routes remain independently cached. Compatibility regressions cover minimap-only scaling, both layouts, software/GPU, 150/186/275% sizes, live text ordering and orb hit regions.

- **0.4.7 / HD UI 0.2.31:** draw the approved GPU-painted sidebar/journal icons directly at the final region resolution. Modern upper and lower tab bars receive separate logical routes even though they share a native frame interface; classic frame icons and the journal use the sidebar route. Each route has independent transform and output-cache state, preserving native button chrome and input.

- **0.4.6:** restores the pre-draft HD UI compatibility scopes while replacement artwork is previewed for selection.
- **0.4.4 cleanup:** the confirmed newly-equipped worn-icon delay was stale native widget visibility, repaired by **HD UI → Instant worn equipment icons**, independently of its artwork options. The resizer owns no worn-slot scripts or diagnostic renderer. Its generic interactive cache refresh now uses one-shot scaler invalidation instead of per-region item/tab revision counters. Completed native side-panel capture and ordinary item/tab refreshes remain so correct native UI reaches the scaled output promptly, with normal background throttling afterward. Tests log to the test console rather than the live client's log. See `docs/rendering-compatibility.md`.

- **HD worn equipment:** the HD UI sidebar pass now includes the worn-equipment tab and the equipment-stats window's separate inventory. The central equipment window receives its own scoped HD pass, following its independent interface scale. Update HD UI too for item preparation and native sprite suppression.

- **0.4.2 / HD UI 0.2.15:** cache independently scoped HD output layers until their current post-script artwork/animation/drag/clip/geometry signature changes; use cached alpha runs for compositing. Prayer-point widget rebuilds no longer trigger a full native/HD prayer-grid refresh. At normal overhead sizes, HD-only capture is restricted to supported visible prayer actors' columns and skips unrelated indicator recognition. Repeated mouse polling within unchanged regions is coalesced while boundary crossings and camera deltas remain correct. Run `hdCompatibilityBenchmark` for a controlled comparison; the measured synthetic 186% panel path fell from 1.435 ms with forced redraw to 0.142 ms with layer reuse. These timings exclude live-widget traversal and do not predict game FPS. Detailed findings and user-run checks are in the paired HD UI project's `docs/performance-0.2.15.md` and `docs/manual-testing.md`.

- HD UI 0.2.13 overhead compatibility shares the approved illustrated prayer sources across native player/NPC overheads and Responsive Movement. Native symbols are recognized against the game's original cache sprites, then replaced once with a directly sampled HD source, including at 100% overhead size. **Prayer icon size** still controls the bottom-centred output. Combined protection symbols are supported; unknown/occluded symbols retain their native rendering. Authored images bypass pixel-art filtering, sharpening and refresh throttling, and shared samples avoid resampling the same prayer for every actor in a crowd. Rebuild/restart all three plugins for the updated handoff.

- Reduce actor-indicator CPU cost when overhead sizes are enabled, even with inventory/chat/minimap at 100%. Empty native overhead passes skip actor collection and recognition. Populated passes use vectorized empty-row checks and restrict recognition/clustering to current content plus transparent sprite borders; old camera-frame pixels outside that area cannot enter recognition. Actors without speech/health bars/hitsplats no longer require screen projections. Inactive HD UI compatibility skips native widget lookups, and mouse movement queues no widget-gate work when there are no scaled input regions. The native-size HD bank/GE/other window passes remain covered by the compatibility tests. See the overhead benchmark below.

- Extend the HD UI 0.2.11 sidebar painter to spellbook, prayer/quick-prayer, emote, skills, combat and autocast interface groups. These share the existing post-filter output transform and retain native input routing. HD UI owns the widget-specific sprite suppression and live state selection; the resizer supplies the separately scoped render pass, including at a one-FPS panel refresh limit. Rebuild both plugins for the new tab categories.

- Extend HD UI 0.2.10 compatibility to trade/confirmation and trade-side inventory, shops, price checker, seed vault and raid-storage inventories. Every central interface can supply a separately scoped HD pass, allowing the HD plugin's registered clue/reward, collection and retrieval windows to participate without adding another central-window allowlist here. Side groups remain independent from the window's scale and painter acknowledgement. New regression matrices cover trades, confirmation, clues, raid rewards, shops and collection log across both layouts, native/enlarged sizes, software/GPU and the one-FPS refresh limit. Individual in-game workflows still need visual confirmation.

- Add HD UI 0.2.7 Grand Exchange compatibility: the GE-side inventory is included in side-panel groups, and the central GE offer group receives a separate native/enlarged draw context. Opening GE with a 100% central window now retains the HD item pass instead of disabling it. The shared window/side regression covers bank and GE, interface sizes 100/150/200%, panel sizes 100/200%, both resizable layouts, software/GPU alpha and a one-FPS scaling limit. Live GE workflow verification remains required.

- Extend the HD UI bridge for 0.2.5 to bank grids and bank-side inventories. Interface-group capabilities and per-pass group selection keep bank and sidebar painters separate, including overlapping logical bounds and a 100% bank window alongside an enlarged inventory. Each region has its own refresh acknowledgement; the first paint no longer consumes every other region's HD pass. Opening a bank, closing it, and enabling HD UI while it is already open are covered by the region protocol. Live visual checks of the paired update are still required.

- Fix HD UI drag/drop cache transitions with the 0.2.4 paired update. Detailed icons, including the dragged icon, stay on the separate every-frame HD pass. HD UI's local native-representation event forces one panel refresh for slot/ownership changes, then the normal scaling refresh limit resumes. A one-FPS regression verifies animated detail updates independently, native ghosts clear immediately, and the background is not permanently exempted from the limit. Dragged HD bounds can extend past the panel while remaining canvas-clipped.

- Add an HD UI 0.2.3 compatibility bridge for direct-resolution inventory detail. HD UI's original render is painted after panel filtering, at the panel's actual output size; a 200% panel no longer has to enlarge a 36×32 HD UI intermediate. The bridge uses only RuneLite's public overlay API, preserves premultiplied GPU alpha, and retains the existing input transform. Install both rebuilt plugins through `C:\Projects\hd ui\install-local.bat`. HD UI's status reports when the direct HD path is active. Main-inventory star detail and animation still need live visual comparison with the earlier setup.

- Fix the chat size slider having no visible effect: chat extraction now waits for the attached `CHATBOX` interface to finish drawing. The parent `CHAT_CONTAINER` layer callback fires before those pixels exist. A regression exercises the registered callbacks in native order at 50%, 100%, 150% and 200% in both resizable layouts with software/GPU capture, checking the complete output, typing line, chat controls, wheel-input alignment and removal of native-size ghosts. Live confirmation is still needed after restarting with the rebuilt plugin.
- Fix the settings panel failing to open after the minimap-filter update: configuration provision now fills missing defaults before RuneLite builds its controls. The panel reads stored enum names directly, so a working render-time fallback alone was insufficient. Existing valid settings are preserved. Regression tests open and edit the real RuneLite Swing settings panel with a fresh profile, an older xBR profile missing the new key, and an explicitly saved minimap filter.
- Give the rotating minimap its own filter so expensive inventory filters do not add their full cost to every camera turn. Existing Nearest/Bilinear/Hybrid preferences remain the default; other shared filters default the minimap to Bilinear. The new **Minimap filter** selector can explicitly choose any filter.
- Fix immediate disable-on-enable: the late hover preparation callback now registers separately through RuneLite's event bus. A real event-bus lifecycle test covers startup, render ordering and callback cleanup.
- Fix central-window enlargement and click alignment: bank/GE/shop/trade capture now runs after the attached interface group has drawn, rather than at the earlier host-layer callback. Hooks follow the active group and are removed when a window closes or scaling returns to 100%. Blank captures and mid-frame geometry changes do not publish a scaled input target.
- Fix the responsive bank's roughly 129% enlargement ceiling: bank/shared-bank hosts receive their smaller logical layout area as soon as they open above 100%, including when opening scripts initially report a short/unfinished body. The bank can reflow to fewer rows before its pixels are enlarged, rather than enlarging an already-tall bank and immediately hitting the fit limit. Close/bottom controls remain on screen with matching input; native sizing modes are restored on close, bypass, resize and shutdown.
- Rework the opening-flash fix after the earlier position-prediction change proved insufficient in-game. Central interfaces now save their host backdrop at the parent-layer hook and choose the actual window rectangle only after the attached group finishes drawing. Late geometry changes therefore no longer trigger the previous native-size fallback. GPU captures isolate the UI from underlying text; software uses the saved host backdrop. The first two completed opening frames log their source/output bounds for live diagnosis.
- Roll back the painted-pixel bounds inference and same-frame late-paint retry after the user reported a worse transition. New live logs show a 99-pixel opening strip at the bottom followed by the proper 626-pixel window, with the host moving from y=0 to y=116. Central layout preparation now runs in BeforeRender before native screen positions are cached; later callbacks normally reuse that layout. Empty widget metadata retains native input instead of guessing a window from arbitrary pixels. Opening diagnostics include both rendered and layout host bounds.
- Refine the improved bank opening: when an opening bank has empty bounds or a mismatched rendered/layout host origin, its unfinished draw is replaced with the exact saved pre-bank backdrop. The first usable frame is then shown at the configured size with matching input. This can briefly defer the opening until native geometry is ready, typically one render frame; it avoids a small native bank or clipped strip without inferring bounds from pixels. Bank input operations are gated during the withheld frame, including late icon drawing. The earlier working JAR was preserved before installation.
- Camera-button holds gate only widgets under the actual translated mouse/press coordinates. Holding middle mouse or Camera's right-camera button in the scene no longer repeatedly traverses and rewrites the inventory tree.
- GPU capture keeps underlying scene text and overheads separate from scaled widgets. Transparent inventory panels no longer enlarge a second copy of those labels, including with the HQ Item Icons compatibility path.
- Moving underlying labels no longer force an unchanged inventory through the scaling filter again. Empty underlay rows are skipped and overlapping captures restore their backdrop once.
- Larger GPU UI outputs now use bulk row copies over empty UI backgrounds, cached opaque/translucent runs when overlapping other UI, and vectorized comparisons for unchanged captured rows. This reduces the plugin's CPU cost when using larger sizes with Stretched Mode disabled.
- Separate sliders under **Additional UI sizes** control health bars, hitsplats, prayer icons, skull icons, overhead text, right-click menus, chat, hover text/tooltips, bank/GE/other central interfaces, and click markers. Fresh profiles default to 100%; existing overhead preferences initialize the five new indicator controls. Existing inventory/minimap/tab-bar sliders allow up to 400%.
- Automated checks cover the scaler/render/input lifecycle and HD UI compatibility. The user confirmed the earlier camera-hold, duplicated-overhead, central-window enlargement, click-alignment and responsive bank reflow fixes in-game, and reported the earlier layout-preparation version as the best so far. The experimental zero-bounds fallback was reported worse and remains reverted. The new item capture/update changes and opening-frame backdrop guard still need live confirmation.

## Version 0.4.1 fixes

- Select only the active layout's widgets, including the full classic inventory frame; inactive modern panels/bars cannot appear over it.
- Keep camera-drag mouse deltas continuous across resized controls and RuneLite Camera button remapping.
- Preserve native GPU inventory transparency and border colors through premultiplied-alpha extraction, filtering and redraw.

## Features

- **Minimap:** resize the map, compass, health/prayer/run/special-attack orbs and nearby buttons together.
- **Inventory / side panel:** resize the active inventory, spellbook, prayer, equipment or other side panel. In classic layout this includes the complete native inventory frame and its classic tab buttons; in modern layout it scales the open content panel. Each has move-left/up controls.
- **Modern layout tab bars:** independently resize and reposition the two rows of tab buttons.
- **Actor indicators:** independently resize native health bars, hitsplats (including numbers), prayer icons, skull icons, and overhead speech. Requires GPU/117 HD. Unrecognized or inseparable indicators keep their native size.
- **Right-click menus:** independently resize menus, including their selection and wheel-input coordinates.
- **Chat:** resize the chatbox, typing area and chat controls from the bottom-left corner.
- **Hover text / tooltips:** resize RuneLite tooltip contents near the visible cursor and the native top-left mouseover text.
- **Bank / GE / interfaces:** one shared size for windows attached to the main modal, CRM and floater hosts. Uses the visible window bounds rather than their full-viewport invisible parent layers.
- **Filtering & sharpening:** a separate settings section for the UI scaling filter, sharpening strength and shared scaling refresh limit; the minimap has an independent filter.
- **Click-through:** exposed original widget locations do not block scene clicks when a region is shrunk or moved.
- **Cursor-relative menus:** context menus from scaled controls appear at the visible cursor, with matching movement, selection and wheel-input coordinates.

Minimap and side-panel scaling support **Resizable - Classic** and **Resizable - Modern**. Tab-bar scaling supports **Resizable - Modern**. Fixed mode and unavailable/hidden containers are bypassed.

Layout selection uses RuneLite's active top-level interface ID. Cached widgets from another layout are never used as a fallback. Classic mode uses its native `SIDE_MENU` frame and ignores the modern panel and tab-bar settings. Changing layouts restores old input flags and drops the old image, drag/menu and input caches before processing the new root.

## Settings

| Section | Settings |
| --- | --- |
| Minimap | Size 50–400% (default 150%), move left, move down, independent minimap filter. |
| Modern layout tab bars | Upper/lower sizes 50–400% (default 100%), independent move-left/up controls. |
| Inventory / side panel | Size 50–400% (default 100%), move left, move up. |
| Additional UI sizes | Ten separate sizes, each 50–400%: health bars, hitsplats, prayer icons, skull icons, overhead text, right-click menus, chat, hover text/tooltips, bank/GE/interfaces, click markers. |
| Filtering & sharpening | UI scaling filter (minimap has its own), sharpening strength, shared scaling refresh limit. |

100% with zero offsets bypasses additional scaling for that region. The minimap keeps its top-right anchor; side panels and tab bars keep their bottom-right anchors. Size and position are clamped to the game buffer. Large regions can overlap other controls; overlapping scaled regions route input to the topmost one.

The login screen and the welcome screen (before clicking to play) automatically stretch to fill the game window. Mouse input follows the stretched screen. If RuneLite's built-in **Stretched Mode** is enabled, its settings handle these screens instead.

For example, lower the overall UI scale in RuneLite's built-in **Stretched Mode**, then set **Inventory / panel size** to **150%**. Global stretching still occurs afterward, while only the panel receives that additional enlargement. Settings are relative to the native game buffer, so the two layers of scaling combine.

Existing minimap, tab-bar and filtering preferences keep their original configuration keys.

### Additional UI sizes

All new controls support the two resizable layouts. The chatbox keeps its bottom-left anchor; central windows fit within the game canvas. Chat and interface clicks, scrolling, right-click actions and rectangular drags use the corresponding visible-to-native mapping. Dragging between a scaled bank and scaled inventory switches to the drop target's mapping while keeping both widget trees available for the native drag operation. Empty modal hosts do not create invisible input targets.

Bank/shared-bank hosts receive a smaller logical area immediately when enlarged above 100%; other central windows receive one when needed to fit. This is similar to Stretched Mode's layout-then-enlarge approach: larger bank items can be shown with fewer visible rows instead of scaling an already-viewport-height bank and stalling at about 129%. Applying the bank layout immediately also avoids displaying a preliminary size before its opening scripts finish sizing the body. The original physical host anchors the output, so shrinking the logical layout does not pull the enlarged window into a smaller corner of the screen. A minimum logical area of 512×350 (or the available area if smaller) retains room for native window chrome. The final fit still limits enlargement when the minimum native window cannot physically fit; it does not crop close or bottom controls to force a requested percentage.

Layout changes are coalesced instead of rewriting/revalidating the widget tree each frame. Bank layout restoration occurs at 100%, on closing/switching the window, resizing the canvas, changing layouts or disabling the plugin. Other central windows can also return to their natural layout when their requested size fits without reflow. Deliberate sizing changes from another script/plugin are preserved per axis. Fixed windows that already fit keep their native layout.

Central-window rendering hooks track the group attached to each host. The parent-layer callback saves the host background immediately before its attached group draws; the completed-group callback then obtains the actual rendered window bounds, extracts those pixels and computes their matching click transform. The capture is not tied to a predicted window rectangle, so an opening group can appear after the early UNDER_WIDGETS pass or change its position/size during drawing without deliberately presenting a smaller native frame. GPU host backgrounds are isolated before drawing and restored after extraction; unchanged empty rows are skipped. Software extraction uses the relevant portion of the saved host backdrop. HQ's late painter retains isolation only inside the completed window while the rest of the host is restored immediately. No previous window screenshot or opening-delay timer is used. Bounded `Interface opening scale`/missing-capture log entries record the first two opening callbacks so live behavior can be checked if the flash persists.

The attempted painted-pixel fallback for empty metadata has been removed, together with its final-pass retry. Live logs from the worsened transition show an inconsistent host origin: the first capture used y=0 and clipped the bank to a 99-pixel strip, while the next used y=116 and captured the full 626-pixel body. Layout revalidation previously occurred from inside native rendering callbacks, after screen-position caching could already have happened. BeforeRender now prepares active central layouts first; a regression checks the recorded 137% case with a centered 512×629 host and full 488×626 bank, including that later capture callbacks do not revalidate it again. Genuine missing metadata still avoids inventing a scaled input target. Runtime diagnostics should be read from `[Client]` log entries; `[Test worker]` entries belong to the automated suite. Opening entries show `host` and `layoutHost` to expose any remaining origin mismatch.

For bank/shared-bank opening frames that still lack metadata or have inconsistent host origins, the saved host backdrop is restored exactly after native/HQ drawing, before presentation. The scene remains visible until a frame can present the full bank. This is a readiness gate rather than a fixed timer, previous-window screenshot or pixel-derived transform. It may defer opening for a render frame (or until native initialization completes). The first capture includes both known host positions when their origins differ, so restoring the background does not leave an unscaled fragment behind. Invisible bank operations are excluded until the ready image and its input transform are published together. Ordinary stable frames retain the improved preparation and capture path. One bounded waiting diagnostic is followed by the usual two completed opening-frame diagnostics.

The interface slider covers central windows such as bank, Grand Exchange, shops, trade, settings, and other interfaces opened in those hosts; the inventory-side component keeps the inventory slider. HUDs, full-screen world maps, separate plugin overlays and custom interfaces outside those hosts do not automatically become central windows.

The five actor-indicator sliders capture only the native overhead pass between RuneLite's ABOVE_SCENE and UNDER_WIDGETS layers. Prayer/skull recognition uses the active actors' actual cache sprites; health bars use loaded front/back sprite pairs or the standard red/green strip. Actor screen positions, active overhead speech and hitsplat expiry times narrow text/splat recognition. These last two categories use conservative screen-region classification rather than separate native draw hooks. Occluded/custom sprites, fading bars and ambiguous text/splat overlaps may remain at their native size. Recognized icons and bars are separated before the remaining text/splat pixels are clustered, so adjacent categories do not share a size. Each recognized element scales about its bottom center. RuneLite scene labels and hint arrows retain their original size. Native-art resampling observes the scaling refresh limit. Capture is bypassed at all-100% sizes unless HD UI supplies replacement prayer artwork; HD sources are sampled directly at the requested size without throttling state changes. Software rendering bypasses this scaling because it has no separate GPU UI alpha layer. The hidden legacy overhead preference initializes each new control without overwriting existing individual preferences.

The click-marker slider resizes the native yellow walking/running and red interaction animation frames around the click location. RuneLite buff/ammo/timer infoboxes already have a **RuneLite → Overlay settings → Infobox size** setting in pixels, plus separate **Infobox font** and **Interface font** settings. Infobox size changes the box dimensions; it is not a uniform icon/text zoom. Full plugin-overlay scaling is separate from the native widget and actor-indicator controls here.

**Responsive Movement compatibility:** its custom local-player health bar, hitsplats, prayer, and skull render before the native overhead capture. Updated versions use a synchronous `responsive-movement` / `render-overhead` PluginMessage to resize each typed element at the smoothed character position, including each hitsplat's number. Native artwork uses the existing filter/sharpening and refresh limit. When HD UI supplies prayer artwork, Responsive Movement forwards the original-resolution source and this plugin samples it directly, including at 100%. With this plugin disabled, Responsive Movement draws that artwork at its normal logical icon size. The positioned result is preserved as an underlay of the later native pass, preventing recognition or scaling a second time. Overhead speech retains its existing handling.

The hover slider uses RuneLite's existing tooltip contents and background preference, follows its above/below-cursor preference, and clamps the scaled result onto the canvas. Its texture starts small and grows for large custom tooltip panels. The native top-left mouseover text is drawn using a resized RuneScape font. At 100% or on shutdown, the standard tooltip painter and native mouseover preference are restored. Menus and hover tooltips update immediately rather than delaying highlight changes behind the widget scaling refresh limit.

## Filtering & sharpening

### HD UI item sharpness at fractional sizes

The updated paired HD UI plugin prepares item icons for their exact, pixel-aligned output rectangles and draws them 1:1 after panel filtering. This removes an extra bilinear resampling pass at fractional panel sizes, including 110–190% and 174/186%. Stack quantities use the same aligned rectangle; widget layout and click targets keep their existing scaling.

There is no universal sharpest percentage in every 10% interval: alignment depends on the panel's actual rounded dimensions, slot position, layout and any later global stretching. Choose the size you prefer with the updated HD UI. Global **Stretched Mode** and the GPU/117 HD final UI filter can still soften the composed result; integer final scaling (such as 1× or 2×) is a useful baseline for comparison. 174% is not a guaranteed special value.

The development build now offers **eight filters**, including experimental **xBR**, **Hybrid**, **Mitchell Bicubic**, and **Lanczos 3**. Existing filter preferences keep their meaning; the old Bicubic option is now labelled Bicubic (Catmull-Rom).

| Filter | Appearance |
| --- | --- |
| Nearest neighbor (pixel sharp) | Crisp original pixels; more blocky at fractional sizes. |
| Bilinear (smooth) | Softer edges; the former Smooth scaling option. |
| Bicubic (Catmull-Rom) | The original Bicubic filter; retains sharper edges than Bilinear. |
| Sharp Bicubic | Bicubic plus adjustable local-contrast sharpening. |
| Bicubic (Mitchell) | A softer cubic filter to compare against Catmull-Rom, with less pronounced ringing. |
| Hybrid (crisp edges) | Reduced first-pass smoothing: narrower boundary transitions with more of the original pixel retained, to reduce softness when the renderer filters the panel again. Integer enlargements match nearest neighbor. |
| Lanczos 3 (fine detail) | Wider sampling for fine detail; also filters out high-frequency aliasing when shrinking. Can produce ringing near strong edges. |
| xBR (experimental) | CPU adaptation of xBR level 2; reconstructs diagonal pixel-art edges. Uses Catmull-Rom when either dimension is reduced. |

### Trying these with 117 HD or GPU

Keep inventory size fixed when comparing filters, and note both the plugin filter and the renderer's UI scaling mode. To check the revised Hybrid, select **Hybrid in both Inventory & Minimap Resize and 117 HD/GPU**. Check item outlines, quantity numbers, spell/prayer icons and panel borders. The same plugin options can be used with GPU and its UI scaling modes; they do not require renderer-specific integration.

Hybrid's first-pass transition is now half as wide and uses half the previous blending amount. This also reduces intermediate grey pixels at 150% panel size, where narrowing the transition alone would leave exact boundary samples unchanged. Fractional sizes look more pixel-defined while retaining some edge blending. This is a deliberately crisper prefilter, not a copy of the renderer's final Hybrid filter. It applies automatically when Hybrid enlarges a panel; no extra setting is needed. Shrinking retains the previous bilinear behaviour.

These filters still run **before** the renderer's final stretch. Choosing xBR here does not guarantee the same pixels as using Stretched Mode alone. This is an experimental quality comparison, and live GPU/117 HD appearance still needs checking. The xBR implementation uses premultiplied-alpha blending and cached per-source-pixel corner classification. Like the other filters, it reuses unchanged images and respects the refresh limit.

**Minimap filter** is separate because camera rotation changes most of the map every frame. If no minimap-specific preference is saved, Nearest, Bilinear and Hybrid follow the UI filter; other UI filters use Bilinear on the minimap. Explicitly choosing a minimap filter saves that independent preference. For smoother camera movement, start with **Bilinear**; **Nearest** is the cheapest and more pixelated. You can retain xBR/Lanczos/Sharp Bicubic on the inventory and select any of them explicitly for the minimap if preferred.

For **Sharp Bicubic**, start with **40% sharpening strength**. Increase toward 60% for more contrast or lower toward 20% for softer edges. At 0%, Sharp Bicubic matches Catmull-Rom. This setting affects only Sharp Bicubic; the other filters ignore it. High strengths can produce edge halos. Original-size images, including position-only changes, bypass filtering to preserve their pixels.

**Scaling refresh limit** defaults to **60 fps**. It limits expensive resampling, not game FPS or mouse-input processing. Cached images are still drawn every game frame. A newly shown control or size/filter change refreshes immediately. Set 0 for unrestricted resampling or lower the limit to 30 for additional CPU headroom.

This value is **updates per second, not milliseconds between updates**. Setting 120 allows more work than 60; use 30 when trying to reduce CPU usage. The setting applies to every scaled region, including the minimap.

## Performance

### Overhead sizes with native-size inventory, chat and minimap

Overhead controls remain active independently of the widget sizes. Previously, enabling any overhead size triggered repeated scalar full-viewport recognition passes, including in scenes with no overhead indicators. The revised path checks empty rows in bulk, skips actor collection on an empty native layer, and confines subsequent copying, recognition and clustering to occupied bounds with enough padding for transparent sprite borders. Actor projections are requested only for active speech, health bars and hitsplats. Input-gate tasks are also skipped on mouse movement when no widget transforms are published.

Same-machine synthetic native-overhead capture/recognition/scaling timings with xBR, 140–150% overhead sizes and a 30 Hz resampling limit:

| Canvas / workload | Before | After | Before / after p95 |
| --- | --- | --- | --- |
| 1920×1080, empty native pass | 1.98 ms/frame | 0.37 ms/frame | 2.97 / 0.48 ms |
| 1920×1080, moving indicators | 5.75 ms/frame | 0.48 ms/frame | 7.12 / 0.64 ms |
| 2560×1440, empty native pass | 11.55 ms/frame | 0.68 ms/frame | 14.22 / 0.88 ms |
| 2560×1440, moving indicators | 11.34 ms/frame | 0.77 ms/frame | 14.28 / 1.08 ms |

Run `./gradlew.bat overheadBenchmark` with JDK 17 to reproduce the current path (200 warmup and 400 measured frames per case). These measurements include synthetic canvas clearing and native indicator drawing; they exclude live actor collection and 3D rendering and are not measured game FPS. Crowded scenes with indicators spread across the viewport still cover a larger recognition area. Regression coverage includes padded sprite borders, subimage strides/coordinates, movement after empty frames, reused scratch pixels, empty-pass backdrop restoration, lazy actor projections and native-size widget bypass alongside other active size settings.

### Widget filtering

The latest development build reuses unchanged **source and filtered rows**, so an item animation, hover change, or quantity update no longer automatically re-filters the entire inventory. It updates every neighboring row required by the chosen filter, including xBR's two-row neighborhood and Sharp Bicubic's sharpening support. Deferred changes are compared against the last processed pixels, so changes made during the refresh-limit interval are retained. Output remains pixel-identical to a full resample in randomized regression tests across all filters, enlargement/reduction, straight and premultiplied alpha, and transparent edge changes.

A same-machine synthetic **200% inventory** benchmark with native GPU alpha, one 36×32 slot changing every frame, and unlimited scaling updates measured:

| Filter | Before row reuse | After row reuse |
| --- | --- | --- |
| Bilinear | 1.96 ms/frame | 0.59 ms/frame |
| Sharp Bicubic | 5.93 ms/frame | 1.12 ms/frame |
| Lanczos 3 | 6.31 ms/frame | 1.11 ms/frame |
| xBR | 10.82 ms/frame | 1.91 ms/frame |

These are CPU workload timings, not measured in-game FPS. They exclude HQ, native widget traversal, and GPU rendering. A full-panel change still requires full resampling; a moving minimap can cost considerably more than a mostly unchanged inventory. The benchmark's default minimap animation changes only one pixel per frame and must not be treated as a moving-scene measurement.

The performance build passes all **190 automated tests**, including real RuneLite event-bus registration and lifecycle, partial-update equivalence, changes accumulated while refresh-limited, off-inventory input gating, held camera buttons, opaque overheads behind transparent panels, large-output blit equivalence, bulk GPU capture equivalence, chat/interface input, native parent-before-nested-interface drawing order across GE/bank/shop/trade and both layouts, interface-group switches and closing, completed bounds changing after host capture, initially unfinished bank sizing, groups appearing after the early capture pass, transparent GPU host isolation and software snapshot extraction, exact GPU/software backdrop restoration for unready frames, invisible-opening input gating, mismatched-host suppression followed by the full bank, preparation before native position caching in the recorded 137% bank case, late HQ window pixels, responsive bank reflow past the old 129% cap to 200% with aligned close/bottom controls, repeated first-frame bank openings despite stale render positions, first-frame GE/shop/trade positioning, centered-host projection and scroll offsets, native layout restoration and external sizing changes, scaled menus/submenus, overhead isolation, tooltip sizing and preference restoration. Minimap-specific coverage checks the lightweight defaults, independent map/inventory filters, immediate filter changes despite refresh limiting, full-frame pixels and input alignment in both layouts with software/GPU alpha. Configuration tests exercise the real proxy, default storage, settings-panel construction and dropdown edits for fresh and upgraded profiles. Live FPS and frame pacing still need comparison in the same scene and renderer.

### Camera rotation and the minimap

The `benchmark.rotatingMinimap` workload rotates the entire 150-pixel circular map within its 240×180 cluster every frame, leaving the surrounding controls static. This exercises continuously changing map rows rather than the old single-pixel animation. A same-machine **200% minimap, GPU-alpha, unlimited scaling updates** run with 1,000 warmup frames and 500 measured frames gave:

| Minimap filter | Mean CPU time/frame | 95th percentile |
| --- | --- | --- |
| Nearest | 0.34 ms | 0.38 ms |
| Bilinear | 1.30 ms | 1.32 ms |
| Hybrid | 1.93 ms | 1.98 ms |
| Sharp Bicubic | 3.87 ms | 3.95 ms |
| Lanczos 3 | 3.97 ms | 4.04 ms |
| xBR | 7.48 ms | 7.72 ms |

These compare **different filter quality/cost choices**, not a same-filter algorithm speedup. The new default uses the lightweight choice automatically when the shared UI filter is expensive. Filtering runs on the render thread, so extra map work can hurt camera frame pacing even when an FPS counter looks acceptable. Timings include synthetic map drawing, capture, filtering, blitting and input-frame publication; they exclude live scene rendering/native widget traversal and do not establish an in-game smoothness gain.

To reproduce in PowerShell, set `$env:JAVA_TOOL_OPTIONS = '-Dbenchmark.gpu=true -Dbenchmark.rotatingMinimap=true -Dbenchmark.minimapOnly=true -Dbenchmark.scale=200 -Dbenchmark.warmup=1000 -Dbenchmark.frames=500'`, run `.\gradlew.bat benchmark --offline --console=plain`, then clear the temporary setting with `Remove-Item Env:JAVA_TOOL_OPTIONS`. Each row explicitly measures the named filter; the benchmark does not apply the plugin's default-selection policy.

For a live check, restart with the rebuilt plugin, select **Minimap → Minimap filter → Bilinear**, keep the same map size/renderer/scene, and repeatedly turn the camera. Compare Nearest and your previous expensive filter, then check minimap walking, compass/orb clicks and camera holds across the enlarged map. Keep the scaling refresh limit fixed during this comparison: lowering it reduces CPU work but also lowers the map's own visual update rate.

**Off-inventory cursor performance:** ordinary scene movement now skips widget-tree traversal and input-property writes for scaled regions whose original bounds do not contain the cursor. Previously, leaving the enlarged inventory caused its tree to be repeatedly excluded/restored even when the cursor was far away in the scene; the scaling refresh limit does not govern that work. Exposed original widget locations still receive click-through handling. The gate cache tracks the actual excluded regions, so crossing their boundaries updates it even when both positions are scene targets. Ordinary pending presses, item drags and menus retain conservative gating. Camera holds check their fixed native-coordinate translation and retain the pending press's location until consumed, so an unmoved scene camera press does not activate the inventory gate. Widgets no longer receive writes for input properties already at their disabled values. Tests exercise repeated render/script cycles without inventory mutations, both camera buttons and layouts, translated boundary crossings, and pending scene/camera clicks; the reported live FPS difference still needs retesting.

To repeat this workload in PowerShell, set `$env:JAVA_TOOL_OPTIONS = '-Dbenchmark.gpu=true -Dbenchmark.animatedInventory=true -Dbenchmark.scale=200'`, run `.\gradlew.bat benchmark --offline --console=plain`, then clear that temporary setting with `Remove-Item Env:JAVA_TOOL_OPTIONS`.

The shared render path uses direct int-buffer row copies, precomputed separable fixed-point interpolation, cached unchanged filtered images, and reused hit masks. Widget-tree traversal is reused within each client tick and invalidated before scripts can recreate widgets. Mouse-triggered input updates are coalesced.

In an earlier same-machine synthetic test at 150% with Sharp Bicubic, the animated minimap workload fell from about **6.36 to 3.17 ms/frame**, and mostly-static tab bars from **2.74 to 0.16 ms/frame**. The expanded-filter development build measured these averages at 150% with the refresh limit disabled:

| Filter | All four regions, animated minimap | Mostly-static inventory alone |
| --- | --- | --- |
| Sharp Bicubic | 3.85 ms/frame | 0.37 ms/frame |
| Mitchell | 2.38 ms/frame | 0.45 ms/frame |
| Hybrid | 2.34 ms/frame | 0.36 ms/frame |
| Lanczos 3 | 4.11 ms/frame | 0.40 ms/frame |
| xBR | 8.10 ms/frame | 0.46 ms/frame |

These are CPU capture/filter/input timings, not in-game FPS. They exclude native widget traversal, scene rendering and GPU costs. Inventory content changes only once every 80 benchmark frames, so its average includes substantial cache reuse and does not represent the cost of refreshing a changed panel. xBR is the most expensive option for continuously changing regions; the refresh limit can reduce that cost.

Run it with `gradlew.bat benchmark`. Actual FPS depends on the renderer, sizes, filter, scene and hardware; compare settings in the same scene.

### Large sizes with Stretched Mode disabled

Disabling Stretched Mode increases the native game-buffer dimensions and the resolution at which the renderer draws the 3D scene. Increasing an individual UI size also increases its output area: 400% produces sixteen times as many output pixels as 100%. The large-output optimizations reduce the plugin's drawing/capture work; they do not reduce the renderer's 3D resolution.

A same-machine synthetic **3840×2160 canvas, 400% mostly-static transparent inventory** comparison measured:

| Filter | Per-pixel output blit | Cached/bulk output blit |
| --- | --- | --- |
| Sharp Bicubic | 2.48 ms/frame | 2.01 ms/frame |
| Lanczos 3 | 2.43 ms/frame | 2.19 ms/frame |
| xBR | 2.55 ms/frame | 2.24 ms/frame |

These timings include clearing the full synthetic canvas, simulated widget drawing, capture, scaling, output drawing and input-frame publication. Both sides use the new capture-row optimization; this comparison isolates the output-blit change. Content changes only three times in the 250 measured frames, so this is cached-image redraw performance rather than continuously animating inventory performance. The timings exclude live scene rendering, HQ and native widget traversal and do not establish an in-game FPS gain.

To reproduce, set `$env:JAVA_TOOL_OPTIONS = '-Dbenchmark.gpu=true -Dbenchmark.scale=400 -Dbenchmark.width=3840 -Dbenchmark.height=2160 -Dbenchmark.inventoryOnly=true'` and run `.\gradlew.bat benchmark --offline --console=plain`. Add `-Dbenchmark.legacyBlit=true` for the per-pixel output path, or `-Dbenchmark.animatedInventory=true` for an item slot changing every frame. Clear the temporary environment setting afterward with `Remove-Item Env:JAVA_TOOL_OPTIONS`.

### Moving overheads behind a transparent inventory

A synthetic **200% transparent inventory** workload now includes opaque labels moving underneath it every frame. With unrestricted filtering, the old mixed-background capture refreshed the inventory **250 times in 250 measured frames**; isolated capture refreshed it **3 times**, corresponding to actual inventory-content updates.

| Filter | Mixed-background capture | Isolated GPU capture |
| --- | --- | --- |
| Nearest neighbor | 0.47 ms/frame | 0.56 ms/frame |
| Bilinear | 0.83 ms/frame | 0.56 ms/frame |
| Sharp Bicubic | 3.25 ms/frame | 0.57 ms/frame |
| Lanczos 3 | 3.82 ms/frame | 0.57 ms/frame |
| xBR | 2.24 ms/frame | 0.57 ms/frame |

These are same-machine synthetic CPU timings, including capture, simulated native UI drawing, underlay restoration, scaling, blitting and hit-frame publication. They exclude native widget-tree traversal and live GPU rendering. Isolating/restoring the underlay has a cost, visible with the inexpensive Nearest filter; the more expensive filters benefit from avoiding false updates. These numbers do not establish an in-game FPS or frame-pacing improvement.

To reproduce, set `$env:JAVA_TOOL_OPTIONS = '-Dbenchmark.gpu=true -Dbenchmark.overheads=true -Dbenchmark.scale=200'` and run `.\gradlew.bat benchmark --offline --console=plain`. Add `-Dbenchmark.legacyCapture=true` to compare the old mixed-background algorithm. Clear the temporary environment setting with `Remove-Item Env:JAVA_TOOL_OPTIONS` afterward. This benchmark also reports the number of actual filter updates.

### Inventory transparency

Native GPU UI pixels are handled as premultiplied alpha throughout capture, filtering and redraw, regardless of how the Java image wrapper describes them. The resampler does not multiply those colors by alpha again, and a direct premultiplied source-over blit preserves their native alpha instead of passing them through a mismatched Java2D color model.

After the UNDER_WIDGETS overlays, GPU capture saves and clears the backdrop beneath the scaled source regions before native widget drawing. Widgets are captured against that transparent buffer. The saved backdrop is restored beneath any remaining unscaled interfaces before the scaled outputs are drawn. Opaque overhead text cannot be reliably separated from a translucent inventory using a single already-blended pixel; this isolation keeps it out of the inventory image in the first place. Overlapping captures share one underlay restoration, and missing widget/output hooks have an end-of-render restoration path. Software rendering keeps its existing comparison-based extraction.

Regression tests cover the translucent interior and opaque border separately in **both classic and modern layouts**, with every filter, RGB/ARGB/premultiplied wrappers, existing translucent UI underneath, renderer-format changes, opaque overheads and their translucent edges, HQ's late painter, overlapping source regions and skipped render hooks. The inventory transparency setting is not overridden. Compare 100% and an enlarged/shrunk size in the same scene for live confirmation of the corrected rendering.

## Right-click menus and input

Menus retain their native actions and have their own size slider. At 100%, they retain their normal size. Their visible position uses ordinary cursor-relative placement and screen-edge clamping. Their mouse input is translated and, when enlarged, inverse-scaled into the native bounds, preserving selection, scrolling and the close margin. Scene menus use the same size control; menus are not tied to the size of the underlying inventory or bank.

RuneLite has no public native-menu position setter, so the implementation captures the background before native menu drawing, restores the original location, and redraws the menu at the visible position using the selected shared filter. A BeforeMenuRender capture includes tooltip pixels drawn later than the initial overlay capture. Visible submenu pixels use the same transform as the root; the combined area is fitted on screen when enlarged. Menu entries and callbacks are not replaced. While holding a camera button, menus temporarily keep the gesture's fixed native-size translation so camera deltas remain continuous; the configured menu size resumes after release.

The side panel, chat and central window rectangles stay interactive so empty slots and transparent controls support dragging and scrolling. Minimap/tab-bar hit masks follow visible coverage. Left-button rectangular drags use the captured transform until release, switching to another scaled rectangular control when it becomes the drop target.

### Camera dragging

A held camera drag keeps a fixed mouse-coordinate translation instead of switching between scene, inventory, tab-bar and minimap mappings. Mouse deltas remain one-to-one in game-buffer coordinates for the gesture, including when it starts over a scaled control. Normal widget/menu routing resumes after release; focus loss or a missed release clears the captured gesture.

The native middle-camera toggle and RuneLite Camera's right-click settings are read on the client thread. The listener runs after button-remapping plugins, including when they are enabled later, and recognizes the native Alt/middle and Meta/right interpretation. Concurrent right-camera/menu input uses the same fixed coordinate space as the displayed menu during the hold, then ordinary cursor-relative menu placement resumes after release.

For the live check, hold the camera button in the scene, cross the inventory, both modern bars and the minimap repeatedly, and release over a scaled control. Also start a drag over a control, test the camera button/remapping you normally use, and check that ordinary item dragging and right-click menus still work afterward.

## Development

Use **JDK 17**. This project compiles Java 11-compatible plugin classes and uses RuneLite's latest release by default. It has no extra runtime dependencies and uses the Plugin Hub's `build=standard` mode.

On Windows, run `run-plugin.bat` to open a development RuneLite client with the plugin loaded. It selects the installed JDK 17 paths on the original development machine; set `MINIMAP_JAVA_HOME` to override them on another machine. Find **Inventory & Minimap Resize** in the plugin list and open its settings.

From PowerShell with `JAVA_HOME` set to JDK 17:

```powershell
.\gradlew.bat build
.\gradlew.bat benchmark
.\gradlew.bat run
```

To test a particular RuneLite version:

```powershell
.\gradlew.bat build "-PruneliteVersion=1.13.1"
```

Plugin JAR: `build/libs/inventory-minimap-resize-0.4.6.jar`.

Test report: `build/reports/tests/test/index.html`.

Restart the development client after rebuilding; toggling the plugin does not reload compiled classes.

## Compatibility and verification

### HQ Item Icons

The development build includes an automatic, local compatibility path for HQ Item Icons' GPU rendering. No update to HQ Item Icons is required for the implementation referenced by its Plugin Hub branch (commit `9866e78c3698bbe69c68645a2066ae63ee1c03dc`).

HQ clears item patches during interface drawing and normally fills them in an `ALWAYS_ON_TOP` pass. While the side panel is scaled or moved, Better Stretched Mode temporarily schedules that existing painter itself, after HQ's dragged-item preparation but before capturing and scaling the panel. Icons and restored translucent panel pixels therefore enter the same scaling pass. The painter stays registered for HQ's shutdown handling, is called only once per frame, and its original layer is restored when compatibility is no longer needed or Better Stretched Mode stops. Detection uses the known painter class name and RuneLite's public overlay API; there is no reflection or HQ runtime dependency.

`gradlew.bat clean build --offline --console=plain` passes all **103 tests**. The new tests simulate HQ's late pixel writes and cover both layouts, icon enlargement and movement, transparent panel pixels, removal of native-position ghosts, input alignment, enable/disable order, and restoration without resurrecting removed overlays. They do not run the real HQ plugin or a live GPU renderer.

For live verification, restart the development client, enable both plugins, and compare inventory sizes of 100%, 150%, and 200% with transparency enabled in both resizable layouts. Check item dragging, quantity text, bank-side inventory, right-click menus, and toggling either plugin. Dragged icons outside the captured panel retain the existing native-position limitation. Because this path captures the panel later, overlapping interfaces/overlays also need a live check. Future HQ changes to the painter class or rendering stages may require updating this integration.

### General compatibility

The scaling controls, click-through, cursor-relative root menus, layout-selection, camera-drag and transparency fixes have been reported working in-game by the user. The expanded-filter development build and **all 101 automated tests pass against RuneLite 1.13.1**. Tests cover rendering/translucent interiors and borders across all eight filters, xBR diagonal smoothing, Hybrid pixel boundaries and thin-stroke contrast through a simulated second standard Hybrid pass, Mitchell impulse response, Lanczos reduction, filter/cache switching, thin images, cached inactive-layout widgets, both layout-switch directions, camera gesture continuity/button remapping, coordinate/drag/menu routing, screen-edge placement, cached image/mask alignment, refresh limiting, native input-property restoration and buffer-format fallbacks. The user reports good results with Lanczos and xBR and excessive softness with the earlier Hybrid. The revised Hybrid still needs live visual confirmation in GPU/117 HD.

- Separate RuneLite overlays, such as minimap tile markers or item overlays, are not individually rescaled and may be covered or misaligned.
- Minimap/inventory/chat scaling keeps native widget dimensions. Responsive central-window hosts can temporarily reflow into a smaller logical area before image scaling; their sizing parameters are restored when that reflow is no longer needed. Input follows the resulting rendered-image transform.
- The software renderer has no separate UI alpha layer. Background comparison can produce artifacts on matching colors or translucent details. GPU and third-party renderers should be tested individually.
- A dragged icon outside the scaled panel can still be drawn at its native location. Alt-drag widget management and other input-transforming plugins need individual testing.
- Submenu-heavy plugins and menus near screen edges need live checks. Menus retain native submenu layout, with their pixels translated alongside the root.

After changes, verify both resizable layouts, item actions and dragging, spells/prayers/equipment, tab buttons, minimap walking/orbs, exposed original locations, context-menu selection/scrolling, window/layout changes, login/logout, disable/re-enable, and operation with built-in Stretched Mode. For the layout fix, keep non-default modern tab-bar settings, switch to classic, enlarge the inventory frame, and confirm no modern panel/bars appear. Switch back to modern and repeat. Compare FPS with each region separately and together, holding the scene and other settings constant.

## License

Original plugin code is licensed under the full **BSD 2-Clause License**, attributed to **PHYSIQUE-sys (formerly GimmitheBong)**. Its text matches the Plugin Hub's license template except for copyright attribution. It is available in [`LICENSE`](LICENSE) and packaged in the plugin JAR as `META-INF/LICENSE`.

The Gradle wrapper keeps its original Apache notices and license. The xBR adaptation carries Hyllian's MIT notice, and the Hybrid mapping is adapted from Casey Zwicker's CC0 shader. Their notices are included in the plugin JAR under `META-INF/licenses/`. See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) and [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt).
