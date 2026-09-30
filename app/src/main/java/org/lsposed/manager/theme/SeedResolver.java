/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.lsposed.manager.theme;

import android.app.WallpaperManager;
import android.content.Context;
import android.os.Build;
import java.util.LinkedHashMap;

/** Reads public system colors; never tries to recover private wallpaper seed settings. */
public final class SeedResolver {
    private SeedResolver() {}
    public static int resolve(Context context, ThemeConfig config) {
        return SeedSelection.resolve(config, Build.VERSION.SDK_INT,
                () -> context.getColor(android.R.color.system_accent1_500), () -> {
            WallpaperManager manager = context.getSystemService(WallpaperManager.class);
            var colors = manager == null ? null : manager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
            if (colors != null) {
                // WallpaperColors provides candidates, not pixel populations. Equal weights
                // let MCU rank suitability without inventing a population distribution.
                var candidates = new LinkedHashMap<Integer, Integer>();
                candidates.put(colors.getPrimaryColor().toArgb(), 1);
                if (colors.getSecondaryColor() != null) candidates.put(colors.getSecondaryColor().toArgb(), 1);
                if (colors.getTertiaryColor() != null) candidates.put(colors.getTertiaryColor().toArgb(), 1);
                return candidates;
            }
            return null;
        });
    }
}
