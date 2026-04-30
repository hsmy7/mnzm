# 妖兽与人型敌人数值计算简化设计

## 背景

当前妖兽和人型敌人的属性计算链路过长，涉及多层运行时乘法和加法叠加：

**妖兽原公式**：
```
最终属性 = RealmConfig.base × 固定倍率 × 小层倍率 × (1 + 类型修正偏移 + 随机方差 + 0.06固定优势)
```
- 固定倍率：HP=2.25, MP=2.25, 攻击=2.61, 防御=2.34, 速度=1.44
- 0.06 固定优势（beastAdvantage）

**人型敌人原公式**：
```
最终属性 = RealmConfig.base × 固定倍率 × 小层倍率 + 装备属性
```
- 固定倍率：HP=2.1, MP=2.0, 物攻=2.5, 法攻=2.5, 物防=2.2, 法防=2.2, 速度=1.33

问题：
1. 固定倍率在运行时每次计算都是常量乘法，无实际意义
2. 0.06 固定优势也是常量，不应占据运行时计算
3. 妖兽和敌人都依赖 RealmConfig（弟子配置），职责不清

## 目标

将固定倍率和 0.06 优势预计算进基础值配置，简化运行时公式为：

**妖兽新公式**：
```
最终属性 = BeastRealmStats.base × 小层倍率 × (1 + 类型修正偏移 + 随机方差)
```

**人型敌人新公式**：
```
最终属性 = EnemyRealmStats.base × 小层倍率 + 装备属性
```

## 变更详情

### 1. GameConfig.kt — 新增 Beast.RealmStats

在 `GameConfig.Beast` 对象中新增：

```kotlin
data class RealmStats(
    val hp: Int,
    val mp: Int,
    val attack: Int,    // 物攻/法攻共用
    val defense: Int,   // 物防/法防共用
    val speed: Int
)

val REALM_STATS = mapOf(
    9  to RealmStats(hp=484,   mp=186,   attack=44,   defense=32,   speed=23),
    8  to RealmStats(hp=1209,  mp=465,   attack=108,  defense=82,   speed=58),
    7  to RealmStats(hp=3143,  mp=1209,  attack=279,  defense=211,  speed=150),
    6  to RealmStats(hp=8223,  mp=3163,  attack=733,  defense=548,  speed=389),
    5  to RealmStats(hp=21766, mp=8371,  attack=1942, defense=1451, speed=1030),
    4  to RealmStats(hp=53204, mp=20463, attack=4749, defense=3547, speed=2519),
    3  to RealmStats(hp=125756,mp=48368, attack=11221,defense=8384, speed=5953),
    2  to RealmStats(hp=280533,mp=107897,attack=25034,defense=18702,speed=13280),
    1  to RealmStats(hp=580404,mp=223236,attack=51791,defense=38694,speed=27475),
    0  to RealmStats(hp=1209195,mp=465075,attack=107897,defense=80613,speed=57240)
)

fun getRealmStats(realm: Int): RealmStats = REALM_STATS[realm] ?: REALM_STATS.getValue(9)
```

**预计算方式**：`RealmConfig.baseXxx × 妖兽固定倍率 × 1.06`，四舍五入取整。

| 属性 | 妖兽固定倍率 | 含1.06总倍率 |
|------|------------|-------------|
| HP | 2.25 | 2.385 |
| MP | 2.25 | 2.385 |
| 攻击 | 2.61 | 2.7666 |
| 防御 | 2.34 | 2.4804 |
| 速度 | 1.44 | 1.5264 |

### 2. GameConfig.kt — 新增 Enemy.RealmStats

在 `GameConfig` 中新增 `Enemy` 对象：

```kotlin
object Enemy {
    data class RealmStats(
        val hp: Int,
        val mp: Int,
        val physicalAttack: Int,
        val magicAttack: Int,
        val physicalDefense: Int,
        val magicDefense: Int,
        val speed: Int
    )

    val REALM_STATS = mapOf(
        9  to RealmStats(hp=426,  mp=156,  physicalAttack=40,  magicAttack=40,  physicalDefense=29,  magicDefense=22,  speed=20),
        8  to RealmStats(hp=1065, mp=390,  physicalAttack=98,  magicAttack=98,  physicalDefense=73,  magicDefense=57,  speed=51),
        7  to RealmStats(hp=2768, mp=1014, physicalAttack=253, magicAttack=253, physicalDefense=187, magicDefense=149, speed=130),
        6  to RealmStats(hp=7241, mp=2652, physicalAttack=663, magicAttack=663, physicalDefense=486, magicDefense=389, speed=339),
        5  to RealmStats(hp=19165,mp=7020, physicalAttack=1755,magicAttack=1755,physicalDefense=1287,magicDefense=1030,speed=898),
        4  to RealmStats(hp=46847,mp=17160,physicalAttack=4290,magicAttack=4290,physicalDefense=3146,magicDefense=2517,speed=2195),
        3  to RealmStats(hp=110729,mp=40560,physicalAttack=10140,magicAttack=10140,physicalDefense=7436,magicDefense=5949,speed=5187),
        2  to RealmStats(hp=247011,mp=90480,physicalAttack=22620,magicAttack=22620,physicalDefense=16588,magicDefense=13270,speed=11571),
        1  to RealmStats(hp=511056,mp=187200,physicalAttack=46800,magicAttack=46800,physicalDefense=34320,magicDefense=27456,speed=23940),
        0  to RealmStats(hp=1064700,mp=390000,physicalAttack=97500,magicAttack=97500,physicalDefense=71500,magicDefense=57200,speed=49875)
    )

    fun getRealmStats(realm: Int): RealmStats = REALM_STATS[realm] ?: REALM_STATS.getValue(9)
}
```

**预计算方式**：`RealmConfig.baseXxx × 敌人固定倍率`，四舍五入取整。人型敌人无 0.06 优势。

| 属性 | 敌人固定倍率 |
|------|------------|
| HP | 2.1 |
| MP | 2.0 |
| 物攻 | 2.5 |
| 法攻 | 2.5 |
| 物防 | 2.2 |
| 法防 | 2.2 |
| 速度 | 1.33 |

### 3. BattleSystem.kt — createBeast() 简化

**修改前**：
```kotlin
val realmConfig = GameConfig.Realm.get(realmIndex)
val layerMult = 1.0 + (realmLayer - 1) * 0.1

val baseHp = (realmConfig.baseHp * 2.25 * layerMult).roundToInt()
val baseMp = (realmConfig.baseMp * 2.25 * layerMult).roundToInt()
val baseAtk = (realmConfig.basePhysicalAttack * 2.61 * layerMult).roundToInt()
val baseDef = (realmConfig.basePhysicalDefense * 2.34 * layerMult).roundToInt()
val baseSpeed = (realmConfig.baseSpeed * 1.44 * layerMult).roundToInt()

val beastAdvantage = 0.06
val hpVariance = -0.2 + Random.nextDouble() * 0.4
val physicalAttackVariance = -0.2 + Random.nextDouble() * 0.4
val magicAttackVariance = -0.2 + Random.nextDouble() * 0.4
val physicalDefenseVariance = -0.2 + Random.nextDouble() * 0.4
val magicDefenseVariance = -0.2 + Random.nextDouble() * 0.4
val speedVariance = -0.2 + Random.nextDouble() * 0.4

val hp = (baseHp * (1.0 + (type.hpMod - 1.0) + hpVariance + beastAdvantage)).toInt()
val mp = (baseMp * (1.0 + (type.hpMod - 1.0) + hpVariance + beastAdvantage)).toInt()
val physicalAttack = (baseAtk * (1.0 + (type.atkMod - 1.0) + physicalAttackVariance + beastAdvantage)).toInt()
val magicAttack = (baseAtk * (1.0 + (type.atkMod - 1.0) + magicAttackVariance + beastAdvantage)).toInt()
val physicalDefense = (baseDef * (1.0 + (type.defMod - 1.0) + physicalDefenseVariance + beastAdvantage)).toInt()
val magicDefense = (baseDef * (1.0 + (type.defMod - 1.0) + magicDefenseVariance + beastAdvantage)).toInt()
val speed = (baseSpeed * (1.0 + (type.speedMod - 1.0) + speedVariance + beastAdvantage)).toInt()
```

**修改后**：
```kotlin
val stats = GameConfig.Beast.getRealmStats(realmIndex)
val layerMult = 1.0 + (realmLayer - 1) * 0.1

val hpVariance = -0.2 + Random.nextDouble() * 0.4
val atkVariance = -0.2 + Random.nextDouble() * 0.4
val defVariance = -0.2 + Random.nextDouble() * 0.4
val speedVariance = -0.2 + Random.nextDouble() * 0.4

val hp = (stats.hp * layerMult * (1.0 + (type.hpMod - 1.0) + hpVariance)).toInt()
val mp = (stats.mp * layerMult * (1.0 + (type.hpMod - 1.0) + hpVariance)).toInt()
val physicalAttack = (stats.attack * layerMult * (1.0 + (type.atkMod - 1.0) + atkVariance)).toInt()
val magicAttack = (stats.attack * layerMult * (1.0 + (type.atkMod - 1.0) + atkVariance)).toInt()
val physicalDefense = (stats.defense * layerMult * (1.0 + (type.defMod - 1.0) + defVariance)).toInt()
val magicDefense = (stats.defense * layerMult * (1.0 + (type.defMod - 1.0) + defVariance)).toInt()
val speed = (stats.speed * layerMult * (1.0 + (type.speedMod - 1.0) + speedVariance)).toInt()
```

**变化点**：
- 移除 `realmConfig` 引用，改用 `GameConfig.Beast.getRealmStats()`
- 移除 `beastAdvantage = 0.06`（已预计算进基础值）
- 方差从 6 个减为 4 个（HP/MP 共用 hpVariance，物攻/法攻共用 atkVariance，物防/法防共用 defVariance）
- 移除 `roundToInt` 导入（不再需要中间四舍五入）
- 公式从 4 层乘法+3 层加法简化为 3 层乘法+2 层加法

### 4. EnemyGenerator.kt — createHumanCombatant() 简化

**修改前**：
```kotlin
val realmConfig = GameConfig.Realm.get(realm)
val layerMult = 1.0 + (realmLayer - 1) * 0.1

val hp = (realmConfig.baseHp * 2.1 * layerMult).roundToInt() + equipmentStats.hp
val mp = (realmConfig.baseMp * 2.0 * layerMult).roundToInt() + equipmentStats.mp
val physicalAttack = (realmConfig.basePhysicalAttack * 2.5 * layerMult).roundToInt() + equipmentStats.physicalAttack
val magicAttack = (realmConfig.baseMagicAttack * 2.5 * layerMult).roundToInt() + equipmentStats.magicAttack
val physicalDefense = (realmConfig.basePhysicalDefense * 2.2 * layerMult).roundToInt() + equipmentStats.physicalDefense
val magicDefense = (realmConfig.baseMagicDefense * 2.2 * layerMult).roundToInt() + equipmentStats.magicDefense
val speed = (realmConfig.baseSpeed * 1.33 * layerMult).roundToInt() + equipmentStats.speed
```

**修改后**：
```kotlin
val stats = GameConfig.Enemy.getRealmStats(realm)
val layerMult = 1.0 + (realmLayer - 1) * 0.1

val hp = (stats.hp * layerMult).toInt() + equipmentStats.hp
val mp = (stats.mp * layerMult).toInt() + equipmentStats.mp
val physicalAttack = (stats.physicalAttack * layerMult).toInt() + equipmentStats.physicalAttack
val magicAttack = (stats.magicAttack * layerMult).toInt() + equipmentStats.magicAttack
val physicalDefense = (stats.physicalDefense * layerMult).toInt() + equipmentStats.physicalDefense
val magicDefense = (stats.magicDefense * layerMult).toInt() + equipmentStats.magicDefense
val speed = (stats.speed * layerMult).toInt() + equipmentStats.speed
```

**变化点**：
- 移除 `realmConfig` 引用，改用 `GameConfig.Enemy.getRealmStats()`
- 移除运行时乘法倍率（已预计算进基础值）
- 移除 `roundToInt` 导入（不再需要）
- 人型敌人无类型修正和随机方差（保持原有逻辑）

## 不变的部分

| 项目 | 说明 |
|------|------|
| 小层倍率 | 保持 `1.0 + (layer - 1) * 0.1`，1层=1.0倍，9层=1.8倍 |
| 妖兽类型修正 | 保持 `type.xxxMod - 1.0` 作为偏移量 |
| 随机方差 | 保持 `-0.2 ~ +0.2` |
| 暴击率 | 保持 `0.05 + realmIndex * 0.01` |
| 人型敌人装备系统 | 保持不变 |
| RealmConfig | 不修改，弟子仍使用原有基础值 |
| 妖兽技能配置 | 不修改 |
| 战斗伤害公式 | 不修改 |

## 数值偏差说明

0.06 固定优势预计算进基础值后，与原公式存在约 1~2% 的数值偏差。

原因：原公式中 0.06 是在最后一步与类型修正做加法叠加（`1 + typeOffset + 0.06`），预计算后 1.06 是与固定倍率做乘法叠加。两种叠加方式在乘法交换律下不完全等价。

示例（金丹7层虎妖，方差=0）：
- 原值：1318 × 2.25 × 1.6 × 1.36 = 6453
- 新值：3143 × 1.6 × 1.3 = 6538
- 偏差：+1.3%

此偏差在可接受范围内，且新公式逻辑更清晰。

## 涉及文件

| 文件 | 变更类型 |
|------|---------|
| `GameConfig.kt` | 新增 Beast.RealmStats、Enemy 对象及配置表 |
| `BattleSystem.kt` | 简化 createBeast() 计算逻辑 |
| `EnemyGenerator.kt` | 简化 createHumanCombatant() 计算逻辑 |
