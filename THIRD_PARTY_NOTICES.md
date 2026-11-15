# Third-party notices

Inventory & Minimap Resize's original plugin code is licensed under the BSD 2-Clause License in [`LICENSE`](LICENSE). The same full text is included in the plugin JAR at `META-INF/LICENSE`.

## Gradle Wrapper

The build tooling includes generated `gradlew` and `gradlew.bat` scripts and `gradle/wrapper/gradle-wrapper.jar`, from [Gradle](https://github.com/gradle/gradle/tree/v8.8.0). These are licensed under the **Apache License, Version 2.0**, rather than this plugin's BSD license.

The original script copyright/license headers are retained. The wrapper JAR retains its bundled `META-INF/LICENSE`. A full copy of the Apache License is provided in [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt).

The wrapper files are development/build tooling and are not bundled in the plugin JAR. RuneLite is used as an external compile-only runtime dependency; its classes are not copied into the plugin JAR.

## xBR level 2

`XbrImageScaler.java` adapts **Hyllian's xBR-lv2 shader**, copyright (C) 2011–2016 Hyllian (sergiogdb@gmail.com), under the MIT license. The original also credits ideas from Joshua Street's SABR shader.

Source: [RuneLite's xbr_lv2_frag.glsl](https://github.com/runelite/runelite/blob/master/runelite-client/src/main/resources/net/runelite/client/plugins/gpu/scale/xbr_lv2_frag.glsl).

The CPU adaptation retains the CORNER_C/small-details rules, classifies corners once per source pixel, blends premultiplied channels, and explicitly initializes the far-right neighbour which is uninitialized in the referenced shader. It is not pixel-identical to either renderer. The full notice is retained in the Java source and packaged as [`META-INF/licenses/xBR-MIT.txt`](src/main/resources/META-INF/licenses/xBR-MIT.txt).

## Hybrid scaling

The hybrid coordinate mapping in `WidgetImageScaler.java` adapts [RuneLite's hybrid.glsl](https://github.com/runelite/runelite/blob/master/runelite-client/src/main/resources/net/runelite/client/plugins/gpu/scale/hybrid.glsl), written in 2025 by Casey Zwicker (caseypzwicker@gmail.com), dedicated to the public domain under **CC0 1.0 Universal**. The shader references [Cole Cecil's pixel-art scaling explanation](https://colececil.dev/blog/2017/scaling-pixel-art-without-destroying-it/).

The dedication is packaged as [`META-INF/licenses/CC0-1.0.txt`](src/main/resources/META-INF/licenses/CC0-1.0.txt).
