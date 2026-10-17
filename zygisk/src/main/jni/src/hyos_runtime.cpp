#include <dirent.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/types.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <map>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include "common/logging.h"
#include "core/native_api.h"

#include "zygisk_next_api.h"

/**
 * @file hyos_runtime.cpp
 * @brief Native hooking support for applications running on the HyperOS Rust Runtime.
 *
 * A process spawned by /system_ext/bin/hyos_spawner is not an ART process. It has no JVM, no
 * JNIEnv, and no binder of its own, so none of the machinery the rest of the framework is built on
 * is available inside it: the framework DEX cannot be loaded, and the daemon cannot be asked what
 * is in scope. What such a process does have is the dynamic loader, and therefore native libraries.
 *
 * This translation unit is the whole of the framework's presence there. It is compiled into the
 * same liblspd.so the Zygisk loader injects everywhere else, and it is reached through Zygisk
 * Next's Runtime API, which the module declares itself for in zn_modules.txt:
 *
 *   path=/system_ext/bin/hyos_spawner companion zygisk/arm64-v8a.so
 *
 * The flow, in order:
 *
 *   1. Zygisk Next injects this library into hyos_spawner and calls `zn_module`'s onModuleLoaded.
 *      getRuntime() reports ZN_RUNTIME_HYOS, and we register `zn_companion_module`'s callbacks.
 *   2. The companion -- a root process Zygisk Next forked for us, and the only reader of the
 *      daemon's index -- packs every published index into one table and serves it to the spawner.
 *      The spawner asks for the daemon's generation stamp before every fork and refetches the table
 *      only when it moved, so the copy every child inherits is current.
 *   3. The spawner forks an application process, applies its uid, gid, groups and SELinux context,
 *      and calls onAppSpecialized in the child.
 *   4. The child looks its own process name up in that table and loads the native libraries it
 *      names. It does no I/O at all: the answer is memory the fork already copied, not a file and
 *      not a socket. That is deliberate -- the callback runs after the child's SELinux context has
 *      been applied, so it is in the application's domain by then, where neither the companion's
 *      socket (sendto is refused between the two domains) nor the daemon's root-only directory is
 *      reachable. A process forked from a possibly multithreaded parent also must not reach for a
 *      lock some other thread held at fork time, and memory costs neither.
 *
 * The hook primitives come from Zygisk Next rather than from our own Dobby, for the reason
 * SetHookBackend documents: two engines patching the same address destroy each other's trampolines,
 * and the engine that already owns this process has the better claim.
 */

namespace lspd::native::hyos {

namespace {

// --- The published state, and where to find it ------------------------------------------------

/**
 * A fixed path the daemon writes the real location into, because there is nothing to list.
 *
 * /data/misc belongs to the system uid with mode 0771, so only root and the daemon can create
 * entries in it, and this file is mode 0600. That combination is deliberate: a process running as
 * an application cannot read it, so an application cannot learn the directory the index lives in
 * and therefore cannot ask whether it is being hooked. The companion is the only reader, and it
 * runs as root.
 */
constexpr auto kPointerPath = "/data/misc/lspd.hyos";

/// The prefix of the single line the pointer file carries.
constexpr std::string_view kPointerKey = "misc=";

/// Where the random daemon directory lives, for the fallback that has to go looking.
constexpr auto kMiscRootPath = "/data/misc";

/// The index directory the daemon creates inside its random directory, its marker and generation.
constexpr std::string_view kIndexDirName = "hyos";
constexpr std::string_view kIndexMarkerName = ".version";
constexpr std::string_view kIndexGenerationName = ".generation";
constexpr std::string_view kIndexMagic = "lspd-hyos 1";

/// The two things the companion can be asked for, and the whole of its protocol.
constexpr char kRequestGeneration = 'G';
constexpr char kRequestTable = 'T';

/// Refuse a table larger than this rather than believe a length field a confused peer sent.
constexpr uint32_t kMaxTableLength = 8u << 20;

/**
 * Where the companion reports whether this process's hyperos support is actually working.
 *
 * A client connects and reads one byte: `1` means the Zygisk Next Runtime API was registered and
 * applications will be specialized, anything else means the runtime did not want us. It is the
 * whole interface, and it exists because the daemon has no other way to ask: the daemon is a Java
 * process, and the connection the spawner holds is not one it can reach.
 *
 * The path is the same one LSPosed 2.2.0 uses, recovered from its released daemon -- its
 * `ILSPManagerService` transaction 67 connects here and reads exactly that byte, and its manager
 * turns the answer into the "HyperOS Runtime injection failed" notice the user sees. Being under
 * /data/adb/lspd, which is mode 0700 owned by root, is deliberate: the companion and the daemon are
 * both root, and nothing running as an application may ask.
 */
constexpr auto kMonitorPath = "/data/adb/lspd/hyos_monitor";

/// How long the companion waits for the spawner's status byte before assuming the worst.
constexpr time_t kStatusByteTimeoutSeconds = 2;

/**
 * How long either side waits on the companion connection.
 *
 * The spawner's fetch runs on the parent side of a fork, so a companion that has wedged must cost
 * a bounded delay rather than the launch itself: the reply is a table written by a process that is
 * already running, so anything above seconds means it is not coming.
 */
constexpr time_t kCompanionTimeoutSeconds = 2;

// --- The Zygisk Next view of this process -----------------------------------------------------

const ZygiskNextAPI *g_api = nullptr;

// The companion connection, established by the spawner before any fork. -1 when the companion could
// not be started, which costs everything: the status byte, and the table itself.
int g_companion_fd = -1;

// Whether the HyperOS Runtime API was actually registered here. Zygisk Next's Runtime API is an
// optional feature of that loader: an older build does not offer it, and one that does can still
// refuse the registration. None of that is this process's problem, so every such path turns the
// feature off rather than failing -- an ordinary Zygisk process and a hyos_spawner running without
// us are both perfectly good outcomes.
bool g_registered = false;

// Count live, successfully registered connections rather than latching a success byte forever.
// A replacement spawner may connect before the old one exits; losing the old connection must not
// clear the new one's status. The monitor and connection workers run on different threads.
std::atomic<unsigned> g_registered_connections{0};

// Whether this process has already done its work. A child is forked once and specializes once, but
// a runtime is free to call the callback again, and loading a module's libraries twice would run
// its native_init twice.
bool g_specialized = false;

// --- Reading files without assuming anything --------------------------------------------------

/// Reads a whole file, up to a sane bound. False when it cannot be read at all.
bool ReadFile(const std::string &path, std::string &out) {
    const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;

    out.clear();
    char buf[512];
    for (;;) {
        const ssize_t n = read(fd, buf, sizeof(buf));
        if (n < 0) {
            close(fd);
            out.clear();
            return false;
        }
        if (n == 0) break;
        out.append(buf, static_cast<size_t>(n));
        // Every file read here is a few hundred bytes of text; a larger one is not one of ours.
        if (out.size() > (1u << 20)) {
            close(fd);
            out.clear();
            return false;
        }
    }
    close(fd);
    return true;
}

/// Writes every byte, or reports that it could not.
bool WriteAll(int fd, const std::string &data) {
    size_t written = 0;
    while (written < data.size()) {
        const ssize_t n = send(fd, data.data() + written, data.size() - written, MSG_NOSIGNAL);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        written += static_cast<size_t>(n);
    }
    return true;
}

/// Reads exactly `length` bytes, or reports that it could not.
bool ReadAll(int fd, void *data, size_t length) {
    auto *out = static_cast<char *>(data);
    size_t read_so_far = 0;
    while (read_so_far < length) {
        const ssize_t n = read(fd, out + read_so_far, length - read_so_far);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        read_so_far += static_cast<size_t>(n);
    }
    return true;
}

/// Read a protocol byte, retrying interruptions without treating them as a disconnected peer.
bool ReadByte(int fd, char &value) {
    ssize_t n;
    do {
        n = read(fd, &value, 1);
    } while (n < 0 && errno == EINTR);
    return n == 1;
}

void AppendU32(std::string &out, uint32_t value) {
    out.append(reinterpret_cast<const char *>(&value), sizeof(value));
}

bool TakeU32(const std::string &in, size_t &pos, uint32_t &value) {
    if (pos + sizeof(value) > in.size()) return false;
    memcpy(&value, in.data() + pos, sizeof(value));
    pos += sizeof(value);
    return true;
}

/// The first line of `text`, without its terminator.
std::string FirstLine(const std::string &text) {
    const auto end = text.find('\n');
    std::string line = text.substr(0, end == std::string::npos ? text.size() : end);
    if (!line.empty() && line.back() == '\r') line.pop_back();
    return line;
}

/**
 * @brief Whether a directory really is the one the LSPosed daemon publishes into.
 *
 * The random name is the whole point of the directory, so finding one by looking means finding
 * somebody else's directory just as easily. The marker is what tells them apart.
 */
bool IsLspdMiscRoot(const std::string &root) {
    std::string content;
    const std::string marker =
        root + "/" + std::string(kIndexDirName) + "/" + std::string(kIndexMarkerName);
    if (!ReadFile(marker, content)) return false;
    return FirstLine(content) == kIndexMagic;
}

/**
 * @brief Finds the index location.
 *
 * The pointer file first, because it needs no directory listing at all. Failing that, look inside
 * /data/misc, where the daemon's directory is the only one carrying the marker. Both run in the
 * companion, which is root; an application process could do neither.
 */
std::string ResolveMiscRoot() {
    std::string pointer;
    if (ReadFile(kPointerPath, pointer)) {
        const std::string line = FirstLine(pointer);
        const std::string_view view{line};
        if (view.rfind(kPointerKey, 0) == 0) {
            std::string root{view.substr(kPointerKey.size())};
            if (!root.empty() && root.back() == '/') root.pop_back();
            if (IsLspdMiscRoot(root)) return root;
            LOGW("The published daemon directory '{}' carries no index marker.", root.c_str());
        } else {
            LOGW("{} does not name a daemon directory.", kPointerPath);
        }
    } else {
        LOGD("No published daemon directory at {}.", kPointerPath);
    }

    // Fallback: it is a random name under /data/misc, so the marker is the only way to recognise
    // it. Reached when the pointer file is missing, or names something that is not there.
    DIR *dir = opendir(kMiscRootPath);
    if (dir == nullptr) {
        LOGW("Cannot look for the daemon directory in {}: {}.", kMiscRootPath, strerror(errno));
        return {};
    }
    std::string found;
    while (struct dirent *entry = readdir(dir)) {
        if (entry->d_name[0] == '.') continue;
        std::string candidate = std::string(kMiscRootPath) + "/" + entry->d_name;
        if (IsLspdMiscRoot(candidate)) {
            found = std::move(candidate);
            break;
        }
    }
    closedir(dir);
    if (!found.empty()) LOGI("Found the daemon directory by marker: {}", found.c_str());
    return found;
}

// --- The published index ----------------------------------------------------------------------

/// One module native library to load into this process.
struct LibraryEntry {
    std::string module_package;
    std::string library_name;
    std::string library_path;
};

/// Splits one index line into its three tab-separated fields.
bool ParseIndexLine(const std::string &line, LibraryEntry &out) {
    const auto first = line.find('\t');
    if (first == std::string::npos) return false;
    const auto second = line.find('\t', first + 1);
    if (second == std::string::npos) return false;

    out.module_package = line.substr(0, first);
    out.library_name = line.substr(first + 1, second - first - 1);
    out.library_path = line.substr(second + 1);
    return !out.library_name.empty() && !out.library_path.empty();
}

/// Turns one published index into the libraries it names. The first line is the format's marker.
std::vector<LibraryEntry> ParseIndex(const std::string &content) {
    std::vector<LibraryEntry> entries;
    bool first = true;
    for (size_t start = 0; start < content.size();) {
        const auto end = content.find('\n', start);
        const std::string line =
            content.substr(start, end == std::string::npos ? std::string::npos : end - start);
        start = end == std::string::npos ? content.size() : end + 1;

        if (first) {
            first = false;
            if (line == kIndexMagic) continue;
            LOGE("LspdHyperRuntime: an index does not start with its marker; ignoring it.");
            return {};
        }
        if (line.empty() || line[0] == '#') continue;

        LibraryEntry entry;
        if (!ParseIndexLine(line, entry)) {
            LOGW("LspdHyperRuntime: skipping a malformed index line.");
            continue;
        }
        entries.push_back(std::move(entry));
    }
    return entries;
}

/**
 * @brief The daemon's generation stamp, which moves whenever the published set changes.
 *
 * A plain decimal number, written after every publish. Zero is the answer for a daemon that has
 * not published yet, or for one too old to write the file at all, and the spawner treats it as
 * "nothing to fetch" rather than as an empty scope set.
 */
uint64_t ReadGeneration(const std::string &misc_root) {
    const std::string dir = misc_root + "/" + std::string(kIndexDirName);
    std::string content;
    if (ReadFile(dir + "/" + std::string(kIndexGenerationName), content)) {
        const uint64_t value = strtoull(content.c_str(), nullptr, 10);
        if (value != 0) return value;
    }
    // A daemon too old to write the stamp still rewrites its marker on every publish, so the
    // marker's modification time is the next best answer, at one second's resolution.
    struct stat marker {};
    if (stat((dir + "/" + std::string(kIndexMarkerName)).c_str(), &marker) != 0) return 0;
    return static_cast<uint64_t>(marker.st_mtime);
}

/**
 * @brief Packs every published index into one blob.
 *
 * Sent as a whole rather than answered per name, because the spawner does not know which name it
 * will be asked about: the runtime picks the package after the fork, so the only safe moment to
 * learn the answer is before it. One transfer per generation, into memory the fork then shares.
 */
std::string BuildTable(const std::string &misc_root) {
    const std::string dir_path = misc_root + "/" + std::string(kIndexDirName);
    std::vector<std::pair<std::string, std::string>> records;

    DIR *dir = opendir(dir_path.c_str());
    if (dir == nullptr) {
        LOGW("LspdHyperRuntime: cannot list {}: {}.", dir_path.c_str(), strerror(errno));
        return {};
    }
    while (struct dirent *entry = readdir(dir)) {
        // The marker and the generation stamp are not scopes; nothing else starts with a dot.
        if (entry->d_name[0] == '.') continue;
        std::string content;
        const std::string path = dir_path + "/" + entry->d_name;
        if (ReadFile(path, content)) records.emplace_back(entry->d_name, std::move(content));
    }
    closedir(dir);

    std::string payload;
    AppendU32(payload, static_cast<uint32_t>(records.size()));
    for (const auto &record : records) {
        AppendU32(payload, static_cast<uint32_t>(record.first.size()));
        payload += record.first;
        AppendU32(payload, static_cast<uint32_t>(record.second.size()));
        payload += record.second;
    }
    return payload;
}

// --- The companion ----------------------------------------------------------------------------

/**
 * @brief Serves the spawner: a generation to compare against, or the table itself.
 *
 * One client only -- the spawner -- so the exchange needs no correlation and no care about replies
 * arriving out of order. Each request resolves the daemon directory again, which is a read of the
 * pointer file: the daemon may have restarted since the last one, and its directory has a new
 * random name each time.
 */
void *ServeCompanion(void *arg) {
    const int fd = static_cast<int>(reinterpret_cast<intptr_t>(arg));
    for (;;) {
        char request = 0;
        if (!ReadByte(fd, request)) break;

        const std::string misc_root = ResolveMiscRoot();
        if (request == kRequestGeneration) {
            const uint64_t generation = misc_root.empty() ? 0 : ReadGeneration(misc_root);
            if (!WriteAll(fd, std::string(reinterpret_cast<const char *>(&generation),
                                          sizeof(generation)))) {
                break;
            }
            continue;
        }
        if (request == kRequestTable) {
            const std::string payload = misc_root.empty() ? std::string() : BuildTable(misc_root);
            std::string framed;
            AppendU32(framed, static_cast<uint32_t>(payload.size()));
            framed += payload;
            if (!WriteAll(fd, framed)) break;
            LOGI("LspdHyperRuntime: published {} bytes to the spawner.", payload.size());
            continue;
        }
        LOGD("LspdHyperRuntime: ignoring request {}.", static_cast<int>(request));
    }
    close(fd);
    g_registered_connections.fetch_sub(1);
    return nullptr;
}

/**
 * @brief Answers the daemon's question about this runtime.
 *
 * Started with the companion rather than with the first connection, because "started and nothing
 * registered yet" has to be answerable: it is what a runtime the loader refused looks like, and the
 * difference between that and no companion at all is the whole reason the daemon asks.
 */
void *ServeMonitor(void *) {
    // A socket left behind by a companion that was killed would make every later bind fail, and the
    // daemon would keep reading the old inode instead of ours.
    unlink(kMonitorPath);

    const int listener = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (listener < 0) {
        LOGE("LspdHyperRuntime: cannot create {}: {}.", kMonitorPath, strerror(errno));
        return nullptr;
    }

    struct sockaddr_un address {};
    address.sun_family = AF_UNIX;
    strlcpy(address.sun_path, kMonitorPath, sizeof(address.sun_path));
    if (bind(listener, reinterpret_cast<struct sockaddr *>(&address), sizeof(address)) != 0 ||
        listen(listener, 8) != 0) {
        LOGE("LspdHyperRuntime: cannot listen on {}: {}.", kMonitorPath, strerror(errno));
        close(listener);
        return nullptr;
    }
    // The daemon runs as root and the directory is root-only, so the mode is not what admits it;
    // it is set anyway so that a stale socket is never what refuses a legitimate reader.
    if (chmod(kMonitorPath, 0666) != 0) {
        LOGW("LspdHyperRuntime: cannot relax {}: {}.", kMonitorPath, strerror(errno));
    }
    LOGI("LspdHyperRuntime: reporting injection status on {}.", kMonitorPath);

    for (;;) {
        const int client = accept(listener, nullptr, nullptr);
        if (client < 0) {
            if (errno == EINTR) continue;
            break;
        }
        const char status = g_registered_connections.load() != 0 ? 1 : 0;
        if (!WriteAll(client, std::string(1, status))) {
            LOGD("LspdHyperRuntime: a monitor client went away before its answer.");
        }
        close(client);
    }
    close(listener);
    return nullptr;
}

// --- The spawner ------------------------------------------------------------------------------

// What the spawner fetched, and which generation of the daemon's index it came from. Built before
// the fork, read after it: a process the runtime spawns looks its own name up in this and does no
// I/O at all.
std::map<std::string, std::vector<LibraryEntry>> g_index;
uint64_t g_generation = 0;
bool g_index_loaded = false;

/**
 * @brief Rebuilds the lookup the spawned processes will read, if the daemon has published since.
 *
 * Runs in the spawner, and on the parent side of the fork, because that is the last moment at which
 * this process may still use the connection: what it produces is memory, and memory is what the
 * fork copies. The generation stamp is asked for first and is eight bytes, so the ordinary case --
 * a fork with nothing republished since the last one -- costs one round trip and no payload.
 */
void RefreshIndex() {
    if (g_companion_fd < 0) return;

    const char request = kRequestGeneration;
    if (!WriteAll(g_companion_fd, std::string(1, request))) return;
    uint64_t generation = 0;
    if (!ReadAll(g_companion_fd, &generation, sizeof(generation))) return;
    if (generation == 0) {
        LOGD("LspdHyperRuntime: the daemon has published nothing yet.");
        return;
    }
    if (g_index_loaded && generation == g_generation) return;

    const char fetch = kRequestTable;
    if (!WriteAll(g_companion_fd, std::string(1, fetch))) return;
    uint32_t length = 0;
    if (!ReadAll(g_companion_fd, &length, sizeof(length)) || length > kMaxTableLength) {
        LOGW("LspdHyperRuntime: the companion sent no usable table.");
        return;
    }
    if (length == 0) {
        // A daemon that has published a generation but no directory yet; ask again next fork.
        LOGD("LspdHyperRuntime: the companion has no index to hand over.");
        return;
    }
    std::string payload(length, '\0');
    if (!ReadAll(g_companion_fd, payload.data(), length)) return;

    size_t pos = 0;
    uint32_t count = 0;
    if (!TakeU32(payload, pos, count)) return;
    std::map<std::string, std::vector<LibraryEntry>> parsed;
    for (uint32_t i = 0; i < count; i++) {
        uint32_t key_length = 0;
        if (!TakeU32(payload, pos, key_length) || pos + key_length > payload.size()) return;
        std::string key = payload.substr(pos, key_length);
        pos += key_length;

        uint32_t content_length = 0;
        if (!TakeU32(payload, pos, content_length) || pos + content_length > payload.size()) {
            return;
        }
        parsed[key] = ParseIndex(payload.substr(pos, content_length));
        pos += content_length;
    }

    g_index = std::move(parsed);
    g_generation = generation;
    g_index_loaded = true;
    LOGI("LspdHyperRuntime: holding scopes for {} process name(s).", g_index.size());
}

/// Runs in the parent, before the fork that creates the next application process.
void PrepareIndex() {
    // A specialized process is an application, not the spawner: its copy of the table is the one
    // the runtime's fork handed it, and it must never touch the connection -- the application's
    // SELinux domain cannot use it, and an application forking on its own would otherwise put
    // requests on a socket the spawner is using.
    if (g_specialized) return;
    if (g_registered) RefreshIndex();
}

/**
 * @brief Brings the native API, and every module library the daemon listed, into this process.
 */
void LoadModuleLibraries(const std::vector<LibraryEntry> &entries) {
    // The interception of the loader was installed by the spawner before it forked, so it is
    // already in place here and inherited. InstallNativeAPI is idempotent and reports that state.
    const bool intercepts_dlopen = InstallNativeAPI();
    if (!intercepts_dlopen) {
        LOGW("LspdHyperRuntime: the loader is not intercepted. Module libraries will still be "
             "loaded, but nothing will observe the loads that follow.");
    }

    const NativeAPIEntries *api_entries = GetNativeAPIEntries();
    if (api_entries == nullptr) {
        LOGE("LspdHyperRuntime: the native API entries could not be built; not loading modules.");
        return;
    }

    for (const auto &entry : entries) {
        // Registering first is what lets the loader hook recognize this very dlopen and call
        // native_init itself. When the hook is not there it is called by hand below, so a module is
        // initialized either way.
        if (intercepts_dlopen) RegisterNativeLib(entry.library_name);

        void *handle = dlopen(entry.library_path.c_str(), RTLD_NOW);
        if (handle == nullptr) {
            LOGE("LspdHyperRuntime: cannot load {} for '{}': {}.", entry.library_path.c_str(),
                 entry.module_package.c_str(), dlerror());
            continue;
        }
        LOGI("LspdHyperRuntime: loaded {} for '{}'.", entry.library_path.c_str(),
             entry.module_package.c_str());

        if (intercepts_dlopen) continue;
        void *init_sym = dlsym(handle, "native_init");
        if (init_sym == nullptr) {
            LOGW("LspdHyperRuntime: {} does not export native_init.", entry.library_path.c_str());
            continue;
        }
        reinterpret_cast<NativeInit>(init_sym)(api_entries);
    }
}

}  // namespace

/**
 * @brief Called once per specialized application process, after its uid, gid, groups and SELinux
 *        context have been applied.
 *
 * This runs in a process forked from a possibly multithreaded parent, so it does no I/O and starts
 * no threads: the answer was fetched by the spawner before the fork and is already in memory, and
 * all this does with it is look the process up and dlopen what it names.
 */
void OnAppSpecialized(const ZnHyosAppSpecializeArgs *args) {
    if (!g_registered) return;
    if (g_specialized) return;
    g_specialized = true;
    if (args == nullptr || args->package_name == nullptr) {
        LOGE("LspdHyperRuntime: specialization arrived without a package name.");
        return;
    }

    LOGI("LspdHyperRuntime: specializing process '{}' (package '{}').", args->process_name,
         args->package_name);

    // The process name first, because that is what a scope is keyed by and what this process
    // actually is: a package with more than one process has a different scope in each. The package
    // name is the fallback, for a runtime that gave us a name we cannot use -- an inherited one, or
    // one truncated to the kernel's fifteen characters -- and it always resolves to the main
    // process, which is the more useful of the two wrong answers.
    const std::vector<LibraryEntry> *entries = nullptr;
    if (args->process_name != nullptr) {
        const auto found = g_index.find(args->process_name);
        if (found != g_index.end()) entries = &found->second;
    }
    if (entries == nullptr) {
        const auto found = g_index.find(args->package_name);
        if (found != g_index.end()) entries = &found->second;
    }
    if (entries == nullptr || entries->empty()) {
        LOGD("LspdHyperRuntime: '{}' is in no module's scope.", args->package_name);
        return;
    }

    LOGI("LspdHyperRuntime: '{}' has {} module librar{} to load.", args->package_name,
         entries->size(), entries->size() == 1 ? "y" : "ies");
    LoadModuleLibraries(*entries);
}

void OnCompanionLoaded() {
    LOGI("LspdHyperRuntime: companion loaded in pid {}.", static_cast<int>(getpid()));
    pthread_t thread;
    if (pthread_create(&thread, nullptr, ServeMonitor, nullptr) != 0) {
        LOGE("LspdHyperRuntime: cannot serve {} on a thread; the daemon will see no runtime.",
             kMonitorPath);
        return;
    }
    pthread_detach(thread);
}

void OnModuleConnected(int fd) {
    LOGI("LspdHyperRuntime: companion connected on fd {}.", fd);

    // The spawner's first write is its status byte, and it is written before any fork, so it is
    // already on its way when this runs. Bounded anyway: a spawner that died between connecting and
    // writing must not leave the companion -- and the loader's own loop that called us -- stuck.
    struct timeval timeout {};
    timeout.tv_sec = kStatusByteTimeoutSeconds;
    if (setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) != 0) {
        LOGW("LspdHyperRuntime: cannot bound the registration handshake: {}.", strerror(errno));
        close(fd);
        return;
    }

    char status = 0;
    if (!ReadByte(fd, status) || status != 1) {
        LOGW("LspdHyperRuntime: no registration status arrived; the daemon will be told that "
             "injection is not working.");
        close(fd);
        return;
    }

    // The timeout above protects only the initial handshake. This endpoint must wait indefinitely
    // between requests; otherwise two seconds of inactivity permanently kills the connection the
    // spawner keeps for the life of the process.
    timeout = {};
    if (setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) != 0) {
        LOGW("LspdHyperRuntime: cannot clear the registration timeout: {}.", strerror(errno));
        close(fd);
        return;
    }
    g_registered_connections.fetch_add(1);
    LOGI("LspdHyperRuntime: the runtime API is registered; the daemon will be told so.");

    pthread_t thread;
    auto *argument = reinterpret_cast<void *>(static_cast<intptr_t>(fd));
    if (pthread_create(&thread, nullptr, ServeCompanion, argument) == 0) {
        pthread_detach(thread);
        return;
    }
    LOGE("LspdHyperRuntime: cannot serve the companion on a thread; serving inline.");
    ServeCompanion(argument);
}

void OnModuleLoaded(void *self_handle, const ZygiskNextAPI *api) {
    if (api == nullptr) {
        LOGW("LspdHyperRuntime: the loader handed us no API; disabled in this process.");
        return;
    }
    g_api = api;

    // Every refusal below is a warning, not an error. The Runtime API is an optional part of the
    // loader: an older build does not have it, one that does can still say no, and in both cases
    // the right outcome is the same -- nothing of ours runs here, and the process carries on
    // exactly as it would without this feature. Saying so at warning level is the whole report.
    const ZygiskNextRuntime *runtime = api->getRuntime ? api->getRuntime() : nullptr;
    if (runtime == nullptr) {
        LOGW("LspdHyperRuntime: this loader offers no Runtime API; disabled in this process.");
        return;
    }
    if (runtime->type != ZN_RUNTIME_HYOS) {
        LOGW("LspdHyperRuntime: runtime type {} is not the HyperOS one; disabled in this process.",
             static_cast<int>(runtime->type));
        return;
    }
    if (runtime->api_version < ZYGISK_NEXT_HYOS_API_VERSION) {
        LOGW("LspdHyperRuntime: the runtime speaks API {}, we need {}; disabled in this process.",
             runtime->api_version, ZYGISK_NEXT_HYOS_API_VERSION);
        return;
    }
    if (runtime->registerModule == nullptr) {
        LOGW("LspdHyperRuntime: the runtime cannot register modules; disabled in this process.");
        return;
    }

    // Take the runtime's own hook engine before the native API is handed to any module: this is the
    // process the hooks will be installed in, and the runtime is the one that knows what it has
    // already patched. Failing here is not fatal -- the API keeps Dobby -- but it is worth saying,
    // because two engines on one address is exactly what the handover avoids.
    if (api->inlineHook == nullptr || api->inlineUnhook == nullptr) {
        LOGW("LspdHyperRuntime: the runtime offers no inline hooks; falling back to Dobby.");
    } else {
        SetHookBackend(api->inlineHook, api->inlineUnhook);
    }

    static const ZygiskNextHyosModule hyos_module = {
        .target_api_version = ZYGISK_NEXT_HYOS_API_VERSION,
        .onAppSpecialized = OnAppSpecialized,
    };
    if (runtime->registerModule(&hyos_module) != ZN_SUCCESS) {
        LOGW("LspdHyperRuntime: the runtime refused our specialization callback; disabled in this "
             "process.");
        return;
    }
    g_registered = true;
    LOGI("LspdHyperRuntime: registered; applications spawned here will be specialized.");

    // Installed here, in the spawner, and inherited by every process it forks. Here rather than in
    // the child is the point: this runs before main, on one thread, with nothing else in the
    // process to race against and no other thread's lock for the fork to have caught mid-hook.
    // Only worth doing now that a callback is coming -- without one, nothing would ever load a
    // module library, and patching a system process's loader for that is a change with no purpose.
    if (!InstallNativeAPI()) {
        LOGW("LspdHyperRuntime: the loader cannot be intercepted; module libraries will still be "
             "loaded on specialization, but later loads will be invisible to them.");
    }

    // One connection, made now and kept for the life of the process. The spawner is its only
    // client -- a child never touches it -- so the exchange on it needs no correlation and no
    // protection against replies crossing.
    if (api->connectCompanion == nullptr) {
        LOGW("LspdHyperRuntime: the loader offers no companion connection; applications spawned "
             "here will run unhooked.");
        return;
    }
    g_companion_fd = api->connectCompanion(self_handle);
    if (g_companion_fd < 0) {
        LOGW("LspdHyperRuntime: no companion, so the target list cannot be obtained; applications "
             "spawned here will run unhooked.");
    } else {
        struct timeval timeout{};
        timeout.tv_sec = kCompanionTimeoutSeconds;
        if (setsockopt(g_companion_fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) != 0) {
            LOGW("LspdHyperRuntime: cannot bound the companion reply: {}.", strerror(errno));
        }
        LOGI("LspdHyperRuntime: companion connection established on fd {}.", g_companion_fd);

        // The companion's first read is this byte, before it serves anything else, so it has to
        // arrive before the first fork -- which it does, because it is written here and children
        // only exist once the spawner's main runs. It is the answer the daemon will be handed when
        // it asks whether this runtime is being injected at all.
        const char status = g_registered ? 1 : 0;
        if (!WriteAll(g_companion_fd, std::string(1, status))) {
            LOGW("LspdHyperRuntime: cannot report the injection status to the companion: {}.",
                 strerror(errno));
        }

        // The table is fetched now so that the first fork has something to hand down, and again on
        // the parent side of every later fork. The hook is registered here rather than in the child
        // for the same reason the loader hook is: this runs before main, on one thread.
        pthread_atfork(PrepareIndex, nullptr, nullptr);
        RefreshIndex();
    }
}

}  // namespace lspd::native::hyos

// =========================================================================================
// Zygisk Next module registration
// =========================================================================================
//
// Both structures are looked up by name in this library when Zygisk Next injects it into
// /system_ext/bin/hyos_spawner, which is what zn_modules.txt asks for. The ordinary Zygisk entry
// point above (REGISTER_ZYGISK_MODULE in module.cpp) is untouched: a library exports as many entry
// points as the loaders loading it need, and the two never run in the same process.

extern "C" __attribute__((visibility("default"))) ZygiskNextModule zn_module = {
    .target_api_version = ZYGISK_NEXT_API_VERSION,
    .onModuleLoaded = lspd::native::hyos::OnModuleLoaded,
};

extern "C" __attribute__((visibility("default"))) ZygiskNextCompanionModule zn_companion_module = {
    .target_api_version = ZYGISK_NEXT_API_VERSION,
    .onCompanionLoaded = lspd::native::hyos::OnCompanionLoaded,
    .onModuleConnected = lspd::native::hyos::OnModuleConnected,
};
