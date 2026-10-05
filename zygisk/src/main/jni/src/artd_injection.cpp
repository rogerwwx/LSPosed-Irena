#include "artd_exec.h"
#include "zygisk_next_api.h"
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <link.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <sys/time.h>
#include <sys/xattr.h>
#include <sys/un.h>
#include <signal.h>
#include <unistd.h>

namespace {
using namespace lspd::artd;
int wrappers[2] = {-1, -1};
int (*originalExecv)(const char *, char *const []) = nullptr;
int (*originalExecve)(const char *, char *const [], char *const []) = nullptr;
bool enabled = false;
constexpr const char *kReceipt = "/data/adb/lspd/artd_receipt";
void Log(const char *message) {
#ifndef LOG_DISABLED
    __android_log_write(ANDROID_LOG_INFO, "LSPosedDex2Oat", message);
#else
    (void)message;
#endif
}

int Execute(const char *path, char *const argv[], char *const env[], bool explicitEnv) {
    Rewrite changed{};
    bool rewrite = __atomic_load_n(&enabled, __ATOMIC_ACQUIRE) && Build(path, argv, wrappers, changed);
    int flags = rewrite ? fcntl(changed.fd, F_GETFD) : -1;
    rewrite = rewrite && flags >= 0 && fcntl(changed.fd, F_SETFD, flags & ~FD_CLOEXEC) == 0;
    int result = explicitEnv
        ? originalExecve(rewrite ? changed.executable : path, rewrite ? changed.args : argv, env)
        : originalExecv(rewrite ? changed.executable : path, rewrite ? changed.args : argv);
    int error = errno;
    if (rewrite) {
        fcntl(changed.fd, F_SETFD, flags);
        // A returning exec has not started the wrapper: retry the original compile attempt.
        return explicitEnv ? originalExecve(path, argv, env) : originalExecv(path, argv);
    }
    errno = error;
    return result;
}
int HookExecv(const char *path, char *const argv[]) { return Execute(path, argv, nullptr, false); }
int HookExecve(const char *path, char *const argv[], char *const env[]) { return Execute(path, argv, env, true); }
int FindArt(dl_phdr_info *info, size_t, void *result) {
    if (!Equal(Base(info->dlpi_name), "libart.so") && !Equal(Base(info->dlpi_name), "libartd.so")) return 0;
    *static_cast<void **>(result) = reinterpret_cast<void *>(info->dlpi_addr);
    return 1;
}
void BoundSocket(int fd) {
    timeval timeout{1, 0};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
}
bool ReceiveWrappers(int fd) {
    unsigned char request = 5, mask = 0;
    if (send(fd, &request, 1, MSG_NOSIGNAL) != 1 || recv(fd, &mask, 1, MSG_WAITALL) != 1) return false;
    union { cmsghdr align; char bytes[CMSG_SPACE(2 * sizeof(int))]; } control{};
    unsigned char count = 0;
    iovec io{&count, 1};
    msghdr msg{};
    msg.msg_iov = &io; msg.msg_iovlen = 1;
    msg.msg_control = control.bytes; msg.msg_controllen = sizeof(control.bytes);
    int received[2] = {-1, -1};
    ssize_t n = recvmsg(fd, &msg, MSG_CMSG_CLOEXEC);
    if (n < 0) return false;
    size_t total = 0;
    bool valid = n == 1 && !(msg.msg_flags & MSG_CTRUNC) && (mask & ~3) == 0;
    for (cmsghdr *c = CMSG_FIRSTHDR(&msg); c; c = CMSG_NXTHDR(&msg, c)) {
        if (c->cmsg_level != SOL_SOCKET || c->cmsg_type != SCM_RIGHTS || c->cmsg_len < CMSG_LEN(0)) { valid = false; continue; }
        size_t bytes = c->cmsg_len - CMSG_LEN(0);
        if (bytes % sizeof(int)) valid = false;
        size_t nfds = bytes / sizeof(int);
        auto *fds = reinterpret_cast<int *>(CMSG_DATA(c));
        for (size_t i = 0; i < nfds; ++i) {
            if (total < 2) received[total++] = fds[i];
            else { close(fds[i]); valid = false; }
        }
    }
    size_t expected = !!(mask & 1) + !!(mask & 2);
    valid = valid && total == expected && count == expected && total != 0;
    if (!valid) { for (int f : received) if (f >= 0) close(f); return false; }
    size_t next = 0;
    if (mask & 1) wrappers[1] = received[next++];
    if (mask & 2) wrappers[0] = received[next];
    return true;
}
void Loaded(void *handle, const ZygiskNextAPI *api) {
    Log("A17 ZN module loaded; checking artd injection");
    char sdk[PROP_VALUE_MAX]{};
    __system_property_get("ro.build.version.sdk", sdk);
    unsigned version = 0;
    for (const char *p = sdk; *p >= '0' && *p <= '9'; ++p) version = version * 10 + (*p - '0');
    if (version < 37 || !api || !api->pltHook || !api->connectCompanion) {
        Log("A17 injection disabled: SDK or ZN API unavailable"); return;
    }
    char exe[256]{};
    ssize_t n = readlink("/proc/self/exe", exe, sizeof(exe) - 1);
    if (n <= 0 || !Equal(Base(exe), "artd")) { Log("A17 injection skipped: executable is not artd"); return; }
    void *base = nullptr;
    dl_iterate_phdr(FindArt, &base);
    if (!base) { Log("A17 injection failed: libart/libartd not loaded"); return; }
    int fd = api->connectCompanion(handle);
    if (fd < 0) { Log("A17 injection failed: cannot connect companion"); return; }
    BoundSocket(fd);
    bool ready = ReceiveWrappers(fd);
    close(fd);
    if (!ready) { Log("A17 injection failed: no valid wrapper FD (check labels/ABI)"); return; }
    bool v = api->pltHook(base, "execv", reinterpret_cast<void *>(HookExecv), reinterpret_cast<void **>(&originalExecv)) == ZN_SUCCESS;
    bool ve = api->pltHook(base, "execve", reinterpret_cast<void *>(HookExecve), reinterpret_cast<void **>(&originalExecve)) == ZN_SUCCESS;
    if (v && ve && originalExecv && originalExecve) {
        __atomic_store_n(&enabled, true, __ATOMIC_RELEASE);
        Log("A17 artd exec hooks ready");
        int status = api->connectCompanion(handle);
        if (status >= 0) {
            BoundSocket(status);
            unsigned char request = 6, ack = 0;
            if (send(status, &request, 1, MSG_NOSIGNAL) != 1 || recv(status, &ack, 1, MSG_WAITALL) != 1 || ack != 1)
                Log("A17 injection ready but companion status acknowledgement failed");
            close(status);
        } else Log("A17 injection ready but status connection failed");
    } else Log("A17 injection failed: execv/execve PLT hook installation failed");
}
bool ArtDAlive(int pid) {
    if (pid <= 0 || kill(pid, 0)) return false;
    char path[kMaxText]{}, exe[256]{};
    size_t n = 0;
    Append(path, n, "/proc/"); Number(path, n, pid); Append(path, n, "/exe");
    return readlink(path, exe, sizeof(exe) - 1) > 0 && Equal(Base(exe), "artd");
}
bool SendReceipt(int fd, int pid) {
    // 固定长度、网络字节序，与 lspd 的 DataInputStream.readInt 对应。
    unsigned char receipt[] = {1, static_cast<unsigned char>(pid >> 24),
                               static_cast<unsigned char>(pid >> 16),
                               static_cast<unsigned char>(pid >> 8), static_cast<unsigned char>(pid)};
    size_t sent = 0;
    while (sent < sizeof(receipt)) {
        ssize_t n = send(fd, receipt + sent, sizeof(receipt) - sent, MSG_NOSIGNAL);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        sent += static_cast<size_t>(n);
    }
    unsigned char ack = 0;
    ssize_t received;
    do { received = recv(fd, &ack, 1, MSG_WAITALL); } while (received < 0 && errno == EINTR);
    return received == 1 && ack == 1;
}
bool ForwardReceipt(int pid) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return false;
    BoundSocket(fd);
    sockaddr_un address{};
    address.sun_family = AF_UNIX;
    size_t n = 0;
    while (kReceipt[n]) { address.sun_path[n] = kReceipt[n]; ++n; }
    bool received = connect(fd, reinterpret_cast<sockaddr *>(&address), sizeof(address)) == 0
                    && SendReceipt(fd, pid);
    close(fd);
    if (!received) Log("A17 companion could not deliver Hook receipt to lspd");
    return received;
}
void Connected(int fd) {
    BoundSocket(fd);
    unsigned char request = 0;
    if (recv(fd, &request, 1, MSG_WAITALL) != 1) { close(fd); return; }
    if (request == 6) {
        ucred peer{};
        socklen_t size = sizeof(peer);
        unsigned char ack = 0;
        if (!getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &peer, &size) && ArtDAlive(peer.pid)) {
            // 成功记录由 lspd 持有，不随 companion 或空闲 artd 退出而丢失。
            ack = ForwardReceipt(peer.pid) ? 1 : 0;
        }
        send(fd, &ack, 1, MSG_NOSIGNAL); close(fd); return;
    }
    if (request != 5) { close(fd); return; }
    int fds[2];
    unsigned char count = 0, mask = 0;
    const char *paths[] = {"/data/adb/modules/zygisk_lsposed/bin/dex2oat64", "/data/adb/modules/zygisk_lsposed/bin/dex2oat32"};
    for (unsigned i = 0; i < 2; ++i) {
        int f = open(paths[i], O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
        struct stat st{};
        unsigned char elf[5]{};
        char context[64]{};
        ssize_t contextSize = f >= 0 ? fgetxattr(f, "security.selinux", context, sizeof(context) - 1) : -1;
        if (f >= 0 && fstat(f, &st) == 0 && S_ISREG(st.st_mode) && st.st_uid == 0 && !(st.st_mode & 0022) &&
            pread(f, elf, sizeof(elf), 0) == sizeof(elf) && elf[0] == 0x7f && elf[1] == 'E' &&
            elf[2] == 'L' && elf[3] == 'F' && elf[4] == (i == 0 ? 2 : 1) &&
            contextSize > 0 && Equal(context, "u:object_r:dex2oat_exec:s0")) {
            fds[count++] = f; mask |= 1 << i;
        }
        else if (f >= 0) close(f);
    }
    if (send(fd, &mask, 1, MSG_NOSIGNAL) == 1 && count) {
        union { cmsghdr align; char bytes[CMSG_SPACE(2 * sizeof(int))]; } control{};
        iovec io{&count, 1};
        msghdr msg{};
        msg.msg_iov = &io; msg.msg_iovlen = 1;
        msg.msg_control = control.bytes; msg.msg_controllen = CMSG_SPACE(count * sizeof(int));
        cmsghdr *c = CMSG_FIRSTHDR(&msg);
        c->cmsg_level = SOL_SOCKET; c->cmsg_type = SCM_RIGHTS; c->cmsg_len = CMSG_LEN(count * sizeof(int));
        for (unsigned i = 0; i < count; ++i) reinterpret_cast<int *>(CMSG_DATA(c))[i] = fds[i];
        sendmsg(fd, &msg, MSG_NOSIGNAL);
    }
    for (unsigned i = 0; i < count; ++i) close(fds[i]);
    close(fd);
}
}
extern "C" {
__attribute__((visibility("default"))) ZygiskNextModule zn_module = {ZYGISK_NEXT_API_VERSION, Loaded};
__attribute__((visibility("default"))) ZygiskNextCompanionModule zn_companion_module = {ZYGISK_NEXT_API_VERSION, nullptr, Connected};
}
