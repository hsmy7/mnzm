#include <gtest/gtest.h>

#include <cmath>
#include <memory>
#include <fstream>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

#include "gamecore/game_core.h"

// ============================================================
// memory_trim_test — MR1 Phase 1 内存批次（P1.3/P1.5/P1.6/P1.7）
//
// 守护目标（memory-refactor-implementation-plan §第四部分 Phase 1）：
//   1. P1.5/B-6：账本 cap 与 import 同源——import 与结算 tick 双路径
//      均被 normalizeLedgers 单一常量源裁剪（禁第二处字面量）；
//   2. P1.3：trim 水位命令投递 → 引擎线程结算边界消费；CRITICAL 有
//      真实动作（账本 shrink_to_fit 归还 OS）且不空转；越界档位防御；
//   3. P1.6：jbytesToString null bytes 检查 + RAII release（源码结构
//      断言——桌面 ctest 无 JNIEnv，行为面由结构守卫锁定）；
//   4. P1.7：m_pendingDraws reserve（源码结构断言）。
// ============================================================

namespace gamecore {
namespace {

class MemoryTrimTest : public ::testing::Test {
protected:
    void SetUp() override {
        clock_.setNowMs(1'700'000'000'000L);
        core_ = std::make_unique<GameCore>(&clock_, &logger_);
        GameCoreConfig config;
        config.systemSeed = 42;
        config.seedInitialized = true;
        ASSERT_TRUE(core_->initialize(config));
    }

    /// 导入含 count 条 mail 记录的最小快照（normalizeLedgers 的裁剪对象）
    bool importWithMailRecords(std::size_t count) {
        nlohmann::json mail = nlohmann::json::array();
        for (std::size_t i = 0; i < count; ++i) {
            mail.push_back({{"mailId", "m" + std::to_string(i)}});
        }
        nlohmann::json snapshot;
        snapshot["gameData"] = {{"gameYear", 5}, {"mailRecords", std::move(mail)}};
        return core_->importStateJson(snapshot.dump());
    }

    FixedClock clock_;
    NullLogger logger_;
    std::unique_ptr<GameCore> core_;
};

// ── P1.5/B-6：import 路径 cap（既有语义，回归守卫）────────────────

TEST_F(MemoryTrimTest, ImportPathCapsMailLedger) {
    ASSERT_TRUE(importWithMailRecords(600));
    const auto o = core_->observeLedgers();
    EXPECT_EQ(o.mailCount, 500u);          // MAIL_RECORD_RETENTION（与 MailService.kt 同源）
    // erase 裁剪不改 capacity——正是 CRITICAL shrink 要归还的部分
    EXPECT_GE(o.mailCapacity, o.mailCount);
}

TEST_F(MemoryTrimTest, ImportPathCapsEventLedger) {
    nlohmann::json events = nlohmann::json::array();
    for (int i = 0; i < 320; ++i) {
        events.push_back({{"seq", i}});
    }
    nlohmann::json snapshot;
    snapshot["gameData"] = {{"gameYear", 5}, {"gameEventRecords", std::move(events)}};
    ASSERT_TRUE(core_->importStateJson(snapshot.dump()));
    EXPECT_EQ(core_->observeLedgers().eventCount, 200u);  // GAME_EVENT_RECORDS_LIMIT
}

// ── P1.5/P1.3：结算 tick 边界消费 trim 水位（cap + shrink）────────

TEST_F(MemoryTrimTest, CriticalTrimShrinksLedgerAtSettleBoundary) {
    ASSERT_TRUE(importWithMailRecords(600));
    auto o = core_->observeLedgers();
    ASSERT_EQ(o.mailCount, 500u);
    ASSERT_GT(o.mailCapacity, o.mailCount);   // erase 后 capacity 滞留

    // CRITICAL 投递 → 旬结算边界消费 → normalize + shrink_to_fit
    core_->postMemoryTrim(GameCore::kTrimCritical);
    core_->settleOnePhase();

    o = core_->observeLedgers();
    EXPECT_EQ(o.mailCount, 500u);
    // shrink_to_fit 为「请求」语义，主流实现（libc++/libstdc++/MSVC）均收缩至 size
    //——按实测行为断言归还，防「CRITICAL 空转」回潮
    EXPECT_EQ(o.mailCapacity, o.mailCount)
        << "CRITICAL trim 后账本容量未归还——shrink 钩子失效/水位未消费";
}

TEST_F(MemoryTrimTest, SoftTrimCapsWithoutShrink) {
    ASSERT_TRUE(importWithMailRecords(600));
    const auto before = core_->observeLedgers();
    ASSERT_EQ(before.mailCount, 500u);
    ASSERT_GT(before.mailCapacity, before.mailCount);

    core_->postMemoryTrim(GameCore::kTrimSoft);
    core_->settleOnePhase();

    const auto after = core_->observeLedgers();
    EXPECT_EQ(after.mailCount, 500u);
    EXPECT_EQ(after.mailCapacity, before.mailCapacity)
        << "SOFT 档不应触发 shrink（归还 OS 仅 CRITICAL 压力路径）";
}

TEST_F(MemoryTrimTest, TrimLevelMonotonicUpgradeSurvivesAcrossSettles) {
    ASSERT_TRUE(importWithMailRecords(600));
    // 低档投递先登记，随后高档升级——高档不被去重吞掉
    core_->postMemoryTrim(GameCore::kTrimSoft);
    core_->postMemoryTrim(GameCore::kTrimCritical);
    core_->settleOnePhase();
    EXPECT_EQ(core_->observeLedgers().mailCapacity, core_->observeLedgers().mailCount);

    // 水位消费后回落 NONE：再次 settle 无 shrink 动作也不出错
    ASSERT_TRUE(importWithMailRecords(600));
    core_->settleOnePhase();
    const auto o = core_->observeLedgers();
    EXPECT_EQ(o.mailCount, 500u);
    EXPECT_GT(o.mailCapacity, o.mailCount) << "无水位时不应有 shrink";
}

TEST_F(MemoryTrimTest, OutOfRangeTrimLevelIsIgnored) {
    ASSERT_TRUE(importWithMailRecords(600));
    core_->postMemoryTrim(99);   // 越界档位防御（枚举面 0-3 之外拒绝）
    core_->postMemoryTrim(-1);
    core_->settleOnePhase();
    const auto o = core_->observeLedgers();
    EXPECT_EQ(o.mailCount, 500u);
    EXPECT_GT(o.mailCapacity, o.mailCount) << "越界档位不应触发消费动作";
}

// ── P1.6/P1.7：结构守卫（桌面无 JNIEnv/GPU——源码面锁定）──────────

/// 读主源文件文本（源码根经 CMake 宏注入）
std::string readSource(const std::string& relative) {
    const std::string root = MR1_CPP_ROOT;
    std::ifstream f(root + "/" + relative, std::ios::binary);
    if (!f) return {};
    return std::string(std::istreambuf_iterator<char>(f),
                       std::istreambuf_iterator<char>());
}

TEST(TrimStructureGuardTest, JbytesToStringHasNullBytesCheckAndRaiiInBothBridges) {
    // M-P2-7/R42 + M-P2-8：两处 jbytesToString 均须 ①null bytes 检查
    // ②ScopedByteArrayElements RAII 配对（桌面 ctest 无 JNIEnv，行为面
    // 不可直接执行——按 RNG 红线思路以源码结构守卫锁定）
    for (const auto& file : std::vector<std::string>{
             "GameCoreBridge.cpp", "gamecore/jni/GameCoreJni.cpp"}) {
        const std::string src = readSource(file);
        ASSERT_FALSE(src.empty()) << "源文件不可读: " << file;
        EXPECT_NE(src.find("if (!scoped) return {};"), std::string::npos)
            << file << ": jbytesToString 缺 null bytes（OOM）检查";
        EXPECT_NE(src.find("class ScopedByteArrayElements"), std::string::npos)
            << file << ": 缺 RAII release guard";
    }
}

TEST(TrimStructureGuardTest, PendingDrawsHasReserveHint) {
    // M-P2-3：帧路径唯一堆增长面必须构造期 reserve（常量命名）——
    // reserve 落在 VulkanBackend.h 的内联构造函数体
    const std::string src = readSource("VulkanBackend.h");
    ASSERT_FALSE(src.empty());
    EXPECT_NE(src.find("m_pendingDraws.reserve(kPendingDrawsReserveHint)"),
              std::string::npos)
        << "VulkanBackend.h: m_pendingDraws 缺构造期 reserve";
}

}  // namespace
}  // namespace gamecore
