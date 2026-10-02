cmake_minimum_required(VERSION 3.22.1)
project(nusamesh_codec2 C)

set(CODEC2_SRC ../../../../third_party/codec2/src)
set(CODEC2_GENERATED ../../nativeInterop/codec2-generated)
add_library(nusamesh_codec2 SHARED
    codec2_jni.c
    ${CODEC2_SRC}/codec2.c
    ${CODEC2_SRC}/codec2_fft.c
    ${CODEC2_SRC}/dump.c
    ${CODEC2_SRC}/interp.c
    ${CODEC2_SRC}/kiss_fft.c
    ${CODEC2_SRC}/kiss_fftr.c
    ${CODEC2_SRC}/lpc.c
    ${CODEC2_SRC}/lsp.c
    ${CODEC2_SRC}/mbest.c
    ${CODEC2_SRC}/nlp.c
    ${CODEC2_SRC}/newamp1.c
    ${CODEC2_SRC}/pack.c
    ${CODEC2_SRC}/phase.c
    ${CODEC2_SRC}/postfilter.c
    ${CODEC2_SRC}/quantise.c
    ${CODEC2_SRC}/sine.c
    ${CODEC2_GENERATED}/codebook.c
    ${CODEC2_GENERATED}/codebookd.c
    ${CODEC2_GENERATED}/codebookjmv.c
    ${CODEC2_GENERATED}/codebookge.c
    ${CODEC2_GENERATED}/codebooknewamp1.c
    ${CODEC2_GENERATED}/codebooknewamp1_energy.c
)
target_compile_definitions(nusamesh_codec2 PRIVATE
    CODEC2_MODE_EN_DEFAULT=0 CODEC2_MODE_3200_EN=1
)
target_link_libraries(nusamesh_codec2 PRIVATE log m)
target_include_directories(nusamesh_codec2 PRIVATE
    ${CODEC2_SRC}
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)
