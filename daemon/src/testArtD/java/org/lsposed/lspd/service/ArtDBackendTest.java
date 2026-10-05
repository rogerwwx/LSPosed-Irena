package org.lsposed.lspd.service;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ArtDBackendTest {
    private static int checks;

    private static void check(boolean value, String message) {
        ++checks;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        var mounts = new AtomicInteger();
        var unmounts = new AtomicInteger();
        var backend = new ArtDBackend();
        backend.activateFallback(mounts::incrementAndGet);
        check(backend.isLegacy() && mounts.get() == 1, "超时启用回退");
        backend.acknowledge();
        check(backend.tryUseInjection(() -> { unmounts.incrementAndGet(); return true; }),
                "晚到的 Hook 回执必须能够恢复注入后端");
        check(!backend.isLegacy() && unmounts.get() == 1, "成功卸载后才退出回退");
        backend.activateFallback(mounts::incrementAndGet);
        check(mounts.get() == 1, "保存的回执不能被后续超时覆盖");

        backend = new ArtDBackend();
        backend.acknowledge();
        backend.activateFallback(() -> { throw new AssertionError("已有回执却仍挂载"); });
        check(backend.tryUseInjection(() -> { throw new AssertionError("未回退却调用卸载"); }),
                "先到回执直接选择注入");
        check(backend.hasReceipt(), "companion 或空闲 artd 退出不应清除成功记录");

        backend = new ArtDBackend();
        check(!backend.tryUseInjection(() -> { throw new AssertionError("没有回执却卸载"); }),
                "无回执不能恢复");
        backend.activateFallback(mounts::incrementAndGet);
        backend.activateFallback(() -> { throw new AssertionError("重复叠加 bind mount"); });
        backend.acknowledge();
        check(!backend.tryUseInjection(() -> false) && backend.isLegacy(), "卸载失败保留回退状态");
        check(backend.tryUseInjection(() -> true) && !backend.isLegacy(), "失败后允许再次恢复");

        backend = new ArtDBackend();
        backend.activateFallback(() -> {});
        var cleanups = new AtomicInteger();
        backend.stop(cleanups::incrementAndGet);
        backend.acknowledge();
        check(cleanups.get() == 1, "broker 停止时清理已启用的回退");
        check(!backend.tryUseInjection(() -> { throw new AssertionError("broker 已停止却恢复"); }),
                "停止后回执不能重新启用后端");
        backend.activateFallback(() -> { throw new AssertionError("停止后仍启用回退"); });

        backend = new ArtDBackend();
        backend.preparationFailed(cleanups::incrementAndGet);
        check(backend.isLegacy() && backend.isStopped() && cleanups.get() == 2,
                "准备失败进入属性回退并停止切换");

        // 模拟回退 helper 尚未返回时 request-6 到达，接收线程必须立即完成。
        var concurrent = new ArtDBackend();
        var mounting = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var acknowledged = new CountDownLatch(1);
        var worker = new Thread(() -> concurrent.activateFallback(() -> {
            mounting.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("回退线程超时");
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }));
        worker.start();
        check(mounting.await(5, TimeUnit.SECONDS), "回退 helper 已启动");
        var receiver = new Thread(() -> { concurrent.acknowledge(); acknowledged.countDown(); });
        receiver.start();
        try {
            check(acknowledged.await(1, TimeUnit.SECONDS), "接收回执不能被 mount 锁阻塞");
        } finally {
            release.countDown();
            worker.join(5000);
            receiver.join(5000);
        }
        check(concurrent.tryUseInjection(() -> true) && !concurrent.isLegacy(),
                "与挂载并发的回执也必须触发恢复");
        System.out.println("ArtDBackend: " + checks + " checks passed");
    }
}
