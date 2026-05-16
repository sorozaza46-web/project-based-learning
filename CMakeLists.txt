cmake_minimum_required(VERSION 3.22.1)

project("mycustomapk")

# C++ dosyasını paylaşılan kütüphane (.so) olarak derle
add_library(
        memory_engine
        SHARED
        memory_engine.cpp
)

# Sistem log kütüphanelerini bağla
find_library(
        log-lib
        log
)

target_link_libraries(
        memory_engine
        ${log-lib}
)

