cmake_minimum_required(VERSION 3.22.1)
project(stealth)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)
FetchContent_Declare(
    xhook
    GIT_REPOSITORY https://github.com/iqiyi/xHook.git
    GIT_TAG master
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(xhook)

# Gabungkan stealth.cpp DAN semua file .c dari xhook langsung ke dalam libstealth.so
add_library(stealth SHARED
    stealth.cpp
    ${xhook_SOURCE_DIR}/libxhook/jni/xh_core.c
    ${xhook_SOURCE_DIR}/libxhook/jni/xh_elf.c
    ${xhook_SOURCE_DIR}/libxhook/jni/xh_jni.c
)

# Arahkan ke folder header yang benar
target_include_directories(stealth PRIVATE ${xhook_SOURCE_DIR}/libxhook/jni)

# Hanya butuh log dan android
target_link_libraries(stealth log android)