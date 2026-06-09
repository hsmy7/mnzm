# 每日签到 — UI 设计方案

## 概述

在游戏中新增每日签到功能，以独立 Dialog 形式展示。左侧为活动标题区，右侧为签到日历网格，下方为签到按钮。

---

## 一、整体布局

```
┌─────────────────────────────────────────────────────┐
│  [关闭]                    活动标题                  │
├──────────┬──────────────────────────────────────────┤
│          │   1    2    3    4    5    6    7        │
│  每      │  ┌───┐┌───┐┌───┐┌───┐┌───┐┌───┐┌───┐  │
│  日      │  │卡 ││卡 ││卡 ││卡 ││卡 ││卡 ││卡 │  │
│  签      │  │片 ││片 ││片 ││片 ││片 ││片 ││片 │  │
│  到      │  └───┘└───┘└───┘└───┘└───┘└───┘└───┘  │
│          │   8    9   10   11   12   13   14        │
│          │  ┌───┐┌───┐┌───┐┌───┐┌───┐┌───┐┌───┐  │
│          │  │...││...││...││...││...││...││...│  │
│          │  └───┘└───┘└───┘└───┘└───┘└───┘└───┘  │
│          │   ...                                   │
│          │  29   30   31                           │
│          │  ┌───┐┌───┐┌───┐                       │
│          │  │...││...││...│                       │
│          │  └───┘└───┘└───┘                       │
│          ├──────────────────────────────────────────┤
│          │          [ 签 到 ]                       │
│          │          (按钮居中)                       │
└──────────┴──────────────────────────────────────────┘
```

- 左侧面板：垂直居中显示"每日签到"文字
- 右侧面板：日历网格（7 列 × 多行）+ 底部签到按钮
- 卡片间距：4dp

---

## 二、日期格子（物品卡片）

### 2.1 基础结构

复用 `UnifiedItemCard` 组件（`ItemCard.kt`），尺寸 60dp，圆角 6dp。

```
┌──────────────────┐
│ ① 日期数字(白色)  │  ← 精灵图区域（rarity 颜色背景）
│                  │
│    [精灵图]      │
│                  │
│  数量(右下角)     │
├──────────────────┤
│   物品名称       │  ← 白色背景区域，14dp 高
└──────────────────┘
```

### 2.2 日期数字

- 位置：精灵图区域左上角（与现有 `alignment = TopStart` 一致）
- 字体大小：9sp（与"锁定"标签一致）
- 字体粗细：`FontWeight.Bold`
- 颜色：`Color.White`
- Padding：`start = 3.dp, top = 2.dp`

**实现方式**：在 `UnifiedItemCard` 的精灵图 Box 内，新增一个 `Text` 组件，使用 `Modifier.align(Alignment.TopStart)`，放在与"锁定"标签相同的位置但前置。

### 2.3 物品名称

- 位于卡片底部白色区域（现有 `UnifiedItemCard` 已有此区域，14dp 高）
- 字体大小：9sp（与现有一致）
- 颜色：`Color.Black`（与现有一致）

---

## 三、签到奖励配置

奖励按星期几分配，不随月份变化：

| 星期 | 物品 | 数量 | 类型 | rarity |
|------|------|------|------|--------|
| 周一 | 灵石 | 10000 | spiritStones | 1 |
| 周二 | 凡虎血 | 20 | material (beastMaterial) | 1 |
| 周三 | 凡品储物袋 | 1 | storageBag | 1 |
| 周四 | 灵石 | 10000 | spiritStones | 1 |
| 周五 | 引灵丹 | 5 | pill | 1 |
| 周六 | 悟法丹 | 5 | pill | 1 |
| 周日 | 灵品储物袋 | 1 | storageBag | 2 |

> **注意**：日历 1 号需要根据实际月份计算是星期几，然后每个日期格子取对应星期几的奖励。

---

## 四、卡片状态

日历中每个日期格子有 5 种状态：

### 4.1 未来日期（FUTURE）
- 外观：正常卡片显示（精灵图 + 名称 + 数量 + 日期数字）
- 边框：默认灰色 `GameColors.Border` (2dp)
- 交互：不可点击

### 4.2 今日未签到（TODAY_UNCLAIMED）
- 外观：正常卡片显示
- 边框：**金色** `GameColors.Gold` (3dp，与 UnifiedItemCard `isSelected` 的 gold 一致)
- 交互：点击签到按钮后领取

### 4.3 今日已签到（TODAY_CLAIMED）
- 外观：精灵图区域替换为浅灰背景
- 精灵图区域显示绿色"已领"文字（`Color(0xFF4CAF50)`，9sp，Bold）
- 右下角仍显示数量（白字，与 MailDialog `ClaimedAttachmentCard` 一致）
- 边框：默认灰色 `GameColors.Border`（已领后金色消失）

### 4.4 过去已签到（PAST_CLAIMED）
- 外观：同 4.3（精灵图区域显示"已领"）
- 边框：默认灰色

### 4.5 过去未签到（MISSED）
- 外观：精灵图区域替换为浅灰背景
- 精灵图区域显示红色"未领"文字（`GameColors.Error = Color(0xFFF44336)`，9sp，Bold）
- 右下角仍显示数量
- 边框：默认灰色
- **注意**：没有补签机制，漏签即为最终状态

---

## 五、签到按钮

- 位置：日历网格下方，水平居中
- 标签："签到"
- 使用现有 `GameButton` 组件
- **可签到状态**（今日未签到时）：正常按钮样式，点击执行签到
- **不可签到状态**（今日已签到后）：灰色（disabled），不可点击
- 点击签到后：
  1. 发放当前日期对应奖励到背包
  2. 今日卡片状态从 TODAY_UNCLAIMED → TODAY_CLAIMED
  3. 按钮变为不可点击

---

## 六、日历计算逻辑

1. 获取当月总天数（28/29/30/31，根据实际月份）
2. 获取当月 1 号是星期几（0=周日, 1=周一, ..., 6=周六）
3. 根据星期几确定每个日期的奖励（查表 3 中的配置）
4. 第一行可能需要留空（如果 1 号不是周日的话）
5. 只显示 1 号到当月最后一天

**日期星期映射**（Java `Calendar.MONDAY` = 2 = 周一）：
```kotlin
// 输入：dayOfMonth (1-31), firstDayOfWeek (1=周日..7=周六)
// 输出：weekday (1=周一..7=周日)
val weekday = ((dayOfMonth - 1 + firstDayOfWeek - 1) % 7) + 1
// 其中 firstDayOfWeek: 1=周日, 2=周一, ..., 7=周六
```

---

## 七、数据模型

```kotlin
// 签到状态
data class SignInState(
    val claimedDays: Set<Int> = emptySet(),  // 本月已领取的日期 (1-31)
    val currentMonth: Int = 0,               // 当前月份 (用于检测月份变更)
    val currentYear: Int = 0                 // 当前年份
)

// 每日奖励定义
data class DailySignInReward(
    val weekday: Int,           // 1=周一..7=周日
    val itemName: String,       // 物品中文名
    val quantity: Int,          // 数量
    val type: String,           // spiritStones / material / pill / storageBag
    val rarity: Int,            // 1-6
    val spriteRes: Int? = null  // 精灵图资源 ID（可选，运行时计算）
)
```

签到状态存储在 `GameData` 中，格式：
```kotlin
@ColumnInfo(name = "sign_in_state_json")
var signInState: SignInState = SignInState()
```

---

## 八、日历卡片实现要点

### 卡片组件签名建议

```kotlin
@Composable
fun SignInDayCard(
    dayOfMonth: Int,              // 日期数字 1-31
    reward: DailySignInReward,    // 当日奖励
    state: SignInDayState,        // 状态枚举
    modifier: Modifier = Modifier
)
```

### 状态枚举

```kotlin
enum class SignInDayState {
    FUTURE,              // 未来日期
    TODAY_UNCLAIMED,     // 今日未签到
    TODAY_CLAIMED,       // 今日已签到
    PAST_CLAIMED,        // 过去已签到
    MISSED               // 过去未签到（漏签）
}
```

### 卡片构建方式

不直接复用 `UnifiedItemCard`（因为需要修改精灵图区域内容），而是在 Dialog 内新建一个专用 Composable，**复用 `UnifiedItemCard` 的布局结构**（60dp、6dp 圆角、2dp 边框、rarity 背景色、14dp 名称栏），但精灵图区域内容可替换：

- **FUTURE / TODAY_UNCLAIMED**：显示精灵图 + 日期数字（白字左上角）+ 数量（白字右下角）
- **TODAY_CLAIMED / PAST_CLAIMED**：显示浅灰背景 + "已领"绿字 + 日期数字（白字左上角）+ 数量（白字右下角）
- **MISSED**：显示浅灰背景 + "未领"红字 + 日期数字（白字左上角）+ 数量（白字右下角）

**日期数字始终显示**（即使已领/未领也显示），因为用户需要识别这是几号的奖励。

---

## 九、关键颜色/尺寸常量

| 常量 | 值 | 用途 |
|------|-----|------|
| 卡片尺寸 | 60.dp | 同 UnifiedItemCard |
| 圆角 | 6.dp | 同 UnifiedItemCard |
| 卡片间距 | 4.dp | 网格间距 |
| 今日边框 | `GameColors.Gold` (0xFFFFD700), 3.dp | 今日未签到的金色边框 |
| 默认边框 | `GameColors.Border` (0xFFDCD6D0), 2.dp | 其他日期边框 |
| 已领文字颜色 | `Color(0xFF4CAF50)` | 绿色"已领" |
| 未领文字颜色 | `GameColors.Error` (0xFFF44336) | 红色"未领" |
| 日期数字颜色 | `Color.White` | 左上角日期数字 |
| 日期数字大小 | 9.sp | 同"锁定"标签 |
| 已领/未领文字大小 | 9.sp | 同 MailDialog ClaimedAttachmentCard |
| 已领/未领背景 | `Color(0xFFF5F5F5)` | 浅灰，同 MailDialog |
| 名称栏高度 | 14.dp | 同 UnifiedItemCard |
| 名称文字颜色 | `Color.Black` | 项目规范 |

---

## 十、签到流程

```
用户打开签到 Dialog
  → 加载 signInState（本月已领日期集合）
  → 计算今日日期 + 星期几
  → 渲染日历（根据五种状态渲染每个格子）
  → 用户点击"签到"按钮
    → 调用 gameEngine.claimDailySignIn()
      → 发放奖励到背包
      → 更新 signInState.claimedDays
      → 今日格子变为 TODAY_CLAIMED
      → 按钮变为 disabled
```

---

## 十一、文件清单

| 文件 | 说明 |
|------|------|
| `ui/game/dialogs/DailySignInDialog.kt` | 签到 Dialog UI（新增） |
| `core/model/DailySignIn.kt` | 数据模型 `SignInState` + `DailySignInReward`（新增） |
| `core/engine/service/DailySignInService.kt` | 签到业务逻辑（新增） |
| `core/model/GameData.kt` | 添加 `signInState` 字段（修改） |
| `core/state/GameStateStore.kt` | 添加 sign-in state flow（修改） |
| `core/engine/GameEngine.kt` | 添加 `claimDailySignIn()` 方法（修改） |
| `data/local/GameDatabase.kt` | DB Migration（修改） |
| `ui/game/GameViewModel.kt` | 绑定签到方法（修改） |
