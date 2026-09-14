/**
 * dispatch_w4a.cpp — **W4-A 批次独占**的 ActionId 分派端口（弟子与建设轴）。
 *
 * 🔴 本文件由 W4-00（并行前置批）建立骨架，此后**只有 W4-A 可写**。
 *    W4-B / W4-C 与任何其他工作一律不得改本文件。
 *
 * ## 怎么用（W4-A 实施者）
 * 1. 在 `scripts/action-catalog/w4a.mjs` 追加本批的 ActionId 条目（只用自己段）
 * 2. 运行 `node scripts/gen-action-ids.mjs` 重生成产物
 * 3. 在本文件的 `dispatchW4A` 内加本批的 `switch` / 区间判定分支；事务实现放
 *    `include/gamecore/system/<domain>_tx.h`（纯函数）
 * 4. 本批 GTest 源登记到 `test/w4a_tests.cmake`
 *
 * ## 预分配段（docs/parallel-batches-w4/README.md §6）
 *   1740–1749 / 1750–1759 / 1810–1819 / 1820–1829 / 1850–1854（条件段）
 *
 * ## 契约
 * 见 `include/gamecore/dispatch_w4.h`：认领 ⇒ 返回完整结果信封；不认领 ⇒ `std::nullopt`。
 * 骨架阶段无任何条目 ⇒ 恒返回 `std::nullopt`（`GameCore::execute` 行为与本前置批之前
 * **逐位一致**：所有 W4 段内的动作号仍走最终 `NOT_IMPLEMENTED` 兜底）。
 */

#include "gamecore/dispatch_w4.h"

namespace gamecore {

std::optional<nlohmann::json> dispatchW4A(GameCore& core, int32_t actionId,
                                          const nlohmann::json& params) {
    (void)core;
    (void)params;
    // ── W4-A 分派区（本区仅 W4-A 可写；预分配段 1740–1749 / 1750–1759 /
    //    1810–1819 / 1820–1829 / 1850–1854）────────────────────────────────
    // 骨架阶段：本批尚未产出任何事务 ⇒ 不认领任何 actionId。
    (void)actionId;
    return std::nullopt;
}

}  // namespace gamecore
