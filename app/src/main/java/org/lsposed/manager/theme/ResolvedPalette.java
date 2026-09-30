/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.lsposed.manager.theme;

import io.material.color.utilities.dynamiccolor.ColorSpec;
import io.material.color.utilities.dynamiccolor.DynamicColor;
import io.material.color.utilities.dynamiccolor.DynamicScheme;
import io.material.color.utilities.dynamiccolor.MaterialDynamicColors;
import io.material.color.utilities.hct.Hct;
import io.material.color.utilities.scheme.SchemeContent;
import io.material.color.utilities.scheme.SchemeExpressive;
import io.material.color.utilities.scheme.SchemeFidelity;
import io.material.color.utilities.scheme.SchemeFruitSalad;
import io.material.color.utilities.scheme.SchemeRainbow;
import io.material.color.utilities.scheme.SchemeTonalSpot;
import io.material.color.utilities.scheme.SchemeVibrant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pure palette generation: no preferences, resources or Android lifecycle dependencies. */
public record ResolvedPalette(int seed, Map<String, Integer> light, Map<String, Integer> dark) {
    public ResolvedPalette {
        light = Collections.unmodifiableMap(new LinkedHashMap<>(light));
        dark = Collections.unmodifiableMap(new LinkedHashMap<>(dark));
    }
    private static final MaterialDynamicColors MDC = new MaterialDynamicColors();
    private static final Map<String, DynamicColor> ROLES = new LinkedHashMap<>();
    static {
        ROLES.put("primary", MDC.primary());
        ROLES.put("onPrimary", MDC.onPrimary());
        ROLES.put("primaryContainer", MDC.primaryContainer());
        ROLES.put("onPrimaryContainer", MDC.onPrimaryContainer());
        ROLES.put("secondary", MDC.secondary());
        ROLES.put("onSecondary", MDC.onSecondary());
        ROLES.put("secondaryContainer", MDC.secondaryContainer());
        ROLES.put("onSecondaryContainer", MDC.onSecondaryContainer());
        ROLES.put("tertiary", MDC.tertiary());
        ROLES.put("onTertiary", MDC.onTertiary());
        ROLES.put("tertiaryContainer", MDC.tertiaryContainer());
        ROLES.put("onTertiaryContainer", MDC.onTertiaryContainer());
        ROLES.put("surface", MDC.surface());
        ROLES.put("onSurface", MDC.onSurface());
        ROLES.put("surfaceVariant", MDC.surfaceVariant());
        ROLES.put("onSurfaceVariant", MDC.onSurfaceVariant());
        ROLES.put("outline", MDC.outline());
        ROLES.put("outlineVariant", MDC.outlineVariant());
        ROLES.put("surfaceContainerLowest", MDC.surfaceContainerLowest());
        ROLES.put("surfaceContainerLow", MDC.surfaceContainerLow());
        ROLES.put("surfaceContainer", MDC.surfaceContainer());
        ROLES.put("surfaceContainerHigh", MDC.surfaceContainerHigh());
        ROLES.put("surfaceContainerHighest", MDC.surfaceContainerHighest());
        ROLES.put("background", MDC.background());
        ROLES.put("onBackground", MDC.onBackground());
        ROLES.put("surfaceDim", MDC.surfaceDim());
        ROLES.put("surfaceBright", MDC.surfaceBright());
        ROLES.put("inverseSurface", MDC.inverseSurface());
        ROLES.put("inverseOnSurface", MDC.inverseOnSurface());
        ROLES.put("inversePrimary", MDC.inversePrimary());
        ROLES.put("error", MDC.error());
        ROLES.put("onError", MDC.onError());
        ROLES.put("errorContainer", MDC.errorContainer());
        ROLES.put("onErrorContainer", MDC.onErrorContainer());
        ROLES.put("surfaceTint", MDC.surfaceTint());
        ROLES.put("primaryFixed", MDC.primaryFixed());
        ROLES.put("primaryFixedDim", MDC.primaryFixedDim());
        ROLES.put("onPrimaryFixed", MDC.onPrimaryFixed());
        ROLES.put("onPrimaryFixedVariant", MDC.onPrimaryFixedVariant());
        ROLES.put("secondaryFixed", MDC.secondaryFixed());
        ROLES.put("secondaryFixedDim", MDC.secondaryFixedDim());
        ROLES.put("onSecondaryFixed", MDC.onSecondaryFixed());
        ROLES.put("onSecondaryFixedVariant", MDC.onSecondaryFixedVariant());
        ROLES.put("tertiaryFixed", MDC.tertiaryFixed());
        ROLES.put("tertiaryFixedDim", MDC.tertiaryFixedDim());
        ROLES.put("onTertiaryFixed", MDC.onTertiaryFixed());
        ROLES.put("onTertiaryFixedVariant", MDC.onTertiaryFixedVariant());
    }
    public static ResolvedPalette generate(ThemeConfig config, int seed) {
        return new ResolvedPalette(seed, resolve(schemeFor(config, seed, false)),
                resolve(schemeFor(config, seed, true)));
    }
    private static Map<String, Integer> resolve(DynamicScheme scheme) {
        Map<String, Integer> result = new LinkedHashMap<>();
        ROLES.forEach((name, role) -> result.put(name, role.getArgb(scheme)));
        return result;
    }
    private static DynamicScheme schemeFor(ThemeConfig config, int seed, boolean dark) {
        Hct source = Hct.fromInt(seed);
        double contrast = 0.0;
        var specVersion = "SPEC_2025".equals(config.effectiveSpec())
                ? ColorSpec.SpecVersion.SPEC_2025
                : ColorSpec.SpecVersion.SPEC_2021;
        var platform = DynamicScheme.Platform.PHONE;
        switch (config.variant()) {
            case "VIBRANT":
                return new SchemeVibrant(source, dark, contrast, specVersion, platform);
            case "EXPRESSIVE":
                return new SchemeExpressive(source, dark, contrast, specVersion, platform);
            case "CONTENT":
                return new SchemeContent(source, dark, contrast, specVersion, platform);
            case "FIDELITY":
                return new SchemeFidelity(source, dark, contrast, specVersion, platform);
            case "RAINBOW":
                return new SchemeRainbow(source, dark, contrast, specVersion, platform);
            case "FRUIT_SALAD":
                return new SchemeFruitSalad(source, dark, contrast, specVersion, platform);
            case "TONAL_SPOT":
            default:
                return new SchemeTonalSpot(source, dark, contrast, specVersion, platform);
        }
    }

}
