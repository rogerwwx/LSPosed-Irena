/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.lsposed.manager.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;

import org.lsposed.manager.R;

/**
 * Draws every list row as one large rounded card (M3E look). MIUIX keeps
 * plain rows, so this decoration is only installed when the M3E skin is
 * active.
 */
public final class ListCardDecoration extends RecyclerView.ItemDecoration {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect bounds = new Rect();
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final float[] cornerRadii = new float[8];
    private final float radius;
    private final float insetHorizontal;
    private final float insetVertical;
    private final float insetSwitchBottom;

    public ListCardDecoration(@NonNull Context context) {
        boolean night = (context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int surfaceAttr = night
                ? com.google.android.material.R.attr.colorSurfaceContainerLow
                : com.google.android.material.R.attr.colorSurfaceContainerLowest;
        paint.setColor(MaterialColors.getColor(context, surfaceAttr, Color.WHITE));
        radius = context.getResources().getDimension(R.dimen.lsposed_m3e_corner_medium);
        insetHorizontal = resolveDimension(context, R.attr.pageHorizontalPadding);
        insetVertical = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 2f, context.getResources().getDisplayMetrics());
        insetSwitchBottom = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 6f, context.getResources().getDisplayMetrics());
    }

    private static float resolveDimension(@NonNull Context context, int attr) {
        var value = new TypedValue();
        if (context.getTheme().resolveAttribute(attr, value, true)) {
            return TypedValue.complexToDimension(value.data, context.getResources().getDisplayMetrics());
        }
        return 0f;
    }

    @Override
    public void onDraw(@NonNull Canvas canvas, @NonNull RecyclerView parent,
                       @NonNull RecyclerView.State state) {
        RecyclerView.Adapter<?> adapter = parent.getAdapter();
        int total = adapter == null ? 0 : adapter.getItemCount();
        for (int index = 0; index < parent.getChildCount(); index++) {
            View child = parent.getChildAt(index);
            int position = parent.getChildAdapterPosition(child);
            if (position == RecyclerView.NO_POSITION) continue;
            // The list is [master switch bar, app rows...]: the bar is its own
            // rounded card, the first and last app rows carry the group's outer
            // corners and the rows in between stay square (small gaps).
            boolean roundTop = position <= 1;
            boolean roundBottom = position == 0 || position == total - 1;
            setCornerRadii(roundTop, roundBottom);
            parent.getDecoratedBoundsWithMargins(child, bounds);
            float topInset = insetVertical;
            float bottomInset = position == 0 ? insetSwitchBottom : insetVertical;
            rect.set(
                    bounds.left + insetHorizontal,
                    bounds.top + topInset,
                    bounds.right - insetHorizontal,
                    bounds.bottom - bottomInset);
            path.reset();
            path.addRoundRect(rect, cornerRadii, Path.Direction.CW);
            canvas.drawPath(path, paint);
        }
    }

    private void setCornerRadii(boolean roundTop, boolean roundBottom) {
        float top = roundTop ? radius : 0f;
        float bottom = roundBottom ? radius : 0f;
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
