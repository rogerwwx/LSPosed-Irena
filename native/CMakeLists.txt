cmake_minimum_required(VERSION 3.28)

project(native)

set(CMAKE_CXX_STANDARD 23)

add_subdirectory(${EXTERNAL_ROOT} external)

file(GLOB_RECURSE NATIVE_SOURCES "src/*.cpp")
add_library(${PROJECT_NAME} STATIC ${NATIVE_SOURCES})

set(IGNORED_WARNINGS
    -Wno-c99-extensions
    -Wno-extra-semi
    -Wno-gnu-zero-variadic-macro-arguments
    -Wno-gnu-flexible-array-initializer
    -Wno-variadic-macros
    -Wno-zero-length-array
)

# --- Include Directories ---
# 'include' is PUBLIC: any target linking this static library inherits it.
target_include_directories(${PROJECT_NAME} PUBLIC include)

# 'src' and the xz-embedded headers (used by the ELF debugdata decoder) are
# only needed to compile this library itself.
target_include_directories(${PROJECT_NAME} PRIVATE src ${EXTERNAL_ROOT}/xz-embedded/linux/include)

# --- Compiler Options ---
target_compile_options(${PROJECT_NAME} PRIVATE -Wpedantic ${IGNORED_WARNINGS})

# --- Library Linking ---
# Mirrors the previous core static library's link set so the final shared
# library resolves every dependency exactly as before.
target_link_libraries(${PROJECT_NAME} PUBLIC
    dobby
    lsplant_static
    xz_embedded
    log # Android logging library
    fmt-header-only
)

target_link_libraries(${PROJECT_NAME} PRIVATE
    dex_builder_static
)
