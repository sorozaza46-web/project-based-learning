cmake_minimum_required(VERSION 3.10.2)
project("mycustomapk")

# 1. Menünün JNI Köprüsü İçin Boş/Dummy Bir Kütüphane Tanımlıyoruz
add_library(memory_engine SHARED
            memory_engine.cpp)

find_library(log-lib log)
target_link_libraries(memory_engine ${log-lib})

# 2. Esas Root Yetkisiyle Çalışacak Bağımsız Executable Daemon Sürümü
add_executable(stealth_daemon root_daemon.cpp)
