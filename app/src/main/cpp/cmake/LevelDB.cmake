set(LEVELDB_VERSION "1.23")
set(LEVELDB_URL "https://github.com/google/leveldb/archive/refs/tags/${LEVELDB_VERSION}.tar.gz")

download_and_extract(
    "LevelDB"
    ${LEVELDB_VERSION}
    ".tar.gz"
    ${LEVELDB_URL}
    "9a37f8a6174f09bd622bc723b55881dc541cd50747cbd08831c2a82d620f6d76"
)

set(LEVELDB_BUILD_TESTS OFF CACHE BOOL "Build LevelDB's unit tests")
set(LEVELDB_BUILD_BENCHMARKS OFF CACHE BOOL "Build LevelDB's benchmarks")

add_subdirectory(${LEVELDB_SOURCE_DIR} ${CMAKE_CURRENT_BINARY_DIR}/leveldb_build EXCLUDE_FROM_ALL)

unset(LEVELDB_BUILD_TESTS)
unset(LEVELDB_BUILD_BENCHMARKS)
