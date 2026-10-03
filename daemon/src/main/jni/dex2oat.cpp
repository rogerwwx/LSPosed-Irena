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
 * Copyright (C) 2023 LSPosed Contributors
 */

#include <fcntl.h>
#include <jni.h>
#include <string>
#include <sys/mount.h>
#include <sys/system_properties.h>
#include <sys/wait.h>
#include <unistd.h>
#include <sched.h>
#include <sys/stat.h>
#include <cerrno>
#include <cstring>
#include <limits.h>

#include "logging.h"

namespace {
// Keep reporting in the parent: the fork child must not enter JNI or logging locks.
struct MountFailure { int stage; int target; int error; };
[[noreturn]] void reportMountFailure(int fd, int stage, int target) {
    MountFailure failure{stage, target, errno};
    ssize_t written;
    do { written = write(fd, &failure, sizeof(failure)); } while (written < 0 && errno == EINTR);
    _exit(1);
}
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_org_lsposed_lspd_service_Dex2OatService_doMountNative(JNIEnv *env, jobject,
                                                           jboolean enabled,
                                                           jstring r32, jstring d32,
                                                           jstring r64, jstring d64) {
    // Copy JNI strings before fork; the child must not enter the Java runtime.
    jstring arguments[] = {r32, d32, r64, d64};
    char targets[4][PATH_MAX]{};
    for (unsigned i = 0; i < 4; ++i) {
        if (!arguments[i]) continue;
        const char *text = env->GetStringUTFChars(arguments[i], nullptr);
        if (!text) return false;
        size_t length = strlen(text);
        if (length < sizeof(targets[i])) memcpy(targets[i], text, length + 1);
        env->ReleaseStringUTFChars(arguments[i], text);
        if (length >= sizeof(targets[i])) return false;
    }
    const char *wrappers[] = {"/data/adb/modules/zygisk_lsposed/bin/dex2oat32",
                              "/data/adb/modules/zygisk_lsposed/bin/dex2oat64"};
    int report[2];
    if (pipe2(report, O_CLOEXEC)) { PLOGE("create mount helper report pipe"); return false; }
    pid_t child = fork();
    if (child < 0) {
        int error = errno;
        close(report[0]); close(report[1]);
        errno = error;
        PLOGE("fork dex2oat mount helper"); return false;
    }
    if (child == 0) {
        close(report[0]);
        int ns = open("/proc/1/ns/mnt", O_RDONLY | O_CLOEXEC);
        if (ns < 0) reportMountFailure(report[1], 1, -1);
        if (setns(ns, CLONE_NEWNS)) reportMountFailure(report[1], 2, -1);
        close(ns);
        for (unsigned i = 0; i < 4; ++i) {
            if (!targets[i][0]) continue;
            const char *wrapper = wrappers[i / 2];
            if (enabled) {
                if (mount(wrapper, targets[i], nullptr, MS_BIND, nullptr)) reportMountFailure(report[1], 3, i);
                if (mount(nullptr, targets[i], nullptr, MS_BIND | MS_REMOUNT | MS_RDONLY, nullptr))
                    reportMountFailure(report[1], 4, i);
            } else {
                struct stat source{}, target{};
                if (!stat(wrapper, &source) && !stat(targets[i], &target) &&
                    source.st_dev == target.st_dev && source.st_ino == target.st_ino &&
                    umount2(targets[i], MNT_DETACH)) reportMountFailure(report[1], 5, i);
            }
        }
        if (enabled) {
            // On a fresh boot dalvik.vm.dex2oat-flags is not set; resetprop
            // --delete then exits 1 ("not found") and the helper would report
            // a false failure even though the mount syscalls all completed.
            if (!__system_property_find("dalvik.vm.dex2oat-flags")) _exit(0);
            execlp("resetprop", "resetprop", "--delete", "dalvik.vm.dex2oat-flags", nullptr);
        } else {
            execlp("resetprop", "resetprop", "dalvik.vm.dex2oat-flags", "--inline-max-code-units=0", nullptr);
        }
        reportMountFailure(report[1], 6, -1);
    }
    close(report[1]);
    int status;
    pid_t waited;
    do { waited = waitpid(child, &status, 0); } while (waited < 0 && errno == EINTR);
    MountFailure failure{};
    ssize_t bytes;
    do { bytes = read(report[0], &failure, sizeof(failure)); } while (bytes < 0 && errno == EINTR);
    close(report[0]);
    if (waited != child || !WIFEXITED(status) || WEXITSTATUS(status) != 0) {
        if (bytes == static_cast<ssize_t>(sizeof(failure))) {
            const char *stages[] = {"unknown", "open mount namespace", "setns", "bind mount",
                                    "remount read-only", "unmount", "exec resetprop"};
            const char *stage = failure.stage >= 1 && failure.stage <= 6 ? stages[failure.stage] : stages[0];
            const char *target = failure.target >= 0 && failure.target < 4 ? targets[failure.target] : "-";
            LOGE("dex2oat helper failed: stage=%s target=%s errno=%d (%s)", stage, target,
                 failure.error, strerror(failure.error));
        } else if (waited == child && WIFEXITED(status)) {
            LOGE("dex2oat helper resetprop exited with code %d (mount syscalls completed)", WEXITSTATUS(status));
        } else {
            LOGE("dex2oat helper terminated before reporting completion");
        }
        return false;
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_lsposed_lspd_service_Dex2OatService_unmountStaleArtDWrappers(JNIEnv *, jclass) {
    pid_t child = fork();
    if (child < 0) return false;
    if (child == 0) {
        int ns = open("/proc/1/ns/mnt", O_RDONLY | O_CLOEXEC);
        if (ns < 0 || setns(ns, CLONE_NEWNS)) _exit(1);
        close(ns);
        const char *targets[] = {
            "/apex/com.android.art/bin/dex2oat32", "/apex/com.android.art/bin/dex2oatd32",
            "/apex/com.android.art/bin/dex2oat64", "/apex/com.android.art/bin/dex2oatd64"
        };
        bool ok = true;
        for (unsigned i = 0; i < 4; ++i) {
            struct stat target{}, wrapper{};
            const char *path = i < 2 ? "/data/adb/modules/zygisk_lsposed/bin/dex2oat32"
                                     : "/data/adb/modules/zygisk_lsposed/bin/dex2oat64";
            if (!stat(targets[i], &target) && !stat(path, &wrapper) &&
                target.st_dev == wrapper.st_dev && target.st_ino == wrapper.st_ino) {
                if (umount2(targets[i], MNT_DETACH)) ok = false;
            }
        }
        _exit(ok ? 0 : 1);
    }
    int status;
    pid_t waited;
    do { waited = waitpid(child, &status, 0); } while (waited < 0 && errno == EINTR);
    return waited == child && WIFEXITED(status) && WEXITSTATUS(status) == 0;
}

static int setsockcreatecon_raw(const char *context) {
    std::string path = "/proc/self/task/" + std::to_string(gettid()) + "/attr/sockcreate";
    int fd = open(path.c_str(), O_RDWR | O_CLOEXEC);
    if (fd < 0) return -1;
    int ret;
    if (context) {
        do {
            ret = write(fd, context, strlen(context) + 1);
        } while (ret < 0 && errno == EINTR);
    } else {
        do {
            ret = write(fd, nullptr, 0); // clear
        } while (ret < 0 && errno == EINTR);
    }
    close(fd);
    return ret < 0 ? -1 : 0;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_org_lsposed_lspd_service_Dex2OatService_setSockCreateContext(JNIEnv *env, jclass,
                                                                  jstring contextStr) {
    if (contextStr == nullptr) return setsockcreatecon_raw(nullptr) == 0;
    const char *context = env->GetStringUTFChars(contextStr, nullptr);
    if (context == nullptr) return false;
    int ret = setsockcreatecon_raw(context);
    env->ReleaseStringUTFChars(contextStr, context);
    return ret == 0;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_org_lsposed_lspd_service_Dex2OatService_getSockPath(JNIEnv *env, jobject) {
    return env->NewStringUTF("5291374ceda0aef7c5d86cd2a4f6a3ac\0");
}
