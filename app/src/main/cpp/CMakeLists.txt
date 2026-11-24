cmake_minimum_required(VERSION 3.22.1)
project("umitguard")

add_library(umitguard SHARED
    native-lib.cpp
)

target_compile_options(umitguard PRIVATE -O2 -fvisibility=hidden -fvisibility-inlines-hidden)
