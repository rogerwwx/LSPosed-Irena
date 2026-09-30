#include <cassert>
#include <chrono>
#include <csignal>
#include <iostream>
#include <thread>

// Include the production translation unit; only the unrelated Android hook/log APIs are stubbed.
#include HYOS_RUNTIME_SOURCE

using namespace lspd::native::hyos;
using namespace std::chrono_literals;

#ifdef LEGACY_RUNTIME_STATUS
// Adapter for compiling this fixture against revisions predating the shared read helper.
static bool ReadByte(int fd, char &value) { return read(fd, &value, 1) == 1; }
#endif

static unsigned connectionCount() {
#ifdef LEGACY_RUNTIME_STATUS
    return g_injection_status.load();
#else
    return g_registered_connections.load();
#endif
}

static void waitForConnections(unsigned expected) {
    const auto deadline = std::chrono::steady_clock::now() + 2s;
    while (connectionCount() != expected && std::chrono::steady_clock::now() < deadline) {
        std::this_thread::sleep_for(1ms);
    }
    assert(connectionCount() == expected);
}

static int connectRuntime() {
    int pair[2];
    assert(socketpair(AF_UNIX, SOCK_STREAM, 0, pair) == 0);
    assert(WriteAll(pair[0], std::string(1, '\1')));
    OnModuleConnected(pair[1]);
    timeval timeout{};
    socklen_t length = sizeof(timeout);
    assert(getsockopt(pair[1], SOL_SOCKET, SO_RCVTIMEO, &timeout, &length) == 0);
    // The registration timeout must not leak into the long-lived server.
    assert(timeout.tv_sec == 0 && timeout.tv_usec == 0);
    timeout.tv_sec = 1;
    assert(setsockopt(pair[0], SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) == 0);
    return pair[0];
}

static void requestAfterIdle(int fd) {
    assert(WriteAll(fd, "M"));
    // The test host has no daemon index. An empty, terminated reply is still a live connection.
    char value = 0;
    size_t size = 0;
    do {
        assert(ReadByte(fd, value));
        assert(++size <= kMaxReplyLength);
    } while (value != '\n');
}

static volatile sig_atomic_t signals = 0;
static void handleSignal(int) { signals = 1; }

int main() {
    assert(connectionCount() == 0);
    const int first = connectRuntime();
    waitForConnections(1);
    std::this_thread::sleep_for(2300ms);
    requestAfterIdle(first);
    std::cout << "PASS: request after more than two idle seconds\n";

    const int second = connectRuntime();
    waitForConnections(2);
    close(first);
    waitForConnections(1);
    requestAfterIdle(second);
    close(second);
    waitForConnections(0);
    std::cout << "PASS: overlapping spawners and status cleared after disconnect\n";

    int pair[2];
    assert(socketpair(AF_UNIX, SOCK_STREAM, 0, pair) == 0);
    assert(WriteAll(pair[0], std::string(1, '\0')));
    OnModuleConnected(pair[1]);
    char value;
    assert(read(pair[0], &value, 1) == 0);
    close(pair[0]);
    assert(connectionCount() == 0);
    std::cout << "PASS: failed registration closes without claiming activation\n";

    assert(socketpair(AF_UNIX, SOCK_STREAM, 0, pair) == 0);
    auto start = std::chrono::steady_clock::now();
    OnModuleConnected(pair[1]);
    assert(std::chrono::steady_clock::now() - start < 5s);
    assert(read(pair[0], &value, 1) == 0);
    close(pair[0]);
    assert(connectionCount() == 0);
    std::cout << "PASS: silent registration remains bounded\n";

    struct sigaction action{};
    action.sa_handler = handleSignal;
    sigemptyset(&action.sa_mask);
    sigaction(SIGPIPE, &action, nullptr);
    assert(socketpair(AF_UNIX, SOCK_STREAM, 0, pair) == 0);
    close(pair[1]);
    assert(!WriteAll(pair[0], "M"));
    assert(signals == 0);
    close(pair[0]);
    std::cout << "PASS: disconnected peer cannot raise SIGPIPE\n";

    sigaction(SIGUSR1, &action, nullptr);
    assert(socketpair(AF_UNIX, SOCK_STREAM, 0, pair) == 0);
    bool readSucceeded = false;
    std::thread reader([&] { readSucceeded = ReadByte(pair[1], value); });
    std::this_thread::sleep_for(30ms);
    assert(pthread_kill(reader.native_handle(), SIGUSR1) == 0);
    std::this_thread::sleep_for(30ms);
    assert(WriteAll(pair[0], "M"));
    reader.join();
    assert(signals != 0 && readSucceeded && value == 'M');
    close(pair[0]);
    close(pair[1]);
    std::cout << "PASS: interrupted protocol read retries\n";
}
