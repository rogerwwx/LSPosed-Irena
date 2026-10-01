#pragma once

#include <fcntl.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/system_properties.h>

// A17-only protocol on <installation token>.a17: request {version=1, ELF class};
// reply one byte (1 = OK) with exactly one SCM_RIGHTS preload descriptor.
static int a17_preload(const char *name) {
    int sock = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (sock < 0) return -1;
    struct timeval timeout = {.tv_sec = 1};
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    snprintf(address.sun_path + 1, sizeof(address.sun_path) - 1, "%s.a17", name);
    if (connect(sock, (struct sockaddr *)&address, sizeof(sa_family_t) + 1 + strlen(address.sun_path + 1))) {
        close(sock); return -1;
    }
    const unsigned char request[2] = {1, LP_SELECT(1, 2)};
    if (send(sock, request, sizeof(request), MSG_NOSIGNAL) != sizeof(request)) { close(sock); return -1; }
    unsigned char status = 0;
    struct iovec io = {.iov_base = &status, .iov_len = 1};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int) * 4)]; } control = {};
    struct msghdr msg = {.msg_iov = &io, .msg_iovlen = 1,
                        .msg_control = control.bytes, .msg_controllen = sizeof(control.bytes)};
    ssize_t n;
    do { n = recvmsg(sock, &msg, MSG_CMSG_CLOEXEC); } while (n < 0 && errno == EINTR);
    close(sock);
    int fd = -1, count = 0;
    bool valid = n == 1 && status == 1 && !(msg.msg_flags & MSG_CTRUNC);
    if (n < 0) return -1;
    for (struct cmsghdr *c = CMSG_FIRSTHDR(&msg); c; c = CMSG_NXTHDR(&msg, c)) {
        if (c->cmsg_level != SOL_SOCKET || c->cmsg_type != SCM_RIGHTS || c->cmsg_len < CMSG_LEN(0)) {
            valid = false; continue;
        }
        size_t size = c->cmsg_len - CMSG_LEN(0);
        if (size % sizeof(int)) valid = false;
        int *fds = (int *)CMSG_DATA(c);
        for (size_t i = 0; i < size / sizeof(int); ++i) {
            if (count++ == 0) fd = fds[i]; else close(fds[i]);
        }
    }
    unsigned char elf[5];
    struct stat st;
    if (!valid || count != 1 || fstat(fd, &st) || !S_ISREG(st.st_mode) ||
        pread(fd, elf, sizeof(elf), 0) != sizeof(elf) ||
        memcmp(elf, "\177ELF", 4) || elf[4] != LP_SELECT(1, 2) || fcntl(fd, F_SETFD, 0)) {
        if (fd >= 0) close(fd);
        return -1;
    }
    return fd;
}

static int a17_main(int argc, char **argv, const char *socket_name) {
    if (argc < 2) return 1;
    const char *stock = argv[1];
    const char *base = strrchr(stock, '/');
    base = base ? base + 1 : stock;
    // No global mount backend on A17: accept only stock ART paths of this ABI.
    if (strncmp(stock, "/apex/", 6) ||
        (strcmp(base, LP_SELECT("dex2oat32", "dex2oat64")) &&
         strcmp(base, LP_SELECT("dex2oatd32", "dex2oatd64")) &&
         strcmp(base, "dex2oat") && strcmp(base, "dex2oatd"))) return 1;
    struct stat target, self;
    if (stat(stock, &target) || stat("/proc/self/exe", &self) ||
        (target.st_dev == self.st_dev && target.st_ino == self.st_ino)) return 1;
    // The executable descriptor was retained only to cross art_exec. Do not leak it to stock.
    const char prefix[] = "/proc/self/fd/";
    if (!strncmp(argv[0], prefix, sizeof(prefix) - 1)) {
        char *end;
        long wrapper = strtol(argv[0] + sizeof(prefix) - 1, &end, 10);
        struct stat candidate;
        if (!*end && wrapper >= 0 && wrapper <= INT_MAX && !fstat((int)wrapper, &candidate) &&
            candidate.st_dev == self.st_dev && candidate.st_ino == self.st_ino) close((int)wrapper);
    }
    int fd = a17_preload(socket_name);
    char *old = getenv("LD_PRELOAD");
    char *saved = old ? strdup(old) : NULL;
    if (old && !saved) { if (fd >= 0) close(fd); fd = -1; }
    if (fd >= 0) {
        char path[64];
        snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
        if (setenv("LD_PRELOAD", path, 1) == 0) {
            LOGI("A17: executing stock dex2oat with preload");
            execve(stock, argv + 1, environ);
            PLOGE("A17: enhanced exec failed");
            if (saved) setenv("LD_PRELOAD", saved, 1); else unsetenv("LD_PRELOAD");
        }
        close(fd);
    }
    free(saved);
    LOGW("A17: preload unavailable; executing stock dex2oat");
    execve(stock, argv + 1, environ);
    PLOGE("A17: stock exec failed");
    return 1;
}
