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
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.DisplayMetrics;
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
import androidx.recyclerview.widget.LinearSmoothScroller;
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
        }
        if (optionsItemSelectListener != null) {
            return optionsItemSelectListener.onOptionsItemSelected(item);
        }
        return false;
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
        /**
         * Above this item distance the list is no longer traversed row-by-row:
         * a glide binds every intermediate row (each log card rebuilds its
         * chips), so the frame budget only allows a few rows per frame. The
         * scroller instead seeks beside the target and glides the landing
         * stretch, which keeps long flights snappy AND jank-free.
         */
        static final int SEEK_THRESHOLD = 120;
        /** Row count of the animated landing glide after a seek. */
        static final int LANDING_ITEMS = 60;
        /** The glide duration ramps from GLIDE_MIN_MS at short hops to
         * GLIDE_MAX_MS at DURATION_REF_ITEMS, sublinearly, so longer flights
         * run at a higher speed instead of crawling at constant rate. */
        static final int DURATION_REF_ITEMS = 400;
        static final long GLIDE_MIN_MS = 220;
        static final long GLIDE_MAX_MS = 480;

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
            private final Pattern logHeader = Pattern.compile("\\[\\s*(\\d{4}-\\d{2}-\\d{2}T(\\d{2}:\\d{2}:\\d{2}))(?:\\.\\d+)?\\s+(\\d+):\\s*(\\d+):\\s*(\\d+)\\s+([VDIWEF])/([^\\]]*?)\\s*\\]\\s?(.*)$");
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
                // Daemon format:
                // [ 2026-08-30T15:30:54.616  1000: 2930: 2930 I/LSPosed-Bridge  ] message
                // Following lines belong to the same entry's body.
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
                        String date = matcher.group(1);
                        String time = date.substring(date.indexOf('T') + 1);
                        String day = date.substring(5, date.indexOf('T'));
                        String tag = matcher.group(7).trim() + " ("
                                + matcher.group(3) + ":" + matcher.group(4) + ")";
                        current = new LogEntry(day + " " + time, tag,
                                Collections.singletonList(matcher.group(6)));
                        if (!matcher.group(8).isEmpty()) {
                            body.append(matcher.group(8));
                        }
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
            glideTo(0, false);
        }

        public void scrollToBottom(LogsFragment logsFragment) {
            logsFragment.binding.appBar.setExpanded(false, true);
            glideTo(Math.max(adaptor.getItemCount() - 1, 0), true);
        }

        /**
         * Glides to {@code target} over a duration scaled by the distance.
         * Flights beyond [SEEK_THRESHOLD] rows are too long to traverse
         * row-by-row, so the list first seeks beside the target (an instant
         * layout, no per-row work) and then glides the landing stretch — the
         * arrival stays animated instead of teleporting.
         */
        private void glideTo(int target, boolean downward) {
            int current = downward
                    ? layoutManager.findLastVisibleItemPosition()
                    : layoutManager.findFirstVisibleItemPosition();
            if (current == RecyclerView.NO_POSITION || current == target
                    || layoutManager.getChildCount() == 0) {
                binding.recyclerView.smoothScrollToPosition(target);
                return;
            }
            int distance = Math.abs(target - current);
            if (distance <= SEEK_THRESHOLD) {
                startGlide(target, distance);
                return;
            }
            int seek = downward
                    ? Math.max(0, target - LANDING_ITEMS)
                    : Math.min(target + LANDING_ITEMS, Math.max(adaptor.getItemCount() - 1, 0));
            binding.recyclerView.scrollToPosition(seek);
            binding.recyclerView.post(() -> {
                if (binding != null) startGlide(target, LANDING_ITEMS);
            });
        }

        private void startGlide(int target, int itemDistance) {
            View sample = layoutManager.getChildAt(0);
            int sampleHeight = sample != null ? sample.getHeight() : 0;
            if (sampleHeight <= 0) {
                binding.recyclerView.smoothScrollToPosition(target);
                return;
            }
            var scroller = new GlideScroller(binding.recyclerView.getContext(),
                    target, itemDistance, sampleHeight);
            layoutManager.startSmoothScroll(scroller);
        }

        /** Sublinear ramp: GLIDE_MIN_MS for a hop, GLIDE_MAX_MS once the
         * flight spans DURATION_REF_ITEMS rows. */
        private static long glideDurationMs(int items) {
            double ramp = Math.min(1d, Math.sqrt(items / (double) DURATION_REF_ITEMS));
            return (long) (GLIDE_MIN_MS + (GLIDE_MAX_MS - GLIDE_MIN_MS) * ramp);
        }

        /** A [LinearSmoothScroller] whose pixel speed is derived from the
         * desired duration and the estimated flight distance, so the glide
         * lasts the computed time instead of the library's constant rate. */
        private static final class GlideScroller extends LinearSmoothScroller {
            private final float msPerPixel;

            GlideScroller(Context context, int targetPosition, int itemDistance, int sampleItemHeightPx) {
                super(context);
                setTargetPosition(targetPosition);
                float estimatedPx = Math.max(itemDistance * (float) sampleItemHeightPx, 1f);
                msPerPixel = glideDurationMs(itemDistance) / estimatedPx;
            }

            @Override
            protected float calculateSpeedPerPixel(DisplayMetrics displayMetrics) {
                return msPerPixel;
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
    }
}
