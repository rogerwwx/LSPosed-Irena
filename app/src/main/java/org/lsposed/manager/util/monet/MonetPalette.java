/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.lsposed.manager.util.monet;

import android.annotation.SuppressLint;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import androidx.annotation.Nullable;

import com.google.android.material.color.utilities.DynamicColor;
import com.google.android.material.color.utilities.DynamicScheme;
import com.google.android.material.color.utilities.Hct;
import com.google.android.material.color.utilities.MaterialDynamicColors;
import com.google.android.material.color.utilities.SchemeContent;
import com.google.android.material.color.utilities.SchemeExpressive;
import com.google.android.material.color.utilities.SchemeFidelity;
import com.google.android.material.color.utilities.SchemeFruitSalad;
import com.google.android.material.color.utilities.SchemeRainbow;
import com.google.android.material.color.utilities.SchemeTonalSpot;
import com.google.android.material.color.utilities.SchemeVibrant;

import org.lsposed.manager.util.ThemeUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime Material You palette: derives a full color scheme from the
 * wallpaper seed with the user's palette style (variant algorithm) and
 * color spec (2021/2025), then exposes it to the theme through a
 * ResourcesLoader color table. Only active for the M3E skin with dynamic
 * accent when a non-default style/spec is chosen.
 */
public final class MonetPalette {

    /** Fallback seed when the wallpaper exposes no colors. */
    private static final int FALLBACK_SEED = 0xFF4C6637;

    /** Palette role -> the app color resource the palette overlay points at. */
    private static final String[] ROLES = {
            "primary", "onPrimary", "primaryContainer", "onPrimaryContainer",
            "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer",
            "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer",
            "surface", "onSurface", "surfaceVariant", "onSurfaceVariant",
            "outline", "outlineVariant",
            "surfaceContainerLowest", "surfaceContainerLow", "surfaceContainer",
            "surfaceContainerHigh", "surfaceContainerHighest",
    };

    private static final Object loaderLock = new Object();
    @SuppressLint("StaticFieldLeak")
    private static ResourcesLoader cachedLoader;
    private static String cachedKey;

    private MonetPalette() {
    }

    /** True when the runtime palette should override the system dynamic colors. */
    public static boolean isActive() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && !ThemeUtil.PALETTE_STYLE_SYSTEM.equals(ThemeUtil.getPaletteStyle())
                || !ThemeUtil.COLOR_SPEC_SYSTEM.equals(ThemeUtil.getColorSpec());
    }

    /** Cache key of the active palette; a change requires a process restart. */
    public static String key() {
        return ThemeUtil.getPaletteStyle() + "/" + ThemeUtil.getColorSpec();
    }

    /**
     * Attaches the palette color table to the base context resources. Must be
     * called before any theme color is resolved (attachBaseContext); the
     * loader is cached per palette key for the process lifetime.
     */
    public static void attach(Context base) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !isActive()) {
            return;
        }
        synchronized (loaderLock) {
            String key = key();
            if (cachedLoader != null && key.equals(cachedKey)) {
                // Resources.addLoaders deduplicates the same loader instance.
                base.getResources().addLoaders(cachedLoader);
                return;
            }
            try {
                ResourcesLoader loader = buildLoader(base);
                cachedLoader = loader;
                cachedKey = key;
                base.getResources().addLoaders(loader);
            } catch (Throwable t) {
                // A failed palette must never take the app down; the static
                // placeholder colors remain in effect.
            }
        }
    }

    private static ResourcesLoader buildLoader(Context context) throws IOException {
        int seed = getSeed(context);
        Map<String, Integer> light = new LinkedHashMap<>();
        Map<String, Integer> night = new LinkedHashMap<>();
        DynamicScheme lightScheme = schemeFor(context, seed, false);
        DynamicScheme darkScheme = schemeFor(context, seed, true);
        for (String role : ROLES) {
            String resourceName = "lsposed_m3e_" + snakeCase(role);
            if ("SPEC_2025".equals(ThemeUtil.getColorSpec())) {
                light.put(resourceName, MaterialColorsSpec2025.getArgb(lightScheme, role));
                night.put(resourceName, MaterialColorsSpec2025.getArgb(darkScheme, role));
            } else {
                light.put(resourceName, colorFrom2021Scheme(lightScheme, role));
                night.put(resourceName, colorFrom2021Scheme(darkScheme, role));
            }
        }
        ByteBuffer table = ColorResourcesTable.create(
                context.getPackageName(), context.getResources(), light, night);
        File cacheFile = new File(context.getCacheDir(), "lsposed_palette.arsc");
        try (OutputStream out = new FileOutputStream(cacheFile)) {
            out.write(table.array(), table.arrayOffset() + table.position(), table.remaining());
        }
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(cacheFile,
                ParcelFileDescriptor.MODE_READ_ONLY)) {
            ResourcesProvider provider = ResourcesProvider.loadFromTable(fd, null);
            ResourcesLoader loader = new ResourcesLoader();
            loader.addProvider(provider);
            return loader;
        }
    }

    /** Dynamic color roles bundled with the material library (2021 spec). */
    private static final MaterialDynamicColors MDC = new MaterialDynamicColors();

    private static int colorFrom2021Scheme(DynamicScheme scheme, String role) {
        DynamicColor color;
        switch (role) {
            case "primary":
                color = MDC.primary();
                break;
            case "onPrimary":
                color = MDC.onPrimary();
                break;
            case "primaryContainer":
                color = MDC.primaryContainer();
                break;
            case "onPrimaryContainer":
                color = MDC.onPrimaryContainer();
                break;
            case "secondary":
                color = MDC.secondary();
                break;
            case "onSecondary":
                color = MDC.onSecondary();
                break;
            case "secondaryContainer":
                color = MDC.secondaryContainer();
                break;
            case "onSecondaryContainer":
                color = MDC.onSecondaryContainer();
                break;
            case "tertiary":
                color = MDC.tertiary();
                break;
            case "onTertiary":
                color = MDC.onTertiary();
                break;
            case "tertiaryContainer":
                color = MDC.tertiaryContainer();
                break;
            case "onTertiaryContainer":
                color = MDC.onTertiaryContainer();
                break;
            case "surface":
                color = MDC.surface();
                break;
            case "onSurface":
                color = MDC.onSurface();
                break;
            case "surfaceVariant":
                color = MDC.surfaceVariant();
                break;
            case "onSurfaceVariant":
                color = MDC.onSurfaceVariant();
                break;
            case "outline":
                color = MDC.outline();
                break;
            case "outlineVariant":
                color = MDC.outlineVariant();
                break;
            case "surfaceContainerLowest":
                color = MDC.surfaceContainerLowest();
                break;
            case "surfaceContainerLow":
                color = MDC.surfaceContainerLow();
                break;
            case "surfaceContainer":
                color = MDC.surfaceContainer();
                break;
            case "surfaceContainerHigh":
                color = MDC.surfaceContainerHigh();
                break;
            case "surfaceContainerHighest":
                color = MDC.surfaceContainerHighest();
                break;
            default:
                throw new IllegalArgumentException("Unknown palette role: " + role);
        }
        return color.getArgb(scheme);
    }

    private static DynamicScheme schemeFor(Context context, int seed, boolean dark) {
        Hct source = Hct.fromInt(seed);
        double contrast = 0.0;
        switch (ThemeUtil.getPaletteStyle()) {
            case ThemeUtil.PALETTE_STYLE_VIBRANT:
                return new SchemeVibrant(source, dark, contrast);
            case ThemeUtil.PALETTE_STYLE_EXPRESSIVE:
                return new SchemeExpressive(source, dark, contrast);
            case ThemeUtil.PALETTE_STYLE_CONTENT:
                return new SchemeContent(source, dark, contrast);
            case ThemeUtil.PALETTE_STYLE_FIDELITY:
                return new SchemeFidelity(source, dark, contrast);
            case ThemeUtil.PALETTE_STYLE_RAINBOW:
                return new SchemeRainbow(source, dark, contrast);
            case ThemeUtil.PALETTE_STYLE_FRUIT_SALAD:
                return new SchemeFruitSalad(source, dark, contrast);
            case ThemeUtil.PALETTE_STYLE_TONAL_SPOT:
            default:
                return new SchemeTonalSpot(source, dark, contrast);
        }
    }

    @SuppressWarnings("deprecation")
    private static int getSeed(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            WallpaperManager wallpaperManager = context.getSystemService(WallpaperManager.class);
            if (wallpaperManager != null) {
                var colors = wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
                if (colors != null) {
                    Integer primary = colors.getPrimaryColor().toArgb();
                    if (primary != null) {
                        return primary;
                    }
                }
            }
        }
        return FALLBACK_SEED;
    }

    private static String snakeCase(String role) {
        return role.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }
}
