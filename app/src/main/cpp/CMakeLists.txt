cmake_minimum_required(VERSION 3.22.1)
project(cestca C CXX)

# Toujours compiler le moteur en mode optimisé (sinon il est des dizaines de fois plus lent)
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
add_compile_options(-O3 -DNDEBUG)
if (ANDROID_ABI STREQUAL "arm64-v8a")
    # Instructions de calcul rapides présentes sur les téléphones depuis ~2018
    add_compile_options(-march=armv8.2-a+dotprod+fp16)
endif()

# Source de whisper.cpp, récupérée par la compilation GitHub à la racine du dépôt
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../whisper.cpp)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_DIR} whisper-build)

add_library(cestca SHARED cestca.c)
target_link_libraries(cestca whisper log android)
