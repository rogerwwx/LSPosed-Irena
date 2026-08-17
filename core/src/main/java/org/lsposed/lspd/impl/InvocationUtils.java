package org.lsposed.lspd.impl;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;

/** Reflection-compatible validation and virtual target resolution for API 102 invokers. */
final class InvocationUtils {
    private InvocationUtils() {
    }

    static Object checkReceiver(Executable executable, boolean isStatic, Object receiver) {
        if (isStatic) return null;
        if (receiver == null) throw new NullPointerException("null receiver");
        var declaringClass = executable.getDeclaringClass();
        if (!declaringClass.isInstance(receiver)) {
            throw new IllegalArgumentException("Expected receiver of type "
                    + declaringClass.getTypeName() + ", but got "
                    + receiver.getClass().getTypeName());
        }
        return receiver;
    }

    static Object[] coerceArguments(Executable executable, Class<?>[] parameterTypes,
                                    Object[] arguments) {
        var args = arguments == null ? new Object[0] : arguments;
        if (args.length != parameterTypes.length) {
            throw new IllegalArgumentException("Wrong number of arguments; expected "
                    + parameterTypes.length + ", got " + args.length);
        }
        var result = new Object[args.length];
        for (int i = 0; i < args.length; ++i) {
            result[i] = coerce(executable, parameterTypes[i], args[i], i);
        }
        return result;
    }

    private static Object coerce(Executable executable, Class<?> type, Object value, int index) {
        if (!type.isPrimitive()) {
            if (value != null && !type.isInstance(value)) {
                throw mismatch(executable, index, type, value);
            }
            return value;
        }
        if (value == null) throw mismatch(executable, index, type, null);

        if (type == boolean.class && value instanceof Boolean) return value;
        if (type == char.class && value instanceof Character) return value;
        if (type == byte.class && value instanceof Byte) return value;
        if (type == short.class) {
            if (value instanceof Byte v) return v.shortValue();
            if (value instanceof Short) return value;
        } else if (type == int.class) {
            if (value instanceof Byte v) return v.intValue();
            if (value instanceof Short v) return v.intValue();
            if (value instanceof Character v) return (int) v.charValue();
            if (value instanceof Integer) return value;
        } else if (type == long.class) {
            if (value instanceof Byte v) return v.longValue();
            if (value instanceof Short v) return v.longValue();
            if (value instanceof Character v) return (long) v.charValue();
            if (value instanceof Integer v) return v.longValue();
            if (value instanceof Long) return value;
        } else if (type == float.class) {
            if (value instanceof Byte v) return v.floatValue();
            if (value instanceof Short v) return v.floatValue();
            if (value instanceof Character v) return (float) v.charValue();
            if (value instanceof Integer v) return v.floatValue();
            if (value instanceof Long v) return v.floatValue();
            if (value instanceof Float) return value;
        } else if (type == double.class) {
            if (value instanceof Byte v) return v.doubleValue();
            if (value instanceof Short v) return v.doubleValue();
            if (value instanceof Character v) return (double) v.charValue();
            if (value instanceof Integer v) return v.doubleValue();
            if (value instanceof Long v) return v.doubleValue();
            if (value instanceof Float v) return v.doubleValue();
            if (value instanceof Double) return value;
        }
        throw mismatch(executable, index, type, value);
    }

    private static IllegalArgumentException mismatch(Executable executable, int index,
                                                     Class<?> type, Object value) {
        var name = executable instanceof Constructor<?> ? "<init>" : executable.getName();
        return new IllegalArgumentException("method " + executable.getDeclaringClass().getTypeName()
                + "." + name + " argument " + (index + 1) + " has type "
                + type.getTypeName() + ", got "
                + (value == null ? "null" : value.getClass().getTypeName()));
    }

    static boolean canBeOverridden(Method method) {
        int modifiers = method.getModifiers();
        if (Modifier.isStatic(modifiers) || Modifier.isPrivate(modifiers)
                || Modifier.isFinal(modifiers)) {
            return false;
        }
        return !Modifier.isFinal(method.getDeclaringClass().getModifiers());
    }

    static Method virtualTargetOf(Method method, Class<?>[] parameterTypes,
                                  Class<?> receiverClass) {
        var declaringClass = method.getDeclaringClass();
        if (!declaringClass.isAssignableFrom(receiverClass)) return null;

        var classes = new ArrayList<Class<?>>(4);
        Class<?> currentClass = receiverClass;
        while (currentClass != null && currentClass != declaringClass) {
            classes.add(currentClass);
            currentClass = currentClass.getSuperclass();
        }

        Method current = method;
        for (int i = classes.size() - 1; i >= 0; --i) {
            var override = declaredOverrideIn(classes.get(i), current, parameterTypes);
            if (override != null) current = override;
        }
        return current == method ? null : current;
    }

    private static Method declaredOverrideIn(Class<?> clazz, Method inherited,
                                             Class<?>[] parameterTypes) {
        for (var candidate : clazz.getDeclaredMethods()) {
            if (!candidate.getName().equals(inherited.getName())) continue;
            if (candidate.getReturnType() != inherited.getReturnType()) continue;
            if (candidate.getParameterCount() != parameterTypes.length) continue;
            var candidateParameters = candidate.getParameterTypes();
            boolean sameParameters = true;
            for (int i = 0; i < parameterTypes.length; ++i) {
                if (candidateParameters[i] != parameterTypes[i]) {
                    sameParameters = false;
                    break;
                }
            }
            if (!sameParameters) continue;

            int modifiers = candidate.getModifiers();
            if (Modifier.isStatic(modifiers) || Modifier.isPrivate(modifiers)) return null;
            return overrides(inherited, clazz) ? candidate : null;
        }
        return null;
    }

    private static boolean overrides(Method inherited, Class<?> subclass) {
        int modifiers = inherited.getModifiers();
        if (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)) return true;
        var owner = inherited.getDeclaringClass();
        return owner.getClassLoader() == subclass.getClassLoader()
                && packageNameOf(owner).equals(packageNameOf(subclass));
    }

    private static String packageNameOf(Class<?> clazz) {
        var name = clazz.getName();
        int lastDot = name.lastIndexOf('.');
        return lastDot < 0 ? "" : name.substring(0, lastDot);
    }
}
