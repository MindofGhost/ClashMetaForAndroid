cmake_minimum_required(VERSION 3.0)

set(CORE_SOURCE "${CMAKE_CURRENT_SOURCE_DIR}/../../foss/golang/clash")
execute_process(
  COMMAND git -C "${CORE_SOURCE}" rev-parse --short=7 HEAD
  OUTPUT_VARIABLE COMMIT_HASH
  OUTPUT_STRIP_TRAILING_WHITESPACE
  RESULT_VARIABLE CORE_GIT_RESULT
)
if(NOT CORE_GIT_RESULT EQUAL 0)
  message(FATAL_ERROR "Cannot determine the Clash core commit")
endif()
execute_process(
  COMMAND git -C "${CORE_SOURCE}" describe --tags --always HEAD
  OUTPUT_VARIABLE CORE_VERSION
  OUTPUT_STRIP_TRAILING_WHITESPACE
)
string(REPLACE "_" "-" CORE_VERSION "${CORE_VERSION}")

# Reconfigure when the core changes, even if C/C++ sources are unchanged.
execute_process(
  COMMAND git -C "${CORE_SOURCE}" rev-parse --absolute-git-dir
  OUTPUT_VARIABLE CORE_GIT_DIR
  OUTPUT_STRIP_TRAILING_WHITESPACE
)
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${CORE_GIT_DIR}/HEAD")
if(EXISTS "${CORE_GIT_DIR}/packed-refs")
  set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${CORE_GIT_DIR}/packed-refs")
endif()
execute_process(
  COMMAND git -C "${CORE_SOURCE}" symbolic-ref --quiet HEAD
  OUTPUT_VARIABLE CORE_GIT_REF
  OUTPUT_STRIP_TRAILING_WHITESPACE
)
if(CORE_GIT_REF)
  execute_process(
    COMMAND git -C "${CORE_SOURCE}" rev-parse --git-path "${CORE_GIT_REF}"
    OUTPUT_VARIABLE CORE_REF_PATH
    OUTPUT_STRIP_TRAILING_WHITESPACE
  )
  get_filename_component(CORE_REF_PATH "${CORE_REF_PATH}" ABSOLUTE BASE_DIR "${CORE_SOURCE}")
  set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${CORE_REF_PATH}")
endif()

string(TIMESTAMP COMPILE_TIME "%y%m%d")
set(GIT_VERSION "${CORE_VERSION}_${COMMIT_HASH}_${COMPILE_TIME}")
message(STATUS "version info = ${GIT_VERSION}")
configure_file("${CMAKE_CURRENT_SOURCE_DIR}/version.h.in" "${CMAKE_CURRENT_BINARY_DIR}/version.h" @ONLY)

project(clash-bridge C)


set(CMAKE_POSITION_INDEPENDENT_CODE on)
set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} -O3")

set(GO_OUTPUT_BASE ${GO_OUTPUT}/${FLAVOR_NAME})

if ("${CMAKE_BUILD_TYPE}" STREQUAL "Debug")
    set(GO_OUTPUT_BASE "${GO_OUTPUT_BASE}Debug")
elseif ("${CMAKE_BUILD_TYPE}" STREQUAL "Release")
    set(GO_OUTPUT_BASE "${GO_OUTPUT_BASE}Release")
elseif ("${CMAKE_BUILD_TYPE}" STREQUAL "RelWithDebInfo")
    set(GO_OUTPUT_BASE "${GO_OUTPUT_BASE}Release")
else ()
    message(FATAL_ERROR "Unknown build type ${CMAKE_BUILD_TYPE}")
endif ()

include_directories(BEFORE "${CMAKE_CURRENT_BINARY_DIR}")
include_directories("${GO_OUTPUT_BASE}/${CMAKE_ANDROID_ARCH_ABI}")
include_directories("${GO_SOURCE}")

link_directories("${GO_OUTPUT_BASE}/${CMAKE_ANDROID_ARCH_ABI}")

add_library(bridge SHARED main.c jni_helper.c bridge_helper.c)
target_link_libraries(bridge log clash)
