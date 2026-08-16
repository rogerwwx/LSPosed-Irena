package org.lsposed.lspd.nativebridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import dalvik.annotation.optimization.FastNative;

public class HookBridge {
    public static native boolean hookMethod(Executable hookMethod, Class<?> hooker, int priority, Object callback);

    public static native boolean unhookMethod(Executable hookMethod, Object callback);

    public static native boolean replaceCallback(Executable hookMethod, Object oldCallback, Object newCallback, int newPriority);

    public static native String[] legacyApiPrefixes();

    public static native boolean deoptimizeMethod(Executable method);

    public static native <T> T allocateObject(Class<T> clazz) throws InstantiationException;

    public static native <T> T allocateSpecialReceiver(Constructor<?> constructor, Class<T> clazz) throws InstantiationException;

    public static native Method findClassInitializer(Class<?> clazz);

    public static native Object invokeOriginalMethod(Executable method, Object thisObject, Object[] args, boolean isConstructor) throws IllegalAccessException, IllegalArgumentException, InvocationTargetException;

    public static Object invokeOriginalMethod(Executable method, Object thisObject, Object... args) throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        return invokeOriginalMethod(method, thisObject, args, method instanceof Constructor);
    }

    public static native <T> Object invokeSpecialMethod(Executable method, Class<T> clazz, Object thisObject, Object... args) throws IllegalAccessException, IllegalArgumentException, InvocationTargetException, InstantiationException;

    public static Object invokeSpecialMethod(Executable method, Object thisObject, Object... args) throws IllegalAccessException, IllegalArgumentException, InvocationTargetException, InstantiationException {
        return invokeSpecialMethod(method, null, thisObject, args);
    }

    @FastNative
    public static native boolean instanceOf(Object obj, Class<?> clazz);

    @FastNative
    public static native int gettid();

    @FastNative
    public static native boolean setTrusted(Object cookie);

    /**
     * Clears the final flag ART reads, so that reflection will write [field] again.
     *
     * <p>Android 17 refuses every reflective write to a static final field however accessible the
     * Field is, and clearing the reflective copy's flag does not help because the check reads
     * the ArtField. The write itself stays with reflection, which keeps its conversions and its
     * exceptions; this only stops it being refused.</p>
     *
     * @param modifiers must be {@code field.getModifiers()}. It is checked against the flags about
     *                  to be written, so a runtime that lays an ArtField out differently is left
     *                  alone rather than corrupted.
     * @return false when the field is still final, which is the caller's cue to report the failure
     *         it already had. The field stays writable afterwards.
     */
    public static native boolean makeFieldWritable(Field field, int modifiers);

    public static native Object[] callbackSnapshot(Executable method, int maxPriority);
}
