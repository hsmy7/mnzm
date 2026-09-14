# `scripts/action-catalog/` — ActionId 分段清单（W4 三批次并行）

> 由 **W4-00 并行前置批** 从 `scripts/gen-action-ids.mjs` 的单一 `ACTION_CATALOG`
> 切分而来。生成器 `scripts/gen-action-ids.mjs` 把四份清单**顺序拼接**后产出两份产物。
> 依据：`docs/parallel-batches-w4/README.md` §3.1 项 1。

---

## 1. 为什么切分

切分前 `ACTION_CATALOG` 是**一个数组字面量**，三个并行批次只能把新条目追加到
**同一位置**（数组末尾）⇒ hunk 重叠 ⇒ **必然的文本冲突**。
切分后每批只写自己那一个文件，冲突面归零。

同时，生成器新增了四条**清单自检**（任一条不成立即生成失败，不写出半成品产物）：

| # | 断言 | 防的是什么 |
|---|---|---|
| 1 | `id` 全局唯一 | 同一动作号被两处代码驱动（静默缺陷） |
| 2 | 各批预分配段**两两不相交** | 段号重叠（历史事故：1730 被 1520–1531 区间吞掉） |
| 3 | 各批条目**必须落在自己的段内** | 跨批占段 |
| 4 | `core.mjs` 条目不得落进任何批次段 | 误改既有条目段号 |

## 2. 文件与所有权

| 文件 | 所有者 | 段号 |
|---|---|---|
| `core.mjs` | 🔴 **冻结**（W4-00 后任何批次禁改） | 1000–1734（批次 0–21 累计 169 条） |
| `w4a.mjs` | **W4-A 独占** | 1740–1749 / 1750–1759 / 1810–1819 / 1820–1829 / 1850–1854（条件段） |
| `w4b.mjs` | **W4-B 独占** | 1760–1765 / 1766–1769 / 1770–1779 / 1840–1849 |
| `w4c.mjs` | **W4-C 独占** | 1780–1789 / 1790–1799 / 1800–1809 / 1855–1859（条件段） |

预分配表权威来源：`docs/parallel-batches-w4/README.md` §6。

## 3. 新增一个动作的流程

```bash
# 1) 只改自己批次的清单文件（例：W4-A）
#    在 scripts/action-catalog/w4a.mjs 的 CATALOG 末尾追加一行
#    { id: 1740, name: 'XXX_TX', desc: '……（判定链/零 RNG 口径）' },

# 2) 重生成两份产物（必须提交，防漂移）
node scripts/gen-action-ids.mjs          # 会打印 "169 actions" 起，含新增后总数

# 3) 自证零漂移（提交前必跑）
git diff --exit-code -- \
  android/app/src/main/cpp/gamecore/include/gamecore/action_ids.h \
  android/core/engine/src/main/java/com/xianxia/sect/core/nativebridge/ActionIds.kt \
  || echo "有 diff 说明生成物未同步提交"
```

随后在**自己的** `src/dispatch_w4X.cpp` 加分支、在**自己的** `test/w4X_tests.cmake`
登记 GTest——三处都不碰共享文件。

## 4. 生成物与合并规则

两份产物（`action_ids.h` / `ActionIds.kt`）是**生成物**：

- 合并冲突**一律「重生成」**：在合并后的树上跑 `node scripts/gen-action-ids.mjs`，
  再以 `git diff --exit-code` 自证零漂移；**禁止手工解冲突**。
- 每批提交前必须自证零漂移（否则干净检出可能编译失败——历史事故：跨批共享文件半提交）。
- `action_ids.h` 额外产出一个**枚举数组** `action::kAllActionIds`（升序）与
  `action::kAllActionIdsCount`，专供 `test/dispatch_guard_test.cpp` 的
  **分派覆盖守卫**使用：对每一个已注册动作号断言"分派可达且落到本域 handler"。
  该守卫在 W4-00 首跑即抓出两处死导出（`INV_ADD_EQUIPMENT_INSTANCE` /
  `INV_ADD_MANUAL_INSTANCE`），已按死导出纪律删除。

## 5. 纪律

- **段内用不满则余量留空**，禁止跨批复用；
- `core.mjs` 冻结：确需修订既有条目（改名/改描述/删条目）⇒ 找**收口人**串行化；
- 条目 `desc` 建议写明**判定链要点与 RNG 口径**（零 RNG / 分区复刻），便于审计；
- `id` 一旦发布不得复用（进程内协议，可自由演进，但复用会让旧 .so 误认领）。
