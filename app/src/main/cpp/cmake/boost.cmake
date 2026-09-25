set(BOOST_VERSION "1.89.0")
set(BOOST_URL "https://github.com/boostorg/boost/releases/download/boost-${BOOST_VERSION}/boost-${BOOST_VERSION}-cmake.tar.xz")

download_and_extract(
    "Boost"
    ${BOOST_VERSION}
    ".tar.xz"
    ${BOOST_URL}
    "67acec02d0d118b5de9eb441f5fb707b3a1cdd884be00ca24b9a73c995511f74"
)

set(_LIME_ORIGINAL_CMAKE_BUILD_TYPE "${CMAKE_BUILD_TYPE}")
set(_LIME_ORIGINAL_CMAKE_BUILD_TYPE_DEFINED FALSE)
if(DEFINED CMAKE_BUILD_TYPE)
    set(_LIME_ORIGINAL_CMAKE_BUILD_TYPE_DEFINED TRUE)
endif()

set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

add_subdirectory(${BOOST_SOURCE_DIR} ${CMAKE_CURRENT_BINARY_DIR}/boost_cmake_build EXCLUDE_FROM_ALL)

file(GLOB BOOST_INCLUDE_DIRS ${BOOST_SOURCE_DIR}/libs/*/include)
set(EXCLUDED
    "graph_parallel"
    "parameter_python"
    "property_map_parallel"
    "test"
    "mpi"
    "python"
    "cobalt"
)
foreach(module ${EXCLUDED})
    list(REMOVE_ITEM BOOST_INCLUDE_DIRS "${BOOST_SOURCE_DIR}/libs/${module}/include")
endforeach()

set(BOOST_MODULES "")
set(BOOST_DEPS "")
foreach(module ${BOOST_INCLUDE_DIRS})
    string(REGEX MATCH "${BOOST_SOURCE_DIR}/libs/([^/]+)/include" matched ${module})
    if(matched AND CMAKE_MATCH_1)
        list(APPEND BOOST_MODULES ${CMAKE_MATCH_1})
        list(APPEND BOOST_DEPS "Boost::${CMAKE_MATCH_1}")
    endif()
endforeach()

target_include_directories(boost_headers SYSTEM INTERFACE
    ${BOOST_SOURCE_DIR}
    ${BOOST_INCLUDE_DIRS}
)

if(_LIME_ORIGINAL_CMAKE_BUILD_TYPE_DEFINED)
    set(CMAKE_BUILD_TYPE "${_LIME_ORIGINAL_CMAKE_BUILD_TYPE}" CACHE STRING "" FORCE)
else()
    unset(CMAKE_BUILD_TYPE CACHE)
    unset(CMAKE_BUILD_TYPE)
endif()

unset(_LIME_ORIGINAL_CMAKE_BUILD_TYPE)
unset(_LIME_ORIGINAL_CMAKE_BUILD_TYPE_DEFINED)
