# game-core 性能基准门禁（重构方案 2026-09-17 R1 收官 bench——G1 证明）
#
# 独立于 game-core-tests 的可执行目标：bench 翻译单元内的全局
# operator new 计数替换（malloc 后端）不进主测试二进制。
# 开关：GAMECORE_BUILD_BENCH（kover 模式——本地默认 OFF、CI 显式 ON，
# 见上层 CMakeLists.txt 与 .github/workflows/ci.yml）。

add_executable(game-core-bench
    accrual_segment_bench_test.cpp
    phase_settlement_bench_test.cpp
    rest_domain_diff_bench_test.cpp
)

target_link_libraries(game-core-bench PRIVATE game-core gtest_main)
target_compile_features(game-core-bench PRIVATE cxx_std_20)

include(GoogleTest)
# 串行 + 互斥属性：bench 是墙钟口径门禁，与其它测试并行会因 CPU/缓存争抢
# 产生假红（并行度由 ctest -j 决定，属性保证无论 -j 取何值都不并行重叠）。
# RUN_SERIAL    —— 本用例执行期间不跑其它用例
# RESOURCE_LOCK —— 三个 bench 用例共用一把锁，彼此也串行
gtest_discover_tests(game-core-bench
    PROPERTIES
        RUN_SERIAL TRUE
        RESOURCE_LOCK gamecore_bench
)
