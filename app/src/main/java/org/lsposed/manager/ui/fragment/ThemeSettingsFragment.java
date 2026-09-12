/*
 * <!--This file is part of LSPosed.
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
 * Copyright (C) 2021 LSPosed Contributors-->
 */

package org.lsposed.manager.ui.fragment;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.DynamicColors;

import org.lsposed.manager.App;
import org.lsposed.manager.R;
import org.lsposed.manager.databinding.FragmentThemeSettingsBinding;
import org.lsposed.manager.ui.activity.MainActivity;
import org.lsposed.manager.ui.widget.MiuixPreferenceAdapter;
import org.lsposed.manager.ui.widget.PreferenceCardDecoration;
import org.lsposed.manager.util.ThemeUtil;

import rikka.core.util.ResourceUtils;
import rikka.material.preference.MaterialSwitchPreference;
import rikka.recyclerview.RecyclerViewKt;
import rikka.widget.borderview.BorderRecyclerView;

/**
 * Second-level page behind the settings "Theme settings" entry: night mode,
 * pure black and the accent controls collected on one screen, mirroring the
 * reference fork. Reached through the nav overlay so back/navigation and the
 * slide-in behave like every other second-level page.
 */
public class ThemeSettingsFragment extends BaseFragment {
    FragmentThemeSettingsBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentThemeSettingsBinding.inflate(inflater, container, false);
        binding.appBar.setLiftable(true);
        setupToolbar(binding.toolbar, binding.clickView, R.string.settings_theme_settings);
        // Saved state can predate the first view and contain no preference child.
        if (getChildFragmentManager().findFragmentById(R.id.theme_container) == null) {
            getChildFragmentManager().beginTransaction().add(R.id.theme_container, new ThemePreferenceFragment()).commitNow();
        }
        return binding.getRoot();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        binding = null;
    }

    public static class ThemePreferenceFragment extends PreferenceFragmentCompat {
        private ThemeSettingsFragment parentFragment;

        @Override
        public void onAttach(@NonNull Context context) {
            super.onAttach(context);

            parentFragment = (ThemeSettingsFragment) requireParentFragment();
        }

        @Override
        public void onDetach() {
            super.onDetach();

            parentFragment = null;
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            addPreferencesFromResource(R.xml.prefs_theme);

            Preference darkTheme = findPreference("dark_theme");
            if (darkTheme != null) {
                darkTheme.setOnPreferenceChangeListener((preference, newValue) -> {
                    if (!App.getPreferences().getString("dark_theme", ThemeUtil.MODE_NIGHT_FOLLOW_SYSTEM).equals(newValue)) {
                        AppCompatDelegate.setDefaultNightMode(ThemeUtil.getDarkTheme((String) newValue));
                    }
                    return true;
                });
            }

            Preference blackDarkTheme = findPreference("black_dark_theme");
            if (blackDarkTheme != null) {
                blackDarkTheme.setOnPreferenceChangeListener((preference, newValue) -> {
                    MainActivity activity = (MainActivity) getActivity();
                    if (activity != null && ResourceUtils.isNightMode(getResources().getConfiguration())) {
                        activity.restart();
                    }
                    return true;
                });
            }

            Preference themeColor = findPreference("theme_color");
            if (themeColor != null) {
                themeColor.setOnPreferenceChangeListener((preference, newValue) -> {
                    MainActivity.restartHost(this);
                    return true;
                });
            }

            MaterialSwitchPreference prefFollowSystemAccent = findPreference("follow_system_accent");
            if (prefFollowSystemAccent != null && DynamicColors.isDynamicColorAvailable()) {
                if (themeColor != null) {
                    themeColor.setVisible(!prefFollowSystemAccent.isChecked());
                }
                prefFollowSystemAccent.setVisible(true);
                prefFollowSystemAccent.setOnPreferenceChangeListener((preference, newValue) -> {
                    MainActivity.restartHost(this);
                    return true;
                });
            }

            // Palette style / color spec only drive the M3E skin with the
            // dynamic accent; the fixed accent overlays are static.
            boolean dynamicAccent = DynamicColors.isDynamicColorAvailable()
                    && (prefFollowSystemAccent == null || prefFollowSystemAccent.isChecked());
            boolean paletteVisible = ThemeUtil.isM3eStyle() && dynamicAccent;
            Preference paletteStyle = findPreference("palette_style");
            if (paletteStyle != null) {
                paletteStyle.setVisible(paletteVisible);
                paletteStyle.setOnPreferenceChangeListener((preference, newValue) -> {
                    restartForPalette();
                    return true;
                });
            }
            Preference colorSpec = findPreference("color_spec");
            if (colorSpec != null) {
                colorSpec.setVisible(paletteVisible);
                colorSpec.setOnPreferenceChangeListener((preference, newValue) -> {
                    restartForPalette();
                    return true;
                });
            }
        }

        /**
         * The palette color table attaches to the process Resources through a
         * ResourcesLoader, which cannot be swapped in place; relaunch the
         * process so the new palette attaches to fresh resources.
         */
        private void restartForPalette() {
            MainActivity activity = (MainActivity) getActivity();
            if (activity == null) {
                return;
            }
            if (App.isParasitic) {
                activity.restart();
                return;
            }
            Intent intent = activity.getPackageManager()
                    .getLaunchIntentForPackage(activity.getPackageName());
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                activity.startActivity(intent);
            }
            Runtime.getRuntime().exit(0);
        }

        @NonNull
        @Override
        protected RecyclerView.Adapter onCreateAdapter(@NonNull PreferenceScreen preferenceScreen) {
            return new MiuixPreferenceAdapter(preferenceScreen);
        }

        @Override
        public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            setDivider(null);
        }

        @NonNull
        @Override
        public RecyclerView onCreateRecyclerView(@NonNull LayoutInflater inflater, @NonNull ViewGroup parent, Bundle savedInstanceState) {
            BorderRecyclerView recyclerView = (BorderRecyclerView) super.onCreateRecyclerView(inflater, parent, savedInstanceState);
            recyclerView.addItemDecoration(new PreferenceCardDecoration(requireContext()));
            RecyclerViewKt.fixEdgeEffect(recyclerView, false, true);
            recyclerView.getBorderViewDelegate().setBorderVisibilityChangedListener((top, oldTop, bottom, oldBottom) -> {
                if (parentFragment != null && parentFragment.binding != null) {
                    parentFragment.binding.appBar.setLifted(!top);
                }
            });
            if (parentFragment != null) {
                View.OnClickListener l = v -> {
                    parentFragment.binding.appBar.setExpanded(true, true);
                    recyclerView.smoothScrollToPosition(0);
                };
                parentFragment.binding.toolbar.setOnClickListener(l);
                parentFragment.binding.clickView.setOnClickListener(l);
            }
            return recyclerView;
        }
    }
}
