# Batch-01：detekt 拆分任务队列·core:engine 域管理者族（34 条）

| 项 | 内容 |
|---|---|
| 批次 | 01 ｜ 并行组 A（与 batch-02/03 互不相交） |
| 模块 | `core:engine`（baseline 34 条 = 26 TooManyFunctions + 8 LargeClass） |
| 性质 | 真实结构性重构（文件/类拆分），**行为零变更** |
| 前置 | 无。开工前重读 [handover](../cpp-migration-handover-m0.md) §2.28/§2.29（拆分纪律与回退先例）+ 本目录 [README](README.md) §3/§5 |
| 预分配 | handover 小节 §2.30；guard `core/engine=` 行（当前 34，只缩） |

## 1. 目标清单（baseline 实测，`android/core/engine/detekt-baseline.xml`）

### TooManyFunctions 26 处（函数数为 §2.28 摘族实跑口径）

| 类 | 函数数 | | 类 | 函数数 |
|---|---|---|---|---|
| InventorySystem | 105 | | HeavenlyTrialService | 28 |
| DiscipleStatCalculator | 85 | | BuildingService | 28 |
| DiscipleFacadeImpl | 68 | | DiscipleService | 25 |
| ProductionProcessor | 59 | | CaveExplorationProcessor | 25 |
| GameEngineCore | 57 | | CultivationEventProcessor | 25 |
| CultivationService | 50 | | MissionSystem | 24 |
| BattleSystem | 47 | | ExplorationService | 20 |
| AISectAttackManager | 43 | | ProductionSlotRepository | 29 |
| BuildingFacadeImpl | 43 | | RedeemCodeManager | 40 |
| MailService | 42 | | SectPolicyToggleUseCase | 40 |
| BattleCalculator | 33 | | UnifiedPerformanceMonitor | 37 |
| LawEnforcementProcessor | 33 | | BuildingConfigService | 35 |
| AISectDiscipleManager | 35 | | InventoryFacadeImpl | —（以 baseline 条目为准） |

### LargeClass 8 处

`AISectAttackManager` / `BattleSystem` / `DiscipleStatCalculator`（+Test）/ `GameEngineCore` /
`InventorySystem` / `ProductionProcessor`（+Test）

> 与 TMF 双标的类（DiscipleStatCalculator/GameEngineCore/InventorySystem/
> ProductionProcessor/AISectAttackManager/BattleSystem）一次拆分应同时消掉两条。

## 2. 拆分模式（按 §2.28/§2.29 已验证先例，优先级从上到下）

1. **文件级扩展域文件**（object/类 → 同包顶层扩展函数，调用点语法零变化）：
   `GameDataCacheManager`/`StorageEngine` 先例（§2.29）。companion 常量经
   `private val TAG = X.TAG` 文件级别名引用；状态字段 private→internal（同模块）。
   **拆分目标数 = 阈值 − 1**（detekt 函数数等于阈值即报）。
2. **纯函数下放文件级顶层**：`FavorDomain` 先例（§2.28）——同包调用语法除限定符外零变化。
3. **域 Ops 文件拆分**（GameEngine 族已做过一轮，§2.28：Coordination→8 域文件、
   BattleOps→5、InventoryOps→2 等）——本轮 GameEngineCore（57）按同法继续。
4. **数据载体/注册表移独立文件**：`ProductionStartSpec`/`PillStatLine` 先例（§2.26/§2.28）。
5. **契约面豁免（不可滥用）**：仅当类为 接口契约下界 / Room 契约 / 注册表同址内聚 /
   JNI 符号面时文件级 @Suppress 附理由（§2.28 ③ 先例 46 处的判定口径）。
   域管理者/服务类**默认不允许豁免**——本轮目标是真实拆分。

## 3. 实施纪律（防复发，全部来自已登记 findings）

- 切分必须**原始列锚定**（对 strip 后行匹配会把函数体内缩进 `val` 误判为块边界——
  §2.28 机制发现①曾致函数体截断）；object 拆分的表达式体函数按**累计括号深度**切块。
- 移动代码与源**逐字节 diff 校验**（单行表达式体是校验器盲区，人工复核）。
- import 剪枝**保留通配导入**（`import androidx.compose.runtime.*` 删之即大片 Unresolved）；
  UnusedImports 对同包导入也报（拆分产物勿加同包显式 import）。
- 文件尾换行（NewLineAtEndOfFile）；跨文件消费的顶层声明 private→internal。
- 架构守卫源路径清单会拦截移动：EngineServiceAnnotationTest / SlotCategoryCoverageTest /
  InventoryAddPathGuardTest / GameSystemRegistryCoverageTest 等以**文件路径/文件名**扫描——
  拆分后同步清单（§2.28/§2.21 先例），守卫失败先判归属再改。
- **每拆完一个类跑全规则 detekt**（次生违规：TMF 拆出文件函数数超 15 / UnusedImports /
  MatchingDeclarationName / FileLength 2000 / LongMethod 等），全根治后才算清偿。
- 嵌套类/内部状态深耦合时（如 InventorySystem 的事务缓存、GameEngineCore 的引擎
  生命周期态）先写拆分设计再动手；超出"行为零变更"边界即**诚实回退**（DiscipleTables
  先例 §2.29）——git 还原 + 条目装回 + 在 §2.30 登记原因。

## 4. 验收

1. 摘除 engine baseline 全部 34 条实跑裁决 → 逐类处置（拆分/豁免）→ **触碰面 0 违规**
   （六模块全规则重跑）；余量（若个别类回退）装回且计数只缩，guard 同步。
2. §4 模板命令：①②③（③ 对拍证明拆分零行为影响）④（engine 相关回归）⑤。
3. `:core:engine:testReleaseUnitTest` 全绿 0 skip（3118+ 用例基线，45 Diff 类逐位一致）。
4. handover §2.30 + §4.1 勾销 + CHANGELOG。

## 5. 本批触碰文件声明（并行协议）

- `android/core/engine/**`（34 条清单文件 + 新拆分域文件 + 测试适配）。
- `android/core/engine/detekt-baseline.xml` + guard `core/engine=` 行。
- 不触碰：其他模块、`GameCoreBridge`、C++、`StateSyncService`、`GameViewModel.kt`。
