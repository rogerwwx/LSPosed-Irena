package org.lsposed.manager.ui.compose

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
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
    var onSelectionChanged: ((Int) -> Unit)? = null

    var isNavigating: Boolean = false
        private set

    private var animator: ValueAnimator? = null
    private var lastFraction = 0f
    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            if (!isNavigating) selectedPageInternal.value = position
            onSelectionChanged?.invoke(selectedPageInternal.value)
        }

        override fun onPageScrollStateChanged(state: Int) {
            if (state == ViewPager2.SCROLL_STATE_DRAGGING && isNavigating) {
                cancelAnimator()
                isNavigating = false
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

        if (beginFakeDrag()) {
            isNavigating = true
            val start = pager.currentItem
            val distance = boundedTarget - start
            lastFraction = 0f
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 420L
                interpolator = PagerSpringInterpolator
                addUpdateListener { animation ->
                    val fraction = animation.animatedValue as Float
                    val delta = (fraction - lastFraction) * distance * pager.width
                    if (abs(delta) >= 1f) pager.fakeDragBy(-delta)
                    lastFraction = fraction
                }
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false

                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (isNavigating) {
                            endFakeDrag()
                            isNavigating = false
                        }
                        if (!cancelled && pager.currentItem != boundedTarget) {
                            pager.setCurrentItem(boundedTarget, false)
                            selectedPageInternal.value = boundedTarget
                        }
                    }
                })
                start()
            }
        } else {
            pager.setCurrentItem(boundedTarget, true)
        }
    }

    fun dispose() {
        cancelAnimator()
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
