#include <gtest/gtest.h>

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <new>

#include <nlohmann/json.hpp>

#include "gamecore/state/dirty_tracker.h"
#include "gamecore/state/models.h"

// ============================================================
// RestDomainDiffBench — 非弟子域（gameData + 9 集合）每旬标脏段基准
// （B20 镜像残余专项批 B20b，b09 残余③成本中心的量化台架）
//
// 成本中心（b18-remaining-arms-report §2.8(b)）：列级导出
// [ColumnDirtyTracker::exportDirtyTree] 的非弟子域段每旬恒付
// `stateWithoutDisciplesToJson(s)` 全量序列化 + `diffTreeSegments`
// 全树递归 diff——**零变更也照付**（写屏障仅挂弟子域；rest 域
// 变异点散布全仓，屏障迁移 = B09 量级审计工程，B20b 登记推迟）。
//
// 本台架把该成本从定性变实测：同族三口径——
//   ① 序列化-only（stateWithoutDisciplesToJson）；
//   ② 零变更旬段（序列化 + diff vs 相同基线 + 基线推进）——"照付"部分；
//   ③ 单字段变更旬段（spiritStones 变一档，最小真实 delta）。
// 场景状态 = makeSettlementState 同族（D=5000 带实例清单：9 集合
// 实体 1.5 万）+ gameData 消息栏/宗门/建筑容器现实规模（与 Kotlin 侧
// MirrorSegmentProjectionBenchTest.seededGameData 同量级）。
//
// 口径（与 phase_settlement_bench 同族）：heap 分配计数复用同执行体的
// phase_settlement_bench_test.cpp 全局 operator new 替换（本目标单进程
// 内多 TU 共享 gamecore::g_heapAllocCount；gtest_discover 每用例独立
// 进程天然隔离）；分配次数确定性、耗时仅打印（CI 抖动不设断言——
// B20b 未拍板预算门，数字作 WS-1/G2 台账与后续屏障批的验收基线）。
// ============================================================

namespace gamecore {
extern std::atomic<std::uint64_t> g_heapAllocCount;  // 定义于 phase_settlement_bench_test.cpp
}

namespace gamecore {
namespace {

using nlohmann::json;
using state::Disciple;
using state::EquipmentInstance;
using state::GameData;
using state::GameState;
using state::GridBuildingData;
using state::GameEventRecord;
using state::ManualInstance;
using state::WorldSect;

constexpr std::int32_t kBenchDisciples = 5000;

/// 现实规模的 gameData 容器面（与 Kotlin bench seededGameData 同量级：
/// 消息栏 400 / 宗门 40 / 建筑 60——全量序列化成本的大头之一）
void populateGameDataContainers(GameData& gd) {
    gd.gameYear = 37;
    gd.gameMonth = 9;
    gd.gamePhase = 2;
    gd.spiritStones = 9876543;
    for (int i = 0; i < 40; ++i) {
        WorldSect sect;
        sect.id = "w" + std::to_string(i);
        sect.name = "宗门" + std::to_string(i);
        sect.level = i % 6 + 1;
        gd.worldMapSects.push_back(std::move(sect));
    }
    for (int i = 0; i < 60; ++i) {
        GridBuildingData b;
        b.buildingId = "hut";
        b.displayName = "木屋" + std::to_string(i);
        b.gridX = i;
        b.gridY = i * 2;
        gd.placedBuildings.push_back(std::move(b));
    }
    for (int i = 0; i < 400; ++i) {
        GameEventRecord e;
        e.timestamp = 1700000000000LL + i;
        e.eventType = "E" + std::to_string(i % 8);
        e.summary = "事件" + std::to_string(i);
        gd.gameEventRecords.push_back(std::move(e));
    }
}

/// 带实例清单的 9 集合面（与 phase_settlement_bench populateInstances 同构：
/// 5000 功法 + 10000 装备实例 = rest 域实体大头）
void populateRestCollections(GameState& state) {
    state.disciples.manualIds.resize(kBenchDisciples);
    state.disciples.weaponIds.resize(kBenchDisciples);
    state.disciples.armorIds.resize(kBenchDisciples);
    for (std::int32_t n = 1; n <= kBenchDisciples; ++n) {
        const std::string id = std::to_string(n);

        ManualInstance mn;
        mn.id = "m" + id;
        mn.name = "功法" + id;
        mn.type = "MIND";
        mn.ownerId = id;
        mn.isLearned = true;
        state.manualInstances.push_back(mn);
        state.disciples.manualIds[n - 1] = {mn.id};

        EquipmentInstance w;
        w.id = "w" + id;
        w.name = "剑" + id;
        w.slot = "WEAPON";
        w.ownerId = id;
        w.isEquipped = true;
        state.equipmentInstances.push_back(w);
        state.disciples.weaponIds[n - 1] = w.id;

        EquipmentInstance a;
        a.id = "a" + id;
        a.name = "甲" + id;
        a.slot = "ARMOR";
        a.ownerId = id;
        a.isEquipped = true;
        state.equipmentInstances.push_back(a);
        state.disciples.armorIds[n - 1] = a.id;
    }
}

GameState makeRestDomainState() {
    GameState state;
    for (std::int32_t n = 1; n <= kBenchDisciples; ++n) {
        Disciple d;
        d.id = std::to_string(n);
        d.name = "弟子" + std::to_string(n);
        d.realm = 9;
        d.realmLayer = 1;
        d.cultivation = 10.0;
        d.isAlive = true;
        d.spiritRootType = "metal";
        d.age = 16;
        d.lifespan = 80;
        state.disciples.appendDisciple(d);
    }
    populateRestCollections(state);
    populateGameDataContainers(state.gameData);
    return state;
}

/// 列级导出的非弟子域段（exportDirtyTree :596-598 原文形态）：
/// 全量序列化 + 树 diff + 基线推进
void restDomainPhaseSegment(const json& baseline, GameState& state) {
    json cur = stateWithoutDisciplesToJson(state);
    json changed = json::object();
    json removed = json::object();
    state::diffTreeSegments(baseline, cur, state::kNonDiscipleCollections, changed, removed);
    static_cast<void>(changed);
    static_cast<void>(removed);
}

template <typename F>
double bestOfUs(int warmup, int samples, F&& block) {
    for (int i = 0; i < warmup; ++i) block();
    double best = 1e18;
    for (int i = 0; i < samples; ++i) {
        const auto t0 = std::chrono::steady_clock::now();
        block();
        const auto t1 = std::chrono::steady_clock::now();
        const double us =
            std::chrono::duration_cast<std::chrono::nanoseconds>(t1 - t0).count() / 1000.0;
        if (us < best) best = us;
    }
    return best;
}

}  // namespace

// ── ① 序列化-only ──────────────────────────────────────────────
TEST(RestDomainDiffBench, SerializeOnly) {
    GameState state = makeRestDomainState();

    const auto t0 = std::chrono::steady_clock::now();
    json tree = stateWithoutDisciplesToJson(state);
    const auto t1 = std::chrono::steady_clock::now();
    const double us =
        std::chrono::duration_cast<std::chrono::nanoseconds>(t1 - t0).count() / 1000.0;
    std::printf(
        "[RestDomainDiffBench] serialize-only D=5000 full: %.1f us, tree bytes≈%zu\n",
        us, tree.dump().size());
}

// ── ② 零变更旬段（"零变更也照付"的实测）─────────────────────────
TEST(RestDomainDiffBench, ZeroChangePhaseSegment) {
    GameState state = makeRestDomainState();
    const json baseline = stateWithoutDisciplesToJson(state);

    const std::uint64_t allocs = [] {
        GameState st = makeRestDomainState();
        const json base = stateWithoutDisciplesToJson(st);
        g_heapAllocCount.store(0, std::memory_order_relaxed);
        restDomainPhaseSegment(base, st);
        return g_heapAllocCount.load(std::memory_order_relaxed);
    }();

    const double us = bestOfUs(2, 5, [&] {
        json cur = stateWithoutDisciplesToJson(state);
        json changed = json::object();
        json removed = json::object();
        state::diffTreeSegments(baseline, cur, state::kNonDiscipleCollections, changed, removed);
    });

    std::printf(
        "[RestDomainDiffBench] zero-change phase segment D=5000 full: "
        "%llu mallocs, %.1f us（零变更也照付的部分）\n",
        static_cast<unsigned long long>(allocs), us);
}

// ── ③ 单字段变更旬段（最小真实 delta；基线逐轮推进 = 生产导出语义）──
TEST(RestDomainDiffBench, OneFieldChangePhaseSegment) {
    GameState state = makeRestDomainState();
    json baseline = stateWithoutDisciplesToJson(state);

    // 预热 + 计数：spiritStones 变一档 → diff 产出 1 个 gameData 字段
    state.gameData.spiritStones += 1;
    g_heapAllocCount.store(0, std::memory_order_relaxed);
    const auto t0 = std::chrono::steady_clock::now();
    json cur = stateWithoutDisciplesToJson(state);
    json changed = json::object();
    json removed = json::object();
    state::diffTreeSegments(baseline, cur, state::kNonDiscipleCollections, changed, removed);
    baseline = std::move(cur);
    const auto t1 = std::chrono::steady_clock::now();
    const std::uint64_t allocs = g_heapAllocCount.load(std::memory_order_relaxed);
    const double us =
        std::chrono::duration_cast<std::chrono::nanoseconds>(t1 - t0).count() / 1000.0;

    EXPECT_EQ(changed.size(), 1u);  // 仅 spiritStones 一字段（diff 正确性旁证）
    std::printf(
        "[RestDomainDiffBench] one-field-change phase segment D=5000 full: "
        "%llu mallocs, %.1f us\n",
        static_cast<unsigned long long>(allocs), us);
}

}  // namespace gamecore
