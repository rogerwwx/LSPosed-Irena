#include "artd_exec.h"
#include <cassert>
#include <cstring>
#include <string>
#include <vector>
#include <iostream>
using namespace lspd::artd;
int main() {
    int fds[] = {56, 57};
    Rewrite out{};
    auto build = [&](const char *path, std::vector<const char *> args) {
        args.push_back(nullptr);
        return Build(path, const_cast<char *const *>(args.data()), fds, out);
    };
    assert(build("/apex/com.android.art/bin/art_exec", {"art_exec", "--drop-capabilities", "--keep-fds=10:11", "--", "/apex/com.android.art/bin/dex2oatd64", "--zip-fd=10"}));
    assert(out.fd == 57 && !strcmp(out.keep, "--keep-fds=10:11:57"));
    assert(!strcmp(out.args[1], "--drop-capabilities"));
    assert(!strcmp(out.args[4], "/proc/self/fd/57"));
    assert(!strcmp(out.args[5], "/apex/com.android.art/bin/dex2oatd64"));
    assert(!strcmp(out.args[6], "--zip-fd=10") && out.args[7] == nullptr);
    assert(build("/apex/com.android.art/bin/dex2oat32", {"dex2oat32", "--zip-fd=10"}));
    assert(out.fd == 56 && !strcmp(out.executable, "/proc/self/fd/56"));
    assert(!strcmp(out.args[1], "/apex/com.android.art/bin/dex2oat32"));
    assert(!build("/apex/com.android.art/bin/art_exec", {"art_exec", "--", "/apex/com.android.art/bin/dex2oat64"}));
    assert(!build("/apex/com.android.art/bin/art_exec", {"art_exec", "--keep-fds=1", "/apex/com.android.art/bin/dex2oat64"}));
    assert(!build("/apex/com.android.art/bin/art_exec", {"art_exec", "--keep-fds=1", "--", "/bin/true", "/apex/com.android.art/bin/dex2oat64"}));
    assert(!build("/apex/com.android.art/bin/art_exec", {"art_exec", "--keep-fds=1", "--keep-fds=2", "--", "/apex/com.android.art/bin/dex2oat64"}));
    assert(build("/apex/com.android.art/bin/art_exec", {"art_exec", "--keep-fds=", "--", "/apex/com.android.art/bin/dex2oat64"}));
    assert(!strcmp(out.keep, "--keep-fds=57"));
    assert(!build("/system/bin/dex2oat64", {"dex2oat64"}));
    assert(!build("/proc/self/fd/57", {"wrapper", "/apex/com.android.art/bin/dex2oat64"}));
    std::string large(kMaxText + 20, '1');
    std::string keep = "--keep-fds=" + large;
    assert(!build("/apex/com.android.art/bin/art_exec", {"art_exec", keep.c_str(), "--", "/apex/com.android.art/bin/dex2oat64"}));
    std::vector<const char *> tooMany(kMaxArgs + 1, "arg");
    assert(!build("/apex/com.android.art/bin/dex2oat64", tooMany));
    fds[0] = -1;
    assert(!build("/apex/com.android.art/bin/dex2oat32", {"dex2oat32"}));
    assert(build("/apex/com.android.art@123/bin/dex2oatd64", {"dex2oatd64"}));
    std::cout << "artd argv rewrite tests passed\n";
}
