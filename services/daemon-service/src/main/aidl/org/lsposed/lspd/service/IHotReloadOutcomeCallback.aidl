package org.lsposed.lspd.service;

import org.lsposed.lspd.models.HotReloadOutcome;

/**
 * Callback from an injected process to the daemon carrying the result of a hot reload attempt.
 */
interface IHotReloadOutcomeCallback {
    /**
     * Called when the hot reload attempt in the injected process finishes.
     */
    oneway void onOutcome(in HotReloadOutcome outcome);
}
