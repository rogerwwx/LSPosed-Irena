cmake_minimum_required(VERSION 3.28)
project(lspd)

# Passed as -DLOG_DISABLED=ON by the special (-Plsp.special=true) build; silences every
# LOGx macro in the loader, in native, and in external (dobby/lsplant) via logging.h.
option(LOG_DISABLED "Compile out all native log printing" OFF)
if (LOG_DISABLED)
    add_compile_definitions(LOG_DISABLED)
endif()

add_subdirectory(${NATIVE_ROOT} native)

add_library(${PROJECT_NAME} SHARED
        src/module.cpp
        src/ipc_bridge.cpp
        src/atexit.cpp)

target_include_directories(${PROJECT_NAME} PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/include)

target_link_libraries(${PROJECT_NAME} native log)

if (DEFINED DEBUG_SYMBOLS_PATH)
    message(STATUS "Debug symbols will be placed at ${DEBUG_SYMBOLS_PATH}")
    add_custom_command(TARGET ${PROJECT_NAME} POST_BUILD
            COMMAND ${CMAKE_COMMAND} -E make_directory ${DEBUG_SYMBOLS_PATH}/${ANDROID_ABI}
            COMMAND ${CMAKE_OBJCOPY} --only-keep-debug $<TARGET_FILE:${PROJECT_NAME}>
            ${DEBUG_SYMBOLS_PATH}/${ANDROID_ABI}/${PROJECT_NAME}.debug
            COMMAND ${CMAKE_STRIP} --strip-all $<TARGET_FILE:${PROJECT_NAME}>
            COMMAND ${CMAKE_OBJCOPY} --add-gnu-debuglink ${DEBUG_SYMBOLS_PATH}/${ANDROID_ABI}/${PROJECT_NAME}.debug
            $<TARGET_FILE:${PROJECT_NAME}>)
endif()
