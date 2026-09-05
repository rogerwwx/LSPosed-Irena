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
 *
 * Copyright (C) 2026 LSPosed Contributors
 */

package org.lsposed.manager.ui.compose.liquid

import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BackdropEffectScope

/**
 * A [Backdrop] that samples a sibling Android [View] (the top-level pager)
 * instead of compose content captured via [top.yukonga.miuix.kmp.blur.layerBackdrop].
 *
 * The view is re-drawn into the shared [graphicsLayer] only when its subtree
 * invalidated since the last record; every glass surface sampling this
 * backdrop then replays the cached layer, so the pager is drawn at most once
 * per frame regardless of how many surfaces blur it. A
 * [ViewTreeObserver.OnPreDrawListener] bumps [version] whenever the view
 * subtree is dirty, which invalidates the draw scopes reading it, keeping the
 * blurred content in sync with the pager.
 */
@Stable
class ViewBackdrop internal constructor(
    private val view: View,
    internal val graphicsLayer: GraphicsLayer,
) : Backdrop {

    override val isCoordinatesDependent: Boolean = true

    internal val version = mutableIntStateOf(0)

    private var lastRecordedVersion = Int.MIN_VALUE

    private val viewLocation = IntArray(2)
    private var inverseLayerScope: InverseLayerScope? = null

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
        downscaleFactor: Int,
    ) {
        // Reading the version subscribes this draw pass to view invalidations.
        val currentVersion = version.intValue

        val viewWidth = view.width
        val viewHeight = view.height
        if (viewWidth <= 0 || viewHeight <= 0) return
        val coordinates = coordinates ?: return

        if (currentVersion != lastRecordedVersion) {
            graphicsLayer.record(IntSize(viewWidth, viewHeight)) {
                drawIntoCanvas { canvas -> view.draw(canvas.nativeCanvas) }
            }
            lastRecordedVersion = currentVersion
        }

        view.getLocationInWindow(viewLocation)
        val offset = coordinates.positionInWindow() - Offset(viewLocation[0].toFloat(), viewLocation[1].toFloat())

        val consumerSize = (density as? BackdropEffectScope)?.size ?: size

        withTransform({
            if (layerBlock != null) {
                with(obtainInverseLayerScope()) { inverseTransform(density, consumerSize, layerBlock) }
            }
            if (downscaleFactor > 1) {
                val inv = 1f / downscaleFactor
                val scaledX = offset.x * inv
                val scaledY = offset.y * inv
                val roundedX = kotlin.math.round(scaledX * 0.5f).toInt().toFloat() * 2f
                val roundedY = kotlin.math.round(scaledY * 0.5f).toInt().toFloat() * 2f
                translate(-roundedX, -roundedY)
                scale(inv, inv, Offset.Zero)
            } else {
                translate(-offset.x, -offset.y)
            }
        }) {
            drawLayer(graphicsLayer)
        }
    }

    private fun obtainInverseLayerScope(): InverseLayerScope = inverseLayerScope?.apply { reset() }
        ?: InverseLayerScope().also { inverseLayerScope = it }
}

/**
 * Remembers a [ViewBackdrop] for [view] and keeps it invalidated while the
 * view subtree redraws. Must be called from the composition that draws the
 * glass surfaces sampling the backdrop.
 */
@Composable
fun rememberViewBackdrop(view: View): ViewBackdrop {
    val graphicsLayer = rememberGraphicsLayer()
    val backdrop = remember(view, graphicsLayer) { ViewBackdrop(view, graphicsLayer) }
    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnPreDrawListener {
            if (hasDirtyDescendant(view)) {
                backdrop.version.intValue += 1
            }
            true
        }
        view.viewTreeObserver.addOnPreDrawListener(listener)
        onDispose { view.viewTreeObserver.removeOnPreDrawListener(listener) }
    }
    return backdrop
}

/**
 * True when [view] or any of its descendants still has a pending invalidate.
 * The flag clears once the tree has been drawn, so an idle tree returns false
 * and the invalidation loop settles instead of self-perpetuating.
 */
private fun hasDirtyDescendant(view: View): Boolean {
    if (view.isDirty) return true
    if (view is android.view.ViewGroup) {
        for (index in 0 until view.childCount) {
            if (hasDirtyDescendant(view.getChildAt(index))) return true
        }
    }
    return false
}
