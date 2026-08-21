package org.lsposed.manager.ui.compose

import android.view.View
import androidx.navigation.NavController
import org.lsposed.manager.R

class SecondLevelController(
    private val navHostView: View,
) {
    var isOverlayVisible: Boolean = false
        private set

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
            isOverlayVisible = true
            navHostView.visibility = View.VISIBLE
            navHostView.translationX = 0f
        }
    }

    fun detach(navController: NavController) {
        navController.removeOnDestinationChangedListener(destinationListener)
        cancelAnimation()
    }

    private fun show() {
        cancelAnimation()
        isOverlayVisible = true
        navHostView.visibility = View.VISIBLE
        val width = navHostView.width.toFloat()
        navHostView.translationX = width
        navHostView.animate()
            .translationX(0f)
            .setDuration(320L)
            .setInterpolator(PagerSpringInterpolator)
            .start()
    }

    private fun hide() {
        cancelAnimation()
        isOverlayVisible = false
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
        isOverlayVisible = false
        navHostView.translationX = 0f
        navHostView.visibility = View.GONE
    }

    private fun cancelAnimation() {
        navHostView.animate().cancel()
    }
}
