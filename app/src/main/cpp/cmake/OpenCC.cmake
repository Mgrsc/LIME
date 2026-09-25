set(OPENCC_VERSION "1.1.9")
set(OPENCC_URL "https://github.com/BYVoid/OpenCC/archive/refs/tags/ver.${OPENCC_VERSION}.tar.gz")

download_and_extract(
    "OpenCC"
    ${OPENCC_VERSION}
    ".tar.gz"
    ${OPENCC_URL}
    "ad4bcd8d87219a240a236d4a55c9decd2132a9436697d2882ead85c8939b0a99"
)

set(USE_SYSTEM_MARISA ON CACHE BOOL "" FORCE)
set(LIBMARISA marisa CACHE STRING "" FORCE)

add_subdirectory(${OPENCC_SOURCE_DIR} ${CMAKE_CURRENT_BINARY_DIR}/opencc_build EXCLUDE_FROM_ALL)

add_to_include(OpenCC "${OPENCC_SOURCE_DIR}/src")

unset(USE_SYSTEM_MARISA CACHE)
unset(LIBMARISA CACHE)
