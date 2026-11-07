
cmake_minimum_required(VERSION 3.22.1)

project(AstroStackNative)

add_library(
    astrostack_native
    SHARED
    native-lib.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    astrostack_native
    ${log-lib}
)
