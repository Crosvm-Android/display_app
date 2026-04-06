cmake_minimum_required(VERSION 3.22.1)
project(virtservice)

# ── virtservice executable ────────────────────────────────────────────────────
# Compiled as an aarch64 Android executable, deployed to /data/local/tmp/
# and launched with root to register the binder service.

add_executable(virtservice
    virtservice_main.cpp
)

target_compile_options(virtservice PRIVATE
    -std=c++17
    -Wall
    -fvisibility=hidden
)

# Add local directory first so our stub headers (binder_manager.h, binder_process.h)
# are found. These functions exist in libbinder_ndk.so on device but their headers
# are not in the public NDK.
target_include_directories(virtservice PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# Link against NDK binder and Android log
# libbinder_ndk.so is present on every Android device
target_link_libraries(virtservice
    libbinder_ndk.so
    log
)

# Strip the binary to keep it small
if (CMAKE_BUILD_TYPE STREQUAL "Release")
    add_custom_command(TARGET virtservice POST_BUILD
        COMMAND ${CMAKE_STRIP} $<TARGET_FILE:virtservice>)
endif()
