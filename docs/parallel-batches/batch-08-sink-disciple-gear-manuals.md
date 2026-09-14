# Batch-08：UI 操作面下沉·弟子管理第一子批（装备穿脱/功法学习卸下/任命卸任）

| 项 | 内容 |
|---|---|
| 批次 | 08 ｜ 并行组 C（与 06/07/09 共享面仅 3 文件，协议见 [README](README.md) §3.3/§3.4） |
| 模块 | C++（gamecore 新域头 + execute_dispatch）+ Kotlin 门面（core:engine 弟子域） |
| 性质 | WS-2 规模下沉批：**最大残余域（弟子管理）的第一子批**——选零 RNG 纯事务子域打样 |
| 来源 | [ui-read-surface](../ui-read-surface.md) §4.1：「弟子管理——任命/装备穿脱/功法/收徒/逐出/状态同步/婚姻/仓库驻守/玉符/checkpoint（最大残余域）→ 弟子管理族 ×N 批」 |
| 预分配 | handover §2.37；**ActionId 段 1480–1499** |

## 1. 范围

**In-scope（本子批，全部零 RNG 纯事务，跨弟子行 + 背包/装备实例表/功法表双段同事务）**：

- 装备穿脱（equip/unequip——装备实例在弟子行与背包/装备实例表间转移）
- 功法学习/卸下（learnManual/unlearnManual——背包功法堆叠消耗 + manualMasteries 写入；
  §2.27 已做过 learnManual 资格守卫/堆叠消耗/写回三段重构，逻辑边界清晰）
- 任命/卸任（弟子槽位：长老/职位/巡逻关联的槽位写——DiscipleSlotOps 域）

**Out-of-scope（登记 §2.37，归 README §6 W3 弟子管理后续子批）**：婚姻/道侣、收徒/
逐出、状态同步、仓库驻守、玉符、checkpoint（生命周期与序列化敏感面，单独拍板批次）。

## 2. 地基（已就绪）

- **C++ 状态**：弟子行（DiscipleStore 列协议：装备六槽/功法/manualMasteries）+
  背包/装备实例表/功法堆叠（gameData 段）均在镜像协议——事务内双段同源写。
- **库存原语**：`INV_ADD_EQUIPMENT_STACK / INV_REMOVE_EQUIPMENT / INV_ADD_MANUAL_STACK /
  INV_REMOVE_MANUAL` 等既有 C++ 事务（execute_dispatch handleInventory）——穿脱事务
  复用原语（进程内直调，非经 dispatch 自递归）。
- **Kotlin 门面域文件**：§2.28 已把 GameEngine 族拆出 `DiscipleSlotOps` /
  `DiscipleItemOps` / `GameEngineManualOps`——native 分支接线落点清晰。
- **注意**：DiscipleFacadeImpl/DiscipleService 在 batch-01 拆分队列（68/25 函数）——
  本批接线尽量落在 Ops 域文件与 DiscipleEquipmentService，**若必须触碰 01 所有权
  文件，PR 提前声明并与 01 协调合并顺序**（README §3.2 例外条款）。

## 3. 实施步骤

1. **写者审计**：三族操作逐条调用链 + stateStore.update 触碰字段 + **RNG 标注**
   （预期零 RNG；learnManual 堆叠消耗无 roll——§2.27 重构时已确认；发现 roll 即登记）。
   特别核对"装备实例轨道 vs 堆叠轨道"双持有防重（S6 袋物化同族边界）。
2. **C++ 事务**（`gamecore/include/gamecore/system/disciple_tx.h`）：
   `equipTransaction / unequipTransaction / learnManualTransaction /
   unlearnManualTransaction / assignSlotTransaction / unassignSlotTransaction`
   （detail 函数族，production.h 风格）：校验链（弟子存在/存活/槽位占用/背包余量/
   资格守卫——逐字对齐 Kotlin 判定序）→ 弟子行列写 + 背包段写（库存原语）原子执行
   → 信封。**失败零写入**（含"扣了背包却穿不上"的中间态禁止——单事务内两段要么
   都成要么都不动）。零 RNG 论证进头注释。
3. **协议**：`DISCIPLE_TX_EQUIP=1480 / UNEQUIP=1481 / LEARN_MANUAL=1482 /
   UNLEARN_MANUAL=1483 / ASSIGN_SLOT=1484 / UNASSIGN_SLOT=1485`（1480–1499 段内
   追加）→ 重新生成两份产物；`execute_dispatch.cpp` 独立 `handleDiscipleTx`
   （与既有 `handleDisciple` 查询域分开）+ 中央一行。
4. **Kotlin 门面**：`DiscipleEquipmentService` / `GameEngineManualOps` /
   `DiscipleSlotOps` 落 native 分支（AUTHORITATIVE 门控 + 镜像回读弟子行与背包段 +
   失败信封回退 Kotlin 原路径）。**不改 GameViewModel / DiscipleDelegate**
   （DiscipleDelegate 在 batch-02 所有权内；UI 调用面经既有门面方法零变化）。
5. **测试**：GTest `disciple_tx_test.cpp`（每事务 happy/校验链失败臂零写入/装备实例
   轨道完整性——无双持有/功法堆叠精确消耗/槽位互斥（任命占用释放）/RNG 零消费
   审计）；Diff 对拍可选（装备穿脱后战斗属性经 C++ computeCombatPower 双端一致——
   推荐加 1 场景）；Kotlin flag 门控单测。

## 4. 风险与红线

- **弟子行写与镜像行级应用**：弟子行变更经信封 id 行级应用（WS-1）回镜像——事务
  信封必须携带弟子行变更集使回读增量生效（InventoryNativeForward 同族机制）。
- **manualMasteries 列**：功法熟练度为弟子行内 map 列——写路径与旬结算熟练度增长
  （C++ 已下沉）同列，无抽取序交互（本批零 RNG）。
- 穿脱装备改变 baseStats 派生（DiscipleStatCalculator 链在 batch-01 拆分中）——本批
  不触碰派生计算，只写原始列；派生仍由读时计算/镜像回流驱动。

## 5. 验收

1. 三族操作 AUTHORITATIVE 稳态写者归 C++（回退保留）；审计表 + RNG 结论进 §2.37。
2. §4 模板全跑（⑥ 桌面单测 + ③ 对拍必跑——弟子行变更路径最敏感）。
3. handover §2.37 + ui-read-surface §4.1 弟子管理行标注"第一子批已下沉" + CHANGELOG。

## 6. 本批触碰文件声明

- 新：`disciple_tx.h` / `disciple_tx_test.cpp`。
- 改：`gen-action-ids.mjs` + 生成物 / `execute_dispatch.cpp`（独立 handler + 一行）/
  `DiscipleEquipmentService.kt`、`GameEngineManualOps.kt`、`DiscipleSlotOps.kt`（native
  分支）/ 相关测试。
- 不触碰：`GameViewModel.kt`、`DiscipleDelegate.kt`（batch-02 所有权）、`GameCoreBridge.*`、
  `StateSyncService.kt`、`models.h`、batch-01 清单文件（除 §3.2 例外条款并声明）。
