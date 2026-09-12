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

package org.lsposed.manager.ui.fragment;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;
import androidx.core.view.MenuProvider;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.lsposed.manager.App;
import org.lsposed.manager.ConfigManager;
import org.lsposed.manager.R;
import org.lsposed.manager.adapters.AppHelper;
import org.lsposed.manager.adapters.ScopeAdapter;
import org.lsposed.manager.databinding.FragmentAppListBinding;
import org.lsposed.manager.ui.widget.ListCardDecoration;
import org.lsposed.manager.util.BackupUtils;
import org.lsposed.manager.util.ModuleUtil;
import org.lsposed.manager.util.ThemeUtil;

import rikka.material.app.LocaleDelegate;
import rikka.recyclerview.RecyclerViewKt;

public class AppListFragment extends BaseFragment implements MenuProvider {

    public SearchView searchView;
    private ScopeAdapter scopeAdapter;
    private ModuleUtil.InstalledModule module;
    private String modulePackageName;
    private int moduleUserId;
    private ModuleUtil.ModuleListener awaitingModule;
    private OnBackPressedCallback backPressedCallback;

    private SearchView.OnQueryTextListener searchListener;
    public FragmentAppListBinding binding;
    public ActivityResultLauncher<String> backupLauncher;
    public ActivityResultLauncher<String[]> restoreLauncher;

    private final RecyclerView.AdapterDataObserver observer = new RecyclerView.AdapterDataObserver() {
        @Override
        public void onChanged() {
            if (binding != null && scopeAdapter != null) {
                binding.swipeRefreshLayout.setRefreshing(!scopeAdapter.isLoaded());
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentAppListBinding.inflate(getLayoutInflater(), container, false);
        if (module == null) {
            // The module scan has not published a snapshot yet: hold the page
            // open with a loading subtitle instead of leaving. buildContent()
            // runs once the awaited module is published.
            binding.appBar.setLiftable(true);
            setupToolbar(binding.toolbar, binding.clickView, modulePackageName, -1,
                    view -> requireActivity().getOnBackPressedDispatcher().onBackPressed());
            binding.toolbar.setSubtitle(getString(R.string.loading));
            return binding.getRoot();
        }
        buildContent();
        return binding.getRoot();
    }

    private void buildContent() {
        binding.appBar.setLiftable(true);
        String title;
        if (module.userId != 0) {
            title = String.format(LocaleDelegate.getDefaultLocale(), "%s (%d)", module.getAppName(), module.userId);
        } else {
            title = module.getAppName();
        }
        binding.toolbar.setSubtitle(module.packageName);

        scopeAdapter = new ScopeAdapter(this, module);
        scopeAdapter.setHasStableIds(true);
        scopeAdapter.registerAdapterDataObserver(observer);
        var concatAdapter = new ConcatAdapter();
        concatAdapter.addAdapter(scopeAdapter.switchAdaptor);
        concatAdapter.addAdapter(scopeAdapter);
        binding.recyclerView.setAdapter(concatAdapter);
        binding.recyclerView.setHasFixedSize(true);
        binding.recyclerView.setLayoutManager(new LinearLayoutManager(requireActivity()));
        binding.recyclerView.getBorderViewDelegate().setBorderVisibilityChangedListener((top, oldTop, bottom, oldBottom) -> binding.appBar.setLifted(!top));
        RecyclerViewKt.fixEdgeEffect(binding.recyclerView, false, true);
        // M3E draws every scope row as its own rounded card; MIUIX keeps rows.
        if (!ThemeUtil.isMiuixStyle()) {
            binding.recyclerView.addItemDecoration(new ListCardDecoration(requireContext()));
        }
        binding.swipeRefreshLayout.setOnRefreshListener(() -> scopeAdapter.refresh(true));
        binding.swipeRefreshLayout.setProgressViewEndTarget(true, binding.swipeRefreshLayout.getProgressViewEndOffset());
        Intent intent = AppHelper.getSettingsIntent(module.packageName, module.userId);
        if (intent == null) {
            binding.fab.setVisibility(View.GONE);
        } else {
            binding.fab.setVisibility(View.VISIBLE);
            binding.fab.setOnClickListener(v -> ConfigManager.startActivityAsUserWithFeature(intent, module.userId));
        }
        searchListener = scopeAdapter.getSearchListener();

        setupToolbar(binding.toolbar, binding.clickView, title, R.menu.menu_app_list, view -> requireActivity().getOnBackPressedDispatcher().onBackPressed());
        // Search lives on the toolbar as an expanding icon in both skins.
        searchView = setupToolbarSearch(binding.toolbar, searchListener);
        View.OnClickListener l = v -> {
            binding.recyclerView.smoothScrollToPosition(0);
            binding.appBar.setExpanded(true, true);
        };
        binding.toolbar.setOnClickListener(l);
        binding.clickView.setOnClickListener(l);

        if (backPressedCallback != null) backPressedCallback.setEnabled(true);
        // onResume may already have passed while the page was waiting for the
        // scan, so the first scope load is triggered here.
        scopeAdapter.refresh();
    }

    /**
     * Called from ModuleUtil notifications (worker threads) while the page is
     * waiting for the scan to publish. Distinguishes still-loading from
     * loaded-but-missing: only a complete snapshot that lacks the package
     * sends the user back.
     */
    private void checkAwaitedModule() {
        if (module != null || awaitingModule == null) return;
        var util = ModuleUtil.getInstance();
        if (!util.isModulesLoaded()) return;
        var m = util.getModule(modulePackageName, moduleUserId);
        module = m;
        util.removeListener(awaitingModule);
        awaitingModule = null;
        runOnUiThread(() -> {
            if (!isAdded()) return;
            if (m != null) {
                if (binding != null && scopeAdapter == null) buildContent();
            } else {
                navigateUp();
            }
        });
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppListFragmentArgs args = AppListFragmentArgs.fromBundle(getArguments());
        modulePackageName = args.getModulePackageName();
        moduleUserId = args.getModuleUserId();

        module = ModuleUtil.getInstance().getModule(modulePackageName, moduleUserId);
        if (module == null) {
            if (ModuleUtil.getInstance().isModulesLoaded()) {
                // The snapshot is complete and the module is not in it: this
                // page was opened for a package that is not a module.
                navigateUp();
            } else {
                // FragmentManager restores this fragment by itself, so waiting
                // for the scan has to work even when the page is recreated
                // without a fresh navigation event.
                awaitingModule = new ModuleUtil.ModuleListener() {
                    @Override
                    public void onModulesReloaded() {
                        checkAwaitedModule();
                    }

                    @Override
                    public void onSingleModuleReloaded(ModuleUtil.InstalledModule reloaded) {
                        checkAwaitedModule();
                    }
                };
                ModuleUtil.getInstance().addListener(awaitingModule);
            }
        }

        // Disabled until the scope adapter exists: while the page is still
        // loading, back keeps its default navigate-up behavior.
        backPressedCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                scopeAdapter.onBackPressed();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(this, backPressedCallback);

        backupLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/gzip"),
                uri -> {
                    if (uri == null) return;
                    runAsync(() -> {
                        try {
                            BackupUtils.backup(uri, modulePackageName);
                        } catch (Exception e) {
                            var text = App.getInstance().getString(R.string.settings_backup_failed2, e.getMessage());
                            showHint(text, false);
                        }
                    });
                });
        restoreLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri == null) return;
                    runAsync(() -> {
                        try {
                            BackupUtils.restore(uri, modulePackageName);
                        } catch (Exception e) {
                            var text = App.getInstance().getString(R.string.settings_restore_failed2, e.getMessage());
                            showHint(text, false);
                        }
                    });
                });
    }

    @Override
    public void onDestroy() {
        if (awaitingModule != null) {
            ModuleUtil.getInstance().removeListener(awaitingModule);
            awaitingModule = null;
        }
        super.onDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (scopeAdapter != null) scopeAdapter.refresh();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (scopeAdapter != null) scopeAdapter.unregisterAdapterDataObserver(observer);
        searchView = null;
        searchListener = null;
        binding = null;
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem item) {
        return scopeAdapter != null && scopeAdapter.onOptionsItemSelected(item);
    }

    @Override
    public void onPrepareMenu(@NonNull Menu menu) {
        if (scopeAdapter != null) scopeAdapter.onPrepareOptionsMenu(menu);
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {

    }

    @Override
    public boolean onContextItemSelected(@NonNull MenuItem item) {
        if (scopeAdapter != null && scopeAdapter.onContextItemSelected(item)) {
            return true;
        }
        return super.onContextItemSelected(item);
    }
}
