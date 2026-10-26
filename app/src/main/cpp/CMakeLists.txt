# SPDX-License-Identifier: GPL-3.0-or-later
cmake_minimum_required(VERSION 3.22.1)
project(voicenotes_native LANGUAGES C CXX)

# Inference must be optimised even in debug builds, or transcription is unusably slow.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

# The processor-specific code is built several times (baseline ARMv8.0 up to i8mm, SVE2 and SME) as separate backend
# libraries; ggml checks the phone's processor when the app starts and loads the best one that works on it
# (see ensureBackends in WhisperBridge.kt). This replaces a single build that crashed on phones without the i8mm
# instructions. Needs shared libraries, a shared C++ runtime, and the libraries extracted at install time
# (useLegacyPackaging in app/build.gradle.kts), because ggml loads them by file path.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)

# Reproducible builds: the same source must give the same bytes in any directory. Without these the libraries contain
# the absolute path of the build directory and a build ID, so two builds of one commit differ.
get_filename_component(PROJECT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
add_compile_options("-ffile-prefix-map=${PROJECT_ROOT}=/voicenotes")
add_link_options("-Wl,--build-id=none")
# ggml writes the git commit (and "-dirty" if files changed, which file timestamps can fake) into its library. Stop it from
# asking git, so the library is the same whether it is built from a clone, a tarball or a copy.
set(GIT_EXE "" CACHE FILEPATH "git is not used for build stamps" FORCE)

# Where Android Gradle Plugin collects the libraries to put into the app. ggml sends its backend modules to
# build/bin instead, so they are copied here after they are built.
set(APP_LIB_DIR "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)
file(READ ${WHISPER_DIR}/include/whisper.h WHISPER_HEADER)
if(NOT WHISPER_HEADER MATCHES "whisper_set_audio_ctx")
    message(FATAL_ERROR "whisper.cpp is missing the Voice Notes patch. See third_party/README.md")
endif()
add_subdirectory(${WHISPER_DIR} whisper.cpp)

add_library(whisper_jni SHARED whisper_jni.cpp whisper_core.cpp)
target_link_libraries(whisper_jni whisper log)

get_property(ALL_TARGETS DIRECTORY ${WHISPER_DIR}/ggml/src PROPERTY BUILDSYSTEM_TARGETS)
foreach(target ${ALL_TARGETS})
    get_target_property(target_type ${target} TYPE)
    if(target MATCHES "^ggml-cpu-" AND target_type STREQUAL "MODULE_LIBRARY")
        add_dependencies(whisper_jni ${target})
        if(APP_LIB_DIR)
            add_custom_command(TARGET whisper_jni POST_BUILD
                COMMAND ${CMAKE_COMMAND} -E copy_if_different $<TARGET_FILE:${target}> "${APP_LIB_DIR}/")
        endif()
    endif()
endforeach()
