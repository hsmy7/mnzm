# 版本更新指南

## 更新应用版本时的检查清单

当用户要求"更新版本"或"更新小版本"时，**必须**完成以下所有步骤：

### 必须更新的文件（按顺序）

| 序号 | 文件 | 字段 | 说明 |
|------|------|------|------|
| 1 | `android/app/build.gradle` | `versionCode` | 递增整数 |
| 2 | `android/app/build.gradle` | `versionName` | 语义化版本 |
| 3 | `GameConfig.kt` | `VERSION` | 必须与 versionName 一致，用于登录界面显示 |
| 4 | `MainGameScreen.kt` | 设置界面版本文本 | 必须与 versionName 一致，用于设置界面显示 |
| 5 | `CHANGELOG.md` | 新版本条目 | 记录变更内容 |

### 条件更新的文件

| 条件 | 文件 | 字段 |
|------|------|------|
| 涉及数据库架构变化 | `GameDatabase.kt` | `version` |
| 涉及数据库架构变化 | `DatabaseMigrations.kt` | 添加 `MIGRATION_X_Y` |

---

## 详细说明

当用户要求"更新版本"或"更新小版本"时，除了修改 `build.gradle` 中的版本号外，**必须**检查以下事项：

### 1. 检查数据库架构变化

如果本次修改涉及以下操作，**必须**更新 Room 数据库版本号：
- 添加/删除/修改 `@Entity` 数据类的字段
- 添加/删除实体类
- 修改实体类的主键

**相关文件**：
- `android/app/src/main/java/com/xianxia/sect/data/local/GameDatabase.kt`

**操作**：
```kotlin
@Database(
    entities = [...],
    version = 8,  // <-- 增加此版本号
    exportSchema = false
)
```

**常见会改变数据库架构的修改**：
- 修改 `Disciple.kt` 中的弟子属性（如移除 `spiritRootQuality`）
- 修改 `Items.kt` 中的物品属性
- 修改 `GameData.kt` 中的游戏数据

### 2. 添加数据库迁移（Migration）

**重要**：如果不添加迁移，旧存档会被删除！

**相关文件**：
- `android/app/src/main/java/com/xianxia/sect/data/local/DatabaseMigrations.kt`

**操作步骤**：

1. **添加迁移对象**：
```kotlin
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 根据修改类型选择迁移方式
        
        // 方式1：添加新列（简单）
        db.execSQL("ALTER TABLE table_name ADD COLUMN new_column TEXT NOT NULL DEFAULT ''")
        
        // 方式2：删除列（SQLite不支持直接删除，需要重建表）
        // 1. 创建新表（不包含要删除的列）
        db.execSQL("CREATE TABLE table_new (...)")
        // 2. 复制数据
        db.execSQL("INSERT INTO table_new SELECT col1, col2, ... FROM table_name")
        // 3. 删除旧表
        db.execSQL("DROP TABLE table_name")
        // 4. 重命名新表
        db.execSQL("ALTER TABLE table_new RENAME TO table_name")
    }
}
```

2. **注册迁移**：
```kotlin
val ALL_MIGRATIONS: Array<Migration>
    get() = arrayOf(
        MIGRATION_5_6,
        MIGRATION_7_8  // <-- 添加新迁移
    )
```

### 3. 更新应用版本号

**文件**：`android/app/build.gradle`

```gradle
android {
    defaultConfig {
        versionCode 10        // <-- 递增此数字
        versionName "1.4.5"   // <-- 更新此版本名称
    }
}
```

### 4. 同步更新 GameConfig 版本号

**重要**：`build.gradle` 中的版本号必须与 `GameConfig.kt` 中的版本号保持一致！

**文件**：`android/app/src/main/java/com/xianxia/sect/core/GameConfig.kt`

```kotlin
object GameConfig {
    object Game {
        const val NAME = "模拟宗门"
        const val VERSION = "1.4.5"   // <-- 必须与 build.gradle 的 versionName 一致
        // ...
    }
}
```

**注意**：`GameConfig.Game.VERSION` 用于在登录界面等UI中显示版本号，如果不更新会导致显示版本与实际版本不一致。

### 5. 更新设置界面版本号

**重要**：设置界面中的版本号是硬编码的，必须手动更新！

**文件**：`android/app/src/main/java/com/xianxia/sect/ui/game/MainGameScreen.kt`

搜索 `"版本` 关键字，找到设置界面中的版本号显示位置：

```kotlin
Text(
    text = "版本 1.4.5 (build 1045)",  // <-- 更新此处，格式：版本 X.X.XX (build XXXX)
    fontSize = 10.sp,
    color = Color(0xFF999999),
    modifier = Modifier.fillMaxWidth(),
    textAlign = TextAlign.Center
)
```

**注意**：build 号为 versionCode，版本号为 versionName。

### 6. 更新 CHANGELOG.md

**重要**：每次版本更新必须记录变更内容！

**文件**：`CHANGELOG.md`

**操作**：在文件顶部添加新版本条目：
```markdown
## [1.4.5] - 2026-03-22

### 新增
- 功能描述

### 修复
- 修复描述

### 系统
- 版本号：1.4.5 (build 1045)

---
```

### 7. 版本号命名规范

- **versionCode**: 整数，每次发布必须递增
- **versionName**: 语义化版本，格式为 `主版本.次版本.修订号`
  - 主版本：重大功能更新或架构变更
  - 次版本：新功能添加
  - 修订号：bug修复或小优化

### 8. 错误示例

#### 错误1：只更新应用版本号，未更新数据库版本号
```
java.lang.IllegalStateException: Room cannot verify the data integrity. 
Looks like you've changed schema but forgot to update the version number.
```

#### 错误2：更新了数据库版本号，但未添加迁移
如果没有对应的迁移且启用了 `fallbackToDestructiveMigration()`，**旧存档会被删除**。

#### 错误3：只更新了 build.gradle 版本号，未更新 GameConfig 版本号
登录界面显示的旧版本号与实际版本不一致，导致用户困惑。

#### 错误4：只更新了 build.gradle 版本号，未更新 MainGameScreen 版本号
设置界面显示的旧版本号与实际版本不一致，导致用户困惑。

#### 错误5：只更新了版本号，未更新 CHANGELOG.md
缺少版本变更记录，无法追踪版本历史。

### 9. 正确示例

**场景**：移除了 `Disciple` 类中的 `spiritRootQuality` 字段

**必须同时修改**：
1. `build.gradle`: versionCode 9 → 10, versionName "1.4.4" → "1.4.5"
2. `GameConfig.kt`: VERSION "1.4.4" → "1.4.5"（与 build.gradle 保持一致）
3. `MainGameScreen.kt`: "版本 1.4.4 (build 9)" → "版本 1.4.5 (build 10)"（与 build.gradle 保持一致）
4. `CHANGELOG.md`: 添加新版本条目，记录变更内容
5. `GameDatabase.kt`: version 7 → 8（如涉及数据库架构变化）
6. `DatabaseMigrations.kt`: 添加 MIGRATION_7_8（如涉及数据库架构变化）

## 快速检查命令

检查最近修改的实体类文件：
```bash
git diff --name-only HEAD~5 HEAD | grep -E "(Disciple|Items|GameData)\.kt"
```

如果上述命令有输出，说明需要：
1. 更新数据库版本号
2. 添加数据库迁移

## 数据库迁移模板

### 添加列
```kotlin
val MIGRATION_X_Y = object : Migration(X, Y) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE table_name ADD COLUMN new_column TEXT NOT NULL DEFAULT ''")
    }
}
```

### 删除列（SQLite需要重建表）
```kotlin
val MIGRATION_X_Y = object : Migration(X, Y) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. 创建新表（不包含要删除的列）
        db.execSQL("CREATE TABLE table_new (...)")
        
        // 2. 复制数据（选择保留的列）
        db.execSQL("INSERT INTO table_new SELECT col1, col2 FROM table_name")
        
        // 3. 删除旧表
        db.execSQL("DROP TABLE table_name")
        
        // 4. 重命名新表
        db.execSQL("ALTER TABLE table_new RENAME TO table_name")
    }
}
```

### 修改列类型
```kotlin
val MIGRATION_X_Y = object : Migration(X, Y) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // SQLite不支持直接修改列类型，需要重建表
        db.execSQL("CREATE TABLE table_new (...)")
        db.execSQL("INSERT INTO table_new SELECT * FROM table_name")
        db.execSQL("DROP TABLE table_name")
        db.execSQL("ALTER TABLE table_new RENAME TO table_name")
    }
}
```
