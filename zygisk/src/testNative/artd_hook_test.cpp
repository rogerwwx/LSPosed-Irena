#include <cassert>
#include <cstring>
#include <cstdio>
#include <thread>
#include "../main/jni/src/artd_injection.cpp"
extern "C" int __system_property_get(const char *, char *value) { *value = 0; return 0; }
extern "C" int __android_log_write(int, const char *, const char *) { return 0; }
static int calls;
static int testFd;
static char *const *expectedEnv;
static int FakeExecv(const char *path, char *const argv[]) {
    ++calls;
    if (calls == 1) {
        assert(lspd::artd::Prefix(path, "/proc/self/fd/"));
        assert(!(fcntl(testFd, F_GETFD) & FD_CLOEXEC));
        assert(!strcmp(argv[1], "/apex/com.android.art/bin/dex2oat64"));
    } else {
        assert(!strcmp(path, "/apex/com.android.art/bin/dex2oat64"));
        assert(fcntl(testFd, F_GETFD) & FD_CLOEXEC);
        assert(!strcmp(argv[0], "dex2oat64"));
    }
    errno = ENOENT;
    return -1;
}
static int FakeExecve(const char *path, char *const argv[], char *const env[]) {
    assert(env == expectedEnv);
    return FakeExecv(path, argv);
}
static int spawnCalls;
static int FakeSpawn(pid_t *, const char *path, const posix_spawn_file_actions_t *,
                     const posix_spawnattr_t *, char *const argv[], char *const env[]) {
    ++spawnCalls;
    assert(env == expectedEnv);
    if (spawnCalls == 1) {
        assert(lspd::artd::Prefix(path, "/proc/self/fd/"));
        assert(!(fcntl(testFd, F_GETFD) & FD_CLOEXEC));
        assert(!strcmp(argv[1], "/apex/com.android.art/bin/dex2oat64"));
        return ENOENT;  // Simulate a wrapper that cannot start, so the retry runs.
    }
    if (spawnCalls == 2) {
        assert(!strcmp(path, "/apex/com.android.art/bin/dex2oat64"));
        assert(fcntl(testFd, F_GETFD) & FD_CLOEXEC);
        assert(!strcmp(argv[0], "dex2oat64"));
        return 0;
    }
    // Not a stock compiler: the spawn is passed through untouched.
    assert(!strcmp(path, "/system/bin/true"));
    assert(fcntl(testFd, F_GETFD) & FD_CLOEXEC);
    return ENOENT;
}
static void TestReceipt(int ack, bool expected) {
    int sockets[2];
    assert(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, sockets) == 0);
    BoundSocket(sockets[0]);
    BoundSocket(sockets[1]);
    std::thread lspd([&] {
        unsigned char message[5]{};
        assert(recv(sockets[1], message, sizeof(message), MSG_WAITALL) == sizeof(message));
        const unsigned char expectedMessage[] = {1, 0x01, 0x02, 0x03, 0x04};
        assert(!memcmp(message, expectedMessage, sizeof(message)));
        if (ack >= 0) {
            auto response = static_cast<unsigned char>(ack);
            assert(send(sockets[1], &response, 1, MSG_NOSIGNAL) == 1);
        }
        close(sockets[1]);
    });
    assert(SendReceipt(sockets[0], 0x01020304) == expected);
    close(sockets[0]);
    lspd.join();
}
int main() {
    TestReceipt(1, true);
    TestReceipt(0, false);
    TestReceipt(2, false);
    TestReceipt(-1, false);
    testFd = open("/dev/null", O_RDONLY | O_CLOEXEC);
    assert(testFd >= 0);
    wrappers[1] = testFd;
    enabled = true;
    originalExecv = FakeExecv;
    originalExecve = FakeExecve;
    char arg0[] = "dex2oat64", arg1[] = "--zip-fd=10", env0[] = "A=B";
    char *args[] = {arg0, arg1, nullptr};
    char *env[] = {env0, nullptr};
    expectedEnv = env;
    assert(HookExecv("/apex/com.android.art/bin/dex2oat64", args) == -1);
    assert(calls == 2 && errno == ENOENT && (fcntl(testFd, F_GETFD) & FD_CLOEXEC));
    calls = 0;
    assert(HookExecve("/apex/com.android.art/bin/dex2oat64", args, env) == -1);
    assert(calls == 2 && errno == ENOENT);
    originalSpawn = FakeSpawn;
    pid_t child = -1;
    assert(HookPosixSpawn(&child, "/apex/com.android.art/bin/dex2oat64", nullptr, nullptr,
                          args, env) == 0);
    assert(spawnCalls == 2 && (fcntl(testFd, F_GETFD) & FD_CLOEXEC));
    assert(HookPosixSpawn(&child, "/system/bin/true", nullptr, nullptr, args, env) == ENOENT);
    assert(spawnCalls == 3);
    close(testFd);
    puts("artd receipt protocol, exec/spawn failure fallback, env and CLOEXEC tests passed");
}
