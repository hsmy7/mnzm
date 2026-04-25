# AI宗门弟子系统重构报告

**版本**: 2.5.24
**日期**: 2026-04-26
**范围**: AI宗门弟子生成、修炼、战斗物品生成

---

## 1. 重构目标

重构AI宗门的弟子设计，使其更符合宗门规模定位，简化日常维护逻辑，将功法和装备的生成推迟到战斗时按需进行。

---

## 2. 需求变更

### 2.1 宗门弟子初始配置

| 宗门规模 | 普通弟子数量 | 普通弟子境界上限 | 精英弟子数量 | 精英弟子境界 |
|---------|------------|----------------|------------|------------|
| 小型宗门 | 20-60 | 化神境以下(realm≤6) | 5 | 化神(realm=5) |
| 中型宗门 | 40-80 | 炼虚境以下(realm≤5) | 5 | 合体(realm=3) |
| 大型宗门 | 40-120 | 合体境以下(realm≤4) | 5 | 大乘(realm=2) |
| 顶级宗门 | 50-120 | 大乘境以下(realm≤3) | 5 | 渡劫(realm=1) |

### 2.2 年度招募

- 所有AI宗门每年获得5名练气一层弟子
- 招募时机：每年1月
- 若超过宗门弟子上限(MAX_AI_DISCIPLES_PER_SECT)，按战力排序截取

### 2.3 修炼方式

- AI弟子每月直接增加修为进度
- 计算方式与玩家弟子一致（使用DiscipleStatCalculator.calculateCultivationSpeed）
- 包含完整的突破逻辑（小境界突破+大境界突破）
- 大境界突破需满足神魂需求

### 2.4 战斗物品生成

- 弟子平时不持有功法和装备
- 进入战斗时自动生成随机功法和装备
- 品阶受境界限制（有最小和最大品阶）
- 功法数量不超过弟子最大功法数
- 装备不超过4件（武器/防具/靴子/饰品各1件）
- 功法熟练度等级随机（入门/小成/大成/圆满）
- 装备孕养等级随机

---

## 3. 修改文件清单

| 文件 | 路径 | 修改类型 |
|------|------|---------|
| AISectDiscipleManager.kt | core/engine/ | 完全重写 |
| AISectAttackManager.kt | core/engine/ | 修改convertToCombatant、calculatePowerScore |
| AICaveTeamGenerator.kt | core/engine/ | 修改convertToAICaveDisciple |
| CultivationService.kt | core/engine/service/ | 修改processAISectOperations |
| WorldMapGenerator.kt | core/engine/ | 修改generateSectLevelAndDisciples、generateDisciplesForLevel |
| build.gradle | app/ | 版本号更新 |
| CHANGELOG.md | 根目录/ | 更新日志 |

---

## 4. 核心逻辑详解

### 4.1 弟子初始化 (AISectDiscipleManager.initializeSectDisciples)

```
输入: sectName, sectLevel
输出: (disciples, sectMaxRealm)

1. 根据sectLevel获取SectLevelConfig
2. 生成normalCount名普通弟子:
   - 境界分布在(normalMaxRealm+1)到9之间
   - 炼气/筑基权重更高(3:2:2:1...)
3. 生成eliteCount名精英弟子，固定eliteRealm境界
4. 若超过MAX_AI_DISCIPLES_PER_SECT，按战力排序截取
```

### 4.2 战斗物品生成 (AISectDiscipleManager.generateBattleItems)

```
输入: disciple
输出: BattleItems(manuals, equipments, nurtures)

1. 根据弟子境界确定:
   - minRarity: 最低品阶(避免高境界配低品阶)
   - maxRarity: 最高品阶(GameConfig.Realm.getMaxRarity)
   - maxManuals: 最大功法数

2. 生成功法:
   - 随机1~maxManuals本
   - 优先选心法(MIND)，其余从攻击/防御中选
   - 熟练度随机: 入门(0-100)/小成(100-200)/大成(200-300)/圆满(300-400)

3. 生成装备:
   - 随机1~4件
   - 从4个槽位中随机选取
   - 优先选取符合境界品阶范围的装备

4. 生成孕养数据:
   - 根据装备品阶确定maxNurtureLevel
   - 随机0~maxLevel的孕养等级
```

### 4.3 每月修炼 (AISectDiscipleManager.processMonthlyCultivation)

```
输入: disciples
输出: updatedDisciples

对每个存活弟子:
1. 计算修炼速度(calculateCultivationSpeed)
   - 灵根加成、悟性加成、天赋加成
   - 无功法/建筑/传道等加成(传空值)
2. 月修为增长 = speed * SECONDS_PER_MONTH
3. 检查突破:
   - 小境界: realmLayer++
   - 大境界: realm--, realmLayer=1
   - 需满足神魂需求
4. 更新寿命(若突破大境界)
```

### 4.4 年度招募 (AISectDiscipleManager.recruitYearlyDisciples)

```
输入: sectName, existingDisciples
输出: updatedDisciples

1. 生成5名练气一层弟子
2. 合并到现有弟子列表
3. 若超过上限，按战力排序截取
```

### 4.5 宗门攻击战力评估 (AISectAttackManager.calculatePowerScore)

```
输入: disciples
输出: powerScore

对每个存活弟子:
  realmPower = (10 - realm) * REALM_BASE
  equipmentPower = avgEquipmentRarity * 2.0 * EQUIPMENT_RARITY
  manualPower = avgManualRarity * (maxManuals/2) * MANUAL_RARITY
  talentPower = sum(talent.rarity * TALENT_RARITY)
  total += realmPower + equipmentPower + manualPower + talentPower
```

---

## 5. 关键数据结构

### 5.1 BattleItems

```kotlin
data class BattleItems(
    val manuals: List<Pair<String, Int>>,        // (manualId, proficiency)
    val equipments: List<Pair<String, EquipmentSlot>>, // (equipmentId, slot)
    val weaponNurture: EquipmentNurtureData,
    val armorNurture: EquipmentNurtureData,
    val bootsNurture: EquipmentNurtureData,
    val accessoryNurture: EquipmentNurtureData
)
```

### 5.2 SectLevelConfig

```kotlin
private data class SectLevelConfig(
    val normalMin: Int,      // 普通弟子最小数量
    val normalMax: Int,      // 普通弟子最大数量
    val normalMaxRealm: Int, // 普通弟子境界上限
    val eliteCount: Int,     // 精英弟子数量
    val eliteRealm: Int,     // 精英弟子境界
    val sectMaxRealm: Int    // 宗门最高境界
)
```

---

## 6. 品阶限制规则

### 6.1 功法/装备最大品阶

| 境界 | 最大品阶 |
|------|---------|
| 炼气(9) | 凡品(1) |
| 筑基(8) | 灵品(2) |
| 金丹(7) | 宝品(3) |
| 元婴(6) | 玄品(4) |
| 化神(5) | 地品(5) |
| 炼虚(4) | 天品(6) |
| 合体(3) | 天品(6) |
| 大乘(2) | 天品(6) |
| 渡劫(1) | 天品(6) |
| 仙人(0) | 天品(6) |

### 6.2 功法/装备最小品阶

| 境界 | 最小品阶 |
|------|---------|
| 炼气-元婴(9-6) | 凡品(1) |
| 化神-炼虚(5-4) | 灵品(2) |
| 合体(3) | 宝品(3) |
| 大乘(2) | 宝品(3) |
| 渡劫(1) | 玄品(4) |
| 仙人(0) | 地品(5) |

### 6.3 最大功法数

| 境界 | 最大功法数 |
|------|----------|
| 炼气-筑基(9-8) | 3 |
| 金丹-元婴(7-6) | 4 |
| 化神-炼虚(5-4) | 5 |
| 合体以下(≤3) | 6 |

---

## 7. 移除的功能

| 功能 | 原因 |
|------|------|
| processManualMasteryGrowth | 战斗时临时生成，无需日常增长 |
| processEquipmentNurture | 战斗时临时生成，无需日常孕养 |
| recruitDisciples(旧版) | 替换为每年固定招募5名 |
| 弟子持久化功法装备数据 | 改为战斗时动态生成 |

---

## 8. 兼容性说明

### 8.1 存档兼容性

- 旧存档中的AI弟子数据仍然有效
- 旧存档中AI弟子已有的功法装备数据会被保留但不使用
- 战斗时会重新生成临时功法装备
- 修炼逻辑变更后，旧存档AI弟子会按新逻辑继续修炼

### 8.2 数据库迁移

- 无需数据库迁移
- 不涉及表结构变更
- 仅修改运行时逻辑

---

## 9. 测试建议

1. **新建游戏测试**: 验证各等级宗门弟子生成数量是否正确
2. **时间推进测试**: 验证每月修炼和每年招募是否正常
3. **战斗测试**: 验证AI弟子进入战斗时是否正确生成功法装备
4. **洞府探索测试**: 验证AI洞府队伍是否正确生成战斗物品
5. **存档加载测试**: 验证旧存档加载后AI弟子行为是否正常

---

## 10. 已知问题与修复

### 10.1 v2.5.24 修复

- **问题**: CultivationService.executePlayerSectBattle中deadAttackerIds/deadDefenderIds引用错误
- **影响**: 玩家宗门被攻击时死亡判定逻辑颠倒
- **修复**: 还原正确的ID引用

- **问题**: AISectAttackManager.decideAttacks中冗余攻击条件检查
- **影响**: 代码冗余，不影响功能
- **修复**: 移除冗余检查（已由allTargets过滤覆盖）

---

## 11. 版本历史

| 版本 | 日期 | 说明 |
|------|------|------|
| 2.5.22 | 2026-04-26 | AI宗门弟子系统重构主体 |
| 2.5.23 | 2026-04-26 | 功法替换UI优化 |
| 2.5.24 | 2026-04-26 | 修复战斗死亡ID引用错误 |
