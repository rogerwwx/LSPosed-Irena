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
     * Slides in when the freshly added fragment view reports its first real
     * layout, so the animation only composites a static full-screen layer
     * instead of competing with view inflation.
     */
    private fun waitForContentAndSlideIn() {
        val group = navHostView as? ViewGroup
        if (group == null) {
            slideIn(navHostView.width)
            return
        }
        val hierarchyListener = object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) {
                val content = child ?: return
                content.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        if (content.width > 0 && content.height > 0) {
                            content.viewTreeObserver.removeOnGlobalLayoutListener(this)
                            group.setOnHierarchyChangeListener(null)
                            if (isOverlayVisible && navHostView.visibility == View.VISIBLE) {
                                slideIn(navHostView.width)
                            }
                        }
                    }
                })
            }

            override fun onChildViewRemoved(parent: View?, child: View?) = Unit
        }
        group.setOnHierarchyChangeListener(hierarchyListener)

        // Safety net: if the content never reports a layout (e.g. an empty
        // fragment), slide in anyway shortly after.
        val fallback = Runnable {
            if (isOverlayVisible && navHostView.visibility == View.VISIBLE && !slideInStarted) {
                group.setOnHierarchyChangeListener(null)
                slideIn(navHostView.width)
            }
        }
        pendingSlideInRunnable = fallback
        navHostView.postDelayed(fallback, 800L)
    }

    private fun clearPendingSlideIn() {
        (navHostView as? ViewGroup)?.setOnHierarchyChangeListener(null)
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
        navHostView.animate()
            .translationX(0f)
            .setDuration(320L)
            .setInterpolator(PagerSpringInterpolator)
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
}
