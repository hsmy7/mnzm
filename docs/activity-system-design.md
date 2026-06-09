# 活动系统设计方案

> **版本**: v1.0  
> **日期**: 2026-06-08  
> **状态**: 待实施  
> **参考来源**: 24 条（S级 7 / A级 7 / B级 10）

---

## 一、概述

### 1.1 需求摘要

在游戏主界面「种植」按钮下方新增「活动」按钮，点击后弹出全屏活动界面。界面采用 2:8 左右分栏（参考邮件界面的列表+详情模式），左侧显示活动名称列表，右侧默认显示第一个活动的内容（无活动时显示「暂无活动」）。标题为「活动」，右侧有关闭按钮。

### 1.2 设计原则

基于行业调研，本方案遵循以下原则：

| 原则 | 来源依据 |
|------|----------|
| **数据驱动活动投放** — 活动配置与代码分离，通过数据定义活动而非硬编码 | Sensor Tower 2025 报告：顶级游戏均采用 server/config-driven 活动架构 |
| **渐进式复杂度** — 先实现活动框架（骨架），具体活动内容后续填充 | Friendly GameDev 2025：Gossip Harbor 从 20→100 活动/月分三阶段演进 |
| **类型化活动系统** — 活动按时间维度分类（日常/周常/限时），统一的状态机管理 | Adjust 2025：分类化管理是活动运营的基础设施 |
| **列表+详情双栏布局** — 符合 Material Design 3 规范的标准 list-detail 模式 | Android Developers 官方文档 (2024-2025) |
| **活动入口可见性** — 按钮放置在主界面固定位置，不隐藏在深层菜单 | 一念逍遥 (2025)：活动入口置于主界面核心区域 |

---

## 二、行业调研摘要

### 2.1 市场背景

- 2025 年全球手游市场达 **$103-108B**，其中 **84% 的 IAP 收入**来自有 LiveOps 的游戏（Newzoo 2025 / Adjust 2025）
- **78% 的头部 LiveOps 游戏**在 2025 H1 收入下滑，仅少数有纪律化运营的游戏增长（Sensor Tower 2025）
- 头部游戏平均每月活动数从 **73 → 89**，活动密度持续增加（Friendly GameDev / AppMagic 2025）
- 成功案例：Whiteout Survival（$2.2B）、Last War: Survival（$2.4B）、Gossip Harbor（$750M+）（Sensor Tower 2025）

### 2.2 活动系统设计对标

| 游戏 | 活动入口位置 | 界面布局 | 活动类型 | 参考价值 |
|------|-------------|---------|---------|---------|
| **原神** | 主界面右侧固定图标 → 活动总览页 | 垂直列表+大图卡片 | 版本主题活动、限时挑战、签到福利 | 高 — 固定入口+分类筛选 |
| **崩坏：星穹铁道** | 主界面右侧「旅情事记」 | 时间轴+卡片列表 | 版本活动、常驻活动、限时活动 | 高 — 时间轴可视化 |
| **一念逍遥** | 主界面顶部活动图标 | 卡片网格 | 宗门活动、限时活动、日常福利 | 高 — 同品类修仙游戏 |
| **Whiteout Survival** | 主界面右侧活动中心 | 左右分栏 | 联盟战、Lucky Wheel、季度通行证 | 中 — 分层活动系统 |
| **Merge Mansion** | 主界面底部活动入口 | 卡片轮播 | Mini Battle Pass、收集活动、装饰活动 | 中 — 活动日历化 |

### 2.3 关键洞察

1. **活动入口固定化**：所有头部游戏都将活动入口放在主界面的固定位置，不使用汉堡菜单或深层导航
2. **左右分栏是主流**：列表+详情的双栏布局在移动端活动系统中最为常见（邮件、任务、成就、活动均使用此模式）
3. **活动状态机标准化**：活动有明确的生命周期（预告期→进行中→结算期→已结束），需要统一的状态管理
4. **奖励投放分层**：基于活跃度/付费能力的分层奖励是 2025 年的核心趋势（GameAnalytics 2025）

---

## 三、活动系统架构设计

### 3.1 活动数据模型

```kotlin
// 活动定义（配置层）
data class ActivityDef(
    val id: String,                    // 唯一标识
    val name: String,                  // 活动名称（显示在左侧列表）
    val description: String,           // 活动描述
    val type: ActivityType,            // 活动类型
    val startTime: Long,               // 开始时间（epoch millis）
    val endTime: Long,                 // 结束时间（epoch millis）
    val rewardPreview: List<RewardPreviewItem>, // 奖励预览
    val status: ActivityStatus,        // 活动状态
    val sortOrder: Int,                // 排序权重
    val iconRes: String?               // 活动图标资源名（可选）
)

// 活动类型枚举
enum class ActivityType {
    DAILY,          // 日常活动（每日刷新）
    WEEKLY,         // 周常活动（每周刷新）
    LIMITED,        // 限时活动（固定时间段）
    SEASONAL,       // 季节/节日活动
    BEGINNER,       // 新手活动
    SPECIAL         // 特殊活动
}

// 活动状态枚举
enum class ActivityStatus {
    UPCOMING,       // 预告期
    ACTIVE,         // 进行中
    CLAIMABLE,      // 可领奖（活动结束但奖励未领）
    EXPIRED         // 已过期
}

// 奖励预览项
data class RewardPreviewItem(
    val itemId: String,
    val name: String,
    val quantity: Int,
    val rarity: Int
)

// 活动进度（玩家维度，持久化到 Room）
@Entity(tableName = "activity_progress")
data class ActivityProgress(
    @PrimaryKey val activityId: String,
    val slotId: Int,                   // 存档槽位
    val currentProgress: Int,          // 当前进度
    val targetProgress: Int,           // 目标进度
    val isClaimed: Boolean,            // 是否已领取
    val completedAt: Long?,            // 完成时间
    val participationCount: Int        // 参与次数
)
```

### 3.2 活动状态机

```
                ┌─────────┐
                │ UPCOMING │  ← 活动已创建但未到开始时间
                └────┬─────┘
                     │ startTime <= now
                     ▼
                ┌─────────┐
                │  ACTIVE  │  ← 活动进行中
                └────┬─────┘
                     │ endTime <= now
                     ▼
              ┌──────────┐
              │ CLAIMABLE │  ← 活动结束，奖励可领（缓冲期）
              └────┬─────┘
                   │ 领取奖励 or 过期时间到
                   ▼
              ┌─────────┐
              │ EXPIRED  │  ← 活动彻底结束
              └─────────┘
```

### 3.3 活动配置化架构

遵循行业最佳实践，活动定义与代码分离：

```
活动数据流:
┌────────────────────┐
│ BuiltinActivityConfig│  ← 内置活动配置（JSON，当前阶段）
│ (内置 JSON 配置)      │
└────────┬───────────┘
         │ 加载
         ▼
┌────────────────────┐
│ ActivityRepository  │  ← 活动数据仓库（统一入口）
└────────┬───────────┘
         │ 提供给
         ▼
┌────────────────────┐
│ ActivityViewModel   │  ← 活动 ViewModel（管理 UI 状态）
└────────┬───────────┘
         │ 驱动
         ▼
┌────────────────────┐
│ ActivityDialog      │  ← 活动界面（Compose UI）
└────────────────────┘
```

**当前阶段**：内置配置。后续可演进为远端配置（server-driven），无需修改客户端代码即可更新活动。

### 3.4 活动配置文件结构

```json
// assets/activities.json（内置活动配置）
{
  "version": 1,
  "activities": [
    {
      "id": "daily_sign_in",
      "name": "每日签到",
      "description": "每日登录签到领取丰厚奖励",
      "type": "DAILY",
      "sortOrder": 1
    },
    {
      "id": "weekly_mission", 
      "name": "周常任务",
      "description": "完成每周任务获取修炼资源",
      "type": "WEEKLY",
      "sortOrder": 2
    }
  ]
}
```

**当前阶段**：`getAllActivities()` 返回空列表（暂无活动）。ActivityDialog 需要在 `remember` 中检测：若列表非空则默认选中第一项，若为空则右侧显示「暂无活动」。

### 3.4 默认选中行为

```kotlin
// ActivityDialog 中的默认选中逻辑
val activities = remember { BuiltinActivityConfig.getAllActivities() }
var selectedActivityId by remember {
    mutableStateOf(activities.firstOrNull()?.id)  // 有活动时默认选中第一个
}

// 右侧显示逻辑：
// - selectedActivity != null → 显示活动详情
// - selectedActivity == null → 显示「暂无活动」
```

---

## 四、UI 设计方案

### 4.1 按钮位置

活动按钮位于 `GameActionButtons` 组件中，「种植」按钮下方。

```
现有按钮布局：              新增后：
┌──────────────┐          ┌──────────────┐
│  邮件 日志   │          │  邮件 日志   │
│  商人 招募   │          │  商人 招募   │
│  建造 仓库   │          │  建造 仓库   │
│  设置        │          │  设置        │
├──────────────┤          ├──────────────┤
│  弟子        │          │  弟子        │
│  世界        │          │  世界        │
│  外交        │          │  外交        │
│  种植        │          │  种植        │
│              │          │  活动  ← NEW │
└──────────────┘          └──────────────┘
```

使用素材：`D:\模拟宗门美术素材\活动按钮.png`

### 4.2 活动界面布局（全屏，2:8 分栏）

```
┌──────────────────────────────────────────────────────┐
│  ← 活动              活动中心                  [关闭] │  ← 标题栏
├──────────┬───────────────────────────────────────────┤
│          │                                           │
│ 活动列表 │              活动详情区                    │
│ (2/10)   │              (8/10)                       │
│          │                                           │
│ ┌──────┐ │  ┌─────────────────────────────────────┐ │
│ │活动1  │ │  │ 活动名称                            │ │
│ │(选中) │ │  │ 活动时间：2026.06.08 - 06.15       │ │
│ └──────┘ │  │                                     │ │
│ ┌──────┐ │  │ 活动描述正文...                      │ │
│ │活动2  │ │  │                                     │ │
│ └──────┘ │  │ 奖励预览：                           │ │
│ ┌──────┐ │  │ [奖励1] [奖励2] [奖励3]              │ │
│ │活动3  │ │  │                                     │ │
│ └──────┘ │  │ [参与/领取按钮]                      │ │
│          │  └─────────────────────────────────────┘ │
│          │                                           │
│          │           暂无活动                         │
│          │         （无活动时居中显示）                │
│          │                                           │
└──────────┴───────────────────────────────────────────┘
```

**关键设计参数**：
- 左侧列表宽度：`Modifier.weight(0.2f)` = 2/10
- 右侧详情宽度：`Modifier.weight(0.8f)` = 8/10
- 分隔线：1dp 灰色竖线（`Color(0xFFBDBDBD)`）
- 列表项背景：使用 `bg_dialog_mail` 或同类素材
- 详情区背景：`Color(0xFFF6EBD5)` PanelBg 色
- 所有文字颜色：`Color.Black`（严格遵循项目规范）
- 标题栏文字：「活动」，14sp Bold

### 4.3 空态与默认行为

**右侧默认行为**：
- 有活动时：自动选中第一个活动，右侧展示该活动详情
- 无活动时：右侧居中显示「暂无活动」（12sp, Color.Black）

**左侧列表**：
- 有活动时：显示活动名称列表，点击切换右侧详情
- 无活动时：左侧区域留空，不显示任何占位文字

---

## 五、实施计划

### 5.1 涉及文件清单

| 操作 | 文件路径 | 说明 |
|------|----------|------|
| **新增** | `core/model/ActivityDef.kt` | 活动数据模型 |
| **新增** | `core/config/BuiltinActivityConfig.kt` | 内置活动配置（初始为空） |
| **新增** | `data/local/ActivityProgressEntity.kt` | 活动进度 Room Entity（预留） |
| **新增** | `ui/game/ActivityViewModel.kt` | 活动 ViewModel |
| **新增** | `ui/game/dialogs/ActivityDialog.kt` | 活动界面 Compose |
| **新增** | `res/drawable-nodpi/ui_activity_button.webp` | 活动按钮素材 |
| **修改** | `ui/navigation/GameRoute.kt` | 添加 `DialogRoute.Activity` |
| **修改** | `ui/game/components/GameActionButtons.kt` | 添加活动按钮 |
| **修改** | `ui/game/components/GameOverlayHost.kt` | 添加 ActivityDialog 渲染 |
| **修改** | `ui/game/GameViewModel.kt` | 添加活动相关导航方法（如需要） |

### 5.2 实施步骤

#### 步骤 1：准备美术素材

```
将 D:\模拟宗门美术素材\活动按钮.png
复制/转换为 → android/app/src/main/res/drawable-nodpi/ui_activity_button.webp
```

**验证**：R.drawable.ui_activity_button 可被 IDE 识别

#### 步骤 2：创建数据模型

创建 `core/model/ActivityDef.kt`，包含：
- `ActivityType` 枚举（DAILY, WEEKLY, LIMITED, SEASONAL, BEGINNER, SPECIAL）
- `ActivityStatus` 枚举（UPCOMING, ACTIVE, CLAIMABLE, EXPIRED）
- `ActivityDef` 数据类
- `RewardPreviewItem` 数据类

**验证**：编译通过 `./gradlew.bat compileReleaseKotlin`

#### 步骤 3：创建活动配置

创建 `core/config/BuiltinActivityConfig.kt`，提供：
- `getAllActivities(): List<ActivityDef>` — 返回空列表（当前暂无活动）
- 预留后续从 JSON 加载活动的能力

**验证**：编译通过

#### 步骤 4：注册导航路由

在 `ui/navigation/GameRoute.kt` 中：
- `GameRoute` 添加 `object Activity : GameRoute("activity")`
- `DialogRoute` 添加 `object Activity : DialogRoute()`
- `toDialogRoute()` 方法添加对应映射

**验证**：编译通过

#### 步骤 5：添加活动按钮

在 `ui/game/components/GameActionButtons.kt` 中：
- 在「种植」按钮下方（Column 末尾）添加活动按钮
- 使用 `ui_activity_button` 作为图标
- 点击回调：`viewModel.navigateToDialog(DialogRoute.Activity)`

```kotlin
FloatingActionButton(
    text = "活动",
    drawableRes = R.drawable.ui_activity_button
) { viewModel.navigateToDialog(DialogRoute.Activity) }
```

**验证**：编译通过，运行时按钮显示在种植下方

#### 步骤 6：创建 ActivityViewModel

创建 `ui/game/ActivityViewModel.kt`：
- 继承 `BaseViewModel`
- 暴露 `activities: StateFlow<List<ActivityDef>>`
- 暴露 `selectedActivityId: StateFlow<String?>`
- 初始化时自动选中第一个活动（如有）
- 方法：`selectActivity(id: String)`

```kotlin
@HiltViewModel
class ActivityViewModel @Inject constructor() : BaseViewModel() {
    private val _activities = MutableStateFlow<List<ActivityDef>>(emptyList())
    val activities: StateFlow<List<ActivityDef>> = _activities.asStateFlow()
    
    private val _selectedActivityId = MutableStateFlow<String?>(null)
    val selectedActivityId: StateFlow<String?> = _selectedActivityId.asStateFlow()
    
    init {
        _activities.value = BuiltinActivityConfig.getAllActivities()
        // 有活动时默认选中第一个
        _selectedActivityId.value = _activities.value.firstOrNull()?.id
    }
    
    fun selectActivity(id: String) {
        _selectedActivityId.value = id
    }
}
```

**验证**：编译通过

#### 步骤 7：创建 ActivityDialog

创建 `ui/game/dialogs/ActivityDialog.kt`，参照 `MailDialog.kt` 结构：

核心结构：
```
Surface(fillMaxSize, PageBackground)
  ├── 背景图 bg_horizontal
  ├── Column(fillMaxSize, padding(horizontal=32dp))
  │   ├── 标题栏 Row
  │   │   ├── Text("活动", 14sp, Bold, Black)
  │   │   ├── Spacer(weight=1f)
  │   │   └── CloseButton(onClick=onDismiss)
  │   └── Row(weight=1f) ← 2:8 分栏
  │       ├── 左侧列 Column(weight=0.2f)
  │       │   ├── [活动列表，无活动时留空]
  │       │   └── [列表项使用 bg_dialog_mail 背景]
  │       ├── 分隔线 Box(width=1dp, fillMaxHeight, Color(0xFFBDBDBD))
  │       └── 右侧列 Column(weight=0.8f)
  │           ├── [活动详情 或 "暂无活动"空态]
  │           └── 详情区使用 PanelBg 背景
```

**验证**：编译通过，运行时界面正确显示

#### 步骤 8：注册到 GameOverlayHost

在 `ui/game/components/GameOverlayHost.kt` 中：
- 添加 `DialogRoute.Activity` 分支
- 参照 `DialogRoute.Mail` 的处理方式

```kotlin
is DialogRoute.Activity -> {
    ActivityDialog(
        viewModel = viewModel,
        onDismiss = onDismiss
    )
}
```

**验证**：编译通过，点击活动按钮可弹出界面

#### 步骤 9：端到端测试

1. 启动游戏
2. 确认「活动」按钮在「种植」按钮下方
3. 点击「活动」按钮 → 弹出全屏界面
4. 界面显示「活动」标题和关闭按钮
5. 左侧列表为空（无占位文字），右侧居中显示「暂无活动」
6. 点击关闭按钮 → 返回主界面
7. （后续有活动时）验证默认选中第一个活动，点击左侧列表可切换

### 5.3 验证清单

- [ ] `./gradlew.bat compileReleaseKotlin` 通过
- [ ] 活动按钮位置正确（种植按钮下方）
- [ ] 活动界面全屏显示
- [ ] 2:8 左右分栏布局正确
- [ ] 标题「活动」显示正确，关闭按钮可用
- [ ] 无活动时：左侧留空，右侧居中显示「暂无活动」
- [ ] 所有文字颜色为 `Color.Black`
- [ ] 关闭按钮功能正常
- [ ] 后台返回键可关闭界面
- [ ] `./gradlew.bat test` 通过（无回归）

---

## 六、后续扩展预留

当前方案为活动系统框架，预留以下扩展点：

| 扩展项 | 预留方式 | 说明 |
|--------|----------|------|
| 活动内容展示 | `ActivityDef.description` 支持富文本 | 右侧详情面板可扩展为 WebView 或 Markdown 渲染 |
| 活动奖励系统 | `RewardPreviewItem` + `ActivityProgress` Entity | 后续可接领取/发放逻辑 |
| 活动进度追踪 | `ActivityProgress` Room Entity（已预留表结构） | 日常/周常活动的进度持久化 |
| 远程活动配置 | `BuiltinActivityConfig` → 可替换为 Retrofit 加载 | 无需客户端更新即可推送新活动 |
| 活动红点提示 | `unreadCount` 机制参照邮件系统 | 有新活动时按钮显示红点角标 |
| 活动分类筛选 | `ActivityType` 枚举 | 后续可在左侧列表顶部添加分类 Tab |
| 活动时间轴 | `startTime` / `endTime` 字段 | 右侧可展示活动时间线 |

---

## 七、参考来源

| # | 等级 | 标题 | URL | 发布日期 |
|---|------|------|-----|----------|
| 1 | **S** | Newzoo Global Games Market Report 2025 | https://newzoo.com/resources/trend-reports/newzoo-global-games-market-report-2025 | 2025 |
| 2 | **S** | Sensor Tower — Winning with Live Ops: Insights from Top-Grossing Games | https://sensortower.com/blog/top-grossing-mobile-games-live-ops-strategies-2025-report | 2025 |
| 3 | **S** | Sensor Tower China — 破解运营活动密码 | https://sensortower-china.com/zh-CN/blog/cracking-mobile-gaming-live-ops-2025-CN | 2025 |
| 4 | **S** | Adjust — Live-ops for mobile games: strategy and best practices | https://www.adjust.com/blog/what-is-live-ops/ | 2024-2025 |
| 5 | **S** | Android Developers — 构建列表-详情布局 (List-Detail) | https://developer.android.com/develop/ui/compose/layouts/adaptive/list-detail | 2024-2025 |
| 6 | **S** | Android Developers — Canonical Layouts (Material Design 3) | https://developer.android.com/develop/ui/compose/layouts/adaptive/canonical-layouts | 2024-2025 |
| 7 | **S** | Sensor Tower — 2025 年全球畅销手游运营活动洞察 | https://go.sensortower-china.com/rs/351-RWH-315/images/2025年全球畅销手游运营活动洞察.pdf | 2025 |
| 8 | **A** | GameAnalytics — Player-centric LiveOps (Sergei Vasiuk) | https://www.gameanalytics.com/blog/player-centric-liveops | 2025 |
| 9 | **A** | GameAnalytics — From Metrics to Mastery: Tracking What Matters in LiveOps | https://www.gameanalytics.com/blog/what-matters-in-liveops | 2025 |
| 10 | **A** | PocketGamer.biz — Cutting the wait: How game teams are accelerating live ops | https://www.pocketgamer.biz/cutting-the-wait-how-game-teams-are-accelerating-live-ops/ | 2025 |
| 11 | **A** | PocketGamer.biz — SciPlay on why your live ops strategy will make or break your mobile game | https://www.pocketgamer.biz/sciplay-on-why-your-live-ops-strategy-will-make-or-break-your-mobile-game/ | 2025 |
| 12 | **A** | App Developer Magazine — Cracking the Live Ops Code (Sensor Tower/Playliner) | https://appdevelopermagazine.com/cracking-the-live-ops-code/amp/ | 2025-08 |
| 13 | **A** | Game Refinery — How Metacore Scaled Merge Mansion With a Stellar Live Event Strategy | https://www.gamerefinery.com/how-metacore-scaled-merge-mansion-with-a-stellar-live-event-strategy/ | 2025 |
| 14 | **A** | Friendly GameDev — How LiveOps Worked in 2025 | https://friendlygamedev.com/blog/how-liveops-worked-in-2025/ | 2025 |
| 15 | **B** | GameRes — 如何写好游戏系统策划案 | https://www.gameres.com/904506.html | 2024-01 |
| 16 | **B** | GameRefinery — Episode 21: Seasonal Events in Mobile Games | https://www.gamerefinery.com/episode-21-seasonal-events-in-mobile-games/ | 2024 |
| 17 | **B** | Friendly GameDev — Gossip Harbour's LiveOps Strategy | https://friendlygamedev.com/blog/gossip-harbours-liveops-strategy/ | 2025 |
| 18 | **B** | PlayStation Universe — The Slot Machine Psyche: Variable Ratio Reinforcement | https://www.psu.com/news/the-slot-machine-psyche-how-variable-ratio-reinforcement-drives-modern-gaming-engagement/ | 2025 |
| 19 | **B** | iXie Gaming — Mastering LiveOps in 2025 | https://www.ixiegaming.com/blog/mastering-liveops-in-2025/ | 2025 |
| 20 | **B** | Unity Discussions — Unity Architecture: Scriptable Object Pattern (2024) | https://discussions.unity.com/t/unity-architecture-scriptable-object-pattern/1568508 | 2024-12 |
| 21 | **B** | Raxis Studio — Unity Modular Design: Future-Proof Your Game Systems | https://www.raxisstudio.com/blog-1/future-proofing-your-game-why-modular-design-matters-for-indie-devs | 2024 |
| 22 | **B** | Under30CEO — Secret to Retention: How Game Design Keeps Players Coming Back | https://www.under30ceo.com/secret-to-retention-how-game-design-keeps-players-coming-back/ | 2025-11 |
| 23 | **B** | Gamigion — LiveOps Do and Don'ts | https://www.gamigion.com/liveops-do-and-donts/ | 2024-2025 |
| 24 | **B** | GoodTK — 游戏运营进阶：运营活动设计/解析思路 | http://www.goodtk.net/post/103 | 2024 |

> **来源等级说明**：S级 = 官方文档/行业报告（7条），A级 = 头部产品技术博客/知名团队复盘（7条），B级 = 高质量社区文章（10条）

---

## 八、方案总结

本方案设计了一个**可立即实施的、最小化的活动系统框架**：

1. **按钮**：在种植按钮下方新增活动按钮，使用指定素材 `D:\模拟宗门美术素材\活动按钮.png`
2. **界面**：全屏 2:8 分栏布局，参照邮件界面，符合 Material Design 3 list-detail 规范
3. **数据**：活动定义通过配置驱动，当前为空（暂无活动），后续可扩展
4. **默认行为**：有活动时自动选中第一个显示内容；无活动时左侧留空、右侧居中显示「暂无活动」
5. **预留**：为活动进度、奖励领取、远程配置等后续功能预留了扩展点

整个方案约需 **10 个文件**（8 新增 + 2 修改），预计实施时间 **2-4 小时**（含测试验证），可由一名 Android 开发者独立完成。
