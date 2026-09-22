# AGENTS.md — 模拟宗门（mnzm）项目规范

> **本文件是本仓库唯一的规范真源（single source of truth）。**
> 仓库**只保留这一份**规范入口——历史上并存的 CLAUDE.md 已删除，避免双份维护与双份指令预算占用。
> 专题规则在 `rules/`，架构文档在 `docs/`——**这两处不会被任何 agent 自动加载**，
> 必须按 §0 路由表在对应任务开始时主动读取。
>
> **为什么拆这么多文件**：Codex CLI 的合并项目指令上限是 `project_doc_max_bytes`（默认 **32768 字节**），
> 超限即被静默截断——本文件单靠自身承载不了全部规范。DSH 侧另有 65536 字节预算。
> 因此策略是：**根文件只放「每次都用得到的硬约束 + 路由表」，深度规范下沉到 `rules/` 按需加载。**
> `scripts/check-agent-instructions.mjs` 是这套架构的门禁（预算闸 / 单一真源 / 引用无死链 / 路由表完整），进 CI。

## 0. 任务路由表（动任何东西之前先看这里）

| 你要做的事 | 必读（按顺序） |
|---|---|
| **任何代码改动之前** | §1 用户公约 + §5 编码规范 |
| **提交之前** | `rules/build-quality.md` → `rules/pr-review-checklist.md`（逐条过）→ `rules/version-release.md` |
| 改任何 `@Entity`、表结构、Entity 字段 | `rules/database-migration.md`（**存档损坏头号根因，最高优先**） |
| 改 `SaveData` ProtoBuf / `@ProtoNumber` 字段 | `rules/database-migration.md` |
| 新增玩法系统（新入口/新结算/新模块） | `rules/expansion-playbook.md` → `docs/architecture.md` |
| 新增或改对话框 | `rules/new-dialog-checklist.md` + `rules/dialog-scrim-standard.md` |
| 新增**含输入框**的界面 | `rules/dialog-soft-input-guard.md`（文本→`TextInputDialog`；数字→`NumberInputPanel`） |
| 新增聊天/对话类对话框 | `rules/chat-dialog-design.md` |
| 新增/改精灵图、素材、建筑或装饰显示尺寸 | `rules/static-resources.md` |
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
17. **桌面操作优先级铁律** — 使用 computer use / 桌面自动化能力（`mcp__cua-driver-mcp__*`、`cua_driver_native__*`）时，必须严格按以下顺序选择操作方式，禁止颠倒：
    1. **语义调用**（首选）— 用 `element_token` / `element_index` 配合当轮快照定位控件；可后台投递、不抢用户焦点，且不依赖像素与布局
    2. **键盘**（次选）— 界面上已明确标注的加速键（如 `Ctrl+N`）用 `hotkey`；纯文本输入用 `type_text`；提交用 `press_key`
    3. **像素坐标**（最后手段）— 仅用于 canvas / WebGL / 视频等**没有语义节点**的自绘表面；坐标必须从当轮截图直读
    - 工具返回 `snapshot_id has invalid format`、`background_unavailable` 等错误时，**必须原地解决**（重新取快照以获得有效句柄；前台投递仅在该工具明确要求时使用），**禁止降级为盲点坐标绕开**——绕开会导致点击打偏、误改界面状态
    - 每次操作后必须从新状态验证结果，**点击送达不等于结果达成**
    - **文字输入后必须回读校验**：`type_text` / `set_value` 返回的字符数只说明"按键已发出"，**不保证目标控件完整接收**。输入完成后必须重新取快照、**回读该控件的 `value` 逐字核对**；不符则清空重输，禁止未核对就提交。富文本编辑器（Lexical/ProseMirror）优先用逐字符键入（`keystrokes`）

---

## 2. 工程速查（Build / Test / Lint）

所有命令在 `android/` 目录下用 Gradle wrapper 执行：

```bash
# 编译检查（最快的反馈，每次改动后都跑）
cd android && ./gradlew.bat compileReleaseKotlin

# 构建 release / debug APK
cd android && ./gradlew.bat assembleRelease
cd android && ./gradlew.bat assembleDebug

# 全量单元测试（Robolectric + JUnit）— 必须串行（--max-workers=1），并行会因共享静态状态跨类污染出错
cd android && ./gradlew.bat testReleaseUnitTest --max-workers=1

# 跑单个测试类 — 同样串行，且必须模块限定写法（裸 `test --tests` 会报 Unknown command-line option；
# 未模块限定时过滤会波及 core:data 等模块触发 No tests found）
cd android && ./gradlew.bat :app:testReleaseUnitTest --tests "com.xianxia.sect.core.engine.BattleSystemTest" --max-workers=1

# Lint
cd android && ./gradlew.bat lintRelease

# 静态分析
cd android && ./gradlew.bat detekt

# 清理（KSP 增量缓存炸出 NoSuchFileException *_Impl.java 时用）
cd android && ./gradlew.bat clean
```

测试位于 `android/app/src/test/` 与各模块 `src/test/`。用 JUnit 4、Mockito、Robolectric、`kotlinx-coroutines-test`；
Robolectric 测试需要 `includeAndroidResources = true`。mock/stub 约定见 `rules/testing.md`。

```bash
# 覆盖率（Kover）— 本地默认关闭（消除插桩开销），必须显式开开关，否则覆盖率为 0
cd android && ./gradlew.bat koverHtmlReport --max-workers=1 -Pkover.enabled=true

# 完整 CI 检查（编译 + 测试 + detekt + 覆盖率 + RNG 守卫）— 测试必须串行
# RNG 红线是守卫测试而非 grep（`.random()` 是 stdlib 扩展、自建 RNG 是 object，都不带
# `import kotlin.random.Random`，grep 匹配不到）——见 docs/adr/rng-determinism-remediation.md §1
cd android && ./gradlew.bat compileReleaseKotlin testReleaseUnitTest --max-workers=1 -Pkover.enabled=true detekt koverHtmlReport -Pkover.enabled=true && ./gradlew.bat :core:engine:testReleaseUnitTest --tests "com.xianxia.sect.core.architecture.RngSourceGuardTest" --tests "com.xianxia.sect.core.architecture.RngEngineIsolationGuardTest" --max-workers=1
```

**规范分发架构门禁**（改本文件、`rules/`、`docs/` 或任何 `AGENTS.md` 之后必跑）：

```bash
node scripts/check-agent-instructions.mjs
```

---

## 3. 项目定位与架构入口

**项目**：修仙宗门模拟经营手游（Android，包名 `com.xianxia.sect`）。技术栈：Kotlin 2.2.20（UI/平台层）
+ **C++20 引擎核心 `game-core`**（零 Android 依赖、桌面可编译）+ JNI / nlohmann::json；Compose、Hilt、Room、MMKV、kotlinx.serialization ProtoBuf。

架构设计见 [`docs/architecture.md`](docs/architecture.md)，代码级 Wiki 见 [`CODE_WIKI.md`](CODE_WIKI.md)，
子系统实现与关键类见 [`docs/knowledge-base.md`](docs/knowledge-base.md)。**动这些子系统之前先读对应文档**：

- **C++ 是 AUTHORITATIVE 真相源** — `game-core` 承载模拟逻辑，Kotlin `GameStateStore` 是**只读镜像**；**反向同步通道已删除**：稳态下 Kotlin 对 C++ 只读，唯一合法写入是 `StateSyncService.importToNative` 全量导入。防复发守卫：`MirrorReadOnlyGuardTest`（符号面）+ `DiffAuthoritativeTickTest`（行为面）。**未完成**：真机验证批、WS-4 NPC 移动、WS-1 阶段 3 数据导向存储。总方案 `docs/adr/cpp-engine-migration.md`，进度 `docs/cpp-engine.md`，镜像合法面 `docs/ui-read-surface.md` §2
- **惰性结算四层** — L0 时间推进 / L1 每旬检查 / L2 惰性生产 / L3 月变 / L4 年变（年变分帧，对标 Supercell + RimWorld）；新逻辑必须落既有层级，禁另起结算循环或新线程 tick
- **线程契约** — 唯一合法状态写入口是 GameEngine-Thread；白名单与禁止区见 [`docs/threading-contract.md`](docs/threading-contract.md)（新增跨线程交互须先登记再实现）
- **存档为纯手动** — 禁止重新实现自动保存，禁止 `autoSave*` 命名；`SaveValidator` 规则引擎按 `order` 排序，`registerDefaults()` 加一行即注册
- **扩展性预留与待办** — RemoteConfig 未绑定状态与激活前置、商业化接入点、离线收益引擎接入点、社交隔离层、iOS 迁移预留；R 系列待办与偿还触发档案见 `docs/architecture.md`

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
> 提交前审查清单见 `rules/pr-review-checklist.md`；质量细则与**全部 BAD/GOOD 代码示例**见 `rules/code-quality.md`（§1.6）。

### 0. 代码质量铁律

**0.1 🔴 代码必须有测试覆盖** — 所有新增/修改的业务逻辑代码必须有对应单元测试。无测试视为未完成，不得合并。需覆盖正常路径、边界条件（空值/空列表/极值）、异常路径。

**0.2 🔴 代码必须可长期维护** — 命名清晰、意图明确、不需要注释就能理解；函数短小聚焦、单一职责；避免过度耦合，依赖注入优于硬编码；不引入隐性技术债。

**0.3 🔴 禁止"当前能跑就行"心态** — 只处理正常路径、日志不足、硬编码、重复代码、明知有更好实现却选更差的，直接打回。

**0.4 🔴 禁止硬编码数字（魔法数字）** — 有业务含义的数字必须定义为命名常量（`const val` 或 `companion object` 的 `val`）。例外：0、1、-1 等自解释的循环/索引/增量、detekt 配置中声明的游戏数学常量。

### 1. Kotlin 语言规范

**1.1 🔴 禁止 `!!` 操作符** — 除非有编译时证明（如 `lateinit var` 初始化后访问）。用 `?.` / `?:` / `checkNotNull()` 安全访问。

**1.2 🟡 优先 `val`** — 默认 `val`；`var` 仅在不可变 copy-on-write 不可行时使用并注释理由。

**1.3 🔴 领域结果用 sealed class** — 可预期的业务失败（找不到、校验失败）用 sealed class，不抛异常；异常仅用于程序错误和基础设施故障。禁止裸 `Boolean` 代表成功/失败。

**1.4 🔴 协程规范** — 禁止 `runBlocking`（测试用 `runTest`）；Dispatcher 通过 Hilt `@Dispatcher(IO)` 注入，禁止硬编码 `Dispatchers.IO`。

**1.5 🟢 扩展函数放专用文件** — 某类型的大量扩展函数放入 `{TypeName}Ext.kt`，不堆积在 ViewModel/Service。

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

**2.3 🟡 `internal` 默认可见性** — 非模块公开 API 的类/函数一律 `internal`。

**2.4 🔴 禁止循环依赖** — 模块间必须形成 DAG，CI 中通过 Konsist 检查。

**2.5 🟢 新建模块需 ADR** — 新增 Gradle 模块需在 `docs/adr/` 记录决策，且至少包含 3 个内聚领域类。

### 3. 文件与代码行规范

**3.1 🔴 单文件最大 2000 行**（Room `_Impl`、ProtoBuf 等生成代码除外）。

**3.2 🔴 单行最大 120 字符** — 以 `android/config/detekt/detekt.yml` 的 `MaxLineLength: maxLineLength: 120` 为准（Compose 链式调用需要；import 语句、KDoc 标签、URL 除外）。

**3.3 🟡 单函数体最大 60 行** — 超限拆分为私有辅助函数。

**3.4 🔴 最大构造参数：类 7 个，Composable 函数 6 个** — 超限须分组为配置数据类或拆分类（示例见 `rules/code-quality.md` §1.6）。

**3.5 🔴 单一职责** — 类名必须反映唯一职责。避免 "Manager"、"Handler"、"Utils" 等模糊后缀，除非确实承担协调/处理/工具职责。

**3.6 🔴 上帝对象重构阈值** — 超过 10 个构造依赖且超过 2000 行的类必须有重构计划。

### 4. ViewModel 规范

**4.1 🔴 必须继承 `BaseViewModel`** — 所有 ViewModel 继承 `com.xianxia.sect.ui.game.BaseViewModel`，确保统一的 `showError()`/`showSuccess()` 事件通道。

**4.3 🔴 只读 StateFlow 暴露状态** — 禁止公开 `MutableStateFlow`，状态一律通过 `StateFlow`（只读）暴露给 Compose。

**4.4 🔴 禁止直接访问 `GameStateStore`** — ViewModel 与 UI 层的所有状态变更走 `GameEngine` 方法，不直接调 `stateStore.update()` 或 `gameEngine.updateGameData {}`。数据流单向：UI → ViewModel → GameEngine → Service → GameStateStore。

**4.5 🟡 UserAction/ActionResult 模式** — ViewModel 公开方法用 sealed `UserAction` 统一入口，便于错误处理与日志。

**4.6 🟡 ViewModel 与 Screen 一对一** — 一个 ViewModel 只驱动一个 Screen。

### 5. 引擎服务规范

**5.1 🔴 通过快照访问状态** — Service 不直接订阅 `StateFlow`，通过传入参数或构造注入的 snapshot 访问状态。

**5.2 🟡 方法签名：`suspend` 或返回 Result** — 执行 I/O 或领域逻辑的服务方法必须为 `suspend`，或返回 `Result<T>`/sealed class。

**5.3 🟡 服务间禁止共享可变状态** — 通过 EventBus 事件或协调器对象通信，不用共享 `MutableStateFlow` 或 `ConcurrentHashMap`。

**5.4 🔴 错误必须传播** — 禁止静默吞异常。`log-and-continue` 仅允许在非关键后台操作中使用。

**5.5 🔴 `@GameService` 注解** — 所有游戏领域逻辑类必须标注 `@GameService(name = "...")`。

### 6. 状态管理规范

**6.1 🔴 `GameStateStore` 是唯一真相源** — 禁止在 ViewModel/Service 缓存 `GameData` 或实体列表的本地副本。

**6.2 🟡 多实体变更必须用单次 `stateStore.update`** — 所有状态修改在同一 `stateStore.update {}` 事务内原子完成，禁止多次孤立的 `update` 调用（示例见 `rules/code-quality.md` §1.6）。

**6.3 🟡 Flow 派生规则** — 高频 StateFlow 派生必须用 `distinctUntilChanged()` + `sample(50)` + `stateIn(scope, WhileSubscribed(5000), initial)`。

**6.4 🔴 新增影响生产系统的字段需同步更新 checkpoint** — 生产系统用 `checkpointAllProduction()` 在政策/长老变化时重算活跃槽位的 duration 与 completionMonth（无需指纹数据类）。新增以下内容时必须同步（同步点清单见 `rules/pr-review-checklist.md`）：

- 新增生产类政策 → `SectPolicyToggleUseCase` 触发 `checkpointAllProduction()`
- 新增长老类型 → `ElderManagementUseCase.productionElderTypes` 注册
- 新增生产速率因子 → `calculateWorkDurationWithAllDisciples` / `calculateSpiritFieldMaturityBonus`
- 新增丹药类型 → `CultivationCore.processRealtimeAutoPills` + `DisciplePillManager.classify`

**6.5 🔴 界面实时性：UI 不驱动系统 tick** — `FocusDomain` / `InterfaceDomainMap` / `DomainMappingTest` 均不存在，**禁止按旧规则注册焦点域**。界面需要随时间变化的数据（进度条 / 倒计时 / 数量增减）时，直接订阅对应 `GameEngine` StateFlow 派生（参照 `HeavenlyTrialViewModel.trialState` / `SecretRealmViewModel.session` 的 `map + stateIn` 模式）。

**6.6 🔴 精灵图必须统一注册并使用统一入口** — 所有静态图片资源必须无损 WebP、双模块放置、在 `XianxiaApplication.kt` 经 `SpriteResRegistry.register(...)` 注册、经 `SpriteImage("名称")` / Canvas `drawSprite(name, cache, ...)` / `SpriteResRegistry.resolve("名称")` 使用。禁止直引 `painterResource(R.drawable.xxx)`（注册代码除外），禁止提交 PNG/JPG 游戏图片（唯一例外 `ic_launcher-playstore.png`）。**新增精灵全流程 7 步、显示尺寸口径与图集 codegen 管线见 `rules/static-resources.md`。**

### 7. 数据库规范

**7.1 🔴 任何 Entity 变更必须有 Migration** — 每次变更：递增 `@Database(version)` + 编写 `MIGRATION_N_M` + 注册到 `build()`。**修改 `@Entity` 前必须先读 `rules/database-migration.md`**——最常见的存档损坏原因就是改字段没写 Migration。拿不准时保留旧字段 + 新字段（`@Ignore`），永远不要删列。

**7.2 🔴 禁止 `ALTER TABLE DROP COLUMN`** — SQLite 3.35.0 才支持。用 `db.safeDropColumns()` 或保留旧列 + `@Ignore`。

**7.3 🟡 ProtoBuf 仅 `List`，禁止 `Set`/`Map`** — 需要去重语义在业务层 `.toSet()` 转换。忽略会导致序列化静默失败、**存档变空**。

**7.4 🔴 Migration 必须有测试** — 旧版本插入种子数据 → 运行迁移 → 验证数据完整性。

### 8. 错误处理规范

**8.1 🔴 `CancellationException` 必须重新抛出** — 任何 `catch (e: Exception)` 前必须有 `catch (e: CancellationException) { throw e }`。

**8.2 🔴 禁止空 catch 块** — 每个 `catch` 至少包含 `Log.w(TAG, "...", e)`。

**8.3 🟡 UI 错误统一走 `BaseViewModel.showError()`** — ViewModel 不直接处理错误展示。

**8.4 🟡 引擎错误记录上下文** — `Log.e(TAG, "操作名 failed: id=$id, ctx=$ctx", e)`，信息要足够定位问题。

### 9. 测试规范

**9.1 🔴 引擎服务 80%+ 行覆盖率** — `:core:engine` 模块目标 80% 行覆盖（Kover/JaCoCo 检测）。

**9.2 🔴 Migration 必须有集成测试** — 每条 Migration 验证旧数据能完整迁移。

**9.3 🟡 测试命名：`方法名_状态_预期行为`** — Given-When-Then 模式，如 `addEquipmentStack - empty name returns INVALID_NAME`。

**9.4 🟢 优先 Fake 而非 Mock** — 手写 Fake 优于 Mockito mock，可复用、可读、可调试。mock 风格硬约束见 `rules/testing.md`。

**9.5 🔴 跨域变更必须写测试守卫** — 当新增枚举/接口/配置项涉及多处分头实现时，必须写**测试守卫（Guard Test）**，在枚举值变更时自动失败并提示需要同步更新哪些地方。

**守卫测试三要素：** ① **枚举/配置驱动**——以新增入口（如 `SlotCategory` 枚举）为锚点遍历所有值；② **明确标注故意排除项**——`intentionallyExcluded` 集合显式声明为什么不覆盖；③ **错误消息带操作指引**——`assertTrue` 的 message 直接告诉开发者缺什么、去哪改。模板见 `rules/code-quality.md` §1.6。

**适用场景：** 任何"加一个枚举值需要同步改 N 处"的跨域变更（新增槽位系统 / 事件类型 / 对话框类型 / 建筑类型等）。

### 10. 性能规范

**10.1 🟡 Compose 稳定性注解** — 所有出现在 Compose State 中的数据类标注 `@Immutable`，或加入 `stability_config.conf`。

**10.2 🟡 禁止 Composition 内读 State** — 用 `derivedStateOf` 计算派生值，避免不必要的 recomposition（示例见 `rules/code-quality.md` §1.6）。

**10.3 🟡 `LazyColumn`/`LazyRow` 必须用稳定 key** — `key = { it.id }`，不可用 index（会导致排序/过滤时的错误 recomposition）。

**10.4 🟡 Canvas 用 `drawBehind{}`** — 静态绘制用 `Modifier.drawBehind {}` 跳过 Composition/Layout 阶段；动画用 `Animatable` + `LaunchedEffect`。

### 11. UI 样式规范

**11.1 🔴 按钮尺寸标准化** — 所有按钮使用 `ButtonSizes.StandardWidth` (72dp) × `ButtonSizes.StandardHeight` (38dp)。

### 12. 文档规范

**12.1 🟡 公开 API 必须有 KDoc** — `:core:domain` 与 `:core:engine` 中所有 public 函数/类/属性必须有 KDoc（描述 + `@param` + `@return`）。

**12.2 🟢 架构决策记录到 `docs/adr/`** — 新模块、重要模式变更、大重构写入 ADR（Context / Decision / Consequences）。

**12.3 🟢 同步 `CODE_WIKI.md` 与 `docs/architecture.md`** — 新增模块/模式后更新架构文档。

**12.4 🔴 功能变更必须更新 Changelog** — 功能完成后**两个更新日志必须一起更新**（漏一个视为任务未完成）：

- **游戏内**（`android/app/src/main/assets/changelog_entries.json`）— 在当前版本条目的 `changes` 数组**末尾追加**一行；**给玩家看**，须通俗易懂无专业术语、不泄露数值细节、只能粗略描述
- **外部**（`CHANGELOG.md`，项目根目录）— 追加到**当前版本**段落内，不强制递增版本号；给开发者看，可写技术细节

禁止按日期拆成多个同版本条目（同日同版本一律并入同一条目，`date` 取首次发布日）；**禁止擅自更新版本号**，由用户判断和指令。
完整的玩家视角文案规范、条目合并规则、发布检查清单与关键文件索引见 `rules/version-release.md`。

---

## 6. 代码审查与强制执行

**6.1 🔴 Pre-commit 检查** — 每次提交前运行 `cd android && ./gradlew.bat compileReleaseKotlin lintRelease`，必须 BUILD SUCCESSFUL。完整构建质量门禁（含 `--max-workers=1` 强制、新增警告检查、守卫测试要求）见 `rules/build-quality.md`。

**6.2 🔴 detekt baseline 只缩不增** — `detekt-baseline.xml` 只能减少条目，不能新增。新违规必须修复而非加入 baseline。

**6.3 🔴 PR 审查清单** — 提交前必须逐条过 [`rules/pr-review-checklist.md`](rules/pr-review-checklist.md)（代码级红线 + 扩展方向 + 提交前最低动作）。该文件原为 CLAUDE.md 13.3 节，2026-09 整表迁移。

**6.4 🔴 detekt 配置** — 以 `android/config/detekt/detekt.yml` 实值为准（行宽 120；`MagicNumber` 关闭；`TooManyFunctions` 文件 15/类 20/对象 12；`LargeClass` 800；`LongParameterList` 函数 8/构造 10；`EmptyCatchBlock` 启用）。

---

## 7. 设计方案规范

写任何设计方案（新功能 / 重构 / 技术选型）之前读 [`rules/design-plan-review.md`](rules/design-plan-review.md)：
第零节是**设计方案 6 条原则**（编写规范结构 / 功能模块化 / 全局视角与影响范围清单格式 / 低高端设备兼容 / 隐私合规双入口 / 扩展方向对标），
第一~七节是**方案自检清单**（未来场景推演 / 技术债显性化 / YAGNI 反向检查 / 测试成本核算 / 全局影响交叉核对 / 决策分级 / 盲区自查），
第八节是提交用户前的逐项勾选表。

两条不可协商的硬要求：

- 🔴 **方案必须是可长期维护的成熟方案，禁止分阶段/渐进式交付** — 一次覆盖所有影响点（UI、存储、测试、旧数据兼容），不留"后续优化"。边界（与批次化工程实施的分工）见该文件第零节
- 🔴 **最优方案不计成本且需跨 iOS 平台** — 全量采纳头部产品先进设计；无法采纳的逐条说明原因与替代方案；所有方案必须 Android 与 iOS 均可落地，依赖平台独占能力时给出 iOS 对等实现

行业对标的硬性指标（≥20 条来源、本年前两年窗口、S/A/B/C 来源等级表、≥12 条 S/A 配额）与 6 步对标流程见 `rules/industry-benchmark.md`。

---

## 8. 版本发布

发布时更新 `version.properties`（项目根，单一事实源）：

- `versionCode` — 递增 1
- `versionName` — 格式 `X.XX.XX`（主版本 1 位 + 次版本 2 位 + 构建 2 位，不足前补零）。例：`4.00.86` → `4.00.87`、`4.00.99` → `4.01.00`、`4.99.99` → `5.00.00`。**禁止写成 `4.0.86`**（次版本段缺前补零）

测试必须串行：`./gradlew.bat testReleaseUnitTest --max-workers=1`（并行会因共享静态状态跨类污染）。

完整发布检查清单见 [`rules/version-release.md`](rules/version-release.md)。
