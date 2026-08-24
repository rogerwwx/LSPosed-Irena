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

package org.lsposed.lspd.service;

import static org.lsposed.lspd.service.PackageService.PER_USER_RANGE;

import android.content.AttributionSource;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.NonNull;

import org.lsposed.daemon.BuildConfig;
import org.lsposed.lspd.models.HotReloadOutcome;
import org.lsposed.lspd.models.Module;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.service.HookedProcess;
import io.github.libxposed.service.IHotReloadCallback;
import io.github.libxposed.service.IXposedScopeCallback;
import io.github.libxposed.service.IXposedService;

public class LSPModuleService extends IXposedService.Stub {

    private final static String TAG = "LSPosedModuleService";

    // Per-target serialization lives on the target itself; this only keeps one slow target from
    // delaying another.
    private final static ExecutorService hotReloadExecutor =
            Executors.newCachedThreadPool(r -> new Thread(r, "lsposed-hot-reload"));

    // How long a target gets to answer. Generous, because the whole point is that the callee runs
    // module code - but finite, because binder is not, and a target left in RELOADING answers every
    // later request with IN_PROGRESS for as long as the process lives.
    private final static long RELOAD_TIMEOUT_SECONDS = 30L;

    private final static Set<Integer> uidSet = ConcurrentHashMap.newKeySet();
    private final static Set<ModuleBinderKey> sentBinderSet = ConcurrentHashMap.newKeySet();

    /**
     * Which delivery is running for a key right now. The key alone is not enough to say that:
     * {@link #uidGone} deliberately drops the marker rather than wait for a send that may never
     * return, so from that moment a replacement process can start a second send while the first is
     * still blocked — and the first, on its way out, would remove the marker the second is
     * holding, letting a *third* start behind it, and would then commit its own dead process's
     * result over the second's. The value is the attempt that owns the key: a send touches nothing
     * unless the token it was handed is still the one here.
     */
    private final static Map<ModuleBinderKey, Object> sendingBinderSet = new ConcurrentHashMap<>();

    /**
     * Held wherever the delivery state changes hands: a send committing its result and
     * {@link #uidGone} both read one set to decide what to do to the other. Each set is
     * individually atomic, which is what makes the gap between them easy to miss — a send that
     * tested its ownership and was then overtaken by {@link #uidGone} before it committed left a
     * stale entry in {@link #sentBinderSet} that refused the replacement process. Nothing that
     * blocks runs under it.
     */
    private final static Object deliveryLock = new Object();

    private final static Map<Module, LSPModuleService> serviceMap = Collections.synchronizedMap(new WeakHashMap<>());
    private final static ExecutorService binderExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "module-binder-delivery"));

    static final int XPOSED_API_VERSION = XposedInterface.LIB_API;

    public final static String FILES_DIR = "files";

    private final @NonNull
    Module loadedModule;

    static void uidClear() {
        synchronized (deliveryLock) {
            uidSet.clear();
            sentBinderSet.clear();
            sendingBinderSet.clear();
        }
    }

    /**
     * Drives the same cycle as a service request, so onHotReloading can still refuse it. Called
     * after a module package update when the module opts in through {@code autoHotReload=true} in
     * module.prop.
     */
    static void autoHotReload(Module module) {
        if (module == null || module.file == null || !module.file.autoHotReload) return;
        var service = serviceMap.computeIfAbsent(module, LSPModuleService::new);
        for (var target : LSPApplicationService.staleHotReloadTargets(module.packageName)) {
            if (LSPApplicationService.beginHotReload(target)) {
                Log.d(TAG, "Auto hot reloading " + module.packageName + " in " + target.processName);
                hotReloadExecutor.execute(() -> service.runHotReload(target, null, null, module));
            }
        }
    }

    static void uidStarts(int uid) {
        if (uidSet.add(uid)) {
            sendBinderForUid(uid);
        }
    }

    static void uidGone(int uid) {
        synchronized (deliveryLock) {
            uidSet.remove(uid);
            sentBinderSet.removeIf(k -> k.uid == uid);
            // A send that never returns — `provider.call` runs the module's own onServiceBind,
            // with no deadline — would otherwise leave the key here for the life of the daemon,
            // and every later delivery for it refused at the top of sendBinderForModule. Giving
            // the key up rather than waiting is what lets the process that replaces this one be
            // served at once; the attempt token is what stops the send we walked away from
            // committing over it.
            sendingBinderSet.keySet().removeIf(k -> k.uid == uid);
        }
    }

    static void sendBindersForRunningModules() {
        for (int uid : uidSet) {
            sendBinderForUid(uid);
        }
    }

    static void sendBinderForRunningModule(String packageName) {
        for (int uid : uidSet) {
            var module = ConfigManager.getInstance().getModule(uid);
            if (module != null && Objects.equals(module.packageName, packageName)) {
                sendBinderForModule(module, uid);
            }
        }
    }

    private static void sendBinderForUid(int uid) {
        var module = ConfigManager.getInstance().getModule(uid);
        if (module != null) {
            sendBinderForModule(module, uid);
        }
    }

    private static void sendBinderForModule(Module module, int uid) {
        if (module.file == null || module.file.legacy) {
            return;
        }
        var key = new ModuleBinderKey(module.packageName, uid);
        // What identifies this attempt for as long as it runs, and what every later step of it is
        // tested against: see the comment on sendingBinderSet.
        var attempt = new Object();
        if (sentBinderSet.contains(key) || sendingBinderSet.putIfAbsent(key, attempt) != null) {
            return;
        }
        try {
            LSPModuleService service;
            synchronized (serviceMap) {
                service = serviceMap.computeIfAbsent(module, LSPModuleService::new);
            }
            binderExecutor.execute(() -> service.sendBinder(uid, key, attempt));
        } catch (Throwable e) {
            sendingBinderSet.remove(key, attempt);
            Log.w(TAG, "failed to schedule module binder for uid " + uid, e);
        }
    }

    private void sendBinder(int uid, ModuleBinderKey key, Object attempt) {
        var name = loadedModule.packageName;
        try {
            int userId = uid / PackageService.PER_USER_RANGE;
            if (!ConfigManager.getInstance().isModuleEnabledForUser(name, userId)) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    DeviceIdleService.addPowerSaveTempWhitelistApp(name, userId, "shell");
                    Log.d(TAG, "add " + userId + ":" + name + " to power save temp whitelist for 30s");
                    try {
                        Thread.sleep(400L);
                    } catch (InterruptedException e) {
                        Log.d(TAG, "sendBinder interrupted while waiting for whitelist, continuing for " + name, e);
                    }
                } catch (Throwable e) {
                    Log.e(TAG, "failed to add " + userId + ":" + name + " to power save temp whitelist", e);
                }
            }
            var authority = name + AUTHORITY_SUFFIX;
            var provider = ActivityManagerService.getContentProvider(authority, userId);
            for (int retry = 1; provider == null && retry < 3; retry++) {
                Log.d(TAG, "no service provider for " + name + ", retry " + retry);
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    Log.d(TAG, "sendBinder interrupted during retry sleep for " + name + ", continuing", e);
                }
                provider = ActivityManagerService.getContentProvider(authority, userId);
            }
            if (provider == null) {
                Log.d(TAG, "no service provider for " + name + " after 3 attempts");
                return;
            }
            var extra = new Bundle();
            extra.putBinder("binder", asBinder());
            Bundle reply = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                reply = provider.call(new AttributionSource.Builder(1000).setPackageName("android").build(), authority, SEND_BINDER, null, extra);
            } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
                reply = provider.call("android", null, authority, SEND_BINDER, null, extra);
            } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                reply = provider.call("android", authority, SEND_BINDER, null, extra);
            } else {
                reply = provider.call("android", SEND_BINDER, null, extra);
            }
            if (reply != null) {
                Log.d(TAG, "sent module binder to " + name);
                // Only the attempt that still owns the key may say the module has its service.
                // An abandoned one spoke to a process the uid has already outlived, and marking
                // the key served on its word is what refuses the process that replaced it.
                synchronized (deliveryLock) {
                    if (sendingBinderSet.get(key) == attempt) {
                        sentBinderSet.add(key);
                    }
                }
            } else {
                Log.w(TAG, "failed to send module binder to " + name);
            }
        } catch (Throwable e) {
            Log.w(TAG, "failed to send module binder for uid " + uid, e);
        } finally {
            sendingBinderSet.remove(key, attempt);
        }
    }

    private static final class ModuleBinderKey {
        private final String packageName;
        private final int uid;

        private ModuleBinderKey(String packageName, int uid) {
            this.packageName = packageName;
            this.uid = uid;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ModuleBinderKey)) return false;
            var key = (ModuleBinderKey) o;
            return uid == key.uid && Objects.equals(packageName, key.packageName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(packageName, uid);
        }
    }

    LSPModuleService(@NonNull Module module) {
        loadedModule = module;
    }

    private int ensureModule() throws RemoteException {
        var appId = Binder.getCallingUid() % PER_USER_RANGE;
        if (loadedModule.appId != appId) {
            throw new RemoteException("Module " + loadedModule.packageName + " is not for uid " + Binder.getCallingUid());
        }
        return Binder.getCallingUid() / PER_USER_RANGE;
    }

    @Override
    public int getApiVersion() throws RemoteException {
        ensureModule();
        return XPOSED_API_VERSION;
    }

    @Override
    public String getFrameworkName() throws RemoteException {
        ensureModule();
        return "LSPosed";
    }

    @Override
    public String getFrameworkVersion() throws RemoteException {
        ensureModule();
        return BuildConfig.VERSION_NAME;
    }

    @Override
    public long getFrameworkVersionCode() throws RemoteException {
        ensureModule();
        return BuildConfig.VERSION_CODE;
    }

    @Override
    public long getFrameworkProperties() throws RemoteException {
        ensureModule();
        var properties = IXposedService.PROP_CAP_SYSTEM | IXposedService.PROP_CAP_REMOTE;
        if (ConfigManager.getInstance().dexObfuscate()) {
            properties |= IXposedService.PROP_RT_API_PROTECTION;
        }
        return properties;
    }

    @Override
    public List<HookedProcess> getRunningTargets() throws RemoteException {
        var userId = ensureModule();
        return LSPApplicationService.getHotReloadTargets(loadedModule.packageName, userId);
    }

    @Override
    public void hotReloadModule(long targetId, Bundle data, IHotReloadCallback callback) throws RemoteException {
        // The user id matters as much as the app id here: ensureModule only proves the caller
        // shares the module's app id, which every copy of it does. The copies are one module and
        // one APK, but they are separate apps with separate uids, and the boundary that keeps a
        // module out of a user that never installed it applies to reloading too.
        var userId = ensureModule();
        // SecurityException is reserved by the AIDL for exactly these conditions, so it must not be
        // raised for anything else on this path.
        var target = LSPApplicationService.getHotReloadTarget(targetId, loadedModule.packageName, userId);
        if (target == null) {
            throw new SecurityException("Target " + targetId + " is not a target of " + loadedModule.packageName);
        }

        if (!target.hotReloadable) {
            // Hot reload is specified only for modules declaring exactly one Java entry class.
            report(callback, IXposedService.HOT_RELOAD_UNSUPPORTED, "Module has no single Java entry class");
            return;
        }

        if (!LSPApplicationService.beginHotReload(target)) {
            report(callback, IXposedService.HOT_RELOAD_IN_PROGRESS, "A reload is already running");
            return;
        }

        // The AIDL asks implementations to validate and enqueue promptly and report through the
        // callback. Running the cycle inline would pin this binder thread for its whole duration.
        var newModule = ConfigManager.getInstance().getModuleByPackageName(loadedModule.packageName);
        hotReloadExecutor.execute(() -> runHotReload(target, data, callback, newModule));
    }

    private void runHotReload(LSPApplicationService.HotReloadTarget target, Bundle data,
                              IHotReloadCallback callback, Module newModule) {
        var status = IXposedService.HOT_RELOAD_FAILED;
        String message = "Hot reload did not run";
        Long loadedVersion = null;
        var answered = new CountDownLatch(1);
        HotReloadOutcome[] outcomeRef = new HotReloadOutcome[1];

        try {
            var binder = LSPApplicationService.getHotReloadBinder(target);
            if (binder == null) {
                status = IXposedService.HOT_RELOAD_UNSUPPORTED;
                message = "Process " + target.processName + " has no hot reload entry point";
                return;
            }
            if (newModule == null || newModule.file == null || newModule.file.legacy) {
                status = IXposedService.HOT_RELOAD_UNSUPPORTED;
                message = "No installed generation of " + loadedModule.packageName + " to load";
                return;
            }

            var callbackStub = new IHotReloadOutcomeCallback.Stub() {
                @Override
                public void onOutcome(HotReloadOutcome result) {
                    outcomeRef[0] = result;
                    answered.countDown();
                }
            };
            binder.hotReload(loadedModule.packageName, data, newModule, callbackStub);

            // Bounded, because the callee runs arbitrary module code and binder has no timeout of
            // its own: without this a module that never returns from onHotReloading would leave
            // the target RELOADING for the life of the process.
            if (!answered.await(RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                status = LSPApplicationService.isProcessRegistered(target)
                        ? IXposedService.HOT_RELOAD_FAILED
                        : IXposedService.HOT_RELOAD_PROCESS_DIED;
                message = status == IXposedService.HOT_RELOAD_PROCESS_DIED
                        ? "Process " + target.processName + " died during hot reload"
                        : "Process " + target.processName + " did not answer within " + RELOAD_TIMEOUT_SECONDS + "s";
                return;
            }

            var answer = outcomeRef[0];
            if (answer == null) {
                status = IXposedService.HOT_RELOAD_FAILED;
                message = "Process " + target.processName + " answered with nothing";
                return;
            }

            status = answer.status;
            // Whether the generation was swapped is not the same question as whether the reload
            // succeeded: onHotReloaded runs after the swap is committed, so a throw from it leaves
            // the process on the new code and still reports FAILED.
            if (answer.generationChanged) loadedVersion = newModule.versionCode;
            // A null message is reserved for a refusal, so anything else gets one supplied.
            message = answer.message != null
                    ? answer.message
                    : (status == IXposedService.HOT_RELOAD_FAILED && !answer.refused
                    ? "Hot reload failed without a diagnostic message"
                    : null);
        } catch (Throwable t) {
            // Deliberately not keyed on DeadObjectException: a frozen-but-alive target answers a
            // transaction with exactly that, so the exception type says nothing about whether the
            // process is gone. The heartbeat registry does - it is driven by a DeathRecipient.
            var gone = !LSPApplicationService.isProcessRegistered(target);
            status = gone ? IXposedService.HOT_RELOAD_PROCESS_DIED : IXposedService.HOT_RELOAD_FAILED;
            message = gone
                    ? "Process " + target.processName + " died during hot reload"
                    : t.getClass().getName() + ": " + (t.getMessage() != null ? t.getMessage() : "no message");
            Log.e(TAG, "Hot reload of " + loadedModule.packageName + " failed", t);
        } finally {
            LSPApplicationService.endHotReload(target, stateFor(status), loadedVersion);
            report(callback, status, message);
        }
    }

    private static int stateFor(int status) {
        return switch (status) {
            case IXposedService.HOT_RELOAD_SUCCEEDED -> HookedProcess.TARGET_STATE_UP_TO_DATE;
            case IXposedService.HOT_RELOAD_FAILED -> HookedProcess.TARGET_STATE_FAILED;
            // Unsupported and process-died say nothing about the generation the target is running,
            // so the reported state falls back to comparing versions.
            default -> HookedProcess.TARGET_STATE_UP_TO_DATE;
        };
    }

    private static void report(IHotReloadCallback callback, int status, String message) {
        try {
            if (callback != null) callback.onHotReloadResult(status, message);
        } catch (Throwable t) {
            Log.w(TAG, "Cannot deliver hot reload result", t);
        }
    }

    @Override
    public List<String> getScope() throws RemoteException {
        ensureModule();
        ArrayList<String> res = new ArrayList<>();
        var scope = ConfigManager.getInstance().getModuleScope(loadedModule.packageName);
        if (scope == null) return res;
        for (var s : scope) {
            res.add(s.packageName);
        }
        return res;
    }

    @Override
    public void requestScope(List<String> packages, IXposedScopeCallback callback) throws RemoteException {
        Objects.requireNonNull(packages, "packages cannot be null");
        Objects.requireNonNull(callback, "callback cannot be null");
        var userId = ensureModule();
        var scopePackages = new ArrayList<>(packages);
        if (scopePackages.isEmpty()) {
            callback.onScopeRequestFailed("Invalid request");
            return;
        }
        if (ConfigManager.getInstance().scopeRequestBlocked(loadedModule.packageName, userId)) {
            callback.onScopeRequestFailed("Blocked by user");
        } else {
            LSPNotificationManager.requestModuleScope(loadedModule.packageName, userId, scopePackages, callback);
        }
    }

    @Override
    public void removeScope(List<String> packages) throws RemoteException {
        Objects.requireNonNull(packages, "packages cannot be null");
        var userId = ensureModule();
        try {
            if (!ConfigManager.getInstance().removeModuleScope(loadedModule.packageName, packages, userId)) {
                throw new RemoteException("Invalid request");
            }
        } catch (Throwable e) {
            throw new RemoteException(e.getMessage());
        }
    }

    @Override
    public Bundle requestRemotePreferences(String group) throws RemoteException {
        var userId = ensureModule();
        var bundle = new Bundle();
        bundle.putSerializable("map", ConfigManager.getInstance().getModulePrefs(loadedModule.packageName, userId, group));
        return bundle;
    }

    @Override
    public void updateRemotePreferences(String group, Bundle diff) throws RemoteException {
        var userId = ensureModule();
        Map<String, Object> values = new ArrayMap<>();
        if (diff.containsKey("delete")) {
            var deletes = (Set<?>) diff.getSerializable("delete");
            for (var key : deletes) {
                values.put((String) key, null);
            }
        }
        if (diff.containsKey("put")) {
            try {
                var puts = (Map<?, ?>) diff.getSerializable("put");
                for (var entry : puts.entrySet()) {
                    values.put((String) entry.getKey(), entry.getValue());
                }
            } catch (Throwable e) {
                Log.e(TAG, "updateRemotePreferences: ", e);
            }
        }
        try {
            ConfigManager.getInstance().updateModulePrefs(loadedModule.packageName, userId, group, values);
            ((LSPInjectedModuleService) loadedModule.service).onUpdateRemotePreferences(userId, group, diff);
        } catch (Throwable e) {
            throw new RemoteException(e.getMessage());
        }
    }

    @Override
    public void deleteRemotePreferences(String group) throws RemoteException {
        var userId = ensureModule();
        ConfigManager.getInstance().deleteModulePrefs(loadedModule.packageName, userId, group);
    }

    @Override
    public String[] listRemoteFiles() throws RemoteException {
        var userId = ensureModule();
        try {
            var dir = ConfigFileManager.resolveModuleDir(loadedModule.packageName, FILES_DIR, userId, Binder.getCallingUid());
            var files = dir.toFile().list();
            return files == null ? new String[0] : files;
        } catch (IOException e) {
            throw new RemoteException(e.getMessage());
        }
    }

    @Override
    public ParcelFileDescriptor openRemoteFile(String path) throws RemoteException {
        var userId = ensureModule();
        ConfigFileManager.ensureModuleFilePath(path);
        try {
            var dir = ConfigFileManager.resolveModuleDir(loadedModule.packageName, FILES_DIR, userId, Binder.getCallingUid());
            return ParcelFileDescriptor.open(dir.resolve(path).toFile(), ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_READ_WRITE);
        } catch (IOException e) {
            throw new RemoteException(e.getMessage());
        }
    }

    @Override
    public boolean deleteRemoteFile(String path) throws RemoteException {
        var userId = ensureModule();
        ConfigFileManager.ensureModuleFilePath(path);
        try {
            var dir = ConfigFileManager.resolveModuleDir(loadedModule.packageName, FILES_DIR, userId, Binder.getCallingUid());
            return dir.resolve(path).toFile().delete();
        } catch (IOException e) {
            throw new RemoteException(e.getMessage());
        }
    }
}
