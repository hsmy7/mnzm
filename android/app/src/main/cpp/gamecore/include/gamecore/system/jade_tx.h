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
//    .generateRandom）：C++ data 层只有静态
//    模板表（equipment_db.h 等）**没有生成器**，下沉即需重写整条 MAIL 分区
//    抽取链 → RNG 红线（README §7.1）风险，本批不做。兑换码编排、邮件附件
//    发放因此整体留 Kotlin（其落账与物品发放同事务，拆分即破坏「失败零写入
//    + 凭据保留」契约，见 §2.50 判定）。
//  - **广告/内购/网络投递/TapDB**：纯平台效应（S8 热控同口径），留 Kotlin。
//  - **玉符墙钟发放**（JadeSymbolService.settleGrants/maybeDayReset/checkpointNow）：
//    由单调时钟 + 墙钟驱动，非 UI 操作面，留 Kotlin 服务本体。
//    【W4-B/B2 勘误标注（2026-09-15）】batch-19 的"留 Kotlin"指**完整服务**；
//    W4-B（w3-04）按平台效应回执化口径把其中 GameData 四字段稳态写下沉为
//    事务 5–8（1766–1769）——volatile 累计/单调差分/墙钟节流/本地午夜计算
//    仍留 Kotlin 运行时（读数参数化，见事务 5–8 注）。
//
// 🔴 玉符账本模型（SS9，方案 §2.4）——余额真源 = jadeLedger（append-only）：
//   余额 = 期初条目（OPENING_BALANCE）+ Σdelta；jadeSymbols 是同事务双写的
//   派生缓存。玉符事务一律经 appendLedgerEntry 落账（禁止 +=/-=/绝对值覆盖
//   余额），条目冗余 balance_after 供 O(1) 读末条；派生缓存与账本基准不一
//   致时以账本为准重锚并经回执 drift 标记（Kotlin 臂 Log 上报；StorageMetrics
//   getter 归 SS3）。无余额变化的事务（dayReset/checkpoint）经
//   verifyDerivedBalance 做派生校验。扣费/发放路径余额检查读账本末条
//   balance_after（O(1)），不遍历账本（防双花复活 + O(n) 退化）。
//   守卫测试 `JadeSymbolConsumptionGuardTest`（扫描 Kotlin 主源）：派生缓存
//   与账本的独立写入点零容忍（正则覆盖 jadeSymbols + jadeLedger）。
//
// 物品发放溢出语义类别（CLAUDE.md 13.3，对抗性审查 C1/C2/C3/H1/H2 教训）：
//  - 宗门等级奖励 = **凭据类**（玩家可重试的领取）→ `overflowMailSuppressed=true`：
//    溢出**不转邮件**，本事务整体回滚失败 → Kotlin 臂幂等重试，凭据保留。
//    与 Kotlin 原路径 `withOverflowMailSuppressed` + Partial/Failure 抛异常回滚
//    逐位等价（避免「邮件 + 重试」重复发放）。
//
// 零 RNG 论证：四事务均为纯确定性状态变换——校验链只读，变更段仅
// worldMapSects 单条目改写 / materials+storageBags 追加 / spiritStones 线性加减 /
// sectLevelClaimRecords upsert / jadeLedger 追加 + 派生缓存双写 / merchantRefreshChances
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

// ── 玉符账本核心（余额真源 = jadeLedger，append-only）────────────────────
//
// reason 协议值（Kotlin JadeLedgerReasons 同名同步；语义登记
// docs/knowledge-base.md 经济基线表）：
//   OPENING_BALANCE            新档开账（delta = 期初余额，余额不变）
//   GRANT_TIME                 在线时长发放（事务 5）
//   GRANT_AD                   激励视频发放（事务 8；含白名单直发——特权
//                              语义保持无上限，账本如实记录来源）
//   SPEND_MERCHANT_REFRESH     商人刷新扣费（事务 3）
//   SPEND_BREAKTHROUGH_BONUS   突破率加成扣费（事务 4）
inline constexpr const char* kJadeReasonOpeningBalance = "OPENING_BALANCE";
inline constexpr const char* kJadeReasonGrantTime = "GRANT_TIME";
inline constexpr const char* kJadeReasonGrantAd = "GRANT_AD";
inline constexpr const char* kJadeReasonSpendMerchantRefresh = "SPEND_MERCHANT_REFRESH";
inline constexpr const char* kJadeReasonSpendBreakthroughBonus = "SPEND_BREAKTHROUGH_BONUS";

/// 账本余额（O(1) 读末条冗余 balance_after；空账本兜底读派生缓存——
/// 未经 OPENING_BALANCE 开账的存量状态首次落账时以此建立基准）。
inline int32_t jadeLedgerBalance(const state::GameData& gd) {
    return gd.jadeLedger.empty() ? gd.jadeSymbols : gd.jadeLedger.back().balanceAfter;
}

/// 派生缓存与账本基准是否一致（账本空 ⇒ 一致性无从判定，视为一致）。
inline bool jadeDerivedMatchesLedger(const state::GameData& gd) {
    return gd.jadeLedger.empty() || gd.jadeSymbols == gd.jadeLedger.back().balanceAfter;
}

/// 派生校验（无余额变化的事务用）：缓存 ≠ 账本基准时以账本为准重锚。
/// @return true = 检出漂移并已重锚（调用方置回执 drift，Kotlin 臂 Log 上报）
inline bool verifyDerivedBalance(state::GameData& gd) {
    if (jadeDerivedMatchesLedger(gd)) return false;
    gd.jadeSymbols = jadeLedgerBalance(gd);
    return true;
}

/// 账本落账唯一写入口：append 条目 + 派生缓存同事务双写。
/// 余额推进以**账本末条 balance_after** 为基准（账本是唯一真源）；
/// 派生缓存原值若偏离基准（残留覆盖写），以账本为准重锚并标 drift。
struct JadeAppendOutcome {
    int32_t balanceAfter = 0;
    bool drift = false;  // 派生缓存与账本基准不一致（已以账本为准纠正）
};

inline JadeAppendOutcome appendLedgerEntry(state::GameData& gd, int32_t delta,
                                           const char* reason, int64_t atEpochMs) {
    JadeAppendOutcome out;
    const int32_t ledgerBase = jadeLedgerBalance(gd);
    out.drift = !gd.jadeLedger.empty() && gd.jadeSymbols != ledgerBase;
    const int32_t newBalance = ledgerBase + delta;
    state::JadeLedgerEntry entry;
    entry.atEpochMs = atEpochMs;
    entry.delta = delta;
    entry.reason = reason;
    entry.balanceAfter = newBalance;
    gd.jadeLedger.push_back(entry);
    gd.jadeSymbols = newBalance;
    out.balanceAfter = newBalance;
    return out;
}

/// 期初开账（账本首条，专用非增量形）：把当前余额记为 OPENING_BALANCE 条目
/// （delta = balanceAfter = 期初余额，余额不变）。delta 自含期初值保证全账本
/// 「Σdelta == 末条 balance_after == 派生缓存」恒成立（验收⑤守卫断言）。
/// 新档期初余额为 0（条目 0/0）；存量兜底（导入态未经开账）记存量余额。
/// @return 落账后余额（= 期初余额，不变）
inline int32_t openJadeLedger(state::GameData& gd, int64_t atEpochMs) {
    state::JadeLedgerEntry entry;
    entry.atEpochMs = atEpochMs;
    entry.delta = gd.jadeSymbols;
    entry.reason = kJadeReasonOpeningBalance;
    entry.balanceAfter = gd.jadeSymbols;
    gd.jadeLedger.push_back(entry);
    return gd.jadeSymbols;
}

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
// 玉符扣减走账本：`jadeLedgerBalance` 读末条 balance_after（O(1)）判定余额
// → appendLedgerEntry 落 SPEND_* 条目（-cost）→ 派生缓存同事务双写。
// Kotlin 臂成功后以回执重锚运行时读数（余额权威在账本，Kotlin 运行时跟随）。
//
// Kotlin 判定序（GameEngineJadePurchaseOps）：
//   上限校验**先于**扣款（达上限不消耗玉符）→ 账本余额 >= cost → 落账扣减
//   → 写回（merchantRefreshChances 累加钳制 / disciple.statusData）。

struct JadePurchaseOutcome {
    TxResult base;
    int32_t jadeSymbols = 0;   // 落账后余额（回执；Kotlin 运行时重锚用/对拍证据）
    int32_t value = 0;         // 事务写回值（刷新次数 / 新加成 ×100 取整不适用，见下）
    std::string writtenValue;  // statusData 写回字符串（breakthrough 专用）
    bool drift = false;        // 派生缓存与账本基准不一致（已以账本为准纠正）
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
/// （账本余额 < cost → INSUFFICIENT，不写入）→ 账本落账扣减 → 累加钳制。
inline JadePurchaseOutcome purchaseMerchantRefreshTx(state::GameState& st, int32_t cost,
                                                     int32_t perJade,
                                                     int32_t maxChances,
                                                     int64_t nowMs) {
    JadePurchaseOutcome out;
    if (cost <= 0 || perJade <= 0 || maxChances <= 0 || nowMs < 0) {
        out.base.errorType = "INVALID_PARAMS";
        out.base.message = "cost/perJade/maxChances must be positive and nowMs non-negative";
        return out;
    }
    auto& gd = st.gameData;
    if (gd.merchantRefreshChances >= maxChances) {
        out.base.errorType = "LIMIT_REACHED";
        out.base.message = "merchant refresh chances at max";
        return out;
    }
    if (jadeLedgerBalance(gd) < cost) {
        out.base.errorType = "INSUFFICIENT_JADE";
        out.base.message = "insufficient jade symbols";
        return out;
    }
    const auto app = appendLedgerEntry(gd, -cost, kJadeReasonSpendMerchantRefresh, nowMs);
    gd.merchantRefreshChances = std::min(gd.merchantRefreshChances + perJade, maxChances);
    out.base.ok = true;
    out.jadeSymbols = app.balanceAfter;
    out.drift = app.drift;
    out.value = gd.merchantRefreshChances;
    return out;
}

/// 突破率加成购买落账
/// （GameEngineJadePurchaseOps.purchaseBreakthroughBonus）。
/// 判定序：弟子存在 → 存活 → 上限校验（currentBonus >= maxBonus → LIMIT，
/// 不扣玉符）→ 余额校验 → 账本落账扣减 → statusData["adBreakthroughBonus"] 写回。
/// `currentBonus` 解析语义 = Kotlin `statusData[key]?.toDoubleOrNull() ?: 0.0`。
inline JadePurchaseOutcome purchaseBreakthroughBonusTx(
    state::GameState& st, const std::string& discipleId, int32_t cost, double perJade,
    double maxBonus, int64_t nowMs) {
    JadePurchaseOutcome out;
    constexpr const char* kBonusKey = "adBreakthroughBonus";
    if (cost <= 0 || perJade <= 0 || maxBonus <= 0 || nowMs < 0) {
        out.base.errorType = "INVALID_PARAMS";
        out.base.message = "cost/perJade/maxBonus must be positive and nowMs non-negative";
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
    if (jadeLedgerBalance(st.gameData) < cost) {
        out.base.errorType = "INSUFFICIENT_JADE";
        out.base.message = "insufficient jade symbols";
        return out;
    }
    const auto app = appendLedgerEntry(st.gameData, -cost,
                                       kJadeReasonSpendBreakthroughBonus, nowMs);
    const double newBonus = std::min(currentBonus + perJade, maxBonus);
    state::Disciple updated = current;
    updated.statusData[kBonusKey] = javaDoubleToString(newBonus);
    ds.upsertDisciple(updated);
    out.base.ok = true;
    out.jadeSymbols = app.balanceAfter;
    out.drift = app.drift;
    out.writtenValue = updated.statusData[kBonusKey];
    return out;
}

// ── 事务 5–8：玉符运行时（W4-B/B2，w3-04，1766–1769）─────────────────────
//
// 🔴 墙钟/单调钟读数参数化（batch-W4B §2.2 ③类统一处置模板）：
//  - 单调差分/10s 裁剪/1s 墙钟节流/**本地午夜计算**仍留 Kotlin 运行时
//    （volatile 累计 + Calendar 时区语义 = 平台读数，禁止 C++ 内取时或复刻时区规则）；
//  - jadeSymbolsToday/jadeAccumMs/jadeDayAnchorMs 三字段的**稳态写**归本组
//    事务；落账墙钟 `nowMs` 同为参数传入（C++ 不取时）；Kotlin 臂成功后以
//    回执重锚运行时读数（余额权威在账本，Kotlin 运行时跟随）。
//
// 🔴 账本模型（SS9）：本组事务不做任何余额的绝对值覆盖写——
//  - 事务 5/8（发放）：appendLedgerEntry 落 GRANT_TIME / GRANT_AD 条目 +
//    派生缓存同事务双写（回执 total = 落账后余额）；
//  - 事务 6/7（日重置/checkpoint）：不触碰余额，末尾 verifyDerivedBalance
//    派生校验（缓存漂移时以账本为准重锚 → 回执 drift 上报）。
//  失败零写入：参数校验先行，非法即 failure 信封零写入。
//
// 零 RNG 论证：四事务均为纯确定性算术/比较（整除、取模、钳制、比较写），
// 无 rng() 调用点（签名级证据：不接受 RngManager/种子参数）。

/// 每获得 1 枚玉符所需前台时长（Kotlin GameConfig.Jade.INTERVAL_MS 同值）
inline constexpr int64_t kJadeIntervalMs = 10LL * 60 * 1000;
/// 单日（墙钟 0 点起）时长渠道玉符上限（GameConfig.Jade.DAILY_CAP 同值）
inline constexpr int32_t kJadeDailyCap = 20;

/// 事务 5：玉符结算发放（JADE_RUNTIME_SETTLE_TX，1766）。
///
/// Kotlin 判定序（JadeSymbolService.settleGrants）：
///   grants = accumMs / INTERVAL_MS；grants<=0 → **零写入** ok 回执
///   （total = 账本余额）→ remainder = accumMs % INTERVAL_MS；
///   headroom = DAILY_CAP - today → headroom<=0 → 拿满冻结：jadeAccumMs = 0
///   （余量丢弃，余额零变化不落账）→ toGrant = min(grants, headroom)；
///   keepRemainder = (toGrant == grants) → 落 GRANT_TIME 条目（delta=+toGrant）
///   并写 jadeSymbolsToday/jadeAccumMs。
struct JadeSettleOutcome {
    TxResult base;
    int32_t total = 0;      // 账本余额（回执权威）
    int32_t today = 0;
    int64_t accumMs = 0;
    bool frozen = false;    // headroom<=0：拿满冻结（accum 归零）
    bool drift = false;     // 派生缓存与账本基准不一致（已以账本为准纠正）
};

inline JadeSettleOutcome settleJadeGrantsTx(state::GameState& st, int32_t today,
                                            int64_t accumMs, int64_t nowMs) {
    JadeSettleOutcome out;
    if (today < 0 || accumMs < 0 || nowMs < 0) {
        out.base.errorType = "INVALID_PARAMS";
        out.base.message = "runtime values must be non-negative";
        return out;
    }
    auto& gd = st.gameData;
    const int64_t grants = accumMs / kJadeIntervalMs;
    out.total = jadeLedgerBalance(gd);
    if (grants <= 0) {
        // Kotlin 原语义：grants<=0 → 无事务（零写入；本回执仅回声）
        out.base.ok = true;
        out.today = today;
        out.accumMs = accumMs;
        return out;
    }
    const int64_t remainder = accumMs % kJadeIntervalMs;
    const int64_t headroom = static_cast<int64_t>(kJadeDailyCap) - today;
    if (headroom <= 0) {
        gd.jadeAccumMs = 0;
        out.base.ok = true;
        out.frozen = true;
        out.today = today;
        out.accumMs = 0;
        return out;
    }
    const auto toGrant =
        static_cast<int32_t>(std::min(grants, headroom));
    const bool keepRemainder = static_cast<int64_t>(toGrant) == grants;
    const int64_t newAccum = keepRemainder ? remainder : 0;
    const auto app = appendLedgerEntry(gd, toGrant, kJadeReasonGrantTime, nowMs);
    gd.jadeSymbolsToday = today + toGrant;
    gd.jadeAccumMs = newAccum;
    out.base.ok = true;
    out.total = app.balanceAfter;
    out.today = gd.jadeSymbolsToday;
    out.accumMs = newAccum;
    out.drift = app.drift;
    return out;
}

/// 事务 6：玉符跨天重置/首次锚定（JADE_RUNTIME_DAY_RESET_TX，1767）。
///
/// Kotlin 判定序（JadeSymbolService.maybeDayReset，写段）：
///   todayMidnight 由 Kotlin Calendar 本地时区计算后传入（平台语义留宿主）
///   → anchor = gd.jadeDayAnchorMs；`anchor != 0 && todayMidnight <= anchor`
///     （同一天或墙钟回拨）→ **零写入** ok 回执 changed=false
///   → crossedDay = anchor != 0；写 jadeDayAnchorMs = todayMidnight
///   → crossedDay：真跨天 → jadeSymbolsToday = 0、jadeAccumMs = 0
///   → 首锚（旧档 anchor==0）：只锚定，不动计数。
/// 余额零变化（不落账）；成功路径末尾派生校验（缓存漂移 → 以账本为准
/// 重锚并经回执 drift 上报）。
/// 同一 todayMidnightMs 重复调用幂等（第二次命中 `<= anchor` 零写入）；
/// 墙钟回退（todayMidnight 更小）同样零写入——ADR 盲区 3 兜底。
struct JadeDayResetOutcome {
    TxResult base;
    bool changed = false;
    bool crossedDay = false;
    int32_t today = 0;
    int64_t accumMs = 0;
    int64_t dayAnchorMs = 0;
    bool drift = false;  // 派生缓存与账本基准不一致（已以账本为准纠正）
};

inline JadeDayResetOutcome jadeDayResetTx(state::GameState& st,
                                          int64_t todayMidnightMs, int32_t today,
                                          int64_t accumMs) {
    JadeDayResetOutcome out;
    if (todayMidnightMs <= 0) {
        out.base.errorType = "INVALID_PARAMS";
        out.base.message = "todayMidnightMs must be positive";
        return out;
    }
    auto& gd = st.gameData;
    const int64_t anchor = gd.jadeDayAnchorMs;
    if (anchor != 0 && todayMidnightMs <= anchor) {
        out.base.ok = true;
        out.today = today;
        out.accumMs = accumMs;
        out.dayAnchorMs = anchor;
        return out;
    }
    const bool crossedDay = anchor != 0;
    gd.jadeDayAnchorMs = todayMidnightMs;
    if (crossedDay) {
        gd.jadeSymbolsToday = 0;
        gd.jadeAccumMs = 0;
        out.today = 0;
        out.accumMs = 0;
    } else {
        out.today = today;
        out.accumMs = accumMs;
    }
    out.base.ok = true;
    out.changed = true;
    out.crossedDay = crossedDay;
    out.dayAnchorMs = todayMidnightMs;
    out.drift = verifyDerivedBalance(gd);
    return out;
}

/// 事务 7：玉符 checkpoint（JADE_RUNTIME_CHECKPOINT_TX，1768）。
///
/// Kotlin 判定序（JadeSymbolService.checkpointNow 写段 + onLoopTick 拿满冻结）：
/// 写 jadeSymbolsToday/jadeAccumMs/jadeDayAnchorMs 三字段（余额真源在账本，
/// checkpoint 不做余额覆盖写；未 onLoopStart 哨兵 `lastSampleMs==0` 留 Kotlin）。
/// 拿满冻结（accum=0）复用本事务：runtime 以 accumMs=0 调用即为等价写。
/// 成功路径末尾派生校验（缓存漂移 → 以账本为准重锚 → 回执 drift 上报）。
struct JadeCheckpointOutcome {
    TxResult base;
    int32_t total = 0;  // 账本余额（回执权威）
    int32_t today = 0;
    int64_t accumMs = 0;
    int64_t dayAnchorMs = 0;
    bool drift = false;  // 派生缓存与账本基准不一致（已以账本为准纠正）
};

inline JadeCheckpointOutcome jadeCheckpointTx(state::GameState& st,
                                              int32_t today,
                                              int64_t accumMs,
                                              int64_t dayAnchorMs) {
    JadeCheckpointOutcome out;
    if (today < 0 || accumMs < 0 || dayAnchorMs < 0) {
        out.base.errorType = "INVALID_PARAMS";
        out.base.message = "runtime values must be non-negative";
        return out;
    }
    auto& gd = st.gameData;
    gd.jadeSymbolsToday = today;
    gd.jadeAccumMs = accumMs;
    gd.jadeDayAnchorMs = dayAnchorMs;
    out.base.ok = true;
    out.total = jadeLedgerBalance(gd);
    out.today = today;
    out.accumMs = accumMs;
    out.dayAnchorMs = dayAnchorMs;
    out.drift = verifyDerivedBalance(gd);
    return out;
}

/// 事务 8：玉符广告发放落账（JADE_RUNTIME_GRANT_AD_TX，1769）。
///
/// Kotlin 判定序（JadeSymbolService.grantFromAd 写段）：amount>0 校验 +
/// 落 GRANT_AD 条目（delta=+amount，白名单直发同 reason 如实记录来源——
/// 特权语义保持无上限）+ 派生缓存同事务双写；**不写 jadeSymbolsToday**
/// （广告玉符独立于时间渠道每日上限）。广告 SDK/播放本身 = 平台效应留
/// Kotlin（本事务只承接账段）。
struct JadeGrantOutcome {
    TxResult base;
    int32_t total = 0;  // 落账后余额（账本权威）
    bool drift = false;  // 派生缓存与账本基准不一致（已以账本为准纠正）
};

inline JadeGrantOutcome grantJadeFromAdTx(state::GameState& st, int32_t amount,
                                          int64_t nowMs) {
    JadeGrantOutcome out;
    if (amount <= 0 || nowMs < 0) {
        out.base.errorType = "INVALID_PARAMS";
        out.base.message = "amount must be positive and nowMs non-negative";
        return out;
    }
    const auto app = appendLedgerEntry(st.gameData, amount, kJadeReasonGrantAd, nowMs);
    out.base.ok = true;
    out.total = app.balanceAfter;
    out.drift = app.drift;
    return out;
}

}  // namespace jade_tx
}  // namespace gamecore::system
