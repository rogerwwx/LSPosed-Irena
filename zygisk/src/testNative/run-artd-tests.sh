#!/bin/sh
set -eu
cd "$(dirname "$0")/../../.."
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
g++ -std=c++20 -Wall -Wextra -Werror -fsanitize=address,undefined \
    -Izygisk/src/main/jni/include zygisk/src/testNative/artd_exec_test.cpp -o "$out/argv"
"$out/argv"
g++ -std=c++20 -Wall -Wextra -Werror -fsanitize=address,undefined \
    -pthread \
    -Izygisk/src/testNative/android-stubs -Izygisk/src/main/jni/include \
    zygisk/src/testNative/artd_hook_test.cpp -o "$out/hooks"
"$out/hooks"
# Android-only declarations are stubbed; this is not an NDK build.
g++ -std=c++20 -Wall -Wextra -Werror -fsyntax-only \
    -Izygisk/src/testNative/android-stubs -Izygisk/src/main/jni/include \
    zygisk/src/main/jni/src/artd_injection.cpp
gcc -std=gnu2x -D_GNU_SOURCE -Wall -Wextra -Werror -fsyntax-only \
    -Izygisk/src/testNative/android-stubs dex2oat/src/main/cpp/dex2oat.c
gcc -std=gnu2x -Wall -Wextra -Wno-unused-function -fsanitize=address,undefined \
    -Izygisk/src/testNative/android-stubs dex2oat/src/testNative/a17_fd_test.c -o "$out/fds"
"$out/fds"
