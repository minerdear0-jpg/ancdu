cmake_minimum_required(VERSION 3.22)
project(ancdu C)

set(CMAKE_C_STANDARD 17)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_INTERPROCEDURAL_OPTIMIZATION_RELEASE ON)

find_package(Threads REQUIRED)

add_library(ancdu_core STATIC
  arena.c
)
target_include_directories(ancdu_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_definitions(ancdu_core PUBLIC _GNU_SOURCE)
target_compile_options(ancdu_core PRIVATE -Wall -Wextra -fvisibility=hidden)
target_link_libraries(ancdu_core PUBLIC Threads::Threads)
