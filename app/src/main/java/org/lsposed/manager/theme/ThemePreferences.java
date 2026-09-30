/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.lsposed.manager.theme;

import android.content.SharedPreferences;
import org.lsposed.manager.App;

/** Persists normalized configuration before any preference defaults are inflated. */
public final class ThemePreferences {
    private ThemePreferences() {}

    public static ThemeConfig read() { return ThemeConfig.from(App.getPreferences().getAll()); }

    public static void migrate(SharedPreferences preferences) {
        ThemeConfig config = ThemeConfig.from(preferences.getAll());
        preferences.edit().putInt("theme_config_version", 1)
                .putString("ui_style", config.skin()).putString("color_source", config.source().name())
                .putString("palette_style", config.variant()).putString("color_spec", config.spec())
                .putString("theme_color", config.fixedColor()).putString("dark_theme", config.nightMode())
                .putBoolean("black_dark_theme", config.black())
                .putBoolean("follow_system_accent", config.followSystemAccent()).apply();
    }
}
