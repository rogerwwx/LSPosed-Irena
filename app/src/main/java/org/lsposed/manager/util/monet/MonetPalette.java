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

import org.lsposed.manager.R;
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

    private static final String TAG = "MonetPalette";

    /** Fallback seed when the wallpaper exposes no colors. */
    private static final int FALLBACK_SEED = 0xFF4C6637;

    /** Palette role -> the app color resource the palette overlay points at.
     * R.color ids are compile-time constants, immune to resource renaming. */
    private static final Map<String, Integer> ROLE_IDS = new LinkedHashMap<>();

    static {
        ROLE_IDS.put("primary", R.color.lsposed_m3e_primary);
        ROLE_IDS.put("onPrimary", R.color.lsposed_m3e_on_primary);
        ROLE_IDS.put("primaryContainer", R.color.lsposed_m3e_primary_container);
        ROLE_IDS.put("onPrimaryContainer", R.color.lsposed_m3e_on_primary_container);
        ROLE_IDS.put("secondary", R.color.lsposed_m3e_secondary);
        ROLE_IDS.put("onSecondary", R.color.lsposed_m3e_on_secondary);
        ROLE_IDS.put("secondaryContainer", R.color.lsposed_m3e_secondary_container);
        ROLE_IDS.put("onSecondaryContainer", R.color.lsposed_m3e_on_secondary_container);
        ROLE_IDS.put("tertiary", R.color.lsposed_m3e_tertiary);
        ROLE_IDS.put("onTertiary", R.color.lsposed_m3e_on_tertiary);
        ROLE_IDS.put("tertiaryContainer", R.color.lsposed_m3e_tertiary_container);
        ROLE_IDS.put("onTertiaryContainer", R.color.lsposed_m3e_on_tertiary_container);
        ROLE_IDS.put("surface", R.color.lsposed_m3e_surface);
        ROLE_IDS.put("onSurface", R.color.lsposed_m3e_on_surface);
        ROLE_IDS.put("surfaceVariant", R.color.lsposed_m3e_surface_variant);
        ROLE_IDS.put("onSurfaceVariant", R.color.lsposed_m3e_on_surface_variant);
        ROLE_IDS.put("outline", R.color.lsposed_m3e_outline);
        ROLE_IDS.put("outlineVariant", R.color.lsposed_m3e_outline_variant);
        ROLE_IDS.put("surfaceContainerLowest", R.color.lsposed_m3e_surface_container_lowest);
        ROLE_IDS.put("surfaceContainerLow", R.color.lsposed_m3e_surface_container_low);
        ROLE_IDS.put("surfaceContainer", R.color.lsposed_m3e_surface_container);
        ROLE_IDS.put("surfaceContainerHigh", R.color.lsposed_m3e_surface_container_high);
        ROLE_IDS.put("surfaceContainerHighest", R.color.lsposed_m3e_surface_container_highest);
    }

    /** The vendored Material Color Utilities engine (evaluates whichever
     * spec the scheme carries). */
    private static final MaterialDynamicColors MDC = new MaterialDynamicColors();

    /** Palette role -> its Material Color Utilities definition. Built once so
     * resolveRole is a lookup instead of a hand-maintained switch. */
    private static final Map<String, DynamicColor> ROLE_COLORS = new LinkedHashMap<>();

    static {
        ROLE_COLORS.put("primary", MDC.primary());
        ROLE_COLORS.put("onPrimary", MDC.onPrimary());
        ROLE_COLORS.put("primaryContainer", MDC.primaryContainer());
        ROLE_COLORS.put("onPrimaryContainer", MDC.onPrimaryContainer());
        ROLE_COLORS.put("secondary", MDC.secondary());
        ROLE_COLORS.put("onSecondary", MDC.onSecondary());
        ROLE_COLORS.put("secondaryContainer", MDC.secondaryContainer());
        ROLE_COLORS.put("onSecondaryContainer", MDC.onSecondaryContainer());
        ROLE_COLORS.put("tertiary", MDC.tertiary());
        ROLE_COLORS.put("onTertiary", MDC.onTertiary());
        ROLE_COLORS.put("tertiaryContainer", MDC.tertiaryContainer());
        ROLE_COLORS.put("onTertiaryContainer", MDC.onTertiaryContainer());
        ROLE_COLORS.put("surface", MDC.surface());
        ROLE_COLORS.put("onSurface", MDC.onSurface());
        ROLE_COLORS.put("surfaceVariant", MDC.surfaceVariant());
        ROLE_COLORS.put("onSurfaceVariant", MDC.onSurfaceVariant());
        ROLE_COLORS.put("outline", MDC.outline());
        ROLE_COLORS.put("outlineVariant", MDC.outlineVariant());
        ROLE_COLORS.put("surfaceContainerLowest", MDC.surfaceContainerLowest());
        ROLE_COLORS.put("surfaceContainerLow", MDC.surfaceContainerLow());
        ROLE_COLORS.put("surfaceContainer", MDC.surfaceContainer());
        ROLE_COLORS.put("surfaceContainerHigh", MDC.surfaceContainerHigh());
        ROLE_COLORS.put("surfaceContainerHighest", MDC.surfaceContainerHighest());
    }

    private static final Object loaderLock = new Object();
    @SuppressLint("StaticFieldLeak")
    private static ResourcesLoader cachedLoader;
    private static String cachedKey;

    private MonetPalette() {
    }

    /** True when the runtime palette should override the system dynamic colors. */
    public static boolean isActive() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && (!ThemeUtil.PALETTE_STYLE_SYSTEM.equals(ThemeUtil.getPaletteStyle())
                || !ThemeUtil.COLOR_SPEC_SYSTEM.equals(ThemeUtil.getColorSpec()));
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
                android.util.Log.e(TAG, "palette attach failed for " + key, t);
            }
        }
    }

    private static ResourcesLoader buildLoader(Context context) throws IOException {
        int seed = getSeed(context);
        // Resource ids are the only stable handles (resopt renames resources);
        // the loader table matches entries by type/entry index from the id.
        Resources resources = context.getResources();
        Map<Integer, Integer> light = new LinkedHashMap<>();
        Map<Integer, Integer> night = new LinkedHashMap<>();
        DynamicScheme lightScheme = schemeFor(context, seed, false);
        DynamicScheme darkScheme = schemeFor(context, seed, true);
        for (Map.Entry<String, Integer> entry : ROLE_IDS.entrySet()) {
            String role = entry.getKey();
            int resId = entry.getValue();
            light.put(resId, resolveRole(lightScheme, role));
            night.put(resId, resolveRole(darkScheme, role));
        }
        ByteBuffer table = ColorResourcesTable.create(context.getPackageName(),
                resources::getResourceEntryName, light, night);
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

    private static int resolveRole(DynamicScheme scheme, String role) {
        DynamicColor color = ROLE_COLORS.get(role);
        if (color == null) {
            throw new IllegalArgumentException("Unknown palette role: " + role);
        }
        return color.getArgb(scheme);
    }

    private static DynamicScheme schemeFor(Context context, int seed, boolean dark) {
        Hct source = Hct.fromInt(seed);
        double contrast = 0.0;
        var specVersion = ThemeUtil.COLOR_SPEC_2025.equals(ThemeUtil.getColorSpec())
                ? ColorSpec.SpecVersion.SPEC_2025
                : ColorSpec.SpecVersion.SPEC_2021;
        var platform = DynamicScheme.Platform.PHONE;
        switch (ThemeUtil.getPaletteStyle()) {
            case ThemeUtil.PALETTE_STYLE_VIBRANT:
                return new SchemeVibrant(source, dark, contrast, specVersion, platform);
            case ThemeUtil.PALETTE_STYLE_EXPRESSIVE:
                return new SchemeExpressive(source, dark, contrast, specVersion, platform);
            case ThemeUtil.PALETTE_STYLE_CONTENT:
                return new SchemeContent(source, dark, contrast, specVersion, platform);
            case ThemeUtil.PALETTE_STYLE_FIDELITY:
                return new SchemeFidelity(source, dark, contrast, specVersion, platform);
            case ThemeUtil.PALETTE_STYLE_RAINBOW:
                return new SchemeRainbow(source, dark, contrast, specVersion, platform);
            case ThemeUtil.PALETTE_STYLE_FRUIT_SALAD:
                return new SchemeFruitSalad(source, dark, contrast, specVersion, platform);
            case ThemeUtil.PALETTE_STYLE_TONAL_SPOT:
            default:
                return new SchemeTonalSpot(source, dark, contrast, specVersion, platform);
        }
    }

    private static int getSeed(Context context) {
        // attach() only runs from R onwards, so no API-level guard is needed
        // here (WallpaperColors itself arrived in O_MR1).
        var wallpaperManager = context.getSystemService(WallpaperManager.class);
        var colors = wallpaperManager == null
                ? null : wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
        return colors != null ? colors.getPrimaryColor().toArgb() : FALLBACK_SEED;
    }
}
