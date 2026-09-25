set(MARISA_TRIE_VERSION "0.3.1")
set(MARISA_TRIE_URL "https://github.com/s-yata/marisa-trie/archive/refs/tags/v${MARISA_TRIE_VERSION}.tar.gz")

download_and_extract(
    "marisa-trie"
    ${MARISA_TRIE_VERSION}
    ".tar.gz"
    ${MARISA_TRIE_URL}
    "986ed5e2967435e3a3932a8c95980993ae5a196111e377721f0849cad4e807f3"
)

set(ENABLE_STATIC_STDLIB ON CACHE BOOL "")
set(ENABLE_TOOLS OFF CACHE BOOL "")

add_subdirectory(${MARISA_TRIE_SOURCE_DIR} ${CMAKE_CURRENT_BINARY_DIR}/marisa_build EXCLUDE_FROM_ALL)

add_to_include(marisa "${MARISA_TRIE_SOURCE_DIR}/include")

unset(ENABLE_STATIC_STDLIB CACHE)
unset(ENABLE_TOOLS CACHE)
