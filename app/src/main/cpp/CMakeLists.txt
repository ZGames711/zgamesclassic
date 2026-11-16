cmake_minimum_required(VERSION 3.22.1)
project(psxfrontend LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(psxfrontend SHARED frontend.cpp)

find_library(log-lib log)
find_library(android-lib android)
target_link_libraries(psxfrontend ${log-lib} ${android-lib} dl)

# Android 15+ devices may use 16 KB pages.
target_link_options(psxfrontend PRIVATE -Wl,-z,max-page-size=16384)
