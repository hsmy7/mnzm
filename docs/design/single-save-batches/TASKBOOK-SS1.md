# TASKBOOK-SS1 · 去槽位维度（Kotlin + C++ 同批）

> **本文件是 SS1 的派工真源**。上位方案 §0.5.2（W3/W8）+ §2.1 的反转说明。
> 协议：[`DISPATCH-ledger.md`](DISPATCH-ledger.md) §4（**§4.2 重录窗口是本批的头号约束**）+ [`../gacha-batches/EXECUTION-PROTOCOL.md`](../gacha-batches/EXECUTION-PROTOCOL.md)。
> 入口判据：**SS0 已合入**；**与 G 批负责人确认过重录窗口归属**（§4.1/§4.2）；工作树干净。
> 证据等级：改造面来自影响面普查 + 主线程直读；C++ 行号为实测。

---

## 1. 目标与验收判据

| 项 | 内容 |
|---|---|
| 业务目标 | 单存档前提下，把**槽位维度从数据模型与协议中彻底删除**（不再"保留列恒常量"）。删档重置解除了兼容包袱，这是唯一能安全删净的窗口。 |
| 验收① | **Kotlin 侧**：26 张带 slot 的 Room 表去掉 `slot_id` 列（含 `(id, slot_id)` **复合主键 → 单主键**）；**18 个 DAO / 189 个方法**的 slot 参数删除；`RepoInterfaces.kt` 的 **35 处 `slotId: Int = 0`** 归零 |
| 验收② | **C++ 侧**：`GameData.currentSlot`（`models.h:1205`）+ 物品 **7 类** `slotId`（`models.h:68/85/247/262/273/284/296`）+ `inventory.h:541` + 邮件归属槽（`month_settlement.h:1062`、`secret_realm_settlement.h:64`、`secret_realm_residual_tx.h:122,142`）+ 分派回传（`execute_dispatch.cpp:1256`、`dispatch_w4c.cpp:174`、`game_core.cpp:526`）+ `json_codec.cpp` 的 **15 处** `GC_TO/GC_FROM` 全部清除 |
| 验收③ | **两侧同 batch、同 commit**（G 批铁律 6"三端字段链同 commit"）：Kotlin 实体/桥梁与 C++ 模型/编解码必须一次改完，不得分批留下一侧带槽一侧不带槽的中间态 |
| 验收④ | **对拍基线**按 §4.2 的安排处理；最终 `DiffAuthoritativeTickTest` / `DiffInventoryTest` / `DiffSpiritFieldTest` 全绿 |
| 验收⑤ | **伪同形名零误伤**（铁律 15）：`SpiritMineViewModel.slotId`、`DiscipleDetailScreen.slotId(part)`、`SlotAssignment.slotId`（String）、`CaptiveGearUtils.slotIdOf`、`ProductionSlotRepository` 的 String id、`patrol_tx.h` 的 `slotIdx`、`execute_dispatch.cpp:312` 的 `currentSlots`（仓库槽数）**一律不动** |
| 验收⑥ | **`Disciple.slotId` 的 C++ 侧不动**（铁律 16）：`models.h:305-306` 明示其不在快照协议面，只有 Kotlin 侧含 Room 主键语义 |
| 验收⑦ | **`DEFAULT_MAX_SLOTS` 三处字面量随维度消失**（`StorageConstants.kt:20` / `StorageConfig.kt:111` / `SlotLockManager.kt:31`），`SlotLockManager` 的按槽锁退化为单锁或删除 |
| 验收⑧ | **文件层命名去槽**：`SaveFileManager.kt:388-390` 的 `slot_N.sav/.bak/.tmp`、`:337/:356/:366/:372/:377` 的 tombstone 文件改为固定名（无迁移包袱，可安全改名） |
| **不做** | 不做增量落盘（SS5）；不做 UI 终态收敛（SS4，本批只改数据模型与协议）；不碰 `Disciple` 的 C++ 结构；不引入账号维度（SS2）；**不写任何 Room 迁移**（见 D-1） |

---

## 2. 改造面

### 2.1 Kotlin 侧

| 面 | 规模 | 说明 |
|---|---|---|
| Room 实体 | **27 实体中 26 张带 slot 维度**（唯一例外 `ChangeLogEntity`） | 多为 `(id, slot_id)` 复合主键（`game_data` / `disciples` / 八张物品表 / `storage_bags` / production 系列）⇒ 改单主键 |
| DAO | **18 个文件 / 189 个带 slot 参数的方法** | `InventoryDaos` 53、`DiscipleDao` 20、`ProductionDaos` 17、`ManualDaos` 16、`ProductionSlotDao` 16、`MailDao` 12、`BattleLogDao` 10、`EquipmentInstanceDao` 9、`GameHeavyDataDao` 8、`GameDataDao` 6、五个 StateDao 各 3、`SaveSlotMetadataDao` 3、`ArchiveDaos` 2、`MailDraftDao` 2 |
| 仓库接口 | `RepoInterfaces.kt` **35 处** `slotId: Int = 0` + `MailRepository.kt` 同型 | 默认值 `0` = 云会话伪槽且 `isValidSlot(0)` 为真 ⇒ 漏传静默写错槽；本批整体去参 |
| 槽位管理 | `SlotLockManager`、`StorageEngine` 的 `_currentSlot`(:137-138)、`save/load/delete` 校验(:142-148/:270-275/:424-431)、`getSaveSlots`(:503-546)、`setCurrentSlot`(:548-554) | 全部去槽 |
| 写库盖章 | `StorageEngineWriteOps.kt:111-114`（`slotId = slot; id = "game_data_$slot"`）、`:170`、`:177-199`、`:210`、`:213`、`:219-256` | 去 `copy(slotId = …)` 与 `id` 拼槽 |
| 删档清单 | `StorageEngineSaveSupport.kt:273-303` `clearAllSlotTables` 的 27 DAO 平铺清单 | 随去槽重写 |
| 文件层 | `SaveFileManager` 的命名/tombstone | 去槽命名 |
| 入口透传 | `AutoEntry.kt`、`MainActivity` `EXTRA_SLOT`、`GameActivity` `KEY_CURRENT_SLOT`、`SaveLoadViewModel*Ops` 一族 | 去槽透传 |

### 2.2 C++ 侧（**必须与 Kotlin 同批**）

| 面 | 位置 |
|---|---|
| 存档槽字段 | `models.h:1205` `GameData.currentSlot`；物品 7 类 `slotId`（`:68/85/247/262/273/284/296`）；`inventory.h:541` |
| 邮件归属槽 | `month_settlement.h:1062`（`closeDraft.slotId = state.gameData.currentSlot`）、`secret_realm_settlement.h:64`、`secret_realm_residual_tx.h:122,142` |
| 编解码 | `json_codec.cpp:1185/1279`（`currentSlot`）+ 14 处 `GC_TO/GC_FROM(v, j, slotId)`（`:65/83/140/146/174/180/188/193/200/205/212/218/226/231`） |
| 分派回传 | `execute_dispatch.cpp:1256`、`dispatch_w4c.cpp:174`、`game_core.cpp:526` |
| **明确不动** | `models.h:305-306` 的 `Disciple.slotId`（不在协议面）；`execute_dispatch.cpp:312` 的 `currentSlots`（仓库槽数，伪同形名）；`patrol_tx.h` 的 `slotIdx`（生产槽索引） |

---

## 3. 决策

| # | 决策 | 依据 / 代价 |
|---|---|---|
| **D-1** | 🔴 **直接改实体删列，不写任何 Room 迁移** | 删档重置后老库由 destructive fallback **整体重建**，不存在"需要迁移的旧库"⇒ 无需迁移 SQL。`rules/database-migration.md` 的"禁 `ALTER TABLE DROP COLUMN`"约束的是**迁移 SQL**，本批不产生迁移 SQL。**此判断必须写进报告并说明依据** |
| **D-2** | **复合主键改单主键** | `(id, slot_id)` 在单档下退化；保留 `slot_id` 会留下永远恒定的死列 |
| **D-3** | **文件层命名去槽**（`save.sav` / `.bak` / `.tmp` / `.deleted`） | 无迁移包袱；`slot_1.*` 在单档下是误导性命名 |
| **D-4** | **Kotlin 与 C++ 同 batch、同 commit** | 一侧带槽一侧不带槽会导致快照协议错位（`GC_TO/GC_FROM` 键不匹配 ⇒ 静默丢字段） |
| **D-5** | **伪同形名排除清单写进编码规范并加守卫** | 机械替换会打断装备部位/生产槽/指派系统 |
| **D-6** | **`Disciple.slotId` 只改 Kotlin 侧** | `models.h:305-306` 明示其不在协议面，改 C++ 结构无收益且增加对拍面 |
| **D-7** | **对拍重录与 SS9 共用一次窗口**（ledger §4.2 安排 ① 或 ②） | 全局唯一重录窗口（G 批 R3）；本批是两次形状变更中的第一次 |

---

## 4. 文件面与切片（≤10 文件/片）

| 片 | 允许改 | 禁止改 | 自检项 |
|---|---|---|---|
| **A-SS1a** 实体与主键 | `core/domain` 的 26 张实体（去列、改主键） | C++、DAO | 每张表列集与 schema 一致；复合主键已改单主键 |
| **A-SS1b** DAO 去参 | `core/data/.../local/*Dao.kt`（18 个）+ `archive/ArchiveDaos.kt` | 查询语义（除槽过滤外） | 189 个方法 slot 参数归零；SQL 无 `slot_id` |
| **A-SS1c** 仓库与引擎 | `RepoInterfaces.kt`、`MailRepository.kt`、`StorageEngine*.kt`、`SlotLockManager.kt`、`GameStateRepository.kt` | 事务边界 | 35 处默认值归零；按槽锁退化处理 |
| **A-SS1d** 文件层与入口 | `SaveFileManager.kt`、`AutoEntry.kt`、`MainActivity.kt`、`GameActivity.kt`、`SaveLoadViewModel*Ops.kt` | 云仲裁逻辑 | 固定文件名；`EXTRA_SLOT`/`KEY_CURRENT_SLOT` 清除 |
| **T-SS1a** C++ 模型 | `models.h`（`currentSlot` + 物品 7 类 `slotId`）、`inventory.h` | `Disciple.slotId`、`patrol_tx.h`、`currentSlots` | **与 A 侧同 commit** |
| **T-SS1b** C++ 编解码 | `json_codec.cpp`（15 处） | 其他字段编解码 | 导出 JSON 无槽键；双向对称 |
| **T-SS1c** C++ 结算与分派 | `month_settlement.h`、`secret_realm_settlement.h`、`secret_realm_residual_tx.h`、`execute_dispatch.cpp`、`dispatch_w4c.cpp`、`game_core.cpp` | 伪同形名点 | 邮件归属不再需要槽号 |
| **c-SS1a** 测试与对拍 | 各 `Diff*Test`、`RoomMigration*`（SS0 已删）、新增"无槽"守卫 | 生产源 | 按 §4.2 安排重录或登记 |
| **主线程** | 生成物（若有）、双 changelog 草稿、`report-SS1.md`、门禁复跑 | — | 见 §5 |

---

## 5. 门禁清单

```powershell
# 工作目录 = C:\Mnzm\XianxiaSectNative\android
.\gradlew.bat compileReleaseKotlin --console=plain
# C++ 侧（触碰即必跑；先构建再 ctest）
cd app\src\main\cpp\gamecore\build\desktop-test ; cmake --build . ; ctest
# 桌面 JNI 重建（仓库根）
pwsh -File scripts/build-desktop-jni.ps1
# Kotlin 全门（串行）
.\gradlew.bat :core:domain:testReleaseUnitTest :core:data:testReleaseUnitTest :core:engine:testReleaseUnitTest :app:testReleaseUnitTest `
  --max-workers=1 "-Dgamecore.jni.path=C:\Mnzm\XianxiaSectNative\android\core\engine\build\desktop-jni\libgamecorejni.so" `
  --rerun-tasks --console=plain
.\gradlew.bat :core:domain:detekt :core:data:detekt :core:engine:detekt :app:detekt --console=plain
# 生成物零漂移
node scripts/gen-action-ids.mjs ; git diff --exit-code
node scripts/check-jni-count.mjs
```

**提交前**：`git status` 只留本批改动；构建副产物 `git checkout --` 还原。

---

## 6. 登记项

1. **跨批登记（SS9）**：本批删净槽维度后，玉符账本的协议字段**不再需要槽维度**，SS9 的字段设计据此简化；两者共用同一次重录。
2. **跨批登记（SS4）**：本批只改数据模型与协议，UI 层的"无槽位概念"收敛归 SS4。
3. **风险（必须写进报告）**：本批改动面为全案最大（~105 Kotlin 文件 + 7 C++ 文件），且**编译期会一次性出现大量错误**——建议按"实体 → DAO → 仓库 → 引擎 → 入口 → C++"顺序推进，每步保证 `compileReleaseKotlin` 绿再进下一步。
4. **风险**：D-1 的"不写迁移"判断若被审阅者质疑，需以"删档批次已使老库全部失效"为据说明——**该依据必须写进报告**。

---

## 7. 一句话给执行者

**删档重置解除了兼容包袱，所以这次把槽位维度真正删干净：26 张表去 `slot_id` 列、189 个 DAO 方法去参、35 处默认值归零、C++ 的 `currentSlot` 与物品 `slotId` 一并去掉（但 `Disciple.slotId` 的 C++ 侧与 6 处伪同形名不许动）；两侧必须同 batch，且与 SS9 共用同一次对拍重录。**
