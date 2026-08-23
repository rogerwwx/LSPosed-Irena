package org.lsposed.manager.ui.compose

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.navigation.NavController
import org.lsposed.manager.R

class SecondLevelController(
    private val navHostView: View,
) {
    /** Notified whenever [isOverlayVisible] flips, so owners can resync back dispatch. */
    fun interface OnVisibilityChangedListener {
        fun onVisibilityChanged()
    }

    var isOverlayVisible: Boolean = false
        private set

    var onVisibilityChangedListener: OnVisibilityChangedListener? = null

    private var pendingSlideInRunnable: Runnable? = null
    private var pendingLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var slideInStarted = false

    private val destinationListener = NavController.OnDestinationChangedListener { _, destination, _ ->
        if (destination.id == R.id.top_level_stub) {
            hide()
        } else {
            show()
        }
    }

    fun attach(navController: NavController) {
        navController.addOnDestinationChangedListener(destinationListener)
        if (navController.currentDestination?.id == R.id.top_level_stub) {
            hideImmediately()
        } else {
            show()
        }
    }

    fun detach(navController: NavController) {
        navController.removeOnDestinationChangedListener(destinationListener)
        cancelAnimation()
        clearPendingSlideIn()
    }

    private fun show() {
        // Navigating between two second-level pages: the overlay is already in
        // place and only the content changed, so do not re-slide it.
        val alreadyShown = isOverlayVisible && navHostView.visibility == View.VISIBLE && navHostView.translationX == 0f
        cancelAnimation()
        clearPendingSlideIn()
        updateVisibility(true)
        navHostView.visibility = View.VISIBLE
        if (alreadyShown) {
            return
        }
        // Pre-position off-screen so the destination content is built without
        // being seen, then slide in once its view is attached and laid out.
        // Starting the slide while the fragment inflates (the scope page and
        // logs are the heaviest) stalls every frame on the main thread.
        val width = navHostView.width
        navHostView.translationX = if (width > 0) width.toFloat() else navHostView.rootView.width.toFloat()
        waitForContentAndSlideIn()
    }

    /**
     * Slides in once the freshly added fragment view has a real layout, so the
     * animation composites a static full-screen layer instead of competing
     * with view inflation.
     *
     * The wait observes layouts on the host itself: it survives every
     * ordering between the async fragment transaction and this call, unlike a
     * hierarchy-change listener chain that silently misses the child. The
     * empty top-level stub (a zero-size view) never satisfies the check.
     */
    private fun waitForContentAndSlideIn() {
        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (!hasLaidOutContent()) return
                clearPendingSlideIn()
                if (isOverlayVisible && navHostView.visibility == View.VISIBLE) {
                    slideIn(navHostView.width)
                }
            }
        }
        pendingLayoutListener = listener
        navHostView.viewTreeObserver.addOnGlobalLayoutListener(listener)

        // Bounded freeze: if a pathologically heavy page has not finished its
        // first layout by now, slide anyway rather than holding the old page.
        val fallback = Runnable {
            if (isOverlayVisible && navHostView.visibility == View.VISIBLE && !slideInStarted) {
                clearPendingSlideIn()
                slideIn(navHostView.width)
            }
        }
        pendingSlideInRunnable = fallback
        navHostView.postDelayed(fallback, PREBUILD_BUDGET_MS)
    }

    private fun hasLaidOutContent(): Boolean {
        val group = navHostView as? ViewGroup ?: return navHostView.width > 0
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            if (child.width > 0 && child.height > 0) return true
        }
        return false
    }

    private fun clearPendingSlideIn() {
        pendingLayoutListener?.let { listener ->
            val observer = navHostView.viewTreeObserver
            if (observer.isAlive) {
                observer.removeOnGlobalLayoutListener(listener)
            }
        }
        pendingLayoutListener = null
        pendingSlideInRunnable?.let { navHostView.removeCallbacks(it) }
        pendingSlideInRunnable = null
    }

    private fun slideIn(width: Int) {
        if (slideInStarted) {
            return
        }
        slideInStarted = true
        val w = if (width > 0) width.toFloat() else navHostView.rootView.width.toFloat()
        navHostView.translationX = w
        // The hardware layer renders the freshly built content once before
        // the first animation frame, so the 320ms slide is pure composition
        // even when the fragment's async data lands mid-flight.
        navHostView.animate()
            .translationX(0f)
            .setDuration(320L)
            .setInterpolator(PagerSpringInterpolator)
            .withLayer()
            .withEndAction { slideInStarted = false }
            .start()
    }

    private fun hide() {
        cancelAnimation()
        clearPendingSlideIn()
        updateVisibility(false)
        val width = navHostView.width.toFloat()
        if (width == 0f || !navHostView.isAttachedToWindow) {
            hideImmediately()
            return
        }
        navHostView.animate()
            .translationX(width)
            .setDuration(320L)
            .setInterpolator(PagerSpringInterpolator)
            .withLayer()
            .withEndAction { hideImmediately() }
            .start()
    }

    private fun hideImmediately() {
        cancelAnimation()
        clearPendingSlideIn()
        updateVisibility(false)
        navHostView.translationX = 0f
        navHostView.visibility = View.GONE
    }

    private fun updateVisibility(visible: Boolean) {
        val changed = isOverlayVisible != visible
        isOverlayVisible = visible
        if (changed) onVisibilityChangedListener?.onVisibilityChanged()
    }

    private fun cancelAnimation() {
        navHostView.animate().cancel()
    }

    private companion object {
        /**
         * How long the old page may stay still while the destination builds
         * off-screen. Measured builds of the heaviest page (module scope)
         * land well below this; the cap only exists so a broken page can
         * never wedge the entry.
         */
        private const val PREBUILD_BUDGET_MS = 350L
    }
}
