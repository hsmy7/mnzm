# w4d_tests.cmake — **W4-D 汇流波（串行收口）**的 GTest 源清单
#（延续 W4-00 并行前置批的切分模式；w4a/w4b/w4c 同款第四分片）。
#
# 🔴 本文件此后**只有 W4-D（收口人）可写**。
#
# 用法：新增 W4-D GTest 文件后，把文件名追加到下面的 `W4D_TEST_SOURCES` 列表末尾。
# `test/CMakeLists.txt` 已 `include` 本文件并把该变量并入
# `add_executable(game-core-tests ...)`。

set(W4D_TEST_SOURCES
    # w3-11 月年编排残差：引导领奖事务（判定序/零写入/抽取位/条件语义/注册表锚点）
    guide_reward_tx_test.cpp
    # D4 续批·弟子通道收口：交谈效果事务（逐位语义/clamp 边界/无操作零写入/零抽取/端口形状）
    chat_effect_tx_test.cpp
    # D4 续批·任务域收口：任务派遣事务（模板快照/槽位清理/状态重置/零抽取/端口形状）
    mission_start_tx_test.cpp
)
