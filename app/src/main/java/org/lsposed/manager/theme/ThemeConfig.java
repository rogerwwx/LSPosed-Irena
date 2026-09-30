/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.lsposed.manager.theme;

import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Collections;

/** Immutable, Android-independent configuration. Legacy values are decoded by behavior. */
public record ThemeConfig(String skin, Source source, String variant, String spec,
                          String fixedColor, String nightMode, boolean black, boolean followSystemAccent) {
    public enum Source { SYSTEM_SEED, WALLPAPER, FIXED, SYSTEM_DIRECT }
    public static final Set<String> VARIANTS = choices("TONAL_SPOT", "VIBRANT", "EXPRESSIVE",
            "CONTENT", "FIDELITY", "RAINBOW", "FRUIT_SALAD");
    public static final Map<String, Integer> SEEDS;
    static {
        Map<String, Integer> seeds = new LinkedHashMap<>();
        seeds.put("SAKURA", 0xffff9ca8);
        seeds.put("MATERIAL_RED", 0xfff44336);
        seeds.put("MATERIAL_PINK", 0xffe91e63);
        seeds.put("MATERIAL_PURPLE", 0xff9c27b0);
        seeds.put("MATERIAL_DEEP_PURPLE", 0xff673ab7);
        seeds.put("MATERIAL_INDIGO", 0xff3f51b5);
        seeds.put("MATERIAL_BLUE", 0xff2196f3);
        seeds.put("MATERIAL_LIGHT_BLUE", 0xff03a9f4);
        seeds.put("MATERIAL_CYAN", 0xff00bcd4);
        seeds.put("MATERIAL_TEAL", 0xff009688);
        seeds.put("MATERIAL_GREEN", 0xff4faf50);
        seeds.put("MATERIAL_LIGHT_GREEN", 0xff8bc3a4);
        seeds.put("MATERIAL_LIME", 0xffcddc39);
        seeds.put("MATERIAL_YELLOW", 0xffffeb3b);
        seeds.put("MATERIAL_AMBER", 0xffffc107);
        seeds.put("MATERIAL_ORANGE", 0xffff9800);
        seeds.put("MATERIAL_DEEP_ORANGE", 0xffff5722);
        seeds.put("MATERIAL_BROWN", 0xff795548);
        seeds.put("MATERIAL_BLUE_GREY", 0xff607d8f);
        SEEDS = Collections.unmodifiableMap(seeds);
    }
    private static Set<String> choices(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }
    public static final int DEFAULT_SEED = 0xff2196f3;

    public static ThemeConfig from(Map<String, ?> values) {
        boolean legacy = !values.containsKey("theme_config_version") && !values.isEmpty();
        String variant = string(values, "palette_style", legacy ? "SYSTEM" : "TONAL_SPOT");
        String spec = string(values, "color_spec", legacy ? "SYSTEM" : "SPEC_2025");
        Source source = Source.SYSTEM_SEED;
        if (legacy) {
            source = Boolean.FALSE.equals(values.get("follow_system_accent")) ? Source.FIXED
                    : "SYSTEM".equals(variant) && "SYSTEM".equals(spec)
                    ? Source.SYSTEM_DIRECT : Source.WALLPAPER;
        }
        try { source = Source.valueOf(string(values, "color_source", source.name())); }
        catch (IllegalArgumentException ignored) { source = Source.SYSTEM_SEED; }
        if (!VARIANTS.contains(variant)) variant = "TONAL_SPOT";
        if (!choices("SPEC_2021", "SPEC_2025").contains(spec)) spec = legacy ? "SPEC_2021" : "SPEC_2025";
        String fixed = string(values, "theme_color", "MATERIAL_BLUE");
        if (!SEEDS.containsKey(fixed)) fixed = "MATERIAL_BLUE";
        String night = string(values, "dark_theme", "MODE_NIGHT_FOLLOW_SYSTEM");
        if (!choices("MODE_NIGHT_FOLLOW_SYSTEM", "MODE_NIGHT_NO", "MODE_NIGHT_YES").contains(night))
            night = "MODE_NIGHT_FOLLOW_SYSTEM";
        return new ThemeConfig("MATERIAL".equals(values.get("ui_style")) ? "MATERIAL" : "MIUIX",
                source, variant, spec, fixed, night, Boolean.TRUE.equals(values.get("black_dark_theme")),
                !Boolean.FALSE.equals(values.get("follow_system_accent")));
    }

    private static String string(Map<String, ?> values, String key, String fallback) {
        return values.get(key) instanceof String value ? value : fallback;
    }

    public boolean supports2025() { return choices("TONAL_SPOT", "VIBRANT", "EXPRESSIVE").contains(variant); }
    public String effectiveSpec() { return supports2025() ? spec : "SPEC_2021"; }
    public boolean material() { return "MATERIAL".equals(skin); }
    public int fixedSeed() { return SEEDS.getOrDefault(fixedColor, DEFAULT_SEED); }
    public boolean generatesPalette(int api) { return material() && api >= 30 && source != Source.SYSTEM_DIRECT; }
    public String signature(int seed, boolean dark) {
        return skin + "/" + source + "/" + variant + "/" + effectiveSpec() + "/"
                + fixedColor + "/" + seed + "/" + nightMode + "/" + dark + "/" + black + "/" + followSystemAccent;
    }
}
