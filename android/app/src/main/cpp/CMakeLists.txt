cmake_minimum_required(VERSION 3.22)
project(native-renderer VERSION 1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 需要 Vulkan 1.1（Android 7+ / API 24 起内置 Vulkan 1.0+）
find_package(Vulkan REQUIRED)

if(NOT Vulkan_FOUND)
    message(FATAL_ERROR "Vulkan not found — NDK r25+ required")
endif()

# SPIR-V 着色器头文件路径（构建前已由 gen_header.py 预生成）
set(SHADER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/shaders)

# 主库
add_library(native-renderer SHARED
    VulkanBackend.cpp
    SpriteBatcher.cpp
    TextureAtlas.cpp
    KtxLoader.cpp
    NativeBridge.cpp
)

target_include_directories(native-renderer PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${SHADER_DIR}
    ${Vulkan_INCLUDE_DIRS}
)

target_link_libraries(native-renderer
    ${Vulkan_LIBRARIES}
    android
    log
    z
)

set_target_properties(native-renderer PROPERTIES
    POSITION_INDEPENDENT_CODE ON
)
