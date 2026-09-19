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

# 精灵图集生成头目录（TextureAtlas.h 由 build-atlas.mjs --codegen 生成，
# 通过 Gradle externalNativeBuild cmake arguments 注入 -DSPRITE_GENERATED_DIR）
if(NOT DEFINED SPRITE_GENERATED_DIR)
    set(SPRITE_GENERATED_DIR ${CMAKE_CURRENT_SOURCE_DIR})
endif()

# ════════════════════════════════════════════════════════════════
# game-core — 纯 C++ 游戏引擎核心
# 静态库引入；桌面/CI 测试见 gamecore/CMakeLists.txt（GAMECORE_BUILD_TESTS）
# ════════════════════════════════════════════════════════════════
add_subdirectory(gamecore)

# 主库
add_library(native-renderer SHARED
    VulkanBackend.cpp
    GlesBackend.cpp
    SpriteBatcher.cpp
    TextureAtlas.cpp
    KtxLoader.cpp
    SkyBackground.cpp
    NativeBridge.cpp
)

target_include_directories(native-renderer PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/gamecore/include
    ${SHADER_DIR}
    ${SPRITE_GENERATED_DIR}
    ${Vulkan_INCLUDE_DIRS}
)

target_link_libraries(native-renderer
    ${Vulkan_LIBRARIES}
    android
    log
    z
    EGL
    GLESv2
)

# ════════════════════════════════════════════════════════════════
# native-game-core — 游戏引擎 JNI 库
# 独立于渲染库；GameCoreBridge.cpp 是唯一依赖 JNI/Android 的桥层，
# game-core 静态库本体零 Android 依赖（桌面可复用，核心保持可移植）
# ════════════════════════════════════════════════════════════════
add_library(native-game-core SHARED
    GameCoreBridge.cpp
)

target_include_directories(native-game-core PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(native-game-core
    game-core
    android
    log
)

# ════════════════════════════════════════════════════════════════
# FP 确定性钉死（G6 渲染层闭合，重构方案 2026-09-17 R3.3/B11 前置缺陷 B）
#
# R0.2 的 -ffp-contract=off 只加在 game-core（PUBLIC 传导至 native-game-core 与
# 桌面 GTest/对拍桥），**native-renderer 未覆盖**——而 R3.1/R3.2 起场景几何
# （格→世界像素、线宽除 scale、俯视 Y 轴压缩、UV 收缩）全部在 native-renderer 内
# 做浮点运算，arm64 clang 默认可把乘加融合成 FMA ⇒ 桌面顶点流逐位对照绿
# **不等于** 真机位一致（同一输入可能产出末位不同的顶点）。
# 写法沿用 gamecore/CMakeLists.txt 的 MSVC 豁免（桌面工具链无该 GCC/Clang 旗标）。
# ════════════════════════════════════════════════════════════════
target_compile_options(native-renderer PRIVATE
    $<$<CXX_COMPILER_ID:MSVC>:/fp:precise>
    $<$<NOT:$<CXX_COMPILER_ID:MSVC>>:-ffp-contract=off>
)

set_target_properties(native-renderer PROPERTIES
    POSITION_INDEPENDENT_CODE ON
)

# ════════════════════════════════════════════════════════════════
# 显式优化策略（审计 P3-8）：Release 锁 -O2 + section GC + ThinLTO；
# Debug 保持默认。渲染行为不因构建类型分叉（无 #ifdef 行为分叉，
# 审计 §9 PASS 项维持），仅优化级别差异——行为锁定而非依赖 AGP 默认。
# 注：若 NDK r27 ThinLTO 链接异常，退为仅 -O2 + gc-sections（LTO 可选，非目标）。
# ════════════════════════════════════════════════════════════════
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    target_compile_options(native-renderer PRIVATE -O2 -ffunction-sections -fdata-sections)
    target_link_options(native-renderer PRIVATE -Wl,--gc-sections)
    set_target_properties(native-renderer PROPERTIES INTERPROCEDURAL_OPTIMIZATION TRUE)
endif()
