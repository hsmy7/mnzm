# Batch-02：detekt 拆分任务队列·feature:game ViewModel 族（8 条）

| 项 | 内容 |
|---|---|
| 批次 | 02 ｜ 并行组 A（与 batch-01/03 互不相交） |
| 模块 | `feature:game`（baseline 8 条 = 7 TooManyFunctions + 1 LargeClass） |
| 性质 | 真实结构性重构（ViewModel/delegate 拆分），**行为零变更** |
| 前置 | 无。开工前重读 [handover](../cpp-migration-handover-m0.md) §2.28/§2.29 + 本目录 [README](README.md) §3/§5 |
| 预分配 | handover 小节 §2.31；guard `feature/game=` 行（当前 8，只缩） |

## 1. 目标清单（`android/feature/game/detekt-baseline.xml` 实测）

| 文件/类 | 规则 | 函数数（§2.28 口径） | 拆分路径 |
|---|---|---|---|
| `GameViewModel.kt$GameViewModel` | TMF | **188**（最大单体） | **既有 delegate 基建扩展**——沿 DiscipleDelegate/NavigationDelegate/BuildingUpgradeDelegate 等先例按 UI 域继续外移 |
| `SaveLoadViewModel.kt$SaveLoadViewModel` | TMF + LC | 75 | 存/读/云存档/重启流程已有 SaveLoadLoadDelegate 等委托面，按流程相拆 |
| `ProductionViewModel.kt$ProductionViewModel` | TMF | 61 | 生产 UI 域（排班/槽位/灵田/配方展示） |
| `SectViewModel.kt$SectViewModel` | TMF | 48 | 宗门信息/政策/外交 UI 域 |
| `DiscipleDelegate.kt$DiscipleDelegate` | TMF | 41 | 弟子管理族聚合面——按操作族再拆（装备/功法/任命/婚姻…） |
| `NativeSurfaceView.kt$NativeSurfaceView` | TMF | 21 | 渲染宿主——图集流水线已抽 `AtlasAsyncPipeline`（§2.5 先例），本轮把 surface 生命周期/输入/渲染回调域再抽类 |
| `NavigationDelegate.kt$NavigationDelegate` | TMF | 21 | 路由/弹窗栈管理域 |

> SaveLoadViewModel 的 LC 与 TMF 双标一次拆分同时消掉两条。

## 2. 拆分模式与纪律

- **ViewModel → delegate** 是本模块既定惯用法（GameViewModel 已有 8+ delegate，§2.28
  时点 DiscipleDelegate 41/NavigationDelegate 21 即产物）：状态留 ViewModel，操作流按
  UI 域外移 delegate/文件级函数；本批对 delegate 本体超阈值的（DiscipleDelegate）继续二拆。
- **零行为变更**：delegate 外移不改 StateFlow 暴露面、不改调用时序；移动代码与源
  **逐字节 diff 校验**；同包顶层函数/文件级 private 是零调用点变化落位。
- detekt TMF **等于阈值即报**，拆分目标 = 阈值 − 1；文件级阈值 15。
- Compose 域注意（§2.28 先例）：跨文件消费的顶层声明 private→internal；共享常量表随
  消费方迁移；`MatchingDeclarationName`（新文件首声明与文件名一致）。
- **每拆完一个文件跑全规则 detekt**，次生违规（UnusedImports/LongMethod/NBD/FileLength）
  全根治；守卫测试以文件名/路径扫描的（导航/弹窗路由映射测试）随拆分同步清单。
- 测试源编译必须跑（主源不查测试源；§2.24.2 撞名无限递归事故的暴露面就在测试源）。
- GameViewModel 拆分风险最高（188 函数 + 并行批协议禁改约束）：**本批拥有该文件**，
  组 C 下沉批（06-09）不得触碰——若组 C PR 先到，rebase 时以本批拆分后结构为准重放。

## 3. 验收

1. 摘除 game baseline 全部 8 条实跑裁决 → 逐文件拆分归零（不装回；若个别回退按
   §2.29 诚实口径登记）→ 触碰面 0 违规（六模块全规则）；guard `feature/game=` 只缩。
2. §4 模板：①②④（feature:game 全量 868 用例基线 + app state/repository 定向）③⑤。
3. handover §2.31 + §4.1 勾销 + CHANGELOG。

## 4. 本批触碰文件声明

- `android/feature/game/**`（7 个目标文件 + 新 delegate/域文件 + 测试适配）。
- `android/feature/game/detekt-baseline.xml` + guard `feature/game=` 行。
- 不触碰：`core:engine`/`core:domain`/`core:data`/`app`、C++、`GameCoreBridge`。
