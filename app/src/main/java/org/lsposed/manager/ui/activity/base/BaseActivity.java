/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2020 EdXposed Contributors
 * Copyright (C) 2021 LSPosed Contributors
 */

package org.lsposed.manager.ui.activity.base;

import android.app.ActivityManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.lsposed.manager.App;
import org.lsposed.manager.R;

import rikka.material.app.MaterialActivity;

public class BaseActivity extends MaterialActivity {
    private static Bitmap icon = null;
    private org.lsposed.manager.theme.ThemeController.Snapshot themeSnapshot;
    private android.app.WallpaperManager wallpaperManager;
    private boolean themeRefreshRequested;
    private final android.app.WallpaperManager.OnColorsChangedListener wallpaperListener =
            (colors, which) -> {
                if ((which & android.app.WallpaperManager.FLAG_SYSTEM) != 0) refreshThemeIfChanged();
            };

    private void refreshThemeIfChanged() {
        if (!themeRefreshRequested && !isFinishing() && !isDestroyed()
                && !themeSnapshot().signature().equals(
                org.lsposed.manager.theme.ThemeController.signature(this))) {
            themeRefreshRequested = true;
            recreate();
        }
    }

    @Override
    protected void onStop() {
        if (wallpaperManager != null) {
            try { wallpaperManager.removeOnColorsChangedListener(wallpaperListener); }
            catch (RuntimeException ignored) { /* OEM service may have disconnected. */ }
            wallpaperManager = null;
        }
        super.onStop();
    }


    private org.lsposed.manager.theme.ThemeController.Snapshot themeSnapshot() {
        if (themeSnapshot == null) themeSnapshot = org.lsposed.manager.theme.ThemeController.prepare(this);
        return themeSnapshot;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshThemeIfChanged();
    }


    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        themeSnapshot = org.lsposed.manager.theme.ThemeController.prepare(this);
        setTheme(R.style.AppTheme);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onStart() {
        super.onStart();
        var config = themeSnapshot().config();
        if (android.os.Build.VERSION.SDK_INT >= 30 && (config.material()
                ? config.source() != org.lsposed.manager.theme.ThemeConfig.Source.FIXED
                : config.followSystemAccent())) {
            try {
                wallpaperManager = getSystemService(android.app.WallpaperManager.class);
                if (wallpaperManager != null) wallpaperManager.addOnColorsChangedListener(
                        wallpaperListener, new android.os.Handler(android.os.Looper.getMainLooper()));
            } catch (RuntimeException ignored) { wallpaperManager = null; }
        }
        if (!App.isParasitic) return;
        for (var task : getSystemService(ActivityManager.class).getAppTasks()) {
            task.setExcludeFromRecents(false);
        }
        if (icon == null) {
            var drawable = getApplicationInfo().loadIcon(getPackageManager());
            if (drawable instanceof BitmapDrawable) {
                icon = ((BitmapDrawable) drawable).getBitmap();
            } else if (drawable instanceof AdaptiveIconDrawable) {
                icon = Bitmap.createBitmap(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
                final Canvas canvas = new Canvas(icon);
                drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                drawable.draw(canvas);
            }
        }
        setTaskDescription(new ActivityManager.TaskDescription(getTitle().toString(), icon, getColor(R.color.ic_launcher_background)));
    }

    @Override
    protected void attachBaseContext(@androidx.annotation.NonNull android.content.Context base) {
        // The runtime palette must be attached before any theme color is
        // resolved, including windowBackground during super.onCreate.
        super.attachBaseContext(base);
        themeSnapshot = org.lsposed.manager.theme.ThemeController.prepare(this);
    }

    @Override
    public void onApplyUserThemeResource(@NonNull Resources.Theme theme, boolean isDecorView) {
        org.lsposed.manager.theme.ThemeController.apply(this, theme, themeSnapshot());
    }

    @Override
    public String computeUserThemeKey() {
        return themeSnapshot().signature() + "/" + themeSnapshot().paletteLoaded();
    }

    @Override
    public void onApplyTranslucentSystemBars() {
        super.onApplyTranslucentSystemBars();
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
    }
}
