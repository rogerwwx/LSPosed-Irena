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
import org.lsposed.manager.ui.widget.ThemedPreferenceAdapter;
import org.lsposed.manager.ui.widget.PreferenceCardDecoration;
import org.lsposed.manager.util.ThemeUtil;

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

            for (String key : new String[]{"ui_style", "dark_theme", "black_dark_theme", "theme_color",
                    "follow_system_accent", "color_source", "palette_style", "color_spec"}) {
                Preference preference = findPreference(key);
                if (preference != null) preference.setOnPreferenceChangeListener((changed, value) -> {
                    var editor = App.getPreferences().edit();
                    if (value instanceof Boolean flag) editor.putBoolean(key, flag);
                    else editor.putString(key, (String) value);
                    editor.apply(); // SharedPreferences memory is updated before any recreation.
                    if ("dark_theme".equals(key)) {
                        AppCompatDelegate.setDefaultNightMode(ThemeUtil.getDarkTheme((String) value));
                    } else {
                        MainActivity.restartHost(this);
                    }
                    return true;
                });
            }
            var config = org.lsposed.manager.theme.ThemePreferences.read();
            boolean runtime = config.material() && android.os.Build.VERSION.SDK_INT >= 30;
            boolean direct = config.source() == org.lsposed.manager.theme.ThemeConfig.Source.SYSTEM_DIRECT;
            boolean systemAvailable = DynamicColors.isDynamicColorAvailable();
            Preference followSystem = findPreference("follow_system_accent");
            if (followSystem != null) followSystem.setVisible(!config.material() && systemAvailable);
            Preference fixed = findPreference("theme_color");
            if (fixed != null) fixed.setVisible(config.material()
                    ? !runtime || config.source() == org.lsposed.manager.theme.ThemeConfig.Source.FIXED
                            || (direct && !systemAvailable)
                    : !ThemeUtil.isSystemAccent());
            rikka.preference.SimpleMenuPreference source = findPreference("color_source");
            if (source != null) {
                source.setVisible(runtime);
                // Wallpaper is entered from Advanced; keep it visible as the current value afterwards.
                var labels = getResources().getStringArray(R.array.theme_color_source_texts);
                var values = getResources().getStringArray(R.array.theme_color_source_values);
                var availableLabels = new java.util.ArrayList<String>();
                var availableValues = new java.util.ArrayList<String>();
                for (int i = 0; i < values.length; i++) {
                    if ("SYSTEM_DIRECT".equals(values[i]) && !systemAvailable && !direct) continue;
                    if ("WALLPAPER".equals(values[i]) && config.source()
                            != org.lsposed.manager.theme.ThemeConfig.Source.WALLPAPER) continue;
                    availableLabels.add(labels[i]); availableValues.add(values[i]);
                }
                source.setEntries(availableLabels.toArray(new String[0]));
                source.setEntryValues(availableValues.toArray(new String[0]));
                if (direct && !systemAvailable) source.setSummary(R.string.theme_system_unavailable);
            }
            Preference advanced = findPreference("advanced_colors");
            if (advanced != null) advanced.setVisible(runtime);
            Preference palette = findPreference("palette_style");
            if (palette != null) {
                palette.setEnabled(!direct);
                if (direct) palette.setSummary(R.string.theme_system_direct_summary);
            }
            Preference spec = findPreference("color_spec");
            if (spec != null) {
                spec.setEnabled(!direct && config.supports2025());
                if (direct) spec.setSummary(R.string.theme_system_direct_summary);
                else if (!config.supports2025()) spec.setSummary(R.string.theme_spec_legacy_variant);
            }
            Preference wallpaper = findPreference("use_wallpaper_colors");
            if (wallpaper != null) wallpaper.setOnPreferenceClickListener(preference -> {
                App.getPreferences().edit().putString("color_source", "WALLPAPER").apply();
                MainActivity.restartHost(this);
                return true;
            });
        }

        @NonNull
        @Override
        protected RecyclerView.Adapter onCreateAdapter(@NonNull PreferenceScreen preferenceScreen) {
            return new ThemedPreferenceAdapter(preferenceScreen);
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
