# PR 审查清单（Code Review Checklist）

> **权威位置**：本文件是「提交前必须逐条过一遍」的完整审查清单，由根 `AGENTS.md` §0 路由表索引。
> 严重度：🔴 严重（必须遵守，违反导致构建/审查失败）、🟡 重要（应遵守，违反需在审查中说明理由）。
>
> **迁移说明（2026-09）**：本清单原先内嵌在根 `AGENTS.md` 第 13.3 节。根文件受 Codex CLI 的
> `project_doc_max_bytes`（默认 32768 字节）硬约束，超限即被静默截断，故整表迁移至本文件
> （见根 `AGENTS.md` §0）。迁移时同步修正了三处失真：① 死亡标记路径在旧表中出现两次且指向
> 不同 API（已合并统一）；② 含输入框对话框条目仍是 2026-09 之前的旧框架（已按
> `rules/dialog-soft-input-guard.md` 的根治口径改写）；③ 渲染特性检查清单的引用路径少写了一层
> `android/` 前缀（本次已修正）。

---

## 一、代码级红线（每次提交前逐条核对）

| 严重度 | 检查项 |
|--------|--------|
| 🔴 | 无 `!!` 操作符 |
| 🔴 | `CancellationException` 已重新抛出 |
| 🔴 | 无空 catch 块 |
| 🔴 | Entity 变更有 Migration |
| 🔴 | UI 层无直接 `GameStateStore` 访问 |
| 🔴 | 文件不超过 2000 行 |
| 🔴 | 类构造参数不超过 7 个 |
| 🔴 | 新功能有测试 |
| 🔴 | 代码无"当前能跑就行"迹象（边界/异常/日志/硬编码） |
| 🔴 | 新增影响修炼速率的操作已添加 `checkpointDisciple()` 调用 |
| 🔴 | 新增随机数逻辑已使用 `GameRngManager.getRng(RngPartition.xxx)` 替代 `kotlin.random.Random` |
| 🔴 | 新增 `GameSystem` 已拆分为 ≤60 行/方法的子系统，不可出现 God Method |
| 🔴 | 新增系统的 `EventBus` 事件 emit 在 `stateStore.update` 事务外（参照 `flushPendingEvents` 模式） |
| 🔴 | 新增标记 `isAlive=0` / `status=DEAD` 的代码路径**必须调用 `discipleTables.markDead(id, year)`**，禁止手写三个字段；仅 `handleDiscipleDeath` 可豁免（其内部已写 deathYears）。弟子死亡处理链路见 `docs/knowledge-base.md` |
| 🔴 | 新增影响炼丹/锻造/灵田速率因子已同步更新 `calculateWorkDurationWithAllDisciples` 或 `calculateSpiritFieldMaturityBonus` |
| 🔴 | 新增生产类政策已同步在 `SectPolicyToggleUseCase` 中触发 `checkpointAllProduction()` |
| 🔴 | 新增长老类型已同步在 `ElderManagementUseCase.productionElderTypes` 中注册 |
| 🔴 | 新增对话框已遵循 `rules/new-dialog-checklist.md` 标准流程（注册 `DialogType` → `GameOverlayHost` 渲染 when 分支） |
| 🔴 | 新增含输入框的界面已按 `rules/dialog-soft-input-guard.md` 判定输入类型：**数字/数量输入一律用 `NumberInputPanel`**（自绘键盘，完全不弹系统 IME）；**文本输入一律用 `TextInputDialog`**（独立平台 Dialog 窗口，内部已内置 `DialogSoftInputGuard` + `ImeAwareContainer` + `freezeSystemBars=true` + `rememberImeAwareAutoFocusRequester` + `InputSessionStateMachine`）；**含游戏渲染 Surface 的 Activity 窗口内禁止内联文本输入**；🔴 禁止新增任何机型/渲染模式特判分支（该文件 2026-09 已根治，历史五轮补丁式机制全部作废） |
| 🔴 | 新增使用 Compose `Dialog()` 或 Material3 `AlertDialog` 的组件已添加 `DialogSystemBarGuard()` 调用（Dialog Window 不继承 Activity 的 `hideSystemBars()`，需独立隐藏状态栏；含输入框时经 `DialogSystemBarFreezeEffect` 冻结本窗口，guard 自动只隐藏状态栏） |
| 🔴 | 新增聊天/对话类对话框使用 `UnifiedGameDialog` 容器（详见 `rules/chat-dialog-design.md`） |
| 🔴 | 新增精灵图已在 `SpriteResRegistry` 注册 + 文件已放两个模块 `drawable-nodpi`（详见 `rules/static-resources.md`） |
| 🔴 | 新增 UI 界面使用 `SpriteImage()` 或 `SpriteResRegistry.resolve()` 而非直接 `R.drawable.xxx` |
| 🔴 | 渲染特性变更（地图/Canvas/精灵）已同步实现 Vulkan 和 Canvas 两条路径（见 `android/docs/renderer-feature-checklist.md`），并已回来更新该清单 |
| 🔴 | 新增渲染特性有对应的 `SoftwareCanvasBackend` 单元测试（`SoftwareCanvasBackendTest.kt`） |
| 🔴 | HW 加速决策已检查所有 Activity 入口（`MainActivity` 和 `GameActivity` 均需在 `super.onCreate()` 前检查 `isAccelerationDisabled()` 并切换主题） |
| 🔴 | 使用 `Build.SOC_MANUFACTURER`（API 31+）、`Build.SOC_MODEL`（API 31+）等新增 API 字段已添加 `Build.VERSION.SDK_INT` 守卫 |
| 🔴 | 新增/修改涉及 AI 弟子参战的战斗路径必须调用 `AISectDiscipleManager.prepareDisciplesForBattle()` 生成模拟装备/功法，禁止传 `emptyMap()` 或自行构建装备映射；AI 弟子不吃丹药、无血炼 |
| 🟡 | 新 Service 有 `@GameService` 注解 |
| 🟡 | State 数据类有 `@Immutable` |
| 🟡 | 公开 API 有 KDoc |
| 🟡 | Flow 派生用了 `distinctUntilChanged`/`sample`/`stateIn` |
| 🔴 | 新增 `SlotCategory` 枚举值后需更新 8 处（`SlotCategoryCoverageTest` 会失败并列出具体指引）：`scanAndRegister` + `DiscipleSlotCleanup.clearAllSlots` + 分配入口（事务内 `clearAllSlotsDataOnly` 防双槽位 + 事务外 `releaseDiscipleFromAllSlotsAtomic`/`confirmAssign` + 旧 occupant release/sync，清单式守卫检查新入口文件）+ 测试检查集合 + `DiscipleStatusService.buildSlotFlagsFor`/`SlotFlags`（状态推导）+ `clearSlotsForReset` + `GameEngineSelfHealOps` 自愈扫描/重写；住所式被动不互斥须显式加入 `intentionallyExcluded` 并注释理由 |
| 🔴 | 新增 `@ProtoNumber` 字段规则：字段默认值如果不是该类型的零值（`0`/`""`/`false`/`emptyList()`），必须标注 `@EncodeDefault(EncodeDefault.Mode.ALWAYS)`，否则 `encodeDefaults = false` 下该字段不会被写入二进制，导致存档数据丢失 |
| 🔴 | 新增给玩家发放物品（装备/丹药/草药/材料/种子/功法/储物袋）的代码路径**必须通过 `InventorySystem.addXxx` 统一入口**（`StackableItemStore` 自动合并，禁止手写 `find`+追加/`coerceAtMost` 截断/手写 `StackableItemStore(`——守卫测试 `InventoryAddPathGuardTest` 会拦截），并包裹 `withTrackingSource("来源名")`（来源名必须加入 `OverflowMailSender.SOURCE_DISPLAY_NAMES` 映射，否则来源映射守卫测试失败） |
| 🔴 | 新增广告类型（`AdPurpose` 枚举值）已在 ViewModel 中通过 `adService.watchAd()` 统一入口调用，白名单守卫由 `AdServiceImpl` 自动继承。详见 `docs/knowledge-base.md#免广告特权白名单` |
| 🔴 | 新增物品发放路径须判定**溢出语义类别**：**凭据类**（玩家可重试的领取/获得——兑换码、宗门等级奖励、新手引导、邮件领取、没收、卸装）必须包裹 `withOverflowMailSuppressed`（溢出不转邮件，失败保留凭据重试补齐）；**发放类**（自动入库——战斗掉落、探索所得、灵田收获、生产产出、商人购买、AutoBuy、储物袋开启）不包裹（溢出自动转邮件）。选错类别会导致物品重复发放或丢失——这是对抗性审查实测出的 C 类缺陷（详见 `rules/economy-design.md` 与 `rules/database-migration.md`） |
| 🔴 | 登录/主流程**关键路径上的非必要初始化必须解耦**：与登录无因果关系的初始化（广告 SDK/统计/回调注册）不得与关键步骤（防沉迷验证/界面跳转）串行绑定在同一调用链——初始化调用必须幂等、**永不抛出**，且经 `safeRunAfterSdkInit` 编排（语义由 `SafeRunAfterSdkInitTest` 守护）；登出路径必须完整清理 TapTap SDK 会话（防静默登录导致防沉迷验证不触发）。详见 `rules/sdk-init-lifecycle.md` |
| 🔴 | 新增玉符（`jadeSymbols`）消耗/发放路径**必须收敛于 `JadeSymbolService`**（消耗走事务内 `deduct(state, cost)` 同步运行时 totalCount，发放走服务内部结算），禁止在 Service/GameEngine 直接 `copy(jadeSymbols = ...)`——玉符是绝对值覆盖写模型，绕过 totalCount 同步则 `checkpointNow` 把余额写回覆盖前值（玉符回涨）；守卫测试 `JadeSymbolConsumptionGuardTest` 会拦截。模式参照：洗炼灵根 `GameEngineSpiritRootOps.washSpiritRoot`（先扣后抽 + sealed 三态 + 事务外 `publishJadeSymbolStateNow`） |

## 二、扩展方向（新增功能时的规范遵循）

| 严重度 | 检查项 | 引用 |
|--------|--------|------|
| 🔴 | 新增代码已遵循 `rules/code-quality.md`（命名规范/坏味道清单/设计原则/可测试性/扩展友好性/量化指标） | code-quality.md |
| 🔴 | 新增代码已检查 iOS 跨平台可移植性（core 层无 Android 独占 API、平台能力走接口抽象、新平台依赖有 iOS 对等方案——游戏未来做 iOS 端） | code-quality.md 跨平台章节 |
| 🔴 | 修改代码后已做注释一致性检查：注释只描述最终状态，无"之前/原来/新增/删除/迁移"等历史性表述、无已解决 TODO、无旧架构描述、无 AI 工作汇报式注释（详见 `rules/code-comment.md` 七项检查清单） | code-comment.md |
| 🔴 | 新增玩法系统已遵循 `rules/expansion-playbook.md` 全流程（引擎注册/惰性结算层级/EventBus/RNG 分区/DialogType/Migration/存档兼容/进度锚定游戏时间/引导接入/配置开关/守卫测试） | expansion-playbook.md |
| 🔴 | 新增玩法 UI 已优先复用现有组件（`GameButton`/`UnifiedGameDialog`/`ItemCard`/`SpriteImage`/`CircularCheckbox` 等，组件清单见 `rules/expansion-playbook.md` UI 组件复用优先），禁止自建重复组件；确需新建的通用组件放 core/ui 并登记回清单 | expansion-playbook.md UI 组件复用优先 |
| 🔴 | 新增货币/经济资源已遵循 `rules/economy-design.md`（必要性论证/持有上限/源汇闭环/通胀防控/奖励价值审计） | economy-design.md |
| 🔴 | 新增付费点位（广告/IAP/月卡/战令/活动）已遵循 `rules/commercialization.md`（冷却或领取窗口/慷慨原则/隐私合规双入口） | commercialization.md |
| 🔴 | 新增运营活动（历战卡片/运营邮件/RemoteConfig 配置）已遵循 `rules/commercialization.md`（配置化/时间窗三态/本地默认值兜底） | commercialization.md |
| 🔴 | 新增排行榜/社交功能已遵循 `rules/social-system.md`（异步社交/好友榜优先/分层奖励/数据合规/不污染 AI 外交路径） | social-system.md |
| 🔴 | 新增埋点事件已遵循 `rules/data-analytics.md`（无 PII/非阻塞/事件字典登记） | data-analytics.md |
| 🟡 | 新增功能模块具备配置化启停开关 | 设计方案规范 原则 2 |

> 归并说明：以上扩展方向条目为**设计级**（全流程遵循）；第一节中广告 `watchAd` 统一入口（代码级）、渲染双路径/Vulkan 降级/Build.SOC_API 守卫（代码级）等条目保留在第一张表，两者层级不同不重复。

## 三、提交前的最低动作（不可省略）

1. 逐条过完本文件第一节与第二节（🔴 项有任何未满足即为未完成）。
2. 跑构建质量门禁：`rules/build-quality.md`（`--max-workers=1` + 编译 + 新增警告检查 + 守卫测试）。
3. 同步两个更新日志并核对版本号规则：`rules/version-release.md`。
4. 若本次改动触发了注册表/枚举扩展，确认对应守卫测试已跑且为绿——守卫红即任务未完成。
