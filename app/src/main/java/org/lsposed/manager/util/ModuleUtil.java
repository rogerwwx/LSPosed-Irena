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
 * Copyright (C) 2020 EdXposed Contributors
 * Copyright (C) 2021 LSPosed Contributors
 */

package org.lsposed.manager.util;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pair;

import org.lsposed.lspd.models.UserInfo;
import org.lsposed.manager.App;
import org.lsposed.manager.ConfigManager;
import org.lsposed.manager.repo.RepoLoader;
import org.lsposed.manager.repo.model.OnlineModule;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

public final class ModuleUtil {
    // xposedminversion below this
    public static int MIN_MODULE_VERSION = 2; // reject modules with
    public static final int MIN_OUTDATED_MODERN_MODULE_API = 100;
    private static ModuleUtil instance = null;
    private final PackageManager pm;
    private final Set<ModuleListener> listeners = ConcurrentHashMap.newKeySet();

    /**
     * Immutable view of the last published scan result. UI getters read the
     * current snapshot without taking the scan monitor, so the first frame
     * never waits for the background enumeration; a rescan keeps the previous
     * snapshot visible until the fresh one is published. Writers copy, mutate
     * and re-publish under the scan lock, which is why readers can trust every
     * published instance to be complete and consistent.
     */
    public static final class ModuleSnapshot {
        public final Map<Pair<String, Integer>, InstalledModule> installedModules;
        public final List<UserInfo> users;
        public final Set<Pair<String, Integer>> enabledModules;

        private ModuleSnapshot(Map<Pair<String, Integer>, InstalledModule> installedModules,
                               List<UserInfo> users,
                               Set<Pair<String, Integer>> enabledModules) {
            this.installedModules = Collections.unmodifiableMap(installedModules);
            this.users = users == null ? null : Collections.unmodifiableList(users);
            this.enabledModules = Collections.unmodifiableSet(enabledModules);
        }
    }

    private volatile ModuleSnapshot snapshot;

    static final int MATCH_ANY_USER = 0x00400000; // PackageManager.MATCH_ANY_USER

    static final int MATCH_ALL_FLAGS = PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE | PackageManager.MATCH_UNINSTALLED_PACKAGES | MATCH_ANY_USER;

    private ModuleUtil() {
        pm = App.getInstance().getPackageManager();
    }

    public boolean isModulesLoaded() {
        return snapshot != null;
    }

    public static synchronized ModuleUtil getInstance() {
        if (instance == null) {
            instance = new ModuleUtil();
            App.getExecutorService().submit(instance::reloadInstalledModules);
        }
        return instance;
    }

    public static int extractIntPart(String str) {
        if (str == null) {
            return 0;
        }
        int result = 0, length = str.length();
        for (int offset = 0; offset < length; offset++) {
            char c = str.charAt(offset);
            if ('0' <= c && c <= '9')
                result = result * 10 + (c - '0');
            else
                break;
        }
        return result;
    }

    private static int getTargetApiVersion(ZipFile zip) throws IOException {
        var propEntry = zip.getEntry("META-INF/xposed/module.prop");
        if (propEntry == null) {
            return 0;
        }
        var prop = new Properties();
        try (var in = zip.getInputStream(propEntry)) {
            prop.load(in);
        }
        return extractIntPart(prop.getProperty("targetApiVersion"));
    }

    private static boolean isOutdatedModernModule(ZipFile zip, int apiVersion) throws IOException {
        int targetApiVersion = getTargetApiVersion(zip);
        return zip.getEntry("META-INF/xposed/java_init.list") != null
                && apiVersion > 0
                && targetApiVersion >= MIN_OUTDATED_MODERN_MODULE_API
                && targetApiVersion < apiVersion;
    }

    public static ZipFile getModernModuleApk(ApplicationInfo info, int apiVersion) {
        String[] apks;
        if (info.splitSourceDirs != null) {
            apks = Arrays.copyOf(info.splitSourceDirs, info.splitSourceDirs.length + 1);
            apks[info.splitSourceDirs.length] = info.sourceDir;
        } else apks = new String[]{info.sourceDir};
        ZipFile zip = null;
        for (var apk : apks) {
            try {
                zip = new ZipFile(apk);
                if (zip.getEntry("META-INF/xposed/java_init.list") != null
                        && apiVersion > 0
                        && getTargetApiVersion(zip) >= apiVersion) {
                    return zip;
                }
                zip.close();
                zip = null;
            } catch (IOException ignored) {
            }
        }
        return zip;
    }

    public static ZipFile getOutdatedModernModuleApk(ApplicationInfo info, int apiVersion) {
        String[] apks;
        if (info.splitSourceDirs != null) {
            apks = Arrays.copyOf(info.splitSourceDirs, info.splitSourceDirs.length + 1);
            apks[info.splitSourceDirs.length] = info.sourceDir;
        } else apks = new String[]{info.sourceDir};
        ZipFile zip = null;
        for (var apk : apks) {
            try {
                zip = new ZipFile(apk);
                if (isOutdatedModernModule(zip, apiVersion)) {
                    return zip;
                }
                zip.close();
                zip = null;
            } catch (IOException ignored) {
            }
        }
        return zip;
    }

    public static boolean isLegacyModule(ApplicationInfo info) {
        return info.metaData != null && info.metaData.containsKey("xposedminversion");
    }

    /**
     * Re-enumerates every module and publishes the result as one snapshot.
     * The scan monitor only serializes writers; readers never take it, so
     * notifying listeners happens after publication and outside the lock -
     * a listener that asks for the fresh data on the main thread gets it
     * without ever blocking behind this scan.
     */
    public void reloadInstalledModules() {
        boolean changed;
        synchronized (this) {
            changed = scanAndPublish();
        }
        if (changed) {
            listeners.forEach(ModuleListener::onModulesReloaded);
        }
    }

    /**
     * The whole write side runs under the lock: build the containers, fetch
     * the enabled set last - so an enable/disable that raced this scan has
     * already reached the daemon before the query - and publish atomically.
     * Caller must hold {@code this}.
     */
    private boolean scanAndPublish() {
        if (!ConfigManager.isBinderAlive()) {
            // A dead binder ends the scan without clearing the last snapshot;
            // only the very first scan publishes an empty one, so the UI shows
            // an empty list instead of an eternal spinner.
            if (snapshot == null) {
                publishSnapshot(new HashMap<>(), new ArrayList<>(), new HashSet<>());
            }
            return false;
        }

        int apiVersion = ConfigManager.getXposedApiVersion();
        Map<Pair<String, Integer>, InstalledModule> modules = new HashMap<>();
        var users = ConfigManager.getUsers();
        for (PackageInfo pkg : ConfigManager.getInstalledPackagesFromAllUsers(PackageManager.GET_META_DATA | MATCH_ALL_FLAGS, false)) {
            ApplicationInfo app = pkg.applicationInfo;

            var modernApk = getModernModuleApk(app, apiVersion);
            var legacy = isLegacyModule(app);
            var outdatedModernApk = modernApk == null && !legacy ? getOutdatedModernModuleApk(app, apiVersion) : null;
            if (modernApk != null || legacy || outdatedModernApk != null) {
                modules.computeIfAbsent(Pair.create(pkg.packageName, app.uid / App.PER_USER_RANGE),
                        k -> new InstalledModule(pkg, modernApk != null ? modernApk : outdatedModernApk));
            }
        }

        Set<Pair<String, Integer>> enabledModules = ConfigManager.getEnabledModules().stream()
                .map(module -> Pair.create(module.packageName, module.userId))
                .collect(Collectors.toCollection(HashSet::new));
        publishSnapshot(modules, users, enabledModules);
        return true;
    }

    private void publishSnapshot(Map<Pair<String, Integer>, InstalledModule> modules,
                                 List<UserInfo> users,
                                 Set<Pair<String, Integer>> enabledModules) {
        snapshot = new ModuleSnapshot(modules, users, enabledModules);
    }

    @Nullable
    public List<UserInfo> getUsers() {
        var current = snapshot;
        return current == null ? null : current.users;
    }

    public InstalledModule reloadSingleModule(String packageName, int userId) {
        return reloadSingleModule(packageName, userId, false);
    }

    /**
     * Single-module update with the same publication rule as the full scan:
     * mutate by copy-on-write under the lock, so the result can neither be
     * lost to a concurrent scan publishing stale state nor overwrite a newer
     * snapshot. Listeners fire after the lock is released.
     */
    public InstalledModule reloadSingleModule(String packageName, int userId, boolean packageFullyRemoved) {
        List<Runnable> notifications = new ArrayList<>(2);
        InstalledModule result = null;
        var key = Pair.create(packageName, userId);
        synchronized (this) {
            var current = snapshot;
            int apiVersion = ConfigManager.getXposedApiVersion();
            if (packageFullyRemoved && current != null && current.enabledModules.contains(key)) {
                var enabledModules = new HashSet<>(current.enabledModules);
                enabledModules.remove(key);
                publishSnapshot(current.installedModules, current.users, enabledModules);
                notifications.add(() -> listeners.forEach(ModuleListener::onModulesReloaded));
            }
            PackageInfo pkg = null;

            try {
                pkg = ConfigManager.getPackageInfo(packageName, PackageManager.GET_META_DATA, userId);
            } catch (NameNotFoundException e) {
                InstalledModule old = current == null ? null : current.installedModules.get(key);
                if (current != null && current.installedModules.containsKey(key)) {
                    var modules = new HashMap<>(current.installedModules);
                    modules.remove(key);
                    publishSnapshot(modules, current.users, current.enabledModules);
                }
                if (old != null) notifications.add(() -> listeners.forEach(i -> i.onSingleModuleReloaded(old)));
                result = null;
            }

            if (pkg != null) {
                ApplicationInfo app = pkg.applicationInfo;
                var modernApk = getModernModuleApk(app, apiVersion);
                var legacy = isLegacyModule(app);
                var outdatedModernApk = modernApk == null && !legacy ? getOutdatedModernModuleApk(app, apiVersion) : null;
                if (modernApk != null || legacy || outdatedModernApk != null) {
                    InstalledModule module = new InstalledModule(pkg, modernApk != null ? modernApk : outdatedModernApk);
                    var modules = current == null
                            ? new HashMap<Pair<String, Integer>, InstalledModule>()
                            : new HashMap<>(current.installedModules);
                    modules.put(key, module);
                    publishSnapshot(modules, current == null ? null : current.users,
                            current == null ? new HashSet<>() : current.enabledModules);
                    notifications.add(() -> listeners.forEach(i -> i.onSingleModuleReloaded(module)));
                    result = module;
                } else {
                    InstalledModule old = current == null ? null : current.installedModules.get(key);
                    if (current != null && current.installedModules.containsKey(key)) {
                        var modules = new HashMap<>(current.installedModules);
                        modules.remove(key);
                        publishSnapshot(modules, current.users, current.enabledModules);
                    }
                    if (old != null) notifications.add(() -> listeners.forEach(i -> i.onSingleModuleReloaded(old)));
                    result = null;
                }
            }
        }
        notifications.forEach(Runnable::run);
        return result;
    }

    @Nullable
    public InstalledModule getModule(String packageName, int userId) {
        var current = snapshot;
        return current == null ? null : current.installedModules.get(Pair.create(packageName, userId));
    }

    @Nullable
    public InstalledModule getModule(String packageName) {
        return getModule(packageName, 0);
    }

    @Nullable
    public Map<Pair<String, Integer>, InstalledModule> getModules() {
        var current = snapshot;
        return current == null ? null : current.installedModules;
    }

    public boolean setModuleEnabled(String packageName, int userId, boolean enabled) {
        if (!ConfigManager.setModuleEnabled(packageName, userId, enabled)) {
            return false;
        }
        // The daemon call and the local publication may interleave with a full
        // scan, but applying the delta to whatever snapshot is current under
        // the lock keeps the last writer's change in the published state.
        var key = Pair.create(packageName, userId);
        synchronized (this) {
            var current = snapshot;
            if (current != null) {
                var enabledModules = new HashSet<>(current.enabledModules);
                if (enabled) {
                    enabledModules.add(key);
                } else {
                    enabledModules.remove(key);
                }
                publishSnapshot(current.installedModules, current.users, enabledModules);
            }
        }
        return true;
    }

    public boolean isModuleEnabled(String packageName, int userId) {
        var current = snapshot;
        return current != null && current.enabledModules.contains(Pair.create(packageName, userId));
    }

    public int getEnabledModulesCount() {
        var current = snapshot;
        return current == null ? -1 : current.enabledModules.size();
    }

    public void addListener(ModuleListener listener) {
        listeners.add(listener);
    }

    public void removeListener(ModuleListener listener) {
        listeners.remove(listener);
    }

    public interface ModuleListener {
        /**
         * Called whenever one (previously or now) installed module has been
         * reloaded
         */
        default void onSingleModuleReloaded(InstalledModule module) {

        }

        default void onModulesReloaded() {

        }
    }

    public class InstalledModule {
        //private static final int FLAG_FORWARD_LOCK = 1 << 29;
        public final int userId;
        public final String packageName;
        public final String versionName;
        public final long versionCode;
        public final boolean legacy;
        public final int minVersion;
        public final int targetVersion;
        public final boolean staticScope;
        public final long installTime;
        public final long updateTime;
        public final ApplicationInfo app;
        public final PackageInfo pkg;
        private String appName; // loaded lazily
        private String description; // loaded lazily
        private List<String> scopeList; // loaded lazily

        private InstalledModule(PackageInfo pkg, ZipFile modernModuleApk) {
            app = pkg.applicationInfo;
            this.pkg = pkg;
            userId = pkg.applicationInfo.uid / App.PER_USER_RANGE;
            packageName = pkg.packageName;
            versionName = pkg.versionName;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                versionCode = pkg.versionCode;
            } else {
                versionCode = pkg.getLongVersionCode();
            }
            installTime = pkg.firstInstallTime;
            updateTime = pkg.lastUpdateTime;
            legacy = modernModuleApk == null;

            if (legacy) {
                Object minVersionRaw = app.metaData.get("xposedminversion");
                if (minVersionRaw instanceof Integer) {
                    minVersion = (Integer) minVersionRaw;
                } else if (minVersionRaw instanceof String) {
                    minVersion = extractIntPart((String) minVersionRaw);
                } else {
                    minVersion = 0;
                }
                targetVersion = minVersion; // legacy modules don't have a target version
                staticScope = false;
            } else {
                int minVersion = 100;
                int targetVersion = 100;
                boolean staticScope = false;
                try (modernModuleApk) {
                    var propEntry = modernModuleApk.getEntry("META-INF/xposed/module.prop");
                    if (propEntry != null) {
                        var prop = new Properties();
                        prop.load(modernModuleApk.getInputStream(propEntry));
                        minVersion = extractIntPart(prop.getProperty("minApiVersion"));
                        targetVersion = extractIntPart(prop.getProperty("targetApiVersion"));
                        staticScope = TextUtils.equals(prop.getProperty("staticScope"), "true");
                    }
                    var scopeEntry = modernModuleApk.getEntry("META-INF/xposed/scope.list");
                    if (scopeEntry != null) {
                        try (var reader = new BufferedReader(new InputStreamReader(modernModuleApk.getInputStream(scopeEntry)))) {
                            scopeList = reader.lines().collect(Collectors.toList());
                        }
                    } else {
                        scopeList = Collections.emptyList();
                    }
                } catch (IOException | OutOfMemoryError e) {
                    Log.e(App.TAG, "Error while closing modern module APK", e);
                }
                this.minVersion = minVersion;
                this.targetVersion = targetVersion;
                this.staticScope = staticScope;
            }
        }

        public boolean isInstalledOnExternalStorage() {
            return (app.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0;
        }

        public String getAppName() {
            if (appName == null)
                appName = app.loadLabel(pm).toString();
            return appName;
        }

        public String getDescription() {
            if (this.description != null) return this.description;
            String descriptionTmp = "";
            if (legacy) {
                Object descriptionRaw = app.metaData.get("xposeddescription");
                if (descriptionRaw instanceof String) {
                    descriptionTmp = ((String) descriptionRaw).trim();
                } else if (descriptionRaw instanceof Integer) {
                    try {
                        int resId = (Integer) descriptionRaw;
                        if (resId != 0)
                            descriptionTmp = pm.getResourcesForApplication(app).getString(resId).trim();
                    } catch (Exception ignored) {
                    }
                }
            } else {
                var des = app.loadDescription(pm);
                if (des != null) descriptionTmp = des.toString();
            }
            this.description = descriptionTmp;
            return this.description;
        }

        public List<String> getScopeList() {
            if (scopeList != null) return scopeList;
            List<String> list = null;
            try {
                int scopeListResourceId = app.metaData.getInt("xposedscope");
                if (scopeListResourceId != 0) {
                    list = Arrays.asList(pm.getResourcesForApplication(app).getStringArray(scopeListResourceId));
                } else {
                    String scopeListString = app.metaData.getString("xposedscope");
                    if (scopeListString != null)
                        list = Arrays.asList(scopeListString.split(";"));
                }
            } catch (Exception ignored) {
            }
            if (list == null) {
                OnlineModule module = RepoLoader.getInstance().getOnlineModule(packageName);
                if (module != null && module.getScope() != null) {
                    list = module.getScope();
                }
            }
            if (list != null) {
                //For historical reasons, legacy modules use the opposite name.
                //https://github.com/rovo89/XposedBridge/commit/6b49688c929a7768f3113b4c65b429c7a7032afa
                list.replaceAll(s ->
                    switch (s) {
                        case "android" -> "system";
                        case "system" -> "android";
                        default -> s;
                    }
                );
                scopeList = list;
            }
            return scopeList;
        }

        public PackageInfo getPackageInfo() {
            return pkg;
        }

        @NonNull
        @Override
        public String toString() {
            return getAppName();
        }
    }
}
