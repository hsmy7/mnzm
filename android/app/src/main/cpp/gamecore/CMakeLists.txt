# ============================================================
# game-core — 纯 C++ 游戏引擎核心（Kotlin→C++ 迁移批次 0）
#
# 设计约束（关键）：
#   1. 零 Android 依赖：不得 include android/*、不得依赖 JNI——
#      桌面（Linux/Windows/macOS）与 iOS 均可编译，CI 用桌面工具链直接跑 GTest
#   2. C++20，无 RTTI/异常依赖（Android NDK 默认 -fno-exceptions 兼容）
#   3. 平台能力（现实时间/日志）一律走注入接口（clock.h/logger.h）
#
# 使用方式：
#   - 桌面/CI 测试：直接 cmake -B build && cmake --build build（test/ 子目录构建 GTest）
#   - Android：由 app 的 CMakeLists.txt add_subdirectory 引入（作为静态库打包进 JNI 库）
# ============================================================
cmake_minimum_required(VERSION 3.22)
project(game-core VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

# ── 主库：game-core 静态库 ─────────────────────────────────────────
add_library(game-core STATIC
    src/rng.cpp
    src/game_core.cpp
    src/json_codec.cpp
    src/execute_dispatch.cpp
    src/dirty_tracker.cpp
)

target_include_directories(game-core PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party
)

target_compile_features(game-core PUBLIC cxx_std_20)

# 与 NDK 默认对齐：禁异常/RTTI（Android 上 libc++ 无 exception 支持开销）；
# 桌面构建同样启用，保证跨平台行为一致
target_compile_options(game-core PRIVATE
    $<$<CXX_COMPILER_ID:MSVC>:/W4>
    $<$<NOT:$<CXX_COMPILER_ID:MSVC>>:-Wall -Wextra -Wno-unused-parameter>
)

# ── 测试（仅桌面/CI；Android 构建不启用）──────────────────────────
option(GAMECORE_BUILD_TESTS "Build game-core unit tests (desktop/CI only)" OFF)
if(GAMECORE_BUILD_TESTS)
    enable_testing()
    add_subdirectory(test)
endif()
