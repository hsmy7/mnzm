# 冗余代码一次性清理方案

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 删除项目中已确认的死代码（零引用文件/函数）、空目录、未跟踪备份文件，减少约 270 行死代码和混淆点。

**Architecture:** 分 6 个独立任务组，按风险从低到高排序。每个任务组完成后可独立提交。任务 1-4 为直接删除（零风险），任务 5-6 为代码内清理和整理。

**Tech Stack:** Kotlin 2.0.21, Gradle 8.8.0

---

## 任务 1：删除空文件 & 残桩文件

**风险：零** — 这些文件零引用，Kotlin 编译器不会感知它们存在。

### Task 1.1: 删除 DisciplePurchaseSystem.kt（空文件）

**文件：**
- 删除：`android/app/src/main/java/com/xianxia/sect/core/engine/DisciplePurchaseSystem.kt`

- [ ] **Step 1: 确认文件为空**

```bash
wc -c android/app/src/main/java/com/xianxia/sect/core/engine/DisciplePurchaseSystem.kt
```

预期：`0`（零字节）

- [ ] **Step 2: 确认零引用**

```bash
grep -r "DisciplePurchaseSystem" android/app/src/main/java/ --include="*.kt"
```

预期：无输出

- [ ] **Step 3: 删除文件**

```bash
rm android/app/src/main/java/com/xianxia/sect/core/engine/DisciplePurchaseSystem.kt
```

- [ ] **Step 4: 编译验证**

```bash
cd android && ./gradlew.bat compileReleaseKotlin
```

预期：BUILD SUCCESSFUL

### Task 1.2: 删除 BattleMarker.kt（残桩文件）

**文件：**
- 删除：`android/app/src/main/java/com/xianxia/sect/ui/game/map/markers/BattleMarker.kt`

- [ ] **Step 1: 确认文件内容仅为 package 声明**

```bash
head -5 android/app/src/main/java/com/xianxia/sect/ui/game/map/markers/BattleMarker.kt
```

预期：仅一行 `package com.xianxia.sect.ui.game.map.markers`

- [ ] **Step 2: 确认零引用**

```bash
grep -r "BattleMarker" android/app/src/main/java/ --include="*.kt"
```

预期：无输出（或仅匹配到自身文件名）

- [ ] **Step 3: 删除文件**

```bash
rm android/app/src/main/java/com/xianxia/sect/ui/game/map/markers/BattleMarker.kt
```

- [ ] **Step 4: 编译验证**

```bash
cd android && ./gradlew.bat compileReleaseKotlin
```

预期：BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add android/app/src/main/java/com/xianxia/sect/core/engine/DisciplePurchaseSystem.kt
git add android/app/src/main/java/com/xianxia/sect/ui/game/map/markers/BattleMarker.kt
git commit -m "chore: 删除空文件DisciplePurchaseSystem.kt和残桩BattleMarker.kt"
```

---

## 任务 2：删除零引用完整文件

**风险：零** — 两个文件在全项目 `src/main` 中均零 import。

### Task 2.1: 删除 BagUtils.kt（功能已被 StorageBagUtils 取代）

**文件：**
- 删除：`android/app/src/main/java/com/xianxia/sect/core/state/BagUtils.kt`

- [ ] **Step 1: 确认零引用**

```bash
grep -r "import com.xianxia.sect.core.state.BagUtils" android/app/src/main/java/ --include="*.kt"
grep -r "BagUtils\." android/app/src/main/java/ --include="*.kt" | grep -v "StorageBagUtils" | grep -v "BagUtils.kt"
```

预期：无输出（所有 BagUtils 的使用都在 StorageBagUtils 中，或仅在 BagUtils.kt 自身定义中）

- [ ] **Step 2: 确认替代品 StorageBagUtils 活跃**

```bash
grep -r "StorageBagUtils" android/app/src/main/java/ --include="*.kt" -l
```

预期：列出 4 个活跃引用文件（DiscipleEquipmentManager.kt, DiscipleManualManager.kt, DisciplePillManager.kt, GameEngine.kt）

- [ ] **Step 3: 删除文件**

```bash
rm android/app/src/main/java/com/xianxia/sect/core/state/BagUtils.kt
```

- [ ] **Step 4: 编译验证**

```bash
cd android && ./gradlew.bat compileReleaseKotlin
```

预期：BUILD SUCCESSFUL

### Task 2.2: 删除 GameResult.kt（功能已被 AppError + Kotlin Result 取代）

**文件：**
- 删除：`android/app/src/main/java/com/xianxia/sect/core/util/GameResult.kt`

- [ ] **Step 1: 确认零引用**

```bash
grep -r "import com.xianxia.sect.core.util.GameResult" android/app/src/main/java/ --include="*.kt"
grep -r "\bGameResult\b" android/app/src/main/java/ --include="*.kt" | grep -v "GameResult.kt"
```

预期：无输出（GameResult 的所有引用均在其自身文件中）

- [ ] **Step 2: 删除文件**

```bash
rm android/app/src/main/java/com/xianxia/sect/core/util/Gameresult.kt
```

- [ ] **Step 3: 编译验证**

```bash
cd android && ./gradlew.bat compileReleaseKotlin
```

预期：BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add android/app/src/main/java/com/xianxia/sect/core/state/BagUtils.kt
git add android/app/src/main/java/com/xianxia/sect/core/util/GameResult.kt
git commit -m "chore: 删除零引用文件BagUtils.kt和GameResult.kt（已被StorageBagUtils和AppError取代）"
```

---

## 任务 3：清理 GameUtils.kt 中的死函数

**风险：极低** — 仅删除 4 个零调用函数，保留其余活跃函数。

**文件：**
- 修改：`android/app/src/main/java/com/xianxia/sect/core/util/GameUtils.kt`

- [ ] **Step 1: 确认 4 个函数零调用**

```bash
grep -r "GameUtils\.generateId\|GameUtils\.generateShortId\|GameUtils\.randomInt\|GameUtils\.randomLong" android/app/src/main/java/ --include="*.kt"
```

预期：无输出

- [ ] **Step 2: 删除函数并清理 import**

删除 GameUtils object 中第 37-62 行的 4 个函数：
- `generateId()` (line 37)
- `generateShortId()` (line 39)
- `randomInt(min, max)` (line 41-45)
- `randomLong(min, max)` (line 47-62)

同时删除不再需要的 import：
- `java.util.UUID` — 仅被 `generateId()` / `generateShortId()` 使用，删除这两个函数后不再需要

修改后的文件开头：

```kotlin
package com.xianxia.sect.core.util

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.model.SectRelation
import com.xianxia.sect.core.model.Alliance
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.random.Random

enum class SectRelationLevel(val displayName: String, val minFavor: Int, val maxFavor: Int, val colorHex: Long) {
    // ... 保持不变
}

object GameUtils {
    // 删除 generateId(), generateShortId(), randomInt(), randomLong() — 共26行
    // 以下函数保持不变：
    // randomDouble(), randomChance(), calculateAverageRealm(),
    // calculateTeamAverageRealm(), calculateBeastRealm(), calculateBeastRealmFromAvg(),
    // randomFrom(), randomFromWeighted(), shuffle(),
    // formatNumber(), formatPercent(), formatTime(),
    // calculateBreakthroughChance(), calculateDamage(),
    // calculateExperienceForLevel(), applyPriceFluctuation(),
    // generateRandomName(), generateRandomSpiritRoot(),
    // getSectRelation(), getSectRelationLevel(), calculateSectTradePriceMultiplier()
}
```

- [ ] **Step 3: 编译验证**

```bash
cd android && ./gradlew.bat compileReleaseKotlin
```

预期：BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add android/app/src/main/java/com/xianxia/sect/core/util/GameUtils.kt
git commit -m "chore: 删除GameUtils中4个零调用函数generateId/generateShortId/randomInt/randomLong（-26行）"
```

---

## 任务 4：删除空目录和未跟踪备份文件

**风险：零** — 空目录不影响编译，备份文件未加入 git。

### Task 4.1: 删除空包目录

- [ ] **Step 1: 确认目录为空**

```bash
ls -la android/app/src/main/java/com/xianxia/sect/ad/
ls -la android/app/src/main/java/com/xianxia/sect/ui/state/
```

- [ ] **Step 2: 删除目录并清理 .gitkeep 或等同物**

```bash
rmdir android/app/src/main/java/com/xianxia/sect/ad/
rmdir android/app/src/main/java/com/xianxia/sect/ui/state/
```

### Task 4.2: 删除 drawable-nodpi-orig 备份

- [ ] **Step 1: 确认正式资源目录存在对应文件**

```bash
ls android/app/src/main/res/drawable-nodpi/ | head -20
ls android/drawable-nodpi-orig/ | head -20
```

确认 `drawable-nodpi-orig/` 中的文件在 `res/drawable-nodpi/` 中都有对应。

- [ ] **Step 2: 删除**

```bash
rm -rf android/drawable-nodpi-orig/
```

- [ ] **Step 3: 提交**

```bash
git add android/app/src/main/java/com/xianxia/sect/ad/
git add android/app/src/main/java/com/xianxia/sect/ui/state/
git commit -m "chore: 删除空目录ad/和ui/state/，清理未跟踪精灵图备份drawable-nodpi-orig/"
```

---

## 任务 5：移除无操作数据库迁移

**风险：低** — 仅删除空迁移对象引用，不改变 schema。

**文件：**
- 修改：`android/app/src/main/java/com/xianxia/sect/data/local/GameDatabase.kt`

- [ ] **Step 1: 在 GameDatabase 中定位 MIGRATION_2_3**

确认 `MIGRATION_2_3` 在 `companion object` 中的 `migrations` 列表中的引用位置。

- [ ] **Step 2: 检查迁移列表中 MIGRATION_2_3 的用法**

```bash
grep -n "MIGRATION_2_3" android/app/src/main/java/com/xianxia/sect/data/local/GameDatabase.kt
```

预期：2 处匹配（一处定义 + 一处 migrations 列表引用）

- [ ] **Step 3: 从 migrations 列表中移除 MIGRATION_2_3**

在 `Room.databaseBuilder(...)` 的 `.addMigrations(...)` 调用中，删除 `MIGRATION_2_3` 条目。

- [ ] **Step 4: 删除 MIGRATION_2_3 定义**

删除 companion object 中第 332-337 行的 `MIGRATION_2_3` 定义。

- [ ] **Step 5: 编译验证**

```bash
cd android && ./gradlew.bat compileReleaseKotlin
```

预期：BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
git add android/app/src/main/java/com/xianxia/sect/data/local/GameDatabase.kt
git commit -m "chore: 移除无操作数据库迁移MIGRATION_2_3"
```

---

## 任务 6：运行完整测试验证

**目的：** 确保所有删除操作未破坏任何功能。

- [ ] **Step 1: 运行全部单元测试**

```bash
cd android && ./gradlew.bat test
```

预期：所有测试通过

- [ ] **Step 2: 运行 lint**

```bash
cd android && ./gradlew.bat lintRelease
```

预期：无新增 lint 错误

- [ ] **Step 3: 最终编译检查**

```bash
cd android && ./gradlew.bat assembleDebug
```

预期：BUILD SUCCESSFUL

---

## 变更影响总结

| 任务 | 操作 | 删除行数 | 风险 |
|------|------|---------|------|
| 1 | 删除 DisciplePurchaseSystem.kt + BattleMarker.kt | 0 (空/残桩) | 无 |
| 2 | 删除 BagUtils.kt + GameResult.kt | 241 行 | 无 |
| 3 | 删除 GameUtils 中 4 个死函数 | 26 行 | 极低 |
| 4 | 删除空目录 + 未跟踪文件 | 0 (非源码) | 无 |
| 5 | 移除 MIGRATION_2_3 | 13 行 | 低 |
| **合计** | | **280 行** | |

## 不在本次清理范围（留待后续）

以下项目涉及调用点迁移或架构决策，需单独评估：

- `GameError.kt` / `GameLoopError.kt` / `ProductionError.kt` — 虽 `@Deprecated`，仍有活跃调用方需逐个迁移
- 6 个 `ValidationResult` 类统一 — 需在 `AppError.Domain.Validation` 设计中达成一致
- `Disciple.calculateBaseStatsWithVariance` 委托内联 — 5 个调用点需逐个修改
- `StorageFacade` 9 个废弃同步方法 — 需确认零调用方后批量删除
- `DiscipleAggregate.toCompactDisciple()` — 等待 U-01 Phase3 迁移

## 验证清单

- [ ] `./gradlew.bat compileReleaseKotlin` 通过（每次删除后）
- [ ] `./gradlew.bat test` 全部通过（全部删除后）
- [ ] `./gradlew.bat lintRelease` 无新增问题
- [ ] `./gradlew.bat assembleDebug` 成功
- [ ] `git status` 干净（预期删除 + 修改文件外无意外变更）
