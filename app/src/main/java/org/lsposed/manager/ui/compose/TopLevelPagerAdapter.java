package org.lsposed.manager.ui.compose;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStateAdapter;

import org.lsposed.manager.ui.fragment.HomeFragment;
import org.lsposed.manager.ui.fragment.ModulesFragment;
import org.lsposed.manager.ui.fragment.RepoFragment;
import org.lsposed.manager.ui.fragment.SettingsFragment;

public class TopLevelPagerAdapter extends FragmentStateAdapter {
    public static final int PAGE_COUNT = 4;
    public static final int PAGE_HOME = 0;
    public static final int PAGE_MODULES = 1;
    public static final int PAGE_REPO = 2;
    public static final int PAGE_SETTINGS = 3;

    private boolean binderAlive = true;
    private boolean magiskInstalled = false;

    public TopLevelPagerAdapter(@NonNull FragmentManager fragmentManager) {
        super(fragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT);
    }

    public void setAvailability(boolean newBinderAlive, boolean newMagiskInstalled) {
        if (binderAlive == newBinderAlive && magiskInstalled == newMagiskInstalled) return;
        binderAlive = newBinderAlive;
        magiskInstalled = newMagiskInstalled;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        Fragment fragment;
        switch (position) {
            case PAGE_MODULES -> fragment = new ModulesFragment();
            case PAGE_REPO -> fragment = new RepoFragment();
            case PAGE_SETTINGS -> fragment = new SettingsFragment();
            default -> fragment = new HomeFragment();
        }
        return fragment;
    }

    @Override
    public int getItemCount() {
        return PAGE_COUNT;
    }

    @Override
    public long getItemId(int position) {
        if (position == PAGE_MODULES && !binderAlive) {
            return unavailableModulesId();
        }
        if (position == PAGE_REPO && !(binderAlive || magiskInstalled)) {
            return unavailableRepoId();
        }
        return position;
    }

    @Override
    public boolean containsItem(long itemId) {
        if (itemId >= 0 && itemId < PAGE_COUNT) {
            return itemId != unavailableModulesId() && itemId != unavailableRepoId();
        }
        return itemId == unavailableModulesId() || itemId == unavailableRepoId();
    }

    private long unavailableModulesId() {
        return binderAlive ? Long.MIN_VALUE : Long.MIN_VALUE + PAGE_MODULES;
    }

    private long unavailableRepoId() {
        return binderAlive || magiskInstalled
                ? Long.MIN_VALUE
                : Long.MIN_VALUE + 1000 + PAGE_REPO;
    }
}
