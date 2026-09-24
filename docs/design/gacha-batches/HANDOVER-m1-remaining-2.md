# M1 剩余批次交接（第二份）

> **本文件用途**：本次会话终止时的完整交接——① 已收官 3 批的实测基线；② G03 失败运行的状态与**可直接派工的复跑任务书**；③ 剩余批次的落点指针与已知阻塞；④ 三次实操验证过的作业规程。
> **与第一份的分工**：[`HANDOVER-m1-remaining.md`](HANDOVER-m1-remaining.md) 仍是**批次清单 / 顺序理由 / 产品口径 / 串行约束 / 待拍板项**的权威（本文件不重复其 §4 口径表与 §5 待拍板表）；本文件补充**实测基线与执行细节**，并**取代**其 §7 门禁基线（那份是 G07 时代数值，已过时）。
> **更新时点**：G06 收官（`e1a69d8e9`）后、G03 首次尝试失败回退后。

---

## 1. 当前状态一页速览

| 项 | 值 |
|---|---|
| 分支 | `feat/gacha-m0-m1` |
| HEAD | `e1a69d8e9`（G06 收官） |
| 工作树 | **干净**（零跟踪改动）；未跟踪仅 4 组，**永不提交** |
| 未跟踪 4 组 | `模拟宗门美术素材/`、`模拟宗门音乐音效/`、`docs/research/android-game-perf-sota-2026-09.md`、`docs/research/mobile-perf-quality-adaptation-benchmark.md` |
| 已完成 | **3 / 9 批**（G02、G05、G06） |
| 下一批 | **G03（首次尝试失败，需从头复跑——见 §4）** |

### 1.1 已收官批次

| 批次 | 提交 | 报告 | 规模 | 门禁要点 |
|---|---|---|---|---|
| G02 | `5dbaac1e3` | [`report-G02.md`](report-G02.md) | 443 文件 +10775/−26845 | ctest 1532/1536→4 条 B 类；JUnit 7893/0；Room v54→v55 |
| G05 | `8e593a71b` | [`report-G05.md`](report-G05.md) | 143 文件 +5624/−8401 | ctest 1487/1483/4；JUnit 7720/0；JNI 89→86；Room v55→v56 |
| G06 | `e1a69d8e9` | [`report-G06.md`](report-G06.md) | 42 文件 +179/−1309 | ctest 1483/1479/4；JUnit 7709/0；ActionId 1590/1740 退役 |

三批均为：全门禁实测绿 + 双 changelog + `report-Gxx.md` + **单次提交**。

### 1.2 剩余顺序（固定，不可乱序）

**G03 → G04 → G08 → G09 → G11 → G10**

- G03/G04 都动 `DiscipleColumn` 枚举 → 与已合入的 G02 **串行**（每批开工前 `git status` 必须干净）。
- G08 → G09 → G11 是依赖链（G09 解锁依赖 G08 模板读取层；G11 依赖 G09）。
- G10 最后（唯一一次 RNG 重录窗口）。
- M1 完成判据：**G10 全绿 + G11 最简 UI 真机通**。

---

## 2. 门禁基线与运行方式（G06 收官实测）

### 2.1 环境前置

```powershell
# C++ 桌面构建：PATH 前置三段
$env:PATH = "C:\Users\cp050\llvm-mingw\llvm-mingw-20260616-ucrt-x86_64\bin;" +
            "C:\Users\cp050\llvm-mingw\llvm-mingw-20260616-ucrt-x86_64\x86_64-w64-mingw32\bin;" +
            "$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1\bin;" + $env:PATH
# 桌面构建目录：android/app/src/main/cpp/gamecore/build/desktop-test
```

### 2.2 门禁表

| 门 | 命令 | G06 收官实测值 |
|---|---|---|
| 桌面 C++ 编译 | `cmake --build .`（desktop-test 目录） | EXIT=0 |
| 桌面 ctest | `ctest`（同目录） | **1483 总 / 1479 过 / 4 败**；4 败 = 既有 B 类（见 §6.2） |
| Kotlin 编译 | `cd android && ./gradlew.bat compileReleaseKotlin` | EXIT=0 |
| detekt | `./gradlew.bat detekt` | EXIT=0（六模块；baseline 只缩不增） |
| JUnit 全量 | `./gradlew.bat testReleaseUnitTest --max-workers=1 "-Dgamecore.jni.path=<绝对路径>"` | **7709 / 0**（app 1013 / data 812 / domain 1647 / engine 3123 / ui 146 / feature 968） |
| 跨语言对拍库 | `pwsh -File scripts/build-desktop-jni.ps1` → `android/core/engine/build/desktop-jni/libgamecorejni.so` | EXIT=0；**改任何 C++ 后必须重建**，否则 Diff 家族跑的是旧库 |
| JNI 计数 | `node scripts/check-jni-count.mjs` | **86 / 86**（只缩不增；收缩时 `--update` 同批降基线） |
| ActionId | `node scripts/gen-action-ids.mjs` | **198 动作 / maxId=1861**（无 `--check`；零漂移自证 = `git diff --exit-code`） |
| 游戏数据 | `node scripts/gen-game-data.mjs --check` | sha256 `915563485e9fd41d14c77d76b2f25ff711c535e82f384d34a4faca5b10be84d2` |
| 规范门禁 | `node scripts/check-agent-instructions.mjs` | EXIT=0（**改 `docs/`、`rules/`、任何 `AGENTS.md` 后必跑**） |
| Room | `DATABASE_VERSION` = **56**；`schemas/…/56.json` 已入库 | 禁 `DROP COLUMN`；历史 schema JSON 只增不改 |

### 2.3 三条实测教训

1. **KSP 会就地改写历史 schema JSON**：bump 版本号后，若 KSP 把 `55.json` 等历史快照改小（G05 实测 4+/41−），立即 `git checkout -- <该 json>` 还原；只允许新增当前版本 JSON。
2. **构建副产物必须还原**：`sprite-uid-map.json`、`atlas-rgba-manifest.json` 等每次构建都会改时间戳 → 提交前 `git checkout --`。
3. **文本编辑用文件编辑工具，不要用 PowerShell 拼接**：G05 曾用脚本拼接 `changelog_entries.json` 造成重复逗号/缩进错乱（缩进口径：条目行 4 空格、数组闭合 2 空格）。JSON 追加后必须 `node -e "JSON.parse(...)"` 校验。

---

## 3. 单批作业规程（G02 / G05 / G06 三次验证）

### 3.1 五步循环

1. **读侦察**：对应 recon 文件章节 + 本文件 §2 基线 + `EXECUTION-PROTOCOL.md`（+ 触达面读 `rules/database-migration.md`、`rules/cpp-priority.md`、`rules/new-dialog-checklist.md`）。**侦察行号是快照，施工前逐处 grep 复核**（G03/G06 均实测到快照漂移）。
2. **三代理并行派工**：按 §3.2 分片，每份 prompt 必须自包含（子代理看不到会话上下文）。
3. **主线程集成**：按 §3.3 清单收口；同时**独立 grep 终态核验**（不信代理自证）。
4. **双 changelog + report**：`CHANGELOG.md` 新增批次段（含「门禁（实测）」行）；`android/app/src/main/assets/changelog_entries.json` 在当前版本条目 `changes` 数组**末尾追加**玩家视角行（通俗、无术语、无数值细节）；`docs/design/gacha-batches/report-Gxx.md`（九节结构照 report-G05/G06）。
5. **单次提交**：`node scripts/check-agent-instructions.mjs` → 暂存校验（危险项扫描）→ 单条中文 commit → 验证 `非未跟踪残留=0`。

### 3.2 三代理分片原则（文件面不相交，禁跨面）

| 分片 | 允许改 | 禁止改 |
|---|---|---|
| **T · C++** | `android/app/src/main/cpp/gamecore/**`（含 test/CMakeLists）、`scripts/action-catalog/**`、生成物 `action_ids.h` | Kotlin 手写源、Room、docs |
| **A · Kotlin 生产+UI** | 各模块 `src/main`、生成物 `ActionIds.kt`、配置中性源（`GameConfigData.kt`/`GameConfig.kt`/`game_config.json`） | `src/test`、`.cpp/.h`、Room 迁移与迁移测试、docs |
| **c340 · Kotlin 测试** | 各模块 `src/test` | `src/main`、`.cpp/.h`、Room 迁移本体（只改断言）、docs |
| **主线程** | Room 迁移四件套（迁移类 + `DATABASE_VERSION` + 注册 + schema JSON）、迁移测试本体、双 changelog、report、所有提交与门禁 | — |

**验证归属**：T 跑 cmake/ctest/gen-action-ids/check-jni-count/build-desktop-jni；A 跑 `compileReleaseKotlin`；c340 跑各模块 test 编译与可跑的模块测试。**主线程必须复跑全部**。

### 3.3 主线程集成清单

- [ ] 独立 grep 终态（删除模式全 0 + 保留清单命中贴证）
- [ ] 全门禁复跑（§2.2 全表，含重建 JNI 库后跑全量 JUnit）
- [ ] Room：版本递增 + 迁移类（`rebuildTableDroppingColumns` 双表create-copy-drop-rename）+ 注册 + schema JSON 入库 + 迁移测试（含既有迁移链测试的期望集同步）
- [ ] ActionId 退役：保号 + desc【已退役，编号禁复用】+ dispatch case 删 + `dispatch_guard_test` retired 登记 + regen 198/1861
- [ ] `@ProtoNumber` 空缺号 `reserved` 注释登记（两处 proto 定义）
- [ ] 配置源变更：改中性源 → 跑生成器 → 记录新 sha256 → `--check` 复验
- [ ] 双 changelog + report-Gxx
- [ ] `check-agent-instructions` EXIT=0
- [ ] 暂存危险扫描（排除 §1 的四组未跟踪）→ 单次提交

### 3.4 提交纪律

```powershell
git add android scripts CHANGELOG.md docs/design/gacha-batches/report-Gxx.md
# 危险扫描：命中 美术素材|音乐音效|docs/research|worktrees 即中止
$bad = git diff --cached --name-only | Where-Object { $_ -match '美术素材|音乐音效|docs/research|worktrees' }
git commit -m "<type>(gacha): Gxx …"   # 中文；正文按「删除面 / 保留面 / 退役 / 门禁（全实测）/ 测试处置 / 登记」分层
```

### 3.5 常见坑（实测）

1. `git status` 干净是硬前置（删除批共享 `models.h` / `DiscipleTables*` / `Disciple.kt`）。
2. 子代理可能同时触碰同一文件 → 派工时用「文件面」而非「概念面」划界。
3. 子代理收尾报告与主线程追加指令可能**竞态**（G06 出现一次）→ 追加任务要显式重发并核对终态。
4. 门禁要在**终树**上跑（代理并行期间的运行结果不作数）。
5. detekt 首跑几乎必有违规（孤儿 import / 悬空参数 / 超长行）→ 修复而非入 baseline。
6. ctest 总数减少必须与「删除用例数」算术闭合（G06: 1487→1483 恰为删 4 条）。
7. 金黄/baseline 只在 G10 重录；中间批次的红一律登记 B 类（§6）。
8. 游戏内 changelog 是 `assets/` 下的 JSON：改完必须校验可解析。

---

## 4. G03 复跑交接（本次失败批次的完整状态）

### 4.1 失败事实与处置

| 项 | 状态 |
|---|---|
| 事件 | G03 三代理（C++ / Kotlin 生产+UI / Kotlin 测试）**先后异常中断，均无收尾报告**（疑上下文或资源中断） |
| 残留 | 工作树出现 33 项跟踪改动（7 删 / 26 改，+101 / −1436） |
| 处置 | **已 `git checkout -- .` 全量回退**；HEAD 未变（`e1a69d8e9`）；工作树现干净 |
| 未保留补丁的原因 | 半成品全部是「删除面」动作，其删除清单与本文件 §4.3 任务书逐条一致 → 按任务书从头复跑即可，无需恢复半成品 |

**回退前已删除的 7 个文件**（可作为「落点确认存在」的证据）：

```
android/app/src/main/cpp/gamecore/include/gamecore/system/child_birth.h
android/core/domain/src/main/java/com/xianxia/sect/core/state/PendingMarriageProposal.kt
android/core/engine/src/main/java/com/xianxia/sect/core/engine/domain/disciple/DiscipleStatCalculator修炼Ops7.kt
android/core/engine/src/main/java/com/xianxia/sect/core/engine/system/ChildBirthSystem.kt
android/core/engine/src/main/java/com/xianxia/sect/core/engine/system/PartnerSystem.kt
android/feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/DaoCompanionManagementDialog.kt
android/feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/GameNotificationDialog.kt
```

**半成品触达的 26 个修改文件**（说明这些文件确有 G03 落点，复跑时重点复核）：

```
android/app/src/main/cpp/gamecore/include/gamecore/system/ai_sect_recruit.h
android/app/src/main/cpp/gamecore/include/gamecore/system/name_service.h
android/app/src/main/java/com/xianxia/sect/XianxiaApplication.kt
android/app/src/main/java/com/xianxia/sect/core/state/GameStateStoreImpl.kt
android/app/src/main/java/com/xianxia/sect/di/CoreModule.kt
android/core/domain/src/main/java/com/xianxia/sect/core/model/Disciple.kt
android/core/domain/src/main/java/com/xianxia/sect/core/model/DiscipleAggregate.kt
android/core/domain/src/main/java/com/xianxia/sect/core/model/DiscipleComponents.kt
android/core/domain/src/main/java/com/xianxia/sect/core/model/DiscipleDelegates.kt
android/core/domain/src/main/java/com/xianxia/sect/core/model/DiscipleExtended.kt
android/core/domain/src/main/java/com/xianxia/sect/core/model/DiscipleStatsProvider.kt
android/core/domain/src/main/java/com/xianxia/sect/core/state/GameStateStore.kt
android/core/domain/src/main/java/com/xianxia/sect/core/state/MutableGameState.kt
android/core/engine/src/main/java/com/xianxia/sect/core/engine/GameEngine.kt
android/core/engine/src/main/java/com/xianxia/sect/core/engine/GameEngineSettingsOps.kt
android/core/engine/src/main/java/com/xianxia/sect/core/engine/domain/disciple/DiscipleStatCalculator.kt
android/core/engine/src/main/java/com/xianxia/sect/core/engine/domain/disciple/DiscipleStatCalculator修炼Ops6.kt
android/core/engine/src/main/java/com/xianxia/sect/core/engine/domain/disciple/DiscipleStatCalculator属性Ops4.kt
android/core/engine/src/main/java/com/xianxia/sect/core/engine/domain/disciple/DiscipleStatCalculator突破Ops5.kt
android/core/engine/src/main/java/com/xianxia/sect/core/engine/service/CultivationRateCalculator.kt
android/core/engine/src/main/java/com/xianxia/sect/core/engine/service/DiscipleBreakthroughHandler.kt
android/feature/game/src/main/java/com/xianxia/sect/ui/game/GameViewModel.kt
android/feature/game/src/main/java/com/xianxia/sect/ui/game/components/GameOverlayHost.kt
android/feature/game/src/main/java/com/xianxia/sect/ui/game/components/detail/DetailBasicInfoSection.kt
android/feature/game/src/main/java/com/xianxia/sect/ui/game/components/detail/DetailCultivationSection.kt
android/feature/game/src/main/java/com/xianxia/sect/ui/game/delegate/DiscipleDelegateLifecycleOps.kt
```

### 4.2 G03 批次定位

| 项 | 值 |
|---|---|
| 范围 | 删生育 / 道侣 / 亲缘（`parentId` 全链） |
| 深度 | **4（最重批次）** |
| 侦察 | [`recon-G02-G03.md`](recon-G02-G03.md) `## G03`（§G03-0~8 + 附施工顺序建议）；**行号系 G02 前快照，施工前逐处 grep 复核** |
| 关键前提 | G05 已摘 `child_birth.h` 的 `recruitList.push_back` 与 autoRecruit 钩子；G06 已删逐出/改名/哀悼解绑段 |

### 4.3 复跑任务书（三份，可直接派工）

> 派工时按 §3.2 分片发出；每份 prompt 已自包含（含背景契约、删除面、保留面、验证命令、报告要求）。

#### 4.3.1 T · C++ 侧删除面

```text
你在仓库 C:\Mnzm\XianxiaSectNative（分支 feat/gacha-m0-m1，HEAD=e1a69d8e9 已含 G02/G05/G06）执行 G03 批次 C++ 侧删除面。全程中文；只改 gamecore 的 .h/.cpp/test 与 scripts/action-catalog；禁止碰 Kotlin 手写源（ActionIds.kt 等生成物除外）、Room、docs。开工前必读 EXECUTION-PROTOCOL.md 与 recon-G02-G03.md §G03 全节（G03-0~8+施工顺序），侦察行号系 G02 前快照，施工前逐处 grep 复核。前置已就位：G05 摘了 child_birth 的 recruitList.push_back 与 autoRecruit 钩子；G06 删了逐出/改名。

删除面（按 §G03-2，第一步=child_birth.h 整文件删）：
1. child_birth.h 1-231 整文件删；month_settlement.h 摘 include(:16)/processChildBirthStep(211-219)/调用(2345-2348)/边界注释；月结自动配对整段删（kPairingProbability/kPairingMinAge/hasBloodRelation/processPartnerMatching(239-308 含 marriage 事件)/调用 2427-2428）；year_settlement.h 删 isRelatives/applyGriefToRelativesStep/unbindPartnerColumnsStep+调用、哀悼到期段(1205-1223)+调用:1864+哨兵:82、BereavementDraft(138-143)+导出:1791（unbindMasterColumnsStep 保留）；battle_residual_tx.h 删 LifeEventDraft/applyGriefToRelativesBattle/lifeEvents(:107)；phase_settlement.h 删 parentBonusFor+接入两点（masterBonusFor 保留、赠礼接线 :26/:1175-1189 保留）；relative_gift.h 收缩为仅师徒（保 kMasterGiftProb=0.40/kApprenticeGiftProb=0.30，删 partner/parent/child/sibling 概率与查找分类，GiftRelationshipType 收缩）；disciple_lifecycle_tx.h 删 MarriageApproveResult/approveMarriageTransaction/rejectMarriageTransaction+头注释婚姻行（G06 后头注已是「三事务」→改「拜师/年俸开关」两事务+1590/1592/1750 退役说明）；execute_dispatch.cpp 摘婚姻派发(2620-2631 两处)；game_core.cpp 边界列(:267/:271/:288/:289)；亲缘 7 列三端 C++ 环：models.h(:391-397 一带)/disciple_store.h(261-269)/DiscipleColumn 枚举/DiscipleStore/column_dirty.h nameOf+serialize/json_codec TO-FROM/gameview_encode {78}-{84}/game_view.proto :203-209 改 reserved 78,79,80,81,82,83,84;（proto 改动含注释同步）/GameCoreJni.cpp :755 相邻与 :855 op。
2. ⚠️ AI 分区红线（施工顺序4）：ai_sect_recruit.h 去 #include child_birth.h(:43)、generateSpiritRoot(:126) 改接 name_service.h（:45 已 include）——改后必须保证 AI 分区 RNG 灵根生成消费顺序逐位一致（不增不减 nextInt/nextDouble 次数与顺序）；age=16+nextInt(14)(:160)/loyalty(:166)/lifespan=computeLifespan(:188) 若依赖 child_birth/寿命面同批改接或按 G02 后现状处理（grep 实况）。单独在报告贴「改接前后消费序列论证」。
3. ActionId 退役留洞：DISCIPLE_LIFECYCLE_MARRY_APPROVE=1592（core.mjs:209 一带，自查确认归属）与 DISCIPLE_LIFECYCLE_MARRY_REJECT=1750（归属自查）desc 改【已退役，编号禁复用】+ handler 内 case 删（1592 在 handleDiscipleLifecycleTx 区间 1591-1594 内=删 case 留区间；1750 按实况）+ dispatch_guard_test.cpp retired 集 +2；node scripts/gen-action-ids.mjs（目标 198/maxId=1861）。
4. C++ 测试（§G03-5）：child_birth_test.cpp 整文件删（CMake 注册同步——删除后 CMakeLists/glob 复核）；month_settlement_test.cpp PartnerMatching×3+:1136-1140 删；relative_gift_test.cpp 改断言仅师徒（PartnerBeatsParent 等删）；battle_residual_tx_test.cpp 丧亲用例删；year_settlement_test.cpp:751-758 哀悼段删；列双射五件（disciple_store_test/column_export_equivalence_test/json_codec_test/gameview_encode_test/column_dirty_test）必须同步 7 列；ai_sect_ops_test.cpp 灵根/亲缘残留改断言（manual_recruit/recruit_tx 测试 G05 已删，grep 复核实际存在性）。

RNG/门禁口径（本批特别）：SYSTEM 序列（月结生育+配对+赠礼）与既有金黄必然平移——ctest 中除既有 4 条 B 类（ChildBirth.GoldenSequenceSingleBirth——若 child_birth_test 整删则该用例消失=登记「随文件删除」、DiscipleFactory×2、DeterminismProbe）外的新增金黄/平移失败，逐条登记 B 类（文件::用例名+原因）绝不重录，写入报告 G10 表；门禁绿判定 = cmake 编译 EXIT=0 + 失败集合 ⊆ B 类登记集 + 零 A 类（非 RNG 断言/编译错必须修）。Diff 对拍库重建 build-desktop-jni.ps1 EXIT=0。

构建环境：PATH 前置 C:\Users\cp050\llvm-mingw\llvm-mingw-20260616-ucrt-x86_64\bin、...\x86_64-w64-mingw32\bin、$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1\bin；desktop-test 目录 cmake --build + ctest 贴前后实数。

交付中文报告：①逐文件删/收缩/保留清单（含7列三端 C++ 环与 proto reserved diff）②AI 分区改接消费序列逐位一致论证 ③ActionId 退役 diff+regen 校验 ④ctest 前后实数+失败逐条定性（A修/B登记，B 全清单）⑤grep 零残留（§G03-7 清单1-8,11 的 C++ 面；#12 师徒反向守卫命中贴证）。不 commit。
```

#### 4.3.2 A · Kotlin 生产源 + UI 分片

```text
你在仓库 C:\Mnzm\XianxiaSectNative（分支 feat/gacha-m0-m1，HEAD=e1a69d8e9 已含 G02/G05/G06）执行 G03 批次 Kotlin 生产源 + UI 分片。全程中文；只改各模块 src/main 与配置源产物链；禁止碰 src/test、.cpp/.h（含 GameCoreJni.cpp）、Room 迁移/MIGRATION/schema（@Entity 字段可删，迁移归主线程）、docs。开工前必读 recon-G02-G03.md §G03-0/1/4/6/7（行号系 G02 前快照，施工前 grep 复核）。前置：G05 摘了 ChildBirthSystem 的 recruitList 写入与 autoRecruit 尾块；G06 删了逐出/改名/哀悼解绑段相关面。

删除面：
1. 整文件删：ChildBirthSystem.kt（core/engine/system，186 行）、PartnerSystem.kt（157 行）、PendingMarriageProposal.kt（core/domain/state，17 行）、DaoCompanionManagementDialog.kt（feature/game/dialogs，159 行）；SystemManager.kt 删两处注册。
2. 收缩（保师徒）：RelativeGiftHandler.kt（420 行→仅师徒：师 0.40/徒 0.30，删 partner/parent/child/sibling 分支）；GiftRelationshipType.kt（9 行→收缩，枚举/常量同步，删值全仓 grep 连带）。
3. 婚姻链删：GameEngine* 的 approveMarriageProposal/rejectMarriageProposal（grep 实名+定义，含 GameEngineMarriageProposalTest 证明存在的那对）；DiscipleDelegateLifecycleOps.kt:15-23；GameViewModel.kt:74/:556 pendingMarriageProposals；GameNotificationDialog.kt:14/:17 MarriageApprovalDialog；GameOverlayHost.kt MarriageApproval 整段（:22/:42/:49/:51/:106/:142/:217/:246/:258/:264/:286/:294/:345-359 按实测复核）。
4. 哀悼/亲缘 Kotlin 面：DiscipleLifecycleProcessor.kt :14+哀悼/解绑段（unbindMaster 保留！）；DiscipleTables.kt GRIEF_YEAR_NULL_SENTINEL(:249)+列；Disciple.kt hasPartner(:196)；UI DetailActionButtons.kt 亲缘段（:284-307/:373-374，师徒段保留）；DetailBasicInfoSection.kt 亲缘显示；DiscipleChatDialog.kt 结缘入口（grep 复核）；AutoManagementDialog.kt 道侣项；SectManagementDialog.kt:152/:164 按钮；AutoAssignDelegate.kt 两项 daoCompanion 设置；DisciplesTab.kt/DiscipleManagementDialog.kt（待确认项 grep 后处置）。
5. 字段链（G03-1 表 Kotlin 环 + G03-0）：7 social 列（partnerId/partnerSectId/parentId1/parentId2/lastChildYear/childBirthMonth/griefEndYear）+ GameData daoCompanionBannedRootCounts(:615)/daoCompanionConsentRequired(:620)——从 DiscipleComponents/DiscipleSerializer(含 :294-300 归一化 ifEmpty/takeIf 摘除 + @ProtoNumber 11-16/102 字段删 + OldSerializable 侧同号删)/DiscipleTables/DiscipleTablesColumnRegistry/DiscipleTablesAssemblers/DiscipleTablesWrite/AssembleGroup/GameViewMirrorCodec/DiscipleExtended/DiscipleAggregate/DiscipleDelegates/GameData/GameDataSectModels(SectPolicyState 里 daoCompanion 两列注意=独立 @Entity 列!) 全链删除；@ProtoNumber 空缺号登记（11/12/13/14/15/16/102 Disciple + 102/103 GameData 级，G02 同款 reserved 注释）。@Entity 列删除如实列清单（DiscipleComponents social_ 7 列 + SectPolicyState 两 daoCompanion 列 + GameData 对应 Room 列）→ 归主线程 v57 迁移，你只删实体字段+报告表名列名。
6. 配置源：GameConfigData.kt 删 PAIRING/DaoCompanion/MARRIAGE/GRIEF/BIRTH 段（grep 实名）；GameConfig.kt 配对概率/最小年龄/道侣禁灵根常量删；game_config.json daoCompanion*/pairing*/marriage* 段删（改 GameConfig 单源后跑 node scripts/gen-game-data.mjs 重新生成 game-data.json+hash 并贴新 sha256；--check 复验）。
7. preserve 铁律：masterId/师徒/拜师/APPRENTICE 全保留（§G03-7 #12 反向守卫=必须仍有命中）；social_masterId 列绝不动；renameSect/没收/执法等前批保留面不碰。

验证（必跑贴数）：cd android && ./gradlew.bat compileReleaseKotlin EXIT=0；grep 自查贴实数：§G03-7 清单 1-6/9-11 在 src/main 归零（历史迁移/协议白名单逐条列）、#12 师徒反向命中数；gen-game-data --check 新 sha。

交付中文报告：①逐文件删/收缩/保留清单 ②字段链 7+2 列的 @Entity/Room 列对应表（给主线程 v57）③@ProtoNumber 空缺号登记 ④配置源 diff 与新 sha ⑤grep 实数+反向守卫贴证。不 commit。
```

#### 4.3.3 c340 · Kotlin 测试分片

```text
你在仓库 C:\Mnzm\XianxiaSectNative（分支 feat/gacha-m0-m1，HEAD=e1a69d8e9 已含 G02/G05/G06）执行 G03 批次 Kotlin 测试分片。全程中文；只改各模块 src/test；禁止碰 src/main、.cpp/.h、Room 迁移（迁移链测试属你但只改断言不写迁移——v57 迁移本体归主线程，你按契约预留同步位）、docs。开工前必读 recon-G02-G03.md §G03-5 全表（行号快照，施工前复核）。

背景契约（生产分片并行施工）：
- 生育/道侣/亲缘下线：ChildBirthSystem/PartnerSystem/PendingMarriageProposal/DaoCompanionManagementDialog 整删；RelativeGiftHandler/GiftRelationshipType 收缩仅师徒；婚姻 approve/reject 全链删（含 GameOverlayHost MarriageApproval 段、GameViewModel pendingMarriageProposals）；7 social 列（partnerId/partnerSectId/parentId1/parentId2/lastChildYear/childBirthMonth/griefEndYear）+ GameData daoCompanion 两字段全链删；@ProtoNumber Disciple 11-16/102、GameData 102/103 空缺 reserved 注释；Room v57 迁移归主线程（@Entity 列将删，迁移测试的期望集你预留 V57_* 同源占位——策略：涉及 v57 的迁移链测试断言先按「主线程落位后复跑」如实登记时序，不要伪造）。
- 保（反向守卫）：masterId/师徒/拜师/Apprentice 面全部保留；social_masterId；renameSect 等前批保留面。

测试处置（§G03-5 表 Kotlin 部分）：
1. 整类删：ChildBirthSystemTest.kt、PartnerSystemTest.kt、GameEngineMarriageProposalTest.kt。
2. 改断言：RelativeGiftHandlerTest.kt（仅师徒口径：保底概率 0.40/0.30，partner/parent/child/sibling 断言删）；DiscipleModelsTest.kt social 段；DiscipleServiceApprenticeTest.kt:210 保留核对不动（师徒回归门）。
3. Proto/序列化守卫（reserved 后必须同步）：ProtoNumberCoverageTest:257、ProtoNumberUniquenessTest:109、SaveDataDirectSerializationTest:164、SaveDataReconcilerTest:177、SaveDataMailWireRoundtripTest:178。
4. 迁移链：RoomMigrationTest:925、MigrationChainGuardTest:60、RoomMigrationLegacyTest:303、RoomMigrationRecoveryTest:143——期望集扩 ∪ V57_* 形态等主线程 v57 落位后生效；常量名与主线程约定：V57_DISCIPLES_DROPPED_COLUMNS/V57_GAME_DATA_DROPPED_COLUMNS/V57_SECT_POLICY_DROPPED_COLUMNS；若主线程未落位则这些测试编译红=如实登记时序，主线程落位即绿。不要自己写迁移。
5. 夹具/镜像/列守卫同步：ArchivePayloadRoundTripTest、CloudPayloadSizeBenchTest、MirrorProtoFeedFixture+MirrorProtoFeedEquivalenceTest:211、DiffStateTest、DiffColumnExportMergeConvergenceTest:164、DiffMirrorArmConvergenceTest、GameViewDiscipleProjectionTest:146、GameViewDiscipleColumnApplyEquivalenceTest:300、GameDataFieldPatchGuardTest:258、BaselineFieldCoverageGuardTest:93、DiscipleTables*Test（12 文件列级）、SaveMigrationCoordinatorTest:442（feature/game saveload）、StateEntitiesTest/GameDataTest 等字段断言（grep partnerId/parentId/grief/childBirth 连带）。
6. RNG 纪律（本批特别）：月结/旬结序列平移是预期——DiffMonthSettlementTest/DiffYearSettlementTest/DiffAuthoritativeTickTest/DiffProductionSettlementTest 等对拍若因生育/配对/赠礼删除而红：逐条登记 B 类（文件::用例+原因）绝不改锚不重录；C++ child_birth_test 整删的黄金用例=随文件删除登记。你的报告必须产出「G03 新增 B 类登记清单」完整表（供 G10）。
7. grep 补漏：src/test 全树 partnerId|parentId1|parentId2|lastChildYear|childBirthMonth|griefEndYear|ChildBirth|PartnerSystem|PendingMarriage|daoCompanion|MarriageApproval|approveMarriage|relative_gift|RelativeGift 逐命中处置（删/改/保+理由）；#12 反向：masterId|师徒|拜师|APPRENTICE 必须仍有命中贴证。

验证（必跑贴数）：:core:data:testReleaseUnitTest :core:domain:testReleaseUnitTest --max-workers=1 --continue（如编译错=依赖主线程 v57/生产落位，如实列缺符号清单）；:core:engine:compileReleaseUnitTestKotlin :feature:game:compileReleaseUnitTestKotlin :app:compileReleaseUnitTestKotlin --max-workers=1。

交付中文报告：①逐文件/逐段处置表 ②迁移链四测试的 V57 同源占位与时序说明 ③G03 新增 B 类登记清单（G10 用，完整）④反向守卫+grep 实数 ⑤验证实数。不 commit。
```

### 4.4 G03 特有陷阱（复跑必看）

1. **Room 版本 = 57**（侦察写的 v54→v55 是 G02 前快照）：主线程建 `GameDatabaseMigrationsV57.kt`，列名以 `schemas/…/56.json` 权威差集核对；`DATABASE_VERSION` 56→57 + 注册 + `57.json` 入库 + 迁移测试（既有迁移链测试的期望集用「注册删列集 ∩ 起点既有列」交集语义维护）；历史 schema JSON 只增不改。
2. **AI 分区红线**：`ai_sect_recruit.h` 改接 `name_service` 必须保证 RNG 消费顺序**逐位一致**（不增不减调用次数与顺序）——T 报告须单列论证。
3. **门禁口径变化**：SYSTEM 序列必然平移 → 新增金黄失败**登记 B 类、不重录**；绿 = 编译 EXIT=0 + 失败集 ⊆ B 类登记集 + 零 A 类。
4. **列双射五件测试必须同步**（`disciple_store_test` / `column_export_equivalence_test` / `json_codec_test` / `gameview_encode_test` / `column_dirty_test`）。
5. **proto `reserved` 登记**：`SerializableDisciple` → `reserved 11,12,13,14,15,16,102`；`OldSerializableSaveData.SerializableDisciple` → 同；GameData 级 → `reserved 102,103`；`game_view.proto` → `reserved 78,79,80,81,82,83,84`。
6. **反向守卫 #12**：师徒/拜师面（`masterId`/`social_masterId`）必须**仍有命中**——删多了会断拜师链。
7. **配置源变更**：改 `GameConfigData.kt`/`GameConfig.kt`/`game_config.json` 后必须跑 `node scripts/gen-game-data.mjs` 重生成并记录**新 sha256**（G06 基线 sha 见 §2.2）。
8. **`child_birth_test.cpp` 整删**：既有 B 类 `ChildBirth.GoldenSequenceSingleBirth` 随文件消失，ctest 总数下降需与删除用例数算术闭合。

---

## 5. 剩余批次指针与已知阻塞

| 批次 | 内容 | 侦察落点 | 关键前置 / 阻塞 |
|---|---|---|---|
| **G03** | 删生育/道侣/亲缘（`parentId` 全链） | [`recon-G02-G03.md`](recon-G02-G03.md) `## G03` | 见 §4（失败复跑） |
| **G04** | 删洗炼/资质/悟性/天赋体质词条/血炼/职位特质/战斗随机成长 | [`recon-G04.md`](recon-G04.md)（含 `§11` 产品缺口） | 🔴 **待产品拍板**：悟性删除后突破率公式无定义。三口径见 [`HANDOVER-m1-remaining.md`](HANDOVER-m1-remaining.md) §5#1——**口径①「随悟性一并删除」是唯一无新协议可独立完成的**（推荐）；另：`comprehensionAdd` 与 `SkillStats.comprehension` 是两套概念、UI 有 5 处把 `intelligenceAdd` 标为「悟性」（预存文案 bug）；`ELDER_SKILL_BASELINE` 双重身份，只删突破率引用不动教学公式 |
| **G08** | 角色模板层 + `templateId` 实例化 + 开局周明/5 万 + 兑换码改道 | [`recon-G05-G06-G08-G09.md`](recon-G05-G06-G08-G09.md) `G08` | 口径见第一份 §4#9/#10/#11/#12；已知影响：兑换码改道会牵动 4 处测试 + 6 个死 helper；AI 宗弟子构造保持旁路（`templateId=""`） |
| **G09** | 抽卡核心 `gacha_tx`（roll/保底/碎片/升星/解锁/入库） | 同上 `G09` | 硬依赖 G08 模板读取层（当前 `characterTemplates` 零生产读取方） |
| **G11** | 最简寻访 UI：主界面 + 结果页 Q30/Q31 + 图鉴最小 + `GachaDelegate` | 同上 `§G11` | 🔴 **素材注册硬阻塞**：12 个角色精灵键全部未注册；源图在 `模拟宗门美术素材/<角色名>/{头像,全身像}.png`（该目录永不提交，注册流程见 `rules/static-resources.md`）；色表强制 Q31（`GameConfig.Gacha` 单源），禁用 `ItemCard.getRarityColor` 旧色表 |
| **G10** | RNG 对拍基线重录 + 全量回归 + 死代码 grep 清零 | —（协议 §2） | 必须在 G02–G07 全部合入后**唯一一次**重录窗口；G08/G09 建议同窗 |

---

## 6. RNG / 金黄纪律与 B 类登记

### 6.1 纪律

1. 金黄 / baseline **只在 G10 重录**；中间批次出现红：A 类（编译错、非 RNG 断言）必修；B 类（RNG 平移导致的金序列漂移）**逐条登记，不重录**。
2. 门禁绿判定（中间批次）= 编译 EXIT=0 **且** 失败集合 ⊆ B 类登记集 **且** 零 A 类。
3. 每批报告必须产出「本批新增 B 类清单」（文件::用例名 + 原因），G10 汇总消化。

### 6.2 当前 B 类登记（C++，G05/G06 持平未变）

```
ChildBirth.GoldenSequenceSingleBirth
DiscipleFactory.GoldenSequenceSeed42
DiscipleFactory.GoldenSequenceSeed987654321Female
DeterminismProbeTest.DigestMatchesGoldenBaseline
```

Kotlin 侧 K1–K4 观察项实测全绿（`Differences` 家族全通过）。G03 预期会新增一批 SYSTEM 序列平移项（月结/旬结/赠礼），由 T 与 c340 各自产出清单、主线程合并入 report-G03 §G10 表。

### 6.3 G10 待办汇总（累积登记）

| 来源 | 条数 | 内容指引 |
|---|---|---|
| G02 | 保留项 14 条 | [`report-G02.md`](report-G02.md)（含 `SettingsPatchFieldCoverageTest` 类死引用、`markDead` 命名收口、`isOutsideSect` 语义过载等） |
| G05 | 途中发现 8 条 | [`report-G05.md`](report-G05.md)（战俘收编下线产品变更、`prisonerSpiritRootFilter` 死设置、`RecruitFailed` 零发布者、`RECRUIT_MONTHLY_LIMIT` 残留等） |
| G06 | G10 登记 6 条 | [`report-G06.md`](report-G06.md)（`clearAllDiscipleSlotsForRemoval` 零调用方、`eraseDiscipleDerivedMaps` 生产零消费、recon 行号过期、既有 B 类持平） |
| G03 | 待产出 | 本批新增 B 类清单 + `annualDesertedDisciples` 类零写入字段口径（G06 已登记：字段保留但全仓零写入） |

---

## 7. 第一份文档中仍然有效的硬口径（复述提醒，细则回原文）

1. **玩家侧弟子永不死亡 = 重伤**（钳 `INJURED_HP`、`isAlive` 保 1、不清槽/不解绑/不物化行囊、不计年报死亡）；回血复用既有每旬机制，**不新增机制与配置项**。
2. **ActionId 只增不复用**：退役 = 保号 + 删 dispatch case + desc 标【已退役，编号禁复用】；`gen-action-ids.mjs` 生成后必须 `git diff --exit-code` 自证零漂移。
3. **Room 列禁 `DROP COLUMN`**：用 `rebuildTableDroppingColumns`（create-copy-drop-rename）+ 递增版本 + 注册 + 迁移测试；旧 `Index` 声明不得删。
4. **ProtoBuf `@ProtoNumber` 只增不复用**：删字段在两处 proto 定义写 `reserved`。
5. **C++ AUTHORITATIVE**：核心逻辑下沉 C++，Kotlin 只读展示；`MirrorReadOnlyGuardTest` 必须零命中。
6. **UI 不驱动系统 tick**：界面实时数据订阅 `GameEngine` StateFlow 派生。
7. 详见 [`HANDOVER-m1-remaining.md`](HANDOVER-m1-remaining.md) §4（14 条口径）与 §5（待拍板/硬阻塞）。

---

## 8. 下次开工 checklist

1. `git status` 确认工作树干净（应只剩 §1 的四组未跟踪）；构建副产物如被改则 `git checkout --` 还原。
2. 读 [`EXECUTION-PROTOCOL.md`](EXECUTION-PROTOCOL.md) + 本文件 §2/§3 + 第一批次侦察章节；触达面按仓库根 `AGENTS.md` §0 路由表读 `rules/` 下对应专题文件。
3. 按 §1.2 顺序取下一批（当前 = **G03**，任务书见 §4.3）：先写「做 / 不做」两栏并核对串行约束（与 G02/G04 共享 `models.h` / `DiscipleTables*` / `Disciple.kt`）。
4. 三代理并行（§3.2 分片）→ 主线程集成（§3.3）→ 门禁（§2.2）→ 双 changelog + report → 单次提交（§3.4）。
5. 若 G03 再次出现代理中断：先 `git status` 固化半成品清单、`git checkout -- .` 回退、再按 §4.3 重派；**不要在混入半成品的树上继续施工**。

---

## 9. 诚实状态声明

- **M1 完成度 3 / 9 批**（G02 / G05 / G06 已提交且门禁全绿）；G03 首次尝试**失败并已回退**（未产生任何提交）；G04 / G08 / G09 / G11 / G10 **尚未开始**。
- 本文件不改变任何代码状态：工作树 = HEAD `e1a69d8e9`，除本文件本身外无改动。
- 阻塞项：G04 待悟性口径拍板（口径①可独立开工）；G11 待 12 个角色精灵键注册；G10 须等 G03–G07 全部合入。
- 已知但未处理的问题清单：第一份 §9（10 条）+ 各批 report 的「途中发现 / G10 登记」（§6.3 汇总）。
