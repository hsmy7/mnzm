# Tasks

- [x] Task 1: 创建AI宗门弟子数据模型
  - [x] SubTask 1.1: 创建 `AISectDisciple.kt` 数据类，包含ID、姓名、境界、境界层数、修为、灵根类型、年龄、寿命、战斗属性浮动、天赋列表、功法列表、装备列表、悟性等字段
  - [x] SubTask 1.2: 添加计算属性：境界名称、修为上限、战斗属性计算方法
  - [x] SubTask 1.3: 添加突破概率计算方法
  - [x] SubTask 1.4: 添加修炼速度计算方法：基础速度(5.0) × 灵根加成 × 悟性加成 × 功法加成 × 天赋加成

- [x] Task 2: 修改WorldSect数据结构
  - [x] SubTask 2.1: 在 `GameData.kt` 的 `WorldSect` 数据类中添加 `aiDisciples: List<AISectDisciple>` 字段
  - [x] SubTask 2.2: 添加计算属性 `discipleCountByRealm` 从 `aiDisciples` 自动计算境界分布

- [x] Task 3: 创建AI宗门弟子管理器
  - [x] SubTask 3.1: 创建 `AISectDiscipleManager.kt` 对象
  - [x] SubTask 3.2: 实现 `generateRandomDisciple()` 方法：生成随机AI弟子（包含1-3个天赋、1-5本功法、1-4种装备、随机悟性）
  - [x] SubTask 3.3: 实现 `recruitDisciplesForAllSects()` 方法：为所有AI宗门招募新弟子（统一1-10人）
  - [x] SubTask 3.4: 实现 `processMonthlyCultivation()` 方法：处理月度修炼
    - 修炼速度 = 基础速度(5.0) × 灵根加成 × 悟性加成 × 功法加成 × 天赋加成
    - 月度修为增加 = 修炼速度 × 5 × 30
  - [x] SubTask 3.5: 实现 `processBreakthrough()` 方法：处理突破逻辑
  - [x] SubTask 3.6: 实现 `processAging()` 方法：处理年龄增长和死亡
  - [x] SubTask 3.7: 实现天赋生成方法（1-3个天赋，可能包含负面）
  - [x] SubTask 3.8: 实现功法生成方法（1-5本，品质不超过境界限制）
  - [x] SubTask 3.9: 实现装备生成方法（1-4种，品质不超过境界限制）

- [x] Task 4: 集成到GameEngine
  - [x] SubTask 4.1: 在 `WorldMapGenerator.kt` 初始化AI宗门时生成初始弟子列表
  - [x] SubTask 4.2: 在 `GameEngine.kt` 年度事件中调用AI弟子招募
  - [x] SubTask 4.3: 在 `GameEngine.kt` 月度事件中调用AI弟子修炼和突破
  - [x] SubTask 4.4: 在 `GameEngine.kt` 年度事件中调用AI弟子年龄增长处理

- [x] Task 5: 修改AICaveTeamGenerator
  - [x] SubTask 5.1: 修改 `generateAITeam()` 方法，从宗门真实弟子中选择成员
  - [x] SubTask 5.2: 使用弟子的真实属性、天赋、装备和功法生成战斗数据
  - [x] SubTask 5.3: 处理弟子在战斗中的状态（如死亡后从列表移除）

- [x] Task 6: 更新侦查系统
  - [x] SubTask 6.1: 修改 `updateSectInfo()` 方法，使用真实弟子数据生成侦查信息
  - [x] SubTask 6.2: 确保侦查信息显示正确的境界分布

# Task Dependencies
- [Task 2] depends on [Task 1]
- [Task 3] depends on [Task 1]
- [Task 4] depends on [Task 2, Task 3]
- [Task 5] depends on [Task 1, Task 2]
- [Task 6] depends on [Task 2]
