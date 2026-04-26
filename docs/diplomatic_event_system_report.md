# 宗门好感度系统重新设计报告

## 版本信息

- **版本号**: 2.5.37
- **日期**: 2026-04-26
- **涉及模块**: 外交系统、月度事件系统、宗门关系系统

---

## 一、改动概览

本次重新设计保留了**月度外交随机事件系统**（16种事件），其余好感度相关改动全部回退到原始版本。

### 1.1 保留的改动

| 改动项 | 说明 |
|-------|------|
| 月度外交随机事件系统 | 16种外交事件，每月3%概率触发随机一种，作用域统一为全部宗门关系 |

### 1.2 回退的改动

| 改动项 | 回退原因 |
|-------|---------|
| 好感度衰减系统重做 | 按用户要求回退 |
| 战斗好感度区分胜负 | 按用户要求回退 |
| AI宗门自动结盟 | 按用户要求回退 |
| 盟约好感度维护 | 按用户要求回退 |
| 交易好感度奖励 | 按用户要求回退 |
| 物品送礼功能 | 按用户要求回退 |
| 解除盟约好感度惩罚 | 按用户要求回退 |

---

## 二、月度外交随机事件系统

### 2.1 设计目标

为宗门关系系统引入随机性，使游戏世界更加动态和不可预测。每对宗门关系每月有概率触发随机外交事件，影响双方好感度。

### 2.2 触发机制

- **触发概率**: 每月每对宗门关系有 3% 概率触发事件
- **事件选择**: 从16种事件中**完全随机**选择一种
- **作用域**: 全部宗门关系（包括玩家与AI、AI与AI）
- **无触发条件**: 不检查好感度范围、阵营、相邻关系、盟约状态等

### 2.3 事件列表

#### 负面事件（7种）

| 事件ID | 事件名称 | 好感度变化 | 事件描述 |
|--------|---------|-----------|---------|
| border_dispute | 边境争端 | -5 | 两宗弟子在边境因修炼资源发生冲突 |
| resource_conflict | 资源争夺 | -8 | 两宗因争夺灵矿资源产生矛盾 |
| disciple_clash | 弟子冲突 | -3 | 两宗弟子在外历练时发生争执 |
| territorial_encroachment | 领地蚕食 | -12 | 一宗暗中蚕食另一宗的势力范围 |
| spy_discovered | 间谍暴露 | -15 | 一宗派出的间谍被另一宗抓获 |
| opposing_alignment_clash | 正邪对立 | -7 | 正道与邪道宗门之间爆发冲突 |
| player_insult_incident | 口角之争 | -4 | 我宗弟子与他宗弟子发生口角 |

#### 正面事件（9种）

| 事件ID | 事件名称 | 好感度变化 | 事件描述 |
|--------|---------|-----------|---------|
| cultural_exchange | 文化交流 | +3 | 两宗弟子互相交流修炼心得 |
| joint_expedition | 联合探险 | +5 | 两宗弟子在秘境中携手合作 |
| mutual_aid | 互助救灾 | +8 | 一宗遭遇灾祸，另一宗伸出援手 |
| alliance_cooperation | 盟友协作 | +2 | 盟约宗门之间加深合作 |
| trade_boom | 贸易繁荣 | +4 | 两宗之间商贸往来频繁 |
| marriage_alliance | 联姻结好 | +15 | 两宗通过弟子联姻加深关系 |
| same_alignment_bond | 同道相惜 | +5 | 正道/邪道宗门之间因立场一致而亲近 |
| player_disciple_encounter | 弟子偶遇 | +2 | 我宗弟子在外偶遇他宗弟子，相谈甚欢 |
| player_escort_mission | 护送之恩 | +6 | 我宗弟子护送了他宗遇险弟子 |

### 2.4 好感度变化范围

- **最大单次减少**: -15（间谍暴露）
- **最大单次增加**: +15（联姻结好）
- **变化边界**: 好感度始终限制在 [0, 100] 范围内

### 2.5 事件通知规则

| 场景 | 通知方式 |
|------|---------|
| 涉及玩家宗门的事件 | 始终通知，格式："事件名：描述（与XX宗关系+X/-X）" |
| AI宗门间的事件 | 仅当好感度变化绝对值 >= 10 时通知 |

---

## 三、代码实现

### 3.1 配置文件

**文件**: [DiplomaticEventConfig.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/config/DiplomaticEventConfig.kt)

```kotlin
object DiplomaticEventConfig {
    data class DiplomaticEventDef(
        val id: String,
        val name: String,
        val description: String,
        val favorChange: Int,
        val isPositive: Boolean = true
    )

    const val MONTHLY_TRIGGER_CHANCE = 0.03

    object Events {
        val ALL_EVENTS: List<DiplomaticEventDef> = listOf(
            BORDER_DISPUTE, RESOURCE_CONFLICT, DISCIPLE_CLASH,
            CULTURAL_EXCHANGE, JOINT_EXPEDITION, MUTUAL_AID,
            ALLIANCE_COOPERATION, TRADE_BOOM,
            TERRITORIAL_ENCROACHMENT, SPY_DISCOVERED,
            MARRIAGE_ALLIANCE, SAME_ALIGNMENT_BOND, OPPOSING_ALIGNMENT_CLASH,
            PLAYER_DISCIPLE_ENCOUNTER, PLAYER_ESCORT_MISSION, PLAYER_INSULT_INCIDENT
        )
    }
}
```

### 3.2 事件处理逻辑

**文件**: [CultivationService.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/service/CultivationService.kt) (processDiplomacyMonthlyEvents)

```kotlin
private fun processDiplomacyMonthlyEvents(year: Int, month: Int) {
    val data = currentGameData
    val playerSect = data.worldMapSects.find { it.isPlayerSect } ?: return
    val playerSectId = playerSect.id
    val updatedRelations = data.sectRelations.toMutableList()
    var relationsChanged = false

    val events = DiplomaticEventConfig.Events.ALL_EVENTS

    for (relation in data.sectRelations) {
        // 每月3%概率触发
        if (Random.nextDouble() >= DiplomaticEventConfig.MONTHLY_TRIGGER_CHANCE) continue

        // 随机选择一种事件
        val eventDef = events.random()
        val involvesPlayer = relation.sectId1 == playerSectId || relation.sectId2 == playerSectId
        val sect1 = data.worldMapSects.find { it.id == relation.sectId1 }
        val sect2 = data.worldMapSects.find { it.id == relation.sectId2 }
        if (sect1 == null || sect2 == null) continue

        // 应用好感度变化
        val favorChange = eventDef.favorChange
        val newFavor = (relation.favor + favorChange)
            .coerceIn(GameConfig.Diplomacy.MIN_FAVOR, GameConfig.Diplomacy.MAX_FAVOR)

        // 更新关系
        val index = updatedRelations.indexOfFirst {
            it.sectId1 == relation.sectId1 && it.sectId2 == relation.sectId2
        }
        if (index >= 0) {
            updatedRelations[index] = updatedRelations[index].copy(favor = newFavor)
            relationsChanged = true
        }

        // 发送事件通知
        // ...
    }

    if (relationsChanged) {
        currentGameData = data.copy(sectRelations = updatedRelations.toList())
    }
}
```

### 3.3 调用时机

月度外交事件在 [CultivationService.processMonthlyEvents()](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/service/CultivationService.kt) 的月度处理流程中被调用：

```
processMonthlyEvents(year, month)
  -> processDiplomacyMonthlyEvents(year, month)  // 外交事件
  -> processFavorDecay(year)                     // 好感度衰减（原始版本）
  -> ...
```

---

## 四、未改动的原始系统

以下好感度相关系统保持原始实现不变：

### 4.1 好感度衰减

- **触发条件**: 仅影响与玩家宗门相关的关系
- **衰减阈值**: 好感度 > 80 时开始衰减
- **衰减规则**: 1年未送礼每年扣1点
- **衰减下限**: 衰减到80为止，不会低于80

### 4.2 战斗好感度变化

| 场景 | 好感度变化 |
|------|-----------|
| AI宗门间战斗（任何结果） | -10 |
| 攻击玩家宗门 | -15 |

### 4.3 灵石送礼

- 每年每个宗门只能送礼一次
- 基于"基础值+百分比"混合模式计算好感度增长
- 好感度上限100

### 4.4 结盟系统

- 结盟最低好感度: 80
- 盟约持续年数: 5年
- 好感度低于80时盟约自动解除
- 解除结盟仅扣除灵石，不扣好感度

---

## 五、涉及文件清单

### 5.1 新增文件

| 文件路径 | 说明 |
|---------|------|
| [DiplomaticEventConfig.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/config/DiplomaticEventConfig.kt) | 外交事件配置（16种事件定义） |

### 5.2 修改文件

| 文件路径 | 修改内容 |
|---------|---------|
| [CultivationService.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/service/CultivationService.kt) | 实现 processDiplomacyMonthlyEvents |
| [build.gradle](file:///c:/Mnzm/XianxiaSectNative/android/app/build.gradle) | 版本号更新 |
| [CHANGELOG.md](file:///c:/Mnzm/XianxiaSectNative/CHANGELOG.md) | 更新日志 |

### 5.3 回退修改的文件（恢复原始状态）

| 文件路径 | 回退内容 |
|---------|---------|
| [DiplomacyService.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/service/DiplomacyService.kt) | 移除 giftItem、回退 dissolveAlliance、回退 calculatePreferenceMultiplier/RejectModifier |
| [EventService.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/service/EventService.kt) | 移除交易好感度奖励 |
| [GameData.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/model/GameData.kt) | 移除 SectDetail 新增字段 |
| [GameConfig.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/GameConfig.kt) | 移除 SAME_ALIGNMENT_BONUS |
| [SerializableSaveData.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/data/serialization/unified/SerializableSaveData.kt) | 移除序列化新增字段 |
| [SerializationModule.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/data/serialization/unified/SerializationModule.kt) | 移除序列化转换新增字段 |

---

## 六、存档兼容性

### 6.1 向前兼容

- 旧存档加载后，外交事件系统立即生效
- 旧存档的 sectRelations 数据结构未改变
- 无需数据迁移

### 6.2 向后兼容

- v2.5.37 存档可在旧版本加载（新增字段有默认值）
- SectDetail 中移除的 tradeFavorCountThisYear 和 tradeFavorLastResetYear 字段不影响旧版本（旧版本不读取这些字段）

---

## 七、测试建议

### 7.1 功能测试

1. **事件触发测试**: 验证每月确实有约3%的关系触发事件
2. **好感度边界测试**: 验证好感度不会超出 [0, 100] 范围
3. **通知测试**: 验证涉及玩家的事件始终通知，AI间大变化事件才通知
4. **多关系测试**: 验证多个关系同时触发事件时的数据一致性

### 7.2 平衡性测试

1. **长期观察**: 运行游戏多年，观察好感度分布是否合理
2. **极端情况**: 验证连续触发负面/正面事件后的好感度状态
3. **玩家体验**: 评估事件频率和幅度对游戏体验的影响

---

## 八、后续扩展点

如需进一步扩展外交系统，可考虑以下方向：

1. **事件连锁**: 某些事件触发后增加/减少其他事件的概率
2. **事件冷却**: 同一对关系连续触发事件的间隔限制
3. **事件条件**: 根据好感度范围、阵营、相邻关系等条件过滤事件
4. **事件选择**: 玩家可选择如何应对事件，不同选择产生不同结果
5. **事件历史**: 记录每对关系的历史事件，影响后续事件概率
