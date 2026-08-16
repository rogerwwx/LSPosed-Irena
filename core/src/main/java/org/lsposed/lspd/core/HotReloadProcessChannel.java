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

package org.lsposed.lspd.core;

import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import org.lsposed.lspd.impl.LSPosedContext;
import org.lsposed.lspd.models.Module;
import org.lsposed.lspd.service.IHotReloadOutcomeCallback;
import org.lsposed.lspd.service.IProcessChannel;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * This process's end of the only channel the daemon has for calling in.
 *
 * <p>Handed over once while the framework bootstraps, before any module is loaded and carrying no
 * module identity - which is what lets system_server have one too, since its modules load before
 * the daemon's module cache exists.</p>
 */
public class HotReloadProcessChannel extends IProcessChannel.Stub {
    private static final String TAG = "LSPosedHotReload";

    private static final HotReloadProcessChannel instance = new HotReloadProcessChannel();

    /**
     * One thread, so reloads in this process are serialised even across modules, and so the
     * incoming oneway transaction returns at once. Running the cycle on the binder thread that
     * delivered it would hold one of this app's binder threads for as long as the module's
     * onHotReloading cares to take.
     */
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "lsposed-hot-reload"));

    private HotReloadProcessChannel() {
    }

    public static HotReloadProcessChannel getInstance() {
        return instance;
    }

    @Override
    public void hotReload(String modulePackageName, Bundle extras, Module module,
                          IHotReloadOutcomeCallback callback) {
        // The daemon is the only caller this binder was ever handed to, but it runs as the system
        // uid rather than as root, and this object lives in an app process - so the check is worth
        // stating rather than assuming. Nothing else may drive a module's lifecycle.
        var caller = Binder.getCallingUid();
        if (caller != Process.SYSTEM_UID && caller != 0) {
            Log.w(TAG, "Refusing a hot reload request from uid " + caller);
            return;
        }

        worker.execute(() -> {
            var outcome = LSPosedContext.hotReload(modulePackageName, extras, module);
            try {
                if (callback != null) callback.onOutcome(outcome);
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot report the hot reload outcome", e);
            }
        });
    }
}
