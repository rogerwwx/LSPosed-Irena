package org.lsposed.manager.ui.activity;

import androidx.activity.OnBackPressedCallback;

public class PagerBackCallback extends OnBackPressedCallback {
    private final Runnable animateToHome;

    public PagerBackCallback(Runnable animateToHome) {
        super(false);
        this.animateToHome = animateToHome;
    }

    @Override
    public void handleOnBackPressed() {
        animateToHome.run();
    }
}
