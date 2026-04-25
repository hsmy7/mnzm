# 宗门战争系统重构文档

## 版本

v2.5.26

## 变更概览

本次重构彻底重写了宗门战争系统的核心逻辑，主要围绕以下方向展开：

- 攻击不再受距离和路径限制
- 战斗规模固定为10v10
- 防守弟子必须在宗门内（IDLE状态）
- 占领条件改为化神及以上弟子全灭
- 引入驻守队伍机制

---

## 核心规则

### 战斗规模

| 项目 | 数值 |
|------|------|
| 攻击方人数 | 10人 |
| 防守方人数 | 10人 |
| 最大回合数 | 25回合 |
| 参战优先级 | 高境界优先（realm值小的优先） |

### 胜负判定

| 条件 | 结果 |
|------|------|
| 一方弟子全部阵亡 | 另一方胜利 |
| 25回合后双方都有存活 | 平局 |

### 防守弟子筛选

防守方只从满足以下条件的弟子中选择：

- `isAlive == true`
- `status == DiscipleStatus.IDLE`
- 按 `realm` 升序排列（境界高的优先）
- 取前10人

这意味着处于探索队伍、任务中、采矿中等状态的弟子无法参与防守。

### 占领条件

宗门被占领需同时满足：

1. 攻击方在战斗中获胜
2. 该宗门内化神及以上（realm <= 5）弟子全部阵亡

若宗门原本就没有化神及以上弟子，则攻击方胜利即可直接占领。

### 驻守机制

攻击方胜利占领宗门后：

1. 攻击方存活弟子转变为驻守队伍
2. 若驻守队伍不足10人，从被占领宗门的IDLE弟子中按境界高低补足
3. 驻守队伍的 `status` 为 `"stationed"`，`isGarrison` 为 `true`

当有其他宗门进攻该宗门时：

1. 驻守队伍作为防守方参战
2. 若驻守队伍不足10人，从宗门IDLE弟子中补足
3. 驻守队伍失败且宗门内无化神及以上弟子 → 宗门被新攻击方占领，新攻击方转为驻守

### 攻击范围

攻击方可攻击地图上的所有宗门，无视距离和路径限制。唯一限制：

- 不能攻击自己
- 不能攻击自己已占领的宗门

---

## 数据模型变更

### AIBattleTeam

新增字段：

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `isGarrison` | Boolean | false | 是否为驻守队伍 |
| `garrisonSectId` | String | "" | 驻守的宗门ID |
| `garrisonSectName` | String | "" | 驻守的宗门名称 |

### WorldSect

新增字段：

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `garrisonTeamId` | String | "" | 驻守队伍ID |

### 序列化兼容性

所有新增字段均带有默认值，旧存档读取时不会出错。

---

## 核心代码结构

### AISectAttackManager

```
decideAttacks(gameData) -> List<AIBattleTeam>
checkAttackConditions(attacker, defender, gameData, aiDisciplesMap) -> Boolean
createAttackTeam(attacker, defender, gameData, attackerDisciples) -> AIBattleTeam?
createDefenseTeam(defenderDisciples) -> List<Disciple>
executeAISectBattle(attackTeam, defenderSect, defenderDisciples) -> AIBattleResult
executePlayerSectBattle(attackTeam, playerDefenseTeam) -> AIBattleResult
createGarrisonTeam(attackerAliveDisciples, occupiedSectDisciples, attackerSectId, attackerSectName, occupiedSectId) -> AIBattleTeam
createPlayerDefenseTeam(disciples, equipmentMap, manualMap, manualProficiencies) -> List<Disciple>
```

### CultivationService

```
processDailyEvents()
  -> processPlayerBattleTeamMovement()
     -> executePlayerBattleTeamBattle(team, targetSect)
  -> processAIBattleTeamMovement()
     -> triggerAISectBattle(team)
        -> triggerPlayerSectBattle(team, playerSect, attackerSect)
```

### 战斗流程

```
攻击方发起攻击
  -> 队伍移动（每日更新进度）
  -> 到达目标宗门
  -> 组建防守队伍（IDLE弟子优先，高境界优先）
  -> 执行战斗（最多25回合）
  -> 处理阵亡弟子
  -> 判定占领条件（胜利 + 化神及以上全灭）
  -> 若占领：攻击方转驻守 / 若未占领：攻击方返回
```

---

## 关键代码位置

| 功能 | 文件 | 关键函数/行 |
|------|------|------------|
| AI攻击决策 | [AISectAttackManager.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/AISectAttackManager.kt) | `decideAttacks` (L30) |
| 防守队伍组建 | [AISectAttackManager.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/AISectAttackManager.kt) | `createDefenseTeam` (L173) |
| 战斗执行 | [AISectAttackManager.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/AISectAttackManager.kt) | `executeAISectBattle` (L397) |
| 驻守队伍创建 | [AISectAttackManager.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/AISectAttackManager.kt) | `createGarrisonTeam` (L704) |
| AI战斗触发 | [CultivationService.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/service/CultivationService.kt) | `triggerAISectBattle` (L2143) |
| 玩家进攻触发 | [CultivationService.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/service/CultivationService.kt) | `executePlayerBattleTeamBattle` (L2009) |
| 玩家防守触发 | [CultivationService.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/service/CultivationService.kt) | `triggerPlayerSectBattle` (L2292) |
| 玩家队伍移动 | [CultivationService.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/service/CultivationService.kt) | `processPlayerBattleTeamMovement` (L1966) |
| 攻击目标选择 | [BattleViewModel.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/ui/game/BattleViewModel.kt) | `selectBattleTeamTarget` (L275) |
| 数据模型 | [GameData.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/model/GameData.kt) | `AIBattleTeam` (L678), `WorldSect` (L487) |

---

## 配置参数

相关配置位于 [GameConfig.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/GameConfig.kt)：

```kotlin
object AI {
    const val MIN_DISCIPLES_FOR_ATTACK = 10  // 攻击最少弟子数
    const val POWER_RATIO_THRESHOLD = 0.8    // 战力比阈值
    const val TEAM_SIZE = 10                  // 战斗队伍人数
    const val MAX_BATTLE_TURNS = 25           // 最大战斗回合数
}
```

---

## 旧存档兼容性

- 新增字段均有默认值，旧存档可直接读取
- `AIBattleTeam.isGarrison` 默认为 `false`
- `WorldSect.garrisonTeamId` 默认为 `""`
- 旧存档中已有的占领宗门不会有驻守队伍，直到被再次攻击时才会生成
