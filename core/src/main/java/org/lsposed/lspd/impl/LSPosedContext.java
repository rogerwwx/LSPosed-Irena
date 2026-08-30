package org.lsposed.lspd.impl;

import android.annotation.SuppressLint;
import android.app.ActivityThread;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.DeadSystemException;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.lsposed.lspd.core.BuildConfig;
import org.lsposed.lspd.models.HotReloadOutcome;
import org.lsposed.lspd.models.Module;
import org.lsposed.lspd.nativebridge.NativeAPI;
import org.lsposed.lspd.service.ILSPInjectedModuleService;
import org.lsposed.lspd.util.LspModuleClassLoader;

import java.io.File;
import java.io.FileNotFoundException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import io.github.libxposed.api.error.XposedFrameworkError;
import io.github.libxposed.service.IXposedService;


@SuppressLint("NewApi")
public class LSPosedContext implements XposedInterface {

    private static final String TAG = "LSPosedContext";

    public static boolean isSystemServer;
    public static String appDir;
    public static String processName;

    // The framework's only strong reference to entry instances, and what detach() removes. Any
    // dispatch added later, hot reload included, must iterate this rather than keep its own list.
    static final Set<XposedModule> modules = ConcurrentHashMap.newKeySet();

    /**
     * One module generation loaded into this process. Entries are weak on purpose: modules owns
     * the only strong reference, and detach() removes it. A reload holds a local strong list for
     * the cycle instead.
     */
    private static class Generation {
        final ClassLoader classLoader;
        final LSPosedContext context;
        final List<WeakReference<XposedModule>> entryRefs;
        final boolean isSystemServer;
        final String processName;

        Generation(ClassLoader classLoader, LSPosedContext context, List<XposedModule> entries,
                   boolean isSystemServer, String processName) {
            this.classLoader = classLoader;
            this.context = context;
            this.entryRefs = new ArrayList<>(entries.size());
            for (var entry : entries) {
                this.entryRefs.add(new WeakReference<>(entry));
            }
            this.isSystemServer = isSystemServer;
            this.processName = processName;
        }

        List<XposedModule> liveEntries() {
            var result = new ArrayList<XposedModule>(entryRefs.size());
            for (var ref : entryRefs) {
                var entry = ref.get();
                if (entry != null && modules.contains(entry)) result.add(entry);
            }
            return result;
        }
    }

    private static final Map<String, Generation> generations = new ConcurrentHashMap<>();

    // Reloads are serialized per module within this process; the daemon serializes per target.
    private static final Map<String, ReentrantLock> reloadLocks = new ConcurrentHashMap<>();

    // Native entrypoints must be recorded before entry classes run (a module may dlopen from its
    // constructor), but the dlopen-hook list never shrinks: re-recording the same library on a hot
    // reload would fire the module-loaded callback twice.
    private static final Set<String> recordedNativeLibraries = ConcurrentHashMap.newKeySet();

    private final String mPackageName;
    private final ApplicationInfo mApplicationInfo;
    private final ILSPInjectedModuleService service;
    private final ExceptionMode mDefaultExceptionMode;
    private final Map<String, SharedPreferences> mRemotePrefs = new ConcurrentHashMap<>();
    private volatile boolean frozen = false;

    LSPosedContext(String packageName, ApplicationInfo applicationInfo, ILSPInjectedModuleService service,
                   ExceptionMode defaultExceptionMode) {
        this.mPackageName = packageName;
        this.mApplicationInfo = applicationInfo;
        this.service = service;
        this.mDefaultExceptionMode = defaultExceptionMode;
    }

    /** Stops all subsequent lifecycle callbacks for a single entry. */
    static void detach(XposedModule module) {
        if (modules.remove(module)) {
            Log.d(TAG, "Detached entry " + module.getClass().getName());
        }
    }

    void freeze() {
        frozen = true;
    }

    void unfreeze() {
        frozen = false;
    }

    public static void callOnPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        for (XposedModule module : modules) {
            try {
                module.onPackageLoaded(param);
            } catch (Throwable t) {
                Log.e(TAG, "Error when calling onPackageLoaded of " + module.getModuleApplicationInfo().packageName, t);
            }
        }
    }

    public static void callOnPackageReady(XposedModuleInterface.PackageReadyParam param) {
        for (XposedModule module : modules) {
            try {
                module.onPackageReady(param);
            } catch (Throwable t) {
                Log.e(TAG, "Error when calling onPackageReady of " + module.getModuleApplicationInfo().packageName, t);
            }
        }
    }

    public static void callOnSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        for (XposedModule module : modules) {
            try {
                module.onSystemServerStarting(param);
            } catch (Throwable t) {
                Log.e(TAG, "Error when calling onSystemServerStarting of " + module.getModuleApplicationInfo().packageName, t);
            }
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    public static boolean loadModule(ActivityThread at, Module module) {
        var built = buildGeneration(module, isSystemServer, processName);
        if (built == null) return false;
        var generation = built.first;
        var entries = built.second;

        entries.forEach(modules::add);
        generations.put(module.packageName, generation);

        var param = new XposedModuleInterface.ModuleLoadedParam() {
            @Override
            public boolean isSystemServer() {
                return isSystemServer;
            }

            @NonNull
            @Override
            public String getProcessName() {
                return processName;
            }
        };
        for (var entry : entries) {
            try {
                entry.onModuleLoaded(param);
            } catch (Throwable e) {
                Log.e(TAG, "    Failed to load class " + entry.getClass(), e);
            }
        }
        Log.d(TAG, "Loaded module " + module.packageName + ": " + generation.context);
        return true;
    }

    /**
     * Publishes nothing, so a reload can fail before the old generation is touched.
     */
    private static Pair<Generation, List<XposedModule>> buildGeneration(Module module,
                                                                        boolean isSystemServer,
                                                                        String processName) {
        try {
            Log.d(TAG, "Loading module " + module.packageName);
            var sb = new StringBuilder();
            var abis = Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
            for (String abi : abis) {
                sb.append(module.apkPath).append("!/lib/").append(abi).append(File.pathSeparator);
            }
            var librarySearchPath = sb.toString();
            var initLoader = XposedModule.class.getClassLoader();
            var blockLegacyApi = module.file.targetApiVersion >= 102;
            var mcl = LspModuleClassLoader.loadApk(module.apkPath, module.file.preLoadedDexes,
                    librarySearchPath, initLoader, blockLegacyApi);
            if (mcl.loadClass(XposedModule.class.getName()).getClassLoader() != initLoader) {
                Log.e(TAG, "  Cannot load module: " + module.packageName);
                Log.e(TAG, "  The Xposed API classes are compiled into the module's APK.");
                Log.e(TAG, "  This may cause strange issues and must be fixed by the module developer.");
                return null;
            }
            module.file.moduleLibraryNames.forEach(lib -> {
                if (recordedNativeLibraries.add(lib)) NativeAPI.recordNativeEntrypoint(lib);
            });
            var defaultExceptionMode = module.file.exceptionPassthrough ? ExceptionMode.PASSTHROUGH : ExceptionMode.PROTECTIVE;
            var ctx = new LSPosedContext(module.packageName, module.applicationInfo, module.service, defaultExceptionMode);
            var entries = new ArrayList<XposedModule>();
            for (var entry : module.file.moduleClassNames) {
                var moduleClass = mcl.loadClass(entry);
                Log.d(TAG, "  Loading class " + moduleClass);
                if (!XposedModule.class.isAssignableFrom(moduleClass)) {
                    Log.e(TAG, "    This class doesn't implement any sub-interface of XposedModule, skipping it");
                    continue;
                }
                try {
                    var moduleContext = (XposedModule) moduleClass.getConstructor().newInstance();
                    // detach() is per entry: only the instance that calls it stops.
                    moduleContext.attachFramework(ctx, () -> detach(moduleContext));
                    entries.add(moduleContext);
                } catch (Throwable e) {
                    Log.e(TAG, "    Failed to load class " + moduleClass, e);
                }
            }
            // A generation with nothing in it is not a generation.
            if (entries.isEmpty()) {
                Log.e(TAG, "No entry class of " + module.packageName + " could be instantiated");
                return null;
            }
            return new Pair<>(new Generation(mcl, ctx, entries, isSystemServer, processName), entries);
        } catch (Throwable e) {
            Log.d(TAG, "Loading module " + module.packageName, e);
            return null;
        }
    }

    /**
     * Loads a new generation of the module over the one already running. Runs in this process on
     * the hot reload worker thread. Returns the outcome to report back to the daemon.
     */
    public static HotReloadOutcome hotReload(String modulePackageName, Bundle extras, Module newModule) {
        if (modulePackageName == null) {
            return unsupported("Hot reload was requested without a module");
        }
        var lock = reloadLocks.computeIfAbsent(modulePackageName, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            return outcome(IXposedService.HOT_RELOAD_IN_PROGRESS,
                    "A reload of " + modulePackageName + " is already running in this process");
        }
        try {
            return runHotReload(modulePackageName, extras, newModule);
        } catch (Throwable t) {
            Log.e(TAG, "Hot reload of " + modulePackageName + " failed", t);
            return failed(describe(t));
        } finally {
            lock.unlock();
        }
    }

    private static HotReloadOutcome runHotReload(String packageName, Bundle extras, Module newModule) {
        if (newModule == null) {
            return unsupported("No new generation of " + packageName + " was supplied");
        }
        var old = generations.get(packageName);
        if (old == null) {
            return unsupported(packageName + " is not loaded in " + processName);
        }
        if (newModule.file.moduleClassNames.size() != 1) {
            return unsupported(packageName + " does not declare exactly one Java entry class");
        }

        // Keeps the old generation reachable until onHotReloaded has finished.
        var oldEntries = old.liveEntries();
        if (oldEntries.isEmpty()) {
            return unsupported("Every entry of " + packageName + " has detached in this process");
        }

        var built = buildGeneration(newModule, old.isSystemServer, old.processName);
        if (built == null) {
            return unsupported("Cannot build a new generation of " + packageName);
        }
        var newGeneration = built.first;
        var newEntries = built.second;

        // Before the callback, so registrations from inside it fail while unhook and replace work.
        // Under the hook registry's lock for this module, because a registration on another thread
        // that has already passed its own check must either finish before the freeze - and so be in
        // the list the successor is handed - or see the freeze and fail.
        synchronized (LSPosedBridge.lockOf(packageName)) {
            old.context.freeze();
        }

        var savedStateRef = new Object[1];
        var reloadingParam = new XposedModuleInterface.HotReloadingParam() {
            @Nullable
            @Override
            public Bundle getExtras() {
                return extras;
            }

            @Override
            public void setSavedInstanceState(@Nullable Object outState) {
                rejectOldGenerationState(outState, old.classLoader);
                savedStateRef[0] = outState;
            }
        };

        boolean accepted;
        try {
            // One refusal cancels the reload for the whole module.
            accepted = true;
            for (var entry : oldEntries) {
                if (!entry.onHotReloading(reloadingParam)) {
                    accepted = false;
                    break;
                }
            }
        } catch (Throwable t) {
            old.context.unfreeze();
            Log.e(TAG, "onHotReloading of " + packageName + " threw", t);
            return failed(describe(t));
        }
        if (!accepted) {
            old.context.unfreeze();
            Log.d(TAG, packageName + " refused the hot reload");
            return refusal();
        }

        // Captured after the freeze and after old code had its chance to unhook.
        var oldHandles = LSPosedBridge.snapshotHandles(packageName);

        oldEntries.forEach(modules::remove);
        // Active before the callback, so an entry detaching from inside it is honoured.
        newEntries.forEach(modules::add);

        var reloadedParam = new XposedModuleInterface.HotReloadedParam() {
            @Override
            public boolean isSystemServer() {
                return old.isSystemServer;
            }

            @NonNull
            @Override
            public String getProcessName() {
                return old.processName;
            }

            @Nullable
            @Override
            public Bundle getExtras() {
                return extras;
            }

            @Nullable
            @Override
            public Object getSavedInstanceState() {
                return savedStateRef[0];
            }

            @NonNull
            @Override
            public List<XposedInterface.HookHandle> getOldHookHandles() {
                return oldHandles;
            }
        };

        // Committed before the callback runs, because the interface releases the old generation
        // "after this callback returns or throws" - there is no rollback.
        generations.put(packageName, newGeneration);

        Throwable failure = null;
        // The default onHotReloaded already unhooks these; doing both would double-unhook.
        for (var entry : newEntries) {
            if (!modules.contains(entry)) continue;
            if (failure != null) break;
            try {
                entry.onHotReloaded(reloadedParam);
            } catch (Throwable t) {
                failure = t;
            }
        }

        if (failure != null) {
            Log.e(TAG, "onHotReloaded of " + packageName + " threw", failure);
            return failed(describe(failure), true);
        }

        Log.d(TAG, "Hot reloaded " + packageName);
        return outcome(IXposedService.HOT_RELOAD_SUCCEEDED, null, true);
    }

    /**
     * Rejects saved state that the old generation created, which would otherwise keep the retired
     * classloader reachable through the new one. A shallow scan, as the API describes it.
     */
    private static void rejectOldGenerationState(@Nullable Object state, ClassLoader oldClassLoader) {
        if (state == null) return;
        reject(state, oldClassLoader);
        if (state instanceof Object[] array) {
            for (var element : array) {
                if (element != null) reject(element, oldClassLoader);
            }
        } else if (state instanceof Collection<?> collection) {
            for (var element : collection) {
                if (element != null) reject(element, oldClassLoader);
            }
        } else if (state instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (entry.getKey() != null) reject(entry.getKey(), oldClassLoader);
                if (entry.getValue() != null) reject(entry.getValue(), oldClassLoader);
            }
        }
    }

    private static void reject(Object value, ClassLoader oldClassLoader) {
        if (definedBy(value.getClass(), oldClassLoader)) {
            throw new IllegalArgumentException(
                    "Saved instance state contains " + value.getClass().getName() + ", which was created under " +
                            "the old module classloader");
        }
    }

    private static boolean definedBy(Class<?> clazz, ClassLoader classLoader) {
        ClassLoader loader = (clazz.isArray() ? clazz.getComponentType() : clazz).getClassLoader();
        while (loader != null) {
            if (loader == classLoader) return true;
            loader = loader.getParent();
        }
        return false;
    }

    private static HotReloadOutcome outcome(int status, @Nullable String message, boolean generationChanged) {
        var o = new HotReloadOutcome();
        o.status = status;
        o.message = message;
        o.generationChanged = generationChanged;
        return o;
    }

    private static HotReloadOutcome outcome(int status, @Nullable String message) {
        return outcome(status, message, false);
    }

    private static HotReloadOutcome unsupported(String message) {
        return outcome(IXposedService.HOT_RELOAD_UNSUPPORTED, message);
    }

    private static HotReloadOutcome failed(String message) {
        return outcome(IXposedService.HOT_RELOAD_FAILED, message);
    }

    private static HotReloadOutcome failed(String message, boolean generationChanged) {
        return outcome(IXposedService.HOT_RELOAD_FAILED, message, generationChanged);
    }

    private static HotReloadOutcome refusal() {
        var o = outcome(IXposedService.HOT_RELOAD_FAILED, null);
        o.refused = true;
        return o;
    }

    private static String describe(Throwable t) {
        return t.getClass().getName() + ": " + (t.getMessage() != null ? t.getMessage() : "no message");
    }

    private static final class Pair<A, B> {
        final A first;
        final B second;

        Pair(A first, B second) {
            this.first = first;
            this.second = second;
        }
    }

    @NonNull
    @Override
    public String getFrameworkName() {
        return BuildConfig.FRAMEWORK_NAME;
    }

    @NonNull
    @Override
    public String getFrameworkVersion() {
        return BuildConfig.VERSION_NAME;
    }

    @Override
    public long getFrameworkVersionCode() {
        return BuildConfig.VERSION_CODE;
    }

    @Override
    public long getFrameworkProperties() {
        try {
            return service.getFrameworkProperties();
        } catch (RemoteException e) {
            throw new XposedFrameworkError(e);
        }
    }

    @Override
    @NonNull
    public HookBuilder hook(@NonNull Executable origin) {
        return LSPosedBridge.newHookBuilder(this, origin, mPackageName, () -> frozen, mDefaultExceptionMode);
    }

    @Override
    @NonNull
    public HookBuilder hookClassInitializer(@NonNull Class<?> origin) {
        return LSPosedBridge.newClassInitializerHookBuilder(this, origin, mPackageName, () -> frozen, mDefaultExceptionMode);
    }

    @Override
    public boolean deoptimize(@NonNull Executable executable) {
        return LSPosedBridge.doDeoptimize(executable);
    }

    @NonNull
    @Override
    public Invoker<?, Method> getInvoker(@NonNull Method method) {
        return LSPosedBridge.newInvoker(method);
    }

    @NonNull
    @Override
    public <T> CtorInvoker<T> getInvoker(@NonNull Constructor<T> constructor) {
        return LSPosedBridge.newInvoker(constructor);
    }

    @Override
    public void log(int priority, @Nullable String tag, @NonNull String msg) {
        if (BuildConfig.SPECIAL_BUILD) return;
        log(priority, tag, msg, null);
    }

    @Override
    public void log(int priority, @Nullable String tag, @NonNull String message, @Nullable Throwable throwable) {
        if (BuildConfig.SPECIAL_BUILD) return;
        if (message.isEmpty() && throwable == null) {
            return;
        }

        var estimatedLength = Math.max(0xFC2 - (tag == null ? 0 : tag.length()), 100);
        var output = new StringWriter(estimatedLength);
        var writer = new PrintWriter(output);

        var moduleTag = String.valueOf(tag);
        writer.println(String.format("[%s,%s] %s", mPackageName, moduleTag, message));

        if (throwable != null) {
            Throwable candidate;
            for (candidate = throwable; candidate != null && !(candidate instanceof UnknownHostException); candidate = candidate.getCause()) {
                if (candidate instanceof DeadSystemException) {
                    writer.println("DeadSystemException: The system died; earlier logs will point to the root cause");
                    break;
                }
            }
            if (candidate == null) {
                throwable.printStackTrace(writer);
            }
        }

        writer.flush();
        Log.println(priority, TAG, output.toString());
    }

    @NonNull
    @Override
    public ApplicationInfo getModuleApplicationInfo() {
        return mApplicationInfo;
    }

    @NonNull
    @Override
    public SharedPreferences getRemotePreferences(String name) {
        if (name == null) throw new IllegalArgumentException("name must not be null");
        return mRemotePrefs.computeIfAbsent(name, n -> {
            try {
                return new LSPosedRemotePreferences(service, n);
            } catch (RemoteException e) {
                log(Log.ERROR, "null", "Failed to get remote preferences", e);
                throw new XposedFrameworkError(e);
            }
        });
    }

    @NonNull
    @Override
    public String[] listRemoteFiles() {
        try {
            return service.getRemoteFileList();
        } catch (RemoteException e) {
            log(Log.ERROR, "null", "Failed to list remote files", e);
            throw new XposedFrameworkError(e);
        }
    }

    @NonNull
    @Override
    public ParcelFileDescriptor openRemoteFile(String name) throws FileNotFoundException {
        if (name == null) throw new IllegalArgumentException("name must not be null");
        try {
            return service.openRemoteFile(name);
        } catch (RemoteException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }
}
