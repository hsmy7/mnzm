# ============================================================
# game-core — 纯 C++ 游戏引擎核心
#
# 设计约束（关键）：
#   1. 零 Android 依赖：不得 include android/*、不得依赖 JNI——
#      桌面（Linux/Windows/macOS）均可编译，CI 用桌面工具链直接跑 GTest
#      （目标平台以 Android 为主，核心保持可移植；iOS 暂缓）
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
    src/gameview_encode.cpp
    src/disciple_store.cpp
    # W4 三批次并行的分派端口（W4-00 并行前置批建立；各批只写自己那个文件，
    # 从而消除「三批同时往 execute_dispatch.cpp 链尾追加分支」的必然冲突）。
    # 见 docs/parallel-batches-w4/README.md §3.1 项 3。
    src/dispatch_w4a.cpp
    src/dispatch_w4b.cpp
    src/dispatch_w4c.cpp
    # W4-D 汇流波（串行收口）第四端口——同一模式对并行切分结构的一次性延续。
    src/dispatch_w4d.cpp
)

target_include_directories(game-core PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party
)

target_compile_features(game-core PUBLIC cxx_std_20)

# 与 NDK 默认对齐的编译选项：-Wall/-Wextra 告警；异常/RTTI 未显式禁用（依赖 NDK/libc++
# 行为）。注：历史注释原称"禁异常/RTTI"，实际未加 -fno-exceptions/-fno-rtti 旗标，
# 与实现一致（代码仅用注入式平台接口，不依赖平台异常）。
target_compile_options(game-core PRIVATE
    $<$<CXX_COMPILER_ID:MSVC>:/W4>
    $<$<NOT:$<CXX_COMPILER_ID:MSVC>>:-Wall -Wextra -Wno-unused-parameter>
)

# ── FP 确定性钉死（R0.2）────────────────────────────────────────────
# 浮点位一致由编译器选项保证而非测试巧合：
#   - GCC/Clang：-ffp-contract=off 禁止乘加融合（FMA）——arm64 clang 默认
#     允许 contraction，与 x86-64 桌面 CI 产物位漂移（对拍红线的根因面）
#   - MSVC：/fp:precise 对齐桌面桥语义（默认即 off，显式声明防策略漂移）
# PUBLIC：消费方（JNI 桥层、桌面对拍桥）继承同一策略——桥层若有浮点
# 中间计算（如 UV/几何换算）也与引擎同口径。
target_compile_options(game-core PUBLIC
    $<$<CXX_COMPILER_ID:MSVC>:/fp:precise>
    $<$<NOT:$<CXX_COMPILER_ID:MSVC>>:-ffp-contract=off>
)

# ── 测试（仅桌面/CI；Android 构建不启用）──────────────────────────
option(GAMECORE_BUILD_TESTS "Build game-core unit tests (desktop/CI only)" OFF)
if(GAMECORE_BUILD_TESTS)
    enable_testing()
    add_subdirectory(test)
endif()

# ── 性能基准门禁（R1 收官 bench，G1 证明）───────────────────────────
# 沿用 kover 开关模式：本地默认 OFF（可关），CI 必跑（ci.yml 显式置 ON）。
# 独立可执行目标：bench 内的全局 operator new 计数替换不进主测试二进制。
option(GAMECORE_BUILD_BENCH "Build game-core benchmark gate (malloc count + timing; CI on)" OFF)
if(GAMECORE_BUILD_BENCH AND GAMECORE_BUILD_TESTS)
    add_subdirectory(test/bench)
endif()
