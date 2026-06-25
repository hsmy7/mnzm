# 弟子筛选栏统一化改造

## 背景

筛选栏（灵根、属性、境界）在"选择弟子界面"和"弟子列表界面"之间呈现**底层共享 + 上层分裂**的形态：

- 筛选栏 UI 组件 `SpiritRootAttributeFilterBar` 是统一的
- 状态管理和过滤算法存在两套并行实现：手写状态 + `applyFilters` 和 `DiscipleFilterState` + `DiscipleSelectorDialog`
- 境界选项常量存在两套不一致的定义：A 套（0-9，含仙人和炼虚）与 B 套（1-8，方向相反，缺仙人和炼虚）
- B 套的境界数值映射与领域层 `GameConfig.Realm.CONFIGS` 权威定义不一致，为事实 bug
- `ProductionComponents.kt` 中存在与 `DisciplesTab.kt` 内容完全重复的私有 `REALM_FILTERS`

## 排序规则

### 有属性排序键时

属性降序 → 境界升序（高境界在前）→ realmLayer 降序 → 灵根数升序

### 无属性键但有境界/灵根筛选时

境界升序 → realmLayer 降序 → 灵根数升序

### 无任何筛选时

已关注优先 → 推荐属性降序（若有）→ 境界升序 → realmLayer 降序

若未传入推荐属性，则"已关注优先 → 境界升序 → realmLayer 降序"。

### 关键约束

已关注优先仅在没有筛选时生效。一旦玩家触发任何属性/境界/灵根筛选，已关注不再参与排序。

## 推荐属性映射

| 场景 | 推荐属性 key |
|---|---|
| 灵矿场矿工 | mining |
| 灵矿场执事 | morality |
| 灵植阁弟子 / 灵植长老 | spiritPlanting |
| 外门长老 / 内门长老 | comprehension |
| 问道塔/青云塔传道长老 / 传道师 | teaching |
| 副宗主 | intelligence |
| 纳徒长老 | charm |
| 炼丹炉弟子（主工+储备） / 炼丹长老 | pillRefining |
| 锻造坊弟子（主工+储备） / 天工长老 | artifactRefining |
| 执法长老 / 执法弟子 / 执法堂储备弟子 | intelligence |

## 改动清单

### 算法层

| 文件 | 改动 |
|---|---|
| `feature/.../ui/game/DiscipleFilterUtils.kt` | 新增权威境界常量 `REALM_FILTER_OPTIONS`（基于 `GameConfig.Realm.getName`，0-9 共 10 项）；重写 `applyFilters` 为新排序规则；移除对 `sortedByFollowAttributeAndRealm` 的依赖 |
| `feature/.../ui/game/dialogs/shared/DiscipleFilterState.kt` | 构造时接收 `defaultSortAttribute`，`filtered` 方法委托给 `applyFilters` |

### 统一封装

| 文件 | 改动 |
|---|---|
| `feature/.../ui/game/dialogs/shared/DiscipleSelectorDialog.kt` | 移除硬编码的错误境界常量，改用 `REALM_FILTER_OPTIONS`；`DiscipleSelectorConfig` 新增 `defaultSortAttribute`、`currentId`、`extraAttributesProvider` 三个字段 |

### 境界常量迁移（11 处 import + 2 处删除重复定义）

- **删除** `DisciplesTab.kt` 中的 `REALM_FILTER_OPTIONS` 本地定义
- **删除** `ProductionComponents.kt` 中的私有 `REALM_FILTERS`，两处引用替换为 `REALM_FILTER_OPTIONS`
- **修正** `SPIRIT_MINE_THEME.recommendAttributeText` 由"采矿"改为"道德"，`elderSortComparator` 由 `mining` 改为 `morality`
- 9 个文件的 import 从 `tabs.REALM_FILTER_OPTIONS` 迁移到 `game.REALM_FILTER_OPTIONS`

### 并入统一封装

| 文件 | 改动 |
|---|---|
| `dials/SpiritMineDialog.kt` | 删除 107 行手写 `SpiritMineDeaconSelectionDialog`，代之以 `DiscipleSelectorDialog`；矿工两处补 `defaultSortAttribute = "mining"` |
| `PeakScreenComponents.kt` | `PeakDiscipleSelectionDialog` 增加 `defaultSortAttribute` 参数，撤销内嵌排序，完全委托给 `applyFilters` |
| `dials/WenDaoPeakDialog.kt` | 3 处调用补 `defaultSortAttribute`：外门长老=comprehension，传道长老/传道师=teaching |
| `dials/QingyunPeakDialog.kt` | 3 处调用补 `defaultSortAttribute`：内门长老=comprehension，传道长老/传道师=teaching |
| `dials/AlchemyDialog.kt` | 储备弟子补 `defaultSortAttribute = "pillRefining"` |
| `dials/ForgeDialog.kt` | 储备弟子补 `defaultSortAttribute = "artifactRefining"` |
| `dials/LawEnforcementHallDialog.kt` | 储备弟子补 `defaultSortAttribute = "intelligence"` |

### 清理

- `DisciplesTab.kt`、`HerbGardenDialog.kt` 删除死 import `sortedByFollowAttributeAndRealm`
- `SpiritMineDialog.kt` 删除死 import `SpiritRootAttributeFilterBar`、`getSpiritRootCount`、`applyFilters`

### 测试

| 文件 | 改动 |
|---|---|
| `feature/.../test/.../DiscipleFilterUtilsTest.kt` | 新增 13 个单元测试，覆盖 `applyFilters` 的全部排序分支和过滤逻辑 |

## 未改动的场景

以下场景无推荐属性，默认排序为"已关注优先 → 境界高低"：

巡视楼、任务大厅、血炼池、住所、斥候、攻击弟子、世界地图驻守、关卡详情、藏经阁、联盟、主弟子列表
