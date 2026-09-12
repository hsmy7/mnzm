// ============================================================
// jade_tx.h — 玉符/宗门升级落账族事务（batch-19）
//
// ui-read-surface §4.1「月年编排 —— 玉符、兑换码、宗门升级、邮件附件」
// UI 操作面写者下沉（语义权威 = 各 Kotlin 源文件，判定序与 Kotlin 原实现
// 逐字对齐）。写者审计结论（§2.50）：
//
// 四族中真正可下沉的是**零 RNG 且物品模板已定**的「落账段」——
//  1) 宗门升级（GameEngineSectLevelOps.upgradeSectLevel 写段）
//  2) 宗门等级奖励领取（GameEngineSectLevelOps.writeSectLevelRewards）
//  3) 玉符购买商人刷新（GameEngineJadePurchaseOps.purchaseMerchantRefresh）
//  4) 玉符购买突破率加成（GameEngineJadePurchaseOps.purchaseBreakthroughBonus）
//
// 未下沉（Kotlin 残差，PR/§2.50 显式登记）：
//  - **物品随机生成**（EquipmentDatabase/ManualDatabase/ItemDatabase/HerbDatabase
//    .generateRandom、RedeemCodeManager.generateDisciple）：C++ data 层只有静态
//    模板表（equipment_db.h 等）**没有生成器**，下沉即需重写整条 MAIL 分区
//    抽取链 → RNG 红线（README §7.1）风险，本批不做。兑换码编排、邮件附件
//    发放因此整体留 Kotlin（其落账与物品发放同事务，拆分即破坏「失败零写入
//    + 凭据保留」契约，见 §2.50 判定）。
//  - **广告/内购/网络投递/TapDB**：纯平台效应（S8 热控同口径），留 Kotlin。
//  - **玉符墙钟发放**（JadeSymbolService.settleGrants/maybeDayReset/checkpointNow）：
//    由单调时钟 + 墙钟驱动，非 UI 操作面，留 Kotlin 服务本体。
//
// 🔴 玉符绝对值覆盖写模型（CLAUDE.md 13.3）——本批下沉 deduct 的**前置条件**：
//   JadeSymbolService 运行时 `totalCount` 是绝对值覆盖写的源头（checkpointNow/
//   settleGrants 都以它覆盖写 GameData.jadeSymbols）。故 C++ 事务扣减
//   jadeSymbols 后，Kotlin 臂**必须**立即调用
//   `JadeSymbolService.syncBalanceFromSnapshot()` 把 totalCount 重锚到 C++ 权威
//   余额（`GameEngineJadePurchaseOps` 两臂均为成功路径固定动作），否则下一次
//   checkpoint 会把旧绝对值写回 → 玉符回涨。守卫测试
//   `JadeSymbolConsumptionGuardTest`（扫描 Kotlin 主源）零改动通过：本批 Kotlin
//   侧不新增任何 `copy(jadeSymbols` / `.jadeSymbols =` 写入点。
//
// 物品发放溢出语义类别（CLAUDE.md 13.3，对抗性审查 C1/C2/C3/H1/H2 教训）：
//  - 宗门等级奖励 = **凭据类**（玩家可重试的领取）→ `overflowMailSuppressed=true`：
//    溢出**不转邮件**，本事务整体回滚失败 → Kotlin 臂幂等重试，凭据保留。
//    与 Kotlin 原路径 `withOverflowMailSuppressed` + Partial/Failure 抛异常回滚
//    逐位等价（避免「邮件 + 重试」重复发放）。
//
// 零 RNG 论证：四事务均为纯确定性状态变换——校验链只读，变更段仅
// worldMapSects 单条目改写 / materials+storageBags 追加 / spiritStones 线性加减 /
// sectLevelClaimRecords upsert / jadeSymbols 线性加减 / merchantRefreshChances
// 累加钳制 / disciple.statusData 单键写。全链无 rng() 调用点（签名级证据：
// 本头 API 不接受 RngManager/种子参数）。Kotlin 原路径（GameEngineSectLevelOps /
// GameEngineJadePurchaseOps）亦零抽取。
//
// 失败零写入：校验链先行；变更段中途失败（仅仓库容量类）以 GameData + 物品轨
// 快照回滚（见 claimSectLevelRewardTx）。任一失败 → 零状态变更 + failure 信封 →
// Kotlin 回退原路径重执行校验链（用户可见文案由 Kotlin 臂产出；本头 message 仅诊断）。
// ============================================================
#pragma once

#include <algorithm>
#include <charconv>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <string>
#include <vector>

#include "gamecore/state/models.h"
#include "gamecore/system/economy.h"    // SpiritStoneWallet / SpiritStoneGrade
#include "gamecore/system/inventory.h"  // addMaterial / addStorageBag / nextItemId

namespace gamecore::system {
namespace jade_tx {

/// 事务结果信封（失败零写入；failure → Kotlin 回退原路径重执行校验链）
struct TxResult {
    bool ok = false;
    std::string errorType;
    std::string message;
};

// ── 事务 1：宗门升级写回（SECT_LEVEL_UPGRADE_TX）─────────────────────────
//
// Kotlin 判定序（GameEngineSectLevelOps.upgradeSectLevel）：
//   玩家宗门存在性（含 ensureGameDataIntegrity 修复重试，留 Kotlin 只读面）
//   → currentLevel >= SectLevel.TOP → AlreadyMaxLevel
//   → targetLevel = currentLevel + 1 → 升级条件校验（最高境界/已占宗门等级，
//     Kotlin 读面与 SectLevelRewardConfig 留 Kotlin）
//   → 写段：worldMapSects 中 isPlayerSect 条目 copy(level = targetLevel,
//     levelName = newLevelName)。
// 本事务只承**写段**（前段为只读校验，经参数 targetLevel 传入）。
// 零 RNG 纯事务。

struct SectLevelUpgradeOutcome {
    TxResult base;
    int32_t newLevel = 0;
    std::string levelName;
};

inline SectLevelUpgradeOutcome upgradeSectLevelTx(state::GameState& st,
                                                   int32_t targetLevel,
                                                   const std::string& levelName) {
    SectLevelUpgradeOutcome out;
    if (targetLevel <= 0) {
        out.base.errorType = "INVALID_LEVEL";
        out.base.message = "target sect level must be positive";
        return out;
    }
    auto& sects = st.gameData.worldMapSects;
    auto it = std::find_if(sects.begin(), sects.end(),
                           [](const state::WorldSect& s) { return s.isPlayerSect; });
    if (it == sects.end()) {
        out.base.errorType = "PLAYER_SECT_NOT_FOUND";
        out.base.message = "no player sect in worldMapSects";
        return out;
    }
    // 目标等级必须是严格升级（Kotlin targetLevel = currentLevel + 1 同判据；
    // 不满足 → Kotlin 校验链兜底，零写入）
    if (targetLevel <= it->level) {
        out.base.errorType = "NOT_AN_UPGRADE";
        out.base.message = "target level not greater than current level";
        return out;
    }
    it->level = targetLevel;
    it->levelName = levelName;
    out.base.ok = true;
    out.newLevel = targetLevel;
    out.levelName = levelName;
    return out;
}

// ── 事务 2：宗门等级奖励领取落账（SECT_LEVEL_CLAIM_TX）────────────────────

/// 兽血材料条目（Kotlin buildSectLevelRewardCards 的 generatedBeastBlood 汇总：
/// Map<模板名, Pair<rarity, count>>；category = 模板 materialCategory，Kotlin 臂
/// 经 BeastMaterialDatabase 反查后传入——C++ data 层只有静态模板表无生成器）
struct ClaimMaterial {
    std::string name;
    int32_t rarity = 1;
    std::string category = "BEAST_HIDE";
    int32_t quantity = 1;
};

/// 储物袋条目（Kotlin aggregate.storageBagRarities：Map<rarity, count>）
struct ClaimStorageBag {
    std::string name;
    int32_t rarity = 1;
    int32_t quantity = 1;
};

struct SectLevelClaimOutcome {
    TxResult base;
    int32_t materialCount = 0;
    int32_t storageBagCount = 0;
    int64_t spiritStones = 0;
    int32_t claimedLevel = 0;
};

/// 宗门等级奖励领取冷却：7 天（Kotlin GameEngineSectLevelOps.WEEK_MS 同值）
inline constexpr int64_t kSectLevelRewardIntervalMs = 7LL * 24 * 60 * 60 * 1000;

/// 宗门等级奖励领取落账（凭据类）。
///
/// Kotlin 判定序（claimSectLevelReward → writeSectLevelRewards）：
///   冷却（sectLevelClaimRecords[level].claimedAtEpochMs + 7 天 > nowMs → 拒绝）
///   → 材料逐条 addMaterial（withOverflowMailSuppressed，Partial/Failure 即整体回滚）
///   → 储物袋逐条 addStorageBag（同上）
///   → totalSpiritStones > 0 时 wallet.add(LOW, "SectLevelReward")（记年度账）
///   → sectLevelClaimRecords upsert（filter { level != level } + 新记录）
/// 全部非 RNG。**凭据类**：溢出抑制 → 不产邮件草稿，失败回滚零写入（凭据保留可重试）。
inline SectLevelClaimOutcome claimSectLevelRewardTx(
    state::GameState& st, int32_t level, int64_t nowMs,
    const std::vector<ClaimMaterial>& materials,
    const std::vector<ClaimStorageBag>& storageBags,
    int64_t spiritStones) {
    SectLevelClaimOutcome out;
    if (level <= 0) {
        out.base.errorType = "INVALID_LEVEL";
        out.base.message = "sect level must be positive";
        return out;
    }
    // 冷却校验（校验链先行——失败零写入）
    for (const auto& rec : st.gameData.sectLevelClaimRecords) {
        if (rec.level != level) continue;
        if (nowMs - rec.claimedAtEpochMs < kSectLevelRewardIntervalMs) {
            out.base.errorType = "ALREADY_CLAIMED";
            out.base.message = "sect level reward already claimed within 7 days";
            return out;
        }
    }

    // 事务快照（失败零写入：GameData 覆盖灵石/年度账/领取记录，物品轨覆盖增删）
    const state::GameData gdBefore = st.gameData;
    const std::vector<state::Material> materialsBefore = st.materials;
    const std::vector<state::StorageBag> storageBagsBefore = st.storageBags;
    const auto rollback = [&]() {
        st.gameData = gdBefore;
        st.materials = materialsBefore;
        st.storageBags = storageBagsBefore;
    };

    // 凭据类：溢出抑制（溢出不转邮件草稿，采整体回滚 + Kotlin 重试补齐）
    OverflowMailCollector overflowMail;
    for (const auto& m : materials) {
        if (m.quantity <= 0) continue;
        state::Material item;
        item.id = nextItemId("gc-mat");
        item.name = m.name;
        item.rarity = m.rarity;
        item.category = m.category;
        item.quantity = m.quantity;
        const auto r = addMaterial(st, item, overflowMail, "sect_level", true);
        if (r.status != InventoryStatus::kSuccess) {
            rollback();
            out.base.errorType = "CAPACITY_INSUFFICIENT";
            out.base.message = "material " + m.name + " exceeds warehouse capacity";
            return out;
        }
        ++out.materialCount;
    }
    for (const auto& b : storageBags) {
        if (b.quantity <= 0) continue;
        state::StorageBag bag;
        bag.id = nextItemId("gc-bag");
        bag.name = b.name;
        bag.rarity = b.rarity;
        bag.quantity = b.quantity;
        const auto r = addStorageBag(st, bag, overflowMail, "sect_level", true);
        if (r.status != InventoryStatus::kSuccess) {
            rollback();
            out.base.errorType = "CAPACITY_INSUFFICIENT";
            out.base.message = "storage bag " + b.name + " exceeds bag capacity";
            return out;
        }
        ++out.storageBagCount;
    }
    if (spiritStones > 0) {
        SpiritStoneWallet::add(st.gameData, spiritStones, SpiritStoneGrade::LOW,
                               "SectLevelReward");
        out.spiritStones = spiritStones;
    }

    // 领取记录 upsert（Kotlin filter { it.level != level } + 新记录 → 追加尾部）
    auto& records = st.gameData.sectLevelClaimRecords;
    records.erase(std::remove_if(records.begin(), records.end(),
                                 [level](const state::SectLevelClaimRecord& r) {
                                     return r.level == level;
                                 }),
                  records.end());
    state::SectLevelClaimRecord rec;
    rec.level = level;
    rec.claimedAtEpochMs = nowMs;
    records.push_back(rec);

    out.base.ok = true;
    out.claimedLevel = level;
    return out;
}

// ── 事务 3/4：玉符购买落账（JADE_PURCHASE_*）─────────────────────────────
//
// 🔴 本事务承接玉符**扣减**（CLAUDE.md 13.3 绝对值覆盖写模型）：调用方
// （Kotlin native 臂）成功后必须立即 `JadeSymbolService.syncBalanceFromSnapshot()`
// 重锚运行时 totalCount，否则 checkpointNow 以旧绝对值覆盖写 → 玉符回涨。
//
// Kotlin 判定序（GameEngineJadePurchaseOps）：
//   上限校验**先于**扣款（达上限不消耗玉符）→ jadeSymbols >= cost → 扣减
//   → 写回（merchantRefreshChances 累加钳制 / disciple.statusData）。

struct JadePurchaseOutcome {
    TxResult base;
    int32_t jadeSymbols = 0;   // 扣减后余额（Kotlin 侧重锚用；亦作对拍证据）
    int32_t value = 0;         // 事务写回值（刷新次数 / 新加成 ×100 取整不适用，见下）
    std::string writtenValue;  // statusData 写回字符串（breakthrough 专用）
};

/// Java Double.toString 最短往返表示（Kotlin `newBonus.toString()` 等价）。
/// std::to_chars 的 shortest round-trip 与 Java Double.toString 在
/// 0.0/0.15/0.3 等本域取值上输出一致（jade_tx_test 逐值锁定）。
inline std::string javaDoubleToString(double v) {
    if (v == 0.0) return "0.0";  // Java：0.0（含 -0.0）
    char buf[32];
    const auto res = std::to_chars(buf, buf + sizeof(buf), v);
    if (res.ec != std::errc()) return "0.0";
    return std::string(buf, res.ptr);
}

/// 商人刷新次数购买落账（GameEngineJadePurchaseOps.purchaseMerchantRefresh）。
/// 判定序：上限校验（>= maxChances → LIMIT，**不扣玉符**）→ 余额校验
/// （jadeSymbols < cost → INSUFFICIENT，不写入）→ 扣减 → 累加钳制。
inline JadePurchaseOutcome purchaseMerchantRefreshTx(state::GameState& st, int32_t cost,
                                                     int32_t perJade,
                                                     int32_t maxChances) {
    JadePurchaseOutcome out;
    if (cost <= 0 || perJade <= 0 || maxChances <= 0) {
        out.base.errorType = "INVALID_PARAMS";
        out.base.message = "cost/perJade/maxChances must be positive";
        return out;
    }
    auto& gd = st.gameData;
    if (gd.merchantRefreshChances >= maxChances) {
        out.base.errorType = "LIMIT_REACHED";
        out.base.message = "merchant refresh chances at max";
        return out;
    }
    if (gd.jadeSymbols < cost) {
        out.base.errorType = "INSUFFICIENT_JADE";
        out.base.message = "insufficient jade symbols";
        return out;
    }
    gd.jadeSymbols -= cost;
    gd.merchantRefreshChances = std::min(gd.merchantRefreshChances + perJade, maxChances);
    out.base.ok = true;
    out.jadeSymbols = gd.jadeSymbols;
    out.value = gd.merchantRefreshChances;
    return out;
}

/// 突破率加成购买落账
/// （GameEngineJadePurchaseOps.purchaseBreakthroughBonus）。
/// 判定序：弟子存在 → 存活 → 上限校验（currentBonus >= maxBonus → LIMIT，
/// 不扣玉符）→ 余额校验 → 扣减 → statusData["adBreakthroughBonus"] 写回。
/// `currentBonus` 解析语义 = Kotlin `statusData[key]?.toDoubleOrNull() ?: 0.0`。
inline JadePurchaseOutcome purchaseBreakthroughBonusTx(
    state::GameState& st, const std::string& discipleId, int32_t cost, double perJade,
    double maxBonus) {
    JadePurchaseOutcome out;
    constexpr const char* kBonusKey = "adBreakthroughBonus";
    if (cost <= 0 || perJade <= 0 || maxBonus <= 0) {
        out.base.errorType = "INVALID_PARAMS";
        out.base.message = "cost/perJade/maxBonus must be positive";
        return out;
    }
    auto& ds = st.disciples;
    const auto row = ds.rowOf(discipleId);
    if (!row) {
        out.base.errorType = "DISCIPLE_NOT_FOUND";
        out.base.message = "disciple not found: " + discipleId;
        return out;
    }
    const state::Disciple current = ds.materialize(*row);
    if (!current.isAlive) {
        out.base.errorType = "DISCIPLE_DEAD";
        out.base.message = "disciple is dead";
        return out;
    }
    double currentBonus = 0.0;
    const auto it = current.statusData.find(kBonusKey);
    if (it != current.statusData.end()) {
        char* end = nullptr;
        const double parsed = std::strtod(it->second.c_str(), &end);
        // Kotlin toDoubleOrNull：解析失败 → null → 0.0（空串/脏值防御）
        currentBonus = (end != nullptr && end != it->second.c_str() &&
                        std::isfinite(parsed))
                           ? parsed
                           : 0.0;
    }
    if (currentBonus >= maxBonus) {
        out.base.errorType = "LIMIT_REACHED";
        out.base.message = "breakthrough bonus at max";
        return out;
    }
    if (st.gameData.jadeSymbols < cost) {
        out.base.errorType = "INSUFFICIENT_JADE";
        out.base.message = "insufficient jade symbols";
        return out;
    }
    st.gameData.jadeSymbols -= cost;
    const double newBonus = std::min(currentBonus + perJade, maxBonus);
    state::Disciple updated = current;
    updated.statusData[kBonusKey] = javaDoubleToString(newBonus);
    ds.upsertDisciple(updated);
    out.base.ok = true;
    out.jadeSymbols = st.gameData.jadeSymbols;
    out.writtenValue = updated.statusData[kBonusKey];
    return out;
}

}  // namespace jade_tx
}  // namespace gamecore::system
