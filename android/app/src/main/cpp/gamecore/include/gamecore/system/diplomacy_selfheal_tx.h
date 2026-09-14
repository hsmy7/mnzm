// ============================================================
// diplomacy_selfheal_tx.h — 外交/自愈/运行态族事务（W4-B/B4，w3-12，1840–1849）
//
// ## 本批实裁范围（逐点判定并成文，批文档 §2.3 B4 口径）
//
// **下沉**（本头事务）：
//  - markWarningStageShownTx（1843）← GameEngineDiplomacyOps.kt:18
//    （shownWarningStageIds 参与存档 ⇒ 按 ① 处置——批文档盲区 #2 保守判定）
//
// **登记不下沉（本批核实结论，W4-D/D2 统一处置）**：
//  - VassalService 年贡/附属年贡：C++ **逻辑已在位**（year_settlement.h
//    processYearlyTribute / processYearlyVassalTribute，随 AUTHORITATIVE 年结管线
//    执行）；Kotlin 调用点在冻结宿主（CultivationEventMonthlyOps / GameEngineCoreYearOps
//    ——w3-11 处理面）⇒ 本批若再开 ActionId 臂即**双重扣贡**；门控统一归 W4-D/D2
//    （批文档 §8 技术债表首行同结论）。
//  - VassalService 月度脱离（:324）：脱离概率链（战力/好感/近 3 年战绩）为 Kotlin
//    决策面 + SYSTEM 抽取；抽取序跨臂对拍风险高 ⇒ 同上归 w3-11。
//  - GameEngineServiceOps.releaseMemory 内存裁剪（:77）：③类平台决策面；
//    裁剪清单语义与 DiscipleSlotCleanup / 死亡处理交叉，登记 W4-D。
//  - SaveFacadeImpl.regenerateSectsBeforeSave（:56）：世界生成（WorldMapGenerator）
//    面，与 W4-C WS-5b「生成即数据」方案同域 ⇒ 归 W4-D 评估（避免协议面双头）。
//  - GameEngineServiceOps.checkpointAllDisciples（:40）：写 DiscipleTables
//    检查点列（弟子域核心表，语义归 W4-A w3-01）；C++ disciple 模型无检查点列
//    ⇒ 下沉需 models.h 扩列（W4-C 租约）⇒ 登记 W4-D。
//
// 零 RNG 论证：本头事务为纯确定性状态变换（判空/追加），无 rng() 调用点。
// ============================================================
#pragma once

#include <cstdint>
#include <string>

#include "gamecore/state/models.h"

namespace gamecore::system {
namespace diplomacy_selfheal_tx {

/// 事务结果信封（失败零写入；failure → Kotlin 回退臂重执行）
struct TxResult {
    bool ok = false;
    std::string errorType;
    std::string message;
};

/// 事务 1：预警阶段标记（DIPLOMACY_WARNING_STAGE_TX，1843）。
///
/// Kotlin 语义（GameEngineDiplomacyOps.markWarningStageShown）：
/// `shownWarningStageIds = shownWarningStageIds + stageKey`（List 追加，
/// **不去重**——与回退臂逐位一致；重复展示防御由 UI 层读取侧承担）。
struct WarningStageOutcome {
    TxResult base;
    int32_t stageCount = 0;
};

inline WarningStageOutcome markWarningStageShownTx(state::GameState& st,
                                                   const std::string& stageKey) {
    WarningStageOutcome out;
    if (stageKey.empty()) {
        out.base.errorType = "INVALID_PARAMS";
        out.base.message = "stageKey must not be empty";
        return out;
    }
    st.gameData.shownWarningStageIds.push_back(stageKey);
    out.base.ok = true;
    out.stageCount = static_cast<int32_t>(st.gameData.shownWarningStageIds.size());
    return out;
}

}  // namespace diplomacy_selfheal_tx
}  // namespace gamecore::system
