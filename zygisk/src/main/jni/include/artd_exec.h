#pragma once

#include <stddef.h>

// No allocation, locks or logging: this code runs between fork and exec in artd.
namespace lspd::artd {
constexpr size_t kMaxArgs = 2048;
constexpr size_t kMaxText = 4096;
inline bool Equal(const char *a, const char *b) {
    if (!a || !b) return false;
    for (size_t i = 0; i < kMaxText; ++i) {
        if (a[i] != b[i]) return false;
        if (!a[i]) return true;
    }
    return false;
}
inline const char *Base(const char *s) {
    if (!s) return "";
    const char *base = s;
    for (size_t i = 0; i < kMaxText; ++i) {
        if (!s[i]) return base;
        if (s[i] == '/') base = s + i + 1;
    }
    return "";
}
inline int Width(const char *s) {
    const char *b = Base(s);
    if (Equal(b, "dex2oat32") || Equal(b, "dex2oatd32")) return 0;
    if (Equal(b, "dex2oat64") || Equal(b, "dex2oatd64")) return 1;
    if (Equal(b, "dex2oat") || Equal(b, "dex2oatd")) return sizeof(void *) == 8;
    return -1;
}
inline bool Prefix(const char *s, const char *p) {
    while (*p) if (*s++ != *p++) return false;
    return true;
}
inline bool Append(char *out, size_t &n, const char *s) {
    while (*s) {
        if (n + 1 >= kMaxText) return false;
        out[n++] = *s++;
    }
    out[n] = 0;
    return true;
}
inline bool Number(char *out, size_t &n, unsigned value) {
    char digits[10];
    size_t count = 0;
    do { digits[count++] = static_cast<char>('0' + value % 10); value /= 10; } while (value);
    while (count) {
        if (n + 1 >= kMaxText) return false;
        out[n++] = digits[--count];
    }
    out[n] = 0;
    return true;
}
struct Rewrite {
    char *args[kMaxArgs + 2];
    char fdPath[kMaxText];
    char keep[kMaxText];
    const char *executable;
    int fd;
};
inline bool Build(const char *path, char *const argv[], const int wrappers[2], Rewrite &out) {
    if (!path || !argv || !argv[0]) return false;
    if (Prefix(path, "/proc/self/fd/") || Prefix(path, "/data/adb/modules/")) return false;
    size_t count = 0;
    while (count < kMaxArgs && argv[count]) ++count;
    if (count == kMaxArgs) return false;
    size_t target = 0, keep = count;
    bool artExec = Equal(Base(path), "art_exec");
    if (artExec) {
        target = count;
        for (size_t i = 1; i < count; ++i) {
            if (Equal(argv[i], "--")) { target = i + 1; break; }
            if (Prefix(argv[i], "--keep-fds=")) {
                if (keep != count) return false;
                keep = i;
            }
        }
        if (target >= count || keep == count) return false;
    }
    const char *stock = artExec ? argv[target] : path;
    if (!Prefix(stock, "/apex/")) return false;
    int width = Width(stock);
    if (width < 0 || (out.fd = wrappers[width]) < 0) return false;
    size_t n = 0;
    if (!Append(out.fdPath, n, "/proc/self/fd/") || !Number(out.fdPath, n, out.fd)) return false;
    if (artExec) {
        n = 0;
        if (!Append(out.keep, n, argv[keep])) return false;
        // art_exec accepts an empty list; do not introduce a leading empty element.
        if (n > 11 && !Append(out.keep, n, ":")) return false;
        if (!Number(out.keep, n, out.fd)) return false;
    }
    size_t j = 0;
    for (size_t i = 0; i < count; ++i) {
        if (i == target) {
            out.args[j++] = out.fdPath;
            out.args[j++] = const_cast<char *>(stock);
        } else out.args[j++] = artExec && i == keep ? out.keep : argv[i];
    }
    out.args[j] = nullptr;
    out.executable = artExec ? path : out.fdPath;
    return true;
}
} // namespace lspd::artd
