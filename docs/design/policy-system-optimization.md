# 宗门政策系统优化设计方案

> 生成日期：2026-07-23 | 状态：待评审 | 对标来源：12篇

---

## 一、背景与目标

### 现状问题

当前政策系统仅 7 个布尔开关，每个政策纯正面效果 + 月费消耗，缺乏深度玩法：

- **无策略权衡** — 政策没有负面效果，玩家无脑全开即可，不存在"开还是不开"的决策
- **无政策种类** — 7 个政策混合在同一列表，无分类、无槽位限制、无解锁条件
- **开启消耗定价扁平** — 开启消耗仅有"3000"和"4000"两档（灵矿增产甚至免费），无梯度
- **无政策组合** — 政策间无协同或拮抗，不存在"政策搭配"的优化空间
- **UI 简陋** — 纯 checkbox 列表，无法直观看到政策的正面/负面/状态

### 优化目标

| 维度 | 当前 | 目标 |
|------|------|------|
| 政策数量 | 7 个 | 15-18 个（增 8-11 个新政策） |
| 政策深度 | 纯正面 | 正面+负面双刃剑（每个政策都自带 trade-off） |
| 开启消耗 | 3000/4000/免费三档 | 梯度化 + 按宗门规模动态定价 |
| 槽位系统 | 无限制全开 | 有限槽位，玩家必须在多个政策中取舍 |
| 政策分类 | 无 | 四大类（生产/修行/治安/管理），每类独立槽位 |
| UI 展示 | checkbox | 政策卡牌展示（正面+负面+状态） |

### 行业对标参考

| 游戏 | 系统 | 可借鉴点 |
|------|------|---------|
| **文明6** | 政策卡 + 政体槽位 | 有限槽位迫使策略取舍、政策卡分类（军事/经济/外交/通用） |
| **群星 Stellaris** | 传统树 + 法令系统 | 政策有冷却期、正负面兼顾、完成传统树提供飞升槽 |
| **冰汽时代 Frostpunk** | 法典树 | 选择即代价、不可逆越界机制、道德困境 |
| **RimWorld Ideology** | 模因 + 戒律 | 模因冲突不能共存、正面+负面配套、戒律可自定义 |
| **CK3** | 决议 + 法律 | 资源消耗前置、封臣好感惩罚、20年冷却 |
| **了不起的修仙模拟器** | 分堂专精 | 堂主能力影响加成强度、管理负担机制 |

---

## 二、技术方案

### 2.1 政策槽位系统（核心架构变化）

**设计理念**：借鉴文明6政策卡槽位系统，玩家不能全开所有政策，必须在有限槽位中做取舍。

```
宗门政策槽位（随宗门等级解锁）
├─ 生产槽 × 2（初始1个，宗门Lv.10解锁第2个）
├─ 修行槽 × 2（初始1个，宗门Lv.15解锁第2个）
├─ 治安槽 × 1（初始可用）
├─ 管理槽 × 1（宗门Lv.5解锁）
└─ 通用槽 × 1（宗门Lv.20解锁）
总计：7 个槽位
```

**槽位锁定逻辑**：
- 每个政策属于一个类别（Category），同类政策竞争同一类槽位
- 切换政策无需成本，但每月结算时才生效改（减少无脑反复切换）
- 被切换下来的政策进入 1 个月冷却，期间不能重新激活

**宗门等级解锁槽位**：

| 宗门等级 | 解锁内容 | 累计槽位 |
|---------|---------|---------|
| 1 | 生产槽×1 + 修行槽×1 + 治安槽×1 | 3 |
| 5 | 管理槽×1 | 4 |
| 10 | 生产槽×2 | 5 |
| 15 | 修行槽×2 | 6 |
| 20 | 通用槽×1 | 7 |

### 2.2 政策数据结构

```kotlin
/** 政策分类 */
enum class PolicyCategory(val displayName: String) {
    PRODUCTION("生产"),   // 矿产、灵田、炼丹、锻造
    CULTIVATION("修行"),  // 修炼、突破、功法
    SECURITY("治安"),     // 执法、防务
    ADMIN("管理")         // 招募、人事
}

/** 政策唯一标识 */
enum class PolicyId(val displayName: String) {
    // ── 现存政策（调整后） ──
    SPIRIT_MINE_BOOST("灵矿增产"),
    ALCHEMY_INCENTIVE("丹道激励"),
    FORGE_INCENTIVE("锻造激励"),
    HERB_CULTIVATION("灵药培育"),
    CULTIVATION_SUBSIDY("修行津贴"),
    MANUAL_RESEARCH("功法研习"),
    ENHANCED_SECURITY("增强治安"),

    // ── 新增政策 ──
    OPEN_RECRUITMENT("广纳门徒"),
    ELITE_FOCUS("精英策略"),
    ASCETIC_TRAINING("苦修令"),
    CLOSED_DOOR_SUBSIDY("闭关资助"),
    DAO_DISCOURSE("论道大会"),
    CURFEW("宵禁"),
    REWARD_PUNISH("赏善罚恶"),
    STRICT_TRAINING("严苛训练"),
    RELAXED_MGMT("松弛管理"),
    SPIRIT_SPRING("灵泉灌溉"),
    FRUGALITY("开源节流")
}

/** 政策激活消耗 */
data class PolicyActivationCost(
    val spiritStones: Long = 0,      // 一次性灵石消耗
    val buildingRequired: String? = null,  // 需要建筑
    val sectLevelRequired: Int = 1       // 需要宗门等级
)

/** 政策效果（正面+负面） */
data class PolicyEffect(
    val positive: String,     // 正面效果描述
    val negative: String,     // 负面效果描述
    val positiveValue: Double,  // 正面数值
    val negativeValue: Double   // 负面数值
)

/** 政策定义 */
data class PolicyDef(
    val id: PolicyId,
    val category: PolicyCategory,
    val displayName: String,
    val description: String,        // 一句话描述
    val activationCost: PolicyActivationCost,
    val monthlyCost: Long,          // 月消耗灵石
    val effects: PolicyEffect,
    val conflictsWith: Set<PolicyId> = emptySet(), // 冲突政策
    val synergizesWith: Set<PolicyId> = emptySet() // 协同政策（同时激活有额外效果）
)
```

**新增 `SectPolicies` 字段**：

```kotlin
data class SectPolicies(
    // 现有 7 个政策（不变）
    val spiritMineBoost: Boolean = false,
    val enhancedSecurity: Boolean = false,
    val alchemyIncentive: Boolean = false,
    val forgeIncentive: Boolean = false,
    val herbCultivation: Boolean = false,
    val cultivationSubsidy: Boolean = false,
    val manualResearch: Boolean = false,

    // 新增 11 个政策
    val openRecruitment: Boolean = false,
    val eliteFocus: Boolean = false,
    val asceticTraining: Boolean = false,
    val closedDoorSubsidy: Boolean = false,
    val daoDiscourse: Boolean = false,
    val curfew: Boolean = false,
    val rewardPunish: Boolean = false,
    val strictTraining: Boolean = false,
    val relaxedMgmt: Boolean = false,
    val spiritSpring: Boolean = false,
    val frugality: Boolean = false,

    // 现有自动分配字段（不变）
    val autoPlant: Boolean = false,
    val autoAlchemy: Boolean = false,
    val autoForge: Boolean = false,
    val autoMineFocused: Boolean = false,
    val autoMineRootCounts: List<Int> = emptyList(),
    // ...（原有字段保留不变）
)
```

### 2.3 完整政策表

#### 现存政策调整

| PolicyId | 分类 | 槽位 | 开启消耗 | 月消耗 | 正面效果 | 负面效果 | 冲突 |
|----------|------|------|---------|-------|---------|---------|------|
| 灵矿增产 | 生产 | 生产槽 | 免费→**1000** | 0 | 灵石产出+20% | 采矿弟子忠诚-1/月 | — |
| 丹道激励 | 生产 | 生产槽 | 3000→**5000** | 3000 | 炼丹成功率+10% | **炼丹材料消耗+10%** | — |
| 锻造激励 | 生产 | 生产槽 | 3000→**5000** | 3000 | 锻造成功率+10% | **锻造材料消耗+10%** | — |
| 灵药培育 | 生产 | 生产槽 | 3000→**5000** | 3000 | 灵药生长速度+20% | **灵田维护灵石+20%** | — |
| 修行津贴 | 修行 | 修行槽 | 4000→**8000** | 4000 | 化神下修炼+15% | **宗门灵石储备-5%/月** | 苦修令 |
| 功法研习 | 修行 | 修行槽 | 4000→**8000** | 4000 | 功法修炼速度+20% | **弟子参悟消耗+10%** | — |
| 增强治安 | 治安 | 治安槽 | 3000→**5000** | 3000 | 抓捕率+20% | **弟子忠诚-1/月** | 松弛管理 |

> **变化**：所有旧政策新增负面效果（粗体标记），开启消耗整体上调，灵矿增产新增开启消耗。

#### 新增政策

| PolicyId | 分类 | 开启消耗 | 月消耗 | 正面效果 | 负面效果 | 冲突 |
|----------|------|---------|-------|---------|---------|------|
| **广纳门徒** | 管理 | 3000 | 2000 | 招募周期-30% | 新弟子平均灵根数+0.5（资质下降） | 精英策略 |
| **精英策略** | 管理 | 6000 | 3000 | 新弟子灵根数-0.8（资质上升） | 招募周期+50%，每月消耗+3000 | 广纳门徒 |
| **苦修令** | 修行 | 5000 | 2000 | 修炼速度+25% | 忠诚-2/月，弟子满意度-10% | 修行津贴 |
| **闭关资助** | 修行 | 10000 | 5000 | 突破概率+10% | 突破失败惩罚+（损失更多修为） | — |
| **论道大会** | 修行 | 8000 | 3000 | 全体修炼+5%（有冷却，3月可触发一次事件额外加成） | 每月消耗3000，冷却期内无效果 | — |
| **宵禁** | 治安 | 3000 | 1000 | 治安事件率-30%，弟子叛逃率-20% | 弟子忠诚-1/月 | 赏善罚恶 |
| **赏善罚恶** | 治安 | 5000 | 3000 | 执法效率+30%，忠诚低弟子自动触发改造 | 管理消耗增加（执事弟子占用） | 宵禁 |
| **严苛训练** | 管理 | 4000 | 2000 | 战斗修炼速度+30% | 忠诚-3/月 | 松弛管理 |
| **松弛管理** | 管理 | 2000 | 1000 | 忠诚+2/月 | 生产效率-10%，治安效率-10% | 严苛训练/增强治安 |
| **灵泉灌溉** | 生产 | 6000 | 2000 | 灵田产量+15%，灵药生长+10% | 每月消耗2000灵石 | — |
| **开源节流** | 生产 | 4000 | 1000 | 建筑消耗材料-20% | 灵石产出-10% | — |

### 2.4 冲突/协同机制

**互斥政策**（不能同时激活）：

| 互斥对 | 原因 |
|--------|------|
| 广纳门徒 ↔ 精英策略 | 招募方向相反 |
| 苦修令 ↔ 修行津贴 | 一严一宽，修行理念冲突 |
| 宵禁 ↔ 赏善罚恶 | 一禁一导，治安路线冲突 |
| 严苛训练 ↔ 松弛管理 | 管理风格冲突 |
| 增强治安 ↔ 松弛管理 | 严格执法与放松管理矛盾 |

**协同政策**（同时激活触发额外效果）：

| 协同组合 | 额外效果 |
|---------|---------|
| 灵矿增产 + 开源节流 | 建筑消耗材料额外-10%（叠加） |
| 丹道激励 + 灵药培育 | 炼丹时灵药消耗-15%（产业链协同） |
| 宵禁 + 严苛训练 | 忠诚度衰减从-4/月降为-2/月（秩序互补） |
| 论道大会 + 修行津贴 | 论道大会冷却-1个月，修炼加成+5% |
| 精英策略 + 闭关资助 | 精英弟子突破概率额外+5% |
| 赏善罚恶 + 松弛管理 | 忠诚+3/月，治安效率不降低 |

### 2.5 开启消耗动态定价

开启消耗不再固定，而是基于宗门当前状态动态调整：

```kotlin
fun calculateActivationCost(baseCost: Long, gameData: GameData): Long {
    // 基础消耗
    var cost = baseCost

    // 宗门等级放大（等级越高，改革成本越大）
    val sectLevel = gameData.sectBuildingLevels.values.maxOrNull() ?: 1
    cost = (cost * (1.0 + (sectLevel - 1) * 0.1)).toLong()

    // 弟子人数放大（人越多，推行新政策的阻力越大）
    val discipleCount = maxOf(1, gameData.discipleCount)
    cost = (cost * (1.0 + (discipleCount - 10) * 0.01)).toLong()

    return cost.coerceAtMost(baseCost * 5)
}
```

### 2.6 UI 设计

取消当前简单 checkbox 列表，改为**政策卡牌面板**：

```
┌─────────────────────────────────────┐
│  【宗门政策】                        │
│                                      │
│  ┌─ 生产槽（2/2）─────────────────┐ │
│  │ ┌──────────┐ ┌──────────┐     │ │
│  │ │灵矿增产  │ │丹道激励  │     │ │
│  │ │✅+20%产出│ │✅+10%成功│     │ │
│  │ │❌-1忠诚  │ │❌+10%消耗│     │ │
│  │ │ 已激活   │ │ 已激活   │     │ │
│  │ └──────────┘ └──────────┘     │ │
│  │ ┌──────────┐ ┌──────────┐     │ │
│  │ │灵药培育  │ │灵泉灌溉  │     │ │
│  │ │ 未激活   │ │ 未激活   │     │ │
│  │ │ +20%生长 │ │ +15%产量 │     │ │
│  │ │ ❌+20%维护│ │ ❌2千/月 │     │ │
│  │ │ 开启:5千 │ │ 开启:6千 │     │ │
│  │ └──────────┘ └──────────┘     │ │
│  └────────────────────────────────┘ │
│                                      │
│  ┌─ 修行槽（1/2）─────────────────┐ │
│  │ ┌──────────┐ ┌──────────┐     │ │
│  │ │修行津贴  │ │苦修令    │     │ │
│  │ │✅+15%    │ │ 互斥冲突!│     │ │
│  │ │❌5%储备  │ │ (与津贴) │     │ │
│  │ │ 已激活   │ │         │     │ │
│  │ └──────────┘ └──────────┘     │ │
│  └────────────────────────────────┘ │
│                                      │
│  ┌─ 治安槽（1/1）─────────────────┐ │
│  │ ┌──────────┐                   │ │
│  │ │增强治安  │                   │ │
│  │ │✅+20%抓捕│                   │ │
│  │ │❌-1忠诚  │                   │ │
│  │ │ 已激活   │                   │ │
│  │ └──────────┘                   │ │
│  └────────────────────────────────┘ │
│                                      │
│  总月消耗：13000 灵石               │
└─────────────────────────────────────┘
```

**UI 交互规范**：
- 每张政策卡显示：名称、正面效果（绿色）、负面效果（红色）、激活状态、消耗
- 点击未激活的政策 → 弹出确认对话框（展示开启消耗+月消耗+效果详情）
- 点击已激活的政策 → 询问是否停用（无返还开启消耗）
- 互斥政策以红色边框+冲突提示标记，点击时提示哪个冲突政策需先停用
- 槽位满时其他政策灰显，hover 提示"生产槽已满"

---

## 三、影响范围清单

### 3.1 新增/修改文件

| 文件路径 | 变更类型 | 变更说明 |
|---------|---------|---------|
| `core/domain/.../model/GameData.kt` | 修改 | `SectPolicies` 新增 11 个 bool 字段 |
| `core/domain/.../model/domain/SectPolicyState.kt` | 修改 | `SectPolicyDomainState` 同步新增字段 |
| `core/domain/.../GameConfig.kt` | 修改 | `PolicyConfig` 新增 11 组常量（消耗/效果值/名称） |
| `core/domain/.../model/SectPolicyStateEntity.kt` | 修改 | Room Entity 同步新增字段 |
| `core/engine/.../model/PolicyDef.kt` | **新增** | 政策定义数据类（PolicyDef/Category/Effect） |
| `core/engine/.../model/PolicyRegistry.kt` | **新增** | 政策注册表（所有政策定义的集中管理） |
| `core/engine/.../usecase/SectPolicyToggleUseCase.kt` | **重写** | 改为数据驱动（基于 PolicyRegistry），消除重复代码 |
| `core/engine/.../service/CultivationSettlement.kt` | 修改 | `processPolicyCosts` 扩展到所有新政策 |
| `core/engine/.../service/CultivationRateCalculator.kt` | 修改 | 新增政策效果叠加（苦修令/闭关资助/论道大会） |
| `core/engine/.../service/ProductionProcessor.kt` | 修改 | 新增政策效果叠加（灵泉灌溉/开源节流/松弛管理） |
| `core/engine/.../service/LawEnforcementProcessor.kt` | 修改 | 新增政策效果（宵禁/赏善罚恶） |
| `core/engine/.../service/RecruitmentService.kt` | 修改 | 新增政策效果（广纳门徒/精英策略） |
| `core/engine/.../GameEngineCore.kt` | 修改 | 月度 tick 新增槽位切换生效逻辑 |
| `core/data/.../SectPolicyStateDao.kt` | 修改 | Room DAO 同步 |
| `core/data/.../StorageEngine.kt` | 修改 | 存档/读档同步 |
| `core/data/.../Migration_XX_XX.kt` | **新增** | Room Migration（新增字段） |
| `feature/game/.../dialog/TianshuHallDialog.kt` | **重写** | 政策卡牌面板 UI |
| `feature/game/.../SectViewModel.kt` | 修改 | 适配新的 UseCase API |
| `app/.../state/DomainStateProvider.kt` | 修改 | 同步新增字段 |

### 3.2 兼容性分析

| 维度 | 说明 |
|------|------|
| **存档兼容** | `SectPolicies` 新增 11 个 bool 字段，Room 用 `ALTER TABLE ADD COLUMN` Migration（默认值 false） |
| **向前兼容** | 旧存档加载时新字段默认 false = 政策未激活，不破坏任何逻辑 |
| **序列化** | `SectPolicies` 已标 `@Serializable`，新增字段加 `@EncodeDefault` 保证旧存档反序列化正常 |
| **UI 兼容** | 旧存档首次打开政策界面时展示所有新政策为"未激活"状态，无异常 |

---

## 四、测试方案

### 4.1 单元测试

| 测试类 | 覆盖内容 |
|--------|---------|
| `SectPolicyPureLogicTest` | 所有政策效果计算的纯逻辑验证（正面值/负面值/协同效果） |
| `SectPolicyToggleUseCaseTest` | 切换政策（开启/关闭/互斥冲突/槽位限制/消耗检查） |
| `SectPolicyCostTest` | 动态开启消耗计算、月消耗扣除 |
| `SectPolicySlotTest` | 槽位限制逻辑（同类槽位满不能激活、通用槽位可用性） |
| `SectPolicySynergyTest` | 协同组合触发额外效果 |

### 4.2 对抗性审查要点

| Agent | 审查点 |
|-------|--------|
| 边界狂魔 | 开启消耗为 0 时行为、槽位满时切换逻辑、互斥政策同时激活防护 |
| 状态破坏者 | 连续快速切换政策、切换中存档/读档、结算月变时切换 |
| 数据篡改者 | 存档中手动编辑政策字段为全 true 读档、跳过开启消耗直接激活 |

---

## 五、风险评估与兜底

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| 政策数量暴增（7→18）导致 UI 拥挤 | 中等 | 分类卡牌布局 + 分页/滚动，每页只展示一个类别 |
| 负面效果让玩家不适 | 中等 | 首次展示时明确标注"双刃剑"，数字使用红色、❌ 标记 |
| 槽位限制让玩家感觉"被削弱" | 中高 | 引导提示：槽位随宗门等级解锁，初期 3 个槽位足够 |
| 启用消耗动态定价使玩家困惑 | 低 | 确认对话框展示明细（基础价 × 等级系数 × 人数系数 = 最终价） |

---

## 六、实施步骤

```
Phase 1 — 数据层（预估 1 天）
├─ PolicyDef + PolicyRegistry 新增
├─ GameData.SectPolicies 新增 11 字段
├─ Room Migration
└─ DomainStateProvider 同步

Phase 2 — 逻辑层（预估 1.5 天）
├─ SectPolicyToggleUseCase 重写（数据驱动）
├─ 槽位系统 + 互斥/协同逻辑
├─ 动态开启消耗计算
├─ 各 Service 政策效果叠加
└─ 月度结算扩展

Phase 3 — UI 层（预估 1 天）
├─ 政策卡牌面板重写
├─ 开启确认对话框
├─ 冲突/槽位满提示
└─ ViewModel 适配

Phase 4 — 测试（预估 0.5 天）
├─ 单元测试
├─ 对抗性审查
└─ 兼容性验证
```

---

## 附录：行业来源清单

| # | 来源 | 等级 | 核心借鉴 |
|---|------|------|---------|
| 1 | [文明6政策卡系统 - 小红书分析](https://www.xiaohongshu.com/discovery/item/679449bb000000002900ee51) | B | 政策卡分类+有限槽位迫使策略取舍 |
| 2 | [文明6政府与政策 - IGN Guide](https://rc.www.ign.com/wikis/civilization-6/Governments_and_Policies) | A | 政体切换冷却期、槽位类型配置 |
| 3 | [群星政策系统 - Paradox Wiki](https://stellaris.paradoxwikis.com/index.php?title=Policies&diff=51065&oldid=49792) | S | 政策修改冷却+轨道轰炸三档选择（权衡） |
| 4 | [群星传统树 3.1版 - Bilibili](https://www.bilibili.com/opus/558153595938257604) | B | 传统树开门+关门加成（政策升级路径） |
| 5 | [冰汽时代法典树 - 游戏狂](https://gamemad.com/guide/65077) | B | 选择即代价、不可逆越界机制 |
| 6 | [RimWorld Ideology DLC 官方](https://rimworldgame.com/ideology/) | S | 模因冲突/协同、戒律正面+负面配套 |
| 7 | [RimWorld Ideology 中文维基](https://rimworld.huijiwiki.com/wiki/%E6%96%87%E5%8C%96) | B | 模因互斥不能共存、戒律详细设计 |
| 8 | [CK3 决议系统 - Paradox Wiki](https://ck3.paradoxwikis.com/index.php?title=Resolutions) | S | 资源消耗前置、封臣好感惩罚、冷却机制 |
| 9 | [CK3 法律系统 - Paradox Wiki](https://ck3.parawikis.com/zh-hk/Laws) | B | 权威等级提升的正面+封臣好感负面 |
| 10 | [了不起的修仙模拟器 门派结构攻略](https://playgame.wiki/xxmnq/gonglue/1e74407abf) | B | 堂主能力影响加成、管理负担机制 |
| 11 | [鬼谷八荒宗门管理 - 9Game](https://www.9game.cn/guigubahuang1/10723480.html) | B | 五堂分工、安定度/繁荣度多维度管理 |
| 12 | [国家等级系统设计 - TapTap](https://www.taptap.cn/moment/709508091170784917) | B | 政策等级解锁槽位、消耗递增模式 |
