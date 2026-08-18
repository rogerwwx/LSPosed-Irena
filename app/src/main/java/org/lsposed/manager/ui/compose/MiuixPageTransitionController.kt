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

package org.lsposed.manager.ui.compose

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.IdRes
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Animates the NavHost as one pager page while FragmentNavigator is free to
 * save and restore its independent top-level back stacks underneath.
 *
 * FragmentNavigator persists the animation from the transaction that created
 * a saved back stack. A container-level transition avoids replaying an old or
 * directionally incorrect fragment animation when that stack is restored.
 */
internal class MiuixPageTransitionController(
    private val content: View,
    private val overlay: FrameLayout,
    private val window: Window,
) {
    private var pending: PendingTransition? = null
    private var animator: AnimatorSet? = null
    private var destinationTimeout: Runnable? = null
    private var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var captureGeneration = 0L
    private var preparing = false

    /**
     * Captures the current page asynchronously and places the live NavHost
     * just outside the viewport. [logicalDirection] is +1 toward the logical
     * end and -1 back. [onReady] is always called on the main thread.
     */
    fun prepare(
        @IdRes targetTopLevel: Int,
        logicalDirection: Int,
        onReady: () -> Unit,
    ): Boolean {
        finishImmediately()

        if (!content.isAttachedToWindow || !content.isLaidOut ||
            content.width <= 0 || content.height <= 0
        ) {
            return false
        }

        val physicalDirection = if (content.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            -logicalDirection
        } else {
            logicalDirection
        }.coerceIn(-1, 1)
        if (physicalDirection == 0) {
            return false
        }

        val generation = ++captureGeneration
        val width = content.width
        val height = content.height
        preparing = true
        destinationTimeout = Runnable {
            if (preparing && captureGeneration == generation) {
                preparing = false
                ++captureGeneration
                destinationTimeout = null
                onReady()
            }
        }.also { content.postDelayed(it, CAPTURE_TIMEOUT_MS) }

        captureContent(generation, width, height) { bitmap ->
            if (!preparing || captureGeneration != generation) {
                bitmap?.recycle()
                return@captureContent
            }

            preparing = false
            destinationTimeout?.let { content.removeCallbacks(it) }
            destinationTimeout = null

            if (bitmap == null || content.width != width || content.height != height) {
                bitmap?.recycle()
                onReady()
                return@captureContent
            }

            val snapshot = ImageView(content.context).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.FIT_XY
                outlineProvider = ViewOutlineProvider.BOUNDS
                elevation = PAGE_EDGE_ELEVATION_DP * resources.displayMetrics.density
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }

            overlay.removeAllViews()
            overlay.clipChildren = true
            overlay.addView(snapshot)
            overlay.isClickable = true
            overlay.visibility = View.VISIBLE

            content.clearAnimation()
            content.translationX = physicalDirection * content.width.toFloat()
            val transition = PendingTransition(
                targetTopLevel = targetTopLevel,
                direction = physicalDirection,
                snapshot = snapshot,
                bitmap = bitmap,
            )
            pending = transition
            destinationTimeout = Runnable {
                if (pending === transition) finishImmediately()
            }.also { content.postDelayed(it, DESTINATION_TIMEOUT_MS) }
            onReady()
        }
        return true
    }

    /** Starts only after Navigation reports the page we prepared for. */
    fun onDestinationChanged(@IdRes topLevel: Int) {
        val transition = pending ?: return
        if (transition.started || transition.targetTopLevel != topLevel) return
        transition.started = true

        // Fragment transactions are committed asynchronously. The first draw
        // after the commit is the earliest point where the incoming view is
        // complete, so listen directly instead of adding an extra queued frame.
        if (pending !== transition) return
        val observer = content.viewTreeObserver
        if (!observer.isAlive) {
            startSafely(transition)
        } else {
            val listener = object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    removePreDrawListener()
                    if (pending === transition) startSafely(transition)
                    return true
                }
            }
            preDrawListener = listener
            observer.addOnPreDrawListener(listener)
            content.invalidate()
        }
    }

    fun cancel() = finishImmediately()

    private fun startSafely(transition: PendingTransition) {
        try {
            start(transition)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to start the page transition", error)
            if (pending === transition) finishImmediately()
        }
    }

    private fun start(transition: PendingTransition) {
        if (!content.isAttachedToWindow || content.width <= 0 ||
            !ValueAnimator.areAnimatorsEnabled()
        ) {
            finishImmediately()
            return
        }

        // Stop a legacy Fragment animation restored with a saved back stack;
        // the NavHost itself is the sole source of motion for this transition.
        content.clearAnimation()
        if (content is ViewGroup) {
            for (index in 0 until content.childCount) {
                content.getChildAt(index).clearAnimation()
            }
        }

        val width = content.width.toFloat()
        val direction = transition.direction.toFloat()
        val incoming = ObjectAnimator.ofFloat(
            content,
            View.TRANSLATION_X,
            content.translationX,
            0f,
        )
        val outgoing = ObjectAnimator.ofFloat(
            transition.snapshot,
            View.TRANSLATION_X,
            0f,
            -direction * width,
        )

        val pageAnimator = AnimatorSet().apply {
            playTogether(incoming, outgoing)
            duration = PAGE_TRANSITION_DURATION_MS
            interpolator = PagerSpringInterpolator
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) finishImmediately()
                }
            })
        }
        animator = pageAnimator
        pageAnimator.start()
    }

    private fun captureContent(
        generation: Long,
        width: Int,
        height: Int,
        onCaptured: (Bitmap?) -> Unit,
    ) {
        val bitmap = try {
            // The NavHost has an opaque background. RGB_565 keeps the temporary
            // full-page copy at half the heap cost while PixelCopy converts the
            // window buffer into the requested format.
            Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565).also {
                it.density = content.resources.displayMetrics.densityDpi
            }
        } catch (error: OutOfMemoryError) {
            Log.w(TAG, "Not enough memory for a page transition snapshot", error)
            mainHandler.post {
                if (captureGeneration == generation && preparing) onCaptured(null)
            }
            return
        }

        val location = IntArray(2)
        content.getLocationInWindow(location)
        val source = Rect(
            location[0],
            location[1],
            location[0] + width,
            location[1] + height,
        )
        try {
            PixelCopy.request(window, source, bitmap, { result ->
                if (captureGeneration != generation || !preparing) {
                    bitmap.recycle()
                    return@request
                }
                if (result == PixelCopy.SUCCESS) {
                    onCaptured(bitmap)
                } else {
                    Log.w(TAG, "PixelCopy failed with result $result")
                    bitmap.recycle()
                    onCaptured(null)
                }
            }, mainHandler)
        } catch (error: RuntimeException) {
            bitmap.recycle()
            Log.w(TAG, "Unable to capture the current page", error)
            mainHandler.post {
                if (captureGeneration == generation && preparing) onCaptured(null)
            }
        }
    }

    private fun finishImmediately() {
        ++captureGeneration
        preparing = false
        removePreDrawListener()
        destinationTimeout?.let { content.removeCallbacks(it) }
        destinationTimeout = null

        animator?.run {
            removeAllListeners()
            cancel()
        }
        animator = null

        content.clearAnimation()
        content.translationX = 0f

        pending?.let { transition ->
            transition.snapshot.setImageDrawable(null)
            overlay.removeView(transition.snapshot)
            if (!transition.bitmap.isRecycled) transition.bitmap.recycle()
        }
        pending = null
        overlay.removeAllViews()
        overlay.isClickable = false
        overlay.visibility = View.INVISIBLE
    }

    private fun removePreDrawListener() {
        val listener = preDrawListener ?: return
        val observer = content.viewTreeObserver
        if (observer.isAlive) observer.removeOnPreDrawListener(listener)
        preDrawListener = null
    }

    private data class PendingTransition(
        @param:IdRes val targetTopLevel: Int,
        val direction: Int,
        val snapshot: ImageView,
        val bitmap: Bitmap,
        var started: Boolean = false,
    )

    private object PagerSpringInterpolator : TimeInterpolator {
        private const val STIFFNESS = 322.2
        private const val DAMPING_COEFFICIENT = 32.31
        private const val SIMULATION_DURATION_SECONDS = 0.5
        private val angularFrequency = sqrt(STIFFNESS)
        private val dampingRatio = DAMPING_COEFFICIENT / (2.0 * angularFrequency)
        private val dampedFrequency = angularFrequency * sqrt(1.0 - dampingRatio * dampingRatio)

        override fun getInterpolation(input: Float): Float {
            if (input <= 0f) return 0f
            if (input >= 1f) return 1f

            val seconds = input.toDouble() * SIMULATION_DURATION_SECONDS
            val decay = exp(-dampingRatio * angularFrequency * seconds)
            val oscillation = cos(dampedFrequency * seconds) +
                dampingRatio * angularFrequency / dampedFrequency *
                sin(dampedFrequency * seconds)
            return (1.0 - decay * oscillation).toFloat()
        }
    }

    private companion object {
        const val TAG = "MiuixPageTransition"
        const val PAGE_TRANSITION_DURATION_MS = 420L
        const val CAPTURE_TIMEOUT_MS = 100L
        const val DESTINATION_TIMEOUT_MS = 1_200L
        const val PAGE_EDGE_ELEVATION_DP = 10f
    }
}
