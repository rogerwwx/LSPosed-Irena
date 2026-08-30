/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.lsposed.manager.ui.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroupAdapter;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceViewHolder;

import com.google.android.material.color.MaterialColors;

import org.lsposed.manager.R;

/**
 * Shared preference list adapter for both skins: restyles the row typography
 * (13sp category labels, 16sp titles, 14sp summaries) and tints the leading
 * icons, which stock preference rows leave untinted (the bundled outline
 * icons are white fills and would otherwise vanish on light surfaces).
 */
@SuppressLint("RestrictedApi")
public final class MiuixPreferenceAdapter extends PreferenceGroupAdapter {
    private final ColorStateList primaryTextColors;
    private final ColorStateList secondaryTextColors;
    private final ColorStateList iconColors;
    private final Typeface regularTypeface = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface categoryTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    public MiuixPreferenceAdapter(@NonNull PreferenceScreen preferenceScreen) {
        super(preferenceScreen);
        Context context = preferenceScreen.getContext();
        primaryTextColors = createTextColors(
                context,
                com.google.android.material.R.attr.colorOnSurface,
                R.color.lsposed_miuix_text_primary);
        secondaryTextColors = createTextColors(
                context,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                R.color.lsposed_miuix_text_secondary);
        iconColors = primaryTextColors;
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder, int position) {
        super.onBindViewHolder(holder, position);

        Preference preference = getItem(position);
        TextView title = (TextView) holder.findViewById(android.R.id.title);
        if (preference instanceof PreferenceCategory) {
            if (title != null) {
                title.setTextColor(secondaryTextColors);
                title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                title.setTypeface(categoryTypeface);
            }
            return;
        }

        if (title != null) {
            title.setTextColor(primaryTextColors);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            title.setTypeface(regularTypeface);
        }
        TextView summary = (TextView) holder.findViewById(android.R.id.summary);
        if (summary != null) {
            summary.setTextColor(secondaryTextColors);
            summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            summary.setTypeface(regularTypeface);
        }
        ImageView icon = (ImageView) holder.findViewById(android.R.id.icon);
        if (icon != null && icon.getDrawable() != null) {
            icon.setImageTintList(iconColors);
        }
    }

    @NonNull
    private static ColorStateList createTextColors(@NonNull Context context, int colorAttr,
                                                   int fallbackColorRes) {
        int enabledColor = MaterialColors.getColor(
                context, colorAttr, ContextCompat.getColor(context, fallbackColorRes));
        int disabledAlpha = Math.round(Color.alpha(enabledColor) * 0.38f);
        int disabledColor = ColorUtils.setAlphaComponent(enabledColor, disabledAlpha);
        return new ColorStateList(
                new int[][]{
                        new int[]{-android.R.attr.state_enabled},
                        new int[]{}
                },
                new int[]{disabledColor, enabledColor});
    }
}
