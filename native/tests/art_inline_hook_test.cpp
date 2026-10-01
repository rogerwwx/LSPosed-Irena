// Exercise the production restorer against a synthetic file-backed libart PT_LOAD.
#include <core/art_inline_hook_invalidation.h>

#include <fcntl.h>
#include <link.h>
#include <sys/mman.h>
#include <unistd.h>

#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

static dl_phdr_info *image = nullptr;
static int scans = 0;

extern "C" int dl_iterate_phdr(int (*callback)(dl_phdr_info *, size_t, void *), void *data) {
    ++scans;
    return image ? callback(image, sizeof(*image), data) : 0;
}

struct Fixture {
    const size_t page = static_cast<size_t>(sysconf(_SC_PAGESIZE));
    const size_t size = page * 4;
    char directory[64] = "/tmp/lspd-inline-hooks-XXXXXX";
    std::string path;
    std::vector<unsigned char> baseline = std::vector<unsigned char>(size, 0x42);
    unsigned char *live = nullptr;
    ElfW(Phdr) segment{};
    dl_phdr_info info{};

    Fixture() {
        assert(mkdtemp(directory));
        path = std::string(directory) + "/libart.so";
        int fd = open(path.c_str(), O_CREAT | O_RDWR | O_TRUNC, 0600);
        assert(fd >= 0);
        assert(write(fd, baseline.data(), size) == static_cast<ssize_t>(size));
        live = static_cast<unsigned char *>(mmap(nullptr, size, PROT_READ | PROT_EXEC, MAP_PRIVATE, fd, 0));
        assert(live != MAP_FAILED);
        close(fd);
        segment.p_type = PT_LOAD;
        segment.p_flags = PF_R | PF_X;
        segment.p_offset = page;
        segment.p_vaddr = page;
        segment.p_filesz = page * 2;
        segment.p_memsz = page * 2;
        info.dlpi_name = path.c_str();
        info.dlpi_addr = reinterpret_cast<uintptr_t>(live);
        info.dlpi_phdr = &segment;
        info.dlpi_phnum = 1;
        image = &info;
    }

    void patch(size_t offset, unsigned char value) {
        auto *address = live + (offset & ~(page - 1));
        assert(mprotect(address, page, PROT_READ | PROT_WRITE | PROT_EXEC) == 0);
        live[offset] = value;
        assert(mprotect(address, page, PROT_READ | PROT_EXEC) == 0);
    }

    ~Fixture() {
        image = nullptr;
        munmap(live, size);
        unlink(path.c_str());
        rmdir(directory);
    }
};

int main() {
    using namespace lspd::native;
    assert(!ConfigureArtInlineHookInvalidation(false));
    assert(InvalidateArtInlineHooksIfEnabled());
    assert(scans == 0);
    assert(!ConfigureArtInlineHookInvalidation(true));  // Missing libart, fail closed.
    assert(InvalidateArtInlineHooksIfEnabled());

    {
        Fixture f;
        assert(ConfigureArtInlineHookInvalidation(true));
        f.patch(f.page + 48, 0x99);
        RecordArtInlineHookInvalidationTarget(f.live + f.page + 48);
        RecordArtInlineHookInvalidationTarget(f.live + f.page + 48);
        assert(InvalidateArtInlineHooksIfEnabled());
        assert(std::memcmp(f.live, f.baseline.data(), f.size) == 0);
        f.patch(f.page + 48, 0x77);
        assert(InvalidateArtInlineHooksIfEnabled());  // One shot, not recurring.
        assert(f.live[f.page + 48] == 0x77);
    }
    {
        Fixture f;
        f.patch(f.page + 16, 0x13);  // Pre-existing hook on the same page as LSPlant.
        f.patch(2 * f.page + 16, 0x27);
        f.baseline.assign(f.live, f.live + f.size);
        assert(ConfigureArtInlineHookInvalidation(true));
        f.patch(f.page + 16, 0x90);  // A chained hook at an already modified target.
        f.patch(f.page + 96, 0x91);
        f.patch(2 * f.page + 96, 0x92);
        RecordArtInlineHookInvalidationTarget(f.live + f.page + 16);
        RecordArtInlineHookInvalidationTarget(f.live + 2 * f.page + 96);
        assert(InvalidateArtInlineHooksIfEnabled());
        assert(std::memcmp(f.live, f.baseline.data(), f.size) == 0);
    }
    {
        Fixture f;
        assert(ConfigureArtInlineHookInvalidation(true));
        auto *target = f.live + f.page + 16;
        RecordArtInlineHookInvalidationTarget(target);
        ForgetArtInlineHookInvalidationTarget(target);
        f.patch(f.page + 16, 0x63);
        assert(InvalidateArtInlineHooksIfEnabled());  // Nothing tracked: do not touch the image.
        assert(f.live[f.page + 16] == 0x63);
    }
    {
        Fixture f;
        assert(ConfigureArtInlineHookInvalidation(true));
        f.patch(f.page + 16, 0x64);
        RecordArtInlineHookInvalidationTarget(f.live + f.page + 16);
        assert(!ConfigureArtInlineHookInvalidation(false));  // Reset between processes.
        assert(InvalidateArtInlineHooksIfEnabled());
        assert(f.live[f.page + 16] == 0x64);
    }
    {
        Fixture f;
        f.segment.p_filesz = f.size * 2;
        assert(!ConfigureArtInlineHookInvalidation(true));  // Invalid backing bounds.
        assert(InvalidateArtInlineHooksIfEnabled());
    }
    {
        Fixture f;
        assert(ConfigureArtInlineHookInvalidation(true));
        f.patch(f.page + 16, 0x65);
        RecordArtInlineHookInvalidationTarget(f.live + f.page + 16);
        assert(unlink(f.path.c_str()) == 0);
        assert(!InvalidateArtInlineHooksIfEnabled());  // Backing file unavailable.
        assert(f.live[f.page + 16] == 0x65);
        assert(InvalidateArtInlineHooksIfEnabled());  // No unsafe retry.
    }
    std::puts("ART inline hook restorer: 8 scenarios passed");
}
