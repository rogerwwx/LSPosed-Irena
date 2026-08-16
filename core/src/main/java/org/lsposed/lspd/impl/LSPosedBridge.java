package org.lsposed.lspd.impl;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.lsposed.lspd.nativebridge.HookBridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.error.HookFailedError;

public class LSPosedBridge {

    private static final String TAG = "LSPosed-Bridge";
    private static final String CAST_ERROR = "Return value's type from hook callback does not match the hooked method";
    private static final Object[] EMPTY_ARRAY = new Object[0];

    private static final Method getCause;

    static {
        Method tmp;
        try {
            tmp = InvocationTargetException.class.getMethod("getCause");
        } catch (Throwable e) {
            tmp = null;
        }
        getCause = tmp;
    }

    /**
     * What the process remembers about the hooks a module has installed.
     *
     * <p>Keyed by module package name rather than by generation, because both things kept here have
     * to outlive one: an id exists so that new code can name a hook old code installed, and the
     * handle list handed to {@code HotReloadedParam#getOldHookHandles} is by definition the
     * previous generation's.</p>
     */
    private static final class IdKey {
        final String moduleId;
        final Executable executable;
        final String id;

        IdKey(String moduleId, Executable executable, String id) {
            this.moduleId = moduleId;
            this.executable = executable;
            this.id = id;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof IdKey)) return false;
            var key = (IdKey) o;
            return Objects.equals(moduleId, key.moduleId)
                    && executable.equals(key.executable)
                    && Objects.equals(id, key.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(moduleId, executable, id);
        }
    }

    private static final Map<IdKey, HookHandleImpl> hookIds = new ConcurrentHashMap<>();
    private static final Map<String, Set<HookHandleImpl>> hooksByModule = new ConcurrentHashMap<>();
    private static final Map<String, Object> hookLocks = new ConcurrentHashMap<>();

    /**
     * Serialises everything that decides which handle owns a registration, and is also what a hot
     * reload takes to freeze old code without racing a registration already under way.
     */
    static Object lockOf(String moduleId) {
        return hookLocks.computeIfAbsent(moduleId, k -> new Object());
    }

    private static HookHandleImpl findId(String moduleId, Executable origin, String id) {
        return hookIds.get(new IdKey(moduleId, origin, id));
    }

    private static void claimId(String moduleId, Executable origin, String id, HookHandleImpl handle) {
        hookIds.put(new IdKey(moduleId, origin, id), handle);
    }

    private static void releaseId(String moduleId, Executable origin, String id, HookHandleImpl handle) {
        hookIds.remove(new IdKey(moduleId, origin, id), handle);
    }

    private static void track(String moduleId, HookHandleImpl handle) {
        hooksByModule.computeIfAbsent(moduleId, k -> ConcurrentHashMap.newKeySet()).add(handle);
    }

    private static void forget(String moduleId, HookHandleImpl handle) {
        var set = hooksByModule.get(moduleId);
        if (set != null) set.remove(handle);
    }

    /** The hooks of [moduleId] that are still installed, for {@code HotReloadedParam#getOldHookHandles}. */
    static List<XposedInterface.HookHandle> snapshotHandles(String moduleId) {
        var set = hooksByModule.get(moduleId);
        if (set == null) return Collections.emptyList();
        var result = new ArrayList<XposedInterface.HookHandle>();
        for (var handle : set) {
            if (handle.isLive) result.add(handle);
        }
        return result;
    }

    /** Everything a handle remembers about one installed hook. */
    static final class HookRecord {
        final XposedInterface.Hooker hooker;
        final int priority;
        final XposedInterface.ExceptionMode exceptionMode;
        final String id;

        HookRecord(XposedInterface.Hooker hooker, int priority,
                   XposedInterface.ExceptionMode exceptionMode, String id) {
            this.hooker = hooker;
            this.priority = priority;
            this.exceptionMode = exceptionMode;
            this.id = id;
        }
    }

    public static void log(String text) {
        Log.i(TAG, text);
    }

    public static void log(Throwable t) {
        String logStr = Log.getStackTraceString(t);
        Log.e(TAG, logStr);
    }

    public static class NativeHooker<T extends Executable> {
        private final Object[] params;

        private NativeHooker(Executable method) {
            params = new Object[]{
                    method,
                    returnTypeOf(method),
                    Modifier.isStatic(method.getModifiers()),
            };
        }

        public Object callback(Object[] rawArgs) throws Throwable {
            var method = (T) params[0];
            var returnType = (Class<?>) params[1];
            var isStatic = (Boolean) params[2];

            Object thisObject;
            Object[] args;
            if (isStatic) {
                thisObject = null;
                args = rawArgs;
            } else {
                thisObject = rawArgs[0];
                args = new Object[rawArgs.length - 1];
                System.arraycopy(rawArgs, 1, args, 0, args.length);
            }

            Object[] hookers = HookBridge.callbackSnapshot(method, XposedInterface.PRIORITY_HIGHEST);
            if (hookers.length == 0) {
                return invokeOriginal(method, thisObject, args, method instanceof Constructor);
            }

            var chain = new ChainImpl<>(method, returnType, hookers, thisObject, args, false);
            try {
                return chain.proceed();
            } finally {
                chain.close();
            }
        }
    }

    private static Class<?> returnTypeOf(Executable executable) {
        if (!(executable instanceof Method method)) {
            return null;
        }
        var returnType = method.getReturnType();
        return returnType.isPrimitive() ? null : returnType;
    }

    private static Object checkReturnType(Object result, Class<?> returnType) {
        if (returnType != null && !HookBridge.instanceOf(result, returnType)) {
            throw new ClassCastException(CAST_ERROR);
        }
        return result;
    }

    private static Object unwrapInvocationTarget(InvocationTargetException e) throws Throwable {
        if (getCause == null) {
            throw e;
        }
        Throwable cause = (Throwable) HookBridge.invokeOriginalMethod(getCause, e, EMPTY_ARRAY, false);
        if (cause != null) {
            throw cause;
        }
        throw e;
    }

    private static <T extends Executable> Object invokeOriginal(
            T method,
            Object thisObject,
            Object[] args,
            boolean isConstructor
    ) throws Throwable {
        try {
            return HookBridge.invokeOriginalMethod(method, thisObject, args, isConstructor);
        } catch (InvocationTargetException e) {
            return unwrapInvocationTarget(e);
        }
    }

    static class ChainImpl<T extends Executable> implements XposedInterface.Chain {
        private final int threadId = HookBridge.gettid();
        private final T executable;
        private final Class<?> returnType;
        private final Object[] hookers;
        private final boolean special;
        private int index = -1;
        private boolean active = true;
        private Object thisObject;
        private Object[] args;

        ChainImpl(T executable, Class<?> returnType, Object[] hookers,
                  Object thisObject, Object[] args, boolean special) {
            this.executable = executable;
            this.returnType = returnType;
            this.hookers = hookers == null ? EMPTY_ARRAY : hookers;
            this.thisObject = thisObject;
            this.args = args == null ? EMPTY_ARRAY : args;
            this.special = special;
        }

        void close() {
            active = false;
        }

        private void checkActive() {
            if (HookBridge.gettid() != threadId) {
                throw new IllegalStateException("Chain must be accessed in the same thread as the hooked method");
            }
            if (!active) {
                throw new IllegalStateException("Chain cannot be used after the interception ends");
            }
        }

        @NonNull
        @Override
        public Executable getExecutable() {
            return executable;
        }

        @Override
        public Object getThisObject() {
            return thisObject;
        }

        @NonNull
        @Override
        public List<Object> getArgs() {
            return Collections.unmodifiableList(Arrays.asList(args));
        }

        @Override
        public Object getArg(int index) throws IndexOutOfBoundsException, ClassCastException {
            return args[index];
        }

        @Override
        public Object proceed() throws Throwable {
            checkActive();
            var next = ++index;
            try {
                if (next != hookers.length) {
                    Object result = ((XposedInterface.Hooker) hookers[next]).intercept(this);
                    return checkReturnType(result, returnType);
                }
                if (special) {
                    try {
                        return checkReturnType(HookBridge.invokeSpecialMethod(executable, null, thisObject, args), returnType);
                    } catch (InvocationTargetException e) {
                        return unwrapInvocationTarget(e);
                    }
                }
                return checkReturnType(invokeOriginal(executable, thisObject, args, executable instanceof Constructor), returnType);
            } finally {
                index--;
            }
        }

        @Override
        public Object proceed(@NonNull Object[] args) throws Throwable {
            var oldArgs = this.args;
            this.args = args;
            try {
                return proceed();
            } finally {
                this.args = oldArgs;
            }
        }

        @Override
        public Object proceedWith(@NonNull Object thisObject) throws Throwable {
            var oldThisObject = this.thisObject;
            this.thisObject = thisObject;
            try {
                return proceed();
            } finally {
                this.thisObject = oldThisObject;
            }
        }

        @Override
        public Object proceedWith(@NonNull Object thisObject, @NonNull Object[] args) throws Throwable {
            var oldThisObject = this.thisObject;
            var oldArgs = this.args;
            this.thisObject = thisObject;
            this.args = args;
            try {
                return proceed();
            } finally {
                this.thisObject = oldThisObject;
                this.args = oldArgs;
            }
        }
    }

    static class ProtectiveChain implements XposedInterface.Chain {
        private final XposedInterface.Chain base;
        private boolean proceeded;
        private Object result;
        private Throwable throwable;

        ProtectiveChain(XposedInterface.Chain base) {
            this.base = base;
        }

        @NonNull
        @Override
        public Executable getExecutable() {
            return base.getExecutable();
        }

        @Override
        public Object getThisObject() {
            return base.getThisObject();
        }

        @NonNull
        @Override
        public List<Object> getArgs() {
            return base.getArgs();
        }

        @Override
        public Object getArg(int index) throws IndexOutOfBoundsException, ClassCastException {
            return base.getArg(index);
        }

        @Override
        public Object proceed() throws Throwable {
            proceeded = true;
            try {
                result = base.proceed();
                return result;
            } catch (Throwable t) {
                throwable = t;
                throw t;
            }
        }

        @Override
        public Object proceed(@NonNull Object[] args) throws Throwable {
            proceeded = true;
            try {
                result = base.proceed(args);
                return result;
            } catch (Throwable t) {
                throwable = t;
                throw t;
            }
        }

        @Override
        public Object proceedWith(@NonNull Object thisObject) throws Throwable {
            proceeded = true;
            try {
                result = base.proceedWith(thisObject);
                return result;
            } catch (Throwable t) {
                throwable = t;
                throw t;
            }
        }

        @Override
        public Object proceedWith(@NonNull Object thisObject, @NonNull Object[] args) throws Throwable {
            proceeded = true;
            try {
                result = base.proceedWith(thisObject, args);
                return result;
            } catch (Throwable t) {
                throwable = t;
                throw t;
            }
        }
    }

    static class ProtectiveHooker implements XposedInterface.Hooker {
        private final XposedInterface context;
        private final XposedInterface.Hooker hooker;

        ProtectiveHooker(XposedInterface context, XposedInterface.Hooker hooker) {
            this.context = context;
            this.hooker = hooker;
        }

        @Override
        public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
            var protectiveChain = new ProtectiveChain(chain);
            try {
                return hooker.intercept(protectiveChain);
            } catch (Throwable t) {
                if (protectiveChain.throwable == t) {
                    throw t;
                }
                context.log(Log.WARN, "ProtectiveHooker", "Exception in hooker", t);
                if (!protectiveChain.proceeded) {
                    return chain.proceed();
                }
                if (protectiveChain.throwable != null) {
                    throw protectiveChain.throwable;
                }
                return protectiveChain.result;
            }
        }
    }

    static class HookBuilderImpl implements XposedInterface.HookBuilder {
        private final XposedInterface context;
        private final Executable executable;
        private final String moduleId; // null for framework hooks
        private final BooleanSupplier frozen;
        private final XposedInterface.ExceptionMode defaultExceptionMode;
        private int priority = XposedInterface.PRIORITY_DEFAULT;
        private XposedInterface.ExceptionMode exceptionMode = XposedInterface.ExceptionMode.DEFAULT;
        private String id;

        HookBuilderImpl(XposedInterface context, Executable executable, String moduleId,
                        BooleanSupplier frozen, XposedInterface.ExceptionMode defaultExceptionMode) {
            this.context = context;
            this.executable = executable;
            this.moduleId = moduleId;
            this.frozen = frozen;
            this.defaultExceptionMode = defaultExceptionMode;
        }

        @Override
        public XposedInterface.HookBuilder setPriority(int priority) {
            this.priority = priority;
            return this;
        }

        @Override
        public XposedInterface.HookBuilder setExceptionMode(@NonNull XposedInterface.ExceptionMode mode) {
            this.exceptionMode = mode;
            return this;
        }

        @Override
        public XposedInterface.HookBuilder setId(@Nullable String id) {
            this.id = id;
            return this;
        }

        @NonNull
        @Override
        public XposedInterface.HookHandle intercept(@NonNull XposedInterface.Hooker hooker) {
            if (hooker == null) throw new IllegalArgumentException("hooker should not be null!");
            ensureNotFrozen();
            if (Modifier.isAbstract(executable.getModifiers())) {
                throw new IllegalArgumentException("Cannot hook abstract methods: " + executable);
            } else if (executable.getDeclaringClass().getClassLoader() == LSPosedContext.class.getClassLoader()) {
                throw new IllegalArgumentException("Do not allow hooking inner methods");
            } else if (executable.getDeclaringClass() == Method.class && executable.getName().equals("invoke")) {
                throw new IllegalArgumentException("Cannot hook Method.invoke");
            }
            if (exceptionMode == XposedInterface.ExceptionMode.PROTECTIVE
                    || exceptionMode == XposedInterface.ExceptionMode.DEFAULT
                    && defaultExceptionMode == XposedInterface.ExceptionMode.PROTECTIVE) {
                hooker = new ProtectiveHooker(context, hooker);
            }
            var resolvedMode = exceptionMode == XposedInterface.ExceptionMode.DEFAULT
                    ? defaultExceptionMode : exceptionMode;
            var record = new HookRecord(hooker, priority, resolvedMode, id);

            // A framework hook. No module, so no id to scope and nothing to serialise against.
            if (moduleId == null) return register(record, null);

            synchronized (lockOf(moduleId)) {
                // Checked again under the lock a reload takes to freeze old code, so a registration
                // cannot slip in between the check above and the snapshot the successor is handed.
                ensureNotFrozen();

                // Same module, same executable, same id: the interface says this replaces the old
                // hook atomically and invalidates its handle, rather than installing a second one.
                if (id != null) {
                    var existing = findId(moduleId, executable, id);
                    if (existing != null && existing.isLive) {
                        return existing.swapLocked(record);
                    }
                }
                return register(record, moduleId);
            }
        }

        private void ensureNotFrozen() {
            if (frozen != null && frozen.getAsBoolean()) {
                throw new IllegalStateException(
                        "This module generation has been retired by a hot reload and cannot register hooks");
            }
        }

        /** Installs [record] natively and records the handle against its owner. */
        private XposedInterface.HookHandle register(HookRecord record, String moduleId) {
            if (!HookBridge.hookMethod(executable, NativeHooker.class, record.priority, record.hooker)) {
                throw new HookFailedError("Cannot hook " + executable);
            }
            var handle = new HookHandleImpl(executable, moduleId, record);
            if (moduleId != null) {
                track(moduleId, handle);
                if (record.id != null) claimId(moduleId, executable, record.id, handle);
            }
            return handle;
        }
    }

    static class HookHandleImpl implements XposedInterface.HookHandle {
        private final Executable executable;
        private final String moduleId; // null for framework hooks, which cannot be replaced
        private volatile HookRecord record;
        private volatile boolean isLive = true;

        HookHandleImpl(Executable executable, String moduleId, HookRecord record) {
            this.executable = executable;
            this.moduleId = moduleId;
            this.record = record;
        }

        @NonNull
        @Override
        public Executable getExecutable() {
            return executable;
        }

        @Nullable
        @Override
        public String getId() {
            return record.id;
        }

        @Override
        public void unhook() {
            if (moduleId == null) {
                synchronized (this) {
                    if (!isLive) return;
                    isLive = false;
                }
                HookBridge.unhookMethod(executable, record.hooker);
                return;
            }
            synchronized (lockOf(moduleId)) {
                // Idempotent, as the interface requires - and a handle that has already been
                // superseded has to stay quiet here rather than tear down its own replacement.
                if (!isLive) return;
                isLive = false;
                if (record.id != null) releaseId(moduleId, executable, record.id, this);
                forget(moduleId, this);
                HookBridge.unhookMethod(executable, record.hooker);
            }
        }

        @NonNull
        @Override
        public XposedInterface.HookHandle replaceHook(@NonNull XposedInterface.Hooker hooker) {
            if (hooker == null) throw new IllegalArgumentException("hooker is null");
            if (moduleId == null) {
                throw new IllegalStateException("This hook does not belong to a module");
            }
            synchronized (lockOf(moduleId)) {
                if (!isLive) throw new IllegalStateException("This hook handle is no longer valid");
                // Everything but the hooker is inherited, which is what distinguishes this from
                // registering a new hook that happens to carry the same id.
                return swapLocked(new HookRecord(hooker, record.priority, record.exceptionMode, record.id));
            }
        }

        /**
         * Puts [replacement] where this handle's record is and hands the registration to a fresh
         * handle. Callers hold this module's lock, and [replacement] must carry this record's id.
         */
        private XposedInterface.HookHandle swapLocked(HookRecord replacement) {
            if (!HookBridge.replaceCallback(executable, record.hooker, replacement.hooker, replacement.priority)) {
                // The record was not where we left it, and nothing was changed, so whatever hook is
                // installed now stays installed.
                throw new HookFailedError("Cannot replace the hook on " + executable);
            }
            isLive = false;
            forget(moduleId, this);
            var handle = new HookHandleImpl(executable, moduleId, replacement);
            track(moduleId, handle);
            if (replacement.id != null) claimId(moduleId, executable, replacement.id, handle);
            return handle;
        }
    }

    static class BaseInvoker<T extends Executable> {
        final T executable;
        protected Object target;

        BaseInvoker(T executable) {
            this.executable = executable;
            this.target = XposedInterface.Invoker.Type.Chain.FULL;
            executable.setAccessible(true);
        }

        public Object invoke(Object thisObject, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
            var type = (XposedInterface.Invoker.Type) target;
            Objects.requireNonNull(type);
            if (type instanceof XposedInterface.Invoker.Type.Origin) {
                return HookBridge.invokeOriginalMethod(executable, thisObject, args, executable instanceof Constructor);
            }
            if (!(type instanceof XposedInterface.Invoker.Type.Chain chainType)) {
                throw new IllegalStateException("Unknown invoker type");
            }
            return invokeChain(thisObject, args, chainType.maxPriority(), false);
        }

        public Object invokeSpecial(@NonNull Object thisObject, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
            if (Modifier.isStatic(executable.getModifiers())) {
                throw new IllegalArgumentException("Cannot invoke special on static method: " + executable);
            }
            var type = (XposedInterface.Invoker.Type) target;
            Objects.requireNonNull(type);
            if (type instanceof XposedInterface.Invoker.Type.Origin) {
                try {
                    return HookBridge.invokeSpecialMethod(executable, null, thisObject, args);
                } catch (InstantiationException e) {
                    throw new InstantiationError(e.getMessage());
                }
            }
            if (!(type instanceof XposedInterface.Invoker.Type.Chain chainType)) {
                throw new IllegalStateException("Unknown invoker type");
            }
            return invokeChain(thisObject, args, chainType.maxPriority(), true);
        }

        Object invokeChain(Object thisObject, Object[] args, int maxPriority, boolean special)
                throws InvocationTargetException, IllegalAccessException {
            var hookers = HookBridge.callbackSnapshot(executable, maxPriority);
            var chain = new ChainImpl<>(executable, returnTypeOf(executable), hookers, thisObject, args, special);
            try {
                return chain.proceed();
            } catch (Error | RuntimeException | InvocationTargetException | IllegalAccessException e) {
                throw e;
            } catch (Throwable t) {
                throw new InvocationTargetException(t);
            } finally {
                chain.close();
            }
        }

        void setInvokerType(@NonNull XposedInterface.Invoker.Type type) {
            target = type;
        }
    }

    static class MethodInvokerImpl extends BaseInvoker<Method> implements XposedInterface.Invoker<MethodInvokerImpl, Method> {
        MethodInvokerImpl(Method executable) {
            super(executable);
        }

        @Override
        public MethodInvokerImpl setType(@NonNull XposedInterface.Invoker.Type type) {
            setInvokerType(type);
            return this;
        }
    }

    static class CtorInvokerImpl<T> extends BaseInvoker<Constructor<T>> implements XposedInterface.CtorInvoker<T> {
        CtorInvokerImpl(Constructor<T> executable) {
            super(executable);
        }

        @NonNull
        @Override
        public T newInstance(Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException, InstantiationException {
            var instance = HookBridge.allocateObject(executable.getDeclaringClass());
            invoke(instance, args);
            return instance;
        }

        @NonNull
        @Override
        public <U> U newInstanceSpecial(@NonNull Class<U> subClass, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException, InstantiationException {
            var type = (XposedInterface.Invoker.Type) super.target;
            Objects.requireNonNull(type);
            if (type instanceof XposedInterface.Invoker.Type.Origin) {
                return (U) HookBridge.invokeSpecialMethod(executable, subClass, null, args);
            }
            if (!(type instanceof XposedInterface.Invoker.Type.Chain chainType)) {
                throw new IllegalStateException("Unknown invoker type");
            }
            var instance = HookBridge.allocateSpecialReceiver(executable, subClass);
            invokeChain(instance, args, chainType.maxPriority(), true);
            return instance;
        }

        @Override
        public XposedInterface.CtorInvoker<T> setType(@NonNull XposedInterface.Invoker.Type type) {
            setInvokerType(type);
            return this;
        }
    }

    public static XposedInterface.HookHandle doHook(
            Executable hookMethod,
            int priority,
            XposedInterface.Hooker hooker
    ) {
        if (Modifier.isAbstract(hookMethod.getModifiers())) {
            throw new IllegalArgumentException("Cannot hook abstract methods: " + hookMethod);
        } else if (hookMethod.getDeclaringClass().getClassLoader() == LSPosedContext.class.getClassLoader()) {
            throw new IllegalArgumentException("Do not allow hooking inner methods");
        } else if (hookMethod.getDeclaringClass() == Method.class && hookMethod.getName().equals("invoke")) {
            throw new IllegalArgumentException("Cannot hook Method.invoke");
        } else if (hooker == null) {
            throw new IllegalArgumentException("hooker should not be null!");
        }

        if (HookBridge.hookMethod(hookMethod, LSPosedBridge.NativeHooker.class, priority, hooker)) {
            return new HookHandleImpl(hookMethod, null,
                    new HookRecord(hooker, priority, XposedInterface.ExceptionMode.DEFAULT, null));
        }
        throw new HookFailedError("Cannot hook " + hookMethod);
    }

    public static XposedInterface.HookBuilder newHookBuilder(
            XposedInterface context,
            Executable executable,
            String moduleId,
            BooleanSupplier frozen,
            XposedInterface.ExceptionMode defaultExceptionMode
    ) {
        Objects.requireNonNull(executable, "origin must not be null");
        return new HookBuilderImpl(context, executable, moduleId, frozen, defaultExceptionMode);
    }

    public static XposedInterface.HookBuilder newClassInitializerHookBuilder(
            XposedInterface context,
            Class<?> clazz,
            String moduleId,
            BooleanSupplier frozen,
            XposedInterface.ExceptionMode defaultExceptionMode
    ) {
        Objects.requireNonNull(clazz, "origin must not be null");
        if (clazz.getClassLoader() == LSPosedContext.class.getClassLoader()) {
            throw new IllegalArgumentException("Do not allow hooking inner classes");
        }
        synchronized (clazz) {
            Method classInitializer = HookBridge.findClassInitializer(clazz);
            if (classInitializer == null) {
                throw new IllegalArgumentException("Cannot find class initializer for " + clazz);
            }
            return new HookBuilderImpl(context, classInitializer, moduleId, frozen, defaultExceptionMode);
        }
    }

    public static boolean doDeoptimize(@NonNull Executable executable) {
        Objects.requireNonNull(executable, "executable must not be null");
        if (Modifier.isAbstract(executable.getModifiers())) {
            throw new IllegalArgumentException("Cannot deoptimize abstract methods: " + executable);
        } else if (Proxy.isProxyClass(executable.getDeclaringClass())) {
            throw new IllegalArgumentException("Cannot deoptimize methods from proxy class: " + executable);
        }
        return HookBridge.deoptimizeMethod(executable);
    }

    public static XposedInterface.Invoker<?, Method> newInvoker(@NonNull Method method) {
        Objects.requireNonNull(method, "method must not be null");
        return new MethodInvokerImpl(method);
    }

    public static <T> XposedInterface.CtorInvoker<T> newInvoker(@NonNull Constructor<T> constructor) {
        Objects.requireNonNull(constructor, "constructor must not be null");
        return new CtorInvokerImpl<>(constructor);
    }
}
