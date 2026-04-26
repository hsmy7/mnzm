# 游戏失败机制实现报告

## 版本信息

- **版本号**: 2.5.34
- **版本代码**: 2102
- **日期**: 2026-04-26
- **提交**: feat: 游戏失败机制 - 所有宗门被占领时宣告失败 v2.5.34

---

## 需求概述

当玩家所有宗门都被占领时（包括初始自身宗门），宣告游戏失败并弹出提示框。提示框包含：
- 游戏失败描述
- 重开游戏按钮
- 回到主界面按钮（主界面指游戏的登录界面）

---

## 实现方案

### 1. 数据层：GameData 新增游戏失败状态字段

**文件**: `core/model/GameData.kt`

在 `GameData` 数据类末尾添加 `isGameOver` 字段：

```kotlin
var smartBattleEnabled: Boolean = false,

var isGameOver: Boolean = false
```

- 默认值为 `false`，确保旧存档加载时自动兼容（Kotlin Serialization 使用默认值填充缺失字段）
- 该字段被 Room 和 Kotlinx Serialization 同时序列化，支持数据库存储和 JSON 存档

---

### 2. 逻辑层：游戏失败检测

**文件**: `core/engine/service/CultivationService.kt`

新增 `checkGameOverCondition()` 方法：

```kotlin
private fun checkGameOverCondition() {
    if (currentGameData.isGameOver) return

    val playerSect = currentGameData.worldMapSects.find { it.isPlayerSect } ?: return
    val playerSectId = playerSect.id

    val playerControlsAnySect = currentGameData.worldMapSects.any { sect ->
        (sect.isPlayerSect && sect.occupierSectId.isEmpty()) ||
        (sect.occupierSectId == playerSectId && !sect.isPlayerSect)
    }

    if (!playerControlsAnySect) {
        currentGameData = currentGameData.copy(isGameOver = true)
        eventService.addGameEvent("我宗所有领地已被攻占，宗门覆灭...", EventType.DANGER)
    }
}
```

**判定逻辑说明**：

| 条件 | 含义 |
|------|------|
| `sect.isPlayerSect && sect.occupierSectId.isEmpty()` | 玩家初始宗门未被占领 |
| `sect.occupierSectId == playerSectId && !sect.isPlayerSect` | 该宗门被玩家占领（非初始宗门） |

当以上任一条件都不满足时（即玩家不控制任何宗门），触发游戏失败。

**检测时机**（两处调用）：

1. **每日 tick** (`processDailyEvents` 中 `processAIBattleTeamMovement()` 之后)
   - AI 战斗队伍移动到达目标并结算战斗后，可能占领玩家宗门
2. **每月 tick** (`processMonthlyEvents` 中 `processAISectOperations()` 之后)
   - AI 宗门月度攻击决策和执行后，可能占领玩家宗门

---

### 3. 对话框状态管理

**文件**: `ui/state/DialogStateManager.kt`

在 `DialogType` 密封类中添加 `GameOver` 类型：

```kotlin
object BuildingDetail : DialogType()
object GameOver : DialogType()
```

---

### 4. ViewModel 层：游戏失败状态暴露与对话框控制

**文件**: `ui/game/GameViewModel.kt`

新增：

```kotlin
val isGameOver: StateFlow<Boolean> = gameEngine.gameData
    .map { it.isGameOver }
    .distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

fun openGameOverDialog() {
    viewModelScope.launch {
        gameEngineCore.pause()
    }
    dialogStateManager.closeDialog()
    openDialog(DialogType.GameOver)
}

fun closeGameOverDialog() {
    closeCurrentDialog()
}
```

- `isGameOver` 使用 `distinctUntilChanged()` 避免重复触发
- `openGameOverDialog()` 先暂停游戏引擎，再关闭其他对话框，最后打开游戏失败对话框

---

### 5. UI 层：游戏失败对话框

**文件**: `ui/game/MainGameScreen.kt`

#### 5.1 参数扩展

`MainGameScreen` 新增 `onRestartGame` 回调参数：

```kotlin
fun MainGameScreen(
    // ... 原有参数
    onRestartGame: () -> Unit,
    // ...
)
```

#### 5.2 游戏失败状态监听

```kotlin
val isGameOver by viewModel.isGameOver.collectAsState()
val showGameOverDialog = currentDialog?.type == DialogType.GameOver

LaunchedEffect(isGameOver) {
    if (isGameOver && !showGameOverDialog) {
        viewModel.openGameOverDialog()
    }
}
```

- 使用 `LaunchedEffect` 监听 `isGameOver` 状态变化
- 当 `isGameOver` 变为 `true` 且对话框未显示时，自动弹出
- 加载已失败的存档时也会触发（初始值即为 `true`）

#### 5.3 GameOverDialog 组件

```kotlin
@Composable
private fun GameOverDialog(
    onRestartGame: () -> Unit,
    onReturnToMain: () -> Unit
) {
    Dialog(onDismissRequest = {}) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "宗门覆灭",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFF4444)
                )
                // ... 失败描述文本
                GameButton(text = "重开游戏", onClick = onRestartGame)
                GameButton(text = "回到主界面", onClick = onReturnToMain)
            }
        }
    }
}
```

**UI 特性**：
- `Dialog(onDismissRequest = {})`：对话框不可通过点击外部或返回键关闭
- 暗色主题卡片（`0xFF1A1A2E`），红色标题（`0xFFFF4444`）
- 两个按钮：蓝色"重开游戏"、灰色"回到主界面"

---

### 6. Activity 层：回调绑定

**文件**: `ui/game/GameActivity.kt`

在 `setContent` 中传递 `onRestartGame`：

```kotlin
MainGameScreen(
    viewModel = viewModel,
    saveLoadViewModel = saveLoadViewModel,
    // ...
    onRestartGame = {
        saveLoadViewModel.restartGame()
    },
    // ...
)
```

- "重开游戏"调用 `SaveLoadViewModel.restartGame()`，该函数会：
  1. 停止游戏循环
  2. 调用 `gameEngine.restartGameSuspend()` 重置游戏数据（`stateStore.reset()` 会将 `isGameOver` 重置为 `false`）
  3. 保存新游戏状态
  4. 重新启动游戏循环
- "回到主界面"复用已有的 `onLogout` 逻辑，跳转到 `MainActivity`（登录界面）

---

### 7. 数据库迁移

**文件**: `data/local/GameDatabase.kt`

#### 7.1 新增迁移

```kotlin
val MIGRATION_14_15 = object : androidx.room.migration.Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        try {
            Log.i("GameDatabase", "Migrating database from version 14 to 15: add isGameOver field to game_data")
            db.execSQL("ALTER TABLE game_data ADD COLUMN isGameOver INTEGER NOT NULL DEFAULT 0")
        } catch (e: Exception) {
            Log.e("GameDatabase", "Migration 14->15 failed", e)
            throw e
        }
    }
}
```

#### 7.2 更新数据库版本

```kotlin
@Database(
    // ... entities
    version = 15,
    exportSchema = true
)
```

#### 7.3 注册迁移

```kotlin
.addMigrations(
    // ... 原有迁移
    MIGRATION_13_14,
    MIGRATION_14_15
)
```

---

## 存档兼容性

| 场景 | 行为 |
|------|------|
| 新游戏 | `isGameOver` 默认为 `false` |
| 旧存档加载（JSON） | Kotlin Serialization 使用默认值 `false` 填充缺失字段 |
| 旧应用升级（数据库） | `MIGRATION_14_15` 自动添加 `isGameOver` 列，默认 `0`（false） |
| 加载已失败的存档 | `LaunchedEffect` 检测到 `isGameOver = true`，自动弹出失败对话框 |

---

## 修改文件清单

| 文件路径 | 修改类型 | 说明 |
|---------|---------|------|
| `core/model/GameData.kt` | 修改 | 添加 `isGameOver` 字段 |
| `core/engine/service/CultivationService.kt` | 修改 | 添加 `checkGameOverCondition()` 方法，两处调用 |
| `ui/state/DialogStateManager.kt` | 修改 | 添加 `GameOver` DialogType |
| `ui/game/GameViewModel.kt` | 修改 | 添加 `isGameOver` StateFlow 和对话框控制方法 |
| `ui/game/MainGameScreen.kt` | 修改 | 添加 `onRestartGame` 参数、`GameOverDialog` 组件、状态监听 |
| `ui/game/GameActivity.kt` | 修改 | 传递 `onRestartGame` 回调 |
| `data/local/GameDatabase.kt` | 修改 | 添加 `MIGRATION_14_15`，版本号 14→15 |
| `app/build.gradle` | 修改 | versionCode 2101→2102，versionName 2.5.33→2.5.34 |
| `CHANGELOG.md` | 修改 | 更新日志 |
| `schemas/.../15.json` | 新增 | Room 数据库 schema 版本 15 |

---

## 构建验证

```
BUILD SUCCESSFUL in 1m 5s
20 actionable tasks: 8 executed, 12 up-to-date
```

编译通过，无新增错误。仅存在项目原有的警告（与本次修改无关）。

---

## 代码复查要点

复查专家识别的关键问题及修复：

1. **数据库迁移缺失** → 已添加 `MIGRATION_14_15`
2. **AI 战斗队伍移动后未检测** → 在 `processDailyEvents` 的 `processAIBattleTeamMovement()` 后添加 `checkGameOverCondition()`
3. **对话框未关闭其他对话框** → `openGameOverDialog()` 中先调用 `dialogStateManager.closeDialog()`
