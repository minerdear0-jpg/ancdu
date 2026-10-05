cmake_minimum_required(VERSION 3.22)
project(ancdu_tests C)

set(ANCDU_SANITIZE "" CACHE STRING "Sanitizers: address,undefined | thread | empty")
if(ANCDU_SANITIZE)
  add_compile_options(-fsanitize=${ANCDU_SANITIZE} -fno-omit-frame-pointer -g)
  add_link_options(-fsanitize=${ANCDU_SANITIZE})
endif()

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/cpp core)

enable_testing()
function(ancdu_test name)
  add_executable(${name} ${name}.c)
  target_link_libraries(${name} PRIVATE ancdu_core)
  add_test(NAME ${name} COMMAND ${name})
endfunction()

ancdu_test(test_arena)
ancdu_test(test_csr)
ancdu_test(test_scan)
ancdu_test(test_dump)
ancdu_test(test_index)
ancdu_test(test_rmtree)
