cmake_minimum_required(VERSION 3.22.1)

project("stenollm" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp is fetched by scripts/fetch_deps.sh into third_party/llama.cpp
set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp)
if(NOT EXISTS ${LLAMA_SRC}/CMakeLists.txt)
    message(FATAL_ERROR "llama.cpp not found at ${LLAMA_SRC}. Run scripts/fetch_deps.sh first.")
endif()

if(ANDROID_ABI STREQUAL "arm64-v8a")
    # dotprod is available on all ARMv8.2+ phones and on the Apple Silicon emulator.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

add_subdirectory(${LLAMA_SRC} build-llama)

add_library(stenollm SHARED llama_jni.cpp)
target_link_libraries(stenollm llama android log)
