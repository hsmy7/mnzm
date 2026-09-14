/**
 * dispatch_w4c.cpp — **W4-C 批次独占**的 ActionId 分派端口（战斗与世界协议轴）。
 *
 * 🔴 本文件由 W4-00（并行前置批）建立骨架，此后**只有 W4-C 可写**。
 *    W4-A / W4-B 与任何其他工作一律不得改本文件。
 *
 * ## 怎么用（W4-C 实施者）
 * 1. 在 `scripts/action-catalog/w4c.mjs` 追加本批的 ActionId 条目（只用自己段）
 * 2. 运行 `node scripts/gen-action-ids.mjs` 重生成产物
 * 3. 在本文件的 `dispatchW4C` 内加本批的 `switch` / 区间判定分支；事务实现放
 *    `include/gamecore/system/<domain>_tx.h`（纯函数）
 * 4. 本批 GTest 源登记到 `test/w4c_tests.cmake`
 *
 * ## 预分配段（docs/parallel-batches-w4/README.md §6）
 *   1780–1789 / 1790–1799 / 1800–1809 / 1855–1859（条件段）
 *
 * ## 🔴 本批的两条结构性红线
 * 1. **WS-5b（地图冻结）不经本端口**：地形由生命周期（init / import / 新档）触发，
 *    不是玩家操作 ⇒ 不占 ActionId、不加分派分支（判据见 batch-W4C-battle-world.md
 *    §2.3.4）。
 * 2. **战利品生成族不下沉**：模板抽取实参走 `kotlin.random.Random.Default`
 *    （非游戏分区），C++ 无法逐位复刻；且分区抽取被夹在其间，拆出即"半吊子混合态"
 *    ⇒ 整族留 Kotlin（batch-20b 登记，禁止近似复刻）。
 *
 * ## 契约
 * 见 `include/gamecore/dispatch_w4.h`：认领 ⇒ 返回完整结果信封；不认领 ⇒ `std::nullopt`。
 * 骨架阶段无任何条目 ⇒ 恒返回 `std::nullopt`（行为与本前置批之前逐位一致）。
 */

#include "gamecore/dispatch_w4.h"

namespace gamecore {

std::optional<nlohmann::json> dispatchW4C(GameCore& core, int32_t actionId,
                                          const nlohmann::json& params) {
    (void)core;
    (void)params;
    // ── W4-C 分派区（本区仅 W4-C 可写；预分配段 1780–1789 / 1790–1799 /
    //    1800–1809 / 1855–1859）────────────────────────────────────────────
    // 骨架阶段：本批尚未产出任何事务 ⇒ 不认领任何 actionId。
    (void)actionId;
    return std::nullopt;
}

}  // namespace gamecore
