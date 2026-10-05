package org.lsposed.lspd.service;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/** 解码 root companion 转发的事件；artd 身份已在接收 request-6 时校验。 */
final class ArtDReceipt {
    private ArtDReceipt() {}

    static int readPid(int relayUid, InputStream stream) throws IOException {
        if (relayUid != 0) return -1;
        var input = new DataInputStream(stream);
        if (input.readUnsignedByte() != 1) return -1;
        int pid = input.readInt();
        return pid > 0 ? pid : -1;
    }
}
