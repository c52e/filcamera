cmake_minimum_required(VERSION 3.22)
project(filcamera_film LANGUAGES CXX)
option(SPK_STORAGE_PROBES "Include rejected image storage experiment in test builds" OFF)
get_filename_component(ROOT "${CMAKE_CURRENT_LIST_DIR}/../.." ABSOLUTE)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)
set(ENGINE "${CMAKE_CURRENT_LIST_DIR}/engine")
set(SHADERS "${CMAKE_CURRENT_BINARY_DIR}/shaders")
file(MAKE_DIRECTORY "${SHADERS}")
find_program(GLSLC NAMES glslc.exe glslc HINTS "${ANDROID_NDK}/shader-tools/windows-x86_64" "${ANDROID_NDK}/shader-tools/linux-x86_64" "${ANDROID_NDK}/shader-tools/darwin-x86_64" REQUIRED)
find_program(SPIRV_VAL NAMES spirv-val.exe spirv-val HINTS "${ANDROID_NDK}/shader-tools/windows-x86_64" "${ANDROID_NDK}/shader-tools/linux-x86_64" "${ANDROID_NDK}/shader-tools/darwin-x86_64" REQUIRED)

# Preserve the qualified binary for unchanged upstream shaders. Edited sources
# are compiled automatically; never silently use a stale precompiled include.
function(spektra_shader NAME PINNED_SHA BINARY_SHA)
    set(SOURCE "${ENGINE}/gpu/${NAME}.comp")
    set(OUTPUT "${SHADERS}/${NAME}_spv.inc")
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${SOURCE}")
    file(SHA256 "${SOURCE}" SOURCE_SHA)
    if(SOURCE_SHA STREQUAL PINNED_SHA)
        file(SHA256 "${ENGINE}/precompiled/${NAME}_spv.inc" ACTUAL_BINARY_SHA)
        if(NOT ACTUAL_BINARY_SHA STREQUAL BINARY_SHA)
            message(FATAL_ERROR "Qualified SPIR-V changed for ${NAME}; update and qualify the source/binary pair")
        endif()
        add_custom_command(OUTPUT "${OUTPUT}"
            COMMAND ${CMAKE_COMMAND} -E copy_if_different "${ENGINE}/precompiled/${NAME}_spv.inc" "${OUTPUT}"
            DEPENDS "${SOURCE}" "${ENGINE}/precompiled/${NAME}_spv.inc" VERBATIM)
    else()
        message(STATUS "Recompiling modified shader ${NAME}; rerun numerical qualification")
        add_custom_command(OUTPUT "${OUTPUT}"
            COMMAND "${GLSLC}" --target-env=vulkan1.1 -fshader-stage=compute "${SOURCE}" -o "${SHADERS}/${NAME}.spv"
            COMMAND "${SPIRV_VAL}" --target-env vulkan1.1 "${SHADERS}/${NAME}.spv"
            COMMAND "${GLSLC}" --target-env=vulkan1.1 -fshader-stage=compute -mfmt=c "${SOURCE}" -o "${OUTPUT}"
            DEPENDS "${SOURCE}" VERBATIM)
    endif()
    set(SHADER_OUTPUTS ${SHADER_OUTPUTS} "${OUTPUT}" PARENT_SCOPE)
endfunction()
include(PinnedShaders.cmake)
set(DENSITY_MODES 0 1)
if(SPK_STORAGE_PROBES)
    list(APPEND DENSITY_MODES 2)
endif()
foreach(MODE IN LISTS DENSITY_MODES)
    foreach(STAGE IN ITEMS layers sample)
        if(STAGE STREQUAL "layers")
            set(SOURCE "${ENGINE}/gpu/filming_stage.comp")
        else()
            set(SOURCE "${ENGINE}/gpu/grain.comp")
        endif()
        set(OUTPUT "${SHADERS}/density_${STAGE}${MODE}.inc")
        add_custom_command(OUTPUT "${OUTPUT}"
            COMMAND "${GLSLC}" --target-env=vulkan1.1 -DDENSITY_STORAGE=${MODE} -fshader-stage=compute -mfmt=c "${SOURCE}" -o "${OUTPUT}"
            DEPENDS "${SOURCE}" VERBATIM)
        list(APPEND SHADER_OUTPUTS "${OUTPUT}")
    endforeach()
endforeach()
add_custom_command(OUTPUT "${SHADERS}/full_spv.inc"
    COMMAND "${GLSLC}" --target-env=vulkan1.1 -O "${ENGINE}/adapter/full.comp" -o "${SHADERS}/full.spv"
    COMMAND "${SPIRV_VAL}" --target-env vulkan1.1 "${SHADERS}/full.spv"
    COMMAND "${GLSLC}" --target-env=vulkan1.1 -O -mfmt=c "${ENGINE}/adapter/full.comp" -o "${SHADERS}/full_spv.inc"
    DEPENDS "${ENGINE}/adapter/full.comp" VERBATIM)
add_custom_command(OUTPUT "${SHADERS}/full_relaxed_spv.inc"
    COMMAND "${GLSLC}" --target-env=vulkan1.1 -O -DFC_RELAXED=1 "${ENGINE}/adapter/full.comp" -o "${SHADERS}/full_relaxed.spv"
    COMMAND "${SPIRV_VAL}" --target-env vulkan1.1 "${SHADERS}/full_relaxed.spv"
    COMMAND "${GLSLC}" --target-env=vulkan1.1 -O -DFC_RELAXED=1 -mfmt=c "${ENGINE}/adapter/full.comp" -o "${SHADERS}/full_relaxed_spv.inc"
    DEPENDS "${ENGINE}/adapter/full.comp" VERBATIM)
add_custom_target(spektra_shaders DEPENDS ${SHADER_OUTPUTS} "${SHADERS}/full_spv.inc" "${SHADERS}/full_relaxed_spv.inc")

add_library(filmcamera_gpu SHARED IMPORTED)
set_target_properties(filmcamera_gpu PROPERTIES IMPORTED_LOCATION "${ROOT}/.build/raw-vulkan/build/gpu-runtime/libfilmcamera_gpu.so" INTERFACE_INCLUDE_DIRECTORIES "${ROOT}/gpu-runtime")
file(GLOB ENGINE_SOURCES CONFIGURE_DEPENDS "${ENGINE}/gpu/*.cpp" "${ENGINE}/kernels/*.cpp"
    "${ENGINE}/io/*.cpp" "${ENGINE}/model/*.cpp" "${ENGINE}/profiles/*.cpp"
    "${ENGINE}/runtime/*.cpp" "${ENGINE}/runtime/stages/*.cpp")
add_library(spektra_core STATIC "${ENGINE}/spektra.cpp" ${ENGINE_SOURCES} "${ENGINE}/adapter/resident.cpp" film_spatial.cpp)
add_dependencies(spektra_core spektra_shaders)
target_include_directories(spektra_core PUBLIC "${ENGINE}" "${ENGINE}/adapter" "${CMAKE_CURRENT_LIST_DIR}" PRIVATE "${SHADERS}")
target_compile_features(spektra_core PUBLIC cxx_std_17)
target_compile_options(spektra_core PUBLIC -O2 -UNDEBUG)
target_compile_definitions(spektra_core PUBLIC _USE_MATH_DEFINES PRIVATE SPK_ENABLE_VULKAN)
if(SPK_STORAGE_PROBES)
    target_compile_definitions(spektra_core PUBLIC SPK_STORAGE_PROBES)
endif()
find_package(Threads REQUIRED)
target_link_libraries(spektra_core PUBLIC filmcamera_gpu vulkan android log Threads::Threads)
add_library(filcamera_film SHARED bridge.cpp)
target_compile_features(filcamera_film PRIVATE cxx_std_17)
target_compile_options(filcamera_film PRIVATE -O2)
target_link_libraries(filcamera_film PRIVATE spektra_core)
