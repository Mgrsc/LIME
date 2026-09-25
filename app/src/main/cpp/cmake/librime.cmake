set(LIBRIME_VERSION "1.17.0")
set(LIBRIME_URL "https://github.com/rime/librime/archive/refs/tags/${LIBRIME_VERSION}.tar.gz")

download_and_extract(
    "librime"
    ${LIBRIME_VERSION}
    ".tar.gz"
    ${LIBRIME_URL}
    "a60274da5d8b8a7187e6c7e9ba5023334ed7bdd182535e93c4e96de8cf188377"
)

# Overlay tracked src_override in-place onto extracted librime source tree
file(GLOB_RECURSE RIME_SRC_OVERRIDE_FILES CONFIGURE_DEPENDS "${RIME_JNI_ROOT}/src_override/rime/*")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${RIME_SRC_OVERRIDE_FILES})
file(COPY "${RIME_JNI_ROOT}/src_override/rime/" DESTINATION "${LIBRIME_SOURCE_DIR}/src/rime")

include(librime-plugins)

# Do not use add_subdirectory(${LIBRIME_SOURCE_DIR}),
# because its find_package() could not find external libraries.
include(override/librime/main)

add_to_include(librime "${LIBRIME_SOURCE_DIR}/src")
