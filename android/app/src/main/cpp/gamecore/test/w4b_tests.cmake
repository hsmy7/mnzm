# w4b_tests.cmake — **W4-B 批次独占**的 GTest 源清单（W4-00 并行前置批建立骨架）。
#
# 🔴 本文件此后**只有 W4-B 可写**。W4-A / W4-C 与任何其他工作一律不得改本文件。
#
# 用法（W4-B 实施者）：新增本批 GTest 文件后，把文件名追加到下面的
# `W4B_TEST_SOURCES` 列表末尾。`test/CMakeLists.txt` 已 `include` 本文件并把该变量
# 并入 `add_executable(game-core-tests ...)`，无需改 `test/CMakeLists.txt`
# （该文件在 W4-00 后冻结）。
#
# 存在理由：`add_executable` 的源清单原本是一段显式逐文件列举——三个并行批次各自
# 往末尾追加会让 hunk 锚定同一位置，产生必然的文本冲突。切分后各批只改自己的文件。
# 见 docs/parallel-batches-w4/README.md §3.1 项 4。

set(W4B_TEST_SOURCES
    # B2（w3-04 玉符运行时，1766–1769）
    jade_runtime_tx_test.cpp
    # B3（w3-05 行商刷新族，1770–1773）
    merchant_tx_test.cpp
    # B4（w3-12 实裁面：1843；登记项见 diplomacy_selfheal_tx.h 头注）
    diplomacy_selfheal_tx_test.cpp
)
