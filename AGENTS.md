# AGENTS.md — 模拟宗门（mnzm）项目规范

> **本文件是唯一的规范入口，也是本仓库唯一的规范真源。**
> 专题规则在 `rules/`，架构文档在 `docs/`——**这两处不会被任何 agent 自动加载**，
> 必须按 §0 路由表在对应任务开始时主动读取。
>
> **本文件只放「每次都用得到的硬约束 + 路由表」，深度规范一律下沉到 `rules/`**：Codex CLI 的合并项目指令
> 上限是 `project_doc_max_bytes`（默认 **32768 字节**），超限即被静默截断；DSH 侧另有 65536 字节预算。
> `scripts/check-agent-instructions.mjs` 是这套架构的门禁（预算闸 / 单一真源 / 引用无死链 / 路由表完整），进 CI。
> 改动任何 `AGENTS.md` 后必跑该门禁，并遵守 `docs/AGENTS.md` 的引用路径规范。

## 0. 任务路由表（动任何东西之前先看这里）

| 你要做的事 | 必读（按顺序） |
|---|---|
| **任何代码改动之前** | §1 用户公约 + §5 编码规范（🔴）；细则、BAD/GOOD 示例与 🟡/🟢 级条目见 `rules/code-quality.md` |
| **桌面自动化 / computer use**（点击、输入、读界面、截图） | 本机用户级 `~/.dsh/AGENTS.md`（唯一真源，Windows-MCP 工具面与操作纪律；仅本机有效） |
| **提交之前** | `rules/build-quality.md` → `rules/pr-review-checklist.md`（逐条过）→ `rules/version-release.md` |
| 改任何 `@Entity`、表结构、Entity 字段 | `rules/database-migration.md`（**存档损坏头号根因，最高优先**） |
| 改 `SaveData` ProtoBuf / `@ProtoNumber` 字段 | `rules/database-migration.md` |
| 新增玩法系统（新入口/新结算/新模块） | `rules/expansion-playbook.md` → `docs/architecture.md` |
| 新增或改对话框 | `rules/new-dialog-checklist.md` + `rules/dialog-scrim-standard.md` |
| 新增**含输入框**的界面 | `rules/dialog-soft-input-guard.md`（文本→`TextInputDialog`；数字→`NumberInputPanel`） |
| 新增聊天/对话类对话框 | `rules/chat-dialog-design.md` |
| 新增/改精灵图、素材、建筑或装饰显示尺寸 | `rules/static-resources.md` |
| 找原始美术/音频素材源文件（含角色立绘、音乐） | `rules/media-source-assets.md` |
| 引擎/战斗/结算/生产/探索/内政/经济/RNG 逻辑 | `rules/cpp-priority.md` → `docs/cpp-engine.md` |
| 新增随机数逻辑 | §5 编码规范 9.5 红线 + `docs/knowledge-base.md`「确定性 RNG 系统」 |
| 新增/改跨线程交互、线程、渲染数据通道 | `docs/threading-contract.md`（**先登记再实现**） |
| UI 要读一个**新的**游戏状态字段 | `docs/ui-read-surface.md` §2（镜像合法面纪律） |
| 新增/改渲染特性（地图/Canvas/精灵/后处理） | `android/docs/renderer-feature-checklist.md`（双端同步 + 回来更新清单） |
| 改 GPU 分级、硬件加速、降级链 | `android/docs/vulkan-crash-defense-design.md` + `android/docs/render-thread-crash-strategy.md` |
| 新增货币/资源/奖励/离线收益 | `rules/economy-design.md` |
| 新增付费点位、运营活动、RemoteConfig | `rules/commercialization.md` |
| 新增/改广告点位 | `rules/ad-cooldown.md` + `docs/knowledge-base.md#免广告特权白名单` |
| 新增埋点事件 | `rules/data-analytics.md`（三处同步 + 守卫测试） |
| 新增社交/排行榜/分享 | `rules/social-system.md` |
| 改 SDK 初始化、登录、登出、防沉迷 | `rules/sdk-init-lifecycle.md` |
| 写或改单元测试、mock/stub | `rules/testing.md` |
| 写或改代码注释、KDoc | `rules/code-comment.md`（七项检查清单） |
| 写设计方案 | `rules/design-plan-review.md`（原则 + 自检清单） |
| 做行业对标调研 | `rules/industry-benchmark.md` |
| 新增 Gradle 模块、重大架构决策 | `docs/architecture.md` + `CODE_WIKI.md` + `docs/adr/` |
| 接手 C++ 迁移 | `docs/cpp-migration-handover-m0.md` §4/§5/§6 |
| 查某个类或子系统当前怎么实现 | `docs/knowledge-base.md` |
| 新增平台能力（时间/存储/网络/加密/通知/支付/广告/分享） | `rules/code-quality.md` §1.5（iOS 对等实现） |
| 排查 `libhwui.so` / RenderThread 崩溃 | `android/docs/render-thread-crash-strategy.md` |
| iOS 立项相关 | `docs/adr/ios-migration-plan.md` |

**模块级规范**（进入该目录工作时读，DSH 会在读到该目录文件时自动注入）：

| 目录 | 文件 | 覆盖 |
|---|---|---|
| `android/` | [`android/AGENTS.md`](android/AGENTS.md) | Android 工程：构建命令、模块边界、Gradle 约定 |
| `android/core/engine/` | [`android/core/engine/AGENTS.md`](android/core/engine/AGENTS.md) | 引擎层：C++ 优先、RNG 分区、线程契约、坐标与结算 |
| `android/core/data/` | [`android/core/data/AGENTS.md`](android/core/data/AGENTS.md) | 数据层：Room/Migration、ProtoBuf、存档与云存档 |
| `android/feature/game/` | [`android/feature/game/AGENTS.md`](android/feature/game/AGENTS.md) | UI 层：Compose、ViewModel、对话框、精灵图 |
| `android/app/src/main/cpp/gamecore/` | [`android/app/src/main/cpp/gamecore/AGENTS.md`](android/app/src/main/cpp/gamecore/AGENTS.md) | C++ 引擎核心：确定性保真、ECS、语言约束 |
| `docs/` | [`docs/AGENTS.md`](docs/AGENTS.md) | 文档维护：ADR 流程、文档同步义务 |

---

## 1. 用户公约（产品经理思维）

本用户不懂技术，需求描述未必清晰、未必使用专业术语。

### AI 行为规范
1. **先确需再执行** — 收到需求后先用业务语言复述确认理解，再自行翻译为技术方案；不确定时立即提问，不猜测需求，不让用户解释技术细节
2. **提问要精准** — 简洁、直接、给出选项，不要让用户解释技术细节
3. **因果链确凿** — 定位问题时，必须从症状追溯到根因，每一步因果关系都要能说清楚。禁止仅凭相关性就下结论，必须有直接证据链
4. **举一反三排查** — 定位到问题后、动手修复前，先搜索代码库中是否存在同类模式的其他问题，一并纳入修复方案，再统一实施
5. **默认使用中文** — 所有回复、注释、commit message、文档均使用中文，除非涉及代码标识符或技术术语无合适翻译
6. **不知疲倦的执行** — AI 是不知疲倦的工作机器，不得以"资源消耗"、"token消耗"等任何理由拒绝或缩减任务范围。用户需求就是指令，全力以赴执行到底
7. **零成本考量** — 设计方案及实施均禁止考虑各项成本（时间成本、人力成本、计算成本等），只需给出最优方案并完整实施。若因客观限制无法全量采纳，逐条说明原因和替代方案
8. **禁止途中停下询问** — 禁止在实施过程中停下询问"是否需要继续"、"是否需要执行"等——用户已经发出的指令就是最终指令，除非遇到确实无法自动决策的封锁性问题
9. **禁止偷工减料** — 实施方案必须严格按计划完成所有条目，不允许留"后续优化"、"视情况而定"等尾巴。方案是什么结局就是什么，不欠技术债
10. **诚实报告进度** — 必须如实报告已完成和未完成的工作，禁止隐藏未完成的尾巴欺骗用户"已完成"。用户清楚真实进度才能正确决策
11. **退一步看全局** — 定位问题后出方案时，必须退一步判断是简单问题还是架构级问题，并在方案末尾明确告知用户。若是架构级问题，给出两个选项：(1) 只修眼前问题不碰架构，(2) 彻底重构根除。若用户选(2)，按设计方案规则出重构级根治方案
12. **报告途中发现** — 任务完成后必须明确向用户报告中途发现的预存问题、无用代码、可优化的代码等可改进项，不自作主张隐藏
13. **清理一次性代码** — 任务完成后必须直接清理为完成任务而创建的临时测试代码、调试代码等一次性代码，不遗留垃圾
14. **任务完成后才提交** — 禁止在任务中途提交代码。所有改动（修复代码、测试、临时诊断日志等）在任务全部完成、清理完一次性代码后，一次性提交
15. **根因修复** — 修复 bug 必须做根因修复：从症状沿因果链追溯到根因，用正确的逻辑覆盖错误，禁止打补丁式绕过（特判分支、屏蔽症状、掩盖错误的 workaround 等均属打补丁）。修复后必须验证根因路径已被正确逻辑替代、症状不再复现，并在提交说明中写明根因
16. **C++ 优先** — 项目整体技术方向为 C++（总方案见 `docs/adr/cpp-engine-migration.md`）：所有新增/修改的引擎、战斗、结算、生产、探索、内政等核心逻辑代码，以及涉及这些逻辑的设计方案，一律优先采用 C++ 实现（经 JNI 与 Kotlin 对接；UI 层 Compose 只能用 Kotlin，保持不变）。禁止新增与 C++ 迁移方向相悖的纯 Kotlin 引擎逻辑；确因紧急无法立即 C++ 化的，必须登记并尽快下沉。详见 `rules/cpp-priority.md`
17. **桌面操作铁律** — 桌面自动化由本机 **Windows-MCP**（工具名 `mcp__windows__*`）提供。操作优先级（语义快照 → 键盘 → 像素）、`label` 时效性、错误处理与回读验证纪律**以本机用户级 `~/.dsh/AGENTS.md` 为唯一真源**（桌面自动化属本机能力，本仓库不维护第二份）

---

## 2. 工程速查（Build / Test / Lint）

命令在 `android/` 下用 Gradle wrapper 执行；**测试一律加 `--max-workers=1`**（并行会因共享静态状态跨类污染）：

```bash
cd android && ./gradlew.bat compileReleaseKotlin              # 编译检查（每次改动后）
cd android && ./gradlew.bat testReleaseUnitTest --max-workers=1
cd android && ./gradlew.bat lintRelease detekt
```

单类测试写法、APK、Kover、CI 一行流、clean 见 `rules/build-quality.md`；测试与 mock/stub 约定见 `rules/testing.md`。
**规范分发门禁**（改本文件 / `rules/` / `docs/` / 任何 `AGENTS.md` 后必跑）：`node scripts/check-agent-instructions.mjs`

---

## 3. 项目定位与架构入口

**项目**：修仙宗门模拟经营手游（Android，包名 `com.xianxia.sect`）。技术栈：Kotlin 2.2.20（UI/平台层）
+ **C++20 引擎核心 `game-core`**（零 Android 依赖、桌面可编译）+ JNI / nlohmann::json；Compose、Hilt、Room、MMKV、kotlinx.serialization ProtoBuf。

架构见 [`docs/architecture.md`](docs/architecture.md)、代码级 Wiki 见 [`CODE_WIKI.md`](CODE_WIKI.md)、子系统实现见 [`docs/knowledge-base.md`](docs/knowledge-base.md)。**动这些子系统之前先读对应文档**；以下五条是硬不变式：

- **C++ 是 AUTHORITATIVE 真相源** — `game-core` 承载模拟逻辑，Kotlin `GameStateStore` 是**只读镜像**；稳态下 Kotlin 对 C++ 只读，唯一合法写入是 `StateSyncService.importToNative` 全量导入。防复发守卫：`MirrorReadOnlyGuardTest`（符号面）+ `DiffAuthoritativeTickTest`（行为面）。总方案 `docs/adr/cpp-engine-migration.md`；进度与镜像合法面见 `docs/cpp-engine.md` / `docs/ui-read-surface.md` §2
- **实时结算四层** — L0 时间推进 / L1 连续积分 + 判定窗口 / L2 惰性差分 / L3+L4 月年边界事件派发；**新逻辑必须落既有层级，禁另起结算循环或新线程 tick**；现实时长换算一律走 `GameConfig.Time` 常量栈
- **线程契约** — 唯一合法状态写入口是 GameEngine-Thread；白名单与禁止区见 [`docs/threading-contract.md`](docs/threading-contract.md)（新增跨线程交互须先登记再实现）
- **存档入口纪律** — 手动存档（5 槽位）+ 云存档 + **现实墙钟节拍自动存档**（每 10 现实秒至多一次、三前置门控：旗标/有效槽位/引擎已加载；命名统一 `realtimeAutoSave*`）；**禁止复活旧月变触发式 `AutoSaveTrigger`**；`SaveValidator` 规则按 `order` 排序，`registerDefaults()` 加一行即注册
- **扩展性预留与 R 系列债** — 见 `docs/architecture.md`；离线收益口径见 `rules/economy-design.md` §4

模块源码路径：`:app`（应用壳 + JNI 桥）、`:core:domain`（数据类/接口/sealed/Registry）、`:core:data`（Room/序列化/Repository）、
`:core:engine`（GameEngine/Service/System/游戏循环）、`:core:ui`（共享 Compose 组件）、`:feature:game`（ViewModel/Screen/对话框）。

---

## 4. ViewModel / UseCase 约定

- ViewModel 继承 `BaseViewModel`（提供 `showError()` / `showSuccess()` / `showInfo()` / `withLoading()`）。
- 每个功能一个 ViewModel（`AlchemyViewModel`、`ForgeViewModel`、`ProductionViewModel`、`DiscipleViewModel` 等）。
- ViewModel 从 `GameStateStore.unifiedState` 读取（`collectAsState()` 或快照用的 `.value` 直读）。
- 状态变更一律走 `GameEngine` 方法，**UI 层永不直接写 `GameStateStore`**。

### UseCase / Facade 模式

业务逻辑组织为 **UseCase** 类（`app/.../core/usecase/`），包裹 **Facade** 接口（`core/engine/domain/`）。约定：

- 单一 `operator fun invoke()` 作为入口（多操作场景用命名方法）
- 异步用 `suspend`，响应式流用 `StateFlow`
- 错误处理用 `Result<T>` 或 `DomainResult<T>`——**永不抛裸异常**

```
ViewModel → UseCase → Facade (interface) → Service (impl) → GameStateStore
```

大型 ViewModel（如 `GameViewModel`）可按领域拆为 **Delegate** 类（`ui/game/delegate/`）：
`BuildingDelegate`、`DisciplineDelegate`、`BeastAttackDelegate` 等。

---

## 5. 编码规范 (Coding Standards)

> 严重度：🔴 必须遵守（违反导致构建/审查失败）、🟡 应遵守（违反需在审查中说明理由）、🟢 建议（推荐遵循）。
> 提交前审查清单见 `rules/pr-review-checklist.md`；质量细则、**BAD/GOOD 示例**与 **🟡/🟢 级条目（§1.7）**见 `rules/code-quality.md`。**编号缺号 = 该条细则在 `rules/code-quality.md`。**

### 0. 代码质量铁律

**0.1 🔴 代码必须有测试覆盖** — 所有新增/修改的业务逻辑代码必须有对应单元测试。无测试视为未完成，不得合并。需覆盖正常路径、边界条件（空值/空列表/极值）、异常路径。

**0.2 🔴 代码必须可长期维护** — 命名清晰、意图明确、不需要注释就能理解；函数短小聚焦、单一职责；避免过度耦合，依赖注入优于硬编码；不引入隐性技术债。

**0.3 🔴 禁止"当前能跑就行"心态** — 只处理正常路径、日志不足、硬编码、重复代码、明知有更好实现却选更差的，直接打回。

**0.4 🔴 禁止硬编码数字（魔法数字）** — 有业务含义的数字必须定义为命名常量（`const val` 或 `companion object` 的 `val`）。例外：0、1、-1 等自解释的循环/索引/增量、detekt 配置中声明的游戏数学常量。

### 1. Kotlin 语言规范

**1.1 🔴 禁止 `!!` 操作符** — 除非有编译时证明（如 `lateinit var` 初始化后访问）。用 `?.` / `?:` / `checkNotNull()` 安全访问。

**1.3 🔴 领域结果用 sealed class** — 可预期的业务失败（找不到、校验失败）用 sealed class，不抛异常；异常仅用于程序错误和基础设施故障。禁止裸 `Boolean` 代表成功/失败。

**1.4 🔴 协程规范** — 禁止 `runBlocking`（测试用 `runTest`）；Dispatcher 通过 Hilt `@Dispatcher(IO)` 注入，禁止硬编码 `Dispatchers.IO`。

### 2. 模块架构规范

**2.1 🔴 依赖方向不可反转** — `:core:domain` ← `:core:data` / `:core:engine` / `:core:ui` ← `:feature:game` ← `:app`。`:core:domain` 零 Android 依赖（仅 `javax.inject` + `kotlinx.coroutines` + `kotlinx.serialization` + `room-common` 注解）。

**2.2 🔴 模块内容边界：**

| 模块 | 只能包含 | 禁止包含 |
|------|---------|---------|
| `:core:domain` | 数据类、接口、sealed class、注解、StateFlow 定义、Registry 静态数据 | Room DAO、Android Context、ViewModel、Compose |
| `:core:engine` | GameEngine、Service、System、游戏循环 | Compose UI、ViewModel、Activity |
| `:core:data` | Room DB/DAO/Migration、序列化、加密、Repository 实现 | ViewModel、Compose、游戏逻辑 |
| `:core:ui` | 共享 Compose 组件、Theme、导航工具 | ViewModel、Room DAO、游戏逻辑 |
| `:feature:game` | ViewModel、Screen 级 Compose、对话框 | Room DAO、直接写 GameStateStore |

**2.4 🔴 禁止循环依赖** — 模块间必须形成 DAG，CI 中通过 Konsist 检查。

### 3. 文件与代码行规范

**3.1 🔴 单文件最大 2000 行**（Room `_Impl`、ProtoBuf 等生成代码除外）。

**3.2 🔴 单行最大 120 字符** — 以 `android/config/detekt/detekt.yml` 的 `MaxLineLength: maxLineLength: 120` 为准（Compose 链式调用需要；import 语句、KDoc 标签、URL 除外）。

**3.4 🔴 最大构造参数：类 7 个，Composable 函数 6 个** — 超限须分组为配置数据类或拆分类（示例见 `rules/code-quality.md` §1.6）。

**3.5 🔴 单一职责** — 类名必须反映唯一职责。避免 "Manager"、"Handler"、"Utils" 等模糊后缀，除非确实承担协调/处理/工具职责。

**3.6 🔴 上帝对象重构阈值** — 超过 10 个构造依赖且超过 2000 行的类必须有重构计划。

### 4. ViewModel 规范

**4.1 🔴 必须继承 `BaseViewModel`** — 所有 ViewModel 继承 `com.xianxia.sect.ui.game.BaseViewModel`，确保统一的 `showError()`/`showSuccess()` 事件通道。

**4.3 🔴 只读 StateFlow 暴露状态** — 禁止公开 `MutableStateFlow`，状态一律通过 `StateFlow`（只读）暴露给 Compose。

**4.4 🔴 禁止直接访问 `GameStateStore`** — ViewModel 与 UI 层的所有状态变更走 `GameEngine` 方法，不直接调 `stateStore.update()` 或 `gameEngine.updateGameData {}`。数据流单向：UI → ViewModel → GameEngine → Service → GameStateStore。

### 5. 引擎服务规范

**5.1 🔴 通过快照访问状态** — Service 不直接订阅 `StateFlow`，通过传入参数或构造注入的 snapshot 访问状态。

**5.4 🔴 错误必须传播** — 禁止静默吞异常。`log-and-continue` 仅允许在非关键后台操作中使用。

**5.5 🔴 `@GameService` 注解** — 所有游戏领域逻辑类必须标注 `@GameService(name = "...")`。

### 6. 状态管理规范

**6.1 🔴 `GameStateStore` 是唯一真相源** — 禁止在 ViewModel/Service 缓存 `GameData` 或实体列表的本地副本。

**6.4 🔴 新增影响生产系统的字段需同步更新 checkpoint** — 生产系统用 `checkpointAllProduction()` 在政策/长老变化时重算活跃槽位的 duration 与 completionMonth。需同步的四类变更（生产类政策 / 长老类型 / 生产速率因子 / 丹药类型）及各自同步点见 `rules/pr-review-checklist.md`。

**6.5 🔴 界面实时性：UI 不驱动系统 tick** — **禁止复活旧焦点域体系**（`FocusDomain` / `InterfaceDomainMap` / `DomainMappingTest`）。界面需要随时间变化的数据（进度条 / 倒计时 / 数量增减）时，直接订阅对应 `GameEngine` StateFlow 派生（参照 `HeavenlyTrialViewModel.trialState` / `SecretRealmViewModel.session` 的 `map + stateIn` 模式）。

**6.6 🔴 精灵图必须统一注册并使用统一入口** — 所有静态图片资源必须无损 WebP、双模块放置、在 `XianxiaApplication.kt` 经 `SpriteResRegistry.register(...)` 注册、经 `SpriteImage("名称")` / Canvas `drawSprite(name, cache, ...)` / `SpriteResRegistry.resolve("名称")` 使用。禁止直引 `painterResource(R.drawable.xxx)`（注册代码除外），禁止提交 PNG/JPG 游戏图片（唯一例外 `ic_launcher-playstore.png`）。**新增精灵全流程 7 步、显示尺寸口径与图集 codegen 管线见 `rules/static-resources.md`。**

### 7. 数据库规范

**7.1 🔴 任何 Entity 变更必须有 Migration** — 每次变更：递增 `@Database(version)` + 编写 `MIGRATION_N_M` + 注册到 `build()`。**修改 `@Entity` 前必须先读 `rules/database-migration.md`**——最常见的存档损坏原因就是改字段没写 Migration。拿不准时保留旧字段 + 新字段（`@Ignore`），永远不要删列。

**7.2 🔴 禁止 `ALTER TABLE DROP COLUMN`** — SQLite 3.35.0 才支持。用 `db.safeDropColumns()` 或保留旧列 + `@Ignore`。

**7.4 🔴 Migration 必须有测试** — 旧版本插入种子数据 → 运行迁移 → 验证数据完整性。

### 8. 错误处理规范

**8.1 🔴 `CancellationException` 必须重新抛出** — 任何 `catch (e: Exception)` 前必须有 `catch (e: CancellationException) { throw e }`。

**8.2 🔴 禁止空 catch 块** — 每个 `catch` 至少包含 `Log.w(TAG, "...", e)`。

### 9. 测试规范

**9.1 🔴 引擎服务 80%+ 行覆盖率** — `:core:engine` 模块目标 80% 行覆盖（Kover/JaCoCo 检测）。

**9.2 🔴 Migration 必须有集成测试** — 每条 Migration 验证旧数据能完整迁移。

**9.5 🔴 跨域变更必须写测试守卫** — 当新增枚举/接口/配置项涉及多处分头实现时，必须写**测试守卫（Guard Test）**，在枚举值变更时自动失败并提示需要同步更新哪些地方。

**守卫测试三要素：** ① **枚举/配置驱动**——以新增入口（如 `SlotCategory` 枚举）为锚点遍历所有值；② **明确标注故意排除项**——`intentionallyExcluded` 集合显式声明为什么不覆盖；③ **错误消息带操作指引**——`assertTrue` 的 message 直接告诉开发者缺什么、去哪改。模板见 `rules/code-quality.md` §1.6。

**适用场景：** 任何"加一个枚举值需要同步改 N 处"的跨域变更（新增槽位系统 / 事件类型 / 对话框类型 / 建筑类型等）。

### 11. UI 样式规范

**11.1 🔴 按钮尺寸标准化** — 所有按钮使用 `ButtonSizes.StandardWidth` (72dp) × `ButtonSizes.StandardHeight` (38dp)。

### 12. 文档规范

**12.4 🔴 功能变更必须更新 Changelog** — 功能完成后**两个更新日志必须一起更新**（漏一个视为任务未完成）：游戏内 `android/app/src/main/assets/changelog_entries.json`（给玩家看：通俗、无术语、不泄数值）+ 外部 `CHANGELOG.md`（给开发者看，可写技术细节）。同日同版本并入同一条目；**禁止擅自更新版本号**。文案规范、合并规则与发布清单见 `rules/version-release.md`。

---

## 6. 代码审查与强制执行

**6.1 🔴 Pre-commit 检查** — 每次提交前运行 `cd android && ./gradlew.bat compileReleaseKotlin lintRelease`，必须 BUILD SUCCESSFUL。完整构建质量门禁（含 `--max-workers=1` 强制、新增警告检查、守卫测试要求）见 `rules/build-quality.md`。

**6.2 🔴 detekt baseline 只缩不增** — `detekt-baseline.xml` 只能减少条目，不能新增。新违规必须修复而非加入 baseline。

**6.3 🔴 PR 审查清单** — 提交前必须逐条过 [`rules/pr-review-checklist.md`](rules/pr-review-checklist.md)（代码级红线 + 扩展方向 + 提交前最低动作）。

**6.4 🔴 detekt 配置** — 以 `android/config/detekt/detekt.yml` 实值为准（行宽 120；`MagicNumber` 关闭；`TooManyFunctions` 文件 15/类 20/对象 12；`LargeClass` 800；`LongParameterList` 函数 8/构造 10；`EmptyCatchBlock` 启用）。

---

## 7. 设计方案规范

写任何设计方案（新功能 / 重构 / 技术选型）之前读 [`rules/design-plan-review.md`](rules/design-plan-review.md)（第零节 6 条原则 + 第一~七节自检清单 + 第八节提交前勾选表）。**两条不可协商的硬要求**：

- 🔴 **方案必须是可长期维护的成熟方案，禁止分阶段/渐进式交付** — 一次覆盖所有影响点（UI、存储、测试、旧数据兼容），不留"后续优化"。边界（与批次化工程实施的分工）见该文件第零节
- 🔴 **最优方案不计成本且需跨 iOS 平台** — 全量采纳头部产品先进设计；无法采纳的逐条说明原因与替代方案；所有方案必须 Android 与 iOS 均可落地，依赖平台独占能力时给出 iOS 对等实现

行业对标硬性指标（≥20 条来源、S/A 配额、6 步流程）见 `rules/industry-benchmark.md`。

---

## 8. 版本发布

发布时更新 `version.properties`（项目根，单一事实源）：`versionName` 为 `X.X.XX`（主版本 1 位 + 次版本 1 位 + 构建 2 位，不足前补零；**禁止写成 `4.2.0`**）；`versionCode` = 主版本 × 1000 + 次版本 × 100 + 构建（`4.2.00` → `4200`），且**必须单调递增**（低于上一版会被商店拒收）。**禁止擅自更新版本号**，由用户判断和指令。完整发布流程、**双更新日志**要求与检查清单见 [`rules/version-release.md`](rules/version-release.md)。