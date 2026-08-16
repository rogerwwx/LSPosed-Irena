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
 * Copyright (C) 2021 - 2022 LSPosed Contributors
 */

package org.lsposed.lspd.service;

import static org.lsposed.lspd.service.ServiceManager.TAG;

import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;

import org.lsposed.lspd.models.Module;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import io.github.libxposed.service.HookedProcess;

public class LSPApplicationService extends ILSPApplicationService.Stub {
    final static int DEX_TRANSACTION_CODE = 1310096052;
    final static int OBFUSCATION_MAP_TRANSACTION_CODE = 724533732;
    // key: <uid, pid>
    private final static Map<Pair<Integer, Integer>, ProcessInfo> processes = new ConcurrentHashMap<>();

    /**
     * One module generation loaded into one process: what a hot reload request addresses.
     */
    static class HotReloadTarget {
        final long id;
        final String modulePackageName;
        final String processName;
        final int uid;
        final int pid;
        volatile long loadedVersionCode;
        final boolean hotReloadable;
        final AtomicInteger state = new AtomicInteger(HookedProcess.TARGET_STATE_UP_TO_DATE);

        HotReloadTarget(long id, String modulePackageName, String processName, int uid, int pid,
                        long loadedVersionCode, boolean hotReloadable) {
            this.id = id;
            this.modulePackageName = modulePackageName;
            this.processName = processName;
            this.uid = uid;
            this.pid = pid;
            this.loadedVersionCode = loadedVersionCode;
            this.hotReloadable = hotReloadable;
        }
    }

    private final static Map<Long, HotReloadTarget> hotReloadTargets = new ConcurrentHashMap<>();

    // Ids are framework-assigned and never reused, as HookedProcess.targetId requires.
    private final static AtomicLong nextHotReloadTargetId = new AtomicLong(1);

    static class ProcessInfo implements DeathRecipient {
        final int uid;
        final int pid;
        final String processName;
        final IBinder heartBeat;
        final Map<String, Long> targetIds = new ConcurrentHashMap<>();
        volatile IProcessChannel hotReloadBinder = null;

        ProcessInfo(int uid, int pid, String processName, IBinder heartBeat) throws RemoteException {
            this.uid = uid;
            this.pid = pid;
            this.processName = processName;
            this.heartBeat = heartBeat;
            heartBeat.linkToDeath(this, 0);
            Log.d(TAG, "register " + this);
            processes.put(new Pair<>(uid, pid), this);
        }

        @Override
        public void binderDied() {
            Log.d(TAG, this + " is dead");
            heartBeat.unlinkToDeath(this, 0);
            processes.remove(new Pair<>(uid, pid), this);
            targetIds.values().forEach(hotReloadTargets::remove);
        }

        @NonNull
        @Override
        public String toString() {
            return "ProcessInfo{" +
                    "uid=" + uid +
                    ", pid=" + pid +
                    ", processName='" + processName + '\'' +
                    ", heartBeat=" + heartBeat +
                    '}';
        }
    }

    private static void recordHotReloadTargets(ProcessInfo info, List<Module> modules) {
        for (var module : modules) {
            if (module.file == null || module.file.legacy) continue;
            info.targetIds.computeIfAbsent(module.packageName, pkg -> {
                var id = nextHotReloadTargetId.getAndIncrement();
                // system_server records its targets before the module cache exists, so its version
                // starts at zero; fall back to the cached version when one is already known.
                var cachedVersion = ConfigManager.getInstance().getModuleVersion(pkg);
                var versionCode = module.versionCode != 0L ? module.versionCode
                        : cachedVersion != null ? cachedVersion : 0L;
                hotReloadTargets.put(id, new HotReloadTarget(
                        id,
                        pkg,
                        info.processName,
                        info.uid,
                        info.pid,
                        versionCode,
                        // Hot reload is specified only for modules with exactly one Java entry class.
                        module.file.moduleClassNames != null && module.file.moduleClassNames.size() == 1
                ));
                return id;
            });
        }
    }

    /**
     * Whether [userId]'s copy of the module may address [target]. One module is one package and one
     * APK, but the copies installed for two users are two apps with two uids, and neither has any
     * business reloading the other's processes. The carve-out is for the AID_* uids below
     * {@link Process#FIRST_APPLICATION_UID}: system_server is one process for the whole device, and
     * every user holding the module is equally entitled to the one generation loaded there.
     */
    private static boolean addressableBy(HotReloadTarget target, int userId) {
        return target.uid < Process.FIRST_APPLICATION_UID || target.uid / PackageService.PER_USER_RANGE == userId;
    }

    // Not filtered to hot-reloadable targets: the AIDL documents this as hooked processes, and one
    // that cannot be reloaded answers UNSUPPORTED rather than disappearing.
    static List<HookedProcess> getHotReloadTargets(String modulePackageName, int userId) {
        var installedVersion = ConfigManager.getInstance().getModuleVersion(modulePackageName);
        return hotReloadTargets.values().stream()
                .filter(t -> t.modulePackageName.equals(modulePackageName) && addressableBy(t, userId))
                .map(t -> {
                    var p = new HookedProcess();
                    p.targetId = t.id;
                    p.uid = t.uid;
                    p.pid = t.pid;
                    p.processName = t.processName;
                    p.state = reportedState(t, installedVersion);
                    p.loadedVersionCode = t.loadedVersionCode;
                    return p;
                })
                .collect(Collectors.toList());
    }

    // RELOADING and FAILED describe the last attempt and outrank a version comparison.
    private static int reportedState(HotReloadTarget target, Long installedVersion) {
        var state = target.state.get();
        if (state != HookedProcess.TARGET_STATE_UP_TO_DATE) return state;
        // Zero means unknown, not old; claiming STALE would never be satisfiable by a reload.
        if (target.loadedVersionCode == 0L) return state;
        return (installedVersion != null && installedVersion != target.loadedVersionCode)
                ? HookedProcess.TARGET_STATE_STALE
                : state;
    }

    static HotReloadTarget getHotReloadTarget(long targetId, String modulePackageName, int userId) {
        var target = hotReloadTargets.get(targetId);
        if (target == null || !target.modulePackageName.equals(modulePackageName) || !addressableBy(target, userId)) {
            return null;
        }
        return target;
    }

    static List<HotReloadTarget> staleHotReloadTargets(String modulePackageName) {
        var installedVersion = ConfigManager.getInstance().getModuleVersion(modulePackageName);
        if (installedVersion == null || installedVersion == 0L) return Collections.emptyList();
        return hotReloadTargets.values().stream()
                .filter(t -> t.modulePackageName.equals(modulePackageName)
                        && t.hotReloadable
                        && t.loadedVersionCode != 0L
                        && t.loadedVersionCode != installedVersion)
                .collect(Collectors.toList());
    }

    static boolean isProcessRegistered(HotReloadTarget target) {
        return processes.containsKey(new Pair<>(target.uid, target.pid));
    }

    // Reloads are serialized per target, so check and transition must be one atomic step.
    static boolean beginHotReload(HotReloadTarget target) {
        while (true) {
            var current = target.state.get();
            if (current == HookedProcess.TARGET_STATE_RELOADING) return false;
            if (target.state.compareAndSet(current, HookedProcess.TARGET_STATE_RELOADING)) return true;
        }
    }

    static void endHotReload(HotReloadTarget target, int state, Long loadedVersionCode) {
        if (loadedVersionCode != null) target.loadedVersionCode = loadedVersionCode;
        target.state.set(state);
    }

    static IProcessChannel getHotReloadBinder(HotReloadTarget target) {
        var info = processes.get(new Pair<>(target.uid, target.pid));
        return info == null ? null : info.hotReloadBinder;
    }

    /**
     * system_server records its targets before the daemon's module cache exists, so they start
     * without a version. Fill those in from the cache whenever it is refreshed.
     */
    static void backfillLoadedVersions() {
        for (var target : hotReloadTargets.values()) {
            if (target.loadedVersionCode != 0L) continue;
            var version = ConfigManager.getInstance().getModuleVersion(target.modulePackageName);
            if (version != null && version != 0L) target.loadedVersionCode = version;
        }
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        Log.d(TAG, "LSPApplicationService.onTransact: code=" + code);
        switch (code) {
            case DEX_TRANSACTION_CODE: {
                var shm = ConfigManager.getInstance().getPreloadDex();
                if (shm == null) return false;
                // assume that write only a fd
                shm.writeToParcel(reply, 0);
                reply.writeLong(shm.getSize());
                return true;
            }
            case OBFUSCATION_MAP_TRANSACTION_CODE: {
                var obfuscation = ConfigManager.getInstance().dexObfuscate();
                var signatures = ObfuscationManager.getSignatures();
                reply.writeInt(signatures.size() * 2);
                for (Map.Entry<String, String> entry : signatures.entrySet()) {
                    reply.writeString(entry.getKey());
                    // return val = key if obfuscation disabled
                    reply.writeString(obfuscation ? entry.getValue() : entry.getKey());
                }
                return true;
            }
        }
        return super.onTransact(code, data, reply, flags);
    }

    public boolean registerHeartBeat(int uid, int pid, String processName, IBinder heartBeat) {
        try {
            new ProcessInfo(uid, pid, processName, heartBeat);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    private List<Module> getAllModulesList() throws RemoteException {
        var processInfo = ensureRegistered();
        if (processInfo.uid == Process.SYSTEM_UID && processInfo.processName.equals("system")) {
            return ConfigManager.getInstance().getModulesForSystemServer();
        }
        if (ServiceManager.getManagerService().isRunningManager(processInfo.pid, processInfo.uid))
            return Collections.emptyList();
        return ConfigManager.getInstance().getModulesForProcess(processInfo.processName, processInfo.uid);
    }

    @Override
    public List<Module> getLegacyModulesList() throws RemoteException {
        return getAllModulesList().stream().filter(m -> m.file.legacy).collect(Collectors.toList());
    }

    @Override
    public List<Module> getModulesList() throws RemoteException {
        var processInfo = ensureRegistered();
        var modules = getAllModulesList().stream().filter(m -> !m.file.legacy).collect(Collectors.toList());
        // A target is recorded as a side effect of answering, so system_server - which loads its
        // modules before the daemon's module cache exists - is covered the same way as any app.
        recordHotReloadTargets(processInfo, modules);
        return modules;
    }

    @Override
    public void attachProcessChannel(IProcessChannel channel) throws RemoteException {
        // Synchronous on purpose: a oneway transaction arrives with getCallingPid() == 0, and this
        // registry is keyed on (uid, pid).
        var info = ensureRegistered();
        info.hotReloadBinder = channel;
        Log.d(TAG, "Process channel attached for " + info.processName + " (pid=" + info.pid + ")");
    }

    @Override
    public String getPrefsPath(String packageName) throws RemoteException {
        ensureRegistered();
        return ConfigManager.getInstance().getPrefsPath(packageName, getCallingUid());
    }

    @Override
    public ParcelFileDescriptor requestInjectedManagerBinder(List<IBinder> binder) throws RemoteException {
        var processInfo = ensureRegistered();
        if (ServiceManager.getManagerService().postStartManager(processInfo.pid, processInfo.uid) ||
                ConfigManager.getInstance().isManager(processInfo.uid)) {
            binder.add(ServiceManager.getManagerService().obtainManagerBinder(processInfo.heartBeat, processInfo.pid, processInfo.uid));
        }
        return ConfigManager.getInstance().getManagerApk();
    }

    public boolean hasRegister(int uid, int pid) {
        return processes.containsKey(new Pair<>(uid, pid));
    }

    @NonNull
    private ProcessInfo ensureRegistered() throws RemoteException {
        var uid = getCallingUid();
        var pid = getCallingPid();
        var key = new Pair<>(uid, pid);
        ProcessInfo processInfo = processes.getOrDefault(key, null);
        if (processInfo == null || uid != processInfo.uid || pid != processInfo.pid) {
            processes.remove(key, processInfo);
            Log.w(TAG, "non-authorized: info=" + processInfo + " uid=" + uid + " pid=" + pid);
            throw new RemoteException("Not registered");
        }
        return processInfo;
    }
}
