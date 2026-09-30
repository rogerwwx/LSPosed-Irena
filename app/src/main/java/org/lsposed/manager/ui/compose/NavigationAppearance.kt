/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.lsposed.manager.ui.compose

import android.content.Context
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import org.lsposed.manager.R
import org.lsposed.manager.util.ThemeUtil
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text

/** Skin-specific presentation; the navigation controller only owns behavior. */
internal data class NavigationAppearance(val inactive: Int, val divider: Int, val selectedContainer: Int?) {
    companion object {
        fun from(context: Context): NavigationAppearance = if (ThemeUtil.isMiuixStyle()) {
            NavigationAppearance(ContextCompat.getColor(context, R.color.lsposed_miuix_navigation_inactive),
                ContextCompat.getColor(context, R.color.lsposed_miuix_divider), null)
        } else {
            fun color(attr: Int) = MaterialColors.getColor(context, attr, "NavigationAppearance")
            NavigationAppearance(color(com.google.android.material.R.attr.colorOnSurfaceVariant),
                color(com.google.android.material.R.attr.colorOutlineVariant), color(R.attr.themeNavigationSelectedColor))
        }
    }
}

@Composable
internal fun NavigationSkinTheme(content: @Composable () -> Unit) {
    if (ThemeUtil.isMiuixStyle()) MiuixTheme { content() } else content()
}

@Composable
internal fun NavigationText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Normal,
    textAlign: TextAlign = TextAlign.Unspecified,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    if (ThemeUtil.isMiuixStyle()) {
        Text(text = text, color = color, fontSize = fontSize, modifier = modifier,
            fontWeight = fontWeight, textAlign = textAlign, maxLines = maxLines, overflow = overflow)
    } else {
        BasicText(text = text, modifier = modifier, maxLines = maxLines, overflow = overflow,
            style = TextStyle(color = color, fontSize = fontSize, fontWeight = fontWeight,
                fontFamily = FontFamily.SansSerif, textAlign = textAlign))
    }
}
