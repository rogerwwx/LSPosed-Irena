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
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import io.material.color.utilities.score.Score;

/** Pure selection policy. Deferred readers avoid wallpaper access when system colors work. */
public final class SeedSelection {
    private SeedSelection() {}

    public static int resolve(ThemeConfig config, int api, IntSupplier systemColor,
                              Supplier<Map<Integer, Integer>> wallpaperColors) {
        if (config.source() == ThemeConfig.Source.FIXED) return config.fixedSeed();
        if (config.source() != ThemeConfig.Source.WALLPAPER && api >= 31) {
            try { return systemColor.getAsInt(); }
            catch (RuntimeException ignored) { /* Missing OEM resource: try wallpaper. */ }
        }
        try {
            Map<Integer, Integer> candidates = wallpaperColors.get();
            if (candidates != null && !candidates.isEmpty()) {
                return Score.score(candidates, 1, ThemeConfig.DEFAULT_SEED, true).get(0);
            }
        } catch (RuntimeException ignored) { /* Includes wallpaper permission failures. */ }
        return ThemeConfig.DEFAULT_SEED;
    }
}
