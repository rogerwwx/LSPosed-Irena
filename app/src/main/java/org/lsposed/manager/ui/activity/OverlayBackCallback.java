package org.lsposed.manager.ui.activity;

import androidx.activity.BackEventCompat;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.navigation.NavController;

import org.lsposed.manager.ui.compose.SecondLevelController;
import org.lsposed.manager.util.ThemeUtil;

/**
 * Second-level pages: back pops the nav overlay. While enabled it outranks
 * the NavHostFragment's own callback, so the commit performs the pop itself.
 * With predictive back enabled the overlay slides out following the gesture
 * before the commit pops it.
 */
public class OverlayBackCallback extends OnBackPressedCallback {
    private final SecondLevelController controller;
    private final NavController navController;

    public OverlayBackCallback(SecondLevelController controller, NavController navController) {
        super(false);
        this.controller = controller;
        this.navController = navController;
    }

    @Override
    public void handleOnBackStarted(@NonNull BackEventCompat backEvent) {
        if (ThemeUtil.isPredictiveBackEnabled()) {
            controller.beginPredictiveDismiss();
        }
    }

    @Override
    public void handleOnBackProgressed(@NonNull BackEventCompat backEvent) {
        if (ThemeUtil.isPredictiveBackEnabled()) {
            controller.updatePredictiveDismiss(backEvent.getProgress());
        }
    }

    @Override
    public void handleOnBackCancelled() {
        if (ThemeUtil.isPredictiveBackEnabled()) {
            controller.cancelPredictiveDismiss();
        }
    }

    @Override
    public void handleOnBackPressed() {
        if (ThemeUtil.isPredictiveBackEnabled()) {
            controller.commitPredictiveDismiss();
        }
        navController.navigateUp();
    }
}
