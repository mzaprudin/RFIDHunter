cmake_minimum_required(VERSION 3.22.1)
project(rfidhunter_whisper LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Android Gradle invokes CMake with CMAKE_BUILD_TYPE=Debug for mockDebug.
# Clang then defaults to unoptimized (-O0) code, which makes a base Whisper
# inference take several minutes on the Kirin 970. Keep debug symbols, but
# always optimize the native inference core; Kotlin/Compose remain debuggable.
set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG} -O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O3 -DNDEBUG")

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2 OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

set(WHISPER_SOURCE_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/whisper.cpp)
if (NOT EXISTS ${WHISPER_SOURCE_DIR}/CMakeLists.txt)
    message(FATAL_ERROR "Vendored whisper.cpp is missing. Clone tag v1.9.2 into third_party/whisper.cpp")
endif()

add_subdirectory(${WHISPER_SOURCE_DIR} whisper-build EXCLUDE_FROM_ALL)

add_library(whisper-jni SHARED whisper_jni.cpp)
target_include_directories(whisper-jni PRIVATE ${WHISPER_SOURCE_DIR}/include)
target_link_libraries(whisper-jni PRIVATE whisper log android)
target_compile_options(whisper-jni PRIVATE -fexceptions -frtti)
