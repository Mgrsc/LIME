set(PLUGIN_SRC_DIR ${LIBRIME_PREDICT_SOURCE_DIR}/src)

file(GLOB PLUGIN_SOURCES ${PLUGIN_SRC_DIR}/*.cc)

add_library(rime-predict-objs OBJECT
    ${PLUGIN_SOURCES}
)

target_include_directories(rime-predict-objs PRIVATE
    ${PLUGIN_SRC_DIR}
    ${RIME_SOURCE_DIR}/include
    ${RIME_SOURCE_DIR}/src
    ${MARISA_TRIE_SOURCE_DIR}/include
    ${JNI_INCLUDE_DIR}
)

target_link_libraries(rime-predict-objs PRIVATE ${BOOST_DEPS})

set(plugin_modules "predict")
set(plugin_objs $<TARGET_OBJECTS:rime-predict-objs>)
