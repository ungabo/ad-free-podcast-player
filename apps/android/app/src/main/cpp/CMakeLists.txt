cmake_minimum_required(VERSION 3.22.1)
project(whisper_android)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

# Download whisper.cpp source during the configure step.
# Requires internet access on first build; cached after that.
include(FetchContent)
FetchContent_Declare(
    whisper_cpp
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
    GIT_TAG        v1.7.4
    GIT_SHALLOW    TRUE
    GIT_PROGRESS   FALSE
)

# Disable optional components we do not need.
set(GGML_OPENMP      OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE   OFF CACHE BOOL "" FORCE)
set(GGML_METAL       OFF CACHE BOOL "" FORCE)
set(GGML_CUDA        OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(whisper_cpp)

# JNI bridge shared library loaded by WhisperEngine.java
add_library(whisper_jni SHARED whisper_jni.cpp)

target_link_libraries(whisper_jni
    PRIVATE
        whisper
        log
        android
)
