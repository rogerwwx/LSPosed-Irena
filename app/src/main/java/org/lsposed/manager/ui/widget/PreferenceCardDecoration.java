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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroupAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;

import org.lsposed.manager.R;
import org.lsposed.manager.util.ThemeUtil;

/** Draws the rows below each preference category as one rounded surface. */
@SuppressLint("RestrictedApi")
public final class PreferenceCardDecoration extends RecyclerView.ItemDecoration {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Rect childBounds = new Rect();
    private final RectF cardBounds = new RectF();
    private final float[] cornerRadii = new float[8];
    private final float cornerRadius;
    private final boolean perRow;
    private final float rowInsetVertical;

    public PreferenceCardDecoration(@NonNull Context context) {
        // M3E light cards are surfaceContainerLowest (whiter than the page);
        // MIUIX and dark mode use the plain surface tone.
        boolean night = (context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int surfaceAttr = !ThemeUtil.isMiuixStyle() && !night
                ? com.google.android.material.R.attr.colorSurfaceContainerLowest
                : com.google.android.material.R.attr.colorSurface;
        paint.setColor(MaterialColors.getColor(
                context,
                surfaceAttr,
                ContextCompat.getColor(context, R.color.lsposed_miuix_surface)));
        cornerRadius = context.getResources().getDimension(ThemeUtil.isMiuixStyle()
                ? R.dimen.lsposed_miuix_corner_medium
                : R.dimen.lsposed_m3e_corner_medium);
        // M3E draws every row as its own rounded card like the reference;
        // MIUIX merges each category's rows into one surface. Preference rows
        // carry no vertical margins, so shrink the card a little to open the
        // gap between neighbouring cards (same trick as ListCardDecoration).
        perRow = !ThemeUtil.isMiuixStyle();
        rowInsetVertical = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 3f, context.getResources().getDisplayMetrics());
    }

    @Override
    public void onDraw(@NonNull Canvas canvas, @NonNull RecyclerView parent,
                       @NonNull RecyclerView.State state) {
        RecyclerView.Adapter<?> adapter = parent.getAdapter();
        if (!(adapter instanceof PreferenceGroupAdapter preferenceAdapter)) {
            return;
        }

        for (int index = 0; index < parent.getChildCount(); index++) {
            View child = parent.getChildAt(index);
            int position = parent.getChildAdapterPosition(child);
            if (!isCardRow(preferenceAdapter, position)) {
                continue;
            }

            boolean isFirst = perRow || !isCardRow(preferenceAdapter, position - 1);
            boolean isLast = perRow || !isCardRow(preferenceAdapter, position + 1);
            setCornerRadii(isFirst, isLast);

            parent.getDecoratedBoundsWithMargins(child, childBounds);
            float inset = perRow ? rowInsetVertical : 0f;
            cardBounds.set(
                    childBounds.left + child.getTranslationX(),
                    childBounds.top + child.getTranslationY() + inset,
                    childBounds.right + child.getTranslationX(),
                    childBounds.bottom + child.getTranslationY() - inset);

            path.reset();
            path.addRoundRect(cardBounds, cornerRadii, Path.Direction.CW);
            canvas.drawPath(path, paint);
        }
    }

    private static boolean isCardRow(@NonNull PreferenceGroupAdapter adapter, int position) {
        if (position < 0 || position >= adapter.getItemCount()) {
            return false;
        }
        Preference preference = adapter.getItem(position);
        return preference != null && !(preference instanceof PreferenceCategory);
    }

    private void setCornerRadii(boolean roundTop, boolean roundBottom) {
        float top = roundTop ? cornerRadius : 0f;
        float bottom = roundBottom ? cornerRadius : 0f;
        cornerRadii[0] = top;
        cornerRadii[1] = top;
        cornerRadii[2] = top;
        cornerRadii[3] = top;
        cornerRadii[4] = bottom;
        cornerRadii[5] = bottom;
        cornerRadii[6] = bottom;
        cornerRadii[7] = bottom;
    }
}
