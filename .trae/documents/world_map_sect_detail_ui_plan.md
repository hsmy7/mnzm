# 世界地图宗门信息界面 UI 调整计划

## 摘要
调整 `WorldMapSectDetailDialog` 的宗门信息界面：
1. 删除“等级宗门”文本标签。
2. 将宗门等级图标移动到宗门名称左侧。
3. 下方操作按钮改为根据屏幕宽度自动换行排列。
4. 在“关系”标签左侧增加“所属势力”显示：若被其他宗门占领则显示占领宗门名称，否则显示该宗门自身名称。

---

## 当前状态分析

### 关键文件
- **[WorldMapSectDetailDialog.kt](file:///c:/Mnzm/XianxiaSectNative/android/feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/WorldMapSectDetailDialog.kt)**：世界地图宗门详情弹窗，所有目标 UI 都在此文件内。
- **[GameDialog.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/ui/src/main/java/com/xianxia/sect/ui/components/GameDialog.kt)**：`UnifiedGameDialog` 定义，标题栏只接受纯文本 `title`。
- **[GameData.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/domain/src/main/java/com/xianxia/sect/core/model/GameData.kt)**：`WorldSect` 数据模型，包含 `occupierSectId`（占领宗门 ID）、`isPlayerOccupied`、`isPlayerSect` 等字段。
- **[GameButton.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/ui/src/main/java/com/xianxia/sect/ui/components/GameButton.kt)**：固定宽度 84dp 的游戏按钮。

### 当前布局
1. **标题**：通过 `UnifiedGameDialog(title = sect.name, ...)` 显示纯文本宗门名称，无图标。
2. **顶部标签行（第 94–143 行）**：非玩家宗门时显示 `等级图标 + sect.levelName` 文本，以及“本宗”/“盟友”标签。
3. **关系行（第 145–167 行）**：显示 `关系: <关系等级> (<好感度>)`，无所属势力信息。
4. **操作按钮区（第 255–390 行）**：
   - 非玩家、未被玩家占领：第一行 `探查 / 送礼 / 结盟`，第二行 `交易 / 进攻`。
   - 被玩家占领：只显示 `进入`，下方是驻守弟子槽位。
   - 玩家宗门：只显示 `进入`。
   按钮使用固定 `Row` 排列，不会随屏幕宽度自动换行。

---

## 拟议改动

### 文件：`android/feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/WorldMapSectDetailDialog.kt`

#### 1. 标题区：等级图标移到宗门名称左侧
- 将 `UnifiedGameDialog(title = sect.name, ...)` 改为 `title = ""`，保留关闭按钮在标题栏右侧。
- 在内容 `Column` 的最顶部新增一个标题行 `Row`：
  - 左侧显示宗门等级图标：`sectIconRes(sect.level)`，`size(26.dp)`。
  - 右侧显示宗门名称 `sect.name`，字号/字重与原标题一致（可用 `AppTypography.Title` 或 16sp + `FontWeight.Bold`）。
  - 垂直居中对齐。

#### 2. 删除等级宗门文本
- 在顶部标签 `Row`（第 94–143 行）中，删除非玩家宗门分支里的 `Image + Text(sect.levelName)` 组合。
- 保留“本宗”“盟友”标签逻辑不变。
- 删除该处不再使用的 `sectIconResId` 局部变量。

#### 3. 关系行左侧增加“所属势力”
- 计算所属势力名称：
  ```kotlin
  val ownerSect = gameData?.worldMapSects?.find { it.id == sect.occupierSectId }
  val affiliationName = if (!sect.occupierSectId.isNullOrEmpty() && ownerSect != null) {
      ownerSect.name
  } else {
      sect.name
  }
  ```
- 在现有关系行最左侧插入：
  - `Text("所属势力:", 12.sp, Color.Black)`
  - `Text(affiliationName, 12.sp, FontWeight.Bold, Color.Black)`
- 保持仅在 `!sect.isPlayerSect` 时显示该行。

#### 4. 操作按钮改为动态排列
- 使用 `FlowRow` 替换原有固定 `Row` 布局：
  ```kotlin
  FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
      verticalArrangement = Arrangement.spacedBy(8.dp)
  ) { ... }
  ```
- 在 `FlowRow` 中按条件渲染按钮（保持原有回调和 `enabled` 逻辑）：
  - 非玩家宗门且未被玩家占领：`探查`、`送礼`、`结盟`、`交易`、`进攻`。
  - 被玩家占领：`进入`。
  - 玩家宗门：`进入`。
- 被玩家占领分支保留原有的 `HorizontalDivider`、`驻守弟子` 标题和 10 个驻守槽位，仅把 `进入` 按钮放入 `FlowRow` 以统一风格。
- 如编译器要求 `ExperimentalLayoutApi`，在 `WorldMapSectDetailDialog` 可组合函数上添加 `@OptIn(ExperimentalLayoutApi::class)`；`androidx.compose.foundation.layout.*` 已导入，一般无需额外 import。

---

## 假设与决策

1. **“动态调整排列”采用 `FlowRow` 自动换行**：当屏幕宽度不足以放下所有按钮时，按钮会按顺序换到下一行并居中。这是最符合 Compose 习惯的实现。
2. **不修改 `UnifiedGameDialog`**：通过清空标题并在内容区自定义标题行实现图标左置，避免影响其他 40+ 处调用该组件的弹窗。
3. **所属势力逻辑**：
   - `occupierSectId` 为空 → 显示当前宗门自身名称。
   - `occupierSectId` 非空且能在 `worldMapSects` 中找到 → 显示占领宗门名称。
   - 这同时覆盖“被其他 AI 宗门占领”和“被玩家宗门占领”两种情况。
4. **等级图标尺寸保持 26.dp**，与当前代码一致。
5. **玩家宗门不显示“所属势力”和“关系”**：保持现有逻辑不变。

---

## 验证步骤

1. **编译验证**：
   - 运行 `./gradlew :feature:game:compileDebugKotlin`（或对应 Windows 命令），确认无编译错误。
2. **UI 验证**：
   - 打开世界地图，点击任意非玩家宗门：
     - 标题左侧出现等级图标，右侧为宗门名称；标题栏内不再重复显示名称。
     - 标签行不再显示“小型宗门/中型宗门”等等级文本。
     - “所属势力: XXX”显示在“关系:”左侧。
   - 点击被 AI 占领的宗门：所属势力显示占领宗门名称。
   - 点击未被占领的宗门：所属势力显示该宗门自身名称。
   - 调整屏幕宽度/旋转设备/切换分屏：操作按钮应自动换行，且保持居中对齐与间距。
   - 点击玩家宗门：只显示“进入”按钮，界面不崩溃。
   - 点击被玩家占领的宗门：显示“进入”按钮，下方驻守弟子槽位正常显示。
