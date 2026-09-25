set(YAML_CPP_VERSION "0.8.0")
set(YAML_CPP_URL "https://github.com/jbeder/yaml-cpp/archive/refs/tags/${YAML_CPP_VERSION}.tar.gz")

download_and_extract(
    "yaml-cpp"
    ${YAML_CPP_VERSION}
    ".tar.gz"
    ${YAML_CPP_URL}
    "fbe74bbdcee21d656715688706da3c8becfd946d92cd44705cc6098bb23b3a16"
)

set(YAML_CPP_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(YAML_CPP_BUILD_TOOLS OFF CACHE BOOL "" FORCE)

add_subdirectory(${YAML_CPP_SOURCE_DIR} ${CMAKE_CURRENT_BINARY_DIR}/yaml_cpp_build EXCLUDE_FROM_ALL)

unset(YAML_CPP_BUILD_TESTS CACHE)
unset(YAML_CPP_BUILD_TOOLS CACHE)
