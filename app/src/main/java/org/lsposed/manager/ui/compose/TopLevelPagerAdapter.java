package org.lsposed.manager.ui.compose;

import androidx.annotation.NonNull;
import androidx.annotation.IdRes;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.Lifecycle;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import org.lsposed.manager.R;
import org.lsposed.manager.ui.fragment.HomeFragment;
import org.lsposed.manager.ui.fragment.ModulesFragment;
import org.lsposed.manager.ui.fragment.RepoFragment;
import org.lsposed.manager.ui.fragment.SettingsFragment;

import java.util.ArrayList;
import java.util.List;

public class TopLevelPagerAdapter extends FragmentStateAdapter {
    public static final int PAGE_COUNT = 4;

    private final List<Integer> visiblePageIds = new ArrayList<>();
    private final ViewPager2 pager;

    public TopLevelPagerAdapter(@NonNull FragmentManager fragmentManager, @NonNull Lifecycle lifecycle,
                                @NonNull ViewPager2 pager) {
        super(fragmentManager, lifecycle);
        this.pager = pager;
        rebuildPages(true, false);
    }

    public synchronized void setAvailability(boolean binderAlive, boolean magiskInstalled) {
        List<Integer> oldPages = new ArrayList<>(visiblePageIds);
        rebuildPages(binderAlive, magiskInstalled);
        if (oldPages.equals(visiblePageIds)) return;

        if (pager.getCurrentItem() >= visiblePageIds.size()) {
            pager.setCurrentItem(visiblePageIds.size() - 1, false);
        }
        notifyDataSetChanged();
        if (!hasStableIdsFor(oldPages)) {
            pager.post(() -> {
                if (pager.getAdapter() == TopLevelPagerAdapter.this &&
                        pager.getCurrentItem() >= visiblePageIds.size()) {
                    pager.setCurrentItem(visiblePageIds.size() - 1, false);
                }
            });
        }
    }

    public synchronized int getPositionForId(@IdRes int pageId) {
        for (int position = 0; position < visiblePageIds.size(); position++) {
            if (visiblePageIds.get(position) == pageId) return position;
        }
        if (pageId == R.id.modules_nav || pageId == R.id.repo_nav) return -1;
        return 0;
    }

    public synchronized @IdRes int getPageId(int position) {
        if (position < 0 || position >= visiblePageIds.size()) return R.id.main_fragment;
        return visiblePageIds.get(position);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        return createFragmentForId(getPageId(position));
    }

    @Override
    public int getItemCount() {
        synchronized (this) {
            return visiblePageIds.size();
        }
    }

    @Override
    public long getItemId(int position) {
        return getPageId(position);
    }

    @Override
    public boolean containsItem(long itemId) {
        synchronized (this) {
            for (int pageId : visiblePageIds) {
                if (pageId == itemId) return true;
            }
        }
        return false;
    }

    private void rebuildPages(boolean binderAlive, boolean magiskInstalled) {
        visiblePageIds.clear();
        visiblePageIds.add(R.id.main_fragment);
        if (binderAlive) visiblePageIds.add(R.id.modules_nav);
        if (binderAlive || magiskInstalled) visiblePageIds.add(R.id.repo_nav);
        visiblePageIds.add(R.id.settings_fragment);
    }

    private boolean hasStableIdsFor(List<Integer> oldPages) {
        for (int pageId : oldPages) {
            if (getPositionForId(pageId) < 0) return false;
        }
        return true;
    }

    private Fragment createFragmentForId(@IdRes int pageId) {
        if (pageId == R.id.modules_nav) return new ModulesFragment();
        if (pageId == R.id.repo_nav) return new RepoFragment();
        if (pageId == R.id.settings_fragment) return new SettingsFragment();
        return new HomeFragment();
    }
}
