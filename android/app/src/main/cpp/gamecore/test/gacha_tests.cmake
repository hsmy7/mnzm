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
    # 寻访抽卡核心：前置校验零消费/保底第10抽/十连原子/历史环/品阶截断/满仓转邮件/
    # 解锁描述符/RNG 消费序与分区隔离
    gacha_pull_test.cpp
    # 星级乘区（口径 A）：星级表/稀疏反查/战斗与修炼两侧的乘区落位与截断
    star_zone_test.cpp
)
