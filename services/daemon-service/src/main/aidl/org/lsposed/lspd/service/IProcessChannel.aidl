package org.lsposed.lspd.service;

import org.lsposed.lspd.models.Module;
import org.lsposed.lspd.service.IHotReloadOutcomeCallback;

/**
 * The one thing the daemon calls <i>into</i> an injected process for.
 *
 * <p>Handed to the daemon by {@link ILSPApplicationService#attachProcessChannel} while the
 * framework bootstraps, before any module has loaded and carrying no module identity at all -
 * which is what makes it work for system_server, whose modules load before the daemon's module
 * cache exists.</p>
 *
 * <p>The process side additionally checks that the caller is the daemon.</p>
 */
interface IProcessChannel {
    /**
     * Loads a new generation of {@code module} over the one already running, and answers through
     * {@code callback}.
     *
     * <p>oneway, and answered out of band, because this runs the old code's
     * {@code onHotReloading} and the new code's {@code onHotReloaded} - module code, with no bound
     * on how long it takes. The daemon supplies a timeout instead.</p>
     *
     * @param modulePackageName which loaded module to replace
     * @param extras            what the module app passed to {@code hotReloadModule}, reaching the
     *                          old code as {@code HotReloadingParam#getExtras}. Null for a reload
     *                          the daemon started itself, on autoHotReload
     * @param module            the generation to load
     * @param callback          the callback to report the outcome through
     */
    oneway void hotReload(String modulePackageName, in Bundle extras, in Module module,
                          IHotReloadOutcomeCallback callback);
}
