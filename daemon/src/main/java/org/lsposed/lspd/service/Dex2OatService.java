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
 * Copyright (C) 2022 LSPosed Contributors
 */

package org.lsposed.lspd.service;

import static org.lsposed.lspd.ILSPManagerService.DEX2OAT_CRASHED;
import static org.lsposed.lspd.ILSPManagerService.DEX2OAT_MOUNT_FAILED;
import static org.lsposed.lspd.ILSPManagerService.DEX2OAT_OK;
import static org.lsposed.lspd.ILSPManagerService.DEX2OAT_SELINUX_PERMISSIVE;
import static org.lsposed.lspd.ILSPManagerService.DEX2OAT_SEPOLICY_INCORRECT;
import static org.lsposed.lspd.ILSPManagerService.DEX2OAT_ZN_WAITING;
import static org.lsposed.lspd.ILSPManagerService.DEX2OAT_ZN_ACTIVE;

import android.net.LocalServerSocket;
import android.os.Build;
import android.os.FileObserver;
import android.os.Process;
import android.os.SELinux;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;

@RequiresApi(Build.VERSION_CODES.Q)
public class Dex2OatService implements Runnable {
    private static final String TAG = "LSPosedDex2Oat";
    private static final String WRAPPER32 = "bin/dex2oat32";
    private static final String WRAPPER64 = "bin/dex2oat64";
    private static final String PRELOAD32 = "lib/libpreload32.so";
    private static final String PRELOAD64 = "lib/libpreload64.so";

    private final String[] dex2oatArray = new String[4];
    private final FileDescriptor[] fdArray = new FileDescriptor[6];
    private final FileObserver selinuxObserver;
    private final boolean useArtD = Build.VERSION.SDK_INT >= 37;
    private volatile int compatibility = DEX2OAT_OK;

    private static boolean isSELinuxEnforcing() {
        // The project's SELinux compile-time stub does not expose isSELinuxEnforced().
        // Read the kernel state, as the legacy observer does; an unreadable state is not ready.
        try (var input = Files.newInputStream(Paths.get("/sys/fs/selinux/enforce"))) {
            return input.read() == '1';
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }

    private void openPreload(int id, String path) {
        try {
            var fd = Os.open(path, OsConstants.O_RDONLY, 0);
            fdArray[id] = fd;
        } catch (ErrnoException ignored) {
        }
    }

    private void openDex2oat(int id, String path) {
        try {
            var fd = Os.open(path, OsConstants.O_RDONLY, 0);
            dex2oatArray[id] = path;
            fdArray[id] = fd;
        } catch (ErrnoException ignored) {
        }
    }

    public Dex2OatService() {
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            openDex2oat(Process.is64Bit() ? 2 : 0, "/apex/com.android.runtime/bin/dex2oat");
            openDex2oat(Process.is64Bit() ? 3 : 1, "/apex/com.android.runtime/bin/dex2oatd");
        } else {
            openDex2oat(0, "/apex/com.android.art/bin/dex2oat32");
            openDex2oat(1, "/apex/com.android.art/bin/dex2oatd32");
            openDex2oat(2, "/apex/com.android.art/bin/dex2oat64");
            openDex2oat(3, "/apex/com.android.art/bin/dex2oatd64");
        }

        openPreload(4,"/data/adb/modules/zygisk_lsposed/lib/libpreload32.so");
        openPreload(5,"/data/adb/modules/zygisk_lsposed/lib/libpreload64.so");

        var enforce = Paths.get("/sys/fs/selinux/enforce");
        var policy = Paths.get("/sys/fs/selinux/policy");
        var list = new ArrayList<File>();
        list.add(enforce.toFile());
        list.add(policy.toFile());
        selinuxObserver = new FileObserver(list, FileObserver.CLOSE_WRITE) {
            @Override
            public synchronized void onEvent(int i, @Nullable String s) {
                Log.d(TAG, "SELinux status changed");
                if (compatibility == DEX2OAT_CRASHED) {
                    stopWatching();
                    return;
                }

                if (!isSELinuxEnforcing()) {
                    if (compatibility == DEX2OAT_OK) doMount(false);
                    compatibility = DEX2OAT_SELINUX_PERMISSIVE;
                } else if (SELinux.checkSELinuxAccess("u:r:untrusted_app:s0",
                        "u:object_r:dex2oat_exec:s0", "file", "execute")
                        || SELinux.checkSELinuxAccess("u:r:untrusted_app:s0",
                        "u:object_r:dex2oat_exec:s0", "file", "execute_no_trans")) {
                    if (compatibility == DEX2OAT_OK) doMount(false);
                    compatibility = DEX2OAT_SEPOLICY_INCORRECT;
                } else if (compatibility != DEX2OAT_OK) {
                    doMount(true);
                    if (notMounted()) {
                        doMount(false);
                        compatibility = DEX2OAT_MOUNT_FAILED;
                        stopWatching();
                    } else {
                        compatibility = DEX2OAT_OK;
                    }
                }
            }

            @Override
            public void stopWatching() {
                super.stopWatching();
                Log.w(TAG, "SELinux observer stopped");
            }
        };
    }

    private boolean notMounted() {
        for (int i = 0; i < dex2oatArray.length; i++) {
            var bin = dex2oatArray[i];
            if (bin == null) continue;
            try {
                var apex = Os.stat("/proc/1/root" + bin);
                var wrapper = Os.stat(i < 2 ? WRAPPER32 : WRAPPER64);
                if (apex.st_dev != wrapper.st_dev || apex.st_ino != wrapper.st_ino) {
                    Log.w(TAG, "Check mount failed for " + bin);
                    return true;
                }
            } catch (ErrnoException e) {
                Log.e(TAG, "Check mount failed for " + bin, e);
                return true;
            }
        }
        Log.d(TAG, "Check mount succeeded");
        return false;
    }

    private void doMount(boolean enabled) {
        if (useArtD) return;
        doMountNative(enabled, dex2oatArray[0], dex2oatArray[1], dex2oatArray[2], dex2oatArray[3]);
    }

    public void start() {
        if (useArtD) {
            compatibility = DEX2OAT_ZN_WAITING;
            new Thread(this::runArtD, "dex2oat-a17").start();
            return;
        }
        if (notMounted()) { // Already mounted when restart daemon
            doMount(true);
            if (notMounted()) {
                doMount(false);
                compatibility = DEX2OAT_MOUNT_FAILED;
                return;
            }
        }

        var thread = new Thread(this);
        thread.setName("dex2oat");
        thread.start();
        selinuxObserver.startWatching();
        selinuxObserver.onEvent(0, null);
    }

    @Override
    public void run() {
        Log.i(TAG, "Dex2oat wrapper daemon start");
        var sockPath = getSockPath();
        Log.d(TAG, "wrapper path: " + sockPath);
        var lsposed_file = "u:object_r:lsposed_file:s0";
        var dex2oat_exec = "u:object_r:dex2oat_exec:s0";
        var system_file = "u:object_r:system_file:s0";
        SELinux.setFileContext(PRELOAD32, system_file);
        SELinux.setFileContext(PRELOAD64, system_file);
        if (SELinux.checkSELinuxAccess("u:r:dex2oat:s0", dex2oat_exec,
                "file", "execute_no_trans")) {
            SELinux.setFileContext(WRAPPER32, dex2oat_exec);
            SELinux.setFileContext(WRAPPER64, dex2oat_exec);
            setSockCreateContext("u:r:dex2oat:s0");
        } else {
            SELinux.setFileContext(WRAPPER32, lsposed_file);
            SELinux.setFileContext(WRAPPER64, lsposed_file);
            setSockCreateContext("u:r:installd:s0");
        }
        try (var server = new LocalServerSocket(sockPath)) {
            setSockCreateContext(null);
            while (true) {
                // One misbehaving client must not take down the wrapper thread: a connection reset
                // throws IOException, and a client that disconnects before writing anything makes
                // read() return -1. Either used to escape the loop - the IOException retired the
                // wrapper for good, the RuntimeException escaped into the default uncaught handler
                // and System.exit()ed the whole daemon.
                try (var client = server.accept();
                     var is = client.getInputStream();
                     var os = client.getOutputStream()) {
                    int id = is.read();
                    if (id < 0 || id >= fdArray.length) continue;
                    var fd = new FileDescriptor[]{fdArray[id]};
                    client.setFileDescriptorsForSend(fd);
                    os.write(1);
                    Log.d(TAG, "Sent stock fd: is64 = " + ((id & 0b10) != 0) +
                            ", isDebug = " + ((id & 0b01) != 0));
                } catch (IOException e) {
                    Log.w(TAG, "dex2oat client failed", e);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Dex2oat wrapper daemon crashed", e);
            if (compatibility == DEX2OAT_OK) {
                doMount(false);
                compatibility = DEX2OAT_CRASHED;
            }
        }
    }

    // This broker never mounts or executes a compiler. The art_exec child retains its own
    // identity, task profile, process group and cancellation semantics across both execs.
    private void runArtD() {
        if (fdArray[4] == null && fdArray[5] == null) {
            compatibility = DEX2OAT_CRASHED;
            Log.e(TAG, "No preload library available for A17");
            return;
        }
        if (!isSELinuxEnforcing()) {
            compatibility = DEX2OAT_SELINUX_PERMISSIVE;
            return;
        }
        boolean labels = true;
        for (String path : new String[]{WRAPPER32, WRAPPER64}) {
            if (new File(path).exists()) labels &= SELinux.setFileContext(path, "u:object_r:dex2oat_exec:s0");
        }
        for (String path : new String[]{PRELOAD32, PRELOAD64}) {
            if (new File(path).exists()) labels &= SELinux.setFileContext(path, "u:object_r:system_file:s0");
        }
        if (!labels || !setSockCreateContext("u:r:dex2oat:s0")) {
            setSockCreateContext(null);
            compatibility = DEX2OAT_SEPOLICY_INCORRECT;
            return;
        }
        LocalServerSocket listener;
        try {
            listener = new LocalServerSocket(getSockPath() + ".a17");
        } catch (IOException e) {
            Log.e(TAG, "Cannot start A17 preload broker", e);
            compatibility = DEX2OAT_CRASHED;
            return;
        } finally {
            setSockCreateContext(null);
        }
        try (listener) {
            Log.i(TAG, "A17 preload broker ready; waiting for Zygisk Next artd injection");
            while (true) {
                try (var client = listener.accept()) {
                    client.setSoTimeout(1000);
                    if (!isSELinuxEnforcing()) {
                        compatibility = DEX2OAT_SELINUX_PERMISSIVE;
                        continue;
                    }
                    var peer = client.getPeerCredentials();
                    String context = Files.readString(Paths.get("/proc/" + peer.getPid() + "/attr/current"))
                            .replace("\u0000", "").trim();
                    if (!context.equals("u:r:dex2oat:s0")) continue;
                    var input = client.getInputStream();
                    if (input.read() != 1) continue;
                    int elfClass = input.read();
                    if (elfClass != 1 && elfClass != 2) continue;
                    var preload = fdArray[elfClass == 1 ? 4 : 5];
                    if (preload == null || !preload.valid()) continue;
                    client.setFileDescriptorsForSend(new FileDescriptor[]{preload});
                    client.getOutputStream().write(1);
                    // Observed interception, not a claim that CompilerOptions has been verified.
                    compatibility = DEX2OAT_ZN_ACTIVE;
                } catch (IOException | RuntimeException e) {
                    Log.w(TAG, "A17 preload request failed", e);
                }
            }
        } catch (IOException e) {
            compatibility = DEX2OAT_CRASHED;
            Log.e(TAG, "A17 preload broker stopped", e);
        }
    }

    public int getCompatibility() {
        return compatibility;
    }

    private native void doMountNative(boolean enabled,
                                      String r32, String d32, String r64, String d64);

    private static native boolean setSockCreateContext(String context);

    private native String getSockPath();
}
