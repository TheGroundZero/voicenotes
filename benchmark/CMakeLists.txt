# SPDX-License-Identifier: GPL-3.0-or-later
# The command-line benchmark tool for this machine: whisper.cpp built for the host processor plus the transcription core
# the app uses (app/src/main/cpp/whisper_core.cpp). Built by scripts/benchmark-build.sh.
cmake_minimum_required(VERSION 3.22)
project(vn_bench LANGUAGES C CXX)

set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE ON CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)       # as in the app: ggml's own thread pool
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GIT_EXE "" CACHE FILEPATH "git is not used for build stamps" FORCE)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/whisper.cpp)
file(READ ${WHISPER_DIR}/include/whisper.h WHISPER_HEADER)
if(NOT WHISPER_HEADER MATCHES "whisper_set_audio_ctx")
    message(FATAL_ERROR "whisper.cpp is missing the Voice Notes patch. Run scripts/setup-third-party.sh")
endif()
add_subdirectory(${WHISPER_DIR} whisper.cpp)

set(CORE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/cpp)
add_executable(vn-bench vn_bench.cpp ${CORE_DIR}/whisper_core.cpp)
target_include_directories(vn-bench PRIVATE ${CORE_DIR})
target_link_libraries(vn-bench whisper)
