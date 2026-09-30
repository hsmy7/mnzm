#pragma once

// ============================================================
// nurture_constants.h — 熟练度/孕养数值常量与曲线（S8 循环包含断链）
//
// 自 phase_settlement.h 上移：ai_sect_ops.h（S8 AI 修炼/孕养）与
// phase_settlement.h（每旬玩家结算）共用同一组常量/曲线，但
// phase_settlement.h ↔ month_settlement.h（→ ai_sect_ops.h）存在包含环，
// 本头文件为无依赖叶子（仅cstdint/cmath），两侧共同包含。
//
// 数值来源（Kotlin 同名字段）：
//   - ManualProficiencySystem.BASE_PROFICIENCY_RATE / MAX_PROFICIENCY /
//     LIBRARY_PROFICIENCY_BONUS_RATE
//   （装备孕养族 NURTURE_GAIN_PER_PHASE / getMaxNurtureLevel /
//   getExpRequiredForLevelUp 已随 B3 孕养体系退役删除）
// ============================================================

#include <cmath>
#include <cstdint>

namespace gamecore::system {

/// 每旬熟练度基础增长参数（ManualProficiencySystem：6/s × (1+藏经阁0.5) × 2000ms）
constexpr double kBaseProficiencyRate = 6.0;
constexpr double kLibraryProficiencyBonusRate = 0.5;
constexpr int32_t kMaxProficiency = 30000;

}  // namespace gamecore::system

