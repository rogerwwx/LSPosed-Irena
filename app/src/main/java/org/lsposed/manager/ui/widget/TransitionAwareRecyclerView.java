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
 * along with LSPosed. If not, see <https://www.gnu.org/licenses/>.
 */

package org.lsposed.manager.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import rikka.widget.borderview.BorderRecyclerView;

/**
 * Lets the page transition omit transient scrollbars without changing the
 * scrollbar configuration or the padding that participates in layout.
 */
public class TransitionAwareRecyclerView extends BorderRecyclerView {
    private boolean transitionScrollbarsSuppressed;
    private boolean pendingInitialScrollbarRestore;
    private boolean aggregatedVisible = true;

    public TransitionAwareRecyclerView(@NonNull Context context) {
        super(context);
    }

    public TransitionAwareRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public TransitionAwareRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public void setTransitionScrollbarsSuppressed(boolean suppressed) {
        if (transitionScrollbarsSuppressed != suppressed) {
            transitionScrollbarsSuppressed = suppressed;
            invalidate();
        }
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        boolean wasVisible = aggregatedVisible;
        if (isVisible && !wasVisible && getScrollState() == SCROLL_STATE_IDLE
                && isVerticalScrollBarEnabled()) {
            setVerticalScrollBarEnabled(false);
            pendingInitialScrollbarRestore = true;
        }

        super.onVisibilityAggregated(isVisible);
        aggregatedVisible = isVisible;
    }

    @Override
    public void onDrawForeground(@NonNull Canvas canvas) {
        if (pendingInitialScrollbarRestore) {
            pendingInitialScrollbarRestore = false;
            setVerticalScrollBarEnabled(true);
        }

        if (transitionScrollbarsSuppressed) {
            // BorderRecyclerView draws its border after the framework foreground
            // (which includes scrollbars). Preserve that border while omitting
            // only the transient foreground pass during a page transition.
            getBorderViewDelegate().onDrawForeground(canvas);
        } else {
            super.onDrawForeground(canvas);
        }
    }
}
