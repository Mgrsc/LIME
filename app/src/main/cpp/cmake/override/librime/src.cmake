set(RIME_SRC_DIR ${RIME_SOURCE_DIR}/src)

aux_source_directory(${RIME_SRC_DIR} api_src)
aux_source_directory(${RIME_SRC_DIR}/rime rime_base_src)
aux_source_directory(${RIME_SRC_DIR}/rime/algo rime_algo_src)
list(REMOVE_ITEM rime_algo_src "${RIME_SRC_DIR}/rime/algo/syllabifier.cc")
list(APPEND rime_algo_src "${RIME_JNI_ROOT}/src_override/rime/algo/syllabifier.cc")
aux_source_directory(${RIME_SRC_DIR}/rime/config rime_config_src)
aux_source_directory(${RIME_SRC_DIR}/rime/dict rime_dict_src)
list(REMOVE_ITEM rime_dict_src "${RIME_SRC_DIR}/rime/dict/table.cc")
list(APPEND rime_dict_src "${RIME_JNI_ROOT}/src_override/rime/dict/table.cc")
list(REMOVE_ITEM rime_dict_src "${RIME_SRC_DIR}/rime/dict/dictionary.cc")
list(APPEND rime_dict_src "${RIME_JNI_ROOT}/src_override/rime/dict/dictionary.cc")
list(REMOVE_ITEM rime_dict_src "${RIME_SRC_DIR}/rime/dict/user_dictionary.cc")
list(APPEND rime_dict_src "${RIME_JNI_ROOT}/src_override/rime/dict/user_dictionary.cc")
list(REMOVE_ITEM rime_dict_src "${RIME_SRC_DIR}/rime/dict/dict_compiler.cc")
list(APPEND rime_dict_src "${RIME_JNI_ROOT}/src_override/rime/dict/dict_compiler.cc")
list(REMOVE_ITEM rime_dict_src "${RIME_SRC_DIR}/rime/dict/prism.cc")
list(APPEND rime_dict_src "${RIME_JNI_ROOT}/src_override/rime/dict/prism.cc")
aux_source_directory(${RIME_SRC_DIR}/rime/gear rime_gears_src)
list(REMOVE_ITEM rime_gears_src "${RIME_SRC_DIR}/rime/gear/script_translator.cc")
list(APPEND rime_gears_src "${RIME_JNI_ROOT}/src_override/rime/gear/script_translator.cc")
list(REMOVE_ITEM rime_gears_src "${RIME_SRC_DIR}/rime/gear/memory.cc")
list(APPEND rime_gears_src "${RIME_JNI_ROOT}/src_override/rime/gear/memory.cc")
aux_source_directory(${RIME_SRC_DIR}/rime/lever rime_levers_src)
if(rime_plugins_library)
    aux_source_directory(${RIME_SOURCE_DIR}/plugins rime_plugins_src)
endif()

set(rime_core_module_src
    ${rime_api_src}
    ${rime_base_src}
    ${rime_config_src}
)

set(rime_dict_module_src
  ${rime_algo_src}
  ${rime_dict_src}
)

set(rime_src
    ${api_src}
    ${rime_core_module_src}
    ${rime_dict_module_src}
    ${rime_gears_src}
    ${rime_levers_src}
    ${rime_plugins_objs}
    ${rime_plugins_src}
)

set(REQUIRED_TARGETS
    "Threads::Threads"
    ${BOOST_DEPS}
    "yaml-cpp"
    "marisa"
    "leveldb"
    "libopencc"
)

foreach(dep_target ${REQUIRED_TARGETS})
    if(TARGET ${dep_target})
        get_target_property(target_type ${dep_target} TYPE)
        message(STATUS "Target ${dep_target} exists (type: ${dep_target})")
    else()
        message(FATAL_ERROR "Target `${dep_target}` does not exist!")
    endif()
endforeach()

add_library(rime-static STATIC ${rime_src})

target_include_directories(rime-static PUBLIC
    ${RIME_JNI_ROOT}/src_override
    ${RIME_SOURCE_DIR}/include
    ${RIME_SOURCE_DIR}/src
)
target_include_directories(rime-static SYSTEM PRIVATE
    ${RIME_JNI_ROOT}/src_override
    ${RIME_SOURCE_DIR}/include
    ${RIME_SOURCE_DIR}/src
    ${RIME_BUILD_DIR}/src
    ${OPENCC_SOURCE_DIR}/src
    ${MARISA_TRIE_SOURCE_DIR}/include
    ${JNI_INCLUDE_DIR}
)

get_target_property(rime_includes rime-static INCLUDE_DIRECTORIES)
message(STATUS "rime-static includes: ${rime_includes}")

target_link_libraries(rime-static ${REQUIRED_TARGETS})

set_target_properties(rime-static PROPERTIES
    POSITION_INDEPENDENT_CODE ON
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
    OUTPUT_NAME "rime" PREFIX "lib"
    ARCHIVE_OUTPUT_DIRECTORY ${RIME_BUILD_DIR}/lib
)
install(TARGETS rime-static DESTINATION ${CMAKE_INSTALL_FULL_LIBDIR})

set_property(SOURCE ${RIME_SRC_DIR}/rime/setup.cc
    PROPERTY COMPILE_DEFINITIONS
    "RIME_EXTRA_MODULES=${RIME_SETUP_EXTRA_MODULES}"
)
