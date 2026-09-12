#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "gamecore/state/models.h"

// ============================================================
// 弟子死亡处理器
//
// 等价移植 Kotlin DiscipleDeathHandler 的**纯逻辑核心**：
//   - markDead：三字段写入（isAlive=0 + status=DEAD + deathYears）
//     + 年死亡计数（annualDeceasedDisciples +1）+ 装备断言守卫
//   - markAllDead：批量标记（String ID 集合）
//   - backfillDeathYears：列表 copy 模式补写 deathYears
//     （"assembleAll → map 标记 → replaceAll → 补 deathYears"流水线中，
//      replaceAll 会清空列写入，此函数在 replaceAll 之后统一恢复）
//   - hasEquipmentHeld：装备断言（Kotlin 仅记日志，不阻断标记）
//
// 与 Kotlin 语义对齐要点：
//   - deathYears 是 Kotlin DiscipleTables 稀疏组件列（仅已故弟子有条目、
//     存活弟子 0 或无条目），**不进 Disciple JSON 序列化协议**（writeAllFields
//     不写此表）——C++ 侧同为纯内存列，不参与 to_json/from_json。
//   - Kotlin markDead(Int) 不检查弟子存在（对不存在的 id 会插入幽灵列条目，
//     稠密 SoA 无法模拟）——C++ 侧对不存在的 id 静默跳过（marked=false），
//     等价 Kotlin markDead(String) 的 toIntOrNull 失败跳过语义；实际调用方
//     （战斗/洞府/驻防阵亡）均保证弟子存在。
//   - 年死亡计数**无条件 +1**（禁止 isAlive 守卫）：洞府探索路径先经
//     processBattleCasualties.replaceAll 预标记（isAlive=0）再走本入口，
//     加守卫会导致洞府阵亡漏计（Kotlin ⚠ 注释同源）。
//   - status 写入 DiscipleStatus.name（"DEAD"）。
// ============================================================
namespace gamecore::system {

/// 死亡状态枚举名（DiscipleStatus.DEAD.name）
inline constexpr const char* kDeadStatusName = "DEAD";

/// deathYears 无条目哨兵（0 = 无记录；游戏年份从 1 起，0 安全）
inline constexpr int32_t kDeathYearNone = 0;

/// markDead 结果
struct MarkDeadResult {
    bool marked = false;         // 弟子存在且已标记（不存在 → false，静默跳过）
    bool hadEquipment = false;   // 标记时四装备位任一非空（Kotlin 记日志用）
};

/// 装备断言守卫：四装备位任一非空 → true（Kotlin assertNoEquipmentHeld）
inline bool hasEquipmentHeld(const state::DiscipleStore& store, std::size_t row) {
    return !store.weaponIds[row].empty() || !store.armorIds[row].empty() ||
           !store.bootsIds[row].empty() || !store.accessoryIds[row].empty();
}

/// 标记单个弟子死亡（Kotlin DiscipleDeathHandler.markDead(Int)）。
/// 写入 isAlive=0 + status=DEAD + deathYears，年死亡计数 +1，并执行装备断言。
/// 弟子不存在 → marked=false，不写列不计数。
inline MarkDeadResult markDead(state::DiscipleStore& store, const std::string& id,
                               int32_t deathYear, int32_t& annualDeceasedDisciples) {
    MarkDeadResult r;
    const auto rowOpt = store.rowOf(id);
    if (!rowOpt.has_value()) return r;  // 不存在 → 静默跳过（String 版 toIntOrNull 失败同义）
    const std::size_t row = *rowOpt;
    store.isAlive[row] = 0;
    store.statuses[row] = kDeadStatusName;
    store.deathYears[row] = deathYear;
    r.hadEquipment = hasEquipmentHeld(store, row);
    annualDeceasedDisciples += 1;  // 无条件计数（洞府预标记路径不漏计）
    r.marked = true;
    return r;
}

/// 批量标记阵亡弟子（Kotlin DiscipleDeathHandler.markAllDead）。
/// 无法解析/不存在的 ID 静默跳过；返回实际标记数。
/// 注意：Kotlin 用 Set<String> 遍历（无 RNG、逐 id 独立写列，顺序不影响结果）。
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
