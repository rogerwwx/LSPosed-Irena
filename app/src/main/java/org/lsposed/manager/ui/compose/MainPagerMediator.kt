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

    var onSelectionChanged: OnSelectionChangedListener? = null

    var isNavigating: Boolean = false
        private set

    private var animator: ValueAnimator? = null
    private var lastFraction = 0f
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

        if (isNavigating && pager.isFakeDragging) {
            endFakeDrag()
        }
        isNavigating = false

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
                            pager.setCurrentItem(boundedTarget, false)
                            selectedPageInternal.value = boundedTarget
                        }
                    }
                })
                start()
            }
        }
    }

    fun dispose() {
        cancelAnimator()
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
