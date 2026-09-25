if(NOT DEFINED LIBRIME_LUA_SOURCE_DIR)
    set(LIBRIME_LUA_SOURCE_DIR "${JNI_DEPS_DIR}/librime-lua-ec52e48")
endif()

if(NOT DEFINED LIBRIME_LUA_THIRDPARTY_SOURCE_DIR)
    set(LIBRIME_LUA_THIRDPARTY_SOURCE_DIR "${JNI_DEPS_DIR}/librime-lua-thirdparty-fa40fad")
endif()

set(LUA_SRC_DIR "${LIBRIME_LUA_THIRDPARTY_SOURCE_DIR}/lua5.4")

if(EXISTS "${LIBRIME_LUA_THIRDPARTY_SOURCE_DIR}/lua5.3")
    file(REMOVE_RECURSE "${LIBRIME_LUA_THIRDPARTY_SOURCE_DIR}/lua5.3")
endif()

if(NOT EXISTS "${LUA_SRC_DIR}/lua.h")
    message(FATAL_ERROR "lua.h not found at: ${LUA_SRC_DIR}/lua.h. Cannot build librime-lua.")
endif()

if(EXISTS "${LUA_SRC_DIR}/lua.h")
    file(GLOB LUA_SOURCES "${LUA_SRC_DIR}/*.c")
    list(REMOVE_ITEM LUA_SOURCES "${LUA_SRC_DIR}/lua.c" "${LUA_SRC_DIR}/luac.c")

    file(GLOB LUA_PLUGIN_SOURCES
        "${LIBRIME_LUA_SOURCE_DIR}/src/*.cc"
        "${LIBRIME_LUA_SOURCE_DIR}/src/lib/*.cc"
        "${LIBRIME_LUA_SOURCE_DIR}/src/lib/*.c"
    )

    add_library(rime-lua-objs OBJECT
        ${LUA_PLUGIN_SOURCES}
        ${LUA_SOURCES}
    )

    target_compile_definitions(rime-lua-objs PRIVATE
        -DLUA_COMPAT_5_3
        -DLUA_USE_POSIX
        -DLUA_USE_DLOPEN
    )

    target_include_directories(rime-lua-objs PRIVATE
        ${LUA_SRC_DIR}
        ${LIBRIME_LUA_SOURCE_DIR}/src
        ${LIBRIME_LUA_SOURCE_DIR}/src/lib
        ${RIME_SOURCE_DIR}/include
        ${RIME_SOURCE_DIR}/src
        ${MARISA_TRIE_SOURCE_DIR}/include
        ${LEVELDB_SOURCE_DIR}/include
        ${JNI_INCLUDE_DIR}
    )

    target_link_libraries(rime-lua-objs PRIVATE ${BOOST_DEPS})

    set(plugin_modules "lua")
    set(plugin_objs $<TARGET_OBJECTS:rime-lua-objs>)
endif()
