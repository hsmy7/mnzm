# B20 镜像残余专项批完成报告

> 交付：2026-09-21（实施会话；用户指令「直接实施 B20，完成后收尾」）
> 卡：`batch-B20-mirror-residual.md`（§5 状态 / §6 勘察结论）；卡定义 = `b18-remaining-impl-2026-09-20.md` 附A
> 前置核验：B18 P3 ✅ `802565...`→`802564565`（Bench 口径收口）+ P1 TypedRow 基建 ✅（`85603146a`/`ed235c479`）
> **登记状态：delivered（待验收）**——看护已终止（台账 21:58 条），`accepted` 待验收轮亲跑设置

---

## 0. 结论速览

| 阶段 | commit | 内容 | 结果 |
|---|---|---|---|
| B20a | `07bc405b6` + `a115921bb` | 列级补丁 presence 列直写 + 测试替身生产口径化 | **main 实红的 Bench sanity 门复绿**；列级臂 D=1000 37.62→15.72ms、D=5000 139.30→64.21ms |
| B20b | `131019d61` | rest 域每旬标脏段量化台架 | **实测反转预估**：零变更旬 160.2ms / 997,951 mallocs；屏障迁移独立立卡建议 |
| B20c | `46bfa2cfc` | G2 分档收官 + COW 保真 | D=5000 <150ms **达标** ⇒ WS-1 关闭判据持续满足；D≤1000 未达标如实登记重拍板待裁决 |
| 收尾 | 本报告 + 三件套 | 方案 §7.2/§0.2 + CHANGELOG + 台账 | B20 行 delivered；总收官段见 §5 |

**零玩家可见变更、零存档面变更、零 proto schema 变更、零生产 C++ 变更**（B20b 为测试台架）。
**JNI 计数 87 不变**；游戏内 `changelog_entries.json` 未追加；`version.properties` 未递增。

---

## 1. B20a — 消费侧组装偏置消除 + 列级收口（G 残余①②）

### 1.1 勘察即发现：main 上 Bench sanity 门实红

列级信封稳态每行只携 3~5 脏列，旧全行合并臂（`assemble` 基线 + `toRow` 全行合并 +
`toDisciple` + 全组列写）每行 ~300 次列访问——**比全行臂更慢**：

```
[B09-mirror-bench] D=1000 列级 37.62ms vs 全脏 28.00ms = 1.34×（门限 1.15×）→ AssertionError
```

历史上 D=5000 档的大 O(D) 偏置（两臂各背同一 `assembleAll` 常数）掩盖了该每行成本
（B09 登记 139.30 vs 132.68 压线 1.15× 通过）。**该门在 B20 开工前的 main 上是红的**
（2026-09-21 复捕，B20a 前首轮实测）——勘察阶段定案：卡内的「列级收窄评估」升格为必做。

### 1.2 实施（`07bc405b6`）

1. **`GameViewDiscipleRows.applyPatchInPlace`**（+ `DiscipleTables.patchExistingMirrorRow`
   原位写入口：锁/写守卫/changedId 语义与 `upsertMirrorRow` 相同）：补丁 presence 列
   原位直写，净效果与全行臂**逐列全等**：
   - 标量列直写（`cultivationCheckpoint` Long→Double、社交 ""/0/-1 哨兵→null、
     `status` 宽松枚举、usage 布尔→0/1 同口径）；
   - repeated/映射列整列替换（`REPEATED_FIELD_CLEARERS` 同清单）；
   - **孕养消息列整值替换**（clearer 先清 + mergeFrom 整值写入）——实施中曾按
     "字段级 overlay" 误读实现，**等价守卫实测逮出后修正**（守卫存在价值的直接实证）；
   - 储物袋三表达（110 typed / 75 legacy / present 清空）含 **75-only 的 typed 优先
     怪语义**（基线袋非空时 75 被吞）逐输入对齐；
   - **协议外瞬态列净效果显式复刻**：`lifeEvents` 恒清空（瞬态显示列，每旬镜像重投后
     由投影事务重写）、`slotIds` 恒 0（`toDisciple` 回写）、`deathYear` 两臂同丢弃；
   - 新行/幽灵行返回 false 回退全行臂（稀疏新增 fail-fast 语义不变）。
2. **`StateSyncService.applyDisciplePatches`** 接线：存在行直写、新行/幽灵行回退。
3. **`FakeGameStateStore` 生产口径化**（Bench 登记的 O(D) 偏置消除）：旧面每事务
   `assembleAll()` 全表组装 → `GameStateStoreImpl.dispatchAssemble` 同款三判据
   （零弟子写入零组装 / changedIds≳半表 `assembleAllPatched` / 稀疏
   `assembleAllIncremental` / 容量拒绝全量兜底）；构造期 replaceAll 的 tracker 污染
   同事务内消费（对齐生产 COW 提交态基线）；稳态零写入断言（`nonMirrorWriteCount`）
   判定序不变。
4. **守卫**：新增 `GameViewDiscipleColumnApplyEquivalenceTest` 12 场景（老合并臂 vs
   新直写臂：组装弟子逐字段全等 + 协议外/稀疏列净效果 + changedId 全等）；Bench 两臂
   落库全等断言不破。

### 1.3 Bench 前后对照（同机同轮，Robolectric 桌面 JVM）

| 口径 | B20a 前 | B20a 后 |
|---|---|---|
| 列级臂 D=1000 | **37.62ms**（对全脏臂 +34% 慢，门红） | **15.72ms**（-37%，门绿） |
| 列级臂 D=5000 | 139.30ms（B09 登记，压线） | **64.21ms** |
| 全脏投影臂 D=1000 | 28.00ms | 24.94ms |
| 全脏投影臂 D=5000 | 132.68ms（B09 登记） | 101.99ms |

### 1.4 detekt 补（`a115921bb`）

首轮组合门唯一红项：5 个分段直写函数 CyclomaticComplexMethod（16~32 > 15）——
presence 位测试映射表样板（与 C++ `serializeDiscipleColumn` switch 同形），按 B10
LongParameterList 先例加函数级 `@Suppress` + 理由注释。

---

## 2. B20b — rest 域标脏段量化台架（H 残余③：定性→定数）

新增 `RestDomainDiffBench`（`131019d61`，G1 bench 同族：heap 分配计数替换 +
gtest_discover 每用例独立进程；三口径 = 序列化-only / 零变更旬段 / 单字段变更旬段；
场景 = D=5000 带实例清单 1.5 万 rest 实体 + gameData 容器现实面）。

### 2.1 实测（2026-09-21，Release，本机）

```
serialize-only:            41.3ms（树 5.9MB）
零变更旬段:                160.2ms / 997,951 mallocs   ←「零变更也照付」的部分
单字段变更旬段:            173.7ms / 997,952 mallocs（diff 正确性旁证 = 恰产出 spiritStones 一字段）
```

**定性反转**：此前按生产稳态 mirror 2.6ms/旬（R2.3（一）登记，真实弟子规模）外推
估 rest 域段为 1-3ms 量级；实测证明 G2 压力口径（D=5000 全清单）下它是**最大的单项
镜像成本**（大于 B20a 消除的全部 Kotlin 侧残余）。

### 2.2 屏障迁移 = 独立立卡建议（`batch-b09-residual`，不塞本批）

完整迁移 = gameData 137 字段 + 9 集合的**全仓写屏障审计**（仅 `execute_dispatch.cpp`
即 74 处 push_back/erase；`inventory.h`/`disciple_purchase.h` 等头文件内联变异点更分散；
`markGameDataField` API 在库但生产零挂载）。**部分挂载 = 漏标 = 静默陈旧镜像**——
不可接受；B09 弟子域屏障为独立整批先例。本台架即该批的验收基线（届时拍板预算门）。

**零生产 C++ 变更**：本阶段仅 `test/bench/` 两文件（台架 + CMakeLists）。

---

## 3. B20c — G2 分档基线收官 + COW 保真（H 残余④ + WS-1）

1. **替身 COW 保真**（`46bfa2cfc`）：事务构造从 replaceAll 全列写重建换 committed
   基线表 `deepCopy`（COW 每列 O(1) 存储共享、零列写——生产每事务 deepCopy 同语义）；
   基线失效（初次/测试直改 `disciplesValue`）走 replaceAll 重建；事务表晋升提交基线时
   锁写（对齐生产出厂锁）。D=1000 带 15.7→17.5ms（±12% 抖动带内）= **保真改非提速改**
   （replaceAll 非主要成本项）。
2. **分档判定（MirrorSegmentProjectionBenchTest KDoc 登记）**：
   - **D=5000 < 150ms：达标**（64.2~67.8ms，2.2× 余量，较 B09 基线余量翻倍）
     ⇒ **WS-1 关闭判据持续满足**（§7.2/§7.3 回填）；
   - **D≤1000 < 10ms：未达标**（15.7~17.5ms）——按卡纪律**如实登记不放宽断言**；
     余量构成 = 千行 patch 直写 + 千行 `assembleAllPatched` 组装（生产同款语义）+
     Robolectric 事务开销；生产稳态口径（mirror 2.6ms/旬@真实弟子规模）远低于本压力档。
     **重拍板建议：撤销或放宽 10ms@1000 档（待用户/看护裁决）**；
   - **ci.yml 接入考虑 = 不接入**：G2 是计时断言，Robolectric 同轮实测 ±12%，硬阈值门
     必抖红；G1 可 CI 因断言为**确定性 malloc 计数**。生产长期门 =
     PhaseSegmentTimer 每旬打点告警线 100ms（debug 构建，已在线）。
3. **弟子块迁评估（H④）= 无实施面**：报告 §2.8(b) 原文定义 = 「把**非弟子域**迁到与
   弟子域同族的 typed 投影 + 脏位图」——与 H③ 同物（B20b 已量化登记独立立卡）。
   弟子域本身已终态：C++ 行位图 + tombstone 写屏障、Kotlin typed 直读投影（R2.3 二）
   + B20a presence 列直写。

---

## 4. 门禁实录

| 门 | 结果 | 证据 |
|---|---|---|
| 桥重建 | **免**（零生产 C++ 面） | 三阶段生产 C++ 零改动（B20b 仅 test/bench）；`.so` 输入零变化 |
| ctest 全量 | **1561/1561 exit 0**（67.65s） | `GAMECORE_BUILD_BENCH=ON` 重配置，bench 6 例（PhaseSettlement 3 + RestDomain 3）入 ctest 套件；基线 1553 → 1561（+8） |
| 组合门 | **全绿**（分三轮补齐，见 §4.1） | 六模块 7873 / 0 fail / 17 既有 skip；detekt 六模块 + `compileReleaseKotlin` + `lintRelease` 绿 |
| Diff* 0 skip | ✅ **50 类 273 用例 0 skip** | `-Dgamecore.jni.path` 生效轮实测（engine 3396 / 0 fail / **0 skip**，时间戳落本轮） |
| NDK arm64 | **免**（零生产 C++/JNI 面） | bench 目录 `GAMECORE_BUILD_TESTS=OFF` 不进 NDK；JNI 87 不变（`check-jni-count` 口径无新端口） |
| 存档回归 | **零触及声明** | 三阶段零存档 schema/序列化面变更（纯 Kotlin 镜像消费侧 + 测试台架） |
| 已知抖动 | 2 例（ Lifecycle 1 + CrashHandlerBacklog 1，均在满载轮） | 各单跑全绿甄别（12/12、4/4），B03 先例不计失败；带桥重跑轮零抖动 |

### 4.1 组合门轮次台账（诚实登记）

| 轮 | 范围 | 结果 |
|---|---|---|
| 第一轮（B20a 后） | 全模块 + detekt + compile + lint | ❌ detekt 5 issues（CyclomaticComplexMethod）→ `a115921bb` 修复；tests/compile/lint 全绿（12m56s，230 任务全 executed） |
| 第二轮 | 同上 | ❌ 已知抖动 `GameEngineCoreLifecycleInterleavingTest` 1 例（单跑 12/12 甄别绿）；**本轮结果作废**——轮内源文件被 B20c 编辑竞态污染 |
| 第三轮（B20a/b/c 全部入库后） | 同上 | ❌ `:app` 已知抖动 `CrashHandlerBacklogTest` 1 例（save-audit 面文件系统时序断言，单跑 4/4 甄别绿）；其余模块全绿；**⚠️ 本轮另发现 `-Dgamecore.jni.path` 置于 `--rerun-tasks` 之后被 Gradle CLI 吞弃 ⇒ Diff* 256 skip（非失败，是桥未注入）** |
| 补跑 A（带桥 engine 轮） | `:core:engine:testReleaseUnitTest`（`-D` 前置生效） | ✅ **3396 / 0 fail / 0 skip；Diff* 50 类 273 用例 0 skip**（与 B09-B18 历史门基线完全一致） |
| 补跑 B（app 轮） | `:app:testReleaseUnitTest` | ✅ 991 / 0 fail / 2 既有 skip |
| 补跑 C（静态门轮） | `detekt + compileReleaseKotlin + lintRelease` | ✅ 全绿（最终树） |

**六模块汇总（各轮最新绿证合并）**：`:core:engine` **3396**（0 skip，带桥）/
`:core:domain` **1743** / `:core:data` **710**（15 既有 skip）/ `:core:ui` **146** /
`:feature:game` **887** / `:app` **991**（2 既有 skip）——合计 **7873 / 0 失败 /
17 既有 skip**；XML 时间戳均落各自本轮窗口。

---

## 5. 残余与后续（诚实登记）

1. **D≤1000 档 10ms 门未达**（15.7~17.5ms）——重拍板待裁决（撤销或放宽；本台架维持
   「只锁硬性质」口径，未放宽任何断言）。
2. **rest 域写屏障迁移未实施**（B09 残余③本体）——实测 160ms/旬@压力口径 + 独立立卡
   建议 `batch-b09-residual`（本台架为验收基线）。
3. **验收**：B20（及 B18/B19/save-audit 的在袋证据）待看护验收轮亲跑设 `accepted`；
   本报告数字均为实施会话亲跑实测，非转抄。
4. **总收官**：B20 = 重构队列最后一批（B01–B20 全交付）；总收官报告与方案终态登记
   随验收轮完成后由用户驱动。

---

## 6. commit 清单

| commit | 内容 |
|---|---|
| `07bc405b6` | B20a：列级直写 + 偏置消除 + 等价守卫 12 场景 |
| `a115921bb` | B20a 补：detekt 复杂度豁免（映射表样板） |
| `131019d61` | B20b：RestDomainDiffBench 台架（160ms/99.8 万 malloc 实测登记） |
| `46bfa2cfc` | B20c：COW 保真 + 分档判定 + 施工卡 §5/§6 |
| （本笔） | 收尾：方案 §7.2/§0.2 + CHANGELOG + 台账 + 完成报告 |
