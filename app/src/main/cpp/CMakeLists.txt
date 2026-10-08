cmake_minimum_required(VERSION 3.22)
project(ancdu C)

set(CMAKE_C_STANDARD 17)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_INTERPROCEDURAL_OPTIMIZATION_RELEASE ON)

find_package(Threads REQUIRED)

add_library(ancdu_core STATIC
  arena.c
  csr.c
  delta.c
  scan.c
  index.c
  rmtree.c
  rm_parent.c
  session.c
  session_root.c
  shq.c
)
target_include_directories(ancdu_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_definitions(ancdu_core PUBLIC _GNU_SOURCE)
target_compile_options(ancdu_core PRIVATE -Wall -Wextra -fvisibility=hidden)
target_link_libraries(ancdu_core PUBLIC Threads::Threads)
if(ANDROID)
  target_link_libraries(ancdu_core PUBLIC log) # rmtree: «сверка пропущена» — в logcat
endif()

add_executable(ancdu_scan cli.c)
target_link_libraries(ancdu_scan PRIVATE ancdu_core)
target_compile_options(ancdu_scan PRIVATE -Wall -Wextra)
set_target_properties(ancdu_scan PROPERTIES OUTPUT_NAME "libancdu_scan.so")

if(ANDROID)
  add_executable(memfd_probe ${CMAKE_CURRENT_SOURCE_DIR}/../../../../tools/memfd_probe.c)
  target_link_libraries(memfd_probe PRIVATE ancdu_core)
endif()

if(ANDROID)
  add_library(ancdu SHARED jni.c)
  target_link_libraries(ancdu PRIVATE ancdu_core)
  target_compile_options(ancdu PRIVATE -Wall -Wextra)
endif()
