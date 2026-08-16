package org.lsposed.lspd.service;

import org.lsposed.lspd.models.Module;
import org.lsposed.lspd.service.IProcessChannel;

interface ILSPApplicationService {
    List<Module> getLegacyModulesList();

    List<Module> getModulesList();

    String getPrefsPath(String packageName);

    ParcelFileDescriptor requestInjectedManagerBinder(out List<IBinder> binder);

    /**
     * Registers the injected process's {@link IProcessChannel}, the only channel the daemon uses
     * to call into the process (currently only for hot reload). Called once while the framework
     * bootstraps, before any module is loaded.
     */
    void attachProcessChannel(IProcessChannel channel);
}
