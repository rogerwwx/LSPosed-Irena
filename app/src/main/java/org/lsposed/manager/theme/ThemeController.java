/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.lsposed.manager.theme;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import com.google.android.material.color.DynamicColors;
import org.lsposed.manager.R;
import org.lsposed.manager.util.ThemeUtil;
import org.lsposed.manager.util.monet.MonetPalette;

/** The only entry point for preparing and applying a manager theme. */
public final class ThemeController {
    public record Snapshot(ThemeConfig config, String signature, boolean paletteLoaded) {}
    private ThemeController() {}
    private static String cachedPaletteKey;
    private static ResolvedPalette cachedPalette;

    private static boolean dark(Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    public static String signature(Context context) {
        ThemeConfig config = ThemePreferences.read();
        return signature(context, config, resolveSeed(context, config));
    }

    private static int resolveSeed(Context context, ThemeConfig config) {
        // Static themes on older Android releases do not depend on wallpaper changes.
        return config.material() && Build.VERSION.SDK_INT >= 30 ? SeedResolver.resolve(context, config) : 0;
    }

    private static String signature(Context context, ThemeConfig config, int seed) {
        String fingerprint = "";
        if (Build.VERSION.SDK_INT >= 31 && (config.material()
                ? config.source() == ThemeConfig.Source.SYSTEM_DIRECT : config.followSystemAccent())) {
            // A system variant can change surfaces/secondary colors without changing accent1.
            for (int id : new int[]{android.R.color.system_accent1_100, android.R.color.system_accent1_500,
                    android.R.color.system_accent2_500,
                    android.R.color.system_accent3_500, android.R.color.system_neutral1_500,
                    android.R.color.system_neutral2_500}) {
                try { fingerprint += "/" + context.getColor(id); }
                catch (RuntimeException ignored) { fingerprint += "/missing"; }
            }
        }
        return config.signature(seed, dark(context)) + fingerprint;
    }

    public static synchronized Snapshot prepare(Context context) {
        ThemeConfig config = ThemePreferences.read();
        int seed = resolveSeed(context, config);
        String signature = signature(context, config, seed);
        ResolvedPalette palette = null;
        if (config.generatesPalette(Build.VERSION.SDK_INT)) {
            String paletteKey = seed + "/" + config.variant() + "/" + config.effectiveSpec();
            try {
                if (!paletteKey.equals(cachedPaletteKey)) {
                    cachedPalette = ResolvedPalette.generate(config, seed);
                    cachedPaletteKey = paletteKey;
                }
                palette = cachedPalette;
            } catch (RuntimeException | LinkageError failure) {
                android.util.Log.e("ThemeController", "Palette generation failed", failure);
            }
        }
        boolean loaded = MonetPalette.apply(context, palette, signature);
        return new Snapshot(config, signature, loaded);
    }

    public static void apply(Context context, Resources.Theme theme, Snapshot snapshot) {
        ThemeConfig config = snapshot.config();
        boolean system = DynamicColors.isDynamicColorAvailable();
        if (!config.material()) {
            if (!ThemeUtil.isSystemAccent()) theme.applyStyle(ThemeUtil.getColorThemeStyleRes(), true);
            theme.applyStyle(R.style.ThemeOverlay_LSPosed_Miuix, true);
        } else {
            // Complete static fallback if generation/loading fails. Never expose placeholder roles.
            if (!snapshot.paletteLoaded() && (config.source() != ThemeConfig.Source.SYSTEM_DIRECT || !system))
                theme.applyStyle(ThemeUtil.getColorThemeStyleRes(), true);
            if (snapshot.paletteLoaded()) theme.applyStyle(R.style.ThemeOverlay_LSPosed_M3E_Palette, true);
            theme.applyStyle(R.style.ThemeOverlay_LSPosed_M3E, true);
            theme.applyStyle(dark(context) ? R.style.ThemeOverlay_LSPosed_M3E_Surfaces_Dark
                    : R.style.ThemeOverlay_LSPosed_M3E_Surfaces_Light, true);
        }
        theme.applyStyle(ThemeUtil.getNightThemeStyleRes(context), true);
        theme.applyStyle(rikka.material.preference.R.style.ThemeOverlay_Rikka_Material3_Preference, true);
    }
}
