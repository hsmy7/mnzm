# G06 · 删逐出弟子 + 弟子改名 + disciple_expelled 死事件 — 批次报告

> 批次：G06（M1 序列第 3 批，前置 G02 `5dbaac1e3` + G05 `8e593a71b`）；执行：三子代理并行（C++ 面 / Kotlin 生产+UI / Kotlin 测试）+ 主线程集成。
> 侦察：`recon-G05-G06-G08-G09.md` §G06-1~6（施工前全部行号 grep 复核——多处行号系 G02/G05 前快照，偏差已逐条登记）。

## 一、做了什么

### 1. 删除面（三端）

| 端 | 删除/改造 |
|---|---|
| **UI**（feature:game） | DetailRightPanel：「驱逐」按钮、`onShowExpelConfirm`/`onRenameDisciple` 回调字段、`onNameClick` 全链（姓名区回静态展示布局不塌）；DiscipleDetailScreen：双状态/双接线/改名渲染块/驱逐确认块 + 无消费者 `onDismiss` 链参数收窄；ReflectionCliffDialog：驱逐入参/状态/透传/按钮/`ReflectionExpelConfirmDialog` 组件 + 4 孤儿 import（「释放」保留）；DialogFunctionalBuildingRoutes 接线；RenameDialog：**仅删 `RenameDiscipleDialog`**（通用容器 + `RenameSectDialog(:59)` + Config 按共享性判定保留） |
| **契约/引擎**（core:engine、app） | `ExpelDiscipleUseCase` 整删；`DiscipleFacade` 删 `expelDisciple`+`dismissDisciple` 契约与实现（recon :191 待复核批量入口真身=转调实现、零其他调用方→连根清）；`GameEngine.expelDisciple`/`dismissDisciple` 扩展删；`DiscipleDelegate` 两函数删；`GameEngineCoordination.renameDisciple` 整删；`DiscipleService.expelDisciple` 整删（`disciple_expel` 归因、`materializeBagItemsToWarehouse` 调用点、`annualDesertedDisciples+1`）；`DiscipleLifecycleNativeTx` 删 `tryNativeExpelDisciple`/`EXPEL_TRACKING_SOURCE` + 孤儿 `parseBagItemDrafts/envelopeJson`（拜师/年俸事务保留） |
| **C++**（15 文件 +31/−511） | `disciple_lifecycle_tx.h`：`expelTransaction`+`ExpelResult` 整删（校验链/bagItems/清槽/销毁装备/移除行/玩家逐出计数）；仅逐出消费的 `destroyWornInstances`/`kRefiningStatusName`/`kIdleStatusName`/`using StorageBagItem`/`#include <vector>`/`#include blood_refinement.h` 按消费者 grep 连带删；`disciple_tx.h`：`renameDiscipleTx`（1740）+ recruitList 同人净化死码段 + **isSamePerson 三件套整块连删**（G05 迁移 KDoc 随块删除，当前状态口径）；1741-1748 同族七事务原样；dispatch：区间起点 1590→`DISCIPLE_LIFECYCLE_APPRENTICE`(1591)、EXPEL case 删、`dispatch_w4a.cpp` RENAME case 删；注释同步（execute_dispatch handler/w4a 段头/blood_refinement/phase_settlement×2/slot_cleanup_test/**boundary_tx.h:22**） |
| **事件** | `GameEvents.kt` 删 `DiscipleExpelledEvent`（净 7 行，纯内存 DomainEvent；零发布点/零 when 分支/零序列化注册，定义即整类） |

### 2. 双保铁律（核验贴证）

| 项 | 裁决 | 终态证据 |
|---|---|---|
| `annualDesertedDisciples` | **仅删玩家逐出两写点；字段/协议/年报面保留** | Kotlin `+1` 写点=0；C++ 写点=0（expel :252 已删）；保留面原样：GameData 字段、@ProtoNumber(132)、CultivationEventMonthlyOps 读/清、GameDataFieldPatch、models.h/json_codec/year_settlement 读+清零 |
| `renameSect` 全链 | **绝不动** | src/main 保留命中 11 处（SectDelegate×4/DialogSystemRoutes×3/RenameSectDialog×2/GameEngineLifecycleOps×2）+ MainGameScreen 门槛；回归门单跑：GameEngineSectIdentityOpsTest 2/0、GameViewModelTest 48/0 |

### 3. ActionId 退役
- `DISCIPLE_LIFECYCLE_EXPEL=1590`（归属 **core.mjs**，自查确认）与 `DISCIPLE_OP_RENAME=1740`（**w4a.mjs**）desc 改【已退役，编号禁复用】（沿 G05 先例保留原 desc）；regen EXIT=0 = **198 动作/maxId=1861**，action_ids.h/ActionIds.kt 仅两条 desc 再生成（无手改）。
- `dispatch_guard_test.cpp` retired 集 +2，实测两轮全绿（1590/1740 均落 NOT_IMPLEMENTED 不可达）。

## 二、主线程裁决记录

| # | 裁决 | 理由 |
|---|---|---|
| 1 | `DiscipleExpelledEvent` **纳入本批连根删**（c340 途中发现上报 → 查得 HANDOVER §136 项7 正登记 G06） | 死事件=只定义无发布点；逐出链删除后发布点永久消失；生产 7 行+测试 2 用例双侧清净、全树 0 命中 |
| 2 | `dismissDisciple` 批量入口**连根清**（recon :191「待复核」） | 实测=仅转调 expelDisciple、全仓零其他调用方 |
| 3 | `destroyWornInstances` **删**（T 按「仅逐出消费」判定） | grep 全 gamecore 仅定义+expel 调用两点 |
| 4 | `clearAllDiscipleSlotsForRemoval`/`eraseDiscipleDerivedMaps` **保留** | 任务书显式保留（slot_cleanup 共享壳/派生清键登记面）；当前零生产调用方 → **G10 死码评估登记** |
| 5 | isSamePerson 三件套**随 rename 连删** | G05 迁移 KDoc 明写唯一消费者=renameDiscipleTx；G06 删 rename 后即死码（全树终态 0 命中，唯退役 desc 字面文字） |

## 三、侦察快照漂移登记（施工前 grep 复核纪律的实证）

1. **执法写点已随 G02 消失**：recon §G06-2#3（month_settlement.h:1322）与 #4-6（LawEnforcementTheftTxOps.kt:339/443/469）在当前 HEAD 不存在（G02 删 law-enforcement/叛逃面）→ `annualDesertedDisciples` 现**全仓零写入**（年报恒 0）；字段保留口径不变（HANDOVER §5#3），**如需连根删字段另开批次**。
2. recon §G06-5 `month_settlement.h:1319` eraseDiscipleDerivedMaps 执法消费同上过期（文档批修订项，docs 本批不改）。
3. 行号偏差（按实测执行）：DiscipleServiceCrudTest expel 段实测 :207-241（recon 212-244 超文件总长）；UseCaseInvocationTest 实测 :77-103 含第二用例尾；GameEngineRenameTest 实际 2 个 @Test（recon 标 5 用例，:91-288 为私有夹具随整删）；DiscipleLifecycleNativeTxGateTest 按最小面改（recon 段界 125 起实为第二三入口断言，不能整段砍）。
4. `ReflectionCliff*Test` 主干 glob=0（recon「推测存在」不成立）；唯一命中系另一 worktree。

## 四、旧用例处置表

**Kotlin 测试（c340，7 文件 6M+1D）**
- 删：DiscipleServiceCrudTest（expel 三分支+计数，CRUD 余 9 用例保留）、UseCaseInvocationTest（Expel 段 2 用例）、**GameEngineRenameTest 整删**（2 @Test + 夹具 288→0）、GameEngineDiscipleOpsNativeTxGateTest（rename 降级段，余 gate 保留）、GameEventsTest（DiscipleExpelledEvent 2 用例，44→42）
- 改：DiscipleLifecycleNativeTxGateTest（三入口→两入口、回退臂-逐出用例删、五入口→四入口 KDoc、孤儿 import 删）、InventoryBagTransferTest（KDoc 去陈旧逐出提法）
- **保（回归门单跑坐实）**：GameEngineSectIdentityOpsTest（renameSect 2 用例）、GameViewModelTest（renameSect 3 用例）、DialogTypeRenderCoverageTest:53（ReflectionCliff 渲染守卫）、DiffMonthSettlementTest:734-735（annualDeserted==0 执法语义断言，不改锚）、GameEvents 其余事件用例
- 验证实数：`:core:data` 812/0/15skip + `:core:domain` 1649/0（`--rerun` 强制实跑）；engine/feature/app 测试编译 EXIT=0 零缺符号

**C++ 测试（T）**
- disciple_lifecycle_tx_test：删逐出 2 用例（annualDeserted 断言 :271/:305）+ 仅逐出 4 fixture helper + using StorageBagItem；拜师×3/婚姻批准×3/婚姻拒绝×2/年俸×1 保留；头注释四事务同步 + 顺手修「释放思过」失实 bullet（预存）
- disciple_ops_tx_test：删改名 2 用例；AllOpsConsumeZeroRng 余 8 op 零 RNG 审计保留；九→八事务头注释
- 旧用例算术：ctest 1487→1483 = 删除 4 条，通过 1483→1479，**闭合无隐性掉测**

**RNG 纪律**：金黄/baseline 零改动零重录；无新 B 类登记。

## 五、验证（全部实跑）

| 门 | 结果 |
|---|---|
| 桌面 `cmake --build` | ✅ EXIT=0（49 warnings 全为 secret_realm/production/sect_conquest 预存，非本批文件） |
| 桌面 ctest | ✅ **1483 总 / 1479 过 / 4 败**——4 败与 G05 基线**逐条同名**（ChildBirth/DiscipleFactory×2/DeterminismProbe 既有 B 类），零新增、无 A 类 |
| `compileReleaseKotlin` | ✅ BUILD SUCCESSFUL（A 三连 + 事件删除后 21 executed 全链真实重编） |
| JUnit 六模块（`--max-workers=1` + Diff JNI 桥） | ✅ **7709/7709 全绿**（app 1013 / data 812 / domain 1647 / engine 3130→3123 / ui 146 / feature:game 968；BUILD SUCCESSFUL EXIT=0，规模递减=G06 删例闭合） |
| detekt 六模块 | ✅ EXIT=0 |
| `gen-action-ids` | ✅ 198 actions (maxId=1861)，双产物仅 2 条退役 desc |
| `check-jni-count` | ✅ 86/86（本批不涉 JNI 面） |
| `build-desktop-jni.ps1` | ✅ EXIT=0（对拍库含 boundary_tx.h 最终态重建） |
| `gen-game-data --check` | 不涉本批（双绿复验） |
| `check-agent-instructions` | ✅ EXIT=0（410 引用无死链、路由表 7/7；1 处 basename 告警系 rules/static-resources.md 预存） |

## 六、grep 终态证据

- gamecore：`expelTransaction|renameDiscipleTx` **0**；`renameDisciple|expelDisciple|destroyWornInstances|ExpelResult|samePersonSignature|kSignatureSeparator` **0**；`isSamePerson`=1（1740 退役 desc 字面）；`DISCIPLE_LIFECYCLE_EXPEL`=2（保号常量+guard 登记）；`DISCIPLE_OP_RENAME`=2（同）；`annualDesertedDisciples`=5（字段/编解码/年报读清，**C++ 写入=0**）
- android 全树：`DiscipleExpelledEvent|disciple_expelled` **0**；`expelDisciple|tryNativeExpelDisciple|ExpelDiscipleUseCase|RenameDiscipleDialog|onExpelDisciple|showExpelConfirmDialog|EXPEL_TRACKING_SOURCE|renameDisciple` **0**（10 模式验收终态；唯一历史白名单 `boundary_tx.h:22` 已随 T 收尾清净）

## 七、G10 登记（本批新增）

1. `detail::clearAllDiscipleSlotsForRemoval`：任务书保留但当前零调用方 → G10 死码清理评估。
2. `eraseDiscipleDerivedMaps`：生产消费方现为零（recon 执法消费随 G02 消失；测试直调保留）→ G10 复评。
3. recon §G06-2#3/§G06-5 month_settlement 行号过期 → 文档批修订。
4. 既有：ctest 4 条 B 类（同名持平）+ Kotlin 侧金黄口径不变；Diff 家族无新增观察项（本批 Diff 断言零锚改动）。

## 八、集成收口（G03 开工前置）

- [x] G06 单次提交（serial 约束）
- [x] G05 恒空语义下 renameDiscipleTx 同人净化死码已随 rename 整删
- [x] isSamePerson 双端生命周期闭合（G05 迁入保 G06 前编译 → G06 随 rename 删）
- [x] annualDesertedDisciples 零写入状态与字段保留口径显式登记
- [x] ActionId 1590/1740 退役 + 区间起点迁移 + guard 登记

## 九、附录：分工

| 分片 | 交付 | 验证 |
|---|---|---|
| T·C++ | 15 文件 +31/−511；expel/rename 整删+isSamePerson 连带+5 项孤儿清理；区间 1590→1591；1590(core.mjs)/1740(w4a.mjs) 退役+guard +2；注释同步含 boundary_tx.h:22 | cmake EXIT=0；ctest 1483/1479/4（同名持平、算术闭合）；gen 198/1861；JNI 86/86；jni 库重建 EXIT=0；grep 终态贴证 |
| A·Kotlin 生产+UI | 17 文件（16+事件）；逐出/改名双全链+dismissDisciple 连带；双保核验；10 模式 grep 白名单曾=1（已由 T 清净） | compileReleaseKotlin ×3 EXIT=0 + 事件删除后 21 executed 真实重编 |
| c340·Kotlin 测试 | 7 文件（6M+1D）；G06-6 清单+GameEventsTest 追加；双保回归门单跑；recon 行号偏差按实测处置 | data 812/0 + domain 1649/0（--rerun 实数）；engine/feature/app 测试编译 EXIT=0 零缺符号；GameEventsTest 42/0 |
| 主线程 | DiscipleExpelledEvent 纳入裁决（HANDOVER §136 项7）+ 双侧下发；全门禁链；双 changelog；本报告 | 见 §5 终验行 |
