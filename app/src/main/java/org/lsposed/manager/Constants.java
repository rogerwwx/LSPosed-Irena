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
 * Copyright (C) 2020 EdXposed Contributors
 * Copyright (C) 2021 LSPosed Contributors
 */

package org.lsposed.manager;

import android.os.IBinder;
import android.util.Log;

import org.lsposed.lspd.ILSPManagerService;
import org.lsposed.manager.receivers.LSPManagerServiceHolder;

public class Constants {
    private static final String TAG = "LSPosed-Manager";

    /**
     * What the daemon turned out to be, when it is not something this build can talk to.
     *
     * Null in the ordinary case, including "no daemon at all" - this is only ever set when a
     * binder did arrive and was then refused. That is a distinct situation from having no daemon
     * and has to be rendered as one: the binder is alive and the framework is plainly running, so
     * every screen would otherwise draw it as one that answers nothing.
     */
    private static volatile String peerMismatch = null;

    public static String getPeerMismatch() {
        return peerMismatch;
    }

    public static boolean setBinder(IBinder binder) {
        // This APK can be older or newer than the framework that pushed the binder: the manager
        // can be installed as an ordinary app, and an installed copy survives every later flash -
        // and flashing a new zip without rebooting leaves a running daemon that predates the
        // manager apk the next launch loads. Nothing about that mismatch is loud on its own:
        // Stub.asInterface wraps any binder in a proxy without checking, the binder stays alive so
        // isBinderAlive() keeps answering true, and every transaction then fails somewhere
        // plausible and wrong. So ask first.
        //
        // The descriptor question is exempt by construction: INTERFACE_TRANSACTION sits outside
        // the FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION band the generated dispatcher checks
        // the interface token for, so it is answered across any mismatch. It can still throw - the
        // call is remote and the daemon may have died between the push and here - and a throw is
        // not evidence of a mismatch, so it falls through to binding and lets linkToDeath report
        // the death.
        // The interface's fully qualified name is its binder descriptor. This literal is that
        // name; if the interface is ever renamed the descriptor changes with it and this check
        // must follow. (The AIDL-generated Stub keeps its DESCRIPTOR private, so the expected
        // value is spelled out here rather than read from it.)
        String theirDescriptor = null;
        try {
            theirDescriptor = binder.getInterfaceDescriptor();
        } catch (Throwable ignored) {
        }
        if (theirDescriptor != null && !"org.lsposed.lspd.ILSPManagerService".equals(theirDescriptor)) {
            Log.e(TAG, "the daemon speaks " + theirDescriptor + ", this manager speaks "
                    + "org.lsposed.lspd.ILSPManagerService; refusing to bind");
            peerMismatch = theirDescriptor;
            return false;
        }

        ILSPManagerService service = ILSPManagerService.Stub.asInterface(binder);

        // A matching descriptor means the two ends agree on what this interface is called, not on
        // what is in it: transaction ids follow declaration order, so a daemon built from a
        // different revision maps the same numbers to different methods. getProtocolVersion is
        // declared first and is therefore FIRST_CALL_TRANSACTION in every revision, so it is the
        // one question both ends are guaranteed to agree on. A daemon built before the handshake
        // runs its own transaction-1 method (getApi) under this number, and its string reply
        // misreads as some other int - refused for being the wrong version, which is the right
        // answer for a peer that cannot be trusted to route anything else correctly either. A
        // remote exception is not evidence of a mismatch - it falls through to binding and lets
        // linkToDeath report the death.
        Integer theirProtocol = null;
        try {
            theirProtocol = service.getProtocolVersion();
        } catch (Throwable ignored) {
        }
        if (theirProtocol != null && theirProtocol != ILSPManagerService.PROTOCOL_VERSION) {
            Log.e(TAG, "the daemon speaks protocol " + theirProtocol + ", this manager speaks "
                    + ILSPManagerService.PROTOCOL_VERSION + "; refusing to bind");
            peerMismatch = "protocol " + theirProtocol;
            return false;
        }

        peerMismatch = null;
        LSPManagerServiceHolder.init(binder);
        var held = LSPManagerServiceHolder.getService();
        return held != null && held.asBinder().isBinderAlive();
    }
}
