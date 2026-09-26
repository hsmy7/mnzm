// ============================================================
// gacha_tx.h — 寻访抽卡核心事务（roll / 保底 / 碎片 / 入库 / 历史）
//
// 职责：一笔事务内完成「前置校验 → 扣灵石 → 逐抽 roll（类别 / 品阶 / 候选）→
//      角色入碎片账本 / 物品入仓库 → 写寻访历史 → 回写保底计数 → 回执信封」。
// 端口：ActionId 1871 `GACHA_PULL_ONCE` / 1872 `GACHA_PULL_TEN`，
//      分派在 `src/dispatch_gacha.cpp`（域内独占端口，不并入 execute_dispatch 链）。
// 业务口径权威：`docs/character-gacha-redesign-2026-09-23.md` §4.1 / §15.3，
//      实施口径与拍板记录见 `docs/design/gacha-batches/TASKBOOK-G09.md` §1/§3。
//
// ## 复用而非另开写者（G09 的硬边界）
//   - 碎片入账与升星：**只经** `gacha_fragment.h::addFragment`（账本唯一写者）；
//   - 物品入库：**只经** `inventory.h::addMaterial/addHerb/addSeed`（含年度来源
//     统计与溢出草稿），来源键 = [kTrackingSource]；
//   - 灵石扣减：**只经** `SpiritStoneWallet::deduct`（年报 expenditureByReason 记账）；
//   - 解锁入册：C++ 只出描述符 [PullOutcome::unlockedTemplateIds]，弟子实例化在
//     Kotlin（`DiscipleService.instantiateTemplate` 是唯一入册口，C++ 无弟子模板表，
//     也不应有）。同理 `annualNewDisciples` / `guideCounters` 由 Kotlin 单点自增，
//     **C++ 不得计**（TASKBOOK-G09 D-6：双计即年报凭空多一名）。
//
// ## RNG 契约（对拍命门）
//   - 分区 = `kGacha`(12) 独立抽卡流（TASKBOOK-G09 D-3 已拍板）。**禁止**取 `kSystem`：
//     抽卡次数由玩家点击驱动、无上界，混入结算分区会扰动其既有抽取序；
//   - 消费序逐位钉死（与 Kotlin `GachaPullLedger` 双臂同式）：
//       保底抽   ：`nextInt(角色候选数)` —— 1 次；
//       角色抽   ：`nextInt(100)` 类别 → `nextInt(该类别候选数)` —— 2 次；
//       物品抽   ：`nextInt(100)` 类别 → `nextInt(100)` 品阶 → `nextInt(候选数)` —— 3 次；
//   - **失败零消费**：所有校验（池存在/启用/自洽、余额、抽数）一律先于扣费与掷点，
//     失败信封路径不触碰 RNG、不写任何状态——这是双臂等价与 SL 回档可复现的前提；
//   - 候选序：物品候选 `filter(rarity) → 按模板 id 升序`（**禁依赖表迭代序**，
//     C++ 表序是数据文件数组序、Kotlin Registry 序可能不同，不钉死会两头出货不同）。
//
// ## 原子性（无快照/回滚 ⇒ 靠"前置校验全覆盖"）
// `GameCore::execute` 直进 handler 改 `state_`，没有状态快照/回滚臂，故中途不得有
// 失败分支。写入段（扣费之后）的唯一潜在失败是 `addFragment` 的两条拒绝条件
// （templateId 为空 / 发放数非正）与仓库满——前者已由 [checkPool] 排除（角色候选
// 逐个存在于模板表、发放数恒正），后者按"发放类"语义转邮件而非失败（验收⑤）。
// 因此扣费后不存在失败出口，事务等价于原子。
// ============================================================
#pragma once

#include <algorithm>
#include <cstdint>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

#include "gamecore/data/beast_material_db.h"
#include "gamecore/data/gacha_pool_db.h"
#include "gamecore/data/herb_db.h"
#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/system/economy.h"
#include "gamecore/system/gacha_fragment.h"
#include "gamecore/system/inventory.h"

namespace gamecore::system::gacha_tx {

// ── 口径常量 ───────────────────────────────────────────────────────────
/// 权重总量（`categories[].weightPct` 与 `itemRarityWeights[].weightPct` 各自和恰为此值）
inline constexpr int32_t kWeightTotal = 100;
/// 单笔事务抽数区间（单抽 1 / 十连 10；不做差额部分抽取）
inline constexpr int32_t kMinPullCount = 1;
inline constexpr int32_t kMaxPullCount = 10;
/// 寻访历史环容量（与 Kotlin `GameConfig.Gacha.HISTORY_RING_SIZE` 及配置
/// `gachaDefaults.historyRingSize` **三向一致**，由 `CharacterTemplateGuardTest` 看护）
inline constexpr int32_t kHistoryRingSize = 50;
/// 入库来源内部键（ASCII；玩家可见显示名在 `OverflowMailSender.SOURCE_DISPLAY_NAMES`）
inline constexpr const char* kTrackingSource = "gacha_pull";
/// 灵石扣减原因（年报 `annualExpenditureByReason` 的键；与 Kotlin
/// `SpiritStoneReason.Gacha.key` 同字面量，否则两臂写进两套键）
inline constexpr const char* kWalletReason = "Gacha";
/// 历史条目 category 取值域（Kotlin `GachaHistoryEntry` KDoc 同口径）
inline constexpr const char* kCategoryCharacter = "character";
inline constexpr const char* kCategoryItem = "item";
inline constexpr const char* kCategoryPity = "pity";
/// 保底归属挑选方式（其余取值 = 自选，本批不实现 ⇒ 池不自洽拒绝）
inline constexpr const char* kPickModeRandom = "random";
/// 物品类 itemSource 取值域（与三张模板表的注入段名一致）
inline constexpr const char* kSourceHerbs = "herbs";
inline constexpr const char* kSourceSeeds = "seeds";
inline constexpr const char* kSourceBeastMaterials = "beastMaterials";

/// 单抽结果行（回执 `rows[]`；G11 结果页按数组下标呈现格序，故行内无 slotIndex）
struct PullRow {
    /// `character` / `item` / `pity`
    std::string category;
    /// 角色类与保底抽的模板 id；物品类为空
    std::string templateId;
    /// 物品类的模板 id；角色类为空
    std::string itemId;
    /// 物品类品阶；角色/保底行为 0（角色无品阶语义，星级另经 gachaStarMap 呈现）
    int32_t rarity = 0;
    /// 本次入账户的数量（碎片数或物品数量）
    int32_t count = 0;
    bool isPity = false;
};

/// 事务结果（errorType 非空即失败信封；失败时账本与 RNG 状态零改动）
struct PullOutcome {
    bool ok = false;
    std::string errorType;
    std::string message;
    std::string poolId;
    int64_t pricePaid = 0;
    int64_t spiritStonesAfter = 0;
    int32_t pityAfter = 0;
    std::vector<PullRow> rows;
    /// 本次使模板由 0 星跨到 1 星的角色（Kotlin 据此入册；幂等由限持判定兜底）
    std::vector<std::string> unlockedTemplateIds;
    /// 满仓溢出草稿（Kotlin `InventoryNativeForward` 投递为邮件；不回读即丢件）
    std::vector<OverflowDraft> overflowDrafts;
};

namespace detail {

/// 读保底计数（无键即 0——新档与未抽过的池同语义）
inline int32_t pityBefore(const state::GameData& gd, const std::string& poolId) {
    const auto it = gd.gachaPityCounters.find(poolId);
    return it == gd.gachaPityCounters.end() ? 0 : it->second;
}

/// 绝对月序（寻访历史的时间戳；与 Kotlin `usage.recruitedMonth` 同式）
inline int32_t absoluteMonth(const state::GameData& gd) {
    return gd.gameYear * 12 + gd.gameMonth;
}

/// 池内全部角色候选（各角色类别的 templateIds 按声明序拼接——保底在此集六选一）
inline std::vector<std::string> characterPool(const data::GachaPoolTemplate& pool) {
    std::vector<std::string> ids;
    for (const auto& cat : pool.categories) {
        if (!cat.isCharacter()) continue;
        ids.insert(ids.end(), cat.templateIds.begin(), cat.templateIds.end());
    }
    return ids;
}

/**
 * 某物品来源下指定品阶的候选模板 id（**按 id 升序**）。
 *
 * 排序是双臂等价的必要条件：C++ 表序 = 数据文件数组序，Kotlin 侧 Registry 的枚举序
 * 由其自身决定，两侧只保证同集合同 id 存在、不保证同序。不钉死升序就会两头出货不同。
 */
inline std::vector<std::string> itemCandidates(const std::string& itemSource,
                                               int32_t rarity) {
    std::vector<std::string> ids;
    if (itemSource == kSourceHerbs) {
        for (const auto& t : data::herbTemplates()) {
            if (t.rarity == rarity) ids.push_back(t.id);
        }
    } else if (itemSource == kSourceSeeds) {
        for (const auto& t : data::seedTemplates()) {
            if (t.rarity == rarity) ids.push_back(t.id);
        }
    } else if (itemSource == kSourceBeastMaterials) {
        for (const auto& t : data::beastMaterialTemplates()) {
            if (t.rarity == rarity) ids.push_back(t.id);
        }
    }
    std::sort(ids.begin(), ids.end());
    return ids;
}

/// 类别内可达品阶（池级掷点结果按类别 maxRarity 截断——池内最高品阶投放口径）
inline int32_t clampedRarity(int32_t rarity, int32_t maxRarity) {
    return std::min(rarity, maxRarity);
}

/// 权重列（category / itemRarityWeights 两处的掷点共用）
inline std::vector<int32_t> weightColumn(const std::vector<data::GachaCategory>& cats) {
    std::vector<int32_t> weights;
    weights.reserve(cats.size());
    for (const auto& c : cats) weights.push_back(c.weightPct);
    return weights;
}

inline std::vector<int32_t> weightColumnRarities(
    const std::vector<data::GachaRarityWeight>& rows) {
    std::vector<int32_t> weights;
    weights.reserve(rows.size());
    for (const auto& r : rows) weights.push_back(r.weightPct);
    return weights;
}

}  // namespace detail

/**
 * 加权抽取（**双臂逐位同式**）：一次 `nextInt(kWeightTotal)`，按声明顺序累加
 * weightPct，取第一个满足 `roll < 累计和` 的下标。
 *
 * 权重和必须恰为 [kWeightTotal]——由 [checkPool] 前置保证（配置侧另有守卫），
 * 故末尾兜底分支在生产路径不可达，只为让"万一不自洽"退化成末位而非越界。
 */
inline int32_t weightedPickIndex(rng::DeterministicRng& rng,
                                 const std::vector<int32_t>& weights) {
    const int32_t roll = rng.nextInt(kWeightTotal);
    int32_t accumulated = 0;
    for (size_t i = 0; i < weights.size(); ++i) {
        accumulated += weights[i];
        if (roll < accumulated) return static_cast<int32_t>(i);
    }
    return static_cast<int32_t>(weights.size()) - 1;
}

/**
 * 池自洽校验（零 RNG、零写入）——十连的原子性前提（D-11）。
 *
 * @return 空串 = 通过；否则为失败信封码（PoolNotFound / PoolDisabled /
 *         PoolMalformed）
 */
inline std::string checkPool(const data::GachaPoolTemplate* pool) {
    if (pool == nullptr) return "PoolNotFound";
    const auto& p = *pool;
    if (!p.enabled) return "PoolDisabled";
    if (p.poolId.empty() || p.pricePerPull <= 0) return "PoolMalformed";
    if (p.categories.empty() || p.itemRarityWeights.empty()) return "PoolMalformed";
    if (p.pity.pullThreshold < 1 || p.pity.fragmentCount < 1) return "PoolMalformed";
    // 自选保底属 G13 备选：本批遇到非 random 取值一律拒绝而非静默按随机执行
    if (p.pity.pickMode != kPickModeRandom) return "PoolMalformed";
    if (p.fragmentsPerStar < 1 || p.maxStar < 1) return "PoolMalformed";
    // 保底候选为空 ⇒ nextInt(0) 越界；此处拒绝而非运行期炸
    if (detail::characterPool(p).empty()) return "PoolMalformed";
    // 权重和必须恰为 100：weightedPickIndex 的口径前提（配置侧守卫看同一对常量）
    int32_t categoryWeightSum = 0;
    for (const auto& c : p.categories) categoryWeightSum += c.weightPct;
    if (categoryWeightSum != kWeightTotal) return "PoolMalformed";
    int32_t rarityWeightSum = 0;
    for (const auto& r : p.itemRarityWeights) {
        if (r.rarity < 1) return "PoolMalformed";
        rarityWeightSum += r.weightPct;
    }
    if (rarityWeightSum != kWeightTotal) return "PoolMalformed";

    for (const auto& cat : p.categories) {
        if (cat.kind.empty() || cat.weightPct <= 0) return "PoolMalformed";
        if (cat.isCharacter()) {
            if (cat.templateIds.empty()) return "PoolMalformed";
            // 运行期账本 key 域守卫的配置侧一半：池只允许引用模板表内角色
            for (const auto& id : cat.templateIds) {
                if (data::characterTemplateById(id) == nullptr) return "PoolMalformed";
            }
            continue;
        }
        if (cat.itemSource != kSourceHerbs && cat.itemSource != kSourceSeeds &&
            cat.itemSource != kSourceBeastMaterials) {
            return "PoolMalformed";
        }
        if (cat.maxRarity < 1) return "PoolMalformed";
        // 每个可达品阶桶都必须有候选：十连中途无失败分支的前提（D-11）
        for (const auto& weight : p.itemRarityWeights) {
            if (weight.weightPct <= 0) continue;
            const int32_t rarity = detail::clampedRarity(weight.rarity, cat.maxRarity);
            if (detail::itemCandidates(cat.itemSource, rarity).empty()) {
                return "PoolMalformed";
            }
        }
    }
    return "";
}

namespace detail {

/// 角色入账（普通角色抽与保底抽共用同一入账单点；`isPity` 只决定 category 标签）
inline PullRow grantCharacter(state::GameData& gd, const std::string& templateId,
                              int32_t fragments, bool isPity,
                              std::vector<std::string>& unlocked) {
    const auto grant = gacha_fragment::addFragment(gd, templateId, fragments);
    PullRow row;
    row.category = isPity ? kCategoryPity : kCategoryCharacter;
    row.templateId = templateId;
    row.count = fragments;
    row.isPity = isPity;
    // 解锁判据 = 首次跨过第 1 个门槛（0 星 → 1 星）；满星/存量角色不重复解锁
    if (grant.ok && grant.starBefore < 1 && grant.starAfter >= 1) {
        unlocked.push_back(templateId);
    }
    return row;
}

/// 物品入库（三类各走对应 addXxx；溢出走发放类语义转邮件，抽卡不因满仓失败）
inline PullRow grantItem(state::GameState& state, const std::string& itemSource,
                         const std::string& itemId, int32_t rarity,
                         OverflowMailCollector& overflowMail) {
    PullRow row;
    row.category = kCategoryItem;
    row.itemId = itemId;
    row.rarity = rarity;
    row.count = 1;
    // 实例 id 前缀 gacha-：仓库合并按"名称+品阶+分类"键（StackableItemStore），
    // 实例 id 只在新建堆叠时留痕，Kotlin 臂用 UUID——两侧本就不比对实例 id
    const std::string instanceId = std::string("gacha-") + itemId;
    if (itemSource == kSourceHerbs) {
        for (const auto& t : data::herbTemplates()) {
            if (t.id != itemId) continue;
            state::Herb herb;
            herb.id = instanceId;
            herb.name = t.name;
            herb.rarity = t.rarity;
            herb.category = t.category;
            herb.description = t.description;
            herb.quantity = 1;
            addHerb(state, herb, overflowMail, kTrackingSource, false);
            break;
        }
    } else if (itemSource == kSourceSeeds) {
        for (const auto& t : data::seedTemplates()) {
            if (t.id != itemId) continue;
            state::Seed seed;
            seed.id = instanceId;
            seed.name = t.name;
            seed.rarity = t.rarity;
            seed.description = t.description;
            seed.growTime = t.growTime;
            seed.yield = t.yield;
            seed.quantity = 1;
            addSeed(state, seed, overflowMail, kTrackingSource, false);
            break;
        }
    } else {
        for (const auto& t : data::beastMaterialTemplates()) {
            if (t.id != itemId) continue;
            state::Material material;
            material.id = instanceId;
            material.name = t.name;
            material.rarity = t.rarity;
            material.category = t.materialCategory;
            material.description = t.description;
            material.quantity = 1;
            addMaterial(state, material, overflowMail, kTrackingSource, false);
            break;
        }
    }
    return row;
}

/// 抽一次卡（前置校验与扣费均已完成 ⇒ 本函数无失败出口）
inline PullRow pullSingle(state::GameState& state, rng::DeterministicRng& rng,
                          const data::GachaPoolTemplate& pool, int32_t& pity,
                          std::vector<std::string>& unlocked,
                          OverflowMailCollector& overflowMail) {
    state::GameData& gd = state.gameData;
    pity += 1;
    if (pity >= pool.pity.pullThreshold) {
        // 第 10 抽**本身**即保底：不 roll 类别，随机角色碎片 ×N 后计数归零
        const auto pityCandidates = characterPool(pool);
        const std::string tid =
            pityCandidates[rng.nextInt(static_cast<int32_t>(pityCandidates.size()))];
        PullRow row = grantCharacter(gd, tid, pool.pity.fragmentCount, true, unlocked);
        pity = 0;
        return row;
    }

    const auto weights = weightColumn(pool.categories);
    const auto& cat = pool.categories[weightedPickIndex(rng, weights)];
    if (cat.isCharacter()) {
        const std::string tid =
            cat.templateIds[rng.nextInt(static_cast<int32_t>(cat.templateIds.size()))];
        return grantCharacter(gd, tid, 1, false, unlocked);
    }

    const auto rarityWeights = weightColumnRarities(pool.itemRarityWeights);
    const int32_t rolled =
        pool.itemRarityWeights[weightedPickIndex(rng, rarityWeights)].rarity;
    const int32_t rarity = clampedRarity(rolled, cat.maxRarity);
    const auto candidates = itemCandidates(cat.itemSource, rarity);
    const std::string itemId = candidates[rng.nextInt(static_cast<int32_t>(candidates.size()))];
    return grantItem(state, cat.itemSource, itemId, rarity, overflowMail);
}

/// 追加历史（新在前）并按环容量淘汰——环截断口径与 Kotlin 回退臂逐字同式
inline void pushHistory(state::GameData& gd, const PullRow& row, int32_t monthIndex,
                        const std::string& poolId) {
    state::GachaHistoryEntry entry;
    entry.poolId = poolId;
    entry.category = row.category;
    entry.templateId = row.templateId;
    entry.itemId = row.itemId;
    entry.rarity = row.rarity;
    entry.count = row.count;
    entry.isPity = row.isPity;
    entry.gameMonthIndex = monthIndex;
    gd.gachaHistory.insert(gd.gachaHistory.begin(), entry);
    if (gd.gachaHistory.size() > static_cast<size_t>(kHistoryRingSize)) {
        gd.gachaHistory.resize(static_cast<size_t>(kHistoryRingSize));
    }
}

}  // namespace detail

/**
 * 抽卡事务（单抽 `count=1`；十连在同一笔事务内顺序执行 10 次单抽语义，
 * 共享同一抽卡分区流与同一保底计数，跨十连连续）。
 *
 * 十连一次性校验「余额 ≥ count × 单抽价」并**一次性扣费**，不做差额部分抽取。
 *
 * @param state 引擎权威状态（就地写 wallet / 两张碎片账本 / 保底计数 / 历史 / 仓库）
 * @param rngMgr 分区 RNG 管理器（本事务只消费 `kGacha`，不取其它分区）
 * @param poolId 卡池 id
 * @param count 抽数 ∈ [[kMinPullCount], [kMaxPullCount]]
 */
inline PullOutcome pullTx(state::GameState& state, rng::RngManager& rngMgr,
                          const std::string& poolId, int32_t count) {
    PullOutcome out;
    out.poolId = poolId;
    state::GameData& gd = state.gameData;

    // ── ① 前置校验（零 RNG、零写入）─────────────────────────────────
    if (count < kMinPullCount || count > kMaxPullCount) {
        out.errorType = "InvalidPullCount";
        out.message = "抽数越界: " + std::to_string(count);
        return out;
    }
    const auto* pool = data::gachaPoolById(poolId);
    out.errorType = checkPool(pool);
    if (!out.errorType.empty()) {
        out.message = "卡池校验未通过: " + poolId + " (" + out.errorType + ")";
        return out;
    }
    const int64_t totalCost = static_cast<int64_t>(pool->pricePerPull) * count;
    if (gd.spiritStones < totalCost) {
        out.errorType = "InsufficientSpiritStones";
        out.message = "下品灵石不足: 需 " + std::to_string(totalCost) + " 现有 " +
            std::to_string(gd.spiritStones);
        return out;
    }

    // ── ② 扣费（钱包语义：记年报支出；不自动售卖中/上品灵石）────────
    const auto deduct = SpiritStoneWallet::deduct(gd, totalCost, SpiritStoneGrade::LOW,
                                                  kWalletReason, kWalletReason, false);
    if (deduct.status != DeductStatus::kSuccess) {
        // 前置校验刚查过同一余额、且 autoConvert=false 不改判据 ⇒ 不可达；
        // 仍显式拒绝而不静默扣费（错误必须传播）
        out.errorType = "InsufficientSpiritStones";
        out.message = "钱包拒绝扣减: status=" +
                      std::to_string(static_cast<int32_t>(deduct.status));
        return out;
    }
    out.pricePaid = totalCost;

    // ── ③④⑤⑥ 逐抽：掷点 → 入账/入库 → 历史 → 保底计数 ─────────────
    auto& rng = rngMgr.getRng(rng::RngPartition::kGacha);
    OverflowMailCollector overflowMail;
    int32_t pity = detail::pityBefore(gd, poolId);
    const int32_t monthIndex = detail::absoluteMonth(gd);
    out.rows.reserve(static_cast<size_t>(count));
    for (int32_t i = 0; i < count; ++i) {
        const PullRow row = detail::pullSingle(state, rng, *pool, pity,
                                               out.unlockedTemplateIds, overflowMail);
        detail::pushHistory(gd, row, monthIndex, poolId);
        out.rows.push_back(row);
    }
    gd.gachaPityCounters[poolId] = pity;

    out.ok = true;
    out.pityAfter = pity;
    out.spiritStonesAfter = gd.spiritStones;
    out.overflowDrafts = overflowMail.takeAll();
    return out;
}

namespace detail {

/// 参数盲取（与 `gacha_fragment.h::grantFragmentsTransaction` 同口径：
/// 缺失/类型不符按默认值处理，不抛异常——业务失败由校验链以失败信封表达）
inline std::string readPoolId(const nlohmann::json& params) {
    const auto it = params.find("poolId");
    if (it != params.end() && it->is_string()) return it->get<std::string>();
    return {};
}

}  // namespace detail

/// 单抽事务入口（ActionId `GACHA_PULL_ONCE` 的落点）
inline PullOutcome pullOnceTransaction(state::GameState& state, rng::RngManager& rngMgr,
                                       const nlohmann::json& params) {
    return pullTx(state, rngMgr, detail::readPoolId(params), kMinPullCount);
}

/// 十连事务入口（ActionId `GACHA_PULL_TEN` 的落点）
inline PullOutcome pullTenTransaction(state::GameState& state, rng::RngManager& rngMgr,
                                      const nlohmann::json& params) {
    return pullTx(state, rngMgr, detail::readPoolId(params), kMaxPullCount);
}

}  // namespace gamecore::system::gacha_tx
