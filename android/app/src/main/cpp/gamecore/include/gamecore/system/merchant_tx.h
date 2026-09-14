// ============================================================
// merchant_tx.h — 行商刷新族事务（W4-B/B3，w3-05，1770–1773）
//
// Kotlin 语义权威 = MerchantAndRecruitService（判定序逐字对齐）。
//
// ## 池生成与落账的切分口径（本头事务只承接「落账段」）
// 行商/收购池的**物品生成**（buildMerchantItemPools 40 件池选取、保底相位、
// 价格波动 SYSTEM 分区抽取）留 Kotlin——C++ data 层只有静态模板表无生成器
// （jade_tx.h §2.50 同证）。Kotlin native 臂先以**镜像 count**计算新刷新计数
// 与保底相位、生成整表（RNG 全 SYSTEM 分区、存档可重放），再把整表作为参数
// 推入事务；C++ 承接**状态写**（池覆写 / 凭据扣减与发放）。
//
// ## 写者对应（本头事务消除的 Kotlin 稳态写者）
//  - grantMerchantRefreshChanceTx      ← giveMerchantRefreshChanceIfDue（:344 写段）
//  - refreshMerchantAcquisitionTx      ← refreshMerchantAcquisition（:377 写段；
//                                        其写面 merchantAcquisitionItems /
//                                        merchantAcquisitionLastRefreshYear 为在册
//                                        已关闭字段——本事务消除 AUTHORITATIVE
//                                        下的关闭域 Kotlin 写者残留）
//  - refreshTravelingMerchantTx        ← refreshTravelingMerchant（:95 写段）
//  - refreshTravelingMerchantManualTx  ← refreshTravelingMerchantManual（:319 写段；
//                                        校验链 + 扣凭据 + 池覆写**单事务原子**——
//                                        强于 Kotlin 两段式（updateAndReturn 扣减 +
//                                        第二事务写池），可观测结局一致）
//
// 零 RNG 论证：四事务均为纯确定性状态变换（比较/扣减/整表覆写），无 rng()
// 调用点（签名级证据：不接受 RngManager/种子参数）。池生成侧 RNG（SYSTEM
// 分区）全部发生在 Kotlin 参数准备阶段。
//
// 失败零写入：校验链先行；失败 → failure 信封 → Kotlin 回退臂重执行校验链
// （Kotlin 原路径整体保留为降级回退臂）。
// ============================================================
#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "gamecore/state/models.h"

namespace gamecore::system {
namespace merchant_tx {

/// 事务结果信封（失败零写入；failure → Kotlin 回退臂重执行校验链）
struct TxResult {
    bool ok = false;
    std::string errorType;
    std::string message;
};

// ── 事务 1：手动刷新次数年度发放（MERCHANT_CHANCE_GRANT_TX，1770）─────────
//
// Kotlin 判定序（giveMerchantRefreshChanceIfDue）：
//   year <= 0 → INVALID_YEAR（Kotlin 侧为防御性跳过；事务侧以 failure 表达）
//   → chances >= maxChances → ok changed=false 零写入（达上限不覆盖 grantYear）
//   → lastGrant == 0 || year - lastGrant >= intervalYears
//     → chances = min(chances + 1, maxChances)；grantYear = year
//   → 否则 ok changed=false 零写入（未到 30 年间隔）
struct MerchantChanceGrantOutcome {
    TxResult base;
    bool granted = false;
    int32_t chances = 0;
    int32_t grantYear = 0;
};

inline MerchantChanceGrantOutcome grantMerchantRefreshChanceTx(
    state::GameState& st, int32_t year, int32_t maxChances, int32_t intervalYears) {
    MerchantChanceGrantOutcome out;
    if (year <= 0 || maxChances <= 0 || intervalYears <= 0) {
        out.base.errorType = "INVALID_PARAMS";
        out.base.message = "year/maxChances/intervalYears must be positive";
        return out;
    }
    auto& gd = st.gameData;
    out.chances = gd.merchantRefreshChances;
    out.grantYear = gd.merchantLastRefreshChanceGrantYear;
    if (gd.merchantRefreshChances >= maxChances) {
        out.base.ok = true;  // 达上限：零写入（Kotlin return@update 同判据）
        return out;
    }
    const int32_t lastGrant = gd.merchantLastRefreshChanceGrantYear;
    if (lastGrant != 0 && year - lastGrant < intervalYears) {
        out.base.ok = true;  // 未到间隔：零写入
        return out;
    }
    gd.merchantRefreshChances =
        std::min(gd.merchantRefreshChances + 1, maxChances);
    gd.merchantLastRefreshChanceGrantYear = year;
    out.base.ok = true;
    out.granted = true;
    out.chances = gd.merchantRefreshChances;
    out.grantYear = year;
    return out;
}

// ── 事务 2：收购池整表覆写（MERCHANT_ACQUISITION_REFRESH_TX，1771）────────

struct MerchantRefreshOutcome {
    TxResult base;
    int32_t itemCount = 0;
};

inline MerchantRefreshOutcome refreshMerchantAcquisitionTx(
    state::GameState& st, const std::vector<state::MerchantItem>& items,
    int32_t year) {
    MerchantRefreshOutcome out;
    if (year <= 0) {
        out.base.errorType = "INVALID_YEAR";
        out.base.message = "year must be positive";
        return out;
    }
    st.gameData.merchantAcquisitionItems = items;
    st.gameData.merchantAcquisitionLastRefreshYear = year;
    out.base.ok = true;
    out.itemCount = static_cast<int32_t>(items.size());
    return out;
}

// ── 事务 3：旅行商人池整表覆写（MERCHANT_TRAVELING_REFRESH_TX，1772）──────
//
// Kotlin 侧 newRefreshCount（含 2e9 折叠保底相位）以镜像 count 预计算后传入；
// 保底物品已由 Kotlin 生成进 items（池生成留 Kotlin 口径，见头注）。

inline MerchantRefreshOutcome refreshTravelingMerchantTx(
    state::GameState& st, const std::vector<state::MerchantItem>& items,
    int32_t year, int32_t newRefreshCount) {
    MerchantRefreshOutcome out;
    if (year <= 0 || newRefreshCount < 0) {
        out.base.errorType = "INVALID_PARAMS";
        out.base.message = "year must be positive and newRefreshCount non-negative";
        return out;
    }
    st.gameData.travelingMerchantItems = items;
    st.gameData.merchantLastRefreshYear = year;
    st.gameData.merchantRefreshCount = newRefreshCount;
    out.base.ok = true;
    out.itemCount = static_cast<int32_t>(items.size());
    return out;
}

// ── 事务 4：手动刷新（MERCHANT_MANUAL_REFRESH_TX，1773）──────────────────
//
// Kotlin 判定序（refreshTravelingMerchantManual 两段合一）：
//   merchantRefreshChances <= 0 → INSUFFICIENT_CHANCES **零写入**（凭据保留）
//   → chances -= 1 → 池覆写 + year + newRefreshCount（单事务原子——
//     Kotlin 原路径为两段式，可观测结局一致；C++ 侧更强，无中间态）

struct MerchantManualRefreshOutcome {
    TxResult base;
    int32_t chances = 0;
    int32_t itemCount = 0;
};

inline MerchantManualRefreshOutcome refreshTravelingMerchantManualTx(
    state::GameState& st, const std::vector<state::MerchantItem>& items,
    int32_t year, int32_t newRefreshCount) {
    MerchantManualRefreshOutcome out;
    if (year <= 0 || newRefreshCount < 0) {
        out.base.errorType = "INVALID_PARAMS";
        out.base.message = "year must be positive and newRefreshCount non-negative";
        return out;
    }
    auto& gd = st.gameData;
    if (gd.merchantRefreshChances <= 0) {
        out.base.errorType = "INSUFFICIENT_CHANCES";
        out.base.message = "no merchant refresh chances left";
        return out;
    }
    gd.merchantRefreshChances -= 1;
    gd.travelingMerchantItems = items;
    gd.merchantLastRefreshYear = year;
    gd.merchantRefreshCount = newRefreshCount;
    out.base.ok = true;
    out.chances = gd.merchantRefreshChances;
    out.itemCount = static_cast<int32_t>(items.size());
    return out;
}

}  // namespace merchant_tx
}  // namespace gamecore::system
