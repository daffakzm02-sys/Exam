cmake_minimum_required(VERSION 3.22.1)
project(stealth)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)
FetchContent_Declare(
    xhook
    URL https://github.com/iqiyi/xhook/archive/refs/tags/1.2.2.zip
)
FetchContent_MakeAvailable(xhook)

add_library(stealth SHARED stealth.cpp)
target_include_directories(stealth PRIVATE ${xhook_SOURCE_DIR}/libs)
target_link_libraries(stealth xhook log android)