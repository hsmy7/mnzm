# 降低弟子各境界最大寿命实施计划

## 摘要

仅调整 `GameConfig.kt` 中两个高境界的 `maxAge`：
- 大乘（realm 2）：3000 → 2500
- 渡劫（realm 1）：5000 → 4000

其余境界 `maxAge` 保持当前值不变。

同时彻底移除“突破境界时额外增加寿命”的机制，并直接清理相关代码（而非仅返回 0）：
- 删除 `CultivationCore.getLifespanGainForRealm`
- 删除 `SettlementCoordinator` 中的同名私有函数及调用
- 删除 `DiscipleBreakthroughHandler` 与 `SettlementCoordinator` 突破成功时的寿命增益计算

该变更影响：
- 新创建/招募的大乘、渡劫弟子初始寿命上限
- 弟子衰老死亡判定中对应境界的寿命上限
- 突破成功后不再追加额外寿命（弟子实际可活年限仍受 `maxOf(当前寿命, 境界maxAge, 天赋加成后寿命)` 保护）

已存在存档中弟子的当前 `lifespan` 不会被动回退。

---

## 当前状态分析

### 1. 境界最大寿命配置

文件：`android/core/domain/src/main/java/com/xianxia/sect/core/GameConfig.kt`

`Realm.CONFIGS` 中每个 `RealmConfig` 的 `maxAge` 字段即为该境界弟子的最大寿命基准。

| 境界编号 | 境界名称 | 当前 maxAge | 修改后 maxAge |
|----------|----------|-------------|---------------|
| 9        | 炼气     | 80          | 80（不变）    |
| 8        | 筑基     | 120         | 120（不变）   |
| 7        | 金丹     | 200         | 200（不变）   |
| 6        | 元婴     | 300         | 300（不变）   |
| 5        | 化神     | 500         | 500（不变）   |
| 4        | 炼虚     | 800         | 800（不变）   |
| 3        | 合体     | 1500        | 1500（不变）  |
| 2        | 大乘     | 3000        | **2500**      |
| 1        | 渡劫     | 5000        | **4000**      |
| 0        | 仙人     | 9999        | 9999（不变）  |

### 2. 突破额外寿命机制

文件 1：`android/core/engine/src/main/java/com/xianxia/sect/core/service/CultivationCore.kt`

```kotlin
fun getLifespanGainForRealm(realm: Int): Int {
    return when (realm) {
        8 -> 50
        7 -> 100
        6 -> 200
        5 -> 400
        4 -> 800
        3 -> 1500
        2 -> 3000
        1 -> 5000
        0 -> 10000
        else -> 0
    }
}
```

文件 2：`android/core/engine/src/main/java/com/xianxia/sect/core/domain/settlement/SettlementCoordinator.kt`（私有副本）

```kotlin
private fun getLifespanGainForRealm(realm: Int): Int {
    return when (realm) {
        8 -> 50; 7 -> 100; 6 -> 200; 5 -> 400
        4 -> 800; 3 -> 1500; 2 -> 3000; 1 -> 5000
        0 -> 10000; else -> 0
    }
}
```

调用点：
- `DiscipleBreakthroughHandler.kt:106-112`：实时突破成功后 `newLifespan += cultivationCore.getLifespanGainForRealm(newRealm)`，并叠加天赋 `lifespan` 加成。
- `SettlementCoordinator.kt:699-703`：月结突破成功后 `lifespan = d.lifespan + lifespanGain + extraLifespan`。

### 3. 寿命使用点

- `DiscipleFactory.kt:131`：创建弟子时 `lifespan = (GameConfig.Realm.get(realm).maxAge * (1 + lifespanBonus)).toInt()`
- `DiscipleLifecycleProcessor.kt:64-66`：年度老化时 `maxAge = maxOf(agedDisciple.lifespan, realmMaxAge, talentLifespan)`
- `AISectDiscipleManager.kt`：AI 宗门弟子创建/突破时同样读取 `maxAge`
- `RedeemCodeManager.kt:688`：兑换码弟子寿命也基于 `realmConfig.maxAge`

---

## 拟议修改

### 修改 1：调整大乘、渡劫 maxAge

**文件**：`android/core/domain/src/main/java/com/xianxia/sect/core/GameConfig.kt`

仅修改 `Realm.CONFIGS` 中 realm 2 与 realm 1 两个条目的 `maxAge` 字段，其余字段完全不变。

**修改后片段**：

```kotlin
2 to RealmConfig(2, "大乘", 300000, 280,
    maxAge = 2500, maxLayers = 9,
    baseHp = 117624, baseMp = 45240, basePhysicalAttack = 9048, baseMagicAttack = 9048,
    basePhysicalDefense = 7540, baseMagicDefense = 6032, baseSpeed = 8700),
1 to RealmConfig(1, "渡劫", 1000000, 360,
    maxAge = 4000, maxLayers = 9,
    baseHp = 243360, baseMp = 93600, basePhysicalAttack = 18720, baseMagicAttack = 18720,
    basePhysicalDefense = 15600, baseMagicDefense = 12480, baseSpeed = 18000),
```

### 修改 2：直接清理突破额外寿命机制

#### 2.1 删除 `CultivationCore.getLifespanGainForRealm`

**文件**：`android/core/engine/src/main/java/com/xianxia/sect/core/service/CultivationCore.kt`

删除整个函数（第 138-151 行）。

#### 2.2 删除 `SettlementCoordinator` 中的私有副本

**文件**：`android/core/engine/src/main/java/com/xianxia/sect/core/domain/settlement/SettlementCoordinator.kt`

删除 `private fun getLifespanGainForRealm(realm: Int): Int`（第 893-899 行）。

#### 2.3 清理 `DiscipleBreakthroughHandler` 中的调用

**文件**：`android/core/engine/src/main/java/com/xianxia/sect/core/service/DiscipleBreakthroughHandler.kt`

当前代码（第 46-50 行声明 + 第 97-112 行逻辑）：

```kotlin
var newLifespan = disciple.lifespan
// ...
if (success) {
    // ...
    newLifespan += cultivationCore.getLifespanGainForRealm(newRealm)

    val lifespanTalentBonus = TalentDatabase.calculateTalentEffects(disciple.talentIds)["lifespan"] ?: 0.0
    if (lifespanTalentBonus != 0.0) {
        val extraLifespan = (cultivationCore.getLifespanGainForRealm(newRealm) * lifespanTalentBonus).toInt()
        newLifespan += extraLifespan
    }
}
```

清理后：
- 删除 `var newLifespan = disciple.lifespan`
- 删除成功分支中的 lifespan 增益计算
- `disciple.copy(...)` 中 `lifespan = newLifespan` 改为 `lifespan = disciple.lifespan`

#### 2.4 清理 `SettlementCoordinator` 中的调用

**文件**：`android/core/engine/src/main/java/com/xianxia/sect/core/domain/settlement/SettlementCoordinator.kt`

当前代码（第 699-710 行）：

```kotlin
if (success) {
    // ...
    val lifespanGain = getLifespanGainForRealm(newRealm)
    val lifespanTalentBonus = TalentDatabase.calculateTalentEffects(d.talentIds)["lifespan"] ?: 0.0
    val extraLifespan = if (lifespanTalentBonus != 0.0) {
        (getLifespanGainForRealm(newRealm) * lifespanTalentBonus).toInt()
    } else 0

    d = d.copy(
        cultivation = 0.0,
        realm = newRealm,
        realmLayer = newRealmLayer,
        lifespan = d.lifespan + lifespanGain + extraLifespan
    )
}
```

清理后：

```kotlin
if (success) {
    // ...
    d = d.copy(
        cultivation = 0.0,
        realm = newRealm,
        realmLayer = newRealmLayer,
        lifespan = d.lifespan
    )
}
```

---

## 假设与决策

1. **仅调整大乘、渡劫**：其余境界 `maxAge` 维持原值，与“其余不变化”一致。
2. **彻底清理而非返回 0**：按反馈直接删除函数及调用，保持代码整洁。
3. **不迁移存档**：已存在弟子当前 `lifespan` 不回退，仅新弟子和未来突破受影响。这是最小侵入性方案。
4. **寿命天赋保留**：`lifespan` 天赋仍按百分比影响最终寿命，仅不再对突破增益生效。
5. **丹药/道具延寿保留**：`extendLife` 类效果不受影响，继续按固定数值增加寿命。
6. **AI 宗门弟子同步生效**：`AISectDiscipleManager` 和 `RedeemCodeManager` 均读取 `GameConfig.Realm.get(...).maxAge`，无需单独修改。

---

## 验证步骤

1. **编译检查**
   - 修改后执行 Gradle 编译，确认无语法/类型错误。

2. **单元测试**
   - 运行 `GameConfigTest`：确认境界配置结构、名称、突破概率等未受影响。
   - 运行 `DiscipleFactoryTest`：确认新弟子 `lifespan > 0`。
   - 运行 `ConfigLoaderTest` 与 `RedeemCodeTest`：确认与 `maxAge` 相关的非境界字段未受影响。

3. **数值验证（可选手动检查）**
   - 断言 `GameConfig.Realm.get(2).maxAge == 2500`
   - 断言 `GameConfig.Realm.get(1).maxAge == 4000`
   - 断言其他境界 `maxAge` 保持原值。
   - 确认项目中不再存在 `getLifespanGainForRealm` 引用。

4. **游戏内逻辑抽查**
   - 新招募大乘弟子初始寿命上限约为 `2500 * (1 + lifespanBonus)`。
   - 新招募渡劫弟子初始寿命上限约为 `4000 * (1 + lifespanBonus)`。
   - 弟子突破成功后，`lifespan` 字段不发生变化。
