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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed. If not, see <https://www.gnu.org/licenses/>.
 */

package org.lsposed.manager.util;

import android.graphics.Typeface;
import android.os.Build;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

/**
 * Shared hint-line styling for list rows: appends an emphasized, tinted
 * fragment to a SpannableStringBuilder. Uses the medium sans-serif weight on
 * P+ and falls back to bold below it.
 */
public final class SpanUtils {

    private SpanUtils() {
    }

    public static void appendEmphasized(SpannableStringBuilder sb, CharSequence text, int color) {
        int start = sb.length();
        sb.append(text);
        int end = sb.length();
        sb.setSpan(new ForegroundColorSpan(color), start, end, Spannable.SPAN_INCLUSIVE_INCLUSIVE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            sb.setSpan(new TypefaceSpan(Typeface.create("sans-serif-medium", Typeface.NORMAL)),
                    start, end, Spannable.SPAN_INCLUSIVE_INCLUSIVE);
        } else {
            sb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_INCLUSIVE_INCLUSIVE);
        }
    }
}
