# game-core 单元测试（仅桌面/CI 构建；Android 构建不包含）
#
# GTest：vendored 源码（third_party/googletest，自 NDK r27 复制）。
# NDK 版 CMakeLists 依赖 NDK 定制辅助函数（cxx_library），桌面不可直接
# add_subdirectory——此处手写最小构建（gtest-all.cc 单编译单元）。
# 平台差异：Windows/MinGW 无 pthread → GTEST_HAS_PTHREAD=0。

set(GTEST_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/googletest)

add_library(gtest STATIC ${GTEST_ROOT}/src/gtest-all.cc)
target_include_directories(gtest PUBLIC
    ${GTEST_ROOT}/include
    ${GTEST_ROOT}
)
target_compile_definitions(gtest PRIVATE GTEST_HAS_PTHREAD=0)
target_compile_features(gtest PUBLIC cxx_std_20)
target_compile_options(gtest PRIVATE
    $<$<NOT:$<CXX_COMPILER_ID:MSVC>>:-w>          # 抑制 vendored 源码告警
    $<$<CXX_COMPILER_ID:MSVC>:/w>
)

add_library(gtest_main STATIC ${GTEST_ROOT}/src/gtest_main.cc)
target_link_libraries(gtest_main PUBLIC gtest)

add_executable(game-core-tests
    rng_test.cpp
    result_test.cpp
    game_core_test.cpp
    json_codec_test.cpp
    dirty_tracker_test.cpp
    apply_reverse_dirty_test.cpp
    disciple_store_test.cpp
    disciple_store_bench_test.cpp
    equipment_db_test.cpp
    herb_db_test.cpp
    trait_db_test.cpp
    trait_effects_test.cpp
    recipe_db_test.cpp
    beast_material_db_test.cpp
    manual_db_test.cpp
    time_system_test.cpp
    settle_phase_test.cpp
    road_system_test.cpp
    road_compositor_test.cpp
    economy_test.cpp
    inventory_test.cpp
    spirit_field_test.cpp
    disciple_test.cpp
    disciple_factory_test.cpp
    cultivation_test.cpp
    phase_settlement_test.cpp
    month_settlement_test.cpp
    year_settlement_test.cpp
    breakthrough_test.cpp
    lifecycle_test.cpp
    battle_test.cpp
    government_test.cpp
    exploration_test.cpp
    level_generator_test.cpp
    death_handler_test.cpp
    secret_realm_test.cpp
    sect_diplomacy_test.cpp
    slot_cleanup_test.cpp
    redeem_code_test.cpp
    execute_dispatch_test.cpp
    engine_loop_test.cpp
    watchdog_test.cpp
)

target_link_libraries(game-core-tests PRIVATE game-core gtest_main)
target_compile_features(game-core-tests PRIVATE cxx_std_20)

include(GoogleTest)
gtest_discover_tests(game-core-tests)
