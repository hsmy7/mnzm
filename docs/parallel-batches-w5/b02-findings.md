# B02 途中发现登记——解析口径双份与两处范围外观察

> 日期：2026-09-18。来源：批次 B02（R1.2 + R1.3，去物化 + dense 索引）施工完成报告
> "途中发现"第 3、4 条的展开与复核。关联提交：`96636ec95`（R1.2）/ `d4e25dac1`、
> `dd2b4e0e9`、`f7e9b3251`（R1.3 两步 + 补遗）/ `c656dce3f`（文档三件套）。
> 性质：均为**观察登记**，不是 B02 交付的阻塞项；逐项处置建议见文末汇总表。
>
> **增补（2026-09-20）**：本登记册扩展承接跨批"预存可清理项"登记——发现 5 来自
> 批次 B10（R3.1 + R3.2，C++ SceneStore 与 JNI 面重构；关联提交 `ba0901c89` /
> `242440778` / `cd5df439b`），登记口径与 B02 各条一致（观察登记、非阻塞项、
> 带触发条件的处置建议）。
>
> **再增补（2026-09-20）**：发现 6、7 来自批次 B06（R2.1 + R2.2，GameView proto
> 定义 + 镜像通道换 protobuf；关联提交 `daa8eeb11` / `6b3354708` / `9e1d9c0cc` /
> `147a53cab`）。发现 6 为**架构级护栏缺失**（非 B06 引入），发现 7 为 R2.3 前瞻
> 语义纪律（B06 域等价无损）。均观察登记、非 B06 阻塞项，处置建议见文末汇总表。
>
> **第四次增补（2026-09-20）**：发现 8、9、10 来自批次 B07（R2.3 第一波，UI 消费面
> 二进制传输切换；关联提交 `707cbf2df` / `cb59bf537` / `57f1d67ae` / `18083ac5a` /
> `34f9ec767`，审计报告 `docs/mirror-consumer-audit-2026-09-18.md`）。三条均为
> 施工完成报告"途中发现"第 2/3/4 条的展开。因 B08（R2.3 第二波）与 B09（R2.4）
> 已在其后落地，**每条均按 2026-09-20 的代码现状复核过清偿进度**（发现 8 半清偿），
> 登记口径与本册 B02/B06 各条一致：观察登记、非阻塞项、处置建议带触发条件。

---

## 发现 3 —— 整数解析逻辑双份实现（已用守卫测试锁定一致）

### 现象

B02 R1.3 第一步给弟子存储表（`DiscipleStore`）新增"数字 id 列"时，需要一份
"字符串 → 整数"的解析逻辑。这套逻辑仓库里原本就有一份——
`settle_util::toIntOrNull`（`gamecore/system/settlement_detail.h`，Kotlin
`String.toIntOrNull` 的逐字等价实现）。本次没有直接复用它，而是在存储层复制了一份
**同实现**的 `DiscipleStore::parseNumericId`（`gamecore/src/disciple_store.cpp`）。

### 为什么复制而不是复用

仓库分层规则（CLAUDE.md §2.1）："依赖方向不可反转"——`state` 层（弟子存储所在）
只能被上层引用，**不能反向引用** `system` 层（解析工具所在）。若在存储层 include
`settlement_detail.h`，依赖方向即被打破。因此单点复制，并在两处注释中互相指认。

### 风险与已布置的对策

复制的经典风险是"改了一份忘了另一份，两边口径悄悄分叉"（表现为同一弟子 id 在
不同读取路径下被判定为有效/无效不一致）。已布置对策：

- **守卫测试** `DiscipleStoreTest.ParseParityWithSettleUtilToIntOrNull`
  （`disciple_store_test.cpp`，随 `d4e25dac1` 落库）：以边界样本
  （空串 / 非数字 / 数字后缀 / 前导空白 / 尾随空白 / 显式正号 / 负号 / 前导零 /
  int32 上界 / 溢出大数）同时喂两份实现，断言"有效性与数值"逐样本一致。
  任何人改动其中一份，该测试立即红，强制同步。

### 附注：64 位平台溢出行为差异（既有特性，非本批引入）

事实链：解析底层 `std::stol` 解析到 C++ `long`，其宽度**随平台变化**
（Windows 32 位 / Linux、安卓 arm64 64 位）。对超出 int32 表示范围的大数串
（如 `"99999999999"`）：

| 平台 | `std::stol` 行为 | 最终结果 | Kotlin `toIntOrNull` |
|---|---|---|---|
| Windows（桌面 GTest） | 32 位 long → 溢出抛异常 | "无效"（与 Kotlin 一致） | 无效 |
| Linux / 安卓 arm64（真机 .so） | 64 位 long → 解析成功 | `static_cast<int32_t>` 截断回卷（C++20 语义确定） | **无效** → 两端不一致 |

要点：

1. **这是 `settle_util::toIntOrNull` 的既有行为**，`parseNumericId` 刻意保持
   一致（两端一致性守卫的口径基准就是它），不是本批新引入的分叉。
2. **实际影响评估：当前不可达**。弟子 id 为自增小整数（Kotlin 侧生成口径），
   存量与可预见增量都远小于 2^31；没有任何路径会产生超界 id 串。
3. **若需根治**（登记备查，不建议立即做）：在两处解析中把 `stol` 结果先做
   `long → int32` 范围检查、超界返回"无效"即可与 Kotlin 对齐；属**行为变更**
   （Linux/真机臂语义收口），须按对拍纪律单独走批，并在 arm64 对拍 CI
   （R0.2 建立的 Test Lab 通道）配好后补超界样本用例。
   **触发条件**：id 生成机制变更为可能超 int32 的口径，或 arm64 对拍 CI
   纳入超界样本之前——二者满足其一时再处理。

---

## 发现 4 —— 范围外观察两处（4b 为对施工报告口径的更正）

### 4a 战斗组装域保留 map 版 `finalStats`（语义必须，非漏改）

**现象**：任务完成结算（`mission_completion.h` 的 `discipleToCombatant` /
`createBeastBattle`）与宗门防御战（`sect_defense_battle.h`）、AI 宗门模拟
（`ai_sect_ops.h`）组装战斗体时，仍走传参映射版
`stats::finalStats(d, equipmentMap, manualMap, ...)`。

**不动它们的两个理由**：

1. **不在本批热路径**。本批改造对象是"每旬结算"——全宗门弟子每旬都要走的
   高频链路；上述三处是"打一场仗 / 完成一个任务才执行一次"的低频域。
2. **输入形态不兼容，语义上必须保留**。桶式存储（`instance_buckets.h`）的
   寻址键是"弟子在宗门弟子表（DiscipleStore）中的行号"，且指向宗门装备/功法
   实例表（`state.equipmentInstances`/`state.manualInstances`）；而战斗组装的
   输入常是**临时副本**——典型如 AI 宗门弟子的装备是现场模拟生成、不存在宗门
   实例表中（`ai_sect_ops.h` 自建 per-disciple 模拟装备映射），根本没有行号
   可寻。硬套桶结构等于要求"临时道具必须先进宗门库存表才能参战"，属语义变更，
   违反本批"只改形状不改结果"红线。

**结论**：`finalStats` 的映射签名是该域的正确形态，保留；本批删除的是
`getMaxHpMp` / `calculateCultivationPerPhaseColumn` 的映射版（全部调用方
已确认可迁移完毕），与 `finalStats` 无关。

### 4b `applyEquipmentUpdates` 复核更正：单遍 O(E)，无性能问题（更正施工报告）

**更正**：B02 完成报告原文写"applyEquipmentUpdates 提交仍逐 update 全表扫描
（每旬一次，低频）"——**该描述不准确，特此更正**。实测实现
（`phase_settlement.h:532`）：

```cpp
inline void applyEquipmentUpdates(
        GameState& state, const std::map<std::string, EquipmentInstance>& updates) {
    if (updates.empty()) return;
    for (auto& eq : state.equipmentInstances) {      // 单遍历装备表
        const auto it = updates.find(eq.id);          // 每实例查一次更新映射
        if (it != updates.end()) eq = it->second;
    }
}
```

即**遍历装备表一次**（O(E)，每实例一次 map 查找），不是"每条更新扫全表"
（O(U×E)）。每旬结算仅执行一次、E 为装备实例量级，**当前实现已经合理，
无优化必要**。原文将"以表为主循环、按实例查更新"误述为"以更新为主循环、
逐条扫表"，方向说反了。此条登记以更正为准，不产生任何待办。

---

## 发现 5（2026-09-20 增补，来源批次 B10）——`generateFootprintHeader` 生成管道消费位退役（纯死管道，可清理）

### 现象与事实链

B10（R3.2）把 C++ 侧建筑占地表的来源从 `footprint_table.h` 换轨为
`scene_uv_tables.h` 后，`generateFootprintHeader` 生成管道的**生产消费位已消失**，
但管道本身仍在每次构建中运行。逐环节事实（file:line 实测于 2026-09-20）：

1. **任务本体**：`android/app/build.gradle:512-576` `generateFootprintHeader`——
   用 Groovy 正则解析**生成版** `SpriteAtlasDef.kt`（LAYOUT → build-atlas.mjs 生成
   的 Kotlin 常量），提取 `FOOTPRINT_BY_NAME_INDEX` 产出
   `app/build/generated/sprite/footprint_table.h`（`FP_W[]`/`FP_H[]` 两张整型表）。
2. **接线**：`android/app/build.gradle:655` 挂在 `preBuild` 的 `dependsOn` 链上——
   **每次构建都跑**（Groovy 解析开销毫秒级，无构建时长痛点）。
3. **生产消费位（已退役）**：唯一 include 方 `NativeBridge.cpp` 的
   `#include "footprint_table.h"` 已在 B10（`242440778`）删除，建筑占地查找改消费
   `scene/scene_uv_tables.h` 的 `kFootprintW/kFootprintH`（含固定结构
   `kStructureFpW/kStructureFpH`）——由 build-atlas.mjs `generateSceneUvTablesH`
   从 LAYOUT **直接**生成（一跳），替代原"二跳"链（LAYOUT → SpriteAtlasDef.kt →
   Groovy 解析 → C++ 头）。B10 后全仓 `footprint_table.h` 引用仅剩：
   NativeBridge.cpp 的一句"消费位接替"注释、scene_uv_tables.h 生成头内的
   "同源"注释、以及下述测试。
4. **测试消费位（仍在，守卫职责已被接替）**：
   `app/src/test/.../FootprintTableSyncTest.kt` 三个用例中，**用例 1**
   （`FP_W FP_H 与 FOOTPRINT_BY_NAME_INDEX 逐项一致`，解析 build 产物做
   "生成器幂等 + C++ 消费表同步"守卫）的 C++ 消费侧职责已由 B10 新守卫
   `SceneUvTablesMirrorGuardTest.footprint tables mirror SpriteAtlasDef`
   （`:core:engine`，`cd5df439b` 随批落库）**逐项接替**——后者直接锁
   `scene_uv_tables.h` 的 `kFootprintW/kFootprintH` ↔ `SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX`
   （这是生产代码实际消费的表）。用例 2/3（`BUILDING_NAMES` 数量一致 /
   `BuildingFeatureRegistry` 占地逐项一致）守的是 **Kotlin 侧两表一致性**，
   与 footprint_table.h 无关，须保留。
5. **文案级引用（无结构依赖）**：`BuildingSpriteFootprintJsonGuardTest.kt:86`
   的失败消息提及"重新生成 footprint_table.h"，仅措辞。

### 风险评估：当前无风险，清理动机是管线简化

- 产物无人消费 = 死管道，**不影响正确性**（多生成一个无人读的头文件）；
- 运行成本毫秒级，**不影响构建时长**；
- 真正的动机：少维护一条"Groovy 正则解析生成版 Kotlin 文件"的脆弱管线
  （其行级锚定解析对 SpriteAtlasDef.kt 格式变化敏感），并让占地表的
  C++ 来源收敛到单一生成器（build-atlas.mjs）单跳产出。

### 清理范围清单（执行时按单处理）

1. 删 `generateFootprintHeader` 任务（`app/build.gradle:512-576`）+
   `preBuild` dependsOn 链中的 `'generateFootprintHeader'` 条目（:655 附近）；
2. `FootprintTableSyncTest` 用例 1 退役（删或改注"守卫职责已迁
   `SceneUvTablesMirrorGuardTest`"）；用例 2/3 保留；
3. `BuildingSpriteFootprintJsonGuardTest.kt:86` 失败消息措辞更新（去掉
   "/ footprint_table.h"）;
4. `build-atlas.mjs` `generateSceneUvTablesH` 模板注释与
   `scene_uv_tables.h`（生成物）中"footprint_table.h 同源"的历史指认措辞
   择机改为"消费位接替记录"（纯注释，随生成器自然刷新）；
5. 基线联动：`:app` JUnit 计数 1004 → 1003（用例 1 退役），登记于当批完成报告。

### 处置建议

**无需立即行动**（当前零风险、零可观测成本）。建议归属：**R3 回滚臂删除批**
（drawAllTiles + `sceneStoreRender=false` 旧臂一起退役的批次）顺带处理——该批
本就要跑组合门与基线对账，顺手收口可摊薄验证成本；若回滚臂批次推迟，可作
独立小清理批执行。执行门槛：无前置条件（守卫职责迁移已在 B10 完成），
仅需按上面清单逐项处理并跑 `:app` 测试 + 组合门。

---

## 发现 6（2026-09-20 增补，来源批次 B06）——JNI 面"计数不增"缺自动门禁（架构级护栏缺失，非本批引入）

### 现象与事实链

方案 §"CI 与度量执法"第 3 条要求「JNI 面计数不增（脚本比对 external fun 总数）」，
但该门禁**自 R0.2 起只有登记、从未建设**。逐环节事实（实测于 2026-09-20）：

1. **要求已白纸黑字**：`docs/native-engine-refactor-plan-2026-09-17.md` §"CI 与度量
   执法"第 3 条列「静态门禁：JNI 面计数不增（脚本比对 external fun 总数）」。
2. **前例已登记、未落闸**：§7.1「登记的后续衔接项」明载——「'CI 与度量执法'：JNI 面
   计数不增静态门禁尚未建设（R0.2 探针 +1 已在 CHANGELOG 登记豁免理由）」，即
   `nativeFpDeterminismProbe` 当时 +1 只做了人工豁免登记，没有拦截工具。
3. **B06 再次 +1**：为把 C++ 变更集导出分发切到 protobuf，新增
   `external fun nativeSetDirtyExportProtobuf(Boolean)`（`GameCoreBridge.kt`）+ C++
   同名 setter（`GameCoreBridge.cpp`）。**无自动门禁 ⇒ 此次增长仅靠 CHANGELOG/§7.2
   人工登记被记录，不会被任何工具拦截**。
4. **为何没走零新增路子**：评估过用既有 `nativeExecute` ActionId 通道承载此开关
   （真·零新增 external fun），但 `action_ids.h`/`ActionIds.kt` 是 198 个**业务玩法
   操作**的 codegen 清单，把"传输编码格式"这种引擎控制态塞进业务操作码表属语义误用、
   且要改 codegen 反而面更大。最终选与既有 `nativeSetAiThermalBatchSize` 同族的
   "引擎线程控制 setter"，代价是 1 个受控豁免（已在 §7.2/CHANGELOG 写明理由）。

### 风险评估：单批无害，累积回弹是真风险（架构级）

- 本批增量合规（已豁免登记），**当前零生产影响**；
- 结构性风险：护栏缺失 → **每批 +1、无人察觉地累积**，最终把 G3/G4（稳态每帧 JNI
  次数）的病根重新养回来——与整份重构方案收敛 JNI 的方向背道而驰；
- 属"度量执法缺位"，非某批的代码缺陷，故登记为架构级观察项。

### 处置建议

**无需 B06 内立即行动**（本批已按 R0.2 先例登记豁免）。根治建议：

- 在 **B17（CI 与度量执法批）** 落 `external fun` 计数基线脚本——比对当前总数与仓库
  基线数字，**增长即 fail**，除非 PR 显式改基线 + 附豁免理由。让后续每批 JNI 变更有
  硬闸，替代人评审。
- 归属倾向：并入 B17 既有的「平台纯度 gate（grep `#include <android`）+ bench 门禁」
  一起做（同属度量执法），不单独立项，摊薄验证成本。
- 执行门槛：无前置条件；B17 开工即纳入其交付面。

---

## 发现 7（2026-09-20 增补，来源批次 B06）——proto3 空集合与"缺省键"线路不可区分（R2.3 前瞻语义纪律；本批域等价无损）

### 现象与事实链

弟子行（`DiscipleRow`）里的**空数组/空映射字段**（如 `physiqueIds:[]`、空
`manualMasteries`/`statusData`），经 protobuf 传输后**键会被整个省略**。逐环节事实：

1. **线路根因**：protobuf 的 repeated/map 无"空集合"编码位——长度为 0 的 repeated
   字段与"该字段完全不出现"在 wire format 上字节相同、无法区分（proto3 语义特性，
   非实现 bug）。
2. **编码器侧**：`gameview_encode.cpp` 逐键遍历，空数组循环 0 次 ⇒ 不产出该字段的任何
   entry；`removed`/`upserts` 同理。
3. **解码器侧**：`GameViewMirrorCodec.kt` 以 `hasXxx()` / `count==0` 判定 presence，
   不重建空集合键 ⇒ 还原的变更集树里该弟子行**没有** `physiqueIds` 键。
4. **域层面被默认值补回**：`Disciple` 各集合字段 `@Serializable` 默认值即
   `emptyList()`/`emptyMap()` ⇒ 无论"键缺失"还是"键为 `[]`"，`Disciple.serializer()`
   解码都得同一个空集合域值。**R2.2 传输换轨因此逐值等价、零生产影响**——
   `DiffDirtyEnvelopeEquivalenceTest` 对"空容器 vs 缺省键"做了归一化 deep-equal，
   实跑 0 失败已锁定这一点。

### 风险评估：B06 无损，风险前移到 R2.3

- **当前（R2.2）**：仅换传输编码、走同一 JSON applier、域默认补位 ⇒ **等价无损**，
  无待办；
- **前瞻（R2.3）**：UI 消费面从"整块 JSON 反序列化"迁到"直接读 typed proto 字段"时，
  若有人拿"字段 present 与否"当**业务判据**（例如"present=有变化 / absent=无变化"），
  就会把"集合被清空"误读成"该字段未携带"——语义分叉。

### 处置建议

**无需 B06 内行动**（等价已由守卫锁定）。纪律前置建议：

- **R2.3 开工时**，在 `game_view.proto` 文件头的「字段只增不改」演进纪律里补一条：
  *集合字段（repeated/map）的 present 不得承载业务语义，"空集合"与"缺省键"一律
  回落到域模型默认值判断*；并在 `GameViewMirrorCodec` / 未来 `GameViewStore` 注释
  钉死，等价性守卫继续跑。
- 触发条件：R2.3 第一/二波消费面开始直读 typed proto 字段之前提入演进纪律清单，
  避免各消费点各自发明 present 语义。
- 现状不动代码（无生产影响、避免过度改动），仅登记为 R2.3 使用约束。

---

## 发现 8（2026-09-20 增补，来源批次 B07）——mirror 段双层 JSON 尾巴：每旬全量 gameData JSON 往返 + GameView 信封内嵌 JSON 原文（B08/B09 后**半清偿**）

### 现象与事实链

B07 审计 `StateSyncService → GameStateStore` 时，实测到"传输已二进制、消费侧仍两次 JSON"
的双层形状（当时 R2.3 第一波红线内**有意不动**，登记为"诚实边界"）：

1. **applier 层**：`StateSyncService.mergeGameDataChanges` 为改 3~5 个标量，每旬把整份
   `GameData`（137 个序列化字段，含 `recruitList` / `worldMapSects` / `gameEventRecords`
   等巨型容器）做 `encodeToJsonElement → 覆写变更键 → decodeFromJsonElement` 各一次
   ——即"每旬级全量重建"，与传输编码无关，换 protobuf 消不掉它；
2. **信封层**：GameView 的扩展区四个载荷字段是"二进制外壳 + JSON 原文"——
   `game_view.proto:265 upsertsJson`（非弟子实体集合）、`:277 valueJson`（未 typed 的
   gameData 字段）、`:191 storageBagItemsJson`（弟子储物袋）、`:299 detailJson`（事件流载荷）；
   解码集中在 `GameViewMirrorCodec`（`:144` / `:112` / `:337` BYTES_JSON / `:200-207`）
   各 `parseToJsonElement` 一次。

因果链：R2.1 把这些字段定为 v1 过渡编码（typed 化时按「只增不改」追加新字段号）→ R2.2
只换传输 → R2.3 第一波红线"镜像仍全量、UI 零变更" ⇒ 中间态是设计结果，不是缺陷；
但 **G2「每旬镜像 < 10ms@5000 弟子」不可能在只换传输的前提下达成**——这条判断是
B07 报告拒绝用它的 2.6ms/旬 小态冒充 G2 达成的依据。

### 现状复核（2026-09-20，B08/B09 之后）

| 层 | 状态 | 证据 |
|---|---|---|
| applier 全量往返 | **已清偿**（B08） | `StateSyncService.kt:481-490` 生产走 `GameViewProjection` 旗标下的 `GameDataFieldPatch.apply`（一次浅拷贝 + 变更字段逐个解码，成本与"本封变了什么"成比例）；旧全量往返降为回滚臂（`:491-515`，`NativeEngineFlag.gameViewProjection` 默认 true，`:80`） |
| 信封内嵌 JSON 原文 | **仍开放** | 四处字段与 codec 解析点逐一在位（上表行号），B09 只是把 `detailJson` 的解析收敛到 codec 一处（族内口径一致），未 typed 化 |

G2 权威口径以方案 §7.2 B08/B09 行为准，测量单点即 B08 新增的
**`MirrorSegmentProjectionBenchTest`**（Robolectric，同一封"每旬全脏"信封两臂分阶段计时）：
D=5000（信封 2,177,797B）旧臂 **343.13ms**（decode 65.49 + apply 277.64）→ 投影臂
**132.68ms**（27.87 + 104.80）＝ **−61%**；B09 列级臂再把 decode 段打到 **1.37ms（−95%）**，
但 mirror 合计仍 **139.30ms**，**`<10ms` 未达、WS-1 保持悬置**。方案已列的残余成本中心
①`upsertMirrorRow` 全组列写未随列级收窄、②store 侧 O(D) `assembleAll`、③C++ rest 域
每封序列化、④弟子列表块未迁投影——**其中 ③④ 与本条"信封内嵌 JSON 原文 + 未 typed 化"
同源**，处置时并入同一批避免两次触碰 codec（本条不重复立项，只补 B07 侧的字节/耗时观测）。

### 风险评估

低。真正要注意的是"每一次 typed 化都要过逐值等价守卫"，并遵守发现 7 的
proto3 present 语义纪律（集合 present 不作业务判据）；长期不做，则扩展区永远留一次
JSON parse，但它当前不在预算瓶颈上（瓶颈已外移到渲染链）。

### 处置建议

**不单独立批**。触发条件三选一即启动：① 某集合/字段经实测成为 mirror 段主要占比；
② G2 收口需要再降 decode 成本；③ **R2 灰度期满删回滚臂批**（届时 `upsertsJson` 族与
`gameDataChange` 回滚臂一并处理，避免两次触碰同一 codec 函数）。做法固定：
proto 追加 typed 字段（新字段号）→ C++ 编码器与 codec 单点切换 → 等价守卫对照
（`DiffDirtyEnvelopeEquivalenceTest` / `MirrorProtoFeedEquivalenceTest` 家族）→
桌面对拍桥重建。

---

## 发现 9（2026-09-20 增补，来源批次 B07）——protobuf 首封一次性初始化 ~194ms（冷启动成本 + mirror 观测口径污染风险）

### 现象与事实链

B07 的 e2e 守卫（`DiffMirrorArmConvergenceTest`，12 旬真实 C++ 结算）逐旬计时打印：
**首封 194.017ms，其后 11 旬稳态中位 2.611ms（差约 74 倍）**，兜底臂稳态 3.172ms/旬。

根因链：protobuf-javalite 生成消息类首次使用 = 类装载 + 字段表/`Oneof`/descriptor 初始化
+ 未经 JIT 的解码路径整体走一遍；桌面 JUnit JVM 无预热，这笔一次性成本全额显形在
"第一封信封的 mirror 段"上。生产侧对应落点是 **native 初始化后的第一个镜像旬**
（AUTHORITATIVE 启动序列本就在此做 `importToNative` + 首轮镜像），故玩家感知被启动
耗时吸收。

### 现状复核（2026-09-20）

主源**仍无任何 protobuf 预热**：`grep 预热|prewarm|warm --include=*.kt core/engine/src/main`
只命中渲染器的 `prewarmDevice`/`VulkanPrewarmState`（与本条无关）。而 B08/B09 之后
mirror 段已经是**被计量、被报道**的面（`MirrorSegmentProjectionBenchTest` 分阶段
decode/apply 计时 + PhaseSegmentTimer mirror 段 + G2 趋势登记），本条尖刺正好处在
这套口径的入口第一帧上。

### 风险评估

非缺陷、零正确性影响。风险全部在**误判成本**上：一个 0.2s 的单帧尖刺出现在
mirror 段监控或 CI 波动里，形态与真回归一致——"区分抖动与回归"在本项目已是反复发生的
排查动作（B03 两轮偶发后单类重跑放行并写入 CHANGELOG；B10 首轮组合门唯一失败即该类、
按 B03 前例重跑 + 第二看护轮独立复核；B07 看护轮亦曾就 engine XML 进度疑滞做过一次误判
复核），每多一类可预防的尖刺就多一轮重跑。属"低成本可预防的观测噪声"。

### 处置建议

**不单开批次**（为 0.2s 改启动序列不划算）。两个做法二选一，随下一个"mirror 打点 /
启动预算"批次顺带做，成本各约 3 行：

1. **预热**：native 初始化完成后主动解一次空 GameView 信封（version=0、空树）——与
   `applyEnvelope` 的空变更集零写入快速路径同语义，不触碰状态、不推进版本；
2. **口径隔离**：打点与 bench 显式区分 cold/warm（首封单列，不进稳态中位与预算断言）——
   B07 守卫内部已按"稳态段中位数"取证（`steadyMedian` 弃首封），可直接沿用该口径。

触发条件：mirror 段出现单帧尖刺告警、或做启动 P0 预算拆解时。

---

## 发现 10（2026-09-20 增补，来源批次 B07）——镜像应用面对"不在 GameData 序列化面"的字段名宽松忽略 ⇒ 契约测试可"绿着空转"

### 现象与事实链

B07 写全链路守卫时，载荷字段先照抄了 B06 夹具里的 `gameData.disabledPolicies`，
编译器立刻报 `Unresolved reference 'disabledPolicies'`——**`GameData` 根本没有这个字段**。

根因链：增量应用器对不认识的 gameData 键**宽松忽略**（前向兼容设计：C++ 可先上新字段、
Kotlin 后跟，镜像不能因此崩）——旧路径靠 `Json { ignoreUnknownKeys = true }`，B08 新路径
在 `GameDataFieldPatch.kt:76-78` 显式保持"未知键宽松忽略、不中断其余字段"的同语义。
后果：**任何以不存在的字段名写出的断言，两臂都会被静默丢弃而"彼此相等"，测试全绿却不
校验任何东西**（假绿，不是假失败——比红更贵）。

### 对 B06 的澄清（防误读为缺陷）

B06 在**编码面**用例里用这个键是**有效的**：那一层验的是"任意 JSON 载荷能原样过
protobuf 信封再回来"，字段是否真属于 `GameData` 与该校验无关。失配的只是
"验证 gameData 容器合并"这一层意图——B07 的 `MirrorProtoFeedEquivalenceTest` 已用真实
字段 `unlockedManuals`（`List<String>` 容器）在 store 馈送面补上，B06 无需回改。

### 现状复核（2026-09-20）

B08 已把**另一半**堵死：字段名在 `GameData` 序列化面内、但 `GameDataFieldPatch` 写入器表
缺失 ⇒ **抛错 fail-fast**（方案 §5"投影缺失字段 fail-fast 而非静默空"），并有
"写入器键集 ↔ `GameData.serializer().descriptor.elementNames` 双射"守卫锁定表完整性。
本条描述的"**完全不在序列化面**"分支仍按设计宽松——**不应改成抛错**（改了协议前向兼容就破）。
⇒ 风险面收敛为"测试夹具选错字段名"，生产无隐患。

### 风险评估

低，但隐蔽且随守卫密度上升而放大：B08/B09 之后镜像/投影/事件流各有一批等价对照测试，
任何一条的载荷字段名写错都是"绿着空转"，成本远高于一次真红。

### 处置建议

零成本纪律化，不碰生产语义：

1. 写镜像/投影类等价守卫时，gameData 载荷字段名一律取自
   **`GameDataFieldPatch.coveredFields`**（它就是权威清单），并在测试里加一条
   `assertTrue(name in GameDataFieldPatch.coveredFields)`——把"选错名"从静默忽略
   变成运行期红；触发条件：后续批次（R3/R4 消费面接入）新增同类守卫时顺带做；
2. 可选加固：把"镜像载荷测试可用字段名"收敛为单一常量源（同发现 3 的
   `ParseParityWithSettleUtilToIntOrNull` 处置思路——双份口径靠守卫单源化），
   归属 R2 回滚臂删除批顺带；
3. 不改应用器的宽松语义（前向兼容是既有契约，非本条问题）。

---

## 处置建议汇总

| # | 事项 | 是否需要行动 | 建议归属 / 触发条件 |
|---|---|---|---|
| 3 | 解析逻辑双份 | 无需立即行动（守卫测试已锁口径） | 持续由 `ParseParityWithSettleUtilToIntOrNull` 守卫；若未来两份实现任一需要修改，先过该测试 |
| 3 附注 | 64 位平台溢出语义与 Kotlin 不一致 | 否（当前不可达） | 触发条件二选一：id 生成口径可能超 int32；或 arm64 对拍 CI 配好后补超界样本——届时按对拍纪律单独走批根治（`stol` 超界 → 无效） |
| 4a | 战斗组装域 map 版 `finalStats` | 否（语义必须保留） | 无待办；若未来战斗域也要求实例寻址统一，需先解决"临时模拟装备无行号"的前置问题（属 R4 战斗域批次范畴） |
| 4b | `applyEquipmentUpdates` 扫描方式 | 否（更正：已是单遍 O(E)） | 无待办；本条仅为更正施工报告口径 |
| 5 | `generateFootprintHeader` 死管道（B10 增补） | 否（当前零风险零成本） | 无前置触发条件；建议归属 R3 回滚臂删除批顺带（或独立小清理批），按发现 5 清理范围清单逐项执行 |
| 6 | JNI 面计数不增缺自动门禁（B06 增补，架构级） | 否（本批已按 R0.2 先例登记豁免；当前零生产影响） | 归属 B17（CI 与度量执法批）：落 `external fun` 计数基线脚本，增长即 fail 除非显式改基线+附豁免理由；无前置条件，与平台纯度 gate/bench 门禁同批交付 |
| 7 | proto3 空集合与缺省键不可区分（B06 增补，R2.3 前瞻） | 否（R2.2 域等价无损，已由 `DiffDirtyEnvelopeEquivalenceTest` 锁定） | 触发条件：R2.3 消费面直读 typed proto 字段之前——在 `game_view.proto` 演进纪律补「集合字段 present 不作业务判据、空↔缺省回落域默认」，并在 `GameViewMirrorCodec`/`GameViewStore` 注释钉死 |
| 8 | mirror 段双层 JSON 尾巴（B07 增补；applier 层已由 B08 清偿、信封层仍开放） | 否（生产零影响；G2 未达已按 B08/B09 诚实登记，不重复立项） | 触发条件三选一：某字段实测成为 mirror 段主要占比 / G2 收口需再降 decode 成本 / **R2 灰度期满删回滚臂批**（`upsertsJson` 族与 gameData 回滚臂一并处理，同批吸收方案 B09 残余③④）；做法=proto 追加 typed 字段 + codec 单点切读 + 等价守卫 + 桥重建 |
| 9 | protobuf 首封 ~194ms 一次性初始化（B07 增补） | 否（零正确性影响；纯观测噪声与冷启动归属问题） | 随下一个 mirror 打点/启动预算批顺带，二选一约 3 行：native 初始化后解一次空 GameView 信封预热，或 cold/warm 分列口径（B07 守卫已按弃首封的稳态中位取证）；触发条件=mirror 段出现单帧尖刺告警或做启动 P0 拆解 |
| 10 | 未知 gameData 字段名被宽松忽略 ⇒ 等价守卫可"绿着空转"（B07 增补；B08 已堵在册漏写入器的 fail-fast 半边） | 否（生产宽松语义是既有前向兼容契约，不改） | 纪律化零成本：后续镜像/投影类守卫的载荷字段名取自 `GameDataFieldPatch.coveredFields` 并断言 ∈ 该集合（选错名即红）；可选把"可用字段名单源"并入 R2 回滚臂删除批；触发条件=R3/R4 消费面接入新增同类守卫时 |
