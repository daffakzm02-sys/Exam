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

add_library(stealth SHARED stealth.cpp)

# Perbaikan: header xhook ada di libxhook/jni
target_include_directories(stealth PRIVATE ${xhook_SOURCE_DIR}/libxhook/jni)

# xHook tidak punya CMakeLists.txt sendiri, jadi kita compile langsung file .c-nya
add_library(xhook STATIC
    ${xhook_SOURCE_DIR}/libxhook/jni/xh_core.c
    ${xhook_SOURCE_DIR}/libxhook/jni/xh_elf.c
    ${xhook_SOURCE_DIR}/libxhook/jni/xh_jni.c
)
target_include_directories(xhook PRIVATE ${xhook_SOURCE_DIR}/libxhook/jni)

target_link_libraries(stealth xhook log android)