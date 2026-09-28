# 规则：新玩法系统接入规范（Expansion Playbook）

> ⚠️ **约束未来设计的接入规范**：本文约束新增玩法系统的接入流程；时间与结算基座（权威时间轴/四层/离线收益）已于 2026-09 实时结算改造落地，按现状描述。现状基线见 `docs/knowledge-base.md#扩展性现状盘点`。新增玩法系统（新入口/新结算内容/新系统模块）前必读本文。

## 第一步：判断是否真的需要新系统

新玩法需求先回答三个问题（参照 RimWorld 功能删减三问 / rules/design-plan-review.md 第零节 原则 2）：
1. 能否通过扩展既有系统实现？（新生产类型 → 扩展 `ProductionProcessor`；新活动 → 历战卡片注册；新随机事件 → 事件库扩展）
2. 去掉这个功能能否发布？（砍掉所有发行不需要的东西）
3. 新增系统是否形成独立可测试、可开关的模块？（禁止硬编码嵌入既有类形成面条式代码）

只有三条都否/可模块化时，才按本文全流程接入。

## 接入检查清单（🔴 10 项硬性约束，全部必须完成）

- [ ] **1. 引擎注册**：接入 GameSystem 生命周期（BootPhase 单向推进注册点）+ `@GameService(name = "...")` 注解（AGENTS.md 5.5）
- [ ] **2. 结算层级选择**：新结算逻辑必须落入既有四层（L0 时间推进——权威毫秒轴 + 日历投影 / L1 连续积分 + 判定窗口 / L2 惰性差分 / L3+L4 月年边界事件派发），**禁止另起结算循环或新线程 tick**（层级语义详见 `android/core/engine/AGENTS.md` 结算层级节；连续产出类落 L1 积分函数族 `accrueContinuous`/`accrueMonthlyContinuous`，离散判定类落判定窗口，完成时间戳类落 L2 毫秒差分）
- [ ] **3. EventBus 事务边界**：事件 emit 在 `stateStore.update` 事务外（参照 `flushPendingEvents` 模式，rules/pr-review-checklist.md 已有条目）
- [ ] **4. 确定性 RNG**：一律 `GameRngManager.getRng(RngPartition.xxx)`，禁止 `kotlin.random.Random`；新增分区需论证独立性（世界事件/活动掉宝不可与战斗共用分区）
- [ ] **5. UI 入口注册**：走 `DialogType` 注册 + `GameOverlayHost` when 穷举分支，或 MainTab 注册；有入口的活动走历战卡片轮转注册（`LizhanDialog` 卡片列表 + 图标 + 描述）
- [ ] **6. 存储与存档**：新表/新字段走完整 Migration（rules/database-migration.md 建表规范）+ 存档向前兼容 + SaveValidator 规则注册（`SaveValidationRuleRegistry.registerDefaults()`）
- [ ] **7. 进度锚定唯一权威时间轴**：新系统进度一律锚定权威游戏毫秒轴 `elapsedGameMs`（现实时间连续积分，INV-1），游戏日历（年/月/旬）只是它的派生投影——**禁止另立第二套时间真相源**（自维护墙钟计时器、自算"游戏时刻"等均属走私）；现实时长 → 游戏时长的换算必须走 `GameConfig.Time` 常量栈（1 旬 = 2000 游戏毫秒，C++ 同源锚点 `time_units.h`，改值双端同步）。参照秘境"50 年一现"模式（年数经常量栈换算，非自读墙钟）
- [ ] **8. 新手引导接入**：新玩法接入 GuideTask 注册（`GuideCounterKeys` 常量 + 计数器接入点），首屏 90 秒内呈现钩子（FTUE——行业数据：D1 中位数留存 22%、超 50% 用户首日流失）
- [ ] **9. 配置化启停开关**：功能模块可配置开关启停（rules/design-plan-review.md 第零节 原则 2 落地；未来 RemoteConfig 下发——见 rules/commercialization.md）
- [ ] **10. 守卫测试**：新增枚举/注册表项必须配守卫测试（AGENTS.md 9.5 三要素），测试失败信息直接指出需同步的 N 处
- [ ] **11. UI 组件复用优先**：新玩法 UI 必须优先复用现有组件（见下文"UI 组件复用优先"清单），禁止自建重复组件

## UI 组件复用优先（🔴 2026-08-04 起）

**新增玩法 UI 必须优先使用游戏内已有组件（按钮/对话框/卡片/输入框等），禁止自建重复组件。** 现有共享组件清单（`core/ui` 模块，跨模块复用）：

| 组件 | 用途 |
|------|------|
| `GameButton` | 统一按钮（尺寸用 `ButtonSizes`：72dp × 38dp，AGENTS.md 11.1） |
| `UnifiedGameDialog` | 半屏/全屏对话框容器（自带 60% 遮罩 + DialogSystemBarGuard + DialogSoftInputGuard） |
| `StandardPromptDialog` / `InlineStandardPromptDialog` | 标准提示框 / 内联提示框（含输入框容器） |
| `SmallScreenDialog` | 小屏对话框 |
| `ItemCard` / `GridRow` | 物品卡片 / 网格行（物品/背包/商店界面统一卡片） |
| `CircularCheckbox` | 圆形勾选框（开关/多选/筛选） |
| `ClickableWithSound` / `LocalPlayClickSound` | 带按钮音效的点击（SFX 统一入口） |
| `AudioToggleRow` | 设置项开关行 |
| `SpriteImage` / `EquipmentSprite` | 精灵图 / 装备精灵显示（禁止直接 `R.drawable`，rules/static-resources.md） |
| `RewardDisplayDialog` | 奖励发放展示 |
| `ProgressAnimation` | 进度动画 |
| `DialogFocusGuard` / `DialogLifecycle` / `DialogState` | 对话框基础（焦点/生命周期门控/状态） |
| `ItemDetailDialog` / `WatchItemButton` | 物品详情（关注/效果展示） |
| `RewardCardHost` / `GameActionButtons` / `MessageListContent` | 奖励卡 / 主界面操作按钮区 / 消息栏内容 |

**规则：**

1. 新增玩法 UI 先对照本清单：能用现有组件**绝不新建**（列表 → `LazyColumn` + 稳定 key；按钮 → `GameButton`；对话 → `UnifiedGameDialog`；物品 → `ItemCard`；勾选 → `CircularCheckbox`；音效 → `ClickableWithSound`）
2. 现有组件不能满足时：先评估**扩展既有组件**（加参数/组合）→ 仍不满足才新建
3. 新建的通用组件必须放 `core/ui` 模块（跨模块共享）并**登记回本清单**，禁止把通用组件私有化到业务模块
4. 新增交互容器/对话框必须遵循 `rules/new-dialog-checklist.md` 标准流程（DialogType 注册 + when 穷举）

## 事件/内容设计准则（🟡 供设计参考，行业对标）

- **事件库 + 条件组合**：万级碎片事件 + 随机组合产生涌现式叙事（鬼谷八荒模式：文本密度 + 玩家脑补 = 最低成本叙事）。**警示：玩法不能完全让文本背锅**——随机事件必须搭配数值验证出口（战斗/排行等）
- **事件连锁与叙述者节奏**：事件产生持续后果、引发关联新事件（RimWorld Spiralling events）；以"张弛交替"节奏编排威胁与休整（RimWorld Cassandra 叙述者模式）
- **世界事件模板**：强制条件 + 可变区间（如"宗门等级 ≥ 2 且年份 ∈ [50, 70] 触发"），制造"世界在运转"感（参照秘境每 50 年一现的雏形）
- **成长错峰**：新系统收益曲线与既有 10 系统错峰（多进度系统错峰原则——AFK 剑与远征：玩家在某个模式卡关时可切换其他模式获得推进感）

## 离线收益（✅ 已定稿，2026-09-28 实时结算线 B7 落地）

- **现状基线**：离线收益已落地——关闭游戏期间宗门按上限规则继续累积连续收益，读档时一次性注入结算；后台运行仍纯暂停（后台不计时，离线计量以读档墙钟差为准）
- **口径（已拍板，见 `rules/economy-design.md` §4）**：≤12 现实小时全额（1x 速率，与在线速度档解耦）→ 12–24h 段按 50% → 24h 硬顶（注入总量上限 = 18h 游戏时间）；结果 floor 到旬；时钟回拨按 0
- **接入点（已建成，新离线产出项必须复用）**：读档时 `GameEngineCoreOfflineOps` 两段式注入（staging = boot Step 6.5 折算 → consume = `ensureAuthoritativeNative` 尾部）→ C++ `GameCore::injectOfflineGameMs`（L1 连续积分 + 月度连续积分 + 三轴同步跳变）；**判定轨 0 次 / RNG 零消耗**（离线无判定无事件）。仍**禁止另起结算循环**——新收益项挂进 `accrueContinuous`/`accrueMonthlyContinuous` 积分函数族即可被离线复算
- **经济审计**：离线收益是纯新增源，接入新产出项必须过经济审计并登记 `docs/knowledge-base.md` 经济基线表；"离线收益领取间隔"类频控预留见 `rules/ad-cooldown.md` 预留节

## 行业依据

- RimWorld GDC 2017《Contrarian, Ridiculous, and Impossible Game Design Methods》：https://gdcvault.com/play/1024232/（故事生成器/叙述者节奏/功能删减三问）
- 《鬼谷八荒 · 修仙文本的涌现式叙事》案例（万级事件库 + NPC 关系网）
- 知乎《开放世界游戏的事件设计思考》：https://zhuanlan.zhihu.com/p/594151953（事件设计四层级：分支/网络/全局/沙盒）
- 《海外分析师：稳居放置RPG榜首，〈剑与远征：启程〉能学到什么？》：http://www.gamelook.com.cn/2024/11/558473/（挂机收益设计/多进度错峰）
- GameRes《越玩越无聊？论放置修仙游戏的长线困局》：https://www.gameres.com/913019.html（内容消耗快于迭代的警示）
- Supersonic《3分钟征服用户的"黄金FTUE"设计法则》：http://www.nadianshi.com/2025/06/390411（90 秒钩子）
- GameIndustryLibrary《2025 Mobile Gaming Benchmarks》：https://gameindustrylibrary.com/documents/mobile-gaming-benchmarks-2025（留存基准 D1 22%）
