
add_executable(test-retro test-retro.c)
target_include_directories(test-retro PRIVATE ${LIBRETRO_INCLUDE_DIR})

add_test(NAME retro COMMAND test-retro $<TARGET_FILE:retro-hatari>)

add_executable(test-debug-api test-debug-api.c)
target_include_directories(test-debug-api PRIVATE ${LIBRETRO_INCLUDE_DIR})
target_link_libraries(test-debug-api ${CMAKE_DL_LIBS})

add_test(NAME debug-api COMMAND test-debug-api $<TARGET_FILE:retro-hatari>)
set_tests_properties(debug-api PROPERTIES ENVIRONMENT "HATARI_TEST=debug-api")
