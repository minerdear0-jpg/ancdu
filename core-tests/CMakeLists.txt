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
# rmtree.c включается в сам тест (перехват openat), ancdu_core не нужен
add_executable(test_rmtree_race test_rmtree_race.c)
target_include_directories(test_rmtree_race PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/cpp)
target_compile_definitions(test_rmtree_race PRIVATE _GNU_SOURCE)
add_test(NAME test_rmtree_race COMMAND test_rmtree_race)
ancdu_test(test_session)

ancdu_test(test_memfd)
target_compile_definitions(test_memfd PRIVATE ANCDU_CLI="$<TARGET_FILE:ancdu_scan>")
add_dependencies(test_memfd ancdu_scan)

ancdu_test(test_shq)
ancdu_test(test_root)
target_compile_definitions(test_root PRIVATE ANCDU_CLI="$<TARGET_FILE:ancdu_scan>")
add_dependencies(test_root ancdu_scan)
