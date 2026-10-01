package org.lsposed.manager.ui.fragment;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

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
import org.lsposed.manager.databinding.FragmentAppListBinding;
import org.lsposed.manager.databinding.ItemModuleBinding;
import org.lsposed.manager.ui.widget.EmptyStateRecyclerView;
import org.lsposed.manager.ui.widget.ListCardDecoration;
import org.lsposed.manager.util.GlideApp;
import org.lsposed.manager.util.ThemeUtil;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.recyclerview.RecyclerViewKt;

/** Package-wide compatibility choices, independent of any module's scope. */
public class InlineHookAppsFragment extends BaseFragment implements MenuProvider {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Set<String> selected = new HashSet<>();
    private final Set<String> pending = new HashSet<>();
    private final List<AppEntry> apps = new ArrayList<>();
    private final List<AppEntry> visible = new ArrayList<>();
    private FragmentAppListBinding binding;
    private AppsAdapter adapter;
    private SearchView searchView;
    private boolean loaded;
    private boolean loading;
    private boolean selectedOnly;
    private boolean showSystem = true;
    private String query = "";
    private int viewGeneration;

    private record AppEntry(PackageInfo info, String label) {}

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            query = savedInstanceState.getString("query", "");
            selectedOnly = savedInstanceState.getBoolean("selectedOnly");
            showSystem = savedInstanceState.getBoolean("showSystem", true);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentAppListBinding.inflate(inflater, container, false);
        viewGeneration++;
        binding.appBar.setLiftable(true);
        setupToolbar(binding.toolbar, binding.clickView, R.string.inline_hooks_title,
                R.menu.menu_inline_hook_apps);
        searchView = setupToolbarSearch(binding.toolbar, new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String text) { return false; }

            @Override
            public boolean onQueryTextChange(String text) {
                query = text;
                filterApps();
                return true;
            }
        });
        adapter = new AppsAdapter();
        binding.recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerView.setAdapter(new ConcatAdapter(new NoticeAdapter(), adapter));
        if (!ThemeUtil.isMiuixStyle()) {
            binding.recyclerView.addItemDecoration(new ListCardDecoration(requireContext()));
        }
        binding.recyclerView.getBorderViewDelegate().setBorderVisibilityChangedListener(
                (top, oldTop, bottom, oldBottom) -> {
                    if (binding != null) binding.appBar.setLifted(!top);
                });
        RecyclerViewKt.fixEdgeEffect(binding.recyclerView, false, true);
        binding.swipeRefreshLayout.setOnRefreshListener(this::refresh);
        binding.toolbar.setOnClickListener(v -> scrollToTop());
        binding.clickView.setOnClickListener(v -> scrollToTop());
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (!query.isEmpty()) {
            String restoredQuery = query;
            binding.toolbar.getMenu().findItem(R.id.menu_search).expandActionView();
            searchView.setQuery(restoredQuery, false);
            searchView.clearFocus();
        }
        refresh();
    }

    private void scrollToTop() {
        binding.recyclerView.smoothScrollToPosition(0);
        binding.appBar.setExpanded(true, true);
    }

    private void refresh() {
        if (loading || !pending.isEmpty()) {
            binding.swipeRefreshLayout.setRefreshing(loading);
            return;
        }
        loading = true;
        binding.swipeRefreshLayout.setRefreshing(true);
        adapter.notifyDataSetChanged();
        int generation = viewGeneration;
        var pm = requireContext().getPackageManager();
        worker.execute(() -> {
            try {
                var choices = new HashSet<>(ConfigManager.getInvalidateArtInlineHookPackages());
                var packages = new LinkedHashMap<String, AppEntry>();
                for (var info : ConfigManager.getInstalledPackagesFromAllUsersOrThrow(0, true)) {
                    if (info.applicationInfo == null || "system".equals(info.packageName)) continue;
                    if ((info.applicationInfo.flags & ApplicationInfo.FLAG_INSTALLED) == 0) continue;
                    // This setting follows the package across Android users, so display it once.
                    packages.putIfAbsent(info.packageName,
                            new AppEntry(info, AppHelper.getAppLabel(info, pm).toString()));
                }
                // Keep stored choices removable even if a package has since been uninstalled.
                for (String name : choices) {
                    if (!packages.containsKey(name)) {
                        var info = new PackageInfo();
                        info.packageName = name;
                        packages.put(name, new AppEntry(info, name));
                    }
                }
                runOnUiThread(() -> {
                    if (binding == null || generation != viewGeneration) return;
                    selected.clear();
                    selected.addAll(choices);
                    apps.clear();
                    apps.addAll(packages.values());
                    loaded = true;
                    loading = false;
                    binding.swipeRefreshLayout.setRefreshing(false);
                    filterApps();
                });
            } catch (Exception e) {
                Log.e(App.TAG, "Cannot load inline hook applications", e);
                runOnUiThread(() -> {
                    if (binding == null || generation != viewGeneration) return;
                    loading = false;
                    binding.swipeRefreshLayout.setRefreshing(false);
                    adapter.notifyDataSetChanged();
                    showHint(R.string.inline_hooks_load_failed, false);
                });
            }
        });
    }

    private void setEnabled(AppEntry entry, boolean enabled) {
        String name = entry.info.packageName;
        if (!loaded || loading || !pending.add(name)) return;
        adapter.notifyDataSetChanged();
        // Serialize writes and refreshes, but finish accepted writes even if the page closes.
        worker.execute(() -> {
            boolean success = ConfigManager.setInvalidateArtInlineHooks(name, enabled);
            runOnUiThread(() -> {
                pending.remove(name);
                if (success) {
                    if (enabled) selected.add(name);
                    else selected.remove(name);
                }
                if (binding == null) return;
                filterApps();
                showHint(success ? R.string.inline_hooks_restart : R.string.inline_hooks_save_failed, false);
            });
        });
    }

    private void filterApps() {
        if (binding == null || adapter == null) return;
        String needle = query.toLowerCase(Locale.ROOT);
        visible.clear();
        for (var app : apps) {
            boolean checked = selected.contains(app.info.packageName);
            if (selectedOnly && !checked) continue;
            if (!showSystem && !checked && app.info.applicationInfo != null
                    && (app.info.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            if (!app.label.toLowerCase(Locale.ROOT).contains(needle)
                    && !app.info.packageName.toLowerCase(Locale.ROOT).contains(needle)) continue;
            visible.add(app);
        }
        int sort = App.getPreferences().getInt("list_sort", 0);
        var comparator = AppHelper.getAppListComparator(sort, requireContext().getPackageManager());
        var collator = Collator.getInstance();
        visible.sort((a, b) -> {
            int checkedOrder = Boolean.compare(selected.contains(b.info.packageName),
                    selected.contains(a.info.packageName));
            if (checkedOrder != 0) return checkedOrder;
            // Compare cached labels consistently, including uninstalled entries.
            if (sort < 2) {
                int order = collator.compare(a.label, b.label);
                return sort == 1 ? -order : order;
            }
            return comparator.compare(a.info, b.info);
        });
        adapter.notifyDataSetChanged();
        binding.toolbar.setSubtitle(getString(R.string.inline_hooks_selected_count, selected.size()));
    }

    @Override
    public void onPrepareMenu(@NonNull Menu menu) {
        menu.findItem(R.id.inline_hooks_selected_only).setChecked(selectedOnly);
        menu.findItem(R.id.item_filter_system).setChecked(showSystem);
        int sort = App.getPreferences().getInt("list_sort", 0);
        int[] sortIds = {R.id.item_sort_by_name, R.id.item_sort_by_package_name,
                R.id.item_sort_by_install_time, R.id.item_sort_by_update_time};
        menu.findItem(sortIds[Math.max(0, Math.min(3, sort / 2))]).setChecked(true);
        menu.findItem(R.id.reverse).setChecked(sort % 2 != 0);
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.inline_hooks_selected_only) {
            selectedOnly = !selectedOnly;
        } else if (item.getItemId() == R.id.item_filter_system) {
            showSystem = !showSystem;
        } else if (!AppHelper.onOptionsItemSelected(item, App.getPreferences())) {
            return false;
        }
        onPrepareMenu(binding.toolbar.getMenu());
        filterApps();
        return true;
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {}

    @Override
    public void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("query", query);
        state.putBoolean("selectedOnly", selectedOnly);
        state.putBoolean("showSystem", showSystem);
    }

    @Override
    public void onDestroyView() {
        viewGeneration++;
        loading = false;
        searchView.setOnQueryTextListener(null);
        binding.recyclerView.setAdapter(null);
        binding = null;
        adapter = null;
        searchView = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }

    private class AppsAdapter extends EmptyStateRecyclerView.EmptyStateAdapter<AppHolder> {
        @Override
        public boolean isLoaded() { return loaded; }

        @Override
        public int getItemCount() { return visible.size(); }

        @NonNull
        @Override
        public AppHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new AppHolder(ItemModuleBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull AppHolder holder, int position) {
            var entry = visible.get(position);
            var row = holder.row;
            if (!ThemeUtil.isMiuixStyle()) {
                row.getRoot().setBackgroundResource(getItemCount() == 1 ? R.drawable.m3e_scope_row_ripple
                        : position == 0 ? R.drawable.m3e_scope_row_top
                        : position == getItemCount() - 1 ? R.drawable.m3e_scope_row_bottom
                        : R.drawable.m3e_scope_row_middle);
            }
            row.appName.setText(entry.label);
            row.appPackageName.setText(entry.info.packageName);
            row.appPackageName.setVisibility(View.VISIBLE);
            row.checkbox.setVisibility(View.VISIBLE);
            row.checkbox.setChecked(selected.contains(entry.info.packageName));
            boolean enabled = loaded && !loading && !pending.contains(entry.info.packageName);
            row.checkbox.setEnabled(enabled);
            row.getRoot().setEnabled(enabled);
            row.getRoot().setAlpha(enabled ? 1f : .5f);
            GlideApp.with(row.appIcon)
                    .load(entry.info.applicationInfo == null ? null : entry.info)
                    .fallback(R.drawable.ic_outline_android_24)
                    .into(row.appIcon);
            row.getRoot().setOnClickListener(v -> setEnabled(entry, !selected.contains(entry.info.packageName)));
        }

        @Override
        public void onViewRecycled(@NonNull AppHolder holder) {
            super.onViewRecycled(holder);
            GlideApp.with(holder.row.appIcon).clear(holder.row.appIcon);
        }
    }

    private static class AppHolder extends RecyclerView.ViewHolder {
        final ItemModuleBinding row;
        AppHolder(ItemModuleBinding row) {
            super(row.getRoot());
            this.row = row;
        }
    }

    private static class NoticeAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public int getItemCount() { return 1; }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RecyclerView.ViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_inline_hook_notice, parent, false)) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {}
    }
}
