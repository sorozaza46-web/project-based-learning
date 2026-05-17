cmake_minimum_required(VERSION 3.10.2)
project("mycustomapk")

add_library(memory_engine SHARED
            memory_engine.cpp)

find_library(log-lib log)
target_link_libraries(memory_engine ${log-lib})
