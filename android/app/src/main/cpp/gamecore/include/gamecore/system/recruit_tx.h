// ============================================================
// recruit_tx.h — 招募/派遣/俘虏残余族事务（batch-16）
//
// ui-read-surface §4.1「招募/派遣/俘虏」UI 操作面写者下沉（语义权威 =
// 各 Kotlin 源文件，判定序与抽取序逐字对齐）。写者审计结论（§2.47）：
// 手动/一键/自动招募、俘虏装备物化、年度刷新/老化/自动拒绝的**结算权威**
// 已在 recruit_settlement.h / year_settlement.h / 专用 JNI——本头只承
// Kotlin 侧**直调点**三类事务（execute 通道，ActionId 1630–1632）：
//  - GameEngine.removeFromRecruitList（招募列表 UI 拒绝按钮；零 RNG 幂等）
//  - RecruitService.refreshRecruitList 直调点（开机/读档自愈/年结回退臂；
//    复用 year_settlement 候选生成链——SYSTEM 分区与 Kotlin 臂逐位同源）
//  - RecruitService.ageRecruitList 直调点（老化+净化一体，零 RNG）
//
// RNG 契约（对拍命门）：
//  - removeRecruitTx / ageRecruitTx：**零抽取**（签名级：不接受 rng 参数）。
//  - refreshRecruitTx：复用 detail::processRefreshRecruitList——数量
//    1×nextInt（宗门等级区间+长老魅力加成；无玩家宗门兜底 nextInt(7)）→
//    逐候选 1×nextInt(2) 性别 + 名字（kFull）+ 灵根（五元素洗牌）+
//    1×nextInt(14) 年龄 + createDisciple 链，全部 SYSTEM 分区；与 Kotlin
//    臂（RecruitService.refreshRecruitList）消费序逐位一致（对拍已由
//    year_settlement_test 锁定）。差值门（year-lastRecruitYear<3）在函数
//    内前置——零抽取零写入；Kotlin native 臂同款预检后回退 Kotlin 臂。
//
// 已知范围边界（对拍约定，appointment_tx.h 同口径）：
//  - 候选弟子 id：C++ 臂生成 id=""（镜像生成字段，Kotlin 臂 UUID——
//    recruit_settlement.h 头注释口径：id 不参与业务逻辑）。
//  - 惰性门（autoRecruitIdle/autoRejectIdle）：GameState 瞬态字段，刷新
//    事务内随 C++ 链路复位；Kotlin 内存侧 RecruitLazyState 由 native 臂
//    成功后同步复位（纯内存残差，不进 JSON 协议）。
//  - lifeEvents 为 Kotlin 类体属性（协议外列）——经信封 counts 由 Kotlin
//    镜像侧自理（disciple_tx.h:logLine 同款口径）。
// ============================================================
#pragma once

#include <algorithm>
#include <cstdint>
#include <string>
#include <vector>

#include "gamecore/state/models.h"
#include "gamecore/system/year_settlement.h"  // detail::processRefreshRecruitList / processRecruitAging

namespace gamecore::system::recruit_tx {

// ── 结果信封（失败零写入；failure → Kotlin 回退原路径重执行校验链）────────

struct TxResult {
    bool ok = false;
    std::string errorType;
    std::string message;
};

/// 招募列表移除结果：removed = 本次按 id 移除条目数（幂等，0 = 无命中）
struct RecruitRemoveOutcome {
    TxResult base;
    int32_t removed = 0;
    int32_t remaining = 0;
};

/// 年度刷新结果：generated = 新增候选数（含随后被自动招募入宗者）；
/// autoRecruited = 刷新事务内自动招募入宗数（recruitCountThisMonth 增量）
struct RecruitRefreshOutcome {
    TxResult base;
    int32_t generated = 0;
    int32_t autoRecruited = 0;
    int32_t remaining = 0;
};

/// 老化净化结果：removed = 超寿元死亡 + 损坏/重复/已入宗门残留移除总数
struct RecruitAgeOutcome {
    TxResult base;
    int32_t removed = 0;
    int32_t remaining = 0;
};

// ── 事务 1：招募列表移除（GameEngine.removeFromRecruitList 写段）──────────
//
// Kotlin 判定序：updateGameDataSync { recruitList.filter { it.id != id } }——
// 全量过滤（同 id 多条全移，非首命中）、幂等（无命中 = 原样返回成功）。
// 零 RNG 纯事务。
inline RecruitRemoveOutcome removeRecruitTx(gamecore::state::GameState& state,
                                            const std::string& discipleId) {
    RecruitRemoveOutcome out;
    auto& list = state.gameData.recruitList;
    const auto before = static_cast<int32_t>(list.size());
    list.erase(std::remove_if(list.begin(), list.end(),
                              [&discipleId](const gamecore::state::Disciple& d) {
                                  return d.id == discipleId;
                              }),
               list.end());
    out.base.ok = true;
    out.removed = before - static_cast<int32_t>(list.size());
    out.remaining = static_cast<int32_t>(list.size());
    return out;
}

// ── 事务 2：年度招募列表刷新（RecruitService.refreshRecruitList 直调段）────
//
// 复用 year_settlement 权威链（detail::processRefreshRecruitList）：
// 差值门 → 数量（宗门等级区间 + 纳徒长老魅力/职务加成；无玩家宗门兜底
// nextInt(7)≥1）→ 广纳门徒 +50% → 逐候选 SYSTEM 分区生成 → 追加 +
// lastRecruitYear + 复位双惰性门 + processAutoRecruit。
// 计数口径与 Kotlin 日志一致：generated = 列表净增 + 自动招募数。
inline RecruitRefreshOutcome refreshRecruitTx(gamecore::state::GameState& state,
                                              int32_t year,
                                              gamecore::rng::RngManager& rng) {
    RecruitRefreshOutcome out;
    auto& gd = state.gameData;
    const int32_t beforeSize = static_cast<int32_t>(gd.recruitList.size());
    const int32_t beforeMonthCount = gd.recruitCountThisMonth;

    detail::processRefreshRecruitList(state, year, rng);

    const int32_t afterSize = static_cast<int32_t>(gd.recruitList.size());
    out.autoRecruited = gd.recruitCountThisMonth - beforeMonthCount;
    out.base.ok = true;
    out.generated = (afterSize - beforeSize) + out.autoRecruited;
    out.remaining = afterSize;
    return out;
}

// ── 事务 3：招募列表老化+净化（RecruitService.ageRecruitList 直调段）───────
//
// 复用 year_settlement 权威链（detail::processRecruitAging）：全员 age+1 →
// 超寿元移除 → 损坏过滤 + 三级去重 + 跨表 isSamePerson 残留移除。零 RNG。
// Kotlin 老化（processRecruitAging）与净化（sanitizeRecruitList）两段
// 与 C++ 单函数同序合并等价。
inline RecruitAgeOutcome ageRecruitTx(gamecore::state::GameState& state,
                                      ecs::World& world) {
    RecruitAgeOutcome out;
    const int32_t before = static_cast<int32_t>(state.gameData.recruitList.size());

    detail::processRecruitAging(state, world);

    out.base.ok = true;
    out.removed = before - static_cast<int32_t>(state.gameData.recruitList.size());
    out.remaining = static_cast<int32_t>(state.gameData.recruitList.size());
    return out;
}

}  // namespace gamecore::system::recruit_tx
