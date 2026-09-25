# gacha_tests.cmake — 角色卡池域的 GTest 源清单（沿用 w4X_tests.cmake 的切分模式：
# 域内批次只改自己的清单文件，`test/CMakeLists.txt` 的 add_executable 源列表
# 只需追加一次 `${GACHA_TEST_SOURCES}`，避免多域并行往同一处追加产生冲突）。
#
# 用法：新增本域 GTest 文件后，把文件名追加到下面 `GACHA_TEST_SOURCES` 列表末尾。
# `test/CMakeLists.txt` 已 `include` 本文件并把该变量并入
# `add_executable(game-core-tests ...)`。

set(GACHA_TEST_SOURCES
    # 角色碎片入账：门槛进位/满星累加/拒绝臂零改动/可加性/零 RNG
    gacha_fragment_test.cpp
)
