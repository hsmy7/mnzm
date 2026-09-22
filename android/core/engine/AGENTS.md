# AGENTS.md — 引擎层（:core:engine）

> 本文件是 `android/core/engine/` 的追加规范。**通用规范见仓库根 `AGENTS.md`，专题规则见 `rules/`。**

## 语言归属：C++ 优先

引擎核心逻辑（时间推进、结算、战斗、生产、探索、内政、弟子属性生成、经济、RNG）**一律优先 C++ 实现**
（`game-core`，零 Android 依赖），经 JNI 与 Kotlin 对接；Kotlin 侧保留转发层与平台效应。
UI / Compose 只能用 Kotlin。语言选择判定表、边界规则与审查清单见 `rules/cpp-priority.md`。

## 服务与系统

- 所有游戏领域逻辑类标注 `@GameService(name = "...")`
- Service 不订阅 `StateFlow`，通过传入参数或构造注入的 snapshot 访问状态
- 新增 `GameSystem` 必须拆为 ≤60 行/方法的子系统，禁止 God Method
- **`EventBus` 事件 emit 必须在 `stateStore.update` 事务外**（参照 `flushPendingEvents` 模式）
- 多实体变更必须在**单次** `stateStore.update {}` 事务内原子完成

## 结算层级

新逻辑必须落既有四层（L0 时间推进 / L1 每旬检查 / L2 惰性生产 / L3 月变 / L4 年变），
**禁止另起结算循环或新线程 tick**。生产系统同步点（政策/长老/速率因子/丹药）见 `rules/pr-review-checklist.md`；
新增玩法系统的完整接入清单见 `rules/expansion-playbook.md`。

## 确定性 RNG

**新增随机逻辑一律 `GameRngManager.getRng(RngPartition.xxx)`**，禁止 `kotlin.random.Random`。
分区选择与 `inSnapshot` 标记见 `docs/knowledge-base.md`「确定性 RNG 系统」。
红线由**守卫测试**看护（不是 grep——`.random()` 是 stdlib 扩展、自建 RNG 是 object，二者都不带
`import kotlin.random.Random`）：`RngSourceGuardTest` + `RngEngineIsolationGuardTest`。
治理背景与五类入口分类见 `docs/adr/rng-determinism-remediation.md`。

## 线程契约

唯一合法的状态写入口是 GameEngine-Thread。跨线程白名单、禁止区与合法通信通道见
`docs/threading-contract.md` —— **新增任何跨线程交互前先在该文件登记，再实现**。

## 与 C++ 的边界

`game-core` 是 AUTHORITATIVE 真相源，Kotlin `GameStateStore` 是**只读镜像**。
**反向同步通道已删除**：AUTHORITATIVE 稳态下 Kotlin 对 C++ 只读，唯一合法写入是
`StateSyncService.importToNative` 全量导入（读档/新档基线与 `rebaselineNativeMirror` 后重建）。
防复发守卫：`MirrorReadOnlyGuardTest`（符号面）+ `DiffAuthoritativeTickTest`（行为面）。
镜像合法内容上限与「UI 要新状态必须先扩 C++ 协议」的纪律见 `docs/ui-read-surface.md` §2。
