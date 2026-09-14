# w4c_tests.cmake — **W4-C 批次独占**的 GTest 源清单（W4-00 并行前置批建立骨架）。
#
# 🔴 本文件此后**只有 W4-C 可写**。W4-A / W4-B 与任何其他工作一律不得改本文件。
#
# 用法（W4-C 实施者）：新增本批 GTest 文件后，把文件名追加到下面的
# `W4C_TEST_SOURCES` 列表末尾。`test/CMakeLists.txt` 已 `include` 本文件并把该变量
# 并入 `add_executable(game-core-tests ...)`，无需改 `test/CMakeLists.txt`
# （该文件在 W4-00 后冻结）。
#
# 存在理由：`add_executable` 的源清单原本是一段显式逐文件列举——三个并行批次各自
# 往末尾追加会让 hunk 锚定同一位置，产生必然的文本冲突。切分后各批只改自己的文件。
# 见 docs/parallel-batches-w4/README.md §3.1 项 4。

set(W4C_TEST_SOURCES
    # WS-5b 地图冻结：生成即数据 / 存的地形恒优先 / 老档回填幂等 / 段存在性协议
    terrain_freeze_test.cpp
    # w3-06 战斗/探索残差：伤亡残差 / 关卡胜利事务 / 战前突破结算
    battle_residual_tx_test.cpp
    # w3-08 秘境残差：出发换岗 / 到期兜底
    secret_realm_residual_tx_test.cpp
)
