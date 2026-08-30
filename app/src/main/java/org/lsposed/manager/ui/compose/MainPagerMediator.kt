package org.lsposed.manager.ui.compose

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import androidx.annotation.IdRes
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

class MainPagerMediator(
    private val pager: ViewPager2,
) {
    private val selectedPageInternal = MutableStateFlow(pager.currentItem)
    val selectedPage: StateFlow<Int> = selectedPageInternal.asStateFlow()
    fun interface OnSelectionChangedListener {
        fun onChanged()
    }

    val currentSelectedPage: Int
        get() = selectedPageInternal.value

    fun animateToPageId(@IdRes pageId: Int) {
        val adapter = pager.adapter as? TopLevelPagerAdapter
        val target = adapter?.getPositionForId(pageId) ?: -1
        if (target < 0) {
            animateToPage(0)
        } else {
            animateToPage(target)
        }
    }

    /**
     * Switches the pager without the fake-drag animation. Used when a
     * second-level overlay opens right after (e.g. the logs deep link), so the
     * pager work does not overlap and stall the overlay slide.
     */
    fun jumpToPageId(@IdRes pageId: Int) {
        val adapter = pager.adapter as? TopLevelPagerAdapter
        val target = adapter?.getPositionForId(pageId) ?: 0
        val boundedTarget = target.coerceIn(0, (pager.adapter?.itemCount ?: 1) - 1)
        cancelAnimator()
        if (pager.isFakeDragging) {
            endFakeDrag()
        }
        isNavigating = false
        if (pager.currentItem != boundedTarget) {
            pager.setCurrentItem(boundedTarget, false)
        }
        selectedPageInternal.value = boundedTarget
        onSelectionChanged?.onChanged()
    }

    var onSelectionChanged: OnSelectionChangedListener? = null

    var isNavigating: Boolean = false
        private set

    private var animator: ValueAnimator? = null
    private var lastFraction = 0f
    private var fakeDragDistance = 0f
    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            val selectionChanged = !isNavigating && selectedPageInternal.value != position
            if (selectionChanged) selectedPageInternal.value = position
            if (selectionChanged) onSelectionChanged?.onChanged()
        }

        override fun onPageScrollStateChanged(state: Int) {
            if (state == ViewPager2.SCROLL_STATE_IDLE) {
                onSelectionChanged?.onChanged()
            }
        }
    }

    init {
        pager.registerOnPageChangeCallback(pageChangeCallback)
    }

    fun animateToPage(target: Int) {
        val boundedTarget = target.coerceIn(0, (pager.adapter?.itemCount ?: 1) - 1)
        if (boundedTarget == pager.currentItem && !isNavigating) {
            selectedPageInternal.value = boundedTarget
            return
        }

        cancelAnimator()
        selectedPageInternal.value = boundedTarget

        if (predictiveDragging) {
            // Stray navigation during an unfinished predictive peek: close
            // the fake drag session before taking a new one.
            predictiveDragging = false
            endFakeDrag()
        }
        if (isNavigating && pager.isFakeDragging) {
            endFakeDrag()
        }
        isNavigating = false

        if (beginFakeDrag()) {
            isNavigating = true
            val start = pager.currentItem
            val distance = boundedTarget - start
            lastFraction = 0f
            fakeDragDistance = 0f
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 420L
                interpolator = PagerSpringInterpolator
                addUpdateListener { animation ->
                    val fraction = animation.animatedValue as Float
                    val delta = (fraction - lastFraction) * distance * pager.width
                    if (abs(delta) >= 1f) {
                        pager.fakeDragBy(-delta)
                        fakeDragDistance += delta
                    }
                    lastFraction = fraction
                }
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false

                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (isNavigating && !cancelled) {
                            val totalDistance = abs(distance) * pager.width.coerceAtLeast(1)
                            val remainingDistance = totalDistance - abs(fakeDragDistance)
                            if (pager.isFakeDragging && abs(remainingDistance) >= .01f) {
                                val direction = if (distance < 0) -1f else 1f
                                pager.fakeDragBy(-direction * remainingDistance)
                            }
                            endFakeDrag()
                            isNavigating = false
                            if (pager.currentItem != boundedTarget) {
                                pager.setCurrentItem(boundedTarget, false)
                            }
                            selectedPageInternal.value = boundedTarget
                        }
                    }
                })
                start()
            }
        } else {
            // A settle from a cancelled predictive peek can still be finishing
            // and blocking the fake drag; fall back to the widget's own smooth
            // scroll so the navigation never silently drops.
            pager.setCurrentItem(boundedTarget, true)
        }
    }

    /**
     * Predictive back peek: while the system back gesture progresses, drag
     * the pager toward the home page by at most one page width. The commit
     * hands the remaining distance to [animateToPage]; the cancel ends the
     * fake drag and lets the pager settle back where it was.
     */
    private var predictiveDragging = false
    private var predictiveLastFraction = 0f

    /**
     * True between handleOnBackStarted and its commit/cancel. The back
     * callback must stay enabled through this window even when the peek
     * crosses into the home page (which flips the selected page to 0 and
     * would otherwise drop the commit and strand the fake drag session).
     */
    val isPredictiveBackActive: Boolean
        get() = predictiveDragging

    fun beginPredictiveBack() {
        if (predictiveDragging || isNavigating) return
        if (!beginFakeDrag()) return
        predictiveDragging = true
        predictiveLastFraction = 0f
    }

    fun updatePredictiveBack(fraction: Float) {
        if (!predictiveDragging) return
        val clamped = fraction.coerceIn(0f, 1f)
        // Positive dx scrolls toward lower page indices, i.e. the home page —
        // the same convention animateToPage uses to fly to page 0.
        val delta = (clamped - predictiveLastFraction) * pager.width
        if (abs(delta) >= 1f) {
            pager.fakeDragBy(delta)
            predictiveLastFraction = clamped
        }
    }

    fun cancelPredictiveBack() {
        if (!predictiveDragging) return
        predictiveDragging = false
        endFakeDrag()
    }

    fun commitPredictiveBack() {
        if (predictiveDragging) {
            predictiveDragging = false
            endFakeDrag()
        }
        animateToPage(0)
    }

    fun dispose() {
        cancelAnimator()
        predictiveDragging = false
        endFakeDrag()
        isNavigating = false
        pager.unregisterOnPageChangeCallback(pageChangeCallback)
    }

    private fun beginFakeDrag(): Boolean {
        return try {
            pager.beginFakeDrag()
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun endFakeDrag() {
        try {
            if (pager.isFakeDragging) pager.endFakeDrag()
        } catch (_: RuntimeException) {
        }
    }

    private fun cancelAnimator() {
        val animation = animator ?: return
        animation.cancel()
        animator = null
    }
}
