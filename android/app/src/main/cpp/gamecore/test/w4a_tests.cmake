# w4a_tests.cmake — **W4-A 批次独占**的 GTest 源清单（W4-00 并行前置批建立骨架）。
#
# 🔴 本文件此后**只有 W4-A 可写**。W4-B / W4-C 与任何其他工作一律不得改本文件。
#
# 用法（W4-A 实施者）：新增本批 GTest 文件后，把文件名追加到下面的
# `W4A_TEST_SOURCES` 列表末尾。`test/CMakeLists.txt` 已 `include` 本文件并把该变量
# 并入 `add_executable(game-core-tests ...)`，无需改 `test/CMakeLists.txt`
# （该文件在 W4-00 后冻结）。
#
# 存在理由：`add_executable` 的源清单原本是一段显式逐文件列举——三个并行批次各自
# 往末尾追加会让 hunk 锚定同一位置，产生必然的文本冲突。切分后各批只改自己的文件。
# 见 docs/parallel-batches-w4/README.md §3.1 项 4。

set(W4A_TEST_SOURCES
    # w3-01 弟子操作面事务族（W4-A 第一子批）
    disciple_ops_tx_test.cpp
)
