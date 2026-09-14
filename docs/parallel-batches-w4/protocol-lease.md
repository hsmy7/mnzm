# W4 协议面租约表（`models.h` / `json_codec.cpp` / `game_core.*` / `GameEngine.kt` / `GameData.kt`）

> 由 **W4-00 并行前置批** 建立。适用范围：**W4-A / W4-B / W4-C 三个并行批次**。
> 依据：`docs/parallel-batches-w4/README.md` §5.4。
> 🔴 **未登记租约的批次不得修改下表资源**——走"Kotlin 组装参数传入"路线（w2 §3.3 既有口径）。

---

## 1. 为什么需要租约

绝大多数共享文件在 W4-00 已被**结构性切分**（ActionId 清单 / C++ 分发表 / 测试源清单 /
反向通道关闭数据），三批各写各的文件，冲突面归零。

但有四类文件**切不开**——它们是**单一协议模型**，任何一处变更都会同时影响另外两批的
编译面与对拍面：

| 资源 | 为什么切不开 |
|---|---|
| `state/models.h` + `src/json_codec.cpp` | 跨语言协议模型：C++ `GameData`/`GameState` 与 Kotlin `GameData` 必须逐字段对齐；`DiffSurfaceAssertion` 断言"C++ 导出而 Kotlin 缺失 = 协议漂移即红" |
| `src/game_core.{h,cpp}` | 引擎生命周期入口（`initialize` / `importStateInternal` / `exportStateJson`）与归纳一化族 |
| `core/domain/.../model/GameData.kt` + `core/data/.../local/GameDatabase.kt` | 上述 Kotlin 侧的对应面（ProtoBuf + Room 列 + Migration） |
| `core/engine/.../engine/GameEngine.kt` | 装配/接线入口；w2 §3.3 已定"下沉接线一律在 Facade / Ops 层完成"，本波把该口径**升级为硬约束** |

## 2. 默认所有者与租约顺序

| 资源 | 默认所有者 | 租约顺序 | 说明 |
|---|---|---|---|
| `include/gamecore/state/models.h` | **W4-C** | W4-C → W4-A → W4-B | W4-C 的 **WS-5b（地图冻结）必须改**：GameData 增 `mapGenVersion` + 地形段 |
| `src/json_codec.cpp` | **W4-C** | 同上 | 同上（`GC_TO`/`GC_FROM` 双向编解码） |
| `include/gamecore/game_core.h` + `src/game_core.cpp` | **W4-C** | W4-C → W4-A → W4-B | WS-5b：`importStateInternal` 归一化族新增 `ensureTerrainGenerated` |
| `core/domain/.../model/GameData.kt` | **W4-C** | W4-C → W4-D（D5 死函数清理） | WS-5b 新增 `@ProtoNumber` + `@ColumnInfo` 字段 |
| `core/data/.../local/GameDatabase.kt`（+ `MIGRATION_50_51` + schema JSON） | **W4-C** | 独占（无其他批需要） | `@Database(version)` 50 → 51 |
| `core/engine/.../engine/GameEngine.kt` | **W4-A** | W4-A → W4-C → W4-B | W4-A 的 w3-02 必碰（`:276/:277/:305/:306` 婚姻提议审批/拒绝接线）；W4-C 的 `:170-172` 三处 `xxxRngManager` 赋值点随形参化移除需申请 |

## 3. 实测注记（降低预期的争用）

**w3 十二批无一批需要改 `models.h` / `json_codec.cpp`**（2026-09-15 实测）：

- w3 §3 的原子变更集是"五件套"（`action_ids.h` / `ActionIds.kt` / `execute_dispatch.cpp` /
  `test/CMakeLists.txt` / `gen-action-ids.mjs`），**不含**协议模型面；
- `system/` 下 14 个 `*_tx.h` 全部 `#include "gamecore/state/models.h"`，但都是**只读依赖**
  （WS-5b 改 `models.h` 会触发它们重编译，属构建成本，不是文本冲突）；
- w3-01 的新发现稳态写者 `GameEngineManualOps.replaceManual` 实测只写既有字段
  （`manualStacks` / `manualInstances` / `manualProficiencies` / `manualIds` / `storageBagItems`）
  ⇒ 无需扩协议；
- w3-09 的"`GridBuildingData` 槽位字段"有 ADR 给的**备选路线**（改由 C++ 侧槽位表承载）
  ⇒ 默认走备选，不动模型。

⇒ **本租约预期零争用**，保留作为兜底与书面约束。

## 4. 申请方式

1. 在下方「租约登记」表追加一行（`批次 / 资源 / 字段或函数 / 起止提交 / 理由`）；
2. 合并进 `main` 前知会收口人，由收口人按租约顺序串行化落库；
3. 同一资源**同一时间只能有一个"进行中"租约**——后到者等待，或改走 Kotlin 传参路线。

## 5. 租约登记

| 批次 | 资源 | 字段 / 函数 | 起止提交 | 理由 | 状态 |
|---|---|---|---|---|---|
| W4-C | `models.h` + `json_codec.cpp` + `game_core.{h,cpp}` + `GameData.kt` + `GameDatabase.kt` | `mapGenVersion`、地形段（RLE 存储编码）、`ensureTerrainGenerated`、`@Database` 50→51 + `MIGRATION_50_51` | 待 W4-C 开工 | WS-5b 地图冻结（已拍板完整业界方案） | **预留（第一顺位）** |
| W4-A | `GameEngine.kt` | `:276/:277/:305/:306` 婚姻提议审批/拒绝接线 native 臂 | 待 W4-A 开工 | w3-02 低成本起手项（C++ 事务 `DISCIPLE_LIFECYCLE_MARRY_APPROVE=1592` 已就绪未接线） | **预留（第一顺位）** |
| W4-C | `GameEngine.kt` | `:170-172` 三处顶层 `xxxRngManager` 赋值点随形参化移除 | w4c/01 | C8 随机源收敛：W4-A 尚未开工（`w4/a-disciple-building` 无提交、面未触碰），按租约顺序顺延取得；改动仅 init 块 3 行删减 + 2 行 import 清理，与 W4-A 的 `:276-306` 接线 hunks 零重叠，合并不冲突 | **进行中（W4-C w4c/01）** |
| （空） | | | | | |

> 登记后请把「状态」改为 `进行中（<批次> <起始提交>）`；完成后改为 `已释放`。
