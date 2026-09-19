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

# ── W4 三批次并行的测试源清单（W4-00 并行前置批切分）────────────────────
# 每批只改自己的文件，从而消除「三批同时往 add_executable 末尾追加源文件」的
# 必然冲突。include 必须在 add_executable 之前（变量在此定义）。
# 见 docs/parallel-batches-w4/README.md §3.1 项 4。
include(${CMAKE_CURRENT_SOURCE_DIR}/w4a_tests.cmake)
include(${CMAKE_CURRENT_SOURCE_DIR}/w4b_tests.cmake)
include(${CMAKE_CURRENT_SOURCE_DIR}/w4c_tests.cmake)
# W4-D 汇流波（串行收口）第四分片——同一模式的一次性延续。
include(${CMAKE_CURRENT_SOURCE_DIR}/w4d_tests.cmake)

add_executable(game-core-tests
    appointment_tx_test.cpp
    exploration_tx_test.cpp
    rng_test.cpp
    result_test.cpp
    item_id_reseed_test.cpp
    ai_corpse_budget_test.cpp
    disciple_derived_maps_test.cpp
    storage_bag_capacity_test.cpp
    game_core_test.cpp
    json_codec_test.cpp
    dirty_tracker_test.cpp
    dirty_tracker_bench_test.cpp
    # R1.4 列级写屏障（重构方案 2026-09-17 B03 批）：列枚举双射/挂点标脏/
    # 列级导出/与全量树 diff 等价性守卫
    column_dirty_test.cpp
    # R2.4/B09 列级导出生产接线：真实结算列级↔全量双臂等价 + eventFeed 入流守卫
    column_export_equivalence_test.cpp
    # R3.1/B10 SceneStore（native-renderer 场景真相持有者）：六要素导入-读取
    # 逐值往返 + 整表替换/清空语义 + 纪元复位（头文件在 cpp/scene/，见下方 include）
    scene_store_test.cpp
    # R3.2/B10 场景等价性守卫（验收门 4）：新 SceneStore 路径 vs 旧 drawAllTiles
    # 路径顶点流逐位对照（六要素 × 相机三档位 + 生成 UV 表↔Kotlin 公式夹具对照）
    scene_equivalence_test.cpp
    # R3.3/B11 叠加层等价性守卫（验收门 5）：C++ 生成几何 vs 旧 Kotlin 逐 rect
    # 路径顶点流逐位对照（四要素 × 相机三档位 × overlayFlags 组合 + G4 draw call 量化）
    scene_overlay_equivalence_test.cpp
    # R2.1 GameView protobuf 信封编码器 wire format 锁定（重构方案 2026-09-17 B06 批）
    gameview_encode_test.cpp
    manual_recruit_test.cpp
    disciple_store_test.cpp
    disciple_store_bench_test.cpp
    disciple_tx_test.cpp
    equipment_db_test.cpp
    herb_db_test.cpp
    trait_db_test.cpp
    trait_effects_test.cpp
    recipe_db_test.cpp
    beast_material_db_test.cpp
    manual_db_test.cpp
    time_system_test.cpp
    settle_phase_test.cpp
    # R0.2 FP 确定性对拍探针（桌面腿：x86-64 golden 位锁定；真机腿走 JNI）
    determinism_probe_test.cpp
    road_system_test.cpp
    road_compositor_test.cpp
    draw_order_test.cpp
    road_tx_test.cpp
    building_tx_test.cpp
    island_cliff_test.cpp
    terrain_test.cpp
    economy_test.cpp
    inventory_test.cpp
    disciple_lifecycle_tx_test.cpp
    inventory_tx_test.cpp
    spirit_field_test.cpp
    disciple_test.cpp
    disciple_factory_test.cpp
    cultivation_test.cpp
    phase_settlement_test.cpp
    month_settlement_test.cpp
    year_settlement_test.cpp
    breakthrough_test.cpp
    relative_gift_test.cpp
    production_test.cpp
    production_ui_tx_test.cpp
    mission_completion_test.cpp
    ai_sect_ops_test.cpp
    child_birth_test.cpp
    lifecycle_test.cpp
    battle_test.cpp
    battle_calculator_test.cpp
    battle_ai_test.cpp
    battle_execution_test.cpp
    government_test.cpp
    exploration_test.cpp
    level_generator_test.cpp
    death_handler_test.cpp
    secret_realm_test.cpp
    secret_realm_session_test.cpp
    sect_diplomacy_test.cpp
    diplomacy_tx_test.cpp
    slot_cleanup_test.cpp
    redeem_code_test.cpp
    execute_dispatch_test.cpp
    engine_loop_test.cpp
    watchdog_test.cpp
    ecs_entity_test.cpp
    ecs_storage_test.cpp
    ecs_system_test.cpp
    ecs_job_test.cpp
    ecs_disciple_test.cpp
    ecs_npc_test.cpp
    sect_attack_decision_test.cpp
    sect_defense_battle_test.cpp
    sect_conquest_test.cpp
    recruit_tx_test.cpp
    boundary_tx_test.cpp
    government_tx_test.cpp
    jade_tx_test.cpp
    secret_realm_platform_tx_test.cpp
    patrol_tx_test.cpp
    sect_attack_tx_test.cpp
    lock_beast_tx_test.cpp
    storage_bag_tx_test.cpp
    # W4-00 新增：分派覆盖守卫（防「区间写法吞掉动作号」缺陷复发；
    # 见 test/dispatch_guard_test.cpp 与本文件末尾的 _w4 batch 段说明）
    dispatch_guard_test.cpp
    # ── W4 三批次并行的测试源清单（W4-00 并行前置批切分）──────────────────
    # 三个并行批次各写自己的 w4X_tests.cmake（本文件此后冻结，见
    # docs/parallel-batches-w4/README.md §3.1 项 4 与 §5.3）。
    ${W4A_TEST_SOURCES}
    ${W4B_TEST_SOURCES}
    ${W4C_TEST_SOURCES}
    ${W4D_TEST_SOURCES}
)

target_link_libraries(game-core-tests PRIVATE game-core gtest_main)
target_compile_features(game-core-tests PRIVATE cxx_std_20)

# R3.1/B10 SceneStore 场景模块（native-renderer 侧，cpp/scene/）：
# 测试目标追加仓库 cpp 根目录为 include 路径（仅测试可执行目标——game-core
# 静态库本体保持零渲染依赖）。scene_equivalence_test（R3.2）另需 SpriteBatcher。
target_include_directories(game-core-tests PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../..
)
target_sources(game-core-tests PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../../SpriteBatcher.cpp
)

include(GoogleTest)
gtest_discover_tests(game-core-tests)
