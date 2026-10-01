package org.lsposed.lspd.service;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Package preferences and process matching, independent of Android Binder and package lookup. */
final class InlineHookPolicy {
    static final String KEY_PREFIX = "invalidate_art_inline_hooks:";

    private InlineHookPolicy() {}

    static boolean isValidPackage(String packageName) {
        // In Irena "system" is the virtual system_server target, not an APK.
        return packageName != null && !packageName.isEmpty() && !"system".equals(packageName)
                && packageName.matches("[a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)*");
    }

    static Set<String> configuredPackages(Map<String, Object> preferences) {
        var packages = new TreeSet<String>();
        preferences.forEach((key, value) -> {
            if (key.startsWith(KEY_PREFIX) && Boolean.TRUE.equals(value)) {
                var packageName = key.substring(KEY_PREFIX.length());
                if (isValidPackage(packageName)) packages.add(packageName);
            }
        });
        return packages;
    }

    static boolean mayInvalidate(String processName, int uid) {
        return processName != null && uid >= 0
                && !(uid == 1000 && ("system".equals(processName)
                || "system_server".equals(processName)));
    }

    static boolean matches(String packageName, int expectedUid, int actualUid,
                           String processName, String applicationProcess, Set<String> componentProcesses) {
        if (!mayInvalidate(processName, actualUid) || expectedUid != actualUid) return false;
        // ResolverActivity runs in this framework UI process, not in system_server.
        return ("android".equals(packageName) && actualUid == 1000 && "system:ui".equals(processName))
                || processName.equals(applicationProcess) || componentProcesses.contains(processName);
    }
}
