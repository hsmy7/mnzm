# C++ 迁移剩余工作·第二轮并行批次总览与协作协议（W2-b ~ W3）

| 项 | 内容 |
|---|---|
| 文档性质 | **协调文档**（非实施批）：剩余工作全集 → 批次映射、依赖图、多批并行的共享文件协议、统一验证与纪律。各实施批见同目录 `batch-11` ~ `batch-21` |
| 依据 | [cpp-migration-handover-m0.md](../cpp-migration-handover-m0.md) §4 遗留待办 + §5 下轮建议 + [ui-read-surface.md](../ui-read-surface.md) §4.1 逐域写者审计 + `core:engine` 实测（2026-09-11） |
| **基线（开工前必读）** | 上一轮十批（batch-01~10）已合流 `main`；**W2-a 已清偿**（§2.41，提交 `c7faad7` + `7bfbaa7`）：库存**出售/上架/材料消耗族**写者已下沉 C++（`system/inventory_tx.h`，ActionId 1520–1525） |
| 基线实测值（勿凭记忆；**2026-09-13 最新实测**） | ActionId **166**（maxId=**1712**，实用段 1520–1531 / 1550–1559 / 1570–1573 / 1590–1594 / 1610–1616 / 1630–1632 / 1650–1657 / 1670–1672 / 1680–1682 / 1690–1693 / 1710–1712）；桌面 C++ **1284/1284**；引擎全量 **3237 用例 / 289 类 / 跳过 0**（**27 处失败为并行线在途 / 预存，逐类归属见 handover §3**）；detekt 六模块 baseline **全 0**（触碰面 0 违规；`:feature:game` 6 处为纹理并行线在途）；`execute_dispatch.cpp` handler **31** 个 |
| 分支 | 每批一分支 `w2/<NN>-<slug>`；完成后按 §9 收口清单合入 `main` |
| **收口状态（2026-09-13）** | **已交付并并入主树**：batch-11（库存收官：商人购买/充公）｜**batch-12（巡逻/住所/矿场/年俸，见下方"未实施"更正）**｜batch-13（探索）｜batch-14（弟子生命周期，含 14b 名字随机源分区化）｜batch-15（弟子任命/驻守/洗炼）｜batch-16（招募残余）｜batch-17（生产 UI 面 + 灵田）｜batch-18（月年边界）｜batch-19（玉符/宗门升级/邮件）｜batch-20a（秘境平台段）｜**batch-20b（攻宗确定性写回，见下方"未实施"更正）**。已交付批次的实施文档除 **batch-12** 与 **batch-20**（二分交付，保留为设计记录）外均已删除——内容落入 [handover §2.42–§2.52 集成收口 + §2.43 / §2.51b](../cpp-migration-handover-m0.md)（权威记录），CHANGELOG 同步。**唯一未实施**：batch-21（反向通道关闭，前置未达成）。**❗ 更正（2026-09-13）**：本行原记"batch-12（巡逻/住所）未实施；batch-20b（攻宗/执法/战利品残余）未实施"——**两批已于 2026-09-13 交付**（`patrol_tx.h` 1550–1559 / `sect_attack_tx.h` 1711–1712，桌面 1244→1284）。**集成期登记**：`:core:engine` 全量 **27 处失败**（`BootSequenceControllerTest` 10 / `ProductionUiNativeTxGateTest` 4 / `JadeNativeTxGateTest` 1 = 原登记 15 处，另 **`PolicyNativeTxGateTest` 12 处为首次实跑暴露的预存 mock 缺陷**——`getSlots()` 返回 `EmptyList` 而非 `StateFlow`）——被测主体均**不在触碰面**，归属其他并行工作流 / 预存，未代改（见 handover §2.52 + §3）。**另注**：本轮 `.git` 对象库两度被破坏（refs/logs/worktrees 被删、pack 缺失、远端不可达），历史提交不可恢复，成果以工作区文件保全 |

> **教训前置（上一轮真实事故，本轮协议已固化）**：① 各批在自己分支交付后**无人合并**，且最全分支的 C++ 树因"顺手带走他批在途 Kotlin 改动"而断裂（缺 2 头文件 + 2 测试文件，干净检出无法编译桌面 GTest 与 NDK）→ **共享文件必须整组提交，批次分支必须在收口时合并验证**；② 合并冲突**不得取 "theirs" 整体覆盖**（旧基线分支会把后续批次的结构重构整体回退），须逐项补差；③ 收口后立即清理分支，非祖先提交先打 `archive/*` tag 再删。

---

## 1. 剩余工作全集 → 批次映射

剩余工作分四类。**只有第一类可以多人并行实施**；后三类见 [non-parallel-work.md](non-parallel-work.md)。

### 第一类：UI 操作面逐域下沉（唯一长期主轴，可并行）

| # | 批次文档 | 域 | 主触碰文件（所有权） | ActionId 段 | 规模 | 可并行 |
|---|---|---|---|---|---|---|
| 11 | [batch-11-inventory-final.md](batch-11-inventory-final.md) 库存域收官（商人购买/开袋/充公/实例回仓） | 库存 | `InventoryFacadeImpl.kt` + `InventoryNativeTx.kt` + `inventory_tx.h` | 1530–1549 | WS-2 | ✅ 组 A |
| 12 | [batch-12-patrol-residence.md](batch-12-patrol-residence.md) 巡逻/住所分配族 | 巡逻 | `GameEngineAtomicAssign.kt` + `GameEnginePatrolOps.kt` + 新 `patrol_tx.h` | 1550–1569（**实用 1550–1559**） | WS-2 | **✅ 已交付 2026-09-13**（handover §2.43） |
| 13 | [batch-13-exploration.md](batch-13-exploration.md) 探索族（侦察/世界关卡/洞府） | 探索 | `GameEngineScoutOps.kt` + `GameEngineWorldBattleOps.kt` + 新 `exploration_tx.h` | 1570–1589 | WS-2 | ✅ 组 A |
| 14 | [batch-14-disciple-lifecycle.md](batch-14-disciple-lifecycle.md) 弟子管理二（收徒/逐出/状态/婚姻） | 弟子 | `DiscipleFacadeImpl*.kt`（3 文件）+ `DiscipleLifecycleManager.kt` + `DiscipleStatusService.kt` | 1590–1609 | WS-2 | ✅ 组 B |
| 15 | [batch-15-disciple-appointment.md](batch-15-disciple-appointment.md) 弟子管理三（任命/驻守/亲传/长老单值槽） | 弟子 | `GameEngineWarehouseOps.kt` + `ElderManagementUseCase.kt` + 新 `appointment_tx.h` | 1610–1629 | WS-2 | ✅ 组 B |
| 16 | [batch-16-recruit-captive.md](batch-16-recruit-captive.md) 招募/派遣/俘虏残余 | 招募 | `RecruitService.kt` + `GameEngineRecruitOps.kt` + `MerchantAndRecruitService.kt` | 1630–1649 | 中批 | ✅ 组 B |
| 17 | [batch-17-production-spiritfield.md](batch-17-production-spiritfield.md) 生产 UI 面 + 灵田种植族 | 生产 | `BuildingFacadeImpl.kt` + `GameEngineProductionOps.kt` + `SpiritFieldOps*.kt` | 1650–1669 | WS-2 | ✅ 组 C |
| 18 | [batch-18-month-year-boundary.md](batch-18-month-year-boundary.md) 月年边界编排族 | 编排 | `CultivationEventProcessor.kt` + `GameEngineCoordination.kt` + `GameEngineGuideOps.kt` + `SectPolicyToggleUseCase.kt` | 1670–1689 | 大（可二分） | ✅ 组 C |
| 19 | [batch-19-jade-redeem-sect.md](batch-19-jade-redeem-sect.md) 玉符/兑换码/宗门升级/邮件附件 | 货币与运营 | `JadeSymbolService.kt` + `RedeemCodeManager.kt` + `GameEngineSectLevelOps.kt` + `MailAttachmentDistributeOps.kt` | 1690–1709 | 中批 | ✅ 组 C |
| 20 | [batch-20-realm-platform-battle.md](batch-20-realm-platform-battle.md) 秘境平台段 + 攻宗/执法残余 | 秘境/战斗 | `GameEngineSecretRealmOps.kt` + `GameEngineBattleOps.kt` + `LawEnforcement*.kt` | 1710–1729（**实用 1710 / 1711–1712**） | WS-2 | **✅ 二分已交付 2026-09-13**（20a §2.51 / 20b §2.51b；20b 经审计改判为两段 + 三条登记不下沉） |
| 21 | [batch-21-reverse-channel-closeout.md](batch-21-reverse-channel-closeout.md) **反向通道关闭批（终局）** | 同步通道 | `GameStateStoreImpl.kt`（捕获面）+ `StateSyncService.kt` | 无新 ActionId | 收敛批 | ⛔ **串行**，依赖 11–20 全部完成（**11–20 已全部交付 → 前置的"批次侧"已达成；仍需 ui-read-surface §4.1 残余域逐域下沉后方可执行**） |

### 第二~四类：不可并行（见 [non-parallel-work.md](non-parallel-work.md)）

| 类别 | 项 | 处置 |
|---|---|---|
| 真机验证 | batch-22 物理设备验证批（原 10 项残留 + W2-a/W2-b 新增 native 臂 6 项） | 需物理设备；与全部代码批正交，**不阻塞** |
| 待拍板 | WS-4 NPC 移动系统 / P1-5 月结配对结构级优化 / 地图跨版本冻结协议 / `TimeSystem.onPhaseTick` 与 `GameSettingsData.autoSave` 删除 | **需用户决策**，未拍板前不派工 |
| 立项 | WS-1 阶段 3 数据导向存储（列级 delta/二进制通道 + dirty_tracker 列级写屏障） | 约 145+ 列写点回归风险，**需单独立项**，非派工项 |

---

## 2. 依赖图与并行分组

```
组 A（库存/巡逻/探索——文件零交集）
  batch-11 库存收官 ──┐
  batch-12 巡逻住所 ──┤
  batch-13 探索 ──────┘
组 B（弟子域两个子批 + 招募——按文件所有权切分，互斥）
  batch-14 弟子生命周期（DiscipleFacadeImpl* / LifecycleManager / StatusService）
  batch-15 弟子任命（GameEngineWarehouseOps / ElderManagementUseCase）
  batch-16 招募俘虏（RecruitService / GameEngineRecruitOps / MerchantAndRecruitService）
组 C（生产/编排/货币）
  batch-17 生产与灵田（BuildingFacadeImpl / GameEngineProductionOps）
  batch-18 月年边界（CultivationEventProcessor / GameEngineCoordination / GuideOps / PolicyToggle）
  batch-19 玉符兑换宗门（JadeSymbolService / RedeemCodeManager / SectLevelOps / MailAttachment）
组 D
  batch-20 秘境平台段与战斗执法（GameEngineSecretRealmOps / GameEngineBattleOps / LawEnforcement*）
        │
        ▼ （组 A/B/C/D 全部合入 main 后）
batch-21 反向通道关闭批（停捕获 + 信封摘段 + 体积归零验收）
```

**组内完全并行，组间也完全并行**——分组只是提示"文件相邻度"，真正的互斥约束见 §5 所有权矩阵。

**唯一硬依赖**：`batch-21` 必须等 `11–20` 全部合入 `main` 后才能开工。
**其余各批之间无数据依赖**，只有共享文件（§3）的合并顺序问题。

---

## 3. 共享文件协议（每批开工前必读）

### 3.1 原子变更集（必须同批提交，禁止拆开）

`scripts/gen-action-ids.mjs` → `android/app/src/main/cpp/gamecore/include/gamecore/action_ids.h`
→ `android/core/engine/src/main/java/com/xianxia/sect/core/nativebridge/ActionIds.kt`
→ `android/app/src/main/cpp/gamecore/src/execute_dispatch.cpp`
→ `android/app/src/main/cpp/gamecore/test/CMakeLists.txt`

**流程**：在 `ACTION_CATALOG` **追加自己段的条目** → 运行 `node scripts/gen-action-ids.mjs`（会同时重写两份生成物）→
手工在 `execute_dispatch.cpp` 新增**独立** `handleXxxTx` 域函数 + 中央 switch **各自一行** `case`/范围分支 → CMakeLists 追加自己的 GTest 文件。
追加式改动 git 可自动合并；**禁止改动其他批的段或 handler**。

### 3.2 ActionId 段预分配（**当前 maxId = 1712；实际使用见"实占"列**）

> 更正（2026-09-13）：原表头写"当前 maxId = 1525"为 W2-a 时点值，已过时。下表"实占"列为实测使用情况（`action_ids.h` / `ActionIds.kt` 同源核对）。

| 批 | 段 | 用途 | 实占 |
|---|---|---|---|
| （预留） | 1526–1529 | 紧急修复 | 未用 |
| 11 | **1530–1549** | 库存收官（商人购买/开袋/充公/实例回仓） | **1530–1531**（购买 + 充公；开袋按路线 B 不下沉） |
| 12 | **1550–1569** | 巡逻/住所 | **1550–1559**（十事务，2026-09-13） |
| 13 | **1570–1589** | 探索 | **1570–1573** |
| 14 | **1590–1609** | 弟子生命周期 | **1590–1594** |
| 15 | **1610–1629** | 弟子任命/驻守/长老 | **1610–1616** |
| 16 | **1630–1649** | 招募/俘虏 | **1630–1632** |
| 17 | **1650–1669** | 生产/灵田 | **1650–1657** |
| 18 | **1670–1689** | 月年边界编排 | **1670–1672 + 1680–1682**（政策开关落 `government.h` 段） |
| 19 | **1690–1709** | 玉符/兑换码/宗门/邮件 | **1690–1693**（兑换码不下沉） |
| 20 | **1710–1729** | 秘境平台段/战斗执法 | **1710**（20a）+ **1711–1712**（20b） |
| 后续 | 1730+ | 开批时再分配 | 空闲 |

### 3.3 其他共享文件

| 文件 | 协议 |
|---|---|
| `include/gamecore/state/models.h` | **原则上不改**。确需新增字段 = 协议漂移（对拍键集红线），PR 必须提前声明并同步 `json_codec`；能由 Kotlin 组装参数传入的一律不落 C++ 模型 |
| `execute_dispatch.cpp` 包含块 | `diplomacy_tx.h` 必须留在包含块**末尾**（其传递引入的 `month_settlement.h` using 声明会改变后续头文件的非限定名解析）。新增事务头插在它**之前** |
| `GameStateStoreImpl.kt` / `StateSyncService.kt`（反向捕获/信封面） | **仅 batch-21 可动**。11–20 各批只加 native 分支，**不得改反向捕获与信封**——提前关闭任何域 = 数据丢失缺陷 |
| `GameViewModel.kt` | **禁改**。下沉接线一律在 Facade / Ops / 协作类层完成（S7/batch-06~09 先例）；确需改时只动 `ui/game/delegate/*` 并在 PR 声明 |
| `GameCoreBridge.cpp/.kt` | **不新增 JNI 导出**：全部走 `nativeExecute` 转发（S6/S7 先例）。确需新导出时 PR 提前声明 |
| `detekt-baseline.xml` + `detekt-baseline-count.guard` | 六模块 baseline **全 0**：本批触碰面**不得新增任何 baseline 条目**（13.2）；新违规必须实修或附理由 `@Suppress`（与既有注解**合并**为单注解多参数——同声明两个 `@Suppress` 只生效其一且重复即编译错） |

---

## 4. handover 章节号预分配

各批完成后在 `docs/cpp-migration-handover-m0.md` §2 追加**自己预分配的**小节，并在 §3 追加验证行、在 §4.1 勾销自己的登记项：

| 批 | 章节 | 批 | 章节 |
|---|---|---|---|
| 11 | §2.42 | 16 | §2.47 |
| 12 | §2.43 | 17 | §2.48 |
| 13 | §2.44 | 18 | §2.49 |
| 14 | §2.45 | 19 | §2.50 |
| 15 | §2.46 | 20 | §2.51（20a）／**§2.51b（20b）** |
| 21 | **§2.53** | — | — |

> ❗ **更正（2026-09-13）**：原表把 batch-21 预分配为 **§2.52**——但 `§2.52` 已被 **2026-09-12 第二轮集成收口**占用（handover 实有章节）。为避免并行批写同一节号冲突，**batch-21 起改用 §2.53**；非并行项顺延：batch-22 → **§2.54**、batch-22a（debug 埋点小批）→ **§2.54a**。（batch-20 的 20a/20b 二分沿用 §2.51 / §2.51b 的字母后缀先例。）

**共享规划记忆文件**（`findings.md` / `progress.md` / `task_plan.md` / `CODE_WIKI.md` / `docs/cpp-engine.md` §9 计数表 / `docs/ui-read-surface.md` §4.1）：
各批**不直接编辑**（并行编辑冲突率高）——把要写入的内容作为"findings 候选 / 文档同步项"写进 PR 描述，由**收口人统一合并**。

---

## 5. 文件所有权矩阵（跨批互斥）

| 文件/目录 | 拥有批 | 其他批规则 |
|---|---|---|
| `domain/inventory/InventoryFacadeImpl.kt` + `InventoryNativeTx.kt` + `system/inventory_tx.h` | **11** | 其余批禁改（含已下沉的出售/上架/材料消耗段） |
| `GameEngineAtomicAssign.kt` / `GameEnginePatrolOps.kt` / `system/patrol_tx.h` | **12** | 其余批禁改；`DiscipleSlotCleanup` 若需扩展清理面 → 由 12 改并在 PR 声明（14 需 rebase） |
| `GameEngineScoutOps.kt` / `GameEngineWorldBattleOps.kt` / `system/exploration_tx.h` | **13** | 其余批禁改 |
| `domain/disciple/DiscipleFacadeImpl*.kt`（3 文件）/ `DiscipleLifecycleManager.kt` / `DiscipleStatusService.kt` | **14** | 15 若需动这些文件 → 找协调人拍板，默认改由 14 代做 |
| `GameEngineWarehouseOps.kt` / `ElderManagementUseCase.kt` / `system/appointment_tx.h` | **15** | 同上 |
| `RecruitService.kt` / `GameEngineRecruitOps.kt` / `MerchantAndRecruitService.kt` | **16** | 同上 |
| `domain/building/BuildingFacadeImpl.kt` / `GameEngineProductionOps.kt` / `*SpiritFieldOps*.kt` | **17** | 其余批禁改（建筑四事务已下沉，勿回退） |
| `CultivationEventProcessor.kt` / `GameEngineCoordination.kt` / `GameEngineGuideOps.kt` / `SectPolicyToggleUseCase.kt` | **18** | `GameEngineCoordination.kt` 为**最大冲突源**（13 处 update + 多批可能想动）——默认归 18，其他批需要时改为调 18 提供的新入口 |
| `JadeSymbolService.kt` / `RedeemCodeManager.kt` / `GameEngineSectLevelOps.kt` / `MailAttachmentDistributeOps.kt` | **19** | 其余批禁改（玉符有 `JadeSymbolConsumptionGuardTest` 守卫，见批次文档） |
| `GameEngineSecretRealmOps.kt` / `GameEngineBattleOps.kt` / `LawEnforcement*.kt` | **20** | 其余批禁改 |
| `GameStateStoreImpl.kt` / `StateSyncService.kt` | **21** | 11–20 一律只读 |

**跨文件撞车时**：后到者 `rebase` 重放；不可自动解的找协调人拍板。**每次保存文件后**以编译 + 定向 diff 复核（多批并行时批前 `git status` 快照不可信）。

---

## 6. 统一验证命令模板（各批按触碰面裁剪，**全部必须真跑并贴输出**）

```bash
# 工作目录：android/（gradle 根）
# ① 桌面 C++ 全量单测（触碰 C++ 的批次；先构建 desktop 套件）
cd android/app/src/main/cpp/gamecore/build/desktop-test && cmake --build . && ctest
# ② 重建桌面 JNI（对拍用；触碰 C++ 必跑）——脚本在【仓库根 scripts/】，工作目录为 android/ 故用 ../
pwsh -File ../scripts/build-desktop-jni.ps1
#    （或任意 cwd 下用绝对路径：pwsh -File C:\Mnzm\XianxiaSectNative\scripts\build-desktop-jni.ps1）
# ③ 引擎全量单测（对拍验收）——JNI 路径必须【绝对完整文件路径】，且必须 --rerun-tasks
./gradlew.bat :core:engine:testReleaseUnitTest --rerun-tasks --max-workers=1 \
  "-Dgamecore.jni.path=C:\Mnzm\XianxiaSectNative\android\core\engine\build\desktop-jni\libgamecorejni.so"
# ④ detekt 六模块（baseline 全 0，禁增）
./gradlew.bat :app:detekt :core:data:detekt :core:domain:detekt :core:engine:detekt :feature:game:detekt :core:ui:detekt
# ⑤ 编译（主源 + 测试源——改名/拆分批必须跑测试源）
./gradlew.bat :core:engine:compileReleaseKotlin :core:engine:compileReleaseUnitTestKotlin \
  :feature:game:compileReleaseKotlin :feature:game:compileReleaseUnitTestKotlin \
  :app:compileReleaseKotlin :app:compileReleaseUnitTestKotlin
# ⑥ 提交门（触碰 C++ 必跑 native）
./gradlew.bat :app:externalNativeBuildRelease :app:lintRelease
# ⑦ 模块回归（按触碰面）
./gradlew.bat :core:data:testReleaseUnitTest --max-workers=1
./gradlew.bat :feature:game:testReleaseUnitTest --max-workers=1
./gradlew.bat :app:testReleaseUnitTest --tests "com.xianxia.sect.core.state.*" --tests "com.xianxia.sect.core.repository.*" --max-workers=1
```

> 注：**工作目录 = `android/`**（gradle 根）。故仓库根脚本须写 **`../scripts/...`**——`scripts/build-desktop-jni.ps1` 在 `android/` 下**不存在**（原 §6 ② 行按根目录相对路径书写，实为失真，2026-09-13 更正）。
> 注：**不要**写 `:app :core:data … compileReleaseKotlin` 这种裸模块名 + 尾任务的形式（Gradle 8.14.5 报 `task 'app' not found`）——逐模块写全任务名。

**已知坑（沿用 handover findings，勿重踩）**：
① `-D` 单横线（`--D` 被 Gradle 判未知选项）；② JNI 路径传目录 → `UnsatisfiedLinkError` **假失败**；③ 系统属性非任务输入，不 `--rerun-tasks` 会判 `UP-TO-DATE` **假绿**；④ 桌面套件以 `build/desktop-test/` 为准（`build/` 是旧套件）；⑤ 运行时需 llvm-mingw `bin` 在 PATH；cmake/ctest 在 `%LOCALAPPDATA%\Android\Sdk\cmake\3.22.1\bin`；⑥ detekt 全量重跑必须**全规则看报告**（行文本变化会让**其他规则**的既有条目失配复活）；⑦ detekt 报告行号随编辑漂移，脚本以新鲜报告为输入且幂等；⑧ Windows 并行会话共享 Gradle daemon 的 `classes.jar` 文件锁 → `gradlew --stop` + 杀残留 `KotlinCompileDaemon` 后重试。

---

## 7. 统一纪律（所有批次必须遵守）

1. **RNG 红线**：任何下沉/重构**不得改变 RNG 抽取集与顺序**。触碰结算/循环形状的改动必须论证抽取序不变；结构级变更须双端同步（需拍板）。
2. **失败零写入**：事务校验链先行，任一校验失败 → **零状态变更** + failure 信封 → Kotlin 回退原路径重执行校验链（用户可见文案由 Kotlin 臂产出）。C++ 侧 message 仅诊断。
3. **双实现并行契约**：Kotlin 原路径**保留为降级回退臂**，不得在下沉当批删除（删除属 batch-21 终局批，且需全部域下沉完成）。
4. **13.2 禁止装回**：触碰面不得新增任何 detekt baseline 条目；新违规实修或附理由 `@Suppress`。
5. **AUTHORITATIVE 门控**：native 臂首行判 `NativeEngineFlag.authoritative`，再判镜像服务可用；测试 mock 下 `stateSyncServiceRef` 会返回 null，**必须先赋给可空局部再判空**（否则调用点内在非空检查直接 NPE——handover findings 13）。
6. **零 JNI 新导出**：走 `GameEngineNativeOps.tryExecuteNative`。
7. **测试必配**：C++ 黄金用例（happy / 校验链全失败臂零写入 / 边界与篡改防御 / **零 RNG 全分区快照差分**）+ Kotlin flag 门控降级用例。无测试视为未完成。
8. **诚实回退**：超出"行为零变更"安全边界时 `git checkout` 还原 + 在 PR 登记，不带病下沉。
9. **提交纪律**：严格按本批触碰文件清单 `git add -- <pathspec>`，**禁止整仓 `git add`**（并行会话曾互相覆盖）。
10. **文档义务**：本批 handover §2.x 小节 + §3 验证行 + §4.1 勾销 + `CHANGELOG.md` + 游戏内 `changelog_entries.json`（玩家向粗粒度文案，不含数值/术语）——**两个更新日志缺一视为未完成**。

---

## 8. 非并行项速览（详见 [non-parallel-work.md](non-parallel-work.md)）

| 项 | 为何不可并行 | 谁来做 |
|---|---|---|
| batch-22 真机（物理设备）验证批 | 需要真机 + 人工操作 + logcat 观察；代码面仅需 debug 埋点小改 | QA / 有设备的人 |
| 待拍板四项（WS-4 NPC / P1-5 配对 / 地图冻结 / 死代码删除） | **决策项**，不是实施项；未拍板前派工 = 白做 | 产品/用户 |
| WS-1 阶段 3 数据导向存储 | 单独立项（145+ 列写点回归面），需设计文档先行 | 架构负责人 |

---

## 9. 各批完成后动作（收口清单）

1. 验证全绿（§6 裁剪面，贴原始输出摘要）。
2. `git add -- <本批触碰文件清单>` → 单次提交（中文提交说明，写明根因/口径差异/验证数值）。
3. handover 追加自己预分配的 §2.x 小节 + §3 验证行 + §4.1 勾销。
4. `CHANGELOG.md` + `android/app/src/main/assets/changelog_entries.json`（同版本条目**追加到 changes 数组末尾**，禁止新建同版本第二条目）。
5. PR 描述附：findings 候选 / 文档同步项（`cpp-engine.md` §9 计数表、`CODE_WIKI.md`、`ui-read-surface.md` §4.1）/ 验证输出摘要。
6. 分支并入 `main` 前必须跑一次**全量门禁**（§6 全部 ①–⑦）；冲突按 §3 协议处理，**不得取 "theirs" 整体覆盖**。
7. 合并后删除分支（非祖先提交先打 `archive/*` tag）。
