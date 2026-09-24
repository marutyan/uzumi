# ニューラル変換のJNIの橋渡し（libuzumi_neural.so）と、それが使うllama.cppの共有ライブラリを作る。
# Gradleからは呼ばず、tools/neural/build_android.shがNDKのtoolchainで構成してビルドする。
# 生成物はuzumi.neuralArtifactsDirへ置き、app/build.gradle.ktsがjniLibsへ取り込む。
cmake_minimum_required(VERSION 3.22)
project(uzumi_neural LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cppのソース（azooKey fork 66afb885）の場所。Git追跡外にあり、ビルドの手順が渡す。
set(LLAMA_SOURCE_DIR "" CACHE PATH "llama.cpp source directory")
if(NOT EXISTS "${LLAMA_SOURCE_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "LLAMA_SOURCE_DIR does not point to llama.cpp: ${LLAMA_SOURCE_DIR}")
endif()

# 推論に要るライブラリだけを作る。Web UI（npm）、server、例、試験、共通部品は作らない。
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
add_subdirectory("${LLAMA_SOURCE_DIR}" llama.cpp)

add_library(uzumi_neural SHARED uzumi_neural_jni.cpp)
target_link_libraries(uzumi_neural PRIVATE llama)
