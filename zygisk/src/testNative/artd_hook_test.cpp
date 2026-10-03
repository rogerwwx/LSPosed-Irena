#include <cassert>
#include <cstring>
#include <cstdio>
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
int main() {
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
    close(testFd);
    puts("artd exec failure fallback, env and CLOEXEC tests passed");
}
