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
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
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
        secondLevelController.attach(navController);
        ViewPager2 viewPager = binding.viewPager;
        topLevelPagerAdapter = new TopLevelPagerAdapter(getSupportFragmentManager(), viewPager);
        viewPager.setAdapter(topLevelPagerAdapter);
        viewPager.setOffscreenPageLimit(1);
        viewPager.post(() -> {
            if (topLevelPagerAdapter != null) {
                viewPager.setOffscreenPageLimit(TopLevelPagerAdapter.PAGE_COUNT - 1);
            }
        });
        mainPagerMediator = new MainPagerMediator(viewPager);
        pagerBackCallback = new PagerBackCallback(() -> mainPagerMediator.animateToPage(0));
        mainPagerMediator.setOnSelectionChanged(new MainPagerMediator.OnSelectionChangedListener() {
            @Override
            public void onChanged() {
                updateBackCallback();
            }
        });
        getOnBackPressedDispatcher().addCallback(this, pagerBackCallback);
        navigationController = new MiuixNavigationController(
                binding.nav,
                navController,
                mainPagerMediator,
                getResources().getConfiguration().smallestScreenWidthDp >= 600
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
                        navigationController.selectDestination(R.id.main_fragment);
                        navController.navigate(R.id.logs_fragment, null, new NavOptions.Builder()
                                .setEnterAnim(R.anim.fragment_enter).setExitAnim(R.anim.fragment_exit)
                                .setPopEnterAnim(R.anim.fragment_enter_pop).setPopExitAnim(R.anim.fragment_exit_pop)
                                .setLaunchSingleTop(true).build());
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
                                    new Uri.Builder().scheme("lsposed").authority("module").appendQueryParameter("modulePackageName", data.getHost()).appendQueryParameter("moduleUserId", String.valueOf(data.getPort())).build(),
                                    new NavOptions.Builder().setEnterAnim(R.anim.fragment_enter).setExitAnim(R.anim.fragment_exit).setPopEnterAnim(R.anim.fragment_enter_pop).setPopExitAnim(R.anim.fragment_exit_pop).setLaunchSingleTop(true).build());
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
        pagerBackCallback.setEnabled(mainPagerMediator.getCurrentSelectedPage() != 0 && !secondLevelController.isOverlayVisible());
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
        super.onDestroy();
    }
}
