cmake_minimum_required(VERSION 3.22.1)
project(runjar_jni C)

add_library(runjar_jni SHARED
        jni_bridge.c
        log_bridge.c)

target_compile_options(runjar_jni PRIVATE -Wall -Wextra -Wno-unused-parameter -fvisibility=hidden)

find_library(log-lib log)
target_link_libraries(runjar_jni ${log-lib} dl)
