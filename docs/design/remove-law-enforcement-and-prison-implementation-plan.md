# 执法堂 / 执法长老 / 执法弟子 / 监牢 删除实施文档

> **实施状态：已完成（2026-09-27）**——实施结果与实跑门禁见
> [`remove-law-enforcement-and-prison-report.md`](remove-law-enforcement-and-prison-report.md)。
> 用户拍板：P-1 下架「赏善罚恶」政策（方案正文 §0.4 的 (A) 选项）、P-2 删除建筑素材（(A) 选项）、P-3 版本号不动；
> 途中发现 F-1~F-5 已一并解决。**唯一剩余门禁缺口**：另线在途重构（remove-2x-speed）导致 `:feature:game` 暂不可编译，
> 见报告 §6。

> 文档性质：**删除批实施文档**（可长期维护的完整方案，照单实施即可，不含"后续优化"尾巴）
> 编写依据：`rules/design-plan-review.md`（§0 六原则 + 第一~八节自检清单）、`rules/database-migration.md`、
> `rules/static-resources.md`、`rules/pr-review-checklist.md`、`rules/version-release.md`
> 行号口径：本文所有 `file:line` 均为**编写时点树快照**，实施时以符号名 grep 定位为准（派工不得只按行号）。
> 前置事实来源：`docs/design/gacha-batches/report-G02.md` §保留项登记 #2/#3/#14、`docs/design/gacha-batches/report-G04.md`
> §1/§二·补（"连建筑整建制拆除"先例）、`CHANGELOG.md`（4.01.14 已验证"执法堂玩法下线、建筑保留"）。

---

## 0. 结论摘要与决策分级

**一句话结论**：执法长老 / 执法弟子 / 执法堂 的**玩法职责在 G02（2026-09-23）已被删除**（捕获率、叛逃捕获、偷盗捕获结算全部下线），
仅剩**数据字段 + 建筑壳 + 引导条目 + 一个零调用方的任命 API**；监牢（旧称思过崖）的**唯一在役入口**是
`ReflectionCliffDialog` 的"释放"按钮——而它的写入方（叛逃捕获 / 偷盗捕获 / 年结思过释放）**已全部下线**，
所以本次删除是"**清死面 + 清死壳**"，但有一处**必须同批处理的真实玩家问题**（见 §0.3）。

**决策分级**：**架构级重构**（跨 Kotlin 六模块 + C++ 引擎 + Room/ProtoBuf 存档 + 生成器/图集管线 + 引导双端注册表；
触及 `/docs` 活文档与 3 张手工快照期望表）。按设计方案规则全流程执行。

### 0.1 现状（调查结论）

| 系统 | 当前真实状态 | 证据锚点 |
|---|---|---|
| **执法长老**（`lawEnforcementElder`） | 数据字段存活、ProtoBuf 持久化、读档建 gate、状态推导可用；**全仓无任何 UI 任命/卸任入口**（G02 删 `LawEnforcementHallDialog` 时一并删掉了任命面）→ 实际不可达 | `SectViewModel.kt:81`、`ProductionViewModelElderOps.kt:58`（两个查询 API **零调用方**）；`feature/game` 全模块 `ElderSlotType.` 引用共 20 处（`SectViewModel` 2 / `ProductionViewModelElderOps` 2 / `QingyunPeakDialog` 4 / `TianshuHallDialog` 8 / `WenDaoPeakDialog` 4），**无一处 `LAW_ENFORCEMENT`** |
| **执法弟子**（`lawEnforcementDisciples`） | 同上；`DiscipleStatus.LAW_ENFORCING`（"执法弟子"）仍由 `SlotFlags.lawEnforcing` 推导，但因无人可任命 ⇒ 恒不触发 | `ElderSlotType.kt:10/22/37`、`DiscipleStatusService.kt:97/127/170/660-661` |
| **执法堂建筑**（`law_enforcement_hall` / `LAW_ENFORCEMENT_HALL`） | 可建造、可拆除、可点开（点击**无对话框**——`buildingOpenAction` 无该 key 分支）；职责只剩"承载执法长老槽位" | `BuildingFeatureBoot.kt:92-96`、`BuildingsTab.kt:62`、`Defaults.kt:128-140/336-342` |
| **监牢建筑**（`reflection_cliff` / `REFLECTION_CLIFF`） | 可建造、可点开 `ReflectionCliffDialog`（列表 + "释放"）；拆除时全量释放思过弟子 | `ReflectionCliffDialog.kt` 整文件、`BuildingFacadeImpl.kt:701-703`、`BuildingFacadeImpl同步Ops.kt:312-322` |
| **思过（REFLECTING）状态** | **枚举值 + 受保护推导分支存活**；写入方（叛逃捕获/偷盗捕获/年结释放）已随 G02 全删；**无任何读档归一化规则** | `DiscipleStatusService.kt:84`、`report-G02.md:73`；`data/integrity/rules/` 下无 REFLECTING 相关规则（已 grep 实测） |
| **执法域结算** | 已删除（`month_settlement.h` 叛逃/偷盗整段、`LawEnforcementProcessor`/`LawEnforcementTheftTxOps` 整文件删） | `report-G02.md:15`、`docs/cpp-engine.md:561`（S-13 标注"执法堂域已删"） |
| **执法域配置壳** | `GameConfig.LawEnforcementConfig`（`THEFT_*` ×14）+ `PolicyConfig.REWARD_PUNISH_EFFECT` **零生产消费者** | `GameConfig.kt:857-883`；全仓 grep `REWARD_PUNISH_EFFECT` 仅定义处 1 命中 |

### 0.2 删除范围（本方案做什么）

1. 执法堂建筑（`law_enforcement_hall`）+ 其全部三端定义、配置、别名、图集槽位。
2. 监牢建筑（`reflection_cliff`）+ 其全部三端定义、配置、别名、图集槽位、对话框与路由。
3. 执法长老槽位字段 `lawEnforcementElder` + 执法弟子槽位列表 `lawEnforcementDisciples`（Kotlin + C++ + ProtoBuf 双端）。
4. `ElderSlotType.LAW_ENFORCEMENT` 枚举值 + 其全部 9 处同步点。
5. `BuildingType.LAW_ENFORCEMENT_HALL` / `BuildingType.REFLECTION_CLIFF` 枚举值。
6. 引导任务 13「宗门律法」/ 14「执法亲传」/ 25「监牢惩戒」（Kotlin + C++ 双注册表），并登记 **13/14/25 为空号禁复用**。
7. 死 API / 死代码收口：`getLawEnforcementElder/Disciples`（Kotlin ×2 文件）、`SLOT_TYPE_LAW_ENFORCEMENT` 分支族、
   `ElderPositions.LAW_ENFORCEMENT`、`BuildingNames` 映射、`ProductionSlotRepository.BUILDING_ID_MAP` 两行、
   `GameConfig.LawEnforcementConfig`、`GameConfig.Elder.REALM_LAW_ENFORCEMENT` + `realmLawEnforcement` 配置链。

### 0.3 🔴 必须同批处理的真实玩家问题（**不是可选项**）

**问题**：`DiscipleStatus.REFLECTING` 是**受保护状态**——`deriveDiscipleStatus` 对其"永不回退"
（`DiscipleStatusService.kt:84`、`disciple_tx.h:1061`）；而**它的全部生产写入方已随 G02 下线**
（叛逃捕获 → REFLECTING、偷盗捕获 → REFLECTING、年结 `processReflectionRelease` 释放全删）。
今天唯一能让 REFLECTING 弟子回到 IDLE 的路径就是：

- 监牢对话框的"释放"按钮（`ReflectionCliffDialog.kt:139` → `releaseReflectionDisciple`）；
- 弟子选择界面勾选"显示所有弟子"后选中该弟子（`SpiritMineViewModel.kt:269`、`DiscipleDelegateLifecycleOps.kt:22` 等 `REFLECTING -> releaseReflectionDisciple` 分支）。

**若本批直接把监牢对话框与释放入口删掉，老存档中被判定为 REFLECTING 的弟子将永久卡死**
（不可分配、不可参战、不可被任何系统使用，且玩家无任何 UI 可解除）。
因此本方案**强制包含**一条读档归一化规则（`SaveValidator`，见 §4.8），把存量 REFLECTING 直接归零为 IDLE 并清除思过双键。

**同批顺带解决的对称项**：`LAW_ENFORCING` 同样可能出现于老存档（本次删除字段前曾可写入），
归一化规则一并覆盖，避免"删了槽位字段但状态列残留"的短暂不一致。

### 0.4 待用户拍板项（实施前必须回答，不阻塞本文档其余部分）

| # | 待拍板 | 选项 | 本方案默认建议 |
|---|---|---|---|
| **P-1** | 宗门政策「赏善罚恶」（`sectPolicies.rewardPunish`，月耗 3000 灵石，UI 文案"执法效率+30%"） | (A) 一并下架该政策开关（政策本体 + 常量 + UI 行 + 扣费）；(B) 保留政策但改写文案为当前真实效果 | 建议 **(A)**：其唯一效果（抓捕率 +30%）的消费方已随执法域删除，现为**纯支出零效果**的空政策，保留即误导玩家。若选 (B) 需产品给出新效果口径 |
| **P-2** | 执法堂/监牢的 `building_reflection_cliff.webp` / `building_law_enforcement.webp` 是否删除素材文件 | (A) 随建筑删除（G04 血炼池先例）；(B) 素材暂留（不动 webp，仅删图集槽位） | 建议 **(A)**：G04 已确立"连建筑整建制拆除"先例；素材源文件另有归档（`模拟宗门美术素材/`、`rules/media-source-assets.md`），删仓内产物不丢原图 |
| **P-3** | 当前版本号 | `version.properties` 为 `4.01.14`，而 `CHANGELOG.md` 段头已是 `4.01.16`（G14 拍板项在途） | **本方案不动版本号**（`AGENTS.md` §8：由用户判断和指令）；双 changelog 按当前版本条目追加 |

---

## 1. 背景与目标

### 1.1 背景

`docs/design/character-gacha-redesign-2026-09-23.md` 的产品决策链把"执法/监牢"判为**随叛逃系统一起退役**：

- `:277`「执法堂思过链 → 随叛逃退役」
- `:474`「执法堂/监牢/仓库驻守 → 入口下架（建筑图闲置）」

G02 批（`report-G02.md`）执行了**结算面删除**，但在 §保留项登记中明确把**建筑与槽位壳体留给了后续收口**：

- `:72` #2「`lawEnforcementElder` / `lawEnforcementDisciples` 长老槽位 + `LAW_ENFORCING` 状态 + 执法堂建筑 →
  **职责（捕获率）已失** ⇒ 登记为后续收口项（含 guide 任务 17/18、UI 任命入口是否退役待产品确认）」
  （注：该条把引导任务号写成了 17/18，实测**受影响的引导任务是 13/14**，见 §4.6）
- `:73` #3「`DiscipleStatus.REFLECTING` 枚举值 + 推导保持分支 + 监牢拆除/秘境换岗/任务派遣的思过键清理 →
  **旧档反序列化兼容**（删枚举值会炸档）；生产写入方已全部下线，**仅存量归一化**」
- `:84` #14「`law_enforcement_hall` 建筑 + 引导任务 13/14 → 建筑与任命槽位存续（长老系统未列建筑面）」→ 待收口

已对玩家公布的文案（`changelog_entries.json:11`，4.01.14）：**"执法堂与仓库驻守玩法下线——相关建筑保留，引导任务改为只看建筑落成"**。
本批正是这条"建筑保留"承诺的**兑现方**：把建筑本身也拆掉，并同步改写引导。

### 1.2 目标（成功标准）

| # | 成功标准 | 验证方式 |
|---|---|---|
| G1 | 玩家侧：建造栏/地图/建筑详情/引导列表中**不再出现**"执法堂""监牢" | 真机 + `BuildingTypeCoverageTest`/`BuildingFeatureRegistry` 守卫 |
| G2 | 存档侧：含执法堂/监牢实例、执法长老、REFLECTING/LAW_ENFORCING 状态的旧档**可正常读入且数据零丢失**（仅下线内容被清理） | `SaveValidator` 新规则单测 + Room 全链迁移测试 + 手工旧档实测 |
| G3 | 玩家侧：旧档中被卡在 REFLECTING 的弟子**全部自动回归空闲**，无需任何操作 | 归一化规则单测 + 端到端用例 |
| G4 | 引导侧：引导任务列表不含死步骤、**任何老档进度都不会因删除而错位或卡死** | `GuideTaskTest` + C++ `guide_reward_tx_test` + 空号禁复用断言 |
| G5 | 生产面零残留：`lawEnforcement` / `LAW_ENFORCING` / `law_enforcement_hall` / `reflection_cliff` / `监牢` / `执法堂` 在**生产面**（Kotlin 六模块 `src/main` + C++ `gamecore/include|src|jni` + `scripts`）grep 归零（豁免项逐条贴证） | 终态 grep 证据表（§13.4） |
| G6 | 三端构建与测试门禁全绿：`compileReleaseKotlin`、六模块 JUnit、detekt、`cmake --build` + ctest、NDK、`lintRelease` | §13.3 门禁表 |
| G7 | 图集三件产物（`SpriteAtlasDef.kt` / `atlas_astc.ktx` / `atlas-rgba-*`）与布局源同步重生成，**3 张手工快照期望表**同步 | `generateAstcAtlas` + `generateOfflineRgbaAtlas` + `generateSpriteCode` 全绿；`AtlasManifestSyncTest`/`SceneEquivalenceTest` 绿 |

---

## 2. 系统现状全貌（调查明细）

### 2.1 执法长老 / 执法弟子（槽位链）

**数据定义**

| 层 | 位置 | 内容 |
|---|---|---|
| Kotlin 模型 | `android/core/domain/src/main/java/com/xianxia/sect/core/model/GameDataSectModels.kt:77-78` | `ElderSlots.lawEnforcementElder: String`（`@ProtoNumber(9)`）、`lawEnforcementDisciples: List<DirectDiscipleSlot>`（`@ProtoNumber(10)`） |
| Kotlin 槽位类型 | `.../core/model/ElderSlotType.kt:10` / `:22` / `:37` | `LAW_ENFORCEMENT` 枚举值 + key `"lawEnforcementElder"` + 显示名 `"执法长老"` |
| 旧档兼容副本 | `android/core/data/src/main/java/com/xianxia/sect/data/serialization/backwardcompat/OldSerializableSaveData.kt:812-813` | `SerializableElderSlots` 的同号字段（历史 JSON 档解析面） |
| Room 存储 | `android/core/data/src/main/java/com/xianxia/sect/data/local/EnumConverters.kt:28-34` | `elderSlots` 列为 **base64(ProtoBuf(ElderSlots))**，**非** JSON、**非**独立列 |
| C++ 模型 | `android/app/src/main/cpp/gamecore/include/gamecore/state/models.h:514-515` | `std::string lawEnforcementElder; std::vector<DirectDiscipleSlot> lawEnforcementDisciples;` |
| C++ 编解码 | `.../src/json_codec.cpp:370` / `:380` | `GC_TO`/`GC_FROM` 各一行 |

**业务接线（全部为"壳体存活、职责已死"）**

| 位置 | 语义 | 现状 |
|---|---|---|
| `ElderManagementUseCase.kt:37` | `REALM_LAW_ENFORCEMENT` 常量 | 仅 `GameConfigConsistencyTest` 消费 |
| `ElderManagementUseCase.kt:44` | `SLOT_TYPES_CLEARING_DIRECT_DISCIPLES` 含 `LAW_ENFORCEMENT` | 可达但无入口 |
| `ElderManagementUseCase.kt:76` / `:89` | 全长老 ID / 全亲传 ID 收集 | 活 |
| `ElderManagementUseCase.kt:102` | 任命时清空的亲传列表映射 | 活 |
| `ElderManagementUseCase.kt:214-217` / `:293-296` | 任命/卸任写段 | 活但不可达 |
| `ElderManagementUseCase.kt:358` | `getElderIdBySlotType` | 活 |
| `appointment_tx.h:81` / `:97` | C++ 任命/清列表（`"LAW_ENFORCEMENT"` 字符串分派） | 活但不可达 |
| `DiscipleAssignmentGate.kt:123` / `:132` | 读档重建 gate 注册 | **活（老档会真的注册）** |
| `DiscipleSlotCleanup.kt:203` / `:224` | 弟子死亡/释放时清槽 | 活 |
| `DiscipleSlotManager.kt:188` / `:195` | 批量重置清槽 | 活 |
| `DiscipleStatusService.kt:187-188` / `:597` / `:606` | 占用弟子收集 / 重置清槽 | 活 |
| `DiscipleStatusService.kt:660-661` | `buildOfficerFlags` → `lawEnforcing` | 活 |
| `DiscipleStatusService.kt:97` / `:127` / `:170` | 推导表 / flags 构建 / `SlotFlags` 字段 | 活 |
| `disciple_tx.h:1105-1108` / `:1065` / `:1035` | C++ 同构推导 | 活 |
| `disciple_tx.h:1172` | 职位名 `"执法长老"` 派生 | 活 |
| `slot_cleanup.h:71` / `:90` | C++ 槽位清理 | 活 |
| `month_settlement.h:186` / `:193` | 月结占用弟子收集（10 单槽 + 7 列表） | 活 |
| `SlotWinner.kt:51` / `:59` | 双槽位自愈赢家注册 | 活 |
| `GameEngineSelfHealOps.kt:203` / `:231` | 自愈写回 | 活 |
| `DiscipleFacadeImpl.kt:480` | `resetDirectSlotAt` 分支 | 活 |
| `DiscipleFacadeImpl战斗Ops2.kt:207` / `:244` / `:279` | 亲传读/增槽/列表取 | 活 |
| `DiscipleConstants.kt:33` | `SLOT_TYPE_LAW_ENFORCEMENT = "lawEnforcement"` | 活 |
| `GameDataSectModels.kt:93` / `:100` / `:133` | 占位判定 / 职位名解析 | 活 |
| `ProductionProcessor.kt:180` / `:184` | 占用槽位弟子 ID 收集列表 | 活 |
| `SectViewModel.kt:81-88` / `ProductionViewModelElderOps.kt:58-65` | 查询 API | **零调用方（死 API）** |
| `guide_reward_tx.h:149` / `:162` + `GuideTask.kt:82` / `:105` | 引导条件字段分派 | 仅引导 13/14 使用 |

**结论**：整条槽位链**除"数据持久化 + 状态推导 + 清槽"外无任何生产职责**；任命面在 G02 已被删除，
所以本次删除**不会有任何玩家可见的功能回退**（只是老档中已存在的执法长老会回归空闲）。

### 2.2 执法堂建筑链

| 层 | 位置 | 内容 |
|---|---|---|
| 建筑类型枚举 | `.../core/model/production/ProductionSlot.kt:264` / `:282` | `LAW_ENFORCEMENT_HALL` + 显示名 |
| 注册表（生产） | `android/feature/game/src/main/java/com/xianxia/sect/ui/game/building/BuildingFeatureBoot.kt:92-96` | key `law_enforcement_hall`、`SlotGroup.ElderPositions.LAW_ENFORCEMENT`、cost 6000、6×3 |
| 槽位组 | `.../core/engine/domain/building/BuildingFeature.kt:308-320` | `ElderPositions.LAW_ENFORCEMENT`（clear/collect 规格） |
| 配置兜底 | `.../core/engine/.../config/Defaults.kt:128-140` | `createManagementStageBuildings()` 条目 |
| 配置资产 | `android/app/src/main/assets/config/buildings.json:103-117` | 与 `Defaults.kt` 互为镜像（构建期校验一一对应） |
| 别名表 | `Defaults.kt:336-342`（5 别名）+ `buildings.json:284-286`（3 别名） | `lawenforcementhall` / `lawenforcement` / `zhifatang` / `执法堂` … |
| 名称映射 | `.../core/engine/.../util/BuildingNames.kt:25-26` | `"lawenforcementhall"`/`"lawEnforcementHall"` → "执法堂" |
| 生产槽 repo | `.../core/engine/.../repository/ProductionSlotRepository.kt:44` | `LAW_ENFORCEMENT_HALL to "law_enforcement_hall"` |
| UI 建造栏描述 | `feature/game/.../tabs/BuildingsTab.kt:62` | `"law_enforcement_hall" to "维护宗门纪律"` |
| UI 建造栏动作 | `BuildingsTab.kt:84-98` | **无该 key 分支**（点击无对话框） |
| 地图点击 | `feature/game/.../MainGameScreen.kt:407-427` | `else -> { _ -> Unit }`（无分支） |
| 建筑详情路由 | `feature/game/.../BuildingSelection.kt:46-56` | **无该 key**（点击走兜底日志） |
| 拆除残差（Kotlin） | `BuildingFacadeImpl.kt:677-716` | 槽组过滤 + `ElderPositions` 末座判定（"最后一座执法堂才清长老"） |
| 拆除残差（native 转发） | `BuildingNativeTx.kt:263-278` / `:303-312` | `ElderPositions` 组**不发线**（留 Kotlin 补扫） |
| 引导任务 13/14 | `GuideTask.kt:346-361`、`guide_reward_tx.h:106-109` | `BuildingCount("执法堂",1)` + 任命条件 |
| 图集 | `android/scripts/build-atlas.mjs:124`（`buildingNames[10]`）、`:143`（`footprints[10]`）、`:361`（drawable） | 索引 10；`docs/design/topdown-view-art-spec.md:85` |
| 精灵文件 | `android/feature/game/src/main/res/drawable-nodpi/building_law_enforcement.webp` | 仅 game 模块（app 模块仅 `building_tianshu_hall.webp`） |
| uid 映射 | `android/scripts/sprite-uid-map.json:38`（uid 28） | **保留不复用**（G04 血炼池先例：`blood_refining_pool: 20` 在删除后仍留档） |

### 2.3 监牢（思过崖）链

| 层 | 位置 | 内容 |
|---|---|---|
| 建筑类型枚举 | `ProductionSlot.kt:266` / `:284` | `REFLECTION_CLIFF` + 显示名 `"监牢"` |
| 注册表（生产） | `BuildingFeatureBoot.kt:107-110` | key `reflection_cliff`、**空槽组**、cost 20000、4×4 |
| 配置兜底 | `Defaults.kt:115-127` | `createManagementStageBuildings()` 条目（`slotCount = 6` 历史残留值） |
| 配置资产 | `buildings.json:88-102` | 同上 |
| 别名表 | `Defaults.kt:350-354` + `buildings.json:289-290` | `reflectioncliff` / `siguoya` / `监牢` |
| 名称映射 | `BuildingNames.kt:29-30` | → "监牢" |
| 生产槽 repo | `ProductionSlotRepository.kt:46` | `REFLECTION_CLIFF to "reflection_cliff"` |
| UI 描述/动作 | `BuildingsTab.kt:64` / `:95` | 描述 + `openReflectionCliffDialog()` |
| 地图点击 | `MainGameScreen.kt:418` | `DialogType.ReflectionCliff` |
| 建筑详情路由 | `BuildingSelection.kt:54` | `DialogType.ReflectionCliff` |
| 对话框类型 | `core/domain/.../domain/dialog/DialogType.kt:86` | `data object ReflectionCliff` |
| 路由 | `core/ui/.../navigation/GameRoute.kt:22` / `:72` | `GameRoute.ReflectionCliff` + 映射 |
| 导航门面 | `NavigationDelegate.kt:61-64` | `openReflectionCliffDialog()` |
| 话题路由 | `OverlayDialogRouter.kt:47-52` + `DialogFunctionalBuildingRoutes.kt:14/35/135-150` | 渲染分派 |
| 对话框本体 | `feature/game/.../dialogs/ReflectionCliffDialog.kt`（**整文件 150 行**） | 列表 + 「释放」按钮 |
| **native 特例（Kotlin）** | `BuildingNativeTx.kt:273`（`isReflectionCliff` 参数） | 拆除时全量释放 REFLECTING |
| **native 特例（C++）** | `building_residual_tx.h:33` / `:65` / `:70-71` / `:107-108` / `:169-177` | `ResidualTarget.isReflectionCliff` + 释放段 + 思过双键 |
| 拆除释放（Kotlin 回退臂） | `BuildingFacadeImpl.kt:701-703` + `BuildingFacadeImpl同步Ops.kt:312-322` | `releaseReflectingDisciples()` |
| 拆除覆盖守卫豁免 | `BuildingRemovalCoverageTest.kt:24` | `intentionallyExcluded = setOf("reflection_cliff", "mission_hall")` |
| 引导任务 25 | `GuideTask.kt:433-439`、`guide_reward_tx.h:127` | `BuildingCount("监牢",1)` 单条件 |
| 图集 | `build-atlas.mjs:124`（索引 13）、`:144`、`:364`；`topdown-view-art-spec.md:88` | |
| 精灵文件 | `feature/game/src/main/res/drawable-nodpi/building_reflection_cliff.webp` | uid `sprite-uid-map.json:45` = 35（保留） |

**在役玩家语义**：监牢无任何生产/资源功能；唯一交互是"查看思过弟子 + 释放"。
而思过弟子**已不可能新增**（§0.3）⇒ 该对话框对**新档恒为空态**，对**老档**是唯一解救入口（§4.8 用归一化替代）。

### 2.4 状态枚举残留面

| 状态 | 定义 | 生产写入方 | 处理口径（本方案） |
|---|---|---|---|
| `LAW_ENFORCING` | `Disciple.kt:250` + 显示名 `:263` "执法弟子" | `SlotFlags.lawEnforcing` ← 执法槽位（本批删除后恒 false） | **保留枚举值 + displayName**（旧档反序列化兼容，同 `WAREHOUSE_GARRISON` 先例），删推导规则与 flags 字段；新增读档归一化 |
| `REFLECTING` | `Disciple.kt:250` + 显示名 `:265` "监牢中" | **零**（G02 已删全部写入方） | **保留枚举值 + displayName**；新增读档归一化（§0.3 / §4.8） |
| `WAREHOUSE_GARRISON` | `Disciple.kt:254`（注释已标"旧档兼容保留"） | 零 | 不动（本批参照对象） |

**为什么保留而不是删枚举值**：
① `DiscipleStatus` 以 **String name** 持久化于 `discipleTables.statuses`（Room 列）与 C++ `statuses` 组件列，
`safeDiscipleStatus`（`DiscipleSerializer.kt:295-301`）与 `GameViewDiscipleRows.kt:293` 有 IDLE 兜底、但
`discipleTables` 稀疏组件表的解码面**不保证四处口径一致**（G02 §保留项 #3 的原始判断：删枚举值会炸档）；
② `WAREHOUSE_GARRISON` 已确立"保留枚举值 + 只删生产写入方"的**同族先例**；
③ 保留枚举值的代价仅为一行 `when` 分支与一条 displayName，收益是**零存档风险**。

---

## 3. 删除范围与边界

### 3.1 删除清单（Do）

见 §5 影响范围清单（逐文件）。按语义分组为 8 个工作面：

- **WS-1 数据模型与存档兼容**（`ElderSlots` 字段退役 + ProtoBuf reserved + 新增归一化规则）
- **WS-2 状态机**（`LAW_ENFORCING` 推导链删除；`SlotFlags.lawEnforcing` 删除）
- **WS-3 长老槽位业务链**（`ElderSlotType`、`ElderManagementUseCase`、gate/cleanup/selfheal/winner/worker 族、C++ 槽位清理）
- **WS-4 建筑链**（`BuildingType`、注册表、配置、别名、名称、repo、槽组、拆除残差）
- **WS-5 UI 链**（建造栏、地图点击、详情路由、DialogType/GameRoute/导航、对话框整删、设置页文案）
- **WS-6 引导链**（双注册表 + 条件分派 + 空号禁复用登记）
- **WS-7 图集与静态资源**（LAYOUT/webp/三件产物重生成 + 3 张手工快照期望表）
- **WS-8 死配置收口**（`LawEnforcementConfig`、`REALM_LAW_ENFORCEMENT`/`realmLawEnforcement`、P-1 政策）

### 3.2 明确不做（Out of scope，含理由）

| 项 | 理由 |
|---|---|
| 删 `DiscipleStatus.LAW_ENFORCING` / `REFLECTING` 枚举值 | §2.4：旧档反序列化风险 > 收益 |
| 删 `DiscipleStatus.WAREHOUSE_GARRISON` | 不属本批范围（G02 已定"保留"） |
| 删 `DiscipleStatusService` 的 REFLECTING 受保护分支 | 归一化规则已把 REFLECTING 清零，但保留该分支是**纵深防御**（若历史路径仍写入，弟子不会卡死而是被释放路径接管）；删它需先证"零写入方"，收益不足。**登记为技术债**（§10） |
| 清 `building_residual_tx.h` 的 `isMissionHall` 特例 / 任务阁链 | 与监牢特例同文件但不同功能；不动（仅删 `isReflectionCliff` 相关） |
| 删 `TalentType.POSITION_LAW_ENFORCEMENT` / 词条 `aff_pos_law_enforcement` | G02 §保留项 #8/#13：条目已删，枚举值仅用于旧档 id 解析（`mapNotNull` 静默丢弃） |
| 删 `sprite-uid-map.json` 两条 uid | G04 先例：uid 不复用，留档 |
| 改版本号 | `AGENTS.md` §8：由用户判断和指令 |
| 处理 `prisonerSpiritRootFilter`（俘虏灵根筛选） | 名字含"俘"但与执法/监牢无关，G05/G10 已登记为独立收口项 |

### 3.3 保留但必须同步收窄的清单（Modify-not-delete）

| 位置 | 收窄方式 |
|---|---|
| `GuideTask.kt:77-86` `ElderAppointed` 字段分派 | 删 `"lawEnforcementElder"` 分支（其余 6 字段保留——任务 7/16/18 仍在用） |
| `GuideTask.kt:102-108` `DirectDiscipleActive` 字段分派 | 删 `"lawEnforcementDisciples"` 分支（其余 3 字段保留——任务 2/17/19 仍在用） |
| `guide_reward_tx.h:142-153` `elderSlotOccupant` | 删 `"lawEnforcementElder"` 行（unknown field → `kEmpty` 语义不变） |
| `guide_reward_tx.h:157-170` `directDiscipleActiveCount` | 删 `"lawEnforcementDisciples"` 行 |
| `ElderSlotTypeCoverageTest.kt:56/105/143` | 删 `LAW_ENFORCEMENT` 三处（枚举值删除后必然编译失败，同步点） |
| `StatusDerivationCoverageTest.kt:49` | 删 `LAW_ENFORCING to "lawEnforcing"` 映射 |
| `ElderSlotsStatusCoverageTest.kt:181/186` | 删两条 `SLOT_SAMPLES`（反射双向校验会强制同步） |
| `BuildingTypeCoverageTest.kt:48/50` | 删两个 `BuildingType` 期望 |
| `BuildingFeatureTestRegistration.kt:87-96` | 删两条测试注册 |
| `BuildingRemovalCoverageTest.kt:24` | `intentionallyExcluded` 收窄为 `setOf("mission_hall")` |
| `DialogTypeRenderCoverageTest.kt:52` | 删 `DialogType.ReflectionCliff` |
| `GuideTaskTest.kt:44-45` / `:49-56` / `:76` | 25→22；ID 集合改 `(1..12)+(15..23)+(26)`；追加 13/14/25 三个空号 `getTask()==null` 断言 |
| `SpriteAtlasDefGeneratedTest.kt:212-245` | `BUILDING_NAMES` 18→16、`FOOTPRINTS` 18→16（**同步**删两行期望并重算索引顺序） |
| `BuildingSpriteFootprintGuardTest.kt:39/42/57` | 删两行尺寸期望；数量断言 18→16 |
| `scene_equivalence_test.cpp:157/160` | 删两行 rect 夹具 + **重算其后所有建筑 rect 与 `kStructureNameBase`**（G04 §二·补 行 1 的教训） |
| `guide_reward_tx_test.cpp:216-233` | `reg.size()` 24→21；id 循环与单条件奖励断言收窄；`findTask(26)` 断言保留（见 §14 途中发现 D-1） |

---

## 4. 技术方案

### 4.1 WS-1 数据模型与 ProtoBuf 字段退役

**Kotlin**（`GameDataSectModels.kt:66-136`）：

1. 删除 `@ProtoNumber(9) val lawEnforcementElder` 与 `@ProtoNumber(10) val lawEnforcementDisciples` 两行。
2. 在 `ElderSlots` 类内补 **reserved 注释**（本项目 Kotlin 侧 wire 预留写法，见 `GameData.kt:384/541/572/621/742/780`、
   `DiscipleSerializer.kt:317/324`）：
   ```kotlin
   // reserved 9,10;（lawEnforcementElder/lawEnforcementDisciples 字段号已退役，禁止复用）
   ```
   **号禁复用**原因：旧档二进制里 tag 9/10 有真实数据，复用号会让旧档字节按新语义解码（wire 漂移）。
3. `isDiscipleInAnyPosition`（`:88-105`）删两处引用（`allElderIds` 去掉 `lawEnforcementElder`、
   `allDirectDiscipleIds` 去掉 `lawEnforcementDisciples`）。
4. `resolveElderPositionName`（`:123-135`）删 `lawEnforcementElder -> formatSlotTypeName(LAW_ENFORCEMENT)` 行。
5. KDoc 不改写为"删除"叙述（`rules/code-comment.md` 七项检查：只描述当前状态）；`resolvePositionName` 的
   "10 长老槽位 + 3 弟子列表" 计数注释改为实测值 **9 + 6**。

**旧档兼容副本**（`OldSerializableSaveData.kt:804-813`）：`SerializableElderSlots` 的 `@ProtoNumber(9)/(10)` 两行
**保留**（历史 JSON 档解析面；`OldSaveFormatDeserializer` 走 `ignoreUnknownKeys = true`，保留不产生副作用），
仅在其映射到新模型时**不再赋值**（映射代码在新模型删字段后自然不引用）。若映射代码出现编译错误则同批删该行。

**C++**：`models.h:514-515` 两字段删除；`json_codec.cpp:370/380` 两 `GC_*` 宏参数删除。
**兼容性**：`json_codec` 的 `readField` 为**宽松读**（键缺失保持默认值），旧 C++ 快照 JSON 含该键时被忽略——
与 Kotlin 侧 `ignoreUnknownKeys` 语义对称。

**Room 迁移判定**：**零迁移**。理由：`elderSlots` 列本身不变（TEXT + `EnumConverters` 的 ProtoBuf base64），
字段级变更发生在列**载荷**内部，不触发 Room schema 变更。`DATABASE_VERSION` 保持 **59**（`GameDatabase.kt:95`），
`schema/*.json` 零改写（历史 schema 快照禁改，见 `report-G04.md` §三坑 1）。

**ProtoBuf 解码安全性的硬证据**（本方案的兼容性基石）：

- `kotlinx.serialization` 的 ProtoBuf 解码**按 wire format 规范跳过未知字段号、不抛异常**——
  行为由既有测试固化：`android/core/data/src/test/java/com/xianxia/sect/data/serialization/unified/SaveDataDirectSerializationTest.kt:121-151`
  （`protoBuf decode skips unknown field numbers instead of throwing`，注入 tag 999 后已知字段正常解码）。
- 因此删除 `ElderSlots` 的 9/10 号字段后，**旧存档中其余 15 个长老槽位字段逐一原样解码**（不触发 `default()` 兜底）。
- ⚠️ **禁止**把 `ProtobufConverters.decodeFromBase64`（`ProtobufConverters.kt:200-221`）的 `default()` 兜底当作兼容手段：
  它是"解码抛异常 → 返回 `ElderSlots()`"的**全量清零**语义，一旦触发会**丢掉全部长老任命**。
  本方案依赖的是"未知字段被跳过"而非"异常兜底"，因此必须补 **§4.1 单测**证明旧载荷（含 tag 9/10）解出的
  **其他槽位字段逐值相等**。

### 4.2 WS-2 状态机收窄

1. `DiscipleStatusService.kt`：
   - `SLOT_FLAG_STATUS_RULES`（`:93-107`）删 `{ flags -> flags.lawEnforcing } to DiscipleStatus.LAW_ENFORCING` 行。
   - `buildSlotFlagsFor`（`:116-138`）删 `lawEnforcing = officer.lawEnforcing` 实参。
   - `SlotFlags`（`:166-179`）删 `val lawEnforcing: Boolean = false`。
   - `SyncIndex`（`:144-159`）+ 索引构建（`:364` 附近）+ `buildOfficerFlags`（`:655-671`）：
     删 `lawEnforcerIds` 字段、收集点、`lawEnforcing` 判定；`OfficerFlags`（`:625-630`）删该字段。
   - KDoc 优先级链注释（`:61-65`）删"执法"节点。
2. `disciple_tx.h`：`SlotFlags`（`:1035`）删 `lawEnforcing`；`deriveDiscipleStatus`（`:1065`）删
   `if (f.lawEnforcing) return "LAW_ENFORCING";`；`buildSlotFlags`（`:1105-1108`）删两段判定；
   头注释优先级链（`:1054-1056`）删"执法"。
3. **枚举值保留**（§2.4）：`Disciple.kt:250/263` 两行不动；`StatusDerivationCoverageTest.kt:33-38` 的
   `nonDerivedStatuses` 追加 `DiscipleStatus.LAW_ENFORCING` 并补注释（"旧档兼容保留值，无生产写入方"），
   使"非推导状态"集合与实现一致（当前 `LAW_ENFORCING` 在推导表中，删表后必须移入 `nonDerivedStatuses`，
   否则守卫测试 `all derived DiscipleStatus values have a corresponding SlotFlags field` 会红）。

### 4.3 WS-3 长老槽位业务链收口

| 文件 | 动作 |
|---|---|
| `ElderSlotType.kt` | 删枚举值 `LAW_ENFORCEMENT`（`:10`）、`key` 分支（`:22`）、`formatSlotTypeName` 分支（`:37`） |
| `ElderManagementUseCase.kt` | 删 `REALM_LAW_ENFORCEMENT`（`:37`）、集合成员（`:44`）、ID 收集（`:76`/`:89`）、列表映射（`:102`）、任命写段（`:214-217`）、卸任写段（`:293-296`）、`getElderIdBySlotType` 分支（`:358`） |
| `DiscipleAssignmentGate.kt` | 删 `scanElderSlots` 两行（`:123`/`:132`） |
| `DiscipleSlotCleanup.kt` | 删列表清理（`:203`）与 `clearElderTitleIfUnprotected` 调用（`:224`） |
| `DiscipleSlotManager.kt` | 删两行（`:188`/`:195`） |
| `DiscipleStatusService.kt` | 删 `collectElderSlotDiscipleIds` 两行（`:187-188`）、重置清槽两行（`:597`/`:606`） |
| `DiscipleConstants.kt` | 删 `SLOT_TYPE_LAW_ENFORCEMENT`（`:33`） |
| `DiscipleFacadeImpl.kt` | 删 `resetDirectSlotAt` 分支（`:480`） |
| `DiscipleFacadeImpl战斗Ops2.kt` | 删三处 `SLOT_TYPE_LAW_ENFORCEMENT` 分支（`:207`/`:244`/`:279`） |
| `SlotWinner.kt` | 删 `registerElderField`（`:51`）与 `registerDirectList`（`:59`）两行 |
| `GameEngineSelfHealOps.kt` | 删 `rewriteElderWinner` 分支（`:203`）与 `rewriteDirectElderList` 分支（`:231`） |
| `ProductionProcessor.kt` | 删 `collectElderSlotDiscipleIds` 参数两处（`:180`/`:184`） |
| `SectViewModel.kt` | **整段删** `getLawEnforcementElder`/`getLawEnforcementDisciples`（`:81-88`，零调用方）+ 其 import |
| `ProductionViewModelElderOps.kt` | 同上（`:58-65`） |
| `BuildingFeature.kt` | 删 `ElderPositions.LAW_ENFORCEMENT`（`:308-320`） |
| `appointment_tx.h` | 删 `:81` / `:97` 两行分派 |
| `slot_cleanup.h` | 删 `:71` / `:90` 两行 |
| `month_settlement.h` | 删 `:186` 指针数组元素 + `:193` 列表指针元素，并更新 `:180` 注释计数（10 单槽 + 7 列表 → **9 + 6**） |
| `disciple_tx.h` | 删 `:330`（`lawEnforcement` → `lawEnforcementDisciples` 映射）与 `:1172`（`"执法长老"` 职位名） |

### 4.4 WS-4 建筑链删除

1. `ProductionSlot.kt`：删 `BuildingType.LAW_ENFORCEMENT_HALL`（`:264`）与 `REFLECTION_CLIFF`（`:266`）枚举值 +
   `displayName` 两行（`:282`/`:284`）。
   **旧档安全**：`ProductionSlotConverters.toBuildingType`（`:346-347`）对未知 name 兜底 `ALCHEMY`；
   且两建筑**从未有生产槽位**（槽组只有 `ElderPositions` / 空列表），旧档不应存在此类生产槽行。
2. `BuildingFeatureBoot.kt`：删 `law_enforcement_hall`（`:92-96`）与 `reflection_cliff`（`:107-110`）两条特征。
   同步更新 `:85` 注释（"厅堂/职能型建筑"清单）。
3. `Defaults.kt`：删 `reflection_cliff`（`:115-127`）、`law_enforcement_hall`（`:128-140`）两条配置；
   删别名 5+4 行（`:336-354`）；`createManagementStageBuildings()` 只剩 `mission_hall`。
4. `buildings.json`：删两条建筑定义（`:88-117`）与 5 行别名（`:284-286`、`:289-290`）。
   **构建期硬约束**：`build-atlas.mjs:827` 校验 `config/buildings.json ↔ LAYOUT.buildingNames` **一一对应**，
   任一侧漏删即 `generateSpriteAtlasDef` / `generateAstcAtlas` 报错（G04 §二-11 实证）。
5. `BuildingNames.kt`：删 `:25-26`、`:29-30` 四条映射。
6. `ProductionSlotRepository.kt`：删 `:44` 与 `:46` 两行。
7. 拆除残差：
   - Kotlin 回退臂：`BuildingFacadeImpl.kt:701-703` 删监牢特判 + `:725` 尾注释；
     `BuildingFacadeImpl同步Ops.kt:312-322` **整段删** `releaseReflectingDisciples()`。
   - native 转发：`BuildingNativeTx.kt:273` 删 `put("isReflectionCliff", ...)`；
     `:142`/`:349` 注释同步。
   - C++：`building_residual_tx.h` 删 `kReflectingStatusName`/`kReflectionStartYearKey`/`kReflectionEndYearKey`
     （`:65`/`:70-71`）、`ResidualTarget::isReflectionCliff`（`:108`）、`clearResidualTransaction` 释放段（`:169-177`）、
     头注释两处（`:33`/`:21`）。
     **实测更正**：当前 1810 事务的入参投影 `ResidualTarget`（`:102-109`）**没有**关联弟子 id 字段
     （`docs/cpp-migration-handover-m0.md` §540 所述"关联弟子 id"是早期设计描述）；历史上带 `discipleIds` 的是
     G04 已删的 `REFINING` 破除段 ⇒ 本次只需删监牢段，**不涉及参数面收窄**。
     `SlotGroupKind` 与 Kotlin `BuildingNativeTx.kindName()`（`:303-312`）的映射不受影响（两建筑本就不在 C++ 清扫范围内）。
8. `BuildingRemovalCoverageTest.kt:24` 豁免集收窄为 `setOf("mission_hall")`。

### 4.5 WS-5 UI 链删除

| 文件 | 动作 |
|---|---|
| `ReflectionCliffDialog.kt` | **整文件删**（150 行） |
| `GameRoute.kt` | 删 `object ReflectionCliff`（`:22`）与 `simpleDialogTypes` 映射（`:72`） |
| `DialogType.kt` | 删 `data object ReflectionCliff`（`:86`） |
| `NavigationDelegate.kt` | 删 `openReflectionCliffDialog()`（`:61-64`） |
| `OverlayDialogRouter.kt` | `:50` 分支列表去掉 `is DialogType.ReflectionCliff`；`:47` 注释同步 |
| `DialogFunctionalBuildingRoutes.kt` | 删 import（`:14`）、`when` 分支（`:35`）、`renderReflectionCliff`（`:135-150`）、类头注释（`:19-20`） |
| `BuildingSelection.kt` | 删 `"reflection_cliff" ->`（`:54`） |
| `MainGameScreen.kt` | 删 `"reflection_cliff" ->`（`:418`） |
| `BuildingsTab.kt` | 删描述两行（`:62`/`:64`）与 `"reflection_cliff"` 动作分支（`:95`） |
| `SettingsTab.kt` | 删重置确认文案中的"监牢弟子不受影响"（`:333`）——改为"…工作/职务槽位将清空"（REFLECTING 已由归一化清零，文案不再需要该例外） |
| `ResignGateResult.kt` | REFLECTING 分支（`:35-36`）——见下方决策 |

**关于 `ResignGate` 的 REFLECTING 分支**（`:35-36` + `ResignGateTest.kt:36-41/117`）：
归一化规则落地后，**稳态下不存在 REFLECTING 弟子**，该分支实际不可达。两个选项：

- **(A) 保留分支与文案**（推荐）：零风险纵深防御；若历史路径/未归一化场景仍出现 REFLECTING，
  玩家仍有明确出口（"是否释放？"）。需把文案里的"监牢"改写为中性表述（如"该弟子处于思过中，是否解除？"）。
- **(B) 删分支**：需先证"零 REFLECTING 写入方 + 归一化 100% 覆盖"，收益仅一行代码。

**采纳 (A)**，并同步改 `ResignGateTest.kt` 的文案断言（`:41` 中 `contains("释放")` 保持，
`contains("监牢")` 改为新文案锚点）。

### 4.6 WS-6 引导链

**Kotlin**（`GuideTask.kt`）：

- 删任务 13「宗门律法」（`:346-353`）、任务 14「执法亲传」（`:354-361`）、任务 25「监牢惩戒」（`:433-439`）。
  → `ALL_TASKS` 25 → **22**。
- `ElderAppointed.elderSlotOccupant` 删 `lawEnforcementElder` 分支（`:82`）。
- `DirectDiscipleActive` 删 `lawEnforcementDisciples` 分支（`:105`）。
- **空号禁复用登记**：13/14/25 成为空号，与既有空号 24 同口径——**旧档 `guideClaimedRewardIds` 可能残留
  13/14/25 的领取记录**，若未来新任务复用这些 id，会让新步骤"开局即完成"（静默失效）。
  因此 `GuideTaskRegistry` 的 KDoc 与 `GuideTaskTest` 必须显式登记：

  | 空号 | 原任务 | 禁复用理由 |
  |---|---|---|
  | 13 | 宗门律法 | 旧档可能已领取（执法堂建筑建成即完成） |
  | 14 | 执法亲传 | 同上 |
  | 24 | 血炼强化（G04 已删） | 既有登记 |
  | 25 | 监牢惩戒 | 旧档可能已领取（建成监牢即完成） |

- **顺带核对**：任务 13/14 的**第一条件**是 `BuildingCount("执法堂",1)`——G02 已把"条件生产方死亡导致引导卡死"
  记为真实事故（`report-G02.md` §未完成 3：引导 23/25 条件生产方死亡 → 双端单条件化），
  本批是**根因消除**：建筑整体消失后，保留任何"只看建筑落成"的条件都会造成**永久不可完成的引导步骤**。

**C++**（`guide_reward_tx.h`）：

- `registry()`（`:80-130`）删 13/14/25 三条 → 24 → **21**；`:76` 注释计数改为"21 任务中 20 个为默认 2，任务 23 为 1"。
- `elderSlotOccupant`（`:149`）与 `directDiscipleActiveCount`（`:162`）删对应行。
- `guide_reward_tx_test.cpp:216-233`：`reg.size()` 24→21；id 循环改为在册 id 集合；
  `findTask(24/13/14/25) == nullptr` 断言（空号禁复用，C++ 侧锁）；`findTask(26)` 断言**保持**（见 §14 D-1）。

### 4.7 WS-7 图集与静态资源

**布局源（唯一权威）** `android/scripts/build-atlas.mjs`：

| 位置 | 动作 |
|---|---|
| `LAYOUT.buildingNames`（`:121-127`） | 删 `'执法堂'`、`'监牢'` 两项（18→16） |
| `LAYOUT.footprints`（`:142-145`） | 同步删 `[6,3]`、`[4,4]` 两项（**数组按索引对齐 `buildingNames`**，漏删即保真校验失败——G04 §二-补 行 2 实证） |
| `LAYOUT.buildingColsPerRow`（`:128`） | 复核：当前 `[5,5,5,3]`；16 栋需重算行分布（**不得凭手感**，按 `colsPerRow` 语义与槽位坐标公式推导后运行构建期越界/重叠校验） |
| `BUILDING_DRAWABLE`（`:350-369`） | 删两行（`:361`/`:364`） |
| `android/scripts/lib/atlas-offline-rgba-lib.mjs`（`:194`/`:197`） | 删两行 `BUILDING_DRAWABLE` 镜像 |

**注意**：`LAYOUT.buildingNames` 索引是**语义索引**（`semanticIndices()` `:372-396` 按名查，
`structureNameBase = buildingNames.length`）：删项会**平移其后所有建筑的图集 rect 与 nameIdx**。
因此：

- `generateSpriteAtlasDef`（Kotlin 常量）、`generateSpriteCode`（`TextureAtlas.h`/`SpriteRegistryData.kt`）、
  `generateAstcAtlas`（`atlas_astc.ktx` + `atlas-manifest.json`）、`generateOfflineRgbaAtlas`
  （`atlas-rgba-raw.bin`/`atlas-rgba-mips.bin`/`atlas-rgba-manifest.json`）**四件必须全部重生成**；
- `android/app/src/main/cpp/scene/scene_uv_tables.h` 由 `--codegen` 同源重写。

**必须同步的 3 张手工快照期望表**（G04 §二·补 "规程回写"——它们**不被生成器覆盖、不报编译错**，
且其中两处只由 ctest / 全量 JUnit 暴露）：

1. `android/app/src/main/cpp/gamecore/test/scene_equivalence_test.cpp` 的手工 UV/rect 夹具
   （`:92-170` 区间，含 `:157` 执法堂 / `:160` 监牢）→ 删两行 + 重算其后所有 rect 与 `kStructureNameBase`。
2. `android/core/engine/src/test/java/com/xianxia/sect/core/render/SpriteAtlasDefGeneratedTest.kt` 的解析期望表
   （`:212-245`）→ `18→16`（名称 + 占地 + 用例名）。
3. `android/core/engine/src/test/java/com/xianxia/sect/core/config/BuildingSpriteFootprintGuardTest.kt` 的兜底尺寸期望表
   （`:28-47`）→ 删两行；两处数量断言 `expected.size`（`:57`）随之收敛。

**动态取值守卫**（`AtlasManifestSyncTest`/`SpriteCodegenSyncTest`/`AtlasLayoutSyncTest`/`FootprintTableSyncTest`/
`SceneUvTablesMirrorGuardTest`/`AtlasOfflineRgbaSyncTest`）取 `SpriteAtlasDef.BUILDING_NAMES.size`，随生成物自动一致——
它们是"生成物内部一致性"守卫，**不能替代**上面 3 张手工表的同步。

**素材文件**（待 P-2 拍板，默认删）：

- `android/feature/game/src/main/res/drawable-nodpi/building_law_enforcement.webp`
- `android/feature/game/src/main/res/drawable-nodpi/building_reflection_cliff.webp`

**保留**：`android/scripts/sprite-uid-map.json:38/45`（uid 28/35，不复用）、
`android/core/engine/src/test/java/com/xianxia/sect/core/config/BuildingSpriteFootprintGuardTest.kt` 的
静态架构不变；`docs/design/topdown-view-art-spec.md:85/88` 两行改为"已下线"注记或删行（见 §5 文档栏）。

### 4.8 WS-1 续：存档归一化规则（**本方案的核心兼容措施**）

新增一条 `SaveValidator` 规则，照 `BloodPoolBuildingCleanupRule`（order=17）/`RecruitListCleanupRule`（order=20）先例：

**`android/core/data/src/main/java/com/xianxia/sect/data/integrity/rules/LawEnforcementPrisonCleanupRule.kt`**

```
id    = "law_enforcement_prison_cleanup"
order = 24            // 现状 order 占用 0..23，下一个空闲值
```

职责（幂等、零异常、无残留即 `Passed` 避免无谓落盘）：

1. **建筑实例清理**：`gameData.placedBuildings` 中 `buildingId ∈ {law_enforcement_hall, reflection_cliff}` 的实例全部移除，
   并记录被移除实例的 `instanceId`。
2. **关联槽位清理**：`gameData.productionSlots` 中 `buildingInstanceId` 命中上一步的实例者移除
   （照血炼池先例；两建筑理论上无生产槽，此为防御性覆盖）。
3. **执法槽位清空**：`gameData.elderSlots` 的两个字段在新模型已不存在（解码即丢弃）——
   此处**无代码**；但需在规则 KDoc 中写明"由 §4.1 ProtoBuf 未知字段跳过机制完成，非本规则职责"，
   避免后人误以为遗漏。
4. **状态归一化**：`disciples` 中 `status` 为 `REFLECTING` 或 `LAW_ENFORCING` 者 →
   `status = IDLE`，并清除 `statusData` 的 `reflectionStartYear`/`reflectionEndYear`/`positionName`
   三个键（照 `DiscipleFacadeImpl.kt:100-113` 的归一化写段与 `releaseReflectingDisciples` 的键集）。
5. **引导进度**：`guideClaimedRewardIds` 中的 13/14/25 **保留不动**（历史领取记录，删除会让"已领取"标记消失、
   玩家可重复领奖 = 经济漏洞）。仅在 KDoc 中写明。

**注册**：`SaveValidationRuleDefaults.kt:25` 后追加一行并注明 order。
**覆盖路径核验**：`StorageEngine.load()`（`StorageEngine.kt:214`）、`StorageEngineLoadOps.load()`（`:60`/`:110`）、
云端/`.sav` 恢复（`OldSaveFormatDeserializer`）+ `CorruptedResultHandler.validateRestoredData` 均经
`SaveValidator.validate` ⇒ **本地 Room 直读与云档恢复两条路径全覆盖**（这是 G04 用同一机制清理血炼池的实证）。

**C++ 侧对称归一化**：C++ 是 AUTHORITATIVE，Kotlin 侧归一化后的数据经 `importToNative` 全量导入；
但若存在"仅 C++ 侧快照"的路径（desktop 对拍/原生快照恢复），需在 `importStateInternal` 的**归一化族**补：
`gameData.elderSlots.lawEnforcement*` 字段已随结构删除（无代码）、`statuses` 列中的 `"REFLECTING"`/`"LAW_ENFORCING"`
→ `"IDLE"` 并清思过键。**实施前必须实测确认**该族位置与既有先例（`report-G04.md` §1 提到"生成/回填入口落
`importStateInternal` 归一化族"）；若确认不存在该族，则仅由 Kotlin 归一化 + `syncAllDiscipleStatuses` 收敛，
并在报告中显式声明覆盖边界。

### 4.9 WS-8 死配置收口

| 位置 | 动作 | 依据 |
|---|---|---|
| `GameConfig.kt:857-883` `object LawEnforcementConfig`（`THEFT_REALM_BASE_AMOUNTS` + 13 常量） | **整块删** | `report-G02.md:81` #11 已登记"生产消费方已随执法域归零 → 保留待后续收口"；本批即收口 |
| `GameConfig.kt:140` `Elder.REALM_LAW_ENFORCEMENT` | 删 | 仅 `GameConfigConsistencyTest:120-122` 消费（该用例同步删） |
| `GameConfigData.kt:55` `realmLawEnforcement` | 删 | 同上；`GameConfigConsistencyTest` 同步 |
| `android/app/src/main/assets/config/game_config.json:15` `realmLawEnforcement` | 删 | `ConfigLoader` 走 `ignoreUnknownKeys=true`，删键安全（实测 `ConfigJsonConsistencyTest` 未断言该键） |
| `GameConfig.kt:818` `REWARD_PUNISH_EFFECT` | 随 **P-1** 决定：选 (A) 则与政策本体一并删；选 (B) 保留并补新消费方 | 全仓零消费者（实测） |
| `SectPolicyToggleGovernanceOps.kt:37-43` + `TianshuHallDialog.kt:738-744` + `CultivationSettlement.kt:210-211` + `GameDataSectModels.kt:57` + `OldSerializableSaveData.kt:969` | 随 **P-1** 决定 | 政策本体链 |

---

## 5. 影响范围清单（文件路径 — 变更类型 — 变更说明）

> 标记：**D**=删除整文件/整段 · **M**=修改收窄 · **A**=新增 · **R**=重生成（生成物）
>
> **路径简写约定**（下表为节省宽度使用，展开规则如下，机械可解析——避免 `rules`/`docs` 的"裸文件名"踩坑）：
>
> | 简写前缀 | 展开为 |
> |---|---|
> | `.../core/model/` `.../core/config/` `.../core/usecase/` `.../core/GameConfig.kt` 等 `:core:domain` 内路径 | `android/core/domain/src/main/java/com/xianxia/sect/` |
> | `.../core/engine/`（含 `.../core/usecase/`、`.../core/repository/`、`.../core/util/`、`.../core/config/` 中位于 engine 模块者） | `android/core/engine/src/main/java/com/xianxia/sect/` |
> | `.../data/` | `android/core/data/src/main/java/com/xianxia/sect/data/` |
> | `feature/game/.../` | `android/feature/game/src/main/java/com/xianxia/sect/` |
> | `core/ui/.../` | `android/core/ui/src/main/java/com/xianxia/sect/` |
> | `android/core/domain/src/test/.../` / `android/core/engine/src/test/.../` | 对应模块 `src/test/java/com/xianxia/sect/` |
> | 无前缀的 C++ 文件名（如 `disciple_tx.h`/`json_codec.cpp`） | `android/app/src/main/cpp/gamecore/include/gamecore/system/`（`src/` 下的 `.cpp` 用 `android/app/src/main/cpp/gamecore/src/`） |
>
> 同一模块内出现同名前缀歧义时（`config/Defaults.kt` 在 engine、`config/GameConfigData.kt` 在 domain），
> 表中已给足区分信息；实施时以**符号名 grep** 为准，不以行号为准。

### 5.1 `:core:domain`

| 文件 | 类型 | 说明 |
|---|---|---|
| `android/core/domain/src/main/java/com/xianxia/sect/core/model/ElderSlotType.kt` | M | 删 `LAW_ENFORCEMENT` 枚举值 + key + 显示名 |
| `.../core/model/GameDataSectModels.kt` | M | 删两字段 + reserved 注释 + 3 处引用 |
| `.../core/model/guide/GuideTask.kt` | M | 删任务 13/14/25 + 两个条件分派分支 + KDoc |
| `.../core/GameConfig.kt` | M | 删 `LawEnforcementConfig` 整块 + `REALM_LAW_ENFORCEMENT`（+ P-1 项） |
| `.../core/config/GameConfigData.kt` | M | 删 `realmLawEnforcement` |
| `.../core/model/production/ProductionSlot.kt` | M | 删两个 `BuildingType` 枚举值 + displayName 两行 |
| `.../core/model/Disciple.kt` | — | **不动**（枚举值保留） |
| `.../core/model/ResignGateResult.kt` | M | REFLECTING 分支文案去"监牢"（§4.5 决策 (A)） |
| `android/core/domain/src/test/.../ElderSlotsPositionNameTest.kt` | M | 删 `lawEnforcementElder` 用例（`:54-56`）与 `lawEnforcementDisciples` 断言（`:118-123`） |
| `android/core/domain/src/test/.../GameConfigConsistencyTest.kt` | M | 删 `执法长老境界两源一致` 用例（`:120-122`） |
| `android/core/domain/src/test/.../GuideTaskTest.kt` | M | 25→22 + ID 集合 + 3 条空号断言 |
| `android/core/domain/src/test/.../ResignGateTest.kt` | M | 文案锚点更新（`:36-41`），`LAW_ENFORCING` 期望（`:82`）复核 |
| `android/core/domain/src/test/.../GameDataTest.kt` | M | 删 `lawEnforcement*` 默认值断言（`:340-341`） |
| `android/core/domain/src/test/.../ItemsTest.kt` | M | 删两行断言（`:641-642`） |

### 5.2 `:core:engine`

| 文件 | 类型 | 说明 |
|---|---|---|
| `.../core/usecase/ElderManagementUseCase.kt` | M | 7 处收窄（§4.3） |
| `.../core/engine/domain/disciple/DiscipleAssignmentGate.kt` | M | 删 2 行 |
| `.../core/engine/domain/disciple/DiscipleSlotCleanup.kt` | M | 删 2 行 |
| `.../core/engine/domain/disciple/DiscipleSlotManager.kt` | M | 删 2 行 |
| `.../core/engine/domain/disciple/DiscipleStatusService.kt` | M | 状态机收窄（§4.2）+ 占用/清槽 4 行 |
| `.../core/engine/domain/disciple/DiscipleConstants.kt` | M | 删常量 |
| `.../core/engine/domain/disciple/DiscipleFacadeImpl.kt` | M | 删 1 分支 |
| `.../core/engine/domain/disciple/DiscipleFacadeImpl战斗Ops2.kt` | M | 删 3 分支 |
| `.../core/engine/SlotWinner.kt` | M | 删 2 行 |
| `.../core/engine/GameEngineSelfHealOps.kt` | M | 删 2 分支 |
| `.../core/engine/service/ProductionProcessor.kt` | M | 删 2 实参 |
| `.../core/engine/domain/building/BuildingFeature.kt` | M | 删 `ElderPositions.LAW_ENFORCEMENT` |
| `.../core/engine/domain/building/BuildingNativeTx.kt` | M | 删 `isReflectionCliff` 参数 + 注释 |
| `.../core/engine/domain/building/BuildingFacadeImpl.kt` | M | 删监牢拆除特判 |
| `.../core/engine/domain/building/BuildingFacadeImpl同步Ops.kt` | M | 整段删 `releaseReflectingDisciples` |
| `.../core/engine/config/Defaults.kt` | M | 删两建筑配置 + 9 行别名 + 计数注释 |
| `.../core/engine/util/BuildingNames.kt` | M | 删 4 行映射 |
| `.../core/engine/repository/ProductionSlotRepository.kt` | M | 删 2 行 |
| `feature/game` 侧两个 ViewModel 扩展 | D | 见 5.4 |
| 测试：`ElderSlotTypeCoverageTest.kt` | M | 删 3 处（`:56`/`:105`/`:143`） |
| 测试：`StatusDerivationCoverageTest.kt` | M | 删映射 + 移入 `nonDerivedStatuses` |
| 测试：`ElderSlotsStatusCoverageTest.kt` | M | 删 2 条 `SLOT_SAMPLES` |
| 测试：`DiscipleStatusServiceTest.kt` | M | 删 `lawEnforcing` 用例（`:189-195`）与 flags 构造（`:344`/`:451`） |
| 测试：`DiscipleSlotCleanupTest.kt` | M | 删 fixture 两行与断言（`:58-62`/`:149`） |
| 测试：`BuildingTypeCoverageTest.kt` | M | 删 2 期望 |
| 测试：`domain/building/BuildingFeatureTestRegistration.kt` | M | 删 2 条测试注册 |
| 测试：`BuildingRemovalCoverageTest.kt` | M | 豁免集收窄 |
| 测试：`BuildingRemovalSlotCleanupTest.kt` | M | 删执法堂 fixture（`:81-82`） |
| 测试：`BuildingBatchRemovalTest.kt` | M | 删监牢释放用例（`:292-302`） |
| 测试：`SpriteAtlasDefGeneratedTest.kt` | M | 期望表 18→16（§4.7） |
| 测试：`BuildingSpriteFootprintGuardTest.kt` | M | 期望表删 2 行 |
| 测试：`android/app/src/test/java/com/xianxia/sect/core/util/BuildingNamesTest.kt`（`:core:engine` 的 `BuildingNames` 由 app 模块守护） | M | 删 `lawEnforcementHall`（`:64-65`）与 `reflectionCliff`（`:74-75`）两个用例 |

### 5.3 `:core:data`

| 文件 | 类型 | 说明 |
|---|---|---|
| `.../data/integrity/rules/LawEnforcementPrisonCleanupRule.kt` | A | 新增归一化规则（order=24） |
| `.../data/integrity/rules/SaveValidationRuleDefaults.kt` | M | 追加注册一行 |
| `.../data/serialization/backwardcompat/OldSerializableSaveData.kt` | M | 旧档字段**保留**；映射引用清理 |
| 测试：新增 `LawEnforcementPrisonCleanupRuleTest.kt` | A | 4 类场景（§7.1） |
| 测试：`ProtoNumberUniquenessTest.kt` | M | 追加 `ElderSlots` 退役号 9/10 的 reserved 禁复用用例 |
| 「存档」测试：新增 `SaveDataElderSlotsUnknownTagSkipTest.kt` | A | §4.1 的兼容性硬证据（旧载荷含 tag 9/10 → 其余字段逐值相等） |

### 5.4 `:feature:game` / `:core:ui`

| 文件 | 类型 | 说明 |
|---|---|---|
| `feature/game/.../ui/game/dialogs/ReflectionCliffDialog.kt` | D | 整文件 |
| `feature/game/.../ui/game/SectViewModel.kt` | D(段) | 删 2 个死 API + import |
| `feature/game/.../ui/game/ProductionViewModelElderOps.kt` | D(段) | 同上 |
| `feature/game/.../ui/game/building/BuildingFeatureBoot.kt` | M | 删 2 特征 + 注释 |
| `feature/game/.../ui/game/tabs/BuildingsTab.kt` | M | 删 2 描述 + 1 分支 |
| `feature/game/.../ui/game/tabs/SettingsTab.kt` | M | 文案去"监牢" |
| `feature/game/.../ui/game/MainGameScreen.kt` | M | 删 1 分支 |
| `feature/game/.../ui/game/BuildingSelection.kt` | M | 删 1 分支 |
| `feature/game/.../ui/game/dialogs/TianshuHallDialog.kt` | M | P-1 决定（政策行） |
| `core/ui/.../navigation/GameRoute.kt` | M | 删路由 + 映射 |
| `core/domain/.../dialog/DialogType.kt` | M | 删 `ReflectionCliff` |
| `feature/game/.../delegate/NavigationDelegate.kt` | M | 删门面方法 |
| `feature/game/.../components/OverlayDialogRouter.kt` | M | 分支收窄 |
| `feature/game/.../components/dialog/DialogFunctionalBuildingRoutes.kt` | M | 删 import/分支/渲染函数 |
| `feature/game/.../delegate/DiscipleDelegateLifecycleOps.kt` | M | REFLECTING 分支保留（决策 (A)）；KDoc 更新 |
| `feature/game/.../SpiritMineViewModel.kt` | M | 同上 |
| 测试：`DialogTypeRenderCoverageTest.kt` | M | 删 1 项 |

### 5.5 C++ `gamecore`

| 文件 | 类型 | 说明 |
|---|---|---|
| `include/gamecore/state/models.h` | M | 删 `ElderSlots` 两字段（`:514-515`） |
| `src/json_codec.cpp` | M | 删 `GC_TO`/`GC_FROM` 两行（`:370`/`:380`） |
| `include/gamecore/system/disciple_tx.h` | M | 状态机收窄 + 删 2 行（`:330`/`:1172`） |
| `include/gamecore/system/appointment_tx.h` | M | 删 2 行（`:81`/`:97`） |
| `include/gamecore/system/slot_cleanup.h` | M | 删 2 行（`:71`/`:90`） |
| `include/gamecore/system/month_settlement.h` | M | 删 2 元素 + 注释计数 |
| `include/gamecore/system/building_residual_tx.h` | M | 删监牢特例（§4.4.7） |
| `include/gamecore/system/guide_reward_tx.h` | M | 删 3 任务 + 2 分派行 + 注释计数 |
| `include/gamecore/system/sect_defense_battle.h` | M | `:77` 的 `status == "REFLECTING"` 防守排除——**保留**（归一化后不可达，但保留是零成本纵深防御）；若实施方选择删除需同步 `ResolveBeastAttackFightTest`/`AISectAttackManagerTest` |
| `include/gamecore/system/mission_start_tx.h` / `secret_realm_residual_tx.h` | — | **保留**（`REFLECTING` 键剥离语义对存量仍有效） |
| 测试：`scene_equivalence_test.cpp` | M | 手工 rect 夹具（§4.7-1） |
| 测试：`guide_reward_tx_test.cpp` | M | `reg.size()` 与断言收窄 |
| 测试：`building_residual_tx_test.cpp` | M | 删监牢释放断言段（`:155-177` 部分） |
| 测试：`appointment_tx_test.cpp` | M | 删 `LAW_ENFORCEMENT` 三处（`:101`/`:117`/`:128`/`:137`/`:147` 中相关项） |
| 测试：`battle_residual_tx_test.cpp` | M | `:101`/`:124` 的 `lawEnforcementElder` fixture 替换为其他槽位 |
| 测试：`mission_start_tx_test.cpp` / `secret_realm_residual_tx_test.cpp` | — | 保留（REFLECTING 键剥离用例仍有效） |

### 5.6 脚本 / 资产 / 生成物

| 文件 | 类型 | 说明 |
|---|---|---|
| `android/scripts/build-atlas.mjs` | M | `buildingNames`/`footprints`/`BUILDING_DRAWABLE`/`buildingColsPerRow` |
| `android/scripts/lib/atlas-offline-rgba-lib.mjs` | M | 删 2 行 drawable 映射 |
| `android/scripts/sprite-uid-map.json` | — | **不动**（uid 不复用） |
| `android/feature/game/src/main/res/drawable-nodpi/building_law_enforcement.webp` | D | 待 P-2 |
| `android/feature/game/src/main/res/drawable-nodpi/building_reflection_cliff.webp` | D | 待 P-2 |
| `android/app/src/main/assets/atlas/atlas_astc.ktx` | R | `generateAstcAtlas` |
| `android/app/src/main/assets/atlas/atlas-manifest.json` | R | 同上 |
| `android/app/src/main/assets/atlas/atlas-rgba-raw.bin` / `atlas-rgba-mips.bin` / `atlas-rgba-manifest.json` | R | `generateOfflineRgbaAtlas` |
| `android/app/src/main/cpp/scene/scene_uv_tables.h` | R | `generateSpriteCode` |
| `android/core/engine/build/generated/sprite/.../SpriteAtlasDef.kt` | R | 不入库生成物 |
| `android/app/build/generated/sprite/*`（`SpriteRegistryData.kt`/`TextureAtlas.h`） | R | 不入库生成物 |
| `android/app/src/main/assets/config/buildings.json` | M | 删两条 + 别名 5 行 |
| `android/app/src/main/assets/config/game_config.json` | M | 删 `realmLawEnforcement` |

### 5.7 文档 / 更新日志（`docs/AGENTS.md` 同步义务）

| 文件 | 类型 | 说明 |
|---|---|---|
| `CHANGELOG.md` | M | 当前版本段追加：执法堂/监牢建筑下线、执法长老/执法弟子槽位退役、旧档思过弟子自动回归空闲、引导任务 13/14/25 下架 |
| `android/app/src/main/assets/changelog_entries.json` | M | 当前版本 `changes` **末尾追加**玩家文案（通俗、无数值细节）：例如"调整：执法堂与监牢建筑下线，老存档中相关建筑与思过弟子会一并清理，弟子回归空闲" |
| `docs/knowledge-base.md` | M | 删/改执法堂、思过、监牢现状描述（`:297` BloodPool 附近、`:423` 建筑相关段落按实测核） |
| `docs/cpp-engine.md` | M | `:561` 的 S-13 注记由"执法堂域已删"改写为当前状态（执法堂建筑/槽位已整删）；相关行加"已删"注记（**注意**：批次归档表属历史取证快照，`report-G04.md` §八-9 已确立"历史快照不逐条改写"的划界，仅补现况注记） |
| `docs/architecture.md` | M | 建筑清单类描述按实测核（如含执法堂/监牢） |
| `CODE_WIKI.md` | M | 若含执法堂/监牢模块描述则删条目 |
| `docs/ui-read-surface.md` | M | 若含执法堂字段族读面行则标注下线 |
| `docs/design/topdown-view-art-spec.md` | M | `:85`/`:88` 两行（已下线素材） |
| `docs/design/character-gacha-redesign-2026-09-23.md` | — | **不动**（一次性产出，历史决策记录） |
| 本文件 | — | 实施完成后按报告补充实测值（或另建 `report-*.md`，依批次惯例） |

---

## 6. 兼容性分析（Migration / 序列化 / 存档）

### 6.1 Room / Migration

| 判定项 | 结论 |
|---|---|
| 是否需要新 Migration | **否**。`elderSlots`（TEXT，ProtoBuf base64 载荷）、`placedBuildings`（TEXT）、`production_slots`（TEXT 载荷内枚举名）、`discipleTables.statuses`（TEXT）四处的**列结构均不变**，变更只发生在列**载荷**内部 |
| `DATABASE_VERSION` | 保持 `59`（`GameDatabase.kt:95`）；**禁止**擅自 bump（会触发 `RoomMigrationTest` 全链期望集与 `MigrationChainGuardTest` 连带改动，且无实际需要） |
| `schema/*.json` | 零改写（`report-G04.md` §三坑 1：KSP 曾就地改写历史快照，必须 `git checkout --` 还原） |
| 新增表/列 | 无 |

### 6.2 序列化面四路

| 路径 | 旧档行为 | 依据 |
|---|---|---|
| Room `elderSlots`（`EnumConverters:33-34` → `ProtobufConverters.decodeFromBase64`） | 未知 tag 9/10 被**跳过**，其余 15 个槽位字段逐值解码 | `SaveDataDirectSerializationTest.kt:121-151`（行为固化测试）；**必须新增 ElderSlots 专属用例**（§5.3/§7.1） |
| `SaveData` ProtoBuf（云档/`.sav`/`.bak`） | 同上（`ElderSlots` 作为 `GameData` 的内嵌消息，同机制） | 同上 |
| 旧 JSON 档（`OldSaveFormatDeserializer`） | `ignoreUnknownKeys = true`；`SerializableElderSlots` 字段保留 ⇒ 解析无变化 | `OldSaveFormatDeserializer.kt:27/97` |
| C++ 原生 JSON 快照（`json_codec.cpp`） | `readField` 宽松读，键缺失/多余均安全 | `rules/database-migration.md`「C++/Kotlin 字段同步清单」 |

**关键风险与对策**：`ProtobufConverters.decodeFromBase64` 的 catch 分支会返回 `ElderSlots()`（**全量清零**）。
若"未知字段跳过"行为在某次库升级中变化，旧档会**静默丢失全部长老任命**。
对策：① 新增 §5.3 的专属兼容性测试（锁行为）；② 规则层加 `ElderSlots` 关键槽位"清零即报"的完整性校验**不做**
（YAGNI：`SaveValidator` 已有 `SlotRefRule` 覆盖引用一致性，再加一层会引入误报风险）。

### 6.3 存档内容兼容矩阵

| 旧档内容 | 读档后行为 | 玩家可感知 |
|---|---|---|
| 已放置执法堂建筑 | 被 `LawEnforcementPrisonCleanupRule` 移除（**不返还灵石**——与 G04 血炼池同口径：下线建筑不退费，`report-G04.md` §1 未引入退款） | 建筑消失；无补偿邮件 |
| 已放置监牢建筑 | 同上 | 同上 |
| 已任命执法长老/执法弟子 | 字段随解码丢弃；弟子在下一次 `syncAllDiscipleStatuses` 收敛为 IDLE（`deriveDiscipleStatus` 不再有 `lawEnforcing` 规则 ⇒ 无槽位 ⇒ IDLE） | 弟子回归空闲可重新分配 |
| 弟子状态为 `REFLECTING` | 规则直接归一化为 IDLE + 清思过键 | **弟子从"永久卡死"变为可用（本批最大正向收益）** |
| 弟子状态为 `LAW_ENFORCING` | 规则归一化为 IDLE | 轻微文案变化 |
| `guideClaimedRewardIds` 含 13/14/25 | **保留**（防重复领奖） | 无感知 |
| 引导进度指向已删任务 | UI 列表不含该任务，进度不可见 | 无感知（其余任务进度不受影响） |
| 建筑建造引导计数 `buildingBuilt:执法堂`/`buildingBuilt:监牢` | `guideCounters` 中的键**保留**（孤儿键，零消费；照 G13 ⑨ 先例"不补删悬空键"） | 无感知 |
| 已在途的生产槽/弟子槽位 | 两建筑**从无槽位实例**（槽组为 `ElderPositions`/空）；防御性清理覆盖异常档 | 无感知 |

### 6.4 向前/向后兼容边界

- **向前**：新档不再写入任何执法/监牢数据；新档在旧版 App 上打开时，旧版 App 会因缺建筑/槽位而
  **视为"无执法堂"**（旧版 `BuildingFeatureRegistry` 仍认该 key，但新档没有实例）——无崩溃风险。
- **云档双向**：新档 → 旧版 App：字段缺失取默认值（`encodeDefaults=false` + 类默认值），安全；
  旧档 → 新版 App：§6.3 覆盖。
- **回滚**：本批无 feature flag。若上线后发现缺陷，回滚需**再发版**（不可运行时关闭）——
  这是"删除类变更"的固有属性，已登记为 §8 风险 R-5。

---

## 7. 测试方案

### 7.1 新增测试（必须）

| 用例 | 目标 | 关键断言 |
|---|---|---|
| `LawEnforcementPrisonCleanupRuleTest`（4 例） | 归一化规则正确性 | ① 无残留 → `Passed`（零落盘）；② 两建筑残留 → `Repaired` + 实例与关联槽位消失、其他建筑/槽位逐值不动；③ `REFLECTING` + 思过双键 → IDLE + 三键清除；④ `LAW_ENFORCING` → IDLE；⑤ 幂等（二次 `validate` 返回 `Passed`） |
| `SaveDataElderSlotsUnknownTagSkipTest` | §4.1 兼容性硬证据 | 用旧 serializer（或手工拼 wire bytes：tag 9=string / tag 10=message）生成含 9/10 的载荷 → 新模型解码 → **其余 15 槽位字段逐值相等**、无异常 |
| `ProtoNumberUniquenessTest` 追加用例 | 号禁复用 | `ElderSlots` 所在源文件中 `@ProtoNumber(9)`/`(10)` 零命中（reserved 登记成立） |
| `BuildingTypeCoverageTest`（已存在的守卫，收窄即可） | 枚举-注册表一致 | 自动覆盖（删除两端后必须同步，否则红） |
| `ElderSlotTypeCoverageTest`（收窄即可） | 枚举同步点 | 同上 |

### 7.2 修改测试（同步点，见 §5 各表）

核心 3 类：
① **枚举/注册表驱动守卫**（`BuildingTypeCoverageTest`/`ElderSlotTypeCoverageTest`/`StatusDerivationCoverageTest`/
    `ElderSlotsStatusCoverageTest`/`DialogTypeRenderCoverageTest`/`BuildingRemovalCoverageTest`）——自动红→按指引收窄；
② **手工快照期望表**（`SpriteAtlasDefGeneratedTest`/`BuildingSpriteFootprintGuardTest`/`scene_equivalence_test.cpp`）；
③ **行为用例**（`BuildingBatchRemovalTest` 监牢释放、`DiscipleSlotCleanupTest`、`DiscipleStatusServiceTest`、
    `appointment_tx_test.cpp`、`battle_residual_tx_test.cpp`）——删例或换 fixture，**禁止**改断言以掩盖行为回退。

### 7.3 对抗性审查要点

| 要点 | 验证方法 |
|---|---|
| 旧档"Other 槽位不丢" | `SaveDataElderSlotsUnknownTagSkipTest` + 手工构造"有副宗主 + 有执法长老"的旧档真机实测 |
| REFLECTING 不再卡死 | 真机：读入含 REFLECTING 的旧档 → 弟子列表状态为"空闲中"且可分配 |
| 引导不错位 | 真机：老档（已完成任务 13/14/25）读入 → 引导列表不含这三步、其余步骤进度不变、`getTask(13/14/25)==null` |
| 图集不错位 | 对照 G04 §二·补：3 张手工表 + 4 件产物 + `AtlasManifestSyncTest`/`SceneEquivalenceTest` 全绿 |
| 建筑拆除无残留 | 手工：老档同时含执法堂/监牢/其他建筑 → 只清理目标两栋，其余建筑坐标尺寸不变、无越界 |
| 无静默幽灵引用 | `SlotRefRule` + `GhostRefCleanupRule` 复跑；`BuildingRefRule` 绿 |

### 7.4 墙钟成本核算（`rules/design-plan-review.md` 第四节）

- 新用例均为纯函数/纯数据变（无循环、无 IO、无 Robolectric 依赖），单例 < 50ms；
  新增 6 例合计 < 0.3s 墙钟。
- 归一化规则测试走 `SaveValidator.validate`（既有轻量框架），不引入 Room/Robolectric；
  **唯一需要 Robolectric/真实 Room 的场景不新增**（本批零迁移，无新迁移测试需求）。
- 不新增迭代型守卫（确定性偏差单次即可捕获）。

---

## 8. 风险评估与兜底

| # | 风险 | 概率 | 影响 | 对策 |
|---|---|---|---|---|
| **R-1** | 旧档长老槽位**全量清零**（`decodeFromBase64` 异常兜底被触发） | 低 | 🔴 高（丢失全部长老任命） | §7.1 专属兼容性测试锁行为；实施后**手工旧档真机实测**（含副宗主/内门长老的老档，逐槽核对） |
| **R-2** | 图集索引平移导致建筑**画错图/位置错位** | 中 | 🔴 高 | 严格按 §4.7 执行 4 件产物重生成 + 3 张手工表同步 + `buildingColsPerRow` 重算；对照 G04 §二·补 全套验证 |
| **R-3** | 引导**卡死/进度错位** | 中 | 🟠 中 | 双端注册表同步删 + 空号登记 + `GuideTaskTest`/`guide_reward_tx_test` 双向断言 |
| **R-4** | `REFLECTING` 归一化遗漏某条读档路径 → 弟子仍卡死 | 低 | 🟠 中 | 规则挂在 `SaveValidator`（本地 Room + 云档 + 损坏恢复三路全覆盖，§4.8 已核）；真机实测 + §14 盲区自查 D-3 的 C++ 侧边界声明 |
| **R-5** | 上线后无法运行时回退 | — | 🟠 中 | 删除类变更固有属性；靠"删除前完整门禁 + 手工旧档实测"降险；不引入无人消费的 feature flag（YAGNI 红线） |
| **R-6** | `sect_defense_battle.h:77` 保留 REFLECTING 排除导致"永不可参战"若归一化失效 | 极低 | 🟡 低 | 归一化失效时 R-4 已暴露；保留该排除是纵深防御（若删，归一化失效时 REFLECTING 弟子会**参战**，语义更强但改动面更大） |
| **R-7** | 玩家可见的"监控盲区"：清理过程无玩家提示 | 中 | 🟡 低 | 依 G04/G05 先例：清理静默执行 + 走 changelog 说明（不给一次性邮件提示，避免邮件通道再引入依赖） |
| **R-8** | `guideCounters` 孤儿键（`buildingBuilt:执法堂/监牢`）长期滞留 | 确定 | 🟢 极低 | G13 ⑨ 先例"不补删悬空键"；登记 §10 技术债 |

---

## 9. 未来场景推演（≥6 个月档）

| 维度 | 推演 | 本方案结论 |
|---|---|---|
| **规模增长** | 未来再删/再加建筑（如再下架 2 栋）：本批建立的"8 工作面 + 3 张手工表 + 空号登记"流程可**线性复用**；引导任务 id 增长时，空号清单（13/14/24/25）为**追加式**，不需重编号 | ✅ 线性可控 |
| **生命周期**（构建/清缓存/重生成） | 图集产物是"仓库内提交的构建产物 + hash 增量"：本批重生成后 hash 更新，CI/本地 clean 构建行为一致；无时间戳进 hash（无 generatedAt 类字段） | ✅ 一致 |
| **平台扩张**（iOS） | 变更**全部在共享层**（Kotlin `:core:domain`/`:core:engine`/`:core:data` + 零 Android 依赖的 C++ gamecore）；唯一 Android 相关项是 `drawable-nodpi` 素材删除与 `R.drawable` 引用消失——iOS 侧本就不消费该资源 | ✅ 零 iOS 重做；见 §11 iOS 标签 |
| **运营演进** | 无新增可调数值；`LawEnforcementConfig` 删除后**减少**一处配置双源面；「赏善罚恶」政策（P-1）若保留则仍是零效果开销，运营改价也不改变"零效果"事实 ⇒ 建议 (A) 下架 | ⚠️ 需 P-1 拍板 |
| **兼容回退** | 无开关，回退=发版（R-5）。**可判定的分期边界**：本批不拆批（§12） | ⚠️ 已登记 |
| **数据合规** | 删除的是游戏内玩法数据，无个人信息新增/变更；不触碰 SDK/权限/网络 ⇒ **隐私政策无需更新**（`rules/design-plan-review.md` 原则 5 判定为不触发） | ✅ 不适用 |

---

## 10. 技术债与偿还计划

| 债项 | 产生原因（为何现在不全做） | 偿还时机（明确触发条件） |
|---|---|---|
| `DiscipleStatusService`/`disciple_tx.h` 保留 `REFLECTING` 受保护分支与思过键清理段 | 归一化后已不可达；删除需先证"零写入方 + 三路读档 100% 覆盖"，收益一行代码、风险存档 | 下一次弟子状态机重构批，或"REFLECTING 在连续 N 个版本的真机日志中零命中"（≥2 个版本） |
| `DiscipleStatus.LAW_ENFORCING` / `REFLECTING` 枚举值 + displayName 保留 | 旧档 String 持久化兼容（删值风险 > 收益） | 连续 ≥3 个版本无旧档命中后，可评估"读档迁移 + 删值"（须先做保留值→IDLE 的**持久化**迁移，而非仅读时归一化） |
| `sect_defense_battle.h:77` 的 REFLECTING 防守排除；`mission_start_tx.h`/`secret_realm_residual_tx.h` 的思过键剥离 | 存量归一化的纵深防御 | 随上一条（枚举值退役）同批 |
| `guideCounters` 孤儿键 `buildingBuilt:执法堂`/`buildingBuilt:监牢` | 补删需遍历存档 map + 无收益（零消费） | 下次存档精简批，或出现"引导计数键枚举守卫"时顺带 |
| `sprite-uid-map.json` 两条 uid 保留 | uid 不复用规则（G04 先例） | 永不需要（永久保留即正确） |
| C++ 引导注册表缺 id=26（Kotlin 有、C++ 无，`guide_reward_tx_test.cpp:232` 反向锁定） | 属 G12 遗留（G12 声明"C++ 零改动"），与删除任务无关 | 引导双端对拍批；本批**登记不改**（见 §14 D-1） |
| C++ 任务 23 奖励数量 = 1 与 Kotlin = 2 分歧（`guide_reward_tx.h:76`/`:126` vs `GuideTask.kt:243` 默认 2 + `GuideTaskTest:97-98`） | G02 把 23/25 改单条件时 C++ 侧数量未同步 | 本批删 25 后可顺手对齐（见 §14 D-2，建议同批修） |

> 本批**不新增**结构性技术债；上表均为"存量债登记 + 触发条件"。

### 10.1 YAGNI 反向检查（`rules/design-plan-review.md` 第三节）

本方案只新增**一个**抽象——`LawEnforcementPrisonCleanupRule`（`SaveValidator` 规则）：

| 检查项 | 结论 |
|---|---|
| 是否有当前生产消费者？ | **有**：`StorageEngine.load()`（`StorageEngine.kt:214`）、`StorageEngineLoadOps.load()`（`:60`/`:110`）、`CorruptedResultHandler.validateRestoredData()` 三条真实读档路径**每次读档都会执行**（非测试消费者） |
| 能否用既有模块扩展而不新增类型？ | **不能**：既有 `BloodPoolBuildingCleanupRule` 语义单一（只清血炼池 + 其生产槽），`SlotRefRule` 只做引用一致性校验（不写状态列）；把本次四类清理塞进任一既有规则会破坏"单一职责 + 单一 id/order"的注册表契约 |
| 是否引入了无消费者的新接口/开关？ | **无**。本批**不新增** feature flag、不新增 ActionId、不新增 JNI 导出、不新增遥测事件 |
| 其余"删除"动作是否 YAGNI-clean？ | 是（删除不产生新抽象）；唯一需警惕的是**顺手新增"执法域残留清理"的第三、第四条规则**——本方案明确**只加一条**，四类清理合并在这条规则内，禁止拆成多条（避免规则链膨胀与 order 争用） |

---

## 11. 全局影响交叉核对（`rules/` 逐项）

| `rules/` 文件 | 触碰 | 核对结论 |
|---|---|---|
| `rules/database-migration.md` | ✅ 强烈相关 | §6.1：**零 Room 迁移**（列结构不变）；§4.1 ProtoBuf 号 reserved；§4.8 规则注册；无 `DROP COLUMN` |
| `rules/static-resources.md` | ✅ 强烈相关 | §4.7：图集是 **codegen 管线**（非"双模块放置"常规流程）；LAYOUT 单源、四产物重生成、uid 不复用、webp 删除 |
| `rules/code-comment.md` | ✅ | 删注释/KDoc 与改写计数注释时只描述当前状态，禁"之前/原来/已删除"叙述（历史事实留在 CHANGELOG） |
| `rules/pr-review-checklist.md` | ✅ | §13.3 逐条过：守卫测试同步、detekt 只缩不增、双 changelog、无新增 `!!`/裸异常、构造参数上限（删字段不会增加） |
| `rules/version-release.md` | ✅ | §5.7 双 changelog；**版本号不动**（P-3） |
| `rules/economy-design.md` | ✅（**经济**标签） | 见下 |
| `rules/cpp-priority.md` | ✅ | 变更以**删除**为主，无新增 Kotlin 逻辑 ⇒ 不违反 C++ 优先；唯一新增逻辑（归一化规则）落在 `:core:data` 存档层（非引擎逻辑），符合分层 |
| `rules/testing.md` | ✅ | §7：新用例纯函数、双端齐备（Kotlin 单测 + C++ GTest 收窄）、mock 约定不变 |
| `rules/build-quality.md` | ✅ | §13.3 门禁 |
| `rules/design-plan-review.md` | ✅ | 本文档结构 + 第九~十四章 |
| `rules/expansion-playbook.md` | ⬜ 不适用 | 无新增玩法系统 |
| `rules/commercialization.md` / `rules/ad-cooldown.md` / `rules/social-system.md` / `rules/sdk-init-lifecycle.md` / `rules/data-analytics.md` | ⬜ 不适用 | 无付费点位/广告/社交/埋点/SDK 变更 |
| `rules/dialog-scrim-standard.md` / `rules/new-dialog-checklist.md` / `rules/dialog-soft-input-guard.md` / `rules/chat-dialog-design.md` | ⬜ 不适用（**删除对话框**，不新增） | 需确保删 `DialogType.ReflectionCliff` 后 `OverlayDialogRouter` 的 `when` 仍穷尽编译通过 |
| `android/docs/renderer-feature-checklist.md` | ⬜ | 无渲染特性变更（仅图集布局数值变化，非特性）——但需在报告中标明"图集重生成已过 Vulkan + Canvas 双路径一致性守卫（`AtlasManifestSyncTest`/`AtlasOfflineRgbaSyncTest`）" |

**「经济」标签 —— 源与汇分析**：

| 项 | 类型 | 影响 |
|---|---|---|
| 执法堂建造 6000 灵石 | **汇减少** | 已建玩家存量不退款；新档不再有此支出 |
| 监牢建造 20000 灵石 | **汇减少** | 同上 |
| 监牢"思过"原为 5 年道德/忠诚加成机制（历史）| 已无效 | G02 已删除思过释放加成（`processReflectionRelease` 删） |
| 「赏善罚恶」月耗 3000 灵石 | **汇潜在减少** | P-1 选 (A) 时该支出消失 |
| 执法堂/监牢**不产出**任何货币/资源 | 无源 | 删除**不产生**新的灵石源 |
| 引导任务 13/14/25 奖励（凡品储物袋 ×2）| **汇减少**（多发 3 份的通道消失） | 老档已领取者不受影响；新档少 3 份奖励（属正常范围，非经济风险） |

**「iOS」标签 —— 对等性分析**：

本批**不新增任何平台能力调用**（时间/存储/网络/加密/通知/支付/广告/分享全未触碰）。
删除的资源（`drawable-nodpi/*.webp`）与路由（Compose 层）在 iOS 侧**本就不存在对应实现**，
唯一需要 iOS 关注的是 `SaveValidator` 归一化规则与 `ElderSlots` 字段退役——二者在 `:core:data`/`:core:domain`
（零 Android 依赖，Kotlin Multiplatform 可复用）⇒ **iOS 立项时直接复用，无需对等重做**。

---

## 12. 决策分级与实施编排

**分级：架构级重构**（判定表命中 3 条：同模式问题 ≥3 处 / 影响跨平台路径 / 触及 6 个月规划中尚未收口的登记项）。
按架构级全流程：完整方案文档（本文件）+ 对抗性审查要点（§7.3） + 盲区自查（§14）。
**最小切入路径备选**（若产品临时只要"建筑下线"）：仅做 WS-4 + WS-5 + WS-7，
保留槽位/状态/引导/配置——**不推荐**：会留下"建筑已删但引导任务仍以该建筑为条件"的**永久卡死引导步骤**
（G02 已记录同类事故），故本方案**不拆批**（`rules/design-plan-review.md` §0 禁止分阶段交付）。

### 12.1 工作面与依赖顺序（单批内并行分片）

```
WS-1 数据模型（ElderSlots 字段 + reserved + 归一化规则）   ──┐
WS-2 状态机（LAW_ENFORCING 推导链）                        ──┼──► WS-3 槽位业务链（依赖 1/2 的符号面）
WS-8 死配置收口                                            ──┘        │
                                                                      ▼
WS-4 建筑链（含 C++ 槽位清理/拆除残差） ◄──────────────────────────────┘
        │
        ├──► WS-5 UI 链
        ├──► WS-6 引导链（双端）
        └──► WS-7 图集与静态资源（**必须在 WS-4 删配置后**：buildings.json ↔ LAYOUT 一一对应校验）
                                    │
                                    └──► 测试面（枚举守卫自动红 → 手工表 → 行为用例）
```

**白名单纪律**：每片 ≤10 文件、片内自包含；白名单外只登记不顺手改（G04 §七 工程事实：主线程收口不可省）。

### 12.2 分片建议（12 片）

| 片 | 内容 | 文件数 |
|---|---|---|
| **K-1** | `ElderSlots` 字段退役 + reserved + `OldSerializableSaveData` 映射清理 + `isDiscipleInAnyPosition`/`resolveElderPositionName` | 3 |
| **K-2** | `ElderSlotType` + `ElderManagementUseCase` + `DiscipleAssignmentGate` | 3 |
| **K-3** | 槽位清理/自愈/赢家族（`DiscipleSlotCleanup`/`SlotManager`/`StatusService` 槽段/`SlotWinner`/`SelfHealOps`/`DiscipleConstants`/`DiscipleFacadeImpl`×2/`ProductionProcessor`） | 9 |
| **K-4** | 状态机（`DiscipleStatusService` 规则段 + `Disciple.kt` 注释） | 2 |
| **K-5** | 建筑模型/配置/名称/repo（`ProductionSlot`/`Defaults`/`BuildingNames`/`ProductionSlotRepository`/`buildings.json`） | 5 |
| **K-6** | `BuildingFeature` + `BuildingFeatureBoot` + 拆除残差（`BuildingNativeTx`/`BuildingFacadeImpl`×2） | 6 |
| **K-7** | UI 链（`DialogType`/`GameRoute`/`NavigationDelegate`/`OverlayDialogRouter`/`DialogFunctionalBuildingRoutes`/`BuildingSelection`/`MainGameScreen`/`BuildingsTab`/`SettingsTab`/`ResignGateResult`/`ReflectionCliffDialog` 删/两个死 API） | 13（可再拆 2 片） |
| **K-8** | 引导双端（`GuideTask.kt` + `guide_reward_tx.h`） | 2 |
| **K-9** | 死配置（`GameConfig`/`GameConfigData`/`game_config.json` + P-1 项） | 4 |
| **T-1** | C++ 侧（`models.h`/`json_codec.cpp`/`disciple_tx.h`/`appointment_tx.h`/`slot_cleanup.h`/`month_settlement.h`/`building_residual_tx.h`） | 7 |
| **T-2** | 图集管线（`build-atlas.mjs` + `atlas-offline-rgba-lib.mjs` + webp 删 + 四产物重生成 + `scene_uv_tables.h`） | 6+生成物 |
| **cT/cK** | 测试面（Kotlin 14 文件 / C++ 5 文件 / 新增 3 文件） | ~22 |

### 12.3 验收门禁（`rules/build-quality.md`）

| 门 | 命令 | 期望 |
|---|---|---|
| Kotlin 编译 | `cd android && ./gradlew.bat compileReleaseKotlin` | BUILD SUCCESSFUL |
| 单元测试 | `cd android && ./gradlew.bat testReleaseUnitTest --max-workers=1` | 全绿（新增 6 例；删除例数在报告中对账） |
| 静态分析 | `cd android && ./gradlew.bat lintRelease detekt` | 全绿；`detekt-baseline.xml` **只减不增** |
| C++ 编译/测试 | 桌面 `cmake --build` + `ctest` | 编译 EXIT=0；ctest 失败项**必须逐条归因**（A 类=本批缺陷必须修；B 类=黄金序列平移需登记，本批预期 **B 类零新增**——删除不消费 RNG） |
| NDK / JNI | `build-desktop-jni.ps1` + `check-jni-count.mjs` | 零新增导出（本批无新事务） |
| 图集管线 | `generateSpriteAtlasDef` / `generateSpriteCode` / `generateAstcAtlas` / `generateOfflineRgbaAtlas` | 四件全绿 + 保真校验通过（16 栋建筑） |
| 生成物守卫 | `AtlasManifestSyncTest` / `SpriteCodegenSyncTest` / `AtlasLayoutSyncTest` / `FootprintTableSyncTest` / `SceneUvTablesMirrorGuardTest` / `AtlasOfflineRgbaSyncTest` / `SceneEquivalenceTest` | 全绿 |
| Room | `RoomMigrationTest` 全链 + `MigrationChainGuardTest` | 全绿且**零新 schema JSON** |
| 规范分发门禁 | `node scripts/check-agent-instructions.mjs` | EXIT=0（改 docs/rules 后必跑） |
| 双 changelog | `changelog_entries.json` JSON 可解析 + `CHANGELOG.md` 追加 | 通过 |

### 12.4 终态 grep 证据表（G5 验收）

生产面 = `android/{core,feature,app}/src/main` + `android/app/src/main/cpp/gamecore/{include,src,jni}` + `android/scripts`：

| 模式 | 期望 | 豁免（须逐条贴证） |
|---|---|---|
| `lawEnforcement\|LAW_ENFORCEMENT\|law_enforcement_hall` | 0 | `OldSerializableSaveData`（历史解析面）、`ProtoNumberUniquenessTest` reserved 断言、历史迁移链 SQL/注释、`sprite-uid-map.json`、changelog/CHANGELOG |
| `reflection_cliff\|REFLECTION_CLIFF\|ReflectionCliff` | 0（生产面） | `OldSerializableSaveData`、`sprite-uid-map.json`、`GameDatabaseMigration*`（历史 SQL）、changelog |
| `监牢` / `执法堂` | 0（玩家可见面与生产代码） | CHANGELOG/changelog_entries（历史条目）、`docs/`（历史快照与本文档）、`GameDatabaseMigration*` 注释 |
| `REFLECTING\|LAW_ENFORCING` | **必须仍有命中**（保留的枚举值 + 受保护分支 + 归一化规则 + 存量清理段） | — 逐条列为"保留面清单" |
| 反向守卫 | `ElderSlots` 其余 15 字段、`mission_hall` 特例、`BuildingsTab` 其余分支、`GuideTask` 22 任务全在 | — |

---

## 13. 实施完成后的报告要求

1. 实测值表：文件数/增删行数、ctest 与 JUnit 计数并逐模块对账、detekt/lint 结果、图集产物哈希。
2. **不采信自证**：第二会话在同一棵树上复跑门禁（G04 §二·补 的教训：原报告 ctest/detekt/JUnit 三项"绿"均不可复现）。
3. 每片报告回填白名单外发现项（G04 §工程事实：跨片断点 16 项由主线程收口）。
4. 手工旧档真机验证项（R-1/R-4/R-6）单独列 pending-device 台账。

---

## 14. 盲区自查与完善建议（末章）

> 立场：本文档作者切换为审查者/对抗者，逐维度自查。**实质影响方案主体的建议已回写正文**（标注回写位置）。

### 需求理解

**D-1｜"删除监牢"是否等于"删除思过玩法"？** 字面需求是删建筑，但监牢的**唯一玩法职能**是监牢→释放（思过）。
如果只删建筑不处理 REFLECTING 存量，等于删掉了玩家唯一的解救入口 = **制造卡死**。
→ **已回写正文 §0.3 + §4.8**（本方案的核心兼容措施）。这是本方案最高价值判断。

**D-2｜"执法弟子/执法长老"是"两个槽位"还是"装备/职位系统的一部分"？** 需确认不是要删整个长老系统。
→ 实测：G02 已把执法职责删除，且 `ElderSlotType` 有 10 个值、本批只删 1 个；
本文档 §3.2 明确"不动长老系统其余 9 槽 + 弟子列表 6 类"。

**D-3｜是否有产品意图"保留建筑作为纪念/装饰"？** 4.01.14 changelog 曾写"相关建筑保留"。
→ **本次需求即为推翻该表述**（用户明确要求"删除执法堂、监牢"）；但**必须双 changelog 显式说明变化**，
否则玩家会觉得"说好保留怎么又拆了"（§5.7 文案要求已回写）。

### 边界与极端

**D-4｜老档同时含执法堂/监牢 + 满槽长老 + REFLECTING 弟子**：归一化规则是**单次幂等事务**，
四类清理互不干扰（建筑/槽位/状态/引导）；已列 §7.1 用例 ②③④ 与 §7.3 真机项。

**D-5｜`buildingColsPerRow` 重算错误** → 图集槽位重叠/越界。构建期 `SpriteAtlasDefGeneratedTest` 的**矩形不重叠校验**
（`:560-600` 附近）会红。→ 已写入 §4.7 与 §12.3 门禁。

**D-6｜半完成状态（清理规则抛异常）**：规则必须零抛异常（`SaveValidationRule` 契约），
异常会被框架转 `Corrupted` **阻断读档**。→ 已写入 §4.8（照 `BloodPoolBuildingCleanupRule` 头注释）。

### 系统耦合

**D-7｜未枚举的耦合点**：已通过全仓 grep 建立 §2 明细，覆盖
结算层级（无新增 tick）、EventBus（无事件）、RNG 分区（**零 RNG 消费，无平移**）、
镜像协议（`models.h`/`json_codec.cpp` 双端同步）、引导计数器（孤儿键登记）、
`SaveValidator` 规则链（order 24 唯一性）、图集 codegen、Room schema、UI 路由穷尽性。
→ 已分别回写 §4/§5/§10/§11。

**D-8｜`guideClaimedRewardIds` 与任务 id 的空号问题**：若删 id 又不登记空号，未来复用会让新步骤"开局即完成"
（G12 已为 id 24 立过规）。→ **已回写 §4.6 空号登记表 + `GuideTaskTest`/`guide_reward_tx_test` 断言**。

### 假设有效性

| 假设 | 验证方式 | 不成立时的备选路径 |
|---|---|---|
| ProtoBuf 解码跳过未知 tag（不抛异常） | `SaveDataDirectSerializationTest` 已固化 + 本批新增 ElderSlots 专属用例 | 若某版本库行为变化：改用"字段保留 + `@Deprecated` + 不写"策略（`GameData.autoSaveIntervalMonths` 的 `@Ignore+@Transient+@ProtoNumber` 模式，`GameData.kt:144-150` 先例），即**退回不删字段**，只删业务引用 |
| `SaveValidator` 覆盖所有读档路径 | §4.8 已核 4 处调用点 | 若发现遗漏路径：在其入口补同一归一化调用（而不是把逻辑复制进规则） |
| 两建筑从无生产槽位实例 | 槽组定义为 `ElderPositions`（slotsPerInstance=0）/ 空；`ProductionSlotRepository` 有历史映射但 `createSlots` 不产槽 | 规则第 2 步为防御性覆盖，已兜住 |
| C++ `importStateInternal` 归一化族存在 | **未实测** | 不存在则只由 Kotlin 归一化 + `syncAllDiscipleStatuses` 收敛，并在报告**显式声明覆盖边界**（不假装覆盖） |

### 数据与兼容

**D-9｜回滚路径**：无运行时开关（R-5）。已登记；不引入 flag（YAGNI）。

**D-10｜`docs/` 历史快照不得伪造**：删除后改写历史取证行会破坏证据链。
→ 已回写 §5.7：只补"现况注记"，不改历史台账（G04 §八-9 划界）。

### 非功能属性

**D-11｜性能**：归一化规则是 O(建筑 + 弟子) 单次遍历，仅在有残留时 `Repaired` 落盘一次；
老档读档增加 < 1ms 量级，无每帧/每旬成本。**内存**：减少两建筑图集槽位与两条模型字段（净减少）。
**功耗**：无影响（无新 tick/线程）。
**安全/隐私**：无网络/权限/SDK 变更 ⇒ 隐私政策无需更新（§9 结论）。

**D-12｜图集体积**：删两栋建筑 → ASTC/RGBA 产物**可能不变或变小**（布局是固定 4096²/2048² 画布，
删项不缩小画布，但 `atlas-rgba-*` 的逐精灵 golden 列表变短）。→ 不视为风险，但报告需给出产物哈希与体积对照。

### 生命周期

**D-13｜运营调整**：P-1（赏善罚恶）若选 (B) 保留，将来产品要给新效果时需再改一次 UI 文案 + 新消费方
⇒ 建议 (A) 一次到位。

**D-14｜iOS 移植**：见 §11（复用共享层，无重做）。

### 流程盲区

**D-15｜测试覆盖不到的部分**：(a) 真机旧档实测（R-1/R-4）；(b) 图集在低端设备 Canvas 软渲染路径的观感
（产物一致性由 `AtlasOfflineRgbaSyncTest` 守住，但**观感**需真机）；(c) 老档"已完成引导 13/14/25"的 UI 表现。
→ 三项列入 pending-device 台账（§13.4）。

**D-16｜监控盲区**：清理过程只有 `SaveValidatorFixes.logRepairStatus` 日志，无埋点。
→ 依 G04/G05 先例不新增埋点（`rules/data-analytics.md` 三处同步成本 > 收益）；若运营需要，用既有
「存档修复」日志聚合（登记为可选）。

### 途中发现（非本批范围，`AGENTS.md` 公约 12 要求报告）

| # | 发现 | 影响 | 建议 |
|---|---|---|---|
| **F-1** | **C++ 引导注册表缺 id=26**（Kotlin 25 任务含 26，C++ 24 任务无 26），且 `guide_reward_tx_test.cpp:232` 反向锁定 `findTask(26) == nullptr` | G12 的"C++ 零改动"结论使**双实现并行契约**在任务 26 上失效：`claimGuideReward(26)` 的 native 臂恒失败 → 每次落 Kotlin 回退臂（功能正常，但 C++ 侧永久未对齐；若未来加 Diff 对拍会红） | 交**引导双端对拍批**处理（本批只做删除，不扩大范围）；若要同批修，需同步 `guide_reward_tx.h` 注册表 + `:232` 断言 + 该任务的 rewardQuantity |
| **F-2** | **任务 23 奖励数量双端分歧**：C++ `guide_reward_tx.h:126` 为 `1`（且 `:76` 注释、`guide_reward_tx_test.cpp:229` 断言 23/25 为 1），Kotlin `GuideTask.kt:243` 默认 `2` 且任务 23 未覆盖（`GuideTaskTest:97-98` 锁"统一 2"） | 同一任务在两臂领奖数量不同 ⇒ 跨语言行为不一致（native 臂成功时 1 份、降级时 2 份） | 本批删任务 25 时**顺手对齐**（改 C++ 23 → 2，即以 Kotlin 玩家可见值为权威），并同步注释与断言；否则登记 |
| **F-3** | `Defaults.kt:119` 监牢 `slotCount = 6` 与注册表空槽组矛盾 | 仅配置镜像数值，无功能影响（`BuildingFeature.slotCount` 才是权威） | 随本批删除自然消失 |
| **F-4** | `GameConfig.PolicyConfig.REWARD_PUNISH_EFFECT`（`:818`）与 `LawEnforcementConfig`（`:857-883`）**零生产消费者**（G02 登记后长期未收口） | 死配置面（配置双源漂移风险） | 本批收口（WS-8） |
| **F-5** | `report-G02.md:72` 把受影响的引导任务写成"17/18"，实测为 **13/14**（17/18 是问道塔/青云塔任务） | 文档失真（可能导致按该登记去改错任务） | 本批在 §1.1 显式更正，并在报告中登记该失真 |
| **F-6** | `docs/design/character-gacha-redesign-2026-09-23.md:474` 写"建筑图闲置"，本批改为"连建筑删除" | 产品决策演进，文档口径不一致 | 该文件为一次性产出（`docs/AGENTS.md`：专题方案一般不回改），本批**不改**该行；在 `CHANGELOG.md` 记录演进 |

### 自查结论

本方案无"遗留未处理"的实质盲区：14 项 D 条目中 **D-1/D-3/D-6/D-8/D-10 已回写正文**（§0.3、§4.6、§4.8、§5.7），
其余为已登记或明确不适用并给出依据。6 项途中发现（F-1~F-6）中 F-2 建议同批顺手对齐、
F-4 纳入本批（WS-8），F-1/F-5/F-6 登记不改。
实施前**唯一硬阻塞**是 §0.4 的三项拍板（P-1/P-2/P-3）。
