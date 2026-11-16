cmake_minimum_required(VERSION 3.22.1)
project(tubik_native LANGUAGES CXX)

add_library(
    tubik_native
    SHARED
    tubik_native.cpp
)

target_compile_options(
    tubik_native
    PRIVATE
    -std=c++17
    -fvisibility=hidden
    -fno-rtti
    -fno-exceptions
    # NDK r28 clang 19 miscompiles the slot-decode loop when vectorized (arm64, x86, x86_64),
    # which corrupts the decoded player path and silently disables stream fallback. The library is
    # tiny and not performance sensitive, so keep vectorization off.
    -fno-vectorize
    -fno-slp-vectorize
    -ffunction-sections
    -fdata-sections
    -ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.
)

target_link_options(
    tubik_native
    PRIVATE
    -Wl,--gc-sections
    -Wl,-z,max-page-size=16384
)
