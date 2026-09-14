# Batch-07：UI 操作面下沉·道路放置/拆除事务（C++ 唯一真相）

| 项 | 内容 |
|---|---|
| 批次 | 07 ｜ 并行组 C（与 06/08/09 共享面仅 3 文件，协议见 [README](README.md) §3.3/§3.4） |
| 模块 | C++（gamecore 新域头 + execute_dispatch）+ Kotlin 门面（core:engine 道路域） |
| 性质 | 中批下沉：两操作域（placeRoad/removeRoad），与 batch-06 同族但更小 |
| 来源 | [ui-read-surface](../ui-read-surface.md) §4.1：「道路——placeRoad/removeRoad（Kotlin 直改 + 即时回导加固）→ 道路事务批」 |
| 预分配 | handover §2.36；**ActionId 段 1470–1479** |

## 1. 范围

`placeRoad` / `removeRoad`（石板路编辑面）。**Out-of-scope**：道路渲染增量装配
（`RoadMaskTracker`，WS-5 产物，纯 Kotlin UI 缓存——道路集变化经镜像回读驱动，不迁）；
修路消耗的月度整图重算逻辑若为月结域（每格修路）则保持月结侧不动。

## 2. 地基（已就绪）

- **C++ 状态**：`state/models.h:1355` `gameData.roads`（`std::vector<RoadData>`，
  注释明示"C++ 仅承载状态"——本批把写入面也收进来）。
- **C++ 组合先例**：`nativeRoadCompose`（无状态纯函数，kAnyThread）已存在——道路
  图组合逻辑在 C++ 侧有先例。
- **UI**：RoadMaskTracker 消费"道路集变化 → 重算受影响格"——镜像回读的新 roads
  引用即可驱动，无需改它。

## 3. 实施步骤（同 batch-06 骨架，差异点如下）

1. **写者审计**：placeRoad/removeRoad 调用链 + 修路消耗（金币/石板材料？）与
   占位冲突判定序 + RNG 标注（预期零 RNG）。`RoadFacadeImpl` 为门面落点。
2. **C++ 事务**（`gamecore/include/gamecore/system/road_tx.h`）：校验（存在性/资源/
   重复放置/占位冲突对齐 Kotlin 序）→ `gameData.roads` 增删 + 资源扣减（wallet /
   库存原语复用——若消耗材料经 INV_* 既有原语路径则钱包与库存双段同事务）→ 信封。
   **失败零写入**；零 RNG 论证进头注释。
3. **协议**：`ROAD_PLACE=1470 / ROAD_REMOVE=1471`（`gen-action-ids.mjs` 追加 1470–1479
   段 → 重新生成两份产物）；`execute_dispatch.cpp` 独立 `handleRoadTx` + 中央一行。
   不新增 JNI 导出。
4. **Kotlin 门面**（`RoadFacadeImpl`）：AUTHORITATIVE 门控 + 镜像回读（roads + 资源
   段）+ 失败信封回退 Kotlin 原路径。gameData 镜像域无 Room 写回；不改 GameViewModel。
5. **测试**：GTest `road_tx_test.cpp`（happy/校验链失败臂/资源精确扣减/重复与不存在
   防御/零 RNG 审计/与 roadCompose 联动一致性——roads 变更后 compose 输出含新边）；
   Kotlin flag 门控单测。

## 4. 与 batch-06 的边界

- 建筑占位与道路占位若共享冲突判定（路上放建筑/建筑上铺路）：**判定原语放
  `building_tx.h` 与 `road_tx.h` 各自文件、互相 include 只读常量**，不建共享新头
  （避免两批并行改同一新文件）；判定序以各自 Kotlin 原实现逐字为准。
- 两批均不改 `models.h`、不改 `GridSystem.kt`（Kotlin 占位几何工具保持现状供回退臂）。

## 5. 验收

1. 两操作 AUTHORITATIVE 稳态写者归 C++；审计表 + RNG 结论进 §2.36。
2. §4 模板全跑（⑥③对拍必跑——roads 变化经月结修路/旬结路径双端一致）。
3. handover §2.36 + ui-read-surface §4.1 道路行标注 + CHANGELOG。

## 6. 本批触碰文件声明

- 新：`road_tx.h` / `road_tx_test.cpp`。
- 改：`gen-action-ids.mjs` + 生成物 / `execute_dispatch.cpp`（独立 handler + 一行）/
  `RoadFacadeImpl.kt`（+ RoadFacade 接口如需暴露状态）/ 相关测试。
- 不触碰：`RoadTiling.kt`/`RoadMaskTracker`、`GameViewModel.kt`、`GameCoreBridge.*`、
  `StateSyncService.kt`、`models.h`。
