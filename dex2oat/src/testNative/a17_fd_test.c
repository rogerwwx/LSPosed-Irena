#define _GNU_SOURCE
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <limits.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>
#define LP_SELECT(a, b) b
#define LOGI(...) ((void)0)
#define LOGW(...) ((void)0)
#define PLOGE(...) ((void)0)
#include "../main/cpp/a17.h"

static int fd_count(void) {
    DIR *d = opendir("/proc/self/fd");
    assert(d);
    int n = 0;
    while (readdir(d)) ++n;
    closedir(d);
    return n;
}
static void transfer(int variant) {
    char name[80];
    snprintf(name, sizeof(name), "irena-test-%d-%d", getpid(), variant);
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    snprintf(address.sun_path + 1, sizeof(address.sun_path) - 1, "%s.a17", name);
    int listener = socket(AF_UNIX, SOCK_STREAM, 0);
    assert(listener >= 0);
    assert(!bind(listener, (struct sockaddr *)&address, sizeof(sa_family_t) + 1 + strlen(address.sun_path + 1)));
    assert(!listen(listener, 1));
    int before = fd_count();
    pid_t pid = fork();
    assert(pid >= 0);
    if (!pid) {
        int client = accept(listener, NULL, NULL);
        assert(client >= 0);
        unsigned char request[2];
        assert(recv(client, request, 2, MSG_WAITALL) == 2);
        assert(request[0] == 1 && request[1] == 2);
        if (variant == 4) { sleep(2); _exit(0); }
        char temp[] = "/tmp/irena-preload-XXXXXX";
        int fd = mkstemp(temp);
        assert(fd >= 0);
        unlink(temp);
        unsigned char elf[5] = {0x7f, 'E', 'L', 'F', variant == 2 ? 1 : 2};
        assert(write(fd, elf, sizeof(elf)) == sizeof(elf));
        unsigned char status = variant == 3 ? 0 : 1;
        struct iovec io = {.iov_base = &status, .iov_len = 1};
        union { struct cmsghdr align; char bytes[CMSG_SPACE(2 * sizeof(int))]; } control = {};
        int count = variant == 1 ? 2 : 1;
        struct msghdr msg = {.msg_iov = &io, .msg_iovlen = 1, .msg_control = control.bytes,
                             .msg_controllen = CMSG_SPACE(count * sizeof(int))};
        struct cmsghdr *c = CMSG_FIRSTHDR(&msg);
        c->cmsg_len = CMSG_LEN(count * sizeof(int)); c->cmsg_level = SOL_SOCKET; c->cmsg_type = SCM_RIGHTS;
        for (int i = 0; i < count; ++i) ((int *)CMSG_DATA(c))[i] = fd;
        assert(sendmsg(client, &msg, MSG_NOSIGNAL) == 1);
        _exit(0);
    }
    int received = a17_preload(name);
    if (!variant) {
        assert(received >= 0);
        assert(!(fcntl(received, F_GETFD) & FD_CLOEXEC));
        close(received);
    } else assert(received == -1);
    assert(fd_count() == before);
    close(listener);
    int status;
    assert(waitpid(pid, &status, 0) == pid && WIFEXITED(status) && WEXITSTATUS(status) == 0);
}
int main(void) {
    for (int i = 0; i < 5; ++i) transfer(i);
    assert(a17_preload("irena-no-such-server") == -1);
    puts("A17 SCM_RIGHTS, ABI, malformed reply, timeout and FD leak tests passed");
}
