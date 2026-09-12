package org.lsposed.manager.ui.compose

import android.view.View
import android.view.ViewTreeObserver
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import org.lsposed.manager.R
import org.lsposed.manager.ui.fragment.TopLevelStubFragment

class SecondLevelController(
    private val navHostView: View,
    private val navHostFragment: NavHostFragment,
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
     * Observe layouts on the host, but check the destination fragment's view.
     * The NavHost's intermediate container can be laid out while the actual
     * destination is still being created or the top-level stub is showing.
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
        val fragment = navHostFragment.childFragmentManager.primaryNavigationFragment ?: return false
        if (fragment is TopLevelStubFragment) return false
        val content = fragment.view ?: return false
        return content.isAttachedToWindow && content.width > 0 && content.height > 0
            && !content.isLayoutRequested
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

    private var predictiveDismiss = false

    /**
     * Predictive back for second-level pages: the overlay slides out the way
     * it slid in, tracking the system back progress. The commit pops the nav
     * stack and the regular hide() continues from wherever the gesture left
     * it; the cancel springs the overlay back into place.
     */
    fun beginPredictiveDismiss() {
        if (!isOverlayVisible) return
        cancelAnimation()
        clearPendingSlideIn()
        slideInStarted = false
        predictiveDismiss = true
    }

    fun updatePredictiveDismiss(fraction: Float) {
        if (!predictiveDismiss) return
        navHostView.translationX = fraction.coerceIn(0f, 1f) * navHostView.width
    }

    fun cancelPredictiveDismiss() {
        if (!predictiveDismiss) return
        predictiveDismiss = false
        navHostView.animate()
            .translationX(0f)
            .setDuration(320L)
            .setInterpolator(PagerSpringInterpolator)
            .withLayer()
            .start()
    }

    fun commitPredictiveDismiss() {
        if (!predictiveDismiss) return
        predictiveDismiss = false
        // Keep the current translation; hide() takes over on pop.
    }

    private fun cancelAnimation() {
        // A cancelled ViewPropertyAnimator does not run withEndAction. Leaving
        // slideInStarted set would strand the next destination off-screen and
        // also disable its layout/timeout recovery paths.
        navHostView.animate().withEndAction(null).cancel()
        slideInStarted = false
        predictiveDismiss = false
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
