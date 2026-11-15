# Native capture and HD UI compatibility

## Approved painted tabs (0.4.7)

HD UI 0.2.31 supplies 23 selected paintings with native layout aliases. The classic sidebar route includes its frame icons and the journal. Modern tab strips use distinct logical painter scopes (`-62002` upper, `-62003` lower); they must not be identified only by their shared native toplevel interface ID. These scopes are advertised only when the corresponding visible strip needs scaling or translation. Independent post-filter passes use each region's own source/destination transform and cached HD layer. HD UI limits frame traversal to the frame's native group and suppresses only selected artwork IDs, leaving button backgrounds and nested content native to their own routes.

The newly-equipped worn-icon delay was traced to the native slot remaining hidden until the next client cycle. The user confirmed the native-listener correction works independently and with both plugins. **HD UI → Instant worn equipment icons** now owns that correction. Its existing artwork/rendering checkboxes can all be turned off to use just the fix.

The resizer retains generic rendering guarantees:

- Capture completed native side-panel contents after item overlays finish drawing.
- Invalidate item-sensitive scaler caches once after container changes, and the sidebar cache once after a tab switch. The scaler consumes this invalidation on its next actual update, then resumes the configured refresh limit.
- Keep authored HD artwork separate from panel filtering and scoped independently for sidebar/central windows.
- Keep native input mapping, premultiplied-alpha capture, scene underlays, camera-drag behavior and optional Responsive Movement overhead handoff covered by regression tests.

The earlier item/tab revision counters and misleading claims that resizer capture changes fixed native worn-slot visibility have been removed. Resizer startup version is recorded in `client.log`; tests use a console-only logging configuration.

After an update, compare native and 186%/202% sidebar sizes, both resizable layouts, software/GPU rendering and a 1-FPS panel limit. Test the integrated HD UI fix first with artwork options off, then with any desired artwork features enabled. Check item quantities, selection, dragging and already-correct worn slots.

## Native-path performance review (2026-10-29)

**Follow-up:** The Bilinear/Hybrid optimization described in this section was reverted in 0.4.13 after a reported camera-smoothness regression. These initial filter measurements used Java 17; the follow-up below uses the installed RuneLite Java 11 runtime. The overhead recognition optimization remains in place.

HD UI is not required for the resizer's CPU capture, filtering, blending or overhead recognition paths. The scaling refresh limit throttles filter updates, not native capture, recognition, input or drawing. Overhead recognition is bypassed when all five overhead sizes are normal and no HD prayer provider is active.

- Bilinear/Hybrid now have a dedicated two-tap pass. Their nonnegative weights permit packed intermediate pixels rather than four integer channels, reducing horizontal-buffer storage by 75% and avoiding unused cubic taps/clamps. Cubic and Lanczos still retain signed intermediate channels for overshoot.
- Overhead recognition now bounds scalar scans to occupied row spans. Empty rows are checked with array comparisons, and only previously occupied scratch spans are cleared. This avoids scanning/copying a nearly full viewport simply because two small overhead icons are far apart. Regression coverage includes subimages, moving/disappearing text and bars, and transparent sprite borders.

Synthetic CPU measurements on the development machine (not in-game FPS):

| Scenario | Before | After |
| --- | ---: | ---: |
| Rotating 200% minimap, Bilinear | 1.397 ms/frame | 1.138 ms/frame |
| Rotating 200% minimap, Hybrid | 1.388 ms/frame | 1.198 ms/frame |
| Rotating 200% minimap, Bicubic control | 1.928 ms/frame | 1.938 ms/frame |
| Widely separated overheads, 1920×1080 | 7.048 ms/frame | 0.826 ms/frame |
| Widely separated overheads, 2560×1440 | 13.954 ms/frame | 2.161 ms/frame |

The minimap benchmark includes synthetic terrain generation and buffer clearing, updates filtering every frame, and excludes native widget traversal. Run each filter in a fresh JVM to avoid cross-filter JIT profiling effects. In PowerShell:

```powershell
$env:JAVA_TOOL_OPTIONS='-Dbenchmark.gpu=true -Dbenchmark.rotatingMinimap=true -Dbenchmark.minimapOnly=true -Dbenchmark.scale=200 -Dbenchmark.warmup=1000 -Dbenchmark.frames=1000 -Dbenchmark.filter=BILINEAR'
.\gradlew.bat benchmark --offline --console=plain
$env:JAVA_TOOL_OPTIONS='-Dbenchmark.spreadOverheads=true'
.\gradlew.bat overheadBenchmark --offline --console=plain
Remove-Item Env:JAVA_TOOL_OPTIONS
```

## Open-panel follow-up (0.4.13)

The saved profile used xBR for the 194% side panel, with a separate Bilinear minimap filter. The previous xBR dirty-row optimization still reclassified and redrew entire rows for a small changing icon or number. The scaler now tracks the first/last changed pixel per row, expands the affected xBR neighborhoods by two pixels in both directions, and redraws only corresponding output spans. Changes accumulate against the last filtered image across refresh-limited frames. Full updates still occur for new sources, sizes and filter settings.

The animated-slot benchmark at 194%, running on RuneLite's Java 11, measured 0.897 → 0.538 ms/frame, p95 1.357 → 0.789 ms, and p99 1.692 → 0.956 ms. Static-panel runs were about 0.18–0.20 ms/frame. These are synthetic capture/filter/draw costs with filtering unthrottled, not a diagnosis of the user's total FPS loss. Native widget drawing, input traversal, other plugins and the 3D renderer are not represented in this benchmark. Sparse-update regression tests compare against complete filtering, including transparent pixels, subimages, boundaries, fractional scales and changes deferred by the FPS limit.

Benchmark tasks accept `-PbenchmarkJava=<path to java.exe>` and tests accept `-PtestJava=<path to java.exe>` to select the client runtime independently of the Gradle JDK.

For in-client measurement, enable **Filtering & sharpening → Log render timings**. It records one `Resize timings:` line in `client.log` every 10 seconds, including mean stage time per frame, maximum time per call, frame intervals and counts of panel changes/filter updates. Compare at least 20 seconds with tabs closed, a panel open, and camera movement. `OUTPUTS` includes the panel capture/filter/blit substages and compatibility painters; do not sum it with its substages. Frame intervals include the rest of the client and are not resizer-only CPU times. Diagnostics are off by default and do not read the clock or format messages while disabled.

## HD-painted minimap camera-motion follow-up (resizer 0.4.14 / HD UI 0.2.41)

The separate HD minimap terrain path was passing an unlimited refresh rate to the scaler instead of the configured scaling limit. Camera rotation therefore filtered terrain every client frame, unlike the non-HD path. It now honors the same limit; native control/text updates remain immediate and stencil changes force a fresh terrain image. Raw/UI/terrain scratch arrays are reused, and the projected clipping mask is rebuilt only for geometry/stencil changes rather than recalculating its coordinates on every terrain update.

HD UI's compass retains its 3× supersampled rotation, but its exact integer-ratio downsampling now uses reusable buffers and integer footprint averaging. Randomized alpha tests verify pixel equality against the previous general footprint reducer. Native and final-size material samples are cached separately, preventing repeated source-image reduction when the two render sizes alternate. When global Stretched Mode accepts the final compass route, the resizer no longer renders a redundant invisible native preview; the minimap control preview is still maintained for click masks.

On the installed Java 11 runtime, the compass **downsampling step alone** measured:

| Output size | Previous | Updated | Previous temporary allocation | Updated |
| --- | ---: | ---: | ---: | ---: |
| 51×51 | 0.285 ms | 0.144 ms | 112.5 KiB/update | ~0.1 KiB/update |
| 70×70 | 0.492 ms | 0.270 ms | 211.2 KiB/update | ~0 KiB/update |
| 105×105 | 1.110 ms | 0.617 ms | 474.4 KiB/update | ~0 KiB/update |

These are synthetic CPU measurements, excluding the rotation, renderer and other plugins. Reproduce from the HD UI project with `gradlew.bat compassBenchmark -PbenchmarkJava="<RuneLite Java 11 java.exe>" --offline`. Test camera motion with HD painted minimap enabled, both with and without global Stretched Mode; confirm compass orientation, native orb values, theme borders and clicks remain correct.

## Paired-pipeline audit (resizer 0.4.15 / HD UI 0.2.42)

The 1-FPS reproduction prompted a complete review of the shared capture/painter/message/presentation path, rather than another filter-only benchmark. Confirmed issues addressed:

- **Native mask semantics:** the client's sprite mask draws a span between each row's first and last transparent pixels, including a full span for an entirely opaque row. Terrain capture now uses those spans while final clipping retains the visual per-pixel stencil. Previously, changing terrain behind chrome could remain in the residual UI layer, invalidating its unthrottled filter. Tests cover opaque rows, internal stencil pixels, clipping and unchanged residual UI during terrain changes.
- **Native-sized output:** at 100%, terrain and residual UI are recombined for one draw. Returning to an enlarged size invalidates its old filtered cache. Unchanged identity scaling no longer advertises a fresh image and invalidates dependent blit/mask caches every frame.
- **Pixel conversions:** GPU captures remain premultiplied through extraction and scaling; only the small glyph/partial-alpha samples requiring straight colours are converted. Terrain copies use packed buffers rather than Java2D image conversion.
- **Compass:** an optional tight layer-bounds response removes minimap-sized intermediate surfaces for the small compass. Rotation still uses the 3x coverage grid and bicubic interpolation. Source premultiplication is cached, circular coverage is cached, and coverage/downsampling are integrated in premultiplied space. Randomized rotation tests require exact output alpha and at most one premultiplied colour level of rounding difference from the original Java2D composition.
- **Traversal:** group selection is checked before unrelated surface/config queries. Text/graphic leaves no longer request static/nested interface children. Dirty global-output clips reject unrelated artwork before preparing it.
- **Message traffic:** a one-time `request-render-bridge` handshake supplies an owner-bound callback. Frequent rendering requests bypass the global event bus; unsupported versions retain the previous message protocol. Stopped/replaced painters reject retained callbacks.
- **Artwork caches:** ordinary UI retains both native-preview and final-size artwork samples, avoiding alternating-size cache thrash. Supported route sets are immutable and reused.
- **Global Stretched Mode:** static content and compass signatures are tracked separately. Compass-only changes clear/recompose only the old/new compass rectangle, replaying the original painter order and occluders. GPU texture uploads use that dirty rectangle. Context changes, resizes and static/menu/tooltip changes still trigger full refreshes. Pixel tests compare incremental frames to full recomposition through movement, hiding and occlusion; the desktop GPU check verifies a nonzero-offset partial upload on the RTX 3060.

`pairedMinimapBenchmark` in the HD UI project runs the real painter and resizer compositor together with lightweight synthetic API proxies. Compile the resizer first. Use `-PbenchmarkJava=<client java.exe>`, optional `-PbaselineHd=<previous jar> -PbaselineResizer=<previous jar>`, and JVM properties `benchmark.percent=145` / `benchmark.motion=still|turning` for isolated comparisons. `-PprofileFile=<recording.jfr>` enables Java Flight Recorder for the benchmark only. It prints the loaded class locations to make baseline selection explicit. The native fixture uses SpriteMask row spans; an early generic-ellipse fixture was corrected after profiling exposed its inaccurate edge coverage.

With Java 11, 145% scale and a 1-FPS terrain limit, separate-JVM comparisons against the installed 0.4.14/0.2.41 pair measured 0.485 → 0.419 ms/frame still (p95 0.616 → 0.557), and 1.536 → 1.158 ms/frame turning (p95 1.681 → 1.359). This benchmark includes synthetic pixel generation and API proxies but excludes global event-bus fan-out, native game rendering and GPU presentation. It demonstrates a lower paired CPU cost, not zero in-game overhead.

Optional `Resize timings:` logs now include `HD_MINIMAP`, `HD_COMPASS`, `HD_ARTWORK`, `terrainUpdates` and `minimapUiUpdates`. HD timings are nested within output/minimap totals; don't sum parent and child stages. In global Stretched Mode the final DrawManager presentation is outside these native-stage timings. At 100%, identity image updates are not expensive filter executions.
