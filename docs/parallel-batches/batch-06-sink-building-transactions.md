# Batch-06：UI 操作面下沉·建筑放置/迁移/升级/拆除事务（C++ 唯一真相）

| 项 | 内容 |
|---|---|
| 批次 | 06 ｜ 并行组 C（与 07/08/09 共享面仅 3 文件，协议见 [README](README.md) §3.3/§3.4） |
| 模块 | C++（gamecore 新域头 + execute_dispatch）+ Kotlin 门面（core:engine 建筑域） |
| 性质 | WS-2 规模下沉批：UI 操作面 Kotlin 直改写者 → C++ 事务（反向通道按域关闭的前置） |
| 来源 | [ui-read-surface](../ui-read-surface.md) §4.1：「建筑放置/拆除/升级——placeBuilding/moveBuilding/upgradeBuilding/removeBuilding/enterSect/BootSequence 迁移（**全部无 C++ 通道**；WS-5 只下沉了地形生成与占位读面）→ 建筑放置事务批」 |
| 预分配 | handover §2.35；**ActionId 段 1450–1469** |

## 1. 范围

**In-scope（本批四个事务）**：`placeBuilding` / `moveBuilding` / `upgradeBuilding` /
`removeBuilding`（玩家建筑编辑面全量——目前**零 C++ 通道**的最大空白域）。

**Out-of-scope**：`enterSect`（进宗门编排）与 BootSequence 存档迁移的建筑重建——
登记进 §2.35 归后续波次（README §6 W2/W3）。

## 2. 地基（已就绪，无需新建）

- **C++ 状态**：`state/models.h:1352` `gameData.placedBuildings`（`GridBuildingData`）
  已在镜像协议；月结（`month_settlement.h:620/1743`）与旬结（`phase_settlement.h:260`）
  已按 C++ 侧 placedBuildings 结算——本批下沉后结算视图与操作视图首次同源。
- **占位读面**：WS-5 已做 `applyBuildingOccupancy`（纯地形基座 + 脚印标记，Kotlin UI）。
- **转发协议**：`nativeExecute` + `execute_dispatch.cpp` 域 handler 模式（S6/S7 先例）；
  `NativeEngineFlag.authoritative` 门控 + 失败信封回退 Kotlin（双实现并行契约）。

## 3. 实施步骤

### 第一步：写者审计（强制前置，产出进 §2.35）

逐操作列出 Kotlin 调用链（UI → ViewModel/Delegate → Facade/Service → stateStore.update
触碰字段）与 **RNG 消费标注**（建筑编辑面预期零 RNG——凡发现 roll 点即登记并评估
抽取序）。参照 §2.21.1 审计口径（"场景规避"与"实际写者"差异的教训）。

### 第二步：C++ 事务（新头文件 `gamecore/include/gamecore/system/building_tx.h`）

`production.h startProductionTransaction`（S7）同构：校验链（存在性/金币与资源/
占位冲突/边界与道路占位——逐条对齐 Kotlin 判定序）→ 状态变更（placedBuildings
增/删/改 + 钱包扣减走既有 wallet 原语）→ 单次事务返回信封（成功载荷或结构化失败
`BuildingTxStatus`）。**失败零写入**。零 RNG 论证写进头注释。

### 第三步：协议接线（共享文件协议见 README §3.3）

- `scripts/gen-action-ids.mjs` ACTION_CATALOG 追加 **1450–1469 段**：
  `BUILDING_PLACE=1450 / MOVE=1451 / UPGRADE=1452 / REMOVE=1453`（余量备用）→
  `node scripts/gen-action-ids.mjs` 重新生成两份产物一并提交。
- `execute_dispatch.cpp`：新增独立 `handleBuildingTx` + 中央 switch 一行 case。
  **不新增 JNI 导出、不改 GameCoreBridge**（nativeExecute 既有通道）。

### 第四步：Kotlin 门面（`BuildingFacadeImpl`，S7 门面层先例）

native 分支（AUTHORITATIVE 门控 + `gameEngineCore.stateSyncServiceRef` 直达）→
`tryExecuteNative` 镜像回读（placedBuildings + 钱包 + 相关容量派生）→ UI 状态流刷新；
失败信封 → **回退 Kotlin 原路径重执行校验链**（双实现并行契约——回退写者保留是
§4.2 豁免先例认可形态）。建筑域**无 Room 写回**（gameData 镜像域，与 S7 的 repo
不同）。**不改 GameViewModel**（接线在 Facade 层；README §2 末段约束）。

### 第五步：测试

- **GTest 黄金**（新 `gamecore/test/building_tx_test.cpp`，production_test 风格）：
  四事务 happy path（字段逐项断言）/ 校验链全失败臂（金币不足/占位冲突/越界/不存在
  → 失败零写入）/ move 等价 place+remove 的原子性 / upgrade 资源精确扣减 / 空表与
  幽灵建筑防御 / RNG 零消费审计断言。
- **Diff 对拍**（可选但推荐）：建筑编辑后跑旬/月结对拍（placedBuildings 变化经
  C++ 结算双端一致）——若 Kotlin 回退臂行为可对拍，加 1-2 场景进 Diff 家族。
- Kotlin 侧：门面 native 分支 flag 门控单测（既有 NativeSurfaceViewTest/生产域
  flag 门控先例——单测环境回退臂）。

## 4. 纪律

- 校验链**逐字对齐** Kotlin 判定序（失败信封要能让回退臂重放同等判定）；
  S7 口径：失败明细（如缺哪种资源）可由 Kotlin 自行构建。
- 月结/旬结已消费 placedBuildings——下沉后同一 C++ state 被操作与结算共享，
  **无新增同步点**正是本域下沉收益；对拍全量必跑（③）。
- UI 占位刷新依赖镜像回读的**引用变化**（WS-5 拷贝契约：占位副本产出新引用驱动
  失效）——门面回读后确认 `applyBuildingOccupancy` 输入面拿到新数组引用。

## 5. 验收

1. 四操作 AUTHORITATIVE 稳态写者归 C++（Kotlin 仅回退路径）；写者审计表 + RNG 结论进 §2.35。
2. §4 模板全跑：桌面 C++ 单测（⑥ 重建 desktop-jni）+ ③ 引擎对拍全量 + ①②④⑤。
3. handover §2.35 + ui-read-surface §4.1 建筑行标注"已下沉（回退写者保留）" + CHANGELOG。

## 6. 本批触碰文件声明

- 新：`building_tx.h` / `building_tx_test.cpp`。
- 改：`gen-action-ids.mjs` + 生成物两份 / `execute_dispatch.cpp`（独立 handler + 一行
  case）/ `BuildingFacadeImpl.kt`（+必要时 `GameEngineBuildingOps` 同族域文件）/
  相关测试。
- 不触碰：`GameViewModel.kt`、`GameCoreBridge.*`、`StateSyncService.kt`、其他域 handler、
  `models.h`（占位校验所需常量走参数或既有配置，不增协议字段）。
