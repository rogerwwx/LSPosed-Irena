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
        visualPagePosition = boundedTarget.toFloat()
        selectedPageInternal.value = boundedTarget
        onSelectionChanged?.onChanged()
    }

    var onSelectionChanged: OnSelectionChangedListener? = null

    var isNavigating: Boolean = false
        private set

    private var animator: ValueAnimator? = null
    private var lastFraction = 0f
    private var fakeDragDistance = 0f

    /**
     * Live visual page position (position + offset) from onPageScrolled.
     * [ViewPager2.getCurrentItem] only advances when a page selection is
     * dispatched — during a fake drag that happens at the very end — so it
     * cannot anchor a new navigation that interrupts one mid-flight: the
     * distance would be wrong and the flight would stall or freeze.
     */
    private var visualPagePosition = pager.currentItem.toFloat()

    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            val selectionChanged = !isNavigating && selectedPageInternal.value != position
            if (selectionChanged) selectedPageInternal.value = position
            if (selectionChanged) onSelectionChanged?.onChanged()
        }

        override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPx: Int) {
            visualPagePosition = position + positionOffset
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
        if (!isNavigating && abs(visualPagePosition - boundedTarget) < .01f) {
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

        // Before the first layout pass (e.g. a deep link straight from
        // onCreate) every pixel delta would be zero: the animation would
        // freeze for its whole duration and then jump. Jump right away.
        if (pager.width <= 0) {
            isNavigating = false
            if (pager.currentItem != boundedTarget) {
                pager.setCurrentItem(boundedTarget, false)
            }
            visualPagePosition = boundedTarget.toFloat()
            return
        }

        // Set before acquiring the session: tearing down the interrupted
        // navigation and starting the new one dispatches page events that
        // must not be mistaken for a user-driven selection change.
        isNavigating = true

        if (!beginFakeDragReliably()) {
            isNavigating = false
            if (pager.scrollState == ViewPager2.SCROLL_STATE_DRAGGING) {
                // The user is mid-gesture on the pager; a programmatic scroll
                // would fight it. The gesture's settle dispatches
                // onPageSelected, which restores the selection.
                return
            }
            pager.setCurrentItem(boundedTarget, true)
            return
        }

        val start = visualPagePosition
        val distance = boundedTarget - start
        lastFraction = 0f
        fakeDragDistance = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = animationDuration(distance)
            interpolator = PagerSpringInterpolator
            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                val delta = (fraction - lastFraction) * distance * pager.width
                lastFraction = fraction
                if (abs(delta) < 1f) return@addUpdateListener
                if (!pager.fakeDragBy(-delta)) {
                    // The fake drag session was lost mid-flight (a real touch
                    // or an accessibility scroll took over the pager). Stop
                    // animating instead of freezing for the rest of the
                    // duration and teleporting at the end.
                    abandonFakeDrag(boundedTarget)
                    return@addUpdateListener
                }
                fakeDragDistance += delta
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
                        visualPagePosition = boundedTarget.toFloat()
                        selectedPageInternal.value = boundedTarget
                    }
                }
            })
            start()
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

    /**
     * [ViewPager2.beginFakeDrag] can return true for a session that is
     * already dead: right after registering the fake drag it stops whatever
     * RecyclerView scroll is still finishing — every ended fake drag leaves a
     * short settle behind — and the resulting IDLE notification resets the
     * ScrollEventAdapter's fake-drag state synchronously. Every later
     * fakeDragBy is then a silent no-op and the navigation freezes for its
     * whole duration before jumping to the target.
     *
     * One retry is enough to recover: the first attempt already stopped the
     * RecyclerView, so the second beginFakeDrag fires no state notification
     * and its session survives.
     */
    private fun beginFakeDragReliably(): Boolean {
        if (!beginFakeDrag()) return false
        if (pager.isFakeDragging) return true
        beginFakeDrag()
        return pager.isFakeDragging
    }

    private fun abandonFakeDrag(target: Int) {
        cancelAnimator()
        isNavigating = false
        if (pager.scrollState == ViewPager2.SCROLL_STATE_DRAGGING) {
            // The user's gesture owns the pager now; its settle dispatches
            // onPageSelected and the selection follows it.
            return
        }
        pager.setCurrentItem(target, true)
    }

    /** Full-page flights keep the tuned duration; short remainders finish faster. */
    private fun animationDuration(distancePages: Float): Long {
        val pages = abs(distancePages).coerceAtMost(1f)
        return (140L + 280L * pages).toLong()
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
