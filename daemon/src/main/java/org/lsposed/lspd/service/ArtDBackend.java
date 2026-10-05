package org.lsposed.lspd.service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** 保存本次 lspd 生命周期中的 Hook 回执，并串行执行后端切换。 */
final class ArtDBackend {
    private final AtomicBoolean receipt = new AtomicBoolean();
    private volatile boolean legacy;
    private volatile boolean stopped;

    void acknowledge() {
        // 回执线程不能等待 mount helper，否则会拖住 artd 的启动握手。
        receipt.set(true);
    }

    boolean hasReceipt() { return receipt.get(); }
    boolean isLegacy() { return legacy; }
    boolean isStopped() { return stopped; }

    synchronized void activateFallback(Runnable mount) {
        if (stopped || legacy || hasReceipt()) return;
        legacy = true;
        mount.run();
    }

    synchronized boolean tryUseInjection(BooleanSupplier unmount) {
        if (stopped || !hasReceipt()) return false;
        if (legacy && !unmount.getAsBoolean()) return false;
        legacy = false;
        return true;
    }

    synchronized void preparationFailed(Runnable cleanup) {
        legacy = true;
        stopped = true;
        cleanup.run();
    }

    synchronized void stop(Runnable cleanup) {
        stopped = true;
        if (legacy) cleanup.run();
    }
}
