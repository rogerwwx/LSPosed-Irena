package org.lsposed.manager.ui.compose

import android.view.View
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
    }

    private fun show() {
        cancelAnimation()
        updateVisibility(true)
        navHostView.visibility = View.VISIBLE
        val width = navHostView.width.toFloat()
        if (width > 0f) {
            navHostView.translationX = width
        } else {
            navHostView.translationX = navHostView.rootView.width.toFloat()
            navHostView.post {
                if (isOverlayVisible && navHostView.isAttachedToWindow && navHostView.visibility == View.VISIBLE) {
                    navHostView.translationX = navHostView.width.toFloat()
                    navHostView.animate()
                        .translationX(0f)
                        .setDuration(320L)
                        .setInterpolator(PagerSpringInterpolator)
                        .start()
                }
            }
            return
        }
        navHostView.animate()
            .translationX(0f)
            .setDuration(320L)
            .setInterpolator(PagerSpringInterpolator)
            .start()
    }

    private fun hide() {
        cancelAnimation()
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
