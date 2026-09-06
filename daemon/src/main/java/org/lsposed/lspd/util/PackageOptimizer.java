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
 * Copyright (C) 2026 LSPosed Contributors
 */

package org.lsposed.lspd.util;

import android.os.Build;
import android.util.Log;

import org.lsposed.lspd.service.PackageService;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Backs the manager's "Re-optimize" action: drops an app's stale ART profiles and rebuilds its
 * {@code speed-profile} dexopt artifacts.
 *
 * <p>Both steps were historically hidden {@code IPackageManager} binder calls
 * ({@code clearApplicationProfileData} / {@code performDexOptMode}). They are present through
 * Android 16 but dropped from {@code framework.jar} on Android 17, where the binder calls throw
 * {@code NoSuchMethodError}. Since Android 14, dexopt and profiles are handled by the ART Service,
 * which exposes the same operations through its shell ({@code cmd package art clear-app-profiles} /
 * {@code cmd package compile}) independently of those binder methods. We therefore use the shell on
 * Android 14+, falling back to the binder call, and use the binder path directly on older releases.
 */
public final class PackageOptimizer {

    private static final String TAG = "LSPosedOptimizer";

    private PackageOptimizer() {
    }

    private static final Backend backend =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                    ? new FallbackBackend(new ShellBackend(), new BinderBackend())
                    : new BinderBackend();

    /**
     * Re-optimizes [packageName]: clears its profiles, then forces a profile-guided recompile.
     *
     * <p>{@code speed-profile} only AOT-compiles methods recorded in the app's reference profile,
     * so clearing first prevents the recompile from re-baking a profile captured before the module
     * set changed.</p>
     */
    public static boolean optimize(String packageName) {
        // Best-effort clear: on failure the recompile merely reuses an older profile, which still
        // beats aborting the whole action.
        try {
            backend.clearProfiles(packageName);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to clear profiles for " + packageName, t);
        }
        try {
            return backend.compile(packageName);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to optimize " + packageName, t);
            return false;
        }
    }

    /** Each operation returns whether it succeeded. */
    private interface Backend {
        boolean clearProfiles(String packageName) throws Exception;

        boolean compile(String packageName) throws Exception;
    }

    /**
     * Runs [primary], falling back to [fallback] whenever an operation returns false or throws.
     *
     * <p>On Android 14+ the primary is the ART Service shell and the fallback is the binder call.
     * The binder methods are still present on Android 14/15/16, so the fallback is valid there; on
     * 17 they are gone, but the shell succeeds so the fallback is never reached.</p>
     */
    private static final class FallbackBackend implements Backend {
        private final Backend primary;
        private final Backend fallback;

        FallbackBackend(Backend primary, Backend fallback) {
            this.primary = primary;
            this.fallback = fallback;
        }

        @Override
        public boolean clearProfiles(String packageName) {
            return firstSuccess(primary, fallback, packageName, true);
        }

        @Override
        public boolean compile(String packageName) {
            return firstSuccess(primary, fallback, packageName, false);
        }

        private boolean firstSuccess(Backend a, Backend b, String packageName, boolean clear) {
            try {
                if (clear ? a.clearProfiles(packageName) : a.compile(packageName)) return true;
            } catch (Throwable ignored) {
            }
            try {
                return clear ? b.clearProfiles(packageName) : b.compile(packageName);
            } catch (Throwable t) {
                Log.e(TAG, "Fallback optimizer backend failed for " + packageName, t);
                return false;
            }
        }
    }

    /** Android 14+: dexopt and profiles are owned by the ART Service, reachable through its shell. */
    private static final class ShellBackend implements Backend {
        @Override
        public boolean clearProfiles(String packageName) throws Exception {
            return exec("cmd package art clear-app-profiles " + packageName).exitCode == 0;
        }

        @Override
        public boolean compile(String packageName) throws Exception {
            var result = exec("cmd package compile -m speed-profile -f " + packageName);
            return result.exitCode == 0 && result.output.contains("Success");
        }

        private static ExecResult exec(String command) throws Exception {
            // Merge stderr into stdout: an unread stderr pipe can fill up and block the child
            // forever, which would hang the waitFor() below on a binder thread.
            var process = new ProcessBuilder(command.split(" "))
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                var sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                output = sb.toString();
            }
            return new ExecResult(process.waitFor(), output);
        }

        private static final class ExecResult {
            final int exitCode;
            final String output;

            ExecResult(int exitCode, String output) {
                this.exitCode = exitCode;
                this.output = output;
            }
        }
    }

    /** Pre-14, and the Android 14+ fallback: the hidden {@code IPackageManager} binder calls. */
    private static final class BinderBackend implements Backend {
        @Override
        public boolean clearProfiles(String packageName) throws Exception {
            PackageService.clearApplicationProfileData(packageName);
            return true;
        }

        @Override
        public boolean compile(String packageName) throws Exception {
            return PackageService.performDexOptMode(packageName);
        }
    }
}
