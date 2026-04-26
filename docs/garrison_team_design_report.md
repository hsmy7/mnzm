# 驻守队伍设计重构报告

## 版本信息

- **版本号**: 2.5.35
- **日期**: 2026-04-26
- **涉及文件**:
  - `AISectAttackManager.kt`
  - `CultivationService.kt`
  - `CHANGELOG.md`
  - `build.gradle`

---

## 一、设计前提

### 核心定义

> **驻守队伍就是战斗队伍，是战斗队伍起到了驻守所处宗门的职责。**

当宗门被攻击时，处于该宗门的战斗队伍起到驻守作用，与攻击队伍进行战斗。若该战斗队伍人数不足10人，则由该宗门补充人数（高境界优先）。

### 关键前提条件

| 前提 | 说明 |
|------|------|
| 战斗队伍唯一性 | 每个宗门（玩家或AI）同一时间只能拥有一支战斗队伍 |
| 驻守即战斗 | 驻守不是独立系统，而是战斗队伍在特定位置（宗门）的特定状态（stationed） |
| 人数上限 | 每队最多10人（`TEAM_SIZE = 10`） |
| 补充规则 | 不足10人时从宗门可用弟子中补充，高境界优先（`sortedBy { it.realm }`，数字越小境界越高） |
| 旧存档兼容 | 新增字段均有默认值，旧存档加载后驻守相关字段为默认值，查找逻辑走fallback分支 |

---

## 二、修改内容

### 2.1 AISectAttackManager.kt

#### 新增 `findGarrisonTeam()` 函数

```kotlin
fun findGarrisonTeam(sect: WorldSect, aiBattleTeams: List<AIBattleTeam>): AIBattleTeam? {
    return if (sect.garrisonTeamId.isNotEmpty()) {
        aiBattleTeams.find { it.id == sect.garrisonTeamId }
    } else {
        aiBattleTeams.find { it.isGarrison && it.garrisonSectId == sect.id }
    }
}
```

**作用**: 统一驻守队伍查找逻辑。

**查找优先级**:
1. 优先通过 `garrisonTeamId`（新数据模型）查找
2. 兼容通过 `isGarrison && garrisonSectId`（旧数据模型）查找

**旧存档兼容性**: 旧存档中 `garrisonTeamId` 为空字符串，走第二个分支，行为与修改前一致。

#### 新增 `supplementDisciples()` 函数

```kotlin
fun supplementDisciples(
    coreDisciples: List<Disciple>,
    availableDisciples: List<Disciple>
): List<Disciple> {
    val core = coreDisciples.take(TEAM_SIZE)
    if (core.size >= TEAM_SIZE) return core
    val coreIds = core.map { it.id }.toSet()
    val supplements = availableDisciples
        .filter { it.isAlive && it.id !in coreIds }
        .sortedBy { it.realm }
        .take(TEAM_SIZE - core.size)
    return core + supplements
}
```

**作用**: 统一弟子补充逻辑。

**变量关系**:
- `coreDisciples`: 核心队伍弟子（如驻守队伍原有成员、玩家战斗队伍成员）
- `availableDisciples`: 可供补充的弟子池（如宗门空闲弟子）
- `sortedBy { it.realm }`: 按境界排序，realm数字越小境界越高，因此高境界优先
- 返回: 核心弟子 + 补充弟子，总数不超过 `TEAM_SIZE`

#### 删除 `createGarrisonTeam()` 死代码

原 `createGarrisonTeam()` 函数在整个代码库中从未被调用，且 `garrisonSectName` 设置为空字符串，存在数据不一致隐患。已删除。

#### 简化 `createPlayerDefenseTeam()`

移除3个未使用的参数：`equipmentMap`、`manualMap`、`manualProficiencies`。

---

### 2.2 CultivationService.kt

#### 修改 `triggerAISectBattle()` — AI宗门间战斗

**变更前**: AI占领宗门后，攻击队伍状态变为 `completed`，然后创建一支全新的驻守队伍（`createGarrisonTeam`）。

**变更后**: AI占领宗门后，攻击队伍直接变为驻守队伍：

```kotlin
it.copy(
    status = "stationed",
    isGarrison = true,
    garrisonSectId = team.defenderSectId,
    garrisonSectName = defenderSect.name,
    disciples = supplementedDisciples,
    moveProgress = 1f
)
```

**关键变化**:
- 队伍ID不变（`team.id`）
- `garrisonTeamId` 设置为 `team.id`
- 不再创建新队伍，避免 `aiBattleTeams` 列表膨胀

#### 新增 `triggerPlayerGarrisonBattle()` — 玩家驻守宗门被攻击

**场景**: AI攻击玩家占领的宗门（非玩家本宗）。

**原逻辑缺陷**: `isPlayerDefender` 仅判断 `defender.isPlayerSect`，当AI攻击被玩家占领的AI宗门时，`isPlayerDefender` 为 false，玩家驻守队伍完全不参与防守。

**新逻辑**:

```kotlin
val playerTeamStationedThere = playerBattleTeam != null &&
    playerBattleTeam.isOccupying &&
    playerBattleTeam.occupiedSectId == team.defenderSectId &&
    playerBattleTeam.status == "stationed" &&
    playerBattleTeam.aliveMemberCount > 0

if (playerTeamStationedThere) {
    triggerPlayerGarrisonBattle(team, defenderSect, attackerSect, playerBattleTeam!!)
    return
}
```

**防守方构成**:
1. 玩家战斗队伍成员（slot中的弟子）作为核心
2. 不足10人时，从被占领宗门的AI弟子中补充（高境界优先）

**战斗结果处理**:
- **AI胜利**: 玩家驻守队伍撤回（`status = "returning"`），AI攻击队伍变为驻守队伍
- **玩家胜利/平局**: 玩家驻守队伍保留，AI攻击队伍标记为 `completed`

#### 修改 `triggerPlayerSectBattle()` — 玩家本宗被攻击

**变更前**: 直接从所有空闲弟子中选10人组成防守队伍。

**变更后**: 若玩家战斗队伍在宗门，则战斗队伍作为主力防守：

```kotlin
val battleTeamAtSect = playerBattleTeam != null &&
    (playerBattleTeam.isIdle || playerBattleTeam.isStationed) &&
    playerBattleTeam.isAtSect &&
    playerBattleTeam.aliveMemberCount > 0

val playerDefenseTeam = if (battleTeamAtSect) {
    val teamDisciples = playerBattleTeam!!.slots
        .filter { it.discipleId.isNotEmpty() && it.isAlive }
        .mapNotNull { slot -> currentDisciples.find { it.id == slot.discipleId } }
        .filter { it.isAlive }

    if (teamDisciples.isEmpty()) {
        AISectAttackManager.createPlayerDefenseTeam(disciples = currentDisciples)
    } else {
        val teamIds = teamDisciples.map { it.id }.toSet()
        val idleDisciples = currentDisciples.filter {
            it.isAlive && it.status == DiscipleStatus.IDLE && it.id !in teamIds
        }
        AISectAttackManager.supplementDisciples(teamDisciples, idleDisciples)
    }
} else {
    AISectAttackManager.createPlayerDefenseTeam(disciples = currentDisciples)
}
```

**关键变化**:
- 战斗队伍成员优先入选
- 不足10人时从空闲弟子补充
- 战斗队伍成员的死亡状态同步到 `battleTeam.slots`

#### 修改 `executePlayerBattleTeamBattle()` — 玩家攻击AI宗门

**变更**:
1. 使用 `findGarrisonTeam()` 统一查找驻守队伍
2. 使用 `supplementDisciples()` 统一补充弟子
3. 玩家占领宗门时设置 `garrisonTeamId = team.id`

```kotlin
if (sect.id == targetSect.id) {
    sect.copy(
        isPlayerOccupied = true,
        occupierSectId = data.worldMapSects.find { it.isPlayerSect }?.id ?: "",
        garrisonTeamId = team.id
    )
}
```

#### 修复 `canActuallyOccupy` 使用最新sect数据

**问题**: `canActuallyOccupy` 判断使用了函数开头获取的旧 `targetSect` / `defenderSect` 引用，而中间流程可能已更新 `currentGameData`（如驻守队伍被全灭后 `garrisonTeamId` 被清空）。

**修复**: 在判断前重新获取最新sect数据：

```kotlin
val currentTargetSect = currentGameData.worldMapSects.find { it.id == targetSect.id } ?: targetSect
val currentDefenderSect = currentGameData.worldMapSects.find { it.id == team.defenderSectId } ?: defenderSect
```

#### 修复运算符优先级

**问题**:
```kotlin
it.isGarrison && it.garrisonSectId == targetSect.id || it.id == targetSect.garrisonTeamId
```

Kotlin中 `&&` 优先级高于 `||`，虽然结果恰好正确，但意图不明确。

**修复**:
```kotlin
((it.isGarrison && it.garrisonSectId == targetSect.id) || it.id == targetSect.garrisonTeamId)
```

---

## 三、战斗场景矩阵

| 攻击方 | 防守方 | 防守主力 | 补充来源 | 占领后驻守 |
|--------|--------|----------|----------|------------|
| 玩家 | AI宗门 | AI驻守队伍 | 该宗门弟子 | AI攻击队伍 |
| AI | AI宗门 | AI驻守队伍 | 该宗门弟子 | AI攻击队伍 |
| AI | 玩家本宗 | 玩家战斗队伍（如在宗门） | 空闲弟子 | AI攻击队伍 |
| AI | 玩家占领宗门 | 玩家驻守战斗队伍 | 该宗门AI弟子 | AI攻击队伍 |

---

## 四、数据模型关系

### WorldSect（宗门）

```
garrisonTeamId: String = ""   // 驻守队伍ID（新模型）
occupierSectId: String = ""   // 占领方宗门ID
isPlayerOccupied: Boolean     // 是否被玩家占领
```

### AIBattleTeam（AI战斗队伍）

```
id: String                    // 队伍ID（唯一标识）
status: String                // moving / battling / stationed / returning / completed
isGarrison: Boolean = false   // 是否为驻守队伍（旧模型兼容）
garrisonSectId: String = ""   // 驻守宗门ID（旧模型兼容）
garrisonSectName: String = "" // 驻守宗门名
disciples: List<Disciple>     // 队伍成员
```

### BattleTeam（玩家战斗队伍）

```
status: String                // idle / moving / battle / stationed / returning
isOccupying: Boolean          // 是否正在占领某宗门
occupiedSectId: String = ""   // 占领的宗门ID
isAtSect: Boolean             // 是否在宗门内
slots: List<BattleTeamSlot>   // 队伍槽位（2长老+8弟子）
```

---

## 五、旧存档兼容性

| 字段 | 旧存档值 | 处理方式 |
|------|----------|----------|
| `WorldSect.garrisonTeamId` | `""` | 查找驻守队伍时走 `isGarrison && garrisonSectId` fallback |
| `AIBattleTeam.isGarrison` | `false` | 旧存档中无驻守队伍，行为与修改前一致 |
| `AIBattleTeam.garrisonSectId` | `""` | 同上 |

**结论**: 旧存档加载后，驻守相关字段为默认值，不会找到任何驻守队伍，行为与无驻守一致，无兼容性问题。

---

## 六、潜在风险与脆弱假设

| 风险点 | 说明 | 缓解措施 |
|--------|------|----------|
| 补充弟子同时存在于两处 | 被补充进驻守队伍的防守方弟子仍保留在 `aiSectDisciples` 中 | 通过 `coreIds` 排除机制避免战斗时重复选取；死亡处理时分别更新 |
| 玩家全灭被占领时驻守缺员 | 攻击方也损失惨重时，驻守队伍可能不足10人 | 符合设计意图，补充来源为空时接受缺员 |
| `supplementDisciples` 不检查状态 | 仅过滤 `isAlive`，不过滤 `DiscipleStatus` | 调用方在传入前已做状态过滤；函数文档中应明确此约定 |

---

## 七、代码优化项

| 优化项 | 状态 |
|--------|------|
| 提取 `findGarrisonTeam` 公共函数 | 已完成 |
| 提取 `supplementDisciples` 公共函数 | 已完成 |
| 删除 `createGarrisonTeam` 死代码 | 已完成 |
| 简化 `createPlayerDefenseTeam` 参数 | 已完成 |
| 修复运算符优先级 | 已完成 |
| 修复 `canActuallyOccupy` 过时数据 | 已完成 |

---

## 八、构建验证

```
BUILD SUCCESSFUL in 44s
45 actionable tasks: 10 executed, 35 up-to-date
```

无编译错误，无警告。
