/**
 * dispatch_w4b.cpp — **W4-B 批次独占**的 ActionId 分派端口（内政与经济运营轴）。
 *
 * 🔴 本文件由 W4-00（并行前置批）建立骨架，此后**只有 W4-B 可写**。
 *    W4-A / W4-C 与任何其他工作一律不得改本文件。
 *
 * ## 怎么用（W4-B 实施者）
 * 1. 在 `scripts/action-catalog/w4b.mjs` 追加本批的 ActionId 条目（只用自己段）
 * 2. 运行 `node scripts/gen-action-ids.mjs` 重生成产物
 * 3. 在本文件的 `dispatchW4B` 内加本批的 `switch` / 区间判定分支；事务实现放
 *    `include/gamecore/system/<domain>_tx.h`（纯函数）
 * 4. 本批 GTest 源登记到 `test/w4b_tests.cmake`
 *
 * ## 预分配段（docs/parallel-batches-w4/README.md §6）
 *   1760–1765 / 1766–1769 / 1770–1779 / 1840–1849
 *
 * ## 🔴 本批的结构性红线（平台效应回执化）
 * 墙钟 / 邮箱投递 / 内存压力 / 存档时机都是**平台读数**：只能把它们作为**参数**
 * 传入本端口（禁止在 game-core 内直接取系统时间或发起平台调用），端口只签发
 * 「结果 + 回执」，副作用仍由 Kotlin 侧执行。见 docs/parallel-batches-w4/
 * batch-W4B-court-economy.md §2.2。
 *
 * ## 契约
 * 见 `include/gamecore/dispatch_w4.h`：认领 ⇒ 返回完整结果信封；不认领 ⇒ `std::nullopt`。
 * 骨架阶段无任何条目 ⇒ 恒返回 `std::nullopt`（行为与本前置批之前逐位一致）。
 */

#include "gamecore/dispatch_w4.h"

namespace gamecore {

std::optional<nlohmann::json> dispatchW4B(GameCore& core, int32_t actionId,
                                          const nlohmann::json& params) {
    (void)core;
    (void)params;
    // ── W4-B 分派区（本区仅 W4-B 可写；预分配段 1760–1765 / 1766–1769 /
    //    1770–1779 / 1840–1849）────────────────────────────────────────────
    // 骨架阶段：本批尚未产出任何事务 ⇒ 不认领任何 actionId。
    (void)actionId;
    return std::nullopt;
}

}  // namespace gamecore
