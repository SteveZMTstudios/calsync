cmake_minimum_required(VERSION 3.22.1)
project(calsync_llama_jni C CXX)

# Path to vendored llama.cpp
# CMAKE_SOURCE_DIR = app/src/main/cpp
set(LLAMA_DIR_RELATIVE "${CMAKE_SOURCE_DIR}/../../../../third_party/llama.cpp")
get_filename_component(LLAMA_DIR ${LLAMA_DIR_RELATIVE} ABSOLUTE)

# Enforce C++17 for the entire project
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fPIC -ffile-prefix-map=${LLAMA_DIR}=. -ffile-prefix-map=${CMAKE_BINARY_DIR}=.")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fPIC -ffile-prefix-map=${LLAMA_DIR}=. -ffile-prefix-map=${CMAKE_BINARY_DIR}=.")

# Reproducible build fixes
add_compile_options("-ffile-prefix-map=${LLAMA_DIR}=.")
add_compile_options("-ffile-prefix-map=${CMAKE_BINARY_DIR}=.")
add_link_options("LINKER:--build-id=none")

if(NOT EXISTS ${LLAMA_DIR})
    message(FATAL_ERROR "llama.cpp not found at ${LLAMA_DIR}")
endif()

message(STATUS "Using llama.cpp from: ${LLAMA_DIR}")

# Configure llama.cpp build options
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "llama: build tests" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "llama: build examples" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "llama: build server" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "llama: build common" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "llama: build tools" FORCE)
set(LLAMA_LLAMAFILE OFF CACHE BOOL "llama: disable llamafile sgemm" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "ggml: disable llamafile" FORCE)

# CPU-only optimizations (Vulkan requires additional NDK setup)
set(GGML_OPENMP OFF CACHE BOOL "ggml: use OpenMP" FORCE)

# Android-specific optimizations
if(ANDROID)
    message(STATUS "Building for Android ABI: ${ANDROID_ABI}")
    
    # Use NEON on ARM64
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_NEON ON CACHE BOOL "ggml: use NEON" FORCE)
        message(STATUS "Enabling NEON for arm64-v8a")
    endif()
endif()

# Add llama.cpp as subdirectory
add_subdirectory(${LLAMA_DIR} ${CMAKE_BINARY_DIR}/llama)

# Create the JNI library
add_library(llama_jni SHARED llama_jni.cpp)

# Ensure C++17
target_compile_features(llama_jni PRIVATE cxx_std_17)

# Link llama
if(TARGET llama)
    target_link_libraries(llama_jni PRIVATE llama)
    message(STATUS "Linked against llama target")
elseif(TARGET llama-static)
    target_link_libraries(llama_jni PRIVATE llama-static)
    message(STATUS "Linked against llama-static target")
else()
    message(FATAL_ERROR "llama.cpp target not found (expected 'llama' or 'llama-static')")
endif()

# Link Android log library
find_library(log-lib log)
if(log-lib)
    target_link_libraries(llama_jni PRIVATE ${log-lib})
    message(STATUS "Linked Android log library")
else()
    message(FATAL_ERROR "Android log library not found")
endif()

# Include paths
target_include_directories(llama_jni PRIVATE
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}
)

# Compiler flags
if(MSVC)
    target_compile_options(llama_jni PRIVATE /W4 /permissive-)
else()
    target_compile_options(llama_jni PRIVATE
        -Wall -Wextra -Wpedantic
        -Wno-unused-parameter
        -Wno-unused-variable
    )
endif()

message(STATUS "llama_jni library configured successfully")

