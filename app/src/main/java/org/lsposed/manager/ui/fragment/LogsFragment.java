/*
 * This file is part of LSPosed.
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
 * Copyright (C) 2021 LSPosed Contributors
 */

package org.lsposed.manager.ui.fragment;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.google.android.material.textview.MaterialTextView;

import org.lsposed.manager.App;
import org.lsposed.manager.ConfigManager;
import org.lsposed.manager.R;
import org.lsposed.manager.databinding.FragmentLogsBinding;
import org.lsposed.manager.databinding.ItemLogCardBinding;
import org.lsposed.manager.databinding.ItemLogTextviewBinding;
import org.lsposed.manager.databinding.SwiperefreshRecyclerviewBinding;
import org.lsposed.manager.receivers.LSPManagerServiceHolder;
import org.lsposed.manager.ui.widget.EmptyStateRecyclerView;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import rikka.material.app.LocaleDelegate;
import rikka.recyclerview.RecyclerViewKt;

public class LogsFragment extends BaseFragment implements MenuProvider {
    private FragmentLogsBinding binding;
    private LogPageAdapter adapter;
    private MenuItem wordWrap;

    interface OptionsItemSelectListener {
        boolean onOptionsItemSelected(@NonNull MenuItem item);
    }

    private OptionsItemSelectListener optionsItemSelectListener;

    private final ActivityResultLauncher<String> saveLogsLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/zip"),
            uri -> {
                if (uri == null) return;
                runAsync(() -> {
                    var context = requireContext();
                    var cr = context.getContentResolver();
                    try (var zipFd = cr.openFileDescriptor(uri, "wt")) {
                        showHint(context.getString(R.string.logs_saving), false);
                        LSPManagerServiceHolder.getService().getLogs(zipFd);
                        showHint(context.getString(R.string.logs_saved), true);
                    } catch (Throwable e) {
                        var cause = e.getCause();
                        var message = cause == null ? e.getMessage() : cause.getMessage();
                        var text = context.getString(R.string.logs_save_failed2, message);
                        showHint(text, false);
                        Log.w(App.TAG, "save log", e);
                    }
                });
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentLogsBinding.inflate(inflater, container, false);
        binding.appBar.setLiftable(true);
        setupToolbar(binding.toolbar, binding.clickView, R.string.Logs, R.menu.menu_logs);
        binding.toolbar.setSubtitle(ConfigManager.isVerboseLogEnabled() ? R.string.enabled_verbose_log : R.string.disabled_verbose_log);
        adapter = new LogPageAdapter(this);
        binding.viewPager.setAdapter(adapter);
        new TabLayoutMediator(binding.tabLayout, binding.viewPager, (tab, position) -> tab.setText((int) adapter.getItemId(position))).attach();

        binding.tabLayout.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            ViewGroup vg = (ViewGroup) binding.tabLayout.getChildAt(0);
            int tabLayoutWidth = IntStream.range(0, binding.tabLayout.getTabCount()).map(i -> vg.getChildAt(i).getWidth()).sum();
            if (tabLayoutWidth <= binding.getRoot().getWidth()) {
                binding.tabLayout.setTabMode(TabLayout.MODE_FIXED);
                binding.tabLayout.setTabGravity(TabLayout.GRAVITY_FILL);
            }
        });

        return binding.getRoot();
    }

    public void setOptionsItemSelectListener(OptionsItemSelectListener optionsItemSelectListener) {
        this.optionsItemSelectListener = optionsItemSelectListener;
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem item) {
        var itemId = item.getItemId();
        if (itemId == R.id.menu_save) {
            save();
            return true;
        } else if (itemId == R.id.menu_word_wrap) {
            item.setChecked(!item.isChecked());
            App.getPreferences().edit().putBoolean("enable_word_wrap", item.isChecked()).apply();
            binding.viewPager.setUserInputEnabled(item.isChecked());
            adapter.refresh();
            return true;
        }
        if (optionsItemSelectListener != null) {
            return optionsItemSelectListener.onOptionsItemSelected(item);
        }
        return false;
    }

    @Override
    public void onPrepareMenu(@NonNull Menu menu) {
        wordWrap = menu.findItem(R.id.menu_word_wrap);
        wordWrap.setChecked(App.getPreferences().getBoolean("enable_word_wrap", false));
        binding.viewPager.setUserInputEnabled(wordWrap.isChecked());
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {

    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        binding = null;
    }

    private void save() {
        LocalDateTime now = LocalDateTime.now();
        String filename = String.format(LocaleDelegate.getDefaultLocale(), "LSPosed_%s.zip", now.toString());
        try {
            saveLogsLauncher.launch(filename);
        } catch (ActivityNotFoundException e) {
            showHint(R.string.enable_documentui, true);
        }
    }

    public static class LogFragment extends BaseFragment {
        public static final int SCROLL_THRESHOLD = 500;
        protected boolean verbose;
        protected SwiperefreshRecyclerviewBinding binding;
        protected LogAdaptor adaptor;
        protected LinearLayoutManager layoutManager;

        /** One card per event: the header line carries time, level chips and
         * the tag/process, the following lines are the message body. */
        static final class LogEntry {
            final String time;
            final String tag;
            final List<String> chips;
            String body = "";

            LogEntry(String time, String tag, List<String> chips) {
                this.time = time;
                this.tag = tag;
                this.chips = chips;
            }
        }

        class LogAdaptor extends EmptyStateRecyclerView.EmptyStateAdapter<LogAdaptor.ViewHolder> {
            /** A header line starts with the daemon's MM-dd HH:mm:ss stamp. */
            private final Pattern logHeader = Pattern.compile("^(\\d{2}-\\d{2} \\d{2}:\\d{2})(?:\\.\\d+)?\\s+(.*)$");
            private List<Object> items = Collections.emptyList();
            private final Set<Integer> expanded = new HashSet<>();
            private boolean isLoaded = false;

            @Override
            public int getItemViewType(int position) {
                return items.get(position) instanceof LogEntry ? 1 : 0;
            }

            @NonNull
            @Override
            public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                if (viewType == 1) {
                    return new ViewHolder(ItemLogCardBinding.inflate(getLayoutInflater(), parent, false));
                }
                return new ViewHolder(ItemLogTextviewBinding.inflate(getLayoutInflater(), parent, false));
            }

            @Override
            public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
                if (!(items.get(position) instanceof LogEntry entry)) {
                    holder.item.setText((CharSequence) items.get(position));
                    return;
                }
                holder.chips.removeAllViews();
                var context = holder.itemView.getContext();
                for (String letter : entry.chips) {
                    TextView chip = new TextView(context);
                    chip.setText(letter);
                    chip.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                    chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                    chip.setPadding(dp(8), dp(2), dp(8), dp(2));
                    int bgAttr, fgAttr;
                    switch (letter) {
                        case "E", "F" -> {
                            bgAttr = com.google.android.material.R.attr.colorErrorContainer;
                            fgAttr = com.google.android.material.R.attr.colorOnErrorContainer;
                        }
                        case "W" -> {
                            bgAttr = com.google.android.material.R.attr.colorTertiaryContainer;
                            fgAttr = com.google.android.material.R.attr.colorOnTertiaryContainer;
                        }
                        case "R" -> {
                            bgAttr = com.google.android.material.R.attr.colorSecondaryContainer;
                            fgAttr = com.google.android.material.R.attr.colorOnSecondaryContainer;
                        }
                        default -> {
                            bgAttr = com.google.android.material.R.attr.colorSurfaceVariant;
                            fgAttr = com.google.android.material.R.attr.colorOnSurfaceVariant;
                        }
                    }
                    chip.setBackgroundResource(R.drawable.m3e_log_chip_bg);
                    chip.setBackgroundTintList(ColorStateList.valueOf(MaterialColors.getColor(chip, bgAttr)));
                    chip.setTextColor(MaterialColors.getColor(chip, fgAttr));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.setMarginEnd(dp(4));
                    holder.chips.addView(chip, lp);
                }
                holder.tag.setText(entry.tag);
                holder.time.setText(entry.time);
                holder.body.setText(entry.body);
                boolean isExpanded = expanded.contains(position);
                holder.body.setMaxLines(isExpanded ? Integer.MAX_VALUE : 4);
                holder.body.setEllipsize(isExpanded ? null : TextUtils.TruncateAt.END);
                holder.card.setOnClickListener(v -> {
                    int pos = holder.getBindingAdapterPosition();
                    if (pos == RecyclerView.NO_POSITION) return;
                    if (!expanded.remove(pos)) {
                        expanded.add(pos);
                    }
                    notifyItemChanged(pos);
                });
            }

            /** Groups raw log lines into events: a line starting with the
             * daemon's MM-dd HH:mm:ss stamp opens an entry, everything up to
             * the next stamp is its body. Lines before the first stamp
             * (e.g. "----part 1 start----") stay as plain text. */
            private List<Object> groupEvents(List<CharSequence> lines) {
                List<Object> out = new ArrayList<>();
                LogEntry current = null;
                StringBuilder body = new StringBuilder();
                for (CharSequence line : lines) {
                    var matcher = logHeader.matcher(line);
                    if (matcher.matches()) {
                        if (current != null) {
                            current.body = body.toString();
                            out.add(current);
                            body = new StringBuilder();
                        }
                        String rest = matcher.group(2).trim();
                        List<String> chips = new ArrayList<>();
                        var leading = Pattern.compile("^([VDIWEFR])(?:\\s+([VDIWEFR]))?\\s+(.*)$").matcher(rest);
                        if (leading.matches()) {
                            chips.add(leading.group(1));
                            if (leading.group(2) != null) chips.add(leading.group(2));
                            rest = leading.group(3).trim();
                        } else {
                            var anywhere = Pattern.compile("(?:^|\\s)([VDIWEF])(?=\\s|$)").matcher(rest);
                            if (anywhere.find()) {
                                chips.add(anywhere.group(1));
                                rest = (rest.substring(0, anywhere.start()) + " "
                                        + rest.substring(anywhere.end())).trim();
                            }
                        }
                        current = new LogEntry(matcher.group(1), rest, chips);
                    } else if (current != null) {
                        if (body.length() > 0) body.append('\n');
                        body.append(line);
                    } else {
                        out.add(line);
                    }
                }
                if (current != null) {
                    current.body = body.toString();
                    out.add(current);
                }
                return out;
            }

            private int dp(int value) {
                return Math.round(value * getResources().getDisplayMetrics().density);
            }

            @Override
            public int getItemCount() {
                return items.size();
            }

            @SuppressLint("NotifyDataSetChanged")
            void refresh(List<Object> items) {
                runOnUiThread(() -> {
                    isLoaded = true;
                    this.items = items;
                    expanded.clear();
                    notifyDataSetChanged();
                });
            }

            void fullRefresh() {
                runAsync(() -> {
                    isLoaded = false;
                    List<CharSequence> tmp;
                    try (var parcelFileDescriptor = ConfigManager.getLog(verbose);
                         var br = new BufferedReader(new InputStreamReader(new FileInputStream(parcelFileDescriptor != null ? parcelFileDescriptor.getFileDescriptor() : null)))) {
                        tmp = br.lines().parallel().collect(Collectors.toList());
                    } catch (Throwable e) {
                        tmp = Arrays.asList(Log.getStackTraceString(e).split("\n"));
                    }
                    refresh(groupEvents(tmp));
                });
            }

            @Override
            public boolean isLoaded() {
                return isLoaded;
            }

            class ViewHolder extends RecyclerView.ViewHolder {
                final MaterialTextView item;
                final View card;
                final LinearLayout chips;
                final MaterialTextView tag;
                final MaterialTextView time;
                final MaterialTextView body;

                public ViewHolder(ItemLogTextviewBinding binding) {
                    super(binding.getRoot());
                    item = binding.logItem;
                    card = null;
                    chips = null;
                    tag = null;
                    time = null;
                    body = null;
                }

                public ViewHolder(ItemLogCardBinding binding) {
                    super(binding.getRoot());
                    item = null;
                    card = binding.logCard;
                    chips = binding.logChips;
                    tag = binding.logTag;
                    time = binding.logTime;
                    body = binding.logBody;
                }
            }
        }

        protected LogAdaptor createAdaptor() {
            return new LogAdaptor();
        }

        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
            binding = SwiperefreshRecyclerviewBinding.inflate(getLayoutInflater(), container, false);
            var arguments = getArguments();
            if (arguments == null) return null;
            verbose = arguments.getBoolean("verbose");
            adaptor = createAdaptor();
            binding.recyclerView.setAdapter(adaptor);
            layoutManager = new LinearLayoutManager(requireActivity());
            binding.recyclerView.setLayoutManager(layoutManager);
            // ltr even for rtl languages because of log format
            binding.recyclerView.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            binding.swipeRefreshLayout.setProgressViewEndTarget(true, binding.swipeRefreshLayout.getProgressViewEndOffset());
            RecyclerViewKt.fixEdgeEffect(binding.recyclerView, false, true);
            binding.swipeRefreshLayout.setOnRefreshListener(adaptor::fullRefresh);
            adaptor.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
                @Override
                public void onChanged() {
                    binding.swipeRefreshLayout.setRefreshing(!adaptor.isLoaded());
                }
            });
            adaptor.fullRefresh();
            return binding.getRoot();
        }

        public void scrollToTop(LogsFragment logsFragment) {
            logsFragment.binding.appBar.setExpanded(true, true);
            if (layoutManager.findFirstVisibleItemPosition() > SCROLL_THRESHOLD) {
                binding.recyclerView.scrollToPosition(0);
            } else {
                binding.recyclerView.smoothScrollToPosition(0);
            }
        }

        public void scrollToBottom(LogsFragment logsFragment) {
            logsFragment.binding.appBar.setExpanded(false, true);
            var end = Math.max(adaptor.getItemCount() - 1, 0);
            if (adaptor.getItemCount() - layoutManager.findLastVisibleItemPosition() > SCROLL_THRESHOLD) {
                binding.recyclerView.scrollToPosition(end);
            } else {
                binding.recyclerView.smoothScrollToPosition(end);
            }
        }

        void attachListeners() {
            var parent = getParentFragment();
            if (parent instanceof LogsFragment logsFragment) {
                logsFragment.binding.appBar.setLifted(!binding.recyclerView.getBorderViewDelegate().isShowingTopBorder());
                binding.recyclerView.getBorderViewDelegate().setBorderVisibilityChangedListener((top, oldTop, bottom, oldBottom) -> logsFragment.binding.appBar.setLifted(!top));
                logsFragment.setOptionsItemSelectListener(item -> {
                    int itemId = item.getItemId();
                    if (itemId == R.id.menu_scroll_top) {
                        scrollToTop(logsFragment);
                    } else if (itemId == R.id.menu_scroll_down) {
                        scrollToBottom(logsFragment);
                    } else if (itemId == R.id.menu_clear) {
                        if (ConfigManager.clearLogs(verbose)) {
                            logsFragment.showHint(R.string.logs_cleared, true);
                            adaptor.fullRefresh();
                        } else {
                            logsFragment.showHint(R.string.logs_clear_failed_2, true);
                        }
                        return true;
                    }
                    return false;
                });

                View.OnClickListener l = v -> scrollToTop(logsFragment);
                logsFragment.binding.clickView.setOnClickListener(l);
                logsFragment.binding.toolbar.setOnClickListener(l);
            }
        }

        void detachListeners() {
            binding.recyclerView.getBorderViewDelegate().setBorderVisibilityChangedListener(null);
        }

        @Override
        public void onStart() {
            super.onStart();
            attachListeners();
        }

        @Override
        public void onResume() {
            super.onResume();
            attachListeners();
        }


        @Override
        public void onPause() {
            super.onPause();
            detachListeners();
        }

        @Override
        public void onStop() {
            super.onStop();
            detachListeners();
        }
    }

    class LogPageAdapter extends FragmentStateAdapter {

        public LogPageAdapter(@NonNull Fragment fragment) {
            super(fragment);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            var bundle = new Bundle();
            bundle.putBoolean("verbose", verbose(position));
            var f = new LogFragment();
            f.setArguments(bundle);
            return f;
        }

        @Override
        public int getItemCount() {
            return 2;
        }

        @Override
        public long getItemId(int position) {
            return verbose(position) ? R.string.nav_item_logs_verbose : R.string.nav_item_logs_module;
        }

        @Override
        public boolean containsItem(long itemId) {
            return itemId == R.string.nav_item_logs_verbose || itemId == R.string.nav_item_logs_module;
        }

        public boolean verbose(int position) {
            return position != 0;
        }

        public void refresh() {
            runOnUiThread(this::notifyDataSetChanged);
        }
    }
}
