package org.lsposed.lspd.service;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.util.Arrays;

public final class ArtDReceiptTest {
    private static int checks;

    private static void check(boolean value, String message) {
        ++checks;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        byte[] receipt = {1, 0, 1, 2, 3};
        check(ArtDReceipt.readPid(0, new ByteArrayInputStream(receipt)) == 0x10203,
                "按网络字节序读取完整 PID");
        check(ArtDReceipt.readPid(0, new ByteArrayInputStream(new byte[]{1, 127, -1, -1, -1}))
                == Integer.MAX_VALUE, "事件入队后 artd 可能已退出，不能再次依赖进程存活");
        check(ArtDReceipt.readPid(1000, new InputStream() {
            @Override public int read() { throw new AssertionError("不能读取非 root 客户端的数据"); }
        }) == -1, "先校验转发者身份");
        check(ArtDReceipt.readPid(0, new ByteArrayInputStream(new byte[]{2})) == -1,
                "拒绝未知版本");
        check(ArtDReceipt.readPid(0, new ByteArrayInputStream(new byte[]{1, 0, 0, 0, 0})) == -1,
                "拒绝 PID 0");
        check(ArtDReceipt.readPid(0, new ByteArrayInputStream(new byte[]{1, -1, -1, -1, -1})) == -1,
                "拒绝负 PID");
        for (int length = 0; length < receipt.length; ++length) {
            try {
                ArtDReceipt.readPid(0, new ByteArrayInputStream(Arrays.copyOf(receipt, length)));
                throw new AssertionError("截断回执不能成功");
            } catch (EOFException expected) {
                ++checks;
            }
        }
        System.out.println("ArtDReceipt: " + checks + " checks passed");
    }
}
