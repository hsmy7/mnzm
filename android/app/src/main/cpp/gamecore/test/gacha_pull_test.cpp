// ============================================================
// gacha_pull_test — 寻访抽卡核心守护（G09）
//
// 守护目标（与 Kotlin `GachaPullLedger` / `DiffGachaPullTest` 同一组口径，
// 两侧各跑一遍——`gamecore/AGENTS.md` 测试双守护）：
//   - 前置校验全覆盖 ⇒ 失败臂**零 RNG 消费、零状态写入**（双臂等价与 SL 复现的前提）
//   - 保底语义：第 10 抽**本身**= 随机角色碎片 ×5、该抽不 roll 类别、计数归零
//   - 十连：一笔事务 10 行回执、一次性扣费、保底跨十连连续
//   - 历史环缓冲：新在前、条数封顶 50
//   - 物品：品阶严格 ≤ 池内 maxRarity；满仓**不失败**而转溢出草稿
//   - 解锁描述符只在首次跨第 1 个门槛时给出（入册本身归 Kotlin）
//   - RNG 消费次数与结果行同构；抽卡只推进 12 号分区，既有分区逐位不动
//
// 数据面：卡池/模板表**读真实配置**（`assets/data/game-data.json`）后注入，
// 而不是在测试里手抄一份概率表——手抄的静态期望表是编译与生成器都抓不到的
// 孤儿面（HANDOVER-3 §2.3 坑 9），改了配置测试还会绿。
// ============================================================
#include <gtest/gtest.h>

#include <algorithm>
#include <cstdint>
#include <fstream>
#include <map>
#include <sstream>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

#include "gamecore/data/data_inject.h"
#include "gamecore/data/gacha_pool_db.h"
#include "gamecore/data/herb_db.h"
#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/system/gacha_tx.h"
#include "gamecore/system/inventory.h"

#include "game_data_json.h"

namespace gamecore {
namespace {

namespace gacha_tx = gamecore::system::gacha_tx;

using gamecore::data::GachaPoolTemplate;
using gamecore::rng::RngManager;
using gamecore::rng::RngPartition;
using gamecore::state::GameData;
using gamecore::state::GameState;

/// 物品行是否命中三张模板表之一且品阶一致（行内不带 itemSource，故按 id 全域查）
bool isKnownItemTemplate(const std::string& itemId, int32_t rarity) {
    for (const auto& t : data::herbTemplates()) {
        if (t.id == itemId) return t.rarity == rarity;
    }
    for (const auto& t : data::seedTemplates()) {
        if (t.id == itemId) return t.rarity == rarity;
    }
    for (const auto& t : data::beastMaterialTemplates()) {
        if (t.id == itemId) return t.rarity == rarity;
    }
    return false;
}

/// 夹具：注入真实卡池配置 + 备足灵石的干净状态 + 已播种的 RNG
class GachaPullTest : public ::testing::Test {
protected:
    void SetUp() override {
        const std::string payload = testsupport::readGameDataJson();
        ASSERT_FALSE(payload.empty())
            << "game-data.json 不存在——先跑 node scripts/gen-game-data.mjs";
        data::resetGameDataStoreForTest();
        ASSERT_TRUE(data::inject::injectFromJson(payload));
        pool_ = data::gachaPoolById(kPoolId);
        ASSERT_NE(nullptr, pool_);
        price_ = pool_->pricePerPull;

        state_ = GameState{};
        // 余额按 2000 抽备足：本文件最长用例是 100 次十连（1000 抽），
        // 抽卡次数是自变量，不该被余额截断成假绿
        state_.gameData.spiritStones = static_cast<int64_t>(price_) * 2000;
        rng_.initSystemSeed(kSeed);
    }

    void TearDown() override { data::resetGameDataStoreForTest(); }

    /// 预置保底计数（跳过前 N 抽的样板流程，直接测保底臂）
    void seedPity(int32_t value) { state_.gameData.gachaPityCounters[kPoolId] = value; }

    /// 抽一次（断言成功并返回结果，失败即把信封码打在断言消息里）
    gacha_tx::PullOutcome pull(int32_t count) {
        auto out = gacha_tx::pullTx(state_, rng_, kPoolId, count);
        EXPECT_TRUE(out.ok) << "抽卡失败: " << out.errorType << " " << out.message;
        return out;
    }

    int32_t pityOf() const {
        const auto it = state_.gameData.gachaPityCounters.find(kPoolId);
        return it == state_.gameData.gachaPityCounters.end() ? 0 : it->second;
    }

    static constexpr const char* kPoolId = "standard";
    static constexpr int64_t kSeed = 20260926LL;

    const GachaPoolTemplate* pool_ = nullptr;
    int32_t price_ = 0;
    GameState state_;
    RngManager rng_;
};

// ── ① 前置校验：失败臂零消费、零写入 ─────────────────────────────────

TEST_F(GachaPullTest, 池不存在_失败且零RNG消费零写入) {
    const auto snapshotBefore = rng_.exportStates();
    const int64_t stonesBefore = state_.gameData.spiritStones;
    const size_t historySizeBefore = state_.gameData.gachaHistory.size();

    const auto out = gacha_tx::pullTx(state_, rng_, "no_such_pool", 1);
    EXPECT_FALSE(out.ok);
    EXPECT_EQ("PoolNotFound", out.errorType);
    EXPECT_EQ(snapshotBefore, rng_.exportStates()) << "失败臂不得消费任何分区";
    EXPECT_EQ(stonesBefore, state_.gameData.spiritStones);
    EXPECT_EQ(historySizeBefore, state_.gameData.gachaHistory.size());
    EXPECT_TRUE(state_.gameData.gachaPityCounters.empty()) << "失败臂不得建保底键";
    EXPECT_TRUE(state_.gameData.gachaFragmentCounts.empty());
}

TEST_F(GachaPullTest, 余额不足_失败且不动任何账本) {
    state_.gameData.spiritStones = price_ - 1;
    const auto snapshotBefore = rng_.exportStates();
    const auto out = gacha_tx::pullTx(state_, rng_, kPoolId, 1);
    EXPECT_FALSE(out.ok);
    EXPECT_EQ("InsufficientSpiritStones", out.errorType);
    EXPECT_EQ(snapshotBefore, rng_.exportStates());
    EXPECT_EQ(price_ - 1, state_.gameData.spiritStones);
    EXPECT_EQ(0, pityOf());
}

TEST_F(GachaPullTest, 十连余额只够九抽_整体失败而非差额抽) {
    state_.gameData.spiritStones = static_cast<int64_t>(price_) * 9 + 1;
    const auto out = gacha_tx::pullTx(state_, rng_, kPoolId, 10);
    EXPECT_FALSE(out.ok);
    EXPECT_EQ("InsufficientSpiritStones", out.errorType);
    EXPECT_TRUE(out.rows.empty());
    // 差额部分抽取被明确否决（产品 §4.1「不做差额部分抽取」）：余额一分未动
    EXPECT_EQ(static_cast<int64_t>(price_) * 9 + 1, state_.gameData.spiritStones);
}

TEST_F(GachaPullTest, 抽数越界_失败信封) {
    EXPECT_EQ("InvalidPullCount", gacha_tx::pullTx(state_, rng_, kPoolId, 0).errorType);
    EXPECT_EQ("InvalidPullCount", gacha_tx::pullTx(state_, rng_, kPoolId, 11).errorType);
}

TEST_F(GachaPullTest, 池不自洽一律拒绝_含非随机保底与权重漂移) {
    // 复制真实池后逐项破坏——checkPool 是纯函数，不必重新注入
    auto mutate = [&](auto&& tweak) {
        GachaPoolTemplate bad = *pool_;
        tweak(bad);
        return gacha_tx::checkPool(&bad);
    };
    EXPECT_EQ("PoolDisabled", mutate([](GachaPoolTemplate& p) { p.enabled = false; }));
    EXPECT_EQ("PoolMalformed", mutate([](GachaPoolTemplate& p) { p.pricePerPull = 0; }));
    EXPECT_EQ("PoolMalformed", mutate([](GachaPoolTemplate& p) { p.categories.clear(); }));
    EXPECT_EQ("PoolMalformed",
              mutate([](GachaPoolTemplate& p) { p.pity.pullThreshold = 0; }));
    EXPECT_EQ("PoolMalformed", mutate([](GachaPoolTemplate& p) { p.pity.pickMode = "manual"; }));
    EXPECT_EQ("PoolMalformed",
              mutate([](GachaPoolTemplate& p) { p.itemRarityWeights[0].weightPct += 1; }));
    EXPECT_EQ("PoolMalformed",
              mutate([](GachaPoolTemplate& p) { p.categories[0].weightPct += 1; }));
    EXPECT_EQ("PoolMalformed",
              mutate([](GachaPoolTemplate& p) { p.categories[0].templateIds.clear(); }));
    EXPECT_EQ("PoolMalformed",
              mutate([](GachaPoolTemplate& p) { p.categories[0].templateIds[0] = "ghost"; }));
    EXPECT_EQ("PoolMalformed",
              mutate([](GachaPoolTemplate& p) { p.categories[2].itemSource = "chests"; }));
    // 候选桶为空（该品阶一张模板都没有）必须被**前置**拒绝，而不是十连中途出货失败
    EXPECT_EQ("PoolMalformed", mutate([](GachaPoolTemplate& p) {
        p.categories[2].maxRarity = 99;
        p.itemRarityWeights[0].rarity = 99;
    }));
    EXPECT_EQ("", gacha_tx::checkPool(pool_));
}

// ── ② 保底语义（Q33：第 10 抽本身，不是额外赠送）─────────────────────

TEST_F(GachaPullTest, 第九抽不触发保底_计数逐位推进) {
    seedPity(8);
    const auto out = pull(1);
    EXPECT_FALSE(out.rows[0].isPity);
    EXPECT_NE("pity", out.rows[0].category);
    EXPECT_EQ(9, out.pityAfter);
    EXPECT_EQ(9, pityOf());
}

TEST_F(GachaPullTest, 第十抽本身是随机角色碎片且计数归零) {
    seedPity(pool_->pity.pullThreshold - 1);
    const auto out = pull(1);
    ASSERT_EQ(1u, out.rows.size());
    const auto& row = out.rows[0];
    EXPECT_TRUE(row.isPity);
    EXPECT_EQ("pity", row.category);
    EXPECT_EQ(pool_->pity.fragmentCount, row.count);
    EXPECT_TRUE(row.itemId.empty());
    EXPECT_EQ(0, out.pityAfter);
    EXPECT_EQ(0, pityOf());

    // 保底归属六个角色之一（含已解锁/已满星，不做排除——P-5 拍板）
    const auto poolIds = gacha_tx::detail::characterPool(*pool_);
    ASSERT_NE(poolIds.end(), std::find(poolIds.begin(), poolIds.end(), row.templateId))
        << "保底抽到了池外角色: " << row.templateId;
    // 碎片确实入账到该模板，且只进 fragmentCount 片
    EXPECT_EQ(pool_->pity.fragmentCount, state_.gameData.gachaFragmentCounts[row.templateId]);
    // 保底抽不 roll 类别 ⇒ 该抽只消费一次候选掷点
    EXPECT_TRUE(out.unlockedTemplateIds.empty());
}

TEST_F(GachaPullTest, 保底跨十连连续_恰一条保底行且位置正确) {
    seedPity(7);
    const auto out = pull(10);
    ASSERT_EQ(10u, out.rows.size());
    int32_t pityRows = 0;
    int32_t pityIndex = -1;
    for (size_t i = 0; i < out.rows.size(); ++i) {
        if (out.rows[i].isPity) {
            pityRows++;
            pityIndex = static_cast<int32_t>(i);
        }
    }
    // 7 + 3 = 10 ⇒ 第 3 格（下标 2）是保底；其后 7 抽重新累加到 7
    EXPECT_EQ(1, pityRows);
    EXPECT_EQ(2, pityIndex);
    EXPECT_EQ(7, out.pityAfter);
    EXPECT_EQ(7, pityOf());
}

// ── ③ 扣费与回执 ─────────────────────────────────────────────────────

TEST_F(GachaPullTest, 十连一次性扣费并按钱包语义记年报支出) {
    const int64_t stonesBefore = state_.gameData.spiritStones;
    const auto out = pull(10);
    EXPECT_EQ(static_cast<int64_t>(price_) * 10, out.pricePaid);
    EXPECT_EQ(stonesBefore - out.pricePaid, out.spiritStonesAfter);
    EXPECT_EQ(stonesBefore - out.pricePaid, state_.gameData.spiritStones);
    // 走 SpiritStoneWallet::deduct 而非裸减法：年报必须看见这笔 sink
    const auto it = state_.gameData.annualExpenditureByReason.find(gacha_tx::kWalletReason);
    ASSERT_NE(it, state_.gameData.annualExpenditureByReason.end())
        << "抽卡是主 sink，未记 annualExpenditureByReason 即年报漏项";
    EXPECT_EQ(out.pricePaid, it->second);
    EXPECT_EQ(out.pricePaid, state_.gameData.annualTotalExpenditure);
}

TEST_F(GachaPullTest, 单抽回执字段齐备且与状态一致) {
    const auto out = pull(1);
    ASSERT_EQ(1u, out.rows.size());
    EXPECT_EQ(kPoolId, out.poolId);
    EXPECT_EQ(price_, out.pricePaid);
    EXPECT_EQ(1, out.pityAfter);
    const auto& row = out.rows[0];
    if (row.category == "item") {
        EXPECT_TRUE(row.templateId.empty());
        EXPECT_GT(row.rarity, 0);
    } else {
        EXPECT_EQ(0, row.rarity) << "角色/保底行不带品阶语义（星级另经 gachaStarMap）";
        EXPECT_FALSE(row.templateId.empty());
    }
}

// ── ④ 历史环缓冲（新在前、封顶 50）───────────────────────────────────

TEST_F(GachaPullTest, 历史新在前且条数封顶环容量) {
    const int32_t ring = gacha_tx::kHistoryRingSize;
    for (int32_t i = 0; i < ring + 10; ++i) pull(1);
    ASSERT_EQ(static_cast<size_t>(ring), state_.gameData.gachaHistory.size());

    // 十连的第 10 抽排在最前（同批内也是新在前）
    const auto out = pull(10);
    ASSERT_EQ(static_cast<size_t>(ring), state_.gameData.gachaHistory.size());
    const auto& newest = state_.gameData.gachaHistory.front();
    const auto& lastRow = out.rows.back();
    EXPECT_EQ(lastRow.category, newest.category);
    EXPECT_EQ(lastRow.templateId, newest.templateId);
    EXPECT_EQ(lastRow.itemId, newest.itemId);
    EXPECT_EQ(lastRow.isPity, newest.isPity);
    EXPECT_EQ(state_.gameData.gameYear * 12 + state_.gameData.gameMonth,
              newest.gameMonthIndex);
}

// ── ⑤ 物品：品阶截断与满仓 ───────────────────────────────────────────

TEST_F(GachaPullTest, 物品行品阶严格落在池内maxRarity且命中真实模板) {
    int32_t itemRows = 0;
    for (int32_t i = 0; i < 100; ++i) {
        for (const auto& row : pull(10).rows) {
            if (row.category != "item") continue;
            itemRows++;
            EXPECT_GE(row.rarity, 1);
            EXPECT_LE(row.rarity, 4) << "五阶及以上不得进寻访（Q37 池内最高四阶）";
            EXPECT_EQ(1, row.count);
            EXPECT_TRUE(isKnownItemTemplate(row.itemId, row.rarity))
                << "物品行未命中模板表: " << row.itemId;
        }
    }
    EXPECT_GT(itemRows, 0) << "100 次十连一件物品都没出，说明类别权重失效";
}

TEST_F(GachaPullTest, 满仓抽卡不失败_溢出转邮件草稿) {
    // 用其他类型把槽位预算吃光：herb 的可用上限 = maxSlots - 其他类型数
    const int32_t cap = system::computeMaxSlots(state_);
    ASSERT_GT(cap, 0);
    for (int32_t i = 0; i < cap; ++i) {
        state::Material filler;
        filler.id = "filler-" + std::to_string(i);
        filler.name = "填充材料" + std::to_string(i);
        filler.rarity = 1;
        filler.category = "BEAST_HIDE";
        filler.quantity = 1;
        state_.materials.push_back(filler);
    }
    const int64_t stonesBefore = state_.gameData.spiritStones;

    // 发放类语义：满仓只转邮件，绝不把整抽判失败（验收⑤）
    const auto out = pull(10);
    EXPECT_TRUE(out.ok);
    EXPECT_EQ(10u, out.rows.size());
    EXPECT_EQ(stonesBefore - out.pricePaid, state_.gameData.spiritStones);

    int32_t itemRows = 0;
    for (const auto& row : out.rows) {
        if (row.category == "item") itemRows++;
    }
    if (itemRows > 0) {
        ASSERT_FALSE(out.overflowDrafts.empty()) << "物品被抽中却没入库也没草稿 = 丢件";
        for (const auto& draft : out.overflowDrafts) {
            EXPECT_EQ(gacha_tx::kTrackingSource, draft.source);
            EXPECT_EQ(1, draft.quantity);
        }
    }
}

TEST_F(GachaPullTest, 仓库有空位时物品确实入库且草药来源计入年度统计) {
    // 与满仓用例相反的一端：仓库空时物品必须进仓库（统一入库入口的实效证明）
    int32_t itemRows = 0;
    for (int32_t i = 0; i < 20; ++i) {
        for (const auto& row : pull(10).rows) {
            if (row.category == "item") itemRows++;
        }
    }
    ASSERT_GT(itemRows, 0);
    const int32_t stacks = static_cast<int32_t>(
        state_.herbs.size() + state_.seeds.size() + state_.materials.size());
    EXPECT_GT(stacks, 0) << "抽到 " << itemRows << " 件物品却一件都没入库";
    // 草药来源统计由 addHerb 内部按 trackingSource 写入（抽卡侧不得自算）
    if (!state_.herbs.empty()) {
        const auto it = state_.gameData.annualHerbBySource.find(gacha_tx::kTrackingSource);
        ASSERT_NE(it, state_.gameData.annualHerbBySource.end())
            << "草药入库但 annualHerbBySource 无 gacha_pull 键 = 年报漏项";
        EXPECT_GT(it->second, 0);
    }
}

// ── ⑥ 解锁描述符（入册本身归 Kotlin，C++ 只出 id）────────────────────

TEST_F(GachaPullTest, 首次跨一星才给解锁描述符_已解锁再抽不重复给) {
    // 六个模板全部预置到"差 1 片升星"，保底抽必中其一 ⇒ 断言与抽中谁无关
    for (const auto& id : gacha_tx::detail::characterPool(*pool_)) {
        state_.gameData.gachaFragmentCounts[id] = pool_->fragmentsPerStar - 1;
    }
    seedPity(pool_->pity.pullThreshold - 1);
    const auto out = pull(1);
    ASSERT_EQ(1u, out.unlockedTemplateIds.size());
    const std::string unlocked = out.unlockedTemplateIds[0];
    EXPECT_EQ(1, state_.gameData.gachaStarMap[unlocked]);
    // 保底给 fragmentCount 片：99 + 5 = 104 ⇒ 跨一星后余 4 片（不截断、不折算）
    EXPECT_EQ(pool_->pity.fragmentCount - 1, state_.gameData.gachaFragmentCounts[unlocked]);

    // 已解锁的再抽：星级可继续升，但解锁描述符只在 0→1 那一跳给出
    std::vector<std::string> unlockedAgain;
    const auto row = gacha_tx::detail::grantCharacter(state_.gameData, unlocked, 1, false,
                                                      unlockedAgain);
    EXPECT_TRUE(unlockedAgain.empty()) << "已解锁角色被重复报为解锁";
    EXPECT_EQ(1, row.count);
    EXPECT_EQ(1, state_.gameData.gachaStarMap[unlocked]);

    // 满星后继续累加（上限口径由 gacha_fragment_test 细测，这里只证不越界）
    state_.gameData.gachaStarMap[unlocked] = pool_->maxStar;
    std::vector<std::string> atMax;
    gacha_tx::detail::grantCharacter(state_.gameData, unlocked, 1, false, atMax);
    EXPECT_EQ(pool_->maxStar, state_.gameData.gachaStarMap[unlocked]);
    EXPECT_TRUE(atMax.empty());
}

TEST_F(GachaPullTest, 角色命中每次一片_星级账本只在跨门槛时写) {
    int32_t characterRows = 0;
    for (int32_t i = 0; i < 60; ++i) {
        for (const auto& row : pull(10).rows) {
            if (row.category != "character") continue;
            characterRows++;
            EXPECT_EQ(1, row.count) << "角色命中固定得 1 片";
            EXPECT_FALSE(row.isPity);
            EXPECT_TRUE(row.itemId.empty());
        }
    }
    EXPECT_GT(characterRows, 0) << "60 次十连没出过角色，类别权重可疑";

    // 账本 key 域：碎片/星级两本账都只允许出现池内角色（D-15 的运行期面）
    const auto poolIds = gacha_tx::detail::characterPool(*pool_);
    for (const auto& [id, _] : state_.gameData.gachaFragmentCounts) {
        EXPECT_NE(poolIds.end(), std::find(poolIds.begin(), poolIds.end(), id))
            << "碎片账本出现池外 key: " << id;
    }
    for (const auto& [id, star] : state_.gameData.gachaStarMap) {
        EXPECT_NE(poolIds.end(), std::find(poolIds.begin(), poolIds.end(), id))
            << "星级账本出现池外 key: " << id;
        EXPECT_GE(star, 1) << "星级账本稀疏：0 星不得占位";
        EXPECT_LE(star, pool_->maxStar);
    }
}

// ── ⑦ RNG 契约：消费次数同构 + 不外溢 ────────────────────────────────

TEST_F(GachaPullTest, 抽卡只推进12号分区_其余分区逐位不动) {
    const auto before = rng_.exportStates();
    pull(10);
    const auto after = rng_.exportStates();
    ASSERT_EQ(before.size(), after.size());
    for (const auto& [id, value] : before) {
        if (id == static_cast<int32_t>(RngPartition::kGacha)) continue;
        EXPECT_EQ(value, after.at(id)) << "抽卡扰动了分区 " << id << "（红线 1）";
    }
    EXPECT_NE(before.at(static_cast<int32_t>(RngPartition::kGacha)),
              after.at(static_cast<int32_t>(RngPartition::kGacha)))
        << "十连一次都没消费抽卡分区，说明取错了分区";
}

TEST_F(GachaPullTest, 随机消费次数与结果行同构) {
    // 消费序口径（gacha_tx.h 头注释）：保底 1 次、角色 2 次、物品 3 次 nextInt
    // 参照流用同一个 seed 独立推进，逐抽比对快照 ⇒ 消费次数被钉死
    auto reference = RngManager{};
    reference.initSystemSeed(kSeed);
    auto& ref = reference.getRng(RngPartition::kGacha);
    const std::vector<int32_t> catWeights = [&] {
        std::vector<int32_t> w;
        for (const auto& c : pool_->categories) w.push_back(c.weightPct);
        return w;
    }();
    const int32_t pityPoolSize =
        static_cast<int32_t>(gacha_tx::detail::characterPool(*pool_).size());

    for (int32_t i = 0; i < 30; ++i) {
        const int32_t pityBefore = pityOf();
        const auto out = pull(1);
        ASSERT_TRUE(out.ok);
        const auto& row = out.rows[0];
        if (pityBefore + 1 >= pool_->pity.pullThreshold) {
            ref.nextInt(pityPoolSize);                      // 保底：只掷候选
        } else if (row.category == "character") {
            const int32_t catIndex = gacha_tx::weightedPickIndex(ref, catWeights);
            const auto& cat = pool_->categories[catIndex];
            ref.nextInt(static_cast<int32_t>(cat.templateIds.size()));
        } else {
            const int32_t catIndex = gacha_tx::weightedPickIndex(ref, catWeights);
            const auto& cat = pool_->categories[catIndex];
            std::vector<int32_t> rarityWeights;
            for (const auto& r : pool_->itemRarityWeights) rarityWeights.push_back(r.weightPct);
            const int32_t rarityIndex = gacha_tx::weightedPickIndex(ref, rarityWeights);
            const int32_t rarity = std::min(pool_->itemRarityWeights[rarityIndex].rarity,
                                            cat.maxRarity);
            ref.nextInt(static_cast<int32_t>(
                gacha_tx::detail::itemCandidates(cat.itemSource, rarity).size()));
        }
        ASSERT_EQ(ref.snapshot(), rng_.getRng(RngPartition::kGacha).snapshot())
            << "第 " << i << " 抽的消费序与参照流分叉（category=" << row.category << "）";
    }
}

TEST_F(GachaPullTest, 同种子重放同结果_确定性可复现) {
    std::vector<std::string> first;
    for (int32_t i = 0; i < 5; ++i) {
        for (const auto& row : pull(10).rows) {
            first.push_back(row.category + "|" + row.templateId + "|" + row.itemId + "|" +
                std::to_string(row.rarity) + "|" + std::to_string(row.isPity));
        }
    }
    // 复位到同一初始态重放
    state_ = GameState{};
    state_.gameData.spiritStones = static_cast<int64_t>(price_) * 2000;
    rng_.initSystemSeed(kSeed);
    std::vector<std::string> again;
    for (int32_t i = 0; i < 5; ++i) {
        for (const auto& row : pull(10).rows) {
            again.push_back(row.category + "|" + row.templateId + "|" + row.itemId + "|" +
                std::to_string(row.rarity) + "|" + std::to_string(row.isPity));
        }
    }
    EXPECT_EQ(first, again);
}

}  // namespace
}  // namespace gamecore
