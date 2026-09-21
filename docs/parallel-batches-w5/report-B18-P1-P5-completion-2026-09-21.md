# B18 批 P1–P5 完成报告（2026-09-21）

> 批次：**B18**（回滚臂删除批，方案 §7.3 收口）剩余项 **P1–P5**
> 施工细则：`docs/parallel-batches-w5/b18-remaining-impl-2026-09-20.md` §1–§5
> 实施渠道：**桌面 WorkBuddy AI**（用户 2026-09-21 09:2x 明确"不要在 zcode 上实施，就通过 workbuddy ai 上实施"）
> 范围：仅 P1–P5；**B20/B19 未启动**（用户指令"完成 b18 就结束"）
> 前置四臂：臂1 `df6b70d5a` / 臂2 `efba3ee72` / 臂3 `a7de1360a` / 臂β `4ed1d7c24`（JNI 基线 91 → 87）

---

## 0. commit 清单（9 笔，每子项独立）

| # | commit | 内容 | 文件 |
|---|---|---|---|
| 1 | `85603146a` | **P1-A1** `upsertsJson`/`valueJson` typed 化 | 7 文件 +516/-28 |
| 2 | `ed235c479` | **P1-A2** `DiscipleRow.storageBagItems` typed 化（110 号 + 111 号列携带位） | 6 文件 +344/-32 |
| 3 | `ff127abc8` | P1-A2 补遗（JVM typed 臂字符串分派改用 `JsonPrimitive.isString`） | 1 文件 +1/-3 |
| 4 | `ce4e3f84b` | **P2** `indexById` 收口（`month_settlement.h` 两处） | 1 文件 +10/-6 |
| 5 | `802564565` | **P3** 注释/口径收口（codegen 注释面 + 渲染桥 + bench 口径） | 5 文件 +38/-20 |
| 6 | `68d9f9ea1` | **P4** `aiBeastEncounterTargets` 死臂清理（调用链闭合） | 5 文件 +29/-143 |
| 7 | `2448af6f1` | **P5** γ-收口（判归 A = 零删除） | 5 文件 +45/-18 |
| 8 | `f55f8337b` | 文档三件套（方案 §7.2 / CHANGELOG / 渲染清单）+ 本报告首版 | 4 文件 +378/-3 |
| 9 | **本提交** | **P1 §1.6 勘察点② 实测台架 + 三文档登记回填**（见 §1「P1 §1.6 勘察点② 实测登记」） | 5 文件 |

---

## 1. 逐子项落地摘要

### P1-A1（`85603146a`）——信封级两字段 typed 化

- **proto（只增字段）**：新增通用递归承载 `TypedValue` / `TypedField` / `TypedRow`；
  `CollectionChange` 追加 `repeated TypedRow upsertsTyped = 4`、`JsonFieldChange`
  追加 `optional TypedValue valueTyped = 3`；旧 2 号 bytes **停写保留、号冻结**。
  `TypedValue` 含 `vNull`（防御位）与 **`vEmptyArray` 判别位**（`[]` 与 `{}` 在 wire 层
  同为零字节不可区分，而形状语义不同）。
- **C++**：`gameview_encode.cpp` 新增递归助手 `appendTypedValue` / `appendTypedRow`，
  删两处 JSON 文本序列化分支；字段号升序 + nlohmann 键序 ⇒ 确定性保持。
- **Kotlin**：`GameViewMirrorCodec` 两处单点切读（typed 优先 + 旧 bytes fallback
  **刻意保留** = 对照面）；typed→JsonElement 重建基建落在 `GameViewDiscipleRows.kt`
  尾部（因 `MirrorConsumerSurfaceGuardTest` 把 `proto.gameview.*` import 锁死在
  codec + 本文件两处）。

### P1-A2（`ed235c479` + 补遗 `ff127abc8`）——行级袋列 typed 化 + **列携带判别位**

- `DiscipleRow` 追加 `repeated TypedRow storageBagItemsTyped = 110` +
  `optional bool storageBagItemsPresent = 111`；75 号停写保留。
- **⚠️ 本批最值钱的判据**：75 号是**标量**，bytes 空/非空天然区分「未携带」与
  「携带空袋」；换轨 repeated 后**零条目同时表示两种相反语义**（列缺省 / 列脏且清空）
  ⇒ 列级合并（`mergeToDisciple` 按补丁 presence 清空基线）会让**"清空袋"静默丢失**
  （镜像保留已消耗丹药 = 用户可见数据错误）。**可达性实测**（非推测）：
  `auto_gear.h:936`（`ds.storageBagItems[row] = d.storageBagItems` 后标脏，而右侧可被
  `decreaseItemQuantity` 扣空）与 `month_settlement.h:1698` 均能在袋被扣空后标脏该列。
  处置 = 列携带位：C++ 行 JSON 携带该键时恒置位（写在全部字段之后保持字段号升序），
  Kotlin 合并判据改 `count > 0 || present`。
- 反向基建 `JsonElement.toTypedValue()` / `JsonObject.toTypedRow()` 与解码侧同文件单源；
  `REPEATED_FIELD_CLEARERS` 补条；codec `bj` → `jx`（`Kind.JSON_ELEMENT`），
  删死枚举成员与孤儿 `ByteString` import。

### P1 §1.6 勘察点② 实测登记（补做，2026-09-21）

台架 = `TypedEnvelopeSizeBenchTest`（桌面 JVM + **真 C++ 编码器**；
`--tests '*TypedEnvelopeSizeBenchTest*'`，2000 迭代 + 500 预热）。口径：同一棵树两种
**真实 wire 形态**——旧形态 = `CollectionChange{ name(1), upsertsJson(2)=JSON 文本 }`
（本台架以 javalite 原样重建，即 B18-P1 前 C++ 编码器形状）；新形态 = **真 C++ 编码器**
（`nativeCoreEncodeGameView`）产出的 `upsertsTyped(4)`。逐集合 3 实体、键集 = C++
`models.h` **真实协议键**；`storageBags` 样本含条目内嵌 `equipmentInstance`（最坏嵌套档）。

| 集合 | 旧形态 | 新形态 | 体积比 | 消费侧耗时比（新/旧） |
|---|---|---|---|---|
| equipmentStacks | 824B | 958B | 1.163 | 1.64 |
| equipmentInstances | 920B | 1111B | 1.208 | 2.10 |
| manualStacks | 2027B | 2356B | 1.162 | 1.87 |
| manualInstances | 1964B | 2284B | 1.163 | 1.23 |
| pills | 937B | 1073B | 1.145 | 2.12 |
| materials | 407B | 454B | 1.115 | 1.44 |
| herbs | 415B | 462B | 1.113 | 1.74 |
| seeds | 394B | 453B | 1.150 | 1.73 |
| storageBags | 2242B | 2606B | 1.162 | 1.92 |

**结论（三轴分解，诚实登记）**：

1. **体积**：typed 形态比旧 JSON 文本大 **11.3%–20.8%**（9 集合全部）——无灾难性膨胀，
   远低于台架 3× 硬门。成因：`TypedField` 每字段携带 key 字符串 + 双重 tag 开销，而 JSON 的
   key 只出现一次且无 per-field tag（代价是引号/冒号/逗号）。**⇒ 无需任一集合「暂留 JSON」**
   （暂留只保留成本、放弃"单一 typed 承载"的语义统一性；镜像信封为**瞬态、不落盘**，
   +21% wire 体积可接受）。
2. **Kotlin 消费侧耗时**：typed 重建比 JSON 文本解析**一致地慢 1.23–2.12×**
   （绝对值 0.005–0.027 ms / 3 实体，随实体数线性）。成因：typed 路径在 proto 解析之上
   再逐字段 `associate` 建 map + 装箱，而 kotlinx `parseToJsonElement` 是高度优化的流式词法器。
   **本轴是"半程对比"**——它**不含**被消除的 C++ `dump()` 与 JSON 文本生成成本。
3. **C++ 编码侧耗时 = 不可测（诚实登记）**：旧编码器（`dump()` + bytes 分支）已随本笔删除，
   **无法在同一实现上对拍**；任何"重实现一个旧编码器"测的都是 Kotlin 侧而非被删的 C++ 路径。
   ⇒ **净性能收益不可判定**，**不得**声称"typed 化更快"。
4. **本笔的确定收益 = 语义/维护面**：单一 typed 承载（消灭"JSON 文本往返"这一跨语言语义
   风险面），而非性能或体积。
5. **附带收益**：本台架在**真实样本**上顺带完成 9 集合两形态解码**逐字段等价复核**（硬断言），
   与 `MirrorTypedEnvelopeEquivalenceTest` 的合成事实源互为补充。



### P2（`ce4e3f84b`）——热路径索引收口（行为等价重构）

- `month_settlement.h` 两处：① 候选循环内同一表达式两次 `indexById` 重建；
  ② 叛逃检查每轮 3 次重建（`freshIdx` + 两个调用点实参）→ 一轮一次构建复用。
- **红线**：跨迭代重建是 A 组 UAF 根治后的正确性机制，**未提循环外**。
- I 族同型勘察（`phase_settlement.h` / `battle_residual_tx.h` / `disciple_store.h`）
  逐点实测 = **零改动**。

### P3（`802564565`）——注释/口径收口

- `build-atlas.mjs`：删「Kotlin 旧逐 rect 路径（灰度回滚臂）引用本表」失效陈述，
  改两路消费者口径；codegen 重生成 ⇒ `scene_uv_tables.h` **纯注释 +2/-1**。
- `NativeBridge.cpp`：删「B18 前新旧两路」措辞 → 「生产路径唯一 + 测试侧冻结参考臂」。
- **`scene_draw.h` 修正失效守卫名**：原注释引用不存在的 `SceneOverlayFlagsMirrorGuardTest`
  （全仓无此文件），实名 `SceneOverlayProtocolGuardTest`。
- `MirrorSegmentProjectionBenchTest`：测试 2 去「对照」措辞。
- **零改动核对**：`float_text.h` 4 处 / `scene_draw.h` :11+:148 / `scene_*.cpp` 4 文件 /
  `NativeBridge.cpp` :134+:162+:212+:287 —— 逐项实测自洽。

### P4（`68d9f9ea1`）——死臂清理（调用链闭合）

- 死判：`gameData.aiBeastEncounterTargets` 全仓（Kotlin + C++）**零插入者** ⇒ 恒空 ⇒
  遭遇战分支**自始不可执行**。
- 删除面（闭合）：`resolveEncounterPath` / `launchEncounterBattle` /
  `selectBeastDefenders` / `resolveBeastEncounterIfAny` + 两处分支调用 +
  `manualDefenders` 死形参（两层签名）+ 私有别名与 internal 常量 + 孤儿 import。
- **保留面（红线）**：`GameData` 字段本体（判归 = **值保留**）、`exploration_tx.h`
  字段（零 C++ 改动）、`GameDataFieldPatchGuardTest` 三条守卫、DI 访问器。
- 文件内留 **tombstone 注释**：删除理由 + 复活须知（接上插入者**不会**自动恢复特性）。

### P5（`2448af6f1`）——γ-收口（判归 A = 零删除，一行生产逻辑未改）

- 三处 `null` 出口重标性质：`!authoritative` = 全局逐动作 OFF kill-switch 投影 /
  `!isLoaded` = 平台兜底前置 / `containsKey("error")` = 失败信封容错——**均非灰度回滚臂**。
- 「适配范围」实测校准为 **6 文件 11 调用点**（PatrolBattleSystem ×3、
  AISectBeastAttackProcessor ×3、MissionSystem ×2、ExplorationService ×1、
  SecretRealmService ×1、EncounterBattleService ×1）。
- `BattleExecutionRouterTest` 补**诚实登记**：失败信封分支 JVM 不可单测（无注入缝），
  **不造假用例**。

---

## 2. 逐门实测证据（命令 + 数字）

### 门 1：桌面 GTest（ctest 全量）

```
cd android/app/src/main/cpp/gamecore
export PATH="<cmake 3.22.1>/bin:<llvm-mingw>/bin:$PATH"
<cmake>/ctest.exe --test-dir build/desktop-test
```

| 轮次 | 结果 | 说明 |
|---|---|---|
| A1 前（发现失败） | 1556/1557（1 失败） | `CollectionChangeCarriesTypedRows` 测试自身断言层级错误（已修） |
| **A1** | **1557/1557，EXIT=0**（392s） | = 真实基线 1556 + A1 新增 1 例 |
| **A2** | **1558/1558，EXIT=0**（298s） | = A1 + `DiscipleRowEmptyBagCarriesPresenceBit` |
| **P2** | **1558/1558，EXIT=0**（305s） | 计数持平 = 行为等价重构实证 |
| **P3** | **1558/1558，EXIT=0**（304s） | codegen 注释面变化后重跑 |
| **P4/P5** | **1558/1558，EXIT=0**（375s） | 纯 Kotlin 批照跑（SOP） |

> **⚠️ 基线校正**：ctest 真实基线 = **1556**（历史文档/skill 记的「1553」是**单进程直跑**
> 口径误记）。已回写方案 §7.2 与 CHANGELOG。

### 门 2：组合验收门（六模块）

```
cd android && export JAVA_HOME=<jdk-21>
./gradlew.bat testReleaseUnitTest --max-workers=1 \
  "-Dgamecore.jni.path=C:/Mnzm/XianxiaSectNative/android/core/engine/build/desktop-jni/libgamecorejni.so" \
  detekt compileReleaseKotlin lintRelease
```

| 轮次 | 结果 | 任务面 |
|---|---|---|
| A1 | BUILD SUCCESSFUL **19m35s** | 339 任务（34 executed / 305 up-to-date） |
| A1 首轮（打回） | FAILED | `Unresolved reference 'toJsonElement'`（缺 import，已修） |
| A1 次轮（打回） | FAILED | `detekt` `NestedBlockDepth`（`envelope` 抽助手后转绿） |
| A2 | BUILD SUCCESSFUL **25m43s** | 339 任务（54 executed） |
| P2 | BUILD SUCCESSFUL **5m22s** | 339 任务（18 executed）——纯 C++ 批，Kotlin 侧 UP-TO-DATE |
| **P2 补强** | `:core:engine:testReleaseUnitTest --rerun-tasks` **BUILD SUCCESSFUL 18m05s**（53 任务全 executed） | **证据补强**：纯 C++ 批不触发 Kotlin 测试重跑 ⇒ 强制重跑 engine 全模块，Diff* 携新桥实证 |
| P3 | BUILD SUCCESSFUL **14m03s** | 339 任务（27 executed） |
| **P4+P5（共享一轮）** | BUILD SUCCESSFUL **27m18s** | 339 任务（59 executed） |
| **P4 独立门（补跑，skill 文档口径）** | BUILD SUCCESSFUL **20m13s** | 319 任务（43 executed / 276 up-to-date）；**前 4 次尝试均被环境/外部事件中断，非代码失败**（逐条留痕见 §3.6） |

**P4 独立门（补跑）实测**：`testReleaseUnitTest`（六模块）+ `:core:engine:detekt` +
`:feature:game:detekt` + `:app:lintRelease`（= **skill 文档口径的组合门**）→ 全绿。
`:core:engine` **3381 例 / 0 失败 / 0 错误 / 0 skip**（XML 10:07Z 本轮实证；= B18 的 3380 +
本次新增勘察点② 台架 1 例）；`Diff*` 50 类 273 例 0 skip；`SoftwareCanvasBackend*` 106 例
0 skip；`GameDataFieldPatchGuardTest` 6/6；`MirrorTypedDiscipleBagGuardTest` 3/3；
`MirrorTypedEnvelopeEquivalenceTest` 3/3；`TypedEnvelopeSizeBenchTest` 1/1。

> ⚠️ **计数口径提醒**：六模块 TOTAL 本轮为 **7888**，其中 **5 例属并行会话在飞的 B19 测试文件**
> （`core:data` 716 → **721**，即其新增的 `RoomMigrationV51To52Test`）⇒ **B18 口径 TOTAL = 7883**。
> `core:data` 的 XML 时间戳为 07:22Z（未在本轮重跑，因该模块非本批输入）⇒ 其数字含外部在飞内容。

**六模块用例汇总（P4/P5 轮，XML 时间戳 05:02–05:04Z 本轮实证）**：

```
core:engine  tests=3380  fail=0 err=0 skip=0    (XML 05:02Z)
core:domain  tests=1743  fail=0 err=0 skip=0
core:data    tests=716   fail=0 err=0 skip=15   (既有 skip)
core:ui      tests=146   fail=0 err=0 skip=0
feature:game tests=887   fail=0 err=0 skip=0    (XML 05:04Z)
app          tests=1010  fail=0 err=0 skip=2    (XML 04:58Z)
TOTAL        tests=7882  fail=0 err=0 skip=17
```

- **`Diff*` 50 类 273 例 0 skip**（携新桥，每轮均核）；
- **`SoftwareCanvasBackend*` 10 类 106 例 0 skip**（Canvas 兜底零改动实证）；
- **`GameDataFieldPatchGuardTest` 6 例 0 失败**（P4 值保留锁定面未破）；
- `detekt` / `compileReleaseKotlin` / `lintRelease` 全绿。

### 门 3：JNI 计数门

```
node scripts/check-jni-count.mjs   →  ✓ total=87/87，双桥无扩散
```
P1–P5 **零新端口**（JNI 面零变更）。

### 门 4：NDK arm64 真机侧编译

```
./gradlew.bat :app:externalNativeBuildRelease  →  BUILD SUCCESSFUL in 3m35s
```
（51 条既有告警，无新增错误；C++ 改动面 = P1-A1/A2 + P2 均已过真机侧编译。）

### 门 5：桥重建（每笔 C++ 改动后）

clang++ 直调（等价 `scripts/build-desktop-jni.ps1`，Bash 无法调用 PowerShell 的安全降级）：
A1 **EXIT=0 / 9,637,888 B**、A2 **EXIT=0 / 9,636,864 B**、P2 **EXIT=0**。

---

## 3. 诚实登记（未达标 / 残余 / 偏差）

### 3.1 施工卡口径勘误（3 处，已回头登记进方案 §7.2）

1. **P3 §3.2 前提反向**：施工卡称「对照对象列级臂已于臂 3 删除」并要求把 bench 两条
   硬性质改为"对自身趋势"。实测：臂 3 删的是**非列级**导出臂（`dirtyColumnExport`），
   臂 2 删的是投影旗标——本测构造的两种**信封形状**（列级脏列补丁 vs 全字段行）都仍可
   构造；且第二条「两臂落库逐字段全等」正是**等价守卫本体**。按其字面改会**削弱守卫力**
   （违反"删臂后守卫不得失去对照面"红线）⇒ **保留两条断言**，只校正 KDoc 并就地登记
   `FakeGameStateStore.assembleAll()` O(D) 组装偏置（消除属 B20a）。
2. **P3 生成注释指令与事实相反**：施工卡称删「预览框/选中/拆除高亮」（理由"Vulkan 消费者
   已随臂 β 退役"）。实测 C++ `scene_draw.h::buildOverlayLayers` **仍消费全部四类**
   （网格线/预览框/选中/拆除高亮），退役的是 **Kotlin 逐 rect 绘制臂** ⇒ 按事实改写
   （否则注释失真）。
3. **P3 守卫名错误**：施工卡/源码注释引用 `SceneOverlayFlagsMirrorGuardTest`，全仓无此
   文件；实名 `SceneOverlayProtocolGuardTest` ⇒ 已修正。

### 3.2 超出施工卡清单的改动（均为"调用链闭合"必需，已逐项说明）

- **P1-A2 新增 111 号列携带位**（施工卡 §1.2 未预见）——理由见 §1 P1-A2；这是**防止
  用户可见数据错误**的必需项。
- **P4 连带删除面**：`launchEncounterBattle` / `selectBeastDefenders` /
  `BEAST_DEFENDER_EXCLUDE_STATUSES` / `manualDefenders` 死形参 / 3 个孤儿 import
  ——施工卡只列了 4 个触点 + 2 个函数，但"先 grep 确认调用链闭合"要求闭合处置；
  其中 `manualDefenders` 由 detekt `UnusedParameter` 实证为死形参。
- **P2/P4 触及 `GameEngine.kt` / `GameEngineWorldBattleOps.kt`**（P4 的调用点删除）。

### 3.3 残余项（未做，明确登记）

1. **其余 repeated 列缺"列脏且清空"判别位**（`manualIds` / `talentIds` / `physiqueIds` /
   `affixIds` / `statusData` / `activePillTypes` / `usedPill*`）——**既有协议空洞**，
   非本批引入；建议由 **B20b**「typed 投影 + 脏位图」一并收口。
2. **`ExplorationService.encounterBattleService` DI 访问器 + `SubSystems` 字段**：P4 后无
   生产消费者（测试仍注入）⇒ 保留未删（不扩大删除面），登记为残余。
3. **P4/P5 共享一轮组合门** → **已补跑 P4 独立门**（§2 门 2 表末行，skill 文档口径，全绿）。
   两笔 commit 内容均为该轮验证状态的**子集**，且 P5 的 diff 为**纯注释/KDoc 文本**
   （45 insertions / 18 deletions 无一行生产逻辑）⇒ 生产代码状态与 P4 commit 等价。
   **残余**：P5 未单独再跑一轮（其零行为变更已由文本 diff + P4 独立门覆盖）。
4. **真机像素级/真机运行时回归**：仍无设备农场基建（延续 B11/B12/B13 残余③），
   本批未涉及渲染行为变更，风险面为零。
5. **P2 组合门为"UP-TO-DATE + 补强重跑"两段证据**（非单次全量门）——已在 commit body
   与上表显式登记。

### 3.4 途中发现并修正的前会话遗留缺陷（3 处，均被门实测打回）

1. **编译错误**：`MirrorTypedEnvelopeEquivalenceTest` 缺 `toJsonElement` import
   （前会话因环境封锁从未编译过 A1，组合门首轮打回）。
2. **detekt**：`envelope` `NestedBlockDepth` = 4（次轮打回，抽助手后转绿）。
3. **ctest**：`CollectionChangeCarriesTypedRows` 把 TypedField **子消息原文**当 key 比对
   （应再解一层）。

另**主动加固 2 处**：C++ 真编码臂显式 `discipleRowsAsPatches = false`（防形态缺省静默
短路，B18-臂β 教训）；JVM typed 臂字符串分支优先（防 `"1001"`/`"true"` 数字形字符串
两臂静默分叉，并在事实源加例钉住）。

### 3.5 未触碰面（红线实证）

- **Canvas 兜底**：`SoftwareCanvasBackend*` 改动面 = 0（106 例 0 skip 佐证）；
- **存档 schema / 协议既有字段语义**：零变更（proto 只增字段，2/75 号停写保留）；
- **构建副产物**：`atlas-rgba-manifest.json` 每轮均 `git checkout --` 还原，未混入任何提交；
- **`dispatch-ledger.md`**：本会话未提交该文件（其内含看护未提交的锁行编辑，避免覆盖）。

---

### 3.6 环境事件登记（非代码失败，逐条留痕）

本批门禁期间遇到 **4 类环境事件**（均**非**本批代码问题，已逐条取证）：

1. **Gradle 守护被外部 `--stop` 中途杀死**（首次 P4 独立门，`--rerun-tasks` 轮）：
   失败信息 = `Gradle build daemon has been stopped: stop command received`（**非**编译/测试失败）。
   取证：本会话未执行 `--stop`；同工作区另有会话在跑（见 4）。处置：`--stop` 后重跑。
2. **守护状态损坏导致 Kotlin 编译器文件名乱码**（第二次尝试）：
   `error: source file or directory not found: ...BattleSystemu4F24u5BB3Ops3.kt`——中文名
   （`BattleSystem伤害Ops3.kt`）被转义成 `uXXXX` 字面量。取证：`gradle.properties` 已含
   `-Dfile.encoding=UTF-8`，且清守护后**同一命令 21s 编译成功** ⇒ 结论 = **守护被外部杀死后
   的残留/新起守护编码环境漂移**，非仓库配置缺陷（**未改仓库配置**）。
3. **"暂停"中断**（第三次尝试）：用户 `暂停` 指令终止后台任务，日志停在 `:feature:game`
   编译中、无失败段 ⇒ 属**主动中断**，非失败。
4. **外部在飞工作打回全模块 detekt**（第四次尝试）：`:core:data:detekt` 报
   `RoomMigrationV51To52Test.kt:86 LongMethod (80 > 60)`。取证：该文件为**未跟踪**文件
   （`??`，15:21 由**另一会话**创建，主题 = Room V51→V52 迁移，正对应 B19），且
   `git diff --stat 985719d24..HEAD -- android/core/data/` **为空**（B18 零触碰 core:data）
   ⇒ **与本批零相关**。处置：改用 **skill 文档口径的组合门**
   （`testReleaseUnitTest` + `:core:engine:detekt` + `:feature:game:detekt` + `:app:lintRelease`），
   该口径**本就不含** `:core:data:detekt`（此前几轮跑的是更严的 bare `detekt`）。
   **未触碰、未移动、未提交**该外部文件（尊重并行会话在飞工作）。
5. **本批自身的一处真失败（第五次尝试，已修）**：改用文档口径后 `:core:engine:detekt`
   报 **5 项** `ImplicitDefaultLocale`——**全部在本次新增的 `TypedEnvelopeSizeBenchTest`**
   （`String.format("%.3f", …)` 隐式 locale）。取证：该文件此前只跑过 `--tests` 过滤测试任务、
   **未过 detekt** ⇒ 属**真实遗漏**（非环境）。处置：全部改 `String.format(Locale.ROOT, …)`，
   `:core:engine:detekt` + 编译复验 **12s 转绿**，第六次尝试 **BUILD SUCCESSFUL 20m13s** 通过。

**给看护的提示**：同工作区存在**并行会话正在实施 B19**（Room 迁移），其未提交文件目前
使**全模块 `detekt`** 变红。若验收轮跑 bare `detekt`，需先确认该文件已被其作者修正或提交；
按 skill 文档口径的组合门不受影响。



## 4. 文件清单（按 commit）

见 §0 表格；测试面新增三个文件：

- `android/core/engine/src/test/java/com/xianxia/sect/core/nativebridge/MirrorTypedEnvelopeEquivalenceTest.kt`（A1，信封级双路等价 + C++ 真编码臂 + 数字 content 红线）
- `android/core/engine/src/test/java/com/xianxia/sect/core/nativebridge/MirrorTypedDiscipleBagGuardTest.kt`（A2，行级袋列双路等价 + **列携带位双向语义** + C++ 真编码臂）
- `android/core/engine/src/test/java/com/xianxia/sect/core/nativebridge/TypedEnvelopeSizeBenchTest.kt`（本提交，勘察点② 实测台架）

文档面：

- `docs/native-engine-refactor-plan-2026-09-17.md` §7.2（B18 收官段 + 勘察点② 登记）
- `CHANGELOG.md`（4.01.15 段内 B18 收官小节 + 勘察点② 登记）
- `android/docs/renderer-feature-checklist.md`（3 行臂β 退役后的失效表述修正）
- `docs/parallel-batches-w5/report-B18-P1-P5-completion-2026-09-21.md`（本报告）

---

## 5. 验收建议（看护侧）

1. 亲跑两门：`ctest` 全量（期望 **1558/1558 EXIT=0**）+ 组合门（`--rerun-tasks`
   全量重跑可作最强证据）；
2. 重点复核 3 处判断：**111 号列携带位的必要性**（§1 P1-A2）、**P3 bench 未按施工卡
   弱化断言的理由**（§3.1-1）、**P4 调用链闭合删除面**（§3.2）；
3. 如需逐笔独立门，补跑 P4 一轮（§3.3-3）；
4. 台账 B18 行状态更新为 accepted 由看护设置（本会话未动台账）。
