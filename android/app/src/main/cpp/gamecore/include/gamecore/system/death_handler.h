#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "gamecore/state/models.h"

// ============================================================
// 弟子败北处理器（G07：玩家侧不可战死 → 重伤）
//
// 等价移植 Kotlin DiscipleDeathHandler 的**纯逻辑核心**：
//   - markDead：**重伤**写入（currentHp=1，isAlive 保持 1）
//     + 不写 status=DEAD / deathYears + 年死亡计数不递增
//   - markAllDead：批量重伤（String ID 集合）
//   - backfillDeathYears：列表 copy 模式补写 deathYears
//     （仅历史已死亡行；重伤弟子无 deathYears）
//   - hasEquipmentHeld：装备断言（重伤不清装，仅诊断日志面）
//
// 与 Kotlin 语义对齐要点：
//   - 重伤 = HP=1 存活（Q20/Q41：UI 由 HP 派生，不新增枚举）；
//     回血走既有每旬 kPhaseHpMpRecoveryRate。
//   - C++ 对不存在的 id 静默跳过（marked=false）。
//   - AI/妖兽死亡路径不走本文件（各自死亡链）。
// ============================================================
namespace gamecore::system {

/// 重伤恒定 HP（Q20）
inline constexpr int32_t kInjuredHp = 1;

/// 死亡状态枚举名（保留兼容；玩家侧不再写入）
inline constexpr const char* kDeadStatusName = "DEAD";

/// deathYears 无条目哨兵（0 = 无记录；游戏年份从 1 起，0 安全）
inline constexpr int32_t kDeathYearNone = 0;

/// markDead 结果
struct MarkDeadResult {
    bool marked = false;         // 弟子存在且已重伤（不存在 → false，静默跳过）
    bool hadEquipment = false;   // 标记时六装备位任一非空（诊断用；重伤不清装）
};

/// 装备断言守卫：六装备位任一非空 → true
inline bool hasEquipmentHeld(const state::DiscipleStore& store, std::size_t row) {
    return !store.headIds[row].empty() || !store.bodyIds[row].empty() ||
           !store.handsIds[row].empty() || !store.feetIds[row].empty() ||
           !store.weaponIds[row].empty() || !store.legsIds[row].empty();
}

/// 标记单个弟子重伤（G07；原 markDead 名保留兼容调用点）。
/// 写入 currentHp=1，isAlive 保持 1，不写 status/deathYears，不递增年死亡计数。
inline MarkDeadResult markDead(state::DiscipleStore& store, const std::string& id,
                               int32_t /*deathYear*/, int32_t& /*annualDeceasedDisciples*/) {
    MarkDeadResult r;
    const auto rowOpt = store.rowOf(id);
    if (!rowOpt.has_value()) return r;
    const std::size_t row = *rowOpt;
    store.currentHps[row] = kInjuredHp;
    if (store.isAlive[row] == 0) store.isAlive[row] = 1;  // 预标记路径恢复存活
    r.hadEquipment = hasEquipmentHeld(store, row);
    r.marked = true;
    return r;
}

/// 批量标记重伤（原 markAllDead 名保留）。
inline int32_t markAllDead(state::DiscipleStore& store,
                           const std::vector<std::string>& ids,
                           int32_t deathYear, int32_t& annualDeceasedDisciples) {
    int32_t count = 0;
    for (const std::string& id : ids) {
        if (markDead(store, id, deathYear, annualDeceasedDisciples).marked) ++count;
    }
    return count;
}

/// 列表 copy 模式补写 deathYears（Kotlin DiscipleDeathHandler.backfillDeathYears）。
/// 对快照列表中 !isAlive 且 deathYears 无记录的弟子补写 deathYear；
/// 已有记录（deathYears != 0）的弟子不覆盖。返回补写数。
inline int32_t backfillDeathYears(state::DiscipleStore& store,
                                  const std::vector<state::Disciple>& disciples,
                                  int32_t deathYear) {
    int32_t count = 0;
    for (const state::Disciple& d : disciples) {
        if (d.isAlive) continue;
        const auto rowOpt = store.rowOf(d.id);
        if (!rowOpt.has_value()) continue;
        std::size_t row = *rowOpt;
        if (store.deathYears[row] != kDeathYearNone) continue;  // 已有记录不覆盖
        store.deathYears[row] = deathYear;
        ++count;
    }
    return count;
}

}  // namespace gamecore::system
