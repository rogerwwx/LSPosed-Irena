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
package org.lsposed.lspd.service

import android.os.Process
import io.github.libxposed.service.HookedProcess
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * One module generation loaded into one process: what a hot reload request addresses.
 */
class HotReloadTarget(
    @JvmField val id: Long,
    @JvmField val modulePackageName: String,
    @JvmField val processName: String,
    @JvmField val uid: Int,
    @JvmField val pid: Int,
    @JvmField @Volatile var loadedVersionCode: Long,
    @JvmField val hotReloadable: Boolean,
) {
    val state = AtomicInteger(HookedProcess.TARGET_STATE_UP_TO_DATE)
}

/**
 * Registry of every module generation currently loaded into a hooked process, addressed by the
 * framework-assigned target id. Owns the per-target reload state machine: at most one reload runs
 * against a target at any time, and the last attempt's outcome is what every later query reports.
 */
object HotReloadRegistry {

    // Ids are framework-assigned and never reused, as HookedProcess.targetId requires.
    private val nextId = AtomicLong(1)
    private val targets = ConcurrentHashMap<Long, HotReloadTarget>()

    /**
     * Records one generation loaded into one process, or silently keeps the existing target when
     * the process re-reports its modules. [versionCode] may be zero when the caller could not know
     * it yet (system_server loads its modules before the module cache exists).
     */
    @JvmStatic
    fun register(
        modulePackageName: String,
        uid: Int,
        pid: Int,
        processName: String,
        versionCode: Long,
        hotReloadable: Boolean,
    ): Long {
        val id = nextId.getAndIncrement()
        targets[id] = HotReloadTarget(id, modulePackageName, processName, uid, pid, versionCode, hotReloadable)
        return id
    }

    /** Drops every target in [ids]; used when the process that owned them has died. */
    @JvmStatic
    fun removeAll(ids: Collection<Long>) {
        ids.forEach { targets.remove(it) }
    }

    /**
     * Whether [userId]'s copy of the module may address [target]. One module is one package and one
     * APK, but the copies installed for two users are two apps with two uids, and neither has any
     * business reloading the other's processes. The carve-out is for the AID_* uids below
     * [Process.FIRST_APPLICATION_UID]: system_server is one process for the whole device, and
     * every user holding the module is equally entitled to the one generation loaded there.
     */
    private fun addressableBy(target: HotReloadTarget, userId: Int): Boolean =
        target.uid < Process.FIRST_APPLICATION_UID ||
            target.uid / PackageService.PER_USER_RANGE == userId

    // RELOADING and FAILED describe the last attempt and outrank a version comparison.
    private fun reportedState(target: HotReloadTarget, installedVersion: Long?): Int {
        val state = target.state.get()
        if (state != HookedProcess.TARGET_STATE_UP_TO_DATE) return state
        // Zero means unknown, not old; claiming STALE would never be satisfiable by a reload.
        if (target.loadedVersionCode == 0L) return state
        return if (installedVersion != null && installedVersion != target.loadedVersionCode) {
            HookedProcess.TARGET_STATE_STALE
        } else {
            state
        }
    }

    /**
     * Not filtered to hot-reloadable targets: the AIDL documents this as hooked processes, and one
     * that cannot be reloaded answers UNSUPPORTED rather than disappearing.
     */
    @JvmStatic
    fun targetsFor(modulePackageName: String, userId: Int, installedVersion: Long?): List<HookedProcess> =
        targets.values
            .filter { it.modulePackageName == modulePackageName && addressableBy(it, userId) }
            .map { t ->
                HookedProcess().apply {
                    targetId = t.id
                    uid = t.uid
                    pid = t.pid
                    processName = t.processName
                    state = reportedState(t, installedVersion)
                    loadedVersionCode = t.loadedVersionCode
                }
            }

    @JvmStatic
    fun find(targetId: Long, modulePackageName: String, userId: Int): HotReloadTarget? {
        val target = targets[targetId] ?: return null
        if (target.modulePackageName != modulePackageName || !addressableBy(target, userId)) return null
        return target
    }

    @JvmStatic
    fun staleTargets(modulePackageName: String, installedVersion: Long?): List<HotReloadTarget> {
        if (installedVersion == null || installedVersion == 0L) return emptyList()
        return targets.values.filter {
            it.modulePackageName == modulePackageName &&
                it.hotReloadable &&
                it.loadedVersionCode != 0L &&
                it.loadedVersionCode != installedVersion
        }
    }

    /** Reloads are serialized per target, so check and transition must be one atomic step. */
    @JvmStatic
    fun begin(target: HotReloadTarget): Boolean {
        while (true) {
            val current = target.state.get()
            if (current == HookedProcess.TARGET_STATE_RELOADING) return false
            if (target.state.compareAndSet(current, HookedProcess.TARGET_STATE_RELOADING)) return true
        }
    }

    @JvmStatic
    fun end(target: HotReloadTarget, state: Int, loadedVersionCode: Long?) {
        if (loadedVersionCode != null) target.loadedVersionCode = loadedVersionCode
        target.state.set(state)
    }

    /**
     * system_server records its targets before the daemon's module cache exists, so they start
     * without a version. Fill those in from the cache whenever it is refreshed.
     */
    @JvmStatic
    fun backfillLoadedVersions(lookup: (String) -> Long?) {
        for (target in targets.values) {
            if (target.loadedVersionCode != 0L) continue
            val version = lookup(target.modulePackageName)
            if (version != null && version != 0L) target.loadedVersionCode = version
        }
    }
}
