# 丹药名称优化规格

## Why
当前游戏中的丹药命名存在以下问题：
1. 名称过于直白（如"凡品增力丹"、"灵品聚灵丹"），缺乏修仙题材的意境
2. 同类丹药命名规律不一致，玩家难以从名称猜测效果
3. 缺乏层次感和文化内涵，无法体现修仙世界的深度

通过优化丹药命名，让玩家能够通过名称大致猜出丹药的作用，同时增强修仙题材的沉浸感。

## What Changes
- 重新设计所有丹药的命名体系
- 建立清晰的命名规律：前缀（品级/特性）+ 核心词（功效）+ 后缀（丹药类型）
- 确保名称与效果之间的关联性，让玩家能够直观理解
- **需要更新的系统**：
  - ItemDatabase.kt - 丹药数据定义
  - PillRecipeDatabase.kt - 丹药配方定义
  - 数据库迁移（如需处理旧存档兼容性）

## Impact
- 影响文件：
  - `android/app/src/main/java/com/xianxia/sect/core/data/ItemDatabase.kt` - 丹药数据定义
  - `android/app/src/main/java/com/xianxia/sect/core/data/PillRecipeDatabase.kt` - 丹药配方定义
- 影响玩家体验：更好的沉浸感和可理解性
- **注意**：丹药名称存储在数据库中，新游戏会直接使用新名称；旧存档中的丹药保持原有名称直到被消耗

## ADDED Requirements

### Requirement: 突破丹命名体系
突破丹采用"境界+突破意象"的命名方式，体现突破境界的艰难与丹药的辅助作用。

#### Scenario: 各境界突破丹
| 原名称 | 新名称 | 说明 |
|--------|--------|------|
| 筑基丹 | 筑基丹 | 保持不变（已符合修仙传统） |
| 结金丹 | 凝金丹 | 更强调凝聚金丹的过程 |
| 元婴丹 | 结婴丹 | 更强调结成元婴的动作 |
| 化神丹 | 化神丹 | 保持不变，意境已佳 |
| 炼虚丹 | 破虚丹 | 强调突破虚空之意 |
| 合体丹 | 合道丹 | 强调与道合一 |
| 大乘丹 | 大乘丹 | 保持不变，佛教术语 |
| 渡厄丹 | 渡劫丹 | 更直接表明渡劫用途 |
| 飞升丹 | 登仙丹 | 更雅致的飞升表述 |

### Requirement: 持续修炼加速丹命名体系
采用"X灵丹"系列命名，体现引导、聚集、凝聚灵气的渐进过程。

#### Scenario: 持续修炼加速丹
| ID | 原名称 | 新名称 | 说明 |
|----|--------|--------|------|
| spiritGatheringPill | 聚灵丹 | 引灵丹 | 凡品，引导灵气入体 |
| spiritCondensingPill | 凝气丹 | 聚灵丹 | 灵品，聚集灵气 |
| essenceGatheringPill | 聚精丹 | 凝元丹 | 宝品，凝聚元气 |
| spiritRefiningPill | 炼气丹 | 炼气丹 | 玄品，保持不变 |
| heavenEarthPill | 天地丹 | 混元丹 | 地品，天地混元 |
| celestialPill | 天元丹 | 仙灵丹 | 天品，仙界灵气 |

### Requirement: 立即修为丹命名体系
采用"X元丹"系列命名，体现增加、培养、巩固元气的功效递进。

#### Scenario: 立即修为丹
| ID | 原名称 | 新名称 | 说明 |
|----|--------|--------|------|
| commonCultivationPill | 引气丹 | 增元丹 | 凡品，增加元气 |
| uncommonCultivationPill | 聚气丹 | 培元丹 | 灵品，培养元气 |
| rareCultivationPill | 凝气丹 | 固元丹 | 宝品，巩固元气 |
| epicCultivationPill | 化气丹 | 真元丹 | 玄品，真元之力 |
| legendaryCultivationPill | 合气丹 | 玄元丹 | 地品，玄妙元气 |
| mythicCultivationPill | 仙气丹 | 仙元丹 | 天品，仙界元气 |

### Requirement: 功法熟练度丹命名体系
采用悟道意象命名，体现从入门到洞悉天机的修炼层次。

#### Scenario: 功法熟练度丹
| ID | 原名称 | 新名称 | 说明 |
|----|--------|--------|------|
| commonSkillPill | 参悟丹 | 悟道丹 | 凡品，领悟道法 |
| uncommonSkillPill | 悟道丹 | 明心丹 | 灵品，明心见性 |
| rareSkillPill | 通明丹 | 通玄丹 | 宝品，通晓玄妙 |
| epicSkillPill | 顿悟丹 | 慧灵丹 | 玄品，智慧灵动 |
| legendarySkillPill | 大乘丹 | 道悟丹 | 地品，悟道成真 |
| mythicSkillPill | 造化丹 | 天机丹 | 天品，洞悉天机 |

### Requirement: 延寿丹命名体系
采用寿命意象递进命名，从延寿到永生。

#### Scenario: 延寿丹
| ID | 原名称 | 新名称 | 说明 |
|----|--------|--------|------|
| commonLifePill | 养元丹 | 延寿丹 | 凡品，延长寿命 |
| uncommonLifePill | 延寿丹 | 续命丹 | 灵品，延续生命 |
| rareLifePill | 续命丹 | 长生丹 | 宝品，追求长生 |
| epicLifePill | 长生丹 | 不老丹 | 玄品，青春不老 |
| legendaryLifePill | 不死丹 | 万寿丹 | 地品，万寿无疆 |
| mythicLifePill | 永恒丹 | 永生丹 | 天品，永生不灭 |

### Requirement: 物理攻击丹命名体系
采用动物力量意象递进，从凡兽到神兽。

#### Scenario: 物理攻击丹
| ID | 原名称 | 新名称 | 说明 |
|----|--------|--------|------|
| commonPhysAtkPill | 凡品增力丹 | 虎力丹 | 虎之力 |
| uncommonPhysAtkPill | 灵品增力丹 | 熊力丹 | 熊之力 |
| rarePhysAtkPill | 宝品增力丹 | 龙力丹 | 龙之力 |
| epicPhysAtkPill | 玄品增力丹 | 神力丹 | 神之力 |
| legendaryPhysAtkPill | 地品增力丹 | 霸力丹 | 霸者之力 |
| mythicPhysAtkPill | 天品增力丹 | 天力丹 | 天之力 |

### Requirement: 法术攻击丹命名体系
采用火焰意象递进，从灵火到天火。

#### Scenario: 法术攻击丹
| ID | 原名称 | 新名称 | 说明 |
|----|--------|--------|------|
| commonMagicAtkPill | 凡品聚灵丹 | 灵火丹 | 灵火之力 |
| uncommonMagicAtkPill | 灵品聚灵丹 | 真火丹 | 真火之力 |
| rareMagicAtkPill | 宝品聚灵丹 | 三昧丹 | 三昧真火 |
| epicMagicAtkPill | 玄品聚灵丹 | 玄火丹 | 玄妙之火 |
| legendaryMagicAtkPill | 地品聚灵丹 | 地火丹 | 地脉之火 |
| mythicMagicAtkPill | 天品聚灵丹 | 天火丹 | 天界之火 |

### Requirement: 物理防御丹命名体系
采用防御意象递进，从铁甲到天罡。

#### Scenario: 物理防御丹
| ID | 原名称 | 新名称 | 说明 |
|----|--------|--------|------|
| commonPhysDefPill | 凡品铁壁丹 | 铁甲丹 | 铁甲护体 |
| uncommonPhysDefPill | 灵品铁壁丹 | 铜墙丹 | 铜墙铁壁 |
| rarePhysDefPill | 宝品铁壁丹 | 金刚丹 | 金刚不坏 |
| epicPhysDefPill | 玄品铁壁丹 | 玄盾丹 | 玄妙护盾 |
| legendaryPhysDefPill | 地品铁壁丹 | 地罡丹 | 地煞罡气 |
| mythicPhysDefPill | 天品铁壁丹 | 天罡丹 | 天罡正气 |

### Requirement: 法术防御丹命名体系
采用护盾意象递进，从灵盾到天护。

#### Scenario: 法术防御丹
| ID | 原名称 | 新名称 | 说明 |
|----|--------|--------|------|
| commonMagicDefPill | 凡品护灵丹 | 灵盾丹 | 灵气护盾 |
| uncommonMagicDefPill | 灵品护灵丹 | 法盾丹 | 法力护盾 |
| rareMagicDefPill | 宝品护灵丹 | 神盾丹 | 神力护盾 |
| epicMagicDefPill | 玄品护灵丹 | 玄罡丹 | 玄妙罡气 |
| legendaryMagicDefPill | 地品护灵丹 | 地护丹 | 大地庇护 |
| mythicMagicDefPill | 天品护灵丹 | 天护丹 | 天界庇护 |

### Requirement: 生命值丹命名体系
采用气血意象递进，从气血到天血。

#### Scenario: 生命值丹
| ID | 原名称 | 新名称 | 说明 |
|----|--------|--------|------|
| commonHpPill | 凡品壮体丹 | 气血丹 | 补充气血 |
| uncommonHpPill | 灵品壮体丹 | 血精丹 | 血液精华 |
| rareHpPill | 宝品壮体丹 | 血魂丹 | 血之魂魄 |
| epicHpPill | 玄品壮体丹 | 玄血丹 | 玄妙血液 |
| legendaryHpPill | 地品壮体丹 | 地血丹 | 地脉血气 |
| mythicHpPill | 天品壮体丹 | 天血丹 | 天界血气 |

### Requirement: 灵力值丹命名体系
采用灵气意象递进，从回灵到天灵。

#### Scenario: 灵力值丹（战斗用）
| ID | 原名称 | 新名称 | 说明 |
|----|--------|--------|------|
| commonMpPill | 凡品回灵丹 | 回灵丹 | 保持不变 |
| uncommonMpPill | 灵品回灵丹 | 汇灵丹 | 汇聚灵气 |
| rareMpPill | 宝品回灵丹 | 凝灵丹 | 凝聚灵气 |
| epicMpPill | 玄品回灵丹 | 玄灵丹 | 玄妙灵气 |
| legendaryMpPill | 地品回灵丹 | 地灵丹 | 地脉灵气 |
| mythicMpPill | 天品回灵丹 | 天灵丹 | 天界灵气 |

### Requirement: 速度丹命名体系
采用风意象递进，从疾风到天风。

#### Scenario: 速度丹
| ID | 原名称 | 新名称 | 说明 |
|----|--------|--------|------|
| commonSpeedPill | 凡品疾风丹 | 疾风丹 | 保持不变 |
| uncommonSpeedPill | 灵品疾风丹 | 迅风丹 | 迅捷如风 |
| rareSpeedPill | 宝品疾风丹 | 神风丹 | 神行如风 |
| epicSpeedPill | 玄品疾风丹 | 玄风丹 | 玄妙之风 |
| legendarySpeedPill | 地品疾风丹 | 地风丹 | 地脉之风 |
| mythicSpeedPill | 天品疾风丹 | 天风丹 | 天界之风 |

### Requirement: 武者丹命名体系（物攻+物防）
采用战斗者意象递进，从战体到战神。

#### Scenario: 武者丹
| ID | 原名称 | 新名称 | 说明 |
|----|--------|--------|------|
| commonWarriorPill | 凡品武者丹 | 战体丹 | 战斗体质 |
| uncommonWarriorPill | 灵品武者丹 | 战魂丹 | 战斗魂魄 |
| rareWarriorPill | 宝品武者丹 | 战意丹 | 战斗意志 |
| epicWarriorPill | 玄品武者丹 | 战心丹 | 战斗之心 |
| legendaryWarriorPill | 地品武者丹 | 战圣丹 | 战斗圣者 |
| mythicWarriorPill | 天品武者丹 | 战神丹 | 战斗之神 |

### Requirement: 法士丹命名体系（法攻+法防）
采用法术者意象递进，从法体到法神。

#### Scenario: 法士丹
| ID | 原名称 | 新名称 | 说明 |
|----|--------|--------|------|
| commonMagePill | 凡品法士丹 | 法体丹 | 法术体质 |
| uncommonMagePill | 灵品法士丹 | 法魂丹 | 法术魂魄 |
| rareMagePill | 宝品法士丹 | 法意丹 | 法术意志 |
| epicMagePill | 玄品法士丹 | 法心丹 | 法术之心 |
| legendaryMagePill | 地品法士丹 | 法圣丹 | 法术圣者 |
| mythicMagePill | 天品法士丹 | 法神丹 | 法术之神 |

### Requirement: 生机丹命名体系（生命+灵力）
采用生灵意象递进，从生灵到圣灵。

#### Scenario: 生机丹
| ID | 原名称 | 新名称 | 说明 |
|----|--------|--------|------|
| commonVitalityPill | 凡品生机丹 | 生灵丹 | 生命灵气 |
| uncommonVitalityPill | 灵品生机丹 | 命灵丹 | 命之灵气 |
| rareVitalityPill | 宝品生机丹 | 元灵丹 | 元气灵气 |
| epicVitalityPill | 玄品生机丹 | 真灵丹 | 真元灵气 |
| legendaryVitalityPill | 地品生机丹 | 混灵丹 | 混元灵气 |
| mythicVitalityPill | 天品生机丹 | 圣灵丹 | 圣者灵气 |

### Requirement: 治疗丹-生命恢复命名体系
采用恢复意象递进，从回春到九转还魂。

#### Scenario: 生命恢复丹
| ID | 原名称 | 新名称 | 说明 |
|----|--------|--------|------|
| commonHpPill_heal | 小还丹 | 回春丹 | 恢复青春 |
| uncommonHpPill_heal | 回元丹 | 复元丹 | 复原元气 |
| rareHpPill_heal | 大还丹 | 回生丹 | 起死回生 |
| epicHpPill_heal | 回魂丹 | 还魂丹 | 还魂归体 |
| legendaryHpPill_heal | 涅槃丹 | 涅槃丹 | 保持不变（凤凰涅槃） |
| mythicHpPill_heal | 九转还魂丹 | 九转还魂丹 | 保持不变（经典名称） |

### Requirement: 治疗丹-灵力恢复命名体系
采用恢复意象递进，从补气到九转归元。

#### Scenario: 灵力恢复丹
| ID | 原名称 | 新名称 | 说明 |
|----|--------|--------|------|
| commonMpPill_heal | 小灵丹 | 补气丹 | 补充真气 |
| uncommonMpPill_heal | 回灵丹 | 复灵丹 | 复原灵气 |
| rareMpPill_heal | 大灵丹 | 凝神丹 | 凝聚精神 |
| epicMpPill_heal | 聚魂丹 | 聚魂丹 | 保持不变 |
| legendaryMpPill_heal | 重生丹 | 归元丹 | 回归元气 |
| mythicMpPill_heal | 九转聚灵丹 | 九转归元丹 | 九转归元 |

## MODIFIED Requirements
无

## REMOVED Requirements
无

## 数据库兼容性说明
- 丹药名称存储在 `pills` 表的 `name` 字段中
- 旧存档中的丹药将保持原有名称，直到被消耗
- 新获得的丹药（通过炼丹、购买、掉落等）将使用新名称
- 配方名称存储在 `recipes` 表中，同样需要更新
