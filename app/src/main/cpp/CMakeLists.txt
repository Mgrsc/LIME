# SPDX-FileCopyrightText: 2026 Bitfennec Authors & Upstream RIME community
# SPDX-License-Identifier: BSD-3-Clause

cmake_minimum_required(VERSION 3.22)
project(lime_engine VERSION 0.9.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)
set(BUILD_TEST OFF)
set(BUILD_STATIC ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

set(CMAKE_POLICY_VERSION_MINIMUM 3.5)

# Keep Debug input latency representative across Rime and its dependencies.
# Preserve debug symbols and tracing; do not define NDEBUG here.
add_compile_options("$<$<CONFIG:Debug>:-O2>")

set(RIME_JNI_ROOT ${CMAKE_CURRENT_SOURCE_DIR})
list(APPEND CMAKE_MODULE_PATH ${RIME_JNI_ROOT}/cmake)

set(DOWNLOAD_PATH "${RIME_JNI_ROOT}/Downloads")
if(NOT EXISTS ${DOWNLOAD_PATH})
    file(MAKE_DIRECTORY ${DOWNLOAD_PATH})
endif()

set(JNI_DEPS_DIR ${RIME_JNI_ROOT}/deps)
if(NOT EXISTS ${JNI_DEPS_DIR})
    file(MAKE_DIRECTORY ${JNI_DEPS_DIR})
endif()

set(JNI_INCLUDE_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated/include")
file(MAKE_DIRECTORY "${JNI_INCLUDE_DIR}")

include(Helper)
include(boost)
include(LevelDB)
include(yaml-cpp)
include(marisa-trie)
include(OpenCC)
include(librime)

set(JNI_SOURCE_DIR ${RIME_JNI_ROOT}/jni)
set(JNI_LIB_NAME "lime_engine")

set(JNI_SOURCES
    ${JNI_SOURCE_DIR}/lime_jni.cc
)

add_library(${JNI_LIB_NAME} SHARED
    ${JNI_SOURCES}
)

set_target_properties(${JNI_LIB_NAME} PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
    POSITION_INDEPENDENT_CODE ON
)

target_include_directories(${JNI_LIB_NAME} PRIVATE
    ${JNI_SOURCE_DIR}
    ${JNI_INCLUDE_DIR}
    ${RIME_JNI_ROOT}/src_override
    ${LIBRIME_SOURCE_DIR}/src
    ${LIBRIME_SOURCE_DIR}/include
)

target_link_libraries(${JNI_LIB_NAME} PRIVATE
    rime-static
    c++_static
    log
    android
)

target_compile_definitions(${JNI_LIB_NAME} PRIVATE
    -DLOG_TAG="LimeEngine"
)

target_compile_options(${JNI_LIB_NAME} PRIVATE
    -fvisibility=hidden
)

target_link_options(${JNI_LIB_NAME} PRIVATE
    -Wl,--gc-sections
    -Wl,--as-needed
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
)

if(CMAKE_BUILD_TYPE MATCHES "^(Release|RelWithDebInfo)$")
    target_compile_definitions(${JNI_LIB_NAME} PRIVATE -DNDEBUG)
    target_compile_options(${JNI_LIB_NAME} PRIVATE -O3 -flto)
    target_link_options(${JNI_LIB_NAME} PRIVATE -flto)
endif()
