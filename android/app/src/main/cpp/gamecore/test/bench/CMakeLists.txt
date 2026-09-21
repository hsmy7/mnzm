# game-core 性能基准门禁（重构方案 2026-09-17 R1 收官 bench——G1 证明）
#
# 独立于 game-core-tests 的可执行目标：bench 翻译单元内的全局
# operator new 计数替换（malloc 后端）不进主测试二进制。
# 开关：GAMECORE_BUILD_BENCH（kover 模式——本地默认 OFF、CI 显式 ON，
# 见上层 CMakeLists.txt 与 .github/workflows/ci.yml）。

add_executable(game-core-bench
    phase_settlement_bench_test.cpp
    rest_domain_diff_bench_test.cpp
)

target_link_libraries(game-core-bench PRIVATE game-core gtest_main)
target_compile_features(game-core-bench PRIVATE cxx_std_20)

include(GoogleTest)
gtest_discover_tests(game-core-bench)
