/*
 * <!--This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2021 LSPosed Contributors-->
 */

package org.lsposed.manager.ui.activity;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import org.lsposed.manager.App;
import org.lsposed.manager.ConfigManager;
import org.lsposed.manager.R;
import org.lsposed.manager.databinding.ActivityMainBinding;
import org.lsposed.manager.repo.RepoLoader;
import org.lsposed.manager.ui.activity.base.BaseActivity;
import org.lsposed.manager.ui.compose.MiuixNavigationController;
import org.lsposed.manager.ui.compose.MainPagerMediator;
import org.lsposed.manager.ui.compose.SecondLevelController;
import org.lsposed.manager.ui.compose.TopLevelPagerAdapter;
import org.lsposed.manager.ui.activity.PagerBackCallback;
import androidx.viewpager2.widget.ViewPager2;
import org.lsposed.manager.util.ModuleUtil;
import org.lsposed.manager.util.UpdateUtil;

import java.util.HashSet;
import java.util.Objects;

public class MainActivity extends BaseActivity implements RepoLoader.RepoListener, ModuleUtil.ModuleListener {
    private static final String KEY_PREFIX = MainActivity.class.getName() + '.';
    private static final String EXTRA_SAVED_INSTANCE_STATE = KEY_PREFIX + "SAVED_INSTANCE_STATE";

    private static final RepoLoader repoLoader = RepoLoader.getInstance();
    private static final ModuleUtil moduleUtil = ModuleUtil.getInstance();

    private boolean restarting;
    private ActivityMainBinding binding;
    private MiuixNavigationController navigationController;
    private TopLevelPagerAdapter topLevelPagerAdapter;
    private MainPagerMediator mainPagerMediator;
    private SecondLevelController secondLevelController;
    private PagerBackCallback pagerBackCallback;
    private OverlayBackCallback overlayBackCallback;
    private NavController mainNavController;

    @NonNull
    public static Intent newIntent(@NonNull Context context) {
        return new Intent(context, MainActivity.class);
    }

    @NonNull
    private static Intent newIntent(@NonNull Bundle savedInstanceState, @NonNull Context context) {
        return newIntent(context)
                .putExtra(EXTRA_SAVED_INSTANCE_STATE, savedInstanceState);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            savedInstanceState = getIntent().getBundleExtra(EXTRA_SAVED_INSTANCE_STATE);
        }
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment);
        if (navHostFragment == null) {
            return;
        }

        NavController navController = navHostFragment.getNavController();
        mainNavController = navController;
        secondLevelController = new SecondLevelController(binding.navHostFragment);
        // Keep PagerBackCallback in sync: while a second-level page overlays the
        // pager, back must pop the nav stack instead of animating the pager home.
        secondLevelController.setOnVisibilityChangedListener(this::updateBackCallback);
        secondLevelController.attach(navController);
        ViewPager2 viewPager = binding.viewPager;
        topLevelPagerAdapter = new TopLevelPagerAdapter(
                getSupportFragmentManager(),
                getLifecycle(),
                viewPager
        );
        viewPager.setAdapter(topLevelPagerAdapter);
        viewPager.setOffscreenPageLimit(1);
        viewPager.post(() -> {
            if (topLevelPagerAdapter != null) {
                viewPager.setOffscreenPageLimit(TopLevelPagerAdapter.PAGE_COUNT - 1);
            }
        });
        mainPagerMediator = new MainPagerMediator(viewPager);
        pagerBackCallback = new PagerBackCallback(mainPagerMediator);
        mainPagerMediator.setOnSelectionChanged(new MainPagerMediator.OnSelectionChangedListener() {
            @Override
            public void onChanged() {
                updateBackCallback();
            }
        });
        getOnBackPressedDispatcher().addCallback(this, pagerBackCallback);
        // Added after the NavHostFragment registered its own callback, so
        // while it is enabled it outranks it and drives the predictive
        // slide-out of second-level pages itself.
        overlayBackCallback = new OverlayBackCallback(secondLevelController, navController);
        getOnBackPressedDispatcher().addCallback(this, overlayBackCallback);
        boolean useNavigationRail = getResources().getConfiguration().smallestScreenWidthDp >= 600;
        // Both the floating pill and the blurred classic bar need the pager to
        // stretch behind the navigation surface.
        boolean barOverlayMode = MiuixNavigationController.isFloatingBottomBarEnabled(this)
                || MiuixNavigationController.isClassicBarBlurEnabled(this);
        if (barOverlayMode) {
            applyFloatingBottomBarLayout(viewPager);
        }
        navigationController = new MiuixNavigationController(
                binding.nav,
                navController,
                mainPagerMediator,
                useNavigationRail,
                barOverlayMode ? viewPager : null
        );
        if (topLevelPagerAdapter != null) {
            topLevelPagerAdapter.setAvailability(
                    ConfigManager.isBinderAlive(),
                    ConfigManager.isMagiskInstalled()
            );
        }
        navigationController.setAvailability(
                ConfigManager.isBinderAlive(),
                ConfigManager.isMagiskInstalled()
        );
        navigationController.setFrameworkUpdateAvailable(UpdateUtil.needUpdate());
        updateBackCallback();


        repoLoader.addListener(this);
        moduleUtil.addListener(this);
        onModulesReloaded();

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment);
        if (navHostFragment == null) {
            return;
        }
        NavController navController = navHostFragment.getNavController();
        if (intent.getAction() != null && intent.getAction().equals("android.intent.action.APPLICATION_PREFERENCES")) {
            navigationController.selectDestination(R.id.settings_fragment);
        } else if (ConfigManager.isBinderAlive()) {
            if (!TextUtils.isEmpty(intent.getDataString())) {
                switch (intent.getDataString()) {
                    case "modules" -> navigationController.selectDestination(R.id.modules_nav);
                    case "logs" -> {
                        navigationController.selectDestinationImmediate(R.id.main_fragment);
                        navController.navigate(R.id.logs_fragment);
                    }
                    case "repo" -> {
                        if (ConfigManager.isMagiskInstalled()) {
                            navigationController.selectDestination(R.id.repo_nav);
                        }
                    }
                    case "settings" -> navigationController.selectDestination(R.id.settings_fragment);
                    default -> {
                        var data = intent.getData();
                        if (data != null && Objects.equals(data.getScheme(), "module")) {
                            navController.navigate(
                                    new Uri.Builder().scheme("lsposed").authority("module").appendQueryParameter("modulePackageName", data.getHost()).appendQueryParameter("moduleUserId", String.valueOf(data.getPort())).build());
                        }
                    }
                }
            }
        }
    }

    private void updateBackCallback() {
        if (pagerBackCallback == null || mainPagerMediator == null || secondLevelController == null) {
            return;
        }
        // The predictive peek can cross into the home page mid-gesture,
        // which flips the selected page to 0; the active flag keeps the
        // callback enabled until the commit/cancel lands.
        pagerBackCallback.setEnabled((mainPagerMediator.isPredictiveBackActive()
                || mainPagerMediator.getCurrentSelectedPage() != 0)
                && !secondLevelController.isOverlayVisible());
        if (overlayBackCallback != null) {
            overlayBackCallback.setEnabled(secondLevelController.isOverlayVisible());
        }
    }

    /**
     * Stretches the pager to full height and the navigation ComposeView to a
     * full-screen overlay, so page content scrolls behind the floating
     * liquid-glass pill which blurs it in real time. Empty overlay areas do
     * not consume touches; the pager keeps receiving gestures.
     */
    private void applyFloatingBottomBarLayout(@NonNull ViewPager2 viewPager) {
        ConstraintLayout.LayoutParams pagerParams =
                (ConstraintLayout.LayoutParams) viewPager.getLayoutParams();
        pagerParams.bottomToTop = ConstraintLayout.LayoutParams.UNSET;
        pagerParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
        viewPager.setLayoutParams(pagerParams);

        ConstraintLayout.LayoutParams navParams =
                (ConstraintLayout.LayoutParams) binding.nav.getLayoutParams();
        navParams.height = ConstraintLayout.LayoutParams.MATCH_PARENT;
        binding.nav.setLayoutParams(navParams);
    }

    @Override
    public boolean onSupportNavigateUp() {
        return super.onSupportNavigateUp();
    }

    public void restart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || App.isParasitic) {
            recreate();
        } else {
            try {
                Bundle savedInstanceState = new Bundle();
                onSaveInstanceState(savedInstanceState);
                finish();
                startActivity(newIntent(savedInstanceState, this));
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                restarting = true;
            } catch (Throwable e) {
                recreate();
            }
        }
    }

    @Override
    public boolean dispatchKeyEvent(@NonNull KeyEvent event) {
        return restarting || super.dispatchKeyEvent(event);
    }

    @SuppressLint("RestrictedApi")
    @Override
    public boolean dispatchKeyShortcutEvent(@NonNull KeyEvent event) {
        return restarting || super.dispatchKeyShortcutEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(@NonNull MotionEvent event) {
        return restarting || super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchTrackballEvent(@NonNull MotionEvent event) {
        return restarting || super.dispatchTrackballEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(@NonNull MotionEvent event) {
        return restarting || super.dispatchGenericMotionEvent(event);
    }


    @Override
    public void onRepoLoaded() {
        final int[] count = new int[]{0};
        HashSet<String> processedModules = new HashSet<>();
        var modules = moduleUtil.getModules();
        if (modules == null) return;
        modules.forEach((k, v) -> {
                    if (!processedModules.contains(k.first)) {
                        var ver = repoLoader.getModuleLatestVersion(k.first);
                        if (ver != null && ver.upgradable(v.versionCode, v.versionName)) {
                            ++count[0];
                        }
                        processedModules.add(k.first);
                    }
                }
        );
        runOnUiThread(() -> {
            if (navigationController != null) {
                navigationController.setRepoUpdateCount(count[0]);
            }
        });
    }

    @Override
    public void onThrowable(Throwable t) {
        runOnUiThread(() -> {
            if (navigationController != null) {
                navigationController.setRepoUpdateCount(0);
            }
        });
    }

    @Override
    public void onModulesReloaded() {
        onRepoLoaded();
        setModulesSummary(moduleUtil.getEnabledModulesCount());
    }

    @Override
    public void onResume() {
        super.onResume();
        if (ConfigManager.isBinderAlive()) {
            setModulesSummary(moduleUtil.getEnabledModulesCount());
        } else setModulesSummary(0);
        if (navigationController != null) {
            navigationController.setFrameworkUpdateAvailable(UpdateUtil.needUpdate());
            navigationController.setAvailability(
                    ConfigManager.isBinderAlive(),
                    ConfigManager.isMagiskInstalled()
            );
        }
    }

    private void setModulesSummary(int moduleCount) {
        runOnUiThread(() -> {
            if (navigationController != null) {
                navigationController.setModuleCount(moduleCount);
            }
        });
    }

    @Override
    protected void onDestroy() {
        repoLoader.removeListener(this);
        moduleUtil.removeListener(this);
        if (navigationController != null) {
            navigationController.dispose();
            navigationController = null;
        }
        if (mainPagerMediator != null) {
            mainPagerMediator.dispose();
            mainPagerMediator = null;
        }
        if (secondLevelController != null) {
            secondLevelController.detach(mainNavController);
            secondLevelController = null;
        }
        if (pagerBackCallback != null) {
            pagerBackCallback.remove();
            pagerBackCallback = null;
        }
        if (overlayBackCallback != null) {
            overlayBackCallback.remove();
            overlayBackCallback = null;
        }
        super.onDestroy();
    }
}
