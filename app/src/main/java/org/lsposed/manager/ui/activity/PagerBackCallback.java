package org.lsposed.manager.ui.activity;

import androidx.activity.BackEventCompat;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;

import org.lsposed.manager.ui.compose.MainPagerMediator;
import org.lsposed.manager.util.ThemeUtil;

/**
 * Top-level pages: back flies the pager home (page 0). With predictive back
 * enabled the pager follows the gesture by one page width before the commit
 * flies the rest of the way; the switch is read live so it needs no restart.
 */
public class PagerBackCallback extends OnBackPressedCallback {
    private final MainPagerMediator mediator;

    public PagerBackCallback(MainPagerMediator mediator) {
        super(false);
        this.mediator = mediator;
    }

    @Override
    public void handleOnBackStarted(@NonNull BackEventCompat backEvent) {
        if (ThemeUtil.isPredictiveBackEnabled()) {
            mediator.beginPredictiveBack();
        }
    }

    @Override
    public void handleOnBackProgressed(@NonNull BackEventCompat backEvent) {
        if (ThemeUtil.isPredictiveBackEnabled()) {
            mediator.updatePredictiveBack(backEvent.getProgress());
        }
    }

    @Override
    public void handleOnBackCancelled() {
        if (ThemeUtil.isPredictiveBackEnabled()) {
            mediator.cancelPredictiveBack();
        }
    }

    @Override
    public void handleOnBackPressed() {
        if (ThemeUtil.isPredictiveBackEnabled()) {
            mediator.commitPredictiveBack();
        } else {
            mediator.animateToPage(0);
        }
    }
}
